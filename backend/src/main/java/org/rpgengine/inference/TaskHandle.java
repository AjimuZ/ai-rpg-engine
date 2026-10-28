package org.rpgengine.inference;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Caller view of one submitted task. The result stage always completes exactly once, with a
 * {@link TaskResult} and never exceptionally, so callers need no runtime-specific error types.
 */
public interface TaskHandle {
    /** @return the request identity */
    UUID taskId();

    /** @return a read-only stage completing with the terminal result */
    CompletionStage<TaskResult> result();

    /**
     * Request cancellation. A queued task completes {@link TaskResult.Status#CANCELLED} at once;
     * a running turn is interrupted and the result reflects the runtime's confirmed terminal
     * status (a turn that completed first still reports COMPLETED). Never blocks.
     *
     * @return false when the task had already finished
     */
    boolean cancel();
}
