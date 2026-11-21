package org.rpgengine.creative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.CombatAbility;
import org.rpgengine.combat.CombatAbility.Kind;
import org.rpgengine.combat.CombatAbility.Targets;
import org.rpgengine.combat.CombatEffect;
import org.rpgengine.combat.CombatEffect.Clock;
import org.rpgengine.combat.CombatEffect.Prevents;
import org.rpgengine.combat.CombatEffect.Stacking;
import org.rpgengine.creative.AbilityDraft.Step;
import org.rpgengine.creative.DraftPreview.Caster;
import org.rpgengine.creative.DraftPreview.Duration;
import org.rpgengine.creative.DraftPreview.Issue;
import org.rpgengine.creative.DraftPreview.Ready;
import org.rpgengine.creative.DraftPreview.Rejected;

/**
 * Creative ability drafting and validation preview (P15.4-A, docs/10 section 9, docs/20 scenario 7): a draft is checked
 * against its profile, its cost is derived rather than proposed, blockers of the current state are shown, and a preview never
 * executes, learns or changes anything.
 */
class DraftPreviewTest {
    /** Two round ends of -2 attack and no movement: magnitude 2. */
    private static final CombatEffect REPELLED = new CombatEffect("repelled", "repelled", Stacking.STRONGEST, 1,
            Clock.ROUND_ENDS, 2, false, 0, 0, 0, -2, 0, Set.of(Prevents.MOVEMENT), List.of(), true);
    private static final CombatEffect BOUND = new CombatEffect("bound", "bound", Stacking.STRONGEST, 1, Clock.PERSISTENT, 0,
            false, 0, 0, 0, 0, 0, Set.of(Prevents.ACTIONS), List.of(), true);
    private static final CombatEffect SLOWED = new CombatEffect("slowed", "slowed", Stacking.STRONGEST, 1, Clock.ROUND_ENDS,
            9, false, 0, 0, 0, -1, 0, Set.of(), List.of(), true);
    private static final Map<String, CombatEffect> CATALOG = Map.of("repelled", REPELLED, "bound", BOUND, "slowed", SLOWED);
    private static final CreativeProfile PROFILE = new CreativeProfile("weaving", 3, 3, 2, 4, 4,
            EnumSet.of(Kind.DAMAGE, Kind.APPLY, Kind.REMOVE), Set.of("repelled", "bound", "slowed", "absent"), 3, false, 16, 2,
            1, 1, Set.of("weaving-basics"));
    /** The scenario 7 recipe: push two creatures back with a light hit and a repelling effect. */
    private static final AbilityDraft REPEL = new AbilityDraft("Repelling gust", Targets.HOSTILE, 2, 1, List.of(
            new Step(Kind.DAMAGE, 1, 4, null, 0, 0, null, false),
            new Step(Kind.APPLY, 0, 0, null, 0, 0, "repelled", false)));
    private static final Caster READY = new Caster(20, Set.of("weaving-basics"));

    /** Construct a stateless fixture. */
    DraftPreviewTest() {
    }

    /** A valid draft shows derived costs, targets and durations, and previewing it twice changes nothing. */
    @Test
    void validDraftShowsDerivedCostTargetsAndDurations() {
        var result = assertInstanceOf(Ready.class, DraftPreview.preview(PROFILE, REPEL, CATALOG, READY));

        // (1 + 1 die) + (2 + magnitude 2) = 6 per target, times 2 targets, plus range 1.
        assertEquals(13, result.complexity());
        CombatAbility candidate = result.candidate();
        assertEquals(DraftPreview.CANDIDATE_ID, candidate.id());
        assertEquals(2, candidate.cost());
        assertEquals(13, candidate.focus());
        assertEquals(13, candidate.cooldownRounds());
        assertEquals(CombatAbility.Limit.NONE, candidate.limit());
        assertEquals(Targets.HOSTILE, candidate.targets());
        assertEquals(2, candidate.maxTargets());
        assertEquals(1, candidate.range());
        assertEquals(List.of(new Duration("repelled", Clock.ROUND_ENDS, 2)), result.durations());
        assertFalse(result.zeroCost());
        assertTrue(result.unblocked());
        assertEquals("Repelling gust", result.label());

        assertEquals(result, DraftPreview.preview(PROFILE, REPEL, CATALOG, READY));
        assertEquals(new Caster(20, Set.of("weaving-basics")), READY);
        assertEquals(3, CATALOG.size());
    }

