package org.rpgengine.inference.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.rpgengine.inference.contract.ContractFixtures.ADVENTURE;
import static org.rpgengine.inference.contract.ContractFixtures.CAMPAIGN;
import static org.rpgengine.inference.contract.ContractFixtures.GENERATION;
import static org.rpgengine.inference.contract.ContractFixtures.SECRET;
import static org.rpgengine.inference.contract.ContractFixtures.guard;
import static org.rpgengine.inference.contract.ContractFixtures.intentBody;
import static org.rpgengine.inference.contract.ContractFixtures.narrationBody;
import static org.rpgengine.inference.contract.ContractFixtures.output;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.scheduling.SchedulerFixtures;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.inference.session.SessionScope;
import org.rpgengine.presentation.SupportedLocale;

/**
 * P08.3 at the contract layer: the runner derives every session from the task kind and the
 * binding, so interpretation and narration, campaigns, generations and policies get separate
 * sessions, a privileged planner session is never used to narrate, and a stale generation is
 * refused without submitting anything.
 */
class ContractSessionScopeTest {
    static final String POLICY = "b".repeat(64);
    static final String NEW_POLICY = "c".repeat(64);
    static final UUID OTHER_CAMPAIGN = UUID.fromString("00000000-0000-4000-8000-000000000004");

    final ScriptedTaskPort port = new ScriptedTaskPort();
    final ScopedSessions sessions = new ScopedSessions(port, ScopedSessions.DEFAULT_CAPACITY);
    final ContractRunner runner = new ContractRunner(SchedulerFixtures.scheduler(sessions, port, Clock.systemUTC()),
            Clock.systemUTC());
    final NarrationContract narration = new NarrationContract();
    final IntentContract intent = new IntentContract();

    static TaskBinding binding(UUID campaign, long generation, String policy) {
        return new TaskBinding(UUID.randomUUID(), ADVENTURE, campaign, generation, 7, SupportedLocale.EN, 2, 3,
                "a".repeat(64), policy);
    }

    /**
     * Run one valid narration under {@code binding}, with a fresh task identity (the scheduler's
     * ledger refuses a repeated identity), and return its single request.
     */
    TaskRequest narrate(TaskBinding binding) throws Exception {
        binding = new TaskBinding(UUID.randomUUID(), binding.adventureId(), binding.campaignId(),
                binding.campaignGeneration(), binding.baseRevision(), binding.outputLocale(), binding.localeRevision(),
                binding.glossaryRevision(), binding.packHash(), binding.policyHash());
        var context = ContractFixtures.narration(binding, guard());
        port.completes(output(narration, context, "proposed", narrationBody(SupportedLocale.EN)));
        var outcome = runner.start(narration, context, InferenceSlot.STANDARD, Duration.ofSeconds(90)).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(outcome).isInstanceOf(ContractOutcome.Accepted.class);
        return port.requests.getLast();
    }

    UUID session(TaskKind kind, TaskBinding binding) {
        return sessions.activeSession(kind.scope(binding)).orElseThrow();
    }

    @Test
    void interpretationAndNarrationRunInSeparatePublicSessionsNamedInTheEnvelope() throws Exception {
        TaskBinding interpretBinding = binding(CAMPAIGN, GENERATION, POLICY);
        var context = ContractFixtures.intent(interpretBinding, "I ask Mara: \"Where is the old mill?\"",
                Set.of(IntentKind.values()));
        port.completes(output(intent, context, "proposed", intentBody()));
        var interpreted = runner.start(intent, context, InferenceSlot.LIGHT, Duration.ofSeconds(60)).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(interpreted).isInstanceOf(ContractOutcome.Accepted.class);
        TaskRequest interpretation = port.requests.getLast();
        TaskBinding narrateBinding = binding(CAMPAIGN, GENERATION, POLICY);
        TaskRequest narrated = narrate(narrateBinding);

        assertThat(interpretation.sessionId()).isEqualTo(session(TaskKind.INTENT, interpretBinding))
                .isNotEqualTo(narrated.sessionId());
        assertThat(narrated.sessionId()).isEqualTo(session(TaskKind.NARRATION, narrateBinding));
        assertThat(interpretation.instructions()).contains("\"taskKind\":\"intent\",\"role\":\"interpreter\","
                + "\"audience\":\"public\"").contains("\"campaignGeneration\":1,");
        assertThat(narrated.instructions()).contains("\"taskKind\":\"narration\",\"role\":\"narrator\","
                + "\"audience\":\"public\"");
        assertThat(TaskKind.NARRATION.scope(narrateBinding)).isEqualTo(new SessionScope(ADVENTURE, CAMPAIGN,
                GENERATION, AiRole.NARRATOR, Audience.PUBLIC, "a".repeat(64), POLICY));
    }

