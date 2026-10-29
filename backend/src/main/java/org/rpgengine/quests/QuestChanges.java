package org.rpgengine.quests;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.persistence.CanonicalValues;

/**
 * The durable effects of one {@link QuestPlan} evaluation, as a canonical commit applies them
 * (P10.3): the new status of each touched quest, the objectives that became satisfied and the
 * locations the player newly visited. Each write cites the plan event that announced it by index,
 * exactly like item creations, so a stored quest change can always be explained.
 *
 * <p>The item rewards of a quest ending are not here: they travel as an {@code ItemPlan} creation
 * with a {@code REWARD} source and a reward settlement in the same plan, so one ledger covers all
 * created units.
 *
 * @param states one write per touched quest
 * @param objectives satisfied objectives, each at most once
 * @param visits first visits to locations, each at most once per actor
 */
public record QuestChanges(List<StateWrite> states, List<ObjectiveWrite> objectives, List<VisitWrite> visits) {
    /** No quest effects. */
    public static final QuestChanges NONE = new QuestChanges(List.of(), List.of(), List.of());
    /** Upper bound of each list, far above what the evaluation budgets allow. */
    public static final int MAX_WRITES = 128;

    /** Copy, bound and reject duplicate targets. */
    public QuestChanges {
        states = bounded(states, "quest states");
        objectives = bounded(objectives, "quest objectives");
        visits = bounded(visits, "location visits");
        var quests = new HashSet<String>();
        for (StateWrite write : states) {
            if (!quests.add(write.questId())) {
                throw new IllegalArgumentException("a quest is written twice in one commit");
            }
        }
        var satisfied = new HashSet<String>();
        for (ObjectiveWrite write : objectives) {
            if (!satisfied.add(write.questId() + "\u0000" + write.objectiveId())) {
                throw new IllegalArgumentException("an objective is satisfied twice in one commit");
            }
        }
        var visited = new HashSet<String>();
        for (VisitWrite write : visits) {
            if (!visited.add(write.actorId() + "\u0000" + write.locationId())) {
                throw new IllegalArgumentException("a location is visited twice in one commit");
            }
        }
    }

    /**
     * New status of one quest.
     *
     * @param questId canonical quest ID
     * @param expectedRowVersion storage version read ({@link QuestPlan.State#ABSENT} inserts the row)
     * @param status the status after the commit; never undiscovered
     * @param outcomeId the ending outcome of a terminal status, otherwise null
     * @param eventIndex index of the plan event announcing the last transition
     */
    public record StateWrite(String questId, long expectedRowVersion, QuestStatus status, String outcomeId,
            int eventIndex) {
        /** Validate the row before any transaction starts. */
        public StateWrite {
            CanonicalValues.requireText(questId, "questId", 256);
            if (expectedRowVersion < QuestPlan.State.ABSENT) {
                throw new IllegalArgumentException("invalid row version");
            }
            Objects.requireNonNull(status, "status");
            if (status == QuestStatus.UNDISCOVERED) {
                throw new IllegalArgumentException("an undiscovered quest has no row");
            }
            if (status.terminal() != (outcomeId != null)) {
                throw new IllegalArgumentException("exactly a terminal quest records its outcome");
            }
            if (eventIndex < 0) {
                throw new IllegalArgumentException("eventIndex must not be negative");
            }
        }
    }

    /**
     * An objective that became satisfied; it stays satisfied.
     *
     * @param questId canonical quest ID
     * @param objectiveId objective ID
     * @param eventIndex index of the announcing plan event
     */
    public record ObjectiveWrite(String questId, String objectiveId, int eventIndex) {
        /** Validate the row. */
        public ObjectiveWrite {
            CanonicalValues.requireText(questId, "questId", 256);
            CanonicalValues.requireText(objectiveId, "objectiveId", 256);
            if (eventIndex < 0) {
                throw new IllegalArgumentException("eventIndex must not be negative");
            }
        }
    }

    /**
     * The first time an actor is recorded at a location. Visits are idempotent; the commit ignores a
     * row that already exists.
     *
     * @param actorId the visiting actor
     * @param locationId canonical location ID
     */
    public record VisitWrite(UUID actorId, String locationId) {
        /** Validate the row. */
        public VisitWrite {
            Objects.requireNonNull(actorId, "actorId");
            CanonicalValues.requireText(locationId, "locationId", 256);
        }
    }

    /** @return whether nothing is written */
    public boolean isEmpty() {
        return states.isEmpty() && objectives.isEmpty() && visits.isEmpty();
    }

    /**
     * Derive the writes from an evaluation.
     *
     * @param result the evaluation
     * @param eventIndexes the plan event index announcing each step, aligned with {@code result.steps()}
     * @param visits the visits to record
     * @return the changes
     * @throws IllegalArgumentException if the indexes do not align with the steps
     */
    public static QuestChanges of(QuestPlan.Result result, List<Integer> eventIndexes, List<VisitWrite> visits) {
        if (eventIndexes.size() != result.steps().size()) {
            throw new IllegalArgumentException("every quest step needs its announcing event");
        }
        var last = new LinkedHashMap<String, Integer>();
        var objectives = new ArrayList<ObjectiveWrite>();
        for (int index = 0; index < result.steps().size(); index++) {
            QuestPlan.Step step = result.steps().get(index);
            last.put(step.questId(), eventIndexes.get(index));
            if (step instanceof QuestPlan.ObjectiveSatisfied satisfied) {
                objectives.add(new ObjectiveWrite(step.questId(), satisfied.objectiveId(), eventIndexes.get(index)));
            }
        }
        var states = new ArrayList<StateWrite>();
        result.after().forEach((quest, state) -> {
            // A quest can only have changed through a step, so it has an announcing event.
            Integer event = last.get(quest);
            if (event == null) {
                throw new IllegalStateException("a changed quest has no announcing step");
            }
            states.add(new StateWrite(quest, state.rowVersion(), state.status(), state.outcomeId(), event));
        });
        return new QuestChanges(states, objectives, visits);
    }

    /** Defensive copy of at most {@link #MAX_WRITES} entries. */
    private static <T> List<T> bounded(List<T> values, String name) {
        if (values.size() > MAX_WRITES) {
            throw new IllegalArgumentException(name + " exceed " + MAX_WRITES);
        }
        return List.copyOf(values);
    }
}
