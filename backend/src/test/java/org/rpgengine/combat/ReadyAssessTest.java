package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rpgengine.combat.EncounterOutcomeTest.active;
import static org.rpgengine.combat.EncounterOutcomeTest.ally;
import static org.rpgengine.combat.EncounterOutcomeTest.fight;
import static org.rpgengine.combat.EncounterOutcomeTest.foe;
import static org.rpgengine.combat.EncounterOutcomeTest.library;
import static org.rpgengine.combat.EncounterOutcomeTest.phases;
import static org.rpgengine.combat.EncounterOutcomeTest.player;
import static org.rpgengine.combat.EncounterOutcomeTest.script;
import static org.rpgengine.combat.EncounterOutcomeTest.tick;

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
 * Pure acceptance of the Ready/equip and Assess actions (docs/11 section 7, ADR 0034): each costs its declared points, an
 * illegal or unaffordable attempt is refused before anything is spent, readying takes over exactly the values it was given
 * and changes nothing when a tick fells the player first, and an assessment follows its recorded dice against the target's
 * effective Resistance, revealing values only on success. The PostgreSQL path is {@code ReadyAssessCommandsTest}. A fixture
 * participant has insight 1, Guard 10 unless set, Resistance 10 and armor 0.
 */
class ReadyAssessTest {
    private static final EncounterObjective HOLD = new EncounterObjective("hold", EncounterObjective.Kind.SURVIVE, null, null, 9);
    /** A ranged device that spends one {@code arrows} per attempt. */
    private static final CombatAttack BOLT = new CombatAttack("bolt-caster", "finesse", 0, 1, 6, "finesse", 0, 0, 1,
            CombatAttack.Defense.GUARD, true, "arrows");
    private static final ReadiedGear ARMED = new ReadiedGear(13, 1, List.of(BOLT), List.of("cloak", "bolt-caster"));

    /** Construct a stateless fixture. */
    ReadyAssessTest() {
    }

    // ---- assessment ------------------------------------------------------------------------------------------------

