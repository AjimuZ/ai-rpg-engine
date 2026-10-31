package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.rpgengine.inference.admission.AdmissionFixtures.A;
import static org.rpgengine.inference.admission.AdmissionFixtures.B;
import static org.rpgengine.inference.admission.AdmissionFixtures.CAPACITY;
import static org.rpgengine.inference.admission.AdmissionFixtures.LARGE;
import static org.rpgengine.inference.admission.AdmissionFixtures.SMALL;
import static org.rpgengine.inference.admission.AdmissionFixtures.T0;
import static org.rpgengine.inference.admission.AdmissionFixtures.settings;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ModelServerControl;
import org.rpgengine.inference.admission.LeaseExecutor.Outcome;
import org.rpgengine.inference.admission.LeaseExecutor.Status;
import org.rpgengine.inference.admission.LeaseExecutor.TextState;
import org.rpgengine.inference.local.LocalAdapters;
import org.rpgengine.inference.local.ScriptedModelServer;
import org.rpgengine.inference.scheduling.WorkClass;

/**
 * The lease executor over the real HTTP control client, the scripted model server, a scripted worker
 * and a scripted free-memory reading: all fixtures, so nothing here shows how a real server, worker or
 * card behaves. The stand-in card has 100 bytes; the text and image models need 60 each, so they never
 * fit together, and the budget is the fixtures' 100.
 */
class LeaseExecutorTest {
    private static final String TEXT = "text-a";
    private static final String OTHER_TEXT = "text-b";
    private static final String IMAGE = "image-a";
    private static final Duration RECHECK = Duration.ofSeconds(10);
    private static final LeaseExecutorSettings SETTINGS = new LeaseExecutorSettings(0, 5, 0, 3, Duration.ofSeconds(1),
            RECHECK, Duration.ofSeconds(30), Duration.ofMinutes(5));

    private final AdmissionFixtures fixtures = new AdmissionFixtures();
    private final MemoryLeaseStore store = new MemoryLeaseStore();
    private final SteppedClock clock = new SteppedClock(T0);
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private Consumer<String> observer = event -> { };
    private final ScriptedWorker worker = new ScriptedWorker(this::record);
    private final AdmissionCoordinator coordinator = new AdmissionCoordinator(settings(), store, clock,
            AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
    private ScriptedModelServer server;
    private LeaseExecutor executor;
    private long card = 100;
    private long unowned;
    private boolean readable = true;
    private boolean expected;

    @BeforeEach
    void start() throws IOException {
        server = new ScriptedModelServer();
        server.onControl(this::record);
        executor = executor(SETTINGS);
        executor.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private void record(String event) {
        events.add(event);
        observer.accept(event);
    }

    private LeaseExecutor executor(LeaseExecutorSettings settings) {
        return executor(coordinator, settings, Map.of(Modality.TEXT, LocalAdapters.control(server), Modality.IMAGE, worker,
                Modality.SPEECH, worker));
    }

    private LeaseExecutor executor(AdmissionCoordinator coordinator, LeaseExecutorSettings settings,
            Map<Modality, ModelServerControl> controls) {
        return new LeaseExecutor(coordinator, controls, this::reading, () -> expected, settings, clock, clock::advance);
    }

    /** What the stand-in card has free: its size minus resident models and what other applications hold. */
    private OptionalLong reading() {
        events.add("reading");
        if (!readable) {
            return OptionalLong.empty();
        }
        long used = unowned + (server.residency(TEXT).equals("loaded") ? 60 : 0)
                + (server.residency(OTHER_TEXT).equals("loaded") ? 60 : 0) + (worker.holds(IMAGE) ? 60 : 0);
        return OptionalLong.of(Math.max(0, card - used));
    }

    private AdmissionRequest textTurn() {
        return fixtures.text("turn", WorkClass.INTERACTIVE, B, TEXT, LARGE);
    }

    private AdmissionRequest imageJob() {
        return fixtures.request("art", Modality.IMAGE, WorkClass.PREPARATION, A, IMAGE, LARGE, clock.instant());
    }

    /** One text turn from start to end, which leaves the text model warm, and a clean event list. */
    private void warmText() {
        var turn = textTurn();
        coordinator.submit(turn);
        assertThat(executor.prepareText(executor.pass().grants().getFirst()).status()).isEqualTo(Status.READY);
        assertThat(executor.finishText(turn.id())).isTrue();
        assertThat(server.residency(TEXT)).isEqualTo("loaded");
        events.clear();
    }

    /** One text turn on {@code model} from start to end; the model stays warm and may evict another. */
    private void turnOn(String model, Demand demand) {
        var turn = fixtures.text("turn", WorkClass.INTERACTIVE, B, model, demand);
        coordinator.submit(turn);
        assertThat(executor.prepareText(executor.pass().grants().getFirst()).status()).isEqualTo(Status.READY);
        assertThat(executor.finishText(turn.id())).isTrue();
    }

    /** Further passes, also past the recheck pause, must leave the swapped-out model out and the warm one alone. */
    private void assertTextAStaysOutBesideTextB() {
        assertThat(server.residency(TEXT)).isEqualTo("unloaded");
        events.clear();
        executor.pass();
        clock.advance(RECHECK);
        executor.pass();

        assertThat(events).doesNotContain("load " + TEXT, "save " + OTHER_TEXT, "unload " + OTHER_TEXT);
        assertThat(server.residency(TEXT)).isEqualTo("unloaded");
        assertThat(server.residency(OTHER_TEXT)).isEqualTo("loaded");
        assertThat(executor.metrics()).containsEntry("text_models_resident", 1L).containsEntry("text_models_swapped_out", 1L);
    }

    private Grant grantImage() {
        coordinator.submit(imageJob());
        return executor.pass().grants().getFirst();
    }

    private SteppedJob steps(int total) {
        AtomicInteger done = new AtomicInteger();
        return () -> {
            int step = done.incrementAndGet();
            events.add("step " + step);
            return step < total;
        };
    }

    private LeaseStatus stored(Lease lease) {
        return store.rows.get(lease.id()).status();
    }

    @Test
    void aBatchSavesUnloadsConfirmsRunsThenReloadsAndRestoresInThatOrder() {
        warmText();
        Grant grant = grantImage();
        events.clear();

        Outcome outcome = executor.runBatch(grant, steps(2));

        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.steps()).isEqualTo(2);
        assertThat(outcome.text()).isEqualTo(TextState.RESTORED);
        assertThat(outcome.reconciling()).isFalse();
        assertThat(events).containsExactly("reading", "save " + TEXT, "unload " + TEXT, "reading",
                "worker load " + IMAGE, "step 1", "step 2", "worker unload " + IMAGE, "reading", "load " + TEXT,
                "restore " + TEXT);
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.RELEASED);
        assertThat(coordinator.free()).isEqualTo(CAPACITY);
        assertThat(server.residency(TEXT)).isEqualTo("loaded");
        assertThat(worker.holds(IMAGE)).isFalse();
    }

