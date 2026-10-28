package org.rpgengine.inference.codex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deterministic test double of the pinned app-server, a verification device only (ADR 0020
 * decision 10). Its result and notification shapes follow the retained 0.164.0 neutral
 * fixture. Daemon state (threads, turns, statuses) is shared by every connection, so a test can
 * drop a connection and observe resume and reconciliation on the next one, as P01.2 did with
 * the real runtime.
 *
 * <p>The scenario of a turn is selected by a token in its instruction text:
 * {@code scenario-complete} (default), {@code scenario-early} (events precede the
 * acknowledgment), {@code scenario-noise} (foreign, late, unscoped and delta events interleave),
 * {@code scenario-hold} (runs until interrupted), {@code scenario-fail}, {@code scenario-tool}
 * (a command item), {@code scenario-reroute}, {@code scenario-approval} (server request),
 * {@code scenario-eof} (connection ends mid-turn) and {@code scenario-silent} (the first
 * interrupt is acknowledged without a terminal event).
 */
final class ScriptedAppServer {
    static final String OUTPUT = "{\"text\":\"The marker is blue.\"}";
    static final JsonMapper JSON = JsonMapper.builder().build();

    /** One request received by the server. */
    record Received(long connection, String method, JsonNode params) {
    }

    private final Map<String, ThreadState> threads = new LinkedHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicLong connections = new AtomicLong();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonNode> clientReplies = Collections.synchronizedList(new ArrayList<>());
    volatile String userAgent = "codex/0.164.0";
    volatile JsonNode account = JSON.readTree("{\"account\":{\"type\":\"chatgpt\"},\"requiresOpenaiAuth\":true}");
    volatile boolean unsafeThreadPolicy;

    private static final class ThreadState {
        final String model;
        final String cwd;
        final List<TurnState> turns = new ArrayList<>();

        ThreadState(String model, String cwd) {
            this.model = model;
            this.cwd = cwd;
        }
    }

    private static final class TurnState {
        final String id;
        final String scenario;
        String status = "inProgress";
        boolean ignoredInterrupt;

        TurnState(String id, String scenario) {
            this.id = id;
            this.scenario = scenario;
        }
    }

    /** @return requests received so far, in order */
    List<Received> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    /** @return requests with {@code method} */
    List<Received> received(String method) {
        return received().stream().filter(r -> r.method().equals(method)).toList();
    }

    /** @return responses the client sent to server-initiated requests */
    List<JsonNode> clientReplies() {
        synchronized (clientReplies) {
            return List.copyOf(clientReplies);
        }
    }

    /** @return status of a turn, or null */
    synchronized String turnStatus(String threadId, String turnId) {
        ThreadState thread = threads.get(threadId);
        return thread == null ? null : thread.turns.stream().filter(t -> t.id.equals(turnId)).map(t -> t.status)
                .findFirst().orElse(null);
    }

    /** Server side of one connection; output frames go to {@code sink}, EOF to {@code eof}. */
    final class Connection {
        final long number = connections.incrementAndGet();
        private final Consumer<byte[]> sink;
        private final Runnable eof;

        Connection(Consumer<byte[]> sink, Runnable eof) {
            this.sink = sink;
            this.eof = eof;
        }

        void emit(JsonNode message) {
            sink.accept(JSON.writeValueAsBytes(message));
        }

        void notification(String method, ObjectNode params) {
            ObjectNode message = JSON.createObjectNode().put("method", method);
            message.set("params", params);
            emit(message);
        }

        void respond(JsonNode id, JsonNode result) {
            ObjectNode message = JSON.createObjectNode();
            message.set("id", id);
            message.set("result", result);
            emit(message);
        }

        void error(JsonNode id) {
            ObjectNode message = JSON.createObjectNode();
            message.set("id", id);
            message.putObject("error").put("code", -32600).put("message", "private detail /home/owner");
            emit(message);
        }

        /** Handle one client frame. */
        void handle(byte[] frame) {
            JsonNode message = JSON.readTree(frame);
            if (!message.has("method")) {
                clientReplies.add(message);
                return;
            }
            String method = message.get("method").stringValue();
            JsonNode params = message.path("params");
            received.add(new Received(number, method, params));
            if (!message.has("id")) {
                return;
            }
            JsonNode id = message.get("id");
            synchronized (ScriptedAppServer.this) {
                switch (method) {
                    case "initialize" -> respond(id, JSON.createObjectNode().put("userAgent", userAgent));
                    case "account/read" -> respond(id, account);
                    case "model/list" -> respond(id, catalogPage(params.path("cursor").asString(null)));
                    case "thread/start" -> threadStart(id, params);
                    case "thread/resume" -> threadResume(id, params);
                    case "thread/turns/list" -> turnsList(id, params);
                    case "turn/start" -> turnStart(id, params);
                    case "turn/interrupt" -> interrupt(id, params);
                    default -> error(id);
                }
            }
        }

