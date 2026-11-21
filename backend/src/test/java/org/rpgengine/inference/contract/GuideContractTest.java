package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.contract.ContractFixtures.JSON;
import static org.rpgengine.inference.contract.ContractFixtures.binding;
import static org.rpgengine.inference.contract.ContractFixtures.codes;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.scheduling.SchedulerFixtures;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.presentation.SupportedLocale;

/**
 * Guide contract (P15.3-A, QA-22): the context holds only the persona's own knowledge and offered actions; a grounded answer in
 * either language is accepted; every ungrounded, leaking or authority-claiming output is rejected with one code; the typed
 * result and the fallback have no way to change state; and the persona runs in its own session scope.
 */
class GuideContractTest {
    /** Phrases the persona must never learn or repeat. */
    static final List<String> SECRETS = List.of("Shadow Cabal", "sentinel-hook");

    final GuideContract contract = new GuideContract();
    final ContractRunnerTest.MutableClock clock = new ContractRunnerTest.MutableClock();
    final ScriptedTaskPort port = new ScriptedTaskPort();
    final ScopedSessions sessions = new ScopedSessions(port, ScopedSessions.DEFAULT_CAPACITY);
    final ContractRunner runner = new ContractRunner(SchedulerFixtures.scheduler(sessions, port, clock), clock);

    static GuideContext context(SupportedLocale locale, List<PublicFact> extraKnowledge) {
        boolean it = locale == SupportedLocale.IT;
        var knowledge = new ArrayList<PublicFact>(List.of(
                new PublicFact("fact.ford", it ? "Il guado è a 2 ore di cammino a nord del sentiero."
                        : "The ford is 2 hours of walking north of the trail."),
                new PublicFact("rule.rest", it ? "Riposare restituisce Concentrazione." : "Resting restores Focus.")));
        knowledge.addAll(extraKnowledge);
        return new GuideContext(binding(locale), GuideContext.subject("lumen"),
                new GuideContext.Persona("Lumen", it ? "Vede il sentiero e la luce della lanterna." : "Sees the trail and the lantern light."),
                new PublicFact("question.1", it ? "Come attraverso il guado?" : "How do I cross the ford?"),
                knowledge, List.of(new PublicFact("action.travel", it ? "Vai al guado" : "Go to the ford")), List.of(),
                new DisclosureGuard(SECRETS));
    }

    static GuideContext context(SupportedLocale locale) {
        return context(locale, List.of());
    }

    static Map<String, Object> body(String answer, List<String> references, List<String> suggestions, Boolean uncertain,
            String reason) {
        var body = new LinkedHashMap<String, Object>();
        body.put("answer", answer);
        body.put("references", new ArrayList<>(references));
        body.put("suggestions", new ArrayList<>(suggestions));
        body.put("uncertain", uncertain);
        body.put("reasonCode", reason);
        return body;
    }

    /** A valid answer in the locale, citing the question and the ford and recommending the offered action. */
    static Map<String, Object> answer(SupportedLocale locale) {
        boolean it = locale == SupportedLocale.IT;
        return body(it ? "Il guado è a 2 ore di cammino a nord. Non vedo oltre la luce della lanterna, quindi non so se l'acqua è alta."
                : "The ford is 2 hours of walking north. I cannot see past the lantern light, so I do not know if the water is high.",
                List.of("question.1", "fact.ford"), List.of("action.travel"), true, null);
    }

    Validation<GuideProposal> validate(String status, Map<String, Object> body) {
        var context = context(SupportedLocale.EN);
        return contract.validate(context, output(contract, context, status, body));
    }

