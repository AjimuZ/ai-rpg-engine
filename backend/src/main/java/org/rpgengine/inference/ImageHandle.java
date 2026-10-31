package org.rpgengine.inference;

import java.util.concurrent.CompletionStage;

/**
 * Caller view of one submitted image job. The result stage completes exactly once with an
 * {@link ImageResult} and never exceptionally, so callers need no adapter-specific error types.
 */
public interface ImageHandle {
    /** @return a read-only stage completing with the terminal result */
    CompletionStage<ImageResult> result();

    /**
     * Request cancellation without blocking. A queued job ends {@link ImageResult.Status#CANCELLED}
     * at once. A running job is interrupted on the backend, and its result is
     * {@code CANCELLED} even if the backend still returned an image, which is dropped.
     *
     * @return false when the job had already finished
     */
    boolean cancel();
}
