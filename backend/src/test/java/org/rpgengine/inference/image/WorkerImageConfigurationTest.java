package org.rpgengine.inference.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageModelControl;
import org.rpgengine.inference.ModelServerControl;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The worker's control and port exist only with both the worker's address and the admission switch, and building them
 * contacts nothing. An {@code a1111-http} adapter never provides a control.
 */
class WorkerImageConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WorkerImageConfiguration.class);

    @Test
    void bothSettingsBuildTheControlAndThePortAndContactNothing() throws IOException {
        try (ScriptedImageWorker worker = new ScriptedImageWorker()) {
            runner.withPropertyValues("engine.inference.image-worker.url=" + worker.url(),
                    "engine.inference.admission.enabled=true").run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(ImageModelControl.class).hasSingleBean(ImageGenerationPort.class);
                        assertThat(context.getBean(ImageModelControl.class).control()).isInstanceOf(WorkerModelControl.class);
                        assertThat(context.getBean(ImageGenerationPort.class).backend()).isEqualTo("local-image");
                        // The text lookup is by this neutral type, so the worker must not answer it.
                        assertThat(context).doesNotHaveBean(ModelServerControl.class);
                        assertThat(worker.seen()).isEmpty();
                    });
        }
    }

    @Test
    void theAddressAloneOrTheSwitchAloneBuildsNothing() {
        runner.withPropertyValues("engine.inference.image-worker.url=http://127.0.0.1:9").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ImageModelControl.class).doesNotHaveBean(ImageGenerationPort.class);
        });
        runner.withPropertyValues("engine.inference.admission.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ImageModelControl.class).doesNotHaveBean(ImageGenerationPort.class);
        });
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ImageModelControl.class).doesNotHaveBean(ImageGenerationPort.class);
        });
    }

    @Test
    void aBlankAddressOrAFalseSwitchBuildsNothing() {
        runner.withPropertyValues("engine.inference.image-worker.url= ", "engine.inference.admission.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ImageModelControl.class));
        runner.withPropertyValues("engine.inference.image-worker.url=http://127.0.0.1:9",
                "engine.inference.admission.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ImageModelControl.class));
    }

    @Test
    void anInvalidAddressFailsStartupWithoutEchoingIt() {
        for (String url : new String[] {"ftp://private-host", "http://private-host/path", "http://user@private-host",
                "not a url private-host"}) {
            runner.withPropertyValues("engine.inference.image-worker.url=" + url, "engine.inference.admission.enabled=true")
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .hasStackTraceContaining("engine.inference.image-worker.url must be")
                            .satisfies(failure -> assertThat(failure.toString()).doesNotContain("private-host")));
        }
    }

    @Test
    void anA1111AdapterProvidesNoControlWhateverElseIsSet() {
        runner.withBean(ImageGenerationPort.class, () -> new A1111ImageAdapter(A1111Settings.of("http://127.0.0.1:9", "forge")))
                .withPropertyValues("engine.inference.admission.enabled=true").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ImageModelControl.class).doesNotHaveBean(ModelServerControl.class);
                    assertThat(context.getBean(ImageGenerationPort.class).backend()).isEqualTo("a1111-http");
                });
        assertThat(ModelServerControl.class.isAssignableFrom(A1111ImageAdapter.class)).isFalse();
    }
}
