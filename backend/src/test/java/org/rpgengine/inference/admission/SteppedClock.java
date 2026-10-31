package org.rpgengine.inference.admission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock that moves only when told to, so lease expiry and aging are deterministic. */
final class SteppedClock extends Clock {
    private Instant now;

    SteppedClock(Instant start) {
        now = start;
    }

    void advance(Duration step) {
        now = now.plus(step);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
