# Technical Architecture

## 1. Architectural style

Use an Angular frontend and a **modular Spring Boot application**. PostgreSQL is the canonical store. The backend owns command processing, rules, persistence, knowledge filtering, and the Codex integration. Start with one deployable backend rather than separate gameplay, memory, and orchestration services.

The primary project's stack is retained. The initial AI adapter is specific to Codex. An internal boundary keeps protocol details out of the domain; it does not imply a generic multi-provider product.

```mermaid
flowchart LR
    UI[Angular RPG interface] -->|REST commands and queries| API[Spring Boot API]
    API --> APP[Campaign application services]
    APP --> RULES[Deterministic rules and combat]
    APP --> MEMORY[Knowledge and context assembly]
    APP --> AI[Codex orchestration port]
    AI --> BRIDGE[Java app-server adapter]
    BRIDGE -->|Private IPC| CODEX[Isolated Codex runtime]
    CODEX --> SERVICE[Configured Codex service]
    APP --> DB[(PostgreSQL)]
    RULES --> APP
    DB --> OUTBOX[Committed presentation outbox]
    OUTBOX -->|SSE| UI
    PACK[Validated adventure files] --> IMPORT[Pack compiler and registry]
    IMPORT --> DB
    ASSETS[Registered local assets] --> API
```

The diagram describes dependencies and data exchange. It does not grant the Codex process database access.

## 2. Stack baseline

| Area | Decision | Version policy |
| --- | --- | --- |
| Frontend | Angular, TypeScript, standalone components, signals, SCSS, zoneless style | Angular 22.2.3, TypeScript 6.0.3, Node.js 24.22.1 LTS, and npm 11.19.1 pinned by [ADR 0009](adr/0009-toolchain-baseline.md); committed lockfile, no `latest` resolution |
| Async UI | Angular HTTP client; RxJS for streams and cancellation; signals for view state | RxJS 7.8.2 within Angular's supported peer range ([ADR 0009](adr/0009-toolchain-baseline.md)) |
| Backend | Spring Boot on Java 21 LTS | Spring Boot 4.1.2 compiled with `release 21` and run on a JDK 21 ([ADR 0009](adr/0009-toolchain-baseline.md)); libraries take their versions from the Boot BOM; Eclipse Temurin 21 JDK build and JRE runtime images on Ubuntu 26.04 LTS, pinned by digest |
| Build | Maven Wrapper | Committed `only-script` Maven Wrapper 3.3.4 running Maven 3.10.0, verified by SHA-256; no version ranges or snapshots; Maven Central only with strict checksums ([ADR 0009](adr/0009-toolchain-baseline.md)) |
| Data access | Spring Data JPA for aggregate persistence; explicit queries for projections | Keep domain calculations independent of entities |
| Database | PostgreSQL 18 | `postgres:18.6-trixie` pinned by digest; `pg_dump`/`pg_restore` 18.6 from the same image; major upgrades are explicit migrations with a verified backup ([ADR 0009](adr/0009-toolchain-baseline.md)) |
| Migrations | Flyway (open-source edition with the PostgreSQL module) | Flyway 12.4.0 managed by Spring Boot 4.1.2; forward migrations; backup and restore for destructive upgrades |
| JSON | Jackson with explicit DTOs and strict bounds | Reject unknown operation types and unsafe polymorphic input |
| Streaming | Server-Sent Events with durable cursor | Polling fallback; no dependency on browser access to Codex |
| AI | Codex app-server managed by the Java adapter; packaged private IPC and local stdio probe profiles | Versioned adapter/container compatibility matrix; no assumed official Java SDK |
| Environment | Complete Docker Compose installation with root `start.sh`; backend serves compiled Angular assets | Digest-pinned Linux images with a refresh policy. Docker Engine 29.0+, Compose 5.0+, and Buildx; GNU Bash 4.4+. Windows 11 with Docker Desktop on WSL 2 and Git Bash is the first target ([ADR 0009](adr/0009-toolchain-baseline.md)). Verify the isolated runtime, durable volumes, health checks, and the supported shell/host matrix |

Do not select library versions merely by copying an old README or resolving “latest” during a build. Reproducible build versions and actual compatibility tests are required.

P02.2 provides neutral `frontend/` and `backend/` development shells with the pinned
toolchains and no adventure constants. The backend has JDBC/Flyway wiring and minimal
status-only health; runtime integration, packaged assets and full readiness
contracts were later P02 tasks. P03.1 adds Spring Data JPA, the first Flyway
migration and the canonical foundation entities in the `identity`, `adventure`,
`content`, `campaign`, `commands`, `characters`, `progression`, `memory`,
`presentation` and `persistence` packages. P03.3 adds an internal canonical commit service with a durable reference-only outbox; command APIs, semantic rules, runtime orchestration and outbox delivery remain later work. See the
[frontend](../frontend/README.md) and [backend](../backend/README.md) build/test commands.

