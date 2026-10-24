package org.rpgengine.rules;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Portable HMAC-SHA-256 v1 integer draws from private seed bytes and an explicit operation/ordinal/bound. */
public final class DeterministicDraw {
    public static final String ALGORITHM = "HMAC-SHA-256";
    public static final int VERSION = 1;
    public static final int MAX_DRAWS = 128;
    private static final byte[] DOMAIN = "engine-draw-v1\0".getBytes(StandardCharsets.US_ASCII);

    /** Prevent instances: all protocol inputs are explicit and no mutable generator cursor exists. */
    private DeterministicDraw() {
    }

    /** Reject ordinal outside 0..127 or nonpositive sides; returns nothing and changes no state. */
    public static void validate(int ordinal, int sides) {
        if (ordinal < 0 || ordinal >= MAX_DRAWS || sides < 1) {
            throw new IllegalArgumentException("draw ordinal or sides outside supported bounds");
        }
    }

    /**
     * Return an unbiased value in 1..sides from exactly 32 seed bytes. Message bytes
     * are ASCII domain (including NUL), UUID high/low 64-bit words, ordinal, sides,
     * rejection counter; all integers are big endian, counter starts at zero.
     * Reject invalid inputs; fail closed if JDK crypto is unavailable or counter overflows.
     */
    public static int value(byte[] seed, UUID operation, int ordinal, int sides) {
        Objects.requireNonNull(operation, "operation");
        if (seed == null || seed.length != 32) {
            throw new IllegalArgumentException("draw seed must contain 32 bytes");
        }
        validate(ordinal, sides);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(seed, "HmacSHA256"));
            // Discard the incomplete residue interval instead of biasing modulo sides.
            long space = 1L << 32;
            long limit = space - space % sides;
            for (int counter = 0; ; counter = Math.incrementExact(counter)) {
                byte[] message = ByteBuffer.allocate(DOMAIN.length + 28).put(DOMAIN)
                        .putLong(operation.getMostSignificantBits()).putLong(operation.getLeastSignificantBits())
                        .putInt(ordinal).putInt(sides).putInt(counter).array();
                long sample = Integer.toUnsignedLong(ByteBuffer.wrap(mac.doFinal(message)).getInt());
                if (sample < limit) {
                    return (int) (sample % sides) + 1;
                }
            }
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("required draw algorithm unavailable", failure);
        }
    }
}
