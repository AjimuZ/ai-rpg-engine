# Changelog

This changelog records delivered project artifacts. Future functionality belongs in [the roadmap](docs/16-development-roadmap.md), not in completed release notes.

## Unreleased — Narration runtime isolation (P01.3), 23 October 2026

- Bake an engine isolation profile into the 0.164.0 runtime image: a managed configuration layer, an engine model catalog, neutral instructions, requirements pinning `never`/`read-only`/no search with no extension servers, and a startup guard refusing inherited home configuration or instruction files ([ADR 0013](docs/adr/0013-narration-runtime-isolation.md)).
- Add `test_isolation.py` with Docker fixtures. They prove an empty model tool list, six refused host tool calls with no canonical write, rejected or clamped wider thread policies, and ignored inherited configuration and launch flags. The guard refuses each inherited home file. Turns containing non-narration items now fail closed.
- Results: all 59 probe tests pass with Docker enabled, and the packaging tests pass, including recreation. A live re-check on the rebuilt image hit the account usage limit and remains open.

## Unreleased — Device-code login prerequisite, 23 October 2026

- Explain the ChatGPT security-setting/workspace permission required by the project's verified container device-code login flow in setup and integration guidance. Require actionable launcher guidance in P02.6–P02.7; keep other upstream login methods distinct and retain owner-controlled account setup.

## Unreleased — Authenticated neutral lifecycle completion (P01.2), 23 October 2026

- Verify one live neutral task after owner-operated device-code login in a dedicated runtime volume. Explicit `gpt-6-luna` / `low` settings produce a correlated completed envelope accepted by the independent validator; a second stdio process resumes the same completed turn and settings.
- Retain a sanitized runtime 0.164.0 report without account identifiers, credentials or thread/turn IDs. Rerun all 51 protocol tests with Docker enabled: all pass with no skips. Complete P01.2; deeper isolation, packaged startup, gameplay behavior and QA-08/QA-28 remain open.

## Unreleased — Development packaging prerequisite revision, 23 October 2026

- Permit local development image builds on the existing exact official Java 21.0.12.1 pins while the updated official images are unpublished. Retain the same distribution, base, digest checks and packaging acceptance; require the newest published Java update before release.
- Recheck both exact image index digests and the official tag API. This documentation revision unblocks further P02.3 work; it does not deliver an engine image, complete P02.3 or validate signed-in inference. Earlier checkpoint entries below retain their historical gate descriptions.
- Document owner-operated device-code sign-in in a dedicated runtime volume, keeping the development account separate; login and live neutral inference remain distinct checks.

## Unreleased — Packaged file-secret configuration (partial P02.3), 23 October 2026

- Add an opt-in Spring runtime profile requiring a file-secret config tree for the database password, with container HTTP binding and no fallback password; retain loopback host-development defaults.
- Verify actual configuration loading, newline handling, paths with spaces, host defaults and missing mount/file failures using neutral account-free tests. All nine packaged backend tests pass; a non-root executable jar authenticates from a mounted secret and serves exact compiled index/notices bytes. Engine image/service wiring remains gated by the official Java refresh; full packaged startup and QA-28 remain open.

## Unreleased — Single-origin jar assembly (partial P02.3), 23 October 2026

- Add an opt-in Maven profile embedding compiled Angular production assets and extracted third-party notices in the executable backend jar, served at one origin without an additional server.
- Verify the real HTTP index, bundles, notices and missing API/resource 404 responses against disposable PostgreSQL. Guard missing index/notices before packaging; keep ordinary backend builds independent of frontend output. Engine container packaging remains gated on the mandatory official Java image refresh; no launcher or full QA-28 acceptance is claimed.

## Unreleased — Isolated infrastructure (partial P02.3), 23 October 2026

- Add root Compose database/runtime services with separate networks, file-based database secrets, durable private storage, tmpfs control IPC, bounded logs, graceful stop and process/socket health checks.
- Add rendered-configuration boundary tests and an opt-in account-free Docker test proving password-authenticated database connectivity, network/UID/socket checks and neutral storage preservation across container recreation. Stop test services while retaining volumes and fixture secrets. Engine packaging and the single-origin interface remain pending the official Java image refresh; full startup and account readiness are unverified.