## 3. Backend modules

P04.1 adds a data-only manifest/parsing/hash stage in `content`, using fixed
classpath schemas and no I/O ports. Its immutable `ParsedPack` is distinct from
an admitted `ContentRevision`; semantic compilation and protected staging/registry
operations remain P04.3–P04.4. P04.2 adds the pure `PackCompiler` semantic stage
(IDs, dependencies through a local catalog, cross-references, creation, items,
skills, abilities and world definitions) producing a `CompiledPack`. See the [pack contract](09-adventure-packs.md).

| Module | Responsibility | Main dependencies |
| --- | --- | --- |
| `identity` | Local owner identity and administrative privileges | Configuration and storage |
| `adventure` | Adventure workspaces that own content revisions and campaigns ([docs/25](25-adventure-workspaces.md)) | Identity, persistence |
| `content` | Parse, validate, compile, register, and version packs | Rules catalog, asset registry |
| `campaign` | Campaign creation, activation, state revision, lifecycle | Content, persistence |
| `commands` | Command taxonomy, idempotency, leases, operation state machine | Campaign, rules, orchestration |
| `rules` | Prerequisites, expressions, legal operations, effects, random-draw protocol | Pinned ruleset definitions |
| `characters` | Attributes, skills, resources, conditions, ability availability | Rules |
| `inventory` | Item identity, ownership, stacks, slots, durability, trade | Characters, rules |
| `quests` | Objective predicates, state transitions, rewards | Domain events, rules |
| `world` | Locations, clocks, factions, schedules, causal triggers | Rules and domain events |
| `social` | Relationships, commitments, NPC beliefs and goals | Knowledge and events |
| `combat` | Encounters, initiative, legal turns, reactions, resolution, settlement | Rules, characters, inventory |
| `progression` | XP, milestones, purchases, stat caps, reward ledger | Rules and events |
| `knowledge` | Fact scopes, provenance, public projections, disclosure | Campaign and events |
| `memory` | Checkpoints, summaries, retrieval, context budgets | Knowledge, events |
| `ai` | Task profiles, prompt contracts, proposal parsing, model routing | Context and Codex adapter |
| `codex` | Runtime transport/lifecycle, protocol framing, events, cancellation | Private IPC or local process API according to deployment profile; no domain repositories |
| `presentation` | Narrative records, scene references, safe media cues | Committed events and projections |
| `operations` | Backups, imports/exports, diagnostics, recovery | All modules through administrative services |
| `persistence` | Shared entity base, engine-assigned identifiers and canonical value checks | JPA only; no domain rules |

These are logical packages initially, not separate Maven artifacts or services. Split further only when actual complexity warrants it.

## 4. Dependency rules

- The domain rules layer must not depend on HTTP, Angular, prompt templates, or the Codex transport.
- Controllers validate transport shape and invoke application services; they do not calculate damage or manipulate entity repositories directly.
- AI returns typed proposals. Only command services can request canonical transitions.
- Query services return purpose-built projections; serializing JPA entities into player responses is prohibited.
- Modules communicate using narrow services and typed domain events. A presentation listener cannot award loot.
- One application transaction coordinates changes spanning a resolved action. Avoid distributed transactions.
- JSONB is appropriate for namespaced extension values and immutable compiled definitions. Core ownership and balances need constraints and queryable fields.

## 5. Ports

The proposed `CodexGameMasterPort` exposes task-oriented generation: interpret intent, propose content, choose a legal tactic, narrate resolved events, and summarize an event range. Domain services never send raw arbitrary protocol methods.

`RulesetPort` resolves legal actions and effects from a pinned ruleset. `CampaignRepository` loads canonical state and commits revisions. `KnowledgeQueryPort` provides permitted facts for an audience. `PresentationPublisher` emits committed output through an outbox. `RandomSource` produces and records server-owned draws. Tests can replace these boundaries independently.

## 6. Runtime separation

| Boundary | Data allowed across it |
| --- | --- |
| Browser → backend | Validated commands, preferences, content imports selected by owner |
| Backend → browser | Authorized projections, public narration, legal choices, operation status |
| Backend → Codex | Minimal task instructions, authorized context, supported structured output contract |
| Codex → backend | Untrusted structured proposals or narrative, never canonical records |
| Pack → runtime | Compiled, validated content and bounded expressions |
| Canonical store → summaries | Provenance-linked facts/events; no replacement of the original records |

The player should not see RPC logs, chain-of-thought, command output, unfiltered model commentary, private relationship metrics, or hidden creature tactics. Developer diagnostics must be separately permissioned and redacted.

## 7. Local deployment

