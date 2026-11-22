import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;

/**
 * Scripted Messages-API upstream for the second narration client's isolation proof (P20.1-B) and its
 * launch-profile, signal and retention probe (P20.1-C); Java 21, JDK only.
 *
 * <p>The pinned client documents {@code ANTHROPIC_BASE_URL} as its supported base-URL override, so the
 * probe points it at this server on an internal Compose network with no published ports. The client
 * then runs its own agent loop (tool dispatch, permission checks, hooks, extensions) unchanged; only
 * the upstream answers are scripted, and no request ever reaches a real inference service or account.
 *
 * <p>Every {@code POST .../v1/messages} request is answered by its body, as {@link #kind(String)}
 * decides. The P20.1-C scenarios refuse a request with a scripted API error ({@link #ERROR_SCENARIOS},
 * or {@link #UNLISTED_MODEL} as the model), add usage-limit headers ({@link #limitHeaders(String)}),
 * answer with a {@code StructuredOutput} call, or stop at {@code max_tokens}. The P20.1-B rules:
 * <ul>
 *   <li>a body that carries a {@code tool_result} block gets the neutral text answer, so a turn that
 *       answered the scripted tool calls can finish;</li>
 *   <li>otherwise a body containing {@code scenario-tools} gets {@link #TOOL_CALLS}: host tools that a
 *       compromised model could request whatever the client offered;</li>
 *   <li>every other body (including {@code scenario-complete} and any auxiliary request the client
 *       makes on its own) gets the neutral text answer.</li>
 * </ul>
 * Streaming requests ({@code "stream":true}) get Server-Sent Events, the others one JSON message.
 * {@code .../count_tokens} gets a fixed count; every other path gets 404, so an unexpected call is
 * visible in the log instead of half-working.
 *
 * <p>Each request is written to stdout as one line
 * {@code request <method> <path> <kind> <base64 headers JSON> <base64 body>}. The headers are logged
 * because credential sentinels would travel there; the probe has no real credential, so the log holds
 * only fixture values.
 */
public final class MessagesProvider {
    /** Request bodies above this bound are refused; the client's are far smaller. */
    static final int MAX_BODY = 16 * 1024 * 1024;
    /** The only text this upstream ever answers with. test_claude_isolation.py expects it as the result. */
    static final String NEUTRAL_TEXT = "The marker is blue.";
    /** The workspace file every scripted write targets; the probe requires it to stay absent. */
    static final String CANONICAL_SENTINEL = "/var/lib/runtime/workspace/canonical-sentinel.json";
    /**
     * Scripted host tool calls as {@code {id, name, input JSON}}. Each one would leave a trace the probe
     * can see: the Bash call writes a marker and the canonical sentinel, the Write call the sentinel,
     * the Read call would copy a subscription credential sentinel into the next request, and the
     * WebFetch call would reach this server's {@code /webfetch-probe} path. Inputs are constants and
     * contain no sentinel text, so their presence in requests or output is never mistaken for a leak.
     */
    static final String[][] TOOL_CALLS = {
        {"toolu_probe_bash", "Bash", "{\"command\":\"touch /tmp/markers/tool-bash && echo x > "
            + CANONICAL_SENTINEL + "\",\"description\":\"Probe a host command\"}"},
        {"toolu_probe_write", "Write", "{\"file_path\":\"" + CANONICAL_SENTINEL + "\",\"content\":\"{}\"}"},
        {"toolu_probe_read", "Read", "{\"file_path\":\"/var/lib/runtime/home/.claude/.credentials.json\"}"},
        {"toolu_probe_fetch", "WebFetch",
            "{\"url\":\"http://provider:8080/webfetch-probe\",\"prompt\":\"Summarize the page.\"}"},
    };
    /**
     * Error scenarios of the launch-profile probe (P20.1-C) as {@code {token, HTTP status, error type,
     * message}}. A body containing the token is refused with that status and a Messages-API error body,
     * so test_claude_profile.py can record how the pinned client reports each one. The billing message
     * is the exact text the client matches to classify a credit stop.
     */
    static final String[][] ERROR_SCENARIOS = {
        {"scenario-auth", "401", "authentication_error", "invalid x-api-key"},
        {"scenario-billing", "400", "invalid_request_error", "Your credit balance is too low to access the "
            + "Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."},
        {"scenario-invalid", "400", "invalid_request_error", "fixture request is not valid"},
        {"scenario-toolong", "400", "invalid_request_error", "prompt is too long: 250000 tokens > 200000 maximum"},
        {"scenario-overloaded", "529", "overloaded_error", "Overloaded"},
        {"scenario-server", "500", "api_error", "Internal server error"},
        {"scenario-ratelimit", "429", "rate_limit_error", "This request would exceed your account's rate limit."},
    };
    /** The fixed reset time (epoch seconds, 1 January 2100) of every scripted usage-limit header. */
    static final long RESET_AT = 4102444800L;
    /** The one model identifier this upstream refuses as unknown; every other identifier is served. */
    static final String UNLISTED_MODEL = "claude-unlisted-fixture";
    /** The structured answer of {@code scenario-schema}; it satisfies the probe's neutral-check schema. */
    static final String STRUCTURED_INPUT = "{\"marker\":\"blue\"}";
    /** Usage object of every scripted message; the client only needs well-formed counters. */
    static final String USAGE = "{\"input_tokens\":1,\"cache_creation_input_tokens\":0,"
            + "\"cache_read_input_tokens\":0,\"output_tokens\":1}";

