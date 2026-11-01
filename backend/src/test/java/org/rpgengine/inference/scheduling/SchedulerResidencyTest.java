package org.rpgengine.inference.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.inference.session.SessionScope;

/**
 * The scheduler's side of residency, with a recording {@link TextResidency}, a scripted port and an in-memory
 * ledger, so no GPU, server or database takes part. The scheduler is not started: every pass runs on the calling
 * thread, so each step below has finished when its call returns.
 */
class SchedulerResidencyTest {
    private static final Duration BUDGET = Duration.ofSeconds(60);

    /** A clock the test moves. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-01T12:00:00Z");

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** Records claims and releases in one list, in order, and leaves every answer to the test. */
    private static final class RecordingResidency implements TextResidency {
        private final List<String> log = new ArrayList<>();
        private final List<Listener> listeners = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public Claim claim(ScheduledTask task, Listener listener) {
            if (failure != null) {
                throw failure;
            }
            int number = listeners.size();
            log.add("claim " + number);
            listeners.add(listener);
            return yielded -> log.add((yielded ? "yield " : "finish ") + number);
        }

        Listener listener() {
            return listeners.getLast();
        }
    }

    private final TestClock clock = new TestClock();
    private final ControlledPort port = new ControlledPort();
    private final MemoryTaskLedger ledger = new MemoryTaskLedger();
    private final RecordingResidency residency = new RecordingResidency();
    private final InferenceScheduler scheduler = scheduler(port, ledger, residency);

    private InferenceScheduler scheduler(NarrationTaskPort runtime, TaskLedger tasks, TextResidency text) {
        var built = new InferenceScheduler(new ScopedSessions(runtime, 64), runtime, tasks, SchedulerSettings.defaults(),
                clock, () -> 0.5, text);
        built.recover();
        return built;
    }

    private static ScheduledTask task(AiRole role, WorkClass workClass) {
        Audience audience = role.permits(Audience.PUBLIC) ? Audience.PUBLIC : Audience.PRIVILEGED;
        return new ScheduledTask(UUID.randomUUID(), new SessionScope(UUID.randomUUID(), UUID.randomUUID(), 0, role,
                audience, "a".repeat(64), "b".repeat(64)), workClass, InferenceSlot.STANDARD, "Task.", "{\"type\":\"object\"}",
                BUDGET);
    }

    private static ScheduledTask narration() {
        return task(AiRole.NARRATOR, WorkClass.INTERACTIVE);
    }

    private static ScheduledTask plan() {
        return task(AiRole.QUEST_PLANNER, WorkClass.PREPARATION);
    }

