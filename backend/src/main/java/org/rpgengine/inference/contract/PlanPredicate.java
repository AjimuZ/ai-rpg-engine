package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Typed condition of a quest plan (docs/28 section 6): the closed, bounded expression vocabulary
 * that a plan's root assumptions, node eligibility, branch guards, objectives, failure rules,
 * secret disclosure prerequisites and pending-event triggers are written in. A predicate is data
 * the backend interprets; it is never code, SQL, a path or free text, and it can never express a
 * player action, so a plan cannot order the protagonist to do anything.
 *
 * <p>Wire shape: every node is an object with exactly the fields {@code op}, {@code operands},
 * {@code ref}, {@code value}, {@code amount}, {@code fromSeconds} and {@code toSeconds}; fields an
 * operator does not use are {@code null} (or an empty {@code operands} array). Operators:
 * <ul>
 *   <li>{@code all} and {@code any}: one to {@value #MAX_OPERANDS} operands; {@code not}: one.</li>
 *   <li>{@code actor-at}: {@code ref} an {@code actor:} entity, {@code value} a location reference;
 *       true when the actor is currently at that location.</li>
 *   <li>{@code resource-at-least}: {@code ref} a {@code resource:} entity, {@code amount}
 *       0..1,000,000; true when the pool's current value is at least the amount.</li>
 *   <li>{@code item-available}: {@code ref} an {@code item:} entity; true while the item exists.</li>
 *   <li>{@code fact-known-to}: {@code ref} a {@code known-fact:} entity, {@code value}
 *       {@code player}; true while the player holds that knowledge record.</li>
 *   <li>{@code quest-status}: {@code ref} a {@code known-quest:} entity, {@code value} one of
 *       {@code ACTIVE}, {@code COMPLETED}, {@code FAILED}, {@code ABANDONED}.</li>
 *   <li>{@code world-time-window}: {@code fromSeconds} and {@code toSeconds} of world time,
 *       {@code from <= to}; no reference.</li>
 * </ul>
 * {@code actor-status}, {@code relationship-band} and {@code location-reachable} are recognized
 * names of the proposed vocabulary without an evaluator yet (actor life states arrive with P11,
 * relationships with P15, travel reachability with P10); they are rejected as
 * {@code unsupported-predicate} rather than trusted. Any other name is {@code unsupported-operator}.
 *
 * <p>Bounds: at most {@value #MAX_DEPTH} nested levels and {@value #MAX_LEAVES} leaves per
 * predicate, so evaluation is linear and recursion is bounded.
 */
public sealed interface PlanPredicate {
    /** Deepest accepted nesting; a lone leaf has depth 1. */
    int MAX_DEPTH = 4;
    /** Most leaves of one predicate. */
    int MAX_LEAVES = 16;
    /** Most operands of one {@code all}/{@code any}. */
    int MAX_OPERANDS = 8;
    /** Exact field set of every predicate object; the schema drift test compares it. */
    List<String> FIELDS = List.of("op", "operands", "ref", "value", "amount", "fromSeconds", "toSeconds");
    /** Implemented operator names, in schema order. */
    List<String> OPERATORS = List.of("all", "any", "not", "actor-at", "resource-at-least", "item-available",
            "fact-known-to", "quest-status", "world-time-window");
    /** Recognized proposal names that have no evaluator yet. */
    Set<String> RECOGNIZED_UNSUPPORTED = Set.of("actor-status", "relationship-band", "location-reachable");
    /** Quest statuses of the player's known-quest records (V10). */
    Set<String> QUEST_STATUSES = Set.of("ACTIVE", "COMPLETED", "FAILED", "ABANDONED");

    /** Three-valued result of evaluating against a snapshot: a future time window is UNKNOWN. */
    enum Truth {
        /** Holds in the snapshot. */
        TRUE,
        /** Cannot hold in the snapshot or later without a world change. */
        FALSE,
        /** May hold later (only a not-yet-open time window produces this). */
        UNKNOWN;

        /** @return Kleene negation */
        Truth not() {
            return this == TRUE ? FALSE : this == FALSE ? TRUE : UNKNOWN;
        }
    }

    /**
     * Facts the evaluator reads from the snapshot.
     */
    interface Facts {
        /** @return the entity of the context with this reference, or null */
        PlanEntity entity(String ref);

        /** @return world time of the snapshot in seconds */
        long worldTimeSeconds();
    }

    /**
     * @param facts snapshot facts
     * @return the three-valued truth of this predicate in the snapshot
     */
    Truth evaluate(Facts facts);

    /**
     * Collect every entity reference this predicate reads, in first-seen order.
     *
     * @param into destination set
     */
    void references(Set<String> into);

    /** @return the predicate as an ordered JSON-ready map in wire shape */
    Map<String, Object> toWire();

    /** Conjunction or disjunction. */
    record Junction(boolean all, List<PlanPredicate> operands) implements PlanPredicate {
        /** Freeze the operands. */
        public Junction {
            operands = List.copyOf(operands);
        }

        @Override
        public Truth evaluate(Facts facts) {
            boolean unknown = false;
            for (PlanPredicate operand : operands) {
                Truth truth = operand.evaluate(facts);
                if (truth == (all ? Truth.FALSE : Truth.TRUE)) {
                    return truth;
                }
                unknown |= truth == Truth.UNKNOWN;
            }
            return unknown ? Truth.UNKNOWN : all ? Truth.TRUE : Truth.FALSE;
        }

        @Override
        public void references(Set<String> into) {
            operands.forEach(operand -> operand.references(into));
        }

        @Override
        public Map<String, Object> toWire() {
            return wire(all ? "all" : "any", operands.stream().map(PlanPredicate::toWire).toList(), null, null, null,
                    null, null);
        }
    }

    /** Negation of one operand. */
    record Not(PlanPredicate operand) implements PlanPredicate {
        @Override
        public Truth evaluate(Facts facts) {
            return operand.evaluate(facts).not();
        }

        @Override
        public void references(Set<String> into) {
            operand.references(into);
        }

        @Override
        public Map<String, Object> toWire() {
            return wire("not", List.of(operand.toWire()), null, null, null, null, null);
        }
    }

    /**
     * One entity condition.
     *
     * @param op leaf operator name
     * @param ref entity reference read
     * @param value location reference, {@code player} or quest status; null for operators without one
     * @param amount resource threshold; null for operators without one
     */
    record Leaf(String op, String ref, String value, Long amount) implements PlanPredicate {
        @Override
        public Truth evaluate(Facts facts) {
            PlanEntity entity = facts.entity(ref);
            if (entity == null) {
                // Validation rejects unknown references first; an absent entity never holds.
                return Truth.FALSE;
            }
            boolean holds = switch (op) {
                case "actor-at" -> value.equals(entity.location());
                case "resource-at-least" -> entity.amount() != null && entity.amount() >= amount;
                case "quest-status" -> value.equals(entity.status());
                // Presence in the snapshot is the condition: the item exists, the knowledge is held.
                default -> true;
            };
            return holds ? Truth.TRUE : Truth.FALSE;
        }

        @Override
        public void references(Set<String> into) {
            into.add(ref);
        }

        @Override
        public Map<String, Object> toWire() {
            return wire(op, List.of(), ref, value, amount, null, null);
        }
    }

    /**
     * World-time window; not an entity read, so it adds no dependency. The actual command that
     * would use the plan rechecks time itself.
     *
     * @param fromSeconds first world second of the window
     * @param toSeconds last world second of the window
     */
    record TimeWindow(long fromSeconds, long toSeconds) implements PlanPredicate {
        @Override
        public Truth evaluate(Facts facts) {
            long now = facts.worldTimeSeconds();
            if (toSeconds < now) {
                return Truth.FALSE;
            }
            return fromSeconds <= now ? Truth.TRUE : Truth.UNKNOWN;
        }

        @Override
        public void references(Set<String> into) {
        }

        @Override
        public Map<String, Object> toWire() {
            return wire("world-time-window", List.of(), null, null, null, fromSeconds, toSeconds);
        }
    }

    /**
     * Read one predicate from untrusted output, recording every violation on the reader.
     *
     * @param reader strict reader collecting violations
     * @param value candidate JSON value
     * @param path JSON pointer of the value
     * @param entities context entities, used for reference and kind checks
     * @return the typed predicate, or null after recording at least one violation
     */
    static PlanPredicate read(StrictJson reader, Object value, String path, Map<String, PlanEntity> entities) {
        int[] leaves = {0};
        return read(reader, value, path, entities, 1, leaves);
    }

    /** Recursive reader; {@code leaves} counts across the whole predicate. */
    private static PlanPredicate read(StrictJson reader, Object value, String path, Map<String, PlanEntity> entities,
            int depth, int[] leaves) {
        if (depth > MAX_DEPTH) {
            reader.semantic("predicate-too-deep", path);
            return null;
        }
        Map<String, Object> node = reader.object(value, path, Set.copyOf(FIELDS));
        if (node == null) {
            return null;
        }
        Object op = node.get("op");
        if (!(op instanceof String name)) {
            reader.structural("unexpected-type", path + "/op");
            return null;
        }
        if (RECOGNIZED_UNSUPPORTED.contains(name)) {
            reader.semantic("unsupported-predicate", path + "/op");
            return null;
        }
        if (!OPERATORS.contains(name)) {
            reader.semantic("unsupported-operator", path + "/op");
            return null;
        }
        boolean junction = name.equals("all") || name.equals("any") || name.equals("not");
        List<Object> operands = reader.array(node, "operands", path, 0, MAX_OPERANDS);
        if (operands == null) {
            return null;
        }
        if (junction) {
            int min = 1;
            int max = name.equals("not") ? 1 : MAX_OPERANDS;
            if (operands.size() < min || operands.size() > max) {
                reader.structural("out-of-bounds", path + "/operands");
                return null;
            }
            requireNull(reader, node, path, "ref", "value", "amount", "fromSeconds", "toSeconds");
            var typed = new ArrayList<PlanPredicate>();
            for (int index = 0; index < operands.size(); index++) {
                PlanPredicate operand = read(reader, operands.get(index), path + "/operands/" + index, entities,
                        depth + 1, leaves);
                if (operand == null) {
                    return null;
                }
                typed.add(operand);
            }
            return name.equals("not") ? new Not(typed.getFirst()) : new Junction(name.equals("all"), typed);
        }
        if (!operands.isEmpty()) {
            reader.semantic("status-field-conflict", path + "/operands");
            return null;
        }
        if (++leaves[0] > MAX_LEAVES) {
            reader.semantic("predicate-too-large", path);
            return null;
        }
        if (name.equals("world-time-window")) {
            requireNull(reader, node, path, "ref", "value", "amount");
            Long from = reader.integer(node, "fromSeconds", path, 0, Long.MAX_VALUE, false);
            Long to = reader.integer(node, "toSeconds", path, 0, Long.MAX_VALUE, false);
            if (from == null || to == null) {
                return null;
            }
            if (from > to) {
                reader.semantic("invalid-window", path);
                return null;
            }
            return new TimeWindow(from, to);
        }
        requireNull(reader, node, path, "fromSeconds", "toSeconds");
        Object ref = node.get("ref");
        if (!(ref instanceof String reference) || !ContextValues.REFERENCE.matcher(reference).matches()) {
            reader.structural("unexpected-type", path + "/ref");
            return null;
        }
        PlanEntity entity = entities.get(reference);
        if (entity == null) {
            reader.semantic("unknown-reference", path + "/ref");
            return null;
        }
        String expectedKind = switch (name) {
            case "actor-at" -> "actor";
            case "resource-at-least" -> "resource";
            case "item-available" -> "item";
            case "fact-known-to" -> "known-fact";
            default -> "known-quest";
        };
        if (!entity.kind().equals(expectedKind)) {
            reader.semantic("predicate-reference-kind", path + "/ref");
            return null;
        }
        String text = null;
        Long amount = null;
        switch (name) {
            case "actor-at" -> {
                requireNull(reader, node, path, "amount");
                text = reader.text(node, "value", path, 128, false);
                if (text != null && !ContextValues.REFERENCE.matcher(text).matches()) {
                    reader.structural("unexpected-type", path + "/value");
                    text = null;
                }
                if (text == null) {
                    return null;
                }
            }
            case "resource-at-least" -> {
                requireNull(reader, node, path, "value");
                amount = reader.integer(node, "amount", path, 0, 1_000_000, false);
                if (amount == null) {
                    return null;
                }
            }
            case "item-available" -> requireNull(reader, node, path, "value", "amount");
            case "fact-known-to" -> {
                requireNull(reader, node, path, "amount");
                text = reader.choice(node, "value", path, Set.of("player"), false);
                if (text == null) {
                    return null;
                }
            }
            default -> {
                requireNull(reader, node, path, "amount");
                text = reader.choice(node, "value", path, QUEST_STATUSES, false);
                if (text == null) {
                    return null;
                }
            }
        }
        return new Leaf(name, reference, text, amount);
    }

    /** Record {@code status-field-conflict} for every listed field that is not null. */
    private static void requireNull(StrictJson reader, Map<String, Object> node, String path, String... fields) {
        for (String field : fields) {
            if (node.get(field) != null) {
                reader.semantic("status-field-conflict", path + "/" + field);
            }
        }
    }

    /** @return an ordered wire map */
    private static Map<String, Object> wire(String op, List<Map<String, Object>> operands, String ref, String value,
            Long amount, Long from, Long to) {
        var map = new LinkedHashMap<String, Object>();
        map.put("op", op);
        map.put("operands", operands);
        map.put("ref", ref);
        map.put("value", value);
        map.put("amount", amount);
        map.put("fromSeconds", from);
        map.put("toSeconds", to);
        return map;
    }

    /**
     * @param predicates predicates, nulls ignored
     * @return every entity reference they read, in first-seen order
     */
    static Set<String> referencesOf(List<PlanPredicate> predicates) {
        var refs = new LinkedHashSet<String>();
        for (PlanPredicate predicate : predicates) {
            if (predicate != null) {
                predicate.references(refs);
            }
        }
        return refs;
    }
}
