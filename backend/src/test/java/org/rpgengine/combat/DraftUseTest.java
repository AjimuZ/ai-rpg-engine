package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.CombatAbility.Kind;
import org.rpgengine.combat.CombatAbility.Targets;
import org.rpgengine.combat.TacticalRules.Refusal;
import org.rpgengine.creative.AbilityDraft;
import org.rpgengine.creative.AbilityDraft.Step;
import org.rpgengine.creative.CreativeProfile;
import org.rpgengine.creative.DraftPreview;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.CreativeBounds.Power;
import org.rpgengine.rules.ZoneGraph;

/**
 * Executing a creative draft in a fight (P15.4-B, docs/10 section 9): the candidate the preview returns is resolved by the
 * scheduler exactly like a frozen ability, every limit of an ability applies to it, a draft without its candidate is refused,
 * all drafts share one recorded use, and the largest high-power draft still stays inside the operation's draw budget.
 */
class DraftUseTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    /** Attack -2 until the holder's next activation: magnitude 2. */
    private static final CombatEffect WINDED = new CombatEffect("winded", "winded", CombatEffect.Stacking.STRONGEST, 1,
            CombatEffect.Clock.NEXT_ACTIVATION, 0, false, 0, 0, 0, -2, 0, Set.of(), List.of(), true);
    private static final CombatEffect STUNNED = new CombatEffect("stunned", "stunned", CombatEffect.Stacking.STRONGEST, 1,
            CombatEffect.Clock.ACTIVATIONS, 1, false, 0, 0, 0, 0, 0, Set.of(CombatEffect.Prevents.ACTIONS), List.of(), true);
    private static final CreativeProfile PROFILE = new CreativeProfile("weaving", Power.STANDARD, 2, 2, 1, 2, 2,
            EnumSet.of(Kind.DAMAGE, Kind.APPLY), Set.of("winded"), 2, false, 12, 2, 1, 1, Set.of());
    /** A light hit that winds one hostile beside the user: (1 + 1 die) + (2 + 2) = complexity 6. */
    private static final AbilityDraft GUST = new AbilityDraft("Gust", Targets.HOSTILE, 1, 0, List.of(
            new Step(Kind.DAMAGE, 1, 4, null, 0, 0, null, false), new Step(Kind.APPLY, 0, 0, null, 0, 0, "winded", false)));
    /** A bare hit: complexity 2. */
    private static final AbilityDraft JAB = new AbilityDraft("Jab", Targets.HOSTILE, 1, 0, List.of(
            new Step(Kind.DAMAGE, 1, 4, null, 0, 0, null, false)));

    /** Construct a stateless fixture. */
    DraftUseTest() {
    }

    /**
     * The candidate pays its derived points and Focus, takes its target's Health from one recorded die, applies its effect and
     * is recorded under the reserved ID; the event names that ID and the derived costs, never a learned ability.
     */
    @Test
    void aPreviewedDraftResolvesLikeAnAbility() {
        var state = fight(1, 3, 10, List.of());
        var candidate = candidate(GUST, state);
        assertEquals(DraftPreview.CANDIDATE_ID, candidate.id());
        assertEquals(6, candidate.focus());
        assertEquals(6, candidate.cooldownRounds());
        var draws = new ArrayList<Integer>();
        var plan = CombatScheduler.useDraft(state, new TacticalIntent.UseDraft(List.of("brute")), candidate, (ordinal, sides) -> {
            draws.add(ordinal);
            assertEquals(4, sides);
            return 3;
        });

        assertEquals(List.of(0), draws, "one die, drawn once");
        assertEquals(EncounterPhase.AWAITING_ACTION, plan.last().phase());
        assertEquals(1, plan.last().actionPoints());
        assertEquals(-6, plan.playerFocusDelta());
        var brute = update(plan, "brute");
        assertEquals(27, brute.health(), "3 from the die, no armor");
        assertTrue(brute.effects().stream().anyMatch(active -> active.effectId().equals("winded")));
        assertEquals(Map.of(DraftPreview.CANDIDATE_ID, new CombatAbility.Use(1, 1)), update(plan, EncounterTemplate.PLAYER).uses());
        var used = plan.notes().stream().filter(note -> note.type().equals("COMBAT_ABILITY_USED")).findFirst().orElseThrow();
        assertTrue(used.byPlayer());
        assertEquals(DraftPreview.CANDIDATE_ID, used.payload().get("abilityId"));
        assertEquals(2, used.payload().get("actionPoints"));
        assertEquals(6, used.payload().get("focus"));
    }

    /** Every ability rule applies to the candidate, in the same order, and nothing resolves a draft that was not previewed. */
    @Test
    void theAbilityLimitsApplyToTheCandidate() {
        var state = fight(1, 3, 10, List.of());
        var candidate = candidate(GUST, state);
        assertNull(TacticalRules.draftRefusal(state, use("brute"), candidate));
        assertEquals(Refusal.ABILITY, TacticalRules.refusal(state, use("brute")), "no candidate, no draft");
        assertThrows(IllegalStateException.class, () -> CombatScheduler.apply(state, use("brute"), (ordinal, sides) -> 1));

        assertEquals(Refusal.TARGET, TacticalRules.draftRefusal(state, use(), candidate), "a hostile draft names someone");
        assertEquals(Refusal.TARGET, TacticalRules.draftRefusal(state, use("porter"), candidate), "an ally is not hostile");
        assertEquals(Refusal.TARGET, TacticalRules.draftRefusal(state, use("sniper"), candidate), "two zones away, range 0");
        assertEquals(Refusal.TARGET, TacticalRules.draftRefusal(state, use("brute", "sniper"), candidate), "one target at most");
        assertEquals(Refusal.TARGET, TacticalRules.draftRefusal(state, use("ghost"), candidate));
        assertEquals(Refusal.RESOURCE, TacticalRules.draftRefusal(fight(1, 3, 5, List.of()), use("brute"), candidate));
        assertEquals(Refusal.ACTION_POINTS, TacticalRules.draftRefusal(fight(1, 1, 10, List.of()), use("brute"), candidate));
        var stunned = new CombatEffects.Active("e1", "stunned", null, 1, 1, 1, 0, null, false);
        assertEquals(Refusal.PREVENTED, TacticalRules.draftRefusal(fight(1, 3, 10, List.of(stunned)), use("brute"), candidate));
        var resolving = state(EncounterPhase.RESOLVING, 1, 3, 10, List.of(), Map.of());
        assertEquals(Refusal.TURN, TacticalRules.draftRefusal(resolving, use("brute"), candidate));
        assertThrows(IllegalStateException.class, () -> CombatScheduler.useDraft(resolving, use("brute"), candidate,
                (ordinal, sides) -> 1), "the scheduler checks again and refuses");
    }

    /**
     * Every draft is recorded under one ID, so a draft waits its own cooldown counted from the player's last draft, whichever
     * it was: after the complexity-6 gust, the complexity-2 jab waits two rounds and the gust six.
     */
    @Test
    void draftsShareTheirRecordedUse() {
        var used = Map.of(DraftPreview.CANDIDATE_ID, new CombatAbility.Use(1, 1));
        var jab = candidate(JAB, fight(1, 3, 10, List.of()));
        var gust = candidate(GUST, fight(1, 3, 10, List.of()));
        assertEquals(2, jab.cooldownRounds());
        assertEquals(Refusal.ABILITY, TacticalRules.draftRefusal(at(1, used), use("brute"), jab), "the same activation");
        assertEquals(Refusal.ABILITY, TacticalRules.draftRefusal(at(2, used), use("brute"), jab));
        assertNull(TacticalRules.draftRefusal(at(3, used), use("brute"), jab));
        assertEquals(Refusal.ABILITY, TacticalRules.draftRefusal(at(6, used), use("brute"), gust));
        assertNull(TacticalRules.draftRefusal(at(7, used), use("brute"), gust));
        // A draft never becomes one of the player's frozen abilities, even under its reserved ID.
        assertEquals(Refusal.ABILITY, TacticalRules.refusal(at(7, used), new TacticalIntent.UseAbility(DraftPreview.CANDIDATE_ID,
                List.of("brute"))));
    }

    /**
     * Bounded high power: the most complex draft a ceiling profile allows (four operations of 4d20, 16 dice) costs its six
     * points and 60 Focus and draws exactly its 16 dice, the most a frozen ability may, inside the operation's budget.
     */
    @Test
    void theLargestHighPowerDraftStaysInsideTheDrawBudget() {
        var ceiling = new CreativeProfile("ceiling", Power.HIGH, CombatAbility.MAX_OPERATIONS, 9, 4, CombatAbility.MAX_DICE,
                20, EnumSet.allOf(Kind.class), Set.of(), 0, true, CreativeProfile.COMPLEXITY_MAX, 6, 3, 0, Set.of());
        Step heavy = new Step(Kind.DAMAGE, 4, 20, null, 0, 0, null, false);
        var state = state(EncounterPhase.AWAITING_ACTION, 1, 6, 60, List.of(), Map.of(), Map.of("actionPoints", 6));
        var ready = assertInstanceOf(DraftPreview.Ready.class, DraftPreview.preview(ceiling, new AbilityDraft("Storm",
                Targets.HOSTILE, 1, 0, List.of(heavy, heavy, heavy, heavy)), state.effects(),
                new DraftPreview.Caster(state.player().focus(), Set.of())));
        assertTrue(ready.unblocked());
        var draws = new ArrayList<Integer>();
        var plan = CombatScheduler.useDraft(state, use("brute"), ready.candidate(), (ordinal, sides) -> {
            draws.add(ordinal);
            return 1;
        });
        assertEquals(CombatAbility.MAX_DICE, draws.size());
        assertTrue(draws.stream().allMatch(ordinal -> ordinal < org.rpgengine.rules.DeterministicDraw.MAX_DRAWS));
        assertEquals(-60, plan.playerFocusDelta());
        assertEquals(0, plan.last().actionPoints());
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    /** @return the candidate of a draft the profile admits and nothing blocks at this state */
    private static CombatAbility candidate(AbilityDraft draft, EncounterState state) {
        var ready = assertInstanceOf(DraftPreview.Ready.class, DraftPreview.preview(PROFILE, draft, state.effects(),
                new DraftPreview.Caster(state.player().focus(), Set.of())));
        assertTrue(ready.unblocked());
        return ready.candidate();
    }

    private static TacticalIntent.UseDraft use(String... targets) {
        return new TacticalIntent.UseDraft(List.of(targets));
    }

    private static TacticalPlan.ParticipantUpdate update(TacticalPlan plan, String id) {
        return plan.participants().stream().filter(update -> update.participantId().equals(id)).findFirst().orElseThrow();
    }

    /** The player's decision in this round with these stored draft uses. */
    private static EncounterState at(int round, Map<String, CombatAbility.Use> uses) {
        return state(EncounterPhase.AWAITING_ACTION, round, 3, 10, List.of(), uses);
    }

    /** The player's decision of round {@code round} with these points, this Focus and these held effects. */
    private static EncounterState fight(int round, int points, int focus, List<CombatEffects.Active> held) {
        return state(EncounterPhase.AWAITING_ACTION, round, points, focus, held, Map.of());
    }

    private static EncounterState state(EncounterPhase phase, int round, int points, int focus,
            List<CombatEffects.Active> held, Map<String, CombatAbility.Use> uses) {
        return state(phase, round, points, focus, held, uses, Map.of());
    }

    /**
     * The player in {@code ring} beside a hostile {@code brute} (30 Health, no armor) and an allied {@code porter}, with a
     * hostile {@code sniper} two zones away; winded and stunned are frozen.
     */
    private static EncounterState state(EncounterPhase phase, int round, int points, int focus,
            List<CombatEffects.Active> held, Map<String, CombatAbility.Use> uses, Map<String, Integer> overrides) {
        var profile = CombatProfile.resolve("pack", Map.of("force", "a", "finesse", "b", "insight", "c", "resolve", "d"), "hp",
                "fp", overrides);
        var roles = Map.of("force", 1, "finesse", 1, "insight", 1, "resolve", 1);
        var sheet = new EncounterState.Sheet(10, 10, 0, roles, List.of());
        var participants = List.of(
                new EncounterState.Combatant(EncounterTemplate.PLAYER, 0, PLAYER, EncounterTemplate.Side.PLAYER, "ring",
                        EncounterState.Status.ACTIVE, 1, 2, 20, 0, 1, 0, 20, 20, false, sheet, focus, 99, held, Map.of(), uses),
                foe("brute", 1, "ring", sheet),
                foe("sniper", 2, "far", sheet),
                new EncounterState.Combatant("porter", 3, null, EncounterTemplate.Side.ALLY, "ring", EncounterState.Status.ACTIVE,
                        1, 2, 17, 3, 1, 0, 30, 30, false, sheet, 0, 0, List.of(), Map.of(), Map.of()));
        var effects = new TreeMap<String, CombatEffect>(Map.of("winded", WINDED, "stunned", STUNNED));
        boolean acting = phase == EncounterPhase.AWAITING_ACTION || phase == EncounterPhase.RESOLVING;
        return new EncounterState(ID, 5, phase, round, acting ? 0 : null, acting ? points : null, 0, List.of(), profile, space(),
                Set.of(), List.of(), OpeningPolicy.INITIATIVE, null, "NONE", participants, List.of(), effects,
                held.size(), List.of());
    }

    private static EncounterState.Combatant foe(String id, int ordinal, String zone, EncounterState.Sheet sheet) {
        return new EncounterState.Combatant(id, ordinal, null, EncounterTemplate.Side.HOSTILE, zone, EncounterState.Status.ACTIVE,
                1, 2, 20 - ordinal, ordinal, 1, 0, 30, 30, false, sheet, 0, 0, List.of(), Map.of(), Map.of());
    }

    /** ring - mid - far, every edge open both ways. */
    private static ZoneGraph space() {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        for (String id : List.of("ring", "mid", "far")) {
            zones.put(id, new ZoneGraph.Zone(id, Set.of(), ZoneGraph.Cover.NONE, false, 0));
        }
        var edges = new ArrayList<ZoneGraph.Edge>();
        for (String[] pair : new String[][] {{"ring", "mid"}, {"mid", "far"}}) {
            edges.add(new ZoneGraph.Edge(pair[0], pair[1], false, false));
            edges.add(new ZoneGraph.Edge(pair[1], pair[0], false, false));
        }
        return new ZoneGraph(zones, edges);
    }
}
