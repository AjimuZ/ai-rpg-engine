package org.rpgengine.inference.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.TaskResult.Status;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.inference.session.SessionScope;

/**
 * Privileged-to-public handoff scenarios of ADR 0007 through the real Codex adapter and the
 * scripted app-server: every scope gets its own runtime thread, a secret sentinel given to the
 * planner is never sent on any other thread, and renewal, a policy change, campaign retirement
 * and a newer generation start fresh threads for exactly the affected scopes.
 */
class ScopedThreadIsolationTest {
    static final String SENTINEL = "SENTINEL-CABAL-7731";
    static final UUID ADVENTURE = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID CAMPAIGN = UUID.fromString("00000000-0000-4000-8000-000000000003");
    static final String PACK = "a".repeat(64);
    static final String POLICY = "b".repeat(64);

    private final ScriptedAppServer server = new ScriptedAppServer();
    private final Map<InferenceSlot, EffectiveSettings> slots = new EnumMap<>(Map.of(
            InferenceSlot.LIGHT, new EffectiveSettings("model-a", "low"),
            InferenceSlot.STANDARD, new EffectiveSettings("model-a", "medium"),
            InferenceSlot.DELIBERATE, new EffectiveSettings("model-b", "high")));
    private CodexTaskAdapter adapter;
    private ScopedSessions sessions;

    private void start() throws InterruptedException {
        adapter = new CodexTaskAdapter(CodexTaskAdapterTest.settings(4), slot -> Optional.ofNullable(slots.get(slot)),
                server::memoryWire);
        adapter.start();
        ScriptedAppServer.await(() -> adapter.status().state() != RuntimeStatus.State.STARTING);
        assertThat(adapter.status().state()).isEqualTo(RuntimeStatus.State.READY);
        sessions = new ScopedSessions(adapter, 16);
    }

    @AfterEach
    void stop() {
        if (adapter != null) {
            adapter.stop();
        }
    }

    static SessionScope scope(long generation, AiRole role, Audience audience, String policy) {
        return new SessionScope(ADVENTURE, CAMPAIGN, generation, role, audience, PACK, policy);
    }

