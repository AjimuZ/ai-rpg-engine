package org.rpgengine.commands;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.rpgengine.presentation.PresentationJobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owner cancellation of one operation (P07.5, docs/07 section 8). The race with execution is
 * decided by the campaign lock, which every journal writer takes before the journal row:
 *
 * <ul>
 *   <li>If cancellation obtains the lock while the operation is ACCEPTED, PLANNING or
 *       VALIDATING, the operation becomes CANCELLED and its mutation permit is retired with a
 *       fence step. The executing thread's next prepare, draw or commit then fails its status,
 *       operation-version and lease checks, so no canonical effect can follow. Draws reserved
 *       before the cancellation stay bound to the operation; an equivalent new attempt reuses
 *       their stream (see {@link OperationAdmissionService}).</li>
 *   <li>If the commit transaction obtained the lock first, the action is saved and is never
 *       reported as cancelled. Cancellation then only stops optional narration: a queued
 *       presentation job ends pending at once, a running one finishes pending.</li>
 *   <li>A finished operation is left unchanged.</li>
 * </ul>
 *
 * <p>Repeating a cancellation is idempotent and reports the same outcome. Each call is one
 * bounded READ COMMITTED transaction with the shared 2-second lock and 5-second statement
 * limits; contention surfaces as a {@link org.springframework.dao.TransientDataAccessException}
 * and nothing is written. Status changes emit their stream messages through the V11 trigger.
 */
@Service
public class OperationCancellationService {
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * Wire local persistence only; missing dependencies fail startup.
     *
     * @param entities JPA context for the journal transition
     * @param jdbc JDBC access joined to the same transaction
     * @param manager shared transaction manager
     */
    public OperationCancellationService(EntityManager entities, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    /**
     * Request cancellation of one owned operation.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign campaign that must own the operation
     * @param operation operation identity
     * @return what the cancellation achieved; read the operation status for the current view
     * @throws OperationAdmissionFailure {@code SCOPE_UNAVAILABLE} for missing or foreign identities
     * @throws IllegalStateException if called inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for lock timeouts or storage failures
     */
    public Outcome cancel(UUID owner, UUID adventure, UUID campaign, UUID operation) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(operation, "operation");
        MutationLeaseService.requireNoTransaction();
        return transaction.execute(status -> decide(owner, adventure, campaign, operation));
    }

    /** Transaction body: owner-scoped locks, then one decision; any exception rolls back. */
    private Outcome decide(UUID owner, UUID adventure, UUID campaign, UUID operation) {
        jdbc.execute("set local lock_timeout = '2s'");
        jdbc.execute("set local statement_timeout = '5s'");
        // Lock the campaign through the owner join so a foreign scope is indistinguishable from absence.
        List<UUID> scoped = jdbc.query("select c.id from campaign c join adventure_workspace a on a.id = c.adventure_id "
                        + "where c.id = ? and c.adventure_id = ? and a.owner_id = ? for update of c",
                (row, index) -> row.getObject(1, UUID.class), campaign, adventure, owner);
        List<UUID> journals = scoped.isEmpty() ? List.of() : jdbc.query("select id from operation_journal "
                + "where id = ? and campaign_id = ? for update", (row, index) -> row.getObject(1, UUID.class),
                operation, campaign);
        if (journals.isEmpty()) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE);
        }
        CampaignOperation journal = entities.find(CampaignOperation.class, operation);
        return switch (journal.getStatus()) {
            case ACCEPTED, PLANNING, VALIDATING -> {
                journal.transitionTo(OperationStatus.CANCELLED, Instant.now());
                entities.flush();
                // Fence out the executing worker: it can no longer draw, commit or renew.
                MutationLeaseService.releaseOperation(jdbc, campaign, operation);
                yield Outcome.CANCELLED;
            }
            // COMMITTING exists only inside the commit transaction, which holds the campaign lock.
            case COMMITTING -> throw new IllegalStateException("committing operation observed outside its commit");
            case COMMITTED, NARRATING -> {
                PresentationJobService.requestStopWithin(entities, jdbc, campaign, operation);
                yield Outcome.ALREADY_COMMITTED;
            }
            case DELIVERED, COMMITTED_NARRATION_PENDING -> Outcome.ALREADY_COMMITTED;
            case CANCELLED -> Outcome.CANCELLED;
            case REJECTED, FAILED_BEFORE_COMMIT, NEEDS_CLARIFICATION -> Outcome.ALREADY_FINISHED;
        };
    }

    /** Result of a cancellation request; the HTTP adapter publishes the name as {@code cancellation}. */
    public enum Outcome {
        /** The operation is cancelled before commit (now or by an earlier request); nothing changed. */
        CANCELLED,
        /** Effects were already committed and are preserved; optional narration was stopped. */
        ALREADY_COMMITTED,
        /** The operation had already ended without effects (rejected, failed or clarification). */
        ALREADY_FINISHED
    }
}
