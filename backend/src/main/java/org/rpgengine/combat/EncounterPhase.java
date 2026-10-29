package org.rpgengine.combat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of one encounter: the combat state machine of docs/11 section 2 (P11.1).
 *
 * <p>{@link #PROPOSED}, {@link #VALIDATED} and {@link #REJECTED} describe a candidate before anything is
 * written: an entry request is validated in memory and either refused (nothing persists) or frozen. A stored
 * encounter therefore starts at {@link #ENTERING}, the frozen state before initiative. The V22 migration
 * repeats {@link #successors} as a trigger, so no writer can skip or reverse a step.
 *
 * <p>Since P11.1 only entry is executed; the round, activation and settlement steps are the transitions
 * later tasks drive (initiative and activations P11.2, outcome and settlement P11.5).
 */
public enum EncounterPhase {
    /** A candidate encounter exists but has not been checked. */
    PROPOSED,
    /** The candidate passed validation and may be frozen. */
    VALIDATED,
    /** The candidate failed validation; terminal and never stored. */
    REJECTED,
    /** Frozen roster, profile, zones and objectives are stored; initiative has not been rolled. */
    ENTERING,
    /** A round opens: reaction budgets refresh once. */
    ROUND_OPEN,
    /** An actor's activation starts: expiring effects, start triggers, action points. */
    ACTOR_START,
    /** The acting actor may choose an action. */
    AWAITING_ACTION,
    /** One chosen action resolves atomically. */
    RESOLVING,
    /** The activation ends; unused action points expire. */
    ACTOR_END,
    /** Every eligible activation of the round has run; end-of-round effects resolve. */
    ROUND_END,
    /** A terminal predicate holds; settlement is owed. */
    OUTCOME_PENDING,
    /** The outcome is settled exactly once. */
    SETTLED,
    /** Narrative play resumes; terminal. */
    NARRATIVE_RESUME;

    /** Legal successors of every phase, exactly the arrows of the docs/11 diagram. */
    private static final Map<EncounterPhase, Set<EncounterPhase>> NEXT = Map.ofEntries(
            Map.entry(PROPOSED, EnumSet.of(VALIDATED, REJECTED)),
            Map.entry(VALIDATED, EnumSet.of(ENTERING)),
            Map.entry(REJECTED, EnumSet.noneOf(EncounterPhase.class)),
            Map.entry(ENTERING, EnumSet.of(ROUND_OPEN)),
            Map.entry(ROUND_OPEN, EnumSet.of(ACTOR_START)),
            Map.entry(ACTOR_START, EnumSet.of(AWAITING_ACTION, OUTCOME_PENDING)),
            Map.entry(AWAITING_ACTION, EnumSet.of(RESOLVING, ACTOR_END)),
            Map.entry(RESOLVING, EnumSet.of(AWAITING_ACTION, ACTOR_END, OUTCOME_PENDING)),
            Map.entry(ACTOR_END, EnumSet.of(ACTOR_START, ROUND_END)),
            Map.entry(ROUND_END, EnumSet.of(ROUND_OPEN, OUTCOME_PENDING)),
            Map.entry(OUTCOME_PENDING, EnumSet.of(SETTLED)),
            Map.entry(SETTLED, EnumSet.of(NARRATIVE_RESUME)),
            Map.entry(NARRATIVE_RESUME, EnumSet.noneOf(EncounterPhase.class)));

    /** @return the phases this phase may move to, possibly none */
    public Set<EncounterPhase> successors() {
        return Set.copyOf(NEXT.get(this));
    }

    /** @return whether no transition leaves this phase */
    public boolean terminal() {
        return NEXT.get(this).isEmpty();
    }

    /**
     * Whether a stored encounter in this phase still holds the campaign in tactical mode. Only stored phases
     * count; the pre-storage candidate phases are never active.
     *
     * @return true from {@link #ENTERING} through {@link #OUTCOME_PENDING}
     */
    public boolean active() {
        return compareTo(ENTERING) >= 0 && compareTo(SETTLED) < 0;
    }

    /**
     * Check one step.
     *
     * @param next the phase to move to
     * @return {@code next}, for chaining
     * @throws IllegalStateException when the diagram has no such arrow
     */
    public EncounterPhase require(EncounterPhase next) {
        if (!NEXT.get(this).contains(next)) {
            throw new IllegalStateException("illegal encounter transition " + this + " -> " + next);
        }
        return next;
    }
}
