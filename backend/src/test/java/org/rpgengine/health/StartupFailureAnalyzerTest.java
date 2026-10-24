package org.rpgengine.health;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the startup failure messages; no database or server is needed.
 * Verifies classification by SQLState, nested causes, secrecy and the unrecognized case.
 */
class StartupFailureAnalyzerTest {
    private final StartupFailureAnalyzer analyzer = new StartupFailureAnalyzer();

    /** Construct the fixture; nothing can fail. */
    StartupFailureAnalyzerTest() {
    }

    /** Wrap a SQL failure like Boot does (two levels deep) and analyze it. */
    private FailureAnalysis analyze(String state, String message) {
        var sql = new SQLException(message, state);
        return analyzer.analyze(new IllegalStateException("outer", new RuntimeException(sql)));
    }

    /** Rejected credentials are described without echoing the driver message. */
    @Test
    void explainsRejectedCredentialsWithoutLeakingMessage() {
        var result = analyze("28P01", "password authentication failed for user \"secret-user\"");
        assertNotNull(result);
        assertTrue(result.getDescription().contains("credentials"));
        assertFalse(result.getDescription().contains("secret-user"));
        assertFalse(result.getAction().contains("secret-user"));
    }

    /** Connection-class and shutdown-class SQLStates mean the database is unreachable. */
    @Test
    void explainsUnreachableDatabase() {
        assertTrue(analyze("08001", "refused").getDescription().contains("cannot be reached"));
        assertTrue(analyze("57P03", "starting up").getDescription().contains("cannot be reached"));
    }

    /** Unrecognized failures fall through so other analyzers can describe them. */
    @Test
    void ignoresUnrelatedFailures() {
        assertNull(analyze("23505", "duplicate"));
        assertNull(analyzer.analyze(new IllegalStateException("unrelated")));
    }
}
