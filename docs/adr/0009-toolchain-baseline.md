# ADR 0009: Supported Toolchain Baseline

**Status:** Accepted for the frontend toolchain (P00.1), the backend, build, and database baseline (P00.2), the host platform, container image, Compose, and shell baseline (P00.3), and the narration runtime image (P01.1). **Date:** 22 October 2026; P00.2, P00.3, and P01.1 sections 23 October 2026.

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

P02.2 security refinement (23 October 2026): retain Angular 22.2.3 and pin its
transitive Vite dependency to **8.3.5** through npm `overrides`. The builder otherwise
pins Vite 8.3.0; a full npm audit reports development-server vulnerabilities, including
[the upstream cross-origin advisory](https://github.com/vitejs/vite/security/advisories/GHSA-9jrq-w75r-8gcw).
The patched 8.3 line preserves Node.js 24 compatibility. This override also applies
to Vitest's Vite resolution; review/remove it when the Angular pin incorporates the
fix. Verify the lockfile with full npm audit plus Windows/Linux typecheck, default
test, and production build. P02.2 verified the override with zero full-audit findings and passing Windows/Linux
locked installs, typechecks, default tests, and production builds.

Rules for applying the baseline:

- Commit `package-lock.json` and install with `npm ci` in CI and container builds. Never resolve a `latest` tag or an open range during an image build.
- Record the toolchain in `package.json` (`engines` and `packageManager`) when the workspace is scaffolded in P02.2.
- Packaged builds use the official Node.js 24.22.1 image pinned by digest ([P00.3 section](#decision-host-platforms-container-images-compose-and-shell-p003)), so hosts need no Node.js installation.
- Select the browser acceptance runner (Playwright or equivalent) when browser tests are introduced in P05, under the same pinning rules.

Node.js 26 becomes LTS on 28 October 2026, with end of life on 30 April 2029. It is not selected today because it is not yet LTS and has not been verified with this workspace. Reconsider it before the v1 release candidate (P19) or as Node.js 24 approaches end of life.

## Decision: backend, build, and database (P00.2)

| Component | Pinned version | Allowed updates | Evidence on 22–23 October 2026 |
| --- | --- | --- | --- |
| Java | Java 21 LTS: compile with `release 21`; build and run on a JDK 21 | 21.0.x updates; moving to Java 25 LTS needs a matrix update with build and test evidence | Spring Boot 4.1.2 requires Java 17 or later and is compatible up to Java 27. Eclipse Temurin 21 is supported until at least December 2029. The JDK distribution is Eclipse Temurin; its build and runtime images are pinned in the P00.3 section |
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
- Official image tags are rebuilt as their base images change. Reference the image by digest, and refresh the digest deliberately with the PostgreSQL integration checks. The P00.3 section extends this policy to all images.
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

## Decision: host platforms, container images, Compose, and shell (P00.3)

### Host platform targets

| Target | Status on 23 October 2026 | Conditions |
| --- | --- | --- |
| Windows 11 x64 with Docker Desktop (WSL 2 backend, Linux containers); `start.sh` run from Git Bash (Git for Windows) | **First validation target.** The pinned images and the Compose features below were verified here (see Verification). Launcher behavior is verified in P02.7 | Docker Desktop's own requirements apply: Windows 11 23H2 (build 22631) or later within Microsoft's servicing timeline, WSL 2.1.5 or later, 8 GB system RAM, and hardware virtualization |
| Windows 11 x64 with Bash inside a WSL 2 distribution, using Docker Desktop's WSL integration | Candidate, not verified | The owner workstation has no user WSL distribution, only Docker Desktop's internal `docker-desktop` distribution. P02.7 verifies it once a distribution is installed, and records whether a checkout on the Windows drive (`/mnt/c`) is acceptable |
| Linux with Docker Engine; macOS with Docker Desktop; Windows with Docker Desktop's Hyper-V backend | Unclaimed | Claim only after the launcher, runtime, and persistence checks pass on that host (P19). macOS ships Bash 3.2, below the shell floor |
| Windows on Arm, Windows 10, Windows Server, Docker Desktop's Windows-containers mode, WSL 1, PowerShell or `cmd.exe` as the launcher shell | Not supported | Docker Desktop on Windows Arm is early access, and Docker Desktop does not support server editions. The engine images are Linux images, and `start.sh` cannot run natively in PowerShell or `cmd.exe` |

All images are Linux images. The verified platform is `linux/amd64`. The pinned multi-platform indexes also contain `linux/arm64`, which remains unverified.

### Container images

Every reference names the exact version and OS variant plus the multi-platform index digest.

| Role | Pinned reference | Base OS | Allowed updates | Evidence on 23 October 2026 |
| --- | --- | --- | --- | --- |
| Dockerfile frontend (`# syntax=` line) | `docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e` | — | Reviewed 1.x releases | The stable `docker/dockerfile:1` channel resolves to this digest. Version 1.28.0 (7 October 2026) is published but has not been promoted to `1` |
| Frontend build stage | `node:24.22.1-trixie-slim@sha256:43b9ba1142b8dcf33fa8f922ca1be85714adc444e0586c7cf7726daaeabc1bea` | Debian 13 (glibc 2.41) | Node.js 24.x patches, as in the P00.1 section | Node.js 24.22.1 is the newest 24.x release (security release, 20 October 2026). The image reports Node.js v24.22.1 and npm 11.19.1 |
| Backend build stage | `eclipse-temurin:21.0.12.1_1-jdk-resolute@sha256:3b59c79a788985965c89203cca233c53893f62293108bb825264740fac926724` plus the Ubuntu `unzip` package | Ubuntu 26.04.1 LTS | Java 21.0.x updates; JDK and JRE move together | Newest published official Temurin 21 image: `21.0.12.1+1`. Its `linux/amd64` image is identical to that of the unsuffixed `21.0.12.1_1-jdk` tag used by the P00.2 probe |
| Engine runtime stage | `eclipse-temurin:21.0.12.1_1-jre-resolute@sha256:e7b42d0fad9b3dd6d61d6816ce3984df079a5da7d8e9af81c464243d15c04e3e` | Ubuntu 26.04.1 LTS | Same as the JDK | The JRE (49 modules, including `java.sql` and `jdk.httpserver`) ran the Spring Boot 4.1.2 probe as a non-root user. The image includes `bash`, `curl`, and `wget`, and has the `ubuntu` account (UID 1000) |
| Database | `postgres:18.6-trixie@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6` | Debian 13 | As in the P00.2 section | The registry still serves the same index digest |
| Narration runtime base (`codex` service, [`docker/codex-runtime/Dockerfile`](../../docker/codex-runtime/Dockerfile)) | `debian:trixie-20261020-slim@sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad` plus the Debian `ca-certificates` package | Debian 13 | Dated Debian 13 slim refreshes under the rules below | The image adds the runtime binary, the `runtime` account (UID/GID 10001), and, since P01.3, the root-owned engine isolation profile in `/etc/codex` with the `runtime-entrypoint` startup guard as ENTRYPOINT ([ADR 0013](0013-narration-runtime-isolation.md)). It ran discovery over stdio and over the packaged Unix socket without a network (P01.1) |
| Narration runtime executable | Upstream release `rust-v0.164.0` (`codex-cli 0.164.0`), static musl builds: `x86_64` SHA-256 `90dd0b03b28afcb7fa733a4f73b413db2916a47347efd43fb669cc1c774ee8e4`, `aarch64` SHA-256 `29a507a2a3fd8f6cd94b96b762d281d299d15d7902dee6132d314b78cf9108eb` | — | Exact version only. A new version needs the P01.1 probe to pass again and `VERSION` in `tools/runtime_probe/contract.py` to change in the same commit | `ADD --checksum` verifies each download in BuildKit. The image reports `codex-cli 0.164.0`; only `linux/amd64` was run |
| Packaged-transport probe client (`relay` service in `tools/runtime_probe/compose.yaml`) | The backend build stage reference above | Ubuntu 26.04.1 LTS | Same as the JDK | The Java 21 single-file launcher ran `UnixRelay.java` as UID 10001 with no network |

P01.5 records the exact runtime/client/host contract in the [compatibility matrix](../../tools/runtime_probe/COMPATIBILITY.md). A runtime pin change must refresh the retained neutral projection and pass the full Docker-enabled runtime suite, isolation checks and packaging checks; offline replay alone cannot qualify a version or architecture. Unexecuted architecture checksums do not establish support.

The Temurin images use Ubuntu 26.04 LTS ("resolute"): it is Temurin's default base for the 21 line, standard support runs until May 2031, and the build and runtime stages share one base. Adoptium published Temurin 21.0.13+9 binaries, the October 2026 quarterly update, on 22 October 2026, but no official `eclipse-temurin` 21.0.13 image tag existed on 23 October 2026. Refresh both JDK and JRE references to the first published 21.0.13 (or later 21.0.x) official image under the refresh rules. No release may ship on an older Java update than the newest published one.

P02.3 rechecked the official Docker Hub tag API on 23 October 2026: `https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags?page_size=100&name=21.0.13` returned `count: 0`; inspecting `21.0.13_9-jdk-resolute` returned `not found`. The JDK/JRE `21-*-resolute` aliases still resolve to the exact indexes above.

**Development-only revision (23 October 2026):** following the owner's instruction to unblock development, P02.3 may build and verify local development images using the exact official 21.0.12.1 JDK/JRE tags and index digests already recorded above. A fresh `imagetools inspect` of both exact tags confirmed those digests, while the official tag API still returned zero 21.0.13 images. This revises the earlier first-build publication prerequisite only; it does not establish security equivalence to 21.0.13 or permit a release on the older update. Keep Java 21, the same vendor/base, non-root isolation and every packaging check. Do not use floating tags or silently change Java distribution. Refresh both pins when the official update is available and repeat the build/integration checks. P19.1 must verify the newest published Java update before any release; if its official images remain unavailable, the release stays blocked pending a separately reviewed baseline. Historical checkpoint notes describing the first-build gate are superseded by this paragraph.

Rules for the images:

- The engine image is multi-stage: Angular in the Node.js stage, the Spring Boot jar in the JDK stage, and only the jar on the JRE stage, which contains no build tools, Node.js, or package caches ([deployment specification](../22-container-deployment-and-startup.md#2-runtime-topology)).
- Build stages may install named OS packages with `--no-install-recommends` from the base distribution's archive; today that is only `unzip` for the Maven Wrapper. Package versions are not pinned, because Ubuntu's update archive drops superseded versions. The runtime stage installs no packages unless this record is updated.
- The runtime stage runs as a non-root user. P02.3 uses UID/GID 10001 for engine and runtime socket access; separate mounts/networks enforce the account/data boundary.
- Use BuildKit cache mounts for the npm cache and the Maven repository, so warm builds need no downloads. Build contexts exclude secrets, private data, and local assistant files ([deployment specification](../22-container-deployment-and-startup.md#4-planned-repository-artifacts)).
  The engine's JDK verification target retains a copy of its Maven cache so full
  database tests can run offline on an internal fixture network. Only the jar
  enters the final JRE image; no build cache, source or test tools enter it.
- Generate `package-lock.json` with the pinned npm, as on the Windows workstation, and keep the Linux optional binaries in it; `npm ci` in the Linux build stage is the check. npm 11.19.1 warns that `@parcel/watcher`, `esbuild`, `lmdb`, and `msgpackr-extract` have install scripts not covered by `allowScripts`. P02.2 decides and records the install-script policy.
- Integration tests use the pinned PostgreSQL reference. Helper images used by a test library are pinned through that library's version, and P02.2 records them.

### Docker Engine, Compose, and Buildx

| Component | Required floor, checked by the launcher | Verified version |
| --- | --- | --- |
| Docker Desktop for Windows | A release that bundles the floors below, using the WSL 2 backend and Linux containers | 4.96.3 (244472), released 22 October 2026 |
| Docker Engine (daemon) | 29.0 or later, with daemon `OSType` `linux` | 29.9.2 (API 1.56), containerd 2.4.2, runc 1.5.2, cgroup v2 |
| Docker Compose plugin (`docker compose`) | 5.0 or later. The legacy standalone `docker-compose` command is not supported | v5.6.1, released 21 October 2026 |
| Docker Buildx plugin | Present; Compose 5 delegates builds to Bake | v0.38.1 |
| WSL | 2.1.5 or later (Docker Desktop requirement) | 3.0.3.0, kernel 6.18.54.1 |

Compose 5.0 (2 December 2025) removed the internal builder, so every build goes through Buildx and Bake. A 5.x floor therefore gives one build path. Engine 29.0 is the oldest release of the verified major line. Both floors are above every feature the packaged profile uses: `depends_on` with `condition: service_healthy` and `required` (Compose 2.20.0), `healthcheck.start_interval` (Compose 2.20.2 and Docker Engine 25.0), `up --wait`, file secrets with a custom `target`, internal networks, and loopback-only port publishing. Versions between a floor and the verified version are accepted but unverified, and `start.sh --check` reports the verified matrix. Raising a floor requires a reason and a verification record.

The launcher (P02.6) uses the current Docker context and never switches it. It fails with an actionable message when the CLI is missing, the daemon is unreachable, the daemon runs Windows containers, or the Engine, Compose, or Buildx floor is not met. Compose 5.6.1 prints `:0` and exits with status 0 for a port that is not published, so checks must compare command output rather than rely on exit status.

### Shell requirements

- `start.sh` is a Bash script: `#!/usr/bin/env bash`, GNU Bash 4.4 or later. It checks `BASH_VERSINFO` before using any other Bash feature. Bash 4.4 is the first release that accepts an empty array expansion under `set -u`. The script has LF line endings (`.gitattributes` applies `eol=lf` to all text files) and Git file mode `100755`.
- The script resolves its own directory (`cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P`), changes into it, and passes Compose files by relative path. That works from any working directory and for paths containing spaces. The Windows `docker.exe` cannot read MSYS paths such as `/c/Users/...`; when an absolute host path must reach Docker, convert it with `cygpath -w` where `cygpath` exists.
- Under Git Bash, export `MSYS_NO_PATHCONV=1` and `MSYS2_ARG_CONV_EXCL='*'` before calling Docker. Otherwise MSYS rewrites container paths: `-w /tmp` became `C:/Users/.../Temp`, and Docker rejected it.
- Host commands are limited to Bash builtins (including `/dev/tcp`), `docker` with its Compose and Buildx plugins, and these utilities, which are present in Git for Windows and common Linux distributions: `awk`, `cat`, `chmod`, `date`, `dirname`, `grep`, `head`, `mkdir`, `mktemp`, `od`, `rm` (only for the launcher's own temporary files), `sed`, `tr`, `uname`, plus `cygpath` when present. Do not require `curl`, `wget`, `openssl`, `jq`, `python`, `ss`, `lsof`, or `netstat` on the host; Git Bash lacks `ss`, `lsof`, and `netstat`. Adding a command requires updating this list and the P02.7 evidence.
- Generate secrets from `/dev/urandom`, for example with `od -An -N32 -tx1 /dev/urandom`, under `umask 077`. In Git Bash the MSYS runtime provides the device from the Windows system random generator.
- Docker Desktop shows Compose file secrets from the Windows drive as mode `0777`, owned by root, inside containers, whatever the host permissions. Secret confidentiality therefore rests on the host file's location and permissions and on mounting each secret only into the service that needs it. On Linux hosts, bind-mounted secrets keep host ownership and mode, so P02.3 must make each secret readable by its consuming service's UID there.
- TTY allocation and signal delivery to the native Windows `docker.exe` differ from Linux. P02.6 and P02.7 must verify the interactive `--login` flow and Ctrl+C interruption in Git Bash before claiming them.

### Image reference and digest refresh policy

This policy applies to every image: the Dockerfile frontend, build and runtime stages, Compose services, and test images.

- Reference images as `<name>:<exact version and OS variant>@sha256:<multi-platform index digest>`. Floating tags such as `latest`, `1`, `21-jdk`, `24`, or `18`, tag-only references, and platform-manifest digests are not allowed. When both are present, Docker resolves by digest; the tag documents the intended version.
- Keep each reference in one place in the build files and mirror it in this record; change both in the same commit. P02.4 delivered that check (`tools/repository_checks/checks.py images`, run in ordinary CI): every external-image `FROM`, every `# syntax=` line, and every Compose `image:` line carries an exact tag and an index digest. A `FROM` that names an earlier build stage is exempt, and so is the `image:` name given to a locally built image.
- Refresh a digest when:
  - the pinned line, or its base OS, publishes a security release or advisory. Examples: Temurin quarterly updates (January, April, July, October), Node.js security releases, PostgreSQL quarterly minor releases (February, May, August, November), and Dockerfile frontend releases.
  - a milestone demonstration or a release candidate is prepared.
  - a base OS approaches the end of its standard support.
- Refresh procedure:
  1. Resolve the digest with `docker buildx imagetools inspect <name>:<tag>`.
  2. Update the build file and this record.
  3. Rebuild the engine image, and run the frontend build, the backend tests against PostgreSQL, and the launcher checks that exist at that time.
  4. Record the evidence.
- A different line (Java 25, Node.js 26, PostgreSQL 19, or another OS base) requires an update to this record and a compatibility review. A PostgreSQL major version also requires the backup-and-migrate procedure.
- P02.4 added no update automation. Any later automation may propose digest changes, but it must not merge them without these checks.

### Resource expectations (provisional)

After the P00.3 probe, the pinned images used 337 MB (Node.js), 728 MB (JDK), 461 MB (JRE), and 650 MB (PostgreSQL). The probe engine image used 505 MB, and the BuildKit cache 2.2 GB, including the P00.2 probe's Maven cache. Plan for at least 10 GB of free Docker disk space. Docker Desktop's VM had 7.6 GiB of memory and 20 CPUs, and the probe ran without memory pressure. P02.7 replaces these figures with measurements of the real engine, including cold and warm start times.

Alternatives considered:

- **Alpine-based Node.js, JDK, or JRE images:** musl libc changes the native packages of the Angular toolchain and locale behavior, and image size is not a constraint here.
- **Distroless or minimal JRE runtime images:** they have no shell or `curl` for health checks and diagnosis. Reconsider them during hardening (P18) together with a Java-based health probe.
- **Temurin on Ubuntu 24.04 (`noble`) or UBI:** supported, but `noble`'s standard support ends in 2029, two years earlier, and neither is Temurin's default base for the 21 line.
- **Waiting for Temurin 21.0.13 images:** they were not published on 23 October 2026. The exact 21.0.12.1 pins are allowed for local development under the revision above; the newest published Java update remains mandatory before release.
- **`docker/dockerfile:1.28.0`, or no `# syntax=` line:** 1.28.0 is not yet in the stable `1` channel. Without a `# syntax=` line, the Dockerfile frontend would vary with each host's BuildKit version.
- **A Compose 2.x floor:** it covers the required features, but builds through the removed internal builder and is unverified.
- **POSIX `sh` or Bash 3.2 compatibility:** they would allow dash or macOS's default Bash, but lose arrays, `BASH_SOURCE`, and safe `set -u` with arrays. Reconsider when macOS support is evaluated.
- **A PowerShell launcher:** a second implementation to maintain and verify, while the owner requires `start.sh`.
- **A WSL distribution as the first shell:** none is installed on the owner workstation, and installing one is a system change. Git Bash ships with Git for Windows, which development already requires.

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

### Host, container images, Compose, and shell (P00.3)

- Resolved every pinned reference with `docker buildx imagetools inspect` against Docker Hub. All are OCI image indexes that include `linux/amd64` and `linux/arm64`. Also checked:
  - Docker Hub tag metadata: no `eclipse-temurin` 21.0.13 tag existed.
  - The Adoptium API: `jdk-21.0.13+9`, published 22 October 2026.
  - The Node.js release index, the Docker Desktop release notes and Windows requirements, the Compose releases and service reference, the Dockerfile `HEALTHCHECK` reference, and the Dockerfile frontend release `dockerfile/1.28.0`.
- Pulled the JDK, JRE, and Node.js images by digest and inspected them:
  - JDK and JRE: Ubuntu 26.04.1 LTS, Temurin `21.0.12.1+1`. The JDK lacks `unzip`; both images include `bash`, `curl`, and `wget`.
  - Node.js: Debian 13, Node.js v24.22.1, npm 11.19.1, glibc 2.41. The image has no `curl`, `wget`, `git`, or `python3`.
- Generated a minimal Angular 22.2.3 application with TypeScript 6.0.3, a Spring Boot 4.1.2 application, and a Compose file in an ignored scratch directory whose path contains a space. The Angular lockfile was generated with npm 11.19.1 on the Windows host and contains both the `linux-x64` and `win32-x64` optional binaries. Ran the probe from Git Bash 5.3.15, invoking it directly from the repository root and through `bash` from `/tmp`. The probe:
  - checked the CLI, the daemon `OSType`, the Engine, Compose, and Buildx floors, and Bash 4.4;
  - generated the database password from `/dev/urandom`;
  - ran `docker compose up --build --wait`, with the pinned Dockerfile frontend, BuildKit cache mounts, a health-gated `depends_on`, `start_interval` health checks, file secrets (one mapped to a Spring Boot `configtree` property), an internal data network, and a read-only engine with all capabilities dropped and `no-new-privileges`.
- Results:
  - `npm ci` installed 224 packages in 5 s and `ng build` produced the bundle in the Node.js stage.
  - The Maven Wrapper build succeeded in the JDK stage with `unzip` installed, Maven Central-only settings, and `-C`.
  - The JRE stage served the compiled Angular index and reported Java `21.0.12.1` (Temurin), running as `uid=1000(ubuntu)`.
  - PostgreSQL `18.6`, Flyway version 1, and a JSONB row were readable through the secret-provided password, and readiness health was `UP`.
  - The engine was published only on `127.0.0.1:18080`, and PostgreSQL was not published. The data network had no external route.
  - Bash `/dev/tcp` reached the published port.
  - First run to healthy: 32 s, with the base images already pulled and a Maven cache left by P00.2. Unchanged second run: 8 s to healthy, about 14 s including stop, with every build step cached.
- Rebuilding only the frontend stage without its cache took 8.4 s.
- Findings now recorded as rules above: the MSYS path rewriting, the `:0` output from `docker compose port`, the `0777` root-owned secret files on Docker Desktop, and the npm install-script warning.
- `wsl.exe -l -v` listed only `docker-desktop`, so a WSL distribution shell could not be verified.
- The probe stack was stopped with `docker compose down`, and the probe image and scratch files were removed. No container, network, or volume remains. The pinned base images and BuildKit cache remain as local cache.

These P00 results are compatibility checks, not builds of the engine. P02.2 subsequently adds the neutral frontend/backend workspaces; their own checks and limits are recorded in the roadmap and workspace READMEs.

## Tool availability inventory

Owner workstation, Windows 11, 22 October 2026, with the container and shell entries rechecked on 23 October 2026:

| Tool | Version | Relevance |
| --- | --- | --- |
| Windows | Windows 11 Pro x64, OS build 10.0.26300.9711, 20 logical CPUs | First validation host |
| Node.js / npm | 24.22.1 / 11.19.1 | Matches the pinned frontend toolchain for host development |
| Java (Oracle JDK) | 27.0.1 | Not the Java 21 target; host backend development needs a JDK 21. Containerized builds use the pinned Temurin 21 image |
| Maven | 3.10.0 | Matches the Maven baseline; project builds use the Maven Wrapper |
| Docker Desktop | 4.96.3 (244472), WSL 2 backend, `desktop-linux` context, Linux containers, 7.6 GiB VM memory | Verified container host (P00.3) |
| Docker Engine / Compose / Buildx | 29.9.2 (API 1.56) / v5.6.1 / v0.38.1; containerd 2.4.2, runc 1.5.2 | Verified versions above the launcher floors |
| WSL | 3.0.3.0, kernel 6.18.54.1; distributions: `docker-desktop` only | No user distribution, so the WSL shell candidate is unverified |
| Git / Git Bash | Git 2.56.0.windows.2; GNU Bash 5.3.15, MSYS runtime 3.6.10 | Repository workflow; first launcher shell |
| Python | 3.15.1 | Repository checks and packaging/runtime-probe tests, locally and in ordinary CI (`actions/setup-python`); not an engine dependency |

Host versions describe one development machine. The packaged profile must not depend on them.

## Consequences

Frontend and backend scaffolding in P02.2 has an unambiguous, reproducible baseline. TypeScript upgrades follow Angular's supported range rather than the npm `latest` tag. Java, Spring Boot, Flyway, and the JDBC driver move together through the Spring Boot BOM. PostgreSQL major upgrades are deliberate migrations with backups. The Spring Boot 4.1 support window makes a reviewed framework upgrade a planned task before July 2027. Packaging in P02.3 and the launcher in P02.6 start from digest-pinned Linux images, explicit Engine, Compose, and Buildx floors, and a single supported shell profile. Local development image work may use the verified existing pins under the development-only revision; refreshing to the newest published Java update remains a release gate. Image updates become deliberate, evidence-backed commits instead of side effects of a rebuild. Host support beyond Windows 11 with Docker Desktop on WSL 2 and Git Bash stays unclaimed until verified. The narration runtime image is the remaining container decision, owned by P01.1.

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
- [Docker Desktop for Windows requirements](https://docs.docker.com/desktop/setup/install/windows-install/)
- [Docker Desktop release notes](https://docs.docker.com/desktop/release-notes/)
- [Docker Compose releases](https://github.com/docker/compose/releases)
- [Compose services reference](https://docs.docker.com/reference/compose-file/services/)
- [Dockerfile reference (`HEALTHCHECK`, `# syntax=`)](https://docs.docker.com/reference/dockerfile/)
- [Dockerfile frontend releases](https://github.com/moby/buildkit/releases)
- [Eclipse Temurin official image](https://hub.docker.com/_/eclipse-temurin)
- [Adoptium release API](https://api.adoptium.net/q/swagger-ui/)
- [Node.js official image](https://hub.docker.com/_/node)
- [Ubuntu release cycle](https://ubuntu.com/about/release-cycle)
