import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.Executors;

/**
 * Deterministic model-provider stand-in for the P01.2 session proof (Java 21, JDK only).
 *
 * <p>The pinned runtime is configured with a custom provider whose base URL is
 * this server, on an internal Compose network with no published ports. That lets the
 * probe drive real thread and turn lifecycles without an account, without
 * service connectivity, and without sending any context to a real inference
 * service. The runtime still performs every protocol step itself; only the
 * upstream Responses stream is scripted.
 *
 * <p>The scenario is selected by the <em>last</em> scenario token in the
 * request body. The runtime resends the whole thread history on every turn,
 * so earlier turns' tokens are also present; the latest user message is the
 * one that appears last.
 * <ul>
 *   <li>{@code scenario-complete}: stream one assistant message carrying the
 *       neutral envelope from contract.NEUTRAL_OUTPUT, then complete.</li>
 *   <li>{@code scenario-hold}: open the stream and keep it open without
 *       completing, so the turn stays in progress until it is interrupted.</li>
 *   <li>{@code scenario-fail}: reject the request with HTTP 400, which the
 *       runtime reports as a failed turn (retries are disabled in the fixture).</li>
 *   <li>{@code scenario-tools} (P01.3 isolation check): the first request of
 *       the turn is answered with {@link #toolCallStream()}, which asks for
 *       host tools the runtime was never offered. Once the runtime reports the
 *       call results back (the request then contains a tool-call output item),
 *       the neutral envelope is streamed so the turn can finish and the probe
 *       can inspect what, if anything, was executed.</li>
 * </ul>
 *
 * <p>Every request is written to stdout as one line
 * {@code request <path> <scenario> <base64 body>} so the probe can read the
 * model, reasoning effort, and output schema the runtime actually sent. The
 * bodies contain only the neutral fixture text and the runtime's own
 * instructions, never account data: the fixture has no account.
 */
public final class ScriptedProvider {
    /** Request bodies above this bound are refused; the runtime's are far smaller. */
    static final int MAX_BODY = 8 * 1024 * 1024;
    /** A held stream ends on its own after this long, so a lost probe cannot leak it forever. */
    static final long HOLD_MILLIS = 120_000;
    /** Scenario tokens, in the order used for the lookup. */
    static final String[] SCENARIOS = {"scenario-complete", "scenario-hold", "scenario-fail",
        "scenario-tools"};
    /**
     * The neutral envelope, byte-for-byte equal in meaning to contract.NEUTRAL_OUTPUT.
     * test_session.py parses this constant and compares it with the Python value,
     * so the two cannot drift apart silently.
     */
    static final String NEUTRAL_OUTPUT = "{\"contractVersion\":1,\"taskId\":\"neutral-protocol-check\","
            + "\"baseRevision\":0,\"contextDigest\":"
            + "\"1ddb7a9dfedfe9904c7d01f62ec30bc911cca6c47ff3bb5f557536a7163904ab\","
            + "\"status\":\"answered\",\"body\":{\"text\":\"The marker is blue.\"}}";

    /** Prevent construction of this static fixture; accepts no inputs and does no I/O. */
    private ScriptedProvider() {
    }

    /**
     * Return the scenario whose token occurs last in {@code body}, or {@code "none"}.
     *
     * @param body the request body decoded as UTF-8
     * @return one of {@link #SCENARIOS} or {@code "none"} when no token occurs
     */
    static String scenario(String body) {
        String selected = "none";
        int position = -1;
        for (String candidate : SCENARIOS) {
            int index = body.lastIndexOf(candidate);
            if (index > position) {
                position = index;
                selected = candidate;
            }
        }
        return selected;
    }

