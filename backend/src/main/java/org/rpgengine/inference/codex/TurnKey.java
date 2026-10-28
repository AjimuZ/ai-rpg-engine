package org.rpgengine.inference.codex;

/**
 * Exact identity of one runtime turn. Events are correlated by both values: a delayed event
 * of an earlier turn on the same thread must never complete a newer one (docs/06 section 5).
 *
 * @param threadId runtime thread identifier
 * @param turnId runtime turn identifier
 */
record TurnKey(String threadId, String turnId) {
}
