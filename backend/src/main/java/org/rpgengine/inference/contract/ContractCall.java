package org.rpgengine.inference.contract;

import java.util.concurrent.CompletionStage;

/**
 * Caller view of one running contract call. The outcome stage completes exactly once and never
 * exceptionally.
 *
 * @param <T> typed proposal
 */
public interface ContractCall<T> {
    /** @return read-only stage completing with the terminal outcome */
    CompletionStage<ContractOutcome<T>> outcome();

    /**
     * Request cancellation: the running attempt is cancelled through the task port and no repair
     * starts afterwards. Never blocks.
     *
     * @return false when the call had already finished
     */
    boolean cancel();
}
