# Changelog

This changelog records delivered project artifacts. Future functionality belongs in [the roadmap](docs/16-development-roadmap.md), not in completed release notes.

## Unreleased — Atomic campaign creation (P06.1), 24 October 2026

- Add an owner-scoped transactional creation service from sealed pack snapshots,
  validated preset/custom budgets, resource maxima and authored item/ability grants.
  Persist immutable scoped request identities for durable, concurrent safe retries.
- Add V9 character/loadout storage and recovery allowlist coverage; preserve
  populated older schemas without inventing character choices or advancing counters.
- Load bounded transitive sealed dependency snapshots, resolve external creation
  profiles and mechanics in their declaring namespace, and enforce actual pinned caps.
  Reject ambiguous dependency versions and keep existing root-local identities stable.
- Add opt-in local session bootstrap and protected synchronous campaign creation,
  with exact Host/Origin, server-configured ownership, bounded ephemeral sessions,
  independent CSRF proofs, concurrency admission and strict 16 KiB UTF-8 JSON.
  Return only committed identities and stable sanitized errors. Starting narration
  and creation UI integration remain later tasks.
- Verify two genre campaigns and immutable retry results across separate application
  JVM restarts, with old browser sessions rejected and initial counters/grants intact.
  All 290 packaged backend tests and 22 repository policy tests pass; scans are clean.

## Unreleased — Staged interface integration dependencies, 24 October 2026

- Correct the roadmap dependency cycle between localized interface integration
  and campaign/operation APIs. P06 waits for the completed UI foundation;
  P05.6 waits for P06.2 and P07.5 before integration can resume.
- Document exact-task prerequisite syntax, preserving all 117 task identities,
  the 36 completion marks, real-campaign reload protection and human review gates.
  This changes scheduling documentation, not delivered gameplay functionality.

## Unreleased — Localization review foundation (partial P05.6), 24 October 2026

- Assemble both locale variants into the jar/container; select the saved device
  language at root and support explicit localized preview GET routes. Preserve
  shared artwork/notices, opaque query/hash and missing-resource/API 404s.
  Verify bilingual browser journeys against the actual container, exact jar
  resource bytes and resource hashes across recreation; full QA-35 remains open.

- Add persisted English/Italian selection to compiled review Settings, safe reload
  with scoped literal drafts/selections, validated tab reading preferences/positions
  and equivalent-control keyboard focus; invalidate old callbacks and reply objects.
- Block language reload for unsaved/conflicting drafts, unresolved recovery across
  route changes, denied storage and real campaign contexts awaiting reconciliation.
  Review 218 unique messages including storage/fallback guidance; verify real
  EN→IT→EN keyboard reloads, scope isolation and persisted native size selection.
- Extend to 212 unique reviewed messages across every local preview screen,
  public sample passages/metadata, forms, preview receipts, recovery, kit and
  accessibility. Preserve IDs, proper adventure titles and literal player drafts.
- Add chronicle ICU plurals and locale-formatted resource text without changing
  numeric ARIA/protocol values. Verify native localized reading/form journeys.
- Pin Angular localize 22.2.3 and add separate English/Italian review builds with
  26 reviewed navigation/Settings messages, accessible labels and page titles.
- Reject missing, blank, stale, extra or duplicate translations and mismatched
  placeholders before compilation; run checker tests and review builds in CI.
- Verify both Settings variants in Chrome, including narrow layouts, keyboard
  focus, locale bases, same-origin resource hashes, notices and missing-file 404s.
- Keep real transport errors/receipts and operation/cursor reconciliation open;
  the packaged interface now consumes both localized variants.

## Unreleased — Complete local interface graphic kit (P05.5), 24 October 2026

- Integrate original MIT SVG/CSS controls, 20 allowlisted icons, bounded numeric
  resource/progress bars, dialogue/portrait/item frames and independent sample item
  art, with explicit empty/unavailable/invalid/selected/disabled/busy states.
- Add a Settings-linked review surface, editable-source/provenance inventory and
  complete distributed MIT notice; no third-party art or raster generation is used.
- Verify 30 frontend tests, strict typecheck/build, zero-vulnerability audit,
  22 repository tests and Chrome desktop/mobile keyboard/pointer/contrast checks,
  including narrow layouts, forced colours, increased contrast and reduced motion.
  Runtime campaign artwork and Italian resources remain later milestones.

## Unreleased — Revision-aware drafts and quick replies (P05.4), 24 October 2026

- Separate scoped public projection state from unsent drafts, with canonical and
  presentation revision ordering, callback epochs and stale/duplicate rejection.
- Persist bounded device-local draft text/selection per adventure/campaign; retain
  edits with visible fallback on unavailable, corrupt or full storage.
- Add typed contextual speech/question/action suggestions that fill an empty
  editable composer; dismissal preserves text and restores keyboard focus.
- Present specific connection/recovery states without clearing accepted context
  or replaying input. Send stays disabled until campaign operations exist.
- Pass 26 frontend tests, strict typecheck/build, a zero-vulnerability audit,
  22 repository tests and Chrome desktop/mobile draft/reload/connection smoke;
  visually review the mobile composer. Actual operation reconciliation remains
  in P06/P07 and full accessibility acceptance remains pending.

## Unreleased — Responsive reading layouts (P05.3), 24 October 2026

- Add a tab-lifetime text-focused reading preference alongside the default visual
  novel; omit artwork in text mode while retaining public scene descriptions.
- Resolve original vector portraits from scoped public speaker identities with
  live labels and missing-image fallbacks; record editable sources and MIT terms.
- Attribute revealed chronicle entries and keep keyboard focus in the dialogue
  when Continue reaches the end of supplied text. Reading remains local-only.
- Pass strict typecheck, 18 frontend tests, production build, zero-vulnerability
  audit, 22 repository tests and Chrome desktop/mobile keyboard/focus smoke,
  including 320px/landscape no-artwork reading at 24px and reduced motion.
  Visually review three screenshots. Persistent drafts, playable input,
  localization, the complete graphic kit and assistive-technology review remain.

