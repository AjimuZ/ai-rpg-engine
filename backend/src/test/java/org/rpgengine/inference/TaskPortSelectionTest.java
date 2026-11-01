package org.rpgengine.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.rpgengine.health.GameplayHealthIndicator;
import org.rpgengine.inference.admission.AdmissionControls;
import org.rpgengine.inference.admission.AdmissionCoordinator;
import org.rpgengine.inference.admission.JdbcLeaseStore;
import org.rpgengine.inference.admission.LeaseExecutor;
import org.rpgengine.inference.admission.LeaseStore;
import org.rpgengine.inference.admission.Modality;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.inference.scheduling.JdbcTaskLedger;
import org.rpgengine.inference.scheduling.MemoryTaskLedger;
import org.rpgengine.inference.scheduling.ScheduledTask;
import org.rpgengine.inference.scheduling.SchedulerSettings;
import org.rpgengine.inference.scheduling.TaskLedger;
import org.rpgengine.inference.scheduling.TextResidency;
import org.rpgengine.inference.scheduling.WorkClass;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.JdbcSessionJournal;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.inference.session.SessionScope;
import org.rpgengine.inference.slots.JdbcSlotSettingsStore;
import org.rpgengine.inference.slots.MemorySlotSettingsStore;
import org.rpgengine.inference.slots.ModelSlot;
import org.rpgengine.inference.slots.SlotSettingsService;
import org.rpgengine.inference.slots.SlotSettingsStore;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Spring selection of exactly one task port: the unavailable port by default, the Codex adapter
 * for {@code stdio}/{@code unix}, and a startup failure for any other transport or for a
 * half-assigned slot; with a local server URL, the routing port in front of the adapters (P21.3-B).
 * The scoped session registry (P08.3) wraps whichever port is selected, and the
 * fair scheduler (P08.6) sits in front of the registry with the single qualified turn permit. Uses
 * component scanning of the inference package, so the adapter is reached only through the neutral
 * interface; the scan replaces the PostgreSQL ledger and the PostgreSQL slot-settings store with
 * in-memory ones and leaves out the PostgreSQL session journal (the registry then records nothing)
 * and the PostgreSQL lease store, because this context has no data source.
 */
class TaskPortSelectionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Scan.class, GameplayHealthIndicator.class);

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(value = "org.rpgengine.inference",
            excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = {JdbcTaskLedger.class, JdbcSessionJournal.class, JdbcSlotSettingsStore.class,
                            JdbcLeaseStore.class}))
    static class Scan {
        @org.springframework.context.annotation.Bean
        TaskLedger ledger() {
            return new MemoryTaskLedger();
        }

        @org.springframework.context.annotation.Bean
        SlotSettingsStore slotSettings() {
            return new MemorySlotSettingsStore();
        }
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
    void scopedSessionsWrapTheSelectedPortAndRefuseAnInvalidCapacity() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ScopedSessions.class);
            ScopedSessions sessions = context.getBean(ScopedSessions.class);
            var scope = new SessionScope(UUID.randomUUID(), UUID.randomUUID(), 0, AiRole.NARRATOR, Audience.PUBLIC,
                    "a".repeat(64), "b".repeat(64));
            TaskResult result = sessions.submit(scope, UUID.randomUUID(), InferenceSlot.STANDARD, "x", "{}",
                    Duration.ofSeconds(5)).result().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(TaskResult.Status.UNAVAILABLE);
            assertThat(sessions.activeSession(scope)).isPresent();
        });
        runner.withPropertyValues("engine.inference.sessions.capacity=0").run(context -> assertThat(context)
                .hasFailed().getFailure().hasStackTraceContaining("engine.inference.sessions.capacity must be 1..4096"));
    }

    @Test
    void theSchedulerFrontsTheRegistryWithOneQualifiedPermitAndRefusesHigherConcurrency() {
        runner.run(context -> {
            InferenceScheduler scheduler = context.getBean(InferenceScheduler.class);
            assertThat(scheduler.status().state()).isEqualTo(InferenceScheduler.State.READY);
            assertThat(scheduler.status().maxActive()).isEqualTo(SchedulerSettings.QUALIFIED_MAX_ACTIVE).isEqualTo(1);
            var scope = new SessionScope(UUID.randomUUID(), UUID.randomUUID(), 0, AiRole.NARRATOR, Audience.PUBLIC,
                    "a".repeat(64), "b".repeat(64));
            TaskResult result = scheduler.submit(new ScheduledTask(UUID.randomUUID(), scope, WorkClass.INTERACTIVE,
                    InferenceSlot.STANDARD, "x", "{}", Duration.ofSeconds(5))).result().toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(TaskResult.Status.UNAVAILABLE);
            assertThat(result.errorCode()).isEqualTo("runtime-not-configured");
            assertThat(context.getBean(ScopedSessions.class).activeSession(scope)).isPresent();
        });
        runner.withPropertyValues("engine.inference.scheduler.max-active=2").run(context -> assertThat(context)
                .hasFailed().getFailure().hasStackTraceContaining("max-active above 1 is concurrency-unqualified"));
        runner.withPropertyValues("engine.inference.scheduler.background-per-campaign=777123").run(context -> assertThat(
                context).hasFailed().getFailure().hasStackTraceContaining("background-per-campaign is outside")
                .satisfies(failure -> assertThat(failure.toString()).doesNotContain("777123")));
        runner.withPropertyValues("engine.inference.scheduler.daily-usage-limit=lots").run(context -> assertThat(
                context).hasFailed().getFailure().hasStackTraceContaining("daily-usage-limit must be a whole number")
                .satisfies(failure -> assertThat(failure.toString()).doesNotContain("lots")));
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
                            .contains(SlotAssignment.of("codex", new EffectiveSettings("model-a", "medium")));
                });
    }

    @Test
    void aBlankLocalUrlSelectsNothingNew() {
        runner.withPropertyValues("engine.inference.local.url= ").run(context -> {
            assertThat(context).hasSingleBean(NarrationTaskPort.class);
            assertThat(context.getBean(NarrationTaskPort.class)).isInstanceOf(UnavailableTaskPort.class);
        });
    }

    @Test
    void aLocalUrlPutsTheRoutingPortInFrontOfTheAdaptersAndRoutesBySlot() {
        runner.withPropertyValues("engine.inference.local.url=http://127.0.0.1:9",
                "engine.inference.local.reconnect-initial=50ms", "engine.inference.slots.standard.model=model-a",
                "engine.inference.slots.standard.effort=low", "engine.inference.slots.standard.backend=local",
                "engine.inference.slots.light.model=model-a", "engine.inference.slots.light.effort=low",
                "engine.inference.slots.light.backend=codex").run(context -> {
                    assertThat(context).hasNotFailed();
                    NarrationTaskPort port = context.getBean(NarrationTaskPort.class);
                    assertThat(port).isInstanceOf(SlotRoutingTaskPort.class);
                    assertThat(context.getBeansOfType(NarrationTaskPort.class)).hasSize(3);
                    // The registry and the probe use the routing port, not one adapter.
                    assertThat(port.statusOf("local")).isPresent();
                    assertThat(port.statusOf("codex")).isEmpty();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (port.status().state() != RuntimeStatus.State.UNAVAILABLE && System.nanoTime() < deadline) {
                        Thread.sleep(10);
                    }
                    assertThat(port.status().reasonCode()).isEqualTo("runtime-unreachable");
                    assertThat(context.getBean(GameplayHealthIndicator.class).health().getStatus())
                            .isEqualTo(Status.UNKNOWN);
                    ScopedSessions sessions = context.getBean(ScopedSessions.class);
                    var scope = new SessionScope(UUID.randomUUID(), UUID.randomUUID(), 0, AiRole.NARRATOR, Audience.PUBLIC,
                            "a".repeat(64), "b".repeat(64));
                    TaskResult toLocal = sessions.submit(scope, UUID.randomUUID(), InferenceSlot.STANDARD, "x", "{}",
                            Duration.ofSeconds(5)).result().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    assertThat(toLocal.errorCode()).isEqualTo("runtime-unreachable");
                    TaskResult toCodex = sessions.submit(scope, UUID.randomUUID(), InferenceSlot.LIGHT, "x", "{}",
                            Duration.ofSeconds(5)).result().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    assertThat(toCodex.errorCode()).isEqualTo("slot-backend-mismatch");
                });
    }

    @Test
    void theAdmissionSwitchIsOffByDefaultAndWithItOffNoAdmissionBeanExists() {
        runner.run(context -> assertThat(context).doesNotHaveBean(LeaseExecutor.class)
                .doesNotHaveBean(TextResidency.class));
        runner.withPropertyValues("engine.inference.local.url=http://127.0.0.1:9",
                "engine.inference.admission.gpu-budget-mib=10240").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LeaseExecutor.class).doesNotHaveBean(AdmissionCoordinator.class)
                            .doesNotHaveBean(ModelServerControl.class).doesNotHaveBean(TextResidency.class);
                    assertThat(context.getBean(NarrationTaskPort.class)).isInstanceOf(SlotRoutingTaskPort.class);
                });
    }

    @Test
    void theAdmissionSwitchWithALocalUrlBuildsTheExecutorOverTheLocalControlAndLeavesPortSelectionAlone() {
        runner.withBean(LeaseStore.class, () -> mock(LeaseStore.class))
                .withPropertyValues("engine.inference.local.url=http://127.0.0.1:9",
                        "engine.inference.local.reconnect-initial=50ms", "engine.inference.admission.enabled=true",
                        "engine.inference.admission.gpu-budget-mib=10240")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LeaseExecutor.class);
                    assertThat(context.getBean(ModelServerControl.class).getClass().getPackageName())
                            .isEqualTo("org.rpgengine.inference.local");
                    assertThat(context.getBean(AdmissionControls.class).byModality()).containsOnlyKeys(Modality.TEXT)
                            .containsValue(context.getBean(ModelServerControl.class));
                    assertThat(context.getBean(NarrationTaskPort.class)).isInstanceOf(SlotRoutingTaskPort.class);
                });
    }

    @Test
    void theWorkerAddressAddsTheImageControlAndPortAndLeavesTheTextLookupAlone() {
        runner.withBean(LeaseStore.class, () -> mock(LeaseStore.class))
                .withPropertyValues("engine.inference.local.url=http://127.0.0.1:9",
                        "engine.inference.local.reconnect-initial=50ms", "engine.inference.admission.enabled=true",
                        "engine.inference.admission.gpu-budget-mib=10240",
                        "engine.inference.image-worker.url=http://127.0.0.1:9")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModelServerControl.class).hasSingleBean(ImageModelControl.class);
                    ModelServerControl text = context.getBean(ModelServerControl.class);
                    assertThat(text.getClass().getPackageName()).isEqualTo("org.rpgengine.inference.local");
                    assertThat(context.getBean(AdmissionControls.class).byModality())
                            .containsOnlyKeys(Modality.TEXT, Modality.IMAGE).containsEntry(Modality.TEXT, text)
                            .containsEntry(Modality.IMAGE, context.getBean(ImageModelControl.class).control());
                    assertThat(context.getBean(ImageGenerationPort.class).backend()).isEqualTo("local-image");
                });
    }

    @Test
    void withTheSwitchOffTheWorkerAddressAddsNoBeanAndPortSelectionStaysAsItWas() {
        runner.withPropertyValues("engine.inference.local.url=http://127.0.0.1:9",
                "engine.inference.image-worker.url=http://127.0.0.1:9").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ImageModelControl.class).doesNotHaveBean(ImageGenerationPort.class)
                            .doesNotHaveBean(ModelServerControl.class).doesNotHaveBean(AdmissionControls.class)
                            .doesNotHaveBean(LeaseExecutor.class);
                    assertThat(context.getBean(NarrationTaskPort.class)).isInstanceOf(SlotRoutingTaskPort.class);
                });
        runner.withPropertyValues("engine.inference.image-worker.url=http://127.0.0.1:9",
                "engine.inference.admission.enabled=false").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ImageModelControl.class).doesNotHaveBean(ImageGenerationPort.class);
                    assertThat(context.getBean(NarrationTaskPort.class)).isInstanceOf(UnavailableTaskPort.class);
                });
    }

    @Test
    void anA1111PortGetsNoImageControl() {
        ImageGenerationPort a1111 = mock(ImageGenerationPort.class);
        when(a1111.backend()).thenReturn("a1111-http");
        runner.withBean(LeaseStore.class, () -> mock(LeaseStore.class))
                .withBean(ImageGenerationPort.class, () -> a1111)
                .withPropertyValues("engine.inference.local.url=http://127.0.0.1:9",
                        "engine.inference.local.reconnect-initial=50ms", "engine.inference.admission.enabled=true",
                        "engine.inference.admission.gpu-budget-mib=10240")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ImageModelControl.class);
                    assertThat(context.getBean(AdmissionControls.class).byModality()).containsOnlyKeys(Modality.TEXT);
                });
    }

    @Test
    void theAdmissionSwitchGivesTheSchedulerAResidencySideThatServesATaskOnAnotherBackendAtOnce() {
        runner.withBean(LeaseStore.class, () -> mock(LeaseStore.class))
                .withPropertyValues("engine.inference.local.url=http://127.0.0.1:9",
                        "engine.inference.local.reconnect-initial=50ms", "engine.inference.admission.enabled=true",
                        "engine.inference.admission.gpu-budget-mib=10240",
                        "engine.inference.slots.standard.backend=codex", "engine.inference.slots.standard.model=model-a",
                        "engine.inference.slots.standard.effort=medium")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TextResidency.class);
                    var scope = new SessionScope(UUID.randomUUID(), UUID.randomUUID(), 0, AiRole.NARRATOR, Audience.PUBLIC,
                            "a".repeat(64), "b".repeat(64));
                    // The slot is on a backend with no adapter here, so the routing port refuses it, and no lease is taken.
                    TaskResult result = context.getBean(InferenceScheduler.class).submit(new ScheduledTask(UUID.randomUUID(),
                            scope, WorkClass.INTERACTIVE, InferenceSlot.STANDARD, "x", "{}", Duration.ofSeconds(5))).result()
                            .toCompletableFuture().get(5, TimeUnit.SECONDS);
                    assertThat(result.status()).isEqualTo(TaskResult.Status.FAILED);
                    assertThat(result.errorCode()).isEqualTo("slot-backend-mismatch");
                    assertThat(context.getBean(LeaseExecutor.class).metrics()).containsEntry("leases_active", 0L);
                });
    }

    @Test
    void theAdmissionSwitchWithoutALocalUrlFailsStartup() {
        runner.withBean(LeaseStore.class, () -> mock(LeaseStore.class))
                .withPropertyValues("engine.inference.admission.enabled=true",
                        "engine.inference.admission.gpu-budget-mib=10240")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("needs exactly one model server control"));
    }

    @Test
    void theLocalBackendRunsBesideTheRuntimeAdapter() {
        runner.withPropertyValues("engine.inference.local.url=http://127.0.0.1:9",
                "engine.inference.codex.transport=unix", "engine.inference.codex.socket-path=/nonexistent/codex.sock")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NarrationTaskPort port = context.getBean(NarrationTaskPort.class);
                    assertThat(port).isInstanceOf(SlotRoutingTaskPort.class);
                    assertThat(port.statusOf("local")).isPresent();
                    assertThat(port.statusOf("codex")).isPresent();
                });
    }

    @Test
    void savingAChangedAssignmentRetiresThatSlotsSessionsThroughTheApplicationEvent() {
        runner.run(context -> {
            ScopedSessions sessions = context.getBean(ScopedSessions.class);
            var scope = new SessionScope(UUID.randomUUID(), UUID.randomUUID(), 0, AiRole.NARRATOR, Audience.PUBLIC,
                    "a".repeat(64), "b".repeat(64));
            sessions.submit(scope, UUID.randomUUID(), InferenceSlot.STANDARD, "x", "{}", Duration.ofSeconds(5));
            assertThat(sessions.activeSession(scope)).isPresent();
            SlotSettingsService settings = context.getBean(SlotSettingsService.class);

            settings.update(new SlotSettingsService.Request(ModelSlot.LIGHT, "local", "model-a", "low", null, List.of(),
                    null));
            assertThat(sessions.activeSession(scope)).isPresent();

            settings.update(new SlotSettingsService.Request(ModelSlot.STANDARD, "local", "model-a", "low", null, List.of(),
                    null));
            assertThat(sessions.activeSession(scope)).isEmpty();
            assertThat(sessions.status().retired()).containsEntry(ScopedSessions.Reason.SLOT_CHANGED, 1L);
        });
    }

    @Test
    void anInvalidLocalUrlFailsStartupWithoutEchoingIt() {
        runner.withPropertyValues("engine.inference.local.url=ftp://private-host").run(context -> assertThat(context)
                .hasFailed().getFailure().hasStackTraceContaining("engine.inference.local.url must be")
                .satisfies(failure -> assertThat(failure.toString()).doesNotContain("private-host")));
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

    @Test
    void profileResolverIsWiredFromOwnerSettingsAndRefusesAnInvalidFixedQuality() {
        runner.withPropertyValues("engine.inference.fixed-quality=Deliberate",
                "engine.inference.slots.deliberate.model=model-b", "engine.inference.slots.deliberate.effort=high",
                "engine.inference.allowlist.codex=model-b@high").run(context -> {
                    ProfileResolver resolver = context.getBean(ProfileResolver.class);
                    // Every request resolves through the fixed-quality slot, against the codex allowlist.
                    assertThat(resolver.resolve(InferenceSlot.LIGHT, "codex", pair -> true))
                            .isEqualTo(new ProfileResolver.Resolved(InferenceSlot.DELIBERATE,
                                    new EffectiveSettings("model-b", "high"), null));
                    assertThat(resolver.resolve(InferenceSlot.LIGHT, "codex", pair -> false))
                            .isEqualTo(new ProfileResolver.Refused("slot-unsupported"));
                });
        runner.withPropertyValues("engine.inference.fixed-quality=secret-profile").run(context -> assertThat(context)
                .hasFailed().getFailure().hasStackTraceContaining("fixed-quality must be light, standard or deliberate")
                .satisfies(failure -> assertThat(failure.toString()).doesNotContain("secret-profile")));
        runner.withPropertyValues("engine.inference.allowlist.codex=secret-model").run(context -> assertThat(context)
                .hasFailed().getFailure().hasStackTraceContaining("allowlist of backend codex is malformed")
                .satisfies(failure -> assertThat(failure.toString()).doesNotContain("secret-model")));
    }
}
