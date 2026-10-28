package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.contract.ContractFixtures.TASK;
import static org.rpgengine.inference.contract.ContractFixtures.list;
import static org.rpgengine.inference.contract.ContractFixtures.narration;
import static org.rpgengine.inference.contract.ContractFixtures.narrationBody;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.presentation.SupportedLocale;

/**
 * The runner's single bounded repair: when it repairs, with what, within which budget, and when it
 * must not (transport outcomes, cancellation, exhausted budget, refused context).
 */
class ContractRunnerTest {
    final NarrationContract contract = new NarrationContract();
    final NarrationContext context = narration(SupportedLocale.IT);
    final MutableClock clock = new MutableClock();
    final ScriptedTaskPort port = new ScriptedTaskPort();
    final ScopedSessions sessions = new ScopedSessions(port, ScopedSessions.DEFAULT_CAPACITY);
    final ContractRunner runner = new ContractRunner(sessions, clock);

    /** @return the session the registry opened for the narration scope of {@link #context} */
    UUID narrationSession() {
        return sessions.activeSession(TaskKind.NARRATION.scope(context.binding())).orElseThrow();
    }
    final String valid = output(contract, context, "proposed", narrationBody(SupportedLocale.IT));

    /** An output the validator rejects with {@code unsupported-number}; its unique word must never be echoed. */
    String invalid() {
        var body = narrationBody(SupportedLocale.IT);
        list(body, "passages").get(0).put("text", "Zanzibarico ti colpisce e perdi 12 punti.");
        return output(contract, context, "proposed", body);
    }

