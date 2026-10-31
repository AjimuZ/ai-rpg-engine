package org.rpgengine.inference.local;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.rpgengine.inference.CompletedTaskHandle;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.RuntimeStatus;
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
 * Adapter of the packaged llama.cpp model server behind the runtime-neutral {@link NarrationTaskPort}
 * (P21.3-B, ADR 0021 decision 5).
 *
 * <p>Admission and concurrency match the runtime adapter: submissions enter one bounded FIFO queue and
 * a single dispatcher thread sends one request at a time. A full queue rejects immediately
 * ({@code queue-full}); while the server is unusable, submissions and queued tasks end UNAVAILABLE
 * with the readiness reason.
 *
 * <p>Readiness: the dispatcher reads the server's model list when it starts, while idle and before
 * every task. READY means that list parsed; it does not prove any model can load or answer.
 *
 * <p>Per task: {@link ProfileResolver} resolves the slot afresh to a pair that must be in that list
 * (and the owner's allowlist, when set). The request names the model, effort, reply limit, sampling
 * and output schema explicitly, so nothing is inherited from an earlier task or the server's defaults.
 * The reply is returned as untrusted text: the contract runner validates it exactly as it validates
 * any other adapter's output, and this class never judges its content.
 *
 * <p>Sessions: the server keeps no conversation, so this adapter keeps one per logical session, in
 * memory only: the earlier instructions and replies of completed tasks, sent again with each request.
 * {@link #retireSession} forgets it, so the next task of that session starts with an empty history and
 * reports {@code freshSession}. A task already running keeps its detached conversation. The bounds on
 * sessions and bytes only cost continuity, in the same way.
 *
 * <p>Uncertainty: closing the connection is the only stop signal the chat API offers, so a cancelled
 * or timed-out request ends CANCELLED or TIMED_OUT once the exchange is aborted, without confirmation
 * that the server stopped generating. A later request then waits behind that work at the server, and
 * nothing it produces can reach the engine. A connection lost after sending ends UNRESOLVED. Since an
 * abandoned exchange leaves no conversation at the server, there is nothing to reconcile, and this
 * adapter ignores {@link #attachJournal} and {@link #reconcile}: after a restart the scheduler holds its
 * permit until this adapter is READY and idle.
 *
 * <p>The HTTP client follows no redirect and uses no proxy: the configured server is the only peer.
 */
final class LocalTaskAdapter implements NarrationTaskPort, SmartLifecycle {
    static final String RUNTIME = "local";
    /** Longest single wait of the dispatcher, so cancellation and shutdown are noticed promptly. */
    static final long POLL_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /** What one session has said so far; confined to the dispatcher once fetched from {@link #sessions}. */
    private static final class Conversation {
        private final List<LocalProtocol.Message> messages = new ArrayList<>();
        private long bytes;

        void clear() {
            messages.clear();
            bytes = 0;
        }

        void add(String role, String content) {
            LocalProtocol.Message message = new LocalProtocol.Message(role, content);
            messages.add(message);
            bytes += message.bytes();
        }
    }

    private final LocalSettings settings;
    private final ProfileResolver profiles;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final ArrayBlockingQueue<Job> queue;
    private final Map<UUID, Job> live = new ConcurrentHashMap<>();
    private final Object admission = new Object();
    private final Map<UUID, Conversation> sessions;
    private volatile RuntimeStatus.State state = RuntimeStatus.State.STOPPED;
    private volatile String reason = "not-started";
    private volatile boolean stopping;
    private volatile Job active;
    private volatile Thread dispatcher;

    // Dispatcher-confined state, apart from the client's creation before the dispatcher starts.
    private HttpClient client;
    private Set<String> catalog = Set.of();
    private long backoffMillis;
    private long nextCheck;

    /**
     * @param settings validated settings
     * @param profiles resolves each task's slot to explicit settings
     */
    LocalTaskAdapter(LocalSettings settings, ProfileResolver profiles) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.queue = new ArrayBlockingQueue<>(settings.queueCapacity());
        int maxSessions = settings.maxSessions();
        this.sessions = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Conversation> eldest) {
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

    /** Forget the conversation of {@code sessionId}; idempotent. */
    @Override
    public void retireSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        synchronized (sessions) {
            sessions.remove(sessionId);
        }
    }

    /** @return non-sensitive readiness and queue counts; {@code active} is 1 while a request runs */
    @Override
    public RuntimeStatus status() {
        RuntimeStatus.State current = state;
        return new RuntimeStatus(RUNTIME, current, current == RuntimeStatus.State.READY ? null : reason, queue.size(),
                active != null ? 1 : 0);
    }

    // ---------------------------------------------------------------- lifecycle

    /** Start the dispatcher; tasks queue until the first catalog read ends. */
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
            nextCheck = System.nanoTime();
            client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY)
                    .connectTimeout(settings.requestTimeout()).build();
            dispatcher = Thread.ofPlatform().daemon().name("local-model-dispatcher").start(this::dispatch);
        }
    }

    /** Stop admissions, cancel queued tasks and abort the running request; bounded by the catalog deadline. */
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
            worker.join(settings.requestTimeout().toMillis() + 1000);
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(2000);
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
                if (System.nanoTime() - nextCheck >= 0) {
                    String problem = refreshCatalog();
                    if (problem != null) {
                        unavailable(problem);
                        continue;
                    }
                    nextCheck = System.nanoTime() + settings.catalogInterval().toNanos();
                    ready();
                }
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
            client.shutdownNow();
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

    /**
     * Read the server's model list into {@link #catalog}.
     *
     * @return null on success, otherwise the fixed reason the server cannot be used now
     */
    private String refreshCatalog() throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(settings.uri(LocalProtocol.MODELS_PATH))
                .timeout(settings.requestTimeout()).GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request,
                info -> new BoundedBody(LocalProtocol.MAX_RESPONSE_BYTES));
        try {
            while (!stopping) {
                try {
                    HttpResponse<byte[]> response = pending.get(POLL_SLICE_NANOS, TimeUnit.NANOSECONDS);
                    if (response.statusCode() == 503) {
                        return "model-server-starting";
                    }
                    if (response.statusCode() != 200) {
                        return "model-server-error";
                    }
                    catalog = LocalProtocol.parseModels(json, response.body());
                    return null;
                } catch (TimeoutException slice) {
                    continue;
                } catch (ExecutionException failed) {
                    return "runtime-unreachable";
                } catch (LocalProtocol.Refused refused) {
                    return refused.code();
                }
            }
            return "stopping";
        } finally {
            pending.cancel(true);
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
        // Read for every task, so a model the owner added or removed counts at once.
        String problem = refreshCatalog();
        if (problem != null) {
            nextCheck = System.nanoTime();
            finish(job, TaskResult.withoutTurn(id, Status.UNAVAILABLE, problem));
            return;
        }
        // Resolved afresh for every task: nothing is inherited from an earlier one.
        Set<String> models = catalog;
        ProfileResolver.Resolution resolution = profiles.resolve(request.slot(), RUNTIME,
                candidate -> models.contains(candidate.model()));
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
        Conversation conversation;
        synchronized (sessions) {
            conversation = sessions.computeIfAbsent(request.sessionId(), session -> new Conversation());
        }
        long next = new LocalProtocol.Message("user", request.instructions()).bytes();
        if (conversation.bytes + next > settings.maxConversationBytes()) {
            conversation.clear();
        }
        boolean fresh = conversation.messages.isEmpty();
        TaskResult.Provenance provenance = new TaskResult.Provenance(RUNTIME, LocalProtocol.SERVER_BUILD,
                effective.model(), effective.effort(), resolved.degradedFrom());
        byte[] body = json.writeValueAsBytes(LocalProtocol.chatBody(json, effective, conversation.messages,
                request.instructions(), schema, maxTokens(resolved.slot()), settings));
        HttpRequest http = HttpRequest.newBuilder(settings.uri(LocalProtocol.CHAT_PATH))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        TaskResult result = exchange(job, http, provenance, fresh);
        if (result.status() == Status.COMPLETED) {
            // On a retired session this is a detached object: the retirement stands.
            conversation.add("user", request.instructions());
            conversation.add("assistant", result.output());
        }
        finish(job, result);
    }

    /** @return the reply limit of a slot, equal to the output allowance the session registry reserves */
    private static int maxTokens(InferenceSlot slot) {
        return switch (slot) {
            case LIGHT -> 1_024;
            case STANDARD -> 2_048;
            case DELIBERATE -> 4_096;
        };
    }

    /** Send one chat request and wait for its result, aborting it on cancellation, deadline or shutdown. */
    private TaskResult exchange(Job job, HttpRequest http, TaskResult.Provenance provenance, boolean fresh)
            throws InterruptedException {
        UUID id = job.request.taskId();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(http,
                info -> new BoundedBody(LocalProtocol.MAX_RESPONSE_BYTES));
        try {
            while (true) {
                if (stopping || job.cancelRequested) {
                    return new TaskResult(id, Status.CANCELLED, null, stopping ? "shutdown" : "cancelled", provenance,
                            fresh);
                }
                if (System.nanoTime() - job.deadline >= 0) {
                    return new TaskResult(id, Status.TIMED_OUT, null, "timeout", provenance, fresh);
                }
                try {
                    return reply(id, pending.get(POLL_SLICE_NANOS, TimeUnit.NANOSECONDS), provenance, fresh);
                } catch (TimeoutException slice) {
                    continue;
                } catch (ExecutionException failed) {
                    return failure(id, failed.getCause(), provenance, fresh);
                }
            }
        } finally {
            pending.cancel(true);
        }
    }

    private TaskResult reply(UUID id, HttpResponse<byte[]> response, TaskResult.Provenance provenance, boolean fresh) {
        int code = response.statusCode();
        if (code == 503) {
            // Loading or busy: the server took no work from this request.
            nextCheck = System.nanoTime();
            return TaskResult.withoutTurn(id, Status.UNAVAILABLE, "model-server-busy");
        }
        if (code >= 500) {
            return new TaskResult(id, Status.FAILED, null, "model-server-error", provenance, fresh);
        }
        if (code != 200) {
            // Provenance makes the registry retire the session, because an oversized history is a likely cause.
            return new TaskResult(id, Status.FAILED, null, "turn-rejected", provenance, fresh);
        }
        try {
            return new TaskResult(id, Status.COMPLETED, LocalProtocol.parseCompletion(json, response.body()), null,
                    provenance, fresh);
        } catch (LocalProtocol.Refused refused) {
            return new TaskResult(id, Status.FAILED, null, refused.code(), provenance, fresh);
        }
    }

    private TaskResult failure(UUID id, Throwable cause, TaskResult.Provenance provenance, boolean fresh) {
        if (causedBy(cause, BoundedBody.TooLarge.class)) {
            return new TaskResult(id, Status.FAILED, null, "response-too-large", provenance, fresh);
        }
        if (causedBy(cause, ConnectException.class) || causedBy(cause, HttpConnectTimeoutException.class)) {
            // No connection, so nothing was sent.
            nextCheck = System.nanoTime();
            return TaskResult.withoutTurn(id, Status.UNAVAILABLE, "runtime-unreachable");
        }
        nextCheck = System.nanoTime();
        return new TaskResult(id, Status.UNRESOLVED, null, "connection-lost", provenance, fresh);
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
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
