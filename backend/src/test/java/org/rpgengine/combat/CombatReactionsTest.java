package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.CombatEffect.Clock;
import org.rpgengine.combat.CombatEffect.Phase;
import org.rpgengine.combat.CombatEffect.Prevents;
import org.rpgengine.combat.CombatEffect.Stacking;
import org.rpgengine.combat.CombatEffects.Active;
import org.rpgengine.combat.CombatReaction.Kind;
import org.rpgengine.combat.CombatReaction.Policy;
import org.rpgengine.combat.CombatReaction.Trigger;
import org.rpgengine.commands.ResolvedCommit;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.DeterministicDraw;
import org.rpgengine.rules.ZoneGraph;

/**
 * Pure acceptance of P11.4 on {@link CombatScheduler} and {@link TacticalRules}: Brace and its one stacking key, policies,
 * visibility, the depth limit, simultaneous order, resource costs, every effect phase and clock, prevention, abilities with
 * their limits and the bounds of the largest fight. Dice are scripted, so a surplus draw fails the case; the PostgreSQL
 * path is {@code ReactionCommandsTest}.
 */
class CombatReactionsTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final CombatAttack BLADE = new CombatAttack("blade", "force", 2, 1, 6, "force", 0, 0, 0,
            CombatAttack.Defense.GUARD, true, null);
    private static final CombatAttack BOLT = new CombatAttack("bolt", "force", 2, 1, 6, "force", 0, 0, 1,
            CombatAttack.Defense.GUARD, true, null);
    private static final CombatAttack HEX = new CombatAttack("hex", "force", 2, 1, 6, "force", 0, 0, 0,
            CombatAttack.Defense.RESISTANCE, true, null);
    private static final CombatAttack CLAW = new CombatAttack("claw", "force", 1, 1, 6, "force", 0, 0, 0,
            CombatAttack.Defense.GUARD, false, null);
    private static final CombatReaction RIPOSTE = new CombatReaction("riposte", Trigger.HIT, 0, 0, false,
            CombatReaction.Attacks.ANY, Kind.COUNTER, 0, null, null, false);

    /** Construct a stateless fixture. */
    CombatReactionsTest() {
    }

    // ---- Brace and its stacking key ------------------------------------------------------------------------------

    /** Brace adds 2 against one attack and spends the round's one reaction; the budget refreshes once per round. */
    @Test
    void braceMeetsOneAttackPerRound() {
        var state = fight(values("actionPoints", 6), player().attacks(BLADE), foe("foe").guard(12).brace(Policy.FIRST));
        var first = CombatScheduler.apply(state, strike("blade", "foe"), script(4, 4));
        assertEquals(14, note(first, "STRIKE_CALCULATED").payload().get("defense"), "12 + Brace");
        assertEquals(false, note(first, "STRIKE_RESOLVED").payload().get("hit"), "12 misses a braced 14");
        var shown = note(first, "REACTION_RESOLVED");
        assertEquals("foe", shown.payload().get("participantId"));
        assertEquals("brace", shown.payload().get("reactionId"));
        assertFalse(shown.internal());
        assertFalse(shown.payload().containsKey("defense"), "the public record keeps the foe's Guard hidden");
        state = after(state, first);
        assertEquals(0, state.participant("foe").reactions());

        var second = CombatScheduler.apply(state, strike("blade", "foe"), script(4, 4, 1));
        assertEquals(12, note(second, "STRIKE_CALCULATED").payload().get("defense"), "the budget is spent");
        assertEquals(0, count(second, "REACTION_RESOLVED"));
        state = after(state, second);

        var ended = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        state = after(state, ended);
        assertEquals(2, state.round());
        assertEquals(1, state.participant("foe").reactions(), "refreshed once when the round opened");
    }

    /** The Guard action holds the stacking key: Brace would add nothing, so it is skipped and spends nothing. */
    @Test
    void aRedundantBraceIsSkipped() {
        var state = fight(values(), player().attacks(BLADE), foe("foe").guard(12).brace(Policy.FIRST).guarding());
        var plan = CombatScheduler.apply(state, strike("blade", "foe"), script(1, 1));
        assertEquals(14, note(plan, "STRIKE_CALCULATED").payload().get("defense"), "2 from the stance, never 4");
        assertEquals(0, count(plan, "REACTION_RESOLVED"));
        assertEquals(1, after(state, plan).participant("foe").reactions());
    }

    /** At the situational cap Brace raises nothing either: cover already fills a cap of 2. */
    @Test
    void braceRespectsTheSituationalCap() {
        var state = fight(values("situationalGuardCap", 2), player().attacks(BOLT),
                foe("foe").guard(12).brace(Policy.FIRST).zone("shade"));
        var plan = CombatScheduler.apply(state, strike("bolt", "foe"), script(1, 1));
        assertEquals(14, note(plan, "STRIKE_CALCULATED").payload().get("defense"), "12 + 2 cover, capped");
        assertEquals(0, count(plan, "REACTION_RESOLVED"));

        var roomy = fight(values(), player().attacks(BOLT), foe("foe").guard(12).brace(Policy.FIRST).zone("shade"));
        var both = CombatScheduler.apply(roomy, strike("bolt", "foe"), script(1, 1));
        assertEquals(16, note(both, "STRIKE_CALCULATED").payload().get("defense"), "cover is a separate source and combines");
        assertEquals(1, count(both, "REACTION_RESOLVED"));
    }

    /** Resistance is not Guard: neither stance nor Brace helps, so the reaction is not spent. */
    @Test
    void braceDoesNotMeetAResistanceAttack() {
        var state = fight(values(), player().attacks(HEX), foe("foe").resistance(9).brace(Policy.FIRST));
        var plan = CombatScheduler.apply(state, strike("hex", "foe"), script(1, 1));
        assertEquals(9, note(plan, "STRIKE_CALCULATED").payload().get("defense"));
        assertEquals(0, count(plan, "REACTION_RESOLVED"));
    }

    /** A higher declared bonus wins under the one key, and the lower reaction that follows is skipped unspent. */
    @Test
    void aProtectiveReactionSharesTheStackingKey() {
        var cover = new CombatReaction("cover", Trigger.ATTACKED, 5, 0, true, CombatReaction.Attacks.ANY, Kind.GUARD, 3, null,
                null, false);
        var state = fight(values(), player().guard(10).brace(Policy.FIRST), foe("foe").attacks(CLAW),
                ally("ally").react(cover, Policy.FIRST));
        // The foe acts before the ally here: rank order is player, foe, ally.
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script(1, 1));
        assertEquals(13, note(plan, "STRIKE_RESOLVED").payload().get("defense"), "10 + 3, the higher bonus and not 3 + 2");
        assertEquals(1, count(plan, "REACTION_RESOLVED"));
        assertEquals("cover", note(plan, "REACTION_RESOLVED").payload().get("reactionId"));
        assertEquals("REDUNDANT", line(plan, "REACTION_SKIPPED").get("reason"), "the player's Brace would add nothing and spends nothing");
    }

    // ---- policies ------------------------------------------------------------------------------------------------

    /** A policy decides, from the reactor's own facts, whether its reaction fires. */
    @Test
    void policiesDecideBeforeTheRoll() {
        assertEquals(0, count(CombatScheduler.apply(fight(values(), player().attacks(BLADE), foe("foe").brace(Policy.OFF)),
                strike("blade", "foe"), script(1, 1)), "REACTION_RESOLVED"), "off");
        var atHalf = new Policy(Policy.Mode.HEALTH_AT_MOST, 50);
        assertEquals(0, count(CombatScheduler.apply(fight(values(), player().attacks(BLADE),
                foe("foe").health(13, 24).brace(atHalf)), strike("blade", "foe"), script(1, 1)), "REACTION_RESOLVED"), "54%");
        assertEquals(1, count(CombatScheduler.apply(fight(values(), player().attacks(BLADE),
                foe("foe").health(12, 24).brace(atHalf)), strike("blade", "foe"), script(1, 1)), "REACTION_RESOLVED"), "50%");
        var focused = new Policy(Policy.Mode.FOCUS_AT_LEAST, 2);
        assertEquals(0, count(CombatScheduler.apply(fight(values(), player().attacks(BLADE),
                foe("foe").focus(1, 4).brace(focused)), strike("blade", "foe"), script(1, 1)), "REACTION_RESOLVED"));
        assertEquals(1, count(CombatScheduler.apply(fight(values(), player().attacks(BLADE),
                foe("foe").focus(2, 4).brace(focused)), strike("blade", "foe"), script(1, 1)), "REACTION_RESOLVED"));
    }

    /** A participant without a stored policy, like one frozen before this release, does not react. */
    @Test
    void noStoredPolicyMeansNoReaction() {
        var state = fight(values(), player().attacks(BLADE), foe("foe").guard(12));
        assertEquals(0, count(CombatScheduler.apply(state, strike("blade", "foe"), script(1, 1)), "REACTION_RESOLVED"));
        assertEquals(Policy.OFF, state.participant("foe").policy("brace"));
    }

    /** The player sets a policy before the first round or at its own decision, at no cost, and never in between. */
    @Test
    void aPolicyChangesOnlyAtAnOwnDecision() {
        var off = new TacticalIntent.SetReactionPolicy("brace", Policy.OFF);
        var state = fight(values(), player().attacks(BLADE).brace(Policy.FIRST), foe("foe"));
        assertNull(TacticalRules.refusal(state, off));
        var plan = CombatScheduler.apply(state, off, script());
        var changed = after(state, plan);
        assertEquals(Policy.OFF, changed.player().policy("brace"));
        assertEquals(3, changed.actionPoints(), "a control command costs nothing");
        assertEquals(state.phase(), changed.phase());
        assertTrue(note(plan, "REACTION_POLICY_SET").byPlayer());

        assertEquals(TacticalRules.Refusal.REACTION, TacticalRules.refusal(state,
                new TacticalIntent.SetReactionPolicy("parry", Policy.OFF)));
        var theirs = withPhase(state, EncounterPhase.ACTOR_END);
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(theirs, off), "not after seeing someone's roll");
        var entering = new EncounterState(ID, 0, EncounterPhase.ENTERING, 0, null, null, 0, List.of(), state.profile(),
                state.frozenSpace(), Set.of(), List.of(), OpeningPolicy.INITIATIVE, null, "NONE", state.participants());
        assertNull(TacticalRules.refusal(entering, off), "before any roll");
        assertEquals(EncounterPhase.ENTERING, after(entering, CombatScheduler.apply(entering, off, script())).phase());
    }

    /** An attack from an obscured zone the reactor is outside of is not a visible attack: no Brace answers it. */
    @Test
    void anUnseenAttackIsNotBraced() {
        var state = fight(values(), player().guard(10).brace(Policy.FIRST), foe("foe").attacks(BOLT).zone("murk"));
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script(1, 1));
        assertEquals(10, note(plan, "STRIKE_RESOLVED").payload().get("defense"));
        assertEquals(0, count(plan, "REACTION_RESOLVED"));
    }

    // ---- depth, order and resources --------------------------------------------------------------------------------

    /** A counter is one reaction and provokes none: the player's Brace and riposte stay out of it. */
    @Test
    void reactionsNeverChain() {
        var state = fight(values(), player().attacks(BLADE).guard(10).brace(Policy.FIRST).react(RIPOSTE, Policy.FIRST),
                foe("foe").guard(10).attacks(CLAW).react(RIPOSTE, Policy.FIRST));
        // Player 9 + 9 + 4 = 22 is a strong hit (die 3, strong die 2); the foe's counter 9 + 9 + 2 = 20 hits (die 2).
        var plan = CombatScheduler.apply(state, strike("blade", "foe"), script(9, 9, 3, 2, 9, 9, 2));
        var strikes = notes(plan, "STRIKE_RESOLVED");
        assertEquals(2, strikes.size());
        assertEquals("foe", strikes.get(1).payload().get("participantId"));
        assertEquals(true, strikes.get(1).payload().get("reaction"));
        assertEquals(0, strikes.get(1).payload().get("actionPoints"), "paid by the reaction, not in points");
        assertFalse(strikes.get(1).payload().containsKey("remainingActionPoints"));
        assertEquals(10, strikes.get(1).payload().get("defense"), "the player's Brace did not answer a reaction");
        assertEquals(1, count(plan, "REACTION_RESOLVED"));
        assertEquals("foe", note(plan, "REACTION_RESOLVED").payload().get("participantId"));
        var after = after(state, plan);
        assertEquals(1, after.player().reactions());
        assertEquals(0, after.participant("foe").reactions());
        assertEquals(1, after.actionPoints(), "the player paid only its own strike");
        assertEquals(List.of("STRIKE_RESOLVED", "STRIKE_CALCULATED", "REACTION_RESOLVED", "STRIKE_RESOLVED", "STRIKE_CALCULATED"),
                plan.notes().stream().map(TacticalPlan.Note::type).toList(), "the attack's record comes before its answer");
    }

    /** A counter can fell the player during its own action: the activation ends and the schedule goes on. */
    @Test
    void aCounterCanFellThePlayer() {
        var heavy = new CombatAttack("heavy", "force", 1, 1, 6, "force", 20, 0, 0, CombatAttack.Defense.GUARD, false, null);
        var state = fight(values(), player().attacks(BLADE).guard(10).health(5, 24),
                foe("foe").guard(0).health(40, 40).attacks(heavy).react(RIPOSTE, Policy.FIRST));
        var plan = CombatScheduler.apply(state, strike("blade", "foe"), script(9, 9, 3, 2, 9, 9, 2));
        var after = after(state, plan);
        assertEquals(0, after.player().health());
        assertEquals(EncounterState.Status.DEFEATED, after.player().status());
        assertEquals(EncounterPhase.ROUND_END, after.phase(), "no decision is left for a player who fell");
        assertEquals(-5, plan.playerHealthDelta());
    }

    /** Simultaneous reactions run by declared priority, then initiative order, then participant ID. */
    @Test
    void simultaneousReactionsHaveOneOrder() {
        CombatReaction low = snap(0);
        CombatReaction high = snap(5);
        var state = fight(values(), player().guard(40),
                foe("zed").guard(10).attacks(CLAW).react(low, Policy.FIRST),
                foe("amy").guard(10).attacks(CLAW).react(low, Policy.FIRST),
                foe("rex").guard(10).attacks(CLAW).react(high, Policy.FIRST));
        var plan = CombatScheduler.apply(state, new TacticalIntent.MoveZone("mid"), script(1, 1, 1, 1, 1, 1));
        assertEquals(List.of("rex", "zed", "amy"), notes(plan, "REACTION_RESOLVED").stream()
                .map(note -> note.payload().get("participantId")).toList(), "priority first, then rank, not the ID alone");
        assertEquals("mid", after(state, plan).player().zone(), "a mover who survives arrives");
    }

    /** Leaving a zone provokes only a reaction that declares it: Brace and a plain opponent do nothing. */
    @Test
    void aMoveProvokesOnlyWhatDeclaresIt() {
        var state = fight(values(), player().guard(40), foe("foe").guard(10).attacks(CLAW).brace(Policy.FIRST));
        var plan = CombatScheduler.apply(state, new TacticalIntent.MoveZone("mid"), script());
        assertEquals(0, count(plan, "REACTION_RESOLVED"), "leaving a zone creates no universal free attack");
        assertEquals("mid", after(state, plan).player().zone());
    }

    /** A leaving player felled by a declared counter does not arrive. */
    @Test
    void aFelledMoverDoesNotArrive() {
        var heavy = new CombatAttack("heavy", "force", 1, 1, 6, "force", 20, 0, 0, CombatAttack.Defense.GUARD, false, null);
        var state = fight(values(), player().guard(0).health(3, 24), foe("foe").guard(10).attacks(heavy).react(snap(0), Policy.FIRST));
        var plan = CombatScheduler.apply(state, new TacticalIntent.MoveZone("mid"), script(5, 5, 1));
        var after = after(state, plan);
        assertEquals("ring", after.player().zone());
        assertEquals(EncounterState.Status.DEFEATED, after.player().status());
        assertEquals(0, count(plan, "PARTICIPANT_MOVED"));
    }

    /** Lacking Focus skips a reaction without inventing a cost; having it pays, in the player's own pool. */
    @Test
    void reactionsPayTheirFocusOrAreSkipped() {
        var costly = new CombatReaction("parry", Trigger.ATTACKED, 0, 2, false, CombatReaction.Attacks.ANY, Kind.GUARD, 3, null, null,
                false);
        var poor = fight(values(), player().guard(10).focus(1, 4).react(costly, Policy.FIRST), foe("foe").attacks(CLAW));
        var skipped = CombatScheduler.apply(poor, new TacticalIntent.EndActivation(), script(1, 1));
        assertEquals(10, note(skipped, "STRIKE_RESOLVED").payload().get("defense"));
        assertEquals(0, skipped.playerFocusDelta());
        assertEquals("INSUFFICIENT_RESOURCES", line(skipped, "REACTION_SKIPPED").get("reason"));
        assertEquals(0, count(skipped, "REACTION_RESOLVED"), "a skipped reaction spends nothing");

        var rich = fight(values(), player().guard(10).focus(3, 4).react(costly, Policy.FIRST), foe("foe").attacks(CLAW));
        var paid = CombatScheduler.apply(rich, new TacticalIntent.EndActivation(), script(1, 1));
        assertEquals(13, note(paid, "STRIKE_RESOLVED").payload().get("defense"));
        assertEquals(-2, paid.playerFocusDelta());
        assertEquals(1, after(rich, paid).player().focus());

        // An opponent's Focus stays hidden: its skipped reaction leaves no public trace, and its own pool pays when it fires.
        var foePoor = fight(values(), player().attacks(BLADE), foe("foe").guard(10).focus(1, 4).react(costly, Policy.FIRST));
        var quiet = CombatScheduler.apply(foePoor, strike("blade", "foe"), script(1, 1));
        assertEquals(0, count(quiet, "EFFECTS_RESOLVED"));
        var foeRich = fight(values(), player().attacks(BLADE), foe("foe").guard(10).focus(3, 4).react(costly, Policy.FIRST));
        var fired = after(foeRich, CombatScheduler.apply(foeRich, strike("blade", "foe"), script(1, 1)));
        assertEquals(1, fired.participant("foe").focus());
    }

    /** Absorption from a reaction meets this blow first and does not wear a lasting shield it did not need. */
    @Test
    void aReactionAbsorbsOnlyTheBlowItAnswers() {
        var ward = effect("ward", Clock.PERSISTENT, 0, e -> e.absorption = 10);
        var sidestep = new CombatReaction("sidestep", Trigger.HIT, 0, 0, false, CombatReaction.Attacks.ANY, Kind.ABSORB, 4, null, null,
                false);
        var state = library(fight(values(), player().attacks(BLADE),
                foe("foe").guard(0).health(40, 40).react(sidestep, Policy.FIRST).holding(active(ward, 1, 10))), ward);
        // 5 + 5 + 4 = 14, margin 14: a strong hit, die 2 and strong die 3: 2 + 2 + 3 = 7; 4 absorbed, 3 from the shield.
        var plan = CombatScheduler.apply(state, strike("blade", "foe"), script(5, 5, 2, 3));
        var after = after(state, plan);
        assertEquals(40, after.participant("foe").health());
        assertEquals(7, after.participant("foe").effects().getFirst().absorption(), "only what the reaction did not take");
    }

    // ---- phases, clocks and stacking -------------------------------------------------------------------------------

    /** Each declared phase fires exactly once for its trigger, and every trigger key of the fight is distinct. */
    @Test
    void everyPhaseTicksOncePerTrigger() {
        var library = new ArrayList<CombatEffect>();
        var held = new ArrayList<Active>();
        int sequence = 0;
        for (Phase phase : Phase.values()) {
            var ticking = tick("tick-" + phase.wire(), phase, 1, false);
            library.add(ticking);
            held.add(active(ticking, ++sequence, 0));
        }
        var state = library(fight(values(), player().attacks(BLADE).guard(0).health(99, 99).holding(held.toArray(new Active[0])),
                foe("foe").guard(40).health(40, 40).attacks(CLAW)), library.toArray(new CombatEffect[0]));

        var keys = new ArrayList<Object>();
        var fired = new ArrayList<Object>();
        var strike = CombatScheduler.apply(state, strike("blade", "foe"), script(1, 1));
        collect(strike, fired, keys);
        assertEquals(List.of("actionDeclared"), fired, "the holder's own action, before its dice");
        state = after(state, strike);

        fired.clear();
        var ended = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script(5, 5, 3));
        collect(ended, fired, keys);
        assertEquals(List.of("activationEnd", "preRoll", "postHit", "postDamage", "roundEnd", "roundOpen", "activationStart"), fired,
                "the foe's hit fired the three attack phases on the player, its target");
        assertEquals(keys.size(), new HashSet<>(keys).size(), "no effect resolves twice for one trigger key: " + keys);
        state = after(state, ended);
        assertEquals(99 - (8 - 0) - 4, state.player().health(), "eight ticks at 1 and the claw's 3 + 1");
    }

    /** A second action in the same round is a new trigger, with its own key. */
    @Test
    void aRepeatedTriggerHasItsOwnKey() {
        var ticking = tick("burn", Phase.ACTION_DECLARED, 1, false);
        var state = library(fight(values("actionPoints", 6), player().attacks(BLADE).health(9, 9).holding(active(ticking, 1, 0)),
                foe("foe").guard(40)), ticking);
        var first = CombatScheduler.apply(state, strike("blade", "foe"), script(1, 1));
        state = after(state, first);
        var second = CombatScheduler.apply(state, strike("blade", "foe"), script(1, 1));
        assertFalse(line(first, "TICK").get("triggerKey").equals(line(second, "TICK").get("triggerKey")));
        assertEquals(7, after(state, second).player().health());
    }

    /** A poisoned participant who falls at its activation start receives no points, draws nothing and takes no action. */
    @Test
    void aPoisonedDefeatedActorCannotAct() {
        var poison = tick("poison", Phase.ACTIVATION_START, 5, false);
        var state = library(fight(values(), player().attacks(BLADE),
                foe("foe").health(3, 9).attacks(CLAW).holding(active(poison, 1, 0))), poison);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        var after = after(state, plan);
        assertEquals(EncounterState.Status.DEFEATED, after.participant("foe").status());
        assertEquals(0, after.participant("foe").health());
        assertTrue(after.participant("foe").effects().isEmpty(), "its effects end with it");
        assertEquals(0, count(plan, "STRIKE_RESOLVED"));
        assertEquals(0, notes(plan, "ACTIVATION_STARTED").getFirst().payload().get("actionPoints"));
        assertEquals("HOLDER_DEFEATED", line(plan, "ENDED").get("reason"));
        assertEquals(2, after.round(), "the next round opens without it");
    }

    /** The player can fall to a tick at its own activation start: it never waits for a decision it cannot take. */
    @Test
    void aPlayerFellingAtItsOwnStartHasNoDecision() {
        var poison = tick("poison", Phase.ACTIVATION_START, 5, false);
        var state = library(fight(values(), player().attacks(BLADE).health(3, 24).holding(active(poison, 1, 0)), foe("foe")), poison);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        var after = after(state, plan);
        assertEquals(EncounterState.Status.DEFEATED, after.player().status());
        assertEquals(EncounterPhase.ROUND_END, after.phase());
        assertFalse(after.playerDecision());
    }

    /** Healing ticks stop at the maximum and do not revive; a tick keeps to the fixed amount per stack. */
    @Test
    void healingTicksRespectTheirBounds() {
        var mend = tick("mend", Phase.ROUND_END, 4, true);
        var state = library(fight(values(), player().attacks(BLADE).health(22, 24).holding(active(mend, 1, 0)), foe("foe")), mend);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals(2, plan.playerHealthDelta(), "only the missing Health");
        assertEquals(2L, ((Number) line(plan, "TICK").get("healed")).longValue());
        var fallen = library(fight(values(), player().attacks(BLADE), foe("foe").health(0, 9).status(EncounterState.Status.DEFEATED)
                .holding()), mend);
        assertEquals(0, count(CombatScheduler.apply(fallen, new TacticalIntent.EndActivation(), script()), "EFFECTS_RESOLVED"));

        var stacked = tick("venom", Phase.ROUND_END, 2, false);
        var triple = new Active("e1", "venom", null, 1, 0, 3, 0, null, false);
        var layered = library(fight(values(), player().attacks(BLADE).health(20, 24).holding(triple), foe("foe")), stacked);
        assertEquals(-6, CombatScheduler.apply(layered, new TacticalIntent.EndActivation(), script()).playerHealthDelta());
    }

    /** An effect follows its definition when its source falls, and one waiting for that participant's activation ends. */
    @Test
    void aDefeatedSourceEndsWhatItsDefinitionSays() {
        var persists = effect("persists", Clock.PERSISTENT, 0, e -> { });
        var ends = effect("ends", Clock.PERSISTENT, 0, e -> e.endsWithSource = true);
        var waiting = new CombatEffect("waiting", "waiting", Stacking.STRONGEST, 1, Clock.NEXT_ACTIVATION, 0, true, 0, 0, 0, 0, 0,
                Set.of(), List.of(), false);
        var state = library(fight(values(), player().attacks(BLADE).holding(
                new Active("e1", "persists", "foe", 1, 0, 1, 0, null, false),
                new Active("e2", "ends", "foe", 2, 0, 1, 0, null, false),
                new Active("e3", "waiting", "foe", 3, 0, 1, 0, "foe", false)),
                foe("foe").guard(0).health(1, 9)), persists, ends, waiting);
        var plan = CombatScheduler.apply(state, strike("blade", "foe"), script(9, 9, 6, 4));
        var after = after(state, plan);
        assertEquals(EncounterState.Status.DEFEATED, after.participant("foe").status());
        assertEquals(List.of("persists"), after.player().effects().stream().map(Active::effectId).toList());
        var reasons = new TreeMap<Object, Object>();
        lines(plan, "ENDED").forEach(line -> reasons.put(line.get("effectId"), line.get("reason")));
        assertEquals(Map.of("ends", "SOURCE_DEFEATED", "waiting", "ACTOR_GONE"), reasons);
    }

    /** An attack's effect lasts until the named actor's next activation, and the modifier is felt until then. */
    @Test
    void anEffectLastsUntilTheNamedActorsNextActivation() {
        var exposed = effect("exposed", Clock.NEXT_ACTIVATION, 0, e -> e.guard = -2);
        var marking = new CombatAttack("mark", "force", 1, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, false, null,
                List.of(new CombatAttack.OnHit("exposed", Phase.POST_HIT, false)));
        var state = library(fight(values(), player().attacks(BLADE).guard(12), foe("alpha").attacks(marking), foe("beta").attacks(CLAW)), exposed);
        // Alpha 8 + 8 + 2 = 18 hits (die 1); beta meets Guard 10 with 9 + 9 + 2 = 20 (die 1).
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script(8, 8, 1, 9, 9, 1));
        var strikes = notes(plan, "STRIKE_RESOLVED");
        assertEquals(12, strikes.get(0).payload().get("defense"));
        assertEquals(10, strikes.get(1).payload().get("defense"), "the effect applied by the earlier blow");
        var after = after(state, plan);
        assertTrue(after.player().effects().isEmpty(), "the player's next activation has started");
        assertEquals("EXPIRED", line(plan, "ENDED").get("reason"));
        assertEquals(1, after.effectsApplied());
    }

    /** The world-time clock counts the round's seconds and ends the effect after the same number of them. */
    @Test
    void worldTimeExpiresAnEffectInTheFight() {
        var timed = effect("timed", Clock.WORLD_SECONDS, 12, e -> { });
        var state = library(fight(values(), player().attacks(BLADE).holding(new Active("e1", "timed", null, 1, 12, 1, 0, null, false)), foe("foe")), timed);
        state = after(state, CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script()));
        assertEquals(4, state.player().effects().getFirst().remaining());
        var last = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertTrue(after(state, last).player().effects().isEmpty());
        assertEquals(8, last.elapsedSeconds(), "the clock adds exactly the round's time");
    }

    /** Modifiers reach the attack score and the defense, capped by the profile in both directions. */
    @Test
    void modifiersMeetTheirCaps() {
        var keen = effect("keen", Clock.PERSISTENT, 0, e -> e.attack = 4);
        var state = library(fight(values(), player().attacks(BLADE).holding(active(keen, 1, 0)), foe("foe").guard(40)), keen);
        var plan = CombatScheduler.apply(state, strike("blade", "foe"), script(1, 1));
        assertEquals(1 + 1 + 2 + 2 + 3, note(plan, "STRIKE_RESOLVED").payload().get("attackTotal"), "clamped to +3");

        var wide = effect("wide", Clock.PERSISTENT, 0, e -> e.guard = -4);
        var exposed = library(fight(values(), player().attacks(BLADE),
                foe("foe").guard(1).holding(active(wide, 1, 0))), wide);
        assertEquals(0, note(CombatScheduler.apply(exposed, strike("blade", "foe"), script(1, 1, 1, 1)), "STRIKE_CALCULATED")
                .payload().get("defense"), "never below zero");

        var doubled = effect("wide", Clock.PERSISTENT, 0, e -> {
            e.guard = 4;
            e.resistance = -4;
        });
        var guarded = library(fight(values(), player().attacks(BLADE, HEX),
                foe("foe").guard(10).resistance(10).guarding().holding(active(doubled, 1, 0))), doubled);
        assertEquals(14, note(CombatScheduler.apply(guarded, strike("blade", "foe"), script(1, 1)), "STRIKE_CALCULATED")
                .payload().get("defense"), "stance and modifiers together stay under the cap of 4");
        assertEquals(6, note(CombatScheduler.apply(guarded, strike("hex", "foe"), script(1, 1, 1)), "STRIKE_CALCULATED")
                .payload().get("defense"), "Resistance reads its own modifier");
    }

    /** Absorption meets damage after armor and before Health, and a spent shield ends. */
    @Test
    void shieldsAbsorbAfterArmor() {
        var ward = effect("ward", Clock.PERSISTENT, 0, e -> e.absorption = 3);
        var state = library(fight(values(), player().attacks(BLADE), foe("foe").guard(0).armor(1).health(20, 20).holding(active(ward, 1, 3))), ward);
        // 5 + 5 + 4 = 14, strong: die 4 + force 2 + strong die 1 = 7; armor leaves 6; the shield takes 3 and ends.
        var plan = CombatScheduler.apply(state, strike("blade", "foe"), script(5, 5, 4, 1));
        var after = after(state, plan);
        assertEquals(17, after.participant("foe").health());
        assertEquals(3, note(plan, "STRIKE_CALCULATED").payload().get("absorbed"));
        assertEquals(3, note(plan, "STRIKE_RESOLVED").payload().get("damage"));
        assertEquals("DEPLETED", line(plan, "ENDED").get("reason"));
        assertTrue(after.participant("foe").effects().isEmpty());
    }

    /** Effects a hit applies respect their phase: a blow fully absorbed by armor is a hit that took no Health. */
    @Test
    void anAttackAppliesItsEffectsAtTheirPhase() {
        var mark = effect("mark", Clock.PERSISTENT, 0, e -> { });
        var bleed = effect("bleed", Clock.PERSISTENT, 0, e -> { });
        var attack = new CombatAttack("barb", "force", 2, 1, 2, null, 0, 0, 0, CombatAttack.Defense.GUARD, false, null,
                List.of(new CombatAttack.OnHit("mark", Phase.POST_HIT, false), new CombatAttack.OnHit("bleed", Phase.POST_DAMAGE, false)));
        var state = library(fight(values(), player().attacks(attack), foe("foe").guard(0).armor(4).health(9, 9)), mark, bleed);
        var plan = CombatScheduler.apply(state, strike("barb", "foe"), script(5, 5, 1));
        assertEquals(List.of("mark"), after(state, plan).participant("foe").effects().stream().map(Active::effectId).toList());

        var bare = library(fight(values(), player().attacks(attack), foe("foe").guard(0).armor(0).health(9, 9)), mark, bleed);
        var hurt = CombatScheduler.apply(bare, strike("barb", "foe"), script(5, 5, 1));
        assertEquals(List.of("mark", "bleed"), after(bare, hurt).participant("foe").effects().stream().map(Active::effectId).toList());
        assertEquals(2, after(bare, hurt).effectsApplied());

        var missed = CombatScheduler.apply(library(fight(values(), player().attacks(attack), foe("foe").guard(40)), mark, bleed),
                strike("barb", "foe"), script(1, 1));
        assertEquals(0, count(missed, "EFFECTS_RESOLVED"), "a miss applies nothing");
    }

    /** Conditions a participant came with become instances once, in the initiative operation. */
    @Test
    void entryConditionsBecomeEffectsAtInitiative() {
        var dazed = effect("dazed", Clock.ROUND_ENDS, 2, e -> e.guard = -1);
        var plain = library(fight(values(), player().attacks(BLADE).conditions("dazed", "exploration-only"), foe("foe").conditions("dazed")), dazed);
        var entering = new EncounterState(ID, 0, EncounterPhase.ENTERING, 0, null, null, 0, List.of(), plain.profile(),
                plain.frozenSpace(), Set.of(), List.of(), OpeningPolicy.INITIATIVE, null, "NONE",
                unranked(plain.participants()), List.of(), plain.effects(), 0);
        var plan = CombatScheduler.apply(entering, new TacticalIntent.RollInitiative(), script(9, 9, 1, 1));
        var after = after(entering, plan);
        assertEquals(1, after.player().effects().size(), "a condition without a combat form changes nothing here");
        assertEquals(2, after.effectsApplied());
        assertEquals(Set.of("e1", "e2"), Set.of(after.player().effects().getFirst().instanceId(),
                after.participant("foe").effects().getFirst().instanceId()));
    }

    // ---- prevention ------------------------------------------------------------------------------------------------

    /** A stunned player keeps its decision without usable points: speech and the end stay, everything else is refused. */
    @Test
    void preventedActionsLeaveThePlayerAbleToEnd() {
        var stunned = effect("stunned", Clock.ACTIVATIONS, 1, e -> e.prevents = Set.of(Prevents.ACTIONS));
        var state = library(fight(values(), player().attacks(BLADE).holding(active(stunned, 1, 1)), foe("foe")), stunned);
        assertEquals(TacticalRules.Refusal.PREVENTED, TacticalRules.refusal(state, strike("blade", "foe")));
        assertEquals(TacticalRules.Refusal.PREVENTED, TacticalRules.refusal(state, new TacticalIntent.Guard()));
        assertEquals(TacticalRules.Refusal.PREVENTED, TacticalRules.refusal(state, new TacticalIntent.MoveZone("mid")));
        assertNull(TacticalRules.refusal(state, new TacticalIntent.Speech("foe", "Wait.")));
        assertNull(TacticalRules.refusal(state, new TacticalIntent.EndActivation()));

        // Its next activation grants no points: the grant checks prevention after the clocks ran.
        var lingering = library(fight(values(), player().attacks(BLADE).holding(new Active("e1", "stunned", null, 1, 2, 1, 0, null, false)),
                foe("foe")), stunned);
        var next = after(lingering, CombatScheduler.apply(lingering, new TacticalIntent.EndActivation(), script()));
        assertEquals(0, next.actionPoints());
        assertTrue(next.playerDecision(), "the end stays explicit, so the fight cannot stall");
        assertEquals(1, next.player().effects().getFirst().remaining());
        var freed = after(next, CombatScheduler.apply(next, new TacticalIntent.EndActivation(), script()));
        assertEquals(3, freed.actionPoints());
        assertTrue(freed.player().effects().isEmpty());
    }

    /** A prevented participant takes no action; movement, reactions and Focus have their own prevention. */
    @Test
    void preventionNamesWhatItStops() {
        var stunned = effect("stunned", Clock.PERSISTENT, 0, e -> e.prevents = Set.of(Prevents.ACTIONS));
        var state = library(fight(values(), player().attacks(BLADE), foe("foe").attacks(CLAW).holding(active(stunned, 1, 0))), stunned);
        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script());
        assertEquals(0, count(plan, "STRIKE_RESOLVED"));
        assertEquals(0, count(plan, "GUARD_RAISED"));

        var snared = effect("snared", Clock.PERSISTENT, 0, e -> e.prevents = Set.of(Prevents.MOVEMENT));
        var held = library(fight(values(), player().attacks(BLADE).holding(active(snared, 1, 0)), foe("foe").attacks(CLAW).zone("mid")
                .holding(active(snared, 2, 0))), snared);
        assertEquals(TacticalRules.Refusal.PREVENTED, TacticalRules.refusal(held, new TacticalIntent.MoveZone("mid")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(held, strike("blade", "foe")),
                "only movement is stopped: the strike is judged on its own rules");
        assertEquals(0, count(CombatScheduler.apply(held, new TacticalIntent.EndActivation(), script()), "PARTICIPANT_MOVED"));

        var numb = effect("numb", Clock.PERSISTENT, 0, e -> e.prevents = Set.of(Prevents.REACTIONS));
        var dull = library(fight(values(), player().guard(10).brace(Policy.FIRST).holding(active(numb, 1, 0)), foe("foe").attacks(CLAW)), numb);
        var unanswered = CombatScheduler.apply(dull, new TacticalIntent.EndActivation(), script(1, 1));
        assertEquals(10, note(unanswered, "STRIKE_RESOLVED").payload().get("defense"));
        assertEquals("PREVENTED", line(unanswered, "REACTION_SKIPPED").get("reason"));

        var gagged = effect("gagged", Clock.PERSISTENT, 0, e -> e.prevents = Set.of(Prevents.RESOURCES));
        var focused = new CombatAbility("focused", 1, CombatAbility.Limit.NONE, 0, 1, CombatAbility.Targets.SELF, 1, 0,
                List.of(heal(0, 0, 1)));
        var spent = library(fight(values(), player().abilities(focused).focus(4, 4).holding(active(gagged, 1, 0)), foe("foe")), gagged);
        assertEquals(TacticalRules.Refusal.PREVENTED, TacticalRules.refusal(spent, new TacticalIntent.UseAbility("focused", List.of())));
    }

    /** The opening strike of a prevented or fallen player lapses at no cost. */
    @Test
    void aPreventedOpeningLapses() {
        var stunned = effect("stunned", Clock.PERSISTENT, 0, e -> e.prevents = Set.of(Prevents.ACTIONS));
        var base = library(fight(values(), player().attacks(BLADE).conditions("stunned"), foe("foe")), stunned);
        var entering = new EncounterState(ID, 0, EncounterPhase.ENTERING, 0, null, null, 0, List.of(), base.profile(),
                base.frozenSpace(), Set.of(), List.of(), OpeningPolicy.INITIATIVE, "foe", "PENDING",
                unranked(base.participants()), List.of(), base.effects(), 0);
        var plan = CombatScheduler.apply(entering, new TacticalIntent.RollInitiative(), script(9, 9, 1, 1));
        var after = after(entering, plan);
        assertEquals("PREVENTED", note(plan, "OPENING_STRIKE_LAPSED").payload().get("reason"));
        assertEquals("RESOLVED", after.openingStatus());
        assertEquals(0, after.actionPoints());
    }

    // ---- abilities -------------------------------------------------------------------------------------------------

    /** An ability pays its points and Focus; its cooldown counts rounds and only rounds. */
    @Test
    void anAbilityPaysAndCoolsDown() {
        var mend = new CombatAbility("mend", 1, CombatAbility.Limit.NONE, 2, 2, CombatAbility.Targets.SELF, 1, 0, List.of(heal(1, 4, 1)));
        var state = fight(values(), player().abilities(mend).health(10, 24).focus(5, 6), foe("foe"));
        var use = new TacticalIntent.UseAbility("mend", List.of());
        var plan = CombatScheduler.apply(state, use, script(3));
        assertEquals(-2, plan.playerFocusDelta());
        assertEquals(4, plan.playerHealthDelta(), "die 3 + flat 1");
        assertTrue(note(plan, "COMBAT_ABILITY_USED").byPlayer());
        assertEquals(List.of(0), line(plan, "HEAL").get("drawOrdinals"));
        state = after(state, plan);
        assertEquals(2, state.actionPoints());
        assertEquals(TacticalRules.Refusal.ABILITY, TacticalRules.refusal(state, use), "cooling down");
        assertEquals("COOLDOWN", ability(state, "mend").get("disabled"));

        state = after(state, CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script()));
        assertEquals(TacticalRules.Refusal.ABILITY, TacticalRules.refusal(state, use), "one round passed of two");
        state = after(state, CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script()));
        assertNull(TacticalRules.refusal(state, use), "two rounds passed");
        assertNull(ability(state, "mend").get("disabled"));

        var broke = fight(values(), player().abilities(mend).focus(1, 6), foe("foe"));
        assertEquals(TacticalRules.Refusal.RESOURCE, TacticalRules.refusal(broke, use));
        assertEquals("FOCUS", ability(broke, "mend").get("disabled"));
        var tired = withPoints(fight(values(), player().abilities(mend).focus(4, 6), foe("foe")), 0);
        assertEquals(TacticalRules.Refusal.ACTION_POINTS, TacticalRules.refusal(tired, use));
    }

    /** A free ability needs a limit, cannot refresh itself, and each limit counts what it says. */
    @Test
    void freeAbilitiesAreLimited() {
        assertThrows(IllegalArgumentException.class, () -> new CombatAbility("free", 0, CombatAbility.Limit.NONE, 0, 0,
                CombatAbility.Targets.SELF, 1, 0, List.of(heal(0, 0, 1))));
        var perTurn = new CombatAbility("breath", 0, CombatAbility.Limit.ACTIVATION, 0, 0, CombatAbility.Targets.SELF, 1, 0,
                List.of(heal(0, 0, 1)));
        var once = new CombatAbility("gift", 0, CombatAbility.Limit.ENCOUNTER, 0, 0, CombatAbility.Targets.SELF, 1, 0,
                List.of(heal(0, 0, 1)));
        var state = fight(values(), player().abilities(perTurn, once).health(10, 24), foe("foe"));
        var breath = new TacticalIntent.UseAbility("breath", List.of());
        var gift = new TacticalIntent.UseAbility("gift", List.of());
        state = after(state, CombatScheduler.apply(state, breath, script()));
        state = after(state, CombatScheduler.apply(state, gift, script()));
        assertEquals(3, state.actionPoints(), "free means free");
        assertEquals(TacticalRules.Refusal.ABILITY, TacticalRules.refusal(state, breath));
        assertEquals("LIMIT", ability(state, "gift").get("disabled"));
        state = after(state, CombatScheduler.apply(state, new TacticalIntent.EndActivation(), script()));
        assertNull(TacticalRules.refusal(state, breath), "a new activation");
        assertEquals(TacticalRules.Refusal.ABILITY, TacticalRules.refusal(state, gift), "once per encounter stays used");
    }

    /** Targets follow the declared side and space; one operation's dice are drawn once, for all of them. */
    @Test
    void abilityTargetsAndDice() {
        var volley = new CombatAbility("volley", 2, CombatAbility.Limit.NONE, 0, 0, CombatAbility.Targets.HOSTILE, 2, 1,
                List.of(new CombatAbility.Operation(CombatAbility.Kind.DAMAGE, 2, 4, "force", 1, 1, null, false)));
        var state = fight(values(), player().abilities(volley), foe("near").armor(1).health(20, 20),
                foe("next").armor(3).zone("mid").health(20, 20), foe("away").zone("far"), foe("hidden").zone("murk"),
                ally("friend"));
        assertEquals(List.of("near", "next"), ability(state, "volley").get("targets"), "range 1; the obscured zone is out of sight");
        for (var illegal : List.of(List.of("away"), List.of("hidden"), List.of("friend"), List.of("player"), List.of("ghost"),
                List.<String>of(), List.of("near", "next", "near"), List.of("near", "near"))) {
            assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.UseAbility("volley", illegal)),
                    illegal.toString());
        }
        var plan = CombatScheduler.apply(state, new TacticalIntent.UseAbility("volley", List.of("near", "next")), script(2, 3));
        var after = after(state, plan);
        // 2 + 3 + force 2 + flat 1 = 8; penetration 1 leaves armor 0 on near and armor 2 on next.
        assertEquals(12, after.participant("near").health());
        assertEquals(14, after.participant("next").health());
        var hurt = lines(plan, "DAMAGE");
        assertEquals(2, hurt.size());
        assertEquals(List.of(0, 1), hurt.getFirst().get("drawOrdinals"));
        assertEquals(hurt.get(0).get("drawOrdinals"), hurt.get(1).get("drawOrdinals"), "the same dice for every target");
        assertFalse(hurt.getFirst().containsKey("healthAfter"), "a foe's Health stays in the internal record");
        assertEquals(0, count(plan, "REACTION_RESOLVED"), "an ability is no attack");

        var self = new CombatAbility("self", 1, CombatAbility.Limit.NONE, 0, 0, CombatAbility.Targets.SELF, 1, 0, List.of(heal(0, 0, 1)));
        var alone = fight(values(), player().abilities(self), foe("foe"));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(alone, new TacticalIntent.UseAbility("self", List.of("foe"))));
        assertEquals(TacticalRules.Refusal.ABILITY, TacticalRules.refusal(alone, new TacticalIntent.UseAbility("missing", List.of())));
    }

    /** Apply and remove reach the participants the ability names, and removal is what a persistent clock waits for. */
    @Test
    void abilitiesApplyAndRemoveEffects() {
        var hex = effect("hex", Clock.PERSISTENT, 0, e -> e.guard = -1);
        var curse = new CombatAbility("curse", 1, CombatAbility.Limit.NONE, 0, 0, CombatAbility.Targets.HOSTILE, 1, 1,
                List.of(new CombatAbility.Operation(CombatAbility.Kind.APPLY, 0, 0, null, 0, 0, "hex", false)));
        var cleanse = new CombatAbility("cleanse", 1, CombatAbility.Limit.NONE, 0, 0, CombatAbility.Targets.ALLY, 1, 0,
                List.of(new CombatAbility.Operation(CombatAbility.Kind.REMOVE, 0, 0, null, 0, 0, "hex", false)));
        var state = library(fight(values(), player().abilities(curse, cleanse).holding(active(hex, 1, 0)), foe("foe")), hex);
        var cursed = CombatScheduler.apply(state, new TacticalIntent.UseAbility("curse", List.of("foe")), script());
        state = after(state, cursed);
        assertEquals("foe", line(cursed, "APPLIED").get("holderId"));
        assertEquals("player", line(cursed, "APPLIED").get("sourceId"));
        assertEquals(1, state.participant("foe").effects().size());
        var cleansed = CombatScheduler.apply(state, new TacticalIntent.UseAbility("cleanse", List.of("player")), script());
        assertEquals("REMOVED", line(cleansed, "ENDED").get("reason"));
        assertTrue(after(state, cleansed).player().effects().isEmpty());
        assertEquals(1, after(state, cleansed).participant("foe").effects().size());
    }

    // ---- bounds and determinism ------------------------------------------------------------------------------------

    /**
     * The largest roster with every reaction and effect keeps one operation inside each of its bounds. The player acts in
     * the middle of the order, so one end of its activation crosses a round boundary with all eight others striking and its
     * reaction budget refreshing: the counters stop at the draw ceiling while each mandatory strike still fits.
     */
    @Test
    void theLargestFightFitsOneOperation() {
        var library = new ArrayList<CombatEffect>();
        var held = new ArrayList<Active>();
        int sequence = 0;
        for (Phase phase : List.of(Phase.ROUND_OPEN, Phase.ACTIVATION_START, Phase.ACTIVATION_END, Phase.ROUND_END)) {
            for (int index = 0; index < 2; index++) {
                var ticking = tick(phase.wire() + index, phase, 1, false);
                library.add(ticking);
                held.add(active(ticking, ++sequence, 0));
            }
        }
        var strongest = new CombatAttack("strongest", "force", 1, CombatProfile.ATTACK_DICE_MAX, CombatProfile.ATTACK_SIDES_MAX,
                "force", 0, 0, 0, CombatAttack.Defense.GUARD, true, null);
        var builders = new ArrayList<Builder>();
        for (int index = 0; index < EncounterTemplate.MAX_PARTICIPANTS; index++) {
            builders.add(foe("rival-" + index).guard(0).health(999, 999).attacks(strongest).brace(Policy.FIRST)
                    .react(RIPOSTE, Policy.FIRST).holding(held.toArray(new Active[0])));
        }
        var state = library(fight(values("reactionsPerRound", 3), player().attacks(strongest).guard(0).health(999, 999)
                .react(RIPOSTE, Policy.FIRST).holding(held.toArray(new Active[0])), builders.toArray(new Builder[0])),
                library.toArray(new CombatEffect[0]));
        state = playerAtRank(state, 4);

        var plan = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), seeded(7, new ArrayList<>()));
        assertTrue(plan.steps().size() <= TacticalPlan.MAX_STEPS, "steps " + plan.steps().size());
        assertTrue(plan.notes().size() <= ResolvedCommit.MAX_EVENTS, "notes " + plan.notes().size());
        assertEquals(1, lines(plan, "BOUNDED").size(), "the automatic bound was reached and announced once");
        assertTrue(lines(plan, "TICK").size() <= CombatScheduler.MAX_AUTOMATIC_EFFECTS);
        var asked = new ArrayList<Integer>();
        CombatScheduler.apply(state, new TacticalIntent.EndActivation(), seeded(7, asked));
        assertEquals(asked.stream().distinct().count(), asked.size(), "each ordinal is drawn once");
        assertTrue(asked.stream().allMatch(ordinal -> ordinal < DeterministicDraw.MAX_DRAWS), "draws " + asked.size());

        assertEquals(8, notes(plan, "STRIKE_RESOLVED").stream().filter(n -> !n.payload().containsKey("reaction")).count(),
                "every mandatory strike was made");
        var counters = notes(plan, "STRIKE_RESOLVED").stream().filter(n -> n.payload().containsKey("reaction")).toList();
        assertFalse(counters.isEmpty());
        for (var counter : counters) {
            int first = (Integer) ((List<?>) counter.payload().get("drawOrdinals")).getFirst();
            assertTrue(first <= CombatScheduler.COUNTER_DRAW_CEILING, "a counter past the ceiling at " + first);
        }
        assertTrue(lines(plan, "REACTION_SKIPPED").stream().anyMatch(line -> "UNAVAILABLE".equals(line.get("reason"))),
                "the budget ran out before the reactions did");
        assertTrue(count(plan, "REACTION_RESOLVED") <= CombatScheduler.MAX_REACTIONS);
    }

    /** A fight where the player takes this rank, the others keeping their relative order, at the player's decision. */
    private static EncounterState playerAtRank(EncounterState s, int rank) {
        var list = new ArrayList<EncounterState.Combatant>();
        for (var p : s.participants()) {
            int moved = p.player() ? rank : p.rank() <= rank ? p.rank() - 1 : p.rank();
            list.add(new EncounterState.Combatant(p.id(), p.ordinal(), p.actorId(), p.side(), p.zone(), p.status(), p.finesse(),
                    p.initiativeModifier(), 20 - moved, moved, p.reactions(), p.rowVersion(), p.health(), p.healthMax(), p.guarding(),
                    p.sheet(), p.focus(), p.focusMax(), p.effects(), p.policies(), p.uses()));
        }
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), rank, s.actionPoints(), s.speechUsed(), s.passages(),
                s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), list,
                s.ammunition(), s.effects(), s.effectsApplied());
    }

    /** The same state, intention and draws always give the same plan, reactions and effects included. */
    @Test
    void theSameDrawsGiveTheSamePlan() {
        var poison = tick("poison", Phase.ROUND_END, 1, false);
        var state = library(fight(values(), player().attacks(BLADE).guard(5).brace(Policy.FIRST).react(RIPOSTE, Policy.FIRST).holding(active(poison, 1, 0)),
                foe("foe").guard(5).attacks(CLAW).brace(Policy.FIRST).react(RIPOSTE, Policy.FIRST).holding(active(poison, 2, 0))), poison);
        for (int seed = 0; seed < 50; seed++) {
            var first = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), seeded(seed, new ArrayList<>()));
            var second = CombatScheduler.apply(state, new TacticalIntent.EndActivation(), seeded(seed, new ArrayList<>()));
            assertEquals(first, second, "seed " + seed);
        }
    }

    /** Seeded fights of every mechanism: pools, stacks and budgets stay in bounds, and each trigger key resolves once. */
    @Test
    void seededFightsKeepEveryBound() {
        var poison = new CombatEffect("poison", "poison", Stacking.ADDITIVE, 3, Clock.ROUND_ENDS, 2, false, 0, 0, 0, 0, 0, Set.of(),
                List.of(new CombatEffect.Tick(Phase.ROUND_END, 1, false)), false);
        var mend = new CombatEffect("mend", "mend", Stacking.REFRESH, 1, Clock.ACTIVATIONS, 2, false, 0, 0, 0, 0, 0, Set.of(),
                List.of(new CombatEffect.Tick(Phase.ACTIVATION_START, 1, true)), false);
        var ward = effect("ward", Clock.PERSISTENT, 0, e -> e.absorption = 2);
        var stun = effect("stun", Clock.ACTIVATIONS, 1, e -> e.prevents = Set.of(Prevents.ACTIONS));
        var fang = new CombatAttack("fang", "force", 2, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, true, null,
                List.of(new CombatAttack.OnHit("poison", Phase.POST_DAMAGE, false), new CombatAttack.OnHit("stun", Phase.POST_HIT, false)));
        var shield = new CombatAbility("shield", 1, CombatAbility.Limit.NONE, 1, 0, CombatAbility.Targets.SELF, 1, 0,
                List.of(new CombatAbility.Operation(CombatAbility.Kind.APPLY, 0, 0, null, 0, 0, "ward", true),
                        new CombatAbility.Operation(CombatAbility.Kind.APPLY, 0, 0, null, 0, 0, "mend", true)));
        for (int seed = 0; seed < 60; seed++) {
            var random = new Random(seed);
            var state = library(fight(values(), player().attacks(fang).abilities(shield).guard(10).health(30, 30).brace(Policy.FIRST)
                    .react(RIPOSTE, Policy.FIRST), foe("a").guard(10).health(24, 24).attacks(fang).brace(Policy.FIRST).react(snap(1), Policy.FIRST),
                    foe("b").guard(10).health(24, 24).attacks(fang).zone("mid").react(RIPOSTE, Policy.FIRST)), poison, mend, ward, stun);
            var keys = new HashSet<Object>();
            for (int step = 0; step < 40 && state.playerDecision(); step++) {
                var legal = new ArrayList<TacticalIntent>();
                for (var candidate : List.of(strike("fang", "a"), strike("fang", "b"), new TacticalIntent.MoveZone("mid"),
                        new TacticalIntent.MoveZone("ring"), new TacticalIntent.Guard(), new TacticalIntent.UseAbility("shield", List.of()),
                        new TacticalIntent.EndActivation(), new TacticalIntent.EndActivation())) {
                    if (TacticalRules.refusal(state, candidate) == null) {
                        legal.add(candidate);
                    }
                }
                var plan = CombatScheduler.apply(state, legal.get(random.nextInt(legal.size())), seeded(random.nextInt(), new ArrayList<>()));
                for (var line : lines(plan, "TICK")) {
                    assertTrue(keys.add(line.get("triggerKey")), "repeated " + line);
                }
                state = after(state, plan);
                for (var participant : state.participants()) {
                    assertEquals(participant.health() == 0, participant.status() == EncounterState.Status.DEFEATED, participant.id());
                    assertTrue(participant.reactions() >= 0 && participant.reactions() <= 1);
                    assertTrue(participant.status() == EncounterState.Status.ACTIVE || participant.effects().isEmpty());
                    assertTrue(participant.effects().size() <= CombatEffects.MAX_ACTIVE);
                }
            }
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    /** A reaction that strikes back at an opponent who leaves its zone. */
    private static CombatReaction snap(int priority) {
        return new CombatReaction("snap", Trigger.LEAVES_ZONE, priority, 0, false, CombatReaction.Attacks.ANY, Kind.COUNTER, 0, null, null,
                false);
    }

    /** One intended strike. */
    private static TacticalIntent strike(String attack, String target) {
        return new TacticalIntent.Strike(attack, target);
    }

    /** A healing operation with these dice and flat. */
    private static CombatAbility.Operation heal(int count, int sides, int flat) {
        return new CombatAbility.Operation(CombatAbility.Kind.HEAL, count, sides, null, flat, 0, null, false);
    }

    /** Mutable fields of an effect a case customizes. */
    private static final class Shape {
        private int guard;
        private int resistance;
        private int attack;
        private int absorption;
        private Set<Prevents> prevents = Set.of();
        private boolean endsWithSource;
    }

    /** A strongest-stacking effect on a clock. */
    private static CombatEffect effect(String id, Clock clock, int count, java.util.function.Consumer<Shape> shape) {
        var fields = new Shape();
        shape.accept(fields);
        return new CombatEffect(id, id, Stacking.STRONGEST, 1, clock, count, false, 0, fields.guard, fields.resistance, fields.attack,
                fields.absorption, fields.prevents, List.of(), fields.endsWithSource);
    }

    /** A lasting effect with one tick. */
    private static CombatEffect tick(String id, Phase phase, int amount, boolean heal) {
        return new CombatEffect(id, id, Stacking.STRONGEST, 1, Clock.PERSISTENT, 0, false, 0, 0, 0, 0, 0, Set.of(),
                List.of(new CombatEffect.Tick(phase, amount, heal)), false);
    }

    /** A sourceless instance of a definition with the units its clock starts on, unless given. */
    private static Active active(CombatEffect effect, int sequence, int units) {
        return new Active("e" + sequence, effect.id(), null, sequence, units, 1, effect.absorption() > 0 ? units : 0, null, false);
    }

    /** Parameters as override pairs. */
    private static Map<String, Integer> values(Object... pairs) {
        var map = new TreeMap<String, Integer>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], (Integer) pairs[index + 1]);
        }
        return map;
    }

    /** Builder of one participant of a fight. */
    private static final class Builder {
        private final String id;
        private final EncounterTemplate.Side side;
        private String zone = "ring";
        private int health = 24;
        private int healthMax = 24;
        private int guard = 10;
        private int resistance = 10;
        private int armor;
        private int focus;
        private int focusMax;
        private boolean guarding;
        private EncounterState.Status status = EncounterState.Status.ACTIVE;
        private List<CombatAttack> attacks = List.of();
        private List<CombatAbility> abilities = List.of();
        private final List<CombatReaction> reactions = new ArrayList<>();
        private final Map<String, Policy> policies = new TreeMap<>();
        private List<Active> effects = List.of();
        private List<String> conditions = List.of();

        Builder(String id, EncounterTemplate.Side side) {
            this.id = id;
            this.side = side;
        }

        Builder zone(String value) { zone = value; return this; }
        Builder health(int current, int maximum) { health = current; healthMax = maximum; return this; }
        Builder guard(int value) { guard = value; return this; }
        Builder resistance(int value) { resistance = value; return this; }
        Builder armor(int value) { armor = value; return this; }
        Builder focus(int current, int maximum) { focus = current; focusMax = maximum; return this; }
        Builder guarding() { guarding = true; return this; }
        Builder status(EncounterState.Status value) { status = value; return this; }
        Builder attacks(CombatAttack... values) { attacks = List.of(values); return this; }
        Builder abilities(CombatAbility... values) { abilities = List.of(values); return this; }
        Builder brace(Policy policy) { policies.put(CombatReaction.BRACE_ID, policy); return this; }
        Builder react(CombatReaction reaction, Policy policy) { reactions.add(reaction); policies.put(reaction.id(), policy); return this; }
        Builder holding(Active... values) { effects = List.of(values); return this; }
        Builder conditions(String... values) { conditions = List.of(values); return this; }

        /** Build at an initiative rank, with one reaction this round. */
        EncounterState.Combatant build(int ordinal, int budget) {
            var sheet = new EncounterState.Sheet(guard, resistance, armor, Map.of("force", side == EncounterTemplate.Side.PLAYER ? 2 : 1,
                    "finesse", 1, "insight", 1, "resolve", 1), attacks, abilities, reactions, conditions);
            return new EncounterState.Combatant(id, ordinal, side == EncounterTemplate.Side.PLAYER ? PLAYER : null, side, zone, status, 1, 2,
                    20 - ordinal, ordinal, budget, 0, health, healthMax, guarding, sheet, focus, focusMax, effects, policies, Map.of());
        }
    }

    /** The player at rank 0. */
    private static Builder player() {
        return new Builder(EncounterTemplate.PLAYER, EncounterTemplate.Side.PLAYER);
    }

    /** A hostile participant. */
    private static Builder foe(String id) {
        return new Builder(id, EncounterTemplate.Side.HOSTILE);
    }

    /** An allied participant. */
    private static Builder ally(String id) {
        return new Builder(id, EncounterTemplate.Side.ALLY);
    }

    /** A fight at the player's decision of round one: rank order is the argument order. */
    private static EncounterState fight(Map<String, Integer> overrides, Builder player, Builder... others) {
        var profile = CombatProfile.resolve("pack", Map.of("force", "a", "finesse", "b", "insight", "c", "resolve", "d"), "hp", "fp", overrides);
        var list = new ArrayList<EncounterState.Combatant>();
        int budget = profile.reactionsPerRound();
        list.add(player.build(0, budget));
        for (int index = 0; index < others.length; index++) {
            list.add(others[index].build(index + 1, budget));
        }
        return new EncounterState(ID, 5, EncounterPhase.AWAITING_ACTION, 1, 0, profile.actionPoints(), 0, List.of(), profile, space(),
                Set.of(), List.of(), OpeningPolicy.INITIATIVE, null, "NONE", list);
    }

    /** The state with these frozen effects, its application counter past the instances it starts with. */
    private static EncounterState library(EncounterState state, CombatEffect... effects) {
        var library = new TreeMap<String, CombatEffect>();
        for (CombatEffect effect : effects) {
            library.put(effect.id(), effect);
        }
        return new EncounterState(state.id(), state.rowVersion(), state.phase(), state.round(), state.cursor(), state.actionPoints(),
                state.speechUsed(), state.passages(), state.profile(), state.frozenSpace(), state.escapeZones(), state.actions(),
                state.opening(), state.openingTarget(), state.openingStatus(), state.participants(), state.ammunition(), library,
                state.participants().stream().mapToInt(p -> p.effects().size()).sum());
    }

    /** A hub of zones, one per space rule. */
    private static ZoneGraph space() {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        zones.put("ring", new ZoneGraph.Zone("ring", Set.of(), ZoneGraph.Cover.NONE, false, 0));
        zones.put("mid", new ZoneGraph.Zone("mid", Set.of(), ZoneGraph.Cover.NONE, false, 0));
        zones.put("far", new ZoneGraph.Zone("far", Set.of(), ZoneGraph.Cover.NONE, false, 0));
        zones.put("shade", new ZoneGraph.Zone("shade", Set.of(), ZoneGraph.Cover.PARTIAL, false, 0));
        zones.put("murk", new ZoneGraph.Zone("murk", Set.of(), ZoneGraph.Cover.NONE, true, 0));
        var edges = new ArrayList<ZoneGraph.Edge>();
        for (String[] pair : new String[][] {{"ring", "mid"}, {"mid", "far"}, {"ring", "shade"}, {"ring", "murk"}}) {
            edges.add(new ZoneGraph.Edge(pair[0], pair[1], false, false));
            edges.add(new ZoneGraph.Edge(pair[1], pair[0], false, false));
        }
        return new ZoneGraph(zones, edges);
    }

    /** The participants before initiative: no totals, ranks or reaction budgets. */
    private static List<EncounterState.Combatant> unranked(List<EncounterState.Combatant> participants) {
        return participants.stream().map(p -> new EncounterState.Combatant(p.id(), p.ordinal(), p.actorId(), p.side(), p.zone(), p.status(),
                p.finesse(), p.initiativeModifier(), null, null, 0, p.rowVersion(), p.health(), p.healthMax(), p.guarding(), p.sheet(), p.focus(),
                p.focusMax(), p.effects(), p.policies(), p.uses())).toList();
    }

    /** A copy of the state in another phase. */
    private static EncounterState withPhase(EncounterState s, EncounterPhase phase) {
        return new EncounterState(s.id(), s.rowVersion(), phase, s.round(), s.cursor(), s.actionPoints(), s.speechUsed(), s.passages(), s.profile(),
                s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(), s.ammunition(),
                s.effects(), s.effectsApplied());
    }

    /** A copy of the state with other remaining points. */
    private static EncounterState withPoints(EncounterState s, int points) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), points, s.speechUsed(), s.passages(), s.profile(),
                s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(), s.ammunition(),
                s.effects(), s.effectsApplied());
    }

    /** What the writer stores after a plan. */
    private static EncounterState after(EncounterState state, TacticalPlan plan) {
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
        return new EncounterState(state.id(), state.rowVersion() + plan.steps().size(), last.phase(), last.round(), last.cursor(), last.actionPoints(),
                last.speechUsed(), last.passages(), state.profile(), state.frozenSpace(), state.escapeZones(), state.actions(), state.opening(),
                state.openingTarget(), last.openingStatus(), list, state.ammunition(), state.effects(), last.effectsApplied());
    }

    /** The notes of a type, in order. */
    private static List<TacticalPlan.Note> notes(TacticalPlan plan, String type) {
        return plan.notes().stream().filter(note -> note.type().equals(type)).toList();
    }

    /** The first note of a type. */
    private static TacticalPlan.Note note(TacticalPlan plan, String type) {
        return notes(plan, type).stream().findFirst().orElseThrow(() -> new AssertionError(type));
    }

    /** How many notes of a type. */
    private static long count(TacticalPlan plan, String type) {
        return notes(plan, type).size();
    }

    /** The public effect lines of a kind, in order. */
    private static List<Map<?, ?>> lines(TacticalPlan plan, String kind) {
        var found = new ArrayList<Map<?, ?>>();
        for (var note : notes(plan, "EFFECTS_RESOLVED")) {
            for (Object line : (List<?>) note.payload().get("lines")) {
                if (kind.equals(((Map<?, ?>) line).get("kind"))) {
                    found.add((Map<?, ?>) line);
                }
            }
        }
        return found;
    }

    /** The first public effect line of a kind. */
    private static Map<?, ?> line(TacticalPlan plan, String kind) {
        return lines(plan, kind).stream().findFirst().orElseThrow(() -> new AssertionError(kind));
    }

    /** Gather the phases and trigger keys of a plan's ticks. */
    private static void collect(TacticalPlan plan, List<Object> phases, List<Object> keys) {
        for (var line : lines(plan, "TICK")) {
            phases.add(line.get("phase"));
            keys.add(line.get("triggerKey"));
        }
    }

    /** The receipt's option line of one ability. */
    private static Map<?, ?> ability(EncounterState state, String id) {
        return ((List<?>) TacticalRules.options(state).get("abilities")).stream().map(line -> (Map<?, ?>) line)
                .filter(line -> id.equals(line.get("abilityId"))).findFirst().orElseThrow();
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

    /** Seeded draws that are stable by ordinal and record each ordinal asked. */
    private static CombatScheduler.Dice seeded(int seed, List<Integer> asked) {
        return (ordinal, sides) -> {
            asked.add(ordinal);
            return new Random(seed * 1_000_003L + ordinal).nextInt(sides) + 1;
        };
    }
}
