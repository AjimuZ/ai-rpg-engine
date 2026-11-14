package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.contract.ContractFixtures.JSON;
import static org.rpgengine.inference.contract.ContractFixtures.TASK;
import static org.rpgengine.inference.contract.ContractFixtures.binding;
import static org.rpgengine.inference.contract.ContractFixtures.codes;
import static org.rpgengine.inference.contract.ContractFixtures.envelope;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.rpgengine.combat.TacticCandidates;
import org.rpgengine.combat.TacticStates;
import org.rpgengine.combat.TacticalIntent;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.scheduling.SchedulerFixtures;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.presentation.SupportedLocale;

/**
 * Tactic-choice contract (P12.3, docs/21 section 7): the context holds only the acting participant's knowledge-scoped menu,
 * with every value seeded as secret absent; each candidate kind is accepted with its permitted parameter; each illegal
 * proposal is rejected with one code; and a scripted runtime shows the single repair, the final rejection the caller falls
 * back from, and the acting character's own session.
 */
class TacticContractTest {
    /** Values the bandit must never learn: a hidden participant, the player's attack, ammunition and campaign actor. */
    static final List<String> SECRETS = List.of(TacticStates.HIDDEN, TacticStates.PLAYER_ATTACK, TacticStates.PLAYER_AMMUNITION,
            TacticStates.PLAYER_ACTOR.toString(), "sentinel-hook");

    final TacticContract contract = new TacticContract();
    final ContractRunnerTest.MutableClock clock = new ContractRunnerTest.MutableClock();
    final ScriptedTaskPort port = new ScriptedTaskPort();
    final ScopedSessions sessions = new ScopedSessions(port, ScopedSessions.DEFAULT_CAPACITY);
    final ContractRunner runner = new ContractRunner(SchedulerFixtures.scheduler(sessions, port, clock), clock);

    static TacticContext context(SupportedLocale locale) {
        return TacticContext.of(binding(locale), TacticCandidates.menu(TacticStates.banditDecides()),
                new DisclosureGuard(SECRETS));
    }

    static Map<String, Object> body(String candidate, String target, String destination, String reason) {
        var body = new LinkedHashMap<String, Object>();
        body.put("candidateActionId", candidate);
        body.put("targetId", target);
        body.put("destinationId", destination);
        body.put("reasonCode", reason);
        return body;
    }

    Validation<TacticProposal> validate(String status, Map<String, Object> body) {
        var context = context(SupportedLocale.EN);
        return contract.validate(context, output(contract, context, status, body));
    }

    /** The rendered request holds the actor's own values and the public standing of what it sees, and no secret. */
    @Test
    void rendersOnlyTheActorsKnowledge() {
        var context = context(SupportedLocale.EN);
        // The guard holds every secret, so rendering would refuse if any of them had reached the context.
        String instructions = contract.render(context, List.of()).instructions();
        for (String secret : SECRETS) {
            assertThat(instructions).doesNotContain(secret);
        }
        assertThat(instructions).doesNotContain("sentinel").doesNotContain(TacticStates.ENCOUNTER.toString())
                .contains("\"role\":\"tactician\"")
                .contains("\"audience\":\"character:actor:" + TacticStates.BANDIT_ACTOR + "\"");
        var data = JSON.readTree(instructions.substring(instructions.indexOf("CONTEXT (JSON")).lines().skip(1)
                .findFirst().orElseThrow());
        assertThat(new ArrayList<>(data.propertyNames()))
                .containsExactly("round", "actor", "observed", "candidates", "beliefs", "reasonCodes");
        assertThat(new ArrayList<>(data.path("actor").propertyNames())).containsExactly("participantId", "zone",
                "actionPoints", "health", "healthMax", "focus", "focusMax", "guarding");
        var seen = new ArrayList<String>();
        data.path("observed").forEach(entry -> {
            assertThat(new ArrayList<>(entry.propertyNames()))
                    .containsExactly("participantId", "relation", "zone", "status", "guarding");
            seen.add(entry.path("participantId").asString());
        });
        assertThat(seen).containsExactly("player", "scout");
        var candidates = new ArrayList<String>();
        data.path("candidates").forEach(entry -> candidates.add(entry.path("candidateActionId").asString()));
        assertThat(candidates).containsExactly("move", "strike:cudgel", "strike:sling", "guard", "end-activation");
    }

