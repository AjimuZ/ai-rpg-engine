package org.rpgengine.inference;

import java.util.UUID;

/**
 * Runtime-neutral boundary for every inference task of the engine (ADR 0020 decision 5).
 * Exactly one implementation is active per installation: the selected runtime's adapter, or
 * {@link UnavailableTaskPort} when none is configured. Domain and application services depend
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
 *       runtime or model (ADR 0020 decision 3).</li>
 *   <li>Implementations hold no canonical authority and never touch the database.</li>
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
     * running keeps its conversation until it ends.
     *
     * @param sessionId logical session to retire
     */
    void retireSession(UUID sessionId);

    /** @return current non-sensitive readiness */
    RuntimeStatus status();
}
