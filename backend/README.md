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
returning status without components/details. There are no gameplay APIs, account
integration, or browser assets in the jar yet. Full readiness/failure contracts are
P02.5; container networking and single-origin asset packaging are P02.3. No
Testcontainers dependency or test-library helper image is used. Lint/CI is P02.4.
