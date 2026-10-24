package org.rpgengine.commands;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.rpgengine.persistence.AssignedIdEntity;
import org.rpgengine.persistence.CanonicalValues;

/**
 * Immutable delivery intent for one committed operation (V3). Receives only scoped
 * IDs, revision and kind; carries no private payload. Invalid values or a database
 * constraint failure abort the enclosing commit. Delivery is deliberately separate.
 */
@Entity
@Immutable
@Table(name = "canonical_outbox")
public class OutboxMessage extends AssignedIdEntity {
    /** Internal work categories; neither implies that delivery has already happened. */
    public enum Kind {
        /** Rebuild the authorized public projection from the committed revision. */
        PUBLIC_STATE,
        /** Prepare audience-filtered narration after the transaction has ended. */
        NARRATION_REQUIRED
    }

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;
    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;
    @Column(name = "state_revision", nullable = false, updatable = false)
    private long stateRevision;
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private Kind kind;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA hydration only; persistent fields are supplied by the database. */
    protected OutboxMessage() {
    }

    /**
     * Build an unsaved intent from a committed journal entry and kind.
     * Reject a missing/uncommitted entry; the deferred FK rechecks it at commit.
     */
    OutboxMessage(CampaignOperation operation, Kind kind, Instant now) {
        super(UUID.randomUUID());
        if (operation.getCommittedRevision() == null) {
            throw new IllegalArgumentException("outbox requires a committed operation");
        }
        campaignId = operation.getCampaignId();
        operationId = operation.getId();
        stateRevision = operation.getCommittedRevision();
        this.kind = Objects.requireNonNull(kind, "kind");
        createdAt = CanonicalValues.timestamp(now, "now");
    }
}
