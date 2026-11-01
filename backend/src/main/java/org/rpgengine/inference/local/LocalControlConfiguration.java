package org.rpgengine.inference.local;

import org.rpgengine.inference.ModelServerControl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Exposes the local server's load, unload and slot routes as the neutral {@link ModelServerControl} when
 * both {@code engine.inference.local.url} and {@code engine.inference.admission.enabled} are set. This
 * package may not import the admission package, so the switch is read here by its name and the admission
 * configuration finds this bean by its neutral type. Without both settings nothing here exists.
 */
@Configuration(proxyBeanMethods = false)
@Conditional({LocalConfiguration.Configured.class, LocalControlConfiguration.AdmissionOn.class})
class LocalControlConfiguration {
    /** The same strict reading as the admission package's switch: only {@code true} is on. */
    static final class AdmissionOn implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String text = context.getEnvironment().getProperty("engine.inference.admission.enabled");
            return text != null && text.strip().equalsIgnoreCase("true");
        }
    }

    /**
     * @param environment source of {@code engine.inference.local.*}
     * @return the control of the local model server
     */
    @Bean
    ModelServerControl localModelServerControl(Environment environment) {
        return new LocalModelControl(LocalSettings.from(environment));
    }
}
