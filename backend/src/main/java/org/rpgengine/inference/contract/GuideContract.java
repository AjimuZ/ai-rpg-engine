package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rpgengine.inference.session.Audience;

/**
 * Guide contract, version 1 (P15.3-A, docs/10 section 10, docs/21 section 9): one authored guide persona answers one player
 * question with advice, from its own knowledge and with no authority over the game.
 *
 * <p>The task runs in the player-facing {@code guide} role and the persona's own audience ({@code character:guide:<persona>}),
 * so an answer never shares a runtime conversation with the public narrator, with another persona or with a privileged planner.
 *
 * <p>A {@code proposed} output is accepted only when all of these hold:
 * <ul>
 *   <li>Bounds: an {@code answer} of at most {@link #MAX_ANSWER} characters, plain text only, at most
 *       {@link #MAX_REFERENCES} references and {@link #MAX_SUGGESTIONS} suggestions, and a boolean {@code uncertain}.</li>
 *   <li>Grounding: every reference is the question or a supplied knowledge fact ({@code unknown-reference}); every suggestion
 *       is an action the context offered ({@code unknown-action}); an answer that cites nothing says so with
 *       {@code uncertain: true} ({@code uncertainty-required}); every number already occurs in supplied text
 *       ({@code unsupported-number}).</li>
 *   <li>Knowledge scope: no guard marker in the answer ({@code scope-leak}); glossary and language checks as for narration.</li>
 * </ul>
 * A {@code cannot-complete} output carries only {@code missing-information} or {@code out-of-scope}.
 *
 * <p><b>No mutation authority.</b> The body has no field for a command, intention, draft, cost, check or state change, and an
 * unknown field fails {@code field-set}. A suggestion is an identity the caller may show as a choice; submitting it is the
 * player's ordinary flow. Wording that implies an action was already taken, or hints at a hidden truth through paraphrase, is
 * beyond these mechanical checks and remains a human-rubric evaluation (docs/21 section 13).
 */
public final class GuideContract implements TaskContract<GuideContext, GuideProposal> {
    /** Classpath resource of the output schema. */
    static final String SCHEMA_RESOURCE = "/ai-contract/1/guide.schema.json";
    /** Ordered body fields; the schema drift test compares them with the bundled schema. */
    static final List<String> BODY_FIELDS = List.of("answer", "references", "suggestions", "uncertain", "reasonCode");
    static final Set<String> STATUSES = Set.of("proposed", "cannot-complete");
    static final Set<String> REASONS = Set.of("missing-information", "out-of-scope");
    /** Longest answer. */
    static final int MAX_ANSWER = 600;
    /** Most cited identities. */
    static final int MAX_REFERENCES = 8;
    /** Most recommended actions. */
    static final int MAX_SUGGESTIONS = 4;

    static final String TASK_INSTRUCTION = """
            Task: guide answer. Speak as the guide described by CONTEXT.persona, answering CONTEXT.question. Use only \
            CONTEXT.knowledge, which is everything this guide knows, and say plainly what it cannot know from its own \
            senses. Cite in references the question and knowledge ids the answer relies on. You may recommend actions \
            only by putting ids from CONTEXT.actions in suggestions; the player decides and submits any action \
            themselves. You cannot act: never claim to have done, started, spent, moved, completed, unlocked or drafted \
            anything, and never promise an outcome. Set uncertain to true when the sources are incomplete, and then \
            references may be empty. Use only numbers present in CONTEXT. If no answer fits these rules, return \
            cannot-complete with a reasonCode and every other field null or empty.""";

    private final String schema = ContractEnvelope.resource(SCHEMA_RESOURCE);
    private final String templateHash = ContractEnvelope.sha256(ContractEnvelope.SHARED_INSTRUCTION + "\n"
            + TASK_INSTRUCTION + "\n" + schema);

    /** Load the bundled schema; fails at construction when it is missing from the package. */
    public GuideContract() {
    }

    /** @return {@link TaskKind#GUIDE} */
    @Override
    public TaskKind kind() {
        return TaskKind.GUIDE;
    }

    /** @return the persona's audience, {@code character:guide:<persona>} */
    @Override
    public Audience audience(GuideContext context) {
        return Audience.character(context.subject());
    }

    /** @return the bundled guide schema text */
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
    public RenderedTask render(GuideContext context, List<ContractViolation> repair) {
        return ContractEnvelope.render(kind(), audience(context), context, TASK_INSTRUCTION, data(context), repair);
    }

