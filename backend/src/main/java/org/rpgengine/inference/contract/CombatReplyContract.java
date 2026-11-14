package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rpgengine.inference.session.Audience;

/**
 * Combat-reply contract, version 1 (P12.4-B, docs/21 sections 8 and 15d): one addressed non-player participant answers the
 * player's words or a resolved social outcome in character, from its own knowledge.
 *
 * <p>The task runs in the player-facing {@code character-voice} role and the speaker's own audience
 * ({@code character:<subject>}), so the reply never shares a runtime conversation with the public narrator, which holds all the
 * player knows, nor with any other character or the tactician.
 *
 * <p>A {@code proposed} output is accepted only when all of these hold:
 * <ul>
 *   <li>Bounds: {@code speech} and {@code narration} of at most {@link #MAX_LINE} characters each and {@link #MAX_TOTAL}
 *       together, at least one of them present, plain text only, one to {@link #MAX_REFERENCES} references.</li>
 *   <li>Grounding: every reference is a supplied heard, outcome or belief identity ({@code unknown-reference}); every outcome
 *       statement is cited ({@code event-not-narrated}), so the reply is about the committed result; every number already
 *       occurs in supplied text ({@code unsupported-number}).</li>
 *   <li>Knowledge scope: no guard marker, such as a participant hidden from the speaker or another participant's campaign
 *       actor, in either line ({@code scope-leak}); glossary and language checks as for narration.</li>
 * </ul>
 * A {@code cannot-complete} output carries only {@code missing-information} or {@code out-of-scope}. There is no field for a
 * speaker, an action, an answer to an offer, a cost, a check or a state change: the reply is presentation only, and whether
 * terms were offered, refused or accepted is fixed by the committed events it cites. Implying an acceptance, compliance or a
 * new participant in free words is beyond these mechanical checks and remains a human-rubric evaluation (docs/21 section 13).
 */
public final class CombatReplyContract implements TaskContract<CombatReplyContext, CombatReplyProposal> {
    /** Classpath resource of the output schema. */
    static final String SCHEMA_RESOURCE = "/ai-contract/1/combat-reply.schema.json";
    /** Ordered body fields; the schema drift test compares them with the bundled schema. */
    static final List<String> BODY_FIELDS = List.of("speech", "narration", "references", "reasonCode");
    static final Set<String> STATUSES = Set.of("proposed", "cannot-complete");
    static final Set<String> REASONS = Set.of("missing-information", "out-of-scope");
    /** Longest speech or narration line. */
    static final int MAX_LINE = 300;
    /** Longest speech and narration together. */
    static final int MAX_TOTAL = 450;
    /** Most cited identities. */
    static final int MAX_REFERENCES = 8;

    static final String TASK_INSTRUCTION = """
            Task: combat reply. Speak as the participant described by CONTEXT.speaker, answering CONTEXT.heard (the \
            player's words to this participant) and reacting to CONTEXT.outcome (resolved public results). Use only what \
            CONTEXT says this participant observes and believes. speech is this participant's own words; narration is one \
            short description of its visible reaction; give one or both, each brief. Cite in references the heard, outcome \
            and belief ids the reply relies on, including every outcome id. The outcome is final: do not change, accept, \
            refuse or add terms, and do not invent damage, movement, participants, items, compliance or an end to the \
            fight. Never speak or decide for the player's character, and use only numbers present in CONTEXT. If no reply \
            fits these rules, return cannot-complete with a reasonCode and every other field null or empty.""";

    private final String schema = ContractEnvelope.resource(SCHEMA_RESOURCE);
    private final String templateHash = ContractEnvelope.sha256(ContractEnvelope.SHARED_INSTRUCTION + "\n"
            + TASK_INSTRUCTION + "\n" + schema);

    /** Load the bundled schema; fails at construction when it is missing from the package. */
    public CombatReplyContract() {
    }

    /** @return {@link TaskKind#COMBAT_REPLY} */
    @Override
    public TaskKind kind() {
        return TaskKind.COMBAT_REPLY;
    }

    /** @return the speaker's audience, {@code character:<subject>} */
    @Override
    public Audience audience(CombatReplyContext context) {
        return Audience.character(context.subject());
    }

    /** @return the bundled combat-reply schema text */
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
    public RenderedTask render(CombatReplyContext context, List<ContractViolation> repair) {
        return ContractEnvelope.render(kind(), audience(context), context, TASK_INSTRUCTION, data(context), repair);
    }

