package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.EncounterFeasibility.Finding;
import org.rpgengine.combat.EncounterFeasibility.Reason;
import org.rpgengine.combat.EncounterFeasibility.Violation;
import org.rpgengine.combat.EncounterObjective.Kind;
import org.rpgengine.combat.EncounterTemplate.Participant;
import org.rpgengine.combat.EncounterTemplate.Side;
import org.rpgengine.combat.EncounterTemplate.Threat;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * Feasibility of a frozen encounter for the player entering it (P13.3-A, docs/12 sections 4-5, QA-14): an objective no walk,
 * round or means of the player can meet is unreachable, and a hostile the player can never harm or reach, or one that can deny
 * the player every remaining action, is a hidden hard counter.
 *
 * <p>Fixture player: every role 2 under the default profile (situational modifier cap 3, situational Guard cap 4, round cap 30).
 * Its best attack score is 20 + 2 + 0 + 3 = 25. The {@code blade} deals at most 1d6 + force = 8 in melee; the {@code pick} is
 * the same blade with penetration 4; the {@code bow} reaches two zones for at most 6; the {@code venom} blade applies a bleeding
 * tick on any hit.
 */
class EncounterFeasibilityTest {
    static final UUID ID = UUID.fromString("00000000-0000-4000-8000-0000000000f1");
    static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000f2");
    static final CombatProfile PROFILE = CombatProfile.resolve("pack",
            Map.of("force", "force", "finesse", "finesse", "insight", "insight", "resolve", "resolve"), "health", null, Map.of());
    static final EncounterObjective WIN = new EncounterObjective("win", Kind.DEFEAT_HOSTILES, null, null, 0);
    static final EncounterObjective RUN = new EncounterObjective("run", Kind.ESCAPE, null, null, 0);

    /** Construct a stateless fixture. */
    EncounterFeasibilityTest() {
    }

    /** An armed player can win the patrol; an unarmed one cannot, which refuses only a stake that asks for a defeat. */
    @Test
    void admitsAWinnableFightAndRefusesAnEliminationTheUnarmedCannotWin() {
        var patrol = template(zones(zone("road", 0), zone("camp", 0)), both("road", "camp"), Set.of(),
                List.of(hostile("sentry", "camp", stats(10, 1))), List.of(WIN), List.of());
        assertTrue(check(patrol, "blade").admitted());
        assertEquals(List.of(new Finding(Violation.UNREACHABLE_OBJECTIVE, "win", Reason.COUNTERED),
                new Finding(Violation.HARD_COUNTER, "sentry", Reason.UNHARMABLE)), check(patrol).findings());

        var hold = template(zones(zone("road", 0), zone("camp", 0)), both("road", "camp"), Set.of(),
                List.of(hostile("sentry", "camp", stats(10, 1))),
                List.of(new EncounterObjective("hold", Kind.SURVIVE, null, null, 3)), List.of());
        assertTrue(check(hold).admitted(), "no stake asks for the sentry's defeat");
    }

