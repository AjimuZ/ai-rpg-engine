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
status-only health; canonical migrations, feature modules, runtime integration,
packaged assets, and full readiness contracts remain later tasks. See the
[frontend](../frontend/README.md) and [backend](../backend/README.md) build/test commands.

## 3. Backend modules

| Module | Responsibility | Main dependencies |
| --- | --- | --- |
| `identity` | Local owner identity and administrative privileges | Configuration and storage |
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

The current root Compose file is a partial infrastructure checkpoint: database
and runtime services only, with separate data/inference networks, private named
storage and tmpfs socket IPC. No ports are published until the engine service is
implemented. Neutral storage survives real container recreation; the engine
image/service and complete application startup remain pending. An opt-in Maven
profile now embeds compiled Angular assets and notices in the executable jar;
real HTTP tests verify single-origin serving independently of container packaging.
See the [backend build instructions](../backend/README.md).

## 8. Concurrency and background work

One campaign admits one state-changing operation at a time using a database-backed lease and fencing token. Queries remain available against the last committed revision. No database transaction stays open while waiting for Codex. At commit time the service verifies the state revision, lease token, current actor, and ruleset hash.

Summary generation and media work occur after canonical commit. The outbox retries publication without replaying gameplay. All background jobs have stable identity, source revision, retry policy, and stale-result handling. Bounded image jobs use the verified image capability, public appearance projections and durable entity/asset associations described in [ADR 0011](adr/0011-visual-novel-and-persistent-artwork.md) and [presentation assets](23-presentation-assets.md); an image job cannot mutate canonical item rarity, ownership or mechanics. Media failure cannot prevent a valid purchase, save or combat action.

## 9. Architectural evolution

Add full-text retrieval before semantic indexing. Add an authoring editor after the pack format stabilizes. Add a separate AI service only when profiling or required libraries make it necessary. Add alternate rules schedulers through explicit versioned extensions. A future generic provider is a separate product decision, not hidden v1 scope.
