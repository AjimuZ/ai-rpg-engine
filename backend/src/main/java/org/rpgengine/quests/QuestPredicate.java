package org.rpgengine.quests;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.rpgengine.content.CampaignPackSnapshot;
import org.rpgengine.content.CompiledPack;
import org.rpgengine.rules.WorldClock;

/**
 * Closed, immutable tree of the pack expression language in world scope (docs/09 section 7) and its
 * evaluator (P10.3). The pack compiler has already type-checked every node (numbers and booleans
 * never mix), bounded depth and size and rejected division by zero, so {@link #compile} only copies
 * validated data and {@link #test}/{@link #value} only read committed facts through {@link Facts}.
 *
 * <p>Atoms: {@code visited} (a location the player has stood in), {@code possesses} (units of an item
 * definition the player holds), {@code questStatus} and {@code objective} (a satisfied objective of a
 * quest) and {@code worldTime} (whole elapsed units of the bounded in-world clock). No atom rolls dice
 * or reads the wall clock, so evaluation is a pure function of the facts and never depends on randomness
 * or real time; the in-world clock moves only when a committed command resolves a duration.
 *
 * <p>{@code all} and {@code any} stop at the first deciding operand; with pure atoms this only saves
 * work.
 */
public sealed interface QuestPredicate {
    /** Operators accepted by the compiler in world scope. */
    Set<String> OPERATORS = Set.of("add", "mul", "min", "max", "sub", "div", "clamp", "eq", "ne", "lt", "le", "gt",
            "ge", "all", "any", "not");

    /**
     * Committed facts a predicate may read. Implementations answer from one consistent view; an
     * evaluation is therefore a pure function of it.
     */
    interface Facts {
        /** @return whether the player has been at the canonical location */
        boolean visited(String location);

        /** @return units of the canonical item definition the player holds, zero when none */
        long possessed(String item);

        /** @return the quest's current status; an unknown quest reads as undiscovered */
        QuestStatus status(String quest);

        /** @return whether the objective of the canonical quest has been satisfied (it stays satisfied) */
        boolean satisfied(String quest, String objective);

        /** @return in-world seconds elapsed since the campaign began, as the bounded world clock reads after this commit */
        long worldTime();
    }

    /**
     * Evaluate a boolean node.
     *
     * @param facts the committed facts
     * @return the truth value
     * @throws IllegalStateException if the node is numeric, which a compiled pack never produces
     */
    boolean test(Facts facts);

    /**
     * Evaluate a numeric node with exact long arithmetic.
     *
     * @param facts the committed facts
     * @return the value
     * @throws IllegalStateException if the node is boolean, or on arithmetic overflow (the compiler
     *         proved bounds, so this signals a defect)
     */
    long value(Facts facts);

