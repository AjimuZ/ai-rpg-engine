package org.rpgengine.memory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.rpgengine.commands.CampaignCommandJson;
import org.rpgengine.identity.LocalHttpAdmission;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner HTTP adapter of manual idle renewal (P09.4, docs/08 section 7):
 * {@code POST /api/v1/campaigns/{campaignId}/ai-session/renew?adventureId=...} with the empty
 * JSON object as body. Admission (owner, same origin, CSRF token, JSON content type) is enforced by
 * {@link LocalHttpAdmission} for every campaign route.
 *
 * <p>Responses: 200 {@code {"outcome": "RENEWED", "renewedSessions": n, "stateRevision": r}}; 409
 * {@code SESSION_RENEWAL_BUSY} when the campaign is not idle (nothing retired); 404
 * {@code SESSION_RENEWAL_SCOPE_UNAVAILABLE}; 400 {@code SESSION_RENEWAL_INPUT_MALFORMED}; 503
 * {@code SESSION_RENEWAL_STORAGE_UNAVAILABLE}; 403 {@code LOCAL_ADMISSION_DENIED}. Bodies carry fixed
 * codes and counts only, never session identifiers or campaign text. Repeating the request is safe.
 */
@RestController
public class SessionRenewalController {
    private static final int MAX_BYTES = 64;
    private final SessionRenewalService service;
    private final CampaignCommandJson decoder = new CampaignCommandJson();

    /** @param service the renewal service; a missing bean fails startup */
    public SessionRenewalController(SessionRenewalService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * Renew every AI session of an idle campaign.
     *
     * @param request admitted HTTP request with the owner attribute and the empty JSON object
     * @param campaignId canonical campaign UUID
     * @param adventureId canonical adventure UUID that must own the campaign
     * @return the outcome or a fixed error code
     */
    @PostMapping("/api/v1/campaigns/{campaignId}/ai-session/renew")
    public ResponseEntity<?> renew(HttpServletRequest request, @PathVariable String campaignId,
            @RequestParam(required = false) String adventureId) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            UUID campaign = CampaignCommandJson.uuid(campaignId);
            UUID adventure = CampaignCommandJson.uuid(adventureId);
            decoder.decodeEmpty(request.getInputStream().readNBytes(MAX_BYTES + 1));
            var result = service.renewIdle(owner, adventure, campaign);
            return switch (result.outcome()) {
                case SCOPE_UNAVAILABLE -> error(404, "SESSION_RENEWAL_SCOPE_UNAVAILABLE");
                case BUSY -> error(409, "SESSION_RENEWAL_BUSY");
                case RENEWED -> {
                    var body = new LinkedHashMap<String, Object>();
                    body.put("outcome", result.outcome().name());
                    body.put("renewedSessions", result.renewedSessions());
                    body.put("stateRevision", result.stateRevision());
                    yield ResponseEntity.ok(body);
                }
            };
        } catch (CampaignCommandJson.Malformed | IOException failure) {
            return error(400, "SESSION_RENEWAL_INPUT_MALFORMED");
        } catch (DataAccessException | TransactionException | IllegalStateException failure) {
            return error(503, "SESSION_RENEWAL_STORAGE_UNAVAILABLE");
        }
    }

    /** @return a fixed public error code without request values or exception text */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
