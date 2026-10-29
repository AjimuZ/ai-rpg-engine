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
 * Owner HTTP adapter of the player panels and journal (P10.5, docs/13, "P10.5 panels and journal"):
 * {@code GET /api/v1/campaigns/{campaignId}/panels?adventureId=...&before=...&limit=...}. Local admission
 * (owner, Host and Origin checks, session cookie) is enforced by {@link LocalHttpAdmission}; a GET needs no
 * CSRF token and every response is {@code no-store} through that filter.
 *
 * <p>Responses: 200 with the {@link PlayPanels.Panels} body; 400 {@code CAMPAIGN_READ_INPUT_INVALID} for a
 * missing or noncanonical identity, a journal cursor that is not a positive integer or a page size outside
 * 1..{@value PlayPanels#MAX_EVENT_PAGE}; 404 {@code CAMPAIGN_SCOPE_UNAVAILABLE} for a missing, foreign or
 * mismatched scope; 503 {@code CAMPAIGN_READ_STORAGE_UNAVAILABLE} for storage failures or a list beyond its
 * bound; 403 {@code LOCAL_ADMISSION_DENIED}. The codes are those of the other campaign reads. Reading the
 * panels never advances time, writes a row or starts work, so repeating it is always safe.
 */
@RestController
public class PlayPanelsController {
    private final PlayPanels panels;

    /** @param panels the panel service; a missing bean fails startup */
    public PlayPanelsController(PlayPanels panels) {
        this.panels = Objects.requireNonNull(panels, "panels");
    }

    /**
     * Read the panels of one owned campaign.
     *
     * @param request admitted HTTP request with the owner attribute
     * @param campaignId canonical campaign UUID
     * @param adventureId canonical adventure UUID that must own the campaign
     * @param before exclusive journal cursor from a previous page's {@code nextBefore}; absent starts at the newest
     * @param limit journal page size
     * @return the panels or a fixed error code
     */
    @GetMapping("/api/v1/campaigns/{campaignId}/panels")
    public ResponseEntity<?> read(HttpServletRequest request, @PathVariable String campaignId,
            @RequestParam(required = false) String adventureId, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "" + PlayPanels.DEFAULT_EVENT_PAGE) String limit) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            UUID campaign = CampaignCommandJson.uuid(campaignId);
            UUID adventure = CampaignCommandJson.uuid(adventureId);
            Long cursor = before == null ? null : Long.valueOf(before);
            if (cursor != null && !cursor.toString().equals(before)) {
                throw new IllegalArgumentException("noncanonical cursor");
            }
            return ResponseEntity.ok(panels.read(owner, adventure, campaign, cursor, Integer.parseInt(limit)));
        } catch (IllegalArgumentException malformed) {
            return error(400, "CAMPAIGN_READ_INPUT_INVALID");
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
