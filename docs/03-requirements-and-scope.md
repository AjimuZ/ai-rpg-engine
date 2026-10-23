# Requirements and Release Scope

## 1. Release definitions

**D0 — Design baseline:** this English documentation set. Delivered as design material; no runtime functionality implied.

**MVP — First playable engine:** a local single-player application with pack import, campaign creation, Codex narration, typed state changes, layered memory, AI thread renewal, inventory, character skills, one simple quest flow, and a complete bounded combat loop including rewards and resume. Including combat here intentionally differs from the original game's MVP.

**v1.0 — Complete initial engine:** the MVP plus robust authoring validation, multiple isolated campaigns, complete supported gameplay modules, dynamic encounter generation and tuning, configurable progression, campaign export/restore, knowledge-safe projections, accessibility verification, operational documentation, and public release gates.

“Complete” means complete against the supported v1 contracts. It does not mean all RPG systems, infinite memory, arbitrary mechanics from prose, or a full virtual tabletop.

## 2. Functional requirement register

| ID | Requirement | MVP | v1 verification |
| --- | --- | --- | --- |
| FR-001 | Import a versioned adventure pack with fixed author requirements and bounded generation gaps | Required | Preserve required entities/facts; reject contradictions, invalid references, unsupported mechanics and malicious paths |
| FR-002 | Create a campaign pinned to pack and ruleset revisions | Required | Two campaigns do not share mutable state |
| FR-003 | Configure a protagonist using pack-defined options and validated bounds | Required | Names, attributes, starting abilities and equipment persist |
| FR-004 | Submit structured or free-text player actions through one command pipeline | Required | Ambiguity requests do not consume a turn |
| FR-005 | Use Codex for supported narrative and interpretation tasks | Required | Real adapter contract smoke; no alternate production provider |
| FR-006 | Commit mechanical effects only after deterministic validation | Required | Invalid AI proposal cannot modify canonical state |
| FR-007 | Preserve player agency and stop at the next decision | Required | No unchosen agreement, travel, equipment use, or attack |
| FR-008 | Consult character, inventory, journal, known world, and legal abilities without world advancement | Required | Read-only requests leave game revision, time, and RNG unchanged |
| FR-009 | Track item ownership, quantities, equipment, and resource use atomically | Required | Transfer, consumption, uniqueness, and slot constraints |
| FR-010 | Track quests, prerequisites, milestones, outcomes, and one-time rewards | Basic | Multiple outcomes, blocked/suspended states, dependency validation |
| FR-011 | Maintain persistent current state, event history, checkpoints, and summaries | Required | Restart and renewal resume the pending decision |
| FR-012 | Renew an AI thread independently from a campaign and public chronicle | Required | Renewal in exploration and mid-combat preserves state |
| FR-013 | Keep canonical facts, beliefs, rumors, plans, and private knowledge distinct | Required | Secret markers never reach player projections |
| FR-014 | Generate bounded encounter proposals from actual campaign state | Simple templates | Validate dynamic composition, terrain, abilities, and budget |
| FR-015 | Run deterministic turn-based combat using stats, equipment, abilities, and resources | Required | Legal actions, initiative, reactions, conditions, settlement |
| FR-016 | Support victory, escape, surrender, agreement, objective completion, and defeat | Required | Every terminal path leaves a resumable narrative state |
| FR-017 | Apply configurable advancement and reward policies | Basic | Thresholds, skill purchases, caps, and duplicate prevention |
| FR-018 | Track NPC goals, knowledge, presence, relationships, and party membership | Minimal NPCs | Schedules, companion control policy, scoped private data |
| FR-019 | Advance world clocks and triggered events causally | Basic time | Bounded catch-up; no advancement from UI reads |
| FR-020 | Support an optional guide persona without granting mechanical authority | Optional | Guide uses authorized knowledge only |
| FR-021 | Support optional creative abilities using validated effect primitives | Deferred | Author-defined identity; no host code execution |
| FR-022 | Associate scenes, portraits, maps, identifying item icons and dynamic music/audio cues with committed public state | Static/catalog assets | Stable entity art; optional reviewed online/local soundtrack with public cues, versioned credits, deduplication and fallback; bounded generated artwork is a v1 extension |
| FR-023 | Interrupt work and recover without duplicating effects | Required | Before/after commit and lost-response scenarios |
| FR-024 | Export, back up, restore, and migrate campaigns | Basic backup | Integrity checks, separate full/player exports, compatibility gate |
| FR-025 | Provide diagnostics for Codex account availability, model capabilities, and adapter support | Required | Useful setup failure without leaking credentials |
| FR-026 | Preview and apply explicit canon corrections with an audit trail | Deferred | Administrative change cannot silently rewrite event history |
| FR-027 | Offer a responsive visual-novel story interface with attributed dialogue, editable quick replies, free input and keyboard access | Required | Real-browser reading/choice/accessibility checks; complete graphic assets and media-free fallback |
| FR-028 | Publish clear status, contribution, security, licensing, and release documentation | Required | Fresh-clone release rehearsal and public-content audit |
| FR-029 | Deliver the containerized local application through a root `start.sh` launcher | Required | Prerequisite/auth diagnostics, health-based startup, no host build toolchains, persistent restart, and safe stop verified under QA-28 |
| FR-030 | Generate and persist bounded scene, character and item artwork through a supported image capability | v1 | Stable visual identities, verified capability/cost limits, durable assets, stale-job protection and fallback under QA-31 |
| FR-031 | Present validated, pack-defined item rarity consistently across shops, inventory and equipment | Basic tiers | Tier name plus non-color indication; artwork never assigns rarity, stats or price |
| FR-032 | Manage parallel personalized adventure workspaces with separate specifications, campaigns and media folders | Required | QA-33 proves switching, same-name entities, memory/art isolation and scoped restore |

