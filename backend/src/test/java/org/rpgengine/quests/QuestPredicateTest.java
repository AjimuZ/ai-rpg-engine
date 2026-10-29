package org.rpgengine.quests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Pure evaluation of world predicates over committed facts (P10.3): atoms, logic, arithmetic and misuse. */
class QuestPredicateTest {
    /** Fixed facts: the player visited the grove, holds 3 coins, quest "a" is active with objective "x" satisfied. */
    private static final QuestPredicate.Facts FACTS = new QuestPredicate.Facts() {
        @Override public boolean visited(String location) { return location.equals("grove"); }

        @Override public long possessed(String item) { return item.equals("coin") ? 3 : 0; }

        @Override
        public QuestStatus status(String quest) {
            return quest.equals("a") ? QuestStatus.ACTIVE : QuestStatus.UNDISCOVERED;
        }

        @Override public boolean satisfied(String quest, String objective) { return quest.equals("a") && objective.equals("x"); }
    };

    /** Construct the stateless fixture. */
    QuestPredicateTest() { }

    /** Each atom reads exactly its own fact. */
    @Test
    void atomsReadCommittedFacts() {
        assertTrue(new QuestPredicate.Visited("grove").test(FACTS));
        assertFalse(new QuestPredicate.Visited("trailhead").test(FACTS));
        assertTrue(new QuestPredicate.Possesses("coin", 3).test(FACTS));
        assertFalse(new QuestPredicate.Possesses("coin", 4).test(FACTS));
        assertFalse(new QuestPredicate.Possesses("gem", 1).test(FACTS));
        assertTrue(new QuestPredicate.Status("a", QuestStatus.ACTIVE).test(FACTS));
        assertFalse(new QuestPredicate.Status("a", QuestStatus.COMPLETED).test(FACTS));
        assertTrue(new QuestPredicate.Status("unknown", QuestStatus.UNDISCOVERED).test(FACTS));
        assertTrue(new QuestPredicate.Objective("a", "x").test(FACTS));
        assertFalse(new QuestPredicate.Objective("a", "y").test(FACTS));
        assertFalse(new QuestPredicate.Objective("b", "x").test(FACTS));
    }

    /** Logic combines operands in order, and comparisons use exact integer arithmetic. */
    @Test
    void logicAndArithmeticAreExact() {
        var yes = new QuestPredicate.Flag(true);
        var no = new QuestPredicate.Flag(false);
        assertTrue(new QuestPredicate.Operation("all", List.of(yes, yes)).test(FACTS));
        assertFalse(new QuestPredicate.Operation("all", List.of(yes, no)).test(FACTS));
        assertTrue(new QuestPredicate.Operation("any", List.of(no, yes)).test(FACTS));
        assertFalse(new QuestPredicate.Operation("any", List.of(no, no)).test(FACTS));
        assertTrue(new QuestPredicate.Operation("not", List.of(no)).test(FACTS));
        var sum = new QuestPredicate.Operation("add", List.of(new QuestPredicate.Constant(2), new QuestPredicate.Constant(3)));
        assertEquals(5, sum.value(FACTS));
        assertEquals(10, new QuestPredicate.Operation("mul", List.of(sum, new QuestPredicate.Constant(2))).value(FACTS));
        assertEquals(-2, new QuestPredicate.Operation("div", List.of(new QuestPredicate.Constant(-5),
                new QuestPredicate.Constant(2))).value(FACTS), "division truncates toward zero");
        assertEquals(4, new QuestPredicate.Operation("clamp", List.of(new QuestPredicate.Constant(9),
                new QuestPredicate.Constant(1), new QuestPredicate.Constant(4))).value(FACTS));
        assertTrue(new QuestPredicate.Operation("ge", List.of(sum, new QuestPredicate.Constant(5))).test(FACTS));
        assertFalse(new QuestPredicate.Operation("lt", List.of(sum, new QuestPredicate.Constant(5))).test(FACTS));
        assertTrue(new QuestPredicate.Operation("ne", List.of(sum, new QuestPredicate.Constant(4))).test(FACTS));
    }

    /** Short-circuit evaluation never reads an operand it does not need. */
    @Test
    void shortCircuitSkipsDecidedOperands() {
        var poisoned = new QuestPredicate.Constant(1);
        assertFalse(new QuestPredicate.Operation("all", List.of(new QuestPredicate.Flag(false), poisoned)).test(FACTS));
        assertTrue(new QuestPredicate.Operation("any", List.of(new QuestPredicate.Flag(true), poisoned)).test(FACTS));
    }

    /** A number read as a boolean, a boolean read as a number and a zero divisor are defects, not silent values. */
    @Test
    void typeMisuseFails() {
        assertThrows(IllegalStateException.class, () -> new QuestPredicate.Constant(1).test(FACTS));
        assertThrows(IllegalStateException.class, () -> new QuestPredicate.Flag(true).value(FACTS));
        assertThrows(IllegalStateException.class, () -> new QuestPredicate.Visited("grove").value(FACTS));
        assertThrows(IllegalStateException.class, () -> new QuestPredicate.Operation("div", List.of(
                new QuestPredicate.Constant(1), new QuestPredicate.Constant(0))).value(FACTS));
        assertThrows(IllegalStateException.class, () -> new QuestPredicate.Operation("add", List.of(
                new QuestPredicate.Constant(1), new QuestPredicate.Constant(1))).test(FACTS));
        assertThrows(IllegalArgumentException.class, () -> new QuestPredicate.Possesses("coin", 0));
    }

    /** Statuses round-trip through their wire names; an unknown spelling is empty. */
    @Test
    void statusWireNames() {
        for (QuestStatus status : QuestStatus.values()) {
            assertEquals(status, QuestStatus.parse(status.wire()).orElseThrow());
        }
        assertTrue(QuestStatus.parse("paused").isEmpty());
        assertEquals(Set.of(QuestStatus.COMPLETED, QuestStatus.FAILED),
                Set.copyOf(java.util.Arrays.stream(QuestStatus.values()).filter(QuestStatus::terminal).toList()));
    }
}
