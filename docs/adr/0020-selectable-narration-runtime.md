# ADR 0020: Selectable narration runtime — Codex by default, Claude as the alternative

- Status: Accepted design (owner decision, 25 October 2026); implementation and evidence pending. The Claude runtime is not called supported until the P20 evidence and the terms review in decision 7 are recorded.
- Date: 25 October 2026
- Builds on: [ADR 0002](0002-codex-only.md) (scope, extended here), [ADR 0013](0013-narration-runtime-isolation.md) (isolation profile), [ADR 0005](0005-renewable-memory.md) (renewable sessions) and [ADR 0007](0007-audience-isolation.md) (audience scoping).

**Extended by [ADR 0021](0021-inference-backends-and-model-selection.md):** the runtime chosen here is the default backend of the three text slots, and each slot may be assigned another backend, including free local models and API keys. Decisions 3, 4, 7 and the terms gate still govern the agent runtimes.

## Context

ADR 0002 made Codex app-server the only narration runtime of the first release, and ADR 0013 fixed how that runtime is isolated. On 25 October 2026 the owner asked to be able to run the engine with Claude as well, using one runtime or the other, with Codex staying the default for now.

Claude Code has a documented non-interactive print mode (the command-line form of the Agent SDK) with JSON and streaming output, JSON-Schema output, resumable sessions, permission modes and a switch that disables every built-in tool. Section 2 of the [Claude runtime specification](../33-claude-runtime-integration.md#2-verified-facts-and-sources) lists the sources. The engine's task contracts therefore fit it. Two facts set it apart from Codex and shape this decision:

- **Terms.** Anthropic's [legal and compliance page](https://code.claude.com/docs/en/legal-and-compliance) tells developers of products to use API-key authentication. It does not permit third parties to route requests through Free, Pro or Max credentials on behalf of their users, or to collect, store or intermediate Claude account credentials. It states that plan limits assume ordinary individual use. It does not prevent an end user from signing in to the unmodified Claude Code binary with their own subscription.
- **Billing precedence.** In print mode Claude Code always uses `ANTHROPIC_API_KEY` when it is set ([authentication precedence](https://code.claude.com/docs/en/authentication#authentication-precedence)). A stray key silently turns a subscription session into metered usage.

## Decision

1. **Two named runtimes, not a provider framework.** The engine supports exactly `codex` and `claude`. There is no third runtime, no user-defined endpoint, no per-request routing and no plugin discovery. A third runtime needs its own decision record. Local models, a generic chat API and the Messages API stay outside this decision (ADR 0002 alternatives; ADR 0021 later adds them as separate backends).
2. **One setting, Codex by default.** The owner chooses the runtime with one installation-wide setting, planned as `NARRATION_RUNTIME=codex|claude` in the private `.env`. Unset means `codex`. Any other value is refused with a clear diagnostic. Only the selected runtime's service is started. The setting is installation-wide because the serialized one-active-turn default (P01.7) is installation-wide.
3. **No automatic failover.** If the selected runtime is unavailable (usage limit, sign-in, unsupported version), the engine behaves as already specified: local consultations and receipts keep working, narration stays pending, and the status says so. It may tell the owner that the other runtime can be selected. It never switches, falls back or retries on the other runtime by itself. A fallback model inside a runtime is not used either.
4. **Switching is an owner operation of the launcher.** The owner changes the setting through `./start.sh` (planned `--runtime`) and the stack restarts. The engine, which holds no Docker socket (ADR 0008), cannot start or stop runtime containers. At startup it compares the setting with the runtime recorded in the database. If they differ, it first completes the usual reconciliation, so no unresolved operation, lease or ambiguous turn remains. It then retires every AI session of the previous runtime, creates new ones from canonical checkpoints when needed (ADR 0005), records an audit event with both runtimes and versions, and regenerates narration still pending from committed receipts. Narration-only retry never repeats the action. Campaign state, transcript and pack hashes are untouched.
5. **Same authority, contracts and isolation.** Both adapters sit behind one runtime-neutral task port and use the same typed contracts, validators, single repair, receipts and audience isolation. Neither can write canonical state or use tools. A runtime that cannot show the isolation properties of ADR 0013 (container boundary, no tools, no inherited configuration, no database route, effective policy checked on every response) cannot be selected. Both adapters must pass the same neutral fixtures. Each recorded AI task carries the runtime, its version, and the effective model and effort.
6. **Claude integration surface.** The adapter runs the unmodified Claude Code client, as published by Anthropic and pinned by version, in print mode inside its own isolated runtime container. An engine-owned supervisor in that container starts one process per task from a fixed launch profile; callers supply task data, never flags, paths or environment. Sessions are Claude conversations identified by a UUID and resumed by that identifier. The candidate profile is in the specification and becomes evidence in P20.1.
7. **Authentication, billing and terms are gates.**
   - **Subscription mode.** The owner signs in to the unmodified client inside the runtime container through Anthropic's own flow. Credentials stay in the client's own store in a runtime-only account volume. The engine, launcher and backend never read, copy, log, display, transmit or provision them. Provisioning a long-lived token (`claude setup-token`, `CLAUDE_CODE_OAUTH_TOKEN`) is not part of the supported profile.
   - **API-key mode.** An owner-provided Console key is a runtime-only secret. It is metered, needs an explicit opt-in, and its estimated cost is recorded per task.
   - **Startup guard.** The runtime refuses to start when credentials of the other kind are present (for example `ANTHROPIC_API_KEY` in subscription mode). Metered usage never starts implicitly.
   - **Terms review.** Claude may be called supported only after P20.1 evidence **and** an owner review of the Consumer or Commercial terms for the chosen mode, recorded in the risk register. If the review rejects subscription sign-in for this use, the supported profile is API-key mode or Claude stays disabled. The adapter, selection and isolation design remain valid either way.
   - **First selection.** Selecting `claude` for the first time requires an owner acknowledgement of the billing mode, plan limits, data handling and terms link.
8. **Data flow.** Authorized campaign context goes only to the service of the selected runtime. Retention and training depend on the account type and its privacy setting (see the specification, section 11). Runtime volumes keep no raw prompt traces by default, and session transcripts are bounded and purged when sessions retire.
9. **Routing.** Logical profiles (`light`, `standard`, `deliberate`, `fixedQuality`) resolve against the selected runtime's actual catalog. Model identifiers are private settings, not product constants. The effective model and effort are set on every task.
10. **Test doubles stay verification devices.** They are never selectable. The external development orchestrator is unrelated and unchanged.

## Not decided

- Making Claude the default.
- Mixing runtimes per campaign or task, or running both agent runtimes at once. Per-slot assignment is decided by ADR 0021.
- Any vendor other than these two.
- A direct Messages API adapter (decided later by ADR 0021 as the `anthropic-api` backend).

## Alternatives

- **Claude through the Messages API with an API key.** It is the route Anthropic recommends for products and needs no command-line client or sandbox. But it is a generic chat API (excluded by ADR 0002), it is metered, and it would need its own isolation and session model. Because the port is runtime-neutral, it can be added later by a separate decision.
- **Agent SDK bridge (Python or TypeScript).** It adds another runtime to the packaged stack and falls under the same terms.
- **Failover or per-campaign mixing.** It makes continuity and audience isolation depend on two vendors at once and conflicts with the rule against silent switching.
- **In-app toggle.** It would need the engine to control containers, which ADR 0008 forbids without a Docker-socket bridge.
- **Claude as the default now.** Not requested; Codex is the only runtime with verified evidence (P01).

## Consequences

- Two compatibility matrices, two sign-in flows, two privacy statements and a larger test surface. Narration style differs between runtimes, so continuity must come from canonical state and not from a thread.
- Capabilities differ per runtime. Runtime image generation stays unavailable (P01.6), and the Claude profile claims no image capability.
- The MVP is unchanged and Codex-only. The Claude runtime is a v1 capability, scheduled as package P20 before hardening. P08 designs the task port runtime-neutral from the start.
- The terms risk is real. Anthropic may enforce its restrictions without notice, and plan limits assume ordinary individual use. The supported claim waits for the review.

## Verification

- P20.1 account-free evidence for the pinned client (isolation, launch profile, capability matrix, limit and error signals).
- P20.2 packaging, startup guards and sign-in through the launcher, extending QA-28.
- P20.3 and P20.4 contract parity on shared fixtures, switching and restart, limit and overage handling, secret-sentinel tests, and an opt-in live neutral smoke run by the owner (QA-42).
- Documentation checks: links, anchors and consistency of the runtime wording.