    /**
     * Escape a string for use inside a JSON string literal.
     *
     * <p>Only the characters the fixture's fixed texts can contain need care:
     * quotes, backslashes, and control characters.
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
     * Build the complete Server-Sent Events stream for {@code scenario-complete}.
     *
     * <p>The event sequence is the minimum the runtime accepts for a finished
     * response: created, one completed assistant message item, completed with
     * zero usage. Each event has an {@code event:} line and a JSON {@code data:} line.
     *
     * @return the stream body
     */
    static String completedStream() {
        String message = "{\"type\":\"message\",\"role\":\"assistant\",\"id\":\"msg-fixture\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\""
                + jsonEscape(NEUTRAL_OUTPUT) + "\"}]}";
        return event("response.created",
                "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-fixture\"}}")
                + event("response.output_item.done",
                        "{\"type\":\"response.output_item.done\",\"item\":" + message + "}")
                + event("response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-fixture\","
                                + "\"usage\":{\"input_tokens\":0,\"input_tokens_details\":null,"
                                + "\"output_tokens\":0,\"output_tokens_details\":null,"
                                + "\"total_tokens\":0}}}");
    }

    /**
     * Build the adversarial stream for the first request of {@code scenario-tools}.
     *
     * <p>A compromised or confused model could emit tool calls whatever the
     * runtime advertised. Each call below targets a host capability the
     * isolated profile must not grant: a shell write and an unrelated read
     * through the legacy and unified shell tools, a file write through
     * {@code apply_patch}, the code-mode {@code exec} isolate, an MCP resource
     * read, a local image read and two image-generation function names. These
     * function calls test hostile tool dispatch, not the remote image backend
     * or its account entitlement. Every write aims at the same sentinel path
     * under the runtime workspace, so the probe can prove that none of them
     * ran by checking that the path still does not exist. All arguments are
     * synthetic constants; nothing here comes from a request.
     *
     * @return the stream body; ends with response.completed so the runtime
     *         answers the calls instead of waiting
     */
    static String toolCallStream() {
        String sentinel = "/var/lib/runtime/workspace/canonical-sentinel.json";
        String[] items = {
            functionCall("call-shell", "shell", "{\"command\":[\"sh\",\"-c\",\"echo x > " + sentinel + "\"]}"),
            functionCall("call-exec-command", "exec_command",
                    "{\"cmd\":\"cat /etc/passwd > " + sentinel + "\"}"),
            customCall("call-apply-patch", "apply_patch",
                    "*** Begin Patch\n*** Add File: " + sentinel + "\n+{}\n*** End Patch\n"),
            // JavaScript source: the escaped \\n become newlines inside the JS string.
            customCall("call-exec", "exec", "await tools.apply_patch(\"*** Begin Patch\\n*** Add File: "
                    + sentinel + "\\n+{}\\n*** End Patch\\n\");"),
            functionCall("call-mcp", "read_mcp_resource", "{\"server\":\"host\",\"uri\":\"file:///etc/passwd\"}"),
            functionCall("call-image", "view_image", "{\"path\":\"/etc/passwd\"}"),
            functionCall("call-image-generation", "image_generation", "{\"prompt\":\"A blue square\"}"),
            functionCall("call-image-gen", "image_gen", "{\"prompt\":\"A blue square\"}"),
        };
        StringBuilder out = new StringBuilder(event("response.created",
                "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-tools\"}}"));
        for (String item : items) {
            out.append(event("response.output_item.done",
                    "{\"type\":\"response.output_item.done\",\"item\":" + item + "}"));
        }
        return out.append(event("response.completed",
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-tools\","
                        + "\"usage\":{\"input_tokens\":0,\"input_tokens_details\":null,"
                        + "\"output_tokens\":0,\"output_tokens_details\":null,"
                        + "\"total_tokens\":0}}}")).toString();
    }

    /**
     * Return one Responses {@code function_call} item as single-line JSON.
     * {@code arguments} is the raw JSON argument text; it is escaped here. No I/O.
     */
    static String functionCall(String callId, String name, String arguments) {
        return "{\"type\":\"function_call\",\"id\":\"fc-" + callId + "\",\"call_id\":\"" + callId
                + "\",\"name\":\"" + name + "\",\"arguments\":\"" + jsonEscape(arguments) + "\"}";
    }

