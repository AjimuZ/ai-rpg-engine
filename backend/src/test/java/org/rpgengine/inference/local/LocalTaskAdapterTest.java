package org.rpgengine.inference.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.ModelAllowlist;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.SlotAssignment;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.TaskResult.Status;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * The local adapter against a scripted HTTP server, never a real model: explicit settings on every
 * request, resolution refusals before anything is sent, conversations and their retirement, every
 * failure mapped to a fixed code with no server text, cancellation, deadlines and admission.
 */
class LocalTaskAdapterTest {
    private static final String SCHEMA = "{\"type\":\"object\"}";
    private static final String SERVER_SECRET = "private server diagnostic";

    private final Map<InferenceSlot, SlotAssignment> assigned = new EnumMap<>(InferenceSlot.class);
    private final List<LocalTaskAdapter> started = new ArrayList<>();
    private final ProfileResolver profiles = new ProfileResolver(slot -> Optional.ofNullable(assigned.get(slot)),
            ModelAllowlist.empty(), Optional.empty());
    private ScriptedModelServer server;
    private LocalTaskAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedModelServer("model-a", "model-b");
        assigned.put(InferenceSlot.STANDARD, SlotAssignment.of("local", new EffectiveSettings("model-a", "none")));
        assigned.put(InferenceSlot.DELIBERATE, SlotAssignment.of("local", new EffectiveSettings("model-b", "high")));
        adapter = started(settings(server.url()));
    }

    @AfterEach
    void tearDown() {
        started.forEach(LocalTaskAdapter::stop);
        server.close();
    }

    private static MockEnvironment settings(String url) {
        return new MockEnvironment().withProperty("engine.inference.local.url", url)
                .withProperty("engine.inference.local.reconnect-initial", "50ms")
                .withProperty("engine.inference.local.reconnect-max", "100ms")
                .withProperty("engine.inference.local.catalog-interval", "100ms");
    }

    private LocalTaskAdapter started(MockEnvironment environment) throws Exception {
        var created = new LocalTaskAdapter(LocalSettings.from(environment), profiles);
        created.start();
        started.add(created);
        awaitState(created, RuntimeStatus.State.READY, RuntimeStatus.State.UNAVAILABLE);
        return created;
    }

    private static void awaitState(LocalTaskAdapter target, RuntimeStatus.State... wanted) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!List.of(wanted).contains(target.status().state()) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(wanted).contains(target.status().state());
    }

    private static TaskRequest request(UUID session, InferenceSlot slot, String instructions) {
        return new TaskRequest(UUID.randomUUID(), session, slot, instructions, SCHEMA, Duration.ofSeconds(5));
    }

    private static TaskResult await(TaskHandle handle) throws Exception {
        return handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private TaskResult run(UUID session, InferenceSlot slot, String instructions) throws Exception {
        return await(adapter.submit(request(session, slot, instructions)));
    }

    private TaskResult run() throws Exception {
        return run(UUID.randomUUID(), InferenceSlot.STANDARD, "task");
    }

    @Test
    void everyRequestNamesItsModelAndParametersExplicitly() throws Exception {
        server.answers("{\"a\":1}").answers("{\"b\":2}");
        UUID session = UUID.randomUUID();

        TaskResult standard = run(session, InferenceSlot.STANDARD, "first");
        TaskResult deliberate = run(session, InferenceSlot.DELIBERATE, "second");

        assertThat(standard.status()).isEqualTo(Status.COMPLETED);
        assertThat(standard.output()).isEqualTo("{\"a\":1}");
        assertThat(standard.provenance()).isEqualTo(new TaskResult.Provenance("local", LocalProtocol.SERVER_BUILD,
                "model-a", "none"));
        assertThat(deliberate.provenance()).isEqualTo(new TaskResult.Provenance("local", LocalProtocol.SERVER_BUILD,
                "model-b", "high"));
        JsonNode first = server.chats().get(0);
        assertThat(first.path("model").stringValue()).isEqualTo("model-a");
        assertThat(first.path("stream").booleanValue()).isFalse();
        assertThat(first.path("max_tokens").intValue()).isEqualTo(2048);
        assertThat(first.path("temperature").doubleValue()).isEqualTo(1.0);
        assertThat(first.path("top_p").doubleValue()).isEqualTo(0.95);
        assertThat(first.path("top_k").intValue()).isEqualTo(64);
        assertThat(first.path("reasoning_effort").stringValue()).isEqualTo("none");
        assertThat(first.path("chat_template_kwargs").path("enable_thinking").booleanValue()).isFalse();
        assertThat(first.path("response_format").path("type").stringValue()).isEqualTo("json_schema");
        JsonNode format = first.path("response_format").path("json_schema");
        assertThat(format.path("strict").booleanValue()).isTrue();
        assertThat(format.path("schema").path("type").stringValue()).isEqualTo("object");
        assertThat(first.has("tools")).isFalse();
        // The second task of the same session inherits nothing but its conversation.
        JsonNode second = server.chats().get(1);
        assertThat(second.path("model").stringValue()).isEqualTo("model-b");
        assertThat(second.path("max_tokens").intValue()).isEqualTo(4096);
        assertThat(second.path("reasoning_effort").stringValue()).isEqualTo("high");
        assertThat(second.path("chat_template_kwargs").path("enable_thinking").booleanValue()).isTrue();
    }

    @Test
    void aSessionKeepsItsConversationAndOtherSessionsDoNotSeeIt() throws Exception {
        server.answers("reply one").answers("reply two").answers("reply three");
        UUID session = UUID.randomUUID();

        assertThat(run(session, InferenceSlot.STANDARD, "one").freshSession()).isTrue();
        assertThat(run(session, InferenceSlot.STANDARD, "two").freshSession()).isFalse();
        assertThat(server.lastMessages()).containsExactly("one", "reply one", "two");
        assertThat(server.chats().getLast().path("messages").get(1).path("role").stringValue()).isEqualTo("assistant");

        assertThat(run(UUID.randomUUID(), InferenceSlot.STANDARD, "other").freshSession()).isTrue();
        assertThat(server.lastMessages()).containsExactly("other");
    }

    @Test
    void aRetiredSessionStartsWithAnEmptyHistoryAndOthersKeepTheirs() throws Exception {
        server.answers("a1").answers("b1").answers("a2").answers("b2");
        UUID retired = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        run(retired, InferenceSlot.STANDARD, "a");
        run(kept, InferenceSlot.STANDARD, "b");

        adapter.retireSession(retired);
        adapter.retireSession(retired);

        assertThat(run(retired, InferenceSlot.STANDARD, "a again").freshSession()).isTrue();
        assertThat(server.lastMessages()).containsExactly("a again");
        assertThat(run(kept, InferenceSlot.STANDARD, "b again").freshSession()).isFalse();
        assertThat(server.lastMessages()).containsExactly("b", "b1", "b again");
    }

    @Test
    void aRunningTaskCannotBringARetiredSessionBack() throws Exception {
        server.holds().answers("next");
        UUID session = UUID.randomUUID();
        TaskRequest slow = new TaskRequest(UUID.randomUUID(), session, InferenceSlot.STANDARD, "slow", SCHEMA,
                Duration.ofSeconds(1));
        TaskHandle running = adapter.submit(slow);
        awaitChats(1);
        adapter.retireSession(session);
        assertThat(await(running).status()).isEqualTo(Status.TIMED_OUT);
        assertThat(run(session, InferenceSlot.STANDARD, "next").freshSession()).isTrue();
        assertThat(server.lastMessages()).containsExactly("next");
    }

    @Test
    void aConversationPastItsBoundStartsOver() throws Exception {
        var bounded = started(settings(server.url()).withProperty("engine.inference.local.max-conversation-bytes",
                "65536"));
        String large = "x".repeat(40_000);
        UUID session = UUID.randomUUID();
        server.answers("reply").answers("reply");
        assertThat(await(bounded.submit(request(session, InferenceSlot.STANDARD, large))).freshSession()).isTrue();
        assertThat(await(bounded.submit(request(session, InferenceSlot.STANDARD, large))).freshSession()).isTrue();
        assertThat(server.lastMessages()).containsExactly(large);
    }

    @Test
    void refusalsOfTheResolverSendNothing() throws Exception {
        assigned.remove(InferenceSlot.LIGHT);
        assertThat(run(UUID.randomUUID(), InferenceSlot.LIGHT, "x").errorCode()).isEqualTo("slot-unassigned");
        assigned.put(InferenceSlot.LIGHT, SlotAssignment.of("codex", new EffectiveSettings("model-a", "low")));
        assertThat(run(UUID.randomUUID(), InferenceSlot.LIGHT, "x").errorCode()).isEqualTo("slot-backend-mismatch");
        assigned.put(InferenceSlot.LIGHT, SlotAssignment.of("local", new EffectiveSettings("model-z", "low")));
        TaskResult unlisted = run(UUID.randomUUID(), InferenceSlot.LIGHT, "x");
        assertThat(unlisted.status()).isEqualTo(Status.FAILED);
        assertThat(unlisted.errorCode()).isEqualTo("slot-unsupported");
        assertThat(unlisted.provenance()).isNull();
        var noSchema = new TaskRequest(UUID.randomUUID(), UUID.randomUUID(), InferenceSlot.STANDARD, "x", "[]",
                Duration.ofSeconds(5));
        assertThat(await(adapter.submit(noSchema)).errorCode()).isEqualTo("invalid-output-schema");
        assertThat(server.chats()).isEmpty();
    }

    @Test
    void anApprovedAlternativeRunsWhenThePrimaryIsNotListedAndIsReported() throws Exception {
        EffectiveSettings primary = new EffectiveSettings("model-z", "low");
        EffectiveSettings alternative = new EffectiveSettings("model-b", "low");
        assigned.put(InferenceSlot.LIGHT, new SlotAssignment("local", primary, List.of(alternative)));
        server.answers("ok");
        TaskResult result = run(UUID.randomUUID(), InferenceSlot.LIGHT, "x");
        assertThat(result.provenance().model()).isEqualTo("model-b");
        assertThat(result.provenance().degradedFrom()).isEqualTo(primary);
        assertThat(server.chats().getLast().path("model").stringValue()).isEqualTo("model-b");
    }

    @Test
    void aChangedAssignmentAppliesToTheNextTask() throws Exception {
        server.answers("one").answers("two");
        UUID session = UUID.randomUUID();
        run(session, InferenceSlot.STANDARD, "first");
        assigned.put(InferenceSlot.STANDARD, SlotAssignment.of("local", new EffectiveSettings("model-b", "low")));
        assertThat(run(session, InferenceSlot.STANDARD, "second").provenance().model()).isEqualTo("model-b");
        assertThat(server.chats().getLast().path("model").stringValue()).isEqualTo("model-b");
    }

    @Test
    void aModelTheServerStopsListingIsRefusedAtTheNextTask() throws Exception {
        server.lists("model-b");
        assertThat(run().errorCode()).isEqualTo("slot-unsupported");
        assertThat(server.chats()).isEmpty();
    }

    @Test
    void everyServerFailureEndsWithAFixedCodeAndNoServerText() throws Exception {
        String error = "{\"error\":{\"message\":\"" + SERVER_SECRET + "\"}}";
        record Case(Runnable script, Status status, String code, boolean provenance) {
        }
        List<Case> cases = List.of(
                new Case(() -> server.replies(400, error), Status.FAILED, "turn-rejected", true),
                new Case(() -> server.replies(500, error), Status.FAILED, "model-server-error", true),
                new Case(() -> server.replies(503, error), Status.UNAVAILABLE, "model-server-busy", false),
                new Case(() -> server.replies(200, SERVER_SECRET), Status.FAILED, "malformed-completion", true),
                new Case(() -> server.replies(200, "{\"choices\":[]}"), Status.FAILED, "malformed-completion", true),
                new Case(() -> server.replies(200, "{\"a\":1,\"a\":2}"), Status.FAILED, "malformed-completion", true),
                new Case(() -> server.replies(200, ScriptedModelServer.completion("cut", "length")), Status.FAILED,
                        "output-truncated", true),
                new Case(() -> server.replies(200, ScriptedModelServer.completion("x", "content_filter")), Status.FAILED,
                        "unexpected-finish", true),
                new Case(() -> server.replies(200, ScriptedModelServer.completion("  ", "stop")), Status.FAILED,
                        "empty-output", true),
                new Case(() -> server.replies(200, "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":"
                        + "{\"content\":\"x\",\"tool_calls\":[{\"id\":\"1\"}]}}]}"), Status.FAILED, "tool-call-refused", true),
                new Case(() -> server.replies(200, "x".repeat(LocalProtocol.MAX_RESPONSE_BYTES + 1)), Status.FAILED,
                        "response-too-large", true),
                new Case(() -> server.dropsConnection(), Status.UNRESOLVED, "connection-lost", true));
        for (Case failure : cases) {
            failure.script().run();
            TaskResult result = run();
            assertThat(result.status()).as(failure.code()).isEqualTo(failure.status());
            assertThat(result.errorCode()).isEqualTo(failure.code());
            assertThat(result.output()).isNull();
            assertThat(result.provenance() != null).as(failure.code()).isEqualTo(failure.provenance());
            assertThat(result.toString()).doesNotContain(SERVER_SECRET);
            awaitState(adapter, RuntimeStatus.State.READY);
        }
    }

    @Test
    void aFailedTaskLeavesNothingInTheConversation() throws Exception {
        server.replies(500, "{}").answers("reply");
        UUID session = UUID.randomUUID();
        assertThat(run(session, InferenceSlot.STANDARD, "failed").status()).isEqualTo(Status.FAILED);
        assertThat(run(session, InferenceSlot.STANDARD, "next").freshSession()).isTrue();
        assertThat(server.lastMessages()).containsExactly("next");
    }

    @Test
    void anUnreachableServerIsUnavailableWithNoProvenance() throws Exception {
        String url = server.url();
        server.close();
        var lost = started(settings(url));
        awaitState(lost, RuntimeStatus.State.UNAVAILABLE);
        assertThat(lost.status().reasonCode()).isEqualTo("runtime-unreachable");
        TaskResult result = await(lost.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "x")));
        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.errorCode()).isEqualTo("runtime-unreachable");
        assertThat(result.provenance()).isNull();
    }

    @Test
    void theStatusReportsWhyTheCatalogCannotBeUsedAndRecoversWhenItCan() throws Exception {
        server.catalogAnswers(503, "{}");
        awaitState(adapter, RuntimeStatus.State.UNAVAILABLE);
        assertThat(adapter.status().reasonCode()).isEqualTo("model-server-starting");
        assertThat(adapter.status().runtime()).isEqualTo("local");
        assertThat(await(adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "x"))).errorCode())
                .isEqualTo("model-server-starting");

        server.catalogAnswers(200, "{\"data\":[{\"name\":\"not an id\"}]}");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!"catalog-malformed".equals(adapter.status().reasonCode()) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(adapter.status().reasonCode()).isEqualTo("catalog-malformed");

        server.catalogAnswers(500, "{}");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!"model-server-error".equals(adapter.status().reasonCode()) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(adapter.status().reasonCode()).isEqualTo("model-server-error");

        server.catalogAnswers(200, "{\"data\":[{\"id\":\"model-a\"}]}");
        awaitState(adapter, RuntimeStatus.State.READY);
        assertThat(adapter.status().reasonCode()).isNull();
    }

    @Test
    void aDeadlineAbortsTheRequestAndTheNextTaskRuns() throws Exception {
        server.holds().answers("next");
        var slow = new TaskRequest(UUID.randomUUID(), UUID.randomUUID(), InferenceSlot.STANDARD, "slow", SCHEMA,
                Duration.ofSeconds(1));
        TaskResult result = await(adapter.submit(slow));
        assertThat(result.status()).isEqualTo(Status.TIMED_OUT);
        assertThat(result.errorCode()).isEqualTo("timeout");
        assertThat(result.provenance()).isNotNull();
        assertThat(run().status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void cancellationAbortsARunningRequestAndEndsAQueuedOneWithoutSendingIt() throws Exception {
        server.holds();
        TaskHandle running = adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "running"));
        awaitChats(1);
        TaskHandle queued = adapter.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "queued"));
        assertThat(adapter.status().active()).isEqualTo(1);
        assertThat(adapter.status().queued()).isEqualTo(1);

        assertThat(queued.cancel()).isTrue();
        TaskResult ended = await(queued);
        assertThat(ended.status()).isEqualTo(Status.CANCELLED);
        assertThat(ended.provenance()).isNull();

        assertThat(running.cancel()).isTrue();
        TaskResult stopped = await(running);
        assertThat(stopped.status()).isEqualTo(Status.CANCELLED);
        assertThat(stopped.errorCode()).isEqualTo("cancelled");
        assertThat(stopped.provenance()).isNotNull();
        assertThat(running.cancel()).isFalse();
        assertThat(server.chats()).hasSize(1);
    }

    @Test
    void admissionIsBoundedUniqueAndClosedOutsideItsLifecycle() throws Exception {
        var small = started(settings(server.url()).withProperty("engine.inference.local.queue-capacity", "1"));
        server.holds();
        TaskHandle running = small.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "running"));
        awaitChats(1);
        TaskRequest waiting = request(UUID.randomUUID(), InferenceSlot.STANDARD, "waiting");
        small.submit(waiting);
        TaskResult duplicate = await(small.submit(waiting));
        assertThat(duplicate.status()).isEqualTo(Status.REJECTED);
        assertThat(duplicate.errorCode()).isEqualTo("duplicate-task");
        TaskResult full = await(small.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "full")));
        assertThat(full.errorCode()).isEqualTo("queue-full");

        small.stop();
        assertThat(await(running).status()).isEqualTo(Status.CANCELLED);
        assertThat(await(running).errorCode()).isEqualTo("shutdown");
        assertThat(small.isRunning()).isFalse();
        assertThat(small.status().state()).isEqualTo(RuntimeStatus.State.STOPPED);
        TaskResult closed = await(small.submit(request(UUID.randomUUID(), InferenceSlot.STANDARD, "late")));
        assertThat(closed.errorCode()).isEqualTo("not-running");
    }

    @Test
    void invalidSettingsFailWithoutEchoingTheirValue() {
        for (String url : List.of("ftp://private-host:8080", "not a url private-host", "http://user:pw@private-host",
                "http://private-host/path", "http://private-host?query=1", "//private-host")) {
            assertThatThrownBy(() -> LocalSettings.from(new MockEnvironment().withProperty(LocalSettings.PREFIX + "url", url)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("engine.inference.local.url")
                    .hasMessageNotContaining("private-host");
        }
        MockEnvironment base = new MockEnvironment().withProperty(LocalSettings.PREFIX + "url", "http://models:8080/");
        assertThat(LocalSettings.from(base).uri("/v1/models").toString()).isEqualTo("http://models:8080/v1/models");
        for (var entry : Map.of("queue-capacity", "0", "max-sessions", "5000", "max-conversation-bytes", "1",
                "temperature", "3", "top-p", "0", "top-k", "-1", "request-timeout", "0s").entrySet()) {
            MockEnvironment environment = new MockEnvironment().withProperty(LocalSettings.PREFIX + "url", "http://models:8080")
                    .withProperty(LocalSettings.PREFIX + entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> LocalSettings.from(environment)).as(entry.getKey())
                    .isInstanceOf(IllegalStateException.class);
        }
        MockEnvironment malformed = new MockEnvironment().withProperty(LocalSettings.PREFIX + "url", "http://models:8080")
                .withProperty(LocalSettings.PREFIX + "top-k", "private-value");
        assertThatThrownBy(() -> LocalSettings.from(malformed)).hasMessageContaining("top-k")
                .hasMessageNotContaining("private-value");
    }

    private void awaitChats(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.chats().size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(server.chats()).hasSizeGreaterThanOrEqualTo(count);
    }
}
