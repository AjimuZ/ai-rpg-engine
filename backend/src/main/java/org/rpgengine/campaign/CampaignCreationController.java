package org.rpgengine.campaign;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.rpgengine.commands.CampaignCommandJson;
import org.rpgengine.commands.OperationAdmissionFailure;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.identity.LocalHttpAdmission;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter for campaign creation, durable command admission and status reads.
 * Owner context comes only from local admission; byte-bounded input enters the atomic
 * services, and detached public identities/status leave them. Admission does not execute
 * gameplay: rule resolution and public receipt projection belong to later command
 * work. Failures expose stable codes rather than SQL, pack text or exception messages.
 */
@RestController
public class CampaignCreationController {
    private static final int MAX_BYTES = 16_384;
    private final CampaignCreationService service;
    private final OperationAdmissionService admissions;
    private final CampaignCommandJson commandDecoder = new CampaignCommandJson();
    private final CampaignCreationJson decoder = new CampaignCreationJson();

    /** Require both durable services; missing infrastructure fails application construction. */
    public CampaignCreationController(CampaignCreationService service, OperationAdmissionService admissions) {
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.admissions = java.util.Objects.requireNonNull(admissions, "admissions");
    }

    /**
     * Create/retry one campaign from admitted UTF-8 JSON. Read at most 16 KiB plus one
     * sentinel byte even for chunked input; any failure leaves transaction rollback to the
     * service. A lost response is safely retried with the same request identity and payload.
     */
    @PostMapping("/api/v1/campaigns")
    public ResponseEntity<?> create(HttpServletRequest request) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            if (request.getContentLengthLong() > MAX_BYTES) {
                return error(413, "CREATION_BODY_TOO_LARGE");
            }
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                return error(413, "CREATION_BODY_TOO_LARGE");
            }
            var input = decoder.decode(bytes);
            return ResponseEntity.status(201).body(service.create(owner, input.adventureId(), input.request()));
        } catch (CampaignCreationJson.Malformed | IOException failure) {
            return error(400, "CREATION_INPUT_MALFORMED");
        } catch (CampaignCreationFailure failure) {
            return failure.kind() == CampaignCreationFailure.Kind.SCOPE_UNAVAILABLE
                    ? error(404, "CREATION_SCOPE_UNAVAILABLE") : error(409, "CREATION_REQUEST_CONFLICT");
        } catch (IllegalArgumentException failure) {
            return error(422, "CREATION_CHOICES_INVALID");
        } catch (TransientDataAccessException failure) {
            return error(409, "CREATION_BUSY");
        } catch (DataAccessException | TransactionException failure) {
            return error(503, "CREATION_STORAGE_UNAVAILABLE");
        } catch (IllegalStateException failure) {
            // Corrupt sealed bytes or unsupported stored mechanics must never appear as a successful save.
            return error(503, "CREATION_CONTENT_UNAVAILABLE");
        }
    }

    /**
     * Admit or replay one command. Reads at most 16 KiB plus one sentinel byte, also for
     * chunked input, so the decoder never sees an unbounded body.
     *
     * @param request admitted HTTP request carrying the owner attribute and JSON body
     * @param campaignId canonical campaign UUID from the path
     * @param adventureId canonical adventure UUID that must own the campaign
     * @return 202 new admission, 200 replay, or a fixed error code
     */
    @PostMapping("/api/v1/campaigns/{campaignId}/commands")
    public ResponseEntity<?> submit(HttpServletRequest request, @PathVariable String campaignId,
            @RequestParam(required = false) String adventureId) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            UUID campaign = CampaignCommandJson.uuid(campaignId);
            UUID adventure = CampaignCommandJson.uuid(adventureId);
            if (request.getContentLengthLong() > MAX_BYTES) {
                return error(413, "COMMAND_BODY_TOO_LARGE");
            }
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                return error(413, "COMMAND_BODY_TOO_LARGE");
            }
            var admission = admissions.submit(owner, adventure, campaign, commandDecoder.decode(bytes));
            return ResponseEntity.status(admission.replayed() ? 200 : 202)
                    .body(body(admission.operation(), adventure, admission.replayed()));
        } catch (CampaignCommandJson.Malformed | IOException failure) {
            return error(400, "COMMAND_INPUT_MALFORMED");
        } catch (OperationAdmissionFailure failure) {
            return failure(failure);
        } catch (IllegalArgumentException failure) {
            // Structurally valid JSON can still exceed canonical depth/size or numeric
            // normalization limits. The service applies those bounds before any writes.
            return error(422, "COMMAND_INVALID");
        } catch (TransientDataAccessException failure) {
            // Lock or statement timeout: the campaign is effectively busy; nothing was admitted.
            return error(409, "COMMAND_CAMPAIGN_BUSY");
        } catch (DataAccessException | TransactionException | IllegalStateException failure) {
            return error(503, "COMMAND_STORAGE_UNAVAILABLE");
        }
    }

    /**
     * Read one operation's current status; the safe reconnect path after a lost response.
     *
     * @param request admitted HTTP request carrying the owner attribute
     * @param campaignId canonical campaign UUID
     * @param operationId canonical operation UUID
     * @param adventureId canonical adventure UUID that must own the campaign
     * @return 200 with the operation view, 404 for missing or foreign identities
     */
    @GetMapping("/api/v1/campaigns/{campaignId}/operations/{operationId}")
    public ResponseEntity<?> status(HttpServletRequest request, @PathVariable String campaignId,
            @PathVariable String operationId, @RequestParam(required = false) String adventureId) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            UUID campaign = CampaignCommandJson.uuid(campaignId);
            UUID operation = CampaignCommandJson.uuid(operationId);
            UUID adventure = CampaignCommandJson.uuid(adventureId);
            return ResponseEntity.ok(body(admissions.status(owner, adventure, campaign, operation), adventure, false));
        } catch (CampaignCommandJson.Malformed failure) {
            return error(400, "COMMAND_INPUT_MALFORMED");
        } catch (OperationAdmissionFailure failure) {
            return failure(failure);
        } catch (DataAccessException | TransactionException | IllegalStateException failure) {
            return error(503, "COMMAND_STORAGE_UNAVAILABLE");
        }
    }

    /** Map an expected admission outcome to its stable public status and code. */
    private static ResponseEntity<Map<String, String>> failure(OperationAdmissionFailure failure) {
        return switch (failure.kind()) {
            case SCOPE_UNAVAILABLE -> error(404, "COMMAND_SCOPE_UNAVAILABLE");
            case REQUEST_CONFLICT -> error(409, "COMMAND_REQUEST_CONFLICT");
            case STALE_REVISION -> error(409, "COMMAND_STALE_REVISION");
            case CAMPAIGN_BUSY -> error(409, "COMMAND_CAMPAIGN_BUSY");
            case ADMISSION_LIMIT -> error(429, "COMMAND_ADMISSION_LIMIT");
            case INVALID_COMMAND -> error(422, "COMMAND_INVALID");
            case MODE_ILLEGAL -> error(422, "COMMAND_MODE_ILLEGAL");
            case DECISION_UNAVAILABLE -> error(422, "COMMAND_DECISION_UNAVAILABLE");
            case ACTOR_UNAVAILABLE -> error(422, "COMMAND_ACTOR_UNAVAILABLE");
        };
    }

    /**
     * Build the owner-visible operation body with a relative status query reference.
     * Only server-generated identities are echoed, never caller-supplied text.
     */
    private static Map<String, Object> body(OperationAdmissionService.OperationView view, UUID adventure,
            boolean replayed) {
        var body = new LinkedHashMap<String, Object>();
        body.put("operationId", view.operationId().toString());
        body.put("campaignId", view.campaignId().toString());
        body.put("commandType", view.commandType());
        body.put("status", view.status().name());
        body.put("baseRevision", view.baseRevision());
        body.put("committedRevision", view.committedRevision());
        body.put("operationVersion", view.operationVersion());
        body.put("replayed", replayed);
        body.put("createdAt", view.createdAt().toString());
        body.put("updatedAt", view.updatedAt().toString());
        body.put("query", "/api/v1/campaigns/" + view.campaignId() + "/operations/" + view.operationId()
                + "?adventureId=" + adventure);
        return body;
    }

    /** Return one fixed JSON error code; all caller inputs and raw persistence diagnostics stay private. */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
