/**
 * Item ownership as resolved, checked and written by canonical commits (P10.2). {@code InventoryModel}
 * is the pure working copy a resolver edits (consume, transfer, merge, create); {@code ItemPlan}
 * is the immutable result a commit receives; {@code InventoryWriter} applies it inside the commit's
 * transaction with stale-state guards, a per-definition unit conservation check and the
 * append-only {@code item_creation} ledger that links every created unit to its source.
 */
package org.rpgengine.inventory;
