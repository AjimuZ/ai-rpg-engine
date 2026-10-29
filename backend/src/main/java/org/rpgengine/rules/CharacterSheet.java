package org.rpgengine.rules;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Effective view of one character computed from stored base values, never stored itself:
 * attribute values after active condition modifiers, skill ranks and derived statistics
 * (docs/05 section 4, docs/10 section 1).
 *
 * <p>Because the sheet is a pure function of committed rows and pinned rules, a derived value
 * can never go stale: every caller recomputes it from the same inputs and gets the same
 * numbers, and the state revision it was read at identifies the inputs. Conditions only move
 * attributes inside each attribute's declared range, so a derived formula's proven interval
 * (checked when the pack was compiled) holds at runtime as well.
 *
 * @param attributes effective attribute value by key, sorted by key
 * @param skills skill rank by canonical ID, sorted by ID; untrained skills are absent
 * @param derived derived statistic by key, sorted by key; a statistic whose attributes the actor does
 *        not hold is absent
 * @param conditions the active conditions that contributed, in the order given
 */
public record CharacterSheet(Map<String, Long> attributes, Map<String, Long> skills, Map<String, Long> derived,
        List<ConditionRules.Active> conditions) {
    /** Freeze and sort every part. */
    public CharacterSheet {
        attributes = Map.copyOf(new TreeMap<>(attributes));
        skills = Map.copyOf(new TreeMap<>(skills));
        derived = Map.copyOf(new TreeMap<>(derived));
        conditions = List.copyOf(conditions);
    }

    /**
     * Inclusive range of an attribute in the actor's vocabulary.
     *
     * @param min lowest value
     * @param max highest value
     */
    public record Range(int min, int max) {
    }

    /**
     * Compiled derived statistic.
     *
     * @param id unqualified key
     * @param formula numeric formula over attributes and skills
     * @param min lowest value the compiler proved the formula can reach or the author allows
     * @param max highest value
     */
    public record Derived(String id, StatFormula formula, long min, long max) {
    }

    /**
     * Compute the effective sheet.
     *
     * @param ranges attribute ranges of the actor's vocabulary
     * @param derivedStats derived statistics of that vocabulary
     * @param base stored base attribute values; every key must have a range
     * @param skills stored skill ranks by canonical ID
     * @param active the actor's active conditions
     * @param definitions condition definitions by canonical ID; an active condition without a
     *        definition is a content mismatch
     * @return the effective sheet
     * @throws IllegalStateException if a stored value or condition does not match the pinned rules,
     *         or a derived value leaves its declared bounds; the engine reports corrupt content
     *         rather than showing an invented number
     */
    public static CharacterSheet compute(Map<String, Range> ranges, List<Derived> derivedStats,
            Map<String, Long> base, Map<String, Long> skills, List<ConditionRules.Active> active,
            Map<String, ConditionRules.Definition> definitions) {
        Objects.requireNonNull(ranges, "ranges");
        var effective = new TreeMap<String, Long>();
        for (var entry : base.entrySet()) {
            Range range = ranges.get(entry.getKey());
            if (range == null) {
                throw new IllegalStateException("stored attribute '" + entry.getKey() + "' is not in the pinned rules");
            }
            if (entry.getValue() < range.min() || entry.getValue() > range.max()) {
                throw new IllegalStateException("stored attribute '" + entry.getKey() + "' is outside its range");
            }
            long value = entry.getValue();
            for (var condition : active) {
                var definition = definitions.get(condition.id());
                if (definition == null) {
                    throw new IllegalStateException("active condition '" + condition.id() + "' is not in the pinned rules");
                }
                Integer delta = definition.modifiers().get(entry.getKey());
                if (delta != null) {
                    value += (long) delta * condition.stacks();
                }
            }
            effective.put(entry.getKey(), Math.max(range.min(), Math.min(range.max(), value)));
        }
        StatFormula.Inputs inputs = new StatFormula.Inputs() {
            @Override
            public long attribute(String id) {
                Long value = effective.get(id);
                if (value == null) {
                    throw new IllegalStateException("derived formula reads attribute '" + id + "' the actor does not hold");
                }
                return value;
            }

            @Override
            public long skill(String id) {
                return skills.getOrDefault(id, 0L);
            }
        };
        var derived = new TreeMap<String, Long>();
        for (Derived stat : derivedStats) {
            // An actor whose creation profile omits an attribute simply has no value for statistics
            // that need it; the value is absent rather than a silently invented zero.
            if (!effective.keySet().containsAll(StatFormula.attributesRead(stat.formula()))) {
                continue;
            }
            long value = stat.formula().evaluate(inputs);
            if (value < stat.min() || value > stat.max()) {
                throw new IllegalStateException("derived statistic '" + stat.id() + "' left its declared bounds");
            }
            derived.put(stat.id(), value);
        }
        return new CharacterSheet(effective, skills, derived, active);
    }
}
