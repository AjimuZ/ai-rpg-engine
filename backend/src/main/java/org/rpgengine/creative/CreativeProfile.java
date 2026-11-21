package org.rpgengine.creative;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.rpgengine.combat.CombatAbility;
import org.rpgengine.combat.CombatEffect;
import org.rpgengine.combat.EncounterTemplate;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.CreativeBounds;

/**
 * Bounds and cost rules of one creative-abilities profile (P15.4, docs/10 section 9): how large a draft may be and what the
 * engine charges for it. A draft never declares its own cost; {@link DraftPreview} derives it from the draft's complexity
 * with the rates here. Since P15.4-B a pack declares one with a {@code ruleType: creative} rule.
 *
 * <p>Invariants, checked on construction: every bound lies inside the corresponding bound of a frozen
 * {@link CombatAbility}, and the most expensive draft the complexity budget allows still has a Focus cost and a cooldown a
 * frozen ability can hold. A validated draft can therefore always be expressed as a {@link CombatAbility}. The
 * {@link CreativeBounds.Power power} tier adds the rules of {@link CreativeBounds#violation}: only a high-power profile may
 * have a budget above {@link CreativeBounds#STANDARD_COMPLEXITY_MAX}, charge nothing (both rates 0) or apply persistent
 * effects, so zero cost is always an explicit content choice, never a default.
 *
 * @param id stable profile reference
 * @param power how far the profile may go inside the engine ceilings (P15.4-B)
 * @param maxOperations most operations of one draft, 1..{@link CombatAbility#MAX_OPERATIONS}
 * @param maxTargets most explicit targets, 1..the most participants of an encounter plus one
 * @param maxRange greatest zone distance, 0..the largest {@code maxRangeEdges}
 * @param maxDice most dice of one draft, 0..{@link CombatAbility#MAX_DICE}
 * @param maxFlat largest flat part of an amount, 0..{@link CombatProfile#ATTACK_FLAT_MAX}
 * @param kinds operation kinds a draft may use; never empty
 * @param effects frozen effect IDs a draft may apply or remove
 * @param maxEffectCount largest duration count of an applied effect on a counting clock, 0..{@link CombatEffect#COUNT_MAX}
 * @param persistentEffects whether a draft may apply an effect that lasts until removed; high power only
 * @param complexityBudget largest complexity of one draft, 1..{@link CreativeBounds#COMPLEXITY_MAX}
 * @param actionPoints action points every drafted ability costs, at least 1, so a draft can never repeat for free
 * @param focusPerComplexity Focus charged per point of complexity
 * @param cooldownPerComplexity cooldown rounds per point of complexity
 * @param prerequisites learned ability IDs the user needs before a draft can be used
 */
public record CreativeProfile(String id, CreativeBounds.Power power, int maxOperations, int maxTargets, int maxRange,
        int maxDice, int maxFlat, Set<CombatAbility.Kind> kinds, Set<String> effects, int maxEffectCount,
        boolean persistentEffects, int complexityBudget, int actionPoints, int focusPerComplexity, int cooldownPerComplexity,
        Set<String> prerequisites) {
    /** Largest complexity budget of any profile; bounds the cost arithmetic. */
    public static final int COMPLEXITY_MAX = CreativeBounds.COMPLEXITY_MAX;

    /**
     * Freeze the sets and check every bound.
     *
     * @throws IllegalArgumentException for a bound outside a frozen ability's, an empty kind set, rates that would let the
     *         budget's most complex draft exceed the largest Focus cost or cooldown, or a standard profile that uses what only
     *         a high-power profile may
     */
    public CreativeProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(power, "power");
        if (id.isBlank()) {
            throw new IllegalArgumentException("a creative profile has an id");
        }
        kinds = Collections.unmodifiableSet(kinds.isEmpty() ? EnumSet.noneOf(CombatAbility.Kind.class) : EnumSet.copyOf(kinds));
        effects = Collections.unmodifiableSet(new TreeSet<>(effects));
        prerequisites = Collections.unmodifiableSet(new TreeSet<>(prerequisites));
        if (kinds.isEmpty() || maxOperations < 1 || maxOperations > CombatAbility.MAX_OPERATIONS || maxTargets < 1
                || maxTargets > EncounterTemplate.MAX_PARTICIPANTS + 1 || maxRange < 0
                || maxRange > CombatProfile.PARAMETERS.get("maxRangeEdges").max() || maxDice < 0
                || maxDice > CombatAbility.MAX_DICE || maxFlat < 0 || maxFlat > CombatProfile.ATTACK_FLAT_MAX
                || maxEffectCount < 0 || maxEffectCount > CombatEffect.COUNT_MAX || complexityBudget < 1
                || complexityBudget > COMPLEXITY_MAX || actionPoints < 1
                || actionPoints > CombatProfile.PARAMETERS.get("actionPoints").max() || focusPerComplexity < 0
                || cooldownPerComplexity < 0) {
            throw new IllegalArgumentException("creative profile " + id + " has a value outside its bound");
        }
        String conflict = CreativeBounds.violation(power, complexityBudget, focusPerComplexity, cooldownPerComplexity,
                persistentEffects);
        if (conflict != null) {
            throw new IllegalArgumentException("creative profile " + id + ": " + conflict);
        }
    }

    /** @return whether this profile deliberately charges neither Focus nor a cooldown; only a high-power profile can */
    public boolean zeroCost() {
        return focusPerComplexity == 0 && cooldownPerComplexity == 0;
    }
}
