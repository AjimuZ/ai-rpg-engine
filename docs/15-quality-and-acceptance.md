# Quality Strategy and Acceptance Criteria


Partial P06.1 adds a creation acceptance corpus: pure allocation/minimum/budget/
loadout checks, real PostgreSQL atomic creation, ownership, same-ID genre isolation,
durable and concurrent retries, deferred COMMIT rollback, and populated V8-to-V9
upgrade without backfill. This is internal-service evidence, not full P06 public
campaign reload/consultation or browser acceptance.

## 1. Evidence policy

Partial P05.6 evidence (24 October 2026, Windows 11, Chrome 155): four resource
checker tests and 42 frontend tests pass, with strict typecheck, production and
separate EN/IT review builds and a zero-vulnerability dependency audit. All 218
unique messages across the local preview have reviewed Italian targets and exact
source/ID/placeholder parity. Browser checks cover localized titles, navigation
and form labels, keyboard focus, four viewport sizes, locale bases, same-origin
script/style/notice hashes and missing resource/route 404s. Bilingual story journeys
also cover one/many passage plurals, recovery guidance, media fallback, native
validation and escaped unsaved receipts while preserving literal input/selection.
Unit tests cover all preview recovery states and locale-formatted meter text with
invariant numeric ARIA values. The loopback test server is a review harness, not
Spring packaging evidence. Native EN→IT→EN keyboard journeys now verify persisted
language, two scoped literal drafts/selections, reading layout/size/position and
equivalent-control focus, plus unsupported saved locale and storage-denial notices.
Nine unit tests cover reload/storage boundaries, corrupt snapshots, recovery across
detachment, conflicting draft writes and old callback/reply invalidation. A browser
failure exposed restored text-size options selecting the first value; option-level
selection and a routed regression assertion correct that behavior. Operation/cursor
reconciliation, API errors/receipts and pack-source
fallback integration remain unimplemented;
QA-35 and independent bilingual playtests stay open.
The 24 October prerequisite audit reran 42 frontend tests, strict type checks
and four resource checker tests. It confirms that campaign/operation/stream HTTP
contracts remain proposed; see the [P05.6 integration prerequisites](29-localization.md#p056-integration-prerequisites-24-october-2026-audit).
These checks preserve the preview safety boundary and do not establish real
operation/cursor reconciliation or independent screen-reader acceptance.
All 22 repository-checker tests pass; docs/secrets/workflows/images scans have
zero findings. Desktop English and mobile Italian Settings screenshots, plus
desktop/mobile Italian story screenshots, were visually reviewed.

The fourth P05.6 checkpoint packages both variants into the jar/container. All 22
packaging/context/launcher checks pass with real recreation; the separate opt-in
launcher lifecycle test skips. The complete PostgreSQL-backed packaged backend
suite passes, including four HTTP tests for both locale trees, exact classpath
resource bytes, legal notices, deep links, POST rejection and missing-resource/API
404s. Chrome 155 runs bilingual story/forms/receipt/reload journeys against that
actual container, plus root preference/default/denied/unsupported storage, explicit
locale links and query/hash preservation without draft changes. Resource SHA-256
maps match before/after recreation. An initial root-artwork 404 exposed a missing
build-stage input; shared artwork now copies only from compiled English resources.
No runtime account or inference request is used, and all owned services stop.

P04.6 evidence (24 October 2026, Windows 11/Docker Desktop, pinned JDK 21 and
PostgreSQL 18.6): 45 focused checks pass (40 text-catalog cases, four genre-import
cases and one populated migration case). They cover optional/partial EN/IT
resources, Italian source fallback, stable nested IDs, closed schemas, unknown or
mechanical/audience targets, placeholders, proper names, explicit quote policies,
immutable glossary hints, deterministic coverage, translation-only hash conflicts,
sealed reload and rollback. Both neutral fixtures have reviewed EN/IT resources
for every approved catalog leaf (24 fantasy, 26 salvage), retaining one verbatim
English motto as the Italian fallback. Source-only prose is explicitly excluded.
Full backend `verify` passes 262 tests with three packaged-interface profile skips
(265 total) and builds the jar. V8 permits new version-2 reports while preserving
populated V1→V8 canonical data and old seals exactly. The backup allowlist now
requires V8; eight offline tests pass with one Windows symlink-privilege skip.
A real disposable V8 dump/restore preserves both fixture inventories, raw hashes,
exports, metadata and sealed report versions 1/2 without changing the source.
These are compiler/resource-review and recovery-fixture results toward QA-02 and
the pack portion of QA-35; they do not establish UI coverage, independent human
bilingual playtests, runtime narration quality, public projection authorization,
full QA-23 or full QA-35. All 22 repository-checker tests pass and docs/secrets/workflows/images have zero
findings. Jar inspection confirms the four trusted schemas and V8 are packaged,
with test inventories excluded. No new dependency or toolchain version was introduced.

P04.5 evidence (24 October 2026, Windows 11/Docker Desktop, pinned JDK 21 and
PostgreSQL 18.6): `NeutralGenreImportTest` passes both cases using standalone
fantasy exploration and science-fiction salvage resource directories, without
production source edits or replacement engine contracts. The tests cover read-only
preview, directory/ZIP equivalence, idempotency, exact sealed byte/hash/report
preservation, recompile, distinct vocabulary/mechanics/assets despite shared local
IDs, independent adventure imports and located invalid-reference rejection with
no partial rows. This supplies two-pack import evidence toward QA-01 and QA-02.
Both fixtures disable optional modules; gameplay, distinct module behavior,
reviewed bilingual resources and full QA-33 isolation remain later gates.
Full backend `verify` passes 220 tests, skips three separate packaging-profile
tests (223 total), and builds the jar. All 22 repository-checker tests pass;
docs/secrets/workflows/images checks report zero findings.

P04.4 evidence (24 October 2026, Windows 11/Docker Desktop, pinned JDK 21 and
PostgreSQL 18.6): all 23 focused staging/registration tests pass. Cases cover
paths/devices/case aliases, links and special entries, conflicting archive views,
descriptors/CRC/truncation/amplification, media types/dimensions, immutable copies,
preview/idempotency/conflicts, adventure-scoped dependencies, reload tampering,
database sealing, concurrent retries/direct insertion and transactional rollback.
Full backend `verify` passes 218 tests with three separate packaged-interface
profile skips (221 total), builds the jar, and verifies populated V1→V7 preservation.
Repository checker tests pass 22; docs/secrets/workflows/images report zero findings.
The first full run exposed a V6-latest assumption in the upgrade fixture; it was
extended to V7 and both the focused upgrade test and full suite then passed.
This supplies the hostile-import/immutable-registration portion of QA-02, not
playable two-genre or bilingual acceptance. Directory tests ran in Linux containers;
native Windows junction races, full media decoding, rights review and future
import/serving UI remain outside these results. See [ADR 0018](adr/0018-protected-pack-registration.md).
Final staged review adds payload-CRC, trailing-deflate and unsigned-descriptor
regressions that reach stream validation independently of header mismatches;
the focused staging rerun passes all 12 tests.

AI RPG Engine has developer runtime compatibility tests in [`tools/runtime_probe`](../tools/runtime_probe/README.md), covering pinned discovery and deterministic thread/turn lifecycles. P01.4 adds owned-process shutdown, terminal wire failures, unresolved-request isolation, bounded/redacted stderr and ordered early events, with synthetic failure injection and the actual pinned runtime. These runtime checks do not establish gameplay/domain behavior or full QA-24 save coherence. The scripted upstream proves protocol behavior without an account and cannot establish live inference quality or account readiness. The separate owner-signed-in neutral smoke passed on 23 October 2026, validating correlated output and resume from a second stdio process; its sanitized report is retained with the probe, closing P01.2. These checks do not satisfy campaign renewal (QA-08) or packaged application startup (QA-28). P01.5 retains [neutral projections and a supported-version matrix](../tools/runtime_probe/COMPATIBILITY.md) for offline replay; replay alone cannot qualify a new runtime or platform. Predecessor test reports are references, not evidence that AI RPG Engine works. Every release claim must identify a fresh result, environment, scope, and limitation.

P02.3 adds Docker Desktop Linux-container evidence for the packaged application
shell: all five packaging checks pass, including actual HTTP delivery of the
Angular entry point, hashed assets and notices, nine backend tests against
PostgreSQL, missing-secret rejection, network/mount/identity isolation and
durable markers across three-service recreation. The 116 runtime checks also
pass without account access. This verifies packaging, not launcher, sign-in,
campaign recovery or full QA-28 acceptance. P02.7 adds an opt-in launcher lifecycle check (Windows 11, Docker Desktop, Git Bash): idempotent launch, graceful stop, interruption exit 130, restart and image-recreation preservation of neutral markers. It is partial QA-28 evidence only: no real campaign or combat data, no interactive Ctrl+C, no Linux/macOS host and no sign-in. Native Linux bind-secret ownership
and the Java release refresh remain separate gates; see the
[packaging checks](../tools/container_packaging/README.md).

P02.4 adds ordinary CI without runtime credentials (`.github/workflows/ci.yml`):
repository checks (secret/private-path scan, Markdown links and fences, workflow
policy, image pins), the account-free runtime probe suite, the packaging
build-context/Compose suite, the frontend typecheck/test/build/audit and the
backend `verify` against a job-local PostgreSQL. The checkers have 22 unit tests
with negative cases; see the [repository checks](../tools/repository_checks/README.md).
The opt-in Docker recreation and live-account checks are not run in CI, and the
workflow has not yet executed on GitHub. The backend job was replayed locally in JDK 21
and PostgreSQL 18 containers; backend tests that read the secret-file contract must not
depend on exported `SPRING_DATASOURCE_*` variables, which CI sets for the database tests.

P03.2 adds persistence evidence on 24 October 2026 (Windows 11, Docker Desktop,
pinned JDK 21 and PostgreSQL 18.6). Backend `verify` passes 43 tests; three
packaged-interface tests skip outside their separate profile. Coverage includes
campaign/adventure foreign keys, positive and negative scoped repository reads,
frozen ownership/provenance, explicit optimistic versions, concurrent stale-writer
rollback, simultaneous duplicate operation/reward insertion, resource and JSONB
byte boundaries, and a populated V1-to-V2 migration with unchanged domain counters.
The repository checker passes 22 unit tests and all policy checks; the default
packaging suite passes 20 checks with two opt-in lifecycle/recreation checks skipped.
This is storage-level evidence toward QA-03/QA-04, QA-10 and QA-20; no command API,
inventory gameplay, owner authorization, full QA-20/QA-33 acceptance, atomic outbox,
draw reuse or recovery is claimed by that checkpoint.

P03.3 adds internal atomic-commit evidence on 24 October 2026 in the same pinned
Windows 11/Docker Desktop/JDK 21/PostgreSQL 18.6 environment. Full backend `verify`
passes 72 tests, with three packaged-interface tests skipped outside their profile.
Its 27 commit integration cases cover state, events, reward settlements, journal
receipt and outbox rollback at each write boundary and at deferred commit; exact
receipt replay after later state changes; simultaneous duplicate and competing
operations; stale revision/content/resource versions; cross-scope references; and
an intervening resource writer after inserts flush. Tests reject ambient transactions,
check the service's database-only dependency surface and immutable input bounds,
and verify populated V3 upgrade without fabricated historical outbox messages.
Repository-checker tests pass 22/22, default packaging checks pass 20/20 with two
opt-in checks skipped, and repository policy checks report no findings. This adds
transaction-level QA-20 and scope-level QA-33 evidence; full acceptance still needs
command/API authorization, draw reuse, lease/recovery and delivery work. No live
inference, gameplay or hosted-CI result is claimed for this checkpoint.

P03.4 evidence (24 October 2026, Windows 11/Docker Desktop, pinned JDK 21 and
PostgreSQL 18.6): full offline backend `verify` passes 81 tests, with three
packaged-interface profile skips, and builds the executable jar. Seven added
PostgreSQL cases verify concurrent draw reuse, unchanged world/journal state,
durability across failed canonical commit and a fresh service instance, allocation
rollback including lazy seed creation, cancelled reuse/new-allocation rejection,
128-draw receipt capacity and SQL scope/bounds/identity guards. Two pure protocol
tests pin independently computed HMAC vectors, rejection sampling and invalid inputs.
Populated V1-to-V4 upgrade preserves outcomes/counters without invented seed/draw rows.
Repository checks pass 22 unit tests and zero policy findings; default packaging
passes 20 tests with two opt-in skips. This adds the same-operation draw-retention
part of QA-17; startup reconciliation, backup/restore, lease/fencing, equivalent-new-
attempt reroll policy and full QA-17/QA-20/QA-33 remain later gates. No live runtime,
gameplay or hosted-CI claim is made.

P03.5 (24 October 2026): the full backend verify passes 95 tests, with three
packaged-interface profile skips, on PostgreSQL 18.6. The checks cover exclusive,
monotonic and concurrent lease grants; expiry fencing of late commits and new draws
while replay is kept; database lease guards and bounds; recovery of each precommit
status with no repeated effects; preservation of live leases; pagination past one
page; a restart that keeps a committed receipt with narration pending; recovery
failure that aborts startup; and a populated V4-to-V5 upgrade. The backup tool
passes 9 offline unit tests (one symlink case is skipped on hosts without the
privilege), and a manual backup/restore rehearsal is recorded in
[security §8](14-security-and-operations.md#8-backups-and-restore). This is the
crash-before-commit foundation of QA-17 and a database-only precursor of QA-23, not
their full acceptance.

P03.6 evidence (24 October 2026): pinned JDK 21/PostgreSQL 18.6 full backend
`verify` passes 113 tests with three packaged-interface skips (116 total), including
17 preparation tests and the populated V1→V6 upgrade. Real transactions cover
concurrent enqueue/claim/admission, version conflicts, deferred admission rollback,
critical enqueue/outbox rollback and replay, scoped snapshot constraints, immutable
inputs, omitted-manifest rejection, query phantoms, unrelated revisions, concurrent
epoch invalidation, cancellation, finite retries/expiry, restart gating, generation
retirement and locale separation. Non-query semantics fail closed. A populated V6
backup/restore and jar-startup rehearsal preserves immutable bytes and canonical
counters while retiring old jobs/plans. Repository tests pass 22, backup tests pass
8 with one Windows symlink skip, packaging tests pass 20 with two opt-in skips.
These establish the enabled storage/outbox/admission portions of QA-36/37; they do
not complete semantic plan validation, fair scheduling, runtime cancellation,
player-facing fallback, author/secret checks or narrative-quality evaluation.

P04.2 evidence (24 October 2026): the 8-test `PackCompilerTest` suite compiles a valid
reference pack and rejects duplicate IDs, category/kind mismatch, unknown/wrong-kind/
undeclared references, unresolved dependencies, unknown rarity tiers, shared appearance
identities, creation budget and stack overflows, unknown attributes/resources, rank
overflow, stray or unknown effect fields, unknown fields and unsupported kinds. Full
Maven `verify` on pinned JDK 21/PostgreSQL 18.6 passes 184 tests with three skips (187 total).
Expression limits, cycles and version compatibility remain untested until P04.3.

P04.1 evidence (24 October 2026): the 63-case in-memory parsing corpus covers the
closed contract-1 manifest, YAML 1.2 core resolution, tags/anchors/aliases/merge
rejection, strict JSON, encoding, immutable values, field coordinates, resource
budgets, explicit categories and raw inventory hashes. Whole-value schema patterns
reject trailing line terminators; an independently computed Python digest vector
pins the portable framing. Full Maven `verify` on pinned JDK 21/PostgreSQL 18.6
passes 176 tests, skips three separate packaged-interface profile tests (179 total),
and builds the executable jar containing both schemas. Repository-checker tests pass
22/22 and all repository policy checks report zero findings. This establishes only
the parser/schema/hash portion of QA-02; semantic references, cycles, effects,
archive extraction, media and immutable admission were addressed in P04.2–P04.4.
Playable two-genre QA-01 remains beyond P04.5's import checks. The definition envelope deliberately
does not validate its `data` mechanics. No complete pack-import acceptance is claimed.

## 2. Verification layers

| Layer | Purpose | Proposed tools |
| --- | --- | --- |
| Domain unit/property tests | Invariants, formulas, turn/effect ordering, budgets, progression | JUnit and a suitable property-testing library |
| Persistence integration | PostgreSQL transactions, unique constraints, leases, recovery, migrations | Real PostgreSQL test instances; Testcontainers if suitable |
| Adapter contract tests | Framing, ordering, schema parsing, cancellation, EOF, compatibility | Recorded neutral fixtures and a controllable fake process |
| Application/API tests | Commands, projections, status, idempotency, import/export | Spring test infrastructure |
| Frontend tests | Revision handling, forms, panel state, duplicate delivery | Angular/Vitest and HTTP test support |
| Browser acceptance | Full interaction, responsive layout, focus, reconnect, shutdown | Playwright or equivalent browser runner |
| Real Codex smoke | Supported runtime/account/policy and a minimal neutral task | Opt-in, isolated context, separately reported |
| AI quality evaluations | Continuity, agency, scope, narration consistency, tactics | Fixed scenarios, graders, human review |
| Simulation and playtest | Balance and pacing | Seeded deterministic simulations plus actual play |

Do not require live inference for ordinary CI. Test fixtures must not contain private saves or credentials.

## 3. Mandatory scenario register

| ID | Scenario | Pass condition | Requirements |
| --- | --- | --- | --- |
| QA-01 | Two genres | Two packs with distinct vocabulary/modules run on the same build | FR-001–003, FR-028 |
| QA-02 | Invalid pack | Missing references, cycles, unknown effects, traversal, and oversized aliases fail precisely | FR-001, NFR-007 |
| QA-03 | Duplicate action | Same request retried before/after restart consumes one item and awards once | FR-006, FR-009, FR-023 |
| QA-04 | Changed duplicate | Same key with changed payload returns conflict | NFR-003 |
| QA-05 | Illegal AI proposal | Unknown ability, forged item, negative cost, and raw field patch cannot commit | FR-006 |
| QA-06 | Read-only inspection | Sheet/journal/guide reads leave revision, turn, time, events, and RNG unchanged | FR-008 |
| QA-07 | Player agency | Narration adds no unchosen travel, promise, attack, or resource use | FR-007 |
| QA-08 | Fresh thread resume | New role thread knows current pending decision without replaying old scene | FR-011–012 |
| QA-09 | Combat renewal | Mid-fight renewal preserves initiative, AP, reactions, effects, resources, RNG cursor and dialogue operation/transcript; reconnect cannot submit a draft or repeat a social effect | FR-012, FR-015 |
| QA-10 | Inventory coupling | Potion quantity and capped healing commit together; equipment cannot duplicate ownership | FR-009 |
| QA-11 | Combat arithmetic and dialogue | Miss costs, armor absorption, strong hits, cover and Guard/Brace stacking match rules; bounded speech spends no AP/time/RNG, consequential social/scene actions use declared costs/checks, ambiguous/unsupported/out-of-turn input changes nothing | FR-015 |
| QA-12 | Effect clocks | Start/end effects and durations tick exactly once through defeat and renewal | FR-015 |
| QA-13 | Terminal outcomes | Victory, defeat, escape, surrender, agreement and objective success settle once; test refused negotiation, a counteroffer, explicit player acceptance and lost-response retry of a negotiated ending | FR-015–017 |
| QA-14 | Dynamic encounter bounds | Invalid roster, unreachable objective, hidden hard counter, and over-budget phase rejected | FR-014 |
| QA-15 | Progression | Crossing multiple thresholds grants exact entitlements with no automatic refill | FR-017 |
| QA-16 | Knowledge isolation | Secret sentinels absent from player API, narrative, guide, media metadata, and UI diagnostics | FR-013 |
| QA-17 | Crash before commit | No world effects; operation recoverable and draw identity retained | FR-023 |
| QA-18 | Crash after commit | Effects preserved; missing narration resumes without repeating action | FR-023 |
| QA-19 | Delivery reconnect | Duplicate/out-of-order SSE cannot replay sounds or revert state | FR-022–023 |
| QA-20 | Cross-campaign references | Another campaign's entity/operation ID cannot be read or mutated | NFR-004 |
| QA-21 | NPC schedule | World time and witnesses determine presence/knowledge; inspections advance neither | FR-018–019 |
| QA-22 | Guide and creative abilities | Disabled modules absent; enabled guide cannot execute a draft or reveal secrets | FR-020–021 |
| QA-23 | Restore rehearsal | Full state, active combat, content hashes, assets, and pending operation recovered in fresh storage | FR-024 |
| QA-24 | Process shutdown | Only owned bridge exits; committed save remains coherent | FR-023, FR-025 |
| QA-25 | Accessibility | Keyboard-only creation/play/combat, focus return, text zoom, reduced motion, no audio dependency | FR-027 |
| QA-26 | Publication | Fresh clone, accurate status, rights inventory, no private data or dead local links | FR-028 |
| QA-27 | Canon correction | Concrete preview, backup, source-linked audit, dependency revalidation, and summary invalidation; historical events preserved | FR-026 |
| QA-28 | Containerized startup | Fresh clone starts through root `start.sh` without host build toolchains; prerequisites/auth failures are clear; repeated launch, stop, image recreation, and restore preserve campaigns and combat; secrets and runtime access remain isolated | FR-029, FR-024–025 |
| QA-29 | Visual-novel reading and quick replies | Dominant responsive scene, explicit narrator/NPC/player labels and free input; Continue/history are read-only; selecting/editing/dismissing suggestions preserves drafts and does not submit; stale/duplicate suggestions cannot replay actions; keyboard and no-image modes remain usable | FR-005, FR-007–008, FR-027 |
| QA-30 | Complete graphical UI kit | All required controls/bars/icons/frames and interaction/fallback states exist as separate usable assets/components, with live text/focus, accessible contrast/labels, responsive review and complete license/provenance inventory | FR-022, FR-027–028 |
| QA-31 | Persistent generated portraits and item art | A returning character and a unique sword preserve identity across shop/purchase/equip/reload/restore; equivalent items reuse icons; rarity remains validated data; stale/duplicate jobs, limits, unavailable capability and secret sentinels cannot corrupt state or block play | FR-022–024, FR-030–031 |
| QA-32 | Dynamic music and lawful credits | Explicit opt-in/autoplay handling, public-state transitions, stable loops/crossfades, stale/duplicate cue rejection, source/offline fallback, separated volume controls and complete per-recording versioned credits; no hidden-state leak or unreviewed URL | FR-022, FR-027–028, NFR-009, NFR-012 |
| QA-33 | Independent adventure workspaces | Two genres with same-name NPCs/items retain distinct specifications, drafts, inventories, memory and art folders; switching/stale callbacks cannot mix them; reboot/restore preserves scopes and owner-wide job limits | FR-001–003, FR-022–024, FR-032, NFR-004 |
| QA-34 | Author-fixed content and supported combat variants | Preserve a required named NPC/appearance and fixed facts in fantasy/cyberpunk fixtures; complete only allowed gaps; diagnose conflicts; validate a nondefault supported combat profile, reject unsupported primitives and preserve frozen active encounters | FR-001, FR-006–007, FR-015 |
| QA-35 | First-class English and Italian | Reviewed resource/placeholder parity and ICU/format tests; localized UI/combat/quests/errors/ARIA labels, direct selected-language narration, stable proper names/IDs, explicit source-pack fallback, persisted language and draft/cursor-safe reload; no gameplay change or stale-language result | FR-033, NFR-014 |
| QA-36 | Dependency-safe narrative plans | Revision100→108 unrelated changes retain valid branches; dead NPC/destroyed item/revealed secret/query phantom invalidates affected content; second transactional checks/fences/idempotency prevent stale admission; plan completion creates no canonical fact/time/reward | FR-006–007, FR-013, FR-034, NFR-015 |
| QA-37 | Fair bounded AI jobs and recovery | Durable priority/interactive reservation, scoped contexts, finite quotas/backoff/retries, cancellation-terminal reconciliation, worker/restart/duplicate/outbox tests and no cross-adventure callback; default concurrency1, any higher limit separately qualified | FR-023, FR-032, FR-034, NFR-013 |
| QA-38 | Optional bilingual speech and resource failure | Exact admitted code/weight/voice rights; EN/IT pronunciation/speaker stability, locale-aware cache/restore, text-first controls, explicit audio opt-in; no-GPU/OOM/missing voice/stale line/worker death safely fall back; measure real RTX4070/CPU behavior before enabling | FR-022, FR-036, NFR-009, NFR-014 |
| QA-39 | Bilingual narrative comparisons | Paired baseline/planner/planner+reviewer studies with source-grounded coherence/motivation/agency rubric, 100/500-turn continuity, quota/latency/usage/waste/replanning metrics and explicit unrun/disabled cells; human EN/IT review distinct from objective invariants | FR-007, FR-011–013, FR-033–034, NFR-008, NFR-013 |
| QA-40 | Safe presentation events | Allowlisted public scene/actor/expression/line/asset references only; no executable/raw-URL cues, no hidden-actor or language leak, deduplicated skip/reconnect, persistent visual/voice identities and fallback without world changes | FR-022, FR-030, FR-035–036, NFR-004, NFR-014 |
| QA-41 | Portable stories between owners | Export/import both genres and EN/IT overlays on a clean second installation; IDs/hashes/compatibility/dependencies/credits preserved; missing/unshareable assets handled, hostile ZIP rejected, conflicting revisions previewed; no credentials/private playthrough/live jobs or recipient-save overwrite | FR-001, FR-024, FR-028, FR-037, NFR-012 |

P05.1 shell acceptance passes 11 frontend form/routing/service tests, strict
typecheck and production build. Chrome 155 desktop/mobile smoke verifies both
neutral local previews, route navigation, keyboard Continue, revealed chronicle,
character-name preview, text-size selection and unknown-identity fallback without
page exceptions or nonlocal/action requests. Tests also cover trimmed-name bounds,
literal markup, scope clearing, independent bounded reading and missing-image
recovery. Both scene layouts were visually reviewed. These are shell checks only;
full QA-29 (reading/input), QA-30 (graphic kit), QA-33 (persisted isolation) and
QA-35 (bilingual UI) remain pending. Commands and scope are in the
[frontend guide](../frontend/README.md).

P05.2 extends local preview acceptance to 15 passing frontend tests, strict
typecheck/build and a zero-finding dependency audit. Regressions cover scoped
attribute/resource names, terminology, absent/blank fallback labels, literal markup,
empty metadata and optional-panel visibility/direct routes after adventure changes.
Chrome 155 desktop/mobile smoke additionally checks resource labels and enabled/
disabled panels with no page exceptions or nonlocal/action requests; three screenshots
were visually reviewed. All 22 repository tests and docs/secrets/workflows/images
scans pass. This verifies a typed local public projection contract, not registered
pack/campaign APIs, authored-pack module execution or full QA-29/30/33/35.

P05.3 passes 18 frontend tests, strict typecheck/build, zero-vulnerability audit
and 22 repository tests. Chrome 155 exercises Enter and Space on Continue and
Chronicle, visible focus and Tab after the final advancement, public portrait
identity/failure, scene failure, and navigation-preserved reading/layout choices.
Default desktop/mobile and text-mobile screenshots were visually reviewed.
No-artwork text mode passes 320px and landscape layouts at 24px with reduced
motion; no scene/portrait requests are initiated by that story view. No page
exceptions, nonlocal requests or action requests occur. This covers the local
reading portion of QA-29; playable drafts/suggestions, reconnect deduplication,
assistive-technology review and full QA-29/30/33/35 remain later gates.

P05.4 passes 26 frontend tests, strict typecheck/build, a zero-vulnerability
audit and all 22 repository tests. Service regressions cover revision ordering,
scoped callback epochs (including detach/return), suggestion identity and bounds,
recovery-state preservation, and unavailable/corrupt storage. Routed tests verify
literal rendering, editable suggestions and disabled submission. Chrome 155 on
desktop/mobile verifies keyboard selection/editing/dismissal, multiline input,
draft/selection restoration after reload, scope isolation, offline/reconnecting
states and explicit clearing. The mobile composer screenshot was visually reviewed;
no page exceptions, nonlocal requests or action requests occur. Repository
policy scans cover docs, secrets, workflows and images. This establishes the local
draft/projection portion of QA-29; actual campaign operation reconciliation,
reconnect deduplication, assistive-technology review and full QA-29/30/33/35 remain
later gates. Send remains disabled until P06/P07 provide campaign operations.

P05.5 establishes QA-30 for the local graphic kit: all 30 frontend tests, strict
typecheck, production build (345.91 kB), zero-vulnerability audit and 22 repository
tests pass. Original SVG/CSS geometry covers controls, 20 icons, finite/clamped
numeric resource bars, progress, dialogue/nameplate/portrait/item frames, empty
slots, independent sample item art, status symbols and persistent help disclosure.
Settings ships credits plus source/provenance inventory and complete MIT notice.
Chrome 155 verifies real keyboard and pointer states, disabled/busy/invalid
semantics, live meter labels, measured text/placeholder contrast >=4.5:1 and field boundary/
focus contrast >=3:1, notice delivery and forced-colour/increased-contrast/reduced-
motion rendering. Desktop/mobile full-page screenshots were visually reviewed;
320px/landscape checks have no horizontal overflow. Unknown/inherited icon keys,
literal labels, invalid numeric input and review-state isolation have regression
coverage. An existing browser timing race now waits for the zoneless draft guard
render before checking its unchanged disabled assertion. These checks do not
establish runtime campaign artwork persistence, Italian review, assistive-
technology acceptance or the remaining QA-29/31/33/35 gates.

Combat-chat acceptance for P11/P12 also includes a real-browser English/Italian
path with visible speaker/recipient, editable suggestions and explicit Send;
desktop/mobile keyboard access to both chat and tactics; draft preservation while
an NPC acts; no hidden submission at the next activation; public-only NPC replies;
and a supported scene interaction versus a rejected attempt to rewrite a resolved
event. Verify the per-activation speech limit, no automatic ally action, clarification
before compound actions or unknown costs, insufficient-AP rejection, provider
unavailability with usable tactical controls, and committed narration recovery.
Renew/reconnect during a social check and after an accepted agreement must reuse
the original operation/draws and settle once. These are planned checks, not results
claimed by the current preview shells.

## 4. Long-campaign evaluation

Run at least one **500-turn seeded campaign scenario** with 25 forced context renewals, at least 20 encounters, 50 recurring entities, 30 delayed promises/clues, multiple quest outcomes, and administrative corrections affecting old summaries. The exact turn schedule is a test fixture, not a forced story for users.

Hard gates: zero invalid resource balances; zero duplicate rewards; zero secret-sentinel leaks; zero missing committed operations; every checkpoint resumes the correct pending decision; identical recorded combat inputs reproduce their results. Retrieval correctness targets: at least 95% of scripted relevant-fact probes retrieve their required sources within budget, with failures listed and investigated. Narrative quality has a human rubric rather than a fabricated all-or-nothing guarantee.

A short Codex smoke cannot substitute for this soak. Normal CI can run a smaller deterministic subset; the long evaluation belongs in a release/nightly job with controlled AI usage or recorded responses as appropriate.

## 5. AI evaluation rubric

Score continuity, character consistency, grounded knowledge, preserved agency, rule-result fidelity, clarity of next decision, and nonrepetitive prose. Use adversarial input that asks the narrator to override rules, reveal hidden notes, grant items, edit engine files, or act for the player. Evaluate both proposals and final narration.

Automated checks cover IDs, prohibited markers, numeric references, expected predicates, and response schema. Human reviewers assess implications, misleading prose, unfair tactics, and unnecessary forced decisions. Do not claim a sanitizer can prove that arbitrary natural language contains no spoiler or contradiction.

## 6. Statistical combat checks

Enumerate or simulate 2d10 outcomes and verify probability distributions and strong-hit thresholds. Test min/max builds and extreme legal modifier combinations. Sweep target defenses and armor so balance changes have visible consequences. Record seeds and sample sizes for stochastic sweeps.

Specific properties: damage and resources remain in bounds; an invalid action costs nothing; a valid miss consumes declared costs; same draw references yield same result; action points never increase unexpectedly; reaction chains obey the cap; terminal encounters cannot accept new actions; settlement uniqueness survives concurrent calls.

Balance targets are provisional. Establish reference build and encounter suites before setting acceptance ranges for expected combat duration or win rate. Present observed distributions rather than declaring every budget-compliant fight balanced.

## 7. Fault injection

Exercise malformed JSON, huge frames, truncated stdout, stderr floods, RPC timeout, early turn events, process death, stale callbacks, duplicate notifications, lost leases, database deadlock, rollback, browser reload, expired stream cursor, missing assets, broken remote media, stale summaries, incompatible pack upgrades, and corrupted archives.

For each fault, define the expected operation state and the safe player message. No scenario may require “retry and hope” when a canonical receipt can determine the outcome.

## 8. Release gates

MVP requires QA-01–20, QA-23–26, QA-28–30, QA-33–35 and QA-40 at the supported reduced feature scope, a verified safe Codex profile, English/Italian visual-novel play and basic graphical kit, independent adventure workspaces, a complete simple combat flow, and a restart/renewal demonstration. QA-36–37 apply to the job/admission foundations actually enabled; an unavailable advanced planner must pass the no-plan baseline rather than block MVP. P14 records an optional bilingual planning comparison or an honest unavailable/deferred outcome. Runtime image generation under QA-31 belongs to v1. v1 requires all mandatory scenario scopes, the long-campaign evaluation, backup/restore rehearsal, actual browser/accessibility review, calibrated rules, rights/publication review and required roadmap work complete. QA-38's disabled/text-only fallback is mandatory; full speech/model/GPU qualification is conditional on enabling that optional feature. QA-39 assesses enabled planning/review modes and always reports the bilingual baseline and unrun/disabled cells. It cannot require adopting a mode without measured benefit. The later editor's E-series checks do not become initial-v1 prerequisites.

Failures are documented with severity and disposition. Secret leakage, canonical corruption, duplicate rewards, broken restore, or uncontrolled AI host capability block release. Cosmetic or optional-media issues can ship only with explicit scope/limitation notes and usable fallback.

## 9. Documentation verification

Before publishing this design set, verify internal relative links, required topic coverage, consistent terminology/formulas, Markdown fences, public language, truthful status, and absence of private campaign content. This is documentation QA, not implementation testing. Record the actual checks performed in the handoff rather than marking planned runtime criteria as passed.

The [research evaluation protocol](31-research-validation-and-evaluations.md)
defines paired A/B/C conditions, quantitative metrics, human rubrics, 100/500-turn
fault/continuity cases, live-cost controls and EN/IT speech/GPU qualification.
Objective integrity/security failures block release; human quality scores and
LLM-judge outputs never override deterministic invariant failures. No new
benchmark or runtime feature is verified by writing that protocol.
