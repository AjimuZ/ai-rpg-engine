package org.rpgengine.commands;

import java.util.Objects;
import java.util.Set;

import org.rpgengine.combat.CombatAbility;
import org.rpgengine.combat.EncounterState;
import org.rpgengine.creative.AbilityDraft;
import org.rpgengine.creative.DraftPreview;

/**
 * The preview that stands between a {@code COMBAT_USE_DRAFT} command and the combat scheduler (P15.4-B, docs/10 section 9).
 * The rule gate and the resolver both call it under the same campaign lock, so the candidate the gate accepted is exactly
 * the one the scheduler resolves; nothing computed by an earlier preview is reused.
 *
 * <p>Inputs: the creative profile of the fight's own pack (the frozen combat profile's pack), the effects this fight froze,
 * the player's encounter-local Focus and the abilities the player has learned. Refusal categories, in order:
 * {@code ABILITY_UNAVAILABLE} when that pack declares no creative profile, {@code EFFECT_UNSUPPORTED} when the draft breaks
 * the profile's bounds and {@code PREREQUISITE_UNMET} when a profile prerequisite is not learned. Too little Focus is left
 * to {@link org.rpgengine.combat.TacticalRules#draftRefusal}, which reports it after the turn, the cooldown and prevention,
 * as it does for every ability.
 */
final class DraftUse {
    /** Static helper only. */
    private DraftUse() {
    }

    /**
     * Preview a draft at the current state and return the ability it becomes.
     *
     * @param catalog the campaign's pinned rule facts
     * @param state the active encounter as read under the campaign lock
     * @param draft the player's draft
     * @param learned canonical abilities the player has learned, read under the same lock
     * @return the candidate, under {@link DraftPreview#CANDIDATE_ID}, with costs derived from the profile
     * @throws OperationAdmissionFailure with the first category above
     */
    static CombatAbility candidate(CommandRuleCatalog catalog, EncounterState state, AbilityDraft draft, Set<String> learned) {
        Objects.requireNonNull(catalog, "catalog");
        var profile = catalog.creative().get(state.profile().pack());
        if (profile == null) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE);
        }
        var result = DraftPreview.preview(profile, draft, state.effects(),
                new DraftPreview.Caster(state.player().focus(), learned));
        if (!(result instanceof DraftPreview.Ready ready)) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED);
        }
        if (ready.blockers().stream().anyMatch(blocker -> blocker.code().equals("prerequisite-missing"))) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.PREREQUISITE_UNMET);
        }
        return ready.candidate();
    }
}