    /** Each candidate kind is accepted with exactly its parameter, in each locale, and becomes a tactical intention. */
    @ParameterizedTest
    @EnumSource(SupportedLocale.class)
    void acceptsEachCandidateKind(SupportedLocale locale) {
        var context = context(locale);
        var cases = Map.of(
                body("strike:sling", "player", null, "pressure-known-target"), new TacticalIntent.Strike("sling", "player"),
                body("move", null, "mid", null), new TacticalIntent.MoveZone("mid"),
                body("guard", null, null, "conserve-resource"), new TacticalIntent.Guard(),
                body("end-activation", null, null, null), new TacticalIntent.EndActivation());
        cases.forEach((body, intent) -> {
            var result = contract.validate(context, output(contract, context, "proposed", body));
            assertThat(result.accepted()).as(result.violations().toString()).isTrue();
            var chosen = (TacticProposal.Chosen) result.value();
            assertThat(chosen.intent()).isEqualTo(intent);
            assertThat(chosen.candidateId()).isEqualTo(body.get("candidateActionId"));
            assertThat(chosen.reasonCode()).isEqualTo(body.get("reasonCode"));
            assertThat(context.menu().admits(chosen.intent())).isTrue();
        });
        var none = contract.validate(context, output(contract, context, "cannot-complete",
                body(null, null, null, "missing-information")));
        assertThat(none.value()).isEqualTo(new TacticProposal.CannotChoose("missing-information"));
    }

    /** Every illegal proposal is rejected with the code of the one rule it breaks. */
    @Test
    void rejectsIllegalProposals() {
        assertThat(codes(validate("proposed", body("strike:" + TacticStates.PLAYER_ATTACK, "player", null, null))))
                .containsExactly("unknown-candidate");
        assertThat(codes(validate("proposed", body("ability:any", null, null, null)))).containsExactly("unknown-candidate");
        assertThat(codes(validate("proposed", body("strike:sling", TacticStates.HIDDEN, null, null))))
                .as("a participant hidden from the actor").containsExactly("target-not-permitted");
        assertThat(codes(validate("proposed", body("strike:cudgel", "player", null, null))))
                .as("out of melee reach").containsExactly("target-not-permitted");
        assertThat(codes(validate("proposed", body("move", null, "far", null)))).containsExactly("destination-not-permitted");
        assertThat(codes(validate("proposed", body("move", null, "ring", null)))).containsExactly("destination-not-permitted");
        assertThat(codes(validate("proposed", body("strike:sling", null, null, null)))).containsExactly("missing-value");
        assertThat(codes(validate("proposed", body("move", null, null, null)))).containsExactly("missing-value");
        assertThat(codes(validate("proposed", body("guard", "player", null, null)))).containsExactly("status-field-conflict");
        assertThat(codes(validate("proposed", body("strike:sling", "player", "mid", null))))
                .as("a move cannot ride along with a strike").containsExactly("status-field-conflict");
        assertThat(codes(validate("proposed", body("end-activation", null, null, "missing-information"))))
                .containsExactly("unsupported-value");
        assertThat(codes(validate("cannot-complete", body("guard", null, null, "missing-information"))))
                .containsExactly("status-field-conflict");
        assertThat(codes(validate("cannot-complete", body(null, null, null, null)))).containsExactly("unexpected-type");

        var damage = body("strike:sling", "player", null, null);
        damage.put("damage", 12);
        assertThat(codes(validate("proposed", damage))).containsExactly("field-set");
        var context = context(SupportedLocale.EN);
        var stale = envelope(contract, context, "proposed", body("guard", null, null, null));
        stale.put("baseRevision", context.binding().baseRevision() + 1);
        assertThat(codes(contract.validate(context, JSON.writeValueAsString(stale)))).containsExactly("echo-mismatch");
    }

