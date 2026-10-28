package org.rpgengine.inference.scheduling;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL {@link TaskLedger} over V14 {@code inference_task} and
 * {@code inference_scheduler_state}. Every method is one short READ COMMITTED transaction with a
 * five-second timeout; the V14 guard trigger repeats the lifecycle rules, so a defect here fails
 * loudly instead of corrupting the ledger.
 *
 * <p>The single-turn permit is the V14 partial unique index over DISPATCHED and RECONCILING rows:
 * {@link #dispatch} reports {@link TaskLedger.Dispatch#PERMIT_BUSY} when the index refuses the
 * transition, which is how two scheduler processes sharing one database still run at most one
 * runtime turn. Times are bound as UTC {@link OffsetDateTime} values so the session time zone
 * cannot shift them.
 */
@Component
public class JdbcTaskLedger implements TaskLedger {
    /** Terminal statuses a QUEUED or DISPATCHED row may settle to (UNRESOLVED needs RECONCILING first). */
    private static final Set<String> SETTLEABLE = Set.of("COMPLETED", "CANCELLED", "FAILED", "TIMED_OUT",
            "UNAVAILABLE", "REJECTED", "RECONCILING");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * @param jdbc JDBC access joined to the bounded transactions
     * @param manager shared transaction manager
     */
    public JdbcTaskLedger(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    /** {@inheritDoc} */
    @Override
    public Admission admit(Entry entry) {
        ScheduledTask task = entry.task();
        var scope = task.scope();
        try {
            return transaction.execute(status -> {
                int inserted = jdbc.update("insert into inference_task (id, adventure_id, campaign_id, "
                                + "campaign_generation, role, audience, work_class, slot, status, instance_id, "
                                + "estimated_usage, enqueued_at, deadline_at) "
                                + "select ?, c.adventure_id, c.id, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ? from campaign c "
                                + "where c.id = ? and c.adventure_id = ? and c.recovery_generation = ?",
                        task.taskId(), scope.campaignGeneration(), scope.role().wire(), scope.audience().wire(),
                        task.workClass().name(), task.slot().key(), entry.instance(), entry.estimatedUsage(),
                        time(entry.enqueuedAt()), time(entry.deadline()), scope.campaignId(), scope.adventureId(),
                        scope.campaignGeneration());
                if (inserted == 1) {
                    return Admission.ADMITTED;
                }
                List<Long> generation = jdbc.queryForList("select recovery_generation from campaign "
                        + "where id = ? and adventure_id = ?", Long.class, scope.campaignId(), scope.adventureId());
                return generation.isEmpty() ? Admission.SCOPE_CONFLICT : Admission.STALE_GENERATION;
            });
        } catch (DuplicateKeyException duplicate) {
            return Admission.DUPLICATE;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Dispatch dispatch(UUID taskId, UUID instance, Instant now) {
        try {
            return transaction.execute(status -> {
                int moved = jdbc.update("update inference_task t set status = 'DISPATCHED', dispatched_at = ?, "
                        + "charged_usage = t.estimated_usage, row_version = t.row_version + 1 "
                        + "where t.id = ? and t.status = 'QUEUED' and t.instance_id = ? and exists (select 1 "
                        + "from campaign c where c.id = t.campaign_id and c.recovery_generation = t.campaign_generation)",
                        time(now), taskId, instance);
                if (moved == 1) {
                    return Dispatch.DISPATCHED;
                }
                Boolean ownQueued = jdbc.query("select status = 'QUEUED' and instance_id = ? from inference_task "
                        + "where id = ?", rows -> rows.next() ? rows.getBoolean(1) : Boolean.FALSE, instance, taskId);
                return Boolean.TRUE.equals(ownQueued) ? Dispatch.STALE_GENERATION : Dispatch.FENCED;
            });
        } catch (DuplicateKeyException busy) {
            // The single-turn index refused: another row holds the installation's permit.
            return Dispatch.PERMIT_BUSY;
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean recordReference(UUID taskId, String reference) {
        return Objects.equals(transaction.execute(status -> jdbc.update("update inference_task set runtime_ref = ?, "
                + "row_version = row_version + 1 where id = ? and status = 'DISPATCHED' and runtime_ref is null",
                reference, taskId)), 1);
    }

    /** {@inheritDoc} */
    @Override
    public boolean markPreempted(UUID taskId, UUID instance) {
        return Objects.equals(transaction.execute(status -> jdbc.update("update inference_task set preempted = true, "
                + "row_version = row_version + 1 where id = ? and instance_id = ? and status = 'DISPATCHED' "
                + "and not preempted", taskId, instance)), 1);
    }

    /** {@inheritDoc} */
    @Override
    public boolean settle(UUID taskId, UUID instance, String status, String code, boolean keepCharge, Instant now) {
        if (!SETTLEABLE.contains(status) || (status.equals("COMPLETED")) != (code == null)) {
            throw new IllegalArgumentException("unsupported settlement");
        }
        Integer changed = transaction.execute(tx -> status.equals("RECONCILING")
                ? jdbc.update("update inference_task set status = 'RECONCILING', outcome_code = ?, "
                        + "row_version = row_version + 1 where id = ? and instance_id = ? and status = 'DISPATCHED'",
                        code, taskId, instance)
                : jdbc.update("update inference_task set status = ?, outcome_code = ?, finished_at = ?, "
                        + "charged_usage = case when status = 'DISPATCHED' and ? then charged_usage else 0 end, "
                        + "row_version = row_version + 1 "
                        + "where id = ? and instance_id = ? and status in ('QUEUED', 'DISPATCHED')",
                        status, code, time(now), keepCharge, taskId, instance));
        return Objects.equals(changed, 1);
    }

    /** {@inheritDoc} */
    @Override
    public int release(List<UUID> taskIds, UUID instance, Instant now) {
        Integer released = transaction.execute(status -> {
            int count = 0;
            for (UUID taskId : taskIds) {
                count += jdbc.update("update inference_task set status = 'UNRESOLVED', finished_at = ?, "
                        + "row_version = row_version + 1 where id = ? and instance_id = ? and status = 'RECONCILING'",
                        time(now), taskId, instance);
            }
            return count;
        });
        return released == null ? 0 : released;
    }

    /** {@inheritDoc} */
    @Override
    public Recovery recover(UUID instance, Instant now) {
        return transaction.execute(status -> {
            int abandoned = jdbc.update("update inference_task set status = 'ABANDONED', outcome_code = 'restart', "
                    + "finished_at = ?, row_version = row_version + 1 where status = 'QUEUED' and instance_id <> ?",
                    time(now), instance);
            List<Uncertain> uncertain = jdbc.query("update inference_task set status = 'RECONCILING', "
                    + "outcome_code = coalesce(outcome_code, 'restart'), instance_id = ?, row_version = row_version + 1 "
                    + "where status in ('DISPATCHED', 'RECONCILING') and instance_id <> ? returning id, runtime_ref",
                    (row, index) -> new Uncertain(row.getObject(1, UUID.class), row.getString(2)), instance, instance);
            return new Recovery(abandoned, uncertain);
        });
    }

    /** {@inheritDoc} */
    @Override
    public long usageSince(Instant since, boolean backgroundOnly) {
        Long sum = jdbc.queryForObject("select coalesce(sum(charged_usage), 0) from inference_task "
                + "where charged_usage > 0 and dispatched_at > ?"
                + (backgroundOnly ? " and work_class <> 'INTERACTIVE'" : ""), Long.class, time(since));
        return sum == null ? 0 : sum;
    }

    /** {@inheritDoc} */
    @Override
    public Suspension suspension() {
        List<Suspension> rows = jdbc.query("select suspended_until, suspension_code, consecutive_suspensions "
                + "from inference_scheduler_state where id = 1", (row, index) -> {
                    OffsetDateTime until = row.getObject(1, OffsetDateTime.class);
                    return new Suspension(until == null ? null : until.toInstant(), row.getString(2), row.getInt(3));
                });
        return rows.isEmpty() ? Suspension.NONE : rows.getFirst();
    }

    /** {@inheritDoc} */
    @Override
    public void saveSuspension(Suspension suspension, Instant now) {
        transaction.executeWithoutResult(status -> jdbc.update("insert into inference_scheduler_state (id, "
                        + "suspended_until, suspension_code, consecutive_suspensions, updated_at) values (1, ?, ?, ?, ?) "
                        + "on conflict (id) do update set suspended_until = excluded.suspended_until, "
                        + "suspension_code = excluded.suspension_code, "
                        + "consecutive_suspensions = excluded.consecutive_suspensions, "
                        + "updated_at = excluded.updated_at, row_version = inference_scheduler_state.row_version + 1",
                suspension.until() == null ? null : time(suspension.until()), suspension.code(),
                suspension.consecutive(), time(now)));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> status(UUID taskId) {
        return jdbc.queryForList("select status from inference_task where id = ?", String.class, taskId).stream()
                .findFirst();
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
