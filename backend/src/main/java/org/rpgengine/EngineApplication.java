package org.rpgengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstrap the modular engine shell without adventure content or runtime access.
 * Configuration comes from Spring's environment; startup failures abort the process.
 */
@SpringBootApplication
public class EngineApplication {
    /** Construct the framework entry point without inputs, output, or side effects. */
    public EngineApplication() {
    }

    /**
     * Start the HTTP application from command-line configuration arguments.
     * Returns on successful startup; invalid configuration or database failures propagate.
     *
     * @param args Spring Boot command-line configuration arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(EngineApplication.class, args);
    }
}
