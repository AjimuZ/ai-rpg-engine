package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.presentation.SupportedLocale;

import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic EN/IT contexts and well-formed outputs for the contract suites. Every negative test
 * starts from a valid output built here and changes exactly one aspect, so a failure points at the
 * single rule under test.
 */
final class ContractFixtures {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TASK = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID ADVENTURE = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID CAMPAIGN = UUID.fromString("00000000-0000-4000-8000-000000000003");
    static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID MARA = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    static final UUID TULLO = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    static final UUID STRANGER = UUID.fromString("00000000-0000-4000-8000-0000000000c9");
    static final UUID OPERATION = UUID.fromString("00000000-0000-4000-8000-0000000000d1");
    static final String SECRET = "Shadow Cabal";
    static final String SECRET_IT = "Cabala dell'Ombra";
    static final String QUOTE = "Only the worthy may pass through this gate";

    private ContractFixtures() {
    }

    static TaskBinding binding(SupportedLocale locale) {
        return new TaskBinding(TASK, ADVENTURE, CAMPAIGN, 7, locale, 2, 3, "a".repeat(64), "b".repeat(64));
    }

    static DisclosureGuard guard() {
        return new DisclosureGuard(List.of(SECRET, SECRET_IT));
    }

    static List<GlossaryTerm> glossary(SupportedLocale locale) {
        return List.of(new GlossaryTerm("npc.mara", GlossaryTerm.Kind.PROPER_NAME, "Mara", List.of()),
                locale == SupportedLocale.IT
                        ? new GlossaryTerm("attribute.force", GlossaryTerm.Kind.TERM, "Forza", List.of("Force"))
                        : new GlossaryTerm("attribute.force", GlossaryTerm.Kind.TERM, "Force", List.of("Forza")));
    }

    static IntentContext intent(SupportedLocale locale, String playerText) {
        return intent(locale, playerText, Set.of(IntentKind.values()));
    }

    static IntentContext intent(SupportedLocale locale, String playerText, Set<IntentKind> kinds) {
        boolean it = locale == SupportedLocale.IT;
        return new IntentContext(binding(locale), PLAYER, playerText, kinds,
                List.of(new IntentContext.Destination("loc.mill", it ? "Il vecchio mulino" : "The old mill"),
                        new IntentContext.Destination("loc.square", it ? "La piazza" : "The square")),
                List.of(new KnownActor(MARA, "Mara"), new KnownActor(TULLO, "Tullo")),
                List.of(new IntentContext.AbilityOption("ability.mend", it ? "Ricucire" : "Mend", 1,
                        List.of(new KnownActor(MARA, "Mara"), new KnownActor(TULLO, "Tullo")))),
                3600, List.of(new PublicFact("fact.inn", it ? "Mara gestisce la locanda." : "Mara runs the inn.")),
                glossary(locale), guard());
    }

    /** A valid proposed MOVE body; tests change single fields. */
    static Map<String, Object> intentBody() {
        var body = new LinkedHashMap<String, Object>();
        body.put("intentKind", "MOVE");
        body.put("actorId", PLAYER.toString());
        body.put("destinationId", "loc.mill");
        body.put("recipientActorId", null);
        body.put("speechText", null);
        body.put("waitSeconds", null);
        body.put("abilityId", null);
        body.put("targetActorIds", new ArrayList<>());
        body.put("sourceFactIds", new ArrayList<>(List.of("fact.inn")));
        body.put("clarification", null);
        body.put("reasonCode", null);
        return body;
    }

    static NarrationContext narration(SupportedLocale locale) {
        return narration(locale, guard());
    }

