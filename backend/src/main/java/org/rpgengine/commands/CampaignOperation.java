package org.rpgengine.commands;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.persistence.VersionedEntity;

/**
 * Operation journal entry (docs/07 section 2). The idempotency key is unique per
 * campaign; a retry with the same key and payload hash returns this entry, while the
 * same key with another hash is a conflict ({@link #matchesPayload}).
 *
 * <p>{@code operationVersion} counts this row's own lifecycle transitions. It is not a
 * world counter and never feeds {@code stateRevision}. It is also not the optimistic-lock
 * token: that is the inherited row version, which every update advances (V2). The
 * database additionally keeps terminal statuses final and the commit outcome
 * write-once. {@link CanonicalCommitService} sets the outcome with state, events,
 * settlements and outbox in one transaction. Leases and fencing arrive with P03.5.
 */
@Entity
@Table(name = "operation_journal")
public class CampaignOperation extends VersionedEntity {
    /** Inclusive receipt bound in UTF-8 bytes; matches operation_journal_receipt_size_ck (V2). */
    public static final int MAX_RECEIPT_BYTES = 65_536;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "payload_hash", nullable = false, updatable = false)
    private String payloadHash;

    @Column(name = "command_type", nullable = false, updatable = false)
    private String commandType;

    // Campaign stateRevision the command was planned against.
    @Column(name = "base_revision", nullable = false, updatable = false)
    private long baseRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OperationStatus status;

    @Column(name = "operation_version", nullable = false)
    private long operationVersion;

    @Column(name = "committed_revision")
    private Long committedRevision;

    // Owner-safe commit receipt; null until committed.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "receipt")
    private String receipt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Reserved for JPA. */
    protected CampaignOperation() {
    }

    /** @param id the new identity */
    private CampaignOperation(UUID id) {
        super(id);
    }

    /**
     * Journal a newly received command in {@link OperationStatus#ACCEPTED}.
     *
     * @param campaignId owning campaign
     * @param idempotencyKey 1..128 character client-chosen key, unique per campaign
     * @param payloadHash lowercase hexadecimal SHA-256 of the canonical command payload
     * @param commandType 1..128 character command type name
     * @param baseRevision campaign state revision the command targets
     * @param now acceptance time
     * @return an unsaved journal entry with a fresh opaque identity
     * @throws IllegalArgumentException if any argument is malformed
     */
    public static CampaignOperation accept(UUID campaignId, String idempotencyKey, String payloadHash,
            String commandType, long baseRevision, Instant now) {
        CampaignOperation operation = new CampaignOperation(UUID.randomUUID());
        operation.campaignId = Objects.requireNonNull(campaignId, "campaignId");
        operation.idempotencyKey = CanonicalValues.requireText(idempotencyKey, "idempotencyKey", 128);
        operation.payloadHash = CanonicalValues.requireSha256Hex(payloadHash, "payloadHash");
        operation.commandType = CanonicalValues.requireText(commandType, "commandType", 128);
        operation.baseRevision = CanonicalValues.requireNonNegative(baseRevision, "baseRevision");
        operation.status = OperationStatus.ACCEPTED;
        operation.createdAt = CanonicalValues.timestamp(now, "now");
        operation.updatedAt = operation.createdAt;
        return operation;
    }

    /**
     * Move to a non-commit successor status allowed by docs/07.
     *
     * @param next target status; {@link OperationStatus#COMMITTED} must use {@link #recordCommit}
     * @param now transition time
     * @throws IllegalStateException if the transition is not allowed
     * @throws IllegalArgumentException if {@code next} is COMMITTED
     */
    public void transitionTo(OperationStatus next, Instant now) {
        Objects.requireNonNull(next, "next");
        if (next == OperationStatus.COMMITTED) {
            // Committing without a revision and receipt would leave an unverifiable journal entry.
            throw new IllegalArgumentException("use recordCommit to reach COMMITTED");
        }
        advance(next, now);
    }

    /**
     * Record the outcome of a successful commit: COMMITTING to COMMITTED with the new
     * state revision and the owner-safe receipt.
     *
     * @param newRevision state revision produced by the commit; must exceed the base revision
     * @param receiptJson serialized receipt JSON object
     * @param now commit time
     * @throws IllegalStateException if the operation is not COMMITTING
     * @throws IllegalArgumentException if the revision or receipt is invalid
     */
    public void recordCommit(long newRevision, String receiptJson, Instant now) {
        if (newRevision <= baseRevision) {
            throw new IllegalArgumentException("committedRevision must exceed baseRevision");
        }
        CanonicalValues.requireJsonObject(receiptJson, "receipt", MAX_RECEIPT_BYTES);
        advance(OperationStatus.COMMITTED, now);
        committedRevision = newRevision;
        receipt = receiptJson;
    }

    /**
     * Shared transition step: validate against the lifecycle table, then bump the journal
     * version so concurrent readers can detect that the row changed.
     */
    private void advance(OperationStatus next, Instant now) {
        Instant changedAt = CanonicalValues.timestamp(now, "now");
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("illegal operation transition " + status + " -> " + next);
        }
        status = next;
        operationVersion = Math.addExact(operationVersion, 1);
        updatedAt = changedAt;
    }

    /**
     * @param candidateHash payload hash of a retried request with the same idempotency key
     * @return true if it is the same request (replay); false means an idempotency conflict
     */
    public boolean matchesPayload(String candidateHash) {
        return payloadHash.equals(candidateHash);
    }

    /** @return owning campaign ID */
    public UUID getCampaignId() {
        return campaignId;
    }

    /** @return idempotency key */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @return payload hash */
    public String getPayloadHash() {
        return payloadHash;
    }

    /** @return command type name */
    public String getCommandType() {
        return commandType;
    }

    /** @return base state revision */
    public long getBaseRevision() {
        return baseRevision;
    }

    /** @return current lifecycle status */
    public OperationStatus getStatus() {
        return status;
    }

    /** @return number of lifecycle transitions applied to this row */
    public long getOperationVersion() {
        return operationVersion;
    }

    /** @return committed state revision, or null before commit */
    public Long getCommittedRevision() {
        return committedRevision;
    }

    /** @return serialized receipt, or null before commit */
    public String getReceipt() {
        return receipt;
    }

    /** @return acceptance time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return last transition time */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
