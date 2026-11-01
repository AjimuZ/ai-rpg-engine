package org.rpgengine.inference.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ImageModelControl;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ModelControlException;
import org.rpgengine.inference.ModelServerControl;
import org.rpgengine.inference.ModelServerControl.Residency;

/**
 * The control over the worker's load, unload and model list routes, against a double written from image_worker.py, which
 * shows nothing about the real worker on a GPU. The residency it reports must come from the worker's own answer, and it
 * must never report a checkpoint gone while the worker may hold one.
 */
class WorkerModelControlTest {
    private static final String SERVER_TEXT = "private-server-text";
    private ScriptedImageWorker worker;
    private WorkerModelControl control;

    @BeforeEach
    void start() throws IOException {
        worker = new ScriptedImageWorker();
        Duration bound = Duration.ofSeconds(10);
        control = new WorkerModelControl(new WorkerSettings(worker.url(), bound, bound, bound, 8));
    }

    @AfterEach
    void stop() {
        worker.close();
    }

    /** Past the state in which an empty answer proves nothing. */
    private void verified() throws ModelControlException {
        control.unload("any");
    }

    private void assertCode(ThrowingCall call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ModelControlException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getMessage()).isEqualTo(code).doesNotContain(SERVER_TEXT, worker.url());
        });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    @Test
    void aCatalogIdLoadsAndUnloadsThroughTheDocumentedRoutesAndIsConfirmedFromTheWorkersAnswer() throws Exception {
        verified();
        assertThat(control.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.GONE);

        control.load(ScriptedImageWorker.BASE_ID);
        assertThat(worker.loaded()).isEqualTo(ScriptedImageWorker.BASE_FILE);
        assertThat(control.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.LOADED);
        assertThat(control.resident()).containsExactly(ScriptedImageWorker.BASE_FILE);

        control.unload(ScriptedImageWorker.BASE_ID);
        assertThat(worker.loaded()).isNull();
        assertThat(control.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.GONE);
        assertThat(control.resident()).isEmpty();

        assertThat(worker.jobCalls()).containsExactly("POST /v1/unload", "POST /v1/load", "POST /v1/unload");
        assertThat(worker.seen()).filteredOn(call -> call.path().equals("/v1/load")).singleElement()
                .satisfies(call -> assertThat(call.body()).isEqualTo("{\"checkpoint\":\"sdxl-base-1.0\"}"));
        assertThat(worker.seen()).filteredOn(call -> call.path().equals("/v1/unload"))
                .allSatisfy(call -> assertThat(call.body()).isEqualTo("{}"));
        assertThat(worker.seen()).filteredOn(call -> call.path().equals("/v1/models"))
                .allSatisfy(call -> assertThat(call.method()).isEqualTo("GET"));
    }

    @Test
    void aFileNameLoadsAsItself() throws Exception {
        control.load(ScriptedImageWorker.OWNER_FILE);

        assertThat(control.status(ScriptedImageWorker.OWNER_FILE)).isEqualTo(Residency.LOADED);
    }

    @Test
    void theCurrentCheckpointOmitsTheFieldAndTakesTheWorkersRecommendedOne() throws Exception {
        control.load(ImageModelControl.CURRENT_CHECKPOINT);

        assertThat(worker.seen()).filteredOn(call -> call.path().equals("/v1/load")).singleElement()
                .satisfies(call -> assertThat(call.body()).isEqualTo("{}"));
        assertThat(worker.loaded()).isEqualTo(ScriptedImageWorker.RECOMMENDED_FILE);
        assertThat(control.status(ImageModelControl.CURRENT_CHECKPOINT)).isEqualTo(Residency.LOADED);
    }

    @Test
    void aFreshControlDoesNotTakeAnEmptyAnswerForGoneUntilAnUnloadConfirmsIt() throws Exception {
        // After a restart the worker may still be inside an earlier process's load, when "loaded" is null too.
        assertThat(control.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.OTHER);

        control.unload(ScriptedImageWorker.BASE_ID);

        assertThat(control.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.GONE);
    }

    @Test
    void aFreshControlFollowedByALoadReachesLoaded() throws Exception {
        assertThat(control.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.OTHER);

        control.load(ScriptedImageWorker.BASE_ID);

        assertThat(control.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.LOADED);
    }

    @Test
    void whatAnEarlierProcessLeftResidentIsNeverGone() throws Exception {
        worker.alreadyHolds(ScriptedImageWorker.BASE_FILE);

        assertThat(control.status(ScriptedImageWorker.BASE_ID)).as("the file may be this catalog ID")
                .isEqualTo(Residency.OTHER);
        assertThat(control.status(ScriptedImageWorker.RECOMMENDED_FILE)).isEqualTo(Residency.OTHER);
        assertThat(control.status(ScriptedImageWorker.BASE_FILE)).isEqualTo(Residency.LOADED);

        control.unload(ScriptedImageWorker.BASE_FILE);

        assertThat(worker.loaded()).isNull();
        assertThat(control.status(ScriptedImageWorker.BASE_FILE)).isEqualTo(Residency.GONE);
    }

    @Test
    void theCurrentCheckpointMeansWhateverIsResident() throws Exception {
        worker.alreadyHolds(ScriptedImageWorker.BASE_FILE);

        assertThat(control.status(ImageModelControl.CURRENT_CHECKPOINT)).isEqualTo(Residency.LOADED);
        assertThat(worker.jobCalls()).isEmpty();
    }

    @Test
    void aSecondCheckpointIsRefusedWithoutTouchingTheOneInUse() throws Exception {
        control.load(ScriptedImageWorker.BASE_ID);

        assertThat(control.status(ScriptedImageWorker.OWNER_FILE)).as("true of that model, so nothing unloads for it")
                .isEqualTo(Residency.GONE);
        assertCode(() -> control.load(ScriptedImageWorker.OWNER_FILE), "control-busy");

        assertThat(worker.count("/v1/load")).isEqualTo(1);
        assertThat(worker.loaded()).isEqualTo(ScriptedImageWorker.BASE_FILE);

        control.unload(ScriptedImageWorker.BASE_ID);
        control.load(ScriptedImageWorker.OWNER_FILE);
        assertThat(worker.loaded()).isEqualTo(ScriptedImageWorker.OWNER_FILE);
    }

    @Test
    void aCheckpointThatChangedOnTheWorkerIsNoLongerLoaded() throws Exception {
        control.load(ScriptedImageWorker.BASE_ID);
        worker.alreadyHolds(ScriptedImageWorker.OWNER_FILE);

        assertThat(control.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.OTHER);
    }

    @Test
    void aWorkerThatRestartedEmptyReleasesTheHolder() throws Exception {
        control.load(ScriptedImageWorker.BASE_ID);
        worker.alreadyHolds(null);

        assertThat(control.status(ScriptedImageWorker.OWNER_FILE)).isEqualTo(Residency.GONE);
        control.load(ScriptedImageWorker.OWNER_FILE);
        assertThat(worker.loaded()).isEqualTo(ScriptedImageWorker.OWNER_FILE);
    }

    @Test
    void aBusyWorkerRefusesLoadAndUnloadAndReportsNothingGone() throws Exception {
        worker.alreadyHolds(ScriptedImageWorker.BASE_FILE);
        worker.holdJobs();
        WorkerImageAdapter adapter = new WorkerImageAdapter(new WorkerSettings(worker.url(), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(10), 8));
        try {
            adapter.submit(new ImageRequest("a lantern", "", null, 512, 512, 1, 6, 1, null));
            assertThat(worker.awaitRunningJob()).isTrue();

            assertCode(() -> control.load(ScriptedImageWorker.OWNER_FILE), "control-busy");
            assertCode(() -> control.unload(ScriptedImageWorker.BASE_ID), "control-busy");
            assertThat(control.status(ScriptedImageWorker.BASE_FILE)).isEqualTo(Residency.LOADED);
        } finally {
            worker.release();
            adapter.close();
        }
    }

    @Test
    void aRefusedLoadCarriesAFixedCodeAndADefiniteAnswerProvesNothingResident() throws Exception {
        assertCode(() -> control.load("missing_file.safetensors"), "control-status");
        assertCode(() -> control.load("image-0123456789abcdef"), "control-status");
        assertThat(control.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.GONE);
    }

    @Test
    void aFailedLoadOnTheWorkerLeavesTheCapacityToAnAnswerNotAGuess() throws Exception {
        worker.refuseNext("/v1/load", 507, "gpu-out-of-memory");
        assertCode(() -> control.load(ScriptedImageWorker.BASE_ID), "control-status");
        assertThat(control.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.GONE);
    }

    @Test
    void aBusyAnswerToALoadLeavesAnEmptyAnswerUnproven() throws Exception {
        verified();
        worker.refuseNext("/v1/load", 409, "busy");

        assertCode(() -> control.load(ScriptedImageWorker.BASE_ID), "control-busy");

        assertThat(control.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.OTHER);
    }

    @Test
    void aLoadNobodySawTheEndOfKeepsItsLeaseHeldUntilAnUnloadIsAnswered() throws Exception {
        worker.holdLoads();
        WorkerModelControl impatient = new WorkerModelControl(new WorkerSettings(worker.url(), Duration.ofMillis(100),
                Duration.ofSeconds(10), Duration.ofSeconds(10), 8));

        assertCode(() -> impatient.load(ScriptedImageWorker.BASE_ID), "control-timeout");
        assertThat(worker.loaded()).as("the double's load is still running").isNull();
        assertThat(impatient.status(ScriptedImageWorker.BASE_ID)).as("an empty answer proves nothing now")
                .isEqualTo(Residency.OTHER);
        assertCode(() -> impatient.unload(ScriptedImageWorker.BASE_ID), "control-busy");
        assertThat(impatient.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.OTHER);

        worker.release();
        assertThat(worker.awaitFreeSlot()).isTrue();
        assertThat(worker.loaded()).isEqualTo(ScriptedImageWorker.BASE_FILE);
        assertThat(impatient.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.OTHER);
        impatient.unload(ScriptedImageWorker.BASE_ID);
        assertThat(impatient.status(ScriptedImageWorker.BASE_ID)).isEqualTo(Residency.GONE);
    }

    @Test
    void anAnswerThatDoesNotConfirmTheCallIsMalformedEvenWithASuccessStatus() throws Exception {
        worker.replyNext("/v1/load", 200, "{\"loaded\":\"other.safetensors\",\"catalogId\":null}");
        assertCode(() -> control.load(ScriptedImageWorker.BASE_ID), "control-malformed");
        worker.replyNext("/v1/load", 200, "{\"loaded\":null}");
        assertCode(() -> control.load(ScriptedImageWorker.BASE_ID), "control-malformed");
        worker.replyNext("/v1/load", 200, "[]");
        assertCode(() -> control.load(ScriptedImageWorker.BASE_ID), "control-malformed");
        worker.replyNext("/v1/unload", 200, "{\"loaded\":\"still.safetensors\"}");
        assertCode(() -> control.unload(ScriptedImageWorker.BASE_ID), "control-malformed");
        worker.replyNext("/v1/unload", 200, "{}");
        assertCode(() -> control.unload(ScriptedImageWorker.BASE_ID), "control-malformed");
        worker.replyNext("/v1/models", 200, "{\"recommended\":\"x\"}");
        assertCode(() -> control.status(ScriptedImageWorker.BASE_ID), "control-malformed");
        worker.replyNext("/v1/models", 200, "{\"loaded\":7}");
        assertCode(() -> control.status(ScriptedImageWorker.BASE_ID), "control-malformed");
        worker.replyNext("/v1/models", 200, "not json " + SERVER_TEXT);
        assertCode(control::resident, "control-malformed");
    }

    @Test
    void otherFailuresCarryFixedCodesAndNoServerText() throws Exception {
        worker.replyNext("/v1/models", 500, "{\"error\":\"" + SERVER_TEXT + "\"}");
        assertCode(() -> control.status(ScriptedImageWorker.BASE_ID), "control-status");
        worker.replyNext("/v1/models", 200, "x".repeat(1024 * 1024 + 1));
        assertCode(() -> control.status(ScriptedImageWorker.BASE_ID), "control-too-large");
        worker.replyNext("/v1/unload", 307, "");
        assertCode(() -> control.unload(ScriptedImageWorker.BASE_ID), "control-status");

        worker.close();
        assertCode(() -> control.status(ScriptedImageWorker.BASE_ID), "control-unreachable");
        assertCode(() -> control.load(ScriptedImageWorker.BASE_ID), "control-unreachable");
        assertCode(() -> control.unload(ScriptedImageWorker.BASE_ID), "control-unreachable");
    }

    @Test
    void anInterruptedThreadFailsTheCallWithAFixedCode() {
        Thread.currentThread().interrupt();
        try {
            assertCode(() -> control.status(ScriptedImageWorker.BASE_ID), "control-interrupted");
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    void theWorkerHasNoSlotRoutes() {
        assertCode(() -> control.saveSlot(ScriptedImageWorker.BASE_ID, 0, "file"), "control-unsupported");
        assertCode(() -> control.restoreSlot(ScriptedImageWorker.BASE_ID, 0, "file"), "control-unsupported");
        assertThat(worker.seen()).isEmpty();
    }

    @Test
    void anA1111ServerAtTheAddressConfirmsNothingSoItsCapacityStaysHeld() throws Exception {
        try (ScriptedForgeServer forge = new ScriptedForgeServer()) {
            ModelServerControl pointed = new WorkerModelControl(new WorkerSettings(forge.url(), Duration.ofSeconds(5),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 8));

            for (ThrowingCall call : new ThrowingCall[] {() -> pointed.load("any"), () -> pointed.unload("any"),
                    () -> pointed.status("any"), pointed::resident}) {
                assertThatThrownBy(call::run).isInstanceOfSatisfying(ModelControlException.class,
                        failure -> assertThat(failure.code()).isEqualTo("control-status"));
            }
        }
    }
}
