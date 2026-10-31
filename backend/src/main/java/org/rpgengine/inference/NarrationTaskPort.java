package org.rpgengine.inference;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Runtime-neutral boundary for every inference task of the engine (ADR 0020 decision 5).
 * Exactly one implementation is the installation's port: the selected runtime's adapter,
 * {@link UnavailableTaskPort} when none is configured, or {@link SlotRoutingTaskPort} in front of the
 * adapters when the local backend is configured too. Domain and application services depend
 * on this interface and the neutral records of this package only.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>{@link #submit} never blocks on inference and never throws for runtime conditions:
 *       admission refusals, unavailability and failures arrive as a {@link TaskResult}. It
 *       throws only for programming errors (null request).</li>
 *   <li>Queues are bounded; when full, a submission ends {@link TaskResult.Status#REJECTED}
 *       with {@code queue-full} instead of waiting.</li>
 *   <li>Settings are explicit on every task: the effective model and effort come from
 *       {@link SlotAssignments} for each task and are never inherited from an earlier one.</li>
 *   <li>An uncertain outcome is reported as UNRESOLVED and reconciled by the adapter before
 *       further work, never retried silently; there is no automatic failover to another
 *       runtime or model (ADR 0020 decision 3). Until the reconciliation succeeds,
 *       {@link RuntimeStatus#active()} counts the unconfirmed work, so the scheduler keeps the
 *       turn permit (P08.6).</li>
 *   <li>Implementations hold no canonical authority and never touch the database.</li>
 *   <li>Engine work reaches the port only through the fair scheduler
 *       ({@code org.rpgengine.inference.scheduling.InferenceScheduler}, P08.6), which forwards at
 *       most its qualified number of tasks at a time (one, P01.7) and keeps the durable ledger;
 *       the port's own queue is therefore a hand-off, not the engine's admission policy.</li>
 * </ul>
 */
public interface NarrationTaskPort {
    /**
     * Admit one task.
     *
     * @param request validated request
     * @return handle whose result stage completes exactly once
     */
    TaskHandle submit(TaskRequest request);

    /**
     * Forget the runtime conversation bound to a logical session, so the next task of that
     * session starts a fresh one (renewal and retirement, ADR 0005). Idempotent; a task already
     * running keeps its conversation until it ends. Engine code reaches this only through
     * {@code org.rpgengine.inference.session.ScopedSessions}, which retires sessions it renews,
     * supersedes or evicts.
     *
     * @param sessionId logical session to retire
     */
    void retireSession(UUID sessionId);

    /** @return current non-sensitive readiness */
    RuntimeStatus status();

    /**
     * Readiness of one backend, for probes of a slot assigned to it.
     *
     * @param backend protocol adapter name
     * @return its status when this port runs that backend, otherwise empty
     */
    default Optional<RuntimeStatus> statusOf(String backend) {
        RuntimeStatus status = status();
        return status.runtime().equals(backend) ? Optional.of(status) : Optional.empty();
    }

    /**
     * Attach the scheduler's write-ahead journal (P08.6). A port that can leave work running in
     * an external runtime records an opaque reference through it before starting each task and
     * starts nothing when the record fails; the latest attached journal wins. Ports without
     * external work (the unavailable port, deterministic doubles) ignore it.
     *
     * @param journal journal to call before every runtime start
     */
    default void attachJournal(DispatchJournal journal) {
        Objects.requireNonNull(journal, "journal");
    }

    /**
     * Treat each reference recorded by an earlier backend process as possibly still running
     * (P08.6 restart reconciliation). Never blocks: before the port starts any further work it
     * must confirm, for every reference, that nothing runs under it any more (interrupting and
     * awaiting the terminal status of anything that does). Until then {@link #status()} reports
     * a non-zero {@code active} count, so the scheduler keeps the turn permit. Unknown references
     * are confirmed idle. Ports without external work ignore the call.
     *
     * @param references opaque references previously given to {@link DispatchJournal#recordStart}
     */
    default void reconcile(Collection<String> references) {
        Objects.requireNonNull(references, "references");
    }
}
