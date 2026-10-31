package org.rpgengine.inference.admission;

/**
 * Work on a resident model that can stop between steps, such as one diffusion step at a time. The
 * {@link LeaseExecutor} checks for more urgent demand between two calls and may never call again, so an
 * abandoned job must leave nothing to clean up beyond its model.
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
}
