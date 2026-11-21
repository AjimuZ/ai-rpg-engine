package org.rpgengine.assets;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Local admitted routes of the runtime image game flow (P17.6-C, docs/13 and docs/23 section 4):
 * {@code GET /api/v1/campaigns/{campaignId}/artwork/requests?adventureId=} lists the subjects the player can see now
 * with the state of their art, the capability and the budget; {@code POST} on the same path with
 * {@code {"category":"scene|portrait|item","subject":"..."}} asks for one image. The client never sends appearance
 * text: {@link RuntimeImageRequests} builds it from public pack fields.
 *
 * <p>Always holds: error bodies hold a fixed code and never a request value; a foreign scope and a subject that is
 * unknown, hidden or not in view now are the same {@code 404 ARTWORK_SUBJECT_UNAVAILABLE}; a refusal by the job
 * service (disabled, unavailable, queue full, budget exhausted) is a 200 answer naming it, because play goes on with
 * the fallback art.
 */
@RestController
public class RuntimeImageController {
    /** Largest accepted request body. */
    static final int MAX_BYTES = 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RuntimeImageRequests requests;

    /** Require the game flow service; missing infrastructure fails construction. */
    public RuntimeImageController(RuntimeImageRequests requests) {
        this.requests = Objects.requireNonNull(requests, "requests");
    }

    /**
     * List the subjects the player can see now. Reading changes nothing and never queues a job.
     *
     * @return 200 {@code {"scope":{adventureId,campaignId},"capability":"enabled|disabled|unavailable",
     *         "budget":{charged,limit,queued,running},"subjects":[{category,subject,label,state,errorCode}]}} with state
     *         {@code missing}, {@code pending}, {@code ready} or {@code failed}; 400 {@code ARTWORK_REQUEST_INVALID}, 403,
     *         404 {@code ARTWORK_SUBJECT_UNAVAILABLE} for a scope the owner does not hold, or 503
     *         {@code ARTWORK_STORAGE_UNAVAILABLE}
     */
    @GetMapping("/api/v1/campaigns/{campaignId}/artwork/requests")
    public ResponseEntity<?> list(HttpServletRequest request, @PathVariable String campaignId,
            @RequestParam(required = false) String adventureId) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            UUID adventure = id(adventureId);
            UUID campaign = id(campaignId);
            var listed = requests.list(owner, adventure, campaign);
            if (listed.isEmpty()) {
                return error(404, "ARTWORK_SUBJECT_UNAVAILABLE");
            }
            var body = new LinkedHashMap<String, Object>();
            body.put("scope", Map.of("adventureId", adventure.toString(), "campaignId", campaign.toString()));
            body.put("capability", listed.get().capability().name().toLowerCase(Locale.ROOT));
            var budget = listed.get().budget();
            body.put("budget", Map.of("charged", budget.charged(), "limit", budget.limit(), "queued", budget.queued(),
                    "running", budget.running()));
            body.put("subjects", listed.get().subjects().stream().map(RuntimeImageController::subject).toList());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (IllegalArgumentException failure) {
            return error(400, "ARTWORK_REQUEST_INVALID");
        } catch (DataAccessException | TransactionException failure) {
            return error(503, "ARTWORK_STORAGE_UNAVAILABLE");
        }
    }

    /**
     * Ask for the image of one subject the player can see now.
     *
     * @return 202 {@code {"admission":"queued|coalesced","subject":{...}}} when a job now runs for it, 200 with any other
     *         admission ({@code reused}, {@code stale-appearance}, {@code disabled}, {@code unavailable},
     *         {@code queue-full}, {@code budget-exhausted}); 400 {@code ARTWORK_REQUEST_INVALID} for a body that is not
     *         exactly the two string fields or names another category, 403, 404 {@code ARTWORK_SUBJECT_UNAVAILABLE},
     *         413 {@code ARTWORK_REQUEST_TOO_LARGE}, or 503 {@code ARTWORK_STORAGE_UNAVAILABLE}
     */
    @PostMapping("/api/v1/campaigns/{campaignId}/artwork/requests")
    public ResponseEntity<?> request(HttpServletRequest request, @PathVariable String campaignId,
            @RequestParam(required = false) String adventureId) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            UUID adventure = id(adventureId);
            UUID campaign = id(campaignId);
            if (request.getContentLengthLong() > MAX_BYTES) {
                return error(413, "ARTWORK_REQUEST_TOO_LARGE");
            }
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                return error(413, "ARTWORK_REQUEST_TOO_LARGE");
            }
            JsonNode body = JSON.readTree(bytes);
            if (body == null || !body.isObject() || body.size() != 2 || !body.path("category").isString()
                    || !body.path("subject").isString()) {
                return error(400, "ARTWORK_REQUEST_INVALID");
            }
            AssetCategory category = AssetCategory.fromWire(body.path("category").asString());
            var answer = requests.request(owner, adventure, campaign, category, body.path("subject").asString());
            if (answer.isEmpty()) {
                return error(404, "ARTWORK_SUBJECT_UNAVAILABLE");
            }
            var admission = answer.get().admission();
            var reply = new LinkedHashMap<String, Object>();
            reply.put("admission", admission.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            reply.put("subject", subject(answer.get().subject()));
            boolean running = admission == RuntimeImageJobs.Admission.QUEUED
                    || admission == RuntimeImageJobs.Admission.COALESCED;
            return ResponseEntity.status(running ? 202 : 200).contentType(MediaType.APPLICATION_JSON).body(reply);
        } catch (IllegalArgumentException | JacksonException | IOException failure) {
            return error(400, "ARTWORK_REQUEST_INVALID");
        } catch (DataAccessException | TransactionException failure) {
            return error(503, "ARTWORK_STORAGE_UNAVAILABLE");
        }
    }

    /** The exact wire fields of one subject. */
    private static Map<String, Object> subject(RuntimeImageRequests.Subject subject) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("category", subject.category().wire());
        entry.put("subject", subject.subject());
        entry.put("label", subject.label());
        entry.put("state", subject.state().wire());
        entry.put("errorCode", subject.errorCode());
        return entry;
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
