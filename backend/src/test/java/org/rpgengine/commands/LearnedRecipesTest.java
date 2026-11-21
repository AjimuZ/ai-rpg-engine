package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.CombatAbility;
import org.rpgengine.combat.CombatAbility.Kind;
import org.rpgengine.combat.CombatAbility.Targets;
import org.rpgengine.creative.AbilityDraft;
import org.rpgengine.creative.AbilityDraft.Step;
import org.rpgengine.creative.CreativeProfile;
import org.rpgengine.rules.CreativeBounds;

/**
 * Pure checks of the learned-recipe bounds and of the entry freeze (P15.4-B): versions and recipe counts stop at their
 * limits, and an entry freezes only the recipes that still fit the fight pack's profile, under their reserved IDs, with the
 * costs that profile derives.
 */
class LearnedRecipesTest {
    private static final Step HIT = new Step(Kind.DAMAGE, 1, 4, null, 0, 0, null, false);
    private static final AbilityDraft JAB = new AbilityDraft("Jab", Targets.HOSTILE, 1, 0, List.of(HIT));
    /** Two damage steps, at most three dice, budget 8, two points, one Focus and one round per complexity point. */
    private static final CreativeProfile PROFILE = new CreativeProfile("rules.creative", CreativeBounds.Power.STANDARD, 2, 2,
            1, 3, 2, EnumSet.of(Kind.DAMAGE), Set.of(), 0, false, 8, 2, 1, 1, Set.of("chain-riposte"));

    /** Construct the stateless fixture. */
    LearnedRecipesTest() { }

    /** A new recipe starts at version 1, a held one adds the next; the version and recipe limits refuse. */
    @Test
    void versionsAndRecipesAreBounded() {
        assertEquals(1, LearnedRecipes.nextVersion(List.of(), "jab"));
        assertEquals(4, LearnedRecipes.nextVersion(List.of(new LearnedRecipes.Stored("jab", 3, JAB)), "jab"));
        var last = assertThrows(OperationAdmissionFailure.class, () -> LearnedRecipes.nextVersion(
                List.of(new LearnedRecipes.Stored("jab", LearnedRecipes.MAX_VERSIONS, JAB)), "jab"));
        assertEquals(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, last.kind());
        var full = new ArrayList<LearnedRecipes.Stored>();
        for (int index = 1; index <= LearnedRecipes.MAX_RECIPES; index++) {
            full.add(new LearnedRecipes.Stored("jab-" + index, 1, JAB));
        }
        var ninth = assertThrows(OperationAdmissionFailure.class, () -> LearnedRecipes.nextVersion(full, "jab-9"));
        assertEquals(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, ninth.kind());
        assertEquals(2, LearnedRecipes.nextVersion(full, "jab-8"));
    }

    /**
     * Entry previews each recipe again: one that fits is frozen under {@code #recipe:<id>} with the profile's costs, sorted by
     * recipe ID; one the profile no longer allows, or whose prerequisite is missing, is not frozen; without a profile nothing is.
     */
    @Test
    void entryFreezesOnlyRecipesThatStillFit() {
        var heavy = new AbilityDraft("Heavy", Targets.HOSTILE, 1, 0, List.of(HIT, HIT, HIT));
        var recipes = List.of(new LearnedRecipes.Stored("zeta", 1, JAB), new LearnedRecipes.Stored("heavy", 2, heavy),
                new LearnedRecipes.Stored("alpha", 3, JAB));
        var frozen = LearnedRecipes.frozen(PROFILE, recipes, Map.of(), Set.of("chain-riposte"));
        var jab = List.of(new CombatAbility.Operation(Kind.DAMAGE, 1, 4, null, 0, 0, null, false));
        assertEquals(List.of(new CombatAbility("#recipe:alpha", 2, CombatAbility.Limit.NONE, 2, 2, Targets.HOSTILE, 1, 0, jab),
                new CombatAbility("#recipe:zeta", 2, CombatAbility.Limit.NONE, 2, 2, Targets.HOSTILE, 1, 0, jab)), frozen);
        assertEquals(List.of(), LearnedRecipes.frozen(PROFILE, recipes, Map.of(), Set.of()), "a missing prerequisite");
        assertEquals(List.of(), LearnedRecipes.frozen(null, recipes, Map.of(), Set.of("chain-riposte")), "no profile");
    }

    /** Learning refuses a missing profile, a draft outside it and a missing prerequisite, in that order. */
    @Test
    void learningPreviewsTheDraftAgain() {
        assertEquals(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, assertThrows(OperationAdmissionFailure.class,
                () -> LearnedRecipes.learnable(null, JAB, Map.of(), Set.of("chain-riposte"))).kind());
        var heavy = new AbilityDraft("Heavy", Targets.HOSTILE, 1, 0, List.of(HIT, HIT, HIT));
        assertEquals(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED, assertThrows(OperationAdmissionFailure.class,
                () -> LearnedRecipes.learnable(PROFILE, heavy, Map.of(), Set.of())).kind());
        assertEquals(OperationAdmissionFailure.Kind.PREREQUISITE_UNMET, assertThrows(OperationAdmissionFailure.class,
                () -> LearnedRecipes.learnable(PROFILE, JAB, Map.of(), Set.of())).kind());
        assertEquals(2, LearnedRecipes.learnable(PROFILE, JAB, Map.of(), Set.of("chain-riposte")).complexity());
    }
}
