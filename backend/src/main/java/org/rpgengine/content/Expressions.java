package org.rpgengine.content;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rpgengine.rules.WorldClock;

/**
 * Static analyzer for the closed, data-only expression language used by formulas, effect amounts,
 * conditional effects and quest predicates (docs/09 section 7).
 *
 * <p>An expression is an integer constant, a boolean constant or a single-key object whose key is
 * an operator or an atom. Nothing is evaluated here: the analyzer type-checks every node, enforces
 * the depth/node/dice limits and derives a conservative integer interval for numeric results so
 * the compiler can prove that no division by zero or intermediate overflow is possible and that
 * effect amounts stay within their bounds. Runtime evaluation belongs to the rules engine (P11).
 *
 * <p>Scopes keep evaluation deterministic and side-effect free: {@link Scope#ACTOR} expressions
 * read an acting character (attributes, resources, skill ranks, dice); {@link Scope#WORLD}
 * predicates read canonical world state (visited locations, possessions, quest status, objectives and the bounded world clock)
 * and may not roll dice, so quest progress never depends on randomness.
 *
 * <p>Failure behavior: the first violation throws a located {@link PackException} with code
 * {@code LIMIT} (depth or node count), {@code COST} (dice budget), {@code DIVISION_BY_ZERO},
 * {@code OVERFLOW}, {@code RANGE} (inverted clamp bounds), {@code REFERENCE_UNKNOWN}/{@code
 * REFERENCE_KIND} (atoms) or {@code SCHEMA} (shape, type or scope errors).
 */
final class Expressions {
    /** Maximum nesting depth, counting the root as 1 (docs/09 section 7). */
    static final int MAX_DEPTH = 12;
    /** Maximum nodes per expression, counting constants and atoms (docs/09 section 7). */
    static final int MAX_NODES = 200;
    /** Maximum total dice rolled by one expression; bounds evaluation cost and randomness draws. */
    static final int MAX_DICE = 40;
    /** Maximum operands of a variadic operator. */
    static final int MAX_OPERANDS = 8;
    /** Bound on every intermediate result, far inside long arithmetic, so products of bounds cannot wrap. */
    static final long INTERMEDIATE_MAX = 1_000_000_000L;
    /** Bound on literal constants, matching the compiler's general value bound. */
    static final long CONSTANT_MAX = 1_000_000L;

    /** Quest statuses of the docs/10 section 5 lifecycle. */
    /** Units a {@code worldTime} atom can read the clock in. */
    static final Set<String> CLOCK_UNITS = Set.of("seconds", "minutes", "hours", "days");
    static final Set<String> QUEST_STATUSES = Set.of("undiscovered", "available", "active", "completed", "failed",
            "suspended");

    private static final Set<String> VARIADIC = Set.of("add", "mul", "min", "max");
    private static final Set<String> BINARY = Set.of("sub", "div");
    private static final Set<String> COMPARISONS = Set.of("eq", "ne", "lt", "le", "gt", "ge");
    private static final Set<String> LOGIC = Set.of("all", "any");

    private Expressions() {
    }

    /** Which state an expression may read. */
    enum Scope {
        /** Acting character: attributes, resources, skill ranks and dice. */
        ACTOR,
        /** Canonical world: visited locations, possessions, quest status, objectives and the world clock. */
        WORLD
    }

    /** Result type of an expression; booleans are not numbers and cannot be mixed with them. */
    enum Type {
        NUMBER,
        BOOLEAN
    }

    /**
     * A quest the expression requires to have progressed, found through conjunctions only.
     * The compiler adds these as dependency-graph edges for cycle detection.
     *
     * @param quest the referenced quest ID (local or {@code pack#id})
     * @param pointer source pointer of the atom, for the cycle diagnostic
     */
    record QuestRequirement(String quest, String pointer) {
    }

    /**
     * Analysis outcome.
     *
     * @param type result type
     * @param min inclusive lower bound (0 for booleans)
     * @param max inclusive upper bound (1 for booleans)
     * @param nodes total node count
     * @param dice total dice rolled
     * @param requirements quests whose progress this expression unconditionally requires
     */
    record Result(Type type, long min, long max, int nodes, int dice, List<QuestRequirement> requirements) {
    }

    /** Pack facts the analyzer needs; implemented by the compiler run over already validated rules. */
    interface Context extends Fields.Resolver {
        /** Inclusive {min, max} of an attribute, or null when the attribute is not defined. */
        int[] attributeRange(String id);

        /** Maximum of a resource, or null when the resource is not defined. */
        Integer resourceMax(String id);

