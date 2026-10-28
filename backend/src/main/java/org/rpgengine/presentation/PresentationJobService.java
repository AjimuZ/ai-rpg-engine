package org.rpgengine.presentation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.rpgengine.commands.CampaignOperation;
import org.rpgengine.commands.MutationLeaseService;
import org.rpgengine.commands.OperationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable post-commit presentation jobs (P07.5, V11 {@code presentation_job}). A committed
 * operation that requires narration gets exactly one NARRATION job, inserted by the canonical
 * commit transaction, so the owed presentation can be neither lost by a crash nor duplicated by
 * a retry. Workers move it through the journal's presentation states without ever touching world
 * rows or calling the canonical commit service:
 *
 * <ol>
 *   <li><em>Claim</em> (one transaction, campaign then journal then job locks): QUEUED becomes
 *       RUNNING with one more attempt, a fresh fence and a 30-second database-clock lease; the
 *       journal moves COMMITTED to NARRATING.</li>
 *   <li><em>Narrate</em> outside any transaction through {@link NarrationPort}.</li>
 *   <li><em>Settle</em> (one transaction, same locks): only the exact live lease holder may
 *       finish. DELIVERED ends the job and journal DELIVERED; UNAVAILABLE, a cancellation request
 *       or exhausted attempts end the job PENDING and the journal COMMITTED_NARRATION_PENDING; a
 *       transient failure with attempts left requeues the job with backoff and keeps the journal
 *       NARRATING. A late worker (lost lease, newer fence, settled job) writes nothing.</li>
 * </ol>
 *
 * <p>Expired leases are interrupted attempts: the next pass requeues them while attempts remain,
 * otherwise ends them PENDING. A job created under an older campaign recovery generation (a
 * restored save) is retired PENDING. Journal status changes emit stream messages through the V11
 * trigger, so each presentation outcome is announced exactly once. Narration retries never
 * re-run the action, re-roll draws or advance any canonical counter.
 */
@Service
public class PresentationJobService {
    /** Attempts per job, counting interrupted attempts; matches the bounded retry policy of docs/07 §9. */
    public static final int MAX_ATTEMPTS = 3;
    /** Lease of one attempt in whole seconds of database time. */
    public static final int LEASE_SECONDS = 30;
    /** Upper bound of the retry backoff in seconds. */
    static final int MAX_BACKOFF_SECONDS = 30;
    private static final int PAGE = 100;

    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final NarrationPort narration;
    private final TransactionTemplate transaction;

    /**
     * Wire local persistence and the narration boundary; missing dependencies fail startup.
     *
     * @param entities JPA context used for journal transitions
     * @param jdbc JDBC access joined to the bounded transactions
     * @param manager shared transaction manager
     * @param narration narration boundary called outside transactions
     */
    public PresentationJobService(EntityManager entities, JdbcTemplate jdbc, PlatformTransactionManager manager,
            NarrationPort narration) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.narration = Objects.requireNonNull(narration, "narration");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /**
     * Insert the NARRATION job of a committing operation inside the canonical commit transaction,
     * which already holds the campaign and journal locks. The deferred V11 foreign key requires the
     * operation to be committed at exactly {@code committedRevision} when the transaction ends.
     *
     * @throws IllegalStateException if the campaign is not in the adventure's scope
     * @throws org.springframework.dao.DataAccessException for a duplicate job or storage failure
     */
    public static void enqueueWithin(JdbcTemplate jdbc, UUID adventure, UUID campaign, UUID operation,
            long committedRevision) {
        int inserted = jdbc.update("insert into presentation_job (id, adventure_id, campaign_id, campaign_generation, "
                        + "operation_id, committed_revision, kind, status, max_attempts, due_at, created_at, updated_at) "
                        + "select ?, c.adventure_id, c.id, c.recovery_generation, ?, ?, 'NARRATION', 'QUEUED', ?, "
                        + "clock_timestamp(), clock_timestamp(), clock_timestamp() from campaign c "
                        + "where c.id = ? and c.adventure_id = ?",
                UUID.randomUUID(), operation, committedRevision, MAX_ATTEMPTS, campaign, adventure);
        if (inserted != 1) {
            throw new IllegalStateException("presentation job scope mismatch");
        }
    }

