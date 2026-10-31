package org.rpgengine.inference.local;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.ModelServerControl.Residency;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Request builder and strict parsers of the model server's HTTP chat API (docs/34 section 7). Stateless.
 * A body that breaks these shapes fails with a fixed code; server text never reaches a caller, and a
 * parsed reply is still untrusted output for the contract runner.
 *
 * <p>The shapes follow the llama.cpp server documentation and the probe tool
 * ({@code tools/container_packaging/model_probe.py}); no real server has answered them yet.
 */
final class LocalProtocol {
    /**
     * Build of the pinned server image in {@code compose.yaml}, recorded as provenance. It is the
     * packaged pin and not a value read from the server, so a server the owner replaced reports it too.
     */
    static final String SERVER_BUILD = "llama.cpp-b11960";
    static final String MODELS_PATH = "/v1/models";
    static final String CHAT_PATH = "/v1/chat/completions";
    /** Largest accepted response body; generous for one reply, small enough to protect the heap. */
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    /** Most models accepted in one catalog. */
    static final int MAX_CATALOG_ENTRIES = 4096;
    /** Effort name that switches thinking off (docs/34 section 7). */
    static final String NO_THINKING = "none";
    /** Router routes that list, load and unload models. */
    static final String CONTROL_MODELS_PATH = "/models";
    static final String CONTROL_LOAD_PATH = "/models/load";
    static final String CONTROL_UNLOAD_PATH = "/models/unload";
    private static final String CONTROL_MALFORMED = "control-malformed";

    private LocalProtocol() {
    }

    /** One earlier message of a conversation. */
    record Message(String role, String content) {
        /** @return the UTF-8 size of the content */
        long bytes() {
            return content.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /** A response that breaks the expected shape or that the engine refuses. */
    static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        Refused(String code) {
            super(code);
            this.code = code;
        }

        /** @return fixed lowercase task error code */
        String code() {
            return code;
        }
    }

    /**
     * Build one chat request with every setting explicit, so nothing is inherited from the server's
     * defaults or from an earlier task. No tool is ever offered.
     *
     * @param json mapper
     * @param effective model and effort of this task
     * @param history earlier messages of the conversation
     * @param instructions this task's text
     * @param schema JSON Schema object constraining the reply
     * @param maxTokens reply limit of this task's slot
     * @param settings sampling settings
     * @return the request body
     */
    static ObjectNode chatBody(JsonMapper json, EffectiveSettings effective, List<Message> history, String instructions,
            JsonNode schema, int maxTokens, LocalSettings settings) {
        ObjectNode body = json.createObjectNode().put("model", effective.model()).put("stream", false)
                .put("max_tokens", maxTokens).put("temperature", settings.temperature()).put("top_p", settings.topP())
                .put("top_k", settings.topK()).put("reasoning_effort", effective.effort());
        body.putObject("chat_template_kwargs").put("enable_thinking", !NO_THINKING.equals(effective.effort()));
        ArrayNode messages = body.putArray("messages");
        for (Message earlier : history) {
            messages.addObject().put("role", earlier.role()).put("content", earlier.content());
        }
        messages.addObject().put("role", "user").put("content", instructions);
        ObjectNode format = body.putObject("response_format").put("type", "json_schema");
        format.putObject("json_schema").put("name", "task_output").put("strict", true).set("schema", schema);
        return body;
    }

    /**
     * @param json mapper
     * @param body a catalog response
     * @return the model identifiers the server lists
     * @throws Refused with {@code catalog-malformed} for any other shape
     */
    static Set<String> parseModels(JsonMapper json, byte[] body) throws Refused {
        JsonNode root = parse(json, body, "catalog-malformed");
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() > MAX_CATALOG_ENTRIES) {
            throw new Refused("catalog-malformed");
        }
        Set<String> models = new HashSet<>();
        for (JsonNode model : data) {
            if (!model.path("id").isString()) {
                throw new Refused("catalog-malformed");
            }
            models.add(model.get("id").stringValue());
        }
        return Set.copyOf(models);
    }

    /**
     * @param json mapper
     * @param body a router's {@code GET /models} response
     * @return each listed model with where it stands; {@code failed} wins over the status value, and a
     *         value this engine does not name is {@link Residency#OTHER}, because it may hold memory
     * @throws Refused with {@code control-malformed} for any other shape
     */
    static Map<String, Residency> parseResidency(JsonMapper json, byte[] body) throws Refused {
        JsonNode data = parse(json, body, CONTROL_MALFORMED).path("data");
        if (!data.isArray() || data.size() > MAX_CATALOG_ENTRIES) {
            throw new Refused(CONTROL_MALFORMED);
        }
        Map<String, Residency> models = new HashMap<>();
        for (JsonNode model : data) {
            JsonNode value = model.path("status").path("value");
            if (!model.path("id").isString() || !value.isString()) {
                throw new Refused(CONTROL_MALFORMED);
            }
            boolean failed = model.path("status").path("failed").asBoolean(false);
            models.put(model.get("id").stringValue(), failed ? Residency.FAILED : switch (value.stringValue()) {
                case "loaded" -> Residency.LOADED;
                case "loading" -> Residency.LOADING;
                case "unloaded" -> Residency.GONE;
                default -> Residency.OTHER;
            });
        }
        return Map.copyOf(models);
    }

    /**
     * @param json mapper
     * @param body a 2xx answer to a load or unload request
     * @throws Refused with {@code control-malformed} unless it reports {@code success: true}
     */
    static void requireSuccess(JsonMapper json, byte[] body) throws Refused {
        if (!parse(json, body, CONTROL_MALFORMED).path("success").asBoolean(false)) {
            throw new Refused(CONTROL_MALFORMED);
        }
    }

    /**
     * @param json mapper
     * @param body a 2xx answer to a slot action; its counters are not used
     * @throws Refused with {@code control-malformed} unless it is a JSON object
     */
    static void requireObject(JsonMapper json, byte[] body) throws Refused {
        parse(json, body, CONTROL_MALFORMED);
    }

    /**
     * @param json mapper
     * @param body a chat response with status 200
     * @return the reply text of a completed answer, still untrusted
     * @throws Refused with {@code malformed-completion} for any other shape, {@code tool-call-refused}
     *         when the reply asks for a tool, {@code output-truncated} when the reply hit its token
     *         limit, {@code unexpected-finish} for any other ending and {@code empty-output} when
     *         it holds no text
     */
    static String parseCompletion(JsonMapper json, byte[] body) throws Refused {
        JsonNode choices = parse(json, body, "malformed-completion").path("choices");
        if (!choices.isArray() || choices.size() != 1 || !choices.get(0).path("message").isObject()) {
            throw new Refused("malformed-completion");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.get("message");
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            throw new Refused("tool-call-refused");
        }
        JsonNode finish = choice.path("finish_reason");
        if (!finish.isString()) {
            throw new Refused("malformed-completion");
        }
        if (finish.stringValue().equals("length")) {
            throw new Refused("output-truncated");
        }
        if (!finish.stringValue().equals("stop")) {
            throw new Refused("unexpected-finish");
        }
        JsonNode content = message.path("content");
        if (!content.isString() || content.stringValue().isBlank()) {
            throw new Refused("empty-output");
        }
        return content.stringValue();
    }

    private static JsonNode parse(JsonMapper json, byte[] body, String code) throws Refused {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException malformed) {
            throw new Refused(code);
        }
        if (root == null || !root.isObject()) {
            throw new Refused(code);
        }
        return root;
    }
}
