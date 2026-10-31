package org.rpgengine.inference.admission;

/** Outcome of submitting a request to the coordinator. */
public enum Submission {
    /** The request waits for admission. */
    ACCEPTED,
    /** The request's demand exceeds the whole budget, so it could never be admitted. */
    EXCEEDS_CAPACITY,
    /** A request with this identity is already waiting or holds a lease. */
    DUPLICATE
}
