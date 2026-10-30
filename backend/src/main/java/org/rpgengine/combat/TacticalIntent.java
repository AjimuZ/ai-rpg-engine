package org.rpgengine.combat;

import java.util.Objects;

/**
 * Closed vocabulary of the player's tactical intentions inside an encounter (P11.2, docs/11 sections 6-7.1). Each
 * value names only what the player decides: a destination zone, a recipient, a declared action. Costs, legality
 * and every effect come from the frozen encounter through {@link TacticalRules} and {@link CombatScheduler}; the
 * commands layer decodes the wire payloads and hands these values over.
 */
public sealed interface TacticalIntent {
    /** Roll initiative for every participant once and open round one; legal only right after entry. */
    record RollInitiative() implements TacticalIntent {
    }

    /**
     * Move the acting player across one edge to an adjacent zone.
     *
     * @param zone destination zone ID
     */
    record MoveZone(String zone) implements TacticalIntent {
        /** Require a destination. */
        public MoveZone {
            Objects.requireNonNull(zone, "zone");
        }
    }

    /** End the player's activation; unused action points expire and the scheduler continues. */
    record EndActivation() implements TacticalIntent {
    }

    /**
     * One bounded speech-only utterance to a participant: no action points, no mechanical benefit.
     *
     * @param recipient participant ID addressed
     * @param text the player's exact words, already length-bounded by the command decoder
     */
    record Speech(String recipient, String text) implements TacticalIntent {
        /** Require both fields. */
        public Speech {
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Attempt one declared social or scene action of the frozen encounter.
     *
     * @param action declared action ID
     * @param target addressed participant of a social action; null for a scene action
     */
    record Interact(String action, String target) implements TacticalIntent {
        /** Require the action. */
        public Interact {
            Objects.requireNonNull(action, "action");
        }
    }
}