        /** Maximum rank of an already resolved skill reference (a conservative bound for external skills). */
        int skillMax(String ref);

        /** Objective IDs of an already resolved quest reference, or null when the quest is not local. */
        Set<String> objectives(String questRef);
    }

    /**
     * Analyze one expression and require its result type.
     *
     * @param document owning document, for locations
     * @param pointer pointer of the expression value
     * @param value parsed JSON value of the expression
     * @param scope readable state
     * @param expected required result type
     * @param context pack facts and reference resolver
     * @return type, bounds, cost and quest requirements
     * @throws PackException on the first violation, located at the offending node
     */
    static Result analyze(PackDocument document, String pointer, Object value, Scope scope, Type expected,
            Context context) {
        var walker = new Walker(document, scope, context);
        Interval interval = walker.walk(value, pointer, 1, true);
        if (interval.type != expected) {
            throw new PackException("SCHEMA", document.location(pointer),
                    "Expected a " + expected.name().toLowerCase() + " expression");
        }
        return new Result(interval.type, interval.min, interval.max, walker.nodes, walker.dice,
                List.copyOf(walker.requirements));
    }

    /** Typed interval of one subexpression. */
    private record Interval(Type type, long min, long max) {
        static Interval bool() {
            return new Interval(Type.BOOLEAN, 0, 1);
        }
    }

    /** Single-use recursive walker holding the running node and dice counters. */
    private static final class Walker {
        private final PackDocument document;
        private final Scope scope;
        private final Context context;
        private final List<QuestRequirement> requirements = new ArrayList<>();
        private int nodes;
        private int dice;

        Walker(PackDocument document, Scope scope, Context context) {
            this.document = document;
            this.scope = scope;
            this.context = context;
        }

        /**
         * Analyze one node.
         *
         * @param required true while the node is reached only through conjunctions, so a quest atom
         *                 here is an unconditional requirement for the cycle graph
         */
        Interval walk(Object value, String pointer, int depth, boolean required) {
            // Limits are checked before descending so hostile nesting cannot exhaust the stack.
            if (depth > MAX_DEPTH) {
                throw error("LIMIT", pointer, "Expression nesting exceeds depth " + MAX_DEPTH);
            }
            if (++nodes > MAX_NODES) {
                throw error("LIMIT", pointer, "Expression exceeds " + MAX_NODES + " nodes");
            }
            if (value instanceof Boolean) {
                return Interval.bool();
            }
            if (value instanceof BigInteger || value instanceof BigDecimal) {
                long constant = constant(value, pointer);
                return new Interval(Type.NUMBER, constant, constant);
            }
            if (!(value instanceof Map<?, ?> node) || node.size() != 1) {
                throw error("SCHEMA", pointer, "Expected an integer, a boolean or a single-key operator object");
            }
            String operator = (String) node.keySet().iterator().next();
            Object argument = node.get(operator);
            String at = PackDocument.child(pointer, operator);
            if (VARIADIC.contains(operator)) {
                return variadic(operator, operands(argument, at, 2, MAX_OPERANDS), at, depth);
            }
            if (BINARY.contains(operator)) {
                List<?> pair = operands(argument, at, 2, 2);
                Interval left = number(pair.get(0), element(at, 0), depth);
                Interval right = number(pair.get(1), element(at, 1), depth);
                return operator.equals("sub")
                        ? checked(left.min - right.max, left.max - right.min, at)
                        : divide(left, right, at);
            }
            if (operator.equals("clamp")) {
                List<?> triple = operands(argument, at, 3, 3);
                Interval subject = number(triple.get(0), element(at, 0), depth);
                Interval low = number(triple.get(1), element(at, 1), depth);
                Interval high = number(triple.get(2), element(at, 2), depth);
                if (low.max > high.min) {
                    throw error("RANGE", at, "Clamp lower bound can exceed its upper bound");
                }
                // clamp(v, lo, hi) = min(max(v, lo), hi), applied to interval bounds.
                long floorMin = Math.max(subject.min, low.min);
                long floorMax = Math.max(subject.max, low.max);
                return checked(Math.min(floorMin, high.min), Math.min(floorMax, high.max), at);
            }
            if (COMPARISONS.contains(operator)) {
                List<?> pair = operands(argument, at, 2, 2);
                number(pair.get(0), element(at, 0), depth);
                number(pair.get(1), element(at, 1), depth);
                return Interval.bool();
            }
            if (LOGIC.contains(operator)) {
                List<?> list = operands(argument, at, 1, MAX_OPERANDS);
                // "all" keeps requirements unconditional; one alternative of a multi-way "any" does not.
                boolean pass = required && (operator.equals("all") || list.size() == 1);
                for (int index = 0; index < list.size(); index++) {
                    bool(list.get(index), element(at, index), depth, pass);
                }
                return Interval.bool();
            }
            if (operator.equals("not")) {
                // A negated quest status is not a progress requirement, so it adds no graph edge.
                bool(argument, at, depth, false);
                return Interval.bool();
            }
            return atom(operator, argument, at, required);
        }