        private void threadStart(JsonNode id, JsonNode params) {
            String threadId = "neutral-id-" + ids.incrementAndGet();
            String model = params.path("model").stringValue();
            String cwd = params.path("cwd").stringValue();
            threads.put(threadId, new ThreadState(model, cwd));
            respond(id, threadResult(threadId, model, cwd, "idle"));
        }

        private ObjectNode threadResult(String threadId, String model, String cwd, String status) {
            ObjectNode result = JSON.createObjectNode().put("model", model).put("modelProvider", "fixture")
                    .put("cwd", cwd).put("approvalPolicy", "never");
            result.putObject("sandbox").put("type", unsafeThreadPolicy ? "dangerFullAccess" : "readOnly")
                    .put("networkAccess", false);
            result.putObject("thread").put("id", threadId).putObject("status").put("type", status);
            return result;
        }

        private void threadResume(JsonNode id, JsonNode params) {
            String threadId = params.path("threadId").stringValue();
            ThreadState thread = threads.get(threadId);
            if (thread == null) {
                error(id);
                return;
            }
            respond(id, threadResult(threadId, thread.model, thread.cwd, "idle").put("reasoningEffort", "low"));
        }

        private void turnsList(JsonNode id, JsonNode params) {
            ThreadState thread = threads.get(params.path("threadId").stringValue());
            if (thread == null) {
                error(id);
                return;
            }
            ObjectNode result = JSON.createObjectNode();
            var data = result.putArray("data");
            List<TurnState> turns = new ArrayList<>(thread.turns);
            Collections.reverse(turns);
            turns.stream().limit(params.path("limit").asInt(32))
                    .forEach(turn -> data.addObject().put("id", turn.id).put("status", turn.status));
            result.putNull("nextCursor");
            respond(id, result);
        }

        private void turnStart(JsonNode id, JsonNode params) {
            String threadId = params.path("threadId").stringValue();
            ThreadState thread = threads.get(threadId);
            if (thread == null) {
                error(id);
                return;
            }
            String text = params.path("input").path(0).path("text").stringValue();
            String scenario = text.contains("scenario-") ? text.substring(text.indexOf("scenario-")).split("\\s")[0]
                    : "scenario-complete";
            TurnState turn = new TurnState("neutral-id-" + ids.incrementAndGet(), scenario);
            String previous = thread.turns.isEmpty() ? null : thread.turns.getLast().id;
            thread.turns.add(turn);
            ObjectNode ack = JSON.createObjectNode();
            ack.putObject("turn").put("id", turn.id).put("status", "inProgress");
            if (scenario.equals("scenario-early")) {
                started(threadId, turn);
                respond(id, ack);
                complete(threadId, turn);
                return;
            }
            respond(id, ack);
            started(threadId, turn);
            switch (scenario) {
                case "scenario-noise" -> {
                    notification("item/started", turnParams("other-thread", "other-turn")
                            .set("item", JSON.createObjectNode().put("type", "agentMessage")));
                    if (previous != null) {
                        notification("turn/completed", lifecycle(threadId, previous, "completed"));
                    }
                    notification("account/rateLimits/updated", JSON.createObjectNode());
                    notification("item/agentMessage/delta", turnParams(threadId, turn.id).put("delta", "partial"));
                    complete(threadId, turn);
                }
                case "scenario-fail" -> {
                    ObjectNode error = turnParams(threadId, turn.id).put("willRetry", false);
                    error.putObject("error").put("message", "private text").put("codexErrorInfo", "usageLimitExceeded");
                    notification("error", error);
                    turn.status = "failed";
                    notification("turn/completed", lifecycle(threadId, turn.id, "failed"));
                }
                case "scenario-tool" -> notification("item/started", turnParams(threadId, turn.id)
                        .set("item", JSON.createObjectNode().put("type", "commandExecution").put("id", "item-1")));
                case "scenario-reroute" -> notification("model/rerouted", turnParams(threadId, turn.id));
                case "scenario-approval" -> {
                    ObjectNode request = JSON.createObjectNode().put("id", "srv-1")
                            .put("method", "item/commandExecution/requestApproval");
                    request.set("params", turnParams(threadId, turn.id));
                    emit(request);
                }
                case "scenario-eof" -> eof.run();
                case "scenario-hold", "scenario-silent" -> {
                }
                default -> complete(threadId, turn);
            }
        }

