package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.contract.QuestPlanFixtures.all;
import static org.rpgengine.inference.contract.QuestPlanFixtures.at;
import static org.rpgengine.inference.contract.QuestPlanFixtures.atLeast;
import static org.rpgengine.inference.contract.QuestPlanFixtures.branch;
import static org.rpgengine.inference.contract.QuestPlanFixtures.item;
import static org.rpgengine.inference.contract.QuestPlanFixtures.node;
import static org.rpgengine.inference.contract.QuestPlanFixtures.predicate;
import static org.rpgengine.inference.contract.QuestPlanFixtures.quest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.presentation.SupportedLocale;
import tools.jackson.databind.JsonNode;

/**
 * Deterministic proof of the versioned quest-plan contract (P08.7, docs/28 section 6): bilingual
 * acceptance with backend-written metadata, selective snapshot validation (an infeasible branch
 * is rejected alone, a dropped node takes its causal descendants), the citation map that lets
 * admission narrow reads to branches, and an invalid-output corpus where each case changes one
 * aspect of the canonical plan and must fail with its own fixed code.
 */
class QuestPlanContractTest {
    static final UUID PLAN = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
    static final String QUEST = "known-quest:00000000-0000-4000-8000-0000000000f1";
    static final String PLAYER = "actor:00000000-0000-4000-8000-0000000000a1";
    static final String WARDEN = "actor:00000000-0000-4000-8000-0000000000b1";
    static final String VIGOR = "resource:00000000-0000-4000-8000-0000000000b2";
    static final String KIT = "item:00000000-0000-4000-8000-0000000000a2";
    static final String FACT = "known-fact:00000000-0000-4000-8000-0000000000a3";
    static final List<String> CONSTRAINTS = List.of("kit", "trailhead");

    private final QuestPlanContract plan = new QuestPlanContract(TaskKind.QUEST_PLAN);
    private final QuestPlanContract refine = new QuestPlanContract(TaskKind.QUEST_PLAN_REFINE);

    /** Canonical context: the warden at the trailhead with two vigor, the player's kit and one fact. */
    static QuestPlanContext context(SupportedLocale locale) {
        return context(locale, "trailhead", 2, 1, null);
    }

    /** Context with an explicit witness location, vigor, version and refinement. */
    static QuestPlanContext context(SupportedLocale locale, String wardenAt, long vigor, int version,
            QuestPlanContext.Refinement refinement) {
        var entities = List.of(new PlanEntity(QUEST, "Boundary stones", null, null, "ACTIVE", PLAYER),
                new PlanEntity(PLAYER, "Player", "trailhead", null, null, null),
                new PlanEntity(WARDEN, "Warden", wardenAt, null, null, null),
                new PlanEntity(VIGOR, "vigor", null, vigor, null, WARDEN),
                new PlanEntity(KIT, "kit", null, null, null, PLAYER),
                new PlanEntity(FACT, "The stones moved overnight.", null, null, null, null));
        var references = List.of(QUEST, PLAYER, PLAYER, WARDEN, WARDEN, VIGOR, KIT, FACT);
        var dependencies = new ArrayList<QuestPlanContext.Dependency>();
        for (int index = 0; index < references.size(); index++) {
            dependencies.add(new QuestPlanContext.Dependency("dep-" + index, references.get(index),
                    index == 2 || index == 4));
        }
        return new QuestPlanContext(ContractFixtures.binding(locale), PLAN, version, version == 1 ? null : PLAN,
                QUEST, "Boundary stones", "Find who moved the boundary stones.", 3600, 604_800, entities,
                List.of(new QuestPlanContext.Lore(FACT, "The stones moved overnight.", false),
                        new QuestPlanContext.Lore("hermit", "The hermit watched the surveyor.", true)),
                dependencies, List.of("hermit", "kit", "test:dep#sense", "trailhead", "warden"), CONSTRAINTS,
                refinement, ContractFixtures.glossary(locale));
    }

