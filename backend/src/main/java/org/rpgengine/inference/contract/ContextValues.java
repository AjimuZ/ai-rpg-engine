package org.rpgengine.inference.contract;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Shared checks for trusted context records. Contexts are assembled by backend code, so a bad
 * value is a programming error and fails with an exception instead of a contract violation; the
 * checks exist to keep every rendered instruction bounded and every reference unambiguous.
 */
final class ContextValues {
    /** Stable content reference syntax used for event, fact, destination, ability and decision IDs. */
    static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /** Static helpers only. */
    private ContextValues() {
    }

    /**
     * @param value candidate reference
     * @param name component name for the exception
     * @return the unchanged reference
     * @throws IllegalArgumentException when the value is null or not a stable reference
     */
    static String reference(String value, String name) {
        if (value == null || !REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a stable content reference");
        }
        return value;
    }

    /**
     * @param value human-readable context text
     * @param name component name for the exception
     * @param max maximum UTF-16 length
     * @return the unchanged text
     * @throws IllegalArgumentException when blank, oversized or containing unpaired surrogates
     */
    static String text(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max || !TextChecks.wellFormed(value)) {
            throw new IllegalArgumentException(name + " must be non-blank, bounded, well-formed text");
        }
        return value;
    }

    /**
     * Copy a bounded list and require distinct keys.
     *
     * @param values list to copy
     * @param name component name for the exception
     * @param min minimum size
     * @param max maximum size
     * @param key identity of one element
     * @return an immutable copy in the same order
     * @throws IllegalArgumentException when out of bounds, containing null or duplicate keys
     */
    static <T> List<T> distinct(List<T> values, String name, int min, int max, Function<T, ?> key) {
        Objects.requireNonNull(values, name);
        if (values.size() < min || values.size() > max) {
            throw new IllegalArgumentException(name + " must contain " + min + ".." + max + " entries");
        }
        var seen = new HashSet<Object>();
        for (T value : values) {
            if (value == null || !seen.add(key.apply(value))) {
                throw new IllegalArgumentException(name + " must not contain null or duplicate entries");
            }
        }
        return List.copyOf(values);
    }
}
