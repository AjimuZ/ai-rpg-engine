package org.rpgengine.inference.image;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loopback HTTP double of the packaged image worker, written from docker/image-worker/image_worker.py: its routes, answer
 * shapes, refusal codes, single job slot (freed before the answer is sent, as there) and single resident checkpoint. It is
 * a fixture and shows nothing about how the real worker behaves on a GPU. It is public so that the admission package's
 * end-to-end test can drive it.
 *
 * <p>Tests may hold a load, a generate job or a cancel answer open, delay a job before it takes the slot, or script one
 * answer per route. Everything received is recorded, oldest first.
 */
public final class ScriptedImageWorker implements AutoCloseable {
    public static final String RECOMMENDED_FILE = "waiIllustriousSDXL_v170.safetensors";
    public static final String RECOMMENDED_ID = "wai-illustrious-sdxl-v17";
    public static final String BASE_FILE = "sd_xl_base_1.0.safetensors";
    public static final String BASE_ID = "sdxl-base-1.0";
    public static final String OWNER_FILE = "ownerMix_v2.safetensors";
    public static final String CHECKPOINT_SHA256 = "AB".repeat(32);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> CATALOG = Map.of(RECOMMENDED_ID, RECOMMENDED_FILE, BASE_ID, BASE_FILE);
    private static final Set<String> INSTALLED = Set.of(RECOMMENDED_FILE, BASE_FILE, OWNER_FILE);
    private static final Set<String> GENERATE_FIELDS = Set.of("prompt", "negativePrompt", "width", "height", "steps",
            "guidanceScale", "seed", "loras");
    private static final Reply BUSY = refusal(409, "busy");

    /** One request as received. */
    public record Seen(String method, String path, String body) {
    }

