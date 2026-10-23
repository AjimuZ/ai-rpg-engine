# Documentation Index

Baseline date: **22 October 2026**. Language: English. Status: proposed design, awaiting implementation and product review.

Requirements stated by the project owner are binding for this design. Technical defaults and numerical rules are proposals, explicitly identified where relevant. An unchecked roadmap task means no AI RPG Engine implementation has been delivered. Observations about predecessor projects do not count as AI RPG Engine features.

## Product and research

| Document | Purpose |
| --- | --- |
| [01 — Product vision](01-product-vision.md) | Identity, audience, genericity, options, and naming |
| [02 — Source analysis](02-source-analysis.md) | Evidence, predecessor maturity, reuse, conflicts, and gaps |
| [03 — Requirements and scope](03-requirements-and-scope.md) | Traceable functional requirements and release boundaries |

## Runtime specifications

| Document | Purpose |
| --- | --- |
| [04 — Architecture](04-architecture.md) | Stack, modules, dependencies, deployment, and trust boundaries |
| [05 — Domain and persistence](05-domain-model.md) | Entities, identity, constraints, versioning, and storage |
| [06 — Codex integration](06-codex-integration.md) | Process lifecycle, protocol contract, routing, security, and compatibility |
| [07 — Turn protocol](07-turn-protocol.md) | Commands, transactions, retries, interruptions, and narration consistency |
| [08 — Memory and continuity](08-memory-and-continuity.md) | Long sessions, context selection, renewal, summaries, and recovery |
| [09 — Adventure packs](09-adventure-packs.md) | File-driven customization, formats, validation, import, and migrations |
| [10 — Gameplay modules](10-gameplay-modules.md) | Exploration, inventory, quests, companions, world simulation, and optional systems |
| [11 — Combat system](11-combat-system.md) | Original tactical rules, state machine, actions, effects, and outcomes |
| [12 — Encounters and progression](12-encounters-and-progression.md) | Dynamic encounter creation, difficulty, enemy tactics, rewards, and advancement |
| [13 — API and UI](13-api-and-ui.md) | Proposed application contracts, player experience, and accessibility |
| [14 — Security and operations](14-security-and-operations.md) | Data protection, sandboxing, backups, diagnostics, and installation design |

## Delivery and governance

| Document | Purpose |
| --- | --- |
| [15 — Quality and acceptance](15-quality-and-acceptance.md) | Verification strategy, scenarios, budgets, and release gates |
| [16 — Development roadmap](16-development-roadmap.md) | Ordered work packages, dependencies, deliverables, and exit criteria |
| [17 — Risks and open decisions](17-risks-and-open-decisions.md) | Bounded uncertainty, owners, and decision deadlines |
| [18 — GitHub publication](18-github-publication.md) | Repository structure, licensing, attribution, contribution, and release preparation |
| [19 — Glossary and references](19-glossary-and-references.md) | Shared terminology, primary references, and evidence limits |
| [20 — Worked scenarios](20-worked-scenarios.md) | Cross-module examples, including a complete combat calculation |
| [21 — AI task contracts and prompts](21-ai-task-contracts-and-prompts.md) | Structured task envelopes, authority limits, and generic English prompt drafts |
| [22 — Container deployment and startup](22-container-deployment-and-startup.md) | Dockerized application, root launcher, authentication, persistent data, and startup/recovery gates |
| [23 — Presentation assets](23-presentation-assets.md) | Complete UI graphics, persistent character/item artwork, bounded runtime generation, rarity and provenance |
| [Visual-novel story UI concept](mockups/visual-novel-ui-v1.png) | Primary narrative-screen direction; [prompt and provenance](mockups/visual-novel-ui-v1.prompt.md) |
| [Advanced gameplay UI concept](mockups/advanced-gameplay-ui-v1.png) | Illustrative interface image; [prompt and provenance](mockups/advanced-gameplay-ui-v1.prompt.md) |
| [Architecture decision records](adr/README.md) | Rationale and consequences of major proposed decisions |

## Reading by role

- **Owner:** 01, 03, 11, 16, 17.
- **Backend developer:** 04–12, 14–16, ADRs.
- **Frontend developer:** 03, 05, 07, 11, 13, 15.
- **Adventure author:** 01, 09, 10, 11, 12, 20.
- **Contributor or reviewer:** 02, 15, 18, root contribution and security documents.

## Interpretation rules

“Must” defines a required behavior of the proposed system. “Should” defines a preferred behavior with a documented exception. “May” defines an optional capability. These words describe the target, not existing code.

Core identifiers, ruleset versions, events, and state revisions have consistent meanings across documents. If a numerical example conflicts with the combat rules, the rules take precedence and the example must be corrected. If an adventure conflicts with engine security or canonical state, it is rejected or explicitly migrated; prose cannot override those boundaries.
