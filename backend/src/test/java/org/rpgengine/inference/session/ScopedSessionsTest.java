package org.rpgengine.inference.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.CompletedTaskHandle;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;

/**
 * Registry rules of {@link ScopedSessions}: reuse, separation, supersession on content, policy or
 * generation changes, the generation fence, the adventure consistency check, explicit, campaign
 * and installation retirement, the capacity bound and concurrent use. Every retirement must reach
 * the port, because the adapter's conversation binding is what actually holds earlier context.
 */
class ScopedSessionsTest {
    static final UUID ADVENTURE = SessionScopeTest.ADVENTURE;
    static final UUID CAMPAIGN = SessionScopeTest.CAMPAIGN;
    static final UUID OTHER_CAMPAIGN = UUID.fromString("00000000-0000-4000-8000-000000000004");
    static final String PACK = SessionScopeTest.PACK;
    static final String POLICY = SessionScopeTest.POLICY;

    /** Port double: completes every task at once and records requests and retirements. */
    static final class RecordingPort implements NarrationTaskPort {
        final List<TaskRequest> requests = Collections.synchronizedList(new ArrayList<>());
        final List<UUID> retired = Collections.synchronizedList(new ArrayList<>());

        @Override
        public TaskHandle submit(TaskRequest request) {
            requests.add(request);
            return new CompletedTaskHandle(new TaskResult(request.taskId(), TaskResult.Status.COMPLETED, "{}", null,
                    new TaskResult.Provenance("recording", "1", "fixture-model", "low"), false));
        }

        @Override
        public void retireSession(UUID sessionId) {
            retired.add(sessionId);
        }

        @Override
        public RuntimeStatus status() {
            throw new UnsupportedOperationException("not used by the registry");
        }
    }

    /** Deterministic identity source: 00000000-0000-4000-8000-0000000010NN. */
    static Supplier<UUID> sequence() {
        int[] next = {0};
        return () -> UUID.fromString(String.format("00000000-0000-4000-8000-%012x", 0x1000 + ++next[0]));
    }

    final RecordingPort port = new RecordingPort();
    final ScopedSessions sessions = new ScopedSessions(port, 8, sequence());

    static SessionScope scope(UUID campaign, long generation, AiRole role, Audience audience, String policy) {
        return new SessionScope(ADVENTURE, campaign, generation, role, audience, PACK, policy);
    }

    static SessionScope narrator(long generation, String policy) {
        return scope(CAMPAIGN, generation, AiRole.NARRATOR, Audience.PUBLIC, policy);
    }

    static SessionScope planner(long generation) {
        return scope(CAMPAIGN, generation, AiRole.QUEST_PLANNER, Audience.PRIVILEGED, POLICY);
    }

