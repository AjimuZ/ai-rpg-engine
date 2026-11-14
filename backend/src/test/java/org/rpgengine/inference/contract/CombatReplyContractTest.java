package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.contract.ContractFixtures.JSON;
import static org.rpgengine.inference.contract.ContractFixtures.binding;
import static org.rpgengine.inference.contract.ContractFixtures.codes;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.rpgengine.combat.EncounterState;
import org.rpgengine.combat.TacticStates;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.scheduling.SchedulerFixtures;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.presentation.SupportedLocale;

/**
 * Combat-reply contract (P12.4-B, docs/21 section 15d): the context holds only what the addressed participant knows, with the
 * guard completed from the encounter; a bounded reply in either language is accepted; each ungrounded, unbounded or leaking
 * reply is rejected with one code; and a scripted runtime shows the single repair in the speaker's own session.
 */
class CombatReplyContractTest {
    /** Values the bandit must never learn or repeat: a hidden participant, the player's attack, ammunition and campaign actor. */
    static final List<String> SECRETS = List.of(TacticStates.HIDDEN, TacticStates.PLAYER_ATTACK, TacticStates.PLAYER_AMMUNITION,
            TacticStates.PLAYER_ACTOR.toString(), "sentinel-hook");

    final CombatReplyContract contract = new CombatReplyContract();
    final ContractRunnerTest.MutableClock clock = new ContractRunnerTest.MutableClock();
    final ScriptedTaskPort port = new ScriptedTaskPort();
    final ScopedSessions sessions = new ScopedSessions(port, ScopedSessions.DEFAULT_CAPACITY);
    final ContractRunner runner = new ContractRunner(SchedulerFixtures.scheduler(sessions, port, clock), clock);

    /** The committed fight at the player's decision, after the player spoke to the bandit. */
    static EncounterState committed() {
        return TacticStates.deciding(1, 3, List.of());
    }

    /** The bandit's reply context: the player's words and the bandit's public refusal, plus the given beliefs. */
    static CombatReplyContext context(SupportedLocale locale, List<PublicFact> beliefs) {
        boolean it = locale == SupportedLocale.IT;
        return CombatReplyContext.of(binding(locale), committed(), "bandit", "Brakka",
                new PublicFact("event.41", it ? "Il personaggio dice a Brakka: posa la fionda e vattene."
                        : "The character tells Brakka: lay down the sling and walk away."),
                List.of(new PublicFact("event.42", it ? "Brakka rifiuta la proposta." : "Brakka refuses the proposal.")),
                beliefs, List.of(), List.of(TacticStates.PLAYER_ATTACK, TacticStates.PLAYER_AMMUNITION, "sentinel-hook"));
    }

    static CombatReplyContext context(SupportedLocale locale) {
        return context(locale, List.of());
    }

    static Map<String, Object> body(String speech, String narration, List<String> references, String reason) {
        var body = new LinkedHashMap<String, Object>();
        body.put("speech", speech);
        body.put("narration", narration);
        body.put("references", new ArrayList<>(references));
        body.put("reasonCode", reason);
        return body;
    }

    /** A valid reply in the locale, citing the heard words and the outcome. */
    static Map<String, Object> reply(SupportedLocale locale) {
        boolean it = locale == SupportedLocale.IT;
        return body(it ? "Andarmene? Non finché la mia banda resta in piedi." : "Walk away? Not while my crew still stands.",
                it ? "Brakka sputa a terra e stringe la fionda." : "Brakka spits and tightens the grip on the sling.",
                List.of("event.41", "event.42"), null);
    }

    Validation<CombatReplyProposal> validate(String status, Map<String, Object> body) {
        var context = context(SupportedLocale.EN);
        return contract.validate(context, output(contract, context, status, body));
    }