## Unreleased — Content-bound interface metadata (P05.2), 24 October 2026

- Bind navigation, headings, character-name terminology and attribute/resource
  definitions to typed scoped public metadata with stable identities and neutral
  English fallbacks. Render metadata as literal text and leave balances unconnected.
- Configure optional guide/creative panels independently in the local genre samples;
  hide absent navigation and refuse disabled direct routes after adventure switching.
  This adds a frontend projection contract; pack module execution, registered
  pack/campaign APIs and localized UI remain later tasks.
- Verify 15 frontend tests, strict typecheck, production build, dependency audit
  (zero vulnerabilities), 22 repository tests and all four policy scans. Extended
  Chrome desktop/mobile smoke passes with no page exceptions or remote/action
  requests; visually review story and resource-panel screenshots.

## Unreleased — Portable launcher command checks, 24 October 2026

- Make launcher path/port checks independent of the runner's Docker version by
  injecting strict test-only Docker metadata. Check unsupported Engine/Compose,
  non-Linux containers, unreachable daemons and missing Buildx explicitly.
- Verify read-only file preservation recursively and use an available test port.
  Keep production Engine 29/Compose 5 minimums and real BuildKit/Compose checks.
  The Docker 28.0.4 reproduction now passes; local packaging tests pass 21 with
  two existing opt-in skips. Hosted verification is recorded separately.

## Unreleased — Combat dialogue design, 24 October 2026

- Extend the combat/UI, command and interpretation specifications with a persistent
  in-combat composer for participant dialogue, negotiation and supported scene
  interactions, including public speaker/recipient identities and editable replies.
- Specify bounded speech, declared social-action costs, explicit agreement decisions,
  turn eligibility, duplicate-safe recovery and causal event changes. Add a worked
  example, acceptance cases and P11/P12 scope. This delivers documentation only;
  playable combat chat and numerical-policy calibration remain future work.

## Unreleased — Interface preview shells (P05.1), 24 October 2026

- Add routed library, character-creation, visual-novel story, sheet, inventory,
  journal and settings shells with explicit local preview/unconnected states.
- Separate adventure cards from installed packs; validate bounded character-name
  previews without saving. Keep supplied-text reading positions independent per
  sample, attribute narrator/character passages and expose a read-only chronicle.
- Include original MIT-licensed woodland/station SVG scenes, live accessible text,
  responsive styles and bounded tab-local reading size. Real pack/campaign binding,
  playable input, full localization and the complete graphic kit remain later tasks.
- Align name validation with trimmed service input and clear unsaved summaries on
  adventure changes, including return navigation; add regression and settings tests.
- Verify typecheck, 11 form/routing/service tests, production build, dependency
  audit (zero vulnerabilities), 22 repository tests and all four repository scans
  (zero findings). Desktop/mobile Chrome smoke passes navigation, keyboard reading,
  chronicle, forms, settings and unknown-identity handling without remote/action
  requests or page exceptions; visually review both scene layouts.

## Unreleased — Validated pack localization (P04.6), 24 October 2026

- Add optional closed EN/IT overlays and source-owned text resources, stable ID/field bindings, glossary proper-name/term hints, explicit quote policies, placeholder parity and source-language fallback. Resolve text through a separate immutable catalog; mechanics, identities, visibility and dependency exports remain source-authoritative.
- Include reviewed EN/IT resources for all approved catalog leaves in both neutral genre fixtures, with explicit verbatim English quotes and honest coverage of source-only prose. UI resources, narration and independent human bilingual playtests remain later work.
- Seal deterministic localization coverage in report version 2. Add preservation-only V8 support for old/new report versions, extend the strict database-backup allowlist and verify exact recovery of fixture bytes, hashes, exports and both report formats.
- Verification: 45 focused cases pass; full pinned JDK 21/PostgreSQL 18.6 `verify` passes 262 tests with three packaging-profile skips (265 total), builds the jar and checks populated V1→V8 preservation. Backup tests pass eight with one Windows symlink skip; a disposable V8 dump/restore passes. All 22 repository tests pass and docs/secrets/workflows/images have zero findings; jar inspection confirms trusted schemas/V8 and excludes test inventories.

## Unreleased — Neutral genre import fixtures (P04.5), 24 October 2026

- Add standalone fantasy exploration and science-fiction salvage packs with distinct world graphs, vocabulary, skills, resource caps, creation profiles, abilities, quests and original MIT-licensed schematic PNG assets.
- Prove unchanged directory/ZIP imports, read-only preview, exact sealed byte/hash/report preservation, idempotency, recompilation and independent adventure imports on the same engine. Broken starting-item references in both real fixtures fail with source context and no partial rows.
- Keep optional modules disabled under the existing engine contract. These are English import/compiler fixtures; playable campaigns, guide/creative runtime, full adventure isolation and reviewed EN/IT overlays remain later gates.
- Verification: two focused PostgreSQL integration tests pass; full pinned JDK 21/PostgreSQL 18.6 `verify` passes 220 tests with three packaging-profile skips (223 total) and builds the jar; 22 repository-checker tests pass and all four policy checks have zero findings.

## Unreleased — Protected pack registration (P04.4), 24 October 2026

- Add bounded directory/ZIP staging with portable path/device/case guards, link/special-file rejection, consistent archive headers/descriptors, CRC verification and actual inflation limits; retain immutable staged byte copies without disk extraction.
- Validate allowlisted media signatures and image header dimensions. Store original pack bytes, hashes, exports and deterministic reports atomically in adventure-owned revisions. Serialize idempotent imports and sealing; reject conflicts, legacy-unsealed retries, mutations and late file insertion, and verify hashes on reload.
- Add V7 tables/triggers and populated V1→V7 preservation checks. Record the bounded ZIP subset, PostgreSQL storage and provisional ruleset descriptor hash in ADR 0018.
- Verification: 23 focused admission tests; full pinned JDK 21/PostgreSQL 18.6 `verify` passes 218 tests with three packaging-profile skips (221 total) and builds the jar; 22 repository tests pass and all four policy checks have zero findings.
- Directory sources require owner control and a quiescent tree. Header checks do not fully decode media or prove rights. Import UI/endpoints, serving/decoder budgets, genre fixtures and locale overlays remain future work.

