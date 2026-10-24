package org.rpgengine.campaign;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.rpgengine.identity.LocalHttpAdmission;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Narrow synchronous HTTP creation adapter. Owner context comes only from local admission;
 * strict byte-bounded choices enter the atomic service, and only committed public identities
 * leave it. Failures expose stable codes rather than SQL, pack text or exception messages.
 */
@RestController
public class CampaignCreationController {
    private static final int MAX_BYTES = 16_384;
    private final CampaignCreationService service;
    private final CampaignCreationJson decoder = new CampaignCreationJson();

    /** Require the atomic service; missing infrastructure fails application construction. */
    public CampaignCreationController(CampaignCreationService service) {
        this.service = java.util.Objects.requireNonNull(service, "service");
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

    /** Return one fixed JSON error code; all caller inputs and raw persistence diagnostics stay private. */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
