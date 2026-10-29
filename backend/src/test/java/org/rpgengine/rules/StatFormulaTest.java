package org.rpgengine.rules;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Derived-statistic formulas: exact evaluation, truncating division and fail-closed parsing. */
class StatFormulaTest {
    private static final StatFormula.Inputs INPUTS = new StatFormula.Inputs() {
        @Override
        public long attribute(String id) {
            Long value = Map.of("might", 3L, "awareness", 2L).get(id);
            if (value == null) {
                throw new IllegalStateException("no attribute " + id);
            }
            return value;
        }

        @Override
        public long skill(String id) {
            return Map.of("fieldwork", 2L).getOrDefault(id, 0L);
        }
    };

    /** Construct a stateless fixture. */
    StatFormulaTest() {
    }

    /** Every numeric operator follows the compiler's interval semantics, including clamp order. */
    @Test
    void evaluatesOperatorsExactly() {
        assertEquals(7, eval(Map.of("add", List.of(Map.of("attribute", "might"), Map.of("attribute", "awareness"),
                Map.of("skill", "fieldwork")))));
        assertEquals(6, eval(Map.of("mul", List.of(Map.of("attribute", "might"), bigint(2)))));
        assertEquals(1, eval(Map.of("sub", List.of(Map.of("attribute", "might"), Map.of("attribute", "awareness")))));
        // Division truncates toward zero for negative dividends too.
        assertEquals(-1, eval(Map.of("div", List.of(bigint(-3), bigint(2)))));
        assertEquals(1, eval(Map.of("div", List.of(Map.of("attribute", "might"), bigint(2)))));
        assertEquals(2, eval(Map.of("min", List.of(Map.of("attribute", "might"), Map.of("attribute", "awareness")))));
        assertEquals(3, eval(Map.of("max", List.of(Map.of("attribute", "might"), Map.of("attribute", "awareness")))));
        assertEquals(5, eval(Map.of("clamp", List.of(bigint(9), bigint(0), bigint(5)))));
        assertEquals(0, eval(Map.of("clamp", List.of(bigint(-9), bigint(0), bigint(5)))));
        assertEquals(4, eval(new BigDecimal("4.0")));
    }

    /** The attribute set a formula reads is reported once each, so absent inputs can be detected. */
    @Test
    void reportsAttributesRead() {
        var formula = parse(Map.of("add", List.of(Map.of("attribute", "might"), Map.of("skill", "fieldwork"),
                Map.of("mul", List.of(Map.of("attribute", "might"), Map.of("attribute", "awareness"))))));
        assertEquals(Set.of("might", "awareness"), StatFormula.attributesRead(formula));
    }

    /** Dice, resources, booleans and unknown operators are not part of a derived formula. */
    @Test
    void rejectsAtomsAFormulaMayNotRead() {
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dice", Map.of("count", 1, "sides", 6))));
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("resource", "vigor")));
        assertThrows(IllegalArgumentException.class, () -> parse(Boolean.TRUE));
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("lt", List.of(bigint(1), bigint(2)))));
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("add", List.of(bigint(1)), "mul", List.of())));
    }

    /** Corrupt content (zero divisor, overflow, missing attribute) fails loudly instead of yielding a number. */
    @Test
    void failsClosedOnCorruptContent() {
        assertThrows(IllegalStateException.class, () -> eval(Map.of("div", List.of(bigint(1), bigint(0)))));
        assertThrows(IllegalStateException.class,
                () -> eval(Map.of("mul", List.of(bigint(Long.MAX_VALUE), bigint(2)))));
        assertThrows(IllegalStateException.class, () -> eval(Map.of("attribute", "resolve")));
    }

    private static long eval(Object expression) {
        return parse(expression).evaluate(INPUTS);
    }

    private static StatFormula parse(Object expression) {
        return StatFormula.parse(expression, reference -> reference);
    }

    private static BigInteger bigint(long value) {
        return BigInteger.valueOf(value);
    }
}
