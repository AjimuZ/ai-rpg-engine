package org.rpgengine.inference.contract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;

/**
 * Deterministic task-port double. Each submission consumes the next scripted step, which either
 * completes immediately from the request or leaves the result pending for the test to complete.
 * Every request and cancellation is recorded; there is no thread, runtime or timing involved.
 */
final class ScriptedTaskPort implements NarrationTaskPort {
    static final TaskResult.Provenance PROVENANCE = new TaskResult.Provenance("scripted", "1.0.0", "fixture-model", "low");

    final List<TaskRequest> requests = new ArrayList<>();
    final List<UUID> cancelled = new ArrayList<>();
    final List<CompletableFuture<TaskResult>> pending = new ArrayList<>();
    private final Deque<Function<TaskRequest, TaskResult>> script = new ArrayDeque<>();

    /** Complete the next submission with a completed turn returning {@code output}. */
    ScriptedTaskPort completes(String output) {
        script.add(request -> new TaskResult(request.taskId(), TaskResult.Status.COMPLETED, output, null, PROVENANCE,
                requests.size() == 1));
        return this;
    }

    /** Complete the next submission with a non-completed status. */
    ScriptedTaskPort ends(TaskResult.Status status, String code) {
        script.add(request -> new TaskResult(request.taskId(), status, null, code,
                status == TaskResult.Status.UNAVAILABLE ? null : PROVENANCE, false));
        return this;
    }

    /** Leave the next submission pending in {@link #pending}. */
    ScriptedTaskPort defers() {
        script.add(request -> null);
        return this;
    }

    @Override
    public synchronized TaskHandle submit(TaskRequest request) {
        requests.add(request);
        Function<TaskRequest, TaskResult> step = script.poll();
        if (step == null) {
            throw new AssertionError("unexpected submission " + requests.size());
        }
        TaskResult immediate = step.apply(request);
        CompletableFuture<TaskResult> future = immediate == null ? new CompletableFuture<>()
                : CompletableFuture.completedFuture(immediate);
        if (immediate == null) {
            pending.add(future);
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
                synchronized (ScriptedTaskPort.this) {
                    cancelled.add(request.taskId());
                }
                return !future.isDone();
            }
        };
    }

    @Override
    public void retireSession(UUID sessionId) {
    }

    @Override
    public RuntimeStatus status() {
        throw new UnsupportedOperationException("not used by the contract runner");
    }
}
