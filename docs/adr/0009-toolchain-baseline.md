# ADR 0009: Supported Toolchain Baseline

**Status:** Accepted for the frontend toolchain (P00.1) and the backend, build, and database baseline (P00.2). The OS, engine/runtime container image, Compose, and shell sections are pending P00.3. **Date:** 22 October 2026; P00.2 section 23 October 2026.

## Context

[ADR 0001](0001-modular-stack.md) retains Angular, TypeScript, Spring Boot, Java 21, Maven, and PostgreSQL, and leaves exact versions to P00. The primary source project records Angular 22 and TypeScript 6. Resolving packages through `latest` tags is neither reproducible nor safe: on 22 October 2026 the npm `latest` TypeScript release is 7.0.2, which Angular 22 does not support.

## Decision: frontend toolchain (P00.1)

| Component | Pinned version | Allowed updates | Evidence on 22 October 2026 |
| --- | --- | --- | --- |
| Angular framework, CLI, and build (`@angular/*`) | 22.2.3 | Reviewed patches within 22.2; later 22.x minors after a compatibility review | npm `latest` is 22.2.3, published 14 October 2026. Angular 22 has been active since 3 June 2026, with active support to 2027-06 and LTS to 2028-06 |
| TypeScript | 6.0.3 | 6.0.x patches only | `@angular/compiler-cli` and `@angular/build` 22.2.3 require `>=6.0 <6.1`; TypeScript 7 is incompatible |
| Node.js | 24.22.1 (LTS "Krypton") | 24.x patches; moving to Node.js 26 needs a matrix update with build and test evidence | Angular 22.2.3 engines: `^22.22.3 \|\| ^24.15.0 \|\| >=26.0.0`. Node.js 24 entered maintenance LTS on 20 October 2026; end of life 30 April 2028 |
| npm | 11.19.1, bundled with Node.js 24.22.1 | The version bundled with the pinned Node.js release | Angular CLI engines: npm `>=8.0.0` |
| RxJS | 7.8.2 | 7.x patches | `@angular/core` peer: `^6.5.3 \|\| ^7.4.0`; RxJS 9 is still a beta |
| tslib | 2.8.1 | 2.x | `@angular/core` dependency: `^2.3.0` |
| Vitest | 5.0.5 | 5.x within the Angular build peer range | `@angular/build` peer: `^4.0.8 \|\| ^5.0.0`; Vitest engines include Node.js 24 |
| zone.js | Not used | — | Zoneless change detection follows the architecture baseline; zone.js is only an optional peer |

Rules for applying the baseline:

- Commit `package-lock.json` and install with `npm ci` in CI and container builds. Never resolve a `latest` tag or an open range during an image build.
- Record the toolchain in `package.json` (`engines` and `packageManager`) when the workspace is scaffolded in P02.2.
- Packaged builds use an official Node.js 24.22.1 image pinned by digest (P00.3), so hosts need no Node.js installation.
- Select the browser acceptance runner (Playwright or equivalent) when browser tests are introduced in P05, under the same pinning rules.

Node.js 26 becomes LTS on 28 October 2026, with end of life on 30 April 2029. It is not selected today because it is not yet LTS and has not been verified with this workspace. Reconsider it before the v1 release candidate (P19) or as Node.js 24 approaches end of life.

## Decision: backend, build, and database (P00.2)

