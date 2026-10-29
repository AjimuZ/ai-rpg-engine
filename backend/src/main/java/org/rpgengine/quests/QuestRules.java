package org.rpgengine.quests;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.rpgengine.content.CampaignPackSnapshot;
import org.rpgengine.content.CompiledPack;
import org.rpgengine.content.Definition;

/**
 * Prose-free, immutable projection of every quest in a pinned pack closure (P10.3): initial status,
 * discovery predicate, objectives, outcomes and the item rewards each outcome settles. It is derived
 * only from a sealed, recompiled {@link CampaignPackSnapshot}; names, summaries, descriptions and
 * consequence texts are not retained, so a rule catalog that holds it stays small and cannot leak
 * authored text.
 *
 * <p>Identities are canonical: root quests keep their local ID and dependency quests use
 * {@code pack#id}, exactly like every other stored reference. Quests are held in ascending identity
 * order, which fixes the evaluation order of {@link QuestPlan} and so the order of its events.
 *
 * @param quests quests by canonical ID, ascending
 */
public record QuestRules(Map<String, Quest> quests) {
    /** The pack compiler's bound on units rewarded by one outcome, shared with the per-commit reward bound. */
    public static final int MAX_REWARD_UNITS = org.rpgengine.content.PackCompiler.MAX_QUEST_REWARD_UNITS;
    /** No quests, the shape of a catalog without quest content. */
    public static final QuestRules NONE = new QuestRules(Map.of());

    /** Freeze the index in ascending identity order. */
    public QuestRules {
        quests = java.util.Collections.unmodifiableMap(new TreeMap<>(quests));
    }

    /**
     * One quest.
     *
     * @param id canonical quest ID
     * @param secret whether the quest is hidden background content: it starts tracking by itself
     *        once available, is not addressable by player commands, and stays out of public events
     *        until an outcome ends it
     * @param initial status of a quest that has no stored row ({@code undiscovered} or {@code available})
     * @param availableWhen discovery predicate of an undiscovered quest; null means it is always satisfied
     * @param objectives objectives in authored order (1..32)
     * @param outcomes outcomes in authored order (1..8); the first whose predicate holds ends the quest
     */
    public record Quest(String id, boolean secret, QuestStatus initial, QuestPredicate availableWhen,
            List<Objective> objectives, List<Outcome> outcomes) {
        /** Freeze the lists and require the parts every compiled quest has. */
        public Quest {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(initial, "initial");
            if (initial != QuestStatus.UNDISCOVERED && initial != QuestStatus.AVAILABLE) {
                throw new IllegalArgumentException("a quest starts undiscovered or available");
            }
            objectives = List.copyOf(objectives);
            outcomes = List.copyOf(outcomes);
        }
    }

    /**
     * One objective of a quest.
     *
     * @param id objective ID, unique within the quest
     * @param optional whether the objective is informational for panels; it never gates anything by itself
     * @param when the predicate that satisfies it
     */
    public record Objective(String id, boolean optional, QuestPredicate when) {
        /** Require every part. */
        public Objective {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(when, "when");
        }
    }

    /** How an outcome ends a quest. */
    public enum Kind {
        /** Ends the quest as completed. */
        SUCCESS("success", QuestStatus.COMPLETED),
        /** Ends the quest as failed. */
        FAILURE("failure", QuestStatus.FAILED),
        /** Ends the quest as completed through a different resolution than the primary one. */
        ALTERNATIVE("alternative", QuestStatus.COMPLETED);

        private final String wire;
        private final QuestStatus ends;

        /** @param wire pack spelling @param ends the terminal status the outcome produces */
        Kind(String wire, QuestStatus ends) {
            this.wire = wire;
            this.ends = ends;
        }

        /** @return the pack spelling */
        public String wire() {
            return wire;
        }

        /** @return the terminal status this kind produces */
        public QuestStatus ends() {
            return ends;
        }

