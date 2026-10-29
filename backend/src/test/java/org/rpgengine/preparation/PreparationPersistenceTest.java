package org.rpgengine.preparation;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.presentation.CampaignPresentationSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercise durable preparation against real PostgreSQL with separate committed calls.
 * Each campaign pins a sealed registered sample pack, so the P07.6 content and author
 * checks run against real compiled content. Fixtures contain no runtime accounts or
 * external work. SQL faults and independent workers prove rollback and fencing;
 * infrastructure failures fail rather than skip.
 */
@SpringBootTest
class PreparationPersistenceTest {
    private static final String HASH = "a".repeat(64);
    private static final Duration LEASE = Duration.ofSeconds(120);
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private PreparationJobService jobs;
    @Autowired private PlanningSnapshotService snapshots;
    @Autowired private PlanAdmissionService admissions;
    @Autowired private JobRecoveryService recovery;
    @Autowired private PackRegistry registry;
    @Autowired private PlanContentCatalogs catalogs;
    private Campaign campaign;
    // Author-constraint digest of the pinned sample pack, recomputed by the production catalog.
    private String authorHash;

    /** Construct the injected PostgreSQL fixture; missing infrastructure fails startup. */
    PreparationPersistenceTest() {
    }

    /** Build committed, independently scoped campaign data before each test. */
    @BeforeEach
    void prepare() {
        clear();
        campaign = fixture();
        authorHash = catalogs.preload(campaign.getId()).catalog().authorConstraintHash();
    }

    /** Remove only disposable fixture rows and this class's failure trigger. */
    @AfterEach
    void clear() {
        jdbc.execute("drop trigger if exists p036_fault on plan_admission");
        jdbc.execute("drop trigger if exists p036_fault on ai_job");
        jdbc.execute("drop function if exists p036_fail()");
        jdbc.execute("truncate item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, "
                + "ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, "
                + "operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, "
                + "reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, content_revision, adventure_workspace, identity_owner_profile");
    }

    /** Same logical requests race to one durable row and workers cannot share an attempt. */
    @Test
    void deduplicatesConcurrentEnqueueAndClaim() throws Exception {
        var request = request("initial");
        var results = race(() -> jobs.enqueue(campaign.getAdventureId(), campaign.getId(), request),
                () -> jobs.enqueue(campaign.getAdventureId(), campaign.getId(), request));
        assertEquals(results.getFirst().jobId(), results.getLast().jobId());
        assertNotEquals(results.getFirst().created(), results.getLast().created());
        var claims = race(() -> jobs.claim(UUID.randomUUID(), LEASE), () -> jobs.claim(UUID.randomUUID(), LEASE));
        assertEquals(1, claims.stream().filter(java.util.Optional::isPresent).count());
        assertEquals(1, count("ai_job_attempt"));
        assertEquals(0L, number("select state_revision from campaign"));
        assertEquals(0, count("canonical_outbox"));
    }

