package org.rpgengine.inference.scheduling;

/**
 * What the {@link InferenceScheduler} asks of whatever makes a text task's model resident while the task
 * holds the turn permit (P21.4-C.1.2.2.1, docs/34 section 10). The scheduler knows only this interface, so
 * the dependency keeps pointing from the admission package to this one. No bean of this type exists unless
 * {@code engine.inference.admission.enabled} is {@code true}, and then the scheduler behaves as if this
 * interface did not exist.
 *
 * <p>The scheduler calls every method from its serialized drain loop, so no method here may wait for a
 * server, a database or a lock held across one. Callbacks may arrive on any thread.
 */
public interface TextResidency {
    /** The residency side cannot serve the task at all, for example because it has stopped. */
    String UNAVAILABLE = "residency-unavailable";
    /** The task's model would not load, so the lease ended without a turn. */
    String LOAD_FAILED = "residency-load-failed";
    /** Free GPU memory stayed too low for longer than the owner's patience. */
    String CONTENTION = "residency-contention";
    /** The admission was lost before the task's turn could start. */
    String LOST = "residency-lost";

    /** How a claim progresses. Exactly one of {@link #ready} and {@link #refused} arrives, unless it was released first. */
    interface Listener {
        /** The task may start its turn: its model is resident, or the task needs no residency at all. */
        void ready();

        /**
         * The task cannot run; its claim holds nothing any more.
         *
         * @param code one of the fixed codes of {@link TextResidency}, reported as the task's error code
         */
        void refused(String code);

        /** After {@link #ready}: the admission was taken back or lost, so the running turn must end. */
        void mustStop();
    }

    /** The scheduler's side of one claim. */
    interface Claim {
        /**
         * Give up what the claim holds, once the task's turn cannot be running any more or the task ended
         * before one started. Never waits. The first call counts and later ones do nothing.
         *
         * @param yielded true when the turn ended because it was preempted, so its lease is taken back
         *        rather than finished; a claim still waiting ignores this
         */
        void release(boolean yielded);
    }

    /**
     * Ask for residency for a task that just took the turn permit. Never waits; a failure is reported through
     * {@code listener} or thrown as a {@link RuntimeException}, which the scheduler treats as {@link #UNAVAILABLE}.
     *
     * @param task the dispatched task
     * @param listener told how the claim progresses
     * @return the claim, which the scheduler always releases
     */
    Claim claim(ScheduledTask task, Listener listener);
}
