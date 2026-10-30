package org.rpgengine.inference.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.inference.session.SessionScope;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PostgreSQL proof of the P08.6 scheduler (QA-37 deterministic part) through the real
 * {@link JdbcTaskLedger}, V14 guards, {@link ScopedSessions} and scheduler, with only the runtime
 * replaced by {@link ControlledPort}: durable transitions, one turn at a time across processes,
 * interactive preemption with the permit kept until the terminal result, fair campaign rotation,
 * separate bounded queue places, rolling quotas and an account backoff that survive a restart,
 * cancellation of waiting and running work, queue deadlines, reconciliation holds for unconfirmed
 * outcomes, restart recovery with write-ahead runtime references, campaign scope and generation
 * fences, durable task identities, shutdown, and the database lifecycle guards.
 */
@SpringBootTest
class InferenceSchedulerTest {
    static final String PACK = "a".repeat(64);
    static final String POLICY = "b".repeat(64);
    static final String SCHEMA = "{\"type\":\"object\"}";

    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private TaskLedger ledger;
    private final MutableClock clock = new MutableClock();
    private final List<InferenceScheduler> schedulers = new ArrayList<>();
    private final List<ControlledPort> ports = new ArrayList<>();
    private ControlledPort port;
    private UUID adventure;
    private UUID otherAdventure;
    private UUID campaignA;
    private UUID campaignB;
    private UUID campaignC;
    private int counter;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    InferenceSchedulerTest() {
    }