## Unreleased — Build-context isolation (partial P02.3), 23 October 2026

- Add default-deny root and independent runtime Docker context policies, excluding private configuration, credentials, data, Git history and generated output, including nested private paths.
- Add two real BuildKit export regression tests using neutral isolated fixtures. Engine image/Compose packaging remains pending because ADR 0009 requires official Temurin 21.0.13 or later images that are not yet published; no packaged runtime or persistence claim is made.

## Neutral development shells (P02.2), 23 October 2026

- Add pinned Angular and Spring Boot workspaces without adventure content, with strict frontend compilation, external PostgreSQL/Flyway configuration, and minimal status-only backend health.
- Add default shell tests and reproducible host build commands, checksum-verified Maven Wrapper policy, Central-only resolution, and explicit npm install-script policy. Pin transitive Vite 8.3.5 to resolve development-tool advisories without changing Angular; Windows/Linux locked installs, typechecks, default tests and production builds pass, as do full npm audit and the Java 21/PostgreSQL 18 backend tests and jar build. Packaged startup and canonical migrations remain later tasks.

## Unreleased — Repository policy and environment placeholders (P02.1), 23 October 2026

- Add shared EditorConfig defaults for UTF-8/LF, final newlines and indentation, preserving Markdown hard breaks; retain Git text normalization and binary image handling.
- Keep the owner-approved MIT license and contribution terms, document formatting/configuration policy, and ignore Python probe caches alongside private configuration and build output.
- Add neutral, credential-free environment placeholders with no current consumer. Application scaffolding, packaged startup and CI enforcement remain later P02 work.

## Unreleased — Thread and turn protocol fixture (partial P01.2), 23 October 2026

- Add a test-only scripted upstream on an internal Docker network, exercising seven real runtime turns, two threads and reconnect/resume without credentials. Verify explicit per-turn model/effort and full output schema at the upstream boundary, correlation, terminal outcomes and interruption of active streams.
- Bound and retain early notifications, deny unsolicited privileged requests, validate effective thread policies and redact unknown error variants. Add regression checks for malformed events, missing output, queue overflow, deadlines and the opt-in live profile's account/volume gates.
- Document reproducible checks and an owner-only neutral smoke profile. P01.2 remains open because signed-in inference has not run; production concurrency, deeper isolation and runtime restart remain separate gates.

## Unreleased — Pinned narration runtime and discovery probe (P01.1), 23 October 2026

- Add the narration runtime image `docker/codex-runtime/Dockerfile`. It installs runtime release 0.164.0, verified by checksum, on a digest-pinned Debian 13 base, runs as UID 10001, and listens on a private Unix control socket. ADR 0009 records the pins.
- Add `tools/runtime_probe`, a developer-only probe. It performs the handshake, the account-state read, and paginated catalog discovery over stdio, on the host or in a container, and over the packaged Unix socket through a Java 21 WebSocket relay. It also validates the output contract strictly and offline. It is not engine code and never signs in.
- Record the verified transport facts: WebSocket framing over the socket, the real socket location, and the `0600` UID boundary. Record the remaining gaps too: sign-in, live inference, the sandbox fallback, and `linux/arm64`.

## Unreleased — Later editor and customization plan, 23 October 2026

- Plan the integrated text/graphical adventure editor after core v1 using a separate E01–E06 checklist, with fixed author requirements, mandatory characters and bounded completion of unspecified details.
- Specify genre-independent fantasy/cyberpunk authoring and customizable combat data/policies around the default ruleset, preserving supported kernel boundaries and frozen encounters.
- Add QA-34 and align manual authoring, UI, requirements and roadmap semantics. No editor or new combat algorithm is implemented.

## Unreleased — Independent adventure-workspace design, 23 October 2026

- Separate personalized adventures from playthroughs/checkpoints, with managed folders, scoped specifications, drafts, memory and generated art.
- Add ADR 0012, proposed library/API/storage contracts, FR-032 and QA-33, and explicit implementation/restore gates. No runtime workspace is created by this change.

## Unreleased — Dynamic music design, 23 October 2026

