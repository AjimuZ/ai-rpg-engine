# Codex Integration

## 1. Integration decision

AI RPG Engine uses **Codex app-server** as its initial AI runtime interface. The Java backend manages task-oriented RPC connections. The packaged Docker profile targets an isolated runtime service and a private Unix socket; local protocol probes retain an owned stdio process. The browser communicates only with AI RPG Engine. No desktop UI automation, scraping, generic chat wrapper, Ollama endpoint, or generic provider abstraction is part of this design. [ADR 0008](adr/0008-containerized-startup.md) supersedes the earlier mandatory host-child-process deployment assumption.

Official documentation describes app-server as a product integration surface, with a JSON-RPC-style protocol, threads, turns, streamed items, and an initialization handshake. It documents stdio transport, per-turn configuration and structured output, including restricted read-access policies. These capabilities support the proposed adapter; actual version and platform support must be tested. [OpenAI: Codex app-server](https://learn.chatgpt.com/docs/app-server).

The app-server interface has compatibility and maturity risks. The first implementation milestone must establish a supported version and safe capability profile before treating this architecture as release-ready. A protocol failure must not result in an improvised alternative AI provider.

## 2. Alternatives

| Surface | Intended AI RPG Engine use | Trade-off |
| --- | --- | --- |
| App-server | Primary interactive integration | Direct lifecycle and event control; protocol maintenance burden |
| Official Codex SDK | Possible future adapter implementation if a justified server-side bridge is needed | Adds another runtime if a TypeScript bridge is introduced; not needed merely because Angular uses TypeScript |
| Noninteractive Codex execution | Potential maintenance/evaluation jobs | Simpler bounded jobs; less suitable as the primary rich interactive control surface |

The official SDK provides a server-side TypeScript library for local Codex threads. This is an alternative integration surface, not a Java SDK. [OpenAI: Codex SDK](https://learn.chatgpt.com/docs/codex-sdk). Noninteractive execution supports requesting schema-shaped final output; it can be considered for isolated tooling without changing the gameplay decision. [OpenAI: noninteractive mode](https://learn.chatgpt.com/docs/non-interactive-mode).

## 3. Authentication and data flow

Use supported Codex authentication. Do not read tokens into frontend code, copy credential files into this repository, or implement a private OAuth flow. The owner completes supported sign-in outside gameplay. The application reports account readiness and actionable authentication errors without revealing secret material.

OpenAI documents ChatGPT subscription sign-in and API-key sign-in for local Codex use; account controls, entitlements, billing, and data handling depend on that choice. The product cannot promise that an existing desktop session always provides access, or that usage is free or unlimited. [OpenAI: authentication](https://learn.chatgpt.com/docs/auth).

The local app sends selected campaign context to the configured Codex service for inference. The outbound data inventory must identify task instructions, authorized state, selected lore/events, and generated content. Full saves, unrelated files, personal profiles, other campaigns, and raw credentials are not context inputs.

## 4. Proposed adapter lifecycle

The steps below describe the local stdio probe. In the packaged profile, Compose starts/stops the pinned runtime container and the backend connects through the validated private IPC transport; it must not invoke the Docker daemon. Executable/version checks become image/runtime compatibility checks, and backend shutdown closes owned connections/turns without killing unrelated services. Handshake, correlation, validation, interruption, renewal, and canonical checkpoint rules apply to both profiles. Container account state and IPC permissions are covered in [the deployment specification](22-container-deployment-and-startup.md).

1. Resolve an owner-configured, allowlisted executable path. Launch an argument array without a shell.
2. Check executable/version compatibility and process ownership; create an isolated runtime workspace.
3. Launch the supported stdio form for the pinned version. The demo's historical `--stdio` flag is not assumed current.
4. Start independent bounded stdout and stderr readers before sending requests.
5. Perform the documented initialization handshake and discover account/model capabilities.
6. Load only the selected runtime policy. Verify unwanted tools, inherited plugins, global MCP servers, project instructions, hooks, and development capabilities are unavailable.
7. Create or resume a role- and audience-scoped thread bound to a logical AI session.
8. Start a task turn with explicit effective model, effort, sandbox, and output contract.
9. Correlate notifications to operation, process generation, thread, turn, and item.
10. Parse final output only after confirmed terminal status; validate again at application boundaries.
11. Retire a thread on renewal or unrecoverable uncertainty; rebuild from a canonical checkpoint.
12. On shutdown, stop admissions, request interruption, reconcile operations, close input, wait briefly, and terminate only the owned process if necessary.

Protocol method candidates inherited from the demo are `initialize`, `initialized`, `account/read`, `model/list`, `thread/start`, `thread/resume`, `turn/start`, and `turn/interrupt`. Their exact fields, response shapes, events, and supported launch forms belong in a versioned adapter contract fixture. These names are not an implementation or a guarantee of perpetual compatibility.

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

No gameplay action grants approval to alter the engine or developer environment. Unexpected approval or tool requests produce a controlled task error, not an approval dialog inside the fictional scene.

## 9. Model and reasoning routing

Use logical profiles: `light`, `standard`, `deliberate`, and owner-selected `fixedQuality`. Resolve them against the actual Codex catalog and compatible effort levels. Store preferred account model IDs in private application settings; do not hardcode the demo's model names as permanent product requirements.

Classification is local and task-aware. Deterministic consultations bypass Codex. Ordinary narration uses a standard profile. High-impact negotiation, complex content generation, or tactical planning may use a deliberate profile. Combat arithmetic always remains local regardless of profile. Locale-specific keyword heuristics may assist but cannot override structured command types or current encounter state.

Before every task, set the effective model and effort explicitly. A prior expensive task must not cause an ordinary task to inherit an unintended setting. Preserve requested quality if another allowed model supports it; otherwise use only an owner-approved degradation policy or fail clearly. Never silently change provider, pricing tier, global Codex configuration, or use delegated subagents as a hidden routing mechanism.

## 10. Timeouts and recovery

Proposed initial limits: handshake/discovery request 30 seconds, interpretation 60 seconds, ordinary narration 90 seconds, encounter proposal 120 seconds, and a total interactive operation planning budget of 180 seconds. These are local cancellation budgets, not service-level promises. They must be measured and configurable within safe caps.

A protocol timeout does not prove a turn did not execute. Before retrying, reconcile operation status and turn terminal state. A generated proposal has no canonical authority until committed, so a fresh proposal can be requested safely if the operation remains uncommitted and its draw/attempt identity is preserved. Committed actions retry only missing narration or delivery.

## 11. Compatibility gate

Before the first playable release, capture: executable version; OS; launch form; protocol schema/contract digest; supported account discovery; paginated model catalog; structured result behavior; event ordering; interruption terminal states; restricted read/tool policy; inherited configuration isolation; and clean owned-process shutdown.

P01.1 used `tools/runtime_probe` to capture these items for runtime 0.164.0: the executable version, both launch forms (stdio and the packaged socket), account discovery without sign-in, and the paginated catalog. Structured results are validated offline only. The remaining items belong to P01.2–P01.5.

Run fixtures without live inference in normal CI. Run a minimal opt-in real Codex smoke with neutral temporary context for the supported release baseline. No private campaign, paid call, or account access is needed to verify documentation or ordinary domain tests.
