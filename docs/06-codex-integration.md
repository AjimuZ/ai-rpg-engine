# Codex Integration

## 1. Integration decision

**Runtime selection ([ADR 0020](adr/0020-selectable-narration-runtime.md)).** This document specifies the Codex adapter, the default narration runtime. Claude is a selectable alternative with its own profile in [Claude runtime integration](33-claude-runtime-integration.md). Exactly one runtime is active at a time and there is no automatic failover. Sections 6–11 below (task contracts, isolation, capability policy, routing, timeouts, compatibility gate) apply to whichever runtime is selected. Elsewhere in the documentation, “Codex” in a runtime-neutral statement means the selected narration runtime.

The research extension keeps the verified ADR 0013 isolation for Codex. [Asynchronous planning](28-asynchronous-narrative-planning.md) uses logical role/audience threads and a backend durable queue, not model subagents with tools or a replacement production provider. The external development orchestrator's Claude/Codex switching is unrelated to this gameplay contract.

AI RPG Engine uses **Codex app-server** as its default AI runtime interface; Claude is the only selectable alternative ([ADR 0020](adr/0020-selectable-narration-runtime.md)). The Java backend manages task-oriented RPC connections. The packaged Docker profile targets an isolated runtime service and a private Unix socket; local protocol probes retain an owned stdio process. The browser communicates only with AI RPG Engine. No desktop UI automation, scraping, generic chat wrapper, Ollama endpoint, or generic provider abstraction is part of this design. [ADR 0008](adr/0008-containerized-startup.md) supersedes the earlier mandatory host-child-process deployment assumption.

