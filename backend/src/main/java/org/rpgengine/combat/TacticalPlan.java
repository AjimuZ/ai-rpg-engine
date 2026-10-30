package org.rpgengine.combat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Complete resolved effect of one tactical intention on the encounter (P11.2), produced by {@link CombatScheduler}
 * and written by {@link EncounterWriter#progressWithin} inside the canonical commit. It carries no executable work.
 *
 * <p>{@link #steps} lists every encounter row state the operation passes through, in order; each differs from the
 * previous one by at most one arrow of the docs/11 state machine, so the V22 lifecycle trigger checks every step,
 * and the writer applies them as consecutive row versions starting at {@link #baseRowVersion}. A speech-only
 * utterance is one step that keeps the phase. {@link #participants} are the final values of every participant
 * whose tactical facts changed, each guarded by the row version read.
 *
 * @param encounterId the active encounter
 * @param baseRowVersion encounter row version the plan was calculated from
 * @param steps encounter row states in order, at least one
 * @param participants final tactical facts of changed participants
 * @param notes events to append, in order
 * @param elapsedSeconds world time the operation adds: {@code roundSeconds} per completed round
 * @param summary owner-safe receipt summary (public facts only)
 */
public record TacticalPlan(UUID encounterId, long baseRowVersion, List<Step> steps, List<ParticipantUpdate> participants,
        List<Note> notes, long elapsedSeconds, Map<String, Object> summary) {
    /** Most encounter row states one operation may write (a full round of nine activations fits easily). */
    public static final int MAX_STEPS = 64;

    /**
     * One encounter row state.
     *
     * @param phase lifecycle phase
     * @param round round number
     * @param cursor acting initiative rank or null
     * @param actionPoints remaining action points or null
     * @param speechUsed speech-only utterances used in the activation
     * @param passages passage states after this step
     */
    public record Step(EncounterPhase phase, int round, Integer cursor, Integer actionPoints, int speechUsed,
            List<EncounterAction.Passage> passages) {
        /** Require the phase and the cursor invariant; copy the passages. */
        public Step {
            Objects.requireNonNull(phase, "phase");
            if ((cursor == null) != (actionPoints == null)) {
                throw new IllegalArgumentException("cursor and action points are set together");
            }
            passages = List.copyOf(passages);
        }
    }

    /**
     * Final tactical facts of one participant.
     *
     * @param participantId roster ID
     * @param expectedRowVersion participant row version read
     * @param zone zone after the operation
     * @param initiative initiative total, or null before initiative
     * @param rank initiative rank, or null before initiative
     * @param reactions reactions left this round
     */
    public record ParticipantUpdate(String participantId, long expectedRowVersion, String zone, Integer initiative,
            Integer rank, int reactions) {
        /** Require identities. */
        public ParticipantUpdate {
            Objects.requireNonNull(participantId, "participantId");
            Objects.requireNonNull(zone, "zone");
        }
    }

    /**
     * One event of the operation, before the commit gives it a sequence.
     *
     * @param type event type
     * @param payload canonical payload values
     * @param byPlayer whether the player's own decision caused it (attributed to the player's actor); scheduler
     *        bookkeeping such as round boundaries and other participants' activations is not
     */
    public record Note(String type, Map<String, Object> payload, boolean byPlayer) {
        /** Require type and payload. */
        public Note {
            Objects.requireNonNull(type, "type");
            payload = Map.copyOf(payload);
        }
    }

    /** Copy and bound the lists. */
    public TacticalPlan {
        Objects.requireNonNull(encounterId, "encounterId");
        steps = List.copyOf(steps);
        participants = List.copyOf(participants);
        notes = List.copyOf(notes);
        summary = Map.copyOf(summary);
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("a tactical plan writes 1.." + MAX_STEPS + " encounter states");
        }
        if (notes.isEmpty()) {
            throw new IllegalArgumentException("a tactical plan announces what it did");
        }
        if (elapsedSeconds < 0) {
            throw new IllegalArgumentException("elapsed time cannot be negative");
        }
    }

    /** @return the encounter row state the operation leaves */
    public Step last() {
        return steps.getLast();
    }
}