    /**
     * Stop optional narration of a committed operation (docs/07 §8, "after canonical commit"),
     * inside a transaction that already holds the campaign and journal locks. A queued job ends
     * PENDING at once and the journal becomes COMMITTED_NARRATION_PENDING; a running job records
     * the request and its worker settles PENDING, discarding any narration. Committed effects are
     * never touched.
     *
     * @return what happened to the presentation work
     */
    public static Stop requestStopWithin(EntityManager entities, JdbcTemplate jdbc, UUID campaign, UUID operation) {
        List<String> found = jdbc.query("select status from presentation_job where campaign_id = ? and operation_id = ? "
                + "and kind = 'NARRATION' for update", (row, index) -> row.getString(1), campaign, operation);
        if (found.isEmpty()) {
            return Stop.NONE;
        }
        switch (found.getFirst()) {
            case "QUEUED" -> {
                jdbc.update("update presentation_job set status = 'PENDING', outcome_code = 'NARRATION_CANCELLED', "
                        + "cancel_requested_at = clock_timestamp(), updated_at = clock_timestamp(), "
                        + "row_version = row_version + 1 where campaign_id = ? and operation_id = ? and kind = 'NARRATION'",
                        campaign, operation);
                settleJournal(entities, operation, OperationStatus.COMMITTED_NARRATION_PENDING);
                return Stop.STOPPED;
            }
            case "RUNNING" -> {
                // Write-once: a repeated request keeps the first timestamp and writes nothing.
                jdbc.update("update presentation_job set cancel_requested_at = clock_timestamp(), "
                        + "updated_at = clock_timestamp(), row_version = row_version + 1 where campaign_id = ? "
                        + "and operation_id = ? and kind = 'NARRATION' and cancel_requested_at is null",
                        campaign, operation);
                return Stop.REQUESTED;
            }
            default -> {
                return Stop.SETTLED;
            }
        }
    }

    /**
     * Process at most {@code limit} jobs: interrupt expired attempts, then claim, narrate and
     * settle due jobs one at a time.
     *
     * @return number of passes that made progress
     * @throws IllegalStateException if called inside an ambient transaction
     */
    public int drain(int limit) {
        int processed = 0;
        while (processed < limit && runOnce()) {
            processed++;
        }
        return processed;
    }

    /**
     * Run one pass: interrupt expired attempts, then claim the oldest due job, call the
     * narration port outside any transaction and settle the result.
     *
     * @return true if a job was claimed or settled, false when nothing was due
     * @throws IllegalStateException if called inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for lock timeouts or storage failures;
     *         the job then stays queued or its lease expires and is interrupted later
     */
    public boolean runOnce() {
        requireNoTransaction();
        boolean progressed = interruptExpired() > 0;
        Candidate candidate = transaction.execute(status -> {
            jdbc.execute("set local statement_timeout = '5s'");
            var due = jdbc.query("select id, adventure_id, campaign_id, operation_id from presentation_job "
                    + "where status = 'QUEUED' and due_at <= clock_timestamp() order by due_at, id limit 1",
                    (row, index) -> new Candidate(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getObject(3, UUID.class), row.getObject(4, UUID.class)));
            return due.isEmpty() ? null : due.getFirst();
        });
        if (candidate == null) {
            return progressed;
        }
        Claim claim = transaction.execute(status -> claim(candidate));
        if (claim == null) {
            // Settled without narration (retired, raced or nothing left to narrate).
            return true;
        }
        NarrationPort.Outcome outcome;
        try {
            outcome = Objects.requireNonNull(narration.narrate(claim.request()), "narration outcome");
        } catch (RuntimeException failure) {
            // Port failures are transient by contract; the bounded attempt budget decides the rest.
            outcome = NarrationPort.Outcome.FAILED;
        }
        NarrationPort.Outcome result = outcome;
        transaction.executeWithoutResult(status -> settle(claim, result));
        return true;
    }

    /**
     * Startup reconciliation before the web server and worker start: retire jobs of older
     * recovery generations and interrupt expired attempts. Queued jobs stay queued so missing
     * narration resumes after a restart; live leases are preserved. Keyset pages of 100, one
     * transaction per job; any failure aborts startup.
     *
     * @return number of jobs changed
     */
    public long reconcile() {
        requireNoTransaction();
        long changed = 0;
        Candidate after = null;
        while (true) {
            Candidate cursor = after;
            List<Candidate> page = transaction.execute(status -> {
                jdbc.execute("set local statement_timeout = '5s'");
                String query = "select id, adventure_id, campaign_id, operation_id from presentation_job "
                        + "where status in ('QUEUED', 'RUNNING') ";
                Object[] arguments = cursor == null ? new Object[0] : new Object[] {cursor.campaign(), cursor.job()};
                if (cursor != null) {
                    query += "and (campaign_id, id) > (?, ?) ";
                }
                return jdbc.query(query + "order by campaign_id, id limit " + PAGE,
                        (row, index) -> new Candidate(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                                row.getObject(3, UUID.class), row.getObject(4, UUID.class)), arguments);
            });
            if (page.isEmpty()) {
                return changed;
            }
            for (Candidate candidate : page) {
                if (Boolean.TRUE.equals(transaction.execute(status -> recover(candidate, true)))) {
                    changed++;
                }
                after = candidate;
            }
        }
    }