    /**
     * Ordered context tree of exactly the speaker's knowledge-scoped values, in supplied order, so equal contexts digest
     * equally. The encounter identity and every campaign actor are absent: the binding carries the revision and the session
     * scope carries the character.
     */
    static Map<String, Object> data(CombatReplyContext context) {
        var speaker = new LinkedHashMap<String, Object>();
        speaker.put("participantId", context.speaker().participantId());
        speaker.put("name", context.speaker().name());
        speaker.put("relationToPlayer", context.speaker().opponent() ? "opponent" : "ally");
        speaker.put("zone", context.speaker().zone());
        speaker.put("status", context.speaker().status().name().toLowerCase(java.util.Locale.ROOT));
        var data = new LinkedHashMap<String, Object>();
        data.put("speaker", speaker);
        data.put("observed", context.observed().stream().map(seen -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("participantId", seen.participantId());
            entry.put("relation", seen.opponent() ? "opponent" : "ally");
            entry.put("zone", seen.zone());
            entry.put("status", seen.status().name().toLowerCase(java.util.Locale.ROOT));
            entry.put("guarding", seen.guarding());
            return entry;
        }).toList());
        data.put("heard", context.heard() == null ? null
                : IntentContract.ordered("id", context.heard().id(), "text", context.heard().text()));
        data.put("outcome", context.outcome().stream().map(f -> IntentContract.ordered("id", f.id(), "text", f.text())).toList());
        data.put("beliefs", context.beliefs().stream().map(f -> IntentContract.ordered("id", f.id(), "text", f.text())).toList());
        data.put("glossary", ContractEnvelope.glossary(context.glossary()));
        return data;
    }

    /** {@inheritDoc} */
    @Override
    public Validation<CombatReplyProposal> validate(CombatReplyContext context, String output) {
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
        CombatReplyProposal proposal;
        if (status.equals("cannot-complete")) {
            for (String field : List.of("speech", "narration", "references")) {
                IntentContract.requireEmpty(reader, body, field);
            }
            String reason = reader.choice(body, "reasonCode", "/body", REASONS, false);
            proposal = reason == null ? null : new CombatReplyProposal.CannotReply(reason);
        } else {
            proposal = replied(context, reader, body, warnings);
        }
        return violations.isEmpty() && proposal != null ? new Validation<>(proposal, violations, warnings)
                : Validation.rejected(violations.isEmpty()
                        ? List.of(ContractViolation.structural("unexpected-type", "/body")) : violations, warnings);
    }

    /** Validate a proposed reply; returns null when any part is invalid. */
    private static CombatReplyProposal replied(CombatReplyContext context, StrictJson reader, Map<String, Object> body,
            List<String> warnings) {
        IntentContract.requireEmpty(reader, body, "reasonCode");
        String speech = reader.text(body, "speech", "/body", MAX_LINE, true);
        String narration = reader.text(body, "narration", "/body", MAX_LINE, true);
        List<String> references = reader.references(body, "references", "/body", MAX_REFERENCES);
        if (body.get("speech") == null && body.get("narration") == null) {
            reader.structural("missing-value", "/body/speech");
        }
        if (speech != null && narration != null && speech.length() + narration.length() > MAX_TOTAL) {
            reader.structural("out-of-bounds", "/body");
        }
        if (references != null) {
            if (references.isEmpty()) {
                reader.structural("out-of-bounds", "/body/references");
            }
            var known = new HashSet<String>();
            context.citable().forEach(fact -> known.add(fact.id()));
            for (int index = 0; index < references.size(); index++) {
                if (!known.contains(references.get(index))) {
                    reader.semantic("unknown-reference", "/body/references/" + index);
                }
            }
            if (context.outcome().stream().anyMatch(fact -> !references.contains(fact.id()))) {
                // Reported once at the list: naming the uncited identity in a pointer would mix context into it.
                reader.semantic("event-not-narrated", "/body/references");
            }
        }
        var texts = new ArrayList<PublicText.Located>();
        if (speech != null) {
            texts.add(new PublicText.Located("/body/speech", speech));
        }
        if (narration != null) {
            texts.add(new PublicText.Located("/body/narration", narration));
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
        PublicText.check(reader, context, texts, List.of(), warnings);
        if (references == null || texts.isEmpty()) {
            return null;
        }
        return new CombatReplyProposal.Replied(speech, narration, references);
    }

    /** @return normalized numbers already present in supplied text, so restating one is never a violation */
    private static Set<String> permittedNumbers(CombatReplyContext context) {
        var numbers = new HashSet<String>();
        var sources = new ArrayList<String>();
        sources.add(context.speaker().name());
        context.citable().forEach(fact -> sources.add(fact.text()));
        context.glossary().forEach(term -> sources.add(term.rendering()));
        sources.forEach(text -> numbers.addAll(TextChecks.numbers(text)));
        return numbers;
    }
}
