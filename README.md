# AI RPG Engine

**A Codex-powered engine for persistent, customizable RPG adventures.**

AI RPG Engine separates the software that runs an adventure from the files that describe its world. Authors supply settings, characters, objectives, abilities, equipment, encounters, and presentation preferences. The engine supplies reliable state, deterministic rules, long-term continuity, turn-based combat, and AI orchestration through Codex.

**Project status: neutral development shells; compatibility verification in progress.** The [Angular shell](frontend/README.md) and [Spring Boot shell](backend/README.md) provide build/test entry points, with no gameplay, canonical database schema, adventure compiler, or combat implementation. Every feature below is a development target. Packaged installation is not available yet. The developer [runtime compatibility probe](tools/runtime_probe/README.md) covers discovery (P01.1) and deterministic thread/turn lifecycle checks (partial P01.2); the owner-signed-in neutral smoke remains pending.

Repository policies are in [CONTRIBUTING.md](CONTRIBUTING.md), with shared [editor defaults](.editorconfig), [Git line-ending rules](.gitattributes), and a sanitized [environment example](.env.example). The example's database labels now configure the infrastructure Compose services; full packaged setup remains future P02 work; host development commands are documented in each workspace. P02.3 delivers database/runtime infrastructure, an opt-in
[single-origin jar build](backend/README.md#single-origin-jar-assembly-partial-p023), [packaged file-secret configuration](backend/README.md#packaged-runtime-configuration-partial-p023), and [packaging boundary checks](tools/container_packaging/README.md). The development-only revision in [ADR 0009](docs/adr/0009-toolchain-baseline.md) permits local engine image work on the verified exact Java pins while updated official images are unpublished; refreshing Java before release remains mandatory. The engine image and complete packaged integration are still pending.

## Why this engine exists

A long RPG campaign needs more than a conversation history. Inventory must remain consistent, unfinished promises must survive a new AI session, combat must use actual abilities and resources, and the narrator must respect what each character knows. AI RPG Engine is designed to make these responsibilities explicit and testable.

The design primarily derives from **The World I Can Develop**: Angular, Spring Boot, Java 21, PostgreSQL, modular RPG panels, backend-owned rules, and layered memory. Its playable demo contributes practical improvements: Codex app-server integration, renewable AI threads, public state projections, local consultations, request deduplication, checkpoint validation, and selective media updates. [The source analysis](docs/02-source-analysis.md) distinguishes observed implementation from plans and new proposals.

## Planned capabilities

- Load versioned adventure packs without changing engine source code.
- Run authored, emergent, or hybrid adventures without requiring a fixed plot.
- Generate narration, dialogue, encounter proposals, and tactical choices through Codex.
- Validate every mechanical consequence in the Java backend.
- Keep character statistics, inventory, skills, equipment, quests, relationships, and world changes persistent.
- Resume long campaigns from canonical checkpoints after renewing an AI thread.
- Enter and leave tactical, turn-based combat while preserving narrative continuity.
- Offer configurable rules, progression, presentation, and optional assistance systems.
- Play primarily through a visual-novel screen with dominant scene art, named speakers, editable contextual quick replies and free input; keep an equivalent text-focused mode.
- Deliver a complete graphical UI kit and persist identifying portraits/item icons, with accessible rarity and bounded runtime image generation in v1 ([presentation specification](docs/23-presentation-assets.md)).
- Export campaigns and authoring files without exposing secrets through the player UI.
- Keep personalized adventures in separate workspaces with their own specifications, playthroughs and generated-art folders ([workspace design](docs/25-adventure-workspaces.md)).

## Technology direction

| Area | Proposed baseline |
| --- | --- |
| Web interface | Angular 22.2 with TypeScript 6.0 on Node.js 24 LTS ([ADR 0009](docs/adr/0009-toolchain-baseline.md)); standalone components; signals; SCSS |
| Authoritative application | Spring Boot 4.1 on Java 21 LTS; Maven 3.10 through the Maven Wrapper ([ADR 0009](docs/adr/0009-toolchain-baseline.md)) |
| Persistence | PostgreSQL 18; Flyway 12.4 migrations; relational state and JSONB extension data |
| AI integration | Backend-managed Codex app-server integration; isolated packaged runtime with private IPC, subject to compatibility validation |
| Browser updates | REST commands and resumable Server-Sent Events |
| Local environment | Docker Compose for the complete packaged application; root `start.sh` entry point; durable storage and isolated runtime |
| Retrieval | Structured and full-text retrieval first; optional semantic indexing after evidence of need |
| Verification | JUnit, PostgreSQL integration tests, Angular/Vitest, browser acceptance tests, Codex contract fixtures |

The frontend, backend, build, and database versions, the container images, and the supported host and shell environment are recorded in [ADR 0009](docs/adr/0009-toolchain-baseline.md). The same record pins the Codex runtime image and executable at version 0.164.0 (P01.1). Thread, turn, isolation, and fixture compatibility are still being verified (P01.2–P01.5). The source project's package versions are evidence of its choices, not a claim about the newest supported releases.

Campaign data is stored locally. **Codex inference requires the configured Codex service and account access.** Local hosting does not imply offline AI, unlimited usage, or local model inference. Ollama, generic provider routing, local LLMs, and direct replacement of Codex with a generic chat API are outside the initial release.

**Planned startup:** run `./start.sh` to validate configuration, build/reuse images, start the containerized application and PostgreSQL, and print the local browser URL. The packaged profile will not require host Java, Maven, Node, or npm. The first supported host is Windows 11 with Docker Desktop (WSL 2 backend, Linux containers), running the launcher from Git Bash; it needs Docker Engine 29.0+, Compose 5.0+, and GNU Bash 4.4+. Initial Codex sign-in remains owner-controlled. See [container deployment and startup](docs/22-container-deployment-and-startup.md). The root Compose file currently supplies database/runtime infrastructure only; the engine service, packaged interface and launcher remain pending.

## Start reading

1. [Product vision and naming](docs/01-product-vision.md)
2. [Source analysis and reuse decisions](docs/02-source-analysis.md)
3. [Requirements, MVP, and complete release scope](docs/03-requirements-and-scope.md)
4. [Architecture](docs/04-architecture.md)
5. [Adventure pack specification](docs/09-adventure-packs.md)
6. [Turn-based combat specification](docs/11-combat-system.md)
7. [Development roadmap](docs/16-development-roadmap.md)

The [documentation index](docs/README.md) links every specification, decision record, and planning document.

## A generic engine with a concrete default

The core does not require a goddess, a fantasy world, Rune, Lyra, a particular protagonist, or a relationship storyline. Those belong in adventure content or optional modules. A default original ruleset, **AI RPG Skirmish**, is confirmed as the implementable starting point for tactical combat ([ADR 0006](docs/adr/0006-original-combat.md)). Its numbers are provisional until simulations and playtests calibrate them; packs may configure its data and select supported policies. Arbitrary new mechanics still require a reviewed engine extension. [ADR 0010](docs/adr/0010-name-scope-format-licensing.md) records the product name, the confirmed MVP and v1 boundaries, and the initial pack file format: UTF-8 YAML 1.2 or JSON for structured data, and CommonMark Markdown for prose.

The recommended first implementation is a local, single-owner modular application. Multiple campaigns are isolated from the beginning. Multiplayer, public hosting, a marketplace, arbitrary executable pack plugins, and a visual campaign editor are separate later initiatives.

The requested [integrated adventure editor](docs/26-adventure-editor-roadmap.md) follows core v1: text and graphical controls define world history, mandatory characters and other fixed requirements; only permitted gaps can be completed automatically. Fantasy/cyberpunk worlds use the same engine. The accepted combat ruleset is the default, with supported data/policy customization; new tactical algorithms still require an engine extension.

## Development and publication

See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), and [the publication guide](docs/18-github-publication.md). Project software and documentation are licensed under the [MIT License](LICENSE), copyright 2026 Azim Ben Othman. MIT permits use, modification, redistribution, and commercial use while requiring preservation of its copyright and permission notices. Source heritage and the upstream notice are recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Third-party adventures and assets retain their own terms; using the engine does not relicense user-created content. The licensing plan for sample packs, dependencies, and container images is in [ADR 0010](docs/adr/0010-name-scope-format-licensing.md#decision-licensing-plan).

The project name **AI RPG Engine** was selected by the owner. It directly describes a reusable engine for AI-powered role-playing adventures. The repository slug is `ai-rpg-engine`. Repository, domain, and trademark availability checks remain publication tasks.

The source projects and their live campaign data were not modified to prepare these documents. Local repository setup does not publish the project or configure a remote.
