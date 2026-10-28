package org.rpgengine.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.rpgengine.health.GameplayHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Spring selection of exactly one task port: the unavailable port by default, the Codex adapter
 * for {@code stdio}/{@code unix}, and a startup failure for any other transport or for a
 * half-assigned slot. Uses component scanning of the inference package, so the adapter is
 * reached only through the neutral interface.
 */
class TaskPortSelectionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Scan.class, GameplayHealthIndicator.class);

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan("org.rpgengine.inference")
    static class Scan {
    }

    static TaskRequest request() {
        return new TaskRequest(UUID.randomUUID(), UUID.randomUUID(), InferenceSlot.STANDARD, "x", "{}",
                Duration.ofSeconds(5));
    }

    @Test
    void defaultIsTheUnavailablePortAndGameplayHealthStaysUnknown() {
        runner.run(context -> {
            NarrationTaskPort port = context.getBean(NarrationTaskPort.class);
            assertThat(port).isInstanceOf(UnavailableTaskPort.class);
            assertThat(port.status().state()).isEqualTo(RuntimeStatus.State.DISABLED);
            TaskResult result = port.submit(request()).result().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(TaskResult.Status.UNAVAILABLE);
            assertThat(result.errorCode()).isEqualTo("runtime-not-configured");
            assertThat(context.getBean(GameplayHealthIndicator.class).health().getStatus()).isEqualTo(Status.UNKNOWN);
        });
    }

    @Test
    void unixTransportSelectsTheCodexAdapterWhichReportsAnUnreachableSocket() {
        runner.withPropertyValues("engine.inference.codex.transport=unix",
                "engine.inference.codex.socket-path=/nonexistent/codex.sock",
                "engine.inference.codex.reconnect-initial=50ms",
                "engine.inference.slots.standard.model=model-a", "engine.inference.slots.standard.effort=medium")
                .run(context -> {
                    assertThat(context).hasSingleBean(NarrationTaskPort.class);
                    NarrationTaskPort port = context.getBean(NarrationTaskPort.class);
                    assertThat(port.getClass().getPackageName()).isEqualTo("org.rpgengine.inference.codex");
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (port.status().state() != RuntimeStatus.State.UNAVAILABLE && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                    }
                    assertThat(port.status().reasonCode()).isEqualTo("runtime-unreachable");
                    assertThat(port.status().runtime()).isEqualTo("codex");
                    assertThat(context.getBean(GameplayHealthIndicator.class).health().getStatus())
                            .isEqualTo(Status.UNKNOWN);
                    assertThat(context.getBean(SlotAssignments.class).resolve(InferenceSlot.STANDARD))
                            .contains(new EffectiveSettings("model-a", "medium"));
                });
    }

    @Test
    void unknownTransportOrHalfAssignedSlotFailsStartupWithoutEchoingValues() {
        runner.withPropertyValues("engine.inference.codex.transport=tcp").run(context -> assertThat(context)
                .hasFailed().getFailure().hasStackTraceContaining("transport must be disabled, stdio or unix"));
        runner.withPropertyValues("engine.inference.codex.transport=stdio",
                "engine.inference.codex.command=relative/codex,app-server",
                "engine.inference.codex.working-directory=/tmp").run(context -> assertThat(context).hasFailed()
                .getFailure().hasStackTraceContaining("command must be an absolute path"));
        runner.withPropertyValues("engine.inference.slots.light.model=private-model-name").run(context -> assertThat(
                context).hasFailed().getFailure().hasStackTraceContaining("slot light needs both model and effort")
                .satisfies(failure -> assertThat(failure.toString()).doesNotContain("private-model-name")));
    }
}
