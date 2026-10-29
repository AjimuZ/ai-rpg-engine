package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * P11.1 lifecycle and objectives: the phase table is exactly the docs/11 state diagram, and objectives are
 * structured predicates over mechanical facts with the documented statuses.
 */
class EncounterLifecycleTest {
    /** Construct a stateless fixture. */
    EncounterLifecycleTest() {
    }

    /** Every arrow of the diagram is legal and nothing else is; candidates are never active. */
    @Test
    void phasesFollowTheStateDiagram() {
        var arrows = Map.ofEntries(
                Map.entry(EncounterPhase.PROPOSED, EnumSet.of(EncounterPhase.VALIDATED, EncounterPhase.REJECTED)),
                Map.entry(EncounterPhase.VALIDATED, EnumSet.of(EncounterPhase.ENTERING)),
                Map.entry(EncounterPhase.REJECTED, EnumSet.noneOf(EncounterPhase.class)),
                Map.entry(EncounterPhase.ENTERING, EnumSet.of(EncounterPhase.ROUND_OPEN)),
                Map.entry(EncounterPhase.ROUND_OPEN, EnumSet.of(EncounterPhase.ACTOR_START)),
                Map.entry(EncounterPhase.ACTOR_START, EnumSet.of(EncounterPhase.AWAITING_ACTION,
                        EncounterPhase.OUTCOME_PENDING)),
                Map.entry(EncounterPhase.AWAITING_ACTION, EnumSet.of(EncounterPhase.RESOLVING, EncounterPhase.ACTOR_END)),
                Map.entry(EncounterPhase.RESOLVING, EnumSet.of(EncounterPhase.AWAITING_ACTION, EncounterPhase.ACTOR_END,
                        EncounterPhase.OUTCOME_PENDING)),
                Map.entry(EncounterPhase.ACTOR_END, EnumSet.of(EncounterPhase.ACTOR_START, EncounterPhase.ROUND_END)),
                Map.entry(EncounterPhase.ROUND_END, EnumSet.of(EncounterPhase.ROUND_OPEN, EncounterPhase.OUTCOME_PENDING)),
                Map.entry(EncounterPhase.OUTCOME_PENDING, EnumSet.of(EncounterPhase.SETTLED)),
                Map.entry(EncounterPhase.SETTLED, EnumSet.of(EncounterPhase.NARRATIVE_RESUME)),
                Map.entry(EncounterPhase.NARRATIVE_RESUME, EnumSet.noneOf(EncounterPhase.class)));
        assertEquals(EnumSet.allOf(EncounterPhase.class), EnumSet.copyOf(arrows.keySet()));
        for (EncounterPhase from : EncounterPhase.values()) {
            assertEquals(arrows.get(from), from.successors(), from.name());
            for (EncounterPhase to : EncounterPhase.values()) {
                if (arrows.get(from).contains(to)) {
                    assertEquals(to, from.require(to));
                } else {
                    assertThrows(IllegalStateException.class, () -> from.require(to), from + " -> " + to);
                }
            }
        }
        assertTrue(EncounterPhase.REJECTED.terminal());
        assertTrue(EncounterPhase.NARRATIVE_RESUME.terminal());
        assertFalse(EncounterPhase.ENTERING.terminal());
        var active = new HashSet<EncounterPhase>();
        for (EncounterPhase phase : EncounterPhase.values()) {
            if (phase.active()) {
                active.add(phase);
            }
        }
        assertEquals(EnumSet.range(EncounterPhase.ENTERING, EncounterPhase.OUTCOME_PENDING), EnumSet.copyOf(active));
    }

    /** Each objective kind reads only its facts; a defeated player fails every objective. */
    @Test
    void objectivesEvaluateMechanicalFacts() {
        var defeat = new EncounterObjective("win", EncounterObjective.Kind.DEFEAT_HOSTILES, null, null, 0);
        var escape = new EncounterObjective("run", EncounterObjective.Kind.ESCAPE, null, null, 0);
        var reach = new EncounterObjective("reach", EncounterObjective.Kind.REACH_ZONE, "stones", null, 0);
        var survive = new EncounterObjective("hold", EncounterObjective.Kind.SURVIVE, null, null, 3);
        var protect = new EncounterObjective("keep", EncounterObjective.Kind.PROTECT, null, "keeper", 0);
        var start = new Facts(true, "entry", false, Set.of(), 0);
        assertEquals(EncounterObjective.Status.PENDING, defeat.evaluate(start));
        assertEquals(EncounterObjective.Status.PENDING, escape.evaluate(start));
        assertEquals(EncounterObjective.Status.PENDING, reach.evaluate(start));
        assertEquals(EncounterObjective.Status.PENDING, survive.evaluate(start));
        assertEquals(EncounterObjective.Status.PENDING, protect.evaluate(start));
        var won = new Facts(false, "stones", false, Set.of(), 3);
        assertEquals(EncounterObjective.Status.MET, defeat.evaluate(won));
        assertEquals(EncounterObjective.Status.MET, reach.evaluate(won));
        assertEquals(EncounterObjective.Status.MET, survive.evaluate(won));
        assertEquals(EncounterObjective.Status.MET, protect.evaluate(won));
        assertEquals(EncounterObjective.Status.MET, escape.evaluate(new Facts(true, "exit", false, Set.of(), 1)));
        assertEquals(EncounterObjective.Status.FAILED, protect.evaluate(new Facts(true, "entry", false,
                Set.of("keeper"), 1)));
        var down = new Facts(false, "exit", true, Set.of(), 9);
        for (var objective : java.util.List.of(defeat, escape, reach, survive, protect)) {
            assertEquals(EncounterObjective.Status.FAILED, objective.evaluate(down), objective.id());
        }
        assertEquals(Map.of("id", "hold", "kind", "survive", "rounds", 3), survive.canonicalForm());
        assertEquals(Map.of("id", "win", "kind", "defeatHostiles"), defeat.canonicalForm());
    }

    /** An objective names exactly the parameter its kind uses. */
    @Test
    void objectivesAreUnambiguous() {
        assertThrows(IllegalArgumentException.class, () -> new EncounterObjective("x",
                EncounterObjective.Kind.REACH_ZONE, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new EncounterObjective("x",
                EncounterObjective.Kind.DEFEAT_HOSTILES, "yard", null, 0));
        assertThrows(IllegalArgumentException.class, () -> new EncounterObjective("x",
                EncounterObjective.Kind.SURVIVE, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new EncounterObjective("x",
                EncounterObjective.Kind.PROTECT, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new EncounterObjective("x",
                EncounterObjective.Kind.ESCAPE, null, null, 2));
        assertEquals(EncounterObjective.Kind.REACH_ZONE, EncounterObjective.Kind.parse("reachZone"));
        assertThrows(IllegalArgumentException.class, () -> EncounterObjective.Kind.parse("eliminate"));
        assertEquals(OpeningPolicy.FIRST, OpeningPolicy.parse("first"));
        assertThrows(IllegalArgumentException.class, () -> OpeningPolicy.parse("ambush"));
    }

    /** Fixed facts for one evaluation. */
    private record Facts(boolean hostilesActive, String playerZone, boolean playerDefeated, Set<String> fallen,
            int completedRounds) implements EncounterObjective.Progress {
        @Override
        public boolean defeated(String participant) {
            return fallen.contains(participant);
        }

        @Override
        public Set<String> escapeZones() {
            return Set.of("exit");
        }
    }
}
