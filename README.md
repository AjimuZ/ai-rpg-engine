# AI RPG Engine

**A Codex-powered engine for persistent, customizable RPG adventures.**

AI RPG Engine separates the software that runs an adventure from the files that describe its world. Authors supply settings, characters, objectives, abilities, equipment, encounters, and presentation preferences. The engine supplies reliable state, deterministic rules, long-term continuity, turn-based combat, and AI orchestration through Codex.

**Project status: documentation and design only.** There is no runnable AI RPG Engine application, backend, database schema, adventure compiler, or combat implementation in this repository. Every feature below is a development target. No installation command is currently available.

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
- Export campaigns and authoring files without exposing secrets through the player UI.

## Technology direction

| Area | Proposed baseline |
| --- | --- |
| Web interface | Angular 22 baseline inherited from the source project; TypeScript; standalone components; signals; SCSS |
| Authoritative application | Spring Boot; Java 21 LTS; Maven |
| Persistence | PostgreSQL; Flyway migrations; relational state and JSONB extension data |
| AI integration | Backend-managed Codex app-server process over local standard input/output |
| Browser updates | REST commands and resumable Server-Sent Events |
| Local environment | Docker Compose for PostgreSQL; host backend and Codex initially |
| Retrieval | Structured and full-text retrieval first; optional semantic indexing after evidence of need |
| Verification | JUnit, PostgreSQL integration tests, Angular/Vitest, browser acceptance tests, Codex contract fixtures |

Exact supported dependency patches and the supported Codex protocol baseline must be recorded before scaffolding. The source project's package versions are evidence of its choices, not a claim about the newest supported releases.

Campaign data is stored locally. **Codex inference requires the configured Codex service and account access.** Local hosting does not imply offline AI, unlimited usage, or local model inference. Ollama, generic provider routing, local LLMs, and direct replacement of Codex with a generic chat API are outside the initial release.

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

The core does not require a goddess, a fantasy world, Rune, Lyra, a particular protagonist, or a relationship storyline. Those belong in adventure content or optional modules. A default original ruleset, **AI RPG Skirmish**, supplies an implementable starting point for tactical combat; packs may configure its data and select supported policies. Arbitrary new mechanics still require a reviewed engine extension.

The recommended first implementation is a local, single-owner modular application. Multiple campaigns are isolated from the beginning. Multiplayer, public hosting, a marketplace, arbitrary executable pack plugins, and a visual campaign editor are separate later initiatives.

## Development and publication

See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), and [the publication guide](docs/18-github-publication.md). Project software and documentation are licensed under the [MIT License](LICENSE), copyright 2026 Azim Ben Othman. MIT permits use, modification, redistribution, and commercial use while requiring preservation of its copyright and permission notices. Source heritage and the upstream notice are recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Third-party adventures and assets retain their own terms; using the engine does not relicense user-created content.

The project name **AI RPG Engine** was selected by the owner. It directly describes a reusable engine for AI-powered role-playing adventures. The repository slug is `ai-rpg-engine`. Repository, domain, and trademark availability checks remain publication tasks.

The source projects and their live campaign data were not modified to prepare these documents. Local repository setup does not publish the project or configure a remote.
