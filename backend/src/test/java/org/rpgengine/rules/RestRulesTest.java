package org.rpgengine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure rest arithmetic (P10.4): interruption, elapsed time and restoration. */
class RestRulesTest {
    private static final RestRules.Policy NIGHT = new RestRules.Policy("p", 480, "shelter", 40,
            Map.of("vigor", 8, "focus", 3), List.of("rattled", "dazed"));
    private static final RestRules.Policy SAFE = new RestRules.Policy("p", 10, null, 0, Map.of("vigor", 2), List.of());

    /** Construct the stateless fixture. */
    RestRulesTest() { }

    /** The interruption draw is at or below the chance; a policy with no chance never reads the draw. */
    @Test
    void interruptionFollowsTheRecordedDraw() {
        assertTrue(RestRules.interrupted(NIGHT, 1));
        assertTrue(RestRules.interrupted(NIGHT, 40));
        assertFalse(RestRules.interrupted(NIGHT, 41));
        assertFalse(RestRules.interrupted(NIGHT, 100));
        assertFalse(RestRules.interrupted(SAFE, 1));
        assertFalse(RestRules.interrupted(SAFE, 0), "the draw is ignored when the policy cannot be interrupted");
        assertThrows(IllegalArgumentException.class, () -> RestRules.interrupted(NIGHT, 0));
        assertThrows(IllegalArgumentException.class, () -> RestRules.interrupted(NIGHT, 101));
    }

    /** An interrupted rest takes half the time, at least one minute. */
    @Test
    void elapsedTimeIsFullOrHalf() {
        assertEquals(28_800, RestRules.elapsedSeconds(NIGHT, false));
        assertEquals(14_400, RestRules.elapsedSeconds(NIGHT, true));
        assertEquals(600, RestRules.elapsedSeconds(SAFE, false));
        assertEquals(300, RestRules.elapsedSeconds(SAFE, true));
        var shortest = new RestRules.Policy("p", 1, null, 50, Map.of("vigor", 1), List.of());
        assertEquals(60, RestRules.elapsedSeconds(shortest, true));
    }

    /** Restoration is clamped to the headroom and halved, rounding down, when interrupted. */
    @Test
    void restorationIsClampedAndHalvedWhenInterrupted() {
        assertEquals(8, RestRules.restored(8, false, 20));
        assertEquals(5, RestRules.restored(8, false, 5));
        assertEquals(4, RestRules.restored(8, true, 20));
        assertEquals(1, RestRules.restored(3, true, 20));
        assertEquals(0, RestRules.restored(1, true, 20));
        assertEquals(0, RestRules.restored(8, false, 0));
        assertEquals(0, RestRules.restored(8, false, -3), "a pool above its maximum gains nothing");
    }

    /** A policy rejects out-of-range data and exposes its collections in a stable order. */
    @Test
    void policyIsValidatedAndOrdered() {
        assertEquals(List.of("dazed", "rattled"), NIGHT.removes());
        assertEquals(List.of("focus", "vigor"), List.copyOf(NIGHT.restores().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> NIGHT.restores().put("x", 1));
        assertThrows(IllegalArgumentException.class, () -> new RestRules.Policy("p", 0, null, 0, Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RestRules.Policy("p", 1_441, null, 0, Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RestRules.Policy("p", 5, null, 100, Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RestRules.Policy("p", 5, null, -1, Map.of(), List.of()));
        assertTrue(NIGHT.interruptible());
        assertFalse(SAFE.interruptible());
    }
}
