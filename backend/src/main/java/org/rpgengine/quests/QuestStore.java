package org.rpgengine.quests;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read side of quest persistence (P10.3): the stored quest rows with their satisfied objectives, and
 * the locations an actor has been recorded at. Reads are plain queries joined to the caller's
 * transaction; the rule gate and the resolver call them while holding the campaign lock, so the
 * values cannot change before the commit that uses them.
 */
public final class QuestStore {
    /** Static reads only. */
    private QuestStore() { }

    /**
     * Everything the engine needs from storage for one actor.
     *
     * @param states stored state by canonical quest ID; quests with no row are absent
     * @param visited recorded locations of the actor
     */
    public record Stored(Map<String, QuestPlan.State> states, Set<String> visited) {
        /** No stored quests and no visits. */
        public static final Stored NONE = new Stored(Map.of(), Set.of());

        /** Freeze the parts. */
        public Stored {
            states = Map.copyOf(states);
            visited = Set.copyOf(visited);
        }
    }

    /**
     * Read the stored quests and the actor's visits.
     *
     * @param jdbc JDBC access joined to the caller's transaction
     * @param campaign owning campaign
     * @param actor the player whose visits count
     * @return detached snapshot
     */
    public static Stored read(JdbcTemplate jdbc, UUID campaign, UUID actor) {
        var objectives = new HashMap<String, Set<String>>();
        jdbc.query("select quest_id, objective_id from quest_objective where campaign_id = ?", row -> {
            objectives.computeIfAbsent(row.getString(1), key -> new HashSet<>()).add(row.getString(2));
        }, campaign);
        var states = new HashMap<String, QuestPlan.State>();
        jdbc.query("select quest_id, status, outcome_id, row_version from quest_state where campaign_id = ?", row -> {
            String quest = row.getString(1);
            QuestStatus status = QuestStatus.parse(row.getString(2))
                    .orElseThrow(() -> new IllegalStateException("stored quest status is unknown"));
            states.put(quest, new QuestPlan.State(status, row.getString(3), row.getLong(4),
                    objectives.getOrDefault(quest, Set.of())));
        }, campaign);
        var visited = new HashSet<String>();
        jdbc.query("select location_id from location_visit where campaign_id = ? and actor_id = ?",
                row -> {
                    visited.add(row.getString(1));
                }, campaign, actor);
        return new Stored(states, visited);
    }
}