    /** Canonical plan for a context. */
    static Map<String, Object> body(QuestPlanContext context) {
        return QuestPlanFixtures.plan(new QuestPlanFixtures.Refs(context.planId().toString(), context.planVersion(),
                context.predecessorPlanId() == null ? null : context.predecessorPlanId().toString(), context.questId(),
                QUEST, WARDEN, "trailhead", VIGOR, KIT, FACT, CONSTRAINTS, context.binding().outputLocale().tag(),
                context.refinement() == null ? "" : "new-"));
    }

    private Validation<QuestPlanProposal> validate(QuestPlanContract contract, QuestPlanContext context,
            Map<String, Object> body) {
        return contract.validate(context, ContractFixtures.output(contract, context, "planned", body));
    }

    @ParameterizedTest
    @EnumSource(SupportedLocale.class)
    void acceptsTheBilingualPlanAndWritesBackendMetadata(SupportedLocale locale) {
        var context = context(locale);
        var validation = validate(plan, context, body(context));
        assertThat(validation.violations()).isEmpty();
        var planned = (QuestPlanProposal.Planned) validation.value();
        assertThat(planned.acceptedBranches()).containsExactly("interview", "document");
        assertThat(planned.rejectedBranches()).isEmpty();
        assertThat(planned.authorConstraintRefs()).containsExactlyElementsOf(CONSTRAINTS);
        assertThat(planned.entityRefs()).contains(QUEST, WARDEN, VIGOR, KIT, FACT).doesNotContain(PLAYER);
        JsonNode stored = ContractFixtures.JSON.readTree(planned.bodyJson());
        JsonNode metadata = stored.path("validationMetadata");
        assertThat(metadata.path("validatorVersion").asString()).isEqualTo(QuestPlanContract.VALIDATOR_VERSION);
        assertThat(metadata.path("contractVersion").asString()).isEqualTo(QuestPlanContract.JOB_CONTRACT);
        assertThat(metadata.path("contextDigest").asString()).isEqualTo(plan.render(context, List.of()).contextDigest());
        assertThat(stored.path("premise").asString()).isEqualTo(body(context).get("premise"));
    }

    /**
     * The citation map: the quest (root assumption) and the fact (established lore) guard the
     * whole plan; the witness, its scope and its pool guard only the interview, although the
     * witness is also a participant; the kit guards only the document route; the player's own
     * row and scope are never referenced and get no citation (observed only).
     */
    @Test
    void citationsNarrowWitnessAndDocumentReadsToTheirBranches() {
        var planned = (QuestPlanProposal.Planned) validate(plan, context(SupportedLocale.EN),
                body(context(SupportedLocale.EN))).value();
        assertThat(planned.citations()).containsOnlyKeys("dep-0", "dep-3", "dep-4", "dep-5", "dep-6", "dep-7");
        assertThat(planned.citations().get("dep-0")).isEqualTo(new QuestPlanProposal.Citation(true, Set.of()));
        assertThat(planned.citations().get("dep-7").root()).isTrue();
        for (String witness : List.of("dep-3", "dep-4", "dep-5")) {
            assertThat(planned.citations().get(witness)).isEqualTo(new QuestPlanProposal.Citation(false,
                    Set.of("interview")));
        }
        assertThat(planned.citations().get("dep-6")).isEqualTo(new QuestPlanProposal.Citation(false, Set.of("document")));
    }

    @Test
    void aParticipantNoPredicateReadsIsRootAndDeclaredAssumptionsOnlyAddReliance() {
        var context = context(SupportedLocale.EN);
        var body = body(context);
        // The interview no longer reads the warden; only the participant entry mentions him.
        branches(body).set(0, branch("interview", null, List.of("talk")));
        nodes(body).set(0, node("talk", "opportunity", List.of(), atLeast(VIGOR, 1)));
        var assumption = new LinkedHashMap<String, Object>();
        assumption.put("assumptionId", "player-near");
        assumption.put("branchRefs", new ArrayList<>(List.of("document")));
        assumption.put("dependencyRefs", new ArrayList<>(List.of("dep-1")));
        assumption.put("note", "The player is near the trailhead.");
        body.put("assumptions", new ArrayList<>(List.of(assumption)));
        var planned = (QuestPlanProposal.Planned) validate(plan, context, body).value();
        assertThat(planned.citations().get("dep-3")).isEqualTo(new QuestPlanProposal.Citation(true, Set.of()));
        assertThat(planned.citations().get("dep-5")).isEqualTo(new QuestPlanProposal.Citation(false, Set.of("interview")));
        assertThat(planned.citations().get("dep-1")).isEqualTo(new QuestPlanProposal.Citation(false, Set.of("document")));
    }

