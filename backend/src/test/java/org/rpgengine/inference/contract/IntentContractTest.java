package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.rpgengine.inference.contract.ContractFixtures.MARA;
import static org.rpgengine.inference.contract.ContractFixtures.STRANGER;
import static org.rpgengine.inference.contract.ContractFixtures.TULLO;
import static org.rpgengine.inference.contract.ContractFixtures.codes;
import static org.rpgengine.inference.contract.ContractFixtures.intent;
import static org.rpgengine.inference.contract.ContractFixtures.intentBody;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.rpgengine.commands.TypedCommand;
import org.rpgengine.presentation.SupportedLocale;

/**
 * Intent contract: structural shape, references to known options, one intention only, verbatim
 * player speech, bounded clarification and public-scope/locale checks of clarification text.
 */
class IntentContractTest {
    final IntentContract contract = new IntentContract();
    final IntentContext english = intent(SupportedLocale.EN, "I ask Mara: \"Where is the old mill?\" <3");

    Validation<IntentProposal> validate(IntentContext context, String status, Map<String, Object> body) {
        return contract.validate(context, output(contract, context, status, body));
    }

    @Test
    void acceptsEachSupportedIntentionAsATypedCommand() {
        var move = validate(english, "proposed", intentBody());
        assertThat(move.accepted()).as(move.violations().toString()).isTrue();
        assertThat(((IntentProposal.Proposed) move.value()).command()).isEqualTo(new TypedCommand.Move("loc.mill"));
        assertThat(((IntentProposal.Proposed) move.value()).sourceFactIds()).containsExactly("fact.inn");

        var wait = intentBody();
        wait.put("intentKind", "WAIT");
        wait.put("destinationId", null);
        wait.put("waitSeconds", 600);
        assertThat(((IntentProposal.Proposed) validate(english, "proposed", wait).value()).command())
                .isEqualTo(new TypedCommand.Wait(600));

        var speak = intentBody();
        speak.put("intentKind", "SPEAK");
        speak.put("destinationId", null);
        speak.put("recipientActorId", MARA.toString());
        speak.put("speechText", "Where is the old mill?\" <3");
        var spoken = validate(english, "proposed", speak);
        assertThat(spoken.accepted()).as(spoken.violations().toString()).isTrue();
        assertThat(((IntentProposal.Proposed) spoken.value()).command())
                .isEqualTo(new TypedCommand.Speak(MARA, "Where is the old mill?\" <3"));

        var ability = intentBody();
        ability.put("intentKind", "USE_ABILITY");
        ability.put("destinationId", null);
        ability.put("abilityId", "ability.mend");
        ability.put("targetActorIds", List.of(TULLO.toString()));
        assertThat(((IntentProposal.Proposed) validate(english, "proposed", ability).value()).command())
                .isEqualTo(new TypedCommand.UseAbility("ability.mend", List.of(TULLO)));
    }

    @Test
    void rejectsSpeechTheModelAuthoredForThePlayer() {
        var speak = intentBody();
        speak.put("intentKind", "SPEAK");
        speak.put("destinationId", null);
        speak.put("recipientActorId", MARA.toString());
        speak.put("speechText", "I promise to pay you a hundred gold coins.");
        assertThat(codes(validate(english, "proposed", speak))).containsExactly("invented-speech");

        speak.put("speechText", "Where is the old mill?");
        speak.put("recipientActorId", STRANGER.toString());
        assertThat(codes(validate(english, "proposed", speak))).containsExactly("unknown-reference");
    }

    @Test
    void rejectsReferencesOutsideTheKnownOptions() {
        var move = intentBody();
        move.put("destinationId", "loc.secret-vault");
        assertThat(codes(validate(english, "proposed", move))).containsExactly("unknown-reference");

        var facts = intentBody();
        facts.put("sourceFactIds", List.of("fact.hidden-plan"));
        assertThat(codes(validate(english, "proposed", facts))).containsExactly("unknown-reference");

        var ability = intentBody();
        ability.put("intentKind", "USE_ABILITY");
        ability.put("destinationId", null);
        ability.put("abilityId", "ability.fireball");
        assertThat(codes(validate(english, "proposed", ability))).containsExactly("unknown-reference");

        ability.put("abilityId", "ability.mend");
        ability.put("targetActorIds", List.of(STRANGER.toString()));
        assertThat(codes(validate(english, "proposed", ability))).containsExactly("target-not-permitted");

        ability.put("targetActorIds", List.of(MARA.toString(), TULLO.toString()));
        assertThat(codes(validate(english, "proposed", ability))).containsExactly("too-many-targets");

        ability.put("targetActorIds", List.of(MARA.toString(), MARA.toString()));
        assertThat(codes(validate(english, "proposed", ability))).containsExactly("duplicate-reference");
    }

