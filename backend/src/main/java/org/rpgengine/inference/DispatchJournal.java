package org.rpgengine.inference;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Write-ahead record of where a task is about to run (P08.6, docs/06 section 12). A task port
 * that can leave work running in an external runtime calls {@link #recordStart} with an opaque
 * reference to that work immediately before it asks the runtime to start the task, and starts
 * nothing when the record fails. After a backend restart the scheduler hands the recorded
 * references back through {@link NarrationTaskPort#reconcile}, so the port can confirm that
 * nothing still runs under them before it starts any further work.
 *
 * <p>The reference is opaque to every caller: for the Codex adapter it is the runtime thread
 * identifier. It is stored only in the scheduler's internal ledger and is never projected,
 * exported or shown. Implementations are called on the port's own worker thread and must return
 * promptly (one short database write).
 */
@FunctionalInterface
public interface DispatchJournal {
    /** Accepted references: 1–256 printable characters without whitespace or control characters. */
    Pattern REFERENCE = Pattern.compile("[\\x21-\\x7e]{1,256}");

    /** Journal used when no scheduler is attached: records nothing and permits every start. */
    DispatchJournal NONE = (taskId, reference) -> true;

    /**
     * Durably record that the task {@code taskId} is about to run under {@code reference}.
     *
     * @param taskId identity of the task the port is about to start
     * @param reference opaque runtime reference matching {@link #REFERENCE}
     * @return true when the record is durable (or nothing needs recording); false when the port
     *         must not start the task, which then ends without a runtime turn
     */
    boolean recordStart(UUID taskId, String reference);
}