    /** QA-14: an objective behind a zone its occupants fill, or longer than the round cap, is unreachable. */
    @Test
    void refusesAnObjectiveNoWalkOrRoundCanMeet() {
        var loot = new EncounterObjective("loot", Kind.REACH_ZONE, "vault", null, 0);
        var vault = template(zones(zone("road", 0), zone("door", 1), zone("vault", 0), zone("gate", 0)),
                edges(both("road", "door"), both("door", "vault"), List.of(open("road", "gate"))), Set.of("gate"),
                List.of(hostile("warden", "door", stats(10, 1))), List.of(loot, RUN), List.of());
        assertEquals(List.of(new Finding(Violation.UNREACHABLE_OBJECTIVE, "loot", Reason.NO_PATH)), check(vault, "blade").findings(),
                "the warden fills the only doorway, and a defeated warden keeps its place");
        var wide = template(zones(zone("road", 0), zone("door", 2), zone("vault", 0), zone("gate", 0)),
                edges(both("road", "door"), both("door", "vault"), List.of(open("road", "gate"))), Set.of("gate"),
                List.of(hostile("warden", "door", stats(10, 1))), List.of(loot, RUN), List.of());
        assertTrue(check(wide, "blade").admitted(), "a doorway with room left stays open");

        var hall = template(zones(zone("road", 0), zone("hall", 1), zone("gate", 0)),
                edges(both("road", "hall"), List.of(open("hall", "gate"))), Set.of("gate"),
                List.of(hostile("warden", "hall", stats(10, 1))), List.of(RUN), List.of());
        assertEquals(List.of(new Finding(Violation.UNREACHABLE_OBJECTIVE, "run", Reason.NO_PATH)), check(hall, "blade").findings());
        var unbar = new EncounterAction("unbar", EncounterAction.Kind.SCENE, null, null, 1, null,
                new EncounterAction.Passage("road", "gate", false));
        var barred = edges(both("road", "hall"), List.of(open("hall", "gate"), shut("road", "gate")));
        assertTrue(check(template(zones(zone("road", 0), zone("hall", 1), zone("gate", 0)), barred, Set.of("gate"),
                List.of(hostile("warden", "hall", stats(10, 1))), List.of(RUN), List.of(unbar)), "blade").admitted(),
                "a declared scene action opens the barred gate");
        var fromHall = new EncounterAction("unbar", EncounterAction.Kind.SCENE, null, null, 1, "hall",
                new EncounterAction.Passage("road", "gate", false));
        assertEquals(List.of(new Finding(Violation.UNREACHABLE_OBJECTIVE, "run", Reason.NO_PATH)),
                check(template(zones(zone("road", 0), zone("hall", 1), zone("gate", 0)), barred, Set.of("gate"),
                        List.of(hostile("warden", "hall", stats(10, 1))), List.of(RUN), List.of(fromHall)), "blade").findings(),
                "an action usable only from the full hall opens nothing");

        for (int rounds : List.of(30, 31)) {
            var hold = template(zones(zone("road", 0), zone("camp", 0)), both("road", "camp"), Set.of(),
                    List.of(hostile("sentry", "camp", stats(10, 1))),
                    List.of(new EncounterObjective("hold", Kind.SURVIVE, null, null, rounds)), List.of());
            assertEquals(rounds > PROFILE.roundCap()
                    ? List.of(new Finding(Violation.UNREACHABLE_OBJECTIVE, "hold", Reason.ROUND_CAP)) : List.of(),
                    check(hold, "blade").findings(), rounds + " rounds");
        }
    }

    /** QA-14: armor beyond every blow and a Guard beyond the best roll are hidden counters; penetration or a tick answers them. */
    @Test
    void refusesAHostileThePlayerCanNeverHarm() {
        var golem = arena(hostile("golem", "camp", stats(10, 10)));
        var unharmable = List.of(new Finding(Violation.UNREACHABLE_OBJECTIVE, "win", Reason.COUNTERED),
                new Finding(Violation.HARD_COUNTER, "golem", Reason.UNHARMABLE));
        assertEquals(unharmable, check(golem, "blade").findings(), "8 damage never exceeds armor 10");
        assertTrue(check(golem, "pick").admitted(), "armor 10 less penetration 4 is 6, under 8");
        assertTrue(check(golem, "venom").admitted(), "a bleeding tick on any hit ignores armor");
        assertEquals(unharmable, check(golem, "lateVenom").findings(), "a tick after damage needs Health taken first");
        var parley = new EncounterAction("parley", EncounterAction.Kind.SOCIAL, EncounterAction.Intent.NEGOTIATE,
                EncounterAction.Recipients.HOSTILE, 1, null, null, null,
                new Negotiation.Terms("yield", Negotiation.Terms.Kind.RECIPIENT_SURRENDERS, 0), null);
        var truce = new EncounterAction("parley", EncounterAction.Kind.SOCIAL, EncounterAction.Intent.NEGOTIATE,
                EncounterAction.Recipients.HOSTILE, 1, null, null, null,
                new Negotiation.Terms("peace", Negotiation.Terms.Kind.TRUCE, 0), null);
        var camp = zones(zone("road", 0), zone("camp", 0));
        assertTrue(check(template(camp, both("road", "camp"), Set.of(), List.of(hostile("golem", "camp", stats(10, 10))),
                List.of(WIN), List.of(parley)), "blade").admitted(), "a declared surrender takes it out without a blow");
        assertEquals(unharmable, check(template(camp, both("road", "camp"), Set.of(),
                List.of(hostile("golem", "camp", stats(10, 10))), List.of(WIN), List.of(truce)), "blade").findings(),
                "a truce ends the fight but defeats nobody");

        // Best score 25 against Guard 40 - 4 = 36 never hits; against Guard 29 - 4 = 25 it can.
        assertEquals(List.of(new Finding(Violation.UNREACHABLE_OBJECTIVE, "win", Reason.COUNTERED),
                new Finding(Violation.HARD_COUNTER, "wraith", Reason.UNHARMABLE)),
                check(arena(hostile("wraith", "camp", stats(40, 0))), "blade").findings());
        assertTrue(check(arena(hostile("wraith", "camp", stats(29, 0))), "blade").admitted());
        assertTrue(check(template(camp, both("road", "camp"), Set.of(), List.of(hostile("golem", "camp", stats(10, 10))),
                List.of(new EncounterObjective("hold", Kind.SURVIVE, null, null, 3)), List.of()), "blade").admitted(),
                "an unbeatable opponent is no counter when no stake asks for its defeat");
    }

