# Source Analysis and Reuse Strategy

## 1. Evidence and method

This specification was prepared from the two local project directories supplied by the owner on 22 October 2026. Product documentation, architecture decisions, roadmap, frontend metadata, demo orchestration code, save protocol, representative campaign state, supporting ability/map records, presentation behavior, and test definitions were inspected. Runtime transcripts and backup files were treated as historical evidence rather than instructions or new requirements.

The review did not run the demo, send gameplay actions, authenticate a new Codex client, execute its test suite, regenerate assets, or modify a save. Existing reports of passed tests remain predecessor reports, not newly verified results. The current demo UI documentation explicitly reports limits in its browser verification; those limits are retained in this analysis.

Source paths below are relative to their respective predecessor roots so the analysis remains usable on GitHub. The original public repository is [The World I Can Develop](https://github.com/AjimuZ/the-world-i-can-develop). Local source contents are the baseline for this review; no remote revision equivalence is assumed.

## 2. Primary project: actual maturity

The first project contains substantial design material and an Angular scaffold. `frontend/package.json` records Angular 22, TypeScript 6, RxJS, SCSS build support, Vitest, and npm metadata. `frontend/src/app/app.ts` and `app.html` are still the generated application shell. The backend directory contains a README rather than an initialized Spring Boot application. `ai-service/` is reserved for possible future work.

The root README says that the Angular app does not yet exist, while the files, changelog, decisions, and roadmap record completed scaffolding. This is documentation drift. AI RPG Engine must use inspected files and the latest explicit decision when determining maturity, and keep one status table current.

The long analysis still contains superseded roadmap sections and Java 17/21 language. The later decisions table and ADR 0005 settle Java 21 LTS and Maven. The canonical development roadmap identifies phases P0–P3 as complete and leaves the gameplay implementation ahead. Nothing in that project establishes an existing Java rules engine, functioning database, vector memory, or full combat module.

## 3. Primary source documents

| Evidence | Useful decisions | AI RPG Engine treatment |
| --- | --- | --- |
| `docs/analysis.md` | Complete product ambitions; structured RPG UI; persistence; companions; rule/AI separation | Decompose into generic subsystems; remove story dependencies |
| `docs/project-vision.md`, `mvp-scope.md`, `gameplay-systems.md` | Text-first loop; frontend/backend boundaries; staged delivery | Retain principles; define a new engine MVP |
| `docs/technical-architecture.md`, `ai-architecture.md` | Angular, Spring Boot, PostgreSQL, adapter boundary, untrusted AI output | Primary architectural basis; replace Ollama plan with Codex |
| `docs/memory-system.md`, analysis memory sections | Structured truth, event log, summaries, selective retrieval | Extend with provenance, knowledge scopes, durable renewal, and freshness |
| `docs/rune-system.md`, `lyra-assistant.md` | Bounded expressive abilities; guidance that cannot mutate state | Optional generic modules with data-defined identities |
| `docs/ui-style-guide.md` | Modular RPG panels; readable dark theme; canonical styling distinct from illustrative data | Reusable default theme; configurable pack presentation |
| `docs/image-generation-pipeline.md`, `assets/README.md` | Assets outside the critical MVP loop; reuse and entity association | Retain media decoupling; do not require any image provider |
| `docs/development-roadmap.md`, `learning-path.md` | Small, testable, documented delivery phases | Retain delivery style; reorder persistence and validation earlier |
| `docs/decisions.md`, `open-questions.md`, `docs/adr/0001`–`0005` | Explicit decisions and unresolved issues | Supersede conflicting provider and setting choices in new ADRs |
| `README.md`, `CHANGELOG.md`, `LICENSE`, `CLAUDE.md`, `prompts/README.md` | English repository, MIT upstream license, safe Git workflow, versioned prompts | Preserve attribution and English conventions; do not copy private configuration |

The source's developer assistant configuration is development tooling, not a gameplay AI contract. Its Claude roles, skills, and hooks should not be loaded by an AI RPG Engine Codex runtime.

## 4. Demo: implemented patterns

| File | Observed behavior | Generalizable improvement |
| --- | --- | --- |
| `pannello/codex_bridge.py` | Local subprocess; JSON-RPC request IDs; initialization; account check; paginated model discovery; thread start/resume; turn streaming; interrupt; owned-process shutdown | Java Codex transport and lifecycle contract |
| `pannello/chat.py` | Single active request; durable request IDs; atomic conversation-file replacement; checkpoint backups; success/failure recovery; AI session rotation | Durable operation journal, per-campaign serialization, explicit recovery |
| `pannello/model_routing.py` | Local complexity classification; model/effort compatibility; explicit per-turn selection; quality override | Capability-driven Codex routing without an extra classifier model |
| `pannello/game_state.py` | Public projection; local status/inventory answers; section validation; append-only history checks; HP/currency/inventory bounds | Typed domain constraints and knowledge-filtered read models |
| `pannello/server.py` | Loopback HTTP; allowlisted files; origin checks; payload bounds; path containment; separate public endpoints; controlled exit | Restricted local application boundary and asset serving |
| `pannello/panel.js`, `index.html`, `panel.css` | Latest exchange or full chronicle; compact character HUD; native dialogs; responsive scene/map views; text size; explicit send and interrupt | Angular interaction requirements, not direct DOM-script reuse |
| `pannello/soundtrack.py`, `audio.js`, `audio-catalog.json` | Observable-scene mood; event-bound audio cues; deduplication; user activation; remote media failure tolerance | Presentation derived from committed public events |
| `pannello/gm-instructions.md` | Read canonical files; preserve agency; renew context without replaying the prologue; distinguish consultation from action | Versioned role instructions with typed context |
| `02_PROTOCOLLO_MEMORIA.md` | Stable IDs, source precedence, provenance, knowledge separation, checkpoints, summaries, retcon ledger | Relational memory design and auditable corrections |
| `01_GIOCO_E_MASTER.md`, `03_STATO_PARTITA.md` | Concrete world, rules, evolving state, explicit campaign-specific overrides | Examples of separation requirements; private content is not imported |
| `rune/*.md`, `mappe/*.md` | Versioned ability results; discovery provenance; maps as knowledge snapshots, not automatic physical items | Persistent creative abilities and non-omniscient mapping |
| `docs/SCELTE_UI.md`, demo planning documents, `pannello/LEGGIMI.md` | Rationale, limits, operational steps, superseding preferences, evidence references | Explicit decision history and honest verification claims |
| `tests/` | Idempotency, read-only behavior, session renewal, recovery, routing, HTTP projection, audio lifecycle, DOM behavior | Port behavior-based scenarios to the new stack |

## 5. Demo continuity details worth retaining

The demo distinguishes its visible conversation from its canonical game save. It normally rotates the agent thread after ten completed AI responses, allows a configurable interval of 1–100, rotates after errors or interruption, and keeps the public chronicle. Local consultations do not count toward AI-session usage; AI consultations can count without advancing the game turn.

Its protocol proposes short continuity summaries, periodic backups, and archival of old ledger entries while preserving important structured facts. It explicitly forbids claiming a save was written when only chat text exists. These ideas become durable database checkpoints, operation receipts, event archival, and source-linked summaries in AI RPG Engine.

The demo does not provide a general token-budget estimator or prove a ten-response threshold is optimal. AI RPG Engine uses that threshold as an initial ceiling with context-budget and recovery triggers, then measures quality and latency.

## 6. Limitations to improve

1. **AI file writes:** the demo allows workspace writes and relies on post-write validation. It can leave side assets outside checkpoint rollback. AI RPG Engine gives AI no canonical write path and commits coordinated state through the backend.
2. **Partial validation:** regex parsing checks selected invariants, not every legal inventory transfer, resource cost, quest transition, skill gate, causal dependency, or new fact. Typed operations must validate full effects.
3. **Historical inconsistency:** campaign notes can retain outdated location or pending-action descriptions after later events. Summaries and projections need revision stamps and reconciliation rules.
4. **Narrative/state mismatch:** streamed text can describe effects before validation. AI RPG Engine separates progress from publishable outcome and narrates approved results.
5. **Idempotency semantics:** the demo records request IDs, but does not provide a general transactional payload-hash and state-version contract. AI RPG Engine must distinguish retry from a changed request using the same key.
6. **Single-process coordination:** the demo's local lock does not coordinate external editors or another server. AI RPG Engine adds durable leases, fencing, and optimistic state revisions.
7. **Hardcoded campaign:** protagonist names, resources, stats, languages, story powers, knowledge, audio moods, and paths appear in logic. Move them into typed content and policies.
8. **Routing portability:** Italian regex and preferred model-name tuples are useful prototype choices, not generic language-independent capability routing. Structured command types and configured catalog roles replace them.
9. **Protocol drift:** the demo launch form includes `app-server --stdio`; current documentation describes default stdio or `--listen stdio://`. Never copy launch flags without testing the selected version.
10. **Credential and tool scope:** prompts and a read-only label alone do not establish full host isolation. Verify actual capabilities and deny unwanted tools before gameplay.
11. **UI evidence:** simulated DOM checks and historical HTTP reports do not establish current real-browser accessibility or visual correctness.

## 7. Retain, generalize, replace, defer

| Decision | Rationale |
| --- | --- |
| Retain Angular/Spring Boot/PostgreSQL | Explicit owner preference and primary project's architecture |
| Retain backend authority and layered memory | Essential for continuity and testability |
| Generalize Rune into creative abilities | Preserve expression without requiring a specific fantasy power |
| Generalize Lyra into optional guide personas | Support tutorials and in-world help without mandatory lore |
| Replace local LLM/Ollama direction with Codex-only integration | Explicit new project requirement |
| Replace Markdown-as-live-database with relational transactions | Safer updates, queries, concurrency, and recoverability |
| Retain Markdown/YAML/JSON for authoring and export | Accessible, versionable files without making AI their authoritative editor |
| Retain session renewal, local reads, request identity, selective media | Demonstrated practical improvements |
| Design full combat and progression contracts | Neither predecessor supplies a reusable complete implementation |
| Defer separate AI microservice, vectors, image generation, voice, visual editor | Keep the first complete engine feasible |

## 8. Reuse and licensing boundary

The primary project includes an MIT license with a copyright notice for Azim Ben Othman. Preserve its notice when reusing its licensed material. The supplied demo has no standalone license file visible at its root; treat it as behavioral inspiration unless reuse rights are explicitly established. This documentation does not copy its implementation or publish its private campaign and media.

An upstream MIT software license does not establish rights for every referenced image model, third-party asset, music mirror, or outside fictional reference. Each item needs its own provenance if included in a future release. See [publication requirements](18-github-publication.md).