| Component | Pinned version | Allowed updates | Evidence on 22–23 October 2026 |
| --- | --- | --- | --- |
| Java | Java 21 LTS: compile with `release 21`; build and run on a JDK 21 | 21.0.x updates; moving to Java 25 LTS needs a matrix update with build and test evidence | Spring Boot 4.1.2 requires Java 17 or later and is compatible up to Java 27. Eclipse Temurin 21 is supported until at least December 2029. The JDK distribution and image digest are pinned with the other container images in P00.3 |
| Spring Boot | 4.1.2 (`spring-boot-starter-parent`) | 4.1.x patches; Spring Boot 4.2 after its GA release and a compatibility review, before 4.1 open-source support ends | Published on Maven Central on 22 October 2026. Spring support data: 4.1.x open-source support until 31 July 2027 (commercial until 31 July 2028); 4.0.x open-source support ends 31 December 2026; 3.5.x open-source support ended 30 June 2026. 4.2.0-RC1 is a pre-release, with 4.2 GA planned for November 2026 |
| Libraries managed by Spring Boot | Versions from the Spring Boot 4.1.2 dependency BOM, including Spring Framework 7.0.10, Hibernate ORM 7.4.13.Final, Jackson 3.1.7, HikariCP 7.0.2, and JUnit Jupiter 6.0.3 | Only by changing the Spring Boot version | Values read from `spring-boot-dependencies-4.1.2.pom`. Overriding a managed version requires an ADR update and compatibility evidence |
| Flyway | 12.4.0, open-source edition: `org.flywaydb:flyway-core` plus `org.flywaydb:flyway-database-postgresql` | Follows the Spring Boot BOM; no independent override | Managed by Spring Boot 4.1.2. The newest Flyway release is 13.11.1, but Spring Boot 4.1's Flyway auto-configuration targets the managed 12.x line. The Redgate editions (`com.redgate.flyway`) are not used |
| PostgreSQL JDBC driver | 42.7.14 (`org.postgresql:postgresql`) | Follows the Spring Boot BOM | Managed by Spring Boot 4.1.2; also the latest 42.7.x release on Maven Central |
| PostgreSQL server | Major version 18, minor 18.6. Image `postgres:18.6-trixie` (Debian 13), multi-architecture index digest `sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6` | 18.x minor releases after reviewing the release notes and re-pinning the digest. A new major version is an explicit migration with a verified backup, never an automatic image update | PostgreSQL 18 was released on 25 September 2025 and is supported until 14 November 2030. 18.6 is the current minor release (August 2026). PostgreSQL 19 is a release candidate (RC 1, 15 October 2026). PostgreSQL 16, the source project's candidate, is supported until 9 November 2028 |
| Database backup tooling | `pg_dump` and `pg_restore` 18.6 from the same pinned image | Same as the server | Client and server versions match, so dumps never come from an older client |
| Maven | 3.10.0 | 3.10.x patches | Released 27 September 2026. The Maven project maintains the newest release of the last two GA series (3.10.0 and 3.9.16). Maven 4.0.0 is not GA (rc-7). Spring Boot 4.1.2 requires Maven 3.6.3 or later |
| Maven Wrapper | 3.3.4, `only-script` type (no `maven-wrapper.jar`), `distributionSha256Sum=1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff` for `apache-maven-3.10.0-bin.zip` | Regenerate with the official `maven-wrapper-plugin` and a new checksum whenever Maven changes | 3.3.4 is the newest Maven Wrapper release. The checksum was computed from a strictly verified download (see Verification) |

Rules for applying the baseline:

