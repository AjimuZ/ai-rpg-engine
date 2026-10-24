/**
 * Operation journal, idempotency identity, the docs/07 state machine and the internal
 * atomic commit/outbox boundary. Accepts trusted resolved data and returns durable
 * receipts; persistence failures roll back the bundle. Command API/semantic validation,
 * outbox dispatch and leases/fencing remain later tasks. No network work runs here.
 */
package org.rpgengine.commands;
