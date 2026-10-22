# ADR 0005: Canonical Memory Independent of AI Sessions

**Status:** Proposed. **Date:** 22 October 2026.

## Context

Long campaigns cannot fit forever in a model thread. The source design separates structured state, events, and summaries; the demo demonstrates periodic context renewal with file-based rehydration.

## Decision

Persist structured state, immutable events, source-linked facts, checkpoints, and audience-scoped summaries. Assemble bounded context using entity/causal/full-text retrieval. Renew role-scoped threads at safe boundaries based on task count, pressure, policy change, or error. Keep the public transcript and campaign state unchanged.

Use ten completed AI tasks as an initial configurable ceiling, not an assumed optimal interval. Mandatory current state cannot be silently truncated. Structured state wins over a stale summary. Defer semantic vectors until measured retrieval evidence justifies another dependency.

## Consequences

Resume works after deliberate thread retirement and provider context loss. Summaries need freshness, coverage, provenance, and invalidation. A database checkpoint does not replace an independent backup. An embedding service cannot be added silently under the Codex-only requirement.

## Verification

Delayed recall, stale-summary/correction tests, context overflow, mid-combat renewal, restart, and the 500-turn evaluation. See [memory and continuity](../08-memory-and-continuity.md).
