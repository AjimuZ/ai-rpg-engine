package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.commands.TypedCommand;

/**
 * Intent interpretation contract, version 1 (docs/21 sections 4 and 12).
 *
 * <p>Statuses and their exact body shapes:
 * <ul>
 *   <li>{@code proposed}: one {@code intentKind} allowed by the context, the context's acting
 *       {@code actorId}, exactly the fields of that kind (MOVE: a known {@code destinationId};
 *       WAIT: {@code waitSeconds} within the context bound; SPEAK: a known
 *       {@code recipientActorId} and {@code speechText} copied verbatim from the player's text;
 *       USE_ABILITY: a learned {@code abilityId} and distinct permitted {@code targetActorIds}
 *       within its target bound), known {@code sourceFactIds}, no clarification or reason.</li>
 *   <li>{@code needs-clarification}: a question and two to four interpretations whose kinds are
 *       allowed and whose references are known options; every proposal field empty.</li>
 *   <li>{@code cannot-complete}: one fixed reason code; every other field empty.</li>
 * </ul>
 *
 * <p>Player agency: the schema can express exactly one intention, the model cannot author
 * speech for the player (the {@code invented-speech} check), and there is no field for dice,
 * costs, damage, loot, relationship deltas or quest completion. A field not in the schema fails
 * the exact field-set check, which is how "effect" fields are rejected.
 */
public final class IntentContract implements TaskContract<IntentContext, IntentProposal> {
    /** Classpath resource of the output schema. */
    static final String SCHEMA_RESOURCE = "/ai-contract/1/intent.schema.json";
    /** Ordered body fields; the schema drift test compares them with the bundled schema. */
    static final List<String> BODY_FIELDS = List.of("intentKind", "actorId", "destinationId", "recipientActorId",
            "speechText", "waitSeconds", "abilityId", "targetActorIds", "sourceFactIds", "clarification", "reasonCode");
    /** Ordered clarification fields. */
    static final List<String> CLARIFICATION_FIELDS = List.of("question", "interpretations");
    /** Ordered interpretation fields. */
    static final List<String> INTERPRETATION_FIELDS = List.of("interpretationId", "label", "intentKind", "referenceIds");
    /** Cannot-complete reasons. */
    static final Set<String> REASONS = Set.of("unsupported-intention", "missing-information", "not-permitted");
    static final Set<String> STATUSES = Set.of("proposed", "needs-clarification", "cannot-complete");
    /** Output-local identifier syntax for interpretations, passages and suggestions. */
    static final Pattern LOCAL_ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    static final String TASK_INSTRUCTION = """
            Task: intent interpretation. Identify one declared player intention using only the known \
            options in CONTEXT. Do not resolve mechanics, roll dice, choose costs or describe outcomes. Do \
            not add another action for the player and do not write words the player did not write: speech \
            must be copied verbatim from playerText. If several known options fit a consequential \
            declaration (several targets, items or recipients), return needs-clarification with a short \
            question and two to four interpretations instead of choosing. If no supported intention fits, \
            return cannot-complete. Unused fields are null or empty arrays.""";

    private static final Set<String> KINDS = Set.of("MOVE", "WAIT", "SPEAK", "USE_ABILITY");
    private final String schema = ContractEnvelope.resource(SCHEMA_RESOURCE);
    private final String templateHash = ContractEnvelope.sha256(ContractEnvelope.SHARED_INSTRUCTION + "\n"
            + TASK_INSTRUCTION + "\n" + schema);

    /** Load the bundled schema; fails at construction when it is missing from the package. */
    public IntentContract() {
    }

    /** @return {@link TaskKind#INTENT} */
    @Override
    public TaskKind kind() {
        return TaskKind.INTENT;
    }

    /** @return the bundled intent schema text */
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
    public RenderedTask render(IntentContext context, List<ContractViolation> repair) {
        return ContractEnvelope.render(kind(), context, TASK_INSTRUCTION, data(context), repair);
    }

