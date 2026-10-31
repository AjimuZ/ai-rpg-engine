package org.rpgengine.inference.image;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loopback HTTP double of the gateway in front of a Forge or A1111 server. Every reply is scripted by the
 * test: this is a fixture and not a server, and it shows nothing about how a real one answers. Unscripted,
 * txt2img answers HTTP 500, progress reports an idle server and interrupt answers 200. It records every
 * request, counts interrupts and remembers how many generations ran at once.
 */
final class ScriptedForgeServer implements AutoCloseable {
    static final String DESTINATION = "forge";
    private static final String BASE = "/upstream/" + DESTINATION + "/sdapi/v1/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One request as received. */
    record Seen(String method, String path, Map<String, String> headers, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Queue<Consumer<HttpExchange>> script = new ConcurrentLinkedQueue<>();
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private final List<CountDownLatch> gates = new CopyOnWriteArrayList<>();
    private final Semaphore started = new Semaphore(0);
    private final AtomicInteger interrupts = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int busyJobs;

    ScriptedForgeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext(BASE + "txt2img", this::generate);
        server.createContext(BASE + "progress", exchange -> {
            record(exchange);
            respond(exchange, 200, "{\"progress\":0.0,\"state\":{\"job_count\":" + busyJobs + "}}");
        });
        server.createContext(BASE + "interrupt", exchange -> {
            record(exchange);
            interrupts.incrementAndGet();
            release();
            respond(exchange, 200, "{}");
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Script the next txt2img answer. */
    ScriptedForgeServer replies(int status, String body) {
        script.add(exchange -> respond(exchange, status, body));
        return this;
    }

    /** Script a successful txt2img answer holding these base64 images and an {@code info} document. */
    ScriptedForgeServer answers(String... images) {
        return replies(200, success(images));
    }

    /** Script the next txt2img call to wait for {@link #release()} or an interrupt, and then answer with {@code body}. */
    ScriptedForgeServer holds(String body) {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        script.add(exchange -> {
            try {
                gate.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, body);
        });
        return this;
    }

    /** Script the next txt2img call to close the connection without answering. */
    ScriptedForgeServer dropsConnection() {
        script.add(HttpExchange::close);
        return this;
    }

    /** Script the next txt2img call to answer with this many bytes of filler. */
    ScriptedForgeServer sendsBytes(int count) {
        script.add(exchange -> respond(exchange, 200, "x".repeat(count)));
        return this;
    }

    /** Script the next txt2img call to redirect somewhere else. */
    ScriptedForgeServer redirectsTo(String location) {
        script.add(exchange -> {
            exchange.getResponseHeaders().set("Location", location);
            respond(exchange, 307, "");
        });
        return this;
    }

    /** Make the progress route report this many jobs in progress. */
    void busyWith(int jobs) {
        busyJobs = jobs;
    }

    /** Let every held generation answer. */
    void release() {
        gates.forEach(CountDownLatch::countDown);
    }

    /** Wait until one more txt2img call has arrived. */
    boolean awaitGeneration() throws InterruptedException {
        return started.tryAcquire(10, TimeUnit.SECONDS);
    }

    int interrupts() {
        return interrupts.get();
    }

    int peakGenerations() {
        return peak.get();
    }

    List<Seen> seen() {
        synchronized (seen) {
            return List.copyOf(seen);
        }
    }

    /** @return the paths of the requests received, oldest first */
    List<String> paths() {
        return seen().stream().map(Seen::path).collect(Collectors.toList());
    }

    /** @return a txt2img answer body with these base64 images */
    static String success(String... images) {
        return JSON.writeValueAsString(Map.of("images", List.of(images), "parameters", Map.of(),
                "info", JSON.writeValueAsString(Map.of("sd_model_hash", "ABCDEF0123", "seed", 7))));
    }

    private void generate(HttpExchange exchange) {
        record(exchange);
        int now = running.incrementAndGet();
        peak.accumulateAndGet(now, Math::max);
        started.release();
        try {
            Consumer<HttpExchange> reply = script.poll();
            if (reply == null) {
                respond(exchange, 500, "{\"error\":\"script exhausted\"}");
            } else {
                reply.accept(exchange);
            }
        } finally {
            running.decrementAndGet();
        }
    }

    private void record(HttpExchange exchange) {
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = exchange.getRequestHeaders().entrySet().stream()
                    .collect(Collectors.toMap(entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT),
                            entry -> entry.getValue().getFirst(), (first, second) -> first));
            String query = exchange.getRequestURI().getRawQuery();
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath()
                    + (query == null ? "" : "?" + query), headers, body));
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Set-Cookie", "session=server");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
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
