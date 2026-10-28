package org.rpgengine.inference.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Correlation, bounds and fail-closed rules of one RPC generation, driven frame by frame. */
class RpcGenerationTest {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final long WAIT = TimeUnit.SECONDS.toNanos(2);

    /** Wire whose inbound frames the test pushes and whose outbound frames it inspects. */
    static final class ManualWire implements FrameWire {
        private static final byte[] EOF = new byte[0];
        final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
        final List<JsonNode> written = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        @Override
        public byte[] read() throws WireException {
            try {
                byte[] frame = inbound.take();
                if (frame == EOF) {
                    throw new WireException("transport-eof");
                }
                return frame;
            } catch (InterruptedException e) {
                throw new WireException("transport-read");
            }
        }

        @Override
        public void write(byte[] frame) {
            written.add(JSON.readTree(frame));
        }

        @Override
        public ShutdownRecord close() {
            closed = true;
            inbound.add(EOF);
            return new ShutdownRecord("socket-closed", null, 0);
        }

        void push(String json) {
            inbound.add(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private final ManualWire wire = new ManualWire();
    private final RpcGeneration generation = new RpcGeneration(1, wire, JSON);

    private CompletableFuture<JsonNode> request(String method) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return generation.request(method, JSON.createObjectNode(), Duration.ofSeconds(2));
            } catch (ProtocolException e) {
                throw new IllegalStateException(e.code());
            }
        });
    }

    private void awaitWritten(int count) throws InterruptedException {
        ScriptedAppServer.await(() -> wire.written.size() >= count);
    }

    private static String event(String method, String thread, String turn) {
        return "{\"method\":\"" + method + "\",\"params\":{\"threadId\":\"" + thread + "\",\"turnId\":\"" + turn
                + "\",\"item\":{\"type\":\"reasoning\"}}}";
    }

    @Test
    void concurrentRequestsUseMonotonicIdsAndOutOfOrderResponsesResolveTheirOwnCaller() throws Exception {
        generation.start();
        CompletableFuture<JsonNode> first = request("a");
        awaitWritten(1);
        CompletableFuture<JsonNode> second = request("b");
        awaitWritten(2);
        assertThat(wire.written.stream().map(w -> w.path("id").asLong())).containsExactly(1L, 2L);
        wire.push("{\"id\":2,\"result\":{\"who\":\"b\"}}");
        wire.push("{\"id\":1,\"result\":{\"who\":\"a\"}}");
        assertThat(first.get(2, TimeUnit.SECONDS).path("who").stringValue()).isEqualTo("a");
        assertThat(second.get(2, TimeUnit.SECONDS).path("who").stringValue()).isEqualTo("b");
    }

    @Test
    void correlatedErrorResolvesTheRequestAndKeepsTheGenerationUsable() throws Exception {
        generation.start();
        CompletableFuture<JsonNode> failing = request("a");
        awaitWritten(1);
        wire.push("{\"id\":1,\"error\":{\"code\":-1,\"message\":\"secret path /home/owner\"}}");
        assertThat(failing).failsWithin(Duration.ofSeconds(2)).withThrowableThat()
                .withMessageContaining("runtime-rpc-error").withMessageNotContaining("secret");
        assertThat(generation.failure()).isNull();
        CompletableFuture<JsonNode> next = request("b");
        awaitWritten(2);
        wire.push("{\"id\":2,\"result\":{}}");
        assertThat(next.get(2, TimeUnit.SECONDS).isEmpty()).isTrue();
    }

    @Test
    void unresolvedRequestClosesTheGenerationAndLaterCallsFailWithoutIo() throws Exception {
        generation.start();
        assertThatThrownBy(() -> generation.request("a", JSON.createObjectNode(), Duration.ofMillis(100)))
                .isInstanceOf(ProtocolException.class).hasMessage("request-unresolved");
        assertThat(generation.failure()).isEqualTo("request-unresolved");
        assertThatThrownBy(() -> generation.request("b", JSON.createObjectNode(), Duration.ofSeconds(1)))
                .hasMessage("request-unresolved");
        assertThat(wire.written).hasSize(1);
    }

    @Test
    void serverRequestsAreDeniedAndFailOnlyTheTurnTheyName() throws Exception {
        generation.start();
        TurnInbox target = generation.openTurn(new TurnKey("t1", "u1"));
        TurnInbox other = generation.openTurn(new TurnKey("t1", "u2"));
        wire.push("{\"id\":7,\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\"}}");
        wire.push(event("item/started", "t1", "u2"));
        awaitWritten(1);
        assertThat(wire.written.getFirst().path("id").asInt()).isEqualTo(7);
        assertThat(wire.written.getFirst().path("error").path("code").asInt()).isEqualTo(-32601);
        assertThatThrownBy(() -> target.poll(WAIT)).hasMessage("unexpected-server-request");
        assertThat(other.poll(WAIT).method()).isEqualTo("item/started");
        assertThat(generation.failure()).isNull();
    }

    @Test
    void serverRequestBeforeTheTurnIsRegisteredStillFailsThatTurn() throws Exception {
        // The reader can read the turn/start acknowledgment and the runtime's approval request
        // before the caller registers the turn; the request must still fail it (ADR 0013).
        generation.start();
        generation.beginTurnStart("t1");
        wire.push("{\"method\":\"turn/started\",\"params\":{\"threadId\":\"t1\",\"turn\":{\"id\":\"u1\"}}}");
        wire.push("{\"id\":\"srv-1\",\"method\":\"item/commandExecution/requestApproval\","
                + "\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\"}}");
        wire.push(event("item/started", "t1", "u2"));
        wire.push("{\"id\":\"srv-2\",\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u3\"}}");
        // The second denial is written after the reader handled all four frames.
        awaitWritten(2);
        assertThat(wire.written).extracting(reply -> reply.path("error").path("code").asInt())
                .containsExactly(-32601, -32601);

        TurnInbox failed = generation.openTurn(new TurnKey("t1", "u1"));
        TurnInbox healthy = generation.openTurn(new TurnKey("t1", "u2"));
        generation.endTurnStart("t1");
        assertThatThrownBy(() -> failed.poll(WAIT)).hasMessage("unexpected-server-request");
        assertThat(failed.discarded()).as("events before the request are dropped").isEqualTo(1);
        assertThat(healthy.poll(WAIT).method()).isEqualTo("item/started");
        assertThat(healthy.poll(0)).isNull();
        assertThat(generation.counters().early).isEqualTo(4);
        assertThat(generation.counters().ignored).as("u3 was never acknowledged").isEqualTo(1);
        assertThat(generation.failure()).isNull();

        // Without a start in flight a request for an unknown turn is only denied and counted.
        wire.push("{\"id\":\"srv-3\",\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u4\"}}");
        awaitWritten(3);
        assertThat(generation.openTurn(new TurnKey("t1", "u4")).poll(0)).isNull();
        assertThat(generation.counters().deniedServerRequests).isEqualTo(3);
    }

    @Test
    void earlyEventsAreKeptOnlyWhileAStartIsInFlightAndHandedOverInOrder() throws Exception {
        generation.start();
        wire.push(event("item/started", "t1", "u0"));
        ScriptedAppServer.await(() -> generation.counters().ignored == 1);
        generation.beginTurnStart("t1");
        wire.push("{\"method\":\"turn/started\",\"params\":{\"threadId\":\"t1\",\"turn\":{\"id\":\"u1\"}}}");
        wire.push(event("item/started", "t1", "u1"));
        wire.push(event("item/started", "t1", "u9"));
        ScriptedAppServer.await(() -> generation.counters().early == 3);
        TurnInbox inbox = generation.openTurn(new TurnKey("t1", "u1"));
        generation.endTurnStart("t1");
        assertThat(inbox.poll(WAIT).method()).isEqualTo("turn/started");
        assertThat(inbox.poll(WAIT).method()).isEqualTo("item/started");
        assertThat(inbox.poll(0)).isNull();
        RpcGeneration.Counters counters = generation.counters();
        assertThat(counters.ignored).as("u0 before the start, u9 never acknowledged").isEqualTo(2);

        generation.closeTurn(inbox);
        wire.push(event("item/completed", "t1", "u1"));
        wire.push("{\"method\":\"account/rateLimits/updated\",\"params\":{}}");
        ScriptedAppServer.await(() -> generation.counters().late == 1 && generation.counters().unscoped == 1);
    }

    @Test
    void earlyBufferOverflowClosesTheGeneration() throws Exception {
        generation.start();
        generation.beginTurnStart("t1");
        TurnInbox registered = generation.openTurn(new TurnKey("t2", "u1"));
        for (int i = 0; i <= RpcGeneration.MAX_EARLY_EVENTS; i++) {
            wire.push(event("item/started", "t1", "unknown"));
        }
        assertThatThrownBy(() -> registered.poll(WAIT)).hasMessage("notification-overflow");
        assertThat(generation.failure()).isEqualTo("notification-overflow");
    }

    @Test
    void inboxOverflowFailsOnlyThatTurn() throws Exception {
        generation.start();
        TurnInbox flooded = generation.openTurn(new TurnKey("t1", "u1"));
        for (int i = 0; i <= TurnInbox.MAX_EVENTS; i++) {
            wire.push(event("item/started", "t1", "u1"));
        }
        ScriptedAppServer.await(() -> generation.counters().notifications > TurnInbox.MAX_EVENTS);
        assertThatThrownBy(() -> flooded.poll(WAIT)).hasMessage("turn-event-limit");
        assertThat(generation.failure()).isNull();
    }

    @Test
    void protocolViolationsCloseTheGenerationAndFailPendingWork() throws Exception {
        for (String frame : List.of("{\"id\":99,\"result\":{}}", "{\"id\":1,\"result\":{},\"result\":{}}",
                "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"t1\"}}",
                "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"t1\",\"turnId\":7}}",
                "{\"method\":\"x\",\"params\":[]}", "[1]", "{\"id\":1,\"result\":{},\"error\":{}}")) {
            ManualWire manual = new ManualWire();
            RpcGeneration candidate = new RpcGeneration(2, manual, JSON);
            candidate.start();
            TurnInbox inbox = candidate.openTurn(new TurnKey("t1", "u1"));
            CompletableFuture<String> pending = CompletableFuture.supplyAsync(() -> {
                try {
                    candidate.request("a", JSON.createObjectNode(), Duration.ofSeconds(2));
                    return "resolved";
                } catch (ProtocolException e) {
                    return e.code();
                }
            });
            ScriptedAppServer.await(() -> manual.written.size() == 1);
            manual.push(frame);
            String code = pending.get(3, TimeUnit.SECONDS);
            assertThat(code).as(frame).isIn("unexpected-rpc-id", "invalid-json", "invalid-turn-event",
                    "invalid-rpc-params", "invalid-rpc-message", "invalid-rpc-response");
            assertThat(candidate.failure()).isEqualTo(code);
            assertThatThrownBy(() -> inbox.poll(WAIT)).hasMessage(code);
            candidate.close();
        }
    }

    @Test
    void wireEndFailsEverythingAndCloseReleasesTheWire() throws Exception {
        generation.start();
        TurnInbox inbox = generation.openTurn(new TurnKey("t1", "u1"));
        wire.push(event("item/started", "t1", "u1"));
        wire.inbound.add(ManualWire.EOF);
        ScriptedAppServer.await(() -> generation.failure() != null);
        assertThat(generation.failure()).isEqualTo("transport-eof");
        // Events of a closed generation are discarded rather than trusted.
        assertThatThrownBy(() -> inbox.poll(WAIT)).hasMessage("transport-eof");
        assertThat(generation.close().stage()).isEqualTo("socket-closed");
        assertThat(wire.closed).isTrue();
    }

    @Test
    void pendingRequestRegistryIsBounded() throws Exception {
        generation.start();
        for (int i = 0; i < RpcGeneration.MAX_PENDING_REQUESTS; i++) {
            request("a" + i);
        }
        awaitWritten(RpcGeneration.MAX_PENDING_REQUESTS);
        assertThatThrownBy(() -> generation.request("over", JSON.createObjectNode(), Duration.ofSeconds(1)))
                .hasMessage("request-limit");
        assertThat(generation.failure()).isNull();
        assertThat(wire.written).hasSize(RpcGeneration.MAX_PENDING_REQUESTS);
    }
}
