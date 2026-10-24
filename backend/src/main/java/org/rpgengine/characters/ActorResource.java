package org.rpgengine.characters;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.persistence.VersionedEntity;

/**
 * Bounded numeric resource of one actor, such as health, stamina or ammunition
 * (docs/05 sections 2 and 4). The current value always lies within
 * {@code [minimumValue, maximumValue]}: a change that would leave the bounds is
 * rejected, never clamped, so the caller decides whether a cost is affordable.
 *
 * <p>The resource belongs to the same campaign as its actor; the database enforces the
 * pairing with a composite foreign key and keeps identity, owner and key fixed after
 * insert. {@code updatedRevision} records the campaign state revision of the last
 * change and only moves forward. Which resources exist and how rules change them is
 * defined by content and the rules phases (P06/P07); this class only guards the bounds.
 */
@Entity
@Table(name = "actor_resource")
public class ActorResource extends VersionedEntity {
    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    // Stable content key such as "health"; display names are localized elsewhere.
    @Column(name = "resource_key", nullable = false, updatable = false)
    private String resourceKey;

    @Column(name = "minimum_value", nullable = false)
    private long minimumValue;

    @Column(name = "current_value", nullable = false)
    private long currentValue;

    @Column(name = "maximum_value", nullable = false)
    private long maximumValue;

    @Column(name = "updated_revision", nullable = false)
    private long updatedRevision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Reserved for JPA. */
    protected ActorResource() {
    }

    /** @param id the new identity */
    private ActorResource(UUID id) {
        super(id);
    }

    /**
     * Create a resource for an actor.
     *
     * @param actor owning actor; supplies the campaign so the two can never disagree
     * @param resourceKey 1..256 character content key, unique per actor
     * @param minimumValue inclusive lower bound
     * @param currentValue initial value within the bounds
     * @param maximumValue inclusive upper bound
     * @param revision campaign state revision that created the resource
     * @param now creation time
     * @return an unsaved resource with a fresh opaque identity
     * @throws IllegalArgumentException if the key is malformed, the bounds are inverted,
     *         the value is outside them or the revision is negative
     */
    public static ActorResource create(Actor actor, String resourceKey, long minimumValue, long currentValue,
            long maximumValue, long revision, Instant now) {
        Objects.requireNonNull(actor, "actor");
        ActorResource resource = new ActorResource(UUID.randomUUID());
        resource.campaignId = actor.getCampaignId();
        resource.actorId = actor.getId();
        resource.resourceKey = CanonicalValues.requireText(resourceKey, "resourceKey", 256);
        requireWithin(minimumValue, currentValue, maximumValue);
        resource.minimumValue = minimumValue;
        resource.currentValue = currentValue;
        resource.maximumValue = maximumValue;
        resource.updatedRevision = CanonicalValues.requireNonNegative(revision, "revision");
        resource.createdAt = CanonicalValues.timestamp(now, "now");
        resource.updatedAt = resource.createdAt;
        return resource;
    }

    /**
     * Change the current value by a signed amount.
     *
     * @param delta amount to add; negative values spend the resource
     * @param revision campaign state revision of this change; must not be older than the last one
     * @param now change time
     * @throws IllegalArgumentException if the result leaves the bounds or overflows, or the
     *         revision moves backwards; the resource is unchanged in that case
     */
    public void adjust(long delta, long revision, Instant now) {
        long next;
        try {
            next = Math.addExact(currentValue, delta);
        } catch (ArithmeticException overflow) {
            // An overflowing amount is a malformed request, reported like any out-of-bounds value.
            throw new IllegalArgumentException("resource adjustment overflows", overflow);
        }
        requireWithin(minimumValue, next, maximumValue);
        touch(revision, now);
        currentValue = next;
    }

    /**
     * Replace both bounds, for example after a level-up raises maximum health. The
     * current value must already fit; this never clamps it silently.
     *
     * @param newMinimum inclusive lower bound
     * @param newMaximum inclusive upper bound
     * @param revision campaign state revision of this change; must not be older than the last one
     * @param now change time
     * @throws IllegalArgumentException if the bounds are inverted, exclude the current value
     *         or the revision moves backwards; the resource is unchanged in that case
     */
    public void changeBounds(long newMinimum, long newMaximum, long revision, Instant now) {
        requireWithin(newMinimum, currentValue, newMaximum);
        touch(revision, now);
        minimumValue = newMinimum;
        maximumValue = newMaximum;
    }

    /**
     * Validate and record the revision and time of a change. Callers run every other
     * check first, so a rejected change leaves the resource untouched.
     */
    private void touch(long revision, Instant now) {
        Instant changedAt = CanonicalValues.timestamp(now, "now");
        if (revision < updatedRevision) {
            throw new IllegalArgumentException("revision must not move backwards from " + updatedRevision);
        }
        updatedRevision = revision;
        updatedAt = changedAt;
    }

    /** Mirror of actor_resource_bounds_ck: minimum <= value <= maximum. */
    private static void requireWithin(long minimum, long value, long maximum) {
        if (minimum > maximum) {
            throw new IllegalArgumentException("minimumValue must not exceed maximumValue");
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("value " + value + " is outside [" + minimum + ", " + maximum + "]");
        }
    }

    /** @return owning campaign ID */
    public UUID getCampaignId() {
        return campaignId;
    }

    /** @return owning actor ID */
    public UUID getActorId() {
        return actorId;
    }

    /** @return stable content key */
    public String getResourceKey() {
        return resourceKey;
    }

    /** @return inclusive lower bound */
    public long getMinimumValue() {
        return minimumValue;
    }

    /** @return current value, always within the bounds */
    public long getCurrentValue() {
        return currentValue;
    }

    /** @return inclusive upper bound */
    public long getMaximumValue() {
        return maximumValue;
    }

    /** @return campaign state revision of the last change */
    public long getUpdatedRevision() {
        return updatedRevision;
    }

    /** @return creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return last change time */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
