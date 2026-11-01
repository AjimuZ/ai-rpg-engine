package org.rpgengine.inference.admission;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.rpgengine.inference.ModelServerControl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Builds the admission coordinator and the lease executor when {@code engine.inference.admission.enabled}
 * is {@code true}. Without the switch no bean here exists, nothing reads the settings and behaviour is as
 * before; the {@link JdbcLeaseStore} component stays a passive bean that touches the database only when
 * called.
 *
 * <p>This only constructs: nothing calls {@link LeaseExecutor#start} or {@link LeaseExecutor#pass}, and no
 * task or job submits a request yet, so enabling the switch loads and unloads nothing. Every value that is
 * not an owner setting ({@link AdmissionOptions}) is a placeholder fixed below and has not been measured.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AdmissionConfiguration.Enabled.class)
class AdmissionConfiguration {
    private static final Duration LEASE_TTL = Duration.ofMinutes(2);
    private static final Duration AGING_STEP = Duration.ofMinutes(1);
    private static final Duration STATUS_DELAY = Duration.ofSeconds(1);
    private static final Duration RECHECK = Duration.ofSeconds(5);
    private static final Duration TIME_CAP = Duration.ofMinutes(10);
    private static final int STATUS_ATTEMPTS = 30;

    /** Reads the switch in code rather than an expression, which could echo a mistyped value. */
    static final class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return AdmissionOptions.enabled(context.getEnvironment());
        }
    }

    /**
     * @param environment source of {@code engine.inference.admission.*}
     * @return the validated owner settings; an invalid one fails startup
     */
    @Bean
    AdmissionOptions admissionOptions(Environment environment) {
        return AdmissionOptions.from(environment);
    }

    /**
     * @param options the owner's budget
     * @param store the durable lease record
     * @return a coordinator that holds expired capacity until its model is confirmed unloaded, which the
     *         executor requires because it really loads models
     */
    @Bean
    AdmissionCoordinator admissionCoordinator(AdmissionOptions options, LeaseStore store) {
        AdmissionSettings settings = new AdmissionSettings(options.capacity(), LEASE_TTL, AGING_STEP,
                Map.of(Modality.TEXT, 1, Modality.IMAGE, 4, Modality.SPEECH, 4));
        return new AdmissionCoordinator(settings, store, Clock.systemUTC(), AdmissionCoordinator.Expiry.HOLDS_UNTIL_UNLOADED);
    }

    /**
     * @param text the text model server's control, present when {@code engine.inference.local.url} is set
     * @return the text modality's control; image and speech have none until their workers exist
     * @throws IllegalStateException when no text control exists, so the owner learns at startup that the switch
     *         has nothing to drive
     */
    @Bean
    AdmissionControls admissionControls(ObjectProvider<ModelServerControl> text) {
        ModelServerControl control = text.getIfUnique();
        if (control == null) {
            throw new IllegalStateException(AdmissionOptions.ENABLED + " needs exactly one model server control, "
                    + "which engine.inference.local.url provides");
        }
        return new AdmissionControls(Map.of(Modality.TEXT, control));
    }

    /** @return the free GPU memory read from the host's {@code nvidia-smi}; unknown when it cannot be read */
    @Bean
    GpuMemoryReading gpuMemoryReading() {
        return new HostGpuMemoryReading();
    }

    /**
     * @param coordinator the leases
     * @param controls what holds each modality's models
     * @param reading free GPU memory
     * @param options headroom and patience
     * @return the executor, not started: the first {@link LeaseExecutor#start} belongs to whoever drives it. No
     *         text request is ever expected soon, because no scheduler link exists yet.
     */
    @Bean
    LeaseExecutor leaseExecutor(AdmissionCoordinator coordinator, AdmissionControls controls, GpuMemoryReading reading,
            AdmissionOptions options) {
        LeaseExecutorSettings settings = new LeaseExecutorSettings(0, options.headroomBytes(), 0, STATUS_ATTEMPTS,
                STATUS_DELAY, RECHECK, options.patience(), TIME_CAP);
        return new LeaseExecutor(coordinator, controls.byModality(), reading, () -> false, settings, Clock.systemUTC(),
                duration -> Thread.sleep(duration.toMillis()));
    }
}
