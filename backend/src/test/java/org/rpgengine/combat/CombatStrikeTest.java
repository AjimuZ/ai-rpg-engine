package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Pure acceptance of P11.3 on {@link CombatScheduler} and {@link TacticalRules}: the two-round fight of docs/20 section 3
 * from its recorded dice, the order and meaning of every draw, what a miss spends, every targeting rule, the Guard
 * action, the opening strike's slots and lapses, the small non-player tactic, and seeded fights whose invariants hold at
 * every step. All values are scripted or derive from a fixed seed; the PostgreSQL path is {@code StrikeCommandsTest}.
 */
class CombatStrikeTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final CombatAttack BLADE = new CombatAttack("sword", "force", 2, 1, 6, "force", 0, 0, 0,
            CombatAttack.Defense.GUARD, true, null);

    /** Construct a stateless fixture. */
    CombatStrikeTest() {
    }

    /**
     * The fight of docs/20 section 3 from its recorded dice, through the real fixture and freeze: Guard 12 and armor 1
     * from the worn items, the opponent's Brace raising Guard to 14, 22 against it (a strong hit), 8 raw and 7 taken, the
     * opponent's Strike meeting the player's Brace and missing with 13, its Guard action, a redundant Brace skipped in
     * round two, 15 against Guard 14 (a normal hit), 7 more taken and a defeat without any death.
     */
    @Test
    void playsTheWorkedFightFromItsRecordedDice() throws Exception {
        var state = fixture("yard-duel", 24, null);
        assertEquals(12, state.player().sheet().guard());
        assertEquals(1, state.player().sheet().armor());
        var sword = state.player().sheet().attack("sword");
        assertEquals(2, sword.skill(), "the player's rank in the weapon's skill, read at entry");
        assertEquals("force", sword.damageAttribute());

        // Initiative 6 + 7 + 4 = 17 and 5 + 6 + 2 = 13: the player acts first and the schedule stops at the decision.
        var rolled = CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), script(6, 7, 5, 6));
        state = after(state, rolled);
        assertTrue(state.playerDecision());
        assertEquals(3, state.actionPoints());
        assertEquals(List.of("player", "bandit"), state.order().stream().map(EncounterState.Combatant::id).toList());

        state = after(state, CombatScheduler.apply(state, new TacticalIntent.MoveZone("yard"), script()));
        assertEquals(2, state.actionPoints());

        // Round 1, the player: 9 + 9 + 2 + 2 = 22 is a strong hit; 4 on the d6 + 2 on the d4 + 2 = 8; armor leaves 7.
        var strike = CombatScheduler.apply(state, new TacticalIntent.Strike("sword", "bandit"), script(9, 9, 4, 2));
        state = after(state, strike);
        assertEquals(0, state.actionPoints());
        assertEquals(7, state.participant("bandit").health());
        assertEquals(EncounterPhase.AWAITING_ACTION, state.phase(), "only the explicit end passes control");
        var shown = note(strike, "STRIKE_RESOLVED");
        assertEquals(22, shown.payload().get("attackTotal"));
        assertEquals(true, shown.payload().get("strongHit"));
        assertEquals(7, shown.payload().get("damage"));
        assertEquals(List.of(9, 9), shown.payload().get("attackDice"));
        assertEquals(List.of(4), shown.payload().get("damageDice"));
        assertEquals(2, shown.payload().get("strongDie"));
        assertFalse(shown.internal());
        assertTrue(shown.byPlayer());
        var calculation = note(strike, "STRIKE_CALCULATED");
        assertTrue(calculation.internal(), "the opponent's defense and Health stay out of the public record");
        assertEquals(14, calculation.payload().get("defense"), "the opponent's predeclared Brace");
        assertEquals(8, calculation.payload().get("margin"));
        assertEquals("bandit", note(strike, "REACTION_RESOLVED").payload().get("participantId"));
        assertEquals("brace", note(strike, "REACTION_RESOLVED").payload().get("reactionId"));
        assertEquals(0, state.participant("bandit").reactions(), "Brace spends the one reaction of the round");
        assertEquals(8, calculation.payload().get("rawDamage"));
        assertEquals(1, calculation.payload().get("effectiveArmor"));
        assertEquals(14, calculation.payload().get("healthBefore"));
        assertEquals(7, calculation.payload().get("healthAfter"));
        assertEquals(List.of(0, 1, 2, 3), calculation.payload().get("drawOrdinals"));
        for (String hidden : List.of("defense", "rawDamage", "armor", "healthAfter", "targetHealthAfter")) {
            assertFalse(shown.payload().containsKey(hidden), hidden);
        }

        // The opponent strikes (5 + 6 + 1 + 1 = 13, a miss on the braced Guard 14), then guards with its last point.
        var ended = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script(5, 6));
        state = after(state, ended);
        assertEquals(24, state.player().health());
        assertEquals(0, ended.playerHealthDelta());
        assertEquals(8, ended.elapsedSeconds());
        assertEquals(1, state.player().reactions(), "the budget refreshed once when round two opened");
        assertEquals(2, state.round());
        assertTrue(state.participant("bandit").guarding());
        assertEquals(List.of("ACTIVATION_ENDED", "ACTIVATION_STARTED", "REACTION_RESOLVED", "STRIKE_RESOLVED", "STRIKE_CALCULATED",
                "GUARD_RAISED", "ACTIVATION_ENDED", "ROUND_ENDED", "ROUND_STARTED", "ACTIVATION_STARTED"),
                ended.notes().stream().map(TacticalPlan.Note::type).toList());
        var opponent = note(ended, "STRIKE_RESOLVED");
        assertEquals(14, opponent.payload().get("defense"), "the player may see its own defense");
        assertEquals(false, opponent.payload().get("hit"));
        assertEquals(24, opponent.payload().get("targetHealthAfter"));
        assertFalse(opponent.byPlayer());

        // Round 2: 6 + 5 + 2 + 2 = 15 against Guard 14 is a normal hit; 6 + 2 - 1 = 7 takes the last Health.
        var last = CombatScheduler.apply(state, new TacticalIntent.Strike("sword", "bandit"), script(6, 5, 6));
        state = after(state, last);
        assertEquals(14, note(last, "STRIKE_CALCULATED").payload().get("defense"));
        assertTrue(last.notes().stream().noneMatch(n -> n.type().equals("REACTION_RESOLVED")),
                "the Guard action already holds the stacking key, so Brace would add nothing and is skipped");
        assertEquals(1, state.participant("bandit").reactions(), "a skipped Brace spends nothing");
        assertEquals(false, note(last, "STRIKE_RESOLVED").payload().get("strongHit"));
        assertEquals(0, state.participant("bandit").health());
        assertEquals(EncounterState.Status.DEFEATED, state.participant("bandit").status());
        assertFalse(state.participant("bandit").guarding(), "a defeated participant holds no stance");
        assertTrue(last.notes().stream().anyMatch(n -> n.type().equals("PARTICIPANT_DEFEATED")));

        // It takes no further activation: the next round goes straight back to the player.
        var next = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals(3, after(state, next).round());
        assertEquals(1, next.notes().stream().filter(n -> n.type().equals("ACTIVATION_STARTED")).count());
    }

    /** Draws come in a fixed order with the attack's own dice: attack, damage, strong die; a miss draws only the attack. */
    @Test
    void drawsAreOrderedAndAMissDrawsNoDamage() {
        var heavy = new CombatAttack("maul", "force", 0, 2, 8, "force", 1, 0, 0, CombatAttack.Defense.GUARD, true, null);
        var state = duel(List.of(heavy), new Foe(40, 12, 0, 0, List.of()));
        var asked = new ArrayList<int[]>();
        var dice = recording(asked, 10, 10, 3, 5, 2);
        var plan = CombatScheduler.apply(state, new TacticalIntent.Strike("maul", "foe"), dice);
        assertEquals(List.of("0:10", "1:10", "2:8", "3:8", "4:4"), asked.stream().map(a -> a[0] + ":" + a[1]).toList());
        // 3 + 5 + force 2 + flat 1 + strong die 2.
        assertEquals(13, note(plan, "STRIKE_CALCULATED").payload().get("rawDamage"));

        asked.clear();
        var miss = CombatScheduler.apply(state, new TacticalIntent.Strike("maul", "foe"), recording(asked, 1, 1));
        assertEquals(2, asked.size(), "a miss reserves no damage draw");
        assertEquals(false, note(miss, "STRIKE_RESOLVED").payload().get("hit"));
        assertEquals(0, note(miss, "STRIKE_RESOLVED").payload().get("damage"));
        assertEquals(1, after(state, miss).actionPoints(), "a legal miss still pays its points");
        assertEquals(40, after(state, miss).participant("foe").health());
    }

    /** A strong hit needs permission, armor and penetration apply, a resistance attack reads Resistance alone. */
    @Test
    void attackDefinitionsDecideTheirArithmetic() {
        var plain = new CombatAttack("plain", "force", 0, 1, 6, null, 0, 0, 0, CombatAttack.Defense.GUARD, false, null);
        var state = duel(List.of(plain), new Foe(40, 0, 3, 7, List.of()));
        var noStrong = CombatScheduler.apply(state, new TacticalIntent.Strike("plain", "foe"), script(10, 10, 5));
        assertEquals(false, note(noStrong, "STRIKE_RESOLVED").payload().get("strongHit"));
        // No attribute contribution: 5 raw, armor 3 leaves 2.
        assertEquals(5, note(noStrong, "STRIKE_CALCULATED").payload().get("rawDamage"));
        assertEquals(2, note(noStrong, "STRIKE_RESOLVED").payload().get("damage"));

        var piercing = new CombatAttack("pierce", "force", 0, 1, 6, null, 0, 2, 0, CombatAttack.Defense.GUARD, false, null);
        var pierced = CombatScheduler.apply(duel(List.of(piercing), new Foe(40, 0, 3, 7, List.of())),
                new TacticalIntent.Strike("pierce", "foe"), script(10, 10, 5));
        assertEquals(1, note(pierced, "STRIKE_CALCULATED").payload().get("effectiveArmor"));
        assertEquals(4, note(pierced, "STRIKE_RESOLVED").payload().get("damage"));

        var absorbed = CombatScheduler.apply(duel(List.of(plain), new Foe(40, 0, 10, 7, List.of())),
                new TacticalIntent.Strike("plain", "foe"), script(10, 10, 5));
        assertEquals(0, note(absorbed, "STRIKE_RESOLVED").payload().get("damage"), "armor can absorb the whole hit");
        assertEquals(true, note(absorbed, "STRIKE_RESOLVED").payload().get("hit"));

        var mental = new CombatAttack("hex", "resolve", 0, 1, 4, null, 0, 0, 0, CombatAttack.Defense.RESISTANCE, false, null);
        var guarded = guarding(duel(List.of(mental), new Foe(40, 40, 0, 7, List.of())), "foe");
        var hexed = CombatScheduler.apply(guarded, new TacticalIntent.Strike("hex", "foe"), script(5, 5, 1));
        assertEquals(7, note(hexed, "STRIKE_CALCULATED").payload().get("defense"), "neither Guard nor its stance applies");
        assertEquals("resistance", note(hexed, "STRIKE_CALCULATED").payload().get("defenseKind"));
    }

    /** Damage never takes Health below zero, and the defeat note follows the strike. */
    @Test
    void healthStopsAtZero() {
        var state = duel(List.of(BLADE), new Foe(3, 0, 0, 0, List.of()));
        var plan = CombatScheduler.apply(state, new TacticalIntent.Strike("sword", "foe"), script(10, 10, 6, 4));
        var next = after(state, plan);
        assertEquals(0, next.participant("foe").health());
        assertEquals(3, note(plan, "STRIKE_RESOLVED").payload().get("damage"), "only the Health there was");
        assertEquals(EncounterState.Status.DEFEATED, next.participant("foe").status());
        assertEquals(TacticalRules.Refusal.TARGET,
                TacticalRules.refusal(next, new TacticalIntent.Strike("sword", "foe")), "a defeated target is illegal");
    }

    /** Every refusal of a strike, in the documented order, with nothing to spend. */
    @Test
    void strikesAreLegalOnlyAgainstReachableOpponents() {
        var state = duel(List.of(BLADE), new Foe(40, 0, 0, 0, List.of()));
        assertNull(TacticalRules.refusal(state, new TacticalIntent.Strike("sword", "foe")));
        assertEquals(TacticalRules.Refusal.ATTACK, TacticalRules.refusal(state, new TacticalIntent.Strike("club", "foe")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.Strike("sword", "ghost")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.Strike("sword", "player")));
        assertEquals(TacticalRules.Refusal.ACTION_POINTS,
                TacticalRules.refusal(points(state, 1), new TacticalIntent.Strike("sword", "foe")));
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(atPhase(state, EncounterPhase.ACTOR_END),
                new TacticalIntent.Strike("sword", "foe")));
        var ally = withSide(state, "foe", EncounterTemplate.Side.ALLY);
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(ally, new TacticalIntent.Strike("sword", "foe")),
                "an ally is not a target");
        for (var status : List.of(EncounterState.Status.DEFEATED, EncounterState.Status.ESCAPED,
                EncounterState.Status.SURRENDERED)) {
            assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(withStatus(state, "foe", status),
                    new TacticalIntent.Strike("sword", "foe")), status.name());
        }
    }

    /**
     * Melee needs the same zone; a ranged attack reaches its range, across a blocked edge too. From another zone an
     * obscured zone hides its occupants and full cover stops the shot, partial cover adds its bonus up to the cap;
     * inside the same zone none of that applies.
     */
    @Test
    void reachCoverAndLineOfSight() {
        var bow = new CombatAttack("bow", "finesse", 0, 1, 6, "finesse", 0, 0, 1, CombatAttack.Defense.GUARD, true, null);
        var longbow = new CombatAttack("longbow", "finesse", 0, 1, 6, "finesse", 0, 0, 2, CombatAttack.Defense.GUARD, true, null);
        var state = duel(List.of(BLADE, bow, longbow), new Foe(40, 10, 0, 0, List.of()));
        var mid = at(state, "foe", "mid");
        var space = mid.space();
        var player = mid.player();
        assertEquals(TacticalRules.AttackBlock.OUT_OF_RANGE, TacticalRules.attackBlock(space, player, BLADE, mid.participant("foe")));
        assertNull(TacticalRules.attackBlock(space, player, bow, mid.participant("foe")));
        var far = at(state, "foe", "far");
        assertEquals(TacticalRules.AttackBlock.OUT_OF_RANGE, TacticalRules.attackBlock(far.space(), player, bow, far.participant("foe")));
        assertNull(TacticalRules.attackBlock(far.space(), player, longbow, far.participant("foe")));

        var shelter = at(state, "foe", "bunker");
        assertEquals(TacticalRules.AttackBlock.FULL_COVER,
                TacticalRules.attackBlock(shelter.space(), player, bow, shelter.participant("foe")));
        var fog = at(state, "foe", "fog");
        assertEquals(TacticalRules.AttackBlock.NO_LINE_OF_SIGHT,
                TacticalRules.attackBlock(fog.space(), player, bow, fog.participant("foe")));
        // Inside the same zone the walls and the fog stand between nobody.
        for (String zone : List.of("bunker", "fog", "shrub")) {
            var together = at(at(state, "foe", zone), "player", zone);
            assertNull(TacticalRules.attackBlock(together.space(), together.player(), BLADE, together.participant("foe")), zone);
            assertEquals(10, TacticalRules.defense(together, together.player(), BLADE, together.participant("foe")), zone);
        }
        // A closed passage does not stop a shot: range ignores blocking.
        var closed = at(state, "foe", "gated");
        assertNull(TacticalRules.attackBlock(closed.space(), player, bow, closed.participant("foe")));

        var bush = at(state, "foe", "shrub");
        var target = bush.participant("foe");
        assertEquals(12, TacticalRules.defense(bush, player, bow, target), "partial cover from outside");
        var stance = guarding(bush, "foe");
        assertEquals(14, TacticalRules.defense(stance, player, bow, stance.participant("foe")), "stance and cover add up");
        var capped = withProfile(stance, Map.of("situationalGuardCap", 3));
        assertEquals(13, TacticalRules.defense(capped, player, bow, capped.participant("foe")), "under the situational cap");
    }

    /** Ammunition is spent by the attempt, stack by stack in identity order, and its absence disables the attack. */
    @Test
    void ammunitionIsSpentByTheAttempt() {
        UUID early = new UUID(0, 1);
        UUID late = new UUID(0, 2);
        var caster = new CombatAttack("caster", "finesse", 0, 1, 6, "finesse", 0, 0, 1, CombatAttack.Defense.GUARD, true, "bolts");
        var state = withAmmunition(duel(List.of(caster), new Foe(40, 40, 0, 0, List.of())),
                List.of(new EncounterState.Ammunition(early, "bolts", 1), new EncounterState.Ammunition(late, "bolts", 3)));
        var miss = CombatScheduler.apply(state, new TacticalIntent.Strike("caster", "foe"), script(1, 1));
        assertEquals(List.of(new TacticalPlan.AmmunitionUse(early, "bolts", 1)), miss.ammunition(), "a miss still spends it");
        assertEquals("bolts", note(miss, "STRIKE_RESOLVED").payload().get("ammunition"));

        var none = withAmmunition(state, List.of());
        assertEquals(TacticalRules.Refusal.ATTACK, TacticalRules.refusal(none, new TacticalIntent.Strike("caster", "foe")));
        var lines = (List<?>) TacticalRules.options(none).get("strikes");
        assertEquals("NO_AMMUNITION", ((Map<?, ?>) lines.getFirst()).get("disabled"));
        var armed = (List<?>) TacticalRules.options(state).get("strikes");
        assertNull(((Map<?, ?>) armed.getFirst()).get("disabled"));
        assertEquals(List.of("foe"), ((Map<?, ?>) armed.getFirst()).get("targets"));
    }

    /** The strike options name their targets, cost and the public reason a candidate is disabled. */
    @Test
    void optionsDiscloseAttacks() {
        var state = duel(List.of(BLADE), new Foe(40, 0, 0, 0, List.of()));
        var line = (Map<?, ?>) ((List<?>) TacticalRules.options(state).get("strikes")).getFirst();
        assertEquals("sword", line.get("attackId"));
        assertEquals(2, line.get("cost"));
        assertEquals(List.of("foe"), line.get("targets"));
        assertNull(line.get("disabled"));
        assertEquals("ACTION_POINTS", ((Map<?, ?>) ((List<?>) TacticalRules.options(points(state, 1)).get("strikes"))
                .getFirst()).get("disabled"));
        var away = at(state, "foe", "mid");
        var unreachable = (Map<?, ?>) ((List<?>) TacticalRules.options(away).get("strikes")).getFirst();
        assertEquals("NO_TARGET_IN_REACH", unreachable.get("disabled"));
        assertEquals(List.of(), unreachable.get("targets"));
        var guard = (Map<?, ?>) TacticalRules.options(state).get("guard");
        assertEquals(1, guard.get("cost"));
        assertEquals(true, guard.get("available"));
        var taken = (Map<?, ?>) TacticalRules.options(guarding(state, "player")).get("guard");
        assertEquals(false, taken.get("available"));
        assertEquals(2, ((Map<?, ?>) TacticalRules.options(state).get("useItem")).get("cost"));
        assertEquals(false, ((Map<?, ?>) TacticalRules.options(points(state, 1)).get("useItem")).get("available"));
    }

    /** The Guard action costs its point, happens once per activation and ends when the next activation starts. */
    @Test
    void theGuardActionLastsUntilTheNextActivation() {
        var state = duel(List.of(BLADE), new Foe(40, 0, 0, 0, List.of()));
        var plan = CombatScheduler.apply(state, new TacticalIntent.Guard(), script());
        var guarded = after(state, plan);
        assertEquals(2, guarded.actionPoints());
        assertTrue(guarded.player().guarding());
        assertEquals(EncounterPhase.AWAITING_ACTION, guarded.phase());
        assertEquals(2, note(plan, "GUARD_RAISED").payload().get("bonus"));
        assertEquals(TacticalRules.Refusal.GUARD, TacticalRules.refusal(guarded, new TacticalIntent.Guard()));
        assertEquals(TacticalRules.Refusal.ACTION_POINTS, TacticalRules.refusal(points(state, 0), new TacticalIntent.Guard()));
        // Still up through the player's own end and the round boundary, gone when its next activation starts.
        var next = after(guarded, CombatScheduler.apply(guarded, new TacticalIntent.EndActivation(), script()));
        assertFalse(next.player().guarding());
        assertNull(TacticalRules.refusal(next, new TacticalIntent.Guard()));
    }

    /** The stance protects between activations: an opponent acting after the player's Guard meets the +2. */
    @Test
    void theStanceMeetsTheOpponentsAttack() {
        var cudgel = new CombatAttack("cudgel", "force", 0, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, false, null);
        var state = duel(List.of(BLADE), new Foe(40, 0, 0, 0, List.of(cudgel)));
        var guarded = after(state, CombatScheduler.apply(state, new TacticalIntent.Guard(), script()));
        var plan = CombatScheduler.apply(guarded, new TacticalIntent.EndActivation(), script(5, 5, 1));
        assertEquals(12, note(plan, "STRIKE_CALCULATED").payload().get("defense"));
        var open = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script(5, 5, 1));
        assertEquals(10, note(open, "STRIKE_CALCULATED").payload().get("defense"));
    }

    /** {@code first}: the strike lands before any activation and its price comes out of the player's round-one points. */
    @Test
    void theFirstPolicyStrikesBeforeRoundOneAndChargesLater() throws Exception {
        var state = fixture("close-rush", 24, "dummy");
        var plan = CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), script(1, 1, 10, 10, 6, 6, 6, 4));
        var next = after(state, plan);
        assertEquals("RESOLVED", plan.last().openingStatus());
        assertEquals(1, next.actionPoints(), "paid from the player's own round-one activation");
        assertTrue(next.playerDecision());
        var types = plan.notes().stream().map(TacticalPlan.Note::type).toList();
        assertTrue(types.indexOf("STRIKE_RESOLVED") > types.indexOf("ROUND_STARTED"), types.toString());
        assertTrue(types.indexOf("STRIKE_RESOLVED") < types.indexOf("ACTIVATION_STARTED"), "before the first activation");
        assertEquals(1, types.stream().filter("STRIKE_RESOLVED"::equals).count(), "exactly once");
        assertEquals(true, note(plan, "STRIKE_RESOLVED").payload().get("opening"));
        assertFalse(note(plan, "STRIKE_RESOLVED").payload().containsKey("remainingActionPoints"), "no points in play yet");
        assertEquals(999 - 12, next.participant("dummy").health(), "6 on the d6 + force 2 + strong die 4");
        // The grant itself carries the charge, because points may grow only at an activation start.
        var grant = plan.steps().stream().filter(step -> step.phase() == EncounterPhase.ACTOR_START
                && next.player().rank().equals(step.cursor())).findFirst().orElseThrow();
        assertEquals(1, grant.actionPoints());
    }

    /** {@code initiative}: the strike lands at the player's own slot, after the opponents ahead of it, paid at once. */
    @Test
    void theInitiativePolicyStrikesAtThePlayersSlot() throws Exception {
        var state = fixture("close-brawl", 24, "dummy");
        // Dummy, wall and the allies all outroll the player (modifier 4): they act first, unarmed, and pass.
        var plan = CombatScheduler.apply(state, new TacticalIntent.RollInitiative(),
                script(1, 1, 10, 10, 10, 10, 10, 10, 10, 10, 6, 6, 4, 2));
        var next = after(state, plan);
        assertEquals("RESOLVED", plan.last().openingStatus());
        assertEquals(1, next.actionPoints());
        var types = plan.notes().stream().map(TacticalPlan.Note::type).toList();
        assertTrue(types.lastIndexOf("ACTIVATION_STARTED") < types.indexOf("STRIKE_RESOLVED"), types.toString());
        assertEquals(4, types.stream().filter("ACTIVATION_ENDED"::equals).count(), "the others passed before the slot");
        assertEquals(EncounterPhase.AWAITING_ACTION, next.phase());
        assertEquals(10, ((List<?>) note(plan, "STRIKE_RESOLVED").payload().get("drawOrdinals")).getFirst(),
                "after the ten initiative dice");
    }

    /** A strike that cannot be made at its slot lapses with its public reason, draws nothing and costs nothing. */
    @Test
    void anUnreachableOpeningLapses() throws Exception {
        for (String policy : List.of("yard-duel", "trail-ambush")) {
            var state = fixture(policy, 24, policy.equals("yard-duel") ? "bandit" : "raider");
            var plan = CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), script(9, 9, 1, 1));
            var next = after(state, plan);
            assertEquals("RESOLVED", plan.last().openingStatus(), policy);
            assertEquals(3, next.actionPoints(), policy);
            assertEquals("OUT_OF_RANGE", note(plan, "OPENING_STRIKE_LAPSED").payload().get("reason"), policy);
            assertTrue(plan.notes().stream().noneMatch(n -> n.type().equals("STRIKE_RESOLVED")), policy);
        }
    }

    /** An unarmed player's opening lapses as such, and a player who fell before its slot loses the strike at round end. */
    @Test
    void otherLapses() throws Exception {
        var unarmed = withPlayerAttacks(fixture("close-rush", 24, "dummy"), List.of());
        var plan = CombatScheduler.apply(unarmed, new TacticalIntent.RollInitiative(), script(1, 1, 10, 10));
        assertEquals("NO_ATTACK", note(plan, "OPENING_STRIKE_LAPSED").payload().get("reason"));
        assertEquals(3, after(unarmed, plan).actionPoints());

        // A bandit ahead of the player fells it at one Health before the initiative slot arrives.
        var felled = fixture("close-bandit", 1, "bandit");
        var lost = CombatScheduler.apply(felled, new TacticalIntent.RollInitiative(), script(1, 1, 10, 10, 10, 10, 6, 1));
        var next = after(felled, lost);
        assertEquals("RESOLVED", lost.last().openingStatus());
        assertEquals("ATTACKER_DOWN", note(lost, "OPENING_STRIKE_LAPSED").payload().get("reason"));
        assertEquals(EncounterState.Status.DEFEATED, next.player().status());
        assertEquals(EncounterPhase.ROUND_END, next.phase(), "the schedule stops: no decision is left to take");
        assertEquals(-1, lost.playerHealthDelta());
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(next, new TacticalIntent.Guard()));
    }

    /**
     * The non-player tactic: an unarmed participant passes; an armed one out of reach walks one edge toward the nearest
     * opponent and strikes when it arrives with points left; in reach it strikes once and guards with what is left.
     */
    @Test
    void theTacticApproachesStrikesAndGuards() {
        var cudgel = new CombatAttack("cudgel", "force", 0, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, false, null);
        var close = duel(List.of(BLADE), new Foe(40, 0, 0, 0, List.of(cudgel)));
        var near = CombatScheduler.apply(close, new TacticalIntent.EndActivation(), script(1, 1));
        assertEquals(List.of("STRIKE_RESOLVED", "STRIKE_CALCULATED", "GUARD_RAISED"), actions(near));
        assertEquals(1, near.notes().stream().filter(n -> n.type().equals("STRIKE_RESOLVED")).count(), "one strike per activation");

        var away = at(close, "foe", "mid");
        var walked = CombatScheduler.apply(away, new TacticalIntent.EndActivation(), script(1, 1));
        assertEquals(List.of("PARTICIPANT_MOVED", "STRIKE_RESOLVED", "STRIKE_CALCULATED"), actions(walked));
        assertEquals("ring", after(away, walked).participant("foe").zone());
        assertEquals("mid", note(walked, "PARTICIPANT_MOVED").payload().get("from"));

        var unarmed = duel(List.of(BLADE), new Foe(40, 0, 0, 0, List.of()));
        assertEquals(List.of(), actions(CombatScheduler.apply(unarmed, new TacticalIntent.EndActivation(), script())));

        // Too far to arrive and strike: it walks, then guards with the last point.
        var distant = at(close, "foe", "far");
        var long1 = CombatScheduler.apply(distant, new TacticalIntent.EndActivation(), script());
        assertEquals(List.of("PARTICIPANT_MOVED", "GUARD_RAISED"), actions(long1));
        assertEquals("mid", after(distant, long1).participant("foe").zone());
    }

    /** The tactic never strikes an ally and never picks a defeated target; it prefers the nearest, then roster order. */
    @Test
    void theTacticChoosesAStableLegalTarget() {
        var cudgel = new CombatAttack("cudgel", "force", 0, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, false, null);
        var state = trio(cudgel);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script(1, 1));
        assertEquals("player", note(plan, "STRIKE_RESOLVED").payload().get("targetId"), "same distance: roster order");
        var other = CombatScheduler.apply(at(state, "player", "mid"), new TacticalIntent.EndActivation(), script(1, 1));
        assertEquals("ally", note(other, "STRIKE_RESOLVED").payload().get("targetId"), "the only one in reach");
        // A spear reaches both; the nearer ally comes before the player, who is first in the roster.
        var spear = new CombatAttack("spear", "force", 0, 1, 6, "force", 0, 0, 2, CombatAttack.Defense.GUARD, false, null);
        var both = CombatScheduler.apply(at(trio(spear), "player", "mid"), new TacticalIntent.EndActivation(), script(1, 1));
        assertEquals("ally", note(both, "STRIKE_RESOLVED").payload().get("targetId"), "the nearest comes first");
    }

    /** An item's effects are the commands layer's; the scheduler charges the action and takes over the Health they left. */
    @Test
    void anItemChargesTheActionAndTakesOverHealth() {
        var state = withHealth(duel(List.of(BLADE), new Foe(40, 0, 0, 0, List.of())), "player", 5);
        var own = CombatScheduler.useItem(state, new TacticalIntent.UseItem(null),
                new CombatScheduler.ItemOutcome("salve", 8, null, 0), script(), 0);
        var healed = after(state, own);
        assertEquals(1, healed.actionPoints());
        assertEquals(8, healed.player().health());
        assertEquals(3, own.playerHealthDelta());
        assertEquals(EncounterPhase.AWAITING_ACTION, healed.phase());
        assertEquals("salve", note(own, "COMBAT_ITEM_USED").payload().get("definitionId"));
        assertEquals(TacticalRules.Refusal.ACTION_POINTS, TacticalRules.refusal(points(state, 1), new TacticalIntent.UseItem(null)));

        // An item that fells its user ends the activation and continues the schedule, drawing past the item's own dice.
        var cudgel = new CombatAttack("cudgel", "force", 0, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, false, null);
        var armed = withHealth(duel(List.of(BLADE), new Foe(40, 0, 0, 0, List.of(cudgel))), "player", 1);
        var asked = new ArrayList<int[]>();
        var fatal = CombatScheduler.useItem(armed, new TacticalIntent.UseItem(null),
                new CombatScheduler.ItemOutcome("poison", 0, null, 0), recording(asked), 5);
        assertEquals(EncounterState.Status.DEFEATED, after(armed, fatal).player().status());
        assertEquals(EncounterPhase.ROUND_END, fatal.last().phase());
        assertEquals(-1, fatal.playerHealthDelta());
        assertTrue(asked.isEmpty(), "the foe has no standing opponent left to strike");
    }

    /** Item targets: the player, or a standing ally beside it; never an opponent, an absentee or someone unknown. */
    @Test
    void itemTargetsAreStandingAlliesBeside() {
        var state = trio(BLADE);
        assertNull(TacticalRules.refusal(state, new TacticalIntent.UseItem("ally")));
        assertNull(TacticalRules.refusal(state, new TacticalIntent.UseItem("player")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.UseItem("foe")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.UseItem("ghost")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(at(state, "ally", "mid"), new TacticalIntent.UseItem("ally")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(withStatus(state, "ally", EncounterState.Status.DEFEATED),
                new TacticalIntent.UseItem("ally")), "ordinary healing never revives");
        var healed = CombatScheduler.useItem(withHealth(state, "ally", 2), new TacticalIntent.UseItem("ally"),
                new CombatScheduler.ItemOutcome("bandage", 24, 6, 4), script(), 0);
        assertEquals(6, after(state, healed).participant("ally").health());
        assertEquals(4L, note(healed, "COMBAT_ITEM_USED").payload().get("healed"));
        assertEquals("ally", note(healed, "COMBAT_ITEM_USED").payload().get("targetId"));
    }

    /**
     * Worn gear freezes once: Guard modifiers sum under {@code equipmentGuardCap}, armor under {@code armorMax}, each
     * weapon becomes an attack with the wearer's rank; a form frozen before P11.3 reads back as a sheet without attacks.
     */
    @Test
    void wornGearFreezesWithinItsCaps() throws Exception {
        var rules = EncounterPack.rules(EncounterPack.files());
        var profile = rules.profiles().get("sample:armory");
        var gear = Map.of("buckler", new EncounterGear(2, 0, null, null), "helm", new EncounterGear(2, 3, null, null),
                "mail", new EncounterGear(0, 4, null, null), "axe", new EncounterGear(0, 0, "chop",
                        new CombatAttack("axe", "force", 0, 1, 8, "force", 0, 1, 0, CombatAttack.Defense.GUARD, true, null)));
        var facts = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", 2L, "might", 2L),
                new FrozenEncounter.Pool(8, 8), null, List.of("buckler", "helm", "mail", "axe", "ring"), Set.of(),
                Map.of("chop", 3L));
        var frozen = FrozenEncounter.freeze(ID, rules.templates().get("trail-ambush"), profile, facts, null, gear);
        var player = frozen.player();
        assertEquals(10 + 2 + 3, player.guard(), "2 + 2 capped at +3");
        assertEquals(4, player.armor(), "3 + 4 capped at 4");
        assertEquals(List.of("axe"), player.attacks().stream().map(CombatAttack::id).toList());
        assertEquals(3, player.attacks().getFirst().skill());
        assertEquals(frozen.definition(), FrozenEncounter.freeze(ID, rules.templates().get("trail-ambush"), profile, facts,
                null, gear).definition(), "freezing is pure");

        var form = new java.util.LinkedHashMap<String, Object>(player.canonicalForm());
        assertEquals(player.sheet(), EncounterState.Sheet.fromCanonical(
                tools.jackson.databind.json.JsonMapper.builder().build().readValue(
                        org.rpgengine.rules.CanonicalJson.write(form), Map.class)));
        form.remove("attacks");
        assertEquals(List.of(), EncounterState.Sheet.fromCanonical(form).attacks(), "an older frozen form has no attacks");
        // A participant that cannot attack keeps the form it had before P11.3.
        assertFalse(frozen.participants().get(1).canonicalForm().containsKey("attacks"));
    }

    /** The same state and draw source always give the same plan, the property retried operations rely on. */
    @Test
    void theSameDrawsGiveTheSamePlan() throws Exception {
        var state = fixture("yard-duel", 24, null);
        var seed = new byte[32];
        UUID operation = new UUID(7, 7);
        CombatScheduler.Dice stream = (ordinal, sides) -> DeterministicDraw.value(seed, operation, ordinal, sides);
        assertEquals(CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), stream),
                CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), stream));
        var rolled = after(state, CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), stream));
        var moved = rolled.playerDecision() ? after(rolled, CombatScheduler.apply(rolled,
                new TacticalIntent.MoveZone("yard"), stream)) : rolled;
        assertEquals(CombatScheduler.apply(moved, new TacticalIntent.EndActivation(), stream),
                CombatScheduler.apply(moved, new TacticalIntent.EndActivation(), stream));
    }

    /**
     * The largest roster with the most expensive attacks stays inside every per-operation bound at the worst point, the
     * initiative roll: 18 initiative dice, the opening strike and eight strikes of seven dice each.
     */
    @Test
    void theLargestArmedRosterFitsOneOperation() throws Exception {
        var heavy = new CombatAttack("big", "force", 10, 4, 20, "force", 20, 0, 0, CombatAttack.Defense.GUARD, true, null);
        var base = fixture("close-rush", 999, "dummy");
        var list = new ArrayList<>(base.participants().subList(0, 1));
        var sheet = new EncounterState.Sheet(0, 0, 0, Map.of("force", 6, "finesse", 6, "insight", 6, "resolve", 6), List.of(heavy));
        for (int ordinal = 1; ordinal < 9; ordinal++) {
            list.add(new EncounterState.Combatant(ordinal == 1 ? "dummy" : "rival-" + ordinal, ordinal, null,
                    EncounterTemplate.Side.HOSTILE, "ring", EncounterState.Status.ACTIVE, 6, 12, null, null, 0, 0, 900, 900,
                    false, sheet));
        }
        var state = new EncounterState(base.id(), 0, EncounterPhase.ENTERING, 0, null, null, 0, List.of(), base.profile(),
                base.frozenSpace(), base.escapeZones(), base.actions(), base.opening(), "dummy", "PENDING", list);
        var used = new ArrayList<int[]>();
        var plan = CombatScheduler.apply(state, new TacticalIntent.RollInitiative(), (ordinal, sides) -> {
            used.add(new int[] {ordinal, sides});
            return ordinal < 2 ? 1 : sides;
        });
        // The player's opening sword draws four; every rival's strike lands strong with four damage dice, seven in all.
        assertEquals(18 + 4 + 8 * 7, used.size());
        assertTrue(used.size() <= DeterministicDraw.MAX_DRAWS, "draws " + used.size());
        assertTrue(plan.steps().size() <= TacticalPlan.MAX_STEPS, "steps " + plan.steps().size());
        assertTrue(plan.notes().size() <= 128, "events " + plan.notes().size());
    }

    /**
     * Fifty seeded fights over the fixture with armed participants on both sides, each intention chosen by a seeded
     * random walk over the legal options (seed 20261030). At every step Health stays in bounds,
     * points stay within the allowance, a defeated participant stays defeated and never starts an activation, every draw
     * lies on its die and every plan respects its bounds; replaying a whole fight from its recorded seed gives the same
     * plans. A fight ends when the player can no longer decide or at the round cap.
     */
    @Test
    void seededFightsKeepEveryInvariant() throws Exception {
        var random = new java.util.Random(20261030L);
        int operations = 0;
        int defeats = 0;
        for (int fight = 0; fight < 50; fight++) {
            long seed = random.nextLong();
            var first = walk(seed);
            assertEquals(first, walk(seed), "the same seed replays the same fight");
            operations += first.size();
            defeats += first.stream().flatMap(plan -> plan.notes().stream())
                    .anyMatch(n -> n.type().equals("PARTICIPANT_DEFEATED")) ? 1 : 0;
        }
        assertTrue(operations > 200, "the walk must exercise the fights: " + operations);
        assertTrue(defeats > 0, "some fight must reach a defeat");
    }

    /** One seeded fight, checking the invariants at every step; returns its plans. */
    private static List<TacticalPlan> walk(long seed) throws Exception {
        var random = new java.util.Random(seed);
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var state = fixture("yard-duel", 8 + random.nextInt(17), null);
        var plans = new ArrayList<TacticalPlan>();
        var defeated = new java.util.HashSet<String>();
        for (int step = 0; step < 300; step++) {
            TacticalIntent intent;
            if (state.phase() == EncounterPhase.ENTERING) {
                intent = new TacticalIntent.RollInitiative();
            } else if (!state.playerDecision()) {
                break;
            } else {
                intent = choose(state, random);
            }
            UUID operation = new UUID(seed, step);
            var plan = CombatScheduler.apply(state, intent, (ordinal, sides) -> {
                int value = DeterministicDraw.value(bytes, operation, ordinal, sides);
                assertTrue(value >= 1 && value <= sides);
                return value;
            });
            plans.add(plan);
            assertTrue(plan.steps().size() <= TacticalPlan.MAX_STEPS);
            for (var note : plan.notes()) {
                if (note.type().equals("ACTIVATION_STARTED")) {
                    assertFalse(defeated.contains(note.payload().get("participantId")), "a defeated participant takes no activation");
                }
                if (note.type().equals("PARTICIPANT_DEFEATED")) {
                    defeated.add((String) note.payload().get("participantId"));
                }
            }
            for (var row : plan.steps()) {
                if (row.actionPoints() != null) {
                    assertTrue(row.actionPoints() >= 0 && row.actionPoints() <= state.profile().actionPoints());
                }
            }
            state = after(state, plan);
            for (var participant : state.participants()) {
                assertTrue(participant.health() >= 0 && participant.health() <= participant.healthMax());
                assertEquals(participant.health() == 0, participant.status() == EncounterState.Status.DEFEATED, participant.id());
                assertFalse(participant.guarding() && participant.status() != EncounterState.Status.ACTIVE);
            }
        }
        return plans;
    }

    /** A random legal intention of the player's decision: any listed option, weighted toward using the turn. */
    private static TacticalIntent choose(EncounterState state, java.util.Random random) {
        var candidates = new ArrayList<TacticalIntent>();
        for (var zone : state.space().zones().keySet()) {
            candidates.add(new TacticalIntent.MoveZone(zone));
        }
        for (var participant : state.participants()) {
            for (var attack : state.player().sheet().attacks()) {
                candidates.add(new TacticalIntent.Strike(attack.id(), participant.id()));
                candidates.add(new TacticalIntent.Strike(attack.id(), participant.id()));
            }
        }
        candidates.add(new TacticalIntent.Guard());
        candidates.add(new TacticalIntent.EndActivation());
        var legal = candidates.stream().filter(intent -> TacticalRules.refusal(state, intent) == null).toList();
        return legal.get(random.nextInt(legal.size()));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The first note of a type. */
    private static TacticalPlan.Note note(TacticalPlan plan, String type) {
        return plan.notes().stream().filter(n -> n.type().equals(type)).findFirst().orElseThrow(() -> new AssertionError(type));
    }

    /** The notes of a non-player's activation other than its bookkeeping. */
    private static List<String> actions(TacticalPlan plan) {
        return plan.notes().stream().map(TacticalPlan.Note::type)
                .filter(type -> !List.of("ACTIVATION_ENDED", "ACTIVATION_STARTED", "ROUND_ENDED", "ROUND_STARTED").contains(type))
                .toList();
    }

    /** Draws in order from a fixed script; running out is a test failure, so a surplus draw cannot pass unseen. */
    private static CombatScheduler.Dice script(int... values) {
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

    /** Scripted draws that also record each ordinal and die. */
    private static CombatScheduler.Dice recording(List<int[]> asked, int... values) {
        var inner = script(values);
        return (ordinal, sides) -> {
            asked.add(new int[] {ordinal, sides});
            return inner.value(ordinal, sides);
        };
    }

    /** Entry state of a real fixture template with the surveyor's worn items, skills and a given Health pool. */
    private static EncounterState fixture(String template, int health, String openingTarget) throws Exception {
        var rules = template.equals("close-bandit") ? closeBandit() : EncounterPack.rules(EncounterPack.files());
        var facts = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("awareness", 2L, "might", 2L),
                new FrozenEncounter.Pool(health, Math.max(health, 24)), null, List.of("cloak", "sword"), Set.of(),
                Map.of("bladework", 2L));
        var frozen = FrozenEncounter.freeze(ID, rules.templates().get(template), rules.profiles().get("sample:armory"), facts,
                openingTarget, rules.gear());
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
                frozen.zones(), frozen.escapeZones(), frozen.actions(), frozen.opening(), openingTarget,
                openingTarget == null ? "NONE" : "PENDING", list, List.of(), frozen.effects(), 0);
    }

    /** The fixture rules with one more template: the armed bandit next to the player, for a strike before the player's slot. */
    private static CombatRules closeBandit() throws Exception {
        var files = new TreeMap<>(EncounterPack.files());
        String encounters = new String(files.get("encounters.json"), java.nio.charset.StandardCharsets.UTF_8);
        int end = encounters.lastIndexOf("]}");
        String added = """
                ,{"id":"close-bandit","kind":"encounter","data":{"name":"Close Bandit","location":"trailhead",
                  "zones":[{"id":"ring","name":"Ring"}],"playerZone":"ring",
                  "participants":[{"id":"bandit","name":"Bandit","side":"hostile","zone":"ring",
                    "stats":{"health":14,"guard":12,"resistance":11,"force":1,"finesse":1,"insight":1,"resolve":1},
                    "attacks":[{"id":"cudgel","attribute":"force","damage":{"count":1,"sides":6}}]}],
                  "objectives":[{"id":"last","kind":"survive","rounds":3}]}}""";
        files.put("encounters.json", (encounters.substring(0, end) + added + encounters.substring(end))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return EncounterPack.rules(files);
    }

    /** What the writer stores after a plan: the last step, the updated participants and the spent ammunition. */
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
        var stacks = new ArrayList<EncounterState.Ammunition>();
        for (var stack : state.ammunition()) {
            int spent = plan.ammunition().stream().filter(use -> use.instance().equals(stack.instance()))
                    .mapToInt(TacticalPlan.AmmunitionUse::quantity).sum();
            if (stack.quantity() > spent) {
                stacks.add(new EncounterState.Ammunition(stack.instance(), stack.definition(), stack.quantity() - spent));
            }
        }
        var last = plan.last();
        return new EncounterState(state.id(), state.rowVersion() + plan.steps().size(), last.phase(), last.round(),
                last.cursor(), last.actionPoints(), last.speechUsed(), last.passages(), state.profile(),
                state.frozenSpace(), state.escapeZones(), state.actions(), state.opening(), state.openingTarget(),
                last.openingStatus(), list, stacks, state.effects(), last.effectsApplied());
    }

    /** The opponent of {@link #duel}. */
    private record Foe(int health, int guard, int armor, int resistance, List<CombatAttack> attacks) {
    }

    /**
     * The player (force 2, finesse 2, Guard 10, 24 Health, three points, acting first) and one foe, both in zone
     * {@code ring} of a space with one zone per targeting rule, at the player's decision of round one.
     */
    private static EncounterState duel(List<CombatAttack> attacks, Foe foe) {
        var sheet = new EncounterState.Sheet(10, 10, 0, Map.of("force", 2, "finesse", 2, "insight", 2, "resolve", 2), attacks);
        var player = new EncounterState.Combatant("player", 0, PLAYER, EncounterTemplate.Side.PLAYER, "ring",
                EncounterState.Status.ACTIVE, 2, 4, 20, 0, 1, 0, 24, 24, false, sheet);
        var other = new EncounterState.Combatant("foe", 1, null, EncounterTemplate.Side.HOSTILE, "ring",
                EncounterState.Status.ACTIVE, 1, 2, 10, 1, 1, 0, foe.health(), foe.health(), false,
                new EncounterState.Sheet(foe.guard(), foe.resistance(), foe.armor(),
                        Map.of("force", 1, "finesse", 1, "insight", 1, "resolve", 1), foe.attacks()));
        return decision(space(), List.of(player, other));
    }

    /** The player, a foe with an attack and an ally beside the player, all in {@code ring}. */
    private static EncounterState trio(CombatAttack attack) {
        var base = duel(List.of(BLADE), new Foe(40, 0, 0, 0, List.of(attack)));
        var ally = new EncounterState.Combatant("ally", 2, null, EncounterTemplate.Side.ALLY, "ring",
                EncounterState.Status.ACTIVE, 1, 2, 9, 2, 1, 0, 9, 9, false,
                new EncounterState.Sheet(11, 8, 0, Map.of("force", 0, "finesse", 1, "insight", 1, "resolve", 0), List.of()));
        var list = new ArrayList<>(base.participants());
        list.add(ally);
        return decision(space(), list);
    }

    /** The default profile at the player's decision of round one over these participants. */
    private static EncounterState decision(ZoneGraph space, List<EncounterState.Combatant> participants) {
        var profile = CombatProfile.resolve("pack", Map.of("force", "a", "finesse", "b", "insight", "c", "resolve", "d"), "hp",
                null, Map.of());
        return new EncounterState(ID, 5, EncounterPhase.AWAITING_ACTION, 1, 0, 3, 0, List.of(), profile, space, Set.of(),
                List.of(), OpeningPolicy.INITIATIVE, null, "NONE", participants);
    }

    /** A hub whose neighbours each show one targeting rule, with a blocked edge to {@code gated}. */
    private static ZoneGraph space() {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        zones.put("ring", zone("ring", ZoneGraph.Cover.NONE, false));
        zones.put("mid", zone("mid", ZoneGraph.Cover.NONE, false));
        zones.put("far", zone("far", ZoneGraph.Cover.NONE, false));
        zones.put("bunker", zone("bunker", ZoneGraph.Cover.FULL, false));
        zones.put("fog", zone("fog", ZoneGraph.Cover.NONE, true));
        zones.put("shrub", zone("shrub", ZoneGraph.Cover.PARTIAL, false));
        zones.put("gated", zone("gated", ZoneGraph.Cover.NONE, false));
        var edges = new ArrayList<ZoneGraph.Edge>();
        for (String neighbour : List.of("mid", "bunker", "fog", "shrub")) {
            edges.add(new ZoneGraph.Edge("ring", neighbour, false, false));
            edges.add(new ZoneGraph.Edge(neighbour, "ring", false, false));
        }
        edges.add(new ZoneGraph.Edge("ring", "gated", false, true));
        edges.add(new ZoneGraph.Edge("gated", "ring", false, true));
        edges.add(new ZoneGraph.Edge("mid", "far", false, false));
        edges.add(new ZoneGraph.Edge("far", "mid", false, false));
        return new ZoneGraph(zones, edges);
    }

    private static ZoneGraph.Zone zone(String id, ZoneGraph.Cover cover, boolean obscured) {
        return new ZoneGraph.Zone(id, Set.of(), cover, obscured, 0);
    }

    /** Rebuild a state with changed participants, keeping everything else. */
    private static EncounterState rebuilt(EncounterState s, java.util.function.UnaryOperator<EncounterState.Combatant> change) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(),
                s.passages(), s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(),
                s.openingStatus(), s.participants().stream().map(change).toList(), s.ammunition());
    }

    /** The same participant with chosen mutable facts. */
    private static EncounterState.Combatant copy(EncounterState.Combatant p, EncounterTemplate.Side side, String zone,
            EncounterState.Status status, int health, boolean guarding, EncounterState.Sheet sheet) {
        return new EncounterState.Combatant(p.id(), p.ordinal(), p.actorId(), side, zone, status, p.finesse(),
                p.initiativeModifier(), p.initiative(), p.rank(), p.reactions(), p.rowVersion(), health, p.healthMax(), guarding, sheet);
    }

    private static EncounterState at(EncounterState s, String id, String zone) {
        return rebuilt(s, p -> !p.id().equals(id) ? p : copy(p, p.side(), zone, p.status(), p.health(), p.guarding(), p.sheet()));
    }

    private static EncounterState guarding(EncounterState s, String id) {
        return rebuilt(s, p -> !p.id().equals(id) ? p : copy(p, p.side(), p.zone(), p.status(), p.health(), true, p.sheet()));
    }

    private static EncounterState withStatus(EncounterState s, String id, EncounterState.Status status) {
        return rebuilt(s, p -> !p.id().equals(id) ? p : copy(p, p.side(), p.zone(), status, p.health(), p.guarding(), p.sheet()));
    }

    private static EncounterState withSide(EncounterState s, String id, EncounterTemplate.Side side) {
        return rebuilt(s, p -> !p.id().equals(id) ? p : copy(p, side, p.zone(), p.status(), p.health(), p.guarding(), p.sheet()));
    }

    private static EncounterState withHealth(EncounterState s, String id, int health) {
        return rebuilt(s, p -> !p.id().equals(id) ? p : copy(p, p.side(), p.zone(), p.status(), health, p.guarding(), p.sheet()));
    }

    private static EncounterState withPlayerAttacks(EncounterState s, List<CombatAttack> attacks) {
        return rebuilt(s, p -> !p.player() ? p : copy(p, p.side(), p.zone(), p.status(), p.health(), p.guarding(),
                new EncounterState.Sheet(p.sheet().guard(), p.sheet().resistance(), p.sheet().armor(), p.sheet().roles(), attacks)));
    }

    private static EncounterState withAmmunition(EncounterState s, List<EncounterState.Ammunition> stacks) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(),
                s.passages(), s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(),
                s.openingStatus(), s.participants(), stacks);
    }

    private static EncounterState withProfile(EncounterState s, Map<String, Integer> overrides) {
        var profile = CombatProfile.resolve("pack", Map.of("force", "a", "finesse", "b", "insight", "c", "resolve", "d"), "hp",
                null, overrides);
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(),
                s.passages(), profile, s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(),
                s.openingStatus(), s.participants(), s.ammunition());
    }

    private static EncounterState points(EncounterState s, int points) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), points, s.speechUsed(),
                s.passages(), s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(),
                s.openingStatus(), s.participants(), s.ammunition());
    }

    private static EncounterState atPhase(EncounterState s, EncounterPhase phase) {
        return new EncounterState(s.id(), s.rowVersion(), phase, s.round(), s.cursor(), s.actionPoints(), s.speechUsed(),
                s.passages(), s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(),
                s.openingStatus(), s.participants(), s.ammunition());
    }
}