    /** AP-02 at validation time: a witness elsewhere rejects only the interview; the document route stays. */
    @Test
    void selectiveValidationRejectsOnlyTheInfeasibleBranch() {
        var elsewhere = context(SupportedLocale.EN, "grove", 2, 1, null);
        var planned = (QuestPlanProposal.Planned) validate(plan, elsewhere, body(elsewhere)).value();
        assertThat(planned.acceptedBranches()).containsExactly("document");
        assertThat(planned.rejectedBranches()).containsExactly(Map.entry("interview", "guard-false"));
        assertThat(ContractFixtures.JSON.readTree(planned.bodyJson()).path("validationMetadata")
                .path("rejectedBranches").get(0).path("reason").asString()).isEqualTo("guard-false");

        // An exhausted witness makes the talk node ineligible; a node it supports drops with it.
        var tired = context(SupportedLocale.EN, "trailhead", 0, 1, null);
        var body = body(tired);
        nodes(body).add(node("confession", "development", List.of("talk"), null));
        branches(body).set(0, branch("interview", at(WARDEN, "trailhead"), List.of("confession")));
        planned = (QuestPlanProposal.Planned) validate(plan, tired, body).value();
        assertThat(planned.rejectedBranches()).containsExactly(Map.entry("interview", "missing-support"));
        assertThat(planned.acceptedBranches()).containsExactly("document");
        // The descendant's branch still cites the witness pool through the supporting node.
        assertThat(planned.citations().get("dep-5").branches()).containsExactly("interview");
    }

    @Test
    void aPlanWithoutAnyFeasibleBranchOrWithAFalseRootIsRejected() {
        var elsewhere = context(SupportedLocale.EN, "grove", 2, 1, null);
        var body = body(elsewhere);
        branches(body).remove(1);
        assertThat(ContractFixtures.codes(validate(plan, elsewhere, body))).containsExactly("no-viable-branch");

        var context = context(SupportedLocale.EN);
        body = body(context);
        body.put("requiredWorldFacts", new ArrayList<>(List.of(quest(QUEST, "COMPLETED"))));
        assertThat(ContractFixtures.codes(validate(plan, context, body))).containsExactly("root-assumption-false");
    }

    @Test
    void cannotPlanIsAcceptedOnlyWithEveryPlanFieldEmpty() {
        var context = context(SupportedLocale.IT);
        var refs = new QuestPlanFixtures.Refs(PLAN.toString(), 1, null, QUEST, QUEST, WARDEN, "trailhead", VIGOR, KIT,
                FACT, CONSTRAINTS, "it", "");
        var declined = plan.validate(context, ContractFixtures.output(plan, context, "cannot-plan",
                QuestPlanFixtures.cannotPlan(refs, "insufficient-context")));
        assertThat(declined.value()).isEqualTo(new QuestPlanProposal.CannotPlan("insufficient-context"));
        var leaky = QuestPlanFixtures.cannotPlan(refs, "constraint-conflict");
        leaky.put("premise", "Still a plan.");
        assertThat(ContractFixtures.codes(plan.validate(context, ContractFixtures.output(plan, context, "cannot-plan",
                leaky)))).containsExactly("status-field-conflict");
    }

