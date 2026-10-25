# AI RPG Engine

P05.6 is in progress: [localization review builds](frontend/src/locale/README.md)
provide 218 English/Italian messages across all local screens, sample passages,
validation, preview receipts, recovery and accessibility, with strict resource parity.
The compiled review variants support persisted language selection and safe preview
reload, retaining scoped drafts/selections and tab reading preferences/positions.
The jar/container packages both variants, with device-language selection at root
and explicit `/en/` and `/it/` preview routes. Real operation/cursor reconciliation,
transport localization and independent bilingual/accessibility acceptance remain open.

P05.5 delivers the original MIT [UI graphic kit](frontend/public/ui/README.md):
scalable controls, bars, icons, frames, independent sample item art and fallback
states. Settings links the browser-reviewed kit, editable-source inventory and
distributed notices. Campaign artwork persistence and runtime generation remain P17.

P02.4 ordinary CI is verified by [hosted run 40028086153](https://github.com/AjimuZ/ai-rpg-engine/actions/runs/40028086153), with all four jobs passing on the recorded source revision. This validates the current repository/shell checks, not gameplay or full packaged readiness.

P01.7 selects [serialized inference](tools/runtime_probe/CONCURRENCY.md): one
active turn installation-wide. Bounded scripted dispatch/cancellation evidence
does not prove live simultaneity, headroom or throughput; production scheduling
and any limit increase remain P08 work. The P01 live isolation recheck is open.

P01.6 records [image generation as unavailable in the managed narration
profile](tools/runtime_probe/IMAGE_CAPABILITY.md), with no supported generation
formats/invocation and unverified account/cost entitlement. The developer probe
refuses generation without blocking text discovery; production artwork jobs
and UI fallbacks remain P17 work.

**A Codex-powered engine for persistent, customizable RPG adventures, with Claude Code as a selectable alternative.**

AI RPG Engine separates the software that runs an adventure from the files that describe its world. Authors supply settings, characters, objectives, abilities, equipment, encounters, and presentation preferences. The engine supplies reliable state, deterministic rules, long-term continuity, turn-based combat, and AI orchestration through the selected narration runtime (Codex by default, Claude Code as a selectable alternative).

**Project status: neutral development shells; compatibility verification in progress.** The [Angular shell](frontend/README.md) and [Spring Boot shell](backend/README.md) provide build/test entry points, with canonical database foundations (P03.1–P03.4: adventure workspaces, campaigns, actors, bounded resources, content revisions, operations, events, reward settlements, checkpoints, ownership constraints, storage versions, an internal atomic commit/outbox boundary and versioned deterministic draw reservations) and an internal pack compiler/registry, but no gameplay or combat implementation. Every feature below is a development target. Packaged installation is not available yet. The developer [runtime compatibility probe](tools/runtime_probe/README.md) covers discovery (P01.1) and deterministic thread/turn lifecycle checks plus a successful owner-signed-in neutral output/resume check (P01.2), and the runtime isolation profile with no model tools and no inherited configuration (P01.3, [ADR 0013](docs/adr/0013-narration-runtime-isolation.md)). Runtime process shutdown, EOF/timeouts, stderr bounds and early-event buffering are covered by P01.4 developer checks ([lifecycle scope](tools/runtime_probe/README.md#process-lifecycle-p014)). The [compatibility matrix and retained neutral fixtures](tools/runtime_probe/COMPATIBILITY.md) record the exact supported developer baseline (P01.5); packaged startup remains pending.

Repository policies are in [CONTRIBUTING.md](CONTRIBUTING.md), with shared [editor defaults](.editorconfig), [Git line-ending rules](.gitattributes), and a sanitized [environment example](.env.example). The example's database labels now configure the infrastructure Compose services; full packaged setup remains future P02 work; host development commands are documented in each workspace. P02.3 delivers application-shell infrastructure, an opt-in
[single-origin jar build](backend/README.md#single-origin-jar-assembly-p023), [packaged file-secret configuration](backend/README.md#packaged-runtime-configuration-p023), and [packaging boundary checks](tools/container_packaging/README.md). The pinned multi-stage engine image and root Compose now serve the neutral shell on loopback, with isolated runtime IPC, file secrets and durable database/content/assets/runtime storage. Account-free build, HTTP, IPC and recreation checks pass; no account or gameplay readiness is implied. The development-only revision in [ADR 0009](docs/adr/0009-toolchain-baseline.md) permits these exact Java pins; refreshing Java before release remains mandatory. Ordinary credential-free [CI](.github/workflows/ci.yml) and [repository checks](tools/repository_checks/README.md) are defined (P02.4) but not yet confirmed by a hosted run. The root `./start.sh` launcher is implemented (P02.6) and was verified on Windows 11 with Docker Desktop from Git Bash; its idempotent launch, graceful stop, interruption exit status and restart/image-recreation preservation of neutral markers were checked there (P02.7, [evidence](tools/container_packaging/evidence/launcher-lifecycle-windows.json)). Owner sign-in, interactive Ctrl+C, Linux/macOS hosts, real campaign data and full QA-28 remain pending.

P03.5–P03.6 add internal mutation leases, startup recovery, database backup/restore,
durable preparation jobs and attempts, immutable planning snapshots, versioned plan
and dependency records, query epochs and recovery generations. Critical preparation
commits atomically with the canonical outbox; fenced admission changes no world
counters. Locale preferences remain separate from world revisions. PostgreSQL tests
and a populated V6 restore/startup rehearsal pass; scheduling, global budgets,
complete semantic plan validation and gameplay consumption remain future work.
See the [backend scope](backend/README.md) and [P03 evidence](docs/15-quality-and-acceptance.md#1-evidence-policy).

P04.1 adds the internal contract-1 manifest/parsing stage, structural schemas,
explicit file categories and deterministic raw-byte hashes. P04.2 adds the semantic compiler for IDs, dependencies,
cross-references, creation profiles, items, skills, abilities and world definitions;
P04.3 adds bounded expressions/effects, quests, cycle checks, version/hash
compatibility and a generation gate that cannot override fixed author constraints.
P04.4 adds bounded directory/ZIP staging, media header checks and immutable
transactional pack registration with original files and reports. These are internal
APIs. P04.5 adds standalone [fantasy exploration and science-fiction salvage
fixtures](backend/src/test/resources/packs/README.md) with directory/ZIP import
checks on the same engine build. P04.6 adds optional EN/IT text overlays, source
fallback, glossary/quote policies and sealed coverage reports without changing
mechanics or visibility. Both fixtures include bilingual catalog resources; import
screens, transport-localized UI, runtime narration and playable campaigns remain pending.
See the [pack contract](docs/09-adventure-packs.md).


P06.1 delivers atomic campaign creation from sealed
pack bytes, validated character budgets and authored grants, with durable retries
and V9 storage. Pinned external mechanics now load from a bounded sealed dependency
closure. An opt-in local creation API now requires a server-configured owner and
exact origin, bounded sessions and CSRF proof. All 290 packaged backend tests pass,
including two-genre campaign preservation across application process restart.
UI integration remains later work; P06.3 adds deterministic starting presentation.
See [backend scope](backend/README.md#atomic-campaign-creation-p061).

P06.5 now has admitted owner-scoped persisted adventure library/detail reads,
including empty workspaces and distinct same-title identities. Optional private
folders project sealed specifications/authored media and rebuild from PostgreSQL
on restart. Browser library binding, generated media and complete QA-33 remain
unfinished; see [workspace storage](docs/25-adventure-workspaces.md#2-separate-managed-folders).

P06.4 adds admitted exact `status`/`stato` and `inventory`/`inventario`
consultation reads. Responses separate transient typed passages from committed
source state; no runtime request, transcript write or world advance occurs.
Browser binding and optional durable transcript history remain later delivery work.

P06.3 adds a persisted starting decision and pack-defined starting text to protected
state/continue reads, using the saved narrative locale with explicit source fallback.
The player remains free to respond; no fixed prologue, guide or choices are injected.
All 299 packaged backend tests pass on PostgreSQL 18.6, including both genres,
EN/IT, source fallback, pinned authored text and restart preservation.
Browser API binding and command submission remain P05.6/P07.

P06.2 adds protected owner campaign listing and scoped continue/state, inventory,
learned ability and player-known fact/quest projections. Reads return one
consistent committed snapshot with no game effects; equal names cannot cross
campaign audiences. Starting decisions, text consultations and frontend API
binding remain later tasks. All 296 packaged backend tests pass on PostgreSQL
18.6, including eleven real HTTP tests; see [projection contracts](docs/13-api-and-ui.md#campaign-projections-p062).

## Why this engine exists

A long RPG campaign needs more than a conversation history. Inventory must remain consistent, unfinished promises must survive a new AI session, combat must use actual abilities and resources, and the narrator must respect what each character knows. AI RPG Engine is designed to make these responsibilities explicit and testable.

The design primarily derives from **The World I Can Develop**: Angular, Spring Boot, Java 21, PostgreSQL, modular RPG panels, backend-owned rules, and layered memory. Its playable demo contributes practical improvements: Codex app-server integration, renewable AI threads, public state projections, local consultations, request deduplication, checkpoint validation, and selective media updates. [The source analysis](docs/02-source-analysis.md) distinguishes observed implementation from plans and new proposals.

P05.1 adds [routed interface previews](frontend/README.md) for the library,
character creation, visual-novel story, sheet, inventory, journal and settings.
Two local genre samples use original scene illustrations and attributed passages;
Continue reads supplied text only. These shells have no connected campaigns or
world-action API. P05.2 binds terminology, attribute/resource names and optional
guide/creative panels to typed local public metadata, with neutral fallbacks and
scope-aware route checks. Registered pack/campaign binding remains P06; playable input,
localization and the full graphic kit remain later P05 work. P05.3 adds default
visual-novel and no-artwork text layouts, scoped speaker portraits, attributed
chronicle and keyboard focus handling for the end of available passages. P05.4
adds device-persisted scoped drafts/selection, editable typed contextual replies,
revision/callback guards and connection/recovery states. Choosing/dismissing a
suggestion never submits or advances reading/world state. Send remains disabled;
real campaign operations and status reconciliation remain P06/P07.

## Planned capabilities

The [research and gap review](docs/27-competitive-analysis-and-gaps.md) informs
the following targets without claiming implemented gameplay or benchmark
superiority. English is the default and Italian is required from the first
playable release. Important quests may receive [bounded background preparation](docs/28-asynchronous-narrative-planning.md)
through safe durable jobs; ordinary narration and the no-plan baseline remain
playable. [Local EN/IT speech](docs/30-local-speech-synthesis.md) is optional and
requires model/voice rights, quality and hardware checks. Reusable story bundles
can be imported/exported between people under the [pack contract](docs/09-adventure-packs.md#14-portable-stories-and-person-to-person-sharing);
the integrated graphical editor remains a later initiative. No new feature,
model installation or performance result is delivered by this documentation update.

- Load versioned adventure packs without changing engine source code.
- Run authored, emergent, or hybrid adventures without requiring a fixed plot.
- Generate narration, dialogue, encounter proposals, and tactical choices through Codex or, as a selectable alternative, Claude Code (one runtime at a time; Codex by default).
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
| AI integration | Backend-managed narration runtime behind one task port: Codex app-server by default (isolated packaged runtime with private IPC, subject to compatibility validation), or Claude Code in print mode, chosen by one setting ([ADR 0020](docs/adr/0020-selectable-narration-runtime.md)); the Claude runtime is planned (P20) |
| Browser updates | REST commands and resumable Server-Sent Events |
| Local environment | Docker Compose for the complete packaged application; root `start.sh` entry point; durable storage and isolated runtime |
| Retrieval | Structured and full-text retrieval first; optional semantic indexing after evidence of need |
| Verification | JUnit, PostgreSQL integration tests, Angular/Vitest, browser acceptance tests, contract fixtures for each supported narration runtime |

The frontend, backend, build, and database versions, the container images, and the supported host and shell environment are recorded in [ADR 0009](docs/adr/0009-toolchain-baseline.md). The same record pins the Codex runtime image and executable at version 0.164.0 (P01.1). Thread/turn lifecycle and the signed-in neutral smoke are verified (P01.2), and so is the image's isolation profile (P01.3); runtime lifecycle checks (P01.4) and retained compatibility records (P01.5) cover the developer baseline; authenticated packaged restart and other hosts remain unverified. The source project's package versions are evidence of its choices, not a claim about the newest supported releases.

Campaign data is stored locally. **Inference requires the selected runtime's service and the owner's account access (Codex by default, Claude Code if selected).** Local hosting does not imply offline AI, unlimited usage, or local model inference. Ollama, generic provider routing, local LLMs, a generic chat API, and running both runtimes at once or switching between them automatically are outside the initial release. Claude Code is a selectable alternative to Codex under [ADR 0020](docs/adr/0020-selectable-narration-runtime.md), with its own sign-in, billing and data-handling terms ([Claude runtime specification](docs/33-claude-runtime-integration.md)); it is planned, not implemented.

**ChatGPT device-login prerequisite:** for the container sign-in flow verified by this project, enable device-code login in your ChatGPT account's security settings. In a managed workspace, the administrator must allow this method. Then complete the browser link/code shown by the runtime and retain its dedicated account volume. Signing in to the desktop app alone does not sign in the isolated game runtime. See [account setup](tools/runtime_probe/README.md#owner-account-setup-for-the-live-check) and the [official authentication guide](https://learn.chatgpt.com/docs/auth). This requirement concerns the device-code sign-in method, not every authentication method supported upstream.

**Startup:** run `./start.sh` to check prerequisites, create the ignored `.env` and database secret once, build/reuse images, start the containerized application and PostgreSQL, wait for readiness, and print the local browser URL. Other commands: `--check`, `--status`, `--logs`, `--stop` (keeps data) and `--login` (owner-run Codex device-code sign-in). The packaged profile needs no host Java, Maven, Node, or npm. The first launcher target is Windows 11 with Docker Desktop (WSL 2 backend, Linux containers), running from Git Bash; it needs Docker Engine 29.0+, Compose 5.0+, and GNU Bash 4.4+. Initial Codex sign-in remains owner-controlled and is never performed by the launcher on its own; gameplay readiness arrives with later work. Manual Compose commands for the current account-free shell are in the [packaging guide](tools/container_packaging/README.md); the broader startup/authentication contract is in [container deployment and startup](docs/22-container-deployment-and-startup.md).

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
