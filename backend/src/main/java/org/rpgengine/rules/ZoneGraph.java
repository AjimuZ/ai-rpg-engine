package org.rpgengine.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The encounter space of the default ruleset: a small directed graph of zones (P11.1, docs/11 section 5).
 * Zone graph distance is a range band, not a measure in meters.
 *
 * <p>Two relations are derived from the same declared edges:
 * <ul>
 *   <li><b>Adjacency</b> (range and line of sight) ignores direction and blocking: an edge, even a blocked
 *       one, says the two zones border each other. {@link #distance} counts these steps, so a target across
 *       a closed gate can still be within a ranged weapon's band.</li>
 *   <li><b>Traversal</b> (movement) follows only unblocked edges in their direction. A normal edge costs the
 *       profile's {@code moveCost}; a difficult edge multiplies it by {@code difficultTerrainMultiplier}
 *       ({@link #stepCost}, {@link #travelCost}).</li>
 * </ul>
 *
 * <p>Invariants, checked on construction: 1..{@link #MAX_ZONES} zones with distinct IDs, at most
 * {@link #MAX_EDGES} directed edges, every edge between two different declared zones, at most one edge per
 * ordered pair, and every zone adjacent-connected to every other (no unreachable island). Violations throw
 * {@link IllegalArgumentException} with an author-readable message; the pack compiler reports it at the
 * encounter's location.
 *
 * @param zones zones by ID, in ID order
 * @param edges directed edges, sorted by origin then destination
 */
public record ZoneGraph(Map<String, Zone> zones, List<Edge> edges) {
    /** Most zones one encounter may declare; keeps graph queries trivially cheap and the UI legible. */
    public static final int MAX_ZONES = 16;
    /** Most directed edges, a two-way link counting twice. */
    public static final int MAX_EDGES = 64;
    /** Most actors a zone may be declared to hold. */
    public static final int MAX_CAPACITY = 16;

    /** Cover a zone offers its occupants against direct attacks (docs/11 section 5). */
    public enum Cover {
        /** No protection. */
        NONE,
        /** Partial cover: the profile's {@code partialCoverBonus} to Guard against relevant ranged attacks. */
        PARTIAL,
        /** Full cover: blocks a direct attack. */
        FULL;

        /** @return the pack wire name */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One zone. Tags are author vocabulary for later rules (terrain, light); they have no built-in meaning.
     *
     * @param id stable zone ID, unique within the encounter
     * @param tags sorted author tags
     * @param cover cover offered to occupants
     * @param obscured whether visibility into the zone is obscured (consumed by targeting rules)
     * @param capacity most occupants, 1..{@link #MAX_CAPACITY}, or 0 when unbounded
     */
    public record Zone(String id, Set<String> tags, Cover cover, boolean obscured, int capacity) {
        /** Require an ID and cover; bound the capacity; freeze the tags in order. */
        public Zone {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(cover, "cover");
            if (capacity < 0 || capacity > MAX_CAPACITY) {
                throw new IllegalArgumentException("zone capacity must be within 0.." + MAX_CAPACITY);
            }
            tags = Collections.unmodifiableSet(new TreeSet<>(tags));
        }
    }

    /**
     * One directed edge.
     *
     * @param from origin zone
     * @param to destination zone
     * @param difficult whether traversal costs the difficult-terrain multiple
     * @param blocked whether the edge cannot be traversed (it still makes the zones adjacent)
     */
    public record Edge(String from, String to, boolean difficult, boolean blocked) {
        /** Require both ends and forbid a self-loop. */
        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (from.equals(to)) {
                throw new IllegalArgumentException("an edge must join two different zones: " + from);
            }
        }
    }

    /** Validate the graph invariants and freeze it in a stable order. */
    public ZoneGraph {
        if (zones.isEmpty() || zones.size() > MAX_ZONES) {
            throw new IllegalArgumentException("an encounter needs 1.." + MAX_ZONES + " zones");
        }
        zones.forEach((id, zone) -> {
            if (!id.equals(zone.id())) {
                throw new IllegalArgumentException("zone key does not match its ID: " + id);
            }
        });
        if (edges.size() > MAX_EDGES) {
            throw new IllegalArgumentException("an encounter may declare at most " + MAX_EDGES + " directed edges");
        }
        var pairs = new HashSet<String>();
        for (Edge edge : edges) {
            if (!zones.containsKey(edge.from()) || !zones.containsKey(edge.to())) {
                throw new IllegalArgumentException("edge " + edge.from() + " -> " + edge.to() + " names an undeclared zone");
            }
            if (!pairs.add(edge.from() + "\u0000" + edge.to())) {
                throw new IllegalArgumentException("duplicate edge " + edge.from() + " -> " + edge.to());
            }
        }
        zones = Collections.unmodifiableMap(new TreeMap<>(zones));
        edges = edges.stream().sorted(java.util.Comparator.comparing(Edge::from).thenComparing(Edge::to)).toList();
        String first = zones.keySet().iterator().next();
        var reached = adjacentClosure(first, edges);
        if (reached.size() != zones.size()) {
            var missing = new TreeSet<>(zones.keySet());
            missing.removeAll(reached);
            throw new IllegalArgumentException("zones " + missing + " are not connected to zone " + first);
        }
    }

    /**
     * Range-band distance: the fewest edges between two zones, ignoring direction and blocking.
     *
     * @param from a declared zone
     * @param to a declared zone
     * @return 0 for the same zone, otherwise the number of bordering steps (always finite: the graph is connected)
     * @throws IllegalArgumentException for an undeclared zone
     */
    public int distance(String from, String to) {
        require(from);
        require(to);
        var depth = new HashMap<String, Integer>(Map.of(from, 0));
        var queue = new ArrayDeque<String>(List.of(from));
        while (!queue.isEmpty()) {
            String zone = queue.poll();
            if (zone.equals(to)) {
                return depth.get(zone);
            }
            for (String next : borders(zone, edges)) {
                if (depth.putIfAbsent(next, depth.get(zone) + 1) == null) {
                    queue.add(next);
                }
            }
        }
        throw new IllegalStateException("a connected zone graph has a path between any two zones");
    }

    /**
     * Action-point cost of crossing one edge directly.
     *
     * @param from origin zone
     * @param to destination zone
     * @param profile frozen combat profile supplying the move cost and terrain multiplier
     * @return the cost, or -1 when no unblocked edge leads from {@code from} to {@code to}
     */
    public int stepCost(String from, String to, CombatProfile profile) {
        require(from);
        require(to);
        for (Edge edge : edges) {
            if (edge.from().equals(from) && edge.to().equals(to) && !edge.blocked()) {
                return cost(edge, profile);
            }
        }
        return -1;
    }

    /**
     * Cheapest total action-point cost of moving from one zone to another along unblocked edges. This is a
     * planning and feasibility measure; each step is still one legal Move inside an activation.
     *
     * @param from origin zone
     * @param to destination zone
     * @param profile frozen combat profile
     * @return 0 for the same zone, the cheapest cost, or -1 when traversal cannot reach the destination
     */
    public int travelCost(String from, String to, CombatProfile profile) {
        require(from);
        require(to);
        var best = new HashMap<String, Integer>(Map.of(from, 0));
        var queue = new PriorityQueue<Map.Entry<String, Integer>>(Map.Entry.<String, Integer>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey()));
        queue.add(Map.entry(from, 0));
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (current.getValue() > best.get(current.getKey())) {
                continue;
            }
            if (current.getKey().equals(to)) {
                return current.getValue();
            }
            for (Edge edge : edges) {
                if (edge.from().equals(current.getKey()) && !edge.blocked()) {
                    int total = current.getValue() + cost(edge, profile);
                    if (total < best.getOrDefault(edge.to(), Integer.MAX_VALUE)) {
                        best.put(edge.to(), total);
                        queue.add(Map.entry(edge.to(), total));
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Zones movement can reach from a zone along unblocked edges, the zone itself included.
     *
     * @param from a declared zone
     * @return reachable zone IDs in ID order
     */
    public Set<String> reachable(String from) {
        require(from);
        var reached = new TreeSet<String>(Set.of(from));
        var queue = new ArrayDeque<String>(List.of(from));
        while (!queue.isEmpty()) {
            String zone = queue.poll();
            for (Edge edge : edges) {
                if (edge.from().equals(zone) && !edge.blocked() && reached.add(edge.to())) {
                    queue.add(edge.to());
                }
            }
        }
        return Collections.unmodifiableSet(reached);
    }

    /**
     * Frozen form for storage and hashing: zones with their attributes and the directed edges.
     *
     * @return ordered map of canonical JSON values
     */
    public Map<String, Object> canonicalForm() {
        var zoneForms = new LinkedHashMap<String, Object>();
        zones.forEach((id, zone) -> {
            var form = new LinkedHashMap<String, Object>();
            form.put("tags", List.copyOf(zone.tags()));
            form.put("cover", zone.cover().wire());
            form.put("obscured", zone.obscured());
            form.put("capacity", zone.capacity());
            zoneForms.put(id, form);
        });
        var edgeForms = edges.stream().map(edge -> {
            var form = new LinkedHashMap<String, Object>();
            form.put("from", edge.from());
            form.put("to", edge.to());
            form.put("difficult", edge.difficult());
            form.put("blocked", edge.blocked());
            return (Object) form;
        }).toList();
        var form = new LinkedHashMap<String, Object>();
        form.put("zones", zoneForms);
        form.put("edges", edgeForms);
        return form;
    }

    /**
     * Read a frozen graph back from the canonical form {@link #canonicalForm()} wrote (P11.2).
     *
     * @param form parsed canonical object with {@code zones} and {@code edges}
     * @return the graph, revalidated by the constructor
     * @throws IllegalArgumentException or {@link ClassCastException} for a malformed form
     */
    public static ZoneGraph fromCanonical(Map<?, ?> form) {
        var zones = new TreeMap<String, Zone>();
        ((Map<?, ?>) form.get("zones")).forEach((id, value) -> {
            var zone = (Map<?, ?>) value;
            var tags = new TreeSet<String>();
            ((List<?>) zone.get("tags")).forEach(tag -> tags.add((String) tag));
            zones.put((String) id, new Zone((String) id, tags,
                    Cover.valueOf(((String) zone.get("cover")).toUpperCase(java.util.Locale.ROOT)),
                    Boolean.TRUE.equals(zone.get("obscured")), ((Number) zone.get("capacity")).intValue()));
        });
        var edges = new ArrayList<Edge>();
        for (Object value : (List<?>) form.get("edges")) {
            var edge = (Map<?, ?>) value;
            edges.add(new Edge((String) edge.get("from"), (String) edge.get("to"), Boolean.TRUE.equals(edge.get("difficult")),
                    Boolean.TRUE.equals(edge.get("blocked"))));
        }
        return new ZoneGraph(zones, edges);
    }

    /**
     * The same space with every directed edge between two zones, in either direction, set to a blocked state
     * (P11.2: a declared scene action closing or opening a passage). Range distance is unchanged, because it
     * ignores blocking; only movement changes.
     *
     * @param a one zone of the passage
     * @param b the other zone
     * @param blocked the state to set
     * @return a new graph; {@code this} when no edge joins the two zones
     */
    public ZoneGraph withPassage(String a, String b, boolean blocked) {
        var changed = edges.stream().map(edge -> (edge.from().equals(a) && edge.to().equals(b))
                || (edge.from().equals(b) && edge.to().equals(a))
                ? new Edge(edge.from(), edge.to(), edge.difficult(), blocked) : edge).toList();
        return new ZoneGraph(zones, changed);
    }

    /**
     * Whether a passage between two zones already has a state: every directed edge joining them, in either
     * direction, is blocked (or every one is open).
     *
     * @param a one zone
     * @param b the other zone
     * @param blocked the state asked about
     * @return true when at least one edge joins the zones and all of them have that state
     */
    public boolean passageIs(String a, String b, boolean blocked) {
        var joining = edges.stream().filter(edge -> (edge.from().equals(a) && edge.to().equals(b))
                || (edge.from().equals(b) && edge.to().equals(a))).toList();
        return !joining.isEmpty() && joining.stream().allMatch(edge -> edge.blocked() == blocked);
    }

    /** Cost of one unblocked edge under a profile. */
    private static int cost(Edge edge, CombatProfile profile) {
        int move = profile.value("moveCost");
        return edge.difficult() ? move * profile.value("difficultTerrainMultiplier") : move;
    }

    /** Fail for an undeclared zone. */
    private void require(String zone) {
        if (!zones.containsKey(zone)) {
            throw new IllegalArgumentException("unknown zone " + zone);
        }
    }

    /** Zones bordering one zone through any edge, in either direction. */
    private static Set<String> borders(String zone, List<Edge> edges) {
        var result = new TreeSet<String>();
        for (Edge edge : edges) {
            if (edge.from().equals(zone)) {
                result.add(edge.to());
            } else if (edge.to().equals(zone)) {
                result.add(edge.from());
            }
        }
        return result;
    }

    /** All zones adjacent-connected to a start zone. */
    private static Set<String> adjacentClosure(String start, List<Edge> edges) {
        var reached = new HashSet<String>(Set.of(start));
        var queue = new ArrayDeque<String>(List.of(start));
        while (!queue.isEmpty()) {
            for (String next : borders(queue.poll(), edges)) {
                if (reached.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reached;
    }
}
