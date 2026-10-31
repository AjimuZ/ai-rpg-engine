package org.rpgengine.inference;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The installation's task port when more than one backend can run: sends each task to the adapter of
 * the backend assigned to its slot (P21.3-B, ADR 0021 decision 2). It chooses only by the owner's
 * assignment and never falls back: a slot whose backend has no adapter ends {@code slot-backend-mismatch}
 * and an unassigned slot ends {@code slot-unassigned}, exactly as an adapter would refuse them, and
 * nothing is sent. Each adapter still resolves the task itself when it dispatches, so an assignment
 * changed meanwhile is refused and never run on the wrong backend.
 *
 * <p>Status: {@link #status()} reports the most usable adapter's state, because a READY adapter can
 * run the tasks of its slots while another one is down, and those others end with that adapter's own
 * UNAVAILABLE result. The counts are summed, so work one adapter has not confirmed keeps the scheduler's
 * permit held. Stateless apart from the fixed routes, and thread-safe when the adapters are.
 */
public final class SlotRoutingTaskPort implements NarrationTaskPort {
    /** Runtime name of the combined status; no slot can be assigned to it. */
    public static final String RUNTIME = "routed";

    private final List<NarrationTaskPort> adapters;
    private final Map<String, NarrationTaskPort> routes = new HashMap<>();
    private final ProfileResolver profiles;

    /**
     * @param adapters every task port of the installation; {@link UnavailableTaskPort} gets no route
     *        and counts only in the status
     * @param profiles names the backend assigned to a task's slot
     * @throws IllegalArgumentException when no adapter is given or two serve the same backend
     */
    public SlotRoutingTaskPort(Collection<NarrationTaskPort> adapters, ProfileResolver profiles) {
        this.adapters = List.copyOf(adapters);
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        if (this.adapters.isEmpty()) {
            throw new IllegalArgumentException("at least one adapter is required");
        }
        for (NarrationTaskPort adapter : this.adapters) {
            if (!(adapter instanceof UnavailableTaskPort)
                    && routes.putIfAbsent(adapter.status().runtime(), adapter) != null) {
                throw new IllegalArgumentException("two adapters serve one backend");
            }
        }
    }

    /** Hand the task to its slot's adapter; see {@link NarrationTaskPort#submit}. */
    @Override
    public TaskHandle submit(TaskRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<String> backend = profiles.assignedBackend(request.slot());
        if (backend.isEmpty()) {
            return refused(request, "slot-unassigned");
        }
        NarrationTaskPort adapter = routes.get(backend.get());
        return adapter == null ? refused(request, "slot-backend-mismatch") : adapter.submit(request);
    }

    private static TaskHandle refused(TaskRequest request, String code) {
        return new CompletedTaskHandle(TaskResult.withoutTurn(request.taskId(), TaskResult.Status.FAILED, code));
    }

    /** Forget the session at every adapter, because a session can have run on several. */
    @Override
    public void retireSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        adapters.forEach(adapter -> adapter.retireSession(sessionId));
    }

    /** @return the most usable adapter's state and reason, with the counts of all adapters */
    @Override
    public RuntimeStatus status() {
        RuntimeStatus best = null;
        int queued = 0;
        int active = 0;
        for (NarrationTaskPort adapter : adapters) {
            RuntimeStatus status = adapter.status();
            queued += status.queued();
            active += status.active();
            if (best == null || usability(status.state()) > usability(best.state())) {
                best = status;
            }
        }
        return new RuntimeStatus(RUNTIME, best.state(), best.reasonCode(), queued, active);
    }

    private static int usability(RuntimeStatus.State state) {
        return switch (state) {
            case READY -> 5;
            case STARTING -> 4;
            case UNAVAILABLE -> 3;
            case STOPPING -> 2;
            case STOPPED -> 1;
            case DISABLED -> 0;
        };
    }

    /** @return the status of the adapter of {@code backend}, or empty when none is installed */
    @Override
    public Optional<RuntimeStatus> statusOf(String backend) {
        Objects.requireNonNull(backend, "backend");
        return Optional.ofNullable(routes.get(backend)).map(NarrationTaskPort::status);
    }

    /** Give every adapter the journal; one that leaves no external work ignores it. */
    @Override
    public void attachJournal(DispatchJournal journal) {
        Objects.requireNonNull(journal, "journal");
        adapters.forEach(adapter -> adapter.attachJournal(journal));
    }

    /** Give every adapter the references; one that leaves no external work ignores them. */
    @Override
    public void reconcile(Collection<String> references) {
        Objects.requireNonNull(references, "references");
        adapters.forEach(adapter -> adapter.reconcile(references));
    }
}
