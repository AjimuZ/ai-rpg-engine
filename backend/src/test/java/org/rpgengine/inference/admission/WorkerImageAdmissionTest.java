package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.rpgengine.inference.admission.AdmissionFixtures.A;
import static org.rpgengine.inference.admission.AdmissionFixtures.AGING;
import static org.rpgengine.inference.admission.AdmissionFixtures.CAPACITY;
import static org.rpgengine.inference.admission.AdmissionFixtures.LARGE;
import static org.rpgengine.inference.admission.AdmissionFixtures.SMALL;
import static org.rpgengine.inference.admission.AdmissionFixtures.T0;
import static org.rpgengine.inference.admission.AdmissionFixtures.TTL;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageModelControl;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import org.rpgengine.inference.image.ScriptedImageWorker;
import org.rpgengine.inference.scheduling.WorkClass;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;

/**
 * An image batch from {@link ImageAdmission} to the packaged worker's routes and back: the real driver, executor,
 * coordinator, worker control and worker port over HTTP, with a double of the worker written from image_worker.py, a
 * scripted free-memory reading, a scripted text server and an in-memory lease store. No GPU, real worker or database
 * takes part. The control and the port are the beans the application would build; this package cannot name their types.
 * The tick is an hour, so passes happen only when a test asks for one.
 */
class WorkerImageAdmissionTest {
    private static final LeaseExecutorSettings SETTINGS = new LeaseExecutorSettings(0, 0, 0, 3, Duration.ofMillis(1),
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(5));

    /** Not a stereotype, so no other test's package scan picks it up. */
    @ComponentScan("org.rpgengine.inference.image")
    static class WorkerBeans {
    }

