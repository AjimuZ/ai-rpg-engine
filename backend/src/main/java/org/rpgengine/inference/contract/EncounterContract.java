package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.rpgengine.combat.EncounterOption;
import org.rpgengine.combat.EncounterTemplate;
import org.rpgengine.combat.ThreatBudget;
import org.rpgengine.rules.CombatProfile;

/**
 * Encounter-proposal contract, version 1 (P13.2-A, docs/12 section 3 steps 4-7, docs/21 sections 6 and 12): the encounter
 * proposer composes the hostile roster of one eligible authored template within the party's threat budget.
 *
 * <p>The task runs in the {@code encounter-proposer} role's privileged audience, so it never shares a runtime conversation with
 * a player-facing role, and its output is never shown. Its context is the snapshot hash, the threat band and, per option, the
 * template's zones, starting zones, edges, hostile participants with their tier and cost, allies and objective kinds.
 * Statistics are not rendered, and the proposal has no field to change them.
 *
 * <p>Statuses and their exact body shapes:
 * <ul>
 *   <li>{@code proposed}: {@code snapshotHash} copied from the context, a supplied {@code templateId}, a {@code roster} of
 *       1..{@link #MAX_ROSTER} entries, each a hostile {@code participantId} of that template, at most once, with a
 *       {@code zoneId} among its starting zones, and {@code reasonCode} null.</li>
 *   <li>{@code cannot-complete}: {@code reasonCode} from {@link #CANNOT_REASONS}, {@code snapshotHash} and {@code templateId}
 *       null and an empty {@code roster}.</li>
 * </ul>
 * Codes beyond the shared envelope and structure: {@code unknown-template}, {@code unknown-participant},
 * {@code duplicate-participant}, {@code zone-not-permitted}, {@code zone-capacity}, {@code too-many-hostiles},
 * {@code over-budget}, {@code under-budget} (any other {@link ThreatBudget.Violation} in the same lowercase form),
 * {@code template-invalid} and {@code status-field-conflict}; a {@code snapshotHash} other than the context's is
 * {@code echo-mismatch}. A rejected output gets the runner's single repair; after that the caller uses the deterministic
 * fallback of {@link EncounterFallback}. There is no field for statistics, costs, levels, new participants, allies, objectives,
 * zones, phases, rewards or text, so none of them can be proposed.
 */
public final class EncounterContract implements TaskContract<EncounterContext, EncounterProposal> {
    /** Classpath resource of the output schema. */
    static final String SCHEMA_RESOURCE = "/ai-contract/1/encounter.schema.json";
    /** Ordered body fields; the schema drift test compares them with the bundled schema. */
    static final List<String> BODY_FIELDS = List.of("snapshotHash", "templateId", "roster", "reasonCode");
    /** Ordered fields of one roster entry. */
    static final List<String> ROSTER_FIELDS = List.of("participantId", "zoneId");
    static final Set<String> STATUSES = Set.of("proposed", "cannot-complete");
    /** Reasons of a cannot-complete result. */
    static final List<String> CANNOT_REASONS = List.of("no-fitting-roster", "missing-information");
    /** Most roster entries: one fight's non-player participants. */
    static final int MAX_ROSTER = CombatProfile.PARTICIPANTS_MAX;
    /** Longest template ID: a pack ID, {@code #} and a local ID. */
    static final int MAX_TEMPLATE_ID = 257;
    /** Longest participant or zone ID. */
    static final int MAX_ID = 128;
    /** Length of a snapshot hash. */
    static final int HASH_LENGTH = 64;

    static final String TASK_INSTRUCTION = """
            Task: encounter proposal. Compose one candidate encounter for the party described by CONTEXT, within the \
            supplied template, roster, spatial, capability and threat constraints. Choose exactly one templateId from \
            CONTEXT.options and copy CONTEXT.snapshotHash into snapshotHash. In roster, list one or more of that option's \
            hostiles by participantId, each at most once, each with one zoneId from that option's startZones. The sum of \
            the listed hostiles' cost must lie between CONTEXT.threatMinimum and CONTEXT.threatMaximum inclusive, the \
            roster holds at most the option's hostileLimit entries, and no zone may start with more occupants than its \
            capacity (0 means unbounded; the player and the allies already occupy their zones). Do not invent \
            participants, statistics, costs, levels, zones, allies, objectives, rewards or outcomes, do not change the \
            party, and do not describe the encounter. reasonCode is null for a proposal. If no roster fits these rules, \
            return cannot-complete with reasonCode no-fitting-roster, or missing-information when the context is \
            insufficient, with snapshotHash and templateId null and an empty roster.""";

