package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Knowledge-scoped legal candidates of a non-player decision (P12.3, {@link TacticCandidates}) over the fixed fight of
 * {@link TacticStates}: what an opponent and a companion observe, which candidates they get in which order, what prevention
 * and spent points remove, and that only listed choices are admitted.
 */
class TacticCandidatesTest {
    /** Construct a stateless fixture. */
    TacticCandidatesTest() {
    }

    /** The bandit sees the player and the scout by public standing only; the ally in the obscured zone is nowhere. */
    @Test
    void anOpponentObservesOnlyWhatItCanSee() {
        var menu = TacticCandidates.menu(TacticStates.banditDecides());
        assertEquals("bandit", menu.actorId());
        assertEquals(TacticStates.BANDIT_ACTOR, menu.campaignActor());
        assertEquals(List.of(new TacticCandidates.Observed("player", true, "mid", EncounterState.Status.ACTIVE, true),
                new TacticCandidates.Observed("scout", true, "ring", EncounterState.Status.ACTIVE, false)), menu.observed());
        assertEquals(List.of("move", "strike:cudgel", "strike:sling", "guard", "end-activation"),
                menu.candidates().stream().map(TacticCandidates.Candidate::id).toList());
        assertEquals(List.of(new TacticCandidates.Destination("fog", 1), new TacticCandidates.Destination("mid", 1)),
                menu.candidate("move").destinations(), "zones one edge away, in zone order; far is two edges away");
        assertEquals(List.of("scout"), menu.candidate("strike:cudgel").targets(), "melee reaches its own zone only");
        assertEquals(List.of("scout", "player"), menu.candidate("strike:sling").targets(),
                "nearest first; the hidden ally one edge away is out of sight, not a target");
        assertEquals(9, menu.health());
        assertEquals(12, menu.healthMax());
        for (var candidate : menu.candidates()) {
            assertFalse(candidate.targets().contains(TacticStates.HIDDEN));
        }
    }

    /** A companion chooses among the same kinds of candidates, with the player as its ally. */
    @Test
    void aCompanionStrikesOnlyItsOpponents() {
        var menu = TacticCandidates.menu(TacticStates.scoutDecides());
        assertEquals("scout", menu.actorId());
        assertNull(menu.campaignActor());
        assertEquals(List.of(new TacticCandidates.Observed("player", false, "mid", EncounterState.Status.ACTIVE, true),
                new TacticCandidates.Observed("bandit", true, "ring", EncounterState.Status.ACTIVE, false)), menu.observed(),
                "its own side's member in the obscured zone is hidden from it too");
        assertEquals(List.of("bandit"), menu.candidate("strike:knife").targets());
        assertTrue(menu.admits(new TacticalIntent.Strike("knife", "bandit")));
        assertFalse(menu.admits(new TacticalIntent.Strike("knife", "player")), "an ally is never a strike target");
    }

    /** Only listed choices with a listed parameter are admitted; nothing the scheduler cannot do for a non-player is. */
    @Test
    void admitsOnlyListedChoices() {
        var menu = TacticCandidates.menu(TacticStates.banditDecides());
        assertTrue(menu.admits(new TacticalIntent.MoveZone("mid")));
        assertTrue(menu.admits(new TacticalIntent.Strike("sling", "player")));
        assertTrue(menu.admits(new TacticalIntent.Guard()));
        assertTrue(menu.admits(new TacticalIntent.EndActivation()));
        assertFalse(menu.admits(new TacticalIntent.MoveZone("far")), "two edges away");
        assertFalse(menu.admits(new TacticalIntent.MoveZone("ring")), "the current zone");
        assertFalse(menu.admits(new TacticalIntent.Strike("sling", TacticStates.HIDDEN)), "hidden from the actor");
        assertFalse(menu.admits(new TacticalIntent.Strike("cudgel", "player")), "out of melee reach");
        assertFalse(menu.admits(new TacticalIntent.Strike(TacticStates.PLAYER_ATTACK, "player")), "not the actor's attack");
        assertFalse(menu.admits(new TacticalIntent.Assess("player")));
        assertFalse(menu.admits(new TacticalIntent.UseAbility("any", List.of())));
        assertFalse(menu.admits(new TacticalIntent.Speech("player", "words")));
    }

    /** Spent points, a stance already held and prevention remove candidates; the end of the activation always stays. */
    @Test
    void costsStanceAndPreventionNarrowTheMenu() {
        var one = TacticCandidates.menu(TacticStates.deciding(0, 1, List.of()));
        assertEquals(List.of("move", "guard", "end-activation"), ids(one), "a strike costs two points");

        var guarding = TacticCandidates.menu(TacticStates.state(0, 3, List.of(), Map.of(), true));
        assertEquals(List.of("move", "strike:cudgel", "strike:sling", "end-activation"), ids(guarding));

        var stunned = prevents("stunned", CombatEffect.Prevents.ACTIONS);
        assertEquals(List.of("end-activation"), ids(TacticCandidates.menu(TacticStates.state(0, 3,
                List.of(active(stunned)), Map.of(stunned.id(), stunned), false))));

        var snared = prevents("snared", CombatEffect.Prevents.MOVEMENT);
        assertEquals(List.of("strike:cudgel", "strike:sling", "guard", "end-activation"), ids(TacticCandidates.menu(
                TacticStates.state(0, 3, List.of(active(snared)), Map.of(snared.id(), snared), false))));
    }

    /** There is no menu at the player's decision or outside an activation's decision. */
    @Test
    void noMenuOutsideANonPlayerDecision() {
        assertNull(TacticCandidates.menu(TacticStates.deciding(1, 3, List.of())), "the player decides for itself");
        var entering = TacticStates.banditDecides();
        var waiting = new EncounterState(entering.id(), entering.rowVersion(), EncounterPhase.RESOLVING, entering.round(),
                entering.cursor(), entering.actionPoints(), 0, List.of(), entering.profile(), entering.frozenSpace(),
                entering.escapeZones(), entering.actions(), entering.opening(), null, "NONE", entering.participants());
        assertNull(TacticCandidates.menu(waiting));
    }

    private static List<String> ids(TacticCandidates.Menu menu) {
        return menu.candidates().stream().map(TacticCandidates.Candidate::id).toList();
    }

    private static CombatEffect prevents(String id, CombatEffect.Prevents what) {
        return new CombatEffect(id, id, CombatEffect.Stacking.STRONGEST, 1, CombatEffect.Clock.PERSISTENT, 0, false, 0, 0, 0, 0,
                0, Set.of(what), List.of(), false);
    }

    private static CombatEffects.Active active(CombatEffect effect) {
        return new CombatEffects.Active("e1", effect.id(), null, 1, 0, 1, 0, null, false);
    }
}
