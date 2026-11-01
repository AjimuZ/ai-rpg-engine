package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.rpgengine.inference.admission.AdmissionFixtures.A;
import static org.rpgengine.inference.admission.AdmissionFixtures.LARGE;
import static org.rpgengine.inference.admission.AdmissionFixtures.T0;
import static org.rpgengine.inference.admission.AdmissionFixtures.settings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.CompletedTaskHandle;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.ModelAllowlist;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.SlotAssignment;
import org.rpgengine.inference.TaskHandle;
import org.rpgengine.inference.TaskRequest;
import org.rpgengine.inference.TaskResult;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.inference.scheduling.MemoryTaskLedger;
import org.rpgengine.inference.scheduling.ScheduledTask;
import org.rpgengine.inference.scheduling.SchedulerSettings;
import org.rpgengine.inference.scheduling.TextResidency;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.inference.session.SessionScope;

/**
 * The driver over the real {@link LeaseExecutor} and {@link AdmissionCoordinator}, with the recording
 * {@link ScriptedWorker} as the text server, a scripted free-memory reading and an in-memory lease store: no GPU,
 * server or database takes part, and nothing here shows how a real one behaves. The stand-in card has 100 bytes and
 * a text model needs 60. The tick is an hour, so ticks happen only when a test asks for one or a claim arrives.
 */
class TextResidencyDriverTest {
    private static final String TEXT = "text-a";
    private static final String WORKER_THREAD = "inference-residency";
    private static final Duration RECHECK = Duration.ofSeconds(10);
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final LeaseExecutorSettings SETTINGS = new LeaseExecutorSettings(0, 0, 0, 3, Duration.ofSeconds(1),
            RECHECK, PATIENCE, Duration.ofMinutes(5));

    /** Records what the driver tells the scheduler. */
    private static final class RecordingListener implements TextResidency.Listener {
        final List<String> heard = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void ready() {
            heard.add("ready");
        }

        @Override
        public void refused(String code) {
            heard.add("refused " + code);
        }

        @Override
        public void mustStop() {
            heard.add("stop");
        }
    }