        private void started(String threadId, TurnState turn) {
            notification("turn/started", lifecycle(threadId, turn.id, "inProgress"));
        }

        private void complete(String threadId, TurnState turn) {
            ObjectNode item = JSON.createObjectNode().put("id", "item-" + turn.id).put("type", "agentMessage")
                    .put("text", OUTPUT);
            notification("item/completed", turnParams(threadId, turn.id).set("item", item));
            turn.status = "completed";
            notification("turn/completed", lifecycle(threadId, turn.id, "completed"));
        }

        private void interrupt(JsonNode id, JsonNode params) {
            String threadId = params.path("threadId").stringValue();
            ThreadState thread = threads.get(threadId);
            TurnState turn = thread == null ? null : thread.turns.stream()
                    .filter(t -> t.id.equals(params.path("turnId").stringValue())).findFirst().orElse(null);
            if (turn == null || !turn.status.equals("inProgress")) {
                error(id);
                return;
            }
            respond(id, JSON.createObjectNode());
            if (turn.scenario.equals("scenario-silent") && !turn.ignoredInterrupt) {
                turn.ignoredInterrupt = true;
                return;
            }
            turn.status = "interrupted";
            notification("turn/completed", lifecycle(threadId, turn.id, "interrupted"));
        }
    }

    private static ObjectNode turnParams(String threadId, String turnId) {
        return JSON.createObjectNode().put("threadId", threadId).put("turnId", turnId);
    }

    private static ObjectNode lifecycle(String threadId, String turnId, String status) {
        ObjectNode params = JSON.createObjectNode().put("threadId", threadId);
        params.putObject("turn").put("id", turnId).put("status", status);
        return params;
    }

    private static JsonNode catalogPage(String cursor) {
        String json = cursor == null
                ? "{\"data\":[{\"id\":\"model-a\",\"model\":\"model-a\",\"defaultReasoningEffort\":\"low\","
                        + "\"supportedReasoningEfforts\":[{\"reasoningEffort\":\"low\"},{\"reasoningEffort\":\"medium\"}]}],"
                        + "\"nextCursor\":\"page-2\"}"
                : "{\"data\":[{\"id\":\"model-b\",\"model\":\"model-b\",\"defaultReasoningEffort\":\"medium\","
                        + "\"supportedReasoningEfforts\":[{\"reasoningEffort\":\"medium\"},{\"reasoningEffort\":\"high\"}]}],"
                        + "\"nextCursor\":null}";
        return JSON.readTree(json);
    }

    /** In-memory wire whose server side is a {@link Connection} of this server. */
    final class MemoryWire implements FrameWire {
        private static final byte[] EOF = new byte[0];
        private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
        final Connection connection = new Connection(inbound::add, () -> inbound.add(EOF));
        private volatile boolean closed;
        private WireException failure;

        @Override
        public byte[] read() throws WireException {
            if (failure != null) {
                throw failure;
            }
            try {
                byte[] frame = inbound.take();
                if (frame == EOF) {
                    closed = true;
                    failure = new WireException("transport-eof");
                    throw failure;
                }
                return frame;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WireException("transport-read");
            }
        }

        @Override
        public void write(byte[] frame) throws WireException {
            if (closed) {
                throw new WireException("transport-write");
            }
            connection.handle(frame);
        }

        @Override
        public ShutdownRecord close() {
            closed = true;
            inbound.add(EOF);
            return new ShutdownRecord("socket-closed", null, 0);
        }

        /** Push a raw frame from the server side. */
        void push(byte[] frame) {
            inbound.add(frame);
        }

        /** End the connection from the server side. */
        void serverEof() {
            inbound.add(EOF);
        }
    }

    /** @return a new in-memory connection */
    MemoryWire memoryWire() {
        return new MemoryWire();
    }

    /** Wait until {@code condition} holds or fail after five seconds. */
    static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached");
            }
            Thread.sleep(10);
        }
    }
}
