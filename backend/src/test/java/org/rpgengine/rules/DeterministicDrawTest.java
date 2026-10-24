package org.rpgengine.rules;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pin the portable v1 byte protocol independently of database storage; failures signal replay drift. */
class DeterministicDrawTest {
    /** Construct an isolated pure protocol fixture without external state. */
    DeterministicDrawTest() {
    }

    /** Compare independent Python HMAC vectors; changing framing or mapping must fail replay. */
    @Test
    void matchesIndependentVectors() {
        byte[] seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) i;
        }
        UUID operation = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        assertEquals(9, DeterministicDraw.value(seed, operation, 0, 10));
        assertEquals(2, DeterministicDraw.value(seed, operation, 1, 10));
        assertEquals(1, DeterministicDraw.value(seed, operation, 127, 10));
        assertEquals(1, DeterministicDraw.value(seed, operation, 0, 1));
        // Independent counter-0 sample 3652893867 is rejected for this bound;
        // counter-1 sample 849867194 is accepted. Modulo-only mapping fails here.
        assertEquals(849867195, DeterministicDraw.value(seed, operation, 4, 1073741825));
        assertEquals(1728713619, DeterministicDraw.value(seed, operation, 0, Integer.MAX_VALUE));
    }

    /** Invalid draw shapes must fail before allocation; no seed truncation or ordinal wrapping is allowed. */
    @Test
    void rejectsInvalidInputs() {
        UUID operation = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> DeterministicDraw.value(new byte[31], operation, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> DeterministicDraw.value(new byte[32], operation, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> DeterministicDraw.value(new byte[32], operation, 128, 10));
        assertThrows(IllegalArgumentException.class, () -> DeterministicDraw.value(new byte[32], operation, 0, 0));
    }
}
