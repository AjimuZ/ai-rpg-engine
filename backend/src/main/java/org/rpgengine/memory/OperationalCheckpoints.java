package org.rpgengine.memory;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operational checkpoints (P09.3, docs/08 sections 3 and 10): a compact, deterministic snapshot of
 * one campaign's canonical state at one revision, kept current independently of any AI-generated
 * summary and regenerated from canonical rows, never from a chat transcript.
 *
 * <p><b>Content.</b> The snapshot is built by the database function
 * {@code memory_operational_snapshot} (V16): counters, mode, pending decision, the player
 * character, every actor with its real location, resources, items, abilities, known quests, open
 * commitments, operations whose narration is still pending, and the RNG cursor (algorithm and draw
 * count, never the seed). It is privileged backend data and is never rendered to a limited audience.
 *
 * <p><b>Hash.</b> The snapshot is a {@code jsonb} value and its hash is the SHA-256 of PostgreSQL's
 * canonical {@code jsonb} text, computed in SQL. Equal canonical state therefore always yields the
 * same snapshot and hash, and verification re-derives both from storage rather than trusting any
 * client serialization (the reformatting caveat of {@link CampaignCheckpoint}).
 *
 * <p><b>Safe boundary.</b> A capture reads one repeatable-read snapshot and refuses while a mutation
 * lease is live or an operation is {@code COMMITTING}: a checkpoint is never taken inside an
 * unresolved commit race (docs/08 section 7, step 1). Committed operations awaiting narration are
 * a safe boundary and are recorded as pending narration.
 *
 * <p>A checkpoint in the same database is a resume aid, not a backup (docs/08 section 9); older
 * checkpoints beyond the retention bound are deleted by {@link #prune}.
 */
@Service
public class OperationalCheckpoints {
    /** Layout version of {@code memory_operational_snapshot}. */
    public static final int SNAPSHOT_SCHEMA_VERSION = 1;
    /** SQL of the canonical snapshot hash over a jsonb expression {@code s}. */
    private static final String HASH = "encode(sha256(convert_to(s::text, 'UTF8')), 'hex')";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    /** Spring constructor over the system UTC clock. */
    @Autowired
    public OperationalCheckpoints(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, Clock.systemUTC());
    }

    /**
     * @param jdbc storage
     * @param manager transaction manager; captures and verifications run in repeatable-read transactions
     * @param clock lease expiry and creation time source
     */
    public OperationalCheckpoints(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        snapshot = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(15);
    }

    /**
     * Capture a checkpoint of the campaign's current committed revision, or confirm the existing one.
     *
     * @param campaign campaign identity
     * @return CAPTURED with a new checkpoint, EXISTING when this revision already has an identical
     *         one, UNSAFE_BOUNDARY when a commit may be in progress (nothing written), or MISMATCH
     *         when a stored checkpoint of this revision differs from canonical state (nothing
     *         written; {@link #verify} reports the details)
     * @throws IllegalArgumentException for an unknown campaign
     * @throws IllegalStateException inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for storage failures, including a
     *         serialization failure when another capture of the same revision raced this one
     */
    public Capture capture(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        requireNoTransaction();
        Instant now = clock.instant();
        return Objects.requireNonNull(snapshot.execute(status -> {
            var counters = jdbc.query("select c.state_revision, c.event_sequence, c.game_turn, c.world_time_seconds, "
                    + "r.content_hash, r.ruleset_hash from campaign c join content_revision r on r.id = c.content_revision_id "
                    + "and r.adventure_id = c.adventure_id where c.id = ?", (row, index) -> new Object[] {row.getLong(1),
                            row.getLong(2), row.getLong(3), row.getLong(4), row.getString(5), row.getString(6)}, campaign);
            if (counters.isEmpty()) {
                throw new IllegalArgumentException("unknown campaign");
            }
            Object[] state = counters.getFirst();
            long revision = (long) state[0];
            Integer live = jdbc.queryForObject("select count(*) from campaign_mutation_lease where campaign_id = ? "
                    + "and operation_id is not null and expires_at > ?", Integer.class, campaign, Timestamp.from(now));
            Integer committing = jdbc.queryForObject("select count(*) from operation_journal where campaign_id = ? "
                    + "and status = 'COMMITTING'", Integer.class, campaign);
            if ((live != null && live > 0) || (committing != null && committing > 0)) {
                return new Capture(Capture.Outcome.UNSAFE_BOUNDARY, null, revision);
            }
            var existing = jdbc.query("select id, snapshot = memory_operational_snapshot(campaign_id) from "
                    + "campaign_checkpoint where campaign_id = ? and state_revision = ?", (row, index) -> new Object[] {
                            row.getObject(1, UUID.class), row.getBoolean(2)}, campaign, revision);
            if (!existing.isEmpty()) {
                return new Capture((boolean) existing.getFirst()[1] ? Capture.Outcome.EXISTING : Capture.Outcome.MISMATCH,
                        (UUID) existing.getFirst()[0], revision);
            }
            UUID id = UUID.randomUUID();
            jdbc.update("insert into campaign_checkpoint (id, campaign_id, state_revision, event_high_water, game_turn, "
                    + "world_time_seconds, content_hash, ruleset_hash, snapshot_schema_version, snapshot_hash, snapshot, "
                    + "created_at) select ?, ?, ?, ?, ?, ?, ?, ?, ?, " + HASH + ", s, ? from (select "
                    + "memory_operational_snapshot(?) as s) x", id, campaign, revision, state[1], state[2], state[3],
                    state[4], state[5], SNAPSHOT_SCHEMA_VERSION, Timestamp.from(now), campaign);
            return new Capture(Capture.Outcome.CAPTURED, id, revision);
        }));
    }

    /**
     * Verify the newest checkpoint of a campaign against storage and canonical state.
     *
     * @param campaign campaign identity
     * @return NONE without a checkpoint; CORRUPT when the stored snapshot no longer matches its hash;
     *         UNSUPPORTED for another snapshot layout; INCOMPATIBLE when the campaign's pinned content
     *         or ruleset hash differs from the checkpoint's; DIVERGED when the checkpoint is ahead of
     *         the campaign or, at the same revision, differs from canonical state; OUTDATED when it
     *         is valid but older than the current revision; VALID when it equals canonical state now
     * @throws IllegalArgumentException for an unknown campaign
     * @throws IllegalStateException inside an ambient transaction
     */
    public Verification verify(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        requireNoTransaction();
        return Objects.requireNonNull(snapshot.execute(status -> {
            var current = jdbc.query("select c.state_revision, r.content_hash, r.ruleset_hash from campaign c join "
                    + "content_revision r on r.id = c.content_revision_id and r.adventure_id = c.adventure_id "
                    + "where c.id = ?", (row, index) -> new Object[] {row.getLong(1), row.getString(2),
                            row.getString(3)}, campaign);
            if (current.isEmpty()) {
                throw new IllegalArgumentException("unknown campaign");
            }
            long revision = (long) current.getFirst()[0];
            var rows = jdbc.query("select id, state_revision, snapshot_schema_version, content_hash, ruleset_hash, "
                    + "snapshot_hash = (select " + HASH + " from (select snapshot as s) x) as intact, "
                    + "snapshot = memory_operational_snapshot(campaign_id) as equal from campaign_checkpoint "
                    + "where campaign_id = ? order by state_revision desc limit 1", (row, index) -> new Object[] {
                            row.getObject(1, UUID.class), row.getLong(2), row.getInt(3), row.getString(4),
                            row.getString(5), row.getBoolean(6), row.getBoolean(7)}, campaign);
            if (rows.isEmpty()) {
                return new Verification(Verification.Status.NONE, null, null, revision);
            }
            Object[] row = rows.getFirst();
            UUID id = (UUID) row[0];
            long at = (long) row[1];
            Verification.Status result;
            if (!(boolean) row[5]) {
                result = Verification.Status.CORRUPT;
            } else if ((int) row[2] != SNAPSHOT_SCHEMA_VERSION) {
                result = Verification.Status.UNSUPPORTED;
            } else if (!row[3].equals(current.getFirst()[1]) || !row[4].equals(current.getFirst()[2])) {
                result = Verification.Status.INCOMPATIBLE;
            } else if (at > revision || (at == revision && !(boolean) row[6])) {
                result = Verification.Status.DIVERGED;
            } else if (at < revision) {
                result = Verification.Status.OUTDATED;
            } else {
                result = Verification.Status.VALID;
            }
            return new Verification(result, id, at, revision);
        }));
    }

    /**
     * @param campaign campaign identity
     * @return the newest checkpoint, if any; its snapshot is privileged backend data
     */
    public Optional<Checkpoint> latest(UUID campaign) {
        Objects.requireNonNull(campaign, "campaign");
        return jdbc.query("select id, state_revision, event_high_water, snapshot_hash, snapshot::text from "
                + "campaign_checkpoint where campaign_id = ? order by state_revision desc limit 1",
                (row, index) -> new Checkpoint(row.getObject(1, UUID.class), row.getLong(2), row.getLong(3),
                        row.getString(4), row.getString(5)), campaign).stream().findFirst();
    }

    /**
     * Delete all but the newest {@code keep} checkpoints of a campaign.
     *
     * @param campaign campaign identity
     * @param keep checkpoints to keep, at least 1
     * @return number of deleted checkpoints
     */
    public int prune(UUID campaign, int keep) {
        Objects.requireNonNull(campaign, "campaign");
        if (keep < 1) {
            throw new IllegalArgumentException("keep at least one checkpoint");
        }
        return jdbc.update("delete from campaign_checkpoint where campaign_id = ? and id not in (select id from "
                + "campaign_checkpoint where campaign_id = ? order by state_revision desc limit ?)", campaign, campaign,
                keep);
    }

    /** Captures read their own snapshot; joining a caller's transaction would mix its writes in. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("checkpoints require an independent snapshot");
        }
    }

    /**
     * Result of {@link #capture}.
     *
     * @param outcome what happened
     * @param checkpointId new or existing checkpoint of the revision; null when unsafe
     * @param revision the campaign revision considered
     */
    public record Capture(Outcome outcome, UUID checkpointId, long revision) {
        /** Capture outcomes. */
        public enum Outcome {
            /** A new checkpoint was written. */
            CAPTURED,
            /** An identical checkpoint of this revision already exists. */
            EXISTING,
            /** A commit may be in progress; nothing was written. */
            UNSAFE_BOUNDARY,
            /** A stored checkpoint of this revision differs from canonical state; nothing was written. */
            MISMATCH
        }
    }

    /**
     * Result of {@link #verify}.
     *
     * @param status verification status
     * @param checkpointId the newest checkpoint, null when none exists
     * @param checkpointRevision its revision, null when none exists
     * @param currentRevision the campaign revision now
     */
    public record Verification(Status status, UUID checkpointId, Long checkpointRevision, long currentRevision) {
        /** Verification statuses; see {@link OperationalCheckpoints#verify}. */
        public enum Status {
            NONE, CORRUPT, UNSUPPORTED, INCOMPATIBLE, DIVERGED, OUTDATED, VALID
        }
    }

    /**
     * One stored checkpoint.
     *
     * @param id checkpoint identity
     * @param revision state revision it describes
     * @param eventHighWater newest event sequence it covers
     * @param snapshotHash SHA-256 of the canonical jsonb text
     * @param snapshotJson canonical jsonb text of the snapshot
     */
    public record Checkpoint(UUID id, long revision, long eventHighWater, String snapshotHash, String snapshotJson) {
    }
}
