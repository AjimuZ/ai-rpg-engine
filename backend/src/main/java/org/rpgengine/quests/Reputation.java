package org.rpgengine.quests;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The player's standing with each faction, derived from the quests that have ended (P15.2-A, ADR 0051).
 *
 * <p>Reputation has no storage of its own. A quest outcome may declare {@link QuestRules.ReputationChange}s;
 * the standing toward a faction is the sum of the changes of every ended quest's recorded outcome, clamped once
 * to {@link #MIN}..{@link #MAX}. Ended quests are final (a database trigger refuses any change to a terminal
 * row) and each records the event that ended it, so every point of reputation has a stored cause, a quest is
 * never counted twice, and the standing is a pure function of the pinned rules and the set of endings,
 * independent of the order in which they happened. A faction no ending names stands at zero.
 *
 * @param standings non-zero standings by canonical faction ID, ascending; each within {@link #MIN}..{@link #MAX}
 */
public record Reputation(Map<String, Integer> standings) {
    /** Lowest standing toward any faction. */
    public static final int MIN = -org.rpgengine.content.PackCompiler.MAX_REPUTATION;
    /** Highest standing toward any faction. */
    public static final int MAX = org.rpgengine.content.PackCompiler.MAX_REPUTATION;
    /** Largest change one outcome may make toward one faction, in either direction; the compiler's bound. */
    public static final int MAX_DELTA = org.rpgengine.content.PackCompiler.MAX_REPUTATION_DELTA;
    /** Most factions one outcome may change; the compiler's bound. */
    public static final int MAX_CHANGES = org.rpgengine.content.PackCompiler.MAX_REPUTATION_CHANGES;
    /** No faction has any standing. */
    public static final Reputation NONE = new Reputation(Map.of());

    /** Freeze the standings in ascending faction order and require bounded, non-zero values. */
    public Reputation {
        var sorted = new TreeMap<String, Integer>();
        standings.forEach((faction, value) -> {
            Objects.requireNonNull(faction, "faction");
            if (value == null || value == 0 || value < MIN || value > MAX) {
                throw new IllegalArgumentException("a stored standing is non-zero and within " + MIN + ".." + MAX);
            }
            sorted.put(faction, value);
        });
        standings = Collections.unmodifiableMap(sorted);
    }

    /**
     * @param faction canonical faction ID
     * @return the standing toward it, zero when no ending changed it
     */
    public int toward(String faction) {
        return standings.getOrDefault(faction, 0);
    }

    /**
     * Derive the standings from the quests that have ended.
     *
     * @param rules pinned quest rules
     * @param endings outcome ID by canonical quest ID, one entry per ended quest; an entry whose quest or outcome
     *        the rules do not know contributes nothing, exactly as the quest panel shows no outcome for it
     * @return the clamped standings
     */
    public static Reputation of(QuestRules rules, Map<String, String> endings) {
        Objects.requireNonNull(rules, "rules");
        var sums = new TreeMap<String, Long>();
        endings.forEach((quest, outcomeId) -> {
            QuestRules.Quest rule = rules.quests().get(quest);
            if (rule == null || outcomeId == null) {
                return;
            }
            rule.outcome(outcomeId).ifPresent(outcome -> outcome.reputation()
                    .forEach(change -> sums.merge(change.faction(), (long) change.delta(), Long::sum)));
        });
        var standings = new TreeMap<String, Integer>();
        sums.forEach((faction, sum) -> {
            int clamped = (int) Math.max(MIN, Math.min(MAX, sum));
            if (clamped != 0) {
                standings.put(faction, clamped);
            }
        });
        return new Reputation(standings);
    }
}
