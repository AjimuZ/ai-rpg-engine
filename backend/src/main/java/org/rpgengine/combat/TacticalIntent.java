package org.rpgengine.combat;

import java.util.Objects;

/**
 * Closed vocabulary of the player's tactical intentions inside an encounter (P11.2 to P11.5, docs/11 sections 6-13).
 * Each value names only what the player decides: a destination zone, a recipient, a declared action, an attack and
 * its target, whether to put an item on or take it off, a participant to assess, a zone to withdraw to. Costs, legality and every effect come from the frozen encounter through {@link TacticalRules} and
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

    /**
     * Withdraw across one edge toward an escape zone (P11.5, docs/11 section 7, ADR 0035): a move priced by
     * {@code withdrawCost} that no movement-trigger reaction answers. It is never an escape by itself: only the terminal
     * predicates read the zone it reaches.
     *
     * @param zone destination zone ID
     */
    record Withdraw(String zone) implements TacticalIntent {
        /** Require a destination. */
        public Withdraw {
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

    /**
     * Ready or stow one item the player holds (P11.5, docs/11 section 7, ADR 0034): one change of one equipment assignment.
     * Which instance, its slot, hands and combat data are the commands layer's to check, because they are pinned item data;
     * the combat rules check the turn, prevention and {@code readyCost}.
     *
     * @param equip true to put the item on, false to take it off; the player always says which, nothing toggles
     */
    record Ready(boolean equip) implements TacticalIntent {
    }

    /**
     * Assess one participant (P11.5, docs/11 section 7): a recorded check that reveals its fighting values on success.
     *
     * @param target participant ID observed
     */
    record Assess(String target) implements TacticalIntent {
        /** Require the target. */
        public Assess {
            Objects.requireNonNull(target, "target");
        }
    }

    /**
     * Use one frozen ability (P11.4).
     *
     * @param ability frozen ability ID
     * @param targets participants it is used on, distinct; empty for a self ability
     */
    record UseAbility(String ability, java.util.List<String> targets) implements TacticalIntent {
        /** Require the ability; copy the targets. */
        public UseAbility {
            Objects.requireNonNull(ability, "ability");
            targets = java.util.List.copyOf(targets);
        }
    }

    /**
     * Answer the terms on the table by their identity (P11.5). This is the only way an agreement or a surrender comes
     * about: words, a response or a retry of the attempt never accept anything for the player.
     *
     * @param offer identity of the offer answered
     * @param accept true to accept its terms, false to decline them
     */
    record AnswerOffer(String offer, boolean accept) implements TacticalIntent {
        /** Require the offer. */
        public AnswerOffer {
            Objects.requireNonNull(offer, "offer");
        }
    }

    /**
     * Change one predeclared reaction policy (P11.4): a control command that costs nothing and can never answer a roll
     * already seen, because it is accepted only before the first round or at the player's own decision.
     *
     * @param reaction reaction ID, {@code brace} included
     * @param policy the new policy
     */
    record SetReactionPolicy(String reaction, CombatReaction.Policy policy) implements TacticalIntent {
        /** Require both fields. */
        public SetReactionPolicy {
            Objects.requireNonNull(reaction, "reaction");
            Objects.requireNonNull(policy, "policy");
        }
    }
}
