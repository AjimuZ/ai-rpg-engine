package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.rpgengine.inference.admission.AdmissionFixtures.A;
import static org.rpgengine.inference.admission.AdmissionFixtures.AGING;
import static org.rpgengine.inference.admission.AdmissionFixtures.CAPACITY;
import static org.rpgengine.inference.admission.AdmissionFixtures.LARGE;
import static org.rpgengine.inference.admission.AdmissionFixtures.SMALL;
import static org.rpgengine.inference.admission.AdmissionFixtures.T0;
import static org.rpgengine.inference.admission.AdmissionFixtures.TTL;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageProvenance;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.ModelAllowlist;
import org.rpgengine.inference.ModelServerControl;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.scheduling.ScheduledTask;
import org.rpgengine.inference.scheduling.TextResidency;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.SessionScope;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * An image request through the real {@link TextResidencyDriver}, {@link LeaseExecutor} and
 * {@link AdmissionCoordinator}, with a recording image port, scripted workers as the text and image servers, a scripted
 * free-memory reading and an in-memory lease store: no GPU, server, adapter or database takes part, and nothing here
 * shows how a real one behaves. The stand-in card has 100 bytes and a large model needs 60. The tick is an hour, so
 * passes happen only when a test asks for one or a text claim arrives.
 */
class ImageAdmissionTest {
    private static final String TEXT = "text-a";
    private static final String CHECKPOINT = "image-a";
    private static final String BATCH_THREAD = "inference-image";
    private static final Duration RECHECK = Duration.ofSeconds(10);
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final Duration TIME_CAP = Duration.ofMinutes(5);
    private static final LeaseExecutorSettings SETTINGS = new LeaseExecutorSettings(0, 0, 0, 3, Duration.ofSeconds(1),
            RECHECK, PATIENCE, TIME_CAP);
    private static final Duration AT_ONCE = Duration.ZERO;
    private static final Duration LONG_WAIT = Duration.ofHours(1);
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(20);
    private static final Duration SHORT_CLOSE_WAIT = Duration.ofMillis(200);

    /** One job on the recording port; it ends when a test answers it, or at cancel when it is cancellable. */
    private final class RecordingHandle implements ImageHandle {
        final CompletableFuture<ImageResult> answer = new CompletableFuture<>();
        final AtomicInteger cancels = new AtomicInteger();
        volatile boolean cancellable = true;
        volatile ImageResult lateAnswer;

        @Override
        public CompletableFuture<ImageResult> result() {
            return answer;
        }

        @Override
        public boolean cancel() {
            events.add("cancel");
            cancels.incrementAndGet();
            if (lateAnswer != null) {
                answer.complete(lateAnswer);
                return false;
            }
            if (cancellable) {
                answer.complete(ImageResult.cancelled());
            }
            return true;
        }
    }

    /** Records every submission. By default each job completes at once. */
    private final class RecordingPort implements ImageGenerationPort {
        final List<ImageRequest> requests = Collections.synchronizedList(new ArrayList<>());
        final List<RecordingHandle> handles = Collections.synchronizedList(new ArrayList<>());
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();
        volatile boolean answerAtOnce = true;
        volatile boolean breaks;

        @Override
        public String backend() {
            return "recording";
        }

