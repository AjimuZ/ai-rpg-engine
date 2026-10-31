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

Three context checks copy only the ignore policies into neutral fixture trees
under `.local/scratch/`, then export actual BuildKit contexts and compare exact
file sets. The root policy admits frontend/backend inputs, the engine
Dockerfile and one probe file, `tools/runtime_probe/fixtures/0.164.0/neutral-session.json`,
which the backend's drift check needs. The independent runtime policy admits its Dockerfile and five
reviewed managed-profile files, and the gateway's policy admits its Dockerfile and one source file. All exclude nested private paths. New inputs
must update the relevant policy and fixtures. These checks do not inspect account
files or detect secrets embedded in otherwise admitted source files.
`test_compose.py` checks rendered service boundaries without starting services,
including the optional `models` and `gateway` services under their profiles. It must pass on Compose 2.x
and 5.x (`verify.sh probe` runs it on 2.40.3): 2.x omits `bind.create_host_path` from
`config --format json` when false, so a bind's value is read from the rendered form
when present and otherwise from `compose.yaml`, which must set `create_host_path: false`
explicitly. A missing setting never passes.
`test_model_server.py` (P21.2-A) starts the pinned model server in a uniquely named
project with an empty temporary `MODELS_DIR`, and removes it afterwards. It belongs to
the default suite, needs no flag and never pulls: it runs wherever the image is on the
host (`./start.sh --models profile --gpu-check` fetches it). It checks router mode
through `/props`, the absence of a default route with a bridge-network positive
control, an immediate refusal of an outbound connection, read-only mounts and, where
`nvidia-smi` exists, a CUDA device through the rendered GPU request. Without
`nvidia-smi` it drops the GPU request through an override and reports the device case
as skipped. It loads no model. Its route-parser cases need no Docker and always run.
Without the image it follows the table under [Hosts without Docker](#hosts-without-docker).
`model_probe.py` (P21.2-C) is the readiness and narration probe harness: `py -B
tools/container_packaging/model_probe.py --base-url URL --model ID [--probes FILE]`. It calls `GET /health`, then
sends each probe of `probes/narration-probes.json` (four tasks, each in English and Italian) to
`/v1/chat/completions` with a strict `json_schema` response format, checks the reply against the probe's schema with
its own checker for a keyword subset (an unsupported keyword raises instead of passing), and prints one `PASS` or `FAIL`
line per probe with its latency. It exits 0 only when every probe passed, 1 otherwise (including a server that is
not ready, which yields one `readiness` failure and sends no probe) and 2 for an unreadable probe set. A pass means
the reply parsed and matched its schema; it never certifies narration quality. It sends no API key, so a server
started with `--api-key-file` is not yet reachable by it.
`probes/measurement-sample.json` fixes the measurement record format `rpg-engine.model-measurement/1` (model id and hash,
date, host, the seven metrics load time, prefill and decode speed, peak VRAM and RAM, maximum context and VRAM released
by unload, and the probe results). `kind: format-sample` carries only null metrics and is not a measurement;
`kind: host-measurement` needs a date, a host, the model's SHA-256 and a non-negative number in every metric.
`measurement_problems` validates both. No measurement exists yet.
`test_model_probe.py` runs all of this against a scripted loopback HTTP server that replays canned replies. The fixture
is not a model: its passes show that the probes tell conforming replies from a schema violation, a malformed reply, an
HTTP error, a server that is not ready and an unreachable server, and nothing about any real model. It needs no Docker,
so hosted CI runs it, and its name is matched by the `test_[!h]*.py` pattern of `verify.sh containers`.
`test_gateway.py` (P21.3-C.1) runs `docker/gateway/gateway.py` in process against a scripted loopback upstream, which is
a fixture and not a provider. It checks the allowlist file's refusals, key files, cookies, request and response bounds,
time limits and redirects, and every case asserts that a random sentinel key is absent from the log and from what the
caller received. It needs no Docker, so hosted CI runs it. `test_gateway_container.py` starts the service as Compose
renders it, in a uniquely named project that it removes, beside a scripted upstream on the egress network and a prober
on the private network. It checks the confinement, that only the private network is served, the routes, the rules in
the real networks and TLS (a trusted certificate passes; another authority or a wrong name gets no request). It also
scans logs, container metadata, the process environment and every layer of an image built from a context that
deliberately holds the sentinel. The images are small, so hosted CI runs it too, on its own Compose 2.x. Under
`PACKAGING_DOCKER=1`, `test_infrastructure.py` also selects the gateway beside the real stack: the real engine container
reaches it, the database and runtime cannot resolve it, and a sentinel key mounted in it is absent from a database dump
and from every service's logs. No code path can carry a key to the database today, so that dump guards a future path.
`LauncherStopTest` in `test_gateway_container.py` runs the real `./start.sh --stop` from a temporary copy against a running
gateway and requires its container and both networks gone. Below the launcher's Docker floors, as on hosted CI, it
asserts the launcher's refusal instead.
`test_launcher.py` checks the root `start.sh` contract (static rules, redaction,
refusal paths, read-only `--check` and, with a recording stand-in for Docker, that `--stop` selects every long-running
profile declared in `compose.yaml`) without starting or stopping services.
`test_model_fetch.py` (P21.2-B) drives `docker/model-fetch/model_fetch.py` through its
`main` with a fixture catalog and a recording opener. It needs no Docker and opens no
socket. It checks catalog refusals, the license text, size and SHA-256 mismatches, atomic
installation, existing files, staging cleanup and HTTPS-only redirects. Nothing is skipped
on Windows: the file-mode request and the handling of a delivered SIGTERM run in process.
`test_model_fetch_launcher.py` (P21.2-B) runs `./start.sh --models fetch` in a temporary
copy whose catalog is a fixture at the real path, with URLs under the reserved `.invalid`
domain, so no case can download a model. `LauncherGateTest` replaces `docker` with a
recording function and needs no Docker: it proves the confirmation gate, and that the
container with egress never starts without it. A function stands in for the terminal.
`RealLauncherTest` runs the launcher's real Compose calls in the pinned Python image, which
it may pull because the image is small, so hosted CI runs it, but the real launcher enforces
Engine 29.0 and Compose 5.0 and hosted CI may report less. The test file reads the host's
versions once at import: at or above the floors each `RealLauncherTest` case runs the fetch;
below them each case asserts instead that the real launcher refuses, prints no license and
creates no model directory, and returns. No case is skipped, but below the floors the fetch is
unproven on that host. Unreadable versions or an unreachable daemon count as no shortfall, so
those cases run and fail. `test_compose.py` reads the `/models` bind of both the model server
and `models-fetch` through one helper that tolerates Compose 2.x omitting `create_host_path`.
`ComposeServiceTest`
calls `docker compose run` itself, never reaches the floors and needs only the Docker CLI. A
wrapper at the helper's path in the copy supplies the fixture opener, and one case keeps the
unmodified helper, whose real transport fails on the unresolvable name. The effect of the file
mode and a real `docker stop` are also read here, in the Linux container, whatever the host is.
`test_hardware_profile.py` (P21.1) runs `--models profile [--gpu-check]` with every
host tool replaced by recorded output (driver tool, `/proc`, PowerShell, `df`,
Docker), so it needs no GPU, daemon or Windows host. It checks the profile format,
the container options of the GPU check, failure recording and the absence of
identifiers, and keeps the six backend hardware-profile fixtures byte-identical
to the launcher's output (`HARDWARE_PROFILE_UPDATE=1` rewrites them after an
intended change).
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

