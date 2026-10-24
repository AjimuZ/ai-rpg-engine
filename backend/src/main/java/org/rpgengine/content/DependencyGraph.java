package org.rpgengine.content;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Directed graph of authored "requires" relations (ability prerequisites, conditions applied by
 * condition ticks, quest progress requirements) used only to reject cycles (docs/09 sections 7 and 10).
 *
 * <p>Detection is an iterative depth-first search, so a long hostile chain cannot overflow the
 * Java stack. Edges are visited in insertion order, which makes the reported cycle deterministic
 * for identical input. Edges to nodes outside the graph (for example external dependency IDs)
 * are kept but cannot close a cycle because external packs were admitted acyclic on their own.
 *
 * <p>Failure behavior: {@link #rejectCycles(String)} throws a {@code CYCLE} PackException naming
 * the full path and located at the edge that closes the cycle.
 */
final class DependencyGraph {
    /** One outgoing edge and where it was authored. */
    private record Edge(String to, SourceLocation location) {
    }

    private final Map<String, List<Edge>> edges = new LinkedHashMap<>();

    /** Register a node so it is searched even if it has no outgoing edges. */
    void node(String id) {
        edges.computeIfAbsent(id, key -> new ArrayList<>());
    }

    /** Add a "from requires to" edge authored at {@code location}. */
    void edge(String from, String to, SourceLocation location) {
        node(from);
        edges.get(from).add(new Edge(to, location));
    }

    /**
     * Throw on the first cycle found.
     *
     * @param relation human-readable relation name used in the diagnostic, e.g. "quest requirement"
     * @throws PackException with code CYCLE and the path {@code a -> b -> a}
     */
    void rejectCycles(String relation) {
        // 0 = unvisited, 1 = on the current DFS path, 2 = fully explored.
        Map<String, Integer> state = new HashMap<>();
        for (String root : edges.keySet()) {
            if (state.getOrDefault(root, 0) != 0) {
                continue;
            }
            Deque<Iterator<Edge>> frames = new ArrayDeque<>();
            List<String> path = new ArrayList<>();
            state.put(root, 1);
            path.add(root);
            frames.push(edges.get(root).iterator());
            while (!frames.isEmpty()) {
                Iterator<Edge> frame = frames.peek();
                if (!frame.hasNext()) {
                    frames.pop();
                    state.put(path.remove(path.size() - 1), 2);
                    continue;
                }
                Edge edge = frame.next();
                int target = state.getOrDefault(edge.to(), 0);
                if (target == 1) {
                    List<String> cycle = new ArrayList<>(path.subList(path.indexOf(edge.to()), path.size()));
                    cycle.add(edge.to());
                    throw new PackException("CYCLE", edge.location(),
                            "Cyclic " + relation + ": " + String.join(" -> ", cycle));
                }
                if (target == 0 && edges.containsKey(edge.to())) {
                    state.put(edge.to(), 1);
                    path.add(edge.to());
                    frames.push(edges.get(edge.to()).iterator());
                }
            }
        }
    }
}