- Specify optional event-driven music, reviewed free-license online/local sources, deliberate activation, stable loops/crossfades, privacy controls and failure fallback.
- Record two reference-only tracks with creator/source and distinct CC BY 4.0/3.0 credit templates; no audio is downloaded, bundled or implemented. Add P17.2/QA-32 acceptance work and third-party reference notices.

## Unreleased — Visual-novel and persistent-artwork design, 23 October 2026

- Make visual-novel play the default interface direction, with attributed narrator/character/player passages, editable contextual quick replies, a persistent free-input composer and a complete text-focused alternative.
- Add a primary narrative-screen concept and its prompt/provenance; retain the older illustration as a combat-screen concept. These are design artifacts, not running UI or separately usable production assets.
- Accept ADR 0011 and specify a complete UI graphic kit, stable saved character/item artwork, accessible item rarity and bounded runtime image generation for v1 with verified capability, budget and fallback boundaries.
- Align requirements, domain/content/narration contracts and P01/P05/P17 implementation tasks with QA-29–31. No interface, image-generation pipeline or rarity mechanics are implemented by this documentation change.

## Unreleased — Name, v1 boundaries, pack file format, and licensing plan (P00.5), 23 October 2026

- Add accepted ADR 0010. It records the product name AI RPG Engine and the identifiers derived from it (slug and Compose project `ai-rpg-engine`, Maven `io.github.ajimuz:ai-rpg-engine-backend`, Java package `io.github.ajimuz.rpgengine`, ruleset ID `skirmish`), and it confirms the MVP and v1 boundaries without change.
- Fix pack contract 1 (`schemaVersion: 1`): UTF-8 without a byte-order mark; YAML 1.2 core-schema `.yaml` files with no anchors, aliases, merge keys, tags, or duplicate keys; equivalent `.json`; CommonMark `.md` prose with no front matter or rendered HTML; JSON Schema draft 2020-12; directory or `.zip` distribution.
- Record the licensing plan: MIT for the engine and the neutral sample packs, third-party packs under their own terms, a permissive allowlist for bundled dependencies, locally built images that are not redistributed, and the name's trademark review left for P19.1.
- Align the pack specification, ADR 0004, the ADR index, risks and open decisions, publication guidance, and the README. No parser or application code is included.

## Unreleased — Default combat ruleset confirmation (P00.4), 23 October 2026

- Accept ADR 0006: AI RPG Skirmish is the implementable default ruleset, and every numerical default stays provisional until calibration in P13 and P18.3.
- Add a provisional numerical defaults register to the combat specification. It separates the confirmed structure (which needs a superseding ADR to change) from tunable values, gives each value its defining section and calibration gate, and requires the values to live in ruleset-profile data with versioned revisions.
- Record that the default advancement profile has no maximum level; a pack may declare one.
- Align the ADR index, risks and open decisions, gameplay, encounter, worked-scenario, and README wording. No rules code is included, and no balance is claimed.

## Unreleased — Host, container image, Compose, and shell baseline (P00.3), 23 October 2026

- Extend ADR 0009 with the first validation target: Windows 11 x64 with Docker Desktop (WSL 2 backend, Linux containers) and Git Bash. Bash in a WSL distribution becomes a candidate; other hosts remain unclaimed.
- Pin by multi-platform index digest: Dockerfile frontend 1.27.1, `node:24.22.1-trixie-slim`, and Eclipse Temurin `21.0.12.1_1-jdk-resolute` / `-jre-resolute`. Require a refresh to the Temurin 21.0.13 images before the first engine image.
- Set the launcher floors (Docker Engine 29.0, Compose 5.0, Buildx, GNU Bash 4.4), the allowed host commands, the Git Bash path-conversion rules, and a digest-refresh policy for every image.
- Record a throwaway Compose probe from Git Bash: an Angular build, a Maven Wrapper build, and a Spring Boot JRE runtime against PostgreSQL 18.6. It covered health-gated startup, secrets, an internal network, and a loopback-only port, and its findings became rules.
- Add the image-refresh risk and align the architecture, deployment, risk, README, and ADR index baselines. No container, launcher, or runtime file is included.

## Unreleased — Backend and database baseline (P00.2), 23 October 2026