        /** Analyze a numeric operand; a boolean is a type error. */
        private Interval number(Object value, String pointer, int depth) {
            Interval interval = walk(value, pointer, depth + 1, false);
            if (interval.type != Type.NUMBER) {
                throw error("SCHEMA", pointer, "Expected a numeric expression");
            }
            return interval;
        }

        /** Analyze a boolean operand; a number is a type error. */
        private void bool(Object value, String pointer, int depth, boolean required) {
            if (walk(value, pointer, depth + 1, required).type != Type.BOOLEAN) {
                throw error("SCHEMA", pointer, "Expected a boolean expression");
            }
        }

        /** Fold add/mul/min/max over 2..8 numeric operands, checking overflow after every step. */
        private Interval variadic(String operator, List<?> list, String at, int depth) {
            Interval result = number(list.get(0), element(at, 0), depth);
            for (int index = 1; index < list.size(); index++) {
                Interval next = number(list.get(index), element(at, index), depth);
                result = switch (operator) {
                    case "add" -> checked(result.min + next.min, result.max + next.max, at);
                    case "mul" -> {
                        // Both factors are within 1e9, so every corner product fits in a long.
                        long a = result.min * next.min;
                        long b = result.min * next.max;
                        long c = result.max * next.min;
                        long d = result.max * next.max;
                        yield checked(Math.min(Math.min(a, b), Math.min(c, d)), Math.max(Math.max(a, b), Math.max(c, d)), at);
                    }
                    case "min" -> new Interval(Type.NUMBER, Math.min(result.min, next.min), Math.min(result.max, next.max));
                    default -> new Interval(Type.NUMBER, Math.max(result.min, next.min), Math.max(result.max, next.max));
                };
            }
            return result;
        }

        /**
         * Truncating integer division. A divisor interval containing zero is rejected; otherwise the
         * divisor has one sign and the quotient is monotone in each operand, so corners bound it.
         */
        private Interval divide(Interval dividend, Interval divisor, String at) {
            if (divisor.min <= 0 && divisor.max >= 0) {
                throw error("DIVISION_BY_ZERO", at, "Divisor range " + divisor.min + ".." + divisor.max + " includes zero");
            }
            long a = dividend.min / divisor.min;
            long b = dividend.min / divisor.max;
            long c = dividend.max / divisor.min;
            long d = dividend.max / divisor.max;
            return checked(Math.min(Math.min(a, b), Math.min(c, d)), Math.max(Math.max(a, b), Math.max(c, d)), at);
        }

        /** Build a numeric interval, rejecting bounds beyond the intermediate limit. */
        private Interval checked(long min, long max, String at) {
            if (min < -INTERMEDIATE_MAX || max > INTERMEDIATE_MAX) {
                throw error("OVERFLOW", at, "Intermediate result can reach " + (max > INTERMEDIATE_MAX ? max : min)
                        + "; the limit is +/-" + INTERMEDIATE_MAX);
            }
            return new Interval(Type.NUMBER, min, max);
        }

