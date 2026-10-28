package org.rpgengine.preparation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.persistence.CanonicalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable preparation job queue (docs/28 sections 5 and 9): deduplicated enqueue,
 * {@code FOR UPDATE SKIP LOCKED} claims under an increasing fence, per-attempt
 * snapshot binding, results, cancellation, retries and explicit stop confirmation.
 * Every lease-bound call re-checks holder, fence, attempt, expiry and campaign
 * generation under the job row lock, so an expired or superseded worker can never
 * write. This class stores coordination state only: it starts no runtime, spends no
 * usage and never touches canonical world state. Runtime dispatch arrives at P08.6.
 */
@Service
public class PreparationJobService {
    private static final Set<String> CLAIMED = Set.of("LEASED", "RUNNING");
    private static final Set<String> HEARTBEAT = Set.of("LEASED", "RUNNING", "CANCEL_REQUESTED", "RESULT_RECEIVED");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JobRecoveryService recovery;

    /** Wire local persistence and the startup reconciliation gate. */
    public PreparationJobService(JdbcTemplate jdbc, PlatformTransactionManager manager, JobRecoveryService recovery) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = PreparationSupport.template(manager);
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    /**
     * Enqueue a non-critical job in its own transaction. Critical jobs must be created
     * by their originating canonical commit through {@link #enqueueWithin}.
     *
     * @return the new or already existing job for the same deduplication identity
     * @throws IllegalArgumentException for critical requests or unknown campaigns
     * @throws IllegalStateException when an identical identity carries other hashes
     */
    public Enqueued enqueue(UUID adventure, UUID campaign, JobRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.critical()) {
            throw new IllegalArgumentException("critical jobs are created by their canonical commit");
        }
        PreparationSupport.requireNoTransaction();
        return transaction.execute(status -> {
            PreparationSupport.boundWaits(jdbc);
            return enqueueWithin(jdbc, adventure, campaign, null, request);
        });
    }

    /**
     * Insert a job inside the caller's transaction, so it commits or rolls back with
     * the canonical change that requested it (the transactional outbox intent). The
     * deduplication key binds adventure, campaign, recovery generation, kind, target,
     * purpose, contract version, dependency fingerprint and policy hash; a retried or
     * concurrent identical request waits on the unique key and then finds the winner.
     * Locale, glossary and media revisions are copied from the presentation settings.
     *
     * @param originOperation canonical operation that requested the job; required when critical
     * @throws IllegalArgumentException for unknown or foreign campaigns or a critical job without origin
     * @throws IllegalStateException when the same identity was enqueued with other payload/schema hashes
     */
    public static Enqueued enqueueWithin(JdbcTemplate jdbc, UUID adventure, UUID campaign, UUID originOperation,
            JobRequest request) {
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(request, "request");
        if (request.critical() && originOperation == null) {
            throw new IllegalArgumentException("critical jobs require an origin operation");
        }
        PreparationSupport.requireTransaction();
        // The share lock keeps the generation stable until commit; a canonical
        // transaction already holding the campaign row lock passes straight through.
        var generations = jdbc.queryForList(
                "select recovery_generation from campaign where id = ? and adventure_id = ? for share", Long.class,
                campaign, adventure);
        if (generations.isEmpty()) {
            throw new IllegalArgumentException("unknown campaign");
        }
        long generation = generations.getFirst();
        String dedup = PreparationSupport.sha256(adventure, campaign, generation, request.kind(), request.target(),
                request.purpose(), request.contractVersion(), request.dependencyFingerprint(), request.policyHash());
        var created = jdbc.queryForList("insert into ai_job (id, adventure_id, campaign_id, campaign_generation, kind, "
                        + "target, purpose, contract_version, dependency_fingerprint, dedup_key, priority, critical, status, "
                        + "payload_hash, schema_hash, policy_hash, origin_operation_id, locale_revision, glossary_revision, "
                        + "media_configuration_generation, due_at, expires_at, max_attempts, estimated_usage, "
                        + "reserved_usage, created_at, updated_at) "
                        + "select ?, c.adventure_id, c.id, c.recovery_generation, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, "
                        + "?, coalesce(p.locale_revision, 0), coalesce(p.glossary_revision, 0), "
                        + "coalesce(p.media_configuration_generation, 0), t.now + make_interval(secs => ?), "
                        + "t.now + make_interval(secs => ?), ?, ?, ?, t.now, t.now "
                        + "from campaign c left join presentation_campaign_settings p on p.campaign_id = c.id "
                        + "cross join (select clock_timestamp() as now) t where c.id = ? "
                        + "on conflict (campaign_id, dedup_key) do nothing returning id", UUID.class,
                UUID.randomUUID(), request.kind(), request.target(), request.purpose(), request.contractVersion(),
                request.dependencyFingerprint(), dedup, request.priority(), request.critical(), request.payloadHash(),
                request.schemaHash(), request.policyHash(), originOperation, (double) request.delay().getSeconds(),
                (double) (request.delay().getSeconds() + request.timeToLive().getSeconds()), request.maxAttempts(),
                request.estimatedUsage(), request.reservedUsage(), campaign);
        if (!created.isEmpty()) {
            return new Enqueued(created.getFirst(), true);
        }
        // The conflicting row is committed (or ours): READ COMMITTED sees it in this new statement.
        // A proactive request cannot silently satisfy a critical command's intent
        // without its originating receipt, nor can a second command steal that origin.
        var existing = jdbc.query("select id, payload_hash = ? and schema_hash = ? and critical = ? "
                        + "and origin_operation_id is not distinct from ?::uuid from ai_job "
                        + "where campaign_id = ? and dedup_key = ?",
                (row, index) -> new Enqueued(row.getObject(1, UUID.class), row.getBoolean(2)), request.payloadHash(),
                request.schemaHash(), request.critical(), originOperation, campaign, dedup);
        if (existing.isEmpty() || !existing.getFirst().created()) {
            throw new IllegalStateException("conflicting job request");
        }
        return new Enqueued(existing.getFirst().jobId(), false);
    }

    /**
     * Lease the highest-priority due job of a current campaign generation, skipping
     * rows other claimers hold, and open its attempt record. The fence and attempt
     * count advance by exactly one in the same statement (V6 {@code ai_job_fence}).
     *
     * @param holder opaque worker identity owning the lease
     * @param duration whole-second lease length, 1..300
     * @return the lease, or empty when nothing is claimable
     * @throws IllegalStateException before startup reconciliation completed
     */
    public Optional<Lease> claim(UUID holder, Duration duration) {
        Objects.requireNonNull(holder, "holder");
        long seconds = PreparationSupport.leaseSeconds(duration);
        PreparationSupport.requireNoTransaction();
        // docs/28 section 9: no new work starts until stale leases are reconciled.
        if (!recovery.isReconciled()) {
            throw new IllegalStateException("job recovery has not completed");
        }
        return Optional.ofNullable(transaction.execute(status -> {
            PreparationSupport.boundWaits(jdbc);
            // Select a campaign first, then share-lock it before locking any job. This
            // preserves the global lock order and makes restore wait until claim commits.
            // A competing claimer may consume the candidate; an empty poll is safe.
            var candidates = jdbc.queryForList("select j.campaign_id from ai_job j join campaign c "
                    + "on c.id = j.campaign_id where j.status = 'QUEUED' and j.due_at <= clock_timestamp() "
                    + "and j.expires_at > clock_timestamp() and j.campaign_generation = c.recovery_generation "
                    + "and j.attempt_count < j.max_attempts order by j.priority desc, j.due_at, j.id limit 1", UUID.class);
            if (candidates.isEmpty()) {
                return null;
            }
            UUID campaign = candidates.getFirst();
            if (jdbc.queryForList("select id from campaign where id = ? for share skip locked",
                    UUID.class, campaign).isEmpty()) {
                return null;
            }
            var leases = jdbc.query("with candidate as (select j.id from ai_job j join campaign c "
                            + "on c.id = j.campaign_id where j.campaign_id = ? and j.status = 'QUEUED' and j.due_at <= clock_timestamp() "
                            + "and j.expires_at > clock_timestamp() and j.campaign_generation = c.recovery_generation "
                            + "and j.attempt_count < j.max_attempts order by j.priority desc, j.due_at, j.id limit 1 "
                            + "for update of j skip locked) "
                            + "update ai_job j set status = 'LEASED', fence = j.fence + 1, "
                            + "attempt_count = j.attempt_count + 1, lease_holder = ?, "
                            + "lease_expires_at = clock_timestamp() + make_interval(secs => ?), "
                            + "row_version = j.row_version + 1, updated_at = clock_timestamp() "
                            + "from candidate where j.id = candidate.id "
                            + "returning j.id, j.campaign_id, j.fence, j.attempt_count",
                    (row, index) -> new Lease(row.getObject(1, UUID.class), row.getObject(2, UUID.class), holder,
                            row.getLong(3), row.getInt(4)), campaign, holder, (double) seconds);
            if (leases.isEmpty()) {
                return null;
            }
            Lease lease = leases.getFirst();
            jdbc.update("insert into ai_job_attempt (job_id, attempt_number, campaign_id, fence, lease_holder, "
                            + "started_at) values (?, ?, ?, ?, ?, clock_timestamp())", lease.jobId(), lease.attempt(),
                    lease.campaignId(), lease.fence(), holder);
            return lease;
        }));
    }

    /**
     * Mark the leased attempt as started and record the external runtime references
     * (docs/28 section 9: generation, thread and turn are evidence for later
     * reconciliation). Extends the lease by {@code duration}.
     *
     * @throws IllegalStateException when the lease is stale or the job is not LEASED
     */
    public void start(Lease lease, Duration duration, long runtimeGeneration, String threadRef, String turnRef) {
        Objects.requireNonNull(lease, "lease");
        long seconds = PreparationSupport.leaseSeconds(duration);
        CanonicalValues.requireNonNegative(runtimeGeneration, "runtimeGeneration");
        CanonicalValues.requireText(threadRef, "threadRef", 128);
        CanonicalValues.requireText(turnRef, "turnRef", 128);
        inTransaction(() -> {
            JobRow.requireLease(jdbc, lease, Set.of("LEASED"));
            jdbc.update("update ai_job_attempt set runtime_generation = ?, runtime_thread_ref = ?, runtime_turn_ref = ? "
                            + "where job_id = ? and attempt_number = ?", runtimeGeneration, threadRef, turnRef,
                    lease.jobId(), lease.attempt());
            jdbc.update("update ai_job set status = 'RUNNING', lease_expires_at = clock_timestamp() "
                    + "+ make_interval(secs => ?), row_version = row_version + 1, updated_at = clock_timestamp() "
                    + "where id = ?", (double) seconds, lease.jobId());
        });
    }

    /**
     * Extend a live lease from now; an expired lease cannot be revived because recovery
     * may already have fenced it out.
     *
     * @throws IllegalStateException when the lease is stale or expired
     */
    public void heartbeat(Lease lease, Duration duration) {
        Objects.requireNonNull(lease, "lease");
        long seconds = PreparationSupport.leaseSeconds(duration);
        inTransaction(() -> {
            JobRow.requireLease(jdbc, lease, HEARTBEAT);
            jdbc.update("update ai_job set lease_expires_at = clock_timestamp() + make_interval(secs => ?), "
                    + "row_version = row_version + 1, updated_at = clock_timestamp() where id = ?",
                    (double) seconds, lease.jobId());
        });
    }

    /**
     * Bind an immutable planning snapshot to the active attempt (and as the job's
     * current snapshot). The snapshot must belong to the job's campaign and current
     * generation and carry the job's policy hash; an attempt binds at most one.
     *
     * @throws IllegalArgumentException for an unknown or foreign snapshot
     * @throws IllegalStateException for stale leases, generations, policies or a second binding
     */
    public void bindSnapshot(Lease lease, UUID snapshot) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(snapshot, "snapshot");
        inTransaction(() -> {
            JobRow row = JobRow.requireLease(jdbc, lease, CLAIMED);
            var checks = jdbc.query("select s.campaign_generation = c.recovery_generation, s.policy_hash = ? "
                            + "from planning_snapshot s join campaign c on c.id = s.campaign_id "
                            + "where s.id = ? and s.campaign_id = ?",
                    (result, index) -> new boolean[] {result.getBoolean(1), result.getBoolean(2)}, row.policyHash(),
                    snapshot, lease.campaignId());
            if (checks.isEmpty()) {
                throw new IllegalArgumentException("unknown snapshot");
            }
            if (!checks.getFirst()[0] || !checks.getFirst()[1]) {
                throw new IllegalStateException("snapshot does not match the job");
            }
            UUID bound = jdbc.queryForObject("select snapshot_id from ai_job_attempt where job_id = ? "
                    + "and attempt_number = ?", UUID.class, lease.jobId(), lease.attempt());
            if (bound != null && !bound.equals(snapshot)) {
                throw new IllegalStateException("attempt snapshot already bound");
            }
            jdbc.update("update ai_job_attempt set snapshot_id = ? where job_id = ? and attempt_number = ?", snapshot,
                    lease.jobId(), lease.attempt());
            jdbc.update("update ai_job set snapshot_id = ?, row_version = row_version + 1, "
                    + "updated_at = clock_timestamp() where id = ?", snapshot, lease.jobId());
        });
    }

    /**
     * Record the digest of the running attempt's bounded result. A duplicate delivery of
     * the same digest is a no-op; another digest for the same attempt is rejected, so
     * one attempt can only ever be admitted with one result.
     *
     * @throws IllegalStateException for stale leases, a missing snapshot binding or a conflicting digest
     */
    public void recordResult(Lease lease, String resultDigest, long observedUsage) {
        Objects.requireNonNull(lease, "lease");
        CanonicalValues.requireSha256Hex(resultDigest, "resultDigest");
        CanonicalValues.requireNonNegative(observedUsage, "observedUsage");
        inTransaction(() -> {
            JobRow row = JobRow.requireLease(jdbc, lease, Set.of("RUNNING", "RESULT_RECEIVED"));
            var attempt = jdbc.queryForMap("select snapshot_id, result_digest from ai_job_attempt "
                    + "where job_id = ? and attempt_number = ?", lease.jobId(), lease.attempt());
            if ("RESULT_RECEIVED".equals(row.status())) {
                if (!resultDigest.equals(attempt.get("result_digest"))) {
                    throw new IllegalStateException("conflicting job result");
                }
                return;
            }
            if (attempt.get("snapshot_id") == null) {
                throw new IllegalStateException("job result requires a bound snapshot");
            }
            jdbc.update("update ai_job_attempt set result_digest = ?, observed_usage = ? "
                    + "where job_id = ? and attempt_number = ?", resultDigest, observedUsage, lease.jobId(),
                    lease.attempt());
            jdbc.update("update ai_job set status = 'RESULT_RECEIVED', observed_usage = observed_usage + ?, "
                    + "row_version = row_version + 1, updated_at = clock_timestamp() where id = ?", observedUsage,
                    lease.jobId());
        });
    }

    /**
     * Request cancellation by job identity (no lease needed). A queued job is cancelled
     * at once; a leased one becomes CANCEL_REQUESTED until its holder confirms the stop;
     * a job awaiting reconciliation keeps the request for {@link #confirmStopped}.
     * Terminal jobs are left unchanged.
     *
     * @return the job's status after the call
     * @throws IllegalArgumentException for an unknown or foreign job
     */
    public JobStatus requestCancel(UUID campaign, UUID job) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(job, "job");
        PreparationSupport.requireNoTransaction();
        return transaction.execute(status -> {
            PreparationSupport.boundWaits(jdbc);
            JobRow row = JobRow.lock(jdbc, campaign, job);
            String next = switch (row.status()) {
                case "QUEUED" -> "CANCELLED";
                case "LEASED", "RUNNING", "RESULT_RECEIVED" -> "CANCEL_REQUESTED";
                case "CANCEL_REQUESTED", "RECONCILIATION_REQUIRED" -> row.status();
                default -> null;
            };
            if (next == null) {
                return JobStatus.valueOf(row.status());
            }
            // coalesce keeps the first request time: the column is write-once.
            jdbc.update("update ai_job set status = ?, cancel_requested_at = coalesce(cancel_requested_at, "
                    + "clock_timestamp()), row_version = row_version + 1, updated_at = clock_timestamp() "
                    + "where id = ?", next, job);
            return JobStatus.valueOf(next);
        });
    }

    /**
     * The holder confirms that its cancelled work stopped: the attempt and the job end
     * as CANCELLED and the lease is fenced out.
     *
     * @throws IllegalStateException when the lease is stale or cancellation was not requested
     */
    public void confirmCancelled(Lease lease) {
        Objects.requireNonNull(lease, "lease");
        inTransaction(() -> {
            JobRow.requireLease(jdbc, lease, Set.of("CANCEL_REQUESTED"));
            finishAttempt(lease, "CANCELLED", null);
            jdbc.update("update ai_job set status = 'CANCELLED', " + JobRow.RELEASE + " where id = ?", lease.jobId());
        });
    }

    /**
     * Record a failed attempt. A retryable failure requeues the job while attempts and
     * deadline remain (the attempt count is never reset); otherwise the job fails with
     * {@code code}. Usage accounting and backoff arrive at P08.6.
     *
     * @return QUEUED or FAILED
     * @throws IllegalStateException when the lease is stale
     */
    public JobStatus fail(Lease lease, String code, boolean retryable) {
        Objects.requireNonNull(lease, "lease");
        PreparationSupport.require(PreparationSupport.CODE, code, "code");
        PreparationSupport.requireNoTransaction();
        return transaction.execute(status -> {
            PreparationSupport.boundWaits(jdbc);
            JobRow row = JobRow.requireLease(jdbc, lease, CLAIMED);
            finishAttempt(lease, "FAILED", code);
            if (retryable && row.retryable()) {
                jdbc.update("update ai_job set status = 'QUEUED', " + JobRow.RELEASE + " where id = ?", lease.jobId());
                return JobStatus.QUEUED;
            }
            jdbc.update("update ai_job set status = 'FAILED', failure_code = ?, " + JobRow.RELEASE + " where id = ?",
                    code, lease.jobId());
            return JobStatus.FAILED;
        });
    }

    /**
     * Resolve a RECONCILIATION_REQUIRED job after the caller proved that the upstream
     * work it lost track of has stopped (docs/28 section 9). Retired generations are
     * superseded, cancelled requests cancelled, past deadlines expired; otherwise the
     * job is requeued while attempts remain and fails with ATTEMPTS_EXHAUSTED after.
     *
     * @return the resulting status
     * @throws IllegalArgumentException for an unknown or foreign job
     * @throws IllegalStateException when the job does not require reconciliation
     */
    public JobStatus confirmStopped(UUID campaign, UUID job) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(job, "job");
        PreparationSupport.requireNoTransaction();
        return transaction.execute(status -> {
            PreparationSupport.boundWaits(jdbc);
            jdbc.queryForObject("select id from campaign where id = ? for share", UUID.class, campaign);
            JobRow row = JobRow.lock(jdbc, campaign, job);
            if (!"RECONCILIATION_REQUIRED".equals(row.status())) {
                throw new IllegalStateException("job does not require reconciliation");
            }
            JobStatus next;
            String code = null;
            if (!row.currentGeneration()) {
                next = JobStatus.SUPERSEDED;
                code = "GENERATION_RETIRED";
            } else if (row.cancelRequested()) {
                next = JobStatus.CANCELLED;
            } else if (!row.unexpired()) {
                next = JobStatus.EXPIRED;
                code = "DEADLINE_EXPIRED";
            } else if (row.retryable()) {
                next = JobStatus.QUEUED;
            } else {
                next = JobStatus.FAILED;
                code = "ATTEMPTS_EXHAUSTED";
            }
            jdbc.update("update ai_job set status = ?, failure_code = coalesce(failure_code, ?), "
                    + "row_version = row_version + 1, updated_at = clock_timestamp() where id = ?", next.name(), code,
                    job);
            return next;
        });
    }

    /** Close the lease's attempt once; the write-once trigger rejects a second outcome. */
    private void finishAttempt(Lease lease, String outcome, String code) {
        jdbc.update("update ai_job_attempt set outcome = ?, failure_code = ?, finished_at = clock_timestamp() "
                + "where job_id = ? and attempt_number = ? and outcome is null", outcome, code, lease.jobId(),
                lease.attempt());
    }

    /** Run one bounded write transaction; rejects an ambient transaction first. */
    private void inTransaction(Runnable work) {
        PreparationSupport.requireNoTransaction();
        transaction.executeWithoutResult(status -> {
            PreparationSupport.boundWaits(jdbc);
            work.run();
        });
    }

    /**
     * Result of an enqueue.
     *
     * @param jobId durable job identity
     * @param created whether this call inserted it (false for a deduplicated request)
     */
    public record Enqueued(UUID jobId, boolean created) {
    }

    /**
     * Fenced lease token. Valid only while holder, fence and attempt all match the row,
     * the lease is unexpired and the campaign generation is unchanged.
     *
     * @param jobId leased job
     * @param campaignId owning campaign (lock and ownership scope)
     * @param holder worker identity
     * @param fence fence value granted by the claim
     * @param attempt attempt number opened by the claim
     */
    public record Lease(UUID jobId, UUID campaignId, UUID holder, long fence, int attempt) {
        /** Reject incomplete tokens. */
        public Lease {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(campaignId, "campaignId");
            Objects.requireNonNull(holder, "holder");
            CanonicalValues.requirePositive(fence, "fence");
            CanonicalValues.requirePositive(attempt, "attempt");
        }
    }
}
