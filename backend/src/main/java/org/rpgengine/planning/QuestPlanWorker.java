package org.rpgengine.planning;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Single background thread of optional quest planning (P08.7). It runs only when
 * {@code engine.planning.enabled=true} and {@code engine.planning.worker.enabled} (default true)
 * allow it, so the default installation starts no planner work at all. Each pass first requests
 * bounded refinements for campaigns whose latest plan versions have stale branches (at most
 * {@value #CAMPAIGNS} campaigns per pass, in random order so every campaign is reached), then
 * runs at most one preparation job, which may hold the thread for the contract budget plus
 * margin while the fair scheduler serves interactive work first. A failing pass is reported by
 * exception class only and the next pass retries; durable job state, not this thread, decides
 * what is owed. The test configuration drives {@link QuestPlanning} and {@link #pass} directly
 * for determinism.
 */
@Component
public class QuestPlanWorker implements SmartLifecycle {
    private static final System.Logger LOG = System.getLogger(QuestPlanWorker.class.getName());
    /** Campaigns swept for replanning per pass, bounding one pass's database work. */
    static final int CAMPAIGNS = 16;

    private final QuestPlanning planning;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final long pollMillis;
    private final UUID holder = UUID.randomUUID();
    private ScheduledExecutorService executor;

    /**
     * @param planning planning service; its settings decide whether planning is enabled
     * @param jdbc local persistence for the replanning sweep
     * @param workerEnabled whether this thread may run when planning is enabled
     * @param pollMillis delay between passes, 100..60000 milliseconds
     */
    public QuestPlanWorker(QuestPlanning planning, JdbcTemplate jdbc,
            @Value("${engine.planning.worker.enabled:true}") boolean workerEnabled,
            @Value("${engine.planning.worker.poll-ms:2000}") long pollMillis) {
        this.planning = Objects.requireNonNull(planning, "planning");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        if (pollMillis < 100 || pollMillis > 60_000) {
            throw new IllegalArgumentException("engine.planning.worker.poll-ms must be 100..60000");
        }
        this.enabled = workerEnabled && planning.settings().enabled();
        this.pollMillis = pollMillis;
    }

    /** Start the daemon thread when enabled; repeated calls keep one thread. */
    @Override
    public synchronized void start() {
        if (!enabled || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "quest-plan-worker");
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
            // Only the latest version of each plan matters; random order rotates the bounded
            // sweep, so campaigns beyond the per-pass bound are reached on later passes.
            for (UUID campaign : jdbc.queryForList("select campaign_id from (select distinct l.campaign_id "
                    + "from (select distinct on (campaign_id, plan_id) id, campaign_id from quest_plan_artifact "
                    + "order by campaign_id, plan_id, plan_version desc) l cross join lateral (select status "
                    + "from plan_disposition where artifact_id = l.id order by sequence desc limit 1) d "
                    + "where d.status in ('PARTIALLY_INVALIDATED', 'INVALIDATED')) stale order by random() limit ?",
                    UUID.class, CAMPAIGNS)) {
                planning.replan(campaign);
            }
            planning.processNext(holder);
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "quest plan worker pass failed: " + failure.getClass().getName());
        }
    }
}