The running model-server cases also depend on the pinned image, which is about 2.6 GB
compressed and never pulled by a test:

| Host with Docker | Running model-server cases |
| --- | --- |
| image present | run; any Docker failure or isolation breach fails |
| image absent, `PACKAGING_DOCKER=1` (as in `verify.sh containers`) | fail with the fetch command |
| image absent, flag not set (hosted CI, a fresh host) | skipped, with a reason saying nothing was proven about the running service |
| `docker image inspect` fails for any other reason | fail |

So hosted CI proves only the rendered boundaries in `test_compose.py` and the route
parser. The running isolation proof comes from a host that holds the image.

## Real backup and restore of two genres

`test_restore_isolation.py` belongs to the default suite and follows the [Docker policy above](#hosts-without-docker): it
needs no opt-in flag, hosted CI's packaging job runs it, and a host with no `docker` executable reports it as not run.
Because it starts containers, it is the slowest default case. `verify.sh containers` runs it, while `verify.sh full` does
not include this suite. It builds the same `verification` image, starts the composed PostgreSQL pin on tmpfs and an
internal network, runs the backend's `AdventureRestoreCheck` against a source database, runs the shipped
`tools/database_backup/backup.py`, compares every table row by row, and runs the same check in a new JVM against the
restored database with an empty managed-folder root. Unlike the recreation check below, it never reuses names and leaves
no volume or password file. The [backup tool notes](../database_backup/README.md#live-two-genre-rehearsal-p065-b1-v26)
record what it covers.

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
containers, including stopped ones that a killed run could not clean up: inspect
their Compose labels and remove them by name before rerunning. A killed run can also
leave the retained fixture database interrupted. Its recovery can outlast the health
wait, and then `compose up --wait` fails on every run until the recovery has finished
once with the service left running, or the fixture volume is replaced.

The check builds the pinned images, starts all three services with a random
loopback port, and checks actual identity, networks, mounts, socket permissions,
authenticated database TCP and rejection of passwordless access. HTTP checks
cover the root selector, both `/en/` and `/it/` preview indexes, hashed JS/CSS,
locale/shared notices and artwork, minimal health and unavailable
sensitive/configuration/API routes before and after recreation. Resource SHA-256
maps must match across recreation; the backend HTTP tests also compare bytes to
packaged classpath resources. A WebSocket
upgrade through the read-only shared socket proves engine-to-runtime IPC without
an account or inference request. A missing packaged secret must fail startup.

The engine Dockerfile's `verification` target retains source, the probe fixture and an
offline Maven cache solely for checks. The full backend suite runs in that target against a
separate fixture database on the internal data network, and the check fails on any failure,
error or skipped test: no skip is declared or allowed. The final JRE image contains only
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
