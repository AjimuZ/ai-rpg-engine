package org.rpgengine.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P09.5 wiring against the real application context and schema: with the startup switch on (the
 * production default; the shared test configuration turns it off), the restart reconciliation pass
 * runs while the context starts, over whatever the test database holds, and reports no failure.
 */
@SpringBootTest(properties = "engine.memory.reconciliation.enabled=true")
class MemoryStartupPassTest {
    @Autowired private MemoryStartupReconciliation startup;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    MemoryStartupPassTest() { }

    /** The pass has already run when the context is ready. */
    @Test
    void theStartupPassRunsBeforeTheContextIsReady() {
        assertTrue(startup.isRunning());
        var report = startup.lastReport();
        assertNotNull(report, "the pass ran during context start");
        assertEquals(0, report.failures());
    }
}
