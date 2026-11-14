package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.contract.ContractFixtures.JSON;
import static org.rpgengine.inference.contract.ContractFixtures.TASK;
import static org.rpgengine.inference.contract.ContractFixtures.binding;
import static org.rpgengine.inference.contract.ContractFixtures.codes;
import static org.rpgengine.inference.contract.ContractFixtures.envelope;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.rpgengine.combat.EncounterDifficulty;
import org.rpgengine.combat.EncounterOption;
import org.rpgengine.combat.EncounterTemplate;
import org.rpgengine.combat.PlanningFixtures;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.scheduling.SchedulerFixtures;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.presentation.SupportedLocale;

/**
 * Template-bounded encounter proposals (P13.2-A, docs/12 section 3 steps 4-7, docs/21 section 6): the privileged context holds
 * the budget and the eligible templates without statistics; a composition is accepted only inside one template and the
 * threat band; each illegal composition is rejected with one code; and a scripted runtime shows the three outcomes the planner
 * hands to entry: a valid proposal, a proposal repaired once, and the deterministic authored fallback or no encounter.
 *
 * <p>Fixture: one fresh level-1 player at Standard, so only a roster costing exactly 8 fits; options {@code ambush} (raider 8,
 * wolf 4, hound 4, brute 12; authored roster 28) and {@code patrol} (sentry 8, authored).
 */
class EncounterContractTest {
    final EncounterContract contract = new EncounterContract();
    final ContractRunnerTest.MutableClock clock = new ContractRunnerTest.MutableClock();
    final ScriptedTaskPort port = new ScriptedTaskPort();
    final ScopedSessions sessions = new ScopedSessions(port, ScopedSessions.DEFAULT_CAPACITY);
    final ContractRunner runner = new ContractRunner(SchedulerFixtures.scheduler(sessions, port, clock), clock);

    static EncounterContext context(SupportedLocale locale) {
        return context(locale, 8, PlanningFixtures.ambush(), PlanningFixtures.patrol());
    }

    /** A Standard context for a level-1 player at the given Health, offering the templates at level 1 in order. */
    static EncounterContext context(SupportedLocale locale, long health, EncounterTemplate... templates) {
        var options = new ArrayList<EncounterOption>();
        for (EncounterTemplate template : templates) {
            options.add(new EncounterOption(template, 1));
        }
        return new EncounterContext(binding(locale), PlanningFixtures.snapshot(EncounterDifficulty.STANDARD, health), options);
    }

