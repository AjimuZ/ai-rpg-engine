package org.rpgengine.creative;

import java.util.List;
import java.util.Objects;

import org.rpgengine.combat.CombatAbility;

/**
 * A player's proposed combat ability (P15.4-A, docs/10 section 9): a bounded symbolic recipe of engine operations, written by
 * the player or translated from their description. It is a proposal only. It is unchecked on purpose, so that any proposal can
 * be shown with its problems; {@link DraftPreview} is what checks it.
 *
 * <p>A draft has no field for its action-point cost, Focus cost, cooldown or use limit: the engine derives them from the
 * profile, so a draft cannot make itself cheap. It also has no identity as a learned ability and no campaign reference.
 *
 * @param label the player's name for the draft
 * @param targets who it would take
 * @param maxTargets most explicit targets
 * @param range greatest zone distance to a target
 * @param steps what it would do, in order, for each target
 */
public record AbilityDraft(String label, CombatAbility.Targets targets, int maxTargets, int range, List<Step> steps) {
    /**
     * One proposed operation, with the fields of {@link CombatAbility.Operation} but none of its checks.
     *
     * @param kind what it would do
     * @param diceCount dice of a damage or healing amount
     * @param diceSides faces of each die
     * @param attribute ruleset role added to the amount, or null
     * @param flat flat part of the amount
     * @param penetration armor a damage operation would ignore
     * @param effectId the frozen effect to apply or remove, or null
     * @param toSelf whether it would affect the user rather than the targets
     */
    public record Step(CombatAbility.Kind kind, int diceCount, int diceSides, String attribute, int flat, int penetration,
            String effectId, boolean toSelf) {
        /** Require a kind. */
        public Step {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Require the label and the target rule, and freeze the steps. */
    public AbilityDraft {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(targets, "targets");
        steps = List.copyOf(steps);
    }
}
