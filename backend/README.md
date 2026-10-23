# Backend development shell

Spring Boot 4.1.2 on Java 21, with Maven 3.10.0 through the checksum-verified
Maven Wrapper 3.3.4. Run commands **from `backend/`** so `.mvn/maven.config`
applies the Central-only mirror and strict checksums. Linux needs `unzip` for the
wrapper's checksum-pinned ZIP. On Windows use `mvnw.cmd` instead of `./mvnw`.
The enforcer rejects other JDK majors, Maven outside 3.10.x, snapshots, and dynamic
versions. Library and plugin versions come from the Boot parent/BOM.

Provide a disposable PostgreSQL 18 database for tests and a separate database for
manual development. Use the pinned `postgres:18.6-trixie` digest in
[ADR 0009](../docs/adr/0009-toolchain-baseline.md). Supply Spring's environment
variables without committing values:

- `SPRING_DATASOURCE_URL`: JDBC URL for the selected database.
- `SPRING_DATASOURCE_USERNAME`: database role.
- `SPRING_DATASOURCE_PASSWORD`: private password.

For a temporary host-development database, first set `POSTGRES_PASSWORD` privately
in your shell environment, then run this foreground command in another terminal:

```sh
docker run --rm --name engine-dev-db --env POSTGRES_DB=engine --env POSTGRES_USER=engine --env POSTGRES_PASSWORD --publish 127.0.0.1:5432:5432 --tmpfs /var/lib/postgresql postgres:18.6-trixie@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6
```

Set `SPRING_DATASOURCE_URL` to `jdbc:postgresql://127.0.0.1:5432/engine`, username
to `engine`, and password to the same private value. This disposable database loses
all data on stop; do not use it for persistent campaigns. Stop it with
`docker stop engine-dev-db`. The durable packaged profile is a later task.

From `backend/`, with those variables set:

```sh
./mvnw -B -ntp verify
./mvnw -B -ntp spring-boot:run
```

`verify` starts an ephemeral HTTP server and checks minimal health, hidden diagnostic
endpoints, PostgreSQL major version, Flyway validation, and absence of gameplay
schema/data. A missing database fails tests; nothing silently skips or uses H2.
Use a new disposable database: the test rejects existing public gameplay tables.
Flyway may create its history table, but there are no versioned migrations until P03.
The executable jar is `target/engine-0.1.0.jar`; it can also run with `java -jar`.

The development server binds to `127.0.0.1:8080`. Only `/actuator/health` is exposed,
returning status without components/details. There are no gameplay APIs or account
integration. Ordinary builds contain no browser assets. Full readiness/failure
contracts are P02.5; engine image/service integration remains P02.3. No
Testcontainers dependency or test-library helper image is used. Lint/CI is P02.4.


## Single-origin jar assembly (partial P02.3)

Build the reviewed production interface in `frontend/` with `npm ci` and
`npm run build`, then return to `backend/` and run against the disposable database:

```sh
./mvnw -B -ntp clean verify -Ppackaged-ui
java -jar target/engine-0.1.0.jar
```

The opt-in profile copies `frontend/dist/engine-ui/browser/` into the jar's
`static/` directory and copies the adjacent `3rdpartylicenses.txt` alongside it.
Spring serves the index, hashed JavaScript/CSS and license notices at the same
origin as `/actuator/health`; it needs no separate frontend server or CORS
exception. Unknown API, source/configuration and asset URLs remain 404. No broad
SPA fallback is installed: the current shell has no client routes.

Always use `clean` when changing profiles or rebuilding for distribution so old
assets cannot survive in `target/`. A guard checks the copied index and notices
before tests/package; missing production output fails the build with frontend
build instructions. `ui.dist.directory` may select a different browser output
path, with extracted notices in its parent. The HTTP tests also fail on missing
bundles or notices. Without this profile, the three interface tests skip and the
two ordinary backend integration tests still run.

On 23 October 2026 all five profile tests passed with real PostgreSQL 18 and the
existing pinned Java 21 development image. The ordinary build passed two tests
with three profile tests skipped, and the missing-output guard rejected an empty
input directory. A separate executable-jar HTTP smoke verified health and exact
index/notices bytes; archive inspection matched all four production resources.
This is application jar assembly,
not a containerized installation: the required official Temurin refresh still
blocks the first engine image, and Compose has no engine service. Do not infer
runtime account readiness, canonical campaign recovery or QA-28 from these tests.