    private final SteppedClock clock = new SteppedClock(T0);
    private final MemoryLeaseStore store = spy(new MemoryLeaseStore());
    private final AdmissionCoordinator coordinator = new AdmissionCoordinator(settings(), store, clock,
            AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
    private final Set<String> controlThreads = ConcurrentHashMap.newKeySet();
    private volatile CountDownLatch loadGate = new CountDownLatch(0);
    private volatile Duration loadTakes = Duration.ZERO;
    private final ScriptedWorker server = new ScriptedWorker(event -> {
        controlThreads.add(Thread.currentThread().getName());
        if (event.startsWith("worker load")) {
            clock.advance(loadTakes);
            try {
                loadGate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    });
    private volatile long free = 100;
    private final LeaseExecutor executor = new LeaseExecutor(coordinator, Map.of(Modality.TEXT, server),
            () -> OptionalLong.of(free), () -> false, SETTINGS, clock, clock::advance);
    private Optional<String> served = Optional.of(TEXT);
    private final ReadingWindow window = new ReadingWindow(clock, WINDOW);
    private final TextResidencyDriver driver = new TextResidencyDriver(executor, coordinator, slot -> served, model -> LARGE,
            clock, Duration.ofHours(1), null, window);
    private final RecordingListener listener = new RecordingListener();

    @AfterEach
    void stopDriver() {
        loadGate.countDown();
        driver.stop();
    }

    private static ScheduledTask task(WorkClass workClass) {
        AiRole role = workClass == WorkClass.INTERACTIVE ? AiRole.NARRATOR : AiRole.QUEST_PLANNER;
        Audience audience = role.permits(Audience.PUBLIC) ? Audience.PUBLIC : Audience.PRIVILEGED;
        return new ScheduledTask(UUID.randomUUID(), new SessionScope(UUID.randomUUID(), UUID.randomUUID(), 0, role, audience,
                "a".repeat(64), "b".repeat(64)), workClass, InferenceSlot.STANDARD, "Task.", "{\"type\":\"object\"}",
                Duration.ofSeconds(60));
    }

    private TextResidency.Claim claim(WorkClass workClass) throws Exception {
        TextResidency.Claim claim = driver.claim(task(workClass), listener);
        driver.awaitIdle();
        return claim;
    }

    private long metric(String name) {
        return executor.metrics().get(name);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached");
            }
            Thread.sleep(5);
        }
    }

    @Test
    void theDriverStartsTheExecutorOnceAndKeepsPassing() throws Exception {
        LeaseRecord left = new LeaseRecord(UUID.randomUUID(), UUID.randomUUID(), Modality.TEXT, "text-left", LARGE, 1,
                LeaseStatus.ACTIVE, T0, T0.plusSeconds(60), null);
        store.rows.put(left.id(), left);
        server.leaves("text-left");
        driver.start();
        driver.awaitIdle();
        driver.tickNow();
        driver.tickNow();

        // Taking over an earlier process's open lease and releasing it shows start ran; once, however many ticks.
        verify(store, times(1)).recover(any(), any());
        assertThat(store.rows.get(left.id()).status()).isEqualTo(LeaseStatus.RELEASED);
        assertThat(server.holds("text-left")).isFalse();
        assertThat(controlThreads).containsExactly(WORKER_THREAD);

        // A pass admits: a request submitted directly is granted by a tick.
        var direct = new AdmissionFixtures().text("direct", WorkClass.INTERACTIVE, A, TEXT, LARGE);
        coordinator.submit(direct);
        assertThat(metric("requests_waiting")).isEqualTo(1);
        driver.tickNow();
        assertThat(metric("requests_waiting")).isZero();
    }

    @Test
    void aFailedStartIsRetriedByTheNextTick() throws Exception {
        doThrow(new IllegalStateException("store down")).doCallRealMethod().when(store).recover(any(), any());
        driver.start();
        driver.awaitIdle();
        driver.tickNow();
        verify(store, times(2)).recover(any(), any());

        claim(WorkClass.INTERACTIVE);
        assertThat(listener.heard).containsExactly("ready");
    }

    @Test
    void aClaimLoadsTheModelOnTheWorkerAndAReleaseFinishesTheLeaseAndLeavesTheModelWarm() throws Exception {
        driver.start();
        TextResidency.Claim claim = claim(WorkClass.INTERACTIVE);

        assertThat(listener.heard).containsExactly("ready");
        assertThat(server.holds(TEXT)).isTrue();
        assertThat(metric("leases_active")).isEqualTo(1);
        assertThat(controlThreads).containsExactly(WORKER_THREAD);
        UUID lease = coordinator.leases().getFirst().id();

        claim.release(false);
        claim.release(false);
        driver.awaitIdle();
        assertThat(metric("leases_active")).isZero();
        assertThat(store.rows.get(lease).status()).isEqualTo(LeaseStatus.RELEASED);
        assertThat(server.holds(TEXT)).isTrue();
        assertThat(metric("text_models_resident")).isEqualTo(1);
    }

    @Test
    void aFinishedInteractiveTurnOpensTheReadingWindowAndNothingBeforeItsEndDoes() throws Exception {
        driver.start();
        TextResidency.Claim claim = claim(WorkClass.INTERACTIVE);
        assertThat(listener.heard).containsExactly("ready");
        assertThat(window.getAsBoolean()).as("the turn is still running").isFalse();

        claim.release(false);
        driver.awaitIdle();
        assertThat(window.getAsBoolean()).isTrue();

        clock.advance(WINDOW);
        assertThat(window.getAsBoolean()).isFalse();
    }

    @Test
    void aTurnEndedByYieldingDoesNotOpenTheReadingWindow() throws Exception {
        driver.start();
        TextResidency.Claim claim = claim(WorkClass.INTERACTIVE);

        claim.release(true);
        driver.awaitIdle();

        assertThat(metric("leases_active")).isZero();
        assertThat(window.getAsBoolean()).isFalse();
    }

    @Test
    void aTurnWhoseLeaseWasLostAndThatYieldedDoesNotOpenTheReadingWindow() throws Exception {
        driver.start();
        TextResidency.Claim claim = claim(WorkClass.INTERACTIVE);
        clock.advance(AdmissionFixtures.TTL.plusSeconds(1));
        driver.tickNow();
        assertThat(listener.heard).containsExactly("ready", "stop");

        claim.release(true);
        driver.awaitIdle();

        assertThat(window.getAsBoolean()).isFalse();
    }

    @Test
    void backgroundWorkHasNoReaderAndDoesNotOpenTheReadingWindow() throws Exception {
        driver.start();
        for (WorkClass background : new WorkClass[] {WorkClass.CONTINUITY, WorkClass.PREPARATION, WorkClass.SPECULATIVE}) {
            claim(background).release(false);
            driver.awaitIdle();
            assertThat(window.getAsBoolean()).as(background.name()).isFalse();
        }
    }

    @Test
    void aTurnThatNeededNoLocalModelDoesNotOpenTheReadingWindow() throws Exception {
        served = Optional.empty();
        driver.start();
        TextResidency.Claim claim = claim(WorkClass.INTERACTIVE);

        claim.release(false);
        driver.awaitIdle();

        assertThat(listener.heard).containsExactly("ready");
        assertThat(window.getAsBoolean()).isFalse();
    }

    @Test
    void aClaimWithdrawnBeforeItsTurnDoesNotOpenTheReadingWindow() throws Exception {
        driver.start();
        free = 0;
        TextResidency.Claim claim = claim(WorkClass.INTERACTIVE);
        assertThat(metric("requests_delayed")).isEqualTo(1);

        claim.release(false);
        driver.awaitIdle();

        assertThat(listener.heard).isEmpty();
        assertThat(window.getAsBoolean()).isFalse();
    }

    @Test
    void theNextFinishedTurnRestartsTheReadingWindow() throws Exception {
        driver.start();
        claim(WorkClass.INTERACTIVE).release(false);
        driver.awaitIdle();

        clock.advance(WINDOW.minusSeconds(1));
        claim(WorkClass.INTERACTIVE).release(false);
        driver.awaitIdle();

        clock.advance(WINDOW.minusSeconds(1));
        assertThat(window.getAsBoolean()).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(window.getAsBoolean()).isFalse();
    }

    @Test
    void theRequestCarriesTheTasksIdentityClassAndCampaign() throws Exception {
        driver.start();
        // A full card leaves the request put back, where the coordinator's next admission can be inspected.
        free = 0;
        ScheduledTask task = task(WorkClass.PREPARATION);
        driver.claim(task, listener);
        driver.awaitIdle();
        assertThat(metric("requests_delayed")).isEqualTo(1);

        clock.advance(RECHECK);
        free = 100;
        verify(store, times(1)).grant(any(), any());
        driver.tickNow();
        var granted = org.mockito.ArgumentCaptor.forClass(Lease.class);
        verify(store, times(2)).grant(any(), granted.capture());
        assertThat(granted.getValue().members()).containsExactly(task.taskId());
        assertThat(granted.getValue().model()).isEqualTo(TEXT);
        assertThat(granted.getValue().demand()).isEqualTo(LARGE);
        assertThat(listener.heard).containsExactly("ready");
    }

    @Test
    void aClaimNeverWaitsForTheServer() throws Exception {
        driver.start();
        loadGate = new CountDownLatch(1);
        var returned = new CompletableFuture<TextResidency.Claim>();
        Thread caller = new Thread(() -> returned.complete(driver.claim(task(WorkClass.INTERACTIVE), listener)), "caller");
        caller.start();

        // The worker sits inside the load; the claim and a later release both return regardless.
        TextResidency.Claim claim = returned.get(5, TimeUnit.SECONDS);
        await(() -> controlThreads.contains(WORKER_THREAD));
        claim.release(false);
        assertThat(listener.heard).isEmpty();
        assertThat(controlThreads).doesNotContain("caller");

        loadGate.countDown();
        driver.awaitIdle();
    }

    @Test
    void aClaimReleasedDuringItsLoadIsNeverReadyAndGivesTheLeaseBack() throws Exception {
        driver.start();
        loadGate = new CountDownLatch(1);
        TextResidency.Claim claim = driver.claim(task(WorkClass.INTERACTIVE), listener);
        await(() -> controlThreads.contains(WORKER_THREAD));
        claim.release(false);
        loadGate.countDown();
        driver.awaitIdle();

        assertThat(listener.heard).isEmpty();
        assertThat(metric("leases_active")).isZero();
        assertThat(metric("requests_waiting")).isZero();
    }

    @Test
    void aSlotWithNoLocalModelIsReadyAtOnceAndTouchesNothing() throws Exception {
        served = Optional.empty();
        driver.start();
        TextResidency.Claim claim = claim(WorkClass.INTERACTIVE);

        assertThat(listener.heard).containsExactly("ready");
        assertThat(metric("leases_active")).isZero();
        verify(store, times(0)).grant(any(), any());
        claim.release(false);
        driver.awaitIdle();
        assertThat(server.holds(TEXT)).isFalse();
    }

    @Test
    void aRequestTheCoordinatorRefusesEndsTheClaimUnavailable() throws Exception {
        driver.start();
        // The model name fails the request's own rule, as a value from settings could.
        served = Optional.of("not a model name");
        claim(WorkClass.INTERACTIVE);
        assertThat(listener.heard).containsExactly("refused " + TextResidency.UNAVAILABLE);
    }

    @Test
    void aClaimWithdrawnWhileDelayedLeavesNothingBehind() throws Exception {
        driver.start();
        free = 0;
        TextResidency.Claim claim = claim(WorkClass.INTERACTIVE);
        assertThat(metric("requests_delayed")).isEqualTo(1);
        assertThat(listener.heard).isEmpty();

        claim.release(false);
        driver.awaitIdle();
        assertThat(metric("requests_delayed")).isZero();
        assertThat(metric("requests_waiting")).isZero();

        // It must not come back when memory does.
        free = 100;
        clock.advance(RECHECK);
        driver.tickNow();
        assertThat(server.holds(TEXT)).isFalse();
        assertThat(listener.heard).isEmpty();
    }

    @Test
    void contentionPastThePatienceRefusesTheClaim() throws Exception {
        driver.start();
        free = 0;
        claim(WorkClass.INTERACTIVE);
        assertThat(listener.heard).isEmpty();

        clock.advance(PATIENCE.plus(RECHECK));
        driver.tickNow();
        assertThat(listener.heard).containsExactly("refused " + TextResidency.CONTENTION);
        assertThat(metric("requests_delayed")).isZero();
        assertThat(server.holds(TEXT)).isFalse();
    }

    @Test
    void aModelThatWillNotLoadRefusesTheClaim() throws Exception {
        driver.start();
        server.loadFailures = 1;
        claim(WorkClass.INTERACTIVE);
        assertThat(listener.heard).containsExactly("refused " + TextResidency.LOAD_FAILED);
        assertThat(metric("leases_active")).isZero();
    }

    @Test
    void aHeldLeaseIsRenewedSoItsModelStaysThroughALongTurn() throws Exception {
        driver.start();
        claim(WorkClass.INTERACTIVE);
        UUID lease = coordinator.leases().getFirst().id();

        for (int round = 0; round < 5; round++) {
            clock.advance(AdmissionFixtures.TTL.dividedBy(2).plusSeconds(1));
            driver.tickNow();
        }
        assertThat(store.rows.get(lease).status()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(server.holds(TEXT)).isTrue();
        assertThat(listener.heard).containsExactly("ready");
        assertThat(metric("leases_reconciling")).isZero();
    }

    @Test
    void aLeaseThatCanNoLongerBeRenewedTellsTheHolderToStopOnce() throws Exception {
        driver.start();
        TextResidency.Claim claim = claim(WorkClass.INTERACTIVE);
        coordinator.revoke(coordinator.leases().getFirst().id());

        clock.advance(AdmissionFixtures.TTL.dividedBy(2).plusSeconds(1));
        driver.tickNow();
        driver.tickNow();
        assertThat(listener.heard).containsExactly("ready", "stop");

        claim.release(true);
        driver.awaitIdle();
        assertThat(metric("leases_active")).isZero();
    }

    @Test
    void aStoreFailureDuringARenewalLeavesTheHolderRunning() throws Exception {
        driver.start();
        claim(WorkClass.INTERACTIVE);
        doThrow(new IllegalStateException("store down")).doCallRealMethod().when(store).renew(any(), any(), any(), any());
        clock.advance(AdmissionFixtures.TTL.dividedBy(2).plusSeconds(1));
        driver.tickNow();

        assertThat(listener.heard).containsExactly("ready");
        assertThat(metric("leases_active")).isEqualTo(1);
        assertThat(server.holds(TEXT)).isTrue();
    }

    @Test
    void aLeaseThatLapsedBeforeItsRenewalTellsTheHolderToStop() throws Exception {
        driver.start();
        TextResidency.Claim claim = claim(WorkClass.INTERACTIVE);
        clock.advance(AdmissionFixtures.TTL.plusSeconds(1));
        driver.tickNow();

        assertThat(listener.heard).containsExactly("ready", "stop");
        assertThat(metric("leases_active")).isZero();
        claim.release(true);
        driver.awaitIdle();
        assertThat(listener.heard).containsExactly("ready", "stop");
    }

    @Test
    void aLoadWithinTheLifetimeStartsItsTurnWithAFullLease() throws Exception {
        driver.start();
        // The load takes nearly the whole lifetime, which a renewal timed from the grant would miss.
        loadTakes = AdmissionFixtures.TTL.minusSeconds(1);
        claim(WorkClass.INTERACTIVE);
        assertThat(listener.heard).containsExactly("ready");

        clock.advance(AdmissionFixtures.TTL.dividedBy(2).plusSeconds(1));
        driver.tickNow();
        assertThat(listener.heard).containsExactly("ready");
        assertThat(metric("leases_active")).isEqualTo(1);
        assertThat(server.holds(TEXT)).isTrue();
    }

    @Test
    void aLoadLongerThanTheLifetimeRefusesTheClaimAndRemovesItsModel() throws Exception {
        driver.start();
        loadTakes = AdmissionFixtures.TTL.plusSeconds(1);
        claim(WorkClass.INTERACTIVE);
        assertThat(listener.heard).containsExactly("refused " + TextResidency.LOST);

        driver.tickNow();
        assertThat(metric("leases_active")).isZero();
        assertThat(server.holds(TEXT)).isFalse();
    }

    @Test
    void aLeaseTheCoordinatorNamesForUrgentWorkYieldsAndItsModelStaysWarm() throws Exception {
        driver.start();
        TextResidency.Claim claim = claim(WorkClass.PREPARATION);
        UUID lease = coordinator.leases().getFirst().id();

        // An interactive request for a model that does not fit beside this one.
        coordinator.submit(new AdmissionFixtures().text("urgent", WorkClass.INTERACTIVE, A, "text-b", LARGE));
        driver.tickNow();
        driver.tickNow();
        assertThat(listener.heard).containsExactly("ready", "stop");

        claim.release(true);
        driver.awaitIdle();
        assertThat(store.rows.get(lease).status()).isEqualTo(LeaseStatus.REVOKED);
        assertThat(server.holds(TEXT)).isTrue();
    }

    @Test
    void stoppingRunsQueuedReleasesAndAStoppedDriverRefusesClaims() throws Exception {
        driver.start();
        TextResidency.Claim claim = claim(WorkClass.INTERACTIVE);
        claim.release(false);
        driver.stop();
        assertThat(driver.isRunning()).isFalse();
        assertThat(metric("leases_active")).isZero();

        var late = new RecordingListener();
        driver.claim(task(WorkClass.INTERACTIVE), late);
        assertThat(late.heard).containsExactly("refused " + TextResidency.UNAVAILABLE);
    }

    @Test
    void localModelsNamesOnlyAModelTheLocalAdapterWouldRun() {
        var pair = new EffectiveSettings(TEXT, "medium");
        ProfileResolver local = new ProfileResolver(slot -> Optional.of(SlotAssignment.of("local", pair)),
                ModelAllowlist.empty(), Optional.empty());
        ProfileResolver other = new ProfileResolver(slot -> Optional.of(SlotAssignment.of("codex", pair)),
                ModelAllowlist.empty(), Optional.empty());
        ProfileResolver unassigned = new ProfileResolver(slot -> Optional.empty(), ModelAllowlist.empty(),
                Optional.empty());

        assertThat(TextResidencyConfiguration.localModels(local, listing(Optional.of(true))).apply(InferenceSlot.STANDARD))
                .contains(TEXT);
        // The adapter reports each of these itself, exactly as without the driver.
        assertThat(TextResidencyConfiguration.localModels(local, listing(Optional.of(false))).apply(InferenceSlot.STANDARD))
                .isEmpty();
        assertThat(TextResidencyConfiguration.localModels(local, listing(Optional.empty())).apply(InferenceSlot.STANDARD))
                .isEmpty();
        assertThat(TextResidencyConfiguration.localModels(other, listing(Optional.of(true))).apply(InferenceSlot.STANDARD))
                .isEmpty();
        assertThat(TextResidencyConfiguration.localModels(unassigned, listing(Optional.of(true))).apply(InferenceSlot.STANDARD))
                .isEmpty();
    }

    /** @return a port whose only meaningful answer is whether it lists a model */
    private static NarrationTaskPort listing(Optional<Boolean> answer) {
        return new NarrationTaskPort() {
            @Override
            public TaskHandle submit(TaskRequest request) {
                throw new AssertionError("no task is submitted here");
            }

            @Override
            public void retireSession(UUID sessionId) {
            }

            @Override
            public RuntimeStatus status() {
                return new RuntimeStatus("local", RuntimeStatus.State.READY, null, 0, 0);
            }

            @Override
            public Optional<Boolean> listsModel(String backend, String model) {
                return answer;
            }
        };
    }

    /** A port that records the thread and what is resident when a turn starts, and finishes the turn at once. */
    private final class RecordingPort implements NarrationTaskPort {
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final List<Boolean> residentAtStart = Collections.synchronizedList(new ArrayList<>());

        @Override
        public TaskHandle submit(TaskRequest request) {
            threads.add(Thread.currentThread().getName());
            residentAtStart.add(server.holds(TEXT));
            return new CompletedTaskHandle(new TaskResult(request.taskId(), TaskResult.Status.COMPLETED, "{}", null,
                    new TaskResult.Provenance("local", "1", TEXT, "medium"), false));
        }

        @Override
        public void retireSession(UUID sessionId) {
        }

        @Override
        public RuntimeStatus status() {
            return new RuntimeStatus("local", RuntimeStatus.State.READY, null, 0, 0);
        }
    }

    private InferenceScheduler started(RecordingPort port, TextResidency residency) {
        var scheduler = new InferenceScheduler(new ScopedSessions(port, 16), port, new MemoryTaskLedger(),
                SchedulerSettings.defaults(), clock, () -> 0.5, residency);
        scheduler.recover();
        scheduler.start();
        return scheduler;
    }

    @Test
    void withTheDriverATaskObtainsAndReleasesResidencyThroughTheExecutorOffTheSchedulersThread() throws Exception {
        driver.start();
        var port = new RecordingPort();
        var scheduler = started(port, driver);
        try {
            TaskResult result = scheduler.submit(task(WorkClass.INTERACTIVE)).result().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(TaskResult.Status.COMPLETED);
            assertThat(port.residentAtStart).containsExactly(true);
            // Server calls ran on the worker, and the turn started on the scheduler's own thread.
            assertThat(controlThreads).containsExactly(WORKER_THREAD);
            assertThat(port.threads).containsExactly("inference-scheduler");
            driver.awaitIdle();
            await(() -> metric("leases_active") == 0);
            assertThat(store.rows.values()).singleElement().extracting(LeaseRecord::status)
                    .isEqualTo(LeaseStatus.RELEASED);
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void withoutTheDriverTheSameTaskTouchesNoExecutorAndNoServer() throws Exception {
        var port = new RecordingPort();
        var scheduler = started(port, null);
        try {
            TaskResult result = scheduler.submit(task(WorkClass.INTERACTIVE)).result().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(TaskResult.Status.COMPLETED);
            assertThat(port.residentAtStart).containsExactly(false);
            assertThat(controlThreads).isEmpty();
            assertThat(store.rows).isEmpty();
        } finally {
            scheduler.stop();
        }
    }
}
