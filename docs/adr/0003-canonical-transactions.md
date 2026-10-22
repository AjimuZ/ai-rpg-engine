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

## Verification

Duplicate/crash/concurrency fault tests, resource/ownership constraints, one-time settlement, narration-only retries, and fresh-storage recovery. See [turn protocol](../07-turn-protocol.md).
