package org.rpgengine.inference.admission;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Reads the free GPU memory from the host's GPU tool ({@code nvidia-smi}) through an injectable command runner.
 *
 * <p>Contract: {@link #freeBytes()} never throws. It returns empty when the tool is missing, exits non-zero,
 * exceeds the timeout, or prints anything that is not one non-negative whole number of MiB per line. On a host
 * with several GPUs it returns the free memory of the first listed GPU, because the engine's hardware profile
 * describes one card; a line that cannot be parsed makes the whole reading unknown rather than skipped.
 */
public final class HostGpuMemoryReading implements GpuMemoryReading {
    /** The tool call; {@code --nounits} makes each line a bare MiB count. */
    public static final List<String> COMMAND =
            List.of("nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final long BYTES_PER_MIB = 1024L * 1024L;
    private static final long MAX_MIB = Long.MAX_VALUE / BYTES_PER_MIB;

    /** Result of one command run; a timed-out run is reported by {@link #timedOut}, not by an exit code. */
    public record CommandResult(int exitCode, String output, boolean timedOut) {
        public CommandResult {
            output = output == null ? "" : output;
        }
    }

    /** Runs a command and returns what it printed. May throw {@link IOException} when the program cannot start. */
    @FunctionalInterface
    public interface CommandRunner {
        CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;
    }

    private final CommandRunner runner;
    private final Duration timeout;

    public HostGpuMemoryReading() {
        this(new ProcessCommandRunner(), DEFAULT_TIMEOUT);
    }

    public HostGpuMemoryReading(CommandRunner runner, Duration timeout) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public OptionalLong freeBytes() {
        try {
            CommandResult result = runner.run(COMMAND, timeout);
            if (result == null || result.timedOut() || result.exitCode() != 0) {
                return OptionalLong.empty();
            }
            return parse(result.output());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalLong.empty();
        } catch (IOException | RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    /** @return the first line's MiB converted to bytes, or empty when any line is not a non-negative integer */
    static OptionalLong parse(String output) {
        if (output == null || output.isBlank()) {
            return OptionalLong.empty();
        }
        OptionalLong first = OptionalLong.empty();
        for (String line : output.split("\\R")) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (!text.matches("[0-9]{1,18}")) {
                return OptionalLong.empty();
            }
            long mib = Long.parseLong(text);
            if (mib > MAX_MIB) {
                return OptionalLong.empty();
            }
            if (first.isEmpty()) {
                first = OptionalLong.of(mib * BYTES_PER_MIB);
            }
        }
        return first;
    }

    /** Runs the command as a child process, killing it on timeout; standard error is merged and discarded by the parser's rules. */
    static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try {
                // Read on a separate thread so a full pipe cannot stall the process past the timeout.
                var reader = new java.util.concurrent.atomic.AtomicReference<String>("");
                Thread drain = new Thread(() -> {
                    try {
                        reader.set(new String(process.getInputStream().readNBytes(65536),
                                java.nio.charset.StandardCharsets.UTF_8));
                        process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                    } catch (IOException ignored) {
                        // The stream closes when the process is killed.
                    }
                }, "gpu-memory-reading-drain");
                drain.setDaemon(true);
                drain.start();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    return new CommandResult(-1, "", true);
                }
                drain.join(1000);
                return new CommandResult(process.exitValue(), reader.get(), false);
            } finally {
                process.destroyForcibly();
            }
        }
    }
}
