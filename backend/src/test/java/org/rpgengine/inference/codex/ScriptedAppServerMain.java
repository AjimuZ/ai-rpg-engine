package org.rpgengine.inference.codex;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

/**
 * Child-process form of {@link ScriptedAppServer} for stdio transport tests: one JSON message
 * per line on stdin/stdout. It writes three fixed stderr lines (an allowlisted module, an
 * unknown module and unstructured text, each containing a private marker) to exercise redaction.
 * Modes: {@code --linger} ignores the end of input (to test escalation), {@code --partial}
 * writes a frame without a newline and exits. The pseudo request {@code {"method":"env"}}
 * answers with the sorted names (never values) of the child's environment.
 */
final class ScriptedAppServerMain {
    private ScriptedAppServerMain() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream err = System.err;
        err.println("2026-10-28T00:00:00.000Z WARN codex_app_server: private marker redact-me-1");
        err.println("2026-10-28T00:00:00.000Z ERROR private_module::path: /home/owner/redact-me-2");
        err.println("plain redact-me-3");
        err.flush();
        OutputStream out = System.out;
        if (args.length > 0 && args[0].equals("--partial")) {
            out.write("{\"id\":1".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }
        ScriptedAppServer server = new ScriptedAppServer();
        ScriptedAppServer.Connection connection = server.new Connection(frame -> {
            synchronized (out) {
                try {
                    out.write(frame);
                    out.write('\n');
                    out.flush();
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }, () -> System.exit(0));
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals("{\"method\":\"env\"}")) {
                String names = String.join(",", new TreeSet<>(System.getenv().keySet()));
                connection.emit(ScriptedAppServer.JSON.createObjectNode().put("env", names));
                continue;
            }
            connection.handle(line.getBytes(StandardCharsets.UTF_8));
        }
        if (args.length > 0 && args[0].equals("--linger")) {
            Thread.sleep(60_000);
        }
    }
}
