package org.rpgengine.rules;

/**
 * The bounded in-world clock (P10.4): pure functions over whole seconds of in-world time since the
 * campaign began. The clock is a counter owned by the canonical commit, independent of the wall clock.
 * It moves only when a committed command resolves an explicit duration (travel, waiting, rest), by at
 * most {@link #MAX_STEP_SECONDS} per operation, and never past {@link #END_SECONDS}. Reads and queries
 * never call anything here with a changed value, so looking at the game cannot advance it.
 *
 * <p>Calendar: the clock has no named months. A campaign day is 86,400 seconds, days are numbered from 1
 * and the reading splits the rest of the day into hour, minute and second. This keeps presentation
 * deterministic and free of locale or time-zone data.
 */
public final class WorldClock {
    /** Seconds in one in-world minute, hour and day. */
    public static final long MINUTE = 60;
    public static final long HOUR = 3_600;
    public static final long DAY = 86_400;
    /**
     * Most seconds one committed operation may add. It equals the input ceiling of {@code WAIT} and the
     * longest authored travel or rest, so one command can never skip more than a day of consequences.
     */
    public static final long MAX_STEP_SECONDS = DAY;
    /**
     * Longest legal {@code WAIT} (four hours). Anything longer is a rest or a journey, which carry their
     * own rules; the wire ceiling of one day stays as a structural input bound.
     */
    public static final long MAX_WAIT_SECONDS = 4 * HOUR;
    /** Last representable in-world second: one hundred 365.25-day years. The database enforces the same bound. */
    public static final long END_SECONDS = 3_155_760_000L;
    /** Longest travel or rest duration a pack may declare, in minutes (one day). */
    public static final int MAX_MINUTES = 1_440;

    /** Static helpers only. */
    private WorldClock() {
    }

    /**
     * A reading of the clock.
     *
     * @param seconds total elapsed seconds, 0..{@link #END_SECONDS}
     * @param day campaign day, starting at 1
     * @param hour hour of that day, 0..23
     * @param minute minute of that hour, 0..59
     * @param second second of that minute, 0..59
     */
    public record Reading(long seconds, long day, int hour, int minute, int second) {
        /** Reject an out-of-range clock so a reading is always a valid position. */
        public Reading {
            if (seconds < 0 || seconds > END_SECONDS) {
                throw new IllegalArgumentException("world time outside the clock");
            }
        }
    }

    /**
     * Split a clock value into its calendar reading.
     *
     * @param seconds elapsed seconds
     * @return the reading
     * @throws IllegalArgumentException if the value is negative or beyond {@link #END_SECONDS}
     */
    public static Reading read(long seconds) {
        long inDay = seconds % DAY;
        return new Reading(seconds, seconds / DAY + 1, (int) (inDay / HOUR), (int) (inDay % HOUR / MINUTE),
                (int) (inDay % MINUTE));
    }

    /**
     * Whether an operation may add the given seconds now.
     *
     * @param now current clock value
     * @param step seconds the operation would add
     * @return true when {@code 0 <= step <= MAX_STEP_SECONDS} and the result stays within {@link #END_SECONDS}
     */
    public static boolean fits(long now, long step) {
        return now >= 0 && step >= 0 && step <= MAX_STEP_SECONDS && step <= END_SECONDS - now;
    }

    /**
     * Add an operation's duration to the clock.
     *
     * @param now current clock value
     * @param step seconds to add
     * @return the new clock value
     * @throws IllegalArgumentException if the step is negative, above one day, or would pass the end of the clock
     */
    public static long advance(long now, long step) {
        if (!fits(now, step)) {
            throw new IllegalArgumentException("clock step outside the bounded world clock");
        }
        return now + step;
    }

    /**
     * Whole seconds of an authored duration.
     *
     * @param minutes pack-declared minutes, 0..{@link #MAX_MINUTES}
     * @return the duration in seconds
     */
    public static long seconds(int minutes) {
        if (minutes < 0 || minutes > MAX_MINUTES) {
            throw new IllegalArgumentException("duration outside 0.." + MAX_MINUTES + " minutes");
        }
        return minutes * MINUTE;
    }

    /**
     * Elapsed whole units for a quest clock threshold.
     *
     * @param seconds clock value
     * @param unit one of {@code seconds}, {@code minutes}, {@code hours}, {@code days}
     * @return the clock value in whole units, rounded down
     * @throws IllegalArgumentException for an unknown unit
     */
    public static long inUnits(long seconds, String unit) {
        return switch (unit) {
            case "seconds" -> seconds;
            case "minutes" -> seconds / MINUTE;
            case "hours" -> seconds / HOUR;
            case "days" -> seconds / DAY;
            default -> throw new IllegalArgumentException("unknown clock unit");
        };
    }

    /**
     * Largest value {@link #inUnits} can return, which the pack compiler uses as the proven interval.
     *
     * @param unit a clock unit
     * @return the bound for that unit
     */
    public static long maxUnits(String unit) {
        return inUnits(END_SECONDS, unit);
    }
}
