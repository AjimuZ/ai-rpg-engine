package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.presentation.SupportedLocale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.contract.ContractFixtures.*;

/**
 * Continuity-summary contract (P09.3, docs/21 section 10): precise coverage, surviving obligations,
 * labelled uncertainty, grounded numbers and quotations, public scope and direct EN/IT output. Each
 * negative case starts from a valid body and changes one aspect.
 */
@SuppressWarnings("unchecked")
class SummaryContractTest {
    static final String PROMISE = "commitment:00000000-0000-4000-8000-0000000000e1";
    static final String RUMOR = "belief:00000000-0000-4000-8000-0000000000e2";
    static final String COMMON = "claim:00000000-0000-4000-8000-0000000000e3";
    final SummaryContract contract = new SummaryContract(TaskKind.SUMMARY);

    static SummaryContext context(SupportedLocale locale, DisclosureGuard guard) {
        boolean it = locale == SupportedLocale.IT;
        return new SummaryContext(binding(locale), List.of(
                new PublicFact("event:4", it ? "Mara si sposta dal sentiero al bosco." : "Mara moved from the trail to the grove."),
                new PublicFact("event:6", it ? "Passano 60 secondi." : "60 seconds pass.")),
                List.of(new SummaryContext.Source(PROMISE, "obligation", it ? "Ti mostrerò il guado prima del tramonto."
                                : "I will show you the ford before dusk."),
                        new SummaryContext.Source(RUMOR, "belief", it ? "Il guardiano nasconde una chiave (rumored)"
                                : "The warden hides a key (rumored)"),
                        new SummaryContext.Source(COMMON, "common-knowledge", it ? "Mara è diffidente" : "Mara is wary")),
                List.of(new SummaryContext.Entity("actor:" + MARA, "Mara"), new SummaryContext.Entity("location:grove",
                        it ? "Boschetto" : "Grove")), glossary(locale), guard);
    }

    static Map<String, Object> body(SupportedLocale locale) {
        boolean it = locale == SupportedLocale.IT;
        var body = new LinkedHashMap<String, Object>();
        body.put("summary", it ? "Mara ha raggiunto il bosco e ha atteso 60 secondi. Si dice che il guardiano nasconda "
                + "una chiave." : "Mara reached the grove and waited 60 seconds. It is rumored that the warden hides a key.");
        body.put("coveredEvents", new ArrayList<>(List.of("event:4", "event:6")));
        body.put("obligations", new ArrayList<>(List.of(note(PROMISE, it ? "La promessa del guado resta aperta."
                : "The promise of the ford is still open."))));
        body.put("entities", new ArrayList<>(List.of("actor:" + MARA, "location:grove")));
        body.put("uncertainties", new ArrayList<>(List.of(note(RUMOR, it ? "Solo una voce, non confermata."
                : "Only a rumor, unconfirmed."))));
        body.put("reasonCode", null);
        return body;
    }

