package org.rpgengine.rules;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Condition lifecycle: each stacking policy, the stack cap and expiry by elapsed rounds. */
class ConditionRulesTest {
    /** Construct a stateless fixture. */
    ConditionRulesTest() {
    }

    /** A first application always adds one stack for the full duration, whatever the policy. */
    @Test
    void firstApplicationAddsOneStack() {
        for (var stacking : ConditionRules.Stacking.values()) {
            var result = ConditionRules.apply(definition(stacking, stacking == ConditionRules.Stacking.STACK ? 3 : 1),
                    Optional.empty());
            assertEquals(ConditionRules.Change.ADDED, result.change());
            assertEquals(new ConditionRules.Active("c", 1, 4), result.state());
        }
    }

    /** No-stacking keeps the state, refresh restores the duration, stack adds up to the cap. */
    @Test
    void repeatedApplicationFollowsThePolicy() {
        var worn = Optional.of(new ConditionRules.Active("c", 1, 1));
        var none = ConditionRules.apply(definition(ConditionRules.Stacking.NONE, 1), worn);
        assertEquals(ConditionRules.Change.UNCHANGED, none.change());
        assertEquals(worn.get(), none.state());
        var refresh = ConditionRules.apply(definition(ConditionRules.Stacking.REFRESH, 1), worn);
        assertEquals(ConditionRules.Change.REFRESHED, refresh.change());
        assertEquals(new ConditionRules.Active("c", 1, 4), refresh.state());
        var stack = definition(ConditionRules.Stacking.STACK, 2);
        var second = ConditionRules.apply(stack, worn);
        assertEquals(ConditionRules.Change.STACKED, second.change());
        assertEquals(new ConditionRules.Active("c", 2, 4), second.state());
        // At the cap a further application restores the duration but adds no stack.
        var capped = ConditionRules.apply(stack, Optional.of(new ConditionRules.Active("c", 2, 1)));
        assertEquals(ConditionRules.Change.REFRESHED, capped.change());
        assertEquals(new ConditionRules.Active("c", 2, 4), capped.state());
    }

    /** Elapsed rounds shorten a condition and remove it exactly when the duration is used up. */
    @Test
    void elapsedRoundsExpireTheCondition() {
        var active = new ConditionRules.Active("c", 2, 4);
        assertEquals(active, ConditionRules.elapse(active, 0).orElseThrow());
        assertEquals(new ConditionRules.Active("c", 2, 1), ConditionRules.elapse(active, 3).orElseThrow());
        assertTrue(ConditionRules.elapse(active, 4).isEmpty());
        assertTrue(ConditionRules.elapse(active, Long.MAX_VALUE).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ConditionRules.elapse(active, -1));
    }

    /** Malformed definitions and mismatched states are refused rather than normalized. */
    @Test
    void rejectsInvalidStates() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConditionRules.Definition("c", ConditionRules.Stacking.REFRESH, 4, 2, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConditionRules.Definition("c", ConditionRules.Stacking.NONE, 0, 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConditionRules.Active("c", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ConditionRules.Active("c", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> ConditionRules.apply(
                definition(ConditionRules.Stacking.REFRESH, 1), Optional.of(new ConditionRules.Active("other", 1, 1))));
    }

    private static ConditionRules.Definition definition(ConditionRules.Stacking stacking, int maxStacks) {
        return new ConditionRules.Definition("c", stacking, 4, maxStacks, Map.of("might", -1));
    }
}
