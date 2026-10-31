package org.rpgengine.inference.local;

import java.util.List;
import org.rpgengine.inference.NarrationTaskPort;
import org.rpgengine.inference.ProfileResolver;
import org.rpgengine.inference.SlotRoutingTaskPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Selects the local adapter when {@code engine.inference.local.url} is set, and then puts the slot
 * routing port in front of every adapter as the installation's task port. Without the setting nothing
 * here exists and exactly one port stays selected, as before. This is the only place that exposes the
 * adapter, so no other package needs to name a type of this package.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(LocalConfiguration.Configured.class)
class LocalConfiguration {
    /** A blank value counts as unset. Read here rather than in an expression, which could echo the value. */
    static final class Configured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String url = context.getEnvironment().getProperty(LocalSettings.PREFIX + "url");
            return url != null && !url.isBlank();
        }
    }

    /**
     * @param environment source of {@code engine.inference.local.*}
     * @param profiles resolves each task's slot against the owner's assignment and the server's list
     * @return the adapter; Spring starts and stops it as a {@code SmartLifecycle}
     */
    @Bean
    LocalTaskAdapter localTaskAdapter(Environment environment, ProfileResolver profiles) {
        return new LocalTaskAdapter(LocalSettings.from(environment), profiles);
    }

    /**
     * @param adapters every other task port of this installation; Spring leaves this bean out of its own list
     * @param profiles names the backend assigned to a task's slot
     * @return the port the engine submits to
     */
    @Bean
    @Primary
    SlotRoutingTaskPort slotRoutingTaskPort(List<NarrationTaskPort> adapters, ProfileResolver profiles) {
        return new SlotRoutingTaskPort(adapters, profiles);
    }
}
