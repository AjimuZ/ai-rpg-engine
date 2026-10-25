# Claude Runtime Integration

## 1. Status and scope

**Design specification only.** [ADR 0020](adr/0020-selectable-narration-runtime.md) makes Claude a selectable alternative to Codex for the engine's narration runtime. Nothing here is implemented or has passed a compatibility gate. The launch profile in section 4 is a **candidate**, built from official documentation and from the command-line help of the client installed on the owner's workstation. Package P20 turns candidates into evidence. Until then the engine must not call `claude` supported, and Codex remains the default and the only runtime in the MVP.

This document covers only what differs from Codex. Everything else is shared and defined elsewhere:

- typed task contracts, validation and the single repair: [AI task contracts](21-ai-task-contracts-and-prompts.md);
- role and audience isolation: [ADR 0007](adr/0007-audience-isolation.md);
- capability and tool policy, model and effort routing, timeouts and the compatibility gate: [Codex integration §8–§11](06-codex-integration.md#8-capability-and-tool-policy) (those sections apply to the selected runtime);
- renewal and rebuild from checkpoints: [memory and continuity](08-memory-and-continuity.md);
- the container isolation profile and its evidence style: [ADR 0013](adr/0013-narration-runtime-isolation.md);
- the installation-wide one-active-turn default (P01.7) and the durable queue (P08.6).

## 2. Verified facts and sources

Facts marked *official* were read on 25 October 2026 from the pages linked in section 13. Facts marked *observed* come from the Claude Code client 2.1.308 installed on the owner's workstation, used on 22–25 October 2026 for development tooling (command-line help and real sessions). Observed items are not documented guarantees and must be re-proven on the pinned client in P20.1.

| Fact | Basis |
| --- | --- |
| `-p`/`--print` runs non-interactively. Exit code 0 means success and non-zero means failure. A failure inside the run, such as missing authentication, is printed as the result on stdout. | Official |
| `--output-format` is `text`, `json` (one result with `session_id`, usage and a cost estimate) or `stream-json` (newline-delimited events; it needs `--verbose`; the last line is the `result` message). | Official |
| `--json-schema` with `json` puts the validated object in `structured_output`. The field is absent when the run ends without valid output, and `format` keywords are annotations, not enforced. | Official |
| `--resume <id>` continues a session and `--continue` the latest one. Transcripts are stored as plaintext under `~/.claude/projects/` for 30 days by default (`cleanupPeriodDays`). `--no-session-persistence` (print mode only) stores none. | Official, help observed |
| `--permission-mode dontAsk` denies every call that would prompt. `--permission-prompts none` (client 2.1.259 or later) also tells the model not to retry. With `stream-json`, denials appear as `permission_denied` events and in `permission_denials` of the result. | Official |
| `--tools ""` disables all built-in tools. `--strict-mcp-config` ignores other MCP configuration and `--disable-slash-commands` disables skills and commands. A session with these options listed only the requested tools, and the `disableClaudeAiConnectors` setting removed account connectors. `--safe-mode` and `--restricted` also appear in the help. | Observed |
| A `-p` session runs the hooks and MCP servers of its working directory without a trust prompt unless `--bare` is used. | Official |
| SIGINT ends the turn. SIGTERM exits with code 143 and records no result for the turn. `CLAUDE_CODE_RESUME_INTERRUPTED_TURN=1` would continue an interrupted turn on resume. | Official |
| `system/init` lists tools, MCP servers, plugins and a `capabilities` array. `system/api_retry` carries an `error` category (`authentication_failed`, `oauth_org_not_allowed`, `account_on_hold`, `billing_error`, `rate_limit`, `overloaded`, `invalid_request`, `model_not_found`, `server_error`, `max_output_tokens`, `cloud_credential_error`, `unknown`). | Official |
| `rate_limit_event` has `status` (`allowed`, `allowed_warning`, `rejected`), `resetsAt` in epoch seconds, `rateLimitType`, `isUsingOverage` and window utilization. `result.terminal_reason` includes `blocking_limit`, `prompt_too_long`, `api_error`, `model_error` and `completed`. Result subtypes include `error_max_structured_output_retries`. | Observed |
| In print mode `ANTHROPIC_API_KEY` is always used when present. Precedence: cloud-provider variables, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_API_KEY`, `apiKeyHelper`, `CLAUDE_CODE_OAUTH_TOKEN`, profiles, then the subscription login. `--bare` never reads subscription credentials. | Official |
| Credentials are stored in `~/.claude/.credentials.json` (mode 0600) on Linux, or under `CLAUDE_CONFIG_DIR` when set. In containers the browser may show a code to paste into the terminal. | Official |
| Consumer plans (Free, Pro, Max) let the account choose whether data may train models: retention is 5 years if allowed and 30 days if not. Commercial plans and the API do not train and retain 30 days by default. | Official |
| Developers of products should use API-key authentication. Third parties may not route requests through Free, Pro or Max credentials on behalf of users, or collect, store or intermediate Claude account credentials. Plan limits assume ordinary individual use. Running Claude Code in a product requires the Commercial Terms, an unmodified binary, and no paying for or reselling usage on behalf of end users. An end user may sign in to the unmodified binary with their own subscription. | Official |

## 3. Capability comparison

| Capability | Codex (default; verified for 0.164.0) | Claude (candidate) |
| --- | --- | --- |
| Integration | App-server over a private Unix socket | One `claude -p` process per task, started by an engine-owned supervisor in the runtime container |
| Session continuity | Thread start and resume | `--session-id` and `--resume` with a UUID, or a stamped envelope per task; P20.1 chooses from measurements |
| Structured output | Output schema per turn | `--json-schema` and `structured_output`, validated again by the engine |
| No-tool profile | Managed configuration, requirements and catalog (ADR 0013) | `--tools ""`, no MCP, no skills, plus `system/init` and event checks |
| Model catalog | Paginated list | No catalog call found; an owner-approved allowlist checked by a neutral readiness task |
| Effort levels | Per catalog entry | `low`, `medium`, `high`, `xhigh`, `max` in the client help; per-model support to be proven |
| Interruption | `turn/interrupt` | SIGINT, then bounded escalation on the owned process only |
| Usage-limit signal | Error with reset text or time | `rate_limit_event` and `blocking_limit`, with `resetsAt` |
| Image generation | Unavailable in the managed profile (P01.6) | Not part of the profile and not claimed |
| Sign-in | Device-code flow in the container; state in a runtime-only volume | Anthropic's own flow in the container; state in a runtime-only volume (section 5) |

## 4. Candidate launch profile

The supervisor builds the command line from a fixed template. The backend sends only task data: the prompt, a schema identifier, the resolved model and effort, and the session identifier. It never sends flags, paths or environment. All items are candidates for P20.1.

| Element | Candidate | Reason |
| --- | --- | --- |
| Mode | `-p --output-format json`, or `stream-json --verbose` when events are consumed | Official non-interactive surface |
| Tools and extensions | `--tools ""`, `--strict-mcp-config` with no config, `--disable-slash-commands` | No host commands, files, search, extensions or skills |
| Permissions | `--permission-mode dontAsk --permission-prompts none` | Nothing can be approved |
| Instructions | `--system-prompt` with the engine instruction text, and `--exclude-dynamic-system-prompt-sections` | Replaces the coding-agent prompt, which also drops the Git status snapshot |
| Output | `--json-schema` per task kind | Output contract |
| Model and effort | `--model` and `--effort` on every task; never `--fallback-model` | No inherited or silent change |
| Session | `--session-id` or `--resume` with an engine UUID, or `--no-session-persistence` for stateless tasks | Renewable AI sessions |
| Settings | A read-only, root-owned engine settings file through `--settings`. `--safe-mode` and `--restricted` are evaluated as extra isolation. | Isolation from inherited configuration |
| Working directory | An empty engine-owned workspace with no project settings, hooks or `.mcp.json` | A `-p` session loads them without a trust prompt |
| Environment | Allowlist only. Telemetry and non-essential traffic off (`DISABLE_TELEMETRY`, `DISABLE_ERROR_REPORTING`, `DISABLE_FEEDBACK_COMMAND`, `CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY`, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`), and update checks off (the switch is identified from the pinned client). | Outbound traffic limited to inference |
| Never used | `--dangerously-skip-permissions`, `--allow-dangerously-skip-permissions`, `--bare` in subscription mode, `--continue`, `--add-dir`, `--mcp-config`, `--plugin-dir`, `--agents`, `--remote-control`, `--bg`, `--cloud`, `--teleport`, `CLAUDE_CODE_RESUME_INTERRUPTED_TURN` | Authority, ambiguity or hidden state |