    /** The current state only blocks a valid draft, and a later preview at another state decides again. */
    @Test
    void currentStateBlocksWithoutInvalidatingAndIsCheckedAgain() {
        var blocked = assertInstanceOf(Ready.class, DraftPreview.preview(PROFILE, REPEL, CATALOG, new Caster(12, Set.of())));
        assertEquals(List.of(new Issue("prerequisite-missing", "/prerequisites/weaving-basics"),
                new Issue("insufficient-focus", "/focus")), blocked.blockers());
        assertFalse(blocked.unblocked());

        var later = assertInstanceOf(Ready.class, DraftPreview.preview(PROFILE, REPEL, CATALOG,
                new Caster(13, Set.of("weaving-basics"))));
        assertTrue(later.unblocked());
        assertEquals(blocked.candidate(), later.candidate());
    }

    /** A zero-cost profile charges nothing, and says so. */
    @Test
    void zeroCostIsAnExplicitProfileChoice() {
        var free = new CreativeProfile("free", 3, 3, 2, 4, 4, EnumSet.of(Kind.DAMAGE, Kind.APPLY), Set.of("repelled"), 3,
                false, 16, 1, 0, 0, Set.of());
        var result = assertInstanceOf(Ready.class, DraftPreview.preview(free, REPEL, CATALOG, new Caster(0, Set.of())));
        assertTrue(result.zeroCost());
        assertEquals(0, result.candidate().focus());
        assertEquals(0, result.candidate().cooldownRounds());
        assertEquals(1, result.candidate().cost());
        assertTrue(result.unblocked());
    }

    /** Each bound of the profile and of a frozen ability rejects the draft with its code and path. */
    @Test
    void eachRuleRejectsWithItsCode() {
        Step hit = new Step(Kind.DAMAGE, 1, 4, null, 0, 0, null, false);
        assertRejected(new AbilityDraft(" ", Targets.HOSTILE, 1, 0, List.of(hit)), new Issue("label", "/label"));
        assertRejected(new AbilityDraft("x", Targets.HOSTILE, 4, 0, List.of(hit)), new Issue("target-count", "/maxTargets"));
        assertRejected(new AbilityDraft("x", Targets.SELF, 2, 0, List.of(hit)), new Issue("target-count", "/maxTargets"));
        assertRejected(new AbilityDraft("x", Targets.HOSTILE, 1, 3, List.of(hit)), new Issue("range", "/range"));
        assertRejected(new AbilityDraft("x", Targets.HOSTILE, 1, 0, List.of()), new Issue("operation-count", "/steps"));
        assertRejected(new AbilityDraft("x", Targets.HOSTILE, 1, 0, List.of(hit, hit, hit, hit)),
                new Issue("operation-count", "/steps"));
        assertRejected(step(new Step(Kind.HEAL, 1, 4, null, 0, 0, null, true)), new Issue("kind-not-allowed", "/steps/0/kind"));
        assertRejected(step(new Step(Kind.DAMAGE, 1, 4, null, 0, 0, "repelled", false)),
                new Issue("operand", "/steps/0/effectId"));
        assertRejected(step(new Step(Kind.APPLY, 1, 4, null, 0, 0, "repelled", false)),
                new Issue("operand", "/steps/0/effectId"));
        assertRejected(step(new Step(Kind.DAMAGE, 5, 4, null, 0, 0, null, false)), new Issue("dice", "/steps/0/diceCount"));
        assertRejected(step(new Step(Kind.DAMAGE, 1, 1, null, 0, 0, null, false)), new Issue("dice", "/steps/0/diceCount"));
        assertRejected(new AbilityDraft("x", Targets.HOSTILE, 1, 0, List.of(new Step(Kind.DAMAGE, 3, 4, null, 0, 0, null,
                false), new Step(Kind.DAMAGE, 2, 4, null, 0, 0, null, false))), new Issue("dice", "/steps"));
        assertRejected(step(new Step(Kind.DAMAGE, 1, 4, null, 5, 0, null, false)), new Issue("amount", "/steps/0/flat"));
        assertRejected(step(new Step(Kind.DAMAGE, 1, 4, null, 0, 11, null, false)), new Issue("amount", "/steps/0/flat"));
        assertRejected(step(new Step(Kind.DAMAGE, 1, 4, "charm", 0, 0, null, false)),
                new Issue("attribute", "/steps/0/attribute"));
        assertRejected(step(new Step(Kind.APPLY, 0, 0, null, 0, 0, "absent", false)),
                new Issue("unknown-effect", "/steps/0/effectId"));
        assertRejected(step(new Step(Kind.REMOVE, 0, 0, null, 0, 0, "burning", false)),
                new Issue("unknown-effect", "/steps/0/effectId"));
        assertRejected(step(new Step(Kind.APPLY, 0, 0, null, 0, 0, "bound", false)), new Issue("duration", "/steps/0/effectId"));
        assertRejected(step(new Step(Kind.APPLY, 0, 0, null, 0, 0, "slowed", false)), new Issue("duration", "/steps/0/effectId"));
        // (1 + 4 dice + 4 flat + 1 attribute) per target, times 3 targets: 30, above the budget of 16.
        assertRejected(new AbilityDraft("x", Targets.HOSTILE, 3, 0, List.of(new Step(Kind.DAMAGE, 4, 6, "force", 4, 0, null,
                false))), new Issue("complexity", "/steps"));
    }

