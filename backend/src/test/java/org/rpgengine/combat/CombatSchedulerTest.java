package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * P11.2 scheduler and tactical rules, pure: initiative is rolled once from recorded dice with the documented
 * tie-breaks, rounds and activations follow the docs/11 state machine one arrow at a time, action points are granted
 * at activation start and expire at its end, other participants' activations pass until P11.3/P12 give them tactics,
 * a round ends with its world time exactly once per operation, movement and declared actions are priced and gated
 * by the frozen encounter, speech is bounded, and nothing is legal out of turn.
 */
class CombatSchedulerTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    /** Construct a stateless fixture. */
    CombatSchedulerTest() {
    }

    /** Totals, ranks and order follow the dice and modifiers; the round opens and the faster player decides. */
    @Test
    void initiativeIsRolledOnceAndOpensRoundOne() throws Exception {
        var state = entering("trail-ambush");
        // Recorded dice of the worked example: player 6 + 7 + 4 = 17, raider 5 + 6 + 2 = 13.
        var plan = CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), dice(6, 7, 5, 6));
        assertEquals(List.of(EncounterPhase.ROUND_OPEN, EncounterPhase.ACTOR_START, EncounterPhase.AWAITING_ACTION),
                phases(plan));
        assertEquals(List.of("INITIATIVE_ROLLED", "ROUND_STARTED", "ACTIVATION_STARTED"), types(plan));
        assertEquals(0L, plan.elapsedSeconds());
        var player = update(plan, "player");
        assertEquals(17, player.initiative());
        assertEquals(0, player.rank());
        assertEquals(1, player.reactions(), "round-open refreshes the reaction budget");
        assertEquals(13, update(plan, "raider").initiative());
        assertEquals(1, update(plan, "raider").rank());
        assertEquals(1, plan.last().round());
        assertEquals(3, plan.last().actionPoints());
        assertEquals(0, plan.last().cursor());
        var order = (List<?>) plan.notes().getFirst().payload().get("order");
        assertEquals(List.of(6, 7), ((Map<?, ?>) order.get(0)).get("dice"));
        assertTrue(plan.notes().get(2).byPlayer(), "the player's own activation is attributed to the player");
        assertFalse(plan.notes().get(1).byPlayer(), "round bookkeeping is the scheduler's");
        // A rolled encounter cannot roll again, and the same inputs always give the same plan.
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(after(state, plan), new TacticalIntent.RollInitiative()));
        assertEquals(plan, CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), dice(6, 7, 5, 6)));
        assertThrows(IllegalStateException.class, () -> CombatScheduler.apply(state, new TacticalIntent.RollInitiative(),
                dice(11, 7, 5, 6)), "a die outside its faces is a broken draw source");
    }

    /** A faster hostile takes its activation first; without tactics it passes and its points expire. */
    @Test
    void aFasterParticipantActsFirstAndPasses() throws Exception {
        var plan = CombatScheduler.apply(entering("trail-ambush"), new TacticalIntent.RollInitiative(), dice(1, 1, 9, 9));
        assertEquals(0, update(plan, "raider").rank());
        assertEquals(List.of(EncounterPhase.ROUND_OPEN, EncounterPhase.ACTOR_START, EncounterPhase.AWAITING_ACTION,
                EncounterPhase.ACTOR_END, EncounterPhase.ACTOR_START, EncounterPhase.AWAITING_ACTION), phases(plan));
        assertEquals(List.of("INITIATIVE_ROLLED", "ROUND_STARTED", "ACTIVATION_STARTED", "ACTIVATION_ENDED",
                "ACTIVATION_STARTED"), types(plan));
        assertEquals("raider", plan.notes().get(3).payload().get("participantId"));
        assertEquals(3, plan.notes().get(3).payload().get("unusedActionPoints"));
        assertEquals(1, plan.last().cursor(), "the player decides at rank 1");
        assertEquals(0L, plan.elapsedSeconds(), "no round ended");
        assertSteps(entering("trail-ambush"), plan);
    }

    /** Equal totals are ordered by finesse, then by participant ID. */
    @Test
    void tiesBreakOnFinesseThenIdentity() throws Exception {
        // Roster player, keeper, smuggler: 1+1+4 = 6, 3+3+0 = 6, 2+2+2 = 6. Player and smuggler share finesse 2.
        var plan = CombatScheduler.apply(entering("toll-parley"), new TacticalIntent.RollInitiative(),
                dice(1, 1, 3, 3, 2, 2));
        assertEquals(0, update(plan, "player").rank());
        assertEquals(1, update(plan, "smuggler").rank());
        assertEquals(2, update(plan, "keeper").rank());
        assertEquals(6, update(plan, "keeper").initiative());
    }

    /**
     * Ending the player's activation runs the other activations, ends the round once with its seconds, opens the
     * next round (refreshing reactions, resetting the speech allowance) and stops at the player's next decision.
     */
    @Test
    void endingTheActivationClosesTheRoundOnce() throws Exception {
        var state = rolled("trail-ambush", 6, 7, 5, 6);
        state = after(state, CombatScheduler.apply(state, new TacticalIntent.Speech("raider", "Leave."), dice()));
        state = spend(state, "player");
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), dice());
        assertEquals(List.of(EncounterPhase.ACTOR_END, EncounterPhase.ACTOR_START, EncounterPhase.AWAITING_ACTION,
                EncounterPhase.ACTOR_END, EncounterPhase.ROUND_END, EncounterPhase.ROUND_OPEN, EncounterPhase.ACTOR_START,
                EncounterPhase.AWAITING_ACTION), phases(plan));
        assertEquals(List.of("ACTIVATION_ENDED", "ACTIVATION_STARTED", "ACTIVATION_ENDED", "ROUND_ENDED",
                "ROUND_STARTED", "ACTIVATION_STARTED"), types(plan));
        assertTrue(plan.notes().getFirst().byPlayer());
        assertEquals(3, plan.notes().getFirst().payload().get("unusedActionPoints"));
        assertEquals(0, plan.steps().getFirst().actionPoints(), "unused points expire at the activation's end");
        assertEquals(0, plan.steps().get(3).actionPoints(), "the raider's points expire too");
        assertEquals(8L, plan.elapsedSeconds(), "one completed round of the default eight seconds");
        assertEquals(Map.of("encounterId", ID.toString(), "round", 1, "seconds", 8), plan.notes().get(3).payload());
        assertEquals(2, plan.last().round());
        assertEquals(3, plan.last().actionPoints());
        assertEquals(0, plan.last().speechUsed());
        assertEquals(1, update(plan, "player").reactions(), "spent reactions refresh once at round-open");
        assertSteps(state, plan);
    }

    /** At the round cap the schedule stops at the round's end; nothing is legal until the stalemate policy (P11.5). */
    @Test
    void theRoundCapStopsTheSchedule() throws Exception {
        var state = rolled("trail-ambush", 6, 7, 5, 6);
        state = with(state, CombatProfile.resolve(state.profile().pack(), state.profile().roles(), state.profile().health(),
                null, Map.of("roundCap", 1)));
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), dice());
        assertEquals(EncounterPhase.ROUND_END, plan.last().phase());
        assertNull(plan.last().cursor());
        assertEquals(8L, plan.elapsedSeconds());
        assertFalse(plan.summary().containsKey("options"));
        var capped = after(state, plan);
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(capped, new TacticalIntent.EndActivation()));
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(capped, new TacticalIntent.MoveZone("yard")));
    }

    /** Participants who no longer stand are skipped; a round without a standing player ends and stops. */
    @Test
    void onlyStandingParticipantsActivate() throws Exception {
        var downed = status(entering("trail-ambush"), "raider", EncounterState.Status.DEFEATED);
        var plan = CombatScheduler.apply(downed, new TacticalIntent.RollInitiative(), dice(1, 1, 9, 9));
        assertEquals(List.of("INITIATIVE_ROLLED", "ROUND_STARTED", "ACTIVATION_STARTED"), types(plan));
        assertEquals(0, update(plan, "raider").reactions(), "no reaction budget for a defeated participant");
        var next = CombatScheduler.apply(after(downed, plan), new TacticalIntent.EndActivation(), dice());
        assertTrue(next.notes().stream().noneMatch(note -> "raider".equals(note.payload().get("participantId"))));
        assertEquals(2, next.last().round());
        var alone = status(entering("trail-ambush"), "player", EncounterState.Status.DEFEATED);
        var stopped = CombatScheduler.apply(alone, new TacticalIntent.RollInitiative(), dice(9, 9, 1, 1));
        assertEquals(EncounterPhase.ROUND_END, stopped.last().phase(), "no decision exists without a standing player");
        assertEquals(8L, stopped.elapsedSeconds());
        assertEquals(1, stopped.last().round());
    }

    /** One edge per move at the frozen price; blocked, unknown, current and full zones are refused. */
    @Test
    void movementFollowsTheZoneGraph() throws Exception {
        var state = rolled("trail-ambush", 6, 7, 5, 6);
        assertEquals(2, TacticalRules.moveCost(state, "escape-route"), "difficult terrain doubles the edge");
        var plan = CombatScheduler.apply(state, new TacticalIntent.MoveZone("yard"), dice());
        assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.AWAITING_ACTION), phases(plan));
        assertEquals(2, plan.last().actionPoints());
        assertEquals("yard", update(plan, "player").zone());
        var moved = plan.notes().getFirst().payload();
        assertEquals("entry", moved.get("from"));
        assertEquals(1, moved.get("actionPoints"));
        assertEquals(PLAYER.toString(), moved.get("actorId"));
        var yard = after(state, plan);
        assertEquals(TacticalRules.Refusal.ZONE, TacticalRules.refusal(yard, new TacticalIntent.MoveZone("yard")));
        assertEquals(TacticalRules.Refusal.ZONE, TacticalRules.refusal(yard, new TacticalIntent.MoveZone("cellar")));
        var tired = after(yard, CombatScheduler.apply(yard, new TacticalIntent.MoveZone("escape-route"), dice()));
        assertEquals(1, tired.actionPoints());
        assertEquals(TacticalRules.Refusal.ACTION_POINTS, TacticalRules.refusal(tired,
                new TacticalIntent.MoveZone("entry")), "the difficult edge back costs 2");
        // A zone at capacity cannot be entered.
        var crowded = space(state, "yard", 1);
        assertEquals(TacticalRules.Refusal.ZONE, TacticalRules.refusal(crowded, new TacticalIntent.MoveZone("yard")));
        // A blocked edge cannot be crossed.
        var parley = rolled("toll-parley", 6, 7, 1, 1, 1, 1);
        assertEquals(TacticalRules.Refusal.ZONE, TacticalRules.refusal(parley, new TacticalIntent.MoveZone("booth")));
        // At zero points the activation stays open; only its explicit end passes control.
        var spent = after(tired, CombatScheduler.apply(tired, new TacticalIntent.MoveZone("yard"), dice()));
        assertEquals(0, spent.actionPoints());
        assertEquals(EncounterPhase.AWAITING_ACTION, spent.phase());
        assertTrue(spent.playerDecision());
    }

    /** Speech costs nothing, changes only its count, is bounded per activation and reaches only addressable recipients. */
    @Test
    void speechIsBounded() throws Exception {
        var state = rolled("trail-ambush", 6, 7, 5, 6);
        var plan = CombatScheduler.apply(state, new TacticalIntent.Speech("raider", "Why are you attacking us?"), dice());
        assertEquals(List.of(EncounterPhase.AWAITING_ACTION), phases(plan));
        assertEquals(3, plan.last().actionPoints());
        assertEquals(1, plan.last().speechUsed());
        assertEquals(List.of(), plan.participants());
        assertEquals(0L, plan.elapsedSeconds());
        assertEquals("Why are you attacking us?", plan.notes().getFirst().payload().get("text"));
        var spoken = after(state, plan);
        assertEquals(TacticalRules.Refusal.SPEECH_LIMIT, TacticalRules.refusal(spoken,
                new TacticalIntent.Speech("raider", "Again.")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.Speech("player", "Me.")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.Speech("ghost", "Hi.")));
        // Out of speaking range: a profile that only reaches the same zone.
        var quiet = with(state, CombatProfile.resolve(state.profile().pack(), state.profile().roles(),
                state.profile().health(), null, Map.of("maxRangeEdges", 0)));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(quiet, new TacticalIntent.Speech("raider", "Hi.")));
        // A defeated participant cannot be addressed; a surrendered one can.
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(status(state, "raider",
                EncounterState.Status.DEFEATED), new TacticalIntent.Speech("raider", "Hi.")));
        assertNull(TacticalRules.refusal(status(state, "raider", EncounterState.Status.SURRENDERED),
                new TacticalIntent.Speech("raider", "Hi.")));
    }

    /** Declared social actions address an admitted, reachable recipient and cost their declared points. */
    @Test
    void socialActionsAreDeclaredAndPriced() throws Exception {
        var state = rolled("toll-parley", 6, 7, 1, 1, 1, 1);
        var plan = CombatScheduler.apply(state, new TacticalIntent.Interact("demand-passage", "smuggler"), dice());
        assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.AWAITING_ACTION), phases(plan));
        assertEquals(2, plan.last().actionPoints(), "the default Interact cost");
        var attempt = plan.notes().getFirst().payload();
        assertEquals("negotiate", attempt.get("intent"));
        assertEquals("smuggler", attempt.get("targetId"));
        assertEquals("social", attempt.get("kind"));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state,
                new TacticalIntent.Interact("demand-passage", "keeper")), "a hostile-only action");
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state,
                new TacticalIntent.Interact("demand-passage", null)));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state,
                new TacticalIntent.Interact("ask-keeper", "smuggler")), "an ally-only action");
        assertEquals(TacticalRules.Refusal.ACTION, TacticalRules.refusal(state,
                new TacticalIntent.Interact("bribe", "smuggler")), "undeclared actions do not exist");
        var asked = after(state, CombatScheduler.apply(state, new TacticalIntent.Interact("ask-keeper", "keeper"), dice()));
        assertEquals(1, asked.actionPoints());
        assertEquals(TacticalRules.Refusal.ACTION_POINTS, TacticalRules.refusal(asked,
                new TacticalIntent.Interact("ask-keeper", "keeper")));
    }

    /** Scene actions change a declared passage once, from their zone, and movement then follows the new space. */
    @Test
    void sceneActionsChangeAPassageOnce() throws Exception {
        var state = rolled("toll-parley", 6, 7, 1, 1, 1, 1);
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state,
                new TacticalIntent.Interact("unbar-gate", "smuggler")), "a scene action addresses nobody");
        var plan = CombatScheduler.apply(state, new TacticalIntent.Interact("unbar-gate", null), dice());
        assertEquals(List.of(new EncounterAction.Passage("booth", "gate", false)), plan.last().passages());
        assertEquals(Map.of("from", "booth", "to", "gate", "blocked", false), plan.notes().getFirst().payload().get("passage"));
        var open = after(state, plan);
        assertEquals(1, TacticalRules.moveCost(open, "booth"), "the unbarred gate can be crossed");
        assertEquals(TacticalRules.Refusal.ACTION, TacticalRules.refusal(open,
                new TacticalIntent.Interact("unbar-gate", null)), "an open gate cannot be opened again");
        // A zone-bound action is refused elsewhere and accepted from its zone.
        var ambush = rolled("trail-ambush", 6, 7, 5, 6);
        assertEquals(TacticalRules.Refusal.ACTION, TacticalRules.refusal(ambush,
                new TacticalIntent.Interact("seal-route", null)));
        var yard = after(ambush, CombatScheduler.apply(ambush, new TacticalIntent.MoveZone("yard"), dice()));
        var sealed = after(yard, CombatScheduler.apply(yard, new TacticalIntent.Interact("seal-route", null), dice()));
        assertEquals(-1, TacticalRules.moveCost(sealed, "escape-route"));
        assertTrue(sealed.space().passageIs("yard", "escape-route", true));
        assertEquals(1, sealed.space().distance("yard", "escape-route"), "range ignores the sealed route");
    }

    /** Nothing but the initiative roll is legal outside the player's own decision. */
    @Test
    void nothingIsLegalOutOfTurn() throws Exception {
        var entering = entering("trail-ambush");
        for (TacticalIntent intent : List.of(new TacticalIntent.EndActivation(), new TacticalIntent.MoveZone("yard"),
                new TacticalIntent.Speech("raider", "Hi."), new TacticalIntent.Interact("taunt", "raider"))) {
            assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(entering, intent), intent.toString());
        }
        var state = rolled("trail-ambush", 6, 7, 5, 6);
        // The same rows while the raider acts: the player cannot decide or queue anything.
        var theirs = new EncounterState(state.id(), state.rowVersion(), EncounterPhase.AWAITING_ACTION, 1, 1, 3, 0,
                List.of(), state.profile(), state.frozenSpace(), state.escapeZones(), state.actions(), state.opening(),
                state.openingTarget(), state.openingStatus(), state.participants());
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(theirs, new TacticalIntent.EndActivation()));
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(theirs, new TacticalIntent.Speech("raider", "Hi.")));
        assertEquals(Map.of(), TacticalRules.options(theirs));
        // A player who no longer stands cannot decide, even at the player's own cursor.
        var downed = status(state, "player", EncounterState.Status.DEFEATED);
        assertFalse(downed.playerDecision());
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(downed, new TacticalIntent.EndActivation()));
        assertThrows(IllegalStateException.class, () -> CombatScheduler.apply(theirs, new TacticalIntent.EndActivation(),
                dice()), "the scheduler re-checks legality");
    }

    /** The receipt options list exactly the affordable legal moves, recipients and declared actions. */
    @Test
    void optionsListTheLegalCandidates() throws Exception {
        var state = rolled("trail-ambush", 6, 7, 5, 6);
        var options = TacticalRules.options(state);
        assertEquals(List.of(Map.of("zone", "escape-route", "cost", 2), Map.of("zone", "yard", "cost", 1)),
                options.get("moves"));
        assertEquals(Map.of("remaining", 1, "recipients", List.of("raider")), options.get("speech"));
        assertEquals(List.of(Map.of("actionId", "taunt", "kind", "social", "cost", 1, "targets", List.of("raider"))),
                options.get("actions"), "seal-route needs the yard");
        assertEquals(true, options.get("endActivation"));
        var summary = CombatScheduler.apply(state, new TacticalIntent.MoveZone("yard"), dice()).summary();
        assertEquals("player", summary.get("acting"));
        assertEquals(2, summary.get("actionPoints"));
        assertEquals("yard", summary.get("zone"));
        assertEquals(List.of(Map.of("participantId", "player", "initiative", 17),
                Map.of("participantId", "raider", "initiative", 13)), summary.get("initiative"));
    }

    /** The encounter's frozen definition and profile read back into the same rules values. */
    @Test
    void frozenFormsReadBack() throws Exception {
        var frozen = frozen("toll-parley");
        var definition = new tools.jackson.databind.json.JsonMapper().readValue(frozen.definition(), Map.class);
        assertEquals(frozen.zones(), ZoneGraph.fromCanonical((Map<?, ?>) definition.get("space")));
        var actions = new ArrayList<EncounterAction>();
        ((List<?>) definition.get("actions")).forEach(action -> actions.add(EncounterAction.fromCanonical((Map<?, ?>) action)));
        assertEquals(frozen.actions(), actions);
        var profile = new tools.jackson.databind.json.JsonMapper().readValue(frozen.profile().canonical(), Map.class);
        assertEquals(frozen.profile(), CombatProfile.fromCanonical(profile));
        assertFalse(frozen("grove-watch").definition().contains("\"actions\""), "action-free templates keep the P11.1 form");
        var other = new TreeMap<>(profile);
        other.put("kernel", "other/1");
        assertThrows(IllegalArgumentException.class, () -> CombatProfile.fromCanonical(other));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Freeze one fixture template for the surveyor-like player (awareness 2, might 2, vigor 8/8). */
    private static FrozenEncounter frozen(String template) throws Exception {
        var rules = EncounterPack.rules(EncounterPack.files());
        var facts = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", 2L, "might", 2L),
                new FrozenEncounter.Pool(8, 8), null, List.of(), Set.of());
        return FrozenEncounter.freeze(ID, rules.templates().get(template), rules.profiles().get("sample:armory"), facts,
                null);
    }

    /**
     * The largest roster fits one operation: nine participants, with the rivals on the largest modifier 12 (two tens
     * reach the highest stored total, 32) and a player acting last or first. Roll and end of activation stay within
     * the plan's bound, every other participant's activation passes, and each participant's reactions refresh once.
     */
    @Test
    void theLargestRosterFitsOneOperation() throws Exception {
        for (boolean playerLast : List.of(true, false)) {
            var entered = entering("trail-ambush");
            var list = new ArrayList<>(entered.participants().subList(0, 1));
            for (int ordinal = 1; ordinal < 9; ordinal++) {
                list.add(new EncounterState.Combatant("rival-" + ordinal, ordinal, null, EncounterTemplate.Side.HOSTILE,
                        "yard", EncounterState.Status.ACTIVE, 6, 12, null, null, 0, 0));
            }
            var state = new EncounterState(entered.id(), 0, EncounterPhase.ENTERING, 0, null, null, 0, List.of(),
                    entered.profile(), entered.frozenSpace(), entered.escapeZones(), entered.actions(), entered.opening(),
                    null, "NONE", list);
            int[] values = new int[18];
            java.util.Arrays.fill(values, playerLast ? 10 : 1);
            values[0] = playerLast ? 1 : 10;
            values[1] = values[0];
            var roll = CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), dice(values));
            assertSteps(state, roll);
            assertEquals(playerLast ? 32 : 14, update(roll, "rival-8").initiative());
            assertEquals(playerLast ? 8 : 0, update(roll, "player").rank());
            var rolled = after(state, roll);
            assertTrue(rolled.playerDecision());
            assertEquals(playerLast ? 9 : 1, types(roll).stream().filter("ACTIVATION_STARTED"::equals).count());
            var ended = CombatScheduler.apply(rolled, new TacticalIntent.EndActivation(), dice());
            assertSteps(rolled, ended);
            assertTrue(ended.steps().size() <= TacticalPlan.MAX_STEPS, "steps: " + ended.steps().size());
            assertEquals(9, types(ended).stream().filter("ACTIVATION_STARTED"::equals).count(), "one more activation each");
            assertEquals(8L, ended.elapsedSeconds(), "one round boundary only");
            assertTrue(after(rolled, ended).playerDecision());
            assertEquals(9, after(rolled, ended).participants().stream().filter(p -> p.reactions() == 1).count());
        }
    }

    /** The stored state right after entry: phase ENTERING, round 0, every participant active and unranked. */
    private static EncounterState entering(String template) throws Exception {
        var frozen = frozen(template);
        var list = new ArrayList<EncounterState.Combatant>();
        int ordinal = 0;
        for (var participant : frozen.participants()) {
            list.add(new EncounterState.Combatant(participant.id(), ordinal++, participant.actorId(), participant.side(),
                    participant.zone(), EncounterState.Status.ACTIVE, Math.toIntExact(participant.roles().get("finesse")),
                    Math.toIntExact(participant.initiativeModifier()), null, null, 0, 0,
                    Math.toIntExact(participant.health()), Math.toIntExact(participant.healthMax()), false, participant.sheet()));
        }
        return new EncounterState(frozen.id(), 0, EncounterPhase.ENTERING, 0, null, null, 0, List.of(), frozen.profile(),
                frozen.zones(), frozen.escapeZones(), frozen.actions(), frozen.opening(), null, "NONE", list);
    }

    /** Entry followed by an initiative roll with the given dice. */
    private static EncounterState rolled(String template, int... values) throws Exception {
        var state = entering(template);
        return after(state, CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), dice(values)));
    }

    /** What the writer stores after a plan: the last step and the updated participants, one version per write. */
    private static EncounterState after(EncounterState state, TacticalPlan plan) {
        var updates = new HashMap<String, TacticalPlan.ParticipantUpdate>();
        plan.participants().forEach(update -> updates.put(update.participantId(), update));
        var list = new ArrayList<EncounterState.Combatant>();
        for (var p : state.participants()) {
            var u = updates.get(p.id());
            list.add(u == null ? p : new EncounterState.Combatant(p.id(), p.ordinal(), p.actorId(), p.side(), u.zone(),
                    u.status(), p.finesse(), p.initiativeModifier(), u.initiative(), u.rank(), u.reactions(),
                    p.rowVersion() + 1, u.health() == null ? p.health() + plan.playerHealthDelta() : u.health(), p.healthMax(),
                    u.guarding(), p.sheet(), u.focus() == null ? p.focus() + plan.playerFocusDelta() : u.focus(), p.focusMax(),
                    u.effects(), u.policies(), u.uses()));
        }
        var last = plan.last();
        return new EncounterState(state.id(), state.rowVersion() + plan.steps().size(), last.phase(), last.round(),
                last.cursor(), last.actionPoints(), last.speechUsed(), last.passages(), state.profile(),
                state.frozenSpace(), state.escapeZones(), state.actions(), state.opening(), state.openingTarget(),
                last.openingStatus(), list, List.of(), state.effects(), last.effectsApplied());
    }

    /** The same state under another profile. */
    private static EncounterState with(EncounterState s, CombatProfile profile) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(),
                s.speechUsed(), s.passages(), profile, s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(),
                s.openingTarget(), s.openingStatus(), s.participants());
    }

    /** The same state with one participant's standing changed. */
    private static EncounterState status(EncounterState s, String id, EncounterState.Status status) {
        var list = s.participants().stream().map(p -> !p.id().equals(id) ? p : new EncounterState.Combatant(p.id(),
                p.ordinal(), p.actorId(), p.side(), p.zone(), status, p.finesse(), p.initiativeModifier(), p.initiative(),
                p.rank(), p.reactions(), p.rowVersion())).toList();
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(),
                s.speechUsed(), s.passages(), s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(),
                s.openingTarget(), s.openingStatus(), list);
    }

    /** The same state with one participant's reactions spent. */
    private static EncounterState spend(EncounterState s, String id) {
        var list = s.participants().stream().map(p -> !p.id().equals(id) ? p : new EncounterState.Combatant(p.id(),
                p.ordinal(), p.actorId(), p.side(), p.zone(), p.status(), p.finesse(), p.initiativeModifier(),
                p.initiative(), p.rank(), 0, p.rowVersion())).toList();
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(),
                s.speechUsed(), s.passages(), s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(),
                s.openingTarget(), s.openingStatus(), list);
    }

    /** The same state with one zone's capacity changed. */
    private static EncounterState space(EncounterState s, String zone, int capacity) {
        var zones = new TreeMap<>(s.frozenSpace().zones());
        var old = zones.get(zone);
        zones.put(zone, new ZoneGraph.Zone(zone, old.tags(), old.cover(), old.obscured(), capacity));
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(),
                s.speechUsed(), s.passages(), s.profile(), new ZoneGraph(zones, s.frozenSpace().edges()), s.escapeZones(),
                s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants());
    }

    /** Recorded dice by ordinal. */
    private static CombatScheduler.Dice dice(int... values) {
        return (ordinal, sides) -> {
            if (ordinal >= values.length) {
                throw new AssertionError("unexpected draw " + ordinal);
            }
            assertEquals(10, sides);
            return values[ordinal];
        };
    }

    /** The plan's phases in order. */
    private static List<EncounterPhase> phases(TacticalPlan plan) {
        return plan.steps().stream().map(TacticalPlan.Step::phase).toList();
    }

    /** The plan's event types in order. */
    private static List<String> types(TacticalPlan plan) {
        return plan.notes().stream().map(TacticalPlan.Note::type).toList();
    }

    /** The update of one participant. */
    private static TacticalPlan.ParticipantUpdate update(TacticalPlan plan, String id) {
        return plan.participants().stream().filter(u -> u.participantId().equals(id)).findFirst().orElseThrow();
    }

    /** Every step keeps its phase or follows one arrow of the state machine from the previous one. */
    private static void assertSteps(EncounterState from, TacticalPlan plan) {
        EncounterPhase previous = from.phase();
        for (TacticalPlan.Step step : plan.steps()) {
            if (step.phase() != previous) {
                assertTrue(previous.successors().contains(step.phase()), previous + " -> " + step.phase());
            }
            previous = step.phase();
        }
    }
}
