package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.EngineApplication;
import org.rpgengine.adventure.FirstStartProvisioning;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.inference.CompletedTaskHandle;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.inference.scheduling.ScheduledTask;
import org.rpgengine.inference.scheduling.SchedulerSettings;
import org.rpgengine.inference.scheduling.TaskLedger;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.RenewalPolicy;
import org.rpgengine.inference.session.Rehydrator;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.inference.session.SessionJournal;
import org.rpgengine.inference.session.SessionScope;
import org.rpgengine.memory.MemoryStartupReconciliation;
import org.rpgengine.memory.SessionRehydration;
import org.rpgengine.presentation.NarrationPort;
import org.rpgengine.presentation.PresentationEventStore;
import org.rpgengine.presentation.PresentationJobService;
import org.rpgengine.presentation.PresentationScopePort;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of P14.2-A (QA-08, QA-17, QA-18, docs/07 and docs/08 section 10) on the bundled
 * sample adventure as first-start provisioning installs it. Each case stops the engine at an interruption and then
 * reboots it: a second, complete application context over the same database runs every startup pass of a packaged
 * start (provisioning, journal, presentation and scheduler recovery, then the memory pass, which the test
 * configuration otherwise disables), without a web server and without a runtime account.
 *
 * <p>QA-17: an attempt cut off after its dice are reserved, with its permit left held as a killed process leaves
 * it, has no world effect; the reboot ends it before commit, its lost response replays that ending, and the action
 * asked again finds the same dice and commits once. QA-18 with QA-08: an action committed while its narration ran
 * keeps its effects through the reboot; the narration resumes and is published once without re-running the action;
 * the narrator's first session after the reboot starts at the pending decision with the owed narration counted and
 * without the opening scene; and the action's lost response replays its receipt.
 *
 * <p>Neither the narrator runtime nor the narration it writes is real here: both are scripted, so this proves what
 * the engine sends and stores, not what a live model does with it.
 */
