package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Pure acceptance of settlement (P11.5, docs/11 section 14, ADR 0036). A fight waiting at {@code OUTCOME_PENDING} settles
 * once, on the player's explicit request naming it: the verdict is decided again from the state, an unfinished round adds its
 * seconds once and no effect ticks for it, every combat effect and stance ends, nothing else changes, and the encounter stops
 * at {@code NARRATIVE_RESUME}, where nothing more is legal. The seeded fights of {@code EncounterOutcomeTest} also settle each
 * of their outcomes; the PostgreSQL path is {@code SettlementCommandsTest}.
 */
class SettlementTest {
    private static final EncounterObjective WIN = new EncounterObjective("win", EncounterObjective.Kind.DEFEAT_HOSTILES, null, null, 0);

    /** Construct a stateless fixture. */
    SettlementTest() {
    }

    /**
     * A fight won inside round two settles to that verdict: two steps without a cursor, the round's eight seconds once, no
     * draw, no participant change, and one public event of the player's decision stating the verdict, the round, the seconds
     * and every final standing.
     */
    @Test
    void aSettlementFinalizesTheStoredVerdictOnce() {
        var state = pending(EncounterOutcomeTest.fight(List.of(WIN), EncounterOutcomeTest.player(),
                EncounterOutcomeTest.foe("foe").status(EncounterState.Status.DEFEATED)), 2, 1, Disengagement.STALEMATE);
        var verdict = EncounterOutcome.decide(state);
        assertEquals(EncounterOutcome.Kind.VICTORY, verdict.kind());
        var plan = CombatScheduler.apply(state, settle(state), EncounterOutcomeTest.script());
        assertEquals(List.of(EncounterPhase.SETTLED, EncounterPhase.NARRATIVE_RESUME), EncounterOutcomeTest.phases(plan));
        for (var step : plan.steps()) {
            assertNull(step.cursor(), "no activation outlives the fight");
            assertNull(step.actionPoints());
            assertEquals(2, step.round());
        }
        assertEquals(8, plan.elapsedSeconds(), "the unfinished round runs to its boundary once");
        assertEquals(0, plan.playerHealthDelta(), "nothing is refilled");
        assertEquals(0, plan.playerFocusDelta());
        assertEquals(List.of(), plan.ammunition());
        assertEquals(List.of(), plan.participants(), "nobody's zone, standing or pool changes");

        assertEquals(1, plan.notes().size());
        var note = plan.notes().getFirst();
        assertEquals("ENCOUNTER_SETTLED", note.type());
        assertTrue(note.byPlayer(), "the player's own decision");
        assertFalse(note.internal());
        var expected = new TreeMap<String, Object>(verdict.publicForm());
        expected.put("encounterId", state.id().toString());
        expected.put("round", 2);
        expected.put("seconds", 8L);
        expected.put("participants", List.of(
                Map.of("participantId", "player", "side", "player", "zone", "ring", "status", "ACTIVE"),
                Map.of("participantId", "foe", "side", "hostile", "zone", "ring", "status", "DEFEATED")));
        expected.put("effectsEnded", List.of());
        assertEquals(expected, note.payload());

        var summary = plan.summary();
        assertEquals("NARRATIVE_RESUME", summary.get("phase"));
        assertEquals(verdict.publicForm(), summary.get("outcome"));
        assertEquals(Map.of("seconds", 8L, "effectsEnded", List.of()), summary.get("settlement"));
        assertFalse(summary.containsKey("options"), "no decision follows");
        assertFalse(summary.containsKey("acting"));
    }