    /** A scripted runtime proposing illegal choices gets one repair with fixed codes only, then the caller falls back. */
    @Test
    void aScriptedRuntimeIsRepairedOnceThenRejected() throws Exception {
        var context = context(SupportedLocale.IT);
        port.completes(output(contract, context, "proposed", body("strike:sling", TacticStates.HIDDEN, null, null)))
                .completes(output(contract, context, "proposed", body("strike:" + TacticStates.PLAYER_ATTACK, "player", null,
                        null)));
        var outcome = runner.start(contract, context, InferenceSlot.STANDARD, TaskKind.TACTIC.defaultBudget()).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(outcome).isInstanceOf(ContractOutcome.Rejected.class);
        assertThat(((ContractOutcome.Rejected<TacticProposal>) outcome).violations()).extracting(ContractViolation::code)
                .containsExactly("unknown-candidate");
        assertThat(outcome.attempts()).extracting(ContractOutcome.Attempt::violationCodes)
                .containsExactly(List.of("target-not-permitted"), List.of("unknown-candidate"));
        assertThat(port.requests).hasSize(2);
        var first = port.requests.get(0);
        var repair = port.requests.get(1);
        assertThat(repair.taskId()).isEqualTo(ContractRunner.repairTaskId(TASK));
        assertThat(repair.instructions()).startsWith(first.instructions())
                .contains("- target-not-permitted at /body/targetId");
        for (var request : port.requests) {
            assertThat(request.outputSchema()).isEqualTo(contract.outputSchema());
            for (String secret : SECRETS) {
                assertThat(request.instructions()).doesNotContain(secret);
            }
        }
        UUID banditSession = sessions.activeSession(TaskKind.TACTIC.scope(context.binding(),
                Audience.character(context.subject()))).orElseThrow();
        assertThat(first.sessionId()).isEqualTo(banditSession);
        assertThat(repair.sessionId()).isEqualTo(banditSession);
    }

    /** A legal repair is accepted, and the choice is admitted again by a menu read from the same committed state. */
    @Test
    void aScriptedRuntimeRepairedIntoALegalChoiceIsAccepted() throws Exception {
        var context = context(SupportedLocale.EN);
        port.completes(output(contract, context, "proposed", body("move", null, "far", null)))
                .completes(output(contract, context, "proposed", body("move", null, "mid", "retreat")));
        var outcome = (ContractOutcome.Accepted<TacticProposal>) runner.start(contract, context, InferenceSlot.STANDARD,
                TaskKind.TACTIC.defaultBudget()).outcome().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(outcome.repaired()).isTrue();
        var chosen = (TacticProposal.Chosen) outcome.value();
        assertThat(chosen.intent()).isEqualTo(new TacticalIntent.MoveZone("mid"));
        assertThat(TacticCandidates.menu(TacticStates.banditDecides()).admits(chosen.intent())).isTrue();
    }

    /** A belief carrying a secret means the caller assembled the context outside the actor's knowledge: nothing is sent. */
    @Test
    void refusesAContextHoldingASecretWithoutSubmitting() throws Exception {
        var menu = TacticCandidates.menu(TacticStates.banditDecides());
        var leaking = new TacticContext(binding(SupportedLocale.EN), menu, TacticContext.subject(menu),
                List.of(new PublicFact("belief.watch", "Someone called " + TacticStates.HIDDEN + " waits in the fog.")),
                new DisclosureGuard(SECRETS));
        var outcome = runner.start(contract, leaking, InferenceSlot.STANDARD, TaskKind.TACTIC.defaultBudget()).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(outcome).isEqualTo(new ContractOutcome.Refused<>("context-scope", leaking.binding()));
        assertThat(port.requests).isEmpty();
    }

    /** The kind is interactive tactician work in one character's audience: two participants never share a session. */
    @Test
    void eachActorRunsInItsOwnCharacterAudience() {
        assertThat(TaskKind.TACTIC.wire()).isEqualTo("tactic");
        assertThat(TaskKind.TACTIC.role()).isEqualTo(AiRole.TACTICIAN);
        assertThat(TaskKind.TACTIC.workClass()).isEqualTo(WorkClass.INTERACTIVE);
        assertThat(TaskKind.TACTIC.characterAudience()).isTrue();
        assertThat(TaskKind.NARRATION.characterAudience()).isFalse();
        assertThatThrownBy(TaskKind.TACTIC::audience).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskKind.TACTIC.scope(binding(SupportedLocale.EN), Audience.PUBLIC))
                .isInstanceOf(IllegalArgumentException.class);

        var bandit = context(SupportedLocale.EN);
        var scoutMenu = TacticCandidates.menu(TacticStates.scoutDecides());
        var scout = TacticContext.of(binding(SupportedLocale.EN), scoutMenu, DisclosureGuard.none());
        assertThat(contract.audience(bandit)).isEqualTo(Audience.character("actor:" + TacticStates.BANDIT_ACTOR));
        assertThat(contract.audience(scout))
                .isEqualTo(Audience.character("combatant:" + TacticStates.ENCOUNTER + ":scout"));
        assertThat(TaskKind.TACTIC.scope(bandit.binding(), contract.audience(bandit)))
                .isNotEqualTo(TaskKind.TACTIC.scope(scout.binding(), contract.audience(scout)));
        assertThat(contract.templateHash()).matches("[0-9a-f]{64}");
    }
}
