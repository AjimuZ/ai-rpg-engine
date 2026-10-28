package org.rpgengine.commands;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ActorResource;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of P07.5 cancellation races and the equivalent-attempt draw policy on a
 * campaign created from sealed packs. The executing request runs on its own thread and is held at
 * exact boundaries: after durable admission (a catalog-loading seam), after its first durable draw
 * (a draw seam) and inside the canonical commit transaction (an event-insert trigger waiting on an
 * advisory lock the test holds). Cancellation wins at the first two boundaries, leaving no world
 * effect, a fenced permit and retained draws; the commit wins at the third, keeping every effect
 * and stopping only the optional narration. Equivalent new attempts at the same revision reproduce
 * the retained draws instead of re-rolling, while a new revision opens a fresh stream.
 */
@SpringBootTest
class CommandCancellationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long HOLD = 75_001;
    @Autowired private CommandExecutionService executions;
    @Autowired private OperationCancellationService cancellations;
    @Autowired private OperationAdmissionService admissions;
    @Autowired private CanonicalCommitService commits;
    @Autowired private RandomDrawService draws;
    @Autowired private JournalRecoveryService recovery;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager manager;
    private final ExecutorService threads = Executors.newFixedThreadPool(2);
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private UUID player;
    private UUID warden;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    CommandCancellationTest() { }

    /** Register dependency and root packs, create a campaign through production code and place one NPC. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Cancellation", "cancellation", SupportedLocale.EN,
                    Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        var fixture = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI())).files());
        var dependency = register(CommandExecutionTest.dependencyPack(fixture), "test:dep", List.of());
        UUID content = register(CommandExecutionTest.rootPack(fixture), "test:exec",
                List.of(CommandExecutionTest.pin(dependency))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null,
                null)));
        campaign = created.campaignId();
        player = created.actorId();
        warden = new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, "warden", "Warden", "trailhead", 0, Instant.now());
            entities.persist(actor);
            entities.persist(ActorResource.create(actor, "vigor", 0, 2, 6, 0, Instant.now()));
            return actor.getId();
        });
    }

    /** Remove disposable rows and fault-injection triggers, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("drop trigger if exists test_hold_event on campaign_event");
        jdbc.execute("drop trigger if exists test_fail_event on campaign_event");
        jdbc.execute("drop function if exists test_hold_event()");
        jdbc.execute("drop function if exists test_fail_event()");
        jdbc.execute("truncate presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, "
                + "campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, "
                + "plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, "
                + "planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, "
                + "canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, operation_journal, "
                + "actor_resource, actor, presentation_campaign_settings, campaign, content_revision_report, "
                + "content_revision_file, content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * Cancellation after durable admission but before prepare: the operation becomes CANCELLED,
     * its permit is retired with a fence step, the held request then reports CANCELLED with no
     * draw or world effect, and repeating the cancellation writes nothing.
     */
    @Test
    void cancellationBeforePrepareWinsAndFencesTheWorker() throws Exception {
        var admitted = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var held = service(() -> {
            admitted.countDown();
            await(resume);
        }, ordinal -> { });
        var world = world();
        Future<CommandExecutionService.Execution> running = threads.submit(() ->
                held.execute(owner, adventure, campaign, envelope(new TypedCommand.Wait(30), 0)));
        assertTrue(admitted.await(10, TimeUnit.SECONDS));
        UUID operation = jdbc.queryForObject("select id from operation_journal where status = 'ACCEPTED'", UUID.class);
        long fence = jdbc.queryForObject("select fence from campaign_mutation_lease where operation_id = ?", Long.class,
                operation);
        assertEquals(OperationCancellationService.Outcome.CANCELLED,
                cancellations.cancel(owner, adventure, campaign, operation));
        assertEquals((fence + 1) + "|null", jdbc.queryForObject("select fence || '|' || coalesce(operation_id::text, "
                + "'null') from campaign_mutation_lease where campaign_id = ?", String.class, campaign));
        resume.countDown();
        var result = running.get(10, TimeUnit.SECONDS);
        assertEquals(OperationStatus.CANCELLED, result.operation().status());
        assertNull(result.receipt());
        assertEquals(world, world());
        assertEquals(0, count("operation_random_draw"));
        assertEquals(List.of("operation.accepted", "operation.cancelled"), messages(operation));
        // Repeating is idempotent, recovery leaves the terminal row alone and the campaign is free.
        var before = snapshot();
        assertEquals(OperationCancellationService.Outcome.CANCELLED,
                cancellations.cancel(owner, adventure, campaign, operation));
        assertEquals(0, recovery.reconcile());
        assertEquals(before, snapshot());
        assertEquals(OperationStatus.COMMITTED,
                executions.execute(owner, adventure, campaign, envelope(new TypedCommand.Wait(30), 0)).operation().status());
    }

    /**
     * Cancellation after the first durable draw: the next draw or the commit fails its fence and
     * status checks, the draw stays bound to the cancelled attempt, and an equivalent new request at
     * the same revision commits with the same draw value from the inherited stream. A later attempt
     * at a new revision draws from its own stream.
     */
    @Test
    void cancellationBetweenDrawsKeepsDrawsAndEquivalentAttemptReusesThem() throws Exception {
        var drawn = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var held = service(() -> { }, ordinal -> {
            if (ordinal == 0) {
                drawn.countDown();
                await(resume);
            }
        });
        var world = world();
        var gamble = new TypedCommand.UseAbility("gamble", List.of());
        Future<CommandExecutionService.Execution> running = threads.submit(() ->
                held.execute(owner, adventure, campaign, envelope(gamble, 0)));
        assertTrue(drawn.await(10, TimeUnit.SECONDS));
        UUID cancelled = jdbc.queryForObject("select id from operation_journal where status = 'VALIDATING'", UUID.class);
        assertEquals(OperationCancellationService.Outcome.CANCELLED,
                cancellations.cancel(owner, adventure, campaign, cancelled));
        resume.countDown();
        assertEquals(OperationStatus.CANCELLED, running.get(10, TimeUnit.SECONDS).operation().status());
        assertEquals(world, world());
        String kept = jdbc.queryForObject("select string_agg(ordinal || ':' || sides || ':' || value, ',' order by "
                + "ordinal) from operation_random_draw where operation_id = ?", String.class, cancelled);
        assertTrue(kept.startsWith("0:6:") && !kept.contains(","), kept);
        assertEquals(List.of("operation.accepted", "operation.progress", "operation.cancelled"), messages(cancelled));

        // Equivalent attempt: new request identity, same intention and revision, same stream and values.
        var retried = executions.execute(owner, adventure, campaign, envelope(gamble, 0));
        assertEquals(OperationStatus.COMMITTED, retried.operation().status());
        UUID retry = retried.operation().operationId();
        assertEquals(cancelled, jdbc.queryForObject("select draw_stream_id from operation_journal where id = ?",
                UUID.class, retry));
        assertEquals(1, jdbc.queryForObject("select count(distinct attempt_key) from operation_journal "
                + "where id in (?, ?)", Integer.class, cancelled, retry));
        assertEquals(kept, jdbc.queryForObject("select string_agg(ordinal || ':' || sides || ':' || value, ',' "
                + "order by ordinal) from operation_random_draw where operation_id = ? and ordinal = 0", String.class, retry));
        // The same intention at the new revision is a new deliberate attempt with its own stream.
        var later = executions.execute(owner, adventure, campaign, envelope(gamble, 1));
        assertEquals(OperationStatus.COMMITTED, later.operation().status());
        assertEquals(later.operation().operationId(), jdbc.queryForObject("select draw_stream_id from "
                + "operation_journal where id = ?", UUID.class, later.operation().operationId()));
    }

    /**
     * A failure after durable draws followed by an equivalent new request reuses the failed
     * attempt's values: an infrastructure failure cannot be turned into a reroll either.
     */
    @Test
    void failedAttemptFollowedByEquivalentRequestReusesDrawValues() {
        jdbc.execute("create function test_fail_event() returns trigger language plpgsql as "
                + "$$ begin raise exception 'injected event failure'; end $$");
        jdbc.execute("create trigger test_fail_event before insert on campaign_event "
                + "for each row execute function test_fail_event()");
        var gamble = new TypedCommand.UseAbility("gamble", List.of());
        var failed = executions.execute(owner, adventure, campaign, envelope(gamble, 0));
        assertEquals(OperationStatus.FAILED_BEFORE_COMMIT, failed.operation().status());
        jdbc.execute("drop trigger test_fail_event on campaign_event");
        var retried = executions.execute(owner, adventure, campaign, envelope(gamble, 0));
        assertEquals(OperationStatus.COMMITTED, retried.operation().status());
        String draws = "select string_agg(ordinal || ':' || sides || ':' || value, ',' order by ordinal) "
                + "from operation_random_draw where operation_id = ?";
        assertEquals(jdbc.queryForObject(draws, String.class, failed.operation().operationId()),
                jdbc.queryForObject(draws, String.class, retried.operation().operationId()));
        assertEquals(List.of("operation.accepted", "operation.progress", "operation.failed"),
                messages(failed.operation().operationId()));
    }

    /**
     * Exact draw-stream sharing, independent of small dice: a 2^31-1-sided reservation of an
     * equivalent attempt after a cancelled one is identical, and a different intention at the same
     * revision draws an unrelated value from its own stream.
     */
    @Test
    void equivalentAttemptsShareOneDrawStreamAndOthersDoNot() {
        UUID content = jdbc.queryForObject("select content_revision_id from campaign where id = ?", UUID.class,
                campaign);
        int first = cancelledDraw(envelope(new TypedCommand.Wait(30), 0), content);
        int equivalent = cancelledDraw(envelope(new TypedCommand.Wait(30), 0), content);
        int different = cancelledDraw(envelope(new TypedCommand.Wait(31), 0), content);
        assertEquals(first, equivalent);
        assertNotEquals(first, different);
        assertEquals(3, jdbc.queryForObject("select count(*) from operation_journal where status = 'CANCELLED'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(distinct draw_stream_id) from operation_journal",
                Integer.class));
    }

    /**
     * The commit transaction holds the campaign lock while cancellation waits for it: the commit
     * wins, every effect is kept, the cancellation reports ALREADY_COMMITTED with the receipt
     * available, and only the queued narration is stopped (pending, never re-executed).
     */
    @Test
    void commitThatHoldsTheLockWinsAndCancellationOnlyStopsNarration() throws Exception {
        jdbc.execute("create function test_hold_event() returns trigger language plpgsql as $$ begin "
                + "perform pg_advisory_lock(" + HOLD + "); perform pg_advisory_unlock(" + HOLD + "); return new; end $$");
        jdbc.execute("create trigger test_hold_event before insert on campaign_event "
                + "for each row execute function test_hold_event()");
        Future<CommandExecutionService.Execution> running;
        Future<OperationCancellationService.Outcome> cancelling;
        try (Connection holder = dataSource.getConnection()) {
            holder.createStatement().execute("select pg_advisory_lock(" + HOLD + ")");
            running = threads.submit(() -> executions.execute(owner, adventure, campaign,
                    envelope(new TypedCommand.Move("grove"), 0)));
            waitFor("wait_event_type = 'Lock' and wait_event = 'advisory'");
            UUID operation = jdbc.queryForObject("select id from operation_journal where status = 'VALIDATING'",
                    UUID.class);
            cancelling = threads.submit(() -> cancellations.cancel(owner, adventure, campaign, operation));
            // Release only once cancellation is queued behind the commit's campaign lock.
            waitFor("wait_event_type = 'Lock' and wait_event <> 'advisory'");
            holder.createStatement().execute("select pg_advisory_unlock(" + HOLD + ")");
        }
        var executed = running.get(10, TimeUnit.SECONDS);
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        assertEquals(OperationCancellationService.Outcome.ALREADY_COMMITTED, cancelling.get(10, TimeUnit.SECONDS));
        UUID operation = executed.operation().operationId();
        assertEquals("grove", jdbc.queryForObject("select location_id from actor where id = ?", String.class, player));
        assertEquals(1L, jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign));
        var status = executions.status(owner, adventure, campaign, operation);
        assertEquals(OperationStatus.COMMITTED_NARRATION_PENDING, status.operation().status());
        assertEquals(executed.receipt(), status.receipt());
        assertEquals("PENDING|NARRATION_CANCELLED|0", jdbc.queryForObject("select status || '|' || outcome_code || '|' "
                + "|| attempt_count from presentation_job where operation_id = ?", String.class, operation));
        assertEquals(List.of("operation.accepted", "operation.progress", "state.committed", "narrative.pending"),
                messages(operation));
    }

    /**
     * Finished operations, foreign scopes and ambient transactions: rejected, failed and
     * clarification rows report ALREADY_FINISHED without any write; foreign or unknown identities
     * are indistinguishable; joining a caller transaction is refused.
     */
    @Test
    void finishedForeignAndAmbientCancellationsWriteNothing() {
        for (String status : List.of("REJECTED", "FAILED_BEFORE_COMMIT", "NEEDS_CLARIFICATION")) {
            UUID operation = UUID.randomUUID();
            jdbc.update("insert into operation_journal (id, campaign_id, idempotency_key, payload_hash, command_type, "
                    + "base_revision, status, operation_version, created_at, updated_at) values (?, ?, ?, ?, 'WAIT', 0, "
                    + "?, 2, now(), now())", operation, campaign, UUID.randomUUID().toString(), "c".repeat(64), status);
            var before = snapshot();
            assertEquals(OperationCancellationService.Outcome.ALREADY_FINISHED,
                    cancellations.cancel(owner, adventure, campaign, operation), status);
            assertEquals(before, snapshot(), status);
        }
        var before = snapshot();
        for (UUID[] scope : new UUID[][] {{UUID.randomUUID(), adventure, campaign}, {owner, UUID.randomUUID(), campaign},
                {owner, adventure, UUID.randomUUID()}}) {
            UUID operation = jdbc.queryForObject("select id from operation_journal limit 1", UUID.class);
            var failure = assertThrows(OperationAdmissionFailure.class,
                    () -> cancellations.cancel(scope[0], scope[1], scope[2], operation));
            assertEquals(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE, failure.kind());
        }
        assertEquals(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE, assertThrows(OperationAdmissionFailure.class,
                () -> cancellations.cancel(owner, adventure, campaign, UUID.randomUUID())).kind());
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(status ->
                cancellations.cancel(owner, adventure, campaign, UUID.randomUUID())));
        assertEquals(before, snapshot());
    }

    /**
     * Admit one command, put it into VALIDATING as prepare would, reserve ordinal 0 with the widest
     * bound through the production draw service, then cancel it; returns the reserved value.
     */
    private int cancelledDraw(OperationAdmissionService.Command command, UUID content) {
        var admitted = admissions.submit(owner, adventure, campaign, command);
        UUID operation = admitted.operation().operationId();
        jdbc.update("update operation_journal set status = 'VALIDATING', operation_version = 2, "
                + "row_version = row_version + 1 where id = ?", operation);
        String hash = jdbc.queryForObject("select payload_hash from operation_journal where id = ?", String.class,
                operation);
        var context = new ResolvedCommit.Context(adventure, campaign, operation, content, hash, 0, 2,
                admitted.lease().token());
        int value = draws.reserve(context, 0, Integer.MAX_VALUE).value();
        assertEquals(OperationCancellationService.Outcome.CANCELLED,
                cancellations.cancel(owner, adventure, campaign, operation));
        // The cancelled attempt can never add a draw afterwards.
        assertThrows(IllegalStateException.class, () -> draws.reserve(context, 1, 6));
        return value;
    }

    /** Execution pipeline whose catalog load (between admission and prepare) and draws call test hooks. */
    private CommandExecutionService service(Runnable afterAdmission, IntConsumer afterDraw) {
        var catalogs = new CommandRuleCatalogs(jdbc, registry, manager) {
            @Override
            public Content preload(UUID owner, UUID adventure, UUID campaign, String commandType) {
                afterAdmission.run();
                return super.preload(owner, adventure, campaign, commandType);
            }
        };
        var draws = new RandomDrawService(entities, jdbc, manager) {
            @Override
            public Draw reserve(ResolvedCommit.Context context, int ordinal, int sides) {
                Draw draw = super.reserve(context, ordinal, sides);
                afterDraw.accept(ordinal);
                return draw;
            }
        };
        return new CommandExecutionService(admissions, catalogs, draws, commits, entities, jdbc, manager);
    }

    /** Poll pg_stat_activity until another backend matches the wait predicate, failing after ten seconds. */
    private void waitFor(String predicate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() and "
                + predicate, Integer.class) == 0) {
            assertTrue(System.nanoTime() < deadline, "no backend waiting with " + predicate);
            Thread.sleep(20);
        }
    }

    /** Await a latch inside a hook; a missing release fails the held thread instead of hanging. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test hook was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** Stream message types announced for one operation, in sequence order. */
    private List<String> messages(UUID operation) {
        return jdbc.queryForList("select message_type from campaign_stream_message where operation_id = ? "
                + "order by sequence", String.class, operation);
    }

    /** Fresh envelope at an explicit revision. */
    private static OperationAdmissionService.Command envelope(TypedCommand command, long revision) {
        return new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, command.type(),
                command.payload());
    }

    /** Count rows of one table. */
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** Exact rows of every world-state table. */
    private Map<String, String> world() {
        return rows(List.of("campaign", "actor", "actor_resource", "character_state", "campaign_event",
                "canonical_outbox", "query_epoch", "reward_settlement"));
    }

    /** World rows plus journal, permit, draw, stream and presentation tables. */
    private Map<String, String> snapshot() {
        var rows = world();
        rows.putAll(rows(List.of("operation_journal", "campaign_mutation_lease", "operation_random_draw",
                "campaign_random_seed", "campaign_stream_message", "campaign_stream_head", "presentation_job")));
        return rows;
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

    /** Register a fixture variant through real ZIP staging and sealing. */
    private PackRegistry.Registration register(Map<String, byte[]> original, String id,
            List<Map<String, Object>> dependencies) throws Exception {
        var files = new TreeMap<>(original);
        var manifest = CommandExecutionTest.document(files, "adventure.json");
        manifest.put("id", id);
        manifest.put("version", "1.0.0");
        manifest.put("dependencies", dependencies);
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.setLevel(0);
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(output.toByteArray())));
    }
}