    private record Reply(int status, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Queue<Reply>> scripted = new HashMap<>();
    private final Semaphore slot = new Semaphore(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String loaded;
    private volatile CountDownLatch cancelRequest;
    private volatile CountDownLatch loadGate;
    private volatile CountDownLatch generateGate;
    private volatile CountDownLatch cancelGate;
    private volatile CountDownLatch jobStartGate;

    public ScriptedImageWorker() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::route);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** @return the file the worker holds, or null */
    public String loaded() {
        return loaded;
    }

    /** Set what is resident without a request, as an earlier process or a restart of the worker would leave it. */
    public void alreadyHolds(String file) {
        loaded = file;
    }

    /** Answer the next call of this route with this status and refusal code. */
    public void refuseNext(String path, int status, String code) {
        replyNext(path, status, refusal(status, code).body());
    }

    /** Answer the next call of this route with this status and body. */
    public void replyNext(String path, int status, String body) {
        synchronized (scripted) {
            scripted.computeIfAbsent(path, key -> new ConcurrentLinkedQueue<>()).add(new Reply(status, body));
        }
    }

    /** Keep the next load in the job slot, with nothing resident, until {@link #release}. */
    public void holdLoads() {
        loadGate = new CountDownLatch(1);
    }

    /** Keep generate jobs in the job slot until {@link #release} or a cancel. */
    public void holdJobs() {
        generateGate = new CountDownLatch(1);
    }

    /** Take a cancel request at once and keep its answer back until {@link #release}. */
    public void holdCancelAnswers() {
        cancelGate = new CountDownLatch(1);
    }

    /** A generate call waits before it takes the job slot, until {@link #releaseJobStarts}, so a cancel finds no job. */
    public void delayJobStarts() {
        jobStartGate = new CountDownLatch(1);
    }

    /** Let delayed generate calls go on to the job slot. */
    public void releaseJobStarts() {
        open(jobStartGate);
    }

    /** Open every gate. */
    public void release() {
        open(loadGate);
        open(generateGate);
        open(cancelGate);
        open(jobStartGate);
    }

    private static void open(CountDownLatch gate) {
        if (gate != null) {
            gate.countDown();
        }
    }

    public List<Seen> seen() {
        synchronized (seen) {
            return List.copyOf(seen);
        }
    }

    /** @return method and path of every request but the model list, oldest first */
    public List<String> jobCalls() {
        return seen().stream().filter(call -> !call.path().equals("/v1/models"))
                .map(call -> call.method() + " " + call.path()).toList();
    }

    public long count(String path) {
        return seen().stream().filter(call -> call.path().equals(path)).count();
    }

    /** @return true once a route has been called this many times, false after ten seconds */
    public boolean awaitCalls(String path, long calls) throws InterruptedException {
        return awaitCondition(() -> count(path) >= calls);
    }

    /** @return true once a generate job holds the slot and a cancel would reach it, false after ten seconds */
    public boolean awaitRunningJob() throws InterruptedException {
        return awaitCondition(() -> cancelRequest != null);
    }

    /** @return true once the job slot is free, false after ten seconds */
    public boolean awaitFreeSlot() throws InterruptedException {
        return awaitCondition(() -> slot.availablePermits() == 1);
    }

    private static boolean awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(2);
        }
        return true;
    }

    private void route(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        seen.add(new Seen(exchange.getRequestMethod(), path, new String(raw, StandardCharsets.UTF_8)));
        Reply reply;
        synchronized (scripted) {
            Queue<Reply> queue = scripted.get(path);
            reply = queue == null ? null : queue.poll();
        }
        if (reply == null) {
            JsonNode body = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw);
            reply = switch (exchange.getRequestMethod() + " " + path) {
                case "GET /v1/models" -> new Reply(200, models());
                case "POST /v1/load" -> load(body);
                case "POST /v1/unload" -> unload(body);
                case "POST /v1/generate" -> generate(body);
                case "POST /v1/cancel" -> cancel();
                default -> refusal(404, "unknown-route");
            };
        }
        respond(exchange, reply);
    }

    private static Reply refusal(int status, String code) {
        return new Reply(status, "{\"error\": \"" + code + "\"}");
    }

    private String models() {
        ObjectNode document = JSON.createObjectNode().put("recommended", RECOMMENDED_ID).put("contentPolicy", "safe");
        document.putArray("alternatives").add("pony-diffusion-v6-xl").add(BASE_ID);
        document.putArray("checkpoints").add(RECOMMENDED_FILE).add(BASE_FILE).add(OWNER_FILE);
        document.putArray("loras");
        document.put("loaded", loaded);
        return JSON.writeValueAsString(document);
    }

    private Reply load(JsonNode body) {
        String requested = body.path("checkpoint").isString() ? body.path("checkpoint").stringValue() : RECOMMENDED_FILE;
        String file = CATALOG.getOrDefault(requested, requested);
        if (!file.endsWith(".safetensors")) {
            return refusal(400, "invalid-file-name");
        }
        if (!slot.tryAcquire()) {
            return BUSY;
        }
        try {
            if (!INSTALLED.contains(file)) {
                return refusal(404, "checkpoint-missing");
            }
            if (!file.equals(loaded)) {
                loaded = null;
                await(loadGate);
                loaded = file;
            }
            ObjectNode answer = JSON.createObjectNode().put("loaded", file).put("catalogId", catalogId(file))
                    .put("contentPolicy", "safe");
            return new Reply(200, JSON.writeValueAsString(answer));
        } finally {
            slot.release();
        }
    }

    private static String catalogId(String file) {
        return CATALOG.entrySet().stream().filter(entry -> entry.getValue().equals(file)).map(Map.Entry::getKey).findFirst()
                .orElse(null);
    }

    private Reply unload(JsonNode body) {
        if (body.size() > 0) {
            return refusal(400, "unknown-field");
        }
        if (!slot.tryAcquire()) {
            return BUSY;
        }
        try {
            loaded = null;
            return new Reply(200, "{\"loaded\": null}");
        } finally {
            slot.release();
        }
    }

    private Reply generate(JsonNode body) {
        for (String field : body.propertyNames()) {
            if (!GENERATE_FIELDS.contains(field)) {
                return refusal(400, "unknown-field");
            }
        }
        await(jobStartGate);
        if (!slot.tryAcquire()) {
            return BUSY;
        }
        try {
            if (loaded == null) {
                return refusal(409, "not-loaded");
            }
            CountDownLatch cancelled = new CountDownLatch(1);
            cancelRequest = cancelled;
            try {
                CountDownLatch gate = generateGate;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (gate != null && gate.getCount() > 0 && cancelled.getCount() > 0 && System.nanoTime() < deadline) {
                    Thread.sleep(2);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                cancelRequest = null;
            }
            return cancelled.getCount() == 0 ? refusal(409, "cancelled") : new Reply(200, JSON.writeValueAsString(picture(body)));
        } finally {
            slot.release();
        }
    }

    private ObjectNode picture(JsonNode body) {
        int width = body.path("width").intValue();
        int height = body.path("height").intValue();
        String file = loaded;
        ObjectNode answer = JSON.createObjectNode().put("imagePngBase64", PngFixtures.base64(PngFixtures.valid(width, height)))
                .put("checkpoint", file).put("catalogId", catalogId(file)).put("contentPolicy", "safe")
                .put("seed", body.path("seed").longValue()).put("width", width).put("height", height)
                .put("steps", body.path("steps").intValue()).put("guidanceScale", body.path("guidanceScale").doubleValue())
                .put("chunks", 1);
        answer.putObject("provenance").putObject("checkpoint").put("name", file).put("sha256", CHECKPOINT_SHA256)
                .put("catalogId", catalogId(file));
        return answer;
    }

    private Reply cancel() {
        CountDownLatch running = cancelRequest;
        if (running == null) {
            return refusal(409, "no-job");
        }
        running.countDown();
        await(cancelGate);
        return new Reply(200, "{\"cancelling\": true}");
    }

    private static void await(CountDownLatch gate) {
        if (gate != null) {
            try {
                gate.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void respond(HttpExchange exchange, Reply reply) {
        try (exchange) {
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException aborted) {
            // The client gave up, which some tests do on purpose.
        }
    }

    /** Stop the server and release held requests; a second call does nothing. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            release();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
