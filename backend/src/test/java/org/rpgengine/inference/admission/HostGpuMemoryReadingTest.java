package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.admission.HostGpuMemoryReading.CommandResult;
import org.rpgengine.inference.admission.HostGpuMemoryReading.CommandRunner;

/** The host GPU reading: parsing and every failure of the command, with no GPU and no real tool. */
class HostGpuMemoryReadingTest {
    private static final long MIB = 1024L * 1024L;
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private static HostGpuMemoryReading reading(CommandRunner runner) {
        return new HostGpuMemoryReading(runner, TIMEOUT);
    }

    private static HostGpuMemoryReading printing(String output) {
        return reading((command, timeout) -> new CommandResult(0, output, false));
    }

    @Test
    void readsOneGpuInMibAsBytes() {
        assertThat(printing("11234\n").freeBytes()).isEqualTo(OptionalLong.of(11234L * MIB));
        assertThat(printing(" 8 \r\n").freeBytes()).isEqualTo(OptionalLong.of(8L * MIB));
        assertThat(printing("0").freeBytes()).isEqualTo(OptionalLong.of(0));
    }

    @Test
    void usesTheFirstGpuWhenSeveralAreListed() {
        assertThat(printing("2000\n9000\n").freeBytes()).isEqualTo(OptionalLong.of(2000L * MIB));
    }

    @Test
    void passesTheQueryCommandAndTimeoutToTheRunner() {
        var seen = new Object[2];
        reading((command, timeout) -> {
            seen[0] = command;
            seen[1] = timeout;
            return new CommandResult(0, "1", false);
        }).freeBytes();
        assertThat(seen[0]).isEqualTo(HostGpuMemoryReading.COMMAND);
        assertThat(seen[1]).isEqualTo(TIMEOUT);
    }

    @Test
    void unparsableOutputIsUnknown() {
        for (String output : new String[] {"", "  \n", "N/A", "garbage", "12.5", "-5", "1e3", "12 MiB",
                "100\nN/A", "99999999999999999999", "9999999999999999"}) {
            assertThat(printing(output).freeBytes()).as(output).isEqualTo(OptionalLong.empty());
        }
        assertThat(printing(null).freeBytes()).isEqualTo(OptionalLong.empty());
    }

    @Test
    void missingToolIsUnknown() {
        assertThat(reading((c, t) -> { throw new IOException("Cannot run program nvidia-smi"); }).freeBytes())
                .isEqualTo(OptionalLong.empty());
    }

    @Test
    void failingToolIsUnknown() {
        assertThat(reading((c, t) -> new CommandResult(9, "1000", false)).freeBytes())
                .isEqualTo(OptionalLong.empty());
    }

    @Test
    void timeoutIsUnknown() {
        assertThat(reading((c, t) -> new CommandResult(0, "1000", true)).freeBytes())
                .isEqualTo(OptionalLong.empty());
    }

    @Test
    void unexpectedRunnerFailureIsUnknown() {
        assertThat(reading((c, t) -> { throw new IllegalStateException("boom"); }).freeBytes())
                .isEqualTo(OptionalLong.empty());
        assertThat(reading((c, t) -> null).freeBytes()).isEqualTo(OptionalLong.empty());
    }

    @Test
    void interruptionIsUnknownAndKeepsTheInterruptFlag() {
        try {
            assertThat(reading((c, t) -> { throw new InterruptedException(); }).freeBytes())
                    .isEqualTo(OptionalLong.empty());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void processRunnerReportsAMissingProgramAsIoException() {
        var runner = new HostGpuMemoryReading.ProcessCommandRunner();
        assertThatThrownBy(() -> runner.run(List.of("no-such-gpu-tool-for-tests"), TIMEOUT))
                .isInstanceOf(IOException.class);
    }

    @Test
    void constructorsRejectNulls() {
        assertThatThrownBy(() -> new HostGpuMemoryReading(null, TIMEOUT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HostGpuMemoryReading((c, t) -> null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
