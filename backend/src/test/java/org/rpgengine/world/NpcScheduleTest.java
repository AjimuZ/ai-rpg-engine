package org.rpgengine.world;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.rpgengine.rules.WorldClock;

import static org.junit.jupiter.api.Assertions.*;

/** Pure checks of the P15.1-A schedule and rule values: one entry in force at every time, bounded shapes. */
class NpcScheduleTest {
    /** Construct the stateless fixture. */
    NpcScheduleTest() { }

    /** An entry holds from its start to the next one; the last wraps past midnight; days repeat. */
    @Test
    void theEntryInForceFollowsTheClockAndWrapsPastMidnight() {
        var routine = new NpcSchedule(List.of(new NpcSchedule.Entry(6 * 60, "market"),
                new NpcSchedule.Entry(18 * 60, "tavern")));
        // Before the first entry of the day the previous evening's entry still holds.
        assertEquals("tavern", routine.locationAt(0));
        assertEquals("tavern", routine.locationAt(6 * WorldClock.HOUR - 1));
        assertEquals("market", routine.locationAt(6 * WorldClock.HOUR));
        assertEquals("market", routine.locationAt(18 * WorldClock.HOUR - 1));
        assertEquals("tavern", routine.locationAt(18 * WorldClock.HOUR));
        assertEquals("market", routine.locationAt(41 * WorldClock.DAY + 7 * WorldClock.HOUR));
        assertEquals("tavern", routine.locationAt(WorldClock.END_SECONDS));
        // A single-entry routine is a fixed place.
        assertEquals("well", new NpcSchedule(List.of(new NpcSchedule.Entry(720, "well"))).locationAt(3_600));
        assertThrows(IllegalArgumentException.class, () -> routine.locationAt(-1));
        assertThrows(IllegalArgumentException.class, () -> routine.locationAt(WorldClock.END_SECONDS + 1));
    }

    /** Shapes the compiler refuses are refused again by the values, so a defect cannot slip through. */
    @Test
    void valuesRefuseMalformedRoutinesAndRoutes() {
        assertThrows(IllegalArgumentException.class, () -> new NpcSchedule(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NpcSchedule(List.of(new NpcSchedule.Entry(60, "a"),
                new NpcSchedule.Entry(60, "b"))));
        assertThrows(IllegalArgumentException.class, () -> new NpcSchedule.Entry(1_440, "a"));
        assertThrows(IllegalArgumentException.class, () -> new NpcRules(null, false,
                List.of(new NpcRules.Report("captain", 10))));
        assertThrows(IllegalArgumentException.class, () -> new NpcRules(null, true,
                List.of(new NpcRules.Report("captain", 10), new NpcRules.Report("captain", 20))));
        assertThrows(IllegalArgumentException.class, () -> new NpcRules.Report("captain", WorldClock.MAX_MINUTES + 1));
        assertEquals(1_800, new NpcRules.Report("captain", 30).delaySeconds());
        assertFalse(NpcRules.NONE.active());
        assertTrue(new NpcRules(null, true, List.of()).active());
    }
}
