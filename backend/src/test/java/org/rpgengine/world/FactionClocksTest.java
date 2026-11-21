package org.rpgengine.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Pure planning of faction clocks (P15.2-B): time boundaries, quest endings and clock completions are applied in one
 * deterministic order, each at most once, a full clock takes nothing more, and a bounded plan continues from the
 * recorded history exactly where an unbounded one would have gone.
 */
class FactionClocksTest {
    private static final long MINUTE = 60;
    private static final long HOUR = 3_600;

    /** Construct the stateless fixture. */
    FactionClocksTest() { }

    private static FactionClockRules.Key key(String clock) {
        return new FactionClockRules.Key("raiders", clock);
    }

    private static FactionClockRules rules(FactionClockRules.Clock... clocks) {
        var map = new HashMap<FactionClockRules.Key, FactionClockRules.Clock>();
        for (FactionClockRules.Clock clock : clocks) {
            map.put(clock.key(), clock);
        }
        return new FactionClockRules(map);
    }

    private static FactionClockRules.Clock ticking(String id, int segments, long seconds) {
        return new FactionClockRules.Clock(key(id), segments, new FactionClockRules.Tick(seconds,
                FactionClockRules.Origin.START), List.of());
    }

    /** Fold recorded changes into histories the way the database read does. */
    private static Map<FactionClockRules.Key, FactionClocks.History> fold(
            Map<FactionClockRules.Key, FactionClocks.History> start, List<FactionClocks.Change> changes) {
        var folded = new HashMap<>(start);
        for (FactionClocks.Change change : changes) {
            var before = folded.getOrDefault(change.clock(), FactionClocks.History.EMPTY);
            var quests = new HashSet<>(before.quests());
            var sources = new HashSet<>(before.sources());
            Long lastTick = before.lastTick();
            switch (change.cause()) {
                case WORLD_TIME -> lastTick = change.due();
                case QUEST_ENDED -> quests.add(change.quest());
                case CLOCK_COMPLETED -> sources.add(change.source());
            }
            folded.put(change.clock(), new FactionClocks.History(change.after(),
                    before.firstDue() == null ? change.due() : before.firstDue(), lastTick,
                    change.completed() ? Long.valueOf(change.due()) : before.completedDue(), quests, sources));
        }
        return folded;
    }

    /**
     * Three twelve-segment clocks ticking every 15 minutes, four hours on: 36 changes are due (each clock completes at
     * its twelfth boundary and ignores the other four). A plan bounded at 32 records the oldest 32, and the next plan
     * from the folded history records exactly the remaining 4, so the two commits equal one unbounded plan.
     */
    @Test
    void catchUpIsBoundedAndContinuesInDueOrder() {
        var rules = rules(ticking("a", 12, 15 * MINUTE), ticking("b", 12, 15 * MINUTE), ticking("c", 12, 15 * MINUTE));
        long now = 4 * HOUR;
        var all = FactionClocks.plan(rules, Map.of(), List.of(), now, 1_000);
        assertEquals(36, all.size());
        assertEquals(all, FactionClocks.plan(rules, Map.of(), List.of(), now, 1_000), "planning is deterministic");
        // Due time first, then clock identity; every clock stops at its target.
        for (int index = 1; index < all.size(); index++) {
            var previous = all.get(index - 1);
            var next = all.get(index);
            assertTrue(previous.due() < next.due() || previous.due() == next.due()
                    && previous.clock().compareTo(next.clock()) < 0, next.toString());
        }
        assertEquals(List.of(key("a"), key("b"), key("c")), all.subList(0, 3).stream()
                .map(FactionClocks.Change::clock).toList());
        assertEquals(3, all.stream().filter(FactionClocks.Change::completed).count());
        assertEquals(3 * HOUR, all.getLast().due());

        var first = FactionClocks.plan(rules, Map.of(), List.of(), now, FactionClocks.MAX_CHANGES);
        assertEquals(all.subList(0, 32), first);
        var second = FactionClocks.plan(rules, fold(Map.of(), first), List.of(), now, FactionClocks.MAX_CHANGES);
        assertEquals(all.subList(32, 36), second);
        // The completed clocks take nothing more, however much time passes.
        var done = fold(fold(Map.of(), first), second);
        assertEquals(List.of(), FactionClocks.plan(rules, done, List.of(), 100 * HOUR, FactionClocks.MAX_CHANGES));
    }

    /** A tick waits for its boundary: nothing at 59 minutes, one segment due at exactly 60. */
    @Test
    void ticksWaitForTheirBoundary() {
        var rules = rules(ticking("watch", 4, HOUR));
        assertEquals(List.of(), FactionClocks.plan(rules, Map.of(), List.of(), 59 * MINUTE, 32));
        var tick = FactionClocks.plan(rules, Map.of(), List.of(), HOUR, 32);
        assertEquals(List.of(new FactionClocks.Change(key("watch"), FactionClocks.CauseKind.WORLD_TIME, HOUR, null, null,
                null, 0, 1, 4)), tick);
    }

