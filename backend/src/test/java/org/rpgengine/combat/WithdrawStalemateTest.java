package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * Pure acceptance of Withdraw and the round cap (P11.5, docs/11 sections 7 and 13, ADR 0035). A withdrawal crosses one legal
 * edge that gets strictly closer to an escape zone, for {@code withdrawCost} or the edge's Move price when that is higher, no
 * {@code leavesZone} reaction answers it, and only the terminal predicates turn the zone it reaches into an escape. At the end
 * of round {@code roundCap} an undecided fight follows its frozen disengagement policy, a stalemate by default or an escape,
 * after every other predicate and without changing any participant. The PostgreSQL path is {@code WithdrawCommandsTest}.
 */
class WithdrawStalemateTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final CombatAttack CLAW = new CombatAttack("claw", "force", 1, 1, 6, "force", 0, 0, 0,
            CombatAttack.Defense.GUARD, false, null);
    private static final CombatReaction SNAP = new CombatReaction("snap", CombatReaction.Trigger.LEAVES_ZONE, 0, 0, false,
            CombatReaction.Attacks.ANY, CombatReaction.Kind.COUNTER, 0, null, null, false);
    private static final EncounterObjective WIN = new EncounterObjective("win", EncounterObjective.Kind.DEFEAT_HOSTILES, null, null, 0);
    private static final EncounterObjective RUN = new EncounterObjective("run", EncounterObjective.Kind.ESCAPE, null, null, 0);

    /** Construct a stateless fixture. */
    WithdrawStalemateTest() {
    }

    // ---- Withdraw ------------------------------------------------------------------------------------------------

    /**
     * From the camp the ridge and the marsh lead toward the pass and the gully leads away; the pass itself is two edges away,
     * so it is no single move. The options list exactly the legal withdrawals with their price.
     */
    @Test
    void aWithdrawalCrossesOneLegalEdgeTowardAnEscapeZone() {
        var state = fight(Map.of(), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN), List.of(), EncounterOutcomeTest.player().zone("camp"),
                EncounterOutcomeTest.foe("foe").zone("gully"));
        assertNull(TacticalRules.refusal(state, new TacticalIntent.Withdraw("ridge")));
        assertNull(TacticalRules.refusal(state, new TacticalIntent.Withdraw("marsh")));
        assertEquals(TacticalRules.Refusal.ZONE, TacticalRules.refusal(state, new TacticalIntent.Withdraw("gully")), "away from the pass");
        assertEquals(TacticalRules.Refusal.ZONE, TacticalRules.refusal(state, new TacticalIntent.Withdraw("pass")), "not one edge away");
        assertEquals(TacticalRules.Refusal.ZONE, TacticalRules.refusal(state, new TacticalIntent.Withdraw("camp")), "the current zone");
        assertEquals(TacticalRules.Refusal.ZONE, TacticalRules.refusal(state, new TacticalIntent.Withdraw("nowhere")));
        assertNull(TacticalRules.refusal(state, new TacticalIntent.MoveZone("gully")), "a move may still go anywhere");
        assertEquals(List.of(Map.of("zone", "marsh", "cost", 2), Map.of("zone", "ridge", "cost", 2)),
                TacticalRules.options(state).get("withdraw"));

        var closed = withPassages(state, new EncounterAction.Passage("ridge", "pass", true));
        assertEquals(TacticalRules.Refusal.ZONE, TacticalRules.refusal(closed, new TacticalIntent.Withdraw("ridge")),
                "a closed passage makes the ridge the longer way round");
        assertNull(TacticalRules.refusal(closed, new TacticalIntent.Withdraw("marsh")));

        var nowhere = fight(Map.of(), Set.of(), Disengagement.STALEMATE, List.of(WIN), List.of(), EncounterOutcomeTest.player().zone("camp"),
                EncounterOutcomeTest.foe("foe").zone("gully"));
        for (String zone : List.of("ridge", "marsh", "gully")) {
            assertEquals(TacticalRules.Refusal.ZONE, TacticalRules.refusal(nowhere, new TacticalIntent.Withdraw(zone)),
                    "nothing to withdraw toward");
        }
        assertEquals(List.of(), TacticalRules.options(nowhere).get("withdraw"));
    }

    /** The price is {@code withdrawCost}, raised to the edge's Move price when that is higher, and must be available. */
    @Test
    void aWithdrawalCostsItsDeclaredPriceAndNeverLessThanTheEdge() {
        var normal = fight(Map.of(), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN), List.of(), EncounterOutcomeTest.player().zone("camp"),
                EncounterOutcomeTest.foe("foe").zone("gully"));
        assertEquals(2, TacticalRules.withdrawCost(normal, "ridge"));
        assertEquals(1, TacticalRules.moveCost(normal, "ridge"), "dearer than the move it makes");
        assertEquals(2, TacticalRules.withdrawCost(normal, "marsh"));
        var cheap = fight(Map.of("withdrawCost", 1), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN), List.of(),
                EncounterOutcomeTest.player().zone("camp"), EncounterOutcomeTest.foe("foe").zone("gully"));
        assertEquals(1, TacticalRules.withdrawCost(cheap, "ridge"));
        assertEquals(2, TacticalRules.withdrawCost(cheap, "marsh"), "never cheaper than walking the difficult edge");
        var steep = fight(Map.of("difficultTerrainMultiplier", 3), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN), List.of(),
                EncounterOutcomeTest.player().zone("camp"), EncounterOutcomeTest.foe("foe").zone("gully"));
        assertEquals(3, TacticalRules.withdrawCost(steep, "marsh"));
        assertEquals(-1, TacticalRules.withdrawCost(normal, "gully"));
        assertEquals(0, CombatScheduler.apply(steep, new TacticalIntent.Withdraw("marsh"), EncounterOutcomeTest.script())
                .last().actionPoints(), "the scheduler charges the edge's higher price");
        assertEquals(2, CombatScheduler.apply(cheap, new TacticalIntent.Withdraw("ridge"), EncounterOutcomeTest.script())
                .last().actionPoints(), "and the declared price where that is higher");

        var plan = CombatScheduler.apply(normal, new TacticalIntent.Withdraw("ridge"), EncounterOutcomeTest.script());
        assertEquals(1, plan.last().actionPoints());
        assertEquals(TacticalRules.Refusal.ACTION_POINTS,
                TacticalRules.refusal(EncounterOutcomeTest.after(normal, plan), new TacticalIntent.Withdraw("pass")),
                "the pass is one edge from the ridge, but one point is left");
    }

    /**
     * A move out of the camp provokes the snapper's {@code leavesZone} counter; a withdrawal across the same edge provokes
     * nothing and draws nothing, and its public event names no reaction.
     */
    @Test
    void aWithdrawalSuppressesTheLeavesZoneReaction() {
        var state = fight(Map.of(), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN), List.of(), EncounterOutcomeTest.player().zone("camp"),
                EncounterOutcomeTest.foe("snapper").zone("camp").attacks(CLAW).react(SNAP));
        var moved = CombatScheduler.apply(state, new TacticalIntent.MoveZone("ridge"), EncounterOutcomeTest.script(10, 10, 6));
        assertTrue(types(moved).contains("REACTION_RESOLVED"), "a move is answered");
        assertEquals(1, ((List<?>) moved.summary().get("strikes")).size());

        var withdrawn = CombatScheduler.apply(state, new TacticalIntent.Withdraw("ridge"), EncounterOutcomeTest.script());
        assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.AWAITING_ACTION), EncounterOutcomeTest.phases(withdrawn));
        assertFalse(types(withdrawn).contains("REACTION_RESOLVED"), "a withdrawal is not");
        assertFalse(types(withdrawn).contains("PARTICIPANT_MOVED"));
        assertFalse(withdrawn.summary().containsKey("strikes"));
        var note = withdrawn.notes().stream().filter(n -> n.type().equals("PARTICIPANT_WITHDREW")).findFirst().orElseThrow();
        assertEquals(Map.of("encounterId", ID.toString(), "round", 1, "participantId", "player",
                "actorId", "00000000-0000-0000-0000-0000000000aa", "from", "camp", "to", "ridge", "actionPoints", 2,
                "remainingActionPoints", 1), note.payload());
        assertTrue(note.byPlayer());
        var after = EncounterOutcomeTest.after(state, withdrawn);
        assertEquals("ridge", after.player().zone());
        assertEquals(1, after.participant("snapper").reactions(), "the snapper keeps its reaction");
        assertEquals(24, after.player().health());
    }

    /**
     * Withdrawing sets no status and decides nothing: a zone short of the pass leaves the fight running, and the pass ends it
     * only through the predicates, in their order, so a reach-zone objective on the pass is met first and a declared-action
     * tick that fells the player makes a defeat in place.
     */
    @Test
    void onlyTheTerminalPredicatesCompleteAnEscape() {
        var start = fight(Map.of(), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN), List.of(), EncounterOutcomeTest.player().zone("camp"),
                EncounterOutcomeTest.foe("foe").zone("gully"));
        var first = CombatScheduler.apply(start, new TacticalIntent.Withdraw("ridge"), EncounterOutcomeTest.script());
        var ridge = EncounterOutcomeTest.after(start, first);
        assertEquals(EncounterState.Status.ACTIVE, ridge.player().status());
        assertNull(EncounterOutcome.decide(ridge), "half way is no escape");

        var atRidge = fight(Map.of(), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN), List.of(), EncounterOutcomeTest.player().zone("ridge"),
                EncounterOutcomeTest.foe("foe").zone("gully"));
        var out = CombatScheduler.apply(atRidge, new TacticalIntent.Withdraw("pass"), EncounterOutcomeTest.script());
        assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.OUTCOME_PENDING), EncounterOutcomeTest.phases(out));
        assertEquals("ESCAPE", out.notes().getLast().payload().get("outcome"));
        assertFalse(out.notes().getLast().payload().containsKey("roundCap"));
        var escaped = EncounterOutcomeTest.after(atRidge, out);
        assertEquals(EncounterState.Status.ACTIVE, escaped.player().status(), "settlement records the standing, not the action");
        assertEquals(Map.of("run", EncounterObjective.Status.MET), EncounterOutcome.decide(escaped).objectives());

        var reach = new EncounterObjective("summit", EncounterObjective.Kind.REACH_ZONE, "pass", null, 0);
        var objective = fight(Map.of(), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN, reach), List.of(),
                EncounterOutcomeTest.player().zone("ridge"), EncounterOutcomeTest.foe("foe").zone("gully"));
        var met = CombatScheduler.apply(objective, new TacticalIntent.Withdraw("pass"), EncounterOutcomeTest.script());
        assertEquals("OBJECTIVE", met.notes().getLast().payload().get("outcome"));
        assertEquals("summit", met.notes().getLast().payload().get("objectiveId"));

        var searing = EncounterOutcomeTest.tick("searing", CombatEffect.Phase.ACTION_DECLARED, 99);
        var burning = EncounterOutcomeTest.library(fight(Map.of(), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN), List.of(),
                EncounterOutcomeTest.player().zone("ridge").holding(EncounterOutcomeTest.active(searing, 1)),
                EncounterOutcomeTest.foe("foe").zone("gully")), searing);
        var fell = CombatScheduler.apply(burning, new TacticalIntent.Withdraw("pass"), EncounterOutcomeTest.script());
        assertEquals("DEFEAT", fell.notes().getLast().payload().get("outcome"));
        assertFalse(types(fell).contains("PARTICIPANT_WITHDREW"));
        assertEquals("ridge", EncounterOutcomeTest.after(burning, fell).player().zone(), "a fallen player goes nowhere");
    }

    /** An effect preventing movement or actions refuses a withdrawal, as does another participant's turn. */
    @Test
    void preventionAndTurnRefuseAWithdrawal() {
        for (var prevents : List.of(CombatEffect.Prevents.MOVEMENT, CombatEffect.Prevents.ACTIONS)) {
            var effect = new CombatEffect("held", "held", CombatEffect.Stacking.STRONGEST, 1, CombatEffect.Clock.PERSISTENT, 0, false,
                    0, 0, 0, 0, 0, Set.of(prevents), List.of(), false);
            var state = EncounterOutcomeTest.library(fight(Map.of(), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN), List.of(),
                    EncounterOutcomeTest.player().zone("camp").holding(EncounterOutcomeTest.active(effect, 1)),
                    EncounterOutcomeTest.foe("foe").zone("gully")), effect);
            assertEquals(TacticalRules.Refusal.PREVENTED, TacticalRules.refusal(state, new TacticalIntent.Withdraw("ridge")), prevents.name());
            assertEquals(List.of(), TacticalRules.options(state).get("withdraw"), prevents.name());
        }
        var state = fight(Map.of(), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN), List.of(), EncounterOutcomeTest.player().zone("camp"),
                EncounterOutcomeTest.foe("foe").zone("gully"));
        var elsewhere = at(state, EncounterPhase.AWAITING_ACTION, 1, 1, 3);
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(elsewhere, new TacticalIntent.Withdraw("ridge")));
    }

    /** A withdrawal is an action: it takes terms off the table, as any action but speech or a policy change does. */
    @Test
    void aWithdrawalTakesAnOfferOffTheTable() {
        var offer = new Negotiation.Offer("o1", "parley", "foe", new Negotiation.Terms("peace", Negotiation.Terms.Kind.TRUCE, 0),
                Negotiation.Response.OFFERED);
        var state = fight(Map.of(), Set.of("pass"), Disengagement.STALEMATE, List.of(RUN), List.of(), EncounterOutcomeTest.player().zone("camp"),
                EncounterOutcomeTest.foe("foe").zone("gully"));
        var offered = new EncounterState(state.id(), state.rowVersion(), state.phase(), state.round(), state.cursor(), state.actionPoints(),
                state.speechUsed(), state.passages(), state.profile(), state.frozenSpace(), state.escapeZones(), state.actions(),
                state.opening(), state.openingTarget(), state.openingStatus(), state.participants(), state.ammunition(), state.effects(),
                state.effectsApplied(), state.objectives(), offer, null, state.disengagement());
        var plan = CombatScheduler.apply(offered, new TacticalIntent.Withdraw("ridge"), EncounterOutcomeTest.script());
        var lapsed = plan.notes().stream().filter(n -> n.type().equals("OFFER_LAPSED")).findFirst().orElseThrow();
        assertEquals("ACTION_TAKEN", lapsed.payload().get("reason"));
        assertNull(plan.last().openOffer());
    }

    // ---- the round cap ---------------------------------------------------------------------------------------------

    /**
     * At the end of the last allowed round an undecided fight ends in a stalemate under the default policy: the round closes
     * with its time, no further round opens, the verdict says the cap gave it and every participant is as it was.
     */
    @Test
    void theRoundCapEndsAnUndecidedFightInAStalemate() {
        var state = fight(Map.of("roundCap", 1), Set.of("pass"), Disengagement.STALEMATE, List.of(WIN, RUN), List.of(),
                EncounterOutcomeTest.player().zone("camp"), EncounterOutcomeTest.foe("foe").zone("gully"));
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), EncounterOutcomeTest.script());
        var phases = EncounterOutcomeTest.phases(plan);
        assertEquals(List.of(EncounterPhase.ROUND_END, EncounterPhase.OUTCOME_PENDING), phases.subList(phases.size() - 2, phases.size()));
        assertEquals(8L, plan.elapsedSeconds());
        assertFalse(types(plan).contains("ROUND_STARTED"), "no round beyond the cap");
        var payload = plan.notes().getLast().payload();
        assertEquals("ENCOUNTER_OUTCOME_PENDING", plan.notes().getLast().type());
        assertEquals("STALEMATE", payload.get("outcome"));
        assertEquals(true, payload.get("roundCap"));
        assertEquals(Map.of("outcome", "STALEMATE", "roundCap", true, "objectives", List.of(
                Map.of("objectiveId", "win", "status", "pending"), Map.of("objectiveId", "run", "status", "pending"))),
                ((Map<?, ?>) plan.summary().get("outcome")));
        var stored = EncounterOutcomeTest.after(state, plan);
        assertEquals(new EncounterOutcome(EncounterOutcome.Kind.STALEMATE, null, null,
                Map.of("win", EncounterObjective.Status.PENDING, "run", EncounterObjective.Status.PENDING), true),
                EncounterOutcome.decide(stored), "the stored state gives the same verdict");
        unharmed(state, stored);
        for (var intent : List.of(new TacticalIntent.EndActivation(), new TacticalIntent.Withdraw("ridge"), new TacticalIntent.Guard())) {
            assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(stored, intent));
        }
    }

    /**
     * A template that declares {@code escape} lets the player get away at the cap: an escape the cap gave, with nobody moved,
     * so the escape objective keeps the status the player's zone gives it.
     */
    @Test
    void theDeclaredEscapePolicyEndsItAsAnEscapeWithoutMovingAnyone() {
        var state = fight(Map.of("roundCap", 1), Set.of("pass"), Disengagement.ESCAPE, List.of(RUN), List.of(),
                EncounterOutcomeTest.player().zone("camp"), EncounterOutcomeTest.foe("foe").zone("gully"));
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), EncounterOutcomeTest.script());
        assertEquals(EncounterPhase.OUTCOME_PENDING, plan.last().phase());
        var payload = plan.notes().getLast().payload();
        assertEquals("ESCAPE", payload.get("outcome"));
        assertEquals(true, payload.get("roundCap"));
        assertEquals(List.of(Map.of("objectiveId", "run", "status", "pending")), payload.get("objectives"));
        var verdict = EncounterOutcome.decide(capped(state, Disengagement.ESCAPE));
        assertEquals(EncounterOutcome.Kind.ESCAPE, verdict.kind());
        assertTrue(verdict.roundCap());
        assertEquals(EncounterOutcome.Kind.STALEMATE, EncounterOutcome.decide(capped(state, Disengagement.STALEMATE)).kind());
        unharmed(state, EncounterOutcomeTest.after(state, plan));
    }

    /**
     * The cap is the last predicate and reads only the round count: before the last round ends nothing is decided, a round-end
     * tick still fires at the cap exactly as in any round, and one that fells the last opponent there is a victory.
     */
    @Test
    void theCapComesAfterEveryOtherPredicateAndHarmsNobody() {
        var state = fight(Map.of("roundCap", 2), Set.of("pass"), Disengagement.STALEMATE, List.of(WIN), List.of(),
                EncounterOutcomeTest.player().zone("camp"), EncounterOutcomeTest.foe("foe").zone("gully"));
        assertNull(EncounterOutcome.decide(at(state, EncounterPhase.ROUND_END, 1, null, null)), "one round short of the cap");
        assertNull(EncounterOutcome.decide(at(state, EncounterPhase.AWAITING_ACTION, 2, 0, 3)), "the last round is still running");
        assertEquals(EncounterOutcome.Kind.STALEMATE, EncounterOutcome.decide(at(state, EncounterPhase.ROUND_END, 2, null, null)).kind());
        assertEquals(EncounterOutcome.Kind.STALEMATE,
                EncounterOutcome.decide(at(state, EncounterPhase.OUTCOME_PENDING, 2, null, null)).kind(), "read back from storage");

        var bleed = EncounterOutcomeTest.tick("bleed", CombatEffect.Phase.ROUND_END, 3);
        int[] health = new int[2];
        for (int cap = 1; cap <= 2; cap++) {
            var bleeding = EncounterOutcomeTest.library(fight(Map.of("roundCap", cap), Set.of("pass"), Disengagement.STALEMATE,
                    List.of(WIN), List.of(), EncounterOutcomeTest.player().zone("camp"),
                    EncounterOutcomeTest.foe("foe").zone("gully").holding(EncounterOutcomeTest.active(bleed, 1))), bleed);
            var plan = CombatScheduler.apply(bleeding, new TacticalIntent.EndActivation(), EncounterOutcomeTest.script());
            health[cap - 1] = EncounterOutcomeTest.after(bleeding, plan).participant("foe").health();
        }
        assertEquals(21, health[0], "the last round's own tick still fires");
        assertEquals(health[1], health[0], "the cap adds nothing to it");

        var lethal = EncounterOutcomeTest.library(fight(Map.of("roundCap", 1), Set.of("pass"), Disengagement.STALEMATE, List.of(WIN),
                List.of(), EncounterOutcomeTest.player().zone("camp"),
                EncounterOutcomeTest.foe("foe").zone("gully").health(3, 24).holding(EncounterOutcomeTest.active(bleed, 1))), bleed);
        var won = CombatScheduler.apply(lethal, new TacticalIntent.EndActivation(), EncounterOutcomeTest.script());
        assertEquals("VICTORY", won.notes().getLast().payload().get("outcome"));
        assertFalse(won.notes().getLast().payload().containsKey("roundCap"));

        var last = at(state, EncounterPhase.ROUND_END, 2, null, null);
        var truce = new Negotiation.Agreement("o1", "peace", "foe");
        var agreed = new EncounterState(last.id(), last.rowVersion(), last.phase(), last.round(), last.cursor(), last.actionPoints(),
                last.speechUsed(), last.passages(), last.profile(), last.frozenSpace(), last.escapeZones(), last.actions(), last.opening(),
                last.openingTarget(), last.openingStatus(), last.participants(), last.ammunition(), last.effects(), last.effectsApplied(),
                last.objectives(), null, truce, Disengagement.ESCAPE);
        var accepted = EncounterOutcome.decide(agreed);
        assertEquals(EncounterOutcome.Kind.AGREEMENT, accepted.kind(), "an accepted truce comes before the cap");
        assertFalse(accepted.roundCap());
        var away = fight(Map.of("roundCap", 2), Set.of("pass"), Disengagement.STALEMATE, List.of(WIN), List.of(),
                EncounterOutcomeTest.player().zone("pass"), EncounterOutcomeTest.foe("foe").zone("gully"));
        var reached = EncounterOutcome.decide(at(away, EncounterPhase.ROUND_END, 2, null, null));
        assertEquals(EncounterOutcome.Kind.ESCAPE, reached.kind(), "a reached escape zone comes before the cap");
        assertFalse(reached.roundCap());
    }

    /** Only the cap makes a stalemate, and the cap makes nothing but a stalemate or an escape. */
    @Test
    void onlyTheCapMakesAStalemate() {
        assertThrows(IllegalArgumentException.class,
                () -> new EncounterOutcome(EncounterOutcome.Kind.STALEMATE, null, null, Map.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> new EncounterOutcome(EncounterOutcome.Kind.VICTORY, null, null, Map.of(), true));
        assertEquals(Map.of("outcome", "ESCAPE", "objectives", List.of()),
                new EncounterOutcome(EncounterOutcome.Kind.ESCAPE, null, null, Map.of()).publicForm(), "no mark without the cap");
        assertEquals(Disengagement.ESCAPE, Disengagement.parse("escape"));
        assertEquals(Disengagement.STALEMATE, Disengagement.parse("stalemate"));
        assertThrows(IllegalArgumentException.class, () -> Disengagement.parse("retreat"));
    }

    /**
     * The pack declares the policy per template and the fight freezes it: only a policy other than the default stalemate
     * appears in the frozen definition, so every definition frozen before it keeps its text.
     */
    @Test
    void theTemplateDeclaresThePolicyAndTheFightFreezesIt() throws Exception {
        var rules = EncounterPack.rules(EncounterPack.tactics());
        assertEquals(Disengagement.ESCAPE, rules.templates().get("watch-post").disengagement());
        assertEquals(Disengagement.STALEMATE, rules.templates().get("stand-off").disengagement());
        var facts = new FrozenEncounter.PlayerFacts(UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                Map.of("awareness", 2L, "might", 2L), new FrozenEncounter.Pool(8, 8), null, List.of(), Set.of());
        var profile = rules.profiles().get("sample:armory-tactics");
        var escape = FrozenEncounter.freeze(ID, rules.templates().get("watch-post"), profile, facts, null, rules.library());
        assertEquals(Disengagement.ESCAPE, escape.disengagement());
        assertTrue(escape.definition().contains("\"disengagement\":\"escape\""));
        var stalemate = FrozenEncounter.freeze(ID, rules.templates().get("stand-off"), profile, facts, null, rules.library());
        assertEquals(Disengagement.STALEMATE, stalemate.disengagement());
        assertFalse(stalemate.definition().contains("disengagement"));
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    /** Every participant keeps its Health, status, zone and effects. */
    private static void unharmed(EncounterState before, EncounterState after) {
        for (var participant : before.participants()) {
            var now = after.participant(participant.id());
            assertEquals(participant.health(), now.health(), participant.id());
            assertEquals(participant.status(), now.status(), participant.id());
            assertEquals(participant.zone(), now.zone(), participant.id());
            assertEquals(participant.effects(), now.effects(), participant.id());
        }
    }

    private static List<String> types(TacticalPlan plan) {
        return plan.notes().stream().map(TacticalPlan.Note::type).toList();
    }

    /**
     * A fight at the player's decision of round one over the retreat space; rank order is the argument order.
     *
     * @param overrides profile values
     * @param escapes escape zones
     * @param policy disengagement policy at the round cap
     * @param objectives frozen objectives
     * @param passages passage states
     */
    private static EncounterState fight(Map<String, Integer> overrides, Set<String> escapes, Disengagement policy,
            List<EncounterObjective> objectives, List<EncounterAction.Passage> passages, EncounterOutcomeTest.Builder player,
            EncounterOutcomeTest.Builder... others) {
        var profile = CombatProfile.resolve("pack", Map.of("force", "a", "finesse", "b", "insight", "c", "resolve", "d"), "hp", null,
                overrides);
        var list = new ArrayList<EncounterState.Combatant>();
        list.add(player.build(0, true, profile.reactionsPerRound()));
        for (int index = 0; index < others.length; index++) {
            list.add(others[index].build(index + 1, true, profile.reactionsPerRound()));
        }
        return new EncounterState(ID, 5, EncounterPhase.AWAITING_ACTION, 1, 0, profile.actionPoints(), 0, passages, profile, space(),
                escapes, List.of(), OpeningPolicy.INITIATIVE, null, "NONE", list, List.of(), Map.of(), 0, objectives, null, null, policy);
    }

    /** The same state with these passage states. */
    private static EncounterState withPassages(EncounterState s, EncounterAction.Passage... passages) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(),
                List.of(passages), s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(),
                s.openingStatus(), s.participants(), s.ammunition(), s.effects(), s.effectsApplied(), s.objectives(), s.openOffer(),
                s.agreement(), s.disengagement());
    }

    /** The same state at another point of the schedule, keeping its policy. */
    private static EncounterState at(EncounterState s, EncounterPhase phase, int round, Integer cursor, Integer points) {
        return new EncounterState(s.id(), s.rowVersion(), phase, round, cursor, points, s.speechUsed(), s.passages(), s.profile(),
                s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives(), s.openOffer(), s.agreement(), s.disengagement());
    }

    /** The state at the end of its last allowed round under a policy. */
    private static EncounterState capped(EncounterState s, Disengagement policy) {
        return new EncounterState(s.id(), s.rowVersion(), EncounterPhase.ROUND_END, s.profile().roundCap(), null, null, 0,
                s.passages(), s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(),
                s.openingStatus(), s.participants(), s.ammunition(), s.effects(), s.effectsApplied(), s.objectives(), null, null, policy);
    }

    /** camp - ridge - pass, camp - marsh (difficult) - pass, camp - gully: the gully is a dead end. */
    private static ZoneGraph space() {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        for (String id : List.of("camp", "ridge", "pass", "marsh", "gully")) {
            zones.put(id, new ZoneGraph.Zone(id, Set.of(), ZoneGraph.Cover.NONE, false, 0));
        }
        var edges = new ArrayList<ZoneGraph.Edge>();
        for (String[] pair : new String[][] {{"camp", "ridge"}, {"ridge", "pass"}, {"camp", "gully"}, {"marsh", "pass"}}) {
            edges.add(new ZoneGraph.Edge(pair[0], pair[1], false, false));
            edges.add(new ZoneGraph.Edge(pair[1], pair[0], false, false));
        }
        edges.add(new ZoneGraph.Edge("camp", "marsh", true, false));
        edges.add(new ZoneGraph.Edge("marsh", "camp", true, false));
        return new ZoneGraph(zones, edges);
    }
}
