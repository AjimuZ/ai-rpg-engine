package org.rpgengine.characters;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.persistence.VersionedEntity;

/**
 * Actor instance owned by exactly one campaign (docs/05 section 2). Bounded numeric
 * values attach as {@link ActorResource} rows; attributes, conditions and inventory
 * attach in later phases (P06/P07). Identity, owning campaign, kind, definition and
 * creation revision are fixed after insert, and the database rejects any attempt to
 * move the actor to another campaign (V2).
 */
@Entity
@Table(name = "actor")
public class Actor extends VersionedEntity {
    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private ActorKind kind;

    // Content definition the actor was instantiated from; null for generated actors.
    @Column(name = "definition_id", updatable = false)
    private String definitionId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    // Stable content location ID; null while the actor is not placed.
    @Column(name = "location_id")
    private String locationId;

    @Column(name = "created_revision", nullable = false, updatable = false)
    private long createdRevision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Reserved for JPA. */
    protected Actor() {
    }

    /** @param id the new identity */
    private Actor(UUID id) {
        super(id);
    }

    /**
     * Create an actor inside a campaign.
     *
     * @param campaignId owning campaign
     * @param kind actor kind
     * @param definitionId optional 1..256 character content definition ID
     * @param displayName 1..120 character display name
     * @param locationId optional 1..256 character location ID
     * @param createdRevision state revision at which the actor came into existence
     * @param now creation time
     * @return an unsaved actor with a fresh opaque identity
     * @throws IllegalArgumentException if text or the revision is invalid
     */
    public static Actor create(UUID campaignId, ActorKind kind, String definitionId, String displayName,
            String locationId, long createdRevision, Instant now) {
        Actor actor = new Actor(UUID.randomUUID());
        actor.campaignId = Objects.requireNonNull(campaignId, "campaignId");
        actor.kind = Objects.requireNonNull(kind, "kind");
        actor.definitionId = CanonicalValues.optionalText(definitionId, "definitionId", 256);
        actor.displayName = CanonicalValues.requireText(displayName, "displayName", 120);
        actor.locationId = CanonicalValues.optionalText(locationId, "locationId", 256);
        actor.createdRevision = CanonicalValues.requireNonNegative(createdRevision, "createdRevision");
        actor.createdAt = CanonicalValues.timestamp(now, "now");
        return actor;
    }

    /** @return owning campaign ID */
    public UUID getCampaignId() {
        return campaignId;
    }

    /** @return actor kind */
    public ActorKind getKind() {
        return kind;
    }

    /** @return content definition ID or null */
    public String getDefinitionId() {
        return definitionId;
    }

    /** @return display name */
    public String getDisplayName() {
        return displayName;
    }

    /** @return location ID or null */
    public String getLocationId() {
        return locationId;
    }

    /** @return state revision that created the actor */
    public long getCreatedRevision() {
        return createdRevision;
    }

    /** @return creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