    /** QA-14: a hostile no harmful means can reach, wherever it walks, is a hidden counter; one that can come down is not. */
    @Test
    void refusesAHostileThePlayerCanNeverReach() {
        var ledge = zones(zone("road", 0), new ZoneGraph.Zone("ledge", Set.of(), ZoneGraph.Cover.NONE, true, 0));
        var sealed = List.of(shut("road", "ledge"), shut("ledge", "road"));
        var outOfReach = List.of(new Finding(Violation.UNREACHABLE_OBJECTIVE, "win", Reason.COUNTERED),
                new Finding(Violation.HARD_COUNTER, "sniper", Reason.OUT_OF_REACH));
        var hidden = template(ledge, sealed, Set.of(), List.of(hostile("sniper", "ledge", stats(10, 1))), List.of(WIN), List.of());
        assertEquals(outOfReach, check(hidden, "blade").findings(), "melee never reaches the barred ledge");
        assertEquals(outOfReach, check(hidden, "bow").findings(), "an obscured ledge hides the sniper from a shot");
        var visible = zones(zone("road", 0), zone("ledge", 0));
        assertTrue(check(template(visible, sealed, Set.of(), List.of(hostile("sniper", "ledge", stats(10, 1))), List.of(WIN),
                List.of()), "bow").admitted(), "a bow reaches a visible ledge across the bars");
        var covered = zones(zone("road", 0), new ZoneGraph.Zone("ledge", Set.of(), ZoneGraph.Cover.FULL, false, 0));
        assertEquals(outOfReach, check(template(covered, sealed, Set.of(), List.of(hostile("sniper", "ledge", stats(10, 1))),
                List.of(WIN), List.of()), "bow").findings(), "full cover stops every shot from outside");
        assertTrue(check(template(ledge, List.of(shut("road", "ledge"), open("ledge", "road")), Set.of(),
                List.of(hostile("sniper", "ledge", stats(10, 1))), List.of(WIN), List.of()), "blade").admitted(),
                "a sniper that can climb down can be met");
    }

    /** QA-14: an effect that denies the player's actions for the rest of the fight is a hidden counter under any stake. */
    @Test
    void refusesAHostileThatCanDenyEveryRemainingAction() {
        var lock = List.of(new Finding(Violation.HARD_COUNTER, "jailer", Reason.ACTION_LOCK));
        assertEquals(lock, check(escape(jailer("shackled", null)), "blade").findings(), "a persistent hold");
        assertEquals(lock, check(escape(jailer("chained", null)), "blade").findings(), "30 activations, the whole round cap");
        assertTrue(check(escape(jailer("bound", null)), "blade").admitted(), "29 activations leave a turn");
        assertTrue(check(escape(jailer("dazed", null)), "blade").admitted(), "until the next activation only");
        assertTrue(check(escape(jailer("rooted", null)), "blade").admitted(), "movement only; actions stay");
        var snare = new CombatReaction("snare", CombatReaction.Trigger.HIT, 0, 0, false, CombatReaction.Attacks.ANY,
                CombatReaction.Kind.APPLY, 0, null, "shackled", true);
        assertEquals(lock, check(escape(jailer(null, snare)), "blade").findings(), "a reaction on the attacker");
        var shield = new CombatReaction("snare", CombatReaction.Trigger.HIT, 0, 0, false, CombatReaction.Attacks.ANY,
                CombatReaction.Kind.APPLY, 0, null, "shackled", false);
        assertTrue(check(escape(jailer(null, shield)), "blade").admitted(), "the same effect on the reactor itself");
    }

