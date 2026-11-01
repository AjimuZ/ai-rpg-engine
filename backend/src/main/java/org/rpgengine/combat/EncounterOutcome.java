package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The terminal predicates of an encounter (P11.5, docs/11 section 13, QA-13): whether its committed mechanical state
 * ends the fight, and how. {@link #decide} reads nothing but an {@link EncounterState}, so no narration, speech or other
 * prose can establish or prevent an outcome, and the same state always gives the same verdict, also when it is read back
 * from storage at {@link EncounterPhase#OUTCOME_PENDING}.
 *
 * <p>Priority, the first that holds wins:
 * <ol>
 *   <li>{@link Kind#DEFEAT}: the player is defeated. It comes first because a fallen protagonist meets no objective
 *       ({@link EncounterObjective#evaluate}), so a mutual fall is a defeat, not a victory.</li>
 *   <li>{@link Kind#OBJECTIVE}: a reach-zone, survive or protect objective is met, the first in declared order. Objectives
 *       are alternative ways out of a fight, so one is enough.</li>
 *   <li>{@link Kind#VICTORY}: no hostile participant is still active.</li>
 *   <li>{@link Kind#ESCAPE}: the player has escaped or stands in one of the encounter's escape zones.</li>
 * </ol>
 * The defeat-hostiles and escape objectives read the same facts as {@link Kind#VICTORY} and {@link Kind#ESCAPE}, and keep
 * those outcomes and their place in the order. A failed objective ends nothing by itself: the fight can still be won, lost or
 * left, and {@link #objectives} reports every status for settlement. A player who surrendered gets no verdict here, and
 * surrender, negotiated ends, stalemate and settlement are later P11.5 work; the round cap therefore stays a pause.
 *
 * @param kind how the fight ends
 * @param objective the objective met for {@link Kind#OBJECTIVE}, otherwise null
 * @param objectives every objective's status in declared order
 */
public record EncounterOutcome(Kind kind, String objective, Map<String, EncounterObjective.Status> objectives) {
    /** How a fight ends. */
    public enum Kind {
        VICTORY, DEFEAT, ESCAPE, OBJECTIVE
    }

    /** Require the kind and keep the objective order. */
    public EncounterOutcome {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.OBJECTIVE) != (objective != null)) {
            throw new IllegalArgumentException("only an objective outcome names an objective");
        }
        objectives = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(objectives));
    }

    /**
     * Apply the terminal predicates.
     *
     * @param state an encounter's committed state, or a resolution's working copy of it
     * @return the verdict, or null while the fight goes on
     */
    public static EncounterOutcome decide(EncounterState state) {
        var progress = progress(state);
        var statuses = new LinkedHashMap<String, EncounterObjective.Status>();
        String met = null;
        for (EncounterObjective objective : state.objectives()) {
            var status = objective.evaluate(progress);
            statuses.put(objective.id(), status);
            boolean explicit = objective.kind() != EncounterObjective.Kind.DEFEAT_HOSTILES
                    && objective.kind() != EncounterObjective.Kind.ESCAPE;
            if (met == null && explicit && status == EncounterObjective.Status.MET) {
                met = objective.id();
            }
        }
        var standing = state.player().status();
        if (standing == EncounterState.Status.DEFEATED) {
            return new EncounterOutcome(Kind.DEFEAT, null, statuses);
        }
        if (standing == EncounterState.Status.SURRENDERED) {
            return null;
        }
        if (met != null) {
            return new EncounterOutcome(Kind.OBJECTIVE, met, statuses);
        }
        if (!progress.hostilesActive()) {
            return new EncounterOutcome(Kind.VICTORY, null, statuses);
        }
        if (standing == EncounterState.Status.ESCAPED || progress.escapeZones().contains(progress.playerZone())) {
            return new EncounterOutcome(Kind.ESCAPE, null, statuses);
        }
        return null;
    }

    /**
     * The facts objectives read, taken from one state.
     *
     * @param state an encounter state
     * @return its objective progress
     */
    public static EncounterObjective.Progress progress(EncounterState state) {
        return new EncounterObjective.Progress() {
            @Override
            public boolean hostilesActive() {
                return state.participants().stream().anyMatch(participant -> participant.side() == EncounterTemplate.Side.HOSTILE
                        && participant.status() == EncounterState.Status.ACTIVE);
            }

            @Override
            public String playerZone() {
                return state.player().zone();
            }

            @Override
            public boolean playerDefeated() {
                return state.player().status() == EncounterState.Status.DEFEATED;
            }

            @Override
            public boolean defeated(String participant) {
                var found = state.participant(participant);
                return found != null && found.status() == EncounterState.Status.DEFEATED;
            }

            @Override
            public int completedRounds() {
                return EncounterOutcome.completedRounds(state);
            }

            @Override
            public Set<String> escapeZones() {
                return state.escapeZones();
            }
        };
    }

    /**
     * Rounds fully completed in a state. The current round is complete at its round end, and also at an outcome pending
     * without a cursor, because only the round end reaches that phase with no activation in progress.
     *
     * @param state an encounter state
     * @return the number of completed rounds, zero before the first round ends
     */
    static int completedRounds(EncounterState state) {
        boolean closed = state.phase() == EncounterPhase.ROUND_END
                || state.phase() == EncounterPhase.OUTCOME_PENDING && state.cursor() == null;
        return closed ? state.round() : Math.max(0, state.round() - 1);
    }

    /** @return the public form of this verdict: outcome, the objective met if any and each objective's status */
    public Map<String, Object> publicForm() {
        var form = new TreeMap<String, Object>();
        form.put("outcome", kind.name());
        if (objective != null) {
            form.put("objectiveId", objective);
        }
        var lines = new ArrayList<Object>();
        objectives.forEach((id, status) -> {
            var line = new TreeMap<String, Object>();
            line.put("objectiveId", id);
            line.put("status", status.wire());
            lines.add(line);
        });
        form.put("objectives", lines);
        return form;
    }
}
