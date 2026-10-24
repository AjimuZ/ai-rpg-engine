package org.rpgengine.commands;

import java.util.Objects;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Run recovery after persistence initialization and before lifecycle/server startup.
 * Takes no external input, produces no payload/log output, and propagates failures
 * to abort context refresh. No runtime account or network service is contacted.
 */
@Component
public class JournalStartupReconciliation implements SmartInitializingSingleton {
    private final JournalRecoveryService recovery;

    /** Require the persistence-backed recovery service; null wiring fails startup. */
    public JournalStartupReconciliation(JournalRecoveryService recovery) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    /** Reconcile before SmartLifecycle starts the web server; any failure prevents readiness. */
    @Override
    public void afterSingletonsInstantiated() {
        recovery.reconcile();
    }
}
