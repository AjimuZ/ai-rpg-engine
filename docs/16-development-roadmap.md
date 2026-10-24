# Development Roadmap

## 1. Status and planning rules

**Current status: P00, P01.1–P01.7, P02.1–P02.7 and P03.1–P03.5 complete.** The packaged application shell, developer protocol proofs and ordinary hosted CI have scoped verification; P03.1–P03.5 add the canonical schema, bounded resources, ownership constraints, storage versions, internal atomic commit boundary, versioned deterministic draw reservations, fenced mutation leases, startup journal reconciliation and a baseline database backup tool, and gameplay remains unimplemented. P01 retains its separate managed-profile live isolation recheck and package-exit limits. The root launcher and its Windows lifecycle checks (P02.7) are verified for Docker Desktop with Git Bash only; Linux/macOS, interactive Ctrl+C, owner sign-in, campaign persistence beyond these foundations (P03.6) and full QA-28 remain pending. P03.6 is the next task. Design specifications and predecessor milestones do not establish completed gameplay.

The orchestrator follows the first executable task in roadmap order, respecting task/package dependencies. A blocked task stays unchecked; independent eligible packages may advance. P01.6 and P01.7 are checked against their explicit capability/serialized-inference outcomes; the managed-profile live isolation recheck remains a separate P01 package exit gate. Later package work must still pass its own prerequisites and exit checks.

**Setup prerequisite (23 October 2026):** the owner-authenticated P01.2 check used ChatGPT device-code login. Users of this container login flow must first enable device-code login in their account's security settings, or obtain managed-workspace administrator permission. P02.6 must surface this prerequisite and the supported instructions when authorization is unavailable; it must not assume a desktop sign-in initializes the isolated runtime. P02.7 verifies the guidance/error path. See [runtime account setup](../tools/runtime_probe/README.md#owner-account-setup-for-the-live-check). This documents the required account setting for this login method; the P02.6 launcher surfaces it in `--login` and `--help` without changing account settings.

Repository preparation recorded on 22 October 2026 includes the private GitHub repository, root MIT license, attribution, ignore rules, text line-ending policy, and contribution guidance. The development workflow requires immediate roadmap and affected-documentation updates after every work item, truthful completion evidence, and neutral commit messages. The owner's checkout also has verified local Git gates; lifecycle-hook activation requires review in the relevant client. These local checks are not distributed in a clone and do not replace P02.4 continuous integration.

