package org.rpgengine.inference.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable record of the {@link InferenceScheduler} (V14 {@code inference_task} and
 * {@code inference_scheduler_state}). The scheduler writes every admission, dispatch and terminal
 * transition here before it takes effect, so quotas, the account backoff and the single-turn
 * permit survive a restart and are shared by every scheduler process using the same database.
 * {@link JdbcTaskLedger} is the production implementation; the contract below is what the
 * scheduler relies on.
 *
 * <p>Ownership: every row carries the identity of the scheduler process that admitted it. Only
 * that owner may dispatch or settle it; {@link #recover} lets a new process take over the rows of
 * earlier processes. Times are supplied by the scheduler's clock.
 *
 * <p>Failures: methods throw {@link RuntimeException} subclasses for storage failures. The scheduler
 * treats a failed admission or dispatch as "nothing was sent" and retries a failed settlement while
 * it keeps the turn permit, so a ledger outage can delay work but never lets two turns overlap.
 */
public interface TaskLedger {
    /** Result of {@link #admit}. */
    enum Admission {
        /** The row exists in QUEUED state. */
        ADMITTED,
        /** A row with this task identity already exists (also after a restart). */
        DUPLICATE,
        /** The campaign does not exist under the scope's adventure. */
        SCOPE_CONFLICT,
        /** The campaign's recovery generation differs from the scope's. */
        STALE_GENERATION
    }

    /** Result of {@link #dispatch}. */
    enum Dispatch {
        /** The row is DISPATCHED with its reservation charged; the caller holds the turn permit. */
        DISPATCHED,
        /** Another task holds the installation's turn permit; the row stays QUEUED. */
        PERMIT_BUSY,
        /** The campaign's recovery generation changed; the row stays QUEUED for the caller to end. */
        STALE_GENERATION,
        /** The row is no longer this process's QUEUED row (taken over or settled elsewhere). */
        FENCED
    }

    /**
     * Admission record of one task.
     *
     * @param task the admitted task (only identities, class, slot and timing are stored)
     * @param instance owning scheduler process
     * @param estimatedUsage reservation units
     * @param enqueuedAt admission time
     * @param deadline end of the task's total budget
     */
    record Entry(ScheduledTask task, UUID instance, long estimatedUsage, Instant enqueuedAt, Instant deadline) {
    }

    /**
     * A row taken over by {@link #recover}: an earlier process dispatched it and its outcome is
     * unknown, so it now holds the permit in RECONCILING state for the new owner.
     *
     * @param taskId task identity
     * @param runtimeReference opaque reference recorded before its runtime start, or null when the
     *        earlier process stopped before the start was recorded (then nothing can be running)
     */
    record Uncertain(UUID taskId, String runtimeReference) {
    }

    /**
     * Outcome of startup reconciliation.
     *
     * @param abandoned QUEUED rows of earlier processes ended ABANDONED (never dispatched)
     * @param uncertain DISPATCHED or RECONCILING rows of earlier processes, now RECONCILING and
     *        owned by the recovering process
     */
    record Recovery(int abandoned, List<Uncertain> uncertain) {
        /** Copy the list. */
        public Recovery {
            uncertain = List.copyOf(uncertain);
        }
    }

    /**
     * Installation-wide account backoff.
     *
     * @param until end of the suspension of background work, or null when none is active
     * @param code sanitized condition code while suspended, else null
     * @param consecutive account conditions since the last completed task, 0..64
     */
    record Suspension(Instant until, String code, int consecutive) {
        /** No suspension and no recent condition. */
        public static final Suspension NONE = new Suspension(null, null, 0);
    }

    /**
     * Insert the QUEUED row of a task, provided its campaign exists under the scope's adventure at
     * the scope's recovery generation.
     *
     * @param entry admission record
     * @return the admission result; nothing is written unless {@link Admission#ADMITTED}
     */
    Admission admit(Entry entry);

    /**
     * Move a QUEUED row of {@code instance} to DISPATCHED and charge its reservation, provided no
     * other row holds the turn permit and the campaign generation is unchanged.
     *
     * @param taskId task identity
     * @param instance owning process
     * @param now dispatch time
     * @return the dispatch result
     */
    Dispatch dispatch(UUID taskId, UUID instance, Instant now);

    /**
     * Record the runtime reference of a DISPATCHED row, once, before its runtime start.
     *
     * @param taskId task identity
     * @param reference opaque runtime reference
     * @return true when recorded; false when the row is not a DISPATCHED row without a reference
     */
    boolean recordReference(UUID taskId, String reference);

    /**
     * Mark a DISPATCHED row as preempted for interactive work (once).
     *
     * @param taskId task identity
     * @param instance owning process
     * @return true when marked
     */
    boolean markPreempted(UUID taskId, UUID instance);

    /**
     * End a QUEUED or DISPATCHED row of {@code instance} with a terminal outcome other than
     * UNRESOLVED, or move a DISPATCHED row to RECONCILING ({@code status = "RECONCILING"}).
     *
     * @param taskId task identity
     * @param instance owning process
     * @param status target status name of the V14 lifecycle
     * @param code fixed outcome code; null exactly for COMPLETED
     * @param keepCharge for a dispatched row: true keeps the reservation charged (a turn may have
     *        run), false releases it (the port reported that no turn started)
     * @param now settlement time
     * @return false when the row is no longer this process's (fenced); nothing is written then
     */
    boolean settle(UUID taskId, UUID instance, String status, String code, boolean keepCharge, Instant now);

    /**
     * End RECONCILING rows of {@code instance} UNRESOLVED once the port confirmed that nothing runs.
     *
     * @param taskIds rows to release
     * @param instance owning process
     * @param now release time
     * @return rows released
     */
    int release(List<UUID> taskIds, UUID instance, Instant now);

    /**
     * Startup reconciliation for a new process: QUEUED rows of other processes end ABANDONED
     * (their callers are gone and their owning jobs re-request them with a fresh context);
     * DISPATCHED and RECONCILING rows of other processes become RECONCILING rows of
     * {@code instance}, keeping their charge.
     *
     * @param instance the recovering process
     * @param now reconciliation time
     * @return what was abandoned and taken over
     */
    Recovery recover(UUID instance, Instant now);

    /**
     * @param since start of the rolling window (exclusive)
     * @param backgroundOnly true to count background classes only
     * @return charged usage units of tasks dispatched after {@code since}
     */
    long usageSince(Instant since, boolean backgroundOnly);

    /** @return the current account backoff state */
    Suspension suspension();

    /**
     * Replace the account backoff state.
     *
     * @param suspension new state
     * @param now update time
     */
    void saveSuspension(Suspension suspension, Instant now);

    /**
     * @param taskId task identity
     * @return the row's V14 status name, if the row exists (diagnostics and tests)
     */
    Optional<String> status(UUID taskId);
}
