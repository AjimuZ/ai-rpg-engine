# Risks and Open Decisions

## 1. Risk register

Owners below are implementation responsibilities, not assigned people. The project owner can combine roles in a solo project.

| Risk | Impact | Mitigation and evidence required | Owner / gate |
| --- | --- | --- | --- |
| Codex app-server protocol changes or unsupported behavior | AI integration unavailable | Pin adapter baseline; neutral fixtures; compatibility matrix; fail clearly on unsupported versions | AI integration / P01, every release |
| Runtime cannot restrict tools/reads sufficiently on a target OS | Unwanted host access | Demonstrate effective policy; supported isolated runtime; do not expose canonical data or developer checkout | Security / P01 |
| Assumed account/subscription access fails | User cannot play AI scenes | Supported authentication setup, account readiness, entitlement/error messages, no free/unlimited claims | Product + AI / P01 |
| Cloud inference mistaken for offline AI | Incorrect expectations/privacy claims | Explicit local-storage versus outbound-context documentation | Product / P14, P19 |
| AI text contradicts committed outcome | False player understanding | Narrate resolved bundles; structured claims; one repair; mechanical receipt fallback; human evaluations | AI + QA / P08, P18 |
| Secret leakage through prior thread context | Spoilers or private information exposed | Role/audience thread isolation and input filtering; sentinel tests across API/media/diagnostics | Knowledge + QA / P09, P18 |
| Long-session summary loses a promise or identity | Narrative discontinuity | Preserve structured obligations/provenance; fresh checkpoints; delayed recall tests | Memory / P09, P18 |
| Concurrent workers or retries duplicate actions | Corrupt resources/rewards | Journal keys, payload hash, leases/fences, state revisions, unique settlement constraints | Backend / P03, P07 |
| Genericity becomes arbitrary execution | Unsafe/unmaintainable packs | Bounded data/expressions; supported primitives; explicit extension contract | Content + Security / P04 |
| Encounter budget misses powerful combinations | Unfair or trivial fights | Combination caps, feasibility checks, simulations, human calibration, published limitations | Rules + QA / P13, P18 |
| Adaptive generation undermines player progress | Loss of trust | Freeze mechanics at entry; prebudget reinforcements; no secret counter-scaling | Rules / P13 |
| Too many modules delay a playable core | Endless design/development | Milestone gates, no optional vector/editor/media blockers, small complete combat slice | Product / every package |
| Pack upgrade invalidates an active campaign | Broken saves/abilities | Immutable snapshots, explicit migration preview, backups, dependency validation | Content + Operations / P16 |
| Event log mistaken for a fully replayable system | Unsupported recovery claims | Define tested replay scope; maintain current-state snapshots and schema versions | Backend / P03, P16 |
| Backups omit assets/content revisions | Incomplete recovery | Integrity manifest and fresh-storage restore rehearsal | Operations / P16, P18 |
| Remote media disappears or tracks requests | Broken presentation/privacy | Optional origins, local fallback, rights registry, no gameplay dependency | Presentation / P17 |
| Public release includes private demo material | Privacy/rights exposure | Neutral samples, explicit rights inventory, no recursive source copy | Owner / P19 |
| Stale documentation overstates implementation | Misleading public repository | One status matrix, evidence-linked milestones, docs review in CI | Maintainer / every milestone |
| Pinned backend line leaves open-source support or misses a dependency security fix | Unpatched framework or migration tooling | Spring Boot 4.1 open-source support ends 31 July 2027 and its patches keep Flyway 12.4; review advisories, plan the move to a supported line, and rerun PostgreSQL integration checks for any override ([ADR 0009](adr/0009-toolchain-baseline.md)) | Backend + Maintainer / every release, before July 2027 |
| Container runtime transport, account state, or socket permissions fail on the target host | Packaged launcher cannot provide playable readiness | Pin Linux image/client combination; verify IPC, supported sign-in, isolation, restart, and clear failure; do not substitute a host process silently | Integration + Operations / P01, P02, QA-28 |

## 2. Open decisions with safe working defaults

These are bounded decision records, not missing core specifications. Development can use the documented defaults after the applicable milestone review.

The repository-license decision was resolved on 22 October 2026: project software and documentation use the [MIT License](../LICENSE), with the upstream notice retained in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). Separate adventure-pack and asset rights still require their own provenance records.

| Decision | Working default | Resolve by | Evidence / authority |
| --- | --- | --- | --- |
| Publication naming checks | AI RPG Engine selected by the owner; repository slug `ai-rpg-engine` | Before public repository creation | Verify repository availability and applicable name/rights requirements |
| Exact dependency patches | Resolved by [ADR 0009](adr/0009-toolchain-baseline.md) for the frontend (Angular 22.2.3, TypeScript 6.0.3, Node.js 24.22.1 LTS, npm 11.19.1) and for the backend, build, and database (Spring Boot 4.1.2 on Java 21, Maven 3.10.0 through Maven Wrapper 3.3.4, Flyway 12.4.0, PostgreSQL 18.6 image by digest). JDK, engine/runtime images, Compose, OS, and shell versions remain | P00.3 for the remaining entries | Official compatibility and reproducible dependency matrix |
| Codex release baseline | App-server with restricted packaged IPC profile; local stdio probe | P01 | Actual container/client/account contract and isolation tests |
| Supported OS list | Windows with Linux containers/WSL2 first; other host/shell combinations unclaimed | P01, P02, and P19 | Platform-specific launcher, runtime, and persistence verification |
| Model IDs and effort mapping | Logical catalog-resolved profiles | P08 | Available account catalog and quality/latency evaluation |
| Content authoring syntax | YAML for mechanics, Markdown for prose, JSON equivalent support where documented | P04 | Parser safety, diagnostics, author usability |
| Defeat policy in neutral samples | Recoverable incapacitation; no automatic permanent death | P11 | Rules review and playtest |
| Combat numeric tuning | AI RPG Skirmish defaults in the combat document | P13 and P18 | Simulations and human testing |
| Renewal thresholds | Ten completed tasks; context pressure thresholds | P09 | Context measurements and continuity results |
| Asset/audio strategy | Local static assets; optional allowlisted streams | P17 | Rights, privacy, failure tests |
| Backup retention path/settings | Owner data directory, rolling snapshots, independent daily backups | P16 | Disk usage and successful restore |
| Semantic retrieval | Deferred | Only after measured recall failures | Structured/full-text benchmark and new dependency decision |
| Visual authoring editor | Deferred beyond v1 | Separate initiative | File-contract stability and author feedback |

## 3. Scope-change procedure

A change affecting authority, persistence, content compatibility, AI provider, combat scheduler, or hosting requires an ADR update and revised acceptance/roadmap mapping. A new pack's names, artwork, ordinary items, or supported abilities do not require rewriting engine architecture.

Record why a change is needed, what current behavior demonstrates the problem, which compatibility boundaries change, migration/recovery impact, and how to verify it. Do not silently adopt a source project's superseded decision merely because an old paragraph still contains it.
