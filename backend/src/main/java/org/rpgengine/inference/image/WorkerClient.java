package org.rpgengine.inference.image;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One HTTP exchange with the packaged image worker (docker/image-worker/image_worker.py), for the control and the port.
 * Follows no redirect, keeps no cookie, uses no proxy and bounds every call and body. Nothing the worker says becomes
 * text in an exception: a call either returns an {@link Answer} or fails with a {@link Unanswered.Reason}.
 */
final class WorkerClient {
    static final String MODELS_PATH = "/v1/models";
    static final String LOAD_PATH = "/v1/load";
    static final String UNLOAD_PATH = "/v1/unload";
    static final String GENERATE_PATH = "/v1/generate";
    static final String CANCEL_PATH = "/v1/cancel";
    /** A refusal is one short fixed code. */
    private static final int MAX_REFUSAL_BYTES = 64 * 1024;

    /** The worker gave no answer, so it may still be doing what it was asked. */
    static final class Unanswered extends Exception {
        private static final long serialVersionUID = 1L;

        /** Why no answer came. */
        enum Reason {
            TIMEOUT, UNREACHABLE, TOO_LARGE, INTERRUPTED
        }

        private final Reason reason;

        Unanswered(Reason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }

    /**
     * One answer of the worker.
     *
     * @param status HTTP status
     * @param body the bounded body
     */
    record Answer(int status, byte[] body) {
        boolean ok() {
            return status / 100 == 2;
        }
    }

    private final WorkerSettings settings;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client;

    /** @param settings the worker's address and connect bound */
    WorkerClient(WorkerSettings settings) {
        this.settings = settings;
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(settings.controlTimeout()).build();
    }

    /**
     * @param path a read route
     * @param limit largest accepted body of a 2xx answer, in bytes
     * @return the answer, whatever its status
     * @throws Unanswered when no whole answer came within the control bound
     */
    Answer get(String path, int limit) throws Unanswered {
        return exchange(HttpRequest.newBuilder(settings.uri(path)).GET(), limit, settings.controlTimeout());
    }

    /**
     * @param path a job route
     * @param body the JSON document
     * @param limit largest accepted body of a 2xx answer, in bytes
     * @param deadline bound of the whole call
     * @return the answer, whatever its status
     * @throws Unanswered when no whole answer came within the deadline
     */
    Answer post(String path, byte[] body, int limit, Duration deadline) throws Unanswered {
        return exchange(HttpRequest.newBuilder(settings.uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)), limit, deadline);
    }

    /** @return the document as an object, or null when the bytes are not one */
    JsonNode object(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (JacksonException malformed) {
            return null;
        }
    }

    /** @return the node's text, or null when it holds no string, which a catalog ID of an owner's file does not */
    static String text(JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }

    /** @return the worker's fixed refusal code, or an empty string when the answer holds none */
    String refusal(Answer answer) {
        JsonNode root = object(answer.body());
        String code = root == null ? null : text(root.path("error"));
        return code == null ? "" : code;
    }

    private Answer exchange(HttpRequest.Builder request, int limit, Duration deadline) throws Unanswered {
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request.timeout(deadline).build(),
                info -> new BoundedBody(info.statusCode() / 100 == 2 ? limit : MAX_REFUSAL_BYTES));
        try {
            HttpResponse<byte[]> response = pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            return new Answer(response.statusCode(), response.body());
        } catch (TimeoutException timedOut) {
            pending.cancel(true);
            throw new Unanswered(Unanswered.Reason.TIMEOUT);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Unanswered(Unanswered.Reason.INTERRUPTED);
        } catch (ExecutionException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof BoundedBody.TooLarge) {
                    throw new Unanswered(Unanswered.Reason.TOO_LARGE);
                }
                if (cause instanceof HttpTimeoutException) {
                    throw new Unanswered(Unanswered.Reason.TIMEOUT);
                }
            }
            throw new Unanswered(Unanswered.Reason.UNREACHABLE);
        }
    }
}