    /**
     * Every pair of recorded dice against an independent model: {@code 2d10 + insight 1} meeting Resistance 10 succeeds and
     * reveals the target's Health, maximum, Guard, Resistance and armor; anything less reveals nothing. Either way the
     * attempt costs one point, draws exactly two dice and returns to the decision.
     */
    @Test
    void everyRollFollowsTheModel() {
        var state = fight(List.of(HOLD), player(), foe("raider").guard(13).health(9, 14));
        for (int first = 1; first <= 10; first++) {
            for (int second = 1; second <= 10; second++) {
                var plan = CombatScheduler.apply(state, new TacticalIntent.Assess("raider"), script(first, second));
                boolean success = first + second + 1 >= 10;
                String label = first + "+" + second;
                assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.AWAITING_ACTION), phases(plan), label);
                assertEquals(2, plan.last().actionPoints(), label);
                var shown = note(plan, "ASSESSMENT_RESOLVED");
                assertFalse(shown.internal());
                assertTrue(shown.byPlayer());
                assertEquals(first + second + 1, shown.payload().get("total"), label);
                assertEquals(success, shown.payload().get("success"), label);
                assertEquals(List.of(first, second), shown.payload().get("dice"));
                assertEquals(List.of(0, 1), shown.payload().get("drawOrdinals"));
                assertEquals(success, shown.payload().containsKey("revealed"), label);
                assertFalse(shown.payload().containsKey("difficulty"), "the difficulty stays internal");
                assertFalse(shown.payload().containsKey("margin"));
                var hidden = note(plan, "ASSESSMENT_CALCULATED");
                assertTrue(hidden.internal());
                assertEquals(10, hidden.payload().get("difficulty"));
                assertEquals(first + second + 1 - 10, hidden.payload().get("margin"));
                if (success) {
                    assertEquals(Map.of("health", 9, "healthMax", 14, "guard", 13, "resistance", 10, "armor", 0),
                            shown.payload().get("revealed"), label);
                    assertEquals(shown.payload().get("revealed"), assessment(plan).get("revealed"));
                } else {
                    assertFalse(assessment(plan).containsKey("revealed"), label);
                }
                assertTrue(plan.participants().isEmpty(), "an assessment changes no participant");
                assertEquals(0L, plan.elapsedSeconds());
            }
        }
    }

    /** The difficulty is the target's effective Resistance: an effect that raises it raises the bar. */
    @Test
    void anEffectOnTheTargetRaisesTheDifficulty() {
        var steeled = new CombatEffect("steeled", "steeled", CombatEffect.Stacking.STRONGEST, 1, CombatEffect.Clock.PERSISTENT, 0,
                false, 0, 0, 3, 0, 0, Set.of(), List.of(), false);
        var state = library(fight(List.of(HOLD), player(), foe("raider").holding(active(steeled, 1))), steeled);
        var plan = CombatScheduler.apply(state, new TacticalIntent.Assess("raider"), script(6, 5));
        assertEquals(13, note(plan, "ASSESSMENT_CALCULATED").payload().get("difficulty"));
        assertEquals(false, note(plan, "ASSESSMENT_RESOLVED").payload().get("success"), "12 does not meet 13");
        var met = CombatScheduler.apply(state, new TacticalIntent.Assess("raider"), script(6, 6));
        assertEquals(true, note(met, "ASSESSMENT_RESOLVED").payload().get("success"), "13 meets 13");
        assertEquals(10, ((Map<?, ?>) note(met, "ASSESSMENT_RESOLVED").payload().get("revealed")).get("resistance"),
                "the revealed Resistance is the sheet's, before effects");
    }

    /**
     * Who can be assessed: a different standing participant within two range bands and in sight. Self, unknown, fallen,
     * too far and hidden targets are refused; an ally and a target past a closed passage are not. Cost and turn come after.
     */
    @Test
    void onlyAVisibleStandingParticipantInRangeCanBeAssessed() {
        var state = spread(3);
        assertNull(TacticalRules.refusal(state, new TacticalIntent.Assess("near")));
        assertNull(TacticalRules.refusal(state, new TacticalIntent.Assess("friend")), "an ally can be assessed");
        assertNull(TacticalRules.refusal(state, new TacticalIntent.Assess("covered")), "cover does not stop a look");
        assertNull(TacticalRules.refusal(state, new TacticalIntent.Assess("edge")), "two bands away is in range");
        for (String illegal : List.of("player", "ghost", "fallen", "beyond", "hidden")) {
            assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(state, new TacticalIntent.Assess(illegal)), illegal);
        }
        assertEquals(TacticalRules.Refusal.ACTION_POINTS, TacticalRules.refusal(spread(0), new TacticalIntent.Assess("near")));
        assertEquals(TacticalRules.Refusal.TARGET, TacticalRules.refusal(spread(0), new TacticalIntent.Assess("hidden")),
                "the target rule comes before the cost");
        var options = (Map<?, ?>) TacticalRules.options(state).get("assess");
        assertEquals(1, options.get("cost"));
        assertEquals(List.of("near", "friend", "covered", "edge"), options.get("targets"));
        assertThrows(IllegalStateException.class,
                () -> CombatScheduler.apply(state, new TacticalIntent.Assess("hidden"), script()), "a refused target draws nothing");
    }

    /** From inside an obscured zone its own occupants are seen, as for an attack. */
    @Test
    void insideAnObscuredZoneItsOccupantsAreSeen() {
        var state = spread(3);
        var hidden = state.participant("hidden");
        assertTrue(TacticalRules.canAssess(state.space(), state.profile(), moved(state.player(), "fog"), hidden));
        assertFalse(TacticalRules.canAssess(state.space(), state.profile(), state.player(), hidden));
    }

    /** An assessment is an action: the offer on the table lapses, as it does for any action but words. */
    @Test
    void anAssessmentTakesTheOfferOffTheTable() {
        var base = fight(List.of(HOLD), player(), foe("raider"));
        var offer = new Negotiation.Offer("offer-4", "parley", "raider", new Negotiation.Terms("pass", Negotiation.Terms.Kind.TRUCE, 0),
                Negotiation.Response.OFFERED);
        var state = withOffer(base, offer);
        var plan = CombatScheduler.apply(state, new TacticalIntent.Assess("raider"), script(1, 1));
        assertNull(plan.last().openOffer());
        assertEquals("ACTION_TAKEN", note(plan, "OFFER_LAPSED").payload().get("reason"));
        var ready = CombatScheduler.ready(state, new TacticalIntent.Ready(true), outcome(true, ARMED), script());
        assertNull(ready.last().openOffer(), "so does readying");
        assertEquals("ACTION_TAKEN", note(ready, "OFFER_LAPSED").payload().get("reason"));
    }

    /** Both actions cost points, so an effect that prevents actions refuses them; the turn rule comes first. */
    @Test
    void preventionAndTurnRefuseBoth() {
        var stunned = new CombatEffect("stunned", "stunned", CombatEffect.Stacking.STRONGEST, 1, CombatEffect.Clock.PERSISTENT, 0,
                false, 0, 0, 0, 0, 0, Set.of(CombatEffect.Prevents.ACTIONS), List.of(), false);
        var state = library(fight(List.of(HOLD), player().holding(active(stunned, 1)), foe("raider")), stunned);
        assertEquals(TacticalRules.Refusal.PREVENTED, TacticalRules.refusal(state, new TacticalIntent.Assess("raider")));
        assertEquals(TacticalRules.Refusal.PREVENTED, TacticalRules.refusal(state, new TacticalIntent.Ready(true)));
        var waiting = at(fight(List.of(HOLD), player(), foe("raider")), EncounterPhase.ACTOR_END);
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(waiting, new TacticalIntent.Assess("raider")));
        assertEquals(TacticalRules.Refusal.TURN, TacticalRules.refusal(waiting, new TacticalIntent.Ready(false)));
    }

    /** A declared-action tick that fells the player ends the fight before the check: nothing is drawn or revealed. */
    @Test
    void aFallOnTheDeclaredTickAssessesNothing() {
        var sting = tick("sting", CombatEffect.Phase.ACTION_DECLARED, 5);
        var state = library(fight(List.of(HOLD), player().health(3, 24).holding(active(sting, 1)), foe("raider")), sting);
        var plan = CombatScheduler.apply(state, new TacticalIntent.Assess("raider"), script());
        assertEquals(EncounterPhase.OUTCOME_PENDING, plan.last().phase());
        assertTrue(plan.notes().stream().noneMatch(note -> note.type().startsWith("ASSESSMENT_")));
        var ready = CombatScheduler.ready(state, new TacticalIntent.Ready(true), outcome(true, ARMED), script());
        assertEquals(EncounterPhase.OUTCOME_PENDING, ready.last().phase());
        assertTrue(ready.participants().stream().allMatch(update -> update.readied() == null), "nothing was readied");
        assertTrue(ready.notes().stream().noneMatch(note -> note.type().equals("ITEM_READIED")));
    }

    // ---- readying --------------------------------------------------------------------------------------------------

    /**
     * Readying costs one point and takes over exactly the values the commands layer computed: the player's update carries
     * them, the public event states the change with the player's own Guard and armor, and this operation's options already
     * list the new attack, armed with the stacks the outcome named.
     */
    @Test
    void readyingTakesOverTheComputedValues() {
        var state = fight(List.of(HOLD), player(), foe("dummy"));
        UUID arrows = UUID.randomUUID();
        var plan = CombatScheduler.ready(state, new TacticalIntent.Ready(true), outcome(true, ARMED,
                new EncounterState.Ammunition(arrows, "arrows", 2)), script());
        assertEquals(List.of(EncounterPhase.RESOLVING, EncounterPhase.AWAITING_ACTION), phases(plan));
        assertEquals(2, plan.last().actionPoints());
        assertEquals(1, plan.participants().size());
        assertEquals(ARMED, plan.participants().getFirst().readied());
        var shown = note(plan, "ITEM_READIED").payload();
        assertEquals("EQUIPPED", shown.get("change"));
        assertEquals("off-hand", shown.get("slot"));
        assertEquals("bolt-caster", shown.get("definitionId"));
        assertEquals(13, shown.get("guard"));
        assertEquals(1, shown.get("armor"));
        assertEquals(List.of("bolt-caster"), shown.get("attacks"));
        assertEquals(1, shown.get("actionPoints"));
        assertEquals(2, shown.get("remainingActionPoints"));
        assertTrue(note(plan, "ITEM_READIED").byPlayer());
        var strike = strikes(plan).getFirst();
        assertEquals("bolt-caster", strike.get("attackId"));
        assertNull(strike.get("disabled"), "the outcome's arrows arm it in this operation's options");
        var unarmed = CombatScheduler.ready(state, new TacticalIntent.Ready(true), outcome(true, ARMED), script());
        assertEquals("NO_AMMUNITION", strikes(unarmed).getFirst().get("disabled"));
        assertEquals(0, plan.playerHealthDelta());
        assertTrue(plan.ammunition().isEmpty(), "readying spends no ammunition");
    }

    /** Stowing is the same action the other way: its event says so and its values replace the earlier ones. */
    @Test
    void stowingIsTheSameActionTheOtherWay() {
        var bare = new ReadiedGear(11, 0, List.of(), List.of());
        var plan = CombatScheduler.ready(fight(List.of(HOLD), player(), foe("dummy")), new TacticalIntent.Ready(false),
                outcome(false, bare), script());
        assertEquals("UNEQUIPPED", note(plan, "ITEM_READIED").payload().get("change"));
        assertEquals(bare, plan.participants().getFirst().readied());
        assertEquals(List.of(), strikes(plan));
    }

    /** The cost is checked before anything is spent, and an outcome that says otherwise than the intention is refused. */
    @Test
    void readyingNeedsItsPointsAndAgreement() {
        var state = fight(List.of(HOLD), player(), foe("dummy"));
        var broke = points(state, 0);
        assertEquals(TacticalRules.Refusal.ACTION_POINTS, TacticalRules.refusal(broke, new TacticalIntent.Ready(true)));
        assertEquals(false, ((Map<?, ?>) TacticalRules.options(broke).get("ready")).get("available"));
        assertEquals(Map.of("cost", 1, "available", true), TacticalRules.options(state).get("ready"));
        assertThrows(IllegalStateException.class, () -> CombatScheduler.ready(broke, new TacticalIntent.Ready(true),
                outcome(true, ARMED), script()));
        assertThrows(IllegalStateException.class, () -> CombatScheduler.ready(state, new TacticalIntent.Ready(false),
                outcome(true, ARMED), script()));
        assertThrows(IllegalStateException.class, () -> CombatScheduler.apply(state, new TacticalIntent.Ready(true), script()),
                "readying needs its resolved equipment");
        var tuned = profiled(state, Map.of("readyCost", 2, "assessCost", 3));
        assertEquals(1, CombatScheduler.ready(tuned, new TacticalIntent.Ready(true), outcome(true, ARMED), script()).last()
                .actionPoints(), "the profile's price");
        assertEquals(0, CombatScheduler.apply(tuned, new TacticalIntent.Assess("dummy"), script(1, 1)).last().actionPoints());
        assertEquals(TacticalRules.Refusal.ACTION_POINTS, TacticalRules.refusal(points(tuned, 2), new TacticalIntent.Assess("dummy")));
    }

    /**
     * The readied values follow the same arithmetic as entry: the worn set entry froze gives back the frozen Guard, armor and
     * attacks; readying the bolt caster adds its attack in item order; stowing the cloak removes its armor; the equipment
     * and armor caps hold, and a skill rank above the attack cap is capped.
     */
    @Test
    void readiedValuesMatchEntry() throws Exception {
        var rules = EncounterPack.rules(EncounterPack.files());
        var profile = rules.profiles().get("sample:armory");
        var skills = Map.of("bladework", 2L);
        var facts = new FrozenEncounter.PlayerFacts(UUID.randomUUID(), Map.of("awareness", 2L, "might", 2L),
                new FrozenEncounter.Pool(8, 8), null, List.of("cloak", "sword"), Set.of(), skills);
        var frozen = FrozenEncounter.freeze(UUID.randomUUID(), rules.templates().get("close-brawl"), profile, facts, null,
                rules.library());
        var sheet = frozen.player().sheet();
        var same = ReadiedGear.of(profile, sheet, List.of("sword", "cloak"), rules.library().gear(), skills);
        assertEquals(sheet.guard(), same.guard());
        assertEquals(sheet.armor(), same.armor());
        assertEquals(sheet.attacks(), same.attacks());
        assertEquals(List.of("cloak", "sword"), same.equipment());
        var armed = ReadiedGear.of(profile, sheet, List.of("cloak", "sword", "bolt-caster"), rules.library().gear(), skills);
        assertEquals(List.of("bolt-caster", "sword"), armed.attacks().stream().map(CombatAttack::id).toList());
        assertEquals("arrows", armed.attacks().getFirst().ammunition());
        assertEquals(0, ReadiedGear.of(profile, sheet, List.of("sword"), rules.library().gear(), skills).armor());
        var expert = ReadiedGear.of(profile, sheet, List.of("sword"), rules.library().gear(), Map.of("bladework", 99L));
        assertEquals(CombatProfile.ATTACK_SKILL_MAX, expert.attacks().getFirst().skill());
        var heavy = Map.of("buckler", new EncounterGear(2, 0, null, null), "plate", new EncounterGear(0, 3, null, null));
        var capped = ReadiedGear.of(profile, sheet, List.of("buckler", "buckler", "plate", "plate"), heavy, Map.of());
        assertEquals(profile.value("guardBase") + 2 + profile.value("equipmentGuardCap"), capped.guard(), "10 + finesse 2 + cap 3");
        assertEquals(profile.value("armorMax"), capped.armor());
        assertEquals(ReadiedGear.fromCanonical(new TreeMap<>(armed.canonicalForm())), armed, "the stored form reads back");
    }

    /** The readied values replace Guard, armor and attacks only: roles, Resistance and the rest stay as frozen. */
    @Test
    void aReadiedSheetKeepsEverythingElse() {
        var sheet = new EncounterState.Sheet(12, 11, 1, Map.of("insight", 2), List.of(), List.of(), List.of(), List.of("tired"));
        var readied = sheet.withGear(ARMED);
        assertEquals(13, readied.guard());
        assertEquals(1, readied.armor());
        assertEquals(List.of(BOLT), readied.attacks());
        assertEquals(11, readied.resistance());
        assertEquals(2, readied.role("insight"));
        assertEquals(List.of("tired"), readied.conditions());
        assertThrows(IllegalArgumentException.class, () -> new ReadiedGear(-1, 0, List.of(), List.of()));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static CombatScheduler.ReadyOutcome outcome(boolean equip, ReadiedGear gear, EncounterState.Ammunition... stacks) {
        return new CombatScheduler.ReadyOutcome(UUID.nameUUIDFromBytes(new byte[] {1}), "bolt-caster", "off-hand", equip, gear,
                List.of(stacks));
    }

    private static TacticalPlan.Note note(TacticalPlan plan, String type) {
        return plan.notes().stream().filter(note -> note.type().equals(type)).findFirst().orElseThrow(() -> new AssertionError(type));
    }

    private static Map<?, ?> assessment(TacticalPlan plan) {
        return (Map<?, ?>) plan.summary().get("assessment");
    }

    private static List<Map<?, ?>> strikes(TacticalPlan plan) {
        var options = (Map<?, ?>) plan.summary().get("options");
        return ((List<?>) options.get("strikes")).stream().<Map<?, ?>>map(line -> (Map<?, ?>) line).toList();
    }

    /** The player moved to another zone, for a sight check from there. */
    private static EncounterState.Combatant moved(EncounterState.Combatant p, String zone) {
        return new EncounterState.Combatant(p.id(), p.ordinal(), p.actorId(), p.side(), zone, p.status(), p.finesse(),
                p.initiativeModifier(), p.initiative(), p.rank(), p.reactions(), p.rowVersion(), p.health(), p.healthMax(),
                p.guarding(), p.sheet(), p.focus(), p.focusMax(), p.effects(), p.policies(), p.uses());
    }

    /**
     * Participants spread over a line ring - mid - far - out, with a partially covered {@code cover} zone and an obscured
     * {@code fog} zone beside the ring, and a closed passage between ring and mid: near (ring), friend (ally, mid), covered
     * (cover), edge (far, two bands), beyond (out, three bands), hidden (fog) and fallen (ring, defeated).
     */
    private static EncounterState spread(int points) {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        for (String id : List.of("ring", "mid", "far", "out")) {
            zones.put(id, new ZoneGraph.Zone(id, Set.of(), ZoneGraph.Cover.NONE, false, 0));
        }
        zones.put("cover", new ZoneGraph.Zone("cover", Set.of(), ZoneGraph.Cover.FULL, false, 0));
        zones.put("fog", new ZoneGraph.Zone("fog", Set.of(), ZoneGraph.Cover.NONE, true, 0));
        var edges = new ArrayList<ZoneGraph.Edge>();
        for (String[] pair : new String[][] {{"ring", "mid"}, {"mid", "far"}, {"far", "out"}, {"ring", "cover"}, {"ring", "fog"}}) {
            boolean blocked = pair[1].equals("mid");
            edges.add(new ZoneGraph.Edge(pair[0], pair[1], false, blocked));
            edges.add(new ZoneGraph.Edge(pair[1], pair[0], false, blocked));
        }
        var base = fight(List.of(HOLD), player(), foe("near"), ally("friend").zone("mid"), foe("covered").zone("cover"),
                foe("edge").zone("far"), foe("beyond").zone("out"), foe("hidden").zone("fog"),
                foe("fallen").status(EncounterState.Status.DEFEATED));
        return new EncounterState(base.id(), base.rowVersion(), base.phase(), base.round(), base.cursor(), points, 0, List.of(),
                base.profile(), new ZoneGraph(zones, edges), Set.of(), List.of(), base.opening(), null, "NONE", base.participants(),
                List.of(), Map.of(), 0, base.objectives());
    }

    private static EncounterState withOffer(EncounterState s, Negotiation.Offer offer) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(), s.passages(),
                s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives(), offer, null);
    }

    private static EncounterState points(EncounterState s, int points) {
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), points, s.speechUsed(), s.passages(),
                s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives());
    }

    private static EncounterState at(EncounterState s, EncounterPhase phase) {
        return new EncounterState(s.id(), s.rowVersion(), phase, s.round(), s.cursor(), s.actionPoints(), s.speechUsed(), s.passages(),
                s.profile(), s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives());
    }

    /** The same fight under a profile with these overrides. */
    private static EncounterState profiled(EncounterState s, Map<String, Integer> overrides) {
        var profile = CombatProfile.resolve("pack", Map.of("force", "a", "finesse", "b", "insight", "c", "resolve", "d"), "hp", null,
                overrides);
        return new EncounterState(s.id(), s.rowVersion(), s.phase(), s.round(), s.cursor(), s.actionPoints(), s.speechUsed(), s.passages(),
                profile, s.frozenSpace(), s.escapeZones(), s.actions(), s.opening(), s.openingTarget(), s.openingStatus(), s.participants(),
                s.ammunition(), s.effects(), s.effectsApplied(), s.objectives());
    }
}
