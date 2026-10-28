package org.rpgengine.planning;

import java.time.Duration;
import java.util.Objects;

import org.rpgengine.inference.InferenceSlot;
import org.springframework.core.env.Environment;

/**
 * Validated limits of optional quest planning (docs/28 sections 9 and 12), read from
 * {@code engine.planning.*}. Planning is <b>off by default</b>: the conservative Economy profile
 * of docs/28 section 9, in which every quest plays from its authored or procedural baseline and no
 * planner inference is spent. Enabling it is an explicit owner choice; Balanced may become the
 * default only after the AP-12 safety and benefit gate. An out-of-range value fails startup with
 * the property name only.
 *
 * @param enabled whether quest-plan jobs may be requested, run and used at all
 * @param slot logical inference slot of planner tasks
 * @param budget total budget of one attempt's contract call, including its single repair;
 *        it must leave {@link #WAIT_MARGIN} inside the job lease
 * @param lease preparation-job lease length, 30..300 seconds
 * @param maxAttempts job attempts in total (retries plus the one regeneration), 1..5
 * @param timeToLive how long a queued job stays claimable, 60..86,400 seconds
 * @param replanAllowance most refinement jobs ever requested for one plan, 0..5; beyond it the
 *        quest keeps playing from its baseline and the surviving branches
 * @param maxHorizon longest planning horizon a plan may declare, 1 hour..30 days of world time
 * @param priority preparation-job priority, 0..1000
 */
public record PlanningSettings(boolean enabled, InferenceSlot slot, Duration budget, Duration lease, int maxAttempts,
        Duration timeToLive, int replanAllowance, Duration maxHorizon, int priority) {
    /** Wait beyond the contract budget for the port's own terminal result. */
    public static final Duration WAIT_MARGIN = Duration.ofSeconds(10);
    private static final String PREFIX = "engine.planning.";

    /** @throws IllegalArgumentException naming the first out-of-range setting */
    public PlanningSettings {
        Objects.requireNonNull(slot, "slot");
        require(lease != null && !lease.isNegative() && lease.getNano() == 0
                && lease.compareTo(Duration.ofSeconds(30)) >= 0 && lease.compareTo(Duration.ofSeconds(300)) <= 0,
                "lease-seconds");
        require(budget != null && budget.compareTo(Duration.ofSeconds(1)) >= 0
                && budget.compareTo(Duration.ofSeconds(180)) <= 0 && budget.plus(WAIT_MARGIN).compareTo(lease) < 0,
                "budget-seconds");
        require(maxAttempts >= 1 && maxAttempts <= 5, "max-attempts");
        require(timeToLive != null && timeToLive.getNano() == 0 && timeToLive.compareTo(Duration.ofSeconds(60)) >= 0
                && timeToLive.compareTo(Duration.ofDays(1)) <= 0, "time-to-live-seconds");
        require(replanAllowance >= 0 && replanAllowance <= 5, "replan-allowance");
        require(maxHorizon != null && maxHorizon.compareTo(Duration.ofHours(1)) >= 0
                && maxHorizon.compareTo(Duration.ofDays(30)) <= 0, "max-horizon-seconds");
        require(priority >= 0 && priority <= 1000, "priority");
    }

    /**
     * Defaults: disabled; {@code standard} slot; 60-second budget (two 30-second background
     * attempts, docs/28 section 9); 120-second lease; two attempts (one regeneration); one-hour
     * claim window; three refinements per plan; seven-day horizon; priority 100.
     *
     * @return the default settings
     */
    public static PlanningSettings defaults() {
        return new PlanningSettings(false, InferenceSlot.STANDARD, Duration.ofSeconds(60), Duration.ofSeconds(120), 2,
                Duration.ofHours(1), 3, Duration.ofDays(7), 100);
    }

    /** @return these settings with planning switched on or off */
    public PlanningSettings withEnabled(boolean value) {
        return new PlanningSettings(value, slot, budget, lease, maxAttempts, timeToLive, replanAllowance, maxHorizon,
                priority);
    }

    /**
     * Read and validate {@code engine.planning.*} over {@link #defaults()}.
     *
     * @param environment Spring environment
     * @return validated settings
     * @throws IllegalArgumentException for a malformed or out-of-range value
     */
    public static PlanningSettings from(Environment environment) {
        PlanningSettings base = defaults();
        try {
            return new PlanningSettings(
                    environment.getProperty(PREFIX + "enabled", Boolean.class, base.enabled()),
                    InferenceSlot.valueOf(environment.getProperty(PREFIX + "slot", base.slot().name()).toUpperCase()),
                    Duration.ofSeconds(environment.getProperty(PREFIX + "budget-seconds", Long.class,
                            base.budget().toSeconds())),
                    Duration.ofSeconds(environment.getProperty(PREFIX + "lease-seconds", Long.class,
                            base.lease().toSeconds())),
                    environment.getProperty(PREFIX + "max-attempts", Integer.class, base.maxAttempts()),
                    Duration.ofSeconds(environment.getProperty(PREFIX + "time-to-live-seconds", Long.class,
                            base.timeToLive().toSeconds())),
                    environment.getProperty(PREFIX + "replan-allowance", Integer.class, base.replanAllowance()),
                    Duration.ofSeconds(environment.getProperty(PREFIX + "max-horizon-seconds", Long.class,
                            base.maxHorizon().toSeconds())),
                    environment.getProperty(PREFIX + "priority", Integer.class, base.priority()));
        } catch (RuntimeException malformed) {
            // Conversion failures can quote the value; report only that the planning settings are invalid.
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
