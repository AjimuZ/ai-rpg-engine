# Backend development shell

P07.1 acceptance verification (27 October 2026): continue the preserved
implementation from owner commit `174be3e` and pass 96 focused command tests plus
all 359 packaged backend tests in 29 suites on the isolated PostgreSQL 18.6 fixture,
with zero failures, errors or skips. Two new cases force lease expiry during actual
renewal/admission owner-lock contention in both acquisition orders. The expired
renewal fails and the competing campaign takes the available slot without exceeding
the two-live-permit cap. Production behavior and toolchain pins are unchanged.
The configured finite independent checks also pass frontend installation,
typechecking, 49 tests, four localization checks and both production locales.
See the [acceptance evidence](../docs/15-quality-and-acceptance.md) for scope and
limits. The recovery checkpoints below are historical, including their then-current
unchecked task status; they do not describe a current dependency blocker.

Restored-fixture verification (27 October 2026): the owner restored the isolated
PostgreSQL service. A fresh scoped DNS/TCP check now succeeds; the unchanged private
helper passes all 94 focused command tests and all 357 packaged backend tests with
zero failures, errors or skips. This supersedes the connectivity blocker in the
historical checkpoint below. The owner reports an `unless-stopped` restart policy
and a successful host restart rehearsal; this session does not have container
controls and does not independently repeat that rehearsal. Preserve the same scoped
helper and disposable database settings. The reviewed interrupted implementation is
checkpointed for normal admission, with P07.1 still unchecked during recovery.

Continuation verification checkpoint (27 October 2026): the private helper still
executes JDK/Maven, but its disposable PostgreSQL fixture hostname is unavailable
from the current session (`UnknownHostException`). The full packaged check reports
357 tests: 188 pass and 169 error during database/application-context setup, with
zero assertion failures or skips. No database acceptance pass is established by this
run. There is no Docker/Podman client, container socket or PostgreSQL service tool
in this session to repair that network attachment. Restore the owner-provisioned
fixture's reachability without changing test requirements, toolchain pins or owner
configuration; then rerun the focused corpus and full helper. Frontend checks and
both locale builds pass. Earlier successful runs below remain historical evidence;
P07.1 stays unchecked and its implementation remains pending verification/commit.

Spring Boot 4.1.2 on Java 21, with Maven 3.10.0 through the checksum-verified
Maven Wrapper 3.3.4. Run commands **from `backend/`** so `.mvn/maven.config`
applies the Central-only mirror and strict checksums. On Linux the wrapper extracts
its checksum-pinned ZIP with `unzip`, or with the JDK `jar` tool when `unzip` is
missing (see below). On Windows use `mvnw.cmd` instead of `./mvnw`.
The enforcer rejects other JDK majors, Maven outside 3.10.x, snapshots, and dynamic
versions. Library and plugin versions come from the Boot parent/BOM.

**Hosts without `unzip`.** Upstream Maven Wrapper 3.3.4 fetches the `.tar.gz`
distribution when `unzip` is missing. That archive can never match the committed ZIP
checksum, so a host with an empty wrapper cache stopped with `Failed to validate Maven
distribution SHA-256, your Maven distribution might be compromised`. Nothing was
compromised: Maven Central serves the pinned ZIP and its SHA-256 equals the pin.

Since 29 October 2026 `./mvnw` is a marked, modified copy ([ADR 0009](../docs/adr/0009-toolchain-baseline.md)).
It always downloads the pinned ZIP and checks its SHA-256. Without `unzip` it then
extracts the ZIP with the `jar` tool of the JDK it finds through `JAVA_HOME` or `PATH`,
and makes the `bin/` launchers executable. No setup or cache seeding is needed; a
first run only needs the JDK and access to Maven Central. A host that has neither
`unzip` nor a JDK now stops before downloading with `Neither unzip nor a JDK is
available`. A host with `unzip` but no downloader (no `wget`, `curl` or JDK) stops
with `Cannot download …`, where upstream reported a checksum error for a file it
never fetched. A failed Java download names the URL (`Java: Failed to fetch …`).

