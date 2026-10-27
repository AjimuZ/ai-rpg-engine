package org.rpgengine.commands;

import java.util.Objects;

/**
 * Expected outcome of command admission, common semantic validation or operation lookup. The message
 * is fixed per kind and never contains caller input, SQL or row identities, so adapters
 * can map {@link #kind()} to a stable public code. Every failure escapes the calling
 * admission or canonical transaction: no journal row, lease change or recovery write survives a rejection.
 * Busy/limit outcomes can be retried once capacity is available; stale/conflicting
 * commands require explicit correction rather than automatic submission against new state.
 */
public final class OperationAdmissionFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Kind kind;

    /**
     * Create one failure.
     *
     * @param kind stable classification; null fails immediately
     */
    OperationAdmissionFailure(Kind kind) {
        super("operation admission failed: " + Objects.requireNonNull(kind, "kind"), null, false, false);
        this.kind = kind;
    }

    /** @return stable classification used for HTTP status/code mapping */
    public Kind kind() {
        return kind;
    }

    /** Admission and lookup outcomes from docs/07 §3 and docs/13 §3. */
    public enum Kind {
        /** Owner, adventure, campaign or operation is missing or belongs to another scope (HTTP 404). */
        SCOPE_UNAVAILABLE,
        /** The request identity was already used for a different canonical command (HTTP 409). */
        REQUEST_CONFLICT,
        /** {@code expectedRevision} differs from the committed campaign revision (HTTP 409). */
        STALE_REVISION,
        /** Another operation holds the live campaign mutation lease; nothing is queued (HTTP 409). */
        CAMPAIGN_BUSY,
        /** The owner already runs the maximum number of concurrently leased operations (HTTP 429). */
        ADMISSION_LIMIT,
        /** Unknown type, malformed typed payload or attempted generic effect/field patch (HTTP 422). */
        INVALID_COMMAND,
        /** No registered intention is eligible in the current scheduling mode (HTTP 422). */
        MODE_ILLEGAL,
        /** The stored pending decision has no supported input contract (HTTP 422). */
        DECISION_UNAVAILABLE,
        /** Missing creation player or missing/foreign action recipient (HTTP 422). */
        ACTOR_UNAVAILABLE
    }
}
