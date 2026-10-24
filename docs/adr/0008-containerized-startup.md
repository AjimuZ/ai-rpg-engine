# ADR 0008: Containerized Local Application and Root Startup Launcher

**Status:** Owner-required packaging direction; runtime transport and implementation remain proposed and unverified. **Date:** 22 October 2026.

## Context

The owner requires the application to be Dockerized and easy to launch through `start.sh`. The earlier design containerized only PostgreSQL and ran Java and Codex on the host. That would require additional host toolchains and would not meet the requested packaged startup experience.

## Decision

Use Docker Compose for the packaged local installation: one modular Java engine serving compiled Angular assets, PostgreSQL, and a separately isolated Codex app-server runtime. Provide a root `start.sh` entry point with configuration/prerequisite checks, cached builds, health/readiness reporting, supported sign-in, status/log/stop operations, and persistent data. No host Java/Node build toolchain is required for this profile.

Target private Unix-socket IPC between backend and runtime through a Linux named volume, subject to P01 contract and isolation validation. Compose owns service processes; backend orchestration owns connections and conversation lifecycle. Keep stdio for local compatibility probes. Do not add a custom provider service, expose runtime control to the browser, or mount the Docker socket into the application to bridge containers.

This record supersedes the database-only deployment part of [ADR 0001](0001-modular-stack.md) and the mandatory child-process/stdio deployment assumption in [ADR 0002](0002-codex-only.md). The Angular/Java/PostgreSQL stack, modular backend, backend-owned rules, and Codex-only scope remain unchanged.

## Alternatives

A database-only Compose setup is useful for development but does not fulfill the packaged requirement. Bundling an unrestricted runtime with backend secrets weakens isolation. An additional frontend web server is unnecessary for the first packaged profile because the backend can serve the compiled interface. TCP runtime transport needs its own support, authentication, and exposure review; it is not an automatic substitute when the proposed IPC profile fails.

## Consequences and verification

Container images, runtime credentials, durable volumes, UID/socket permissions, image versions, health checks, migration behavior, and shutdown/recovery become explicit deliverables. The runtime must not inherit developer settings or access canonical storage. Windows validation uses Linux containers and an appropriate shell; other host support is evidence-based.

P01 must prove the pinned container/runtime/client combination. P02 implements packaging and the script. QA-28 verifies the complete startup/restart workflow; P14 and P19 require a fresh-clone rehearsal. No Dockerfile, Compose file, launcher, or passing runtime result is delivered by this decision record. See [the deployment specification](../22-container-deployment-and-startup.md).

Implementation evidence (P02.3, 23 October 2026): root Compose supplies pinned
engine/PostgreSQL/runtime images, private networks, file secrets, durable volumes
and the P01 socket layout. The multi-stage engine image serves compiled Angular
assets/notices from the executable jar as UID 10001 on host loopback. The engine
shares read-only socket IPC, never runtime account state. Account-free HTTP,
database/secret access, IPC handshake and neutral persistence across recreation
of all three services are verified. Content/assets directories are reserved
engine-only storage; no pack registration or generated assets exist yet.
This implements the existing direction without changing it. The root `start.sh` launcher (P02.6) adds prerequisite checks, one-time secret and `.env` creation, cached builds, health-based startup, owner-run sign-in, status/log/stop commands and nonzero failure results; restart, interrupt and recovery evidence is P02.7. Account/gameplay readiness, campaign recovery and full QA-28 remain later gates.