        @Override
        public ImageHandle submit(ImageRequest request) {
            events.add("submit");
            threads.add(Thread.currentThread());
            if (breaks) {
                throw new IllegalStateException("adapter broke its contract");
            }
            requests.add(request);
            var handle = new RecordingHandle();
            handles.add(handle);
            if (answerAtOnce) {
                handle.answer.complete(picture());
            }
            return handle;
        }
    }

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

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final SteppedClock clock = new SteppedClock(T0);
    private final MemoryLeaseStore store = new MemoryLeaseStore();
    private final AdmissionCoordinator coordinator = new AdmissionCoordinator(
            new AdmissionSettings(CAPACITY, TTL, AGING, Map.of(Modality.TEXT, 1, Modality.IMAGE, 4, Modality.SPEECH, 4)),
            store, clock, AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
    private final ScriptedWorker textServer = new ScriptedWorker(event -> events.add("text " + event));
    private final ScriptedWorker imageServer = new ScriptedWorker(event -> events.add("image " + event));
    private final RecordingPort port = new RecordingPort();
    private final RecordingListener listener = new RecordingListener();
    private volatile long free = 100;
    private volatile boolean expected;
    private LeaseExecutor executor;
    private LeasedImageAdmission images;
    private TextResidencyDriver driver;
    private InterruptibleControl interruptible;

    @BeforeEach
    void build() {
        build(SETTINGS, LARGE);
    }

    private void build(LeaseExecutorSettings settings, Demand imageDemand) {
        executor = new LeaseExecutor(coordinator, Map.of(Modality.TEXT, textServer, Modality.IMAGE, imageServer),
                () -> OptionalLong.of(free), () -> expected, settings, clock, clock::advance);
        images = new LeasedImageAdmission(executor, coordinator, imageDemand, clock, Duration.ofMillis(2),
                Duration.ofSeconds(5), CLOSE_WAIT);
        driver = new TextResidencyDriver(executor, coordinator, slot -> Optional.of(TEXT), LARGE, clock, Duration.ofHours(1),
                images);
    }

    /** The image server behind a control that honours interruption, with a pause that does too, as in production. */
    private void buildWithInterruptibleControl(Duration closeWait) {
        interruptible = new InterruptibleControl(imageServer);
        executor = new LeaseExecutor(coordinator, Map.of(Modality.TEXT, textServer, Modality.IMAGE, interruptible),
                () -> OptionalLong.of(free), () -> expected, SETTINGS, clock, duration -> {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                    clock.advance(duration);
                });
        images = new LeasedImageAdmission(executor, coordinator, LARGE, clock, Duration.ofMillis(2), Duration.ofSeconds(5),
                closeWait);
        driver = new TextResidencyDriver(executor, coordinator, slot -> Optional.of(TEXT), LARGE, clock, Duration.ofHours(1),
                images);
    }

    @AfterEach
    void stop() {
        if (interruptible != null) {
            interruptible.open();
        }
        port.handles.forEach(handle -> handle.answer.complete(ImageResult.cancelled()));
        driver.stop();
        images.close();
    }

    private static ImageResult picture() {
        return ImageResult.completed(new byte[] {1, 2, 3}, new ImageProvenance("recording", CHECKPOINT, null, null, 7, 20,
                6.0, 1024, 1024, "a", "b", "c", 3));
    }

    private static ImageRequest request(String model) {
        return new ImageRequest("a castle", "", model, 1024, 1024, 20, 6.0, 7, null);
    }

    private ImageHandle submit(WorkClass workClass, Duration wait) {
        return images.submit(port, request(CHECKPOINT), new ImageAdmission.Ticket(workClass, A, wait));
    }

    private static ImageResult resultOf(ImageHandle handle) throws Exception {
        return handle.result().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private void assertFallback(ImageHandle handle, String code) throws Exception {
        ImageResult result = resultOf(handle);
        assertThat(result.errorCode()).isEqualTo(code);
        assertThat(ImageAdmission.usesCatalogArt(result)).isTrue();
    }

    private long metric(String name) {
        return executor.metrics().get(name);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached");
            }
            Thread.sleep(2);
        }
    }

    /** The batch thread is on the recording port with {@code count} jobs so far. */
    private void awaitRunning(int count) throws InterruptedException {
        await(() -> port.handles.size() >= count);
    }

    private void awaitNoLease() throws InterruptedException {
        await(() -> metric("leases_active") == 0 && metric("jobs_running") == 0);
    }

    private void assertNothingLeftBehind() throws InterruptedException {
        awaitNoLease();
        assertThat(metric("requests_delayed")).isZero();
        assertThat(metric("requests_waiting")).isZero();
        assertThat(metric("leases_reconciling")).isZero();
        assertThat(imageServer.holds(CHECKPOINT)).isFalse();
    }

    /** A later pass, past the recheck pause and with room, must not run the request again. */
    private void assertNeverRunsLater(int jobs) throws Exception {
        free = 100;
        expected = false;
        clock.advance(RECHECK);
        driver.tickNow();
        driver.tickNow();
        assertThat(port.handles).hasSize(jobs);
        assertThat(metric("leases_active")).isZero();
    }

    private TextResidency.Claim claimText(WorkClass workClass) throws Exception {
        AiRole role = workClass == WorkClass.INTERACTIVE ? AiRole.NARRATOR : AiRole.QUEST_PLANNER;
        Audience audience = role.permits(Audience.PUBLIC) ? Audience.PUBLIC : Audience.PRIVILEGED;
        var task = new ScheduledTask(UUID.randomUUID(), new SessionScope(UUID.randomUUID(), UUID.randomUUID(), 0, role, audience,
                "a".repeat(64), "b".repeat(64)), workClass, InferenceSlot.STANDARD, "Task.", "{\"type\":\"object\"}",
                Duration.ofSeconds(60));
        TextResidency.Claim claim = driver.claim(task, listener);
        driver.awaitIdle();
        return claim;
    }

