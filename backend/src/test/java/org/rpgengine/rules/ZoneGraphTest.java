package org.rpgengine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * P11.1 zone graph: range distance ignores direction and blocking, movement follows unblocked directed edges
 * with the profile's costs, and construction refuses graphs that are not a coherent connected space.
 */
class ZoneGraphTest {
    private static final CombatProfile DEFAULT = CombatProfile.resolve("pack", Map.of("force", "a", "finesse", "a",
            "insight", "a", "resolve", "a"), "health", null, Map.of());

    /** Construct a stateless fixture. */
    ZoneGraphTest() {
    }

    /** The worked example: entry, yard and an escape route reachable through a difficult edge or the yard. */
    @Test
    void movementCostsFollowTheProfile() {
        var graph = graph(List.of(edge("entry", "yard", false, false), edge("yard", "entry", false, false),
                edge("entry", "escape", true, false), edge("yard", "escape", false, false)), "entry", "yard", "escape");
        assertEquals(1, graph.stepCost("entry", "yard", DEFAULT));
        assertEquals(2, graph.stepCost("entry", "escape", DEFAULT), "difficult terrain doubles the edge");
        assertEquals(-1, graph.stepCost("escape", "yard", DEFAULT), "edges are directed");
        assertEquals(2, graph.travelCost("entry", "escape", DEFAULT));
        assertEquals(0, graph.travelCost("yard", "yard", DEFAULT));
        assertEquals(-1, graph.travelCost("escape", "entry", DEFAULT));
        assertEquals(Set.of("entry", "yard", "escape"), graph.reachable("entry"));
        assertEquals(Set.of("escape"), graph.reachable("escape"));
        var tuned = CombatProfile.resolve("pack", DEFAULT.roles(), "health", null,
                Map.of("actionPoints", 6, "moveCost", 2, "difficultTerrainMultiplier", 3));
        assertEquals(6, graph.stepCost("entry", "escape", tuned));
        assertEquals(4, graph.travelCost("entry", "escape", tuned), "the cheaper route goes through the yard");
    }

    /** Range bands count bordering steps in any direction, through blocked edges too. */
    @Test
    void distanceIgnoresDirectionAndBlocking() {
        var graph = graph(List.of(edge("gate", "booth", false, true), edge("booth", "vault", false, false)),
                "gate", "booth", "vault");
        assertEquals(0, graph.distance("gate", "gate"));
        assertEquals(1, graph.distance("booth", "gate"));
        assertEquals(2, graph.distance("vault", "gate"));
        assertEquals(-1, graph.stepCost("gate", "booth", DEFAULT), "a blocked edge cannot be crossed");
        assertEquals(Set.of("gate"), graph.reachable("gate"));
        assertThrows(IllegalArgumentException.class, () -> graph.distance("gate", "moon"));
    }

    /** Islands, self-loops, unknown zones, duplicate edges and size limits are refused. */
    @Test
    void refusesIncoherentSpaces() {
        var island = assertThrows(IllegalArgumentException.class, () -> graph(List.of(edge("a", "b", false, false)),
                "a", "b", "c"));
        assertTrue(island.getMessage().contains("[c]"), island.getMessage());
        assertThrows(IllegalArgumentException.class, () -> edge("a", "a", false, false));
        assertThrows(IllegalArgumentException.class, () -> graph(List.of(edge("a", "z", false, false)), "a"));
        assertThrows(IllegalArgumentException.class, () -> graph(List.of(edge("a", "b", false, false),
                edge("a", "b", true, false)), "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> graph(List.of()));
        var many = new String[ZoneGraph.MAX_ZONES + 1];
        var chain = new ArrayList<ZoneGraph.Edge>();
        for (int index = 0; index < many.length; index++) {
            many[index] = "z" + index;
            if (index > 0) {
                chain.add(edge("z" + (index - 1), "z" + index, false, false));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> graph(chain, many));
        assertThrows(IllegalArgumentException.class, () -> new ZoneGraph.Zone("a", Set.of(), ZoneGraph.Cover.NONE, false,
                ZoneGraph.MAX_CAPACITY + 1));
        // A single zone is a legal (if small) space.
        assertEquals(Set.of("solo"), graph(List.of(), "solo").reachable("solo"));
    }

    /** The frozen form lists zones by ID and edges in a stable order, whatever the authored order. */
    @Test
    void canonicalFormIsStable() {
        var forward = graph(List.of(edge("b", "a", false, false), edge("a", "b", true, false)), "b", "a");
        var backward = graph(List.of(edge("a", "b", true, false), edge("b", "a", false, false)), "a", "b");
        assertEquals(CanonicalJson.write(forward.canonicalForm()), CanonicalJson.write(backward.canonicalForm()));
    }

    private static ZoneGraph.Edge edge(String from, String to, boolean difficult, boolean blocked) {
        return new ZoneGraph.Edge(from, to, difficult, blocked);
    }

    private static ZoneGraph graph(List<ZoneGraph.Edge> edges, String... zones) {
        var map = new LinkedHashMap<String, ZoneGraph.Zone>();
        for (String zone : zones) {
            map.put(zone, new ZoneGraph.Zone(zone, Set.of(), ZoneGraph.Cover.NONE, false, 0));
        }
        return new ZoneGraph(map, edges);
    }
}
