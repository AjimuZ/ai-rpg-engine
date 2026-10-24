package org.rpgengine.preparation;

import java.util.Objects;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Reconcile preparation job leases after persistence initialization and before the
 * web server starts, so no claim or admission runs against unreconciled leases.
 * Takes no external input, contacts no runtime or network service and propagates
 * failures to abort context refresh. Independent of journal recovery: neither reads
 * rows the other writes.
 */
@Component
public class JobStartupReconciliation implements SmartInitializingSingleton {
    private final JobRecoveryService recovery;

    /** Require the persistence-backed recovery service; null wiring fails startup. */
    public JobStartupReconciliation(JobRecoveryService recovery) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    /** Run one full pass; any failure prevents readiness. */
    @Override
    public void afterSingletonsInstantiated() {
        recovery.reconcile();
    }
}
