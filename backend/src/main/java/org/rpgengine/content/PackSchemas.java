package org.rpgengine.content;

import java.io.IOException;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evaluate the small assertion vocabulary used by the bundled draft-2020-12 schemas.
 * This is not a general schema service: schemas are trusted classpath resources, references
 * stay local, unsupported keywords fail closed and pack input cannot supply a schema.
 */
public final class PackSchemas {
    private static final Set<String> KEYWORDS = Set.of("$schema", "$id", "title", "description", "$defs", "$ref",
            "type", "const", "enum", "required", "properties", "additionalProperties", "items", "minItems",
            "maxItems", "minLength", "maxLength", "pattern", "uniqueItems");
    private static final Map<String, Object> MANIFEST = load("manifest");
    private static final Map<String, Object> DEFINITIONS = load("definitions");
    private static final Map<String, Object> LOCALIZATION = load("localization");
    private static final Map<String, Object> TEXT_RESOURCES = load("text-resources");

    /** Prevent construction of this fixed-schema utility. */
    private PackSchemas() {
    }

    /** Validate manifest shape, throwing a source-located PackException for the first structural failure. */
    public static void manifest(PackDocument document) {
        validate(MANIFEST, MANIFEST, document.value(), document, "");
    }

    /** Validate the definition envelope only; success does not establish semantic validity or authorize admission. */
    public static void definitions(PackDocument document) {
        validate(DEFINITIONS, DEFINITIONS, document.value(), document, "");
    }

    /** Validate a text-only overlay shape; invalid structure throws a source-located PackException. */
    public static void localization(PackDocument document) {
        validate(LOCALIZATION, LOCALIZATION, document.value(), document, "");
    }

    /** Validate source resource/policy structure; target ownership and protection require semantic compilation. */
    public static void textResources(PackDocument document) {
        validate(TEXT_RESOURCES, TEXT_RESOURCES, document.value(), document, "");
    }

    /** Load one fixed schema as data; missing or unreadable packaged schemas are internal build errors. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String name) {
        String path = "/pack-contract/1/" + name + ".schema.json";
        try (var stream = PackSchemas.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing pack schema: " + name);
            }
            return (Map<String, Object>) new PackTextParser().parse(name + ".json", stream.readAllBytes()).value();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load pack schema: " + name, failure);
        }
    }

    /** Apply trusted assertions recursively; unknown keywords and external refs indicate a broken bundled schema. */
    @SuppressWarnings("unchecked")
    private static void validate(Map<String, Object> root, Map<String, Object> schema, Object value,
            PackDocument document, String pointer) {
        if (!KEYWORDS.containsAll(schema.keySet())) {
            throw new IllegalStateException("Unsupported bundled schema keyword");
        }
        if (schema.containsKey("$ref")) {
            String ref = (String) schema.get("$ref");
            if (!ref.startsWith("#/$defs/") || ref.substring(8).contains("/")) {
                throw new IllegalStateException("Only local definition references are supported");
            }
            Map<String, Object> target = (Map<String, Object>) ((Map<?, ?>) root.get("$defs")).get(ref.substring(8));
            if (target == null) {
                throw new IllegalStateException("Missing bundled schema reference");
            }
            validate(root, target, value, document, pointer);
        }
        String type = (String) schema.get("type");
        if (type != null) {
            boolean matches = switch (type) {
                case "object" -> value instanceof Map;
                case "array" -> value instanceof List;
                case "string" -> value instanceof String;
                // JSON Schema defines integers by their mathematical value, so 1.0
                // and 1e0 satisfy integer constraints just like the lexical integer 1.
                case "integer" -> value instanceof BigInteger
                        || value instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0;
                default -> throw new IllegalStateException("Unsupported bundled schema type");
            };
            check(matches, document, pointer, "Expected " + type);
        }
        if (schema.containsKey("const")) {
            Object constant = schema.get("const");
            boolean equal = value instanceof Number number && constant instanceof Number expected
                    ? new BigDecimal(number.toString()).compareTo(new BigDecimal(expected.toString())) == 0
                    : Objects.equals(value, constant);
            check(equal, document, pointer, "Unsupported contract value");
        }
        if (schema.containsKey("enum")) {
            check(((List<?>) schema.get("enum")).contains(value), document, pointer, "Unsupported value");
        }
        if (value instanceof String text) {
            int length = text.codePointCount(0, text.length());
            bounds(schema, "minLength", "maxLength", length, document, pointer);
            if (schema.containsKey("pattern")) {
                check(Pattern.compile((String) schema.get("pattern")).matcher(text).find(), document, pointer, "Invalid text format");
            }
        }
        if (value instanceof List<?> list) {
            bounds(schema, "minItems", "maxItems", list.size(), document, pointer);
            if (Boolean.TRUE.equals(schema.get("uniqueItems"))) {
                check(new HashSet<>(list).size() == list.size(), document, pointer, "Duplicate entries");
            }
            if (schema.containsKey("items")) {
                for (int index = 0; index < list.size(); index++) {
                    validate(root, (Map<String, Object>) schema.get("items"), list.get(index), document,
                            PackDocument.child(pointer, Integer.toString(index)));
                }
            }
        }
        if (value instanceof Map<?, ?> map) {
            for (Object required : (List<?>) schema.getOrDefault("required", List.of())) {
                String key = (String) required;
                check(map.containsKey(key), document, PackDocument.child(pointer, key), "Required field is missing");
            }
            Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            for (var entry : map.entrySet()) {
                String key = (String) entry.getKey();
                String child = PackDocument.child(pointer, key);
                if (properties.containsKey(key)) {
                    validate(root, (Map<String, Object>) properties.get(key), entry.getValue(), document, child);
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    throw new PackException("SCHEMA", document.location(child), "Unknown field");
                }
            }
        }
    }

    /** Enforce inclusive size bounds from a trusted schema; all input failures retain the field's source location. */
    private static void bounds(Map<String, Object> schema, String min, String max, int size,
            PackDocument document, String pointer) {
        if (schema.containsKey(min)) {
            check(size >= ((BigInteger) schema.get(min)).intValueExact(), document, pointer, "Value is too short");
        }
        if (schema.containsKey(max)) {
            check(size <= ((BigInteger) schema.get(max)).intValueExact(), document, pointer, "Value is too long");
        }
    }

    /** Translate one failed structural assertion to a blocking author diagnostic; successful assertions return normally. */
    private static void check(boolean valid, PackDocument document, String pointer, String message) {
        if (!valid) {
            throw new PackException("SCHEMA", document.location(pointer), message);
        }
    }
}
