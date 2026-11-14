package org.rpgengine.combat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.rpgengine.combat.EncounterTemplate.Participant;
import org.rpgengine.combat.EncounterTemplate.Side;
import org.rpgengine.combat.EncounterTemplate.Threat;
import org.rpgengine.rules.CombatProfile;
import org.rpgengine.rules.ZoneGraph;

/**
 * Fixed encounter-planning inputs for the proposal tests (P13.2-A), all at the location {@code trailhead}.
 *
 * <p>Party: one fresh level-1 player, so capacity is 8, and the Standard band admits a threat of exactly 8 with at most three
 * hostile bodies.
 *
 * <p>{@link #ambush()}: the player and the allied {@code guide} start in {@code road}; {@code road} joins {@code brush}
 * (capacity 3), the obscured {@code ridge} (capacity 1) and the escape zone {@code gate}. Hostile participants in authored
 * order: {@code raider} (standard, brush), {@code wolf} (minor, brush), {@code hound} (minor, ridge) and {@code brute} (elite,
 * brush). The authored roster costs 28, far above the band. {@link #patrol()}: one standard {@code sentry} in {@code camp},
 * next to the player's {@code road}; it costs exactly 8.
 */
public final class PlanningFixtures {
    /** The player's campaign actor. */
    public static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    /** Pinned content hash shared with the contract fixtures' binding. */
    public static final String PACK_HASH = "a".repeat(64);
    /** Location of every fixture template. */
    public static final String LOCATION = "trailhead";

    private PlanningFixtures() {
    }

    /**
     * @param difficulty the selected difficulty
     * @param health the player's current Health out of 8
     * @return a snapshot of one level-1 player at {@link #LOCATION}
     */
    public static CapabilitySnapshot snapshot(EncounterDifficulty difficulty, long health) {
        var player = new CapabilitySnapshot.Actor(PLAYER, CapabilitySnapshot.Membership.PLAYER,
                CapabilitySnapshot.Status.ACTIVE, 1, new FrozenEncounter.Pool(health, 8), Map.of(), List.of(), Set.of(),
                Set.of(), Map.of(), Map.of());
        return new CapabilitySnapshot(PACK_HASH, "profile-hash", 7, LOCATION, Set.of("road"), difficulty, List.of(player));
    }

    /** @return the four-hostile ambush, whose authored roster is over any band of the fixture party */
    public static EncounterTemplate ambush() {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        zones.put("road", zone("road", 0, false));
        zones.put("brush", zone("brush", 3, false));
        zones.put("ridge", zone("ridge", 1, true));
        zones.put("gate", zone("gate", 0, false));
        var edges = List.of(edge("road", "brush"), edge("brush", "road"), edge("road", "ridge"), edge("ridge", "road"),
                edge("road", "gate"));
        return new EncounterTemplate("ambush", "pack", LOCATION, new ZoneGraph(zones, edges), "road", Set.of("gate"),
                List.of(new Participant("guide", Side.ALLY, "road", null, Threat.STANDARD, stats()),
                        new Participant("raider", Side.HOSTILE, "brush", null, Threat.STANDARD, stats()),
                        new Participant("wolf", Side.HOSTILE, "brush", null, Threat.MINOR, stats()),
                        new Participant("hound", Side.HOSTILE, "ridge", null, Threat.MINOR, stats()),
                        new Participant("brute", Side.HOSTILE, "brush", null, Threat.ELITE, stats())),
                List.of(new EncounterObjective("win", EncounterObjective.Kind.DEFEAT_HOSTILES, null, null, 0),
                        new EncounterObjective("run", EncounterObjective.Kind.ESCAPE, null, null, 0)),
                OpeningPolicy.INITIATIVE);
    }

    /** @return the one-sentry patrol, whose authored roster costs exactly the Standard band of the fixture party */
    public static EncounterTemplate patrol() {
        var zones = new TreeMap<String, ZoneGraph.Zone>();
        zones.put("road", zone("road", 0, false));
        zones.put("camp", zone("camp", 0, false));
        return new EncounterTemplate("patrol", "pack", LOCATION,
                new ZoneGraph(zones, List.of(edge("road", "camp"), edge("camp", "road"))), "road", Set.of(),
                List.of(new Participant("sentry", Side.HOSTILE, "camp", null, Threat.STANDARD, stats())),
                List.of(new EncounterObjective("win", EncounterObjective.Kind.DEFEAT_HOSTILES, null, null, 0)),
                OpeningPolicy.INITIATIVE);
    }

    /** @return valid statistics with a recognizable Health of 37, which a planning context must never render */
    public static EncounterTemplate.Stats stats() {
        var roles = new HashMap<String, Integer>();
        CombatProfile.ROLES.forEach(role -> roles.put(role, 2));
        return new EncounterTemplate.Stats(37, 0, 10, 10, 1, roles);
    }

    private static ZoneGraph.Zone zone(String id, int capacity, boolean obscured) {
        return new ZoneGraph.Zone(id, Set.of(), ZoneGraph.Cover.NONE, obscured, capacity);
    }

    private static ZoneGraph.Edge edge(String from, String to) {
        return new ZoneGraph.Edge(from, to, false, false);
    }
}
