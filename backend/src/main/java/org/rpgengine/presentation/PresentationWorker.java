package org.rpgengine.presentation;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Single background thread that drains due presentation jobs (P07.5). It starts with the
 * application lifecycle, after startup reconciliation, polls with a fixed delay and stops before
 * the database pool closes. One failing pass is reported by exception class only (never data or
 * messages, which may contain SQL) and the next pass retries; durable job state, not this thread,
 * decides what is owed. Disable it with {@code engine.presentation.worker.enabled=false}; the test
 * configuration does so and drives {@link PresentationJobService} explicitly for determinism.
 */
@Component
public class PresentationWorker implements SmartLifecycle {
    private static final System.Logger LOG = System.getLogger(PresentationWorker.class.getName());
    /** Jobs handled per pass, bounding one pass's database work. */
    static final int BATCH = 16;

    private final PresentationJobService jobs;
    private final boolean enabled;
    private final long pollMillis;
    private ScheduledExecutorService executor;

    /**
     * Configure the worker; invalid timing fails startup.
     *
     * @param jobs durable job service
     * @param enabled whether the background thread runs at all
     * @param pollMillis delay between passes, 50..60000 milliseconds
     */
    public PresentationWorker(PresentationJobService jobs,
            @Value("${engine.presentation.worker.enabled:true}") boolean enabled,
            @Value("${engine.presentation.worker.poll-ms:1000}") long pollMillis) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        if (pollMillis < 50 || pollMillis > 60_000) {
            throw new IllegalArgumentException("engine.presentation.worker.poll-ms must be 50..60000");
        }
        this.enabled = enabled;
        this.pollMillis = pollMillis;
    }

    /** Start the daemon thread when enabled; repeated calls keep one thread. */
    @Override
    public synchronized void start() {
        if (!enabled || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "presentation-worker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pass, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    }

    /** Stop scheduling and wait up to five seconds for the current pass; unfinished leases expire. */
    @Override
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    /** @return true while the background thread is scheduled */
    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    /** One bounded pass; failures never kill the scheduler. */
    private void pass() {
        try {
            jobs.drain(BATCH);
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "presentation worker pass failed: " + failure.getClass().getName());
        }
    }
}
