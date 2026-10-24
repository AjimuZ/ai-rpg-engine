package org.rpgengine.progression;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.rpgengine.persistence.AssignedIdEntity;
import org.rpgengine.persistence.CanonicalValues;

/**
 * Reward ledger entry (docs/05 section 2). The pair (source key, recipient key) is
 * unique per campaign, so the same quest stage, encounter or milestone can never pay
 * the same recipient twice, even after retries or replays. Rows are immutable.
 *
 * <p>Source keys are stable strings such as {@code quest:<id>:<stage>} chosen by the
 * settling module; their grammar is fixed when quests and combat settle rewards.
 */
@Entity
@Immutable
@Table(name = "reward_settlement")
public class RewardSettlement extends AssignedIdEntity {
    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "source_key", nullable = false, updatable = false)
    private String sourceKey;

    @Column(name = "recipient_key", nullable = false, updatable = false)
    private String recipientKey;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Column(name = "applied_revision", nullable = false, updatable = false)
    private long appliedRevision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rewards", nullable = false, updatable = false)
    private String rewards;

    @Column(name = "settled_at", nullable = false, updatable = false)
    private Instant settledAt;

    /** Reserved for JPA. */
    protected RewardSettlement() {
    }

    /** @param id the new identity */
    private RewardSettlement(UUID id) {
        super(id);
    }

    /**
     * Record a settlement.
     *
     * @param campaignId owning campaign
     * @param sourceKey 1..256 character reward source identity
     * @param recipientKey 1..256 character recipient identity
     * @param operationId operation in the same campaign that applied the reward
     * @param appliedRevision positive state revision at which the reward was applied
     * @param rewardsJson serialized JSON object describing what was granted
     * @param now settlement time
     * @return an unsaved settlement with a fresh opaque identity
     * @throws IllegalArgumentException if any argument is malformed
     */
    public static RewardSettlement settle(UUID campaignId, String sourceKey, String recipientKey,
            UUID operationId, long appliedRevision, String rewardsJson, Instant now) {
        RewardSettlement settlement = new RewardSettlement(UUID.randomUUID());
        settlement.campaignId = Objects.requireNonNull(campaignId, "campaignId");
        settlement.sourceKey = CanonicalValues.requireText(sourceKey, "sourceKey", 256);
        settlement.recipientKey = CanonicalValues.requireText(recipientKey, "recipientKey", 256);
        settlement.operationId = Objects.requireNonNull(operationId, "operationId");
        settlement.appliedRevision = CanonicalValues.requirePositive(appliedRevision, "appliedRevision");
        settlement.rewards = CanonicalValues.requireJsonObject(rewardsJson, "rewards");
        settlement.settledAt = CanonicalValues.timestamp(now, "now");
        return settlement;
    }

    /** @return owning campaign ID */
    public UUID getCampaignId() {
        return campaignId;
    }

    /** @return reward source identity */
    public String getSourceKey() {
        return sourceKey;
    }

    /** @return recipient identity */
    public String getRecipientKey() {
        return recipientKey;
    }

    /** @return applying operation ID */
    public UUID getOperationId() {
        return operationId;
    }

    /** @return state revision at which the reward was applied */
    public long getAppliedRevision() {
        return appliedRevision;
    }

    /** @return serialized granted rewards */
    public String getRewards() {
        return rewards;
    }

    /** @return settlement time */
    public Instant getSettledAt() {
        return settledAt;
    }
}
