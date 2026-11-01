package org.rpgengine.inference.image;

import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageModelControl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Exposes the packaged image worker as the neutral {@link ImageModelControl} and {@link ImageGenerationPort} when both
 * {@code engine.inference.image-worker.url} and {@code engine.inference.admission.enabled} are set. Without both nothing
 * here exists. This package may not import the admission package, so the switch is read here by its name and the admission
 * configuration finds the control by its neutral type.
 *
 * <p>The setting names this worker alone. Nothing here builds a control for an {@code a1111-http} server, which cannot
 * release its checkpoint, and such a server at this address confirms nothing ({@link WorkerModelControl}).
 */
@Configuration(proxyBeanMethods = false)
@Conditional({WorkerImageConfiguration.Configured.class, WorkerImageConfiguration.AdmissionOn.class})
class WorkerImageConfiguration {
    /** A missing or blank address selects nothing. */
    static final class Configured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String url = context.getEnvironment().getProperty(WorkerSettings.URL);
            return url != null && !url.isBlank();
        }
    }

    /** The same strict reading as the admission package's switch: only {@code true} is on. */
    static final class AdmissionOn implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String text = context.getEnvironment().getProperty("engine.inference.admission.enabled");
            return text != null && text.strip().equalsIgnoreCase("true");
        }
    }

    /**
     * @param environment source of the worker's address
     * @return the control that loads and unloads the worker's checkpoint; building it contacts nothing
     */
    @Bean
    ImageModelControl imageModelControl(Environment environment) {
        return new ImageModelControl(new WorkerModelControl(WorkerSettings.from(environment)));
    }

    /**
     * @param environment source of the worker's address
     * @return the port over the worker, closed when the application stops; building it contacts nothing
     */
    @Bean
    WorkerImageAdapter workerImageAdapter(Environment environment) {
        return new WorkerImageAdapter(WorkerSettings.from(environment));
    }
}
