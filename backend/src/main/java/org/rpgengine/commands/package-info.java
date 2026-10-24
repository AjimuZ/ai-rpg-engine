/**
 * Operation journal, idempotency identity, the docs/07 state machine and the internal
 * atomic commit/outbox boundary. Accepts trusted resolved data and returns durable
 * receipts; persistence failures roll back the bundle, including mutation leases,
 * random draws and preparation intents (query epochs, critical jobs, plan
 * invalidation). Command API/semantic validation and outbox dispatch remain later
 * tasks. No network work runs here.
 */
package org.rpgengine.commands;