    /** Interrupt up to 16 expired attempts, one transaction each; returns how many changed. */
    private int interruptExpired() {
        List<Candidate> expired = transaction.execute(status -> {
            jdbc.execute("set local statement_timeout = '5s'");
            return jdbc.query("select id, adventure_id, campaign_id, operation_id from presentation_job "
                    + "where status = 'RUNNING' and lease_expires_at <= clock_timestamp() "
                    + "order by lease_expires_at, id limit 16",
                    (row, index) -> new Candidate(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getObject(3, UUID.class), row.getObject(4, UUID.class)));
        });
        int changed = 0;
        for (Candidate candidate : expired) {
            if (Boolean.TRUE.equals(transaction.execute(status -> recover(candidate, false)))) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * Re-read one job under the shared locks and retire it if its generation is obsolete, or
     * interrupt it if its lease expired. With {@code startup} false only expiry is handled.
     */
    private boolean recover(Candidate candidate, boolean startup) {
        MutationLeaseService.lockScope(jdbc, candidate.adventure(), candidate.campaign(), candidate.operation());
        Job job = job(candidate.job());
        if (job.terminal()) {
            return false;
        }
        if (job.generation() != job.currentGeneration()) {
            if (!startup && !job.expired()) {
                return false;
            }
            finish(job, "PENDING", "GENERATION_RETIRED", OperationStatus.COMMITTED_NARRATION_PENDING);
            return true;
        }
        if (job.running() && job.expired()) {
            // The attempt was interrupted (crash, stall or lost lease): count it and retry if allowed.
            retryOrExhaust(job);
            return true;
        }
        return false;
    }

    /** Claim one due job under campaign, journal and job locks; null when settled without narration. */
    private Claim claim(Candidate candidate) {
        MutationLeaseService.lockScope(jdbc, candidate.adventure(), candidate.campaign(), candidate.operation());
        Job job = job(candidate.job());
        if (!job.queued() || !job.due()) {
            return null;
        }
        if (job.generation() != job.currentGeneration()) {
            finish(job, "PENDING", "GENERATION_RETIRED", OperationStatus.COMMITTED_NARRATION_PENDING);
            return null;
        }
        CampaignOperation journal = entities.find(CampaignOperation.class, job.operation());
        if (journal.getStatus() != OperationStatus.COMMITTED && journal.getStatus() != OperationStatus.NARRATING) {
            // The journal was settled by another writer; there is nothing left to present.
            finish(job, "PENDING", "NARRATION_FAILED", null);
            return null;
        }
        UUID holder = UUID.randomUUID();
        var claimed = jdbc.queryForObject("update presentation_job set status = 'RUNNING', "
                        + "attempt_count = attempt_count + 1, fence = fence + 1, lease_holder = ?, "
                        + "lease_expires_at = clock_timestamp() + (? * interval '1 second'), "
                        + "updated_at = clock_timestamp(), row_version = row_version + 1 where id = ? "
                        + "returning fence, attempt_count",
                (row, index) -> new long[] {row.getLong(1), row.getLong(2)}, holder, LEASE_SECONDS, job.id());
        if (journal.getStatus() == OperationStatus.COMMITTED) {
            journal.transitionTo(OperationStatus.NARRATING, Instant.now());
            entities.flush();
        }
        return new Claim(job.id(), candidate, holder, claimed[0], new NarrationPort.Request(candidate.adventure(),
                candidate.campaign(), job.operation(), job.revision(), (int) claimed[1]));
    }

    /**
     * Settle one attempt if this worker still holds the exact live lease; otherwise discard the
     * result without writing anything.
     */
    private void settle(Claim claim, NarrationPort.Outcome outcome) {
        var c = claim.candidate();
        MutationLeaseService.lockScope(jdbc, c.adventure(), c.campaign(), c.operation());
        Job job = job(claim.job());
        if (!job.running() || !claim.holder().equals(job.holder()) || job.fence() != claim.fence() || job.expired()) {
            return;
        }
        if (job.generation() != job.currentGeneration()) {
            finish(job, "PENDING", "GENERATION_RETIRED", OperationStatus.COMMITTED_NARRATION_PENDING);
        } else if (job.cancelRequested()) {
            // Cancellation wins over a narration produced meanwhile; that narration is discarded.
            finish(job, "PENDING", "NARRATION_CANCELLED", OperationStatus.COMMITTED_NARRATION_PENDING);
        } else {
            switch (outcome) {
                case DELIVERED -> finish(job, "DELIVERED", "NARRATION_DELIVERED", OperationStatus.DELIVERED);
                case UNAVAILABLE -> finish(job, "PENDING", "NARRATION_UNAVAILABLE",
                        OperationStatus.COMMITTED_NARRATION_PENDING);
                case FAILED -> retryOrExhaust(job);
            }
        }
    }

    /** Requeue an ended attempt with exponential backoff while attempts remain, else end it PENDING. */
    private void retryOrExhaust(Job job) {
        if (job.cancelRequested()) {
            finish(job, "PENDING", "NARRATION_CANCELLED", OperationStatus.COMMITTED_NARRATION_PENDING);
        } else if (job.attempts() < job.maxAttempts()) {
            long backoff = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(job.attempts(), 5));
            jdbc.update("update presentation_job set status = 'QUEUED', lease_holder = null, lease_expires_at = null, "
                    + "fence = fence + 1, due_at = clock_timestamp() + (? * interval '1 second'), "
                    + "updated_at = clock_timestamp(), row_version = row_version + 1 where id = ?", backoff, job.id());
        } else {
            finish(job, "PENDING", "NARRATION_FAILED", OperationStatus.COMMITTED_NARRATION_PENDING);
        }
    }

    /**
     * End a job with a terminal status and outcome, releasing its lease with one fence step if it
     * was running, then move the journal to {@code journalStatus} (null keeps the journal as is).
     */
    private void finish(Job job, String status, String outcome, OperationStatus journalStatus) {
        jdbc.update("update presentation_job set status = ?, outcome_code = ?, lease_holder = null, "
                + "lease_expires_at = null, fence = fence + ?, updated_at = clock_timestamp(), "
                + "row_version = row_version + 1 where id = ?", status, outcome, job.running() ? 1 : 0, job.id());
        if (journalStatus != null) {
            settleJournal(entities, job.operation(), journalStatus);
        }
    }

    /**
     * Move the locked journal row to a presentation outcome unless it is already there. An illegal
     * transition (for example a journal settled by another writer) throws and rolls back.
     */
    private static void settleJournal(EntityManager entities, UUID operation, OperationStatus target) {
        CampaignOperation journal = entities.find(CampaignOperation.class, operation);
        if (journal.getStatus() != target) {
            journal.transitionTo(target, Instant.now());
            entities.flush();
        }
    }

    /** Read one job row, plus the campaign's current generation and database-clock predicates. */
    private Job job(UUID id) {
        return jdbc.queryForObject("select j.id, j.operation_id, j.committed_revision, j.status, j.lease_holder, j.fence, "
                        + "j.attempt_count, j.max_attempts, j.campaign_generation, c.recovery_generation, "
                        + "coalesce(j.lease_expires_at <= clock_timestamp(), false), j.due_at <= clock_timestamp(), "
                        + "j.cancel_requested_at is not null from presentation_job j join campaign c on c.id = j.campaign_id "
                        + "where j.id = ? for update of j",
                (row, index) -> new Job(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getLong(3),
                        row.getString(4), row.getObject(5, UUID.class), row.getLong(6), row.getInt(7), row.getInt(8),
                        row.getLong(9), row.getLong(10), row.getBoolean(11), row.getBoolean(12), row.getBoolean(13)),
                id);
    }

    /** Reject joined caller work: a claim or settlement must be durable before anyone acts on it. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("presentation jobs require no ambient transaction");
        }
    }

    /** Outcome of {@link #requestStopWithin}. */
    public enum Stop {
        /** The operation has no presentation job (legacy commit or narration not required). */
        NONE,
        /** A queued job ended PENDING; the journal is COMMITTED_NARRATION_PENDING. */
        STOPPED,
        /** A running job recorded the request; its worker settles PENDING. */
        REQUESTED,
        /** The job had already ended. */
        SETTLED
    }

    /** Scoped job identity read without locks; every step re-reads it under locks. */
    private record Candidate(UUID job, UUID adventure, UUID campaign, UUID operation) {
    }

    /** Detached claim held by one worker between its claim and settle transactions. */
    private record Claim(UUID job, Candidate candidate, UUID holder, long fence, NarrationPort.Request request) {
    }

    /** Locked job row with its campaign's current generation and clock predicates. */
    private record Job(UUID id, UUID operation, long revision, String status, UUID holder, long fence, int attempts,
            int maxAttempts, long generation, long currentGeneration, boolean expired, boolean due,
            boolean cancelRequested) {
        boolean queued() {
            return status.equals("QUEUED");
        }

        boolean running() {
            return status.equals("RUNNING");
        }

        boolean terminal() {
            return !queued() && !running();
        }
    }
}
