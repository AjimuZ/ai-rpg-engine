package org.rpgengine.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic JSON text for frozen rule values (P11.1). Objects are written with their keys in
 * ascending code-point order, without whitespace, so equal values always give byte-identical text and
 * an equal {@link #sha256 digest}, whatever map implementation or insertion order produced them.
 *
 * <p>Only the value shapes rule data needs are accepted: {@link String}, {@link Integer}, {@link Long},
 * {@link Boolean}, null, {@link Map} with string keys and {@link List}. Anything else is a programming
 * error and fails with {@link IllegalArgumentException} instead of being stringified, so a frozen
 * record can never depend on an object's {@code toString}.
 */
public final class CanonicalJson {
    /** Static helpers only. */
    private CanonicalJson() {
    }

    /**
     * Write one value as canonical JSON text.
     *
     * @param value supported value tree
     * @return compact text with sorted object keys
     * @throws IllegalArgumentException for an unsupported value or a non-string map key
     */
    public static String write(Object value) {
        var out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    /**
     * Digest canonical text.
     *
     * @param text canonical JSON text
     * @return lowercase hexadecimal SHA-256 of its UTF-8 bytes
     */
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Append one value; recursion depth is bounded by the caller's own finite value tree. */
    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String text -> string(out, text);
            case Integer number -> out.append(number);
            case Long number -> out.append(number);
            case Boolean flag -> out.append(flag);
            case Map<?, ?> map -> {
                var sorted = new TreeMap<String, Object>();
                map.forEach((key, child) -> {
                    if (!(key instanceof String name)) {
                        throw new IllegalArgumentException("canonical JSON object keys must be strings");
                    }
                    sorted.put(name, child);
                });
                out.append('{');
                boolean first = true;
                for (var entry : sorted.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(out, entry.getKey());
                    out.append(':');
                    append(out, entry.getValue());
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int index = 0; index < list.size(); index++) {
                    if (index > 0) {
                        out.append(',');
                    }
                    append(out, list.get(index));
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("unsupported canonical JSON value: " + value.getClass());
        }
    }

    /** Append a JSON string literal, escaping quotes, backslashes and control characters. */
    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }
}