@SpringBootTest
class RebootRecoveryTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000142");
    private static final String PACK_HASH = "c".repeat(64);
    private static final String POLICY_HASH = "d".repeat(64);
    /** The bundled sample's opening scene, which a renewed narrator must never be given again. */
    private static final String OPENING = "You reach the camp beside the ford at dusk.";
    @Autowired private FirstStartProvisioning provisioning;
    @Autowired private CampaignCreationService creation;
    @Autowired private CommandExecutionService executions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaskLedger ledger;
    @Autowired private SessionJournal journal;
    @Autowired private SessionRehydration rehydration;
    private UUID adventure;
    private UUID campaign;
    private long revision;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    RebootRecoveryTest() { }

    /** Provision the owner and the bundled sample as a first start does, then create one English playthrough. */
    @BeforeEach
    void prepare() {
        clear();
        adventure = provisioning.provision(OWNER).workspaceId();
        UUID content = jdbc.queryForObject("select id from content_revision", UUID.class);
        campaign = creation.create(OWNER, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Reboot watch", SupportedLocale.EN, new CharacterChoices("Player", "scout", Map.of(), Map.of(), null,
                        null))).campaignId();
        revision = 0;
    }

    /** Remove disposable rows and the injected failure, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("drop trigger if exists test_reboot_crash on operation_journal");
        jdbc.execute("drop function if exists test_reboot_crash()");
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, "
                + "equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, "
                + "memory_summary_job, relationship_change, faction_clock_change, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
                + "narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, "
                + "campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, "
                + "ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * QA-17 across a reboot: the initiative roll of the ford ambush is cut off after its dice are reserved and while its
     * permit is held. Nothing of the world changes; once the dead process's permit has expired, the reboot ends the
     * attempt {@code FAILED_BEFORE_COMMIT} with its dice kept, provisions nothing again, and a second recovery pass finds
     * nothing left. The attempt's lost response replays that ending without running it; the roll asked again commits
     * with the same dice; and its own lost response replays the same receipt with the initiative event recorded once.
     */
    @Test
    void aCrashBeforeCommitLeavesNoEffectAndTheRebootKeepsItsDice() {
        send(executions, new TypedCommand.Engage("ford-ambush", null));
        var roll = envelope(new TypedCommand.RollInitiative());
        var world = world();
        crash(roll);
        UUID cut = jdbc.queryForObject("select id from operation_journal where campaign_id = ? and idempotency_key = ?",
                UUID.class, campaign, roll.requestId());
        assertEquals("VALIDATING", status(cut), "the crash left the attempt unresolved");
        String reserved = draws(cut);
        assertNotNull(reserved, "the roll reserved its dice before the crash");
        assertEquals(world, world(), "an attempt cut off before its commit changes nothing of the world");
        assertEquals(0, jdbc.queryForObject("select count(*) from canonical_outbox where operation_id = ?", Integer.class,
                cut), "nor records any effect intent");
        // The killed process never renews its permit, so the permit's deadline passes before the next start.
        jdbc.update("update campaign_mutation_lease set expires_at = clock_timestamp() - interval '1 second' where "
                + "campaign_id = ?", campaign);

        try (var rebooted = reboot()) {
            assertEquals("FAILED_BEFORE_COMMIT", status(cut), "the reboot ends the attempt without resolving it");
            assertEquals(reserved, draws(cut), "and keeps its dice");
            assertEquals(world, world(), "the reboot changes nothing of the world");
            assertEquals(List.of(1, 1, 1), List.of(rows("identity_owner_profile"), rows("adventure_workspace"),
                    rows("content_revision")), "a provisioned installation is not provisioned again");
            var report = rebooted.getBean(MemoryStartupReconciliation.class).lastReport();
            assertNotNull(report, "the memory pass ran at the reboot");
            assertEquals(0, report.failures());
            assertEquals(0, rebooted.getBean(JournalRecoveryService.class).reconcile(), "nothing is left to recover");

            var engine = rebooted.getBean(CommandExecutionService.class);
            var lost = engine.execute(OWNER, adventure, campaign, roll);
            assertTrue(lost.replayed(), "the lost response is answered from the journal");
            assertEquals(OperationStatus.FAILED_BEFORE_COMMIT, lost.operation().status(), "a retry never resumes the attempt");
            assertNull(lost.receipt());
            assertEquals(world, world());

            var again = envelope(new TypedCommand.RollInitiative());
            var rolled = engine.execute(OWNER, adventure, campaign, again);
            assertEquals(OperationStatus.COMMITTED, rolled.operation().status());
            assertNotEquals(cut, rolled.operation().operationId());
            assertEquals(reserved, draws(rolled.operation().operationId()), "a crash and a reboot are not a reroll");
            assertEquals(1, events("INITIATIVE_ROLLED"));
            var settled = everything();
            var replay = engine.execute(OWNER, adventure, campaign, again);
            assertTrue(replay.replayed());
            assertEquals(rolled.receipt(), replay.receipt(), "the lost response replays the receipt");
            assertEquals(settled, everything(), "and rolls, writes and announces nothing again");
            assertEquals(1, events("INITIATIVE_ROLLED"));
        }
    }

    /**
     * QA-18 with QA-08 across a reboot: a narrator session is open, then a wait commits and the process stops while
     * its narration runs. The reboot keeps the action's effects, retires the open session with {@code RESTART}, requeues
     * the interrupted narration and counts it as resuming. The narrator's first session after the reboot is rehydrated
     * at the free-input decision with the owed narration counted and without the opening scene; the narration is then
     * published exactly once without re-running the wait; the wait's lost response replays its receipt; and a later
     * renewal finds nothing owed.
     */
    @Test
    void aRebootResumesInterruptedNarrationOnceBehindARehydratedNarrator() throws Exception {
        var before = new ScriptedNarrator();
        run(scheduler(before, journal, rehydration, ledger), narrator(), "before the stop");
        UUID first = before.requests.getFirst().sessionId();
        assertEquals("ACTIVE", session(first).get("status"));

        var wait = envelope(new TypedCommand.Wait(60));
        var executed = executions.execute(OWNER, adventure, campaign, wait);
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        revision = executed.operation().committedRevision();
        UUID operation = executed.operation().operationId();
        // The process stops while an attempt narrates: the job runs under a lease that then expires.
        jdbc.update("update presentation_job set status = 'RUNNING', attempt_count = 1, fence = 1, lease_holder = "
                + "gen_random_uuid(), lease_expires_at = clock_timestamp() - interval '1 second', row_version = "
                + "row_version + 1 where operation_id = ?", operation);
        jdbc.update("update operation_journal set status = 'NARRATING', operation_version = operation_version + 1, "
                + "row_version = row_version + 1 where id = ?", operation);
        var world = world();
        assertEquals(0, rows("narration_publication"));

        try (var rebooted = reboot()) {
            assertEquals(world, world(), "the reboot keeps the action's effects and repeats none");
            assertEquals("RETIRED", session(first).get("status"));
            assertEquals("RESTART", session(first).get("retired_reason"), "the reboot retires the open session");
            assertEquals("QUEUED", jdbc.queryForObject("select status from presentation_job where operation_id = ?",
                    String.class, operation), "the interrupted narration is queued again");
            assertEquals("NARRATING", status(operation), "the journal still owes the narration");
            var report = rebooted.getBean(MemoryStartupReconciliation.class).lastReport();
            assertNotNull(report, "the memory pass ran at the reboot");
            assertEquals(1, report.narrationResuming());
            assertEquals(0, report.narrationPending());

            var after = new ScriptedNarrator();
            var resumed = rebooted.getBean(SessionRehydration.class);
            run(scheduler(after, rebooted.getBean(SessionJournal.class), resumed, rebooted.getBean(TaskLedger.class)),
                    narrator(), "narrate the committed action");
            TaskRequest renewed = after.requests.getFirst();
            assertNotEquals(first, renewed.sessionId(), "the old conversation is never resumed");
            JsonNode continuity = continuity(renewed.instructions());
            assertEquals("restart", continuity.path("renewal").path("cause").asString());
            assertEquals(revision, continuity.path("resume").path("stateRevision").asLong());
            assertEquals("free-input", continuity.path("resume").path("pendingDecision").asString());
            assertEquals(1, continuity.path("resume").path("narrationPending").asInt(), "the owed narration is counted");
            assertFalse(renewed.instructions().contains(OPENING), "the opening scene is not replayed");
            assertTrue(renewed.instructions().endsWith("\n\nnarrate the committed action"));
            assertEquals(world, world(), "a renewal changes nothing of the world");

            var calls = new AtomicInteger();
            var jobs = new PresentationJobService(rebooted.getBean(EntityManager.class), jdbc,
                    rebooted.getBean(PlatformTransactionManager.class), request -> {
                        calls.incrementAndGet();
                        return NarrationPort.Result.delivered(new NarrationPort.Publication(SupportedLocale.EN, 0, 0,
                                "c".repeat(64), false, List.of(new NarrationPort.Passage(NarrationPort.PassageKind.NARRATION,
                                        null, null, "The river keeps its slow count.", List.of("event.1"))), List.of(),
                                null, null, null));
                    }, rebooted.getBean(PresentationEventStore.class), rebooted.getBean(PresentationScopePort.class));
            // The requeued attempt's backoff elapses.
            jdbc.update("update presentation_job set due_at = clock_timestamp(), row_version = row_version + 1 where "
                    + "operation_id = ?", operation);
            assertEquals(1, jobs.drain(4));
            assertEquals(0, jobs.drain(4));
            assertEquals(1, calls.get(), "the owed narration is produced once");
            assertEquals("DELIVERED", status(operation));
            assertEquals(1, rows("narration_publication"));
            assertEquals(world, world(), "narration never re-runs the action");

            var engine = rebooted.getBean(CommandExecutionService.class);
            var settled = everything();
            var lost = engine.execute(OWNER, adventure, campaign, wait);
            assertTrue(lost.replayed());
            assertEquals(executed.receipt(), lost.receipt(), "the lost response replays the receipt");
            assertEquals(settled, everything(), "and waits, narrates and publishes nothing again");
            assertEquals(1, rows("presentation_job"), "one narration job for one action");

            var later = assertInstanceOf(Rehydrator.Ready.class, resumed.prepare(narrator(), "turn-ceiling", false));
            JsonNode owed = continuity(later.text());
            assertEquals("free-input", owed.path("resume").path("pendingDecision").asString());
            assertEquals(0, owed.path("resume").path("narrationPending").asInt(), "nothing is owed any more");
            assertFalse(later.text().contains(OPENING));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * Start the engine again over the same database, as a packaged start would: provisioning is enabled for the same
     * owner and the memory pass runs, while the presentation and memory workers stay off as in the rest of the suite.
     * Command-line arguments outrank the test configuration file. No web server and no runtime account are used.
     */
    private static ConfigurableApplicationContext reboot() {
        return new SpringApplicationBuilder(EngineApplication.class).web(WebApplicationType.NONE).run(
                "--engine.provisioning.enabled=true", "--engine.http.owner-id=" + OWNER,
                "--engine.memory.reconciliation.enabled=true");
    }

    /**
     * Submit a request whose commit and whose failure bookkeeping both fail after its dice are reserved, as when the
     * process dies between them: the operation stays {@code VALIDATING} with its permit held. The injected trigger
     * refuses every update that moves an operation out of {@code VALIDATING}.
     */
    private void crash(OperationAdmissionService.Command request) {
        jdbc.execute("create or replace function test_reboot_crash() returns trigger language plpgsql as "
                + "$$ begin raise exception 'injected crash'; end $$");
        jdbc.execute("create trigger test_reboot_crash before update on operation_journal for each row "
                + "when (old.status = 'VALIDATING' and new.status <> 'VALIDATING') execute function test_reboot_crash()");
        try {
            var failure = assertThrows(RuntimeException.class, () -> executions.execute(OWNER, adventure, campaign, request));
            assertFalse(failure instanceof OperationAdmissionFailure, "an infrastructure failure, not a refusal");
        } finally {
            jdbc.execute("drop trigger if exists test_reboot_crash on operation_journal");
            jdbc.execute("drop function if exists test_reboot_crash()");
        }
    }

    /** Scripted narrator runtime: completes every task and records what it was sent. */
    private static final class ScriptedNarrator implements NarrationTaskPort {
        final List<TaskRequest> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public TaskHandle submit(TaskRequest request) {
            requests.add(request);
            return new CompletedTaskHandle(new TaskResult(request.taskId(), TaskResult.Status.COMPLETED, "{\"done\":true}",
                    null, PlannerTaskPort.PROVENANCE, false));
        }

        @Override
        public void retireSession(UUID sessionId) {
        }

        @Override
        public RuntimeStatus status() {
            return new RuntimeStatus("scripted", RuntimeStatus.State.READY, null, 0, 0);
        }
    }

    /** A recovered scheduler over a session registry backed by the given process's journal and rehydration. */
    private static InferenceScheduler scheduler(ScriptedNarrator runtime, SessionJournal journal,
            SessionRehydration rehydration, TaskLedger ledger) {
        var sessions = new ScopedSessions(runtime, 16, RenewalPolicy.defaults(), journal, rehydration, UUID::randomUUID,
                Clock.systemUTC());
        var scheduler = new InferenceScheduler(sessions, runtime, ledger, SchedulerSettings.defaults(), Clock.systemUTC(),
                () -> 0.5);
        scheduler.recover();
        return scheduler;
    }

    /** Run one interactive task to its settled result; it must complete. */
    private static void run(InferenceScheduler scheduler, SessionScope scope, String instructions) throws Exception {
        var result = scheduler.submit(new ScheduledTask(UUID.randomUUID(), scope, WorkClass.INTERACTIVE, InferenceSlot.LIGHT,
                instructions, "{\"type\":\"object\"}", Duration.ofSeconds(30))).result().toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
        assertEquals(TaskResult.Status.COMPLETED, result.status());
    }

    /** @return the public narrator scope of the campaign at its current recovery generation */
    private SessionScope narrator() {
        long generation = jdbc.queryForObject("select recovery_generation from campaign where id = ?", Long.class, campaign);
        return new SessionScope(adventure, campaign, generation, AiRole.NARRATOR, Audience.PUBLIC, PACK_HASH, POLICY_HASH);
    }

    /** @return the parsed continuity line of a rehydrated block */
    private static JsonNode continuity(String text) {
        int start = text.indexOf(SessionRehydration.MARKER + "\n");
        assertTrue(start >= 0, "a continuity block is present");
        start += SessionRehydration.MARKER.length() + 1;
        return JSON.readTree(text.substring(start, text.indexOf('\n', start)));
    }

    private Map<String, Object> session(UUID id) {
        return jdbc.queryForMap("select status, retired_reason from ai_session_record where id = ?", id);
    }

    private OperationAdmissionService.Command envelope(TypedCommand command) {
        return new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, command.type(), command.payload());
    }

    private void send(CommandExecutionService engine, TypedCommand command) {
        var execution = engine.execute(OWNER, adventure, campaign, envelope(command));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
        revision = execution.operation().committedRevision();
    }

    private String status(UUID operation) {
        return jdbc.queryForObject("select status from operation_journal where id = ?", String.class, operation);
    }

    /** One operation's draws as {@code ordinal:sides:value}, or null when it has none. */
    private String draws(UUID operation) {
        return jdbc.queryForObject("select string_agg(ordinal || ':' || sides || ':' || value, ',' order by ordinal) "
                + "from operation_random_draw where operation_id = ?", String.class, operation);
    }

    private int events(String type) {
        return jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? and event_type = ?",
                Integer.class, campaign, type);
    }

    /** @return the row count of a fixed table */
    private int rows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /**
     * The world an interruption, a reboot and a narration must keep: the whole campaign row and every row of its
     * actors, pools, conditions, equipment, items, abilities, events, fight and reward settlements. Draws are compared
     * separately, because a cut attempt reserves them before its commit; delivery rows are left to
     * {@link #everything()}, because publishing owed narration legitimately writes them.
     */
    private Map<String, List<String>> world() {
        var tables = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("campaign", "actor", "actor_resource", "actor_condition", "equipment_assignment",
                "item_instance", "learned_ability", "campaign_event", "encounter", "encounter_participant",
                "reward_settlement")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }

    /** The world plus the journal, draws, intents, narration and delivery rows a lost-response replay must not touch. */
    private Map<String, List<String>> everything() {
        var tables = world();
        for (String table : List.of("operation_journal", "operation_random_draw", "canonical_outbox",
                "narration_publication", "presentation_job", "presentation_event", "campaign_stream_message",
                "campaign_stream_head")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }
}
