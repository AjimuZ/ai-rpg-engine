package org.rpgengine.inference.local;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.rpgengine.inference.ModelControlException;
import org.rpgengine.inference.ModelServerControl;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ModelServerControl} over the llama.cpp server's documented router and slot routes: {@code POST
 * /models/load}, {@code POST /models/unload}, {@code GET /models} and {@code POST /slots/N?action=save}
 * or {@code restore}. The shapes follow that server's documentation; only a scripted server has answered
 * them. Slot routes also need the server started with a slot directory, which the packaged service does
 * not pass yet, so they answer an error there and the swap goes on without a cache.
 *
 * <p>Every call has one deadline, follows no redirect and reads a bounded body. Failures carry a fixed
 * code: {@code control-timeout}, {@code control-unreachable}, {@code control-status},
 * {@code control-malformed}, {@code control-too-large} or {@code control-interrupted}. Neither the
 * server's text nor its address reaches an exception.
 */
final class LocalModelControl implements ModelServerControl {
    /** Answers here are short status objects, and a list holds at most the catalog bound. */
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    /** The server may answer only when the work is done, and a large cache takes a while to write. */
    private static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(2);
    private static final Pattern SLOT_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final LocalSettings settings;
    private final Duration deadline;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client;

    /** @param settings the server's address and connect deadline */
    LocalModelControl(LocalSettings settings) {
        this(settings, DEFAULT_DEADLINE);
    }

    /**
     * @param settings the server's address and connect deadline
     * @param deadline bound of one whole call
     */
    LocalModelControl(LocalSettings settings, Duration deadline) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(settings.requestTimeout()).build();
    }

    @Override
    public void load(String model) throws ModelControlException {
        accepted(post(settings.uri(LocalProtocol.CONTROL_LOAD_PATH), modelBody(model)));
    }

    @Override
    public void unload(String model) throws ModelControlException {
        accepted(post(settings.uri(LocalProtocol.CONTROL_UNLOAD_PATH), modelBody(model)));
    }

    /** {@inheritDoc} A model the server does not list is not in memory. */
    @Override
    public Residency status(String model) throws ModelControlException {
        return list().getOrDefault(model, Residency.GONE);
    }

    @Override
    public List<String> resident() throws ModelControlException {
        return list().entrySet().stream()
                .filter(entry -> entry.getValue() != Residency.GONE && entry.getValue() != Residency.FAILED)
                .map(Map.Entry::getKey).sorted().toList();
    }

    @Override
    public void saveSlot(String model, int slot, String file) throws ModelControlException {
        slotAction(model, slot, file, "save");
    }

    @Override
    public void restoreSlot(String model, int slot, String file) throws ModelControlException {
        slotAction(model, slot, file, "restore");
    }

    /** The router picks the child process by the {@code model} field, as on its other POST routes. */
    private void slotAction(String model, int slot, String file, String action) throws ModelControlException {
        if (slot < 0 || !SLOT_FILE.matcher(file).matches()) {
            throw new IllegalArgumentException("slot or file name out of range");
        }
        byte[] body = json.writeValueAsBytes(json.createObjectNode().put("model", model).put("filename", file));
        byte[] answer = post(settings.uri("/slots/" + slot + "?action=" + action), body);
        try {
            LocalProtocol.requireObject(json, answer);
        } catch (LocalProtocol.Refused refused) {
            throw new ModelControlException(refused.code());
        }
    }

    private Map<String, Residency> list() throws ModelControlException {
        byte[] answer = exchange(HttpRequest.newBuilder(settings.uri(LocalProtocol.CONTROL_MODELS_PATH)).GET().build());
        try {
            return LocalProtocol.parseResidency(json, answer);
        } catch (LocalProtocol.Refused refused) {
            throw new ModelControlException(refused.code());
        }
    }

    private void accepted(byte[] answer) throws ModelControlException {
        try {
            LocalProtocol.requireSuccess(json, answer);
        } catch (LocalProtocol.Refused refused) {
            throw new ModelControlException(refused.code());
        }
    }

    private byte[] modelBody(String model) {
        return json.writeValueAsBytes(json.createObjectNode().put("model", model));
    }

    private byte[] post(URI uri, byte[] body) throws ModelControlException {
        return exchange(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    /** @return the body of a 2xx answer; any other status fails and its body is dropped */
    private byte[] exchange(HttpRequest request) throws ModelControlException {
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request,
                info -> new BoundedBody(MAX_RESPONSE_BYTES));
        try {
            HttpResponse<byte[]> response = pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() / 100 != 2) {
                throw new ModelControlException("control-status");
            }
            return response.body();
        } catch (TimeoutException timedOut) {
            pending.cancel(true);
            throw new ModelControlException("control-timeout");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ModelControlException("control-interrupted");
        } catch (ExecutionException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof BoundedBody.TooLarge) {
                    throw new ModelControlException("control-too-large");
                }
            }
            throw new ModelControlException("control-unreachable");
        }
    }
}
