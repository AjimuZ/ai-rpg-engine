# Container packaging checks

The root `compose.yaml` builds the packaged engine, PostgreSQL and the isolated
runtime. The engine serves the Angular shell, hashed assets, notices and health
at one loopback origin (default `http://127.0.0.1:8080`; `APP_PORT` overrides the
port). Gameplay and launcher workflows remain pending. The runtime uses
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
Missing Docker/BuildKit and build failures fail these checks; Ordinary CI runs this default suite ([workflow](../../.github/workflows/ci.yml), P02.4); the opt-in `PACKAGING_DOCKER=1` recreation check stays out of CI until native Linux secret-mount ownership is qualified.

## Real packaged-shell recreation

```powershell
$env:PACKAGING_DOCKER='1'
py -B -m unittest discover -s tools/container_packaging -v
Remove-Item Env:PACKAGING_DOCKER
```

The opt-in check uses only `engine-packaged-shell-check` and refuses existing
containers. It builds the pinned images, starts all three services with a random
loopback port, and checks actual identity, networks, mounts, socket permissions,
authenticated database TCP and rejection of passwordless access. HTTP checks
cover the entry point, hashed JS/CSS, notices, minimal health and unavailable
sensitive/configuration/API routes before and after recreation. A WebSocket
upgrade through the read-only shared socket proves engine-to-runtime IPC without
an account or inference request. A missing packaged secret must fail startup.

The engine Dockerfile's `verification` target retains source and an offline Maven
cache solely for checks. Nine backend tests run in that target against a separate
fixture database on the internal data network. The final JRE image contains only
the packaged jar, and builds also run frontend type checks, tests and compilation.
Neutral markers in the fixture database schema, runtime home/workspace and
content/assets storage must survive recreation. Cleanup removes owned containers,
networks and markers, never Docker volumes. No account or game data is used.

The check retains its named volumes and the paired generated password file
`.local/packaged-shell-check/postgres-password`. This ignored fixture secret is
never printed or passed as a command-line value. On Windows it inherits the
private checkout directory's ACL; launcher permission hardening belongs to P02.6.
Native Linux bind-secret ownership still needs verification. Do not reuse these
fixtures for application data. Without the opt-in environment flag only this
integration check skips. The completed Windows Docker Desktop run passed all
five packaging checks and the separate 116 runtime checks. This evidence does
not establish sign-in, campaign recovery or full QA-28 acceptance.

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
`-v` to the stop command. The future `start.sh` owns ordinary setup. ADR 0009
permits local builds on the verified existing Java pins; refreshing to the newest
published Java update remains a release requirement.
