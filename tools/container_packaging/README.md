# Container packaging checks

The root `compose.yaml` builds the packaged engine, PostgreSQL and the isolated
runtime. The engine serves the Angular shell, hashed assets, notices and health
at one loopback origin (default `http://127.0.0.1:8080`; `APP_PORT` overrides the
port). Gameplay workflows remain pending; the root `start.sh` launcher (P02.6) wraps this stack. The runtime uses
`init: true` for signal forwarding and prompt shutdown.

Run from the repository root with Python and the Linux Docker/BuildKit baseline
in [ADR 0009](../../docs/adr/0009-toolchain-baseline.md):

```powershell
py -B -m unittest discover -s tools/container_packaging -v
```

Two context checks copy only the ignore policies into neutral fixture trees
under `.local/scratch/`, then export actual BuildKit contexts and compare exact
file sets. The root policy admits frontend/backend inputs and the engine
Dockerfile. The independent runtime policy admits its Dockerfile and five
reviewed managed-profile files. Both exclude nested private paths. New inputs
must update the relevant policy and fixtures. These checks do not inspect account
files or detect secrets embedded in otherwise admitted source files.
`test_compose.py` checks rendered service boundaries without starting services.
`test_launcher.py` checks the root `start.sh` contract (static rules, redaction,
refusal paths and read-only `--check`) without starting or stopping services.
Command-surface cases inject strict Docker metadata through a test-only Bash
startup file: they run without a daemon and do not assume the runner meets the
launcher's Engine 29 / Compose 5 minimum. Unsupported Engine/Compose versions,
non-Linux containers, an unreachable daemon and missing Buildx are tested as
explicit failures. The path-with-spaces case checks file bytes recursively and
refuses creation of private state. This fixture does not establish real Docker
compatibility; actual BuildKit/Compose checks and opt-in lifecycle checks remain
separate. The launcher minimum versions and production checks are unchanged.
Missing Buildx or Compose, an unreachable daemon and build failures fail these
checks. Ordinary CI runs this default suite ([workflow](../../.github/workflows/ci.yml),
P02.4); the opt-in `PACKAGING_DOCKER=1` recreation check stays out of CI until native
Linux secret-mount ownership is qualified.

### Hosts without Docker

A host with no `docker` executable on `PATH`, such as the Linux toolchain container,
skips the BuildKit context and Compose render cases with an explicit reason. It does
not report a `FileNotFoundError` for each case. The shared policy is in
[`docker_requirement.py`](docker_requirement.py) and is tested by
`test_docker_requirement.py`:

| Host | BuildKit context and Compose render cases |
| --- | --- |
| `docker` on `PATH` | run; Buildx, Compose, daemon and build errors fail |
| no `docker`, `PACKAGING_REQUIRE_DOCKER=1` or `GITHUB_ACTIONS=true` | run and fail on the missing tool |
| no `docker`, neither set | skipped: "docker executable not found; hosted CI enforces this check" |

The CI packaging step sets `PACKAGING_REQUIRE_DOCKER=1`. Any hosted GitHub Actions job
also counts as required, so the skip never weakens CI. The generic `CI=true` does not
count, because local tools set it to disable prompts. A skip on a Docker-less host
proves nothing about build-context filtering or Compose boundaries. Report it as not
run there and rely on the hosted packaging job, or run the suite on a Docker host.
The launcher fixture cases and the static checks need no Docker and always run.

## Real packaged-shell recreation

```powershell
$env:PACKAGING_DOCKER='1'
py -B -m unittest discover -s tools/container_packaging -v
Remove-Item Env:PACKAGING_DOCKER
```

For host Chrome browser journeys against the actual packaged fixture, also set
`PACKAGING_BROWSER=1` before the same command (remove it afterward). This requires
the host localized build from `npm run build:locales` for exact resource comparisons
and an installed Chromium browser (`BROWSER_PATH` can select it). It uses a fresh
temporary profile, performs no action/API writes and closes the browser. Root
device-language/default/unsupported/denied-storage paths and direct locale links
preserve opaque query/hash and draft bytes. Ordinary CI leaves this flag unset.

