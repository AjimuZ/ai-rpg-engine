package org.rpgengine.inference.image;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageProvenance;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link ImageGenerationPort} over the packaged image worker's {@code POST /v1/generate} and {@code POST /v1/cancel}
 * (docker/image-worker/image_worker.py; only a scripted double has answered this class). It generates on the checkpoint
 * already resident: the worker's generate route takes no checkpoint, and its load belongs to the image control.
 *
 * <p>The generate call lasts as long as the job, so jobs run one at a time on a single thread behind a bounded queue, and
 * {@link #submit} never waits for the worker. A cancel carries no job ID and stops whatever the worker is generating, so
 * it is sent only while this adapter's own call is in flight, and the next job does not start until that cancel has
 * answered. {@code no-job} means the worker has not started the call yet, so the cancel is sent again until the call ends.
 * A worker keeps working after a client merely gives up, so a call that timed out is followed by one cancel too.
 *
 * <p>A completed result passed {@link PngCheck} against the requested size and, when the request names a checkpoint, the
 * worker's answer named that checkpoint. Failures are fixed codes: {@code image-queue-full}, {@code image-server-busy},
 * {@code image-not-loaded}, {@code image-wrong-checkpoint}, {@code image-sampler-unsupported},
 * {@code image-prompt-too-long}, {@code image-prompt-refused}, {@code image-out-of-memory},
 * {@code image-generation-failed}, {@code image-timeout}, {@code image-unreachable}, {@code image-status},
 * {@code image-response-too-large}, {@code image-malformed}, {@code image-bad-encoding}, {@code image-bad-png},
 * {@code image-wrong-size}, {@code image-interrupted} and {@code image-shutdown}. Nothing the worker says beyond its fixed
 * codes reaches a result.
 */
final class WorkerImageAdapter implements ImageGenerationPort, AutoCloseable {
    static final String BACKEND = "local-image";
    /** The worker's only sampler; its generate route has no field for another. */
    static final String WORKER_SAMPLER = "euler-ancestral";
    /** Base64 of {@link PngCheck#MAX_BYTES} and a short document around it. */
    static final int MAX_RESPONSE_BYTES = 33 * 1024 * 1024;
    private static final int MAX_CANCEL_BYTES = 64 * 1024;
    /** Largest base64 text that can decode to {@link PngCheck#MAX_BYTES}. */
    private static final int MAX_BASE64_CHARACTERS = (PngCheck.MAX_BYTES + 2) / 3 * 4;
    private static final byte[] EMPTY_OBJECT = {'{', '}'};
    private static final Duration CANCEL_RETRY = Duration.ofMillis(100);
    private static final Pattern MODEL_HASH = Pattern.compile("[0-9a-fA-F]{64}");
    /** The worker's refusal codes of a generate call; any other answers {@code image-status}. */
    private static final Map<String, String> WORKER_CODES = Map.of(
            "busy", "image-server-busy",
            "not-loaded", "image-not-loaded",
            "cancelled", "image-interrupted",
            "prompt-too-long", "image-prompt-too-long",
            "prompt-conflicts-with-safe-defaults", "image-prompt-refused",
            "gpu-out-of-memory", "image-out-of-memory",
            "invalid-output", "image-bad-png",
            "generation-failed", "image-generation-failed",
            "tokenizers-disagree", "image-generation-failed");

    private final WorkerSettings settings;
    private final WorkerClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ThreadPoolExecutor worker;
    private final int responseLimit;

    /** @param settings the worker's address, time bounds and queue */
    WorkerImageAdapter(WorkerSettings settings) {
        this(settings, MAX_RESPONSE_BYTES);
    }

    /**
     * @param settings the worker's address, time bounds and queue
     * @param responseLimit largest generation answer accepted, in bytes; a smaller one lets tests reach the bound cheaply
     */
    WorkerImageAdapter(WorkerSettings settings, int responseLimit) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.responseLimit = responseLimit;
        client = new WorkerClient(settings);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(settings.maxQueued()),
                task -> {
                    Thread thread = new Thread(task, "image-worker-client");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override
    public String backend() {
        return BACKEND;
    }

    @Override
    public ImageHandle submit(ImageRequest request) {
        Job job = new Job(Objects.requireNonNull(request, "request"));
        try {
            worker.execute(job);
        } catch (RejectedExecutionException full) {
            job.finish(ImageResult.failed(worker.isShutdown() ? "image-shutdown" : "image-queue-full"));
        }
        return job;
    }

    /** Stop accepting jobs; queued jobs end failed with {@code image-shutdown}, a running one is left to its handle. */
    @Override
    public void close() {
        List<Runnable> waiting = new ArrayList<>();
        worker.shutdown();
        worker.getQueue().drainTo(waiting);
        for (Runnable task : waiting) {
            ((Job) task).finish(ImageResult.failed("image-shutdown"));
        }
    }

    /** A failure with its fixed code. */
    private static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        Failure(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    private enum Phase {
        QUEUED, GENERATING, SETTLING, DONE
    }

    private final class Job implements ImageHandle, Runnable {
        private final ImageRequest request;
        private final CompletableFuture<ImageResult> result = new CompletableFuture<>();
        private Phase phase = Phase.QUEUED;
        private boolean cancelRequested;
        private Thread canceller;

        Job(ImageRequest request) {
            this.request = request;
        }

        @Override
        public CompletionStage<ImageResult> result() {
            return result.minimalCompletionStage();
        }

        @Override
        public boolean cancel() {
            synchronized (this) {
                if (phase == Phase.DONE || result.isDone()) {
                    return false;
                }
                cancelRequested = true;
                if (phase == Phase.QUEUED) {
                    // The worker thread finds the job finished and skips it.
                    result.complete(ImageResult.cancelled());
                    return true;
                }
                if (phase == Phase.GENERATING && canceller == null) {
                    canceller = Thread.startVirtualThread(this::stopOnWorker);
                }
            }
            return true;
        }

        void finish(ImageResult outcome) {
            synchronized (this) {
                phase = Phase.DONE;
            }
            result.complete(outcome);
        }

        @Override
        public void run() {
            synchronized (this) {
                if (result.isDone()) {
                    return;
                }
                phase = Phase.GENERATING;
            }
            ImageResult outcome;
            try {
                outcome = accept(request, generate(request));
            } catch (Failure failure) {
                outcome = ImageResult.failed(failure.code);
            } catch (RuntimeException unexpected) {
                // The type is the only safe part: the message may hold worker text.
                outcome = ImageResult.failed("image-malformed");
            }
            awaitCanceller();
            synchronized (this) {
                phase = Phase.DONE;
                if (cancelRequested) {
                    // An acknowledged cancel never yields an image or an error; whatever came back is dropped.
                    outcome = ImageResult.cancelled();
                }
            }
            result.complete(outcome);
        }

        /** Best effort: if every call fails the worker finishes the job and the result is still cancelled. */
        private void stopOnWorker() {
            try {
                while (generating()) {
                    WorkerClient.Answer answer = client.post(WorkerClient.CANCEL_PATH, EMPTY_OBJECT, MAX_CANCEL_BYTES,
                            settings.controlTimeout());
                    if (!"no-job".equals(client.refusal(answer))) {
                        return;
                    }
                    Thread.sleep(CANCEL_RETRY);
                }
            } catch (WorkerClient.Unanswered unanswered) {
                // The generate call decides the outcome.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private synchronized boolean generating() {
            return phase == Phase.GENERATING;
        }

        /** The next job must not start while this job's cancel can still reach the worker. */
        private void awaitCanceller() {
            Thread pending;
            synchronized (this) {
                phase = Phase.SETTLING;
                pending = canceller;
            }
            if (pending == null) {
                return;
            }
            try {
                pending.join(settings.controlTimeout().plus(CANCEL_RETRY));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private byte[] generate(ImageRequest request) throws Failure {
        if (request.sampler() != null && !WORKER_SAMPLER.equals(request.sampler())) {
            throw new Failure("image-sampler-unsupported");
        }
        WorkerClient.Answer answer;
        try {
            answer = client.post(WorkerClient.GENERATE_PATH, json.writeValueAsBytes(body(request)), responseLimit,
                    settings.generateTimeout());
        } catch (WorkerClient.Unanswered unanswered) {
            if (unanswered.reason() == WorkerClient.Unanswered.Reason.TIMEOUT) {
                cancelAfterTimeout();
            }
            throw new Failure(switch (unanswered.reason()) {
                case TIMEOUT -> "image-timeout";
                case UNREACHABLE -> "image-unreachable";
                case TOO_LARGE -> "image-response-too-large";
                case INTERRUPTED -> "image-interrupted";
            });
        }
        if (!answer.ok()) {
            throw new Failure(WORKER_CODES.getOrDefault(client.refusal(answer), "image-status"));
        }
        return answer.body();
    }

    private void cancelAfterTimeout() {
        try {
            client.post(WorkerClient.CANCEL_PATH, EMPTY_OBJECT, MAX_CANCEL_BYTES, settings.controlTimeout());
        } catch (WorkerClient.Unanswered unanswered) {
            // The job's result is already a timeout.
        }
    }

    /** The worker refuses any field it does not know, so this holds its fields only. */
    private ObjectNode body(ImageRequest request) {
        return json.createObjectNode().put("prompt", request.prompt()).put("negativePrompt", request.negativePrompt())
                .put("width", request.width()).put("height", request.height()).put("steps", request.steps())
                .put("guidanceScale", request.guidance()).put("seed", request.seed());
    }

    /** Turn a generate answer into a validated result, or fail with the code of the first broken rule. */
    private ImageResult accept(ImageRequest request, byte[] answer) throws Failure {
        JsonNode root = client.object(answer);
        if (root == null || !root.path("imagePngBase64").isString()) {
            throw new Failure("image-malformed");
        }
        if (request.model() != null && !request.model().equals(WorkerClient.text(root.path("checkpoint")))
                && !request.model().equals(WorkerClient.text(root.path("catalogId")))) {
            throw new Failure("image-wrong-checkpoint");
        }
        String encoded = WorkerClient.text(root.path("imagePngBase64"));
        if (encoded.length() > MAX_BASE64_CHARACTERS) {
            throw new Failure("image-bad-png");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException notBase64) {
            throw new Failure("image-bad-encoding");
        }
        byte[] png;
        try {
            png = PngCheck.validate(decoded, request.width(), request.height());
        } catch (PngCheck.Invalid invalid) {
            throw new Failure(invalid.wrongSize() ? "image-wrong-size" : "image-bad-png");
        }
        return ImageResult.completed(png, new ImageProvenance(BACKEND, request.model(), reportedModelHash(root),
                request.sampler(), request.seed(), request.steps(), request.guidance(), request.width(), request.height(),
                A1111ImageAdapter.sha256(request.prompt().getBytes(StandardCharsets.UTF_8)),
                A1111ImageAdapter.sha256(request.negativePrompt().getBytes(StandardCharsets.UTF_8)),
                A1111ImageAdapter.sha256(png), png.length));
    }

    /** The checkpoint hash in the worker's provenance record, or null when it reports none. */
    private static String reportedModelHash(JsonNode root) {
        JsonNode hash = root.path("provenance").path("checkpoint").path("sha256");
        return hash.isString() && MODEL_HASH.matcher(hash.stringValue()).matches()
                ? hash.stringValue().toLowerCase(Locale.ROOT) : null;
    }
}
