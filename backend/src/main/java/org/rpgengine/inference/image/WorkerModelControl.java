package org.rpgengine.inference.image;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.rpgengine.inference.ImageModelControl;
import org.rpgengine.inference.ModelControlException;
import org.rpgengine.inference.ModelServerControl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ModelServerControl} over the packaged image worker's {@code POST /v1/load}, {@code POST /v1/unload} and
 * {@code GET /v1/models}. Those shapes are read from docker/image-worker/image_worker.py; only a scripted double has
 * answered this class.
 *
 * <p>Residency always comes from the {@code loaded} field of the worker's own answer, and three facts of the worker shape
 * how it is read:
 * <ul>
 *   <li>It holds one checkpoint and a load replaces it, so a second lease must not load another under a running first
 *       one. This control remembers the lease model it loaded for: another model reads {@code GONE}, which is true of
 *       that model, and its load fails {@code control-busy} without touching the worker. Its lease ends
 *       {@code LOAD_FAILED}, with nothing to unload.</li>
 *   <li>{@code loaded} is a file name while a model may be a catalog ID. The load's answer ties the two together; before
 *       one, a resident file that does not match the model reads {@code OTHER} (it may be that model), so a load or
 *       an unload follows, rather than {@code GONE}, which would free capacity the card does not have.</li>
 *   <li>{@code loaded} is null during a load as well as when nothing is resident. An empty answer therefore reads
 *       {@code GONE} only after the worker has answered a call from this process. Until then, or after a load nobody saw
 *       the end of, it reads {@code OTHER}; the unload that follows answers {@code busy} while the worker still works,
 *       which keeps the lease reconciling and its capacity held.</li>
 * </ul>
 *
 * <p>A successful {@link #load} and {@link #unload} also check the answer itself. A server that does not speak these
 * routes, such as an {@code a1111-http} server at this address, confirms nothing, so its lease stays held. The worker has
 * no slot routes, and only a text control is asked for them. Every failure carries a fixed code: {@code control-busy},
 * {@code control-status}, {@code control-malformed}, {@code control-timeout}, {@code control-unreachable},
 * {@code control-too-large}, {@code control-interrupted} or {@code control-unsupported}.
 *
 * <p>Thread-safe; the executor calls it one call at a time anyway, under its monitor.
 */
final class WorkerModelControl implements ModelServerControl {
    /** A model list holds the names of the installed files. */
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final byte[] EMPTY_OBJECT = {'{', '}'};

    private final WorkerSettings settings;
    private final WorkerClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    /** The lease model this control loaded or found resident, until the worker says it is gone. */
    private String holder;
    private String holderFile;
    /** True while an empty {@code loaded} may mean a load in progress. */
    private boolean unverified = true;

    /** @param settings the worker's address and time bounds */
    WorkerModelControl(WorkerSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        client = new WorkerClient(settings);
    }

    /** {@inheritDoc} The worker answers only when the checkpoint is resident, so this call lasts the whole load. */
    @Override
    public synchronized void load(String model) throws ModelControlException {
        if (holder != null && !holder.equals(model)) {
            throw new ModelControlException("control-busy");
        }
        // A catalog ID or file name; the worker takes no field for its recommended checkpoint.
        byte[] body = ImageModelControl.CURRENT_CHECKPOINT.equals(model) ? EMPTY_OBJECT
                : json.writeValueAsBytes(json.createObjectNode().put("checkpoint", model));
        unverified = true;
        WorkerClient.Answer answer = post(WorkerClient.LOAD_PATH, body, settings.loadTimeout());
        if (!answer.ok()) {
            // Only "busy" leaves the worker's slot taken, possibly by a load.
            unverified = answer.status() == 409;
            throw refused(answer);
        }
        JsonNode root = client.object(answer.body());
        if (root == null || !root.path("loaded").isString() || !names(model, root)) {
            throw new ModelControlException("control-malformed");
        }
        holder = model;
        holderFile = WorkerClient.text(root.path("loaded"));
        unverified = false;
    }

    private static boolean names(String model, JsonNode root) {
        return ImageModelControl.CURRENT_CHECKPOINT.equals(model) || model.equals(WorkerClient.text(root.path("loaded")))
                || model.equals(WorkerClient.text(root.path("catalogId")));
    }

    /** {@inheritDoc} The worker holds one checkpoint, so this releases whatever it holds, and refuses during a job. */
    @Override
    public synchronized void unload(String model) throws ModelControlException {
        WorkerClient.Answer answer = post(WorkerClient.UNLOAD_PATH, EMPTY_OBJECT, settings.controlTimeout());
        if (!answer.ok()) {
            throw refused(answer);
        }
        JsonNode root = client.object(answer.body());
        if (root == null || !root.path("loaded").isNull()) {
            throw new ModelControlException("control-malformed");
        }
        holder = null;
        holderFile = null;
        unverified = false;
    }

    /** {@inheritDoc} See the class comment for how the worker's answer is read. */
    @Override
    public synchronized Residency status(String model) throws ModelControlException {
        String loaded = loaded();
        if (loaded == null && !unverified) {
            holder = null;
            holderFile = null;
            return Residency.GONE;
        }
        if (holder != null && !holder.equals(model)) {
            return Residency.GONE;
        }
        if (loaded == null) {
            return Residency.OTHER;
        }
        if (holder != null) {
            return loaded.equals(holderFile) ? Residency.LOADED : Residency.OTHER;
        }
        if (ImageModelControl.CURRENT_CHECKPOINT.equals(model) || model.equals(loaded)) {
            holder = model;
            holderFile = loaded;
            return Residency.LOADED;
        }
        return Residency.OTHER;
    }

    @Override
    public synchronized List<String> resident() throws ModelControlException {
        String loaded = loaded();
        return loaded == null ? List.of() : List.of(loaded);
    }

    @Override
    public void saveSlot(String model, int slot, String file) throws ModelControlException {
        throw new ModelControlException("control-unsupported");
    }

    @Override
    public void restoreSlot(String model, int slot, String file) throws ModelControlException {
        throw new ModelControlException("control-unsupported");
    }

    /** @return the {@code loaded} field of the worker's model list: a file name, or null for none */
    private String loaded() throws ModelControlException {
        WorkerClient.Answer answer;
        try {
            answer = client.get(WorkerClient.MODELS_PATH, MAX_RESPONSE_BYTES);
        } catch (WorkerClient.Unanswered unanswered) {
            throw failed(unanswered);
        }
        if (!answer.ok()) {
            throw refused(answer);
        }
        JsonNode root = client.object(answer.body());
        JsonNode loaded = root == null ? null : root.path("loaded");
        if (loaded == null || !(loaded.isNull() || loaded.isString())) {
            throw new ModelControlException("control-malformed");
        }
        return WorkerClient.text(loaded);
    }

    private WorkerClient.Answer post(String path, byte[] body, Duration deadline) throws ModelControlException {
        try {
            return client.post(path, body, MAX_RESPONSE_BYTES, deadline);
        } catch (WorkerClient.Unanswered unanswered) {
            throw failed(unanswered);
        }
    }

    private ModelControlException refused(WorkerClient.Answer answer) {
        return new ModelControlException("busy".equals(client.refusal(answer)) ? "control-busy" : "control-status");
    }

    private static ModelControlException failed(WorkerClient.Unanswered unanswered) {
        return new ModelControlException(switch (unanswered.reason()) {
            case TIMEOUT -> "control-timeout";
            case UNREACHABLE -> "control-unreachable";
            case TOO_LARGE -> "control-too-large";
            case INTERRUPTED -> "control-interrupted";
        });
    }
}
