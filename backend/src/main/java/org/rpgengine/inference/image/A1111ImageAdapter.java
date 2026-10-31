package org.rpgengine.inference.image;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageProvenance;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link ImageGenerationPort} over the txt2img, progress and interrupt routes of an owner-run Forge or
 * A1111 server (docs/34 section 8), always through the gateway's {@code a1111-http} destination.
 *
 * <p>The server is shared with whoever else uses it, and its VRAM is not coordinated. This adapter
 * therefore does two things only. It runs its own jobs one at a time on a single thread, behind a
 * bounded queue. Before each job it reads {@code GET /sdapi/v1/progress}: a server already working on
 * something is reported as {@code image-server-busy} and nothing is sent to it. It never queues behind
 * a foreign job and never interrupts one.
 *
 * <p>{@code POST /sdapi/v1/interrupt} stops whatever the server is generating, so it is sent only
 * while this adapter's own txt2img call is in flight. The remaining race is that the server finishes
 * our job and another client starts one within the interrupt call's own latency.
 *
 * <p>Nothing from the server's text or address reaches a result: failures are fixed codes
 * ({@code image-queue-full}, {@code image-server-busy}, {@code image-timeout}, {@code image-unreachable},
 * {@code image-status}, {@code image-destination-not-allowed}, {@code image-response-too-large},
 * {@code image-malformed}, {@code image-bad-encoding}, {@code image-bad-png}, {@code image-wrong-size},
 * {@code image-interrupted}, {@code image-shutdown}). No redirect is followed and no cookie is kept.
 * The shapes follow the documented API of those servers; only a scripted server has answered them.
 */
final class A1111ImageAdapter implements ImageGenerationPort, AutoCloseable {
    static final String BACKEND = "a1111-http";
    static final String TXT2IMG_PATH = "/sdapi/v1/txt2img";
    static final String INTERRUPT_PATH = "/sdapi/v1/interrupt";
    static final String PROGRESS_PATH = "/sdapi/v1/progress?skip_current_image=true";
    /** The gateway profile's response bound, so that a body it would refuse is refused here too. */
    static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    /** Gateway refusals are a few bytes; an upstream error body never reaches the engine. */
    static final int MAX_ERROR_BYTES = 64 * 1024;
    /** Largest base64 text that can decode to {@link PngCheck#MAX_BYTES}. */
    private static final int MAX_BASE64_CHARACTERS = (PngCheck.MAX_BYTES + 2) / 3 * 4;
    private static final Pattern MODEL_HASH = Pattern.compile("[0-9a-fA-F]{8,64}");
    /** Gateway refusal codes (docker/gateway/gateway.py) mapped to this adapter's fixed codes. */
    private static final Map<String, String> GATEWAY_CODES = Map.ofEntries(
            Map.entry("destination-not-allowed", "image-destination-not-allowed"),
            Map.entry("path-not-allowed", "image-destination-not-allowed"),
            Map.entry("method-not-allowed", "image-destination-not-allowed"),
            Map.entry("response-too-large", "image-response-too-large"),
            Map.entry("upstream-timeout", "image-timeout"),
            Map.entry("request-timeout", "image-timeout"),
            Map.entry("upstream-unreachable", "image-unreachable"),
            Map.entry("upstream-tls", "image-unreachable"),
            Map.entry("upstream-protocol", "image-unreachable"),
            Map.entry("gateway-busy", "image-unreachable"),
            Map.entry("key-unavailable", "image-unreachable"));

    private final A1111Settings settings;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client;
    private final ThreadPoolExecutor worker;
    private final int responseLimit;

    /** @param settings the gateway destination and the time bounds */
    A1111ImageAdapter(A1111Settings settings) {
        this(settings, MAX_RESPONSE_BYTES);
    }

    /**
     * @param settings the gateway destination and the time bounds
     * @param responseLimit largest generation answer accepted, in bytes; the packaged value is
     *        {@link #MAX_RESPONSE_BYTES}, and a smaller one lets tests reach the bound cheaply
     */
    A1111ImageAdapter(A1111Settings settings, int responseLimit) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.responseLimit = responseLimit;
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(settings.controlTimeout()).build();
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(settings.maxQueued()),
                task -> {
                    Thread thread = new Thread(task, "a1111-image");
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

    /** Stop accepting jobs; queued jobs end failed with {@code image-shutdown}, a running one is not interrupted. */
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
        QUEUED, PROBING, GENERATING, DONE
    }

    private final class Job implements ImageHandle, Runnable {
        private final ImageRequest request;
        private final CompletableFuture<ImageResult> result = new CompletableFuture<>();
        private Phase phase = Phase.QUEUED;
        private boolean cancelRequested;

        Job(ImageRequest request) {
            this.request = request;
        }

        @Override
        public CompletionStage<ImageResult> result() {
            return result.minimalCompletionStage();
        }

        @Override
        public boolean cancel() {
            boolean interrupt;
            synchronized (this) {
                if (result.isDone()) {
                    return false;
                }
                cancelRequested = true;
                if (phase == Phase.QUEUED) {
                    // The worker finds the job finished and skips it.
                    result.complete(ImageResult.cancelled());
                    return true;
                }
                interrupt = phase == Phase.GENERATING;
            }
            if (interrupt) {
                Thread.startVirtualThread(this::interrupt);
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
                phase = Phase.PROBING;
            }
            ImageResult outcome;
            try {
                outcome = execute();
            } catch (Failure failure) {
                outcome = ImageResult.failed(failure.code);
            } catch (RuntimeException unexpected) {
                // The type is the only safe part: the message may hold server text.
                outcome = ImageResult.failed("image-malformed");
            }
            synchronized (this) {
                phase = Phase.DONE;
                if (cancelRequested) {
                    // An acknowledged cancel never yields an image or an error; whatever came back is dropped.
                    outcome = ImageResult.cancelled();
                }
            }
            result.complete(outcome);
        }

