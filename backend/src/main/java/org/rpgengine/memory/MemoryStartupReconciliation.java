package org.rpgengine.memory;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs {@link MemoryReconciliation} once per process at startup (P09.5, docs/08 section 10).
 *
 * <p><b>Ordering.</b> Lifecycle beans start only after every {@code SmartInitializingSingleton}
 * callback, so journal, presentation, preparation-job and scheduler recovery (which also retires
 * AI-session records left active with {@code RESTART}) have finished: expired mutation leases are
 * gone and interrupted narration is settled before checkpoints are verified and recaptured. The
 * phase, {@value #PHASE}, is below the embedded web server's start phase
 * ({@code Integer.MAX_VALUE - 2048}), so the pass also finishes before the first HTTP request, and
 * below the default phase of the background workers, which start afterwards.
 *
 * <p>A failing pass is logged by exception class and never aborts startup: everything it repairs is
 * derived data, and the decisions that matter for safety (mutation pause, summary freshness,
 * checkpoint verification) are re-derived where they are used. {@code engine.memory.reconciliation.enabled}
 * (default true) switches the pass off; the test configuration does so and drives
 * {@link MemoryReconciliation#reconcile()} directly.
 */
@Component
public class MemoryStartupReconciliation implements SmartLifecycle {
    /** Lifecycle phase: after initialization callbacks, before the web server and the workers. */
    public static final int PHASE = Integer.MAX_VALUE - 4096;
    private static final System.Logger LOG = System.getLogger(MemoryStartupReconciliation.class.getName());

    private final MemoryReconciliation reconciliation;
    private final boolean enabled;
    private volatile boolean running;
    private volatile MemoryReconciliation.Report report;

    /**
     * @param reconciliation the pass
     * @param enabled whether the pass runs at startup
     */
    public MemoryStartupReconciliation(MemoryReconciliation reconciliation,
            @Value("${engine.memory.reconciliation.enabled:true}") boolean enabled) {
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        this.enabled = enabled;
    }

    /** Run the pass once, synchronously, when enabled; a later restart of the context runs it again. */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (enabled) {
            try {
                report = reconciliation.reconcile();
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "memory reconciliation pass failed: " + failure.getClass().getName());
            }
        }
        running = true;
    }

    /** Nothing keeps running after start; stopping only clears the flag. */
    @Override
    public synchronized void stop() {
        running = false;
    }

    /** @return true after {@link #start} until {@link #stop} */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** @return {@value #PHASE} */
    @Override
    public int getPhase() {
        return PHASE;
    }

    /** @return totals of this process's startup pass, or null when it did not run or failed */
    public MemoryReconciliation.Report lastReport() {
        return report;
    }
}
