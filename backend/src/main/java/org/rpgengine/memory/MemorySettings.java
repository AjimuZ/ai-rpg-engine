package org.rpgengine.memory;

import java.time.Duration;
import java.util.Objects;

import org.rpgengine.inference.InferenceSlot;
import org.springframework.core.env.Environment;

/**
 * Validated limits of checkpoints and continuity summaries (P09.3, docs/08 sections 8 and 9), read
 * from {@code engine.memory.*}. An out-of-range value fails startup with the property name only.
 *
 * <p>Summaries are <b>off by default</b>: they cost background inference, and until renewal and
 * rehydration (P09.4) consume them nothing depends on them; context assembly falls back to the
 * bounded recent-event window. Operational checkpoints are deterministic database work without
 * inference and follow the worker switch alone.
 *
 * @param summariesEnabled whether summary jobs may be requested and run
 * @param slot logical inference slot of summary tasks
 * @param budget total budget of one attempt's contract call, including its single repair; it must
 *        leave {@link #WAIT_MARGIN} inside the job lease
 * @param lease summary-job lease length, 30..300 seconds
 * @param maxAttempts attempts per summary job, 1..5
 * @param timeToLive how long a queued job stays claimable, 60..86,400 seconds
 * @param interval significant canonical actions (committed operations with public events) after
 *        which a new summary is requested, 1..10; ten is the docs/08 hard ceiling. A scene
 *        boundary (the player character moving) requests one earlier
 * @param maxPending installation-wide bound on unfinished summary jobs, 1..1,024; further requests
 *        are refused as throttled and retried by a later pass
 * @param checkpointRetention newest operational checkpoints kept per campaign, 2..64; older ones
 *        are deleted by the maintenance pass (a checkpoint is a resume aid, not a backup)
 */
public record MemorySettings(boolean summariesEnabled, InferenceSlot slot, Duration budget, Duration lease,
        int maxAttempts, Duration timeToLive, int interval, int maxPending, int checkpointRetention) {
    /** Wait beyond the contract budget for the port's own terminal result. */
    public static final Duration WAIT_MARGIN = Duration.ofSeconds(10);
    private static final String PREFIX = "engine.memory.";

    /** @throws IllegalArgumentException naming the first out-of-range setting */
    public MemorySettings {
        Objects.requireNonNull(slot, "slot");
        require(lease != null && lease.getNano() == 0 && lease.compareTo(Duration.ofSeconds(30)) >= 0
                && lease.compareTo(Duration.ofSeconds(300)) <= 0, "lease-seconds");
        require(budget != null && budget.compareTo(Duration.ofSeconds(1)) >= 0
                && budget.compareTo(Duration.ofSeconds(180)) <= 0 && budget.plus(WAIT_MARGIN).compareTo(lease) < 0,
                "budget-seconds");
        require(maxAttempts >= 1 && maxAttempts <= 5, "max-attempts");
        require(timeToLive != null && timeToLive.getNano() == 0 && timeToLive.compareTo(Duration.ofSeconds(60)) >= 0
                && timeToLive.compareTo(Duration.ofDays(1)) <= 0, "time-to-live-seconds");
        require(interval >= 1 && interval <= 10, "summary-interval");
        require(maxPending >= 1 && maxPending <= 1024, "max-pending");
        require(checkpointRetention >= 2 && checkpointRetention <= 64, "checkpoint-retention");
    }

    /**
     * Defaults: summaries disabled; {@code light} slot; 60-second budget; 120-second lease; three
     * attempts; one-hour claim window; a summary every five significant actions (docs/08 section
     * 8); 256 unfinished jobs installation-wide; eight checkpoints per campaign.
     *
     * @return the default settings
     */
    public static MemorySettings defaults() {
        return new MemorySettings(false, InferenceSlot.LIGHT, Duration.ofSeconds(60), Duration.ofSeconds(120), 3,
                Duration.ofHours(1), 5, 256, 8);
    }

    /** @return these settings with summaries switched on or off */
    public MemorySettings withSummaries(boolean value) {
        return new MemorySettings(value, slot, budget, lease, maxAttempts, timeToLive, interval, maxPending,
                checkpointRetention);
    }

    /** @return these settings with another installation-wide job bound */
    public MemorySettings withMaxPending(int value) {
        return new MemorySettings(summariesEnabled, slot, budget, lease, maxAttempts, timeToLive, interval, value,
                checkpointRetention);
    }

    /** @return these settings with another attempt bound */
    public MemorySettings withAttempts(int value) {
        return new MemorySettings(summariesEnabled, slot, budget, lease, value, timeToLive, interval, maxPending,
                checkpointRetention);
    }

    /**
     * Read and validate {@code engine.memory.*} over {@link #defaults()}.
     *
     * @param environment Spring environment
     * @return validated settings
     * @throws IllegalArgumentException for a malformed or out-of-range value
     */
    public static MemorySettings from(Environment environment) {
        MemorySettings base = defaults();
        try {
            return new MemorySettings(
                    environment.getProperty(PREFIX + "summaries.enabled", Boolean.class, base.summariesEnabled()),
                    InferenceSlot.valueOf(environment.getProperty(PREFIX + "slot", base.slot().name()).toUpperCase()),
                    Duration.ofSeconds(environment.getProperty(PREFIX + "budget-seconds", Long.class,
                            base.budget().toSeconds())),
                    Duration.ofSeconds(environment.getProperty(PREFIX + "lease-seconds", Long.class,
                            base.lease().toSeconds())),
                    environment.getProperty(PREFIX + "max-attempts", Integer.class, base.maxAttempts()),
                    Duration.ofSeconds(environment.getProperty(PREFIX + "time-to-live-seconds", Long.class,
                            base.timeToLive().toSeconds())),
                    environment.getProperty(PREFIX + "summary-interval", Integer.class, base.interval()),
                    environment.getProperty(PREFIX + "max-pending", Integer.class, base.maxPending()),
                    environment.getProperty(PREFIX + "checkpoint-retention", Integer.class, base.checkpointRetention()));
        } catch (RuntimeException malformed) {
            // Conversion failures can quote the value; report only that the memory settings are invalid.
            if (malformed.getMessage() != null && malformed.getMessage().startsWith(PREFIX)) {
                throw malformed;
            }
            throw new IllegalArgumentException(PREFIX + "* contains a malformed value");
        }
    }

    /** Fail with the property name only. */
    private static void require(boolean valid, String name) {
        if (!valid) {
            throw new IllegalArgumentException(PREFIX + name + " is out of range");
        }
    }
}