        /** Analyze a state-reading atom after checking it is readable in this scope. */
        private Interval atom(String name, Object argument, String at, boolean required) {
            boolean actor = Set.of("attribute", "resource", "skill", "dice").contains(name);
            boolean world = Set.of("visited", "possesses", "questStatus", "objective", "worldTime").contains(name);
            if (!actor && !world) {
                throw error("SCHEMA", at, "Unknown operator or atom '" + name + "'");
            }
            if (actor != (scope == Scope.ACTOR)) {
                throw error("SCHEMA", at, "'" + name + "' is not readable in " + scope.name().toLowerCase() + " expressions");
            }
            switch (name) {
                case "attribute" -> {
                    int[] range = context.attributeRange(identifier(argument, at));
                    if (range == null) {
                        throw error("REFERENCE_UNKNOWN", at, "Unknown attribute '" + argument + "'");
                    }
                    return new Interval(Type.NUMBER, range[0], range[1]);
                }
                case "resource" -> {
                    Integer max = context.resourceMax(identifier(argument, at));
                    if (max == null) {
                        throw error("REFERENCE_UNKNOWN", at, "Unknown resource '" + argument + "'");
                    }
                    return new Interval(Type.NUMBER, 0, max);
                }
                case "skill" -> {
                    String skill = reference(argument, at, Set.of("skill"));
                    return new Interval(Type.NUMBER, 0, context.skillMax(skill));
                }
                case "dice" -> {
                    var f = new Fields(document, at, argument, context);
                    int count = f.integer("count", 1, 20, null);
                    int sides = f.integer("sides", 2, 100, null);
                    f.close();
                    dice += count;
                    if (dice > MAX_DICE) {
                        throw error("COST", at, "Expression rolls more than " + MAX_DICE + " dice");
                    }
                    return new Interval(Type.NUMBER, count, (long) count * sides);
                }
                case "visited" -> {
                    reference(argument, at, Set.of("location"));
                    return Interval.bool();
                }
                case "worldTime" -> {
                    // Whole elapsed units of the bounded world clock; the proven interval is the clock's own.
                    if (!(argument instanceof String unit) || !CLOCK_UNITS.contains(unit)) {
                        throw error("SCHEMA", at, "Expected one of the clock units " + CLOCK_UNITS);
                    }
                    return new Interval(Type.NUMBER, 0, WorldClock.maxUnits(unit));
                }
                case "possesses" -> {
                    var f = new Fields(document, at, argument, context);
                    f.ref("item", Set.of("item"), true);
                    f.integer("quantity", 1, 9999, 1);
                    f.close();
                    return Interval.bool();
                }
                case "questStatus" -> {
                    var f = new Fields(document, at, argument, context);
                    String quest = f.ref("quest", Set.of("quest"), true);
                    String status = f.choice("status", QUEST_STATUSES, null);
                    f.close();
                    // Requiring "still undiscovered" never waits on the other quest, so it is no edge.
                    if (required && !status.equals("undiscovered")) {
                        requirements.add(new QuestRequirement(quest, at));
                    }
                    return Interval.bool();
                }
                default -> {
                    var f = new Fields(document, at, argument, context);
                    String quest = f.ref("quest", Set.of("quest"), true);
                    String objective = f.id("id", true);
                    f.close();
                    Set<String> known = context.objectives(quest);
                    if (known == null) {
                        throw error("SCHEMA", PackDocument.child(at, "quest"),
                                "Objective atoms may only name quests defined in this pack");
                    }
                    if (!known.contains(objective)) {
                        throw error("REFERENCE_UNKNOWN", PackDocument.child(at, "id"),
                                "Quest '" + quest + "' has no objective '" + objective + "'");
                    }
                    if (required) {
                        requirements.add(new QuestRequirement(quest, at));
                    }
                    return Interval.bool();
                }
            }
        }

        /** Read an identifier-valued atom argument. */
        private String identifier(Object argument, String at) {
            if (!(argument instanceof String text) || !Fields.ID.matcher(text).matches()) {
                throw error("SCHEMA", at, "Expected a lowercase stable identifier");
            }
            return text;
        }

        /** Read and resolve a reference-valued atom argument. */
        private String reference(Object argument, String at, Set<String> kinds) {
            if (!(argument instanceof String text) || text.isBlank() || text.length() > 300) {
                throw error("SCHEMA", at, "Expected a reference string");
            }
            context.resolve(document, at, text, kinds);
            return text;
        }

        /** Read an integer literal within the constant bound. */
        private long constant(Object value, String pointer) {
            BigInteger whole = null;
            if (value instanceof BigInteger integer) {
                whole = integer;
            } else if (value instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0
                    && decimal.precision() - decimal.scale() < 20) {
                whole = decimal.toBigIntegerExact();
            }
            if (whole == null || whole.abs().compareTo(BigInteger.valueOf(CONSTANT_MAX)) > 0) {
                throw error("SCHEMA", pointer, "Expected an integer constant within +/-" + CONSTANT_MAX);
            }
            return whole.longValueExact();
        }

        /** Require an operand array of the given size. */
        private List<?> operands(Object argument, String at, int min, int max) {
            if (!(argument instanceof List<?> list) || list.size() < min || list.size() > max) {
                throw error("SCHEMA", at, "Expected an operand array with " + min + ".." + max + " entries");
            }
            return list;
        }

        private static String element(String pointer, int index) {
            return PackDocument.child(pointer, Integer.toString(index));
        }

        private PackException error(String code, String pointer, String message) {
            return new PackException(code, document.location(pointer), message);
        }
    }
}