    /**
     * Only the fight the player reviewed, waiting at its outcome, settles. Every other intention is out of turn there, a fight
     * still going on cannot be settled, and once settled nothing is legal, a second settlement included.
     */
    @Test
    void onlyTheReviewedFightAtItsOutcomeSettlesAndOnlyOnce() {
        var decided = pending(EncounterOutcomeTest.fight(List.of(WIN), EncounterOutcomeTest.player(),
                EncounterOutcomeTest.foe("foe").status(EncounterState.Status.DEFEATED)), 2, null, Disengagement.STALEMATE);
        assertNull(TacticalRules.refusal(decided, settle(decided)));
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(decided, new TacticalIntent.Settle(new UUID(0, 1).toString())),
                "another fight's identity");
        for (var intent : others()) {
            assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(decided, intent), intent.toString());
        }

        var undecided = EncounterOutcomeTest.fight(List.of(WIN), EncounterOutcomeTest.player(), EncounterOutcomeTest.foe("foe"));
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(undecided, settle(undecided)), "a fight going on");
        assertThrows(IllegalStateException.class, () -> CombatScheduler.apply(undecided, settle(undecided), EncounterOutcomeTest.script()));

        var settled = EncounterOutcomeTest.after(decided, CombatScheduler.apply(decided, settle(decided), EncounterOutcomeTest.script()));
        assertEquals(EncounterPhase.NARRATIVE_RESUME, settled.phase());
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(settled, settle(settled)), "a second settlement");
        for (var intent : others()) {
            assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(settled, intent), intent.toString());
        }
        assertThrows(IllegalStateException.class, () -> CombatScheduler.apply(settled, settle(settled), EncounterOutcomeTest.script()));
        assertEquals(Map.of(), TacticalRules.options(settled));
    }

    /** A stored outcome without a verdict breaks a guarantee: settling it fails instead of inventing an ending. */
    @Test
    void anOutcomeWithoutVerdictCannotBeSettled() {
        var broken = pending(EncounterOutcomeTest.fight(List.of(WIN), EncounterOutcomeTest.player(), EncounterOutcomeTest.foe("foe")),
                1, 0, Disengagement.STALEMATE);
        assertNull(EncounterOutcome.decide(broken));
        var failure = assertThrows(IllegalStateException.class,
                () -> CombatScheduler.apply(broken, settle(broken), EncounterOutcomeTest.script()));
        assertEquals("a fight waiting for settlement has no verdict", failure.getMessage());
    }

    /**
     * Both round-cap verdicts come at a completed round, so settlement adds no time; the cap's escape moves nobody and changes
     * no standing, so the event states the player still in the zone where the fight stood still.
     */
    @Test
    void theCapOutcomesSettleAtTheirRoundEndAndMoveNobody() {
        for (var policy : List.of(Disengagement.STALEMATE, Disengagement.ESCAPE)) {
            var state = pending(EncounterOutcomeTest.fight(List.of(WIN), EncounterOutcomeTest.player(),
                    EncounterOutcomeTest.foe("foe").zone("far")), 30, null, policy);
            var verdict = EncounterOutcome.decide(state);
            assertTrue(verdict.roundCap());
            assertEquals(policy == Disengagement.ESCAPE ? EncounterOutcome.Kind.ESCAPE : EncounterOutcome.Kind.STALEMATE, verdict.kind());
            var plan = CombatScheduler.apply(state, settle(state), EncounterOutcomeTest.script());
            assertEquals(0, plan.elapsedSeconds(), "the last round already added its time");
            assertEquals(List.of(), plan.participants());
            var payload = plan.notes().getFirst().payload();
            assertEquals(verdict.kind().name(), payload.get("outcome"));
            assertEquals(true, payload.get("roundCap"));
            assertEquals(0L, payload.get("seconds"));
            assertEquals(List.of(Map.of("participantId", "player", "side", "player", "zone", "ring", "status", "ACTIVE"),
                    Map.of("participantId", "foe", "side", "hostile", "zone", "far", "status", "ACTIVE")), payload.get("participants"));
        }
    }

    /**
     * Combat effects and the Guard stance end with the fight, publicly and in roster order, since no pack declares how they
     * would carry over; zones, standings and Health stay, and no tick runs for the settled round.
     */
    @Test
    void combatEffectsAndStancesEndWithTheFight() {
        var venom = EncounterOutcomeTest.tick("venom", CombatEffect.Phase.ROUND_END, 5);
        var base = EncounterOutcomeTest.library(EncounterOutcomeTest.fight(List.of(WIN),
                EncounterOutcomeTest.player().holding(EncounterOutcomeTest.active(venom, 1)),
                EncounterOutcomeTest.foe("foe").status(EncounterState.Status.DEFEATED),
                EncounterOutcomeTest.ally("friend").zone("mid").holding(EncounterOutcomeTest.active(venom, 2))), venom);
        var state = pending(guarding(base, "player"), 3, 0, Disengagement.STALEMATE);
        var plan = CombatScheduler.apply(state, settle(state), EncounterOutcomeTest.script());

        assertEquals(List.of(Map.of("participantId", "player", "effectId", "venom", "stacks", 1),
                Map.of("participantId", "friend", "effectId", "venom", "stacks", 1)), plan.notes().getFirst().payload().get("effectsEnded"));
        assertEquals(List.of("player", "friend"), plan.participants().stream().map(TacticalPlan.ParticipantUpdate::participantId).toList());
        for (var update : plan.participants()) {
            assertEquals(List.of(), update.effects());
            assertFalse(update.guarding());
            var before = state.participant(update.participantId());
            assertEquals(before.zone(), update.zone());
            assertEquals(before.status(), update.status());
            assertEquals(before.player() ? null : (Integer) before.health(), update.health());
        }
        assertEquals(0, plan.playerHealthDelta(), "the settled round's tick does not fire");
        assertEquals(List.of("ENCOUNTER_SETTLED"), plan.notes().stream().map(TacticalPlan.Note::type).toList());
        var standing = (List<?>) plan.summary().get("participants");
        standing.forEach(line -> assertFalse(((Map<?, ?>) line).containsKey("effects"), "no effect is held afterwards"));
        assertEquals(2, ((List<?>) ((Map<?, ?>) plan.summary().get("settlement")).get("effectsEnded")).size());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The settlement of this state's own encounter. */
    private static TacticalIntent.Settle settle(EncounterState state) {
        return new TacticalIntent.Settle(state.id().toString());
    }

    /** Every other kind of intention, none of which a decided or settled fight admits. */
    private static List<TacticalIntent> others() {
        return List.of(new TacticalIntent.EndActivation(), new TacticalIntent.Guard(), new TacticalIntent.MoveZone("mid"),
                new TacticalIntent.Withdraw("mid"), new TacticalIntent.RollInitiative(), new TacticalIntent.Speech("foe", "Well?"),
                new TacticalIntent.SetReactionPolicy("brace", CombatReaction.Policy.OFF), new TacticalIntent.Assess("foe"),
                new TacticalIntent.Ready(true), new TacticalIntent.AnswerOffer("offer-1", true));
    }

    /** The same state waiting at its outcome in this round, inside it when a cursor is given, under this cap policy. */
    private static EncounterState pending(EncounterState s, int round, Integer cursor, Disengagement policy) {
        return new EncounterState(s.id(), s.rowVersion(), EncounterPhase.OUTCOME_PENDING, round, cursor, cursor == null ? null : 0,
                s.speechUsed(), s.passages(), s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(),
                s.openingTarget(), s.openingStatus(), s.participants(), s.ammunition(), s.effects(), s.effectsApplied(),
                s.objectives(), null, null, policy);
    }

    /** The same state with this participant holding its Guard stance. */
    private static EncounterState guarding(EncounterState s, String id) {
        var list = s.participants().stream().map(p -> !p.id().equals(id) ? p : new EncounterState.Combatant(p.id(), p.ordinal(),
                p.actorId(), p.side(), p.zone(), p.status(), p.finesse(), p.initiativeModifier(), p.initiative(), p.rank(),
                p.reactions(), p.rowVersion(), p.health(), p.healthMax(), true, p.sheet(), p.focus(), p.focusMax(), p.effects(),
                p.policies(), p.uses())).toList();
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(),
                s.passages(), s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(),
                s.openingStatus(), list, s.ammunition(), s.effects(), s.effectsApplied(), s.objectives());
    }
}
