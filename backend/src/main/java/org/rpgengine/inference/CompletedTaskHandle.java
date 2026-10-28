package org.rpgengine.inference;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Handle for a task that ended at admission (unavailable, rejected); shared by adapters so an
 * immediate outcome looks exactly like an asynchronous one to callers.
 */
public final class CompletedTaskHandle implements TaskHandle {
    private final TaskResult result;

    /** @param result terminal result of the task */
    public CompletedTaskHandle(TaskResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    /** @return identity of the finished task */
    @Override
    public UUID taskId() {
        return result.taskId();
    }

    /** @return an already completed read-only stage */
    @Override
    public CompletionStage<TaskResult> result() {
        return CompletableFuture.completedStage(result);
    }

    /** @return always false: the task has already finished */
    @Override
    public boolean cancel() {
        return false;
    }
}
