package org.rpgengine.preparation;

/**
 * Lifecycle states of a durable preparation job (docs/28 section 5), identical to the
 * V6 {@code ai_job.status} check. The database trigger {@code guard_ai_job} is the
 * authority for allowed transitions; this enum only gives callers a typed view.
 */
public enum JobStatus {
    /** Waiting for a claim; carries no lease. */
    QUEUED,
    /** Claimed by one holder under a fresh fence; the attempt row exists. */
    LEASED,
    /** The holder started external work and recorded its runtime references. */
    RUNNING,
    /** Cancellation was requested while leased; the holder must confirm it stopped. */
    CANCEL_REQUESTED,
    /** A result digest was recorded for the active attempt and awaits admission. */
    RESULT_RECEIVED,
    /** Admission is checking the result; visible only inside that transaction. */
    VALIDATING,
    /** Terminal: exactly one artifact was admitted for the job. */
    COMPLETED,
    /** Terminal: a newer generation or plan made the job pointless. */
    SUPERSEDED,
    /** Terminal: cancelled before or after confirmed stop. */
    CANCELLED,
    /** Terminal: attempts exhausted or a non-retryable failure. */
    FAILED,
    /** Terminal: the job's deadline passed before it could run. */
    EXPIRED,
    /** A lease ended without proof that upstream work stopped; needs explicit confirmation. */
    RECONCILIATION_REQUIRED
}
