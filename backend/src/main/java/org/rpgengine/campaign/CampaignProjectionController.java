package org.rpgengine.campaign;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import org.rpgengine.identity.LocalHttpAdmission;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local admitted GET adapters for listing, continue and deterministic public sections.
 * Owner identity comes exclusively from admission. Explicit canonical scope IDs enter
 * the read service; only detached allowlisted records or fixed error codes leave it.
 */
@RestController
public class CampaignProjectionController {
    private final CampaignProjectionService service;

    /** Require the scoped snapshot service; missing infrastructure fails construction. */
    public CampaignProjectionController(CampaignProjectionService service) {
        this.service = java.util.Objects.requireNonNull(service, "service");
    }

    /** List a bounded owner page with optional adventure filter and exclusive UUID cursor; invalid syntax is 400. */
    @GetMapping("/api/v1/campaigns")
    public ResponseEntity<?> list(HttpServletRequest request,
            @RequestParam(required = false) String adventureId, @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "50") String limit) {
        return respond(request, () -> service.list(owner(request), optionalId(adventureId), optionalId(after),
                Integer.parseInt(limit)));
    }

    /**
     * Resolve only exact consultation aliases through the existing admitted scoped snapshot.
     * Returns a detached presentation envelope; invalid text/scope is sanitized by respond,
     * and neither success nor failure submits an operation or persists transcript delivery.
     */
    @GetMapping("/api/v1/campaigns/{campaignId}/consultation")
    public ResponseEntity<?> consult(HttpServletRequest request, @PathVariable String campaignId,
            @RequestParam(required = false) String adventureId, @RequestParam(required = false) String command) {
        return respond(request, () -> {
            var kind = LocalConsultation.parse(command);
            return LocalConsultation.deliver(kind, service.read(owner(request), id(adventureId), id(campaignId)));
        });
    }

    /**
     * Read the selected campaign/section without advancing or activating gameplay. Continue
     * and state share the same snapshot contract. Only the supported starting decision has
     * a typed public projection; known-state includes only revealed fact rows.
     */
    @GetMapping({"/api/v1/campaigns/{campaignId}/state", "/api/v1/campaigns/{campaignId}/continue",
            "/api/v1/campaigns/{campaignId}/known-state", "/api/v1/campaigns/{campaignId}/inventory",
            "/api/v1/campaigns/{campaignId}/quests", "/api/v1/campaigns/{campaignId}/abilities"})
    public ResponseEntity<?> read(HttpServletRequest request, @PathVariable String campaignId,
            @RequestParam(required = false) String adventureId) {
        return respond(request, () -> {
            var state = service.read(owner(request), id(adventureId), id(campaignId));
            String path = request.getRequestURI();
            String section = path.substring(path.lastIndexOf('/') + 1);
            return switch (section) {
                case "inventory" -> new CampaignProjectionService.Section<>(state.scope(), state.inventory());
                case "abilities" -> new CampaignProjectionService.Section<>(state.scope(), state.abilities());
                case "quests" -> new CampaignProjectionService.Section<>(state.scope(), state.quests());
                case "known-state" -> new CampaignProjectionService.Section<>(state.scope(), state.knownFacts());
                default -> state;
            };
        });
    }

    /** Execute an admitted read and sanitize scope, input, storage and corrupt-content failures. */
    private static ResponseEntity<?> respond(HttpServletRequest request, Supplier<?> action) {
        if (owner(request) == null) { return error(403, "LOCAL_ADMISSION_DENIED"); }
        try {
            return ResponseEntity.ok(action.get());
        } catch (CampaignProjectionService.ScopeUnavailable failure) {
            return error(404, "CAMPAIGN_SCOPE_UNAVAILABLE");
        } catch (IllegalArgumentException failure) {
            return error(400, "CAMPAIGN_READ_INPUT_INVALID");
        } catch (DataAccessException | TransactionException failure) {
            return error(503, "CAMPAIGN_READ_STORAGE_UNAVAILABLE");
        } catch (IllegalStateException | tools.jackson.core.JacksonException failure) {
            return error(503, "CAMPAIGN_READ_CONTENT_UNAVAILABLE");
        }
    }

    /** Extract only the trusted admission attribute; absent/wrong types return null and deny access. */
    private static UUID owner(HttpServletRequest request) {
        return request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID value ? value : null;
    }

    /** Parse an optional canonical UUID; absence is allowed only for listing filters/cursors. */
    private static UUID optionalId(String value) { return value == null ? null : id(value); }

    /** Require canonical lowercase UUID syntax, rejecting Java's permissive abbreviated UUID forms. */
    private static UUID id(String value) {
        if (value == null) { throw new IllegalArgumentException("scope required"); }
        UUID result = UUID.fromString(value);
        if (!result.toString().equals(value)) { throw new IllegalArgumentException("noncanonical identity"); }
        return result;
    }

    /** Return a stable public category without request values, SQL, pack text or exception messages. */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