The client is installed unmodified, as published by Anthropic, at an exact version with an integrity check. The installation method is recorded in P20.1 and in [ADR 0009](adr/0009-toolchain-baseline.md). The repository does not redistribute the binary. The container follows ADR 0013: read-only root filesystem, dropped capabilities, `no-new-privileges`, an unprivileged UID, a private account volume and IPC, and no route to PostgreSQL.

## 5. Authentication, billing and terms

| Mode | Credentials | Billing | Status |
| --- | --- | --- | --- |
| `subscription` | The owner signs in to the unmodified client in the runtime container through Anthropic's flow. The client's own store in a runtime-only volume holds them. | The owner's plan. Limits assume ordinary individual use. | Working default, gated by the terms review |
| `api-key` | An owner-provided Console key as a runtime-only secret file. | Metered per token. Estimated cost is recorded per task and a `--max-budget-usd` cap may be set. | Explicit opt-in alternative |

Rules that hold in both modes:

- The engine, launcher and backend never read, copy, log, display, transmit or provision credentials, and they do not provision long-lived tokens. The sign-in command of the launcher only runs Anthropic's flow in a one-off runtime container.
- **Startup guard.** The runtime refuses to start when credentials of the other kind are present. In subscription mode this covers `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, an `apiKeyHelper`, `CLAUDE_CODE_OAUTH_TOKEN`, cloud-provider variables and Anthropic profiles. In API-key mode it covers a stored subscription login. It also refuses to start when the home directory holds settings, instructions, hooks, skills, agents, plugins or MCP files. Only the credential file and engine-managed session state may exist. The guard is the engine's own check and does not modify or restrict the client's built-in authentication methods.
- **Terms review.** Before Claude is called supported, the owner reviews the [Consumer](https://www.anthropic.com/legal/consumer-terms) or [Commercial](https://www.anthropic.com/legal/commercial-terms) terms for the chosen mode and records the outcome in [the risk register](17-risks-and-open-decisions.md). The documentation does not decide whether a use is permitted.
- **First selection.** The owner acknowledges the billing mode, plan limits, data handling and terms link before the first Claude task.
- **Naming.** Documentation and the interface may state in plain text that the engine runs Claude Code. They must not use Anthropic names or logos in the product name or suggest endorsement ([publication](18-github-publication.md)).

## 6. Sessions, interruption and recovery

- The adapter exposes the same logical operations as Codex: create, resume and retire an AI session. The renewal rules of [memory and continuity](08-memory-and-continuity.md) apply unchanged. Retiring a session deletes its transcript from the runtime volume, or the task ran with `--no-session-persistence`. `cleanupPeriodDays` is set low as a backstop.
- To interrupt a task, send SIGINT and wait a bounded time. Escalate to SIGTERM and then SIGKILL on the owned process only, and never touch another process. A missing `result` message (for example exit code 143) is ambiguous. The adapter reconciles it against the operation receipt before any retry. It never resumes an interrupted turn implicitly.
- A runtime switch retires every session and rebuilds from canonical checkpoints (ADR 0020, decision 4).
- Process exit, EOF, timeout, stderr size and early output follow the bounded-reader rules of [Codex integration §5](06-codex-integration.md#5-transport-requirements).

## 7. Output validation

The adapter checks that `structured_output` exists, then runs the engine's structural and semantic validators (they do not trust the schema, and `format` is not enforced). `error_max_structured_output_retries` and a missing field count as an invalid proposal: one engine repair, then the deterministic receipt fallback. Stream events other than allowed message types, any `tool_use` and any `permission_denied` event fail the task as an isolation violation. The adapter also checks that `system/init` reports no tools, MCP servers, plugins or skills.

## 8. Models, effort and cost

Logical profiles resolve against an owner-approved allowlist of model identifiers kept in private settings. A neutral readiness task proves that each identifier and effort works. An unsupported pair fails the task clearly and never falls back to another model. Reported cost (`total_cost_usd`) is a client-side estimate: in subscription mode it is a usage indicator and not a bill. It is recorded for diagnostics and never used to infer remaining quota.

## 9. Limits and errors

| Signal | Engine behavior |
| --- | --- |
| `rate_limit_event` `rejected`, `blocking_limit`, or `api_retry` with `rate_limit` | Account unavailable (usage limit). Suspend optional admissions until `resetsAt` plus a margin, or use bounded backoff when it is unknown. No failover and no overage. |
| `isUsingOverage` or `overageInUse` true | Stop the task and treat it as a billing stop. Paid overage never starts implicitly. |
| `authentication_failed`, `oauth_org_not_allowed`, `account_on_hold` | Account unavailable. The owner must act. Sanitized status. |
| `billing_error` | Billing stop. The owner must act. |
| `prompt_too_long` | Context overflow. Renew the session and rebuild the envelope once, or fail clearly. |
| `overloaded`, `server_error`, `api_error`, `aborted_streaming` | Transient. Reconcile first, then bounded retry with backoff. |
| `model_not_found`, `invalid_request` | Unsupported runtime configuration. Diagnostic, no retry. |
| `max_output_tokens`, missing `structured_output` | Invalid proposal. One repair, then the receipt fallback. |
| Unknown `terminal_reason` or event | Treated as transient or unknown, never as success. |

## 10. Isolation evidence required

P20.1 provides evidence in the style of ADR 0013, on synthetic fixtures, with a positive control for each denial:

- the effective tool list in `system/init` and in every request is empty;
- no host command, file edit, search, extension or skill can run, and a canonical sentinel is never written;
- synthetic home settings, project settings, instruction files and hostile flags do not change the profile, or the guard refuses to start;
- credentials of the other kind stop the runtime, and no credential value appears in logs, reports or fixtures;
- the container has no route to PostgreSQL, and only inference egress is open;
- session transcripts stay in the runtime volume and are purged on retirement.

## 11. Data flow and privacy

Authorized campaign context goes only to Anthropic's service while `claude` is selected, and only to OpenAI's while `codex` is selected. Retention and training depend on the account. Consumer plans offer a training choice (5 years of retention if allowed, 30 days if not) at claude.ai/settings/data-privacy-controls. Commercial plans retain for 30 days by default. The documentation does not promise that deleting local data deletes provider-side data. Raw prompts and traces are off by default, and diagnostics redact credentials, account identifiers and personal paths.

## 12. Delivery and verification

| Item | Content | Evidence |
| --- | --- | --- |
| P20.1 | Pin and qualify the unmodified client without a live account | Fixtures, isolation evidence, capability matrix, limit and error signals |
| P20.2 | Package the optional runtime service, startup guards and launcher sign-in | Extended QA-28 |
| P20.3 | Adapter, selection, switching and diagnostics behind the neutral task port | Contract tests |
| P20.4 | Parity on shared fixtures, switching, limits, sentinels, owner-run live smoke, terms review recorded | QA-42 |

## 13. References

- [Claude Code: run programmatically](https://code.claude.com/docs/en/headless) — print mode, output formats, structured output, sessions, permissions, signals.
- [Claude Code: authentication](https://code.claude.com/docs/en/authentication) — methods, precedence, credential storage, long-lived tokens.
- [Claude Code: data usage](https://code.claude.com/docs/en/data-usage) — training, retention, telemetry switches, local transcripts.
- [Claude Code: legal and compliance](https://code.claude.com/docs/en/legal-and-compliance) — authentication and credential use, running Claude Code in products, naming.
- These pages describe Anthropic's product and terms. This project's adapter and engine design are its own, not an Anthropic guarantee.
