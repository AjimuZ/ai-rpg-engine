package org.rpgengine.inference.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable record of logical AI sessions (P09.4, docs/08 section 7, table {@code ai_session_record}).
 * {@link ScopedSessions} reports every opening, use and retirement here; the in-memory registry
 * stays the authority for which session is live, and renewal triggers use its in-memory counters.
 *
 * <p>Contract for implementations: no method throws for a storage failure. A failure is logged
 * by exception class and the call returns, because inference must never wait for or fail on a
 * diagnostic record. Nothing here writes canonical state, prompt text or runtime references.
 */
public interface SessionJournal {
    /** A journal that records nothing and knows no history (tests and storage-free setups). */
    SessionJournal NONE = new SessionJournal() {
        @Override
        public boolean hasHistory(UUID campaignId, AiRole role, Audience audience) {
            return false;
        }

        @Override
        public void opened(Opened record) {
        }

        @Override
        public void used(UUID sessionId, Usage usage, Instant at) {
        }

        @Override
        public void retired(UUID sessionId, ScopedSessions.Reason reason, Instant at) {
        }

        @Override
        public int recover(Instant at) {
            return 0;
        }
    };

    /**
     * @param campaignId campaign
     * @param role role of the line
     * @param audience audience of the line
     * @return true when the line had any earlier session, in this or an earlier process; false
     *         also when storage could not answer
     */
    boolean hasHistory(UUID campaignId, AiRole role, Audience audience);

    /**
     * Record a newly opened session. The implementation numbers it after the line's newest record
     * and names that record as its predecessor; a line without records gets cause {@code FIRST}
     * and rehydration {@code NONE} whatever was supplied.
     *
     * @param record what the session was opened under and rehydrated from
     */
    void opened(Opened record);

    /**
     * Record the session's counters after a settled task. Values are absolute and never decrease.
     *
     * @param sessionId session identity
     * @param usage current counters
     * @param at settlement time
     */
    void used(UUID sessionId, Usage usage, Instant at);

    /**
     * Mark a session retired. Idempotent: an already retired record keeps its first reason.
     *
     * @param sessionId session identity
     * @param reason why it ended
     * @param at retirement time
     */
    void retired(UUID sessionId, ScopedSessions.Reason reason, Instant at);

    /**
     * Startup: retire every record still marked active, with reason {@code RESTART}. The runtime
     * adapter forgets its conversations at restart, so none of those sessions can continue.
     *
     * @param at recovery time
     * @return number of records retired
     */
    int recover(Instant at);

    /**
     * Counters of one session.
     *
     * @param turns runtime turns that may have run (result with provenance, or unconfirmed)
     * @param completed completed tasks, at most {@code turns}
     * @param contextUnits estimated accumulated conversation size
     * @param warned whether the pressure warning threshold was crossed
     */
    record Usage(int turns, int completed, long contextUnits, boolean warned) {
    }

    /**
     * One opened session.
     *
     * @param sessionId fresh logical session identity
     * @param scope its scope
     * @param cause why it was opened: {@code FIRST} or the retirement reason of its predecessor
     *        (a {@link ScopedSessions.Reason} name, or {@code RESTART})
     * @param rehydration the rehydration outcome, or null when none was attempted
     * @param basis recorded basis: NONE, SUMMARY, CHECKPOINT, STATE, UNAVAILABLE or OMITTED
     * @param code fixed reason of UNAVAILABLE, or null
     * @param at opening time
     */
    record Opened(UUID sessionId, SessionScope scope, String cause, Rehydrator.Ready rehydration, String basis,
            String code, Instant at) {
        /** @throws NullPointerException for a missing component */
        public Opened {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(at, "at");
        }
    }
}
