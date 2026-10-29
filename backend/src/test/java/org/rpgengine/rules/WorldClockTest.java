package org.rpgengine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure bounds and arithmetic of the in-world clock (P10.4). */
class WorldClockTest {
    /** Construct the stateless fixture. */
    WorldClockTest() { }

    /** A reading splits the counter into a 1-based day, hour, minute and second with no locale data. */
    @Test
    void readingSplitsTheCounterIntoACalendarPosition() {
        assertEquals(new WorldClock.Reading(0, 1, 0, 0, 0), WorldClock.read(0));
        assertEquals(new WorldClock.Reading(86_399, 1, 23, 59, 59), WorldClock.read(86_399));
        assertEquals(new WorldClock.Reading(86_400, 2, 0, 0, 0), WorldClock.read(86_400));
        assertEquals(new WorldClock.Reading(2 * 86_400 + 3 * 3_600 + 4 * 60 + 5, 3, 3, 4, 5),
                WorldClock.read(2 * 86_400 + 3 * 3_600 + 4 * 60 + 5));
        assertEquals(36_526, WorldClock.read(WorldClock.END_SECONDS).day());
        assertThrows(IllegalArgumentException.class, () -> WorldClock.read(-1));
        assertThrows(IllegalArgumentException.class, () -> WorldClock.read(WorldClock.END_SECONDS + 1));
    }

    /** A step is bounded per operation (one day) and the clock never passes its end. */
    @Test
    void advanceIsBoundedPerStepAndByTheEndOfTheClock() {
        assertEquals(60, WorldClock.advance(0, 60));
        assertEquals(86_400, WorldClock.advance(0, WorldClock.MAX_STEP_SECONDS));
        assertEquals(WorldClock.END_SECONDS, WorldClock.advance(WorldClock.END_SECONDS - 5, 5));
        assertEquals(WorldClock.END_SECONDS, WorldClock.advance(WorldClock.END_SECONDS, 0));
        assertThrows(IllegalArgumentException.class, () -> WorldClock.advance(0, WorldClock.MAX_STEP_SECONDS + 1));
        assertThrows(IllegalArgumentException.class, () -> WorldClock.advance(0, -1));
        assertThrows(IllegalArgumentException.class, () -> WorldClock.advance(WorldClock.END_SECONDS - 4, 5));
        assertThrows(IllegalArgumentException.class, () -> WorldClock.advance(WorldClock.END_SECONDS, 1));
        assertThrows(IllegalArgumentException.class, () -> WorldClock.advance(-1, 1));
        assertFalse(WorldClock.fits(Long.MAX_VALUE, 1), "no overflow near the top of the long range");
        assertTrue(WorldClock.fits(0, 0));
    }

    /** Authored minutes become whole seconds and stay within one day. */
    @Test
    void authoredDurationsAreBoundedMinutes() {
        assertEquals(0, WorldClock.seconds(0));
        assertEquals(2_700, WorldClock.seconds(45));
        assertEquals(WorldClock.MAX_STEP_SECONDS, WorldClock.seconds(WorldClock.MAX_MINUTES));
        assertThrows(IllegalArgumentException.class, () -> WorldClock.seconds(-1));
        assertThrows(IllegalArgumentException.class, () -> WorldClock.seconds(WorldClock.MAX_MINUTES + 1));
        assertTrue(WorldClock.MAX_WAIT_SECONDS < WorldClock.MAX_STEP_SECONDS, "a wait is tighter than a journey");
    }

    /** Quest thresholds read whole units rounded down, and the proven bound is the clock's own end. */
    @Test
    void unitsRoundDownAndAreBounded() {
        assertEquals(1, WorldClock.inUnits(119, "minutes"));
        assertEquals(1, WorldClock.inUnits(7_199, "hours"));
        assertEquals(3, WorldClock.inUnits(3 * 86_400 + 5, "days"));
        assertEquals(WorldClock.END_SECONDS, WorldClock.maxUnits("seconds"));
        assertEquals(36_525, WorldClock.maxUnits("days"));
        assertThrows(IllegalArgumentException.class, () -> WorldClock.inUnits(1, "weeks"));
    }
}
