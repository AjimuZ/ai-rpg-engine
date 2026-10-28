/**
 * Typed campaign knowledge (P09.1, docs/08 section 4): canonical claims with privileged truth,
 * per-actor beliefs with provenance, observers' last known locations of other actors and
 * unresolved commitments between actors. {@link org.rpgengine.knowledge.KnowledgeChange} values
 * are trusted effects carried by a resolved commit, {@link org.rpgengine.knowledge.KnowledgeLedger}
 * writes them inside the canonical commit transaction, and
 * {@link org.rpgengine.knowledge.KnowledgeView} reads them for exactly one audience. Truth and
 * belief are never merged: a holder's stance is projected without the claim's truth, an actor's
 * real current location is never presented as someone else's knowledge, and a planner assumption
 * is never stored here (plans stay in the preparation records of docs/28).
 */
package org.rpgengine.knowledge;
