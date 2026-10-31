package org.rpgengine.inference.local;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loopback HTTP double of the model server's chat API. Every reply is scripted by the test: this is a
 * fixture and not a model, and it shows nothing about how a real server answers. It records each chat
 * request body, and an unscripted chat request gets HTTP 500.
 */
public final class ScriptedModelServer implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Queue<Consumer<HttpExchange>> script = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> chats = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch closing = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String catalogBody;
    private volatile int catalogStatus = 200;

    /**
     * Start on a free loopback port.
     *
     * @param models identifiers listed by the catalog route
     * @throws IOException when the port cannot be bound
     */
    public ScriptedModelServer(String... models) throws IOException {
        lists(models);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1/models", exchange -> respond(exchange, catalogStatus, catalogBody));
        server.createContext("/v1/chat/completions", exchange -> {
            chats.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            Consumer<HttpExchange> reply = script.poll();
            if (reply == null) {
                respond(exchange, 500, "{\"error\":\"script exhausted\"}");
            } else {
                reply.accept(exchange);
            }
        });
        server.start();
    }

    /** @return the base URL of this server */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Replace the models the catalog route lists. */
    public void lists(String... models) {
        catalogBody = JSON.writeValueAsString(Map.of("object", "list", "data",
                java.util.Arrays.stream(models).map(id -> Map.of("id", id)).toList()));
    }

    /** Make the catalog route answer with {@code status} and {@code body}. */
    public void catalogAnswers(int status, String body) {
        catalogStatus = status;
        catalogBody = body;
    }

    /** Script one completed reply holding {@code content}. */
    public ScriptedModelServer answers(String content) {
        return replies(200, completion(content, "stop"));
    }

    /** Script one reply with an exact status and body. */
    public ScriptedModelServer replies(int status, String body) {
        script.add(exchange -> respond(exchange, status, body));
        return this;
    }

    /** Script one request that never gets an answer until the server closes. */
    public ScriptedModelServer holds() {
        script.add(exchange -> {
            try {
                closing.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        return this;
    }

    /** Script one request whose connection closes without an answer. */
    public ScriptedModelServer dropsConnection() {
        script.add(HttpExchange::close);
        return this;
    }

    /** @return a chat response body with one choice */
    public static String completion(String content, String finishReason) {
        return JSON.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", finishReason, "message",
                Map.of("role", "assistant", "content", content)))));
    }

    /** @return a copy of the chat request bodies, oldest first */
    public List<JsonNode> chats() {
        synchronized (chats) {
            return List.copyOf(chats);
        }
    }

    /** @return the last chat request's message contents, oldest first */
    public List<String> lastMessages() {
        List<JsonNode> all = chats();
        List<String> contents = new ArrayList<>();
        all.getLast().path("messages").forEach(message -> contents.add(message.path("content").stringValue()));
        return contents;
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException aborted) {
            // The client gave up, which some tests do on purpose.
        }
    }

    /** Stop the server and release held requests; a second call does nothing. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closing.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
