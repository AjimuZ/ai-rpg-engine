package org.rpgengine.inference.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rpgengine.inference.EffectiveSettings;
import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.TaskResult;
import tools.jackson.databind.JsonNode;

/**
 * The owned-child stdio transport against a real child process (the scripted server run by the
 * current JVM): end-to-end tasks, environment allowlisting, stderr redaction, framing failures
 * and the bounded shutdown sequence.
 */
class StdioFrameWireTest {
    @TempDir
    Path directory;

    static List<String> command(String... args) {
        String java = ProcessHandle.current().info().command().orElseThrow();
        List<String> command = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"),
                ScriptedAppServerMain.class.getName()));
        command.addAll(Arrays.asList(args));
        return command;
    }

    private CodexSettings settings(List<String> command) {
        CodexSettings base = CodexTaskAdapterTest.settings(2);
        return new CodexSettings(CodexSettings.Transport.STDIO, null, command, directory, directory.toString(),
                base.queueCapacity(), Duration.ofSeconds(20), Duration.ofSeconds(20), base.interruptTimeout(),
                base.reconnectInitial(), base.reconnectMax(), Duration.ofSeconds(2), base.maxSessions());
    }

    @Test
    void adapterCompletesATaskOverAnOwnedChildAndClosesItsInput() throws Exception {
        CodexSettings settings = settings(command());
        CodexTaskAdapter adapter = new CodexTaskAdapter(settings,
                slot -> Optional.of(new EffectiveSettings("model-a", "medium")), settings.wireFactory());
        adapter.start();
        try {
            ScriptedAppServer.await(() -> adapter.status().state() != RuntimeStatus.State.STARTING);
            TaskResult result = CodexTaskAdapterTest.result(adapter.submit(CodexTaskAdapterTest.request(
                    UUID.randomUUID(), InferenceSlot.STANDARD, "Narrate.", Duration.ofSeconds(20))));
            assertThat(result.status()).isEqualTo(TaskResult.Status.COMPLETED);
            assertThat(result.output()).isEqualTo(ScriptedAppServer.OUTPUT);
        } finally {
            adapter.stop();
        }
        assertThat(adapter.lastShutdown().stage()).isEqualTo("input-closed");
        assertThat(adapter.lastShutdown().exitCode()).isZero();
    }

    @Test
    void childInheritsOnlyAllowlistedEnvironmentAndStderrIsRedacted() throws Exception {
        StdioFrameWire wire = new StdioFrameWire(command(), directory, Duration.ofSeconds(2));
        wire.write("{\"method\":\"env\"}".getBytes(StandardCharsets.UTF_8));
        JsonNode reply = ScriptedAppServer.JSON.readTree(wire.read());
        List<String> names = Arrays.asList(reply.get("env").stringValue().split(","));
        assertThat(StdioFrameWire.INHERITED_ENVIRONMENT).containsAll(names.stream().filter(n -> !n.isEmpty()).toList());
        assertThat(System.getenv()).containsKey("PATH");
        ShutdownRecord shutdown = wire.close();
        assertThat(shutdown.stage()).isEqualTo("input-closed");
        StderrDiagnostics.Summary stderr = wire.stderrSummary();
        assertThat(stderr.lines()).isEqualTo(3);
        assertThat(stderr.levels()).isEqualTo(Map.of("WARN", 1L, "ERROR", 1L, "unstructured", 1L));
        assertThat(stderr.tail()).containsExactly("WARN codex_app_server", "ERROR unclassified", "unstructured");
        assertThat(stderr.toString()).doesNotContain("redact-me").doesNotContain("/home/owner");
        assertThat(wire.close()).isSameAs(shutdown);
    }

    @Test
    void childIgnoringEndOfInputIsTerminated() throws Exception {
        StdioFrameWire wire = new StdioFrameWire(command("--linger"), directory, Duration.ofMillis(500));
        ShutdownRecord shutdown = wire.close();
        assertThat(shutdown.stage()).isEqualTo("terminated");
    }

    @Test
    void partialFinalFrameAndEndOfStreamAreStickyFailures() throws Exception {
        StdioFrameWire wire = new StdioFrameWire(command("--partial"), directory, Duration.ofSeconds(2));
        assertThatThrownBy(wire::read).hasMessage("invalid-stdio-frame");
        assertThatThrownBy(wire::read).hasMessage("invalid-stdio-frame");
        assertThat(wire.close().stage()).isIn("exited", "input-closed");
        assertThatThrownBy(() -> wire.write("{\"a\":\n1}".getBytes(StandardCharsets.UTF_8))).hasMessage("invalid-frame");
    }

    @Test
    void launchRequiresAnAbsoluteExecutable() {
        assertThatThrownBy(() -> new StdioFrameWire(List.of("java"), directory, Duration.ofSeconds(1)))
                .hasMessage("runtime-launch");
        assertThatThrownBy(() -> new StdioFrameWire(List.of(directory.resolve("missing").toString()), directory,
                Duration.ofSeconds(1))).hasMessage("runtime-launch");
    }

    @Test
    void stderrClassificationKeepsOnlyFixedLabels() {
        assertThat(StderrDiagnostics.classify("2026-10-28T00:00:00Z INFO codex_core::config: x".getBytes(
                StandardCharsets.UTF_8))).isEqualTo("INFO codex_core::config");
        assertThat(StderrDiagnostics.classify("2026-10-28T00:00:00Z INFO codex_core::other: x".getBytes(
                StandardCharsets.UTF_8))).isEqualTo("INFO unclassified");
        assertThat(StderrDiagnostics.classify("token".getBytes(StandardCharsets.UTF_8))).isEqualTo("unstructured");
    }
}
