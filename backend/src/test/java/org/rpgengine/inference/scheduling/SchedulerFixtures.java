package org.rpgengine.inference.scheduling;

import java.time.Clock;

import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.session.ScopedSessions;

/**
 * Shared construction of a recovered scheduler over an in-memory ledger for suites that exercise
 * callers of the scheduler (the contract runner) rather than the scheduler itself. The scheduler
 * is not started: without its timer, every pass runs on the submitting or completing thread, which
 * keeps those suites deterministic.
 */
public final class SchedulerFixtures {
    private SchedulerFixtures() {
    }

    /**
     * @param sessions scoped sessions over {@code port}
     * @param port the task port double
     * @param clock clock shared with the caller under test
     * @return a recovered, ready scheduler with default settings and no jitter
     */
    public static InferenceScheduler scheduler(ScopedSessions sessions, NarrationTaskPort port, Clock clock) {
        var scheduler = new InferenceScheduler(sessions, port, new MemoryTaskLedger(), SchedulerSettings.defaults(),
                clock, () -> 0.5);
        scheduler.recover();
        return scheduler;
    }
}
