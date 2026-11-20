package org.rpgengine.adventure;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.rpgengine.identity.LocalHttpAdmission;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expose the owner's sealed content revisions through a locally admitted GET ({@code GET /api/v1/packs},
 * docs/13). Only canonical UUIDs and bounded pagination enter; detached public metadata or one fixed error
 * code leaves. Owner identity comes exclusively from server admission.
 */
@RestController
public class InstalledPackController {
    private final InstalledPackService service;

    /** Require the read service; a missing dependency fails application construction. */
    public InstalledPackController(InstalledPackService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * List one page of sealed revisions, optionally only those of one owned adventure.
     *
     * @return 200 {@code {packs, nextAfter}}; 400 {@code PACK_READ_INPUT_INVALID} for malformed controls;
     *         403 without admission; 404 {@code PACK_SCOPE_UNAVAILABLE} for a missing or foreign adventure;
     *         503 {@code PACK_READ_STORAGE_UNAVAILABLE} or {@code PACK_READ_CONTENT_UNAVAILABLE}
     */
    @GetMapping("/api/v1/packs")
    public ResponseEntity<?> list(HttpServletRequest request, @RequestParam(required = false) String adventureId,
            @RequestParam(required = false) String after, @RequestParam(defaultValue = "50") String limit) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            return ResponseEntity.ok(service.list(owner, adventureId == null ? null : id(adventureId),
                    after == null ? null : id(after), Integer.parseInt(limit)));
        } catch (AdventureLibraryService.ScopeUnavailable failure) {
            return error(404, "PACK_SCOPE_UNAVAILABLE");
        } catch (IllegalArgumentException failure) {
            return error(400, "PACK_READ_INPUT_INVALID");
        } catch (DataAccessException | TransactionException failure) {
            return error(503, "PACK_READ_STORAGE_UNAVAILABLE");
        } catch (IllegalStateException failure) {
            // A sealed manifest that no longer has its guaranteed shape must never look like an empty library.
            return error(503, "PACK_READ_CONTENT_UNAVAILABLE");
        }
    }

    /** Require lowercase canonical UUIDs; abbreviated, uppercase and otherwise ambiguous forms return 400. */
    private static UUID id(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) { throw new IllegalArgumentException("noncanonical identity"); }
        return parsed;
    }

    /** Return a fixed JSON code without echoing request values or database diagnostics. */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
