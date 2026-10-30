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
 * whose tactical facts changed, each guarded by the row version read. Since P11.3 a plan also carries what an attack
 * changes outside the encounter row: the player's Health, as a delta for the commit's resource effects, and the
 * ammunition consumed, as item effects. Since P11.4 the player's Focus moves the same way, and each participant's update
 * holds its active effects, reaction policies and ability uses.
 *
 * @param encounterId the active encounter
 * @param baseRowVersion encounter row version the plan was calculated from
 * @param steps encounter row states in order, at least one
 * @param participants final tactical facts of changed participants
 * @param notes events to append, in order
 * @param elapsedSeconds world time the operation adds: {@code roundSeconds} per completed round
 * @param summary owner-safe receipt summary (public facts only)
 * @param playerHealthDelta net change of the player's Health pool, which lives outside the encounter (P11.3)
 * @param ammunition units the operation's attacks consumed, per stack (P11.3)
 * @param playerFocusDelta net change of the player's Focus pool, which lives outside the encounter (P11.4)
 */
public record TacticalPlan(UUID encounterId, long baseRowVersion, List<Step> steps, List<ParticipantUpdate> participants,
        List<Note> notes, long elapsedSeconds, Map<String, Object> summary, int playerHealthDelta,
        List<AmmunitionUse> ammunition, int playerFocusDelta) {
    /**
     * Most encounter row states one operation may write. One non-player activation writes at most nine (start, wait,
     * resolve and wait for a move, a strike and the Guard action, end), and an operation runs at most eight of them
     * plus the player's end, start and opening strike and one round boundary.
     */
    public static final int MAX_STEPS = 128;

    /** Plan that changes nothing outside the encounter, the shape of an operation without attacks. */
    public TacticalPlan(UUID encounterId, long baseRowVersion, List<Step> steps, List<ParticipantUpdate> participants,
            List<Note> notes, long elapsedSeconds, Map<String, Object> summary) {
        this(encounterId, baseRowVersion, steps, participants, notes, elapsedSeconds, summary, 0, List.of(), 0);
    }

    /**
     * Units an attack consumed.
     *
     * @param instance item instance
     * @param definition canonical item definition
     * @param quantity units, at least one
     */
    public record AmmunitionUse(UUID instance, String definition, int quantity) {
        /** Require identities and a positive quantity. */
        public AmmunitionUse {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(definition, "definition");
            if (quantity < 1) {
                throw new IllegalArgumentException("consumed quantity must be positive");
            }
        }
    }

    /**
     * One encounter row state.
     *
     * @param phase lifecycle phase
     * @param round round number
     * @param cursor acting initiative rank or null
     * @param actionPoints remaining action points or null
     * @param speechUsed speech-only utterances used in the activation
     * @param passages passage states after this step
     * @param openingStatus {@code NONE}, {@code PENDING} or {@code RESOLVED} after this step
     * @param effectsApplied the encounter's application counter after this step
     */
    public record Step(EncounterPhase phase, int round, Integer cursor, Integer actionPoints, int speechUsed,
            List<EncounterAction.Passage> passages, String openingStatus, int effectsApplied) {
        /** Encounter row state of a fight that has applied no effect, the shape used before P11.4. */
        public Step(EncounterPhase phase, int round, Integer cursor, Integer actionPoints, int speechUsed,
                List<EncounterAction.Passage> passages, String openingStatus) {
            this(phase, round, cursor, actionPoints, speechUsed, passages, openingStatus, 0);
        }

        /** Require the phase and the cursor invariant; copy the passages. */
        public Step {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(openingStatus, "openingStatus");
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
     * @param status tactical standing after the operation
     * @param health encounter-local Health after the operation, or null for the player, whose pool lives elsewhere
     * @param guarding whether its Guard action holds after the operation
     * @param focus encounter-local Focus after the operation, or null for the player, whose pool lives elsewhere
     * @param effects active effect instances after the operation
     * @param policies reaction policies after the operation
     * @param uses ability uses after the operation
     */
    public record ParticipantUpdate(String participantId, long expectedRowVersion, String zone, Integer initiative,
            Integer rank, int reactions, EncounterState.Status status, Integer health, boolean guarding, Integer focus,
            List<CombatEffects.Active> effects, Map<String, CombatReaction.Policy> policies,
            Map<String, CombatAbility.Use> uses) {
        /** Require identities; copy the collections. */
        public ParticipantUpdate {
            Objects.requireNonNull(participantId, "participantId");
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(status, "status");
            effects = List.copyOf(effects);
            policies = Map.copyOf(policies);
            uses = Map.copyOf(uses);
        }
    }

    /**
     * One event of the operation, before the commit gives it a sequence.
     *
     * @param type event type
     * @param payload canonical payload values
     * @param byPlayer whether the player's own decision caused it (attributed to the player's actor); scheduler
     *        bookkeeping such as round boundaries and other participants' activations is not
     * @param internal whether the event stays engine-internal: an attack's full calculation names hidden values
     */
    public record Note(String type, Map<String, Object> payload, boolean byPlayer, boolean internal) {
        /** A public event. */
        public Note(String type, Map<String, Object> payload, boolean byPlayer) {
            this(type, payload, byPlayer, false);
        }

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
        ammunition = List.copyOf(ammunition);
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