- Extend ADR 0009 with Spring Boot 4.1.2 on Java 21, the libraries managed by Spring Boot (including Flyway 12.4.0 and PostgreSQL JDBC 42.7.14), Maven 3.10.0 through an `only-script` Maven Wrapper 3.3.4 with a distribution SHA-256, and PostgreSQL 18.6 (`postgres:18.6-trixie` pinned by digest) with matching `pg_dump`/`pg_restore`.
- Record the evidence: support schedules, Maven Central metadata, and a throwaway wrapper build on a JDK 21 against PostgreSQL 18.6. The build covered checksum and toolchain enforcement, four integration tests, Maven Central artifact origins, and a dump/restore round trip.
- Record the implementation rules found by the probe: `unzip` for Linux wrapper runs, Maven Central-only resolution despite Flyway's extra repository declarations, and the PostgreSQL 18 volume path.
- Add the backend support-window risk, and align the architecture, deployment, risk, and README baselines. No backend workspace or runtime code is included.

## Unreleased — Frontend toolchain baseline (P00.1), 22 October 2026

- Add ADR 0009 pinning Angular 22.2.3, TypeScript 6.0.3, Node.js 24.22.1 LTS, npm 11.19.1, RxJS 7.8.2, tslib 2.8.1, and Vitest 5.0.5.
- Record the compatibility evidence: registry engines and peer ranges, support schedules, a conflict-free lockfile resolution, and the rejection of TypeScript 7.
- Record the workstation tool inventory; the host has Java 27, so the Java 21 baseline remains for P00.2.
- No frontend workspace or runtime code is included.

## Unreleased — Development branch policy, 22 October 2026

- Use `main` as the single project branch: verified implementation commits are pushed directly to `main`. A `develop` branch used briefly the same day was fast-forwarded into `main` and removed.
- Exclude force pushes and published-history rewrites.
- Require implementation commits to name their roadmap item in a final `Roadmap: <item>` body line.

## Unreleased — Container deployment design, 22 October 2026

- Record the owner requirement for a fully containerized local application launched through root `start.sh`.
- Specify the packaged topology, private runtime transport gate, account setup, durable volumes, readiness, and safe lifecycle behavior.
- Add FR-029, QA-28, ADR 0008, and corresponding implementation/release roadmap tasks.
- Align architecture, operations, integration, and publication guidance; no executable container or launcher files are implemented in this milestone.

## Unreleased — Development workflow, 22 October 2026

- Require immediate roadmap and affected-documentation updates after each completed development item or project change.
- Define neutral English commit-message conventions and align the roadmap's suggested messages.
- Record the distinction between local checkout enforcement, semantic review, and future continuous integration; private assistant configuration remains untracked.

## Unreleased — Project licensing, 22 October 2026

- Add the MIT License with copyright 2026 Azim Ben Othman.
- Document license scope, contribution terms, and separate ownership of adventure packs and third-party assets.
- Preserve the upstream MIT notice and resolve the pending repository-license decision.
- Update the publication guide with the private repository status and remaining public-repository preparation.

## Unreleased — Repository preparation, 22 October 2026

- Add Git exclusions for local assistant configuration, environment files, private runtime output, and generated artifacts.
- Normalize text line endings and preserve the UI concept image as binary.
- Document the initial commit scope and pre-commit review commands.

## Unreleased — Documentation baseline, 22 October 2026

- Adopt AI RPG Engine as the owner-selected project name and `ai-rpg-engine` as the project directory and repository slug.
- Document source-project maturity, reusable patterns, conflicts, and improvements.
- Specify Angular/Spring Boot/PostgreSQL architecture and a backend-owned Codex integration.
- Specify immutable adventure packs, canonical state, command transactions, knowledge boundaries, checkpoints, and renewable AI sessions.
- Design the original AI RPG Skirmish turn-based ruleset, bounded encounter generation, and advancement policies.
- Define API/UI behavior, security, recovery, testing targets, worked scenarios, and architectural decisions.
- Provide an ordered development roadmap through a complete initial engine and GitHub release preparation.

No application code, executable configuration, schema implementation, remote repository, or runtime test result is included in this milestone.
