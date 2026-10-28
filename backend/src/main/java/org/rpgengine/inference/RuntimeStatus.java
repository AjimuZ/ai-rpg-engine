package org.rpgengine.inference;

import java.util.Objects;

/**
 * Point-in-time, non-sensitive readiness of the selected runtime, for health and status
 * projections. It never contains account identity, endpoints or model names.
 *
 * @param runtime runtime name ({@code none} when no runtime is configured)
 * @param state lifecycle state
 * @param reasonCode fixed code explaining a non-ready state, or null when ready
 * @param queued tasks waiting for the active permit
 * @param active turns currently holding the permit, including work whose outcome is unconfirmed and
 *        awaits reconciliation (0 or 1 under the serialized default); the P08.6 scheduler keeps
 *        its turn permit while this is non-zero after an uncertain outcome
 */
public record RuntimeStatus(String runtime, State state, String reasonCode, int queued, int active) {
    /** Lifecycle states of a task port. */
    public enum State {
        /** No runtime is configured; tasks end UNAVAILABLE. */
        DISABLED,
        /** Connecting and performing the handshake; tasks queue. */
        STARTING,
        /** Connected, compatible and signed in; tasks run. */
        READY,
        /** Not usable now (sign-in, version, connection, pending reconciliation); tasks end UNAVAILABLE. */
        UNAVAILABLE,
        /** Shutdown in progress; admissions are closed. */
        STOPPING,
        /** Shut down. */
        STOPPED
    }

    /** Require the runtime name and state; counts must be non-negative. */
    public RuntimeStatus {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(state, "state");
        if (queued < 0 || active < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
    }
}
