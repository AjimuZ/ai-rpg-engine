package org.rpgengine.world;

import java.util.List;
import java.util.Objects;

import org.rpgengine.rules.WorldClock;

/**
 * One NPC's daily routine (P15.1-A): pure, immutable and derived only from pinned content. An entry holds
 * from its start minute until the next entry starts; the last entry wraps past midnight to the first, so
 * every in-world time has exactly one entry in force. The schedule is a function of the bounded world clock
 * and nothing else: it has no state of its own, so a long time jump needs no catch-up and a read cannot
 * change where anyone is.
 *
 * @param entries 1..{@value #MAX_ENTRIES} entries in strictly increasing start order
 */
public record NpcSchedule(List<Entry> entries) {
    /** Most entries one schedule holds; equal to the pack compiler's bound. */
    public static final int MAX_ENTRIES = 24;
    /** Minutes in one in-world day. */
    private static final int DAY_MINUTES = (int) (WorldClock.DAY / WorldClock.MINUTE);

    /**
     * One period of the routine.
     *
     * @param minute start minute of the day, 0..1439
     * @param locationId canonical location ID, as campaign rows store it
     */
    public record Entry(int minute, String locationId) {
        /** Reject a minute outside one day and a missing location. */
        public Entry {
            if (minute < 0 || minute >= DAY_MINUTES) {
                throw new IllegalArgumentException("schedule minute outside one day");
            }
            Objects.requireNonNull(locationId, "locationId");
        }
    }

    /** Freeze the entries and require a non-empty, bounded, strictly increasing routine. */
    public NpcSchedule {
        entries = List.copyOf(entries);
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("a schedule has 1.." + MAX_ENTRIES + " entries");
        }
        for (int index = 1; index < entries.size(); index++) {
            if (entries.get(index).minute() <= entries.get(index - 1).minute()) {
                throw new IllegalArgumentException("schedule entries start in strictly increasing order");
            }
        }
    }

    /**
     * Where the routine places the NPC at a clock value.
     *
     * @param worldTimeSeconds campaign world time, 0..{@link WorldClock#END_SECONDS}
     * @return the location of the entry in force: the last one starting at or before the minute of the day,
     *         or the last entry of the day when the first starts later (the routine wraps past midnight)
     * @throws IllegalArgumentException for a clock value outside the bounded world clock
     */
    public String locationAt(long worldTimeSeconds) {
        var reading = WorldClock.read(worldTimeSeconds);
        int minute = reading.hour() * 60 + reading.minute();
        Entry current = entries.getLast();
        for (Entry entry : entries) {
            if (entry.minute() > minute) {
                break;
            }
            current = entry;
        }
        return current.locationId();
    }
}
