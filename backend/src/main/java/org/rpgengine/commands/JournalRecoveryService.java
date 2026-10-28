package org.rpgengine.commands;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconcile durable journal evidence after interruption without speculative commits.
 * Scans bounded keyset pages and locks one campaign/journal pair per transaction.
 * Returns the count of changed operations; SQL/consistency failures abort startup
 * rather than reporting readiness with ambiguous recovery. Previously completed
 * rows remain safely reconciled if a later batch fails and startup is retried.
 */
@Service
public class JournalRecoveryService {
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /** Wire only local persistence dependencies; Flyway/JPA must be ready before this service runs. */
    public JournalRecoveryService(EntityManager entities, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /**
     * Scan all currently unfinished/leased operations once, with no ambient transaction.
     * Live mutation permits are preserved, including when another engine starts.
     * Expired/orphaned preparation fails before commit; stored outcomes and draws
     * remain unchanged. Committed operations with a P07.5 presentation job are left to
     * that job's reconciliation, so queued narration resumes after a restart; legacy
     * commits with only an outbox intent, or interrupted narration without a job,
     * become presentation-pending. No worker, permit or external task is resumed here.
     */
    public long reconcile() {
        MutationLeaseService.requireNoTransaction();
        Candidate after = null;
        long changed = 0;
        while (true) {
            Candidate cursor = after;
            List<Candidate> page = transaction.execute(status -> page(cursor));
            if (page.isEmpty()) {
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

    /** Read at most 100 scoped identities with finite SQL time; keyset ordering never skips due to our updates. */
    private List<Candidate> page(Candidate after) {
        jdbc.execute("set local statement_timeout = '5s'");
        String query = "select c.adventure_id, o.campaign_id, o.id from operation_journal o "
                + "join campaign c on c.id = o.campaign_id where (o.status in "
                + "('ACCEPTED', 'PLANNING', 'VALIDATING', 'COMMITTING', 'COMMITTED', 'NARRATING') "
                + "or exists(select 1 from campaign_mutation_lease l "
                + "where l.campaign_id = o.campaign_id and l.operation_id = o.id)) ";
        Object[] arguments = after == null ? new Object[0] : new Object[] {after.campaign(), after.operation()};
        if (after != null) {
            query += "and (o.campaign_id, o.id) > (?, ?) ";
        }
        return jdbc.query(query + "order by o.campaign_id, o.id limit 100",
                (row, index) -> new Candidate(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        row.getObject(3, UUID.class)), arguments);
    }

    /** Re-read evidence under shared locks; all recovery writes roll back together on any failure. */
    private boolean reconcile(Candidate candidate) {
        MutationLeaseService.lockScope(jdbc, candidate.adventure(), candidate.campaign(), candidate.operation());
        // Invalidate expired permits even when their journal already reached a terminal
        // status. Keep the high-water row so old callbacks can never reuse its fence.
        jdbc.update("update campaign_mutation_lease set fence = fence + 1, operation_id = null, "
                        + "holder_id = null, expires_at = null where campaign_id = ? and expires_at <= clock_timestamp()",
                candidate.campaign());
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from campaign_mutation_lease "
                        + "where campaign_id = ? and operation_id = ? and expires_at > clock_timestamp())",
                Boolean.class, candidate.campaign(), candidate.operation()))) {
            return false;
        }
        CampaignOperation operation = entities.find(CampaignOperation.class, candidate.operation());
        // Since P07.5 a committed operation's presentation belongs to its durable presentation job,
        // which resumes or settles it; only legacy commits without a job are marked pending here.
        if (operation.getStatus().isCommitted() && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 "
                        + "from presentation_job where campaign_id = ? and operation_id = ?)", Boolean.class,
                candidate.campaign(), candidate.operation()))) {
            return false;
        }
        boolean narrationRequired = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from canonical_outbox "
                        + "where campaign_id = ? and operation_id = ? and kind = 'NARRATION_REQUIRED')",
                Boolean.class, candidate.campaign(), candidate.operation()));
        Instant now = jdbc.queryForObject("select clock_timestamp()", (row, index) -> row.getTimestamp(1).toInstant());
        return operation.recoverAfterInterruption(narrationRequired, now);
    }

    /** Scoped keyset cursor; the next transaction always re-reads mutable state under locks. */
    private record Candidate(UUID adventure, UUID campaign, UUID operation) {
    }
}