    // ---------------------------------------------------------------- the stepped job

    @Test
    void anImageRequestRunsThroughTheExecutorAsASteppedJobWithItsModelLeased() throws Exception {
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        assertThat(port.requests).as("nothing reaches the adapter before a lease").isEmpty();
        driver.tickNow();

        ImageResult result = resultOf(handle);
        assertThat(result.status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertThat(result.png()).containsExactly(1, 2, 3);
        assertThat(port.requests).containsExactly(request(CHECKPOINT));
        assertThat(port.threads).extracting(Thread::getName).containsExactly(BATCH_THREAD);
        awaitNoLease();
        assertThat(events).containsExactly("image worker load " + CHECKPOINT, "submit", "image worker unload " + CHECKPOINT);
        assertThat(metric("job_steps")).isEqualTo(1);
        // The grant reached the batch rather than being finished as an unwanted text grant.
        assertThat(store.rows.values()).singleElement().satisfies(row -> {
            assertThat(row.modality()).isEqualTo(Modality.IMAGE);
            assertThat(row.model()).isEqualTo(CHECKPOINT);
            assertThat(row.demand()).isEqualTo(LARGE);
            assertThat(row.status()).isEqualTo(LeaseStatus.RELEASED);
        });
        assertNothingLeftBehind();
    }

    @Test
    void theTextModelLeavesBeforeTheImageModelLoadsAndComesBackAfter() throws Exception {
        driver.tickNow();
        claimText(WorkClass.INTERACTIVE).release(false);
        driver.awaitIdle();
        assertThat(textServer.holds(TEXT)).isTrue();
        events.clear();

        ImageHandle handle = submit(WorkClass.PREPARATION, LONG_WAIT);
        driver.tickNow();
        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.COMPLETED);
        awaitNoLease();

        assertThat(events).containsSubsequence("text worker unload " + TEXT, "image worker load " + CHECKPOINT, "submit",
                "image worker unload " + CHECKPOINT, "text worker load " + TEXT);
        assertThat(textServer.holds(TEXT)).isTrue();
    }

    @Test
    void aStepIsOneBoundedWaitAndTheExecutorChecksBetweenSteps() throws Exception {
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);

        await(() -> metric("job_steps") >= 3);
        assertThat(port.requests).hasSize(1);
        assertThat(handle.result().toCompletableFuture()).isNotDone();

