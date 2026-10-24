package org.rpgengine.health;

import org.flywaydb.core.Flyway;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Storage-readiness component "schema": the database schema must match the migrations
 * shipped in this build (all applied, none pending, checksums valid).
 * Outputs DOWN for pending/invalid migrations or any Flyway/database error, otherwise UP.
 * It never throws and never reports exception text, because messages can contain
 * connection URLs; operators read the launcher or the log for the cause.
 */
@Component("schema")
public class SchemaHealthIndicator implements HealthIndicator {
    private final Flyway flyway;

    /**
     * @param flyway the migration engine configured against the application database
     */
    public SchemaHealthIndicator(Flyway flyway) {
        this.flyway = flyway;
    }

    /** Validate without modifying the database; any failure is mapped to DOWN. */
    @Override
    public Health health() {
        try {
            boolean valid = flyway.validateWithResult().validationSuccessful;
            boolean current = flyway.info().pending().length == 0;
            return valid && current ? Health.up().build() : Health.down().build();
        } catch (RuntimeException e) {
            // Unreachable database or broken history: not ready, but the probe must not fail.
            return Health.down().build();
        }
    }
}
