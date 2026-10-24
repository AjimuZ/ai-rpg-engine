package org.rpgengine.campaign;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.rpgengine.persistence.AssignedIdEntity;
import org.rpgengine.persistence.CanonicalValues;

/**
 * Append-only domain event (docs/05 section 6). The per-campaign sequence is unique;
 * the operation, actor and causation event must belong to the same campaign, which
 * composite foreign keys enforce. A database trigger rejects UPDATE and DELETE.
 *
 * <p>This class only shapes and stores the envelope. Assigning sequences atomically
 * with state changes, receipts and the outbox is handled by CanonicalCommitService (P03.3).
 */
@Entity
@Immutable
@Table(name = "campaign_event")
public class CampaignEvent extends AssignedIdEntity {
    /** Inclusive payload bound in UTF-8 bytes; matches campaign_event_payload_size_ck (V2). */
    public static final int MAX_PAYLOAD_BYTES = 65_536;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "sequence", nullable = false, updatable = false)
    private long sequence;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "causation_event_id", updatable = false)
    private UUID causationEventId;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false)
    private int eventVersion;

    @Column(name = "state_revision", nullable = false, updatable = false)
    private long stateRevision;

    @Column(name = "world_time_seconds", nullable = false, updatable = false)
    private long worldTimeSeconds;

    @Column(name = "ruleset_hash", nullable = false, updatable = false)
    private String rulesetHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, updatable = false)
    private EventVisibility visibility;

    @Column(name = "payload_schema_version", nullable = false, updatable = false)
    private int payloadSchemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Reserved for JPA. */
    protected CampaignEvent() {
    }

    /** @param id the new identity */
    private CampaignEvent(UUID id) {
        super(id);
    }

    /**
     * Build a new event row.
     *
     * @param envelope campaign, ordering, causality and rule context
     * @param body typed payload and its versions
     * @param now real creation time (wall clock, distinct from in-world time)
     * @return an unsaved event with a fresh opaque identity
     */
    public static CampaignEvent append(Envelope envelope, Body body, Instant now) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(body, "body");
        CampaignEvent event = new CampaignEvent(UUID.randomUUID());
        event.campaignId = envelope.campaignId();
        event.sequence = envelope.sequence();
        event.operationId = envelope.operationId();
        event.actorId = envelope.actorId();
        event.causationEventId = envelope.causationEventId();
        event.correlationId = envelope.correlationId();
        event.stateRevision = envelope.stateRevision();
        event.worldTimeSeconds = envelope.worldTimeSeconds();
        event.rulesetHash = envelope.rulesetHash();
        event.visibility = envelope.visibility();
        event.eventType = body.eventType();
        event.eventVersion = body.eventVersion();
        event.payloadSchemaVersion = body.payloadSchemaVersion();
        event.payload = body.payload();
        event.createdAt = CanonicalValues.timestamp(now, "now");
        return event;
    }

    /** @return owning campaign ID */
    public UUID getCampaignId() {
        return campaignId;
    }

    /** @return the event's ordering and causality context */
    public Envelope getEnvelope() {
        return new Envelope(campaignId, sequence, operationId, actorId, causationEventId,
                correlationId, stateRevision, worldTimeSeconds, rulesetHash, visibility);
    }

    /** @return the typed payload and its versions */
    public Body getBody() {
        return new Body(eventType, eventVersion, payloadSchemaVersion, payload);
    }

    /** @return real creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Ordering, causality and rule context of an event.
     *
     * @param campaignId owning campaign
     * @param sequence positive per-campaign sequence number
     * @param operationId operation that produced the event (same campaign)
     * @param actorId acting actor in the same campaign, or null for world events
     * @param causationEventId earlier event in the same campaign that caused this one, or null
     * @param correlationId groups events of one logical interaction
     * @param stateRevision state revision the event belongs to
     * @param worldTimeSeconds in-world time when the event happened
     * @param rulesetHash lowercase hexadecimal SHA-256 of the ruleset used
     * @param visibility public or engine-internal
     */
    public record Envelope(UUID campaignId, long sequence, UUID operationId, UUID actorId,
            UUID causationEventId, UUID correlationId, long stateRevision, long worldTimeSeconds,
            String rulesetHash, EventVisibility visibility) {
        /** Validate eagerly; throws IllegalArgumentException or NullPointerException. */
        public Envelope {
            Objects.requireNonNull(campaignId, "campaignId");
            CanonicalValues.requirePositive(sequence, "sequence");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(correlationId, "correlationId");
            CanonicalValues.requireNonNegative(stateRevision, "stateRevision");
            CanonicalValues.requireNonNegative(worldTimeSeconds, "worldTimeSeconds");
            CanonicalValues.requireSha256Hex(rulesetHash, "rulesetHash");
            Objects.requireNonNull(visibility, "visibility");
        }
    }

    /**
     * Typed payload of an event.
     *
     * @param eventType 1..128 character event type name
     * @param eventVersion positive version of the event type
     * @param payloadSchemaVersion positive version of the payload schema
     * @param payload serialized JSON object, bounded by later payload schemas
     */
    public record Body(String eventType, int eventVersion, int payloadSchemaVersion, String payload) {
        /** Validate eagerly; throws IllegalArgumentException or NullPointerException. */
        public Body {
            CanonicalValues.requireText(eventType, "eventType", 128);
            CanonicalValues.requirePositive(eventVersion, "eventVersion");
            CanonicalValues.requirePositive(payloadSchemaVersion, "payloadSchemaVersion");
            CanonicalValues.requireJsonObject(payload, "payload", MAX_PAYLOAD_BYTES);
        }
    }
}