After each change, update the applicable item or record partial progress before starting the next item. Keep the affected specifications and changelog consistent, and mark a checkbox only when its stated exit checks actually pass. See [the contribution workflow](../CONTRIBUTING.md#documentation-synchronization).

The owner also requires a fully containerized packaged installation and a root `start.sh` entry point. [The deployment specification](22-container-deployment-and-startup.md) and [ADR 0008](adr/0008-containerized-startup.md) document that requirement. Packaging, runtime IPC, account handling, and startup checks remain unimplemented; no roadmap implementation item is completed by this documentation change.

This is an ordered development roadmap, not a claim of committed delivery dates. The work spans architecture, authoring, AI integration, rules, UI, and operations. Estimate each micro-phase after its prerequisites are verified. A credible calendar requires team availability and results from the Codex compatibility and combat prototypes.

**Research/design revision (23 October 2026):** the owner requests [source-backed competitive analysis](27-competitive-analysis-and-gaps.md), first-release [English/Italian support](29-localization.md), safe [asynchronous narrative planning](28-asynchronous-narrative-planning.md), [optional local speech](30-local-speech-synthesis.md) and portable stories. ADR 0014–0016 record the decisions; [prospective evaluations](31-research-validation-and-evaluations.md) and the [Italian report](32-research-executive-summary-it.md) explain gates and priorities. Eleven new unchecked tasks bring the queue from 106 to 117, preserving all twelve verified completion boxes and the later E01–E06 editor. This is documentation-only work: no new application features, model installation or benchmarks are delivered. Optional deep planning/review and speech must not delay text-only MVP play; their unavailable/disabled qualification outcomes need recorded evidence and tested fallbacks, not a claim that the disabled feature was implemented. No optional capability may bypass its safety/rights/quality gates merely to close a task.

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
- **Refresh policy:** a digest-refresh policy now covers every image. Temurin 21.0.13+9 binaries were published on 22 October 2026, but no official image existed on 23 October 2026, so the original P02.3 plan required a refresh before the first engine image; the development-only revision below supersedes that first-build prerequisite. The narration runtime image was deferred to P01.1, which has since pinned it in the same record.

Evidence: registry digests resolved with `docker buildx imagetools inspect`; Adoptium, Node.js, Docker Desktop, Compose, and Dockerfile reference metadata; and a throwaway Compose probe run from Git Bash in an ignored scratch path containing a space.

- **Probe stack:** it built a minimal Angular 22.2.3 application with `npm ci` (lockfile generated on Windows) and `ng build` on the Node.js image. A Spring Boot 4.1.2 jar was built with the Maven Wrapper on the JDK image (with `unzip`) and run on the JRE image as UID 1000, against PostgreSQL 18.6.
- **Compose features exercised:** a health-gated `depends_on`, `start_interval` health checks, file secrets, an internal data network, a loopback-only port, a read-only engine with all capabilities dropped, and `up --wait`.
- **Results:** host checks confirmed the served Angular index, Java 21.0.12.1, PostgreSQL 18.6 with a Flyway migration, health `UP`, an unpublished database, and no external route from the data network. Startup took 32 s to healthy on the first run and 8 s on the unchanged second run.
- **Findings recorded as rules:** MSYS path rewriting, `docker compose port` printing `:0` with exit status 0, Docker Desktop presenting secret files as `0777`, and an npm install-script warning for P02.2.

The probe stack, image, and scratch files were removed. No volume was created. No WSL user distribution is installed, so the WSL shell candidate is not verified. Launcher behavior was added in P02.6 and verified further in P02.7.

**Progress (23 October 2026):** P00.4 is complete. [ADR 0006](adr/0006-original-combat.md) is accepted: AI RPG Skirmish is the implementable default ruleset. A review of the combat, gameplay, encounter, and worked-scenario documents found every formula and value needed for the first implementation. The worked fight's derived values match the formulas: Health 24, Focus 10, Guard 12, threat capacity 8, level-2 maxima 26 and 11, and 16 seconds of world time. The new [provisional numerical defaults register](11-combat-system.md#17-provisional-numerical-defaults) separates two things:

- **Confirmed structure:** zones, persistent initiative, the AP economy, predeclared reactions, 2d10 margin checks, flat armor, and backend dice. Changing one needs a superseding ADR.
- **Provisional numbers:** the tunable values, each with its defining section and calibration gate (P11, P13, P18.3). They are stored as ruleset-profile data and change only through versioned ruleset revisions.

One previously implicit point is now explicit: the default profile has no maximum level, and a pack may declare one. This was a documentation review; no simulation or playtest ran, so balance remains unclaimed. P00.5 remains open, so the package exit is not yet met.

**Progress (23 October 2026):** P00.5 is complete, and with it the P00 package. [ADR 0010](adr/0010-name-scope-format-licensing.md) is accepted:

- **Name:** the owner-selected name AI RPG Engine is recorded with its derived identifiers. The slug, Compose project, and npm/Angular name are `ai-rpg-engine`; the Maven coordinates are `io.github.ajimuz:ai-rpg-engine-backend`; the Java base package is `io.github.ajimuz.rpgengine`; and the ruleset ID is `skirmish`. No trademark search has been done; P19.1 owns it.
- **v1 boundaries:** the MVP, v1.0, and out-of-scope lines in the requirements are confirmed without change. Unverified hosts and prebuilt image publication are explicitly outside v1.
- **Pack contract 1:** UTF-8 without a byte-order mark; YAML 1.2 core-schema `.yaml` files with no anchors, aliases, merge keys, tags, or duplicate keys; equivalent `.json`; CommonMark `.md` prose with no front matter or rendered HTML; JSON Schema draft 2020-12; directory or `.zip` distribution. The campaign archive format belongs to P16.
- **Licensing plan:** MIT for the engine and the neutral sample packs, third-party packs under their own terms, a permissive allowlist for bundled dependencies (inventory gate and review in P19.1; P02.4 delivered the credential-free CI checks without a license inventory), and locally built images that are not redistributed.

Checks: a documentation review against the requirements, pack, security, and publication specifications, plus a local link and anchor check of the changed documents. No parser exists yet; P04.1, P04.4, and P04.5 verify the format with the corpus tests listed in the ADR. **P00 exit evidence:** ADR 0009 holds the version matrix, ADR 0006 and ADR 0010 settle the remaining pre-scaffold decisions, there is no "Java 17/21" ambiguity or `latest` dependency, and the only deferred items are the narration runtime image (P01.1) and the Temurin 21.0.13 image refresh (P02.3), both with owners.

**Deliverables:** a concrete version matrix and updated ADR status. **Exit:** no ambiguous “Java 17/21,” implicit latest dependencies, or unowned pre-scaffold decisions. **Checks:** dependency compatibility references and a minimal tool availability inventory. **Suggested commit:** `Record supported toolchain and engine baselines`.

## 4. P01 — Prove Codex protocol and capability isolation

**Depends on:** P00. **Areas:** future `backend/.../codex`, isolated verification fixtures, operations docs.

- [x] P01.1 Establish pinned Codex executable/image versions, local stdio probe and packaged private IPC transport, handshake, container account readiness, paginated catalog, and output contract.
- [x] P01.2 Verify thread creation/resume, explicit per-turn model/effort, notification correlation, terminal status, and interruption.
- [x] P01.3 Demonstrate restricted roots, denied canonical writes, unavailable unwanted host tools, and inherited-config isolation.
- [x] P01.4 Demonstrate owned-process shutdown, EOF/timeout handling, stderr bounds, and early-event buffering.
- [x] P01.5 Save neutral protocol fixtures and a supported-version matrix; record service maturity and platform limitations.
- [x] P01.6 Record supported image-generation capability or explicit unavailability, restricted invocation, output formats and account/cost boundaries; gate actual generation without blocking text-only fallback or enabling paid access implicitly.
- [x] P01.7 Qualify interleaved thread dispatch, actual provider concurrency and cancellation/priority limits under the managed profile; retain bounded evidence, or explicitly select serialized inference when simultaneity/headroom is unproved. Do not infer live throughput from scripted overlap.

**Progress (23 October 2026):** P01.7 is complete through the explicit serialized
option: **one active inference turn installation-wide**. Its
[bounded qualification and retained evidence](../tools/runtime_probe/CONCURRENCY.md)
use the real managed 0.164.0 runtime on the internal scripted upstream: five
neutral turns, two overlapping held requests, three targeted interrupted
terminals/closed streams, two completed outputs and per-turn inbox routing with
no discarded correlated events. The separate one-active-turn sequence verifies
client-controlled interrupt/reconcile/dispatch priority. Local cancellation
timings include polling overhead; they prove no live cancellation guarantee.
Live simultaneity, headroom, quota behavior and service priority are unverified,
and no account volume/live request is authorized or used. Scripted overlap gives
no live throughput result. Production admission/queue/fairness/recovery remains
P08/QA-37 work; P08.6 gates every increase. All 116 runtime tests pass with Docker
and no skips (76.574s), including the 12 new qualification checks. The capture
refuses existing fixture containers before start/cleanup. All five
packaging checks pass with recreation enabled (13.265s). Four Compose configs,
two Python syntax/docstring audits, 205 local links/anchors and whitespace checks
pass. Owned containers are stopped, with no volumes deleted. P01 package exit
still needs the quota-blocked live isolation recheck; full QA-24/QA-28 remain
application gates. This task's serialized selection does not waive them.

**Progress (23 October 2026):** P01.6 is complete with an explicit unavailable image
capability for the managed 0.164.0 narration profile, with a
[neutral evidence record and enablement boundaries](../tools/runtime_probe/IMAGE_CAPABILITY.md).
No supported generation formats/invocation or verified account/cost entitlement
are claimed. Discovery exposes the record without blocking the text catalog;
the offline generation gate refuses even bounded opt-in before runtime access.
The full Docker runtime regression passes (104 tests, no skips), including
retained-evidence consistency, unexpected image-result rejection and eight
host-tool refusals. Packaging checks pass (five tests, no skips), as do all four
relevant Compose configurations and source/documentation audits.
No account volume, live image call or paid fallback is used.
P17 retains production job/asset/fallback implementation and QA-31 qualification.

**Progress (23 October 2026):** P01.5 is complete. It retains an account-free runtime 0.164.0
protocol projection (27 request/result exchanges and 19 selected notifications),
a restricted capture command, and a [supported-version/platform matrix](../tools/runtime_probe/COMPATIBILITY.md).
Identifiers and sensitive metadata are normalized or omitted. The documented capture
command successfully refreshed the fixture; all 98 runtime tests (including eight
offline replay/redaction checks, Java compilation/framing and real isolation/lifecycle
checks) pass with Docker enabled and no skips (65.779 seconds). All five packaging
checks pass with recreation enabled (13.839 seconds); four Compose configurations,
Python syntax/docstrings and local documentation links/anchors pass. The matrix
records developer-proof maturity, unverified architectures/hosts, the quota-blocked
live isolation recheck and authenticated packaged restart; P01.6 remains separate.

**Progress (23 October 2026):** P01.4 is complete in [`tools/runtime_probe`](../tools/runtime_probe/README.md#process-lifecycle-p014):

- Owned children record input-close, terminate-hook and kill escalation; unique container names restrict escalation to the launched container. Runtime Compose services use `init: true` to forward termination signals. Backend code still owns only connections/turns, never Docker access.
- EOF/read/write/framing failures close the RPC generation and discard/count its buffered notifications. Request timeouts, malformed replies and event floods leave the request unresolved and require a new generation; a turn-wait deadline preserves the connection for explicit interruption. No failed request is replayed implicitly.
- Stderr is drained independently, classifying at most 65,536 bytes with 512-byte line prefixes and a 16-entry tail. Only fixed levels and exact allowlisted module labels survive; log-shaped private module strings become `unclassified`.
- Early events remain ordered until acknowledgment establishes correlation. Synthetic checks cover sticky EOF, stale-generation rejection, redaction, stderr floods, shutdown backpressure, final kill and an unrelated surviving child. All 90 runtime probe tests pass with Docker enabled and no skips (65.990 seconds), including the 13 process-lifecycle checks, real turn-deadline/reconciliation, isolation, warning-free Java compilation and relay framing checks. All 5 packaging tests pass with recreation enabled; four Compose configurations and documentation links/anchors also pass. The two leftover exploratory probe containers were stopped without deleting volumes.
- Scope: runtime 0.164.0, Windows 11 / Docker Desktop / `linux/amd64`, account-free scripted upstream. Canonical receipts, campaign saves and full QA-24/QA-28 remain future application gates. P01.3's live quota re-check remains open; P01.5 now retains the compatibility records described above.

**Progress (23 October 2026):** P01.3 is complete for the pinned 0.164.0 image ([ADR 0013](adr/0013-narration-runtime-isolation.md)):

- **Profile:** [`docker/codex-runtime`](../docker/codex-runtime/Dockerfile) now installs, root-owned and read-only:
  - a managed configuration layer that overrides user config, project config and launch flags;
  - an engine model catalog: direct tool mode, no shell or patch tool types, text-only input;
  - neutral engine instructions;
  - requirements that empty the extension-server allowlist and pin approval `never`, sandbox `read-only` and search `disabled`;
  - a `runtime-entrypoint` guard. It exits 78 if the runtime home contains `config.toml`, `AGENTS.md` or `AGENTS.override.md`; the runtime always loads the last two.
- **Restricted roots and denied writes:** the container is the boundary: a read-only root, private tmpfs/volumes, all capabilities dropped and no tool able to reach a path.
  - A scripted model calling shell, exec, both patch forms (writing a canonical sentinel), an extension resource and an image read received six "unsupported" answers, and the sentinel never appeared.
  - `danger-full-access` thread starts are rejected. Workspace-write (with or without network) and `on-request`/`untrusted` requests are clamped to read-only/never.
  - Bubblewrap is not relied on. The runtime warns that it is missing from `PATH`, and no command tool exists that would need it.
- **Unavailable tools and inherited configuration:** every upstream request carried `tools: []`. Its only developer texts were the engine instructions and the runtime's read-only permissions statement, with no environment block.
  - A synthetic home config, project config and project `AGENTS.md`, plus flags re-enabling shell, exec, images and live search, were all ignored with the guard bypassed.
  - The guard refused the three inherited home files after a normal-start control.
  - `session.await_turn` now fails a turn containing any non-narration item (`unexpected-turn-item`).
- **Verification:** on Windows 11 / Docker Desktop / `linux/amd64`:
  - `py -B -m unittest discover -s tools/runtime_probe` passed 59 tests (8 Docker tests skipped). With `RUNTIME_PROBE_DOCKER` set, all 59 passed, including the 7 `test_isolation.py` tests and the existing lifecycle and packaging tests through the new entrypoint.
  - `docker compose config --quiet` passed for both probe Compose files, the isolation override and the root `compose.yaml`.
  - `tools/container_packaging` passed 5 tests, including the opt-in Docker recreation test with the rebuilt runtime image.
  - No volumes were deleted.
- **Not verified:** the authorized live re-check on the rebuilt image (`gpt-6-luna` / `low`) started, and found the account present without a guard refusal. The upstream then failed the turn with `usageLimitExceeded`, and paid overage was not enabled. The real backend has therefore not yet accepted the engine instructions and catalog. Re-run `probe.py live` when quota is available, before P01 exit or gameplay integration. Only `linux/amd64` was run.

**Progress (23 October 2026):** P01.2 is complete with verified implementation in [`tools/runtime_probe`](../tools/runtime_probe/README.md):

- **Lifecycle:** the pinned 0.164.0 app-server uses a scripted upstream on an internal Docker network without credentials. Seven actual turns across two threads prove creation, reconnect/resume, explicit upstream model/effort/full-schema comparisons, foreign-event filtering, completed/failed/interrupted outcomes and closure of both interrupted upstream streams. Resume checks ordered terminal histories and sticky settings; the runtime itself stays alive.
- **Regression coverage:** effective-policy validation, unknown-error redaction, missing output, malformed lifecycle events, early notifications, privileged-request denial, queue bounds, buffered-event deadlines and refusal to treat an interrupt acknowledgment as terminal confirmation. The sequential probe discards foreign events; a production concurrent dispatcher remains future adapter work.
- **Verification:** on Windows 11 / Docker Desktop Engine 29.9.2 / `linux/amd64`, `py -B -m unittest discover -s tools/runtime_probe` with `RUNTIME_PROBE_DOCKER` set passed all **51 tests** (including all 30 lifecycle checks). This includes the pinned JDK's warning-free compilation checks and the relay's 40 framing/handshake checks. `docker compose --file tools/runtime_probe/session.compose.yaml config --quiet` passed. Both fixture projects returned successful, empty container listings after the suite; no volumes were deleted. Every Python class/function in the changed files has a docstring. No account-backed call was made; the scripted report retains `inferenceVerified: false`.
- **Live completion (23 October 2026):** after owner-operated supported device-code sign-in in a dedicated UID/GID 10001 account volume, `probe.py --timeout 120 live` passed with explicit `gpt-6-luna` / `low`. The independent validator accepted the correlated completed envelope; a second stdio process resumed the thread with the same completed turn and settings. The [sanitized retained report](../tools/runtime_probe/evidence/live-neutral-0.164.0.json) records `inferenceVerified: true`, 80 events and 8 ignored events, omitting account identifiers and thread/turn IDs. The private full report is retained locally. All 51 protocol tests passed again with Docker enabled and no skips (25.392 seconds). The earlier account-free fixture remains separate evidence. P01.3–P01.5 subsequently added isolation, broader runtime lifecycle and compatibility records as described above; the live isolation re-check remains open, and QA-08 and QA-28 are not claimed.

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
  - At the P01.1 checkpoint, sign-in, live inference and structured output awaited P01.2. The successful P01.2 live neutral/resume report above now supplies that evidence; gameplay readiness remains unverified.
  - The runtime logs that no system `bubblewrap` is on `PATH` and falls back to its bundled copy. P01.3 resolved this by not relying on that sandbox: the profile offers no command tool, and the container is the boundary (ADR 0013).
  - Only `linux/amd64` was run.
  - The fixture account lives in tmpfs; persistent account volumes belong to P02.3.

**Deliverables:** isolated compatibility probe and evidence, later retained as adapter tests when authorized implementation begins. **Exit:** no unreviewed broad write/tool privileges and no dependency on undocumented launch assumptions. Failure blocks real gameplay integration; it does not authorize a generic AI replacement. **Checks:** one opt-in real neutral task plus deterministic protocol fixtures. **Suggested commit:** `Verify narration runtime compatibility and isolation`.

## 5. P02 — Establish repository and quality infrastructure

**Depends on:** P00; can follow P01's scope findings. **Areas:** future root configuration, `frontend/`, `backend/`, `.github/`.

The owner-approved root MIT license, ignore rules, text line-ending policy, and contribution terms were delivered during repository preparation on 22 October 2026 (licensing decision: [ADR 0010](adr/0010-name-scope-format-licensing.md#decision-licensing-plan)). P02.1 now adds shared `.editorconfig` formatting defaults, Python-cache ignores and a sanitized `.env.example` with neutral, non-secret labels and no current consumer; CONTRIBUTING documents their scope. Verified on Windows 11 on 23 October 2026: 33 real Git ignore cases; UTF-8/LF/final-newline and Git binary-attribute checks; unchanged license/notices; neutral example and tracked-content pattern review; and 275 local Markdown links/anchors across 48 documents. `git diff --check` passed. `py -B -m unittest discover -s tools/runtime_probe` ran 51 tests (46 passed, five Docker checks skipped because `RUNTIME_PROBE_DOCKER` was unset). No application build, health or database check ran.

P02.1 covers repository policy only: clean-clone application build, health, database and packaged-startup checks remain gates for P02.2–P02.7 and the package exit below.

P02 applies the image, Compose, launcher-floor, shell, and digest-refresh rules of [ADR 0009](adr/0009-toolchain-baseline.md). Its development-only revision allows P02.3 local image builds on the verified exact official 21.0.12.1 JDK/JRE pins while updated images are unpublished. Refresh both pins when official 21.0.13 (or later 21.0.x) images appear; the newest published Java update remains a release requirement. P02.2 records the npm install-script policy and any test-library helper images.

**Unblocking revision (23 October 2026):** the owner requested resuming development after external prerequisites stopped the queue. Both existing exact Java tags still resolve to the recorded index digests and the official tag API still reports zero 21.0.13 images. ADR 0009 now supersedes the first-build refresh prerequisite for local development only; earlier checkpoint descriptions of that gate below are historical. P02.3 remains unchecked until its real container integration checks pass. P01.2 was independently closed only after owner sign-in and the successful live neutral/resume report above; preparing a volume/login alone was insufficient. No release is authorized by this revision.

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
checks rerun; no test was removed or weakened. Credential-free CI was added later in P02.4; full readiness/failure states P02.5, packaging/launcher/restart QA-28 P02.3/P02.6/P02.7.
The package exit below remains open.

- [x] P02.2 Scaffold Angular and Spring Boot using pinned versions, with no adventure constants.

P02.3 first partial checkpoint (23 October 2026; historical, superseded by the
completion below): root and independent runtime
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

- [x] P02.3 Add pinned engine/runtime images, Compose services, durable volumes, private networks/IPC, secret handling, and the single-origin packaged interface; exclude private development data from builds.

P02.3 completed packaging checkpoint (23 October 2026): the digest-pinned
multi-stage engine image and root Compose serve the neutral Angular shell,
bundles/notices and minimal health on loopback. The engine consumes a mounted
database secret as UID 10001, owns durable content/assets storage and shares
read-only private socket IPC, without account/workspace mounts or inference
network access. The runtime receives no database secret/storage/network access.
The rendered engine assertion first failed while the service was absent.
All five real packaging checks pass (43.343 s, no skips), including filtered
BuildKit exports, production HTTP/404s, secret-tree failure, WebSocket upgrade
from engine to runtime, authenticated database access, and neutral storage
preservation across recreation of all three services. The JDK verification
target runs all nine backend tests offline against a separate PostgreSQL 18
fixture database. Frontend typechecks, unit test and production build run in the
Node stage; npm audit reports zero vulnerabilities. All 116 runtime regressions
pass with Docker enabled (78.482 s, no skips). Old infrastructure fixture volumes
remain untouched; new markers use a separate schema in a dedicated shell fixture
project so Flyway's unknown-schema guard is preserved. Services stop and volumes
remain. This closes P02.3 packaging, not launcher/account readiness, application
RPC integration, pack/asset pipelines, canonical campaign recovery or full
QA-28 (P02.5–P02.7/P08 and later acceptance). Native Linux secret UID permissions
and the release Java refresh remain explicit qualification gates.

Final review also passes the two context tests after expanding the runtime
allowlist fixture to all six reviewed inputs, Dockerfile build checks with no
warnings, Compose rendering, 191 local documentation links/fences, and all 19
Python module/class/function docstrings in the changed check modules.

The partial checkpoints below are historical; their pending engine/build-gate
statements are superseded by this checkpoint and ADR 0009's development revision.

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

- [x] P02.4 Add build/test/documentation/secret checks without Codex credentials in ordinary CI.
  **Partial (23 October 2026):** `.github/workflows/ci.yml` defines four jobs with read-only
  contents permission, no secrets, no write-token triggers, SHA-pinned actions and a
  job-local PostgreSQL 18 whose password is generated and masked inside the job:
  repository checks, frontend `npm ci`/typecheck/test/build/audit, backend `mvnw verify`
  and container build-context/Compose checks. The stdlib-only
  [repository checks](../tools/repository_checks/README.md) (secret and private-path scan
  with `.gitignore` coverage, Markdown link/anchor/fence check, workflow policy, image pin
  check) have 22 unit tests including negative cases. Verified locally: all four checks
  report no findings on this repository; repository_checks 22 tests, runtime_probe 116
  (18 opt-in skipped), container_packaging 5 (1 opt-in skipped) pass; `docker build --check`
  and `docker compose --env-file .env.example config --quiet` are clean; frontend typecheck,
  test and build pass and `npm audit` reports 0 vulnerabilities.
  **Backend job reproduced (23 October 2026, later session):** the host JDK 27 is rejected by
  the pinned policy, so the job's steps were replayed in disposable containers on a private
  Docker network: `eclipse-temurin:21-jdk` (plus `unzip`, which hosted Ubuntu runners
  already include) running `./mvnw -B -ntp verify` against the digest-pinned
  `postgres:18.6-trixie` with `SPRING_DATASOURCE_*` exported exactly as the workflow does.
  The first replay exposed a real defect that would have failed the hosted job: the exported
  password outranked the mounted secret file in `PackagedSecretsTest`. The test now loads
  Boot configuration through an environment without process variables; the replay then
  reports BUILD SUCCESS (9 tests, 3 packaged-interface tests skipped as designed). The four
  pinned action SHAs were re-resolved from their upstream release tags and match.
  **Hosted verification (confirmed 24 October 2026):** [CI run 40028086153](https://github.com/AjimuZ/ai-rpg-engine/actions/runs/40028086153)
  ran from the `push` of `09a6311d9557be01f2fee9abeb119206f0b02e34` on `main` and completed successfully
  on 23 October 2026 at 21:37:59 UTC (23:37:59 Europe/Rome). GitHub's run and jobs
  APIs confirm all four jobs completed with `success`: repository hygiene/documentation/
  workflow policy (including account-free runtime checks), Angular typecheck/test/build/
  audit, Spring Boot build/tests against disposable PostgreSQL, and container context/
  Compose boundaries. No job was skipped or merely queued. This supplies the missing
  P02.4 exit evidence; the former owner-push/manual-confirmation blocker is resolved.
  No workflow, application code or credentials changed during this verification.
  Dependency license inventory remains P19.1; automated pin refresh, gameplay gates,
  launcher/account readiness and full QA-28 are not established by this CI run.
- [x] P02.5 Provide health/readiness contracts and useful startup failure states.
  Added `liveness`, `readiness` (database + Flyway schema) and `gameplay` health groups
  (`backend/.../health/`), where gameplay reports `UNKNOWN`/503 until a runtime adapter exists, and a
  `StartupFailureAnalyzer` for unreachable database, rejected credentials and schema mismatch.
  Verified in JDK 21 against disposable PostgreSQL 18.6: `./mvnw verify` BUILD SUCCESS, 13 tests (3
  packaged-UI tests skipped by design); live unreachable-database startup prints the description.
  Not run: the Docker packaging suite after relaxing its exact-body health assertion (syntax-checked
  only), a live wrong-password run, and hosted CI for this commit. Gameplay readiness itself stays P01/P14.
- [x] P02.6 Implement root `start.sh` with prerequisite checks, secure configuration initialization, cached builds, health-based startup, supported sign-in, status/log/stop commands, and nonzero failure results.
  Added the executable root `start.sh` (Bash 4.4 guard, mode 100755, LF): `./start.sh`, `--check`
  (read-only), `--login`, `--status`, `--logs`, `--stop`, `--help`; exit 0 ok, 1 failure, 2 usage, 3 only
  from `--status` (storage ready, gameplay not). Prerequisites cover host utilities, Docker CLI,
  daemon, Linux containers, Engine/Compose/Buildx floors, application-port availability and a
  stable `rpg-engine-<12 hex>` installation identity. A 64-hex database password is created once
  from `/dev/urandom` (never regenerated, never printed; refused when the file is empty or when
  the file is missing while the installation's database volume exists). Start builds with the layer
  cache, then `up --wait` and polls `readiness` through `docker compose exec` (no host curl);
  gameplay `UNKNOWN`/503 is reported honestly until P14. Logs are redacted; `--stop` uses
  `down` without volume removal.
  Verified on Windows 11, Docker Desktop 4.96.3 (Engine 29.9.2, Compose v5.6.1), Git Bash 5.3.15
  with real Docker: first start, repeat start, status, logs, stop with volumes kept, restart reusing
  the database and password, busy port, invalid port, unreachable daemon, missing secret with an
  existing volume, and `--login` reaching the device-code prompt (interrupt restores the runtime
  and removes the one-off container). `python -B -m unittest discover -s tools/container_packaging`
  passes (21 tests, 1 skipped by design: the opt-in `PACKAGING_DOCKER=1` recreation check) and
  `python -B tools/repository_checks/checks.py all` reports 0 findings.
  Not verified: Linux and macOS hosts; native Linux secret-file ownership/permissions (NTFS ignores
  the chmod modes); a completed owner sign-in (human-only, never automated); gameplay readiness
  (P14); fresh-image build duration; hosted CI for this commit. Stop timing, interruption exit
  codes and image-recreation preservation were verified later in P02.7.
- [x] P02.7 Verify idempotent launch, supported Windows shell behavior, interruption, graceful stop, and campaign preservation across restart/image recreation; record QA-28 evidence.
  Added the opt-in `tools/container_packaging/test_lifecycle.py` (`PACKAGING_LIFECYCLE=1`, needs a
  Docker daemon; it uses a disposable copy of the tracked files on port 18480 and never removes
  volumes) and retained its sanitized report
  [`evidence/launcher-lifecycle-windows.json`](../tools/container_packaging/evidence/launcher-lifecycle-windows.json).
  Verified on Windows 11, Docker Desktop 4.96.3 (Engine 29.9.2, Compose v5.6.1), Git Bash 5.3.15,
  real `./start.sh`, warm layer cache (the copy was reused, so no cold build was timed):
  - Idempotent launch: a second `./start.sh` kept every container ID and the database-password
    hash; `--status` exited 3 with `storage: ready` (gameplay readiness is P14). Start took 7-14 s.
  - Graceful stop: `--stop` finished in 3.5 s (limit 40 s). Exit codes from Docker die events:
    database 0, runtime-support service 0, engine 143 (SIGTERM handled by the JVM shutdown hook;
    137 would mean SIGKILL). No containers remained and the volume set was unchanged.
  - Restart: database container recreated; a neutral database row, a content-volume file and the
    password were intact.
  - Interruption: SIGINT sent to the launcher while it was building images and again while
    starting services; both exited 130 with the "nothing was reset, rerun" message and left no
    containers; the next run completed.
  - Image recreation: a frontend source change rebuilt the engine image (new image ID); the markers,
    password and volumes survived.
  Campaign data is represented by those neutral markers: no campaign schema exists before P03, so
  campaign and combat preservation (QA-28) must be rerun against real data in P03 and later.
  Limits: the signal came from a Git Bash job-control shell, and Bash ran the trap after the
  in-flight Docker command finished, so a human Ctrl+C on an interactive terminal and prompt
  cancellation of a running build were not verified; Linux and macOS hosts, native Linux secret
  ownership, WSL Bash, a completed owner sign-in and gameplay readiness are unverified; the
  device-code guidance is confirmed only in `--help` text and by reading the `--login` failure path,
  not by a live rejected sign-in; the engine does not drain in-flight commands (application-level
  draining remains P03/P16, `server.shutdown=graceful` is not configured); a cold build was not
  timed; hosted CI for the P02.5-P02.7 commits is unconfirmed. The default suites pass
  (`tools/container_packaging` 22 tests with 2 skipped by design, `tools/repository_checks` 22
  tests, repository checks 0 findings).

**Exit:** clean clone builds and runs shells/database with documented commands; no private data tracked. **Checks:** frontend build/default test, backend health test, database connectivity, docs links, ignore/secret checks. **Suggested commit:** `Establish the Angular and Spring Boot workspace`.

## 6. P03 — Canonical domain, migrations, and event receipts

**Depends on:** P02. **Areas:** campaign/domain/persistence, migrations.

- [x] P03.1 Implement adventure-workspace, campaign, actor, content revision, operation, event, reward source and checkpoint foundations with explicit adventure/campaign ownership; keep UI/narrative locale preferences and presentation generations distinct from canonical counters.
  Added Spring Data JPA and the Flyway migration
  `backend/src/main/resources/db/migration/V1__canonical_foundations.sql` (Hibernate validates it
  with `ddl-auto=validate`; `open-in-view=false`). It creates ten tables: `identity_owner_profile`
  (UI locale), `adventure_workspace`, `content_revision`, `campaign`,
  `presentation_campaign_settings`, `actor`, `operation_journal`, `campaign_event`,
  `reward_settlement` and `campaign_checkpoint`. Domain classes live in the `adventure`,
  `campaign`, `characters`, `commands`, `content`, `identity`, `memory`, `persistence`,
  `presentation` and `progression` packages. Decisions:
  - Ownership is explicit: a content revision belongs to one adventure, a campaign pins a revision
    of its own adventure through the composite key `(adventure_id, content_revision_id)`, and
    actors, operations, events, reward settlements and checkpoints carry `campaign_id` with
    composite `(campaign_id, id)` foreign keys, so PostgreSQL rejects cross-adventure and
    cross-campaign references. All foreign keys are `ON DELETE RESTRICT`.
  - The canonical counters (state revision, game turn, world time, event sequence) live only on
    the campaign row. Narrative locale, locale revision, glossary revision and media configuration
    generation live in `presentation_campaign_settings`; the UI locale lives on the owner profile.
    `SupportedLocale` holds the en/it allowlist with a visible English fallback.
  - The operation journal implements the docs/07 lifecycle table; `COMMITTED` is reachable only
    through `recordCommit`, and the operation version is the row's own counter. Idempotency keys
    are unique per campaign; reward settlements are unique per `(campaign, source, recipient)`.
  - A `reject_mutation()` trigger makes events, content revisions, checkpoints and reward
    settlements immutable; events also cannot be deleted.
  Verification (24 October 2026): `./mvnw -B -ntp verify` ran in the Java 21 build image against a
  disposable tmpfs `postgres:18.6` container: BUILD SUCCESS, 34 tests, 0 failures, 3 skipped (the
  pre-existing opt-in packaged-interface tests). The 21 new tests cover the persisted graph round
  trip, cross-adventure and cross-campaign foreign keys, idempotency scoping, per-campaign
  uniqueness, immutability triggers, database checks, unchanged campaign rows after presentation
  changes, the lifecycle table, locale negotiation and value validation. `tools/repository_checks`
  (22 tests) and `tools/container_packaging` (22 tests, 2 skipped by design) pass; repository
  checks report 0 findings. Limits: no service, API or UI uses these tables yet; optimistic
  `@Version` fields, resource/ownership bounds and systematic cross-campaign tests remain P03.2,
  atomic commit and outbox P03.3, random draws P03.4, leases/restore/journal reconciliation P03.5,
  and narration jobs/planning snapshots P03.6. Hosted CI for this commit is unconfirmed.
- [x] P03.2 Add campaign-scoped foreign keys and unique constraints, resource and ownership bounds, and explicit version fields.
  Completed 24 October 2026: V2 adds bounded actor resources with a composite actor/campaign
  foreign key and unique resource key; seven mutable tables use independent JPA row
  versions. PostgreSQL freezes scope/provenance, prevents counter rewinds, bounds JSONB
  bytes and requires unique, complete write-once commit outcomes. Tests exercise raw SQL
  and repository isolation, stale and concurrent writers with losing-transaction rollback,
  concurrent idempotency/reward duplicates, inclusive resource/payload boundaries and a
  populated V1-to-V2 upgrade preserving domain counters. Full backend `verify` on pinned
  JDK 21/PostgreSQL 18.6 passes 43 tests (three packaged-interface tests require the
  separate UI profile and are skipped). Repository-checker tests pass 22/22; default
  packaging checks pass 20/20 with two opt-in lifecycle/recreation checks skipped;
  `tools/repository_checks/checks.py all` reports no findings. No dependency versions
  change. Atomic state/event/receipt/outbox commit, expected canonical revision, random
  draws, leases and recovery remain P03.3–P03.6; future API authorization and full
  QA-20/QA-33 acceptance remain unverified. Hosted CI for this commit is unconfirmed.
- [x] P03.3 Commit state, events, receipts, and outbox messages together; no network waits in transactions.
  Add a bounded internal resolved-commit service, scoped campaign/journal locking,
  revision/resource checks, exact stored receipt replay and reference-only immutable
  outbox migration. Reject ambient transactions and executable inputs; persist all
  canonical effects and public-state/narration intents in one short database transaction.
  On Windows 11/Docker Desktop with pinned JDK 21/PostgreSQL 18.6, full backend
  `verify` passes 72 tests (three packaged-interface tests skip outside their profile).
  The 27 commit integration cases include every write boundary, deferred commit failure,
  simultaneous duplicates/competing operations, intervening resource writers and scope
  guards; populated V1-to-V2-to-V3 upgrade preserves existing outcomes without backfill.
  Repository-checker tests pass 22/22; default packaging checks pass 20/20 with two
  opt-in checks skipped; repository policy checks report no findings. Dependency pins
  are unchanged. No API, dispatcher, lease/recovery or full QA-20/QA-33 claim; remaining
  package exits belong to P03.4–P03.6 and later gameplay work. Hosted CI is unconfirmed.
- [x] P03.4 Add deterministic random-draw records and algorithm/version identity.
  Completed 24 October 2026: V4 adds private immutable campaign seeds and scoped
  operation/ordinal reservations with explicit HMAC-SHA-256 v1 identity. Pin binary
  framing and unbiased rejection sampling; reserve in a separate bounded database
  transaction, replay matching draws before freshness checks, reject bound conflicts
  and new stale/terminal allocations. Canonical receipts read ordered stored draws
  without seed disclosure; reservation leaves world and journal counters untouched.
  Independent vectors (including rejection), 128-draw receipt capacity, concurrent
  duplicates, failed reservation/canonical commit, fresh-service reuse, cancellation,
  scope/constraint checks and populated V1-to-V4 upgrade pass. Full backend `verify`
  on Windows 11/Docker Desktop/pinned JDK 21/PostgreSQL 18.6 passes 81 tests with
  three packaged-interface profile skips and builds the jar. Repository-checker tests
  pass 22/22; default packaging checks pass 20/20 with two opt-in skips; repository
  policy checks report no findings. Dependency pins are unchanged. Equivalent-new-
  attempt policy, gameplay authorization, leases/recovery and full package acceptance
  remain later work; no hosted-CI or backup/restore result is claimed.
- [x] P03.5 Implement startup journal reconciliation, lease/fencing foundations, and baseline backup/restore.
  Completed 24 October 2026. V5 adds per-campaign mutation leases that use PostgreSQL
  time, and a persistent fence that grows on every grant, release and expiry
  retirement. Draws and commits require the current token after immutable replay, and
  a commit releases the lease atomically. Startup reconciliation runs before the web
  server starts, in pages of 100 with one transaction per operation. It fails orphaned
  precommit work, marks interrupted narration pending and aborts startup on error.
  `tools/database_backup` writes a pg_dump archive and manifest, restores only into a
  new database and retires saved leases. Full backend `verify` (Windows 11, Docker
  Desktop, pinned JDK 21, PostgreSQL 18.6) passes 95 tests with three packaged-profile
  skips and builds the jar. The backup tool passes 9 offline tests (one symlink skip).
  A manual rehearsal passed: backup, directory reuse refused, tamper rejected,
  restore, data equality, lease retirement (fence 1→2), and both interrupted
  operations moved to `FAILED_BEFORE_COMMIT` by the jar's startup. Repository-checker
  tests pass 22/22, packaging checks pass 20 with two opt-in skips, and policy checks
  report no findings. Limits: database-only backup, single-installation recovery
  assumption, no worker renewal/cancellation or job accounting (P03.6/P07), and no
  hosted-CI or full QA-17/QA-23 claim.
- [x] P03.6 Add durable AI jobs, immutable planning snapshots, versioned plan/dependency records, query epochs and recovery generations; persist locale preferences separately from world revisions and test atomic outbox/admission foundations.
  Completed 24 October 2026: V6 adds eight scoped preparation tables and campaign
  recovery generations. Internal services persist deduplicated jobs, bounded attempts,
  immutable snapshot inputs, versioned artifacts, full dependency manifests and
  append-only admission/disposition records. Critical enqueue, query-epoch bumps and
  plan invalidation share the canonical commit/outbox transaction. Snapshot capture
  checks assembler revision/generation/presentation tokens; admission rechecks query
  epochs and fences under campaign locks, without changing world counters or draws.
  Public locale staleness is rejected; locale preferences remain separately persisted.
  Expired workers require explicit stop confirmation before retry; startup gates new
  work, and restore advances generations, fences leases and invalidates usable plans.
  Full backend `verify` on pinned JDK 21/PostgreSQL 18.6 passes 113 tests with three
  packaged-interface profile skips (116 total), including 17 preparation tests and
  populated V1→V6 upgrade, and builds the jar. A populated V6 backup/restore plus jar
  startup rehearsal preserves immutable bytes and world counters while retiring old
  jobs. Backup tests pass 8 with one Windows symlink skip; repository tests pass 22;
  default packaging tests pass 20 with two opt-in skips. Repository secret, document,
  workflow and image policy checks report no findings. Full semantic dependency
  validation fails closed until P08.7; scheduling, fairness/quota enforcement, runtime
  cancellation, gameplay consumption and full QA-36/37 remain later work. No hosted
  CI or narrative-quality result is claimed.

**Exit:** persisted state survives restart and concurrent duplicate attempts cannot double-commit. **Checks:** real PostgreSQL transaction rollback, duplicate keys, cross-campaign references, stale revision, draw reuse, and recovery. **Suggested commit:** `Add canonical campaign persistence and event receipts`.

## 7. P04 — Adventure contract and pack compiler

**Depends on:** P03; uses P00 ruleset decisions. **Areas:** content schemas/parser/compiler, neutral content fixtures.

- [x] P04.1 Implement versioned manifest, safe YAML/JSON parsing, explicit file categories, and normalized hashes.
  - Session 1: implement a data-only in-memory parsing boundary, closed contract-1
    manifest and definition-envelope schemas, explicit categories and sorted raw-byte
    SHA-256 framing. The 63-case parser/schema/hash corpus covers malicious syntax,
    resource limits, final-line-terminator regressions and an independent digest vector.
    Full pinned JDK 21/PostgreSQL 18.6 backend `verify` passes 176 tests with three
    packaging-profile skips (179 total) and builds the jar with both schemas.
    Repository tests pass 22 and policy checks report zero findings.
    Definition categories validate a preliminary envelope; semantic data, policy/module
    compatibility, filesystem/archive/media protection and registration remain
    P04.2–P04.4. This stage does not admit packs or close the overall P04 exit.
- [x] P04.2 Implement IDs, dependencies, cross-references, creation profiles, item rarity/appearance identities, skills, abilities, and initial world definitions.
  - Session 1: add `PackCompiler`, a pure semantic stage over `ParsedPack` that returns a
    `CompiledPack`. It enforces category/kind agreement, unique IDs, rule-defined
    attribute/resource/rarity vocabularies, pinned dependencies resolved through an
    injected local `DependencyCatalog` (`packId#id` cross-pack references; no fetching),
    typed cross-references, creation budgets and starting stacks, unique item appearance
    identities, skill ranks, ability prerequisites/costs/targets and shallow typed effects,
    and the manifest entrypoint. Unknown fields, unknown rule types and kinds without a
    schema (quest, encounter, anchor, progression) fail closed with located diagnostics.
    The 8-test compiler suite covers a valid pack and each failure code. Full pinned
    JDK 21/PostgreSQL 18.6 backend `verify` passes 184 tests with three
    packaging-profile skips; repository tests and policy checks pass.
    Limitations: expression and effect depth/graph limits, cycles, version-range
    compatibility and quest/encounter/progression semantics are P04.3; archive/path
    protections and registration are P04.4. Nothing is admitted or playable yet.
- [x] P04.3 Implement bounded expressions/effects, graph/cycle limits, fixed author/required-entity constraints, version compatibility and conflict diagnostics; generated gap filling cannot override them.
  - Session 1: add a closed, typed integer/boolean expression language (`Expressions`)
    with actor and world scopes, interval analysis that proves divisors non-zero and
    results in range, and limits of depth 12, 200 nodes, 40 dice and |value| ≤ 10⁹
    (`LIMIT`, `COST`, `OVERFLOW`, `DIVISION_BY_ZERO`, `RANGE`). Effects gain timing,
    targets, resource/condition/item/move effects and guarded `conditional` effects
    (nesting depth 4, 20 nodes per list tree). Ability prerequisites, condition ticks and
    unconditional quest requirements are cycle-checked graphs (`CYCLE` names the path).
    Quests now compile: objectives, outcomes with settlements and world predicates.
    Manifests are checked against an `EngineContract` using a closed SemVer range
    grammar (`VERSION_SYNTAX`, `INCOMPATIBLE_ENGINE`, `INCOMPATIBLE_RULESET`,
    `UNSUPPORTED_MODULE`); `HASH_CONFLICT` rejects different bytes under an installed
    pack or dependency version. The generation policy file compiles into
    `GenerationPolicy` (disabled/catalog-only/bounded categories, budgets, tags,
    templates, ranges, required entities, fixed authored fields, excluded tags;
    contradictions are `CONSTRAINT_CONFLICT`). The pure `GenerationGate` rejects
    generated entities or gap fills that reuse authored IDs, fill fixed or authored
    fields, exceed budgets or ranges, carry excluded tags or retire required entities.
    Unreachable locations are located `UNREACHABLE_LOCATION` warnings. ADR 0017 records
    the choices. The compiler suite grows to 19 tests; full pinned JDK 21/PostgreSQL 18.6
    backend `verify` passes 198 tests with three packaging-profile skips; repository
    tests and policy checks pass.
    Limitations: encounter, anchor and progression kinds remain `UNSUPPORTED_KIND`;
    ruleset 1.0.0 is a provisional contract until P07; the gate is not yet called by any
    runtime path (P10/P12 must use it); reachability ignores locks and move effects.
    Archive/path protections and registration are P04.4.
- [x] P04.4 Add path/archive/type/size protections and immutable registration.
  - Complete protected in-memory directory/ZIP staging, portable path/device/case
    guards, actual byte/inflation budgets, consistent local/central ZIP records and
    descriptors, CRC verification and media signature/dimension checks. Inventories
    are privately constructed and defensively copied. Registry preview is read-only;
    registration stores original files and reports atomically, resolves only sealed
    adventure-local dependencies and enforces idempotency/version conflicts.
    V7 seals immutable file sets under serialized READ COMMITTED writes, recomputes
    stored sizes/hashes and rejects late insertion; reload verifies the pack digest.
    All 23 focused tests pass, including concurrent retries, direct insertion versus
    sealing, legacy-unsealed rejection, tamper detection and failed-seal rollback.
    Pinned JDK 21/PostgreSQL 18.6 full `verify` passes 218 tests with three separate
    packaging-profile skips (221 total), including populated V1→V7 preservation,
    and builds the jar. Repository tests pass 22; docs/secrets/workflows/images have
    zero findings. ADR 0018 records PostgreSQL byte storage and provisional ruleset
    descriptor hashing. Directory sources must be owner-controlled/quiescent;
    full media decoding/rights verification and request-level concurrency limits
    remain gates for future serving/import APIs. P04.5/P04.6 and overall playable
    genre/localization acceptance remain open.
    Final review adds direct payload-CRC, trailing-deflate and unsigned-descriptor
    cases to the staging corpus; their focused rerun passes all 12 tests.
- [x] P04.5 Create two minimal neutral genre fixtures and prove import without source edits.
  - Add standalone English Mosslight Trail (fantasy exploration) and Silent Relay
    (science-fiction salvage) inventories with distinct world graphs, vocabulary,
    skills/resources, creation budgets, tools, abilities, quests and original
    MIT-licensed schematic PNGs. No production source or dependency changes.
    Two PostgreSQL integration cases pass: read-only preview, unchanged directory/ZIP
    import, exact sealed byte/hash/report preservation, idempotency, stored recompile,
    independent adventure imports, shared local-ID separation and located broken
    reference rejection with no partial writes. The test first failed on absent
    fixture directories, then passed with the authored inventories.
    Full pinned JDK 21/PostgreSQL 18.6 `verify` passes 220 tests with three separate
    packaging-profile skips (223 total) and builds the jar. Repository checker tests
    pass 22; docs/secrets/workflows/images have zero findings. Optional modules stay
    disabled because no runtime exists; distinct guide/creative behavior, playable
    QA-01, full QA-33 isolation and reviewed EN/IT resources remain later gates.
- [x] P04.6 Validate optional ID-based English/Italian text overlays, glossary/quote policies, locale coverage and source-language fallback; localized fields cannot alter mechanics or visibility, and both neutral genre fixtures include reviewed EN/IT resources.
  - Add closed source-owned resources and separate immutable catalogs with stable-ID
    text leaves, proper-name/quote policies, placeholder parity and exact EN/IT
    fallback. Mechanics, visibility and exports remain source-authoritative.
    Both fixtures include reviewed EN/IT resources for all 24/26 catalog leaves;
    each protected English motto remains the one Italian fallback. Source-only
    prose is excluded from coverage; UI, narration and human playtests stay later.
    All 45 focused checks pass, including hostile authority changes, hash conflicts,
    sealed reload and populated V1→V8 preservation. Report version 2 uses stable
    ordered coverage; V8 retains old seals without rewriting bytes or identities.
    Full pinned JDK 21/PostgreSQL 18.6 `verify` passes 262 tests with three separate
    packaging-profile skips (265 total) and builds the jar with trusted schemas/V8
    and no test inventories. Backup tests pass eight with one Windows symlink skip;
    a real V8 dump/restore preserves fixture bytes, hashes, exports and report
    versions 1/2. All 22 repository tests pass; docs/secrets/workflows/images report
    zero findings. The strict backup allowlist now includes all reviewed V8 tables.



**Exit:** valid packs compile; invalid/malicious packs fail with file/field context. No arbitrary executable pack plugins. **Checks:** schema/reference/limit/path corpus, hash conflicts, dependency resolution, two-pack integration. **Suggested commit:** `Add validated versioned adventure packs`.

## 8. P05 — Generic interface foundation

**Depends on:** P02 and stable P04 contracts. **Areas:** frontend feature services/components, theme tokens.

- [ ] P05.1 Build library, character-creation, default visual-novel story, sheet, inventory, journal and settings shells with dominant scene art and attributed speaker passages.
- [ ] P05.2 Bind display names, resources, panels, and terminology to content metadata.
- [ ] P05.3 Implement the default visual-novel and alternative text-focused responsive layouts, local passage advancement, chronicle, speaker portraits and keyboard/focus support.
- [ ] P05.4 Define revision-aware projection state, persistent free-input drafts, editable contextual quick replies and connection/error states; choosing a suggestion must not submit or advance the world.
- [ ] P05.5 Create or license and integrate the complete UI graphic kit, including buttons, bars, icons, frames and required interaction/fallback states; register sources, notices and generated-asset provenance.
- [ ] P05.6 Deliver reviewed English-default/Italian UI variants, localized receipts/errors/accessibility, persisted language selection and draft/cursor-safe reload; extend single-origin packaging/resource checks under QA-35.
- [ ] P05.7 Define and render bounded typed public presentation events for scene, pose/expression and optional line audio hooks; reject executable/raw-URL directives, deduplicate delivery and preserve text-only/keyboard/reduced-motion behavior under QA-40.

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
- [ ] P07.6 Implement atomic plan admission with dependency/query-epoch rechecks, scope/content/author validation, fencing and idempotency; artifacts never directly change world facts/time/rewards, and activation uses ordinary commands under QA-36.

**Exit:** untrusted proposals cannot mutate state directly; retries and crashes have deterministic outcomes. This gate precedes real AI-driven mutations. **Checks:** QA-03–07, QA-17–20, transaction fault injection. **Suggested commit:** `Enforce transactional command validation and idempotency`.

## 11. P08 — Codex orchestration and grounded narration

**Depends on:** P01 and P07. **Areas:** Codex adapter, AI task port, versioned runtime prompts, UI status.

- [ ] P08.1 Implement the supported Java transport with bounded queues, request/turn correlation, and lifecycle management.
- [ ] P08.2 Implement typed intent and attributed narration/quick-reply contracts with structural/semantic validation and one bounded repair; generate directly in selected English/Italian, preserve glossary/locale generations, player agency and public knowledge scope.
- [ ] P08.3 Scope threads by campaign/role/audience/policy; separate privileged planning from public narration.
- [ ] P08.4 Resolve logical model profiles against the available catalog and set effective settings on every turn.
- [ ] P08.5 Publish only validated final narration; add deterministic receipt fallback and narration-only retry.
- [ ] P08.6 Implement one durable fair priority queue with isolated logical role/audience threads, interactive reservations, bounded quota/backoff/cancel/restart handling and conservative single-active-turn default; higher concurrency requires P01.7 evidence and QA-37.
- [ ] P08.7 Add the versioned bounded quest-plan contract and selective dependency validation/replanning with a playable no-plan baseline; prove contract/admission/fallback with deterministic fixtures. A live investigation comparison may be deferred/unavailable without blocking MVP; no forced player choice or waiting for a whole arc.

**Exit:** an action runs through Codex interpretation when needed, deterministic resolution, save, and grounded narration without raw tool logs. **Checks:** protocol fixtures, invalid output corpus, no-secret projection tests, opt-in neutral real smoke. **Suggested commit:** `Integrate validated narrative orchestration`.

## 12. P09 — Layered memory and renewable AI context

**Depends on:** P08. **Areas:** knowledge, memory, retrieval, AI sessions, resume UI.

- [ ] P09.1 Implement typed facts, beliefs, provenance, scopes, current/last-known location, and unresolved commitments.
- [ ] P09.2 Build stamped context envelopes, mandatory/optional budgets, ID/full-text retrieval and overflow behavior; distinguish private planning assumptions from admitted facts and public narration, with language/glossary binding.
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

- [ ] P14.1 Complete one end-to-end neutral adventure slice per genre fixture in English and Italian, including combat, localized terminology and return to exploration.
- [ ] P14.2 Verify reboot, AI renewal, interrupted generation, lost response, backup restore, and no duplicate effects.
- [ ] P14.3 Run the required reduced-scope MVP QA matrix and record fresh evidence.
- [ ] P14.4 Measure baseline latency/context use and list unresolved defects honestly; demonstrate one optional bilingual baseline/planner investigation comparison when available, or record deferred/unavailable planning with the playable fallback. Advanced planning cannot block MVP.
- [ ] P14.5 Write reproducible setup and an accurate MVP capability table.

**Exit:** a fresh installation can play, fight, save, resume, and change adventure files without editing engine source. Critical integrity/security failures block the milestone. **Suggested commit:** `Validate the first playable engine milestone`.

## 18. P15 — Complete supported world and optional modules

**Depends on:** P14. **Areas:** social/world/quest modules, guide, creative abilities.

- [ ] P15.1 Add NPC goals/schedules, witnesses, knowledge propagation, companions, and relationship causes/commitments.
- [ ] P15.2 Add richer quest outcomes, faction clocks, bounded time catch-up, reputation, and causally triggered world changes.
- [ ] P15.3 Add guide personas with separate knowledge/session scope and no mutation authority.
- [ ] P15.4 Add creative ability drafting, validation preview, explicit execution, learned recipes, and bounded high-power profiles.
- [ ] P15.5 Prove disabled modules disappear cleanly and enabled modules remain adventure-independent.
- [ ] P15.6 Qualify optional adaptive quest/world-direction tasks using event-triggered significant actors, causal relationship histories and deterministic off-screen catch-up; enable only after agency/coherence/cost comparison, otherwise document disabled/deferred capability with verified baseline fallback.

**Exit:** all supported gameplay modules have typed data, rules, projections, and meaningful integration coverage. **Checks:** QA-21–22 and full requirement-specific scenarios. **Suggested commit:** `Complete reusable world companion and optional ability modules`.

## 19. P16 — Author and owner operations

**Depends on:** P14; integrates P15 definitions. **Areas:** content migration, export/restore, corrections, diagnostic tools.

- [ ] P16.1 Complete validation reports, author previews, dependency graph diagnostics, and content version inspection.
- [ ] P16.2 Add full-owner versus player-facing campaign exports and reusable adventure-bundle import/export for sharing between people, with integrity/dependency/locale/rights manifests; prove clean-recipient round trips and no private save/account leakage under QA-41.
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
- [ ] P17.7 Qualify optional local EN/IT speech with admitted model/voice licenses, stable voice identities, accepted-line audio caching and shared GPU limits under QA-38; ship disabled with tested text-only fallback if hardware/rights/quality gates fail. No TTS prerequisite for core v1.

**Exit:** the graphical visual-novel UI, persistent identifying artwork and bounded image-job pipeline are verified; unsupported generation degrades accurately without blocking play. Optional dynamic music follows [its specification](24-dynamic-music-and-credits.md); no player or audio integration is delivered by adding that document. TTS is not required. **Checks:** QA-16, QA-19, QA-25, QA-29–32, actual visual review and asset/license inventory. **Suggested commit:** `Complete accessible presentation and optional atmosphere`.

## 21. P18 — Full-engine hardening and balance

**Depends on:** P15–P17. **Areas:** long-run evaluation, concurrency/security, performance, simulations.

- [ ] P18.1 Run the complete QA register and 500-turn continuity evaluation with recorded evidence.
- [ ] P18.2 Run adversarial prompt/content/secret tests and Codex supported-platform isolation checks.
- [ ] P18.3 Calibrate AI RPG Skirmish with simulations and human playtests; update threat/rules defaults with versioned changes.
- [ ] P18.4 Profile queries, outbox, memory retrieval, context budgets, AI calls, and latency; optimize measured bottlenecks.
- [ ] P18.5 Rehearse backup recovery, supported migrations, degraded provider mode, and owned-process shutdown.
- [ ] P18.6 Record paired baseline/planner/reviewer comparisons in English and Italian at 100/500 turns, including agency, secrets, wasted work, latency/quota and recovery; publish unrun/unavailable narrative cells honestly under QA-39; benchmark optional speech/GPU under QA-38 only when enabling it.

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
| FR-033 | P03–P05, P08–P09, P14, P17–P18; QA-35, QA-39 |
| FR-034 | P01.7, P03.6, P07.6, P08.6–P08.7, P09, P15.6, P16, P18.6; QA-36–37, QA-39 |
| FR-035 | P05.7, P08.2, P17; QA-40 |
| FR-036 | Optional P17.7, conditional P18.6; QA-38 |
| FR-037 | P04, P16.2, P16.5 and later E05–E06; QA-41 |
| NFR-001–015 | Cross-cutting gates in P01, P03, P07–P09, P16–P19 |

## 24. Explicitly later initiatives

Semantic retrieval/pgvector after retrieval evidence; visual authoring editor after core v1; advanced voice design/fine-tuning or true streaming after separate rights/quality/runtime proofs; alternative combat schedulers and grid maps; public multiplayer hosting; online marketplace/hosted sharing infrastructure; generic AI providers; mobile-native or desktop packaging. Each needs its own scope, design, risks, and roadmap. None is an implicit requirement for the complete initial engine. Bounded runtime image generation is in v1 under ADR 0011; local EN/IT speech is independently optional under ADR 0016. Portable story-file exchange is a v1 P16 requirement and does not require a hosted marketplace.

The owner explicitly requests the integrated text/graphical adventure editor **after core v1**. Its ordered [E01–E06 roadmap](26-adventure-editor-roadmap.md) covers world/history/requirements, mandatory characters, author-fixed versus generatable gaps, fantasy/cyberpunk content, supported combat-profile controls and validate/publish/migrate previews. It is deliberately outside P00–P19 and does not delay the initial engine release. Fixed author constraints and bounded combat customization remain file-contract requirements now; the future editor writes that same contract. QA-34 validates those semantics independently of an editor UI.
