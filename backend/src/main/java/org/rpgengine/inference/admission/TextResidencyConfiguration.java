package org.rpgengine.inference.admission;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

import org.rpgengine.inference.InferenceSlot;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.ProfileResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Links the scheduler's text permit to the lease executor when {@code engine.inference.admission.enabled} is
 * {@code true}, apart from {@link AdmissionConfiguration}, which only constructs, and builds the image path that
 * shares the driver's passes. Without the switch the scheduler finds no
 * {@link org.rpgengine.inference.scheduling.TextResidency}, an image caller finds no {@link ImageAdmission}, and both
 * behave as before.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AdmissionConfiguration.Enabled.class)
class TextResidencyConfiguration {
    /** Backend name of the local adapter, which this package may not import. */
    static final String LOCAL_BACKEND = "local";
    /** Placeholder, not measured: a pass is cheap unless it has an unload to retry or a model to bring back. */
    private static final Duration TICK = Duration.ofSeconds(1);
    /** Placeholder, not measured: how late a running image job can notice that it must stop. */
    private static final Duration IMAGE_STEP_WAIT = Duration.ofSeconds(1);
    /** Placeholder, not measured: how long a cancelled image job may delay the unload of its model. */
    private static final Duration IMAGE_STOP_WAIT = Duration.ofSeconds(10);
    /** Placeholder, not measured: one step, the stop wait and a few seconds for an unload that answers promptly. */
    private static final Duration IMAGE_CLOSE_WAIT = Duration.ofSeconds(15);

    /**
     * @param profiles resolves a slot the way the adapter does
     * @param port the task port, whose answers come from the adapter's last model list
     * @return the model the local adapter would run for a slot; empty when the slot is on another backend or
     *         the adapter would refuse it, so that the adapter's own refusal or unavailability stands
     */
    static Function<InferenceSlot, Optional<String>> localModels(ProfileResolver profiles, NarrationTaskPort port) {
        return slot -> {
            if (profiles.assignedBackend(slot).filter(LOCAL_BACKEND::equals).isEmpty()) {
                return Optional.empty();
            }
            ProfileResolver.Resolution resolution = profiles.resolve(slot, LOCAL_BACKEND,
                    candidate -> port.listsModel(LOCAL_BACKEND, candidate.model()).orElse(false));
            return resolution instanceof ProfileResolver.Resolved resolved ? Optional.of(resolved.settings().model())
                    : Optional.empty();
        };
    }

    /**
     * @param executor runs the batches
     * @param coordinator receives the requests
     * @param options what an image model is taken to need
     * @return the image path, which {@code close} stops. Without the packaged worker's control, each of its batches ends
     *         {@code LOAD_FAILED}, so the caller gets catalog art (docs/34 section 10).
     */
    @Bean
    LeasedImageAdmission imageAdmission(LeaseExecutor executor, AdmissionCoordinator coordinator,
            AdmissionOptions options) {
        return new LeasedImageAdmission(executor, coordinator, options.imageDemand(), Clock.systemUTC(), IMAGE_STEP_WAIT,
                IMAGE_STOP_WAIT, IMAGE_CLOSE_WAIT);
    }

    /**
     * @param executor the executor this driver starts and passes
     * @param coordinator receives the requests
     * @param options what a text model is taken to need
     * @param profiles resolves a task's slot
     * @param port the task port
     * @param images receives the image grants of each pass
     * @return the driver, which the lifecycle starts and stops
     */
    @Bean
    TextResidencyDriver textResidencyDriver(LeaseExecutor executor, AdmissionCoordinator coordinator,
            AdmissionOptions options, ProfileResolver profiles, NarrationTaskPort port, LeasedImageAdmission images) {
        return new TextResidencyDriver(executor, coordinator, localModels(profiles, port), options.textDemand(),
                Clock.systemUTC(), TICK, images);
    }
}
