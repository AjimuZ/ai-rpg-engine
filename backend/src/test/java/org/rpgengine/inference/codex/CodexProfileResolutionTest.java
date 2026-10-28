package org.rpgengine.inference.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.ModelAllowlist;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.SlotAssignment;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.TaskResult.Status;

/**
 * P08.4 through the real adapter and the scripted app-server (whose catalog is {@code model-a}
 * with low/medium and {@code model-b} with medium/high): the owner's slot assignment, allowlist,
 * approved degradation and {@code fixedQuality} reach the wire as explicit per-turn settings, and
 * every refusal happens before any thread or turn exists.
 */
class CodexProfileResolutionTest {
    private final ScriptedAppServer server = new ScriptedAppServer();
    private final Map<InferenceSlot, SlotAssignment> owner = new EnumMap<>(InferenceSlot.class);
    private ModelAllowlist allowlist = ModelAllowlist.empty();
    private Optional<InferenceSlot> fixedQuality = Optional.empty();
    private CodexTaskAdapter adapter;

    private void start() throws InterruptedException {
        adapter = new CodexTaskAdapter(CodexTaskAdapterTest.settings(4),
                new ProfileResolver(slot -> Optional.ofNullable(owner.get(slot)), allowlist, fixedQuality),
                server::memoryWire);
        adapter.start();
        ScriptedAppServer.await(() -> adapter.status().state() != RuntimeStatus.State.STARTING);
        assertThat(adapter.status().state()).isEqualTo(RuntimeStatus.State.READY);
    }

    @AfterEach
    void stop() {
        if (adapter != null) {
            adapter.stop();
        }
    }

    private TaskResult run(UUID session, InferenceSlot slot) throws Exception {
        return CodexTaskAdapterTest.result(adapter.submit(new TaskRequest(UUID.randomUUID(), session, slot, "Narrate.",
                CodexTaskAdapterTest.SCHEMA, Duration.ofSeconds(5))));
    }

    private static EffectiveSettings pair(String model, String effort) {
        return new EffectiveSettings(model, effort);
    }

    private String sent(int turn, String field) {
        return server.received("turn/start").get(turn).params().path(field).stringValue();
    }

    @Test
    void aReassignmentBetweenTurnsOfOneThreadIsSentExplicitlyOnTheNextTurn() throws Exception {
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", pair("model-a", "medium")));
        start();
        UUID session = UUID.randomUUID();
        assertThat(run(session, InferenceSlot.STANDARD).provenance().model()).isEqualTo("model-a");
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", pair("model-b", "high")));
        TaskResult second = run(session, InferenceSlot.STANDARD);

        assertThat(second.provenance()).isEqualTo(new TaskResult.Provenance("codex", "0.164.0", "model-b", "high"));
        assertThat(second.freshSession()).isFalse();
        assertThat(server.received("thread/start")).hasSize(1);
        assertThat(sent(0, "model") + "@" + sent(0, "effort")).isEqualTo("model-a@medium");
        assertThat(sent(1, "model") + "@" + sent(1, "effort")).isEqualTo("model-b@high");
    }

    @Test
    void anApprovedAlternativeRunsWhenThePrimaryIsNotInTheCatalogAndProvenanceSaysSo() throws Exception {
        EffectiveSettings preferred = pair("model-z", "high");
        owner.put(InferenceSlot.DELIBERATE, new SlotAssignment("codex", preferred,
                List.of(pair("model-y", "low"), pair("model-b", "high"))));
        start();
        TaskResult result = run(UUID.randomUUID(), InferenceSlot.DELIBERATE);

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(result.provenance()).isEqualTo(new TaskResult.Provenance("codex", "0.164.0", "model-b", "high",
                preferred));
        assertThat(sent(0, "model")).isEqualTo("model-b");
        assertThat(server.received("thread/start").getFirst().params().path("model").stringValue())
                .isEqualTo("model-b");
    }

    @Test
    void withoutAnApprovedAlternativeAnUnsupportedPairFailsBeforeAnyThreadExists() throws Exception {
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", pair("model-z", "high")));
        start();
        TaskResult result = run(UUID.randomUUID(), InferenceSlot.STANDARD);

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("slot-unsupported");
        assertThat(result.provenance()).isNull();
        assertThat(server.received("thread/start")).isEmpty();
        assertThat(server.received("turn/start")).isEmpty();
    }

    @Test
    void theOwnersAllowlistNarrowsTheDiscoveredCatalog() throws Exception {
        owner.put(InferenceSlot.LIGHT, SlotAssignment.of("codex", pair("model-a", "low")));
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", pair("model-a", "medium")));
        allowlist = ModelAllowlist.of(Map.of("codex", List.of(pair("model-a", "medium"))));
        start();

        TaskResult denied = run(UUID.randomUUID(), InferenceSlot.LIGHT);
        assertThat(denied.errorCode()).isEqualTo("slot-unsupported");
        assertThat(server.received("thread/start")).isEmpty();
        assertThat(run(UUID.randomUUID(), InferenceSlot.STANDARD).status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void aSlotAssignedToAnotherBackendIsRefusedWithoutContactingTheRuntimeForAThread() throws Exception {
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("claude", pair("model-a", "medium")));
        start();
        TaskResult result = run(UUID.randomUUID(), InferenceSlot.STANDARD);

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("slot-backend-mismatch");
        assertThat(server.received("thread/start")).isEmpty();
    }

    @Test
    void fixedQualityMakesEverySlotRequestRunOnTheOwnersChosenPair() throws Exception {
        owner.put(InferenceSlot.LIGHT, SlotAssignment.of("codex", pair("model-a", "low")));
        owner.put(InferenceSlot.STANDARD, SlotAssignment.of("codex", pair("model-a", "medium")));
        owner.put(InferenceSlot.DELIBERATE, SlotAssignment.of("codex", pair("model-b", "high")));
        fixedQuality = Optional.of(InferenceSlot.STANDARD);
        start();
        UUID session = UUID.randomUUID();
        for (InferenceSlot slot : InferenceSlot.values()) {
            assertThat(run(session, slot).provenance().effort()).isEqualTo("medium");
        }
        assertThat(server.received("turn/start")).hasSize(3);
        for (int turn = 0; turn < 3; turn++) {
            assertThat(sent(turn, "model")).isEqualTo("model-a");
            assertThat(sent(turn, "effort")).isEqualTo("medium");
        }
    }
}
