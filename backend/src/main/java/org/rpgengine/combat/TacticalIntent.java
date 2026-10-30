package org.rpgengine.combat;

import java.util.Objects;

/**
 * Closed vocabulary of the player's tactical intentions inside an encounter (P11.2 and P11.3, docs/11 sections 6-9).
 * Each value names only what the player decides: a destination zone, a recipient, a declared action, an attack and
 * its target. Costs, legality and every effect come from the frozen encounter through {@link TacticalRules} and
 * {@link CombatScheduler}; the commands layer decodes the wire payloads and hands these values over.
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

    /**
     * Make one frozen attack against a participant (P11.3).
     *
     * @param attack frozen attack ID
     * @param target participant ID attacked
     */
    record Strike(String attack, String target) implements TacticalIntent {
        /** Require both fields. */
        public Strike {
            Objects.requireNonNull(attack, "attack");
            Objects.requireNonNull(target, "target");
        }
    }

    /** Take the Guard action: a Guard bonus until the start of the player's next activation (P11.3). */
    record Guard() implements TacticalIntent {
    }

    /**
     * Use one held item in the fight (P11.3). The pinned item's use contract decides its effects; this value carries
     * only what the combat rules can check over the frozen encounter.
     *
     * @param target participant the item is used on, or null for the player's own use
     */
    record UseItem(String target) implements TacticalIntent {
    }
}
