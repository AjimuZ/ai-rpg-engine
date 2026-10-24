package org.rpgengine.preparation;

/**
 * Visibility class of planning input and query dependencies (docs/28 section 7). A
 * planner-private read may include hidden state and must never feed public prose
 * without a separate public projection; the value is stored with every snapshot and
 * query dependency so admission and invalidation keep the two apart.
 */
public enum Audience {
    /** May include hidden state; for planning only. */
    PLANNER_PRIVATE,
    /** Projection safe for the player-visible narration. */
    PUBLIC
}
