package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.CapabilitySnapshot.Actor;
import org.rpgengine.combat.CapabilitySnapshot.Membership;
import org.rpgengine.combat.CapabilitySnapshot.Status;
import org.rpgengine.combat.EncounterTemplate.Threat;
import org.rpgengine.combat.FrozenEncounter.Pool;
import org.rpgengine.combat.ThreatBudget.Entry;
import org.rpgengine.combat.ThreatBudget.Finding;
import org.rpgengine.combat.ThreatBudget.Violation;
import org.rpgengine.rules.CombatProfile;

/**
 * The capability snapshot and the threat and roster limits of docs/12 section 4 (P13.1, QA-14 for invalid and over-budget
 * rosters): capacity from levels, Health and relied resources in exact arithmetic, the catalog costs with level scaling,
 * the difficulty band compared exactly, the hostile-body limit, and a snapshot hash that follows facts, not input order.
 */
class ThreatBudgetTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** Construct a stateless fixture. */
    ThreatBudgetTest() {
    }

    /** Capacity is base by level times preparedness, summed exactly and rounded down once. */
    @Test
    void capacityFollowsTheHeuristic() {
        assertEquals(8, ThreatBudget.capacity(List.of(actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 1, 8, 8, Map.of()))));
        assertEquals(12, ThreatBudget.capacity(List.of(actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 3, 8, 8, Map.of()))));
        // 8 × (0.55 + 0.30 × 0.5 + 0.15 × 0) = 5.6
        var hurt = actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 1, 4, 8, Map.of("grit", new Pool(0, 6)));
        assertEquals(5, ThreatBudget.capacity(List.of(hurt)));
        // 10 × (0.55 + 0.30 × 0.8 + 0.15 × 1/3) = 8.4; with the 5.6 above the exact sum is 14, which a binary sum can miss.
        var companion = actor(COMPANION, Membership.COMPANION, Status.ACTIVE, 2, 8, 10, Map.of("grit", new Pool(1, 3)));
        assertEquals(14, ThreatBudget.capacity(List.of(hurt, companion)));
    }

    /** The lowest relied resource sets the ratio; an actor relying on none counts a full one. */
    @Test
    void theLowestReliedResourceCounts() {
        var drained = actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 1, 8, 8,
                Map.of("grit", new Pool(6, 6), "charge", new Pool(0, 4)));
        // 8 × (0.55 + 0.30 + 0) = 6.8
        assertEquals(6, ThreatBudget.capacity(List.of(drained)));
        var full = actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 1, 8, 8, Map.of("grit", new Pool(6, 6)));
        assertEquals(8, ThreatBudget.capacity(List.of(full)));
    }

    /** Absent, defeated, excluded and zero-Health actors add neither capacity nor party size. */
    @Test
    void onlyContributingActorsCount() {
        for (Status status : List.of(Status.ABSENT, Status.DEFEATED, Status.EXCLUDED)) {
            var budget = ThreatBudget.of(snapshot(EncounterDifficulty.STANDARD, fresh(PLAYER, Membership.PLAYER),
                    actor(COMPANION, Membership.COMPANION, status, 5, 10, 10, Map.of())));
            assertEquals(8, budget.capacity(), status.name());
            assertEquals(1, budget.partySize(), status.name());
            assertEquals(3, budget.hostileLimit(), status.name());
        }
        var fallen = ThreatBudget.of(snapshot(EncounterDifficulty.STANDARD,
                actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 1, 0, 8, Map.of())));
        assertEquals(0, fallen.partySize());
        assertEquals(0, fallen.capacity());
        var verdict = fallen.check(List.of(new Entry("raider", Threat.MINOR, 1)));
        assertEquals(List.of(new Finding(Violation.NO_ACTIVE_PARTY, null)), verdict.findings());
    }

    /** Catalog costs at level 1 and the rounded-up level scaling. */
    @Test
    void costsScaleWithLevel() {
        assertEquals(List.of(4, 8, 12, 20), List.of(Threat.values()).stream().map(t -> ThreatBudget.cost(t, 1)).toList());
        assertEquals(List.of(5, 10, 15, 24), List.of(Threat.values()).stream().map(t -> ThreatBudget.cost(t, 2)).toList());
        assertEquals(6, ThreatBudget.cost(Threat.MINOR, 3));
        assertEquals(12, ThreatBudget.cost(Threat.STANDARD, 3));
        assertEquals(96, ThreatBudget.cost(Threat.ANCHOR, CapabilitySnapshot.LEVEL_MAX));
        assertThrows(IllegalArgumentException.class, () -> ThreatBudget.cost(Threat.MINOR, 0));
        assertThrows(IllegalArgumentException.class, () -> ThreatBudget.cost(Threat.MINOR, CapabilitySnapshot.LEVEL_MAX + 1));
    }

    /** A roster inside the band is admitted; one above it is over budget, one below it under budget (QA-14). */
    @Test
    void refusesARosterOutsideTheBand() {
        var budget = ThreatBudget.of(snapshot(EncounterDifficulty.STANDARD, fresh(PLAYER, Membership.PLAYER)));
        assertEquals(8, budget.capacity());
        assertEquals(8, budget.minimum());
        assertEquals(8, budget.maximum());
        assertTrue(budget.check(List.of(new Entry("raider", Threat.STANDARD, 1))).admitted());
        assertTrue(budget.check(List.of(new Entry("wolf", Threat.MINOR, 1), new Entry("hound", Threat.MINOR, 1))).admitted());
        var over = budget.check(List.of(new Entry("raider", Threat.STANDARD, 1), new Entry("wolf", Threat.MINOR, 1)));
        assertEquals(12, over.cost());
        assertEquals(List.of(new Finding(Violation.OVER_BUDGET, null)), over.findings());
        assertFalse(over.admitted());
        // One level above the band's cost is enough to exceed it.
        assertEquals(List.of(new Finding(Violation.OVER_BUDGET, null)),
                budget.check(List.of(new Entry("raider", Threat.STANDARD, 2))).findings());
        assertEquals(List.of(new Finding(Violation.UNDER_BUDGET, null)),
                budget.check(List.of(new Entry("wolf", Threat.MINOR, 1))).findings());
        var hard = ThreatBudget.of(snapshot(EncounterDifficulty.HARD, fresh(PLAYER, Membership.PLAYER)));
        // 8 × 120..140% = 9.6..11.2: only a threat of 10 or 11 fits.
        assertEquals(10, hard.minimum());
        assertEquals(11, hard.maximum());
        assertTrue(hard.check(List.of(new Entry("raider", Threat.STANDARD, 2))).admitted());
        assertEquals(List.of(new Finding(Violation.OVER_BUDGET, null)),
                hard.check(List.of(new Entry("brute", Threat.ELITE, 1))).findings());
    }

    /** Every structural fault is reported in a fixed order, so one repair request can name them all (QA-14). */
    @Test
    void refusesAnInvalidRoster() {
        var budget = ThreatBudget.of(snapshot(EncounterDifficulty.SEVERE, fresh(PLAYER, Membership.PLAYER)));
        var verdict = budget.check(List.of(new Entry("player", Threat.MINOR, 1), new Entry("raider", Threat.MINOR, 1),
                new Entry("raider", Threat.MINOR, 1), new Entry("Raider", Threat.MINOR, 1),
                new Entry("brute", Threat.ELITE, 0)));
        assertEquals(List.of(new Finding(Violation.RESERVED_ID, "player"), new Finding(Violation.DUPLICATE_ID, "raider"),
                new Finding(Violation.INVALID_ID, "Raider"), new Finding(Violation.LEVEL_OUT_OF_RANGE, "brute"),
                new Finding(Violation.TOO_MANY_HOSTILES, null), new Finding(Violation.OVER_BUDGET, null)),
                verdict.findings());
        assertEquals(16, verdict.cost());
        assertEquals(5, verdict.hostiles());
        assertEquals(List.of(new Finding(Violation.EMPTY_ROSTER, null)), budget.check(List.of()).findings());
    }

    /** Hostile bodies stop at party size plus two and never pass one fight's participant limit. */
    @Test
    void limitsHostileBodies() {
        var party = new ArrayList<Actor>();
        party.add(fresh(PLAYER, Membership.PLAYER));
        for (int index = 0; index < CapabilitySnapshot.COMPANIONS_MAX; index++) {
            party.add(fresh(UUID.fromString("00000000-0000-0000-0001-00000000000" + index), Membership.COMPANION));
        }
        var budget = ThreatBudget.of(snapshot(EncounterDifficulty.STANDARD, party.toArray(Actor[]::new)));
        assertEquals(9, budget.partySize());
        assertEquals(72, budget.capacity());
        assertEquals(CombatProfile.PARTICIPANTS_MAX, budget.hostileLimit());
        var nine = new ArrayList<Entry>();
        for (int index = 0; index < 9; index++) {
            nine.add(new Entry("raider-" + index, Threat.STANDARD, 1));
        }
        assertEquals(List.of(new Finding(Violation.TOO_MANY_HOSTILES, null)), budget.check(nine).findings());
        var duo = ThreatBudget.of(snapshot(EncounterDifficulty.GENTLE, fresh(PLAYER, Membership.PLAYER),
                fresh(COMPANION, Membership.COMPANION)));
        // Capacity 16, gentle band 9.6..12.8: three minors (12) fit; five break both the body limit 4 and the band.
        assertEquals(4, duo.hostileLimit());
        assertTrue(duo.check(List.of(new Entry("a", Threat.MINOR, 1), new Entry("b", Threat.MINOR, 1),
                new Entry("c", Threat.MINOR, 1))).admitted());
        assertEquals(List.of(new Finding(Violation.TOO_MANY_HOSTILES, null), new Finding(Violation.OVER_BUDGET, null)),
                duo.check(List.of(new Entry("a", Threat.MINOR, 1), new Entry("b", Threat.MINOR, 1),
                        new Entry("c", Threat.MINOR, 1), new Entry("d", Threat.MINOR, 1), new Entry("e", Threat.MINOR, 1)))
                        .findings());
    }

    /** The hash follows the facts, not the order or map type they were supplied in, and changes with any fact. */
    @Test
    void theSnapshotHashFollowsFacts() {
        var first = new CapabilitySnapshot("pack-hash", "profile-hash", 7, "trailhead", Set.of("night", "road"),
                EncounterDifficulty.STANDARD, List.of(
                        new Actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 2, new Pool(6, 8),
                                Map.of("grit", new Pool(2, 6)), List.of("sword", "cloak"), Set.of("rested", "alert"),
                                Set.of("steady", "riposte"), Map.of("bladework", 1L, "lore", 2L),
                                Map.of("arrows", 3L)),
                        fresh(COMPANION, Membership.COMPANION)));
        var reordered = new CapabilitySnapshot("pack-hash", "profile-hash", 7, "trailhead", Set.of("road", "night"),
                EncounterDifficulty.STANDARD, List.of(fresh(COMPANION, Membership.COMPANION),
                        new Actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 2, new Pool(6, 8),
                                new java.util.TreeMap<>(Map.of("grit", new Pool(2, 6))), List.of("cloak", "sword"),
                                Set.of("alert", "rested"), Set.of("riposte", "steady"),
                                new java.util.TreeMap<>(Map.of("lore", 2L, "bladework", 1L)), Map.of("arrows", 3L))));
        assertEquals(first.canonical(), reordered.canonical());
        assertEquals(first.hash(), reordered.hash());
        assertEquals(PLAYER, reordered.player().actorId());
        assertTrue(first.canonical().contains("\"heuristic\":\"" + ThreatBudget.HEURISTIC + "\""));
        var spent = new CapabilitySnapshot("pack-hash", "profile-hash", 7, "trailhead", Set.of("night", "road"),
                EncounterDifficulty.STANDARD, List.of(
                        new Actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 2, new Pool(6, 8),
                                Map.of("grit", new Pool(2, 6)), List.of("sword", "cloak"), Set.of("rested", "alert"),
                                Set.of("steady", "riposte"), Map.of("bladework", 1L, "lore", 2L),
                                Map.of("arrows", 2L)),
                        fresh(COMPANION, Membership.COMPANION)));
        assertNotEquals(first.hash(), spent.hash());
        var harder = new CapabilitySnapshot("pack-hash", "profile-hash", 7, "trailhead", Set.of("night", "road"),
                EncounterDifficulty.HARD, first.actors());
        assertNotEquals(first.hash(), harder.hash());
    }

    /** The player's entry reads the committed entry facts: worn items only, and Focus as a relied resource when it exists. */
    @Test
    void readsThePlayerFromEntryFacts() {
        var roles = Map.of("force", "might", "finesse", "awareness", "insight", "awareness", "resolve", "might");
        var withFocus = CombatProfile.resolve("pack", roles, "vigor", "grit", Map.of());
        var withoutFocus = CombatProfile.resolve("pack", roles, "vigor", null, Map.of());
        var facts = new FrozenEncounter.PlayerFacts(PLAYER, Map.of("might", 2L, "awareness", 2L), new Pool(8, 8),
                new Pool(0, 6), List.of("sword"), Set.of("rested"), Map.of("bladework", 1L), Set.of("steady"));
        var player = Actor.player(facts, withFocus, 1, Map.of("arrows", 0L));
        assertEquals(Map.of("grit", new Pool(0, 6)), player.resources());
        assertEquals(List.of("sword"), player.equipment());
        assertEquals(Set.of("steady"), player.abilities());
        assertEquals(Map.of("arrows", 0L), player.ammunition());
        // 8 × (0.55 + 0.30 + 0) = 6.8 with drained Focus; a profile without Focus counts a full ratio.
        assertEquals(6, ThreatBudget.capacity(List.of(player)));
        assertEquals(8, ThreatBudget.capacity(List.of(Actor.player(facts, withoutFocus, 1, Map.of()))));
    }

    /** A snapshot has exactly one player, distinct actors, bounded levels and relied resources with a maximum. */
    @Test
    void refusesAnInconsistentSnapshot() {
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(EncounterDifficulty.STANDARD, fresh(COMPANION, Membership.COMPANION)));
        assertThrows(IllegalArgumentException.class, () -> snapshot(EncounterDifficulty.STANDARD,
                fresh(PLAYER, Membership.PLAYER), fresh(COMPANION, Membership.PLAYER)));
        assertThrows(IllegalArgumentException.class, () -> snapshot(EncounterDifficulty.STANDARD,
                fresh(PLAYER, Membership.PLAYER), fresh(PLAYER, Membership.COMPANION)));
        assertThrows(IllegalArgumentException.class,
                () -> actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 0, 8, 8, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 1, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 1, 8, 8, Map.of("grit", new Pool(0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new Actor(PLAYER, Membership.PLAYER, Status.ACTIVE, 1,
                new Pool(8, 8), Map.of(), List.of(), Set.of(), Set.of(), Map.of(), Map.of("arrows", -1L)));
    }

    private static CapabilitySnapshot snapshot(EncounterDifficulty difficulty, Actor... actors) {
        var party = new ArrayList<Actor>();
        Collections.addAll(party, actors);
        return new CapabilitySnapshot("pack-hash", "profile-hash", 1, "trailhead", Set.of(), difficulty, party);
    }

    private static Actor fresh(UUID id, Membership membership) {
        return actor(id, membership, Status.ACTIVE, 1, 8, 8, Map.of());
    }

    private static Actor actor(UUID id, Membership membership, Status status, int level, long health, long healthMax,
            Map<String, Pool> resources) {
        return new Actor(id, membership, status, level, new Pool(health, healthMax), resources, List.of(), Set.of(),
                Set.of(), Map.of(), Map.of());
    }
}