    TaskResult run(SessionScope scope) throws Exception {
        return sessions.submit(scope, UUID.randomUUID(), InferenceSlot.STANDARD, "task", "{}", Duration.ofSeconds(5))
                .result().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    UUID lastSession() {
        return port.requests.getLast().sessionId();
    }

    @Test
    void anEqualScopeReusesItsSessionAndEveryOtherScopeGetsItsOwn() throws Exception {
        run(narrator(1, POLICY));
        UUID narration = lastSession();
        run(narrator(1, POLICY));
        assertThat(lastSession()).isEqualTo(narration);

        List<SessionScope> others = List.of(planner(1),
                scope(CAMPAIGN, 1, AiRole.INTERPRETER, Audience.PUBLIC, POLICY),
                scope(CAMPAIGN, 1, AiRole.TACTICIAN, Audience.character("npc.mara"), POLICY),
                scope(CAMPAIGN, 1, AiRole.TACTICIAN, Audience.character("npc.tullo"), POLICY),
                scope(OTHER_CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, POLICY));
        Set<UUID> seen = new HashSet<>(Set.of(narration));
        for (SessionScope other : others) {
            run(other);
            assertThat(seen.add(lastSession())).as(other.toString()).isTrue();
            assertThat(sessions.activeSession(other)).contains(lastSession());
        }
        // Distinct lines coexist: nothing was superseded or retired.
        assertThat(port.retired).isEmpty();
        assertThat(sessions.activeSession(narrator(1, POLICY))).contains(narration);
        assertThat(sessions.status().active()).isEqualTo(6);
        // The request is forwarded unchanged apart from the session.
        TaskRequest request = port.requests.getLast();
        assertThat(request.slot()).isEqualTo(InferenceSlot.STANDARD);
        assertThat(request.instructions()).isEqualTo("task");
        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void aContentOrPolicyChangeSupersedesOnlyThatLine() throws Exception {
        run(planner(1));
        UUID planning = lastSession();
        run(narrator(1, POLICY));
        UUID original = lastSession();
        run(narrator(1, "c".repeat(64)));
        UUID repoliced = lastSession();
        run(new SessionScope(ADVENTURE, CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, "d".repeat(64), "c".repeat(64)));
        UUID repacked = lastSession();

        assertThat(Set.of(original, repoliced, repacked)).hasSize(3);
        assertThat(port.retired).containsExactly(original, repoliced);
        assertThat(sessions.activeSession(narrator(1, POLICY))).isEmpty();
        assertThat(sessions.activeSession(planner(1))).contains(planning);
        assertThat(sessions.status().retired()).containsEntry(ScopedSessions.Reason.SUPERSEDED, 2L);
    }

    @Test
    void aNewGenerationRetiresEveryOlderSessionOfItsCampaignAndFencesStaleTasks() throws Exception {
        run(planner(1));
        UUID planning = lastSession();
        run(narrator(1, POLICY));
        UUID narration = lastSession();
        run(scope(OTHER_CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, POLICY));
        UUID unrelated = lastSession();

        run(narrator(2, POLICY));
        UUID restored = lastSession();
        assertThat(port.retired).containsExactlyInAnyOrder(planning, narration);
        assertThat(sessions.activeSession(scope(OTHER_CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, POLICY)))
                .contains(unrelated);

        int submitted = port.requests.size();
        for (SessionScope stale : List.of(planner(1), narrator(1, POLICY))) {
            TaskResult refused = run(stale);
            assertThat(refused.status()).isEqualTo(TaskResult.Status.REJECTED);
            assertThat(refused.errorCode()).isEqualTo(ScopedSessions.STALE_GENERATION);
            assertThat(refused.provenance()).isNull();
        }
        assertThat(port.requests).hasSize(submitted);
        assertThat(port.retired).hasSize(2);
        assertThat(sessions.activeSession(narrator(2, POLICY))).contains(restored);
    }

    @Test
    void aCampaignScopedUnderAnotherAdventureIsRefused() throws Exception {
        run(narrator(1, POLICY));
        UUID narration = lastSession();
        var foreign = new SessionScope(UUID.fromString("00000000-0000-4000-8000-000000000099"), CAMPAIGN, 1,
                AiRole.INTERPRETER, Audience.PUBLIC, PACK, POLICY);
        TaskResult refused = run(foreign);
        assertThat(refused.status()).isEqualTo(TaskResult.Status.REJECTED);
        assertThat(refused.errorCode()).isEqualTo(ScopedSessions.SCOPE_CONFLICT);
        assertThat(port.requests).hasSize(1);
        assertThat(sessions.activeSession(narrator(1, POLICY))).contains(narration);
        assertThat(sessions.activeSession(foreign)).isEmpty();
    }

    @Test
    void explicitCampaignAndInstallationRetirementReachThePort() throws Exception {
        run(narrator(1, POLICY));
        UUID narration = lastSession();
        assertThat(sessions.retire(narrator(1, POLICY))).isTrue();
        assertThat(sessions.retire(narrator(1, POLICY))).isFalse();
        assertThat(port.retired).containsExactly(narration);
        run(narrator(1, POLICY));
        UUID renewed = lastSession();
        assertThat(renewed).isNotEqualTo(narration);

        run(planner(1));
        UUID planning = lastSession();
        run(scope(OTHER_CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, POLICY));
        UUID unrelated = lastSession();
        assertThat(sessions.retireCampaign(CAMPAIGN)).isEqualTo(2);
        assertThat(port.retired).containsExactly(narration, renewed, planning);
        assertThat(sessions.retireCampaign(CAMPAIGN)).isZero();
        assertThat(sessions.activeSession(scope(OTHER_CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, POLICY)))
                .contains(unrelated);

        assertThat(sessions.retireAll()).isEqualTo(1);
        assertThat(port.retired).containsExactly(narration, renewed, planning, unrelated);
        assertThat(sessions.status()).isEqualTo(new ScopedSessions.Status(0, Map.of(ScopedSessions.Reason.EXPLICIT, 1L,
                ScopedSessions.Reason.SUPERSEDED, 0L, ScopedSessions.Reason.CAMPAIGN, 2L,
                ScopedSessions.Reason.INSTALLATION, 1L, ScopedSessions.Reason.CAPACITY, 0L)));
    }

    @Test
    void theLeastRecentlyUsedSessionIsRetiredAtTheCapacityBound() throws Exception {
        var bounded = new ScopedSessions(port, 2, sequence());
        SessionScope a = narrator(1, POLICY);
        SessionScope b = planner(1);
        SessionScope c = scope(OTHER_CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, POLICY);
        bounded.submit(a, UUID.randomUUID(), InferenceSlot.LIGHT, "a", "{}", Duration.ofSeconds(5));
        UUID first = lastSession();
        bounded.submit(b, UUID.randomUUID(), InferenceSlot.LIGHT, "b", "{}", Duration.ofSeconds(5));
        UUID second = lastSession();
        // Using a again makes b the least recently used; a diagnostic read does not count as use.
        bounded.submit(a, UUID.randomUUID(), InferenceSlot.LIGHT, "a", "{}", Duration.ofSeconds(5));
        assertThat(bounded.activeSession(b)).contains(second);
        bounded.submit(c, UUID.randomUUID(), InferenceSlot.LIGHT, "c", "{}", Duration.ofSeconds(5));

        assertThat(port.retired).containsExactly(second);
        assertThat(bounded.activeSession(a)).contains(first);
        assertThat(bounded.activeSession(b)).isEmpty();
        assertThat(bounded.status().retired()).containsEntry(ScopedSessions.Reason.CAPACITY, 1L);
    }

    @Test
    void invalidConfigurationAndARepeatedIdentityFailLoudly() throws Exception {
        assertThatThrownBy(() -> new ScopedSessions(port, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopedSessions(port, ScopedSessions.MAX_CAPACITY + 1))
                .isInstanceOf(IllegalArgumentException.class);
        UUID fixed = UUID.fromString("00000000-0000-4000-8000-000000000777");
        var repeating = new ScopedSessions(port, 4, () -> fixed);
        repeating.submit(narrator(1, POLICY), UUID.randomUUID(), InferenceSlot.LIGHT, "a", "{}", Duration.ofSeconds(5));
        assertThatThrownBy(() -> repeating.submit(planner(1), UUID.randomUUID(), InferenceSlot.LIGHT, "b", "{}",
                Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sessions.submit(null, UUID.randomUUID(), InferenceSlot.LIGHT, "a", "{}",
                Duration.ofSeconds(5))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void concurrentSubmissionsOfOneScopeShareExactlyOneSession() throws Exception {
        var shared = new ScopedSessions(port, 64);
        List<SessionScope> scopes = List.of(narrator(1, POLICY), planner(1),
                scope(CAMPAIGN, 1, AiRole.INTERPRETER, Audience.PUBLIC, POLICY),
                scope(OTHER_CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, POLICY));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int offset = worker;
                work.add(pool.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        shared.submit(scopes.get((i + offset) % scopes.size()), UUID.randomUUID(), InferenceSlot.LIGHT,
                                "x", "{}", Duration.ofSeconds(5));
                    }
                    return null;
                }));
            }
            for (Future<?> future : work) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        Set<UUID> used;
        synchronized (port.requests) {
            used = new HashSet<>(port.requests.stream().map(TaskRequest::sessionId).toList());
        }
        assertThat(port.requests).hasSize(1_600);
        assertThat(used).hasSize(4);
        for (SessionScope scope : scopes) {
            assertThat(used).contains(shared.activeSession(scope).orElseThrow());
        }
        assertThat(port.retired).isEmpty();
    }
}
