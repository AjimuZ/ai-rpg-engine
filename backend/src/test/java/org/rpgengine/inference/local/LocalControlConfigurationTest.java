package org.rpgengine.inference.local;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ModelServerControl;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The local model server's control exists only with both the server URL and the admission switch, and
 * building it contacts no server.
 */
class LocalControlConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalControlConfiguration.class);

    @Test
    void bothSettingsBuildTheLocalControl() {
        runner.withPropertyValues("engine.inference.local.url=http://127.0.0.1:9",
                "engine.inference.admission.enabled=true").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModelServerControl.class);
                    assertThat(context.getBean(ModelServerControl.class)).isInstanceOf(LocalModelControl.class);
                });
    }

    @Test
    void theUrlAloneOrTheSwitchAloneBuildsNothing() {
        runner.withPropertyValues("engine.inference.local.url=http://127.0.0.1:9")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ModelServerControl.class));
        runner.withPropertyValues("engine.inference.admission.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ModelServerControl.class));
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ModelServerControl.class));
    }

    @Test
    void aBlankUrlOrAFalseSwitchBuildsNothing() {
        runner.withPropertyValues("engine.inference.local.url= ", "engine.inference.admission.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ModelServerControl.class));
        runner.withPropertyValues("engine.inference.local.url=http://127.0.0.1:9",
                "engine.inference.admission.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ModelServerControl.class));
    }

    @Test
    void anInvalidUrlFailsStartupWithoutEchoingIt() {
        runner.withPropertyValues("engine.inference.local.url=ftp://private-host", "engine.inference.admission.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("engine.inference.local.url must be")
                        .satisfies(failure -> assertThat(failure.toString()).doesNotContain("private-host")));
    }
}
