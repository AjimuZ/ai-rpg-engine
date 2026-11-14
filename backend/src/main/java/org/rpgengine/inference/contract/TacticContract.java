package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rpgengine.combat.TacticCandidates;
import org.rpgengine.combat.TacticalIntent;
import org.rpgengine.inference.session.Audience;

/**
 * Tactic-choice contract, version 1 (P12.3, docs/21 sections 7 and 12): one acting non-player participant chooses one of its
 * knowledge-scoped legal candidates.
 *
 * <p>The task runs in the {@code tactician} role and the acting character's own audience ({@code character:<subject>}), so two
 * participants never share a runtime conversation, and none shares one with the narrator or a planner. Its context is the
 * {@link TacticCandidates.Menu}: what the actor observes and may do, nothing else.
 *
 * <p>Statuses and their exact body shapes:
 * <ul>
 *   <li>{@code proposed}: a supplied {@code candidateActionId}; for the move a {@code destinationId} among its destinations,
 *       for a strike a {@code targetId} among its targets, and for the Guard action or the end of the activation neither; an
 *       optional {@code reasonCode} from {@link #TACTIC_REASONS}.</li>
 *   <li>{@code cannot-complete}: {@code reasonCode} {@code missing-information} and every other field null.</li>
 * </ul>
 * Codes beyond the shared envelope and structure: {@code unknown-candidate}, {@code target-not-permitted},
 * {@code destination-not-permitted}, {@code missing-value} and {@code status-field-conflict}. A rejected output gets the
 * runner's single repair; after that the caller uses its deterministic fallback. There is no field for dice, damage, costs,
 * movement beyond one edge, a second action or any text shown to the player, and a reason code authorizes nothing.
 */
public final class TacticContract implements TaskContract<TacticContext, TacticProposal> {
    /** Classpath resource of the output schema. */
    static final String SCHEMA_RESOURCE = "/ai-contract/1/tactic.schema.json";
    /** Ordered body fields; the schema drift test compares them with the bundled schema. */
    static final List<String> BODY_FIELDS = List.of("candidateActionId", "targetId", "destinationId", "reasonCode");
    static final Set<String> STATUSES = Set.of("proposed", "cannot-complete");
    /** Optional diagnostic codes of a proposed choice (docs/21 section 7). */
    static final List<String> TACTIC_REASONS = List.of("protect-objective", "pressure-known-target", "support-ally",
            "conserve-resource", "retreat");
    /** Reasons of a cannot-complete result. */
    static final Set<String> CANNOT_REASONS = Set.of("missing-information");
    /** Longest candidate identity: {@code strike:} and a reference. */
    static final int MAX_CANDIDATE_ID = 160;

    static final String TASK_INSTRUCTION = """
            Task: tactic choice. Choose one action for the acting participant described by CONTEXT.actor, using only \
            what CONTEXT says this participant observes and believes. Select exactly one supplied candidate by its \
            candidateActionId. A move candidate needs one destinationId from its destinations and a strike candidate \
            one targetId from its targets; every other parameter is null. Do not infer anything that is not supplied, \
            such as participants this one cannot see, the player's items or plans, or anyone's hidden values. Do not \
            invent candidates, targets, zones, costs, dice or outcomes, and do not describe the action. reasonCode is \
            optional and must be one of CONTEXT.reasonCodes. If no candidate can be chosen within these rules, return \
            cannot-complete with reasonCode missing-information and every other field null.""";

    private final String schema = ContractEnvelope.resource(SCHEMA_RESOURCE);
    private final String templateHash = ContractEnvelope.sha256(ContractEnvelope.SHARED_INSTRUCTION + "\n"
            + TASK_INSTRUCTION + "\n" + schema);

    /** Load the bundled schema; fails at construction when it is missing from the package. */
    public TacticContract() {
    }

    /** @return {@link TaskKind#TACTIC} */
    @Override
    public TaskKind kind() {
        return TaskKind.TACTIC;
    }

    /** @return the acting character's audience, {@code character:<subject>} */
    @Override
    public Audience audience(TacticContext context) {
        return Audience.character(context.subject());
    }

    /** @return the bundled tactic schema text */
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
    public RenderedTask render(TacticContext context, List<ContractViolation> repair) {
        return ContractEnvelope.render(kind(), audience(context), context, TASK_INSTRUCTION, data(context), repair);
    }