    private final String schema = ContractEnvelope.resource(SCHEMA_RESOURCE);
    private final String templateHash = ContractEnvelope.sha256(ContractEnvelope.SHARED_INSTRUCTION + "\n"
            + TASK_INSTRUCTION + "\n" + schema);

    /** Load the bundled schema; fails at construction when it is missing from the package. */
    public EncounterContract() {
    }

    /** @return {@link TaskKind#ENCOUNTER} */
    @Override
    public TaskKind kind() {
        return TaskKind.ENCOUNTER;
    }

    /** @return the bundled encounter schema text */
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
    public RenderedTask render(EncounterContext context, List<ContractViolation> repair) {
        return ContractEnvelope.render(kind(), context, TASK_INSTRUCTION, data(context), repair);
    }

    /**
     * Ordered context tree: the snapshot hash, the budget and the options in the caller's order, so equal contexts digest
     * equally. The party's individual capabilities are not rendered; the budget already summarizes them.
     */
    static Map<String, Object> data(EncounterContext context) {
        var budget = context.budget();
        var data = new LinkedHashMap<String, Object>();
        data.put("snapshotHash", context.snapshot().hash());
        data.put("heuristic", ThreatBudget.HEURISTIC);
        data.put("difficulty", budget.difficulty().wire());
        data.put("partySize", budget.partySize());
        data.put("capacity", budget.capacity());
        data.put("threatMinimum", budget.minimum());
        data.put("threatMaximum", budget.maximum());
        data.put("options", context.options().stream().map(option -> optionView(option, budget)).toList());
        data.put("reasonCodes", CANNOT_REASONS);
        return data;
    }