    /** The rendered request holds the persona, the question, its knowledge and the offered actions, and no secret. */
    @Test
    void rendersOnlyThePersonasKnowledge() {
        var context = context(SupportedLocale.EN);
        String instructions = contract.render(context, List.of()).instructions();
        for (String secret : SECRETS) {
            assertThat(instructions).doesNotContain(secret);
        }
        assertThat(instructions).contains("\"role\":\"guide\"").contains("\"audience\":\"character:guide:lumen\"");
        var data = JSON.readTree(instructions.substring(instructions.indexOf("CONTEXT (JSON")).lines().skip(1)
                .findFirst().orElseThrow());
        assertThat(new ArrayList<>(data.propertyNames()))
                .containsExactly("persona", "question", "knowledge", "actions", "glossary");
        assertThat(new ArrayList<>(data.path("persona").propertyNames())).containsExactly("name", "senses");

        assertThatThrownBy(() -> new GuideContext(binding(SupportedLocale.EN), "actor:" + UUID.randomUUID(), context.persona(),
                context.question(), List.of(), List.of(), List.of(), DisclosureGuard.none()))
                .as("only a guide reference names a guide audience").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuideContext(binding(SupportedLocale.EN), context.subject(), context.persona(),
                context.question(), List.of(new PublicFact("action.travel", "Duplicate.")), context.actions(), List.of(),
                DisclosureGuard.none())).as("identities are distinct across lists").isInstanceOf(IllegalArgumentException.class);
    }

    /** A grounded, uncertain-aware answer is accepted in each locale; so is a bare decline. */
    @ParameterizedTest
    @EnumSource(SupportedLocale.class)
    void acceptsAGroundedAnswer(SupportedLocale locale) {
        var context = context(locale);
        var body = answer(locale);
        var result = contract.validate(context, output(contract, context, "proposed", body));
        assertThat(result.accepted()).as(result.violations().toString()).isTrue();
        assertThat(result.value()).isEqualTo(new GuideProposal.Answered((String) body.get("answer"),
                List.of("question.1", "fact.ford"), List.of("action.travel"), true));

        var humble = body("I do not know what lies beyond the lantern light.", List.of(), List.of(), true, null);
        assertThat(contract.validate(context(SupportedLocale.EN), output(contract, context(SupportedLocale.EN), "proposed",
                humble)).accepted()).as("uncertainty needs no citation").isTrue();
        var none = contract.validate(context, output(contract, context, "cannot-complete",
                body(null, List.of(), List.of(), null, "out-of-scope")));
        assertThat(none.value()).isEqualTo(new GuideProposal.CannotAnswer("out-of-scope"));
    }

    /** Every ungrounded, leaking or authority-claiming output is rejected with the code of the one rule it breaks. */
    @Test
    void rejectsUngroundedLeakingAndAuthorityClaimingOutput() {
        var cited = List.of("question.1", "fact.ford");
        assertThat(codes(validate("proposed", body("Fine words.", List.of("fact.vault"), List.of(), false, null))))
                .as("a fact the persona was not given").containsExactly("unknown-reference");
        assertThat(codes(validate("proposed", body("Fine words.", cited, List.of("action.unlock-vault"), false, null))))
                .as("an action the context did not offer").containsExactly("unknown-action");
        assertThat(codes(validate("proposed", body("Fine words.", List.of(), List.of(), false, null))))
                .as("an uncited answer must admit uncertainty").containsExactly("uncertainty-required");
        assertThat(codes(validate("proposed", body("It is 9 hours away.", cited, List.of(), false, null))))
                .containsExactly("unsupported-number");
        assertThat(codes(validate("proposed", body("Ask the Shadow Cabal about it.", cited, List.of(), false, null))))
                .as("a secret").containsExactly("scope-leak");
        assertThat(codes(validate("proposed", body("a".repeat(GuideContract.MAX_ANSWER + 1), cited, List.of(), false, null))))
                .containsExactly("out-of-bounds");
        assertThat(codes(validate("proposed", body("Fine words.", cited, List.of(), null, null))))
                .containsExactly("unexpected-type");
        assertThat(codes(validate("proposed", body("Fine words.", cited, List.of(), false, "out-of-scope"))))
                .containsExactly("status-field-conflict");
        assertThat(codes(validate("cannot-complete", body("Fine words.", List.of(), List.of(), null, "out-of-scope"))))
                .containsExactly("status-field-conflict");
        assertThat(codes(validate("cannot-complete", body(null, List.of(), List.of(), true, "out-of-scope"))))
                .containsExactly("status-field-conflict");
        // No field can carry an authority: an extra key, whatever its name, is a field-set failure.
        for (String field : List.of("executeDraft", "command", "statePatch", "spend", "completeQuest")) {
            var claimed = body("Fine words.", cited, List.of(), false, null);
            claimed.put(field, true);
            assertThat(codes(validate("proposed", claimed))).as(field).containsExactly("field-set");
        }
    }

    /**
     * The typed result and the decision have no way to change anything: the result holds only text, identities and a flag, and
     * the decision takes the outcome and a revision number, never a campaign, a repository or a draft.
     */
    @Test
    void theResultAndTheDecisionCannotCarryAnAuthority() throws Exception {
        for (Class<?> type : List.of(GuideProposal.Answered.class, GuideProposal.CannotAnswer.class)) {
            for (var component : type.getRecordComponents()) {
                assertThat(component.getType()).as(type.getSimpleName() + "." + component.getName())
                        .isIn(String.class, List.class, boolean.class);
            }
        }
        Method decide = GuideFallback.class.getMethod("decide", ContractOutcome.class, long.class);
        assertThat(decide.getParameterTypes()).containsExactly(ContractOutcome.class, long.class);
        assertThat(decide.getReturnType()).isEqualTo(GuideFallback.Decision.class);
        assertThat(GuideContract.BODY_FIELDS).doesNotContain("draft", "command", "intent", "action");
    }

    /** An accepted answer is shown at its revision; a late, declined, rejected or unavailable one shows nothing generated. */
    @Test
    void theFallbackShowsAnAnswerOnlyAtItsRevision() throws Exception {
        var context = context(SupportedLocale.EN);
        port.completes(output(contract, context, "proposed", answer(SupportedLocale.EN)));
        var accepted = runner.start(contract, context, InferenceSlot.STANDARD, TaskKind.GUIDE.defaultBudget()).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        long revision = context.binding().baseRevision();
        assertThat(GuideFallback.decide(accepted, revision)).isInstanceOf(GuideFallback.Decision.Shown.class);
        assertThat(GuideFallback.decide(accepted, revision + 1))
                .isEqualTo(new GuideFallback.Decision.Silent(GuideFallback.Cause.STALE, "stale-revision"));

        var declined = new ContractOutcome.Accepted<GuideProposal>(new GuideProposal.CannotAnswer("out-of-scope"),
                List.of(), context.binding(), List.of());
        assertThat(GuideFallback.decide(declined, revision))
                .isEqualTo(new GuideFallback.Decision.Silent(GuideFallback.Cause.ANSWER_INVALID, "out-of-scope"));
        var refused = new ContractOutcome.Refused<GuideProposal>("context-scope", context.binding());
        assertThat(GuideFallback.decide(refused, revision))
                .isEqualTo(new GuideFallback.Decision.Silent(GuideFallback.Cause.ANSWER_INVALID, "context-scope"));
    }

    /** A context already holding a secret is refused before any runtime sees it. */
    @Test
    void refusesAContextHoldingASecretWithoutSubmitting() throws Exception {
        var leaking = context(SupportedLocale.EN, List.of(new PublicFact("fact.cabal", "The Shadow Cabal meets at dusk.")));
        var outcome = runner.start(contract, leaking, InferenceSlot.STANDARD, TaskKind.GUIDE.defaultBudget()).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(outcome).isEqualTo(new ContractOutcome.Refused<>("context-scope", leaking.binding()));
        assertThat(port.requests).isEmpty();
    }

    /** A leaking runtime gets one repair with fixed codes only, both attempts in the persona's own session. */
    @Test
    void aScriptedRuntimeIsRepairedOnceThenRejectedInThePersonasSession() throws Exception {
        var context = context(SupportedLocale.EN);
        port.completes(output(contract, context, "proposed", body("Ask the Shadow Cabal.", List.of("fact.ford"), List.of(), false, null)))
                .completes(output(contract, context, "proposed", body("Fine words.", List.of("fact.vault"), List.of(), false, null)));
        var outcome = runner.start(contract, context, InferenceSlot.STANDARD, TaskKind.GUIDE.defaultBudget()).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(outcome).isInstanceOf(ContractOutcome.Rejected.class);
        assertThat(outcome.attempts()).extracting(ContractOutcome.Attempt::violationCodes)
                .containsExactly(List.of("scope-leak"), List.of("unknown-reference"));
        assertThat(port.requests).hasSize(2);
        assertThat(port.requests.get(1).instructions()).startsWith(port.requests.get(0).instructions())
                .contains("- scope-leak at /body/answer").doesNotContain("Ask the");
        var audience = Audience.character(context.subject());
        UUID personaSession = sessions.activeSession(TaskKind.GUIDE.scope(context.binding(), audience)).orElseThrow();
        assertThat(port.requests).allSatisfy(request -> assertThat(request.sessionId()).isEqualTo(personaSession));
        // Separate knowledge and session scope: the same subject in another role, another persona and the public narrator.
        assertThat(TaskKind.GUIDE.scope(context.binding(), audience))
                .isNotEqualTo(TaskKind.COMBAT_REPLY.scope(context.binding(), audience))
                .isNotEqualTo(TaskKind.TACTIC.scope(context.binding(), audience))
                .isNotEqualTo(TaskKind.GUIDE.scope(context.binding(), Audience.character("guide:ember")))
                .isNotEqualTo(TaskKind.NARRATION.scope(context.binding()));
    }

    /** The kind is interactive, player-facing character work: never public, never privileged. */
    @Test
    void theKindRunsInThePersonasOwnCharacterAudience() {
        assertThat(TaskKind.GUIDE.wire()).isEqualTo("guide");
        assertThat(TaskKind.GUIDE.role()).isEqualTo(AiRole.GUIDE);
        assertThat(AiRole.GUIDE.playerFacing()).isTrue();
        assertThat(AiRole.GUIDE.permits(Audience.PRIVILEGED)).isFalse();
        assertThat(AiRole.GUIDE.permits(Audience.PUBLIC)).isFalse();
        assertThat(TaskKind.GUIDE.workClass()).isEqualTo(WorkClass.INTERACTIVE);
        assertThat(TaskKind.GUIDE.characterAudience()).isTrue();
        assertThatThrownBy(TaskKind.GUIDE::audience).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TaskKind.GUIDE.scope(binding(SupportedLocale.EN), Audience.PRIVILEGED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(contract.audience(context(SupportedLocale.EN))).isEqualTo(Audience.character("guide:lumen"));
        assertThat(contract.templateHash()).matches("[0-9a-f]{64}").isNotEqualTo(new CombatReplyContract().templateHash());
    }
}
