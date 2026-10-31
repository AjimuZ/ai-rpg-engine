package org.rpgengine.inference.slots;

/** Outcome class of a readiness probe, shown beside each slot. */
public enum ProbeState {
    /** No probe has run for the current assignment; nothing is claimed about readiness. */
    NOT_RUN,
    /** The backend answered and accepted the assigned model. */
    READY,
    /** The backend was reached but cannot serve the assignment (for example the model is not approved). */
    FAILED,
    /** The backend could not be reached, or no adapter for it is installed. */
    UNREACHABLE
}