        /** Parse the compiler-validated pack spelling. */
        static Kind parse(String wire) {
            for (Kind kind : values()) {
                if (kind.wire.equals(wire)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown outcome kind");
        }
    }

    /**
     * One outcome of a quest.
     *
     * @param id outcome ID, unique within the quest
     * @param kind how it ends the quest
     * @param when the predicate that selects it
     * @param rewards item units settled once for the player when this outcome ends the quest; one entry per
     *        definition, at most {@link #MAX_REWARD_UNITS} units in total
     */
    public record Outcome(String id, Kind kind, QuestPredicate when, List<Reward> rewards) {
        /** Freeze the rewards and require every part. */
        public Outcome {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(when, "when");
            rewards = List.copyOf(rewards);
            int units = rewards.stream().mapToInt(Reward::quantity).sum();
            if (units > MAX_REWARD_UNITS) {
                throw new IllegalArgumentException("an outcome rewards at most " + MAX_REWARD_UNITS + " units");
            }
        }

        /** @return total rewarded units */
        public int units() {
            return rewards.stream().mapToInt(Reward::quantity).sum();
        }
    }

    /**
     * Units of one item definition.
     *
     * @param item canonical item definition ID
     * @param quantity units, at least one
     */
    public record Reward(String item, int quantity) {
        /** Require a definition and a positive count. */
        public Reward {
            Objects.requireNonNull(item, "item");
            if (quantity < 1) {
                throw new IllegalArgumentException("a reward grants at least one unit");
            }
        }
    }

    /**
     * Project every quest of a compiled closure. Rewards naming the same definition are merged, so
     * the one-creation-per-source ledger rule can never refuse a legitimate outcome.
     *
     * @param snapshot sealed compiled closure; it is not retained
     * @return the detached rules
     * @throws IllegalArgumentException if the content breaks a bound the compiler guarantees
     */
    public static QuestRules from(CampaignPackSnapshot snapshot) {
        var quests = new LinkedHashMap<String, Quest>();
        for (CompiledPack pack : snapshot.packs().values()) {
            String packId = pack.parsed().manifest().id();
            for (Definition definition : pack.definitions()) {
                if (!definition.kind().equals("quest")) {
                    continue;
                }
                String id = pack == snapshot.root() ? definition.id() : packId + "#" + definition.id();
                quests.put(id, quest(snapshot, pack, id, definition.data()));
            }
        }
        return new QuestRules(quests);
    }

    /** Project one compiler-validated quest definition. */
    private static Quest quest(CampaignPackSnapshot snapshot, CompiledPack pack, String id, Map<String, Object> data) {
        boolean secret = "secret".equals(data.get("visibility"));
        QuestStatus initial = "available".equals(data.get("initialStatus")) ? QuestStatus.AVAILABLE
                : QuestStatus.UNDISCOVERED;
        QuestPredicate available = data.containsKey("availableWhen")
                ? QuestPredicate.compile(data.get("availableWhen"), snapshot, pack) : null;
        var objectives = new ArrayList<Objective>();
        for (Map<?, ?> objective : objects(data.get("objectives"))) {
            objectives.add(new Objective((String) objective.get("id"), Boolean.TRUE.equals(objective.get("optional")),
                    QuestPredicate.compile(objective.get("when"), snapshot, pack)));
        }
        var outcomes = new ArrayList<Outcome>();
        for (Map<?, ?> outcome : objects(data.get("outcomes"))) {
            var merged = new LinkedHashMap<String, Integer>();
            if (outcome.get("settlement") instanceof Map<?, ?> settlement) {
                for (Map<?, ?> reward : objects(settlement.get("rewards"))) {
                    String item = snapshot.resolve(pack, (String) reward.get("item"), "item").id();
                    int quantity = reward.get("quantity") instanceof Number number ? number.intValue() : 1;
                    merged.merge(item, quantity, Integer::sum);
                }
            }
            var rewards = new ArrayList<Reward>();
            merged.forEach((item, quantity) -> rewards.add(new Reward(item, quantity)));
            outcomes.add(new Outcome((String) outcome.get("id"), Kind.parse((String) outcome.get("kind")),
                    QuestPredicate.compile(outcome.get("when"), snapshot, pack), rewards));
        }
        return new Quest(id, secret, initial, available, objectives, outcomes);
    }

    /** Object elements of an optional validated list; absent lists are empty. */
    private static List<Map<?, ?>> objects(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().<Map<?, ?>>map(element -> (Map<?, ?>) element).toList();
    }
}
