package org.rpgengine.preparation;

import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconcile unfinished preparation jobs after a restart or restore (docs/28 sections
 * 9 and 10). Scans bounded keyset pages and handles one job per short transaction.
 * Jobs of a retired campaign generation are superseded; expired leases become
 * reconciliation-required (expiry does not prove that upstream work stopped); queued
 * jobs past their deadline expire. Live leases are left untouched, so a second
 * engine starting against the same database cannot steal running work. Claims and
 * admissions are refused until one full pass succeeds. SQL failures propagate and
 * abort startup; rows already reconciled stay consistent if startup is retried.
 */
@Service
public class JobRecoveryService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    // Set after one complete pass; it is never cleared because later passes only add safety.
    private final AtomicBoolean reconciled = new AtomicBoolean();

    /** Wire local persistence only; Flyway must have migrated V6 before the first pass. */
    public JobRecoveryService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = PreparationSupport.template(manager);
    }

    /** Whether a full reconciliation pass has completed in this process. */
    public boolean isReconciled() {
        return reconciled.get();
    }

    /**
     * Scan every unfinished job once, with no ambient transaction, and return how many
     * rows changed. Contacts no runtime, account or network service and resumes nothing.
     */
    public long reconcile() {
        PreparationSupport.requireNoTransaction();
        Candidate after = null;
        long changed = 0;
        while (true) {
            Candidate cursor = after;
            List<Candidate> page = transaction.execute(status -> page(cursor));
            if (page.isEmpty()) {
                reconciled.set(true);
                return changed;
            }
            for (Candidate candidate : page) {
                if (Boolean.TRUE.equals(transaction.execute(status -> reconcile(candidate)))) {
                    changed++;
                }
                after = candidate;
            }
        }
    }

    /** Read at most 100 unfinished job identities in (campaign, job) order; keyset paging never skips rows. */
    private List<Candidate> page(Candidate after) {
        jdbc.execute("set local statement_timeout = '5s'");
        String query = "select campaign_id, id from ai_job where status not in " + PreparationSupport.TERMINAL_STATES;
        Object[] arguments = after == null ? new Object[0] : new Object[] {after.campaign(), after.job()};
        if (after != null) {
            query += " and (campaign_id, id) > (?, ?)";
        }
        return jdbc.query(query + " order by campaign_id, id limit 100",
                (row, index) -> new Candidate(row.getObject(1, UUID.class), row.getObject(2, UUID.class)), arguments);
    }

    /**
     * Re-read one job under the campaign share lock and its row lock, then apply at
     * most one recovery rule. Returns whether anything changed; all writes of one job
     * commit or roll back together.
     */
    private boolean reconcile(Candidate candidate) {
        PreparationSupport.boundWaits(jdbc);
        // Lock order campaign, then job; a share lock keeps the generation stable.
        jdbc.queryForObject("select id from campaign where id = ? for share", UUID.class, candidate.campaign());
        JobRow row = JobRow.lock(jdbc, candidate.campaign(), candidate.job());
        // A worker can finish after the page scan and before this row lock.
        if (Set.of("COMPLETED", "SUPERSEDED", "CANCELLED", "FAILED", "EXPIRED").contains(row.status())) {
            return false;
        }
        boolean leased = row.holder() != null;
        if (!row.currentGeneration()) {
            // A restore retired this generation: no fence, snapshot or callback from it
            // may ever be admitted, so the job ends regardless of remaining budget.
            if (leased) {
                interrupt(candidate, row, "GENERATION_RETIRED");
            }
            if (!"SUPERSEDED".equals(row.status())) {
                jdbc.update("update ai_job set status = 'SUPERSEDED', failure_code = coalesce(failure_code, "
                        + "'GENERATION_RETIRED'), row_version = row_version + 1, updated_at = clock_timestamp() "
                        + "where id = ?", candidate.job());
            }
            return true;
        }
        if (leased && !row.leaseLive()) {
            interrupt(candidate, row, "LEASE_EXPIRED");
            return true;
        }
        if ("QUEUED".equals(row.status()) && !row.unexpired()) {
            jdbc.update("update ai_job set status = 'EXPIRED', failure_code = 'DEADLINE_EXPIRED', "
                    + "row_version = row_version + 1, updated_at = clock_timestamp() where id = ?", candidate.job());
            return true;
        }
        return false;
    }

    /**
     * Close the active attempt as interrupted and fence the lease out. The job becomes
     * RECONCILIATION_REQUIRED: only an explicit stop confirmation may requeue it.
     */
    private void interrupt(Candidate candidate, JobRow row, String code) {
        jdbc.update("update ai_job_attempt set outcome = 'INTERRUPTED', failure_code = ?, "
                        + "finished_at = clock_timestamp() where job_id = ? and attempt_number = ? and outcome is null",
                code, candidate.job(), row.attempts());
        jdbc.update("update ai_job set status = 'RECONCILIATION_REQUIRED', " + JobRow.RELEASE + " where id = ?",
                candidate.job());
    }

    /** Keyset cursor; each transaction re-reads mutable state under locks. */
    private record Candidate(UUID campaign, UUID job) {
    }
}