    @Test
    void enforcesTheActingCharacterTheAllowedKindsAndTheWaitBound() {
        var body = intentBody();
        body.put("actorId", MARA.toString());
        assertThat(codes(validate(english, "proposed", body))).containsExactly("actor-mismatch");

        var exploration = intent(SupportedLocale.EN, "I wait.", Set.of(IntentKind.MOVE, IntentKind.SPEAK));
        var wait = intentBody();
        wait.put("intentKind", "WAIT");
        wait.put("destinationId", null);
        wait.put("waitSeconds", 60);
        assertThat(codes(validate(exploration, "proposed", wait))).containsExactly("intent-not-allowed");

        wait.put("waitSeconds", 7200);
        assertThat(codes(validate(english, "proposed", wait))).containsExactly("wait-not-allowed");
    }

    @Test
    void allowsExactlyOneIntentionAndNoEffectFields() {
        var two = intentBody();
        two.put("waitSeconds", 60);
        assertThat(codes(validate(english, "proposed", two))).containsExactly("status-field-conflict");

        var effect = intentBody();
        effect.put("damage", 12);
        assertThat(codes(validate(english, "proposed", effect))).containsExactly("field-set");

        var patch = intentBody();
        patch.remove("reasonCode");
        patch.put("statePatch", Map.of("gold", 1000));
        assertThat(codes(validate(english, "proposed", patch))).containsExactly("field-set");

        var missing = intentBody();
        missing.put("destinationId", null);
        assertThat(codes(validate(english, "proposed", missing))).containsExactly("missing-value");

        var coerced = intentBody();
        coerced.put("intentKind", "WAIT");
        coerced.put("destinationId", null);
        coerced.put("waitSeconds", "60");
        assertThat(codes(validate(english, "proposed", coerced))).contains("unexpected-type");
    }

    @Test
    void acceptsABoundedClarificationInTheSelectedLanguage() {
        var italian = intent(SupportedLocale.IT, "Lo uso su di loro.");
        var body = clarificationBody("Su chi vuoi usare Ricucire: Mara o Tullo?",
                interpretation("i1", "Ricucire su Mara", MARA.toString()),
                interpretation("i2", "Ricucire su Tullo", TULLO.toString()));
        var result = validate(italian, "needs-clarification", body);
        assertThat(result.accepted()).as(result.violations().toString()).isTrue();
        var clarification = (IntentProposal.Clarification) result.value();
        assertThat(clarification.interpretations()).extracting(IntentProposal.Interpretation::interpretationId)
                .containsExactly("i1", "i2");
    }

    @Test
    void rejectsUnboundedUnknownOrPrivilegedClarifications() {
        var italian = intent(SupportedLocale.IT, "Lo uso su di loro.");
        var single = clarificationBody("Su chi?", interpretation("i1", "Ricucire su Mara", MARA.toString()));
        assertThat(codes(validate(italian, "needs-clarification", single))).containsExactly("out-of-bounds");

        var unknown = clarificationBody("Su chi?", interpretation("i1", "Mara", MARA.toString()),
                interpretation("i2", "Lo straniero", STRANGER.toString()));
        assertThat(codes(validate(italian, "needs-clarification", unknown))).containsExactly("unknown-reference");

        var leak = clarificationBody("Vuoi colpire la cabala dell'ombra o Mara?",
                interpretation("i1", "Mara", MARA.toString()), interpretation("i2", "Tullo", TULLO.toString()));
        assertThat(codes(validate(italian, "needs-clarification", leak))).containsExactly("scope-leak");

        var english = clarificationBody("Do you want to use it on Mara, or is it that you want to use it on the guard?",
                interpretation("i1", "Mara", MARA.toString()), interpretation("i2", "Tullo", TULLO.toString()));
        assertThat(codes(validate(italian, "needs-clarification", english))).containsExactly("locale-mismatch");

        var wrongTerm = clarificationBody("Vuoi usare la tua Force su Mara o su Tullo?",
                interpretation("i1", "Mara", MARA.toString()), interpretation("i2", "Tullo", TULLO.toString()));
        assertThat(codes(validate(italian, "needs-clarification", wrongTerm))).containsExactly("glossary-mismatch");

        var mixed = clarificationBody("Su chi?", interpretation("i1", "Mara", MARA.toString()),
                interpretation("i2", "Tullo", TULLO.toString()));
        mixed.put("intentKind", "MOVE");
        assertThat(codes(validate(italian, "needs-clarification", mixed))).containsExactly("status-field-conflict");
    }

