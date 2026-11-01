package org.rpgengine.inference.admission;

import java.time.Duration;

/**
 * How the {@link LeaseExecutor} behaves around the model server. No value here has been measured on a
 * real host; a later story sets them from a measured profile.
 *
 * @param slot server slot whose prompt cache is saved across a swap, not negative
 * @param headroomBytes GPU bytes that must stay free beside a model about to load, not negative
 * @param runningFloorBytes free GPU bytes below which a running job yields; 0 turns the check off
 * @param statusAttempts how many times a load, an unload or freed memory is checked, positive
 * @param statusDelay pause between two such checks, positive
 * @param recheck pause before requests that were put back ask again and before a failed reload or unload
 *        is retried, positive
 * @param patience how long a request may stay delayed by contention before it falls back, positive
 * @param timeCap longest time one job may hold its lease, positive
 * @param unmeasuredExtraBytes GPU bytes added to {@code headroomBytes} for a model whose size was never measured
 *        (docs/34 section 10), not negative; 0 means no extra margin
 */
public record LeaseExecutorSettings(int slot, long headroomBytes, long runningFloorBytes, int statusAttempts,
        Duration statusDelay, Duration recheck, Duration patience, Duration timeCap, long unmeasuredExtraBytes) {
    /** The settings with no extra margin for unmeasured models. */
    public LeaseExecutorSettings(int slot, long headroomBytes, long runningFloorBytes, int statusAttempts,
            Duration statusDelay, Duration recheck, Duration patience, Duration timeCap) {
        this(slot, headroomBytes, runningFloorBytes, statusAttempts, statusDelay, recheck, patience, timeCap, 0);
    }

    /** Validate every value. */
    public LeaseExecutorSettings {
        if (slot < 0 || headroomBytes < 0 || runningFloorBytes < 0 || statusAttempts < 1 || unmeasuredExtraBytes < 0) {
            throw new IllegalArgumentException("lease executor setting out of range");
        }
        for (Duration duration : new Duration[] {statusDelay, recheck, patience, timeCap}) {
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("lease executor duration must be positive");
            }
        }
    }
}
