package org.rpgengine.rules;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Effective sheet: condition modifiers inside attribute ranges, derived values and absent inputs. */
class CharacterSheetTest {
    private static final Map<String, CharacterSheet.Range> RANGES = Map.of(
            "might", new CharacterSheet.Range(0, 4), "awareness", new CharacterSheet.Range(0, 4),
            "resolve", new CharacterSheet.Range(0, 4));
    private static final List<CharacterSheet.Derived> DERIVED = List.of(
            new CharacterSheet.Derived("guard", StatFormula.parse(Map.of("add", List.of(Map.of("attribute", "might"),
                    Map.of("skill", "bladework"))), reference -> reference), 0, 7),
            new CharacterSheet.Derived("focus", StatFormula.parse(Map.of("add", List.of(Map.of("attribute", "awareness"),
                    Map.of("attribute", "resolve"))), reference -> reference), 0, 8));
    private static final Map<String, ConditionRules.Definition> CONDITIONS = Map.of(
            "dazed", new ConditionRules.Definition("dazed", ConditionRules.Stacking.REFRESH, 3, 1, Map.of("might", -2)),
            "rattled", new ConditionRules.Definition("rattled", ConditionRules.Stacking.STACK, 4, 3,
                    Map.of("might", -1, "awareness", -1)),
            "empowered", new ConditionRules.Definition("empowered", ConditionRules.Stacking.NONE, 4, 1, Map.of("might", 3)));

    /** Construct a stateless fixture. */
    CharacterSheetTest() {
    }

    /** Without conditions the sheet shows the stored values and derived values from the formulas. */
    @Test
    void derivesFromBaseValues() {
        var sheet = compute(Map.of("might", 3L, "awareness", 2L), Map.of("bladework", 2L), List.of());
        assertEquals(Map.of("might", 3L, "awareness", 2L), sheet.attributes());
        assertEquals(Map.of("guard", 5L), sheet.derived());
        // focus needs resolve, which this actor does not hold, so it is absent rather than zero.
        assertFalse(sheet.derived().containsKey("focus"));
    }

    /** Stacks multiply a modifier and the result is held inside the attribute's declared range. */
    @Test
    void conditionsMoveAttributesWithinTheirRange() {
        var base = Map.of("might", 1L, "awareness", 2L, "resolve", 4L);
        var stacked = compute(base, Map.of(), List.of(new ConditionRules.Active("rattled", 2, 3)));
        assertEquals(0L, stacked.attributes().get("might"));
        assertEquals(0L, stacked.attributes().get("awareness"));
        assertEquals(4L, stacked.attributes().get("resolve"));
        assertEquals(Map.of("guard", 0L, "focus", 4L), stacked.derived());
        var boosted = compute(Map.of("might", 3L, "awareness", 0L, "resolve", 0L), Map.of(),
                List.of(new ConditionRules.Active("empowered", 1, 2), new ConditionRules.Active("dazed", 1, 1)));
        // 3 + 3 - 2 = 4, the range maximum.
        assertEquals(4L, boosted.attributes().get("might"));
    }

    /** The sheet is a pure function: equal inputs give equal sheets in sorted order. */
    @Test
    void isDeterministic() {
        var base = Map.of("might", 2L, "awareness", 1L, "resolve", 0L);
        var one = compute(base, Map.of("bladework", 1L), List.of(new ConditionRules.Active("dazed", 1, 2)));
        var two = compute(base, Map.of("bladework", 1L), List.of(new ConditionRules.Active("dazed", 1, 2)));
        assertEquals(one, two);
        assertEquals(List.of("awareness", "might", "resolve"), List.copyOf(one.attributes().keySet().stream().sorted().toList()));
    }

    /** Stored values that contradict the pinned rules are reported, never shown. */
    @Test
    void failsClosedOnMismatchedState() {
        assertThrows(IllegalStateException.class, () -> compute(Map.of("might", 9L), Map.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> compute(Map.of("grace", 1L), Map.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> compute(Map.of("might", 1L), Map.of(),
                List.of(new ConditionRules.Active("unknown", 1, 1))));
        // A formula result outside its declared bounds is corrupt content, not a value.
        var tight = List.of(new CharacterSheet.Derived("guard", StatFormula.parse(Map.of("add", List.of(
                Map.of("attribute", "might"), Map.of("skill", "bladework"))), reference -> reference), 0, 3));
        assertThrows(IllegalStateException.class, () -> CharacterSheet.compute(RANGES, tight, Map.of("might", 4L),
                Map.of(), List.of(), CONDITIONS));
    }

    private static CharacterSheet compute(Map<String, Long> base, Map<String, Long> skills,
            List<ConditionRules.Active> active) {
        return CharacterSheet.compute(RANGES, DERIVED, base, skills, active, CONDITIONS);
    }
}
