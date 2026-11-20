package org.rpgengine.panels;

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
import tools.jackson.core.JacksonException;

/**
 * Owner HTTP adapter of the combat projection (P12.1, docs/13 section 7):
 * {@code GET /api/v1/campaigns/{campaignId}/encounter?adventureId=...}, the route docs/13 reserves for the public current
 * combat projection. Local admission (owner, Host and Origin checks,
 * session cookie) is enforced by {@link LocalHttpAdmission}; a GET needs no CSRF token and every response is
 * {@code no-store} through that filter.
 *
 * <p>Responses: 200 with the {@link CombatPanels.CombatPanel} body (its {@code encounter} null outside a fight, where its
 * {@code offers} name the encounters the player may start; P13.2-B); 400
 * {@code CAMPAIGN_READ_INPUT_INVALID} for missing or noncanonical identities; 404 {@code CAMPAIGN_SCOPE_UNAVAILABLE} for a
 * missing, foreign or mismatched scope; 503 {@code CAMPAIGN_READ_STORAGE_UNAVAILABLE} for storage failures, an unreadable
 * stored fight or a history beyond its bound; 403 {@code LOCAL_ADMISSION_DENIED}. The codes are those of the other campaign
 * reads. Reading the projection never advances time, writes a row or starts work, so repeating it is always safe.
 */
@RestController
public class CombatPanelsController {
    private final CombatPanels panels;

    /** @param panels the projection service; a missing bean fails startup */
    public CombatPanelsController(CombatPanels panels) {
        this.panels = Objects.requireNonNull(panels, "panels");
    }

    /**
     * Read the combat projection of one owned campaign.
     *
     * @param request admitted HTTP request with the owner attribute
     * @param campaignId canonical campaign UUID
     * @param adventureId canonical adventure UUID that must own the campaign
     * @return the projection or a fixed error code
     */
    @GetMapping("/api/v1/campaigns/{campaignId}/encounter")
    public ResponseEntity<?> read(HttpServletRequest request, @PathVariable String campaignId,
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
            return ResponseEntity.ok(panels.read(owner, adventure, campaign));
        } catch (PlayPanels.ScopeUnavailable unavailable) {
            return error(404, "CAMPAIGN_SCOPE_UNAVAILABLE");
        } catch (DataAccessException | TransactionException | IllegalStateException | JacksonException failure) {
            return error(503, "CAMPAIGN_READ_STORAGE_UNAVAILABLE");
        }
    }

    /** @return a fixed public error code without request values or exception text */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