    /**
     * Return one Responses {@code custom_tool_call} item as single-line JSON.
     * {@code input} is the raw freeform tool input; it is escaped here. No I/O.
     */
    static String customCall(String callId, String name, String input) {
        return "{\"type\":\"custom_tool_call\",\"id\":\"ct-" + callId + "\",\"call_id\":\"" + callId
                + "\",\"name\":\"" + name + "\",\"input\":\"" + jsonEscape(input) + "\"}";
    }

    /**
     * Format the supplied event name and single-line JSON data into an SSE record.
     * Returns text without I/O; callers own the invariant that inputs have no raw newlines.
     */
    static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
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
     * Serve one request. Unknown paths and methods get 404 so that an
     * unexpected runtime call is visible in the log instead of half-working.
     *
     * @param exchange the request to read, log and answer; closed before returning
     * @throws IOException on oversized bodies, failed reads or failed response writes
     */
    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = readBody(exchange.getRequestBody());
            String path = exchange.getRequestURI().getPath();
            boolean responses = "POST".equals(exchange.getRequestMethod())
                    && path.endsWith("/responses");
            String scenario = responses
                    ? scenario(new String(body, StandardCharsets.UTF_8)) : "unhandled";
            // One line per request, flushed immediately so `compose logs` sees
            // it even if the container is stopped right afterwards.
            synchronized (System.out) {
                System.out.println("request " + path + " " + scenario + " "
                        + Base64.getEncoder().encodeToString(body));
                System.out.flush();
            }
            switch (scenario) {
                case "scenario-complete" -> send(exchange, 200, "text/event-stream", completedStream());
                case "scenario-hold" -> hold(exchange);
                // The runtime's reply to the adversarial calls carries their
                // output items; answering it normally lets the turn finish.
                case "scenario-tools" -> send(exchange, 200, "text/event-stream",
                        new String(body, StandardCharsets.UTF_8).contains("_call_output\"")
                                ? completedStream() : toolCallStream());
                case "scenario-fail" -> send(exchange, 400, "application/json",
                        "{\"error\":{\"message\":\"fixture rejected the request\","
                                + "\"type\":\"invalid_request_error\"}}");
                default -> send(exchange, 404, "application/json",
                        "{\"error\":{\"message\":\"not served by fixture\"}}");
            }
        }
    }

    /**
     * Send status, content type and UTF-8 text to the supplied exchange and close its body.
     * Returns no value; propagates IOException when headers or body cannot be written.
     */
    private static void send(HttpExchange exchange, int status, String type, String text)
            throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }

    /**
     * Start a stream and keep it open without completing the response.
     *
     * <p>SSE comment lines are written periodically so that a closed
     * connection (the runtime aborts the request when the turn is interrupted)
     * surfaces as a write failure and frees the handler thread promptly.
     *
     * @param exchange the open request whose response must remain in progress
     * @throws IOException if initial headers or the created event cannot be sent;
     *     subsequent disconnects are expected and reported by a fixed log marker
     */
    private static void hold(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        // Length 0 selects chunked transfer encoding: the stream has no known end.
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write(event("response.created",
                "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-held\"}}")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        long end = System.currentTimeMillis() + HOLD_MILLIS;
        try {
            while (System.currentTimeMillis() < end) {
                Thread.sleep(200);
                out.write(": held\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        } catch (IOException exc) {
            // Expected: the runtime closed the connection after an interruption.
            System.out.println("held-stream-closed");
            System.out.flush();
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
        // A held stream occupies its thread for a long time; virtual threads
        // keep later requests from queuing behind it.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        // The Compose health check waits for this file, so the runtime never
        // races the provider's startup.
        Files.writeString(ready, "ready\n");
    }
}