## 3. Nonfunctional requirements

| ID | Requirement | Acceptance approach |
| --- | --- | --- |
| NFR-001 | Atomic canonical updates | State, events, rewards, operation receipt, and outbox commit together |
| NFR-002 | Deterministic rules | Same pinned inputs and recorded random draws yield the same results |
| NFR-003 | Durable idempotency | Same operation key and payload returns original status/result after restart |
| NFR-004 | Campaign isolation | Every mutable query is campaign-scoped; cross-campaign IDs rejected |
| NFR-005 | Context boundedness | Mandatory context is checked before optional retrieval; overflow fails clearly |
| NFR-006 | Explainable mechanics | Every resource, damage, loot, and advancement change has a rule and event source |
| NFR-007 | Controlled Codex capability | No canonical writes or general host automation exposed to gameplay AI |
| NFR-008 | Usable performance | Local-read and rules budgets measured separately from provider latency |
| NFR-009 | Graceful degradation | Provider or media failure preserves the latest committed state |
| NFR-010 | Maintainability | Module boundaries, migrations, versioned prompts, and focused verification |
| NFR-011 | Accessibility | Keyboard, focus, readable text, reduced motion, and non-color indicators verified |
| NFR-012 | Privacy and portability | Document local storage and outbound context; redacted diagnostics and safe export |

## 4. MVP exclusions

The MVP does not include multiplayer, public hosting, arbitrary executable plugins, a visual editor, semantic embeddings, voice acting, live image generation, advanced political simulation, or compatibility with an external tabletop ruleset. It does include the data boundaries necessary for later optional modules.

The integrated editor is scheduled after core v1 in [its separate roadmap](26-adventure-editor-roadmap.md). Manual files already preserve authored facts/mandatory entities and permit completion of allowed gaps across genres. Default combat data is customizable within supported primitives/policies; a new scheduler or arbitrary code is not file/editor-only customization.

An automated test double may replace Codex in tests. It is a verification device, not a user-selectable production AI provider. When Codex is unavailable, the product may serve deterministic consultations and rules explanations; it must not pretend to generate a new AI scene.

## 5. v1 completion boundaries

The full initial release supports one built-in combat scheduler, one zone-based spatial model, declarative content, bounded expressions, a small effect vocabulary, and a documented extension process. Authors can customize supported data extensively. New engine primitives, new spatial algorithms, and arbitrary code execution are not file-only customization.

The default narrative UI and its graphic kit are MVP requirements. Bounded runtime scene/portrait/item image generation is a v1 requirement under [ADR 0011](adr/0011-visual-novel-and-persistent-artwork.md), with a supported capability proof and explicit generation policy/budgets. It is not a promise that every text subscription includes image generation. Static/catalog fallbacks keep play usable when generation is unavailable. Voice generation remains beyond v1 unless separately authorized.

Two small neutral sample packs must demonstrate different genres, optional module selections, and vocabulary. They exist to prove reuse, not to establish a flagship canon. The original private campaign is neither a sample pack nor a public fixture.

## 6. Traceability

Requirements map to roadmap work packages in [the roadmap](16-development-roadmap.md) and to scenarios in [quality and acceptance](15-quality-and-acceptance.md). Implementation changes must update that mapping. A release cannot call a requirement complete solely because a prompt asks the model to respect it.
