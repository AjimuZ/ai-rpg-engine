package org.rpgengine.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.rpgengine.campaign.CampaignCounters;
import org.rpgengine.persistence.AssignedIdEntity;
import org.rpgengine.persistence.CanonicalValues;

/**
 * Immutable campaign checkpoint (docs/05 section 7): a snapshot at one state revision,
 * the event high-water mark it covers, the world counters at that point and the content
 * and ruleset hashes it was produced under. One checkpoint per campaign and revision.
 *
 * <p>The snapshot hash is supplied by the producer over its canonical serialization;
 * PostgreSQL {@code jsonb} may reformat the stored text, so verification must
 * re-canonicalize before hashing. Snapshot production, RNG cursors and restore are
 * defined by P03.4 and P03.5.
 */
@Entity
@Immutable
@Table(name = "campaign_checkpoint")
public class CampaignCheckpoint extends AssignedIdEntity {
    /** Inclusive snapshot bound in UTF-8 bytes; matches campaign_checkpoint_snapshot_size_ck (V2). */
    public static final int MAX_SNAPSHOT_BYTES = 8_388_608;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "state_revision", nullable = false, updatable = false)
    private long stateRevision;

    @Column(name = "event_high_water", nullable = false, updatable = false)
    private long eventHighWater;

    @Column(name = "game_turn", nullable = false, updatable = false)
    private long gameTurn;

    @Column(name = "world_time_seconds", nullable = false, updatable = false)
    private long worldTimeSeconds;

    @Column(name = "content_hash", nullable = false, updatable = false)
    private String contentHash;

    @Column(name = "ruleset_hash", nullable = false, updatable = false)
    private String rulesetHash;

    @Column(name = "snapshot_schema_version", nullable = false, updatable = false)
    private int snapshotSchemaVersion;

    @Column(name = "snapshot_hash", nullable = false, updatable = false)
    private String snapshotHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, updatable = false)
    private String snapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Reserved for JPA. */
    protected CampaignCheckpoint() {
    }

    /** @param id the new identity */
    private CampaignCheckpoint(UUID id) {
        super(id);
    }

    /**
     * Capture a checkpoint.
     *
     * @param campaignId owning campaign
     * @param counters canonical counters at the checkpoint; the event sequence becomes the high-water mark
     * @param hashes content, ruleset and snapshot digests
     * @param snapshotSchemaVersion positive snapshot schema version
     * @param snapshotJson serialized snapshot JSON object
     * @param now capture time
     * @return an unsaved checkpoint with a fresh opaque identity
     * @throws IllegalArgumentException if any argument is malformed
     */
    public static CampaignCheckpoint capture(UUID campaignId, CampaignCounters counters, Hashes hashes,
            int snapshotSchemaVersion, String snapshotJson, Instant now) {
        Objects.requireNonNull(counters, "counters");
        Objects.requireNonNull(hashes, "hashes");
        CampaignCheckpoint checkpoint = new CampaignCheckpoint(UUID.randomUUID());
        checkpoint.campaignId = Objects.requireNonNull(campaignId, "campaignId");
        checkpoint.stateRevision = counters.stateRevision();
        checkpoint.eventHighWater = counters.eventSequence();
        checkpoint.gameTurn = counters.gameTurn();
        checkpoint.worldTimeSeconds = counters.worldTimeSeconds();
        checkpoint.contentHash = hashes.contentHash();
        checkpoint.rulesetHash = hashes.rulesetHash();
        checkpoint.snapshotHash = hashes.snapshotHash();
        checkpoint.snapshotSchemaVersion =
                (int) CanonicalValues.requirePositive(snapshotSchemaVersion, "snapshotSchemaVersion");
        checkpoint.snapshot = CanonicalValues.requireJsonObject(snapshotJson, "snapshot", MAX_SNAPSHOT_BYTES);
        checkpoint.createdAt = CanonicalValues.timestamp(now, "now");
        return checkpoint;
    }

    /** @return owning campaign ID */
    public UUID getCampaignId() {
        return campaignId;
    }

    /** @return the canonical counters captured by this checkpoint */
    public CampaignCounters getCounters() {
        return CampaignCounters.of(stateRevision, gameTurn, worldTimeSeconds, eventHighWater);
    }

    /** @return the digests recorded with this checkpoint */
    public Hashes getHashes() {
        return new Hashes(contentHash, rulesetHash, snapshotHash);
    }

    /** @return snapshot schema version */
    public int getSnapshotSchemaVersion() {
        return snapshotSchemaVersion;
    }

    /** @return serialized snapshot */
    public String getSnapshot() {
        return snapshot;
    }

    /** @return capture time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Digests bound to a checkpoint, each lowercase hexadecimal SHA-256.
     *
     * @param contentHash content revision hash in force at the checkpoint
     * @param rulesetHash ruleset hash in force at the checkpoint
     * @param snapshotHash hash of the canonical snapshot serialization
     */
    public record Hashes(String contentHash, String rulesetHash, String snapshotHash) {
        /** Validate eagerly; throws IllegalArgumentException or NullPointerException. */
        public Hashes {
            CanonicalValues.requireSha256Hex(contentHash, "contentHash");
            CanonicalValues.requireSha256Hex(rulesetHash, "rulesetHash");
            CanonicalValues.requireSha256Hex(snapshotHash, "snapshotHash");
        }
    }
}