Official documentation describes app-server as a product integration surface, with a JSON-RPC-style protocol, threads, turns, streamed items, and an initialization handshake. It documents stdio transport, per-turn configuration and structured output, including restricted read-access policies. These capabilities support the proposed adapter; actual version and platform support must be tested. [OpenAI: Codex app-server](https://learn.chatgpt.com/docs/app-server).

The app-server interface has compatibility and maturity risks. The first implementation milestone must establish a supported version and safe capability profile before treating this architecture as release-ready. A protocol failure must not result in an improvised alternative AI provider. Selecting the other supported runtime is an explicit owner operation, never an automatic fallback.

The [P01.5 compatibility matrix](../tools/runtime_probe/COMPATIBILITY.md) records
exact supported versions, retained neutral request/result and event projections,
service maturity, upgrade checks and unverified platforms. Support is limited to
the measured developer profile; it is not a cross-version or release guarantee.

## 2. Alternatives

| Surface | Intended AI RPG Engine use | Trade-off |
| --- | --- | --- |
| App-server | Primary interactive integration | Direct lifecycle and event control; protocol maintenance burden |
| Official Codex SDK | Possible future adapter implementation if a justified server-side bridge is needed | Adds another runtime if a TypeScript bridge is introduced; not needed merely because Angular uses TypeScript |
| Noninteractive Codex execution | Potential maintenance/evaluation jobs | Simpler bounded jobs; less suitable as the primary rich interactive control surface |

The official SDK provides a server-side TypeScript library for local Codex threads. This is an alternative integration surface, not a Java SDK. [OpenAI: Codex SDK](https://learn.chatgpt.com/docs/codex-sdk). Noninteractive execution supports requesting schema-shaped final output; it can be considered for isolated tooling without changing the gameplay decision. [OpenAI: noninteractive mode](https://learn.chatgpt.com/docs/non-interactive-mode).

## 3. Authentication and data flow

Use supported Codex authentication. Do not read tokens into frontend code, copy credential files into this repository, or implement a private OAuth flow. The owner completes supported sign-in outside gameplay. The application reports account readiness and actionable authentication errors without revealing secret material.

For the project's verified container device-code flow, the owner must enable device-code login in ChatGPT security settings; managed workspaces require administrator permission for that method. Complete the runtime's browser link/code in its dedicated account volume. Desktop/development-client sign-in does not initialize this isolated volume. The launcher must explain this prerequisite when device authorization is unavailable and link the [official authentication instructions](https://learn.chatgpt.com/docs/auth); account/workspace policy is never changed automatically. Other supported login methods have their own prerequisites.

OpenAI documents ChatGPT subscription sign-in and API-key sign-in for local Codex use; account controls, entitlements, billing, and data handling depend on that choice. The product cannot promise that an existing desktop session always provides access, or that usage is free or unlimited. [OpenAI: authentication](https://learn.chatgpt.com/docs/auth).

The local app sends selected campaign context to the configured Codex service for inference. The outbound data inventory must identify task instructions, authorized state, selected lore/events, and generated content. Full saves, unrelated files, personal profiles, other campaigns, and raw credentials are not context inputs.

## 4. Proposed adapter lifecycle

The steps below describe the local stdio probe. In the packaged profile, Compose starts/stops the pinned runtime container and the backend connects through the validated private IPC transport; it must not invoke the Docker daemon. Executable/version checks become image/runtime compatibility checks, and backend shutdown closes owned connections/turns without killing unrelated services. Handshake, correlation, validation, interruption, renewal, and canonical checkpoint rules apply to both profiles. Container account state and IPC permissions are covered in [the deployment specification](22-container-deployment-and-startup.md).

1. Resolve an owner-configured, allowlisted executable path. Launch an argument array without a shell.
2. Check executable/version compatibility and process ownership; create an isolated runtime workspace.
3. Launch the supported stdio form for the pinned version. The demo's historical `--stdio` flag is not assumed current.
4. Start independent bounded stdout and stderr readers before sending requests.
5. Perform the documented initialization handshake and discover account/model capabilities.
6. Load only the selected runtime policy. Verify unwanted tools, inherited plugins, global MCP servers, project instructions, hooks, and development capabilities are unavailable. For 0.164.0 this policy is baked into the runtime image ([ADR 0013](adr/0013-narration-runtime-isolation.md)); the engine still validates the effective thread policy and rejects any non-narration turn item.
7. Create or resume a role- and audience-scoped thread bound to a logical AI session.
8. Start a task turn with explicit effective model, effort, sandbox, and output contract.
9. Correlate notifications to operation, process generation, thread, turn, and item.
10. Parse final output only after confirmed terminal status; validate again at application boundaries.
11. Retire a thread on renewal or unrecoverable uncertainty; rebuild from a canonical checkpoint.
12. On shutdown, stop admissions, request interruption, reconcile operations, close input, wait briefly, and terminate only the owned process if necessary.

The pinned probe verifies `initialize`, `initialized`, `account/read`, `model/list`, `thread/start`, `thread/resume`, `thread/turns/list`, `turn/start`, and `turn/interrupt`. P01.5 retains selected request/result shapes and terminal events in the [versioned neutral fixture](../tools/runtime_probe/fixtures/0.164.0/neutral-session.json); the [compatibility matrix](../tools/runtime_probe/COMPATIBILITY.md) records launch forms, projection limits and refresh checks. This is a measured subset of 0.164.0, not the production adapter or a guarantee of perpetual compatibility.

## 5. Transport requirements

- UTF-8, one bounded JSON message per protocol frame as required by the selected transport.
- Monotonic request IDs within a process generation; a concurrent pending-request registry.
- Serialize writes; never interleave messages from interruption and normal requests.
- Distinguish response, notification, and server-initiated request. Unknown privileged requests fail closed with the supported denial response.
- Enforce frame size, pending-request count, event queue, and task deadlines.
- Read stderr with redaction and bounded buffering; ignoring it entirely makes diagnosis unnecessarily difficult.
- Buffer early notifications until the turn acknowledgment establishes their correlation. Never discard a global event queue to “start fresh.”
- Filter by both thread and turn identity; a delayed prior-turn event cannot complete a new action.
- On EOF or process exit, fail unresolved requests, reconcile each operation receipt, and discard old process-generation events.
- Keep raw provider events outside the player-facing API.

Framing verified in P01.1 for runtime 0.164.0:

- **Stdio:** one JSON message per line.
- **Packaged Unix socket:** one JSON message per WebSocket text message, after an HTTP/1.1 upgrade. The client masks its frames and reassembles fragmented messages. It rejects binary frames and applies the same size limits.

The [deployment specification](22-container-deployment-and-startup.md#3-runtime-integration-gate) records the socket location and permission findings.

P01.4 implements these lifecycle rules in the sequential developer probe (the production concurrent adapter remains P08 work):

- `Stdio` delivers queued frames before a sticky terminal EOF/read/framing error. `Rpc` closes its generation on terminal wire failure, discards/counts pending notifications, and rejects later operations without I/O. A request that ends without a correlated response is unresolved, including timeout, malformed reply and event-limit failure; reconnect and reconcile rather than replaying it. A correlated error response resolves its request and permits subsequent requests.
- A turn-wait deadline alone leaves the connection usable for `turn/interrupt`; await the correlated terminal status. Losing a packaged relay connection leaves a running upstream turn alive. Resume through a fresh generation, inspect its history and explicitly interrupt/await when cancellation is required.
- Close input, wait 2 seconds, invoke the owned-container hook or terminate the owned local child, wait 2 seconds, then kill/wait for the child. The Docker hook has a separate 30-second bound and targets only its unique launch name; ending the Docker CLI alone cannot establish container termination. Reader joins are bounded to 1 second each. Reports include shutdown stage, exit code and elapsed seconds; an unreaped child is reported explicitly.
- Stderr is always drained, including after its 65,536-byte classification budget is exhausted. Keep at most a 512-byte line prefix and 16 diagnostic entries; discard messages and map unknown module names to `unclassified`. Only the fixed `codex_app_server` and `codex_core::config` module labels are retained. Counters and truncation are recorded, with no raw diagnostic content.
- The 16-frame stdout queue and 256-notification RPC queue are bounded independently. Early notifications retain arrival order until the start acknowledgment supplies turn identity; overflow fails explicitly and deadlines also apply to buffered events.

See [process lifecycle checks](../tools/runtime_probe/README.md#process-lifecycle-p014) for measured shutdown and recovery behavior. A closed process or connection alone is never evidence that a canonical operation was rolled back.

### P08.1 production adapter

P08.1 implements the Java transport behind the runtime-neutral task port. Application code sees only `org.rpgengine.inference`:

- `NarrationTaskPort`: `submit`, `retireSession` and `status`.
- Neutral records: `TaskRequest` (task and logical session identity, `InferenceSlot`, instructions, a JSON output schema and a 1–180-second budget), `TaskResult` (status, untrusted output, fixed error code and runtime/version/model/effort provenance) and `RuntimeStatus`.
- Slot assignment through `SlotAssignments`.

The adapter lives in the package-private `org.rpgengine.inference.codex`. A source and reflection test (`InferenceBoundaryTest`) fails the build if any other package names it or if the neutral API exposes a non-neutral type. Since P08.3 the only caller of `submit` and `retireSession` is the session-scoping registry ([section 7](#p083-session-scoping)), and no gameplay path calls it yet. Validated narration (P08.2/P08.5) consumes it later, and until then `UnavailableNarration` stays wired.

| Concern | Behaviour |
| --- | --- |
| Selection | `engine.inference.codex.transport` = `disabled` (default) / `stdio` / `unix`; any other value fails startup. Disabled wires `UnavailableTaskPort` (every task `UNAVAILABLE`, `runtime-not-configured`). |
| Wires | `unix`: RFC 6455 client over the real socket file (`socket-path`), with the `UnixRelay.java` handshake and frame rules: strict 101/accept, no extensions, masked client frames, reassembly, pong, close = EOF, a 1 MiB message limit and a handshake watchdog. `stdio`: an absolute executable launched without a shell. The child inherits only `PATH`, `HOME`, `CODEX_HOME`, `LANG`, `LC_ALL`, `TMPDIR`, `USER` and `TZ`, so engine secrets never reach it. Stdio uses line framing, P01.4 stderr redaction and the close-input/terminate/kill shutdown with a `ShutdownRecord`. |
| Generation | One reader thread per connection, monotonic request IDs, serialized writes and at most 8 outstanding requests. A correlated error resolves its request. Any request without a correlated response (timeout, interruption, write failure), terminal wire error or protocol violation closes the generation, fails its pending requests and inboxes, and discards buffered events. |
| Correlation | Events route to exact (thread, turn) inboxes of 1,024 events each; overflow fails only that turn. While a `turn/start` is in flight on a thread, events of unregistered turns on that thread are buffered in order, up to 256 per generation; overflow closes the generation. The acknowledgment hands them over. Retired-turn events are counted as late, other unknown turns as ignored, and identity-less events as unscoped. A `turn/*` or `item/*` event without identity is a violation. |
| Server requests | Always denied with `-32601`. One that names a registered turn fails it (`unexpected-server-request`). The runtime can send a request after the `turn/start` acknowledgment has been read but before the caller registers the turn. If the request names such a turn while its start is in flight, its failure is buffered with the early events. It counts toward the same 256 limit and fails the turn when the acknowledgment registers it, so the turn never waits for its deadline (fixed 28 October 2026). |
| Admission | One bounded FIFO queue (`queue-capacity`, default 16). A full queue rejects at once (`queue-full`) and duplicate live task IDs are rejected. The queue wait counts against the task budget, and a task that expires while queued never starts a turn. |
| Permit | One dispatcher thread runs one turn at a time: the P01.7 serialized policy. Since P08.6 every engine task reaches the adapter through the durable fair scheduler ([section 12](#p086-durable-fair-scheduling)), which forwards one task at a time, so this queue is a hand-off. |
| Readiness | `initialize`/`initialized` (a `/x.y.z` user-agent token other than 0.164.0 is refused), `account/read` (only a present ChatGPT or API-key account is `READY`) and the full `model/list` catalog (32 per page, at most 32 pages and 256 models). Otherwise the state is `UNAVAILABLE` with a fixed reason (`sign-in-required`, `unsupported-auth`, `runtime-unreachable`, `unsupported-runtime-version`, …). Queued and new tasks end `UNAVAILABLE`, and reconnection backs off from 1 to 30 seconds. The `narrationRuntime` health component is UP only when `READY`. |
| Per task | `ProfileResolver` resolves the slot through the owner's assignment to an explicit model and effort that must be in the discovered catalog and the owner's allowlist when one is set (`slot-unassigned`, `slot-backend-mismatch`, `slot-unsupported`, [section 9](#p084-profile-resolution)), and the schema must be a JSON object. The logical session maps to a persistent read-only thread: `thread/start` on first use, `thread/resume` when bound in an earlier generation. The effective policy is checked on every start/resume. `turn/start` always carries the model, effort, `approvalPolicy: never`, the read-only `sandboxPolicy` and the schema. Output is returned only after a matching `completed`. |
| Cancellation and deadlines | `cancel()` removes a queued task, or interrupts the exact turn and awaits its terminal status (default 10 seconds). Expiry and shutdown do the same. Results are `CANCELLED`, `TIMED_OUT`, or `COMPLETED` when the turn finished first. |
| Uncertainty | Several cases leave the outcome unknown: a lost generation, a malformed `turn/start` acknowledgment, an unconfirmed interrupt, and an isolation or protocol violation (`unexpected-turn-item`, `model-rerouted`, …). The task ends `UNRESOLVED`, or `FAILED` for violations on a healthy connection. The thread is marked ambiguous, its session bindings are retired (the next task reports `freshSession`), and the dispatcher keeps the permit until reconciliation succeeds: `thread/resume`, `thread/turns/list` (newest first, 8 turns), then an interrupt and confirmation of any `inProgress` turn. |
| Shutdown | Admissions stop, queued tasks end `CANCELLED`/`shutdown`, the active turn is interrupted and confirmed, and the owned connection closes. Stop is bounded by the interrupt and request timeouts plus the wire shutdown. |

Deliberate differences from the sequential probe:

- Reconciliation lists turns newest first (`sortDirection: desc`, the runtime's documented default order), so a long thread's in-progress turn is never beyond the first page.
- Catalog pages are larger.
- The packaged version check uses the user agent, because the engine cannot run `codex --version` inside the runtime container; the image pin remains authoritative.

Since P08.6 the adapter records each thread through the scheduler's write-ahead journal before `turn/start` (no record, no turn: `dispatch-journal-unavailable`), reports `active = 1` while any thread is unconfirmed, and accepts the recorded threads of an earlier process through `reconcile`, so a backend restart no longer forgets a turn that may still run ([section 12](#p086-durable-fair-scheduling)). Durable operation receipts for restart reconciliation of journals remain P09.5 work.

Verification uses deterministic doubles only:

- the retained 0.164.0 neutral fixture, replayed against the Java builders, validators and router;
- a scripted app-server over in-memory, real child-process stdio and real Unix-socket WebSocket transports;
- weakening probes.

No live runtime, account or Docker was available in the P08.1 environment, so the opt-in neutral real smoke (QA-42/§11) remains a P08 package check.

## 6. Task contracts

| Task | Input | Allowed output | Authority |
| --- | --- | --- | --- |
| Intent interpretation | Player text and permitted current options | Typed intention, targets, ambiguity, referenced IDs | Proposal only |
| World/content proposal | Canon, author constraints, generation budget | Candidate NPC/location/item/quest facts and definitions | Content validator must admit them |
| Encounter proposal | Scene, party capabilities, threat budget, templates | Candidate roster, zones, objectives, tactics | Encounter validator must admit them |
| NPC tactic | Acting NPC knowledge and legal action set | Legal action ID and target selection, bounded rationale metadata | Rules revalidate at execution |
| Outcome narration | Sanitized resolved events and visible state | Player prose, known consequence references, nonbinding suggestions | Cannot add consequences |
| Guide response | Guide's own permitted knowledge and questions | Advice and explanations | Read-only |
| Memory summary | Authorized event range and established facts | Summary with source references and unresolved threads | Derived memory only |

Every response has a contract version, task identity, source revision, and task-specific bounded fields. Unknown entities and operations are rejected. Do not accept tool instructions, executable source, host paths, SQL, model-supplied randomness, or generic field-setting operations as gameplay results.

Structured output improves parsing; it does not establish legal targeting, balance, truthful narration, or permission to mutate state. Maintain independent structural and semantic validators. [AI task contracts and prompts](21-ai-task-contracts-and-prompts.md) defines the proposed envelope fields and role templates in more detail.

P08.2 implements the intent and narration contracts in `org.rpgengine.inference.contract`. Each has a bundled output schema (passed as the task's output schema, constraining generation only), an independent Java validator, exactly one bounded repair with the same context and only the violation codes, and a binding to the locale and glossary generation. See [docs/21 section 14](21-ai-task-contracts-and-prompts.md#14-implemented-intent-and-narration-contracts-p082). The contracts name no runtime and pass through `NarrationTaskPort` unchanged. Since P08.5 outcome narration runs from the post-commit presentation job and only validated, still-current narration is published; invalid, declined or unavailable narration leaves the deterministic receipt and a narration-only retry ([docs/07 section 6](07-turn-protocol.md#p085-publication-receipt-fallback-and-narration-only-retry)).

## 7. Role and audience isolation

A game-master planner may need secrets to plan a coherent world. A player narrator, guide, or enemy tactician may not need them. Scope AI sessions by campaign, role, audience, and policy hash. **Never reuse a privileged planner thread to render player narration**, even if the newest message omits secrets; prior context still exists.

Player narration receives a public event bundle. NPC tactics receive that actor's observations and plausible beliefs. A guide receives its defined senses and discoveries. Summaries have the same visibility scope as their inputs. Cross-role handoffs use typed filtered records, not raw conversation transcripts.

### P08.3 session scoping

P08.3 implements this scoping in `org.rpgengine.inference.session`, above the neutral port and below every task contract. It names no runtime, so the Claude adapter (P20) inherits it unchanged.

- **Scope key.** A `SessionScope` holds the adventure, campaign, campaign recovery generation, `AiRole`, `Audience`, pack hash and policy hash. Two tasks share a logical session, and therefore a runtime thread, only when every component is equal. The generation is in the key because a restore increments it (docs/28 section 10). The hashes are in the key because a content or policy change renews the session (docs/08 section 7).
- **Audiences.**
  - `public`: the player's view.
  - `character:<ref>`: one NPC's or guide persona's own knowledge.
  - `privileged`: hidden canon and planning material, equal to the planning snapshot audience `PLANNER_PRIVATE`.
- **Role matrix.**

  | Role (`AiRole` wire name) | Player-facing | Permitted audiences |
  | --- | --- | --- |
  | `interpreter`, `narrator` | yes | `public` |
  | `guide` | yes | `character` |
  | `tactician` | no | `character` |
  | `summarizer` | no | any (the audience of its inputs) |
  | `content-proposer`, `encounter-proposer`, `quest-planner`, `world-director`, `plan-reviewer` | no | `privileged` |

  A player-facing role can never be privileged, and a planning role can never be public. A test checks this for every role. So no scope can serve both planning and narration, which turns "never reuse a privileged planner thread to render player narration" into a construction rule rather than a convention.
- **Single submitter.** `ScopedSessions` is the only main code that builds a `TaskRequest` or calls the port's `submit` or `retireSession`; `SessionBoundaryTest` enforces this. Callers name a scope, never a session identifier. `ContractRunner.start(contract, context, slot, budget)` no longer accepts a session: it derives the scope from the contract kind's role and audience and the binding's campaign, generation and hashes. Intent interpretation and narration are separate roles, so they have separate threads.
- **Registry rules** (`submit`, in order):
  1. A campaign already scoped under another adventure is `REJECTED` with `session-scope-conflict`.
  2. A scope with a lower generation than an active session of its campaign (a callback from before a restore) is `REJECTED` with `stale-campaign-generation`. Nothing is sent, and the current sessions are untouched.
  3. An equal scope reuses its session.
  4. A new scope first retires the campaign's lower-generation sessions (every role and audience) and the same campaign/role/audience line under other hashes.
  5. New sessions get fresh random identifiers, so a retired identifier is never reissued.
  6. At most `engine.inference.sessions.capacity` sessions are active (default 256, range 1–4096); the least recently used one is retired.

  Every retirement is forwarded to `NarrationTaskPort.retireSession`, so the adapter forgets the thread binding too. A task already running finishes on its thread.
- **Retirement operations.** `retire(scope)` handles renewal or a context made unreliable by a failure, `retireCampaign(id)` handles restore, deletion or archival, and `retireAll()` handles a runtime change or an owner request. The P09.4 triggers (turn ceiling, context pressure, idle renewal) will call them.
- **State.** The registry is in memory, like the adapter's bindings. A backend restart starts every scope with a fresh thread, which also covers a runtime switch at restart (ADR 0020 decision 4). Durable `AiSession` records with counters and checkpoint hashes are P09.3/P09.4.

Not part of P08.3:

- Context filtering itself. Callers assemble public contexts from typed projections (P08.5, P09.2), and the P08.2 disclosure guard refuses a context that contains a privileged marker.
- Detecting paraphrased secrets in output, which remains a human-rubric evaluation.
- The durable fair queue that dispatches these scopes (P08.6).

Evidence uses deterministic doubles only. The real Codex adapter runs against the scripted app-server: a planner, a narrator, an interpreter and an NPC tactician of one campaign get four distinct threads. A sentinel given to the planner is sent exactly once, on the planner's thread. Renewal, a policy change, campaign retirement and a newer generation start fresh threads only where they apply. See the [verification record](15-quality-and-acceptance.md).

## 8. Capability and tool policy

The preferred gameplay profile is restricted read-only or tool-less generation with narrowly supplied context. The bridge must prove on each supported platform that its policy blocks arbitrary host command execution, source edits, canonical file writes, database access, unrelated file reads, browser control, messaging, plugin installation, and unrestricted tool network access.

Model service connectivity is separate from the agent's permission to browse or make tool network requests. Blocking tool network access must not be described as offline inference.

If the pinned runtime cannot enforce the required restrictions, P01 fails its release gate. Use a supported restricted process environment or an explicitly isolated local runtime profile and retest. Prompt instructions are defense in depth, not the security boundary. Do not enable broad workspace writes simply to make the demo pattern work.

P01.3 applied this rule to 0.164.0 ([ADR 0013](adr/0013-narration-runtime-isolation.md)):

- **Boundary:** the container. Bubblewrap is absent and is not relied on.
- **Removed capabilities:** a root-owned managed configuration layer, an engine model catalog and requirements remove every model tool (the upstream request carries `tools: []`), pin approval `never`, sandbox `read-only` and search `disabled`, and drop inherited extension servers.
- **Startup guard:** the image refuses to start when the runtime home contains `config.toml`, `AGENTS.md` or `AGENTS.override.md`.
- **Policy requests:** `danger-full-access` thread requests are rejected; other wider requests are clamped to read-only/never.
- **Turn items:** the engine fails a turn containing any command, file change, tool call, search or unknown item (`unexpected-turn-item`).

No gameplay action grants approval to alter the engine or developer environment. Unexpected approval or tool requests produce a controlled task error, not an approval dialog inside the fictional scene.

P01.6 records [image generation as unavailable](../tools/runtime_probe/IMAGE_CAPABILITY.md)
in this exact managed profile. Discovery includes `imageGeneration` separately
from account state and the text catalog. No generation invocation or output
format is supported; account entitlement and cost remain unverified. The offline
`images --generate` probe and contract admission gate fail before runtime access,
even for bounded opt-in. Text discovery is unaffected. Future image enablement
requires separate restricted-profile, account/cost and format qualification in
P17.6; it cannot widen narration tools or enable paid/API access implicitly.

## 9. Model and reasoning routing

Use logical profiles: `light`, `standard`, `deliberate`, and owner-selected `fixedQuality`. Each profile resolves through the owner's slot assignment ([ADR 0021](adr/0021-inference-backends-and-model-selection.md)) to a backend and model; for the agent runtimes, resolve against that runtime's actual catalog and compatible effort levels. Store preferred account model IDs in private application settings; do not hardcode the demo's model names as permanent product requirements.

Classification is local and task-aware. Deterministic consultations bypass Codex. Ordinary narration uses a standard profile. High-impact negotiation, complex content generation, or tactical planning may use a deliberate profile. Combat arithmetic always remains local regardless of profile. Locale-specific keyword heuristics may assist but cannot override structured command types or current encounter state.

Before every task, set the effective model and effort explicitly. A prior expensive task must not cause an ordinary task to inherit an unintended setting. Preserve requested quality if another allowed model supports it; otherwise use only an owner-approved degradation policy or fail clearly. Never silently change provider, pricing tier, global Codex configuration, or use delegated subagents as a hidden routing mechanism.

### P08.4 profile resolution

Implemented (28 October 2026) in the runtime-neutral `org.rpgengine.inference` package; the Codex adapter calls it and no adapter owns routing rules.

- **Assignment.** `SlotAssignments` returns the owner's `SlotAssignment` for a slot: a backend from the closed text set of ADR 0021 (`codex`, `claude`, `local`, `openai-compatible`, `anthropic-api`), a preferred model and effort, and up to four owner-approved alternatives. Private settings: `engine.inference.slots.<light|standard|deliberate>.model`, `.effort`, optional `.backend` and optional `.alternatives` (comma-separated `model@effort`). The backend defaults to `engine.inference.backend`, which itself defaults to `codex`: this is the default-runtime role of `NARRATION_RUNTIME` (P20.2 maps that launcher setting onto it). No model name is shipped. Half-assigned slots, unknown backends, malformed or duplicate pairs and alternatives without a preferred pair fail startup without echoing a value.
- **Resolution order, per task, with no state between tasks.** `ProfileResolver.resolve` (1) applies `fixedQuality` if the owner selected it (`engine.inference.fixed-quality` = `light`, `standard` or `deliberate`), so every request resolves through that one slot whatever weight class the caller asked for, and it never falls back to the requested slot; (2) reads the assignment (`slot-unassigned`); (3) requires the slot's backend to be the resolving adapter's backend (`slot-backend-mismatch`: the engine never reroutes across providers, billing classes or privacy classes); (4) requires a catalog source (`catalog-unavailable` when the backend has neither a discovered catalog nor an allowlist); (5) tries the preferred pair and then the approved alternatives in order and takes the first pair that is in the discovered catalog (when the backend has one) and in the allowlist (when one is configured). When none qualifies the task ends `slot-unsupported`. A degraded run is visible: `TaskResult.Provenance.degradedFrom` holds the owner's preferred pair.
- **Catalog or allowlist.** The Codex adapter passes the pairs of the `model/list` catalog it discovered on its current connection. `engine.inference.allowlist.<backend>` (comma-separated `model@effort`, at most 256) is the owner's approval: for a backend that cannot enumerate models (the `claude` client, an owner-run endpoint) it is the catalog, and for Codex it narrows the discovered catalog so a model that appears in the account later is not used silently. A blank value is unset. An allowlist can never make an undiscovered pair usable.
- **Degradation is not failover.** Alternatives live on the same backend as the preferred pair, so they share its billing and privacy class, and are only consulted while resolving a task against the catalog. A turn that started and failed is never retried on another model (ADR 0020 decision 3).
- **Per turn.** The adapter resolves before the thread is bound and sends the resolved model and effort in `thread/start` (first use) and in every `turn/start`; an owner reassignment between two tasks of one thread therefore changes the next turn only, and a prior expensive task cannot leak its setting.
- **Not part of P08.4.** The owner-only settings API and panel, the advisor and per-slot readiness probes (P21.3), the `image` slot, the Claude and local adapters (P20, P21), and live-account catalog verification remain separate; no live runtime was contacted.

## 10. Timeouts and recovery

Proposed initial limits: handshake/discovery request 30 seconds, interpretation 60 seconds, ordinary narration 90 seconds, encounter proposal 120 seconds, and a total interactive operation planning budget of 180 seconds. These are local cancellation budgets, not service-level promises. They must be measured and configurable within safe caps.

A protocol timeout does not prove a turn did not execute. Before retrying, reconcile operation status and turn terminal state. A generated proposal has no canonical authority until committed, so a fresh proposal can be requested safely if the operation remains uncommitted and its draw/attempt identity is preserved. Committed actions retry only missing narration or delivery.

## 11. Compatibility gate

P01.7 [selects serialized inference](../tools/runtime_probe/CONCURRENCY.md): one
active turn installation-wide across campaigns/roles. A separate account-free
managed-profile experiment routes interleaved events into bounded exact
thread/turn inboxes, confirms targeted cancellation without stopping another
turn, and tests a client-controlled interrupt/reconcile/dispatch handoff.
Its five scripted turns qualify protocol dispatch only. Live simultaneity,
headroom, effective maximum capacity, quota behavior, cancellation latency and
service priority are unverified. No live account is authorized for that check.
The production adapter and the P08.6 durable fair scheduler ([section 12](#p086-durable-fair-scheduling))
keep one active turn; any increase above one still needs that qualification. Lost confirmation retains the permit and requires terminal
reconciliation; unsafe preemption disables optional work or confines it to an
explicit idle maintenance window. P01's live isolation recheck remains open.

The P01.2 developer fixture runs the pinned app-server against a deterministic upstream on an internal Docker network, with no account ([commands and scope](../tools/runtime_probe/README.md)). Its lifecycle client validates effective read-only/approval settings and correlates notifications by both thread and turn; it retains early notifications within a bounded queue. The 30 lifecycle checks pass, including exact upstream model/effort/schema comparisons across seven turns and reconnect/resume of two threads. This is protocol evidence, not live inference or a production concurrent dispatcher. The owner subsequently completed supported sign-in and the separate live neutral output/resume probe passed; see the retained report in the probe instructions. This does not verify gameplay narration quality.

For 0.164.0, `thread/start` requests a persistent thread with `approvalPolicy: never` and `sandbox: read-only`, then checks the effective response. Every `turn/start` sends explicit model, effort, `sandboxPolicy` and output schema; upstream evidence confirms all seven requests, and resume confirms that model/effort become sticky thread settings. Structured output remains a per-turn field. `thread/resume` uses `excludeTurns: true`; ordered history comes from `thread/turns/list` with `sortDirection: asc`. The bounded probe rejects another history page rather than claiming a complete history.

`turn/interrupt` returns an empty acknowledgment; only a matching `turn/completed` with `status: interrupted` proves termination. The fixture also confirms that both interrupted upstream streams close. Completed output is accepted only from a matching `item/completed` agent message followed by matching terminal completion; partial output from failed/interrupted turns is discarded. Unknown error variants become a fixed `unclassified` code. Malformed correlated events, rerouting, missing final output, notification overflow and expired deadlines fail explicitly. Foreign events are counted and discarded by this sequential fixture; the production dispatcher must preserve each pending turn's events.

Before the first playable release, capture: executable version; OS; launch form; protocol schema/contract digest; supported account discovery; paginated model catalog; structured result behavior; event ordering; interruption terminal states; restricted read/tool policy; inherited configuration isolation; and clean owned-process shutdown.

P01.1 captured the executable version, both launch forms (stdio and the packaged socket), account discovery without sign-in, and the paginated catalog. P01.2 adds real app-server lifecycle and scripted structured-result evidence, with `inferenceVerified: false` for that fixture. Its separate owner-signed-in neutral task now validates live structured output and resume, with `inferenceVerified: true` in the retained report. P01.3 verifies the restricted tool/read policy and inherited-configuration isolation with deterministic fixtures. A live re-check of that profile hit the account usage limit (`usageLimitExceeded`) and remains to be repeated. Process lifecycle coverage is documented above (P01.4); retained compatibility fixtures and maturity/platform limits remain P01.5 work.

Run fixtures without live inference in normal CI. Run a minimal opt-in real Codex smoke with neutral temporary context for the supported release baseline. No private campaign, paid call, or account access is needed to verify documentation or ordinary domain tests.

## 12. Fair inference scheduling

### P08.6 durable fair scheduling

Implemented (28 October 2026) in the runtime-neutral `org.rpgengine.inference.scheduling` package, between the contract runner and the session registry: `ContractRunner` → `InferenceScheduler` → `ScopedSessions` → `NarrationTaskPort`. `SchedulerBoundaryTest` fails the build if other main code calls `ScopedSessions.submit`, if the scheduler submits to the port itself, or if a contract caller takes the registry or the port instead of the scheduler. The Claude and local adapters (P20, P21) inherit it unchanged.

- **Durable ledger (V14).** `inference_task` holds one row per admitted task: identity, adventure, campaign, generation, role, audience, work class, slot, status, outcome code, owning process, estimated and charged usage, an opaque runtime reference, a preemption mark and the timestamps. Instructions, contexts, schemas and outputs are never stored, and captured runtime requests are never replayed. Lifecycle: `QUEUED` → `DISPATCHED` → terminal, or `DISPATCHED` → `RECONCILING` → `UNRESOLVED`; queued work may also end `CANCELLED`, `TIMED_OUT`, `REJECTED`, `UNAVAILABLE` or `ABANDONED` without reaching the runtime. A guard trigger enforces the transitions, the owner, the charge rules, the write-once reference and preemption mark and the frozen identity columns; rows cannot be deleted. `inference_scheduler_state` holds the installation-wide account backoff.
- **Single turn permit.** One active runtime turn installation-wide is the default and the only accepted setting. `engine.inference.scheduler.max-active` above `SchedulerSettings.QUALIFIED_MAX_ACTIVE` (1) fails startup with `concurrency-unqualified`. The partial unique index `inference_task_one_turn_uk` over `DISPATCHED` and `RECONCILING` rows enforces the same limit in PostgreSQL, so two backend processes sharing a database still run one turn at a time; the second one sees `PERMIT_BUSY` and retries on its next tick. Raising the limit needs P01.7 live evidence, a QA-37 qualification, and a migration that replaces this index with a permit table.
- **Work classes.** `INTERACTIVE` (intent interpretation and narration of the player's own action; both P08.2 kinds), then `CONTINUITY`, `PREPARATION` and `SPECULATIVE` background work (docs/28 section 9). The class is a property of the task kind (`TaskKind.workClass()`), never caller urgency.
- **Selection (`FairQueue`, a pure policy).**
  1. Permits: running tasks plus permits held for reconciliation never exceed the limit.
  2. Isolated lines: a campaign/role/audience line runs one task at a time and only its oldest waiting task is eligible, so each runtime conversation receives its tasks in admission order and never concurrently.
  3. Interactive first: an eligible interactive task always wins.
  4. Interactive reservation: with one permit, background work starts only when no interactive task is eligible; with several, background work may hold at most all but one.
  5. Aging: background tasks gain one class per `aging-step-seconds` (default 60) of waiting, never above `CONTINUITY`.
  6. Fair rotation: within the chosen rank, the campaign served least recently in that tier goes next, never-served campaigns first, ties to the oldest task.
- **Admission** (refusals complete the handle at once and send nothing): scheduler recovered and running (`scheduler-recovering`, `not-running`); no live duplicate (`duplicate-task`); separate queue places, interactive 16 installation-wide and 4 per campaign, background 8 and 2 (`queue-full`), so background backlog can never take an interactive place; no account suspension for background work (`inference-suspended`); rolling 24-hour usage quotas, 4,000,000 estimated units for all classes and 400,000 for background classes (`quota-exhausted`); then the durable row, whose insert proves that the campaign belongs to the scope's adventure at the scope's recovery generation (`session-scope-conflict`, `stale-campaign-generation`) and that the task identity was never used before, also across restarts (`duplicate-task`). Callers therefore use a fresh task identity per attempt; the narration and repair identities already are.
- **Dispatch.** The remaining budget must still reach one second (`queue-timeout`; waiting time counts against the task's own budget), the quota is re-checked, the row moves to `DISPATCHED` with its reservation charged, the campaign generation is re-checked in the same statement (`stale-campaign-generation`: a restore while a task waits fences it before anything is sent), and the task is submitted through `ScopedSessions` with its remaining budget, so the session is resolved when the task starts.
- **Usage.** The estimate is the UTF-8 input size in 4-byte units plus a per-slot output allowance (1,024/2,048/4,096). It is labelled an estimate everywhere: the 0.164.0 adapter reports no token counts. The reservation stays charged whenever a turn may have run (the result has provenance, or is `UNRESOLVED`) and is released when the port reports that no turn started. Quotas are read from the ledger, so a restart never resets them. No quota or backoff ever enables paid overage, another provider, model or account.
- **Preemption.** When an interactive task is eligible and no permit is free, the lowest-priority, most recently started background task is cancelled through its exact handle; the adapter interrupts that exact turn and awaits its terminal status. The permit stays in use until that terminal result is settled; then the interactive task starts. The caller sees `CANCELLED`/`preempted`, partial output is discarded, and the row keeps the preemption mark and the charge.
- **Cancellation and deadlines.** `cancel()` ends a waiting task `CANCELLED` without dispatch, or forwards to the port handle of a running task, whose confirmed terminal status is reported. The timer (`tick-ms`, default 100) ends waiting tasks whose budget fell below one second. `stop()` refuses admissions, ends waiting tasks `CANCELLED`/`shutdown`, cancels running ones and waits up to 15 seconds for their settlement; a task still running then keeps its `DISPATCHED` row for the next process.
- **Uncertain outcomes.** An `UNRESOLVED` result moves the row to `RECONCILING`, and the scheduler keeps the permit until the port reports `READY` with nothing active or queued, or `DISABLED`. The neutral port contract now requires an adapter to count unconfirmed work in `RuntimeStatus.active`; the Codex adapter counts ambiguous threads until its resume/list/interrupt reconciliation succeeds. While the permit is held and the port reports `UNAVAILABLE` or `STOPPED`, waiting tasks end `UNAVAILABLE` with the port's reason instead of waiting out their budget.
- **Account backoff.** A result with `usageLimitExceeded`, `rateLimitExceeded`, `serverOverloaded` or `unauthorized` suspends background admission and dispatch, with the sanitized codes `account-usage-limit`, `account-rate-limit`, `service-overloaded` and `account-unauthorized`. The delay is 30 seconds doubling per consecutive condition up to 15 minutes, with ±20% jitter; it is durable and expires on its own; a completed task clears it. Interactive work is never suspended; its failures end in the caller's deterministic fallback (the narration receipt). The 0.164.0 protocol mapping carries no retry timing to honor.
- **Restart.** Before any admission (`afterSingletonsInstantiated`), the scheduler attaches its write-ahead journal to the port, ends earlier processes' `QUEUED` rows `ABANDONED`/`restart`, takes over their `DISPATCHED` and `RECONCILING` rows as its own `RECONCILING` rows with the permit held, hands their recorded runtime references to `NarrationTaskPort.reconcile`, and reloads the backoff. The owning durable jobs re-request abandoned work with a fresh context: presentation jobs retry their attempt, and a job's own budgets are unchanged. A row taken over by another process is fenced: the old owner can neither settle it nor dispatch its stale queue entries (`task-fenced`).
- **Write-ahead runtime reference.** `DispatchJournal.recordStart(taskId, reference)` is called by the adapter after the thread is bound and before `turn/start`. The reference is the opaque thread identifier (printable ASCII, at most 256 characters), stored only in the internal ledger and never projected, exported or shown. A start that cannot be recorded is refused without a turn. After a restart, the Codex adapter adds the handed-over threads to its ambiguous set and reconciles them on its first connection before any task runs, so a turn left running by the previous process is interrupted and confirmed instead of overlapping new work.
- **Status.** `InferenceScheduler.status()` reports the state, the permit limit, interactive and background waiting counts, running tasks, rows awaiting reconciliation and an active suspension's sanitized code and end, never identities or text. An owner-facing status API is P21.3.
- **Settings** (`engine.inference.scheduler.*`, validated at startup without echoing values): `max-active`, `interactive-capacity`, `interactive-per-campaign`, `background-capacity`, `background-per-campaign`, `daily-usage-limit`, `background-daily-usage-limit`, `aging-step-seconds`, `backoff-initial-seconds`, `backoff-max-seconds`, `tick-ms`.

Not part of P08.6:

- Live qualification of more than one active turn, live quota behavior, service priority and cancellation latency. These need an authorized account-backed experiment (QA-37 live part); no account was used.
- Background task kinds and their owners: since P08.7 the quest planner's `quest-plan` and `quest-plan-refine` kinds submit through this queue as `PREPARATION` work in the `quest-planner`/`privileged` session (section 13); summaries (P09.3), tactics (P12) and guides (P15) will follow with their own classes.
- Owner-facing status and settings (P21.3), observed token accounting from a runtime, and the GPU/RAM admission coordinator for local backends (P21.4), which must sit behind this permit rather than beside it.
- Supported deployment is one backend process per database. A second process is safe because of the single-turn index and ownership fencing, but its startup reconciliation takes over the rows of a still-running first process.

Evidence uses deterministic doubles only: real PostgreSQL with only the runtime scripted, the real Codex adapter against the scripted app-server, and weakening probes. See the [verification record](15-quality-and-acceptance.md).

## 13. Quest-planner tasks

### P08.7 planner tasks

Optional quest planning (off by default, `engine.planning.enabled`) is the first
background task kind. `QuestPlanning` runs one preparation job at a time on its own worker
thread. Each attempt renders the `quest-plan` contract, or `quest-plan-refine` for a later
version, through `ContractRunner`. The contract kind fixes the role `quest-planner`, the
audience `privileged` and the class `PREPARATION`, so the scheduler gives the task its own
session line, never runs it while an interactive task waits, and preempts it for any
player turn. The task identity is derived from the job and attempt, so it is unique for the
ledger's lifetime. The repair identity is derived from that.

The default budget is 60 seconds: two 30-second attempts, so the single repair fits. The
planning worker waits for the budget plus ten seconds inside a 120-second job lease. The
runtime outcome maps onto the preparation job:

- **Completed and valid:** admission through the gate.
- **Unavailable, failed, timed out, unresolved or preempted:** a retryable job failure,
  limited by the job's two attempts.
- **Invalid after the repair:** a retryable `INVALID_PLAN`.
- **Declined:** a final `PLANNER_DECLINED`.
- **Cancellation requested meanwhile:** the result is discarded and the job is
  `CANCELLED`.

Planner prompts carry privileged canon, so they are never logged, and the ledger stores no
task text. No speculative work class is used yet, and no live planner run was made.
