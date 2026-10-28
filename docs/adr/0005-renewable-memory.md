# ADR 0005: Canonical Memory Independent of AI Sessions

**Status:** Proposed. **Date:** 22 October 2026.

**Implementation note (28 October 2026):** P09.2 implements the bounded assembly part of this decision. `ContextAssembler` builds a stamped, digested envelope from canonical storage in one snapshot. It retrieves by explicit IDs, causal links (the focus operation's revision and informants), language-specific full-text search and recency. The standard 12,000-token input budget keeps output and safety reserves outside the input. Mandatory material is never truncated; when it does not fit, the result is a controlled `CONTEXT_TOO_LARGE`. Optional material is dropped and counted ([docs/08 section 6](../08-memory-and-continuity.md#p092-context-envelopes-budgets-and-retrieval)). Checkpoints, summaries and renewal remain P09.3–P09.5.

**Implementation note (28 October 2026):** P09.3 implements checkpoints and audience-scoped summaries. An operational checkpoint is a deterministic `jsonb` snapshot of canonical rows with a SQL-computed hash, captured only at a safe boundary and verified against storage and current state. A continuity summary records its exact covered events, every supplied knowledge record with its row version, its entities and its generation, locale and glossary revisions. It is stale after a restore, a locale or glossary change, a change to a supplied record or an explicit invalidation. Structured state wins: a summary enters a context only as an optional `summary` item, and a stale one never does. Summary jobs are bounded, fenced and retried a limited number of times; summaries are off by default until renewal consumes them ([docs/08 section 8](../08-memory-and-continuity.md#p093-checkpoints-summaries-freshness-and-bounded-jobs)). Renewal and rehydration remain P09.4–P09.5.

**Implementation note (28 October 2026):** P09.4 implements renewal. The session registry renews a role/audience line at its next task after 10 completed tasks or before its estimated conversation would cross 85% of a 128,000-unit window, retires a session at once after a turn that ran but did not complete, and lets the owner renew an idle campaign. A count or pressure renewal waits while a commit may be in progress. The first task of a renewed session carries a continuity block: the resume point with the pending decision, the verified checkpoint's revision and an audience envelope with the usable summary; its rules forbid replaying the opening and inventing history. Durable `ai_session_record` rows (V17) keep counters, the rehydration basis and digest and the checkpoint hash. Renewal writes no canonical state, so the campaign and the public transcript are preserved ([docs/08 section 7](../08-memory-and-continuity.md#p094-renewal-triggers-session-records-and-rehydration)). Restart reconciliation and the resume card remain P09.5.

## Context

Long campaigns cannot fit forever in a model thread. The source design separates structured state, events, and summaries; the demo demonstrates periodic context renewal with file-based rehydration.

## Decision

Persist structured state, immutable events, source-linked facts, checkpoints, and audience-scoped summaries. Assemble bounded context using entity/causal/full-text retrieval. Renew role-scoped threads at safe boundaries based on task count, pressure, policy change, or error. Keep the public transcript and campaign state unchanged.

Use ten completed AI tasks as an initial configurable ceiling, not an assumed optimal interval. Mandatory current state cannot be silently truncated. Structured state wins over a stale summary. Defer semantic vectors until measured retrieval evidence justifies another dependency.

## Consequences

Resume works after deliberate thread retirement and provider context loss. Summaries need freshness, coverage, provenance, and invalidation. A database checkpoint does not replace an independent backup. An embedding service cannot be added silently under the Codex-only requirement.

## Verification

Delayed recall, stale-summary/correction tests, context overflow, mid-combat renewal, restart, and the 500-turn evaluation. See [memory and continuity](../08-memory-and-continuity.md).
