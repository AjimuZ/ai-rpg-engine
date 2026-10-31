package org.rpgengine.inference.slots;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.rpgengine.content.PackException;
import org.rpgengine.content.PackTextParser;
import org.rpgengine.identity.LocalHttpAdmission;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner HTTP adapter of the model slots (P21.3-A, docs/34 section 12): {@code GET
 * /api/v1/models/status}, {@code PUT /api/v1/settings/models} and {@code POST
 * /api/v1/models/probe}. Admission (owner, same origin, CSRF token, JSON content type) is enforced
 * by {@link LocalHttpAdmission}; a request without the owner attribute is refused here as well.
 *
 * <p>Bodies are closed JSON objects of at most {@value #MAX_BYTES} bytes: an unknown, duplicated or
 * mistyped field is {@code MODEL_SETTINGS_MALFORMED}. Error bodies carry fixed codes (and, for an
 * unacknowledged class change, the two class names) and never echo a submitted value. Responses
 * never contain a key, an endpoint or any task content: the settings hold identifiers only.
 */
@RestController
public class ModelSettingsController {
    private static final int MAX_BYTES = 8192;
    private static final Set<String> SETTING_KEYS = Set.of("slot", "backend", "model", "effort", "declaredClass",
            "fallbacks", "acknowledgeClass");
    private static final Set<String> FALLBACK_KEYS = Set.of("backend", "model", "effort");
    private final SlotSettingsService service;
    private final PackTextParser parser = new PackTextParser();

    /** @param service the settings service; a missing bean fails startup */
    public ModelSettingsController(SlotSettingsService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * @param request admitted HTTP request
     * @return 200 {@code {"slots": [...]}} with every slot's assignment, class badge, fallbacks,
     *         acknowledgement and last probe; 503 {@code MODEL_SETTINGS_STORAGE_UNAVAILABLE}
     */
    @GetMapping("/api/v1/models/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            List<Object> slots = new ArrayList<>();
            service.status().forEach(slot -> slots.add(render(slot)));
            return ResponseEntity.ok(Map.of("slots", slots));
        } catch (DataAccessException | TransactionException | IllegalStateException failure) {
            return error(503, "MODEL_SETTINGS_STORAGE_UNAVAILABLE");
        }
    }

    /**
     * Save one slot's assignment.
     *
     * @param request admitted HTTP request whose body is the assignment object
     * @return 200 {@code {"slot": {...}}}; 400 {@code MODEL_SETTINGS_MALFORMED}; 413
     *         {@code MODEL_SETTINGS_TOO_LARGE}; 422 with the {@link SlotSetting.Invalid} code;
     *         409 {@code CLASS_ACKNOWLEDGEMENT_REQUIRED} with {@code from} and {@code to}; 503
     *         {@code MODEL_SETTINGS_STORAGE_UNAVAILABLE}. Nothing is saved on any error.
     */
    @PutMapping("/api/v1/settings/models")
    public ResponseEntity<?> update(HttpServletRequest request) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) {
                return error(413, "MODEL_SETTINGS_TOO_LARGE");
            }
            return ResponseEntity.ok(Map.of("slot", render(service.update(decodeUpdate(body)))));
        } catch (Malformed | IOException failure) {
            return error(400, "MODEL_SETTINGS_MALFORMED");
        } catch (SlotSetting.Invalid failure) {
            return error(422, failure.code());
        } catch (SlotSettingsService.AcknowledgementRequired failure) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "CLASS_ACKNOWLEDGEMENT_REQUIRED");
            body.put("from", failure.from() == null ? null : failure.from().wire());
            body.put("to", failure.to().wire());
            return ResponseEntity.status(409).body(body);
        } catch (DataAccessException | TransactionException | IllegalStateException failure) {
            return error(503, "MODEL_SETTINGS_STORAGE_UNAVAILABLE");
        }
    }

    /**
     * Probe the stored assignment of one slot and record the result.
     *
     * @param request admitted HTTP request whose body is {@code {"slot": "..."}}
     * @return 200 {@code {"slot": {...}}} with the new probe result; 400
     *         {@code MODEL_SETTINGS_MALFORMED}; 404 {@code MODEL_SLOT_NOT_ASSIGNED}; 503
     *         {@code MODEL_SETTINGS_STORAGE_UNAVAILABLE}
     */
    @PostMapping("/api/v1/models/probe")
    public ResponseEntity<?> probe(HttpServletRequest request) {
        if (!(request.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE) instanceof UUID)) {
            return error(403, "LOCAL_ADMISSION_DENIED");
        }
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) {
                return error(413, "MODEL_SETTINGS_TOO_LARGE");
            }
            Map<String, Object> root = object(body, Set.of("slot"));
            return ResponseEntity.ok(Map.of("slot", render(service.probe(slot(root.get("slot"))))));
        } catch (Malformed | IOException failure) {
            return error(400, "MODEL_SETTINGS_MALFORMED");
        } catch (SlotSettingsService.NotAssigned failure) {
            return error(404, "MODEL_SLOT_NOT_ASSIGNED");
        } catch (DataAccessException | TransactionException | IllegalStateException failure) {
            return error(503, "MODEL_SETTINGS_STORAGE_UNAVAILABLE");
        }
    }

    private SlotSettingsService.Request decodeUpdate(byte[] body) {
        Map<String, Object> root = object(body, SETTING_KEYS);
        ModelSlot slot = slot(root.get("slot"));
        String effort = optionalText(root, "effort");
        if (effort == null && slot != ModelSlot.IMAGE) {
            throw new Malformed();
        }
        List<SlotSetting.Fallback> fallbacks = new ArrayList<>();
        Object listed = root.get("fallbacks");
        if (listed != null) {
            if (!(listed instanceof List<?> entries)) {
                throw new Malformed();
            }
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map) || !FALLBACK_KEYS.containsAll(map.keySet())) {
                    throw new Malformed();
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = (Map<String, Object>) map;
                String entryEffort = optionalText(fields, "effort");
                if (entryEffort == null) {
                    if (slot != ModelSlot.IMAGE) {
                        throw new Malformed();
                    }
                    entryEffort = SlotSetting.IMAGE_EFFORT;
                }
                fallbacks.add(new SlotSetting.Fallback(requiredText(fields, "backend"), requiredText(fields, "model"),
                        entryEffort));
            }
        }
        return new SlotSettingsService.Request(slot, requiredText(root, "backend"), requiredText(root, "model"),
                effort, optionalClass(root, "declaredClass"), fallbacks, optionalClass(root, "acknowledgeClass"));
    }

    private Map<String, Object> object(byte[] body, Set<String> allowed) {
        Object parsed;
        try {
            // The fixed filename selects JSON; the shared parser rejects duplicate keys and bad UTF-8.
            parsed = parser.parse("model-settings.json", body).value();
        } catch (PackException failure) {
            throw new Malformed();
        }
        if (!(parsed instanceof Map<?, ?> map) || !allowed.containsAll(map.keySet())) {
            throw new Malformed();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) map;
        return root;
    }

    private static ModelSlot slot(Object value) {
        if (!(value instanceof String text)) {
            throw new Malformed();
        }
        return ModelSlot.parse(text).orElseThrow(Malformed::new);
    }

    private static String requiredText(Map<String, Object> root, String key) {
        if (!(root.get(key) instanceof String text) || text.isEmpty()) {
            throw new Malformed();
        }
        return text;
    }

    private static String optionalText(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isEmpty()) {
            throw new Malformed();
        }
        return text;
    }

    private static BackendClass optionalClass(Map<String, Object> root, String key) {
        String text = optionalText(root, key);
        return text == null ? null : BackendClass.parse(text).orElseThrow(Malformed::new);
    }

    private static Map<String, Object> render(SlotSettingsService.SlotStatus status) {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("slot", status.slot().wire());
        slot.put("source", status.source().name().toLowerCase(Locale.ROOT));
        slot.put("backend", status.backend());
        slot.put("model", status.model());
        slot.put("effort", status.effort());
        slot.put("class", status.backendClass() == null ? null : badge(status.backendClass()));
        List<Object> fallbacks = new ArrayList<>();
        for (SlotSetting.Fallback entry : status.fallbacks()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("backend", entry.backend());
            fallback.put("model", entry.model());
            fallback.put("effort", entry.effort());
            fallbacks.add(fallback);
        }
        slot.put("fallbacks", fallbacks);
        if (status.acknowledgedClass() == null) {
            slot.put("acknowledged", null);
        } else {
            Map<String, Object> acknowledged = new LinkedHashMap<>();
            acknowledged.put("class", status.acknowledgedClass().wire());
            acknowledged.put("at", status.acknowledgedAt().toString());
            slot.put("acknowledged", acknowledged);
        }
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("state", status.probe().state().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        probe.put("code", status.probe().code());
        probe.put("at", status.probe().at() == null ? null : status.probe().at().toString());
        probe.put("elapsedMillis", status.probe().elapsedMillis());
        slot.put("probe", probe);
        slot.put("updatedAt", status.updatedAt() == null ? null : status.updatedAt().toString());
        return slot;
    }

    private static Map<String, Object> badge(BackendClass value) {
        Map<String, Object> badge = new LinkedHashMap<>();
        badge.put("class", value.wire());
        badge.put("billing", value.billing());
        badge.put("privacy", value.privacy());
        badge.put("leavesMachine", value.leavesMachine());
        return badge;
    }

    /** @return a fixed public error code without request values or exception text */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }

    /** A body that is not the closed shape of the route; maps to HTTP 400. */
    private static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Malformed() {
            super("malformed", null, false, false);
        }
    }
}
