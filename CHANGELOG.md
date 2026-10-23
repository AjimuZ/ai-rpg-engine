# Changelog

This changelog records delivered project artifacts. Future functionality belongs in [the roadmap](docs/16-development-roadmap.md), not in completed release notes.

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
