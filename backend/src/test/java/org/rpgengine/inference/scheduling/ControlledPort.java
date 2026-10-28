package org.rpgengine.inference.scheduling;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.rpgengine.inference.DispatchJournal;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;

/**
 * Task-port double that behaves like a runtime adapter from the scheduler's point of view: every
 * submission records a runtime reference through the attached journal first (and fails without a
 * turn when the journal refuses), stays running until the test completes it, and reports
 * readiness, activity and restart reconciliation as the test scripts them. A cancellation either
 * confirms an interrupted turn at once ({@link #autoInterrupt}) or waits for the test.
 */
final class ControlledPort implements NarrationTaskPort {
    static final TaskResult.Provenance PROVENANCE = new TaskResult.Provenance("scripted", "1.0.0", "model", "low");

    final List<TaskRequest> requests = new CopyOnWriteArrayList<>();
    final List<UUID> cancelled = new CopyOnWriteArrayList<>();
    final List<List<String>> reconciled = new CopyOnWriteArrayList<>();
    final Map<UUID, CompletableFuture<TaskResult>> running = new ConcurrentHashMap<>();
    volatile boolean autoInterrupt = true;
    volatile RuntimeStatus status = ready(0);
    private volatile DispatchJournal journal = DispatchJournal.NONE;
    private int references;

    static RuntimeStatus ready(int active) {
        return new RuntimeStatus("scripted", RuntimeStatus.State.READY, null, 0, active);
    }

    @Override
    public synchronized TaskHandle submit(TaskRequest request) {
        requests.add(request);
        var future = new CompletableFuture<TaskResult>();
        if (!journal.recordStart(request.taskId(), "thread-" + ++references)) {
            future.complete(TaskResult.withoutTurn(request.taskId(), TaskResult.Status.FAILED,
                    "dispatch-journal-unavailable"));
        } else {
            running.put(request.taskId(), future);
        }
        return new TaskHandle() {
            @Override
            public UUID taskId() {
                return request.taskId();
            }

            @Override
            public CompletionStage<TaskResult> result() {
                return future.minimalCompletionStage();
            }

            @Override
            public boolean cancel() {
                cancelled.add(request.taskId());
                if (autoInterrupt) {
                    future.complete(new TaskResult(request.taskId(), TaskResult.Status.CANCELLED, null, "cancelled",
                            PROVENANCE, false));
                }
                return !future.isDone();
            }
        };
    }

    /** Complete a running task with a turn (provenance present). */
    void complete(UUID taskId, TaskResult.Status status, String code) {
        running.get(taskId).complete(new TaskResult(taskId, status, status == TaskResult.Status.COMPLETED ? "{}" : null,
                status == TaskResult.Status.COMPLETED ? null : code, PROVENANCE, false));
    }

    /** Complete a running task as one that never started a runtime turn (no provenance). */
    void completeWithoutTurn(UUID taskId, TaskResult.Status status, String code) {
        running.get(taskId).complete(TaskResult.withoutTurn(taskId, status, code));
    }

    @Override
    public void retireSession(UUID sessionId) {
    }

    @Override
    public RuntimeStatus status() {
        return status;
    }

    @Override
    public void attachJournal(DispatchJournal journal) {
        this.journal = journal;
    }

    /** Like the adapter: the handed-over references count as active until the test confirms them. */
    @Override
    public void reconcile(Collection<String> references) {
        reconciled.add(new ArrayList<>(references));
        status = ready(1);
    }
}
