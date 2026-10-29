package org.rpgengine.rules;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pure rest arithmetic (P10.4, docs/10 section 8). A rest policy is authored pack data: how long it
 * takes, where it may be taken, how likely it is to be interrupted, which resources it restores and
 * which conditions it removes. This class holds the policy value and the three calculations that depend
 * only on it, so a replay with the same recorded draw always gives the same result.
 *
 * <p>Interruption is one recorded percentile draw (1..100). A draw at or below the policy's chance
 * interrupts the rest: only {@link #INTERRUPTED_NUMERATOR}/{@link #INTERRUPTED_DENOMINATOR} (half) of
 * the duration passes, restoration is halved (rounded down) and no condition is removed. A policy with
 * no chance never draws.
 */
public final class RestRules {
    /** Fraction of the duration and of the restoration an interrupted rest keeps. */
    public static final int INTERRUPTED_NUMERATOR = 1;
    public static final int INTERRUPTED_DENOMINATOR = 2;
    /** Sides of the interruption draw. */
    public static final int INTERRUPTION_SIDES = 100;

    /** Static helpers only. */
    private RestRules() {
    }

    /**
     * An authored rest policy, free of prose.
     *
     * @param pack declaring pack ID; owns the unqualified resource keys of {@code restores}
     * @param minutes duration of an uninterrupted rest, 1..{@link WorldClock#MAX_MINUTES}
     * @param locationTag tag the player's location must carry, or null when the rest may be taken anywhere
     * @param interruptionPercent chance 0..99 that the rest is interrupted; 0 means it never is
     * @param restores units restored per resource key (clamped to the pool maximum), sorted by key
     * @param removes canonical condition IDs an uninterrupted rest removes, sorted
     */
    public record Policy(String pack, int minutes, String locationTag, int interruptionPercent,
            Map<String, Integer> restores, List<String> removes) {
        /** Validate the bounds and freeze the collections. */
        public Policy {
            Objects.requireNonNull(pack, "pack");
            if (minutes < 1 || minutes > WorldClock.MAX_MINUTES) {
                throw new IllegalArgumentException("rest minutes outside 1.." + WorldClock.MAX_MINUTES);
            }
            if (interruptionPercent < 0 || interruptionPercent > 99) {
                throw new IllegalArgumentException("interruption chance outside 0..99");
            }
            restores = java.util.Collections.unmodifiableMap(new TreeMap<>(restores));
            removes = removes.stream().sorted().toList();
        }

        /** @return whether resolving this policy needs the interruption draw */
        public boolean interruptible() {
            return interruptionPercent > 0;
        }
    }

    /**
     * Decide interruption from the recorded draw.
     *
     * @param policy the policy
     * @param draw value 1..100 of the recorded draw; ignored for a policy that cannot be interrupted
     * @return whether the rest is interrupted
     */
    public static boolean interrupted(Policy policy, int draw) {
        if (!policy.interruptible()) {
            return false;
        }
        if (draw < 1 || draw > INTERRUPTION_SIDES) {
            throw new IllegalArgumentException("interruption draw outside 1.." + INTERRUPTION_SIDES);
        }
        return draw <= policy.interruptionPercent();
    }

    /**
     * Seconds the rest actually takes.
     *
     * @param policy the policy
     * @param interrupted whether it was interrupted
     * @return the full duration, or half of it (rounded down, at least one minute) when interrupted
     */
    public static long elapsedSeconds(Policy policy, boolean interrupted) {
        long full = WorldClock.seconds(policy.minutes());
        return interrupted ? Math.max(WorldClock.MINUTE, full * INTERRUPTED_NUMERATOR / INTERRUPTED_DENOMINATOR) : full;
    }

    /**
     * Units restored to one pool.
     *
     * @param declared units the policy restores for the resource
     * @param interrupted whether the rest was interrupted (halves the amount, rounded down)
     * @param headroom units the pool can still take: maximum minus current, never negative
     * @return the units actually added, between 0 and {@code headroom}
     */
    public static long restored(int declared, boolean interrupted, long headroom) {
        long units = interrupted ? (long) declared * INTERRUPTED_NUMERATOR / INTERRUPTED_DENOMINATOR : declared;
        return Math.max(0, Math.min(units, headroom));
    }
}
