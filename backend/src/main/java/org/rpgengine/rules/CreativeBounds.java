package org.rpgengine.rules;

import java.util.Locale;

/**
 * Engine ceilings of a creative-abilities profile (P15.4-B, docs/10 section 9), shared by the pack compiler and
 * {@code org.rpgengine.creative.CreativeProfile}, so a profile the compiler admits always constructs and every draft it
 * allows can be frozen as a combat ability.
 *
 * <p>A profile has a {@link Power} tier. A {@link Power#STANDARD} profile (the default) keeps its complexity budget at
 * {@link #STANDARD_COMPLEXITY_MAX} or below, must charge Focus or a cooldown, and cannot apply an effect that lasts until
 * removed. A {@link Power#HIGH} profile is the explicit content choice that lifts those three limits, up to
 * {@link #COMPLEXITY_MAX}. Neither tier lifts a frozen ability's own bounds (operations, dice, targets, range, costs), and
 * every drafted ability still costs at least one action point, so even a zero-cost high-power draft cannot repeat for free.
 * The two complexity ceilings are provisional and have not been playtested.
 */
public final class CreativeBounds {
    /** Largest complexity budget of any profile; bounds the cost arithmetic. */
    public static final int COMPLEXITY_MAX = 32;
    /** Largest complexity budget of a standard profile. */
    public static final int STANDARD_COMPLEXITY_MAX = 16;

    /** Static checks only. */
    private CreativeBounds() {
    }

    /** How far a profile may go inside the engine ceilings. */
    public enum Power {
        /** Bounded play: a budget of at most {@link #STANDARD_COMPLEXITY_MAX}, a real cost and no persistent effect. */
        STANDARD,
        /** Explicitly authored high-power play: up to {@link #COMPLEXITY_MAX}, zero cost and persistent effects allowed. */
        HIGH;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Check the rules that join several values of one profile. Each value's own range is the caller's to check.
     *
     * @param power the profile's tier
     * @param complexityBudget its budget, already within 1..{@link #COMPLEXITY_MAX}
     * @param focusPerComplexity Focus charged per complexity point, already non-negative
     * @param cooldownPerComplexity cooldown rounds per complexity point, already non-negative
     * @param persistentEffects whether a draft may apply an effect that lasts until removed
     * @return null when the values fit together, otherwise the first rule they break as an English sentence fit for an
     *         author diagnostic
     */
    public static String violation(Power power, int complexityBudget, int focusPerComplexity, int cooldownPerComplexity,
            boolean persistentEffects) {
        if (complexityBudget * focusPerComplexity > CombatProfile.FOCUS_COST_MAX) {
            return "The budget times the Focus rate exceeds the largest Focus cost (" + CombatProfile.FOCUS_COST_MAX + ")";
        }
        if (complexityBudget * cooldownPerComplexity > CombatProfile.ABILITY_COOLDOWN_MAX) {
            return "The budget times the cooldown rate exceeds the longest cooldown (" + CombatProfile.ABILITY_COOLDOWN_MAX + ")";
        }
        if (power == Power.STANDARD) {
            if (complexityBudget > STANDARD_COMPLEXITY_MAX) {
                return "A standard profile has a budget of at most " + STANDARD_COMPLEXITY_MAX + "; a larger one needs power high";
            }
            if (focusPerComplexity == 0 && cooldownPerComplexity == 0) {
                return "A standard profile charges Focus or a cooldown; a zero-cost profile needs power high";
            }
            if (persistentEffects) {
                return "A standard profile cannot apply persistent effects; they need power high";
            }
        }
        return null;
    }
}
