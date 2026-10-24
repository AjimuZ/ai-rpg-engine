package org.rpgengine.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Argument checks shared by canonical entities, mirroring the database CHECK
 * constraints of the V1 migration so invalid values fail early with a clear message.
 * The database remains the final authority; these checks only shorten the feedback loop.
 * Every method throws {@link IllegalArgumentException} (or {@link NullPointerException}
 * for a missing value) and never repairs input silently.
 */
public final class CanonicalValues {
    // Lowercase hexadecimal SHA-256, the single hash representation used in storage.
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    /** Static helpers only. */
    private CanonicalValues() {
    }

    /**
     * @param value candidate text
     * @param name field name used in the error message
     * @param maxLength inclusive maximum length in characters
     * @return the unchanged value
     * @throws IllegalArgumentException if blank or longer than {@code maxLength}
     */
    public static String requireText(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be 1.." + maxLength + " non-blank characters");
        }
        return value;
    }

    /**
     * @param value optional text; null is allowed and returned unchanged
     * @param name field name used in the error message
     * @param maxLength inclusive maximum length in characters
     * @return the unchanged value or null
     * @throws IllegalArgumentException if present but blank or too long
     */
    public static String optionalText(String value, String name, int maxLength) {
        return value == null ? null : requireText(value, name, maxLength);
    }

    /**
     * @param value candidate digest
     * @param name field name used in the error message
     * @return the unchanged digest
     * @throws IllegalArgumentException unless it is 64 lowercase hexadecimal characters
     */
    public static String requireSha256Hex(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SHA256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase hexadecimal SHA-256 digest");
        }
        return value;
    }

    /**
     * @param value candidate counter
     * @param name field name used in the error message
     * @return the unchanged value
     * @throws IllegalArgumentException if negative
     */
    public static long requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    /**
     * @param value candidate counter or version
     * @param name field name used in the error message
     * @return the unchanged value
     * @throws IllegalArgumentException if zero or negative
     */
    public static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /**
     * PostgreSQL stores microseconds, so instants are truncated before they are kept;
     * otherwise an entity would compare unequal to its own reloaded state.
     *
     * @param value wall-clock instant
     * @param name field name used in the error message
     * @return the instant truncated to microseconds
     */
    public static Instant timestamp(Instant value, String name) {
        return Objects.requireNonNull(value, name).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Light shape check for JSON object payloads; the database verifies they parse
     * as objects and later packages validate their typed schema.
     *
     * @param value serialized JSON
     * @param name field name used in the error message
     * @return the unchanged value
     * @throws IllegalArgumentException unless the text starts with an object brace
     */
    public static String requireJsonObject(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.strip().startsWith("{")) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        return value;
    }
}