    /** The rendered request holds the speaker's own standing, what it sees and what it heard, and no secret. */
    @Test
    void rendersOnlyTheSpeakersKnowledge() {
        var context = context(SupportedLocale.EN);
        assertThat(context.guard().markers()).contains(TacticStates.HIDDEN, TacticStates.PLAYER_ACTOR.toString())
                .doesNotContain("scout", "player", "bandit");
        String instructions = contract.render(context, List.of()).instructions();
        for (String secret : SECRETS) {
            assertThat(instructions).doesNotContain(secret);
        }
        assertThat(instructions).doesNotContain("sentinel").doesNotContain(TacticStates.ENCOUNTER.toString())
                .contains("\"role\":\"character-voice\"")
                .contains("\"audience\":\"character:actor:" + TacticStates.BANDIT_ACTOR + "\"");
        var data = JSON.readTree(instructions.substring(instructions.indexOf("CONTEXT (JSON")).lines().skip(1)
                .findFirst().orElseThrow());
        assertThat(new ArrayList<>(data.propertyNames()))
                .containsExactly("speaker", "observed", "heard", "outcome", "beliefs", "glossary");
        assertThat(new ArrayList<>(data.path("speaker").propertyNames()))
                .containsExactly("participantId", "name", "relationToPlayer", "zone", "status");
        assertThat(data.path("speaker").path("relationToPlayer").asString()).isEqualTo("opponent");
        var seen = new ArrayList<String>();
        data.path("observed").forEach(entry -> {
            assertThat(new ArrayList<>(entry.propertyNames()))
                    .containsExactly("participantId", "relation", "zone", "status", "guarding");
            seen.add(entry.path("participantId").asString());
        });
        assertThat(seen).containsExactly("player", "scout");

        assertThat(CombatReplyContext.canReply(committed(), "bandit")).isTrue();
        assertThat(CombatReplyContext.canReply(committed(), "player")).isFalse();
        assertThat(CombatReplyContext.canReply(committed(), "nobody")).isFalse();
        assertThatThrownBy(() -> CombatReplyContext.of(binding(SupportedLocale.EN), committed(), "player", "Hero", null,
                List.of(new PublicFact("event.1", "Something happens.")), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CombatReplyContext.of(binding(SupportedLocale.EN), committed(), "bandit", "Brakka", null,
                List.of(), List.of(), List.of(), List.of())).as("nothing to reply to")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A bounded reply with words and a reaction, or either alone, is accepted in each locale. */
    @ParameterizedTest
    @EnumSource(SupportedLocale.class)
    void acceptsABoundedReply(SupportedLocale locale) {
        var context = context(locale);
        var body = reply(locale);
        var result = contract.validate(context, output(contract, context, "proposed", body));
        assertThat(result.accepted()).as(result.violations().toString()).isTrue();
        assertThat(result.value()).isEqualTo(new CombatReplyProposal.Replied((String) body.get("speech"),
                (String) body.get("narration"), List.of("event.41", "event.42")));

        body.put("narration", null);
        assertThat(contract.validate(context, output(contract, context, "proposed", body)).accepted()).isTrue();
        var none = contract.validate(context, output(contract, context, "cannot-complete",
                body(null, null, List.of(), "out-of-scope")));
        assertThat(none.value()).isEqualTo(new CombatReplyProposal.CannotReply("out-of-scope"));
    }

    /** Every ungrounded, unbounded or leaking reply is rejected with the code of the one rule it breaks. */
    @Test
    void rejectsUngroundedUnboundedAndLeakingReplies() {
        var cited = List.of("event.41", "event.42");
        assertThat(codes(validate("proposed", body("Fine words.", null, List.of("event.41", "event.42", "event.99"), null))))
                .containsExactly("unknown-reference");
        assertThat(codes(validate("proposed", body("Fine words.", null, List.of("event.41"), null))))
                .as("the outcome must be cited").containsExactly("event-not-narrated");
        assertThat(codes(validate("proposed", body("Pay me 5 coins and maybe I go.", null, cited, null))))
                .containsExactly("unsupported-number");
        assertThat(codes(validate("proposed", body("Ask the " + TacticStates.HIDDEN + " in the fog.", null, cited, null))))
                .as("a participant hidden from the speaker").containsExactly("scope-leak");
        assertThat(codes(validate("proposed", body(null, "Brakka eyes the " + TacticStates.PLAYER_ATTACK + ".", cited, null))))
                .as("the player's private attack").containsExactly("scope-leak");
        assertThat(codes(validate("proposed", body("a".repeat(CombatReplyContract.MAX_LINE + 1), null, cited, null))))
                .containsExactly("out-of-bounds");
        assertThat(codes(validate("proposed", body("a".repeat(250), "b".repeat(250), cited, null))))
                .as("both lines together").containsExactly("out-of-bounds");
        assertThat(codes(validate("proposed", body("Fine words.", null, List.of(), null))))
                .containsExactly("out-of-bounds", "event-not-narrated");
        assertThat(codes(validate("proposed", body(null, null, cited, null)))).containsExactly("missing-value");
        assertThat(codes(validate("proposed", body("Fine words.", null, cited, "out-of-scope"))))
                .containsExactly("status-field-conflict");
        assertThat(codes(validate("cannot-complete", body("Fine words.", null, List.of(), "out-of-scope"))))
                .containsExactly("status-field-conflict");
        assertThat(codes(validate("cannot-complete", body(null, null, List.of(), null)))).containsExactly("unexpected-type");
        var accepted = body("Fine words.", null, cited, null);
        accepted.put("acceptsOffer", true);
        assertThat(codes(validate("proposed", accepted))).as("no field can agree to anything").containsExactly("field-set");
    }

    /** A scripted runtime leaking twice gets one repair with fixed codes only, both attempts in the bandit's own session. */
    @Test
    void aScriptedRuntimeIsRepairedOnceThenRejectedInTheSpeakersSession() throws Exception {
        var context = context(SupportedLocale.IT);
        port.completes(output(contract, context, "proposed", body("Chiedi a " + TacticStates.HIDDEN + ".", null,
                        List.of("event.41", "event.42"), null)))
                .completes(output(contract, context, "proposed", body("Bella risposta.", null, List.of("event.41"), null)));
        var outcome = runner.start(contract, context, InferenceSlot.STANDARD, TaskKind.COMBAT_REPLY.defaultBudget()).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(outcome).isInstanceOf(ContractOutcome.Rejected.class);
        assertThat(outcome.attempts()).extracting(ContractOutcome.Attempt::violationCodes)
                .containsExactly(List.of("scope-leak"), List.of("event-not-narrated"));
        assertThat(port.requests).hasSize(2);
        assertThat(port.requests.get(1).instructions()).startsWith(port.requests.get(0).instructions())
                .contains("- scope-leak at /body/speech").doesNotContain("Chiedi a");
        var audience = Audience.character(context.subject());
        UUID speakerSession = sessions.activeSession(TaskKind.COMBAT_REPLY.scope(context.binding(), audience)).orElseThrow();
        assertThat(port.requests).allSatisfy(request -> assertThat(request.sessionId()).isEqualTo(speakerSession));
        // The same character's tactic choices run in another role, so a reply never shares a tactician's conversation.
        assertThat(TaskKind.COMBAT_REPLY.scope(context.binding(), audience))
                .isNotEqualTo(TaskKind.TACTIC.scope(context.binding(), audience));
    }

    /** The kind is interactive, player-facing character work: never public, never privileged. */
    @Test
    void theKindRunsInTheSpeakersOwnCharacterAudience() {
        assertThat(TaskKind.COMBAT_REPLY.wire()).isEqualTo("combat-reply");
        assertThat(TaskKind.COMBAT_REPLY.role()).isEqualTo(AiRole.CHARACTER_VOICE);
        assertThat(AiRole.CHARACTER_VOICE.playerFacing()).isTrue();
        assertThat(AiRole.CHARACTER_VOICE.permits(Audience.PRIVILEGED)).isFalse();
        assertThat(AiRole.CHARACTER_VOICE.permits(Audience.PUBLIC)).isFalse();
        assertThat(TaskKind.COMBAT_REPLY.workClass()).isEqualTo(WorkClass.INTERACTIVE);
        assertThat(TaskKind.COMBAT_REPLY.characterAudience()).isTrue();
        assertThatThrownBy(TaskKind.COMBAT_REPLY::audience).isInstanceOf(IllegalStateException.class);
        assertThat(contract.audience(context(SupportedLocale.EN)))
                .isEqualTo(Audience.character("actor:" + TacticStates.BANDIT_ACTOR));
        assertThat(CombatReplyContext.subject(committed(), "scout"))
                .isEqualTo("combatant:" + TacticStates.ENCOUNTER + ":scout");
        assertThat(contract.templateHash()).matches("[0-9a-f]{64}").isNotEqualTo(new TacticContract().templateHash());
    }
}