    static Map<String, Object> note(String ref, String note) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ref", ref);
        entry.put("note", note);
        return entry;
    }

    Validation<SummaryProposal> validate(SupportedLocale locale, Map<String, Object> body) {
        var context = context(locale, guard());
        return contract.validate(context, output(contract, context, "proposed", body));
    }

    @ParameterizedTest
    @EnumSource(SupportedLocale.class)
    void acceptsAPreciseSourceLinkedSummaryInEachLocale(SupportedLocale locale) {
        var result = validate(locale, body(locale));
        assertThat(result.accepted()).as(result.violations().toString()).isTrue();
        var summarized = (SummaryProposal.Summarized) result.value();
        assertThat(summarized.coveredEvents()).containsExactly("event:4", "event:6");
        assertThat(summarized.obligations()).extracting(SummaryProposal.Note::ref).containsExactly(PROMISE);
        assertThat(summarized.uncertainties()).extracting(SummaryProposal.Note::ref).containsExactly(RUMOR);
    }

    @Test
    void coverageIsExactlyTheSuppliedEvents() {
        var invented = body(SupportedLocale.EN);
        ((List<Object>) invented.get("coveredEvents")).add("event:5");
        assertThat(codes(validate(SupportedLocale.EN, invented))).containsExactly("unknown-reference");
        var partial = body(SupportedLocale.EN);
        ((List<Object>) partial.get("coveredEvents")).remove("event:6");
        assertThat(codes(validate(SupportedLocale.EN, partial))).containsExactly("event-not-covered");
    }

    @Test
    void everyOpenObligationSurvivesAndNotesCiteSuppliedRecordsOnly() {
        var dropped = body(SupportedLocale.EN);
        dropped.put("obligations", List.of());
        assertThat(codes(validate(SupportedLocale.EN, dropped))).containsExactly("obligation-dropped");
        var foreign = body(SupportedLocale.EN);
        ((List<Object>) foreign.get("obligations")).add(note(COMMON, "Not an obligation."));
        assertThat(codes(validate(SupportedLocale.EN, foreign))).containsExactly("unknown-reference");
        var entity = body(SupportedLocale.EN);
        ((List<Object>) entity.get("entities")).add("actor:" + STRANGER);
        assertThat(codes(validate(SupportedLocale.EN, entity))).containsExactly("unknown-reference");
        var certain = body(SupportedLocale.EN);
        certain.put("uncertainties", List.of(note(COMMON, "Perhaps.")));
        assertThat(codes(validate(SupportedLocale.EN, certain))).containsExactly("uncertainty-not-belief");
    }

    @Test
    void numbersAndQuotationsMustBeGroundedInSuppliedText() {
        var number = body(SupportedLocale.EN);
        number.put("summary", "Mara reached the grove and waited 90 seconds.");
        assertThat(codes(validate(SupportedLocale.EN, number))).containsExactly("unsupported-number");
        var quote = body(SupportedLocale.EN);
        quote.put("summary", "Mara reached the grove. The warden said \"the key is mine\".");
        assertThat(codes(validate(SupportedLocale.EN, quote))).containsExactly("unsupported-quote");
        var grounded = body(SupportedLocale.EN);
        grounded.put("summary", "Mara reached the grove. The promise was “I will show you the ford before dusk.”");
        assertThat(validate(SupportedLocale.EN, grounded).accepted()).isTrue();
    }

    @Test
    void publicScopeAndLanguageAreEnforced() {
        var leak = body(SupportedLocale.EN);
        leak.put("summary", "Mara reached the grove, where the " + SECRET + " meets.");
        assertThat(codes(validate(SupportedLocale.EN, leak))).containsExactly("scope-leak");
        var wrongLanguage = body(SupportedLocale.IT);
        wrongLanguage.put("summary", "Mara reached the grove and waited there with the warden for the rest of the day.");
        assertThat(codes(validate(SupportedLocale.IT, wrongLanguage))).contains("locale-mismatch");
        var context = new SummaryContext(binding(SupportedLocale.EN), context(SupportedLocale.EN, guard()).events(),
                List.of(new SummaryContext.Source(COMMON, "fact", "The " + SECRET + " is real (true)")), List.of(),
                List.of(), DisclosureGuard.none());
        assertThatThrownBy(() -> contract.render(context, List.of())).isInstanceOf(ContextScopeException.class);
        assertThat(new SummaryContract(TaskKind.SUMMARY_PRIVILEGED).render(context, List.of()).instructions())
                .contains("\"standing\":\"fact\"");
    }

    @Test
    void cannotCompleteCarriesOnlyAReason() {
        var context = context(SupportedLocale.EN, guard());
        var body = new LinkedHashMap<String, Object>();
        body.put("summary", null);
        body.put("coveredEvents", List.of());
        body.put("obligations", List.of());
        body.put("entities", List.of());
        body.put("uncertainties", List.of());
        body.put("reasonCode", "missing-information");
        var result = contract.validate(context, output(contract, context, "cannot-complete", body));
        assertThat(result.value()).isEqualTo(new SummaryProposal.CannotSummarize("missing-information"));
        body.put("coveredEvents", List.of("event:4"));
        assertThat(codes(contract.validate(context, output(contract, context, "cannot-complete", body))))
                .containsExactly("status-field-conflict");
    }

    @Test
    void summaryKindsRunAsContinuityWorkInTheirOwnAudience() {
        assertThat(TaskKind.SUMMARY.role()).isEqualTo(AiRole.SUMMARIZER);
        assertThat(TaskKind.SUMMARY.audience()).isEqualTo(Audience.PUBLIC);
        assertThat(TaskKind.SUMMARY_PRIVILEGED.audience()).isEqualTo(Audience.PRIVILEGED);
        assertThat(TaskKind.SUMMARY.workClass()).isEqualTo(WorkClass.CONTINUITY);
        assertThat(TaskKind.SUMMARY.scope(binding(SupportedLocale.EN)))
                .isNotEqualTo(TaskKind.SUMMARY_PRIVILEGED.scope(binding(SupportedLocale.EN)));
        assertThatThrownBy(() -> new SummaryContract(TaskKind.NARRATION)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryContext(binding(SupportedLocale.EN), List.of(new PublicFact("event:6", "b"),
                new PublicFact("event:4", "a")), List.of(), List.of(), List.of(), DisclosureGuard.none()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