    private final SteppedClock clock = new SteppedClock(T0);
    private final MemoryLeaseStore store = new MemoryLeaseStore();
    private final AdmissionCoordinator coordinator = new AdmissionCoordinator(
            new AdmissionSettings(CAPACITY, TTL, AGING, Map.of(Modality.TEXT, 1, Modality.IMAGE, 4, Modality.SPEECH, 4)),
            store, clock, AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
    private final ScriptedWorker textServer = new ScriptedWorker(event -> { });
    private ScriptedImageWorker worker;
    private AnnotationConfigApplicationContext beans;
    private ImageGenerationPort port;
    private LeaseExecutor executor;
    private LeasedImageAdmission images;
    private TextResidencyDriver driver;

    @BeforeEach
    void build() throws IOException {
        worker = new ScriptedImageWorker();
        beans = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("engine.inference.image-worker.url=" + worker.url(), "engine.inference.admission.enabled=true")
                .applyTo(beans);
        beans.register(WorkerBeans.class);
        beans.refresh();
        port = beans.getBean(ImageGenerationPort.class);
        executor = new LeaseExecutor(coordinator,
                Map.of(Modality.TEXT, textServer, Modality.IMAGE, beans.getBean(ImageModelControl.class).control()),
                () -> OptionalLong.of(100), () -> false, SETTINGS, clock, clock::advance);
        imagesTakingUp(LARGE);
    }

    private void imagesTakingUp(Demand demand) {
        images = new LeasedImageAdmission(executor, coordinator, demand, clock, Duration.ofMillis(2), Duration.ofSeconds(5),
                Duration.ofSeconds(20));
        driver = new TextResidencyDriver(executor, coordinator, slot -> Optional.of("text-a"), SMALL, clock,
                Duration.ofHours(1), images);
    }

    @AfterEach
    void stop() {
        worker.release();
        driver.stop();
        images.close();
        beans.close();
        worker.close();
    }

    private ImageHandle submit(String model) {
        return images.submit(port, new ImageRequest("a castle", "", model, 512, 512, 4, 6.0, 7, null),
                new ImageAdmission.Ticket(WorkClass.INTERACTIVE, A, Duration.ofHours(1)));
    }

    private static ImageResult resultOf(ImageHandle handle) throws Exception {
        return handle.result().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached");
            }
            Thread.sleep(2);
        }
    }

    private void awaitNoLease() throws InterruptedException {
        await(() -> executor.metrics().get("leases_active") == 0 && executor.metrics().get("jobs_running") == 0);
    }

    @Test
    void aBatchLoadsTheCheckpointGeneratesAndUnloadsThroughTheWorkersRoutes() throws Exception {
        ImageHandle handle = submit(ScriptedImageWorker.BASE_ID);
        assertThat(worker.jobCalls()).as("nothing reaches the worker before a lease").isEmpty();

        driver.tickNow();

        ImageResult result = resultOf(handle);
        assertThat(result.status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertThat(result.provenance().backend()).isEqualTo("local-image");
        assertThat(result.provenance().model()).isEqualTo(ScriptedImageWorker.BASE_ID);
        awaitNoLease();
        assertThat(worker.jobCalls()).containsExactly("POST /v1/load", "POST /v1/generate", "POST /v1/unload");
        assertThat(worker.loaded()).as("the model left memory").isNull();
        assertThat(executor.metrics()).containsEntry("leases_reconciling", 0L);
        assertThat(store.rows.values()).singleElement().satisfies(row -> {
            assertThat(row.modality()).isEqualTo(Modality.IMAGE);
            assertThat(row.model()).isEqualTo(ScriptedImageWorker.BASE_ID);
            assertThat(row.status()).isEqualTo(LeaseStatus.RELEASED);
        });
    }

    @Test
    void aRequestWithoutACheckpointRunsOnTheWorkersRecommendedOne() throws Exception {
        ImageHandle handle = submit(null);
        driver.tickNow();

        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.COMPLETED);
        awaitNoLease();
        assertThat(worker.seen()).filteredOn(call -> call.path().equals("/v1/load")).singleElement()
                .satisfies(call -> assertThat(call.body()).isEqualTo("{}"));
        assertThat(worker.loaded()).isNull();
    }

    @Test
    void aCancelReachesTheWorkerBeforeTheCheckpointIsUnloaded() throws Exception {
        worker.holdJobs();
        ImageHandle handle = submit(ScriptedImageWorker.BASE_ID);
        driver.tickNow();
        assertThat(worker.awaitRunningJob()).isTrue();

        assertThat(handle.cancel()).isTrue();

        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.CANCELLED);
        awaitNoLease();
        assertThat(worker.jobCalls()).containsExactly("POST /v1/load", "POST /v1/generate", "POST /v1/cancel",
                "POST /v1/unload");
        assertThat(worker.loaded()).isNull();
        assertThat(executor.metrics()).containsEntry("leases_reconciling", 0L);
    }

    @Test
    void aStoppedBatchCancelsOnTheWorkerAndThenConfirmsTheUnload() throws Exception {
        worker.holdJobs();
        ImageHandle handle = submit(ScriptedImageWorker.BASE_ID);
        driver.tickNow();
        assertThat(worker.awaitRunningJob()).isTrue();

        images.close();

        assertThat(resultOf(handle).errorCode()).isEqualTo("image-shutdown");
        assertThat(worker.jobCalls()).containsExactly("POST /v1/load", "POST /v1/generate", "POST /v1/cancel",
                "POST /v1/unload");
        assertThat(worker.loaded()).isNull();
        assertThat(executor.metrics()).containsEntry("leases_reconciling", 0L);
    }

    @Test
    void aCheckpointTheWorkerCannotLoadEndsInTheFallbackWithNothingHeld() throws Exception {
        ImageHandle handle = submit("missing_file.safetensors");
        driver.tickNow();

        ImageResult result = resultOf(handle);
        assertThat(result.errorCode()).isEqualTo(ImageAdmission.LOAD_FAILED);
        assertThat(ImageAdmission.usesCatalogArt(result)).isTrue();
        awaitNoLease();
        assertThat(executor.metrics()).containsEntry("leases_reconciling", 0L);
        assertThat(worker.count("/v1/generate")).isZero();
    }

    @Test
    void aServerThatConfirmsNothingNeverFreesTheCapacity() throws Exception {
        worker.replyNext("/v1/models", 200, "{}");
        worker.replyNext("/v1/models", 200, "{}");
        ImageHandle handle = submit(ScriptedImageWorker.BASE_ID);
        driver.tickNow();

        assertThat(resultOf(handle).errorCode()).isEqualTo(ImageAdmission.LOAD_FAILED);

        await(() -> executor.metrics().get("leases_reconciling") == 1L);
        assertThat(executor.metrics().get("capacity_held_gpu_bytes")).isEqualTo(LARGE.gpuBytes());
        assertThat(worker.count("/v1/generate")).isZero();
    }

    @Test
    void aSecondCheckpointDoesNotReplaceTheOneARunningBatchUses() throws Exception {
        // An owner's image demand small enough that two image leases fit the budget together.
        driver.stop();
        images.close();
        imagesTakingUp(SMALL);
        worker.holdJobs();
        ImageHandle first = submit(ScriptedImageWorker.BASE_ID);
        driver.tickNow();
        assertThat(worker.awaitRunningJob()).isTrue();

        ImageHandle second = submit(ScriptedImageWorker.OWNER_FILE);
        driver.tickNow();

        assertThat(resultOf(second).errorCode()).isEqualTo(ImageAdmission.LOAD_FAILED);
        assertThat(worker.count("/v1/load")).as("the second never reached the worker").isEqualTo(1);
        assertThat(worker.loaded()).isEqualTo(ScriptedImageWorker.BASE_FILE);
        worker.release();
        assertThat(resultOf(first).status()).isEqualTo(ImageResult.Status.COMPLETED);
        awaitNoLease();
        assertThat(worker.loaded()).isNull();
    }
}
