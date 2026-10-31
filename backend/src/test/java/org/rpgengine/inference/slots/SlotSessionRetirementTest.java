package org.rpgengine.inference.slots;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.ModelAllowlist;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.SlotAssignments;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.local.LocalAdapters;
import org.rpgengine.inference.local.ScriptedModelServer;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.inference.session.SessionScope;

/**
 * Per-slot session retirement from the settings save to the requests a scripted server receives: a
 * changed assignment retires only that slot's sessions, whose next task starts with an empty
 * conversation, while the other slots' sessions keep theirs. The server is a fixture, not a model.
 */
class SlotSessionRetirementTest {
    private static final UUID ADVENTURE = UUID.fromString("00000000-0000-4000-8000-000000000a01");
    private static final UUID CAMPAIGN = UUID.fromString("00000000-0000-4000-8000-000000000a02");
    private static final SessionScope NARRATOR = new SessionScope(ADVENTURE, CAMPAIGN, 1, AiRole.NARRATOR,
            Audience.PUBLIC, "a".repeat(64), "b".repeat(64));
    private static final SessionScope INTERPRETER = new SessionScope(ADVENTURE, CAMPAIGN, 1, AiRole.INTERPRETER,
            Audience.PUBLIC, "a".repeat(64), "b".repeat(64));

    private final SlotAssignments configured = slot -> Optional.empty();
    private ScriptedModelServer server;
    private LocalAdapters.Running adapter;
    private SlotSettingsService settings;
    private ScopedSessions sessions;