    /**
     * Copy one validated world expression.
     *
     * @param value parsed expression
     * @param snapshot compiled closure used to canonicalize references
     * @param pack pack declaring the quest, which owns its unqualified references
     * @return the immutable tree
     * @throws IllegalArgumentException for a shape the compiler would have rejected
     */
    static QuestPredicate compile(Object value, CampaignPackSnapshot snapshot, CompiledPack pack) {
        if (value instanceof Boolean flag) {
            return new Flag(flag);
        }
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            // The compiler accepts integral decimals such as 2.0 as integer constants.
            return new Constant(new BigDecimal(value.toString()).toBigIntegerExact().longValueExact());
        }
        if (!(value instanceof Map<?, ?> node) || node.size() != 1) {
            throw new IllegalArgumentException("compiled expression has an unexpected shape");
        }
        String name = (String) node.keySet().iterator().next();
        Object argument = node.get(name);
        if (OPERATORS.contains(name)) {
            List<?> operands = argument instanceof List<?> list ? list : List.of(argument);
            var compiled = new ArrayList<QuestPredicate>();
            for (Object operand : operands) {
                compiled.add(compile(operand, snapshot, pack));
            }
            return new Operation(name, compiled);
        }
        return switch (name) {
            case "visited" -> new Visited(snapshot.resolve(pack, (String) argument, "location").id());
            case "possesses" -> {
                var fields = (Map<?, ?>) argument;
                yield new Possesses(snapshot.resolve(pack, (String) fields.get("item"), "item").id(),
                        fields.get("quantity") instanceof Number quantity ? quantity.intValue() : 1);
            }
            case "questStatus" -> {
                var fields = (Map<?, ?>) argument;
                yield new Status(snapshot.resolve(pack, (String) fields.get("quest"), "quest").id(),
                        QuestStatus.parse((String) fields.get("status")).orElseThrow(
                                () -> new IllegalArgumentException("unknown quest status")));
            }
            case "worldTime" -> new WorldTime((String) argument);
            case "objective" -> {
                var fields = (Map<?, ?>) argument;
                yield new Objective(snapshot.resolve(pack, (String) fields.get("quest"), "quest").id(),
                        (String) fields.get("id"));
            }
            default -> throw new IllegalArgumentException("expression atom is not readable in world scope");
        };
    }

    /** Boolean literal. */
    record Flag(boolean value) implements QuestPredicate {
        @Override public boolean test(Facts facts) { return value; }
        @Override public long value(Facts facts) { throw numeric(); }
    }

    /** Integer literal. */
    record Constant(long value) implements QuestPredicate {
        @Override public boolean test(Facts facts) { throw boolean_(); }
        @Override public long value(Facts facts) { return value; }
    }

    /**
     * The player has been at a location.
     *
     * @param location canonical location ID
     */
    record Visited(String location) implements QuestPredicate {
        /** Require the reference. */
        public Visited { Objects.requireNonNull(location, "location"); }
        @Override public boolean test(Facts facts) { return facts.visited(location); }
        @Override public long value(Facts facts) { throw numeric(); }
    }

    /**
     * The player holds at least {@code quantity} units of an item definition, in any stacks, worn or not.
     *
     * @param item canonical item definition ID
     * @param quantity 1..9999 units
     */
    record Possesses(String item, int quantity) implements QuestPredicate {
        /** Require the reference and a bounded count. */
        public Possesses {
            Objects.requireNonNull(item, "item");
            if (quantity < 1 || quantity > 9999) {
                throw new IllegalArgumentException("possessed quantity must be 1..9999");
            }
        }
        @Override public boolean test(Facts facts) { return facts.possessed(item) >= quantity; }
        @Override public long value(Facts facts) { throw numeric(); }
    }

    /**
     * A quest currently has exactly this status.
     *
     * @param quest canonical quest ID
     * @param status the status compared against
     */
    record Status(String quest, QuestStatus status) implements QuestPredicate {
        /** Require both parts. */
        public Status {
            Objects.requireNonNull(quest, "quest");
            Objects.requireNonNull(status, "status");
        }
        @Override public boolean test(Facts facts) { return facts.status(quest) == status; }
        @Override public long value(Facts facts) { throw numeric(); }
    }

    /**
     * An objective of a quest has been satisfied. Satisfaction is sticky: giving the proof away later
     * does not undo it, because progress is derived from what happened, not from the present state.
     *
     * @param quest canonical quest ID
     * @param objective objective ID within that quest
     */
    record Objective(String quest, String objective) implements QuestPredicate {
        /** Require both parts. */
        public Objective {
            Objects.requireNonNull(quest, "quest");
            Objects.requireNonNull(objective, "objective");
        }
        @Override public boolean test(Facts facts) { return facts.satisfied(quest, objective); }
        @Override public long value(Facts facts) { throw numeric(); }
    }

    /**
     * The bounded in-world clock read in whole units, rounded down. Numeric; combine it with a
     * comparison, for example {@code {"ge": [{"worldTime": "hours"}, 8]}}.
     *
     * @param unit one of {@code seconds}, {@code minutes}, {@code hours}, {@code days}
     */
    record WorldTime(String unit) implements QuestPredicate {
        /** Require a unit the clock can express. */
        public WorldTime {
            WorldClock.inUnits(0, Objects.requireNonNull(unit, "unit"));
        }
        @Override public boolean test(Facts facts) { throw boolean_(); }
        @Override public long value(Facts facts) { return WorldClock.inUnits(facts.worldTime(), unit); }
    }

    /**
     * Operator application in authored operand order.
     *
     * @param operator one of {@link #OPERATORS}
     * @param operands compiled operands; {@code not} has exactly one
     */
    record Operation(String operator, List<QuestPredicate> operands) implements QuestPredicate {
        /** Freeze the operands. */
        public Operation { operands = List.copyOf(operands); }

        @Override
        public boolean test(Facts facts) {
            return switch (operator) {
                case "eq", "ne", "lt", "le", "gt", "ge" -> {
                    long left = operands.get(0).value(facts);
                    long right = operands.get(1).value(facts);
                    yield switch (operator) {
                        case "eq" -> left == right;
                        case "ne" -> left != right;
                        case "lt" -> left < right;
                        case "le" -> left <= right;
                        case "gt" -> left > right;
                        default -> left >= right;
                    };
                }
                case "all" -> {
                    for (var operand : operands) {
                        if (!operand.test(facts)) {
                            yield false;
                        }
                    }
                    yield true;
                }
                case "any" -> {
                    for (var operand : operands) {
                        if (operand.test(facts)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case "not" -> !operands.getFirst().test(facts);
                default -> throw boolean_();
            };
        }

        @Override
        public long value(Facts facts) {
            switch (operator) {
                case "add", "mul", "min", "max" -> {
                    long result = operands.getFirst().value(facts);
                    for (int index = 1; index < operands.size(); index++) {
                        long next = operands.get(index).value(facts);
                        result = switch (operator) {
                            case "add" -> Math.addExact(result, next);
                            case "mul" -> Math.multiplyExact(result, next);
                            case "min" -> Math.min(result, next);
                            default -> Math.max(result, next);
                        };
                    }
                    return result;
                }
                case "sub" -> {
                    return Math.subtractExact(operands.get(0).value(facts), operands.get(1).value(facts));
                }
                case "div" -> {
                    long dividend = operands.get(0).value(facts);
                    long divisor = operands.get(1).value(facts);
                    if (divisor == 0) {
                        throw new IllegalStateException("division by zero");
                    }
                    // Java long division truncates toward zero, matching the compiler's interval analysis.
                    return dividend / divisor;
                }
                case "clamp" -> {
                    long subject = operands.get(0).value(facts);
                    return Math.min(Math.max(subject, operands.get(1).value(facts)), operands.get(2).value(facts));
                }
                default -> throw numeric();
            }
        }
    }

    /** Defect: a boolean node was read as a number. */
    private static IllegalStateException numeric() {
        return new IllegalStateException("boolean used as a number");
    }

    /** Defect: a numeric node was read as a boolean. */
    private static IllegalStateException boolean_() {
        return new IllegalStateException("number used as a boolean");
    }
}
