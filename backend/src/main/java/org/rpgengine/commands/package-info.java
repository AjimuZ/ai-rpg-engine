/**
 * Durable owner-scoped command admission, payload identity and operation status, plus
 * the docs/07 lifecycle and internal canonical commit/outbox boundary. Admission hashes
 * bounded JSON, validates a closed typed intention against current mode/decision/player
 * eligibility and atomically journals an operation with a fenced campaign permit;
 * all grants/renewals share owner-wide database accounting. Status/replay expose no
 * worker capability and create no world effects. Trusted resolved data commits world
 * changes, immutable receipt/draw references, lease release and preparation intents
 * together; persistence failure rolls back that bundle. Previously reserved draws stay
 * durable independently. Registered player commands require hash-bound immutable typed
 * evidence and repeat semantic checks inside canonical commit. Current-state rules
 * (P07.3) and local execution with durable draws and player-safe receipts (P07.4) are
 * implemented. P07.5 adds owner cancellation decided by the campaign lock, the
 * equivalent-attempt draw stream that prevents reroll fishing, and the read side of the
 * resumable public stream whose messages the V11 journal trigger appends; post-commit
 * presentation jobs live in {@code org.rpgengine.presentation}. No network work runs here.
 */
package org.rpgengine.commands;
