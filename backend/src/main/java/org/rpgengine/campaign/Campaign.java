package org.rpgengine.campaign;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.persistence.VersionedEntity;

/**
 * Campaign aggregate root (docs/05 section 2): one playthrough of exactly one adventure,
 * pinned to one admitted content revision of that same adventure. The database enforces
 * the pairing with a composite foreign key; {@link #start} checks it before any SQL.
 *
 * <p>The canonical counters are embedded here; presentation preferences are not, so a
 * locale change can never rewrite this row. Counter advancement belongs to the atomic
 * commit path (P03.3). The database also
 * guards it (V2): counters never decrease, {@code stateRevision} advances by at most
 * one per update, and the adventure, content revision and seed identity are fixed.
 */
@Entity
@Table(name = "campaign")
public class Campaign extends VersionedEntity {
    @Column(name = "adventure_id", nullable = false, updatable = false)
    private UUID adventureId;

    @Column(name = "content_revision_id", nullable = false, updatable = false)
    private UUID contentRevisionId;

    @Column(name = "title", nullable = false)
    private String title;

    // Opaque reference to the seed material; never the secret seed itself (P03.4).
    @Column(name = "seed_identity", nullable = false, updatable = false)
    private String seedIdentity;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private CampaignMode mode;

    // Typed decision the engine is waiting for; null when nothing is pending.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_decision")
    private String pendingDecision;

    @Embedded
    private CampaignCounters counters;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Reserved for JPA. */
    protected Campaign() {
    }

    /** @param id the new identity */
    private Campaign(UUID id) {
        super(id);
    }

    /**
     * Start a campaign in exploration mode with all counters at zero.
     *
     * @param adventureId the adventure the campaign belongs to
     * @param revision admitted content revision to pin; must belong to {@code adventureId}
     * @param title 1..200 characters
     * @param seedIdentity 1..128 character opaque seed reference
     * @param now creation time
     * @return an unsaved campaign with a fresh opaque identity
     * @throws IllegalArgumentException if the revision belongs to another adventure or text is invalid
     */
    public static Campaign start(UUID adventureId, ContentRevision revision, String title,
            String seedIdentity, Instant now) {
        Objects.requireNonNull(adventureId, "adventureId");
        Objects.requireNonNull(revision, "revision");
        if (!adventureId.equals(revision.getAdventureId())) {
            throw new IllegalArgumentException("content revision belongs to another adventure");
        }
        Campaign campaign = new Campaign(UUID.randomUUID());
        campaign.adventureId = adventureId;
        campaign.contentRevisionId = revision.getId();
        campaign.title = CanonicalValues.requireText(title, "title", 200);
        campaign.seedIdentity = CanonicalValues.requireText(seedIdentity, "seedIdentity", 128);
        campaign.mode = CampaignMode.EXPLORATION;
        campaign.counters = CampaignCounters.initial();
        campaign.createdAt = CanonicalValues.timestamp(now, "now");
        campaign.updatedAt = campaign.createdAt;
        return campaign;
    }

    /**
     * Apply a resolved world's counters, mode and decision within the canonical commit.
     * Inputs are the exact base revision, resolved turn/time effects, positive event
     * count and new decision (null clears it). Returns the new counters. Rejects stale
     * revisions, malformed values or arithmetic overflow before changing this entity.
     * The caller must persist events, journal and outbox in the same transaction.
     */
    public CampaignCounters commitWorld(long expectedRevision, boolean advancesTurn, long elapsedSeconds,
            int eventCount, CampaignMode nextMode, String nextDecision, Instant now) {
        if (counters.stateRevision() != expectedRevision) {
            throw new IllegalStateException("stale campaign revision");
        }
        CanonicalValues.requireNonNegative(elapsedSeconds, "elapsedSeconds");
        CanonicalValues.requirePositive(eventCount, "eventCount");
        Objects.requireNonNull(nextMode, "nextMode");
        if (nextDecision != null) {
            CanonicalValues.requireJsonObject(nextDecision, "pendingDecision", 16_384);
        }
        Instant changedAt = CanonicalValues.timestamp(now, "now");
        CampaignCounters next = CampaignCounters.of(Math.addExact(expectedRevision, 1),
                Math.addExact(counters.gameTurn(), advancesTurn ? 1 : 0),
                Math.addExact(counters.worldTimeSeconds(), elapsedSeconds),
                Math.addExact(counters.eventSequence(), eventCount));
        counters = next;
        mode = nextMode;
        pendingDecision = nextDecision;
        updatedAt = changedAt;
        return next;
    }

    /** @return owning adventure ID */
    public UUID getAdventureId() {
        return adventureId;
    }

    /** @return pinned content revision ID */
    public UUID getContentRevisionId() {
        return contentRevisionId;
    }

    /** @return campaign title */
    public String getTitle() {
        return title;
    }

    /** @return opaque seed reference */
    public String getSeedIdentity() {
        return seedIdentity;
    }

    /** @return current scheduling mode */
    public CampaignMode getMode() {
        return mode;
    }

    /** @return serialized pending decision object, or null when none is pending */
    public String getPendingDecision() {
        return pendingDecision;
    }

    /** @return the canonical world counters */
    public CampaignCounters getCounters() {
        return counters;
    }

    /** @return creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return last change time of this row */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
