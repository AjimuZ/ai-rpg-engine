# Container build-context checks

The root `compose.yaml` is an infrastructure checkpoint with database/runtime
services only. The runtime service uses `init: true` for signal forwarding and
prompt shutdown ([P01.4 lifecycle evidence](../runtime_probe/README.md#process-lifecycle-p014)).
`test_compose.py` renders it through real Compose and verifies
network, mount, identity, secret and pinned-image boundaries without starting
services. The engine service is still pending. Application-level single-origin jar
assembly is verified separately by the [backend tests](../../backend/README.md).

Run from the repository root with Python and the Linux Docker/BuildKit baseline
in [ADR 0009](../../docs/adr/0009-toolchain-baseline.md):

```powershell
py -B -m unittest discover -s tools/container_packaging -v
```

The two build-context tests copy only the ignore policies into isolated neutral fixture trees
under `.local/scratch/`, then use a pinned Dockerfile frontend and a scratch-stage
`COPY` to export the actual context. Exact exported-file comparisons verify both
required build inputs and exclusion of root/nested private paths. They never send
the checkout to Docker, inspect account files, start services, or create volumes.
Missing Docker/BuildKit or build failures fail the suite rather than skip checks.
Fixture directories are removed automatically; BuildKit cache may remain.

The root policy admits only frontend/backend build inputs and the future engine
Dockerfile. The independent runtime policy admits only its Dockerfile, since that
image downloads its checksum-verified executable. New build inputs must update
both policy and fixtures; root policy changes do not affect independent contexts.
These tests cover path filtering, not secret detection inside otherwise admitted
source files, nor packaged service isolation, runtime readiness or persistence.
CI wiring belongs to P02.4. The engine image/service and packaged interface remain
pending P02.3 work. ADR 0009 permits local builds on verified existing Java pins;
refreshing to the newest published Java update remains a release requirement.

## Real infrastructure recreation

Run the opt-in Docker test from PowerShell:

```powershell
$env:PACKAGING_DOCKER='1'
py -B -m unittest discover -s tools/container_packaging -v
Remove-Item Env:PACKAGING_DOCKER
```

It uses only the dedicated `engine-packaging-check` project and refuses to adopt
existing containers. It builds the pinned runtime, starts PostgreSQL and the
account-free runtime, checks rendered/actual network and mount isolation, socket
mode/UID, password-authenticated database TCP with passwordless access rejected, and neutral markers in database,
account and workspace storage. Both containers are recreated and the markers
must survive. Cleanup stops/removes owned containers and networks, never volumes.
No account is loaded, no inference request is made, and no game data is used.

The test retains its named database/account/workspace/IPC volumes and generated
password file `.local/packaging-check/postgres-password`. That ignored file must
stay paired with the initialized fixture database for repeat runs. It is never
printed or passed as a command-line value. On Windows its protection inherits the
private checkout directory's ACL; launcher permission hardening remains P02.6.
Do not use this fixture project or secret for real application data. The ordinary
suite skips only this explicit opt-in test; configuration and BuildKit tests fail
if their required Docker tools are unavailable. A passing fixture is partial
infrastructure evidence, not sign-in, schema/campaign recovery or QA-28 acceptance.

For manual infrastructure-only use, first create an owner-restricted password
file at `.local/secrets/postgres-password` without printing or tracking its value.
`POSTGRES_PASSWORD_FILE` can select a different private host file. Then run:

```text
docker compose --env-file .env.example up --detach --build --wait
docker compose --env-file .env.example ps
docker compose --env-file .env.example down --timeout 30
```

These commands start no browser interface. A missing secret must fail startup.
Database labels and passwords initialize a new cluster only: changing the file
does not rotate an existing cluster's password. Preserve volumes; never add `-v`
to the stop command. The future `start.sh` will own ordinary application setup.
