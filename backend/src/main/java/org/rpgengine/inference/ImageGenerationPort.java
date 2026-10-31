package org.rpgengine.inference;

/**
 * Backend-neutral port of image generation: request, cancel and a validated result with
 * provenance (docs/34 section 8, docs/23). The {@code local-image} worker and the {@code a1111-http}
 * adapter both serve it. P17.6 builds the runtime asset jobs on it; this port holds no job queue of
 * the game, no authority rule and no game state, and an image is never canonical.
 *
 * <p>Contract for every implementation: requests are serialized by the adapter, which owns no more
 * than its own queue; the adapter only cancels its own job; every failure is a fixed code; a completed
 * result carries a PNG that matches the requested size, and its provenance.
 */
public interface ImageGenerationPort {
    /** @return the adapter protocol name, as in {@code SlotBackends.IMAGE_BACKENDS} */
    String backend();

    /**
     * Queue one job. Never blocks on the backend and never throws for a backend condition: a full queue
     * or an unreachable backend ends the returned job {@link ImageResult.Status#FAILED}.
     *
     * @param request what to generate; the only input a backend receives
     * @return the handle of the job
     */
    ImageHandle submit(ImageRequest request);
}
