package org.rpgengine.inference.contract;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned bounded quest-plan contract, version 1 (docs/28 section 6, docs/21 section 13), for
 * the {@code quest-plan} and {@code quest-plan-refine} task kinds of the privileged quest planner.
 *
 * <p>Statuses: {@code planned} carries a whole plan; {@code cannot-plan} carries only the echoed
 * bindings, the locale and one fixed {@code reasonCode} with every other field empty. Either way
 * nothing is executed: a plan is a private candidate the plan-admission gate rechecks, and only an
 * ordinary canonical command can use an admitted branch.
 *
 * <p>Validation of a {@code planned} body, all of it independent of the generation schema:
 * <ol>
 *   <li><b>Bindings:</b> {@code planId}, {@code planVersion}, {@code questId} and
 *       {@code predecessorPlanId} must equal the backend-owned values ({@code binding-mismatch});
 *       {@code validationMetadata} must be null ({@code backend-field}): the backend writes it.</li>
 *   <li><b>Bounds:</b> at most {@value #MAX_NODES} nodes, {@value #MAX_BRANCHES} branches, causal
 *       depth {@value #MAX_GRAPH_DEPTH} ({@code plan-too-deep}), no causal cycle
 *       ({@code causal-cycle}), bounded text and lists, predicates within the {@link PlanPredicate}
 *       vocabulary and bounds, and a horizon within the context's.</li>
 *   <li><b>References:</b> participants are context actors, sources are context lore, content refs
 *       are pinned definitions, dependency refs are manifest ids, terminology refs are glossary ids
 *       ({@code unknown-reference}); secrets have exactly one of an established private fact or a
 *       new proposal ({@code secret-shape}, {@code secret-not-private}).</li>
 *   <li><b>Author constraints:</b> every protected constraint of the context must be cited
 *       ({@code author-constraint-omitted}); unknown ones are rejected.</li>
 *   <li><b>Agency:</b> the vocabulary cannot express a player action and no field orders one;
 *       every plan states a {@code noProgressFallback}, so ignoring the plan stays playable.</li>
 *   <li><b>Snapshot feasibility (selective):</b> root {@code requiredWorldFacts} must not be false
 *       in the snapshot ({@code root-assumption-false}); a node whose eligibility is false is
 *       dropped together with its causal descendants; a branch whose guard is false
 *       ({@code guard-false}) or that lists a dropped node ({@code missing-support}) is rejected
 *       alone; a plan with no remaining branch is {@code no-viable-branch}. A refinement may not
 *       reuse a stale branch id ({@code stale-branch-reused}).</li>
 * </ol>
 *
 * <p>The accepted {@link QuestPlanProposal.Planned} also carries the <b>citation map</b>: for each
 * manifest dependency, whether a root part (required facts, failure rules, secret prerequisites,
 * pending-event triggers, declared whole-plan reads) or which branches (guards, objectives, node
 * eligibility including causal support, declared branch assumptions) read the same reference. An
 * entity the plan mentions outside predicates (participant, lore source, established secret)
 * without any branch predicate reading it is root, so only a typed predicate can narrow a read to
 * individual branches. A read the plan never references at all has no citation: the caller stores
 * it as observed only (recorded and rechecked for audit, guarding no branch).
 */
public final class QuestPlanContract implements TaskContract<QuestPlanContext, QuestPlanProposal> {
    /** Version of the provisional validator, recorded on every admission receipt. */
    public static final String VALIDATOR_VERSION = "quest-plan-validator-v1";
    /** Preparation-job contract identifier of this contract. */
    public static final String JOB_CONTRACT = "quest-plan-v1";
    /** Classpath resource of the output schema. */
    static final String SCHEMA_RESOURCE = "/ai-contract/1/quest-plan.schema.json";
    /** Most nodes of one plan. */
    public static final int MAX_NODES = 24;
    /** Most branches (alternatives) of one plan. */
    public static final int MAX_BRANCHES = 8;
    /** Deepest causal support chain among nodes. */
    public static final int MAX_GRAPH_DEPTH = 6;
    /** Largest stored body, the artifact column bound. */
    static final int MAX_BODY_BYTES = 65_536;

    /** Ordered body fields; the schema drift test compares them with the bundled schema. */
    static final List<String> BODY_FIELDS = List.of("planId", "planVersion", "questId", "predecessorPlanId",
            "premise", "tensions", "planningHorizonSeconds", "establishedLoreRefs", "requiredWorldFacts",
            "participants", "secrets", "nodes", "branches", "failureAndAbandonment", "pendingEventProposals",
            "authorConstraintRefs", "readDependencies", "assumptions", "locale", "terminologyRefs",
            "validationMetadata", "reasonCode");
    static final List<String> PARTICIPANT_FIELDS = List.of("participantRef", "motive", "goal", "sourceRefs",
            "knownSecretRefs");
    static final List<String> SECRET_FIELDS = List.of("secretId", "establishedFactRef", "proposal", "knowerRefs",
            "disclosurePrerequisite");
    static final List<String> NODE_FIELDS = List.of("nodeId", "kind", "summary", "supportRefs", "visibility",
            "eligibility", "expiresAfterSeconds", "contentRefs");
    static final List<String> BRANCH_FIELDS = List.of("branchId", "guard", "nodeRefs", "objective",
            "outcomeCandidates");
    static final List<String> FAILURE_FIELDS = List.of("expiry", "failure", "noProgressFallback",
            "consequenceProposals");
    static final List<String> EVENT_FIELDS = List.of("proposalId", "trigger", "windowFromSeconds", "windowToSeconds",
            "causeRefs", "summary");
    static final List<String> ASSUMPTION_FIELDS = List.of("assumptionId", "branchRefs", "dependencyRefs", "note");
    static final Set<String> STATUSES = Set.of("planned", "cannot-plan");
    static final Set<String> REASONS = Set.of("insufficient-context", "constraint-conflict");
    static final Set<String> NODE_KINDS = Set.of("opportunity", "clue", "development", "subgoal");
    static final Set<String> VISIBILITIES = Set.of("hidden", "discoverable");
    static final Set<String> FALLBACKS = Set.of("baseline-objective", "receipt-only");

    static final String PLAN_INSTRUCTION = """
            Task: quest planning. Prepare one small conditional plan for the quest in CONTEXT using only \
            the supplied entities, lore, content references and author constraints. A plan is private \
            preparation, not canon: never state that an event happened, a clue was found or an ending is \
            certain. Offer optional opportunities and guarded alternative branches; never require, \
            schedule or assume a protagonist action, and keep the quest playable if the player ignores \
            every node. Write conditions only with the supplied predicate operators over supplied entity \
            refs. Cite every author constraint. Keep within the supplied limits. If the context is not \
            enough for a useful plan, return cannot-plan. Unused fields are null or empty arrays; \
            validationMetadata is always null.""";
    static final String REFINE_INSTRUCTION = """
            Task: quest plan refinement. The previous plan version's branches listed as stale in \
            CONTEXT.refinement no longer fit the world. Return one complete new version that keeps any \
            usable branches that still fit and replaces the stale ones with new branch ids that fit the \
            current entities. Follow every rule of quest planning: private preparation only, no required \
            protagonist action, only supplied predicate operators, entity refs and constraints, and \
            return cannot-plan when no useful plan fits. validationMetadata is always null.""";

    private final TaskKind kind;
    private final String instruction;
    private final String schema = ContractEnvelope.resource(SCHEMA_RESOURCE);
    private final String templateHash;

    /**
     * @param kind {@link TaskKind#QUEST_PLAN} or {@link TaskKind#QUEST_PLAN_REFINE}
     * @throws IllegalArgumentException for any other kind
     */
    public QuestPlanContract(TaskKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
        instruction = switch (kind) {
            case QUEST_PLAN -> PLAN_INSTRUCTION;
            case QUEST_PLAN_REFINE -> REFINE_INSTRUCTION;
            default -> throw new IllegalArgumentException("not a quest-plan kind");
        };
        templateHash = ContractEnvelope.sha256(ContractEnvelope.SHARED_INSTRUCTION + "\n" + instruction + "\n" + schema);
    }

    /** {@inheritDoc} */
    @Override
    public TaskKind kind() {
        return kind;
    }

    /** @return the bundled quest-plan schema text */
    @Override
    public String outputSchema() {
        return schema;
    }

    /** @return SHA-256 of the shared instruction, this kind's instruction and the schema */
    @Override
    public String templateHash() {
        return templateHash;
    }

    /** {@inheritDoc} */
    @Override
    public RenderedTask render(QuestPlanContext context, List<ContractViolation> repair) {
        requireKind(context);
        return ContractEnvelope.render(kind, context, instruction, data(context), repair);
    }

    /** Refuse a first-plan context for a refinement and the reverse; this is a caller bug. */
    private void requireKind(QuestPlanContext context) {
        if ((kind == TaskKind.QUEST_PLAN_REFINE) != (context.refinement() != null)) {
            throw new IllegalArgumentException("refinement context and task kind disagree");
        }
    }

    /** Ordered context tree; list order is the supplied order so equal contexts digest equally. */
    static Map<String, Object> data(QuestPlanContext context) {
        var data = new LinkedHashMap<String, Object>();
        var plan = new LinkedHashMap<String, Object>();
        plan.put("planId", context.planId().toString());
        plan.put("planVersion", context.planVersion());
        plan.put("predecessorPlanId", context.predecessorPlanId() == null ? null : context.predecessorPlanId().toString());
        data.put("plan", plan);
        var quest = new LinkedHashMap<String, Object>();
        quest.put("questId", context.questId());
        quest.put("title", context.questTitle());
        quest.put("objective", context.questObjective());
        data.put("quest", quest);
        data.put("worldTimeSeconds", context.worldTimeSeconds());
        data.put("maxHorizonSeconds", context.maxHorizonSeconds());
        var limits = new LinkedHashMap<String, Object>();
        limits.put("maxNodes", MAX_NODES);
        limits.put("maxBranches", MAX_BRANCHES);
        limits.put("maxCausalDepth", MAX_GRAPH_DEPTH);
        limits.put("predicateOperators", PlanPredicate.OPERATORS);
        data.put("limits", limits);
        data.put("entities", context.entities().stream().map(entity -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("ref", entity.ref());
            entry.put("label", entity.label());
            entry.put("location", entity.location());
            entry.put("amount", entity.amount());
            entry.put("status", entity.status());
            entry.put("holderRef", entity.holderRef());
            return entry;
        }).toList());
        data.put("lore", context.lore().stream().map(lore -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", lore.id());
            entry.put("text", lore.text());
            entry.put("secret", lore.secret());
            return entry;
        }).toList());
        data.put("dependencies", context.dependencies().stream().map(dependency -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", dependency.id());
            entry.put("reference", dependency.reference());
            entry.put("query", dependency.query());
            return entry;
        }).toList());
        data.put("contentRefs", context.contentRefs());
        data.put("authorConstraints", context.authorConstraints());
        if (context.refinement() == null) {
            data.put("refinement", null);
        } else {
            var refinement = new LinkedHashMap<String, Object>();
            refinement.put("staleBranches", context.refinement().staleBranches());
            refinement.put("usableBranches", context.refinement().usableBranches());
            data.put("refinement", refinement);
        }
        data.put("glossary", ContractEnvelope.glossary(context.glossary()));
        return data;
    }

    /** {@inheritDoc} */
    @Override
    public Validation<QuestPlanProposal> validate(QuestPlanContext context, String output) {
        requireKind(context);
        var violations = new ArrayList<ContractViolation>();
        var reader = new StrictJson(violations);
        String digest = ContractEnvelope.sha256(ContractEnvelope.json(data(context)));
        Map<String, Object> root = ContractEnvelope.root(reader, output, kind, context.binding(), digest, STATUSES);
        if (root == null) {
            return Validation.rejected(violations, List.of());
        }
        Map<String, Object> body = reader.object(root.get("body"), "/body", Set.copyOf(BODY_FIELDS));
        Object status = root.get("status");
        if (body == null || !(status instanceof String) || !STATUSES.contains(status)) {
            return Validation.rejected(violations, List.of());
        }
        bindings(context, reader, body);
        if (body.get("validationMetadata") != null) {
            reader.semantic("backend-field", "/body/validationMetadata");
        }
        String locale = reader.choice(body, "locale", "/body", Set.of("en", "it"), false);
        if (locale != null && !locale.equals(context.binding().outputLocale().tag())) {
            reader.semantic("locale-mismatch", "/body/locale");
        }
        QuestPlanProposal proposal = "cannot-plan".equals(status) ? cannotPlan(reader, body)
                : new Plan(context, reader, body, digest).read();
        return violations.isEmpty() && proposal != null ? new Validation<>(proposal, violations, List.of())
                : Validation.rejected(violations.isEmpty()
                        ? List.of(ContractViolation.structural("unexpected-type", "/body")) : violations, List.of());
    }

    /** Check the four backend-owned identity echoes. */
    private static void bindings(QuestPlanContext context, StrictJson reader, Map<String, Object> body) {
        UUID plan = reader.uuid(body, "planId", "/body", false);
        if (plan != null && !plan.equals(context.planId())) {
            reader.semantic("binding-mismatch", "/body/planId");
        }
        Long version = reader.integer(body, "planVersion", "/body", 1, 10_000, false);
        if (version != null && version != context.planVersion()) {
            reader.semantic("binding-mismatch", "/body/planVersion");
        }
        if (!context.questId().equals(body.get("questId"))) {
            reader.semantic("binding-mismatch", "/body/questId");
        }
        UUID predecessor = reader.uuid(body, "predecessorPlanId", "/body", true);
        if (!Objects.equals(predecessor, context.predecessorPlanId())) {
            reader.semantic("binding-mismatch", "/body/predecessorPlanId");
        }
    }

    /** Validate a cannot-plan result: one reason, every plan field empty. */
    private static QuestPlanProposal cannotPlan(StrictJson reader, Map<String, Object> body) {
        for (String field : BODY_FIELDS) {
            if (!Set.of("planId", "planVersion", "questId", "predecessorPlanId", "locale", "validationMetadata",
                    "reasonCode").contains(field)) {
                IntentContract.requireEmpty(reader, body, field);
            }
        }
        String reason = reader.choice(body, "reasonCode", "/body", REASONS, false);
        return reason == null ? null : new QuestPlanProposal.CannotPlan(reason);
    }

    /** One planned-body validation; holds the identities read so far. */
    private static final class Plan {
        private final QuestPlanContext context;
        private final StrictJson reader;
        private final Map<String, Object> body;
        private final String digest;
        private final Map<String, PlanEntity> entities = new HashMap<>();
        private final Set<String> lore = new HashSet<>();
        private final Set<String> secretLore = new HashSet<>();
        private final Set<String> dependencyIds = new HashSet<>();
        private final Set<String> secretIds = new LinkedHashSet<>();
        private final Set<String> participants = new LinkedHashSet<>();
        private final Set<String> citedEntities = new LinkedHashSet<>();
        private final Set<String> citedContent = new LinkedHashSet<>();
        // Root-level reads by entity reference and by declared dependency id.
        private final List<PlanPredicate> rootPredicates = new ArrayList<>();
        private final Set<String> rootDependencyIds = new HashSet<>();
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final List<Branch> branches = new ArrayList<>();
        private final Map<String, Set<String>> branchDependencyIds = new HashMap<>();

        Plan(QuestPlanContext context, StrictJson reader, Map<String, Object> body, String digest) {
            this.context = context;
            this.reader = reader;
            this.body = body;
            this.digest = digest;
            context.entities().forEach(entity -> entities.put(entity.ref(), entity));
            context.lore().forEach(entry -> {
                lore.add(entry.id());
                if (entry.secret()) {
                    secretLore.add(entry.id());
                }
            });
            context.dependencies().forEach(dependency -> dependencyIds.add(dependency.id()));
        }

        /** @return the accepted plan, or null after recording violations */
        QuestPlanProposal read() {
            if (body.get("reasonCode") != null) {
                reader.semantic("status-field-conflict", "/body/reasonCode");
            }
            reader.text(body, "premise", "/body", 600, false);
            texts("tensions", "/body", 4, 200);
            reader.integer(body, "planningHorizonSeconds", "/body", 1, context.maxHorizonSeconds(), false);
            loreRefs(body, "establishedLoreRefs", "/body", 16);
            List<Object> facts = reader.array(body, "requiredWorldFacts", "/body", 0, 8);
            if (facts != null) {
                for (int index = 0; index < facts.size(); index++) {
                    String at = "/body/requiredWorldFacts/" + index;
                    PlanPredicate fact = predicate(facts.get(index), at, false);
                    if (fact != null) {
                        rootPredicates.add(fact);
                        if (fact.evaluate(facts()) == PlanPredicate.Truth.FALSE) {
                            reader.semantic("root-assumption-false", at);
                        }
                    }
                }
            }
            participants();
            secrets();
            nodes();
            branches();
            failure();
            events();
            List<String> constraints = authorConstraints();
            List<String> reads = reader.references(body, "readDependencies", "/body", 64);
            if (reads != null) {
                for (int index = 0; index < reads.size(); index++) {
                    if (!dependencyIds.contains(reads.get(index))) {
                        reader.semantic("unknown-reference", "/body/readDependencies/" + index);
                    }
                }
                rootDependencyIds.addAll(reads);
            }
            assumptions();
            List<String> terms = reader.references(body, "terminologyRefs", "/body", 32);
            if (terms != null) {
                for (int index = 0; index < terms.size(); index++) {
                    String term = terms.get(index);
                    if (context.glossary().stream().noneMatch(entry -> entry.termId().equals(term))) {
                        reader.semantic("unknown-reference", "/body/terminologyRefs/" + index);
                    }
                }
            }
            if (constraints == null || branches.isEmpty()) {
                return null;
            }
            return select(constraints);
        }

        /** Participants: distinct context actors with bounded motives and known sources. */
        private void participants() {
            List<Object> entries = reader.array(body, "participants", "/body", 0, 8);
            if (entries == null) {
                return;
            }
            var pendingSecrets = new ArrayList<Map.Entry<String, List<String>>>();
            for (int index = 0; index < entries.size(); index++) {
                String at = "/body/participants/" + index;
                Map<String, Object> entry = reader.object(entries.get(index), at, Set.copyOf(PARTICIPANT_FIELDS));
                if (entry == null) {
                    continue;
                }
                Object ref = entry.get("participantRef");
                PlanEntity entity = ref instanceof String text ? entities.get(text) : null;
                if (entity == null) {
                    reader.semantic("unknown-reference", at + "/participantRef");
                } else if (!entity.kind().equals("actor")) {
                    reader.semantic("predicate-reference-kind", at + "/participantRef");
                } else if (!participants.add(entity.ref())) {
                    reader.structural("duplicate-reference", at + "/participantRef");
                } else {
                    citedEntities.add(entity.ref());
                }
                reader.text(entry, "motive", at, 300, false);
                reader.text(entry, "goal", at, 300, false);
                loreRefs(entry, "sourceRefs", at, 4);
                List<String> known = reader.references(entry, "knownSecretRefs", at, 8);
                if (known != null) {
                    pendingSecrets.add(Map.entry(at, known));
                }
            }
            // Secret ids are read after participants; check knowledge scopes once both exist.
            deferredKnowledge = pendingSecrets;
        }

        private List<Map.Entry<String, List<String>>> deferredKnowledge = List.of();

        /** Secrets: established private facts or new proposals, with knowers among participants. */
        private void secrets() {
            List<Object> entries = reader.array(body, "secrets", "/body", 0, 8);
            if (entries != null) {
                for (int index = 0; index < entries.size(); index++) {
                    String at = "/body/secrets/" + index;
                    Map<String, Object> entry = reader.object(entries.get(index), at, Set.copyOf(SECRET_FIELDS));
                    if (entry == null) {
                        continue;
                    }
                    localId(entry, "secretId", at, secretIds);
                    String established = reader.text(entry, "establishedFactRef", at, 128, true);
                    String proposal = reader.text(entry, "proposal", at, 300, true);
                    if ((established == null) == (proposal == null)) {
                        reader.semantic("secret-shape", at);
                    }
                    if (established != null) {
                        if (!lore.contains(established)) {
                            reader.semantic("unknown-reference", at + "/establishedFactRef");
                        } else if (!secretLore.contains(established)) {
                            reader.semantic("secret-not-private", at + "/establishedFactRef");
                        } else {
                            citeSource(established);
                        }
                    }
                    List<String> knowers = reader.references(entry, "knowerRefs", at, 8);
                    if (knowers != null) {
                        for (int knower = 0; knower < knowers.size(); knower++) {
                            if (!participants.contains(knowers.get(knower))) {
                                reader.semantic("unknown-reference", at + "/knowerRefs/" + knower);
                            }
                        }
                    }
                    PlanPredicate prerequisite = predicate(entry.get("disclosurePrerequisite"),
                            at + "/disclosurePrerequisite", true);
                    if (prerequisite != null) {
                        rootPredicates.add(prerequisite);
                    }
                }
            }
            for (var known : deferredKnowledge) {
                for (int index = 0; index < known.getValue().size(); index++) {
                    if (!secretIds.contains(known.getValue().get(index))) {
                        reader.semantic("unknown-reference", known.getKey() + "/knownSecretRefs/" + index);
                    }
                }
            }
        }

        /** Nodes: bounded optional opportunities with acyclic causal support of bounded depth. */
        private void nodes() {
            List<Object> entries = reader.array(body, "nodes", "/body", 1, MAX_NODES);
            if (entries == null) {
                return;
            }
            var ids = new LinkedHashSet<String>();
            var raw = new ArrayList<Map.Entry<String, Map<String, Object>>>();
            for (int index = 0; index < entries.size(); index++) {
                String at = "/body/nodes/" + index;
                Map<String, Object> entry = reader.object(entries.get(index), at, Set.copyOf(NODE_FIELDS));
                if (entry != null && localId(entry, "nodeId", at, ids) != null) {
                    raw.add(Map.entry(at, entry));
                }
            }
            for (var item : raw) {
                String at = item.getKey();
                Map<String, Object> entry = item.getValue();
                reader.choice(entry, "kind", at, NODE_KINDS, false);
                reader.text(entry, "summary", at, 300, false);
                reader.choice(entry, "visibility", at, VISIBILITIES, false);
                reader.integer(entry, "expiresAfterSeconds", at, 1, context.maxHorizonSeconds(), true);
                List<String> support = reader.references(entry, "supportRefs", at, 6);
                var supportNodes = new ArrayList<String>();
                if (support != null) {
                    for (int index = 0; index < support.size(); index++) {
                        String ref = support.get(index);
                        if (ids.contains(ref)) {
                            supportNodes.add(ref);
                        } else if (lore.contains(ref)) {
                            citeSource(ref);
                        } else if (!secretIds.contains(ref)) {
                            reader.semantic("unknown-reference", at + "/supportRefs/" + index);
                        }
                    }
                }
                contentRefs(entry, "contentRefs", at, 4);
                PlanPredicate eligibility = predicate(entry.get("eligibility"), at + "/eligibility", true);
                String id = (String) entry.get("nodeId");
                nodes.put(id, new Node(id, supportNodes, eligibility));
            }
            graph();
        }

        /** Reject cycles and chains deeper than the bound; iterative so depth never recurses. */
        private void graph() {
            var depth = new HashMap<String, Integer>();
            var remaining = new LinkedHashMap<String, Set<String>>();
            nodes.values().forEach(node -> remaining.put(node.id(), new HashSet<>(node.support())));
            var ready = new ArrayDeque<String>();
            remaining.forEach((id, support) -> {
                if (support.isEmpty()) {
                    ready.add(id);
                }
            });
            int ordered = 0;
            while (!ready.isEmpty()) {
                String id = ready.poll();
                ordered++;
                int own = 1 + nodes.get(id).support().stream().mapToInt(ref -> depth.getOrDefault(ref, 0)).max().orElse(0);
                depth.put(id, own);
                for (Node node : nodes.values()) {
                    Set<String> waiting = remaining.get(node.id());
                    if (waiting.remove(id) && waiting.isEmpty()) {
                        ready.add(node.id());
                    }
                }
            }
            if (ordered < nodes.size()) {
                reader.semantic("causal-cycle", "/body/nodes");
            } else if (depth.values().stream().anyMatch(value -> value > MAX_GRAPH_DEPTH)) {
                reader.semantic("plan-too-deep", "/body/nodes");
            }
        }

        /** Branches: guarded alternatives over known nodes; nothing here is mandatory. */
        private void branches() {
            List<Object> entries = reader.array(body, "branches", "/body", 1, MAX_BRANCHES);
            if (entries == null) {
                return;
            }
            var ids = new LinkedHashSet<String>();
            Set<String> stale = context.refinement() == null ? Set.of() : Set.copyOf(context.refinement().staleBranches());
            for (int index = 0; index < entries.size(); index++) {
                String at = "/body/branches/" + index;
                Map<String, Object> entry = reader.object(entries.get(index), at, Set.copyOf(BRANCH_FIELDS));
                if (entry == null) {
                    continue;
                }
                String id = localId(entry, "branchId", at, ids);
                if (id != null && stale.contains(id)) {
                    reader.semantic("stale-branch-reused", at + "/branchId");
                }
                PlanPredicate guard = predicate(entry.get("guard"), at + "/guard", true);
                PlanPredicate objective = predicate(entry.get("objective"), at + "/objective", true);
                List<String> listed = reader.references(entry, "nodeRefs", at, 8);
                if (listed != null && listed.isEmpty()) {
                    reader.structural("out-of-bounds", at + "/nodeRefs");
                    listed = null;
                }
                if (listed != null) {
                    for (int node = 0; node < listed.size(); node++) {
                        if (!nodes.containsKey(listed.get(node))) {
                            reader.semantic("unknown-reference", at + "/nodeRefs/" + node);
                        }
                    }
                }
                texts(entry, "outcomeCandidates", at, 3, 200);
                if (id != null && listed != null) {
                    branches.add(new Branch(id, guard, objective, listed));
                }
            }
        }

        /** Failure and abandonment: supported predicates and a mandatory no-progress fallback. */
        private void failure() {
            Map<String, Object> entry = reader.object(body.get("failureAndAbandonment"), "/body/failureAndAbandonment",
                    Set.copyOf(FAILURE_FIELDS));
            if (entry == null) {
                return;
            }
            String at = "/body/failureAndAbandonment";
            for (String field : List.of("expiry", "failure")) {
                PlanPredicate rule = predicate(entry.get(field), at + "/" + field, true);
                if (rule != null) {
                    rootPredicates.add(rule);
                }
            }
            reader.choice(entry, "noProgressFallback", at, FALLBACKS, false);
            texts(entry, "consequenceProposals", at, 3, 200);
        }

        /** Pending-event proposals: candidates for later command admission inside the horizon. */
        private void events() {
            List<Object> entries = reader.array(body, "pendingEventProposals", "/body", 0, 8);
            if (entries == null) {
                return;
            }
            var ids = new HashSet<String>();
            long now = context.worldTimeSeconds();
            long last = now + context.maxHorizonSeconds();
            for (int index = 0; index < entries.size(); index++) {
                String at = "/body/pendingEventProposals/" + index;
                Map<String, Object> entry = reader.object(entries.get(index), at, Set.copyOf(EVENT_FIELDS));
                if (entry == null) {
                    continue;
                }
                localId(entry, "proposalId", at, ids);
                PlanPredicate trigger = predicate(entry.get("trigger"), at + "/trigger", false);
                if (trigger != null) {
                    rootPredicates.add(trigger);
                }
                Long from = reader.integer(entry, "windowFromSeconds", at, 0, Long.MAX_VALUE, false);
                Long to = reader.integer(entry, "windowToSeconds", at, 0, Long.MAX_VALUE, false);
                if (from != null && to != null && (from > to || from < now || to > last)) {
                    reader.semantic("invalid-window", at);
                }
                List<String> causes = reader.references(entry, "causeRefs", at, 4);
                if (causes != null) {
                    if (causes.isEmpty()) {
                        reader.structural("out-of-bounds", at + "/causeRefs");
                    }
                    for (int cause = 0; cause < causes.size(); cause++) {
                        String ref = causes.get(cause);
                        if (lore.contains(ref)) {
                            citeSource(ref);
                        } else if (!nodes.containsKey(ref)) {
                            reader.semantic("unknown-reference", at + "/causeRefs/" + cause);
                        }
                    }
                }
                reader.text(entry, "summary", at, 300, false);
            }
        }

        /** @return cited author constraints when all are known and none is omitted, otherwise null */
        private List<String> authorConstraints() {
            List<String> cited = strings(body, "authorConstraintRefs", "/body", QuestPlanContext.MAX_CONSTRAINTS);
            if (cited == null) {
                return null;
            }
            boolean valid = true;
            for (int index = 0; index < cited.size(); index++) {
                if (!context.authorConstraints().contains(cited.get(index))) {
                    reader.semantic("unknown-reference", "/body/authorConstraintRefs/" + index);
                    valid = false;
                }
            }
            if (!cited.containsAll(context.authorConstraints())) {
                reader.semantic("author-constraint-omitted", "/body/authorConstraintRefs");
                valid = false;
            }
            return valid ? cited : null;
        }

        /** Declared assumptions add citations; they can never remove an engine-recorded read. */
        private void assumptions() {
            List<Object> entries = reader.array(body, "assumptions", "/body", 0, 8);
            if (entries == null) {
                return;
            }
            var ids = new HashSet<String>();
            Set<String> branchIds = new HashSet<>();
            branches.forEach(branch -> branchIds.add(branch.id()));
            for (int index = 0; index < entries.size(); index++) {
                String at = "/body/assumptions/" + index;
                Map<String, Object> entry = reader.object(entries.get(index), at, Set.copyOf(ASSUMPTION_FIELDS));
                if (entry == null) {
                    continue;
                }
                localId(entry, "assumptionId", at, ids);
                reader.text(entry, "note", at, 300, false);
                List<String> scoped = reader.references(entry, "branchRefs", at, 8);
                List<String> reads = reader.references(entry, "dependencyRefs", at, 8);
                if (scoped == null || reads == null) {
                    continue;
                }
                if (reads.isEmpty()) {
                    reader.structural("out-of-bounds", at + "/dependencyRefs");
                }
                for (int read = 0; read < reads.size(); read++) {
                    if (!dependencyIds.contains(reads.get(read))) {
                        reader.semantic("unknown-reference", at + "/dependencyRefs/" + read);
                    }
                }
                for (int branch = 0; branch < scoped.size(); branch++) {
                    if (!branchIds.contains(scoped.get(branch))) {
                        reader.semantic("unknown-reference", at + "/branchRefs/" + branch);
                    }
                }
                if (scoped.isEmpty()) {
                    rootDependencyIds.addAll(reads);
                } else {
                    scoped.forEach(branch -> branchDependencyIds.computeIfAbsent(branch, key -> new HashSet<>())
                            .addAll(reads));
                }
            }
        }

        /**
         * Selective snapshot validation and the citation map. Nodes whose eligibility is false
         * drop with every node they support (transitively); branches with a false guard or a
         * dropped node are rejected individually; at least one branch must remain.
         */
        private QuestPlanProposal select(List<String> constraints) {
            var dropped = new HashSet<String>();
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Node node : nodes.values()) {
                    if (dropped.contains(node.id())) {
                        continue;
                    }
                    boolean ineligible = node.eligibility() != null
                            && node.eligibility().evaluate(facts()) == PlanPredicate.Truth.FALSE;
                    if (ineligible || node.support().stream().anyMatch(dropped::contains)) {
                        dropped.add(node.id());
                        changed = true;
                    }
                }
            }
            var accepted = new ArrayList<String>();
            var rejected = new LinkedHashMap<String, String>();
            for (Branch branch : branches) {
                if (branch.guard() != null && branch.guard().evaluate(facts()) == PlanPredicate.Truth.FALSE) {
                    rejected.put(branch.id(), "guard-false");
                } else if (branch.nodes().stream().anyMatch(dropped::contains)) {
                    rejected.put(branch.id(), "missing-support");
                } else {
                    accepted.add(branch.id());
                }
            }
            if (accepted.isEmpty()) {
                reader.semantic("no-viable-branch", "/body/branches");
                return null;
            }
            // Mentions (participants, sources, established secrets) are fixed before predicate
            // references join the cited set, so the citation map can tell the two apart.
            Map<String, QuestPlanProposal.Citation> citations = citations(Set.copyOf(citedEntities));
            var stored = new LinkedHashMap<>(body);
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("contractVersion", JOB_CONTRACT);
            metadata.put("validatorVersion", VALIDATOR_VERSION);
            metadata.put("contextDigest", digest);
            metadata.put("acceptedBranches", accepted);
            metadata.put("rejectedBranches", rejected.entrySet().stream().map(entry -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("branchId", entry.getKey());
                item.put("reason", entry.getValue());
                return item;
            }).toList());
            stored.put("validationMetadata", metadata);
            String json = ContractEnvelope.json(stored);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
                reader.semantic("plan-too-large", "/body");
                return null;
            }
            for (PlanPredicate predicate : allPredicates()) {
                predicate.references(citedEntities);
            }
            return new QuestPlanProposal.Planned(json, accepted, rejected, citations, List.copyOf(citedEntities),
                    List.copyOf(citedContent), constraints);
        }

        /**
         * Map each manifest entry to the root and branch parts that read its reference. A read
         * that only prose-bearing fields mention (a participant, a lore source, an established
         * secret) and no branch predicate narrows counts as root; a read the plan never
         * references has no citation at all.
         *
         * @param mentions entity references cited outside predicates
         */
        private Map<String, QuestPlanProposal.Citation> citations(Set<String> mentions) {
            Set<String> rootRefs = PlanPredicate.referencesOf(rootPredicates);
            var branchRefs = new HashMap<String, Set<String>>();
            for (Branch branch : branches) {
                var predicates = new ArrayList<PlanPredicate>();
                predicates.add(branch.guard());
                predicates.add(branch.objective());
                for (String node : closure(branch.nodes())) {
                    predicates.add(nodes.get(node).eligibility());
                }
                branchRefs.put(branch.id(), PlanPredicate.referencesOf(predicates));
            }
            var citations = new LinkedHashMap<String, QuestPlanProposal.Citation>();
            for (QuestPlanContext.Dependency dependency : context.dependencies()) {
                boolean root = rootRefs.contains(dependency.reference()) || rootDependencyIds.contains(dependency.id());
                var cited = new LinkedHashSet<String>();
                for (Branch branch : branches) {
                    if (branchRefs.get(branch.id()).contains(dependency.reference())
                            || branchDependencyIds.getOrDefault(branch.id(), Set.of()).contains(dependency.id())) {
                        cited.add(branch.id());
                    }
                }
                root |= cited.isEmpty() && mentions.contains(dependency.reference());
                if (root || !cited.isEmpty()) {
                    citations.put(dependency.id(), new QuestPlanProposal.Citation(root, cited));
                }
            }
            return citations;
        }

        /** @return the listed nodes plus every node supporting them, transitively */
        private Set<String> closure(List<String> listed) {
            var seen = new LinkedHashSet<String>();
            var work = new ArrayDeque<>(listed);
            while (!work.isEmpty()) {
                String id = work.poll();
                Node node = nodes.get(id);
                if (node != null && seen.add(id)) {
                    work.addAll(node.support());
                }
            }
            return seen;
        }

        /** @return every predicate of the plan, for reference collection */
        private List<PlanPredicate> allPredicates() {
            var all = new ArrayList<>(rootPredicates);
            nodes.values().forEach(node -> all.add(node.eligibility()));
            branches.forEach(branch -> {
                all.add(branch.guard());
                all.add(branch.objective());
            });
            all.removeIf(Objects::isNull);
            return all;
        }

        /** Snapshot facts view over the context. */
        private PlanPredicate.Facts facts() {
            return new PlanPredicate.Facts() {
                @Override
                public PlanEntity entity(String ref) {
                    return entities.get(ref);
                }

                @Override
                public long worldTimeSeconds() {
                    return context.worldTimeSeconds();
                }
            };
        }

        /** Read a predicate; null JSON is allowed only when {@code nullable}. */
        private PlanPredicate predicate(Object value, String path, boolean nullable) {
            if (value == null) {
                if (!nullable) {
                    reader.structural("unexpected-type", path);
                }
                return null;
            }
            return PlanPredicate.read(reader, value, path, entities);
        }

        /** Read references that must all be context lore; entity-shaped lore counts as cited. */
        private void loreRefs(Map<String, Object> object, String key, String path, int max) {
            List<String> refs = reader.references(object, key, path, max);
            if (refs == null) {
                return;
            }
            for (int index = 0; index < refs.size(); index++) {
                if (!lore.contains(refs.get(index))) {
                    reader.semantic("unknown-reference", path + "/" + key + "/" + index);
                } else {
                    citeSource(refs.get(index));
                }
            }
        }

        /** Record a cited lore source as an entity or content reference. */
        private void citeSource(String ref) {
            if (entities.containsKey(ref)) {
                citedEntities.add(ref);
            } else if (context.contentRefs().contains(ref)) {
                citedContent.add(ref);
            }
        }

        /** Read pinned content references; they may contain {@code #}, so they are read as strings. */
        private void contentRefs(Map<String, Object> object, String key, String path, int max) {
            List<String> refs = strings(object, key, path, max);
            if (refs == null) {
                return;
            }
            for (int index = 0; index < refs.size(); index++) {
                if (!context.contentRefs().contains(refs.get(index))) {
                    reader.semantic("unknown-reference", path + "/" + key + "/" + index);
                } else {
                    citedContent.add(refs.get(index));
                }
            }
        }

        /** @return distinct bounded strings, or null after recording a violation */
        private List<String> strings(Map<String, Object> object, String key, String path, int max) {
            List<Object> values = reader.array(object, key, path, 0, max);
            if (values == null) {
                return null;
            }
            var result = new ArrayList<String>();
            for (int index = 0; index < values.size(); index++) {
                String at = path + "/" + key + "/" + index;
                if (!(values.get(index) instanceof String text) || text.isEmpty() || text.length() > 256) {
                    reader.structural("unexpected-type", at);
                    return null;
                }
                if (result.contains(text)) {
                    reader.structural("duplicate-reference", at);
                    return null;
                }
                result.add(text);
            }
            return result;
        }

        /** Read an array of bounded prose strings. */
        private void texts(String key, String path, int max, int length) {
            texts(body, key, path, max, length);
        }

        /** Read an array of bounded prose strings of {@code object}. */
        private void texts(Map<String, Object> object, String key, String path, int max, int length) {
            List<Object> values = reader.array(object, key, path, 0, max);
            if (values == null) {
                return;
            }
            for (int index = 0; index < values.size(); index++) {
                reader.element(values.get(index), path + "/" + key + "/" + index, length);
            }
        }

        /** Read a proposal-local id that must be unique within {@code seen}; returns it or null. */
        private String localId(Map<String, Object> object, String key, String path, Set<String> seen) {
            String id = reader.text(object, key, path, 32, false);
            if (id == null) {
                return null;
            }
            if (!IntentContract.LOCAL_ID.matcher(id).matches() || !seen.add(id)) {
                reader.structural("duplicate-reference", path + "/" + key);
                return null;
            }
            return id;
        }
    }

    /** One validated node. */
    private record Node(String id, List<String> support, PlanPredicate eligibility) {
    }

    /** One validated branch. */
    private record Branch(String id, PlanPredicate guard, PlanPredicate objective, List<String> nodes) {
    }
}