- The backend inherits `spring-boot-starter-parent` 4.1.2 and sets `java.version` to 21. Dependencies and plugins take their versions from the parent; no version ranges, `SNAPSHOT` dependencies, or `latest`/`release` resolution.
- Commit `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties`, generated by `maven-wrapper-plugin` 3.3.4 with `-Dmaven=3.10.0 -Dtype=only-script` and the distribution checksum above. Do not commit a wrapper JAR.
- The Unix `mvnw` script downloads the `.tar.gz` distribution instead of the `.zip` when `unzip` is unavailable, and then fails the zip checksum. Linux environments that run the wrapper, including the engine image build stage, must provide `unzip`.
- Flyway's parent POM (`flyway-parent` 12.4.0) declares additional repositories (GitHub Packages, a Redgate feed, Gradle, and an Amazon S3 bucket). P02.2 must limit dependency and plugin resolution to Maven Central (for example, through a mirror for all repositories in the build's Maven settings) and build with strict checksum validation (`--strict-checksums`).
- P02.2 adds `maven-enforcer-plugin` rules for JDK `[21,22)`, Maven `[3.10.0,3.11)`, and release-only, non-dynamic dependency versions.
- Database migrations use Flyway's versioned forward migrations with the open-source PostgreSQL module. Pack and database migrations remain explicit operations ([deployment specification](../22-container-deployment-and-startup.md)).
- PostgreSQL 18 images declare `VOLUME /var/lib/postgresql` and keep the cluster in `PGDATA=/var/lib/postgresql/18/docker`. Mount the durable database volume at `/var/lib/postgresql`, not at `/var/lib/postgresql/data` as for PostgreSQL 17 and earlier images. The major-version subdirectory keeps a later major upgrade explicit.
- Use the Debian (`trixie`) image variant for glibc-based locale behavior. Alpine images use musl libc, and the official image documentation limits their locale support to ICU locales.
- Official image tags are rebuilt as their base images change. Reference the image by digest, and refresh the digest deliberately with the PostgreSQL integration checks. P00.3 extends this policy to all images.
- Host-based backend development needs a JDK 21. The owner workstation's Oracle JDK 27.0.1 is compatible with Spring Boot 4.1.2 but is not the Java 21 target, so the enforcer rule will reject it. The packaged profile needs no host JDK or Maven.

Alternatives considered:

- **Spring Boot 3.5:** open-source support ended on 30 June 2026; only commercial support remains.
- **Spring Boot 4.0:** open-source support ends on 31 December 2026, about ten weeks after this decision.
- **Spring Boot 4.2:** only RC1 exists. Re-evaluate it after GA; it is the expected upgrade before 4.1 open-source support ends.
- **Overriding Flyway to 13.x:** an unverified pairing with Spring Boot 4.1 auto-configuration, with no current need.
- **PostgreSQL 16 or 17:** both are supported, but PostgreSQL 18 is supported for two more years than 16. No engine data exists yet, so starting on 16 offers no migration benefit. **PostgreSQL 19:** still a release candidate.
- **Maven 3.9.16:** maintained, but 3.10.0 is the newest GA series and matches the workstation. **Maven 4.0:** not GA.
- **Maven Wrapper `bin` or `source` types:** add a JAR or compiled sources to the repository without a benefit for this project.

Known limitation: Spring Boot patch releases keep the managed Flyway 12.4 line, so a fix published only in a later Flyway release does not reach this baseline automatically. If an advisory affects Flyway 12.4.0 and no Spring Boot 4.1 patch resolves it, evaluate a Flyway override or a Spring Boot upgrade, and rerun the PostgreSQL integration checks. Spring Boot 4.1 open-source support ends on 31 July 2027; plan the move to a supported line before that date.

## Verification

### Frontend toolchain (P00.1)

- Queried npm registry metadata for `@angular/core`, `@angular/compiler-cli`, `@angular/cli`, and `@angular/build` 22.2.3, plus `typescript`, `rxjs`, `tslib`, and `vitest` 5.0.5.
- Checked Node.js release data from `nodejs.org/dist/index.json` and the Node.js Release schedule, and the Angular support window from the Angular releases page.
- Ran `npm install --package-lock-only --ignore-scripts` for the pinned set in a temporary directory outside the repository with Node.js 24.22.1 and npm 11.19.1: resolution succeeded without conflicts.
- Ran the same resolution with TypeScript 7.0.2: it failed with `ERESOLVE` on peer `typescript@">=6.0 <6.1"` from `@angular/build@22.2.3`.

### Backend, build, and database (P00.2)

