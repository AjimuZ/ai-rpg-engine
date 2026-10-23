# Development Roadmap

## 1. Status and planning rules

**Current status: P00 complete; P01 in progress (P01.1 complete). No AI RPG Engine application code has been implemented.** The only code is the developer compatibility probe in `tools/runtime_probe` and the narration runtime image definition in `docker/codex-runtime`. Runtime implementation work remains pending. Predecessor milestones and repository housekeeping do not complete an engine implementation work package.

Repository preparation recorded on 22 October 2026 includes the private GitHub repository, root MIT license, attribution, ignore rules, text line-ending policy, and contribution guidance. The development workflow requires immediate roadmap and affected-documentation updates after every work item, truthful completion evidence, and neutral commit messages. The owner's checkout also has verified local Git gates; lifecycle-hook activation requires review in the relevant client. These local checks are not distributed in a clone and do not replace P02.4 continuous integration.

After each change, update the applicable item or record partial progress before starting the next item. Keep the affected specifications and changelog consistent, and mark a checkbox only when its stated exit checks actually pass. See [the contribution workflow](../CONTRIBUTING.md#documentation-synchronization).

The owner also requires a fully containerized packaged installation and a root `start.sh` entry point. [The deployment specification](22-container-deployment-and-startup.md) and [ADR 0008](adr/0008-containerized-startup.md) document that requirement. Packaging, runtime IPC, account handling, and startup checks remain unimplemented; no roadmap implementation item is completed by this documentation change.

This is an ordered development roadmap, not a claim of committed delivery dates. The work spans architecture, authoring, AI integration, rules, UI, and operations. Estimate each micro-phase after its prerequisites are verified. A credible calendar requires team availability and results from the Codex compatibility and combat prototypes.

Each work package must be split into small reviewable changes with one main objective. For each change record intended behavior, affected modules/files, required checks, documentation update, completion evidence, and a suggested English commit message. Source paths below describe **future** work; they are not folders scaffolded by this documentation phase.

Do not commit, push, create a remote repository, or publish a release merely because a roadmap task mentions it. Those actions follow the owner's development/publication instructions when execution begins. As of 22 October 2026 the owner authorizes commits and pushes of verified implementation work to `main`, the project's single branch; releases and publication remain owner decisions. Each implementation commit names its roadmap item in a final `Roadmap: <item>` body line so progress can be traced to Git history ([contribution workflow](../CONTRIBUTING.md#git-workflow)).

## 2. Milestones

| Milestone | Work packages | Demonstrable outcome |
| --- | --- | --- |
| D0 — Design | This documentation set | Reviewable engine specification and development plan |
| F1 — Feasibility | P00–P01 | Supported stack baseline and safe functioning Codex contract |
| F2 — Foundations | P02–P07 | Validated packs, persistent campaigns, authoritative command pipeline |
| F3 — Persistent AI play | P08–P10 | Codex narration, renewable context, character/inventory/quest loop |
| F4 — Playable MVP | P11–P14 | Complete combat, dynamic bounded encounters, progression, recovery, neutral demo |
| F5 — Complete engine beta | P15–P17 | Full supported modules, author operations, presentation and accessibility |
| F6 — v1.0 release | P18–P19 | Verified long campaigns, reproducible setup, rights review, release documentation |

Critical dependency chain: compatible stack → safe Codex profile → persistence/content → command validation → AI loop/memory → combat → encounters/progression → hardening → publication. UI styling, sample content, and documentation can be developed alongside stable contracts; they must not conceal unresolved authority or persistence work.

## 3. P00 — Resolve and record implementation baselines

**Depends on:** D0 review. **Areas:** toolchain decisions, architecture, scope.

- [x] P00.1 Select supported Angular/TypeScript/Node/npm versions using the primary project's Angular 22 baseline and actual compatibility constraints.
- [x] P00.2 Select a maintained Spring Boot version supporting Java 21, Maven Wrapper version, PostgreSQL major/image, and Flyway version.
- [x] P00.3 Pin initial supported OS/runtime targets, Linux container images, Compose/engine versions, and shell requirements; Windows with WSL2 is first to validate.
- [x] P00.4 Confirm AI RPG Skirmish as the implementable default and record numerical defaults as provisional.
- [x] P00.5 Record the selected name AI RPG Engine and confirm v1 boundaries, initial file format, and licensing plan.

**Progress (22 October 2026):** P00.1 is complete. [ADR 0009](adr/0009-toolchain-baseline.md) pins Angular 22.2.3, TypeScript 6.0.3, Node.js 24.22.1 LTS, npm 11.19.1, RxJS 7.8.2, tslib 2.8.1, and Vitest 5.0.5, and records the workstation tool inventory. Evidence: npm registry engines/peer metadata, Angular and Node.js support schedules, a conflict-free lockfile resolution of the pinned set with npm 11.19.1, and an `ERESOLVE` rejection of TypeScript 7.0.2.

**Progress (23 October 2026):** P00.2 is complete. [ADR 0009](adr/0009-toolchain-baseline.md) pins Spring Boot 4.1.2 on Java 21 (`release 21`; open-source support until 31 July 2027), with the libraries it manages, including Flyway 12.4.0 (open-source PostgreSQL module) and PostgreSQL JDBC 42.7.14. It also pins Maven 3.10.0 through an `only-script` Maven Wrapper 3.3.4 with a distribution SHA-256, and PostgreSQL 18.6 as `postgres:18.6-trixie` by digest, with `pg_dump`/`pg_restore` from the same image. Evidence: Maven Central, Spring, PostgreSQL, Maven, and Docker Hub support and release metadata. A throwaway probe in an ignored scratch directory ran `./mvnw -B -ntp -C verify` on Eclipse Temurin 21.0.12.1 against the pinned PostgreSQL image. It passed: wrapper checksum, enforcer Java/Maven rules, four integration tests (Java 21 runtime, server major 18, Flyway forward migration, JSONB and unique key), Central-only artifact origins, and a dump/restore round trip into a new database. The probe also found two rules now recorded in the ADR: Linux wrapper runs need `unzip`, and Flyway's parent POM declares extra repositories, so P02.2 must restrict resolution to Maven Central. Probe containers and network were removed; no volume was created. The JDK distribution and image digest belong to P00.3.

**Progress (23 October 2026):** P00.3 is complete. [ADR 0009](adr/0009-toolchain-baseline.md) now records the host, image, Compose, and shell baseline:

- **First validation target:** Windows 11 x64 with Docker Desktop (WSL 2 backend, Linux containers), running `start.sh` from Git Bash. Bash in a WSL distribution is an unverified candidate, and Linux and macOS hosts remain unclaimed.
- **Images, pinned by multi-platform index digest:** Dockerfile frontend 1.27.1; `node:24.22.1-trixie-slim` for the frontend build stage; Eclipse Temurin `21.0.12.1_1-jdk-resolute` and `21.0.12.1_1-jre-resolute` (Ubuntu 26.04 LTS) for the backend build and runtime stages; and the P00.2 PostgreSQL image.
- **Launcher floors:** Docker Engine 29.0, Compose 5.0, and a Buildx plugin, verified with Docker Desktop 4.96.3, Engine 29.9.2, Compose v5.6.1, and Buildx v0.38.1. The shell floor is GNU Bash 4.4, with a fixed list of host commands and Git Bash path-conversion rules.
- **Refresh policy:** a digest-refresh policy now covers every image. Temurin 21.0.13+9 binaries were published on 22 October 2026, but no official image existed on 23 October 2026, so P02.3 must refresh the JDK and JRE references before building the first engine image. The narration runtime image was deferred to P01.1, which has since pinned it in the same record.

Evidence: registry digests resolved with `docker buildx imagetools inspect`; Adoptium, Node.js, Docker Desktop, Compose, and Dockerfile reference metadata; and a throwaway Compose probe run from Git Bash in an ignored scratch path containing a space.

- **Probe stack:** it built a minimal Angular 22.2.3 application with `npm ci` (lockfile generated on Windows) and `ng build` on the Node.js image. A Spring Boot 4.1.2 jar was built with the Maven Wrapper on the JDK image (with `unzip`) and run on the JRE image as UID 1000, against PostgreSQL 18.6.
- **Compose features exercised:** a health-gated `depends_on`, `start_interval` health checks, file secrets, an internal data network, a loopback-only port, a read-only engine with all capabilities dropped, and `up --wait`.
- **Results:** host checks confirmed the served Angular index, Java 21.0.12.1, PostgreSQL 18.6 with a Flyway migration, health `UP`, an unpublished database, and no external route from the data network. Startup took 32 s to healthy on the first run and 8 s on the unchanged second run.
- **Findings recorded as rules:** MSYS path rewriting, `docker compose port` printing `:0` with exit status 0, Docker Desktop presenting secret files as `0777`, and an npm install-script warning for P02.2.

The probe stack, image, and scratch files were removed. No volume was created. No WSL user distribution is installed, so the WSL shell candidate is not verified. Launcher behavior remains P02.6–P02.7 work.

**Progress (23 October 2026):** P00.4 is complete. [ADR 0006](adr/0006-original-combat.md) is accepted: AI RPG Skirmish is the implementable default ruleset. A review of the combat, gameplay, encounter, and worked-scenario documents found every formula and value needed for the first implementation. The worked fight's derived values match the formulas: Health 24, Focus 10, Guard 12, threat capacity 8, level-2 maxima 26 and 11, and 16 seconds of world time. The new [provisional numerical defaults register](11-combat-system.md#17-provisional-numerical-defaults) separates two things:

- **Confirmed structure:** zones, persistent initiative, the AP economy, predeclared reactions, 2d10 margin checks, flat armor, and backend dice. Changing one needs a superseding ADR.
- **Provisional numbers:** the tunable values, each with its defining section and calibration gate (P11, P13, P18.3). They are stored as ruleset-profile data and change only through versioned ruleset revisions.

One previously implicit point is now explicit: the default profile has no maximum level, and a pack may declare one. This was a documentation review; no simulation or playtest ran, so balance remains unclaimed. P00.5 remains open, so the package exit is not yet met.

**Progress (23 October 2026):** P00.5 is complete, and with it the P00 package. [ADR 0010](adr/0010-name-scope-format-licensing.md) is accepted:

- **Name:** the owner-selected name AI RPG Engine is recorded with its derived identifiers. The slug, Compose project, and npm/Angular name are `ai-rpg-engine`; the Maven coordinates are `io.github.ajimuz:ai-rpg-engine-backend`; the Java base package is `io.github.ajimuz.rpgengine`; and the ruleset ID is `skirmish`. No trademark search has been done; P19.1 owns it.
- **v1 boundaries:** the MVP, v1.0, and out-of-scope lines in the requirements are confirmed without change. Unverified hosts and prebuilt image publication are explicitly outside v1.
- **Pack contract 1:** UTF-8 without a byte-order mark; YAML 1.2 core-schema `.yaml` files with no anchors, aliases, merge keys, tags, or duplicate keys; equivalent `.json`; CommonMark `.md` prose with no front matter or rendered HTML; JSON Schema draft 2020-12; directory or `.zip` distribution. The campaign archive format belongs to P16.
- **Licensing plan:** MIT for the engine and the neutral sample packs, third-party packs under their own terms, a permissive allowlist for bundled dependencies (inventory gate in P02.4, review in P19.1), and locally built images that are not redistributed.

Checks: a documentation review against the requirements, pack, security, and publication specifications, plus a local link and anchor check of the changed documents. No parser exists yet; P04.1, P04.4, and P04.5 verify the format with the corpus tests listed in the ADR. **P00 exit evidence:** ADR 0009 holds the version matrix, ADR 0006 and ADR 0010 settle the remaining pre-scaffold decisions, there is no "Java 17/21" ambiguity or `latest` dependency, and the only deferred items are the narration runtime image (P01.1) and the Temurin 21.0.13 image refresh (P02.3), both with owners.

**Deliverables:** a concrete version matrix and updated ADR status. **Exit:** no ambiguous “Java 17/21,” implicit latest dependencies, or unowned pre-scaffold decisions. **Checks:** dependency compatibility references and a minimal tool availability inventory. **Suggested commit:** `Record supported toolchain and engine baselines`.

## 4. P01 — Prove Codex protocol and capability isolation

**Depends on:** P00. **Areas:** future `backend/.../codex`, isolated verification fixtures, operations docs.

- [x] P01.1 Establish pinned Codex executable/image versions, local stdio probe and packaged private IPC transport, handshake, container account readiness, paginated catalog, and output contract.
- [ ] P01.2 Verify thread creation/resume, explicit per-turn model/effort, notification correlation, terminal status, and interruption.
- [ ] P01.3 Demonstrate restricted roots, denied canonical writes, unavailable unwanted host tools, and inherited-config isolation.
- [ ] P01.4 Demonstrate owned-process shutdown, EOF/timeout handling, stderr bounds, and early-event buffering.
- [ ] P01.5 Save neutral protocol fixtures and a supported-version matrix; record service maturity and platform limitations.
- [ ] P01.6 Record supported image-generation capability or explicit unavailability, restricted invocation, output formats and account/cost boundaries; gate actual generation without blocking text-only fallback or enabling paid access implicitly.

**Progress (23 October 2026):** P01.2 has a verified partial implementation in [`tools/runtime_probe`](../tools/runtime_probe/README.md):

- **Lifecycle:** the pinned 0.164.0 app-server uses a scripted upstream on an internal Docker network without credentials. Seven actual turns across two threads prove creation, reconnect/resume, explicit upstream model/effort/full-schema comparisons, foreign-event filtering, completed/failed/interrupted outcomes and closure of both interrupted upstream streams. Resume checks ordered terminal histories and sticky settings; the runtime itself stays alive.
- **Regression coverage:** effective-policy validation, unknown-error redaction, missing output, malformed lifecycle events, early notifications, privileged-request denial, queue bounds, buffered-event deadlines and refusal to treat an interrupt acknowledgment as terminal confirmation. The sequential probe discards foreign events; a production concurrent dispatcher remains future adapter work.
- **Verification:** on Windows 11 / Docker Desktop Engine 29.9.2 / `linux/amd64`, `py -B -m unittest discover -s tools/runtime_probe` with `RUNTIME_PROBE_DOCKER` set passed all **51 tests** (including all 30 lifecycle checks). This includes the pinned JDK's warning-free compilation checks and the relay's 40 framing/handshake checks. `docker compose --file tools/runtime_probe/session.compose.yaml config --quiet` passed. Both fixture projects returned successful, empty container listings after the suite; no volumes were deleted. Every Python class/function in the changed files has a docstring. No account-backed call was made; the scripted report retains `inferenceVerified: false`.
- **Remaining gate:** P01.2 stays unchecked because the opt-in real neutral task requires the owner's supported sign-in. The owner must initialize a dedicated runtime-home volume for UID/GID 10001, explicitly run the documented `probe.py live` command, and retain a successful correlated completed-envelope/resume report before closure. The probe never signs in or copies host credentials. P01.3–P01.5 isolation, runtime lifecycle, retained compatibility evidence and platform gates remain open; QA-08 and QA-28 are not claimed.

**Progress (23 October 2026):** P01.1 is complete for discovery without an account:

- **Pins:** runtime release `rust-v0.164.0` (`codex-cli 0.164.0`), using static musl binaries verified by `ADD --checksum`, on `debian:trixie-20261020-slim` pinned by index digest. The image is defined in [`docker/codex-runtime/Dockerfile`](../docker/codex-runtime/Dockerfile) and runs as UID/GID 10001. [ADR 0009](adr/0009-toolchain-baseline.md#container-images) mirrors every reference. `test_packaged.py` fails if the Dockerfile, the Compose fixture, the ADR, and `contract.VERSION` drift apart.
- **Stdio probe:** `probe.py host|container` owns the child process. It sends `initialize`, then the `initialized` notification, then `account/read` with `refreshToken: false`, then `model/list` one page at a time (`limit: 2`, bounded to 32 pages, 256 models, and 1 MiB messages). Server requests are denied, stderr is counted and discarded, and the child environment is an allowlist.
- **Packaged IPC:** `probe.py packaged` runs [`tools/runtime_probe/compose.yaml`](../tools/runtime_probe/compose.yaml). The runtime listens on `unix:///run/runtime/control.sock`. A Java 21 client, `UnixRelay.java`, stands in for the engine adapter and reaches the socket only through a tmpfs IPC volume. Neither service has a network, and both run read-only with all capabilities dropped and `no-new-privileges`.
- **Transport findings:** the runtime speaks WebSocket (RFC 6455) text messages over the Unix socket, not newline-delimited JSON. It binds the real socket, with mode `0600`, at `/tmp/codex-daemon-<uid>/<sha256(path)>`, and leaves only a symlink at the configured path. The [deployment specification](22-container-deployment-and-startup.md#3-runtime-integration-gate) records the consequences.
- **Results:** these ran on Windows 11 with Docker Desktop (Engine 29.9.2, Compose 5.6.1, `linux/amd64`).
  - The container stdio profile and the packaged socket profile both returned version 0.164.0, account state `sign-in-required`, and the same eight-model catalog across several pages.
  - Relays running as UID 10002 and as root without capabilities were refused (`socket-io`).
  - The pinned JDK compiled the relay with `-Xlint:all -Werror`, and its 40 handshake and framing checks passed.
  - `py -B -m unittest discover -s tools/runtime_probe` passed 21 tests. It skipped the 3 Docker tests, which run only when `RUNTIME_PROBE_DOCKER` is set. With it set, all 6 `test_packaged.py` tests passed.
  - No probe container remained afterwards.
- **Output contract:** `OUTPUT_SCHEMA` and the strict validator in `contract.py` are verified offline only ([contract section 16](21-ai-task-contracts-and-prompts.md#16-neutral-protocol-proof-p011)).
- **Not verified, with owners:**
  - Sign-in, a signed-in readiness state, live inference, and live structured output need the owner's account and thread/turn support. They belong to P01.2 and the P01 opt-in check.
  - The runtime logs that no system `bubblewrap` is on `PATH` and falls back to its bundled copy. Whether that sandbox works with capabilities dropped belongs to P01.3.
  - Only `linux/amd64` was run.
  - The fixture account lives in tmpfs; persistent account volumes belong to P02.3.

**Deliverables:** isolated compatibility probe and evidence, later retained as adapter tests when authorized implementation begins. **Exit:** no unreviewed broad write/tool privileges and no dependency on undocumented launch assumptions. Failure blocks real gameplay integration; it does not authorize a generic AI replacement. **Checks:** one opt-in real neutral task plus deterministic protocol fixtures. **Suggested commit:** `Verify narration runtime compatibility and isolation`.

## 5. P02 — Establish repository and quality infrastructure

**Depends on:** P00; can follow P01's scope findings. **Areas:** future root configuration, `frontend/`, `backend/`, `.github/`.

The owner-approved root MIT license, ignore rules, text line-ending policy, and contribution terms were delivered during repository preparation on 22 October 2026 (licensing decision: [ADR 0010](adr/0010-name-scope-format-licensing.md#decision-licensing-plan)). P02.1 now adds shared `.editorconfig` formatting defaults, Python-cache ignores and a sanitized `.env.example` with neutral, non-secret labels and no current consumer; CONTRIBUTING documents their scope. Verified on Windows 11 on 23 October 2026: 33 real Git ignore cases; UTF-8/LF/final-newline and Git binary-attribute checks; unchanged license/notices; neutral example and tracked-content pattern review; and 275 local Markdown links/anchors across 48 documents. `git diff --check` passed. `py -B -m unittest discover -s tools/runtime_probe` ran 51 tests (46 passed, five Docker checks skipped because `RUNTIME_PROBE_DOCKER` was unset). No application build, health or database check ran.

P02.1 covers repository policy only: clean-clone application build, health, database and packaged-startup checks remain gates for P02.2–P02.7 and the package exit below.

P02 applies the image, Compose, launcher-floor, shell, and digest-refresh rules of [ADR 0009](adr/0009-toolchain-baseline.md). P02.3 must first move the Temurin JDK and JRE references to the first published 21.0.13 (or later 21.0.x) official image. P02.2 records the npm install-script policy and any test-library helper images.

- [x] P02.1 Add owner-approved license, ignore rules, formatting/line-ending rules, contribution policy, and neutral environment placeholders.
P02.2 delivered neutral standalone/zoneless Angular 22.2.3 and Spring Boot 4.1.2
shells without adventure constants, canonical migrations, gameplay APIs, runtime
account integration, or packaged startup. ADR 0009 records a Vite 8.3.5 security
override while retaining all framework pins. Workspace READMEs document host
commands and the npm default install-script policy (four uncovered-script warnings;
no broad allow grant). No test-library helper image or Testcontainers is used.

Verified on 23 October 2026: Windows Node.js 24.22.1/npm 11.19.1 and a clean source
copy in the pinned Linux Node image both pass `npm ci`, typecheck, default Vitest
(one test), and production build. Full Windows `npm audit --audit-level=high` reports
zero vulnerabilities after the override. The pinned Temurin JDK 21 container with
`unzip` passes `bash ./mvnw -B -ntp verify`: two real HTTP/PostgreSQL 18 tests,
Flyway validation, minimal health/hidden diagnostics, and executable jar packaging.
The Windows wrapper correctly rejects host JDK 27. A temporary policy verifier
checks exact pins and Linux/Windows lock entries, wrapper SHA-256, 339 Central-only
artifact records, 12 real ignore paths, 290 local documentation links/fences, and a
bounded secret/private-path scan. Initial health and audit failures were fixed and
checks rerun; no test was removed or weakened. Lint/CI automation remains P02.4,
full readiness/failure states P02.5, packaging/launcher/restart QA-28 P02.3/P02.6/P02.7.
The package exit below remains open.

- [x] P02.2 Scaffold Angular and Spring Boot using pinned versions, with no adventure constants.

P02.3 partial checkpoint (23 October 2026): root and independent runtime
build contexts now default-deny unreviewed inputs, with final recursive
exclusions for private settings, credentials, data, and build output. Two
real BuildKit export tests pass against isolated neutral fixtures, including
nested private paths and local editor/account stores; an initial directory-
admission leak was fixed. The existing runtime image still builds with its
independent context policy. The offline runtime suite passes 46 tests, with
five Docker checks skipped because `RUNTIME_PROBE_DOCKER` is unset.
Packaging remains incomplete: Docker Hub's official Temurin tag API returned
zero 21.0.13 tags, and the JDK/JRE resolute aliases still resolve to the
21.0.12.1 indexes. ADR 0009 forbids the first engine build before the Java
refresh. No engine image, root Compose, volumes, secrets, single-origin
interface or startup/restart verification is claimed by this checkpoint.

- [ ] P02.3 Add pinned engine/runtime images, Compose services, durable volumes, private networks/IPC, secret handling, and the single-origin packaged interface; exclude private development data from builds.

P02.3 second partial checkpoint (23 October 2026): root `compose.yaml`
adds the pinned database/runtime infrastructure, separate data/inference networks,
database-only file secret, private durable database/account/workspace volumes,
and the proven tmpfs socket volume. Two rendered-configuration regression tests
pass. An account-free real Docker test passes password-authenticated database
connectivity (with passwordless TCP rejected), service network/mount/UID checks, socket mode 0600 and neutral data
preservation across recreation of both containers. Test services stop afterwards;
fixture volumes and their ignored password file remain. This is infrastructure
evidence, not campaign/schema, account or full QA-28 acceptance. No engine service
or packaged interface exists, and the mandatory Java image gate persists.
All five packaging tests pass with `PACKAGING_DOCKER=1` (including real BuildKit
exports); the offline runtime suite passes 46 tests and skips five opt-in Docker
checks. Changed text/docstrings, Markdown fences and 120 local links pass review.

P02.3 third partial checkpoint (23 October 2026): the opt-in Maven
`packaged-ui` profile embeds compiled Angular production assets and extracted
third-party notices in the executable Spring Boot jar. All five backend tests
pass against disposable PostgreSQL 18, including real HTTP checks for index,
JavaScript/CSS, license notices and missing-resource/API 404 responses. The new
index and notices assertions were first observed failing with 404. Frontend
strict typechecks, its unit test, production build and full npm audit pass.
The ordinary backend build passes two tests with three profile tests skipped;
missing browser output is rejected. The executable jar serves byte-identical
index/notices and healthy HTTP responses, with exactly the four expected static
resources embedded. All five packaging checks pass, including Docker recreation;
46 offline runtime checks pass with five opt-in Docker checks skipped.
Verification uses the existing Java 21 development image, without building an
engine image or weakening ADR 0009. The required new official JDK tag still
returns not found. Engine Dockerfile/service, secret consumption, engine-only
content/assets storage and full container integration remain open; P02.3 and
QA-28 are not complete. See [backend commands](../backend/README.md).

P02.3 fourth partial checkpoint (23 October 2026): the Spring `packaged` runtime
profile requires a file-secret config tree and maps `postgres_password` to the
datasource password without an environment/argument password. Four account-free
configuration tests pass for newline-stripped file contents, container binding,
paths with spaces, and missing mount/file rejection. Initial tests failed before
the profile existed. The profile is separate from the Maven `packaged-ui` build;
default host binding remains loopback. All nine packaged backend tests pass
against disposable PostgreSQL 18. A non-root executable jar authenticates with
the mounted secret, serves byte-identical index/notices and minimal health, and
rejects missing secret directories/files. Missing directories fail import;
missing files fail datasource authentication rather than eager file validation.
Frontend typechecks/test/build/audit pass with zero vulnerabilities; all five
real packaging checks pass, including storage recreation. The runtime suite
passes 46 checks with five opt-in skips. No accounts or inference are used.
The build without `packaged-ui` passes six tests with three interface tests
skipped, still using the packaged file-secret runtime profile for PostgreSQL.
Text/fences and 112 local documentation links pass review.
Docker Hub still reports zero 21.0.13 tags and both required
JDK/JRE tags return not found. Engine image/service, secret mounts, durable
content/assets and full container integration remain open; P02.3 stays unchecked.

- [ ] P02.4 Add build/test/documentation/secret checks without Codex credentials in ordinary CI.
- [ ] P02.5 Provide health/readiness contracts and useful startup failure states.
- [ ] P02.6 Implement root `start.sh` with prerequisite checks, secure configuration initialization, cached builds, health-based startup, supported sign-in, status/log/stop commands, and nonzero failure results.
- [ ] P02.7 Verify idempotent launch, supported Windows shell behavior, interruption, graceful stop, and campaign preservation across restart/image recreation; record QA-28 evidence.

**Exit:** clean clone builds and runs shells/database with documented commands; no private data tracked. **Checks:** frontend build/default test, backend health test, database connectivity, docs links, ignore/secret checks. **Suggested commit:** `Establish the Angular and Spring Boot workspace`.

## 6. P03 — Canonical domain, migrations, and event receipts

**Depends on:** P02. **Areas:** campaign/domain/persistence, migrations.

- [ ] P03.1 Implement adventure-workspace, campaign, actor, content revision, operation, event, reward source and checkpoint foundations with explicit adventure/campaign ownership.
- [ ] P03.2 Add campaign-scoped foreign keys and unique constraints, resource and ownership bounds, and explicit version fields.
- [ ] P03.3 Commit state, events, receipts, and outbox messages together; no network waits in transactions.
- [ ] P03.4 Add deterministic random-draw records and algorithm/version identity.
- [ ] P03.5 Implement startup journal reconciliation, lease/fencing foundations, and baseline backup/restore.

**Exit:** persisted state survives restart and concurrent duplicate attempts cannot double-commit. **Checks:** real PostgreSQL transaction rollback, duplicate keys, cross-campaign references, stale revision, draw reuse, and recovery. **Suggested commit:** `Add canonical campaign persistence and event receipts`.

## 7. P04 — Adventure contract and pack compiler

**Depends on:** P03; uses P00 ruleset decisions. **Areas:** content schemas/parser/compiler, neutral content fixtures.

- [ ] P04.1 Implement versioned manifest, safe YAML/JSON parsing, explicit file categories, and normalized hashes.
- [ ] P04.2 Implement IDs, dependencies, cross-references, creation profiles, item rarity/appearance identities, skills, abilities, and initial world definitions.
- [ ] P04.3 Implement bounded expressions/effects, graph/cycle limits, fixed author/required-entity constraints, version compatibility and conflict diagnostics; generated gap filling cannot override them.
- [ ] P04.4 Add path/archive/type/size protections and immutable registration.
- [ ] P04.5 Create two minimal neutral genre fixtures and prove import without source edits.

**Exit:** valid packs compile; invalid/malicious packs fail with file/field context. No arbitrary executable pack plugins. **Checks:** schema/reference/limit/path corpus, hash conflicts, dependency resolution, two-pack integration. **Suggested commit:** `Add validated versioned adventure packs`.

## 8. P05 — Generic interface foundation

**Depends on:** P02 and stable P04 contracts. **Areas:** frontend feature services/components, theme tokens.

- [ ] P05.1 Build library, character-creation, default visual-novel story, sheet, inventory, journal and settings shells with dominant scene art and attributed speaker passages.
- [ ] P05.2 Bind display names, resources, panels, and terminology to content metadata.
- [ ] P05.3 Implement the default visual-novel and alternative text-focused responsive layouts, local passage advancement, chronicle, speaker portraits and keyboard/focus support.
- [ ] P05.4 Define revision-aware projection state, persistent free-input drafts, editable contextual quick replies and connection/error states; choosing a suggestion must not submit or advance the world.
- [ ] P05.5 Create or license and integrate the complete UI graphic kit, including buttons, bars, icons, frames and required interaction/fallback states; register sources, notices and generated-asset provenance.

**Design clarification (23 October 2026):** The owner selected visual-novel play as the principal experience. [Interface specification](13-api-and-ui.md), [primary mockup](mockups/visual-novel-ui-v1.png), [asset pipeline](23-presentation-assets.md) and [ADR 0011](adr/0011-visual-novel-and-persistent-artwork.md) define attributed dynamic passages, editable quick responses, persistent player input and complete graphics. QA-29–30 are implementation checks; this documentation and concept image do not complete P05. Runtime portrait/item generation and saved visual identities belong to P17 and QA-31.

**Exit:** a generic interface works with either fixture and contains no mandatory protagonist/Rune/Lyra lore. **Checks:** meaningful form/service tests and browser smoke for navigation and reading controls. **Suggested commit:** `Build the configurable RPG interface foundation`.

## 9. P06 — Campaign creation and deterministic consultations

**Depends on:** P03–P05. **Areas:** campaign services/API, knowledge projections, creation UI.

- [ ] P06.1 Create campaigns atomically from immutable pack snapshots and validated character choices.
- [ ] P06.2 Add owner-scoped campaign listing, continue, known state, inventory, quest, and ability projections.
- [ ] P06.3 Show a pack-defined starting situation and pending decision without hardcoded prologue text.
- [ ] P06.4 Keep exact status/inventory consultations local and separate transcript delivery from world state.
- [ ] P06.5 Implement separate adventure specifications/media folders and library switching; prove same-name entity, draft, memory, artwork and restart/restore isolation under QA-33.

**Exit:** two independent campaigns survive restart and read-only use changes no game counters. **Checks:** creation budget/loadout validation, ownership isolation, restart, and no-AI consultations. **Suggested commit:** `Add persistent campaigns and local RPG consultations`.

## 10. P07 — Authoritative command and mutation boundary

**Depends on:** P03, P04, P06. **Areas:** commands, rules, operation API, outbox.

- [ ] P07.1 Implement durable idempotency with payload hashes, expected revision, lease/fencing, admission limits, and operation statuses.
- [ ] P07.2 Implement typed commands and atomic semantic validation; reject generic field patches.
- [ ] P07.3 Add resource, ownership, prerequisite, knowledge, and causal validation.
- [ ] P07.4 Implement local action calculations, durable draws, events, receipts, and pending decisions.
- [ ] P07.5 Add resumable public stream, cancellation race handling, and post-commit presentation jobs.

**Exit:** untrusted proposals cannot mutate state directly; retries and crashes have deterministic outcomes. This gate precedes real AI-driven mutations. **Checks:** QA-03–07, QA-17–20, transaction fault injection. **Suggested commit:** `Enforce transactional command validation and idempotency`.

## 11. P08 — Codex orchestration and grounded narration

**Depends on:** P01 and P07. **Areas:** Codex adapter, AI task port, versioned runtime prompts, UI status.

- [ ] P08.1 Implement the supported Java transport with bounded queues, request/turn correlation, and lifecycle management.
- [ ] P08.2 Implement typed intent and attributed narration/quick-reply contracts with structural/semantic validation and one bounded repair; suggestions preserve player agency and public knowledge scope.
- [ ] P08.3 Scope threads by campaign/role/audience/policy; separate privileged planning from public narration.
- [ ] P08.4 Resolve logical model profiles against the available catalog and set effective settings on every turn.
- [ ] P08.5 Publish only validated final narration; add deterministic receipt fallback and narration-only retry.

**Exit:** an action runs through Codex interpretation when needed, deterministic resolution, save, and grounded narration without raw tool logs. **Checks:** protocol fixtures, invalid output corpus, no-secret projection tests, opt-in neutral real smoke. **Suggested commit:** `Integrate validated narrative orchestration`.

## 12. P09 — Layered memory and renewable AI context

**Depends on:** P08. **Areas:** knowledge, memory, retrieval, AI sessions, resume UI.

- [ ] P09.1 Implement typed facts, beliefs, provenance, scopes, current/last-known location, and unresolved commitments.
- [ ] P09.2 Build stamped context envelopes, mandatory/optional budgets, ID/full-text retrieval, and overflow behavior.
- [ ] P09.3 Implement checkpoints, source-linked summaries, freshness/invalidation, and bounded background jobs.
- [ ] P09.4 Implement count/context/error-triggered renewal and manual idle renewal, preserving transcript and campaign.
- [ ] P09.5 Reconcile restart, committed narration pending, missing dependencies, and stale summaries.

**Exit:** a fresh AI thread resumes the correct pending decision with no invented history or replayed prologue. **Checks:** QA-08, QA-16–19, delayed promises, correction invalidation, input-budget limits. **Suggested commit:** `Add persistent memory and renewable narration sessions`.

## 13. P10 — Inventory, skills, quests, and time

**Depends on:** P07–P09. **Areas:** characters, inventory, quests, world clock, related UI.

- [ ] P10.1 Implement attributes, skills, derived stats, learned abilities, conditions, explicit item rarity tiers, equipment and hand/slot constraints; never derive mechanics from artwork.
- [ ] P10.2 Implement atomic use/transfer/equip/trade commands and source-linked item creation.
- [ ] P10.3 Implement basic objective predicates, quest transitions, and one-time reward-source accounting.
- [ ] P10.4 Implement movement duration, rest, and a bounded world clock without time advancement on queries.
- [ ] P10.5 Integrate reliable state changes into panels and journal.

**Exit:** a coherent noncombat adventure loop uses actual resources, abilities, and objectives. **Checks:** QA-09's shared state preparation, QA-10, quests/rewards, time and rest bounds. **Suggested commit:** `Add reusable character inventory and quest systems`.

## 14. P11 — Deterministic combat foundation

**Depends on:** P10. **Areas:** combat domain, rules, persistence, fixtures.

- [ ] P11.1 Implement encounter lifecycle, frozen per-adventure profiles around the default ruleset, zone graph, objectives and opening-action policy; validate supported customization without changing the scheduler kernel.
- [ ] P11.2 Implement initiative, rounds, activations, AP, movement, legal targeting, and explicit end activation.
- [ ] P11.3 Implement attack/defense/strong-hit/armor/healing calculations with recorded draws.
- [ ] P11.4 Implement predeclared reactions, stacking caps, effect phases, durations, cooldowns, and no-chain limits.
- [ ] P11.5 Implement defeat, escape, surrender/agreement predicates, stalemate policy, and idempotent settlement.
- [ ] P11.6 Persist/recover every combat cursor and partial-round time boundary.

**Exit:** seeded fights produce reproducible legal outcomes and all terminal paths return to a valid narrative state. **Checks:** QA-09, QA-11–13, effect properties and restore mid-round. **Suggested commit:** `Implement deterministic turn-based combat`.

## 15. P12 — Combat interface and AI tactics

**Depends on:** P11 and P08; UI foundation P05. **Areas:** legal-action API, combat panels, NPC task contract.

- [ ] P12.1 Show initiative, AP, zones, known defenses/conditions, costs, targets, and objective without leaking hidden data.
- [ ] P12.2 Allow named actions and clarified free-text intent; preserve explicit player decisions.
- [ ] P12.3 Provide actor-knowledge-scoped legal candidates to Codex for NPC/companion choices.
- [ ] P12.4 Add deterministic tactic fallback, interruption messages, and outcome narration.
- [ ] P12.5 Verify mobile/keyboard play and session renewal inside a fight.

**Exit:** the complete fight is playable through the actual UI, including reconnect and resume. **Checks:** real-browser combat path, QA-07, QA-09, QA-16, tactic-invalid/provider-unavailable fallback. **Suggested commit:** `Add tactical combat UI and constrained opponent tactics`.

## 16. P13 — Dynamic encounter and advancement pipeline

**Depends on:** P11–P12. **Areas:** encounter planning, content variants, progression, rewards UI.

- [ ] P13.1 Implement capability snapshot and deterministic threat/roster/resource constraints.
- [ ] P13.2 Implement template-bounded Codex encounter proposals, one repair, and safe fallback.
- [ ] P13.3 Freeze admitted content; validate reinforcement phases, objectives, escape, and hard counters.
- [ ] P13.4 Implement challenge-source rewards, loot ownership, cumulative thresholds, training, unlocks, and pending purchases.
- [ ] P13.5 Add advancement preview and comparison without automatic player build changes.

**Exit:** two eligible scenes yield valid varied encounters; settlement and progression use actual final state exactly once. **Checks:** QA-14–15, no-ammunition/low-health parties, multi-threshold awards, quest/fight reward deduplication. **Suggested commit:** `Add bounded dynamic encounters and character advancement`.

## 17. P14 — Playable MVP validation

**Depends on:** P00–P13. **Areas:** cross-module tests, sample packs, installation docs.

- [ ] P14.1 Complete one end-to-end neutral adventure slice per genre fixture, including combat and return to exploration.
- [ ] P14.2 Verify reboot, AI renewal, interrupted generation, lost response, backup restore, and no duplicate effects.
- [ ] P14.3 Run the required reduced-scope MVP QA matrix and record fresh evidence.
- [ ] P14.4 Measure baseline latency/context use and list unresolved defects honestly.
- [ ] P14.5 Write reproducible setup and an accurate MVP capability table.

**Exit:** a fresh installation can play, fight, save, resume, and change adventure files without editing engine source. Critical integrity/security failures block the milestone. **Suggested commit:** `Validate the first playable engine milestone`.

## 18. P15 — Complete supported world and optional modules

**Depends on:** P14. **Areas:** social/world/quest modules, guide, creative abilities.

- [ ] P15.1 Add NPC goals/schedules, witnesses, knowledge propagation, companions, and relationship causes/commitments.
- [ ] P15.2 Add richer quest outcomes, faction clocks, bounded time catch-up, reputation, and causally triggered world changes.
- [ ] P15.3 Add guide personas with separate knowledge/session scope and no mutation authority.
- [ ] P15.4 Add creative ability drafting, validation preview, explicit execution, learned recipes, and bounded high-power profiles.
- [ ] P15.5 Prove disabled modules disappear cleanly and enabled modules remain adventure-independent.

**Exit:** all supported gameplay modules have typed data, rules, projections, and meaningful integration coverage. **Checks:** QA-21–22 and full requirement-specific scenarios. **Suggested commit:** `Complete reusable world companion and optional ability modules`.

## 19. P16 — Author and owner operations

**Depends on:** P14; integrates P15 definitions. **Areas:** content migration, export/restore, corrections, diagnostic tools.

- [ ] P16.1 Complete validation reports, author previews, dependency graph diagnostics, and content version inspection.
- [ ] P16.2 Add full-owner versus player-facing exports with integrity manifests and rights metadata.
- [ ] P16.3 Add migration/correction previews, dependency impact, backup, audit, and stale-memory invalidation.
- [ ] P16.4 Restore into explicit adventure/campaign destinations with fresh identities by default, including original artwork, active combat and pending narration; never merge by equal titles.
- [ ] P16.5 Document manual file authoring with complete neutral examples and troubleshooting.

**Exit:** an author can create and evolve content safely without a visual editor; an owner can recover data independently. **Checks:** QA-02, QA-16, QA-23, incompatible upgrade and corrupted archive tests. **Suggested commit:** `Add safe adventure and campaign administration`.

## 20. P17 — Presentation, assets, and accessibility

**Depends on:** P14; uses P15–P16 metadata. **Areas:** asset registry, theme/layout, optional audio, frontend polish.

- [ ] P17.1 Complete scene/map/portrait/item/UI category handling, stable entity artwork, identifying shop/inventory/equipment icons, accessible rarity/frame treatment, hash-based serving, stale-job protection and visible credits.
- [ ] P17.2 Implement optional dynamic music from a reviewed free-license online/local catalog: public-event mood mapping, deliberate activation, stable loops/crossfades, deduplicated cues, volumes, complete current-track/export credits and offline/source-failure fallback under QA-32.
- [ ] P17.3 Finalize the default visual-novel and alternative text-focused layouts, graphic states, configurable tokens and attributed reading/history/quick-reply behavior.
- [ ] P17.4 Run real desktop/narrow-screen/keyboard/zoom/reduced-motion/accessibility checks and fix blocking issues.
- [ ] P17.5 Verify missing art/audio never blocks play and media cannot reveal secrets.
- [ ] P17.6 Implement bounded runtime scene/portrait/item image jobs using verified capability, public appearance inputs, stable identities, durable saved assets, budget limits, deduplication and restore/stale-result checks under QA-31.

**Exit:** the graphical visual-novel UI, persistent identifying artwork and bounded image-job pipeline are verified; unsupported generation degrades accurately without blocking play. Optional dynamic music follows [its specification](24-dynamic-music-and-credits.md); no player or audio integration is delivered by adding that document. TTS is not required. **Checks:** QA-16, QA-19, QA-25, QA-29–32, actual visual review and asset/license inventory. **Suggested commit:** `Complete accessible presentation and optional atmosphere`.

## 21. P18 — Full-engine hardening and balance

**Depends on:** P15–P17. **Areas:** long-run evaluation, concurrency/security, performance, simulations.

- [ ] P18.1 Run the complete QA register and 500-turn continuity evaluation with recorded evidence.
- [ ] P18.2 Run adversarial prompt/content/secret tests and Codex supported-platform isolation checks.
- [ ] P18.3 Calibrate AI RPG Skirmish with simulations and human playtests; update threat/rules defaults with versioned changes.
- [ ] P18.4 Profile queries, outbox, memory retrieval, context budgets, AI calls, and latency; optimize measured bottlenecks.
- [ ] P18.5 Rehearse backup recovery, supported migrations, degraded provider mode, and owned-process shutdown.

**Exit:** all v1 hard gates pass, balance limitations are published, and no integrity/security-critical defect remains open. **Suggested commit:** `Harden the complete engine for long campaigns`.

## 22. P19 — GitHub and v1 release preparation

**Depends on:** P18 and owner publication decisions. **Areas:** public repository, docs, license/credits, release artifacts.

- [ ] P19.1 Verify the publication location for AI RPG Engine and repository slug `ai-rpg-engine`, retain the root MIT license, and review upstream notices and all bundled rights.
- [ ] P19.2 Remove private saves, logs, credentials, generated personal content, machine paths, and misleading status/badges.
- [ ] P19.3 Run fresh-clone `start.sh` installation and sample-play rehearsal without host build toolchains; record supported host/shell/container/runtime versions and persistence/recovery results.
- [ ] P19.4 Finalize README, screenshots from actual software, author guide, troubleshooting, security policy, contribution guide, changelog, and release notes.
- [ ] P19.5 Prepare release candidate, known limitations, checksums, and publication checklist for owner review.
- [ ] P19.6 Publish repository/release only under the owner's explicit publication instruction; verify resulting links and artifacts.

**Exit:** the published material accurately represents a tested engine and includes no private predecessor campaign. **Suggested commit:** `Prepare engine v1 documentation and release assets`.

## 23. Requirement coverage

| Requirements | Owning packages |
| --- | --- |
| FR-001–003 | P03–P06, P08, P16 |
| FR-004–007 | P07–P08, P12 |
| FR-008–010 | P06, P10 |
| FR-011–013 | P03, P09, P18 |
| FR-014–017 | P11–P13, P18 |
| FR-018–021 | P10, P15 |
| FR-022 | P17 |
| FR-023–025 | P01, P03, P07–P09, P16, P18 |
| FR-026 | P16 |
| FR-027 | P05, P12, P17 |
| FR-028 | P02, P14, P19 |
| FR-029 | P00–P02, P14, P16, P19 |
| FR-030 | P01, P04, P16–P18 |
| FR-031 | P04, P10, P13, P17 |
| FR-032 | P03, P05–P06, P09, P16–P17 |
| NFR-001–012 | Cross-cutting gates in P01, P03, P07, P09, P16–P19 |

## 24. Explicitly later initiatives

Semantic retrieval/pgvector after retrieval evidence; visual authoring editor after contract stabilization; voice generation/TTS after provider/rights/privacy decisions; alternative combat schedulers and grid maps; public multiplayer hosting; marketplace/sharing infrastructure; generic AI providers; mobile-native or desktop packaging. Each needs its own scope, design, risks, and roadmap. None is an implicit requirement for the complete initial engine. Bounded runtime image generation is now in v1 under ADR 0011, subject to capability verification and playable fallback.

The owner explicitly requests the integrated text/graphical adventure editor **after core v1**. Its ordered [E01–E06 roadmap](26-adventure-editor-roadmap.md) covers world/history/requirements, mandatory characters, author-fixed versus generatable gaps, fantasy/cyberpunk content, supported combat-profile controls and validate/publish/migrate previews. It is deliberately outside P00–P19 and does not delay the initial engine release. Fixed author constraints and bounded combat customization remain file-contract requirements now; the future editor writes that same contract. QA-34 validates those semantics independently of an editor UI.
