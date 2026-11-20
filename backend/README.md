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

A checkout can keep the same three settings in an ignored file instead of exporting
them; see [Local datasource file](#local-datasource-file).

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

## Local datasource file

Spring Boot reads `config/application.properties` from the working directory in
addition to the classpath. For Maven test runs and `spring-boot:run` started from
`backend/`, that is `backend/config/application.properties`. A checkout whose build
tools do not export `SPRING_DATASOURCE_*` keeps its private test database settings
there:

```properties
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/engine
spring.datasource.username=engine
spring.datasource.password=<private value>
```

Rules and limits:

- **Private.** `.gitignore` ignores `/backend/config/`. The `secrets` check in
  [repository checks](../tools/repository_checks/README.md) rejects any file there
  that Git could stage, and fails if the ignore rule is dropped. Restrict the folder
  to its owner (`chmod 700 config`, `chmod 600 config/application.properties`).
  Never commit it or copy it into an issue or log. The default-deny `.dockerignore`
  keeps it out of image build contexts.
- **Exported variables win.** `SPRING_DATASOURCE_*` environment variables outrank
  the file, so CI and shells that export them behave as before. Command-line
  arguments and test properties outrank both.
- **Same requirements.** The file only supplies the connection. A missing or
  unreachable database still fails `verify`; nothing skips or switches to H2. Use
  a disposable database, because the persistence tests truncate their tables.
- **Scope.** Only processes whose working directory is `backend/` read it.
  `PackagedSecretsTest` sets an explicit `spring.config.location` and ignores it.
  The packaged runtime runs elsewhere and never sees it.
- **Why it exists (29 October 2026).** A workspace check ran
  `./mvnw --batch-mode verify` from `backend/` with no datasource variables, and
  every database-backed context failed with "Failed to determine a suitable driver
  class" (303 errors). The file gives that run the same real PostgreSQL database
  that the exported variables provide elsewhere. Embedding a default URL or
  credentials, or skipping database tests without a URL, would weaken the contract
  above. See [ADR 0009](../docs/adr/0009-toolchain-baseline.md#decision-backend-build-and-database-p002).

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

An optional `presentation` file declares the public scene, line and asset references and the poses
and expressions each NPC may take. It compiles into `CompiledPack.presentation()` (an empty
`PackPresentation.NONE` when absent) and so into the campaign snapshot. Admission refuses a URL,
markup, over-long or over-bounded reference and an undeclared asset, without echoing the value; see
[pack format 3.3](../docs/09-adventure-packs.md#33-presentation-declaration). Its one reader is
`org.rpgengine.narration.PresentationScopeResolver`: a read-only service that resolves the
`PublicPresentationScope` of one committed operation from the pinned declaration and the actors its
public events reference (the narration visibility rule). It returns an empty `Optional` for a stale or
unknown request. `PresentationJobService` calls it through `PresentationScopePort`, only for a
publication that carries cues; the grounded publisher produces none yet, so it is not called during play.

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

`AdventureIsolationTest` (P06.5-B.1) proves the backend side of QA-33 at MVP scope in every
backend run: two equal-title genres with same-name NPCs and items keep separate commands, HTTP
reads, memory, job rows, limits and folders, also across a restart in a separate JVM.
`AdventureRestoreCheck` holds the same world for the real restore rehearsal. Its name keeps it out of
backend runs, because it needs a database the backup tool can reach: run it through
[`test_restore_isolation.py`](../tools/container_packaging/README.md#real-backup-and-restore-of-two-genres),
not on its own. A restart legitimately changes two derived tables (queued `inference_task` rows end
`ABANDONED`, and memory reconciliation captures the first `campaign_checkpoint` of each campaign), and so the
resume card's continuity codes change. No canonical, inventory or memory row does.

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
- `PresentationEventStore` stores admitted presentation events (V26 `presentation_event`, P05.7-B.1.1);
  its `presentation_event_stream` trigger adds one `presentation.event` message per stored cue to the same log.
  The settling transaction of `PresentationJobService` is its production caller (P05.7-B.1.2.3.2): it records
  the cues of a publication being stored, and a refused list is dropped and logged without losing the narration.
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
`PublicStreamTest`, `PresentationEventStreamTest`, `PresentationJobTest` and `CampaignStreamHttpTest`. Test fixtures that
truncate tables must include `presentation_event`, `presentation_job`, `campaign_stream_message` and
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
the probe fixture; the drift check fails if `../tools/runtime_probe/fixtures/0.164.0/neutral-session.json`
is absent, so the verification image admits exactly that file), `StdioFrameWireTest`, `UnixSocketFrameWireTest`,
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

## Item commands and source-linked creation (P10.2)

Five more registered intentions — `USE_ITEM`, `EQUIP_ITEM`, `UNEQUIP_ITEM`, `TRANSFER_ITEM` and
`TRADE_ITEMS` — run through the same pipeline as the P07.4 actions ([rules and categories](../docs/07-turn-protocol.md#p102-item-intentions),
[ADR 0024](../docs/adr/0024-atomic-item-commands-and-source-linked-creation.md)). Package `org.rpgengine.inventory`
holds the pieces:

- `InventoryModel` is the pure working copy `LocalActionResolver` edits (consume, transfer, deposit) and
  turns into a minimal `ItemChange` list: whole-stack moves reassign the same instance, other units
  merge into the receiver's open stacks in identity order, and new identities derive from the operation.
- `ItemPlan` carries the changes, the units created (with source kind, key and announcing event index)
  and consumed, and the equipment changes. Its constructor proves unit conservation per definition.
- `InventoryWriter` applies a plan inside the commit under the campaign lock: every update and delete
  is a compare-and-set on owner, quantity, definition and storage version, and every created unit gets an
  `item_creation` row (V19, append-only) citing the operation and the `ITEM_CREATED` event.
  `CanonicalCommitService` calls it after the events are flushed and applies equipment changes through
  `CharacterService`, so an equip is checked twice (rule gate and commit).
- `CommandRuleCatalog` now also holds each item's `value` and `use` contract and the `CharacterRuleCatalog`
  of the closure.

Pack items may declare `value`, `use` and a `createItem` `quantity` ([pack contract](../docs/09-adventure-packs.md#7-bounded-expressions-and-effects)).
Item and ability effects share one resolver, so `createItem` now also works inside abilities.

Tests: `InventoryModelTest` (pure: merging, reassignment, splitting, determinism, conservation),
`ItemContractPackTest` (pack contract), `ItemCommandsTest` (real PostgreSQL on the armory pack: QA-10
potion, transfers, trades, equipment, creation ledger, refusals, replay, fault after item writes, stale
guards), `CanonicalCommitServiceTest` (reward-sourced creation and rollback), `TypedCommandTest`,
`ResolvedCommitTest` and `EventStatementsTest`. `ArmoryPack` is the shared in-memory pack variant.
Limits: no NPC stock or shop screens and no weight/capacity/durability (the panels arrived with P10.5).

## Quests, objective predicates and one-time rewards (P10.3)

Three more registered intentions — `ACCEPT_QUEST`, `SUSPEND_QUEST` and `RESUME_QUEST` — and the
quest engine that runs after every fact-changing command ([rules and categories](../docs/07-turn-protocol.md#p103-quest-intentions),
[ADR 0025](../docs/adr/0025-quest-lifecycle-objective-predicates-and-one-time-rewards.md)). Package
`org.rpgengine.quests` holds the pieces:

- `QuestRules` is the prose-free projection of every pinned quest (initial status, discovery predicate,
  objectives, outcomes and merged item rewards); `CommandRuleCatalog` carries it, so a cached catalog
  keeps no authored text.
- `QuestPredicate` copies the compiled world expression grammar (`visited`, `possesses`, `questStatus`,
  `objective`, boolean and integer operators) and evaluates it over a `Facts` view. No atom reads dice,
  the clock or prose.
- `QuestPlan` is the pure transition engine. `LocalActionResolver` runs it over the working
  `InventoryModel`, the destination of a move and the stored state; passes repeat in quest-identity
  order until nothing changes, and reward units are deposited through the same inventory model so later
  predicates in the same commit count them. It budgets events and reward units and defers, never drops.
- `QuestChanges` is the immutable result a `ResolvedCommit` carries (quest state writes, satisfied
  objectives, first visits, each citing its announcing event). `QuestWriter` applies it inside the
  commit after the events are flushed: updates are compare-and-set on the storage version, terminal rows
  are immutable by trigger, objectives and visits are append-only (V20). `QuestStore` reads the stored
  state under the campaign lock.
- An ending with rewards adds a `ResolvedCommit.Reward` keyed `quest:<id>:outcome` for the player and
  `REWARD`-sourced `ItemPlan.Creation`s, so `reward_settlement` (unique per source and recipient) and the
  `item_creation` ledger both record it. `EventStatements` states the seven quest events in both
  languages and `CommandReceipts` projects a public `quests` list.

Tests: `QuestPredicateTest` and `QuestPlanTest` (pure), `QuestCommandsTest` (real PostgreSQL on the
`QuestPack` variant of the armory pack: accept/move/settle once, suspension, refusals, an item effect and
a quest ending in one commit, a chain of quests and a hidden quest, fault after the quest writes, replay,
stale and ended-quest writers, attribution), plus the pack reward bound in `PackCompilerTest`,
`TypedCommandTest`, `EventStatementsTest` and the V20 step of `CanonicalMigrationUpgradeTest`. Limits: no
clock (P10.4 added it), fact, encounter or interaction predicates, no abandon command,
no XP or reputation rewards, no live V20 dump and restore rehearsal.

## World clock, travel and rest (P10.4)

Time is a bounded counter owned by the canonical commit ([ADR 0026](../docs/adr/0026-bounded-world-clock-travel-duration-and-rest.md)).

- `org.rpgengine.rules.WorldClock` (pure) holds the bounds (one day per operation, a legal wait of four hours,
  a century in total), the calendar reading and the units a quest threshold reads. `RestRules` holds the rest
  policy value and its three calculations (interruption from a recorded draw, elapsed time, restoration).
- `CommandRuleCatalog` projects each location's per-link `travelMinutes` and `tags` and the pack's rest policies.
  `CommandRules` refuses a wait over four hours, a duration that does not fit the clock (`TIME_ILLEGAL`) and an
  unusable rest (`REST_UNAVAILABLE`). `LocalActionResolver` sets the command's elapsed time (link minutes, wait
  seconds or the rest's own length), resolves a rest (one draw when interruptible) and passes the clock to the
  quest engine, whose `worldTime` atom sees the time after this command.
- `ResolvedCommit.WorldChange` rejects a step over one day; `Campaign.commitWorld` re-checks the end of the
  clock; V21 enforces the same bounds in PostgreSQL and ties a time change to its revision step.
  `ResolvedCommit.ConditionRemoval` carries the conditions a rest ends; the commit applies them guarded by the
  row it deletes.
- `EventStatements` states `ACTOR_MOVED` (with its duration), `REST_COMPLETED`, `REST_INTERRUPTED`,
  `RESOURCE_RECOVERED` and `CONDITION_REMOVED`; `CommandReceipts` projects a `rest` summary only for a rest.

Tests: `WorldClockTest` and `RestRulesTest` (pure), `LocalActionResolverTest` (link duration, the clock bound, both
interruption branches with stub draws), `ClockCommandsTest` (real PostgreSQL on the `ClockPack` variant of the
armory pack: authored travel time, bounded waits, the end of the clock, the V21 guards against any writer, full and
clamped recovery, condition removal, a recorded interruption and its replay, refusals, a clock quest completed by
a wait and by a rest, a failed commit leaving the clock alone, attribution), `WorldClockQueriesTest` (every read
endpoint repeated while real time passes: no row and no clock change), plus the link, rest and `worldTime`
validation in `PackCompilerTest`, `TypedCommandTest`, `EventStatementsTest`, `ResolvedCommitTest`,
`CommandRulesTest` (a dependency pack's rest policy is refused), `CampaignFoundationsTest` and the V21 step of `CanonicalMigrationUpgradeTest`. Limits: the panels show the clock since
P10.5; a rest is interrupted by a draw, not by an encounter (P11); condition durations are in rounds,
so neither waiting nor rest ticks them; no faction or event clocks or bounded catch-up (P15.2); one link per move;
no live V21 dump and restore rehearsal.

## Player panels and journal (P10.5)

One read shows the committed state of a playthrough ([ADR 0027](../docs/adr/0027-player-panels-and-journal-projection.md),
[API](../docs/13-api-and-ui.md#p105-panels-and-journal)): `GET /api/v1/campaigns/{id}/panels`.

- `org.rpgengine.panels.PlayPanels` authorizes the owner, adventure and campaign, then reads clock, effective sheet
  (`CharacterService.sheet`, so conditions and derived statistics are recomputed from the pinned rules), resources,
  conditions, abilities, inventory with worn slots, equipment, quests (`quest_state` and `quest_objective` joined
  with `QuestRules`), typed knowledge (`KnowledgeView.forActorWithin`) and one page of the public event log in a
  single read-only repeatable-read transaction. It writes nothing, draws nothing and never moves the clock.
- `PanelContents` caches, per sealed content revision, the quest rules, character rules and root-pack localization
  (`PanelContent`). Only text whose source visibility is public is returned; a revision that cannot be loaded yields
  numbers without names or quests (`contentAvailable: false`).
- A quest with no stored row stands at its initial status, so quests that start available are listed from revision
  zero. Undiscovered quests, and hidden (`secret`) background quests until they end, are never listed.
- The journal lists `PUBLIC` events only, worded by `EventStatements` in the campaign's narrative language, newest
  first, paged by an exclusive sequence cursor (`before`), at most 100 events per page and 1,000 stored events
  examined per call; events the statements cannot word are skipped.
- `PlayPanelsController` answers with the codes of the other campaign reads. The legacy `/quests` and `/known-state`
  sections of the state endpoint still read the free-text tables; no panel does.

Tests: `PlayPanelsTest` (real PostgreSQL and servlet stack on the quest pack: starting state, every committed change
visible at its revision, the hidden quest and internal events withheld, conditions changing the effective sheet,
cursor paging with a stable past, campaign language, typed knowledge with stances and promises, free-text facts
ignored, byte-identical repeated reads, a refused command changing nothing, scope and input errors),
`WorldClockQueriesTest` (the panels join the reads that never move the clock) and `CampaignInterfaceContractTest`
(three recorded `panels` replies). Limits: no encounter, shop, map, rumor or relationship panels, no journal search or
filters, dependency-pack definitions have no names, journal lines name quests and locations by identifier, and the
legacy free-text tables remain until the planner stops reading them.

### Combat projection (P12.1-A)

`GET /api/v1/campaigns/{id}/encounter?adventureId=…` ([API](../docs/13-api-and-ui.md#p121-a-combat-projection)) is the
backend of the combat panel.

- `org.rpgengine.panels.CombatPanels` authorizes the scope and reads the active encounter (`EncounterWriter.state`) and this
  encounter's `PUBLIC` `ASSESSMENT_RESOLVED` events in one read-only repeatable-read transaction. It returns the cursor,
  initiative order, zones and current passages with crossing costs, public standings with effects, the player's own values,
  objectives with their status (`EncounterObjective.evaluate`), and another participant's values only from its newest
  successful assessment, with the round and revision they were learned at.
- Options and verdict are `TacticalRules.options` and `EncounterOutcome.publicForm` passed through
  `CommandReceipts.tactical`, the receipt's own allowlist, so the projection lists exactly what a receipt would.
- `CombatPanelsController` answers with the codes of the other campaign reads; `encounter` is null outside a fight.
- Since P13.2-B ([ADR 0042](../docs/adr/0042-offered-encounters-reveal-only-what-entry-reveals.md)) the body also has
  `offers`: outside a fight, the templates at the player's location that a plain `ENGAGE` would admit, chosen by
  `commands.EncounterOffers` (`CommandSemantics.explorer` and `CommandRules.engageable`, which calls the admission gate itself)
  with the pinned catalog loaded before the snapshot. An offer holds only the starting zones, participants with side and zone,
  escape zones, objectives and opening policy with its targets. `offers` is empty during a fight, for a defeated player, a
  paused campaign or an unsupported decision. Tested by `CombatPanelTest`'s `offersNameOnlyTheEncountersEntryAdmits` and
  `aDefeatedPlayerIsOfferedNothing`.

Tests: `CombatPanelTest` (real PostgreSQL on the encounter and tactics fixtures: the projection of `close-brawl` against the
stored state and the receipt's options, known values after a successful assessment and none after a failed one,
byte-identical repeated reads that write nothing, scope refusals; hidden sentinels in `ridge-retreat` (the sentry's attack
and reaction absent, only public members per participant, a forged internal assessment and another encounter's assessment
ignored); in `parley-ground` no counteroffer or difficulty before an attempt, and the agreement verdict at
`OUTCOME_PENDING`). Limits: no HTTP-level test of the route, no frontend panel, participants are named by roster ID only.

## Encounter lifecycle, combat profiles and zones (P11.1)

Entering combat freezes an authored encounter ([ADR 0028](../docs/adr/0028-encounter-lifecycle-frozen-profiles-and-zone-graph.md),
[combat](../docs/11-combat-system.md#2-combat-state-machine)).

- `org.rpgengine.rules.CombatProfile` (pure) is one adventure's profile: the fixed scheduler kernel
  (`skirmish-initiative-rounds/1`) plus the 25 provisional parameters of docs/11 section 17, each with its default and
  bound, the cross-parameter invariants, the role mapping (force, finesse, insight, resolve to pack attributes) and
  the Health and optional Focus resources. `canonical()` and `hash()` give its frozen identity. `CanonicalJson`
  writes sorted, whitespace-free JSON for frozen values. `ZoneGraph` (pure) is the encounter space: range distance
  over bordering zones, movement cost over unblocked directed edges, reachability and validation.
- `org.rpgengine.combat`: `EncounterPhase` (the docs/11 state machine), `OpeningPolicy` (`initiative`, `first`,
  `forbidden`), `EncounterObjective` (pure predicate evaluation), `EncounterTemplate` (prose-free template with
  structural invariants), `CombatRules` (profiles and templates projected from a pinned closure, carried by
  `CommandRuleCatalog.combat()`), `FrozenEncounter` (pure freezing of template, profile and the player's committed
  facts) and `EncounterWriter` (stores the encounter and its roster inside the commit; reads the active encounter).
- `PackCompiler` validates `ruleType: combat` and the `encounter` kind with located diagnostics. `ENGAGE` is checked by
  `CommandRules.engage` (`ENCOUNTER_UNAVAILABLE`), resolved by `LocalActionResolver` (no time, no draw, no pool change;
  encounter mode; one public `ENCOUNTER_STARTED` event) and committed with `ResolvedCommit.EncounterEntry`. The
  commit refuses encounter mode without an entry; V22 keeps frozen columns immutable, the lifecycle on the diagram's
  arrows, the opening strike resolvable once and one active encounter per campaign. `EventStatements` states the
  entry in both languages and `CommandReceipts` projects an `encounter` summary for an entry.

Tests: `CombatProfileTest` and `ZoneGraphTest` (pure), `EncounterLifecycleTest` (phase table, objective
evaluation), `CombatRulesTest` (projection, freezing with the worked example's Guard 12 and initiative modifier 4,
refused contradictions, a customized profile through the same kernel, template invariants), `CombatPackTest`
(compiler diagnostics for kernel, parameters, mappings, zones, feasibility and rosters), `EncounterCommandsTest`
(real PostgreSQL on the `EncounterPack` variant of the armory pack: entry freezes everything and resolves nothing,
a replayed retry, every refusal writing nothing, encounter mode refusing exploration and a second entry, the
`first` opening policy, the database guards, a customized profile), plus additions to `TypedCommandTest`,
`EventStatementsTest`, `ResolvedCommitTest`, `PackCompilerTest` and the V22 step of `CanonicalMigrationUpgradeTest`.
Limits: no initiative, activation or tactical intention yet (P11.2), so an entered encounter cannot progress or
end; the opening strike is stored, not resolved (P11.2/P11.3); items carry no Guard or armor modifier; no concealed
participants or hazards; the V16 checkpoint still records no encounter (P11.6); no frontend control sends `ENGAGE`
and no panel shows an encounter (P12.1); no live V22 dump and restore rehearsal.

## Initiative, rounds, activations and bounded combat dialogue (P11.2)

Inside an encounter the player submits tactical intentions
([ADR 0029](../docs/adr/0029-initiative-rounds-activations-and-bounded-combat-dialogue.md),
[turn protocol](../docs/07-turn-protocol.md#p112-tactical-intentions)).

- `TypedCommand.Tactical` marks `ROLL_INITIATIVE`, `MOVE_ZONE`, `END_ACTIVATION`, `COMBAT_SPEAK` (text at most 500
  characters) and `INTERACT`; `CommandSemantics` admits them only in `ENCOUNTER` mode and exploration intentions only
  in `EXPLORATION` mode. They read only the frozen encounter (`needsCatalog` is false).
- `org.rpgengine.combat`: `TacticalIntent` (the combat-layer values), `EncounterState` (stored tactical state with the
  frozen profile, zone graph and declared actions read back from canonical text by `EncounterWriter.state`),
  `TacticalRules` (pure legality: turn, movement, addressing, speech allowance, declared actions, action points, and
  the receipt's legal options), `CombatScheduler` (pure initiative roll and initiative-round scheduler that stops at
  the player's decision) and `TacticalPlan` (steps, participant facts, events, round time and receipt summary),
  written by `EncounterWriter.progressWithin` one row version per step. `EncounterAction` is a declared social or
  scene action; `CombatProfile.fromCanonical` and `ZoneGraph.fromCanonical`/`withPassage`/`passageIs` support them.
- `CommandRules.tactical` maps `TacticalRules` refusals to `TURN_UNAVAILABLE`, `ACTION_POINTS_INSUFFICIENT`,
  `ZONE_UNAVAILABLE`, `TARGET_ILLEGAL`, `SPEECH_LIMIT_REACHED` and `INTERACTION_UNAVAILABLE`, and requires the clock to
  take one round for the roll and the end of an activation. `LocalActionResolver` turns the plan into events (the
  player's decisions attributed to the player), elapsed time and the receipt's `combat` block; `ResolvedCommit`
  carries it as `progress` (encounter mode, its own time); `requireAttributed` binds it to the campaign's active
  encounter. V23 adds the cursor columns and the scheduling triggers; the backup tool covers schema 23.
- The pack compiler validates encounter `actions`; `EventStatements` states the eight new event types in English and
  Italian.
- `CombatProfile.ROLE_MIN`/`ROLE_MAX` (0..6) bound every frozen role value, so an initiative total always fits V23's
  2..32: `PackCompiler` refuses a role mapped to an attribute declared beyond them, `CommandRules.engage` and
  `FrozenEncounter.freeze` refuse a stored value outside them, and `TypedCommand` refuses U+0000 in any text field.

Tests: `CombatSchedulerTest` (pure scheduler and rules), `TacticalCommandsTest` (real PostgreSQL on the
`EncounterPack` fixture, whose templates now declare social and scene actions), `CombatPackTest.refusesBrokenActions`,
plus additions to `TypedCommandTest`, `EventStatementsTest`, `ResolvedCommitTest`, `CampaignCommandHttpTest`,
`EncounterCommandsTest` (a first round needs initiative, role values out of range refuse entry) and the V23 step of
`CanonicalMigrationUpgradeTest`. `TacticalCommandsTest.aLongFightKeepsEveryActivationLegal` plays a seeded fight
against an independent model of the fixture.
Limits: non-player participants pass their activations (attacks P11.3, tactics P12.3/P12.4); the opening strike
stays pending (P11.3); reactions refresh but are never spent (P11.4); no terminal predicate runs and a capped
encounter waits at its round end (P11.5); social attempts get no response yet (P11.5); the V16 checkpoint still
records no encounter (P11.6); no frontend control or panel (P12); no live V23 dump and restore rehearsal.

## Attacks, defense, damage and healing (P11.3)

Inside an encounter the player can now hurt and be hurt, with every roll recorded
([ADR 0030](../docs/adr/0030-attacks-defense-damage-and-healing-with-recorded-draws.md),
[turn protocol](../docs/07-turn-protocol.md#p113-attacks-guard-and-items)).

- `org.rpgengine.combat`: `CombatMath` (pure attack, Guard, damage and healing arithmetic), `CombatAttack` (one frozen
  attack), `EncounterGear` (what a worn item adds at entry) and `EncounterState.Sheet` (frozen fighting values read back
  from the participant's frozen form). `TacticalRules.attackBlock` and `defense` decide reach, line of sight, cover and
  the effective defense. `CombatScheduler` resolves `STRIKE`, `GUARD`, the opening strike at its slot and the interim
  non-player tactic; `CombatScheduler.useItem` charges an item whose effects the commands layer resolved.
- `TacticalPlan` carries `playerHealthDelta` and `ammunition`, which `LocalActionResolver` turns into the commit's resource
  and item effects; `Step` carries `openingStatus` and `ParticipantUpdate` carries status, local Health and the stance.
  `Note.internal` marks the calculation event. `MAX_STEPS` is now 128.
- `TypedCommand` adds `STRIKE`, `GUARD` and `COMBAT_USE_ITEM`, the only tactical intention with `needsCatalog` true.
  `EffectProgram.Heal` runs the `heal` effect in exploration and in a fight. `CommandRules.combatItem` checks the
  contract, its target policy and its draw bound (at most 64 dice); new refusals are `ATTACK_UNAVAILABLE` and
  `GUARD_UNAVAILABLE`. `CommandReceipts` allowlists standings, strikes and options, never another participant's defense,
  armor or Health. `EventStatements` states the five new public event types in English and Italian.
- `PackCompiler` validates item `combat` and participant `attacks`; `CombatRules.gear` projects item data, and
  `FrozenEncounter.freeze` applies the caps. V24 adds `guarding` and the `encounter_participant_vitals` trigger; the backup
  tool covers schema 24.

Tests: `CombatMathTest` (exhaustive), `CombatStrikeTest` (pure: the worked fight of docs/20, draw order, targeting, Guard,
opening slots, the tactic, seeded fights, the largest roster), `StrikeCommandsTest` (real PostgreSQL on the `EncounterPack`
fixture, whose sword, cloak, caster, salve and bandage and `yard-duel`, `close-brawl` and `close-rush` templates serve this
task), the extended long fight of `TacticalCommandsTest`, `CombatPackTest`, additions to `TypedCommandTest`,
`EventStatementsTest` and `CampaignCommandHttpTest`, and the V24 step of `CanonicalMigrationUpgradeTest`.

Limits at that task: Brace, conditions' modifiers, absorption and abilities in a fight arrived with P11.4, below; Withdraw waits for P11.5; no terminal predicate runs, so a fight in which the player fell waits at a round end and a
won fight runs on until P11.5; the interim tactic pursues no objective (P12.3/P12.4); no frontend control or panel (P12);
no live V24 dump and restore rehearsal.

## Reactions, effects and abilities in a fight (P11.4)

`org.rpgengine.combat` gains `CombatEffect` (a frozen effect definition), `CombatEffects` (pure stacking, order, modifiers and
clocks), `CombatReaction` with its `Policy`, and `CombatAbility`. `CombatScheduler` runs reactions at depth one inside the
provoking action, fires effect ticks at their phases and resolves abilities; `TacticalRules` adds prevention, ability legality and
policy changes; `FrozenEncounter` freezes what the fight can reach. Commands `COMBAT_USE_ABILITY` and `SET_REACTION_POLICY` read
only the frozen encounter. V25 stores the participant's `effects`, `reaction_policies` and `ability_uses` and the encounter's
`effects_applied`. Pack data: a condition's or ability's `combat` block, a template participant's `brace`, `reactions` and
`conditions`, an attack's `onHit` ([ADR 0031](../docs/adr/0031-predeclared-reactions-effect-phases-and-cooldowns.md)).

Limits: items cannot apply or remove effects and stay single-target; no forced movement; other participants use no abilities until
P12.3/P12.4; remaining effects are not converted at settlement yet (P11.5); no frontend control (P12).

## Terminal predicates (P11.5)

`EncounterOutcome.decide` is a pure function of `EncounterState`, which now carries the frozen objectives: defeat, then a met
reach-zone, survive or protect objective, then no active hostile, then an escape zone
([ADR 0032](../docs/adr/0032-terminal-predicates-from-committed-state.md)). `CombatScheduler` applies it from `RESOLVING`,
`ACTOR_START` and `ROUND_END`, stores `OUTCOME_PENDING` and writes `ENCOUNTER_OUTCOME_PENDING`; `CommandReceipts` allowlists
`combat.outcome`. No migration was needed. Tests: `EncounterOutcomeTest` (pure) and `OutcomeCommandsTest` (PostgreSQL).

Limits at that package: the stalemate policy, Withdraw and settlement were not implemented (the first two came with P11.5-C.2
and settlement, which also adds an unfinished round's time, with P11.5-C.3, below), so nothing left `OUTCOME_PENDING` then.

## Negotiation, surrender and agreement (P11.5)

`Negotiation` holds a social action's declared `Check` and `Terms`, the stored `Offer` and `Agreement`, and the pure
`respond` over frozen data and a recorded roll ([ADR 0033](../docs/adr/0033-negotiation-explicit-answers-and-surrender.md)).
`CombatScheduler` draws the check, stores at most one offer, lapses it at any action other than speech or a policy change, and
applies terms only on `TacticalIntent.AnswerOffer` from the command `ANSWER_OFFER` {offerId, decision}. `EncounterOutcome` adds
`PLAYER_SURRENDER`, `HOSTILE_SURRENDER` and `AGREEMENT`. V30 stores `open_offer` and `agreement` on the encounter row and guards
them, and refuses bringing a surrendered participant back; the backup tool covers schema 30. Pack data: `check`, `terms` and
`counteroffer` on a social action. Refusal: `OFFER_UNAVAILABLE` (`COMMAND_OFFER_UNAVAILABLE`, HTTP 422). Tests: `NegotiationTest`
(pure), `NegotiationCommandsTest` (PostgreSQL) and `CampaignCommandHttpTest.aNegotiatedEndingAnswersAndReplays` (HTTP).

Limits at that package: nothing left `OUTCOME_PENDING` until settlement came with P11.5-C.3, below; terms cost points only and cannot transfer items; a check on any
intent but `negotiate` records its result and changes nothing else; morale, knowledge and objective policies and a recipient
proposing terms by itself are not modelled (P12.3/P12.4); no generated reply, preview, confirmation or panel yet (P12).

## Ready/equip and Assess (P11.5)

`READY_ITEM` {itemInstanceId, change} and `ASSESS` {targetId} are tactical commands
([ADR 0034](../docs/adr/0034-ready-equip-and-assess-in-a-fight.md)). `TacticalRules` checks the turn, prevention, the Assess target
(`canAssess`) and `readyCost`/`assessCost`; `CommandRules` then applies the exploration equipment rules to a readied item and refuses
a weapon whose hit applies an effect the fight did not freeze. `LocalActionResolver` computes the player's new Guard, armor and
attacks with `ReadiedGear.of` (the same `EncounterGear.worn` arithmetic as entry), and `CombatScheduler.ready` charges the action
and takes them over; the equipment change is the commit's item effect, written only when the scheduler readied the item.
`CombatScheduler` resolves `ASSESS` as a recorded `2d10 + insight` check against the target's effective Resistance. V31 adds
`encounter_participant.readied`, which `EncounterWriter.state` reads in place of the frozen values; the backup tool covers schema 31.
`READY_ITEM` is in the catalog preload set of `CommandRuleCatalogs`, like `COMBAT_USE_ITEM`. Tests: `ReadyAssessTest` (pure) and
`ReadyAssessCommandsTest` (PostgreSQL).

Limits: one item per action and no swap; non-player participants neither ready nor assess (P12.3/P12.4); revealed values are kept
only in the event and that receipt; Withdraw and the stalemate policy came with P11.5-C.2 and settlement with P11.5-C.3, below.

## Withdraw and the round cap (P11.5)

`WITHDRAW` {zoneId} is a tactical command ([ADR 0035](../docs/adr/0035-withdraw-and-the-round-cap.md)).
`TacticalRules.withdrawCost` accepts a legal move whose destination is strictly closer, in action points over the current passages,
to an escape zone, priced `withdrawCost` or the edge's Move price when that is higher; `TacticalRules.options` lists `withdraw`.
`CombatScheduler` moves the player like a move but considers no `leavesZone` reaction and writes `PARTICIPANT_WITHDREW`.
`EncounterOutcome.decide` ends the predicate order with the round cap: after `roundCap` completed rounds it returns `STALEMATE` or,
under the template's `Disengagement.ESCAPE`, `ESCAPE`, both marked `roundCap`. The policy is pack data (`disengagement` on an
encounter, compiled by `PackCompiler`, projected by `CombatRules`), frozen into the definition by `FrozenEncounter` only when not
the default and read back by `EncounterWriter.state`; no migration. `CommandReceipts` allowlists `options.withdraw` and
`combat.outcome.roundCap`. Tests: `WithdrawStalemateTest` (pure) and `WithdrawCommandsTest` (PostgreSQL).

Limits: non-player participants never withdraw (P12.3/P12.4); settlement and the return to narrative came with P11.5-C.3, below;
an encounter stored at its round end at the cap before this change cannot move.

## Settlement and the return to narrative (P11.5)

`SETTLE_ENCOUNTER` {encounterId} is the last tactical command
([ADR 0036](../docs/adr/0036-explicit-settlement-and-the-return-to-narrative.md)). `TacticalRules` accepts `TacticalIntent.Settle`
only at `OUTCOME_PENDING` for that encounter; `CommandRules` adds the clock check when the fight ended inside a round.
`CombatScheduler` decides the verdict again with `EncounterOutcome.decide`, adds that round's `roundSeconds` once, ends every effect
and Guard stance, writes `SETTLED` and `NARRATIVE_RESUME` and the public `ENCOUNTER_SETTLED`; `LocalActionResolver` returns the
campaign to `EXPLORATION` for a plan ending at `NARRATIVE_RESUME`. `ResolvedCommit` accepts tactical progress in exploration mode only
for such a plan and requires it there, and `CanonicalCommitService` refuses any plan leaving encounter mode without one.
`CommandReceipts` allowlists `combat.settlement` {seconds, effectsEnded}; `EventStatements` states the new event. No migration: the
V22 arrows allow the settlement once. Tests: `SettlementTest` (pure), the settlement checks in the seeded fights of
`EncounterOutcomeTest`, and `SettlementCommandsTest` (PostgreSQL). Every deliberate weakening of P11.5 that still applies, including
the catalogues of its packages A and B, fails a test; the roadmap entry lists them.

Limits: no reward, loot, recovery rule, effect conversion or escape destination exists yet (P13); a fight that ended inside a round
cannot settle within one round of the clock's end; no frontend control sends the command yet (P12).

## Checkpoints of a fight in progress (P11.6-A)

Migration V32 replaces `memory_operational_snapshot`, so that the `encounter` member of an operational checkpoint holds the
campaign's active encounter instead of null
([ADR 0037](../docs/adr/0037-the-operational-checkpoint-records-the-active-encounter.md)). It records the phase, round, actor cursor,
action points, speech count, opening status, passages, application counter, offer and truce, the frozen hashes, and `roundSeconds`
with `roundEndsAt`: the world time at which the round in progress ends, set only while the cursor is set. Per participant, in roster
order, it records the zone, standing, local Health and Focus, initiative and rank, reactions, stance, effect instances, reaction
policies, ability uses and readied values. `OperationalCheckpoints.SNAPSHOT_SCHEMA_VERSION` is 2, and `capture` no longer takes a
checkpoint of another layout for the existing one, so a version 1 checkpoint verifies `UNSUPPORTED` and restart reconciliation
replaces it without invalidating summaries. No command, rule, receipt or event changes. Tests: `CombatRecoveryTest` (PostgreSQL: the
recorded cursors; a simulated restore inside a round, with a strike cut off before its commit, after which the fight continues from
the same points, round, clocks and draws; renewals around every round end of a poisoned fight; a fight decided inside a round settled
once at its recorded boundary after a restore) and the V32 step of `CanonicalMigrationUpgradeTest`.

Limits: combat-dialogue operations and transcripts were not covered by this story (see P11.6-B below); the restore in the test
runs the backup tool's retirement statement and the startup passes on the same database, not a dump and reload.

## Combat dialogue recovery and the resume card's encounter (P11.6-B)

`ResumeCards` now fills the card's `encounter` member with `ResumeCards.Encounter`, read in the card's snapshot through
`EncounterWriter.active` and `EncounterWriter.state`
([ADR 0038](../docs/adr/0038-combat-dialogue-recovery-and-the-resume-card-encounter.md)): encounter and template identity, phase,
round, the acting participant with its points (null once the fight has a verdict), `playerDecision`, `speechRemaining`, the
`offer` waiting for `ANSWER_OFFER`, and at `OUTCOME_PENDING` the `outcome` decided again from stored state by `EncounterOutcome.decide`.
Nothing the receipt hides is included. An active encounter that cannot be read back makes the card fail closed with 503.
No migration, command, rule, receipt or event changes, and no recovery state is added: combat dialogue recovers through the
existing replays, shared draw streams and journal recovery. Tests: two more `CombatRecoveryTest` cases on `parley-ground`
(PostgreSQL): a social check cut off by a crash that leaves its operation `VALIDATING` (an injected trigger refuses the commit and
the failure bookkeeping), recovered by a simulated restore, renewed, reconnected and asked again with its reserved dice; and an
accepted counteroffer settled once through two restores, renewals and every reconnect read. `MemoryContinuityTest` still
checks the null member outside a fight.

Limits: the frontend does not read the member yet (P12.1); renewals are prepared through `SessionRehydration`, not a running
runtime session; the restore is simulated on the same database.

## Knowledge-scoped tactic candidates and the tactic contract (P12.3-A)

`combat/TacticCandidates.menu(state)` is a pure function of an `EncounterState` at a standing non-player participant's
`AWAITING_ACTION`, for an opponent or a companion. It returns the participant's own values and the public standing of each
participant it can see (same zone, or a zone that is not obscured). It also returns the legal candidates, in a fixed order:
`move` (destinations one edge away), `strike:<attackId>` (reachable observed opponents), `guard` and `end-activation`.
`Menu.admits(intent)` revalidates a choice against a menu rebuilt from the committed state.

`inference/contract/TacticContract` (kind `tactic`, role `tactician`, schema `ai-contract/1/tactic.schema.json`) asks the
narration runtime for one of those candidates. Each task runs in the acting character's own session
(`character:actor:<uuid>` or `character:combatant:<encounter>:<rosterId>`). To make that possible, `TaskContract` gained
`audience(context)`, which `ContractRunner` and the request envelope use, and `TaskKind` gained `scope(binding, audience)`.

Rules and codes are in [docs/21 section 15b](../docs/21-ai-task-contracts-and-prompts.md#15b-implemented-tactic-choice-contract-p123-a).

Tests:

- `TacticCandidatesTest` (5), over the `TacticStates` fixture.
- `TacticContractTest` (8): seeded secrets absent from the rendered request, each candidate kind, one rejection per code, and
  a scripted runtime through `ContractRunner` with one repair and a refused leaking context.
- The tactic case of `ContractSchemaTest`.

Limits: nothing calls the contract yet, and `CombatScheduler`'s fixed tactic still decides every non-player activation.
Commit revalidation and the player notice in play are later P12 work. No migration, route or command changed.

### Tactic fallback and interruption notice (P12.4-A)

`inference/contract/TacticFallback.decide(outcome, committedMenu)` is pure. It returns `Proposed` for an accepted choice that
the menu rebuilt from the committed state still admits, and otherwise `Fallback` with a cause (`TACTIC_INVALID`,
`PROVIDER_UNAVAILABLE` or `INTERRUPTED`), a fixed detail code and a public `Notice` in the binding's language (English or
Italian). A fallback means the scheduler's fixed tactic plays the activation, which depends on the committed state alone. The
table of outcomes is in [docs/21 section 15c](../docs/21-ai-task-contracts-and-prompts.md#15c-implemented-tactic-fallback-and-interruption-notice-p124-a).

Test: `TacticFallbackTest` (7), through the scripted runtime and the real `ContractRunner`, then `CombatScheduler.apply`.

Limits: no activation calls the decision, so no notice is stored, sent or shown. No migration, route or command changed.

### Knowledge-scoped combat replies (P12.4-B)

`inference/contract/CombatReplyContract` (kind `combat-reply`, new player-facing role `character-voice`, schema
`ai-contract/1/combat-reply.schema.json`) asks the narration runtime for one addressed non-player participant's in-character
reply: `speech` and/or `narration` (a visible reaction), each at most 300 characters and 450 together, citing the heard words,
every outcome statement and any beliefs it relies on. `CombatReplyContext.of(binding, state, speakerId, ...)` reads the
speaker's knowledge from the committed encounter (its own standing and the participants it can see) and adds to the guard the
roster ID of every participant hidden from it and every other participant's campaign actor. Each task runs in the speaker's
own `character:<subject>` session, apart from the narrator and from the same character's tactician session.

`CombatReplyFallback.decide(outcome, committedRevision)` is pure: `Shown` for an accepted reply still at its revision,
otherwise `Silent` with a cause (`REPLY_INVALID`, `PROVIDER_UNAVAILABLE`, `INTERRUPTED`, `STALE`) and a log-only detail code. It
takes no encounter and writes nothing, so a failed reply leaves the committed fight and the player's legal actions unchanged.
Rules and codes are in [docs/21 section 15d](../docs/21-ai-task-contracts-and-prompts.md#15d-implemented-knowledge-scoped-combat-replies-p124-b);
the role decision is [ADR 0039](../docs/adr/0039-a-character-voice-role-for-combat-replies.md).

Tests: `CombatReplyContractTest` (6), `CombatReplyFallbackTest` (4), the combat-reply case of `ContractSchemaTest`, and the
new role in `SessionScopeTest`'s role matrix.

Limits: no command or presentation job calls the contract or the decision yet, so no reply is generated, stored, sent or shown.
No migration, route or command changed.

## Capability snapshot and threat limits (P13.1-A)

Three pure classes in `org.rpgengine.combat` hold the inputs and limits of encounter planning
([ADR 0040](../docs/adr/0040-capability-snapshot-and-a-versioned-threat-heuristic.md),
[encounters sections 2 and 4](../docs/12-encounters-and-progression.md#4-initial-threat-heuristic)):

- `CapabilitySnapshot` is the party's hashed capabilities before entry. Each actor carries an explicit level of 1–20, Health,
  relied resources, worn equipment, conditions, learned abilities, skill ranks and ammunition. The canonical text does not
  depend on input order, and `hash()` is its SHA-256. `Actor.player(facts, profile, level, ammunition)` reads the same
  `FrozenEncounter.PlayerFacts` that entry freezes.
- `EncounterDifficulty` holds the four bands (60–80, 90–110, 120–140 and 150–170 percent of capacity).
- `ThreatBudget.of(snapshot)` derives the capacity (exact rationals, rounded down once), the band bounds and the hostile-body
  limit. `cost(tier, level)` is the catalog cost with level scaling. `check(roster)` returns a `Verdict` with every `Finding`
  in a fixed order.

The heuristic is a fixed engine rule, `threat-heuristic/1`, and not `CombatProfile` data, so stored encounter profiles still
read back.

Test: `ThreatBudgetTest` (10).

Limits: nothing reads a campaign into a snapshot or calls the check, so no encounter is planned or refused by it. There are no
catalog statistic bands per tier, control, area or burst caps, or reinforcement phases (objective reachability and hard counters
followed in P13.3-A, below). No migration, route or command changed.

### Template-bounded encounter proposals (P13.2-A)

`combat/EncounterOption` is one eligible authored template with the level its hostile participants are costed at. It lists the
starting zones (every zone except the escape zones), the hostile limit (the lower of the budget's and the template's own), and
composes a variant that keeps only the placed hostile participants in their placed zones, in authored order, with everything
else unchanged. `EncounterOption.fallback(options, budget)` returns the first option whose authored roster passes the threat
check, or null.

`inference/contract/EncounterContract` (kind `encounter`, role `encounter-proposer`, privileged audience, schema
`ai-contract/1/encounter.schema.json`) asks the narration runtime to choose one offered template, a subset of its hostile
participants and a starting zone for each, echoing the snapshot hash. `EncounterContext` holds the binding, the
`CapabilitySnapshot` and 1–8 options at the snapshot's location. `EncounterFallback.decide(outcome, current)` is pure: it keeps
an accepted composition while the context read again before entry offers the same option at the same snapshot hash, and
otherwise returns `Fallback` with the first fitting authored template or `NoEncounter`. Rules and codes are in
[docs/21 section 15e](../docs/21-ai-task-contracts-and-prompts.md#15e-implemented-template-bounded-encounter-proposals-p132-a);
the decision is [ADR 0041](../docs/adr/0041-template-bounded-encounter-compositions-and-an-authored-fallback.md).

Tests: `EncounterOptionTest` (2), `EncounterContractTest` (9, through the scripted runtime and the real `ContractRunner`), the
encounter case of `ContractSchemaTest`, and the shared `combat/PlanningFixtures`.

Limits: no planning path calls the contract or the decision, so no encounter is proposed, entered or stored this way. No
migration, route or command changed.

### Frozen admission and feasibility of planned encounters (P13.3-A)

`combat/EncounterFeasibility.check(frozen)` judges a `FrozenEncounter` for the player entering it and returns every
`Finding` in a fixed order: objectives in declared order, then hostile participants in roster order.

- `UNREACHABLE_OBJECTIVE`: a reach-zone or escape objective outside the player's walk (`no-path`), a survive objective longer
  than the round cap (`round-cap`), or a defeat-hostiles or protect objective while a hostile is countered (`countered`). The
  walk follows unblocked edges and passages a declared scene action opens; a zone the other participants already fill to
  capacity at entry is closed to the player, because a defeated participant keeps its place.
- `HARD_COUNTER`: while an objective needs hostiles out of the fight, one that no attack, ability or surrender negotiation of
  the player can ever harm (`unharmable`) or reach wherever it walks (`out-of-reach`), each judged on its best case; and,
  under any objective, one whose attack or reaction can stop the player's actions for the rest of the fight (`action-lock`).

`inference/contract/EncounterAdmission.admit(decision, current, entry)` freezes the decided encounter once with the entry's
identity, profiles, player facts and combat forms, exactly as entry freezes, and returns `Admitted` only without a finding.
An infeasible proposal falls back to the first offered authored template that fits the budget and is feasible, or to
`NoEncounter`. `unchanged(admitted, current, entry)` says whether the admitted definition still matches the committed state.
The decision is [ADR 0043](../docs/adr/0043-planned-encounters-are-frozen-once-and-admitted-only-when-feasible.md).

Tests: `EncounterFeasibilityTest` (6) and `EncounterAdmissionTest` (3).

Limits: nothing calls the admission, so no planned encounter is entered or stored. `ENGAGE` and the offers of authored
templates are not checked. Reinforcement phases do not exist in pack data, so no phase is validated. No migration, route or
command changed.

### Challenge rewards and loot ownership (P13.4-A)

`progression/ChallengeRewards.plan(recipient, settled, claims)` takes the recipient's settled source keys and a list of
endings, `Fight` (frozen encounter ID, declared `Source`, verdict) or `Quest` (ID, outcome, completed, optional shared
`Source`, milestone XP and items). It returns each owed `Settlement` once with its canonical ledger form, and a `Skip` for
every part that pays nothing.

- A challenge pays its XP (minor 10, standard 20, major 35) and reward-table items under `challenge:<challengeSourceId>`.
  Only the first claim that earns it pays: a fight whose verdict is in the source's policy (default victory, hostile
  surrender, objective, agreement) or a completed quest naming it.
- Re-entered fights, alias templates, the quest after its fight and replays are `ALREADY_SETTLED`. A loss is `NOT_EARNED`
  and leaves the challenge open.
- A quest milestone settles under `quest:<questId>:outcome`, a distinct source.

`progression/EncounterLoot.open(encounterId, outcome, standings, holdings)` makes each holding one lot.

- Only a defeated hostile's lots are `AVAILABLE`, owned by nobody, and none opens after `DEFEAT` or `PLAYER_SURRENDER`.
- `Loot.claim(lotId, recipient)` moves a whole lot once (`Transfer`, source key `loot:<encounterId>:<lotId>`). It refuses
  `NOT_AVAILABLE`, `ALREADY_CLAIMED` and `UNKNOWN_LOT`.
- `Loot.units()` is unchanged by any claim.

The decision is [ADR 0044](../docs/adr/0044-challenge-keyed-rewards-and-claimed-loot.md).

Tests: `ChallengeRewardsTest` (5) and `EncounterLootTest` (4).

Limits: nothing calls either class. No pack declares a reward source or participant holdings, `SETTLE_ENCOUNTER` settles no
reward, quest endings still settle as in P10.3, and XP does not accumulate into levels. No migration, pack schema, route or
command changed.

### Levels, training, unlocks and pending purchases (P13.4-B)

`progression/Advancement` implements the default level-and-training profile of docs/12 section 9.

- `Advancement.threshold(L)` is `50 × L × (L - 1)`. `Profile.levelFor(xp)` is the highest level reached, capped by an
  optional maximum level (none by default).
- `Advancement.award(profile, standing, xp, health, focus)` adds settled XP (for example `ChallengeRewards.Plan.xp()`) to a
  `Standing` (XP, level, pending training and attribute points). It returns one `Gain` per newly crossed level: one training
  point, plus one attribute point on an even level. Health and Focus maxima rise by 2 and 1 per level, and their current
  values never change. A repeated or zero award grants nothing, and a standing whose level does not match its XP is refused.
- `Advancement.purchase(rules, standing, build, purchase)` spends pending points on one `SkillRank`, `AttributeIncrease` or
  `Unlock`. A rank costs its new value in training points, capped at `min(5, level + 1)` and the skill's `maxRank`. An
  attribute costs one attribute point, capped at 6 and the attribute's range. An unlock needs the ability's skill-rank and
  ability prerequisites and costs the profile's `abilityCost` (1 by default).
- A refusal (`UNKNOWN_DEFINITION`, `RANK_CAP`, `ATTRIBUTE_CAP`, `ALREADY_LEARNED`, `PREREQUISITE_MISSING`,
  `NOT_ENOUGH_TRAINING`, `NO_ATTRIBUTE_POINT`) spends nothing and returns the inputs unchanged.
- `Rules.of(profile, catalog, vocabulary)` reads the pinned skills, abilities and attribute ranges.

The decision is [ADR 0045](../docs/adr/0045-level-entitlements-recorded-once-and-spent-only-by-purchase.md).

Tests: `AdvancementTest` (4).

Limits: nothing calls it. No standing is stored, no award follows a settlement, purchases do not write `character_state` or
`learned_ability`, and no pack declares an advancement profile. No migration, pack schema, route or command changed.

### Advancement preview and comparison (P13.5-A)

`progression/AdvancementPreview` shows what advancement choices would do without committing any of them.

- `options(rules, standing, build)` lists one `Option` per attribute of the build, skill and ability of the rules (attributes,
  skills, abilities; sorted by key). Each carries the `Advancement.Outcome` (costs, or the refusal) and the `Change`s it
  would make (`Kind` ATTRIBUTE, SKILL or ABILITY, from and to). Options are independent single choices from the same start.
- `option(...)` previews one purchase. `path(rules, standing, build, purchases)` applies a sequence in order to a copy and
  stops at the first refusal: it reports how many were applied, the refusal, the end standing and build, the points spent and
  the changes from the stored build.
- `compare(rules, standing, build, left, right)` previews two sequences from the same start and returns both paths, the
  build differences (`from` is the left value, `to` the right one) and the pending points each leaves.
- A preview only calls `Advancement.purchase` on immutable inputs, so it never changes the standing or the build; committing
  stays a separate validated purchase.

Tests: `AdvancementPreviewTest` (4).

Limits: nothing calls it. Effects that depend on pack formulas (for example a pool maximum that follows an attribute) are not
previewed, because the engine reads no level-aware formula yet. No migration, pack schema, route, command or page changed.

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

## Local text backend and slot routing (P21.3-B)

`engine.inference.local.url` (unset by default; `http://models:8080` for the packaged service)
installs `org.rpgengine.inference.local`, and `SlotRoutingTaskPort` then sends each task to the
adapter of its slot's backend. Without the setting, one task port stays selected as before. The
other settings, the request shape, the result codes and the limits are in
[docs/34 section 12](../docs/34-inference-backends-and-model-selection.md#p213-b-local-text-adapter-and-per-slot-session-retirement).
Saving a changed slot assignment retires only that slot's sessions (`ScopedSessions.retireSlot`,
reason `SLOT_CHANGED`). Tests use `ScriptedModelServer`, a scripted HTTP fixture and not a model:
`LocalTaskAdapterTest`, `LocalAdapterContractTest` (real contract runner, scheduler and registry),
`SlotRoutingTaskPortTest`, `SlotSessionRetirementTest` and the wiring cases in
`TaskPortSelectionTest`.

`engine.inference.admission.enabled=true` (default `false`; it needs `engine.inference.local.url` and
`engine.inference.admission.gpu-budget-mib`) builds the admission coordinator and the lease executor over the local
server's control and runs them: a task on a local slot that holds the scheduler's permit loads its model through the
executor before its turn and releases it afterwards, and a chat request asks the server not to load anything (a refusal
ends `model-not-loaded`). With the switch off the scheduler behaves as before, and a chat request asks the packaged server,
which otherwise loads nothing for it, to load its model. The same
switch adds an `ImageAdmission` bean, which runs an image job on a lease as a stepped job and ends it with a fixed
failure code when it cannot run, so the caller shows catalog art. Only the owner-only probe route below calls it, and
without the bean a caller uses `ImageAdmission.direct()`, which calls the adapter. With the switch on, `engine.inference.image-worker.url` (unset by
default; `http://image-worker:8080` for the packaged service, which no Compose file sets) adds an image control and an
`ImageGenerationPort` over the packaged worker's routes. Without that address no image control is registered, so a batch
ends `LOAD_FAILED`; an `a1111-http` server never gets one, because it cannot release its checkpoint. An image batch does not
start within `engine.inference.admission.reading-window` (default one minute, 0 turns it off) after a player's text turn on a
local model ends. With the switch on, the owner can also send `POST /api/v1/models/admission-probe` (the same admission
rules as every `/api/v1/models/` route: session, loopback origin, CSRF token, JSON content type; no body is read). It
sends one fixed text task through the scheduler, in an isolated `tactician` line of the first campaign (lowest identifier),
and one fixed 512 x 512 image job through `ImageAdmission`, and answers only
`{"text": {"status", "code"}, "image": {"status", "code"}}`, where each `status` is an enum name or `UNAVAILABLE` and each
`code` is a fixed code or `none`. Each wait is bounded (a request still open is cancelled and reported `wait-expired`), a
second probe during one gets 409 `PROBE_BUSY`, and a non-owner gets 403 `LOCAL_ADMISSION_DENIED`. With the switch off the
route does not exist. The text task leaves one ledger row in the scheduler, and an install with no campaign answers
`no-campaign`; without an image port the image request answers `no-image-port`. None of this has run against a real server,
worker or GPU. The settings and limits are in
[docs/34 section 10](../docs/34-inference-backends-and-model-selection.md#p214-c121-opt-in-construction-of-the-lease-executor),
[the scheduler link](../docs/34-inference-backends-and-model-selection.md#p214-c1221-link-from-the-schedulers-text-permit-to-the-lease-executor),
[the image path](../docs/34-inference-backends-and-model-selection.md#p214-c1222-image-requests-through-the-lease-executor),
[the packaged worker](../docs/34-inference-backends-and-model-selection.md#p214-d-packaged-image-worker-behind-the-lease-executor),
[the reading window](../docs/34-inference-backends-and-model-selection.md#p214-e2-reading-window-as-expected-text-demand)
and [the probe route](../docs/34-inference-backends-and-model-selection.md#p214-f1-owner-only-probe-of-the-text-and-image-paths);
tests are `AdmissionConfigurationTest`, `AdmissionProbeControllerTest`, `JdbcProbeCampaignsTest` (the campaign lookup
against inserted rows on PostgreSQL),
`LocalControlConfigurationTest`, `SchedulerResidencyTest`,
`TextResidencyDriverTest`, `ReadingWindowTest`, `ImageAdmissionTest`, `WorkerModelControlTest`, `WorkerImageAdapterTest`,
`WorkerImageConfigurationTest`, `WorkerImageAdmissionTest` and the wiring cases in `TaskPortSelectionTest`.

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
  there, resources, the active encounter or null (filled since P11.6-B), the pending decision kind, up to 16 actions awaiting
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