## Unreleased — Bounded pack semantics and generation gate (P04.3), 24 October 2026

- Add a statically analyzed, data-only expression language with depth/node/dice/overflow/division limits; typed effects with bounded conditional nesting and inherited timing; condition stacking, duration, modifiers and ticks; and quest definitions with world-scope predicates and outcomes.
- Detect ability-prerequisite, condition-tick and quest-dependency cycles with path diagnostics; check SemVer engine/ruleset ranges, modules and same-version hash conflicts against a local engine contract (ruleset 1.0.0 provisional until P07).
- Compile generation policies with opt-in categories, budgets, tags, templates, ranges and fixed/required/excluded author constraints. A deterministic `GenerationGate` rejects proposals that override them. Add an unreachable-location warning. ADR 0017 records the decisions.
- 19 compiler tests and the full pinned backend verification pass (198 tests, three profile skips). Encounter/anchor/progression kinds, runtime gate callers and import registration remain future work.

## Unreleased — Semantic pack compiler (P04.2), 24 October 2026

- Add `PackCompiler`, `CompiledPack`, `Definition` and a local `DependencyCatalog`: unique IDs, category/kind agreement, typed cross-references (including `packId#id` for declared dependencies), rule-defined attributes/resources/rarity tiers, unique item appearance identities, creation budgets, skills, abilities and initial world definitions, with closed objects and located diagnostics.
- Reject kinds without a semantic schema instead of ignoring them. Expression/effect limits, cycles and version compatibility stay in P04.3; archive protections and registration in P04.4. The 8-test compiler suite and the full pinned backend verification pass; no playable-pack admission is claimed.

## Unreleased — Contract-1 parsing foundations (P04.1), 24 October 2026

- Add a closed versioned manifest and preliminary definition-envelope JSON Schemas, explicit file categories and source-located diagnostics.
- Parse bounded UTF-8 YAML/JSON as immutable data without YAML object constructors, aliases, tags, merge keys or duplicate keys; preserve YAML 1.2 core typing and reject front matter in unrendered Markdown.
- Define sorted path/length/raw-SHA-256 framing covering all distributed bytes, including manifest/comments/assets, independently of map order or host metadata.
- Pass the 63-case parser/schema/hash corpus, including an independent digest vector and final-line-terminator regressions. Full pinned JDK 21/PostgreSQL 18.6 backend verification passes 176 tests with three packaged-interface profile skips (179 total) and builds the jar with both schemas; repository tests pass 22 and policy checks report zero findings. Keep semantic definition validation, archive/filesystem/media protections and immutable registration in P04.2–P04.4; no playable-pack admission is claimed.

## Unreleased — Durable preparation and fenced admission (P03.6), 24 October 2026

- Add V6 scoped jobs/attempts, immutable snapshots, versioned artifacts/dependencies, admission receipts, append-only dispositions, query epochs and campaign recovery generations.
- Persist finite attempt budgets, usage observations, cancellation and lease fences. Gate claims/admissions on startup reconciliation; require confirmed external stop before retrying expired work. Restore retires generations, leases and usable plans while preserving canonical counters and audit bytes.
- Check snapshot assembler freshness and the complete engine manifest; reject query phantoms, stale public locale/glossary tokens and unsupported semantic dependencies. Serialize admission and canonical invalidation by campaign; cancellation races under the job lock. Keep public locale preferences separate from canonical state.
- Commit critical jobs, query-epoch bumps and invalidation with the canonical state/event/receipt/outbox bundle; reject conflicting duplicate intent and roll back injected failures. Admission creates no canonical event, draw, reward or time advance.
- Pass full backend `verify` on pinned JDK 21/PostgreSQL 18.6: 113 tests pass, three packaged-interface profile skips, jar built; includes 17 preparation tests and populated V1→V6 upgrade. A V6 backup/restore and jar-startup rehearsal passes. Repository tests pass 22; backup tests pass 8 with one Windows symlink skip; default packaging tests pass 20 with two opt-in skips.
- Keep dispatch, fairness/global quota enforcement, runtime cancellation transport, full semantic dependency validation, gameplay consumption and full QA-36/37 for later packages. No hosted-CI or narrative-quality result is claimed.

## Unreleased — Leases, startup recovery and database backups (P03.5), 24 October 2026

- Add forward-only V5 campaign mutation leases with a persistent fence that only grows, database guards against rewinds or unfenced owner changes, and a recovery index. `MutationLeaseService` grants, renews and releases leases using PostgreSQL time.
- Require the current lease token for new draws and canonical commits, after immutable replay. Release the lease in the commit transaction, so a late or expired holder cannot commit.
- Reconcile the journal at startup, before the web server starts, in bounded pages: retire expired leases, keep live ones, fail orphaned precommit work and mark interrupted narration pending without repeating effects. A recovery error aborts startup.
- Add `tools/database_backup`, a trusted-owner `pg_dump`/`pg_restore` tool with a version, catalog and checksum manifest. It restores only into a new database and retires saved leases; its offline tests run in CI.
- Pass the full offline backend verify on pinned JDK 21/PostgreSQL 18.6 (95 tests pass, three packaged-interface profile skips, jar built) and 9 backup-tool tests (one symlink skip). A manual backup/tamper/restore/startup rehearsal also passed. Repository-checker tests pass 22/22, default packaging checks pass 20/22 with two opt-in skips, and policy checks report no findings.
- Assets and content in backups, worker cancellation, durable jobs and full QA-17/QA-23 remain later work; hosted CI for this change is unconfirmed.

