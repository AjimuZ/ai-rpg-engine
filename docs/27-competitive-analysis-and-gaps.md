# Competitive Analysis and Gap Register

## 1. Scope, evidence and interpretation

Research date: **23 October 2026**. This is a static source review and a design decision record. It does not report executed competitor applications, tests, model inference, security audits, accessibility certification, or comparative narrative benchmarks. No competitor code, media, character content, credentials or campaign saves are imported into the engine.

The five requested repositories and their issue trackers were accessible. Their default-branch heads were shallow-cloned into ignored local research storage for read-only inspection; no installation scripts or third-party code were executed. Commit links below identify the inspected bytes. Issue pages are dated observations and may change independently of those commits. Selected modules, schemas, test definitions and dependencies were inspected rather than every file. An unobserved feature means **not established by this review**, not proved absent from the entire project.

Evidence labels:

- **Source:** a path contains the described mechanism. This establishes implementation presence, not correctness in a running installation.
- **Test definition:** a checked-in test asserts the behavior. Its result was not reproduced here.
- **Claim:** README, architecture text or an issue describes behavior without sufficient inspected implementation evidence.
- **Design inference:** a recommendation drawn from the source, explicitly distinct from an upstream capability.

The local baseline was read first: product/requirements, architecture, domain, Codex/turn/memory contracts, packs, gameplay/combat/progression, UI, security, QA, roadmap, task profiles, assets/music, workspace isolation, editor roadmap and relevant ADRs. The neutral [Angular shell](../frontend/src/app/app.ts), [Spring entry point](../backend/src/main/java/org/rpgengine/EngineApplication.java) and [runtime probe inventory](../tools/runtime_probe/README.md) corroborate the documented boundaries. Current status and retained verification belong to [the roadmap](16-development-roadmap.md); this research does not rerun those checks or alter their completion state.

The resulting contracts live in [asynchronous narrative planning](28-asynchronous-narrative-planning.md), [localization](29-localization.md), [local speech](30-local-speech-synthesis.md) and [research evaluation](31-research-validation-and-evaluations.md). The [Italian executive report](32-research-executive-summary-it.md) explains the integrated decisions. Those specifications own their detailed contracts; this chapter owns the comparative evidence and gap rationale.

| Local maturity category | What the inspected baseline establishes |
| --- | --- |
| Documented | Versioned canonical transactions, knowledge scopes, renewable contexts, configurable original combat, VN interface and persistent artwork contracts exist as specifications |
| Planned | Campaign persistence/domain implementation, quest/NPC simulation, command pipeline, production Codex adapter, playable UI and media behavior remain roadmap work |
| Partially implemented | Packaging infrastructure is partial; P02.3 is partial, as recorded in the roadmap |
| Implemented with retained prior evidence | P00, P01.1–P01.5, P02.1–P02.2 have scoped evidence; the P01 live isolation recheck and package exit remain separate gates |
| Verified by this research | Source locations, revisions, inspected license texts and documentation relationships only; no new gameplay/runtime completion claim |

Do not compare a competitor's source presence with a local specification and conclude that the local engine already delivers equivalent gameplay. Conversely, a larger competitor feature list does not establish a stronger authority model or better narrative quality.

## 2. Repository and rights inventory

