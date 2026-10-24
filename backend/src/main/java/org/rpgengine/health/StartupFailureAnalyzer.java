package org.rpgengine.health;

import java.sql.SQLException;

import org.flywaydb.core.api.exception.FlywayValidateException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turn the most common storage startup failures into an actionable message instead of
 * a stack trace: database unreachable, rejected credentials, and schema/migration
 * mismatch. Returns null for any other failure so Boot's own analyzers still apply.
 * Messages are fixed text: exception messages are never echoed, as they may carry
 * connection URLs or user names. Cause-chain search uses the inherited cycle-safe findCause. Secret-tree problems are covered by Boot's own
 * config-data analyzer and the launcher.
 */
public class StartupFailureAnalyzer extends AbstractFailureAnalyzer<Exception> {
    /** Construct the analyzer; Boot instantiates it from META-INF/spring.factories. */
    public StartupFailureAnalyzer() {
    }

    /**
     * @param rootFailure the outermost startup failure
     * @param cause the failure matched by the generic type (same as rootFailure)
     * @return a description with an action, or null when the cause is not recognized
     */
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, Exception cause) {
        FlywayValidateException schema = findCause(rootFailure, FlywayValidateException.class);
        if (schema != null) {
            return new FailureAnalysis(
                    "The database schema does not match the migrations in this build.",
                    "Start the build that created this database, or restore a backup. "
                            + "Never edit applied migrations or clean the database by hand; "
                            + "see docs/22-container-deployment-and-startup.md.", schema);
        }
        SQLException sql = findCause(rootFailure, SQLException.class);
        if (sql == null) {
            return null;
        }
        String state = sql.getSQLState() == null ? "" : sql.getSQLState();
        if (state.startsWith("28")) {
            return new FailureAnalysis("PostgreSQL rejected the configured credentials.",
                    "Check that the password secret file matches the one the database volume "
                            + "was initialized with. The password is never printed.", sql);
        }
        if (state.startsWith("08") || state.startsWith("57")) {
            return new FailureAnalysis("The PostgreSQL database cannot be reached.",
                    "Check that the database service is healthy and that "
                            + "SPRING_DATASOURCE_URL points to it, then start again.", sql);
        }
        return null;
    }
}
