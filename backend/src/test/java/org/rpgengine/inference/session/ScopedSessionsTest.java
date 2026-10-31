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
 * Since P09.4 it also proves the renewal triggers (turn ceiling, context pressure, unreliable
 * turns, owner renewal), deferral at an unsafe boundary, rehydration blocks only on a line's first
 * task after renewal or restart, and that a failing or oversized rehydration costs continuity only.
 */
class ScopedSessionsTest {
    static final UUID ADVENTURE = SessionScopeTest.ADVENTURE;
    static final UUID CAMPAIGN = SessionScopeTest.CAMPAIGN;
    static final UUID OTHER_CAMPAIGN = UUID.fromString("00000000-0000-4000-8000-000000000004");
    static final String PACK = SessionScopeTest.PACK;
    static final String POLICY = SessionScopeTest.POLICY;

    /** Port double: completes every task at once and records requests and retirements. */
    static class RecordingPort implements NarrationTaskPort {
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
                ScopedSessions.Reason.INSTALLATION, 1L, ScopedSessions.Reason.CAPACITY, 0L,
                ScopedSessions.Reason.TURN_CEILING, 0L, ScopedSessions.Reason.CONTEXT_PRESSURE, 0L,
                ScopedSessions.Reason.UNRELIABLE, 0L, ScopedSessions.Reason.OWNER, 0L,
                ScopedSessions.Reason.SLOT_CHANGED, 0L)));
    }

    @Test
    void aSlotChangeRetiresOnlyTheSessionsBoundToThatSlot() throws Exception {
        SessionScope onStandard = narrator(1, POLICY);
        SessionScope onLight = scope(CAMPAIGN, 1, AiRole.INTERPRETER, Audience.PUBLIC, POLICY);
        SessionScope onDeliberate = planner(1);
        SessionScope elsewhere = scope(OTHER_CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, POLICY);
        sessions.submit(onStandard, UUID.randomUUID(), InferenceSlot.STANDARD, "a", "{}", Duration.ofSeconds(5));
        UUID standard = lastSession();
        sessions.submit(onLight, UUID.randomUUID(), InferenceSlot.LIGHT, "b", "{}", Duration.ofSeconds(5));
        UUID light = lastSession();
        sessions.submit(onDeliberate, UUID.randomUUID(), InferenceSlot.DELIBERATE, "c", "{}", Duration.ofSeconds(5));
        UUID deliberate = lastSession();
        sessions.submit(elsewhere, UUID.randomUUID(), InferenceSlot.STANDARD, "d", "{}", Duration.ofSeconds(5));
        UUID otherCampaign = lastSession();

        assertThat(sessions.retireSlot(InferenceSlot.STANDARD)).isEqualTo(2);

        assertThat(port.retired).containsExactlyInAnyOrder(standard, otherCampaign);
        assertThat(sessions.activeSession(onStandard)).isEmpty();
        assertThat(sessions.activeSession(elsewhere)).isEmpty();
        assertThat(sessions.activeSession(onLight)).contains(light);
        assertThat(sessions.activeSession(onDeliberate)).contains(deliberate);
        assertThat(sessions.status().retired()).containsEntry(ScopedSessions.Reason.SLOT_CHANGED, 2L);
        assertThat(sessions.retireSlot(InferenceSlot.STANDARD)).isZero();

        // The next task of the line opens a new session, and the kept sessions continue unchanged.
        sessions.submit(onStandard, UUID.randomUUID(), InferenceSlot.STANDARD, "a", "{}", Duration.ofSeconds(5));
        assertThat(lastSession()).isNotEqualTo(standard);
        sessions.submit(onLight, UUID.randomUUID(), InferenceSlot.LIGHT, "b", "{}", Duration.ofSeconds(5));
        assertThat(lastSession()).isEqualTo(light);
    }

    @Test
    void aSessionIsBoundToEverySlotItRanUnder() throws Exception {
        SessionScope scope = narrator(1, POLICY);
        sessions.submit(scope, UUID.randomUUID(), InferenceSlot.LIGHT, "a", "{}", Duration.ofSeconds(5));
        UUID first = lastSession();
        sessions.submit(scope, UUID.randomUUID(), InferenceSlot.STANDARD, "b", "{}", Duration.ofSeconds(5));
        assertThat(lastSession()).isEqualTo(first);

        assertThat(sessions.retireSlot(InferenceSlot.DELIBERATE)).isZero();
        assertThat(sessions.activeSession(scope)).contains(first);
        assertThat(sessions.retireSlot(InferenceSlot.LIGHT)).isEqualTo(1);
        assertThat(port.retired).containsExactly(first);
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

    // ------------------------------------------------------------------ P09.4 renewal rules

    /** Journal double recording every call as text. */
    static final class RecordingJournal implements SessionJournal {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        boolean history;

        @Override
        public boolean hasHistory(UUID campaignId, AiRole role, Audience audience) {
            return history;
        }

        @Override
        public void opened(Opened record) {
            calls.add("opened " + record.cause() + " " + record.basis() + (record.code() == null ? "" : " " + record.code()));
        }

        @Override
        public void used(UUID sessionId, Usage usage, java.time.Instant at) {
            calls.add("used " + usage.turns() + "/" + usage.completed());
        }

        @Override
        public void retired(UUID sessionId, ScopedSessions.Reason reason, java.time.Instant at) {
            calls.add("retired " + reason);
        }

        @Override
        public int recover(java.time.Instant at) {
            calls.add("recover");
            return 0;
        }
    }

    /** Rehydrator double: answers every preparation with the next scripted result (default a small block). */
    static final class ScriptedRehydrator implements Rehydrator {
        final List<String> causes = Collections.synchronizedList(new ArrayList<>());
        final java.util.Deque<Result> script = new java.util.ArrayDeque<>();

        @Override
        public synchronized Result prepare(SessionScope scope, String cause, boolean deferrable) {
            causes.add(cause + (deferrable ? " deferrable" : ""));
            return script.isEmpty() ? block("BLOCK") : script.poll();
        }

        static Ready block(String text) {
            return new Ready(text, "STATE", 7, "e".repeat(64), null, null, "NONE");
        }
    }

    final RecordingJournal journal = new RecordingJournal();
    final ScriptedRehydrator rehydrator = new ScriptedRehydrator();

    ScopedSessions renewing(RenewalPolicy policy) {
        return new ScopedSessions(port, 8, policy, journal, rehydrator, sequence(), java.time.Clock.systemUTC());
    }

    /** Submit, wait for the port's result and report it as the scheduler does. */
    static TaskResult settle(ScopedSessions registry, SessionScope scope, String instructions) throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskResult result = registry.submit(scope, taskId, InferenceSlot.LIGHT, instructions, "{}", Duration.ofSeconds(5))
                .result().toCompletableFuture().get(1, TimeUnit.SECONDS);
        registry.settled(taskId, result);
        return result;
    }

    @Test
    void theTurnCeilingRenewsAtTheNextTaskWhichCarriesTheContinuityBlock() throws Exception {
        var registry = renewing(new RenewalPolicy(2, 128_000, 75, 85));
        settle(registry, narrator(1, POLICY), "a");
        settle(registry, narrator(1, POLICY), "b");
        UUID first = lastSession();
        assertThat(registry.usage(narrator(1, POLICY)).orElseThrow().completed()).isEqualTo(2);
        settle(registry, narrator(1, POLICY), "c");

        assertThat(lastSession()).isNotEqualTo(first);
        assertThat(port.requests.getLast().instructions()).isEqualTo("BLOCK" + ScopedSessions.SEPARATOR + "c");
        assertThat(port.requests.get(1).instructions()).isEqualTo("b");
        assertThat(port.retired).containsExactly(first);
        assertThat(rehydrator.causes).containsExactly("turn-ceiling deferrable");
        assertThat(journal.calls).containsExactly("opened FIRST NONE", "used 1/1", "used 2/2", "retired TURN_CEILING",
                "opened TURN_CEILING STATE", "used 1/1");
        assertThat(registry.status().retired()).containsEntry(ScopedSessions.Reason.TURN_CEILING, 1L);
        assertThat(registry.status().rehydrated()).isEqualTo(1);
        // Only the first task of the new session carries the block.
        settle(registry, narrator(1, POLICY), "d");
        assertThat(port.requests.getLast().instructions()).isEqualTo("d");
    }

    @Test
    void contextPressureRenewsOnlyAfterATurnAndTheWarningIsCountedOnce() throws Exception {
        var registry = renewing(new RenewalPolicy(10, 8_000, 20, 60));
        String large = "x".repeat(14_000);
        // 3,500 input units + 1,024 allowance: below 4,800, so the fresh session takes it.
        settle(registry, narrator(1, POLICY), large);
        UUID first = lastSession();
        assertThat(registry.status().warned()).isEqualTo(1);
        // A second identical task would reach about 7,000 units: the line is renewed first.
        settle(registry, narrator(1, POLICY), large);
        assertThat(lastSession()).isNotEqualTo(first);
        assertThat(registry.status().retired()).containsEntry(ScopedSessions.Reason.CONTEXT_PRESSURE, 1L);
        assertThat(rehydrator.causes).containsExactly("context-pressure deferrable");

        // An oversized single task in a fresh session runs there: no renewal loop.
        var fresh = renewing(new RenewalPolicy(10, 8_000, 20, 60));
        settle(fresh, planner(1), "y".repeat(40_000));
        assertThat(fresh.status().retired()).containsEntry(ScopedSessions.Reason.CONTEXT_PRESSURE, 0L);
    }

    @Test
    void anUnconfirmedOrFailedTurnRetiresTheSessionAndTurnlessResultsCountNothing() throws Exception {
        var failing = new RecordingPort() {
            @Override
            public TaskHandle submit(TaskRequest request) {
                requests.add(request);
                return new CompletedTaskHandle(new TaskResult(request.taskId(), TaskResult.Status.UNRESOLVED, null,
                        "connection-lost", null, false));
            }
        };
        var registry = new ScopedSessions(failing, 8, RenewalPolicy.defaults(), journal, rehydrator, sequence(),
                java.time.Clock.systemUTC());
        settle(registry, narrator(1, POLICY), "a");
        UUID lost = failing.requests.getLast().sessionId();
        assertThat(failing.retired).containsExactly(lost);
        assertThat(registry.activeSession(narrator(1, POLICY))).isEmpty();
        assertThat(journal.calls).containsExactly("opened FIRST NONE", "used 1/0", "retired UNRELIABLE");

        var registry2 = renewing(RenewalPolicy.defaults());
        UUID taskId = UUID.randomUUID();
        registry2.submit(narrator(1, POLICY), taskId, InferenceSlot.LIGHT, "a", "{}", Duration.ofSeconds(5));
        registry2.settled(taskId, TaskResult.withoutTurn(taskId, TaskResult.Status.REJECTED, "queue-full"));
        assertThat(registry2.usage(narrator(1, POLICY)).orElseThrow().turns()).isZero();
        registry2.settled(UUID.randomUUID(), TaskResult.withoutTurn(UUID.randomUUID(), TaskResult.Status.FAILED, "x"));
        assertThat(registry2.status().retired()).containsEntry(ScopedSessions.Reason.UNRELIABLE, 0L);
    }

    @Test
    void aDeferredRenewalKeepsTheSessionAndAnOversizedBlockIsOmitted() throws Exception {
        var registry = renewing(new RenewalPolicy(1, 128_000, 75, 85));
        settle(registry, narrator(1, POLICY), "a");
        UUID first = lastSession();
        rehydrator.script.add(new Rehydrator.Deferred("unsafe-boundary"));
        settle(registry, narrator(1, POLICY), "b");
        assertThat(lastSession()).isEqualTo(first);
        assertThat(port.requests.getLast().instructions()).isEqualTo("b");
        assertThat(registry.status().deferred()).isEqualTo(1);

        String huge = "z".repeat(TaskRequest.MAX_INSTRUCTION_BYTES);
        rehydrator.script.add(ScriptedRehydrator.block(huge));
        settle(registry, narrator(1, POLICY), "c");
        assertThat(lastSession()).isNotEqualTo(first);
        assertThat(port.requests.getLast().instructions()).isEqualTo("c");
        assertThat(journal.calls).contains("opened TURN_CEILING OMITTED");
        assertThat(registry.status().rehydrated()).isZero();
    }

    @Test
    void aSlotChangeIsJournaledAndTheNextSessionCarriesTheContinuityBlock() throws Exception {
        var registry = renewing(RenewalPolicy.defaults());
        settle(registry, narrator(1, POLICY), "a");
        assertThat(registry.retireSlot(InferenceSlot.LIGHT)).isEqualTo(1);
        settle(registry, narrator(1, POLICY), "b");
        assertThat(port.requests.getLast().instructions()).isEqualTo("BLOCK" + ScopedSessions.SEPARATOR + "b");
        assertThat(rehydrator.causes).containsExactly("slot-changed");
        assertThat(journal.calls).containsExactly("opened FIRST NONE", "used 1/1", "retired SLOT_CHANGED",
                "opened SLOT_CHANGED STATE", "used 1/1");
    }

    @Test
    void ownerRenewalRetiresTheCampaignAndTheJournalRemembersEarlierProcesses() throws Exception {
        var registry = renewing(RenewalPolicy.defaults());
        settle(registry, narrator(1, POLICY), "a");
        settle(registry, planner(1), "b");
        settle(registry, scope(OTHER_CAMPAIGN, 1, AiRole.NARRATOR, Audience.PUBLIC, POLICY), "c");
        assertThat(registry.renewCampaign(CAMPAIGN)).isEqualTo(2);
        assertThat(registry.status().retired()).containsEntry(ScopedSessions.Reason.OWNER, 2L);
        settle(registry, narrator(1, POLICY), "d");
        assertThat(port.requests.getLast().instructions()).startsWith("BLOCK");
        assertThat(rehydrator.causes).containsExactly("owner");

        // A new process knows nothing in memory; the journal's history still rehydrates the line.
        journal.history = true;
        var restarted = renewing(RenewalPolicy.defaults());
        restarted.recover();
        settle(restarted, narrator(1, POLICY), "e");
        assertThat(port.requests.getLast().instructions()).startsWith("BLOCK");
        assertThat(rehydrator.causes).containsExactly("owner", "restart");
        assertThat(journal.calls).contains("recover", "opened RESTART STATE");

        // A failing rehydrator costs continuity only.
        var broken = new ScopedSessions(port, 8, RenewalPolicy.defaults(), journal, (scope, cause, deferrable) -> {
            throw new IllegalStateException("boom");
        }, sequence(), java.time.Clock.systemUTC());
        settle(broken, narrator(1, POLICY), "f");
        assertThat(port.requests.getLast().instructions()).isEqualTo("f");
        assertThat(journal.calls).contains("opened RESTART UNAVAILABLE rehydration-failed");
    }

    @Test
    void renewalSettingsAreValidated() {
        assertThat(RenewalPolicy.defaults()).isEqualTo(new RenewalPolicy(10, 128_000, 75, 85));
        assertThat(RenewalPolicy.defaults().renewUnits()).isEqualTo(108_800);
        assertThatThrownBy(() -> new RenewalPolicy(0, 128_000, 75, 85)).hasMessageContaining("turn-ceiling");
        assertThatThrownBy(() -> new RenewalPolicy(101, 128_000, 75, 85)).hasMessageContaining("turn-ceiling");
        assertThatThrownBy(() -> new RenewalPolicy(10, 7_999, 75, 85)).hasMessageContaining("context-window");
        assertThatThrownBy(() -> new RenewalPolicy(10, 128_000, 85, 85)).hasMessageContaining("context-warn-percent");
        assertThatThrownBy(() -> new RenewalPolicy(10, 128_000, 75, 100)).hasMessageContaining("context-renew-percent");
    }
}
