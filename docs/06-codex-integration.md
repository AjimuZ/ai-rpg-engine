# Codex Integration

## 1. Integration decision

AI RPG Engine uses **Codex app-server** as its initial AI runtime interface. The Java backend manages task-oriented RPC connections. The packaged Docker profile targets an isolated runtime service and a private Unix socket; local protocol probes retain an owned stdio process. The browser communicates only with AI RPG Engine. No desktop UI automation, scraping, generic chat wrapper, Ollama endpoint, or generic provider abstraction is part of this design. [ADR 0008](adr/0008-containerized-startup.md) supersedes the earlier mandatory host-child-process deployment assumption.

Official documentation describes app-server as a product integration surface, with a JSON-RPC-style protocol, threads, turns, streamed items, and an initialization handshake. It documents stdio transport, per-turn configuration and structured output, including restricted read-access policies. These capabilities support the proposed adapter; actual version and platform support must be tested. [OpenAI: Codex app-server](https://learn.chatgpt.com/docs/app-server).

The app-server interface has compatibility and maturity risks. The first implementation milestone must establish a supported version and safe capability profile before treating this architecture as release-ready. A protocol failure must not result in an improvised alternative AI provider.

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

## 7. Role and audience isolation

A game-master planner may need secrets to plan a coherent world. A player narrator, guide, or enemy tactician may not need them. Scope AI sessions by campaign, role, audience, and policy hash. **Never reuse a privileged planner thread to render player narration**, even if the newest message omits secrets; prior context still exists.

Player narration receives a public event bundle. NPC tactics receive that actor's observations and plausible beliefs. A guide receives its defined senses and discoveries. Summaries have the same visibility scope as their inputs. Cross-role handoffs use typed filtered records, not raw conversation transcripts.

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

## 9. Model and reasoning routing

Use logical profiles: `light`, `standard`, `deliberate`, and owner-selected `fixedQuality`. Resolve them against the actual Codex catalog and compatible effort levels. Store preferred account model IDs in private application settings; do not hardcode the demo's model names as permanent product requirements.

Classification is local and task-aware. Deterministic consultations bypass Codex. Ordinary narration uses a standard profile. High-impact negotiation, complex content generation, or tactical planning may use a deliberate profile. Combat arithmetic always remains local regardless of profile. Locale-specific keyword heuristics may assist but cannot override structured command types or current encounter state.

Before every task, set the effective model and effort explicitly. A prior expensive task must not cause an ordinary task to inherit an unintended setting. Preserve requested quality if another allowed model supports it; otherwise use only an owner-approved degradation policy or fail clearly. Never silently change provider, pricing tier, global Codex configuration, or use delegated subagents as a hidden routing mechanism.

## 10. Timeouts and recovery

Proposed initial limits: handshake/discovery request 30 seconds, interpretation 60 seconds, ordinary narration 90 seconds, encounter proposal 120 seconds, and a total interactive operation planning budget of 180 seconds. These are local cancellation budgets, not service-level promises. They must be measured and configurable within safe caps.

A protocol timeout does not prove a turn did not execute. Before retrying, reconcile operation status and turn terminal state. A generated proposal has no canonical authority until committed, so a fresh proposal can be requested safely if the operation remains uncommitted and its draw/attempt identity is preserved. Committed actions retry only missing narration or delivery.

## 11. Compatibility gate

The P01.2 developer fixture runs the pinned app-server against a deterministic upstream on an internal Docker network, with no account ([commands and scope](../tools/runtime_probe/README.md)). Its lifecycle client validates effective read-only/approval settings and correlates notifications by both thread and turn; it retains early notifications within a bounded queue. The 30 lifecycle checks pass, including exact upstream model/effort/schema comparisons across seven turns and reconnect/resume of two threads. This is protocol evidence, not live inference or a production concurrent dispatcher. The owner subsequently completed supported sign-in and the separate live neutral output/resume probe passed; see the retained report in the probe instructions. This does not verify gameplay narration quality.

For 0.164.0, `thread/start` requests a persistent thread with `approvalPolicy: never` and `sandbox: read-only`, then checks the effective response. Every `turn/start` sends explicit model, effort, `sandboxPolicy` and output schema; upstream evidence confirms all seven requests, and resume confirms that model/effort become sticky thread settings. Structured output remains a per-turn field. `thread/resume` uses `excludeTurns: true`; ordered history comes from `thread/turns/list` with `sortDirection: asc`. The bounded probe rejects another history page rather than claiming a complete history.

`turn/interrupt` returns an empty acknowledgment; only a matching `turn/completed` with `status: interrupted` proves termination. The fixture also confirms that both interrupted upstream streams close. Completed output is accepted only from a matching `item/completed` agent message followed by matching terminal completion; partial output from failed/interrupted turns is discarded. Unknown error variants become a fixed `unclassified` code. Malformed correlated events, rerouting, missing final output, notification overflow and expired deadlines fail explicitly. Foreign events are counted and discarded by this sequential fixture; the production dispatcher must preserve each pending turn's events.

Before the first playable release, capture: executable version; OS; launch form; protocol schema/contract digest; supported account discovery; paginated model catalog; structured result behavior; event ordering; interruption terminal states; restricted read/tool policy; inherited configuration isolation; and clean owned-process shutdown.

P01.1 captured the executable version, both launch forms (stdio and the packaged socket), account discovery without sign-in, and the paginated catalog. P01.2 adds real app-server lifecycle and scripted structured-result evidence, with `inferenceVerified: false` for that fixture. Its separate owner-signed-in neutral task now validates live structured output and resume, with `inferenceVerified: true` in the retained report. P01.3 verifies the restricted tool/read policy and inherited-configuration isolation with deterministic fixtures. A live re-check of that profile hit the account usage limit (`usageLimitExceeded`) and remains to be repeated. Process lifecycle coverage is documented above (P01.4); retained compatibility fixtures and maturity/platform limits remain P01.5 work.

Run fixtures without live inference in normal CI. Run a minimal opt-in real Codex smoke with neutral temporary context for the supported release baseline. No private campaign, paid call, or account access is needed to verify documentation or ordinary domain tests.
