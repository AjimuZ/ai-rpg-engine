package org.rpgengine.inference.admission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The executor's source of expected text demand: after a player's text turn on a local model ends, the player is taken
 * to be reading, and their next turn is expected until this window closes. No batch starts inside it, because a batch
 * would unload the text model that next turn needs (docs/34 section 10).
 *
 * <p>The answer is true from the moment {@link #turnEnded} is called until the length has passed, and false before any
 * turn ended. A zero length is never open, which is how an owner turns this off. It is a guess about a person, so a
 * wrong one costs time and never safety: too long delays an image, too short lets one start before the next turn.
 *
 * <p>{@link TextResidencyDriver}'s worker writes this and batch threads read it under the executor's monitor, so the
 * instant is volatile and neither call takes a lock. Nothing is stored, so a new process starts with no window open.
 */
final class ReadingWindow implements BooleanSupplier {
    private final Clock clock;
    private final Duration length;
    private volatile Instant lastTurnEnded;

    /**
     * @param clock source of the current time
     * @param length how long the window stays open after a turn, zero or more
     * @throws IllegalArgumentException when {@code length} is negative
     */
    ReadingWindow(Clock clock, Duration length) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.length = Objects.requireNonNull(length, "length");
        if (length.isNegative()) {
            throw new IllegalArgumentException("a reading window cannot be negative");
        }
    }

    /** A player's text turn ended: open the window, or restart it from now when it is open already. */
    void turnEnded() {
        lastTurnEnded = clock.instant();
    }

    /**
     * @return true while a text request is expected. A clock set back before the turn's end answers false, because
     *         otherwise images would wait for as long as the clock was moved
     */
    @Override
    public boolean getAsBoolean() {
        Instant ended = lastTurnEnded;
        if (ended == null) {
            return false;
        }
        Instant now = clock.instant();
        return !now.isBefore(ended) && now.isBefore(ended.plus(length));
    }
}
