package org.rpgengine.rules;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Immutable numeric formula of a derived statistic, evaluated from attribute values and skill
 * ranks only (docs/10 section 1, docs/05 section 4).
 *
 * <p>The pack compiler has already proven the shape, the operand counts and an interval for the
 * result, so evaluation here uses exact long arithmetic and treats any violation of those proofs
 * as a corrupt-content error ({@link IllegalStateException}) rather than guessing a value. The
 * language is a strict subset of the pack expression language: constants, {@code attribute},
 * {@code skill} and the numeric operators {@code add mul min max sub div clamp}. Dice and
 * resource atoms are rejected when parsing, so a derived value is a pure function of its inputs
 * and can be recomputed identically at any later time.
 */
public sealed interface StatFormula {
    /** Numeric operators accepted in a formula, matching the compiler's analysis. */
    Set<String> OPERATORS = Set.of("add", "mul", "min", "max", "sub", "div", "clamp");

    /**
     * Read access to the values a formula may consult.
     */
    interface Inputs {
        /**
         * @param id unqualified attribute key
         * @return the actor's effective value of that attribute
         * @throws IllegalStateException if the actor's vocabulary does not hold the attribute
         */
        long attribute(String id);

        /**
         * @param id canonical skill ID
         * @return the actor's rank in that skill; zero when untrained
         */
        long skill(String id);
    }

    /**
     * Evaluate with exact arithmetic.
     *
     * @param inputs attribute and skill values
     * @return the formula value
     * @throws IllegalStateException on overflow or a zero divisor, which compiled content cannot produce
     */
    long evaluate(Inputs inputs);

    /**
     * Collect the attributes a formula reads.
     *
     * @param formula formula to inspect
     * @return unqualified attribute keys in first-read order
     */
    static Set<String> attributesRead(StatFormula formula) {
        var found = new java.util.LinkedHashSet<String>();
        collect(formula, found);
        return found;
    }

    private static void collect(StatFormula formula, Set<String> found) {
        switch (formula) {
            case Attribute attribute -> found.add(attribute.id());
            case Operation operation -> operation.operands().forEach(operand -> collect(operand, found));
            case Constant ignored -> { }
            case Skill ignored -> { }
        }
    }

    /**
     * Copy a validated expression tree.
     *
     * @param value parsed expression from compiled pack data
     * @param skills maps a pack-local skill reference to its canonical ID (identity for root skills)
     * @return the immutable formula
     * @throws IllegalArgumentException for a shape, atom or operator the compiler would have rejected
     */
    static StatFormula parse(Object value, UnaryOperator<String> skills) {
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            // The compiler accepts integral decimals such as 2.0 as integer constants.
            return new Constant(new BigDecimal(value.toString()).toBigIntegerExact().longValueExact());
        }
        if (!(value instanceof Map<?, ?> node) || node.size() != 1) {
            throw new IllegalArgumentException("derived formula has an unexpected shape");
        }
        String name = (String) node.keySet().iterator().next();
        Object argument = node.get(name);
        if (OPERATORS.contains(name)) {
            var operands = new ArrayList<StatFormula>();
            for (Object operand : (List<?>) argument) {
                operands.add(parse(operand, skills));
            }
            return new Operation(name, operands);
        }
        return switch (name) {
            case "attribute" -> new Attribute((String) argument);
            case "skill" -> new Skill(skills.apply((String) argument));
            default -> throw new IllegalArgumentException("derived formula cannot read '" + name + "'");
        };
    }

    /** Integer literal. */
    record Constant(long value) implements StatFormula {
        @Override
        public long evaluate(Inputs inputs) {
            return value;
        }
    }

    /** Effective value of an attribute by unqualified key. */
    record Attribute(String id) implements StatFormula {
        @Override
        public long evaluate(Inputs inputs) {
            return inputs.attribute(id);
        }
    }

    /** Rank of a skill by canonical ID. */
    record Skill(String id) implements StatFormula {
        @Override
        public long evaluate(Inputs inputs) {
            return inputs.skill(id);
        }
    }

    /**
     * Operator application in authored operand order.
     *
     * @param operator one of {@link StatFormula#OPERATORS}
     * @param operands compiled operands (2..8 for variadic operators, 2 for sub/div, 3 for clamp)
     */
    record Operation(String operator, List<StatFormula> operands) implements StatFormula {
        /** Freeze operands. */
        public Operation {
            operands = List.copyOf(operands);
        }

        @Override
        public long evaluate(Inputs inputs) {
            try {
                return switch (operator) {
                    case "add", "mul", "min", "max" -> fold(inputs);
                    case "sub" -> Math.subtractExact(operands.get(0).evaluate(inputs), operands.get(1).evaluate(inputs));
                    case "div" -> {
                        long dividend = operands.get(0).evaluate(inputs);
                        long divisor = operands.get(1).evaluate(inputs);
                        if (divisor == 0) {
                            throw new IllegalStateException("derived formula divides by zero");
                        }
                        // Truncating division, matching the compiler's interval analysis.
                        yield dividend / divisor;
                    }
                    // clamp(v, lo, hi) = min(max(v, lo), hi); the compiler proved lo <= hi.
                    default -> Math.min(Math.max(operands.get(0).evaluate(inputs), operands.get(1).evaluate(inputs)),
                            operands.get(2).evaluate(inputs));
                };
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException("derived formula overflows", overflow);
            }
        }

        /** Fold a variadic operator left to right. */
        private long fold(Inputs inputs) {
            long result = operands.getFirst().evaluate(inputs);
            for (int index = 1; index < operands.size(); index++) {
                long next = operands.get(index).evaluate(inputs);
                result = switch (operator) {
                    case "add" -> Math.addExact(result, next);
                    case "mul" -> Math.multiplyExact(result, next);
                    case "min" -> Math.min(result, next);
                    default -> Math.max(result, next);
                };
            }
            return result;
        }
    }
}
