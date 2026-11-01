package org.rpgengine.inference.admission;

/**
 * Work on a resident model that can stop between steps, such as one diffusion step at a time. The
 * {@link LeaseExecutor} checks for more urgent demand between two calls and may never call again, so an
 * abandoned job must leave nothing to clean up beyond its model and what {@link #abandoned} stops.
 */
@FunctionalInterface
public interface SteppedJob {
    /**
     * Run one step.
     *
     * @return true when more steps remain
     * @throws Exception when the step failed; the job ends and its lease is released
     */
    boolean step() throws Exception;

    /**
     * The executor stopped the job before its last step, or a step threw, and will not call {@link #step} again.
     * Called once on the steps' thread, with its interrupt flag down, and before the job's model is unloaded, so that
     * work still running on the server can stop first. It should be bounded, because the model waits for it. A failure
     * here is ignored, as the model must leave memory regardless.
     */
    default void abandoned() {
    }
}
