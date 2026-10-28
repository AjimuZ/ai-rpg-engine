package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.contract.ContractFixtures.MARA;
import static org.rpgengine.inference.contract.ContractFixtures.PLAYER;
import static org.rpgengine.inference.contract.ContractFixtures.QUOTE;
import static org.rpgengine.inference.contract.ContractFixtures.STRANGER;
import static org.rpgengine.inference.contract.ContractFixtures.codes;
import static org.rpgengine.inference.contract.ContractFixtures.list;
import static org.rpgengine.inference.contract.ContractFixtures.narration;
import static org.rpgengine.inference.contract.ContractFixtures.narrationBody;
import static org.rpgengine.inference.contract.ContractFixtures.output;
import static org.rpgengine.inference.contract.ContractFixtures.passage;
import static org.rpgengine.inference.contract.ContractFixtures.suggestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.rpgengine.presentation.SupportedLocale;

/**
 * Narration contract: attribution, grounding in the public bundle, agency at the pending decision,
 * quick-reply bounds, public scope, glossary, protected quotes and direct EN/IT generation.
 */
class NarrationContractTest {
    final NarrationContract contract = new NarrationContract();
    final NarrationContext italian = narration(SupportedLocale.IT);

    Validation<NarrationProposal> validate(NarrationContext context, Map<String, Object> body) {
        return contract.validate(context, output(contract, context, "proposed", body));
    }

    /** Replace one passage's text in a valid Italian body. */
    Map<String, Object> withText(int passage, String text) {
        var body = narrationBody(SupportedLocale.IT);
        list(body, "passages").get(passage).put("text", text);
        return body;
    }

    @ParameterizedTest
    @EnumSource(SupportedLocale.class)
    void acceptsAttributedNarrationWithQuickRepliesInEachLocale(SupportedLocale locale) {
        var result = validate(narration(locale), narrationBody(locale));
        assertThat(result.accepted()).as(result.violations().toString()).isTrue();
        assertThat(result.warnings()).isEmpty();
        var narrated = (NarrationProposal.Narrated) result.value();
        assertThat(narrated.passages()).extracting(NarrationProposal.Passage::kind).containsExactly(
                NarrationProposal.PassageKind.NARRATION, NarrationProposal.PassageKind.CHARACTER_SPEECH);
        assertThat(narrated.passages().get(1).speakerId()).isEqualTo(MARA);
        assertThat(narrated.suggestions()).extracting(NarrationProposal.Suggestion::actionId)
                .containsExactly("action.travel", null);
    }

    @Test
    void attributesSpeechOnlyToKnownNonPlayerSpeakers() {
        var player = narrationBody(SupportedLocale.IT);
        list(player, "passages").get(1).put("speakerId", PLAYER.toString());
        assertThat(codes(validate(italian, player))).containsExactly("player-voice");

        var stranger = narrationBody(SupportedLocale.IT);
        list(stranger, "passages").get(1).put("speakerId", STRANGER.toString());
        assertThat(codes(validate(italian, stranger))).containsExactly("unknown-reference");

        var narrator = narrationBody(SupportedLocale.IT);
        list(narrator, "passages").get(0).put("speakerId", MARA.toString());
        assertThat(codes(validate(italian, narrator))).containsExactly("speaker-not-allowed");

        var silent = narrationBody(SupportedLocale.IT);
        list(silent, "passages").get(1).put("speakerId", null);
        assertThat(codes(validate(italian, silent))).containsExactly("missing-value");
    }

    @Test
    void groundsEveryPassageInTheSuppliedBundle() {
        var unknown = narrationBody(SupportedLocale.IT);
        list(unknown, "passages").get(0).put("references", List.of("evt.1", "evt.99"));
        assertThat(codes(validate(italian, unknown))).containsExactly("unknown-reference");

        var omitted = narrationBody(SupportedLocale.IT);
        list(omitted, "passages").get(1).put("references", List.of("fact.inn"));
        assertThat(codes(validate(italian, omitted))).containsExactly("event-not-narrated");

        var uncited = narrationBody(SupportedLocale.IT);
        list(uncited, "passages").get(0).put("references", List.of());
        list(uncited, "passages").get(1).put("references", List.of("evt.1", "evt.2"));
        assertThat(codes(validate(italian, uncited))).containsExactly("out-of-bounds");

        // 2 is in the event text, 5 is a receipt display number; 12 appears nowhere.
        assertThat(validate(italian, withText(0, "Mara conta 5 monete e ti guarda.")).accepted()).isTrue();
        assertThat(codes(validate(italian, withText(0, "Mara ti colpisce e perdi 12 punti.")))).containsExactly(
                "unsupported-number");
        assertThat(codes(validate(italian, withText(0, "Mara conta 1.200 monete.")))).containsExactly(
                "unsupported-number");
    }

