package org.rpgengine.inference.codex;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

/**
 * Validated adapter settings, read once from {@code engine.inference.codex.*}. Values are
 * owner/deployment configuration, never task data; invalid values fail startup with a message
 * that names the setting but not its value.
 *
 * @param transport how to reach the runtime
 * @param socketPath real socket file for {@link Transport#UNIX}
 * @param command absolute executable and fixed arguments for {@link Transport#STDIO}
 * @param workingDirectory child working directory for {@link Transport#STDIO}
 * @param runtimeCwd working directory requested for threads, as seen by the runtime
 * @param queueCapacity bounded admission queue size (1–256)
 * @param requestTimeout per-request response deadline after the handshake
 * @param handshakeTimeout connect, initialize, account and catalog deadline (docs/06 section 10)
 * @param interruptTimeout wait for the terminal status after an interrupt
 * @param reconnectInitial first reconnect delay; doubles up to {@code reconnectMax}
 * @param reconnectMax longest reconnect delay
 * @param stdioGrace wait after closing child input and after each escalation step
 * @param maxSessions logical-session bindings kept; the least recently used is forgotten beyond it
 */
record CodexSettings(Transport transport, Path socketPath, List<String> command, Path workingDirectory,
        String runtimeCwd, int queueCapacity, Duration requestTimeout, Duration handshakeTimeout,
        Duration interruptTimeout, Duration reconnectInitial, Duration reconnectMax, Duration stdioGrace,
        int maxSessions) {
    /** Default runtime-side workspace of the packaged image (P01.2 fixture). */
    static final String DEFAULT_RUNTIME_CWD = "/var/lib/runtime/workspace";
    private static final String PREFIX = "engine.inference.codex.";

    /** Supported transports (docs/06 section 5). */
    enum Transport {
        /** Owned child process, one JSON message per line (developer profile). */
        STDIO,
        /** WebSocket over the packaged private Unix socket. */
        UNIX
    }

    /**
     * @param environment Spring environment
     * @return validated settings
     * @throws IllegalStateException naming the first invalid setting
     */
    static CodexSettings from(Environment environment) {
        String name = environment.getProperty(PREFIX + "transport", "").strip().toLowerCase(Locale.ROOT);
        Transport transport = switch (name) {
            case "stdio" -> Transport.STDIO;
            case "unix" -> Transport.UNIX;
            default -> throw new IllegalStateException(PREFIX + "transport must be disabled, stdio or unix");
        };
        Path socket = null;
        List<String> command = List.of();
        Path workingDirectory = null;
        String runtimeCwd = environment.getProperty(PREFIX + "runtime-cwd");
        if (transport == Transport.UNIX) {
            socket = absolute(environment.getProperty(PREFIX + "socket-path"), "socket-path");
        } else {
            String[] parts = environment.getProperty(PREFIX + "command", String[].class, new String[0]);
            command = Arrays.stream(parts).map(String::strip).toList();
            if (command.isEmpty() || command.stream().anyMatch(String::isEmpty)) {
                throw new IllegalStateException(PREFIX + "command must list an absolute executable and arguments");
            }
            absolute(command.getFirst(), "command");
            workingDirectory = absolute(environment.getProperty(PREFIX + "working-directory"), "working-directory");
            if (runtimeCwd == null) {
                runtimeCwd = workingDirectory.toString();
            }
        }
        if (runtimeCwd == null) {
            runtimeCwd = DEFAULT_RUNTIME_CWD;
        }
        absolute(runtimeCwd, "runtime-cwd");
        int capacity = environment.getProperty(PREFIX + "queue-capacity", Integer.class, 16);
        int sessions = environment.getProperty(PREFIX + "max-sessions", Integer.class, 256);
        if (capacity < 1 || capacity > 256 || sessions < 1 || sessions > 4096) {
            throw new IllegalStateException(PREFIX + "queue-capacity or max-sessions out of range");
        }
        return new CodexSettings(transport, socket, command, workingDirectory, runtimeCwd, capacity,
                duration(environment, "request-timeout", Duration.ofSeconds(30)),
                duration(environment, "handshake-timeout", Duration.ofSeconds(30)),
                duration(environment, "interrupt-timeout", Duration.ofSeconds(10)),
                duration(environment, "reconnect-initial", Duration.ofSeconds(1)),
                duration(environment, "reconnect-max", Duration.ofSeconds(30)),
                duration(environment, "stdio-grace", Duration.ofSeconds(2)), sessions);
    }

    private static Path absolute(String value, String key) {
        if (value == null || value.isBlank() || !Path.of(value.strip()).isAbsolute()) {
            throw new IllegalStateException(PREFIX + key + " must be an absolute path");
        }
        return Path.of(value.strip());
    }

    private static Duration duration(Environment environment, String key, Duration fallback) {
        String text = environment.getProperty(PREFIX + key);
        Duration value;
        try {
            value = text == null || text.isBlank() ? fallback : DurationStyle.detectAndParse(text.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PREFIX + key + " is not a duration", e);
        }
        if (value.isNegative() || value.isZero() || value.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException(PREFIX + key + " must be between 0 and 5 minutes");
        }
        return value;
    }

    /** @return a factory opening one new wire per process generation */
    CodexTaskAdapter.WireFactory wireFactory() {
        return transport == Transport.UNIX ? () -> new UnixSocketFrameWire(socketPath, handshakeTimeout)
                : () -> new StdioFrameWire(command, workingDirectory, stdioGrace);
    }
}