- Queried Maven Central metadata for `spring-boot`, `spring-boot-dependencies` 4.1.2 (managed versions), `flyway-core`, `org.postgresql:postgresql`, `apache-maven`, and `maven-wrapper`. Also checked the Spring Boot generations API, the Spring Boot 4.1.2 system requirements, the PostgreSQL versioning policy, the Maven release history, Docker Hub tag metadata for `postgres` and `eclipse-temurin`, and the Redgate Flyway PostgreSQL page.
- Resolved `apache-maven-3.10.0-bin.zip` with host Maven 3.10.0 and `--strict-checksums`, then computed its SHA-256. The SHA-512 files published on Maven Central and on `downloads.apache.org` are identical.
- Generated an `only-script` Maven Wrapper 3.3.4 for Maven 3.10.0 with that checksum. In an ignored scratch directory, built a throwaway Spring Boot 4.1.2 probe with JDBC, Flyway, and the PostgreSQL driver. Ran `./mvnw -B -ntp -C verify` in `eclipse-temurin:21-jdk` (resolved to 21.0.12.1+1, digest `sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d`) with `unzip` installed. The probe ran on a private Docker network against the pinned `postgres:18.6-trixie` digest, with tmpfs storage. Results:
  - The wrapper downloaded Maven 3.10.0 and accepted the SHA-256.
  - The enforcer confirmed Java `[21,22)` and Maven `[3.10.0,3.11)`.
  - Four integration tests passed: Java 21 runtime, PostgreSQL server major version 18, Flyway 12.4.0 applying a forward migration with no pending migration, and JSONB storage with database-enforced unique-key rejection.
  - Flyway logged `PostgreSQL 18.6` without an untested-version warning.
- Without `unzip`, the same wrapper downloaded the `.tar.gz` distribution and rejected it with a checksum error. The `unzip` rule above comes from this result.
- All 399 artifact origin records in the probe's fresh local repository name Maven Central. The extra repositories in `flyway-parent` answered HTTP 401 and supplied nothing.
- `pg_dump -Fc` and `pg_restore` 18.6 restored the migrated database into a new database, preserving the Flyway history row and the JSONB row.
- The probe containers and network were removed. No Docker volume was created.

These are compatibility checks, not a build of the engine; no frontend or backend workspace exists yet.

## Tool availability inventory

Owner workstation, Windows 11, 22 October 2026:

| Tool | Version | Relevance |
| --- | --- | --- |
| Node.js / npm | 24.22.1 / 11.19.1 | Matches the pinned frontend toolchain for host development |
| Java (Oracle JDK) | 27.0.1 | Not the Java 21 target; host backend development needs a JDK 21. Containerized builds use a JDK 21 image |
| Maven | 3.10.0 | Matches the Maven baseline; project builds use the Maven Wrapper |
| Docker Engine / Compose | 29.9.2 / v5.6.1 (Docker Desktop with WSL2) | Used for the P00.2 probe; container baseline in P00.3 |
| Git | 2.56.0 | Repository workflow |
| Python | 3.15.1 | Local repository checks only; not an engine dependency |

Host versions describe one development machine. The packaged profile must not depend on them.

## Consequences

Frontend and backend scaffolding in P02.2 has an unambiguous, reproducible baseline. TypeScript upgrades follow Angular's supported range rather than the npm `latest` tag. Java, Spring Boot, Flyway, and the JDBC driver move together through the Spring Boot BOM. PostgreSQL major upgrades are deliberate migrations with backups. The Spring Boot 4.1 support window makes a reviewed framework upgrade a planned task before July 2027. Remaining version ambiguity is limited to the OS targets, engine/runtime images, Compose, and shell requirements, which P00.3 adds to this record.

## References

- [Angular releases and support policy](https://angular.dev/reference/releases)
- [Angular version compatibility](https://angular.dev/reference/versions)
- [Node.js release schedule](https://github.com/nodejs/Release)
- [Node.js release index](https://nodejs.org/dist/index.json)
- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot support generations](https://api.spring.io/projects/spring-boot/generations)
- [PostgreSQL versioning policy](https://www.postgresql.org/support/versioning/)
- [PostgreSQL official image](https://hub.docker.com/_/postgres)
- [Flyway PostgreSQL support](https://documentation.red-gate.com/fd/postgresql-database-277579325.html)
- [Maven release history](https://maven.apache.org/docs/history.html)
- [Maven Wrapper plugin `wrapper` goal](https://maven.apache.org/tools/wrapper/maven-wrapper-plugin/wrapper-mojo.html)
