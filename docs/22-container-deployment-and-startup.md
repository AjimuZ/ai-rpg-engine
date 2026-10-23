# Container Deployment and Single-Command Startup

## 1. Status and owner requirement

**Design specification with partial infrastructure implementation.** The owner requires a Dockerized application launched through a root `start.sh` file. Neutral host development shells, a runtime probe and root database/runtime Compose services exist. An opt-in backend build now embeds the compiled interface and notices in one executable jar, with real HTTP verification. The packaged engine Dockerfile/service and launcher remain pending. The requirement applies to the engine and every adventure, without campaign-specific startup scripts.

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

**P01.1 evidence (23 October 2026, runtime 0.164.0, `linux/amd64`):** the pinned image ([`docker/codex-runtime/Dockerfile`](../docker/codex-runtime/Dockerfile)) and a Java 21 client completed initialization, the account-state read, and catalog pagination over this socket. Neither container had a network attached ([`tools/runtime_probe/compose.yaml`](../tools/runtime_probe/compose.yaml)). These findings bind P02.3 and P08.1:

- **Framing:** `--listen unix://PATH` serves WebSocket (RFC 6455) over the socket: an HTTP/1.1 upgrade, then one JSON-RPC message per text message. `java.net.http` has no Unix-socket transport, so the adapter needs its own bounded WebSocket client on `UnixDomainSocketAddress`. The probe's `UnixRelay.java` is the reference for the handshake and framing checks.
- **Socket location:** the runtime binds the real socket, with mode `0600`, at `/tmp/codex-daemon-<uid>/<sha256(PATH)>`. That file sits in a `0700` directory next to a lock file, and `PATH` itself holds only a symlink to it.
  - The IPC volume is therefore mounted at that daemon directory in `codex`, with `PATH`'s directory on a private tmpfs.
  - The engine connects to the hashed file name.
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
Production admission belongs to P08; any increase requires P08.6 evidence.

## 4. Planned repository artifacts

| Artifact | Purpose |
| --- | --- |
| Root `start.sh` | Stable, executable entry point for ordinary local operation |
| Root `compose.yaml` | Named services, networks, volumes, health checks, secrets, and lifecycle settings |
| `docker/` | Reviewed engine/runtime image definitions and build support |
| Root `.dockerignore` | Exclude credentials, local assistant settings, private data, Git history, and irrelevant build context |
| Root `.env.example` | Sanitized public configuration example without working credentials |
| Version/compatibility records | Pin build/runtime images, dependency locks, runtime protocol, Compose features, and supported host environment; the pre-scaffold baseline is in [ADR 0009](adr/0009-toolchain-baseline.md) |

The tracked root [`.env.example`](../.env.example) contains a proposed port and neutral database labels for future local configuration; its database labels configure the infrastructure services, but it does not make the application runnable. It contains no database password or runtime-account setting. The runtime image and compatibility records already exist for the developer probe. P02.3 adds the root [`.dockerignore`](../.dockerignore) and the independent [runtime context policy](../docker/codex-runtime/.dockerignore); the engine image/service, packaged interface, and launcher remain implementation deliverables. Keep actual `.env`, secret files, account state, and private data untracked. Build context must exclude local `.agents`, `.claude`, `.codex`, `AGENTS.md`, and `CLAUDE.md`; gameplay containers do not inherit development instructions or hooks.

The context policies default-deny inputs: the root admits only Angular/Java build inputs and the future engine Dockerfile, while the runtime context admits only its Dockerfile. Final recursive exclusions protect private names even inside admitted source trees. New build inputs require policy and regression-test updates. The policies do not inspect arbitrary source contents for secrets. Run `py -B -m unittest discover -s tools/container_packaging -v` with the ADR 0009 Docker baseline; it exports neutral scratch-stage fixtures through real BuildKit without sending checkout contents or starting services. See the [test guide](../tools/container_packaging/README.md).

**P02.3 development image revision (23 October 2026):** the official Docker Hub tag API still returns zero tags matching `21.0.13`; the exact existing JDK/JRE tags resolve to their recorded ADR 0009 indexes. The owner-requested unblocking revision permits local development builds using those pins with every isolation and integration check retained. Refresh both official images when available; the newest published Java update remains mandatory before release. This revision itself does not build an engine image or claim packaged operation.

### Single-origin application artifact (partial P02.3)

