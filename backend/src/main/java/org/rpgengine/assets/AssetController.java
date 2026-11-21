package org.rpgengine.assets;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.rpgengine.identity.LocalHttpAdmission;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local admitted route that serves one asset by its SHA-256 within one campaign (docs/13 section 2, docs/14
 * section 5). The client never supplies a path: the hash is looked up in the registry of the owner's campaign, and
 * the media type comes from the stored, validated record.
 *
 * <p>Always holds: the response carries {@code X-Content-Type-Options: nosniff}, a CSP that allows nothing, the
 * stored media type and the hash as its {@code ETag}; an unknown, foreign or unreadable asset is the same
 * {@code 404 ASSET_NOT_FOUND}; error bodies hold a fixed code and never a request value, SQL or path.
 */
@RestController
public class AssetController {
    private final ManagedAssets assets;

    /** Require the registry; missing infrastructure fails construction. */
    public AssetController(ManagedAssets assets) {
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    /**
     * Serve the bytes of one asset the campaign may show.
     *
     * @param request admitted request; owner identity comes only from admission
     * @param campaignId canonical lower-case campaign UUID
     * @param hash lower-case hexadecimal SHA-256 of the asset
     * @param adventureId canonical lower-case UUID of the campaign's adventure
     * @return 200 with the bytes, 400 {@code ASSET_REQUEST_INVALID} for malformed identities, 403 without
     *         admission, 404 {@code ASSET_NOT_FOUND}, or 503 when storage fails
     */
    @GetMapping("/api/v1/campaigns/{campaignId}/assets/{hash}")
    public ResponseEntity<?> serve(HttpServletRequest request, @PathVariable String campaignId,
            @PathVariable("hash") String hash, @RequestParam(required = false) String adventureId) {
        UUID owner = request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID value ? value : null;
        if (owner == null) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            var served = assets.serve(owner, id(adventureId), id(campaignId), hash);
            if (served.isEmpty()) {
                return error(404, "ASSET_NOT_FOUND");
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(served.get().mediaType()))
                    .header("Content-Security-Policy", "default-src 'none'; sandbox")
                    .eTag("\"" + served.get().sha256() + "\"")
                    .body(served.get().bytes());
        } catch (IllegalArgumentException failure) {
            return error(400, "ASSET_REQUEST_INVALID");
        } catch (DataAccessException | TransactionException failure) {
            return error(503, "ASSET_STORAGE_UNAVAILABLE");
        }
    }

    /** Require canonical lower-case UUID text, rejecting Java's permissive abbreviated forms. */
    private static UUID id(String value) {
        if (value == null) {
            throw new IllegalArgumentException("scope required");
        }
        UUID result = UUID.fromString(value);
        if (!result.toString().equals(value)) {
            throw new IllegalArgumentException("noncanonical identity");
        }
        return result;
    }

    /** Return a stable code without request values, SQL, paths or exception messages. */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", code));
    }
}
