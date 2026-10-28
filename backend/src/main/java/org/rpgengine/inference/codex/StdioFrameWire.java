package org.rpgengine.inference.codex;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Owned-child stdio transport (developer profile, docs/06 section 4): one JSON message per
 * line. The child is launched from an argument array without a shell, from an absolute
 * executable path, with an allowlisted environment so engine secrets (for example the database
 * password) never reach the runtime. Stderr is drained by {@link StderrDiagnostics}.
 *
 * <p>Shutdown follows the measured P01.4 sequence: close input, wait {@code grace}, terminate,
 * wait, kill, wait; a child that survives is reported {@code unreaped} instead of raising.
 */
final class StdioFrameWire implements FrameWire {
    /** Environment variables a runtime child may inherit; everything else is dropped. */
    static final Set<String> INHERITED_ENVIRONMENT = Set.of(
            "PATH", "HOME", "CODEX_HOME", "LANG", "LC_ALL", "TMPDIR", "USER", "TZ");

    private final Process process;
    private final InputStream stdout;
    private final OutputStream stdin;
    private final Thread stderrDrain;
    private final StderrDiagnostics stderr = new StderrDiagnostics();
    private final Duration grace;
    private WireException failure;
    private ShutdownRecord shutdown;

    /**
     * Launch the child and start draining its stderr.
     *
     * @param command absolute executable followed by fixed arguments
     * @param workingDirectory existing directory for the child
     * @param grace wait after closing input and after each escalation step
     * @throws WireException {@code runtime-launch} when the command is not an absolute
     *         executable or the process cannot start
     */
    StdioFrameWire(List<String> command, Path workingDirectory, Duration grace) throws WireException {
        if (command.isEmpty() || !Path.of(command.getFirst()).isAbsolute()
                || !Files.isExecutable(Path.of(command.getFirst()))) {
            throw new WireException("runtime-launch");
        }
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command)).directory(workingDirectory.toFile());
        Map<String, String> environment = builder.environment();
        environment.keySet().retainAll(INHERITED_ENVIRONMENT);
        try {
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            throw new WireException("runtime-launch");
        }
        this.grace = grace;
        stdout = new BufferedInputStream(process.getInputStream());
        stdin = process.getOutputStream();
        stderrDrain = Thread.ofPlatform().daemon().name("codex-stderr").start(() -> stderr.drain(process.getErrorStream()));
    }

    /** Read one bounded line; a partial final line or an oversized one is a framing failure. */
    @Override
    public byte[] read() throws WireException {
        synchronized (stdout) {
            if (failure != null) {
                throw failure;
            }
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            try {
                while (true) {
                    int b = stdout.read();
                    if (b < 0) {
                        throw fail(line.size() == 0 ? "transport-eof" : "invalid-stdio-frame");
                    }
                    if (b == '\n') {
                        return line.toByteArray();
                    }
                    if (line.size() >= MAX_FRAME) {
                        throw fail("invalid-stdio-frame");
                    }
                    line.write(b);
                }
            } catch (IOException e) {
                if (e instanceof WireException wire) {
                    throw wire;
                }
                throw fail("transport-read");
            }
        }
    }

    private WireException fail(String code) {
        failure = new WireException(code);
        return failure;
    }

    /** Write one line and flush. */
    @Override
    public void write(byte[] frame) throws WireException {
        if (frame.length > MAX_FRAME) {
            throw new WireException("invalid-frame");
        }
        for (byte b : frame) {
            if (b == '\n' || b == '\r') {
                throw new WireException("invalid-frame");
            }
        }
        try {
            stdin.write(frame);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            throw new WireException("transport-write");
        }
    }

    /** Shut the owned child down in bounded steps; see the class comment. */
    @Override
    public synchronized ShutdownRecord close() {
        if (shutdown != null) {
            return shutdown;
        }
        long started = System.nanoTime();
        String stage = process.isAlive() ? null : "exited";
        try {
            stdin.close();
        } catch (IOException e) {
            // A broken pipe only means the child already stopped reading.
        }
        if (stage == null && waitFor(grace)) {
            stage = "input-closed";
        }
        if (stage == null) {
            process.destroy();
            if (waitFor(grace)) {
                stage = "terminated";
            }
        }
        if (stage == null) {
            process.destroyForcibly();
            stage = waitFor(grace) ? "killed" : "unreaped";
        }
        try {
            stderrDrain.join(1000);
            process.getInputStream().close();
            process.getErrorStream().close();
        } catch (IOException e) {
            // Nothing else to release.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Integer exit = process.isAlive() ? null : process.exitValue();
        shutdown = new ShutdownRecord(stage, exit, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return shutdown;
    }

    private boolean waitFor(Duration duration) {
        try {
            return process.waitFor(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    /** @return redacted stderr counters; complete after {@link #close()} */
    StderrDiagnostics.Summary stderrSummary() {
        return stderr.summary();
    }
}
