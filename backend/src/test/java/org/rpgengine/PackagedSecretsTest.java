package org.rpgengine;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Load real Boot configuration with neutral temporary secrets and no database.
 * Inputs are isolated file trees; outputs are bound settings or startup failures.
 * Configuration/import/binding errors fail assertions rather than being skipped.
 */
class PackagedSecretsTest {
    @TempDir
    private Path temporary;

    /** Construct a JUnit fixture; temporary-directory injection failures fail setup. */
    PackagedSecretsTest() {
    }

    /** Verify a mounted file supplies the password and container bind address; I/O errors fail. */
    @Test
    void loadsPasswordFromRequiredConfigTree() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("private secrets"));
        // A trailing newline is normal for launcher-created files and must not enter the password.
        Files.writeString(directory.resolve("postgres_password"), "neutral-fixture-password\n");
        try (var context = application().run(arguments(directory))) {
            var settings = context.getBean(Settings.class);
            assertThat(settings.password()).isEqualTo("neutral-fixture-password");
            assertThat(settings.address()).isEqualTo("0.0.0.0");
        }
    }

    /** Require an absent secret mount to abort configuration loading before any server starts. */
    @Test
    void rejectsMissingSecretDirectory() {
        assertThatThrownBy(() -> application().run(arguments(temporary.resolve("missing"))))
                .hasStackTraceContaining("ConfigDataResourceNotFoundException");
    }

    /** Reject an existing mount without its required password file instead of using a default. */
    @Test
    void rejectsMissingPasswordFile() {
        assertThatThrownBy(() -> application().run(arguments(temporary)))
                .hasStackTraceContaining("postgres_password");
    }

    /** Keep host startup independent of container secret mounts and bound to loopback. */
    @Test
    void keepsHostDefaultsWithoutSecretMount() {
        try (var context = application().run(
                "--spring.config.location=classpath:/application.properties",
                "--spring.profiles.active=development",
                "--spring.datasource.password=neutral-host-fixture",
                "--spring.main.banner-mode=off", "--logging.level.root=OFF")) {
            var settings = context.getBean(Settings.class);
            assertThat(settings.password()).isEqualTo("neutral-host-fixture");
            assertThat(settings.address()).isEqualTo("127.0.0.1");
        }
    }

    /** Build a non-web Boot loader with only the binding fixture; startup exceptions propagate. */
    private SpringApplication application() {
        var application = new SpringApplication(SecretConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setEnvironment(new IsolatedEnvironment());
        return application;
    }

    /**
     * Environment without process environment variables. Host CI and developers export
     * SPRING_DATASOURCE_* for the database-backed tests, and those variables outrank the
     * mounted secret file, which would make the file-contract assertions depend on the
     * caller. Command-line arguments and system properties stay available.
     */
    private static final class IsolatedEnvironment extends StandardEnvironment {
        /** Construct the environment; property sources are customized during the super call. */
        IsolatedEnvironment() {
        }

        /** Register system properties only, deliberately skipping the process environment. */
        @Override
        protected void customizePropertySources(MutablePropertySources sources) {
            sources.addLast(new MapPropertySource(
                    SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, getSystemProperties()));
        }
    }

    /** Return isolated config arguments for a directory; a missing tree remains a fatal import. */
    private String[] arguments(Path directory) {
        return new String[] {
                "--spring.config.location=classpath:/application.properties",
                "--spring.profiles.active=packaged",
                "--engine.secrets-directory=" + directory.toAbsolutePath() + "/",
                "--spring.main.banner-mode=off",
                "--logging.level.root=OFF"
        };
    }

    /** Bind only settings needed for the contract, avoiding database or runtime side effects. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SecretConfiguration {
        /** Construct the configuration without inputs, outputs or external side effects. */
        SecretConfiguration() {
        }

        /** Match Boot's strict placeholder binding; absent secret properties must abort startup. */
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        /** Return bound file/address values; unresolved password placeholders abort startup. */
        @Bean
        Settings settings(@Value("${spring.datasource.password}") String password,
                          @Value("${server.address}") String address) {
            return new Settings(password, address);
        }
    }

    /** Hold fixture-only configuration inputs without logging or exporting password contents. */
    record Settings(String password, String address) {
        /** Store injected values; placeholder resolution has already failed if inputs are absent. */
        Settings {
        }
    }
}
