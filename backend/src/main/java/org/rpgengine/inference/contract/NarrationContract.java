package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolved narration and quick-reply contract, version 1 (docs/21 sections 8 and 12, docs/29
 * section 5).
 *
 * <p>A {@code proposed} output is accepted only when all of these hold:
 * <ul>
 *   <li>Structure: exact field sets, one to eight passages of at most 1,200 characters and 6,000
 *       in total, at most three suggestions, distinct output-local identifiers, plain text only.</li>
 *   <li>Attribution: narration passages have no speaker; character speech names a known
 *       non-player speaker; the player's character never speaks ({@code player-voice}).</li>
 *   <li>Grounding: every reference is a supplied event or fact, every supplied event is cited by at
 *       least one passage ({@code event-not-narrated}), every number in the text is permitted by
 *       the receipt or already present in supplied text ({@code unsupported-number}).</li>
 *   <li>Agency: the echoed {@code pendingDecisionId} is the supplied one; an action suggestion
 *       names an available action of that decision; reply and question suggestions name none.</li>
 *   <li>Public scope: no guard marker in any passage, label or draft ({@code scope-leak}).</li>
 *   <li>Language: proper names keep their exact form, terms avoid wrong-locale forms, declared
 *       protected quotes occur verbatim, and the text is not dominated by the other language.</li>
 * </ul>
 * A {@code cannot-complete} output carries only a reason code and leads to the deterministic
 * receipt. Natural-language implication (an invented attacker described in new words) is beyond
 * these checks and remains a human-rubric evaluation (docs/21 section 13).
 */
public final class NarrationContract implements TaskContract<NarrationContext, NarrationProposal> {
    /** Classpath resource of the output schema. */
    static final String SCHEMA_RESOURCE = "/ai-contract/1/narration.schema.json";
    /** Ordered body fields; the schema drift test compares them with the bundled schema. */
    static final List<String> BODY_FIELDS = List.of("pendingDecisionId", "passages", "suggestions", "reasonCode");
    /** Ordered passage fields. */
    static final List<String> PASSAGE_FIELDS = List.of("passageId", "kind", "speakerId", "text", "references", "quoteIds");
    /** Ordered suggestion fields. */
    static final List<String> SUGGESTION_FIELDS = List.of("suggestionId", "kind", "label", "draftText", "actionId");
    static final Set<String> STATUSES = Set.of("proposed", "cannot-complete");
    static final Set<String> REASONS = Set.of("missing-information", "out-of-scope");
    /** Longest passage. */
    static final int MAX_PASSAGE = 1200;
    /** Longest total of all passage text. */
    static final int MAX_TOTAL = 6000;

    static final String TASK_INSTRUCTION = """
            Task: resolved narration. Describe only the supplied public resolved events and cite the event \
            or fact identities each passage relies on; every event must be narrated. Preserve the exact \
            mechanical outcome and use only numbers present in CONTEXT. Stop at the supplied pending \
            decision and echo its decisionId. Do not introduce actionable facts, new consequences, new \
            participants, unchosen movement, or speech or decisions for the player's character. Character \
            speech must name a supplied speaker. Optionally add up to three short editable suggestions for \
            the pending decision: a reply, a question, or one of its available actions by actionId. \
            Reproduce protected quotes exactly and list them in quoteIds. If the events cannot be narrated \
            within these rules, return cannot-complete. Unused fields are null or empty arrays.""";

    private final String schema = ContractEnvelope.resource(SCHEMA_RESOURCE);
    private final String templateHash = ContractEnvelope.sha256(ContractEnvelope.SHARED_INSTRUCTION + "\n"
            + TASK_INSTRUCTION + "\n" + schema);

    /** Load the bundled schema; fails at construction when it is missing from the package. */
    public NarrationContract() {
    }

    /** @return {@link TaskKind#NARRATION} */
    @Override
    public TaskKind kind() {
        return TaskKind.NARRATION;
    }

    /** @return the bundled narration schema text */
    @Override
    public String outputSchema() {
        return schema;
    }

    /** @return SHA-256 of the shared instruction, task instruction and schema */
    @Override
    public String templateHash() {
        return templateHash;
    }

