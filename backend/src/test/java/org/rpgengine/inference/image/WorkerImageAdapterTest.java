package org.rpgengine.inference.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The port over the worker's generate and cancel routes, against a double written from image_worker.py, which shows
 * nothing about the real worker on a GPU. Submit, wait and cancel keep the port's contract, every failure is a fixed
 * code, and a cancel only ever reaches this adapter's own job.
 */
class WorkerImageAdapterTest {
    private static final String PROMPT = "a private lantern";
    private static final Duration BOUND = Duration.ofSeconds(10);
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<WorkerImageAdapter> adapters = new ArrayList<>();
    private ScriptedImageWorker worker;
    private WorkerImageAdapter adapter;

    @BeforeEach
    void start() throws IOException {
        worker = new ScriptedImageWorker();
        worker.alreadyHolds(ScriptedImageWorker.BASE_FILE);
        adapter = adapter(BOUND, 8);
    }

    @AfterEach
    void stop() {
        worker.close();
        adapters.forEach(WorkerImageAdapter::close);
    }

    private WorkerImageAdapter adapter(Duration generateBound, int queue) {
        WorkerImageAdapter built = new WorkerImageAdapter(new WorkerSettings(worker.url(), BOUND, generateBound, BOUND, queue));
        adapters.add(built);
        return built;
    }

    private static ImageRequest request(String model) {
        return new ImageRequest(PROMPT, "blurry", model, 512, 768, 12, 6.5, 42, null);
    }

