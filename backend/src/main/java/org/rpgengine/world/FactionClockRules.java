package org.rpgengine.world;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Prose-free faction clock rules of one pinned closure (P15.2-B, docs/10 section 7). A clock belongs to the faction
 * that declares it and fills from zero up to its target number of segments. It advances only through declared
 * causes: a time tick at fixed in-world intervals, the ending of a named quest, or the completion of another clock.
 * A filled clock is complete and never changes again, so a clock changes at most {@code segments} times and
 * completes at most once in a campaign.
 *
 * @param clocks every declared clock by identity, in ascending identity order; at most {@value #MAX_CLOCKS}
 */
public record FactionClockRules(Map<Key, Clock> clocks) {
    /** A closure without clocks; campaigns pinned to it never read or write clock state. */
    public static final FactionClockRules NONE = new FactionClockRules(Map.of());
    /** Largest target of one clock; equal to the pack compiler's bound and the database's check. */
    public static final int MAX_SEGMENTS = 12;
    /** Most clocks one faction declares; equal to the pack compiler's bound. */
    public static final int MAX_CLOCKS_PER_FACTION = 4;
    /** Most quest and clock causes one clock declares; equal to the pack compiler's bound. */
    public static final int MAX_CAUSES = 8;
    /** Shortest time tick in in-world minutes; shorter ticks would only spend the per-commit change budget. */
    public static final int MIN_TICK_MINUTES = 15;
    /** Longest time tick in in-world minutes: one week. */
    public static final int MAX_TICK_MINUTES = 10_080;
    /** Most clocks a closure may hold; more fails the projection instead of silently skipping clocks. */
    public static final int MAX_CLOCKS = 64;

    /** Freeze the clocks in identity order and require each entry to sit under its own key. */
    public FactionClockRules {
        var sorted = new TreeMap<Key, Clock>(Comparator.naturalOrder());
        clocks.forEach((key, clock) -> {
            if (!Objects.requireNonNull(clock, "clock").key().equals(key)) {
                throw new IllegalArgumentException("a clock is keyed by its own identity");
            }
            sorted.put(key, clock);
        });
        if (sorted.size() > MAX_CLOCKS) {
            throw new IllegalArgumentException("a closure holds at most " + MAX_CLOCKS + " faction clocks");
        }
        clocks = java.util.Collections.unmodifiableMap(sorted);
    }

    /**
     * Identity of one clock.
     *
     * @param faction canonical faction definition ID ({@code pack#id} for a dependency faction)
     * @param clock clock identifier, local to the faction
     */
    public record Key(String faction, String clock) implements Comparable<Key> {
        private static final Comparator<Key> ORDER = Comparator.comparing(Key::faction).thenComparing(Key::clock);

        /** Require both parts. */
        public Key {
            Objects.requireNonNull(faction, "faction");
            Objects.requireNonNull(clock, "clock");
        }

        /** Faction first, then clock, in code-point order: the order every evaluation visits clocks in. */
        @Override
        public int compareTo(Key other) {
            return ORDER.compare(this, other);
        }

        @Override
        public String toString() {
            return faction + "/" + clock;
        }
    }

    /** Where a clock's time ticks are counted from. */
    public enum Origin {
        /** From world time zero, when the campaign begins. */
        START("start"),
        /** From the world time at which a quest or clock cause first advanced the clock. */
        ADVANCE("advance");

        private final String token;

        Origin(String token) {
            this.token = token;
        }

        /** @return the authored spelling used in pack files */
        public String token() {
            return token;
        }

        /**
         * @param token authored spelling
         * @return the origin it names
         * @throws IllegalArgumentException for any other spelling; the compiler refuses those first
         */
        public static Origin of(String token) {
            for (Origin origin : values()) {
                if (origin.token.equals(token)) {
                    return origin;
                }
            }
            throw new IllegalArgumentException("unknown clock tick origin: " + token);
        }
    }

    /**
     * A time tick: one segment each time {@code seconds} of in-world time have passed since the origin.
     *
     * @param seconds interval, {@value #MIN_TICK_MINUTES}..{@value #MAX_TICK_MINUTES} minutes in seconds
     * @param from origin of the intervals
     */
    public record Tick(long seconds, Origin from) {
        /** Require a bounded interval and an origin. */
        public Tick {
            Objects.requireNonNull(from, "from");
            if (seconds < MIN_TICK_MINUTES * 60L || seconds > MAX_TICK_MINUTES * 60L) {
                throw new IllegalArgumentException("clock tick interval out of bounds");
            }
        }
    }

    /** A committed fact that advances a clock by a number of segments. */
    public sealed interface Cause permits QuestEnded, ClockCompleted {
        /** @return segments the cause adds, 1..the clock's target */
        int segments();
    }

    /**
     * A quest ended.
     *
     * @param quest canonical quest ID
     * @param outcome the outcome the ending must record, or null for any ending
     * @param segments segments added
     */
    public record QuestEnded(String quest, String outcome, int segments) implements Cause {
        /** Require the quest and a positive advance. */
        public QuestEnded {
            Objects.requireNonNull(quest, "quest");
            if (segments < 1 || segments > MAX_SEGMENTS) {
                throw new IllegalArgumentException("a cause adds 1.." + MAX_SEGMENTS + " segments");
            }
        }

        /**
         * @param recorded outcome ID the quest's ending recorded
         * @return whether that ending satisfies the cause
         */
        public boolean matches(String recorded) {
            return outcome == null || outcome.equals(recorded);
        }
    }

    /**
     * Another clock completed.
     *
     * @param clock identity of the clock whose completion counts
     * @param segments segments added
     */
    public record ClockCompleted(Key clock, int segments) implements Cause {
        /** Require the clock and a positive advance. */
        public ClockCompleted {
            Objects.requireNonNull(clock, "clock");
            if (segments < 1 || segments > MAX_SEGMENTS) {
                throw new IllegalArgumentException("a cause adds 1.." + MAX_SEGMENTS + " segments");
            }
        }
    }

    /**
     * One clock.
     *
     * @param key identity
     * @param segments target, 1..{@value #MAX_SEGMENTS}; the clock is complete when its value reaches it
     * @param tick time tick, or null when only causes advance the clock
     * @param causes quest and clock causes, at most {@value #MAX_CAUSES}; at most one matches any one ending
     */
    public record Clock(Key key, int segments, Tick tick, List<Cause> causes) {
        /** Freeze the causes and require a clock that something can advance. */
        public Clock {
            Objects.requireNonNull(key, "key");
            causes = List.copyOf(causes);
            if (segments < 1 || segments > MAX_SEGMENTS) {
                throw new IllegalArgumentException("a clock has 1.." + MAX_SEGMENTS + " segments");
            }
            if (causes.size() > MAX_CAUSES) {
                throw new IllegalArgumentException("a clock declares at most " + MAX_CAUSES + " causes");
            }
            if (tick == null && causes.isEmpty()) {
                throw new IllegalArgumentException("a clock needs a time tick or a cause");
            }
            if (tick != null && tick.from() == Origin.ADVANCE && causes.isEmpty()) {
                throw new IllegalArgumentException("a tick counted from the first advance needs a cause");
            }
            // At most one cause may match any one fact, so each ending or completion counts once per clock.
            var anyEnding = new java.util.HashSet<String>();
            var endings = new java.util.HashSet<String>();
            var sources = new java.util.HashSet<Key>();
            for (Cause cause : causes) {
                if (cause.segments() > segments) {
                    throw new IllegalArgumentException("a cause adds at most the clock's segments");
                }
                switch (cause) {
                    case QuestEnded quest -> {
                        boolean repeated = quest.outcome() == null
                                ? anyEnding.contains(quest.quest()) || endings.stream().anyMatch(
                                        named -> named.startsWith(quest.quest() + "\n"))
                                : anyEnding.contains(quest.quest()) || endings.contains(quest.quest() + "\n" + quest.outcome());
                        if (repeated) {
                            throw new IllegalArgumentException("two causes match the same quest ending");
                        }
                        if (quest.outcome() == null) {
                            anyEnding.add(quest.quest());
                        } else {
                            endings.add(quest.quest() + "\n" + quest.outcome());
                        }
                    }
                    case ClockCompleted other -> {
                        if (other.clock().equals(key)) {
                            throw new IllegalArgumentException("a clock cannot advance itself");
                        }
                        if (!sources.add(other.clock())) {
                            throw new IllegalArgumentException("two causes name the same clock");
                        }
                    }
                }
            }
        }
    }
}