        port.handles.getFirst().answer.complete(picture());
        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertThat(port.handles.getFirst().cancels).hasValue(0);
        assertNothingLeftBehind();
    }

    @Test
    void aGrantedBatchRunsItsImagesOneAtATimeAndPaysForOneLoad() throws Exception {
        port.answerAtOnce = false;
        ImageHandle first = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        ImageHandle second = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);
        await(() -> metric("job_steps") >= 3);
        assertThat(port.handles).as("the second waits for the first").hasSize(1);

        port.handles.getFirst().answer.complete(picture());
        assertThat(resultOf(first).status()).isEqualTo(ImageResult.Status.COMPLETED);
        awaitRunning(2);
        port.handles.get(1).answer.complete(ImageResult.failed("image-server-busy"));

        assertThat(resultOf(second).errorCode()).isEqualTo("image-server-busy");
        assertNothingLeftBehind();
        assertThat(events.stream().filter(event -> event.startsWith("image worker load"))).hasSize(1);
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void aRequestThatJoinsARunningBatchIsServedByIt() throws Exception {
        port.answerAtOnce = false;
        ImageHandle first = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);

        ImageHandle joined = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        await(() -> metric("requests_waiting") == 0);
        port.handles.getFirst().answer.complete(picture());
        awaitRunning(2);
        port.handles.get(1).answer.complete(picture());

        assertThat(resultOf(first).status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertThat(resultOf(joined).status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertNothingLeftBehind();
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void aRequestThatJoinedAfterTheLastStepGetsItsFallback() throws Exception {
        ImageHandle late = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        UUID id = coordinator.waiting().getFirst().id();

        images.settle(new LeaseExecutor.Outcome(LeaseExecutor.Status.COMPLETED, 1, LeaseExecutor.TextState.UNTOUCHED, false,
                List.of(id)), new ImageSteppedJob(List.of(), Duration.ofMillis(1), Duration.ofMillis(1)));

        assertFallback(late, ImageAdmission.LOST);
    }

    @Test
    void textClaimsKeepWorkingBesideARunningImageThatFits() throws Exception {
        build(SETTINGS, SMALL);
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);

        TextResidency.Claim claim = claimText(WorkClass.INTERACTIVE);
        assertThat(listener.heard).containsExactly("ready");
        assertThat(textServer.holds(TEXT)).isTrue();
        assertThat(metric("leases_active")).isEqualTo(2);
        assertThat(handle.result().toCompletableFuture()).isNotDone();

        claim.release(false);
        driver.awaitIdle();
        port.handles.getFirst().answer.complete(picture());
        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertThat(port.handles.getFirst().cancels).hasValue(0);
        awaitNoLease();
    }

    // ---------------------------------------------------------------- cancellation and interruption

    @Test
    void aCallerCancelStopsTheJobAtTheNextStepAndCancelsTheBackendOnce() throws Exception {
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);

        assertThat(handle.cancel()).isTrue();

        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.CANCELLED);
        assertThat(ImageAdmission.usesCatalogArt(resultOf(handle))).isFalse();
        assertThat(port.handles.getFirst().cancels).hasValue(1);
        assertThat(handle.cancel()).as("already ended").isFalse();
        assertNothingLeftBehind();
        assertThat(events).containsSubsequence("cancel", "image worker unload " + CHECKPOINT);
        assertNeverRunsLater(1);
    }

    @Test
    void anAcknowledgedCancelDropsAnImageThatStillArrives() throws Exception {
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);
        RecordingHandle running = port.handles.getFirst();
        running.cancellable = false;

        handle.cancel();
        await(() -> running.cancels.get() == 1);
        assertThat(handle.result().toCompletableFuture()).isNotDone();
        running.answer.complete(picture());

        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.CANCELLED);
        assertThat(running.cancels).hasValue(1);
        assertNothingLeftBehind();
    }

    @Test
    void aCancelWhileQueuedEndsAtOnceAndNeverReachesTheAdapter() throws Exception {
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        assertThat(metric("requests_waiting")).isEqualTo(1);

        assertThat(handle.cancel()).isTrue();

        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.CANCELLED);
        await(() -> metric("requests_waiting") == 0);
        assertNeverRunsLater(0);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void aCancelWhilePutBackLeavesNoDelayAndNeverReachesTheAdapter() throws Exception {
        free = 0;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        await(() -> metric("requests_delayed") == 1);

        assertThat(handle.cancel()).isTrue();

        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.CANCELLED);
        await(() -> metric("requests_delayed") == 0);
        assertNeverRunsLater(0);
    }

    @Test
    void aGrantWhoseOnlyRequestWasCancelledReleasesItsLeaseWithoutLoading() throws Exception {
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        // Granted by a pass that the worker has not served yet: the cancel's withdrawal comes too late.
        Grant grant = executor.pass().grants().getFirst();
        handle.cancel();

        images.accept(grant);

        assertThat(metric("leases_active")).isZero();
        assertThat(store.rows.values()).singleElement().extracting(LeaseRecord::status).isEqualTo(LeaseStatus.RELEASED);
        assertThat(events).isEmpty();
    }

    @Test
    void anInterruptedBatchThreadStillWaitsForTheBackendAndConfirmsTheUnload() throws Exception {
        buildWithInterruptibleControl(CLOSE_WAIT);
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);
        RecordingHandle running = port.handles.getFirst();
        running.cancellable = false;

        port.threads.iterator().next().interrupt();

        await(() -> running.cancels.get() == 1);
        Thread.sleep(50);
        assertThat(metric("jobs_running")).as("the interrupt does not cut the wait for the backend short").isEqualTo(1);
        assertThat(events).doesNotContain("image worker unload " + CHECKPOINT);
        running.answer.complete(ImageResult.cancelled());

        assertFallback(handle, ImageAdmission.JOB_FAILED);
        assertThat(running.cancels).hasValue(1);
        assertNothingLeftBehind();
        assertThat(events).containsSubsequence("cancel", "image worker unload " + CHECKPOINT);
        assertThat(store.rows.values()).singleElement().extracting(LeaseRecord::status).isEqualTo(LeaseStatus.RELEASED);
    }

    @Test
    void urgentTextInterruptsARunningBackgroundImageAndThenGetsItsTurn() throws Exception {
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.PREPARATION, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);

        claimText(WorkClass.INTERACTIVE);

        assertFallback(handle, ImageAdmission.INTERRUPTED);
        assertThat(port.handles.getFirst().cancels).hasValue(1);
        await(() -> metric("jobs_running") == 0);
        assertThat(events).containsSubsequence("cancel", "image worker unload " + CHECKPOINT);
        driver.tickNow();
        assertThat(listener.heard).containsExactly("ready");
        assertThat(textServer.holds(TEXT)).isTrue();
        assertThat(imageServer.holds(CHECKPOINT)).isFalse();
    }

    @Test
    void anImageThatFinishedBeforeTheCancelReachedItKeepsItsAnswer() throws Exception {
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.PREPARATION, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);
        port.handles.getFirst().lateAnswer = picture();

        claimText(WorkClass.INTERACTIVE);

        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertThat(port.handles.getFirst().cancels).hasValue(1);
    }

    @Test
    void aBackendThatDoesNotAnswerACancelDelaysTheUnloadOnlyForTheBoundedWait() throws Exception {
        images = new LeasedImageAdmission(executor, coordinator, LARGE, clock, Duration.ofMillis(2), Duration.ofMillis(20),
                CLOSE_WAIT);
        driver = new TextResidencyDriver(executor, coordinator, slot -> Optional.of(TEXT), LARGE, clock, Duration.ofHours(1),
                images);
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.PREPARATION, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);
        port.handles.getFirst().cancellable = false;

        claimText(WorkClass.INTERACTIVE);

        assertFallback(handle, ImageAdmission.INTERRUPTED);
        await(() -> metric("jobs_running") == 0);
        assertThat(imageServer.holds(CHECKPOINT)).isFalse();
    }

    // ---------------------------------------------------------------- outcomes and the catalog-art fallback

    @Test
    void everyStatusHasAFixedCode() {
        Set<String> distinct = Stream.of(LeaseExecutor.Status.values())
                .filter(status -> !EnumSet.of(LeaseExecutor.Status.COMPLETED, LeaseExecutor.Status.READY).contains(status))
                .map(LeasedImageAdmission::code).collect(Collectors.toSet());
        assertThat(distinct).hasSize(LeaseExecutor.Status.values().length - 2)
                .allSatisfy(code -> assertThat(code).matches("image-admission-[a-z-]+"));
        assertThat(LeasedImageAdmission.code(LeaseExecutor.Status.COMPLETED)).isEqualTo(ImageAdmission.LOST);
        assertThat(LeasedImageAdmission.code(LeaseExecutor.Status.READY)).isEqualTo(ImageAdmission.LOST);
    }

    @Test
    void deferredForExpectedTextGivesTheFallbackAtOnceAndWithdrawsTheRequest() throws Exception {
        expected = true;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, AT_ONCE);
        driver.tickNow();

        assertFallback(handle, ImageAdmission.DEFERRED);
        assertNothingLeftBehind();
        assertThat(events).doesNotContain("image worker load " + CHECKPOINT);
        assertNeverRunsLater(0);
    }

    @Test
    void delayedByLowMemoryGivesTheFallbackAtOnceAndWithdrawsTheRequest() throws Exception {
        free = 0;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, AT_ONCE);
        driver.tickNow();

        assertFallback(handle, ImageAdmission.DELAYED);
        assertNothingLeftBehind();
        assertNeverRunsLater(0);
    }

    @Test
    void aTextModelThatWillNotLeaveGivesTheFallbackAtOnce() throws Exception {
        driver.tickNow();
        claimText(WorkClass.INTERACTIVE).release(false);
        driver.awaitIdle();
        textServer.unloadFailures = 10;

        ImageHandle handle = submit(WorkClass.PREPARATION, AT_ONCE);
        driver.tickNow();

        assertFallback(handle, ImageAdmission.SWAP_FAILED);
        await(() -> metric("leases_active") == 0 && metric("requests_delayed") == 0);
        assertThat(port.handles).isEmpty();
        assertThat(textServer.holds(TEXT)).isTrue();
    }

    @Test
    void contentionPastThePatienceFallsBackWithoutResubmitting() throws Exception {
        free = 0;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        await(() -> metric("requests_delayed") == 1);
        assertThat(handle.result().toCompletableFuture()).isNotDone();

        clock.advance(PATIENCE.plus(RECHECK));
        driver.tickNow();

        assertFallback(handle, ImageAdmission.CONTENTION);
        assertNothingLeftBehind();
        assertNeverRunsLater(0);
    }

    @Test
    void aRequestPutBackWithinItsWaitRunsWhenTheExecutorAsksAgain() throws Exception {
        free = 0;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        await(() -> metric("requests_delayed") == 1);

        free = 100;
        clock.advance(RECHECK);
        driver.tickNow();

        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertThat(port.requests).hasSize(1);
        assertNothingLeftBehind();
    }

    @Test
    void aWaitThatEndsWhilePutBackGivesTheLastReasonAndWithdrawsTheRequest() throws Exception {
        free = 0;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, Duration.ofSeconds(5));
        driver.tickNow();
        await(() -> metric("requests_delayed") == 1);

        clock.advance(Duration.ofSeconds(5));
        // The first batch may still be settling, and a sweep skips what a batch holds.
        do {
            driver.tickNow();
        } while (!handle.result().toCompletableFuture().isDone());

        assertFallback(handle, ImageAdmission.DELAYED);
        assertNothingLeftBehind();
        assertNeverRunsLater(0);
    }

    @Test
    void aWaitThatEndsWhileQueuedBehindALongTextLeaseIsEndedByTheSweep() throws Exception {
        driver.tickNow();
        claimText(WorkClass.INTERACTIVE);
        assertThat(listener.heard).containsExactly("ready");

        ImageHandle handle = submit(WorkClass.INTERACTIVE, AT_ONCE);
        driver.tickNow();

        assertFallback(handle, ImageAdmission.DEFERRED);
        assertThat(metric("requests_waiting")).isZero();
        assertThat(port.handles).isEmpty();
    }

    @Test
    void aModelThatWillNotLoadGivesTheFallback() throws Exception {
        imageServer.loadFailures = 1;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();

        assertFallback(handle, ImageAdmission.LOAD_FAILED);
        assertNothingLeftBehind();
        assertThat(port.handles).isEmpty();
    }

    @Test
    void theTimeCapCancelsTheBackendAndGivesTheFallback() throws Exception {
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);

        clock.advance(TIME_CAP);

        assertFallback(handle, ImageAdmission.TIME_CAPPED);
        assertThat(port.handles.getFirst().cancels).hasValue(1);
        awaitNoLease();
        assertThat(imageServer.holds(CHECKPOINT)).isFalse();
    }

    @Test
    void aLeaseLostWhileRunningCancelsTheBackendAndGivesTheFallback() throws Exception {
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);

        coordinator.revoke(coordinator.leases().getFirst().id());
        clock.advance(TTL.dividedBy(2).plusSeconds(1));

        assertFallback(handle, ImageAdmission.LOST);
        assertThat(port.handles.getFirst().cancels).hasValue(1);
        await(() -> metric("jobs_running") == 0);
        assertThat(imageServer.holds(CHECKPOINT)).isFalse();
    }

    @Test
    void anAdapterThatThrowsEndsTheBatchWithTheFallbackAndUnloadsTheModel() throws Exception {
        port.breaks = true;
        ImageHandle first = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        ImageHandle second = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();

        assertFallback(first, ImageAdmission.JOB_FAILED);
        assertFallback(second, ImageAdmission.JOB_FAILED);
        assertNothingLeftBehind();
    }

    @Test
    void lowMemoryWhileRunningYieldsAndGivesTheFallback() throws Exception {
        build(new LeaseExecutorSettings(0, 0, 20, 3, Duration.ofSeconds(1), RECHECK, PATIENCE, TIME_CAP), LARGE);
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);

        free = 5;

        assertFallback(handle, ImageAdmission.YIELDED);
        assertThat(port.handles.getFirst().cancels).hasValue(1);
        assertNothingLeftBehind();
    }

    @Test
    void aRequestLargerThanTheBudgetIsRefusedAtOnce() throws Exception {
        build(SETTINGS, new Demand(1000, 1));

        assertFallback(submit(WorkClass.INTERACTIVE, LONG_WAIT), ImageAdmission.UNAVAILABLE);
        assertThat(metric("requests_waiting")).isZero();
    }

    @Test
    void closingEndsWaitingAndRunningJobsAndRefusesNewOnes() throws Exception {
        port.answerAtOnce = false;
        ImageHandle running = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);
        free = 0;
        var other = images.submit(port, request("image-b"), new ImageAdmission.Ticket(WorkClass.INTERACTIVE, A, LONG_WAIT));

        driver.stop();
        images.close();

        assertFallback(running, "image-shutdown");
        assertThat(port.handles.getFirst().cancels).hasValue(1);
        assertFallback(other, "image-shutdown");
        assertThat(metric("requests_waiting")).isZero();
        assertFallback(submit(WorkClass.INTERACTIVE, LONG_WAIT), "image-shutdown");
    }

    @Test
    void closingWaitsForTheBackendAndConfirmsTheUnloadOverAControlThatHonoursInterruption() throws Exception {
        buildWithInterruptibleControl(CLOSE_WAIT);
        port.answerAtOnce = false;
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);
        RecordingHandle running = port.handles.getFirst();
        running.cancellable = false;
        driver.stop();

        CompletableFuture<Void> closing = CompletableFuture.runAsync(images::close);
        await(() -> running.cancels.get() == 1);
        Thread.sleep(50);
        assertThat(closing).as("the backend has not answered yet").isNotDone();
        assertThat(events).doesNotContain("image worker unload " + CHECKPOINT);
        running.answer.complete(ImageResult.cancelled());
        closing.get(10, TimeUnit.SECONDS);

        assertFallback(handle, "image-shutdown");
        assertThat(running.cancels).hasValue(1);
        assertThat(events).containsSubsequence("cancel", "image worker unload " + CHECKPOINT);
        assertNothingLeftBehind();
        assertThat(store.rows.values()).singleElement().extracting(LeaseRecord::status).isEqualTo(LeaseStatus.RELEASED);
    }

    @Test
    void aBatchThatOutlastsTheCloseWaitHoldsUpNoJobAndStillUnloadsItsModelAfterwards() throws Exception {
        buildWithInterruptibleControl(SHORT_CLOSE_WAIT);
        port.answerAtOnce = false;
        ImageHandle running = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        awaitRunning(1);
        var queued = images.submit(port, request("image-b"), new ImageAdmission.Ticket(WorkClass.INTERACTIVE, A, LONG_WAIT));
        interruptible.holdUnloads();
        driver.stop();

        // The unload holds the executor's monitor, so a close that waited for it would time out here.
        CompletableFuture.runAsync(images::close).get(10, TimeUnit.SECONDS);

        assertFallback(running, "image-shutdown");
        assertFallback(queued, "image-shutdown");
        await(interruptible::reachedUnload);
        interruptible.open();
        await(() -> metric("jobs_running") == 0);
        assertThat(port.handles).hasSize(1);
        assertThat(imageServer.holds(CHECKPOINT)).isFalse();
        assertThat(metric("leases_reconciling")).isZero();
    }

    @Test
    void aBatchStillLoadingWhenThePathClosesNeverReachesItsAdapterAndUnloadsItsModel() throws Exception {
        buildWithInterruptibleControl(SHORT_CLOSE_WAIT);
        interruptible.holdLoads();
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        await(interruptible::reachedLoad);
        driver.stop();

        CompletableFuture.runAsync(images::close).get(10, TimeUnit.SECONDS);

        assertFallback(handle, "image-shutdown");
        interruptible.open();
        await(() -> interruptible.reachedUnload() && metric("jobs_running") == 0);
        assertThat(port.handles).isEmpty();
        assertThat(imageServer.holds(CHECKPOINT)).isFalse();
        assertThat(metric("leases_reconciling")).isZero();
        assertThat(metric("leases_active")).isZero();
    }

    private static long threadsNamed(String name) {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(thread -> thread.getName().equals(name)).count();
    }

    @Test
    void cancelsDuringAServerCallWaitOnOneThreadAndAddNoBatchThreads() throws Exception {
        buildWithInterruptibleControl(CLOSE_WAIT);
        interruptible.holdUnloads();
        ImageHandle first = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        driver.tickNow();
        assertThat(resultOf(first).status()).isEqualTo(ImageResult.Status.COMPLETED);
        await(interruptible::reachedUnload);

        List<ImageHandle> queued = Stream.generate(() -> submit(WorkClass.INTERACTIVE, LONG_WAIT)).limit(8).toList();
        queued.forEach(handle -> assertThat(handle.cancel()).isTrue());

        await(() -> threadsNamed(BATCH_THREAD) == 1);
        Thread.sleep(50);
        assertThat(threadsNamed(BATCH_THREAD)).as("only the batch in its unload").isEqualTo(1);
        assertThat(threadsNamed(BATCH_THREAD + "-withdrawal")).isEqualTo(1);

        interruptible.open();
        await(() -> metric("requests_waiting") == 0);
        assertThat(port.handles).hasSize(1);
        assertNothingLeftBehind();
    }

    @Test
    void aGrantThatArrivesAfterClosingIsGivenBackWithoutLoading() throws Exception {
        ImageHandle handle = submit(WorkClass.INTERACTIVE, LONG_WAIT);
        Grant grant = executor.pass().grants().getFirst();
        driver.stop();
        images.close();

        images.accept(grant);

        assertFallback(handle, "image-shutdown");
        assertThat(metric("leases_active")).isZero();
        assertThat(events).isEmpty();
    }

    // ---------------------------------------------------------------- request details

    @Test
    void theRequestCarriesTheTicketsClassOwnerAndDemand() {
        submit(WorkClass.PREPARATION, LONG_WAIT);

        assertThat(coordinator.waiting()).singleElement().satisfies(waiting -> {
            assertThat(waiting.modality()).isEqualTo(Modality.IMAGE);
            assertThat(waiting.workClass()).isEqualTo(WorkClass.PREPARATION);
            assertThat(waiting.owner()).isEqualTo(A);
            assertThat(waiting.model()).isEqualTo(CHECKPOINT);
            assertThat(waiting.demand()).isEqualTo(LARGE);
            assertThat(waiting.enqueuedAt()).isEqualTo(T0);
        });
    }

    @Test
    void aCheckpointNameALeaseCannotHoldBecomesADigestAndNoNameHasItsOwnLease() {
        assertThat(LeasedImageAdmission.leaseModel(request(null))).isEqualTo(LeasedImageAdmission.CURRENT_CHECKPOINT);
        assertThat(LeasedImageAdmission.leaseModel(request("wai-v17.safetensors"))).isEqualTo("wai-v17.safetensors");
        String spaced = LeasedImageAdmission.leaseModel(request("wai illustrious [v17].safetensors"));
        assertThat(spaced).matches("image-[0-9a-f]{32}").doesNotContain("illustrious");
        assertThat(AdmissionRequest.validModel(spaced)).isTrue();
        assertThat(LeasedImageAdmission.leaseModel(request("wai illustrious [v18].safetensors"))).isNotEqualTo(spaced);
    }

    @Test
    void aNegativeWaitIsRefused() {
        assertThatThrownBy(() -> new ImageAdmission.Ticket(WorkClass.INTERACTIVE, A, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- the switch

    private ApplicationContextRunner application() {
        return new ApplicationContextRunner()
                .withUserConfiguration(AdmissionConfiguration.class, TextResidencyConfiguration.class)
                .withBean(LeaseStore.class, MemoryLeaseStore::new)
                .withBean(ModelServerControl.class, () -> mock(ModelServerControl.class))
                .withBean(NarrationTaskPort.class, () -> mock(NarrationTaskPort.class))
                .withBean(ProfileResolver.class, () -> new ProfileResolver(slot -> Optional.empty(), ModelAllowlist.empty(),
                        Optional.empty()));
    }

    @Test
    void withTheSwitchOffNoBeanExistsAndTheAdapterIsCalledDirectly() throws Exception {
        application().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ImageAdmission.class).doesNotHaveBean(TextResidencyDriver.class)
                    .doesNotHaveBean(LeaseExecutor.class);
            ImageAdmission selected = context.getBeanProvider(ImageAdmission.class).getIfAvailable(ImageAdmission::direct);

            ImageHandle handle = selected.submit(port, request(CHECKPOINT),
                    new ImageAdmission.Ticket(WorkClass.INTERACTIVE, A, AT_ONCE));

            assertThat(handle).isSameAs(port.handles.getFirst());
            assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.COMPLETED);
            assertThat(port.threads).containsExactly(Thread.currentThread());
        });
        assertThat(events).containsExactly("submit");
    }

    @Test
    void withTheSwitchOnTheRealWiringRoutesTheGrantAndNoImageControlMeansTheFallback() {
        application().withPropertyValues("engine.inference.admission.enabled=true",
                "engine.inference.admission.gpu-budget-mib=10240", "engine.inference.admission.image-gpu-demand-mib=4096")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ImageAdmission.class);
                    assertThat(context.getBean(AdmissionOptions.class).imageDemand().gpuBytes()).isEqualTo(4096L * 1024 * 1024);
                    ImageAdmission selected = context.getBeanProvider(ImageAdmission.class)
                            .getIfAvailable(ImageAdmission::direct);

                    ImageHandle handle = selected.submit(port, request(CHECKPOINT),
                            new ImageAdmission.Ticket(WorkClass.INTERACTIVE, A, LONG_WAIT));

                    // A grant dropped by the driver would leave this waiting; only the wired tick can end it.
                    assertThat(resultOf(handle).errorCode()).isEqualTo(ImageAdmission.LOAD_FAILED);
                    assertThat(port.handles).isEmpty();
                    assertThat(context.getBean(LeaseExecutor.class).metrics()).containsEntry("leases_active", 0L);
                });
    }

    @Test
    void anImageDemandOutsideTheBudgetFailsStartupWithoutEchoingIt() {
        application().withPropertyValues("engine.inference.admission.enabled=true",
                "engine.inference.admission.gpu-budget-mib=10240", "engine.inference.admission.image-gpu-demand-mib=7654321")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("engine.inference.admission.image-gpu-demand-mib must be between")
                        .satisfies(failure -> assertThat(failure.toString()).doesNotContain("7654321")));
    }
}