    @Test
    void theSlotFileNeverNamesTheModelAndTheSameFileIsSavedAndRestored() {
        warmText();
        executor.runBatch(grantImage(), steps(1));

        var saves = server.controlBodies().stream().filter(body -> body.has("filename")).toList();
        assertThat(saves).hasSize(2);
        String file = saves.getFirst().path("filename").stringValue();
        assertThat(file).matches("engine-slot-[0-9a-f]{32}\\.bin").doesNotContain(TEXT);
        assertThat(saves.getLast().path("filename").stringValue()).isEqualTo(file);
    }

    @Test
    void aSmallModelThatFitsBesideTheWarmTextModelCausesNoSwap() {
        warmText();
        var speech = fixtures.request("line", Modality.SPEECH, WorkClass.PREPARATION, A, "speech-a", SMALL, clock.instant());
        coordinator.submit(speech);
        Grant grant = executor.pass().grants().getFirst();

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.text()).isEqualTo(TextState.UNTOUCHED);
        assertThat(events).doesNotContain("unload " + TEXT, "save " + TEXT);
    }

    @Test
    void aFailedTextUnloadLoadsNothingKeepsTheTextModelUsableAndTheRequestAsksAgain() {
        warmText();
        Grant grant = grantImage();
        server.controlAnswers("unload", 500, "{\"error\":\"no\"}");

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.SWAP_FAILED);
        assertThat(outcome.steps()).isZero();
        assertThat(events).doesNotContain("worker load " + IMAGE);
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.REVOKED);
        assertThat(server.residency(TEXT)).isEqualTo("loaded");
        assertThat(executor.prepareText(turnGrant()).status()).as("the text model still serves").isEqualTo(Status.READY);
    }

    private Grant turnGrant() {
        coordinator.submit(textTurn());
        return executor.pass().grants().getFirst();
    }

    @Test
    void anUnloadTheServerAcceptsButNeverDoesCountsAsFailed() {
        warmText();
        Grant grant = grantImage();
        server.controlAnswers("unload", 200, "{\"success\":true}");

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.SWAP_FAILED);
        assertThat(worker.holds(IMAGE)).isFalse();
        assertThat(executor.metrics()).containsEntry("unload_failures", 1L).containsEntry("swap_failures", 1L);
    }

    @Test
    void theRequestsOfAFailedSwapAskAgainAfterThePauseKeepingTheirAge() {
        warmText();
        Grant grant = grantImage();
        var request = grant.admitted().getFirst();
        server.controlAnswers("unload", 500, "{}");
        executor.runBatch(grant, steps(1));

        assertThat(executor.pass().grants()).as("not before the pause").isEmpty();
        clock.advance(RECHECK);
        Grant again = executor.pass().grants().getFirst();

        assertThat(again.admitted()).containsExactly(request);
        assertThat(again.admitted().getFirst().enqueuedAt()).isEqualTo(request.enqueuedAt());
        assertThat(executor.runBatch(again, steps(1)).status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void aFailedUnloadOfTheBatchModelKeepsItsLeaseReconcilingUntilARetryConfirmsIt() {
        warmText();
        Grant grant = grantImage();
        worker.unloadFailures = 1;

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.reconciling()).isTrue();
        assertThat(outcome.text()).as("the image model still holds the room").isEqualTo(TextState.NOT_RESIDENT);
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.RECONCILING);
        assertThat(coordinator.held()).isEqualTo(LARGE);
        assertThat(worker.holds(IMAGE)).isTrue();
        assertThat(executor.metrics()).containsEntry("leases_reconciling", 1L).containsEntry("capacity_held_gpu_bytes", 60L);

        executor.pass();
        assertThat(stored(grant.lease())).as("not before the pause").isEqualTo(LeaseStatus.RECONCILING);
        clock.advance(RECHECK);
        executor.pass();

        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.RELEASED);
        assertThat(coordinator.held()).isEqualTo(Demand.NONE);
        assertThat(worker.holds(IMAGE)).isFalse();
        assertThat(server.residency(TEXT)).as("the same pass brings the text model back").isEqualTo("loaded");
        assertThat(events.indexOf("worker unload " + IMAGE)).isLessThan(events.lastIndexOf("load " + TEXT));
    }

    @Test
    void aFailedReloadDoesNotFailTheBatchAndALaterPassRecoversTheTextModelWithItsCache() {
        warmText();
        Grant grant = grantImage();
        server.controlAnswers("load", 500, "{}");

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.text()).isEqualTo(TextState.NOT_RESIDENT);
        assertThat(server.residency(TEXT)).isEqualTo("unloaded");
        assertThat(executor.metrics()).containsEntry("load_failures", 1L).containsEntry("text_models_swapped_out", 1L);

        executor.pass();
        assertThat(server.residency(TEXT)).as("not before the pause").isEqualTo("unloaded");
        clock.advance(RECHECK);
        executor.pass();

        assertThat(server.residency(TEXT)).isEqualTo("loaded");
        assertThat(events).endsWith("load " + TEXT, "restore " + TEXT);
        assertThat(executor.metrics()).containsEntry("text_models_swapped_out", 0L).containsEntry("text_models_resident", 1L);
    }

    @Test
    void aTextTurnRecoversTheModelItselfWhenTheReloadFailed() {
        warmText();
        Grant grant = grantImage();
        server.controlAnswers("load", 500, "{}");
        executor.runBatch(grant, steps(1));

        Outcome turn = executor.prepareText(turnGrant());

        assertThat(turn.status()).isEqualTo(Status.READY);
        assertThat(turn.text()).isEqualTo(TextState.RESTORED);
        assertThat(server.residency(TEXT)).isEqualTo("loaded");
    }

    @Test
    void aLoadThatFailsOnTheServerAfterBeingAcceptedIsSeenInTheStatus() {
        warmText();
        Grant grant = grantImage();
        server.controlAnswers("load", 200, "{\"success\":true}");
        // The batch evicts and later the reload is accepted, but the server reports its child failed.
        Outcome outcome = executor.runBatch(grant, () -> {
            server.marks(TEXT, "failed");
            return false;
        });

        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.text()).isEqualTo(TextState.NOT_RESIDENT);
    }

    @Test
    void aLoadTheServerFinishesLaterIsWaitedFor() {
        warmText();
        server.loadsAfterReads(2);

        Outcome outcome = executor.runBatch(grantImage(), steps(1));

        assertThat(outcome.text()).isEqualTo(TextState.RESTORED);
    }

    @Test
    void aFailedSlotRestoreLeavesTheTextModelLoadedAndCold() {
        warmText();
        Grant grant = grantImage();
        server.controlAnswers("restore", 500, "{}");

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.text()).isEqualTo(TextState.COLD);
        assertThat(server.residency(TEXT)).isEqualTo("loaded");
        assertThat(executor.metrics()).containsEntry("slot_restore_failures", 1L).containsEntry("text_models_swapped_out", 0L);
        events.clear();
        assertThat(executor.prepareText(turnGrant()).status()).isEqualTo(Status.READY);
        assertThat(events).as("no second load and no second restore").doesNotContain("load " + TEXT, "restore " + TEXT);
    }

    @Test
    void aFailedSlotSaveCostsTheCacheOnlyAndNoRestoreIsTried() {
        warmText();
        Grant grant = grantImage();
        server.controlAnswers("save", 501, "{}");

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.text()).isEqualTo(TextState.COLD);
        assertThat(events).contains("worker load " + IMAGE, "load " + TEXT).doesNotContain("restore " + TEXT);
        assertThat(executor.metrics()).containsEntry("slot_save_failures", 1L);
    }

    @Test
    void aBatchModelThatWillNotLoadGivesUpAndTheTextModelComesBack() {
        warmText();
        Grant grant = grantImage();
        worker.loadFailures = 1;

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.LOAD_FAILED);
        assertThat(outcome.text()).isEqualTo(TextState.RESTORED);
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.REVOKED);
        assertThat(coordinator.free()).isEqualTo(CAPACITY);
        assertThat(server.residency(TEXT)).isEqualTo("loaded");
    }

    @Test
    void aFailedJobStepReleasesTheLeaseAndUnloadsItsModel() {
        warmText();
        Grant grant = grantImage();

        Outcome outcome = executor.runBatch(grant, () -> {
            throw new IOException("the worker crashed");
        });

        assertThat(outcome.status()).isEqualTo(Status.JOB_FAILED);
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.RELEASED);
        assertThat(worker.holds(IMAGE)).isFalse();
        assertThat(outcome.text()).isEqualTo(TextState.RESTORED);
    }

    @Test
    void aJobYieldsBetweenStepsToInteractiveDemandAndItsLeaseIsRevokedOnlyAfterTheUnload() {
        warmText();
        Grant grant = grantImage();
        var turn = textTurn();
        AtomicReference<LeaseStatus> statusAtUnload = new AtomicReference<>();
        AtomicReference<LeaseStatus> statusAtTextLoad = new AtomicReference<>();
        observer = event -> {
            if (event.equals("worker unload " + IMAGE)) {
                statusAtUnload.set(stored(grant.lease()));
            } else if (event.equals("load " + TEXT)) {
                statusAtTextLoad.set(stored(grant.lease()));
            }
        };
        AtomicInteger done = new AtomicInteger();
        events.clear();

        Outcome outcome = executor.runBatch(grant, () -> {
            int step = done.incrementAndGet();
            events.add("step " + step);
            if (step == 2) {
                coordinator.submit(turn);
            }
            return step < 25;
        });

        assertThat(outcome.status()).isEqualTo(Status.INTERRUPTED);
        assertThat(outcome.steps()).isEqualTo(2);
        assertThat(outcome.text()).isEqualTo(TextState.RESTORED);
        assertThat(events).containsSubsequence("step 2", "worker unload " + IMAGE, "load " + TEXT, "restore " + TEXT);
        assertThat(events).doesNotContain("step 3");
        assertThat(statusAtUnload).hasValue(LeaseStatus.ACTIVE);
        assertThat(statusAtTextLoad).hasValue(LeaseStatus.REVOKED);
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.REVOKED);
        assertThat(executor.metrics()).containsEntry("interruptions", 1L).containsEntry("job_steps", 2L);

        Grant turnGrant = executor.pass().grants().getFirst();
        assertThat(turnGrant.admitted()).containsExactly(turn);
        events.clear();
        assertThat(executor.prepareText(turnGrant).status()).isEqualTo(Status.READY);
        assertThat(events).as("the turn pays no second load").doesNotContain("load " + TEXT);
    }

    @Test
    void aJobOnAnotherThreadStopsAtTheNextStepWhenInteractiveDemandArrives() throws Exception {
        warmText();
        Grant grant = grantImage();
        CountDownLatch stepped = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Outcome> result = new AtomicReference<>();
        Thread runner = new Thread(() -> result.set(executor.runBatch(grant, () -> {
            stepped.countDown();
            if (stepped.getCount() == 0) {
                release.await(10, TimeUnit.SECONDS);
            }
            return true;
        })));
        runner.start();
        assertThat(stepped.await(10, TimeUnit.SECONDS)).isTrue();

        coordinator.submit(textTurn());
        release.countDown();
        runner.join(10_000);

        assertThat(runner.isAlive()).isFalse();
        assertThat(result.get().status()).isEqualTo(Status.INTERRUPTED);
        assertThat(result.get().steps()).isEqualTo(2);
        assertThat(executor.pass().grants()).hasSize(1);
    }

    @Test
    void anInteractiveDemandThatFitsBesideTheJobDoesNotInterruptIt() {
        var speech = fixtures.request("line", Modality.SPEECH, WorkClass.PREPARATION, A, "speech-a", SMALL, clock.instant());
        coordinator.submit(speech);
        Grant grant = executor.pass().grants().getFirst();
        AtomicInteger done = new AtomicInteger();

        Outcome outcome = executor.runBatch(grant, () -> {
            if (done.incrementAndGet() == 1) {
                coordinator.submit(textTurn());
            }
            return done.get() < 3;
        });

        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.steps()).isEqualTo(3);
    }

    @Test
    void noLeaseStartsWhileATextRequestIsQueued() {
        warmText();
        Grant grant = grantImage();
        coordinator.submit(textTurn());

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.DEFERRED);
        assertThat(events).doesNotContain("unload " + TEXT, "worker load " + IMAGE);
        assertThat(executor.metrics()).containsEntry("deferrals", 1L);
    }

    @Test
    void noLeaseStartsWhileATextRequestIsExpectedAndItAsksAgainLater() {
        warmText();
        Grant grant = grantImage();
        expected = true;

        assertThat(executor.runBatch(grant, steps(1)).status()).isEqualTo(Status.DEFERRED);
        assertThat(events).doesNotContain("unload " + TEXT);

        expected = false;
        clock.advance(RECHECK);
        Grant again = executor.pass().grants().getFirst();
        assertThat(executor.runBatch(again, steps(1)).status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void lowFreeMemoryDelaysABatchWithoutTouchingTheTextModelAndItRunsOnceMemoryReturns() {
        warmText();
        unowned = 50;
        Grant grant = grantImage();

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.DELAYED);
        assertThat(events).doesNotContain("save " + TEXT, "unload " + TEXT, "worker load " + IMAGE);
        assertThat(server.residency(TEXT)).isEqualTo("loaded");
        assertThat(executor.metrics()).containsEntry("contention_delays", 1L).containsEntry("requests_delayed", 1L);

        unowned = 0;
        clock.advance(RECHECK);
        Grant again = executor.pass().grants().getFirst();
        assertThat(executor.runBatch(again, steps(1)).status()).isEqualTo(Status.COMPLETED);
        assertThat(executor.metrics()).containsEntry("requests_delayed", 0L);
    }

    @Test
    void aDelayedTextTurnStopsCountingAsDelayedOnceItsModelIsReady() {
        unowned = 50;
        assertThat(executor.prepareText(turnGrant()).status()).isEqualTo(Status.DELAYED);
        assertThat(executor.metrics()).containsEntry("requests_delayed", 1L);

        unowned = 0;
        clock.advance(RECHECK);

        assertThat(executor.prepareText(executor.pass().grants().getFirst()).status()).isEqualTo(Status.READY);
        assertThat(executor.metrics()).containsEntry("requests_delayed", 0L);
    }

    @Test
    void aDelayedBatchThatThenWillNotLoadStopsCountingAsDelayedAndNeverAsksAgain() {
        unowned = 50;
        assertThat(executor.runBatch(grantImage(), steps(1)).status()).isEqualTo(Status.DELAYED);

        unowned = 0;
        worker.loadFailures = 1;
        clock.advance(RECHECK);
        Outcome outcome = executor.runBatch(executor.pass().grants().getFirst(), steps(1));

        assertThat(outcome.status()).isEqualTo(Status.LOAD_FAILED);
        assertThat(executor.metrics()).containsEntry("requests_delayed", 0L);
        clock.advance(RECHECK);
        assertThat(executor.pass().grants()).isEmpty();
    }

    @Test
    void contentionThatOutlastsThePatienceFallsBackAndLeavesTheOtherApplicationAlone() {
        warmText();
        unowned = 50;
        Outcome outcome = executor.runBatch(grantImage(), steps(1));
        assertThat(outcome.status()).isEqualTo(Status.DELAYED);
        for (int attempt = 0; attempt < 2; attempt++) {
            clock.advance(RECHECK);
            outcome = executor.runBatch(executor.pass().grants().getFirst(), steps(1));
            assertThat(outcome.status()).isEqualTo(Status.DELAYED);
        }

        clock.advance(RECHECK);
        outcome = executor.runBatch(executor.pass().grants().getFirst(), steps(1));

        assertThat(outcome.status()).isEqualTo(Status.FELL_BACK);
        assertThat(outcome.members()).hasSize(1);
        assertThat(coordinator.free()).isEqualTo(CAPACITY);
        assertThat(unowned).as("the other application keeps what it holds").isEqualTo(50);
        assertThat(events).as("the text model never moved").doesNotContain("unload " + TEXT);
        assertThat(server.residency(TEXT)).isEqualTo("loaded");
        assertThat(executor.metrics()).containsEntry("contention_fallbacks", 1L).containsEntry("requests_delayed", 0L);
        clock.advance(RECHECK);
        assertThat(executor.pass().grants()).as("given up for good").isEmpty();
    }

    @Test
    void timeSpentDeferredDoesNotCountAgainstTheContentionPatience() {
        warmText();
        expected = true;
        assertThat(executor.runBatch(grantImage(), steps(1)).status()).isEqualTo(Status.DEFERRED);
        expected = false;
        unowned = 50;
        clock.advance(Duration.ofSeconds(60));

        Outcome outcome = executor.runBatch(executor.pass().grants().getFirst(), steps(1));

        assertThat(outcome.status()).as("the first contention, well past the patience since the deferral")
                .isEqualTo(Status.DELAYED);
    }

    @Test
    void memoryThatDoesNotComeFreeAfterTheUnloadStopsTheBatchAndBringsTheTextModelBack() {
        warmText();
        Grant grant = grantImage();
        // Another application takes the room between the gate and the unload.
        observer = event -> {
            if (event.equals("unload " + TEXT)) {
                unowned = 50;
            }
        };

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.DELAYED);
        assertThat(events).doesNotContain("worker load " + IMAGE);
        assertThat(outcome.text()).as("50 free is too little for the text model too").isEqualTo(TextState.NOT_RESIDENT);

        unowned = 0;
        clock.advance(RECHECK);
        var pass = executor.pass();
        assertThat(server.residency(TEXT)).isEqualTo("unloaded");
        assertThat(pass.grants()).as("the delayed request goes first").hasSize(1);
    }

    @Test
    void anUnknownReadingRefusesABatchButNotATextLoad() {
        readable = false;

        Outcome turn = executor.prepareText(turnGrant());
        assertThat(turn.status()).as("text keeps the game playable").isEqualTo(Status.READY);
        assertThat(executor.finishText(turn.members().getFirst())).isTrue();

        Outcome batch = executor.runBatch(grantImage(), steps(1));
        assertThat(batch.status()).isEqualTo(Status.DELAYED);
        assertThat(worker.holds(IMAGE)).isFalse();
        assertThat(executor.metrics().get("readings_unavailable")).isPositive();
    }

    @Test
    void aReadingUnderTheFloorMakesARunningJobYieldAndRecordsIt() {
        var floored = executor(new LeaseExecutorSettings(0, 5, 20, 3, Duration.ofSeconds(1), RECHECK,
                Duration.ofSeconds(30), Duration.ofMinutes(5)));
        floored.start();
        coordinator.submit(imageJob());
        Grant grant = floored.pass().grants().getFirst();
        AtomicInteger done = new AtomicInteger();

        Outcome outcome = floored.runBatch(grant, () -> {
            if (done.incrementAndGet() == 2) {
                unowned = 30;
            }
            return true;
        });

        assertThat(outcome.status()).isEqualTo(Status.CONTENTION_YIELDED);
        assertThat(outcome.steps()).isEqualTo(2);
        assertThat(worker.holds(IMAGE)).isFalse();
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.REVOKED);
        assertThat(floored.metrics()).containsEntry("contention_yields", 1L);
    }

    @Test
    void aLongJobRenewsItsLeaseAndStopsAtItsTimeCap() {
        var capped = executor(new LeaseExecutorSettings(0, 5, 0, 3, Duration.ofSeconds(1), RECHECK, Duration.ofSeconds(30),
                Duration.ofSeconds(100)));
        capped.start();
        coordinator.submit(imageJob());
        Grant grant = capped.pass().grants().getFirst();

        Outcome outcome = capped.runBatch(grant, () -> {
            clock.advance(Duration.ofSeconds(40));
            return true;
        });

        assertThat(outcome.status()).isEqualTo(Status.TIME_CAPPED);
        assertThat(outcome.steps()).as("three steps of 40 s, past 60 s of lifetime").isEqualTo(3);
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.REVOKED);
        assertThat(store.rows.get(grant.lease().id()).expiresAt()).isAfter(grant.lease().expiresAt());
    }

    @Test
    void anExpiredRunningLeaseKeepsItsCapacityUntilItsModelIsGone() {
        Grant grant = grantImage();
        var next = fixtures.request("next", Modality.IMAGE, WorkClass.PREPARATION, B, "image-b", LARGE, clock.instant());

        Outcome outcome = executor.runBatch(grant, () -> {
            // The step hangs past the lease's lifetime while another request waits.
            clock.advance(Duration.ofSeconds(61));
            coordinator.submit(next);
            var pass = executor.pass();
            assertThat(pass.expired()).hasSize(1);
            assertThat(pass.grants()).as("the model is still resident").isEmpty();
            assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.RECONCILING);
            assertThat(coordinator.held()).isEqualTo(LARGE);
            return true;
        });

        assertThat(outcome.status()).isEqualTo(Status.LOST);
        assertThat(outcome.reconciling()).isFalse();
        assertThat(worker.holds(IMAGE)).isFalse();
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.RELEASED);
        assertThat(executor.pass().grants().getFirst().admitted()).containsExactly(next);
    }

    @Test
    void anExpiredRunningLeaseWhoseUnloadFailsStaysReconcilingUntilARetry() {
        Grant grant = grantImage();
        worker.unloadFailures = 1;

        Outcome outcome = executor.runBatch(grant, () -> {
            clock.advance(Duration.ofSeconds(61));
            executor.pass();
            return true;
        });

        assertThat(outcome.status()).isEqualTo(Status.LOST);
        assertThat(outcome.reconciling()).isTrue();
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.RECONCILING);
        clock.advance(RECHECK);
        executor.pass();
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.RELEASED);
    }

    @Test
    void anExpiredTextLeaseIsUnloadedBeforeItsCapacityIsReusedAndAFailedUnloadKeepsTheHold() {
        var turn = textTurn();
        coordinator.submit(turn);
        Grant grant = executor.pass().grants().getFirst();
        executor.prepareText(grant);
        server.controlAnswers("unload", 500, "{}");
        var other = fixtures.request("art", Modality.IMAGE, WorkClass.PREPARATION, A, IMAGE, LARGE, clock.instant());
        coordinator.submit(other);

        clock.advance(Duration.ofSeconds(60));
        var first = executor.pass();

        assertThat(first.expired()).hasSize(1);
        assertThat(first.grants()).as("expired, but still resident").isEmpty();
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.RECONCILING);
        assertThat(coordinator.held()).isEqualTo(LARGE);
        assertThat(server.residency(TEXT)).isEqualTo("loaded");

        clock.advance(RECHECK);
        var second = executor.pass();
        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.RELEASED);
        assertThat(server.residency(TEXT)).isEqualTo("unloaded");
        assertThat(second.grants().getFirst().admitted()).containsExactly(other);
    }

    @Test
    void aLeaseExpiredWhileWaitingToStartLoadsNothing() {
        Grant grant = grantImage();
        clock.advance(Duration.ofSeconds(60));
        executor.pass();

        Outcome outcome = executor.runBatch(grant, steps(1));

        assertThat(outcome.status()).isEqualTo(Status.LOST);
        assertThat(events).doesNotContain("worker load " + IMAGE);
    }

    @Test
    void aRestartUnloadsWhatTheEarlierProcessLeasedAndSwapsOutWhatItLeftWarm() {
        // An earlier process held an image lease and left a text model warm.
        var earlier = new AdmissionCoordinator(settings(), store, clock, AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
        earlier.submit(imageJob());
        var old = earlier.poll().grants().getFirst().lease();
        worker.leaves(IMAGE);
        server.marks(TEXT, "loaded");

        var coordinator = new AdmissionCoordinator(settings(), store, clock, AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
        var restarted = executor(coordinator, SETTINGS, Map.of(Modality.TEXT, LocalAdapters.control(server), Modality.IMAGE,
                worker));
        restarted.start();

        assertThat(worker.holds(IMAGE)).isFalse();
        assertThat(stored(old)).isEqualTo(LeaseStatus.RELEASED);
        assertThat(coordinator.held()).isEqualTo(Demand.NONE);

        var image = imageJob();
        coordinator.submit(image);
        Outcome outcome = restarted.runBatch(restarted.pass().grants().getFirst(), steps(1));
        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(events).containsSubsequence("save " + TEXT, "unload " + TEXT, "worker load " + IMAGE);
        assertThat(outcome.text()).as("its size is unknown, so only a text request brings it back")
                .isEqualTo(TextState.NOT_RESIDENT);

        coordinator.submit(textTurn());
        Outcome turn = restarted.prepareText(restarted.pass().grants().getFirst());
        assertThat(turn.status()).isEqualTo(Status.READY);
        assertThat(turn.text()).isEqualTo(TextState.RESTORED);
        assertThat(server.residency(TEXT)).isEqualTo("loaded");
    }

    @Test
    void aRecoveredLeaseStaysHeldWhileItsModelCannotBeUnloaded() {
        var earlier = new AdmissionCoordinator(settings(), store, clock, AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
        earlier.submit(imageJob());
        var old = earlier.poll().grants().getFirst().lease();
        worker.leaves(IMAGE);
        worker.unloadFailures = 1;

        var coordinator = new AdmissionCoordinator(settings(), store, clock, AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
        var restarted = executor(coordinator, SETTINGS, Map.of(Modality.TEXT, LocalAdapters.control(server), Modality.IMAGE,
                worker));
        restarted.start();

        assertThat(stored(old)).isEqualTo(LeaseStatus.RECONCILING);
        assertThat(coordinator.held()).isEqualTo(LARGE);
        clock.advance(RECHECK);
        restarted.pass();
        assertThat(stored(old)).isEqualTo(LeaseStatus.RELEASED);
    }

    @Test
    void aServerThatIsDownAtStartStopsBatchesUntilItAnswersBecauseItMayHoldAModel() {
        server.modelListAnswers(503, "{}");
        var coordinator = new AdmissionCoordinator(settings(), store, clock, AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
        var restarted = executor(coordinator, SETTINGS, Map.of(Modality.TEXT, LocalAdapters.control(server), Modality.IMAGE,
                worker));
        restarted.start();
        coordinator.submit(imageJob());

        Outcome outcome = restarted.runBatch(restarted.pass().grants().getFirst(), steps(1));

        assertThat(outcome.status()).isEqualTo(Status.SWAP_FAILED);
        assertThat(worker.holds(IMAGE)).isFalse();
    }

    @Test
    void aBackgroundTextHolderIsToldToYieldAndItsModelStaysWarm() {
        var plan = fixtures.text("plan", WorkClass.CONTINUITY, A, TEXT, LARGE);
        coordinator.submit(plan);
        Grant grant = executor.pass().grants().getFirst();
        executor.prepareText(grant);
        var turn = fixtures.text("turn", WorkClass.INTERACTIVE, B, "text-b", LARGE);
        coordinator.submit(turn);

        assertThat(executor.shouldYield(grant.lease().id())).isTrue();
        assertThat(executor.yieldText(grant.lease().id())).containsExactly(plan.id());

        assertThat(stored(grant.lease())).isEqualTo(LeaseStatus.REVOKED);
        assertThat(server.residency(TEXT)).as("warm until something needs the room").isEqualTo("loaded");
        Outcome next = executor.prepareText(executor.pass().grants().getFirst());
        assertThat(next.status()).isEqualTo(Status.READY);
        assertThat(events).containsSubsequence("save " + TEXT, "unload " + TEXT, "load text-b");
        assertThat(executor.yieldText(grant.lease().id())).isEmpty();
    }

    @Test
    void aSwappedOutModelIsNotReloadedBesideAWarmOneItDoesNotFitBeside() {
        warmText();
        turnOn(OTHER_TEXT, LARGE);

        assertTextAStaysOutBesideTextB();
    }

    @Test
    void anUnknownReadingDoesNotLetASwappedOutModelBackBesideAWarmOne() {
        readable = false;
        warmText();
        turnOn(OTHER_TEXT, LARGE);

        assertTextAStaysOutBesideTextB();
    }

    @Test
    void aCardLargerThanTheBudgetDoesNotLetASwappedOutModelBackBesideAWarmOne() {
        card = 1_000;
        warmText();
        turnOn(OTHER_TEXT, LARGE);

        assertTextAStaysOutBesideTextB();
    }

    @Test
    void aSwappedOutModelIsNotReloadedBesideAWarmOneWhenOnlySystemMemoryRunsOut() {
        Demand ramHeavy = new Demand(10, 60);
        turnOn(TEXT, ramHeavy);
        turnOn(OTHER_TEXT, ramHeavy);

        assertTextAStaysOutBesideTextB();
    }

    @Test
    void aTurnOnTheSwappedOutModelStillBringsItBackAndSwapsTheWarmOneOut() {
        warmText();
        turnOn(OTHER_TEXT, LARGE);
        events.clear();

        turnOn(TEXT, LARGE);

        assertThat(events).containsSubsequence("save " + OTHER_TEXT, "unload " + OTHER_TEXT, "load " + TEXT, "restore " + TEXT);
        assertThat(server.residency(OTHER_TEXT)).isEqualTo("unloaded");
        assertThat(server.residency(TEXT)).isEqualTo("loaded");
    }

    @Test
    void afterABatchOnlyTheTextModelLastInUseComesBackWhenTwoDoNotFitTogether() {
        warmText();
        turnOn(OTHER_TEXT, LARGE);

        Outcome outcome = executor.runBatch(grantImage(), steps(1));

        assertThat(outcome.status()).isEqualTo(Status.COMPLETED);
        assertThat(outcome.text()).as("the worst state of the two").isEqualTo(TextState.NOT_RESIDENT);
        assertThat(server.residency(OTHER_TEXT)).isEqualTo("loaded");
        assertTextAStaysOutBesideTextB();
    }

    @Test
    void afterABatchTwoTextModelsThatFitTogetherBothComeBack() {
        card = 1_000;
        Demand half = new Demand(50, 20);
        turnOn(TEXT, half);
        turnOn(OTHER_TEXT, half);
        assertThat(server.residency(TEXT)).as("they fit together, so the second caused no swap").isEqualTo("loaded");

        Outcome outcome = executor.runBatch(grantImage(), steps(1));

        assertThat(outcome.text()).isEqualTo(TextState.RESTORED);
        assertThat(server.residency(TEXT)).isEqualTo("loaded");
        assertThat(server.residency(OTHER_TEXT)).isEqualTo("loaded");
        assertThat(executor.metrics()).containsEntry("text_models_resident", 2L).containsEntry("text_models_swapped_out", 0L);
    }

    @Test
    void aRequestPutBackIsCancelledAndNeverGrantedAgain() {
        warmText();
        expected = true;
        Grant grant = grantImage();
        UUID request = grant.admitted().getFirst().id();
        assertThat(executor.runBatch(grant, steps(1)).status()).isEqualTo(Status.DEFERRED);
        assertThat(executor.metrics()).containsEntry("requests_delayed", 1L);

        assertThat(executor.cancel(request)).isTrue();

        assertThat(executor.metrics()).containsEntry("requests_delayed", 0L);
        expected = false;
        clock.advance(RECHECK);
        assertThat(executor.pass().grants()).as("the withdrawn request does not ask again").isEmpty();
        assertThat(coordinator.waiting()).isEmpty();
        assertThat(executor.cancel(request)).as("already withdrawn").isFalse();
    }

    @Test
    void aRequestBackInTheQueueIsCancelledAndStopsCountingAsDelayed() {
        warmText();
        expected = true;
        Grant grant = grantImage();
        UUID request = grant.admitted().getFirst().id();
        assertThat(executor.runBatch(grant, steps(1)).status()).isEqualTo(Status.DEFERRED);
        expected = false;
        var turn = textTurn();
        coordinator.submit(turn);
        assertThat(executor.prepareText(executor.pass().grants().getFirst()).status()).isEqualTo(Status.READY);
        clock.advance(RECHECK);
        assertThat(executor.pass().grants()).as("the text lease holds the room").isEmpty();
        assertThat(executor.metrics()).containsEntry("requests_waiting", 1L).containsEntry("requests_delayed", 1L);

        assertThat(executor.cancel(request)).isTrue();

        assertThat(executor.metrics()).containsEntry("requests_waiting", 0L).containsEntry("requests_delayed", 0L);
        assertThat(executor.finishText(turn.id())).isTrue();
        clock.advance(RECHECK);
        assertThat(executor.pass().grants()).isEmpty();
    }

    @Test
    void aRequestThatHoldsALeaseIsNotWithdrawnButIsNotPutBackWhenTheLeaseGivesWay() {
        warmText();
        expected = true;
        Grant grant = grantImage();
        UUID request = grant.admitted().getFirst().id();
        assertThat(executor.runBatch(grant, steps(1)).status()).isEqualTo(Status.DEFERRED);
        clock.advance(RECHECK);
        Grant again = executor.pass().grants().getFirst();

        assertThat(executor.cancel(request)).as("it holds a lease").isFalse();
        assertThat(coordinator.leases()).hasSize(1);
        assertThat(executor.metrics()).containsEntry("requests_delayed", 0L);

        assertThat(executor.runBatch(again, steps(1)).status()).isEqualTo(Status.DEFERRED);
        assertThat(executor.metrics()).containsEntry("requests_delayed", 0L);
        clock.advance(RECHECK);
        assertThat(executor.pass().grants()).as("cancelled, so it does not ask again").isEmpty();
    }

    @Test
    void aWaitingRequestIsCancelledAndAnUnknownOneIsNot() {
        var plan = fixtures.text("plan", WorkClass.CONTINUITY, A, TEXT, LARGE);
        coordinator.submit(plan);

        assertThat(executor.cancel(plan.id())).isTrue();

        assertThat(coordinator.waiting()).isEmpty();
        assertThat(executor.cancel(UUID.randomUUID())).isFalse();
    }

    @Test
    void aLeaseThatExpiresBeforeItsRetryStartsEndsTheDelayOfItsMembers() {
        warmText();
        expected = true;
        assertThat(executor.runBatch(grantImage(), steps(1)).status()).isEqualTo(Status.DEFERRED);
        clock.advance(RECHECK);
        assertThat(executor.pass().grants()).hasSize(1);
        assertThat(executor.metrics()).containsEntry("requests_delayed", 1L);

        clock.advance(Duration.ofSeconds(60));
        assertThat(executor.pass().expired()).hasSize(1);

        assertThat(executor.metrics()).containsEntry("requests_delayed", 0L);
    }

    @Test
    void aModalityWithNothingToLoadItIsRefusedAtOnce() {
        var noWorker = executor(coordinator, SETTINGS, Map.of(Modality.TEXT, LocalAdapters.control(server)));
        noWorker.start();
        coordinator.submit(imageJob());

        Outcome outcome = noWorker.runBatch(noWorker.pass().grants().getFirst(), steps(1));

        assertThat(outcome.status()).isEqualTo(Status.LOAD_FAILED);
        assertThat(coordinator.free()).isEqualTo(CAPACITY);
    }

    @Test
    void theExecutorRefusesACoordinatorThatFreesExpiredCapacityAtOnceAndAMismatchedGrant() {
        var accounting = new AdmissionCoordinator(settings(), store, clock);
        assertThatThrownBy(() -> executor(accounting, SETTINGS, Map.of(Modality.TEXT, LocalAdapters.control(server))))
                .isInstanceOf(IllegalArgumentException.class);

        Grant text = turnGrant();
        assertThatThrownBy(() -> executor.runBatch(text, steps(1))).isInstanceOf(IllegalArgumentException.class);
        Grant image = new Grant(new Lease(UUID.randomUUID(), Modality.IMAGE, IMAGE, LARGE, List.of(), T0, T0.plusSeconds(1)),
                List.of(), true);
        assertThatThrownBy(() -> executor.prepareText(image)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metricsNameLeasesWaitsSwapsInterruptionsAndContentionInNumbersAlone() {
        warmText();
        clock.advance(Duration.ofSeconds(5));
        var image = fixtures.request("art", Modality.IMAGE, WorkClass.PREPARATION, A, IMAGE, LARGE, T0);
        coordinator.submit(image);
        Grant grant = executor.pass().grants().getFirst();
        executor.runBatch(grant, steps(1));

        Map<String, Long> metrics = executor.metrics();

        assertThat(metrics).containsEntry("grants", 2L).containsEntry("wait_total_millis", 5_000L)
                .containsEntry("wait_max_millis", 5_000L).containsEntry("swaps_out", 1L).containsEntry("swaps_back", 1L)
                .containsEntry("slot_saves", 1L).containsEntry("slot_restores", 1L).containsEntry("job_steps", 1L)
                .containsEntry("interruptions", 0L).containsEntry("contention_delays", 0L).containsEntry("contention_fallbacks", 0L)
                .containsEntry("leases_active", 0L).containsEntry("leases_reconciling", 0L).containsEntry("requests_waiting", 0L)
                .containsEntry("jobs_running", 0L).containsEntry("text_models_resident", 1L)
                .containsEntry("capacity_free_gpu_bytes", 100L).containsEntry("capacity_free_ram_bytes", 100L);
        assertThat(metrics.keySet()).allMatch(name -> name.matches("[a-z_]+"));
    }

    @Test
    void metricsNeverCarryAKeyAnEndpointAModelAPromptOrAnOutput() {
        String marker = "sensitive-marker-text";
        warmText();
        Grant grant = grantImage();
        server.controlAnswers("save", 500, "{\"error\":\"" + marker + "\"}");
        server.controlAnswers("restore", 500, "{\"error\":\"" + marker + "\"}");
        worker.unloadFailures = 1;
        executor.runBatch(grant, () -> {
            throw new IOException("prompt text " + marker);
        });
        clock.advance(RECHECK);
        executor.pass();

        String rendered = executor.metrics().toString();

        assertThat(rendered).doesNotContain(marker, "prompt", TEXT, IMAGE, server.url(), "127.0.0.1", "error");
        assertThat(executor.metrics().values()).allMatch(value -> value >= 0);
    }
}
