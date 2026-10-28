package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Continuity-summary contract, version 1 (P09.3, docs/21 section 10, docs/08 section 8).
 *
 * <p>A {@code proposed} output is accepted only when all of these hold:
 * <ul>
 *   <li>Structure: exact field sets, a non-empty summary of at most {@value #MAX_SUMMARY}
 *       characters, notes of at most {@value #MAX_NOTE} characters, bounded distinct lists, plain
 *       text only.</li>
 *   <li>Precise coverage: {@code coveredEvents} lists supplied events only
 *       ({@code unknown-reference}) and every supplied event ({@code event-not-covered}); coverage
 *       is never claimed for anything the summarizer did not receive.</li>
 *   <li>Obligations survive: every supplied open obligation has exactly one note
 *       ({@code obligation-dropped}), and no note names anything else ({@code unknown-reference}).
 *       A summary cannot make a promise disappear by assuming it was kept.</li>
 *   <li>Entities and uncertainties cite supplied references only; an uncertainty note must name a
 *       supplied {@code belief} ({@code uncertainty-not-belief}), so speculation stays labelled.</li>
 *   <li>Grounding: every number in the text already occurs in supplied text
 *       ({@code unsupported-number}; numeric current state comes from the database, never from
 *       summary prose), and every quoted span of four or more characters occurs verbatim in
 *       supplied text ({@code unsupported-quote}), so speculation is never turned into a
 *       quotation.</li>
 *   <li>Scope and language: no guard marker ({@code scope-leak}), glossary renderings respected,
 *       and the text not dominated by the other supported language.</li>
 * </ul>
 * A {@code cannot-complete} output carries only a reason code. Inventing a fact in new words, or
 * calling an open promise fulfilled in prose, is beyond these mechanical checks and remains a
 * human-rubric evaluation (docs/21 section 13); the stored summary is in any case labelled as a
 * derived aid that current state overrides.
 */
public final class SummaryContract implements TaskContract<SummaryContext, SummaryProposal> {
    /** Classpath resource of the output schema, shared by both summary kinds. */
    static final String SCHEMA_RESOURCE = "/ai-contract/1/summary.schema.json";
    /** Ordered body fields; the schema drift test compares them with the bundled schema. */
    static final List<String> BODY_FIELDS = List.of("summary", "coveredEvents", "obligations", "entities",
            "uncertainties", "reasonCode");
    /** Ordered note fields. */
    static final List<String> NOTE_FIELDS = List.of("ref", "note");
    static final Set<String> STATUSES = Set.of("proposed", "cannot-complete");
    static final Set<String> REASONS = Set.of("missing-information", "out-of-scope");
    /** Longest summary text. */
    static final int MAX_SUMMARY = 2000;
    /** Longest obligation or uncertainty note. */
    static final int MAX_NOTE = 300;
    /** Most uncertainty notes. */
    static final int MAX_UNCERTAINTIES = 16;
    /** Shortest quoted span that must be grounded; shorter quotes are ordinary emphasis. */
    static final int MIN_QUOTE = 4;
    /** Spans between straight, curly, low and angle quotation marks. */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"|“([^”]+)”|„([^“”]+)[“”]|«([^»]+)»");

    static final String TASK_INSTRUCTION = """
            Task: continuity summary. Condense the supplied events for later recall by the same \
            audience. List every supplied event identity in coveredEvents and nothing else. Preserve \
            unresolved promises: give every supplied obligation exactly one short note and never \
            describe it as kept, broken or released. Keep beliefs and rumors labelled as uncertain \
            and list them in uncertainties; never present them as facts. Mention only the supplied \
            events, sources and entities; do not invent events, consequences, quotations, numbers or \
            current values such as resources or locations. Quote only text that appears verbatim in \
            CONTEXT. List the actor and location references the summary concerns in entities. If the \
            events cannot be summarized within these rules, return cannot-complete. Unused fields are \
            null or empty arrays.""";

    private final TaskKind kind;
    private final String schema = ContractEnvelope.resource(SCHEMA_RESOURCE);
    private final String templateHash = ContractEnvelope.sha256(ContractEnvelope.SHARED_INSTRUCTION + "\n"
            + TASK_INSTRUCTION + "\n" + schema);

    /**
     * @param kind {@link TaskKind#SUMMARY} or {@link TaskKind#SUMMARY_PRIVILEGED}
     * @throws IllegalArgumentException for any other kind
     */
    public SummaryContract(TaskKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
        if (kind != TaskKind.SUMMARY && kind != TaskKind.SUMMARY_PRIVILEGED) {
            throw new IllegalArgumentException("not a summary kind");
        }
    }

    /** @return the summary kind this instance serves */
    @Override
    public TaskKind kind() {
        return kind;
    }

    /** @return the bundled summary schema text */
    @Override
    public String outputSchema() {
        return schema;
    }

    /** @return SHA-256 of the shared instruction, task instruction and schema */
    @Override
    public String templateHash() {
        return templateHash;
    }

    /**
     * {@inheritDoc}
     *
     * @throws ContextScopeException additionally when a public summary context carries a
     *         privileged {@code fact} source, which only an assembler defect can produce
     */
    @Override
    public RenderedTask render(SummaryContext context, List<ContractViolation> repair) {
        if (kind == TaskKind.SUMMARY && context.sources().stream().anyMatch(s -> s.standing().equals("fact"))) {
            throw new ContextScopeException();
        }
        return ContractEnvelope.render(kind, context, TASK_INSTRUCTION, data(context), repair);
    }

    /** Ordered context tree in supplied order, so equal contexts digest equally. */
    static Map<String, Object> data(SummaryContext context) {
        var data = new LinkedHashMap<String, Object>();
        data.put("events", context.events().stream().map(e -> IntentContract.ordered("id", e.id(), "text", e.text()))
                .toList());
        data.put("sources", context.sources().stream().map(s -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", s.id());
            entry.put("standing", s.standing());
            entry.put("text", s.text());
            return entry;
        }).toList());
        data.put("entities", context.entities().stream()
                .map(e -> IntentContract.ordered("ref", e.ref(), "name", e.name())).toList());
        data.put("glossary", ContractEnvelope.glossary(context.glossary()));
        return data;
    }

    /** {@inheritDoc} */
    @Override
    public Validation<SummaryProposal> validate(SummaryContext context, String output) {
        var violations = new ArrayList<ContractViolation>();
        var warnings = new ArrayList<String>();
        var reader = new StrictJson(violations);
        String digest = ContractEnvelope.sha256(ContractEnvelope.json(data(context)));
        Map<String, Object> root = ContractEnvelope.root(reader, output, kind, context.binding(), digest, STATUSES);
        if (root == null) {
            return Validation.rejected(violations, warnings);
        }
        Map<String, Object> body = reader.object(root.get("body"), "/body", Set.copyOf(BODY_FIELDS));
        Object status = root.get("status");
        if (body == null || !(status instanceof String) || !STATUSES.contains(status)) {
            return Validation.rejected(violations, warnings);
        }
        SummaryProposal proposal;
        if (status.equals("cannot-complete")) {
            for (String field : List.of("summary", "coveredEvents", "obligations", "entities", "uncertainties")) {
                IntentContract.requireEmpty(reader, body, field);
            }
            String reason = reader.choice(body, "reasonCode", "/body", REASONS, false);
            proposal = reason == null ? null : new SummaryProposal.CannotSummarize(reason);
        } else {
            proposal = summarized(context, reader, body, warnings);
        }
        return violations.isEmpty() && proposal != null ? new Validation<>(proposal, violations, warnings)
                : Validation.rejected(violations.isEmpty()
                        ? List.of(ContractViolation.structural("unexpected-type", "/body")) : violations, warnings);
    }

    /** Validate a proposed summary; returns null when any part is unreadable. */
    private SummaryProposal summarized(SummaryContext context, StrictJson reader, Map<String, Object> body,
            List<String> warnings) {
        IntentContract.requireEmpty(reader, body, "reasonCode");
        String summary = reader.text(body, "summary", "/body", MAX_SUMMARY, false);
        if (summary != null && summary.isBlank()) {
            reader.structural("missing-value", "/body/summary");
        }
        List<String> covered = reader.references(body, "coveredEvents", "/body", SummaryContext.MAX_EVENTS);
        List<Object> obligationEntries = reader.array(body, "obligations", "/body", 0, SummaryContext.MAX_SOURCES);
        List<String> entities = reader.references(body, "entities", "/body", SummaryContext.MAX_ENTITIES);
        List<Object> uncertaintyEntries = reader.array(body, "uncertainties", "/body", 0, MAX_UNCERTAINTIES);
        var texts = new ArrayList<PublicText.Located>();
        if (summary != null) {
            texts.add(new PublicText.Located("/body/summary", summary));
        }
        if (covered != null) {
            var supplied = context.events().stream().map(PublicFact::id).toList();
            for (int index = 0; index < covered.size(); index++) {
                if (!supplied.contains(covered.get(index))) {
                    reader.semantic("unknown-reference", "/body/coveredEvents/" + index);
                }
            }
            if (!covered.containsAll(supplied)) {
                // Reported once at the list: naming the missing event in a pointer would mix context into it.
                reader.semantic("event-not-covered", "/body/coveredEvents");
            }
        }
        var obligations = notes(reader, obligationEntries, "/body/obligations", texts);
        if (obligations != null) {
            var open = context.obligationIds();
            var noted = new HashSet<String>();
            for (int index = 0; index < obligations.size(); index++) {
                String ref = obligations.get(index).ref();
                if (!open.contains(ref)) {
                    reader.semantic("unknown-reference", "/body/obligations/" + index + "/ref");
                } else if (!noted.add(ref)) {
                    reader.structural("duplicate-reference", "/body/obligations/" + index + "/ref");
                }
            }
            if (!noted.containsAll(open)) {
                reader.semantic("obligation-dropped", "/body/obligations");
            }
        }
        if (entities != null) {
            for (int index = 0; index < entities.size(); index++) {
                String ref = entities.get(index);
                if (context.entities().stream().noneMatch(e -> e.ref().equals(ref))) {
                    reader.semantic("unknown-reference", "/body/entities/" + index);
                }
            }
        }
        var uncertainties = notes(reader, uncertaintyEntries, "/body/uncertainties", texts);
        if (uncertainties != null) {
            var noted = new HashSet<String>();
            for (int index = 0; index < uncertainties.size(); index++) {
                String ref = uncertainties.get(index).ref();
                var source = context.sources().stream().filter(s -> s.id().equals(ref)).findFirst();
                if (source.isEmpty()) {
                    reader.semantic("unknown-reference", "/body/uncertainties/" + index + "/ref");
                } else if (!source.get().standing().equals(SummaryContext.Source.BELIEF)) {
                    reader.semantic("uncertainty-not-belief", "/body/uncertainties/" + index + "/ref");
                } else if (!noted.add(ref)) {
                    reader.structural("duplicate-reference", "/body/uncertainties/" + index + "/ref");
                }
            }
        }
        var supplied = suppliedTexts(context);
        Set<String> numbers = new HashSet<>();
        supplied.forEach(text -> numbers.addAll(TextChecks.numbers(text)));
        String corpus = TextChecks.fold(String.join("\n", supplied));
        var quoted = new ArrayList<String>();
        for (PublicText.Located located : texts) {
            for (String number : TextChecks.numbers(located.text())) {
                if (!numbers.contains(number)) {
                    reader.semantic("unsupported-number", located.path());
                    break;
                }
            }
            Matcher matcher = QUOTED.matcher(located.text());
            while (matcher.find()) {
                String span = firstGroup(matcher);
                if (TextChecks.fold(span).length() >= MIN_QUOTE && !corpus.contains(TextChecks.fold(span))) {
                    reader.semantic("unsupported-quote", located.path());
                    break;
                }
                quoted.add(span);
            }
        }
        // Grounded quotations keep their source language, so they are excluded from the locale signal.
        PublicText.check(reader, context, texts, quoted, warnings);
        if (summary == null || covered == null || obligations == null || entities == null || uncertainties == null) {
            return null;
        }
        return new SummaryProposal.Summarized(summary, covered, obligations, entities, uncertainties);
    }

    /** Read a list of notes; returns null after recording a violation for any unreadable entry. */
    private static List<SummaryProposal.Note> notes(StrictJson reader, List<Object> entries, String path,
            List<PublicText.Located> texts) {
        if (entries == null) {
            return null;
        }
        var notes = new ArrayList<SummaryProposal.Note>();
        boolean readable = true;
        for (int index = 0; index < entries.size(); index++) {
            String at = path + "/" + index;
            Map<String, Object> entry = reader.object(entries.get(index), at, Set.copyOf(NOTE_FIELDS));
            if (entry == null) {
                readable = false;
                continue;
            }
            String ref = reader.text(entry, "ref", at, 128, false);
            String note = reader.text(entry, "note", at, MAX_NOTE, false);
            if (ref != null && !ContextValues.REFERENCE.matcher(ref).matches()) {
                reader.structural("unexpected-type", at + "/ref");
                ref = null;
            }
            if (ref == null || note == null) {
                readable = false;
                continue;
            }
            texts.add(new PublicText.Located(at + "/note", note));
            notes.add(new SummaryProposal.Note(ref, note));
        }
        return readable ? notes : null;
    }

    /** @return every supplied human text: events, sources, entity names and glossary renderings */
    private static List<String> suppliedTexts(SummaryContext context) {
        var texts = new ArrayList<String>();
        context.events().forEach(e -> texts.add(e.text()));
        context.sources().forEach(s -> texts.add(s.text()));
        context.entities().forEach(e -> texts.add(e.name()));
        context.glossary().forEach(g -> texts.add(g.rendering()));
        return texts;
    }

    /** @return the text of whichever alternative of {@link #QUOTED} matched */
    private static String firstGroup(Matcher matcher) {
        for (int group = 1; group <= matcher.groupCount(); group++) {
            if (matcher.group(group) != null) {
                return matcher.group(group);
            }
        }
        return "";
    }
}