    /** Prevent construction of this static fixture; accepts no inputs and does no I/O. */
    private MessagesProvider() {
    }

    /**
     * Return the kind of answer a Messages request receives, without I/O. In order of precedence:
     * {@code "error-model"} when the body names {@link #UNLISTED_MODEL}; {@code "error-<name>"} when it
     * holds an {@link #ERROR_SCENARIOS} token; {@code "tools"} for {@code scenario-tools} and
     * {@code "schema"} for {@code scenario-schema} when the client offered the {@code StructuredOutput}
     * tool, both only until the body carries a {@code tool_result}; {@code "maxtokens"} and
     * {@code "overage"} for their tokens; {@code "text"} otherwise.
     *
     * @param body the request body decoded as UTF-8
     * @return the kind of answer the request receives; never null and never containing a space
     */
    static String kind(String body) {
        if (body.contains("\"model\":\"" + UNLISTED_MODEL + "\"")) {
            return "error-model";
        }
        for (String[] scenario : ERROR_SCENARIOS) {
            if (body.contains(scenario[0])) {
                return "error-" + scenario[0].substring("scenario-".length());
            }
        }
        boolean answered = body.contains("\"type\":\"tool_result\"");
        if (body.contains("scenario-tools") && !answered) {
            return "tools";
        }
        if (body.contains("scenario-schema") && !answered && body.contains("\"name\":\"StructuredOutput\"")) {
            return "schema";
        }
        if (body.contains("scenario-maxtokens")) {
            return "maxtokens";
        }
        return body.contains("scenario-overage") ? "overage" : "text";
    }

    /**
     * Return the scripted error of an {@code "error-..."} kind as {@code {status, type, message}}, or
     * null for any other kind. No I/O.
     *
     * @param kind a value returned by {@link #kind(String)}
     * @return the HTTP status, Messages-API error type and message, or null
     */
    static String[] error(String kind) {
        if ("error-model".equals(kind)) {
            return new String[] {"404", "not_found_error", "model: " + UNLISTED_MODEL};
        }
        for (String[] scenario : ERROR_SCENARIOS) {
            if (kind.equals("error-" + scenario[0].substring("scenario-".length()))) {
                return new String[] {scenario[1], scenario[2], scenario[3]};
            }
        }
        return null;
    }

    /**
     * Return the usage-limit headers an answer of this kind carries, as alternating names and values.
     * A refused request ({@code "error-ratelimit"}) reports the five-hour limit as rejected with paid
     * overage rejected too; an {@code "overage"} answer reports the same limit rejected while paid
     * overage is allowed and in use, as the client's own overage scenarios do. Other kinds carry none.
     *
     * @param kind a value returned by {@link #kind(String)}
     * @return header names and values; empty when the kind carries no limit headers
     */
    static String[] limitHeaders(String kind) {
        String reset = Long.toString(RESET_AT);
        return switch (kind) {
            case "error-ratelimit" -> new String[] {
                "anthropic-ratelimit-unified-status", "rejected",
                "anthropic-ratelimit-unified-reset", reset,
                "anthropic-ratelimit-unified-representative-claim", "five_hour",
                "anthropic-ratelimit-unified-overage-status", "rejected"};
            case "overage" -> new String[] {
                "anthropic-ratelimit-unified-status", "rejected",
                "anthropic-ratelimit-unified-reset", reset,
                "anthropic-ratelimit-unified-representative-claim", "five_hour",
                "anthropic-ratelimit-unified-overage-status", "allowed",
                "anthropic-ratelimit-unified-overage-reset", reset,
                "anthropic-ratelimit-unified-overage-in-use", "true"};
            default -> new String[0];
        };
    }

