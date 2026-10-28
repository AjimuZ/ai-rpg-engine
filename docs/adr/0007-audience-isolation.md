# ADR 0007: Knowledge Filtering and Audience-Isolated AI Context

**Status:** Proposed. **Date:** 22 October 2026.

**Implementation note (28 October 2026):** P08.3 implements the session-scoping part of this decision: a session scope of campaign, recovery generation, role, audience, pack hash and policy hash; a role matrix that never puts a player-facing role in the privileged audience; and a single scoped submitter of the runtime port ([docs/06 section 7](../06-codex-integration.md#p083-session-scoping)). Knowledge filtering of contexts, summaries, media and exports, and adversarial leakage evaluation, are still to be delivered.

**Implementation note (28 October 2026):** P09.1 implements the first sentence of the decision in storage. Claim truth (`knowledge_claim`) and observer knowledge (`knowledge_belief`, `actor_sighting`) are separate records, and `KnowledgeView.forActor` returns one actor's audience view without truth or other actors' real locations ([docs/08 section 4](../08-memory-and-continuity.md#p091-typed-knowledge)). Using these views to filter AI contexts is P09.2.

**Implementation note (28 October 2026):** P09.2 filters AI contexts before retrieval. A public or character context is assembled only from that actor's view, without truth, other holders' beliefs, real locations of others or planning assumptions. Only a privileged context receives truth (`fact`) and private plan assumptions (`assumption`), and the selector and the envelope both refuse those standings for a limited audience. Every item carries its epistemic standing. `withheldPhrases` supplies the disclosure guard's verbatim markers ([docs/08 section 6](../08-memory-and-continuity.md#p092-context-envelopes-budgets-and-retrieval)). Filtering of summaries, media and exports and adversarial leakage evaluation are still to be delivered.

## Context

A planner may require secret canon while the player narrator, guide, or NPC must know less. Filtering only a final response cannot remove hidden facts already present in a reused thread's memory. Media can also reveal secrets indirectly.

## Decision

Model fact truth and observer knowledge separately. Scope every AI session by campaign, role, audience, and policy hash. Feed public narration sanitized resolved events through a separate limited context. NPC tactics use that actor's observations and legal candidates. Player APIs use typed public projections, never raw save/entity serialization.

Apply the same policy to summaries, retrieval, assets, audio, diagnostics, and exports. A full-owner archive is explicitly different from a player-facing journal export.

## Consequences

There may be more than one logical AI thread per campaign, but no thread is authoritative memory. Additional filtering and tests are required. Natural-language leakage still needs adversarial evaluation and cannot be declared solved by schema validation alone.

## Verification

Secret-sentinel tests across all outputs, privileged-to-public handoff scenarios, guide uncertainty, hidden-location tests, and media metadata review. See [security](../14-security-and-operations.md).
