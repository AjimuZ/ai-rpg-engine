# Requirements and Release Scope

## 1. Release definitions

**D0 — Design baseline:** this English documentation set. Delivered as design material; no runtime functionality implied.

**MVP — First playable engine:** a local single-player application with pack import, campaign creation, Codex narration, typed state changes, layered memory, AI thread renewal, inventory, character skills, one simple quest flow, and a complete bounded combat loop including rewards and resume. English is the default and English/Italian UI and narration are both supported from this release. Including combat here intentionally differs from the original game's MVP.

**v1.0 — Complete initial engine:** the MVP plus robust authoring validation, multiple isolated campaigns, complete supported gameplay modules, dynamic encounter generation and tuning, configurable progression, campaign export/restore, knowledge-safe projections, a selectable Claude narration runtime once its terms review is recorded (ADR 0020), free local and API-key backends with owner-chosen models (ADR 0021), accessibility verification, operational documentation, and public release gates.

“Complete” means complete against the supported v1 contracts. It does not mean all RPG systems, infinite memory, arbitrary mechanics from prose, or a full virtual tabletop.

## 2. Functional requirement register

| ID | Requirement | MVP | v1 verification |
| --- | --- | --- | --- |
| FR-001 | Import a versioned adventure pack with fixed author requirements and bounded generation gaps | Required | Preserve required entities/facts; reject contradictions, invalid references, unsupported mechanics and malicious paths |
| FR-002 | Create a campaign pinned to pack and ruleset revisions | Required | Two campaigns do not share mutable state |
| FR-003 | Configure a protagonist using pack-defined options and validated bounds | Required | Names, attributes, starting abilities and equipment persist |
| FR-004 | Submit structured or free-text player actions through one command pipeline | Required | Ambiguity requests do not consume a turn |
| FR-005 | Use the selected narration runtime (Codex by default) for supported narrative and interpretation tasks | Required (Codex) | Real adapter contract smoke for each supported runtime; no unlisted production runtime (FR-038) |
| FR-006 | Commit mechanical effects only after deterministic validation | Required | Invalid AI proposal cannot modify canonical state |
| FR-007 | Preserve player agency and stop at the next decision | Required | No unchosen agreement, travel, equipment use, or attack |
| FR-008 | Consult character, inventory, journal, known world, and legal abilities without world advancement | Required | Read-only requests leave game revision, time, and RNG unchanged |
| FR-009 | Track item ownership, quantities, equipment, and resource use atomically | Required | Transfer, consumption, uniqueness, and slot constraints |
| FR-010 | Track quests, prerequisites, milestones, outcomes, and one-time rewards | Basic | Multiple outcomes, blocked/suspended states, dependency validation |
| FR-011 | Maintain persistent current state, event history, checkpoints, and summaries | Required | Restart and renewal resume the pending decision |
| FR-012 | Renew an AI thread independently from a campaign and public chronicle | Required | Renewal in exploration and mid-combat preserves state |
| FR-013 | Keep canonical facts, beliefs, rumors, plans, and private knowledge distinct | Required | Secret markers never reach player projections |
| FR-014 | Generate bounded encounter proposals from actual campaign state | Simple templates | Validate dynamic composition, terrain, abilities, and budget |
| FR-015 | Run deterministic turn-based combat using stats, equipment, abilities, resources and an in-combat dialogue/action composer | Required | Legal actions, initiative, reactions, conditions, settlement; talk, negotiate or attempt scene changes through validated commands without leaving combat |
| FR-016 | Support victory, escape, surrender, agreement, objective completion, and defeat | Required | Every terminal path leaves a resumable narrative state |
| FR-017 | Apply configurable advancement and reward policies | Basic | Thresholds, skill purchases, caps, and duplicate prevention |
| FR-018 | Track NPC goals, knowledge, presence, relationships, and party membership | Minimal NPCs | Schedules, companion control policy, scoped private data |
| FR-019 | Advance world clocks and triggered events causally | Basic time | Bounded catch-up; no advancement from UI reads |
| FR-020 | Support an optional guide persona without granting mechanical authority | Optional | Guide uses authorized knowledge only |
| FR-021 | Support optional creative abilities using validated effect primitives | Deferred | Author-defined identity; no host code execution |
| FR-022 | Associate scenes, portraits, maps, identifying item icons and dynamic music/audio cues with committed public state | Static/catalog assets | Stable entity art; optional reviewed online/local soundtrack with public cues, versioned credits, deduplication and fallback; bounded generated artwork is a v1 extension |
| FR-023 | Interrupt work and recover without duplicating effects | Required | Before/after commit and lost-response scenarios |
| FR-024 | Export, back up, restore, and migrate campaigns | Basic backup | Integrity checks, separate full/player exports, compatibility gate |
| FR-025 | Provide diagnostics for the selected runtime's account availability, model capabilities, and adapter support | Required | Useful setup failure without leaking credentials |
| FR-026 | Preview and apply explicit canon corrections with an audit trail | Deferred | Administrative change cannot silently rewrite event history |
| FR-027 | Offer a responsive visual-novel story interface with attributed dialogue, editable quick replies, free input and keyboard access | Required | Real-browser reading/choice/accessibility checks; complete graphic assets and media-free fallback |
| FR-028 | Publish clear status, contribution, security, licensing, and release documentation | Required | Fresh-clone release rehearsal and public-content audit |
| FR-029 | Deliver the containerized local application through a root `start.sh` launcher | Required | Prerequisite/auth diagnostics, health-based startup, no host build toolchains, persistent restart, and safe stop verified under QA-28 |
| FR-030 | Generate and persist bounded scene, character and item artwork through a supported image capability | v1 | Stable visual identities, verified capability/cost limits, durable assets, stale-job protection and fallback under QA-31 |
| FR-031 | Present validated, pack-defined item rarity consistently across shops, inventory and equipment | Basic tiers | Tier name plus non-color indication; artwork never assigns rarity, stats or price |
| FR-032 | Manage parallel personalized adventure workspaces with separate specifications, campaigns and media folders | Required | QA-33 proves switching, same-name entities, memory/art isolation and scoped restore |
| FR-033 | Provide English-default and first-class Italian UI, narration, dialogue, errors, terminology and accessibility with persistent language selection | Required | QA-35 and bilingual quality review; direct selected-language generation, immutable transcript language, pack fallback and safe switch |
| FR-034 | Support durable prioritized logical AI tasks and bounded narrative plan artifacts with dependency-sensitive admission and a playable no-plan path | Baseline/fallback and shared job foundations | QA-36–37 prove stale/duplicate/crash/secret/quota safety; optional deep planning is enabled only after QA-39 benefit/cost evidence |
| FR-035 | Use validated typed public presentation cues for scene/character/expression and optional dialogue audio | Basic cues | QA-40 proves allowlisted IDs, deduplicated delivery, scope/locale safety, no arbitrary executable frontend instructions and text fallback |
| FR-036 | Optionally synthesize local English/Italian dialogue with persistent voice identity, admitted rights and bounded resources | Excluded | Optional v1 capability: QA-38 before enabling; disabled/text-only fallback is valid when rights, hardware or bilingual quality gates fail |
| FR-037 | Import/export reusable adventure bundles for person-to-person sharing, including authored/localized content and permitted assets | Pack import only | P16 exports/clean-imports the versioned story bundle with hashes, dependencies, rights and no private playthrough/account data; later editor E05–E06 uses the same format |
| FR-038 | Select the default narration runtime of an installation (Codex by default, Claude as an alternative) with no automatic failover; switch only through the launcher, retiring old AI sessions and rebuilding from checkpoints; slot overrides follow FR-039 | Codex only; the selection setting may exist | QA-42 proves contract parity on shared fixtures, refusal of unknown values and unsupported versions, no silent switch, switch and restart continuity, per-runtime sign-in and billing guards, and the terms-review gate |
| FR-039 | Assign a backend and model to each logical slot (light, standard and deliberate text; image) from a closed set of protocol adapters and an open set of models and endpoints, with defaults recommended for the detected hardware, readiness probes, visible billing and privacy classes, and no hidden class change | Defaults for the reference host; local text slots | QA-43 proves slot assignment, advisor results on synthetic hardware profiles, probe gating, endpoint and secret safety, class acknowledgement and per-slot session retirement |
| FR-040 | Provide free local text and image inference on the owner's GPU with hash-verified explicit model fetching, model servers without network egress, and bounded shared GPU and RAM admission with the speech service | Local text; image generation is v1 | QA-44 proves leases, reload and cache restore, interruption, contention handling, provenance, safe-content defaults and graceful text-only fallback, with measurements recorded on the reference host |

