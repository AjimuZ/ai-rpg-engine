package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.DeterministicDraw;
import org.rpgengine.rules.ZoneGraph;

/**
 * Pure acceptance of the P11.5 terminal predicates (docs/11 section 13, QA-13): {@link EncounterOutcome#decide} reads only
 * mechanical state and applies the documented priority, {@link CombatScheduler} moves to {@code OUTCOME_PENDING} from each
 * place the state machine allows and from each source of a decisive change, and seeded fights over the real fixture reach
 * a terminal state with their invariants intact. The PostgreSQL path is {@code OutcomeCommandsTest}.
 */
class EncounterOutcomeTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final CombatAttack BLADE = new CombatAttack("blade", "force", 2, 1, 6, "force", 0, 0, 0,
            CombatAttack.Defense.GUARD, true, null);
    private static final CombatAttack CLAW = new CombatAttack("claw", "force", 1, 1, 6, "force", 0, 0, 0,
            CombatAttack.Defense.GUARD, false, null);
    private static final CombatReaction RIPOSTE = new CombatReaction("riposte", CombatReaction.Trigger.HIT, 0, 0, false,
            CombatReaction.Attacks.ANY, CombatReaction.Kind.COUNTER, 0, null, null, false);
    private static final CombatReaction SNAP = new CombatReaction("snap", CombatReaction.Trigger.LEAVES_ZONE, 0, 0, false,
            CombatReaction.Attacks.ANY, CombatReaction.Kind.COUNTER, 0, null, null, false);
    private static final EncounterObjective WIN = new EncounterObjective("win", EncounterObjective.Kind.DEFEAT_HOSTILES, null, null, 0);
    private static final EncounterObjective RUN = new EncounterObjective("run", EncounterObjective.Kind.ESCAPE, null, null, 0);
    private static final EncounterObjective REACH = new EncounterObjective("reach", EncounterObjective.Kind.REACH_ZONE, "mid", null, 0);
    private static final EncounterObjective HOLD = new EncounterObjective("hold", EncounterObjective.Kind.SURVIVE, null, null, 2);
    private static final EncounterObjective KEEP = new EncounterObjective("keep", EncounterObjective.Kind.PROTECT, null, "ally", 0);

    /** Construct a stateless fixture. */
    EncounterOutcomeTest() {
    }

    // ---- the predicates ------------------------------------------------------------------------------------------

    /** While every side stands and nobody has left, no verdict exists, whatever the objectives. */
    @Test
    void aFightGoesOnWhileNoPredicateHolds() {
        var state = fight(List.of(WIN, RUN, REACH, HOLD, KEEP), player(), foe("foe"), ally("ally"));
        assertNull(EncounterOutcome.decide(state));
    }

    /**
     * No hostile still active ends the fight, however each one left it, and allies do not count. It reads as a surrender
     * when any of them surrendered, because settlement must not treat a spared opponent as a fallen one, and otherwise as a
     * victory.
     */
    @Test
    void noActiveOpponentIsAVictoryOrTheirSurrender() {
        for (var status : List.of(EncounterState.Status.DEFEATED, EncounterState.Status.ESCAPED, EncounterState.Status.SURRENDERED)) {
            var verdict = EncounterOutcome.decide(fight(List.of(WIN), player(), foe("foe").status(status), ally("ally")));
            assertEquals(status == EncounterState.Status.SURRENDERED ? EncounterOutcome.Kind.HOSTILE_SURRENDER
                    : EncounterOutcome.Kind.VICTORY, verdict.kind(), status.name());
            assertNull(verdict.objective());
            assertEquals(Map.of("win", EncounterObjective.Status.MET), verdict.objectives());
        }
        assertEquals(EncounterOutcome.Kind.HOSTILE_SURRENDER, EncounterOutcome.decide(fight(List.of(WIN), player(),
                foe("foe").status(EncounterState.Status.DEFEATED), foe("other").status(EncounterState.Status.SURRENDERED))).kind(),
                "one spared opponent among the fallen is still a surrender");
        assertEquals(EncounterOutcome.Kind.VICTORY, EncounterOutcome.decide(fight(List.of(WIN), player(),
                foe("foe").status(EncounterState.Status.DEFEATED), ally("ally").status(EncounterState.Status.SURRENDERED))).kind(),
                "an ally's standing says nothing about the opposition");
        assertNull(EncounterOutcome.decide(fight(List.of(WIN), player(), foe("foe").status(EncounterState.Status.SURRENDERED),
                foe("other"))), "a surrender ends nothing while another opponent stands");
        assertNull(EncounterOutcome.decide(fight(List.of(WIN), player(), foe("foe").status(EncounterState.Status.DEFEATED), foe("other"))),
                "one opponent still stands");
        assertEquals(EncounterOutcome.Kind.VICTORY,
                EncounterOutcome.decide(fight(List.of(), player(), foe("foe").status(EncounterState.Status.DEFEATED))).kind(),
                "the structural predicate needs no declared objective");
    }

    /** A defeated player loses the fight and fails every objective. */
    @Test
    void aDefeatedPlayerIsADefeat() {
        var verdict = EncounterOutcome.decide(fight(List.of(WIN, RUN, REACH, HOLD, KEEP),
                player().status(EncounterState.Status.DEFEATED), foe("foe"), ally("ally")));
        assertEquals(EncounterOutcome.Kind.DEFEAT, verdict.kind());
        assertNull(verdict.objective());
        assertEquals(5, verdict.objectives().size());
        assertTrue(verdict.objectives().values().stream().allMatch(status -> status == EncounterObjective.Status.FAILED));
    }

    /** Standing in an escape zone is an escape, with or without an escape objective; a player marked escaped likewise. */
    @Test
    void anEscapeZoneIsAnEscape() {
        assertEquals(EncounterOutcome.Kind.ESCAPE, EncounterOutcome.decide(escapes(fight(List.of(RUN), player().zone("far"), foe("foe")))).kind());
        assertEquals(EncounterOutcome.Kind.ESCAPE, EncounterOutcome.decide(escapes(fight(List.of(WIN), player().zone("far"), foe("foe")))).kind());
        assertEquals(EncounterOutcome.Kind.ESCAPE,
                EncounterOutcome.decide(fight(List.of(WIN), player().status(EncounterState.Status.ESCAPED), foe("foe"))).kind());
        assertNull(EncounterOutcome.decide(fight(List.of(RUN), player().zone("far"), foe("foe"))), "that zone is not an escape zone here");
        assertNull(EncounterOutcome.decide(escapes(fight(List.of(RUN), player(), foe("foe").zone("far")))),
                "an opponent in the zone is nobody's escape");
    }

    /** A reach-zone objective is met only by the player, only in its own zone. */
    @Test
    void reachingTheZoneMeetsItsObjective() {
        var verdict = EncounterOutcome.decide(fight(List.of(REACH), player().zone("mid"), foe("foe")));
        assertEquals(EncounterOutcome.Kind.OBJECTIVE, verdict.kind());
        assertEquals("reach", verdict.objective());
        assertNull(EncounterOutcome.decide(fight(List.of(REACH), player(), foe("foe").zone("mid"), ally("ally").zone("mid"))));
    }

    /** The survive objective counts completed rounds: a round is complete at its end, not while it runs. */
    @Test
    void survivingCountsCompletedRounds() {
        var base = fight(List.of(HOLD), player(), foe("foe"));
        assertNull(EncounterOutcome.decide(at(base, EncounterPhase.AWAITING_ACTION, 2, 0, 3)), "round two is still running");
        assertNull(EncounterOutcome.decide(at(base, EncounterPhase.ROUND_END, 1, null, null)));
        assertNull(EncounterOutcome.decide(at(base, EncounterPhase.ROUND_OPEN, 2, null, null)));
        assertEquals("hold", EncounterOutcome.decide(at(base, EncounterPhase.ROUND_END, 2, null, null)).objective());
        assertEquals("hold", EncounterOutcome.decide(at(base, EncounterPhase.ROUND_OPEN, 3, null, null)).objective());
        assertEquals("hold", EncounterOutcome.decide(at(base, EncounterPhase.AWAITING_ACTION, 3, 0, 3)).objective());
        assertEquals("hold", EncounterOutcome.decide(at(base, EncounterPhase.OUTCOME_PENDING, 2, null, null)).objective(),
                "a stored outcome without a cursor was reached at the round end");
        assertNull(EncounterOutcome.decide(at(base, EncounterPhase.OUTCOME_PENDING, 2, 0, 1)),
                "a stored outcome with a cursor was reached inside the round");
        assertEquals(0, EncounterOutcome.completedRounds(at(base, EncounterPhase.ENTERING, 0, null, null)));
    }

    /** Protection fails when the ally falls and is met once no opponent remains; its failure alone ends nothing. */
    @Test
    void protectionFailsWithTheAllyAndEndsNothingAlone() {
        var fallen = fight(List.of(KEEP), player(), foe("foe"), ally("ally").status(EncounterState.Status.DEFEATED));
        assertNull(EncounterOutcome.decide(fallen), "the fight can still be won, lost or left");
        assertEquals(EncounterObjective.Status.FAILED, KEEP.evaluate(EncounterOutcome.progress(fallen)));

        var won = EncounterOutcome.decide(fight(List.of(KEEP), player(), foe("foe").status(EncounterState.Status.DEFEATED), ally("ally")));
        assertEquals(EncounterOutcome.Kind.OBJECTIVE, won.kind());
        assertEquals("keep", won.objective());

        var bitter = EncounterOutcome.decide(fight(List.of(KEEP), player(), foe("foe").status(EncounterState.Status.DEFEATED),
                ally("ally").status(EncounterState.Status.DEFEATED)));
        assertEquals(EncounterOutcome.Kind.VICTORY, bitter.kind(), "a lost ally does not undo the victory");
        assertEquals(Map.of("keep", EncounterObjective.Status.FAILED), bitter.objectives(), "and settlement can read the loss");
    }

    /**
     * Ties go by the documented priority: a fallen player over everything, then an explicit objective in declared order, then
     * no active opponent, then escape. A mutual fall is a defeat: an incapacitated protagonist meets nothing.
     */
    @Test
    void ties() {
        var downedOpponent = foe("foe").status(EncounterState.Status.DEFEATED);
        assertEquals(EncounterOutcome.Kind.DEFEAT, EncounterOutcome.decide(escapes(fight(List.of(WIN, REACH),
                player().zone("far").status(EncounterState.Status.DEFEATED), downedOpponent))).kind(), "a mutual fall");

        var first = at(fight(List.of(REACH, HOLD), player().zone("mid"), foe("foe")), EncounterPhase.ROUND_END, 2, null, null);
        assertEquals("reach", EncounterOutcome.decide(first).objective());
        var second = at(fight(List.of(HOLD, REACH), player().zone("mid"), foe("foe")), EncounterPhase.ROUND_END, 2, null, null);
        assertEquals("hold", EncounterOutcome.decide(second).objective(), "declared order decides between objectives");

        var objective = EncounterOutcome.decide(fight(List.of(WIN, REACH), player().zone("mid"), downedOpponent));
        assertEquals(EncounterOutcome.Kind.OBJECTIVE, objective.kind(), "an explicit objective outranks no active opponent");
        assertEquals(EncounterObjective.Status.MET, objective.objectives().get("win"));

        var victory = EncounterOutcome.decide(escapes(fight(List.of(WIN, RUN), player().zone("far"), downedOpponent)));
        assertEquals(EncounterOutcome.Kind.VICTORY, victory.kind(), "no active opponent outranks an escape");
        assertEquals(EncounterObjective.Status.MET, victory.objectives().get("run"));
        assertEquals(EncounterObjective.Status.MET, victory.objectives().get("win"));

        var objectiveOverEscape = EncounterOutcome.decide(escapes(fight(List.of(new EncounterObjective("out",
                EncounterObjective.Kind.REACH_ZONE, "far", null, 0)), player().zone("far"), foe("foe"))));
        assertEquals(EncounterOutcome.Kind.OBJECTIVE, objectiveOverEscape.kind(), "an explicit objective outranks an escape");
    }

    /**
     * A player who surrendered has that verdict and fails every objective, even with no opponent left: giving up the fight
     * meets nothing the fight asked for. Only a defeat outranks it, and only the player's standing decides it.
     */
    @Test
    void aSurrenderedPlayerHasItsOwnVerdict() {
        var verdict = EncounterOutcome.decide(fight(List.of(WIN, RUN, REACH, HOLD, KEEP), player().status(EncounterState.Status.SURRENDERED),
                foe("foe").status(EncounterState.Status.DEFEATED), ally("ally")));
        assertEquals(EncounterOutcome.Kind.PLAYER_SURRENDER, verdict.kind());
        assertNull(verdict.objective());
        assertEquals(5, verdict.objectives().size());
        assertTrue(verdict.objectives().values().stream().allMatch(status -> status == EncounterObjective.Status.FAILED));
        assertEquals(EncounterOutcome.Kind.PLAYER_SURRENDER, EncounterOutcome.decide(escapes(fight(List.of(REACH),
                player().zone("far").status(EncounterState.Status.SURRENDERED), foe("foe")))).kind(), "an escape zone changes nothing");
    }

    /**
     * An accepted truce is the last predicate that can end a fight: everything else already decided outranks it, and no
     * objective is met or failed by it. A verdict names the terms, and only an agreement does.
     */
    @Test
    void anAgreementComesAfterEveryOtherPredicate() {
        var truce = new Negotiation.Agreement("offer-1", "open-road", "foe");
        var verdict = EncounterOutcome.decide(agreed(fight(List.of(WIN, HOLD), player(), foe("foe")), truce));
        assertEquals(EncounterOutcome.Kind.AGREEMENT, verdict.kind());
        assertEquals("open-road", verdict.terms());
        assertEquals("open-road", verdict.publicForm().get("termsId"));
        assertEquals(EncounterObjective.Status.PENDING, verdict.objectives().get("win"));

        assertEquals(EncounterOutcome.Kind.DEFEAT, EncounterOutcome.decide(agreed(fight(List.of(WIN),
                player().status(EncounterState.Status.DEFEATED), foe("foe")), truce)).kind());
        assertEquals(EncounterOutcome.Kind.OBJECTIVE, EncounterOutcome.decide(agreed(fight(List.of(REACH), player().zone("mid"), foe("foe")), truce)).kind());
        assertEquals(EncounterOutcome.Kind.VICTORY, EncounterOutcome.decide(agreed(fight(List.of(WIN), player(),
                foe("foe").status(EncounterState.Status.DEFEATED)), truce)).kind());
        assertEquals(EncounterOutcome.Kind.ESCAPE, EncounterOutcome.decide(agreed(escapes(fight(List.of(WIN), player().zone("far"), foe("foe"))), truce)).kind());

        assertThrows(IllegalArgumentException.class, () -> new EncounterOutcome(EncounterOutcome.Kind.AGREEMENT, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new EncounterOutcome(EncounterOutcome.Kind.VICTORY, null, "open-road", Map.of()));
        assertFalse(EncounterOutcome.decide(fight(List.of(), player(), foe("foe").status(EncounterState.Status.DEFEATED)))
                .publicForm().containsKey("termsId"));
    }

    /** An offer waiting for its answer is not an agreement, whatever its terms: the fight has no verdict while it stands. */
    @Test
    void anOpenOfferDecidesNothing() {
        var state = fight(List.of(WIN), player(), foe("foe"));
        var open = new Negotiation.Offer("offer-1", "parley", "foe", new Negotiation.Terms("open-road", Negotiation.Terms.Kind.TRUCE, 0),
                Negotiation.Response.OFFERED);
        assertNull(EncounterOutcome.decide(new EncounterState(state.id(), state.rowVersion(), state.phase(), state.round(), state.cursor(),
                state.actionPoints(), state.speechUsed(), state.passages(), state.profile(), state.frozenSpace(), state.escapeZones(),
                state.actions(), state.opening(), state.openingTarget(), state.openingStatus(), state.participants(), state.ammunition(),
                state.effects(), state.effectsApplied(), state.objectives(), open, null)));
    }

    /** A verdict is well formed and its public form states exactly what it holds, in declared order. */
    @Test
    void theVerdictHasOnePublicForm() {
        var verdict = EncounterOutcome.decide(fight(List.of(WIN, REACH), player().zone("mid"), foe("foe")));
        var form = verdict.publicForm();
        assertEquals("OBJECTIVE", form.get("outcome"));
        assertEquals("reach", form.get("objectiveId"));
        assertEquals(List.of(Map.of("objectiveId", "win", "status", "pending"), Map.of("objectiveId", "reach", "status", "met")),
                form.get("objectives"));
        assertFalse(EncounterOutcome.decide(fight(List.of(), player(), foe("foe").status(EncounterState.Status.DEFEATED)))
                .publicForm().containsKey("objectiveId"));
        assertThrows(IllegalArgumentException.class, () -> new EncounterOutcome(EncounterOutcome.Kind.OBJECTIVE, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new EncounterOutcome(EncounterOutcome.Kind.VICTORY, "win", Map.of()));
    }

    /** A frozen objective reads back exactly from its stored form, whatever its kind. */
    @Test
    void objectivesReadBackFromTheirFrozenForm() {
        for (var objective : List.of(WIN, RUN, REACH, HOLD, KEEP)) {
            assertEquals(objective, EncounterObjective.fromCanonical(objective.canonicalForm()));
        }
    }

    // ---- the scheduler -------------------------------------------------------------------------------------------

    /** The player's strike on the last opponent ends the fight inside the action: no further step, time or option. */
    @Test
    void theLastOpponentFallingEndsTheFightInsideTheAction() {
        var state = fight(List.of(WIN), player().attacks(BLADE), foe("foe").guard(0).health(3, 9));
        var plan = CombatScheduler.apply(state, new TacticalIntent.Strike("blade", "foe"), script(10, 10, 6, 4));
        assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.OUTCOME_PENDING), phases(plan));
        var outcome = plan.notes().getLast();
        assertEquals("ENCOUNTER_OUTCOME_PENDING", outcome.type());
        assertFalse(outcome.internal());
        assertFalse(outcome.byPlayer());
        assertEquals("VICTORY", outcome.payload().get("outcome"));
        assertEquals(ID.toString(), outcome.payload().get("encounterId"));
        assertEquals(1, outcome.payload().get("round"));
        assertEquals(0L, plan.elapsedSeconds());
        assertEquals("OUTCOME_PENDING", plan.summary().get("phase"));
        assertEquals("VICTORY", ((Map<?, ?>) plan.summary().get("outcome")).get("outcome"));
        assertFalse(plan.summary().containsKey("options"));
        assertFalse(plan.summary().containsKey("acting"));
        assertEquals(0, plan.last().cursor(), "the row keeps where inside the round the fight stopped");

        var stored = after(state, plan);
        assertEquals(EncounterOutcome.Kind.VICTORY, EncounterOutcome.decide(stored).kind());
        assertFalse(stored.playerDecision());
        for (var intent : List.of(new TacticalIntent.EndActivation(), new TacticalIntent.Guard(), new TacticalIntent.MoveZone("mid"),
                new TacticalIntent.Strike("blade", "foe"), new TacticalIntent.RollInitiative(), new TacticalIntent.Speech("foe", "Well?"))) {
            assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(stored, intent), intent.toString());
        }

        var remaining = fight(List.of(WIN), player().attacks(BLADE), foe("foe").guard(0).health(3, 9), foe("other"));
        var goesOn = CombatScheduler.apply(remaining, new TacticalIntent.Strike("blade", "foe"), script(10, 10, 6, 4));
        assertEquals(EncounterPhase.AWAITING_ACTION, goesOn.last().phase(), "another opponent still stands");
    }

    /** An opponent felling the player ends the fight inside the opponent's action: its Guard and activation end never run. */
    @Test
    void anOpponentFellingThePlayerEndsTheFightInItsAction() {
        var state = fight(List.of(WIN), player().attacks(BLADE).guard(0).health(1, 24), foe("foe").attacks(CLAW));
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script(10, 10, 6));
        var outcome = plan.notes().getLast();
        assertEquals("DEFEAT", outcome.payload().get("outcome"));
        assertEquals(List.of(EncounterPhase.ACTOR_END, EncounterPhase.ACTOR_START, EncounterPhase.AWAITING_ACTION,
                EncounterPhase.RESOLVING, EncounterPhase.OUTCOME_PENDING), phases(plan));
        assertEquals(-1, plan.playerHealthDelta());
        assertEquals(0L, plan.elapsedSeconds());
        assertEquals(1, plan.notes().stream().filter(note -> note.type().equals("ACTIVATION_ENDED")).count(), "only the player's");
        assertTrue(plan.notes().stream().noneMatch(note -> note.type().equals("GUARD_RAISED") || note.type().equals("ROUND_ENDED")));
    }

    /** The player's predeclared counter felling its attacker in the attacker's own activation wins the fight there. */
    @Test
    void aReactionCanDecideTheFight() {
        var state = fight(List.of(WIN), player().attacks(BLADE).react(RIPOSTE), foe("foe").guard(0).health(1, 9).attacks(CLAW));
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script(10, 10, 1, 10, 10, 1, 1));
        assertEquals("VICTORY", plan.notes().getLast().payload().get("outcome"));
        assertEquals(EncounterPhase.OUTCOME_PENDING, plan.last().phase());
        assertEquals(EncounterPhase.RESOLVING, plan.steps().get(plan.steps().size() - 2).phase());
    }

    /** A declared-action tick felling the last opponent as it starts to walk decides inside that move: it never arrives. */
    @Test
    void anOpponentFellingItselfOnTheMoveEndsTheFightThere() {
        var burn = tick("burn", CombatEffect.Phase.ACTION_DECLARED, 5);
        var state = library(fight(List.of(WIN), player(), foe("foe").zone("mid").health(3, 9).attacks(CLAW).holding(active(burn, 1))), burn);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals("VICTORY", plan.notes().getLast().payload().get("outcome"));
        assertEquals(EncounterPhase.RESOLVING, plan.steps().get(plan.steps().size() - 2).phase());
        assertEquals(0L, plan.elapsedSeconds());
        assertEquals("mid", after(state, plan).participant("foe").zone());
    }

    /** The same tick on the Guard action of an opponent that cannot move or reach anyone decides inside that action. */
    @Test
    void anOpponentFellingItselfOnItsGuardEndsTheFightThere() {
        var burn = tick("burn", CombatEffect.Phase.ACTION_DECLARED, 5);
        var rooted = new CombatEffect("rooted", "rooted", CombatEffect.Stacking.STRONGEST, 1, CombatEffect.Clock.PERSISTENT, 0, false, 0, 0, 0, 0, 0,
                Set.of(CombatEffect.Prevents.MOVEMENT), List.of(), false);
        var state = library(fight(List.of(WIN), player(), foe("foe").zone("far").health(3, 9).attacks(CLAW)
                .holding(active(burn, 1), active(rooted, 2))), burn, rooted);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals("VICTORY", plan.notes().getLast().payload().get("outcome"));
        assertEquals(EncounterPhase.RESOLVING, plan.steps().get(plan.steps().size() - 2).phase());
        assertEquals(0L, plan.elapsedSeconds(), "the round did not run on to its end");
    }

    /** A tick at an activation start decides at that start, before any decision or action. */
    @Test
    void aStartTickDecidesAtTheStart() {
        var poison = tick("poison", CombatEffect.Phase.ACTIVATION_START, 5);
        var state = library(fight(List.of(WIN), player(), foe("foe").health(3, 9).attacks(CLAW).holding(active(poison, 1))), poison);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals("VICTORY", plan.notes().getLast().payload().get("outcome"));
        assertEquals(List.of(EncounterPhase.ACTOR_END, EncounterPhase.ACTOR_START, EncounterPhase.OUTCOME_PENDING), phases(plan));
    }

    /**
     * A fight decided at an activation's end must still pass through the next activation start, the only arrow on from there.
     * Nobody starts: no points, no start trigger, no announcement, and the ally's poison stays unfired.
     */
    @Test
    void aFightDecidedAtAnActivationEndStartsNobody() {
        var burn = tick("burn", CombatEffect.Phase.ACTIVATION_END, 5);
        var poison = tick("poison", CombatEffect.Phase.ACTIVATION_START, 5);
        var state = library(fight(List.of(WIN), player(), foe("foe").health(3, 9).holding(active(burn, 1)),
                ally("ally").health(4, 9).holding(active(poison, 2))), burn, poison);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals("VICTORY", plan.notes().getLast().payload().get("outcome"));
        var steps = plan.steps();
        assertEquals(EncounterPhase.OUTCOME_PENDING, steps.getLast().phase());
        var start = steps.get(steps.size() - 2);
        assertEquals(EncounterPhase.ACTOR_START, start.phase());
        assertEquals(2, start.cursor());
        assertEquals(0, start.actionPoints());
        assertEquals(2, plan.notes().stream().filter(note -> note.type().equals("ACTIVATION_STARTED")).count() + 1,
                "only the opponent started");
        assertEquals(4, after(state, plan).participant("ally").health());
    }

    /**
     * A fight decided at the round's last activation end closes the round: its time passes, but no round-end tick can turn
     * a won fight into a lost one.
     */
    @Test
    void aFightDecidedAtTheLastActivationSkipsTheRoundEndTicks() {
        var burn = tick("burn", CombatEffect.Phase.ACTIVATION_END, 5);
        var poison = tick("poison", CombatEffect.Phase.ROUND_END, 5);
        var state = library(fight(List.of(WIN), player().health(3, 24).holding(active(poison, 1)),
                foe("foe").health(3, 9).holding(active(burn, 2))), burn, poison);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals("VICTORY", plan.notes().getLast().payload().get("outcome"));
        assertEquals(8L, plan.elapsedSeconds());
        assertEquals(0, plan.playerHealthDelta());
        assertEquals(EncounterPhase.ROUND_END, plan.steps().get(plan.steps().size() - 2).phase());
        assertNull(plan.last().cursor());
    }

    /** A round-end tick is checked after it runs: it fells the player at the round end and no new round opens. */
    @Test
    void aRoundEndTickCanDecide() {
        var poison = tick("poison", CombatEffect.Phase.ROUND_END, 5);
        var state = library(fight(List.of(WIN), player().health(3, 24).holding(active(poison, 1)), foe("foe")), poison);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals("DEFEAT", plan.notes().getLast().payload().get("outcome"));
        assertEquals(8L, plan.elapsedSeconds());
        assertEquals(1, plan.last().round());
        assertTrue(plan.notes().stream().noneMatch(note -> note.type().equals("ROUND_STARTED")));
    }

    /** Opening ticks that fell every participant still end on a legal path: a fall on all sides is a defeat. */
    @Test
    void aRoundOpeningThatFellsEveryoneIsADefeat() {
        var blight = tick("blight", CombatEffect.Phase.ROUND_OPEN, 5);
        var state = library(fight(List.of(WIN), player().health(3, 24).holding(active(blight, 1)),
                foe("foe").health(3, 9).holding(active(blight, 2))), blight);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals("DEFEAT", plan.notes().getLast().payload().get("outcome"));
        assertEquals(2, plan.last().round());
        assertEquals(EncounterPhase.ACTOR_START, plan.steps().get(plan.steps().size() - 2).phase());
    }

    /** A survive objective is met at the end of its last round; one round earlier the next round opens. */
    @Test
    void aSurviveObjectiveEndsTheFightAtItsRoundEnd() {
        var state = fight(List.of(HOLD), player(), foe("foe"));
        var first = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals(EncounterPhase.AWAITING_ACTION, first.last().phase());
        var next = after(state, first);
        assertEquals(2, next.round());
        var second = CombatScheduler.apply(next, new TacticalIntent.EndActivation(), script());
        assertEquals("OBJECTIVE", second.notes().getLast().payload().get("outcome"));
        assertEquals("hold", second.notes().getLast().payload().get("objectiveId"));
        assertNull(second.last().cursor());
        assertEquals(8L, second.elapsedSeconds());
        assertEquals(EncounterOutcome.Kind.OBJECTIVE, EncounterOutcome.decide(after(next, second)).kind(),
                "the stored state gives the same verdict");
    }

    /** At the round cap a decided fight has its outcome; an undecided one still only pauses. */
    @Test
    void theRoundCapStaysAPause() {
        var capped = fight(List.of(WIN), Map.of("roundCap", 1), player(), foe("foe"));
        assertEquals(EncounterPhase.ROUND_END, CombatScheduler.apply(capped, new TacticalIntent.EndActivation(), script()).last().phase());
        var decided = fight(List.of(new EncounterObjective("hold", EncounterObjective.Kind.SURVIVE, null, null, 1)),
                Map.of("roundCap", 1), player(), foe("foe"));
        assertEquals(EncounterPhase.OUTCOME_PENDING, CombatScheduler.apply(decided, new TacticalIntent.EndActivation(), script()).last().phase());
    }

    /** Walking into an escape zone ends the fight there; the player's standing is left for settlement to record. */
    @Test
    void reachingAnEscapeZoneEscapes() {
        var state = escapes(fight(List.of(RUN), player().zone("mid"), foe("foe")));
        var plan = CombatScheduler.apply(state, new TacticalIntent.MoveZone("far"), script());
        assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.OUTCOME_PENDING), phases(plan));
        assertEquals("ESCAPE", plan.notes().getLast().payload().get("outcome"));
        var stored = after(state, plan);
        assertEquals("far", stored.player().zone());
        assertEquals(EncounterState.Status.ACTIVE, stored.player().status());
        assertEquals(EncounterOutcome.Kind.ESCAPE, EncounterOutcome.decide(stored).kind());
    }

    /** A blow that lands while leaving decides first: the player falls where it stood and never escapes. */
    @Test
    void aReactionCanStopAnEscape() {
        var state = escapes(fight(List.of(RUN), player().zone("mid").guard(0).health(1, 24), foe("foe").zone("mid").attacks(CLAW).react(SNAP)));
        var plan = CombatScheduler.apply(state, new TacticalIntent.MoveZone("far"), script(10, 10, 6));
        assertEquals("DEFEAT", plan.notes().getLast().payload().get("outcome"));
        assertEquals("mid", after(state, plan).player().zone());
    }

    /** A move that reaches an objective's zone meets it; a move elsewhere changes nothing. */
    @Test
    void reachingAnObjectivesZoneMeetsIt() {
        var state = fight(List.of(REACH), player(), foe("foe"));
        var plan = CombatScheduler.apply(state, new TacticalIntent.MoveZone("mid"), script());
        assertEquals("reach", plan.notes().getLast().payload().get("objectiveId"));
        var aside = CombatScheduler.apply(state, new TacticalIntent.MoveZone("shade"), script());
        assertEquals(EncounterPhase.AWAITING_ACTION, aside.last().phase());
    }

    /** The opening strike can decide at either slot, and each path follows the state machine. */
    @Test
    void anOpeningStrikeCanDecide() {
        var slot = opening(OpeningPolicy.INITIATIVE, player().attacks(BLADE), foe("foe").guard(0).health(1, 9));
        var atSlot = CombatScheduler.apply(slot, new TacticalIntent.RollInitiative(), script(9, 9, 1, 1, 10, 10, 6, 4));
        assertEquals("VICTORY", atSlot.notes().getLast().payload().get("outcome"));
        assertEquals(EncounterPhase.RESOLVING, atSlot.steps().get(atSlot.steps().size() - 2).phase());
        assertEquals("RESOLVED", atSlot.last().openingStatus());

        var first = opening(OpeningPolicy.FIRST, player().attacks(BLADE), foe("foe").guard(0).health(1, 9));
        var ahead = CombatScheduler.apply(first, new TacticalIntent.RollInitiative(), script(9, 9, 1, 1, 10, 10, 6, 4));
        assertEquals("VICTORY", ahead.notes().getLast().payload().get("outcome"));
        assertEquals(EncounterPhase.ACTOR_START, ahead.steps().get(ahead.steps().size() - 2).phase());
        assertEquals("RESOLVED", ahead.last().openingStatus());
    }

    /** A fight over before the opening strike's slot lapses the strike with its reason, so nothing stays pending. */
    @Test
    void anOpeningStrikeLapsesWhenTheFightEndsFirst() {
        var blight = tick("blight", CombatEffect.Phase.ROUND_OPEN, 5);
        var state = library(opening(OpeningPolicy.INITIATIVE, player().attacks(BLADE),
                foe("foe").health(3, 9).conditions("blight")), blight);
        var plan = CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), script(9, 9, 1, 1));
        assertEquals("VICTORY", plan.notes().getLast().payload().get("outcome"));
        assertEquals("RESOLVED", plan.last().openingStatus());
        var lapsed = plan.notes().stream().filter(note -> note.type().equals("OPENING_STRIKE_LAPSED")).findFirst().orElseThrow();
        assertEquals("ENCOUNTER_ENDED", lapsed.payload().get("reason"));
    }

    /**
     * Words decide nothing: speech claiming a victory or an escape and a declared social attempt leave the fight exactly as
     * it was, with no verdict, whatever they say (QA-13). {@link EncounterOutcome#decide} has no other input than the state.
     */
    @Test
    void wordsEstablishNoOutcome() throws Exception {
        var state = fixture("trail-ambush", 24);
        var rolled = after(state, CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), script(9, 9, 1, 1)));
        for (String claim : List.of("The raider is dead and the fight is won.", "I have escaped.", "You surrender and accept my terms.")) {
            var spoken = CombatScheduler.apply(rolled, new TacticalIntent.Speech("raider", claim), script());
            assertEquals(EncounterPhase.AWAITING_ACTION, spoken.last().phase(), claim);
            assertTrue(spoken.notes().stream().noneMatch(note -> note.type().equals("ENCOUNTER_OUTCOME_PENDING")), claim);
            assertNull(EncounterOutcome.decide(after(rolled, spoken)), claim);
        }
        var taunted = CombatScheduler.apply(rolled, new TacticalIntent.Interact("taunt", "raider"), script());
        assertEquals(EncounterPhase.AWAITING_ACTION, taunted.last().phase());
        assertNull(EncounterOutcome.decide(after(rolled, taunted)));
    }

    // ---- seeded fights -------------------------------------------------------------------------------------------

    /**
     * Two hundred seeded fights (seed 20261101) over real fixture templates, with objectives read from the frozen encounter
     * and intentions chosen at random among the legal ones. Each must reach {@code OUTCOME_PENDING} within the round cap, keep
     * every invariant at every step (Health in bounds, zero Health exactly when defeated, a defeated participant staying
     * defeated and starting no activation, points in the allowance, one arrow per step), state the verdict the stored state
     * gives, accept nothing afterwards and replay identically from its seed. Every kind of outcome must occur.
     */
    @Test
    void seededFightsReachATerminalStateAndKeepEveryInvariant() throws Exception {
        var random = new java.util.Random(20261101L);
        var kinds = new TreeMap<String, Integer>();
        var templates = List.of("yard-duel", "trail-ambush", "grove-watch", "toll-parley", "close-brawl");
        for (int fight = 0; fight < 200; fight++) {
            String template = templates.get(fight % templates.size());
            long seed = random.nextLong();
            var first = walk(template, seed);
            assertEquals(first, walk(template, seed), "the same seed replays the same fight");
            var outcome = first.getLast().notes().getLast();
            assertEquals("ENCOUNTER_OUTCOME_PENDING", outcome.type(), template + " seed " + seed);
            kinds.merge((String) outcome.payload().get("outcome"), 1, Integer::sum);
        }
        assertEquals(Set.of("VICTORY", "DEFEAT", "ESCAPE", "OBJECTIVE"), kinds.keySet(), kinds.toString());
    }

    /**
     * A hundred seeded fights (seed 20261102) over the {@code parley-ground} fixture, whose opponents have no attacks. On top
     * of the usual candidates, intentions are chosen at random among every social attempt with something declared, the
     * answers to the offer on the table and words claiming to accept it.
     * On top of every invariant of the other seeded fights, a surrender and an agreement must come only from the player's
     * explicit acceptance, a surrendered participant must stay out of the schedule, and an offer must stand only at a decision.
     * Every negotiated ending and the survive objective must occur, and each fight replays identically from its seed.
     */
    @Test
    void seededNegotiationsOnlyEndByExplicitAcceptance() throws Exception {
        var random = new java.util.Random(20261102L);
        var kinds = new TreeMap<String, Integer>();
        for (int fight = 0; fight < 100; fight++) {
            long seed = random.nextLong();
            var first = walk("parley-ground", seed);
            assertEquals(first, walk("parley-ground", seed), "the same seed replays the same fight");
            kinds.merge((String) first.getLast().notes().getLast().payload().get("outcome"), 1, Integer::sum);
        }
        assertEquals(Set.of("AGREEMENT", "PLAYER_SURRENDER", "HOSTILE_SURRENDER", "OBJECTIVE"), kinds.keySet(), kinds.toString());
    }

    /** One seeded fight to its outcome, checking the invariants at every step; returns its plans. */
    private static List<TacticalPlan> walk(String template, long seed) throws Exception {
        var random = new java.util.Random(seed);
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var state = fixture(template, 8 + random.nextInt(17));
        var plans = new ArrayList<TacticalPlan>();
        var defeated = new HashSet<String>();
        var surrendered = new HashSet<String>();
        for (int step = 0; step < 600 && state.phase() != EncounterPhase.OUTCOME_PENDING; step++) {
            assertNull(EncounterOutcome.decide(state), "an undecided fight has no verdict: " + state.phase());
            var intent = state.phase() == EncounterPhase.ENTERING ? new TacticalIntent.RollInitiative() : choose(state, random);
            UUID operation = new UUID(seed, step);
            var plan = CombatScheduler.apply(state, intent, (ordinal, sides) -> {
                int value = DeterministicDraw.value(bytes, operation, ordinal, sides);
                assertTrue(value >= 1 && value <= sides);
                return value;
            });
            plans.add(plan);
            var previous = state.phase();
            for (var row : plan.steps()) {
                assertTrue(previous == row.phase() || previous.successors().contains(row.phase()), previous + " -> " + row.phase());
                previous = row.phase();
                if (row.actionPoints() != null) {
                    assertTrue(row.actionPoints() >= 0 && row.actionPoints() <= state.profile().actionPoints());
                }
                assertTrue(row.openOffer() == null || row.phase() == EncounterPhase.AWAITING_ACTION, "an offer waits only at a decision");
            }
            boolean accepted = intent instanceof TacticalIntent.AnswerOffer answer && answer.accept();
            for (var note : plan.notes()) {
                if (note.type().equals("ACTIVATION_STARTED")) {
                    assertFalse(defeated.contains(note.payload().get("participantId")), "a defeated participant takes no activation");
                    assertFalse(surrendered.contains(note.payload().get("participantId")), "a surrendered participant takes no activation");
                }
                if (note.type().equals("PARTICIPANT_DEFEATED")) {
                    assertTrue(defeated.add((String) note.payload().get("participantId")), "a participant falls once");
                }
                if (note.type().equals("PARTICIPANT_SURRENDERED")) {
                    assertTrue(accepted, "only the player's acceptance makes a surrender");
                    assertTrue(surrendered.add((String) note.payload().get("participantId")), "a participant surrenders once");
                }
            }
            var offerBefore = state.openOffer();
            state = after(state, plan);
            assertTrue(state.agreement() == null || accepted, "only the player's acceptance makes an agreement");
            boolean spoken = intent instanceof TacticalIntent.Speech;
            assertTrue(state.openOffer() == null || spoken || intent instanceof TacticalIntent.Interact interact
                    && state.action(interact.action()).terms() != null, "terms reach the table only as a response");
            if (spoken) {
                assertEquals(offerBefore, state.openOffer(), "words neither accept nor withdraw terms");
            }
            for (var participant : state.participants()) {
                assertTrue(participant.health() >= 0 && participant.health() <= participant.healthMax());
                assertEquals(participant.health() == 0, participant.status() == EncounterState.Status.DEFEATED, participant.id());
                assertEquals(defeated.contains(participant.id()), participant.status() == EncounterState.Status.DEFEATED, participant.id());
                assertEquals(surrendered.contains(participant.id()), participant.status() == EncounterState.Status.SURRENDERED, participant.id());
                assertTrue(participant.status() == EncounterState.Status.ACTIVE || participant.effects().isEmpty() && !participant.guarding(),
                        "stance and effects end with leaving the fight");
            }
            if (state.phase() != EncounterPhase.OUTCOME_PENDING) {
                assertTrue(state.playerDecision(), "the schedule stops only at a decision or an outcome: " + state.phase());
            }
        }
        assertEquals(EncounterPhase.OUTCOME_PENDING, state.phase(), template + " seed " + seed + " round " + state.round());
        var verdict = EncounterOutcome.decide(state);
        assertNotNull(verdict);
        var note = plans.getLast().notes().getLast();
        verdict.publicForm().forEach((key, value) -> assertEquals(value, note.payload().get(key), key));
        for (var intent : List.of(new TacticalIntent.EndActivation(), new TacticalIntent.Guard(), new TacticalIntent.RollInitiative())) {
            assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(state, intent));
        }
        return plans;
    }

    /** A random legal intention of the player's decision, weighted toward fighting, with the end of the activation always legal. */
    private static TacticalIntent choose(EncounterState state, java.util.Random random) {
        var candidates = new ArrayList<TacticalIntent>();
        for (var zone : state.space().zones().keySet()) {
            candidates.add(new TacticalIntent.MoveZone(zone));
        }
        for (var participant : state.participants()) {
            for (var attack : state.player().sheet().attacks()) {
                for (int weight = 0; weight < 4; weight++) {
                    candidates.add(new TacticalIntent.Strike(attack.id(), participant.id()));
                }
            }
        }
        candidates.add(new TacticalIntent.Guard());
        candidates.add(new TacticalIntent.EndActivation());
        // Only a fight with something to negotiate adds candidates, so the other fixtures keep their seeded paths.
        for (var action : state.actions()) {
            if (action.check() != null || action.terms() != null) {
                // Two surrenders must come before any other ending for that ending to occur, so demanding one weighs more.
                int weight = action.terms() != null && action.terms().kind() == Negotiation.Terms.Kind.RECIPIENT_SURRENDERS ? 12 : 1;
                for (int copy = 0; copy < weight; copy++) {
                    state.participants().forEach(participant -> candidates.add(new TacticalIntent.Interact(action.id(), participant.id())));
                }
            }
        }
        if (state.openOffer() != null) {
            candidates.add(new TacticalIntent.AnswerOffer(state.openOffer().id(), true));
            candidates.add(new TacticalIntent.AnswerOffer(state.openOffer().id(), false));
            candidates.add(new TacticalIntent.Speech(state.openOffer().recipient(), "I accept."));
        }
        var legal = candidates.stream().filter(intent -> TacticalRules.refusal(state, intent) == null).toList();
        return legal.get(random.nextInt(legal.size()));
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    /** The entry state of a real fixture template, with its frozen objectives and the surveyor's worn items. */
    private static EncounterState fixture(String template, int health) throws Exception {
        // Words are fought with on the P11.4 variant's ground; the other fights keep the default pack they were written on.
        boolean talks = template.equals("parley-ground");
        var rules = EncounterPack.rules(talks ? EncounterPack.tactics() : EncounterPack.files());
        var facts = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", 2L, "might", 2L),
                new FrozenEncounter.Pool(health, Math.max(health, 24)), null, List.of("cloak", "sword"), Set.of(),
                Map.of("bladework", 2L));
        var frozen = FrozenEncounter.freeze(ID, rules.templates().get(template),
                rules.profiles().get(talks ? "sample:armory-tactics" : "sample:armory"), facts, null, rules.library());
        var list = new ArrayList<EncounterState.Combatant>();
        int ordinal = 0;
        for (var participant : frozen.participants()) {
            list.add(new EncounterState.Combatant(participant.id(), ordinal++, participant.actorId(), participant.side(),
                    participant.zone(), EncounterState.Status.ACTIVE, Math.toIntExact(participant.roles().get("finesse")),
                    Math.toIntExact(participant.initiativeModifier()), null, null, 0, 0,
                    Math.toIntExact(participant.health()), Math.toIntExact(participant.healthMax()), false, participant.sheet(),
                    Math.toIntExact(participant.focus()), Math.toIntExact(participant.focusMax()), List.of(),
                    participant.policies(), Map.of()));
        }
        return new EncounterState(frozen.id(), 0, EncounterPhase.ENTERING, 0, null, null, 0, List.of(), frozen.profile(),
                frozen.zones(), frozen.escapeZones(), frozen.actions(), frozen.opening(), null, "NONE", list, List.of(),
                frozen.effects(), 0, frozen.objectives());
    }

    /** Builder of one participant of a fight. */
    static final class Builder {
        private final String id;
        private final EncounterTemplate.Side side;
        private String zone = "ring";
        private int health = 24;
        private int healthMax = 24;
        private int guard = 10;
        private EncounterState.Status status = EncounterState.Status.ACTIVE;
        private List<CombatAttack> attacks = List.of();
        private List<CombatAbility> abilities = List.of();
        private final List<CombatReaction> reactions = new ArrayList<>();
        private final Map<String, CombatReaction.Policy> policies = new TreeMap<>();
        private List<CombatEffects.Active> effects = List.of();
        private List<String> conditions = List.of();

        Builder(String id, EncounterTemplate.Side side) {
            this.id = id;
            this.side = side;
        }

        Builder zone(String value) { zone = value; return this; }
        Builder health(int current, int maximum) { health = current; healthMax = maximum; return this; }
        Builder guard(int value) { guard = value; return this; }
        Builder status(EncounterState.Status value) { status = value; return this; }
        Builder attacks(CombatAttack... values) { attacks = List.of(values); return this; }
        Builder abilities(CombatAbility... values) { abilities = List.of(values); return this; }
        Builder react(CombatReaction reaction) { reactions.add(reaction); policies.put(reaction.id(), CombatReaction.Policy.FIRST); return this; }
        Builder holding(CombatEffects.Active... values) { effects = List.of(values); return this; }
        Builder conditions(String... values) { conditions = List.of(values); return this; }

        /** Build at a rank equal to its ordinal, or unranked before initiative. */
        EncounterState.Combatant build(int ordinal, boolean ranked, int budget) {
            var sheet = new EncounterState.Sheet(guard, 10, 0, Map.of("force", side == EncounterTemplate.Side.PLAYER ? 2 : 1,
                    "finesse", 1, "insight", 1, "resolve", 1), attacks, abilities, reactions, conditions);
            return new EncounterState.Combatant(id, ordinal, side == EncounterTemplate.Side.PLAYER ? PLAYER : null, side, zone, status, 1, 2,
                    ranked ? (Integer) (20 - ordinal) : null, ranked ? (Integer) ordinal : null, budget, 0,
                    status == EncounterState.Status.DEFEATED ? 0 : health, healthMax, false, sheet, 0, 0, effects, policies, Map.of());
        }
    }

    static Builder player() {
        return new Builder(EncounterTemplate.PLAYER, EncounterTemplate.Side.PLAYER);
    }

    static Builder foe(String id) {
        return new Builder(id, EncounterTemplate.Side.HOSTILE);
    }

    static Builder ally(String id) {
        return new Builder(id, EncounterTemplate.Side.ALLY);
    }

    /** A fight at the player's decision of round one, with these objectives; rank order is the argument order. */
    static EncounterState fight(List<EncounterObjective> objectives, Builder player, Builder... others) {
        return fight(objectives, Map.of(), player, others);
    }

    /** The same under profile overrides. */
    private static EncounterState fight(List<EncounterObjective> objectives, Map<String, Integer> overrides, Builder player, Builder... others) {
        var profile = profile(overrides);
        var list = new ArrayList<EncounterState.Combatant>();
        list.add(player.build(0, true, profile.reactionsPerRound()));
        for (int index = 0; index < others.length; index++) {
            list.add(others[index].build(index + 1, true, profile.reactionsPerRound()));
        }
        return new EncounterState(ID, 5, EncounterPhase.AWAITING_ACTION, 1, 0, profile.actionPoints(), 0, List.of(), profile, space(),
                Set.of(), List.of(), OpeningPolicy.INITIATIVE, null, "NONE", list, List.of(), Map.of(), 0, objectives);
    }

    /** A fight before initiative whose opening strike at the first opponent still waits under this policy. */
    private static EncounterState opening(OpeningPolicy policy, Builder player, Builder opponent) {
        var profile = profile(Map.of());
        return new EncounterState(ID, 5, EncounterPhase.ENTERING, 0, null, null, 0, List.of(), profile, space(), Set.of(), List.of(),
                policy, opponent.id, "PENDING", List.of(player.build(0, false, 0), opponent.build(1, false, 0)), List.of(), Map.of(), 0,
                List.of(WIN));
    }

    private static CombatProfile profile(Map<String, Integer> overrides) {
        return CombatProfile.resolve("pack", Map.of("force", "a", "finesse", "b", "insight", "c", "resolve", "d"), "hp", null, overrides);
    }

    /** The zones of these fights, in one line: ring - mid - far, and ring - shade. */
    private static ZoneGraph space() {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        for (String id : List.of("ring", "mid", "far", "shade")) {
            zones.put(id, new ZoneGraph.Zone(id, Set.of(), ZoneGraph.Cover.NONE, false, 0));
        }
        var edges = new ArrayList<ZoneGraph.Edge>();
        for (String[] pair : new String[][] {{"ring", "mid"}, {"mid", "far"}, {"ring", "shade"}}) {
            edges.add(new ZoneGraph.Edge(pair[0], pair[1], false, false));
            edges.add(new ZoneGraph.Edge(pair[1], pair[0], false, false));
        }
        return new ZoneGraph(zones, edges);
    }

    /** The same state with this truce stored. */
    private static EncounterState agreed(EncounterState s, Negotiation.Agreement truce) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(), s.passages(),
                s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives(), null, truce);
    }

    /** The same state where {@code far} is an escape zone. */
    private static EncounterState escapes(EncounterState s) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(), s.passages(),
                s.profile(), s.frozenSpace(), Set.of("far"), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives());
    }

    /** The same state at another point of the schedule. */
    private static EncounterState at(EncounterState s, EncounterPhase phase, int round, Integer cursor, Integer points) {
        return new EncounterState(s.id(), s.rowVersion(), phase, round, cursor, points, s.speechUsed(), s.passages(), s.profile(),
                s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives());
    }

    /** The state with these frozen effects. */
    static EncounterState library(EncounterState s, CombatEffect... effects) {
        var library = new TreeMap<String, CombatEffect>();
        for (CombatEffect effect : effects) {
            library.put(effect.id(), effect);
        }
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(), s.passages(),
                s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), library, s.participants().stream().mapToInt(p -> p.effects().size()).sum(), s.objectives());
    }

    /** A lasting effect dealing this damage at one phase. */
    static CombatEffect tick(String id, CombatEffect.Phase phase, int amount) {
        return new CombatEffect(id, id, CombatEffect.Stacking.STRONGEST, 1, CombatEffect.Clock.PERSISTENT, 0, false, 0, 0, 0, 0, 0, Set.of(),
                List.of(new CombatEffect.Tick(phase, amount, false)), false);
    }

    /** A sourceless instance of a definition. */
    static CombatEffects.Active active(CombatEffect effect, int sequence) {
        return new CombatEffects.Active("e" + sequence, effect.id(), null, sequence, 0, 1, 0, null, false);
    }

    /** What the writer stores after a plan. */
    static EncounterState after(EncounterState state, TacticalPlan plan) {
        var updates = new TreeMap<String, TacticalPlan.ParticipantUpdate>();
        plan.participants().forEach(update -> updates.put(update.participantId(), update));
        var list = new ArrayList<EncounterState.Combatant>();
        for (var p : state.participants()) {
            var u = updates.get(p.id());
            list.add(u == null ? p : new EncounterState.Combatant(p.id(), p.ordinal(), p.actorId(), p.side(), u.zone(), u.status(), p.finesse(),
                    p.initiativeModifier(), u.initiative(), u.rank(), u.reactions(), p.rowVersion() + 1,
                    u.health() == null ? p.health() + plan.playerHealthDelta() : u.health(), p.healthMax(), u.guarding(), p.sheet(),
                    u.focus() == null ? p.focus() + plan.playerFocusDelta() : u.focus(), p.focusMax(), u.effects(), u.policies(), u.uses()));
        }
        var last = plan.last();
        return new EncounterState(state.id(), state.rowVersion() + plan.steps().size(), last.phase(), last.round(), last.cursor(),
                last.actionPoints(), last.speechUsed(), last.passages(), state.profile(), state.frozenSpace(), state.escapeZones(), state.actions(),
                state.opening(), state.openingTarget(), last.openingStatus(), list, state.ammunition(), state.effects(), last.effectsApplied(),
                state.objectives(), last.openOffer(), last.agreement());
    }

    /** The phases a plan writes, in order. */
    static List<EncounterPhase> phases(TacticalPlan plan) {
        return plan.steps().stream().map(TacticalPlan.Step::phase).toList();
    }

    /** Draws in order from a fixed script; running out is a test failure, so a surplus draw cannot pass unseen. */
    static CombatScheduler.Dice script(int... values) {
        var queue = new ArrayDeque<Integer>();
        for (int value : values) {
            queue.add(value);
        }
        return (ordinal, sides) -> {
            if (queue.isEmpty()) {
                throw new AssertionError("unexpected draw " + ordinal);
            }
            return queue.poll();
        };
    }
}
