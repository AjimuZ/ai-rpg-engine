# Development Roadmap

## 1. Status and planning rules

**Current status: 66/130 tasks verified.** The owner withdrew the pending P98 review entry on 30 October 2026, so older records below that count 131 entries or mention a pending P98 follow-up describe the queue as it stood then. The committed roadmap checks P00, P01.1–P01.7, P02.1–P02.7, P03.1–P03.6, P04.1–P04.6, P05.1–P05.5, P06.1–P06.4, P07.1–P07.6, P08.1–P08.7, P09.1–P09.5, P10.1, P10.2, P10.3, P10.4, P10.5, P11.1, P11.2 and P11.3. P21.1 has a verified partial checkpoint (hardware-profile command, pinned model-server image, downloaded-and-hashed catalog, advisor and synthetic profiles) but remains unchecked until the profile and container GPU check run on the reference host. P05.6 has verified bilingual preview/packaging checkpoints and a read-only real-campaign integration (persisted operation/cursor reload guard, localized errors/receipts, fallback disclosure, `/saved/<uuid>` serving) but remains unchecked: no browser journey ran in the Linux container, the new Italian messages and QA-35 need independent human review. P06.5 is in progress and now waits for explicit prerequisites P05.7, P08.6 and P09.3 (both complete since 28 October 2026) and P10.2 (presentation events, owner-wide jobs, memory and purchase/equip; P10.2 is complete since 29 October 2026); P07 builds on the completed P06.1–P06.4 foundations, so the corrected graph has no cycle. Task completion does not replace package exit gates: the P01 managed-profile live isolation recheck, full QA-28/QA-35 and other documented acceptance limits remain open. Campaign gameplay and combat are not yet implemented.

The orchestrator follows the first executable task in roadmap order, respecting task/package dependencies. A blocked task stays unchecked; independent eligible packages may advance. An indented `**Requires tasks:** Pnn.n, Pnn.n.` line declares additional exact-task prerequisites, alongside the preceding item in that package and its package dependencies. This permits integration work to wait for later APIs without blocking those APIs behind an entire unfinished UI package. Unknown prerequisites and dependency cycles are invalid. P01.6 and P01.7 are checked against their explicit capability/serialized-inference outcomes; the managed-profile live isolation recheck remains a separate P01 package exit gate. Later package work must still pass its own prerequisites and exit checks.

**Setup prerequisite (23 October 2026):** the owner-authenticated P01.2 check used ChatGPT device-code login. Users of this container login flow must first enable device-code login in their account's security settings, or obtain managed-workspace administrator permission. P02.6 must surface this prerequisite and the supported instructions when authorization is unavailable; it must not assume a desktop sign-in initializes the isolated runtime. P02.7 verifies the guidance/error path. See [runtime account setup](../tools/runtime_probe/README.md#owner-account-setup-for-the-live-check). This documents the required account setting for this login method; the P02.6 launcher surfaces it in `--login` and `--help` without changing account settings.

Repository preparation recorded on 22 October 2026 includes the private GitHub repository, root MIT license, attribution, ignore rules, text line-ending policy, and contribution guidance. The development workflow requires immediate roadmap and affected-documentation updates after every work item, truthful completion evidence, and neutral commit messages. The owner's checkout also has verified local Git gates; lifecycle-hook activation requires review in the relevant client. These local checks are not distributed in a clone and do not replace P02.4 continuous integration.

