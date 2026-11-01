package org.rpgengine.inference.admission;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.rpgengine.inference.ImageModelControl;
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
 * <p>This only constructs; {@link TextResidencyConfiguration} adds the driver that runs the executor and the
 * link to the scheduler. Every value that is not an owner setting ({@link AdmissionOptions}) is a placeholder
 * fixed below and has not been measured.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AdmissionConfiguration.Enabled.class)
class AdmissionConfiguration {
    /** Outlasts the longest load {@link #STATUS_ATTEMPTS} allows, because nothing can renew a lease during a load. */
    private static final Duration LEASE_TTL = Duration.ofMinutes(5);
    private static final Duration AGING_STEP = Duration.ofMinutes(1);
    private static final Duration STATUS_DELAY = Duration.ofSeconds(1);
    private static final Duration RECHECK = Duration.ofSeconds(5);
    private static final Duration TIME_CAP = Duration.ofMinutes(10);
    /** Four minutes at one check a second: the slowest load measured on the reference host took 164 s (docs/34 section 7). */
    private static final int STATUS_ATTEMPTS = 240;

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
     * @param image the packaged image worker's control, present when {@code engine.inference.image-worker.url} is set
     * @return the text modality's control, and the image modality's when the worker is configured. Without it an image
     *         batch ends {@code LOAD_FAILED}, as for an {@code a1111-http} server: a control that reported a checkpoint
     *         gone while its server still held it would let a text model load beside it. Speech has none until its worker
     *         exists.
     * @throws IllegalStateException when no text control exists, so the owner learns at startup that the switch
     *         has nothing to drive
     */
    @Bean
    AdmissionControls admissionControls(ObjectProvider<ModelServerControl> text, ObjectProvider<ImageModelControl> image) {
        ModelServerControl control = text.getIfUnique();
        if (control == null) {
            throw new IllegalStateException(AdmissionOptions.ENABLED + " needs exactly one model server control, "
                    + "which engine.inference.local.url provides");
        }
        Map<Modality, ModelServerControl> controls = new EnumMap<>(Modality.class);
        controls.put(Modality.TEXT, control);
        image.ifAvailable(worker -> controls.put(Modality.IMAGE, worker.control()));
        return new AdmissionControls(controls);
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
     * @return the executor, not started: the first {@link LeaseExecutor#start} belongs to {@link TextResidencyDriver}.
     *         No text request is ever reported as expected soon, because no source of that demand exists yet.
     */
    @Bean
    LeaseExecutor leaseExecutor(AdmissionCoordinator coordinator, AdmissionControls controls, GpuMemoryReading reading,
            AdmissionOptions options) {
        LeaseExecutorSettings settings = new LeaseExecutorSettings(0, options.headroomBytes(), 0, STATUS_ATTEMPTS,
                STATUS_DELAY, RECHECK, options.patience(), TIME_CAP, options.unmeasuredExtraBytes());
        return new LeaseExecutor(coordinator, controls.byModality(), reading, () -> false, settings, Clock.systemUTC(),
                duration -> Thread.sleep(duration.toMillis()),
                lease -> options.sizes().isMeasured(lease.modality(), lease.model()));
    }
}