## Unreleased — Deterministic draw reservations (P03.4), 24 October 2026

- Add private lazy campaign seeds and immutable operation/ordinal draw records in forward-only V4, with scoped foreign keys, uniqueness and bounds. Preserve historical outcomes without backfilling randomness.
- Pin HMAC-SHA-256 v1 binary framing and unbiased rejection sampling. Reserve in a separate bounded transaction without world/journal changes; replay matching retries, reject changed bounds and new stale/cancelled/committed allocations.
- Add backend-owned ordered draw references/values to canonical receipts; keep seeds private and retain reserved values after failed canonical commits. No public draw endpoint or speculative planner access is exposed.
- Pass full offline backend verification on pinned JDK 21/PostgreSQL 18.6: 81 tests pass, three packaged-interface profile tests skip, and the executable jar builds. Cover independent vectors/rejection, maximum receipt capacity, concurrent reuse, cancellation, allocation/commit rollback, fresh-service reuse, database constraints and populated upgrade. Repository-checker tests pass 22/22, default packaging checks pass 20/20 with two opt-in skips, and repository policy checks report zero findings. Dependency pins are unchanged.
- Equivalent-new-attempt reroll policy, gameplay authorization, leases/fencing, backup/restore and full recovery acceptance remain later work; hosted CI for this change is unconfirmed.

## Unreleased — Atomic canonical commits (P03.3), 24 October 2026

- Add an internal bounded commit service for world counters/mode/decision, resource changes, sequenced events, reward settlements, journal receipts and public-state/narration outbox intents in one database transaction.
- Lock scoped campaign and journal rows, recheck revision/content/resource versions, replay the exact stored receipt on duplicate completion, and reject ambient transactions. Accept immutable resolved data without network clients or executable callbacks; use bounded lock, statement and transaction timeouts.
- Add forward-only V3 with immutable reference-only outbox rows, unique operation/kind keys and a deferred campaign/operation/committed-revision foreign key. Preserve historical outcomes without fabricating delivery work on upgrade.
- Pass full backend compilation, tests and jar build against PostgreSQL 18.6: 72 tests pass and three packaged-interface tests skip outside their profile. Cover every write boundary and deferred-commit rollback, duplicate/competing operations, intervening resource writes, scope/version guards and populated upgrades. Repository-checker tests pass 22/22, default packaging checks pass 20/20 with two opt-in checks skipped, and repository policy checks report no findings. Dependency pins are unchanged.
- Command/API authorization and semantic rules, deterministic draws, leases/recovery, outbox delivery and full QA-20/QA-33 acceptance remain later tasks; no gameplay endpoint is exposed.

## Unreleased — Campaign constraints and storage versions (P03.2), 24 October 2026

- Add forward-only V2 with bounded actor resources, campaign/actor foreign keys and unique resource keys; freeze ownership and provenance after insert.
- Add independent optimistic row versions to seven mutable tables, prevent counter rewinds, require complete write-once journal outcomes and one committed operation per campaign revision, and bound stored JSONB payload bytes.
- Verify repository scope, raw SQL constraints, inclusive resource/payload limits, stale/concurrent writer rollback, simultaneous duplicate keys/reward sources and populated V1-to-V2 upgrade preserving canonical counters.
- Pass full backend compilation, tests and jar build against PostgreSQL 18.6: 43 tests pass and three packaged-interface tests skip outside their profile. Repository-checker tests pass 22/22, default packaging checks pass 20/20 with two opt-in checks skipped, and repository policy checks report no findings. Dependency pins are unchanged.
- Application authorization, atomic command/outbox commits, deterministic draws, leases and recovery remain later tasks; this establishes persistence foundations only.

## Unreleased — Canonical foundations (P03.1), 24 October 2026

- Add Spring Data JPA and the first Flyway migration with ten tables for owner profile, adventure workspaces, content revisions, campaigns, presentation settings, actors, the operation journal, campaign events, reward settlements and checkpoints; Hibernate validates the mapping at startup.
- Enforce explicit ownership in PostgreSQL: campaigns pin a content revision of their own adventure, campaign-owned rows use composite campaign foreign keys, idempotency keys and reward sources are unique per campaign, and events, revisions, checkpoints and settlements are immutable.
- Keep canonical counters on the campaign row and narrative locale, glossary and media generations in a separate presentation table; implement the docs/07 operation lifecycle and the en/it locale allowlist.
- Add 21 backend tests against real PostgreSQL 18.6 and in memory. No service, API or UI uses the tables yet; version fields, bounds, atomic commit, random draws, leases and recovery remain P03.2–P03.6.

## Unreleased — Launcher lifecycle verification (P02.7), 24 October 2026

- Add the opt-in `test_lifecycle.py` and a sanitized Windows report. On Windows 11 with Docker Desktop and Git Bash it verified repeated launch (containers and password unchanged), a 3.5 s graceful stop (exit codes 0, 0 and 143 for the engine), restart and image-recreation preservation of neutral database and content markers, and exit 130 after interrupts during build and startup.
- Not verified: an interactive Ctrl+C, Linux/macOS hosts, WSL, owner sign-in, a cold build, real campaign data and engine draining of in-flight commands. The check is partial QA-28 evidence; no application behavior changed.

## Unreleased — Root launcher (P02.6), 24 October 2026

- Add the root `start.sh` (Bash 4.4+, mode 100755): prerequisite checks against the documented Docker floors, one-time `.env` and random database-secret creation, cached builds, health-based startup, and `--check`, `--status`, `--logs`, `--stop` and `--login` commands with nonzero exit codes on failure.
- Never regenerate or print the database secret, refuse to start when it is missing while the installation's database volume exists, redact credential-like values from logs, and stop without removing volumes.
- `--login` only runs the supported device-code flow in an isolated one-off container for the owner; it never reads credential files or enables paid fallback. Gameplay readiness stays unclaimed.
- Add 16 launcher tests. Manual Docker Desktop checks covered first start, repeat start, restart with data kept, busy port and unreachable daemon; Linux/macOS hosts, completed sign-in, interrupt timing and hosted CI for this commit are not verified.