    private static ImageResult resultOf(ImageHandle handle) throws Exception {
        return handle.result().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private void assertFails(ImageHandle handle, String code) throws Exception {
        ImageResult result = resultOf(handle);
        assertThat(result.status()).isEqualTo(ImageResult.Status.FAILED);
        assertThat(result.errorCode()).isEqualTo(code);
        assertThat(result.png()).isNull();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String answerWith(String imageField) {
        return "{\"imagePngBase64\":" + imageField + ",\"checkpoint\":\"" + ScriptedImageWorker.BASE_FILE + "\"}";
    }

    @Test
    void submitWaitsForAValidatedPictureWithItsProvenance() throws Exception {
        assertThat(adapter).isInstanceOf(ImageGenerationPort.class);
        assertThat(adapter.backend()).isEqualTo("local-image");

        ImageResult result = resultOf(adapter.submit(request(ScriptedImageWorker.BASE_ID)));

        assertThat(result.status()).isEqualTo(ImageResult.Status.COMPLETED);
        byte[] png = result.png();
        assertThat(new String(png, StandardCharsets.ISO_8859_1)).as("metadata chunks are removed")
                .doesNotContain("private prompt", "tEXt");
        var provenance = result.provenance();
        assertThat(provenance.backend()).isEqualTo("local-image");
        assertThat(provenance.model()).isEqualTo(ScriptedImageWorker.BASE_ID);
        assertThat(provenance.serverModelHash()).isEqualTo(ScriptedImageWorker.CHECKPOINT_SHA256.toLowerCase(Locale.ROOT));
        assertThat(provenance.seed()).isEqualTo(42);
        assertThat(provenance.steps()).isEqualTo(12);
        assertThat(provenance.guidance()).isEqualTo(6.5);
        assertThat(provenance.width()).isEqualTo(512);
        assertThat(provenance.height()).isEqualTo(768);
        assertThat(provenance.promptSha256()).isEqualTo(sha256(PROMPT.getBytes(StandardCharsets.UTF_8)));
        assertThat(provenance.negativePromptSha256()).isEqualTo(sha256("blurry".getBytes(StandardCharsets.UTF_8)));
        assertThat(provenance.outputSha256()).isEqualTo(sha256(png));
        assertThat(provenance.outputBytes()).isEqualTo(png.length);
    }

    @Test
    void theRequestHoldsOnlyTheWorkersFieldsWhichItWouldRefuseOtherwise() throws Exception {
        resultOf(adapter.submit(request(ScriptedImageWorker.BASE_FILE)));

        assertThat(worker.jobCalls()).containsExactly("POST /v1/generate");
        JsonNode body = json.readTree(worker.seen().getFirst().body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("prompt", "negativePrompt", "width", "height", "steps",
                "guidanceScale", "seed");
        assertThat(body.path("prompt").stringValue()).isEqualTo(PROMPT);
        assertThat(body.path("negativePrompt").stringValue()).isEqualTo("blurry");
        assertThat(body.path("width").intValue()).isEqualTo(512);
        assertThat(body.path("height").intValue()).isEqualTo(768);
        assertThat(body.path("steps").intValue()).isEqualTo(12);
        assertThat(body.path("guidanceScale").doubleValue()).isEqualTo(6.5);
        assertThat(body.path("seed").longValue()).isEqualTo(42);
    }

    @Test
    void aRequestWithoutACheckpointTakesTheResidentOne() throws Exception {
        ImageResult result = resultOf(adapter.submit(request(null)));

        assertThat(result.status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertThat(result.provenance().model()).isNull();
    }

    @Test
    void aResultFromAnotherCheckpointIsRefused() throws Exception {
        assertFails(adapter.submit(request(ScriptedImageWorker.RECOMMENDED_ID)), "image-wrong-checkpoint");
    }

    @Test
    void submitNeverWaitsForTheWorkerAndJobsRunOneAtATime() throws Exception {
        worker.holdJobs();
        long started = System.nanoTime();
        ImageHandle first = adapter.submit(request(null));
        ImageHandle second = adapter.submit(request(null));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));

        assertThat(worker.awaitCalls("/v1/generate", 1)).isTrue();
        Thread.sleep(100);
        assertThat(worker.count("/v1/generate")).as("the second waits behind the first").isEqualTo(1);
        assertThat(first.result().toCompletableFuture().isDone()).isFalse();

        worker.release();
        assertThat(resultOf(first).status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertThat(resultOf(second).status()).isEqualTo(ImageResult.Status.COMPLETED);
    }

    @Test
    void cancellingARunningJobCallsTheCancelRouteOnceAndYieldsNoImage() throws Exception {
        worker.holdJobs();
        ImageHandle handle = adapter.submit(request(null));
        assertThat(worker.awaitRunningJob()).isTrue();

        assertThat(handle.cancel()).isTrue();
        handle.cancel();

        ImageResult result = resultOf(handle);
        assertThat(result.status()).isEqualTo(ImageResult.Status.CANCELLED);
        assertThat(result.png()).isNull();
        assertThat(worker.count("/v1/cancel")).isEqualTo(1);
        assertThat(worker.seen()).filteredOn(call -> call.path().equals("/v1/cancel")).singleElement()
                .satisfies(call -> assertThat(call.body()).isEqualTo("{}"));
    }

    @Test
    void aCancelThatArrivesBeforeTheWorkerStartedTheJobIsSentAgain() throws Exception {
        worker.delayJobStarts();
        worker.holdJobs();
        ImageHandle handle = adapter.submit(request(null));
        assertThat(worker.awaitCalls("/v1/generate", 1)).isTrue();

        assertThat(handle.cancel()).isTrue();
        assertThat(worker.awaitCalls("/v1/cancel", 2)).as("the first calls find no job").isTrue();
        assertThat(handle.result().toCompletableFuture().isDone()).isFalse();
        worker.releaseJobStarts();

        // The job is held until a cancel reaches it, so an answer here means a repeated cancel did.
        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.CANCELLED);
        assertThat(worker.jobCalls()).contains("POST /v1/generate");
    }

    @Test
    void theNextJobWaitsUntilTheCancelOfTheLastHasAnswered() throws Exception {
        worker.holdJobs();
        worker.holdCancelAnswers();
        ImageHandle first = adapter.submit(request(null));
        ImageHandle second = adapter.submit(request(null));
        assertThat(worker.awaitRunningJob()).isTrue();

        first.cancel();
        assertThat(worker.awaitCalls("/v1/cancel", 1)).isTrue();
        Thread.sleep(200);

        assertThat(worker.count("/v1/generate")).as("a late cancel must not stop the next job").isEqualTo(1);
        worker.release();
        assertThat(resultOf(first).status()).isEqualTo(ImageResult.Status.CANCELLED);
        assertThat(resultOf(second).status()).isEqualTo(ImageResult.Status.COMPLETED);
    }

    @Test
    void cancellingAQueuedJobEndsItAtOnceAndSendsNothing() throws Exception {
        worker.holdJobs();
        ImageHandle first = adapter.submit(request(null));
        ImageHandle second = adapter.submit(request(null));
        assertThat(worker.awaitCalls("/v1/generate", 1)).isTrue();

        assertThat(second.cancel()).isTrue();

        assertThat(resultOf(second).status()).isEqualTo(ImageResult.Status.CANCELLED);
        assertThat(worker.count("/v1/cancel")).isZero();
        worker.release();
        assertThat(resultOf(first).status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertThat(worker.count("/v1/generate")).isEqualTo(1);
    }

    @Test
    void aFinishedJobCannotBeCancelled() throws Exception {
        ImageHandle handle = adapter.submit(request(null));
        resultOf(handle);

        assertThat(handle.cancel()).isFalse();
        assertThat(worker.count("/v1/cancel")).isZero();
    }

    @Test
    void aFailingCancelStillEndsTheJobCancelled() throws Exception {
        worker.holdJobs();
        worker.refuseNext("/v1/cancel", 500, "internal-error");
        ImageHandle handle = adapter.submit(request(null));
        assertThat(worker.awaitCalls("/v1/generate", 1)).isTrue();

        handle.cancel();
        assertThat(worker.awaitCalls("/v1/cancel", 1)).isTrue();
        worker.release();

        assertThat(resultOf(handle).status()).isEqualTo(ImageResult.Status.CANCELLED);
    }

    @Test
    void theWorkersFixedCodesBecomeThisPortsFixedCodes() throws Exception {
        Map<String, String> codes = Map.of("busy", "image-server-busy", "not-loaded", "image-not-loaded",
                "cancelled", "image-interrupted", "prompt-too-long", "image-prompt-too-long",
                "prompt-conflicts-with-safe-defaults", "image-prompt-refused", "gpu-out-of-memory", "image-out-of-memory",
                "invalid-output", "image-bad-png", "generation-failed", "image-generation-failed",
                "a-code-from-a-later-version", "image-status");
        for (Map.Entry<String, String> code : codes.entrySet()) {
            worker.refuseNext("/v1/generate", 409, code.getKey());
            assertFails(adapter.submit(request(null)), code.getValue());
        }
    }

    @Test
    void generatingWithNothingResidentFailsWithItsOwnCode() throws Exception {
        worker.alreadyHolds(null);

        assertFails(adapter.submit(request(null)), "image-not-loaded");
    }

    @Test
    void aSamplerTheWorkerCannotUseIsRefusedBeforeAnyCall() throws Exception {
        ImageRequest other = new ImageRequest(PROMPT, "", null, 512, 512, 1, 6, 1, "dpm-2m");

        assertFails(adapter.submit(other), "image-sampler-unsupported");
        assertThat(worker.seen()).isEmpty();

        ImageRequest same = new ImageRequest(PROMPT, "", null, 512, 512, 1, 6, 1, WorkerImageAdapter.WORKER_SAMPLER);
        assertThat(resultOf(adapter.submit(same)).provenance().sampler()).isEqualTo(WorkerImageAdapter.WORKER_SAMPLER);
    }

    @Test
    void aBrokenAnswerFailsWithTheCodeOfTheBrokenRule() throws Exception {
        byte[] right = PngFixtures.valid(512, 768);
        worker.replyNext("/v1/generate", 200, "not json");
        assertFails(adapter.submit(request(null)), "image-malformed");
        worker.replyNext("/v1/generate", 200, "{\"imagePngBase64\":7}");
        assertFails(adapter.submit(request(null)), "image-malformed");
        worker.replyNext("/v1/generate", 200, answerWith("\"not base64!\""));
        assertFails(adapter.submit(request(null)), "image-bad-encoding");
        worker.replyNext("/v1/generate", 200, answerWith("\"" + PngFixtures.base64(new byte[64]) + "\""));
        assertFails(adapter.submit(request(null)), "image-bad-png");
        worker.replyNext("/v1/generate", 200, answerWith("\"" + PngFixtures.base64(PngFixtures.valid(512, 512)) + "\""));
        assertFails(adapter.submit(request(null)), "image-wrong-size");
        worker.replyNext("/v1/generate", 200, answerWith("\"" + PngFixtures.base64(right) + "\""));
        ImageResult whole = resultOf(adapter.submit(request(null)));
        assertThat(whole.status()).isEqualTo(ImageResult.Status.COMPLETED);
        assertThat(whole.provenance().serverModelHash()).as("a hash the worker did not report stays unknown").isNull();
    }

    @Test
    void aTooLargeAnswerAndARedirectAreRefused() throws Exception {
        WorkerImageAdapter small = new WorkerImageAdapter(new WorkerSettings(worker.url(), BOUND, BOUND, BOUND, 8), 1024);
        adapters.add(small);
        worker.replyNext("/v1/generate", 200, "x".repeat(2048));
        assertFails(small.submit(request(null)), "image-response-too-large");

        worker.replyNext("/v1/generate", 307, "");
        assertFails(adapter.submit(request(null)), "image-status");
    }

    @Test
    void aCallThatTimesOutFailsAndAsksTheWorkerToStop() throws Exception {
        worker.holdJobs();
        WorkerImageAdapter impatient = adapter(Duration.ofMillis(300), 8);

        assertFails(impatient.submit(request(null)), "image-timeout");

        assertThat(worker.awaitCalls("/v1/cancel", 1)).as("a worker keeps working after a client gives up").isTrue();
    }

    @Test
    void anUnreachableWorkerFailsTheJobAndSubmitDoesNotThrow() throws Exception {
        worker.close();

        assertFails(adapter.submit(request(null)), "image-unreachable");
    }

    @Test
    void aFullQueueFailsTheSubmission() throws Exception {
        worker.holdJobs();
        WorkerImageAdapter small = adapter(BOUND, 1);
        ImageHandle running = small.submit(request(null));
        assertThat(worker.awaitCalls("/v1/generate", 1)).isTrue();
        ImageHandle queued = small.submit(request(null));

        assertFails(small.submit(request(null)), "image-queue-full");

        worker.release();
        resultOf(running);
        resultOf(queued);
    }

    @Test
    void closingEndsQueuedJobsAndRefusesNewOnes() throws Exception {
        worker.holdJobs();
        ImageHandle running = adapter.submit(request(null));
        assertThat(worker.awaitCalls("/v1/generate", 1)).isTrue();
        ImageHandle queued = adapter.submit(request(null));

        adapter.close();

        assertFails(queued, "image-shutdown");
        assertFails(adapter.submit(request(null)), "image-shutdown");
        worker.release();
        assertThat(resultOf(running).status()).isEqualTo(ImageResult.Status.COMPLETED);
    }

    @Test
    void noFailureCodeHoldsWorkerTextAddressOrPrompt() throws Exception {
        worker.replyNext("/v1/generate", 500, "{\"error\":\"" + PROMPT + " at " + worker.url() + "\"}");

        ImageResult result = resultOf(adapter.submit(request(null)));

        assertThat(result.errorCode()).isEqualTo("image-status");
    }
}