    static Map<String, Object> place(String participant, String zone) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("participantId", participant);
        entry.put("zoneId", zone);
        return entry;
    }

    static Map<String, Object> body(String hash, String template, List<Map<String, Object>> roster, String reason) {
        var body = new LinkedHashMap<String, Object>();
        body.put("snapshotHash", hash);
        body.put("templateId", template);
        body.put("roster", new ArrayList<>(roster));
        body.put("reasonCode", reason);
        return body;
    }

    /** A proposed body for the ambush with the context's own snapshot hash. */
    static Map<String, Object> ambush(EncounterContext context, List<Map<String, Object>> roster) {
        return body(context.snapshot().hash(), "ambush", roster, null);
    }

    Validation<EncounterProposal> validate(String status, Map<String, Object> body) {
        var context = context(SupportedLocale.EN);
        return contract.validate(context, output(contract, context, status, body));
    }

    ContractOutcome<EncounterProposal> run(EncounterContext context) throws Exception {
        return runner.start(contract, context, InferenceSlot.STANDARD, TaskKind.ENCOUNTER.defaultBudget()).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** The request runs in the privileged encounter-proposer line and holds the budget and options, never statistics. */
    @Test
    void rendersTheBudgetAndTheOptionsInThePrivilegedAudience() {
        var context = context(SupportedLocale.EN);
        String instructions = contract.render(context, List.of()).instructions();
        assertThat(instructions).contains("\"role\":\"encounter-proposer\"").contains("\"audience\":\"privileged\"")
                .contains("\"taskKind\":\"encounter\"");
        var data = JSON.readTree(instructions.substring(instructions.indexOf("CONTEXT (JSON")).lines().skip(1)
                .findFirst().orElseThrow());
        assertThat(new ArrayList<>(data.propertyNames())).containsExactly("snapshotHash", "heuristic", "difficulty",
                "partySize", "capacity", "threatMinimum", "threatMaximum", "options", "reasonCodes");
        assertThat(data.path("snapshotHash").asString()).isEqualTo(context.snapshot().hash());
        assertThat(data.path("threatMinimum").asInt()).isEqualTo(8);
        assertThat(data.path("threatMaximum").asInt()).isEqualTo(8);
        var ambush = data.path("options").get(0);
        assertThat(new ArrayList<>(ambush.propertyNames())).containsExactly("templateId", "level", "hostileLimit", "playerZone",
                "escapeZones", "startZones", "zones", "edges", "hostiles", "allies", "objectives");
        assertThat(ambush.path("hostileLimit").asInt()).isEqualTo(3);
        var hostiles = new ArrayList<String>();
        ambush.path("hostiles").forEach(hostile -> {
            assertThat(new ArrayList<>(hostile.propertyNames())).containsExactly("participantId", "threat", "cost",
                    "authoredZone");
            hostiles.add(hostile.path("participantId").asString() + "=" + hostile.path("cost").asInt());
        });
        assertThat(hostiles).containsExactly("raider=8", "wolf=4", "hound=4", "brute=12");
        var starts = new ArrayList<String>();
        ambush.path("startZones").forEach(zone -> starts.add(zone.asString()));
        assertThat(starts).containsExactly("brush", "ridge", "road");
        for (String statistic : List.of("\"health\"", "\"guard\"", "\"resistance\"", "\"armor\"", "\"roles\"", "\"stats\"")) {
            assertThat(instructions).doesNotContain(statistic);
        }

        assertThat(TaskKind.ENCOUNTER.wire()).isEqualTo("encounter");
        assertThat(TaskKind.ENCOUNTER.role()).isEqualTo(AiRole.ENCOUNTER_PROPOSER);
        assertThat(TaskKind.ENCOUNTER.audience()).isEqualTo(Audience.PRIVILEGED);
        assertThat(TaskKind.ENCOUNTER.workClass()).isEqualTo(WorkClass.INTERACTIVE);
        assertThat(TaskKind.ENCOUNTER.scope(context.binding()))
                .isNotEqualTo(TaskKind.QUEST_PLAN.scope(context.binding()));
        assertThat(contract.templateHash()).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> new EncounterContext(binding(SupportedLocale.EN),
                PlanningFixtures.snapshot(EncounterDifficulty.STANDARD, 0), List.of(new EncounterOption(PlanningFixtures.patrol(), 1))))
                .as("no contributing actor").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context(SupportedLocale.EN, 8, PlanningFixtures.patrol(), PlanningFixtures.patrol()))
                .as("one template offered twice").isInstanceOf(IllegalArgumentException.class);
    }

    /** A composition inside one template and the band is accepted in each locale, and so is an explicit inability. */
    @ParameterizedTest
    @EnumSource(SupportedLocale.class)
    void acceptsACompositionInsideTheTemplateAndTheBand(SupportedLocale locale) {
        var context = context(locale);
        var single = contract.validate(context, output(contract, context, "proposed",
                ambush(context, List.of(place("raider", "ridge")))));
        assertThat(single.accepted()).as(single.violations().toString()).isTrue();
        var composed = (EncounterProposal.Composed) single.value();
        assertThat(composed.snapshotHash()).isEqualTo(context.snapshot().hash());
        assertThat(composed.option()).isEqualTo(context.option("ambush"));
        assertThat(composed.verdict().cost()).isEqualTo(8);
        assertThat(composed.encounter().participants()).extracting(EncounterTemplate.Participant::id)
                .containsExactly("guide", "raider");
        assertThat(composed.encounter().participant("raider").zone()).isEqualTo("ridge");

        var pair = contract.validate(context, output(contract, context, "proposed",
                ambush(context, List.of(place("wolf", "brush"), place("hound", "road")))));
        assertThat(pair.accepted()).as(pair.violations().toString()).isTrue();
        assertThat(((EncounterProposal.Composed) pair.value()).verdict().cost()).isEqualTo(8);

        var none = contract.validate(context, output(contract, context, "cannot-complete",
                body(null, null, List.of(), "no-fitting-roster")));
        assertThat(none.value()).isEqualTo(new EncounterProposal.CannotCompose("no-fitting-roster"));
    }

    /** Every illegal composition is rejected with the code of the one rule it breaks. */
    @Test
    void rejectsEachIllegalComposition() {
        var context = context(SupportedLocale.EN);
        assertThat(codes(validate("proposed", ambush(context, List.of(place("raider", "brush"), place("wolf", "brush"))))))
                .containsExactly("over-budget");
        assertThat(codes(validate("proposed", ambush(context, List.of(place("wolf", "brush")))))).containsExactly("under-budget");
        assertThat(codes(validate("proposed", ambush(context, List.of(place("wolf", "brush"), place("hound", "ridge"),
                place("raider", "road"), place("brute", "brush")))))).containsExactly("too-many-hostiles", "over-budget");
        assertThat(codes(validate("proposed", ambush(context, List.of(place("wolf", "ridge"), place("hound", "ridge"))))))
                .containsExactly("zone-capacity");
        assertThat(codes(validate("proposed", ambush(context, List.of(place("raider", "gate"))))))
                .as("an escape zone").containsExactly("zone-not-permitted");
        assertThat(codes(validate("proposed", ambush(context, List.of(place("raider", "moon")))))).containsExactly("zone-not-permitted");
        assertThat(codes(validate("proposed", ambush(context, List.of(place("guide", "brush"))))))
                .as("an ally").containsExactly("unknown-participant");
        assertThat(codes(validate("proposed", ambush(context, List.of(place("sentry", "brush"))))))
                .as("another template's participant").containsExactly("unknown-participant");
        assertThat(codes(validate("proposed", ambush(context, List.of(place("wolf", "brush"), place("wolf", "ridge"))))))
                .containsExactly("duplicate-participant");
        assertThat(codes(validate("proposed", body(context.snapshot().hash(), "fortress", List.of(place("raider", "ridge")),
                null)))).containsExactly("unknown-template");
        assertThat(codes(validate("proposed", body("f".repeat(64), "ambush", List.of(place("raider", "ridge")), null))))
                .containsExactly("echo-mismatch");
        assertThat(codes(validate("proposed", ambush(context, List.of())))).containsExactly("out-of-bounds");
        assertThat(codes(validate("proposed", body(context.snapshot().hash(), "ambush", List.of(place("raider", "ridge")),
                "no-fitting-roster")))).containsExactly("status-field-conflict");
        assertThat(codes(validate("cannot-complete", body(null, "ambush", List.of(), "no-fitting-roster"))))
                .containsExactly("status-field-conflict");
        assertThat(codes(validate("cannot-complete", body(null, null, List.of(place("raider", "ridge")), "missing-information"))))
                .containsExactly("status-field-conflict");
        assertThat(codes(validate("cannot-complete", body(null, null, List.of(), "too-hard")))).containsExactly("unsupported-value");

        var stronger = place("raider", "ridge");
        stronger.put("health", 1);
        assertThat(codes(validate("proposed", ambush(context, List.of(stronger))))).as("a statistic cannot ride along")
                .containsExactly("field-set");
        var cost = ambush(context, List.of(place("raider", "ridge")));
        cost.put("threatCost", 4);
        assertThat(codes(validate("proposed", cost))).containsExactly("field-set");
        var stale = envelope(contract, context, "proposed", ambush(context, List.of(place("raider", "ridge"))));
        stale.put("baseRevision", context.binding().baseRevision() + 1);
        assertThat(codes(contract.validate(context, JSON.writeValueAsString(stale)))).containsExactly("echo-mismatch");
    }

    /** Valid: a scripted runtime's first composition is accepted and handed to entry as the runtime proposed it. */
    @Test
    void aScriptedRuntimesValidCompositionIsAdmitted() throws Exception {
        var context = context(SupportedLocale.EN);
        port.completes(output(contract, context, "proposed", ambush(context, List.of(place("raider", "ridge")))));
        var outcome = (ContractOutcome.Accepted<EncounterProposal>) run(context);
        assertThat(outcome.repaired()).isFalse();
        assertThat(port.requests).hasSize(1);
        assertThat(port.requests.getFirst().sessionId())
                .isEqualTo(sessions.activeSession(TaskKind.ENCOUNTER.scope(context.binding())).orElseThrow());

        var decision = EncounterFallback.decide(outcome, context(SupportedLocale.EN));
        var proposed = (EncounterFallback.Decision.Proposed) decision;
        assertThat(proposed.composition()).isEqualTo(outcome.value());
        assertThat(proposed.composition().encounter().participants()).extracting(EncounterTemplate.Participant::id)
                .containsExactly("guide", "raider");
    }

    /** Repaired: an over-budget composition gets one repair with codes only, and the repaired composition is admitted. */
    @Test
    void aScriptedRuntimeIsRepairedOnceIntoAnAdmittedComposition() throws Exception {
        var context = context(SupportedLocale.IT);
        port.completes(output(contract, context, "proposed", ambush(context, List.of(place("raider", "brush"),
                place("wolf", "brush"))))).completes(output(contract, context, "proposed",
                ambush(context, List.of(place("wolf", "brush"), place("hound", "ridge")))));
        var outcome = (ContractOutcome.Accepted<EncounterProposal>) run(context);
        assertThat(outcome.repaired()).isTrue();
        assertThat(outcome.attempts()).extracting(ContractOutcome.Attempt::violationCodes)
                .containsExactly(List.of("over-budget"), List.of());
        var first = port.requests.get(0);
        var repair = port.requests.get(1);
        assertThat(repair.taskId()).isEqualTo(ContractRunner.repairTaskId(TASK));
        assertThat(repair.instructions()).startsWith(first.instructions()).contains("- over-budget at /body/roster");
        assertThat(repair.sessionId()).isEqualTo(first.sessionId());

        var proposed = (EncounterFallback.Decision.Proposed) EncounterFallback.decide(outcome, context);
        assertThat(proposed.composition().placements()).containsExactly(new EncounterOption.Placement("wolf", "brush"),
                new EncounterOption.Placement("hound", "ridge"));
        assertThat(proposed.composition().verdict().admitted()).isTrue();
    }

    /**
     * Fallback: output still invalid after the repair is never used; entry gets the first authored template that fits,
     * unchanged, or no encounter when none fits.
     */
    @Test
    void anOutputInvalidAfterTheRepairFallsBackToAnAuthoredTemplateOrNone() throws Exception {
        var context = context(SupportedLocale.EN);
        port.completes(output(contract, context, "proposed", ambush(context, List.of(place("guide", "brush")))))
                .completes(output(contract, context, "proposed", ambush(context, List.of(place("raider", "gate")))));
        var outcome = run(context);
        assertThat(outcome).isInstanceOf(ContractOutcome.Rejected.class);
        assertThat(outcome.attempts()).extracting(ContractOutcome.Attempt::violationCodes)
                .containsExactly(List.of("unknown-participant"), List.of("zone-not-permitted"));
        assertThat(port.requests).hasSize(2);

        var fallback = (EncounterFallback.Decision.Fallback) EncounterFallback.decide(outcome, context);
        assertThat(fallback.cause()).isEqualTo(EncounterFallback.Cause.PROPOSAL_INVALID);
        assertThat(fallback.detail()).isEqualTo("zone-not-permitted");
        assertThat(fallback.authored().encounter()).isEqualTo(PlanningFixtures.patrol());
        assertThat(fallback.authored().verdict().cost()).isEqualTo(8);

        var onlyAmbush = context(SupportedLocale.EN, 8, PlanningFixtures.ambush());
        assertThat(EncounterFallback.decide(outcome, onlyAmbush))
                .isEqualTo(new EncounterFallback.Decision.NoEncounter(EncounterFallback.Cause.PROPOSAL_INVALID,
                        "zone-not-permitted"));
    }

    /** A task the port did not complete is never repaired and falls back as unavailable, or interrupted when cancelled. */
    @Test
    void anUncompletedTaskFallsBackWithoutARepair() throws Exception {
        for (TaskResult.Status status : List.of(TaskResult.Status.UNAVAILABLE, TaskResult.Status.REJECTED,
                TaskResult.Status.FAILED, TaskResult.Status.TIMED_OUT, TaskResult.Status.UNRESOLVED,
                TaskResult.Status.CANCELLED)) {
            var scripted = new ScriptedTaskPort().ends(status, "fixture-code");
            var isolated = new ContractRunner(SchedulerFixtures.scheduler(new ScopedSessions(scripted, 1), scripted, clock),
                    clock);
            var context = context(SupportedLocale.EN);
            var outcome = isolated.start(contract, context, InferenceSlot.STANDARD, TaskKind.ENCOUNTER.defaultBudget())
                    .outcome().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(scripted.requests).as(status.name()).hasSize(1);

            var fallback = (EncounterFallback.Decision.Fallback) EncounterFallback.decide(outcome, context);
            assertThat(fallback.cause()).as(status.name()).isEqualTo(status == TaskResult.Status.CANCELLED
                    ? EncounterFallback.Cause.INTERRUPTED : EncounterFallback.Cause.PROVIDER_UNAVAILABLE);
            assertThat(fallback.detail()).isEqualTo("fixture-code");
            assertThat(fallback.authored().option().template().id()).isEqualTo("patrol");
        }
    }

    /** A composition made over other capabilities or a template no longer offered is refused, and so is an inability. */
    @Test
    void aStaleOrDeclinedCompositionFallsBack() throws Exception {
        var context = context(SupportedLocale.EN);
        port.completes(output(contract, context, "proposed", ambush(context, List.of(place("raider", "ridge")))));
        var outcome = run(context);
        assertThat(outcome).isInstanceOf(ContractOutcome.Accepted.class);

        // Hurt to 4 of 8 Health, the party's capacity drops to 6, so the band is 6 and even the patrol (8) is over it.
        var hurt = context(SupportedLocale.EN, 4, PlanningFixtures.ambush(), PlanningFixtures.patrol());
        assertThat(EncounterFallback.decide(outcome, hurt)).isEqualTo(new EncounterFallback.Decision.NoEncounter(
                EncounterFallback.Cause.PROPOSAL_INVALID, "stale-snapshot"));
        var withdrawn = context(SupportedLocale.EN, 8, PlanningFixtures.patrol());
        var stale = (EncounterFallback.Decision.Fallback) EncounterFallback.decide(outcome, withdrawn);
        assertThat(stale.detail()).isEqualTo("stale-option");
        assertThat(stale.authored().encounter()).isEqualTo(PlanningFixtures.patrol());

        var declined = new ContractOutcome.Accepted<EncounterProposal>(new EncounterProposal.CannotCompose("no-fitting-roster"),
                List.of(), context.binding(), List.of());
        assertThat(((EncounterFallback.Decision.Fallback) EncounterFallback.decide(declined, context)).detail())
                .isEqualTo("no-fitting-roster");
        var refused = new ContractOutcome.Refused<EncounterProposal>("context-scope", context.binding());
        assertThat(((EncounterFallback.Decision.Fallback) EncounterFallback.decide(refused, context)).detail())
                .isEqualTo("context-scope");
    }
}