## Unreleased — Hosted CI verification (P02.4), 24 October 2026

- Confirm [hosted run 40028086153](https://github.com/AjimuZ/ai-rpg-engine/actions/runs/40028086153) on commit `09a6311d9557be01f2fee9abeb119206f0b02e34`: all four repository, frontend, backend and container jobs completed successfully. Record its exact source revision and completion time, complete P02.4 and remove the stale manual-confirmation blocker.
- Verify hosted evidence through read-only GitHub run/jobs queries; change no application or workflow code and make no new gameplay, launcher or account-readiness claim.

## Unreleased — Ordinary credential-free CI (P02.4), 23 October 2026

- Add a GitHub Actions workflow with read-only permissions, no secrets, SHA-pinned
  actions and four jobs: repository checks, frontend build/test/audit, backend
  build/test against a job-local PostgreSQL, and container context/Compose checks.
- Add stdlib-only repository checks (secret and private-path scan, Markdown links
  and fences, workflow policy, image pins) with 22 unit tests.
- Make `PackagedSecretsTest` independent of exported `SPRING_DATASOURCE_*` variables, which
  otherwise override the mounted secret file and failed the backend build under CI-style
  environment settings; the job was replayed in JDK 21 and PostgreSQL 18 containers.
- At this implementation checkpoint the hosted run was unconfirmed; the later hosted-verification entry above supplies that evidence. The dependency license inventory remains P19.1.

## Unreleased — Containerized application shell (P02.3), 23 October 2026

- Build the packaged Angular interface and Spring Boot application in pinned
  Node/JDK stages; ship only the executable jar and bundled notices in a
  non-root JRE image.
- Add the engine to Compose with loopback HTTP, private database/runtime
  boundaries, read-only IPC, file-based database secrets, and durable content
  and asset volumes. Keep private development data outside build contexts.
- Verify the three-service shell with five packaging checks, nine backend
  tests, frontend checks and 116 runtime checks. Recreation preserves neutral
  storage markers; missing secrets fail startup. Full launcher/account/campaign
  acceptance (QA-28), native Linux secret permissions and Java release refresh
  remain separate gates.

## Unreleased — Bounded dispatch and serialized inference (P01.7), 23 October 2026

- Add a bounded per-thread/turn inbox fixture using the real managed runtime
  and internal scripted upstream; preserve interleaved events and verify
  targeted interruption, unaffected active work and terminal/stream closure.
- Retain five neutral scripted turns and local cancellation observations;
  exercise interrupt/reconcile/dispatch priority with one active turn.
- Explicitly select one active inference turn installation-wide: live
  simultaneity, headroom, quota behavior and service priority remain unverified.
  Scripted overlap provides no live throughput claim; P08 owns production
  admission/recovery, and P08.6 gates any increase. No account is accessed.

## Unreleased — Explicit image capability boundaries (P01.6), 23 October 2026

- Record managed-runtime image unavailability, no supported invocation/output
  formats, unverified account entitlement/cost and text/catalog fallback scope.
- Include separate image diagnostics in discovery and add an offline report plus
  unconditional generation refusal before runtime/account access. No paid access,
  live image call, account mount or production job/asset pipeline is introduced.
- Test policy/format refusal, account-independent text discovery, CLI isolation,
  managed profile switches and hostile image function calls in the pinned runtime.

## Unreleased — Competitive research and bilingual narrative design, 23 October 2026

- Inspect five competitor repositories at recorded revisions, source/test definitions, dependencies, issue trackers and license texts. Add a traceable comparative/gap register; adopt patterns rather than copying code, media or campaign content, and distinguish observed mechanisms from unmeasured quality claims.
- Specify a durable shared-runtime priority queue, private versioned quest-plan artifacts, complete read/query dependencies, transactional admission, cancellation/recovery, fair budgets and a playable no-plan baseline. Preserve backend authority, tool-free runtime isolation and Codex-only gameplay; higher inference concurrency and narrative review remain evidence-gated.
- Require English-default and first-class Italian from the first playable release. Define localized Angular variants, safe preference reload, text-only pack overlays, direct selected-language generation, stable terminology and immutable historical language.
- Research local speech code/weights/voice rights and languages; conditionally select Qwen3-TTS 0.6B Base with a Kokoro CPU/ONNX fallback, pending exact distributable rights, EN/IT quality and target-hardware tests. Define persistent voice identities, validated-line playback, cache/restore and bounded GPU admission; no speech model is installed or benchmarked.
- Add explicit person-to-person story-bundle import/export with compatibility, locale, dependency, hash and credit manifests; retain private-playthrough/account boundaries and the separate later editor E01–E06.
- Add ADR 0014–0016, FR-033–037, NFR-013–015, QA-35–41, an unexecuted evaluation protocol and an Italian executive report. Integrate eleven unchecked implementation/qualification tasks into P00–P19, preserving all twelve verified completion boxes. Advanced planning/review and speech do not become hidden MVP blockers.
- This change delivers research and documentation only. Validation checks sources/references, links/anchors, traceability, task dependencies/status and the documentation-only diff; it establishes no new gameplay, concurrency, speech or narrative-quality result.

## Unreleased — Protocol fixtures and compatibility records (P01.5), 23 October 2026

- Retain projected account-free 0.164.0 request/result exchanges and terminal events with identifier aliases and neutral error text, plus a bounded-session capture command and offline contract replay.
- Record exact executable/image/client/host scope, service maturity, unsupported architectures/platforms and upgrade checks. Keep live isolation quota, authenticated packaged restart, image capability and full application acceptance gates explicit.
- Verify the documented capture command, all 98 runtime tests and all five packaging tests with Docker enabled and no skips, four Compose configurations, Python syntax/docstrings and local documentation links/anchors. Mark P01.5 complete; retain the remaining package and application gates.

## Unreleased — Runtime process lifecycle (P01.4), 23 October 2026

- Add owned-child shutdown records and bounded escalation scoped to a unique container name; enable runtime Compose init for signal forwarding. Keep Docker control outside the backend.
- Make terminal wire failures sticky, retire unresolved RPC generations, retain ordered early notifications, and classify stderr within fixed byte/line/tail bounds with allowlisted diagnostic labels.
- Add synthetic failure-injection and real pinned-runtime tests for process exit, ignored EOF, service stop, upstream interruption, relay-loss reconciliation and turn deadlines. Results on Windows 11 / Docker Desktop / `linux/amd64`: all 90 runtime tests and 5 packaging tests pass with Docker enabled and no skips; four Compose configurations and documentation links/anchors pass. No account-backed inference or campaign-save acceptance is claimed.

## Unreleased — Narration runtime isolation (P01.3), 23 October 2026

- Bake an engine isolation profile into the 0.164.0 runtime image: a managed configuration layer, an engine model catalog, neutral instructions, requirements pinning `never`/`read-only`/no search with no extension servers, and a startup guard refusing inherited home configuration or instruction files ([ADR 0013](docs/adr/0013-narration-runtime-isolation.md)).
- Add `test_isolation.py` with Docker fixtures. They prove an empty model tool list, six refused host tool calls with no canonical write, rejected or clamped wider thread policies, and ignored inherited configuration and launch flags. The guard refuses each inherited home file. Turns containing non-narration items now fail closed.
- Results: all 59 probe tests pass with Docker enabled, and the packaging tests pass, including recreation. A live re-check on the rebuilt image hit the account usage limit and remains open.

## Unreleased — Device-code login prerequisite, 23 October 2026

- Explain the ChatGPT security-setting/workspace permission required by the project's verified container device-code login flow in setup and integration guidance. Require actionable launcher guidance in P02.6–P02.7; keep other upstream login methods distinct and retain owner-controlled account setup.

## Unreleased — Authenticated neutral lifecycle completion (P01.2), 23 October 2026

- Verify one live neutral task after owner-operated device-code login in a dedicated runtime volume. Explicit `gpt-6-luna` / `low` settings produce a correlated completed envelope accepted by the independent validator; a second stdio process resumes the same completed turn and settings.
- Retain a sanitized runtime 0.164.0 report without account identifiers, credentials or thread/turn IDs. Rerun all 51 protocol tests with Docker enabled: all pass with no skips. Complete P01.2; deeper isolation, packaged startup, gameplay behavior and QA-08/QA-28 remain open.

## Unreleased — Development packaging prerequisite revision, 23 October 2026

- Permit local development image builds on the existing exact official Java 21.0.12.1 pins while the updated official images are unpublished. Retain the same distribution, base, digest checks and packaging acceptance; require the newest published Java update before release.
- Recheck both exact image index digests and the official tag API. This documentation revision unblocks further P02.3 work; it does not deliver an engine image, complete P02.3 or validate signed-in inference. Earlier checkpoint entries below retain their historical gate descriptions.
- Document owner-operated device-code sign-in in a dedicated runtime volume, keeping the development account separate; login and live neutral inference remain distinct checks.

## Unreleased — Packaged file-secret configuration (partial P02.3), 23 October 2026

- Add an opt-in Spring runtime profile requiring a file-secret config tree for the database password, with container HTTP binding and no fallback password; retain loopback host-development defaults.
- Verify actual configuration loading, newline handling, paths with spaces, host defaults and missing mount/file failures using neutral account-free tests. All nine packaged backend tests pass; a non-root executable jar authenticates from a mounted secret and serves exact compiled index/notices bytes. Engine image/service wiring remains gated by the official Java refresh; full packaged startup and QA-28 remain open.

## Unreleased — Single-origin jar assembly (partial P02.3), 23 October 2026

- Add an opt-in Maven profile embedding compiled Angular production assets and extracted third-party notices in the executable backend jar, served at one origin without an additional server.
- Verify the real HTTP index, bundles, notices and missing API/resource 404 responses against disposable PostgreSQL. Guard missing index/notices before packaging; keep ordinary backend builds independent of frontend output. Engine container packaging remains gated on the mandatory official Java image refresh; no launcher or full QA-28 acceptance is claimed.

## Unreleased — Health and readiness contracts (P02.5)

- Add liveness, storage-readiness and separate gameplay-readiness health groups, a schema indicator, and actionable secret-free startup failure descriptions for database and migration problems.

## Unreleased — Isolated infrastructure (partial P02.3), 23 October 2026

- Add root Compose database/runtime services with separate networks, file-based database secrets, durable private storage, tmpfs control IPC, bounded logs, graceful stop and process/socket health checks.
- Add rendered-configuration boundary tests and an opt-in account-free Docker test proving password-authenticated database connectivity, network/UID/socket checks and neutral storage preservation across container recreation. Stop test services while retaining volumes and fixture secrets. Engine packaging and the single-origin interface remain pending the official Java image refresh; full startup and account readiness are unverified.

## Unreleased — Build-context isolation (partial P02.3), 23 October 2026

- Add default-deny root and independent runtime Docker context policies, excluding private configuration, credentials, data, Git history and generated output, including nested private paths.
- Add two real BuildKit export regression tests using neutral isolated fixtures. Engine image/Compose packaging remains pending because ADR 0009 requires official Temurin 21.0.13 or later images that are not yet published; no packaged runtime or persistence claim is made.

## Neutral development shells (P02.2), 23 October 2026

- Add pinned Angular and Spring Boot workspaces without adventure content, with strict frontend compilation, external PostgreSQL/Flyway configuration, and minimal status-only backend health.
- Add default shell tests and reproducible host build commands, checksum-verified Maven Wrapper policy, Central-only resolution, and explicit npm install-script policy. Pin transitive Vite 8.3.5 to resolve development-tool advisories without changing Angular; Windows/Linux locked installs, typechecks, default tests and production builds pass, as do full npm audit and the Java 21/PostgreSQL 18 backend tests and jar build. Packaged startup and canonical migrations remain later tasks.

## Unreleased — Repository policy and environment placeholders (P02.1), 23 October 2026

- Add shared EditorConfig defaults for UTF-8/LF, final newlines and indentation, preserving Markdown hard breaks; retain Git text normalization and binary image handling.
- Keep the owner-approved MIT license and contribution terms, document formatting/configuration policy, and ignore Python probe caches alongside private configuration and build output.
- Add neutral, credential-free environment placeholders with no current consumer. Application scaffolding, packaged startup and CI enforcement remain later P02 work.

## Unreleased — Thread and turn protocol fixture (partial P01.2), 23 October 2026

- Add a test-only scripted upstream on an internal Docker network, exercising seven real runtime turns, two threads and reconnect/resume without credentials. Verify explicit per-turn model/effort and full output schema at the upstream boundary, correlation, terminal outcomes and interruption of active streams.
- Bound and retain early notifications, deny unsolicited privileged requests, validate effective thread policies and redact unknown error variants. Add regression checks for malformed events, missing output, queue overflow, deadlines and the opt-in live profile's account/volume gates.
- Document reproducible checks and an owner-only neutral smoke profile. P01.2 remains open because signed-in inference has not run; production concurrency, deeper isolation and runtime restart remain separate gates.

## Unreleased — Pinned narration runtime and discovery probe (P01.1), 23 October 2026

- Add the narration runtime image `docker/codex-runtime/Dockerfile`. It installs runtime release 0.164.0, verified by checksum, on a digest-pinned Debian 13 base, runs as UID 10001, and listens on a private Unix control socket. ADR 0009 records the pins.
- Add `tools/runtime_probe`, a developer-only probe. It performs the handshake, the account-state read, and paginated catalog discovery over stdio, on the host or in a container, and over the packaged Unix socket through a Java 21 WebSocket relay. It also validates the output contract strictly and offline. It is not engine code and never signs in.
- Record the verified transport facts: WebSocket framing over the socket, the real socket location, and the `0600` UID boundary. Record the remaining gaps too: sign-in, live inference, the sandbox fallback, and `linux/arm64`.

## Unreleased — Later editor and customization plan, 23 October 2026

- Plan the integrated text/graphical adventure editor after core v1 using a separate E01–E06 checklist, with fixed author requirements, mandatory characters and bounded completion of unspecified details.
- Specify genre-independent fantasy/cyberpunk authoring and customizable combat data/policies around the default ruleset, preserving supported kernel boundaries and frozen encounters.
- Add QA-34 and align manual authoring, UI, requirements and roadmap semantics. No editor or new combat algorithm is implemented.

## Unreleased — Independent adventure-workspace design, 23 October 2026

- Separate personalized adventures from playthroughs/checkpoints, with managed folders, scoped specifications, drafts, memory and generated art.
- Add ADR 0012, proposed library/API/storage contracts, FR-032 and QA-33, and explicit implementation/restore gates. No runtime workspace is created by this change.

## Unreleased — Dynamic music design, 23 October 2026

- Specify optional event-driven music, reviewed free-license online/local sources, deliberate activation, stable loops/crossfades, privacy controls and failure fallback.
- Record two reference-only tracks with creator/source and distinct CC BY 4.0/3.0 credit templates; no audio is downloaded, bundled or implemented. Add P17.2/QA-32 acceptance work and third-party reference notices.

## Unreleased — Visual-novel and persistent-artwork design, 23 October 2026

- Make visual-novel play the default interface direction, with attributed narrator/character/player passages, editable contextual quick replies, a persistent free-input composer and a complete text-focused alternative.
- Add a primary narrative-screen concept and its prompt/provenance; retain the older illustration as a combat-screen concept. These are design artifacts, not running UI or separately usable production assets.
- Accept ADR 0011 and specify a complete UI graphic kit, stable saved character/item artwork, accessible item rarity and bounded runtime image generation for v1 with verified capability, budget and fallback boundaries.
- Align requirements, domain/content/narration contracts and P01/P05/P17 implementation tasks with QA-29–31. No interface, image-generation pipeline or rarity mechanics are implemented by this documentation change.

## Unreleased — Name, v1 boundaries, pack file format, and licensing plan (P00.5), 23 October 2026

- Add accepted ADR 0010. It records the product name AI RPG Engine and the identifiers derived from it (slug and Compose project `ai-rpg-engine`, Maven `io.github.ajimuz:ai-rpg-engine-backend`, Java package `io.github.ajimuz.rpgengine`, ruleset ID `skirmish`), and it confirms the MVP and v1 boundaries without change.
- Fix pack contract 1 (`schemaVersion: 1`): UTF-8 without a byte-order mark; YAML 1.2 core-schema `.yaml` files with no anchors, aliases, merge keys, tags, or duplicate keys; equivalent `.json`; CommonMark `.md` prose with no front matter or rendered HTML; JSON Schema draft 2020-12; directory or `.zip` distribution.
- Record the licensing plan: MIT for the engine and the neutral sample packs, third-party packs under their own terms, a permissive allowlist for bundled dependencies, locally built images that are not redistributed, and the name's trademark review left for P19.1.
- Align the pack specification, ADR 0004, the ADR index, risks and open decisions, publication guidance, and the README. No parser or application code is included.

## Unreleased — Default combat ruleset confirmation (P00.4), 23 October 2026

- Accept ADR 0006: AI RPG Skirmish is the implementable default ruleset, and every numerical default stays provisional until calibration in P13 and P18.3.
- Add a provisional numerical defaults register to the combat specification. It separates the confirmed structure (which needs a superseding ADR to change) from tunable values, gives each value its defining section and calibration gate, and requires the values to live in ruleset-profile data with versioned revisions.
- Record that the default advancement profile has no maximum level; a pack may declare one.
- Align the ADR index, risks and open decisions, gameplay, encounter, worked-scenario, and README wording. No rules code is included, and no balance is claimed.

## Unreleased — Host, container image, Compose, and shell baseline (P00.3), 23 October 2026

- Extend ADR 0009 with the first validation target: Windows 11 x64 with Docker Desktop (WSL 2 backend, Linux containers) and Git Bash. Bash in a WSL distribution becomes a candidate; other hosts remain unclaimed.
- Pin by multi-platform index digest: Dockerfile frontend 1.27.1, `node:24.22.1-trixie-slim`, and Eclipse Temurin `21.0.12.1_1-jdk-resolute` / `-jre-resolute`. Require a refresh to the Temurin 21.0.13 images before the first engine image.
- Set the launcher floors (Docker Engine 29.0, Compose 5.0, Buildx, GNU Bash 4.4), the allowed host commands, the Git Bash path-conversion rules, and a digest-refresh policy for every image.
- Record a throwaway Compose probe from Git Bash: an Angular build, a Maven Wrapper build, and a Spring Boot JRE runtime against PostgreSQL 18.6. It covered health-gated startup, secrets, an internal network, and a loopback-only port, and its findings became rules.
- Add the image-refresh risk and align the architecture, deployment, risk, README, and ADR index baselines. No container, launcher, or runtime file is included.

## Unreleased — Backend and database baseline (P00.2), 23 October 2026

- Extend ADR 0009 with Spring Boot 4.1.2 on Java 21, the libraries managed by Spring Boot (including Flyway 12.4.0 and PostgreSQL JDBC 42.7.14), Maven 3.10.0 through an `only-script` Maven Wrapper 3.3.4 with a distribution SHA-256, and PostgreSQL 18.6 (`postgres:18.6-trixie` pinned by digest) with matching `pg_dump`/`pg_restore`.
- Record the evidence: support schedules, Maven Central metadata, and a throwaway wrapper build on a JDK 21 against PostgreSQL 18.6. The build covered checksum and toolchain enforcement, four integration tests, Maven Central artifact origins, and a dump/restore round trip.
- Record the implementation rules found by the probe: `unzip` for Linux wrapper runs, Maven Central-only resolution despite Flyway's extra repository declarations, and the PostgreSQL 18 volume path.
- Add the backend support-window risk, and align the architecture, deployment, risk, and README baselines. No backend workspace or runtime code is included.

## Unreleased — Frontend toolchain baseline (P00.1), 22 October 2026

- Add ADR 0009 pinning Angular 22.2.3, TypeScript 6.0.3, Node.js 24.22.1 LTS, npm 11.19.1, RxJS 7.8.2, tslib 2.8.1, and Vitest 5.0.5.
- Record the compatibility evidence: registry engines and peer ranges, support schedules, a conflict-free lockfile resolution, and the rejection of TypeScript 7.
- Record the workstation tool inventory; the host has Java 27, so the Java 21 baseline remains for P00.2.
- No frontend workspace or runtime code is included.

## Unreleased — Development branch policy, 22 October 2026

- Use `main` as the single project branch: verified implementation commits are pushed directly to `main`. A `develop` branch used briefly the same day was fast-forwarded into `main` and removed.
- Exclude force pushes and published-history rewrites.
- Require implementation commits to name their roadmap item in a final `Roadmap: <item>` body line.

## Unreleased — Container deployment design, 22 October 2026

- Record the owner requirement for a fully containerized local application launched through root `start.sh`.
- Specify the packaged topology, private runtime transport gate, account setup, durable volumes, readiness, and safe lifecycle behavior.
- Add FR-029, QA-28, ADR 0008, and corresponding implementation/release roadmap tasks.
- Align architecture, operations, integration, and publication guidance; no executable container or launcher files are implemented in this milestone.

## Unreleased — Development workflow, 22 October 2026

- Require immediate roadmap and affected-documentation updates after each completed development item or project change.
- Define neutral English commit-message conventions and align the roadmap's suggested messages.
- Record the distinction between local checkout enforcement, semantic review, and future continuous integration; private assistant configuration remains untracked.

## Unreleased — Project licensing, 22 October 2026

- Add the MIT License with copyright 2026 Azim Ben Othman.
- Document license scope, contribution terms, and separate ownership of adventure packs and third-party assets.
- Preserve the upstream MIT notice and resolve the pending repository-license decision.
- Update the publication guide with the private repository status and remaining public-repository preparation.

## Unreleased — Repository preparation, 22 October 2026

- Add Git exclusions for local assistant configuration, environment files, private runtime output, and generated artifacts.
- Normalize text line endings and preserve the UI concept image as binary.
- Document the initial commit scope and pre-commit review commands.

## Unreleased — Documentation baseline, 22 October 2026

- Adopt AI RPG Engine as the owner-selected project name and `ai-rpg-engine` as the project directory and repository slug.
- Document source-project maturity, reusable patterns, conflicts, and improvements.
- Specify Angular/Spring Boot/PostgreSQL architecture and a backend-owned Codex integration.
- Specify immutable adventure packs, canonical state, command transactions, knowledge boundaries, checkpoints, and renewable AI sessions.
- Design the original AI RPG Skirmish turn-based ruleset, bounded encounter generation, and advancement policies.
- Define API/UI behavior, security, recovery, testing targets, worked scenarios, and architectural decisions.
- Provide an ordered development roadmap through a complete initial engine and GitHub release preparation.

No application code, executable configuration, schema implementation, remote repository, or runtime test result is included in this milestone.