    /** {@inheritDoc} */
    @Override
    public RenderedTask render(NarrationContext context, List<ContractViolation> repair) {
        return ContractEnvelope.render(kind(), context, TASK_INSTRUCTION, data(context), repair);
    }

    /** Ordered context tree in supplied order, so equal contexts digest equally. */
    static Map<String, Object> data(NarrationContext context) {
        var data = new LinkedHashMap<String, Object>();
        data.put("operationId", context.operationId().toString());
        data.put("playerActorId", context.playerActorId().toString());
        data.put("events", context.events().stream().map(e -> IntentContract.ordered("id", e.id(), "text", e.text())).toList());
        data.put("facts", context.facts().stream().map(f -> IntentContract.ordered("id", f.id(), "text", f.text())).toList());
        data.put("speakers", context.speakers().stream()
                .map(s -> IntentContract.ordered("actorId", s.actorId().toString(), "name", s.name())).toList());
        data.put("displayNumbers", context.displayNumbers());
        var decision = new LinkedHashMap<String, Object>();
        decision.put("decisionId", context.pendingDecision().decisionId());
        decision.put("prompt", context.pendingDecision().prompt());
        decision.put("actions", context.pendingDecision().actions().stream()
                .map(a -> IntentContract.ordered("actionId", a.actionId(), "label", a.label())).toList());
        data.put("pendingDecision", decision);
        data.put("glossary", ContractEnvelope.glossary(context.glossary()));
        data.put("protectedQuotes", context.quotes().stream().map(q -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("quoteId", q.quoteId());
            entry.put("text", q.text());
            entry.put("sourceLocale", q.sourceLocale().tag());
            return entry;
        }).toList());
        return data;
    }

    /** {@inheritDoc} */
    @Override
    public Validation<NarrationProposal> validate(NarrationContext context, String output) {
        var violations = new ArrayList<ContractViolation>();
        var warnings = new ArrayList<String>();
        var reader = new StrictJson(violations);
        String digest = ContractEnvelope.sha256(ContractEnvelope.json(data(context)));
        Map<String, Object> root = ContractEnvelope.root(reader, output, kind(), context.binding(), digest, STATUSES);
        if (root == null) {
            return Validation.rejected(violations, warnings);
        }
        Map<String, Object> body = reader.object(root.get("body"), "/body", Set.copyOf(BODY_FIELDS));
        Object status = root.get("status");
        if (body == null || !(status instanceof String) || !STATUSES.contains(status)) {
            return Validation.rejected(violations, warnings);
        }
        NarrationProposal proposal;
        if (status.equals("cannot-complete")) {
            for (String field : List.of("pendingDecisionId", "passages", "suggestions")) {
                IntentContract.requireEmpty(reader, body, field);
            }
            String reason = reader.choice(body, "reasonCode", "/body", REASONS, false);
            proposal = reason == null ? null : new NarrationProposal.CannotNarrate(reason);
        } else {
            proposal = narrated(context, reader, body, warnings);
        }
        return violations.isEmpty() && proposal != null ? new Validation<>(proposal, violations, warnings)
                : Validation.rejected(violations.isEmpty()
                        ? List.of(ContractViolation.structural("unexpected-type", "/body")) : violations, warnings);
    }

    /** Validate a proposed narration; returns null when any part is invalid. */
    private static NarrationProposal narrated(NarrationContext context, StrictJson reader, Map<String, Object> body,
            List<String> warnings) {
        IntentContract.requireEmpty(reader, body, "reasonCode");
        String decision = reader.text(body, "pendingDecisionId", "/body", 128, false);
        if (decision != null && !decision.equals(context.pendingDecision().decisionId())) {
            reader.semantic("pending-decision-mismatch", "/body/pendingDecisionId");
        }
        List<Object> passageEntries = reader.array(body, "passages", "/body", 1, 8);
        List<Object> suggestionEntries = reader.array(body, "suggestions", "/body", 0, 3);
        if (passageEntries == null || suggestionEntries == null) {
            return null;
        }
        var texts = new ArrayList<PublicText.Located>();
        var passages = new ArrayList<NarrationProposal.Passage>();
        var cited = new HashSet<String>();
        var passageIds = new HashSet<String>();
        var quoteTexts = new ArrayList<String>();
        int total = 0;
        for (int index = 0; index < passageEntries.size(); index++) {
            String at = "/body/passages/" + index;
            var passage = passage(context, reader, passageEntries.get(index), at, passageIds, cited, quoteTexts);
            if (passage != null) {
                total += passage.text().length();
                texts.add(new PublicText.Located(at + "/text", passage.text()));
                passages.add(passage);
            }
        }
        if (total > MAX_TOTAL) {
            reader.structural("out-of-bounds", "/body/passages");
        }
        // Coverage is judged only when every passage could be read: an unreadable passage already
        // fails the output, and its lost citations would only add a misleading second violation.
        boolean readable = passageEntries.stream().allMatch(entry -> entry instanceof Map<?, ?> map
                && map.keySet().equals(Set.copyOf(PASSAGE_FIELDS)));
        for (int index = 0; readable && index < context.events().size(); index++) {
            if (!cited.contains(context.events().get(index).id())) {
                // Reported once at the passage list: the omission is a property of the whole output,
                // and naming the missing event identity in a pointer would mix context into it.
                reader.semantic("event-not-narrated", "/body/passages");
                break;
            }
        }
        var suggestions = new ArrayList<NarrationProposal.Suggestion>();
        var suggestionIds = new HashSet<String>();
        for (int index = 0; index < suggestionEntries.size(); index++) {
            String at = "/body/suggestions/" + index;
            var suggestion = suggestion(context, reader, suggestionEntries.get(index), at, suggestionIds);
            if (suggestion != null) {
                texts.add(new PublicText.Located(at + "/label", suggestion.label()));
                texts.add(new PublicText.Located(at + "/draftText", suggestion.draftText()));
                suggestions.add(suggestion);
            }
        }
        Set<String> numbers = permittedNumbers(context);
        for (PublicText.Located located : texts) {
            for (String number : TextChecks.numbers(located.text())) {
                if (!numbers.contains(number)) {
                    reader.semantic("unsupported-number", located.path());
                    break;
                }
            }
        }
        PublicText.check(reader, context, texts, quoteTexts, warnings);
        return new NarrationProposal.Narrated(passages, suggestions);
    }

    /** Validate one passage; records citations and declared quote texts. */
    private static NarrationProposal.Passage passage(NarrationContext context, StrictJson reader, Object value,
            String at, Set<String> ids, Set<String> cited, List<String> quoteTexts) {
        Map<String, Object> entry = reader.object(value, at, Set.copyOf(PASSAGE_FIELDS));
        if (entry == null) {
            return null;
        }
        String id = reader.text(entry, "passageId", at, 32, false);
        String kind = reader.choice(entry, "kind", at, Set.of("narration", "characterSpeech"), false);
        UUID speaker = reader.uuid(entry, "speakerId", at, true);
        String text = reader.text(entry, "text", at, MAX_PASSAGE, false);
        List<String> references = reader.references(entry, "references", at, 16);
        List<String> quotes = reader.references(entry, "quoteIds", at, 4);
        if (id != null && (!IntentContract.LOCAL_ID.matcher(id).matches() || !ids.add(id))) {
            reader.structural("duplicate-reference", at + "/passageId");
        }
        if (references != null && references.isEmpty()) {
            reader.structural("out-of-bounds", at + "/references");
        }
        if ("narration".equals(kind) && entry.get("speakerId") != null) {
            reader.semantic("speaker-not-allowed", at + "/speakerId");
        }
        if ("characterSpeech".equals(kind)) {
            if (entry.get("speakerId") == null) {
                reader.structural("missing-value", at + "/speakerId");
            } else if (speaker != null && speaker.equals(context.playerActorId())) {
                reader.semantic("player-voice", at + "/speakerId");
            } else if (speaker != null && context.speakers().stream().noneMatch(s -> s.actorId().equals(speaker))) {
                reader.semantic("unknown-reference", at + "/speakerId");
            }
        }
        if (references != null) {
            for (int index = 0; index < references.size(); index++) {
                String reference = references.get(index);
                boolean known = context.events().stream().anyMatch(e -> e.id().equals(reference))
                        || context.facts().stream().anyMatch(f -> f.id().equals(reference));
                if (known) {
                    cited.add(reference);
                } else {
                    reader.semantic("unknown-reference", at + "/references/" + index);
                }
            }
        }
        if (quotes != null) {
            for (int index = 0; index < quotes.size(); index++) {
                String quoteId = quotes.get(index);
                var quote = context.quotes().stream().filter(q -> q.quoteId().equals(quoteId)).findFirst();
                if (quote.isEmpty()) {
                    reader.semantic("unknown-reference", at + "/quoteIds/" + index);
                } else if (text != null && !text.contains(quote.get().text())) {
                    reader.semantic("quote-altered", at + "/quoteIds/" + index);
                } else {
                    quoteTexts.add(quote.get().text());
                }
            }
        }
        if (id == null || kind == null || text == null || references == null || quotes == null) {
            return null;
        }
        var passageKind = kind.equals("narration") ? NarrationProposal.PassageKind.NARRATION
                : NarrationProposal.PassageKind.CHARACTER_SPEECH;
        return new NarrationProposal.Passage(id, passageKind, speaker, text, references, quotes);
    }

    /** Validate one quick-reply suggestion against the pending decision. */
    private static NarrationProposal.Suggestion suggestion(NarrationContext context, StrictJson reader, Object value,
            String at, Set<String> ids) {
        Map<String, Object> entry = reader.object(value, at, Set.copyOf(SUGGESTION_FIELDS));
        if (entry == null) {
            return null;
        }
        String id = reader.text(entry, "suggestionId", at, 32, false);
        String kind = reader.choice(entry, "kind", at, Set.of("reply", "question", "action"), false);
        String label = reader.text(entry, "label", at, 80, false);
        String draft = reader.text(entry, "draftText", at, 500, false);
        String action = reader.text(entry, "actionId", at, 128, true);
        if (id != null && (!IntentContract.LOCAL_ID.matcher(id).matches() || !ids.add(id))) {
            reader.structural("duplicate-reference", at + "/suggestionId");
        }
        if ("action".equals(kind)) {
            if (entry.get("actionId") == null) {
                reader.structural("missing-value", at + "/actionId");
            } else if (action != null && context.pendingDecision().actions().stream()
                    .noneMatch(a -> a.actionId().equals(action))) {
                reader.semantic("unknown-reference", at + "/actionId");
            }
        } else if (kind != null && entry.get("actionId") != null) {
            reader.semantic("status-field-conflict", at + "/actionId");
        }
        if (id == null || kind == null || label == null || draft == null) {
            return null;
        }
        var suggestionKind = switch (kind) {
            case "reply" -> NarrationProposal.SuggestionKind.REPLY;
            case "question" -> NarrationProposal.SuggestionKind.QUESTION;
            default -> NarrationProposal.SuggestionKind.ACTION;
        };
        return new NarrationProposal.Suggestion(id, suggestionKind, label, draft, action);
    }

    /**
     * @return normalized numbers the output may state: receipt display numbers plus digits that
     *         already occur in supplied public text (event, fact, speaker, decision, glossary and
     *         quote text), so restating a supplied fact is never a violation
     */
    private static Set<String> permittedNumbers(NarrationContext context) {
        var numbers = new HashSet<String>();
        context.displayNumbers().forEach(n -> numbers.add(Long.toString(n)));
        var sources = new ArrayList<String>();
        context.events().forEach(e -> sources.add(e.text()));
        context.facts().forEach(f -> sources.add(f.text()));
        context.speakers().forEach(s -> sources.add(s.name()));
        sources.add(context.pendingDecision().prompt());
        context.pendingDecision().actions().forEach(a -> sources.add(a.label()));
        context.glossary().forEach(g -> sources.add(g.rendering()));
        context.quotes().forEach(q -> sources.add(q.text()));
        sources.forEach(text -> numbers.addAll(TextChecks.numbers(text)));
        return numbers;
    }
}