    @Test
    void refinementMustReplaceStaleBranchesAndMatchItsKind() {
        var refinement = new QuestPlanContext.Refinement(List.of("interview"), List.of("document"));
        var context = context(SupportedLocale.EN, "trailhead", 2, 2, refinement);
        var body = body(context);
        assertThat(validate(refine, context, body).accepted()).isTrue();
        branches(body).set(0, branch("interview", at(WARDEN, "trailhead"), List.of("talk")));
        assertThat(ContractFixtures.codes(validate(refine, context, body))).containsExactly("stale-branch-reused");
        assertThatThrownBy(() -> plan.render(context, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refine.render(context(SupportedLocale.EN), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuestPlanContract(TaskKind.NARRATION)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plannerKindsArePrivilegedPreparationWorkWithTheirOwnTemplates() {
        for (TaskKind kind : List.of(TaskKind.QUEST_PLAN, TaskKind.QUEST_PLAN_REFINE)) {
            assertThat(kind.role()).isEqualTo(AiRole.QUEST_PLANNER);
            assertThat(kind.audience()).isEqualTo(Audience.PRIVILEGED);
            assertThat(kind.workClass()).isEqualTo(WorkClass.PREPARATION);
            assertThat(kind.scope(ContractFixtures.binding(SupportedLocale.EN)).audience()).isEqualTo(Audience.PRIVILEGED);
        }
        assertThat(TaskKind.INTENT.workClass()).isEqualTo(WorkClass.INTERACTIVE);
        assertThat(TaskKind.NARRATION.audience()).isEqualTo(Audience.PUBLIC);
        String instructions = plan.render(context(SupportedLocale.IT), List.of()).instructions();
        assertThat(instructions).contains("\"taskKind\":\"quest-plan\"", "\"role\":\"quest-planner\"",
                "\"audience\":\"privileged\"", "Italian (it)");
        assertThat(plan.templateHash()).matches("[0-9a-f]{64}").isNotEqualTo(refine.templateHash());
    }

    /** One mutation of the canonical plan and the single code it must produce. */
    static Stream<Arguments> invalidCorpus() {
        return Stream.of(
                corrupt("unknown operator", "unsupported-operator", body -> guard(body, predicate("sql",
                        List.of(), null, null, null, null, null))),
                corrupt("unevaluated vocabulary", "unsupported-predicate", body -> guard(body, predicate(
                        "relationship-band", List.of(), WARDEN, "friendly", null, null, null))),
                corrupt("deep predicate", "predicate-too-deep", body -> guard(body,
                        all(all(all(all(at(WARDEN, "trailhead")))))))  ,
                corrupt("too many leaves", "predicate-too-large", body -> {
                    var leaves = new ArrayList<Map<String, Object>>();
                    for (int index = 0; index < 8; index++) {
                        leaves.add(at(WARDEN, "trailhead"));
                    }
                    guard(body, predicate("all", List.of(predicate("all", leaves, null, null, null, null, null),
                            predicate("all", leaves, null, null, null, null, null), at(WARDEN, "trailhead")),
                            null, null, null, null, null));
                }),
                corrupt("wrong reference kind", "predicate-reference-kind", body -> guard(body, at(VIGOR, "trailhead"))),
                corrupt("unknown entity", "unknown-reference", body -> guard(body,
                        item("item:00000000-0000-4000-8000-000000000999"))),
                corrupt("free-text condition", "unexpected-type", body -> branches(body).get(0).put("guard",
                        "if the player feels brave")),
                corrupt("mandatory player action", "field-set", body -> branches(body).get(0).put("requiredPlayerAction",
                        "interview the warden")),
                corrupt("too many nodes", "out-of-bounds", body -> {
                    for (int index = 0; index < 23; index++) {
                        nodes(body).add(node("extra-" + index, "clue", List.of(), null));
                    }
                }),
                corrupt("too many branches", "out-of-bounds", body -> {
                    for (int index = 0; index < 7; index++) {
                        branches(body).add(branch("alt-" + index, null, List.of("ledger")));
                    }
                }),
                corrupt("causal cycle", "causal-cycle", body -> {
                    nodes(body).set(0, node("talk", "opportunity", List.of("ledger"), null));
                    nodes(body).set(1, node("ledger", "clue", List.of("talk"), null));
                }),
                corrupt("deep causal chain", "plan-too-deep", body -> {
                    String previous = "ledger";
                    for (int index = 0; index < 6; index++) {
                        nodes(body).add(node("step-" + index, "subgoal", List.of(previous), null));
                        previous = "step-" + index;
                    }
                }),
                corrupt("model-written metadata", "backend-field", body -> body.put("validationMetadata",
                        Map.of("validatorVersion", "trusted"))),
                corrupt("foreign plan identity", "binding-mismatch", body -> body.put("planId",
                        UUID.randomUUID().toString())),
                corrupt("foreign plan version", "binding-mismatch", body -> body.put("planVersion", 2)),
                corrupt("wrong locale", "locale-mismatch", body -> body.put("locale", "it")),
                corrupt("dropped author constraint", "author-constraint-omitted", body -> body.put(
                        "authorConstraintRefs", new ArrayList<>(List.of("kit")))),
                corrupt("unknown author constraint", "unknown-reference", body -> body.put("authorConstraintRefs",
                        new ArrayList<>(List.of("kit", "trailhead", "dragon")))),
                corrupt("secret with both shapes", "secret-shape", body -> secrets(body).get(0).put(
                        "establishedFactRef", "hermit")),
                corrupt("public fact as secret", "secret-not-private", body -> {
                    secrets(body).get(0).put("proposal", null);
                    secrets(body).get(0).put("establishedFactRef", FACT);
                }),
                corrupt("unknown participant", "unknown-reference", body -> participants(body).get(0).put(
                        "participantRef", "actor:00000000-0000-4000-8000-000000000999")),
                corrupt("unknown pinned content", "unknown-reference", body -> nodes(body).get(0).put("contentRefs",
                        new ArrayList<>(List.of("dragon")))),
                corrupt("unknown dependency", "unknown-reference", body -> body.put("readDependencies",
                        new ArrayList<>(List.of("dep-99")))),
                corrupt("event outside the horizon", "invalid-window", body -> {
                    var event = new LinkedHashMap<String, Object>();
                    event.put("proposalId", "storm");
                    event.put("trigger", at(WARDEN, "trailhead"));
                    event.put("windowFromSeconds", 0);
                    event.put("windowToSeconds", 10);
                    event.put("causeRefs", new ArrayList<>(List.of("talk")));
                    event.put("summary", "A storm closes the trail.");
                    body.put("pendingEventProposals", new ArrayList<>(List.of(event)));
                }),
                corrupt("horizon above the bound", "out-of-bounds", body -> body.put("planningHorizonSeconds", 604_801)),
                corrupt("markup in prose", "executable-content", body -> body.put("premise", "<script>x</script>")),
                corrupt("missing fallback", "unexpected-type", body -> ((Map<String, Object>) body.get(
                        "failureAndAbandonment")).put("noProgressFallback", null)),
                corrupt("reason on a plan", "status-field-conflict", body -> body.put("reasonCode",
                        "insufficient-context")));
    }

    private static Arguments corrupt(String name, String code, Consumer<Map<String, Object>> mutation) {
        return Arguments.of(name, code, mutation);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCorpus")
    void invalidOutputFailsWithItsOwnCode(String name, String code, Consumer<Map<String, Object>> mutation) {
        var context = context(SupportedLocale.EN);
        var body = body(context);
        mutation.accept(body);
        var validation = validate(plan, context, body);
        assertThat(validation.accepted()).as(name).isFalse();
        assertThat(ContractFixtures.codes(validation)).as(name).contains(code);
    }

    @Test
    void malformedAndForeignEnvelopesAreRejected() {
        var context = context(SupportedLocale.EN);
        assertThat(ContractFixtures.codes(plan.validate(context, "{not json"))).containsExactly("malformed-json");
        var envelope = ContractFixtures.envelope(plan, context, "planned", body(context));
        envelope.put("taskKind", "narration");
        assertThat(ContractFixtures.codes(plan.validate(context, ContractFixtures.JSON.writeValueAsString(envelope))))
                .contains("echo-mismatch");
    }

    private static void guard(Map<String, Object> body, Object guard) {
        branches(body).get(0).put("guard", guard);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> branches(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("branches");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> nodes(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("nodes");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> secrets(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("secrets");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> participants(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("participants");
    }
}