The packaged installation runs the modular backend with compiled Angular assets, PostgreSQL, and an isolated Codex runtime through Docker Compose. A root `start.sh` validates prerequisites, builds or reuses images, starts services, waits for readiness, and reports the loopback URL. It preserves campaign/content/asset volumes and does not require host build toolchains. A frontend development server and host stdio probe are optional development workflows. This replaces the earlier database-only Compose baseline under [ADR 0008](adr/0008-containerized-startup.md).

The proposed packaged transport is a private Unix socket in a Linux IPC volume shared only by engine and runtime. Compose owns the runtime service; the backend owns its RPC connections and turns. P01 must prove the selected container, client, permissions, and authentication profile. See [container deployment and startup](22-container-deployment-and-startup.md) for the launcher contract, storage boundaries, and delivery gates.

AI conversations have isolated runtime workspaces outside the source checkout and pack directories. The bridge exposes neither database credentials nor database network access to tools. Authentication needed by Codex remains managed by supported Codex mechanisms. Log and credential handling are detailed in [security and operations](14-security-and-operations.md).

The app binds to loopback by default. GitHub publication distributes source; it does not expose a public gameplay server. A public-hosting design needs a separate authentication, tenant isolation, credential, abuse, and deployment review.

The root Compose file packages the neutral shell as `engine`, `postgres` and
`codex`. The digest-pinned multi-stage engine build embeds Angular assets/notices
in the jar; a JRE-only non-root service serves them on loopback. Only engine and
postgres join internal `data`; only runtime joins `inference`; engine alone joins
`web` for published HTTP. Engine and runtime share UID 10001 for private socket
access, but never account/data mounts. Engine mounts socket IPC read-only and
owns separate content/assets volumes. Its Spring `packaged` profile consumes
the database file secret; the host profile remains loopback-only. Account-free
HTTP, IPC, secret and neutral storage recreation checks pass. The application
adapter, launcher, gameplay readiness and campaign recovery remain later work.
See the [backend build instructions](../backend/README.md). ADR 0009's subsequent development-only revision permits continued local image work on the verified existing Java pins; the newest published Java update remains a release gate.

## 8. Concurrency and background work

One campaign admits one state-changing operation at a time using a database-backed lease and fencing token. Queries remain available against the last committed revision. No database transaction stays open while waiting for Codex. At commit time the service verifies the state revision, lease token, current actor, and ruleset hash.

Summary generation and media work occur after canonical commit. The outbox retries publication without replaying gameplay. All background jobs have stable identity, source revision, retry policy, and stale-result handling. Bounded image jobs use the verified image capability, public appearance projections and durable entity/asset associations described in [ADR 0011](adr/0011-visual-novel-and-persistent-artwork.md) and [presentation assets](23-presentation-assets.md); an image job cannot mutate canonical item rarity, ownership or mechanics. Media failure cannot prevent a valid purchase, save or combat action.

## 9. Architectural evolution

Add full-text retrieval before semantic indexing. Add an authoring editor after the pack format stabilizes. Add a separate AI service only when profiling or required libraries make it necessary. Add alternate rules schedulers through explicit versioned extensions. A future generic provider is a separate product decision, not hidden v1 scope.

## 10. Research revision: planning, language and optional media

Retain the Angular/Spring/Java/PostgreSQL modular monolith. Extend existing
background work with one PostgreSQL durable priority queue and distinct logical
intent/narration, quest-planning and occasional world-direction tasks. Begin with
one active inference turn; qualified concurrency is an opt-in capability, not a
claim that threads create independent provider capacity. The [planning contract](28-asynchronous-narrative-planning.md)
owns snapshots, dependency-sensitive admission, lifecycle, fairness and budgets;
ADR 0014 preserves ADR 0013's verified tool-free runtime isolation. No broker,
autonomous-agent framework, runtime pool or vector database is required.

Interactive work has reserved admission and bounded waiting. No transaction or
campaign mutation lease is held while a deep background plan runs. Planning
artifacts are durable noncanonical preparation; completion never advances the
world clock, awards a reward or forces the next player action. Typed command
services revalidate dependencies and activation. Hidden planner context never
enters a public narrator thread, media cue or loading diagnostic.

The [bilingual contract](29-localization.md) adds locale variants/preferences and
direct selected-language narrative output without replacing canonical IDs.
An optional [local speech worker](30-local-speech-synthesis.md) is a justified
media process for Python/ONNX libraries, not a second game backend or text-AI
provider. It receives only validated public lines and admitted voice references,
has no database/account mount, and shares bounded media/GPU admission with image
jobs. SSE delivers metadata/asset IDs; complete audio segments use authorized
asset serving, not raw model tokens or executable frontend directives.

Verified status remains the existing shell/probe/partial packaging evidence.
These architectural additions are design only; their EN/IT, concurrency, narrative
quality and GPU checks are scheduled separately and have not run.