The [backend `packaged-ui` build](../backend/README.md#single-origin-jar-assembly-partial-p023)
now copies Angular production assets and its extracted third-party notices into
Spring's static classpath resources. Use a clean build after compiling Angular;
missing index/notices fail before packaging. Three real HTTP integration tests
verify index/bundle/notices responses and preserve 404s for missing APIs/assets.
No frontend server, CORS exception or catch-all route is introduced. Two ordinary
backend tests also pass; they remain frontend-independent without this profile.

This verifies jar assembly using the existing development JDK image, not the
first engine image. ADR 0009 permits local engine Dockerfile/service work on
the existing verified Java pins. The future multi-stage build must compile the frontend
first, invoke this profile, and carry its artifacts and notices into the engine
image without adding source/private files. The separate Spring runtime profile
`packaged` now requires `configtree:/run/secrets/` and maps the shared
`postgres_password` file to the datasource password. A private absolute directory
ending in `/` can override the tree through `engine.secrets-directory` for tests.
Missing mounts/password properties fail startup, with no fallback password;
ordinary explicit Spring property overrides retain their normal precedence.
The profile binds inside the container on `0.0.0.0`; the future engine service
must publish only on host loopback. Do not use it for host development.
Engine Compose secret mounts and durable content/asset mounts remain unfinished.
Four neutral configuration tests pass, including unchanged host defaults. An
absent mount fails import; an empty mount fails actual database authentication
during startup, rather than individual-file validation by the config-tree loader.
No full packaged readiness is claimed.

All nine packaged backend tests pass against disposable PostgreSQL 18. A
separate executable-jar smoke as UID/GID 10001 verifies secret-authenticated
startup, minimal health, exact index/notices bytes and missing directory/file
startup failures. It uses the existing pinned development JDK without building
an engine image, touching account state or claiming Linux-host file permissions.

## 5. Launcher contract

**Infrastructure checkpoint (P02.3):** root [`compose.yaml`](../compose.yaml)
currently defines only `postgres` and `codex`. It publishes no ports. PostgreSQL
joins internal `data`; the runtime joins separate `inference` for required egress.
Named `database`, `runtime_account`, and `runtime_workspace` volumes persist;
`runtime_ipc` is tmpfs-backed, UID/GID 10001, mode 0700. The runtime has a read-only
root filesystem, no capabilities, no account bind from the host, and no database
secret. PostgreSQL's official entrypoint initializes volume ownership as root
before dropping to its service identity. Health checks prove process/socket
availability only, not sign-in, schema compatibility or gameplay readiness.

The database reads `/run/secrets/postgres_password` from the private host file
`${POSTGRES_PASSWORD_FILE:-./.local/secrets/postgres-password}`. This variable
contains a path, never a password. Local Compose file secrets use read-only bind
mounts, not encrypted storage; restrict the host file to its owner. Missing files
must fail startup. P02.6 still owns secure initialization. `POSTGRES_DB` and
`POSTGRES_USER` now configure the database; `APP_PORT` has no consumer yet. Never
change a database password by replacing the file alone on an existing cluster:
PostgreSQL uses it only on initial cluster creation. Preserve volumes on stop
and recreation; never use `down -v`. See the [packaging checks](../tools/container_packaging/README.md).

The following commands describe the future application interface and cannot be run successfully against this repository, which currently provides development shells, compatibility fixtures and database/runtime infrastructure:

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

The first validation target is Windows 11 x64 with Docker Desktop's WSL 2 backend and Linux containers, running `start.sh` from Git Bash. That combination was verified with Docker Desktop 4.96.3, Engine 29.9.2, Compose v5.6.1, and Bash 5.3 for the pinned images and Compose features; the launcher itself is verified in P02.7. Bash inside a WSL distribution is an unverified candidate. Native PowerShell and `cmd.exe` do not run the shell script. Linux and macOS support must be claimed only after their launcher and runtime checks pass; macOS's default Bash 3.2 is below the floor.

Codex authentication is completed by the owner using supported mechanisms inside the dedicated runtime environment. Persist its state in a private runtime-only volume; never bake credentials into an image or mount the owner's entire home/development configuration. The planned `--login` command must document the supported interactive or device flow for the pinned version and handle refresh/expiry. See [official authentication guidance](https://learn.chatgpt.com/docs/auth) and [the integration specification](06-codex-integration.md).

The launcher does not guarantee that a desktop/editor login transfers into Linux containers. Account entitlement, service availability, rate limits, and billing remain applicable. Ordinary CI checks use fixtures and do not require account credentials.

**Required for the verified ChatGPT device-code flow:** enable device-code login in the account's security settings before `--login`; for a managed workspace, an administrator must allow that login method. The owner then completes the browser link/code displayed by the runtime. P02.6 must explain this setting when device authorization is disabled or denied, linking the official instructions without changing account settings itself. P02.7 must verify that this condition produces actionable guidance rather than repeated silent retries. The [current probe setup](../tools/runtime_probe/README.md#owner-account-setup-for-the-live-check) documents the manual command used successfully in P01.2. Other upstream sign-in methods have separate requirements; this is the prerequisite for the verified device-code path.

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

Record the supported host/shell/engine/runtime matrix and measured cold/warm startup behavior. Do not claim container, shell, authentication, or recovery checks passed before an executable implementation exists. See FR-029, QA-28, and the linked roadmap tasks.

## 9. Implementation references

- [Compose startup and health dependencies](https://docs.docker.com/compose/how-tos/startup-order/)
- [Compose startup command and wait behavior](https://docs.docker.com/reference/cli/docker/compose/up/)
- [Compose networks](https://docs.docker.com/compose/how-tos/networking/)
- [Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/)