    /**
     * Ordered context tree of exactly the menu's knowledge-scoped values, in menu order, so equal contexts digest equally.
     * The encounter identity, row version and the actor's campaign actor are deliberately absent: the binding carries the
     * revision and the session scope carries the character.
     */
    static Map<String, Object> data(TacticContext context) {
        var menu = context.menu();
        var actor = new LinkedHashMap<String, Object>();
        actor.put("participantId", menu.actorId());
        actor.put("zone", menu.zone());
        actor.put("actionPoints", menu.actionPoints());
        actor.put("health", menu.health());
        actor.put("healthMax", menu.healthMax());
        actor.put("focus", menu.focus());
        actor.put("focusMax", menu.focusMax());
        actor.put("guarding", menu.guarding());
        var data = new LinkedHashMap<String, Object>();
        data.put("round", menu.round());
        data.put("actor", actor);
        data.put("observed", menu.observed().stream().map(seen -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("participantId", seen.participantId());
            entry.put("relation", seen.opponent() ? "opponent" : "ally");
            entry.put("zone", seen.zone());
            entry.put("status", seen.status().name().toLowerCase(java.util.Locale.ROOT));
            entry.put("guarding", seen.guarding());
            return entry;
        }).toList());
        data.put("candidates", menu.candidates().stream().map(candidate -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("candidateActionId", candidate.id());
            entry.put("kind", candidate.kind().wire());
            entry.put("cost", candidate.cost());
            entry.put("attackId", candidate.attackId());
            entry.put("targets", candidate.targets());
            entry.put("destinations", candidate.destinations().stream()
                    .map(d -> IntentContract.ordered("zoneId", d.zone(), "cost", d.cost())).toList());
            return entry;
        }).toList());
        data.put("beliefs", context.beliefs().stream().map(b -> IntentContract.ordered("id", b.id(), "text", b.text()))
                .toList());
        data.put("reasonCodes", TACTIC_REASONS);
        return data;
    }

    /** {@inheritDoc} */
    @Override
    public Validation<TacticProposal> validate(TacticContext context, String output) {
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
        TacticProposal proposal;
        if (status.equals("cannot-complete")) {
            for (String field : List.of("candidateActionId", "targetId", "destinationId")) {
                IntentContract.requireEmpty(reader, body, field);
            }
            String reason = reader.choice(body, "reasonCode", "/body", CANNOT_REASONS, false);
            proposal = reason == null ? null : new TacticProposal.CannotChoose(reason);
        } else {
            proposal = chosen(context.menu(), reader, body);
        }
        return violations.isEmpty() && proposal != null ? new Validation<>(proposal, violations, warnings)
                : Validation.rejected(violations.isEmpty()
                        ? List.of(ContractViolation.structural("unexpected-type", "/body")) : violations, warnings);
    }

    /** Validate a proposed choice against the menu and build its intention; null after recording a violation. */
    private static TacticProposal chosen(TacticCandidates.Menu menu, StrictJson reader, Map<String, Object> body) {
        String id = reader.text(body, "candidateActionId", "/body", MAX_CANDIDATE_ID, false);
        String target = reader.text(body, "targetId", "/body", 128, true);
        String destination = reader.text(body, "destinationId", "/body", 128, true);
        String reason = reader.choice(body, "reasonCode", "/body", Set.copyOf(TACTIC_REASONS), true);
        if (id == null) {
            return null;
        }
        var candidate = menu.candidate(id);
        if (candidate == null) {
            reader.semantic("unknown-candidate", "/body/candidateActionId");
            return null;
        }
        // Each kind owns its parameter; any other one must stay empty so a second choice cannot ride along.
        if (candidate.kind() != TacticCandidates.Kind.MOVE) {
            IntentContract.requireEmpty(reader, body, "destinationId");
        }
        if (candidate.kind() != TacticCandidates.Kind.STRIKE) {
            IntentContract.requireEmpty(reader, body, "targetId");
        }
        TacticalIntent intent = switch (candidate.kind()) {
            case MOVE -> {
                if (destination == null) {
                    if (body.get("destinationId") == null) {
                        reader.structural("missing-value", "/body/destinationId");
                    }
                    yield null;
                }
                if (candidate.destinations().stream().noneMatch(d -> d.zone().equals(destination))) {
                    reader.semantic("destination-not-permitted", "/body/destinationId");
                    yield null;
                }
                yield new TacticalIntent.MoveZone(destination);
            }
            case STRIKE -> {
                if (target == null) {
                    if (body.get("targetId") == null) {
                        reader.structural("missing-value", "/body/targetId");
                    }
                    yield null;
                }
                if (!candidate.targets().contains(target)) {
                    reader.semantic("target-not-permitted", "/body/targetId");
                    yield null;
                }
                yield new TacticalIntent.Strike(candidate.attackId(), target);
            }
            case GUARD -> new TacticalIntent.Guard();
            case END_ACTIVATION -> new TacticalIntent.EndActivation();
        };
        if (intent == null || !menu.admits(intent)) {
            return null;
        }
        return new TacticProposal.Chosen(id, intent, reason);
    }
}