    /** Captured bytes have reproducible digests, immutable storage and checked assembler tokens. */
    @Test
    void snapshotsAreImmutableAndRejectStaleOrForeignInputs() {
        var dependencies = List.of(query(0, PlanAdmissionService.Severity.ARTIFACT));
        var snapshot = snapshot(dependencies);
        assertEquals(snapshot.contextDigest(), snapshot(dependencies).contextDigest());
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "update planning_snapshot set selected_state = '{}' where id = ?", snapshot.id()));
        assertThrows(RuntimeException.class, () -> jdbc.update("delete from planning_snapshot where id = ?", snapshot.id()));
        assertThrows(IllegalArgumentException.class, () -> snapshots.capture(UUID.randomUUID(), campaign.getId(), input(dependencies)));
        bump();
        assertThrows(IllegalStateException.class, () -> snapshot(dependencies));
        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1");
        assertThrows(IllegalStateException.class, () -> snapshot(List.of()));
        assertEquals(0, count("campaign_event"));
    }

    /** A missing/negative query remains an engine dependency; a result cannot erase it. */
    @Test
    void rejectsOmittedDependenciesAndPhantomInsertEpoch() {
        var dependencies = List.of(query(0, PlanAdmissionService.Severity.ARTIFACT));
        var lease = result("initial", dependencies);
        assertThrows(IllegalArgumentException.class, () -> admissions.admit(lease, proposal(UUID.randomUUID(), 1, List.of())));
        bump();
        var proposal = proposal(UUID.randomUUID(), 1, dependencies);
        assertEquals("STALE_QUERY_EPOCH", admissions.admit(lease, proposal).reason());
        assertEquals("STALE_QUERY_EPOCH", admissions.admit(lease, proposal).reason());
        assertEquals(1L, number("select regeneration_count from ai_job"));
        assertEquals(0, count("quest_plan_artifact"));
        var retry = jobs.claim(UUID.randomUUID(), LEASE).orElseThrow();
        assertTrue(retry.fence() > lease.fence());
        assertEquals(2, retry.attempt());
        assertThrows(IllegalStateException.class, () -> jobs.heartbeat(lease, LEASE));
        jobs.bindSnapshot(retry, snapshot(List.of(query(1, PlanAdmissionService.Severity.ARTIFACT))).id());
        jobs.start(retry, LEASE, 1, "thread-2", "turn-2");
        jobs.recordResult(retry, HASH, 2);
        bump();
        assertEquals("STALE_QUERY_EPOCH", admissions.admit(retry,
                proposal(UUID.randomUUID(), 1, List.of(query(1, PlanAdmissionService.Severity.ARTIFACT)))).reason());
        assertEquals("FAILED", text("select status from ai_job"));
        assertEquals(1L, number("select regeneration_count from ai_job"));
    }

    /** Semantic predicates without evaluators must never be labelled validated by storage admission. */
    @Test
    void unsupportedSemanticDependenciesFailClosed() {
        var dependencies = List.of(PlanAdmissionService.Dependency.version(PlanAdmissionService.Kind.FACT,
                "fact:gate-open", 0, PlanAdmissionService.Severity.ARTIFACT, "[]"));
        var lease = result("initial", dependencies);
        assertEquals("UNSUPPORTED_DEPENDENCY", admissions.admit(lease, proposal(UUID.randomUUID(), 1, dependencies)).reason());
        assertEquals(0, count("plan_admission"));
    }

    /** A commit-time failure leaves no partial artifact, dependency, receipt, disposition or finished job. */
    @Test
    void admissionRollsBackAtDeferredCommitAndCanRetry() {
        var dependencies = List.of(query(0, PlanAdmissionService.Severity.ARTIFACT));
        var lease = result("initial", dependencies);
        var proposal = proposal(UUID.randomUUID(), 1, dependencies);
        jdbc.execute("create function p036_fail() returns trigger language plpgsql as $$ begin "
                + "if (select count(*) from plan_dependency) <> 1 or (select status from ai_job) <> 'COMPLETED' "
                + "then raise exception 'incomplete test bundle'; end if; "
                + "raise exception 'p036 injected commit failure'; end; $$");
        jdbc.execute("create constraint trigger p036_fault after insert on plan_admission "
                + "deferrable initially deferred for each row execute function p036_fail()");
        assertThrows(RuntimeException.class, () -> admissions.admit(lease, proposal));
        for (String table : List.of("quest_plan_artifact", "plan_dependency", "plan_admission", "plan_disposition")) {
            assertEquals(0, count(table));
        }
        assertEquals("RESULT_RECEIVED", text("select status from ai_job"));
        assertNull(text("select outcome from ai_job_attempt"));
        jdbc.execute("drop trigger p036_fault on plan_admission");
        assertEquals(PlanAdmissionService.Outcome.ACCEPTED, admissions.admit(lease, proposal).outcome());
    }

    /** Duplicate workers get one receipt, and later versions supersede metadata without touching the world. */
    @Test
    void admitsOnceAndVersionsAppendImmutableRecords() throws Exception {
        var dependencies = List.of(query(0, PlanAdmissionService.Severity.BRANCH));
        var lease = result("initial", dependencies);
        var plan = UUID.randomUUID();
        var proposal = proposal(plan, 1, dependencies);
        var before = text("select to_jsonb(c)::text from campaign c");
        var receipts = race(() -> admissions.admit(lease, proposal), () -> admissions.admit(lease, proposal));
        assertEquals(receipts.getFirst().admissionId(), receipts.getLast().admissionId());
        assertNotEquals(receipts.getFirst().outcome(), receipts.getLast().outcome());
        assertEquals(before, text("select to_jsonb(c)::text from campaign c"));
        assertEquals(0, count("operation_random_draw"));
        assertEquals(0, count("campaign_event"));
        assertEquals(1, count("plan_admission"));
        assertThrows(RuntimeException.class, () -> jdbc.update("update quest_plan_artifact set body = '{}'"));
        assertThrows(RuntimeException.class, () -> jdbc.update("delete from plan_dependency"));
        var refinement = result("refine", dependencies);
        assertEquals(PlanAdmissionService.Outcome.ACCEPTED, admissions.admit(refinement, proposal(plan, 2, dependencies)).outcome());
        assertEquals("SUPERSEDED", text("select status from plan_disposition where artifact_id = '"
                + receipts.getFirst().artifactId() + "' order by sequence desc limit 1"));
        assertEquals(2, count("quest_plan_artifact"));
        bump();
        assertEquals(1, admissions.invalidateStale(campaign.getId()));
        assertEquals(0, admissions.invalidateStale(campaign.getId()));
        assertEquals(1L, number("select count(*) from plan_disposition where status = 'PARTIALLY_INVALIDATED'"));
    }

    /** Cancellation wins before admission and old worker callbacks cannot spend or publish. */
    @Test
    void cancellationFencesOutLateResults() {
        var lease = result("initial", List.of());
        assertEquals(JobStatus.CANCEL_REQUESTED, jobs.requestCancel(campaign.getId(), lease.jobId()));
        assertThrows(IllegalStateException.class, () -> admissions.admit(lease, proposal(UUID.randomUUID(), 1, List.of())));
        jobs.confirmCancelled(lease);
        assertThrows(IllegalStateException.class, () -> jobs.recordResult(lease, HASH, 1));
        assertEquals(JobStatus.CANCELLED, jobs.requestCancel(campaign.getId(), lease.jobId()));
        assertTrue(jobs.claim(UUID.randomUUID(), LEASE).isEmpty());
        assertEquals(0, count("plan_admission"));
    }

    /** Lease expiry requires explicit upstream stop evidence; startup never resumes unknown work. */
    @Test
    void restartReconcilesExpiredWorkersBeforeRequeue() {
        var lease = result("initial", List.of());
        jdbc.update("update ai_job set lease_expires_at = clock_timestamp() - interval '1 second', row_version = row_version + 1");
        var restarted = new JobRecoveryService(jdbc, manager);
        var worker = new PreparationJobService(jdbc, manager, restarted);
        var admission = new PlanAdmissionService(jdbc, manager, restarted, catalogs);
        assertThrows(IllegalStateException.class, () -> worker.claim(UUID.randomUUID(), LEASE));
        assertThrows(IllegalStateException.class, () -> admission.admit(lease, proposal(UUID.randomUUID(), 1, List.of())));
        assertEquals(1, restarted.reconcile());
        assertEquals(0, restarted.reconcile());
        assertEquals("RECONCILIATION_REQUIRED", text("select status from ai_job"));
        assertEquals("INTERRUPTED", text("select outcome from ai_job_attempt"));
        assertTrue(worker.claim(UUID.randomUUID(), LEASE).isEmpty());
        assertEquals(JobStatus.QUEUED, worker.confirmStopped(campaign.getId(), lease.jobId()));
        var fresh = worker.claim(UUID.randomUUID(), LEASE).orElseThrow();
        assertEquals(2, fresh.attempt());
        assertThrows(IllegalStateException.class, () -> worker.heartbeat(lease, LEASE));
    }

    /** Restored generation retires callbacks, dedup identities and old snapshots even at the same revision. */
    @Test
    void generationRetiresOldWorkAndAllowsFreshIdentity() {
        var lease = result("initial", List.of());
        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1");
        assertThrows(IllegalStateException.class, () -> admissions.admit(lease, proposal(UUID.randomUUID(), 1, List.of())));
        assertThrows(IllegalStateException.class, () -> jobs.heartbeat(lease, LEASE));
        assertEquals(1, recovery.reconcile());
        assertEquals("SUPERSEDED", text("select status from ai_job"));
        var fresh = jobs.enqueue(campaign.getAdventureId(), campaign.getId(), request("initial"));
        assertTrue(fresh.created());
        assertNotEquals(lease.jobId(), fresh.jobId());
        var newLease = jobs.claim(UUID.randomUUID(), LEASE).orElseThrow();
        UUID oldSnapshot = jdbc.queryForObject("select id from planning_snapshot", UUID.class);
        assertThrows(IllegalStateException.class, () -> jobs.bindSnapshot(newLease, oldSnapshot));
        assertEquals(0L, number("select state_revision from campaign"));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "update campaign set recovery_generation = 0, row_version = row_version + 1"));
    }

    /** The database and services reject snapshot identifiers belonging to another campaign. */
    @Test
    void crossCampaignSnapshotsAndAutocommitHelpersFailClosed() {
        var other = fixture();
        var foreign = snapshots.capture(other.getAdventureId(), other.getId(), input(List.of()));
        jobs.enqueue(campaign.getAdventureId(), campaign.getId(), request("initial"));
        var lease = jobs.claim(UUID.randomUUID(), LEASE).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> jobs.bindSnapshot(lease, foreign.id()));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "update ai_job set snapshot_id = ?, row_version = row_version + 1", foreign.id()));
        assertThrows(IllegalStateException.class, () -> QueryEpochs.bump(jdbc, campaign.getId(), "quest:gate"));
        assertThrows(IllegalStateException.class, () -> PreparationJobService.enqueueWithin(jdbc,
                campaign.getAdventureId(), campaign.getId(), null, request("other")));
    }

    /** Locale preferences persist independently; only public text becomes stale after a locale switch. */
    @Test
    void localeChangesRetirePublicTextWithoutAdvancingWorld() {
        new TransactionTemplate(manager).executeWithoutResult(status -> entities.persist(
                CampaignPresentationSettings.create(campaign.getId(), SupportedLocale.EN, NOW)));
        var privateLease = result("private", List.of());
        jobs.enqueue(campaign.getAdventureId(), campaign.getId(), request("public"));
        var publicLease = jobs.claim(UUID.randomUUID(), LEASE).orElseThrow();
        var publicSnapshot = snapshots.capture(campaign.getAdventureId(), campaign.getId(),
                new PlanningSnapshotService.SnapshotRequest(Audience.PUBLIC, "{}", "[]", List.of(), authorHash, HASH, 0, 0, 0, 0));
        jobs.bindSnapshot(publicLease, publicSnapshot.id());
        jobs.start(publicLease, LEASE, 0, "public-thread", "public-turn");
        jobs.recordResult(publicLease, HASH, 0);
        var before = text("select to_jsonb(c)::text from campaign c");
        new TransactionTemplate(manager).executeWithoutResult(status -> entities.find(
                CampaignPresentationSettings.class, campaign.getId()).changeNarrativeLocale(SupportedLocale.IT, NOW));
        assertEquals(before, text("select to_jsonb(c)::text from campaign c"));
        assertEquals("it", text("select narrative_locale from presentation_campaign_settings"));
        assertThrows(IllegalStateException.class, () -> snapshot(List.of()));
        assertEquals(PlanAdmissionService.Outcome.ACCEPTED,
                admissions.admit(privateLease, proposal(UUID.randomUUID(), 1, List.of())).outcome());
        assertEquals("STALE_PRESENTATION", admissions.admit(publicLease, proposal(UUID.randomUUID(), 1, List.of())).reason());
        assertEquals(0, count("campaign_event"));
    }

    /** Relevant epochs govern admission: unrelated global revisions do not discard usable preparation. */
    @Test
    void unrelatedWorldChangeDoesNotInvalidatePlan() {
        var dependencies = List.of(query(0, PlanAdmissionService.Severity.ARTIFACT));
        var lease = result("initial", dependencies);
        jdbc.update("update campaign set state_revision = state_revision + 1, row_version = row_version + 1");
        new TransactionTemplate(manager).executeWithoutResult(status -> QueryEpochs.bump(jdbc, campaign.getId(), "region:other"));
        assertEquals(PlanAdmissionService.Outcome.ACCEPTED, admissions.admit(lease, proposal(UUID.randomUUID(), 1, dependencies)).outcome());
        assertEquals(1L, number("select checked_revision from plan_admission"));
        assertEquals(0, admissions.invalidateStale(campaign.getId()));
    }

    /** Competing attempts cannot both create a version even when their job identities differ. */
    @Test
    void concurrentPlanVersionsHaveOneWinner() throws Exception {
        var first = result("initial", List.of());
        var second = result("competing", List.of());
        var proposal = proposal(UUID.randomUUID(), 1, List.of());
        var results = race(() -> admissions.admit(first, proposal), () -> admissions.admit(second, proposal));
        assertEquals(1, results.stream().filter(value -> value.outcome() == PlanAdmissionService.Outcome.ACCEPTED).count());
        assertEquals(1, results.stream().filter(value -> "PLAN_VERSION_CONFLICT".equals(value.reason())).count());
        assertEquals(1, count("quest_plan_artifact"));
        assertEquals(1, count("plan_admission"));
    }

    /** An epoch change racing admission either rejects the result or invalidates the accepted artifact atomically. */
    @Test
    void admissionAndEpochWriterCannotLeaveAStaleValidatedArtifact() throws Exception {
        var dependencies = List.of(query(0, PlanAdmissionService.Severity.ARTIFACT));
        var lease = result("initial", dependencies);
        var proposal = proposal(UUID.randomUUID(), 1, dependencies);
        race(() -> (Object) admissions.admit(lease, proposal), () -> {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                QueryEpochs.bump(jdbc, campaign.getId(), "quest:gate");
                PlanAdmissionService.invalidateWithin(jdbc, campaign.getId(), 0);
            });
            return "changed";
        });
        assertEquals(0L, number("select count(*) from quest_plan_artifact a cross join lateral "
                + "(select status from plan_disposition where artifact_id = a.id order by sequence desc limit 1) d "
                + "where d.status = 'VALIDATED'"));
    }

    /** Retry limits persist across new service instances; live leases are not stolen by startup reconciliation. */
    @Test
    void retryBudgetAndLiveLeaseSurviveRestart() {
        jobs.enqueue(campaign.getAdventureId(), campaign.getId(), request("initial"));
        for (int attempt = 1; attempt <= 3; attempt++) {
            var lease = jobs.claim(UUID.randomUUID(), LEASE).orElseThrow();
            assertEquals(attempt, lease.attempt());
            var restarted = new JobRecoveryService(jdbc, manager);
            assertEquals(0, restarted.reconcile());
            var worker = new PreparationJobService(jdbc, manager, restarted);
            assertTrue(worker.claim(UUID.randomUUID(), LEASE).isEmpty());
            assertEquals(attempt < 3 ? JobStatus.QUEUED : JobStatus.FAILED, worker.fail(lease, "TEST_FAILURE", true));
        }
        assertEquals(3, count("ai_job_attempt"));
        assertTrue(jobs.claim(UUID.randomUUID(), LEASE).isEmpty());
        assertThrows(RuntimeException.class, () -> jdbc.update("update ai_job set status = 'QUEUED', row_version = row_version + 1"));
        assertThrows(RuntimeException.class, () -> jdbc.update("update ai_job_attempt set outcome = 'ACCEPTED'"));
    }

    /** A reconciliation write failure leaves its gate closed and rolls back the interrupted attempt. */
    @Test
    void recoveryFailureAbortsStartupWithoutPartialOutcome() {
        result("initial", List.of());
        jdbc.update("update ai_job set lease_expires_at = clock_timestamp() - interval '1 second', row_version = row_version + 1");
        jdbc.execute("create function p036_fail() returns trigger language plpgsql as $$ begin "
                + "raise exception 'p036 recovery failure'; end; $$");
        jdbc.execute("create trigger p036_fault before update on ai_job for each row execute function p036_fail()");
        var restarted = new JobRecoveryService(jdbc, manager);
        assertThrows(RuntimeException.class, () -> new JobStartupReconciliation(restarted).afterSingletonsInstantiated());
        assertFalse(restarted.isReconciled());
        assertEquals("RESULT_RECEIVED", text("select status from ai_job"));
        assertNull(text("select outcome from ai_job_attempt"));
        jdbc.execute("drop trigger p036_fault on ai_job");
        assertEquals(1, restarted.reconcile());
        assertTrue(restarted.isReconciled());
    }

    /** A queued job whose immutable lifetime elapses is expired without ever opening an attempt. */
    @Test
    void expiredQueueEntryCannotBeClaimed() {
        jobs.enqueue(campaign.getAdventureId(), campaign.getId(), new JobRequest("QUEST_PLAN", "quest:gate",
                "short", "v1", HASH, HASH, HASH, HASH, 10, false, Duration.ZERO, Duration.ofSeconds(1), 1, 0, 0));
        // Use the same database clock as lease/deadline checks; this waits only 1.1 seconds.
        jdbc.execute("select pg_sleep(1.1)");
        assertTrue(jobs.claim(UUID.randomUUID(), LEASE).isEmpty());
        assertEquals(1, recovery.reconcile());
        assertEquals("EXPIRED", text("select status from ai_job"));
        assertEquals(0, count("ai_job_attempt"));
    }

    /**
     * Persist one owner/adventure, register the sealed sample pack for it and start a campaign
     * pinned to that revision; exceptions roll back each step's transaction.
     */
    private Campaign fixture() {
        var adventure = new TransactionTemplate(manager).execute(status -> {
            var owner = OwnerProfile.create("Owner", NOW);
            entities.persist(owner);
            var created = AdventureWorkspace.create(owner.getId(), "Neutral", "neutral", SupportedLocale.EN, NOW);
            entities.persist(created);
            return created.getId();
        });
        UUID revision;
        try {
            revision = registry.register(adventure, new PackStaging(PackStaging.Limits.standard()).directory(
                    Path.of(getClass().getResource("/packs/fantasy-exploration").toURI()))).revisionId();
        } catch (java.net.URISyntaxException failure) {
            throw new IllegalStateException(failure);
        }
        return new TransactionTemplate(manager).execute(status -> {
            var created = Campaign.start(adventure, entities.find(ContentRevision.class, revision), "Campaign",
                    "seed", NOW);
            entities.persist(created);
            return created;
        });
    }

    /** Return a bounded retryable quest job; purposes distinguish independent logical requests. */
    private JobRequest request(String purpose) {
        return new JobRequest("QUEST_PLAN", "quest:gate", purpose, "v1", HASH, HASH, HASH, HASH,
                10, false, Duration.ZERO, Duration.ofHours(1), 3, 10, 10);
    }

    /** Snapshot fixture input asserts initial world/presentation tokens and retains every read dependency. */
    private PlanningSnapshotService.SnapshotRequest input(List<PlanAdmissionService.Dependency> dependencies) {
        return new PlanningSnapshotService.SnapshotRequest(Audience.PLANNER_PRIVATE, "{}", "[]", dependencies,
                authorHash, HASH, 0, 0, 0, 0);
    }

    /** Capture a committed snapshot; stale dependency epochs deliberately propagate to the test. */
    private PlanningSnapshotService.Snapshot snapshot(List<PlanAdmissionService.Dependency> dependencies) {
        return snapshots.capture(campaign.getAdventureId(), campaign.getId(), input(dependencies));
    }

    /** Represent an empty query result whose scope epoch must still detect later matching inserts. */
    private PlanAdmissionService.Dependency query(long epoch, PlanAdmissionService.Severity severity) {
        return PlanAdmissionService.Dependency.query("available-gates", "{}", Audience.PLANNER_PRIVATE,
                "quest:gate", epoch, HASH, severity, "[\"approach\"]");
    }

    /** Run local lease/snapshot/result transitions, returning a live result ready for admission. */
    private PreparationJobService.Lease result(String purpose, List<PlanAdmissionService.Dependency> dependencies) {
        jobs.enqueue(campaign.getAdventureId(), campaign.getId(), request(purpose));
        var lease = jobs.claim(UUID.randomUUID(), LEASE).orElseThrow();
        jobs.bindSnapshot(lease, snapshot(dependencies).id());
        jobs.start(lease, LEASE, 0, "thread", "turn");
        jobs.recordResult(lease, HASH, 2);
        jobs.recordResult(lease, HASH, 2);
        assertEquals(2L, jdbc.queryForObject("select observed_usage from ai_job where id = ?", Long.class, lease.jobId()));
        return lease;
    }

    /** Build one trusted proposal with the recorded digest and explicit version lineage. */
    private PlanAdmissionService.Proposal proposal(UUID plan, int version, List<PlanAdmissionService.Dependency> dependencies) {
        return new PlanAdmissionService.Proposal(plan, version, "quest:gate", "v1", "{}", dependencies,
                "[\"approach\"]", "[]", "[]", "v1", HASH);
    }

    /** Change a query scope atomically; no canonical counters are advanced by this metadata fixture. */
    private void bump() {
        new TransactionTemplate(manager).executeWithoutResult(status -> QueryEpochs.bump(jdbc, campaign.getId(), "quest:gate"));
    }

    /** Count a constant fixture table; SQL errors fail the caller. */
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** Read one numeric result from test-owned SQL, propagating malformed fixtures. */
    private long number(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    /** Read one nullable text result from test-owned SQL, propagating malformed fixtures. */
    private String text(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    /** Start two independent transactions together and propagate every failure within a bounded timeout. */
    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<T>>();
            for (var work : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return work.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.getFirst().get(15, TimeUnit.SECONDS), futures.getLast().get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
        }
    }
}