| Reference | Inspected revision | Commit timestamp | Architecture observed | Reuse boundary |
| --- | --- | --- | --- | --- |
| [Fugue](https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue) | [bf4578398ae174599b25dcb15aecb04a603e48d6](https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/commit/bf4578398ae174599b25dcb15aecb04a603e48d6) | 22 July 2026, UTC−04:00 | Browser JavaScript modules plus optional FastAPI/SQLite living-world and media services | Custom **GETFROGGYHOE FREE FRONTEND LICENSE v1.0** restricts paid redistribution/bundling; creative materials and third-party components have separate rights. Study ideas; do not transplant implementation/assets into the MIT engine. [FL] |
| [SakalioLabs Monogatari](https://github.com/SakalioLabs/Monogatari) | [d366539962bf99d14c94115eef45bccf2bcf4c36](https://github.com/SakalioLabs/Monogatari/commit/d366539962bf99d14c94115eef45bccf2bcf4c36) | 23 August 2026, UTC+08:00 | Rust game/authoring/assets/desktop crates, Vue/TypeScript Web/PWA surface, legacy C# implementation | MIT software license; attribution obligations remain if code is ever reused. Project art/models, example IP and provider/model rights need individual review. [ML] |
| [NarrativeEngine-P](https://github.com/Sagesheep/NarrativeEngine-P) | [43af40b3e92d9f585f14dfd250e3aaf565a0b008](https://github.com/Sagesheep/NarrativeEngine-P/commit/43af40b3e92d9f585f14dfd250e3aaf565a0b008) | 22 October 2026, UTC+07:00 | React/Zustand turn orchestration, Express services, JSON campaign/archive files and SQLite vector index | MIT software; model weights, supplied examples, mods and media are separate inventory entries. [NL] |
| [kiselyovd/dungeon-master-ai](https://github.com/kiselyovd/dungeon-master-ai) | [c3d11b09e186e0362477dde5d586d16ab8a34946](https://github.com/kiselyovd/dungeon-master-ai/commit/c3d11b09e186e0362477dde5d586d16ab8a34946) | 17 October 2026, UTC+03:00 | Rust domain/application/adapters, SQLite migrations, React/Tauri desktop, optional media sidecar | Root LICENSE is MIT; Cargo workspace metadata says MIT OR Apache-2.0. Resolve that metadata discrepancy before concrete code reuse. D&D/SRD data and assets need separate notices. Preserve AI RPG Skirmish. [KL], [K8] |
| [Mortyl/ai-dungeon-master](https://github.com/Mortyl/ai-dungeon-master) | [4251a5fcfb94597686596b4b19ad4de4df166bfc](https://github.com/Mortyl/ai-dungeon-master/commit/4251a5fcfb94597686596b4b19ad4de4df166bfc) | 15 June 2026, UTC+01:00 | Small Python/Pydantic rules engine and FastAPI/Anthropic loop, Next.js UI | No root LICENSE or explicit software reuse grant found in the inspected tree/README. Public visibility is not permission to copy. Conceptual comparison only. [DL] |

The requested **SakalioLabs/Monogatari** is the AI-native authoring/runtime project above. This report does not attribute features of the unrelated historic Monogatari JavaScript VN engine to it.

Licenses are reported from the pinned texts, not inferred from GitHub's repository badge. No legal compatibility clearance or exhaustive dependency audit is claimed. The engine's own [licensing ADR](adr/0010-name-scope-format-licensing.md) and asset admission process remain authoritative; no new dependency or license notice is needed merely for this conceptual research.

### 2.1 Dependency and model footprint

These are inspected declarations and code paths, not installed/resolved inventories or recommendations to add their packages. Weight licenses and hardware results require independent checks.

| Repository | Declared libraries/model paths | Cost, portability and maintenance implication |
| --- | --- | --- |
| Fugue | Root package declares scripts without Node dependencies [F8]; optional Python requirements include FastAPI, ONNX Runtime, Kokoro, PyTorch/diffusers/transformers [F8B]. Voice code supports Pocket/Kokoro paths [F5]. | “Local frontend” does not make its optional media stack small. Keep optional weights/workers outside the text-only engine; broad version ranges are not a reproducible deployment baseline. |
| Monogatari | Vue/Pinia, Tauri, Pixi/Live2D, Three.js and Transformers.js [M9]; Rust Tokio/Serde, ONNX Runtime DirectML, Rhai and ZIP/hash crates [M9B]. Browser default names Qwen2.5-0.5B ONNX q4 [M7B]. | Native/browser model adapters and renderer choices multiply packaging and compatibility paths. Retain Angular/Java and adopt only needed contracts; model capability/quality is not established by a default ID. |
| NarrativeEngine-P | React/Zustand, Express, better-sqlite3/sqlite-vec, Transformers.js, kokoro-js and tokenizer packages [N6C]; quantized CPU mxbai 1024-dimensional embeddings [N5], Kokoro ONNX q8 [N6]. | Real embedding/TTS integration also entails model cache, cold load, rebuild and event-loop scheduling. It supplies no evidence that another database/service is necessary for this engine. |
| kiselyovd | Rust Tokio/Axum, SQLx SQLite, genai and Stronghold [K8]; React/Tauri/Pixi/i18next/validation packages [K8B]; separate Python media dispatcher [K6]. | Domain/adapter separation is reusable as a design, but desktop process ownership/provider/credential infrastructure differs from the accepted Compose/Codex profile. Avoid duplicating it. |
| Mortyl | Pinned FastAPI/Pydantic/Anthropic/pytest Python dependencies [D6]; Next.js/React frontend [D6B]. The DM code selects Anthropic models [D2]. | A compact dependency graph aids inspection but does not supply durable campaign storage, recovery or an offline narrator. Do not import an alternate provider to obtain its simple tool-loop pattern. |

The useful inspected patterns above do not require adding a separate agent framework. Async language runtimes, queues, typed contracts and storage are sufficient building blocks for the proposed design; correctness still depends on admission and recovery policy.

## 3. Traceable source findings

### 3.1 Fugue

| ID and inspected files | Source finding and practical limit |
| --- | --- |
| [F1 — `core.js`][F1], [backend schema][F1B] | Settings use localStorage with an IndexedDB mirror; the Python service separately stores characters, events, messages, places and image assets in SQLite. The inspected character table keys by name. This is real persistence code, but not evidence of a single campaign-scoped transactional authority across browser/backend state. |
| [F2 — `contextMemory.js`][F2], [episodic summarizer][F2B], [older memory module][F2C] | The active API client imports lexical tiered retrieval over episode facts, memory and databank entries. It uses ASCII-oriented token matching and bounded text. Episode summaries derive from rendered chat chunks. The older memory module uses frequency vectors, a 100-entry cap and a placeholder summary; it must not be described as a proven semantic-memory system or the only memory path. |
| [F3 — `simulate_character`][F3], [world tick][F3C], [world events][F3B] | Needs, probabilistic schedules, nearby interactions, directed affinity/trust/suspicion, contact attempts and memory changes have concrete implementation. Some simulation uses wall-clock hour and generic location/text constants. Adapt the causal ideas using local campaign time, stable IDs and recorded random draws; do not import those assumptions. |
| [F4 — `VNCharacterEngine`][F4], [VN response test][F4B] | Body/face/hair/clothing layers and pose/expression paths provide deterministic visual variation. Test definitions check attributed dialogue and reject state-summary leakage. Layered art can preserve a supplied identity; this does not prove that independently generated portraits maintain a face. |
| [F5 — `voiceBridge.js`][F5], [Python audio routes][F5B] | Persistent character voice recipes/reference inputs, Pocket/Kokoro selection, queued synthesis, playback and a small in-memory cache are implemented. The Kokoro language mapping includes Italian. A selectable language is not evidence of natural Italian pronunciation, bilingual voice identity, model consent compliance or measured latency. |
| [F6 — `visualGen.js`][F6], [image job schema][F1B] | Entity/media keys, pending/ready/failed status, polling and regeneration exist. This supports nonblocking media UX as a pattern. The inspected paths do not establish dependency-sensitive stale quest-plan admission, campaign snapshot validation or measured image identity consistency. |
| [F7 — `apiClient.js`][F7], [state mutation pipeline][F7B], [living-world smoke definition][F7C] | Generation cancellation/context limits, response validation and AI-derived data application exist, with many cross-module dependencies. The smoke script makes real API mutations when run; it was only read. Browser-owned state mutation and wildcard CORS in the inspected backend are reasons to retain the stricter local authority/security contracts. |
| [F9 — battle][F9], [XP/economy][F10] | Action costs, turn advancement, enemy choices, reward distribution and XP/skill-point advancement have source implementations. Optional auto-battle can choose protagonist actions. Do not inherit that control policy, browser mutation or random/time assumptions into the local explicit-agency, recorded-draw contract. |

Fugue's strongest demonstrated contribution is connected presentation and social/world breadth. Its size and number of modules do not establish recoverable 500-turn continuity, deterministic seeded simulation, complete English/Italian UI coverage, or secure background planners. Those remain unmeasured here.

### 3.2 SakalioLabs Monogatari

| ID and inspected files | Source finding and practical limit |
| --- | --- |
| [M1 — browser turn orchestration][M1], [roleplay runtime][M1B] | The browser path generates an NPC response, guards it, then performs a separate evaluator call before deterministic state application. Scores, evidence, participant goals, bounded deltas, turn limits and transition targets have structured contracts. This is source-backed separation of responsibilities, **not** concurrent background quest planning. |
| [M2 — evaluator and transition logic][M2] | Evidence observations require a player quote; bounds and authored fallback signals constrain score/relationship changes. A quote's presence proves provenance, not that its semantic interpretation is correct. Adopt source-linked observations where useful, retaining independent legal-effect checks. |
| [M3 — character memory][M3], [versioned save manager][M3B] | Memory types, importance eviction and keyword recall exist. Save schemas preserve dialogue/roleplay/campaign cursors and character state with bounded files and path checks. Importance eviction is appropriate for a context view, not deletion of the local engine's canonical event history. |
| [M4 — quality-suite execution][M4], [quality-suite tests][M4B] | Structured reports bind suites/scenarios to hashes and engine/Git metadata; they report route/workflow coverage and guard/safety signals. These are useful reproducibility patterns. Checked-in tests are not evidence that an independent real-model playthrough passed in this research. |
| [M5 — agent transaction protocol][M5], [apply/validate boundary][M5B], [project archive reader][M5C] | Authoring mutations have explicit missing/SHA-256 preconditions, transaction plans, bounded validation and portable package inspection. Adapt the precondition principle to narrative dependencies; this source concerns authoring files, not proof of a campaign planner commit protocol. |
| [M8 — package manifest][M8], [archive reader][M5C] | Person-to-person project packages contain file sizes, SHA-256 inventory and a package fingerprint. Inspection can run before extraction, with count/size limits, portable-path/collision checks and secret-config scrubbing. Adapt this pattern for the owner's shareable adventure requirement; a checksum establishes integrity, not rights or trust. |
| [M6 — scene presentation][M6], [audio manager][M6B], [renderer selection][M6C] | Bounded dialogue placement, character anchoring, music/ambience/SFX preview and renderer alternatives are concrete UI paths. Audio manager/voice volume controls alone do not prove a multilingual TTS service. Live2D/3D support carries a larger asset/tooling burden than the required Angular VN stage. |
| [M7 — i18n][M7], [WebGPU inference][M7B] | UI locale registry contains English, Simplified Chinese, Japanese and Korean, with persisted choice and English normalization fallback. Translated README availability does not establish those languages in the UI, and Italian is not in this registry. Local browser inference is a distinct optional execution path, not proof that Codex can run offline. |

Monogatari's authored score/route model fits bounded VN scenes. AI RPG Engine can adapt its evidence/coverage discipline while retaining open situations, event predicates and alternative player solutions. Forced endings on a turn cap are not a suitable general RPG quest-completion rule.

### 3.3 NarrativeEngine-P

| ID and inspected files | Source finding and practical limit |
| --- | --- |
| [N1 — file store][N1], [write serializer][N1B], [pending commit][N1C] | Campaign files, temp-write/rename, per-process campaign serialization and pending-turn recovery markers exist. The in-memory lock explicitly disappears on restart. These are useful failure cases, but not equivalent to cross-process database fencing or an atomic multi-aggregate PostgreSQL commit. |
| [N2 — director brief][N2], [turn ordering][N2B] | The Director produces a compact correction brief with timeout/error fallback and per-input caching. It is awaited before story generation, with a 180-second utility timeout in the inspected implementation. A separate role is therefore not evidence of low-latency asynchronous planning. |
| [N3 — relationship memory][N3], [reading weights][N3B], [compaction][N3C] | Directed event/outcome memories distinguish an observable event from a response, emotional register and enduring impact; reading selects by relevance/participants/recency and protects important records. This is a stronger concrete treatment of relational episodes than a single affinity number. Generated interpretations still require source and visibility validation. |
| [N4 — slotted archive retrieval][N4], [vector service][N4B] | Ranked archive hits can feed both longer recalled scenes and short snippets, avoiding a second vector query. The snippet path filters witnessed scenes using on-stage identities and uses stored verbatim user snippets. Local adaptation must apply per-audience authorization: one present witness does not authorize every other present NPC to know an event. |
| [N5 — embedder][N5], [vector store][N5B], [vector tests][N5C] | A CPU quantized mxbai embedding model, SQLite/sqlite-vec, campaign-scoped search, diversity selection and model/version rebuild handling are real implementations. Model warmup and indexing yields are explicit costs. No inspected A/B evidence proves superior English/Italian recall over the local entity/full-text baseline. |
| [N6 — TTS][N6], [i18n registry][N6B], [package dependencies][N6C] | Kokoro ONNX q8, disk audio cache and several UI locales are implemented. The inspected audio hash uses text and voice, so local adaptation must also bind model/voice revision, locale, synthesis settings and workspace privacy. The UI registry includes English, Korean, Russian, Polish, Indonesian and pseudo-locale; not Italian. |
| [N7 — request guards][N7], [guard tests][N7B] | Host/fetch-site/origin defenses explicitly cover localhost request forgery and DNS rebinding. This supports the local threat model and regression cases; it does not justify replacing capability isolation with prompts. |

NarrativeEngine-P contributes especially useful relational-memory, retrieval-budget and recoverability examples. Its flexible mod/provider surface and file-backed orchestration solve different constraints from the Codex-only, declarative-pack, single-backend design; broad plugin execution is not an MVP requirement.

### 3.4 kiselyovd/dungeon-master-ai

| ID and inspected files | Source finding and practical limit |
| --- | --- |
| [K1 — operation contracts][K1], [SQLite operation repository][K1B], [initial operation migration][K1C] | Durable request payloads, running/completed/interrupted states, event sequence receipts and restart/resume checks exist. Recovery binds restore generation, actor and original environment; it can repair delivery from a tool receipt without executing the tool again. This closely supports authority and idempotency decisions already specified locally. |
| [K2 — command planning][K2], [human-command contract][K2B] | Free-text intentions can be converted to a bounded manifest linked to exact player-text spans and scope. Its name “planning” means human command decomposition, not deep quest generation. Adapt provenance/clarification tests without turning one text request into unapproved extra protagonist actions. |
| [K3 — narration verifier][K3], [public context][K3B] | A separate publication check represents unconfirmed effects, invented mechanisms, omitted discoveries and unrequested hero actions against fact IDs/quotes. Scoped public intention context is distinct from trusted DM data. The extra model call costs latency and can make correlated mistakes; measure before making any LLM reviewer mandatory. |
| [K4 — deterministic adventure][K4], [combat resolver][K4B] | The source has deterministic combat and authored progression, but the inspected domain adventure encodes Bell locations/facts/endings in Rust. Do not interpret that as evidence of a generic declarative adventure engine. Preserve local pack/ruleset separation and original rules. |
| [K5 — boundary policy][K5], [operation failure test][K5B] | Dependency allowlists and SQL-trigger failure injection are concrete maintainability/test patterns. The test checks that a terminal event cannot precede its durable completion receipt. This is a valuable test to adapt; no upstream suite was executed. |
| [K6 — GPU dispatcher][K6], [media API][K6B] | The Python sidecar serializes load/unload/generation under a GPU lock and exposes unloading. This supports an optional media worker and shared-resource arbiter as a design pattern. It is not an RTX 4070 TTS/image benchmark. |
| [K7 — i18n bootstrap][K7], [locale parity test][K7B] | English/Russian resources and parity tests are present. Production initialization/fallback is Russian in the inspected bootstrap, while tests initialize English. Adapt parity testing, retaining **English default and Italian first-class** as local requirements. |

This project supplies the strongest inspected reference for durable tool-operation recovery and enforced architectural boundaries. It does not establish that its complete framework, tabletop content, co-op or local-model runtime would improve the required local stack.

### 3.5 Mortyl/ai-dungeon-master

| ID and inspected files | Source finding and practical limit |
| --- | --- |
| [D1 — rules engine][D1], [state model][D1B], [unit tests][D1C] | Typed actions, copy-on-apply state, injectable random source, inventory/lock/combat rules and tests are easy to inspect. The small action set is not a complete RPG progression/quest system. Successful look/inventory handlers also pass through a turn-incrementing path; retain local read-only semantics. |
| [D2 — DM loop][D2] | The synchronous Anthropic loop exposes a finite action tool set and bounds tool rounds; state facts return to the narrator. Each turn rebuilds a current-state prompt, rather than implementing a provenance-linked campaign memory architecture. This is a clear prototype, not a concurrency reference. |
| [D3 — FastAPI store][D3] | `GAMES` is a process-local dictionary and CORS permits any origin. Persistence across turns is real in one process; durability across restart and production security are not established. The README lists PostgreSQL and lore RAG as future work. |
| [D4 — objective/subjective evaluations][D4], [scenario fixtures][D4B] | Seeded scripted engine scenarios are separated from an optional advisory LLM prose judge. The judge request contains player input and narration, not the canonical state snapshot, limiting its ability to verify factual coherence. Adopt the separation; use independent source-aware checks and bilingual human review locally. |
| [D5 — frontend][D5] | A scrolling narrative, free-text input and state-driven location/health/inventory panel exist. No inspected evidence establishes dynamic VN composition, persistent voices, stable generated identities or localization. |

## 4. Issue tracker and maintenance observations

Issue titles, open status and closed status are reports, not reproduced bugs or proof of a fix. Retrieval was unauthenticated and read-only on 23 October 2026; no issue/comment was posted.

| Repository | Tracker observation | Design implication |
| --- | --- | --- |
| Fugue | [#7](https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/issues/7) reports a black initial scene; [#8](https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/issues/8) reports a broken secondary-route connection/model list. Both were open when inspected. | Give the first scene an intentional readable fallback; exercise settings-to-runtime wiring, rather than only individual modules. Do not copy diagnostic content or account fields from issues. |
| Monogatari | The [issues endpoint](https://api.github.com/repos/SakalioLabs/Monogatari/issues?state=all&per_page=6) returned no entries. | No independent issue-history evidence available from that request; do not infer stability or active support from an empty tracker. |
| NarrativeEngine-P | [#50](https://github.com/Sagesheep/NarrativeEngine-P/issues/50), closed when inspected, describes inconsistent ComfyUI settings/response handling between scene and NPC portrait paths. | Define one typed media port for all subject categories; a closed report is not an independently verified corrected installation. |
| kiselyovd | The [tracker](https://github.com/kiselyovd/dungeon-master-ai/issues) includes a dependency dashboard and closed broken-link reports; recent entries also include pull requests. | Separate dependency/documentation activity from gameplay-quality evidence. The code-level boundary and failure tests are stronger evidence than issue count. |
| Mortyl | The [issues endpoint](https://api.github.com/repos/Mortyl/ai-dungeon-master/issues?state=all&per_page=6) returned no entries. | No independent user-issue evidence retrieved; infer neither health nor abandonment. |

Maintenance activity is only a snapshot: the inspected heads span June–October 2026. This review makes no support-life guarantee. Library manifests demonstrate dependencies, not that their currently resolved versions are safe or suitable to add to this engine.

## 5. Competitive feature matrix

Abbreviations: **F** Fugue; **M** Monogatari; **N** NarrativeEngine-P; **K** kiselyovd; **D** Mortyl. A source ID links to the exact inspected commit/file. **NE** means not established in the selected source. All local gameplay entries below are **specified/planned**, unless section 1 explicitly identifies narrower retained implementation evidence. Recommendations concern independently designed patterns, not source transplantation.

### 5.1 Narrative, state and mechanics

| Area | Competitor evidence | Local coverage and relative assessment | Recommendation and player benefit |
| --- | --- | --- | --- |
| Narrative quality | M guards/route suites [M1], [M4]; N Director [N2]; K publication verifier [K3]; D advisory judge [D4] | [Task contracts](21-ai-task-contracts-and-prompts.md), [QA](15-quality-and-acceptance.md). No comparative prose results establish a winner. | **Adapt:** grounded fact/violation reports and reproducible fixtures. Measure quality before extra calls. |
| Player freedom | F freeform system integration [F7]; M scene goals/transitions [M1B]; K command spans [K2B]; D action tools [D2] | [Agency/commands](07-turn-protocol.md), [quests](10-gameplay-modules.md). Local explicit consent and alternative resolutions are at least as strong on paper; unimplemented. | **Adopt/retain:** source-linked intent; clarify ambiguous costs. **Reject:** mandatory story outcomes based only on elapsed dialogue turns. |
| AI orchestration | M generator→evaluator [M1]; N Director awaited [N2B]; D bounded tool loop [D2] | [Codex](06-codex-integration.md), [roles](21-ai-task-contracts-and-prompts.md). Background durable quest planning is a real specification gap, not a verified competitor advantage. | **Adapt:** task boundaries, bounded calls/fallback; design independent asynchronous jobs and prove latency separately. |
| Canonical state | F browser mirror + backend SQL [F1], [F1B]; N JSON+lock [N1], [N1B]; K SQL operation receipts [K1B]; D dictionary [D3] | [Domain](05-domain-model.md), [transactions](07-turn-protocol.md), ADR 0003 already choose PostgreSQL/backend authority. | **Retain:** modular monolith and atomic receipts; **adapt** K's failure/recovery tests. No benefit from replacing the stack. |
| Character intelligence | F needs/schedules [F3]; M participant goals [M1B]; N directed memories [N3]; K authored actions [K4] | [NPC/social](10-gameplay-modules.md), [knowledge](08-memory-and-continuity.md). Personality prose alone cannot establish believable behavior. | **Adapt:** stable goals, observed motives and event-triggered episodes; evaluate behavior consistency rather than “intelligence” labels. |
| NPC memory/relationships | F trust/suspicion [F3]; M bounded memories [M3]; N formative/carried episodes [N3B] | Local relationships, commitments and provenance already specified; selection of relational episodes is less concrete. | **Adapt:** event-linked directed episodes with bounded salience; retain immutable source and distinguish belief from fact. |
| Autonomous NPC activity | F schedule/needs interactions [F3]; N Director corrections [N2]; M scene participant goals [M1B] | [World clocks](10-gameplay-modules.md) already specify lazy, causal world-time boundaries. | **Retain/adapt:** deterministic schedules first, selective candidate planning later; avoid one model session per NPC. |
| Dynamic quests | F state mutation/generic systems [F7B]; M authored scene graph [M1B]; K Bell fact graph [K4]; D richer quests are a claim/future scope | [Quest predicates](10-gameplay-modules.md), [encounter snapshots](12-encounters-and-progression.md). No inspected source proves safe open-ended background quest plans. | **Adapt:** versioned situations, clues, motivations and optional beats with explicit dependencies; avoid importing a fixed route engine. |
| Plot planning | N Director prepares the next brief [N2]; M authored node/ending policies [M1B] | Existing encounter planning is not a quest-plan contract. | **Adopt as local design extension:** progressive situation plans with stale-result validation. Do not call this competitor-proven. |
| World simulation | F world ticks and shared relationships [F3]; N story movement/director paths [N2B]; K deterministic adventure [K4] | Local clocks, witnesses, factions and bounded catch-up already cover foundations. | **Retain:** causal time/events; **defer** large emergent politics/economy until simulated scenarios show value. |
| Combat | F battle mechanics/modules [F9]; K deterministic resolver [K4B]; D seeded attack handler [D1] | [AI RPG Skirmish](11-combat-system.md) already specifies an original complete bounded loop. | **Retain:** original backend rules. **Adapt:** independently designed seeded/failure tests; no D&D rules/content dependency. |
| Progression | F XP/skill-point mutation [F10]; M relationship scores [M2]; K authored fact/reward outcomes [K4] | [Progression](12-encounters-and-progression.md) has entitlements, thresholds, one-time settlements. Relationship scoring is not equivalent to progression. | **Retain:** deterministic rewards/costs. Competitor feature breadth does not justify more classes or skill trees before calibration. |
| Inventory/equipment | F shared mutation modules [F7B]; K combat/loadout source [K4B]; D take/use rules [D1] | [Gameplay](10-gameplay-modules.md), [domain invariants](05-domain-model.md) cover ownership, slots, consumption and atomicity. | **Retain/adapt:** cross-mode identity/ownership tests; artwork and prose never award bonuses. |
| Adventure authoring/sharing | M graph validation, transactions and portable packages [M5], [M5C], [M8]; K authored domain constants [K4]; D Python data [D1B] | [Packs](09-adventure-packs.md), [workspaces](25-adventure-workspaces.md), [editor E01–E06](26-adventure-editor-roadmap.md). M is ahead in visible authoring source, not proof of better generic RPG mechanics. | **Adapt:** previews, diagnostics and person-to-person adventure bundles usable without the editor. Keep graphical workbench post-core and separate adventure export from save/credential export. |

### 5.2 Presentation, continuity and engineering

| Area | Competitor evidence | Local coverage and relative assessment | Recommendation and player benefit |
| --- | --- | --- | --- |
| Visual-novel composition | F layered expressions [F4]; M bounded dialogue/character layout and renderers [M6], [M6C]; D story/status UI [D5] | [VN UI](13-api-and-ui.md), ADR 0011 already require dominant scene, attributed passages, history and persistent draft. | **Adapt:** scene/character/expression cues and deliberate unavailable-art states; keep tactical controls integrated. |
| Suggested replies/player input | F generation/client paths [F7]; M freeform roleplay [M1] | Local editable quick replies + explicit Send + never overwrite draft are already unusually precise [UI](13-api-and-ui.md). | **Retain:** agency-safe composer and read-only Continue. No inspected competitor proof warrants auto-submit. |
| Dynamic illustrations | F keyed jobs/status [F6]; N media-path issue #50; K GPU sidecar [K6] | [Assets](23-presentation-assets.md) already specify asynchronous identities, stale results, cost/capability gates. | **Adapt:** shared job/status/media port; reuse rather than generate per message. |
| Stable appearance | F deterministic sprite layers [F4]; M supplied sprite/model selection [M6C]; generated-face stability NE | Persistent identity/reference/variant policy exists [assets](23-presentation-assets.md). Reusing an accepted asset is stronger than repeating a prompt. | **Retain/adapt:** accepted originals and bounded variants; benchmark reference-conditioned changes. No guarantee from a seed alone. |
| Voice synthesis | F recipes, language mapping and queue [F5]; N Kokoro/cache [N6]; M voice slider is not a TTS proof [M6B] | Previously excluded initial voice scope needs the separately authorized local TTS design; core text remains complete. | **Adapt:** durable voice IDs, bounded queues, subtitles/cache/fallback. Do not infer Italian quality or reuse voices from availability. |
| Music/sound | F media bridge/presentation ecosystem [F5]; M BGM/ambience/SFX manager [M6B] | [Music/credits](24-dynamic-music-and-credits.md) already covers public cues, dedup, user activation and rights. | **Retain/adapt:** declarative registered cues; no model-generated URLs or hidden-threat soundtrack. |
| Localization | F Italian TTS selector [F5], M 4 UI locales [M7], N locale registry [N6B], K English/Russian [K7] | Previous “English UI, other pack locales” assumption is insufficient for the owner's English/Italian initial-release requirement. | **Adopt:** complete English/Italian catalogs, persistent preference, direct language generation and glossary tests. No inspected project proves complete Italian UX. |
| Long campaign continuity | F lexical/chunk recall [F2]; M save cursors/eviction [M3]; N archive/relationship tiers [N3], [N4]; K receipts/recovery [K1] | [Memory](08-memory-and-continuity.md), [QA](15-quality-and-acceptance.md) already require renewal, sources and a 500-turn scenario. | **Adapt:** relationship salience and coverage/provenance reports; preserve canonical history. No 100/500-turn superiority result observed. |
| Performance/inference cost | F context bounds [F7]; M sequential evaluator [M1]; N awaited director and CPU indexing yields [N2], [N5]; K reviewer [K3] | [Routing budgets](21-ai-task-contracts-and-prompts.md), local/backend/provider measurements in [QA](15-quality-and-acceptance.md). | **Adopt:** task-count/token/queue/cancellation accounting and A/B scenarios. More agents can worsen responsiveness; prove overlap and cost. |
| Offline capability | F reduced mode is a README claim with local paths; M WebGPU [M7B]; N CPU embeddings/TTS [N5], [N6]; K optional media [K6]; D deterministic engine [D1] | Codex remains primary; no offline narrative runtime is specified. Deterministic reads/rules and installed media can remain available. | **Retain:** explicit offline limitations. **Reject for initial scope:** an alternate LLM provider merely for parity. |
| Security | F wildcard CORS/backend and browser mutations [F3], [F7B]; N localhost guards [N7]; M precondition/path validation [M5]; K public context [K3B]; D permissive CORS [D3] | [Security](14-security-and-operations.md), ADR 0013 give stronger specified boundaries, with limited runtime proof only. | **Retain/adapt:** strict origin/host/CSRF, no privileged planner tools, public-safe presentation; never treat prompt checks as a sandbox. |
| Maintainability/modularity | M distinct crates/frontends/legacy paths [M1B], [M5]; N many turn/memory services [N1C], [N2B]; K enforced dependency map [K5]; D small pure core [D1] | [Architecture](04-architecture.md) already chooses packages/ports inside one Java application. | **Adapt:** architecture checks and narrow contracts; **reject** framework replacement or microservice fan-out without evidence. |
| Extensibility | M typed content/authoring transactions [M5]; N provider/mod dependencies [N6C]; K domain/adapters [K5] | Local bounded expressions/primitives and versioned packs, with later E-series editor. | **Retain:** explicit extensions with compatibility gates; defer executable mods, provider marketplace and arbitrary agent workflows. |
| Recovery/export | M versioned saves/package inventories [M3B], [M5C]; N temp-rename/pending marker [N1], [N1C]; K durable receipt repair [K1B]; D restart durability absent [D3] | [Domain](05-domain-model.md), [memory](08-memory-and-continuity.md), QA-17/18/23/33. | **Adapt:** interrupted-delivery, restore-generation and manifest tests. A restorable campaign includes plans/assets/voices, not only transcript. |

## 6. Adoption costs and prerequisites

Complexity/risk/testing/maintenance use relative **L/M/H** estimates, not delivery dates. Runtime cost is incremental to the existing interactive narrator. An added model call always has quota and latency cost even if the owner's subscription has no per-request invoice. All proposals require independently authored implementation; source/media reuse requires a separate rights inventory. F-based recommendations remain conceptual under [FL]; M/N/K code reuse would require notices and dependency review; D remains conceptual-only under [DL].

| Candidate | Decision | Local dependency/phases | Benefit | Complexity / implementation risk | Runtime cost | Testing / maintenance |
| --- | --- | --- | --- | --- | --- | --- |
| Durable operation and delivery receipts [K1] | Adopt pattern already specified | P03/P07/P08, canonical transaction/outbox | No duplicated effects after retry or lost narration | H / H | Low DB overhead; no extra inference | H / M: commit/failure/restart matrix |
| Source-linked free-text intent [K2], [M2] | Adapt | P07/P08, public entity IDs, clarification policy | Actions reflect what the player actually asked | M / M | Small structured output; no mandatory extra classifier | H / M: Unicode EN/IT quote spans, ambiguous targets, forged evidence |
| Headless narrative-route fixtures [M4], [D4] | Adapt | P04/P08/P14/P18, neutral packs, seeded tools | Reproducible regression evidence | M / M | None for recorded/deterministic run; bounded opt-in inference | M / M: keep expected facts independent of generated prose |
| Directed relational episodes [N3] | Adapt | P03/P09/P15, event/knowledge provenance | NPC responses recall meaningful shared history | M / M | Deterministic ranking first; selective extraction only | H / M: consent, source, false memory and scope tests |
| Bounded witness-aware retrieval [N4] | Adapt | P09, audience filters before ranking | Fewer irrelevant recalls and fewer accidental spoilers | M / H | Low relational/full-text query cost | H / M: one witness must not authorize all actors |
| Semantic index [N5] | Defer | After retrieval benchmark, privacy/provider/weights ADR | Possible better paraphrase recall if demonstrated | H / M | Model load/RAM, embedding/indexing and rebuild cost | H / H: bilingual recall, stale index, authorization, migrations |
| Deep quest jobs and dependency admission | Adopt local design extension | Foundations P03/P07/P08/P09; small P14 example; P15 expansion | Important quests can remain coherent while play proceeds | H / H | Selective extra calls, bounded queue and stale waste | H / H: revision races, restart, quotas, player bypass |
| Always-on Director/reviewer [N2], [K3] | Reject as mandatory; optional measured experiment | P15/P18 after deterministic validation | Potential causal/character review on difficult arcs | H / H | At least one additional call per reviewed task | H / H: correlated errors and false rejection |
| Declarative presentation cues [F4], [M6] | Adapt | P05/P08/P17, public event/asset registry | Expressions and sound follow the scene predictably | M / M | No extra inference needed for mechanical cues | M / M: stale delivery, reduced motion, missing assets |
| Layered character wardrobe/expressions [F4] | Defer full compositor; adapt simple variants | P17, original asset pipeline, accepted identity | Recognizable character variation | H / M | Low playback; substantial asset-production cost | H / H: per-pose/per-body combinations and accessibility |
| Local speech, stable voice registry [F5], [N6] | Adapt optional feature | P17 after bilingual text/identity/cache foundations | Recognizable narrator/NPC voices with subtitles | H / H | Hardware/model queue/storage; no gameplay authority | H / H: Italian pronunciation, voice drift, consent, fallback, GPU contention |
| Shared media port/job status [F6], [K6] | Adapt | P03/P05/P17, subject/appearance/revision keys | Clear progress with reusable images/audio | M / M | Bounded media jobs, reuse saves inference | H / M: response shape, stale results, duplicate jobs, restore |
| English/Italian catalog parity [M7], [N6B], [K7] | Adopt local requirement | P04/P05/P08/P14/P18 | Complete initial bilingual experience | M / M | UI lookup low; direct narration avoids translation call | H / M: keys, placeholders, glossary, natural language review |
| Authoring preview/transaction manifests and portable bundles [M5], [M8] | Adapt data foundations/sharing; defer full workbench | P04/P16.2, editor E01–E06 after core v1 | Diagnostics, portable authoring and person-to-person sharing | H / M | Local validation; optional later completion tasks | H / H: graph integrity, import limits, rights, migrations and secret exclusion |
| Localhost defense/boundary checks [N7], [K5] | Adopt pattern already required | P02/P07/P18, HTTP/security policies | Protect local saves/account boundaries | M / H | Low validation overhead | H / M: real HTTP adversarial cases, dependency rules |
| Social feed/phone/home/family subsystems [F3], [F7] | Defer | Post-v1 unless a pack demonstrates a supported primitive need | Genre-specific immersion | H / H | Potential simulation/model churn | H / H: multiplying persistence and UI interaction paths |
| Alternate engine stack, broad AI framework or one-agent-per-NPC | Reject for initial scope | Would invalidate current ADRs/deployment/rules boundary | No demonstrated net benefit for the requested product | H / H | Higher operations and inference demand | H / H: large unsupported compatibility surface |

## 7. Prioritized gap register

This register records design gaps and explicit non-gaps. It creates no checked implementation milestone. Canonical requirement IDs and final QA/task identifiers remain in [requirements](03-requirements-and-scope.md), [QA](15-quality-and-acceptance.md) and [roadmap](16-development-roadmap.md), avoiding a second contradictory requirements catalog.

| ID | Gap/category and disposition | Priority / player value | Dependencies and scope | Cost / risk / testing / maintenance |
| --- | --- | --- | --- | --- |
| GAP-01 | **Missing:** complete first-release English/Italian UI/content policy; old English-only assumptions need supersession | MVP critical / H | P04 pack locale metadata; P05 settings/catalogs; P08 direct-language output; P14 both-language smoke | Runtime L; complexity M; risk M; testing H; maintenance M |
| GAP-02 | **Weak:** “background job” exists for summaries/media, but quest-job snapshot/admission/recovery contract is not equivalent | v1 critical foundation; optional P14 demonstration / H | P03 durable jobs/snapshots; P07 admission; P08 routing; P09 scopes; MVP can record unavailable planning and retain baseline fallback | Runtime bounded extra calls; complexity H; risk H; testing H; maintenance H |
| GAP-03 | **Weak:** a global revision mismatch alone would overreject useful plans; unrelated changes must remain admissible | Required whenever a planner result is admitted; v1 foundation / H | Exact dependencies, protected read set, predicates, pack/ruleset hashes and final backend recheck | Runtime L validation plus bounded replan; complexity H; risk H; testing H; maintenance M |
| GAP-04 | **Weak:** narrative premise/optional beats/secrets are not a versioned quest situation contract | v1 critical minimum contract/fallback; richer planning optional / H | Reuse quest identity/predicates/knowledge; deterministic contract/fallback fixtures suffice while live planning is deferred; no prescribed protagonist choices or linear full plot | Runtime per selected plan; complexity H; risk M; testing H; maintenance M |
| GAP-05 | **Missing:** explicit “plan not ready” quest behavior and policy preventing speculative canon leakage | MVP critical / H | Authored/basic situation remains playable; future content admitted only after valid observation/trigger | Runtime L; complexity M; risk H; testing H; maintenance M |
| GAP-06 | **Weak:** multi-role queue priority/quota behavior is not proven by independent thread support | MVP critical safe sequential fallback; v1 critical bounded scheduling / H | Codex compatibility gate, logical sessions, deadlines/cancel, durable result IDs; measured concurrency optional | Runtime bounded; complexity H; risk H; testing H; maintenance H |
| GAP-07 | **Already planned, strengthen:** role/audience memory separation, provenance and summaries | MVP critical / H | Existing P09 and QA-08/09/16; add plan assumptions/private clues without merging narrator contexts | Runtime bounded context; complexity H; risk H; testing H; maintenance M |
| GAP-08 | **Already planned, strengthen:** long-campaign evaluation lacks advanced-planning A/B fixture and EN/IT pair | v1 critical / H | Existing 500-turn suite plus 100-turn subset; compare baseline/queued planner with fixed facts and equal narration budgets | Evaluation cost H; complexity M; risk M; testing H; maintenance M |
| GAP-09 | **Missing:** persistent speech identities, multilingual cache and media-resource contract | v1 optional / M | Optional P17 local worker; durable registry/cache; text-only fallback; no TTS gate on MVP | GPU/RAM/storage variable; complexity H; risk H; testing H; maintenance H |
| GAP-10 | **Weak:** stable generated appearance needs evidence beyond prompt/seed repetition | v1 critical for existing FR-030 contract / H | P17 accepted identity/reference/variant compatibility, provenance and same-entity reload/restore tests | Media cost bounded; complexity H; risk H; testing H; maintenance H |
| GAP-11 | **Already planned, strengthen:** presentation records/media cues need a closed versioned instruction vocabulary | MVP critical base cues; v1 optional richer effects / M | P05/P08/P17; registered asset IDs, message/source identity, no scripts/URLs/raw CSS in AI output | Runtime L; complexity M; risk M; testing M; maintenance M |
| GAP-12 | **Weak:** relationship values have causes, but retrieval should preserve formative episodes/commitments | v1 critical basic event-linked commitments; v1 optional salience tuning / H | P09 event lookup, P15 relationships, audience authorization; deterministic ranking first | Runtime L–M; complexity M; risk M; testing H; maintenance M |
| GAP-13 | **Already planned:** original combat, inventory, one-time rewards and pack versioning are not new competitive gaps | MVP critical / H | Preserve P03/P04/P07/P10–P14 and frozen-encounter rules | No new feature overhead; existing complexity/testing H |
| GAP-14 | **Duplicate proposal:** another vector store alongside PostgreSQL | Post-v1 experiment / uncertain | Only after documented entity/full-text failures, bilingual benchmark, rights/privacy review; pgvector candidate before separate service | Embedding/index cost M; complexity H; risk M; testing H; maintenance H |
| GAP-15 | **Already planned:** graphical authoring is valuable but already assigned E01–E06 | Post-v1 / M | File contract, playable core and core v1 first; reuse same schemas and validation | Runtime optional completion tasks; complexity H; risk M; testing H; maintenance H |
| GAP-16 | **Rejected:** mandatory reviewer or multiple model agents for every dialogue | Rejected / unproven | Deterministic/schema checks and targeted evaluation first; optional complex-plan review only after measured benefit | Avoid recurrent extra calls, false rejections and correlated-error burden |
| GAP-17 | **Rejected:** background agents receive DB/filesystem/shell/network mutation privileges | Rejected / harmful | Existing backend authority and restricted Codex runtime; jobs return untrusted typed proposals | Would create high security/recovery/testing burden with no required player benefit |
| GAP-18 | **Deferred:** household/phone/social network, full wardrobe compositor, Live2D/3D, broad politics | Post-v1 / genre-dependent | Demonstrated pack/player need, reusable primitives, rights/asset budget; no core-v1 blocker | High feature interaction, asset, simulation and maintenance cost |
| GAP-19 | **Rejected:** change stack, combat ruleset or provider to match a competitor | Rejected / no evidence | Retain Angular 22, Spring Boot 4/Java 21, PostgreSQL 18, Compose, Codex and AI RPG Skirmish | Avoid duplicate infrastructure/contracts and unrelated license/content dependencies |
| GAP-20 | **Weak:** feature-performance claims need separated evidence levels | MVP critical evidence policy; v1 critical measurements / H | Existing QA policy; record source presence, fixture replay, real model, real browser, hardware benchmarks separately | Runtime evaluation only; complexity M; risk M; testing H; maintenance M |
| GAP-21 | **Explicit owner clarification:** import/export and share editor-authored adventures person-to-person | v1 critical portable file contract; editor interface post-v1 / H | FR-037, P04/P16.2 and editor E05/E06; checksummed content/media manifest, compatibility/rights preview, fresh workspace identity; exclude account settings, runtime IDs and saves by default | Runtime L; complexity M; risk H for unsafe imports/privacy; testing H; maintenance M |

“MVP critical” applies to the smallest safe contract and demonstrable slice. It does not promote broad autonomous simulation, rich audiovisual generation, an editor, semantic retrieval or parallel inference into playable-core prerequisites. The existing P00–P19 sequence and E01–E06 separation remain intact.

## 8. Strongest product differentiators to validate

These are intended combinations, not claims of market originality or measured superiority. Competitor-inspired parts are labeled; the integration choices are local design proposals.

| Differentiator | Player benefit and comparison | Effort, operating cost and phases | Evidence that would justify the claim |
| --- | --- | --- | --- |
| Responsive play with causally prepared quests | Deep clues/motives can be ready for future actions while the player continues. M routes and N Director inform the separation, but neither inspected path proves safe asynchronous RPG plans. The local dependency-admission combination is a design extension. | H effort/race risk; selective bounded planning calls; P03/P07/P08/P09, one P14 case, P15 breadth | Better human quest-coherence scores at measured cost; no increased interactive p95 beyond declared budget; zero stale resurrection/forced-choice commits |
| NPC relationships grounded in shared history | A remembered promise or betrayal affects later dialogue without making everyone omniscient. Adapt N's directed relational episodes and F's social breadth under local provenance/knowledge rules. | M–H effort; deterministic selection first; P09/P15/P18; bounded optional extraction | Delayed-promise recall and human character-consistency ratings in both languages; zero scope sentinel leaks; enumerate false memories |
| Recoverable long campaigns with inspectable consequences | Saves, combat, accepted plans and visual/voice identities survive interruption. K receipts and M package manifests reinforce existing local authority/restore decisions. | H engineering/testing; low ongoing DB overhead; P03/P07/P09/P16/P18 | Fresh-storage restore, lost-response and renewal scenarios preserve exact settled effects, pending decision and identity hashes across 100/500 turns |
| Coherent bilingual VN presentation | English or Italian play with attributed speech, consistent names, stable art and optional recognizable voices. F/M supply useful presentation patterns; full Italian end-to-end quality is not established in any inspected source. | M–H text/UI effort; optional media cost H/variable; P04/P05/P08/P14/P17/P18 | Native-language review, UI key/placeholder parity, no unintended language switch; pronunciation and voice stability measured separately from text correctness |
| Controlled richness on one maintainable local installation | Profiles can trade background richness for latency/resource use without changing canonical rules. Adapt N indexing-yield and K GPU serialization ideas, preserving the existing modular backend. | M–H resource/control effort; bounded queue/idle media cost; P08/P15/P17/P18 | Economy/Balanced/Cinematic fixture reports show task counts, stale waste, queue delay, GPU/RAM use and text-only recovery; no unbounded queued work |

## 9. Decisions preserved and open evidence

Preserve [ADR 0001](adr/0001-modular-stack.md), [canonical transactions](adr/0003-canonical-transactions.md), [renewable memory](adr/0005-renewable-memory.md), [original combat](adr/0006-original-combat.md), [audience isolation](adr/0007-audience-isolation.md), [VN/assets](adr/0011-visual-novel-and-persistent-artwork.md) and [runtime isolation](adr/0013-narration-runtime-isolation.md). The competitive review supplies no evidence to replace the stack, add a vector database, expose host tools, distribute canonical writers or move E-series work into core v1.

Open evidence before implementation/release claims:

1. Live Codex inference concurrency, contention and quotas under the pinned restricted runtime must be measured by the integration work; multiple thread IDs alone do not establish simultaneous inference.
2. Baseline versus background plans need identical scenario inputs, equal interactive-narration budgets, explicit additional planning cost and human review; no such experiment ran here.
3. English/Italian 100/500-turn recall, character consistency and leak rates remain unmeasured. Competitor lexical or embedding choices do not answer this.
4. Voice model licenses, weights, Italian quality and RTX 4070 resource figures require the dedicated speech investigation. A competitor voice selector or README performance claim is not a benchmark.
5. Stable generated faces, equipment variants, mobile VN interaction and assistive-technology behavior require real artifact/browser review; supplied sprites and screenshots do not prove them.
6. Recheck upstream licenses/dependency/model/asset terms at any concrete reuse. No competitor code/media was selected for bundling by this document.

Research continuation record: all five repositories were available and pinned; source, dependency declarations, license files, representative tests and issue trackers were inspected. This chapter is the durable inventory of completed competitive research and its limits. Application/benchmark execution remains pending by design. The next executable work is implementation of the roadmap's first eligible unchecked task, using the revised specifications and their gates, not repeating this research or marking a new gameplay phase complete.

[FL]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/LICENSE.md
[ML]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/LICENSE
[NL]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/LICENSE
[KL]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/LICENSE
[DL]: https://github.com/Mortyl/ai-dungeon-master/tree/4251a5fcfb94597686596b4b19ad4de4df166bfc
[F1]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/src/modules/core.js
[F1B]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/python/uie_backend.py#L420-L505
[F2]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/src/modules/contextMemory.js
[F2B]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/src/modules/memories.js
[F2C]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/src/modules/memory.js
[F3]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/python/uie_backend.py#L1797-L1894
[F3B]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/python/world_events.py
[F3C]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/python/uie_backend.py#L3507-L3532
[F4]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/src/modules/vnCharacterEngine.js
[F4B]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/scripts/test-vn-response-validation.mjs
[F5]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/src/modules/voiceBridge.js
[F5B]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/python/uie_backend.py#L2532-L3050
[F6]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/src/modules/visualGen.js
[F7]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/src/modules/apiClient.js
[F7B]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/src/modules/stateMutator.js
[F7C]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/scripts/test-living-world.mjs
[F8]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/package.json
[F8B]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/python/requirements.txt
[F9]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/src/modules/battle.js
[F10]: https://github.com/GetfroggyHoe/Universal-Immersion-Engine-Fugue/blob/bf4578398ae174599b25dcb15aecb04a603e48d6/src/modules/economy.js#L259-L279
[M1]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/frontend/src/lib/browserRoleplayTurn.ts#L68-L200
[M1B]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/rust-engine/crates/game/src/scene_roleplay/mod.rs
[M2]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/frontend/src/lib/sceneRoleplay.ts
[M3]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/rust-engine/crates/game/src/characters/memory.rs
[M3B]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/rust-engine/crates/assets/src/save_manager.rs
[M4]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/rust-engine/crates/authoring/src/quality_suite_execution.rs
[M4B]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/rust-engine/crates/authoring/src/quality_suite_execution/tests.rs
[M5]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/rust-engine/crates/authoring/src/agent_transaction/protocol.rs
[M5B]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/rust-engine/crates/authoring/src/agent_transaction.rs
[M5C]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/rust-engine/crates/authoring/src/project_package/archive_reader.rs
[M6]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/frontend/src/lib/scenePresentation.ts
[M6B]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/frontend/src/views/AudioView.vue
[M6C]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/frontend/src/lib/rendererAssets.ts
[M7]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/frontend/src/lib/i18n.ts
[M7B]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/frontend/src/lib/webgpuInference.ts
[M8]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/rust-engine/crates/authoring/src/project_package/manifest.rs
[M9]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/frontend/package.json
[M9B]: https://github.com/SakalioLabs/Monogatari/blob/d366539962bf99d14c94115eef45bccf2bcf4c36/rust-engine/Cargo.toml
[N1]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/server/lib/fileStore.js
[N1B]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/server/lib/writeLock.js
[N1C]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/src/services/turn/pendingCommit.ts
[N2]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/src/services/turn/directorBrief.ts
[N2B]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/src/services/turn/turnOrchestrator.ts#L199-L237
[N3]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/src/services/archive-memory/relationshipMemory.ts
[N3B]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/src/services/npc/relationshipMemoryReading.ts
[N3C]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/src/services/npc/relationshipMemoryCompaction.ts
[N4]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/src/services/archive-memory/slottedRag.ts
[N4B]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/server/services/vectorService.js
[N5]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/server/lib/embedder.js
[N5B]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/server/lib/vectorStore.js
[N5C]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/server/__tests__/vectorStore.test.js
[N6]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/server/lib/tts.js
[N6B]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/src/i18n/index.ts
[N6C]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/package.json
[N7]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/server/lib/requestGuards.js
[N7B]: https://github.com/Sagesheep/NarrativeEngine-P/blob/43af40b3e92d9f585f14dfd250e3aaf565a0b008/server/__tests__/requestGuards.test.js
[K1]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/crates/app-application/src/agent/operations.rs
[K1B]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/crates/adapter-sqlite/src/agent_operations.rs
[K1C]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/crates/adapter-sqlite/migrations/0004_agent_operations.sql
[K2]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/crates/app-application/src/agent/command_planning.rs
[K2B]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/crates/app-application/src/agent/human_commands.rs
[K3]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/crates/app-application/src/agent/narration.rs
[K3B]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/crates/app-application/src/agent/context.rs
[K4]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/crates/app-domain/src/adventure.rs
[K4B]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/crates/app-domain/src/combat/resolver.rs
[K5]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/scripts/architecture-boundaries.json
[K5B]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/crates/adapter-sqlite/tests/agent_operations.rs
[K6]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/sidecar/pipeline.py
[K6B]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/sidecar/app.py
[K7]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/src/i18n.ts
[K7B]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/src/locales/agentLocaleParity.test.ts
[K8]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/Cargo.toml
[K8B]: https://github.com/kiselyovd/dungeon-master-ai/blob/c3d11b09e186e0362477dde5d586d16ab8a34946/package.json
[D1]: https://github.com/Mortyl/ai-dungeon-master/blob/4251a5fcfb94597686596b4b19ad4de4df166bfc/backend/engine.py
[D1B]: https://github.com/Mortyl/ai-dungeon-master/blob/4251a5fcfb94597686596b4b19ad4de4df166bfc/backend/models.py
[D1C]: https://github.com/Mortyl/ai-dungeon-master/blob/4251a5fcfb94597686596b4b19ad4de4df166bfc/tests/test_engine.py
[D2]: https://github.com/Mortyl/ai-dungeon-master/blob/4251a5fcfb94597686596b4b19ad4de4df166bfc/backend/dm.py
[D3]: https://github.com/Mortyl/ai-dungeon-master/blob/4251a5fcfb94597686596b4b19ad4de4df166bfc/backend/api.py
[D4]: https://github.com/Mortyl/ai-dungeon-master/blob/4251a5fcfb94597686596b4b19ad4de4df166bfc/evals/run_evals.py
[D4B]: https://github.com/Mortyl/ai-dungeon-master/blob/4251a5fcfb94597686596b4b19ad4de4df166bfc/evals/scenarios.py
[D5]: https://github.com/Mortyl/ai-dungeon-master/blob/4251a5fcfb94597686596b4b19ad4de4df166bfc/frontend/app/page.tsx
[D6]: https://github.com/Mortyl/ai-dungeon-master/blob/4251a5fcfb94597686596b4b19ad4de4df166bfc/requirements.txt
[D6B]: https://github.com/Mortyl/ai-dungeon-master/blob/4251a5fcfb94597686596b4b19ad4de4df166bfc/frontend/package.json
