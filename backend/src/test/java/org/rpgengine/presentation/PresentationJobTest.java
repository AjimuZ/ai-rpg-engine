package org.rpgengine.presentation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.JournalRecoveryService;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationCancellationService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.identity.OwnerProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of P07.5 post-commit presentation jobs: one job per committed operation,
 * claimed with a fresh fence and lease, narrated outside transactions and settled with the
 * journal exactly once. Scripted narration ports cover delivery, unavailability, bounded transient
 * retries, a late worker whose lease was lost, cancellation while narrating and startup recovery.
 * Presentation never changes a world row, receipt or draw, and never re-runs the action.
 */
@SpringBootTest
class PresentationJobTest {
    @Autowired private PresentationJobService defaults;
    @Autowired private CommandExecutionService executions;
    @Autowired private OperationCancellationService cancellations;
    @Autowired private JournalRecoveryService journalRecovery;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private long revision;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    PresentationJobTest() { }

    /** Commit one owner and a content-free campaign with its creation player. */
    @BeforeEach
    void prepare() {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Instant now = Instant.now();
            var profile = OwnerProfile.create("Owner", now);
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Presentation", "presentation", SupportedLocale.EN, now);
            entities.persist(workspace);
            adventure = workspace.getId();
            var content = ContentRevision.admit(adventure, new ContentRevision.PackCoordinates("neutral", "1",
                    "a".repeat(64), 1, SupportedLocale.EN), new ContentRevision.RulesetCoordinates("core", "1",
                    "b".repeat(64)), now);
            entities.persist(content);
            var started = Campaign.start(adventure, content, "Campaign", "seed", now);
            entities.persist(started);
            campaign = started.getId();
            var player = Actor.create(campaign, ActorKind.CHARACTER, null, "Player", "camp", 0, now);
            entities.persist(player);
            entities.flush();
            jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, "
                    + "profile_id, choices, created_at) values (?, ?, ?, ?, ?, 'neutral', '{}'::jsonb, now())",
                    adventure, UUID.randomUUID(), "a".repeat(64), campaign, player.getId());
            jdbc.update("insert into presentation_campaign_settings (campaign_id, narrative_locale, updated_at) "
                    + "values (?, 'en', now())", campaign);
        });
        revision = 0;
    }

    /** Remove disposable rows; constraints and triggers remain installed. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, "
                + "campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, "
                + "plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, "
                + "planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, "
                + "canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, operation_journal, "
                + "actor_resource, actor, presentation_campaign_settings, campaign, content_revision_report, "
                + "content_revision_file, content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * Delivered narration settles the job and the journal DELIVERED once, after exactly one port
     * call with the committed references; a second pass finds nothing and the world is unchanged.
     */
    @Test
    void deliveredNarrationSettlesJobAndJournalOnce() {
        UUID operation = commitWait();
        assertEquals("QUEUED|0|0", job(operation, "status || '|' || attempt_count || '|' || fence"));
        var calls = new CopyOnWriteArrayList<NarrationPort.Request>();
        var jobs = service(request -> {
            calls.add(request);
            return delivered();
        });
        var world = world();
        assertEquals(1, jobs.drain(4));
        assertEquals(0, jobs.drain(4));
        assertEquals(List.of(new NarrationPort.Request(adventure, campaign, operation, 1, 1,
                UUID.fromString(job(operation, "id::text")), 0)), calls);
        assertEquals("DELIVERED|NARRATION_DELIVERED|1|2", job(operation,
                "status || '|' || outcome_code || '|' || attempt_count || '|' || fence"));
        assertEquals("DELIVERED", journal(operation));
        assertEquals(world, world());
        assertEquals(List.of("operation.accepted", "operation.progress", "state.committed", "operation.progress",
                "narrative.ready"), messages(operation));
    }

    /**
     * Without a narration runtime (the default port) the action stays saved with narration pending,
     * and replaying the original request returns the stored receipt without re-executing.
     */
    @Test
    void unavailableNarrationLeavesTheActionSavedAndNarrationPending() {
        var input = new OperationAdmissionService.Command(UUID.randomUUID().toString(), 0, "WAIT",
                Map.of("seconds", 15));
        var executed = executions.execute(owner, adventure, campaign, input);
        UUID operation = executed.operation().operationId();
        assertEquals(1, defaults.drain(4));
        assertEquals("PENDING|NARRATION_UNAVAILABLE", job(operation, "status || '|' || outcome_code"));
        var world = world();
        var replay = executions.execute(owner, adventure, campaign, input);
        assertTrue(replay.replayed());
        assertEquals(OperationStatus.COMMITTED_NARRATION_PENDING, replay.operation().status());
        assertEquals(executed.receipt(), replay.receipt());
        assertEquals(world, world());
    }

    /**
     * Transient failures requeue with backoff while the journal stays NARRATING; the third failed
     * attempt ends the job PENDING. The port runs exactly once per attempt and the world never moves.
     */
    @Test
    void transientFailuresRetryWithinTheAttemptBudget() {
        UUID operation = commitWait();
        var calls = new CopyOnWriteArrayList<Integer>();
        var jobs = service(request -> {
            calls.add(request.attempt());
            throw new IllegalStateException("transient narration failure");
        });
        var world = world();
        for (int attempt = 1; attempt < PresentationJobService.MAX_ATTEMPTS; attempt++) {
            assertEquals(1, jobs.drain(4));
            assertEquals("QUEUED|" + attempt + "|true", job(operation,
                    "status || '|' || attempt_count || '|' || (due_at > clock_timestamp())"));
            assertEquals("NARRATING", journal(operation));
            assertEquals(0, jobs.drain(4));
            makeDue(operation);
        }
        assertEquals(1, jobs.drain(4));
        assertEquals("PENDING|NARRATION_FAILED|3", job(operation, "status || '|' || outcome_code || '|' || attempt_count"));
        assertEquals("COMMITTED_NARRATION_PENDING", journal(operation));
        assertEquals(List.of(1, 2, 3), calls);
        assertEquals(world, world());
    }

    /**
     * A worker whose lease expired during narration is interrupted and replaced. Its late result
     * arrives while the replacement attempt is running and writes nothing, because neither its
     * holder nor its fence matches; the replacement then presents the operation exactly once.
     */
    @Test
    void lateWorkerAfterLostLeaseWritesNothing() throws Exception {
        UUID operation = commitWait();
        var lateEntered = new CountDownLatch(1);
        var lateRelease = new CountDownLatch(1);
        var replacementEntered = new CountDownLatch(1);
        var replacementRelease = new CountDownLatch(1);
        var late = service(request -> {
            lateEntered.countDown();
            await(lateRelease);
            return delivered();
        });
        var replacement = service(request -> {
            replacementEntered.countDown();
            await(replacementRelease);
            return delivered();
        });
        try (var threads = Executors.newFixedThreadPool(2)) {
            var lateRun = threads.submit(late::runOnce);
            assertTrue(lateEntered.await(10, TimeUnit.SECONDS));
            jdbc.update("update presentation_job set lease_expires_at = clock_timestamp() - interval '1 second', "
                    + "row_version = row_version + 1 where operation_id = ?", operation);
            // The next pass interrupts the expired attempt; its retry is due after backoff.
            assertTrue(service(request -> NarrationPort.Result.of(NarrationPort.Outcome.FAILED)).runOnce());
            assertEquals("QUEUED|1|2", job(operation, "status || '|' || attempt_count || '|' || fence"));
            makeDue(operation);
            var replacementRun = threads.submit(replacement::runOnce);
            assertTrue(replacementEntered.await(10, TimeUnit.SECONDS));
            var running = presentationRows();
            assertEquals("RUNNING|2|3", job(operation, "status || '|' || attempt_count || '|' || fence"));
            lateRelease.countDown();
            assertTrue(lateRun.get(10, TimeUnit.SECONDS));
            assertEquals(running, presentationRows());
            replacementRelease.countDown();
            assertTrue(replacementRun.get(10, TimeUnit.SECONDS));
        }
        assertEquals("DELIVERED|2|4", job(operation, "status || '|' || attempt_count || '|' || fence"));
        assertEquals("DELIVERED", journal(operation));
        assertEquals(1, jdbc.queryForObject("select count(*) from campaign_stream_message where operation_id = ? "
                + "and message_type = 'narrative.ready'", Integer.class, operation));
    }

    /**
     * Cancelling while narration runs records the request; the worker then settles PENDING and
     * discards the narration it produced, while the committed effects and receipt stay intact.
     */
    @Test
    void cancellationDuringNarrationDiscardsItsResult() throws Exception {
        UUID operation = commitWait();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var jobs = service(request -> {
            entered.countDown();
            await(release);
            return delivered();
        });
        var world = world();
        try (var thread = Executors.newSingleThreadExecutor()) {
            var running = thread.submit(jobs::runOnce);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals(OperationCancellationService.Outcome.ALREADY_COMMITTED,
                    cancellations.cancel(owner, adventure, campaign, operation));
            assertEquals("RUNNING|true", job(operation, "status || '|' || (cancel_requested_at is not null)"));
            release.countDown();
            assertTrue(running.get(10, TimeUnit.SECONDS));
        }
        assertEquals("PENDING|NARRATION_CANCELLED", job(operation, "status || '|' || outcome_code"));
        assertEquals("COMMITTED_NARRATION_PENDING", journal(operation));
        assertEquals(world, world());
        // A repeated cancellation reports the committed outcome and writes nothing.
        var before = presentationRows();
        assertEquals(OperationCancellationService.Outcome.ALREADY_COMMITTED,
                cancellations.cancel(owner, adventure, campaign, operation));
        assertEquals(before, presentationRows());
    }

    /**
     * Startup reconciliation keeps queued work and live leases, interrupts an expired attempt, and
     * after a recovery-generation change retires every unfinished job; journal recovery leaves
     * operations with presentation jobs to this pass.
     */
    @Test
    void startupReconciliationResumesQueuedAndRetiresObsoleteWork() {
        UUID queued = commitWait();
        UUID expired = commitWait();
        UUID live = commitWait();
        running(expired, "clock_timestamp() - interval '1 second'");
        running(live, "clock_timestamp() + interval '60 seconds'");
        assertEquals(0, journalRecovery.reconcile());
        assertEquals(1L, defaults.reconcile());
        assertEquals("QUEUED|0|0", job(queued, "status || '|' || attempt_count || '|' || fence"));
        assertEquals("QUEUED|1|2", job(expired, "status || '|' || attempt_count || '|' || fence"));
        assertEquals("RUNNING|1|1", job(live, "status || '|' || attempt_count || '|' || fence"));
        assertEquals(0L, defaults.reconcile());
        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1 "
                + "where id = ?", campaign);
        var world = jdbc.queryForObject("select state_revision || '|' || game_turn || '|' || event_sequence "
                + "from campaign where id = ?", String.class, campaign);
        assertEquals(3L, defaults.reconcile());
        for (UUID operation : List.of(queued, expired, live)) {
            assertEquals("PENDING|GENERATION_RETIRED", job(operation, "status || '|' || outcome_code"));
            assertEquals("COMMITTED_NARRATION_PENDING", journal(operation));
        }
        assertEquals(world, jdbc.queryForObject("select state_revision || '|' || game_turn || '|' || event_sequence "
                + "from campaign where id = ?", String.class, campaign));
    }

    /**
     * Database guards: one job per operation, a committed origin at transaction end, fence and
     * attempt steps on every claim and release, final terminal rows and no deletion.
     */
    @Test
    void databaseGuardsKeepJobsUniqueFencedAndFinal() {
        UUID operation = commitWait();
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status ->
                PresentationJobService.enqueueWithin(jdbc, adventure, campaign, operation, 1)));
        UUID pending = UUID.randomUUID();
        jdbc.update("insert into operation_journal (id, campaign_id, idempotency_key, payload_hash, command_type, "
                + "base_revision, status, created_at, updated_at) values (?, ?, 'open', ?, 'WAIT', 1, 'ACCEPTED', "
                + "now(), now())", pending, campaign, "c".repeat(64));
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status ->
                PresentationJobService.enqueueWithin(jdbc, adventure, campaign, pending, 2)));
        // A claim must consume one attempt and one fence step.
        assertThrows(RuntimeException.class, () -> jdbc.update("update presentation_job set status = 'RUNNING', "
                + "lease_holder = gen_random_uuid(), lease_expires_at = now(), fence = fence + 1, "
                + "row_version = row_version + 1 where operation_id = ?", operation));
        assertThrows(RuntimeException.class, () -> jdbc.update("update presentation_job set status = 'RUNNING', "
                + "attempt_count = 1, lease_holder = gen_random_uuid(), lease_expires_at = now(), "
                + "row_version = row_version + 1 where operation_id = ?", operation));
        assertThrows(RuntimeException.class, () -> jdbc.update("update presentation_job set status = 'DELIVERED', "
                + "outcome_code = 'NARRATION_DELIVERED', row_version = row_version + 1 where operation_id = ?", operation));
        assertEquals(1, defaults.drain(4));
        assertThrows(RuntimeException.class, () -> jdbc.update("update presentation_job set status = 'QUEUED', "
                + "outcome_code = null, row_version = row_version + 1 where operation_id = ?", operation));
        assertThrows(RuntimeException.class, () -> jdbc.update("update presentation_job set updated_at = now(), "
                + "row_version = row_version + 1 where operation_id = ?", operation));
        assertThrows(RuntimeException.class, () -> jdbc.update("delete from presentation_job where operation_id = ?",
                operation));
        assertEquals("PENDING|NARRATION_UNAVAILABLE", job(operation, "status || '|' || outcome_code"));
    }

    /** Commit one WAIT at the tracked revision and return its operation. */
    private UUID commitWait() {
        var executed = executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision, "WAIT", Map.of("seconds", 10)));
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        revision = executed.operation().committedRevision();
        return executed.operation().operationId();
    }

    /** Simulate an attempt in flight: the job RUNNING with one attempt and fence, the journal NARRATING. */
    private void running(UUID operation, String expiry) {
        jdbc.update("update presentation_job set status = 'RUNNING', attempt_count = 1, fence = 1, "
                + "lease_holder = gen_random_uuid(), lease_expires_at = " + expiry + ", row_version = row_version + 1 "
                + "where operation_id = ?", operation);
        jdbc.update("update operation_journal set status = 'NARRATING', operation_version = operation_version + 1, "
                + "row_version = row_version + 1 where id = ?", operation);
    }

    /** A minimal validated English publication bound to the fixture's initial presentation generation. */
    static NarrationPort.Result delivered() {
        return NarrationPort.Result.delivered(new NarrationPort.Publication(SupportedLocale.EN, 0, 0, "c".repeat(64),
                false, List.of(new NarrationPort.Passage(NarrationPort.PassageKind.NARRATION, null, null,
                        "Time passes quietly.", List.of("event.1"))), List.of(), null, null, null));
    }

    /** Job service with a scripted narration port. */
    private PresentationJobService service(NarrationPort port) {
        return new PresentationJobService(entities, jdbc, manager, port);
    }

    /** Make a requeued job due now, as its backoff would eventually. */
    private void makeDue(UUID operation) {
        jdbc.update("update presentation_job set due_at = clock_timestamp(), row_version = row_version + 1 "
                + "where operation_id = ?", operation);
    }

    /** One SQL expression over the operation's job row. */
    private String job(UUID operation, String expression) {
        return jdbc.queryForObject("select " + expression + " from presentation_job where operation_id = ?",
                String.class, operation);
    }

    /** Current journal status. */
    private String journal(UUID operation) {
        return jdbc.queryForObject("select status from operation_journal where id = ?", String.class, operation);
    }

    /** Stream message types of one operation in order. */
    private List<String> messages(UUID operation) {
        return jdbc.queryForList("select message_type from campaign_stream_message where operation_id = ? "
                + "order by sequence", String.class, operation);
    }

    /** Await a latch inside a port; a missing release fails instead of hanging. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("port was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** Exact rows of every world-state table, plus receipts and draws. */
    private Map<String, String> world() {
        var rows = rows(List.of("campaign", "actor", "actor_resource", "campaign_event", "canonical_outbox",
                "query_epoch", "reward_settlement", "operation_random_draw"));
        rows.put("receipts", jdbc.queryForObject("select coalesce(jsonb_agg(receipt order by id), '[]'::jsonb)::text "
                + "from operation_journal", String.class));
        return rows;
    }

    /** Exact presentation rows: jobs, journal and stream. */
    private Map<String, String> presentationRows() {
        return rows(List.of("presentation_job", "operation_journal", "campaign_stream_message", "campaign_stream_head"));
    }

    /** Canonical JSON of each table's rows. */
    private Map<String, String> rows(List<String> tables) {
        var rows = new LinkedHashMap<String, String>();
        for (String table : tables) {
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return rows;
    }
}