    ContractOutcome<NarrationProposal> run(Duration budget) throws Exception {
        return runner.start(contract, context, InferenceSlot.STANDARD, budget).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void acceptsAValidFirstAttemptWithoutRepair() throws Exception {
        port.completes(valid);
        var outcome = (ContractOutcome.Accepted<NarrationProposal>) run(Duration.ofSeconds(90));
        assertThat(outcome.repaired()).isFalse();
        assertThat(port.requests).hasSize(1);
        var request = port.requests.getFirst();
        assertThat(request.taskId()).isEqualTo(TASK);
        assertThat(request.sessionId()).isEqualTo(narrationSession());
        assertThat(request.slot()).isEqualTo(InferenceSlot.STANDARD);
        assertThat(request.outputSchema()).isEqualTo(contract.outputSchema());
        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(request.instructions()).doesNotContain("REPAIR");
        assertThat(outcome.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.provenance()).isEqualTo(ScriptedTaskPort.PROVENANCE);
            assertThat(attempt.violationCodes()).isEmpty();
            assertThat(attempt.freshSession()).isTrue();
        });
        assertThat(outcome.binding().presentationCurrent(SupportedLocale.IT, 2, 3)).isTrue();
        assertThat(outcome.binding().presentationCurrent(SupportedLocale.IT, 2, 4)).isFalse();
    }

    @Test
    void repairsOnceWithTheSameContextSessionAndRemainingBudget() throws Exception {
        String bad = invalid();
        port.defers().completes(valid);
        var call = runner.start(contract, context, InferenceSlot.STANDARD, Duration.ofSeconds(90));
        clock.advance(Duration.ofSeconds(30));
        port.pending.getFirst().complete(new TaskResult(TASK, TaskResult.Status.COMPLETED, bad, null,
                ScriptedTaskPort.PROVENANCE, true));
        var outcome = (ContractOutcome.Accepted<NarrationProposal>) call.outcome().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertThat(outcome.repaired()).isTrue();
        assertThat(port.requests).hasSize(2);
        var first = port.requests.get(0);
        var repair = port.requests.get(1);
        assertThat(repair.taskId()).isEqualTo(ContractRunner.repairTaskId(TASK)).isNotEqualTo(TASK);
        assertThat(repair.sessionId()).isEqualTo(first.sessionId()).isEqualTo(narrationSession());
        assertThat(repair.slot()).isEqualTo(first.slot());
        assertThat(repair.timeout()).isEqualTo(Duration.ofSeconds(60));
        // Same canonical context and envelope, plus only fixed codes and pointers.
        assertThat(repair.instructions()).startsWith(first.instructions())
                .contains("REPAIR", "- unsupported-number at /body/passages/0/text")
                .doesNotContain("Zanzibarico");
        assertThat(outcome.attempts()).extracting(ContractOutcome.Attempt::violationCodes)
                .containsExactly(List.of("unsupported-number"), List.of());
        assertThat(outcome.attempts()).extracting(ContractOutcome.Attempt::repair).containsExactly(false, true);
    }

    @Test
    void stopsAfterTheSingleRepairFails() throws Exception {
        port.completes(invalid()).completes(invalid());
        var outcome = (ContractOutcome.Rejected<NarrationProposal>) run(Duration.ofSeconds(90));
        assertThat(port.requests).hasSize(2);
        assertThat(outcome.violations()).extracting(ContractViolation::code).containsExactly("unsupported-number");
        assertThat(outcome.attempts()).hasSize(2);
    }

    @Test
    void neverRepairsTransportOutcomes() throws Exception {
        for (TaskResult.Status status : List.of(TaskResult.Status.UNAVAILABLE, TaskResult.Status.REJECTED,
                TaskResult.Status.FAILED, TaskResult.Status.TIMED_OUT, TaskResult.Status.UNRESOLVED,
                TaskResult.Status.CANCELLED)) {
            var scripted = new ScriptedTaskPort().ends(status, "fixture-code");
            var outcome = new ContractRunner(new ScopedSessions(scripted, 1), clock).start(contract, context,
                    InferenceSlot.STANDARD, Duration.ofSeconds(90)).outcome().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(outcome).as(status.name()).isInstanceOfSatisfying(ContractOutcome.NotCompleted.class, n -> {
                assertThat(n.status()).isEqualTo(status);
                assertThat(n.errorCode()).isEqualTo("fixture-code");
            });
            assertThat(scripted.requests).as(status.name()).hasSize(1);
        }
    }

    @Test
    void reportsARepairThatDidNotComplete() throws Exception {
        port.completes(invalid()).ends(TaskResult.Status.TIMED_OUT, "deadline");
        var outcome = (ContractOutcome.NotCompleted<NarrationProposal>) run(Duration.ofSeconds(90));
        assertThat(outcome.status()).isEqualTo(TaskResult.Status.TIMED_OUT);
        assertThat(outcome.attempts()).hasSize(2);
    }

    @Test
    void rejectsWithoutRepairWhenTheBudgetIsSpent() throws Exception {
        port.defers();
        var call = runner.start(contract, context, InferenceSlot.STANDARD, Duration.ofSeconds(10));
        clock.advance(Duration.ofMillis(9_500));
        port.pending.getFirst().complete(new TaskResult(TASK, TaskResult.Status.COMPLETED, invalid(), null,
                ScriptedTaskPort.PROVENANCE, false));
        var outcome = call.outcome().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(outcome).isInstanceOf(ContractOutcome.Rejected.class);
        assertThat(port.requests).hasSize(1);
    }

    @Test
    void cancellationReachesTheRunningAttemptAndPreventsTheRepair() throws Exception {
        port.defers();
        var call = runner.start(contract, context, InferenceSlot.STANDARD, Duration.ofSeconds(90));
        assertThat(call.cancel()).isTrue();
        assertThat(port.cancelled).containsExactly(TASK);
        // The turn completed before the interrupt landed, with an invalid output: no repair follows.
        port.pending.getFirst().complete(new TaskResult(TASK, TaskResult.Status.COMPLETED, invalid(), null,
                ScriptedTaskPort.PROVENANCE, false));
        var outcome = (ContractOutcome.NotCompleted<NarrationProposal>) call.outcome().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(outcome.status()).isEqualTo(TaskResult.Status.CANCELLED);
        assertThat(port.requests).hasSize(1);
        assertThat(call.cancel()).isFalse();
    }

    @Test
    void refusesAContextOutsideThePublicScopeWithoutSubmitting() throws Exception {
        var leaking = narration(SupportedLocale.IT, new DisclosureGuard(List.of("vecchio mulino")));
        var outcome = runner.start(contract, leaking, InferenceSlot.STANDARD, Duration.ofSeconds(90))
                .outcome().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(outcome).isEqualTo(new ContractOutcome.Refused<>("context-scope", leaking.binding()));
        assertThat(port.requests).isEmpty();
        // A refused context does not even open a session for its scope.
        assertThat(sessions.status().active()).isZero();
    }

    @Test
    void validatesTheBudget() {
        assertThatThrownBy(() -> runner.start(contract, context, InferenceSlot.STANDARD, Duration.ofSeconds(181)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TaskKind.NARRATION.defaultBudget()).isEqualTo(Duration.ofSeconds(90));
        assertThat(TaskKind.INTENT.defaultBudget()).isEqualTo(Duration.ofSeconds(60));
    }

    /** Clock advanced explicitly by the test. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-28T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
