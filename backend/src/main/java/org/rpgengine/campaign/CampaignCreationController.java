package org.rpgengine.campaign;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.rpgengine.commands.CampaignCommandJson;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionFailure;
import org.rpgengine.commands.OperationCancellationService;
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
 * HTTP adapter for campaign creation, command execution and status reads. Owner context
 * comes only from local admission; byte-bounded input enters the atomic services, and
 * detached public identities, status and the player-safe receipt projection leave them.
 * A fresh command is admitted and then resolved locally in the same request (P07.4); its
 * narration is a durable post-commit presentation job (P07.5). Cancellation requests race the
 * execution under the campaign lock and report whether effects were already committed. Failures
 * expose stable codes rather than SQL, pack text or exception messages.
 */
@RestController
public class CampaignCreationController {
    private static final int MAX_BYTES = 16_384;
    private static final int MAX_CANCEL_BYTES = 64;
    private final CampaignCreationService service;
    private final CommandExecutionService commands;
    private final OperationCancellationService cancellations;
    private final CampaignCommandJson commandDecoder = new CampaignCommandJson();
    private final CampaignCreationJson decoder = new CampaignCreationJson();

    /** Require every durable service; missing infrastructure fails application construction. */
    public CampaignCreationController(CampaignCreationService service, CommandExecutionService commands,
            OperationCancellationService cancellations) {
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.commands = java.util.Objects.requireNonNull(commands, "commands");
        this.cancellations = java.util.Objects.requireNonNull(cancellations, "cancellations");
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
     * Admit and locally resolve one command, or replay it. Reads at most 16 KiB plus one
     * sentinel byte, also for chunked input, so the decoder never sees an unbounded body.
     * A fresh request answers 202 with its final status: {@code COMMITTED} with a receipt,
     * or {@code REJECTED}/{@code FAILED_BEFORE_COMMIT} when current state or storage refused
     * it after durable admission. A replay answers 200 with the stored status and receipt.
     *
     * @param request admitted HTTP request carrying the owner attribute and JSON body
     * @param campaignId canonical campaign UUID from the path
     * @param adventureId canonical adventure UUID that must own the campaign
     * @return 202 new operation, 200 replay, or a fixed error code (nothing journaled)
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
            var execution = commands.execute(owner, adventure, campaign, commandDecoder.decode(bytes));
            return ResponseEntity.status(execution.replayed() ? 200 : 202).body(body(execution, adventure));
        } catch (CampaignCommandJson.Malformed | IOException failure) {
            return error(400, "COMMAND_INPUT_MALFORMED");
        } catch (OperationAdmissionFailure failure) {
            return failure(failure);
        } catch (IllegalArgumentException failure) {
            // Structurally valid JSON can still exceed canonical depth/size or numeric
            // normalization limits. The service applies those bounds before any writes.
            return error(422, "COMMAND_INVALID");
        } catch (TransientDataAccessException failure) {
            // Lock or statement timeout during admission: the campaign is effectively busy and
            // nothing was admitted. Later failures are recorded on the operation instead.
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
            return ResponseEntity.ok(body(commands.status(owner, adventure, campaign, operation), adventure));
        } catch (CampaignCommandJson.Malformed failure) {
            return error(400, "COMMAND_INPUT_MALFORMED");
        } catch (OperationAdmissionFailure failure) {
            return failure(failure);
        } catch (DataAccessException | TransactionException | IllegalStateException failure) {
            return error(503, "COMMAND_STORAGE_UNAVAILABLE");
        }
    }

    /**
     * Request cancellation of one operation. The body must be the empty JSON object {@code {}}.
     * The answer is 200 with the current operation view plus {@code cancellation}:
     * {@code CANCELLED} (no effects; also for a repeated request), {@code ALREADY_COMMITTED}
     * (effects saved, receipt included, optional narration stopped) or {@code ALREADY_FINISHED}
     * (rejected, failed or awaiting clarification). Lock contention answers 409 without writes;
     * retrying is safe.
     *
     * @param request admitted HTTP request carrying the owner attribute and the empty JSON body
     * @param campaignId canonical campaign UUID
     * @param operationId canonical operation UUID
     * @param adventureId canonical adventure UUID that must own the campaign
     * @return 200 with the outcome, or a fixed error code
     */
    @PostMapping("/api/v1/campaigns/{campaignId}/operations/{operationId}/cancel")
    public ResponseEntity<?> cancel(HttpServletRequest request, @PathVariable String campaignId,
            @PathVariable String operationId, @RequestParam(required = false) String adventureId) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            UUID campaign = CampaignCommandJson.uuid(campaignId);
            UUID operation = CampaignCommandJson.uuid(operationId);
            UUID adventure = CampaignCommandJson.uuid(adventureId);
            commandDecoder.decodeEmpty(request.getInputStream().readNBytes(MAX_CANCEL_BYTES + 1));
            var outcome = cancellations.cancel(owner, adventure, campaign, operation);
            var body = body(commands.status(owner, adventure, campaign, operation), adventure);
            body.put("cancellation", outcome.name());
            return ResponseEntity.ok(body);
        } catch (CampaignCommandJson.Malformed | IOException failure) {
            return error(400, "COMMAND_INPUT_MALFORMED");
        } catch (OperationAdmissionFailure failure) {
            return failure(failure);
        } catch (TransientDataAccessException failure) {
            // A commit or another writer held the campaign lock too long; nothing was written.
            return error(409, "COMMAND_CAMPAIGN_BUSY");
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
            case DESTINATION_UNAVAILABLE -> error(422, "COMMAND_DESTINATION_UNAVAILABLE");
            case ABILITY_UNAVAILABLE -> error(422, "COMMAND_ABILITY_UNAVAILABLE");
            case PREREQUISITE_UNMET -> error(422, "COMMAND_PREREQUISITE_UNMET");
            case TARGET_ILLEGAL -> error(422, "COMMAND_TARGET_ILLEGAL");
            case RESOURCE_INSUFFICIENT -> error(422, "COMMAND_RESOURCE_INSUFFICIENT");
            case CONTENT_UNAVAILABLE -> error(422, "COMMAND_CONTENT_UNAVAILABLE");
            case EFFECT_UNSUPPORTED -> error(422, "COMMAND_EFFECT_UNSUPPORTED");
        };
    }

    /**
     * Allowlisted public operation body: identities, lifecycle counters, the safe query and
     * stream paths and the player-safe receipt projection (null until committed). Stored
     * receipts, permits, payloads and rejection internals are never included.
     */
    private static LinkedHashMap<String, Object> body(CommandExecutionService.Execution execution, UUID adventure) {
        var view = execution.operation();
        var body = new LinkedHashMap<String, Object>();
        body.put("operationId", view.operationId().toString());
        body.put("campaignId", view.campaignId().toString());
        body.put("commandType", view.commandType());
        body.put("status", view.status().name());
        body.put("baseRevision", view.baseRevision());
        body.put("committedRevision", view.committedRevision());
        body.put("operationVersion", view.operationVersion());
        body.put("replayed", execution.replayed());
        body.put("createdAt", view.createdAt().toString());
        body.put("updatedAt", view.updatedAt().toString());
        body.put("receipt", execution.receipt());
        body.put("query", "/api/v1/campaigns/" + view.campaignId() + "/operations/" + view.operationId()
                + "?adventureId=" + adventure);
        body.put("stream", "/api/v1/campaigns/" + view.campaignId() + "/stream?adventureId=" + adventure);
        return body;
    }

    /** Return one fixed JSON error code; all caller inputs and raw persistence diagnostics stay private. */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
