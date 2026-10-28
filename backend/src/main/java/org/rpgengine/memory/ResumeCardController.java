package org.rpgengine.memory;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.rpgengine.commands.CampaignCommandJson;
import org.rpgengine.identity.LocalHttpAdmission;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner HTTP adapter of the resume card (P09.5, docs/08 section 10):
 * {@code GET /api/v1/campaigns/{campaignId}/resume?adventureId=...}. Local admission (owner, Host and
 * Origin checks, session cookie) is enforced by {@link LocalHttpAdmission}; a GET needs no CSRF token
 * and every response is {@code no-store} through that filter.
 *
 * <p>Responses: 200 with the {@link ResumeCards.ResumeCard} body; 400
 * {@code CAMPAIGN_READ_INPUT_INVALID} for missing or noncanonical identities; 404
 * {@code CAMPAIGN_SCOPE_UNAVAILABLE} for a missing, foreign or mismatched scope; 503
 * {@code CAMPAIGN_READ_STORAGE_UNAVAILABLE} for storage failures or a list beyond its bound; 403
 * {@code LOCAL_ADMISSION_DENIED}. Codes are those of the other campaign reads. Reading the card
 * never advances time, writes a row or starts work, so repeating it is always safe.
 */
@RestController
public class ResumeCardController {
    private final ResumeCards cards;

    /** @param cards the card service; a missing bean fails startup */
    public ResumeCardController(ResumeCards cards) {
        this.cards = Objects.requireNonNull(cards, "cards");
    }

    /**
     * Read the resume card of one owned campaign.
     *
     * @param request admitted HTTP request with the owner attribute
     * @param campaignId canonical campaign UUID
     * @param adventureId canonical adventure UUID that must own the campaign
     * @return the card or a fixed error code
     */
    @GetMapping("/api/v1/campaigns/{campaignId}/resume")
    public ResponseEntity<?> resume(HttpServletRequest request, @PathVariable String campaignId,
            @RequestParam(required = false) String adventureId) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        UUID campaign;
        UUID adventure;
        try {
            campaign = CampaignCommandJson.uuid(campaignId);
            adventure = CampaignCommandJson.uuid(adventureId);
        } catch (CampaignCommandJson.Malformed malformed) {
            return error(400, "CAMPAIGN_READ_INPUT_INVALID");
        }
        try {
            return ResponseEntity.ok(cards.read(owner, adventure, campaign));
        } catch (ResumeCards.ScopeUnavailable unavailable) {
            return error(404, "CAMPAIGN_SCOPE_UNAVAILABLE");
        } catch (DataAccessException | TransactionException | IllegalStateException failure) {
            return error(503, "CAMPAIGN_READ_STORAGE_UNAVAILABLE");
        }
    }

    /** @return a fixed public error code without request values or exception text */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
