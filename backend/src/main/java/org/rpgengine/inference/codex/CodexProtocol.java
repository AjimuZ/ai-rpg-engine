package org.rpgengine.inference.codex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.rpgengine.inference.EffectiveSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Request builders and result validators for the pinned app-server protocol (runtime
 * {@value #SUPPORTED_VERSION}), ported from the P01 reference clients
 * ({@code tools/runtime_probe/contract.py} and {@code session.py}) whose shapes are retained in
 * the neutral fixture {@code fixtures/0.164.0/neutral-session.json}. Every method either returns
 * validated, bounded values or throws {@link ProtocolException} with a fixed code; runtime
 * free text is never copied into a result or diagnostic.
 */
final class CodexProtocol {
    /** The only runtime version this adapter supports (P01.5 compatibility matrix). */
    static final String SUPPORTED_VERSION = "0.164.0";
    /** Terminal turn statuses of the pinned v2 schema. */
    static final Set<String> TERMINAL_STATUSES = Set.of("completed", "interrupted", "failed");
    /** Item types a tool-free narration turn may contain (P01.3, ADR 0013). */
    static final Set<String> ALLOWED_TURN_ITEMS = Set.of("userMessage", "agentMessage", "reasoning",
            "contextCompaction");
    /** Recognized 0.164.0 error variants; anything else becomes {@code unclassified}. */
    static final Set<String> ERROR_CODES = Set.of("contextWindowExceeded", "sessionBudgetExceeded",
            "usageLimitExceeded", "rateLimitExceeded", "flexUnavailable", "serverOverloaded", "cyberPolicy",
            "misalignmentPolicyViolation", "tooManyDenials", "internalServerError", "unauthorized", "badRequest",
            "threadRollbackFailed", "sandboxError", "other", "httpConnectionFailed",
            "responseStreamConnectionFailed", "responseStreamDisconnected", "responseTooManyFailedAttempts",
            "activeTurnNotSteerable");
    /** Final agent message bound (UTF-8 bytes). */
    static final int MAX_MESSAGE_TEXT = 65_536;
    static final int CATALOG_PAGE_SIZE = 32;
    static final int MAX_CATALOG_PAGES = 32;
    static final int MAX_MODELS = 256;
    /** Turns read during reconciliation; only the newest can still be in progress. */
    static final int RECONCILE_TURNS = 8;
    private static final Pattern VERSION_TOKEN = Pattern.compile("/(\\d+\\.\\d+\\.\\d+)(?![0-9A-Za-z.+-])");

    private CodexProtocol() {
    }

    /** Account readiness derived from {@code account/read}; never includes identity. */
    enum AccountState {
        /** A ChatGPT or API-key account is present (entitlement still unverified). */
        PRESENT,
        /** The runtime requires OpenAI sign-in. */
        SIGN_IN_REQUIRED,
        /** No usable supported authentication. */
        UNSUPPORTED
    }

    /**
     * Discovered model catalog: model identifier to supported effort names.
     *
     * @param efforts immutable map
     */
    record Catalog(Map<String, Set<String>> efforts) {
        /** @return whether {@code settings} names a cataloged model and one of its efforts */
        boolean supports(EffectiveSettings settings) {
            Set<String> supported = efforts.get(settings.model());
            return supported != null && supported.contains(settings.effort());
        }
    }

    /**
     * Turn state from {@code thread/turns/list}.
     *
     * @param turnId turn identifier
     * @param status {@code inProgress} or a terminal status
     */
    record TurnState(String turnId, String status) {
    }

    static ObjectNode initializeParams(JsonMapper json) {
        ObjectNode params = json.createObjectNode();
        ObjectNode client = params.putObject("clientInfo");
        client.put("name", "rpg_engine");
        client.put("title", "RPG engine");
        client.put("version", "0.1.0");
        params.putObject("capabilities").put("experimentalApi", false);
        return params;
    }

    /**
     * Validate the initialize result. The image pins the runtime; when the user agent exposes a
     * {@code /x.y.z} version token it must equal {@link #SUPPORTED_VERSION}.
     *
     * @throws ProtocolException {@code invalid-initialize} or {@code unsupported-runtime-version}
     */
    static void validateInitialize(JsonNode result) throws ProtocolException {
        if (result == null || !result.isObject()) {
            throw new ProtocolException("invalid-initialize");
        }
        String agent = text(result.get("userAgent"), 2048, "invalid-initialize");
        Matcher version = VERSION_TOKEN.matcher(agent);
        if (version.find() && !SUPPORTED_VERSION.equals(version.group(1))) {
            throw new ProtocolException("unsupported-runtime-version");
        }
    }

    static ObjectNode accountParams(JsonMapper json) {
        return json.createObjectNode().put("refreshToken", false);
    }

    /** @throws ProtocolException {@code invalid-account} for a malformed result */
    static AccountState accountState(JsonNode result) throws ProtocolException {
        if (result == null || !result.isObject() || !result.path("requiresOpenaiAuth").isBoolean()) {
            throw new ProtocolException("invalid-account");
        }
        JsonNode account = result.get("account");
        if (account == null || account.isNull()) {
            return result.get("requiresOpenaiAuth").booleanValue() ? AccountState.SIGN_IN_REQUIRED
                    : AccountState.UNSUPPORTED;
        }
        if (!account.isObject() || !account.path("type").isString()) {
            throw new ProtocolException("invalid-account");
        }
        String type = account.get("type").stringValue();
        return type.equals("chatgpt") || type.equals("apiKey") ? AccountState.PRESENT : AccountState.UNSUPPORTED;
    }

    static ObjectNode catalogParams(JsonMapper json, String cursor) {
        ObjectNode params = json.createObjectNode().put("limit", CATALOG_PAGE_SIZE).put("includeHidden", false);
        if (cursor != null) {
            params.put("cursor", cursor);
        }
        return params;
    }

    /**
     * Add one validated {@code model/list} page to {@code into}.
     *
     * @return the next cursor, or null on the last page
     * @throws ProtocolException {@code invalid-catalog}, {@code invalid-efforts} or
     *         {@code duplicate-or-excess-model}
     */
    static String addCatalogPage(JsonNode page, Map<String, Set<String>> into) throws ProtocolException {
        if (page == null || !page.isObject() || !page.path("data").isArray()) {
            throw new ProtocolException("invalid-catalog");
        }
        for (JsonNode model : page.get("data")) {
            if (!model.isObject()) {
                throw new ProtocolException("invalid-catalog");
            }
            String id = text(model.get("id"), 256, "invalid-catalog");
            text(model.get("model"), 256, "invalid-catalog");
            JsonNode efforts = model.get("supportedReasoningEfforts");
            if (efforts == null || !efforts.isArray() || efforts.size() < 1 || efforts.size() > 16) {
                throw new ProtocolException("invalid-efforts");
            }
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode effort : efforts) {
                if (!effort.isObject() || !names.add(text(effort.get("reasoningEffort"), 32, "invalid-efforts"))) {
                    throw new ProtocolException("invalid-efforts");
                }
            }
            if (!names.contains(text(model.get("defaultReasoningEffort"), 32, "invalid-efforts"))) {
                throw new ProtocolException("invalid-efforts");
            }
            if (into.containsKey(id) || into.size() >= MAX_MODELS) {
                throw new ProtocolException("duplicate-or-excess-model");
            }
            into.put(id, Set.copyOf(names));
        }
        JsonNode cursor = page.get("nextCursor");
        return cursor == null || cursor.isNull() ? null : text(cursor, 4096, "invalid-catalog");
    }

    /** {@code thread/start}: persistent, never-approve, read-only (docs/06 section 11). */
    static ObjectNode threadStartParams(JsonMapper json, String model, String cwd) {
        return json.createObjectNode().put("model", model).put("cwd", cwd).put("approvalPolicy", "never")
                .put("sandbox", "read-only").put("ephemeral", false);
    }

    /**
     * Validate a started thread: same model and working directory, effective safe policy, idle.
     *
     * @return the thread identifier
     * @throws ProtocolException {@code invalid-thread-start}, {@code invalid-thread-id},
     *         {@code thread-settings-mismatch} or {@code thread-not-idle}
     */
    static String validateThreadStart(JsonNode result, String model, String cwd) throws ProtocolException {
        if (result == null || !result.isObject() || !result.path("thread").isObject()) {
            throw new ProtocolException("invalid-thread-start");
        }
        String threadId = text(result.get("thread").get("id"), 128, "invalid-thread-id");
        if (!model.equals(result.path("model").asString(null)) || !cwd.equals(result.path("cwd").asString(null))) {
            throw new ProtocolException("thread-settings-mismatch");
        }
        validatePolicy(result);
        if (!"idle".equals(result.get("thread").path("status").path("type").asString(null))) {
            throw new ProtocolException("thread-not-idle");
        }
        return threadId;
    }

    /**
     * A requested policy is not evidence that the runtime applied it: check the effective one.
     *
     * @throws ProtocolException {@code thread-settings-mismatch}
     */
    static void validatePolicy(JsonNode result) throws ProtocolException {
        JsonNode sandbox = result.get("sandbox");
        if (!"never".equals(result.path("approvalPolicy").asString(null)) || sandbox == null || !sandbox.isObject()
                || !"readOnly".equals(sandbox.path("type").asString(null))
                || (sandbox.has("networkAccess") && !sandbox.get("networkAccess").isBoolean())
                || sandbox.path("networkAccess").asBoolean(false)) {
            throw new ProtocolException("thread-settings-mismatch");
        }
    }

    /** {@code thread/resume} without turns; history comes from {@code thread/turns/list}. */
    static ObjectNode resumeParams(JsonMapper json, String threadId) {
        return json.createObjectNode().put("threadId", threadId).put("excludeTurns", true);
    }

    /** @throws ProtocolException {@code invalid-thread-resume}, {@code resumed-thread-mismatch} or a policy failure */
    static void validateResume(JsonNode result, String threadId) throws ProtocolException {
        if (result == null || !result.isObject() || !result.path("thread").isObject()) {
            throw new ProtocolException("invalid-thread-resume");
        }
        if (!threadId.equals(result.get("thread").path("id").asString(null))) {
            throw new ProtocolException("resumed-thread-mismatch");
        }
        validatePolicy(result);
    }

    /**
     * Newest turns first. P01.2 verified that the runtime pages newest first by default; one
     * page suffices because only a thread's newest turn can still be in progress.
     */
    static ObjectNode turnsListParams(JsonMapper json, String threadId) {
        return json.createObjectNode().put("threadId", threadId).put("limit", RECONCILE_TURNS)
                .put("sortDirection", "desc");
    }

    /** @throws ProtocolException {@code invalid-turn-list}, {@code invalid-turn-status} or {@code invalid-turn-id} */
    static List<TurnState> parseTurns(JsonNode result) throws ProtocolException {
        if (result == null || !result.isObject() || !result.path("data").isArray()) {
            throw new ProtocolException("invalid-turn-list");
        }
        List<TurnState> turns = new ArrayList<>();
        for (JsonNode turn : result.get("data")) {
            if (!turn.isObject()) {
                throw new ProtocolException("invalid-turn-list");
            }
            String status = turn.path("status").asString(null);
            if (status == null || !(TERMINAL_STATUSES.contains(status) || status.equals("inProgress"))) {
                throw new ProtocolException("invalid-turn-status");
            }
            turns.add(new TurnState(text(turn.get("id"), 128, "invalid-turn-id"), status));
        }
        return turns;
    }

    /**
     * {@code turn/start} with every setting explicit: model and effort are sticky in the runtime,
     * so a turn that omitted them would inherit the previous task's values.
     */
    static ObjectNode turnStartParams(JsonMapper json, String threadId, String text, EffectiveSettings settings,
            JsonNode outputSchema) {
        ObjectNode params = json.createObjectNode().put("threadId", threadId);
        params.putArray("input").addObject().put("type", "text").put("text", text);
        params.put("model", settings.model()).put("effort", settings.effort()).put("approvalPolicy", "never");
        params.putObject("sandboxPolicy").put("type", "readOnly").put("networkAccess", false);
        params.set("outputSchema", outputSchema);
        return params;
    }

    /** @return the acknowledged turn identifier; @throws ProtocolException for a malformed acknowledgment */
    static String validateTurnStart(JsonNode result) throws ProtocolException {
        if (result == null || !result.isObject() || !result.path("turn").isObject()) {
            throw new ProtocolException("invalid-turn-start");
        }
        String status = result.get("turn").path("status").asString(null);
        if (status == null || !(status.equals("inProgress") || TERMINAL_STATUSES.contains(status))) {
            throw new ProtocolException("invalid-turn-status");
        }
        return text(result.get("turn").get("id"), 128, "invalid-turn-id");
    }

    static ObjectNode interruptParams(JsonMapper json, TurnKey key) {
        return json.createObjectNode().put("threadId", key.threadId()).put("turnId", key.turnId());
    }

    /** The acknowledgment is an empty object; it alone never proves termination. */
    static void validateInterrupt(JsonNode result) throws ProtocolException {
        if (result == null || !result.isObject() || !result.isEmpty()) {
            throw new ProtocolException("invalid-turn-interrupt");
        }
    }

    /**
     * Reduce a TurnError to a fixed code; the free-text message is discarded.
     *
     * @return a recognized variant, {@code unclassified}, or null when {@code error} is not an object
     */
    static String errorCode(JsonNode error) {
        if (error == null || !error.isObject()) {
            return null;
        }
        JsonNode info = error.get("codexErrorInfo");
        if (info != null && info.isObject() && info.size() == 1) {
            String name = info.propertyNames().iterator().next();
            return ERROR_CODES.contains(name) ? name : "unclassified";
        }
        return info != null && info.isString() && ERROR_CODES.contains(info.stringValue()) ? info.stringValue()
                : "unclassified";
    }

    /** @return a non-empty bounded string without control characters; else throws {@code code} */
    static String text(JsonNode value, int limit, String code) throws ProtocolException {
        if (value == null || !value.isString()) {
            throw new ProtocolException(code);
        }
        String text = value.stringValue();
        if (text.isEmpty() || text.length() > limit || text.chars().anyMatch(c -> c < 32)) {
            throw new ProtocolException(code);
        }
        return text;
    }

    /**
     * Applies the P01.2 {@code await_turn} rules to the events of one registered turn. Events are
     * already correlated by thread and turn identity; this class validates their content.
     * Not thread-safe; owned by the dispatcher.
     */
    static final class TurnTracker {
        /** Events read for one turn before giving up. */
        static final int MAX_TURN_EVENTS = 4096;

        private boolean started;
        private String errorCode;
        private String message;
        private int events;

        /**
         * Terminal outcome.
         *
         * @param status {@code completed}, {@code interrupted} or {@code failed}
         * @param text final agent message, only for {@code completed}
         * @param errorCode fixed code for failures, else null
         */
        record Outcome(String status, String text, String errorCode) {
        }

        /** @return whether the matching {@code turn/started} arrived */
        boolean started() {
            return started;
        }

        /**
         * Apply one correlated event.
         *
         * @return the terminal outcome on {@code turn/completed}, else null
         * @throws ProtocolException {@code model-rerouted}, {@code invalid-turn-item},
         *         {@code unexpected-turn-item}, {@code invalid-agent-message},
         *         {@code invalid-turn-status}, {@code missing-agent-message} or {@code turn-event-limit}
         */
        Outcome apply(RpcEvent event) throws ProtocolException {
            if (++events > MAX_TURN_EVENTS) {
                throw new ProtocolException("turn-event-limit");
            }
            JsonNode params = event.params();
            switch (event.method()) {
                case "turn/started" -> started = true;
                case "model/rerouted" -> throw new ProtocolException("model-rerouted");
                case "error" -> errorCode = errorCode(params.get("error"));
                case "item/started", "item/completed" -> {
                    JsonNode item = params.get("item");
                    if (item == null || !item.isObject()) {
                        throw new ProtocolException("invalid-turn-item");
                    }
                    String type = item.path("type").asString(null);
                    // Checked on start too, so a host action is reported before it finishes.
                    if (type == null || !ALLOWED_TURN_ITEMS.contains(type)) {
                        throw new ProtocolException("unexpected-turn-item");
                    }
                    if (event.method().equals("item/completed") && type.equals("agentMessage")) {
                        JsonNode text = item.get("text");
                        if (text == null || !text.isString()
                                || text.stringValue().getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_TEXT) {
                            throw new ProtocolException("invalid-agent-message");
                        }
                        message = text.stringValue();
                    }
                }
                case "turn/completed" -> {
                    JsonNode turn = params.get("turn");
                    String status = turn.path("status").asString(null);
                    if (status == null || !TERMINAL_STATUSES.contains(status)) {
                        throw new ProtocolException("invalid-turn-status");
                    }
                    if (status.equals("completed") && message == null) {
                        throw new ProtocolException("missing-agent-message");
                    }
                    String code = null;
                    if (status.equals("failed")) {
                        code = errorCode != null ? errorCode : errorCode(turn.get("error"));
                        code = code == null ? "unclassified" : code;
                    }
                    // Output is only meaningful after a confirmed successful turn.
                    return new Outcome(status, status.equals("completed") ? message : null, code);
                }
                default -> {
                    // Deltas, plan and usage updates carry nothing the engine accepts.
                }
            }
            return null;
        }
    }
}
