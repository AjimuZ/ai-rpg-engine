# Container Deployment and Single-Command Startup

## 1. Status and owner requirement

**Design specification only.** The owner requires a Dockerized application launched through a root `start.sh` file. This document defines that delivery target; no launcher, Dockerfile, Compose configuration, image, or runnable application has been implemented yet. The requirement applies to the engine and every adventure, without campaign-specific startup scripts.

The packaged local installation must run the application, database, and isolated Codex runtime through Docker Compose. Users must not need host Java, Maven, Node, or npm to build and launch that packaged profile. Codex service connectivity and supported account access are still required. Host-based development and stdio protocol probes remain optional developer workflows, not the completed packaged installation.

This supersedes the earlier database-only Compose plan. The decision and compatibility implications are recorded in [ADR 0008](adr/0008-containerized-startup.md).

## 2. Runtime topology

| Compose service | Responsibility | Access and persistence |
| --- | --- | --- |
| `engine` | Spring Boot API, deterministic rules, background jobs, and compiled Angular assets at one browser origin | Canonical database connection; registered content/assets; private runtime IPC |
| `postgres` | Canonical campaign state, receipts, events, checkpoints, and migrations | Private data network; dedicated durable database volume |
| `codex` | Pinned app-server runtime and isolated role/campaign workspaces | Own account/state volume and inference egress; no database credentials, data volume, or source checkout |

The engine remains one modular Java application. A dedicated runtime container is an isolation and packaging boundary, not a split into gameplay microservices or a generic provider service. A multi-stage engine image builds Angular and the Java application with pinned toolchains, then serves the compiled interface from the Java runtime. A separate frontend development container may be offered later for hot reload; it is not required for normal play.

Only the engine's web entry point is published, bound to loopback. The proposed default is `http://127.0.0.1:8080`, configurable through a documented application-port setting. PostgreSQL and runtime-control endpoints are not published to the host. Keep the database network separate from the runtime's inference network.

## 3. Runtime integration gate

The packaged design targets a private Unix-domain socket shared between `engine` and `codex` through a Docker-managed Linux IPC volume. The volume contains only the control socket; database data and credentials are never placed there. The backend adapter must support the selected transport, permissions, framing, reconnect, and request correlation. Compose owns the runtime container; the backend owns its connections, threads, and turns. Renewing a thread does not recreate the container or reset a campaign.

