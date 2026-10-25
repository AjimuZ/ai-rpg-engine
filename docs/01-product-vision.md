# Product Vision

## 1. Purpose

AI RPG Engine is a reusable runtime for AI-assisted role-playing adventures. It lets authors define a world and its possibilities while the engine maintains rules, state, memory, and presentation. It must support a campaign that develops through play without forcing its author to supply a complete story or predetermined ending.

The intended outcome is a coherent, persistent RPG experience with inspectable mechanics. Creativity belongs in narration, world details, character behavior, encounter composition, and player intent. Authority over mechanical consequences belongs in software.

## 2. Requirements supplied by the owner

- Deliver detailed English documentation and a development roadmap in this phase; create no application code.
- Prepare a project suitable for later publication on GitHub.
- Use The World I Can Develop as the principal technical and product reference.
- Retain its Angular, Spring Boot, Java, PostgreSQL, modular interface, and state-validation direction.
- Use the demo selectively for practical AI orchestration and continuity improvements.
- Use Codex as the initial AI integration rather than generic or local model providers.
- Amended 25 October 2026: also allow Claude (Claude Code) as a selectable alternative narration runtime, one runtime at a time, with Codex remaining the default for now ([ADR 0020](adr/0020-selectable-narration-runtime.md)).
- Amended 25 October 2026 (later): also allow free local models for narration and image generation, hosted APIs through the owner's key and custom endpoints, with defaults recommended for the owner's hardware (an RTX 4070 with 12 GB of VRAM) and maximum freedom to choose other models for each weight class and for images ([ADR 0021](adr/0021-inference-backends-and-model-selection.md)).
- Build an adventure-independent engine, with customizable adventure files.
- Design dynamic turn-based combat informed by familiar tabletop RPG concepts and using original rules and content.
- Account for inventory, attributes, statistics, abilities, skills, progression, and long campaigns.
- Make dynamic visual-novel play the default: dominant scene art, attributed dialogue/narration, editable quick replies and free player input.
- Deliver the complete UI graphics kit and support bounded, saved scene/portrait/item generation during play, with stable identities and accessible rarity presentation; see [ADR 0011](adr/0011-visual-novel-and-persistent-artwork.md).
- After the core engine, provide an integrated text/graphical adventure editor that preserves author requirements and fills only permitted gaps; support cyberpunk/other genres and bounded combat customization around the default ([later roadmap](26-adventure-editor-roadmap.md)).
- Support English-default and first-class Italian from the initial playable release, with reviewed UI and directly generated selected-language narration ([localization](29-localization.md)).
- Prepare important quest situations asynchronously through bounded, validated plans without prescribing player choices; retain the responsive no-plan baseline and authoritative world simulation ([planning](28-asynchronous-narrative-planning.md)).
- Qualify optional local EN/IT voices and shareable story bundles; keep rights, privacy, quotas and graceful text-only play explicit.

## 3. Design assumptions

The first supported installation has one human owner and one active player per campaign. It runs on the owner's computer and uses that person's configured narration-runtime access (Codex by default). Many campaigns may exist, but a campaign processes one state-changing operation at a time. The initial supported operating system is Windows, reflecting the available demo; Linux and macOS require explicit support checks before release claims.

Authors initially work with files and import diagnostics. A visual editor is useful later but must not be necessary to author or play a complete adventure. The UI defaults to English and fully supports Italian from MVP; story source language, selected narration and historical passage language are distinct as defined in ADR 0015. Reusable story bundles can be exchanged between owners under their content/asset licenses; private campaign histories and credentials are excluded.

These assumptions are reversible through versioned decisions. They are not requests for multiplayer, public hosting, third-party service integrations, or an online content marketplace.

## 4. Product layers