    @Test
    void stopsAtThePendingDecisionWithBoundedEditableSuggestions() {
        var advanced = narrationBody(SupportedLocale.IT);
        advanced.put("pendingDecisionId", "decision.after-the-mill");
        assertThat(codes(validate(italian, advanced))).containsExactly("pending-decision-mismatch");

        var many = narrationBody(SupportedLocale.IT);
        var four = new ArrayList<>(list(many, "suggestions"));
        four.add(suggestion("s3", "reply", "Ringrazia", "Grazie, Mara.", null));
        four.add(suggestion("s4", "reply", "Saluta", "A presto.", null));
        many.put("suggestions", four);
        assertThat(codes(validate(italian, many))).containsExactly("out-of-bounds");

        var unavailable = narrationBody(SupportedLocale.IT);
        list(unavailable, "suggestions").get(0).put("actionId", "action.attack");
        assertThat(codes(validate(italian, unavailable))).containsExactly("unknown-reference");

        var reply = narrationBody(SupportedLocale.IT);
        list(reply, "suggestions").get(1).put("actionId", "action.travel");
        assertThat(codes(validate(italian, reply))).containsExactly("status-field-conflict");

        var none = narrationBody(SupportedLocale.IT);
        none.put("suggestions", List.of());
        assertThat(validate(italian, none).accepted()).isTrue();
    }

    @Test
    void keepsPrivilegedKnowledgeOutOfEveryHumanFacingString() {
        assertThat(codes(validate(italian, withText(0, "Mara abbassa la voce: la  CABALA dell'ombra ti osserva.")))).containsExactly(
                "scope-leak");
        var draft = narrationBody(SupportedLocale.IT);
        list(draft, "suggestions").get(1).put("draftText", "Sai qualcosa della Shadow Cabal?");
        assertThat(codes(validate(italian, draft))).containsExactly("scope-leak");
    }

    @Test
    void refusesToRenderAContextThatAlreadyContainsAPrivilegedMarker() {
        var guard = new DisclosureGuard(List.of("ore di cammino"));
        assertThatThrownBy(() -> contract.render(narration(SupportedLocale.IT, guard), List.of()))
                .isInstanceOf(ContextScopeException.class).hasMessageNotContaining("cammino");
    }

    @Test
    void preservesGlossaryTermsAndProperNames() {
        assertThat(codes(validate(italian, withText(0, "Mara misura la tua Force con lo sguardo."))))
                .containsExactly("glossary-mismatch");
        assertThat(validate(italian, withText(0, "Mara misura la tua Forza con lo sguardo.")).accepted()).isTrue();
        assertThat(codes(validate(italian, withText(0, "MARA misura la tua Forza con lo sguardo."))))
                .containsExactly("glossary-mismatch");
        // Whole words only: "Forceps" is not the avoided term "Force".
        assertThat(validate(italian, withText(0, "Mara posa un Forceps sul bancone.")).accepted()).isTrue();
    }

    @Test
    void requiresDeclaredProtectedQuotesVerbatimAndExemptsThemFromTheLocaleSignal() {
        var quoted = withText(0, "Sul cancello è incisa una frase: \"" + QUOTE + "\". Mara la indica.");
        list(quoted, "passages").get(0).put("quoteIds", List.of("quote.gate"));
        var accepted = validate(italian, quoted);
        assertThat(accepted.accepted()).as(accepted.violations().toString()).isTrue();
        assertThat(accepted.warnings()).isEmpty();

        var altered = withText(0, "Sul cancello è incisa una frase: \"Only the worthy pass this gate\".");
        list(altered, "passages").get(0).put("quoteIds", List.of("quote.gate"));
        assertThat(codes(validate(italian, altered))).containsExactly("quote-altered");

        var unknown = narrationBody(SupportedLocale.IT);
        list(unknown, "passages").get(0).put("quoteIds", List.of("quote.password"));
        assertThat(codes(validate(italian, unknown))).containsExactly("unknown-reference");
    }

