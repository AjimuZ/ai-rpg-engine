package org.rpgengine.inference.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.ModelAllowlist;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.SlotAssignment;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.TaskResult.Status;
import tools.jackson.databind.JsonNode;

/**
 * Adapter behaviour against the scripted app-server: explicit per-turn settings, exact
 * correlation, bounded admission, cancellation, deadlines, isolation violations, lost
 * connections with reconciliation, unavailability and shutdown.
 */
class CodexTaskAdapterTest {
    static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}";

    private final ScriptedAppServer server = new ScriptedAppServer();
    private final List<ScriptedAppServer.MemoryWire> wires = new ArrayList<>();
    private final Map<InferenceSlot, EffectiveSettings> slots = new EnumMap<>(Map.of(
            InferenceSlot.LIGHT, new EffectiveSettings("model-a", "low"),
            InferenceSlot.STANDARD, new EffectiveSettings("model-a", "medium"),
            InferenceSlot.DELIBERATE, new EffectiveSettings("model-b", "high")));
    private volatile boolean unreachable;
    private CodexTaskAdapter adapter;

    /** Resolver over fixed per-slot pairs on the {@code codex} backend, with no allowlist. */
    static ProfileResolver resolver(Function<InferenceSlot, EffectiveSettings> pairs) {
        return new ProfileResolver(slot -> Optional.ofNullable(pairs.apply(slot))
                .map(settings -> SlotAssignment.of("codex", settings)), ModelAllowlist.empty(), Optional.empty());
    }

    static CodexSettings settings(int capacity) {
        return new CodexSettings(CodexSettings.Transport.UNIX, Path.of("/unused"), List.of(), null,
                CodexSettings.DEFAULT_RUNTIME_CWD, capacity, Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofMillis(500), Duration.ofMillis(20), Duration.ofMillis(100), Duration.ofMillis(200), 8);
    }

    private CodexTaskAdapter start(int capacity) throws InterruptedException {
        adapter = new CodexTaskAdapter(settings(capacity), resolver(slots::get), () -> {
            if (unreachable) {
                throw new WireException("runtime-unreachable");
            }
            ScriptedAppServer.MemoryWire wire = server.memoryWire();
            synchronized (wires) {
                wires.add(wire);
            }
            return wire;
        });
        adapter.start();
        ScriptedAppServer.await(() -> adapter.status().state() != RuntimeStatus.State.STARTING);
        return adapter;
    }

    @AfterEach
    void stop() {
        if (adapter != null) {
            adapter.stop();
        }
    }

    static TaskRequest request(UUID session, InferenceSlot slot, String text, Duration timeout) {
        return new TaskRequest(UUID.randomUUID(), session, slot, text, SCHEMA, timeout);
    }

    static TaskResult result(TaskHandle handle) throws Exception {
        return handle.result().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private TaskResult run(UUID session, InferenceSlot slot, String text) throws Exception {
        return result(adapter.submit(request(session, slot, text, Duration.ofSeconds(5))));
    }

    private void awaitTurnStarts(int count) throws InterruptedException {
        ScriptedAppServer.await(() -> server.received("turn/start").size() >= count);
    }

    @Test
    void completedTurnCarriesExplicitSettingsOnEveryTurnAndReusesTheSessionThread() throws Exception {
        start(4);
        assertThat(adapter.status().state()).isEqualTo(RuntimeStatus.State.READY);
        UUID session = UUID.randomUUID();
        TaskResult first = run(session, InferenceSlot.DELIBERATE, "Narrate. scenario-complete");
        TaskResult second = run(session, InferenceSlot.LIGHT, "Narrate again.");

        assertThat(first.status()).isEqualTo(Status.COMPLETED);
        assertThat(first.output()).isEqualTo(ScriptedAppServer.OUTPUT);
        assertThat(first.provenance()).isEqualTo(new TaskResult.Provenance("codex", "0.164.0", "model-b", "high"));
        assertThat(first.freshSession()).isTrue();
        assertThat(second.status()).isEqualTo(Status.COMPLETED);
        assertThat(second.freshSession()).isFalse();
        assertThat(second.provenance().model()).isEqualTo("model-a");

        assertThat(server.received("thread/start")).hasSize(1);
        JsonNode threadStart = server.received("thread/start").getFirst().params();
        assertThat(threadStart).isEqualTo(ScriptedAppServer.JSON.readTree("{\"model\":\"model-b\",\"cwd\":"
                + "\"/var/lib/runtime/workspace\",\"approvalPolicy\":\"never\",\"sandbox\":\"read-only\",\"ephemeral\":false}"));
        List<ScriptedAppServer.Received> turns = server.received("turn/start");
        assertThat(turns).hasSize(2);
        // Model and effort are sticky in the runtime, so the second (light) turn must not inherit
        // the first (deliberate) one: both are sent explicitly every time.
        assertThat(turns.get(0).params().path("model").stringValue()).isEqualTo("model-b");
        assertThat(turns.get(0).params().path("effort").stringValue()).isEqualTo("high");
        assertThat(turns.get(1).params().path("model").stringValue()).isEqualTo("model-a");
        assertThat(turns.get(1).params().path("effort").stringValue()).isEqualTo("low");
        for (ScriptedAppServer.Received turn : turns) {
            assertThat(turn.params().path("threadId").stringValue())
                    .isEqualTo(turns.getFirst().params().path("threadId").stringValue());
            assertThat(turn.params().path("approvalPolicy").stringValue()).isEqualTo("never");
            assertThat(turn.params().path("sandboxPolicy")).isEqualTo(
                    ScriptedAppServer.JSON.readTree("{\"type\":\"readOnly\",\"networkAccess\":false}"));
            assertThat(turn.params().path("outputSchema")).isEqualTo(ScriptedAppServer.JSON.readTree(SCHEMA));
        }
        assertThat(server.received().stream().map(ScriptedAppServer.Received::method).limit(4))
                .containsExactly("initialize", "initialized", "account/read", "model/list");
    }

    @Test
    void earlyAndInterleavedEventsAreCorrelatedByExactThreadAndTurn() throws Exception {
        start(4);
        UUID session = UUID.randomUUID();
        assertThat(run(session, InferenceSlot.STANDARD, "scenario-early").status()).isEqualTo(Status.COMPLETED);
        // Foreign-thread, prior-turn (late), unscoped and delta events cannot complete this turn.
        TaskResult noisy = run(session, InferenceSlot.STANDARD, "scenario-noise");
        assertThat(noisy.status()).isEqualTo(Status.COMPLETED);
        assertThat(noisy.output()).isEqualTo(ScriptedAppServer.OUTPUT);
    }

    @Test
    void queueIsBoundedAndQueuedOrActiveTasksCanBeCancelled() throws Exception {
        start(2);
        TaskHandle held = adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "scenario-hold",
                Duration.ofSeconds(30)));
        awaitTurnStarts(1);
        TaskRequest queuedRequest = request(UUID.randomUUID(), InferenceSlot.STANDARD, "x", Duration.ofSeconds(30));
        TaskHandle queued = adapter.submit(queuedRequest);
        TaskHandle waiting = adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "x", Duration.ofSeconds(30)));
        TaskResult full = result(adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "x",
                Duration.ofSeconds(30))));
        assertThat(full.status()).isEqualTo(Status.REJECTED);
        assertThat(full.errorCode()).isEqualTo("queue-full");
        assertThat(adapter.status().queued()).isEqualTo(2);
        assertThat(adapter.status().active()).isEqualTo(1);

        assertThat(queued.cancel()).isTrue();
        assertThat(result(queued)).isEqualTo(TaskResult.withoutTurn(queuedRequest.taskId(), Status.CANCELLED, "cancelled"));
        TaskResult duplicate = result(adapter.submit(new TaskRequest(held.taskId(), UUID.randomUUID(),
                InferenceSlot.STANDARD, "x", SCHEMA, Duration.ofSeconds(5))));
        assertThat(duplicate.errorCode()).isEqualTo("duplicate-task");

        assertThat(held.cancel()).isTrue();
        TaskResult cancelled = result(held);
        assertThat(cancelled.status()).isEqualTo(Status.CANCELLED);
        assertThat(cancelled.errorCode()).isEqualTo("cancelled");
        ScriptedAppServer.Received interrupt = server.received("turn/interrupt").getFirst();
        ScriptedAppServer.Received start = server.received("turn/start").getFirst();
        assertThat(interrupt.params().path("threadId")).isEqualTo(start.params().path("threadId"));
        assertThat(server.turnStatus(interrupt.params().path("threadId").stringValue(),
                interrupt.params().path("turnId").stringValue())).isEqualTo("interrupted");
        assertThat(result(waiting).status()).isEqualTo(Status.COMPLETED);
        assertThat(held.cancel()).isFalse();
    }

    @Test
    void deadlineInterruptsTheTurnAndWaitsForItsTerminalStatus() throws Exception {
        start(2);
        TaskResult timedOut = result(adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD,
                "scenario-hold", Duration.ofSeconds(1))));
        assertThat(timedOut.status()).isEqualTo(Status.TIMED_OUT);
        assertThat(timedOut.errorCode()).isEqualTo("timeout");
        assertThat(server.received("turn/interrupt")).hasSize(1);
    }

    @Test
    void failedTurnReportsOnlyAFixedErrorCode() throws Exception {
        start(2);
        TaskResult failed = run(UUID.randomUUID(), InferenceSlot.STANDARD, "scenario-fail");
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("usageLimitExceeded");
        assertThat(failed.toString()).doesNotContain("private text");
    }

    @Test
    void isolationViolationsFailClosedAndTheThreadIsReconciledAndRetired() throws Exception {
        start(2);
        for (String scenario : List.of("scenario-tool", "scenario-reroute", "scenario-approval")) {
            UUID session = UUID.randomUUID();
            TaskResult violated = run(session, InferenceSlot.STANDARD, scenario);
            assertThat(violated.status()).as(scenario).isEqualTo(Status.FAILED);
            assertThat(violated.errorCode()).isEqualTo(switch (scenario) {
                case "scenario-tool" -> "unexpected-turn-item";
                case "scenario-reroute" -> "model-rerouted";
                default -> "unexpected-server-request";
            });
            // The permit is kept until reconciliation interrupted the still-running turn.
            TaskResult next = run(session, InferenceSlot.STANDARD, "after the violation");
            assertThat(next.status()).isEqualTo(Status.COMPLETED);
            assertThat(next.freshSession()).as("ambiguous thread is retired").isTrue();
        }
        assertThat(server.received("thread/turns/list")).hasSize(3);
        assertThat(server.received("turn/interrupt")).hasSize(3);
        for (ScriptedAppServer.Received list : server.received("thread/turns/list")) {
            assertThat(list.params().path("sortDirection").stringValue()).isEqualTo("desc");
        }
        // The approval request was denied with JSON-RPC method-not-found, never granted.
        assertThat(server.clientReplies()).singleElement().satisfies(reply -> {
            assertThat(reply.path("id").stringValue()).isEqualTo("srv-1");
            assertThat(reply.path("error").path("code").asInt()).isEqualTo(-32601);
        });
    }

    @Test
    void lostConnectionIsUnresolvedAndReconciledOnTheNextGeneration() throws Exception {
        start(2);
        UUID session = UUID.randomUUID();
        TaskResult lost = run(session, InferenceSlot.STANDARD, "scenario-eof");
        assertThat(lost.status()).isEqualTo(Status.UNRESOLVED);
        assertThat(lost.errorCode()).isEqualTo("transport-eof");
        TaskResult next = run(session, InferenceSlot.STANDARD, "continue");
        assertThat(next.status()).isEqualTo(Status.COMPLETED);
        assertThat(next.freshSession()).isTrue();

        ScriptedAppServer.Received start = server.received("turn/start").getFirst();
        String thread = start.params().path("threadId").stringValue();
        List<String> second = server.received().stream().filter(r -> r.connection() == 2)
                .map(ScriptedAppServer.Received::method).toList();
        assertThat(second).containsSubsequence("initialize", "initialized", "account/read", "model/list",
                "thread/resume", "thread/turns/list", "turn/interrupt", "thread/start", "turn/start");
        String interrupted = server.received("turn/interrupt").getFirst().params().path("turnId").stringValue();
        assertThat(server.turnStatus(thread, interrupted)).isEqualTo("interrupted");
    }

    @Test
    void sessionBoundInAnEarlierGenerationIsResumedBeforeItsNextTurn() throws Exception {
        start(2);
        UUID session = UUID.randomUUID();
        assertThat(run(session, InferenceSlot.STANDARD, "first").status()).isEqualTo(Status.COMPLETED);
        synchronized (wires) {
            wires.getFirst().serverEof();
        }
        ScriptedAppServer.await(() -> server.received("initialize").size() == 2
                && adapter.status().state() == RuntimeStatus.State.READY);
        TaskResult resumed = run(session, InferenceSlot.STANDARD, "second");
        assertThat(resumed.status()).isEqualTo(Status.COMPLETED);
        assertThat(resumed.freshSession()).isFalse();
        assertThat(server.received("thread/resume")).singleElement()
                .satisfies(r -> assertThat(r.connection()).isEqualTo(2));
        assertThat(server.received("thread/start")).hasSize(1);
    }

    @Test
    void unconfirmedInterruptKeepsThePermitUntilReconciliationSucceeds() throws Exception {
        start(2);
        TaskResult silent = result(adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD,
                "scenario-silent", Duration.ofSeconds(1))));
        assertThat(silent.status()).isEqualTo(Status.UNRESOLVED);
        assertThat(silent.errorCode()).isEqualTo("interrupt-unconfirmed");
        assertThat(run(UUID.randomUUID(), InferenceSlot.STANDARD, "later").status()).isEqualTo(Status.COMPLETED);
        // The second interrupt, sent by reconciliation, preceded the next turn.
        List<String> order = server.received().stream().map(ScriptedAppServer.Received::method)
                .filter(m -> m.equals("turn/interrupt") || m.equals("turn/start")).toList();
        assertThat(order).containsExactly("turn/start", "turn/interrupt", "turn/interrupt", "turn/start");
    }

    @Test
    void slotsMustBeAssignedAndCatalogedAndSchemasMustBeObjects() throws Exception {
        slots.remove(InferenceSlot.LIGHT);
        slots.put(InferenceSlot.DELIBERATE, new EffectiveSettings("model-b", "low"));
        start(2);
        assertThat(run(UUID.randomUUID(), InferenceSlot.LIGHT, "x").errorCode()).isEqualTo("slot-unassigned");
        assertThat(run(UUID.randomUUID(), InferenceSlot.DELIBERATE, "x").errorCode()).isEqualTo("slot-unsupported");
        TaskResult schema = result(adapter.submit(new TaskRequest(UUID.randomUUID(), UUID.randomUUID(),
                InferenceSlot.STANDARD, "x", "[1]", Duration.ofSeconds(5))));
        assertThat(schema.errorCode()).isEqualTo("invalid-output-schema");
        assertThat(server.received("thread/start")).isEmpty();
    }

    @Test
    void unsafeEffectiveThreadPolicyIsRefused() throws Exception {
        server.unsafeThreadPolicy = true;
        start(2);
        TaskResult refused = run(UUID.randomUUID(), InferenceSlot.STANDARD, "x");
        assertThat(refused.status()).isEqualTo(Status.FAILED);
        assertThat(refused.errorCode()).isEqualTo("thread-settings-mismatch");
        assertThat(server.received("turn/start")).isEmpty();
    }

    @Test
    void signInVersionAndReachabilityGateReadiness() throws Exception {
        server.account = ScriptedAppServer.JSON.readTree("{\"account\":null,\"requiresOpenaiAuth\":true}");
        start(2);
        assertThat(adapter.status()).isEqualTo(new RuntimeStatus("codex", RuntimeStatus.State.UNAVAILABLE,
                "sign-in-required", 0, 0));
        assertThat(run(UUID.randomUUID(), InferenceSlot.STANDARD, "x"))
                .extracting(TaskResult::status, TaskResult::errorCode)
                .containsExactly(Status.UNAVAILABLE, "sign-in-required");
        // Signing in later is picked up by the next reconnect attempt.
        server.account = ScriptedAppServer.JSON.readTree("{\"account\":{\"type\":\"apiKey\"},\"requiresOpenaiAuth\":true}");
        ScriptedAppServer.await(() -> adapter.status().state() == RuntimeStatus.State.READY);
        adapter.stop();

        server.userAgent = "codex/0.165.0 (linux)";
        start(2);
        assertThat(adapter.status().reasonCode()).isEqualTo("unsupported-runtime-version");
        adapter.stop();

        unreachable = true;
        start(2);
        assertThat(adapter.status().reasonCode()).isEqualTo("runtime-unreachable");
    }

    @Test
    void shutdownCancelsQueuedWorkInterruptsTheActiveTurnAndClosesTheConnection() throws Exception {
        start(4);
        TaskHandle held = adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "scenario-hold",
                Duration.ofSeconds(30)));
        awaitTurnStarts(1);
        TaskHandle queued = adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "x", Duration.ofSeconds(30)));
        adapter.stop();
        assertThat(result(queued)).extracting(TaskResult::status, TaskResult::errorCode)
                .containsExactly(Status.CANCELLED, "shutdown");
        assertThat(result(held)).extracting(TaskResult::status, TaskResult::errorCode)
                .containsExactly(Status.CANCELLED, "shutdown");
        assertThat(server.received("turn/interrupt")).hasSize(1);
        assertThat(adapter.lastShutdown()).isNotNull();
        assertThat(adapter.status().state()).isEqualTo(RuntimeStatus.State.STOPPED);
        assertThat(result(adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "x",
                Duration.ofSeconds(5)))).errorCode()).isEqualTo("not-running");
        assertThat(adapter.isRunning()).isFalse();
    }

    @Test
    void taskExpiredWhileQueuedNeverStartsATurn() throws Exception {
        start(2);
        TaskHandle held = adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "scenario-hold",
                Duration.ofSeconds(3)));
        awaitTurnStarts(1);
        TaskHandle expiring = adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "x",
                Duration.ofSeconds(1)));
        assertThat(result(expiring)).extracting(TaskResult::status, TaskResult::errorCode)
                .containsExactly(Status.TIMED_OUT, "expired-in-queue");
        assertThat(result(held).status()).isEqualTo(Status.TIMED_OUT);
        assertThat(server.received("turn/start")).hasSize(1);
    }
}
