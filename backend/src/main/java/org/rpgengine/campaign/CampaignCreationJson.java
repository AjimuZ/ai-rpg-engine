package org.rpgengine.campaign;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.rpgengine.content.PackException;
import org.rpgengine.content.PackTextParser;
import org.rpgengine.presentation.SupportedLocale;

/**
 * Decode the fixed public creation schema without coercions or polymorphic binding.
 * Input is already byte-bounded JSON. Output is immutable validated service input;
 * malformed shape throws Malformed, while illegal choices retain domain validation errors.
 */
final class CampaignCreationJson {
    private final PackTextParser parser = new PackTextParser();

    /** Construct the stateless adapter; the shared data-only parser keeps all budgets call-local. */
    CampaignCreationJson() {
    }

    /** Parse exactly one UTF-8 JSON document; reject duplicates, unknown fields and unsupported field types. */
    Input decode(byte[] bytes) {
        Object parsed;
        try {
            // The filename is fixed, so the reusable bounded parser can only select JSON, never YAML.
            parsed = parser.parse("campaign-request.json", bytes).value();
        } catch (PackException failure) {
            throw new Malformed();
        }
        var root = object(parsed, Set.of("requestId", "adventureId", "contentRevisionId", "title", "narrativeLocale", "choices"));
        var choices = object(root.get("choices"), Set.of("name", "archetypeId", "attributes", "skills", "background", "role"));
        UUID adventure = uuid(root, "adventureId");
        UUID request = uuid(root, "requestId");
        UUID revision = uuid(root, "contentRevisionId");
        // Decode all structural fields before domain construction so syntax errors stay HTTP 400.
        String title = text(root, "title", false);
        String locale = text(root, "narrativeLocale", false);
        String name = text(choices, "name", false);
        String archetype = text(choices, "archetypeId", true);
        String background = text(choices, "background", true);
        String role = text(choices, "role", true);
        var attributes = allocation(choices.get("attributes"));
        var skills = allocation(choices.get("skills"));
        return new Input(adventure, new CampaignCreationService.Request(request, revision, title,
                SupportedLocale.fromTag(locale), new CharacterChoices(name, archetype, attributes, skills, background, role)));
    }

    /** Accept an object with only explicitly allowed fields; required fields are checked at their use sites. */
    private static Map<?, ?> object(Object value, Set<String> allowed) {
        if (!(value instanceof Map<?, ?> map) || !allowed.containsAll(map.keySet())) {
            throw new Malformed();
        }
        return map;
    }

    /** Return an exact string or permitted missing/null optional value; numbers and booleans never coerce. */
    private static String text(Map<?, ?> map, String key, boolean optional) {
        Object value = map.get(key);
        if (value == null && optional) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new Malformed();
        }
        return text;
    }

    /** Require a canonical UUID string; permissive shortened JDK UUID forms are excluded from the wire contract. */
    private static UUID uuid(Map<?, ?> map, String key) {
        String text = text(map, key, false);
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equals(text)) {
                throw new Malformed();
            }
            return id;
        } catch (IllegalArgumentException failure) {
            throw new Malformed();
        }
    }

    /** Decode integral JSON allocation values exactly; decimal/exponent/string values and overflow are malformed. */
    private static Map<String, Integer> allocation(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new Malformed();
        }
        Map<String, Integer> result = new TreeMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof BigInteger number)) {
                throw new Malformed();
            }
            try {
                result.put(key, number.intValueExact());
            } catch (ArithmeticException failure) {
                throw new Malformed();
            }
        }
        return result;
    }

    /** Detached selected adventure and service intent; no client owner or arbitrary grants are represented. */
    record Input(UUID adventureId, CampaignCreationService.Request request) {
    }

    /** Fixed malformed-input signal; never retains submitted text, parser coordinates or exception details. */
    static final class Malformed extends IllegalArgumentException {
        /** Construct a safe syntax category; no inputs or external effects. */
        Malformed() { super("malformed creation input"); }
    }
}
