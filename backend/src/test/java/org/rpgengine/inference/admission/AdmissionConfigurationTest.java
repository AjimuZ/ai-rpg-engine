package org.rpgengine.inference.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ModelServerControl;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * The opt-in wiring of the admission coordinator and the lease executor, with no GPU, no database and no
 * model server: a stand-in lease store and a mocked control take their places.
 */
class AdmissionConfigurationTest {
    private static final long MIB = 1024L * 1024L;
    private final ModelServerControl text = mock(ModelServerControl.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdmissionConfiguration.class)
            .withBean(LeaseStore.class, MemoryLeaseStore::new)
            .withBean(ModelServerControl.class, () -> text);

    private ApplicationContextRunner on(String... more) {
        String[] values = new String[more.length + 2];
        values[0] = "engine.inference.admission.enabled=true";
        values[1] = "engine.inference.admission.gpu-budget-mib=10240";
        System.arraycopy(more, 0, values, 2, more.length);
        return runner.withPropertyValues(values);
    }

    @Test
    void withoutTheSwitchNoAdmissionBeanExists() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LeaseExecutor.class).doesNotHaveBean(AdmissionCoordinator.class)
                    .doesNotHaveBean(AdmissionControls.class).doesNotHaveBean(GpuMemoryReading.class)
                    .doesNotHaveBean(AdmissionOptions.class);
        });
    }

    @Test
    void aFalseOrBlankSwitchIsOffEvenWithSettingsPresent() {
        for (String value : new String[] {"false", "FALSE", " ", ""}) {
            runner.withPropertyValues("engine.inference.admission.enabled=" + value,
                    "engine.inference.admission.gpu-budget-mib=10240").run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(LeaseExecutor.class).doesNotHaveBean(AdmissionCoordinator.class);
                    });
        }
    }

    @Test
    void withTheSwitchTheExecutorIsBuiltOverAHoldingCoordinatorAndTheTextControl() {
        on().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LeaseExecutor.class).hasSingleBean(AdmissionCoordinator.class)
                    .hasSingleBean(AdmissionControls.class).hasSingleBean(GpuMemoryReading.class);
            AdmissionCoordinator coordinator = context.getBean(AdmissionCoordinator.class);
            assertThat(coordinator.expiry()).isEqualTo(AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
            assertThat(coordinator.capacity())
                    .isEqualTo(new Demand(10240 * MIB, AdmissionOptions.DEFAULT_RAM_MIB * MIB));
            assertThat(context.getBean(AdmissionControls.class).byModality())
                    .containsOnlyKeys(Modality.TEXT).containsEntry(Modality.TEXT, text);
            assertThat(context.getBean(GpuMemoryReading.class)).isInstanceOf(HostGpuMemoryReading.class);
            assertThat(context.getBean(LeaseExecutor.class).metrics())
                    .containsEntry("capacity_free_gpu_bytes", 10240 * MIB).containsEntry("leases_active", 0L);
        });
    }

    @Test
    void constructionLoadsNothingAndReadsNothing() {
        on().run(context -> {
            assertThat(context).hasNotFailed();
            verifyNoInteractions(text);
        });
    }

    @Test
    void ownerValuesReachTheOptions() {
        on("engine.inference.admission.ram-budget-mib=16384", "engine.inference.admission.headroom-mib=512",
                "engine.inference.admission.patience=45s").run(context -> {
                    AdmissionOptions options = context.getBean(AdmissionOptions.class);
                    assertThat(options.capacity()).isEqualTo(new Demand(10240 * MIB, 16384 * MIB));
                    assertThat(options.headroomBytes()).isEqualTo(512 * MIB);
                    assertThat(options.patience()).isEqualTo(Duration.ofSeconds(45));
                });
    }

    @Test
    void defaultsAreOneGibHeadroomAndTwoMinutesPatience() {
        MockEnvironment environment = new MockEnvironment().withProperty("engine.inference.admission.gpu-budget-mib", "8192");
        AdmissionOptions options = AdmissionOptions.from(environment);
        assertThat(options.headroomBytes()).isEqualTo(1024 * MIB);
        assertThat(options.patience()).isEqualTo(Duration.ofMinutes(2));
        assertThat(options.capacity().ramBytes()).isEqualTo(AdmissionOptions.MAX_MIB * MIB);
    }

    @Test
    void aTextModelIsTakenToNeedTheWholeBudgetUntilTheOwnerSaysOtherwise() {
        MockEnvironment defaults = new MockEnvironment().withProperty("engine.inference.admission.gpu-budget-mib", "8192")
                .withProperty("engine.inference.admission.ram-budget-mib", "16384");
        assertThat(AdmissionOptions.from(defaults).textDemand()).isEqualTo(new Demand(8192 * MIB, 16384 * MIB));
        defaults.setProperty("engine.inference.admission.text-gpu-demand-mib", "4096");
        defaults.setProperty("engine.inference.admission.text-ram-demand-mib", "0");
        assertThat(AdmissionOptions.from(defaults).textDemand()).isEqualTo(new Demand(4096 * MIB, 0));
    }

    @Test
    void aTextDemandOutsideTheBudgetFailsStartupWithoutEchoingIt() {
        String[][] cases = {
                {"text-gpu-demand-mib=10241", "text-gpu-demand-mib must be between", "10241"},
                {"text-gpu-demand-mib=0", "text-gpu-demand-mib must be between", "=0"},
                {"text-gpu-demand-mib=secret-size", "text-gpu-demand-mib is not a whole number", "secret-size"},
                {"text-ram-demand-mib=7654321", "text-ram-demand-mib must be between", "7654321"},
                {"text-ram-demand-mib=-7654321", "text-ram-demand-mib must be between", "7654321"}};
        for (String[] each : cases) {
            on("engine.inference.admission." + each[0]).run(context -> assertThat(context).hasFailed().getFailure()
                    .hasStackTraceContaining("engine.inference.admission." + each[1])
                    .satisfies(failure -> assertThat(failure.toString()).doesNotContain(each[2])));
        }
    }

    @Test
    void theSwitchWithoutAModelServerControlFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(AdmissionConfiguration.class)
                .withBean(LeaseStore.class, MemoryLeaseStore::new)
                .withPropertyValues("engine.inference.admission.enabled=true",
                        "engine.inference.admission.gpu-budget-mib=10240")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("engine.inference.admission.enabled needs exactly one model server control"));
    }

    @Test
    void theSwitchWithoutABudgetFailsStartup() {
        runner.withPropertyValues("engine.inference.admission.enabled=true").run(context -> assertThat(context).hasFailed()
                .getFailure().hasStackTraceContaining("engine.inference.admission.gpu-budget-mib is required"));
    }

    @Test
    void aSwitchThatIsNeitherTrueNorFalseFailsStartupWithoutEchoingIt() {
        runner.withPropertyValues("engine.inference.admission.enabled=perhaps-private").run(context -> assertThat(context)
                .hasFailed().getFailure().hasStackTraceContaining("engine.inference.admission.enabled must be true or false")
                .satisfies(failure -> assertThat(failure.toString()).doesNotContain("perhaps-private")));
    }

    @Test
    void outOfRangeOrMalformedValuesFailStartupWithoutEchoingThem() {
        String[][] cases = {
                {"gpu-budget-mib=-7654321", "gpu-budget-mib out of range", "7654321"},
                {"gpu-budget-mib=7654321987", "gpu-budget-mib out of range", "7654321987"},
                {"gpu-budget-mib=secret-gpu", "gpu-budget-mib is not a whole number", "secret-gpu"},
                {"ram-budget-mib=-7654321", "ram-budget-mib out of range", "7654321"},
                {"ram-budget-mib=7654321987", "ram-budget-mib out of range", "7654321987"},
                {"headroom-mib=-7654321", "headroom-mib out of range", "7654321"},
                {"headroom-mib=7654321", "headroom-mib out of range", "7654321"},
                {"headroom-mib=1.5e9", "headroom-mib is not a whole number", "1.5e9"},
                {"patience=0s", "patience must be between 0 and 1 hour", "0s"},
                {"patience=-9876s", "patience must be between 0 and 1 hour", "9876"},
                {"patience=3h", "patience must be between 0 and 1 hour", "3h"},
                {"patience=sometime-soon", "patience is not a duration", "sometime-soon"}};
        for (String[] each : cases) {
            on("engine.inference.admission." + each[0]).run(context -> assertThat(context).hasFailed().getFailure()
                    .hasStackTraceContaining("engine.inference.admission." + each[1])
                    .satisfies(failure -> assertThat(failure.toString()).doesNotContain(each[2])));
        }
    }

    @Test
    void theExecutorRefusesACoordinatorThatFreesAtOnce() {
        // Pins why the configuration names HOLDS_UNTIL_UNLOADED: the other rule would not build.
        AdmissionSettings settings = AdmissionFixtures.settings();
        AdmissionCoordinator accounting = new AdmissionCoordinator(settings, new MemoryLeaseStore(),
                java.time.Clock.systemUTC());
        LeaseExecutorSettings executor = new LeaseExecutorSettings(0, 0, 0, 1, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThatThrownBy(() -> new LeaseExecutor(accounting, java.util.Map.of(), () -> java.util.OptionalLong.empty(),
                () -> false, executor, java.time.Clock.systemUTC(), duration -> { })).isInstanceOf(
                        IllegalArgumentException.class);
    }
}
