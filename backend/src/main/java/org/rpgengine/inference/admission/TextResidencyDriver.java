package org.rpgengine.inference.admission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.scheduling.ScheduledTask;
import org.rpgengine.inference.scheduling.TextResidency;
import org.springframework.context.SmartLifecycle;

/**
 * Drives the {@link LeaseExecutor} and serves the scheduler's {@link TextResidency} claims over it: the one
 * caller of {@link LeaseExecutor#start} and {@link LeaseExecutor#pass}.
 *
 * <p>Everything this driver asks of the executor runs on its single worker thread, because the executor
 * holds its monitor across server calls and the scheduler's drain loop must never wait for those. Claims and
 * releases only enqueue work, and their order is kept, so a release always follows its claim and a released
 * model is free before the next claim asks. A long load on the worker delays the next pass and later claims,
 * never the scheduler.
 *
 * <p>A task whose slot has no local model (another backend, or one the adapter would refuse anyway) needs no
 * residency: its claim is ready at once and the adapter reports any refusal itself, exactly as without this
 * driver. Other tasks become one text {@link AdmissionRequest} whose identity is the task's.
 *
 * <p>While a claim holds a lease the driver renews it at half its lifetime, because a lapsed lease would have
 * its model unloaded under the running turn. A failed renewal, an expiry or the coordinator naming the lease
 * tells the holder to stop. The lease ends only through {@link TextResidency.Claim#release}, or when the
 * executor ended it first.
 *
 * <p>Because a pass hands each grant out once, this driver also takes the image grants, and hands them to the
 * {@link LeasedImageAdmission} (P21.4-C.1.2.2.2). Batches run on that class's threads, never on this worker.
 *
 * <p>Failures: a failed pass is logged by class name only and repeats on the next tick. {@link #stop} lets
 * queued releases run for a bounded time; anything left is an open lease row, which the next process takes
 * over as a hold until its model is gone.
 */
final class TextResidencyDriver implements TextResidency, SmartLifecycle {
    /** Longest wait in {@link #stop} for the worker, after which a running pause is interrupted. */
    static final Duration STOP_WAIT = Duration.ofSeconds(15);
    private static final System.Logger LOG = System.getLogger(TextResidencyDriver.class.getName());

    private enum State { NEW, BYPASSED, WAITING, HELD, ENDED }

    /** One claim. Apart from {@link #released}, its fields are confined to the worker. */
    private final class Entry implements Claim {
        private final ScheduledTask task;
        private final Listener listener;
        private volatile boolean released;
        private State state = State.NEW;
        private UUID leaseId;
        private Duration lifetime;
        private Instant renewAt;
        private boolean stopAsked;

        private Entry(ScheduledTask task, Listener listener) {
            this.task = task;
            this.listener = listener;
        }

        @Override
        public void release(boolean yielded) {
            if (released) {
                return;
            }
            released = true;
            try {
                work.execute(() -> end(this, yielded));
            } catch (RejectedExecutionException stopped) {
                // Stopped: the next process holds an open lease until its model is gone.
            }
        }
    }