    /**
     * A quest ending fills "warning" (2 of 2), whose completion starts "raid"; raid then ticks hourly from that moment.
     * Each fact counts once: planning again from the recorded history finds nothing, and an ending with another
     * outcome, or a cause beyond the target, changes nothing more.
     */
    @Test
    void endingsAndCompletionsCascadeOnceInCausalOrder() {
        var warning = new FactionClockRules.Clock(key("warning"), 2, null,
                List.of(new FactionClockRules.QuestEnded("survey", "surveyed", 2)));
        var raid = new FactionClockRules.Clock(key("raid"), 3, new FactionClockRules.Tick(HOUR,
                FactionClockRules.Origin.ADVANCE), List.of(new FactionClockRules.ClockCompleted(key("warning"), 1)));
        var rules = rules(warning, raid);
        long ended = 1_000;
        var ending = new FactionClocks.Ending("survey", "surveyed", 7, ended);

        // Before the quest ends, the raid clock has no origin and never ticks.
        assertEquals(List.of(), FactionClocks.plan(rules, Map.of(), List.of(), 10 * HOUR, 32));

        var changes = FactionClocks.plan(rules, Map.of(), List.of(ending), ended + 2 * HOUR, 32);
        assertEquals(List.of(
                new FactionClocks.Change(key("warning"), FactionClocks.CauseKind.QUEST_ENDED, ended, "survey", 7L, null,
                        0, 2, 2),
                new FactionClocks.Change(key("raid"), FactionClocks.CauseKind.CLOCK_COMPLETED, ended, null, null,
                        key("warning"), 0, 1, 3),
                new FactionClocks.Change(key("raid"), FactionClocks.CauseKind.WORLD_TIME, ended + HOUR, null, null, null,
                        1, 2, 3),
                new FactionClocks.Change(key("raid"), FactionClocks.CauseKind.WORLD_TIME, ended + 2 * HOUR, null, null,
                        null, 2, 3, 3)), changes);
        assertEquals(List.of(), FactionClocks.plan(rules, fold(Map.of(), changes), List.of(ending), 50 * HOUR, 32));

        // Bounded at two, the tick that follows the cascade waits; the next plan resumes with it.
        var bounded = FactionClocks.plan(rules, Map.of(), List.of(ending), ended + 2 * HOUR, 2);
        assertEquals(changes.subList(0, 2), bounded);
        assertEquals(changes.subList(2, 4), FactionClocks.plan(rules, fold(Map.of(), bounded), List.of(ending),
                ended + 2 * HOUR, 32));

        var other = new FactionClocks.Ending("survey", "abandoned", 7, ended);
        assertEquals(List.of(), FactionClocks.plan(rules, Map.of(), List.of(other), 50 * HOUR, 32));
    }

    /** The rule values refuse clocks nothing can advance, self-causes and causes that would count one fact twice. */
    @Test
    void rulesRefuseAmbiguousOrInertClocks() {
        var hourly = new FactionClockRules.Tick(HOUR, FactionClockRules.Origin.START);
        assertThrows(IllegalArgumentException.class, () -> new FactionClockRules.Clock(key("idle"), 2, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FactionClockRules.Clock(key("late"), 2,
                new FactionClockRules.Tick(HOUR, FactionClockRules.Origin.ADVANCE), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FactionClockRules.Clock(key("loop"), 2, null,
                List.of(new FactionClockRules.ClockCompleted(key("loop"), 1))));
        assertThrows(IllegalArgumentException.class, () -> new FactionClockRules.Clock(key("twice"), 2, null,
                List.of(new FactionClockRules.QuestEnded("survey", null, 1),
                        new FactionClockRules.QuestEnded("survey", "surveyed", 1))));
        assertThrows(IllegalArgumentException.class, () -> new FactionClockRules.Clock(key("big"), 2, hourly,
                List.of(new FactionClockRules.QuestEnded("survey", null, 3))));
        assertThrows(IllegalArgumentException.class, () -> new FactionClockRules.Tick(14 * MINUTE,
                FactionClockRules.Origin.START));
        assertThrows(IllegalArgumentException.class, () -> new FactionClockRules.Clock(key("wide"), 13, hourly,
                List.of()));
        // Distinct outcomes of one quest may each count, because a quest ends only once.
        new FactionClockRules.Clock(key("either"), 2, null, List.of(new FactionClockRules.QuestEnded("survey", "a", 1),
                new FactionClockRules.QuestEnded("survey", "b", 2)));
        var many = new ArrayList<FactionClockRules.Clock>();
        for (int index = 0; index <= FactionClockRules.MAX_CLOCKS; index++) {
            many.add(new FactionClockRules.Clock(key("c" + index), 1, hourly, List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> rules(many.toArray(FactionClockRules.Clock[]::new)));
    }
}
