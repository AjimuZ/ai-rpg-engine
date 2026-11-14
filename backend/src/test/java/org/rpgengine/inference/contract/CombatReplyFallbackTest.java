package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.CombatScheduler;
import org.rpgengine.combat.EncounterPhase;
import org.rpgengine.combat.EncounterState;
import org.rpgengine.combat.TacticStates;
import org.rpgengine.combat.TacticalIntent;
import org.rpgengine.combat.TacticalPlan;
import org.rpgengine.combat.TacticalRules;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.scheduling.SchedulerFixtures;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.presentation.SupportedLocale;

/**
 * What a combat-reply call shows (P12.4-B, docs/11 section 7.1): a reply accepted at the committed revision is shown; every
 * failure, a declined reply, a refused context and a late reply show nothing generated; and none of them touches the committed
 * fight, whose tactical controls stay exactly those the rules allow.
 */
class CombatReplyFallbackTest {
    final CombatReplyContract contract = new CombatReplyContract();
    final ContractRunnerTest.MutableClock clock = new ContractRunnerTest.MutableClock();

    /** Run the reply task against a runtime script and return the terminal outcome. */
    ContractOutcome<CombatReplyProposal> run(ScriptedTaskPort port, CombatReplyContext context) throws Exception {
        var runner = new ContractRunner(SchedulerFixtures.scheduler(new ScopedSessions(port, 1), port, clock), clock);
        return runner.start(contract, context, InferenceSlot.STANDARD, TaskKind.COMBAT_REPLY.defaultBudget()).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    static long revision(CombatReplyContext context) {
        return context.binding().baseRevision();
    }

    /** Intentions whose legality is the player's tactical controls at the committed decision. */
    static final List<TacticalIntent> CONTROLS = List.of(new TacticalIntent.EndActivation(), new TacticalIntent.Guard(),
            new TacticalIntent.MoveZone("far"), new TacticalIntent.MoveZone("ring"),
            new TacticalIntent.Speech("bandit", "Last chance."), new TacticalIntent.Strike(TacticStates.PLAYER_ATTACK, "bandit"));

    /** @return each control's refusal, or null when it is available */
    static Map<TacticalIntent, TacticalRules.Refusal> controls(EncounterState state) {
        var refusals = new LinkedHashMap<TacticalIntent, TacticalRules.Refusal>();
        CONTROLS.forEach(intent -> refusals.put(intent, TacticalRules.refusal(state, intent)));
        return refusals;
    }

    /**
     * The guarantee behind every silent decision: the committed fight is unchanged and the player still decides, with the same
     * legal actions as before the reply was asked for.
     */
    static void assertCommittedFightIntact(EncounterState committed, Map<TacticalIntent, TacticalRules.Refusal> before) {
        assertThat(committed).isEqualTo(CombatReplyContractTest.committed());
        assertThat(committed.playerDecision()).isTrue();
        assertThat(controls(committed)).isEqualTo(before);
        assertThat(before.get(new TacticalIntent.EndActivation())).isNull();
        assertThat(before.get(new TacticalIntent.MoveZone("far"))).isNull();
        assertThat(before.get(new TacticalIntent.Speech("bandit", "Last chance."))).isNull();
        // The fixture's player already holds its Guard action, so the rules still refuse a second one.
        assertThat(before.get(new TacticalIntent.Guard())).isEqualTo(TacticalRules.Refusal.GUARD);
    }

    /** Words to a participant change nothing but the speech count and leave the player at the same decision. */
    @Test
    void speechKeepsThePlayerAtTheDecision() {
        TacticalPlan plan = CombatScheduler.apply(CombatReplyContractTest.committed(),
                new TacticalIntent.Speech("bandit", "Lay down the sling and walk away."), (ordinal, sides) -> 1);
        assertThat(plan.last().phase()).isEqualTo(EncounterPhase.AWAITING_ACTION);
        assertThat(plan.last().cursor()).isEqualTo(1);
        assertThat(plan.last().actionPoints()).isEqualTo(3);
        assertThat(plan.notes()).extracting(TacticalPlan.Note::type).containsExactly("COMBAT_SPEECH");
    }

    /** Every uncompleted call is silent: never repaired, unavailable or interrupted, and the fight is untouched. */
    @Test
    void anUncompletedCallIsSilentAndChangesNothing() throws Exception {
        var committed = CombatReplyContractTest.committed();
        var before = controls(committed);
        for (TaskResult.Status status : List.of(TaskResult.Status.UNAVAILABLE, TaskResult.Status.REJECTED,
                TaskResult.Status.FAILED, TaskResult.Status.TIMED_OUT, TaskResult.Status.UNRESOLVED,
                TaskResult.Status.CANCELLED)) {
            var port = new ScriptedTaskPort().ends(status, "fixture-code");
            var context = CombatReplyContractTest.context(SupportedLocale.EN);
            var decision = CombatReplyFallback.decide(run(port, context), revision(context));
            assertThat(port.requests).as(status.name()).hasSize(1);
            assertThat(decision).as(status.name()).isEqualTo(new CombatReplyFallback.Decision.Silent(
                    status == TaskResult.Status.CANCELLED ? CombatReplyFallback.Cause.INTERRUPTED
                            : CombatReplyFallback.Cause.PROVIDER_UNAVAILABLE, "fixture-code"));
            assertCommittedFightIntact(committed, before);
        }
    }

    /** Invalid output after the repair, a declined reply and a context holding a secret are all silent. */
    @Test
    void invalidDeclinedAndRefusedRepliesAreSilent() throws Exception {
        var committed = CombatReplyContractTest.committed();
        var before = controls(committed);
        var context = CombatReplyContractTest.context(SupportedLocale.EN);
        var invalid = output(contract, context, "proposed", CombatReplyContractTest.body("I accept your terms.", null,
                List.of("event.41"), null));
        var rejected = CombatReplyFallback.decide(run(new ScriptedTaskPort().completes(invalid).completes(invalid), context),
                revision(context));
        assertThat(rejected).isEqualTo(new CombatReplyFallback.Decision.Silent(CombatReplyFallback.Cause.REPLY_INVALID,
                "event-not-narrated"));

        var declined = output(contract, context, "cannot-complete", CombatReplyContractTest.body(null, null, List.of(),
                "missing-information"));
        assertThat(CombatReplyFallback.decide(run(new ScriptedTaskPort().completes(declined), context), revision(context)))
                .isEqualTo(new CombatReplyFallback.Decision.Silent(CombatReplyFallback.Cause.REPLY_INVALID,
                        "missing-information"));

        // A belief naming the participant hidden from the speaker: the caller left the speaker's knowledge, nothing is sent.
        var leaking = CombatReplyContractTest.context(SupportedLocale.EN,
                List.of(new PublicFact("belief.fog", "Someone called " + TacticStates.HIDDEN + " waits in the fog.")));
        var port = new ScriptedTaskPort();
        assertThat(CombatReplyFallback.decide(run(port, leaking), revision(leaking)))
                .isEqualTo(new CombatReplyFallback.Decision.Silent(CombatReplyFallback.Cause.REPLY_INVALID, "context-scope"));
        assertThat(port.requests).isEmpty();
        assertCommittedFightIntact(committed, before);
    }

    /** An accepted reply is shown at its revision, and a reply that arrives after a newer commit is not. */
    @Test
    void anAcceptedReplyIsShownOnlyAtItsRevision() throws Exception {
        var context = CombatReplyContractTest.context(SupportedLocale.IT);
        var body = CombatReplyContractTest.reply(SupportedLocale.IT);
        var outcome = run(new ScriptedTaskPort().completes(output(contract, context, "proposed", body)), context);

        var shown = (CombatReplyFallback.Decision.Shown) CombatReplyFallback.decide(outcome, revision(context));
        assertThat(shown.reply().speech()).isEqualTo(body.get("speech"));
        assertThat(shown.reply().references()).containsExactly("event.41", "event.42");
        assertThat(CombatReplyFallback.decide(outcome, revision(context) + 1)).isEqualTo(
                new CombatReplyFallback.Decision.Silent(CombatReplyFallback.Cause.STALE, "stale-revision"));
    }
}