    private void wire(Optional<InferenceSlot> fixedQuality) throws Exception {
        server = new ScriptedModelServer("model-a", "model-b");
        SlotSessionRetirement[] retirement = new SlotSessionRetirement[1];
        settings = new SlotSettingsService(new MemorySlotSettingsStore(), (backend, pair) -> BackendProbe.Outcome.ready(),
                configured, Clock.systemUTC(), event -> {
                    if (event instanceof SlotAssignmentChanged change) {
                        retirement[0].onChange(change);
                    }
                });
        var profiles = new ProfileResolver(new PersistedSlotAssignments(settings, configured), ModelAllowlist.empty(),
                fixedQuality);
        adapter = LocalAdapters.start(server, profiles);
        sessions = new ScopedSessions(adapter, 16);
        retirement[0] = new SlotSessionRetirement(sessions, profiles);
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private void assign(ModelSlot slot, String model, String effort) {
        settings.update(new SlotSettingsService.Request(slot, "local", model, effort, null, List.of(), null));
    }

    private TaskResult run(SessionScope scope, InferenceSlot slot, String instructions) throws Exception {
        server.answers("reply to " + instructions);
        return sessions.submit(scope, UUID.randomUUID(), slot, instructions, "{}", Duration.ofSeconds(5)).result()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** Give the two lines a conversation each, one per slot. */
    private void openBothSlots() throws Exception {
        assign(ModelSlot.STANDARD, "model-a", "low");
        assign(ModelSlot.LIGHT, "model-a", "low");
        run(NARRATOR, InferenceSlot.STANDARD, "narrate");
        run(INTERPRETER, InferenceSlot.LIGHT, "interpret");
    }

    private void assertOnlyStandardWasRetired(UUID narrator, UUID interpreter, String expectedModel) throws Exception {
        assertThat(sessions.activeSession(NARRATOR)).isEmpty();
        assertThat(sessions.activeSession(INTERPRETER)).contains(interpreter);
        assertThat(sessions.status().retired()).containsEntry(ScopedSessions.Reason.SLOT_CHANGED, 1L);

        TaskResult renewed = run(NARRATOR, InferenceSlot.STANDARD, "narrate again");
        assertThat(renewed.freshSession()).isTrue();
        assertThat(renewed.provenance().model()).isEqualTo(expectedModel);
        assertThat(server.lastMessages()).containsExactly("narrate again");
        assertThat(sessions.activeSession(NARRATOR)).isPresent().get().isNotEqualTo(narrator);

        TaskResult kept = run(INTERPRETER, InferenceSlot.LIGHT, "interpret again");
        assertThat(kept.freshSession()).isFalse();
        assertThat(server.lastMessages()).containsExactly("interpret", "reply to interpret", "interpret again");
        assertThat(sessions.activeSession(INTERPRETER)).contains(interpreter);
    }

    @Test
    void changingAModelRetiresOnlyThatSlotsSessions() throws Exception {
        wire(Optional.empty());
        openBothSlots();
        UUID narrator = sessions.activeSession(NARRATOR).orElseThrow();
        UUID interpreter = sessions.activeSession(INTERPRETER).orElseThrow();
        assign(ModelSlot.STANDARD, "model-b", "low");
        assertOnlyStandardWasRetired(narrator, interpreter, "model-b");
    }

    @Test
    void changingAParameterRetiresOnlyThatSlotsSessions() throws Exception {
        wire(Optional.empty());
        openBothSlots();
        UUID narrator = sessions.activeSession(NARRATOR).orElseThrow();
        UUID interpreter = sessions.activeSession(INTERPRETER).orElseThrow();
        assign(ModelSlot.STANDARD, "model-a", "high");
        assertOnlyStandardWasRetired(narrator, interpreter, "model-a");
        assertThat(server.chats().get(2).path("reasoning_effort").stringValue()).isEqualTo("high");
    }

    @Test
    void changingABackendRetiresOnlyThatSlotsSessions() throws Exception {
        wire(Optional.empty());
        openBothSlots();
        UUID narrator = sessions.activeSession(NARRATOR).orElseThrow();
        UUID interpreter = sessions.activeSession(INTERPRETER).orElseThrow();
        settings.update(new SlotSettingsService.Request(ModelSlot.STANDARD, "codex", "model-a", "low", null, List.of(),
                BackendClass.SUBSCRIPTION));
        assertThat(sessions.activeSession(NARRATOR)).isEmpty();
        assertThat(sessions.activeSession(INTERPRETER)).contains(interpreter);
        // The adapter serves no other backend, so the renewed session's task is refused and sends nothing.
        int sent = server.chats().size();
        TaskResult moved = run(NARRATOR, InferenceSlot.STANDARD, "narrate again");
        assertThat(moved.errorCode()).isEqualTo("slot-backend-mismatch");
        assertThat(server.chats()).hasSize(sent);
        assertThat(sessions.activeSession(NARRATOR)).isPresent().get().isNotEqualTo(narrator);
    }

    @Test
    void savingTheSameAssignmentRetiresNothing() throws Exception {
        wire(Optional.empty());
        openBothSlots();
        UUID narrator = sessions.activeSession(NARRATOR).orElseThrow();
        assign(ModelSlot.STANDARD, "model-a", "low");
        assertThat(sessions.status().retired()).containsEntry(ScopedSessions.Reason.SLOT_CHANGED, 0L);
        assertThat(sessions.activeSession(NARRATOR)).contains(narrator);
    }

    @Test
    void theImageSlotRetiresNoTextSession() throws Exception {
        wire(Optional.empty());
        openBothSlots();
        settings.update(new SlotSettingsService.Request(ModelSlot.IMAGE, "local-image", "model-c", null, null, List.of(),
                null));
        assertThat(sessions.status().retired()).containsEntry(ScopedSessions.Reason.SLOT_CHANGED, 0L);
        assertThat(sessions.status().active()).isEqualTo(2);
    }

    @Test
    void underFixedQualityOnlyTheFixedSlotsAssignmentRetires() throws Exception {
        wire(Optional.of(InferenceSlot.DELIBERATE));
        assign(ModelSlot.DELIBERATE, "model-a", "low");
        run(NARRATOR, InferenceSlot.STANDARD, "narrate");
        run(INTERPRETER, InferenceSlot.LIGHT, "interpret");

        // No task reads the standard slot's assignment here, so changing it costs no continuity.
        assign(ModelSlot.STANDARD, "model-b", "low");
        assertThat(sessions.status().active()).isEqualTo(2);

        // Every task reads the fixed slot's assignment, whichever slot it named.
        assign(ModelSlot.DELIBERATE, "model-b", "low");
        assertThat(sessions.status().active()).isZero();
        assertThat(sessions.status().retired()).containsEntry(ScopedSessions.Reason.SLOT_CHANGED, 2L);
        TaskResult renewed = run(NARRATOR, InferenceSlot.STANDARD, "narrate again");
        assertThat(renewed.provenance().model()).isEqualTo("model-b");
        assertThat(server.lastMessages()).containsExactly("narrate again");
    }
}