    /** Every finding at once, objectives in declared order first, then hostile participants in roster order. */
    @Test
    void reportsEveryFindingInItsFixedOrder() {
        var encounter = template(zones(zone("road", 0), zone("hall", 1), zone("gate", 0), zone("camp", 0)),
                edges(both("road", "hall"), both("road", "camp"), List.of(open("hall", "gate"))), Set.of("gate"),
                List.of(hostile("golem", "camp", stats(10, 10)), jailerIn("hall", "shackled")), List.of(RUN, WIN), List.of());
        var verdict = check(encounter, "blade");
        assertEquals(List.of(new Finding(Violation.UNREACHABLE_OBJECTIVE, "run", Reason.NO_PATH),
                new Finding(Violation.UNREACHABLE_OBJECTIVE, "win", Reason.COUNTERED),
                new Finding(Violation.HARD_COUNTER, "golem", Reason.UNHARMABLE),
                new Finding(Violation.HARD_COUNTER, "jailer", Reason.ACTION_LOCK)), verdict.findings());
        assertEquals(List.of("unreachable-objective", "unreachable-objective", "hard-counter", "hard-counter"),
                verdict.findings().stream().map(finding -> finding.violation().code()).toList());
        assertEquals(List.of("no-path", "countered", "unharmable", "action-lock"),
                verdict.findings().stream().map(finding -> finding.reason().code()).toList());
        assertEquals(verdict, check(encounter, "blade"), "the same frozen encounter always gives the same verdict");
    }

    /** One hostile in {@code camp} next to the player's {@code road}, to be defeated. */
    static EncounterTemplate arena(Participant hostile) {
        return template(zones(zone("road", 0), zone("camp", 0)), both("road", "camp"), Set.of(), List.of(hostile), List.of(WIN),
                List.of());
    }

    /** The jailer next to the player's {@code road}, with an open escape {@code gate}; escaping is the only stake. */
    static EncounterTemplate escape(Participant jailer) {
        return template(zones(zone("road", 0), zone("camp", 0), zone("gate", 0)),
                edges(both("road", "camp"), List.of(open("road", "gate"))), Set.of("gate"), List.of(jailer), List.of(RUN),
                List.of());
    }

    /** A jailer in {@code camp} whose chain applies an effect, or which declares a reaction. */
    static Participant jailer(String effect, CombatReaction reaction) {
        if (reaction == null) {
            return jailerIn("camp", effect);
        }
        return new Participant("jailer", Side.HOSTILE, "camp", null, Threat.STANDARD, stats(10, 1), List.of(), List.of(reaction),
                Map.of(CombatReaction.BRACE_ID, CombatReaction.Policy.FIRST, reaction.id(), CombatReaction.Policy.FIRST),
                List.of());
    }

    /** A jailer in a zone whose chain applies an effect on any hit. */
    static Participant jailerIn(String zone, String effect) {
        var chain = new CombatAttack("chain", "force", 0, 1, 4, "force", 0, 0, 0, CombatAttack.Defense.GUARD, false, null,
                List.of(new CombatAttack.OnHit(effect, CombatEffect.Phase.POST_HIT, false)));
        return new Participant("jailer", Side.HOSTILE, zone, null, Threat.STANDARD, stats(10, 1), List.of(chain));
    }

    static Participant hostile(String id, String zone, EncounterTemplate.Stats stats) {
        return new Participant(id, Side.HOSTILE, zone, null, Threat.STANDARD, stats);
    }

    /** Statistics with the given Guard and armor, Resistance 10, Health 20 and every role 2. */
    static EncounterTemplate.Stats stats(int guard, int armor) {
        var roles = new HashMap<String, Integer>();
        CombatProfile.ROLES.forEach(role -> roles.put(role, 2));
        return new EncounterTemplate.Stats(20, 0, guard, 10, armor, roles);
    }

    static EncounterTemplate template(Map<String, ZoneGraph.Zone> zones, List<ZoneGraph.Edge> edges, Set<String> escapes,
            List<Participant> participants, List<EncounterObjective> objectives, List<EncounterAction> actions) {
        return new EncounterTemplate("trial", "pack", "trailhead", new ZoneGraph(zones, edges), "road", escapes, participants,
                objectives, OpeningPolicy.INITIATIVE, actions);
    }

