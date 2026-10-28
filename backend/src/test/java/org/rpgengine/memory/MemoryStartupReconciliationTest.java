package org.rpgengine.memory;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.rpgengine.content.CampaignDependencies;
import org.rpgengine.content.ClosureDiagnosis;
import org.rpgengine.content.PackRegistry;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure checks of the P09.5 startup pass wiring (docs/08 section 10): it starts after every
 * initialization callback but before the embedded web server and the background workers, runs
 * exactly once per start when enabled, never when disabled, and a failing pass never aborts startup.
 * The pass itself is proven against PostgreSQL in {@code MemoryContinuityTest}.
 */
class MemoryStartupReconciliationTest {
    /** Construct the pure fixture. */
    MemoryStartupReconciliationTest() { }

    /** The phase orders the pass before HTTP and before the workers' default phase. */
    @Test
    void runsBeforeTheWebServerAndTheWorkers() {
        assertTrue(MemoryStartupReconciliation.PHASE < WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE);
        assertTrue(MemoryStartupReconciliation.PHASE < SmartLifecycle.DEFAULT_PHASE);
        assertEquals(MemoryStartupReconciliation.PHASE, new MemoryStartupReconciliation(counting(null), true).getPhase());
    }

    /** One pass per start when enabled; none when disabled; a failure is logged, not thrown. */
    @Test
    void runsOncePerStartAndNeverAbortsStartup() {
        var calls = new AtomicInteger();
        var enabled = new MemoryStartupReconciliation(counting(calls), true);
        enabled.start();
        enabled.start();
        assertTrue(enabled.isRunning());
        assertEquals(1, calls.get(), "a running pass is not repeated");
        assertNotNull(enabled.lastReport());
        enabled.stop();
        assertFalse(enabled.isRunning());
        enabled.start();
        assertEquals(2, calls.get(), "a restarted context runs it again");

        var disabled = new MemoryStartupReconciliation(counting(calls), false);
        disabled.start();
        assertTrue(disabled.isRunning());
        assertEquals(2, calls.get());
        assertNull(disabled.lastReport());

        var failing = new MemoryStartupReconciliation(failing(), true);
        assertDoesNotThrow(failing::start);
        assertTrue(failing.isRunning());
        assertNull(failing.lastReport());
    }

    /** A clean diagnosis never pauses, a missing closure must name its issues. */
    @Test
    void diagnosisInvariants() {
        assertFalse(ClosureDiagnosis.AVAILABLE.pausesMutation());
        assertFalse(ClosureDiagnosis.UNSEALED.pausesMutation());
        assertThrows(IllegalArgumentException.class, () -> new ClosureDiagnosis(ClosureDiagnosis.Status.MISSING,
                java.util.List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ClosureDiagnosis(ClosureDiagnosis.Status.AVAILABLE,
                java.util.List.of(new ClosureDiagnosis.Issue(ClosureDiagnosis.Code.CONTENT_CORRUPT, "a", "1.0.0"))));
    }

    /** @return a pass that counts its runs and reports an empty installation */
    private static MemoryReconciliation counting(AtomicInteger calls) {
        return new MemoryReconciliation(new JdbcTemplate(), NO_TRANSACTIONS, dependencies(), checkpoints(), summaries()) {
            @Override
            public Report reconcile() {
                if (calls != null) {
                    calls.incrementAndGet();
                }
                return new Report(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            }
        };
    }

    /** @return a pass whose storage is unavailable */
    private static MemoryReconciliation failing() {
        return new MemoryReconciliation(new JdbcTemplate(), NO_TRANSACTIONS, dependencies(), checkpoints(), summaries()) {
            @Override
            public Report reconcile() {
                throw new IllegalStateException("storage unavailable");
            }
        };
    }

    private static CampaignDependencies dependencies() {
        return new CampaignDependencies(new PackRegistry(new JdbcTemplate(), NO_TRANSACTIONS), new JdbcTemplate());
    }

    private static OperationalCheckpoints checkpoints() {
        return new OperationalCheckpoints(new JdbcTemplate(), NO_TRANSACTIONS, Clock.systemUTC());
    }

    private static MemorySummaries summaries() {
        return new MemorySummaries(new JdbcTemplate(), NO_TRANSACTIONS, MemorySettings.defaults(), Clock.systemUTC());
    }

    /** A transaction manager that is never used by these doubles. */
    private static final PlatformTransactionManager NO_TRANSACTIONS = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            throw new UnsupportedOperationException("no storage in a pure test");
        }

        @Override
        public void commit(TransactionStatus status) {
            throw new UnsupportedOperationException("no storage in a pure test");
        }

        @Override
        public void rollback(TransactionStatus status) {
            throw new UnsupportedOperationException("no storage in a pure test");
        }
    };
}