    @Test
    void aScopeKeepsItsSessionAcrossTasksAndAnotherCampaignGetsItsOwn() throws Exception {
        TaskRequest first = narrate(binding(CAMPAIGN, GENERATION, POLICY));
        TaskRequest second = narrate(binding(CAMPAIGN, GENERATION, POLICY));
        TaskRequest elsewhere = narrate(binding(OTHER_CAMPAIGN, GENERATION, POLICY));
        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(elsewhere.sessionId()).isNotEqualTo(first.sessionId());
        assertThat(port.retired).isEmpty();
    }

    @Test
    void aPolicyOrGenerationChangeRenewsTheSessionAndAStaleGenerationIsRefused() throws Exception {
        UUID original = narrate(binding(CAMPAIGN, GENERATION, POLICY)).sessionId();
        UUID repoliced = narrate(binding(CAMPAIGN, GENERATION, NEW_POLICY)).sessionId();
        assertThat(repoliced).isNotEqualTo(original);
        assertThat(port.retired).containsExactly(original);

        UUID restored = narrate(binding(CAMPAIGN, GENERATION + 1, NEW_POLICY)).sessionId();
        assertThat(restored).isNotEqualTo(repoliced);
        assertThat(port.retired).containsExactly(original, repoliced);

        // A late task assembled before the restore neither runs nor disturbs the current session.
        TaskBinding stale = binding(CAMPAIGN, GENERATION, NEW_POLICY);
        var context = ContractFixtures.narration(stale, guard());
        int submitted = port.requests.size();
        var outcome = runner.start(narration, context, InferenceSlot.STANDARD, Duration.ofSeconds(90)).outcome()
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(outcome).isInstanceOfSatisfying(ContractOutcome.NotCompleted.class, refused -> {
            assertThat(refused.status()).isEqualTo(TaskResult.Status.REJECTED);
            assertThat(refused.errorCode()).isEqualTo(ScopedSessions.STALE_GENERATION);
            assertThat(refused.attempts()).hasSize(1);
        });
        assertThat(port.requests).hasSize(submitted);
        assertThat(sessions.activeSession(TaskKind.NARRATION.scope(binding(CAMPAIGN, GENERATION + 1, NEW_POLICY))))
                .contains(restored);
    }

    @Test
    void aPrivilegedPlannerSessionOfTheSameCampaignIsNeverUsedToNarrate() throws Exception {
        TaskBinding narrateBinding = binding(CAMPAIGN, GENERATION, POLICY);
        var planner = new SessionScope(ADVENTURE, CAMPAIGN, GENERATION, AiRole.QUEST_PLANNER, Audience.PRIVILEGED,
                "a".repeat(64), POLICY);
        port.completes("{\"plan\":\"The " + SECRET + " meets at the mill.\"}");
        sessions.submit(planner, UUID.randomUUID(), InferenceSlot.DELIBERATE,
                "Plan the quest. Secret canon: the " + SECRET + " controls the mill.", "{}", Duration.ofSeconds(30));
        UUID plannerSession = port.requests.getLast().sessionId();

        TaskRequest narrated = narrate(narrateBinding);
        TaskRequest again = narrate(narrateBinding);

        assertThat(narrated.sessionId()).isNotEqualTo(plannerSession);
        assertThat(again.sessionId()).isEqualTo(narrated.sessionId());
        assertThat(narrated.instructions()).doesNotContain(SECRET);
        assertThat(again.instructions()).doesNotContain(SECRET);
        // Both lines stay active side by side; neither supersedes the other.
        assertThat(sessions.activeSession(planner)).contains(plannerSession);
        assertThat(port.retired).isEmpty();
    }

    @Test
    void renewingTheNarratorLeavesTheInterpreterSessionAlone() throws Exception {
        TaskBinding shared = binding(CAMPAIGN, GENERATION, POLICY);
        var context = ContractFixtures.intent(shared, "I ask Mara: \"Where is the old mill?\"",
                Set.of(IntentKind.values()));
        port.completes(output(intent, context, "proposed", intentBody()));
        runner.start(intent, context, InferenceSlot.LIGHT, Duration.ofSeconds(60)).outcome().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        UUID interpreter = session(TaskKind.INTENT, shared);
        UUID before = narrate(binding(CAMPAIGN, GENERATION, POLICY)).sessionId();

        assertThat(sessions.retire(TaskKind.NARRATION.scope(shared))).isTrue();
        UUID after = narrate(binding(CAMPAIGN, GENERATION, POLICY)).sessionId();

        assertThat(after).isNotEqualTo(before);
        assertThat(port.retired).containsExactly(before);
        assertThat(session(TaskKind.INTENT, shared)).isEqualTo(interpreter);
    }
}