    /**
     * Ordered context tree. Lists keep their supplied order so equal contexts digest equally;
     * the allowed kinds are sorted because a set has no order.
     */
    static Map<String, Object> data(IntentContext context) {
        var data = new LinkedHashMap<String, Object>();
        data.put("actorId", context.actorId().toString());
        data.put("playerText", context.playerText());
        data.put("allowedIntentKinds", context.allowedKinds().stream().map(Enum::name).sorted().toList());
        data.put("maxWaitSeconds", context.maxWaitSeconds());
        data.put("destinations", context.destinations().stream()
                .map(d -> ordered("destinationId", d.destinationId(), "label", d.label())).toList());
        data.put("recipients", context.recipients().stream().map(IntentContract::actor).toList());
        data.put("abilities", context.abilities().stream().map(a -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("abilityId", a.abilityId());
            entry.put("name", a.name());
            entry.put("maxTargets", a.maxTargets());
            entry.put("targets", a.targets().stream().map(IntentContract::actor).toList());
            return entry;
        }).toList());
        data.put("facts", context.facts().stream().map(f -> ordered("id", f.id(), "text", f.text())).toList());
        data.put("glossary", ContractEnvelope.glossary(context.glossary()));
        return data;
    }

    /** {@inheritDoc} */
    @Override
    public Validation<IntentProposal> validate(IntentContext context, String output) {
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
        IntentProposal proposal = switch ((String) status) {
            case "proposed" -> proposed(context, reader, body);
            case "needs-clarification" -> clarification(context, reader, body, warnings);
            default -> cannotComplete(reader, body);
        };
        return violations.isEmpty() && proposal != null ? new Validation<>(proposal, violations, warnings)
                : Validation.rejected(violations.isEmpty()
                        ? List.of(ContractViolation.structural("unexpected-type", "/body")) : violations, warnings);
    }

    /** Validate a proposed intention and build its typed command. */
    private static IntentProposal proposed(IntentContext context, StrictJson reader, Map<String, Object> body) {
        String kind = reader.choice(body, "intentKind", "/body", KINDS, false);
        UUID actor = reader.uuid(body, "actorId", "/body", false);
        String destination = reader.text(body, "destinationId", "/body", 128, true);
        UUID recipient = reader.uuid(body, "recipientActorId", "/body", true);
        // Speech is the player's own text, so markup-looking characters ("<3") are not rejected here.
        String speech = reader.verbatim(body, "speechText", "/body", IntentContext.MAX_PLAYER_TEXT, true);
        Long wait = reader.integer(body, "waitSeconds", "/body", 1, 86_400, true);
        String ability = reader.text(body, "abilityId", "/body", 128, true);
        List<String> targets = reader.references(body, "targetActorIds", "/body", 8);
        List<String> facts = citedFacts(context, reader, body);
        requireEmpty(reader, body, "clarification");
        requireEmpty(reader, body, "reasonCode");
        if (actor != null && !actor.equals(context.actorId())) {
            reader.semantic("actor-mismatch", "/body/actorId");
        }
        if (kind == null) {
            return null;
        }
        IntentKind intent = IntentKind.valueOf(kind);
        if (!context.allowedKinds().contains(intent)) {
            reader.semantic("intent-not-allowed", "/body/intentKind");
        }
        // Each kind owns its fields; any other proposal field must stay empty so a second action
        // cannot ride along with the first.
        Set<String> own = switch (intent) {
            case MOVE -> Set.of("destinationId");
            case WAIT -> Set.of("waitSeconds");
            case SPEAK -> Set.of("recipientActorId", "speechText");
            case USE_ABILITY -> Set.of("abilityId", "targetActorIds");
        };
        for (String field : List.of("destinationId", "recipientActorId", "speechText", "waitSeconds", "abilityId",
                "targetActorIds")) {
            if (!own.contains(field)) {
                requireEmpty(reader, body, field);
            } else if (!field.equals("targetActorIds") && body.get(field) == null) {
                reader.structural("missing-value", "/body/" + field);
            }
        }
        TypedCommand command = null;
        switch (intent) {
            case MOVE -> {
                if (destination != null) {
                    if (context.destinations().stream().noneMatch(d -> d.destinationId().equals(destination))) {
                        reader.semantic("unknown-reference", "/body/destinationId");
                    } else {
                        command = new TypedCommand.Move(destination);
                    }
                }
            }
            case WAIT -> {
                if (wait != null) {
                    if (wait > context.maxWaitSeconds()) {
                        reader.semantic("wait-not-allowed", "/body/waitSeconds");
                    } else {
                        command = new TypedCommand.Wait(wait);
                    }
                }
            }
            case SPEAK -> {
                boolean known = recipient != null
                        && context.recipients().stream().anyMatch(r -> r.actorId().equals(recipient));
                if (recipient != null && !known) {
                    reader.semantic("unknown-reference", "/body/recipientActorId");
                }
                // Agency: the narrator-side model may only route the player's own words. A speech
                // text that is not a verbatim part of the declaration was authored by the model.
                boolean verbatim = speech != null && context.playerText().contains(speech.strip())
                        && !speech.strip().isEmpty();
                if (speech != null && !verbatim) {
                    reader.semantic("invented-speech", "/body/speechText");
                }
                if (known && verbatim) {
                    command = new TypedCommand.Speak(recipient, speech.strip());
                }
            }
            case USE_ABILITY -> command = ability(context, reader, ability, targets);
        }
        if (command == null || facts == null) {
            return null;
        }
        return new IntentProposal.Proposed(command, facts);
    }

    /** Validate an ability use against the learned ability and its permitted known targets. */
    private static TypedCommand ability(IntentContext context, StrictJson reader, String abilityId,
            List<String> targets) {
        if (abilityId == null || targets == null) {
            return null;
        }
        var option = context.abilities().stream().filter(a -> a.abilityId().equals(abilityId)).findFirst();
        if (option.isEmpty()) {
            reader.semantic("unknown-reference", "/body/abilityId");
            return null;
        }
        if (targets.size() > option.get().maxTargets()) {
            reader.semantic("too-many-targets", "/body/targetActorIds");
            return null;
        }
        var ids = new ArrayList<UUID>();
        for (int index = 0; index < targets.size(); index++) {
            UUID target = StrictJson.uuid(targets.get(index));
            if (target == null || option.get().targets().stream().noneMatch(t -> t.actorId().equals(target))) {
                reader.semantic("target-not-permitted", "/body/targetActorIds/" + index);
                return null;
            }
            ids.add(target);
        }
        return new TypedCommand.UseAbility(abilityId, ids);
    }

    /** Validate a clarification: bounded, known references, selected locale, nothing privileged. */
    private static IntentProposal clarification(IntentContext context, StrictJson reader, Map<String, Object> body,
            List<String> warnings) {
        for (String field : List.of("intentKind", "actorId", "destinationId", "recipientActorId", "speechText",
                "waitSeconds", "abilityId", "targetActorIds", "sourceFactIds", "reasonCode")) {
            requireEmpty(reader, body, field);
        }
        Map<String, Object> clarification = reader.object(body.get("clarification"), "/body/clarification",
                Set.copyOf(CLARIFICATION_FIELDS));
        if (clarification == null) {
            return null;
        }
        String question = reader.text(clarification, "question", "/body/clarification", 300, false);
        List<Object> entries = reader.array(clarification, "interpretations", "/body/clarification", 2, 4);
        if (question == null || entries == null) {
            return null;
        }
        Set<String> known = knownReferences(context);
        var interpretations = new ArrayList<IntentProposal.Interpretation>();
        var ids = new HashSet<String>();
        var texts = new ArrayList<PublicText.Located>(List.of(
                new PublicText.Located("/body/clarification/question", question)));
        for (int index = 0; index < entries.size(); index++) {
            String at = "/body/clarification/interpretations/" + index;
            Map<String, Object> entry = reader.object(entries.get(index), at, Set.copyOf(INTERPRETATION_FIELDS));
            if (entry == null) {
                continue;
            }
            String id = reader.text(entry, "interpretationId", at, 32, false);
            String label = reader.text(entry, "label", at, 120, false);
            String kind = reader.choice(entry, "intentKind", at, KINDS, false);
            List<String> references = reader.references(entry, "referenceIds", at, 8);
            if (id != null && (!LOCAL_ID.matcher(id).matches() || !ids.add(id))) {
                reader.structural("duplicate-reference", at + "/interpretationId");
            }
            if (kind != null && !context.allowedKinds().contains(IntentKind.valueOf(kind))) {
                reader.semantic("intent-not-allowed", at + "/intentKind");
            }
            if (references != null) {
                for (int ref = 0; ref < references.size(); ref++) {
                    if (!known.contains(references.get(ref))) {
                        reader.semantic("unknown-reference", at + "/referenceIds/" + ref);
                    }
                }
            }
            if (id != null && label != null && kind != null && references != null) {
                texts.add(new PublicText.Located(at + "/label", label));
                interpretations.add(new IntentProposal.Interpretation(id, label, IntentKind.valueOf(kind), references));
            }
        }
        PublicText.check(reader, context, texts, List.of(), warnings);
        return new IntentProposal.Clarification(question, interpretations);
    }

    /** Validate a cannot-complete result. */
    private static IntentProposal cannotComplete(StrictJson reader, Map<String, Object> body) {
        for (String field : List.of("intentKind", "actorId", "destinationId", "recipientActorId", "speechText",
                "waitSeconds", "abilityId", "targetActorIds", "sourceFactIds", "clarification")) {
            requireEmpty(reader, body, field);
        }
        String reason = reader.choice(body, "reasonCode", "/body", REASONS, false);
        return reason == null ? null : new IntentProposal.CannotComplete(reason);
    }

    /** @return cited fact references when all are known, otherwise null after recording violations */
    private static List<String> citedFacts(IntentContext context, StrictJson reader, Map<String, Object> body) {
        List<String> facts = reader.references(body, "sourceFactIds", "/body", 16);
        if (facts == null) {
            return null;
        }
        boolean known = true;
        for (int index = 0; index < facts.size(); index++) {
            String fact = facts.get(index);
            if (context.facts().stream().noneMatch(f -> f.id().equals(fact))) {
                reader.semantic("unknown-reference", "/body/sourceFactIds/" + index);
                known = false;
            }
        }
        return known ? facts : null;
    }

    /** @return every option identity a clarification may refer to */
    private static Set<String> knownReferences(IntentContext context) {
        var known = new HashSet<String>();
        context.destinations().forEach(d -> known.add(d.destinationId()));
        context.recipients().forEach(r -> known.add(r.actorId().toString()));
        context.abilities().forEach(a -> {
            known.add(a.abilityId());
            a.targets().forEach(t -> known.add(t.actorId().toString()));
        });
        context.facts().forEach(f -> known.add(f.id()));
        return known;
    }

    /** Record {@code status-field-conflict} unless the field is null or an empty array. */
    static void requireEmpty(StrictJson reader, Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value != null && !(value instanceof List<?> list && list.isEmpty())) {
            reader.semantic("status-field-conflict", "/body/" + field);
        }
    }

    /** @return an ordered two-entry map */
    static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        var map = new LinkedHashMap<String, Object>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    /** @return the ordered JSON view of a known actor */
    private static Map<String, Object> actor(KnownActor actor) {
        return ordered("actorId", actor.actorId().toString(), "name", actor.name());
    }
}