    /** A profile cannot promise costs or sizes a frozen ability could not hold. */
    @Test
    void profileBoundsKeepEveryCandidateFrozenable() {
        Set<Kind> kinds = EnumSet.of(Kind.DAMAGE);
        assertThrows(IllegalArgumentException.class, () -> new CreativeProfile("p", 3, 3, 2, 4, 4, kinds, Set.of(), 3, false,
                32, 1, 4, 0, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CreativeProfile("p", 3, 3, 2, 4, 4, kinds, Set.of(), 3, false,
                16, 1, 0, 2, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CreativeProfile("p", 5, 3, 2, 4, 4, kinds, Set.of(), 3, false,
                16, 1, 0, 0, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CreativeProfile("p", 3, 3, 2, 4, 4, Set.of(), Set.of(), 3,
                false, 16, 1, 0, 0, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CreativeProfile("p", 3, 3, 2, 4, 4, kinds, Set.of(), 3, false,
                16, 0, 0, 0, Set.of()));
    }

    /**
     * Structural check that a preview cannot execute: the only entry point takes immutable values and returns a result whose
     * parts are text, numbers, flags, lists and a frozen ability definition; a draft declares no cost of its own.
     */
    @Test
    void previewHasNoPathToExecution() {
        var entries = Arrays.stream(DraftPreview.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())).toList();
        assertEquals(1, entries.size());
        assertEquals(List.of(CreativeProfile.class, AbilityDraft.class, Map.class, Caster.class),
                List.of(entries.getFirst().getParameterTypes()));
        Set<Class<?>> allowed = Set.of(String.class, int.class, boolean.class, List.class, CombatAbility.class);
        for (Class<?> result : List.of(Ready.class, Rejected.class, Issue.class, Duration.class)) {
            for (var component : result.getRecordComponents()) {
                assertTrue(allowed.contains(component.getType()) || component.getType() == Clock.class,
                        result.getSimpleName() + "." + component.getName());
            }
        }
        var draftFields = Arrays.stream(AbilityDraft.class.getRecordComponents()).map(c -> c.getName()).toList();
        assertEquals(List.of("label", "targets", "maxTargets", "range", "steps"), draftFields);
    }

    /** @return a one-target, range-zero draft of one step */
    private static AbilityDraft step(Step step) {
        return new AbilityDraft("x", Targets.HOSTILE, 1, 0, List.of(step));
    }

    /** Require exactly one issue. */
    private static void assertRejected(AbilityDraft draft, Issue expected) {
        var rejected = assertInstanceOf(Rejected.class, DraftPreview.preview(PROFILE, draft, CATALOG, READY));
        assertEquals(List.of(expected), rejected.issues());
    }
}