## 3. Nonfunctional requirements

| ID | Requirement | Acceptance approach |
| --- | --- | --- |
| NFR-001 | Atomic canonical updates | State, events, rewards, operation receipt, and outbox commit together |
| NFR-002 | Deterministic rules | Same pinned inputs and recorded random draws yield the same results |
| NFR-003 | Durable idempotency | Same operation key and payload returns original status/result after restart |
| NFR-004 | Campaign isolation | Every mutable query is campaign-scoped; cross-campaign IDs rejected |
| NFR-005 | Context boundedness | Mandatory context is checked before optional retrieval; overflow fails clearly |
| NFR-006 | Explainable mechanics | Every resource, damage, loot, and advancement change has a rule and event source |
| NFR-007 | Controlled narration-runtime capability | No canonical writes or general host automation exposed to gameplay AI |
| NFR-008 | Usable performance | Local-read and rules budgets measured separately from provider latency |
| NFR-009 | Graceful degradation | Provider or media failure preserves the latest committed state |
| NFR-010 | Maintainability | Module boundaries, migrations, versioned prompts, and focused verification |
| NFR-011 | Accessibility | Keyboard, focus, readable text, reduced motion, and non-color indicators verified |
| NFR-012 | Privacy and portability | Document local storage and outbound context; redacted diagnostics and safe export |
| NFR-013 | Bounded fair work admission | Durable quotas, age/fairness, interactive reservation and global concurrency; no implicit paid overage, bounded retry and explicit unavailable accounting |
| NFR-014 | Language and presentation invariance | Preferences, captions, speech, images and reading cannot change canonical facts, mechanics, time or RNG |
| NFR-015 | Traceable planning and recovery | Snapshot/source/dependency/policy versions, admission receipts, fencing and provenance survive restart without treating proposals as world facts |