    private static TaskResult result(TaskHandle handle) throws Exception {
        return handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private List<UUID> sent() {
        return port.requests.stream().map(TaskRequest::taskId).toList();
    }

    @Test
    void aTaskHoldingThePermitWaitsForItsModelAndReleasesItWhenItsResultArrives() throws Exception {
        ScheduledTask task = narration();
        TaskHandle handle = scheduler.submit(task);

        // It holds the permit, and nothing reaches the port before the claim says ready.
        assertThat(residency.log).containsExactly("claim 0");
        assertThat(sent()).isEmpty();
        assertThat(ledger.row(task.taskId()).status()).isEqualTo("DISPATCHED");
        assertThat(handle.result().toCompletableFuture()).isNotDone();

        residency.listener().ready();
        assertThat(sent()).containsExactly(task.taskId());
        assertThat(residency.log).containsExactly("claim 0");

        port.complete(task.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(handle).status()).isEqualTo(TaskResult.Status.COMPLETED);
        assertThat(residency.log).containsExactly("claim 0", "finish 0");
        assertThat(ledger.row(task.taskId()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void theForwardedBudgetIsWhatRemainsAfterTheWait() {
        scheduler.submit(narration());
        clock.advance(Duration.ofSeconds(10));
        residency.listener().ready();
        assertThat(port.requests.getFirst().timeout()).isEqualTo(BUDGET.minusSeconds(10));
    }

    @Test
    void withoutResidencyADispatchedTaskIsForwardedAtOnce() throws Exception {
        var plainPort = new ControlledPort();
        var plain = new InferenceScheduler(new ScopedSessions(plainPort, 64), plainPort, new MemoryTaskLedger(),
                SchedulerSettings.defaults(), clock, () -> 0.5);
        plain.recover();
        ScheduledTask task = narration();
        TaskHandle handle = plain.submit(task);

        assertThat(plainPort.requests).hasSize(1);
        assertThat(plainPort.requests.getFirst().timeout()).isEqualTo(BUDGET);
        plainPort.complete(task.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(handle).status()).isEqualTo(TaskResult.Status.COMPLETED);
    }

    @Test
    void aCancelledWaitEndsAtOnceWithoutATurnAndFreesThePermit() throws Exception {
        ScheduledTask first = narration();
        TaskHandle handle = scheduler.submit(first);
        handle.cancel();

        TaskResult cancelled = result(handle);
        assertThat(cancelled.status()).isEqualTo(TaskResult.Status.CANCELLED);
        assertThat(cancelled.errorCode()).isEqualTo(InferenceScheduler.CANCELLED);
        assertThat(cancelled.provenance()).isNull();
        assertThat(residency.log).containsExactly("claim 0", "finish 0");
        assertThat(ledger.row(first.taskId()).charged()).isZero();

        // A late answer changes nothing, and the next task can take the permit.
        var late = residency.listener();
        late.ready();
        assertThat(sent()).isEmpty();
        scheduler.submit(narration());
        assertThat(residency.log).containsExactly("claim 0", "finish 0", "claim 1");
    }

    @Test
    void aLateReadyAnswerAfterARefusalStartsNoTurn() throws Exception {
        ScheduledTask task = narration();
        TaskHandle handle = scheduler.submit(task);
        var answer = residency.listener();
        answer.refused(TextResidency.LOST);
        answer.ready();
        assertThat(result(handle).status()).isEqualTo(TaskResult.Status.UNAVAILABLE);
        assertThat(sent()).isEmpty();
        assertThat(residency.log).containsExactly("claim 0", "finish 0");
    }

    @Test
    void aWaitThatOutlastsTheBudgetEndsAsAQueueTimeout() throws Exception {
        ScheduledTask task = narration();
        TaskHandle handle = scheduler.submit(task);
        clock.advance(BUDGET);
        // Any pass enforces the deadline; a further submission is the simplest one to cause here.
        scheduler.submit(plan());

        TaskResult timedOut = result(handle);
        assertThat(timedOut.status()).isEqualTo(TaskResult.Status.TIMED_OUT);
        assertThat(timedOut.errorCode()).isEqualTo(InferenceScheduler.QUEUE_TIMEOUT);
        assertThat(residency.log).startsWith("claim 0", "finish 0");
        assertThat(ledger.row(task.taskId()).charged()).isZero();
        assertThat(sent()).isEmpty();
    }

    @Test
    void aModelReadyAfterTheBudgetStartsNoTurn() throws Exception {
        TaskHandle handle = scheduler.submit(narration());
        clock.advance(BUDGET.minus(TaskRequest.MIN_TIMEOUT).plusSeconds(1));
        residency.listener().ready();

        assertThat(result(handle).errorCode()).isEqualTo(InferenceScheduler.QUEUE_TIMEOUT);
        assertThat(sent()).isEmpty();
        assertThat(residency.log).containsExactly("claim 0", "finish 0");
    }

    @Test
    void interactiveWorkPreemptsABackgroundTaskStillWaitingForItsModel() throws Exception {
        ScheduledTask planning = plan();
        TaskHandle planner = scheduler.submit(planning);
        ScheduledTask narrate = narration();
        scheduler.submit(narrate);

        TaskResult preempted = result(planner);
        assertThat(preempted.status()).isEqualTo(TaskResult.Status.CANCELLED);
        assertThat(preempted.errorCode()).isEqualTo(InferenceScheduler.PREEMPTED);
        assertThat(preempted.provenance()).isNull();
        assertThat(ledger.row(planning.taskId()).preempted()).isTrue();
        assertThat(ledger.row(planning.taskId()).charged()).isZero();
        // It never started a turn, so it held no lease worth taking back; the interactive task claims next.
        assertThat(residency.log).containsExactly("claim 0", "yield 0", "claim 1");
        assertThat(sent()).isEmpty();
    }

    @Test
    void shutdownEndsAWaitAndReleasesItsClaim() throws Exception {
        TaskHandle handle = scheduler.submit(narration());
        scheduler.stop();

        TaskResult stopped = result(handle);
        assertThat(stopped.status()).isEqualTo(TaskResult.Status.CANCELLED);
        assertThat(stopped.errorCode()).isEqualTo(InferenceScheduler.SHUTDOWN);
        assertThat(residency.log).containsExactly("claim 0", "finish 0");
        assertThat(sent()).isEmpty();
    }

    @Test
    void aRefusedClaimEndsTheTaskUnavailableWithItsCodeAndFreesThePermit() throws Exception {
        ScheduledTask task = narration();
        TaskHandle handle = scheduler.submit(task);
        residency.listener().refused(TextResidency.CONTENTION);

        TaskResult refused = result(handle);
        assertThat(refused.status()).isEqualTo(TaskResult.Status.UNAVAILABLE);
        assertThat(refused.errorCode()).isEqualTo(TextResidency.CONTENTION);
        assertThat(ledger.row(task.taskId()).charged()).isZero();
        assertThat(sent()).isEmpty();

        scheduler.submit(narration());
        assertThat(residency.log).containsExactly("claim 0", "finish 0", "claim 1");
    }

    @Test
    void aClaimThatThrowsEndsTheTaskUnavailable() throws Exception {
        residency.failure = new IllegalStateException("no worker");
        TaskHandle handle = scheduler.submit(narration());

        assertThat(result(handle).errorCode()).isEqualTo(TextResidency.UNAVAILABLE);
        assertThat(sent()).isEmpty();
        assertThat(scheduler.status().running()).isZero();
    }

    @Test
    void anUnresolvedResultKeepsTheClaimUntilThePortConfirmsNothingRuns() throws Exception {
        ScheduledTask task = narration();
        TaskHandle handle = scheduler.submit(task);
        residency.listener().ready();
        port.status = ControlledPort.ready(1);
        port.complete(task.taskId(), TaskResult.Status.UNRESOLVED, "connection-lost");

        assertThat(result(handle).status()).isEqualTo(TaskResult.Status.UNRESOLVED);
        assertThat(ledger.row(task.taskId()).status()).isEqualTo("RECONCILING");
        assertThat(residency.log).containsExactly("claim 0");

        port.status = ControlledPort.ready(0);
        scheduler.submit(narration());
        // Released before the next claim, so the next task never waits behind its own predecessor's model.
        assertThat(residency.log).containsExactly("claim 0", "finish 0", "claim 1");
        assertThat(ledger.row(task.taskId()).status()).isEqualTo("UNRESOLVED");
    }

    @Test
    void aFailedLedgerWriteStillReleasesTheClaimOnceAndKeepsThePermit() throws Exception {
        TaskLedger failing = mock(TaskLedger.class, delegatesTo(ledger));
        var runtime = new ControlledPort();
        var recorded = new RecordingResidency();
        var built = scheduler(runtime, failing, recorded);
        ScheduledTask task = narration();
        TaskHandle handle = built.submit(task);
        recorded.listener().ready();

        doThrow(new IllegalStateException("database down")).when(failing).settle(any(), any(), anyString(), any(),
                anyBoolean(), any());
        runtime.complete(task.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(handle).status()).isEqualTo(TaskResult.Status.COMPLETED);
        assertThat(recorded.log).containsExactly("claim 0", "finish 0");
        assertThat(built.status().running()).isEqualTo(1);

        doAnswer(delegatesTo(ledger)).when(failing).settle(any(), any(), anyString(), any(), anyBoolean(), any());
        // The retry happens in any later pass and must not release the claim a second time.
        built.submit(narration());
        assertThat(ledger.row(task.taskId()).status()).isEqualTo("COMPLETED");
        assertThat(recorded.log).containsExactly("claim 0", "finish 0", "claim 1");
    }

    @Test
    void aFailedForwardReleasesTheClaim() throws Exception {
        var recorded = new RecordingResidency();
        var built = scheduler(new NarrationTaskPort() {
            @Override
            public TaskHandle submit(TaskRequest request) {
                throw new IllegalStateException("broken port");
            }

            @Override
            public void retireSession(UUID sessionId) {
            }

            @Override
            public RuntimeStatus status() {
                return ControlledPort.ready(0);
            }
        }, new MemoryTaskLedger(), recorded);
        TaskHandle handle = built.submit(narration());
        recorded.listener().ready();

        assertThat(result(handle).errorCode()).isEqualTo(InferenceScheduler.DISPATCH_FAILED);
        assertThat(recorded.log).containsExactly("claim 0", "finish 0");
    }

    @Test
    void aTakenBackAdmissionStopsTheTurnWhichEndsPreemptedAndYieldsItsLease() throws Exception {
        ScheduledTask task = narration();
        TaskHandle handle = scheduler.submit(task);
        var answer = residency.listener();
        answer.ready();

        answer.mustStop();
        answer.mustStop();
        assertThat(port.cancelled).containsExactly(task.taskId());

        TaskResult stopped = result(handle);
        assertThat(stopped.status()).isEqualTo(TaskResult.Status.CANCELLED);
        assertThat(stopped.errorCode()).isEqualTo(InferenceScheduler.PREEMPTED);
        assertThat(residency.log).containsExactly("claim 0", "yield 0");
    }

    @Test
    void aRunningTurnPreemptedForInteractiveWorkYieldsItsLease() throws Exception {
        ScheduledTask planning = plan();
        TaskHandle planner = scheduler.submit(planning);
        residency.listener().ready();
        scheduler.submit(narration());

        assertThat(port.cancelled).containsExactly(planning.taskId());
        assertThat(result(planner).errorCode()).isEqualTo(InferenceScheduler.PREEMPTED);
        assertThat(residency.log).containsExactly("claim 0", "yield 0", "claim 1");
    }

    @Test
    void aTurnThatFinishesBeforeTheInterruptArrivesFinishesItsLease() throws Exception {
        ScheduledTask planning = plan();
        port.autoInterrupt = false;
        TaskHandle planner = scheduler.submit(planning);
        residency.listener().ready();
        scheduler.submit(narration());
        port.complete(planning.taskId(), TaskResult.Status.COMPLETED, null);

        assertThat(result(planner).status()).isEqualTo(TaskResult.Status.COMPLETED);
        assertThat(residency.log).containsExactly("claim 0", "finish 0", "claim 1");
    }
}