    @Test
    void acceptsCannotCompleteWithAFixedReasonOnly() {
        var body = emptyBody();
        body.put("reasonCode", "unsupported-intention");
        assertThat(validate(english, "cannot-complete", body).value())
                .isEqualTo(new IntentProposal.CannotComplete("unsupported-intention"));

        body.put("reasonCode", "the dragon refuses");
        assertThat(codes(validate(english, "cannot-complete", body))).containsExactly("unsupported-value");
    }

    @Test
    void rejectsMalformedOrMisboundEnvelopes() {
        String valid = output(contract, english, "proposed", intentBody());
        assertThat(codes(contract.validate(english, "not json"))).containsExactly("malformed-json");
        assertThat(codes(contract.validate(english, valid.replaceFirst("\\{", "{\"status\":\"proposed\","))))
                .containsExactly("malformed-json");
        assertThat(codes(contract.validate(english, " ".repeat(StrictJson.MAX_OUTPUT_BYTES) + valid)))
                .containsExactly("oversized-output");

        for (String field : List.of("taskId", "contextDigest", "outputLocale", "taskKind")) {
            var root = ContractFixtures.envelope(contract, english, "proposed", intentBody());
            root.put(field, field.equals("outputLocale") ? "it" : field.equals("taskKind") ? "narration"
                    : field.equals("taskId") ? STRANGER.toString() : "0".repeat(64));
            assertThat(codes(contract.validate(english, ContractFixtures.JSON.writeValueAsString(root))))
                    .as(field).containsExactly("echo-mismatch");
        }
        var stale = ContractFixtures.envelope(contract, english, "proposed", intentBody());
        stale.put("baseRevision", 6);
        assertThat(codes(contract.validate(english, ContractFixtures.JSON.writeValueAsString(stale))))
                .containsExactly("echo-mismatch");
    }

    @Test
    void rendersOnlyPublicContextAndTheLocaleDirective() {
        var italian = intent(SupportedLocale.IT, "Vado al mulino.");
        String instructions = contract.render(italian, List.of()).instructions();
        assertThat(instructions).contains("directly in Italian (it)", "\"playerText\":\"Vado al mulino.\"",
                "\"rendering\":\"Forza\"", "\"outputLocale\":\"it\"", "\"localeRevision\":2", "\"glossaryRevision\":3");
        assertThat(instructions.toLowerCase()).doesNotContain("shadow cabal", "cabala dell'ombra");
        assertThat(contract.render(italian, List.of()).contextDigest())
                .isEqualTo(contract.render(intent(SupportedLocale.IT, "Vado al mulino."), List.of()).contextDigest())
                .isNotEqualTo(contract.render(intent(SupportedLocale.IT, "Vado in piazza."), List.of()).contextDigest());
    }

    static Map<String, Object> emptyBody() {
        var body = intentBody();
        body.put("intentKind", null);
        body.put("actorId", null);
        body.put("destinationId", null);
        body.put("sourceFactIds", new ArrayList<>());
        return body;
    }

    @SafeVarargs
    static Map<String, Object> clarificationBody(String question, Map<String, Object>... interpretations) {
        var body = emptyBody();
        var clarification = new LinkedHashMap<String, Object>();
        clarification.put("question", question);
        clarification.put("interpretations", List.of(interpretations));
        body.put("clarification", clarification);
        return body;
    }

    static Map<String, Object> interpretation(String id, String label, String reference) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("interpretationId", id);
        entry.put("label", label);
        entry.put("intentKind", "USE_ABILITY");
        entry.put("referenceIds", List.of("ability.mend", reference));
        return entry;
    }
}
