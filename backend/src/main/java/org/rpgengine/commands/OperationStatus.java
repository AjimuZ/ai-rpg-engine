package org.rpgengine.commands;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Lifecycle states of a journaled operation, exactly as drawn in docs/07 section 2.
 * Each constant lists its allowed successors; anything else is an illegal transition.
 *
 * <p>Statuses at or after {@link #COMMITTED} mean canonical state has changed, so the
 * operation can no longer be cancelled or fail "before commit"; narration problems
 * end in {@link #COMMITTED_NARRATION_PENDING} instead of rolling anything back.
 *
 * <p>P07.5 adds two edges to the original diagram: {@code ACCEPTED -> CANCELLED}, because an
 * admitted operation can be cancelled before its prepare step starts (docs/07 section 8,
 * "before planning completes"), and {@code COMMITTED -> COMMITTED_NARRATION_PENDING}, because
 * a presentation job can be stopped or retired before narration starts. Neither edge can
 * undo or skip a committed effect.
 */
public enum OperationStatus {
    /** Received and journaled under its idempotency key. */
    ACCEPTED,
    /** Interpreting the command into a typed plan. */
    PLANNING,
    /** Waiting for the owner to answer a typed clarification (terminal for this operation). */
    NEEDS_CLARIFICATION,
    /** Checking the plan against rules and the base revision. */
    VALIDATING,
    /** Validation refused the plan; no state changed. */
    REJECTED,
    /** Writing state, events and receipt in one transaction (P03.3). */
    COMMITTING,
    /** Canonical state changed; the receipt is durable. */
    COMMITTED,
    /** Producing narration for committed facts. */
    NARRATING,
    /** Narration delivered. */
    DELIVERED,
    /** State is committed but narration is still owed and can be retried. */
    COMMITTED_NARRATION_PENDING,
    /** Owner cancelled before commit; no state changed. */
    CANCELLED,
    /** Infrastructure failure before commit; no state changed. */
    FAILED_BEFORE_COMMIT;

    /**
     * @return the statuses this one may move to; empty for terminal statuses
     */
    public Set<OperationStatus> successors() {
        // A switch keeps the table next to the enum without forward-reference issues.
        return switch (this) {
            case ACCEPTED -> EnumSet.of(PLANNING, CANCELLED, FAILED_BEFORE_COMMIT);
            case PLANNING -> EnumSet.of(NEEDS_CLARIFICATION, VALIDATING, CANCELLED, FAILED_BEFORE_COMMIT);
            case VALIDATING -> EnumSet.of(REJECTED, COMMITTING, CANCELLED, FAILED_BEFORE_COMMIT);
            case COMMITTING -> EnumSet.of(COMMITTED);
            case COMMITTED -> EnumSet.of(NARRATING, COMMITTED_NARRATION_PENDING);
            case NARRATING -> EnumSet.of(DELIVERED, COMMITTED_NARRATION_PENDING);
            case NEEDS_CLARIFICATION, REJECTED, DELIVERED, COMMITTED_NARRATION_PENDING, CANCELLED,
                    FAILED_BEFORE_COMMIT -> EnumSet.noneOf(OperationStatus.class);
        };
    }

    /**
     * @param next candidate successor
     * @return true if docs/07 allows moving from this status to {@code next}
     */
    public boolean canTransitionTo(OperationStatus next) {
        return successors().contains(Objects.requireNonNull(next, "next"));
    }

    /** @return true if no further transition is allowed */
    public boolean isTerminal() {
        return successors().isEmpty();
    }

    /** @return true if canonical state has been committed for this operation */
    public boolean isCommitted() {
        return this == COMMITTED || this == NARRATING || this == DELIVERED
                || this == COMMITTED_NARRATION_PENDING;
    }
}