    /** Commit one owner, two adventures and three campaigns of the first adventure. */
    @BeforeEach
    void prepare() {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Instant now = Instant.now();
            var profile = OwnerProfile.create("Owner", now);
            entities.persist(profile);
            var workspace = AdventureWorkspace.create(profile.getId(), "Scheduling", "scheduling", SupportedLocale.EN,
                    now);
            entities.persist(workspace);
            adventure = workspace.getId();
            var other = AdventureWorkspace.create(profile.getId(), "Other", "other", SupportedLocale.EN, now);
            entities.persist(other);
            otherAdventure = other.getId();
            var content = ContentRevision.admit(adventure, new ContentRevision.PackCoordinates("neutral", "1",
                    PACK, 1, SupportedLocale.EN), new ContentRevision.RulesetCoordinates("core", "1",
                    "c".repeat(64)), now);
            entities.persist(content);
            List<UUID> campaigns = new ArrayList<>();
            for (String title : List.of("A", "B", "C")) {
                var started = Campaign.start(adventure, content, title, "seed-" + title, now);
                entities.persist(started);
                campaigns.add(started.getId());
            }
            campaignA = campaigns.get(0);
            campaignB = campaigns.get(1);
            campaignC = campaigns.get(2);
            entities.flush();
        });
        port = newPort();
    }

    /** Stop every scheduler (confirming interrupts at once) and remove disposable rows. */
    @AfterEach
    void stopAll() {
        ports.forEach(p -> p.autoInterrupt = true);
        schedulers.forEach(InferenceScheduler::stop);
        clear();
    }

    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_event, presentation_job, campaign_stream_message, "
                + "campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, "
                + "item_instance, learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, "
                + "quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, "
                + "operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, "
                + "campaign_event, operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
        jdbc.update("delete from inference_scheduler_state");
    }

    ControlledPort newPort() {
        var created = new ControlledPort();
        ports.add(created);
        return created;
    }

    static SchedulerSettings settings(int interactiveCapacity, int interactivePerCampaign, int backgroundCapacity,
            int backgroundPerCampaign, long daily, long backgroundDaily) {
        return new SchedulerSettings(1, interactiveCapacity, interactivePerCampaign, backgroundCapacity,
                backgroundPerCampaign, daily, backgroundDaily, Duration.ofSeconds(60), Duration.ofSeconds(30),
                Duration.ofMinutes(15), Duration.ofMillis(10));
    }

    static SchedulerSettings defaults() {
        var d = SchedulerSettings.defaults();
        return settings(d.interactiveCapacity(), d.interactivePerCampaign(), d.backgroundCapacity(),
                d.backgroundPerCampaign(), d.dailyUsageLimit(), d.backgroundDailyUsageLimit());
    }

    /** A recovered and started scheduler over the real ledger, as one backend process. */
    InferenceScheduler process(ControlledPort runtime, SchedulerSettings settings) {
        var scheduler = new InferenceScheduler(new ScopedSessions(runtime, 64), runtime, ledger, settings, clock,
                () -> 0.5);
        scheduler.recover();
        scheduler.start();
        schedulers.add(scheduler);
        return scheduler;
    }

    InferenceScheduler process() {
        return process(port, defaults());
    }

    ScheduledTask task(UUID campaign, AiRole role, WorkClass workClass) {
        return task(adventure, campaign, 0, role, workClass, Duration.ofSeconds(60));
    }

    ScheduledTask task(UUID adventureId, UUID campaign, long generation, AiRole role, WorkClass workClass,
            Duration timeout) {
        Audience audience = role.permits(Audience.PUBLIC) ? Audience.PUBLIC
                : role.permits(Audience.PRIVILEGED) ? Audience.PRIVILEGED : Audience.character("npc-1");
        return new ScheduledTask(UUID.randomUUID(), new SessionScope(adventureId, campaign, generation, role, audience,
                PACK, POLICY), workClass, InferenceSlot.STANDARD, "Task " + ++counter + ".", SCHEMA, timeout);
    }

    ScheduledTask narration(UUID campaign) {
        return task(campaign, AiRole.NARRATOR, WorkClass.INTERACTIVE);
    }

    ScheduledTask plan(UUID campaign) {
        return task(campaign, AiRole.QUEST_PLANNER, WorkClass.PREPARATION);
    }

    static TaskResult result(TaskHandle handle) throws Exception {
        return handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached");
            }
            Thread.sleep(10);
        }
    }

    /** Give the scheduler's timer several passes to do something it must not do. */
    static void settle() throws InterruptedException {
        Thread.sleep(150);
    }

    Map<String, Object> row(UUID taskId) {
        return jdbc.queryForMap("select status, outcome_code, charged_usage, estimated_usage, runtime_ref, preempted, "
                + "instance_id, dispatched_at, work_class, role, audience from inference_task where id = ?", taskId);
    }

    String status(UUID taskId) {
        return (String) row(taskId).get("status");
    }

    List<UUID> sent(ControlledPort runtime) {
        return runtime.requests.stream().map(request -> request.taskId()).toList();
    }

    @Test
    void recordsEveryTransitionAndRunsOneTurnAtATime() throws Exception {
        var scheduler = process();
        ScheduledTask first = narration(campaignA);
        ScheduledTask second = narration(campaignB);
        TaskHandle running = scheduler.submit(first);
        TaskHandle waiting = scheduler.submit(second);

        assertThat(sent(port)).containsExactly(first.taskId());
        assertThat(row(first.taskId())).containsEntry("status", "DISPATCHED").containsEntry("runtime_ref", "thread-1")
                .containsEntry("work_class", "INTERACTIVE").containsEntry("role", "narrator")
                .containsEntry("audience", "public").containsEntry("charged_usage", first.estimatedUsage());
        assertThat(row(second.taskId())).containsEntry("status", "QUEUED").containsEntry("charged_usage", 0L);
        assertThat(scheduler.status()).isEqualTo(new InferenceScheduler.Status(InferenceScheduler.State.READY, 1, 1, 0,
                1, 0, null, null));
        // The forwarded budget is what remains of the task's own budget.
        assertThat(port.requests.getFirst().timeout()).isEqualTo(Duration.ofSeconds(60));

        port.complete(first.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(running).status()).isEqualTo(TaskResult.Status.COMPLETED);
        await(() -> sent(port).size() == 2);
        assertThat(row(first.taskId())).containsEntry("status", "COMPLETED").containsEntry("outcome_code", null)
                .containsEntry("charged_usage", first.estimatedUsage());
        assertThat(status(second.taskId())).isEqualTo("DISPATCHED");
        port.complete(second.taskId(), TaskResult.Status.FAILED, "turn-failed");
        assertThat(result(waiting).errorCode()).isEqualTo("turn-failed");
        await(() -> status(second.taskId()).equals("FAILED"));
        assertThat(row(second.taskId())).containsEntry("outcome_code", "turn-failed");
    }

    @Test
    void interactiveWorkPreemptsBackgroundWorkWhosePermitIsKeptUntilItsTerminalResult() throws Exception {
        port.autoInterrupt = false;
        var scheduler = process();
        ScheduledTask planning = plan(campaignA);
        TaskHandle planner = scheduler.submit(planning);
        ScheduledTask narrate = narration(campaignB);
        TaskHandle narrator = scheduler.submit(narrate);

        await(() -> port.cancelled.contains(planning.taskId()));
        await(() -> Boolean.TRUE.equals(row(planning.taskId()).get("preempted")));
        settle();
        // The interrupt is not confirmed yet: the narration must not start beside the planner.
        assertThat(sent(port)).containsExactly(planning.taskId());

        port.complete(planning.taskId(), TaskResult.Status.CANCELLED, "cancelled");
        TaskResult preempted = result(planner);
        assertThat(preempted.status()).isEqualTo(TaskResult.Status.CANCELLED);
        assertThat(preempted.errorCode()).isEqualTo(InferenceScheduler.PREEMPTED);
        assertThat(preempted.output()).isNull();
        await(() -> sent(port).size() == 2);
        assertThat(sent(port)).containsExactly(planning.taskId(), narrate.taskId());
        // A turn ran, so its reservation stays charged.
        assertThat(row(planning.taskId())).containsEntry("status", "CANCELLED").containsEntry("outcome_code", "preempted")
                .containsEntry("preempted", true).containsEntry("charged_usage", planning.estimatedUsage());
        port.complete(narrate.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(narrator).status()).isEqualTo(TaskResult.Status.COMPLETED);
    }

    @Test
    void campaignsTakeTurnsAndBackgroundWorkWaitsForInteractiveWork() throws Exception {
        var scheduler = process();
        ScheduledTask aFirst = narration(campaignA);
        scheduler.submit(aFirst);
        // While A's first task runs: a planner, A's interpreter, then B's and C's narration.
        ScheduledTask planning = plan(campaignC);
        ScheduledTask aSecond = task(campaignA, AiRole.INTERPRETER, WorkClass.INTERACTIVE);
        ScheduledTask b = narration(campaignB);
        ScheduledTask c = narration(campaignC);
        for (ScheduledTask next : List.of(planning, aSecond, b, c)) {
            scheduler.submit(next);
        }
        for (int done = 1; done <= 4; done++) {
            UUID current = sent(port).getLast();
            port.complete(current, TaskResult.Status.COMPLETED, null);
            int expected = done + 1;
            await(() -> sent(port).size() == expected);
        }
        assertThat(sent(port)).containsExactly(aFirst.taskId(), b.taskId(), c.taskId(), aSecond.taskId(),
                planning.taskId());
    }

    @Test
    void queuePlacesAreBoundedPerClassSoBackgroundBacklogNeverTakesAnInteractivePlace() throws Exception {
        port.autoInterrupt = false;
        var scheduler = process(port, settings(2, 1, 2, 1, 4_000_000L, 400_000L));
        TaskHandle planA = scheduler.submit(plan(campaignA));
        TaskHandle secondPlanA = scheduler.submit(task(campaignA, AiRole.WORLD_DIRECTOR, WorkClass.SPECULATIVE));
        TaskHandle planB = scheduler.submit(plan(campaignB));
        TaskHandle planC = scheduler.submit(plan(campaignC));
        assertThat(result(secondPlanA).errorCode()).isEqualTo(InferenceScheduler.QUEUE_FULL);
        assertThat(result(planC).errorCode()).isEqualTo(InferenceScheduler.QUEUE_FULL);
        assertThat(planA.result().toCompletableFuture().isDone()).isFalse();
        assertThat(planB.result().toCompletableFuture().isDone()).isFalse();

        // Background places are full, interactive places are not.
        TaskHandle narrateA = scheduler.submit(narration(campaignA));
        TaskHandle interpretA = scheduler.submit(task(campaignA, AiRole.INTERPRETER, WorkClass.INTERACTIVE));
        TaskHandle narrateB = scheduler.submit(narration(campaignB));
        TaskHandle narrateC = scheduler.submit(narration(campaignC));
        assertThat(result(interpretA).errorCode()).isEqualTo(InferenceScheduler.QUEUE_FULL);
        assertThat(result(narrateC).errorCode()).isEqualTo(InferenceScheduler.QUEUE_FULL);
        assertThat(narrateA.result().toCompletableFuture().isDone()).isFalse();
        assertThat(narrateB.result().toCompletableFuture().isDone()).isFalse();
        // Refused tasks never got a ledger row.
        assertThat(jdbc.queryForObject("select count(*) from inference_task", Integer.class)).isEqualTo(4);
    }

    @Test
    void rollingQuotasChargeStartedTurnsOnlyAndSurviveARestart() throws Exception {
        ScheduledTask sample = plan(campaignA);
        long estimate = sample.estimatedUsage();
        var limits = settings(16, 4, 8, 2, estimate * 3 + 10, estimate + 10);
        var scheduler = process(port, limits);

        TaskHandle first = scheduler.submit(sample);
        port.complete(sample.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(first).status()).isEqualTo(TaskResult.Status.COMPLETED);
        await(() -> status(sample.taskId()).equals("COMPLETED"));
        assertThat(result(scheduler.submit(plan(campaignB))).errorCode()).isEqualTo(InferenceScheduler.QUOTA_EXHAUSTED);

        // A task the runtime never started releases its reservation.
        ScheduledTask unavailable = narration(campaignA);
        TaskHandle missing = scheduler.submit(unavailable);
        port.completeWithoutTurn(unavailable.taskId(), TaskResult.Status.UNAVAILABLE, "runtime-unreachable");
        assertThat(result(missing).status()).isEqualTo(TaskResult.Status.UNAVAILABLE);
        await(() -> status(unavailable.taskId()).equals("UNAVAILABLE"));
        assertThat(row(unavailable.taskId())).containsEntry("charged_usage", 0L);

        // Interactive work still has the all-class quota: two more turns fit, a third does not.
        for (int turn = 0; turn < 2; turn++) {
            ScheduledTask narrate = narration(campaignB);
            TaskHandle handle = scheduler.submit(narrate);
            port.complete(narrate.taskId(), TaskResult.Status.COMPLETED, null);
            assertThat(result(handle).status()).isEqualTo(TaskResult.Status.COMPLETED);
            await(() -> status(narrate.taskId()).equals("COMPLETED"));
        }
        assertThat(result(scheduler.submit(narration(campaignC))).errorCode())
                .isEqualTo(InferenceScheduler.QUOTA_EXHAUSTED);

        // A restarted process reads the same usage from the ledger.
        scheduler.stop();
        var restarted = process(newPort(), limits);
        assertThat(result(restarted.submit(plan(campaignC))).errorCode()).isEqualTo(InferenceScheduler.QUOTA_EXHAUSTED);
        // The window rolls: a day later the same work is admitted again.
        clock.advance(Duration.ofHours(24).plusSeconds(1));
        TaskHandle later = restarted.submit(plan(campaignC));
        await(() -> ports.getLast().requests.size() == 1);
        assertThat(later.result().toCompletableFuture().isDone()).isFalse();
    }

    @Test
    void accountConditionsSuspendBackgroundWorkDurablyUntilBackoffExpiresOrATaskCompletes() throws Exception {
        var scheduler = process();
        ScheduledTask planning = plan(campaignA);
        TaskHandle planner = scheduler.submit(planning);
        port.complete(planning.taskId(), TaskResult.Status.FAILED, "usageLimitExceeded");
        assertThat(result(planner).errorCode()).isEqualTo("usageLimitExceeded");
        Instant first = clock.instant();
        await(() -> scheduler.status().suspension() != null);
        assertThat(scheduler.status().suspension()).isEqualTo("account-usage-limit");
        assertThat(scheduler.status().suspendedUntil()).isEqualTo(first.plusSeconds(30));
        assertThat(result(scheduler.submit(plan(campaignB))).errorCode()).isEqualTo(InferenceScheduler.SUSPENDED);

        // Interactive work is never suspended; a second condition doubles the backoff.
        ScheduledTask narrate = narration(campaignA);
        TaskHandle narrator = scheduler.submit(narrate);
        port.complete(narrate.taskId(), TaskResult.Status.FAILED, "rateLimitExceeded");
        assertThat(result(narrator).errorCode()).isEqualTo("rateLimitExceeded");
        await(() -> "account-rate-limit".equals(scheduler.status().suspension()));
        assertThat(scheduler.status().suspendedUntil()).isEqualTo(first.plusSeconds(60));
        assertThat(jdbc.queryForMap("select suspension_code, consecutive_suspensions from inference_scheduler_state"))
                .containsEntry("suspension_code", "account-rate-limit").containsEntry("consecutive_suspensions", 2);

        // A restart keeps the suspension; once it expires background work runs, and a completed
        // task clears the backoff.
        scheduler.stop();
        ControlledPort runtime = newPort();
        var restarted = process(runtime, defaults());
        assertThat(restarted.status().suspension()).isEqualTo("account-rate-limit");
        assertThat(result(restarted.submit(plan(campaignB))).errorCode()).isEqualTo(InferenceScheduler.SUSPENDED);
        clock.advance(Duration.ofSeconds(61));
        ScheduledTask resumed = plan(campaignB);
        TaskHandle later = restarted.submit(resumed);
        await(() -> runtime.requests.size() == 1);
        runtime.complete(resumed.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(later).status()).isEqualTo(TaskResult.Status.COMPLETED);
        await(() -> restarted.status().suspension() == null);
        assertThat(jdbc.queryForMap("select suspended_until, suspension_code, consecutive_suspensions "
                + "from inference_scheduler_state")).containsEntry("suspended_until", null)
                .containsEntry("suspension_code", null).containsEntry("consecutive_suspensions", 0);
    }

    @Test
    void cancellationEndsWaitingWorkUnsentAndInterruptsRunningWorkByItsExactHandle() throws Exception {
        var scheduler = process();
        ScheduledTask runningTask = narration(campaignA);
        ScheduledTask waitingTask = narration(campaignB);
        TaskHandle running = scheduler.submit(runningTask);
        TaskHandle waiting = scheduler.submit(waitingTask);

        assertThat(waiting.cancel()).isTrue();
        TaskResult cancelledWhileWaiting = result(waiting);
        assertThat(cancelledWhileWaiting.status()).isEqualTo(TaskResult.Status.CANCELLED);
        assertThat(cancelledWhileWaiting.errorCode()).isEqualTo(InferenceScheduler.CANCELLED);
        assertThat(row(waitingTask.taskId())).containsEntry("status", "CANCELLED").containsEntry("charged_usage", 0L)
                .containsEntry("dispatched_at", null);

        assertThat(running.cancel()).isTrue();
        assertThat(port.cancelled).containsExactly(runningTask.taskId());
        assertThat(result(running).status()).isEqualTo(TaskResult.Status.CANCELLED);
        await(() -> status(runningTask.taskId()).equals("CANCELLED"));
        assertThat(row(runningTask.taskId())).containsEntry("charged_usage", runningTask.estimatedUsage());
        assertThat(running.cancel()).isFalse();
        assertThat(sent(port)).containsExactly(runningTask.taskId());
    }

    @Test
    void waitingWorkWhoseBudgetRunsOutEndsTimedOutWithoutDispatch() throws Exception {
        var scheduler = process();
        scheduler.submit(narration(campaignA));
        ScheduledTask shortTask = task(adventure, campaignB, 0, AiRole.NARRATOR, WorkClass.INTERACTIVE,
                Duration.ofSeconds(5));
        TaskHandle waiting = scheduler.submit(shortTask);
        clock.advance(Duration.ofMillis(4_500));
        TaskResult expired = result(waiting);
        assertThat(expired.status()).isEqualTo(TaskResult.Status.TIMED_OUT);
        assertThat(expired.errorCode()).isEqualTo(InferenceScheduler.QUEUE_TIMEOUT);
        await(() -> status(shortTask.taskId()).equals("TIMED_OUT"));
        assertThat(sent(port)).hasSize(1);
    }

    @Test
    void anUnconfirmedOutcomeKeepsThePermitUntilThePortConfirmsThatNothingRuns() throws Exception {
        var scheduler = process();
        ScheduledTask uncertain = narration(campaignA);
        TaskHandle first = scheduler.submit(uncertain);
        // Like the adapter: the thread is ambiguous (active) before the result is reported.
        port.status = ControlledPort.ready(1);
        port.complete(uncertain.taskId(), TaskResult.Status.UNRESOLVED, "interrupt-unconfirmed");
        assertThat(result(first).status()).isEqualTo(TaskResult.Status.UNRESOLVED);
        await(() -> status(uncertain.taskId()).equals("RECONCILING"));
        assertThat(row(uncertain.taskId())).containsEntry("outcome_code", "interrupt-unconfirmed")
                .containsEntry("charged_usage", uncertain.estimatedUsage());

        ScheduledTask blocked = narration(campaignB);
        TaskHandle second = scheduler.submit(blocked);
        settle();
        assertThat(sent(port)).containsExactly(uncertain.taskId());
        assertThat(scheduler.status().reconciling()).isEqualTo(1);

        // While the held permit faces a port that cannot run anything, waiting work ends at once.
        port.status = new RuntimeStatus("scripted", RuntimeStatus.State.UNAVAILABLE, "reconciliation-pending", 0, 1);
        TaskResult unavailable = result(second);
        assertThat(unavailable.status()).isEqualTo(TaskResult.Status.UNAVAILABLE);
        assertThat(unavailable.errorCode()).isEqualTo("reconciliation-pending");
        assertThat(status(blocked.taskId())).isEqualTo("UNAVAILABLE");
        assertThat(status(uncertain.taskId())).isEqualTo("RECONCILING");

        port.status = ControlledPort.ready(0);
        await(() -> status(uncertain.taskId()).equals("UNRESOLVED"));
        ScheduledTask next = narration(campaignC);
        scheduler.submit(next);
        await(() -> sent(port).size() == 2);
        assertThat(scheduler.status().reconciling()).isZero();
    }

    @Test
    void aRestartAbandonsQueuedWorkAndReconcilesDispatchedTurnsBeforeAnyNewTurn() throws Exception {
        var crashed = process();
        ScheduledTask inFlight = narration(campaignA);
        ScheduledTask queued = narration(campaignB);
        TaskHandle lostCaller = crashed.submit(inFlight);
        TaskHandle queuedCaller = crashed.submit(queued);
        Object crashedInstance = row(inFlight.taskId()).get("instance_id");
        assertThat(row(inFlight.taskId())).containsEntry("status", "DISPATCHED").containsEntry("runtime_ref", "thread-1");

        // A new process starts against the same database while the old one is gone.
        ControlledPort runtime = newPort();
        var restarted = process(runtime, defaults());
        assertThat(runtime.reconciled).containsExactly(List.of("thread-1"));
        assertThat(row(queued.taskId())).containsEntry("status", "ABANDONED").containsEntry("outcome_code", "restart");
        assertThat(row(inFlight.taskId())).containsEntry("status", "RECONCILING").containsEntry("outcome_code", "restart")
                .containsEntry("charged_usage", inFlight.estimatedUsage());
        assertThat(row(inFlight.taskId()).get("instance_id")).isNotEqualTo(crashedInstance);

        ScheduledTask fresh = narration(campaignC);
        TaskHandle freshCaller = restarted.submit(fresh);
        settle();
        assertThat(runtime.requests).isEmpty();
        assertThat(status(fresh.taskId())).isEqualTo("QUEUED");

        // The runtime confirms that nothing runs under the recorded reference.
        runtime.status = ControlledPort.ready(0);
        await(() -> status(inFlight.taskId()).equals("UNRESOLVED"));
        await(() -> runtime.requests.size() == 1);
        assertThat(sent(runtime)).containsExactly(fresh.taskId());

        // The old process's late result is fenced: it cannot settle a row it no longer owns, and its
        // stale queue entry cannot be dispatched any more.
        port.complete(inFlight.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(lostCaller).status()).isEqualTo(TaskResult.Status.COMPLETED);
        TaskResult fenced = result(queuedCaller);
        assertThat(fenced.errorCode()).isEqualTo(InferenceScheduler.FENCED);
        assertThat(status(inFlight.taskId())).isEqualTo("UNRESOLVED");
        assertThat(status(queued.taskId())).isEqualTo("ABANDONED");
        assertThat(sent(port)).containsExactly(inFlight.taskId());
        runtime.complete(fresh.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(freshCaller).status()).isEqualTo(TaskResult.Status.COMPLETED);
    }

    @Test
    void twoProcessesSharingTheDatabaseNeverRunTwoTurnsAtOnce() throws Exception {
        ControlledPort first = port;
        ControlledPort second = newPort();
        var one = process(first, defaults());
        var two = process(second, defaults());
        ScheduledTask a = narration(campaignA);
        ScheduledTask b = narration(campaignB);
        one.submit(a);
        // Wait until the port holds the running turn (it records a request before its journal write
        // and registers the turn after it), so completing it never races the dispatch itself.
        await(() -> first.running.containsKey(a.taskId()));
        TaskHandle waiting = two.submit(b);
        settle();
        assertThat(second.requests).isEmpty();
        assertThat(status(b.taskId())).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select count(*) from inference_task where status in ('DISPATCHED', "
                + "'RECONCILING')", Integer.class)).isEqualTo(1);

        first.complete(a.taskId(), TaskResult.Status.COMPLETED, null);
        await(() -> second.running.containsKey(b.taskId()));
        assertThat(second.requests).hasSize(1);
        second.complete(b.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(waiting).status()).isEqualTo(TaskResult.Status.COMPLETED);
    }

    @Test
    void admissionProvesCampaignScopeAndGenerationAndKeepsIdentitiesUniqueAcrossRestarts() throws Exception {
        var scheduler = process();
        TaskResult conflict = result(scheduler.submit(task(otherAdventure, campaignA, 0, AiRole.NARRATOR,
                WorkClass.INTERACTIVE, Duration.ofSeconds(60))));
        assertThat(conflict.errorCode()).isEqualTo(ScopedSessions.SCOPE_CONFLICT);
        TaskResult stale = result(scheduler.submit(task(adventure, campaignA, 1, AiRole.NARRATOR,
                WorkClass.INTERACTIVE, Duration.ofSeconds(60))));
        assertThat(stale.errorCode()).isEqualTo(ScopedSessions.STALE_GENERATION);
        assertThat(jdbc.queryForObject("select count(*) from inference_task", Integer.class)).isZero();

        ScheduledTask once = narration(campaignA);
        TaskHandle original = scheduler.submit(once);
        assertThat(result(scheduler.submit(once)).errorCode()).isEqualTo(InferenceScheduler.DUPLICATE);
        port.complete(once.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(original).status()).isEqualTo(TaskResult.Status.COMPLETED);
        await(() -> status(once.taskId()).equals("COMPLETED"));
        assertThat(result(scheduler.submit(once)).errorCode()).isEqualTo(InferenceScheduler.DUPLICATE);
        scheduler.stop();
        var restarted = process(newPort(), defaults());
        assertThat(result(restarted.submit(once)).errorCode()).isEqualTo(InferenceScheduler.DUPLICATE);

        // A restore while a task waits: the newer generation fences it before anything is sent.
        ControlledPort runtime = ports.getLast();
        ScheduledTask holding = narration(campaignA);
        restarted.submit(holding);
        ScheduledTask waiting = narration(campaignB);
        TaskHandle fenced = restarted.submit(waiting);
        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1 "
                + "where id = ?", campaignB);
        runtime.complete(holding.taskId(), TaskResult.Status.COMPLETED, null);
        assertThat(result(fenced).errorCode()).isEqualTo(ScopedSessions.STALE_GENERATION);
        assertThat(status(waiting.taskId())).isEqualTo("REJECTED");
        assertThat(sent(runtime)).containsExactly(holding.taskId());
    }

    @Test
    void aRuntimeStartTheJournalCannotRecordIsRefusedBeforeAnyTurn() throws Exception {
        var scheduler = process();
        ScheduledTask first = narration(campaignA);
        scheduler.submit(first);
        assertThat(row(first.taskId())).containsEntry("runtime_ref", "thread-1");
        // The reference is written once, and only for a dispatched row of the ledger.
        assertThat(ledger.recordReference(first.taskId(), "thread-again")).isFalse();
        assertThat(ledger.recordReference(UUID.randomUUID(), "thread-unknown")).isFalse();
        // A start the scheduler did not dispatch has no row, so the attached journal refuses it and
        // the port starts no turn.
        var stray = new TaskRequest(UUID.randomUUID(), UUID.randomUUID(), InferenceSlot.STANDARD, "Stray.", SCHEMA,
                Duration.ofSeconds(5));
        TaskResult refused = result(port.submit(stray));
        assertThat(refused.status()).isEqualTo(TaskResult.Status.FAILED);
        assertThat(refused.errorCode()).isEqualTo("dispatch-journal-unavailable");
        assertThat(refused.provenance()).isNull();
        assertThat(port.running).doesNotContainKey(stray.taskId());
        assertThat(row(first.taskId())).containsEntry("runtime_ref", "thread-1");
    }

    @Test
    void stopCancelsWaitingWorkAndInterruptsRunningWork() throws Exception {
        var scheduler = process();
        ScheduledTask runningTask = narration(campaignA);
        ScheduledTask waitingTask = plan(campaignB);
        TaskHandle running = scheduler.submit(runningTask);
        TaskHandle waiting = scheduler.submit(waitingTask);
        scheduler.stop();
        assertThat(result(waiting).errorCode()).isEqualTo(InferenceScheduler.SHUTDOWN);
        assertThat(result(running).status()).isEqualTo(TaskResult.Status.CANCELLED);
        assertThat(status(waitingTask.taskId())).isEqualTo("CANCELLED");
        assertThat(status(runningTask.taskId())).isEqualTo("CANCELLED");
        assertThat(scheduler.status().state()).isEqualTo(InferenceScheduler.State.STOPPED);
        assertThat(result(scheduler.submit(narration(campaignC))).errorCode())
                .isEqualTo(InferenceScheduler.NOT_RUNNING);
    }

    @Test
    void databaseGuardsKeepTheLifecycleTheChargeAndTheSingleTurnPermit() throws Exception {
        port.autoInterrupt = false;
        var scheduler = process();
        ScheduledTask dispatched = narration(campaignA);
        ScheduledTask queued = narration(campaignB);
        scheduler.submit(dispatched);
        scheduler.submit(queued);
        UUID d = dispatched.taskId();
        UUID q = queued.taskId();

        assertThatThrownBy(() -> jdbc.update("update inference_task set status = 'DISPATCHED', dispatched_at = now(), "
                + "charged_usage = estimated_usage, row_version = row_version + 1 where id = ?", q))
                .hasMessageContaining("inference_task_one_turn_uk");
        assertThatThrownBy(() -> jdbc.update("update inference_task set status = 'COMPLETED', finished_at = now(), "
                + "row_version = row_version + 1 where id = ?", q)).hasMessageContaining("inference_task_transition");
        assertThatThrownBy(() -> jdbc.update("update inference_task set status = 'UNRESOLVED', outcome_code = 'x', "
                + "finished_at = now(), row_version = row_version + 1 where id = ?", d))
                .hasMessageContaining("inference_task_transition");
        assertThatThrownBy(() -> jdbc.update("update inference_task set charged_usage = 0, "
                + "row_version = row_version + 1 where id = ?", d)).hasMessageContaining("inference_task_charge");
        assertThatThrownBy(() -> jdbc.update("update inference_task set runtime_ref = 'thread-9', "
                + "row_version = row_version + 1 where id = ?", d)).hasMessageContaining("inference_task_reference");
        assertThatThrownBy(() -> jdbc.update("update inference_task set role = 'quest-planner', "
                + "row_version = row_version + 1 where id = ?", d)).hasMessageContaining("inference_task_frozen");
        assertThatThrownBy(() -> jdbc.update("update inference_task set instance_id = ?, "
                + "row_version = row_version + 1 where id = ?", UUID.randomUUID(), d))
                .hasMessageContaining("inference_task_owner");
        assertThatThrownBy(() -> jdbc.update("delete from inference_task where id = ?", q)).isNotNull();

        port.complete(d, TaskResult.Status.COMPLETED, null);
        await(() -> status(d).equals("COMPLETED"));
        assertThatThrownBy(() -> jdbc.update("update inference_task set preempted = true, "
                + "row_version = row_version + 1 where id = ?", d)).hasMessageContaining("inference_task_terminal");
        assertThat(row(d)).containsEntry("charged_usage", dispatched.estimatedUsage());
    }

    /** Clock advanced explicitly by the test; read concurrently by the scheduler's timer. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-28T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