Official [app-server documentation](https://learn.chatgpt.com/docs/app-server) describes Unix-socket and stdio connections and identifies TCP WebSocket transport as experimental and unsupported. P01 must validate the exact pinned Linux runtime and Java client before accepting this transport. No production-support claim is inferred from the availability of a command option. A different transport requires an explicit decision update, authentication/isolation review, and new contract evidence; the launcher must not silently expose a TCP listener or mount the Docker socket into the backend to run `docker exec`.

Stdio remains the isolated local protocol-probe option. If the packaged runtime cannot satisfy authentication, isolation, or connection requirements, containerized delivery remains incomplete. It is not replaced with a different model provider or silently presented as a fully Dockerized host-runtime setup.

## 4. Planned repository artifacts

| Future artifact | Purpose |
| --- | --- |
| Root `start.sh` | Stable, executable entry point for ordinary local operation |
| Root `compose.yaml` | Named services, networks, volumes, health checks, secrets, and lifecycle settings |
| `docker/` | Reviewed engine/runtime image definitions and build support |
| Root `.dockerignore` | Exclude credentials, local assistant settings, private data, Git history, and irrelevant build context |
| Root `.env.example` | Sanitized public configuration example without working credentials |
| Version/compatibility records | Pin build/runtime images, dependency locks, runtime protocol, Compose features, and supported host environment; the pre-scaffold baseline is in [ADR 0009](adr/0009-toolchain-baseline.md) |

These paths name deliverables to create during implementation, not files currently present. Keep actual `.env`, secret files, account state, and private data untracked. Build context must exclude local `.agents`, `.claude`, `.codex`, `AGENTS.md`, and `CLAUDE.md`; gameplay containers do not inherit development instructions or hooks.

## 5. Launcher contract

The following commands describe the future interface and cannot be run successfully against this documentation-only repository:

```text
./start.sh           Validate, build with cache, start services, and report readiness
./start.sh --check   Inspect prerequisites and configuration without changing campaign data
./start.sh --login   Run the supported owner-controlled runtime sign-in flow
./start.sh --status  Show service and gameplay readiness
./start.sh --logs    Follow bounded service logs with secret redaction
./start.sh --stop    Stop this installation while preserving persistent data
./start.sh --help    Explain prerequisites and supported commands
```

Normal startup must:

1. Resolve paths relative to the script itself, including when invoked from another directory or a path containing spaces. Use LF line endings and preserve the Git executable bit.
2. Check the container CLI, a reachable daemon running Linux containers, the Docker Engine, Compose, and Buildx floors, the selected Docker context, required configuration, and application-port availability. Report an actionable failure; do not install software, change Docker contexts, or request elevated privileges automatically.
3. Establish a stable installation/project identity. Repeated launches must reuse the same services and volumes; separate installations must not accidentally share them.
4. Initialize missing private configuration safely, without overwriting owner values or generating known/shared passwords. Create secrets from an operating-system cryptographic random source, or require explicit owner provisioning when secure generation is unavailable.
5. Build or reuse pinned images with dependency/build caches, then start only this Compose project's services. First-run downloads and builds may take time and require network access; subsequent starts should reuse unchanged layers and images.
6. Wait for database health, complete supported migration and operation-reconciliation steps, then verify the web/API and runtime connection. Container creation order alone is not readiness.
7. Distinguish API/storage readiness from gameplay readiness. Check runtime account/capability availability without generating a scene or consuming a gameplay turn. Never perform a live inference merely as an automatic health check.
8. Print the local browser URL, effective status, and supported stop/log commands. Report failure with a nonzero exit code and bounded, redacted diagnostics. Never print secrets or raw account files.

Missing runtime authentication must lead to the supported sign-in instructions and a clear incomplete-gameplay status. Saved-data inspection can remain available while narration is unavailable. A successful packaged-gameplay readiness result requires the runtime gate to pass; starting the web server alone is insufficient.

No startup, restart, log, or stop action may reset campaigns, regenerate existing secrets, prune unrelated resources, or delete persistent volumes. Partial startup and interruption must be recoverable by rerunning the launcher. A data reset, if introduced later, is a separate explicitly destructive owner operation, never an automatic repair.

## 6. Host and authentication prerequisites

The host needs:

- Docker Engine 29.0 or later running Linux containers, the Docker Compose plugin 5.0 or later, and the Buildx plugin;
- GNU Bash 4.4 or later, with the limited host-command set in [ADR 0009](adr/0009-toolchain-baseline.md#shell-requirements);
- enough disk and memory (provisionally at least 10 GB of free Docker disk space);
- network access for first builds and inference.

The first validation target is Windows 11 x64 with Docker Desktop's WSL 2 backend and Linux containers, running `start.sh` from Git Bash. That combination was verified with Docker Desktop 4.96.3, Engine 29.9.2, Compose v5.6.1, and Bash 5.3 for the pinned images and Compose features; the launcher itself is verified in P02.7. Bash inside a WSL distribution is an unverified candidate. Native PowerShell and `cmd.exe` do not run the shell script. Linux and macOS support must be claimed only after their launcher and runtime checks pass; macOS's default Bash 3.2 is below the floor.

Codex authentication is completed by the owner using supported mechanisms inside the dedicated runtime environment. Persist its state in a private runtime-only volume; never bake credentials into an image or mount the owner's entire home/development configuration. The planned `--login` command must document the supported interactive or device flow for the pinned version and handle refresh/expiry. See [official authentication guidance](https://learn.chatgpt.com/docs/auth) and [the integration specification](06-codex-integration.md).

The launcher does not guarantee that a desktop/editor login transfers into Linux containers. Account entitlement, service availability, rate limits, and billing remain applicable. Ordinary CI checks use fixtures and do not require account credentials.

## 7. Data, secrets, and lifecycle

Use distinct persistent storage for PostgreSQL, registered content/assets, runtime account/session state, and temporary workspaces. Canonical state belongs to PostgreSQL, not the container's writable layer or the runtime transcript. Backups must include the database, pinned content revisions, and referenced registered assets; a surviving Docker volume alone is not an independent backup.

Within managed content/asset storage, keep each adventure's specifications and campaign-specific generated scene/portrait/item folders separate as defined in [adventure workspaces](25-adventure-workspaces.md). Database mappings remain canonical; container restart retains those identities and original media without regeneration or cross-world folder reuse.

The pinned PostgreSQL 18 image keeps its cluster in `/var/lib/postgresql/18/docker`. Mount the database volume at `/var/lib/postgresql`; the `/var/lib/postgresql/data` path used by PostgreSQL 17 and earlier images would not persist data across container re-creation. Moving to a new PostgreSQL major version is an explicit backup-and-migrate operation, never an image tag update ([ADR 0009](adr/0009-toolchain-baseline.md)).

Provide database credentials only to `postgres` and the canonical backend through reviewed secret-file handling. Runtime authentication is available only to `codex`. Local Compose secret files are not a promise of encrypted host storage. Docker Desktop presents secret files from the Windows drive as mode `0777`, owned by root, inside containers, so confidentiality rests on host file permissions and per-service secret mounts. Linux hosts keep host ownership, so each secret must be readable by its consuming service's UID. Run services without privileged mode, without a Docker-daemon socket mount, and with minimum writable paths and capabilities. Test effective filesystem/network/tool restrictions; containerization alone does not prove them.

On normal shutdown, stop admitting commands, reconcile or cancel in-flight work, flush receipts/outbox state, and close connections before service termination. Compose stop grace periods must permit that sequence. Restart and image recreation must recover active combat, pending operations, and campaign identity from canonical state. Pack/ruleset and database migrations remain explicit compatibility operations, not side effects of every launch.

## 8. Delivery and verification

P00.3 pinned the container, host, and shell baseline ([ADR 0009](adr/0009-toolchain-baseline.md)), verified with a throwaway Compose probe rather than the launcher. P01 proves isolated runtime transport and account readiness. P02 delivers images, Compose, configuration, and the launcher. P14 proves the packaged playable loop. P16 and P19 validate recovery and fresh-clone release behavior.

The implementation must verify clean first start without host build toolchains; a second idempotent launch; invocation from another directory and paths with spaces; daemon unavailable; missing/incompatible Compose; busy port; first-build failure; missing/expired account access; runtime unavailable with saved-data inspection; interrupted startup; graceful stop; restart and image recreation with unchanged campaign/party/inventory/combat; migration failure without reset; independent restore; and absence of secrets, private saves, or development instructions in images and ordinary logs.

Record the supported host/shell/engine/runtime matrix and measured cold/warm startup behavior. Do not claim container, shell, authentication, or recovery checks passed before an executable implementation exists. See FR-029, QA-28, and the linked roadmap tasks.

## 9. Implementation references

- [Compose startup and health dependencies](https://docs.docker.com/compose/how-tos/startup-order/)
- [Compose startup command and wait behavior](https://docs.docker.com/reference/cli/docker/compose/up/)
- [Compose networks](https://docs.docker.com/compose/how-tos/networking/)
- [Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/)
