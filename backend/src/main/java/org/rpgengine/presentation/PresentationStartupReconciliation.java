package org.rpgengine.presentation;

import java.util.Objects;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Reconcile post-commit presentation jobs after persistence initialization and before the web
 * server and the presentation worker start (P07.5). Retires jobs of older recovery generations
 * and interrupts expired attempts; queued work stays queued so missing narration resumes without
 * repeating the action. Contacts no runtime or network service and propagates failures to abort
 * context refresh. Journal recovery leaves operations with a presentation job to this pass.
 */
@Component
public class PresentationStartupReconciliation implements SmartInitializingSingleton {
    private final PresentationJobService jobs;

    /** Require the persistence-backed job service; null wiring fails startup. */
    public PresentationStartupReconciliation(PresentationJobService jobs) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
    }

    /** Run one full pass; any failure prevents readiness. */
    @Override
    public void afterSingletonsInstantiated() {
        jobs.reconcile();
    }
}
