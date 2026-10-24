# ADR 0003: Backend-Owned Canonical Transactions

**Status:** Proposed. **Date:** 22 October 2026.

## Context

The primary project correctly separates narration from authoritative rules. The demo lets AI edit a Markdown save and validates selected invariants afterwards, leaving partial-write and side-asset recovery concerns.

## Decision

Accept typed intentions and content proposals, never arbitrary field patches or AI database writes. Validate and commit canonical state, events, reward settlements, operation receipt, and outbox together. Use durable idempotency keys with payload hashes, expected revisions, per-campaign leases/fencing, and backend-owned random draws. Do not hold a transaction open during AI work.

Narration follows the resolved event bundle. Missing narration is recoverable presentation work, not a reason to repeat the action. Queries and transcript records do not advance world counters.

## Alternatives

Markdown-as-live-state is simple but weak for concurrency and cross-entity invariants. Fully event-sourced reconstruction would require a larger versioned-reducer commitment than the initial engine needs.

## Consequences

More foundational persistence work is required before real AI mutations. In exchange, retries, interruption, UI reconnect, and campaign recovery become well-defined. The event log is auditable without promising universal replay across arbitrary versions.

P03.2 storage decision (24 October 2026): use a separate JPA `@Version`/`row_version`
on mutable rows rather than reusing `stateRevision`, `operationVersion` or a
presentation generation. Multiple journal transitions can be flushed together and
presentation updates must leave canonical counters unchanged. PostgreSQL requires
one version step on UPDATE; SQL writers must also compare the expected token and
reject a zero-row update. Composite foreign keys, frozen scope/provenance columns,
resource bounds and write-once complete journal outcomes guard independent clients.
The full lifecycle is validated in Java. These constraints are foundations for,
and do not replace, the short atomic transaction and lease/fencing checks of P03.3/P03.5.

## Verification

P03.4 decision (24 October 2026): reserve immutable operation/ordinal draws in their
own bounded transaction before canonical commit. Use lazy private 256-bit campaign
seed material and explicitly framed HMAC-SHA-256 v1 with rejection sampling, rather
than JDK generator implementation/cursor state. This gives portable test vectors and
independent operation draws without changing canonical counters. Persist seed bytes
and algorithm/version with the reservation ledger; never derive a secret from the
public campaign seed label. Canonical receipts read stored reservations under the
campaign lock, while a failed world commit retains the earlier draws. There is no
speculative-planner draw API. Later command policy must prevent equivalent unresolved
attempts from acquiring new operation identities solely to reroll.

P03.3 implementation decision (24 October 2026): the internal commit boundary accepts
an immutable bounded resolved value, never an executable callback. It rejects ambient
transactions, locks campaign then journal, rechecks exact revision/content/operation
and resource versions, and returns detached receipt data only after database commit.
The initial typed effects cover world counters/mode/decision, resource adjustments,
events and reward settlements. New domain effects must extend that explicit boundary.
The outbox stores immutable scoped operation/revision references, not event payloads;
its deferred foreign key requires a committed journal outcome. Dispatch and audience
filtering remain later worker work. No network client or delivery hook is injected into
the transaction. Populated upgrade and 27 focused PostgreSQL cases pass, including
deferred commit failure and an intervening resource writer after inserts flush.
Full backend verification passes 72 tests, with three packaged-interface tests skipped
outside their profile. Leases/fencing remain P03.5 and gameplay
semantic/owner authorization remains P07/the application boundary.

Duplicate/crash/concurrency fault tests, resource/ownership constraints, one-time settlement, narration-only retries, and fresh-storage recovery. See [turn protocol](../07-turn-protocol.md).
