# ADR 0014 — Bounded asynchronous narrative planning

- Status: Accepted; P03.6 persistence/admission foundations verified, full runtime integration and benefit verification pending
- Date: 23 October 2026

## Context

Important mysteries and recurring conflicts can benefit from preparation beyond a single immediate narration task. Generating a whole arc before acknowledging a player action adds latency and can make the story depend on assumptions that become false while the player acts. More roles or active threads do not by themselves establish better quality, lower latency or more account capacity.

P01.2 demonstrates overlapping turns against a scripted upstream: one thread's held request stays open while another completes. The neutral live smoke proves one structured task and resume, not concurrent service throughput. P01.3's rebuilt tool-less profile has deterministic isolation evidence; its live recheck remains quota-blocked. P01.4 proves that a lost packaged connection can leave a turn active. Preserve this scope and [ADR 0013](0013-narration-runtime-isolation.md).

## Decision

Keep the modular Spring Boot backend, PostgreSQL and one isolated Codex runtime. Add a durable backend priority queue with distinct role/audience threads. Serialize inference by default with one active turn installation-wide. Interactive intent/narration has priority over optional quest planning, occasional world direction and any reviewer. P01.7 records capability limits; additional live concurrency requires the explicit P08.6 qualification in the [planning specification](../28-asynchronous-narrative-planning.md#9-priority-cancellation-quotas-and-restart). A negative concurrency result retains the serialized baseline; a runtime pool or agent framework is not required.

Store immutable versioned planning snapshots, complete engine-recorded read dependencies, conditional quest-plan artifacts and admission receipts. Track query membership/absence as well as entity versions to detect phantoms. Revalidate relevant dependencies rather than rejecting everything whenever the global revision advances. Recheck generation, dependencies, fence and cancellation inside a short admission transaction and again through the normal command path at use.

Planning completion only saves preparation metadata. It cannot advance world time, disclose a secret, award a reward, create a canonical fact or adopt/resolve a pending world event. Those changes remain ordinary backend-owned command transactions with exact current revision checks, deterministic rules, event provenance and idempotency. Private planner contexts never become public narrator contexts. Codex receives no additional tools or mutation privileges.

Keep a complete playable authored/procedural baseline when the planner is pending, stale, cancelled, over quota or unavailable. Use flexible motives, conditional opportunities and causal support, with abandonment and unexpected lawful player solutions. Never require the protagonist to follow a generated plot or invent pending-plan facts in final narration.

Bound jobs, leases/fences, retries, speculative work, cancellation/reconciliation and per-campaign/global account budgets. Interrupt acknowledgment is not terminal confirmation; an uncertain upstream task retains its accounting/permit until reconciliation. No implicit paid overage, API/provider fallback or development-orchestrator substitution is allowed. English is the default and Italian has equivalent supported narration/quality checks.

## Alternatives

| Alternative | Disposition |
| --- | --- |
| Single narrator without advanced preparation | Retain as complete fallback and comparison baseline |
| Shared runtime with distinct threads and serialized durable queue | Selected initial topology; least new operational machinery |
| Several active turns on one runtime | Optional after correct routing, cancellation, priority, quota/restart and measured latency/usage evidence |
| Multiple instances/runtime pool | Defer until profiling establishes a benefit beyond shared-account limits and lifecycle cost |
| Autonomous agent framework or planning microservice | Reject as an initial dependency; bounded jobs and existing modules suffice |
| Full HTN/GOAP solver or per-NPC LLM loop | Defer solver integration; adapt typed goals, conditions and selective replanning without requiring another runtime |

## Consequences

Durable job/dependency/admission foundations add persistence and race-test work, but reuse the same authority and recovery principles as existing summary/media jobs. Precise query invalidation needs every canonical writer to maintain the relevant epochs; conservative scopes are preferable to incomplete dependency tracking. Natural-language consistency and narrative quality still need human evaluation.

The MVP remains the simple bilingual playable loop. P14 can demonstrate one optional baseline/planner comparison or document its unavailability without blocking MVP fallback. v1 requires safe durable foundations and the minimum bounded planner contract/fallback at P03.6/P07.6/P08.6–P08.7 before enabling optional deep preparation. Adaptive arcs/director/reviewer remain independently gated at P15.6/P18.6; larger orchestration and the E-series editor remain later scope.

Full-owner restore preserves relevant artifacts and pending events, remaps identities, retires old runtime sessions/leases and validates dependencies before reuse. Player exports exclude secret plans. Thread renewal does not reset job/account budgets or alter the campaign's time and next decision.

## Verification

P03.6 implementation decision (24 October 2026): capture immutable selected inputs
with explicit assembler freshness tokens and a full typed engine manifest; reject
an admission that omits or changes that manifest. Under a campaign lock, recheck
query epochs, job fence/deadline, recovery generation and public locale/glossary
tokens. Unsupported non-query semantics fail closed rather than being treated as
validated. Every future gameplay writer must bump all affected query scopes in its
canonical transaction; no automatic predicate evaluator is implied by this storage
foundation. Critical job enqueue and invalidation share that transaction's outbox
boundary. Append immutable versions and admission/disposition history without world
changes. Recovery never requeues a possibly live expired attempt until explicit
stop confirmation, and installation restore advances generations and invalidates
usable plans before job startup reconciliation. Real PostgreSQL races, rollback,
restart and V6 restore checks pass; scheduling and semantic validation remain
P07/P08. The [evidence policy](../15-quality-and-acceptance.md#1-evidence-policy)
records counts and limits.

P01.7 now explicitly selects the serialized baseline: one active inference turn
installation-wide. Its [bounded evidence](../../tools/runtime_probe/CONCURRENCY.md)
qualifies real managed-runtime interleaved dispatch, targeted interruption and
client-controlled serialized priority against an internal scripted upstream.
Live simultaneity/headroom, quota behavior, cancellation latency and service
priority remain unverified. No production scheduler or throughput result is
claimed. P08.6 still gates every increase, and the P01 live isolation recheck
remains required. This confirms the existing decision without changing topology.

P08.6 (28 October 2026) implements the selected topology's durable priority queue
without changing this decision: one fair scheduler with isolated role/audience lines,
interactive reservation and preemption, bounded queue places, rolling usage quotas,
a durable account backoff, cancellation-terminal and restart reconciliation, and one
active turn enforced by setting and by a database index. Its evidence is deterministic
(real PostgreSQL with a scripted runtime, the real adapter against the scripted
app-server); live concurrency, quota and priority behavior remain unqualified
([docs/06 section 12](../06-codex-integration.md#p086-durable-fair-scheduling)).

P09.3 (28 October 2026) adds continuity summaries as the second background kind on the same
queue (`CONTINUITY` class) with their own bounded job table rather than `ai_job`: one unfinished
job per campaign and audience, an installation-wide cap, fenced leases, a limited number of
attempts and no automatic retry of a failed range. Summary freshness revalidates the exact records
a summary used (row versions) instead of broad scope epochs, following the selective-staleness
rule above ([docs/08 section 8](../08-memory-and-continuity.md#p093-checkpoints-summaries-freshness-and-bounded-jobs)).

P09.4 (28 October 2026) adds no background kind. Rehydrating a renewed session may request a
summary of the uncovered tail; the request passes through the same job bounds (one unfinished
job per audience, the installation cap, no retry of a failed range) and is produced later by
the bounded worker. Renewal itself runs at the line's next dispatch, never as a job.

Planned AP-01–AP-12 cover selective staleness, query phantoms, admission races, duplicate workers, fencing, cancellation/EOF/restart, play-before-plan fallback, world-time separation, secret sinks, cross-adventure fairness/restore, author constraints and bilingual A/B/C evaluation. Compare single narration, narration plus planning, and optional review with measured quality, p50/p95 latency, usage and waste. P03.6 covers only the storage/admission subset above. P07.6 adds the transactional scope/content/author/typed-dependency gate and activation through ordinary commands, with deterministic real-PostgreSQL evidence for the AP-01–AP-05 admission assertions and AP-08's no-canonical-change rule; no full AP acceptance or superiority is claimed. Evidence, proposed objectives, ownership and source links are in the [planning specification](../28-asynchronous-narrative-planning.md).

P08.7 (28 October 2026) implements the minimum bounded planner contract and fallback that
this decision requires for v1 without enabling it: the versioned `quest-plan`/`quest-plan-refine`
contract with a closed predicate vocabulary, selective dependency validation (reads narrowed to
the branches whose predicates cite them, never dropped), bounded replanning (one refinement per
stale version, a per-plan allowance) and the playable no-plan baseline (planning off by default;
no gameplay path depends on it). Evidence is deterministic (pure contract corpus, real PostgreSQL
with a scripted planner); the AP-12 comparison and any default change remain evidence gated
([docs/28 section 7](../28-asynchronous-narrative-planning.md#selective-narrowing-and-replanning-p087)).