    @Test
    void detectsWrongLanguageNarrationButOnlyWarnsOnShortText() {
        var english = withText(0, "Mara leans on the counter and looks at you, and it is clear that she has more to say.");
        assertThat(codes(validate(italian, english))).containsExactly("locale-mismatch");

        var shortText = withText(0, "The gate is open.");
        var warned = validate(italian, shortText);
        assertThat(warned.accepted()).isTrue();
        assertThat(warned.warnings()).containsExactly("locale-uncertain");

        var italianInEnglish = narrationBody(SupportedLocale.EN);
        list(italianInEnglish, "passages").get(0).put("text",
                "Mara si appoggia al bancone e ti guarda, ed è chiaro che ha ancora molto da dire sul mulino.");
        assertThat(codes(validate(narration(SupportedLocale.EN), italianInEnglish))).containsExactly("locale-mismatch");
    }

    @Test
    void enforcesStructureBoundsAndPlainText() {
        var unknownField = narrationBody(SupportedLocale.IT);
        list(unknownField, "passages").get(0).put("hpDelta", -3);
        assertThat(codes(validate(italian, unknownField))).containsExactly("field-set");

        assertThat(codes(validate(italian, withText(0, "Mara sorride <script>alert(1)</script>"))))
                .containsExactly("executable-content");
        assertThat(codes(validate(italian, withText(0, "a".repeat(NarrationContract.MAX_PASSAGE + 1)))))
                .containsExactly("out-of-bounds");

        var duplicate = narrationBody(SupportedLocale.IT);
        list(duplicate, "passages").get(1).put("passageId", "p1");
        assertThat(codes(validate(italian, duplicate))).containsExactly("duplicate-reference");

        var empty = narrationBody(SupportedLocale.IT);
        empty.put("passages", List.of());
        assertThat(codes(validate(italian, empty))).containsExactly("out-of-bounds");

        var nine = narrationBody(SupportedLocale.IT);
        var passages = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 9; index++) {
            passages.add(passage("p" + index, "narration", null, "Mara annuisce.", List.of("evt.1", "evt.2")));
        }
        nine.put("passages", passages);
        assertThat(codes(validate(italian, nine))).containsExactly("out-of-bounds");

        var total = narrationBody(SupportedLocale.IT);
        var longPassages = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 6; index++) {
            longPassages.add(passage("p" + index, "narration", null, "Mara annuisce. ".repeat(70).strip(),
                    List.of("evt.1", "evt.2")));
        }
        total.put("passages", longPassages);
        assertThat(codes(validate(italian, total))).containsExactly("out-of-bounds");
    }

    @Test
    void acceptsCannotCompleteOnlyWithoutPassages() {
        var body = narrationBody(SupportedLocale.IT);
        body.put("pendingDecisionId", null);
        body.put("passages", List.of());
        body.put("suggestions", List.of());
        body.put("reasonCode", "missing-information");
        var result = contract.validate(italian, output(contract, italian, "cannot-complete", body));
        assertThat(result.value()).isEqualTo(new NarrationProposal.CannotNarrate("missing-information"));

        var withPassages = narrationBody(SupportedLocale.IT);
        withPassages.put("pendingDecisionId", null);
        withPassages.put("reasonCode", "out-of-scope");
        assertThat(codes(contract.validate(italian, output(contract, italian, "cannot-complete", withPassages))))
                .containsExactly("status-field-conflict", "status-field-conflict");
    }

    @Test
    void bindsTheOutputToTheExactContextAndPresentationGeneration() {
        String output = output(contract, italian, "proposed", narrationBody(SupportedLocale.IT));
        var changed = new NarrationContext(italian.binding(), italian.operationId(), italian.playerActorId(),
                List.of(new PublicFact("evt.1", "Il personaggio entra nella locanda."), italian.events().get(1)),
                italian.facts(), italian.speakers(), italian.displayNumbers(), italian.pendingDecision(),
                italian.glossary(), italian.quotes(), italian.guard());
        assertThat(codes(contract.validate(changed, output))).containsExactly("echo-mismatch");

        TaskBinding binding = italian.binding();
        assertThat(binding.presentationCurrent(SupportedLocale.IT, 2, 3)).isTrue();
        assertThat(binding.presentationCurrent(SupportedLocale.EN, 2, 3)).isFalse();
        assertThat(binding.presentationCurrent(SupportedLocale.IT, 3, 3)).isFalse();
        assertThat(binding.presentationCurrent(SupportedLocale.IT, 2, 4)).isFalse();

        String instructions = contract.render(italian, List.of()).instructions();
        assertThat(instructions).contains("directly in Italian (it)", "\"decisionId\":\"decision.next\"",
                "\"text\":\"" + QUOTE + "\"", "\"avoid\":[\"Force\"]");
        assertThat(instructions.toLowerCase()).doesNotContain("shadow cabal", "cabala dell'ombra");
        assertThat(contract.render(narration(SupportedLocale.EN), List.of()).instructions())
                .contains("directly in English (en)");
    }
}
