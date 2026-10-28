package org.rpgengine.commands;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL checks for P07.1 durable admission: idempotent replay, payload
 * conflicts, stale revisions, busy campaigns, permit expiry with fencing, owner scope,
 * the owner-wide admission limit and concurrent duplicates. Every call crosses a real
 * transaction boundary and every assertion reads committed rows through JDBC.
 */
@SpringBootTest
class OperationAdmissionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    @Autowired private OperationAdmissionService admissions;
    @Autowired private CommandRuleCatalogs catalogs;
    @Autowired private MutationLeaseService leases;
    @Autowired private CanonicalCommitService commits;
    @Autowired private RandomDrawService draws;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private Scope scope;

    /** Construct the injected fixture; missing infrastructure fails context startup. */
    OperationAdmissionServiceTest() {
    }

    /** Start from an empty disposable database with one owner, adventure and campaign. */
    @BeforeEach
    void prepare() {
        clear();
        scope = createScope();
    }

    /** Remove all rows owned by this suite, also after a failed assertion. */
    @AfterEach
    void clear() {
        jdbc.execute("drop trigger if exists p071_fault on campaign_mutation_lease");
        jdbc.execute("drop function if exists p071_fail()");
        jdbc.execute("truncate presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, identity_owner_profile");
    }

    /** A new command is journaled ACCEPTED and owns a fresh fenced permit in the same commit. */
    @Test
    void admissionJournalsOperationAndGrantsPermitAtomically() {
        var admission = submit(scope.campaign(), command("r1", 0, Map.of("seconds", 60)));
        assertFalse(admission.replayed());
        var view = admission.operation();
        assertEquals(OperationStatus.ACCEPTED, view.status());
        assertEquals(0L, view.baseRevision());
        assertEquals("WAIT", view.commandType());
        assertNotNull(admission.lease());
        assertEquals(1L, admission.lease().token().fence());
        assertEquals(view.operationId(), jdbc.queryForObject(
                "select operation_id from campaign_mutation_lease where campaign_id = ?", UUID.class, scope.campaign()));
        assertEquals(admission.lease().token().holderId(), jdbc.queryForObject(
                "select holder_id from campaign_mutation_lease where campaign_id = ?", UUID.class, scope.campaign()));
        String hash = jdbc.queryForObject("select payload_hash from operation_journal where id = ?", String.class,
                view.operationId());
        assertEquals(CommandCanonicalForm.sha256(CommandCanonicalForm.encode("WAIT", 0, Map.of("seconds", 60),
                OperationAdmissionService.MAX_CANONICAL_BYTES)), hash);
        // The returned permit is a real MutationLeaseService token usable by the worker.
        assertTrue(leases.release(scope.adventure(), scope.campaign(), view.operationId(), admission.lease().token()));
    }

    /** Identical retries replay before stale/busy checks, even after the campaign revision moved on. */
    @Test
    void identicalRetryReplaysWithoutWritesAfterRevisionChange() {
        var first = submit(scope.campaign(), command("r1", 0, Map.of("seconds", 60)));
        bumpRevision(scope.campaign());
        var replay = submit(scope.campaign(), command("r1", 0, Map.of("seconds", 60)));
        assertTrue(replay.replayed());
        assertNull(replay.lease());
        assertEquals(first.operation().operationId(), replay.operation().operationId());
        assertEquals(1, count("operation_journal"));
        assertEquals(1L, jdbc.queryForObject("select fence from campaign_mutation_lease", Long.class));
    }

    /** Reusing a request identity for a different canonical command is a conflict, not a replay. */
    @Test
    void changedCommandUnderSameRequestIsConflict() {
        submit(scope.campaign(), new OperationAdmissionService.Command("r1", 0, "WAIT", Map.of("seconds", java.math.BigInteger.TEN)));
        // Integer 10 and decimal 10.0 are different JSON values for the typed validator.
        assertFailure(OperationAdmissionFailure.Kind.REQUEST_CONFLICT,
                () -> submit(scope.campaign(), new OperationAdmissionService.Command("r1", 0, "WAIT", Map.of("seconds", new java.math.BigDecimal("10.0")))));
        assertFailure(OperationAdmissionFailure.Kind.REQUEST_CONFLICT, () -> submit(scope.campaign(),
                new OperationAdmissionService.Command("r1", 0, "MOVE", Map.of("seconds", java.math.BigInteger.TEN))));
        assertEquals(1, count("operation_journal"));
    }

    /** A command built on an old revision is rejected and nothing is journaled or leased. */
    @Test
    void staleRevisionIsRejectedWithoutJournal() {
        bumpRevision(scope.campaign());
        assertFailure(OperationAdmissionFailure.Kind.STALE_REVISION,
                () -> submit(scope.campaign(), command("r1", 0, Map.of())));
        assertEquals(0, count("operation_journal"));
        assertEquals(0, count("campaign_mutation_lease"));
        assertFalse(submit(scope.campaign(), command("r2", 1, Map.of())).replayed());
    }

    /** A live permit makes the campaign busy; the second command is rejected, never queued. */
    @Test
    void liveOperationMakesCampaignBusy() {
        submit(scope.campaign(), command("r1", 0, Map.of()));
        assertFailure(OperationAdmissionFailure.Kind.CAMPAIGN_BUSY,
                () -> submit(scope.campaign(), command("r2", 0, Map.of())));
        assertEquals(1, count("operation_journal"));
        // The rejected identity stays unused and can be admitted once the campaign is free.
        expire(scope.campaign());
        assertFalse(submit(scope.campaign(), command("r2", 0, Map.of())).replayed());
    }

    /** An expired permit is fenced off and its pre-commit operation fails before the next admission. */
    @Test
    void expiredPermitIsRetiredAndAbandonedWorkFails() {
        var old = submit(scope.campaign(), command("r1", 0, Map.of()));
        expire(scope.campaign());
        var next = submit(scope.campaign(), command("r2", 0, Map.of()));
        // Fence 1 grant, +1 expiry retirement, +1 new grant: the old token can never pass again.
        assertEquals(3L, next.lease().token().fence());
        assertEquals("FAILED_BEFORE_COMMIT", jdbc.queryForObject(
                "select status from operation_journal where id = ?", String.class, old.operation().operationId()));
        assertEquals(1L, jdbc.queryForObject("select operation_version from operation_journal where id = ?",
                Long.class, old.operation().operationId()));
        assertThrows(RuntimeException.class, () -> leases.renew(scope.adventure(), scope.campaign(),
                old.operation().operationId(), old.lease().token(), Duration.ofSeconds(10)));
        // A replay of the failed request reports its terminal state instead of reviving it.
        var replay = submit(scope.campaign(), command("r1", 0, Map.of()));
        assertTrue(replay.replayed());
        assertEquals(OperationStatus.FAILED_BEFORE_COMMIT, replay.operation().status());
    }

    /**
     * One admission must classify expiry at one database instant. Force the old lease to
     * lapse between cleanup and the busy query: admitting a successor here would strand
     * the old journal in ACCEPTED with no permit. All SQL still uses the real transaction;
     * this JDBC failpoint only advances the deadline boundary at the vulnerable statement.
     */
    @Test
    void expiryBetweenRetirementAndBusyCheckCannotStrandOldOperation() {
        var old = submit(scope.campaign(), command("old", 0, Map.of()));
        var before = snapshot();
        var boundary = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())) {
            @Override
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                if (sql.startsWith("select exists(select 1 from campaign_mutation_lease")) {
                    // The captured admission time precedes this deadline, but actual time
                    // passes it before the query. No wall-clock scheduling race is required.
                    jdbc.update("update campaign_mutation_lease set expires_at = clock_timestamp() "
                            + "+ interval '1 millisecond' where campaign_id = ?", scope.campaign());
                    jdbc.execute("select pg_sleep(0.02)");
                }
                return super.queryForObject(sql, type, args);
            }
        };
        var service = new OperationAdmissionService(entities, boundary, manager, catalogs);
        assertFailure(OperationAdmissionFailure.Kind.CAMPAIGN_BUSY,
                () -> service.submit(scope.owner(), scope.adventure(), scope.campaign(),
                        command("next", 0, Map.of())));
        assertEquals(before, snapshot());
        // A later admission may take over, but it must retire the old journal atomically.
        expire(scope.campaign());
        assertFalse(submit(scope.campaign(), command("next", 0, Map.of())).replayed());
        assertEquals(OperationStatus.FAILED_BEFORE_COMMIT,
                admissions.status(scope.owner(), scope.adventure(), scope.campaign(), old.operation().operationId()).status());
    }

    /** A released permit also leaves no live owner, so the ACCEPTED row is treated as abandoned. */
    @Test
    void releasedPermitWithoutCommitIsRetired() {
        var old = submit(scope.campaign(), command("r1", 0, Map.of()));
        assertTrue(leases.release(scope.adventure(), scope.campaign(), old.operation().operationId(),
                old.lease().token()));
        submit(scope.campaign(), command("r2", 0, Map.of()));
        assertEquals("FAILED_BEFORE_COMMIT", jdbc.queryForObject(
                "select status from operation_journal where id = ?", String.class, old.operation().operationId()));
    }

    /** Foreign owners, adventures, campaigns and operations are indistinguishable from absence. */
    @Test
    void foreignScopeIsUnavailable() {
        var admitted = submit(scope.campaign(), command("r1", 0, Map.of()));
        Scope other = createScope();
        var command = command("r9", 0, Map.of());
        assertFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE,
                () -> admissions.submit(other.owner(), scope.adventure(), scope.campaign(), command));
        assertFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE,
                () -> admissions.submit(scope.owner(), other.adventure(), scope.campaign(), command));
        assertFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE,
                () -> admissions.submit(UUID.randomUUID(), scope.adventure(), scope.campaign(), command));
        assertFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE,
                () -> admissions.submit(scope.owner(), scope.adventure(), UUID.randomUUID(), command));
        UUID operation = admitted.operation().operationId();
        assertFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE,
                () -> admissions.status(other.owner(), scope.adventure(), scope.campaign(), operation));
        assertFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE,
                () -> admissions.status(scope.owner(), scope.adventure(), other.campaign(), operation));
        assertFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE,
                () -> admissions.status(scope.owner(), scope.adventure(), scope.campaign(), UUID.randomUUID()));
        assertEquals(1, count("operation_journal"));
    }

    /** The owner-wide live-permit cap returns a limit outcome until a permit lapses. */
    @Test
    void ownerAdmissionLimitBoundsParallelCampaigns() {
        UUID second = addCampaign(scope, "seed-2");
        UUID third = addCampaign(scope, "seed-3");
        submit(scope.campaign(), command("r1", 0, Map.of()));
        submit(second, command("r1", 0, Map.of()));
        assertFailure(OperationAdmissionFailure.Kind.ADMISSION_LIMIT, () -> submit(third, command("r1", 0, Map.of())));
        // Another owner's activity never counts against this owner.
        Scope other = createScope();
        assertFalse(admissions.submit(other.owner(), other.adventure(), other.campaign(),
                command("r1", 0, Map.of())).replayed());
        expire(scope.campaign());
        assertFalse(submit(third, command("r1", 0, Map.of())).replayed());
        assertEquals(4, count("operation_journal"));
    }

    /** Status lookup returns the durable view without changing any row. */
    @Test
    void statusReadsCurrentOperation() {
        var admitted = submit(scope.campaign(), command("r1", 0, Map.of()));
        var before = snapshot();
        var view = admissions.status(scope.owner(), scope.adventure(), scope.campaign(),
                admitted.operation().operationId());
        assertEquals(admitted.operation(), view);
        assertEquals(0L, jdbc.queryForObject("select row_version from operation_journal", Long.class));
        assertEquals(before, snapshot());
    }

    /** Concurrent identical submissions serialize on the campaign lock and create exactly one row. */
    @Test
    void concurrentDuplicatesCreateOneOperation() throws Exception {
        int callers = 6;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<OperationAdmissionService.Admission>> tasks = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                tasks.add(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return submit(scope.campaign(), command("same", 0, Map.of("seconds", 61)));
                });
            }
            var futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            List<OperationAdmissionService.Admission> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, results.stream().filter(result -> !result.replayed()).count());
            Set<UUID> ids = results.stream().map(result -> result.operation().operationId()).collect(Collectors.toSet());
            assertEquals(1, ids.size());
            assertEquals(1, count("operation_journal"));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Admission refuses to join a caller transaction, because its permit must be durable on return. */
    @Test
    void rejectsAmbientTransaction() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(status ->
                submit(scope.campaign(), command("r1", 0, Map.of()))));
        assertEquals(0, count("operation_journal"));
    }

    /** Envelope and canonical-form validation fail before any database access. */
    @Test
    void invalidEnvelopesAreRejectedBeforeStorage() {
        assertThrows(IllegalArgumentException.class, () -> command("r1", -1, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationAdmissionService.Command("r1", 0, "move", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationAdmissionService.Command("x".repeat(129), 0, "MOVE", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> submit(scope.campaign(), command("r1", 0, Map.of("text", "\uD800"))));
        assertThrows(IllegalArgumentException.class,
                () -> submit(scope.campaign(), command("r1", 0, Map.of("text", "x".repeat(40_000)))));
        assertEquals(0, count("operation_journal"));
    }

    /** Internal reacquisition must not bypass the same owner cap enforced on new commands. */
    @Test
    void internalReacquisitionCannotBypassOwnerLimit() {
        var old = submit(scope.campaign(), command("old", 0, Map.of()));
        expire(scope.campaign());
        UUID second = addCampaign(scope, "second");
        UUID third = addCampaign(scope, "third");
        submit(second, command("second", 0, Map.of()));
        submit(third, command("third", 0, Map.of()));
        assertFailure(OperationAdmissionFailure.Kind.ADMISSION_LIMIT,
                () -> leases.acquire(scope.adventure(), scope.campaign(), old.operation().operationId(),
                        UUID.randomUUID(), Duration.ofSeconds(180)));
        assertEquals(2, jdbc.queryForObject("select count(*) from campaign_mutation_lease "
                + "where expires_at > clock_timestamp()", Integer.class));
    }

    /** Simultaneous conflicting payloads can neither create two rows nor silently share one result. */
    @Test
    void concurrentChangedPayloadHasOneWinnerAndOneConflict() throws Exception {
        var results = race(
                () -> submit(scope.campaign(), command("same", 0, Map.of("seconds", 62))),
                () -> submit(scope.campaign(), command("same", 0, Map.of("seconds", 63))));
        assertEquals(1, results.stream().filter(OperationAdmissionService.Admission.class::isInstance).count());
        assertEquals(1, results.stream().filter(OperationAdmissionFailure.Kind.REQUEST_CONFLICT::equals).count());
        assertEquals(1, count("operation_journal"));
        assertEquals(1, count("campaign_mutation_lease"));
    }

    /** A revision ahead of current state is just as stale as an old one; neither reserves an identity. */
    @Test
    void futureRevisionIsRejectedExactly() {
        var before = snapshot();
        assertFailure(OperationAdmissionFailure.Kind.STALE_REVISION,
                () -> submit(scope.campaign(), command("future", 1, Map.of())));
        assertEquals(before, snapshot());
    }

    /** Owner capacity spans distinct adventures and serializes simultaneous admission attempts. */
    @Test
    void concurrentCrossAdventureAdmissionsRespectOwnerCap() throws Exception {
        submit(scope.campaign(), command("first", 0, Map.of()));
        Scope second = addAdventure(scope.owner());
        Scope third = addAdventure(scope.owner());
        var results = race(
                () -> admissions.submit(second.owner(), second.adventure(), second.campaign(), command("r", 0, Map.of())),
                () -> admissions.submit(third.owner(), third.adventure(), third.campaign(), command("r", 0, Map.of())));
        assertEquals(1, results.stream().filter(OperationAdmissionService.Admission.class::isInstance).count());
        assertEquals(1, results.stream().filter(OperationAdmissionFailure.Kind.ADMISSION_LIMIT::equals).count());
        assertEquals(2, count("operation_journal"));
        assertEquals(2, livePermits());
    }

    /** Legacy/expired worker reacquisition and a fresh command compete for the same final owner slot. */
    @Test
    void concurrentReacquisitionAndAdmissionShareCapacity() throws Exception {
        var old = submit(scope.campaign(), command("old", 0, Map.of()));
        expire(scope.campaign());
        Scope second = addAdventure(scope.owner());
        Scope third = addAdventure(scope.owner());
        admissions.submit(second.owner(), second.adventure(), second.campaign(), command("r", 0, Map.of()));
        var results = race(
                () -> leases.acquire(scope.adventure(), scope.campaign(), old.operation().operationId(),
                        UUID.randomUUID(), Duration.ofSeconds(180)),
                () -> admissions.submit(third.owner(), third.adventure(), third.campaign(), command("r", 0, Map.of())));
        assertEquals(1, results.stream().filter(OperationAdmissionFailure.Kind.ADMISSION_LIMIT::equals).count());
        assertEquals(2, livePermits());
        assertFalse(leases.release(scope.adventure(), scope.campaign(), old.operation().operationId(),
                new MutationLeaseService.Token(UUID.randomUUID(), old.lease().token().fence())));
    }

    /** At the cap, live retries and renewals retain their existing slot and replay retains no capability. */
    @Test
    void liveRetryAndRenewalAtCapDoNotAllocateAnotherSlot() {
        var first = submit(scope.campaign(), command("first", 0, Map.of()));
        UUID second = addCampaign(scope, "second");
        submit(second, command("second", 0, Map.of()));
        var before = snapshot();
        assertEquals(first.lease(), leases.acquire(scope.adventure(), scope.campaign(), first.operation().operationId(),
                first.lease().token().holderId(), Duration.ofSeconds(300)));
        var replay = submit(scope.campaign(), command("first", 0, Map.of()));
        assertTrue(replay.replayed());
        assertNull(replay.lease());
        assertEquals(before, snapshot());
        var renewed = leases.renew(scope.adventure(), scope.campaign(), first.operation().operationId(),
                first.lease().token(), Duration.ofSeconds(300));
        assertEquals(first.lease().token(), renewed.token());
        assertTrue(renewed.expiresAt().isAfter(first.lease().expiresAt()));
        assertEquals(2, livePermits());
        expire(scope.campaign());
        UUID third = addCampaign(scope, "third");
        submit(third, command("third", 0, Map.of()));
        assertThrows(IllegalStateException.class, () -> leases.renew(scope.adventure(), scope.campaign(),
                first.operation().operationId(), first.lease().token(), Duration.ofSeconds(300)));
        assertEquals(2, livePermits());
    }

    /**
     * Force expiry while two real transactions contend for the owner lock, in both
     * acquisition orders. A renewal that started while live must recheck database time
     * after waiting; even a renewal already holding the owner lock cannot revive the
     * expired slot. The competing campaign may reuse that slot exactly once.
     *
     * <p>The gate pauses immediately after the first owner lock, before either caller
     * locks a campaign. The test connection commits expiry only after PostgreSQL reports
     * the second caller blocked, avoiding timing guesses about which transaction won.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiryDuringRenewalAdmissionContentionCannotExceedCapacity(boolean renewalFirst) throws Exception {
        var old = submit(scope.campaign(), command("old", 0, Map.of()));
        submit(addCampaign(scope, "occupied"), command("occupied", 0, Map.of()));
        UUID third = addCampaign(scope, "contender");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var contenderPid = new AtomicInteger();
        var firstJdbc = ownerLockBoundary(() -> {}, () -> {
            locked.countDown();
            awaitGate(release);
        });
        var secondJdbc = ownerLockBoundary(() -> contenderPid.set(
                jdbc.queryForObject("select pg_backend_pid()", Integer.class)), () -> {});
        var renewing = new MutationLeaseService(renewalFirst ? firstJdbc : secondJdbc, manager);
        var admitting = new OperationAdmissionService(entities, renewalFirst ? secondJdbc : firstJdbc, manager,
                catalogs);
        Callable<Object> renewal = () -> {
            var failure = assertThrows(IllegalStateException.class, () -> renewing.renew(scope.adventure(),
                    scope.campaign(), old.operation().operationId(), old.lease().token(), Duration.ofSeconds(300)));
            assertEquals("mutation lease lost", failure.getMessage());
            return failure;
        };
        Callable<Object> admission = () -> admitting.submit(scope.owner(), scope.adventure(), third,
                command("contender", 0, Map.of()));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(renewalFirst ? renewal : admission);
            awaitGate(locked);
            var second = pool.submit(renewalFirst ? admission : renewal);
            // The normal production lock timeout remains 2s. Observe an actual wait
            // within 1s; unexpected failures/timeouts fail this test rather than skip it.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                if (contenderPid.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                        "select cardinality(pg_blocking_pids(?)) > 0", Boolean.class, contenderPid.get()))) {
                    blocked = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(blocked, "second transaction must wait on the owner lock before expiry");
            expire(scope.campaign());
            release.countDown();
            Object firstResult = first.get(10, TimeUnit.SECONDS);
            Object secondResult = second.get(10, TimeUnit.SECONDS);
            var accepted = (OperationAdmissionService.Admission) (renewalFirst ? secondResult : firstResult);
            assertFalse(accepted.replayed());
            assertEquals(2, livePermits());
            assertEquals(3, count("operation_journal"));
            assertEquals(old.lease().token().fence(), jdbc.queryForObject(
                    "select fence from campaign_mutation_lease where campaign_id = ?", Long.class, scope.campaign()));
            // Capacity checks do not retire another campaign's journal or renew its permit.
            assertEquals(old.operation(), admissions.status(scope.owner(), scope.adventure(),
                    scope.campaign(), old.operation().operationId()));
            assertEquals(0, count("campaign_event"));
            assertEquals(0, count("operation_random_draw"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** Execute bounded test gates around the real owner-row lock on the transaction's connection. */
    private JdbcTemplate ownerLockBoundary(Runnable before, Runnable after) {
        return new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())) {
            @Override
            public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                boolean ownerLock = sql.startsWith("select id from identity_owner_profile");
                if (ownerLock) {
                    before.run();
                }
                List<T> result = super.query(sql, mapper, args);
                if (ownerLock) {
                    after.run();
                }
                return result;
            }
        };
    }

    /** Fail a stalled/interrupted test gate promptly while preserving the worker's interrupt flag. */
    private static void awaitGate(CountDownLatch gate) {
        try {
            assertTrue(gate.await(5, TimeUnit.SECONDS), "owner-lock test gate timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("owner-lock test gate interrupted", failure);
        }
    }

    /** Fail both at lease SQL and at deferred COMMIT: no journal, permit or cleanup write may escape. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void leaseFailureRollsBackJournalAndRetirement(boolean deferred) {
        var first = submit(scope.campaign(), command("old", 0, Map.of()));
        expire(scope.campaign());
        var before = snapshot();
        jdbc.execute("create function p071_fail() returns trigger language plpgsql as $$ begin "
                + "if NEW.operation_id is not null then raise exception 'P071 injected lease failure'; end if; "
                + "return NEW; end $$");
        jdbc.execute(deferred
                ? "create constraint trigger p071_fault after insert or update on campaign_mutation_lease "
                        + "deferrable initially deferred for each row execute function p071_fail()"
                : "create trigger p071_fault before insert or update on campaign_mutation_lease "
                        + "for each row execute function p071_fail()");
        assertThrows(RuntimeException.class, () -> submit(scope.campaign(), command("next", 0, Map.of())));
        assertEquals(before, snapshot());
        jdbc.execute("drop trigger p071_fault on campaign_mutation_lease");
        var next = submit(scope.campaign(), command("next", 0, Map.of()));
        assertFalse(next.replayed());
        assertNotEquals(first.operation().operationId(), next.operation().operationId());
        assertEquals(OperationStatus.FAILED_BEFORE_COMMIT,
                admissions.status(scope.owner(), scope.adventure(), scope.campaign(), first.operation().operationId()).status());
    }

    /** Admitted tokens feed the real draw/commit boundary; expiry and takeover forbid new old-worker effects. */
    @Test
    void expiredAdmittedWorkerCannotDrawOrCommitAfterTakeover() {
        var admitted = submit(scope.campaign(), command("old", 0, Map.of()));
        var plan = resolve(admitted);
        var draw = draws.reserve(plan.context(), 0, 20);
        expire(scope.campaign());
        var next = submit(scope.campaign(), command("next", 0, Map.of()));
        var before = snapshot();
        assertThrows(IllegalStateException.class, () -> draws.reserve(plan.context(), 1, 20));
        assertThrows(IllegalStateException.class, () -> commits.commit(plan));
        assertEquals(draw, draws.reserve(plan.context(), 0, 20));
        assertEquals(before, snapshot());
        assertTrue(next.lease().token().fence() > admitted.lease().token().fence());
        assertEquals(0, count("campaign_event"));
    }

    /** Real startup preserves one committed outcome, retires expired work and never revives a retried identity. */
    @Test
    void applicationRestartReplaysStoredOutcomeAndRecoversAbandonedAdmission() {
        var request = command("committed", 0, Map.of("seconds", 60));
        var admitted = submit(scope.campaign(), request);
        var plan = resolve(admitted);
        var draw = draws.reserve(plan.context(), 0, 20);
        var receipt = commits.commit(plan);
        String world = jdbc.queryForObject("select to_jsonb(c)::text from campaign c where id = ?",
                String.class, scope.campaign());
        UUID second = addCampaign(scope, "abandoned");
        var abandoned = submit(second, command("abandoned", 0, Map.of()));
        expire(second);
        UUID third = addCampaign(scope, "live");
        var live = submit(third, command("live", 0, Map.of()));
        try (var restarted = new org.springframework.boot.builder.SpringApplicationBuilder(org.rpgengine.EngineApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).run()) {
            var service = restarted.getBean(OperationAdmissionService.class);
            var before = snapshot();
            var replay = service.submit(scope.owner(), scope.adventure(), scope.campaign(), request);
            assertTrue(replay.replayed());
            assertNull(replay.lease());
            assertEquals(admitted.operation().operationId(), replay.operation().operationId());
            // Since P07.5 owed narration survives a restart as a queued presentation job, not a pending mark.
            assertEquals(OperationStatus.COMMITTED, replay.operation().status());
            assertEquals("QUEUED", jdbc.queryForObject("select status from presentation_job where operation_id = ?",
                    String.class, admitted.operation().operationId()));
            assertEquals(1L, replay.operation().committedRevision());
            assertEquals(receipt, restarted.getBean(CanonicalCommitService.class).commit(plan));
            assertEquals(draw, restarted.getBean(RandomDrawService.class).reserve(plan.context(), 0, 20));
            assertEquals(OperationStatus.FAILED_BEFORE_COMMIT,
                    service.status(scope.owner(), scope.adventure(), second, abandoned.operation().operationId()).status());
            assertEquals(OperationStatus.ACCEPTED,
                    service.status(scope.owner(), scope.adventure(), third, live.operation().operationId()).status());
            assertEquals(before, snapshot());
            assertEquals(world, jdbc.queryForObject("select to_jsonb(c)::text from campaign c where id = ?",
                    String.class, scope.campaign()));
            assertEquals(1, count("campaign_event"));
            assertEquals(1, count("reward_settlement"));
            assertEquals(2, count("canonical_outbox"));
        }
    }

    /** Resolve a neutral admitted action through the existing lifecycle, without introducing an execution worker. */
    private ResolvedCommit resolve(OperationAdmissionService.Admission admitted) {
        var view = admitted.operation();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var operation = entities.find(CampaignOperation.class, view.operationId());
            operation.transitionTo(OperationStatus.PLANNING, Instant.now());
            operation.transitionTo(OperationStatus.VALIDATING, Instant.now());
        });
        String hash = jdbc.queryForObject("select payload_hash from operation_journal where id = ?",
                String.class, view.operationId());
        return new ResolvedCommit(new ResolvedCommit.Context(scope.adventure(), scope.campaign(), view.operationId(),
                scope.content(), hash, 0, 2, admitted.lease().token()),
                new ResolvedCommit.WorldChange(true, 60, CampaignMode.EXPLORATION, null), List.of(),
                List.of(new ResolvedCommit.Event(null, null, EventVisibility.PUBLIC,
                        new CampaignEvent.Body("explored", 1, 1, "{}"))),
                List.of(new ResolvedCommit.Reward("door:first", "party", "{\"visited\":true}")), "{}", true, ResolvedCommit.Preparation.NONE,
                new CommandIntent(scope.owner(), jdbc.queryForObject("select idempotency_key from operation_journal where id = ?",
                        String.class, view.operationId()), 0, new TypedCommand.Wait(60)));
    }

    /** Start two actual database transactions together; unexpected failures propagate through their futures. */
    private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(call -> pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return call.call();
                } catch (OperationAdmissionFailure failure) {
                    return failure.kind();
                }
            })).toList();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    /** Snapshot counters, journal versions/outcomes, leases, draws and canonical effects for exact no-write checks. */
    private Map<String, String> snapshot() {
        var rows = new LinkedHashMap<String, String>();
        for (String table : List.of("campaign", "operation_journal", "campaign_mutation_lease", "campaign_event",
                "campaign_random_seed", "operation_random_draw", "reward_settlement", "canonical_outbox")) {
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return rows;
    }

    /** Count database-clock live permits across this suite's owners. */
    private int livePermits() {
        return jdbc.queryForObject("select count(*) from campaign_mutation_lease where expires_at > clock_timestamp()",
                Integer.class);
    }

    /** Add an independent adventure/content/campaign under an existing owner to exercise owner-wide limits. */
    private Scope addAdventure(UUID owner) {
        return new TransactionTemplate(manager).execute(status -> {
            var adventure = AdventureWorkspace.create(owner, "Other", "neutral", SupportedLocale.EN, NOW);
            entities.persist(adventure);
            var content = ContentRevision.admit(adventure.getId(),
                    new ContentRevision.PackCoordinates("neutral", "1", "a".repeat(64), 1, SupportedLocale.EN),
                    new ContentRevision.RulesetCoordinates("core", "1", "b".repeat(64)), NOW);
            entities.persist(content);
            var campaign = Campaign.start(adventure.getId(), content, "Campaign", "seed", NOW);
            entities.persist(campaign);
            addPlayer(campaign);
            return new Scope(owner, adventure.getId(), campaign.getId(), content.getId());
        });
    }

    /** Submit through the primary owner and adventure. */
    private OperationAdmissionService.Admission submit(UUID campaign, OperationAdmissionService.Command command) {
        return admissions.submit(scope.owner(), scope.adventure(), campaign, command);
    }

    /**
     * Build a WAIT command; tests vary only the request identity, revision and payload. WAIT
     * consults no pinned definitions, so these admission mechanics need no sealed pack fixture.
     */
    private static OperationAdmissionService.Command command(String request, long revision, Map<String, Object> payload) {
        return new OperationAdmissionService.Command(request, revision, "WAIT",
                payload.isEmpty() ? Map.of("seconds", 60) : payload);
    }

    /** Assert one expected admission outcome. */
    private static void assertFailure(OperationAdmissionFailure.Kind kind, org.junit.jupiter.api.function.Executable call) {
        assertEquals(kind, assertThrows(OperationAdmissionFailure.class, call).kind());
    }

    /** Simulate a committed turn by advancing the campaign revision under the row-version rule. */
    private void bumpRevision(UUID campaign) {
        jdbc.update("update campaign set state_revision = state_revision + 1, row_version = row_version + 1 "
                + "where id = ?", campaign);
    }

    /** Move a live permit into the past, as if its worker died. */
    private void expire(UUID campaign) {
        jdbc.update("update campaign_mutation_lease set expires_at = clock_timestamp() - interval '1 second' "
                + "where campaign_id = ?", campaign);
    }

    /** Count committed rows of one suite-owned table. */
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** Persist an owner, adventure, content revision and campaign in one committed transaction. */
    private Scope createScope() {
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
            addPlayer(campaign);
            return new Scope(owner.getId(), adventure.getId(), campaign.getId(), content.getId());
        });
    }

    /** Add another campaign to an existing adventure and content revision. */
    private UUID addCampaign(Scope owner, String seed) {
        return new TransactionTemplate(manager).execute(status -> {
            var campaign = Campaign.start(owner.adventure(), entities.find(ContentRevision.class, owner.content()),
                    "Campaign", seed, NOW);
            entities.persist(campaign);
            addPlayer(campaign);
            return campaign.getId();
        });
    }

    /** Attach the immutable creation player required by typed action eligibility. */
    private void addPlayer(Campaign campaign) {
        var player = org.rpgengine.characters.Actor.create(campaign.getId(),
                org.rpgengine.characters.ActorKind.CHARACTER, null, "Player", null, 0, NOW);
        entities.persist(player);
        entities.flush();
        jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, "
                        + "profile_id, choices, created_at) values (?, ?, ?, ?, ?, 'neutral', '{}'::jsonb, now())",
                campaign.getAdventureId(), UUID.randomUUID(), "a".repeat(64), campaign.getId(), player.getId());
    }

    /** Detached identities of one owned campaign scope. */
    private record Scope(UUID owner, UUID adventure, UUID campaign, UUID content) {
    }
}
