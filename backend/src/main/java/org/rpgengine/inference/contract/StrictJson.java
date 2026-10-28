package org.rpgengine.inference.contract;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.content.PackTextParser;

/**
 * Independent structural reader for untrusted contract output. It parses with the engine's strict
 * data-only JSON parser (duplicate keys, trailing documents and non-finite numbers fail) and then
 * reads typed fields while recording {@link ContractViolation}s instead of throwing, so one pass
 * reports every structural problem of an output.
 *
 * <p>Every reader method returns {@code null} when the value is absent, JSON {@code null} or
 * invalid; callers distinguish those cases only where the contract requires it, through
 * {@link #present}. Exact field sets are enforced per object, so an unknown field such as
 * {@code damage}, {@code statePatch} or {@code savedSuccessfully} always fails.
 */
final class StrictJson {
    /** Largest accepted output document. */
    static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private final List<ContractViolation> violations;

    /** @param violations sink shared with the semantic validator */
    StrictJson(List<ContractViolation> violations) {
        this.violations = violations;
    }

    /**
     * Parse the whole output document.
     *
     * @param output final agent message of a completed task
     * @return the root value, or null after recording {@code oversized-output} or
     *         {@code malformed-json}; parser diagnostics are dropped because they can quote output
     */
    Object parse(String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_OUTPUT_BYTES) {
            violations.add(ContractViolation.structural("oversized-output", ""));
            return null;
        }
        try {
            Object root = new PackTextParser().parse("contract-output.json", bytes).value();
            if (root == null) {
                violations.add(ContractViolation.structural("malformed-json", ""));
            }
            return root;
        } catch (RuntimeException failure) {
            // PackException (an IllegalArgumentException) for syntax, encoding and limits; any other
            // parser failure is equally treated as malformed rather than escaping the validator.
            violations.add(ContractViolation.structural("malformed-json", ""));
            return null;
        }
    }

    /**
     * @param value candidate object
     * @param path pointer of the value
     * @param fields exact required field set
     * @return the object, or null after recording {@code unexpected-type} or {@code field-set}
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> object(Object value, String path, Set<String> fields) {
        if (!(value instanceof Map<?, ?> map)) {
            violations.add(ContractViolation.structural("unexpected-type", path));
            return null;
        }
        if (!map.keySet().equals(fields)) {
            violations.add(ContractViolation.structural("field-set", path));
            return null;
        }
        return (Map<String, Object>) map;
    }

    /** @return true when the field holds a non-null value */
    static boolean present(Map<String, Object> object, String key) {
        return object.get(key) != null;
    }

    /**
     * Read human-readable or identifier text.
     *
     * @param object containing object
     * @param key field name
     * @param path pointer of the containing object
     * @param max maximum UTF-16 length
     * @param nullable whether JSON null is allowed
     * @return the exact text, or null; records {@code unexpected-type}, {@code out-of-bounds},
     *         {@code invalid-text} or {@code executable-content}
     */
    String text(Map<String, Object> object, String key, String path, int max, boolean nullable) {
        String text = verbatim(object, key, path, max, nullable);
        if (text != null && TextChecks.executable(text)) {
            violations.add(ContractViolation.structural("executable-content", path + "/" + key));
            return null;
        }
        return text;
    }

    /**
     * Read text like {@link #text} but without the markup check, for strings copied from the
     * player's own submission, which may legitimately contain characters such as {@code <3}.
     *
     * @return the exact text, or null after recording a violation
     */
    String verbatim(Map<String, Object> object, String key, String path, int max, boolean nullable) {
        Object value = object.get(key);
        String at = path + "/" + key;
        if (value == null) {
            if (!nullable) {
                violations.add(ContractViolation.structural("unexpected-type", at));
            }
            return null;
        }
        if (!(value instanceof String text)) {
            violations.add(ContractViolation.structural("unexpected-type", at));
            return null;
        }
        if (text.isBlank() || text.length() > max) {
            violations.add(ContractViolation.structural("out-of-bounds", at));
            return null;
        }
        if (!TextChecks.wellFormed(text)) {
            violations.add(ContractViolation.structural("invalid-text", at));
            return null;
        }
        return text;
    }

    /**
     * Read a value from a closed set.
     *
     * @return the value when it is one of {@code allowed}; otherwise null after recording
     *         {@code unexpected-type} or {@code unsupported-value}
     */
    String choice(Map<String, Object> object, String key, String path, Set<String> allowed, boolean nullable) {
        Object value = object.get(key);
        String at = path + "/" + key;
        if (value == null) {
            if (!nullable) {
                violations.add(ContractViolation.structural("unexpected-type", at));
            }
            return null;
        }
        if (!(value instanceof String text)) {
            violations.add(ContractViolation.structural("unexpected-type", at));
            return null;
        }
        if (!allowed.contains(text)) {
            violations.add(ContractViolation.structural("unsupported-value", at));
            return null;
        }
        return text;
    }

    /**
     * Read an exact JSON integer; decimals and numeric strings are not coerced.
     *
     * @return the value within {@code min..max}, or null after recording a violation
     */
    Long integer(Map<String, Object> object, String key, String path, long min, long max, boolean nullable) {
        Object value = object.get(key);
        String at = path + "/" + key;
        if (value == null) {
            if (!nullable) {
                violations.add(ContractViolation.structural("unexpected-type", at));
            }
            return null;
        }
        if (!(value instanceof BigInteger integer)) {
            violations.add(ContractViolation.structural("unexpected-type", at));
            return null;
        }
        if (integer.compareTo(BigInteger.valueOf(min)) < 0 || integer.compareTo(BigInteger.valueOf(max)) > 0) {
            violations.add(ContractViolation.structural("out-of-bounds", at));
            return null;
        }
        return integer.longValueExact();
    }

    /**
     * Read a non-null array.
     *
     * @return the elements when there are {@code min..max}; otherwise null after recording a violation
     */
    List<Object> array(Map<String, Object> object, String key, String path, int min, int max) {
        Object value = object.get(key);
        String at = path + "/" + key;
        if (!(value instanceof List<?> list)) {
            violations.add(ContractViolation.structural("unexpected-type", at));
            return null;
        }
        if (list.size() < min || list.size() > max) {
            violations.add(ContractViolation.structural("out-of-bounds", at));
            return null;
        }
        return new ArrayList<>(list);
    }

    /**
     * Read an array of distinct stable references.
     *
     * @return the references in output order, or null after recording a violation; a duplicate
     *         records {@code duplicate-reference}
     */
    List<String> references(Map<String, Object> object, String key, String path, int max) {
        List<Object> values = array(object, key, path, 0, max);
        if (values == null) {
            return null;
        }
        var references = new ArrayList<String>();
        for (int index = 0; index < values.size(); index++) {
            String at = path + "/" + key + "/" + index;
            if (!(values.get(index) instanceof String text) || !ContextValues.REFERENCE.matcher(text).matches()) {
                violations.add(ContractViolation.structural("unexpected-type", at));
                return null;
            }
            if (references.contains(text)) {
                violations.add(ContractViolation.structural("duplicate-reference", at));
                return null;
            }
            references.add(text);
        }
        return references;
    }

    /**
     * Read a canonical lowercase UUID text.
     *
     * @return the UUID, or null after recording {@code unexpected-type}
     */
    UUID uuid(Map<String, Object> object, String key, String path, boolean nullable) {
        Object value = object.get(key);
        String at = path + "/" + key;
        if (value == null) {
            if (!nullable) {
                violations.add(ContractViolation.structural("unexpected-type", at));
            }
            return null;
        }
        UUID parsed = uuid(value);
        if (parsed == null) {
            violations.add(ContractViolation.structural("unexpected-type", at));
        }
        return parsed;
    }

    /** @return the UUID when {@code value} is its canonical lowercase text, otherwise null */
    static UUID uuid(Object value) {
        if (!(value instanceof String text) || text.length() != 36) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(text);
            return parsed.toString().equals(text) ? parsed : null;
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    /** Record a semantic violation found while reading. */
    void semantic(String code, String path) {
        violations.add(ContractViolation.semantic(code, path));
    }

    /** Record a structural violation found while reading. */
    void structural(String code, String path) {
        violations.add(ContractViolation.structural(code, path));
    }
}
