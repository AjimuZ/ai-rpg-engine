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
 * Scripted Messages-API upstream for the second narration client's isolation proof (P20.1-B; Java 21, JDK only).
 *
 * <p>The pinned client documents {@code ANTHROPIC_BASE_URL} as its supported base-URL override, so the
 * probe points it at this server on an internal Compose network with no published ports. The client
 * then runs its own agent loop (tool dispatch, permission checks, hooks, extensions) unchanged; only
 * the upstream answers are scripted, and no request ever reaches a real inference service or account.
 *
 * <p>Every {@code POST .../v1/messages} request is answered by its body:
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
    /** Usage object of every scripted message; the client only needs well-formed counters. */
    static final String USAGE = "{\"input_tokens\":1,\"cache_creation_input_tokens\":0,"
            + "\"cache_read_input_tokens\":0,\"output_tokens\":1}";

    /** Prevent construction of this static fixture; accepts no inputs and does no I/O. */
    private MessagesProvider() {
    }

    /**
     * Return {@code "tools"} when {@code body} asks for the scripted tool calls, else {@code "text"}.
     *
     * @param body the request body decoded as UTF-8
     * @return the kind of answer the request receives
     */
    static String kind(String body) {
        return body.contains("scenario-tools") && !body.contains("\"type\":\"tool_result\"") ? "tools" : "text";
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
     * Build the streamed neutral text answer: one text block, then {@code end_turn}.
     *
     * @return the complete event stream
     */
    static String textStream() {
        return messageStart("msg_fixture_text")
                + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + jsonEscape(NEUTRAL_TEXT) + "\"}}")
                + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + messageEnd("end_turn");
    }

    /**
     * Build the streamed tool-call answer: one {@code tool_use} block per {@link #TOOL_CALLS} entry, with
     * its input sent as a single JSON delta, then {@code tool_use} as the stop reason so the client
     * answers every call instead of waiting.
     *
     * @return the complete event stream
     */
    static String toolStream() {
        StringBuilder out = new StringBuilder(messageStart("msg_fixture_tools"));
        for (int index = 0; index < TOOL_CALLS.length; index++) {
            String[] call = TOOL_CALLS[index];
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
     * @param kind {@code "tools"} or {@code "text"}, as returned by {@link #kind(String)}
     * @return the message JSON
     */
    static String message(String kind) {
        StringBuilder content = new StringBuilder();
        if ("tools".equals(kind)) {
            for (String[] call : TOOL_CALLS) {
                content.append(content.isEmpty() ? "" : ",").append("{\"type\":\"tool_use\",\"id\":\"")
                        .append(call[0]).append("\",\"name\":\"").append(call[1]).append("\",\"input\":")
                        .append(call[2]).append('}');
            }
        } else {
            content.append("{\"type\":\"text\",\"text\":\"").append(jsonEscape(NEUTRAL_TEXT)).append("\"}");
        }
        return "{\"id\":\"msg_fixture_" + kind + "\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"fixture\",\"content\":[" + content + "],\"stop_reason\":\""
                + ("tools".equals(kind) ? "tool_use" : "end_turn") + "\",\"stop_sequence\":null,\"usage\":"
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
            if (messages && text.contains("\"stream\":true")) {
                send(exchange, 200, "text/event-stream", "tools".equals(kind) ? toolStream() : textStream());
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
