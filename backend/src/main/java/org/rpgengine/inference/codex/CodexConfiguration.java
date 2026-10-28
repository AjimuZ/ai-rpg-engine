package org.rpgengine.inference.codex;

import org.rpgengine.inference.ProfileResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Selects the Codex adapter as the installation's task port when
 * {@code engine.inference.codex.transport} is {@code stdio} or {@code unix}. Any other value
 * except {@code disabled} fails startup in {@link CodexSettings#from(Environment)}. This is the
 * only place that exposes the adapter, and it does so as a {@code NarrationTaskPort} bean, so
 * no other package needs to name a Codex type.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${engine.inference.codex.transport:disabled}'.trim().toLowerCase() != 'disabled'")
class CodexConfiguration {
    /**
     * @param environment source of {@code engine.inference.codex.*}
     * @param profiles resolves each task's slot against the owner's assignment and the catalog
     * @return the adapter; Spring starts and stops it as a {@code SmartLifecycle}
     */
    @Bean
    CodexTaskAdapter codexTaskAdapter(Environment environment, ProfileResolver profiles) {
        CodexSettings settings = CodexSettings.from(environment);
        return new CodexTaskAdapter(settings, profiles, settings.wireFactory());
    }
}
