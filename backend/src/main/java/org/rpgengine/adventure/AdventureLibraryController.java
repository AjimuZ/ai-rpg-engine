package org.rpgengine.adventure;

import java.util.Map;
import java.util.Objects;
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
 * Expose owner-scoped persisted library metadata through locally admitted GET requests.
 * Only canonical UUIDs and bounded pagination enter the service; detached records or
 * fixed error codes leave it. Owner identity comes exclusively from server admission.
 */
@RestController
public class AdventureLibraryController {
    private final AdventureLibraryService service;

    /** Require the read service; a missing dependency fails application construction. */
    public AdventureLibraryController(AdventureLibraryService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /** List an owner page using an optional exclusive cursor and default size 50; malformed controls return 400. */
    @GetMapping("/api/v1/adventures")
    public ResponseEntity<?> list(HttpServletRequest request, @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "50") String limit) {
        return respond(request, () -> service.list(owner(request), after == null ? null : id(after),
                Integer.parseInt(limit)));
    }

    /** Read one owned workspace by canonical identity; missing and foreign identities return the same 404. */
    @GetMapping("/api/v1/adventures/{adventureId}")
    public ResponseEntity<?> read(HttpServletRequest request, @PathVariable String adventureId) {
        return respond(request, () -> service.read(owner(request), id(adventureId)));
    }

    /**
     * Defend against an unadmitted invocation before parsing inputs and sanitize known
     * validation/scope/storage failures. Unexpected programming errors remain failures;
     * they must never be converted to an empty successful library.
     */
    private static ResponseEntity<?> respond(HttpServletRequest request, Supplier<?> action) {
        if (owner(request) == null) { return error(403, "LOCAL_ADMISSION_DENIED"); }
        try {
            return ResponseEntity.ok(action.get());
        } catch (AdventureLibraryService.ScopeUnavailable failure) {
            return error(404, "ADVENTURE_SCOPE_UNAVAILABLE");
        } catch (IllegalArgumentException failure) {
            return error(400, "ADVENTURE_READ_INPUT_INVALID");
        } catch (DataAccessException | TransactionException failure) {
            return error(503, "ADVENTURE_READ_STORAGE_UNAVAILABLE");
        }
    }

    /** Extract the trusted owner attribute, returning null for absent or invalid admission context. */
    private static UUID owner(HttpServletRequest request) {
        return request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID value ? value : null;
    }

    /** Require lowercase canonical UUIDs; reject abbreviated, uppercase and otherwise ambiguous forms with 400. */
    private static UUID id(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) { throw new IllegalArgumentException("noncanonical identity"); }
        return parsed;
    }

    /** Return a fixed JSON code without echoing request values, private metadata or database diagnostics. */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
