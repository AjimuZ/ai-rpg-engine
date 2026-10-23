# Changelog

This changelog records delivered project artifacts. Future functionality belongs in [the roadmap](docs/16-development-roadmap.md), not in completed release notes.

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
