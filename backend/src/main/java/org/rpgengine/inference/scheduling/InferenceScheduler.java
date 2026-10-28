package org.rpgengine.inference.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

import org.rpgengine.inference.DispatchJournal;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.session.ScopedSessions;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The installation's single durable fair priority queue for inference (P08.6, docs/06 section 12,
 * docs/28 section 9). Every engine task passes through it: callers (the contract runner) submit a
 * {@link ScheduledTask}; the scheduler admits it into the durable {@link TaskLedger}, waits for the
 * runtime turn permit, then submits it in its own logical session through {@link ScopedSessions}
 * and records the terminal outcome. {@code SchedulerBoundaryTest} fails the build if other main
 * code calls {@link ScopedSessions#submit}.
 *
 * <p><b>Admission</b> (in order; a refusal completes the handle at once and sends nothing):
 * scheduler recovered and running ({@value #RECOVERING}, {@value #NOT_RUNNING}); a cancellation
 * that arrived first; no live task with the same identity ({@value #DUPLICATE}); the class's
 * queue places: interactive and background work have separate installation and per-campaign
 * bounds, so background backlog can never take an interactive place ({@value #QUEUE_FULL}); no
 * account suspension for background work ({@value #SUSPENDED}); the rolling 24-hour usage quotas,
 * all classes and background classes, counting the task's estimate ({@value #QUOTA_EXHAUSTED});
 * then the durable QUEUED row, which also proves that the campaign belongs to the scope's
 * adventure at the scope's recovery generation ({@link ScopedSessions#SCOPE_CONFLICT},
 * {@link ScopedSessions#STALE_GENERATION}) and that the identity was never used before
 * ({@value #DUPLICATE}, also after a restart).
 *
 * <p><b>Dispatch</b>: {@link FairQueue} chooses the next task (interactive first, isolated
 * role/audience lines in order, background aging, fair campaign rotation, the interactive permit
 * reservation). The task's remaining budget must still reach {@link TaskRequest#MIN_TIMEOUT}
 * ({@value #QUEUE_TIMEOUT}), the quota is re-checked, and the ledger moves the row to DISPATCHED
 * and charges its reservation; the V14 single-turn index refuses this while any other row holds
 * the permit, even one of another process. Only then is the task forwarded to the port with its
 * remaining budget.
 *
 * <p><b>Settlement</b>: the port's terminal result is written to the ledger and returned to the
 * caller unchanged, except that a preempted task reports {@value #PREEMPTED}. The reservation stays
 * charged whenever a runtime turn may have run (the result has provenance, or is UNRESOLVED) and is
 * released otherwise. A failed ledger write never withholds the result; the permit stays in use
 * and the write is retried every tick until it succeeds.
 *
 * <p><b>Uncertain outcomes</b>: an UNRESOLVED result moves the row to RECONCILING and the scheduler
 * keeps the permit until the port reports itself READY with nothing active or queued (the Codex
 * adapter counts unconfirmed threads as active until its resume/list/interrupt reconciliation
 * succeeds), or DISABLED (nothing can ever start). Meanwhile waiting tasks end UNAVAILABLE when the
 * port cannot run anything; the row then ends UNRESOLVED.
 *
 * <p><b>Preemption</b>: when interactive work waits and no permit is free, the lowest-priority
 * running background task is cancelled by its exact handle (the port interrupts the exact turn and
 * awaits its terminal status); the permit stays in use until that terminal result is settled, then
 * the interactive task is dispatched next. Partial output is never kept.
 *
 * <p><b>Account backoff</b>: a result with an account condition code ({@code usageLimitExceeded},
 * {@code rateLimitExceeded}, {@code serverOverloaded}, {@code unauthorized}) suspends background
 * admission and dispatch for a jittered, doubling, bounded delay recorded durably with a sanitized
 * code; a completed task clears it. Interactive work is never suspended: its failure ends in the
 * caller's deterministic fallback, and nothing switches runtime, model, account or billing.
 *
 * <p><b>Restart</b> ({@link #recover}, before any admission): QUEUED rows of earlier processes end
 * ABANDONED (their owning durable jobs re-request them with fresh context, and instructions are
 * never stored or replayed); DISPATCHED and RECONCILING rows become this process's RECONCILING rows,
 * their recorded runtime references go to {@link NarrationTaskPort#reconcile}, and the permit is
 * held until the port confirmed them. The account backoff is reloaded. Quotas are read from the
 * ledger, so a restart never resets usage.
 *
 * <p><b>Concurrency default</b>: one runtime turn installation-wide. {@link SchedulerSettings#from}
 * refuses a higher limit until P01.7 evidence and a QA-37 qualification exist, and the V14 index
 * enforces the same limit in the database.
 *
 * <p>Thread-safety: all scheduling state is confined to a serialized drain loop
 * ({@link #pump()}): submissions, cancellations, port completions and timer ticks enqueue events
 * and whichever thread finds the loop idle runs it. Port completions and ticks run it on the
 * scheduler's own thread once {@link #start()} ran, so ledger writes never block a runtime
 * adapter's worker. {@link #submit} itself never waits for inference.
 */
@Component
public class InferenceScheduler implements SmartLifecycle, SmartInitializingSingleton {
    /** Refusal before {@link #recover} finished. */
    public static final String RECOVERING = "scheduler-recovering";
    /** Refusal after {@link #stop}. */
    public static final String NOT_RUNNING = "not-running";
    /** Refusal of a task identity that is live or already recorded. */
    public static final String DUPLICATE = "duplicate-task";
    /** Refusal when the class's installation or campaign places are taken. */
    public static final String QUEUE_FULL = "queue-full";
    /** Refusal of background work during an account suspension. */
    public static final String SUSPENDED = "inference-suspended";
    /** Refusal when a rolling usage quota would be exceeded. */
    public static final String QUOTA_EXHAUSTED = "quota-exhausted";
    /** A waiting task whose remaining budget fell below the port minimum. */
    public static final String QUEUE_TIMEOUT = "queue-timeout";
    /** A background task interrupted for interactive work. */
    public static final String PREEMPTED = "preempted";
    /** The ledger could not record an admission or dispatch; nothing was sent. */
    public static final String STORAGE = "scheduler-storage-unavailable";
    /** The ledger row is no longer this process's; nothing was sent. */
    public static final String FENCED = "task-fenced";
    /** Submitting through the session registry failed before reaching the port. */
    public static final String DISPATCH_FAILED = "dispatch-failed";
    /** Code of a waiting task cancelled by its caller. */
    public static final String CANCELLED = "cancelled";
    /** Code of work ended by shutdown. */
    public static final String SHUTDOWN = "shutdown";
    /** Rolling quota window. */
    static final Duration WINDOW = Duration.ofHours(24);
    /** Longest wait in {@link #stop()} for running tasks to settle. */
    static final Duration STOP_WAIT = Duration.ofSeconds(15);
    /** Account condition codes of task results and the sanitized suspension codes they produce. */
    static final Map<String, String> ACCOUNT_CONDITIONS = Map.of("usageLimitExceeded", "account-usage-limit",
            "rateLimitExceeded", "account-rate-limit", "serverOverloaded", "service-overloaded",
            "unauthorized", "account-unauthorized");
    private static final System.Logger LOG = System.getLogger(InferenceScheduler.class.getName());

    /** Lifecycle of the scheduler. */
    public enum State {
        /** Constructed; {@link #recover} has not finished, admissions are refused. */
        RECOVERING,
        /** Admitting and dispatching. */
        READY,
        /** Shutdown in progress; admissions are refused. */
        STOPPING,
        /** Stopped. */
        STOPPED
    }

    /**
     * Non-sensitive scheduler state: counts and sanitized codes only, never identities or text.
     *
     * @param state lifecycle state
     * @param maxActive turn permits
     * @param interactiveWaiting interactive tasks waiting for a permit
     * @param backgroundWaiting background tasks waiting for a permit
     * @param running tasks holding a permit
     * @param reconciling permits held for unconfirmed outcomes
     * @param suspension sanitized account condition while background work is suspended, else null
     * @param suspendedUntil end of that suspension, else null
     */
    public record Status(State state, int maxActive, int interactiveWaiting, int backgroundWaiting, int running,
            int reconciling, String suspension, Instant suspendedUntil) {
    }

    private record Counts(int interactiveWaiting, int backgroundWaiting, int running, int reconciling) {
    }

    /** A port result whose ledger write is pending. */
    private record Settlement(Scheduled task, String status, String code, boolean keepCharge) {
    }

    private final ScopedSessions sessions;
    private final NarrationTaskPort port;
    private final TaskLedger ledger;
    private final SchedulerSettings settings;
    private final Clock clock;
    private final DoubleSupplier jitter;
    private final UUID instance = UUID.randomUUID();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger pumpRequests = new AtomicInteger();
    private final ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<>();
    private volatile State state = State.RECOVERING;
    private volatile TaskLedger.Suspension suspension = TaskLedger.Suspension.NONE;
    private volatile Counts counts = new Counts(0, 0, 0, 0);
    private volatile ScheduledExecutorService executor;

    // Drain-confined state.
    private final FairQueue<Scheduled> queue;
    /** Admitted tasks not yet settled, by identity (waiting, running or awaiting their ledger write). */
    private final Map<UUID, Scheduled> live = new LinkedHashMap<>();
    private final List<Settlement> unsettled = new ArrayList<>();
    /** RECONCILING rows of this process whose permits are held until the port confirms them. */
    private final List<UUID> reconciling = new ArrayList<>();

    /**
     * Spring constructor: settings from {@code engine.inference.scheduler.*}, the system clock and
     * random jitter.
     *
     * @param sessions the scoped session registry, the only submitter of the port
     * @param port the installation's task port, read for readiness and reconciliation
     * @param ledger durable ledger
     * @param environment source of the settings
     * @throws IllegalArgumentException for invalid or unqualified settings, failing startup
     */
    @Autowired
    public InferenceScheduler(ScopedSessions sessions, NarrationTaskPort port, TaskLedger ledger,
            Environment environment) {
        this(sessions, port, ledger, SchedulerSettings.from(environment), Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * @param sessions the scoped session registry
     * @param port the task port behind {@code sessions}
     * @param ledger durable ledger
     * @param settings validated limits
     * @param clock clock of deadlines, quotas and backoff; tests supply a controllable one
     * @param jitter source of uniform values in [0, 1) for the backoff jitter
     */
    public InferenceScheduler(ScopedSessions sessions, NarrationTaskPort port, TaskLedger ledger,
            SchedulerSettings settings, Clock clock, DoubleSupplier jitter) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.port = Objects.requireNonNull(port, "port");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.queue = new FairQueue<>(settings.maxActive(), settings.agingStep());
    }

    // ---------------------------------------------------------------- API

    /**
     * Offer one task. Never waits for inference; refusals, unavailability and every terminal
     * outcome arrive through the handle's result, which completes exactly once and never
     * exceptionally.
     *
     * @param task validated task
     * @return the caller's handle
     */
    public TaskHandle submit(ScheduledTask task) {
        Objects.requireNonNull(task, "task");
        Instant now = clock.instant();
        Scheduled scheduled = new Scheduled(task, sequence.incrementAndGet(), now, now.plus(task.timeout()));
        events.add(() -> admit(scheduled));
        pump();
        return scheduled;
    }

    /** @return current non-sensitive state */
    public Status status() {
        Counts current = counts;
        TaskLedger.Suspension backoff = suspension;
        boolean suspended = suspended(backoff, clock.instant());
        return new Status(state, settings.maxActive(), current.interactiveWaiting(), current.backgroundWaiting(),
                current.running(), current.reconciling(), suspended ? backoff.code() : null,
                suspended ? backoff.until() : null);
    }

    /**
     * Startup reconciliation, before any admission (docs/28 section 9): attach the write-ahead
     * journal, abandon earlier processes' queued rows, take over their dispatched and reconciling
     * rows with the permit held, hand their runtime references to the port and reload the account
     * backoff. Idempotent; storage failures propagate and fail startup.
     */
    public synchronized void recover() {
        if (state != State.RECOVERING) {
            return;
        }
        port.attachJournal(this::recordStart);
        TaskLedger.Recovery recovery = ledger.recover(instance, clock.instant());
        suspension = ledger.suspension();
        List<String> references = recovery.uncertain().stream().map(TaskLedger.Uncertain::runtimeReference)
                .filter(Objects::nonNull).toList();
        events.add(() -> {
            for (TaskLedger.Uncertain uncertain : recovery.uncertain()) {
                reconciling.add(uncertain.taskId());
                if (queue.permitsInUse() < settings.maxActive()) {
                    queue.hold();
                }
            }
            state = State.READY;
        });
        if (!references.isEmpty()) {
            port.reconcile(references);
        }
        if (recovery.abandoned() > 0 || !recovery.uncertain().isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "inference scheduler recovery: " + recovery.abandoned()
                    + " abandoned, " + recovery.uncertain().size() + " awaiting reconciliation");
        }
        pump();
    }

    /** Run startup reconciliation once every singleton exists, before lifecycle start. */
    @Override
    public void afterSingletonsInstantiated() {
        recover();
    }

    // ---------------------------------------------------------------- lifecycle

    /** Start the timer that enforces queue deadlines, reconciliation, backoff expiry and retries. */
    @Override
    public synchronized void start() {
        recover();
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "inference-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        long period = settings.tick().toMillis();
        executor.scheduleWithFixedDelay(this::pump, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Refuse admissions, end waiting tasks CANCELLED ({@value #SHUTDOWN}), cancel running ones and
     * wait up to {@link #STOP_WAIT} for their terminal results to be settled. A task still running
     * afterwards keeps its DISPATCHED row, which the next process reconciles.
     */
    @Override
    public void stop() {
        ScheduledExecutorService timer;
        synchronized (this) {
            if (state == State.STOPPED) {
                return;
            }
            state = State.STOPPING;
            timer = executor;
        }
        events.add(this::shutdownWork);
        pump();
        long deadline = System.nanoTime() + STOP_WAIT.toNanos();
        while (counts.running() > 0 && System.nanoTime() - deadline < 0) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            if (timer == null) {
                pump();
            }
        }
        synchronized (this) {
            if (timer != null) {
                timer.shutdownNow();
            }
            executor = null;
            state = State.STOPPED;
        }
    }

    /** @return true while the timer runs */
    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    // ---------------------------------------------------------------- drain loop

    /**
     * Run the drain loop on this thread unless another thread is running it; in that case the
     * running loop repeats once more. Serializes all scheduling state without a lock held across
     * port or caller callbacks, which may re-enter {@link #submit} or complete synchronously.
     */
    private void pump() {
        if (pumpRequests.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        do {
            try {
                drainOnce();
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "inference scheduler pass failed: " + failure.getClass().getName());
            }
            missed = pumpRequests.addAndGet(-missed);
        } while (missed != 0);
    }

    /** Hand work to the scheduler thread when it runs, so callers of this method never write the ledger. */
    private void signal() {
        ScheduledExecutorService timer = executor;
        if (timer != null) {
            try {
                timer.execute(this::pump);
                return;
            } catch (RejectedExecutionException stopped) {
                // Shut down between the read and the call: run the loop here instead.
            }
        }
        pump();
    }

    private void drainOnce() {
        for (Runnable event = events.poll(); event != null; event = events.poll()) {
            event.run();
        }
        Instant now = clock.instant();
        retryUnsettled(now);
        expireWaiting(now);
        releaseReconciliation(now);
        preempt(now);
        dispatchReady(now);
        publishCounts();
    }

    // ---------------------------------------------------------------- admission

    private void admit(Scheduled task) {
        Instant now = clock.instant();
        ScheduledTask request = task.task;
        if (state == State.RECOVERING) {
            refuse(task, TaskResult.Status.REJECTED, RECOVERING);
            return;
        }
        if (state != State.READY) {
            refuse(task, TaskResult.Status.REJECTED, NOT_RUNNING);
            return;
        }
        if (task.cancelRequested) {
            refuse(task, TaskResult.Status.CANCELLED, CANCELLED);
            return;
        }
        if (live.containsKey(request.taskId())) {
            refuse(task, TaskResult.Status.REJECTED, DUPLICATE);
            return;
        }
        if (!placeAvailable(task)) {
            refuse(task, TaskResult.Status.REJECTED, QUEUE_FULL);
            return;
        }
        if (request.workClass().background() && suspended(suspension, now)) {
            refuse(task, TaskResult.Status.REJECTED, SUSPENDED);
            return;
        }
        TaskLedger.Admission admission;
        try {
            if (quotaExceeded(task, now)) {
                refuse(task, TaskResult.Status.REJECTED, QUOTA_EXHAUSTED);
                return;
            }
            admission = ledger.admit(new TaskLedger.Entry(request, instance, task.estimate, task.enqueuedAt,
                    task.deadline));
        } catch (RuntimeException storage) {
            refuse(task, TaskResult.Status.REJECTED, STORAGE);
            return;
        }
        switch (admission) {
            case ADMITTED -> {
                live.put(request.taskId(), task);
                queue.add(task);
            }
            case DUPLICATE -> refuse(task, TaskResult.Status.REJECTED, DUPLICATE);
            case SCOPE_CONFLICT -> refuse(task, TaskResult.Status.REJECTED, ScopedSessions.SCOPE_CONFLICT);
            case STALE_GENERATION -> refuse(task, TaskResult.Status.REJECTED, ScopedSessions.STALE_GENERATION);
        }
    }

    /** Separate interactive and background places, installation-wide and per campaign. */
    private boolean placeAvailable(Scheduled task) {
        boolean background = task.workClass().background();
        int total = 0;
        int campaign = 0;
        for (Scheduled other : live.values()) {
            if (other.workClass().background() == background) {
                total++;
                if (other.line.campaignId().equals(task.line.campaignId())) {
                    campaign++;
                }
            }
        }
        return background
                ? total < settings.backgroundCapacity() && campaign < settings.backgroundPerCampaign()
                : total < settings.interactiveCapacity() && campaign < settings.interactivePerCampaign();
    }

    /** @return true when the task's estimate would exceed a rolling quota; reads the ledger */
    private boolean quotaExceeded(Scheduled task, Instant now) {
        Instant since = now.minus(WINDOW);
        if (ledger.usageSince(since, false) + task.estimate > settings.dailyUsageLimit()) {
            return true;
        }
        return task.workClass().background()
                && ledger.usageSince(since, true) + task.estimate > settings.backgroundDailyUsageLimit();
    }

    /** Complete a task that never got a ledger row. */
    private static void refuse(Scheduled task, TaskResult.Status status, String code) {
        task.future.complete(TaskResult.withoutTurn(task.taskId(), status, code));
    }

    // ---------------------------------------------------------------- cancellation and expiry

    private void cancelled(Scheduled task) {
        if (task.future.isDone()) {
            return;
        }
        if (queue.remove(task)) {
            endWaiting(task, TaskResult.Status.CANCELLED, CANCELLED, clock.instant());
        } else if (task.portHandle != null) {
            // Running: the port interrupts the exact turn and reports the confirmed terminal status.
            task.portHandle.cancel();
        }
        // Not yet admitted: admission sees the flag.
    }

    /** End a waiting task (already removed from the queue) without dispatch; the ledger write is best effort. */
    private void endWaiting(Scheduled task, TaskResult.Status status, String code, Instant now) {
        live.remove(task.taskId(), task);
        try {
            ledger.settle(task.taskId(), instance, status.name(), code, false, now);
        } catch (RuntimeException storage) {
            // The row stays QUEUED, which charges nothing and holds no permit; the next
            // process's startup reconciliation ends it ABANDONED.
            LOG.log(System.Logger.Level.WARNING, "inference ledger write failed: " + storage.getClass().getName());
        }
        task.future.complete(TaskResult.withoutTurn(task.taskId(), status, code));
    }

    private void expireWaiting(Instant now) {
        for (Scheduled task : queue.waiting()) {
            if (Duration.between(now, task.deadline).compareTo(TaskRequest.MIN_TIMEOUT) < 0 && queue.remove(task)) {
                endWaiting(task, TaskResult.Status.TIMED_OUT, QUEUE_TIMEOUT, now);
            }
        }
    }

    private void shutdownWork() {
        Instant now = clock.instant();
        for (Scheduled task : queue.waiting()) {
            if (queue.remove(task)) {
                endWaiting(task, TaskResult.Status.CANCELLED, SHUTDOWN, now);
            }
        }
        for (Scheduled task : queue.active()) {
            if (task.portHandle != null) {
                task.portHandle.cancel();
            }
        }
    }

    // ---------------------------------------------------------------- dispatch

    private void dispatchReady(Instant now) {
        while (state == State.READY) {
            var next = queue.next(now, !suspended(suspension, now));
            if (next.isEmpty()) {
                return;
            }
            Scheduled task = next.get();
            Duration remaining = Duration.between(now, task.deadline);
            if (remaining.compareTo(TaskRequest.MIN_TIMEOUT) < 0) {
                queue.remove(task);
                endWaiting(task, TaskResult.Status.TIMED_OUT, QUEUE_TIMEOUT, now);
                continue;
            }
            TaskLedger.Dispatch dispatch;
            try {
                if (quotaExceeded(task, now)) {
                    queue.remove(task);
                    endWaiting(task, TaskResult.Status.REJECTED, QUOTA_EXHAUSTED, now);
                    continue;
                }
                dispatch = ledger.dispatch(task.taskId(), instance, now);
            } catch (RuntimeException storage) {
                queue.remove(task);
                endWaiting(task, TaskResult.Status.REJECTED, STORAGE, now);
                continue;
            }
            switch (dispatch) {
                case PERMIT_BUSY -> {
                    // Another process holds the installation's turn: retry on a later tick.
                    return;
                }
                case STALE_GENERATION -> {
                    queue.remove(task);
                    endWaiting(task, TaskResult.Status.REJECTED, ScopedSessions.STALE_GENERATION, now);
                }
                case FENCED -> {
                    queue.remove(task);
                    live.remove(task.taskId(), task);
                    refuse(task, TaskResult.Status.REJECTED, FENCED);
                }
                case DISPATCHED -> {
                    queue.started(task);
                    forward(task, remaining);
                }
            }
        }
    }

    /** Submit a dispatched task in its scope's session; its completion returns to the drain loop. */
    private void forward(Scheduled task, Duration remaining) {
        ScheduledTask request = task.task;
        TaskHandle handle;
        try {
            handle = sessions.submit(request.scope(), request.taskId(), request.slot(), request.instructions(),
                    request.outputSchema(), remaining);
        } catch (RuntimeException failure) {
            // Nothing reached the port, so no turn ran and the reservation is released.
            settle(task, TaskResult.withoutTurn(request.taskId(), TaskResult.Status.FAILED, DISPATCH_FAILED));
            return;
        }
        task.portHandle = handle;
        if (task.cancelRequested || state != State.READY) {
            handle.cancel();
        }
        handle.result().whenComplete((result, failure) -> {
            TaskResult outcome = result != null ? result
                    : TaskResult.withoutTurn(request.taskId(), TaskResult.Status.UNRESOLVED, "port-failure");
            events.add(() -> settle(task, outcome));
            signal();
        });
    }

    // ---------------------------------------------------------------- settlement

    private void settle(Scheduled task, TaskResult portResult) {
        TaskResult result = portResult;
        if (task.preempted && result.status() == TaskResult.Status.CANCELLED) {
            result = new TaskResult(result.taskId(), TaskResult.Status.CANCELLED, null, PREEMPTED, result.provenance(),
                    result.freshSession());
        }
        boolean uncertain = result.status() == TaskResult.Status.UNRESOLVED;
        // A result without provenance never started a runtime turn (TaskResult contract).
        boolean keepCharge = uncertain || result.provenance() != null;
        var settlement = new Settlement(task, uncertain ? "RECONCILING" : result.status().name(),
                result.status() == TaskResult.Status.COMPLETED ? null : result.errorCode(), keepCharge);
        if (!write(settlement)) {
            unsettled.add(settlement);
        }
        account(result);
        task.future.complete(result);
    }

    /** @return true when the settlement is durable (or fenced) and the permit was released or held */
    private boolean write(Settlement settlement) {
        Scheduled task = settlement.task();
        try {
            ledger.settle(task.taskId(), instance, settlement.status(), settlement.code(), settlement.keepCharge(),
                    clock.instant());
        } catch (RuntimeException storage) {
            LOG.log(System.Logger.Level.WARNING, "inference ledger write failed: " + storage.getClass().getName());
            return false;
        }
        // A false result means another process took the row over (it now holds the permit in
        // RECONCILING), so this process releases its local permit either way.
        queue.finished(task);
        live.remove(task.taskId(), task);
        if (settlement.status().equals("RECONCILING")) {
            reconciling.add(task.taskId());
            queue.hold();
        }
        return true;
    }

    private void retryUnsettled(Instant now) {
        for (Iterator<Settlement> pending = unsettled.iterator(); pending.hasNext();) {
            if (write(pending.next())) {
                pending.remove();
            }
        }
    }

    /**
     * Release held permits once the port confirmed that nothing runs, and end waiting tasks
     * UNAVAILABLE while a held permit faces a port that cannot run anything.
     */
    private void releaseReconciliation(Instant now) {
        if (reconciling.isEmpty()) {
            return;
        }
        RuntimeStatus runtime;
        try {
            runtime = port.status();
        } catch (RuntimeException unknown) {
            return;
        }
        boolean confirmed = runtime.state() == RuntimeStatus.State.DISABLED
                || (runtime.state() == RuntimeStatus.State.READY && runtime.active() == 0 && runtime.queued() == 0);
        if (confirmed) {
            try {
                ledger.release(List.copyOf(reconciling), instance, now);
            } catch (RuntimeException storage) {
                return;
            }
            reconciling.clear();
            while (queue.held() > 0) {
                queue.release();
            }
            return;
        }
        if (runtime.state() != RuntimeStatus.State.READY && runtime.state() != RuntimeStatus.State.STARTING) {
            String code = runtime.reasonCode() == null ? "runtime-unavailable" : runtime.reasonCode();
            for (Scheduled task : queue.waiting()) {
                if (queue.remove(task)) {
                    endWaiting(task, TaskResult.Status.UNAVAILABLE, code, now);
                }
            }
        }
    }

    private void preempt(Instant now) {
        queue.preemptionVictim(now).ifPresent(victim -> {
            queue.markPreempting(victim);
            victim.preempted = true;
            try {
                ledger.markPreempted(victim.taskId(), instance);
            } catch (RuntimeException storage) {
                // The mark is diagnostic; the interruption itself must not wait for it.
            }
            if (victim.portHandle != null) {
                victim.portHandle.cancel();
            }
        });
    }

    // ---------------------------------------------------------------- account backoff

    /** Suspend background work after an account condition; clear the backoff after a completed task. */
    private void account(TaskResult result) {
        Instant now = clock.instant();
        TaskLedger.Suspension current = suspension;
        String condition = result.errorCode() == null ? null : ACCOUNT_CONDITIONS.get(result.errorCode());
        TaskLedger.Suspension next;
        if (condition != null) {
            int consecutive = Math.min(64, current.consecutive() + 1);
            Instant until = now.plus(backoff(consecutive));
            if (current.until() != null && current.until().isAfter(until)) {
                until = current.until();
            }
            next = new TaskLedger.Suspension(until, condition, consecutive);
        } else if (result.status() == TaskResult.Status.COMPLETED && !current.equals(TaskLedger.Suspension.NONE)) {
            next = TaskLedger.Suspension.NONE;
        } else {
            return;
        }
        suspension = next;
        try {
            ledger.saveSuspension(next, now);
        } catch (RuntimeException storage) {
            // Kept in memory; the next condition or completion writes it again.
            LOG.log(System.Logger.Level.WARNING, "inference ledger write failed: " + storage.getClass().getName());
        }
    }

    /**
     * @param consecutive conditions in a row, at least 1
     * @return {@code backoffInitial * 2^(consecutive-1)} with ±20% jitter, at most {@code backoffMax}
     */
    Duration backoff(int consecutive) {
        long base = settings.backoffInitial().toMillis();
        long max = settings.backoffMax().toMillis();
        long delay = base;
        for (int step = 1; step < consecutive && delay < max; step++) {
            delay = Math.min(max, delay * 2);
        }
        double factor = 0.8 + 0.4 * Math.min(Math.max(jitter.getAsDouble(), 0.0), 1.0);
        return Duration.ofMillis(Math.min(max, Math.max(1, Math.round(delay * factor))));
    }

    private static boolean suspended(TaskLedger.Suspension backoff, Instant now) {
        return backoff.until() != null && now.isBefore(backoff.until());
    }

    // ---------------------------------------------------------------- journal

    /** Write-ahead journal given to the port: record the reference before the runtime start. */
    private boolean recordStart(UUID taskId, String reference) {
        if (reference == null || !DispatchJournal.REFERENCE.matcher(reference).matches()) {
            return false;
        }
        try {
            return ledger.recordReference(taskId, reference);
        } catch (RuntimeException storage) {
            return false;
        }
    }

    private void publishCounts() {
        int interactive = 0;
        int background = 0;
        for (Scheduled task : queue.waiting()) {
            if (task.workClass().background()) {
                background++;
            } else {
                interactive++;
            }
        }
        counts = new Counts(interactive, background, queue.active().size(), reconciling.size());
    }

    // ---------------------------------------------------------------- handle

    /** One offered task: the queue item and the caller's handle. */
    private final class Scheduled implements FairQueue.Item, TaskHandle {
        private final ScheduledTask task;
        private final FairQueue.Line line;
        private final long sequence;
        private final long estimate;
        private final Instant enqueuedAt;
        private final Instant deadline;
        private final CompletableFuture<TaskResult> future = new CompletableFuture<>();
        private volatile boolean cancelRequested;
        // Drain-confined.
        private TaskHandle portHandle;
        private boolean preempted;

        private Scheduled(ScheduledTask task, long sequence, Instant enqueuedAt, Instant deadline) {
            this.task = task;
            this.line = FairQueue.Line.of(task.scope());
            this.sequence = sequence;
            this.estimate = task.estimatedUsage();
            this.enqueuedAt = enqueuedAt;
            this.deadline = deadline;
        }

        @Override
        public UUID taskId() {
            return task.taskId();
        }

        @Override
        public FairQueue.Line line() {
            return line;
        }

        @Override
        public WorkClass workClass() {
            return task.workClass();
        }

        @Override
        public long sequence() {
            return sequence;
        }

        @Override
        public Instant enqueuedAt() {
            return enqueuedAt;
        }

        @Override
        public CompletionStage<TaskResult> result() {
            return future.minimalCompletionStage();
        }

        /**
         * Request cancellation: a waiting task ends CANCELLED at once; a running one is interrupted
         * by the port, whose confirmed terminal status is reported. Never blocks.
         */
        @Override
        public boolean cancel() {
            if (future.isDone()) {
                return false;
            }
            cancelRequested = true;
            events.add(() -> cancelled(this));
            pump();
            return true;
        }
    }
}