    /** Freeze for the fixture player wearing the given items and check it. */
    static EncounterFeasibility.Verdict check(EncounterTemplate template, String... worn) {
        var facts = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("force", 2L, "finesse", 2L, "insight", 2L, "resolve", 2L),
                new FrozenEncounter.Pool(8, 8), null, List.of(worn), Set.of());
        return EncounterFeasibility.check(FrozenEncounter.freeze(ID, template, PROFILE, facts, null, library()));
    }

    /** The fixture's weapons and effects. */
    static FrozenEncounter.Library library() {
        var blade = new CombatAttack("blade", "force", 0, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, false, null);
        var pick = new CombatAttack("pick", "force", 0, 1, 6, "force", 0, 4, 0, CombatAttack.Defense.GUARD, false, null);
        var bow = new CombatAttack("bow", "finesse", 0, 1, 6, null, 0, 0, 2, CombatAttack.Defense.GUARD, false, null);
        var venom = new CombatAttack("venom", "force", 0, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, false, null,
                List.of(new CombatAttack.OnHit("bleeding", CombatEffect.Phase.POST_HIT, false)));
        var lateVenom = new CombatAttack("lateVenom", "force", 0, 1, 6, "force", 0, 0, 0, CombatAttack.Defense.GUARD, false,
                null, List.of(new CombatAttack.OnHit("bleeding", CombatEffect.Phase.POST_DAMAGE, false)));
        var gear = new TreeMap<String, EncounterGear>();
        for (CombatAttack attack : List.of(blade, pick, bow, venom, lateVenom)) {
            gear.put(attack.id(), new EncounterGear(0, 0, null, attack));
        }
        var effects = new TreeMap<String, CombatEffect>();
        effects.put("bleeding", effect("bleeding", CombatEffect.Clock.ROUND_ENDS, 2, Set.of(),
                List.of(new CombatEffect.Tick(CombatEffect.Phase.ROUND_END, 1, false))));
        effects.put("shackled", effect("shackled", CombatEffect.Clock.PERSISTENT, 0, Set.of(CombatEffect.Prevents.ACTIONS),
                List.of()));
        effects.put("chained", effect("chained", CombatEffect.Clock.ACTIVATIONS, 30, Set.of(CombatEffect.Prevents.ACTIONS),
                List.of()));
        effects.put("bound", effect("bound", CombatEffect.Clock.ACTIVATIONS, 29, Set.of(CombatEffect.Prevents.ACTIONS),
                List.of()));
        effects.put("dazed", effect("dazed", CombatEffect.Clock.NEXT_ACTIVATION, 0, Set.of(CombatEffect.Prevents.ACTIONS),
                List.of()));
        effects.put("rooted", effect("rooted", CombatEffect.Clock.PERSISTENT, 0, Set.of(CombatEffect.Prevents.MOVEMENT),
                List.of()));
        return new FrozenEncounter.Library(gear, effects, Map.of(), Map.of());
    }

    static CombatEffect effect(String id, CombatEffect.Clock clock, int count, Set<CombatEffect.Prevents> prevents,
            List<CombatEffect.Tick> ticks) {
        return new CombatEffect(id, id, CombatEffect.Stacking.STRONGEST, 1, clock, count, false, 0, 0, 0, 0, 0, prevents, ticks,
                false);
    }

    static Map<String, ZoneGraph.Zone> zones(ZoneGraph.Zone... zones) {
        var map = new TreeMap<String, ZoneGraph.Zone>();
        for (ZoneGraph.Zone zone : zones) {
            map.put(zone.id(), zone);
        }
        return map;
    }

    static ZoneGraph.Zone zone(String id, int capacity) {
        return new ZoneGraph.Zone(id, Set.of(), ZoneGraph.Cover.NONE, false, capacity);
    }

    @SafeVarargs
    static List<ZoneGraph.Edge> edges(List<ZoneGraph.Edge>... groups) {
        var all = new ArrayList<ZoneGraph.Edge>();
        for (List<ZoneGraph.Edge> group : groups) {
            all.addAll(group);
        }
        return all;
    }

    static List<ZoneGraph.Edge> both(String a, String b) {
        return List.of(open(a, b), open(b, a));
    }

    static ZoneGraph.Edge open(String from, String to) {
        return new ZoneGraph.Edge(from, to, false, false);
    }

    static ZoneGraph.Edge shut(String from, String to) {
        return new ZoneGraph.Edge(from, to, false, true);
    }
}
