package org.rpgengine.inference.scheduling;

import java.time.Duration;
import java.util.function.Function;

import org.springframework.core.env.Environment;

/**
 * Validated limits of the {@link InferenceScheduler} (docs/06 section 12, docs/28 section 9).
 * Every value is a local bound to qualify, not a provider promise. Read from
 * {@code engine.inference.scheduler.*} by {@link #from(Environment)}; an out-of-range value fails
 * startup with the property name only, never the value.
 *
 * @param maxActive runtime turns the scheduler may hold at once. Production accepts only
 *        {@link #QUALIFIED_MAX_ACTIVE}; larger values exist for the pure policy tests and need the
 *        P01.7/QA-37 evidence plus a migration replacing the V14 single-turn index before use
 * @param interactiveCapacity interactive tasks waiting or running installation-wide; background
 *        work can never use these places (the queue part of the interactive reservation)
 * @param interactivePerCampaign interactive tasks waiting or running per campaign
 * @param backgroundCapacity background tasks waiting or running installation-wide
 * @param backgroundPerCampaign background tasks waiting or running per campaign
 * @param dailyUsageLimit estimated usage units dispatched per rolling 24 hours, all classes
 * @param backgroundDailyUsageLimit estimated usage units dispatched per rolling 24 hours by
 *        background classes; at most {@code dailyUsageLimit}
 * @param agingStep waiting time that promotes a background task by one background class
 * @param backoffInitial first suspension of background work after an account condition
 * @param backoffMax longest suspension; each consecutive condition doubles the previous one
 * @param tick period of the scheduler's timer (queue deadlines, reconciliation, backoff expiry)
 */
public record SchedulerSettings(int maxActive, int interactiveCapacity, int interactivePerCampaign,
        int backgroundCapacity, int backgroundPerCampaign, long dailyUsageLimit, long backgroundDailyUsageLimit,
        Duration agingStep, Duration backoffInitial, Duration backoffMax, Duration tick) {
    /**
     * Highest number of simultaneous runtime turns with recorded qualification evidence. P01.7
     * explicitly selected serialized inference (one active turn installation-wide) and no QA-37
     * run has qualified more; raising this constant requires that evidence (docs/06 section 12).
     */
    public static final int QUALIFIED_MAX_ACTIVE = 1;
    /** Upper bound of {@code maxActive} accepted by the policy itself. */
    static final int MAX_ACTIVE_BOUND = 8;
    private static final String PREFIX = "engine.inference.scheduler.";

    /** @throws IllegalArgumentException naming the first out-of-range setting */
    public SchedulerSettings {
        require(maxActive >= 1 && maxActive <= MAX_ACTIVE_BOUND, "max-active");
        require(interactiveCapacity >= 1 && interactiveCapacity <= 256, "interactive-capacity");
        require(interactivePerCampaign >= 1 && interactivePerCampaign <= interactiveCapacity,
                "interactive-per-campaign");
        require(backgroundCapacity >= 0 && backgroundCapacity <= 256, "background-capacity");
        require(backgroundPerCampaign >= 0 && backgroundPerCampaign <= Math.max(backgroundCapacity, 0),
                "background-per-campaign");
        require(dailyUsageLimit >= 1 && dailyUsageLimit <= 1_000_000_000L, "daily-usage-limit");
        require(backgroundDailyUsageLimit >= 0 && backgroundDailyUsageLimit <= dailyUsageLimit,
                "background-daily-usage-limit");
        require(within(agingStep, Duration.ofSeconds(1), Duration.ofHours(1)), "aging-step");
        require(within(backoffInitial, Duration.ofSeconds(1), Duration.ofHours(1)), "backoff-initial");
        require(within(backoffMax, backoffInitial == null ? Duration.ofSeconds(1) : backoffInitial,
                Duration.ofHours(6)), "backoff-max");
        require(within(tick, Duration.ofMillis(10), Duration.ofSeconds(5)), "tick-ms");
    }

    /**
     * Defaults of the initial candidate policy (docs/28 section 9): one active turn, at most two
     * pending background tasks per campaign and eight installation-wide, sixteen interactive
     * places (four per campaign), four million estimated units a day of which background work may
     * use one tenth, one-minute aging, and a 30-second backoff doubling up to 15 minutes.
     *
     * @return the default settings
     */
    public static SchedulerSettings defaults() {
        return new SchedulerSettings(1, 16, 4, 8, 2, 4_000_000L, 400_000L, Duration.ofSeconds(60),
                Duration.ofSeconds(30), Duration.ofMinutes(15), Duration.ofMillis(100));
    }

    /**
     * Read and validate {@code engine.inference.scheduler.*} over {@link #defaults()}.
     *
     * @param environment Spring environment
     * @return validated settings
     * @throws IllegalArgumentException for a malformed or out-of-range value, and for
     *         {@code max-active} above {@link #QUALIFIED_MAX_ACTIVE}
     *         ({@code concurrency-unqualified}); the message names the property, never the value
     */
    public static SchedulerSettings from(Environment environment) {
        SchedulerSettings d = defaults();
        int maxActive = read(environment, "max-active", Integer::parseInt, d.maxActive());
        if (maxActive > QUALIFIED_MAX_ACTIVE) {
            throw new IllegalArgumentException(PREFIX + "max-active above " + QUALIFIED_MAX_ACTIVE
                    + " is concurrency-unqualified: it requires recorded P01.7 evidence and a QA-37 qualification");
        }
        return new SchedulerSettings(maxActive,
                read(environment, "interactive-capacity", Integer::parseInt, d.interactiveCapacity()),
                read(environment, "interactive-per-campaign", Integer::parseInt, d.interactivePerCampaign()),
                read(environment, "background-capacity", Integer::parseInt, d.backgroundCapacity()),
                read(environment, "background-per-campaign", Integer::parseInt, d.backgroundPerCampaign()),
                read(environment, "daily-usage-limit", Long::parseLong, d.dailyUsageLimit()),
                read(environment, "background-daily-usage-limit", Long::parseLong, d.backgroundDailyUsageLimit()),
                Duration.ofSeconds(read(environment, "aging-step-seconds", Long::parseLong,
                        d.agingStep().toSeconds())),
                Duration.ofSeconds(read(environment, "backoff-initial-seconds", Long::parseLong,
                        d.backoffInitial().toSeconds())),
                Duration.ofSeconds(read(environment, "backoff-max-seconds", Long::parseLong,
                        d.backoffMax().toSeconds())),
                Duration.ofMillis(read(environment, "tick-ms", Long::parseLong, d.tick().toMillis())));
    }

    private static <T> T read(Environment environment, String name, Function<String, T> parse, T fallback) {
        String raw = environment.getProperty(PREFIX + name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return parse.apply(raw.trim());
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(PREFIX + name + " must be a whole number");
        }
    }

    private static boolean within(Duration value, Duration min, Duration max) {
        return value != null && value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    private static void require(boolean valid, String name) {
        if (!valid) {
            throw new IllegalArgumentException(PREFIX + name + " is outside its supported range");
        }
    }
}