        private ImageResult execute() throws Failure {
            if (busy()) {
                throw new Failure("image-server-busy");
            }
            synchronized (this) {
                if (cancelRequested) {
                    return ImageResult.cancelled();
                }
                phase = Phase.GENERATING;
            }
            byte[] answer = exchange(HttpRequest.newBuilder(settings.uri(TXT2IMG_PATH))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body(request)))),
                    responseLimit, settings.generateTimeout());
            return accept(request, answer);
        }

        /** Best effort: if the call fails the server finishes the render and the result is still cancelled. */
        private void interrupt() {
            synchronized (this) {
                if (phase != Phase.GENERATING) {
                    return;
                }
            }
            try {
                exchange(HttpRequest.newBuilder(settings.uri(INTERRUPT_PATH)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[0])), MAX_ERROR_BYTES,
                        settings.controlTimeout());
            } catch (Failure ignored) {
                // The generation call decides the outcome.
            }
        }
    }

    /** @return whether the server reports work in progress that this adapter did not start */
    private boolean busy() throws Failure {
        byte[] answer = exchange(HttpRequest.newBuilder(settings.uri(PROGRESS_PATH)).GET(), MAX_ERROR_BYTES,
                settings.controlTimeout());
        JsonNode jobs = parse(answer).path("state").path("job_count");
        if (!jobs.isNumber()) {
            throw new Failure("image-malformed");
        }
        return jobs.intValue() > 0;
    }

    private ObjectNode body(ImageRequest request) {
        ObjectNode body = json.createObjectNode().put("prompt", request.prompt())
                .put("negative_prompt", request.negativePrompt()).put("width", request.width())
                .put("height", request.height()).put("steps", request.steps()).put("cfg_scale", request.guidance())
                .put("seed", request.seed()).put("batch_size", 1).put("n_iter", 1).put("send_images", true)
                .put("save_images", false);
        if (request.sampler() != null) {
            body.put("sampler_name", request.sampler());
        }
        if (request.model() != null) {
            body.putObject("override_settings").put("sd_model_checkpoint", request.model());
            body.put("override_settings_restore_afterwards", true);
        }
        return body;
    }

    private JsonNode parse(byte[] answer) throws Failure {
        try {
            JsonNode root = json.readTree(answer);
            if (root == null || !root.isObject()) {
                throw new Failure("image-malformed");
            }
            return root;
        } catch (JacksonException malformed) {
            throw new Failure("image-malformed");
        }
    }

    /** Turn a txt2img answer into a validated result, or fail with the code of the first broken rule. */
    private ImageResult accept(ImageRequest request, byte[] answer) throws Failure {
        JsonNode root = parse(answer);
        JsonNode images = root.path("images");
        if (!images.isArray() || images.size() != 1 || !images.get(0).isString()) {
            throw new Failure("image-malformed");
        }
        String encoded = images.get(0).stringValue();
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
                sha256(request.prompt().getBytes(StandardCharsets.UTF_8)),
                sha256(request.negativePrompt().getBytes(StandardCharsets.UTF_8)), sha256(png), png.length));
    }

    /** The checkpoint hash the server reports in its {@code info} document, or null when it reports none. */
    private String reportedModelHash(JsonNode root) {
        JsonNode info = root.path("info");
        if (!info.isString()) {
            return null;
        }
        try {
            JsonNode hash = json.readTree(info.stringValue()).path("sd_model_hash");
            return hash.isString() && MODEL_HASH.matcher(hash.stringValue()).matches()
                    ? hash.stringValue().toLowerCase(Locale.ROOT) : null;
        } catch (JacksonException unreadable) {
            return null;
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", missing);
        }
    }

    /**
     * @return the body of a 2xx answer; any other status fails with a fixed code and its body is not
     *         forwarded. The bound covers connecting, the server's work and the whole body.
     */
    private byte[] exchange(HttpRequest.Builder request, int limit, Duration deadline) throws Failure {
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request.timeout(deadline).build(),
                info -> new BoundedBody(info.statusCode() / 100 == 2 ? limit : MAX_ERROR_BYTES));
        try {
            HttpResponse<byte[]> response = pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() / 100 != 2) {
                throw new Failure(refusal(response.body()));
            }
            return response.body();
        } catch (TimeoutException timedOut) {
            pending.cancel(true);
            throw new Failure("image-timeout");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Failure("image-interrupted");
        } catch (ExecutionException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof BoundedBody.TooLarge) {
                    throw new Failure("image-response-too-large");
                }
                if (cause instanceof HttpTimeoutException) {
                    throw new Failure("image-timeout");
                }
            }
            throw new Failure("image-unreachable");
        }
    }

    /** The fixed code for a non-2xx answer: a gateway refusal code when it names one, else the status class. */
    private String refusal(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode code = root == null ? null : root.path("code");
            if (code != null && code.isString() && GATEWAY_CODES.containsKey(code.stringValue())) {
                return GATEWAY_CODES.get(code.stringValue());
            }
        } catch (JacksonException unreadable) {
            // Not a gateway document; only the status is known.
        }
        return "image-status";
    }
}
