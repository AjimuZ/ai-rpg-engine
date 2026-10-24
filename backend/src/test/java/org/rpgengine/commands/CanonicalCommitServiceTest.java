package org.rpgengine.commands;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ActorResource;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL atomicity, retry, scope and concurrency checks for P03.3. Each call
 * crosses a real transaction boundary; independent JDBC reads observe only committed
 * data. Test-only database triggers inject failures even after all SQL has flushed,
 * without adding callbacks or fault switches to production code. Infrastructure,
 * timeouts and assertion failures fail the suite instead of silently skipping it.
 */
@SpringBootTest
class CanonicalCommitServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final String HASH = "c".repeat(64);
    private static final List<String> TABLES = List.of("campaign", "actor_resource", "operation_journal",
            "campaign_event", "reward_settlement", "canonical_outbox");
    @Autowired private CanonicalCommitService commits;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private DataSource dataSource;
    private Fixture fixture;

    /** Construct the injected fixture; missing infrastructure fails context startup. */
    CanonicalCommitServiceTest() {
    }

    /** Start from an empty disposable database and persist one fully scoped validating operation. */
    @BeforeEach
    void prepare() {
        clear();
        fixture = createFixture();
    }

    /** Remove only test-owned fault triggers/functions and rows, including after an assertion fails. */
    @AfterEach
    void clear() {
        for (String table : TABLES) {
            jdbc.execute("drop trigger if exists p033_fault on " + table);
        }
        jdbc.execute("drop function if exists p033_fail()");
        jdbc.execute("truncate canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision, adventure_workspace, identity_owner_profile");
    }

    /** All effects persist together; lost acknowledgments and later narration never spend again. */
    @Test
    void commitsCompleteBundleAndReplaysExactReceiptAfterLaterChanges() {
        var plan = plan(fixture, fixture.operation());
        var result = commits.commit(plan);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(1, result.stateRevision());
        assertEquals(7L, number("actor_resource", "current_value"));
        assertEquals(1L, number("campaign", "state_revision"));
        assertEquals(1L, number("campaign", "game_turn"));
        assertEquals(60L, number("campaign", "world_time_seconds"));
        assertEquals(2L, number("campaign", "event_sequence"));
        assertEquals(List.of(1L, 2L), jdbc.queryForList("select sequence from campaign_event order by sequence", Long.class));
        assertEquals(2, count("canonical_outbox"));
        assertEquals(1, count("reward_settlement"));
        assertEquals(2, jdbc.queryForObject("select count(*) from campaign_event where state_revision = 1 "
                + "and world_time_seconds = 60 and ruleset_hash = ?", Integer.class, "b".repeat(64)));
        assertEquals("{\"kind\": \"choose\"}", jdbc.queryForObject("select pending_decision::text from campaign", String.class));
        assertEquals(result.json(), jdbc.queryForObject("select receipt::text from operation_journal", String.class));
        assertFalse(result.json().contains("hidden-clue"));
        assertEquals(Set.of("id", "campaign_id", "operation_id", "state_revision", "kind", "created_at"),
                jdbc.queryForMap("select * from canonical_outbox limit 1").keySet());

        var next = operation(fixture.campaign(), 1);
        var later = plan(fixture, next);
        later = new ResolvedCommit(later.context(), new ResolvedCommit.WorldChange(false, 0,
                CampaignMode.EXPLORATION, null), List.of(), later.events(), List.of(), "{}", false);
        commits.commit(later);
        assertEquals(2L, number("campaign", "state_revision"));
        assertEquals(1L, number("campaign", "game_turn"));
        assertEquals(60L, number("campaign", "world_time_seconds"));
        assertEquals(4L, number("campaign", "event_sequence"));
        assertEquals(3, count("canonical_outbox"));
        assertNull(jdbc.queryForObject("select pending_decision from campaign", String.class));
        var before = snapshot();
        assertEquals(result, commits.commit(plan));
        assertEquals(before, snapshot());
        var c = plan.context();
        var changedPayload = new ResolvedCommit(new ResolvedCommit.Context(c.adventureId(), c.campaignId(),
                c.operationId(), c.contentRevisionId(), "d".repeat(64), c.expectedRevision(),
                c.expectedOperationVersion()), plan.world(), plan.resources(), plan.events(), plan.rewards(), "{}", true);
        assertThrows(IllegalStateException.class, () -> commits.commit(changedPayload));
        assertEquals(before, snapshot());
    }

    /** Failure in any persisted component leaves the exact accepted baseline, with no partial effects. */
    @ParameterizedTest
    @ValueSource(strings = {"campaign", "actor_resource", "campaign_event", "reward_settlement",
            "operation_journal", "canonical_outbox", "deferred_commit"})
    void rollsBackEveryWriteStageIncludingCommitTime(String stage) {
        var before = snapshot();
        installFailure(stage);
        var failure = assertThrows(RuntimeException.class, () -> commits.commit(plan(fixture, fixture.operation())));
        assertTrue(rootMessage(failure).contains("p033 injected failure"), rootMessage(failure));
        assertEquals(before, snapshot());
    }

    /** Simultaneous retries return one identical durable receipt and create exactly one effect bundle. */
    @Test
    void concurrentDuplicatesCommitOnce() throws Exception {
        var plan = plan(fixture, fixture.operation());
        var results = race(() -> commits.commit(plan), () -> commits.commit(plan));
        assertInstanceOf(CanonicalCommitService.Receipt.class, results.get(0));
        assertEquals(results.get(0), results.get(1));
        assertEquals(1L, number("campaign", "state_revision"));
        assertEquals(7L, number("actor_resource", "current_value"));
        assertEquals(2, count("campaign_event"));
        assertEquals(2, count("canonical_outbox"));
        assertEquals(1, count("reward_settlement"));
    }

    /** Two distinct operations from the same revision cannot both commit, even with concurrent entry. */
    @Test
    void competingOperationsRejectTheStaleLoser() throws Exception {
        var second = operation(fixture.campaign(), 0);
        var results = race(() -> commits.commit(plan(fixture, fixture.operation())),
                () -> commits.commit(plan(fixture, second)));
        assertEquals(1L, results.stream().filter(CanonicalCommitService.Receipt.class::isInstance).count());
        assertEquals(1L, results.stream().filter(IllegalStateException.class::isInstance).count());
        assertEquals(1L, number("campaign", "state_revision"));
        assertEquals(7L, number("actor_resource", "current_value"));
        assertEquals(1, count("reward_settlement"));
        assertEquals(2, count("canonical_outbox"));
        assertEquals(1, jdbc.queryForObject("select count(*) from operation_journal where status = 'VALIDATING'",
                Integer.class));
    }

    /** Reject wrong provenance, stale tokens, changed payloads, and cross-campaign identities atomically. */
    @ParameterizedTest
    @ValueSource(strings = {"adventure", "operation", "content", "revision", "operationVersion", "payload",
            "resource", "actor", "causation", "resourceVersion", "bounds", "cancelled", "malformedReceipt"})
    void refusesInvalidPlansWithoutAnyWrites(String fault) {
        Fixture other = createFixture();
        var plan = plan(fixture, fixture.operation());
        var c = plan.context();
        if (Set.of("adventure", "operation", "content", "revision", "operationVersion", "payload").contains(fault)) {
            c = new ResolvedCommit.Context(fault.equals("adventure") ? other.campaign().getAdventureId() : c.adventureId(),
                    c.campaignId(), fault.equals("operation") ? other.operation().getId() : c.operationId(),
                    fault.equals("content") ? other.campaign().getContentRevisionId() : c.contentRevisionId(),
                    fault.equals("payload") ? "d".repeat(64) : c.payloadHash(),
                    fault.equals("revision") ? 1 : c.expectedRevision(),
                    fault.equals("operationVersion") ? 1 : c.expectedOperationVersion());
        }
        var changes = plan.resources();
        var events = plan.events();
        String details = plan.receiptDetails();
        switch (fault) {
            case "resource" -> changes = List.of(new ResolvedCommit.ResourceChange(other.resource().getId(), 0, -1));
            case "resourceVersion" -> changes = List.of(new ResolvedCommit.ResourceChange(fixture.resource().getId(), 1, -1));
            case "bounds" -> changes = List.of(new ResolvedCommit.ResourceChange(fixture.resource().getId(), 0, -11));
            case "actor" -> events = List.of(new ResolvedCommit.Event(other.resource().getActorId(), null,
                    EventVisibility.PUBLIC, new CampaignEvent.Body("test", 1, 1, "{}")));
            case "causation" -> {
                commits.commit(plan(other, other.operation()));
                UUID cause = jdbc.queryForObject("select id from campaign_event where campaign_id = ? limit 1",
                        UUID.class, other.campaign().getId());
                events = List.of(new ResolvedCommit.Event(null, cause, EventVisibility.PUBLIC,
                        new CampaignEvent.Body("test", 1, 1, "{}")));
            }
            case "cancelled" -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                var op = entities.find(CampaignOperation.class, fixture.operation().getId());
                op.transitionTo(OperationStatus.CANCELLED, NOW);
            });
            case "malformedReceipt" -> details = "{invalid";
            default -> { /* Context mutations above need no additional effect changes. */ }
        }
        var invalid = new ResolvedCommit(c, plan.world(), changes, events, plan.rewards(), details, true);
        var before = snapshot();
        assertThrows(RuntimeException.class, () -> commits.commit(invalid));
        assertEquals(before, snapshot());
    }

    /** A repeated source/recipient settlement rolls back the entire later operation, including its cost. */
    @Test
    void duplicateRewardRollsBackLaterState() {
        commits.commit(plan(fixture, fixture.operation()));
        var next = operation(fixture.campaign(), 1);
        var original = plan(fixture, next);
        long version = number("actor_resource", "row_version");
        var duplicate = new ResolvedCommit(original.context(), original.world(),
                List.of(new ResolvedCommit.ResourceChange(fixture.resource().getId(), version, -2)),
                original.events(), original.rewards(), "{}", true);
        var before = snapshot();
        assertThrows(RuntimeException.class, () -> commits.commit(duplicate));
        assertEquals(before, snapshot());
    }

    /** No outer transaction may delay durability; the service dependency surface admits only local DB/JSON work. */
    @Test
    void rejectsAmbientTransactionsAndHasNoNetworkOrCallbackDependency() {
        var before = snapshot();
        new TransactionTemplate(manager).executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> commits.commit(plan(fixture, fixture.operation()))));
        assertEquals(before, snapshot());
        for (var field : CanonicalCommitService.class.getDeclaredFields()) {
            assertTrue(Set.of(EntityManager.class, TransactionTemplate.class, JsonMapper.class).contains(field.getType()),
                    "Review transaction dependency: " + field);
        }
    }

    /** A writer after the resource read invalidates the whole bundle, even after event/outbox INSERTs executed. */
    @Test
    void interveningResourceWriterRollsBackFlushedBundle() throws Exception {
        // Hold a session advisory lock solely to pause the service at its outbox INSERT,
        // after resource reads and before Hibernate's resource UPDATE. No timing guess
        // decides the race: pg_stat_activity confirms the worker is blocked at the gate.
        jdbc.execute("create function p033_fail() returns trigger language plpgsql as $$ begin "
                + "perform pg_advisory_xact_lock(330033); return new; end; $$");
        jdbc.execute("create trigger p033_fault before insert on canonical_outbox "
                + "for each row execute function p033_fail()");
        var pool = Executors.newSingleThreadExecutor();
        try (var gate = dataSource.getConnection(); var statement = gate.createStatement()) {
            statement.execute("select pg_advisory_lock(330033)");
            try {
                var worker = pool.submit(() -> {
                    try {
                        return (Object) commits.commit(plan(fixture, fixture.operation()));
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                boolean waiting;
                do {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity "
                            + "where wait_event = 'advisory' and query like 'insert into canonical_outbox%')", Boolean.class));
                    if (!waiting) {
                        Thread.sleep(10);
                    }
                } while (!waiting && System.nanoTime() < deadline);
                assertTrue(waiting, "worker must reach the outbox gate before the intervening write");
                assertEquals(1, jdbc.update("update actor_resource set current_value = 9, row_version = row_version + 1 "
                        + "where id = ? and row_version = 0", fixture.resource().getId()));
                var baselineWithExternalWrite = snapshot();
                statement.execute("select pg_advisory_unlock(330033)");
                Object outcome = worker.get(10, TimeUnit.SECONDS);
                assertInstanceOf(jakarta.persistence.OptimisticLockException.class, outcome);
                assertEquals(baselineWithExternalWrite, snapshot());
            } finally {
                // Session locks survive a rollback, so always release before returning
                // this connection to the pool and before waiting for worker shutdown.
                statement.execute("select pg_advisory_unlock(330033)");
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    /** Deferred references reject uncommitted/wrong-revision/cross-campaign envelopes and duplicates stay unique. */
    @Test
    void outboxDatabaseGuardsCommitReferencesAndImmutability() {
        assertThrows(RuntimeException.class, () -> insertOutbox(fixture.campaign().getId(), fixture.operation().getId(), 1, "PUBLIC_STATE"));
        var plan = plan(fixture, fixture.operation());
        commits.commit(new ResolvedCommit(plan.context(), plan.world(), plan.resources(), plan.events(),
                plan.rewards(), plan.receiptDetails(), false));
        var before = snapshot();
        // Use the absent kind for the FK checks so uniqueness cannot mask a broken FK.
        assertThrows(RuntimeException.class, () -> insertOutbox(fixture.campaign().getId(), fixture.operation().getId(), 2, "NARRATION_REQUIRED"));
        assertThrows(RuntimeException.class, () -> insertOutbox(UUID.randomUUID(), fixture.operation().getId(), 1, "NARRATION_REQUIRED"));
        assertThrows(RuntimeException.class, () -> insertOutbox(fixture.campaign().getId(), fixture.operation().getId(), 1, "PUBLIC_STATE"));
        assertThrows(RuntimeException.class, () -> jdbc.update("update canonical_outbox set created_at = now()"));
        assertThrows(RuntimeException.class, () -> jdbc.update("delete from canonical_outbox"));
        assertEquals(before, snapshot());
    }

    /** Insert a forged intent through raw SQL; deferred FK/uniqueness violations propagate at commit. */
    private void insertOutbox(UUID campaign, UUID operation, long revision, String kind) {
        new TransactionTemplate(manager).executeWithoutResult(status -> jdbc.update(
                "insert into canonical_outbox values (?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), campaign, operation, revision, kind));
    }

    /** Construct a fresh independent owner/adventure/content/campaign/actor/resource and validating operation. */
    private Fixture createFixture() {
        return new TransactionTemplate(manager).execute(status -> {
            var owner = OwnerProfile.create("Owner", NOW);
            entities.persist(owner);
            var adventure = AdventureWorkspace.create(owner.getId(), "Neutral", "neutral", SupportedLocale.EN, NOW);
            entities.persist(adventure);
            var content = ContentRevision.admit(adventure.getId(),
                    new ContentRevision.PackCoordinates("neutral", "1", "a".repeat(64), 1, SupportedLocale.EN),
                    new ContentRevision.RulesetCoordinates("core", "1", "b".repeat(64)), NOW);
            entities.persist(content);
            var campaign = Campaign.start(adventure.getId(), content, "Campaign", "seed", NOW);
            entities.persist(campaign);
            var actor = Actor.create(campaign.getId(), ActorKind.CHARACTER, "hero", "Hero", null, 0, NOW);
            entities.persist(actor);
            var resource = ActorResource.create(actor, "stamina", 0, 10, 10, 0, NOW);
            entities.persist(resource);
            return new Fixture(campaign, resource, operation(campaign, 0));
        });
    }

    /** Persist a new command at base revision, already resolved through VALIDATING; return its detached identity. */
    private CampaignOperation operation(Campaign campaign, long base) {
        return new TransactionTemplate(manager).execute(status -> {
            var operation = CampaignOperation.accept(campaign.getId(), UUID.randomUUID().toString(), HASH, "explore", base, NOW);
            operation.transitionTo(OperationStatus.PLANNING, NOW);
            operation.transitionTo(OperationStatus.VALIDATING, NOW);
            entities.persist(operation);
            return operation;
        });
    }

    /** Return a bounded resolved action with a cost, public/private events, reward, decision and narration intent. */
    private static ResolvedCommit plan(Fixture fixture, CampaignOperation operation) {
        return new ResolvedCommit(new ResolvedCommit.Context(fixture.campaign().getAdventureId(),
                fixture.campaign().getId(), operation.getId(), fixture.campaign().getContentRevisionId(), HASH,
                operation.getBaseRevision(), operation.getOperationVersion()),
                new ResolvedCommit.WorldChange(true, 60, CampaignMode.EXPLORATION, "{\"kind\":\"choose\"}"),
                List.of(new ResolvedCommit.ResourceChange(fixture.resource().getId(), 0, -3)),
                List.of(new ResolvedCommit.Event(fixture.resource().getActorId(), null, EventVisibility.PUBLIC,
                                new CampaignEvent.Body("explored", 1, 1, "{}")),
                        new ResolvedCommit.Event(null, null, EventVisibility.INTERNAL,
                                new CampaignEvent.Body("clue", 1, 1, "{\"secret\":\"hidden-clue\"}"))),
                List.of(new ResolvedCommit.Reward("site:first", "hero", "{\"visited\":true}")),
                "{\"cost\":3}", true);
    }

    /** Capture every affected row, including storage versions, for exact rollback comparisons. */
    private List<List<String>> snapshot() {
        return TABLES.stream().map(table -> jdbc.queryForList("select to_jsonb(t)::text from "
                + table + " t order by id", String.class)).toList();
    }

    /** Read one numeric fixture column; callers use constant table/column names and one-row fixtures. */
    private long number(String table, String column) {
        return jdbc.queryForObject("select " + column + " from " + table, Long.class);
    }

    /** Return a committed row count from a constant test table; SQL errors fail the test. */
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /**
     * Install a test-only SQL failure at a selected stage. The deferred case verifies
     * all six components exist inside the transaction before failing at COMMIT.
     */
    private void installFailure(String stage) {
        boolean deferred = stage.equals("deferred_commit");
        String check = deferred ? "if (select count(*) from canonical_outbox) <> 2 "
                + "or (select count(*) from campaign_event) <> 2 "
                + "or (select count(*) from reward_settlement) <> 1 "
                + "or (select state_revision from campaign) <> 1 "
                + "or (select current_value from actor_resource) <> 7 "
                + "or (select status from operation_journal) <> 'COMMITTED' then "
                + "raise exception 'incomplete test bundle'; end if; " : "";
        jdbc.execute("create function p033_fail() returns trigger language plpgsql as $$ begin " + check
                + "raise exception 'p033 injected failure' using errcode = '23514'; end; $$");
        if (deferred) {
            jdbc.execute("create constraint trigger p033_fault after insert on canonical_outbox "
                    + "deferrable initially deferred for each row execute function p033_fail()");
        } else {
            jdbc.execute("create trigger p033_fault before insert or update on " + stage
                    + " for each row execute function p033_fail()");
        }
    }

    /** Return the deepest exception message so wrapper types cannot hide the intended failure reason. */
    private static String rootMessage(Throwable failure) {
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure.getMessage();
    }

    /** Run two bounded concurrent calls; return receipts or expected runtime failures, never hide infrastructure errors. */
    private static List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<Object>>();
            for (var work : List.of(first, second)) {
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        return work.call();
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(tasks.get(0).get(15, TimeUnit.SECONDS), tasks.get(1).get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
        }
    }

    /** Detached fixture identities; each test owns its rows and never shares mutable entity state between threads. */
    private record Fixture(Campaign campaign, ActorResource resource, CampaignOperation operation) {
    }
}
