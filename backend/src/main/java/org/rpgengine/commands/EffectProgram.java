package org.rpgengine.commands;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.rpgengine.content.CampaignPackSnapshot;
import org.rpgengine.content.CompiledPack;

/**
 * Immutable, prose-free projection of one pinned ability's effect tree and its bounded
 * expressions (P07.4). The pack compiler has already type-checked every node, proved the
 * numeric bounds, rejected division by zero and limited depth, node and dice counts
 * (docs/09 section 7); this class only copies that validated data into closed Java values
 * that {@link LocalActionResolver} can evaluate without reparsing pack bytes.
 *
 * <p>Only effect types the exploration resolver can execute are represented precisely
 * ({@code modifyResource} and {@code conditional}). Every other compiled type
 * ({@code damage}, {@code heal}, {@code move}, condition changes, {@code reveal},
 * {@code createItem}) becomes {@link Unsupported}: those need armor, conditions, forced
 * movement, knowledge records or item instances that later tasks supply (P09.1, P10, P11),
 * so {@link CommandRules} rejects such abilities as {@code EFFECT_UNSUPPORTED} before any
 * journal write instead of guessing a meaning.
 */
final class EffectProgram {
    /** Static compilation helpers only. */
    private EffectProgram() { }

    /**
     * Compile one validated effect list.
     *
     * @param value parsed {@code effects} (or {@code then}/{@code else}) list; absent means empty
     * @param snapshot compiled closure used to canonicalize skill references
     * @param pack pack declaring the ability, which owns its unqualified references
     * @param inherited timing of the enclosing conditional, or null at the top level
     * @return immutable effects in authored order
     */
    static List<Effect> compile(Object value, CampaignPackSnapshot snapshot, CompiledPack pack, String inherited) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var effects = new ArrayList<Effect>();
        for (Object element : list) {
            var node = (Map<?, ?>) element;
            String type = (String) node.get("type");
            // The compiler defaults top-level timing to "post"; nested nodes inherit it.
            String timing = node.get("timing") instanceof String explicit ? explicit
                    : inherited == null ? "post" : inherited;
            effects.add(switch (type) {
                case "modifyResource" -> new ModifyResource(timing, target(node), (String) node.get("resource"),
                        Formula.compile(node.get("amount"), snapshot, pack));
                case "conditional" -> new Conditional(timing, Formula.compile(node.get("when"), snapshot, pack),
                        compile(node.get("then"), snapshot, pack, timing),
                        compile(node.get("else"), snapshot, pack, timing));
                default -> new Unsupported(timing, type);
            });
        }
        return List.copyOf(effects);
    }

    /** Compiler default for an omitted effect target is the intention's explicit target. */
    private static String target(Map<?, ?> node) {
        return node.get("target") instanceof String target ? target : "target";
    }

    /**
     * Whether every node, in every branch, can be executed by the exploration resolver.
     *
     * @param effects compiled list
     * @return false when any node is {@link Unsupported}
     */
    static boolean supported(List<Effect> effects) {
        for (Effect effect : effects) {
            switch (effect) {
                case Unsupported ignored -> { return false; }
                case Conditional branch -> {
                    if (!supported(branch.then()) || !supported(branch.otherwise())) {
                        return false;
                    }
                }
                case ModifyResource ignored -> { }
            }
        }
        return true;
    }

    /**
     * Worst-case number of single-die draws the list can reserve: every die in every
     * expression of every branch. Each die is one durable draw ordinal, so this static bound
     * proves that resolution can never exceed {@code DeterministicDraw.MAX_DRAWS}.
     */
    static int maxDraws(List<Effect> effects) {
        int total = 0;
        for (Effect effect : effects) {
            total += switch (effect) {
                case ModifyResource change -> change.amount().dice();
                case Conditional branch -> branch.when().dice() + maxDraws(branch.then())
                        + maxDraws(branch.otherwise());
                case Unsupported ignored -> 0;
            };
        }
        return total;
    }

    /**
     * Whether any expression, in any branch, reads an {@code attribute} or {@code resource}
     * atom. Those keys are unqualified and belong to the declaring pack's vocabulary, so the
     * value is only meaningful when the acting player was created from that vocabulary.
     * Resource <em>changes</em> are not counted: the resolver applies them per affected actor
     * and records a vocabulary mismatch as an explicit unaffected outcome.
     */
    static boolean readsActorValues(List<Effect> effects) {
        for (Effect effect : effects) {
            boolean reads = switch (effect) {
                case ModifyResource change -> change.amount().readsActorValues();
                case Conditional branch -> branch.when().readsActorValues() || readsActorValues(branch.then())
                        || readsActorValues(branch.otherwise());
                case Unsupported ignored -> false;
            };
            if (reads) {
                return true;
            }
        }
        return false;
    }

    /** One compiled effect node; timing is {@code pre} or {@code post}. */
    sealed interface Effect permits ModifyResource, Conditional, Unsupported {
        /** @return resolution phase of the node, inherited inside conditionals */
        String timing();
    }

    /**
     * Change one resource of the affected actors by an evaluated amount, clamped to its bounds.
     *
     * @param timing resolution phase
     * @param target {@code self}, {@code target} or {@code targets}
     * @param resource unqualified resource key of the declaring pack's vocabulary
     * @param amount actor-scope numeric expression, evaluated once per node
     */
    record ModifyResource(String timing, String target, String resource, Formula amount) implements Effect {
        /** Require every part; compiled content always supplies them. */
        ModifyResource {
            Objects.requireNonNull(timing, "timing");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * Branch on an actor-scope boolean expression; nested nodes share this node's timing.
     *
     * @param timing resolution phase
     * @param when boolean guard
     * @param then effects when the guard holds (at least one)
     * @param otherwise effects when it does not (possibly empty)
     */
    record Conditional(String timing, Formula when, List<Effect> then, List<Effect> otherwise) implements Effect {
        /** Freeze both branches. */
        Conditional {
            Objects.requireNonNull(timing, "timing");
            Objects.requireNonNull(when, "when");
            then = List.copyOf(then);
            otherwise = List.copyOf(otherwise);
        }
    }

    /**
     * A compiled effect type the exploration resolver deliberately does not execute.
     *
     * @param timing resolution phase
     * @param type pack effect type name, retained only for diagnostics in tests
     */
    record Unsupported(String timing, String type) implements Effect { }

    /**
     * Closed expression tree of the pack expression language in actor scope. World atoms
     * ({@code visited}, {@code possesses}, quest atoms) are only legal in world scope and so
     * never appear in an ability; meeting one here is a compiler/engine mismatch.
     */
    sealed interface Formula permits Constant, Flag, Operation, Attribute, Resource, Skill, Dice {
        /** Operators accepted by the compiler: variadic, binary, clamp, comparison and logic. */
        Set<String> OPERATORS = Set.of("add", "mul", "min", "max", "sub", "div", "clamp", "eq", "ne", "lt", "le",
                "gt", "ge", "all", "any", "not");

        /** @return number of single-die draws this expression can reserve in the worst case */
        int dice();

        /** @return whether the expression reads a vocabulary-dependent attribute or resource */
        boolean readsActorValues();

        /**
         * Copy one compiled expression.
         *
         * @throws IllegalArgumentException for a shape the compiler would have rejected
         */
        static Formula compile(Object value, CampaignPackSnapshot snapshot, CompiledPack pack) {
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
                var compiled = new ArrayList<Formula>();
                for (Object operand : operands) {
                    compiled.add(compile(operand, snapshot, pack));
                }
                return new Operation(name, compiled);
            }
            return switch (name) {
                case "attribute" -> new Attribute((String) argument);
                case "resource" -> new Resource((String) argument);
                case "skill" -> new Skill(snapshot.resolve(pack, (String) argument, "skill").id());
                case "dice" -> {
                    var dice = (Map<?, ?>) argument;
                    yield new Dice(((Number) dice.get("count")).intValue(), ((Number) dice.get("sides")).intValue());
                }
                default -> throw new IllegalArgumentException("expression atom is not readable in actor scope");
            };
        }
    }

    /** Integer literal. */
    record Constant(long value) implements Formula {
        public int dice() { return 0; }
        public boolean readsActorValues() { return false; }
    }

    /** Boolean literal. */
    record Flag(boolean value) implements Formula {
        public int dice() { return 0; }
        public boolean readsActorValues() { return false; }
    }

    /**
     * Operator application in authored operand order.
     *
     * @param operator one of {@link Formula#OPERATORS}
     * @param operands compiled operands; {@code not} has exactly one
     */
    record Operation(String operator, List<Formula> operands) implements Formula {
        /** Freeze operands. */
        Operation { operands = List.copyOf(operands); }
        public int dice() { return operands.stream().mapToInt(Formula::dice).sum(); }
        public boolean readsActorValues() { return operands.stream().anyMatch(Formula::readsActorValues); }
    }

    /** Acting player's attribute value by unqualified key. */
    record Attribute(String id) implements Formula {
        public int dice() { return 0; }
        public boolean readsActorValues() { return true; }
    }

    /** Acting player's current resource value by unqualified key. */
    record Resource(String id) implements Formula {
        public int dice() { return 0; }
        public boolean readsActorValues() { return true; }
    }

    /** Acting player's rank of a canonical skill ID; a missing rank is zero, as in prerequisites. */
    record Skill(String id) implements Formula {
        public int dice() { return 0; }
        public boolean readsActorValues() { return false; }
    }

    /**
     * Sum of {@code count} independent dice with {@code sides} faces (count 1..20, sides 2..100).
     * Each die consumes one durable draw ordinal.
     */
    record Dice(int count, int sides) implements Formula {
        public int dice() { return count; }
        public boolean readsActorValues() { return false; }
    }
}