    /**
     * Escape a string for use inside a JSON string literal: quotes, backslashes and control characters.
     *
     * @param value raw text
     * @return the escaped text without surrounding quotes
     */
    static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Format one Server-Sent Events record. Returns text without I/O; callers guarantee that
     * {@code data} is single-line JSON.
     */
    static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }

    /** Return the {@code message_start} event of a scripted message with the given ID. No I/O. */
    static String messageStart(String id) {
        return event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"" + id
                + "\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"fixture\",\"content\":[],"
                + "\"stop_reason\":null,\"stop_sequence\":null,\"usage\":" + USAGE + "}}");
    }

    /** Return the closing {@code message_delta} and {@code message_stop} events. No I/O. */
    static String messageEnd(String stopReason) {
        return event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason
                + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}")
                + event("message_stop", "{\"type\":\"message_stop\"}");
    }

    /**
     * Return the scripted tool calls of a {@code "tools"} or {@code "schema"} answer, else an empty array.
     * The schema answer is one {@code StructuredOutput} call holding {@link #STRUCTURED_INPUT}. No I/O.
     */
    static String[][] calls(String kind) {
        return switch (kind) {
            case "tools" -> TOOL_CALLS;
            case "schema" -> new String[][] {{"toolu_schema_answer", "StructuredOutput", STRUCTURED_INPUT}};
            default -> new String[0][];
        };
    }

    /**
     * Build the streamed neutral text answer: one text block, then {@code end_turn}, or
     * {@code max_tokens} for the {@code "maxtokens"} kind.
     *
     * @param kind a value returned by {@link #kind(String)} that has no tool calls
     * @return the complete event stream
     */
    static String textStream(String kind) {
        return messageStart("msg_fixture_text")
                + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + jsonEscape(NEUTRAL_TEXT) + "\"}}")
                + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + messageEnd("maxtokens".equals(kind) ? "max_tokens" : "end_turn");
    }

    /**
     * Build the streamed tool-call answer: one {@code tool_use} block per call of {@link #calls(String)},
     * with its input sent as a single JSON delta, then {@code tool_use} as the stop reason so the client
     * answers every call instead of waiting.
     *
     * @param kind {@code "tools"} or {@code "schema"}
     * @return the complete event stream
     */
    static String toolStream(String kind) {
        String[][] calls = calls(kind);
        StringBuilder out = new StringBuilder(messageStart("msg_fixture_" + kind));
        for (int index = 0; index < calls.length; index++) {
            String[] call = calls[index];
            out.append(event("content_block_start", "{\"type\":\"content_block_start\",\"index\":" + index
                    + ",\"content_block\":{\"type\":\"tool_use\",\"id\":\"" + call[0] + "\",\"name\":\""
                    + call[1] + "\",\"input\":{}}}"));
            out.append(event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index
                    + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"" + jsonEscape(call[2])
                    + "\"}}"));
            out.append(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":" + index + "}"));
        }
        return out.append(messageEnd("tool_use")).toString();
    }

    /**
     * Build the same answer as one non-streamed JSON message.
     *
     * @param kind a value returned by {@link #kind(String)} that is not an error
     * @return the message JSON
     */
    static String message(String kind) {
        StringBuilder content = new StringBuilder();
        String[][] calls = calls(kind);
        if (calls.length > 0) {
            for (String[] call : calls) {
                content.append(content.isEmpty() ? "" : ",").append("{\"type\":\"tool_use\",\"id\":\"")
                        .append(call[0]).append("\",\"name\":\"").append(call[1]).append("\",\"input\":")
                        .append(call[2]).append('}');
            }
        } else {
            content.append("{\"type\":\"text\",\"text\":\"").append(jsonEscape(NEUTRAL_TEXT)).append("\"}");
        }
        return "{\"id\":\"msg_fixture_" + kind + "\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"fixture\",\"content\":[" + content + "],\"stop_reason\":\""
                + (calls.length > 0 ? "tool_use" : "maxtokens".equals(kind) ? "max_tokens" : "end_turn")
                + "\",\"stop_sequence\":null,\"usage\":"
                + USAGE + "}";
    }

    /**
     * Return the request headers as one JSON object of lower-case names to value arrays. No I/O.
     *
     * @param headers the exchange's request headers
     * @return single-line JSON, names sorted
     */
    static String headersJson(Map<String, List<String>> headers) {
        Map<String, List<String>> sorted = new TreeMap<>();
        headers.forEach((name, values) -> sorted.computeIfAbsent(name.toLowerCase(Locale.ROOT),
                key -> new ArrayList<>()).addAll(values));
        StringBuilder out = new StringBuilder("{");
        sorted.forEach((name, values) -> {
            out.append(out.length() > 1 ? "," : "").append('"').append(jsonEscape(name)).append("\":[");
            for (int index = 0; index < values.size(); index++) {
                out.append(index > 0 ? "," : "").append('"').append(jsonEscape(values.get(index))).append('"');
            }
            out.append(']');
        });
        return out.append('}').toString();
    }

    /**
     * Read the request body with a hard size bound.
     *
     * @param in the exchange's request stream
     * @return the complete bounded body
     * @throws IOException when the body exceeds {@link #MAX_BODY} or the read fails
     */
    private static byte[] readBody(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            throw new IOException("request-too-large");
        }
        return body;
    }

    /**
     * Log and answer one request.
     *
     * @param exchange the request to read, log and answer; closed before returning
     * @throws IOException on oversized bodies, failed reads or failed response writes
     */
    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = readBody(exchange.getRequestBody());
            String text = new String(body, StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            boolean messages = "POST".equals(exchange.getRequestMethod()) && path.endsWith("/v1/messages");
            String kind = messages ? kind(text) : path.endsWith("/count_tokens") ? "count" : "unhandled";
            // One flushed line per request, so `compose logs` sees it even when the
            // fixture is stopped right afterwards.
            synchronized (System.out) {
                System.out.println("request " + exchange.getRequestMethod() + " " + path + " " + kind + " "
                        + Base64.getEncoder().encodeToString(
                                headersJson(exchange.getRequestHeaders()).getBytes(StandardCharsets.UTF_8))
                        + " " + Base64.getEncoder().encodeToString(body));
                System.out.flush();
            }
            String[] limits = limitHeaders(kind);
            for (int index = 0; index < limits.length; index += 2) {
                exchange.getResponseHeaders().set(limits[index], limits[index + 1]);
            }
            String[] error = messages ? error(kind) : null;
            if (error != null) {
                send(exchange, Integer.parseInt(error[0]), "application/json",
                        "{\"type\":\"error\",\"error\":{\"type\":\"" + error[1] + "\",\"message\":\""
                                + jsonEscape(error[2]) + "\"},\"request_id\":\"req_fixture\"}");
            } else if (messages && text.contains("\"stream\":true")) {
                send(exchange, 200, "text/event-stream",
                        calls(kind).length > 0 ? toolStream(kind) : textStream(kind));
            } else if (messages) {
                send(exchange, 200, "application/json", message(kind));
            } else if ("count".equals(kind)) {
                send(exchange, 200, "application/json", "{\"input_tokens\":1}");
            } else {
                send(exchange, 404, "application/json",
                        "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\","
                                + "\"message\":\"not served by fixture\"}}");
            }
        }
    }

    /**
     * Send status, content type and UTF-8 text to the supplied exchange and close its body.
     * Returns no value; propagates IOException when headers or body cannot be written.
     */
    private static void send(HttpExchange exchange, int status, String type, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("request-id", "req_fixture");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }

    /**
     * Start the server on the given port and mark readiness with a file.
     *
     * @param args {@code [port] [ready-file]}; defaults 8080 and /tmp/ready
     * @throws IOException when the port cannot be bound or readiness cannot be written
     * @throws IllegalArgumentException when the supplied port is invalid
     */
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path ready = Path.of(args.length > 1 ? args[1] : "/tmp/ready");
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 16);
        server.createContext("/", exchange -> {
            try {
                handle(exchange);
            } catch (IOException exc) {
                // The client went away mid-request; nothing to report beyond a code.
                System.out.println("request-io-error");
                System.out.flush();
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        // The Compose health check waits for this file, so the client never races startup.
        Files.writeString(ready, "ready\n");
    }
}