    /** @return the ordered view of one option: what a composition may choose and what it must respect */
    private static Map<String, Object> optionView(EncounterOption option, ThreatBudget budget) {
        var template = option.template();
        var view = new LinkedHashMap<String, Object>();
        view.put("templateId", template.id());
        view.put("level", option.level());
        view.put("hostileLimit", option.hostileLimit(budget));
        view.put("playerZone", template.playerZone());
        view.put("escapeZones", List.copyOf(template.escapeZones()));
        view.put("startZones", List.copyOf(option.startZones()));
        view.put("zones", template.zones().zones().values().stream().map(zone -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("zoneId", zone.id());
            entry.put("capacity", zone.capacity());
            entry.put("cover", zone.cover().wire());
            entry.put("obscured", zone.obscured());
            return entry;
        }).toList());
        view.put("edges", template.zones().edges().stream().map(edge -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("from", edge.from());
            entry.put("to", edge.to());
            entry.put("blocked", edge.blocked());
            entry.put("difficult", edge.difficult());
            return entry;
        }).toList());
        view.put("hostiles", option.hostiles().stream().map(hostile -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("participantId", hostile.id());
            entry.put("threat", hostile.threat().wire());
            entry.put("cost", ThreatBudget.cost(hostile.threat(), option.level()));
            entry.put("authoredZone", hostile.zone());
            return entry;
        }).toList());
        view.put("allies", template.participants().stream().filter(p -> p.side() == EncounterTemplate.Side.ALLY)
                .map(ally -> IntentContract.ordered("participantId", ally.id(), "zone", ally.zone())).toList());
        view.put("objectives", template.objectives().stream()
                .map(objective -> IntentContract.ordered("objectiveId", objective.id(), "kind", objective.kind().wire()))
                .toList());
        return view;
    }

    /** {@inheritDoc} */
    @Override
    public Validation<EncounterProposal> validate(EncounterContext context, String output) {
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
        EncounterProposal proposal;
        if (status.equals("cannot-complete")) {
            IntentContract.requireEmpty(reader, body, "snapshotHash");
            IntentContract.requireEmpty(reader, body, "templateId");
            if (!(body.get("roster") instanceof List<?> roster)) {
                reader.structural("unexpected-type", "/body/roster");
            } else if (!roster.isEmpty()) {
                reader.semantic("status-field-conflict", "/body/roster");
            }
            String reason = reader.choice(body, "reasonCode", "/body", Set.copyOf(CANNOT_REASONS), false);
            proposal = reason == null ? null : new EncounterProposal.CannotCompose(reason);
        } else {
            proposal = composed(context, reader, body, violations);
        }
        return violations.isEmpty() && proposal != null ? new Validation<>(proposal, violations, warnings)
                : Validation.rejected(violations.isEmpty()
                        ? List.of(ContractViolation.structural("unexpected-type", "/body")) : violations, warnings);
    }

    /**
     * Validate a proposed composition against the chosen option and the snapshot's budget and compose it.
     *
     * @return the composition, or null after recording at least one violation
     */
    private static EncounterProposal composed(EncounterContext context, StrictJson reader, Map<String, Object> body,
            List<ContractViolation> violations) {
        String hash = reader.text(body, "snapshotHash", "/body", HASH_LENGTH, false);
        String templateId = reader.text(body, "templateId", "/body", MAX_TEMPLATE_ID, false);
        List<Object> entries = reader.array(body, "roster", "/body", 1, MAX_ROSTER);
        if (body.get("reasonCode") != null) {
            reader.semantic("status-field-conflict", "/body/reasonCode");
        }
        String snapshotHash = context.snapshot().hash();
        if (hash != null && !hash.equals(snapshotHash)) {
            reader.structural("echo-mismatch", "/body/snapshotHash");
        }
        List<EncounterOption.Placement> placements = entries == null ? null : placements(reader, entries);
        if (templateId == null || placements == null) {
            return null;
        }
        EncounterOption option = context.option(templateId);
        if (option == null) {
            reader.semantic("unknown-template", "/body/templateId");
            return null;
        }
        if (!placeable(option, reader, placements)) {
            return null;
        }
        var budget = context.budget();
        if (!option.overfilled(placements).isEmpty()) {
            reader.semantic("zone-capacity", "/body/roster");
        }
        if (placements.size() > option.hostileLimit(budget)) {
            reader.semantic("too-many-hostiles", "/body/roster");
        }
        ThreatBudget.Verdict verdict = budget.check(option.entries(placements));
        var codes = new LinkedHashSet<String>();
        for (ThreatBudget.Finding finding : verdict.findings()) {
            // The option's own limit is never above the budget's and has been reported already.
            if (finding.violation() != ThreatBudget.Violation.TOO_MANY_HOSTILES) {
                codes.add(finding.violation().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            }
        }
        codes.forEach(code -> reader.semantic(code, "/body/roster"));
        if (!violations.isEmpty()) {
            return null;
        }
        EncounterTemplate encounter;
        try {
            encounter = option.compose(placements);
        } catch (IllegalArgumentException refused) {
            // Every rule compose enforces is checked above; this keeps the validator total if the template adds one.
            reader.semantic("template-invalid", "/body");
            return null;
        }
        return new EncounterProposal.Composed(snapshotHash, option, placements, encounter, verdict);
    }

    /** Read the roster entries; null after recording a structural violation in any of them. */
    private static List<EncounterOption.Placement> placements(StrictJson reader, List<Object> entries) {
        var placements = new ArrayList<EncounterOption.Placement>();
        boolean complete = true;
        for (int index = 0; index < entries.size(); index++) {
            String at = "/body/roster/" + index;
            Map<String, Object> entry = reader.object(entries.get(index), at, Set.copyOf(ROSTER_FIELDS));
            if (entry == null) {
                complete = false;
                continue;
            }
            String participant = reader.text(entry, "participantId", at, MAX_ID, false);
            String zone = reader.text(entry, "zoneId", at, MAX_ID, false);
            if (participant == null || zone == null) {
                complete = false;
            } else {
                placements.add(new EncounterOption.Placement(participant, zone));
            }
        }
        return complete ? placements : null;
    }

    /** Check each entry names a distinct hostile participant of the option in a starting zone; false after recording a code. */
    private static boolean placeable(EncounterOption option, StrictJson reader, List<EncounterOption.Placement> placements) {
        var allowed = option.startZones();
        var seen = new HashSet<String>();
        boolean placeable = true;
        for (int index = 0; index < placements.size(); index++) {
            var placement = placements.get(index);
            String at = "/body/roster/" + index;
            var participant = option.template().participant(placement.participantId());
            if (participant == null || participant.side() != EncounterTemplate.Side.HOSTILE) {
                reader.semantic("unknown-participant", at + "/participantId");
                placeable = false;
            } else if (!seen.add(placement.participantId())) {
                reader.semantic("duplicate-participant", at + "/participantId");
                placeable = false;
            }
            if (!allowed.contains(placement.zone())) {
                reader.semantic("zone-not-permitted", at + "/zoneId");
                placeable = false;
            }
        }
        return placeable;
    }
}
