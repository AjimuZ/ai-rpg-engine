# Claude Runtime Integration

## 1. Status and scope

**Design specification, partly qualified on fixtures.** [ADR 0020](adr/0020-selectable-narration-runtime.md) makes Claude a selectable alternative to Codex for the engine's narration runtime. No product code, image or setting uses it, and it has passed no compatibility gate with a live account. P20.1-A pinned the client (2.1.323) and P20.1-B proved its isolation profile on fixtures. P20.1-C (22 November 2026) fixed the launch profile and supervisor protocol in section 4 as a runtime-probe script, and recorded the capability matrix (section 3.1), the readiness approach (section 8), the limit and error signals (section 9) and the retention and purge behavior (section 6). Each row of those sections names its fixture test or is marked unproven. Package P20 still has to package, adapt and verify the runtime. Until then the engine must not call `claude` supported, and Codex remains the default and the only runtime in the MVP. [ADR 0021](adr/0021-inference-backends-and-model-selection.md) adds a direct Messages API backend for owner keys, which is the route Anthropic recommends for products; this document covers only the Claude Code client.

This document covers only what differs from Codex. Everything else is shared and defined elsewhere:

- typed task contracts, validation and the single repair: [AI task contracts](21-ai-task-contracts-and-prompts.md);
- role and audience isolation: [ADR 0007](adr/0007-audience-isolation.md);
- capability and tool policy, model and effort routing, timeouts and the compatibility gate: [Codex integration §8–§11](06-codex-integration.md#8-capability-and-tool-policy) (those sections apply to the selected runtime);
- renewal and rebuild from checkpoints: [memory and continuity](08-memory-and-continuity.md);
- the container isolation profile and its evidence style: [ADR 0013](adr/0013-narration-runtime-isolation.md);
- the installation-wide one-active-turn default (P01.7) and the durable fair queue (P08.6, [docs/06 section 12](06-codex-integration.md#p086-durable-fair-scheduling)). A Claude adapter must honor its neutral port duties: record an opaque reference through the attached `DispatchJournal` before each start and start nothing when that fails, count unconfirmed work in `RuntimeStatus.active` until it is confirmed idle, and confirm references handed over by `reconcile` after a restart before running new work (or report that its client process cannot outlive the backend, in which case references are confirmed idle).

## 2. Verified facts and sources

Facts marked *official* were read on 25 October 2026 from the pages linked in section 13. Facts marked *observed* come from the Claude Code client 2.1.308 installed on the owner's workstation, used on 22–25 October 2026 for development tooling (command-line help and real sessions). Observed items are not documented guarantees and must be re-proven on the pinned client in P20.1. Facts on the pinned 2.1.323 client that a fixture test now asserts are in sections 3.1, 6, 8 and 9, which name the test.

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
| Integration | App-server over a private Unix socket | One `claude -p` process per task, started by the engine-owned supervisor of section 4 |
| Session continuity | Thread start and resume | `--session-id` to create and `--resume` to continue an engine UUID, `--no-session-persistence` for stateless tasks (section 6) |
| Structured output | Output schema per turn | `--json-schema` and `structured_output`, through the client's own `StructuredOutput` tool, validated again by the engine |
| No-tool profile | Managed configuration, requirements and catalog (ADR 0013) | `--tools ""`, no MCP, no skills, plus `system/init` and event checks |
| Model catalog | Paginated list | No catalog call found; an owner-approved allowlist checked by a neutral readiness task (section 8) |
| Effort levels | Per catalog entry | `low`, `medium`, `high`, `xhigh`, `max`; sent for some models and silently dropped for others (section 8) |
| Interruption | `turn/interrupt` | SIGINT, then bounded escalation on the owned process only (unproven) |
| Usage-limit signal | Error with reset text or time | `rate_limit_event` with `resetsAt`, only when signed in to a subscription, plus the `rate_limit` error (section 9) |

### 3.1 Capability matrix of the pinned client (P20.1-C)

Every row describes client 2.1.323 run through the fixed profile of section 4 against the scripted upstream. *Fixture* names the check in [`test_claude_profile.py`](../tools/runtime_probe/test_claude_profile.py) (or [`test_claude_isolation.py`](../tools/runtime_probe/test_claude_isolation.py)) that asserts it. *Scratch* means it was seen in a scratch `docker build` on 22 November 2026 and no test asserts it. *Unproven* means it was not exercised. The *Fixture* checks passed under Compose in the supervisor's `sh .agents/ralph/verify.sh containers` run of 22 November 2026 (runtime-probe-docker `Ran 151 tests`, `OK`, logs in `.agents/ralph/logs/verify/20261122T233042-10323`; roadmap P20.1).

| Capability | Behavior of 2.1.323 in the fixed profile | Evidence |
| --- | --- | --- |
| Task run | One `system/init`, then events, then one `result` on stdout; exit 0 on success and 1 on any reported error | Fixture: `check_signals` |
| Prompt passing | `-p` reads the prompt from stdin. A positional prompt such as `--version` is parsed as an option (the client printed its version), so the supervisor never passes one. | Fixture: `prompt-is-data` step; the option parse is scratch only |
| Tools | None without a schema: `system/init` lists no tool and no request offers one | Fixture: `check_signals`; P20.1-B |
| Structured output | `--json-schema` adds the client's own `StructuredOutput` tool. `system/init` lists `["StructuredOutput"]`, requests offer `DeferredToolPlaceholder` and `StructuredOutput`, and `structured_output` holds the call's input. A model that answers in text is asked once more, then the task ends `success` without `structured_output`. | Fixture: `schema` and `schema-unanswered` steps |
| Model | `--model` is sent unchanged with `CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP=1`. Without that variable the client sent `claude-opus-5-5` for `claude-opus-4-1`. | Fixture: `check_readiness`; the remap itself is scratch only |
| Effort | Sent as `output_config.effort` for `claude-sonnet-5-5`, `claude-opus-5-5`, `claude-haiku-5-5`, `claude-fable-5-2` and an unknown identifier; silently dropped for `claude-sonnet-4-5` and `claude-opus-4-1`. An unknown value only prints a warning and uses the default. | Fixture: `check_readiness`; the unknown value is scratch only (the supervisor refuses it) |
| Retries | With `CLAUDE_CODE_MAX_RETRIES=0`, API-key mode shows no `api_retry` event. The client still sends 3 requests on `invalid_request` (dropping beta features), 2 on an unknown model and 4 on `max_tokens` (3 automatic continuations). A rejected subscription token gives 2 `api_retry` events and 2 requests. | Fixture: `SIGNALS` and `SUBSCRIPTION` request counts |
| Sessions | `--session-id` stores one plaintext JSONL transcript, `--resume` replays it, reusing an ID is refused, and `--no-session-persistence` stores nothing | Fixture: `check_lifecycle` |
| Limit events | `rate_limit_event` appears only with a subscription login; API-key mode reports a 429 as the `rate_limit` error only | Fixture: `check_subscription`, `check_signals` |
| Credentials | API-key mode sends only `x-api-key`. A stored subscription login sends only its bearer token. The supervisor refuses credentials of the other mode. | Fixture: `check_task_common`, `check_refusals` |
| Extensions | No MCP server, plugin, skill or slash command in `system/init`; five built-in agents still listed | Fixture: `check_task_common`; agents are a P20.1-B residual |
| Interruption | SIGINT and SIGTERM handling | Unproven. The supervisor uses `exec`, so a signal sent to its process ID reaches the client, but no test sends one. |
| Cost | `total_cost_usd` is reported even for scripted answers | Scratch only |
| Outbound traffic | Telemetry and non-essential traffic switches set; the fixture network has no external route | Unproven: no test shows what the client would contact on an open network |
| Live account | Real subscription or Console key, real models, real limits | Unproven (P20.4 owner-run smoke) |
| Image generation | Unavailable in the managed profile (P01.6) | Not part of the profile and not claimed |
| Sign-in | Device-code flow in the container; state in a runtime-only volume | Anthropic's own flow in the container; state in a runtime-only volume (section 5) |

## 4. Fixed launch profile and supervisor protocol

P20.1-C fixed the profile for client 2.1.323 as [`tools/runtime_probe/claude_supervisor/supervise.sh`](../tools/runtime_probe/claude_supervisor/supervise.sh), a POSIX `sh` script that is the only way a task reaches the client. It is a runtime-probe artifact. P20.2 puts the same template into the packaged runtime image, and P20.3 makes the backend's adapter its only caller. The caller sends only task data. It never sends flags, paths or environment.

**Protocol.** One supervisor process per operation:

| Request | Fields (each refused unless it matches exactly) | Result |
| --- | --- | --- |
| `run SESSION MODEL EFFORT SCHEMA`, prompt on stdin | `SESSION` is `none`, `new:UUID` or `resume:UUID` (lower-case UUID). `MODEL` matches `claude-[a-z0-9.-]{1,58}`; the engine's allowlist decides which ones are used. `EFFORT` is `low`, `medium`, `high`, `xhigh` or `max`. `SCHEMA` is `none` or the name of a schema file the supervisor owns. The prompt is 1 to 262,144 bytes. | The supervisor `exec`s the client, so the exit status, stdout (stream-json events) and stderr are the client's own, and a signal sent to the supervisor's process ID reaches the client. |
| `retire UUID` | A lower-case UUID | Deletes every path in the home directory whose name holds the UUID, then exits 0 with `retired UUID` only if no path names it and no file contains it. Otherwise it exits 65 with one `remaining PATH` line per path. |
| Any malformed request | — | Exit 64, one `supervisor-refused REASON` line on stderr, nothing started |
| Credentials of the other mode present | — | Exit 66, one `supervisor-guard REASON` line on stderr, nothing started (section 5) |

The container configuration, never the caller, sets `SUPERVISOR_AUTH_MODE` (`api-key` or `subscription`) and `SUPERVISOR_API_KEY_FILE`. The supervisor forwards `ANTHROPIC_BASE_URL` when it is set. Only the probe fixture sets it, and the packaged runtime must not.

**Template.** These elements are fixed. The offline tests of `test_claude_profile.py` check that the script names each one exactly once, and that it names no forbidden option. The Docker checks of section 3.1 run it.

| Element | Fixed value | Reason |
| --- | --- | --- |
| Mode | `-p --output-format stream-json --verbose`, with the prompt on stdin | Events are needed for init, limit and error checks. A positional prompt can be parsed as an option. |
| Tools and extensions | `--tools ""`, `--strict-mcp-config` with no config, `--disable-slash-commands` | No host commands, files, search, extensions or skills (P20.1-B) |
| Permissions | `--permission-mode dontAsk` | Nothing can be approved. `--permission-prompts` takes a target in 2.1.323 and is not used. |
| Instructions | `--system-prompt` with the engine instruction text | Replaces the coding-agent prompt. `--exclude-dynamic-system-prompt-sections` was not adopted: it was accepted in a scratch run, but no test shows what it changes. |
| Output | `--json-schema` with the schema file's content when `SCHEMA` is not `none` | Output contract (section 7) |
| Model and effort | `--model` and `--effort` on every task; never `--fallback-model` | No inherited or silent change (section 8) |
| Session | `--no-session-persistence`, `--session-id UUID` or `--resume UUID` | Renewable AI sessions (section 6) |
| Settings | `--setting-sources ""` and `--settings` holding only the `enabledPlugins` map that sets each of the 14 bundled `name@builtin` plugins of 2.1.323 to `false` (`claude_supervisor/settings.json`, the same map as the P20.1-B profile) | Isolation from inherited configuration and bundled plugins. `--safe-mode` and `--restricted` were not evaluated. |
| Working directory | The engine-owned empty workspace | A `-p` session loads project settings, hooks and `.mcp.json` without a trust prompt |
| Environment | `env -i` with only `HOME`, `PATH=/usr/bin:/bin`, `LANG=C.UTF-8`, `NO_COLOR=1`, `DISABLE_TELEMETRY`, `DISABLE_ERROR_REPORTING`, `DISABLE_AUTOUPDATER`, `DISABLE_FEEDBACK_COMMAND`, `CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY`, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`, `CLAUDE_CODE_DISABLE_AUTO_MEMORY`, `CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP` (all `1`) and `CLAUDE_CODE_MAX_RETRIES=0`, plus `ANTHROPIC_BASE_URL` when set and, in API-key mode only, `ANTHROPIC_API_KEY` read from the key file | No inherited credential or setting. The client retries nothing itself, so the engine reconciles before any retry. No retired model is silently replaced, and no automatic memory is kept. |
| Never used | `--dangerously-skip-permissions`, `--allow-dangerously-skip-permissions`, `--bare`, `--continue`, `--fork-session`, `--add-dir`, `--mcp-config`, `--plugin-dir`, `--plugin-url`, `--agents`, `--allowedTools`, `--remote-control`, `--bg`, `--cloud`, `--teleport`, `CLAUDE_CONFIG_DIR`, `CLAUDE_CODE_RESUME_INTERRUPTED_TURN` | Authority, ambiguity or hidden state |

The client is installed unmodified, as published by Anthropic, at an exact version with an integrity check. The installation method is recorded in P20.1 and in [ADR 0009](adr/0009-toolchain-baseline.md#decision-second-narration-client-pin-and-installation-p201-a). P20.1-A pins npm `@anthropic-ai/claude-code` 2.1.323 with its `linux-x64` platform package. A runtime-probe image build ([`tools/runtime_probe/claude_client/`](../tools/runtime_probe/claude_client/Dockerfile)) checks both registry archives against their SHA-512 integrity, then extracts the platform binary with `tar`. The image has no Node.js or npm, so no `postinstall` or other lifecycle script runs. On 22 November 2026 the image was built and its Docker checks passed in `sh .agents/ralph/verify.sh containers` (runtime-probe-docker: 125 tests, OK, no skips): the selftest stage's rejections, the byte-identical binary and `claude --version` offline as an unprivileged user. The repository does not redistribute the binary. The container follows ADR 0013: read-only root filesystem, dropped capabilities, `no-new-privileges`, an unprivileged UID, a private account volume and IPC, and no route to PostgreSQL.

## 5. Authentication, billing and terms

| Mode | Credentials | Billing | Status |
| --- | --- | --- | --- |
| `subscription` | The owner signs in to the unmodified client in the runtime container through Anthropic's flow. The client's own store in a runtime-only volume holds them. | The owner's plan. Limits assume ordinary individual use. | Working default, gated by the terms review |
| `api-key` | An owner-provided Console key as a runtime-only secret file. | Metered per token. Estimated cost is recorded per task and a `--max-budget-usd` cap may be set. | Explicit opt-in alternative |

Rules that hold in both modes:

- The engine, launcher and backend never read, copy, log, display, transmit or provision credentials, and they do not provision long-lived tokens. The sign-in command of the launcher only runs Anthropic's flow in a one-off runtime container.
- **Startup guard.** The runtime refuses to start when credentials of the other kind are present. In subscription mode this covers `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, an `apiKeyHelper`, `CLAUDE_CODE_OAUTH_TOKEN`, cloud-provider variables and Anthropic profiles. In API-key mode it covers a stored subscription login. It also refuses to start when the home directory holds settings, instructions, hooks, skills, agents, plugins or MCP files. Only the credential file and engine-managed session state may exist. The guard is the engine's own check and does not modify or restrict the client's built-in authentication methods. The P20.1-C probe supervisor already applies the credential part to each task. In API-key mode it refuses a stored login, `CLAUDE_CODE_OAUTH_TOKEN` and `ANTHROPIC_AUTH_TOKEN`. In subscription mode it refuses `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `CLAUDE_CODE_OAUTH_TOKEN` and a present key file (exit 66, fixture `check_refusals`). Its `env -i` drops cloud-provider variables and profiles. The home-directory check, and the refusal at runtime start rather than per task, are P20.2 work.
- **Terms review.** Before Claude is called supported, the owner reviews the [Consumer](https://www.anthropic.com/legal/consumer-terms) or [Commercial](https://www.anthropic.com/legal/commercial-terms) terms for the chosen mode and records the outcome in [the risk register](17-risks-and-open-decisions.md). The documentation does not decide whether a use is permitted.
- **First selection.** The owner acknowledges the billing mode, plan limits, data handling and terms link before the first Claude task.
- **Naming.** Documentation and the interface may state in plain text that the engine runs Claude Code. They must not use Anthropic names or logos in the product name or suggest endorsement ([publication](18-github-publication.md)).

## 6. Sessions, interruption and recovery

- The adapter exposes the same logical operations as Codex: create, resume and retire an AI session. The renewal rules of [memory and continuity](08-memory-and-continuity.md) apply unchanged. Retiring a session deletes its transcript from the runtime volume, or the task ran with `--no-session-persistence`. `cleanupPeriodDays` is not set by the fixed profile, so the client's 30-day default applies as a backstop only for transcripts that were never retired; lowering it is unevaluated.
- **Retention and purge on 2.1.323 (P20.1-C, fixture `check_lifecycle`).** `--session-id UUID` stores exactly one file for the session, `~/.claude/projects/-var-lib-runtime-workspace/UUID.jsonl`, and it holds the prompt in plain text. `--resume UUID` replays it: the resumed request carries the first turn. `--session-id` with an existing UUID fails with exit 1 (`Session ID … is already in use`) and sends nothing. The supervisor's `retire UUID` deletes the file. After that, `--resume UUID` ends with `error_during_execution` and `No conversation found with session ID: UUID`, and sends nothing. A stateless task (`--no-session-persistence`) stores nothing. A scan of the home, the workspace and `/tmp` then finds no path naming the session and no file holding it or any lifecycle prompt. The same scan found the transcript before the purge. The only files left are `~/.claude.json` and its backups under `~/.claude/backups/`, which hold client state and no prompt. Unproven: a transcript with tool calls, attachments or compaction (none occur in this profile), concurrent sessions, and purge while a task runs.
- To interrupt a task, send SIGINT and wait a bounded time. Escalate to SIGTERM and then SIGKILL on the owned process only, and never touch another process. A missing `result` message (for example exit code 143) is ambiguous. The adapter reconciles it against the operation receipt before any retry. It never resumes an interrupted turn implicitly.
- A runtime switch retires every session and rebuilds from canonical checkpoints (ADR 0020, decision 4).
- Process exit, EOF, timeout, stderr size and early output follow the bounded-reader rules of [Codex integration §5](06-codex-integration.md#5-transport-requirements).

## 7. Output validation

The adapter checks that `structured_output` exists, then runs the engine's structural and semantic validators (they do not trust the schema, and `format` is not enforced). `error_max_structured_output_retries` and a missing field count as an invalid proposal: one engine repair, then the deterministic receipt fallback. On 2.1.323 a model that answers in text is asked once more by the client, and the task then ends with `subtype` `success` and no `structured_output` (fixture `schema-unanswered`). The result `subtype` is `success` even for API errors, so the adapter decides from `is_error` and `terminal_reason`, never from `subtype` (section 9). Stream events other than allowed message types, any `tool_use` other than the client's own `StructuredOutput` call in a schema task, and any `permission_denied` event fail the task as an isolation violation. The adapter also checks that `system/init` reports no MCP servers, plugins or skills, and no tools except `["StructuredOutput"]` when a schema was given. Requests of a schema task also offer the client's `DeferredToolPlaceholder`. What a call to it would do is unproven, so such a call is an isolation violation too.

## 8. Models, effort and cost

Logical profiles resolve against an owner-approved allowlist of model identifiers kept in private settings. A neutral readiness task proves that each identifier and effort works. An unsupported pair fails the task clearly and never falls back to a model the owner has not listed in that slot's `alternatives` ([P08.4 resolution](06-codex-integration.md#p084-profile-resolution); the allowlist setting is `engine.inference.allowlist.claude`, in `model@effort` entries). Reported cost (`total_cost_usd`) is a client-side estimate: in subscription mode it is a usage indicator and not a bill. It is recorded for diagnostics and never used to infer remaining quota.

**Readiness approach (P20.1-C).** The client has no model catalog, and its output does not say which effort it applied. It drops an effort it does not support for a model without any warning, so a successful task does not prove the effort was used. Readiness therefore has two layers:

1. **Per pinned version, on fixtures.** A `model@effort` pair may enter an allowlist only if the fixture readiness check has given it the verdict `ready` for the pinned client: the neutral task completed, `system/init` and every request named exactly that model, and every request carried `output_config.effort` with that value. `effort-dropped` pairs are refused. For 2.1.323 (fixture `check_readiness`, `readiness-pairs.txt`) the verdicts are: `claude-sonnet-5-5@high`, `claude-opus-5-5@max`, `claude-haiku-5-5@low` and `claude-fable-5-2@xhigh` are `ready`; `claude-sonnet-4-5@medium` and `claude-opus-4-1@low` are `effort-dropped`; `claude-unlisted-fixture@high` is `model-not-found`. A new client version needs the check again.
2. **Per account, live.** At startup, and after any allowlist change, the adapter runs one neutral readiness task per approved pair through the supervisor. `model_not_found` (or an account error, section 9) marks that pair unavailable, with no fallback. This proves the account can use the model. It cannot prove the effort, which only layer 1 shows. The live layer is P20.3 and P20.4 work and is unproven.

Other effort levels of the same models, and models not in `readiness-pairs.txt`, are unproven.

## 9. Limits and errors

**Observed signals of 2.1.323 (P20.1-C).** Each row is one scripted upstream answer, sent through the fixed profile. The table `SIGNALS` (API-key mode) or `SUBSCRIPTION` (a planted fixture login) in `test_claude_profile.py` asserts it. *Error* is the `error` field of the last `assistant` event. In every row the `result` has `subtype` `success`, and the client exits 1 exactly when `is_error` is true.

| Upstream answer | Mode | Error | `terminal_reason` | `api_error_status` | Requests | `rate_limit_event` |
| --- | --- | --- | --- | --- | --- | --- |
| 401 `authentication_error` | API key | `authentication_failed` | `api_error` | 401 | 1 | none |
| 401 `authentication_error` | subscription | `authentication_failed`, after 2 `api_retry` events | `api_error` | 401 | 2 | none |
| 400 with the credit-balance message | both | `billing_error` | `api_error` | 400 | 1 | none |
| 400 `invalid_request_error` | API key | `unknown` | `api_error` | 400 | 3 | none |
| 400 `prompt is too long` | API key | `invalid_request` | `prompt_too_long` | 400 | 1 | none |
| 529 `overloaded_error` | API key | `server_error` | `api_error` | 529 | 1 | none |
| 500 `api_error` | API key | `server_error` | `api_error` | 500 | 1 | none |
| 404 for the model | API key | `model_not_found` | `api_error` | 404 | 2 | none |
| `max_tokens` stop | API key | `max_output_tokens`, after 3 automatic continuations | `api_error` | none | 4 | none |
| 429 with unified limit headers (`rejected`, five-hour, overage `rejected`) | API key | `rate_limit` | `api_error` | 429 | 1 | none |
| the same 429 | subscription | `rate_limit` | `api_error` | 429 | 1 | `rejected`, `resetsAt` 4102444800, `five_hour`, `overageStatus` `rejected`, `isUsingOverage` false, before the error |
| 200 with headers for overage in use | API key | none, task completed | `completed` | none | 1 | none |
| the same 200 | subscription | none, task completed | `completed` | none | 1 | `rejected`, `overageStatus` `allowed`, `isUsingOverage` and `overageInUse` true, after the answer |
| 200 plain | subscription | none | `completed` | none | 1 | `allowed`, `isUsingOverage` false |

Consequences for the engine:

- `overloaded` is never reported as such. A 529 arrives as `server_error`, like a 500, so the engine uses `api_error_status` to tell them apart.
- API-key mode reports no limit event, and a 429 there is the `rate_limit` error with no reset time. The engine treats it with bounded backoff.
- In subscription mode the overage event arrives after the paid answer is complete. The engine cannot prevent the first overage task. It can only stop every later task until the owner acts. Disabling overage at the account is the only prevention, and the documentation must say so.
- Unproven: `oauth_org_not_allowed`, `account_on_hold`, `verification_required`, `cloud_credential_error`, `blocking_limit`, `aborted_streaming`, `allowed_warning` events, weekly limits, a missing or malformed response, timeouts, and any real-account behavior. The fixture login uses fixture strings, so the client's token refresh and profile calls were never made against a real service.

| Signal | Engine behavior |
| --- | --- |
| `rate_limit_event` `rejected`, `blocking_limit`, or the `rate_limit` error (assistant event or `api_retry`) | Account unavailable (usage limit). Suspend optional admissions until `resetsAt` plus a margin, or use bounded backoff when it is unknown (always in API-key mode). No failover and no overage. |
| `isUsingOverage` or `overageInUse` true | Billing stop. The task that reported it has already completed on overage, so its result is kept, and no later task starts until the owner acts. The engine cannot keep the first overage task from running. |
| `authentication_failed`, `oauth_org_not_allowed`, `account_on_hold` | Account unavailable. The owner must act. Sanitized status. |
| `billing_error` | Billing stop. The owner must act. |
| `terminal_reason` `prompt_too_long` | Context overflow. Renew the session and rebuild the envelope once, or fail clearly. |
| `overloaded`, `server_error` (a 529 or 500), `api_error`, `aborted_streaming` | Transient. Reconcile first, then bounded retry with backoff. |
| `model_not_found`, `invalid_request`, or `unknown` with `api_error_status` 400 | Unsupported runtime configuration. Diagnostic, no retry. |
| `max_output_tokens`, missing `structured_output` | Invalid proposal. One repair, then the receipt fallback. The client has already made 3 continuations, each one paid. |
| Unknown `terminal_reason`, error or event | Treated as transient or unknown, never as success. |

## 10. Isolation evidence required

P20.1 provides evidence in the style of ADR 0013, on synthetic fixtures, with a positive control for each denial:

- the effective tool list in `system/init` and in every request is empty;
- no host command, file edit, search, extension or skill can run, and a canonical sentinel is never written;
- synthetic home settings, project settings, instruction files and hostile flags do not change the profile, or the guard refuses to start;
- credentials of the other kind stop the runtime, and no credential value appears in logs, reports or fixtures;
- the container has no route to PostgreSQL, and only inference egress is open;
- session transcripts stay in the runtime volume and are purged on retirement.

**P20.1-B status (22 November 2026): done; the container rerun on the fixes passed.** [`tools/runtime_probe/test_claude_isolation.py`](../tools/runtime_probe/test_claude_isolation.py) proves, for the pinned 2.1.323 client, only part of the list above: an empty tool list in `system/init` and in every request; no host command, file write, file read or fetch run; home and project settings, instruction files, skills, hooks and MCP servers ignored; and credentials of the other kind left unused in API-key mode. It does not cover hostile flags, and it does not show credentials of the other kind stopping the runtime. It uses the client's documented `ANTHROPIC_BASE_URL` override and a scripted upstream ([runtime probe README](../tools/runtime_probe/README.md#second-narration-client-isolation-p201-b)). A positive control runs the client's defaults over 16 planted home and project files. It must show tools offered and run, hooks, MCP servers, instructions, skills and a configured credential. The isolated profile (section 4, with `--setting-sources ""`) then runs over the same files, in API-key mode with a fixture key and with subscription and first-runtime credentials in its environment. It must expose none of them, refuse four scripted host tool calls and leak no sentinel into any request, output or new file. The supervisor's first container run (22 November 2026) found two problems. First, the isolated profile still loaded three bundled plugins (`cc-plugin-agents-md`, `cc-plugin-privacy`, `cc-plugin-plugin-authoring`, all `@builtin`). Second, the positive control's host command did not run, because the client ignores project `permissions.allow` entries in an untrusted workspace. The profile now also passes `--settings` with the `enabledPlugins` map described in section 4. A scratch run of the edited fixture script over all 16 planted files showed an init event with empty `plugins`, `tools`, `mcp_servers`, `skills` and `slash_commands`; that run had no upstream. Only the positive control marks its workspace as trusted, by adding `projects[<workspace>].hasTrustDialogAccepted` to the planted `~/.claude.json`. The control now also requires built-in plugins to load, and every one it sees to be named in the isolated `enabledPlugins` map. Residuals: the init event still names five built-in agents (`claude`, `Explore`, `general-purpose`, `Plan`, `statusline-setup`). With no tools there is no agent tool to start them, and no planted agent appears. The init event also names an auto-memory path in the home directory. The supervisor's rerun of `sh .agents/ralph/verify.sh containers` on these fixes (22 November 2026, logs in `.agents/ralph/logs/verify/20261122T224140-8474`) exited 0: runtime-probe-docker `Ran 137 tests in 89.887s`, `OK`, container-packaging `Ran 357 tests`, `OK (skipped=6)`. A bundled plugin added by a later client version is not in the map and would fail the Docker test until it is added. The client gives an API key precedence over a subscription login, so it does not refuse a foreign key in subscription mode by itself: the startup guard in section 5 is required. Subscription mode, PostgreSQL reachability and transcript purge are not covered here.

**P20.1-C status (22 November 2026): done; the supervisor's container run passed.** The supervisor's `sh .agents/ralph/verify.sh containers` run on this work (logs in `.agents/ralph/logs/verify/20261122T233042-10323`) exited 0: runtime-probe-docker `Ran 151 tests in 116.372s`, `OK`, which includes the five Docker tests of `test_claude_profile.py` and the P20.1-B Docker tests against the changed upstream; container-packaging `Ran 357 tests`, `OK (skipped=6)`. [`tools/runtime_probe/test_claude_profile.py`](../tools/runtime_probe/test_claude_profile.py) adds the last item of the list above for the fixed profile: a session's transcript stays in the client's home and is purged on retirement, and a scan finds nothing left (section 6). It also adds part of the fourth item: the supervisor refuses credentials of the other mode for each task (section 5), and requests carry only the mode's own credential. Hostile flags cannot reach the client: the caller sends no flags, every field is checked exactly (15 refusal cases), and the prompt is never an argument. PostgreSQL reachability and outbound traffic beyond the fixture network are still not covered.

## 11. Data flow and privacy

Authorized campaign context goes only to Anthropic's service while `claude` is selected, and only to OpenAI's while `codex` is selected. Retention and training depend on the account. Consumer plans offer a training choice (5 years of retention if allowed, 30 days if not) at claude.ai/settings/data-privacy-controls. Commercial plans retain for 30 days by default. The documentation does not promise that deleting local data deletes provider-side data. Locally, a retired session's plaintext transcript is deleted from the runtime volume (section 6), which has no effect on what the provider keeps. Raw prompts and traces are off by default, and diagnostics redact credentials, account identifiers and personal paths.

## 12. Delivery and verification

| Item | Content | Evidence |
| --- | --- | --- |
| P20.1 | Pin and qualify the unmodified client without a live account | Fixtures, isolation evidence, capability matrix, limit and error signals |
| P20.2 | Package the optional runtime service, startup guards and launcher sign-in | Extended QA-28 |
| P20.3 | Adapter, selection, switching and diagnostics behind the neutral task port (`org.rpgengine.inference.NarrationTaskPort`, implemented for Codex in P08.1; a Claude adapter is a sibling package that must pass the same boundary test and neutral fixtures) | Contract tests |
| P20.4 | Parity on shared fixtures, switching, limits, sentinels, owner-run live smoke, terms review recorded | QA-42 |

## 13. References

- [Claude Code: run programmatically](https://code.claude.com/docs/en/headless) — print mode, output formats, structured output, sessions, permissions, signals.
- [Claude Code: authentication](https://code.claude.com/docs/en/authentication) — methods, precedence, credential storage, long-lived tokens.
- [Claude Code: data usage](https://code.claude.com/docs/en/data-usage) — training, retention, telemetry switches, local transcripts.
- [Claude Code: legal and compliance](https://code.claude.com/docs/en/legal-and-compliance) — authentication and credential use, running Claude Code in products, naming.
- These pages describe Anthropic's product and terms. This project's adapter and engine design are its own, not an Anthropic guarantee.
