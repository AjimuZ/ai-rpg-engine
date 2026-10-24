package org.rpgengine.campaign;

/**
 * Visibility classification of a domain event (docs/05 section 6). Player projections
 * may only be built from public events; internal events stay inside the engine.
 */
public enum EventVisibility {
    /** May feed player-facing projections once committed. */
    PUBLIC,
    /** Engine-only bookkeeping or hidden facts; never projected to players directly. */
    INTERNAL
}
