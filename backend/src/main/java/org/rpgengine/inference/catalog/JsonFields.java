package org.rpgengine.inference.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict reading of the catalog and hardware-profile documents. Both are closed formats: every
 * object declares its exact field set, so an unknown or missing field, a duplicate key, a wrong
 * type or a value outside its range is a {@link CatalogException} with the field's JSON Pointer.
 * Absent and {@code null} are different: a field is either required (present, may be
 * {@code null} only where {@link #optionalLong} or {@link #optionalText} is used) or rejected.
 */
final class JsonFields {
    /** Upper bound for either document; the bundled catalog is about 20 KB. */
    static final int MAX_BYTES = 1_048_576;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private JsonFields() {
    }

    /** Parse {@code bytes} as exactly one JSON object; fail with SYNTAX, SIZE or TYPE. */
    static JsonNode document(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw new CatalogException("SIZE", "", "document exceeds " + MAX_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (JacksonException e) {
            throw new CatalogException("SYNTAX", "", "not a single well-formed JSON document without duplicate keys");
        }
        if (root == null || !root.isObject()) {
            throw new CatalogException("TYPE", "", "the document must be a JSON object");
        }
        return root;
    }

    /**
     * Require {@code node} to be an object whose field names are exactly {@code fields}; reports
     * the first unknown field, then the first missing one.
     */
    static JsonNode object(JsonNode node, String pointer, Set<String> fields) {
        if (node == null || !node.isObject()) {
            throw new CatalogException("TYPE", pointer, "expected an object");
        }
        for (String name : node.propertyNames()) {
            if (!fields.contains(name)) {
                throw new CatalogException("UNKNOWN_FIELD", pointer + "/" + name, "field is not part of the format");
            }
        }
        for (String name : fields) {
            if (!node.has(name)) {
                throw new CatalogException("MISSING_FIELD", pointer + "/" + name, "required field is absent");
            }
        }
        return node;
    }

    /** Ordered field-name set, for use with {@link #object}. */
    static Set<String> names(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }

    /** A non-blank string of at most {@code max} characters. */
    static String text(JsonNode node, String pointer, int max) {
        if (node == null || !node.isString()) {
            throw new CatalogException("TYPE", pointer, "expected a string");
        }
        String value = node.stringValue();
        if (value.isBlank() || value.length() > max) {
            throw new CatalogException("TEXT", pointer, "expected 1 to " + max + " characters");
        }
        return value;
    }

    /** {@link #text} or {@code null} when the value is JSON null. */
    static String optionalText(JsonNode node, String pointer, int max) {
        return node != null && node.isNull() ? null : text(node, pointer, max);
    }

    /** A string matching {@code pattern} completely. */
    static String matching(JsonNode node, String pointer, Pattern pattern, String rule) {
        String value = text(node, pointer, 512);
        if (!pattern.matcher(value).matches()) {
            throw new CatalogException(rule, pointer, "value does not have the required form");
        }
        return value;
    }

    /** One of {@code allowed}. */
    static String choice(JsonNode node, String pointer, Set<String> allowed) {
        String value = text(node, pointer, 64);
        if (!allowed.contains(value)) {
            throw new CatalogException("VALUE", pointer, "expected one of " + allowed);
        }
        return value;
    }

    /** An integer in {@code [min, max]}. Fractions, strings and booleans are rejected. */
    static long integer(JsonNode node, String pointer, long min, long max) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new CatalogException("TYPE", pointer, "expected an integer");
        }
        long value = node.longValue();
        if (value < min || value > max) {
            throw new CatalogException("RANGE", pointer, "expected " + min + " to " + max);
        }
        return value;
    }

    /** {@link #integer} or {@code null} when the value is JSON null. */
    static Long optionalLong(JsonNode node, String pointer, long min, long max) {
        return node != null && node.isNull() ? null : integer(node, pointer, min, max);
    }

    /** A JSON boolean. */
    static boolean bool(JsonNode node, String pointer) {
        if (node == null || !node.isBoolean()) {
            throw new CatalogException("TYPE", pointer, "expected a boolean");
        }
        return node.booleanValue();
    }

    /** An ISO calendar date such as {@code 2026-10-28}. */
    static LocalDate date(JsonNode node, String pointer) {
        String value = text(node, pointer, 10);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new CatalogException("DATE", pointer, "expected an ISO date (YYYY-MM-DD)");
        }
    }

    /** A UTC instant such as {@code 2026-10-28T12:00:00Z}. */
    static Instant instant(JsonNode node, String pointer) {
        String value = text(node, pointer, 40);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new CatalogException("DATE", pointer, "expected an ISO-8601 UTC instant");
        }
    }

    /** The elements of an array with between {@code min} and {@code max} items. */
    static List<JsonNode> array(JsonNode node, String pointer, int min, int max) {
        if (node == null || !node.isArray()) {
            throw new CatalogException("TYPE", pointer, "expected an array");
        }
        if (node.size() < min || node.size() > max) {
            throw new CatalogException("RANGE", pointer, "expected " + min + " to " + max + " items");
        }
        List<JsonNode> items = new ArrayList<>(node.size());
        node.values().forEach(items::add);
        return items;
    }

    /** Distinct strings from {@code allowed}; a repeated value is DUPLICATE. */
    static List<String> choices(JsonNode node, String pointer, Set<String> allowed, int min) {
        List<String> values = new ArrayList<>();
        List<JsonNode> items = array(node, pointer, min, allowed.size());
        for (int i = 0; i < items.size(); i++) {
            String value = choice(items.get(i), pointer + "/" + i, allowed);
            if (values.contains(value)) {
                throw new CatalogException("DUPLICATE", pointer + "/" + i, "value is listed twice");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }
}
