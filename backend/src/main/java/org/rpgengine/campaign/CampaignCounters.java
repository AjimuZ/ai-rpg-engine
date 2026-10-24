package org.rpgengine.campaign;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import org.rpgengine.persistence.CanonicalValues;

/**
 * The canonical world counters of a campaign (docs/05 section 1), kept together so
 * code cannot confuse them with presentation generations or journal versions.
 *
 * <ul>
 *   <li>{@code stateRevision}: increments once per committed state-changing operation.</li>
 *   <li>{@code gameTurn}: advances only on an accepted world turn.</li>
 *   <li>{@code worldTimeSeconds}: in-world elapsed time, independent of wall-clock time.</li>
 *   <li>{@code eventSequence}: high-water mark of the per-campaign event sequence.</li>
 * </ul>
 *
 * Immutable value: P03.3 replaces the whole value when a commit advances the counters.
 * Locale, glossary and media generations deliberately live elsewhere
 * ({@code presentation_campaign_settings}).
 */
@Embeddable
public class CampaignCounters {
    @Column(name = "state_revision", nullable = false)
    private long stateRevision;

    @Column(name = "game_turn", nullable = false)
    private long gameTurn;

    @Column(name = "world_time_seconds", nullable = false)
    private long worldTimeSeconds;

    @Column(name = "event_sequence", nullable = false)
    private long eventSequence;

    /** Reserved for JPA. */
    protected CampaignCounters() {
    }

    /** Fields are validated by {@link #of}. */
    private CampaignCounters(long stateRevision, long gameTurn, long worldTimeSeconds, long eventSequence) {
        this.stateRevision = stateRevision;
        this.gameTurn = gameTurn;
        this.worldTimeSeconds = worldTimeSeconds;
        this.eventSequence = eventSequence;
    }

    /** @return the all-zero counters of a newly started campaign */
    public static CampaignCounters initial() {
        return new CampaignCounters(0, 0, 0, 0);
    }

    /**
     * @return a counter value with the given components
     * @throws IllegalArgumentException if any component is negative
     */
    public static CampaignCounters of(long stateRevision, long gameTurn, long worldTimeSeconds, long eventSequence) {
        return new CampaignCounters(
                CanonicalValues.requireNonNegative(stateRevision, "stateRevision"),
                CanonicalValues.requireNonNegative(gameTurn, "gameTurn"),
                CanonicalValues.requireNonNegative(worldTimeSeconds, "worldTimeSeconds"),
                CanonicalValues.requireNonNegative(eventSequence, "eventSequence"));
    }

    /** @return committed state revision */
    public long stateRevision() {
        return stateRevision;
    }

    /** @return accepted world turns */
    public long gameTurn() {
        return gameTurn;
    }

    /** @return in-world elapsed seconds */
    public long worldTimeSeconds() {
        return worldTimeSeconds;
    }

    /** @return last assigned event sequence (0 when no event exists) */
    public long eventSequence() {
        return eventSequence;
    }

    /** Value equality over all four counters. */
    @Override
    public boolean equals(Object other) {
        return other instanceof CampaignCounters that
                && stateRevision == that.stateRevision
                && gameTurn == that.gameTurn
                && worldTimeSeconds == that.worldTimeSeconds
                && eventSequence == that.eventSequence;
    }

    /** Consistent with {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(stateRevision, gameTurn, worldTimeSeconds, eventSequence);
    }

    /** Diagnostic form; contains no player-facing or secret data. */
    @Override
    public String toString() {
        return "CampaignCounters[stateRevision=" + stateRevision + ", gameTurn=" + gameTurn
                + ", worldTimeSeconds=" + worldTimeSeconds + ", eventSequence=" + eventSequence + "]";
    }
}