The opt-in check uses only `engine-packaged-shell-check` and refuses existing
containers. It builds the pinned images, starts all three services with a random
loopback port, and checks actual identity, networks, mounts, socket permissions,
authenticated database TCP and rejection of passwordless access. HTTP checks
cover the root selector, both `/en/` and `/it/` preview indexes, hashed JS/CSS,
locale/shared notices and artwork, minimal health and unavailable
sensitive/configuration/API routes before and after recreation. Resource SHA-256
maps must match across recreation; the backend HTTP tests also compare bytes to
packaged classpath resources. A WebSocket
upgrade through the read-only shared socket proves engine-to-runtime IPC without
an account or inference request. A missing packaged secret must fail startup.

The engine Dockerfile's `verification` target retains source and an offline Maven
cache solely for checks. The full backend suite runs in that target against a separate
fixture database on the internal data network. The final JRE image contains only
the packaged jar, and builds also run frontend type checks, tests and compilation.
Neutral markers in the fixture database schema, runtime home/workspace and
content/assets storage must survive recreation. Cleanup removes owned containers,
networks and markers, never Docker volumes. No account or game data is used.

The check retains its named volumes and the paired generated password file
`.local/packaged-shell-check/postgres-password`. This ignored fixture secret is
never printed or passed as a command-line value. On Windows it inherits the
private checkout directory's ACL. The launcher's own secret (`.local/secrets/postgres-password`) is created by `start.sh`; NTFS ignores its chmod modes.
Native Linux bind-secret ownership still needs verification. Do not reuse these
fixtures for application data. Without the opt-in environment flag only this
integration check skips. The completed Windows Docker Desktop run passed all
five packaging checks and the separate 116 runtime checks. This evidence does
not establish sign-in, campaign recovery or full QA-28 acceptance.

## Real launcher lifecycle (P02.7)

`test_lifecycle.py` runs the actual root `start.sh` against Docker and is skipped
unless `PACKAGING_LIFECYCLE=1` is set and a daemon is reachable; ordinary CI does not
run it. It works in a disposable copy of the tracked files under
`.local/scratch/lifecycle check` (a path with a space, port 18480) and always stops the
services at the end. It never removes volumes, so reruns reuse the copy's installation
and warm build cache. It checks, in order: start and repeated start (same container IDs
and password hash, `--status` exit 3 with storage ready); neutral markers in the
database and content volume; `--stop` within 40 s with the die-event exit codes (0 for
the database and runtime-support service, 0 or 143 for the engine; 137 would be
SIGKILL) and an unchanged volume set; restart with markers and password intact; SIGINT
during the image build and during service startup (exit 130, message shown, nothing
left running); and a rebuilt engine image keeping the markers, password and volumes.

```text
PACKAGING_LIFECYCLE=1 PACKAGING_LIFECYCLE_REPORT=report.json \
  python -B -m unittest discover -s tools/container_packaging -p test_lifecycle.py
```

The report holds timings, exit codes and booleans only; the retained Windows run is
[`evidence/launcher-lifecycle-windows.json`](evidence/launcher-lifecycle-windows.json).
The signal comes from a Git Bash job-control shell and the trap runs after the in-flight
Docker command ends, so this does not prove a human Ctrl+C cancels a build. Markers stand
in for campaign data until P03; Linux, macOS, WSL and sign-in are not covered.

## Manual development shell

Create an owner-restricted password file at `.local/secrets/postgres-password`
without printing or tracking its value. `POSTGRES_PASSWORD_FILE` can select a
different private file. Run:

```text
docker compose --env-file .env.example up --detach --build --wait
docker compose --env-file .env.example ps
docker compose --env-file .env.example down --timeout 30
```

Open the loopback URL manually after health checks pass. A missing secret fails
startup. Database labels/passwords initialize a new cluster only; changing the
file does not rotate an existing cluster's password. Preserve volumes; never add
`-v` to the stop command. `start.sh` owns ordinary setup (see the
[launcher contract](../../docs/22-container-deployment-and-startup.md#5-launcher-contract) and
`test_launcher.py`, which needs no Docker for its static and function checks). ADR 0009
permits local builds on the verified existing Java pins; refreshing to the newest
published Java update remains a release requirement.
