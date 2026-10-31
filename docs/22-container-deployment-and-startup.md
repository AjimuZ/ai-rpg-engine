# Container Deployment and Single-Command Startup

## 1. Status and owner requirement

**Application shell packaging and root launcher implemented; gameplay pending.** P02.3
provides the pinned engine/runtime images and root Compose services, serving the
neutral Angular shell and health from one loopback origin. P05.6 extends that
artifact with compiled `/en/` and `/it/` variants, root device language selection,
finite preview GET routes and shared root artwork/notices. Missing bundles and
API routes remain errors, and no command selects a locale. Account-free build,
secret, IPC and storage recreation checks pass. P02.6 adds the root `start.sh`
entry point (see the launcher contract in section 5). Its Windows/Git Bash idempotence,
interruption, graceful-stop and restart-preservation verification is recorded in P02.7 (section 8);
Linux/macOS hosts, interactive Ctrl+C and owner sign-in remain unverified, and gameplay readiness remains P14.

The packaged local installation must run the application, database, and isolated Codex runtime through Docker Compose. Users must not need host Java, Maven, Node, or npm to build and launch that packaged profile. Codex service connectivity and supported account access are still required. Host-based development and stdio protocol probes remain optional developer workflows, not the completed packaged installation.

This supersedes the earlier database-only Compose plan. The decision and compatibility implications are recorded in [ADR 0008](adr/0008-containerized-startup.md).

## 2. Runtime topology

| Compose service | Responsibility | Access and persistence |
| --- | --- | --- |
| `engine` | Spring Boot API, deterministic rules, background jobs, and compiled Angular assets at one browser origin | Canonical database connection; registered content/assets; private runtime IPC |
| `postgres` | Canonical campaign state, receipts, events, checkpoints, and migrations | Private data network; dedicated durable database volume |
| `codex` | Pinned app-server runtime and isolated role/campaign workspaces | Own account/state volume and inference egress; no database credentials, data volume, or source checkout |

The engine remains one modular Java application. A dedicated runtime container is an isolation and packaging boundary, not a split into gameplay microservices or a generic provider service. A multi-stage engine image builds Angular and the Java application with pinned toolchains, then serves the compiled interface from the Java runtime. A separate frontend development container may be offered later for hot reload; it is not required for normal play.

Only the engine's web entry point is published, bound to loopback. Its default is `http://127.0.0.1:8080`, configurable with `APP_PORT`. PostgreSQL and runtime-control endpoints are not published to the host. Keep the database network separate from the runtime's inference network.

## 3. Runtime integration gate

The packaged design targets a private Unix-domain socket shared between `engine` and `codex` through a Docker-managed Linux IPC volume. The volume contains only the control socket; database data and credentials are never placed there. The backend adapter must support the selected transport, permissions, framing, reconnect, and request correlation. Compose owns the runtime container; the backend owns its connections, threads, and turns. Renewing a thread does not recreate the container or reset a campaign.

**Selectable runtime (ADR 0020, planned in P20.2).** `NARRATION_RUNTIME=codex|claude` in the private `.env` selects the runtime. Unset means `codex`, and any other value is refused. Compose defines one service per runtime and starts only the selected one. `./start.sh --runtime <name>` changes the setting and restarts, `--login` signs in to the selected runtime, and `--check` and `--status` report the selected runtime and its readiness. The `claude` service follows the same rules as `codex`: read-only root, dropped capabilities, private account volume and IPC, no database route, no credentials in images or `.env`, and no Docker socket ([Claude runtime integration](33-claude-runtime-integration.md)). Only `codex` exists today. [ADR 0021](adr/0021-inference-backends-and-model-selection.md) plans three more services under the same rules: `models` (local text server), `images` (local image worker) and `gateway` (hosted APIs and custom endpoints, the only service holding keys). `./start.sh --models` profiles the hardware (`profile`, implemented in P21.1), and will advise, fetch and verify model files; it never changes WSL, Docker or driver settings ([inference backends](34-inference-backends-and-model-selection.md)).

