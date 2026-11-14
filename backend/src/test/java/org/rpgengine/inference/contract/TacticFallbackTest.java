package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.rpgengine.inference.contract.ContractFixtures.binding;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.rpgengine.combat.CombatScheduler;
import org.rpgengine.combat.EncounterPhase;
import org.rpgengine.combat.TacticCandidates;
import org.rpgengine.combat.TacticStates;
import org.rpgengine.combat.TacticalIntent;
import org.rpgengine.combat.TacticalPlan;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.scheduling.SchedulerFixtures;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.presentation.SupportedLocale;

/**
 * Deterministic tactic fallback (P12.4-A, docs/11 section 12): each way a tactic call can fail becomes a fallback decision with
 * a public notice in the narrative language, a legal runtime choice stays a proposal, a choice the committed state no longer
 * admits is refused, and the fixed tactic then carries the fight on to the player's next decision.
 */
class TacticFallbackTest {
    final TacticContract contract = new TacticContract();
    final ContractRunnerTest.MutableClock clock = new ContractRunnerTest.MutableClock();

    /** Run the tactic task against a runtime script and return the terminal outcome. */
    ContractOutcome<TacticProposal> run(ScriptedTaskPort port, SupportedLocale locale) throws Exception {
        var runner = new ContractRunner(SchedulerFixtures.scheduler(new ScopedSessions(port, 1), port, clock), clock);
        return runner.start(contract, TacticContractTest.context(locale), InferenceSlot.STANDARD,
                TaskKind.TACTIC.defaultBudget()).outcome().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    static TacticCandidates.Menu committed() {
        return TacticCandidates.menu(TacticStates.banditDecides());
    }

    String invalid(SupportedLocale locale, String candidate, String target) {
        var context = TacticContractTest.context(locale);
        return output(contract, context, "proposed", TacticContractTest.body(candidate, target, null, null));
    }

    /**
     * The fixed tactic's guarantee: from the player's end of activation, every non-player participant acts and the plan stops at
     * the player's decision of the next round. Nothing about it depends on the runtime.
     */
    static void assertFightProgresses() {
        TacticalPlan plan = CombatScheduler.apply(TacticStates.deciding(1, 3, List.of()), new TacticalIntent.EndActivation(),
                (ordinal, sides) -> 1);
        assertThat(plan.last().phase()).isEqualTo(EncounterPhase.AWAITING_ACTION);
        assertThat(plan.last().round()).isEqualTo(2);
        assertThat(plan.last().cursor()).isEqualTo(1);
        assertThat(plan.notes()).anySatisfy(note -> {
            assertThat(note.type()).isEqualTo("ACTIVATION_ENDED");
            assertThat(note.payload()).containsEntry("participantId", "bandit");
            assertThat(note.byPlayer()).isFalse();
        });
        assertThat(plan.notes()).extracting(TacticalPlan.Note::type).containsAnyOf("PARTICIPANT_MOVED", "STRIKE_RESOLVED",
                "GUARD_RAISED");
    }

    /** An output that stays invalid through the single repair is a tactic-invalid fallback, and the fight goes on. */
    @ParameterizedTest
    @EnumSource(SupportedLocale.class)
    void invalidOutputAfterTheRepairFallsBack(SupportedLocale locale) throws Exception {
        var port = new ScriptedTaskPort().completes(invalid(locale, "strike:sling", TacticStates.HIDDEN))
                .completes(invalid(locale, "strike:" + TacticStates.PLAYER_ATTACK, "player"));
        var outcome = run(port, locale);
        assertThat(outcome).isInstanceOf(ContractOutcome.Rejected.class);

        var decision = TacticFallback.decide(outcome, committed());
        var fallback = (TacticFallback.Decision.Fallback) decision;
        assertThat(fallback.cause()).isEqualTo(TacticFallback.Cause.TACTIC_INVALID);
        assertThat(fallback.detail()).isEqualTo("unknown-candidate");
        assertThat(fallback.notice().code()).isEqualTo("tactic-fallback.invalid");
        assertThat(fallback.notice().locale()).isEqualTo(locale);
        assertThat(fallback.notice().text()).isEqualTo(TacticFallback.text(TacticFallback.Cause.TACTIC_INVALID, locale));
        assertFightProgresses();
    }

    /** A task the port did not complete is never repaired and falls back as provider-unavailable, or interrupted when cancelled. */
    @Test
    void anUncompletedTaskFallsBackAsUnavailableOrInterrupted() throws Exception {
        for (TaskResult.Status status : List.of(TaskResult.Status.UNAVAILABLE, TaskResult.Status.REJECTED,
                TaskResult.Status.FAILED, TaskResult.Status.TIMED_OUT, TaskResult.Status.UNRESOLVED,
                TaskResult.Status.CANCELLED)) {
            var port = new ScriptedTaskPort().ends(status, "fixture-code");
            var outcome = run(port, SupportedLocale.EN);
            assertThat(port.requests).as(status.name()).hasSize(1);

            var fallback = (TacticFallback.Decision.Fallback) TacticFallback.decide(outcome, committed());
            assertThat(fallback.cause()).as(status.name()).isEqualTo(status == TaskResult.Status.CANCELLED
                    ? TacticFallback.Cause.INTERRUPTED : TacticFallback.Cause.PROVIDER_UNAVAILABLE);
            assertThat(fallback.detail()).isEqualTo("fixture-code");
            assertThat(fallback.notice().code()).isEqualTo(fallback.cause().noticeCode());
            assertFightProgresses();
        }
    }

    /** A runtime that repaired into a legal choice, or chose directly, keeps its proposal while the committed menu admits it. */
    @Test
    void aLegalChoiceStaysAProposal() throws Exception {
        var context = TacticContractTest.context(SupportedLocale.EN);
        var port = new ScriptedTaskPort().completes(output(contract, context, "proposed",
                TacticContractTest.body("strike:sling", "player", null, "pressure-known-target")));
        var decision = TacticFallback.decide(run(port, SupportedLocale.EN), committed());
        assertThat(decision).isEqualTo(new TacticFallback.Decision.Proposed("strike:sling",
                new TacticalIntent.Strike("sling", "player"), "pressure-known-target"));
    }

    /** A choice made over an older state is refused, not forced: the committed menu no longer lists it. */
    @Test
    void aStaleChoiceFallsBack() throws Exception {
        var context = TacticContractTest.context(SupportedLocale.IT);
        var port = new ScriptedTaskPort().completes(output(contract, context, "proposed",
                TacticContractTest.body("strike:sling", "player", null, null)));
        var outcome = run(port, SupportedLocale.IT);
        assertThat(outcome).isInstanceOf(ContractOutcome.Accepted.class);

        var spent = TacticCandidates.menu(TacticStates.deciding(0, 0, List.of()));
        var fallback = (TacticFallback.Decision.Fallback) TacticFallback.decide(outcome, spent);
        assertThat(fallback.cause()).isEqualTo(TacticFallback.Cause.TACTIC_INVALID);
        assertThat(fallback.detail()).isEqualTo("stale-choice");
        assertThat(fallback.notice().text()).startsWith("La scelta tattica");
    }

    /** An explicit inability to choose and a context refused before submission both fall back without a model choice. */
    @Test
    void noChoiceAndARefusedContextFallBack() throws Exception {
        var context = TacticContractTest.context(SupportedLocale.EN);
        var port = new ScriptedTaskPort().completes(output(contract, context, "cannot-complete",
                TacticContractTest.body(null, null, null, "missing-information")));
        var none = (TacticFallback.Decision.Fallback) TacticFallback.decide(run(port, SupportedLocale.EN), committed());
        assertThat(none.cause()).isEqualTo(TacticFallback.Cause.TACTIC_INVALID);
        assertThat(none.detail()).isEqualTo("missing-information");

        var refused = new ContractOutcome.Refused<TacticProposal>("context-scope", binding(SupportedLocale.EN));
        var blocked = (TacticFallback.Decision.Fallback) TacticFallback.decide(refused, committed());
        assertThat(blocked.cause()).isEqualTo(TacticFallback.Cause.TACTIC_INVALID);
        assertThat(blocked.detail()).isEqualTo("context-scope");
    }

    /** The notice is public: it exists in both languages and names no participant, candidate or hidden value. */
    @Test
    void noticesNameNothingOfTheChoice() {
        var secrets = List.of("bandit", "scout", "player", TacticStates.HIDDEN, TacticStates.PLAYER_ATTACK, "strike", "sling",
                "move", "guard");
        for (var cause : TacticFallback.Cause.values()) {
            var english = TacticFallback.text(cause, SupportedLocale.EN);
            var italian = TacticFallback.text(cause, SupportedLocale.IT);
            assertThat(english).isNotEqualTo(italian).endsWith("this turn.");
            for (String secret : secrets) {
                assertThat(english.toLowerCase(java.util.Locale.ROOT)).doesNotContain(secret);
                assertThat(italian.toLowerCase(java.util.Locale.ROOT)).doesNotContain(secret);
            }
        }
    }
}