## 4. MVP exclusions

The MVP does not include multiplayer, public hosting, arbitrary executable plugins, a visual editor, semantic embeddings, voice acting, live image generation, advanced political simulation, or compatibility with an external tabletop ruleset. It does include the data boundaries necessary for later optional modules.

The integrated editor is scheduled after core v1 in [its separate roadmap](26-adventure-editor-roadmap.md). Manual files already preserve authored facts/mandatory entities and permit completion of allowed gaps across genres. Default combat data is customizable within supported primitives/policies; a new scheduler or arbitrary code is not file/editor-only customization.

An automated test double may replace the narration runtime in tests. It is a verification device, never a selectable production runtime: the selectable backends are the closed protocol set of [ADR 0020](adr/0020-selectable-narration-runtime.md) and [ADR 0021](adr/0021-inference-backends-and-model-selection.md), with Codex the default. When the selected runtime is unavailable, the product may serve deterministic consultations and rules explanations; it must not pretend to generate a new AI scene.

## 5. v1 completion boundaries

The full initial release supports one built-in combat scheduler, one zone-based spatial model, declarative content, bounded expressions, a small effect vocabulary, and a documented extension process. Authors can customize supported data extensively. New engine primitives, new spatial algorithms, and arbitrary code execution are not file-only customization.

The default narrative UI and its graphic kit are MVP requirements. Bounded runtime scene/portrait/item image generation is a v1 requirement under [ADR 0011](adr/0011-visual-novel-and-persistent-artwork.md), with a supported capability proof and explicit generation policy/budgets. It is not a promise that every text subscription includes image generation. Static/catalog fallbacks keep play usable when generation is unavailable. The owner's research revision now admits local speech as **optional v1**, independently enabled after rights, hardware and EN/IT quality gates, under ADR 0016; text-only release remains complete without it.

Two small neutral sample packs must demonstrate different genres, optional module selections, and vocabulary. They exist to prove reuse, not to establish a flagship canon. The original private campaign is neither a sample pack nor a public fixture.

## 6. Research revision priorities

| Priority | Scope | Value, complexity and operating/verification cost |
| --- | --- | --- |
| MVP critical | EN/IT UI/receipts/narration, stable terminology, safe presentation cues and existing canonical/knowledge guarantees | High player value; medium localization/tooling cost; no mandatory extra translation/model-review call |
| v1 critical | Durable jobs, role/audience boundaries, versioned plan/dependency admission and no-plan recovery; portable story bundles | High continuity/portability value; high transactional/security testing, bounded runtime overhead even with deep planning off |
| v1 optional | Selective asynchronous investigation/quest plans, occasional director/reviewer, licensed local speech | Enable only on measured benefit and capability; higher inference/VRAM/voice/maintenance evaluation costs; failure never blocks play |
| Post-v1 | Integrated editor E01–E06, broad political simulation, runtime pool, advanced realtime audio/voice design | More authoring/operational complexity; foundations do not require shipping these now |
| Rejected as defaults | Omnipotent director, model-written canon/SQL, mandatory reviewer, continuous per-NPC inference, vector database without evidence, unrestricted voice/asset reuse | Breaks authority, agency, rights or budget constraints without demonstrated value |

The [competitive/gap matrix](27-competitive-analysis-and-gaps.md) records
per-feature dependencies, licensing and recommendations. The [planning](28-asynchronous-narrative-planning.md),
[localization](29-localization.md), [speech](30-local-speech-synthesis.md) and
[evaluation](31-research-validation-and-evaluations.md) contracts refine these
requirements. Source-inspected competitor behavior is not local implementation
evidence. Optional capability qualification tasks may conclude disabled/deferred
with verified fallback; they cannot claim a feature works without its checks.

## 7. Traceability

Requirements map to roadmap work packages in [the roadmap](16-development-roadmap.md) and to scenarios in [quality and acceptance](15-quality-and-acceptance.md). Implementation changes must update that mapping. A release cannot call a requirement complete solely because a prompt asks the model to respect it.