| Layer | Owns | Does not own |
| --- | --- | --- |
| Engine | Rules execution, transactions, campaigns, knowledge boundaries, turns, narration runtime adapters, persistence | A mandatory plot or setting |
| Ruleset | Attribute definitions, formulas, action economy, conditions, progression policies | A particular campaign's events |
| Adventure pack | World, initial entities, narrative constraints, authored content, allowed generation, selected ruleset | Credentials, executable host commands, direct SQL |
| Campaign | A particular playthrough, choices, discoveries, state, encounters, rewards | Mutations of the installed pack |
| Presentation profile | Theme, panels, terminology, media policy, optional voice | Mechanical authority |

One pack can create many isolated campaigns. A campaign pins exact pack and ruleset revisions. A science-fiction pack and a fantasy pack must be able to use the same engine executable without changing Java or TypeScript source.

## 5. Experiences to support

**Authored:** the author provides quests, characters, places, and event conditions. The narration runtime adapts dialogue and description within those boundaries.

**Emergent:** the author provides world principles, factions, generation constraints, and a starting situation. The narration runtime proposes additional content as needed. The engine assigns identities, validates additions, and records them as campaign canon.

**Hybrid:** authored anchors coexist with generated travel scenes, side objectives, and encounters. This is the recommended default because it offers continuity with room for discovery.

These are content policies, not separate codebases. An authored quest may fail, a faction may change plans, and a battle may end in escape or agreement. The engine must not force an unfinished plot to reach an author-preferred outcome.

## 6. Architecture options considered

| Approach | Benefits | Limitations | Decision |
| --- | --- | --- | --- |
| Extend the demo's Python application and Markdown saves | Fastest path from a working local prototype; existing Codex bridge | Diverges from requested Java/Angular stack; regex save parsing; AI file writes; campaign coupling | Use as a reference and test-case source |
| Angular + Spring Boot modular application, PostgreSQL, local Codex bridge | Matches primary stack; clear authority; transactional persistence; reusable modules | More initial work; Codex protocol adapter must be verified | Recommended |
| Distributed orchestration services and a generic provider framework | Independent scaling and broad model integrations | Premature operational complexity; contradicts Codex-focused first phase | Defer; not part of v1 |

The recommended application has one authoritative backend. A separate Python AI service, Node service, vector database, message broker, and Kubernetes cluster are not prerequisites. Module boundaries should make later extraction possible without paying that cost now.

## 7. Naming

**Selected name: AI RPG Engine.** The owner selected this name because it states the program's purpose directly: a reusable engine for AI-powered role-playing adventures. Repository slug: `ai-rpg-engine`. Short description: “A Codex-powered RPG engine, with Claude Code, free local models and API keys as selectable alternatives, featuring persistent worlds, configurable adventures, and deterministic turn-based combat.”

Use **AI RPG Engine** consistently in public documentation and product presentation, and `ai-rpg-engine` in repository and directory names. The name identifies the software rather than a particular adventure or setting. Repository availability and applicable name/rights checks remain publication tasks; the product name itself is no longer an open design choice.

## 8. Success criteria

1. Two structurally different adventures run without engine source edits.
2. Campaigns survive application restarts and deliberate AI thread renewal.
3. An AI response cannot grant an item, kill a creature, or complete a quest without a valid engine operation.
4. A combat encounter can be generated, validated, played, interrupted, resumed, and settled with exactly one reward settlement.
5. Player-visible projections do not include unrevealed secrets or private NPC knowledge.
6. An author can diagnose an invalid pack from precise file and field errors.
7. The repository states its implementation status honestly and supports a reproducible release.

## 9. Genericity boundaries

The engine provides supported primitives, not arbitrary reality simulation. Authors can rename attributes, create items and abilities, configure formulas within a bounded expression system, change difficulty and advancement policies, define world structures, and disable optional modules. A mechanic requiring a new type of effect, a new spatial model, or a different scheduling algorithm requires an engine extension and compatibility version.

Rune becomes an optional **creative abilities module**. Lyra becomes an optional **guide persona module**. Their names, lore, personalities, visual identities, and campaign-specific privileges are not engine constants. Neither module is necessary to create a campaign or enter combat.
