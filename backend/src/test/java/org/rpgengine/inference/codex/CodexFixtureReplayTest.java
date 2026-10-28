package org.rpgengine.inference.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.EffectiveSettings;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replays the retained P01.5 neutral fixture (runtime 0.164.0, account-free packaged session)
 * against the Java protocol layer: the adapter's requests must equal the recorded ones, the
 * recorded results must validate, and the recorded notification stream, routed through a real
 * {@link RpcGeneration}, must yield the recorded terminal statuses for every turn.
 */
class CodexFixtureReplayTest {
    static final String RESOURCE = "/contracts/codex/0.164.0/neutral-session.json";
    static final Path SOURCE = Path.of("../tools/runtime_probe/fixtures/0.164.0/neutral-session.json");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    static JsonNode fixture() throws IOException {
        try (InputStream in = CodexFixtureReplayTest.class.getResourceAsStream(RESOURCE)) {
            return JSON.readTree(in);
        }
    }

    static List<JsonNode> requests(JsonNode fixture, String method) {
        List<JsonNode> found = new ArrayList<>();
        fixture.get("requests").forEach(r -> {
            if (r.get("method").stringValue().equals(method)) {
                found.add(r);
            }
        });
        return found;
    }

    @Test
    void backendCopyIsByteIdenticalToTheRetainedProbeFixture() throws IOException {
        // The verification image builds from backend/ only, so the backend keeps a copy; in a
        // full checkout (every developer and CI run) it must not drift from the probe's retained
        // fixture. Only the backend-only image reports this case as skipped.
        Assumptions.assumeTrue(Files.isRegularFile(SOURCE), "probe fixture not in this build context");
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            assertThat(in.readAllBytes()).isEqualTo(Files.readAllBytes(SOURCE));
        }
        assertThat(fixture().get("runtimeVersion").stringValue()).isEqualTo(CodexProtocol.SUPPORTED_VERSION);
    }

    @Test
    void threadAndTurnRequestsEqualTheRecordedShapes() throws Exception {
        JsonNode fixture = fixture();
        JsonNode threadStart = requests(fixture, "thread/start").getFirst();
        assertThat(CodexProtocol.threadStartParams(JSON, "gpt-6.1-sol", "/var/lib/runtime/workspace"))
                .isEqualTo(threadStart.get("params"));
        for (JsonNode turnStart : requests(fixture, "turn/start")) {
            JsonNode params = turnStart.get("params");
            ObjectNode rebuilt = CodexProtocol.turnStartParams(JSON, params.get("threadId").stringValue(),
                    params.get("input").get(0).get("text").stringValue(),
                    new EffectiveSettings(params.get("model").stringValue(), params.get("effort").stringValue()),
                    params.get("outputSchema"));
            assertThat(rebuilt).isEqualTo(params);
        }
        for (JsonNode interrupt : requests(fixture, "turn/interrupt")) {
            JsonNode params = interrupt.get("params");
            assertThat(CodexProtocol.interruptParams(JSON, new TurnKey(params.get("threadId").stringValue(),
                    params.get("turnId").stringValue()))).isEqualTo(params);
            CodexProtocol.validateInterrupt(interrupt.get("result"));
        }
        JsonNode resume = requests(fixture, "thread/resume").getFirst();
        assertThat(CodexProtocol.resumeParams(JSON, "neutral-id-4")).isEqualTo(resume.get("params"));
        assertThat(CodexProtocol.accountParams(JSON)).isEqualTo(requests(fixture, "account/read").getFirst().get("params"));
        // Same members as the recorded initialize; the client identity is the engine's own.
        JsonNode initialize = requests(fixture, "initialize").getFirst().get("params");
        ObjectNode ours = CodexProtocol.initializeParams(JSON);
        assertThat(ours.propertyNames()).containsExactlyElementsOf(initialize.propertyNames());
        assertThat(ours.get("clientInfo").propertyNames()).containsExactlyElementsOf(
                initialize.get("clientInfo").propertyNames());
        assertThat(ours.get("capabilities")).isEqualTo(initialize.get("capabilities"));
        // Catalog paging differs only in page size.
        JsonNode list = requests(fixture, "model/list").get(1).get("params");
        ObjectNode ourList = CodexProtocol.catalogParams(JSON, "neutral-id-1");
        assertThat(ourList.propertyNames()).containsExactlyInAnyOrderElementsOf(list.propertyNames());
        assertThat(ourList.get("includeHidden")).isEqualTo(list.get("includeHidden"));
    }

    @Test
    void recordedResultsValidate() throws Exception {
        JsonNode fixture = fixture();
        CodexProtocol.validateInitialize(requests(fixture, "initialize").getFirst().get("result"));
        // The fixture is account-free: valid, but never "ready" for gameplay.
        assertThat(CodexProtocol.accountState(requests(fixture, "account/read").getFirst().get("result")))
                .isEqualTo(CodexProtocol.AccountState.UNSUPPORTED);
        Map<String, Set<String>> catalog = new HashMap<>();
        String cursor = "start";
        List<JsonNode> pages = requests(fixture, "model/list").subList(0, 4);
        for (JsonNode page : pages) {
            assertThat(cursor).isNotNull();
            cursor = CodexProtocol.addCatalogPage(page.get("result"), catalog);
        }
        assertThat(cursor).isNull();
        assertThat(catalog).hasSize(8).containsEntry("gpt-5.5", Set.of("low", "medium", "high", "xhigh"));
        assertThat(new CodexProtocol.Catalog(catalog).supports(new EffectiveSettings("gpt-6-luna", "ultra"))).isFalse();
        JsonNode threadStart = requests(fixture, "thread/start").getFirst();
        assertThat(CodexProtocol.validateThreadStart(threadStart.get("result"), "gpt-6.1-sol",
                "/var/lib/runtime/workspace")).isEqualTo("neutral-id-4");
        assertThatThrownBy(() -> CodexProtocol.validateThreadStart(threadStart.get("result"), "gpt-5.5",
                "/var/lib/runtime/workspace")).hasMessage("thread-settings-mismatch");
        for (JsonNode resume : requests(fixture, "thread/resume")) {
            CodexProtocol.validateResume(resume.get("result"), resume.get("params").get("threadId").stringValue());
        }
        List<String> started = new ArrayList<>();
        for (JsonNode turn : requests(fixture, "turn/start")) {
            started.add(CodexProtocol.validateTurnStart(turn.get("result")));
        }
        assertThat(started).containsExactly("neutral-id-5", "neutral-id-7", "neutral-id-9", "neutral-id-10",
                "neutral-id-11", "neutral-id-12", "neutral-id-13");
        assertThat(CodexProtocol.parseTurns(requests(fixture, "thread/turns/list").getFirst().get("result")))
                .extracting(CodexProtocol.TurnState::status)
                .containsExactly("completed", "completed", "completed", "interrupted", "failed");
    }

    @Test
    void recordedNotificationStreamYieldsTheRecordedTerminalStatuses() throws Exception {
        JsonNode fixture = fixture();
        RpcGenerationTest.ManualWire wire = new RpcGenerationTest.ManualWire();
        RpcGeneration generation = new RpcGeneration(1, wire, JSON);
        generation.start();
        Map<TurnKey, TurnInbox> inboxes = new LinkedHashMap<>();
        for (String[] key : new String[][] {{"neutral-id-4", "neutral-id-5"}, {"neutral-id-4", "neutral-id-7"},
                {"neutral-id-8", "neutral-id-9"}, {"neutral-id-4", "neutral-id-10"}, {"neutral-id-4", "neutral-id-11"},
                {"neutral-id-4", "neutral-id-12"}, {"neutral-id-4", "neutral-id-13"}}) {
            TurnKey turn = new TurnKey(key[0], key[1]);
            inboxes.put(turn, generation.openTurn(turn));
        }
        for (JsonNode notification : fixture.get("notifications")) {
            wire.inbound.add(JSON.writeValueAsBytes(notification));
        }
        Map<String, CodexProtocol.TurnTracker.Outcome> outcomes = new LinkedHashMap<>();
        for (TurnInbox inbox : inboxes.values()) {
            CodexProtocol.TurnTracker tracker = new CodexProtocol.TurnTracker();
            CodexProtocol.TurnTracker.Outcome outcome = null;
            while (outcome == null) {
                RpcEvent event = inbox.poll(2_000_000_000L);
                assertThat(event).as(inbox.key().toString()).isNotNull();
                outcome = tracker.apply(event);
            }
            assertThat(tracker.started()).isTrue();
            outcomes.put(inbox.key().turnId(), outcome);
        }
        String expectedText = fixture.get("notifications").get(1).get("params").get("item").get("text").stringValue();
        assertThat(outcomes.get("neutral-id-5")).isEqualTo(new CodexProtocol.TurnTracker.Outcome("completed",
                expectedText, null));
        assertThat(outcomes.get("neutral-id-9").status()).isEqualTo("interrupted");
        assertThat(outcomes.get("neutral-id-11").status()).isEqualTo("interrupted");
        assertThat(outcomes.get("neutral-id-12")).isEqualTo(new CodexProtocol.TurnTracker.Outcome("failed", null,
                "other"));
        assertThat(outcomes.get("neutral-id-13").status()).isEqualTo("completed");
        assertThat(generation.failure()).isNull();
        generation.close();
    }
}
