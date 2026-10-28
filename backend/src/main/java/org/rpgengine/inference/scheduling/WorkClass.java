package org.rpgengine.inference.scheduling;

/**
 * Priority classes of scheduled inference work, highest first (docs/28 section 9). The class is a
 * property of the task kind, never of the caller's urgency: interactive work is what a player is
 * waiting for, every other class is background work that may be delayed, refused or preempted.
 *
 * <p>Order is strict between interactive and background work: background work never ages above
 * {@link #INTERACTIVE}. Within the background classes a waiting task gains one class per
 * {@link SchedulerSettings#agingStep()} of waiting, up to {@link #CONTINUITY}, so a steady stream
 * of preparation work cannot starve speculative work forever.
 */
public enum WorkClass {
    /** Intent interpretation, resolved narration and immediate legal-tactic choices of a player turn. */
    INTERACTIVE(0),
    /** Required bounded continuity work, such as summaries the next turn depends on. */
    CONTINUITY(1),
    /** Preparation or refinement for an accepted quest. */
    PREPARATION(2),
    /** Speculative preparation, world direction and plan review; the first work to be refused. */
    SPECULATIVE(3);

    private final int rank;

    /** @param rank priority rank, 0 is served first */
    WorkClass(int rank) {
        this.rank = rank;
    }

    /** @return priority rank; a lower rank is served first */
    public int rank() {
        return rank;
    }

    /** @return true for every class except {@link #INTERACTIVE} */
    public boolean background() {
        return this != INTERACTIVE;
    }
}