    private TaskResult run(SessionScope scope, String text) throws Exception {
        return sessions.submit(scope, UUID.randomUUID(), InferenceSlot.STANDARD, text,
                CodexTaskAdapterTest.SCHEMA, Duration.ofSeconds(5)).result().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    /** @return thread of the n-th turn/start (0-based) */
    private String turnThread(int index) {
        return server.received("turn/start").get(index).params().path("threadId").stringValue();
    }

    private static String turnText(ScriptedAppServer.Received turn) {
        return turn.params().path("input").path(0).path("text").stringValue();
    }

    @Test
    void privilegedPlanningAndPublicWorkNeverShareARuntimeThread() throws Exception {
        start();
        SessionScope planner = scope(1, AiRole.QUEST_PLANNER, Audience.PRIVILEGED, POLICY);
        SessionScope narrator = scope(1, AiRole.NARRATOR, Audience.PUBLIC, POLICY);
        SessionScope interpreter = scope(1, AiRole.INTERPRETER, Audience.PUBLIC, POLICY);
        SessionScope tactician = scope(1, AiRole.TACTICIAN, Audience.character("npc.mara"), POLICY);

        TaskResult plan = run(planner, "Plan the quest. Secret canon: " + SENTINEL + " controls the mill.");
        TaskResult narration = run(narrator, "Narrate the public events.");
        TaskResult refine = run(planner, "Refine the plan.");
        TaskResult narrationAgain = run(narrator, "Narrate the next public events.");
        TaskResult interpretation = run(interpreter, "Interpret the declaration.");
        TaskResult tactic = run(tactician, "Choose a legal candidate.");

        for (TaskResult result : List.of(plan, narration, refine, narrationAgain, interpretation, tactic)) {
            assertThat(result.status()).isEqualTo(Status.COMPLETED);
        }
        assertThat(List.of(plan, narration, refine, narrationAgain, interpretation, tactic))
                .extracting(TaskResult::freshSession).containsExactly(true, true, false, false, true, true);
        assertThat(server.received("thread/start")).hasSize(4);

        String plannerThread = turnThread(0);
        String narratorThread = turnThread(1);
        assertThat(turnThread(2)).isEqualTo(plannerThread);
        assertThat(turnThread(3)).isEqualTo(narratorThread);
        assertThat(List.of(plannerThread, narratorThread, turnThread(4), turnThread(5))).doesNotHaveDuplicates();

        // The sentinel reached the runtime exactly once, on the planner's thread.
        List<ScriptedAppServer.Received> carrying = server.received().stream()
                .filter(frame -> frame.params() != null && frame.params().toString().contains(SENTINEL)).toList();
        assertThat(carrying).singleElement().satisfies(frame -> {
            assertThat(frame.method()).isEqualTo("turn/start");
            assertThat(frame.params().path("threadId").stringValue()).isEqualTo(plannerThread);
        });
        for (ScriptedAppServer.Received turn : server.received("turn/start")) {
            if (!turn.params().path("threadId").stringValue().equals(plannerThread)) {
                assertThat(turnText(turn)).doesNotContain(SENTINEL);
            }
        }
    }

    @Test
    void renewalPolicyChangeRetirementAndRestoreStartFreshThreadsOnlyWhereAffected() throws Exception {
        start();
        SessionScope planner = scope(1, AiRole.QUEST_PLANNER, Audience.PRIVILEGED, POLICY);
        SessionScope narrator = scope(1, AiRole.NARRATOR, Audience.PUBLIC, POLICY);
        assertThat(run(planner, "Plan. " + SENTINEL).freshSession()).isTrue();
        assertThat(run(narrator, "Narrate.").freshSession()).isTrue();
        String plannerThread = turnThread(0);
        String narratorThread = turnThread(1);

        // Renewal of the narrator: a fresh thread for it, continuity for the planner.
        assertThat(sessions.retire(narrator)).isTrue();
        assertThat(run(narrator, "Narrate after renewal.").freshSession()).isTrue();
        assertThat(turnThread(2)).isNotIn(narratorThread, plannerThread);
        assertThat(run(planner, "Refine.").freshSession()).isFalse();
        assertThat(turnThread(3)).isEqualTo(plannerThread);

        // A policy change renews the narrator line; the old-policy scope is gone.
        SessionScope repoliced = scope(1, AiRole.NARRATOR, Audience.PUBLIC, "c".repeat(64));
        assertThat(run(repoliced, "Narrate under the new policy.").freshSession()).isTrue();
        assertThat(sessions.activeSession(narrator)).isEmpty();
        assertThat(turnThread(4)).isNotIn(turnThread(2), plannerThread);

        // Retiring the campaign ends the planner's thread binding as well.
        assertThat(sessions.retireCampaign(CAMPAIGN)).isEqualTo(2);
        assertThat(run(planner, "Plan again.").freshSession()).isTrue();
        assertThat(turnThread(5)).isNotEqualTo(plannerThread);

        // A restore (generation 2) supersedes generation 1; a late generation-1 task sends nothing.
        SessionScope restoredNarrator = scope(2, AiRole.NARRATOR, Audience.PUBLIC, "c".repeat(64));
        assertThat(run(restoredNarrator, "Narrate after the restore.").freshSession()).isTrue();
        int frames = server.received().size();
        TaskResult stale = run(planner, "Late planner callback.");
        assertThat(stale.status()).isEqualTo(Status.REJECTED);
        assertThat(stale.errorCode()).isEqualTo(ScopedSessions.STALE_GENERATION);
        assertThat(server.received()).hasSize(frames);

        assertThat(server.received("thread/start")).hasSize(6);
        assertThat(server.received("turn/start").stream().map(ScopedThreadIsolationTest::turnText)
                .filter(text -> text.contains(SENTINEL))).hasSize(1);
    }
}
