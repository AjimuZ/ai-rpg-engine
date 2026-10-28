package org.rpgengine.memory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.rpgengine.inference.session.Audience;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Single background thread of memory maintenance (P09.3). Each bounded pass:
 * <ol>
 *   <li>captures an operational checkpoint for at most {@value #CAMPAIGNS} campaigns whose newest
 *       checkpoint is older than their current revision (random order, so every campaign is reached
 *       on later passes), skipping any campaign not at a safe boundary, and prunes each to
 *       {@link MemorySettings#checkpointRetention()} checkpoints;</li>
 *   <li>when summaries are enabled, asks for a public continuity summary for at most
 *       {@value #CAMPAIGNS} campaigns (the request policy decides whether one is due) and runs at
 *       most one summary job, which may hold the thread for the contract budget plus margin while
 *       the fair scheduler serves interactive work first.</li>
 * </ol>
 * Privileged summaries are requested by their privileged callers, not by this sweep, so the default
 * background cost is one audience. A failing pass is reported by exception class only and the next
 * pass retries; durable rows, not this thread, decide what is owed. The thread runs only when
 * {@code engine.memory.worker.enabled} (default true) allows it; the test configuration drives
 * {@link #pass} directly for determinism.
 */
@Component
public class MemoryWorker implements SmartLifecycle {
    private static final System.Logger LOG = System.getLogger(MemoryWorker.class.getName());
    /** Campaigns visited per pass and step, bounding one pass's database work. */
    static final int CAMPAIGNS = 16;

    private final OperationalCheckpoints checkpoints;
    private final MemorySummaries summaries;
    private final ContinuitySummarizer summarizer;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final long pollMillis;
    private final UUID holder = UUID.randomUUID();
    private ScheduledExecutorService executor;

    /**
     * @param checkpoints checkpoint service
     * @param summaries summary jobs; their settings decide whether summaries run
     * @param summarizer summary job runner
     * @param jdbc storage for the bounded sweeps
     * @param enabled whether this thread may run
     * @param pollMillis delay between passes, 100..300000 milliseconds
     */
    public MemoryWorker(OperationalCheckpoints checkpoints, MemorySummaries summaries, ContinuitySummarizer summarizer,
            JdbcTemplate jdbc, @Value("${engine.memory.worker.enabled:true}") boolean enabled,
            @Value("${engine.memory.worker.poll-ms:5000}") long pollMillis) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        if (pollMillis < 100 || pollMillis > 300_000) {
            throw new IllegalArgumentException("engine.memory.worker.poll-ms must be 100..300000");
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
            Thread thread = new Thread(task, "memory-worker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pass, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    }

    /** Stop scheduling and wait up to five seconds; an unfinished lease expires and is recovered. */
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

    /** One bounded pass, also callable directly by embedders and suites; failures never escape. */
    public void pass() {
        try {
            for (UUID campaign : jdbc.queryForList("select c.id from campaign c where c.state_revision > coalesce(("
                    + "select max(k.state_revision) from campaign_checkpoint k where k.campaign_id = c.id), -1) "
                    + "order by random() limit ?", UUID.class, CAMPAIGNS)) {
                var capture = checkpoints.capture(campaign);
                if (capture.outcome() == OperationalCheckpoints.Capture.Outcome.CAPTURED) {
                    checkpoints.prune(campaign, summaries.settings().checkpointRetention());
                }
            }
            if (summaries.settings().summariesEnabled()) {
                for (UUID campaign : jdbc.queryForList("select id from campaign order by random() limit ?", UUID.class,
                        CAMPAIGNS)) {
                    summaries.request(campaign, Audience.PUBLIC, false);
                }
                summarizer.processNext(holder);
            }
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "memory worker pass failed: " + failure.getClass().getName());
        }
    }
}