    /**
     * Ordered context tree of exactly the persona's knowledge-scoped values, in supplied order, so equal contexts digest
     * equally. The persona's reference and every campaign actor are absent: the session scope carries the persona.
     */
    static Map<String, Object> data(GuideContext context) {
        var data = new LinkedHashMap<String, Object>();
        data.put("persona", IntentContract.ordered("name", context.persona().name(), "senses", context.persona().senses()));
        data.put("question", IntentContract.ordered("id", context.question().id(), "text", context.question().text()));
        data.put("knowledge", context.knowledge().stream().map(f -> IntentContract.ordered("id", f.id(), "text", f.text())).toList());
        data.put("actions", context.actions().stream().map(f -> IntentContract.ordered("id", f.id(), "label", f.text())).toList());
        data.put("glossary", ContractEnvelope.glossary(context.glossary()));
        return data;
    }

    /** {@inheritDoc} */
    @Override
    public Validation<GuideProposal> validate(GuideContext context, String output) {
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
        GuideProposal proposal;
        if (status.equals("cannot-complete")) {
            for (String field : List.of("answer", "references", "suggestions")) {
                IntentContract.requireEmpty(reader, body, field);
            }
            if (body.get("uncertain") != null) {
                reader.semantic("status-field-conflict", "/body/uncertain");
            }
            String reason = reader.choice(body, "reasonCode", "/body", REASONS, false);
            proposal = reason == null ? null : new GuideProposal.CannotAnswer(reason);
        } else {
            proposal = answered(context, reader, body, warnings);
        }
        return violations.isEmpty() && proposal != null ? new Validation<>(proposal, violations, warnings)
                : Validation.rejected(violations.isEmpty()
                        ? List.of(ContractViolation.structural("unexpected-type", "/body")) : violations, warnings);
    }

    /** Validate a proposed answer; returns null when any part is invalid. */
    private static GuideProposal answered(GuideContext context, StrictJson reader, Map<String, Object> body,
            List<String> warnings) {
        IntentContract.requireEmpty(reader, body, "reasonCode");
        String answer = reader.text(body, "answer", "/body", MAX_ANSWER, false);
        List<String> references = reader.references(body, "references", "/body", MAX_REFERENCES);
        List<String> suggestions = reader.references(body, "suggestions", "/body", MAX_SUGGESTIONS);
        Boolean uncertain = body.get("uncertain") instanceof Boolean flag ? flag : null;
        if (uncertain == null) {
            reader.structural("unexpected-type", "/body/uncertain");
        }
        if (references != null) {
            var known = new HashSet<String>();
            context.citable().forEach(fact -> known.add(fact.id()));
            for (int index = 0; index < references.size(); index++) {
                if (!known.contains(references.get(index))) {
                    reader.semantic("unknown-reference", "/body/references/" + index);
                }
            }
            if (references.isEmpty() && Boolean.FALSE.equals(uncertain)) {
                reader.semantic("uncertainty-required", "/body/uncertain");
            }
        }
        if (suggestions != null) {
            var offered = new HashSet<String>();
            context.actions().forEach(action -> offered.add(action.id()));
            for (int index = 0; index < suggestions.size(); index++) {
                if (!offered.contains(suggestions.get(index))) {
                    reader.semantic("unknown-action", "/body/suggestions/" + index);
                }
            }
        }
        if (answer != null) {
            Set<String> numbers = permittedNumbers(context);
            for (String number : TextChecks.numbers(answer)) {
                if (!numbers.contains(number)) {
                    reader.semantic("unsupported-number", "/body/answer");
                    break;
                }
            }
            PublicText.check(reader, context, List.of(new PublicText.Located("/body/answer", answer)), List.of(), warnings);
        }
        if (answer == null || references == null || suggestions == null || uncertain == null) {
            return null;
        }
        return new GuideProposal.Answered(answer, references, suggestions, uncertain);
    }

    /** @return normalized numbers already present in supplied text, so restating one is never a violation */
    private static Set<String> permittedNumbers(GuideContext context) {
        var numbers = new HashSet<String>();
        var sources = new ArrayList<String>();
        sources.add(context.persona().name());
        sources.add(context.persona().senses());
        context.citable().forEach(fact -> sources.add(fact.text()));
        context.actions().forEach(action -> sources.add(action.text()));
        context.glossary().forEach(term -> sources.add(term.rendering()));
        sources.forEach(text -> numbers.addAll(TextChecks.numbers(text)));
        return numbers;
    }
}