    static NarrationContext narration(SupportedLocale locale, DisclosureGuard guard) {
        boolean it = locale == SupportedLocale.IT;
        return new NarrationContext(binding(locale), OPERATION, PLAYER,
                List.of(new PublicFact("evt.1", it ? "Il personaggio chiede a Mara dove si trova il vecchio mulino."
                                : "The character asks Mara where the old mill is."),
                        new PublicFact("evt.2", it ? "Mara risponde che il mulino è a 2 ore di cammino verso nord."
                                : "Mara answers that the mill is 2 hours away to the north.")),
                List.of(new PublicFact("fact.inn", it ? "Mara gestisce la locanda." : "Mara runs the inn.")),
                List.of(new KnownActor(MARA, "Mara")), List.of(5L),
                new NarrationContext.PendingDecision("decision.next", it ? "Cosa fai adesso?" : "What do you do now?",
                        List.of(new NarrationContext.ActionOption("action.travel", it ? "Vai al mulino" : "Go to the mill"),
                                new NarrationContext.ActionOption("action.ask", it ? "Chiedi altro" : "Ask more"))),
                glossary(locale), List.of(new ProtectedQuote("quote.gate", QUOTE, SupportedLocale.EN)), guard);
    }

    static Map<String, Object> passage(String id, String kind, UUID speaker, String text, List<String> refs) {
        var passage = new LinkedHashMap<String, Object>();
        passage.put("passageId", id);
        passage.put("kind", kind);
        passage.put("speakerId", speaker == null ? null : speaker.toString());
        passage.put("text", text);
        passage.put("references", new ArrayList<>(refs));
        passage.put("quoteIds", new ArrayList<>());
        return passage;
    }

    static Map<String, Object> suggestion(String id, String kind, String label, String draft, String action) {
        var suggestion = new LinkedHashMap<String, Object>();
        suggestion.put("suggestionId", id);
        suggestion.put("kind", kind);
        suggestion.put("label", label);
        suggestion.put("draftText", draft);
        suggestion.put("actionId", action);
        return suggestion;
    }

    /** A valid proposed narration body in the selected locale. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> narrationBody(SupportedLocale locale) {
        boolean it = locale == SupportedLocale.IT;
        var body = new LinkedHashMap<String, Object>();
        body.put("pendingDecisionId", "decision.next");
        body.put("passages", new ArrayList<>(List.of(
                passage("p1", "narration", null, it ? "Mara si appoggia al bancone e ti guarda con attenzione."
                        : "Mara leans on the counter and studies you carefully.", List.of("evt.1")),
                passage("p2", "characterSpeech", MARA, it ? "Il mulino è a 2 ore di cammino verso nord, oltre il ponte."
                        : "The mill is 2 hours away to the north, past the bridge.", List.of("evt.2", "fact.inn")))));
        body.put("suggestions", new ArrayList<>(List.of(
                suggestion("s1", "action", it ? "Vai al mulino" : "Go to the mill",
                        it ? "Vado subito al mulino." : "I head to the mill right away.", "action.travel"),
                suggestion("s2", "question", it ? "Chiedi della strada" : "Ask about the road",
                        it ? "C'è una strada sicura per arrivarci?" : "Is there a safe road to get there?", null))));
        body.put("reasonCode", null);
        return body;
    }

    /** Wrap a body in a correctly echoed envelope for the context. */
    static <C extends ContractContext> String output(TaskContract<C, ?> contract, C context, String status,
            Map<String, Object> body) {
        return JSON.writeValueAsString(envelope(contract, context, status, body));
    }

    static <C extends ContractContext> Map<String, Object> envelope(TaskContract<C, ?> contract, C context,
            String status, Map<String, Object> body) {
        var root = new LinkedHashMap<String, Object>();
        root.put("contractVersion", 1);
        root.put("taskId", context.binding().taskId().toString());
        root.put("taskKind", contract.kind().wire());
        root.put("baseRevision", context.binding().baseRevision());
        root.put("contextDigest", contract.render(context, List.of()).contextDigest());
        root.put("outputLocale", context.binding().outputLocale().tag());
        root.put("status", status);
        root.put("body", body);
        return root;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    static List<String> codes(Validation<?> validation) {
        return validation.violations().stream().map(ContractViolation::code).toList();
    }
}