**Homes on a `noexec` mount.** The wrapper installs Maven under `~/.m2`. When that
directory is on a filesystem mounted `noexec` (for example a tmpfs home in a hardened
container, as in the independent verification run), the kernel refuses to execute
`bin/mvn` although it is 0755, and upstream exits with `./mvnw: …: exec: …/bin/mvn:
Permission denied` (status 126). Since 29 October 2026 the wrapper detects a launcher
that cannot be executed directly (`test -x` is false there on Linux) and runs it
through the interpreter on its `#!` line, `/bin/sh` for Maven. The JVM itself comes
from `JAVA_HOME` or `PATH` and must be on an executable mount. Maven 3.10.0 then logs
`JLineNativeLoader` warnings ("Failed to load native library … failed to map segment
from shared object"), because its terminal library cannot map native code from that
mount or from a `noexec` `/tmp`. JLine falls back to its non-native terminal, and the
build is not affected. Set `MAVEN_USER_HOME` to an executable location to avoid them.

The wrapper caches Maven under `$MAVEN_USER_HOME/wrapper/dists/apache-maven-3.10.0/<hash>`
(`MAVEN_USER_HOME` defaults to `~/.m2`; the hash is derived from `distributionUrl`). A
directory there is reused as is, so delete it to force a fresh, verified download.
`MVNW_VERBOSE=true ./mvnw --version` shows which download and extraction path is used.
The regression test `tools/repository_checks/test_maven_wrapper.py` runs this script
against a local fixture distribution, with and without `unzip`, and with a launcher
that cannot be executed directly (cleared execute bits, and a real `noexec` mount when
the host has a writable one).

Initial recovery prerequisite diagnostics (26 October 2026): the continuation
environment could not run the backend at that checkpoint. Both `java` and `javac`
must resolve to JDK 21 (or be available under `JAVA_HOME`); a JRE alone cannot satisfy the build. At that time
the wrapper needed `unzip` to select the pinned ZIP (since 29 October the JDK `jar`
tool is the fallback, see above). `curl` or `wget` can download it; without
both, the wrapper falls back to compiling and running its Java downloader. An
installed standalone Maven is unnecessary when the wrapper prerequisites are met.

If the wrapper reports missing Java followed by a distribution-checksum error,
repair those prerequisites before diagnosing the archive. On this environment no
downloader could execute. An independent download of the exact 3.10.0 ZIP was
10,102,082 bytes and matched the committed SHA-256
`1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff`.
The checksum and version pins therefore remain unchanged. This artifact check
is not a Maven execution, compilation or database-test result. The attempted
Temurin metadata lookup returned HTTP 403; no JDK or database was provisioned.
Resume only once the documented JDK and real disposable database are available.

A verification entrypoint that calls `mvn` directly also requires the pinned Maven
3.10.0 executable on its own `PATH`; a downloaded ZIP or a wrapper available only
in another shell does not satisfy it. Packaged verification additionally requires
`npm ci` and `npm run build:locales` in `frontend/` with the pinned Node/npm versions
before building the backend. Preserve both locale bundles and extracted license
notices. At the initial continuation checkpoint, the direct packaged verification
command failed with `mvn: not found`; frontend dependencies, compiled locale output
and the three test datasource environment variables were absent too. Provision all
these prerequisites before expecting a packaged backend verification result. Do not
substitute an unpackaged run for the packaged gate.

Verified dependency recovery (26 October 2026): the owner-provisioned private
verification helper now loads JDK/javac 21.0.12.1, checksum-pinned Maven 3.10.0,
Node 24.22.1/npm 11.19.1 and scoped datasource settings for a separate disposable
PostgreSQL 18.6 fixture. Run its `frontend-install`, `frontend-typecheck`,
`frontend-test`, `frontend-i18n` and `frontend-build` modes before `backend`.
The helper executes each command in its module directory; backend uses
`mvn -B -ntp verify -Ppackaged-ui`, retaining strict checksums and the Central-only
mirror. Private helper paths and environment values belong in the recovery handoff,
not source configuration. The owner stages runner environment/check activation at
its next safe boundary; this recovery does not change that configuration.

The actual helper passes typechecking, all 49 frontend tests, four localization
checker tests, parity for 240 reviewed messages, both production locale bundles
and all 318 backend tests with zero failures, errors or skips. The backend run
compiles 111 production and 26 test sources and packages the executable jar.
Its 49 canonical commit tests include duplicate races, transaction rollback,
lease contention, expired-holder fencing, draw/receipt replay and restart recovery.
These verify the existing foundations, not the eight uninstalled P07.1 drafts.
The unscoped wrapper still fails distribution checksum validation; `unzip` remains
absent, and the wrapper selects a tar archive when it is unavailable even though
the committed checksum pins the ZIP. Keep the pins intact and use the verified
private helper for this environment. Wrapper use still requires its documented
ZIP extraction prerequisite; no wrapper pass or system-wide provisioning is claimed.

P07.1 now installs the bounded command canonicalizer, atomic admission service,
shared owner permit accounting and HTTP admission/status in the existing campaign
adapter. The eight original migration drafts remain preserved externally; source
contains reviewed implementations and tests. The previous 318-test recovery result
above describes the prerequisite baseline, not the current command corpus.

With the scoped test environment loaded, run the focused corpus from `backend/`:

```sh
mvn -B -ntp test -Ppackaged-ui -Dtest=CommandCanonicalFormTest,OperationAdmissionServiceTest,CampaignCommandHttpTest,CanonicalCommitServiceTest,CampaignOperationTest,ResolvedCommitTest
```

Then use the private helper's `backend` mode for the full packaged gate. The
[protocol](../docs/07-turn-protocol.md#p071-durable-admission) and
[API](../docs/13-api-and-ui.md#p071-admission-and-status) distinguish durable
admission from the later gameplay executor; no UI Send action is enabled here.

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
`docker stop engine-dev-db`. Durable shell storage uses the root Compose profile.

From `backend/`, with those variables set:

```sh
./mvnw -B -ntp verify
./mvnw -B -ntp spring-boot:run
```

`verify` starts an ephemeral HTTP server and checks minimal health, hidden diagnostic
endpoints, PostgreSQL major version, Flyway validation, and the canonical schema.
A missing database fails tests; nothing silently skips or uses H2. Use a new
disposable database: the test requires exactly the thirty-five domain tables created by
`src/main/resources/db/migration/V1__canonical_foundations.sql` (P03.1),
`V2__campaign_bounds_and_versions.sql` (P03.2, adding `actor_resource`) and
`V3__canonical_outbox.sql` (P03.3), `V4__deterministic_random_draws.sql` (P03.4) and
`V5__campaign_mutation_leases.sql` (P03.5) and
`V6__durable_jobs_and_planning.sql` (P03.6), `V7__pack_registration.sql` (P04.4) and
`V8__localized_pack_reports.sql` (P04.6, report-version constraint only) and
`V9__campaign_creation.sql` (P06.1, character state, grants and request identities) and
`V10__player_knowledge.sql` (P06.2, explicit player-audience facts and quests),
`V11__operation_stream_and_presentation_jobs.sql` (P07.5) and
`V12__plan_admission_gate_and_activation.sql` (P07.6), with no rows, and Hibernate
validates the entities against them (`ddl-auto=validate`).
The persistence tests truncate these tables before and after each test, so never
point `verify` at a database whose data you want to keep.
P03.2 adds storage row versions separate from canonical counters, frozen ownership,
bounded actor resources and complete write-once journal outcomes. Integration tests
include cross-campaign rejection, scoped reads, concurrent duplicate inserts and
stale-writer rollback, plus populated V1-to-V10 upgrade in a disposable schema.
P03.3 adds an internal `CanonicalCommitService` consuming immutable `ResolvedCommit`
values from trusted backend resolution. It atomically saves world/resource changes,
events, settlements, a backend receipt and reference-only outbox intents, and returns
only after commit succeeds. It rejects ambient transactions and has no network ports
or executable callbacks. P07.1 adds owner-scoped operation acceptance/status;
gameplay authorization/rules, execution workers, outbox delivery and narration
remain later tasks.
`CanonicalCommitServiceTest` uses real PostgreSQL failure triggers (including deferred
commit failures), duplicate/stale races and scoped references; no runtime account is needed.
See [database invariants and payload limits](../docs/05-domain-model.md#3-database-organization).
P03.4 adds backend-only `RandomDrawService.reserve(context, ordinal, sides)` with
private lazy campaign seeds and immutable operation/ordinal reservations. HMAC-SHA-256
v1 uses a pinned byte protocol and unbiased rejection sampling; same-operation retries
reuse durable values after canonical rollback or service restart. Reservation requires
no ambient transaction and never changes world counters. Commit receipts include
ordered references/values, never seed bytes. No public draw or speculative planner API
is exposed; equivalent-new-attempt policy remains later work.
P03.5 adds `MutationLeaseService` (acquire/renew/release one campaign lease using
PostgreSQL time, with a persistent fence that only grows). Draws and commits require
the current token in `ResolvedCommit.Context`. `JournalStartupReconciliation` runs
`JournalRecoveryService` before the web server starts, and any failure aborts
startup. See the [recovery matrix](../docs/07-turn-protocol.md#8-interrupt-and-recovery-matrix)
and the [database backup tool](../tools/database_backup/README.md).
P03.6 adds the internal `preparation` package: deduplicated jobs and fenced attempts,
immutable snapshots with assembler freshness tokens, versioned plan/dependency and
admission records, query epochs, cancellation/recovery state and durable usage counts.
Critical jobs, epoch bumps and plan invalidation commit with the canonical outbox.
Admission preserves the complete snapshot manifest and checks query epochs, generation,
lease and public locale/glossary tokens under database locks. Unsupported raw predicate
and constraint rows fail closed; P08.7 plan predicates are compiled onto typed reads. `JobStartupReconciliation` gates claims
and admissions until recovery completes; expired attempts require confirmed external
stop before requeue. Restore advances generations and invalidates usable artifacts.
No dispatcher, runtime cancellation transport, global quota enforcement, plan-consumption
API or gameplay validation is exposed. The full V6 verify passes 113 tests with three
packaged-interface skips; a populated backup/restore and packaged startup rehearsal
also passes (24 October 2026).
The executable jar is `target/engine-0.1.0.jar`; it can also run with `java -jar`.

P04.1 adds internal `content.PackParser.parse(Map<String, byte[]>)` for caller-staged
inventories: a strict contract-1 manifest, safe bounded YAML/JSON, explicit file
categories, source diagnostics and sorted raw-byte SHA-256 identities. The
Boot-managed SnakeYAML dependency is used only for parser events; custom core
scalar resolution bypasses YAML 1.1 typing and all object constructors. Bundled
draft-2020-12 schemas describe the manifest and preliminary definition envelope.
The focused corpus runs with `./mvnw -B -ntp -Dtest=PackParsingTest test` without
PostgreSQL. `ParsedPack` establishes parsing only; callers must use staging and
registration below for admission, and later export workflows must verify rights.
The 63 parser cases and full JDK 21/PostgreSQL 18.6 backend `verify` pass: 176
tests pass, three packaged-interface profile tests skip, and the built jar contains
both contract schemas (24 October 2026).
See the [contract and hash protocol](../docs/09-adventure-packs.md#3-manifest-fields).

P04.2–P04.3 add `content.PackCompiler`, which turns a `ParsedPack` into a `CompiledPack`.
The compiler checks typed references, bounded expressions/effects, quests, dependency
cycles, engine/ruleset/module compatibility and hash conflicts. It also compiles a
`GenerationPolicy`, and `content.GenerationGate` checks generated proposals against it.
`CompiledPack` output alone is not a registered revision. The compiler suite runs
with `./mvnw -B -ntp -Dtest=PackCompilerTest test` without PostgreSQL: 19 tests pass.
The full backend `verify` passes 198 tests with three profile skips (24 October 2026).
See [ADR 0017](../docs/adr/0017-bounded-pack-semantics.md).

P04.4 adds `PackStaging.directory(Path)` / `archive(InputStream)` and the injected
`PackRegistry.preview(adventureId, staged)` / `register(adventureId, staged)`.
Only protected staged inventories can enter registration. Preview is read-only;
registration compiles, checks media headers and stores the exact files plus a
sealed report in one transaction. Dependencies are adventure-local. Identical
imports return the existing revision; differing bytes at the same ID/version
fail. `report(revisionId)` reads the report and `files(revisionId)` checks the
stored inventory against its normalized hash. No controller or media route exists.

Defaults: 100 MiB ZIP, 250 MiB expanded, 2,000 files, 50:1 expansion, 32 MiB per
file (text further limited to 1 MiB); image headers declare at most 8,192 per side
and 32 Mi pixels. Directory sources must be owner-controlled and quiescent;
archive bytes never extract to disk. Media admission does not fully decode files
or verify redistribution rights. The provisional ruleset descriptor hash is not
a gameplay-kernel hash. [ADR 0018](../docs/adr/0018-protected-pack-registration.md)
records storage, sealing, restrictions and deferred decoder/concurrency gates.
Focused checks use `./mvnw -B -ntp -Dtest=PackStagingTest,PackRegistryPersistenceTest test`
against a disposable PostgreSQL database (23 tests pass). The populated migration
test also verifies V7 leaves legacy metadata and campaign pins intact without
fabricating stored bytes or admission reports.
Full pinned JDK 21/PostgreSQL 18.6 `verify` passes 218 tests, skips the three
separate packaged-interface profile tests (221 total), and builds the jar
(24 October 2026). The initial full run exposed a V6-latest fixture assumption;
the V1→V7 extension and subsequent full rerun both pass.

## Neutral genre imports (P04.5)

Two standalone [contract-1 fixtures](src/test/resources/packs/README.md) cover
fantasy exploration and science-fiction salvage with distinct locations,
terminology, resource caps, creation budgets, tools, abilities, quests and
original MIT-licensed PNG schematics. No production code or dependency changes
are needed to import either pack. They live in test resources and are not bundled
in the production jar.

`./mvnw -B -ntp -Dtest=NeutralGenreImportTest test` runs PostgreSQL-backed
cases: unchanged directory/ZIP imports, read-only preview, sealed byte/hash/report
round trips, recompile, idempotency, independent adventure imports and located
invalid-reference rejection with no partial writes. Optional modules stay
disabled; campaign gameplay and UI remain later work. P04.6 adds reviewed EN/IT
catalog resources and two localization-specific integration cases described below.
Pinned JDK 21/PostgreSQL 18.6 full `verify` passes 220 tests with three separate
packaging-profile skips (223 total) and builds the jar (24 October 2026).

## Pack text catalogs (P04.6)

Verification: 45 focused compiler/import/upgrade cases pass. Full pinned backend
`verify` passes 262 tests with three packaged-interface profile skips (265 total)
and builds the jar. The V8 backup/restore rehearsal preserves registered fixture
bytes, hashes, exports and both sealed report versions; see the
[backup tool scope](../tools/database_backup/README.md).

Optional contract-1 `localizations` and `textResources` declarations compile into
`CompiledPack.localization()`, separate from mechanics and author constraints.
The [localization contract](../docs/29-localization.md#4-pack-localization-contract)
defines closed schemas, stable child-ID paths, owner/visibility inheritance,
proper-name/quote policies, named-placeholder parity and bounds. Partial or absent
overlays fall back to exact source text; a verbatim quote retains source language.
The immutable result includes actual locale, fallback, source owner/visibility,
kind and quote policy. Missing text is an explicit diagnostic.

The catalog is internal content: authorize source owner and field **before** lookup.
Reports add privileged `localization` coverage/counts and fallback keys, without
private prose or hints. Original overlay/resource files and reports are sealed
through the existing registry and raw-byte hashes. New reports use version 2; V8
permits versions 1 and 2 while preserving old seals. Identical re-imports keep the
original report; new inspection may supply current coverage. No dependency change
is needed. Markdown and prose arrays without stable child IDs stay source-only.
This implements pack validation, not localized UI, ICU, rendering or narrative quality.

Run `./mvnw -B -ntp -Dtest=PackLocalizationTest,NeutralGenreImportTest test`
against disposable PostgreSQL. The corpus covers both reviewed bilingual fixtures,
unknown/mechanical/audience targets, source policies and visibility, placeholder
changes, duplicates/path/locale/size errors, stable-ID reordering, fallback and
unchanged mechanics. Integration checks preserve localized bytes/reports on reload,
reject changed-overlay hash conflicts and admit no rows after hostile overlay errors.

## Packaged runtime configuration (P02.3)

Activate the Spring runtime profile with `--spring.profiles.active=packaged`.
This differs from the Maven `-Ppackaged-ui` build profile: one configures startup,
the other embeds the interface. The runtime profile requires the config tree
`/run/secrets/` and reads `postgres_password` into `spring.datasource.password`.
Supply the JDBC URL and role through `SPRING_DATASOURCE_URL` and
`SPRING_DATASOURCE_USERNAME`; do not export the password or put it in arguments.
For isolated verification, `--engine.secrets-directory=/absolute/private/path/`
overrides the mount location; the trailing slash is required. Missing mounts or
password properties fail startup; there is no optional import or password default.
A normal trailing file newline is stripped by Spring's config-tree loader.
Ordinary Spring property precedence still applies; this profile is not a secret
manager and does not override explicitly supplied datasource settings.

The profile binds to `0.0.0.0` **inside the container**. The Compose engine
service publishes only on host loopback; avoid activating this profile for
host development. The default profile still binds to `127.0.0.1`.
`PackagedSecretsTest` checks real configuration loading with neutral files,
including paths with spaces, rejection of missing mounts/files under strict
placeholder binding, and unchanged host defaults, without a
database or account. The engine Compose service consumes the secret as UID 10001;
readability is verified on Windows Docker Desktop, not yet native Linux hosts.
Local Compose secret files are not an encrypted store.
For the actual application, an absent mount fails configuration import; an empty
mount fails database authentication during startup. The config-tree import alone
does not validate individual file presence before datasource initialization.

On 23 October 2026 all nine packaged backend tests passed against disposable
PostgreSQL 18. A separate executable-jar smoke ran as UID/GID 10001, authenticated
with the read-only mounted secret, checked health and exact index/notices bytes,
and confirmed startup failure for missing directories/files. No engine image was
built; these checks use the existing pinned Java 21 development image.
The clean build without `-Ppackaged-ui` also passes six tests and skips its three
interface tests, using the same packaged runtime profile for database access.

The development server binds to `127.0.0.1:8080`. Only status-only health is exposed
(no components/details): `/actuator/health` (root, storage health), `/actuator/health/liveness`
(process only), `/actuator/health/readiness` (database reachable and schema matches the shipped
migrations; HTTP 503 otherwise) and `/actuator/health/gameplay` (always 503/`UNKNOWN` until a
narration runtime adapter exists). Startup failures for an unreachable database, rejected
credentials or schema mismatch print a fixed, secret-free description and action
(`StartupFailureAnalyzer`). There are no gameplay APIs or account integration. Ordinary
builds contain no browser assets. Engine image/service integration is covered by the
[packaging suite](../tools/container_packaging/README.md). No
Testcontainers dependency or test-library helper image is used. The CI job runs `./mvnw -B -ntp verify` against a job-local disposable PostgreSQL 18 ([workflow](../.github/workflows/ci.yml)); a style linter is not configured.


## Single-origin jar assembly (P02.3)

Build the reviewed production interface in `frontend/` with `npm ci` and
`npm run build:locales`, then return to `backend/` and run against the disposable database:

```sh
./mvnw -B -ntp clean verify -Ppackaged-ui
java -jar target/engine-0.1.0.jar
```

The opt-in profile copies `frontend/dist/engine-ui-localized/browser/{en,it}/`
into the jar's `static/{en,it}/` directories, with extracted dependency notices
at root and under both prefixes. Shared artwork/project notices are also copied
from compiled English output to stable root URLs. No source tree is served.
Spring serves the root language selector, locale indexes, hashed bundles and notices at the same
origin as `/actuator/health`; it needs no separate frontend server or CORS
exception. Unknown API, source/configuration and asset URLs remain 404. No broad
SPA fallback is installed. Only known preview GET routes and `/saved/<canonical
lowercase UUID>` under `/en/` and `/it/` receive the corresponding compiled index
(`no-store`); the identity never selects a resource. Known unprefixed preview
GET routes use the root selector, preserving route/query/hash; unsupported paths
and locale prefixes remain 404. Root chooses the saved allowlisted device locale
or English, never browser headers; native locale links work without scripting.
POST/API requests never redirect to a locale or receive a successful index.

Always use `clean` when changing profiles or rebuilding for distribution so old
assets cannot survive in `target/`. A guard checks the copied index and notices
before tests/package; missing production output fails the build with frontend
build instructions. `ui.dist.directory` may select a different browser output
path, with extracted notices in its parent. The HTTP tests also fail on missing
bundles or notices, compare served bytes with packaged resources and probe both
locale route trees. Without this profile, the four packaged-interface tests skip
and ordinary backend tests remain independent of frontend output.

The P05.6 verification also runs an offline negative build with a missing locale
output directory; the guard rejects assembly with the localized build instructions.

On 23 October 2026 all five profile tests passed with real PostgreSQL 18 and the
existing pinned Java 21 development image. The ordinary build passed two tests
with three profile tests skipped, and the missing-output guard rejected an empty
input directory. A separate executable-jar HTTP smoke verified health and exact
index/notices bytes; archive inspection matched all four production resources.
These earlier results establish jar assembly. The completed P02.3 container
check builds Angular and the jar from filtered source in pinned toolchain stages,
then serves the shell from a JRE-only image as UID 10001. Its verification target
runs all nine backend tests offline against a separate disposable PostgreSQL 18
database with the packaged file secret. Production HTTP checks run on the actual
engine service before and after recreation; a real IPC handshake checks its
socket access as the production UID.
See the [packaging commands and limits](../tools/container_packaging/README.md).
ADR 0009 retains Java refresh as a release requirement. Account readiness,
canonical campaign recovery and full QA-28 remain later gates.


## Atomic campaign creation (P06.1)

`CampaignCreationService.create(ownerId, adventureId, request)` accepts trusted owner
context, a sealed content revision, a request UUID, title, narrative locale and
bounded character choices. It authorizes the adventure before returning any retry,
recompiles exact stored bytes and validates preset/custom allocations and authored
grants before writing. One transaction saves the campaign, actor, character state,
resources, item stacks, learned abilities, locale and immutable request result.
Identical retries survive service reconstruction; changed payloads conflict.
Creation leaves canonical counters at zero and creates no event, draw or job.

`CharacterCreationTest` and `CampaignCreationPersistenceTest` cover authored minima,
budgets, caps, loadouts, two same-ID genre snapshots, independent campaigns,
owner rejection, concurrent retries and rollback of deferred COMMIT failures.
The populated upgrade test preserves V8 rows and creates no fictional backfill.
Creation now verifies a bounded transitive closure of sealed dependencies and
resolves external profiles, skills, locations and grants in their declaring pack.
Skill allocation keys and persisted references qualify dependency IDs as
`pack#definition`; root-local IDs stay bare. Profile-owned rules determine
attributes/resources, and dependency skill/stack bounds are rechecked from bytes.
See [closure limits and version conflict policy](../docs/09-adventure-packs.md).
`GET /api/v1/session` and `POST /api/v1/campaigns` now expose protected local
creation. Set `ENGINE_HTTP_OWNER_ID` to a pre-existing owner UUID; absent owner
configuration leaves both routes unavailable. Set `ENGINE_HTTP_ORIGIN` to the
exact browser origin when using a port/host other than `http://127.0.0.1:8080`.
Only HTTP loopback origins with explicit ports are accepted. The server derives
ownership from this configuration; the browser cannot select it. This opt-in
does not create owners/workspaces or import packs. Those remain internal setup.
These environment variables configure host backend execution. Root Compose does
not forward them automatically; enabling this API there requires an explicit
engine-service environment override and the same pre-existing database setup.
Bootstrap returns a CSRF token and a separate HttpOnly SameSite=Strict cookie;
send both plus the exact Origin with UTF-8 JSON creation requests. Sessions
expire after 30 minutes or process restart, with at most 32 live sessions and
four concurrent mutations. See the [public request schema and error codes](
../docs/13-api-and-ui.md) and [security boundary](../docs/14-security-and-operations.md#4-application-http-boundary).
The strict 16 KiB decoder reuses the data-only parser in JSON mode to reject
duplicate keys, trailing documents and invalid UTF-8 without coercion.
Process restart acceptance launches separate application JVMs, creates two genre
campaigns, stops/restarts the application, rejects the expired process session,
then verifies both durable results and unchanged counters/grants. Starting
narration and public pending decisions remain later P06 tasks.
Pinned packaged backend verification passes all 290 tests on PostgreSQL 18.6,
including five real HTTP tests and four admission tests, with no failures/skips.

## Starting presentation (P06.3)

Atomic creation saves a versioned pending marker with no world-counter advance.
State/continue resolve only the public pinned root entrypoint in the saved narrative
locale and expose actual language/fallback plus typed free-input decision metadata.
Unknown/cleared markers and legacy saves receive null presentation/decision fields;
creation retries never replay or restore a decision. No schema migration, runtime
call, fixed story, forced choice or frontend binding is introduced. The exact DTO
contract is documented in [campaign projections](../docs/13-api-and-ui.md#campaign-projections-p062).
On 25 October 2026, pinned Java 21/PostgreSQL 18.6 `clean verify -Ppackaged-ui`
passes all 299 backend tests with zero failures/errors/skips. The 24 focused
campaign tests also pass, covering EN/IT in both genres, source fallback, authored
revision pinning, unknown/cleared markers, no-write reads and separate-JVM restart.
All 22 repository policy tests pass; repository scans report zero findings.

The real HTTP test helper preserves byte offsets while decoding chunk framing,
then decodes UTF-8, including accented Italian entrypoint text.

## Player projections (P06.2)

`CampaignProjectionService` provides bounded owner listing and independently
authorized read snapshots at repeatable-read isolation. Protected GET routes
include campaign `state`, `continue`, `known-state`, `inventory`, `quests` and
`abilities`; see the [exact request/response contract](../docs/13-api-and-ui.md#campaign-projections-p062).
Each read requires an adventure ID and returns scope/revision tokens. The player
comes from the creation receipt, so equal actor/item names cannot join worlds.
Only owned resource/item/ability rows and explicit player-audience knowledge are
returned. Legacy campaigns expose an unavailable player as null. Continue has
no write, narration or session-renewal effect; browser API binding remains P05.6.
V10 creates empty knowledge/quest tables without promoting authored secrets.
Pinned `clean verify -Ppackaged-ui` passes all 296 backend tests on PostgreSQL
18.6 with no failures/errors/skips. Eleven HTTP tests include whole-database
no-write comparisons, owner/audience isolation, concurrent snapshot consistency,
collection bounds, legacy saves and separate-JVM restart. Migration acceptance
preserves populated old saves through V10. All 22 repository policy tests pass.

## Persisted adventure library (P06.5 checkpoint)

`GET /api/v1/adventures` and `GET /api/v1/adventures/{adventureId}` expose only
the configured local owner's workspace metadata through the session admission
boundary. Listing includes empty workspaces, pages by opaque UUID (default 50,
maximum 100), and never merges equal titles. Detail reads hide missing and
foreign scopes behind the same 404. Reads use independent read-only snapshots;
no global active adventure or world counters are changed. See the
[API contract](../docs/13-api-and-ui.md#persisted-adventure-library-p065-checkpoint).
Workspace creation and generated-media storage remain pending;
the browser library still uses local preview fixtures.

## Exact consultations (P06.4)

The admitted scoped GET `/api/v1/campaigns/{id}/consultation` takes `adventureId`
and `command`. Exact `status`/`stato` or `inventory`/`inventario` aliases accept
outer whitespace and ignore case, with a 64 UTF-16-unit input bound. Other text
is rejected with the existing sanitized 400 read-input code, never interpreted.
`LocalConsultation` is a pure parser/presentation adapter over the existing
read-only snapshot. Its `{type, scope, passage}` response contains own character/
resources or inventory, with source revision rather than a fabricated action or
delivery revision. It writes no transcript row, operation, job, event or draw.
Continue does not deliver these passages. Optional durable history and browser
rendering remain separate later delivery work. Run the focused HTTP corpus with
`./mvnw -B -ntp -Dtest=CampaignCreationHttpTest test`; full packaged verification
uses `./mvnw -B -ntp clean verify -Ppackaged-ui`.

Verified on 25 October 2026: all 300 packaged backend tests pass on pinned Java
21/PostgreSQL 18.6 without failures/errors/skips. All 15 HTTP cases cover exact
aliases, whole-database equality on progressed/depleted saves, admission/scope
isolation and reconstruction across separate-JVM restart; repository checks pass.
No separate Java style linter is configured; compilation supplies type checking.

## Managed revision folders (P06.5 checkpoint)

Set `ENGINE_WORKSPACES_ROOT` to an absolute, normalized private persistent path
outside the source checkout. The default is disabled; no new mount or property is
silently enabled in Compose. A packaged installation can use an owner-controlled
subdirectory of the existing engine-only content volume, for example
`/var/lib/engine/content/workspaces`, after checking its ownership and permissions.
Never share it with the runtime or expose it as static web content.

The optional component publishes sealed originals under
`adventures/<adventure-id>/content-revisions/<revision-id>/`, separating
`specification/` and `assets/authored/` and recording `integrity.json`. One atomic
directory rename publishes the complete tree. Startup and post-commit import
notifications verify or reconstruct one revision at a time; unchanged retries
never rewrite files. PostgreSQL remains authoritative and database backups include
all projected original bytes. A registration can commit before projection fails;
repair the root and retry identical bytes or restart, never import a changed release
as a workaround. Corrupt/extra files and static links fail closed without deletion.

Use a single engine process and a private quiescent root. New POSIX paths use
owner-only permissions; operators own existing-root permissions and Windows ACLs.
Interrupted `.building-*` directories require offline inspection/cleanup. Existing
partial published revisions require offline operator repair or an empty replacement
projection root. No generated-art storage, file-serving route, browser switching,
identity-remapped restore or full QA-33 acceptance is claimed. See the
[workspace contract](../docs/25-adventure-workspaces.md#2-separate-managed-folders).

## Typed intentions and atomic common semantics (P07.2)

New command admission accepts only the closed immutable `MOVE`, `WAIT` and `SPEAK`
intention payloads in the [turn protocol](../docs/07-turn-protocol.md#p072-typed-intentions-and-atomic-common-semantics).
It rejects unknown types, generic/nested patches, effect/cost fields and unsupported
mode/decision/player/recipient state before journaling or granting capacity. Decode
and hash one bounded canonical byte snapshot so mutable Java callers cannot change
the validated payload while waiting for a lock. Preserve historical matching replay.

Trusted resolvers pass `CommandIntent` in the full `ResolvedCommit` constructor.
Registered player operations require matching request/type/revision/hash evidence
and repeat common semantic checks inside canonical commit. Omitting evidence fails
before world effects. Internal non-player foundation operations retain their trusted
compatibility constructors. No new HTTP controller, migration or worker is added.
An admission alone is not an executed action; local calculations and mechanical
receipts arrived with P07.4 and delivery with P07.5 (below).

`TypedCommandTest` covers closed shape, bounds, coercion, unknown types and immutable
hash evidence. `TypedCommandValidationTest` uses real PostgreSQL for rejection
no-write snapshots, scoped actors, unsupported decisions/modes, frozen direct input,
legacy replay, commit revalidation, draw retention and multi-effect rollback. Both
admission and commit contention cases observe a real PostgreSQL blocker before a
concurrent mode change commits. Run these with the existing command corpus and
full packaged backend verification; repository checks remain required.

Verified on 27 October 2026 with the unchanged scoped JDK 21.0.12.1/Maven 3.10.0
and disposable PostgreSQL 18.6 fixture: 131 focused command cases and all 394
packaged backend cases in 31 suites pass with zero failures/errors/skips. The
executable jar is packaged. Configured frontend checks, EN/IT builds, 22 repository
policy tests and zero-finding scans also pass. Two earlier compile attempts failed
on a related multi-catch and test factory signature; both were repaired before
these passing runs. See [evidence scope](../docs/15-quality-and-acceptance.md).

## Current-state command rules (P07.3)

`CommandRules` applies the [rule contract](../docs/07-turn-protocol.md#p073-current-state-rule-validation)
after common semantics at admission and again inside canonical commit, under the
same campaign lock. `MOVE` follows one disclosed, accessible authored link from the
player's current location; speech recipients and ability targets must be co-present,
perceivable campaign actors; the new `USE_ABILITY` intention requires a learned
pinned ability, its prerequisite abilities and skill ranks, a legal target count and
affordable costs from the creation profile's own resource vocabulary. Concealment-
sensitive failures share one category. At commit, resource changes and event actors
must belong to the intention's participants. Matching retries and stored receipts
replay first. No migration, controller, worker or browser action is added.

`CommandRuleCatalogs` compiles an immutable prose-free rule catalog from the sealed
pinned closure in a read-only transaction before the lock (at most 16 revisions
cached). A campaign whose content cannot be loaded rejects content-dependent fresh
commands with `CONTENT_UNAVAILABLE`; `WAIT` and speech to an actor without an
authored origin need no catalog, so content-free fixtures use those intentions.

`CommandRulesTest` registers a root and a dependency pack through real staging and
sealing, creates a campaign with the production creation service and covers open,
blocked, secret, unlinked, current and dependency destinations; secret, foreign,
self, absent and out-of-range recipients; ability ownership, prerequisites, every
target policy and exact/insufficient/cross-vocabulary/missing costs; commit-time
re-derivation, unattributable effects and receipt replay; admission replay; real
campaign-lock contention at both boundaries; and catalog scope, prose exclusion and
revision binding. Every rejection is compared with an exact multi-table snapshot.

Verified on 27 October 2026 with the unchanged scoped JDK 21.0.12.1/Maven 3.10.0
and disposable PostgreSQL 18.6 fixture: 138 focused command cases and all 401
packaged backend cases in 32 suites pass with zero failures/errors/skips, and the
executable jar is packaged. Configured frontend checks, EN/IT builds, 22 repository
policy tests and zero-finding scans also pass. See
[evidence scope](../docs/15-quality-and-acceptance.md).

## Local command execution (P07.4)

`CommandExecutionService` executes one registered intention per request according to
the [execution contract](../docs/07-turn-protocol.md#p074-local-action-execution):
admission, a prepare transaction (`ACCEPTED → PLANNING → VALIDATING`, rules re-checked,
detached snapshot), pure calculation in `LocalActionResolver` with one durable
`RandomDrawService` reservation per die, and one `CanonicalCommitService` commit. Rule
drift after admission records `REJECTED`, any other failure `FAILED_BEFORE_COMMIT`; the
lease is released and reserved draws remain bound to the operation. Matching retries
replay the stored status and receipt and never re-execute.

`MOVE` commits a typed row-version-guarded `Placement`, `WAIT` advances exactly the
requested seconds, `SPEAK` records the exact words, and `USE_ABILITY` pays costs exactly
and runs the ability's compiled `EffectProgram` (`modifyResource` and `conditional`
nodes; other effect types are refused as `EFFECT_UNSUPPORTED` before journaling). Every
action advances the turn once, clears the pending decision, writes v1 events, a receipt
with calculations and draw ordinals, both outbox intents and `actor:<uuid>` query-epoch
bumps. `CommandReceipts` projects the player-safe receipt returned by the command and
status HTTP routes. No migration, worker, browser action or inference is added.

`LocalActionResolverTest` (pure) covers expression semantics, draw ordinal order, timing
phases, branches, exact costs, merged net changes, clamping, vocabulary-bound targets,
event visibility, fail-closed unknown values and determinism. `CommandExecutionTest`
(real PostgreSQL, sealed packs, production campaign creation) covers committed movement,
waiting, speech and abilities, draw/receipt agreement, replay before and after startup
reconciliation, refused effect programs, and trigger-injected faults after durable draws
(failing event insert, concurrent rule-fact change) that leave no world effect.

Verified on 28 October 2026 with the unchanged scoped JDK 21 toolchain and disposable
PostgreSQL 18.6 fixture: 151 focused command cases and all 414 packaged backend cases
in 34 suites pass with zero failures/errors/skips, and the executable jar is packaged.
Configured frontend checks, EN/IT builds, 22 repository policy tests and zero-finding
scans also pass. See [evidence scope](../docs/15-quality-and-acceptance.md).

## Stream, cancellation and presentation jobs (P07.5)

V11 adds the equivalent-attempt columns, the public stream log and post-commit
presentation jobs described in the
[delivery contract](../docs/07-turn-protocol.md#p075-public-stream-cancellation-and-presentation-jobs):

- `OperationCancellationService` (`POST …/operations/{id}/cancel`, body `{}`) decides the
  race with execution under the campaign lock: pre-commit operations become `CANCELLED`
  with a fenced permit; a commit that won reports `ALREADY_COMMITTED` and only stops
  optional narration.
- Admission records `attempt_key`/`draw_stream_id`; `RandomDrawService` derives values from
  the draw stream, so an equivalent new attempt reproduces a cancelled or failed attempt's
  draws.
- The `operation_journal_stream` trigger appends `campaign_stream_message` rows;
  `PublicStreamService` and `CampaignStreamController` (`GET …/stream`) serve them as SSE
  long polls with `generation:sequence` identities, `Last-Event-ID` resumption and resets.
- `org.rpgengine.presentation`: `PresentationJobService` (claim, narrate, settle, retry,
  expiry and startup reconciliation), `PresentationWorker` (one daemon thread),
  `NarrationPort` with the default `UnavailableNarration` until P08.

| Property | Default | Meaning |
| --- | --- | --- |
| `engine.presentation.worker.enabled` | `true` | Run the background presentation worker |
| `engine.presentation.worker.poll-ms` | `1000` | Delay between worker passes (50–60000) |
| `engine.stream.hold-ms` | `15000` | How long one stream response waits for new messages (0–60000) |
| `engine.stream.poll-ms` | `250` | Storage polling interval while a stream response waits (20–5000) |

`src/test/resources/config/application.properties` disables the worker for every test
context, because a background pass would race assertions about journal status; tests
drive `PresentationJobService` explicitly, and `CampaignStreamHttpTest` enables the worker
in its own discarded context to prove automatic dispatch. New test suites:
`CommandCancellationTest` (races held after admission, after a durable draw and inside the
commit through an advisory-lock trigger; equivalent-attempt draw streams),
`PublicStreamTest`, `PresentationJobTest` and `CampaignStreamHttpTest`. Test fixtures that
truncate tables must include `presentation_job`, `campaign_stream_message` and
`campaign_stream_head`.

Verified on 28 October 2026 with the unchanged scoped JDK 21 toolchain and disposable
PostgreSQL 18.6 fixture: 173 focused cases and all 435 packaged backend cases in 38
suites pass with zero failures/errors/skips, and the executable jar is packaged.
Configured frontend checks, EN/IT builds, 22 repository policy tests, zero-finding scans
and the offline backup tests also pass. See
[evidence scope](../docs/15-quality-and-acceptance.md).

## Plan admission gate and activation (P07.6)

V12 and the `preparation` package complete the transactional plan checks described in the
[admission contract](../docs/07-turn-protocol.md#p076-plan-admission-and-activation):

- `PlanContentCatalogs`/`PlanContentCatalog`: prose-free pinned definition identities,
  secret identities, required/fixed author constraints and their digest
  (`PlanContentCatalog.authorConstraintHash`), loaded before any campaign lock and cached
  for 16 revisions. Legacy metadata-only revisions are unavailable and fail closed.
- `PlanAdmissionService.admit` runs the `plan-gate-v1` gate (scope, content, author,
  typed dependencies, coverage, lineage) after the P03.6 fencing/idempotency checks.
  `Proposal.references` carries the entity, content and author-constraint references the
  provisional validator extracted; the 11-argument constructor cites none.
- `PlanActivations`: `requireUsable`/`recordWithin`, called only by
  `CanonicalCommitService` for `ResolvedCommit.Preparation.activations`; refusals throw
  `PlanActivations.Rejected` with a `PLAN_*` reason and roll back the command.
- SQL: `plan_reference_version`, `plan_dependency_fresh`, `plan_admission.gate_version`/
  `checked_references` and the immutable `plan_activation` table.

`PlanAdmissionGateTest` drives the gate with ordinary commands on a campaign created from
sealed packs; `PreparationPersistenceTest` now pins a sealed sample pack so its fixtures pass
the content and author checks. Test fixtures that truncate tables must include
`plan_activation`.

Verified on 28 October 2026 with the unchanged scoped JDK 21 toolchain and disposable
PostgreSQL 18.6 fixture: 155 focused cases and all 444 packaged backend cases in 40
suites pass with zero failures/errors/skips, seven deliberate weakening probes were
detected, and the executable jar is packaged. Configured frontend checks, EN/IT builds,
26 repository policy tests, zero-finding scans and the offline backup tests also pass.
See [evidence scope](../docs/15-quality-and-acceptance.md).

## Narration task port and Codex transport (P08.1)

`org.rpgengine.inference` is the runtime-neutral port every later AI task uses; the Codex
adapter behind it is the package-private `org.rpgengine.inference.codex`, which no other
package may name (`InferenceBoundaryTest`). Behaviour, bounds and failure codes are specified
in [docs/06](../docs/06-codex-integration.md#p081-production-adapter).

- `NarrationTaskPort.submit(TaskRequest)` never blocks and never throws for runtime
  conditions; the handle's stage always completes with a `TaskResult`
  (`COMPLETED`, `CANCELLED`, `FAILED`, `TIMED_OUT`, `UNRESOLVED`, `UNAVAILABLE`, `REJECTED`).
- `UnavailableTaskPort` is wired by default; `CodexConfiguration` replaces it when
  `engine.inference.codex.transport` is `stdio` or `unix`.
- `GameplayHealthIndicator` (`narrationRuntime`) is UP only when the port is `READY`.

| Property | Default | Meaning |
| --- | --- | --- |
| `engine.inference.codex.transport` | `disabled` | `disabled`, `stdio` or `unix`; anything else fails startup |
| `engine.inference.codex.socket-path` | none (packaged: the hashed real socket) | Runtime socket for `unix` |
| `engine.inference.codex.command` | none | `stdio`: absolute executable, then arguments, comma separated |
| `engine.inference.codex.working-directory` | none | `stdio`: child working directory |
| `engine.inference.codex.runtime-cwd` | `/var/lib/runtime/workspace` (`stdio`: working directory) | Thread working directory as seen by the runtime |
| `engine.inference.codex.queue-capacity` | `16` | Bounded admission queue (1–256) |
| `engine.inference.codex.request-timeout` / `handshake-timeout` / `interrupt-timeout` | `30s` / `30s` / `10s` | Response, discovery and interrupt-confirmation deadlines |
| `engine.inference.codex.reconnect-initial` / `reconnect-max` | `1s` / `30s` | Reconnect backoff |
| `engine.inference.codex.stdio-grace` | `2s` | Wait after closing child input and after each escalation step |
| `engine.inference.codex.max-sessions` | `256` | Session bindings kept (least recently used forgotten) |
| `engine.inference.slots.<light\|standard\|deliberate>.model` / `.effort` | none | Private slot assignment; both or neither. Optional `.backend` and `.alternatives` are described under P08.4 below |

Tests use deterministic doubles only: `ScriptedAppServer` (fixture-shaped responses and
events, shared daemon state across connections; never selectable), its child-process form
`ScriptedAppServerMain`, and an in-test Unix-socket WebSocket server. Suites:
`CodexTaskAdapterTest`, `RpcGenerationTest`, `CodexFixtureReplayTest` (replays
`src/test/resources/contracts/codex/0.164.0/neutral-session.json`, a byte-identical copy of
the probe fixture; the drift check is skipped only where `../tools` is absent, i.e. in the
backend-only verification image), `StdioFrameWireTest`, `UnixSocketFrameWireTest`,
`InferenceBoundaryTest` and `TaskPortSelectionTest`. No database is needed by these suites.

Verified on 28 October 2026 with the unchanged scoped JDK 21 toolchain and disposable
PostgreSQL 18.6 fixture: 46 focused cases and all 490 packaged backend cases in 47
suites pass with zero failures/errors/skips, nine deliberate weakening probes were
detected, and the executable jar is packaged. See
[evidence scope](../docs/15-quality-and-acceptance.md).

## Intent and narration task contracts (P08.2)

`org.rpgengine.inference.contract` turns the neutral task port into typed, validated
application tasks. The rules, violation codes and limits are in
[docs/21 section 14](../docs/21-ai-task-contracts-and-prompts.md#14-implemented-intent-and-narration-contracts-p082).

- `IntentContract` maps one free-text declaration to a `TypedCommand` proposal (MOVE, WAIT,
  SPEAK, USE_ABILITY), a bounded clarification or cannot-complete. The proposal is never
  submitted by itself; it still passes admission, rule validation and idempotency.
- `NarrationContract` maps a public resolved-event bundle to one to eight attributed passages
  and up to three editable quick replies for the pending decision.
- `ContractRunner` submits through `ScopedSessions` (P08.3), validates the untrusted output
  and repairs at most once. The rejected text is never echoed: only violation codes and pointers
  go back. It returns `Accepted`, `Rejected`, `NotCompleted` or `Refused` with the
  `TaskBinding`; check `presentationCurrent` before showing anything.
- Output schemas are bundled at `src/main/resources/ai-contract/1/`. They constrain generation
  only. `ContractSchemaTest` keeps their field sets equal to the validators'.

Since P08.5 the post-commit presentation job calls the narration contract (below); the intent
contract is not yet called from gameplay. Session scoping is described below.

Tests use the deterministic `ScriptedTaskPort` and a manual clock; no database or runtime is
needed. The suites are `IntentContractTest`, `NarrationContractTest`, `ContractRunnerTest` and
`ContractSchemaTest`.

Verified on 28 October 2026 with the unchanged scoped JDK 21 toolchain and disposable
PostgreSQL 18.6 fixture: 35 focused cases and all 525 packaged backend cases in 51 suites
pass with zero failures/errors/skips, fifteen deliberate weakening probes were detected, and
the executable jar is packaged. See [evidence scope](../docs/15-quality-and-acceptance.md).

## Session scoping by campaign, role, audience and policy (P08.3)

`org.rpgengine.inference.session` keeps privileged planning apart from public narration. The
rules are in [docs/06 section 7](../docs/06-codex-integration.md#p083-session-scoping).

- `SessionScope` is the isolation key: adventure, campaign, recovery generation, `AiRole`,
  `Audience` (`public`, `character:<ref>`, `privileged`), pack hash and policy hash. Tasks
  share a runtime thread only within one scope.
- `AiRole` fixes the permitted audiences per role. `interpreter` and `narrator` are public
  only, `guide` and `tactician` use a character audience, and the planning and proposal roles
  are privileged only. No player-facing role permits `privileged`.
- `ScopedSessions` (a Spring component) is the port's only submitter, and since P08.6 the fair
  scheduler is its only caller (see below). It reuses the session
  of an equal scope, and it retires superseded lines (new generation, pack or policy hash).
  It also rejects a stale generation (`stale-campaign-generation`) or a campaign under two
  adventures (`session-scope-conflict`) without sending anything, and bounds active sessions.
  Every retirement is forwarded to `NarrationTaskPort.retireSession`. `retire`,
  `retireCampaign` and `retireAll` serve renewal, restore and a runtime change.
- `ContractRunner.start(contract, context, slot, budget)` derives the scope from the task kind
  and the binding; callers cannot pass a session. `TaskBinding` gained `campaignGeneration`,
  which the envelope renders.

| Property | Default | Meaning |
| --- | --- | --- |
| `engine.inference.sessions.capacity` | `256` | Active scoped sessions (1–4096); the least recently used is retired |

The registry is in memory, like the adapter's thread bindings, so a backend restart starts
every scope on a fresh thread. Since P09.4 the registry also renews sessions and records them
durably (see "Session renewal, records and rehydration").

Tests use deterministic doubles only and need no database:

- `SessionScopeTest` covers the role matrix and the scope key.
- `ScopedSessionsTest` covers reuse, supersession, the generation fence, the adventure
  conflict, retirement, the capacity bound and concurrent use.
- `SessionBoundaryTest` checks that only the registry builds `TaskRequest` or calls the port,
  and that the runner accepts no port and no session identifier.
- `ContractSessionScopeTest` covers runner-derived sessions and envelope fields.
- `ScopedThreadIsolationTest` runs the real Codex adapter against the scripted app-server: a
  planner sentinel is sent only on the planner's thread.
- `TaskPortSelectionTest` covers Spring wiring and the capacity bound.

Verified on 28 October 2026 with the unchanged scoped JDK 21 toolchain and disposable
PostgreSQL 18.6 fixture: 79 focused cases (23 new) and all 548 packaged backend cases in 56
suites pass with zero failures/errors/skips, fifteen deliberate weakening probes were
detected, and the executable jar is packaged. See
[evidence scope](../docs/15-quality-and-acceptance.md).

## Interface reload contract (P05.6)

`commands/CampaignInterfaceContractTest` is the producer side of the browser reload contract.
In its own discarded context (worker enabled, 200 ms stream holds) it creates a fantasy-fixture
campaign narrating in Italian, records the exact GET replies the bilingual interface receives
across five phases (first load, another client's move plus one admitted command left in flight,
delivery, cancellation during the language reload, reloaded page) and compares the normalized
transcript with `src/test/resources/contracts/campaign-reload-transcript.json`. UUIDs become
`00000000-0000-4000-8000-<ordinal>` in order of first appearance, instants become
`2026-01-01T00:00:00Z` and the CSRF token a fixed placeholder; every other byte is the
server's. Each run also writes `target/contracts/campaign-reload-transcript.json`.

The frontend replays a byte-identical copy (`frontend/src/app/campaign/contract/`), and the
repository `contracts` check keeps the two equal. After an intended protocol change run
`mvn test -Dtest=CampaignInterfaceContractTest -Dengine.contract.update=true` (it rewrites
both copies in a full checkout; the verification image carries only the backend copy), review
the diffs, then run the frontend tests.

## Profile resolution through the owner's slot assignment (P08.4)

`ProfileResolver` (neutral `org.rpgengine.inference`) resolves each task's logical slot to the
explicit model and effort of one turn, for every task and with no state carried between tasks.
The order, error codes and limits are in
[docs/06](../docs/06-codex-integration.md#p084-profile-resolution); the Codex adapter only
passes its discovered `model/list` catalog and its backend name.

| Property | Default | Meaning |
| --- | --- | --- |
| `engine.inference.backend` | `codex` | Backend of every slot that names none (the default-runtime role of `NARRATION_RUNTIME`) |
| `engine.inference.slots.<slot>.backend` | `engine.inference.backend` | `codex`, `claude`, `local`, `openai-compatible` or `anthropic-api`; an assigned slot served by another backend ends `slot-backend-mismatch` |
| `engine.inference.slots.<slot>.alternatives` | none | Owner-approved, same-backend `model@effort` list (at most 4) used in order when the preferred pair is unsupported; the run is flagged by `Provenance.degradedFrom` |
| `engine.inference.allowlist.<backend>` | none | Owner-approved `model@effort` list (at most 256): the catalog of a backend that cannot enumerate models, and a narrowing of a discovered catalog |
| `engine.inference.fixed-quality` | none | `light`, `standard` or `deliberate`: every request resolves through that slot |

Refusals (`slot-unassigned`, `slot-backend-mismatch`, `catalog-unavailable`,
`slot-unsupported`) end the task `FAILED` before any thread or turn exists. Malformed settings
fail startup without echoing a value. Tests: `ProfileResolverTest`, `ProfileSettingsTest`,
`CodexProfileResolutionTest` (through the real adapter and the scripted app-server) and the
wiring case in `TaskPortSelectionTest`.

## Grounded narration publication, receipt fallback and retry (P08.5)

`org.rpgengine.narration` connects the presentation jobs to the narration contract. The rules
are in [docs/07 section 6](../docs/07-turn-protocol.md#p085-publication-receipt-fallback-and-narration-only-retry)
and the HTTP view in [docs/13](../docs/13-api-and-ui.md#p085-narration-presentation-and-retry).

- `NarrationContextAssembler` builds the public context of one committed operation from its
  `PUBLIC` events only, stated by `EventStatements` in English or Italian.
- `GroundedNarration` is the engine's `NarrationPort`. It runs the contract on the `standard`
  slot (90-second budget including the single repair) and returns a validated
  `NarrationPort.Publication`, or `REJECTED`, `UNAVAILABLE` or `FAILED`.
- `PresentationJobService` stores the publication in its settling transaction only while the
  campaign's locale and glossary revisions are unchanged (V13 `narration_publication`, immutable),
  falls back to the stored receipt otherwise, and queues narration-only retry rounds
  (`retry`, at most three after the original; the journal never changes). `presentation` reads
  the player view.
- The attempt lease is 120 seconds (`PresentationJobService.LEASE_SECONDS`). No new setting is
  introduced; with no ready runtime every round ends `NARRATION_UNAVAILABLE` without reading or
  submitting anything.

Tests: `GroundedNarrationTest` (real PostgreSQL, real job service, assembler, runner, sessions
and contract; only the runtime is the deterministic `NarratorTaskPort`), the retry case in
`CampaignCommandHttpTest`, the adapted `PresentationJobTest`, and the V13 step of
`CanonicalMigrationUpgradeTest`.

## Durable fair inference scheduling (P08.6)

`org.rpgengine.inference.scheduling` puts one durable fair priority queue in front of every
inference task: `ContractRunner` → `InferenceScheduler` → `ScopedSessions` → task port. The rules
are in [docs/06 section 12](../docs/06-codex-integration.md#p086-durable-fair-scheduling).

- `InferenceScheduler` (a Spring component and lifecycle) admits a `ScheduledTask` (scope,
  `WorkClass`, slot, instructions, schema, budget) into the ledger, waits for the turn permit,
  dispatches it in its scope's session with the remaining budget and records the outcome. It
  preempts running background work for interactive work, keeps the permit through
  `RECONCILING` until the port confirms an unconfirmed outcome, suspends background work after
  an account condition and recovers before any admission at startup.
- `FairQueue` is the pure selection policy: interactive first, one task at a time per
  campaign/role/audience line in admission order, background aging, fair campaign rotation and
  the interactive permit reservation.
- `JdbcTaskLedger` writes V14 `inference_task` (one row per task; identities, class, timing,
  outcome codes, estimated usage and an internal runtime reference; never instructions or
  output) and `inference_scheduler_state` (the account backoff). The partial unique index
  `inference_task_one_turn_uk` allows one dispatched or reconciling task installation-wide.
- `DispatchJournal` (neutral) is the write-ahead record the adapter makes before `turn/start`;
  `NarrationTaskPort.reconcile` receives recorded references after a restart.

| Property | Default | Meaning |
| --- | --- | --- |
| `engine.inference.scheduler.max-active` | `1` | Runtime turns at once; above 1 fails startup (`concurrency-unqualified`) |
| `engine.inference.scheduler.interactive-capacity` / `interactive-per-campaign` | `16` / `4` | Interactive tasks waiting or running, installation-wide / per campaign |
| `engine.inference.scheduler.background-capacity` / `background-per-campaign` | `8` / `2` | Background tasks waiting or running, installation-wide / per campaign |
| `engine.inference.scheduler.daily-usage-limit` / `background-daily-usage-limit` | `4000000` / `400000` | Estimated usage units per rolling 24 hours, all classes / background classes |
| `engine.inference.scheduler.aging-step-seconds` | `60` | Waiting time that promotes a background task by one class |
| `engine.inference.scheduler.backoff-initial-seconds` / `backoff-max-seconds` | `30` / `900` | Account backoff of background work, doubling with ±20% jitter |
| `engine.inference.scheduler.tick-ms` | `100` | Timer for queue deadlines, reconciliation and backoff expiry |

Tests: `FairQueueTest` (pure policy, including several permits), `InferenceSchedulerTest` (real
PostgreSQL, real ledger, sessions and scheduler; only the runtime is the `ControlledPort`
double), `SchedulerBoundaryTest`, the journal and restart cases of `CodexTaskAdapterTest`, the
scheduler wiring case of `TaskPortSelectionTest`, the V14 step of
`CanonicalMigrationUpgradeTest`, and `GroundedNarrationTest`, which now runs through the real
scheduler and ledger. Suites without a database use `MemoryTaskLedger` through
`SchedulerFixtures`.

### Optional quest planning (P08.7)

`org.rpgengine.planning` holds the optional quest planner. It is off by default, and no
other package depends on it:

- `QuestPlanAssembler` reads the privileged slice of one `known-quest:<uuid>` target in one
  repeatable-read transaction, records each read as a row-version or actor query-epoch
  dependency, captures the `PLANNER_PRIVATE` snapshot and builds the `QuestPlanContext`.
- `QuestPlanning` handles the plan lifecycle. `request` (deduplicated, never waits) and
  `processNext` claim, snapshot, run the `quest-plan`/`quest-plan-refine` contract through
  the fair scheduler and admit through `PlanAdmissionService`, narrowing each read to the
  branches whose predicates cite it. `replan` requests one bounded refinement per stale
  latest version, and `standing` reports `PREPARED` or `BASELINE` with a reason, without
  writing.
- `QuestPlanWorker` is a single daemon thread. It runs only when planning and the worker are
  enabled.

| Property | Default | Meaning |
| --- | --- | --- |
| `engine.planning.enabled` | `false` | Economy default: no planning job is requested, run or used |
| `engine.planning.slot` | `standard` | Logical inference slot of planner tasks |
| `engine.planning.budget-seconds` | `60` | Contract budget of one attempt including its repair; plus 10 s must stay below the lease |
| `engine.planning.lease-seconds` | `120` | Preparation-job lease, 30..300 |
| `engine.planning.max-attempts` | `2` | Attempts per job (one retry or regeneration), 1..5 |
| `engine.planning.time-to-live-seconds` | `3600` | Claim window of a queued job |
| `engine.planning.replan-allowance` | `3` | Most refinement jobs per plan, 0..5 |
| `engine.planning.max-horizon-seconds` | `604800` | Longest planning horizon a plan may declare (1 hour..30 days) |
| `engine.planning.priority` | `100` | Preparation-job priority, 0..1000 |
| `engine.planning.worker.enabled` / `worker.poll-ms` | `true` / `2000` | Worker thread (only with planning enabled) and its poll delay |

Tests:

- `QuestPlanContractTest`: pure; EN/IT acceptance, citation map, selective rejection and a
  28-case invalid-output corpus. `ContractSchemaTest` checks the schema against the
  validator.
- `QuestPlanningTest`: real PostgreSQL, sealed packs, production campaign creation, the real
  assembler, jobs, ledger, scheduler and gate; only the planner runtime is the
  `PlannerTaskPort` double.
- `PlanAdmissionGateTest`: manifest narrowing.
- `PlanningBoundaryTest`: no package depends on planning, and narration and presentation
  never read preparation.
- `PlanningSettingsTest`.

## Typed knowledge, last known locations and commitments (P09.1)

`org.rpgengine.knowledge` keeps canonical truth apart from what actors believe. V15 adds four
tables, all written only by the canonical commit beside the events they cite. The model and its
rules are in [docs/08 section 4](../docs/08-memory-and-continuity.md#p091-typed-knowledge).

- `knowledge_claim`: a typed proposition (`EXISTS`, `PROPERTY`, `RELATION`, `STATEMENT`) about an
  `actor:`/`item:` row or a `location:`/`definition:` content id, with a privileged truth
  (`TRUE`, `FALSE`, `UNDETERMINED`, resolved at most once), a disclosure scope (`COMMON` or
  `HOLDERS`), provenance and a validity interval through supersession.
- `knowledge_belief`: one holder's current stance (`KNOWS`, `BELIEVES`, `RUMORED`, `SUSPECTS`,
  `DOUBTS`) on a claim, with acquisition (`OBSERVED`, `TOLD` with informant, `READ` with
  document, `INFERRED`); `KNOWS` needs a current `TRUE` claim.
- `actor_sighting`: an observer's last known location of another actor and a later observed
  absence. The real current location stays in `actor.location_id`.
- `actor_commitment`: an `OPEN` promise, obligation or agreement with exact terms and their
  language, an optional deadline and one terminal resolution.

`KnowledgeChange` values ride on `ResolvedCommit.knowledge()` and cite one of the operation's
own events by index. `KnowledgeLedger.applyWithin` writes them under the commit's campaign
lock, `observeWithin` records what the player perceives after each registered intention, and
`revealedTo` lets `CommandRules` disclose a secret location or actor only through a current
`TRUE` `EXISTS` claim that is common knowledge or that the player `KNOWS` or `BELIEVES`.
`KnowledgeView.forActor` reads one actor's audience view (no truth, no real locations of
others), and `KnowledgeView.privileged` the full record. Writers bump `knowledge:claims`,
`knowledge:<actor>`, `sightings:<actor>` and `commitments:<actor>` and step row versions;
`plan_reference_version` now also evaluates `claim:`, `belief:` and `commitment:` references.

Tests: `KnowledgeLedgerTest` (real PostgreSQL, sealed pack, production creation, real admission,
rules, resolver and commit) and the V15 step of `CanonicalMigrationUpgradeTest`.

## Context envelopes, budgets and retrieval (P09.2)

`org.rpgengine.memory.ContextAssembler.assemble(Request)` builds one stamped, budgeted AI task
context from canonical storage. It reads one read-only repeatable-read snapshot and loads the
glossary outside it, and it refuses an ambient transaction. The design is in
[docs/08](../docs/08-memory-and-continuity.md#p092-context-envelopes-budgets-and-retrieval).

- **Request:** adventure, campaign, role and audience (the role must permit the audience), an
  optional committed focus operation, up to 64 focus references, an optional full-text query of
  at most 1,000 characters, the caller's instruction-token estimate, a `ContextBudget` and the
  caller's policy hash.
- **Result:** `Assembled(ContextEnvelope)` or `TooLarge` (`CONTEXT_TOO_LARGE`, with required and
  available tokens per section). Mandatory material is never truncated.
- **Envelope:**
  - a `Stamp` (generation, revision, world time, hashes, locale and glossary revisions, epochs);
  - the budget and estimator method;
  - a legend of the standings present;
  - sections `state`, `obligations`, `knowledge`, `history` and `planning`, holding items with
    `ref`, `standing` and `lang`;
  - per-section `omitted` counts, `glossaryStatus`, `glossary` and `glossaryOmitted`.

  `json()` is deterministic, `digest()` is its SHA-256, and `partial()` reports omissions.
- **Audience filtering:**
  - `public` and `character:actor:<uuid>` contexts come from `KnowledgeView.forActorWithin`
    (new, caller-transaction variant);
  - `privileged` contexts come from `privilegedWithin`, plus the assumptions of usable quest
    plans read through the preparation-owned `PlanAssumptions.usableWithin` (current
    generation, gate-admitted, validated or partially invalidated).

  `fact` and `assumption` items are refused for limited audiences.
- **Retrieval:** required items first, then the `id` tier (explicit references; `event:` and
  `narration:` references also fetch records older than the recent window), the `causal` tier,
  the `text` tier (PostgreSQL full text with the `english` or `italian` configuration of each
  text's language) and finally the `recent` tier.
- **Selection:** `ContextSelection` is pure and deterministic, and enforces per-section
  allowances and headroom. `TokenEstimator` uses a conservative one token per two UTF-8 bytes.
- **Glossary:** `PackGlossaries` binds the pinned pack's glossary for the definitions the items
  name, in the output locale. A `term` avoids the other locale's form.
- **Staleness and guard:** `staleness(stamp)` lists changed components.
  `withheldPhrases(campaign, holder)` returns disclosure-guard markers.

`EventStatements` is now public so the assembler can state events in the output locale.

Tests:
- `ContextSelectionTest` (pure): estimator, budgets, item and envelope invariants, ranking,
  allowances and overflow.
- `ContextAssemblerTest` (real PostgreSQL, sealed packs, production creation, commands, commit
  and the planning pipeline with a scripted planner): audience filtering, standings, the
  overflow boundary, ID and English/Italian full-text retrieval beyond the window, glossary
  binding, staleness and withheld phrases.

## Memory checkpoints and continuity summaries (P09.3)

`org.rpgengine.memory` keeps operational checkpoints and audience-scoped, source-linked
continuity summaries (migration V16). None of it writes canonical state. The design is in
[docs/08](../docs/08-memory-and-continuity.md#p093-checkpoints-summaries-freshness-and-bounded-jobs).

- **`OperationalCheckpoints`:** `capture(campaign)` stores the `jsonb` snapshot of the database
  function `memory_operational_snapshot` in `campaign_checkpoint` with a SQL-computed SHA-256 of
  its canonical text. It returns `CAPTURED`, `EXISTING`, `UNSAFE_BOUNDARY` (a live mutation lease
  or a `COMMITTING` operation; nothing written) or `MISMATCH`. `verify(campaign)` returns `NONE`,
  `CORRUPT`, `UNSUPPORTED`, `INCOMPATIBLE`, `DIVERGED`, `OUTDATED` or `VALID`; `latest` and
  `prune` complete it. The snapshot never contains the RNG seed.
- **`MemorySummaries`:** `request(campaign, audience, force)` applies the request policy
  (interval of significant actions, scene boundary, one pending job per audience, no automatic
  retry of a failed range, installation-wide cap). `recover`, `claim`, `fail` and `record`
  manage fenced job attempts. `freshness(summary)` returns `CURRENT`, `BEHIND` or `STALE` with
  reasons, `usableWithin` gives the assembler the newest summary that is not stale, and
  `invalidate(campaign, reason, refs)` records an explicit invalidation.
- **`ContinuitySummarizer.processNext(holder)`:** recovers, claims one job, assembles the
  audience's envelope (`ContextAssembler.Request` with `summaries = false`), runs the `summary` or
  `summary-privileged` contract (`SummaryContract`, `ai-contract/1/summary.schema.json`) as
  `CONTINUITY` work through the fair scheduler, and records the result after a source recheck.
- **`ContextAssembler`:** a usable summary is an optional `history` item with standing
  `summary`; `Request` gained a trailing `summaries` flag (the 10-argument constructor keeps it
  on).
- **`MemoryWorker`:** one daemon thread with bounded passes (checkpoints for at most 16
  campaigns, then, when summaries are enabled, public summary requests for at most 16 campaigns
  and one job).

| Property | Default | Meaning |
| --- | --- | --- |
| `engine.memory.worker.enabled` | `true` | Run the maintenance thread (the test configuration sets `false`) |
| `engine.memory.worker.poll-ms` | `5000` | Delay between passes, 100–300,000 ms |
| `engine.memory.checkpoint-retention` | `8` | Newest checkpoints kept per campaign, 2–64 |
| `engine.memory.summaries.enabled` | `false` | Request and run continuity summaries |
| `engine.memory.summary-interval` | `5` | Significant actions per summary, 1–10 |
| `engine.memory.max-pending` | `256` | Unfinished summary jobs installation-wide, 1–1,024 |
| `engine.memory.max-attempts` | `3` | Attempts per summary job, 1–5 |
| `engine.memory.budget-seconds` | `60` | Contract budget per attempt, including one repair |
| `engine.memory.lease-seconds` | `120` | Job lease, 30–300 s; must exceed the budget plus 10 s |
| `engine.memory.time-to-live-seconds` | `3600` | Claim window of a queued job, 60–86,400 s |
| `engine.memory.slot` | `light` | Inference slot of summary tasks |

Tests:
- `SummaryContractTest` (pure): acceptance in English and Italian, precise coverage, surviving
  obligations, labelled uncertainty, grounded numbers and quotations, public scope, locale and
  the kind/audience/work-class mapping; `ContractSchemaTest` checks the schema.
- `MemoryContinuityTest` (real PostgreSQL, sealed packs, production creation, commands and commit,
  the scripted runtime behind a real scheduler): checkpoint determinism, verification and safe
  boundaries; the request policy; precise source-linked recording; audience separation;
  freshness after newer events, source changes, invalidations, a locale switch and a generation
  change; the source race; bounded retries, fencing, throttling and database guards.
- `CanonicalMigrationUpgradeTest` covers the V16 step.

## Session renewal, records and rehydration (P09.4)

Design: [docs/08 section 7](../docs/08-memory-and-continuity.md#p094-renewal-triggers-session-records-and-rehydration).
Migration V17 adds `ai_session_record`. Nothing here writes canonical state or the transcript.

- **`ScopedSessions`** keeps counters per live session. `settled(taskId, result)` is called by
  the scheduler for every forwarded task: a result with provenance (or `UNRESOLVED`) adds a turn
  and its estimated size; a completed one counts toward the ceiling; one that did not complete
  retires the session (`UNRELIABLE`). At the line's next `submit`, a session at the turn ceiling
  (`TURN_CEILING`) or one whose conversation plus the task would cross the pressure threshold
  (`CONTEXT_PRESSURE`) is replaced, unless the rehydrator defers because a commit may be in
  progress. `renewCampaign(id)` retires a campaign's sessions (`OWNER`). `recover()` (called by
  the scheduler's startup recovery) retires records left active. `status()` adds `deferred`,
  `warned` and `rehydrated` counts; `usage(scope)` reads one session's counters.
- **`RenewalPolicy`** validates `engine.inference.sessions.*` (table below).
- **`SessionJournal` / `JdbcSessionJournal`** write `ai_session_record`: one row per session with
  line generation, predecessor, opening cause, retirement reason, counters and the rehydration
  basis, code, revision, digest, checkpoint and summary. Failures are logged and never block.
- **`Rehydrator` / `SessionRehydration`** build the continuity block of a renewed line's first
  task: capture and verify a checkpoint (deferral at an unsafe boundary), request a summary of
  the uncovered tail, assemble a 4,000-token envelope for the scope's own role and audience with
  the usable summary, and check that the resume point and the envelope share one revision. The
  summarizer role gets no block.
- **`SessionRenewalService` / `SessionRenewalController`**:
  `POST /api/v1/campaigns/{id}/ai-session/renew?adventureId=…` with `{}` renews an idle campaign
  (200 with counts; 409 `SESSION_RENEWAL_BUSY`; 404, 400, 503 fixed codes).

| Property | Default | Meaning |
| --- | --- | --- |
| `engine.inference.sessions.turn-ceiling` | `10` | Completed tasks per session before renewal, 1–100 |
| `engine.inference.sessions.context-window` | `128000` | Estimated conversation size (4-byte units) the percentages refer to, 8,000–2,000,000 |
| `engine.inference.sessions.context-warn-percent` | `75` | Warning threshold, below the renewal threshold |
| `engine.inference.sessions.context-renew-percent` | `85` | Renewal threshold, 2–99 |

Tests:
- `ScopedSessionsTest` (doubles): the ceiling renews at the next task with the block on that
  task only; pressure renews only after a turn and counts the warning once; failed or unconfirmed
  turns retire at once while turnless results count nothing; deferral keeps the session; an
  oversized block is omitted; owner renewal and journal history after a restart rehydrate; a
  failing rehydrator costs continuity only; settings are validated.
- `MemoryContinuityTest` (real PostgreSQL, real scheduler, journal and rehydration, scripted
  runtime): the ceiling renewal resumes at the starting decision with a verified checkpoint, no
  opening text and linked records while the campaign row, events and transcript are unchanged;
  an unreliable turn and context pressure renew with `free-input`; a live lease defers renewal
  and a restart rehydrates from the journal; owner renewal waits for idleness, refuses a foreign
  owner and resumes from the public summary with the open promise and without the secret; the
  summarizer gets no block.
- `CampaignCommandHttpTest`: the renewal route answers counts only, refuses malformed bodies,
  foreign scopes and missing browser proofs, and leaves the campaign unchanged.
- `CanonicalMigrationUpgradeTest` covers the V17 step.

## Restart reconciliation, missing dependencies and the resume card (P09.5)

Design: [docs/08 section 10](../docs/08-memory-and-continuity.md#p095-restart-reconciliation-missing-dependencies-and-the-resume-card).
No migration. Nothing here writes canonical state, the transcript or a counter.

- **`PackRegistry.diagnose` / `ClosureDiagnosis` / `CampaignDependencies`** (content): whether this
  build can load a campaign's pinned closure. `AVAILABLE`, `UNSEALED` (a legacy root admitted before
  sealing; unchanged behavior) or `MISSING` with issues `REVISION_MISSING`, `RULESET_UNAVAILABLE`,
  `CONTENT_CORRUPT`, `DEPENDENCY_MISSING` (every missing dependency is listed) or `CLOSURE_INVALID`,
  each with the pack ID and version only. `AVAILABLE` is cached per revision for the process;
  failures are never cached, so a restored or re-imported pack lifts the pause without a restart.
- **Mutation pause** (`CommandRuleCatalogs`, `OperationAdmissionService`): a fresh command of a
  campaign whose closure is `MISSING` fails with `DEPENDENCIES_MISSING` (HTTP 409
  `COMMAND_DEPENDENCIES_MISSING`) right after the replay check; nothing is journaled, and identical
  retries of earlier requests still replay.
- **`MemoryReconciliation`** (one pass, pages of 100 campaigns, per-campaign failures counted and
  logged): `MemorySummaries.recover()` once (also cancelling queued jobs whose range is beyond the
  campaign's newest event, `RANGE_UNAVAILABLE`); per campaign the closure diagnosis; checkpoint
  repair (unusable checkpoints discarded newest first, `CHECKPOINT_DIVERGED` invalidation after a
  divergence, recapture at a safe boundary); a one-time `STATE_ROLLED_BACK` invalidation for
  summaries built from a revision the campaign no longer has; a forced replacement request for an
  audience whose summaries are all stale (summaries enabled only); and counts of narration still
  owed. **`MemoryStartupReconciliation`** runs it once per start in lifecycle phase
  `Integer.MAX_VALUE - 4096`: after every startup recovery, before HTTP and the workers.
- **`ResumeCards` / `ResumeCardController`**: `GET /api/v1/campaigns/{id}/resume?adventureId=…`
  returns the resume card (location with its public localized name, actors the player last saw
  there, resources, a null encounter, the pending decision kind, up to 16 actions awaiting
  narration with receipts and presentation state, the dependency diagnosis and continuity codes).
- Freshness gains the `rolled-back` reason (summary source revision or event high-water above the
  campaign's counters).

| Property | Default | Meaning |
| --- | --- | --- |
| `engine.memory.reconciliation.enabled` | `true` | Run the restart reconciliation pass at startup (tests set false and drive it directly) |

Tests:
- `PackRegistryPersistenceTest.diagnosesPinnedClosuresWithoutThrowing`: available, unsealed,
  missing revision, an upgraded ruleset, a lost dependency and changed bytes, without writing.
- `MemoryContinuityTest` (real PostgreSQL): checkpoint repair for corrupt, diverged, ahead,
  outdated and unsafe cases with canonical state unchanged; stale and rolled-back summary work;
  a lost dependency pauses admission in a fresh process while replays still work and the card
  explains it; interrupted narration resumes exactly once after a restart behind a rehydrated
  narrator that does not replay the opening, pending narration keeps its receipt and retries, and a
  delivered narration-only retry removes the action from the card and the count.
- `CampaignCreationHttpTest`: the card over HTTP (read-only, localized EN/IT location name, 400,
  404, 403), corrupt pinned content answers 409 `COMMAND_DEPENDENCIES_MISSING` and the restored
  content lifts the pause. `CampaignCommandHttpTest`: a legacy unsealed campaign is not paused.
- `MemoryStartupReconciliationTest` (pure) and `MemoryStartupPassTest` (real context): the pass
  runs once per start before the web server and never aborts startup.
- `CampaignInterfaceContractTest` records the card in the browser reload transcript.

## Model catalog and hardware advisor (P21.1)

`org.rpgengine.inference.catalog` is a pure package with no Spring wiring yet. It reads the
bundled catalog `src/main/resources/inference/model-catalog.json` (`ModelCatalog.bundled()`) and
a launcher profile (`HardwareProfile.parse`). Both are closed formats, parsed strictly; a
violation is a `CatalogException` with a rule code and a JSON Pointer. `ModelAdvisor.advise` ranks
every entry against the profile, explains it with notes, picks a recommended text and image
entry and fills the local-only, hybrid and cloud-first presets. Formats, rules and the fixture
results are in [docs/34 section 5](../docs/34-inference-backends-and-model-selection.md#5-catalog-and-hardware-advisor).

- `ModelCatalogTest`: the bundled catalog's pins and every rejection rule.
- `HardwareProfileTest`: the six fixtures under `src/test/resources/inference/hardware-profiles/`,
  which `tools/container_packaging/test_hardware_profile.py` keeps byte-identical to the
  launcher's output, and the rejection rules.
- `ModelAdvisorTest`: the synthetic profiles (no GPU, 6, 8, 12 and 24 GB) with hand-derived
  budgets, contexts and rankings.

Memory figures are estimates until P21.2 and P21.5 record measured profiles. Nothing here
downloads, starts a process or changes a setting.
