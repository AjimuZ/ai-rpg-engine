# Risks and Open Decisions

P02.3 infrastructure evidence (23 October 2026): root Compose separates the
database and runtime networks/mounts and supplies the database secret only to
PostgreSQL. Account-free neutral markers survive recreation of both containers.
This reduces packaging uncertainty but does not close account/tool containment,
canonical campaign recovery, launcher readiness or QA-28. The first engine build
still requires the unavailable official Temurin refresh in ADR 0009. Local file
secret permissions/initialization remain P02.6 work; file mounts are not encrypted.
The third checkpoint verifies optional single-origin jar assembly and bundled
frontend notices independently. Container integration remains unverified and the
image gate still applies; jar HTTP tests do not establish full startup readiness.

## 1. Risk register

Owners below are implementation responsibilities, not assigned people. The project owner can combine roles in a solo project.

| Risk | Impact | Mitigation and evidence required | Owner / gate |
| --- | --- | --- | --- |
| Codex app-server protocol changes or unsupported behavior | AI integration unavailable | Pin adapter baseline; neutral fixtures; compatibility matrix; fail clearly on unsupported versions | AI integration / P01, every release |
| Runtime cannot restrict tools/reads sufficiently on a target OS | Unwanted host access | Demonstrate effective policy; supported isolated runtime; do not expose canonical data or developer checkout. The 0.164.0 image has no system `bubblewrap`, so the runtime falls back to its bundled copy; P01.3 must prove that this sandbox works with capabilities dropped and `no-new-privileges` | Security / P01 |
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
| Generated portraits/items drift, cross scopes or reveal secrets | Inconsistent identity or leaked hidden content | Stable public appearance identity, durable original assets, source/audience validation, stale-job rejection and restore checks | Presentation + QA / P17, QA-31 |
| Parallel adventures share names or stale callbacks | Cross-world content, memory or artwork mixing | Adventure/campaign ownership, distinct managed folders, scoped drafts/threads/jobs and same-name two-genre isolation checks | Domain + QA / P03, P06, P16–P17, QA-33 |
| Image generation is assumed available or spends without bounds | Unusable feature or unexpected account usage | Capability/account proof, explicit enablement and finite budgets, duplicate-job reconciliation and static/category fallback | Integration + Presentation / P01.6, P17.6 |
| Public release includes private demo material | Privacy/rights exposure | Neutral samples, explicit rights inventory, no recursive source copy | Owner / P19 |
| Stale documentation overstates implementation | Misleading public repository | One status matrix, evidence-linked milestones, docs review in CI | Maintainer / every milestone |
| Vulnerable frontend development tooling | Development-server exposure or local command execution | P02.2 pins transitive Vite 8.3.5 through npm overrides because Angular build 22.2.3 pins 8.3.0; full audit and Windows/Linux build/test checks gate the override. Reassess/remove when Angular includes the fixes ([ADR 0009](adr/0009-toolchain-baseline.md)) | Frontend + Maintainer / P02.4, every dependency update |
| Pinned backend line leaves open-source support or misses a dependency security fix | Unpatched framework or migration tooling | Spring Boot 4.1 open-source support ends 31 July 2027 and its patches keep Flyway 12.4; review advisories, plan the move to a supported line, and rerun PostgreSQL integration checks for any override ([ADR 0009](adr/0009-toolchain-baseline.md)) | Backend + Maintainer / every release, before July 2027 |
| Pinned base images lag security releases or drift through untracked updates | Known vulnerabilities in shipped images or unreproducible builds | Exact tag plus index digest for every image; refresh on security releases, milestones, and release candidates with build and integration checks. The first engine image must move to the Temurin 21.0.13 image; on 23 October 2026 the official tag API still returned no 21.0.13 tags, so P02.3 remains partial. Root and independent runtime context allowlists and real BuildKit fixture exports now protect against private-path inclusion; source-content secret review remains required ([ADR 0009](adr/0009-toolchain-baseline.md)) | Operations + Maintainer / P02.3, P02.4, every release |
| Container runtime transport, account state, or socket permissions fail on the target host | Packaged launcher cannot provide playable readiness | Pin Linux image/client combination; verify IPC, supported sign-in, isolation, restart, and clear failure; do not substitute a host process silently. P01.1 verified the IPC and its UID boundary for 0.164.0 on Docker Desktop (`linux/amd64`) without an account; sign-in, persistent account volumes, and restart remain open ([deployment gate](22-container-deployment-and-startup.md#3-runtime-integration-gate)) | Integration + Operations / P01, P02, QA-28 |

## 2. Open decisions with safe working defaults

These are bounded decision records, not missing core specifications. Development can use the documented defaults after the applicable milestone review.

The repository-license decision was resolved on 22 October 2026: project software and documentation use the [MIT License](../LICENSE), with the upstream notice retained in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). Separate adventure-pack and asset rights still require their own provenance records.

| Decision | Working default | Resolve by | Evidence / authority |
| --- | --- | --- | --- |
| Publication naming checks | AI RPG Engine selected by the owner and recorded with its derived identifiers in [ADR 0010](adr/0010-name-scope-format-licensing.md); repository `AjimuZ/ai-rpg-engine` exists (private). No trademark search has been done | P19.1, before public release | Trademark/name review and owner publication decision |
| Exact dependency patches | Resolved by [ADR 0009](adr/0009-toolchain-baseline.md) for the frontend (Angular 22.2.3, TypeScript 6.0.3, Node.js 24.22.1 LTS, npm 11.19.1); the backend, build, and database (Spring Boot 4.1.2 on Java 21, Maven 3.10.0 through Maven Wrapper 3.3.4, Flyway 12.4.0, PostgreSQL 18.6 image by digest); and the host, image, Compose, and shell baseline (Temurin 21 JDK/JRE and Node.js 24.22.1 images by digest, Docker Engine 29.0+, Compose 5.0+, Buildx, GNU Bash 4.4+). The narration runtime image and executable are pinned too: release 0.164.0 by checksum on a digest-pinned Debian 13 base (P01.1) | Resolved; refresh under the ADR 0009 policy | Official compatibility and reproducible dependency matrix |
| Codex release baseline | App-server 0.164.0 is pinned. P01.1 verified discovery without an account over stdio and over the packaged Unix socket, which uses WebSocket framing. Partial P01.2 verifies seven scripted turns across two threads, reconnect/resume, explicit upstream settings/schema, terminal states and interruption. Signed-in neutral inference, deeper isolation, process lifecycle and the retained compatibility matrix remain open | P01.2–P01.5 | Actual container/client/account contract and isolation tests |
| Supported OS list | Windows 11 x64 with Docker Desktop (WSL 2 backend, Linux containers) and Git Bash first; Bash in a WSL distribution is a candidate; other hosts and shells unclaimed ([ADR 0009](adr/0009-toolchain-baseline.md)) | P01, P02, and P19 | Platform-specific launcher, runtime, and persistence verification |
| Model IDs and effort mapping | Logical catalog-resolved profiles | P08 | Available account catalog and quality/latency evaluation |
| Content authoring syntax | Resolved by [ADR 0010](adr/0010-name-scope-format-licensing.md) as pack contract 1: UTF-8, YAML 1.2 core schema (`.yaml`, no anchors, aliases, tags, or duplicate keys), equivalent JSON (`.json`), CommonMark prose (`.md`, no front matter or rendered HTML), JSON Schema draft 2020-12, directory or `.zip` distribution | P04 implements and verifies | Parser corpus tests, diagnostics, author usability |
| Defeat policy in neutral samples | Recoverable incapacitation; no automatic permanent death | P11 | Rules review and playtest |
| Combat numeric tuning | AI RPG Skirmish confirmed as the implementable default ([ADR 0006](adr/0006-original-combat.md)); its numbers are the provisional defaults listed in the [combat register](11-combat-system.md#17-provisional-numerical-defaults), stored as ruleset-profile data | P13 and P18.3 | Simulations, statistical checks, and human playtests; changes ship as versioned ruleset revisions |
| Renewal thresholds | Ten completed tasks; context pressure thresholds | P09 | Context measurements and continuity results |
| Asset/audio strategy | Default visual novel and complete graphic kit; stable saved portrait/item identities; bounded v1 runtime images under [ADR 0011](adr/0011-visual-novel-and-persistent-artwork.md); audio remains optional | P05, P17 | Capability, rights, privacy, identity/recovery and failure tests |
| Backup retention path/settings | Owner data directory, rolling snapshots, independent daily backups | P16 | Disk usage and successful restore |
| Semantic retrieval | Deferred | Only after measured recall failures | Structured/full-text benchmark and new dependency decision |
| Visual authoring editor | Deferred beyond v1 | Separate initiative | File-contract stability and author feedback |

## 3. Scope-change procedure

A change affecting authority, persistence, content compatibility, AI provider, combat scheduler, or hosting requires an ADR update and revised acceptance/roadmap mapping. A new pack's names, artwork, ordinary items, or supported abilities do not require rewriting engine architecture.

Record why a change is needed, what current behavior demonstrates the problem, which compatibility boundaries change, migration/recovery impact, and how to verify it. Do not silently adopt a source project's superseded decision merely because an old paragraph still contains it.