Official [app-server documentation](https://learn.chatgpt.com/docs/app-server) describes Unix-socket and stdio connections and identifies TCP WebSocket transport as experimental and unsupported. P01 must validate the exact pinned Linux runtime and Java client before accepting this transport. No production-support claim is inferred from the availability of a command option. A different transport requires an explicit decision update, authentication/isolation review, and new contract evidence; the launcher must not silently expose a TCP listener or mount the Docker socket into the backend to run `docker exec`.

**P01.1 evidence (23 October 2026, runtime 0.164.0, `linux/amd64`):** the pinned image ([`docker/codex-runtime/Dockerfile`](../docker/codex-runtime/Dockerfile)) and a Java 21 client completed initialization, the account-state read, and catalog pagination over this socket. Neither container had a network attached ([`tools/runtime_probe/compose.yaml`](../tools/runtime_probe/compose.yaml)). These findings bind P02.3 and P08.1:

- **Framing:** `--listen unix://PATH` serves WebSocket (RFC 6455) over the socket: an HTTP/1.1 upgrade, then one JSON-RPC message per text message. `java.net.http` has no Unix-socket transport, so the adapter needs its own bounded WebSocket client on `UnixDomainSocketAddress`. The probe's `UnixRelay.java` is the reference for the handshake and framing checks.
- **Socket location:** the runtime binds the real socket, with mode `0600`, at `/tmp/codex-daemon-<uid>/<sha256(PATH)>`. That file sits in a `0700` directory next to a lock file, and `PATH` itself holds only a symlink to it.
  - The IPC volume is therefore mounted at that daemon directory in `codex`, with `PATH`'s directory on a private tmpfs.
  - The engine connects to the hashed file name. P08.1 implements this client as
    `UnixSocketFrameWire` in the backend, behind the runtime-neutral task port
    ([docs/06](06-codex-integration.md#p081-production-adapter)).
- **UID boundary:** the runtime and the engine must share one UID, 10001 in the probe. Relays running as UID 10002 and as root without capabilities were refused.
- **Volume:** a tmpfs-backed IPC volume owned by UID 10001 keeps nothing at rest. `compose down` without `-v` leaves the named volume in place, and the launcher must not delete volumes.
- **Not yet proven:** sign-in, account readiness, and authenticated runtime restart. P02.3 separately verifies account-free infrastructure recreation and neutral volume preservation below.

**P01.2 fixture evidence (same pin and platform):** [`session.compose.yaml`](../tools/runtime_probe/session.compose.yaml) adds a scripted upstream solely for deterministic testing, on an internal network with no published ports; the relay still has no network and the runtime receives no account. Seven turns across two threads verify explicit upstream model/effort/schema, correlated terminal statuses and active-stream interruption. A second relay connection resumes both threads and checks their ordered histories and sticky settings while the same runtime stays alive. This proves connection resume, not runtime restart or persistent sign-in. Production remains Codex-only; the scripted upstream is never a deployment alternative. See the [probe instructions](../tools/runtime_probe/README.md) for commands and the successful separately retained owner-signed-in neutral stdio smoke. That check verifies output and resume across two stdio processes, not packaged socket restart or full application readiness.

**P01.3 isolation profile (same pin and platform):** the runtime image installs a root-owned managed configuration, requirements, engine model catalog and neutral instructions in `/etc/codex`, and starts through a guard ([ADR 0013](adr/0013-narration-runtime-isolation.md)). The model receives no tools. Approval is pinned to `never`, sandbox to `read-only` and search to `disabled`, and inherited extension servers are removed. The guard exits with code 78 if the runtime home contains `config.toml`, `AGENTS.md` or `AGENTS.override.md`. The runtime-account volume must therefore hold sign-in state only. The future launcher must report such a refusal and never delete or edit files in the volume. The packaging recreation check passes with the guarded image.

Stdio remains the isolated local protocol-probe option. If the packaged runtime cannot satisfy authentication, isolation, or connection requirements, containerized delivery remains incomplete. It is not replaced with a different model provider or silently presented as a fully Dockerized host-runtime setup.

P01.5's [compatibility matrix](../tools/runtime_probe/COMPATIBILITY.md) records
this exact image/client/host scope and retained neutral fixtures. Other hosts and
architectures, authenticated packaged restart and full startup remain unverified;
the matrix does not complete the integration gate.

P01.6 [records images as unavailable](../tools/runtime_probe/IMAGE_CAPABILITY.md)
in the managed runtime, with no generation formats or invocation. Image account
entitlement/cost is unverified and is not a text-readiness prerequisite. These
checks use no account volume; no image service, API credential, wider tool profile
or launcher sign-in is introduced. Future enablement remains separately gated.

P01.7 [qualifies managed-profile scripted dispatch/cancellation](../tools/runtime_probe/CONCURRENCY.md)
on the existing private IPC fixture, without account mounts or topology changes.
It explicitly selects one active inference turn per installation; additional
threads/campaigns/containers do not imply more qualified live capacity.
Live simultaneity/headroom and authenticated packaged restart remain unverified.
Production admission is the P08.6 fair scheduler; any increase requires authorized
live evidence, and the packaged backend refuses `max-active` above 1.

## 4. Planned repository artifacts

| Artifact | Purpose |
| --- | --- |
| Root `start.sh` | Stable, executable entry point for ordinary local operation |
| Root `compose.yaml` | Named services, networks, volumes, health checks, secrets, and lifecycle settings |
| `docker/` | Reviewed engine/runtime image definitions and build support |
| Root `.dockerignore` | Exclude credentials, local assistant settings, private data, Git history, and irrelevant build context |
| Root `.env.example` | Sanitized public configuration example without working credentials |
| Version/compatibility records | Pin build/runtime images, dependency locks, runtime protocol, Compose features, and supported host environment; the pre-scaffold baseline is in [ADR 0009](adr/0009-toolchain-baseline.md) |

The tracked root [`.env.example`](../.env.example) contains the consumed application port and neutral database labels. It contains no database password or runtime-account setting. P02.3 supplies the engine/runtime images, root Compose and packaged interface; P02.6 adds the launcher, which creates the ignored `.env` from this example on first start and the private database-password file. Keep actual `.env`, secret files, account state, and private data untracked. The root [`.dockerignore`](../.dockerignore) and independent [runtime context policy](../docker/codex-runtime/.dockerignore) exclude local `.agents`, `.claude`, `.codex`, `AGENTS.md`, and `CLAUDE.md`; gameplay containers do not inherit development instructions or hooks.

The context policies default-deny inputs: the root admits only Angular/Java build inputs and the engine Dockerfile, while the runtime context admits its reviewed Dockerfile/profile files. Final recursive exclusions protect private names even inside admitted source trees. New build inputs require policy and regression-test updates. The policies do not inspect arbitrary source contents for secrets. Build-context tests export neutral scratch-stage fixtures through real BuildKit without sending checkout contents or starting services; the opt-in integration test builds the actual stack. A host with no `docker` executable skips the BuildKit context and Compose render cases with an explicit reason. The CI packaging step (`PACKAGING_REQUIRE_DOCKER=1`) and any hosted GitHub Actions job run them anyway, so a missing tool still fails there. See the [test guide](../tools/container_packaging/README.md#hosts-without-docker).

**P02.3 development image revision (23 October 2026):** the official Docker Hub tag API still returns zero tags matching `21.0.13`; the exact existing JDK/JRE tags resolve to their recorded ADR 0009 indexes. The owner-requested unblocking revision permits local development builds using those pins with every isolation and integration check retained. Refresh both official images when available; the newest published Java update remains mandatory before release. This revision itself does not build an engine image or claim packaged operation.

### Single-origin application artifact (P02.3)

The [backend `packaged-ui` build](../backend/README.md#single-origin-jar-assembly-p023)
now copies Angular production assets and its extracted third-party notices into
Spring's static classpath resources. Use a clean build after compiling Angular;
missing index/notices fail before packaging. Three real HTTP integration tests
verify index/bundle/notices responses and preserve 404s for missing APIs/assets.
No frontend server, CORS exception or catch-all route is introduced. Two ordinary
backend tests also pass; they remain frontend-independent without this profile.

The [engine Dockerfile](../docker/engine/Dockerfile) builds Angular with pinned
Node/npm and the jar with pinned Java/Maven. It runs frontend typechecks/unit
tests and four account-free secret configuration tests during assembly. Its
JDK `verification` target retains Maven dependencies for the complete nine-test
suite offline on the fixture's internal database network. The final JRE stage
copies only the jar, with no Node, JDK, Maven, source or build caches.
The separate Spring runtime profile
`packaged` now requires `configtree:/run/secrets/` and maps the shared
`postgres_password` file to the datasource password. A private absolute directory
ending in `/` can override the tree through `engine.secrets-directory` for tests.
Missing mounts/password properties fail startup, with no fallback password;
ordinary explicit Spring property overrides retain their normal precedence.
The profile binds inside the container on `0.0.0.0`; Compose publishes only on
host loopback. Do not use it for host development. Engine Compose secret and
durable content/assets mounts are now verified on Docker Desktop Linux containers.
Four neutral configuration tests pass, including unchanged host defaults. An
absent mount fails import; an empty mount fails actual database authentication
during startup, rather than individual-file validation by the config-tree loader.
No full packaged readiness is claimed.

All nine packaged backend tests pass against disposable PostgreSQL 18. The actual
engine image/service runs as UID/GID 10001 with a read-only root, no capabilities
and no-new-privileges. Host HTTP verifies index, JS/CSS, notices, minimal health
and sensitive-path/API 404s before and after recreation. A WebSocket upgrade
from engine to the private socket verifies UID/mount accessibility without RPC
or account access. Missing secret-tree startup fails. No native Linux-host
file permission or account readiness claim is made.

## 5. Launcher contract

**Application shell (P02.3):** root [`compose.yaml`](../compose.yaml) defines
`engine`, `postgres` and `codex`. Engine alone publishes loopback HTTP and joins
`web` plus internal `data` and `models`; PostgreSQL joins only `data`; runtime joins only
`inference` for required egress. The engine has no runtime-account mount, and
the runtime has no engine/database storage, database secret or Docker socket.
The optional `models` service (P21.2-A) runs only under `--profile models` and joins
only internal `models`, which is empty otherwise. It mounts `${MODELS_DIR:-./.local/models}`
read-only and publishes nothing ([inference backends section 7](34-inference-backends-and-model-selection.md#7-local-text-server-profile)).
Named `database`, `content`, `assets`, `runtime_account`, and `runtime_workspace` volumes persist;
`runtime_ipc` is tmpfs-backed, UID/GID 10001, mode 0700. The runtime has a read-only
root filesystem, no capabilities, no account bind from the host, and no database
secret. PostgreSQL's official entrypoint initializes volume ownership as root
before dropping to its service identity. Health checks prove process/socket
availability only, not sign-in, schema compatibility or gameplay readiness.

The database reads `/run/secrets/postgres_password` from the private host file
`${POSTGRES_PASSWORD_FILE:-./.local/secrets/postgres-password}`. This variable
contains a path, never a password. Local Compose file secrets use read-only bind
mounts, not encrypted storage; restrict the host file to its owner. Missing files
must fail startup. The launcher creates the file on first start (see the launcher
behavior below). `POSTGRES_DB` and
`POSTGRES_USER` configure both services; `APP_PORT` selects engine host HTTP. Never
change a database password by replacing the file alone on an existing cluster:
PostgreSQL uses it only on initial cluster creation. Preserve volumes on stop
and recreation; never use `down -v`. See the [packaging checks](../tools/container_packaging/README.md).

The launcher (P02.6) provides the commands below. Manual shell build/start/stop
commands remain in the [packaging guide](../tools/container_packaging/README.md).

```text
./start.sh           Validate, build with cache, start services, and report readiness
./start.sh --check   Inspect prerequisites and configuration without changing campaign data
./start.sh --login   Run the supported owner-controlled runtime sign-in flow
./start.sh --status  Show service and gameplay readiness
./start.sh --logs    Follow bounded service logs with secret redaction
./start.sh --stop    Stop this installation while preserving persistent data
./start.sh --models profile [--gpu-check]  Write the hardware profile for the model advisor (P21.1)
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

**Launcher behavior (P02.6, `start.sh`):**

- **Exit codes:** 0 success, 1 failure, 2 usage error, and 3 only from `--status` when storage is ready but gameplay is not. `--check` changes nothing and exits 1 on any failed prerequisite.
- **Identity:** the first run writes a random 12-hex installation id to the ignored `.local/installation-id` and exports `COMPOSE_PROJECT_NAME=rpg-engine-<id>`. Later runs reuse it, so services and volumes are stable; a malformed id file is refused, never regenerated.
- **Configuration:** `.env` is copied from `.env.example` when absent and then parsed as `KEY=value` lines, never sourced, so its content cannot run code. Exported variables take precedence. An invalid `APP_PORT` or a busy port (the launcher's own running engine excepted) fails with the variable name and the way to change it.
- **Database password:** 64 hex characters from `/dev/urandom` written without a newline to `.local/secrets/postgres-password` (or `POSTGRES_PASSWORD_FILE`), created exclusively with umask 077. An existing non-empty file is never touched. An empty file, or a missing file while this installation's database volume exists, is refused with instructions, because a new password would not match the existing cluster. The value is never printed.
- **Start:** `docker compose build` reuses the layer cache; `up --detach --no-build --wait` follows, then the launcher polls `/actuator/health/readiness` by executing `curl` inside the engine container (the host needs no HTTP client). On failure it prints bounded, redacted service status and recent log lines and exits 1. It then reports `gameplay` readiness separately and prints the loopback URL and the status, log, stop and sign-in commands.
- **Sign-in:** `--login` stops the runtime service, runs `codex login --device-auth` in a one-off container created from the runtime service definition without its dependencies (so no database or engine is started; file credential store, ChatGPT method), then verifies the state and restores the runtime if it was running. Interruption removes the one-off container and restores the runtime. `--status` classifies sign-in from `codex login status` output only; credential files are never read, printed or copied. The launcher enables no paid overage or API-key fallback and does not change account settings. Device-code prerequisites are in section 6.
- **Logs and stop:** `--logs [SERVICE] [--no-follow]` prints the last 200 lines through a redactor (Bearer tokens, `key=value` credentials, signed tokens, `sk-` keys and the exact password value) and then follows. `--stop` runs `compose --profile models down --timeout 35` without `-v`, so every volume is kept and a selected model server stops too.
- **Hardware profile (P21.1):** `--models profile` writes `.local/hardware.json` from the NVIDIA driver tool, `/proc`, PowerShell's CIM classes (Windows only), `df` and `docker info`, and never fails because a probe is missing. It needs no Docker daemon and starts nothing. `--gpu-check` also runs the pinned llama.cpp CUDA image with `--list-devices`, all GPUs, no network, a read-only root, no capabilities and user 65534, removed afterwards, and pulls it by digest first if absent (about 2.6 GB). The profile holds hardware models, sizes and versions only. Format and advisor: [inference backends section 5](34-inference-backends-and-model-selection.md#5-catalog-and-hardware-advisor). It ran on the reference host on 31 October 2026 (P21.1-A) and saw the device; behavior under load is unverified.
- **Limits:** NTFS ignores the `chmod` modes on the password file, and native Linux ownership is not verified. Compose file secrets are bind mounts, not encrypted storage. The password file is a host-user-readable secret, not a vault.

**Health contract (P02.5):** status-only Actuator groups, each HTTP 200 when `UP` and 503 otherwise:
`liveness` (process only, never touches the database), `readiness` (storage readiness: `readinessState`,
database connection and Flyway schema validity with no pending migration) and `gameplay`
(narration runtime/account). The root `/actuator/health` aggregates storage health and lists
the group names; it exposes no components or details. `gameplay` currently reports `UNKNOWN`
mapped to 503 because no runtime adapter exists; `UNKNOWN` ranks lowest so it never lowers
the root or readiness status. Since P08.1 the component reads the runtime-neutral task
port's cached status without inference: it is `UP` only when the selected adapter is
connected, on the pinned version and signed in (`READY`), and `UNKNOWN` otherwise. The
packaged profile names the real socket (`engine.inference.codex.socket-path`) but keeps
`engine.inference.codex.transport` at its `disabled` default, so packaged gameplay stays
`UNKNOWN` until the narration consumer (P08.5) enables `unix` and a Docker-capable packaged
check verifies it. Neither has run yet. Startup failures (unreachable database, rejected credentials, schema/migration mismatch)
abort with a fixed message and action that never echo driver text or credentials; a missing
secret tree is reported by Boot's configuration-data analyzer. The container health check
still proves root availability only. Verified live: an unreachable database prints the
description; rejected credentials are covered by unit tests (the disposable server trusted
its own network namespace, so a live wrong-password run was not possible).

Missing runtime authentication must lead to the supported sign-in instructions and a clear incomplete-gameplay status. Saved-data inspection can remain available while narration is unavailable. A successful packaged-gameplay readiness result requires the runtime gate to pass; starting the web server alone is insufficient.

No startup, restart, log, or stop action may reset campaigns, regenerate existing secrets, prune unrelated resources, or delete persistent volumes. Partial startup and interruption must be recoverable by rerunning the launcher. A data reset, if introduced later, is a separate explicitly destructive owner operation, never an automatic repair.

## 6. Host and authentication prerequisites

The research revision adds an optional local speech worker under [ADR 0016](adr/0016-optional-local-speech.md).
It is off by default and is not part of mandatory text-only startup readiness.
Admit explicit model/voice revisions, rights, download sizes and hashes before
an owner installs the optional media profile. A missing GPU/worker/model must
produce usable text, not a failed game startup or silent cloud/API substitute.
The intended RTX 4070 12 GB / 32 GB Windows/WSL2 profile is an evaluation target,
not measured support. Its private service network and least-privilege mounts
exclude PostgreSQL, runtime accounts and the Docker socket. Keep the accepted
core packaged topology; do not add an agent framework or runtime pool for speech.

The host needs:

- Docker Engine 29.0 or later running Linux containers, the Docker Compose plugin 5.0 or later, and the Buildx plugin;
- GNU Bash 4.4 or later, with the limited host-command set in [ADR 0009](adr/0009-toolchain-baseline.md#shell-requirements);
- enough disk and memory (provisionally at least 10 GB of free Docker disk space);
- network access for first builds and inference.

The first validation target is Windows 11 x64 with Docker Desktop's WSL 2 backend and Linux containers, running `start.sh` from Git Bash. That combination was verified with Docker Desktop 4.96.3, Engine 29.9.2, Compose v5.6.1, and Bash 5.3 for the pinned images and Compose features; the P02.6 launcher was exercised on that same combination, and P02.7 verified idempotent launch, stop, interruption and restart/image-recreation preservation on it (see the P02.7 evidence below). Bash inside a WSL distribution is an unverified candidate. Native PowerShell and `cmd.exe` do not run the shell script. Linux and macOS support must be claimed only after their launcher and runtime checks pass; macOS's default Bash 3.2 is below the floor.

Codex authentication is completed by the owner using supported mechanisms inside the dedicated runtime environment. Persist its state in a private runtime-only volume; never bake credentials into an image or mount the owner's entire home/development configuration. The `--login` command runs the device-code flow for the pinned version in a one-off runtime container; it never reads or prints the stored credentials. Refresh and expiry handling beyond reporting the signed-in or signed-out state remains later work. See [official authentication guidance](https://learn.chatgpt.com/docs/auth) and [the integration specification](06-codex-integration.md).

The launcher does not guarantee that a desktop/editor login transfers into Linux containers. Account entitlement, service availability, rate limits, and billing remain applicable. Ordinary CI checks use fixtures and do not require account credentials.

**Required for the verified ChatGPT device-code flow:** enable device-code login in the account's security settings before `--login`; for a managed workspace, an administrator must allow that login method. The owner then completes the browser link/code displayed by the runtime. `./start.sh --login` and its help text explain this setting and link the official instructions without changing account settings. The `--help` text and the `--login` failure message name this setting and link the instructions, and a failed sign-in ends the command with a nonzero status instead of retrying. P02.7 confirmed the help text and read the failure path; a live rejected sign-in was not exercised. The [current probe setup](../tools/runtime_probe/README.md#owner-account-setup-for-the-live-check) documents the manual command used successfully in P01.2. Other upstream sign-in methods have separate requirements; this is the prerequisite for the verified device-code path.

## 7. Data, secrets, and lifecycle

Use distinct persistent storage for PostgreSQL, registered content/assets, runtime account/session state, and temporary workspaces. Canonical state belongs to PostgreSQL, not the container's writable layer or the runtime transcript. Backups must include the database, pinned content revisions, and referenced registered assets; a surviving Docker volume alone is not an independent backup.

Within managed content/asset storage, keep each adventure's specifications and campaign-specific generated scene/portrait/item folders separate as defined in [adventure workspaces](25-adventure-workspaces.md). Database mappings remain canonical; container restart retains those identities and original media without regeneration or cross-world folder reuse.

The pinned PostgreSQL 18 image keeps its cluster in `/var/lib/postgresql/18/docker`. Mount the database volume at `/var/lib/postgresql`; the `/var/lib/postgresql/data` path used by PostgreSQL 17 and earlier images would not persist data across container re-creation. Moving to a new PostgreSQL major version is an explicit backup-and-migrate operation, never an image tag update ([ADR 0009](adr/0009-toolchain-baseline.md)).

Provide database credentials only to `postgres` and the canonical backend through reviewed secret-file handling. Runtime authentication is available only to `codex`. Local Compose secret files are not a promise of encrypted host storage. Docker Desktop presents secret files from the Windows drive as mode `0777`, owned by root, inside containers, so confidentiality rests on host file permissions and per-service secret mounts. Linux hosts keep host ownership, so each secret must be readable by its consuming service's UID. Run services without privileged mode, without a Docker-daemon socket mount, and with minimum writable paths and capabilities. Test effective filesystem/network/tool restrictions; containerization alone does not prove them.

The runtime service uses `init: true`: runtime 0.164.0 running as PID 1 ignores SIGTERM, while the init-enabled service forwards it and exits promptly with code 0 under `docker stop`. This applies to root Compose and both probe fixtures. Local stdio runtime EOF exits cleanly, even during a turn; it does not establish terminal interruption. Disconnecting the packaged relay leaves an active turn running in the service. P01.4 therefore requires interrupt/terminal confirmation before normal connection closure, or fresh-connection resume/history reconciliation after a loss. Only developer probes may escalate against their uniquely named container; the backend never receives Docker access. See [lifecycle evidence](../tools/runtime_probe/README.md#process-lifecycle-p014).

On normal shutdown, stop admitting commands, reconcile or cancel in-flight work, flush receipts/outbox state, and close connections before service termination. Compose stop grace periods must permit that sequence. Restart and image recreation must recover active combat, pending operations, and campaign identity from canonical state. Pack/ruleset and database migrations remain explicit compatibility operations, not side effects of every launch.

## 8. Delivery and verification

P00.3 pinned the container, host, and shell baseline ([ADR 0009](adr/0009-toolchain-baseline.md)), verified with a throwaway Compose probe rather than the launcher. P01 proves isolated runtime transport and account readiness. P02 delivers images, Compose, configuration, and the launcher. P14 proves the packaged playable loop. P16 and P19 validate recovery and fresh-clone release behavior.

The implementation must verify clean first start without host build toolchains; a second idempotent launch; invocation from another directory and paths with spaces; daemon unavailable; missing/incompatible Compose; busy port; first-build failure; missing/expired account access; runtime unavailable with saved-data inspection; interrupted startup; graceful stop; restart and image recreation with unchanged campaign/party/inventory/combat; migration failure without reset; independent restore; and absence of secrets, private saves, or development instructions in images and ordinary logs.

Record the supported host/shell/engine/runtime matrix and measured cold/warm startup behavior. Do not claim container, shell, authentication, or recovery checks passed before an executable implementation exists.

**P02.6 evidence (24 October 2026):** on Windows 11 with Docker Desktop 4.96.3 (Engine 29.9.2, Compose v5.6.1) and Git Bash 5.3.15, the launcher was run against real Docker for first start, repeated start, status, bounded logs, stop with volumes kept, restart reusing the database and password, busy port, invalid port, unreachable daemon, a missing secret with an existing database volume, and `--login` up to the displayed device-code prompt. Sixteen launcher unit tests (`tools/container_packaging/test_launcher.py`) cover file format, the tracked executable bit, forbidden host tools, volume safety, `.env` parsing, redaction, identity and secret handling, and a read-only `--check` from a path with spaces. Not verified there: Linux or macOS hosts, a completed owner sign-in (human-only) and gameplay readiness (503 until P14); P02.7 closed the stop-timing, interruption and image-recreation gaps below.

**P02.7 evidence (24 October 2026):** the opt-in `tools/container_packaging/test_lifecycle.py` (`PACKAGING_LIFECYCLE=1`) drove the real launcher on the same Windows 11 / Docker Desktop 4.96.3 / Git Bash 5.3.15 combination from a disposable copy of the tracked files on port 18480, with a warm layer cache. A repeated `./start.sh` kept all container IDs and the password hash, and `--status` exited 3 with `storage: ready`. `--stop` took 3.5 s against a 40 s budget; Docker recorded exit 0 for the database and runtime-support service and 143 for the engine (the JVM shutdown hook after SIGTERM, not SIGKILL 137); no containers remained and the volume set was unchanged. After restart a neutral database row, a content-volume file and the password were intact. SIGINT during the image build and again during service startup produced exit 130 with the rerun message and no containers left, and the following run completed. A frontend change rebuilt the engine image (new image ID) with markers, password and volumes preserved. The sanitized [report](../tools/container_packaging/evidence/launcher-lifecycle-windows.json) holds timings, exit codes and booleans only.

Limits: the interrupt came from a Git Bash job-control shell, and Bash ran the trap after the in-flight Docker command finished, so a human Ctrl+C on an interactive terminal and prompt cancellation of a running build are unverified. The markers stand in for campaign data because no campaign schema exists before P03; QA-28's campaign and combat preservation must be rerun with real data. The engine does not yet drain in-flight commands (`server.shutdown=graceful` is not set; planned with P03/P16). A cold build, Linux/macOS, WSL Bash and native Linux secret ownership were not measured or verified. See FR-029, QA-28, and the linked roadmap tasks.

## 9. Implementation references

- [Compose startup and health dependencies](https://docs.docker.com/compose/how-tos/startup-order/)
- [Compose startup command and wait behavior](https://docs.docker.com/reference/cli/docker/compose/up/)
- [Compose networks](https://docs.docker.com/compose/how-tos/networking/)
- [Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/)
