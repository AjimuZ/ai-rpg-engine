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
 * evidence and repeat semantic checks inside canonical commit. Detailed resource,
 * prerequisite and knowledge rules, execution workers, public receipt projection and
 * outbox dispatch remain later tasks. No network work runs here.
 */
package org.rpgengine.commands;
