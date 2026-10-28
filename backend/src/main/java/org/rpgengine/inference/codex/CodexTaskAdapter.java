package org.rpgengine.inference.codex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.rpgengine.inference.CompletedTaskHandle;
import org.rpgengine.inference.DispatchJournal;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.TaskResult.Status;
import org.springframework.context.SmartLifecycle;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Codex app-server adapter behind the runtime-neutral {@link NarrationTaskPort} (P08.1).
 *
 * <p>Admission and concurrency: submissions enter one bounded FIFO queue; a single dispatcher
 * thread holds the installation-wide active-turn permit and runs one turn at a time, the
 * serialized policy selected by P01.7. Engine work arrives through the P08.6 fair scheduler,
 * which forwards one task at a time and owns priorities, quotas and the durable ledger, so this
 * queue is a hand-off. A full queue rejects immediately ({@code queue-full}); while the runtime
 * is not ready, submissions and queued tasks end UNAVAILABLE with the readiness reason.
 *
 * <p>Lifecycle: the dispatcher connects (owned stdio child or packaged socket), performs
 * {@code initialize}/{@code initialized}, reads account state and drains the model catalog.
 * The adapter is READY only with a present account and the pinned version. A lost generation is
 * replaced with exponential backoff; logical sessions bound to threads of an earlier generation
 * are resumed ({@code thread/resume}) before their next turn.
 *
 * <p>Per task: {@link ProfileResolver} resolves the slot through the owner's assignment to explicit
 * settings that must exist in the discovered catalog (and the owner's allowlist, when set);
 * the logical session maps to a persistent read-only thread (started on first use); the turn
 * is started with explicit model, effort, approval, sandbox and output schema; its events are
 * correlated by exact thread and turn identity; output is returned only after a confirmed
 * {@code completed} status. Cancellation, deadline expiry and shutdown interrupt the exact turn
 * and wait for its terminal status.
 *
 * <p>Uncertainty: when a turn's outcome cannot be confirmed (lost generation, unacknowledged
 * interrupt, protocol or isolation violation) the task ends UNRESOLVED or FAILED, the thread is
 * marked ambiguous and its session binding retired, and the dispatcher keeps the permit until
 * reconciliation (resume, list newest turns, interrupt anything in progress, await terminal)
 * succeeds. While any thread is unconfirmed, {@link #status()} reports one active turn, so the
 * scheduler keeps its permit too.
 *
 * <p>Restart (P08.6): before {@code turn/start} the thread identifier is handed to the attached
 * {@link DispatchJournal}, which records it durably; when the record fails, no turn is started
 * ({@code dispatch-journal-unavailable}). After a backend restart the scheduler passes the
 * recorded identifiers to {@link #reconcile}; they join the ambiguous threads and are reconciled
 * on the first connection before any task runs, so a turn left running by the previous process is
 * interrupted and confirmed instead of overlapping new work.
 *
 * <p>No type of this package is visible outside it; callers see only neutral records.
 */
final class CodexTaskAdapter implements NarrationTaskPort, SmartLifecycle {
    static final String RUNTIME = "codex";
    /** Longest single wait of the dispatcher, so cancellation and shutdown are noticed promptly. */
    static final long POLL_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /** Opens one new wire per process generation. */
    @FunctionalInterface
    interface WireFactory {
        /**
         * @return a connected wire
         * @throws WireException with a fixed code when the runtime cannot be reached
         */
        FrameWire open() throws WireException;
    }

    private record Binding(String threadId, long generation) {
    }

    private final CodexSettings settings;
    private final ProfileResolver profiles;
    private final WireFactory wires;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final ArrayBlockingQueue<Job> queue;
    private final Map<UUID, Job> live = new ConcurrentHashMap<>();
    private final Object admission = new Object();
    private final Map<UUID, Binding> sessions;
    private volatile RuntimeStatus.State state = RuntimeStatus.State.STOPPED;
    private volatile String reason = "not-started";
    private volatile boolean stopping;
    private volatile Job active;
    private volatile Thread dispatcher;
    private volatile ShutdownRecord lastShutdown;
    private volatile DispatchJournal journal = DispatchJournal.NONE;
    /** Thread identifiers recorded by an earlier process, waiting for the dispatcher to reconcile them. */
    private final Set<String> restartThreads = ConcurrentHashMap.newKeySet();
    /** Mirror of {@code ambiguousThreads.size()} for {@link #status()}; written by the dispatcher only. */
    private volatile int ambiguousCount;

    // Dispatcher-confined state.
    private RpcGeneration generation;
    private long generations;
    private CodexProtocol.Catalog catalog;
    private final Set<String> ambiguousThreads = new LinkedHashSet<>();
    private long backoffMillis;

    /**
     * @param settings validated settings
     * @param profiles resolves each task's slot to explicit settings (P08.4)
     * @param wires wire factory for each new generation
     */
    CodexTaskAdapter(CodexSettings settings, ProfileResolver profiles, WireFactory wires) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.wires = Objects.requireNonNull(wires, "wires");
        this.queue = new ArrayBlockingQueue<>(settings.queueCapacity());
        int maxSessions = settings.maxSessions();
        this.sessions = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Binding> eldest) {
                // Forgetting a binding only costs continuity: the next task starts a fresh
                // thread and reports freshSession, and the caller rebuilds from a checkpoint.
                return size() > maxSessions;
            }
        };
    }

    // ---------------------------------------------------------------- port

    /** Admit a task without blocking; see {@link NarrationTaskPort#submit}. */
    @Override
    public TaskHandle submit(TaskRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (admission) {
            RuntimeStatus.State current = state;
            if (stopping || current == RuntimeStatus.State.STOPPING || current == RuntimeStatus.State.STOPPED) {
                return rejected(request, Status.REJECTED, "not-running");
            }
            if (current == RuntimeStatus.State.UNAVAILABLE) {
                return rejected(request, Status.UNAVAILABLE, reason);
            }
            Job job = new Job(request);
            if (live.putIfAbsent(request.taskId(), job) != null) {
                return rejected(request, Status.REJECTED, "duplicate-task");
            }
            if (!queue.offer(job)) {
                live.remove(request.taskId(), job);
                return rejected(request, Status.REJECTED, "queue-full");
            }
            return job;
        }
    }

    private static TaskHandle rejected(TaskRequest request, Status status, String code) {
        return new CompletedTaskHandle(TaskResult.withoutTurn(request.taskId(), status, code));
    }

    /** Forget the thread bound to {@code sessionId}; idempotent. */
    @Override
    public void retireSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        synchronized (sessions) {
            sessions.remove(sessionId);
        }
    }

    /**
     * @return non-sensitive readiness and queue counts; {@code active} is 1 while a turn runs or
     *         any thread awaits reconciliation (including identifiers handed over by
     *         {@link #reconcile} and not yet confirmed)
     */
    @Override
    public RuntimeStatus status() {
        RuntimeStatus.State current = state;
        boolean busy = active != null || ambiguousCount > 0 || !restartThreads.isEmpty();
        return new RuntimeStatus(RUNTIME, current, current == RuntimeStatus.State.READY ? null : reason,
                queue.size(), busy ? 1 : 0);
    }

    /** Use {@code journal} for the write-ahead record of every later turn start. */
    @Override
    public void attachJournal(DispatchJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    /**
     * Treat thread identifiers recorded by an earlier process as ambiguous: the dispatcher resumes
     * each one, lists its newest turns and interrupts and confirms any still in progress before it
     * runs another task. Malformed identifiers cannot have been recorded and are ignored.
     */
    @Override
    public void reconcile(Collection<String> references) {
        Objects.requireNonNull(references, "references");
        for (String reference : references) {
            if (reference != null && DispatchJournal.REFERENCE.matcher(reference).matches()) {
                restartThreads.add(reference);
            }
        }
    }

    /** @return how the last generation's wire ended, or null; for diagnostics and tests */
    ShutdownRecord lastShutdown() {
        return lastShutdown;
    }

    // ---------------------------------------------------------------- lifecycle

    /** Start the dispatcher; tasks queue while it connects. */
    @Override
    public void start() {
        synchronized (admission) {
            if (dispatcher != null) {
                return;
            }
            stopping = false;
            state = RuntimeStatus.State.STARTING;
            reason = "connecting";
            backoffMillis = settings.reconnectInitial().toMillis();
            dispatcher = Thread.ofPlatform().daemon().name("codex-dispatcher").start(this::dispatch);
        }
    }

    /**
     * Stop admissions, cancel queued tasks, interrupt the active turn and close the owned
     * connection. Bounded by the interrupt and request timeouts plus the wire shutdown.
     */
    @Override
    public void stop() {
        Thread worker;
        synchronized (admission) {
            if (dispatcher == null) {
                return;
            }
            stopping = true;
            state = RuntimeStatus.State.STOPPING;
            reason = "stopping";
            worker = dispatcher;
        }
        drainQueue(Status.CANCELLED, "shutdown");
        try {
            worker.join(settings.interruptTimeout().plus(settings.requestTimeout()).toMillis() + 1000);
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(settings.stdioGrace().multipliedBy(3).toMillis() + 2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drainQueue(Status.CANCELLED, "shutdown");
        synchronized (admission) {
            dispatcher = null;
            state = RuntimeStatus.State.STOPPED;
            reason = "stopped";
        }
    }

    /** @return whether the dispatcher is running */
    @Override
    public boolean isRunning() {
        return dispatcher != null;
    }

    // ---------------------------------------------------------------- dispatcher

    private void dispatch() {
        try {
            while (!stopping) {
                if (generation == null || generation.failure() != null) {
                    closeGeneration();
                    if (!connect()) {
                        unavailable(reason);
                        continue;
                    }
                }
                adoptRestartThreads();
                if (!ambiguousThreads.isEmpty() && !reconcile()) {
                    if (generation != null && generation.failure() == null) {
                        // Usable connection but unconfirmed turns: keep the permit and retry later.
                        unavailable("reconciliation-pending");
                    }
                    continue;
                }
                ready();
                Job job = queue.poll(250, TimeUnit.MILLISECONDS);
                if (job != null) {
                    active = job;
                    try {
                        run(job);
                    } finally {
                        active = null;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // A programming error must not leave callers waiting forever: the finally block
            // settles every task, and the status stays non-ready until a restart.
            synchronized (admission) {
                state = RuntimeStatus.State.UNAVAILABLE;
                reason = "adapter-failure";
            }
        } finally {
            Job job = active;
            if (job != null) {
                finish(job, TaskResult.withoutTurn(job.request.taskId(), Status.UNRESOLVED, "shutdown"));
            }
            drainQueue(Status.CANCELLED, "shutdown");
            closeGeneration();
        }
    }

    /** Move identifiers handed over by {@link #reconcile} into the ambiguous set; dispatcher only. */
    private void adoptRestartThreads() {
        for (String threadId : List.copyOf(restartThreads)) {
            // Count the thread as ambiguous before it leaves the pending set, so status() never dips to idle.
            ambiguousThreads.add(threadId);
            ambiguousCount = ambiguousThreads.size();
            restartThreads.remove(threadId);
        }
    }

    private void ready() {
        synchronized (admission) {
            if (!stopping) {
                state = RuntimeStatus.State.READY;
                reason = null;
            }
        }
        backoffMillis = settings.reconnectInitial().toMillis();
    }

    /** Mark unavailable, end queued tasks with {@code code} and back off before the next attempt. */
    private void unavailable(String code) throws InterruptedException {
        synchronized (admission) {
            if (!stopping) {
                state = RuntimeStatus.State.UNAVAILABLE;
                reason = code;
            }
        }
        drainQueue(Status.UNAVAILABLE, code);
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMillis);
        while (!stopping && System.nanoTime() < until) {
            Thread.sleep(Math.min(50, Math.max(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()))));
        }
        backoffMillis = Math.min(backoffMillis * 2, settings.reconnectMax().toMillis());
    }

    private void drainQueue(Status status, String code) {
        List<Job> drained = new ArrayList<>();
        queue.drainTo(drained);
        drained.forEach(job -> finish(job, TaskResult.withoutTurn(job.request.taskId(), status, code)));
    }

    private void finish(Job job, TaskResult result) {
        if (job.future.complete(result)) {
            live.remove(job.request.taskId(), job);
        }
    }

    private void closeGeneration() {
        if (generation != null) {
            lastShutdown = generation.close();
            generation = null;
            catalog = null;
        }
    }

    /** Open a generation and complete the handshake; on failure set {@link #reason}. */
    private boolean connect() {
        FrameWire wire;
        try {
            wire = wires.open();
        } catch (WireException e) {
            reason = e.code().startsWith("handshake-") || e.code().equals("runtime-launch") ? e.code()
                    : "runtime-unreachable";
            return false;
        }
        RpcGeneration candidate = new RpcGeneration(++generations, wire, json);
        candidate.start();
        try {
            CodexProtocol.validateInitialize(candidate.request("initialize", CodexProtocol.initializeParams(json),
                    settings.handshakeTimeout()));
            candidate.notify("initialized", json.createObjectNode());
            CodexProtocol.AccountState account = CodexProtocol.accountState(
                    candidate.request("account/read", CodexProtocol.accountParams(json), settings.handshakeTimeout()));
            if (account != CodexProtocol.AccountState.PRESENT) {
                reason = account == CodexProtocol.AccountState.SIGN_IN_REQUIRED ? "sign-in-required"
                        : "unsupported-auth";
                lastShutdown = candidate.close();
                return false;
            }
            Map<String, Set<String>> models = new HashMap<>();
            Set<String> cursors = new LinkedHashSet<>();
            String cursor = null;
            for (int page = 0;; page++) {
                if (page >= CodexProtocol.MAX_CATALOG_PAGES) {
                    throw new ProtocolException("catalog-page-limit");
                }
                cursor = CodexProtocol.addCatalogPage(candidate.request("model/list",
                        CodexProtocol.catalogParams(json, cursor), settings.handshakeTimeout()), models);
                if (cursor == null) {
                    break;
                }
                if (!cursors.add(cursor)) {
                    throw new ProtocolException("repeated-catalog-cursor");
                }
            }
            generation = candidate;
            catalog = new CodexProtocol.Catalog(Map.copyOf(models));
            return true;
        } catch (ProtocolException e) {
            reason = e.code().equals("runtime-rpc-error") ? "handshake-failed" : e.code();
            lastShutdown = candidate.close();
            return false;
        }
    }

    // ---------------------------------------------------------------- one task

    private void run(Job job) throws InterruptedException {
        TaskRequest request = job.request;
        UUID id = request.taskId();
        if (job.cancelRequested) {
            finish(job, TaskResult.withoutTurn(id, Status.CANCELLED, "cancelled"));
            return;
        }
        if (System.nanoTime() - job.deadline >= 0) {
            finish(job, TaskResult.withoutTurn(id, Status.TIMED_OUT, "expired-in-queue"));
            return;
        }
        // Resolved afresh for every task: nothing is inherited from an earlier turn or thread.
        ProfileResolver.Resolution resolution = profiles.resolve(request.slot(), RUNTIME, catalog::supports);
        if (!(resolution instanceof ProfileResolver.Resolved resolved)) {
            finish(job, TaskResult.withoutTurn(id, Status.FAILED, ((ProfileResolver.Refused) resolution).code()));
            return;
        }
        EffectiveSettings effective = resolved.settings();
        JsonNode schema;
        try {
            schema = json.readTree(request.outputSchema());
        } catch (JacksonException e) {
            schema = null;
        }
        if (schema == null || !schema.isObject()) {
            finish(job, TaskResult.withoutTurn(id, Status.FAILED, "invalid-output-schema"));
            return;
        }
        TaskResult.Provenance provenance = new TaskResult.Provenance(RUNTIME, CodexProtocol.SUPPORTED_VERSION,
                effective.model(), effective.effort(), resolved.degradedFrom());
        RpcGeneration current = generation;

        // Bind the logical session to a thread on this generation.
        boolean fresh;
        String threadId;
        try {
            Binding binding;
            synchronized (sessions) {
                binding = sessions.get(request.sessionId());
            }
            fresh = binding == null;
            threadId = fresh ? null : binding.threadId();
            if (binding != null && binding.generation() != current.number()) {
                try {
                    CodexProtocol.validateResume(current.request("thread/resume",
                            CodexProtocol.resumeParams(json, threadId), settings.requestTimeout()), threadId);
                } catch (ProtocolException e) {
                    if (current.failure() != null) {
                        throw e;
                    }
                    // The runtime no longer knows the thread or reports another policy: start over.
                    fresh = true;
                }
            }
            if (fresh) {
                threadId = CodexProtocol.validateThreadStart(current.request("thread/start",
                        CodexProtocol.threadStartParams(json, effective.model(), settings.runtimeCwd()),
                        settings.requestTimeout()), effective.model(), settings.runtimeCwd());
            }
            synchronized (sessions) {
                sessions.put(request.sessionId(), new Binding(threadId, current.number()));
            }
        } catch (ProtocolException e) {
            // No turn exists yet, so nothing can be running for this task.
            finish(job, TaskResult.withoutTurn(id, current.failure() != null ? Status.UNAVAILABLE : Status.FAILED,
                    e.code()));
            return;
        }

        // Write-ahead record of the thread, so a later process can reconcile a turn this one leaves.
        if (!recordStart(id, threadId)) {
            finish(job, TaskResult.withoutTurn(id, Status.FAILED, "dispatch-journal-unavailable"));
            return;
        }

        // Start the turn with every setting explicit and register its exact identity.
        TurnInbox inbox;
        current.beginTurnStart(threadId);
        try {
            String turnId = CodexProtocol.validateTurnStart(current.request("turn/start",
                    CodexProtocol.turnStartParams(json, threadId, request.instructions(), effective, schema),
                    settings.requestTimeout()));
            inbox = current.openTurn(new TurnKey(threadId, turnId));
        } catch (ProtocolException e) {
            if (current.failure() == null && e.code().equals("runtime-rpc-error")) {
                // A correlated rejection: the runtime started nothing.
                finish(job, new TaskResult(id, Status.FAILED, null, "turn-rejected", provenance, fresh));
            } else {
                // The turn may exist with an unknown identity: reconcile before any further work.
                markAmbiguous(threadId);
                finish(job, new TaskResult(id, Status.UNRESOLVED, null, e.code(), provenance, fresh));
            }
            return;
        } finally {
            current.endTurnStart(threadId);
        }
        try {
            finish(job, await(job, current, inbox, provenance, fresh));
        } finally {
            current.closeTurn(inbox);
        }
    }

    /** Await the terminal status of one registered turn, interrupting it when required. */
    private TaskResult await(Job job, RpcGeneration current, TurnInbox inbox, TaskResult.Provenance provenance,
            boolean fresh) throws InterruptedException {
        UUID id = job.request.taskId();
        TurnKey key = inbox.key();
        CodexProtocol.TurnTracker tracker = new CodexProtocol.TurnTracker();
        String interruptReason = null;
        long interruptDeadline = 0;
        try {
            while (true) {
                long now = System.nanoTime();
                if (interruptReason == null) {
                    String why = stopping ? "shutdown" : job.cancelRequested ? "cancelled"
                            : now - job.deadline >= 0 ? "timeout" : null;
                    if (why != null) {
                        interruptReason = why;
                        interruptDeadline = now + settings.interruptTimeout().toNanos();
                        interrupt(current, key);
                    }
                } else if (now - interruptDeadline >= 0) {
                    markAmbiguous(key.threadId());
                    return new TaskResult(id, Status.UNRESOLVED, null, "interrupt-unconfirmed", provenance, fresh);
                }
                long limit = interruptReason == null ? job.deadline : interruptDeadline;
                RpcEvent event = inbox.poll(Math.min(POLL_SLICE_NANOS, limit - System.nanoTime()));
                if (event == null) {
                    continue;
                }
                CodexProtocol.TurnTracker.Outcome outcome = tracker.apply(event);
                if (outcome != null) {
                    return result(id, outcome, interruptReason, provenance, fresh);
                }
            }
        } catch (ProtocolException e) {
            // The turn may still run (or its stream is unusable): retire and reconcile it.
            markAmbiguous(key.threadId());
            Status status = current.failure() != null ? Status.UNRESOLVED : Status.FAILED;
            return new TaskResult(id, status, null, e.code(), provenance, fresh);
        }
    }

    /** Send {@code turn/interrupt}; a correlated error is tolerated because the turn may have ended. */
    private void interrupt(RpcGeneration current, TurnKey key) throws ProtocolException {
        try {
            CodexProtocol.validateInterrupt(current.request("turn/interrupt", CodexProtocol.interruptParams(json, key),
                    settings.requestTimeout()));
        } catch (ProtocolException e) {
            if (current.failure() != null || !e.code().equals("runtime-rpc-error")) {
                throw e;
            }
        }
    }

    private static TaskResult result(UUID id, CodexProtocol.TurnTracker.Outcome outcome, String interruptReason,
            TaskResult.Provenance provenance, boolean fresh) {
        return switch (outcome.status()) {
            case "completed" -> new TaskResult(id, Status.COMPLETED, outcome.text(), null, provenance, fresh);
            case "failed" -> new TaskResult(id, Status.FAILED, null, outcome.errorCode(), provenance, fresh);
            default -> {
                if (interruptReason == null) {
                    yield new TaskResult(id, Status.FAILED, null, "interrupted", provenance, fresh);
                }
                yield interruptReason.equals("timeout")
                        ? new TaskResult(id, Status.TIMED_OUT, null, "timeout", provenance, fresh)
                        : new TaskResult(id, Status.CANCELLED, null, interruptReason, provenance, fresh);
            }
        };
    }

    /** @return true when the journal durably recorded the thread; a failing journal refuses the start */
    private boolean recordStart(UUID taskId, String threadId) {
        try {
            return journal.recordStart(taskId, threadId);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Record an unconfirmed thread and forget every session bound to it. */
    private void markAmbiguous(String threadId) {
        ambiguousThreads.add(threadId);
        ambiguousCount = ambiguousThreads.size();
        synchronized (sessions) {
            sessions.values().removeIf(binding -> binding.threadId().equals(threadId));
        }
    }

    /**
     * Confirm that no turn of an ambiguous thread is still running: resume it on the current
     * generation, list its newest turns, interrupt any in progress and await the terminal status
     * (or a later listing without it). A thread the runtime no longer knows has nothing running.
     *
     * @return true when every ambiguous thread is confirmed idle
     */
    private boolean reconcile() throws InterruptedException {
        RpcGeneration current = generation;
        for (String threadId : List.copyOf(ambiguousThreads)) {
            try {
                try {
                    CodexProtocol.validateResume(current.request("thread/resume",
                            CodexProtocol.resumeParams(json, threadId), settings.requestTimeout()), threadId);
                } catch (ProtocolException e) {
                    if (current.failure() == null && e.code().equals("runtime-rpc-error")) {
                        ambiguousThreads.remove(threadId);
                        ambiguousCount = ambiguousThreads.size();
                        continue;
                    }
                    throw e;
                }
                for (CodexProtocol.TurnState turn : listTurns(current, threadId)) {
                    if (turn.status().equals("inProgress") && !interruptAndConfirm(current,
                            new TurnKey(threadId, turn.turnId()))) {
                        return false;
                    }
                }
                ambiguousThreads.remove(threadId);
                ambiguousCount = ambiguousThreads.size();
            } catch (ProtocolException e) {
                return false;
            }
        }
        return true;
    }

    private List<CodexProtocol.TurnState> listTurns(RpcGeneration current, String threadId)
            throws ProtocolException {
        return CodexProtocol.parseTurns(current.request("thread/turns/list",
                CodexProtocol.turnsListParams(json, threadId), settings.requestTimeout()));
    }

    private boolean interruptAndConfirm(RpcGeneration current, TurnKey key)
            throws ProtocolException, InterruptedException {
        TurnInbox inbox = current.openTurn(key);
        try {
            interrupt(current, key);
            long deadline = System.nanoTime() + settings.interruptTimeout().toNanos();
            while (System.nanoTime() - deadline < 0 && !stopping) {
                RpcEvent event = inbox.poll(Math.min(POLL_SLICE_NANOS, deadline - System.nanoTime()));
                if (event != null && event.method().equals("turn/completed")
                        && CodexProtocol.TERMINAL_STATUSES.contains(event.params().path("turn").path("status")
                                .asString(""))) {
                    return true;
                }
            }
        } finally {
            current.closeTurn(inbox);
        }
        // The terminal event may have preceded registration: the listing is authoritative.
        return listTurns(current, key.threadId()).stream()
                .noneMatch(turn -> turn.turnId().equals(key.turnId()) && turn.status().equals("inProgress"));
    }

    // ---------------------------------------------------------------- handle

    /** Queued or running task; the caller's handle. */
    private final class Job implements TaskHandle {
        private final TaskRequest request;
        private final long deadline;
        private final CompletableFuture<TaskResult> future = new CompletableFuture<>();
        private volatile boolean cancelRequested;

        private Job(TaskRequest request) {
            this.request = request;
            this.deadline = System.nanoTime() + request.timeout().toNanos();
        }

        @Override
        public UUID taskId() {
            return request.taskId();
        }

        @Override
        public CompletionStage<TaskResult> result() {
            return future.minimalCompletionStage();
        }

        @Override
        public boolean cancel() {
            if (future.isDone()) {
                return false;
            }
            cancelRequested = true;
            if (queue.remove(this)) {
                finish(this, TaskResult.withoutTurn(request.taskId(), Status.CANCELLED, "cancelled"));
            }
            return true;
        }
    }
}
