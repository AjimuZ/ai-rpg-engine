package org.rpgengine.inference.codex;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One RPC connection ("process generation") to the runtime: a single reader thread, a
 * concurrent pending-request registry and exact per-turn inboxes (docs/06 section 5,
 * extending the sequential P01.4 client and the P01.7 turn router to concurrent callers).
 *
 * <p>Rules:
 * <ul>
 *   <li>Request identifiers increase monotonically within the generation; at most
 *       {@link #MAX_PENDING_REQUESTS} are outstanding. Writes are serialized.</li>
 *   <li>A response must match a pending request. A correlated error resolves its request and
 *       leaves the generation usable; a request without a correlated response (timeout,
 *       interruption, write failure) is unresolved and closes the generation, because upstream
 *       work may still execute and a late response must never be attributed to another call.</li>
 *   <li>Server-initiated requests (approvals, tool calls, elicitation) are always denied with
 *       JSON-RPC {@code -32601}; one that names a registered turn also fails that turn with
 *       {@code unexpected-server-request} (ADR 0013).</li>
 *   <li>Notifications are routed by thread <em>and</em> turn identity. While a
 *       {@code turn/start} is in flight on a thread, events of not-yet-registered turns on that
 *       thread are buffered in arrival order (at most {@link #MAX_EARLY_EVENTS}; overflow closes
 *       the generation) and handed to the turn when its acknowledgment registers it. Events of
 *       retired turns are counted as late and discarded; other unknown turns are counted as
 *       ignored. Events without turn identity are diagnostics and never reach an inbox; a
 *       {@code turn/*} or {@code item/*} event without identity is a protocol violation.</li>
 *   <li>Any terminal wire error or protocol violation closes the generation for good: pending
 *       requests and inboxes fail with the code and buffered events are discarded. Callers then
 *       reconcile through a new generation; nothing is replayed here.</li>
 * </ul>
 */
final class RpcGeneration {
    static final int MAX_PENDING_REQUESTS = 8;
    static final int MAX_EARLY_EVENTS = 256;
    static final int MAX_RETIRED_TURNS = 64;
    private static final int MAX_IDENTIFIER = 128;

    private final long number;
    private final FrameWire wire;
    private final JsonMapper json;
    private final Object lock = new Object();
    private final Object writeLock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new HashMap<>();
    private final Map<TurnKey, TurnInbox> inboxes = new HashMap<>();
    private final Map<String, Integer> startsInFlight = new HashMap<>();
    private final ArrayDeque<Early> early = new ArrayDeque<>();
    private final LinkedHashSet<TurnKey> retired = new LinkedHashSet<>();
    private final Counters counters = new Counters();
    private volatile String failure;
    private Thread reader;

    private record Early(TurnKey key, RpcEvent event) {
    }

    /** Mutable diagnostics, guarded by the generation lock; contain counts only. */
    static final class Counters {
        long notifications;
        long unscoped;
        long ignored;
        long late;
        long early;
        long deniedServerRequests;
        long discarded;
    }

    /**
     * @param number process generation number, for diagnostics
     * @param wire connected wire; owned by this generation from now on
     * @param json strict mapper (duplicate keys rejected)
     */
    RpcGeneration(long number, FrameWire wire, JsonMapper json) {
        this.number = number;
        this.wire = wire;
        this.json = json;
    }

    /** Start the single reader thread; call once before the first request. */
    void start() {
        reader = Thread.ofPlatform().daemon().name("codex-reader-" + number).start(this::readLoop);
    }

    /** @return the generation number */
    long number() {
        return number;
    }

    /** @return the code that closed this generation, or null while it is usable */
    String failure() {
        return failure;
    }

    /** @return a snapshot of the routing counters */
    Counters counters() {
        synchronized (lock) {
            Counters copy = new Counters();
            copy.notifications = counters.notifications;
            copy.unscoped = counters.unscoped;
            copy.ignored = counters.ignored;
            copy.late = counters.late;
            copy.early = counters.early;
            copy.deniedServerRequests = counters.deniedServerRequests;
            copy.discarded = counters.discarded;
            return copy;
        }
    }

    /**
     * Send one request and wait for its correlated response.
     *
     * @param method protocol method
     * @param params request parameters
     * @param timeout response deadline
     * @return the {@code result} member of the response
     * @throws ProtocolException {@code runtime-rpc-error} for a correlated error (generation stays
     *         usable), {@code request-limit} when too many requests are outstanding (nothing was
     *         sent), or the generation's failure code ({@code request-unresolved} after a timeout)
     */
    JsonNode request(String method, ObjectNode params, Duration timeout) throws ProtocolException {
        long id = sequence.incrementAndGet();
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        synchronized (lock) {
            requireOpen();
            if (pending.size() >= MAX_PENDING_REQUESTS) {
                throw new ProtocolException("request-limit");
            }
            pending.put(id, response);
        }
        try {
            ObjectNode message = json.createObjectNode();
            message.put("id", id);
            message.put("method", method);
            message.set("params", params);
            send(message);
            JsonNode reply = response.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (reply.has("error")) {
                throw new ProtocolException("runtime-rpc-error");
            }
            return reply.get("result");
        } catch (TimeoutException e) {
            closeGeneration("request-unresolved");
            throw new ProtocolException("request-unresolved");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeGeneration("request-unresolved");
            throw new ProtocolException("request-unresolved");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof ProtocolException protocol ? protocol
                    : new ProtocolException("request-unresolved");
        } finally {
            synchronized (lock) {
                pending.remove(id);
            }
        }
    }

    /**
     * Send one notification (no response).
     *
     * @throws ProtocolException when the generation is closed or the write fails
     */
    void notify(String method, ObjectNode params) throws ProtocolException {
        requireOpen();
        ObjectNode message = json.createObjectNode();
        message.put("method", method);
        message.set("params", params);
        send(message);
    }

    private void requireOpen() throws ProtocolException {
        String code = failure;
        if (code != null) {
            throw new ProtocolException(code);
        }
    }

    private void send(ObjectNode message) throws ProtocolException {
        byte[] frame = json.writeValueAsBytes(message);
        synchronized (writeLock) {
            requireOpen();
            try {
                wire.write(frame);
            } catch (WireException e) {
                closeGeneration(e.code());
                throw new ProtocolException(e.code());
            }
        }
    }

    /**
     * Mark a {@code turn/start} on {@code threadId} as in flight, so its early events are kept.
     * Pair every call with {@link #endTurnStart(String)}.
     */
    void beginTurnStart(String threadId) {
        synchronized (lock) {
            startsInFlight.merge(threadId, 1, Integer::sum);
        }
    }

    /**
     * End an in-flight start. When none remains on the thread, early events nobody claimed are
     * counted as ignored and dropped.
     */
    void endTurnStart(String threadId) {
        synchronized (lock) {
            if (startsInFlight.merge(threadId, -1, Integer::sum) <= 0) {
                startsInFlight.remove(threadId);
                for (Iterator<Early> it = early.iterator(); it.hasNext();) {
                    if (it.next().key().threadId().equals(threadId)) {
                        it.remove();
                        counters.ignored++;
                    }
                }
            }
        }
    }

    /**
     * Register an acknowledged turn and hand it every buffered early event, in arrival order.
     *
     * @return the turn's inbox
     * @throws ProtocolException the generation's failure, or {@code duplicate-turn}
     */
    TurnInbox openTurn(TurnKey key) throws ProtocolException {
        synchronized (lock) {
            requireOpen();
            if (inboxes.containsKey(key)) {
                throw new ProtocolException("duplicate-turn");
            }
            TurnInbox inbox = new TurnInbox(key);
            inboxes.put(key, inbox);
            retired.remove(key);
            for (Iterator<Early> it = early.iterator(); it.hasNext();) {
                Early item = it.next();
                if (item.key().equals(key)) {
                    inbox.offer(item.event());
                    it.remove();
                }
            }
            return inbox;
        }
    }

    /** Unregister a turn; its later events are counted as late and discarded. */
    void closeTurn(TurnInbox inbox) {
        synchronized (lock) {
            if (inboxes.remove(inbox.key(), inbox)) {
                retired.add(inbox.key());
                if (retired.size() > MAX_RETIRED_TURNS) {
                    retired.remove(retired.iterator().next());
                }
            }
        }
    }

    /**
     * Close this generation for good with {@code code}: pending requests and inboxes fail and
     * buffered early events are discarded. Idempotent; the first code wins. The wire itself is
     * released by {@link #close()}.
     */
    void closeGeneration(String code) {
        List<CompletableFuture<JsonNode>> requests;
        List<TurnInbox> turns;
        synchronized (lock) {
            if (failure != null) {
                return;
            }
            failure = code;
            requests = List.copyOf(pending.values());
            turns = List.copyOf(inboxes.values());
            counters.discarded += early.size();
            early.clear();
        }
        ProtocolException cause = new ProtocolException(code);
        requests.forEach(request -> request.completeExceptionally(cause));
        turns.forEach(turn -> turn.fail(code));
    }

    /**
     * Close the generation (code {@code generation-closed} unless already failed), release the
     * wire and wait briefly for the reader.
     *
     * @return the wire's shutdown record
     */
    ShutdownRecord close() {
        closeGeneration("generation-closed");
        ShutdownRecord record = wire.close();
        if (reader != null) {
            try {
                reader.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return record;
    }

    private void readLoop() {
        while (failure == null) {
            byte[] frame;
            try {
                frame = wire.read();
            } catch (WireException e) {
                closeGeneration(e.code());
                return;
            }
            try {
                dispatch(frame);
            } catch (ProtocolException e) {
                closeGeneration(e.code());
                return;
            }
        }
    }

    private JsonNode parse(byte[] frame) throws ProtocolException {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(frame));
            return json.readTree(frame);
        } catch (CharacterCodingException | JacksonException e) {
            throw new ProtocolException("invalid-json");
        }
    }

    private void dispatch(byte[] frame) throws ProtocolException {
        JsonNode value = parse(frame);
        if (value == null || !value.isObject()) {
            throw new ProtocolException("invalid-rpc-message");
        }
        if (!value.has("method")) {
            JsonNode id = value.get("id");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong()) {
                throw new ProtocolException("unexpected-rpc-id");
            }
            if (value.has("result") == value.has("error")) {
                throw new ProtocolException("invalid-rpc-response");
            }
            CompletableFuture<JsonNode> request;
            synchronized (lock) {
                request = pending.get(id.longValue());
            }
            if (request == null) {
                throw new ProtocolException("unexpected-rpc-id");
            }
            request.complete(value);
            return;
        }
        JsonNode method = value.get("method");
        if (!method.isString() || method.stringValue().isEmpty() || value.has("result") || value.has("error")) {
            throw new ProtocolException("invalid-rpc-method");
        }
        JsonNode params = value.has("params") ? value.get("params") : json.createObjectNode();
        if (!params.isObject()) {
            throw new ProtocolException("invalid-rpc-params");
        }
        if (value.has("id")) {
            denyServerRequest(value.get("id"), params);
            return;
        }
        route(new RpcEvent(method.stringValue(), params));
    }

    private void denyServerRequest(JsonNode id, JsonNode params) throws ProtocolException {
        if (!(id.isIntegralNumber() && id.canConvertToLong()) && !id.isString()) {
            throw new ProtocolException("invalid-rpc-id");
        }
        ObjectNode denial = json.createObjectNode();
        denial.set("id", id);
        ObjectNode error = denial.putObject("error");
        error.put("code", -32601);
        error.put("message", "Unsupported by engine");
        TurnInbox inbox;
        synchronized (lock) {
            counters.deniedServerRequests++;
            inbox = inboxes.get(new TurnKey(params.path("threadId").asString(""), params.path("turnId").asString("")));
        }
        send(denial);
        if (inbox != null) {
            inbox.fail("unexpected-server-request");
        }
    }

    private void route(RpcEvent event) throws ProtocolException {
        String method = event.method();
        JsonNode params = event.params();
        JsonNode turnId = method.equals("turn/started") || method.equals("turn/completed")
                ? params.path("turn").get("id") : params.get("turnId");
        synchronized (lock) {
            counters.notifications++;
            if (turnId == null || turnId.isNull()) {
                // Malformed lifecycle events must not masquerade as diagnostics.
                if (method.startsWith("turn/") || method.startsWith("item/")) {
                    throw new ProtocolException("invalid-turn-event");
                }
                counters.unscoped++;
                return;
            }
            TurnKey key = new TurnKey(identifier(params.get("threadId")), identifier(turnId));
            TurnInbox inbox = inboxes.get(key);
            if (inbox != null) {
                inbox.offer(event);
            } else if (retired.contains(key)) {
                counters.late++;
            } else if (startsInFlight.containsKey(key.threadId())) {
                if (early.size() >= MAX_EARLY_EVENTS) {
                    throw new ProtocolException("notification-overflow");
                }
                early.addLast(new Early(key, event));
                counters.early++;
            } else {
                counters.ignored++;
            }
        }
    }

    private static String identifier(JsonNode value) throws ProtocolException {
        if (value == null || !value.isString() || value.stringValue().isEmpty()
                || value.stringValue().length() > MAX_IDENTIFIER) {
            throw new ProtocolException("invalid-turn-event");
        }
        return value.stringValue();
    }
}