After each change, update the applicable item or record partial progress before starting the next item. Keep the affected specifications and changelog consistent, and mark a checkbox only when its stated exit checks actually pass. See [the contribution workflow](../CONTRIBUTING.md#documentation-synchronization).

The owner also requires a fully containerized packaged installation and a root `start.sh` entry point. [The deployment specification](22-container-deployment-and-startup.md) and [ADR 0008](adr/0008-containerized-startup.md) document that requirement. Packaging, runtime IPC, account handling, and startup checks remain unimplemented; no roadmap implementation item is completed by this documentation change.

This is an ordered development roadmap, not a claim of committed delivery dates. The work spans architecture, authoring, AI integration, rules, UI, and operations. Estimate each micro-phase after its prerequisites are verified. A credible calendar requires team availability and results from the Codex compatibility and combat prototypes.

**Research/design revision (23 October 2026):** the owner requests [source-backed competitive analysis](27-competitive-analysis-and-gaps.md), first-release [English/Italian support](29-localization.md), safe [asynchronous narrative planning](28-asynchronous-narrative-planning.md), [optional local speech](30-local-speech-synthesis.md) and portable stories. ADR 0014–0016 record the decisions; [prospective evaluations](31-research-validation-and-evaluations.md) and the [Italian report](32-research-executive-summary-it.md) explain gates and priorities. Eleven new unchecked tasks bring the queue from 106 to 117, preserving all twelve verified completion boxes and the later E01–E06 editor. This is documentation-only work: no new application features, model installation or benchmarks are delivered. Optional deep planning/review and speech must not delay text-only MVP play; their unavailable/disabled qualification outcomes need recorded evidence and tested fallbacks, not a claim that the disabled feature was implemented. No optional capability may bypass its safety/rights/quality gates merely to close a task.

**Runtime selection revision (25 October 2026):** the owner asks to run the engine with Claude as well as Codex, one runtime at a time, with Codex remaining the default. [ADR 0020](adr/0020-selectable-narration-runtime.md) records the decision and the [Claude runtime specification](33-claude-runtime-integration.md) its candidate profile. Four new unchecked tasks (P20.1–P20.4) bring the queue from 117 to 121, and the MVP stays Codex-only. P08.1 and P08.4 now require a runtime-neutral task port. P18 hardening waits for P20, so Claude arrives before the v1 gates. Claude is not called supported until the P20 evidence and an owner review of Anthropic's terms are recorded. This is documentation-only work: no adapter, image, launcher option or setting is delivered, and no Claude result is claimed.

**Backends revision (25 October 2026):** the owner asks for free local models for narration and for image generation, for hosted use through an API key, for defaults recommended from the reference PC (RTX 4070 with 12 GB of VRAM and one 16 GB memory module), and for maximum freedom to choose other models for each weight class. [ADR 0021](adr/0021-inference-backends-and-model-selection.md) and the [specification](34-inference-backends-and-model-selection.md) record the decision. Six new unchecked tasks (P21.1–P21.6) bring the queue from 121 to 127. P21 follows the memory work (P09) so that P10–P17 can be built and played with free inference, P17.6 requires the image backend, and P18 waits for P20 and P21. The MVP is unchanged. This is documentation-only work: no backend, image worker, launcher option or setting is delivered, and no measurement is claimed.

Each work package must be split into small reviewable changes with one main objective. For each change record intended behavior, affected modules/files, required checks, documentation update, completion evidence, and a suggested English commit message. Source paths below describe **future** work; they are not folders scaffolded by this documentation phase.

Do not commit, push, create a remote repository, or publish a release merely because a roadmap task mentions it. Those actions follow the owner's development/publication instructions when execution begins. As of 22 October 2026 the owner authorizes commits and pushes of verified implementation work to `main`, the project's single branch; releases and publication remain owner decisions. Each implementation commit names its roadmap item in a final `Roadmap: <item>` body line so progress can be traced to Git history ([contribution workflow](../CONTRIBUTING.md#git-workflow)).

**Speech-input revision (26 October 2026):** the owner requests optional EN/IT
[STT and hands-free play](35-speech-input-and-hands-free-play.md), including
combat dialogue/commands, under [ADR 0022](adr/0022-optional-speech-input.md).
Three unchecked tasks P17.8–P17.10 bring the queue from 127 to 130, preserving all
40 verified boxes. Microphone enablement/status/mute, voice-only correction and
confirmation, normal command authority/idempotency, echo suppression and scoped
ephemeral audio are required before enabling a profile. Concrete recognition
backend/model selection remains open. No implementation, installation, capture,
performance or gameplay result is delivered. Full voice qualification is optional;
QA-45's disabled/no-capture/text fallback remains required for release.

## 2. Milestones

| Milestone | Work packages | Demonstrable outcome |
| --- | --- | --- |
| D0 — Design | This documentation set | Reviewable engine specification and development plan |
| F1 — Feasibility | P00–P01 | Supported stack baseline and safe functioning Codex contract |
| F2 — Foundations | P02–P07 | Validated packs, persistent campaigns, authoritative command pipeline |
| F3 — Persistent AI play | P08–P10, P21 | Narration (Codex by default, or free local models), renewable context, local image backend, character/inventory/quest loop |
| F4 — Playable MVP | P11–P14 | Complete combat, dynamic bounded encounters, progression, recovery, neutral demo |
| F5 — Complete engine beta | P15–P17, P20 | Full supported modules, author operations, presentation and accessibility, selectable Claude narration runtime |
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

**CI maintenance (24 October 2026):** [hosted run 40088839023](https://github.com/AjimuZ/ai-rpg-engine/actions/runs/40088839023)
passed the frontend, backend and repository jobs, but two launcher command tests
failed because its Docker Engine 28.0.4 is below the production minimum 29.0.
The tests checked only daemon reachability and therefore never reached their
intended path/port assertions. Replace that host dependency with strict test-only
Docker metadata and keep real BuildKit/Compose integration checks separate.
Add explicit refusal cases for old Engine/Compose, non-Linux containers,
unreachable daemon and missing Buildx; preserve launcher minimums and production
behavior. The old-runner reproduction fails both original cases before the fix
and passes after it. Local packaging verification passes 21 tests with the two
existing opt-in lifecycle/recreation skips (23 total); no new lifecycle result or
hosted success is inferred from that local result. No roadmap checkbox changes.

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

- [x] P05.1 Build library, character-creation, default visual-novel story, sheet, inventory, journal and settings shells with dominant scene art and attributed speaker passages.
  - Local routed previews now cover all seven shells for two neutral genre samples,
    with separate adventure/pack library sections, bounded character-name preview,
    original vector scenes, explicit speaker labels and local-only Continue/chronicle.
    Acceptance checks pass: typecheck, 11 form/routing/service tests, production build,
    dependency audit (zero vulnerabilities), 22 repository tests and
    docs/secrets/workflows/images (zero findings). Chrome 155 desktop/mobile smoke
    verifies navigation, keyboard Continue, chronicle, name form, settings and unknown
    identity handling without remote/action requests or page exceptions; screenshots
    were visually reviewed. Trimmed-name boundaries, returned-route summary clearing
    and missing-image recovery have regression coverage. Campaign APIs, real pack
    binding, persistent drafts/locales, full reading/accessibility behavior and the
    complete graphic kit remain later tasks; P05 overall is not yet accepted.
- [x] P05.2 Bind display names, resources, panels, and terminology to content metadata.
  - Typed local public metadata now drives scoped terminology, attribute/resource
    definitions and optional guide/creative panels. Neutral fallbacks, literal text,
    stable route IDs and disabled direct-route handling preserve generic behavior.
    Strict typecheck, all 15 frontend tests, production build and dependency audit
    pass (zero vulnerabilities), as do 22 repository tests and all four policy scans.
    Chrome 155 desktop/mobile smoke covers metadata/resource labels, optional and
    disabled panels, navigation and reading without page exceptions or remote/action
    requests; three screenshots were visually reviewed. Empty metadata and literal
    markup have regression coverage. Registered pack/campaign APIs remain P06;
    these definitions never imply current balances or enable pack module execution.
- [x] P05.3 Implement the default visual-novel and alternative text-focused responsive layouts, local passage advancement, chronicle, speaker portraits and keyboard/focus support.
  - Settings selects scene-led or no-artwork reading without changing local passage
    positions; scoped public speaker IDs resolve original MIT vector portraits.
    Missing portraits/scenes retain public text, and the attributed ordered chronicle
    contains only revealed passages. Native Enter/Space controls, visible focus and
    final-passage focus transfer pass Chrome 155 browser checks on both previews.
    Desktop/mobile screenshots were visually reviewed; text mode also passes 320px
    and landscape layouts at 24px with reduced motion and no story artwork requests.
    Strict typecheck, all 18 frontend tests, production build, zero-vulnerability
    audit and all 22 repository tests pass; docs/secrets/workflows/images scans
    report zero findings. Full QA-29 input/reconnect behavior,
    bilingual resources, the complete graphic kit and assistive-technology review
    remain outside this local reading increment; no campaign actions are enabled.
- [x] P05.4 Define revision-aware projection state, persistent free-input drafts, editable contextual quick replies and connection/error states; choosing a suggestion must not submit or advance the world.
  - Separate scoped public projections from bounded persistent draft text/selection;
    reject stale revisions, callbacks and suggestion identities, including after
    detachment/return. Editable typed suggestions fill only an empty draft and
    never submit or advance the world. Connection/recovery states preserve input.
    All 26 frontend tests, strict typecheck, production build, zero-vulnerability
    audit and 22 repository tests pass; docs/secrets/workflows/images scans report
    zero findings. Chrome 155 desktop/mobile verifies keyboard editing/dismissal,
    reload/selection, scope isolation, offline/reconnecting and clear persistence
    with no exceptions or nonlocal/action requests; mobile composer visually reviewed.
    Send and retry await P06/P07 campaign/operation transport; full accessibility
    and bilingual acceptance remain later gates.
- [x] P05.5 Create or license and integrate the complete UI graphic kit, including buttons, bars, icons, frames and required interaction/fallback states; register sources, notices and generated-asset provenance.
  - Original MIT SVG/CSS kit supplies 20 icons, bounded resource bars, separate item
    artwork, inventory/portrait/dialogue frames, native control states and persistent
    help disclosure. Navigation, reading actions, sheet and inventory use the kit;
    Settings links its review route, editable-source inventory and shipped notice.
    No third-party artwork or raster generation is used. All 30 frontend tests,
    strict typecheck/build (345.91 kB), zero-vulnerability audit and 22 repository
    tests pass. Chrome 155 verifies keyboard/pointer states, live numeric labels,
    measured text/placeholder/control contrast, forced colours, increased contrast, reduced
    motion and notice delivery. Desktop/mobile screenshots were visually reviewed;
    320px/landscape layouts do not overflow. QA-30 covers this local kit; campaign
    asset delivery/ownership, Italian resources and runtime artwork remain later
    tasks. No sample value or item implies canonical state.
- [ ] P05.6 Deliver reviewed English-default/Italian UI variants, localized receipts/errors/accessibility, persisted language selection and draft/cursor-safe reload; extend single-origin packaging/resource checks under QA-35.
  **Requires tasks:** P06.2, P07.5.
  - Integration prerequisite audit (24 October 2026): remaining acceptance is
    blocked on P06.1–P06.2 authorized campaign projections and P07.1/P07.5
    operation queries/resumable stream. Only preview GET controllers exist;
    internal journals/catalogs are not browser APIs. The original package-wide
    P06→P05 dependency formed a scheduling cycle; the correction below separates
    UI foundations from integration. Keep the real-campaign reload guard and this
    checkbox intact. See the [requirement/prerequisite/evidence matrix](29-localization.md#p056-integration-prerequisites-24-october-2026-audit).
    All 42 frontend tests, strict type checks and four resource checker tests
    pass again. Independent bilingual and screen-reader acceptance still requires
    human review after integration; no runtime changes were made in this audit.
  - Partial checkpoint: pin `@angular/localize` to Angular 22.2.3 and extract/review
    26 navigation/Settings messages, including accessible navigation, titles,
    reading-size placeholders and credits. Exact ID/source/placeholder parity
    rejects missing, blank, stale (including whitespace), extra and duplicate translations. Separate
    EN/IT review builds compile; the first checkpoint retains packaged English output.
    Second checkpoint extends review to 212 unique messages across all local
    screens, public preview passages/metadata, neutral terminology, native forms,
    unsaved preview receipts, recovery states, media fallbacks and kit/ARIA text.
    Stable IDs, proper adventure titles and user drafts stay unchanged. Chronicle
    ICU plurals and locale-formatted meter text compile in both variants; numeric
    ARIA/protocol values remain invariant. All recovery-state and locale-format
    tests pass, alongside bilingual story/receipt/validation browser journeys.
    Third checkpoint adds allowlisted persisted language selection in
    the compiled review variants, validated tab-scoped reading preferences/positions,
    draft-storage verification and safe reload with equivalent Settings focus and
    old callback/reply invalidation. Nine reload/storage tests and native
    EN→IT→EN browser journeys pass, including two independent draft scopes,
    reading size/layout/position, focus, unsupported locale and storage denial.
    Restore the native text-size option after loop-created options exist.
    Reload refuses
    unresolved recovery, real campaign contexts and unsaved/conflicting drafts,
    including after route detachment. No operation/cursor transport is invented.
    Fourth checkpoint packages both compiled locale trees, a device-preference
    root selector and finite Spring preview GET routes. Shared artwork/notices
    retain root URLs; localized resources retain their locale prefixes. Packaging
    checks pass: 22 packaging tests (one separate launcher lifecycle test skips),
    full PostgreSQL-backed packaged backend verification including four HTTP tests,
    and Chrome 155 bilingual journeys against the actual container. Root preference,
    denied/unsupported storage, direct links and opaque query/hash preserve drafts;
    both locale resource/notice/artwork hashes survive recreation. An initial
    root-artwork 404 was fixed by copying shared assets from compiled output.
    An offline negative build rejects missing localized output with build instructions.
    Actual operation/cursor reconciliation remains open.
    Four checker tests, 42 frontend tests, strict typecheck, both build commands
    (218 reviewed messages),
    zero-vulnerability audit and Chrome 155 review smoke pass. Browser checks
    cover localized labels/titles/focus, literal drafts and selection, one/many
    passage plurals, recovery/media fallbacks, four viewport sizes, locale bases and
    same-origin script/style/notice hashes and missing-resource 404s using a
    loopback review harness. All 22 repository tests and docs/secrets/workflows/images
    scans pass again for this checkpoint. Full QA-35, real API error/receipt and
    pack-source fallback integration, and bilingual release acceptance remain open.
  - Fifth checkpoint (28 October 2026, campaign integration): with P06.1–P06.4 and
    P07.1–P07.5 complete, the interface binds the real endpoints read-only. A
    validated campaign protocol layer (cursors, operation bodies, receipts, SSE frames,
    state subset) and a `CampaignSession` persist each scope's stream cursor and
    operation identities/versions/statuses; language reload now proceeds for a real
    campaign only when that record merges with other tabs, is written and reads back
    exactly, and drafts are stored (replacing the blanket refusal). After reload,
    pending and recently saved operations are re-queried by identity and the stream
    resumes after the saved cursor; duplicates are skipped, resets adopt the head and
    re-query, generation changes are reported. 77 `campaign.*` messages (317 total)
    localize 26 error codes (unknown codes use one generic sentence), operation
    statuses, receipts (locale digits only; identical mechanics), narration/reset
    reasons and an explicit source-language fallback note shown from
    `startingSituation.fallback`. The packaged jar serves `/saved/<uuid>` and
    `/{en,it}/saved/<uuid>`; a new `localization` repository check fails when a
    backend campaign error code lacks a message. Verified: 68 frontend tests, strict
    typecheck, parity build, 435 backend tests with PostgreSQL (four packaged
    interface tests including the new route), 24 repository tests and zero scan
    findings. Not done, so this task stays unchecked: no browser exists in the
    Linux build container, so no Chrome EN→IT→EN journey or packaged-container
    hash recreation ran for the new screens; the 77 Italian targets are unreviewed
    drafts; there is still no submit path, so draft-plus-pending-operation reload is
    proven only on simulated servers; independent bilingual and screen-reader
    review (QA-35) is a human gate.
  - Sixth checkpoint (28 October 2026, recorded real-server contract): the backend test
    `CampaignInterfaceContractTest` records 19 real HTTP exchanges (servlet stack,
    PostgreSQL, presentation worker) across first load, another client's move plus an
    admitted command left in flight, delivery, cancellation during the language reload
    and the reloaded page; the normalized transcript must equal its golden file on every
    run. `campaign-contract.spec.ts` replays a byte-identical copy through all browser
    validators and an EN→IT→EN reload of the routed view: draft text and selection, both
    operation identities and the cursor survive, in-flight and receipt-less operations
    are re-queried by identity, delivery resumes after the saved cursor, nothing is
    applied twice and no request writes. A new `contracts` repository check and CI step
    keep both copies equal. The replay exposed a reset loop (an unknown cursor merged
    back over the adopted head) and a stale starting decision after a commit; both
    regression tests failed before and pass after the fixes. An author review corrected
    11 Italian campaign targets. Verified: 72 frontend tests, strict typecheck, four
    localization checker tests, parity build (317 messages), 436 packaged backend tests
    with zero failures/errors/skips, 26 repository tests and zero findings in all six
    checks. Still unchecked: the replay runs in jsdom, not Chrome, so no browser or
    packaged-container journey ran (no browser in the Linux container); independent
    Italian and screen-reader review and the QA-35 sign-off are human gates.
  - Seventh checkpoint (28 October 2026, Italian variant rendering): `italian-variant.spec.ts`
    loads the reviewed Italian catalog into `$localize` before importing the application and
    renders every routed preview screen, the navigation shell and the recorded real-server
    playthrough in Italian. It fails on English-only catalog text (including ICU branches),
    uncatalogued English phrases, unnamed controls/links/images, broken ARIA references and
    unsupported `lang` values, and checks the real replies' statuses, receipts, notices and
    404 code in Italian with identical receipt digit sequences; its detectors are self-tested
    and failed on deliberate defects. An exploratory pass found only data (titles, meter values,
    icon IDs, the `Italiano` endonym) outside the catalog. Saved-adventure titles and genres now
    carry their source `lang`, and `build:locales` writes each variant's reviewed brand into its
    pre-bootstrap `<title>` (the `it` index opened with an English title; `PackagedInterfaceTest`
    now asserts each locale's title and `lang` and failed before the fix). Verified: 77 frontend
    tests in 13 files, strict typecheck, five localization tool tests, parity build (317
    messages) and 436 packaged backend tests with zero failures/errors/skips. Still unchecked
    for the same reasons: no browser or screen-reader journey can run in the Linux container,
    and independent Italian and screen-reader review (QA-35) is a human gate.
- [ ] P05.7 Define and render bounded typed public presentation events for scene, pose/expression and optional line audio hooks; reject executable/raw-URL directives, deduplicate delivery and preserve text-only/keyboard/reduced-motion behavior under QA-40.

**Design clarification (23 October 2026):** The owner selected visual-novel play as the principal experience. [Interface specification](13-api-and-ui.md), [primary mockup](mockups/visual-novel-ui-v1.png), [asset pipeline](23-presentation-assets.md) and [ADR 0011](adr/0011-visual-novel-and-persistent-artwork.md) define attributed dynamic passages, editable quick responses, persistent player input and complete graphics. QA-29–30 are implementation checks; this documentation and concept image do not complete P05. Runtime portrait/item generation and saved visual identities belong to P17 and QA-31.

**Exit:** a generic interface works with either fixture and contains no mandatory protagonist/Rune/Lyra lore. **Checks:** meaningful form/service tests and browser smoke for navigation and reading controls. **Suggested commit:** `Build the configurable RPG interface foundation`.

## 9. P06 — Campaign creation and deterministic consultations

**Depends on:** P03–P04; uses completed P05.1–P05.5 foundations, not P05.6/P05.7 integration. **Areas:** campaign services/API, knowledge projections, creation UI.

- [x] P06.1 Create campaigns atomically from immutable pack snapshots and validated character choices.
  **Requires tasks:** P05.5.
  - Verified internal creation-core checkpoint (24 October 2026): owner/adventure
    authorization, sealed-byte/ruleset reload, bounded preset/custom budgets and atomic
    character/resources/loadout/locale state with immutable durable request retries.
    V9 preserves populated legacy state without fabricated backfill; recovery scope
    includes all four new tables. Full pinned Java/Maven packaged verification passes
    275 tests on PostgreSQL 18.6; 22 repository policy tests and 8 backup tests pass
    (one Windows symlink case skips); repository scans have zero findings.
    Verified dependency checkpoint: bounded transitive sealed dependency loading
    preserves declaring-pack namespaces, profile rules and canonical grant IDs.
    Preset skill maps accept typed dependency references; creation rechecks actual
    pinned rank and stack caps before inserting campaign rows.
    Closure limits are 32 revisions, depth 16 and 250 MiB of sealed bytes;
    shared subtree heights prevent cached diamond paths evading the depth limit.
    Conflicting versions of one pack ID fail closed. PostgreSQL integration tests
    cover these bounds, namespaces, immutable pins and invalid grants; the byte
    limit uses a simulated aggregate size over real stored revisions.
    Full pinned packaged verification passes 281 tests with zero failures/skips;
    all 22 repository policy tests pass and repository scans have zero findings.
    Local HTTP admission now has exact configured loopback Host/Origin checks,
    a server-configured owner, 32 fixed-lived 30-minute sessions, independent
    CSRF proofs, restricted cookies and a four-mutation concurrency cap.
    Four focused admission tests and five real HTTP/PostgreSQL creation tests
    pass. Strict 16 KiB JSON creation rejects coercions, duplicate/unknown fields,
    foreign scopes, invalid budgets and changed retries without partial writes.
    A focused real process-restart check passes: create two genre campaigns,
    gracefully stop/start separate application JVMs, reject old sessions and
    return both original request results with unchanged counters and grants.
    Expanded checks also cover bounded chunked input, concurrent admission and
    capacity release after writer failures. Final pinned Java 21/Maven 3.10
    `clean verify -Ppackaged-ui` passes 290 tests on PostgreSQL 18.6 with zero
    failures, errors or skips. All 22 repository policy tests pass; scans report
    zero findings. Resumed-session review and a fresh full packaged build confirm
    the same 290 passing tests and clean policy checks before commit.
    P06.1 creation, ownership and process-restart checks are complete.
    Starting narration, public projections and consultation acceptance remain later tasks.
  - Scheduling correction (24 October 2026): the completed P05.1–P05.5
    foundation permits P06/P07 API development; P05.6 waits explicitly for P06.2
    and P07.5, then becomes eligible again in roadmap order. All 117 task IDs
    and 36 completion marks are preserved. The dependency simulation selects
    P06.1 first and returns to P05.6/P05.7 after P07.5; this is scheduling
    evidence, not proof that those future implementations or reviews pass.
    Local scheduling verification passes all 60 temporary-repository tests,
    including eight new exact-prerequisite/cycle/retained-human-blocker cases;
    repository policy and Markdown checks report zero findings.
- [x] P06.2 Add owner-scoped campaign listing, continue, known state, inventory, quest, and ability projections.
  - Authorize the exact owner/adventure/campaign scope before child reads; use
    detached player DTOs and independent read-only repeatable-read snapshots.
    Bounded UUID-keyset listing never exposes another owner's campaigns.
    V10 adds explicit actor-audience facts/quests with no legacy backfill;
    owned inventory/learned abilities never join by equal display names.
    Raw pending decisions, hidden NPCs, pack bodies and plans stay private.
  - Verified on 24 October 2026: pinned PostgreSQL 18.6 and packaged backend
    `clean verify -Ppackaged-ui` pass all 296 tests with zero failures/errors/skips.
    Eleven real HTTP tests cover both genres, foreign/mismatched scopes across
    all sections, whole-database no-write comparisons, separate-JVM restart,
    concurrent committed snapshots, collection limits and legacy characters.
    Populated V1-to-V10 upgrade preserves old saves without inventing knowledge.
    All 22 repository policy tests pass; repository scans report zero findings.
    Typed starting decisions are covered by P06.3. Exact text consultations,
    frontend binding and full QA-33 media/draft/restore isolation remain
    P06.4–P06.5/P05 integration.
  - CI recovery (24 October 2026): exact run 40122681346 failed the backup
    migration-scope regression after V10 was added. Extend the reviewed recovery
    allowlist to V10 and both actor-audience knowledge tables; retain strict
    version/catalog checks. Local reproduction confirmed the V9/V10 mismatch;
    all nine backup tests now complete with eight passes and the existing Windows
    symlink-privilege skip. All 22 repository tests pass; runtime probes pass
    98 cases with 18 opt-in skips. No live V10 restore is claimed. No task
    completion marks change. Repository scans report zero findings; all 117
    task IDs, titles and checked states match the incident commit.
- [x] P06.3 Show a pack-defined starting situation and pending decision without hardcoded prologue text.
  - Atomic creation stores a versioned starting marker; authorized state/continue
    project only the pinned public root entrypoint in the campaign locale with
    explicit source fallback and typed free-input metadata. Unknown/cleared
    decisions and legacy saves expose no invented prose; retries never replay it.
  - Verified on 25 October 2026: pinned Java 21/PostgreSQL 18.6 packaged backend
    `clean verify -Ppackaged-ui` passes all 299 tests with no failures/errors/skips.
    All 24 focused campaign tests pass, including EN/IT in both genres, authored
    revision pinning, fallback, unsupported markers, whole-database no-write reads
    and separate-JVM restart. Repair the HTTP test helper to remove byte-counted
    chunk framing before UTF-8 decoding; the Italian regression now passes.
    All 22 repository policy tests pass; repository scans report zero findings.
    Backup checks pass eight tests with one Windows symlink-privilege skip.
    Browser binding and command submission remain P05.6/P07; this completes only
    the backend starting projection, not full QA-33 or the remaining P06 tasks.
- [x] P06.4 Keep exact status/inventory consultations local and separate transcript delivery from world state.
  - Add the admitted scoped consultation GET with bounded exact English/Italian
    aliases, detached typed passages and committed source scope/revision. Status
    exposes own character/resources; inventory exposes owned stacks. Prose and
    unsupported commands fail closed without runtime interpretation or operations.
    Delivery/retries write no transcript, timestamp, event, draw, job or world counter;
    optional durable history remains a separate presentation boundary.
  - Verified on 25 October 2026: pinned Java 21/PostgreSQL 18.6 packaged
    `clean verify -Ppackaged-ui` passes all 300 tests, zero failures/errors/skips.
    All 15 HTTP cases pass, including progressed/depleted-save whole-database
    equality, repeated bilingual commands, admission/foreign-owner rejection and
    consultation reconstruction for both genres across separate-JVM restart.
    All 22 repository policy tests pass and repository scans have zero findings.
    Browser API binding remains P05.6; full QA-33 and optional durable transcript
    history are not claimed. No schema/dependency/runtime-account changes.
- [ ] P06.5 Implement separate adventure specifications/media folders and library switching; prove same-name entity, draft, memory, artwork and restart/restore isolation under QA-33.
  **Requires tasks:** P05.7, P08.6, P09.3, P10.2.
  - Verified partial checkpoint (25 October 2026): locally admitted owner-scoped adventure listing/detail reads
    over persisted workspace IDs, including empty workspaces and equal titles.
    Bounded UUID pages and detached public metadata do not select an active world,
    expose authored specifications, or change campaign counters. The complete
    adventure route subtree now receives the same admission guard as campaigns.
    Pinned Java 21/PostgreSQL 18.6 `clean verify -Ppackaged-ui` passes all 305 tests
    without failures/errors/skips, including 19 HTTP cases, whole-database no-write
    comparisons, independent snapshots, bounded pagination and same-title two-genre
    separate-JVM restart. Two new regressions first failed on missing routes.
    Repository policy tests pass all 22 cases; backup tests pass eight with one
    Windows symlink-privilege skip. No live restore rehearsal is claimed.
    This first checkpoint did not deliver managed folders or browser switching.
  - Verified second partial checkpoint (25 October 2026): add opt-in private, atomic
    specification/authored-media projections of sealed revisions, keyed by adventure
    and revision UUIDs. PostgreSQL remains the sole authority; startup and identical
    import retries rebuild missing projections. Changed/extra files, links and
    scope mismatches fail closed. The initial two-genre folder regression failed
    before implementation and passes afterward. All 13 focused tests pass, including
    corruption/static links, post-commit storage failure and identical retry repair,
    title renames and fresh-JVM reconstruction. Pinned Java 21/PostgreSQL 18.6
    `clean verify -Ppackaged-ui` passes all 318 tests with no failures/errors/skips.
    The shipped backup/restore tool plus fresh-JVM startup preserved all 32 canonical
    tables and rebuilt 34 byte-identical files/manifests for two same-title genres
    into an empty root. This rehearsal has authored originals, not gameplay memory,
    drafts or generated media. Repository policy tests pass 22; backup tests pass
    eight with one Windows symlink-privilege skip; repository scans have no findings.
    Real browser switching, generated media, memory, owner-wide job limits and
    complete QA-33 draft/artwork/job restart/restore evidence remain unfinished.
  - Verified third partial checkpoint (25 October 2026): the browser library now reads
    persisted adventures through a validating same-origin client (session bootstrap,
    one re-bootstrap after 403, strict UUID/bounded payload checks, foreign/duplicate
    rows rejected). `/library` lists saved cards keyed by UUID; `/saved/:adventureId`
    shows one scope with read-only playthrough selection through `?playthrough=`.
    Switching aborts and discards the previous adventure's reads. Seven new Vitest
    cases cover equal titles, foreign playthroughs, outages, non-UUID routes and a
    late reply after switching. Frontend typecheck, 49 tests, i18n parity (240
    reviewed) and 4 i18n tests, both builds, and EN/IT Chrome smoke pass. Repository
    policy tests pass 22 cases; repository scans have no findings. Packaged backend
    `clean verify -Ppackaged-ui` was not run because the local container daemon was
    unavailable. The packaged jar does not yet allowlist `/saved/...` for reload or
    direct links (in-app navigation only). Saved playthroughs are not playable in the
    browser.
  - Dependency correction (25 October 2026): full QA-33 at the MVP scope needs
    playable saved campaigns with pending replies and late media events (P05.6–P05.7),
    owner-wide job limits (P08.6, P09.3), memory (P09.1–P09.5) and purchase/equip
    (P10.2). P07 previously depended on the whole P06 package, so declaring these
    prerequisites formed a cycle and P06.5 could neither finish nor wait. P07 now
    depends on P03–P04 and uses the completed P06.1–P06.4 foundations through
    `P07.1 Requires tasks: P06.4`; P06.5 declares the prerequisites above. P14 still
    depends on the complete P06 package, so the MVP keeps the QA-33 gate. Runtime
    image generation belongs to v1 under QA-31 ([acceptance scope](15-quality-and-acceptance.md)),
    so P06.5 proves authored artwork and campaign presentation media; P17.6 must
    prove that generated images stay in their originating adventure folders across
    switching and restore, and P18.1 reruns the complete QA-33 scenario. No
    requirement, task identity, title or checked status changed. The runner's own
    parser reported a cycle before this correction and accepts the corrected graph.

**Exit:** two independent campaigns survive restart and read-only use changes no game counters. **Checks:** creation budget/loadout validation, ownership isolation, restart, and no-AI consultations. **Suggested commit:** `Add persistent campaigns and local RPG consultations`.

## 10. P07 — Authoritative command and mutation boundary

**Depends on:** P03, P04; uses completed P06.1–P06.4 campaign foundations, not P06.5 isolation acceptance. **Areas:** commands, rules, operation API, outbox.

- [x] P07.1 Implement durable idempotency with payload hashes, expected revision, lease/fencing, admission limits, and operation statuses.
  **Requires tasks:** P06.4.
  - Acceptance verification (27 October 2026): continue the implementation already
    committed in `174be3e`; 96 focused command tests and all 359 packaged backend
    tests in 29 suites pass on PostgreSQL 18.6, with zero failures/errors/skips.
    Two new cases force expiry during real renewal/admission owner-lock contention
    in both acquisition orders, closing the earlier unforced interleaving gap.
    Frontend installation/typecheck, 49 tests, four localization tests, both locale
    builds, 22 repository-policy tests and zero-finding scans pass. Run the unchanged
    selected independent commands directly; leave final runner admission to the
    runner without altering its state/configuration. Preserve all original inputs,
    eight migration hashes and prior recovery history. Only P07.1 may advance;
    typed execution and presentation delivery retain the follow-on scope below.
    Earlier unchecked checkpoints record their historical status, not a current
    dependency blocker. See [evidence scope](15-quality-and-acceptance.md).
  - Restored-fixture recovery (27 October 2026): the owner restored the disposable
    PostgreSQL service; fresh DNS/TCP checks, 94 focused command tests and all 357
    packaged backend tests pass with zero failures/errors/skips. Frontend typecheck,
    49 tests, four localization checks and both production locales pass. Preserve
    the reviewed interrupted implementation in a verified checkpoint and retain all
    historical failures and original migration drafts. This supersedes the DNS
    blocker below, not the normal task-admission gate. P07.1 stays unchecked and all
    task identities/titles/statuses remain unchanged during recovery. Host restart
    persistence is owner-reported; this session verifies database reachability and
    authenticated checks without changing service or runner configuration.
  - Continuation verification (27 October 2026): the scoped helper executes
    JDK/Maven, but the disposable PostgreSQL fixture hostname cannot resolve.
    Packaged verification reports 357 tests: 188 pass, 169 database/context errors,
    zero assertion failures and zero skips. Frontend typecheck, 49 tests, four
    localization checks, both locale builds and 22 repository-policy tests pass;
    repository scans report zero findings. Read-only review finds no critical or
    important defect; the documented exact-expiry renewal race remains unforced.
    Preserve the earlier successful evidence and all source/migration drafts.
    Restore fixture connectivity and rerun focused/full database verification before
    the implementation commit and completion mark. This is a dependency blocker,
    not a new missing-JDK failure or a passing P07.1 acceptance result.
  - Continuation checkpoint (26 October 2026): the eight preserved Java drafts
    match their migration manifest hashes and remain outside the source tree.
    Admission, payload normalization and status lookup have been reviewed against
    the existing commit/lease boundary; no P07.1 implementation or acceptance pass
    is claimed. Java/JDK 21 is absent, Maven is not on PATH, and no PostgreSQL
    tooling or Docker/Podman runtime is available. `./mvnw -B -ntp verify` stops
    before compilation because Java/javac is missing. This is a dependency blocker.
    Documentation checks pass: 22 repository policy tests, zero scan findings and
    a clean whitespace diff; these checks do not verify the Java implementation.
  - Recovery checkpoint (26 October 2026): unchanged wrapper failure reproduced;
    `unzip`, `curl` and `wget` are also absent. Independently downloaded Maven 3.10.0
    ZIP bytes match the committed checksum. The Temurin metadata lookup returned
    HTTP 403, and no JDK or PostgreSQL service was provisioned. Recovery remains
    unresolved. Preserve all toolchain pins, prerequisites, draft files and task
    statuses; documentation/artifact checks do not replace backend verification.
  - Follow-up prerequisite audit (26 October 2026): the installed dependency-graph
    validator accepts all 130 tasks, preserves 40 checked items and confirms P07.1's
    roadmap prerequisites are satisfied. No scheduling dependency needs alteration.
    Direct packaged verification fails with `mvn: not found`; compiled EN/IT assets,
    frontend dependencies and test datasource variables are also absent. Provision
    the complete documented build environment before retrying; no backend compilation,
    database verification or recovery success is claimed.
  - Verified dependency recovery (26 October 2026): the owner-provisioned private
    helper executes with JDK/javac 21.0.12.1, Maven 3.10.0 and scoped settings for
    a separate disposable PostgreSQL 18.6 fixture. Frontend installation,
    typechecking, 49 tests, four localization tests, parity for 240 reviewed messages
    and both production locales pass. Packaged backend verification compiles the
    existing source and passes all 318 tests with zero failures/errors/skips,
    including 49 canonical commit/lease/recovery cases. The missing-toolchain
    blocker is repaired for that helper; the unscoped wrapper still requires
    `unzip` for its pinned ZIP. Preserve prior recovery history and all eight
    uninstalled drafts. This is prerequisite recovery, not P07.1 implementation or
    acceptance. The owner controls activation of the staged runner environment
    and checks; no runner configuration or task identity/status is changed.
  - Implementation checkpoint (26 October 2026): install bounded version-1 SHA-256
    canonicalization, exact-revision/idempotent atomic journal-plus-lease admission,
    shared owner-wide permit accounting on acquire/reacquire/renew, and owner-scoped
    HTTP status/replay through the existing campaign adapter. Preserve all eight
    original drafts externally; add no controller file or migration. Retain 32 KiB
    canonical/16 KiB HTTP bounds, two live permits per owner and a 180-second initial
    lease, independent of inference concurrency. Cleanup/busy share one database
    timestamp after a deterministic regression reproduced an expiry race.
  - Verified exits (26 October 2026): all 94 focused command tests and all 357
    packaged backend tests in 29 suites pass on PostgreSQL 18.6, with zero failures,
    errors or skips. Coverage includes duplicate/conflicting races, exact revision,
    immediate/deferred journal/lease rollback, cross-adventure owner-cap and internal
    reacquisition races, old-worker draw/commit fencing, application-context restart,
    immutable stored-result replay and read-only scoped status. The expiry regression
    first failed as expected, then passed with the shared cutoff. Frontend typecheck,
    49 tests, four localization checks and both locale builds pass. All 22 repository
    policy tests and zero-finding scans pass. Review's minor unforced exact-expiry
    renewal/admission interleaving is recorded in the
    [evidence scope](15-quality-and-acceptance.md); sequential expired renewal and
    mixed reacquisition/admission races pass. Completion remains unchecked until the
    neutral owner implementation commit is recorded.
  - Follow-on boundary: typed command registration/semantic execution is P07.2–P07.4;
    stream/cancellation delivery remains P07.5. Admission itself runs no worker,
    retains no payload for automatic restart and enables no browser Send action.
    See the [implemented contract](07-turn-protocol.md#p071-durable-admission).
  - Exit checks: real PostgreSQL duplicate/conflicting payload races, exact revision
    rejection, rollback between journal insertion and lease grant, concurrent
    cross-campaign owner limits, expired-holder commit/draw rejection, restart and
    stored-result replay, and read-only owner-scoped status. Run focused command
    tests and full backend verification, plus repository policy tests/scans; record
    actual results and a neutral owner commit before checking this task.
- [x] P07.2 Implement typed commands and atomic semantic validation; reject generic field patches.
  - Package A — closed intention contracts: sealed exploration `MOVE`, `WAIT` and
    `SPEAK` types with exact bounded payloads, no arbitrary patches/effects/batches,
    and immutable canonical input shared by hashing and decoding. Preserve historical
    matching replay before current registration checks; unsupported actions fail closed.
  - Package B — atomic common semantics: validate mode, supported pending decision,
    immutable creation-player binding and campaign-scoped speech recipient under the
    admission lock before journal/lease writes. Require hash-bound `CommandIntent`
    evidence and repeat eligibility inside canonical commit; reject omitted/mismatched
    evidence for registered player operations. Keep rule/resource/knowledge/causal
    validation and execution in P07.3–P07.4; no executor or browser submission is added.
  - Verified evidence (27 October 2026): all 131 focused command cases and all
    394 packaged backend cases in 31 suites pass with zero failures/errors/skips on
    the scoped JDK 21/PostgreSQL 18.6 fixture. Configured frontend installation,
    typechecking, 49 tests, four localization tests, 240-message parity and both
    locale builds pass; all 22 repository-policy tests and zero-finding scans pass.
    Implementation owner commit: `05f5ba7`; all exit checks passed before this
    completion mark. Preserve the repaired compile-attempt history in the quality
    evidence. Synchronize the pre-existing pending P98 requested-changes entry from
    the external roadmap; all 130 imported tasks and that pending follow-up retain
    their identities/titles, and only P07.2 gains a completion mark.
  - Independent re-verification (27 October 2026): the first completion report
    listed superseded failed development attempts as verification outcomes and was
    rejected. With the implementation unchanged, fresh focused (131), packaged
    backend (394 in 31 suites), configured frontend, repository-policy and scan
    checks pass again; only final repaired-gate outcomes count as exit evidence.
    See the [re-verification record](15-quality-and-acceptance.md).
  - Exit checks: pure closed-payload/coercion/bounds/patch corpus; real PostgreSQL
    no-write rejection snapshots, old-request replay, mutable-input freezing, owner/
    player/recipient scope, mode/decision drift between admission and commit, actual
    campaign-lock contention at both boundaries, draw retention, multi-effect rollback
    and immutable committed replay. Run focused commands, full packaged backend and
    repository policy tests/scans; record actual results and a neutral owner commit
    before checking this task.
- [x] P07.3 Add resource, ownership, prerequisite, knowledge, and causal validation.
  - Package A — current-state rule gate: `CommandRules` runs after common semantics
    under the same campaign lock at admission and again in canonical commit. `MOVE`
    follows one disclosed `accessible` authored link from the player's current
    location; speech recipients and ability targets are other co-present campaign
    actors whose authored origin is not secret. Secret definitions fail closed until
    typed reveal records exist (P09.1). Concealment-sensitive failures share one
    sanitized category each. Matching retries and stored receipts replay first.
  - Package B — ability ownership, prerequisites and resources: register the closed
    `USE_ABILITY {abilityId, targetActorIds}` intention (at most eight distinct
    targets, no costs/rolls/actor overrides). Require a learned pinned ability, its
    prerequisite abilities and skill ranks, a legal `self`/`single`/`multiple` target
    count (`area` waits for P11 zones) and affordable nonzero costs in the creation
    profile's own resource vocabulary. Item, trade and encounter intentions stay
    unregistered until P10.1–P10.2/P11 supply their contracts.
  - Package C — pinned facts and causal attribution: compile an immutable prose-free
    rule catalog from the sealed pinned closure before the lock (16-revision cache,
    revision-bound, `CONTENT_UNAVAILABLE` only where definitions are needed). At
    commit, every resource change and event actor must belong to the intention's
    participants. No migration, controller, worker or browser action is added;
    calculations, cost spending and execution remain P07.4. See the
    [rule contract](07-turn-protocol.md#p073-current-state-rule-validation).
  - Verified evidence (27 October 2026): all 138 focused command cases (eight new
    command-rule cases) and all 401 packaged backend cases in 32 suites pass with zero
    failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6 fixture. Configured
    frontend installation, typechecking, 49 tests, four localization tests,
    240-message parity and both locale builds pass; all 22 repository-policy tests
    and zero-finding scans pass. Deliberate rule-weakening probes failed the intended
    cases and were reverted byte-identically. See the
    [verification record](15-quality-and-acceptance.md).
    Implementation owner commit: `1b9b0ee`; all exit checks passed on that exact
    tree before this completion mark. All 131 task identities/titles and the pending
    P98 follow-up are preserved; only P07.3 gains a completion mark.
  - Exit checks: real sealed root/dependency packs and production campaign creation;
    open/blocked/secret/unlinked/current/dependency destinations and missing location;
    secret/foreign/self/absent/out-of-range recipients; ownership, ability and skill
    prerequisites, every target policy and exact/insufficient/cross-vocabulary/missing
    costs; exact multi-table no-write snapshots for every rejection; commit-time
    re-derivation after target movement and spending; unattributable resource/event
    effects; admission and receipt replay before current rules; real campaign-lock
    contention at both boundaries; catalog scope, prose exclusion and revision binding.
    Run focused commands, full packaged backend, configured frontend gates and
    repository policy tests/scans; record actual results and a neutral owner commit
    before checking this task.
- [x] P07.4 Implement local action calculations, durable draws, events, receipts, and pending decisions.
  - Package A — local execution pipeline: `CommandExecutionService` admits, then moves a
    fresh operation `ACCEPTED → PLANNING → VALIDATING` under the campaign/journal locks,
    re-runs common semantics and current-state rules, reads a detached snapshot, calculates
    with the pure `LocalActionResolver`, reserves one durable draw per die and commits once.
    Rule drift after admission records `REJECTED`, other failures `FAILED_BEFORE_COMMIT`;
    both release the lease and keep reserved draws bound to the operation. Matching retries
    replay the stored status/receipt and never re-execute. No payload, worker or inference.
  - Package B — calculations and effects: `MOVE` commits a typed row-version-guarded
    `Placement`, `WAIT` advances exactly the requested seconds, `SPEAK` records the exact
    words, and `USE_ABILITY` pays costs exactly, then runs the compiled `EffectProgram`
    (`modifyResource`/`conditional`; `pre` before `post`; exact integer expression
    semantics; clamped, vocabulary-bound targets with explicit `NO_RESOURCE`). Effect types
    that need P09–P11 systems, programs above 128 draws and foreign-vocabulary value reads
    are refused before journaling as `EFFECT_UNSUPPORTED`. Durations are provisional until
    P10.4.
  - Package C — events, receipts and pending decisions: v1 `ACTOR_MOVED`, `TIME_PASSED`,
    `SPEECH_DELIVERED`, `ABILITY_USED`, `RESOURCE_SPENT` and `EFFECT_RESOLVED` events with
    calculation inputs and draw ordinals (internal when they hold other actors' values);
    one turn per action; the pending decision resolves to none, clearing the starting
    marker; receipts record calculations, net changes and committed draws; both outbox
    intents and `actor:<uuid>` query-epoch bumps commit atomically. The command and status
    HTTP routes return an allowlisted player-safe receipt projection. See the
    [execution contract](07-turn-protocol.md#p074-local-action-execution).
  - Exit checks: pure resolver corpus (operators, ordinals, timing, branches, costs, net
    merge, clamping, vocabulary targets, visibility, fail-closed unknown values,
    determinism); real PostgreSQL execution on sealed packs and production campaign
    creation for every intention with exact counters, events, outbox, epochs and lease
    release; draw/receipt agreement; replay before and after startup reconciliation;
    refused effect programs with exact no-write snapshots; trigger-injected failures after
    durable draws (event insert failure, concurrent rule-fact change) leaving no world
    effect; forged/stale placements; HTTP receipt allowlist and replay. Run focused
    commands, full packaged backend, configured frontend gates and repository policy
    tests/scans; record actual results and a neutral owner commit before checking this task.
  - Verified evidence (28 October 2026): all 151 focused command cases (seven pure
    resolver and six execution cases new) and all 414 packaged backend cases in 34 suites
    pass with zero failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6 fixture.
    Configured frontend installation, typechecking, 49 tests, four localization tests,
    240-message parity and both locale builds pass; all 22 repository-policy tests and
    zero-finding scans pass. A deliberate weakening of the effect-support rule and
    placement attribution failed the intended cases and was reverted byte-identically.
    See the [verification record](15-quality-and-acceptance.md). Implementation owner
    commit: `3174e5a`; all exit checks passed on that exact code before this completion
    mark. All 131 task identities/titles and the pending P98 follow-up are preserved;
    only P07.4 gains a completion mark.
- [x] P07.5 Add resumable public stream, cancellation race handling, and post-commit presentation jobs.
  - Package A — cancellation races: `OperationCancellationService` and
    `POST …/operations/{id}/cancel` take the campaign then journal lock like every journal
    writer. `ACCEPTED`/`PLANNING`/`VALIDATING` become `CANCELLED` and the permit is retired
    with a fence step, so the executing request's next prepare, draw or commit fails; a
    commit that won reports `ALREADY_COMMITTED` with its receipt and only stops optional
    narration; finished operations report `ALREADY_FINISHED`. Lifecycle gains
    `ACCEPTED → CANCELLED` and `COMMITTED → COMMITTED_NARRATION_PENDING`.
  - Package B — equivalent-attempt policy: admission records an attempt key (normalized
    intention plus base revision; ability targets sorted) and inherits the earliest
    equivalent attempt's draw stream; draw values derive from the stream, so cancelling or
    failing and resubmitting at the same revision reproduces the retained values.
  - Package C — resumable public stream: a V11 journal trigger appends one
    `campaign_stream_message` per status change in the changing transaction (head counter,
    1000-message retention); `PublicStreamService` and `GET …/stream` serve
    `generation:sequence` identities as bounded SSE long polls with `Last-Event-ID`,
    allowlisted rendering from immutable journal data, `stream.ready` and `stream.reset`.
  - Package D — post-commit presentation jobs: the commit inserts one fenced
    `presentation_job` per narration-requiring operation; `PresentationWorker` claims it
    with a lease, calls the runtime-neutral `NarrationPort` outside transactions (default
    `UNAVAILABLE` until P08) and settles job and journal together; bounded retries,
    interrupted-lease recovery, generation retirement and startup reconciliation resume
    owed narration without repeating the action. See the
    [delivery contract](07-turn-protocol.md#p075-public-stream-cancellation-and-presentation-jobs).
  - Exit checks: real PostgreSQL races holding the executing request after admission, after
    a durable draw and inside the commit transaction; fenced permits, retained draws,
    exact no-write snapshots, idempotent cancellation and scope hiding; deterministic
    draw-stream sharing for equivalent attempts after cancellation and failure and a fresh
    stream after a revision change; stream status mapping, ordering, resumption, identical
    re-rendering, generation/head/retention resets and allowlists; presentation delivery,
    unavailability, bounded retries, a late worker after lease loss, cancellation during
    narration, startup recovery and database guards; HTTP SSE framing, `Last-Event-ID`
    resumption, automatic background presentation and the cancel route's codes; V11
    upgrade without backfill. Run focused suites, full packaged backend, configured
    frontend gates, repository policy tests/scans and deliberate weakening probes; record
    actual results and a neutral owner commit before checking this task.
  - Verified evidence (28 October 2026): all 173 focused cases in 16 suites (six
    cancellation, four stream, seven presentation-job and three stream/cancel HTTP cases
    new) and all 435 packaged backend cases in 38 suites pass with zero
    failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6 fixture. Configured
    frontend installation, typechecking, 49 tests, four localization tests, 240-message
    parity and both locale builds pass; all 22 repository-policy tests, zero-finding scans
    and nine offline backup tests pass. Five deliberate weakening probes failed exactly
    the intended cases and were reverted byte-identically. See the
    [verification record](15-quality-and-acceptance.md). Implementation owner commit:
    `dd122c0`; all exit checks passed on that exact code before this completion mark.
    All 131 task identities/titles and the pending P98 follow-up are preserved; only
    P07.5 gains a completion mark. Narration (P08) and browser binding (P05.6/P05.7)
    remain open.
- [x] P07.6 Implement atomic plan admission with dependency/query-epoch rechecks, scope/content/author validation, fencing and idempotency; artifacts never directly change world facts/time/rewards, and activation uses ordinary commands under QA-36.
  - Package A — typed dependency rechecks: V12 adds the read-only evaluator
    `plan_reference_version` for the closed grammar `actor:`/`resource:`/`item:`/`character:`
    (ENTITY_FIELD) and `known-fact:`/`known-quest:` (FACT) plus a lowercase UUID, returning
    the row's storage version (null when absent or foreign), and `plan_dependency_fresh`
    combining it with query epochs. Admission requires exact versions and current epochs;
    predicates and CONSTRAINT rows still fail closed until P08.7. Every canonical commit
    re-evaluates admitted plans against the rows it changed (`DEPENDENCY_CHANGED` or
    `QUERY_EPOCH_CHANGED`); unrelated revisions change nothing.
  - Package B — scope, content and author validation: `PlanContentCatalogs` loads prose-free
    pinned facts before any lock. Under the campaign lock the gate (`plan-gate-v1`) checks the
    snapshot generation, public input without private reads or secret definitions, the pinned
    revision/pack/ruleset hashes, cited definitions, an author-constraint digest recomputed
    from the sealed root policy and cited required/fixed entities, and that every cited
    campaign entity is covered by a recorded dependency. Snapshot capture applies the
    audience and author checks too. Receipts store the gate version and sorted references.
  - Package C — fencing, idempotency and activation through ordinary commands: P03.6
    lease/fence/generation/result-digest checks and one receipt per attempt still come
    first. A trusted resolved commit may cite admitted branches; the canonical commit
    rechecks them live after its own revision/lease/rule checks and before any effect,
    rolls back on `PLAN_*` refusals and records each branch once in immutable
    `plan_activation` (deferred committed-origin trigger). Matching retries replay first.
    No player intention, HTTP route or job can cite a plan; the plan supplies no effect and
    receipts never name it. See the
    [admission contract](07-turn-protocol.md#p076-plan-admission-and-activation).
  - Exit checks: real PostgreSQL on sealed packs and production campaign creation with
    ordinary WAIT/MOVE/USE_ABILITY commands: eight unrelated commits keep a plan admissible;
    a witness change invalidates only its branch while the independent branch activates
    once; a destroyed object and a knowledge-scope phantom reject stale admission and
    invalidate admitted plans; the live recheck at use wins over an earlier validation; an
    admission racing a move never leaves a usable stale artifact; foreign, unrecorded,
    absent, unknown-content, unconstrained-author, unsupported, secret-in-public and
    legacy-snapshot inputs reject without writes; exact world-row snapshots around every
    admission, invalidation and refused activation; activation commit fault injection at
    COMMIT, replay, second-use and storage guards; V12 upgrade without backfill. Run
    focused suites, full packaged backend, configured frontend gates, repository policy
    tests/scans, offline backup tests and deliberate weakening probes; record actual
    results and a neutral owner commit before checking this task.
  - Verified evidence (28 October 2026): all 155 focused cases in 13 suites (seven
    plan-gate and one resolved-commit case new) and all 444 packaged backend cases in 40
    suites pass with zero failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6
    fixture. Configured frontend installation, typechecking, 77 tests, five localization
    tests, 317-message parity and both locale builds pass; all 26 repository-policy tests,
    zero-finding scans and nine offline backup tests pass. Seven deliberate weakening
    probes failed exactly the intended cases and were reverted byte-identically. The
    container-packaging suite still needs Docker (four `FileNotFoundError: 'docker'`
    errors, unchanged scope). See the [verification record](15-quality-and-acceptance.md).
    Implementation owner commit: `c598429`; all exit checks passed on that exact code
    before this completion mark. All 131 task identities/titles and the pending P98
    follow-up are preserved; only P07.6 gains a completion mark. Every P07 task is now
    checked; the package exit remains bounded by later acceptance work: the quest-plan
    contract, predicate/constraint evaluators and plan consumers (P08.7, P09.1, P10),
    narration agency (QA-07, P08) and dispatch/budgets (QA-37, P08.6).

**Exit:** untrusted proposals cannot mutate state directly; retries and crashes have deterministic outcomes. This gate precedes real AI-driven mutations. **Checks:** QA-03–07, QA-17–20, transaction fault injection. **Suggested commit:** `Enforce transactional command validation and idempotency`.

## 11. P08 — Codex orchestration and grounded narration

**Depends on:** P01 and P07. **Areas:** runtime-neutral AI task port, Codex adapter, versioned runtime prompts, UI status.

- [x] P08.1 Implement the supported Java Codex transport behind a runtime-neutral narration task port with logical `light`, `standard` and `deliberate` slots ([ADR 0020](adr/0020-selectable-narration-runtime.md), [ADR 0021](adr/0021-inference-backends-and-model-selection.md)), with bounded queues, request/turn correlation, and lifecycle management; no Codex-specific type may leak into domain or application services.
  - Package A — neutral port: `org.rpgengine.inference` defines `NarrationTaskPort`
    (`submit`, `retireSession`, `status`), `TaskRequest` (task/logical-session identity,
    `InferenceSlot` `light`/`standard`/`deliberate`, bounded instructions and JSON output
    schema, 1–180 s budget), `TaskResult` (status, untrusted output, fixed error code,
    runtime/version/model/effort provenance, fresh-session flag), `RuntimeStatus` and
    `SlotAssignments` with interim configuration-backed settings (no shipped model names;
    P08.4 resolves them against the backend catalog). `UnavailableTaskPort` is the default when no runtime is configured;
    the `narrationRuntime` health component is UP only for a READY runtime.
  - Package B — Codex wires and generation: package-private `org.rpgengine.inference.codex`
    with the packaged Unix-socket WebSocket client (`UnixRelay.java` rules) and the owned
    stdio child (absolute executable, environment allowlist, redacted stderr, bounded
    close/terminate/kill shutdown); one RPC generation per connection with a single reader,
    monotonic IDs, at most 8 pending requests, server-request denial, early-event buffering
    while a start is in flight and exact (thread, turn) inboxes; unresolved requests, wire
    errors and protocol violations close the generation.
  - Package C — admission and lifecycle: bounded FIFO queue (`queue-full`, duplicate
    rejection, queue time inside the budget), one dispatcher holding the serialized P01.7
    permit, handshake/account/catalog readiness with backoff, slot-to-catalog check and
    explicit model/effort/policy/schema on every turn, session-to-thread binding with resume
    across generations, cancellation/deadline/shutdown interruption awaiting the terminal
    status, ambiguity retirement and resume/list/interrupt reconciliation before further
    work. See [docs/06](06-codex-integration.md#p081-production-adapter).
  - Exit checks: replay the retained 0.164.0 neutral fixture against the Java builders,
    validators and router; adapter scenarios against a scripted app-server over in-memory,
    real child-process stdio and real Unix-socket WebSocket transports (explicit sticky
    settings, early/foreign/late events, queue bounds, cancellation, deadlines, failures,
    tool/reroute/approval violations, lost connection and unconfirmed interrupt with
    reconciliation, sign-in/version/reachability gating, shutdown); RPC generation bounds
    and fail-closed rules; Spring selection and startup refusals; a source/reflection
    boundary test proving no package outside the adapter names it and the neutral API
    exposes only neutral types; deliberate weakening probes; the full packaged backend and
    repository checks. A live runtime/account smoke is not part of this task (no runtime,
    account or Docker in this environment); it stays the opt-in P08 package check.
  - Verified evidence (28 October 2026): 46 focused cases in seven new suites (57 with the
    application, packaged-secret and packaged-interface suites) and all 490 packaged backend
    cases in 47 suites pass with zero failures/errors/skips on the scoped JDK 21/PostgreSQL
    18.6 fixture, and the jar is packaged. The fixture replay rebuilds every recorded
    thread/turn/interrupt/resume request byte-for-byte and yields the recorded terminal
    statuses. Nine deliberate weakening probes failed exactly their intended cases and
    were reverted byte-identically: omitted effort, thread-only correlation, any item
    allowed, server requests ignored, unbounded queue, interrupt acknowledgment trusted,
    reconciliation skipped, inherited environment and unchecked effective policy. All 26
    repository-policy tests pass with zero scan findings. Frontend sources are unchanged
    and its gates were not rerun. Not verified: a live runtime, an account or the packaged
    Docker socket (none available here); packaged gameplay keeps the transport disabled
    until P08.5. See the [verification record](15-quality-and-acceptance.md).
    Implementation owner commit: `4c46d9f`; all exit checks passed on that exact code
    before this completion mark. All 131 task identities/titles and the pending P98
    follow-up are preserved; only P08.1 gains a completion mark.
- [x] P08.2 Implement typed intent and attributed narration/quick-reply contracts with structural/semantic validation and one bounded repair; generate directly in selected English/Italian, preserve glossary/locale generations, player agency and public knowledge scope.
  - Package A — envelope and binding: `org.rpgengine.inference.contract` renders a versioned
    request envelope (task, kind, role, public audience, adventure/campaign, base revision,
    SHA-256 context digest, output locale, locale and glossary revisions, pack/policy hashes)
    with English templates; the response must echo every identity (`echo-mismatch`), and
    `TaskBinding.presentationCurrent` rejects output after a locale switch or glossary edit.
  - Package B — intent contract: one MOVE/WAIT/SPEAK/USE_ABILITY proposal over supplied
    options for the acting character (exact per-kind fields, verbatim player speech, known
    destinations/recipients/abilities/targets/facts, wait bound), a bounded clarification
    or cannot-complete; the result is a `TypedCommand` that still needs ordinary admission.
  - Package C — narration contract: one to eight attributed passages (no player voice,
    known speakers, cited events/facts, every event narrated, permitted numbers only),
    the echoed pending decision and up to three editable suggestions over its available
    actions; privileged markers, wrong glossary forms, altered protected quotes and a
    dominant other language are rejected, and a context that already contains a marker is
    refused before submission.
  - Package D — single repair: `ContractRunner` submits through `NarrationTaskPort`,
    validates the untrusted output and repairs once with the same context, session and slot,
    the remaining budget and only violation codes; transport outcomes are never repaired
    and cancellation stops the repair. Bundled schemas constrain generation only. See
    [docs/21 section 14](21-ai-task-contracts-and-prompts.md#14-implemented-intent-and-narration-contracts-p082).
  - Exit checks: valid EN and IT outputs per contract; one single-aspect invalid output per
    structural and semantic rule (the invalid-output corpus for these contracts);
    schema/validator field-set drift; repair content, budget and limit; no repair for
    transport outcomes; cancellation; scope refusal; stale binding detection; deliberate
    weakening probes; the full packaged backend and repository checks. Gameplay wiring and
    publication (P08.5), thread scoping (P08.3), a live runtime smoke and human EN/IT review
    are not part of this task.
  - Verified evidence (28 October 2026): 35 focused cases in four new suites and all 525
    packaged backend cases in 51 suites pass with zero failures/errors/skips on the scoped
    JDK 21/PostgreSQL 18.6 fixture, and the jar is packaged. Fifteen deliberate weakening
    probes (non-verbatim speech, a second intention, unchecked targets, scope leak, context
    scope, glossary, locale, digest echo, numbers, event coverage, pending decision, player
    voice, unbounded repair, repaired transport outcomes, echoed rejected output) each made
    the focused run fail and were reverted byte-identically. All 26 repository-policy tests
    pass with zero scan findings. Frontend sources are unchanged and its gates were not
    rerun. Not verified: live output from a runtime and human EN/IT narrative quality. See
    the [verification record](15-quality-and-acceptance.md). Implementation owner commit:
    `3caa39b`; all exit checks passed on that exact code before this completion mark. All
    131 task identities/titles and the pending P98 follow-up are preserved; only P08.2
    gains a completion mark.
- [x] P08.3 Scope threads by campaign/role/audience/policy; separate privileged planning from public narration.
  - Package A — scope key and role matrix: `org.rpgengine.inference.session` defines
    `SessionScope` (adventure, campaign, recovery generation, `AiRole`, `Audience`
    `public`/`character:<ref>`/`privileged`, pack and policy hashes); tasks share a runtime
    thread only within one scope. The player-facing `interpreter`, `narrator` and `guide`
    roles never run privileged; `quest-planner`, `world-director`, `plan-reviewer`,
    `content-proposer` and `encounter-proposer` run only privileged (the planning-snapshot
    `PLANNER_PRIVATE` audience), so no scope can serve both planning and narration.
  - Package B — scoped registry: `ScopedSessions` is the only main-code submitter of the task
    port. It reuses equal scopes, retires the campaign's older-generation sessions and the
    same campaign/role/audience line under other hashes, rejects a stale generation
    (`stale-campaign-generation`) or a campaign under two adventures
    (`session-scope-conflict`) without sending anything, issues fresh random identifiers,
    bounds active sessions (`engine.inference.sessions.capacity`, default 256) and forwards
    every retirement (`retire`, `retireCampaign`, `retireAll`, supersession, eviction) to
    `NarrationTaskPort.retireSession`. It is in memory, like the adapter's bindings.
  - Package C — contract integration: `ContractRunner.start` no longer accepts a session; it
    derives the scope from the task kind's role and audience and the binding, which gains
    `campaignGeneration` (rendered in the envelope with the kind's role and audience). A
    stale generation ends `NotCompleted(REJECTED)` without repair. See
    [docs/06 section 7](06-codex-integration.md#p083-session-scoping).
  - Exit checks: role-matrix and scope-key tests; registry reuse, separation, supersession,
    generation fence, adventure conflict, explicit/campaign/installation retirement, capacity
    eviction and concurrent use; a source/reflection boundary (only the registry builds
    requests or calls the port; the runner takes no port or session); runner-derived
    sessions and envelope fields; privileged-to-public handoff scenarios through the real
    Codex adapter and the scripted app-server with a secret sentinel; Spring wiring and the
    capacity bound; deliberate weakening probes; the full packaged backend and repository
    checks. Durable session records and renewal triggers (P09.3/P09.4), filtering of public
    contexts (P08.5/P09.2), the durable fair queue (P08.6), paraphrase detection and a live
    runtime smoke are not part of this task.
  - Verified evidence (28 October 2026): 23 new cases (22 in five new suites, one in the
    Spring selection suite; 79 focused cases with the existing contract, boundary and
    adapter suites) and all 548 packaged backend cases in 56 suites pass with zero
    failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6 fixture, and the jar is
    packaged. Through the real adapter, a planner, narrator, interpreter and NPC tactician of
    one campaign get four distinct threads and a planner sentinel is sent exactly once, on
    the planner's thread. Fifteen deliberate weakening probes (campaign-wide session,
    privileged narrator, unchecked role matrix, no supersession, no generation fence,
    unchecked adventure conflict, unforwarded explicit retirement, supersession or eviction,
    over-broad campaign retirement, a diagnostic read that reorders eviction, interpreter and
    narrator sharing a role, generation or policy outside the scope, a request built outside
    the registry) each made the focused run fail and were reverted byte-identically. All 26
    repository-policy tests pass with zero scan findings. Frontend sources are unchanged and
    its gates were not rerun. Not verified: a live runtime or account, and leakage through
    paraphrase or through what a caller places in a public context. See the
    [verification record](15-quality-and-acceptance.md). Implementation owner commit:
    `a55319f`; all exit checks passed on that exact code before this completion mark. All
    131 task identities/titles and the pending P98 follow-up are preserved; only P08.3
    gains a completion mark.
- [x] P08.4 Resolve logical profiles through the owner's slot assignment (backend and model per slot, ADR 0021) against the assigned backend's catalog or approved allowlist, and set effective settings on every turn.
  - Package A — owner assignment: `SlotAssignments` returns a `SlotAssignment` per text slot
    (backend from the closed ADR 0021 text set, preferred model and effort, at most four
    owner-approved same-backend alternatives). Private settings
    `engine.inference.slots.<slot>.model`/`.effort`/`.backend`/`.alternatives` (the backend
    defaults to `engine.inference.backend`, itself `codex`); half-assigned slots, unknown
    backends and malformed or duplicate pairs fail startup without echoing a value.
  - Package B — resolver: `ProfileResolver` applies, for every task and with no carried state,
    the owner's `fixedQuality` slot (`engine.inference.fixed-quality`), the assignment
    (`slot-unassigned`), the backend match (`slot-backend-mismatch`, never rerouting across
    providers or classes), a catalog source (`catalog-unavailable`) and then the preferred pair
    and the approved alternatives in order against the discovered catalog and the owner's
    allowlist `engine.inference.allowlist.<backend>` (`slot-unsupported`). A degraded run is
    recorded as `Provenance.degradedFrom`.
  - Package C — adapter integration: the Codex adapter passes its discovered `model/list`
    catalog and backend name and sends the resolved model and effort in `thread/start` and in
    every `turn/start`; refusals end `FAILED` before any thread or turn exists. See
    [docs/06 section 9](06-codex-integration.md#p084-profile-resolution).
  - Exit checks: resolver tests (per-slot resolution without inheritance, unassigned slot,
    backend mismatch, discovered-catalog and allowlist gates, allowlist-only backend,
    ordered approved degradation and its provenance, `fixedQuality`, live reassignment,
    value-free validation errors); settings parsing and Spring wiring tests; the real Codex
    adapter against the scripted app-server (explicit settings per turn, reassignment between
    turns of one thread, degradation, allowlist narrowing, backend mismatch and
    `fixedQuality`, each refusal sending no thread or turn); the neutral-API boundary test;
    deliberate weakening probes; the full packaged backend and repository checks. The owner
    settings API and panel, advisor and readiness probes (P21.3), the `image` slot, the Claude
    and local adapters (P20, P21) and a live-account catalog check are not part of this task.
  - Verified evidence (28 October 2026): 26 new cases (14 resolver, 5 settings, 6 through the
    real Codex adapter, one in the Spring selection suite; 85 focused cases with the existing
    adapter, wire, boundary and session suites) and all 574 packaged backend cases in 59
    suites pass with zero failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6 fixture,
    and the jar is packaged. Through the real adapter an owner reassignment between two turns
    of one thread is sent explicitly on the next turn, an approved alternative runs and is
    recorded as `degradedFrom`, and unsupported, allowlist-narrowed and other-backend slots
    end `FAILED` with no thread or turn started. Thirteen deliberate weakening probes
    (unchecked backend, ignored `fixedQuality`, allowlist or discovered catalog, unreported
    degradation, skipped or reversed alternatives, accepted missing catalog, adapter catalog
    bypass, dropped provenance, wrong adapter backend, ignored slot backend and alternatives
    settings) each made the focused run fail and were reverted byte-identically. All 26
    repository-policy tests pass with zero scan findings. Frontend sources are unchanged and
    its gates were not rerun. Not verified: a live runtime or account catalog and the Claude
    and local backends. See the [verification record](15-quality-and-acceptance.md).
    Implementation owner commit: `fb77cf0`; all exit checks passed on that exact code before
    this completion mark. All 131 task identities/titles and the pending P98 follow-up are
    preserved; only P08.4 gains a completion mark.
- [x] P08.5 Publish only validated final narration; add deterministic receipt fallback and narration-only retry.
  - Package A — grounded publisher: `org.rpgengine.narration` assembles the public narration
    context of one committed operation in one read-only snapshot (its `PUBLIC` events only,
    stated deterministically in English or Italian, the addressed characters as speakers, the
    generic free-action decision, the campaign generation, revision, locale and glossary
    revisions, content hash and public policy hash) and `GroundedNarration`, the engine's
    `NarrationPort`, runs the P08.2 contract in the public narrator session on the `standard`
    slot with the 90-second budget and its single repair. An unready runtime answers
    `UNAVAILABLE` without reading or submitting anything.
  - Package B — publication and receipt fallback: V13 `narration_publication` (immutable, one
    per operation) is written only by the settling transaction of the delivering job, only
    while the campaign's locale and glossary revisions still equal the binding's; stale
    narration is discarded and the attempt counts as failed. Output still invalid after the
    repair, a declined answer or an unnarratable bundle end the round `NARRATION_FAILED` at
    once and the committed player-safe receipt is the presentation. The attempt lease is 120
    seconds. See [docs/07 section 6](07-turn-protocol.md#p085-publication-receipt-fallback-and-narration-only-retry).
  - Package C — narration-only retry and view: up to three retry rounds per operation, one in
    flight and one delivered at most (V13 partial unique indexes); a round never changes the
    journal, re-runs the action, rolls a draw, writes a receipt or world row or appends a
    stream message, and cancellation stops it. Every operation body carries `presentation`
    (`IN_PROGRESS`, `NARRATED` with backend-assigned message identities, or `RECEIPT` with its
    reason and remaining retries); `POST .../operations/{id}/narration/retry` queues a round.
    See [docs/13](13-api-and-ui.md#p085-narration-presentation-and-retry).
  - Exit checks: real-PostgreSQL tests through the real job service, assembler, contract
    runner, scoped sessions and narration contract with only the runtime scripted (single
    publication with trusted speaker names, public narrator scope, no `INTERNAL` event in any
    instruction, one repair then the receipt, a repaired publication, declined and
    unnarratable bundles, unavailable runtime, locale switch during narration, retry without
    any world/receipt/draw/stream change, bounded and cancellable rounds, database guards);
    the HTTP retry and presentation view; the adapted P07.5 job, stream and cancellation
    suites; the V13 upgrade step; deliberate weakening probes; the full packaged backend and
    repository checks. Browser rendering and the Retry control (P05.6/P05.7), a stream message
    for retry rounds, privileged phrase sets and richer contexts (P09.2), regeneration after a
    runtime switch (P20.2) and live narration quality (QA-07, EN/IT human review) are not part
    of this task.
  - Verified evidence (28 October 2026): 9 new cases (8 in `GroundedNarrationTest`, one HTTP
    retry case) plus the adapted job, stream, cancellation, interface-contract and migration
    suites (36 focused cases in 7 suites) and all 583 packaged backend cases in 60 suites pass
    with zero failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6 fixture, and the jar is
    packaged. Through the real job service, assembler, runner, sessions and contract with only
    the runtime scripted: one publication per operation with trusted speaker names and
    backend-assigned message identities, the public narrator scope, no `INTERNAL` payload in any
    instruction, one repair then the receipt (`NARRATION_FAILED`, three retries left), a repaired
    publication, declined, unnarratable and unavailable rounds, an Italian re-narration after a
    locale switch during narration, and retry rounds that leave world rows, receipts, draws, the
    journal and the stream unchanged, are idempotent, bounded to three and cancellable. Fifteen
    deliberate weakening probes each made the focused run fail and were reverted
    byte-identically. The recorded interface transcript gained only the `presentation` field;
    frontend installation, typechecking, 77 tests, the localization test and both locale builds
    (317-message parity) pass with it. All 26 repository-policy tests pass with zero findings.
    Not verified: live runtime narration, browser rendering of passages and the Retry control,
    and human EN/IT review of the event statements. See the
    [verification record](15-quality-and-acceptance.md). Implementation owner commit:
    `32edd5e`; all exit checks passed on that exact code before this completion mark. All 131
    task identities/titles and the pending P98 follow-up are preserved; only P08.5 gains a
    completion mark.
- [x] P08.6 Implement one durable fair priority queue with isolated logical role/audience threads, interactive reservations, bounded quota/backoff/cancel/restart handling and conservative single-active-turn default; higher concurrency requires P01.7 evidence and QA-37.
  - Package A — ledger and permit: V14 `inference_task` records every admitted task
    (identities, campaign, generation, role, audience, work class, slot, outcome code,
    owning process, estimated and charged usage, an internal write-ahead runtime reference,
    preemption mark; never instructions or output) through `QUEUED`, `DISPATCHED`,
    `RECONCILING` and terminal states under a guard trigger, and `inference_scheduler_state`
    holds the durable account backoff. The partial unique index `inference_task_one_turn_uk`
    keeps one dispatched or reconciling task installation-wide, also across processes;
    `engine.inference.scheduler.max-active` above 1 fails startup (`concurrency-unqualified`).
  - Package B — fair selection: the pure `FairQueue` policy serves interactive work first,
    runs one task at a time per campaign/role/audience line in admission order, ages
    background work (never into the interactive class), rotates campaigns fairly and keeps
    the interactive permit reservation; `InferenceScheduler` admits through separate
    interactive and background queue places, rolling 24-hour usage quotas and campaign
    scope/generation/identity checks, dispatches through `ScopedSessions` with the remaining
    budget, and preempts background work by exact handle, keeping the permit until its
    terminal result.
  - Package C — failures and restart: cancellation of waiting and running work, queue
    deadlines, `RECONCILING` holds until the port confirms an unconfirmed outcome, a durable
    jittered backoff of background work after account conditions, startup recovery
    (abandon earlier queued rows, take over dispatched ones with the permit held, hand their
    references to `NarrationTaskPort.reconcile`), ownership fencing, and the Codex adapter's
    write-ahead `DispatchJournal` record before `turn/start`, restart-thread reconciliation
    and unconfirmed threads counted as active. `ContractRunner` and grounded narration submit
    through the scheduler. See [docs/06 section 12](06-codex-integration.md#p086-durable-fair-scheduling).
  - Exit checks: pure policy tests (interactive priority, line isolation and order, rotation,
    single- and multi-permit reservation, aging bounds, suspension, held permits, preemption
    victim); real-PostgreSQL scheduler tests through the real ledger, sessions and scheduler
    with only the runtime scripted (durable transitions, one turn at a time, preemption with
    the permit kept until terminal, campaign rotation, bounded places, quotas surviving a
    restart and releasing unstarted turns, durable backoff, cancellation, queue deadlines,
    reconciliation holds, restart recovery with fencing, two processes on one database, scope,
    generation and identity checks, the journal refusal, shutdown, database guards); the real
    Codex adapter against the scripted app-server (write-ahead record before `turn/start`, no
    turn without it, restart threads reconciled before the first turn); the boundary, Spring
    wiring and settings refusals; the adapted contract, narration and V14 migration suites;
    deliberate weakening probes; the full packaged backend and repository checks. Live
    qualification of more than one turn, live quota and priority behavior, background task
    kinds (P08.7, P09.3, P12, P15), owner-facing status and settings (P21.3) and observed token
    accounting are not part of this task.
  - Verified evidence (28 October 2026): 31 new cases (10 pure policy, 15 real-PostgreSQL
    scheduler, 3 boundary, 2 through the real Codex adapter, one Spring wiring case; 92 focused
    cases with the adapted contract, narration, session, presentation, schema and V14 migration
    suites) and all 614 packaged backend cases in 63 suites pass with zero failures/errors/skips
    on the scoped JDK 21/PostgreSQL 18.6 fixture, and the jar is packaged. Through the real
    ledger, V14 guards, sessions and scheduler with only the runtime scripted: one turn at a
    time also across two processes, interactive preemption with the permit kept until the
    interrupted turn's terminal result, campaigns taking turns, separate bounded queue places,
    quotas that charge only started turns and survive a restart, a durable 30/60-second backoff
    of background work that a completed task clears, cancellation, queue deadlines,
    reconciliation holds, restart recovery that abandons queued rows and confirms the recorded
    thread before new work while fencing the old process, scope, generation and identity
    refusals, shutdown and the database guards; the real adapter records the thread before
    `turn/start`, starts no turn without the record and reconciles an earlier process's
    running turn before its first new turn. Twenty-four deliberate weakening probes each made
    their targeted suites fail and were reverted byte-identically (one probe definition was
    first ineffective and was corrected). Frontend sources and the interface transcript are
    unchanged. All 26 repository-policy tests pass with zero findings. Not verified: more than
    one active turn, live quota, rate-limit, priority and cancellation behavior (no runtime or
    account was used), observed token usage and the not yet existing background task kinds.
    See the [verification record](15-quality-and-acceptance.md). Implementation owner commit:
    `e2d0ffc`; all exit checks passed on that exact code before this completion mark. All 131
    task identities/titles and the pending P98 follow-up are preserved; only P08.6 gains a
    completion mark.
- [x] P08.7 Add the versioned bounded quest-plan contract and selective dependency validation/replanning with a playable no-plan baseline; prove contract/admission/fallback with deterministic fixtures. A live investigation comparison may be deferred/unavailable without blocking MVP; no forced player choice or waiting for a whole arc.
  - Package A — versioned contract: `quest-plan` and `quest-plan-refine` task kinds of the
    privileged `quest-planner` role (`PREPARATION` work class, 60-second budget), one
    `QuestPlanContract` with the bundled `ai-contract/1/quest-plan.schema.json` (statuses
    `planned`/`cannot-plan`), validator `quest-plan-validator-v1`, backend-owned plan bindings,
    backend-written `validationMetadata`, the closed typed `PlanPredicate` vocabulary
    (`all`/`any`/`not`, `actor-at`, `resource-at-least`, `item-available`, `fact-known-to`,
    `quest-status`, `world-time-window`; unevaluated `actor-status`, `relationship-band` and
    `location-reachable` rejected) and the caps of docs/28 section 6 (24 nodes, 8 branches,
    causal depth 6, predicate depth 4 and 16 leaves). No field can express a player action, and
    every plan states a no-progress fallback.
  - Package B — selective dependency validation: the validator evaluates predicates against
    the snapshot (a false root rejects the output; a false branch guard or a dropped node with
    its causal descendants rejects only the affected branch) and builds a citation map. The
    planner narrows each recorded read to the branches whose predicates cite it, keeps root or
    prose-only reliance whole-artifact and stores never-referenced reads as observed only. The
    P07.6 gate accepts only such narrowing (no observation dropped or altered, no captured
    branch guard weakened, no cited entity left unguarded), and invalidation ignores
    observed-only rows. `QuestPlanAssembler` reads one known quest's privileged slice in one
    repeatable-read transaction, records row versions and actor query epochs and refuses an
    oversized slice instead of truncating it.
  - Package C — replanning and baseline: `QuestPlanning` requests (deduplicated, never waits),
    runs jobs through the fair scheduler and the transactional gate with bounded attempts,
    discards results after a cancellation request and requests one `refine-v<version>` job per
    stale latest version within `engine.planning.replan-allowance`. Unrelated revisions request
    nothing. `standing` reports `PREPARED` usable branches or `BASELINE` with a reason, and
    `QuestPlanWorker` runs only when enabled. Planning is off by default (Economy). No command,
    commit, narration or presentation code depends on planning, and narration and presentation
    never read preparation records. See
    [docs/28 sections 6–7](28-asynchronous-narrative-planning.md#selective-narrowing-and-replanning-p087).
  - Exit checks:
    - Pure contract fixtures: bilingual acceptance, citation map, selective rejection and an
      invalid-output corpus with one fixed code per corruption; the schema drift test.
    - Real PostgreSQL suites over sealed packs, production campaign creation and the real
      assembler, jobs, ledger, scheduler, gate and commands, with only the planner runtime
      scripted:
      - the disabled default, and play continuing while a plan is pending, failed, declined
        or invalidated;
      - privileged `PREPARATION` dispatch;
      - selective severities, no canonical change and no secret in any public sink;
      - unrelated revisions keeping the plan;
      - a witness change invalidating only its branch, with one refinement superseding the
        predecessor and the allowance bounding later changes;
      - a root change invalidating the whole plan;
      - a change during generation rejected by the gate and then regenerated;
      - cancellation, an unknown quest and a worker pass.
    - Gate narrowing refusals; the planning boundary and settings tests; deliberate weakening
      probes; the full packaged backend and repository checks.
    - Not part of this task: a live planner run and the paired AP-12 investigation comparison
      (deferred to P14, optional there, and P18.6; it does not block the MVP), consumers that
      build commands from plans (P09.1, P10), speculative preparation, world direction, plan
      review and location-membership query epochs.
  - Verified evidence (28 October 2026): 52 new cases (38 pure contract, one schema drift, 7
    real-PostgreSQL planning, one gate narrowing, 3 boundary, 2 settings; 62 cases in those six
    suites) and all 666 packaged backend cases in 67 suites pass with zero failures/errors/skips
    on the scoped JDK 21/PostgreSQL 18.6 fixture, and the jar is packaged. With only the
    planner runtime scripted, over sealed packs, production campaign creation and the real
    assembler, jobs, scheduler ledger, gate and commands:
    - the default is disabled and writes nothing while commands commit, and play continues
      while a plan is pending, failed, declined or invalidated;
    - planner tasks run as `quest-planner`/`privileged`/`PREPARATION`;
    - witness reads guard only the interview, the kit only the document route and the quest
      the whole plan, and the player's own rows are observed only;
    - admission changes no canonical row, and the secret proposal reaches no public sink;
    - eight unrelated commands keep the plan;
    - a witness change invalidates only its branch, requests exactly one refinement, which
      supersedes version 1, and a spent allowance requests nothing more;
    - a quest change invalidates the whole plan;
    - a change during generation is rejected by the gate and the regeneration is admitted;
    - cancellation discards the result, an unknown quest fails closed, and one worker pass
      replans and runs the refinement.
    Twenty-eight single-aspect corrupt outputs each fail with their own code. Twenty-one
    deliberate weakening probes each made their targeted suites fail and were reverted
    byte-identically. Frontend sources and the interface transcript are unchanged. All 26
    repository-policy tests pass with zero findings. Not verified: a live planner run and the
    AP-12 comparison (deferred to P14, where it is optional, and P18.6; not an MVP blocker),
    plan consumers (P09.1, P10) and location-membership phantoms. See the
    [verification record](15-quality-and-acceptance.md). Implementation owner commit:
    `a984b05`; all exit checks passed on that exact code before this completion mark. All 131
    task identities/titles and the pending P98 follow-up are preserved; only P08.7 gains a
    completion mark.

**Exit:** an action runs through Codex interpretation when needed, deterministic resolution, save, and grounded narration without raw tool logs. **Checks:** protocol fixtures, invalid output corpus, no-secret projection tests, opt-in neutral real smoke. **Suggested commit:** `Integrate validated narrative orchestration`.

## 12. P09 — Layered memory and renewable AI context

**Depends on:** P08. **Areas:** knowledge, memory, retrieval, AI sessions, resume UI.

- [x] P09.1 Implement typed facts, beliefs, provenance, scopes, current/last-known location, and unresolved commitments.
  - Package A — typed records (V15, `org.rpgengine.knowledge`): `knowledge_claim` (typed
    `EXISTS`/`PROPERTY`/`RELATION`/`STATEMENT` proposition about an `actor:`/`item:` row or a
    `location:`/`definition:` content id, statement language, `COMMON`/`HOLDERS` disclosure,
    privileged `TRUE`/`FALSE`/`UNDETERMINED` truth resolved at most once, supersession interval),
    `knowledge_belief` (holder stance `KNOWS`/`BELIEVES`/`RUMORED`/`SUSPECTS`/`DOUBTS`,
    acquisition `OBSERVED`/`TOLD`+informant/`READ`+document/`INFERRED`, one current belief per
    holder and claim, `KNOWS` only on a current `TRUE` claim), `actor_sighting` (last known
    location and observed absence, separate from `actor.location_id`) and `actor_commitment`
    (`OPEN` promise/obligation/agreement with exact terms and language, optional deadline, one
    terminal resolution). Every record cites an event of its own operation at its own revision
    (database-checked); frozen propositions, write-once closures, monotonic sightings and no
    deletes are enforced by triggers.
  - Package B — commit integration: `ResolvedCommit.knowledge()` carries typed
    `KnowledgeChange` values; `KnowledgeLedger.applyWithin` writes them under the campaign lock
    and rechecks row versions, open status, campaign references and real locations of observed
    sightings; registered intentions may only touch their participants' knowledge; after every
    committed registered intention the player observes perceivable co-present actors and
    records absences (none without a pinned catalog, a location or with more than 64 present).
    Writers bump `knowledge:claims`, `knowledge:<actor>`, `sightings:<actor>` and
    `commitments:<actor>` and step row versions; `plan_reference_version` evaluates `claim:`,
    `belief:` and `commitment:` references.
  - Package C — reveals and audience views: `CommandRules` discloses a secret location or NPC
    origin only through a current `TRUE` `EXISTS` claim that is `COMMON` or that the player
    `KNOWS`/`BELIEVES`; `KnowledgeView.forActor` returns one actor's beliefs without truth,
    common claims, own location, last known locations (never real ones) and visible open
    commitments with derived lateness; `KnowledgeView.privileged` returns truth, intervals,
    all beliefs, real locations and resolved commitments. See
    [docs/08 section 4](08-memory-and-continuity.md#p091-typed-knowledge).
  - Exit checks:
    - Real PostgreSQL suite over a sealed pack, production campaign creation and the real
      admission, rules, resolver and commit: truth/belief separation and provenance, rollback
      of every refused premise, typed-only reveals, perception versus real location, scoped
      commitments with derived lateness, participant attribution, database guards and view
      scoping.
    - The V15 upgrade step; deliberate weakening probes; the full packaged backend and the
      repository checks.
    - Not part of this task: context envelopes and retrieval (P09.2), the `reveal` effect and
      plan consumers (P10), NPC-side observation and commitment witnesses (P11/P15), player
      panels (P10.5) and retiring the free-text `campaign_known_fact` projection.
  - Verified evidence (28 October 2026): 8 new real-PostgreSQL cases in `KnowledgeLedgerTest`, the
    V15 step of `CanonicalMigrationUpgradeTest`, and all 674 packaged backend cases in 68 suites
    pass with zero failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6 fixture; the jar is
    packaged. Over a sealed pack, production campaign creation and the real admission, rules,
    resolver and commit:
    - holder views carry stance and acquisition but no truth, another holder's belief never
      leaks, and determination, belief revision and claim supersession record exact event
      sequences, row versions and scope epochs;
    - ten refused premises each roll back the whole operation, including earlier valid writes;
    - rumors, false or undetermined claims, another actor's knowledge, superseded beliefs and
      free-text known facts never reveal; a believed true claim opens the secret cellar and a
      common true claim the secret lurker;
    - observation records the public warden and not the concealed lurker, the last known
      location stays put when the warden really leaves, a later look records the absence and a
      new sighting replaces it; a reported sighting keeps the informant's wrong account;
    - commitments reach only their parties or everyone when common, become overdue without any
      write, resolve once and refuse reopening, rewriting and deletion;
    - a registered intention cannot write an uninvolved actor's knowledge.
    Twenty-one deliberate weakening probes each made the suite fail and were reverted
    byte-identically. The two-process scheduler test, which failed intermittently by completing
    a task before its port registered it, now waits for the registration (8/8 clean reruns).
    All 26 repository-policy tests pass with zero findings. Not verified: context assembly over
    these views (P09.2), any writer from play (`reveal` effect, plan consumers, dialogue
    promises; P10/P12/P15), NPC-side observation and player panels. Implementation owner
    commit: `24dba76`; all exit checks passed on that exact code before this completion mark.
    All 131 task identities/titles and the pending P98 follow-up are preserved; only P09.1
    gains a completion mark.
  - CI recovery (28 October 2026): exact run 40743829288 for commit `489e521` failed the
    backup migration-scope regression because V13–V15 had added seven tables without a
    reviewed backup-scope update. Extend the recovery allowlist to V15 and
    `narration_publication`, `inference_task`, `inference_scheduler_state`,
    `knowledge_claim`, `knowledge_belief`, `actor_sighting` and `actor_commitment`; strict
    version/catalog checks are retained and the scope diagnostic now names the current
    version. Local reproduction confirmed the V12/V15 mismatch; all nine backup tests pass
    after the repair. No live V15 restore is claimed and no task completion mark changes.
    Re-verification of the repair (`d65e05c`) on 28 October 2026: an exact export of
    `489e521` failed only the migration-scope test (V1–V15 found, V1–V12 allowed); the
    repaired code passes all nine backup tests, 26 repository tests, 116 runtime cases (18
    opt-in skips), the frontend gates and the full backend `verify` (687 tests). The four
    BuildKit/Compose cases of `tools/container_packaging` error with a missing `docker`
    executable because this development container has no Docker; they remain enforced by
    the hosted packaging job and are not claimed here.
- [x] P09.2 Build stamped context envelopes, mandatory/optional budgets, ID/full-text retrieval and overflow behavior; distinguish private planning assumptions from admitted facts and public narration, with language/glossary binding.
  - Package A — envelope and standings (`org.rpgengine.memory`): `ContextEnvelope` with a
    `Stamp` (adventure, campaign, generation, revision, world time, role, audience, holder,
    focus operation, pack/ruleset/policy hashes, output locale with locale and glossary
    revisions, query-scope epochs), deterministic JSON, SHA-256 digest, per-section omission
    counts and a legend; `ContextItem` labels every item `state`, `event`, `fact`,
    `common-knowledge`, `obligation`, `belief`, `last-known`, `narration` or `assumption`;
    `fact` and `assumption` are privileged-only, and assumptions live only in the `planning`
    section. `staleness(stamp)` reports changed generation, revision, locale, glossary and
    epochs.
  - Package B — budgets and overflow: `ContextBudget` (standard 12,000 input, 2,000 output and
    1,000 safety reserve, 500 protocol overhead, `forModel` scaling, per-section optional
    allowances, planning room only for privileged audiences), the conservative subadditive
    `TokenEstimator` (one token per two UTF-8 bytes, recorded in the envelope) and the pure
    `ContextSelection`: mandatory material is never truncated and returns `CONTEXT_TOO_LARGE`
    when it cannot fit; optional material is taken by rank within allowances, then headroom,
    later sections losing first, and every drop is counted.
  - Package C — audience-first retrieval: `ContextAssembler` reads one repeatable-read snapshot
    per audience (`KnowledgeView.forActorWithin`/`privilegedWithin`, public events stated by
    `EventStatements`, published narration, and assumptions of usable quest plans with
    freshness through the preparation-owned `PlanAssumptions`)
    and ranks required, `id` (explicit references; `event:`/`narration:` references fetch older
    records), `causal`, `text` (PostgreSQL full text with the `english`/`italian` configuration
    of each text) and `recent` tiers; `withheldPhrases` supplies disclosure-guard markers.
  - Package D — language and glossary binding: every text item keeps its language,
    `PackGlossaries` binds the pinned pack's reviewed terms for the definitions the items name
    in the output locale (a `term` avoids the other locale's form), each item is charged for its
    terms, and an unavailable glossary or terms beyond the 128 bound are rendered explicitly.
    See [docs/08](08-memory-and-continuity.md#p092-context-envelopes-budgets-and-retrieval).
  - Exit checks:
    - Pure suite: estimator bound and subadditivity, budget derivation and refusal, item and
      envelope invariants, deduplication, ranking, allowances, the overflow result and
      deterministic rendering.
    - Real PostgreSQL suite over sealed packs, production creation, real commands and commit
      and the real planning pipeline: limited audiences receive labelled beliefs without truth
      or other holders' secrets; privileged contexts keep facts, beliefs and assumptions apart;
      the exact overflow boundary; ID and English/Italian full-text retrieval beyond the recent
      window; locale-bound glossary terms; full charging of every rendered part; staleness,
      withheld phrases and retired-generation plans.
    - Deliberate weakening probes; the full packaged backend and the repository checks.
    - Not part of this task: switching the intent, narration and planner callers to the
      envelope (P09.3 onward), checkpoints, summaries and renewal (P09.3–P09.5), NPC-side
      history for character contexts (P11/P15), dependency-pack glossaries and semantic
      retrieval.
  - Verified evidence (28 October 2026): 13 new cases (`ContextSelectionTest` 7 pure,
    `ContextAssemblerTest` 6 real PostgreSQL) and all 687 packaged backend cases in 70 suites
    pass with zero failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6 fixture; the jar is
    packaged. Over sealed packs, production campaign creation, real commands and commit and the
    real planning pipeline with a scripted planner:
    - a public context holds the player's labelled `rumored` belief (text tier), common
      knowledge, mandatory own state, present warden and promise, and no truth, unseen secret,
      foreign commitment, real location or assumption; the withheld phrases are exactly the
      unseen secret and foreign terms, absent from the context; the warden's context is its own;
    - a privileged context labels `fact` with truth, `belief` and a fresh `assumption`
      separately, the public context of the same state has none of them, and a retired
      recovery generation removes the plan's assumptions;
    - mandatory material overflows as `CONTEXT_TOO_LARGE` one token below the exact required
      size and fits at it with every optional record omitted and counted and every rendered part
      charged;
    - an `event:` reference and English and Italian stemmed full text reach history older than
      the 64-event window; glossary terms bind in the output locale with the other locale's
      form avoided; digests are stable; staleness reports the revision and sighting epoch.
    Thirteen deliberate weakening probes each made the suites fail and were reverted
    byte-identically. The full run caught a plan-storage boundary violation (ADR 0014), so plan
    assumptions are now read through the preparation-owned `PlanAssumptions`. All 26
    repository-policy tests pass with zero findings. Not verified: callers switched to the
    envelope (P09.3 onward), renewal-level recall (QA-08, P09.3–P09.5), NPC-side history,
    dependency-pack glossaries, paraphrase leakage and live model runs. Implementation owner
    commit: `2e6e32f`; all exit checks passed on that exact code before this completion mark.
    All 131 task identities/titles and the pending P98 follow-up are preserved; only P09.2
    gains a completion mark.
  - Re-verification (28 October 2026), after the completion was returned for missing checks:
    the earlier record had not run the frontend gates or the backup, runtime-probe and
    packaging suites of the project check plan.
    An interrupted later session had also left the `assumption-unlabelled` probe applied to
    `ContextEnvelope.java` in the working tree. That line was restored to its committed bytes
    (the blob is identical to `2e6e32f` and HEAD), and the probes now mutate only an exported
    copy of HEAD, so a stopped run cannot leave the checkout weakened. Backend and frontend
    sources are unchanged since `2e6e32f`. On the clean tree at `801c8e4` every command of the
    project check plan ran:
    - passed: `git diff --check HEAD`, the repository checks (0 findings) and their 26 tests,
      frontend install, typecheck, 77 tests in 13 files, 5 localization checks and the
      localized build, the full packaged backend `verify` (687 tests in 70 suites, zero
      failures/errors/skips, jar packaged), the 9 backup tests and the runtime probe (116
      cases, 18 opt-in skips; each of its eight modules also passed separately);
    - focused: the 13 `ContextSelectionTest`/`ContextAssemblerTest` cases passed on the
      unmutated copy, and all 13 weakening probes were detected again;
    - environment: `tools/container_packaging` ran 23 cases with 4 errors (two BuildKit
      context and two Compose render cases) and 2 opt-in skips; every error is
      `FileNotFoundError: 'docker'` because this container has no Docker. This matches the
      P09.1 record; P09.2 changes no packaging input, and the hosted packaging job enforces
      these cases.
  - Second re-verification (28 October 2026). The completion was returned again because the
    plan command `python -B -m unittest discover -s tools/container_packaging -v` failed. Its
    two BuildKit context cases and two Compose render cases called a `docker` executable that
    this container does not have. These four cases now share the
    `tools/container_packaging/docker_requirement.py` policy:
    - The cases are skipped, with a reason saying hosted CI enforces them, only when no
      `docker` executable exists and nothing requires Docker.
    - `PACKAGING_REQUIRE_DOCKER=1` (now set by the CI packaging step) or `GITHUB_ACTIONS=true`
      keeps them mandatory, so a missing tool still fails there. The generic `CI=true` does
      not count.
    - An installed but unusable Docker still fails.

    The first full backend run of this re-verification failed one case:
    `CodexTaskAdapterTest.isolationViolationsFailClosedAndTheThreadIsReconciledAndRetired`,
    where `scenario-approval` ended `TIMED_OUT` instead of `FAILED`. This is a real P08.1 race
    in `RpcGeneration`, not a test-only flake:
    - The reader thread can read the `turn/start` acknowledgment and then a runtime approval
      request before the caller registers the turn.
    - The request was denied, but nothing failed the turn.
    - The failure is now buffered with the turn's early events, within the same 256 bound,
      and fails the turn when the acknowledgment registers it.
    - The deterministic regression case
      `RpcGenerationTest.serverRequestBeforeTheTurnIsRegisteredStillFailsThatTurn` fails on an
      exported copy of the unfixed code ("Expecting code to raise a throwable") and passes on
      the fix.
    - docs/06 records the rule.

    P09.2 context sources are unchanged since `2e6e32f`. The race fix is owner commit `d88ee64`
    and the packaging policy is `5ca1899`. On the clean tree of `5ca1899`, every command of the
    project check plan passed:
    - `git diff --check HEAD`;
    - the repository checks: 0 findings, and their 26 tests;
    - frontend install, typecheck, 77 tests in 13 files, 5 localization checks and the
      localized build;
    - the full packaged backend `verify`: 688 tests in 70 suites, zero failures/errors/skips, jar packaged;
    - 9 backup tests;
    - the runtime probe: 116 cases with 18 opt-in skips, and each of its eight modules
      separately;
    - packaging: 27 cases pass, 4 of them new policy cases. There are 6 skips: 4 because
      Docker is missing and 2 opt-in.
      - With `PACKAGING_REQUIRE_DOCKER=1` or `GITHUB_ACTIONS=true`, the same 4 cases error
        with `FileNotFoundError: 'docker'`.
      - With a stand-in `docker` that exits 1, they fail.

    These skips do not show that build-context filtering or the Compose boundaries hold on this
    host. The hosted packaging job remains their gate.
- [x] P09.3 Implement checkpoints, source-linked summaries, freshness/invalidation, and bounded background jobs.
  - Package A — operational checkpoints (V16, `org.rpgengine.memory.OperationalCheckpoints`):
    the database function `memory_operational_snapshot` builds a deterministic `jsonb` snapshot
    of canonical rows (counters, mode, pending decision, player, actors with real locations,
    resources, items, abilities, quests, open commitments, operations awaiting narration, the RNG
    cursor without its seed, a null encounter). `campaign_checkpoint` stores it with the SHA-256
    of its canonical text computed in SQL. Capture runs in one repeatable-read transaction and is
    refused while a mutation lease is live or an operation is `COMMITTING`; verification reports
    `NONE`, `CORRUPT`, `UNSUPPORTED`, `INCOMPATIBLE`, `DIVERGED`, `OUTDATED` or `VALID`; retention
    keeps the newest 8 per campaign.
  - Package B — source-linked summaries (`memory_summary`, `SummaryContract`): the `summary`
    (public) and `summary-privileged` contracts of the `summarizer` role condense 1–32 events of
    one audience from a `ContextAssembler` envelope. Validation enforces exact coverage, a note
    for every open obligation, uncertainty only on beliefs, supplied references only, grounded
    numbers and quotations, the public disclosure guard, glossary and locale. A recorded summary
    stores its exact covered events, every supplied knowledge record with its row version, its
    entities, its generation, locale and glossary revisions, the invalidation high-water mark and
    the context, template and body digests. See
    [docs/08](08-memory-and-continuity.md#p093-checkpoints-summaries-freshness-and-bounded-jobs).
  - Package C — freshness and invalidation (`MemorySummaries`): `STALE` after a generation
    change, a locale or glossary change, a changed supplied record or an explicit
    `memory_invalidation` naming its events, sources or entities; `BEHIND` with newer events;
    `CURRENT` otherwise. Only the newest summary that is not stale enters a context, as an
    optional `summary` item of its own audience that current state overrides. Recording rechecks
    sources and invalidations under a per-campaign advisory lock that `invalidate` also takes.
  - Package D — bounded background jobs (`memory_summary_job`, `ContinuitySummarizer`,
    `MemoryWorker`): requests after 5 significant actions (at most 10) or a scene boundary, one
    unfinished job per campaign and audience, an installation cap of 256, at most 32 events per
    job, fenced leases, 3 attempts with backoff, recovery of expired and retired-generation work,
    no automatic retry of a failed range, `CONTINUITY` work through the fair scheduler.
    Summaries are off by default; checkpoints run in the maintenance worker. The backup tool
    covers the V16 scope.
  - Exit checks:
    - Pure suite: contract acceptance in English and Italian and each rejection code, the schema
      against the validator, the kind, audience and work-class mapping.
    - Real PostgreSQL suite over sealed packs, production creation, real commands and commit and
      the real fair scheduler with a scripted summarizer: checkpoint determinism, verification and
      safe boundaries; the request policy; precise source-linked recording; audience separation;
      freshness after newer events, source changes, invalidations, a locale switch and a
      generation change; the recording race; bounded retries, fencing, throttling and guards.
    - The V16 upgrade step; deliberate weakening probes on an exported copy; every command of the
      project check plan.
    - Not part of this task: renewal triggers, durable session records and rehydration (P09.4),
      restart reconciliation of stale summaries and the resume card (P09.5), switching the intent,
      narration and planner callers to envelopes, entity and chapter summaries.
  - Verified evidence (28 October 2026): 14 new cases (`SummaryContractTest` 8 pure,
    `ContractSchemaTest` 1, `MemoryContinuityTest` 5 real PostgreSQL), the V16 step of
    `CanonicalMigrationUpgradeTest`, and all 702 packaged backend cases in 72 suites pass with zero
    failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6 fixture; the jar is packaged. Over
    sealed packs, production campaign creation, real commands and commit and the real fair
    scheduler with a scripted summarizer:
    - checkpoints hash the canonical `jsonb` text, never hold the seed, repeat as `EXISTING`,
      become `OUTDATED` after a command, are refused under a live mutation lease, keep the newest
      on pruning, and a forged hash or snapshot is `CORRUPT` or `DIVERGED` (capture: `MISMATCH`);
    - four waits are not due and the fifth requests a job, a repeat is the same pending job, and a
      single player move is a scene boundary;
    - a recorded public summary covers exactly the five public events with versioned sources,
      changes no canonical state, never saw the secret, and is assembled as an optional `summary`
      item (`current`, then `behind`) only into public contexts; a privileged summary sees the
      secret fact and stays privileged;
    - a promise kept during summarization is caught at recording and retried; a revised belief,
      an invalidation naming a covered event, a locale switch and a generation change make a
      summary stale and remove it from contexts, while an unrelated invalidation does not; the
      retired generation's queued job is cancelled;
    - a leak and an invented coverage use the single repair and the backoff; a failed runtime
      spends the last attempt and the range is given up; an expired lease is requeued with a new
      fence that locks the old worker out; the installation cap throttles; database guards refuse
      terminal changes and a second unfinished job.
    Twenty-four deliberate weakening probes, applied only to an exported copy of the backend, each
    made the suites fail and were reverted byte-identically. Every command of the project check
    plan passed on the clean implementation commit: `git diff --check HEAD`, the repository checks
    (0 findings) and their 26 tests, frontend install, typecheck, 77 tests in 13 files, 5
    localization checks and the localized build, the full backend `verify`, packaging (27 cases;
    4 skipped because Docker is missing and 2 opt-in), 9 backup tests with the V16 scope, and the
    runtime probe (116 cases, 18 opt-in skips; each of its eight modules also separately). The
    skipped packaging cases stay enforced by the hosted packaging job. Not verified: renewal and
    rehydration consumers (P09.4), restart reconciliation and the resume card (P09.5), callers
    switched to envelopes, entity and chapter summaries, summary quality and paraphrase (human
    rubric), live model runs and a live V16 dump and restore. Implementation owner commit:
    `2c7d5ed`; all exit checks passed on that exact code before this completion mark. All 131
    task identities/titles and the pending P98 follow-up are preserved; only P09.3 gains a
    completion mark.
- [x] P09.4 Implement count/context/error-triggered renewal and manual idle renewal, preserving transcript and campaign.
  - Package A — renewal triggers (`ScopedSessions`, `RenewalPolicy`, scheduler settlement): per
    live session the registry counts turns, completed tasks and the estimated conversation size
    (`settled`, called by `InferenceScheduler` for every forwarded task). At the line's next task
    it renews a session that completed `engine.inference.sessions.turn-ceiling` tasks (10, 1–100)
    or whose conversation plus the task would exceed `context-renew-percent` (85) of
    `context-window` (128,000 estimated units); `context-warn-percent` (75) is counted once per
    session. A turn that ran but did not complete (failed, timed out, cancelled, unconfirmed)
    retires its session at once (`UNRELIABLE`). A count or pressure renewal is deferred while a
    mutation lease is live or an operation is committing.
  - Package B — durable session records (V17 `ai_session_record`, `SessionJournal`,
    `JdbcSessionJournal`): one row per logical session with line generation and predecessor,
    opening cause, retirement reason, turn/completed/context counters, rehydration basis, code,
    revision and digest, the verified checkpoint (id, revision, hash, status) and the summary
    used. Frozen identity, monotonic counters, terminal retirement and no deletion are enforced by
    triggers; startup recovery retires records left active (`RESTART`). Failures never block
    inference. The backup tool covers the V17 scope.
  - Package C — rehydration (`Rehydrator`, `SessionRehydration`): the first task of a line that
    had an earlier session (in memory or in the records) carries a continuity block: fixed rules
    (no replayed opening or earlier scenes, no invented history, the task context wins), the
    resume point (revision, turn, world time, mode, pending decision, pending narration count), the
    verified checkpoint's status and revision, and a 4,000-token `ContextAssembler` envelope for
    the session's own role and audience with the usable summary. Basis `SUMMARY`, `CHECKPOINT` or
    `STATE`; the summarizer role gets no block; a failed or oversized block opens the session
    without one. See
    [docs/08 section 7](08-memory-and-continuity.md#p094-renewal-triggers-session-records-and-rehydration).
  - Package D — manual idle renewal (`SessionRenewalService`, `SessionRenewalController`):
    `POST /api/v1/campaigns/{id}/ai-session/renew` retires every session of an owned campaign
    when nothing of it runs (no live lease, no operation being resolved or narrated, no running
    presentation or summary job, no dispatched inference task); otherwise 409
    `SESSION_RENEWAL_BUSY`. Renewal writes no campaign state, event, transcript entry or decision.
  - Exit checks:
    - Registry suite with doubles: each trigger, deferral, the block on the first task only,
      omission of an oversized block, owner renewal, journal history after a restart, a failing
      rehydrator and the settings bounds.
    - Real PostgreSQL suite with the real scheduler, journal and rehydration and a scripted
      runtime: the fresh session resumes at the correct pending decision (`starting-situation`,
      then `free-input`) with a verified checkpoint, without the opening text, with linked records,
      and the campaign row, events and transcript unchanged; unreliable and pressure renewals;
      deferral under a live lease; rehydration after a restart; owner renewal refused while busy
      or for a foreign owner, then resuming from the public summary with the open promise and
      without the secret.
    - The HTTP route, the V17 upgrade step, deliberate weakening probes on an exported copy and
      every command of the project check plan.
    - Not part of this task: restart reconciliation of stale summaries, missing dependencies and
      the resume card (P09.5), a UI control for renewal, switching the intent, narration and
      planner callers to envelopes, and live-model or human evaluation of QA-08.
  - Verified evidence (28 October 2026): 11 new cases (`ScopedSessionsTest` 6 with doubles,
    `MemoryContinuityTest` 4 real PostgreSQL, `CampaignCommandHttpTest` 1), the V17 step of
    `CanonicalMigrationUpgradeTest`, and all 713 packaged backend cases in 72 suites pass with zero
    failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6 fixture; the jar is packaged. Over
    sealed packs, production campaign creation, real commands and commit, the real fair scheduler,
    the real journal and rehydration and a scripted runtime:
    - with a ceiling of two, the third task runs in a new session whose block resumes at the
      `starting-situation` decision at the current revision with a `valid` checkpoint, holds no
      opening text, and is followed by the unchanged task instructions; the records link both
      sessions (`TURN_CEILING`, line generation 2, predecessor, digest of the block, the stored
      checkpoint hash, which is never rendered); the campaign row, its events and the transcript
      are unchanged;
    - a failed turn retires its session at once and the replacement resumes at `free-input`; the
      warning is counted and context pressure renews the next session;
    - a live mutation lease defers a due renewal, which happens at the next task; after a restart
      the old record is `RESTART` and the new process's first session is rehydrated as generation 3;
    - owner renewal is refused while an operation is validated or a lease is live and for a
      foreign owner, then retires the session without changing the campaign; the next session
      resumes from the public summary with the open promise and without the secret; the summarizer
      gets no block;
    - the HTTP route answers counts only and refuses malformed bodies, foreign scopes and missing
      browser proofs; a retired record cannot be reopened.
    Twenty-one deliberate weakening probes, applied only to an exported copy of the backend, each
    made the suites fail and were reverted byte-identically (the lease probe after the test was
    tightened to isolate the lease). Every command of the project check plan passed on the clean
    implementation commit: `git diff --check HEAD`, the repository checks (0 findings) and their
    26 tests, frontend install, typecheck, 77 tests in 13 files, 5 localization checks and the
    localized build, the full backend `verify`, packaging (27 cases; 4 skipped because Docker is
    missing and 2 opt-in), 9 backup tests with the V17 scope, and the runtime probe (116 cases, 18
    opt-in skips; each of its eight modules also separately). The skipped packaging cases stay
    enforced by the hosted packaging job. Not verified: whether a live model follows the
    continuity rules (QA-08 live and human rubric), a UI control, restart reconciliation and the
    resume card (P09.5), live model runs and a live V17 dump and restore. Implementation owner
    commit: `ff22ae6`; all exit checks passed on that exact code before this completion mark. All
    131 task identities/titles and the pending P98 follow-up are preserved; only P09.4 gains a
    completion mark.
- [x] P09.5 Reconcile restart, committed narration pending, missing dependencies, and stale summaries.
  - Package A — pinned content diagnosis and mutation pause (`PackRegistry.diagnose`,
    `ClosureDiagnosis`, `CampaignDependencies`, `CommandRuleCatalogs`, `OperationAdmissionService`):
    the campaign-loading traversal, in its own read-only transaction and without throwing for
    content problems, reports `AVAILABLE`, `UNSEALED` (a legacy root admitted before sealed storage;
    earlier behavior kept) or `MISSING` with `REVISION_MISSING`, `RULESET_UNAVAILABLE`,
    `CONTENT_CORRUPT`, `DEPENDENCY_MISSING` (every missing dependency listed) or `CLOSURE_INVALID`,
    each with pack ID and version only. A revision is now verified, then its dependencies are
    loaded, then it is compiled. `AVAILABLE` is cached per revision; failures are never cached. A
    fresh command of a `MISSING` campaign is refused right after the replay check with
    `DEPENDENCIES_MISSING` (HTTP 409 `COMMAND_DEPENDENCIES_MISSING`), nothing journaled; identical
    retries still replay; nothing substitutes another pack.
  - Package B — restart reconciliation (`MemoryReconciliation`, `MemoryStartupReconciliation`,
    `engine.memory.reconciliation.enabled`): one pass per process, after every startup recovery
    and before HTTP, in pages of 100 campaigns with per-campaign failure isolation; summary-job
    recovery (also cancelling queued jobs over vanished events, `RANGE_UNAVAILABLE`); the content
    diagnosis; unusable checkpoints (`CORRUPT`, `UNSUPPORTED`, `INCOMPATIBLE`, `DIVERGED`)
    discarded newest first and recaptured from canonical state at a safe boundary, a divergence
    invalidating every earlier summary (`CHECKPOINT_DIVERGED`); summaries built from a revision the
    campaign no longer has stale (`rolled-back`) and invalidated once (`STATE_ROLLED_BACK`); one
    replacement request for an audience with only stale summaries when summaries are enabled;
    counts of narration still owed. Never writes canonical state; never blocks startup.
  - Package C — committed narration pending across a restart: presentation recovery requeues the
    interrupted attempt, reconciliation counts it, the narrator's first session after the restart
    is rehydrated at the pending decision with the pending narration counted and without the
    opening, and the narration is published exactly once without re-running the action.
  - Package D — resume card (`ResumeCards`, `ResumeCardController`, playthrough view):
    `GET /api/v1/campaigns/{id}/resume?adventureId=…` reads, without advancing time or writing, the
    player's location with its public localized name, actors the player knows to be there,
    resources, a null encounter, the pending decision kind, up to 16 actions awaiting narration with
    their receipts and presentation state, the content diagnosis with the pause, and continuity
    codes. The interface shows it in English and Italian with an alert naming each missing pack;
    the browser reload contract records it. See
    [docs/08 section 10](08-memory-and-continuity.md#p095-restart-reconciliation-missing-dependencies-and-the-resume-card).
  - Exit checks:
    - Registry suite: available, unsealed, missing revision, an upgraded ruleset, a lost dependency
      and changed bytes are diagnosed without writing, while loading keeps its exception types.
    - Real PostgreSQL suite with sealed packs, production creation, real commands, the real
      scheduler, journal and rehydration and scripted runtimes: checkpoint repair for corrupt,
      diverged, ahead, outdated and unsafe cases with canonical state unchanged; stale and
      rolled-back summary work retired, replaced once and kept stale; a lost dependency pausing a
      fresh process's admission while replays work and the card explains it; interrupted
      narration resuming once after a restart behind a narrator that does not replay the opening.
    - HTTP: the read-only, localized card (EN and IT), 400/404/403, a corrupt pinned pack answering
      409 `COMMAND_DEPENDENCIES_MISSING` until restored; a legacy unsealed campaign not paused.
    - Startup wiring: the pass runs once per start before the web server and never aborts startup.
    - Frontend parser, view and Italian-variant specs over the refreshed reload transcript.
    - Deliberate weakening probes on an exported copy and every command of the project check plan.
    - Not part of this task: a UI control for narration retry or session renewal, switching the
      intent, narration and planner callers to envelopes, live-model or human evaluation of QA-08,
      and a live dump and restore.
  - Verified evidence (28 October 2026): 11 new backend cases (`MemoryContinuityTest` 4 real
    PostgreSQL, `PackRegistryPersistenceTest` 1, `CampaignCreationHttpTest` 1, `CampaignCommandHttpTest`
    1, `MemoryStartupReconciliationTest` 3 pure, `MemoryStartupPassTest` 1 real context), the refreshed
    browser reload contract and 2 new frontend specs; all 724 packaged backend cases in 74 suites pass
    with zero failures/errors/skips on the scoped JDK 21/PostgreSQL 18.6 fixture, and 79 frontend tests
    in 13 files pass. Over sealed packs, production creation, real commands and commit, the real
    scheduler, journal and rehydration and scripted runtimes:
    - restart reconciliation captures a missing or outdated checkpoint, discards and recaptures a
      corrupt one without touching summaries, discards a checkpoint diverging at its own revision
      (which capture alone reports as `MISMATCH`) and one ahead of the campaign, invalidates every
      earlier summary after a divergence, leaves an unsafe boundary to a later pass and never changes
      the campaign row or its events; a second pass does nothing;
    - an audience whose summaries are all stale gets one replacement request (`PENDING` on the next
      pass); after a rollback without a generation change, queued work over vanished events is
      `CANCELLED` (`RANGE_UNAVAILABLE`), the summary is stale (`rolled-back`) and invalidated once
      (`STATE_ROLLED_BACK`), and it stays stale and out of contexts after the campaign grows past it;
    - a lost pinned dependency is diagnosed by its coordinates in a fresh process, which refuses fresh
      `WAIT` and `MOVE` commands with `DEPENDENCIES_MISSING` while journaling nothing and still replays
      an earlier request; the resume card shows the pause, the committed location, the free-input
      decision and the warden the player saw there after the warden left unseen; the startup pass
      counts the paused campaign;
    - narration interrupted by a crash is requeued at restart, counted as resuming and published once
      by the narrator, whose first session after the restart resumes at `free-input` with one pending
      narration and without the opening; campaign row, events, resources and receipts are unchanged;
      pending narration shows its receipt with three retries, and a delivered retry round removes it;
    - over HTTP the card is read-only (whole-database snapshot), localized (English and Italian
      location names), refuses bad, foreign and unadmitted requests, and a corrupt pinned pack answers
      409 `COMMAND_DEPENDENCIES_MISSING` until its bytes are restored; a legacy unsealed campaign is
      not paused; an upgraded ruleset, a lost dependency and changed bytes are diagnosed without
      writing; the startup pass runs before the web server and never aborts startup.
    Twenty-two deliberate weakening probes, applied only to an exported copy of the backend, each
    made the suites fail and were reverted byte-identically (one was first missed because no case
    published a retry round for an action whose journal stays pending; the test now covers it). Every
    command of the project check plan passed on the clean implementation commit: `git diff --check
    HEAD`, the repository checks (0 findings) and their 26 tests, frontend install, typecheck, 79 tests
    in 13 files, 5 localization checks and the localized build, the full backend `verify`, packaging
    (27 cases; 4 skipped because Docker is missing and 2 opt-in), 9 backup tests, and the runtime
    probe (116 cases, 18 opt-in skips; each of its eight modules also separately). The skipped
    packaging cases stay enforced by the hosted packaging job. Not verified: a UI control for
    narration retry or renewal, live model behavior after a restart (QA-08 live and human rubric), a
    live dump and restore, and a real browser run. Implementation owner commit: `3935422`; all exit
    checks passed on that exact code before this completion mark. All 131 task identities/titles and
    the pending P98 follow-up are preserved; only P09.5 gains a completion mark.

**Exit:** a fresh AI thread resumes the correct pending decision with no invented history or replayed prologue. **Checks:** QA-08, QA-16–19, delayed promises, correction invalidation, input-budget limits. **Suggested commit:** `Add persistent memory and renewable narration sessions`.

## 13. P21 — Local and API-key inference backends with owner-chosen models

**Depends on:** P02, P08–P09. **Areas:** `docker/` model-server, image-worker and gateway images, `compose.yaml`, `start.sh --models`, backend slot settings and adapters, `tools/` fixtures, [inference backends and model selection](34-inference-backends-and-model-selection.md). Decision: [ADR 0021](adr/0021-inference-backends-and-model-selection.md).

Scheduling: this package follows the memory work so that P10–P17 can be built and played with free local models, and it supplies the image capability that P17.6 requires. Codex stays the default narration runtime. Every speed, memory and quality figure in the specification is an estimate until P21 records measurements on the reference host.

- [ ] P21.1 Qualify the reference host and define the catalog: a launcher-generated hardware profile (GPU memory read from the driver tool, not from the Windows adapter field), Docker GPU passthrough on WSL 2 with the pinned llama.cpp CUDA image, free-VRAM and WSL-memory measurements, the catalog schema (source, revision, files, SHA-256, license, requirements, languages, capabilities, slots, attribution), the advisor rules and synthetic-profile fixtures, and catalog entries for the reference host with verified hashes.
  - Verified partial checkpoint (28 October 2026). The task stays open until the profile and the
    container GPU check run on the reference host itself; this Linux toolchain container has no
    GPU device, driver tool or Docker. On 29 October 2026 the drafted work was re-verified (the
    full verification plan, including `./mvnw --batch-mode verify` with 746 backend tests, and the
    nine catalog digests against the download records) and committed as this checkpoint; the
    container still had no GPU tool or Docker.
  - Package A — hardware profile (`start.sh`): `./start.sh --models profile [--gpu-check]` writes
    `.local/hardware.json` (`rpg-engine.hardware-profile/1`). GPU name, total, used and free VRAM,
    driver and compute capability come from `nvidia-smi` (on `PATH` or WSL's
    `/usr/lib/wsl/lib/nvidia-smi`), never from the Windows adapter field. It also records the CPU
    and memory from `/proc` (scope `vm` under WSL), the module layout through PowerShell CIM on
    Windows, free disk under `.local`, and the container engine's memory (the WSL 2 VM under Docker
    Desktop). A missing probe is recorded, never fatal. `--gpu-check` pulls the pinned image by
    digest if absent and runs `--list-devices` with all GPUs, no network, a read-only root, no
    capabilities and user 65534 (`visible`, `not-visible`, `failed`, `engine-unavailable`). There are
    no identifiers.
  - Package B — pinned image: `ghcr.io/ggml-org/llama.cpp:server-cuda-b11960` by its index digest
    (CUDA 12.8.1, entrypoint `llama-server`, resolved through the registry API), mirrored in ADR 0009.
    The repository `images` check now also covers launcher `*_IMAGE` constants.
  - Package C — catalog (`backend/src/main/resources/inference/model-catalog.json`,
    `org.rpgengine.inference.catalog.ModelCatalog`): the closed format
    `rpg-engine.model-catalog/1` with source and revision, files with size, SHA-256 and verification
    evidence, license class, URL, basis and confirmation, attribution, estimate inputs from GGUF
    headers, languages, capabilities, slots, content notes and a measured profile slot. Revision 1
    lists Gemma 4 26B-A4B, 12B, E4B and E2B (Google QAT Q4_0 GGUF), Qwen3.5-9B Q4_K_M (Unsloth GGUF),
    WAI-illustrious-SDXL v17.0, SDXL base 1.0 and Pony Diffusion V6 XL with its VAE. All nine files
    (56,749,024,108 bytes) were downloaded on 28 October 2026 and their SHA-256 recomputed; every one
    matched the publisher's digest and size.
  - Package D — advisor and fixtures (`HardwareProfile`, `ModelAdvisor`): strict profile parsing;
    GPU and memory budgets, text fit with the largest fitting context and image fit, ranking,
    default eligibility, notes and the local-only, hybrid and cloud-first presets. Six profile
    fixtures (reference host, reference host with a container GPU, no GPU, 6, 8 and 24 GB) are
    generated by the launcher from recorded tool output. On the reference-host profile the advisor
    recommends Gemma 4 12B with a 32,768-token context and WAI-illustrious v17.0 (ADR 0021 decision
    10). See [docs/34 section 5](34-inference-backends-and-model-selection.md#5-catalog-and-hardware-advisor).
  - Exit checks:
    - Done: 12 launcher profile tests with recorded tool output (format, the six fixtures
      byte-identical, container options, pull by digest, visible, not-visible and failed outcomes,
      missing and failing tools, placeholders, WSL scope, no identifiers, usage errors, escaping);
      22 backend tests (bundled catalog pins, every catalog and profile rejection rule, the advisor
      on the six profiles with hand-derived budgets, contexts and rankings, unverified digests,
      measured and Italian evidence, settings); a real `--models profile` run in the development
      container on the reference host's WSL 2 VM (i5-14600KF, 20 threads, 7,823 MiB total and
      5,825 MiB available, no GPU tool, no Docker, all recorded as such); and the download-and-hash
      verification of all nine catalog files.
    - Open: `./start.sh --models profile --gpu-check` on the reference host (Windows 11, Git Bash,
      Docker Desktop with WSL 2), which must show the RTX 4070's VRAM from the driver tool, the WSL
      VM memory, the module layout, and a `visible` CUDA device inside the pinned image with its free
      VRAM. Record that profile, sanitized, as evidence, and compare the container's free VRAM with
      the driver tool's.
- [ ] P21.2 Package and qualify the local text backend: a pinned model-server image in router mode with a read-only model volume and no network egress, hash-verified `./start.sh --models fetch` with license display and explicit confirmation, JSON-Schema readiness probes, an English and Italian narration probe set, and recorded measurements (load time, prefill and decode speed, peak VRAM and RAM, context limits, VRAM released by unload) for the default and alternate models.
- [ ] P21.3 Implement slot assignment and the remaining backend adapters behind the runtime-neutral task port: independent `light`, `standard`, `deliberate` and `image` slots, readiness probes with visible results, billing and privacy class badges with acknowledgement on a class change, same-class fallback lists only when configured, per-slot session retirement, and the `gateway` service for owner-key hosted APIs and allowlisted custom endpoints (keys as runtime-only secrets, budgets, bounded redirects and sizes). Expose `GET /api/v1/models/status`, the owner-only settings API and the Settings → Models panel.
- [ ] P21.4 Implement the shared GPU and RAM admission coordinator for text, image and speech: priorities, leases using the model server's documented load and unload endpoints and slot save and restore, batching, interruption between diffusion steps, unowned-contention handling and metrics. Prove no engine-caused out-of-memory on the reference host.
- [ ] P21.5 Implement the local image backend: a pinned image-worker container loading single-file safetensors checkpoints and LoRAs, with WAI-illustrious-SDXL v17.0 as the recommended default and Pony Diffusion V6 XL and SDXL base 1.0 as catalog alternatives; prompt chunking beyond 77 tokens; default safe-content tags; step-boundary cancellation; output validation and provenance; and the `a1111-http` adapter for an owner-run Forge or A1111 server. Integrate with the P17 asset-job contract without changing authority rules.
- [ ] P21.6 Verify and document: QA-43 and QA-44 on deterministic fixtures and on the reference host, synthetic-hardware advisor tests, custom-endpoint and secret-handling tests, the bilingual narration evaluation of the default and alternate models, license and attribution records, and the supported-defaults table for the reference host with honest limits.

**Exit:** the owner can run text and image generation entirely locally on the reference host with the recommended defaults, assign any compatible model or endpoint to each slot, and see every class change and limit; no model server has network egress; GPU and RAM admission prevents engine-caused exhaustion; and a default is marked supported only where evidence exists. **Checks:** QA-43, QA-44, extended QA-28, QA-16 secret sentinels. **Suggested commit:** `Add local and key-based inference backends with owner-assigned slots`.


## 14. P10 — Inventory, skills, quests, and time

**Depends on:** P07–P09. **Areas:** characters, inventory, quests, world clock, related UI.

- [x] P10.1 Implement attributes, skills, derived stats, learned abilities, conditions, explicit item rarity tiers, equipment and hand/slot constraints; never derive mechanics from artwork.
  - Package A — pack contract (`PackCompiler`): new `equipment` rule (hands 1–4, slots with capacity
    and accepted tags) and `derivedStats` rule (formula over attributes and skills only; dice and
    resource atoms rejected; proven interval inside the declared bounds); item `hands` and `tags`
    with `slot` validated against the pack's slots (`REFERENCE_UNKNOWN` listing the defined slots),
    equippable items must not stack; condition `maxStacks`; creation `startingItems[].equipped`
    checked for slot capacity and hands. Derived rules are validated after the others.
  - Package B — pure rules (`rules`: `StatFormula`, `ConditionRules`, `EquipmentRules`,
    `CharacterSheet`): exact long arithmetic, `none`/`refresh`/`stack` conditions with a stack cap
    and expiry by rounds, combined slot/tag/capacity/hands check reporting every violation, and an
    effective sheet recomputed from base values (condition modifiers clamped to the attribute range;
    a statistic with an unheld attribute is absent, never zero).
  - Package C — persistence and service (`V18`, `CharacterRuleCatalog`, `CharacterService`,
    `CharacterCreation`): `actor_condition` and `equipment_assignment`, a composite foreign key that
    blocks transferring an equipped instance, storage bounds on stacks and total hands, a catalog
    that carries the declared rarity tier and no appearance data, learned abilities with skill and
    ability prerequisites, and explicit equipped starting items. Operations run inside the caller's
    transaction under an actor row lock. Decision: [ADR 0023](adr/0023-character-rules-and-equipment-constraints.md).
  - Verification (29 October 2026): `CharacterRulesPackTest` (9 compile cases), `StatFormulaTest`,
    `ConditionRulesTest`, `EquipmentRulesTest`, `CharacterSheetTest` and `CharacterServiceTest`
    (PostgreSQL 18.6: catalog, sheet, conditions, abilities, equipment constraints, transfer block,
    transaction requirement), the V18 populated-upgrade step, and the backup tool catalog at schema
    version 18. Full backend `./mvnw --batch-mode verify`: 783 tests, 0 failures, 4 skipped
    (`PackagedInterfaceTest`, outside the container).
  - Re-verification (29 October 2026): an independent rerun of the plan rejected the first
    completion because `./mvnw --batch-mode verify` stopped in the wrapper with "Failed to validate
    Maven distribution SHA-256". The code was not at fault. The container has no `unzip`; a wrapper
    with an empty cache then downloads the `.tar.gz`, which can never match the pinned ZIP checksum.
    Maven Central's ZIP matches the pin (checked by download), and the failure reproduces by running
    the wrapper without `MAVEN_USER_HOME`. With the toolchain environment (`MAVEN_USER_HOME` holding
    a cache seeded from the checksum-verified ZIP) the wrapper runs offline. The procedure for hosts
    without `unzip` was then added to the [backend guide](../backend/README.md); the pin and wrapper were
    left unchanged at that point.
  - Wrapper repair (29 October 2026): the second independent rerun failed the same way. It ran
    with `MAVEN_USER_HOME` unset (it left an empty `~/.m2/wrapper/dists/apache-maven-3.10.0`), so the
    seeded cache never applied. The committed `backend/mvnw` is now a marked, modified copy of the
    3.3.4 script. Without `unzip` it downloads and verifies the pinned ZIP and extracts it with the
    JDK `jar` tool. When it cannot download, it says why ([ADR 0009](adr/0009-toolchain-baseline.md) amendment).
    `tools/repository_checks/test_maven_wrapper.py` (9 cases, loopback fixture; the 2 that need
    `unzip` skip here) passes against this copy; 6 of its cases fail against the upstream script.
    An independent review found three defects before commit, each now fixed and tested: a relative
    JDK path, the unpinned `.tar.gz` path, and a new `find` dependency. A cold run had no `unzip`, no
    `MAVEN_USER_HOME`, `MAVEN_OPTS` or toolchain Maven, and a fresh wrapper cache. It downloaded
    Maven 3.10.0 and 574 artifacts from Central and passed `./mvnw --batch-mode verify`: 783 tests,
    0 failures, 4 skipped. `unzip` and cache seeding are no longer required; a first run needs a
    JDK and access to Maven Central.
  - Second wrapper repair (29 October 2026): the third independent rerun got past the checksum,
    then stopped with `./mvnw: 163: exec: ~/.m2/wrapper/dists/apache-maven-3.10.0/dae9a06d/bin/mvn:
    Permission denied` (status 126). The verifier's home is a tmpfs mounted `noexec` (so is `/tmp`),
    so no file in the wrapper cache can be executed, whatever its mode. The wrapper now runs a
    launcher that cannot be executed directly through the interpreter on its `#!` line
    ([ADR 0009](adr/0009-toolchain-baseline.md) second amendment). Reproduced first with `HOME` on a
    `noexec` mount. `test_maven_wrapper.py` has two new cases (cleared execute bits; a real `noexec`
    home); both fail against the previous script with status 126. A cold `./mvnw --batch-mode verify`
    with `HOME` and a fresh repository on a `noexec` mount passed: 574 artifacts downloaded, 783
    tests, 0 failures, 4 skipped. JLine logs native-library warnings there and falls back.
  - Test database repair (29 October 2026): the fourth independent rerun got through the wrapper,
    then 303 tests errored with "Failed to determine a suitable driver class". The workspace check
    runs `./mvnw --batch-mode verify` with the JDK on `PATH` but without `SPRING_DATASOURCE_*`, so
    no Spring context could reach PostgreSQL. Reproduced with that environment (3 of 3
    `EngineApplicationTest` errors). Spring Boot's standard `backend/config/application.properties`
    now holds a checkout's private connection: `.gitignore` ignores `/backend/config/`, the
    `secrets` check treats it as private and requires the ignore rule, and exported variables still
    win ([local datasource file](../backend/README.md#local-datasource-file); [ADR 0009](adr/0009-toolchain-baseline.md)
    amendment). No test is skipped and there is no default URL or embedded database.
    Final evidence (commit `7239175`, clean tree, steps run with the workspace check's environment):
    all 16 plan commands passed (`./mvnw` step: 783 tests, 0 failures, 4 skipped).
  - Limits: no play commands use these rules yet (atomic use/transfer/equip is P10.2), ranks and
    levels do not advance, condition ticks and rest are P10.4/P11, and no panel shows conditions or
    equipment (P10.5). Starting loadouts from dependency packs are checked at campaign creation, not
    by a dedicated persistence test. No live V18 dump and restore rehearsal ran.
- [x] P10.2 Implement atomic use/transfer/equip/trade commands and source-linked item creation.
  - Package A — pack contract (`PackCompiler`): optional item `value` (1–1,000,000 trade units; absent
    means unpriced) and `use` (`consumes`, default true; optional `target` policy; `effects` in the exact
    ability grammar and node budget), and an optional `createItem.quantity` (1–99 per recipient). Existing
    packs and content hashes are unchanged.
  - Package B — pure inventory (`org.rpgengine.inventory`): `InventoryModel` (consume, transfer, deposit with
    identity-preserving whole-stack moves and merging in identity order), `ItemChange`/`ItemPlan` (minimal
    mutations, declared creations and consumptions, per-definition unit conservation proved at
    construction) and `InventoryWriter` (compare-and-set on owner, quantity, definition and storage
    version; ledger rows).
  - Package C — commands (`commands`): `USE_ITEM`, `EQUIP_ITEM`, `UNEQUIP_ITEM`, `TRANSFER_ITEM` and
    `TRADE_ITEMS` in the closed vocabulary; rule gate with the stable categories `ITEM_UNAVAILABLE`,
    `EQUIPMENT_ILLEGAL`, `QUANTITY_ILLEGAL`, `TRADE_REFUSED` (plus the existing target, actor and effect
    categories); resolver branches, `createItem` executable in abilities and items; trade accepted by the
    value-balance rule; `CharacterService.equipViolations` shares the equip check; attribution of every item
    effect to the intention's participants.
  - Package D — persistence and surfaces (`V19`): append-only `item_creation` ledger (operation, announcing
    event, `EFFECT`/`REWARD` source key, recipient, definition, quantity; unique per source), commit applies
    items after the events inside the same transaction, six v1 events with bilingual narration statements,
    player-safe receipt `items` lines, HTTP codes, English/Italian frontend messages (349 reviewed
    messages), backup tool at schema version 19. Decision: [ADR 0024](adr/0024-atomic-item-commands-and-source-linked-creation.md).
  - Exit checks: QA-10 (potion quantity and capped healing commit together; equipment cannot duplicate
    ownership), unit conservation across transfer, trade, use and creation, every refusal writes nothing,
    replay never repeats an effect, a fault after the item rows rolls back rows, ledger, events and
    resources, the ledger is append-only and unique per source, the V19 upgrade step, and the full plan.
  - Verification (29 October 2026, PostgreSQL 18.6, JDK 21, the runner's environment): backend suite 817 tests,
    0 failures; `./mvnw --batch-mode verify` 817 tests, 0 failures, 4 skipped (`PackagedInterfaceTest`). New
    and extended cases: `InventoryModelTest` (10 pure: merging, reassignment, splitting, determinism,
    conservation), `ItemContractPackTest` (5), `ItemCommandsTest` (13 on the armory pack: QA-10 potion with
    capped healing, whole and partial transfers, trades, equipment rules, creation ledger rows with
    operation, event and source, targets and unsupported effects, consuming use of a worn item, fault after the
    item rows, replay, stale writer guards, attribution), `CanonicalCommitServiceTest` (reward-sourced
    creation commits and rolls back with its settlement), `TypedCommandTest`, `ResolvedCommitTest`,
    `EventStatementsTest` and the V19 step of `CanonicalMigrationUpgradeTest`. Eleven weakenings applied to
    an exported copy each failed at least one test (see ADR 0024); one first survived and gained a test.
    Frontend: 79 tests, 349 reviewed EN/IT messages, typecheck and build pass. Repository checks and the
    backup tool (schema version 19) pass.
  - Limits: no inventory or equipment panel, journal line, world-time cost or quest reward uses these
    commands yet (P10.3–P10.5); NPC stock exists only through gifts and creation, so a player must know an
    instance identity to request it in a trade; weight, capacity and durability are not enforced; damage,
    heal, move, condition and reveal effects stay refused as `EFFECT_UNSUPPORTED`; item intents are not
    offered to the model-proposal contract; no live V19 dump and restore rehearsal ran; the 18 Docker-dependent
    probe cases and the `unzip` wrapper cases are not proven in this container.
- [x] P10.3 Implement basic objective predicates, quest transitions, and one-time reward-source accounting.
  - Package A — pure engine (`org.rpgengine.quests`): `QuestRules` (prose-free projection of every pinned
    quest), `QuestPredicate` (the compiled world grammar: `visited`, `possesses`, `questStatus`, `objective`
    and the boolean/integer operators, evaluated over committed facts with no dice or clock) and `QuestPlan`
    (deterministic transitions: derived discovery, accept/suspend/resume requests, sticky objectives, ordered
    outcomes, cascades to a fixed point, rewards visible to later predicates, event and reward-unit budgets
    that defer instead of dropping).
  - Package B — persistence (`V20`): `quest_state` (terminal rows immutable, no return to `available`),
    `quest_objective` and `location_visit` (append-only); `QuestStore` reads, `QuestWriter` applies inside the
    commit with compare-and-set on the storage version. Old saves read every quest at its initial status.
  - Package C — commands and commit: `ACCEPT_QUEST`, `SUSPEND_QUEST`, `RESUME_QUEST` and the refusal category
    `QUEST_UNAVAILABLE`; `LocalActionResolver` runs the engine after movement, item commands, ability use and
    the quest commands, `ResolvedCommit` carries `QuestChanges`, `CanonicalCommitService` writes them beside the
    events; the pack compiler bounds an outcome at 32 reward units.
  - Package D — one-time accounting and surfaces: an ending settles its item rewards for the player under
    `quest:<id>:outcome` through one `reward_settlement` (unique per source and recipient) and `REWARD`-sourced
    `item_creation` rows, so three guards stop a second payment; hidden (`secret`) quests start by themselves
    and only their ending is public; seven quest events with EN/IT narration, a player-safe receipt `quests`
    list, frontend messages (353 reviewed), backup tool at schema version 20. Decision:
    [ADR 0025](adr/0025-quest-lifecycle-objective-predicates-and-one-time-rewards.md).
  - Exit checks: reaching a place and holding items finish objectives and endings in the commit that caused
    them; a quest reward is paid once however often the player returns, replays or retries; a suspended quest
    tracks nothing and resumes with an immediate evaluation; a hidden quest never leaks into public events or
    receipts before its ending; a fault after the quest writes rolls back rows, settlement, ledger, events and
    revision; the ended quest, the settlement and the objectives are immutable at the database; the V20 upgrade
    step; and the full plan.
  - Verification (29 October 2026, PostgreSQL 18.6, JDK 21, the runner's environment, clean HEAD `7e40034`): all
    16 plan commands passed; backend suite 844 tests, 0 failures; `./mvnw --batch-mode verify` 844 tests, 0 failures,
    4 skipped (`PackagedInterfaceTest`). New and extended cases: `QuestPredicateTest` (5 pure: atoms, logic,
    arithmetic, misuse), `QuestPlanTest` (10 pure: discovery, requests, sticky objectives, ordered outcomes,
    cascades, rewards inside one evaluation, hidden quests, budgets, determinism, change derivation),
    `QuestCommandsTest` (10 on the quest pack: accept, move and settle once, suspension, refusals, an item effect
    and a quest ending in one commit, a chain of quests with a hidden one, fault after the quest writes, replay,
    stale and ended-quest writers, attribution), the reward bound in `PackCompilerTest`, `TypedCommandTest`,
    `EventStatementsTest` and the V20 step of `CanonicalMigrationUpgradeTest`. Fifteen weakenings applied to an
    exported copy each failed at least one test (see [ADR 0025](adr/0025-quest-lifecycle-objective-predicates-and-one-time-rewards.md)). Frontend: 79 tests, 353 reviewed EN/IT
    messages, typecheck and build pass. Repository checks and the backup tool (schema version 20) pass.
  - Limits: no journal, panel or quest screen (P10.5); predicates cover visits, possessions and quest state only
    (no acquired-fact, encounter, interaction or clock predicates yet); eligibility and discovery are one step
    (`availableWhen`), accepting needs no issuer nearby, and a quest cannot be abandoned by command; rewards are
    item units for the protagonist (no XP, reputation or party shares, P13.4/P15.2); consequence texts are not shown
    anywhere yet; the operational checkpoint snapshot still lists only player-known quests; deferred transitions settle
    at the next evaluating command; no live V20 dump and restore rehearsal ran; the 18 Docker-dependent probe cases
    and the `unzip` wrapper cases are not proven in this container.
- [x] P10.4 Implement movement duration, rest, and a bounded world clock without time advancement on queries.
  - Package A — pure rules (`org.rpgengine.rules`): `WorldClock` (bounds: one in-world day per operation, a
    four-hour legal wait, a clock end at 3,155,760,000 seconds; calendar reading; quest units) and `RestRules`
    (policy value, interruption from a recorded draw, elapsed time, clamped and halved restoration).
  - Package B — pack contract: location link `minutes` (0..1,440, default 0), `rule` type `rest` (policies with
    duration, optional location tag, interruption chance, restores, removed conditions; validated after the
    resource vocabulary) and the world-scope quest atom `worldTime` (seconds, minutes, hours, days).
  - Package C — persistence (`V21`, no new table): the clock is bounded on `campaign` and `campaign_event`, and
    the `campaign_world_time_step` trigger lets it change only together with a state-revision step, by at most
    one day, so no read or stray writer can advance it. Backup tool schema 21.
  - Package D — commands: `REST` (names a policy only), refusals `TIME_ILLEGAL` and `REST_UNAVAILABLE` (HTTP 422),
    `CommandRules` bounds wait and every duration, `LocalActionResolver` sets elapsed time per command, resolves
    a rest (one draw when interruptible) and feeds the clock to the quest engine; `ResolvedCommit` carries typed
    `ConditionRemoval`s and bounds the step; `WAIT` loads the catalog when available.
  - Package E — surfaces and docs: `ACTOR_MOVED` states its duration, `REST_COMPLETED`/`REST_INTERRUPTED`,
    `RESOURCE_RECOVERED` and `CONDITION_REMOVED` with EN/IT narration, a `rest` receipt summary, two reviewed
    frontend messages (355 in the catalog), ADR 0026, docs 05/07/09/10/13, backend README, CHANGELOG.
  - Limits: no panel or journal shows the clock (P10.5); a rest is interrupted by a draw, not an encounter
    (P11); condition durations are rounds, so waiting and rest do not tick them; no faction or event clocks or
    bounded catch-up (P15.2); one link per move; no live V21 dump and restore rehearsal.
  - Verification (29 October 2026, PostgreSQL 18.6, JDK 21, the runner's environment): all 16 plan commands
    passed on the implementation commit `89be9f1` (backend suite 875 tests, 0 failures; `./mvnw --batch-mode
    verify` the same count with 4 skipped `PackagedInterfaceTest` cases; frontend 79 tests, 355 reviewed EN/IT
    messages) and again on the final commit. New cases: `WorldClockTest`, `RestRulesTest` (pure), `ClockCommandsTest`
    (13 on PostgreSQL: authored travel time, bounded waits, the clock end, the V21 guards against any writer,
    clamped recovery, condition removal, a recorded interruption and its replay, refusals, clock quests finished by a
    wait and by a rest, a failed commit leaving the clock alone, attribution), `WorldClockQueriesTest` (14 read
    endpoints repeated three times while real time passes: 200 each, the clock, the revision and 20 tables unchanged;
    only the next command moves the clock), and additions to `LocalActionResolverTest` (both interruption branches
    with stub draws), `PackCompilerTest`, `CommandRulesTest` (a dependency pack's rest policy is refused), `TypedCommandTest`,
    `EventStatementsTest`, `ResolvedCommitTest`, `CampaignFoundationsTest` and the V21 step of `CanonicalMigrationUpgradeTest`.
    Eighteen weakenings applied to an exported copy each failed at least one test; one (`rest-vocabulary-unchecked`)
    first survived and gained a test (see [ADR 0026](adr/0026-bounded-world-clock-travel-duration-and-rest.md)).
    Not proven here: the 18 Docker-dependent probe cases, the `unzip` wrapper cases and a live V21 dump and restore.
- [x] P10.5 Integrate reliable state changes into panels and journal.
  - Package A — projection (`org.rpgengine.panels`): `PlayPanels` reads clock, effective sheet (base and effective
    attributes, skills, recomputed derived statistics), resources, conditions, abilities, inventory with worn slots,
    equipment, engine-state quests, typed knowledge and a cursor-paged public event history in one read-only
    repeatable-read snapshot; `PanelContents`/`PanelContent` cache the pinned quest rules, character rules and
    public localized text per sealed revision; `PlayPanelsController` serves `GET /api/v1/campaigns/{id}/panels`.
  - Package B — rules of disclosure: quests come from `quest_state`/`quest_objective` joined with the pinned rules
    (a row-less quest stands at its initial status); undiscovered quests and hidden background quests that have
    not ended are withheld; only `PUBLIC` events are worded (`EventStatements`, campaign language); names are public
    pack text or the stable identifier; unloadable content gives numbers without names or quests.
  - Package C — frontend: strict `parsePlayPanels`, `CampaignTransport.panels`, the read-only `CampaignPanels`
    component inside the playthrough view (re-read on every newer revision, older-events paging, fallback
    disclosure, content-missing notice), 57 reviewed EN/IT messages (catalog 412), re-recorded reload transcript.
  - Package D — docs: ADR 0027, docs 08/10/13/15/17, backend README, CHANGELOG. The old free-text
    `campaign_known_fact`/`campaign_known_quest` tables stay for the planner and checkpoint but no panel reads them.
  - Limits: no encounter, shop, map, rumor or relationship panels, no journal search or filters, no names for
    dependency-pack definitions, journal lines name quests and locations by identifier, no weight, capacity or
    durability, and no browser journey ran in the Linux container (specs replay the recorded server replies).
  - Verification (29 October 2026, PostgreSQL 18.6, JDK 21, the runner's environment): all 16 plan commands
    passed on the implementation commit `ff1f7be` (backend suite 884 tests, 0 failures; `./mvnw --batch-mode
    verify` the same count with 4 skipped `PackagedInterfaceTest` cases; frontend 99 tests, 412 reviewed EN/IT
    messages) and again on the final commit (887 backend tests). New cases: `PlayPanelsTest` (11 on PostgreSQL
    and the servlet stack, quest pack: the starting state; every committed change visible at its revision -
    accepted quest, satisfied objective, paid outcome, taken-off shield; a hidden background quest and internal
    events withheld, and an ended hidden quest listed without its secret text; a condition changing the effective
    sheet; cursor paging with a stable past; Italian statements for an Italian campaign; typed discoveries with
    stances, a statement-less belief skipped, promises with their direction and the legacy free-text fact ignored;
    byte-identical repeated reads that wrote no row; a refused command changing nothing; a save without a player
    and a save whose content cannot be loaded; a foreign owner; scope and input errors), `campaign-panels.spec.ts`
    (20: 12 refused shapes, wording, the routed view, hidden names, source-language disclosure, older-event
    paging), the panels read added to `WorldClockQueriesTest` and `CampaignInterfaceContractTest` (three recorded
    replies in the reload transcript) and the Italian-variant assertions on the recorded replies. Fifteen
    weakenings applied to an exported copy each failed at least one test; two first survived (a belief without a
    statement, which gained a test, and a second, redundant owner check that the earlier lookup makes
    unobservable). Frontend weakenings were not run.
    Not proven here: the 18 Docker-dependent probe cases, the `unzip` wrapper cases, a browser journey (the specs
    replay recorded server replies) and independent review of the 57 new Italian messages.

**Exit:** a coherent noncombat adventure loop uses actual resources, abilities, and objectives. **Checks:** QA-09's shared state preparation, QA-10, quests/rewards, time and rest bounds. **Suggested commit:** `Add reusable character inventory and quest systems`.

## 15. P11 — Deterministic combat foundation

**Depends on:** P10. **Areas:** combat domain, rules, persistence, fixtures.

- [x] P11.1 Implement encounter lifecycle, frozen per-adventure profiles around the default ruleset, zone graph, objectives and opening-action policy; validate supported customization without changing the scheduler kernel.
  - Package A — profiles (`org.rpgengine.rules`): `CombatProfile` = the fixed AI RPG Skirmish kernel
    (`skirmish-initiative-rounds/1`) plus the 25 provisional parameters of docs/11 section 17 with defaults,
    bounds and cross-parameter invariants; a pack `combat` rule maps the roles to its attributes, names Health
    and Focus and may tune parameters inside their bounds; another scheduler, space or unknown parameter is a
    located pack error. `CanonicalJson` freezes values with a SHA-256.
  - Package B — space and templates: `ZoneGraph` (range over bordering zones, movement over unblocked directed
    edges with difficult terrain, reachability, connectivity), the `encounter` pack kind (location, zones,
    edges, player and escape zones, participants with explicit statistics and threat tier, predicate objectives
    `defeatHostiles`/`escape`/`reachZone`/`survive`/`protect`, opening policy `initiative`/`first`/`forbidden`)
    with feasibility checks, `CombatRules` and `EncounterTemplate` in `org.rpgengine.combat`.
  - Package C — lifecycle and entry: `EncounterPhase` (the docs/11 state machine), the `ENGAGE` intention
    (`ENCOUNTER_UNAVAILABLE`), `FrozenEncounter` (player defenses from the role mapping, actual pools, worn
    items, conditions), one public `ENCOUNTER_STARTED` event, encounter mode, no time, draw or pool change, the
    opening strike stored once as pending; V22 `encounter`/`encounter_participant` with frozen-column,
    lifecycle, single-resolution and one-active-encounter guards; backup schema 22.
  - Package D — docs and interface: ADR 0028, docs 05/07/09/10/11/12/13/15, backend README, backup README,
    CHANGELOG, one reviewed EN/IT refusal message (catalog 413).
  - Limits: no initiative, activation or tactical intention (P11.2), so an entered encounter cannot progress or
    end; the opening strike is not resolved yet (P11.2/P11.3); items carry no Guard or armor modifier; no
    concealed participants or hazards; the V16 checkpoint records no encounter (P11.6); no frontend control or
    encounter panel (P12.1).
  - Verification (29 October 2026, PostgreSQL 18.6, JDK 21, the runner's environment): all 16 plan
    commands passed on `85f8849` (backend suite 924 tests, 0 failures; `./mvnw --batch-mode verify` the
    same count with 4 skipped `PackagedInterfaceTest` cases; frontend 99 tests, 413 reviewed EN/IT
    messages; repository checks 0 findings) and again on the final commit. New cases: `CombatProfileTest`
    and `ZoneGraphTest` (pure: documented defaults, every parameter bound, invariants, canonical hash;
    range, movement cost, blocking, connectivity), `EncounterLifecycleTest` (the phase table is exactly
    the docs/11 diagram; objective statuses), `CombatRulesTest` (projection, the worked example's Guard 12
    and initiative modifier 4, distinct-role formulas, refused contradictions, a customized profile through
    the same kernel), `CombatPackTest` (located diagnostics for another scheduler or space, unknown or
    out-of-bound parameters, conflicts, mappings, zones, feasibility and rosters), `EncounterCommandsTest`
    (7 on PostgreSQL: entry freezes everything with no draw, time or pool change and a pending opening
    strike; a replayed retry; refusals writing nothing; encounter mode refusing exploration and a second
    entry; the `first` policy; the V22 guards; a customized profile), plus additions to `TypedCommandTest`,
    `EventStatementsTest`, `ResolvedCommitTest`, `CanonicalCommitServiceTest`, `PackCompilerTest` and the
    V22 step of `CanonicalMigrationUpgradeTest`. Twenty-eight weakenings applied to an exported copy each
    failed at least one test; two first survived (a one-sided range distance and an opening strike against
    an ally) and gained tests.
    Not proven here: the 18 Docker-dependent probe cases, the `unzip` wrapper cases, a live V22 dump and
    restore, balance of the provisional numbers and independent review of the new Italian message.
- [x] P11.2 Implement initiative, rounds, activations, AP, movement, legal targeting and explicit end activation; enforce bounded speech and declared social/scene-action eligibility/costs at player decision boundaries.
  - Package A — scheduler (`org.rpgengine.combat`): `ROLL_INITIATIVE` (two recorded d10 per participant in
    roster order plus the frozen modifier; order by total, finesse, participant ID; stored once), `CombatScheduler`
    (round-open reaction refresh, activations over active participants in rank order, action points granted at
    activation start and expired at its end, `roundSeconds` per completed round, stop only at the player's own
    decision, round cap, no standing player), other participants passing until P11.3/P12 give them tactics.
  - Package B — player actions and legality: `TacticalRules` (turn, one-edge `MOVE_ZONE` with difficult terrain and
    capacity, addressing within `maxRangeEdges`, speech allowance, declared actions, action points) and the
    explicit `END_ACTIVATION`; refusals `TURN_UNAVAILABLE`, `ACTION_POINTS_INSUFFICIENT`, `ZONE_UNAVAILABLE`,
    `SPEECH_LIMIT_REACHED`, `INTERACTION_UNAVAILABLE` and `TARGET_ILLEGAL`; tactical intentions only in encounter
    mode, exploration intentions only in exploration mode.
  - Package C — bounded dialogue and declared actions: `COMBAT_SPEAK` (one free utterance per activation by
    default, at most 500 characters, no draw, time or cursor change) and `INTERACT` over the encounter's declared
    `social` (intent, recipient policy) and `scene` (zone, passage to block or open) actions at their declared
    cost; a social attempt establishes no outcome (P11.5).
  - Package D — storage and interface: V23 cursor, speech, passages, initiative and reactions with scheduling
    triggers; `TacticalPlan` written one row version per phase step; receipt `combat` block with the legal options;
    eight EN/IT event statements; five reviewed EN/IT refusal messages (catalog 418); backup schema 23; ADR 0029,
    docs 05/07/09/10/11/13/15, backend README, backup README, CHANGELOG.
  - Exit checks: the full verification plan passes; pure scheduler cases and real PostgreSQL cases prove recorded
    initiative, legal transitions across activations, exact costs, one round of time per operation, refusals
    without writes, no out-of-turn submission and the V23 guards; weakening probes on an exported copy fail tests.
  - Limits: non-player participants pass; the opening strike stays pending (P11.3); reactions refresh but are
    never spent (P11.4); no terminal predicate and no response to social attempts (P11.5); the V16 checkpoint
    records no encounter (P11.6); no frontend control or panel (P12).
  - Verification (30 October 2026, PostgreSQL 18.6, JDK 21, the runner's environment): all 16 plan
    commands passed on `bfffa7d` (backend suite 950 tests). A second review of that work then found
    two defects, fixed in `6f8dd50`: a role attribute above the supported 0..6 range could enter a fight
    whose `ROLL_INITIATIVE` then failed its V23 bound on every attempt, leaving the campaign at `ENTERING`,
    and U+0000 in command text ended as a failed operation instead of `INVALID_COMMAND`. All 16 plan
    commands then passed on `6f8dd50` (backend suite 955 tests, 0 failures; `./mvnw --batch-mode verify` the
    same count with 4 skipped `PackagedInterfaceTest` cases; frontend 99 tests, 418 reviewed EN/IT
    messages; repository checks 0 findings) and again on the final commit. New cases: `CombatSchedulerTest`
    (14, pure: initiative from recorded dice with both tie-breaks, one arrow per step, points granted and
    expired, passing participants, one round end of 8 seconds per operation, the round cap, skipped and
    non-standing participants, movement with difficult terrain, blocking and capacity, bounded speech,
    addressing range, declared social and scene actions, no out-of-turn legality, receipt options, frozen
    forms read back, the largest roster inside one operation's bound), `TacticalCommandsTest` (12 on
    PostgreSQL: recorded and replayed initiative, exact move, speech and action costs without draws, one
    round closed per end of activation, refusals writing nothing, mode and turn refusals, the clock bound,
    a customized profile, the V23 guards, the largest supported role values rolling, unstorable words
    writing nothing, and a seeded 240-step fight comparing every verdict and effect with an independent
    model of the fixture across activations and round boundaries), `CombatPackTest.refusesBrokenActions`
    and `refusesARoleAttributeBeyondTheSupportedRange`, plus additions to `TypedCommandTest`,
    `EventStatementsTest`, `ResolvedCommitTest`, `CampaignCommandHttpTest`, `EncounterCommandsTest`,
    `CombatRulesTest` and the V23 step of `CanonicalMigrationUpgradeTest`. Thirty-seven weakenings on an
    exported copy of `fd0a477` (29 in Java and the pack compiler, 8 in V23 run against a fresh schema after a
    passing baseline) each failed a test except four; three gained tests in `bfffa7d` (expired points, a
    non-standing player, the round clock bound) and the fourth (the progress attribution check) is
    redundant with the writer's campaign-scoped update. Eighteen more on an export of `6f8dd50` each failed
    a test: nine on the two fixes and the step bound, and nine scheduling and legality weakenings that the
    long fight caught by itself.
    Not proven here: the 18 Docker-dependent probe cases, the `unzip` wrapper cases, a live V23 dump and
    restore, balance of the provisional numbers and independent review of the five new Italian messages.
- [x] P11.3 Implement attack/defense/strong-hit/armor/healing calculations with recorded draws.
  - Package A — arithmetic and frozen data ([ADR 0030](adr/0030-attacks-defense-damage-and-healing-with-recorded-draws.md)):
    pure `CombatMath`; `CombatAttack` and `EncounterGear` from pack `combat` on equippable items and template `attacks`;
    worn Guard, armor and weapons frozen at entry under their caps.
  - Package B — resolution: `STRIKE` with a fixed draw order, ammunition per attempt, a public `STRIKE_RESOLVED` and an
    internal `STRIKE_CALCULATED`; reach, line of sight and cover in `TacticalRules`; `GUARD` until the next activation;
    defeat at zero Health; the opening strike resolved once at its slot or lapsed with a reason; a small non-player tactic.
  - Package C — healing and storage: the `heal` effect in exploration and in fights; `COMBAT_USE_ITEM`; V24 `guarding` and
    Health guards; refusals `ATTACK_UNAVAILABLE` and `GUARD_UNAVAILABLE`; receipt standings, strikes and options.
  - Exit checks: the worked fight's numbers from recorded dice, exhaustive arithmetic, every targeting rule, no redraw on
    retry, seeded fights keeping their invariants, the V24 guards and all plan commands; weakening probes on an exported copy.
  - Limits: Brace, condition modifiers, absorption, abilities in a fight and multi-target items wait for P11.4; Ready,
    Assess and Withdraw for P11.4/P11.5; no terminal predicate, so a fight whose player fell waits at a round end and a won
    fight runs on until P11.5; the non-player tactic is a small fixed rule without objectives (P12.3/P12.4); the V16
    checkpoint records no encounter (P11.6); no frontend control or panel (P12).
  - Verification (30 October 2026, PostgreSQL 18.6, JDK 21, the runner's environment): all 16 plan commands passed on
    `db33432` (backend suite 1,010 tests, 0 failures; `./mvnw --batch-mode verify` the same count with 4 skipped
    `PackagedInterfaceTest` cases; frontend 99 tests, 420 reviewed EN/IT messages; repository checks 0 findings; backup 9;
    repository tests 38 with 2 skipped; runtime probe 116 with 18 skipped, no Docker). New cases: `CombatMathTest` (the
    worked numbers and exhaustive sweeps: all 100 two-die outcomes against defenses 0..40, about 1.25 million damage
    combinations, every healing combination up to 40), `CombatStrikeTest` (pure: the worked fight of docs/20 from its
    recorded dice without Brace, draw order, attack definitions, every targeting rule, ammunition, options, Guard, both
    opening slots and each lapse, the tactic, item outcomes, gear caps, determinism, the largest armed roster at 18 + 4 +
    56 draws, 50 seeded fights replayed), `StrikeCommandsTest` (19 on PostgreSQL: recorded and replayed strikes, refusals
    writing nothing, an opponent felling the player down to exactly zero, the stance met by the next strike, opening
    strikes, cover and ammunition, healing the player and an ally within the maximum, `NOT_REVIVED`, unsupported effects,
    the V24 guards, older frozen forms and receipts), `CombatRulesTest.projectsAttacksAndGear`,
    `CombatPackTest.refusesBrokenTemplateAttacks` and `refusesBrokenItemCombat`, the long fight of `TacticalCommandsTest`
    with strikes and Guard in its independent model (720 seeded candidates), plus additions to `TypedCommandTest`,
    `EventStatementsTest` and the V24 step of `CanonicalMigrationUpgradeTest`. Seventy-four weakenings on an exported copy of
    `16fc8ce` (5 of them in V24 against a fresh schema after a passing baseline) each failed a test except ten, none by a
    compile error; those ten and two more on the receipt fix gained tests in `db33432` and all twelve then failed a test.
    Not proven here: the 18 Docker-dependent probe cases, the `unzip` wrapper cases, a live V24 dump and restore, Brace in
    the worked fight, balance of the provisional numbers and independent review of the two new Italian messages.
- [ ] P11.4 Implement predeclared reactions, stacking caps, effect phases, durations, cooldowns, and no-chain limits.
- [ ] P11.5 Implement defeat, escape, surrender/agreement predicates, refused/counteroffered/explicitly accepted negotiation, stalemate policy and idempotent settlement; prose alone cannot establish an outcome.
- [ ] P11.6 Persist/recover every combat cursor, partial-round time boundary and combat-dialogue operation/transcript without duplicate social effects or settlement.

**Exit:** seeded fights produce reproducible legal outcomes and all terminal paths return to a valid narrative state. **Checks:** QA-09, QA-11–13, effect properties and restore mid-round. **Suggested commit:** `Implement deterministic turn-based combat`.

## 16. P12 — Combat interface and AI tactics

**Depends on:** P11 and P08; UI foundation P05. **Areas:** legal-action API, combat panels, NPC task contract.

- [ ] P12.1 Show initiative, AP, zones, known defenses/conditions, costs, targets, and objective without leaking hidden data.
- [ ] P12.2 Allow named actions and a persistent combat dialogue/action chat with known recipients, attributed replies and editable quick replies; validate talk, negotiation and scene-changing intent, preview consequential costs and preserve explicit player submission/decisions.
- [ ] P12.3 Provide actor-knowledge-scoped legal candidates to Codex for NPC/companion choices.
- [ ] P12.4 Add deterministic tactic fallback, interruption messages, bounded knowledge-scoped combat replies and outcome narration; dialogue failure leaves committed state and tactical controls available.
- [ ] P12.5 Verify mobile/keyboard play, chat/tactics switching, draft preservation, no out-of-turn/automatic submission and session renewal/reconnect inside a fight, including negotiated settlement retries.

**Requirement update (24 October 2026):** the owner requests free-input chat during
combat to talk to participants and influence subsequent events. The design now
defines bounded speech, rule-validated social/scene actions, explicit agreement
decisions and transcript recovery in [combat](11-combat-system.md#71-dialogue-and-scene-changing-intent-during-combat),
[UI](13-api-and-ui.md#71-combat-chat), command/AI contracts and QA-09/11/13.
This is documented future work for P11/P12; it does not mark implementation or
acceptance complete, add a new task, or require combat in the P05.1 preview shells.
Documentation checks pass for repository policy, relative links/anchors and Markdown
fences. All 117 task identities and their 31 checked statuses are unchanged by
this update. No combat runtime/browser result is claimed.

**Exit:** the complete fight is playable through the actual UI, including reconnect and resume. **Checks:** real-browser combat path, QA-07, QA-09, QA-16, tactic-invalid/provider-unavailable fallback. **Suggested commit:** `Add tactical combat UI and constrained opponent tactics`.

## 17. P13 — Dynamic encounter and advancement pipeline

**Depends on:** P11–P12. **Areas:** encounter planning, content variants, progression, rewards UI.

- [ ] P13.1 Implement capability snapshot and deterministic threat/roster/resource constraints.
- [ ] P13.2 Implement template-bounded Codex encounter proposals, one repair, and safe fallback.
- [ ] P13.3 Freeze admitted content; validate reinforcement phases, objectives, escape, and hard counters.
- [ ] P13.4 Implement challenge-source rewards, loot ownership, cumulative thresholds, training, unlocks, and pending purchases.
- [ ] P13.5 Add advancement preview and comparison without automatic player build changes.

**Exit:** two eligible scenes yield valid varied encounters; settlement and progression use actual final state exactly once. **Checks:** QA-14–15, no-ammunition/low-health parties, multi-threshold awards, quest/fight reward deduplication. **Suggested commit:** `Add bounded dynamic encounters and character advancement`.

## 18. P14 — Playable MVP validation

**Depends on:** P00–P13. **Areas:** cross-module tests, sample packs, installation docs.

- [ ] P14.1 Complete one end-to-end neutral adventure slice per genre fixture in English and Italian, including combat, localized terminology and return to exploration.
- [ ] P14.2 Verify reboot, AI renewal, interrupted generation, lost response, backup restore, and no duplicate effects.
- [ ] P14.3 Run the required reduced-scope MVP QA matrix and record fresh evidence.
- [ ] P14.4 Measure baseline latency/context use and list unresolved defects honestly; demonstrate one optional bilingual baseline/planner investigation comparison when available, or record deferred/unavailable planning with the playable fallback. Advanced planning cannot block MVP.
- [ ] P14.5 Write reproducible setup and an accurate MVP capability table.

**Exit:** a fresh installation can play, fight, save, resume, and change adventure files without editing engine source. Critical integrity/security failures block the milestone. **Suggested commit:** `Validate the first playable engine milestone`.

## 19. P15 — Complete supported world and optional modules

**Depends on:** P14. **Areas:** social/world/quest modules, guide, creative abilities.

- [ ] P15.1 Add NPC goals/schedules, witnesses, knowledge propagation, companions, and relationship causes/commitments.
- [ ] P15.2 Add richer quest outcomes, faction clocks, bounded time catch-up, reputation, and causally triggered world changes.
- [ ] P15.3 Add guide personas with separate knowledge/session scope and no mutation authority.
- [ ] P15.4 Add creative ability drafting, validation preview, explicit execution, learned recipes, and bounded high-power profiles.
- [ ] P15.5 Prove disabled modules disappear cleanly and enabled modules remain adventure-independent.
- [ ] P15.6 Qualify optional adaptive quest/world-direction tasks using event-triggered significant actors, causal relationship histories and deterministic off-screen catch-up; enable only after agency/coherence/cost comparison, otherwise document disabled/deferred capability with verified baseline fallback.

**Exit:** all supported gameplay modules have typed data, rules, projections, and meaningful integration coverage. **Checks:** QA-21–22 and full requirement-specific scenarios. **Suggested commit:** `Complete reusable world companion and optional ability modules`.

## 20. P16 — Author and owner operations

**Depends on:** P14; integrates P15 definitions. **Areas:** content migration, export/restore, corrections, diagnostic tools.

- [ ] P16.1 Complete validation reports, author previews, dependency graph diagnostics, and content version inspection.
- [ ] P16.2 Add full-owner versus player-facing campaign exports and reusable adventure-bundle import/export for sharing between people, with integrity/dependency/locale/rights manifests; prove clean-recipient round trips and no private save/account leakage under QA-41.
- [ ] P16.3 Add migration/correction previews, dependency impact, backup, audit, and stale-memory invalidation.
- [ ] P16.4 Restore into explicit adventure/campaign destinations with fresh identities by default, including original artwork, active combat and pending narration; never merge by equal titles.
- [ ] P16.5 Document manual file authoring with complete neutral examples and troubleshooting.

**Exit:** an author can create and evolve content safely without a visual editor; an owner can recover data independently. **Checks:** QA-02, QA-16, QA-23, incompatible upgrade and corrupted archive tests. **Suggested commit:** `Add safe adventure and campaign administration`.

## 21. P17 — Presentation, assets, and accessibility

**Depends on:** P14; uses P15–P16 metadata. **Areas:** asset registry, theme/layout, optional audio, frontend polish.

- [ ] P17.1 Complete scene/map/portrait/item/UI category handling, stable entity artwork, identifying shop/inventory/equipment icons, accessible rarity/frame treatment, hash-based serving, stale-job protection and visible credits.
- [ ] P17.2 Implement optional dynamic music from a reviewed free-license online/local catalog: public-event mood mapping, deliberate activation, stable loops/crossfades, deduplicated cues, volumes, complete current-track/export credits and offline/source-failure fallback under QA-32.
- [ ] P17.3 Finalize the default visual-novel and alternative text-focused layouts, graphic states, configurable tokens and attributed reading/history/quick-reply behavior.
- [ ] P17.4 Run real desktop/narrow-screen/keyboard/zoom/reduced-motion/accessibility checks and fix blocking issues.
- [ ] P17.5 Verify missing art/audio never blocks play and media cannot reveal secrets.
- [ ] P17.6 Implement bounded runtime scene/portrait/item image jobs using verified capability, public appearance inputs, stable identities, durable saved assets, budget limits, deduplication and restore/stale-result checks under QA-31.
  **Requires tasks:** P21.5.
  - QA-33 scope (25 October 2026): besides QA-31, this task proves that generated
    images and their associations stay in the originating adventure folder for
    same-name entities across switching, late job results and backup/restore.
    P06.5 covers authored artwork and presentation media at the MVP scope.
- [ ] P17.7 Qualify optional local EN/IT speech with admitted model/voice licenses, stable voice identities, accepted-line audio caching and shared GPU limits under QA-38; ship disabled with tested text-only fallback if hardware/rights/quality gates fail. No TTS prerequisite for core v1.
- [ ] P17.8 Research and qualify a concrete optional local EN/IT STT backend against exact code/weight/dependency rights, bounded capture/cancel interfaces, offline behavior, platform packaging and measured CPU/RAM/GPU/latency budgets; no recognizer is selected by the design alone.
  **Requires tasks:** P21.4.
- [ ] P17.9 Implement explicitly enabled scoped microphone capture, accessible listening/mute states, draft-only recognition, voice-only preview/correction/repeat/confirmation/cancel and normal command/receipt routing for exploration and combat; suppress playback echo and never auto-queue out-of-turn input.
- [ ] P17.10 Run QA-45 in EN/IT with silence/noise/accent/negation, TTS/interruptions, duplicate audio/results/confirmations, reconnect/restart/cancel, same-name adventure isolation and shared-resource failures. Enable only the measured qualified profile, or record disabled capability and verified no-capture/complete typed fallback; do not claim unimplemented voice support.

STT is separate from P17.7's TTS output. Dictation does not require TTS; claiming
full hands-free play additionally requires admitted audible public previews and
voice-only correction/confirmation. P17.8–P17.10 may conclude with a documented
disabled disposition and tested fallback if qualification fails; this never
counts as implementation of the disabled capability or blocks core text play.

**Exit:** the graphical visual-novel UI, persistent identifying artwork and bounded image-job pipeline are verified; unsupported generation degrades accurately without blocking play. Optional dynamic music follows [its specification](24-dynamic-music-and-credits.md); no player or audio integration is delivered by adding that document. TTS and STT are optional; disabled/no-capture/text fallback is required. **Checks:** QA-16, QA-19, QA-25, QA-29–32, QA-45 at enabled or disabled scope, actual visual review and asset/license inventory. **Suggested commit:** `Complete accessible presentation and optional atmosphere`.

## 22. P18 — Full-engine hardening and balance

**Depends on:** P15–P17, P20 and P21. **Areas:** long-run evaluation, concurrency/security, performance, simulations.

- [ ] P18.1 Run the complete QA register and 500-turn continuity evaluation with recorded evidence.
- [ ] P18.2 Run adversarial prompt/content/secret tests and Codex supported-platform isolation checks.
- [ ] P18.3 Calibrate AI RPG Skirmish with simulations and human playtests; update threat/rules defaults with versioned changes.
- [ ] P18.4 Profile queries, outbox, memory retrieval, context budgets, AI calls, and latency; optimize measured bottlenecks.
- [ ] P18.5 Rehearse backup recovery, supported migrations, degraded provider mode, and owned-process shutdown.
- [ ] P18.6 Record paired baseline/planner/reviewer comparisons in English and Italian at 100/500 turns, including agency, secrets, wasted work, latency/quota and recovery; publish unrun/unavailable narrative cells honestly under QA-39; benchmark optional speech/GPU under QA-38 only when enabling it.

QA-45 adds mandatory disabled/no-capture/text fallback checks; full STT and
hands-free measurements are conditional on enabling the optional profile.

**Exit:** all v1 hard gates pass, balance limitations are published, and no integrity/security-critical defect remains open. **Suggested commit:** `Harden the complete engine for long campaigns`.

## 23. P19 — GitHub and v1 release preparation

**Depends on:** P18 and owner publication decisions. **Areas:** public repository, docs, license/credits, release artifacts.

- [ ] P19.1 Verify the publication location for AI RPG Engine and repository slug `ai-rpg-engine`, retain the root MIT license, and review upstream notices and all bundled rights.
- [ ] P19.2 Remove private saves, logs, credentials, generated personal content, machine paths, and misleading status/badges.
- [ ] P19.3 Run fresh-clone `start.sh` installation and sample-play rehearsal without host build toolchains; record supported host/shell/container/runtime versions and persistence/recovery results.
- [ ] P19.4 Finalize README, screenshots from actual software, author guide, troubleshooting, security policy, contribution guide, changelog, and release notes.
- [ ] P19.5 Prepare release candidate, known limitations, checksums, and publication checklist for owner review.
- [ ] P19.6 Publish repository/release only under the owner's explicit publication instruction; verify resulting links and artifacts.

**Exit:** the published material accurately represents a tested engine and includes no private predecessor campaign. **Suggested commit:** `Prepare engine v1 documentation and release assets`.

## 24. P20 — Selectable Claude narration runtime

**Depends on:** P01, P02, P08–P09. **Areas:** `docker/` runtime images, `compose.yaml`, `start.sh`, backend Claude adapter and runtime selection, `tools/` fixtures, [Claude runtime integration](33-claude-runtime-integration.md). Decision: [ADR 0020](adr/0020-selectable-narration-runtime.md).

Scheduling: this package follows the core packages in roadmap order and gates P18 hardening and P19 release. The MVP (P14) does not depend on it. Codex stays the default throughout. Claude is not called supported until the P20.1 evidence and the recorded terms review exist.

- [ ] P20.1 Pin and qualify the unmodified Claude Code client without a live account: exact version, installation method and integrity check; the fixed launch profile and supervisor protocol; fixture-based proof (a scripted upstream only if the pinned client documents a supported base-URL override) that tools, MCP, skills, hooks, project and home configuration, and credentials of the other kind are unavailable or refused; the capability matrix, the model and effort readiness approach, and the limit, overage and error signals; session and transcript retention and purge behavior. Record the terms-review inputs for both sign-in modes.
- [ ] P20.2 Package the optional `claude` runtime service following ADR 0013 and ADR 0008: pinned image, read-only root, dropped capabilities, private account volume and IPC, no database route, no credentials in images or `.env`, startup guards, a Compose service started only when selected, `NARRATION_RUNTIME` handling, and `start.sh --runtime`, `--login`, `--check` and `--status` for the selected runtime. Extend QA-28 evidence and keep Codex behavior unchanged.
- [ ] P20.3 Implement the Claude adapter behind the runtime-neutral task port: typed tasks with `--json-schema` outputs revalidated by the engine, per-task model and effort from an approved allowlist, session create/resume/retire with purge, bounded readers, SIGINT-first interruption with owned-process shutdown, limit, overage, authentication and billing mapping, selected-runtime diagnostics (`GET /api/v1/runtime/status`), and the startup comparison that retires sessions and records an audit event when the selection changed.
- [ ] P20.4 Verify parity and the supported claim: run the same neutral fixtures through both adapters (QA-42), including switch and restart, no failover, limit and overage, unknown-setting and unsupported-version refusal, mixed-credential guards and secret sentinels. The owner runs an opt-in live neutral smoke for the chosen sign-in mode. Record the terms-review outcome. If it rejects a mode, document that mode as disabled and keep the rest.

**Exit:** the owner can select Codex or Claude with one setting; exactly one runtime serves tasks; neither can write canonical state or use tools; unavailability never switches runtimes; and the documentation states which Claude sign-in modes are supported and why. **Checks:** QA-42, extended QA-28, QA-16 secret sentinels, and QA-33–35 unaffected by a switch. **Suggested commit:** `Add a selectable second narration runtime`.

## 25. Requirement coverage

| Requirements | Owning packages |
| --- | --- |
| FR-001–003 | P03–P06, P08, P16 |
| FR-004–007 | P07–P08, P12; FR-005 selection also P20 |
| FR-008–010 | P06, P10 |
| FR-011–013 | P03, P09, P18 |
| FR-014–017 | P11–P13, P18 |
| FR-018–021 | P10, P15 |
| FR-022 | P17 |
| FR-023–025 | P01, P03, P07–P09, P16, P18; FR-025 also P20 |
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
| FR-038 | P08 (runtime-neutral port), P20; QA-42 |
| FR-039 | P08 (slot-aware port), P21; QA-43 |
| FR-040 | P17.6, P21; QA-44 |
| FR-041 | Optional P17.8–P17.10, P18; QA-45; command/combat foundations P07/P11–P12 |
| NFR-001–015 | Cross-cutting gates in P01, P03, P07–P09, P16–P19 |

## 26. Explicitly later initiatives

Semantic retrieval/pgvector after retrieval evidence; visual authoring editor after core v1; advanced voice design/fine-tuning or true streaming after separate rights/quality/runtime proofs; alternative combat schedulers and grid maps; public multiplayer hosting; online marketplace/hosted sharing infrastructure; generic AI providers (beyond the closed protocol set of ADR 0020 and ADR 0021); a ComfyUI workflow adapter, multi-GPU or remote GPU scheduling, model training or fine-tuning, and automatic model downloads; mobile-native or desktop packaging. Each needs its own scope, design, risks, and roadmap. None is an implicit requirement for the complete initial engine. Bounded runtime image generation is in v1 under ADR 0011; local EN/IT speech is independently optional under ADR 0016. Portable story-file exchange is a v1 P16 requirement and does not require a hosted marketplace.

The owner explicitly requests the integrated text/graphical adventure editor **after core v1**. Its ordered [E01–E06 roadmap](26-adventure-editor-roadmap.md) covers world/history/requirements, mandatory characters, author-fixed versus generatable gaps, fantasy/cyberpunk content, supported combat-profile controls and validate/publish/migrate previews. It is deliberately outside P00–P19 and does not delay the initial engine release. Fixed author constraints and bounded combat customization remain file-contract requirements now; the future editor writes that same contract. QA-34 validates those semantics independently of an editor UI.