    private final LeaseExecutor leases;
    private final AdmissionCoordinator coordinator;
    private final Function<InferenceSlot, Optional<String>> models;
    private final Demand demand;
    private final Clock clock;
    private final Duration tick;
    private final LeasedImageAdmission images;
    private final ScheduledExecutorService work = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "inference-residency");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;

    // Worker-confined state.
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private boolean started;

    /** A driver with no image path: a grant of another modality is unwanted and given back. */
    TextResidencyDriver(LeaseExecutor leases, AdmissionCoordinator coordinator,
            Function<InferenceSlot, Optional<String>> models, Demand demand, Clock clock, Duration tick) {
        this(leases, coordinator, models, demand, clock, tick, null);
    }

    /**
     * @param leases the executor; it is started by the first tick
     * @param coordinator receives the requests and the renewals of their leases
     * @param models names the local model that serves a slot now, empty when the slot needs no residency; it
     *        runs on the worker, so it may read settings
     * @param demand what a text model is taken to need, within the coordinator's capacity
     * @param clock the coordinator's time source
     * @param tick time between two passes, positive
     * @param images receives every image grant, or null when no image path exists
     */
    TextResidencyDriver(LeaseExecutor leases, AdmissionCoordinator coordinator,
            Function<InferenceSlot, Optional<String>> models, Demand demand, Clock clock, Duration tick,
            LeasedImageAdmission images) {
        this.images = images;
        this.leases = Objects.requireNonNull(leases, "leases");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.models = Objects.requireNonNull(models, "models");
        this.demand = Objects.requireNonNull(demand, "demand");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tick = Objects.requireNonNull(tick, "tick");
    }

    /** Enqueue the claim; a stopped driver refuses it through {@code listener}. */
    @Override
    public Claim claim(ScheduledTask task, Listener listener) {
        Entry entry = new Entry(Objects.requireNonNull(task, "task"), Objects.requireNonNull(listener, "listener"));
        try {
            work.execute(() -> begin(entry));
        } catch (RejectedExecutionException stopped) {
            listener.refused(UNAVAILABLE);
        }
        return entry;
    }

    // ---------------------------------------------------------------- lifecycle

    /** Start ticking. One start only: a stopped driver stays stopped. */
    @Override
    public synchronized void start() {
        if (running || work.isShutdown()) {
            return;
        }
        running = true;
        work.scheduleWithFixedDelay(this::tick, 0, tick.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stop ticking and let queued claims and releases run, up to {@link #STOP_WAIT}. */
    @Override
    public void stop() {
        synchronized (this) {
            running = false;
            work.shutdown();
        }
        try {
            if (!work.awaitTermination(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                work.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            work.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** @return true while ticking */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** Starts before the scheduler and stops after it, so the scheduler's last releases still run. */
    @Override
    public int getPhase() {
        return DEFAULT_PHASE - 1;
    }

    /** Run one tick on the worker and wait for it, for tests that move their clock between ticks. */
    void tickNow() throws InterruptedException, ExecutionException {
        work.submit(this::tick).get();
    }

    /** Wait until everything enqueued so far has run. */
    void awaitIdle() throws InterruptedException, ExecutionException {
        work.submit(() -> { }).get();
    }

    // ---------------------------------------------------------------- worker

    private void begin(Entry entry) {
        if (entry.released) {
            entry.state = State.ENDED;
            return;
        }
        ScheduledTask task = entry.task;
        try {
            Optional<String> model = models.apply(task.slot());
            if (model.isEmpty()) {
                entry.state = State.BYPASSED;
                entry.listener.ready();
                return;
            }
            Submission submission = coordinator.submit(new AdmissionRequest(task.taskId(), Modality.TEXT,
                    task.workClass(), task.scope().campaignId(), model.get(), demand, clock.instant()));
            if (submission == Submission.ACCEPTED) {
                entry.state = State.WAITING;
                entries.put(task.taskId(), entry);
                tick();
                return;
            }
        } catch (RuntimeException failure) {
            // A model name the request refuses lands here too; the message is left out, as it could quote one.
            LOG.log(System.Logger.Level.WARNING, "residency request failed: " + failure.getClass().getName());
        }
        entry.state = State.ENDED;
        entry.listener.refused(UNAVAILABLE);
    }

    private void end(Entry entry, boolean yielded) {
        State state = entry.state;
        UUID id = entry.task.taskId();
        entry.state = State.ENDED;
        entries.remove(id);
        try {
            if (state == State.WAITING) {
                leases.cancel(id);
            } else if (state == State.HELD && yielded && entry.leaseId != null) {
                List<UUID> members = new ArrayList<>(leases.yieldText(entry.leaseId));
                members.remove(id);
                ended(members, LOST);
            } else if (state == State.HELD) {
                leases.finishText(id);
            }
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "residency release failed: " + failure.getClass().getName());
        }
    }

    private void tick() {
        try {
            if (!started) {
                leases.start();
                started = true;
            }
            renewHeld();
            LeaseExecutor.Pass pass = leases.pass();
            pass.expired().forEach(lease -> ended(lease.members(), LOST));
            pass.grants().forEach(this::serve);
            askHoldersToYield();
            if (images != null) {
                images.sweep();
            }
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "residency pass failed: " + failure.getClass().getName());
        }
    }

    /** Renew before the pass, whose poll would otherwise expire a lease that is due. */
    private void renewHeld() {
        Instant now = clock.instant();
        for (Entry entry : List.copyOf(entries.values())) {
            if (entry.state == State.HELD && entry.leaseId != null && !now.isBefore(entry.renewAt)) {
                if (renewed(entry.leaseId)) {
                    entry.renewAt = now.plus(entry.lifetime.dividedBy(2));
                } else {
                    ended(List.of(entry.task.taskId()), LOST);
                }
            }
        }
    }

    /** @return false when the lease is gone or lapsed, so its holder has lost its admission */
    private boolean renewed(UUID leaseId) {
        try {
            return coordinator.renew(leaseId);
        } catch (RuntimeException failure) {
            // The accounting renewed it; only the record is behind, and a restart holds such a row anyway.
            return true;
        }
    }

    /** Make a text grant's model resident, or pass an image grant on. Only this driver submits text requests. */
    private void serve(Grant grant) {
        if (images != null && grant.lease().modality() == Modality.IMAGE) {
            images.accept(grant);
            return;
        }
        List<UUID> admitted = grant.admitted().stream().map(AdmissionRequest::id).toList();
        if (admitted.stream().noneMatch(this::wanted)) {
            admitted.forEach(leases::finishText);
            return;
        }
        try {
            LeaseExecutor.Outcome outcome = leases.prepareText(grant);
            switch (outcome.status()) {
                case READY -> admitted.forEach(id -> held(id, grant.lease()));
                // The executor put the requests back and asks again itself; they stay waiting.
                case DELAYED, SWAP_FAILED, DEFERRED -> { }
                case FELL_BACK -> ended(outcome.members(), CONTENTION);
                case LOAD_FAILED -> ended(outcome.members(), LOAD_FAILED);
                default -> ended(outcome.members(), LOST);
            }
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "residency grant failed: " + failure.getClass().getName());
            admitted.forEach(leases::finishText);
            ended(admitted, UNAVAILABLE);
        }
    }

    private boolean wanted(UUID id) {
        Entry entry = entries.get(id);
        return entry != null && !entry.released;
    }

    /**
     * A request's model is resident. A claim released meanwhile gives its place back here. The lease is renewed
     * before the turn starts because the worker could not renew it during the load, which may have used most of
     * its lifetime. A load that outlasted the lifetime loses it, and the next pass removes the model.
     */
    private void held(UUID id, Lease lease) {
        Entry entry = entries.get(id);
        if (entry == null || entry.released) {
            leases.finishText(id);
            if (entry != null) {
                entries.remove(id);
                entry.state = State.ENDED;
            }
            return;
        }
        if (!renewed(lease.id())) {
            ended(List.of(id), LOST);
            return;
        }
        entry.state = State.HELD;
        entry.leaseId = lease.id();
        entry.lifetime = Duration.between(lease.grantedAt(), lease.expiresAt());
        entry.renewAt = clock.instant().plus(entry.lifetime.dividedBy(2));
        entry.listener.ready();
    }

    /** These requests have no admission any more: a waiting claim is refused and a holder is told to stop. */
    private void ended(List<UUID> members, String code) {
        for (UUID member : members) {
            Entry entry = entries.get(member);
            if (entry == null) {
                continue;
            }
            if (entry.state == State.WAITING) {
                entries.remove(member);
                entry.state = State.ENDED;
                entry.listener.refused(code);
            } else if (entry.state == State.HELD) {
                entry.leaseId = null;
                askToStop(entry);
            }
        }
    }

    private void askHoldersToYield() {
        for (Entry entry : List.copyOf(entries.values())) {
            if (entry.state == State.HELD && entry.leaseId != null && !entry.stopAsked
                    && leases.shouldYield(entry.leaseId)) {
                askToStop(entry);
            }
        }
    }

    private void askToStop(Entry entry) {
        if (!entry.stopAsked) {
            entry.stopAsked = true;
            entry.listener.mustStop();
        }
    }
}
