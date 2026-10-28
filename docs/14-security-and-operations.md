# Security, Privacy, and Operations


P06.1 exposes creation through a protected local adapter with trusted owner
context. It rejects inaccessible adventure/revision scopes before returning stored
identities and accepts no player-supplied item or ability grants. HTTP creation
requires an exact configured loopback Host/Origin and server-side owner,
with bounded ephemeral sessions and independent CSRF proofs. V9
backup scope now includes character state, item instances, learned abilities and
immutable creation requests; older schema archives remain explicitly rejected.

## 1. Deployment threat model

The initial supported product is a local application for one owner. Relevant threats still include malicious adventure files, prompt injection, generated operations outside rules, secret leakage, unsafe asset paths, cross-origin requests to localhost, corrupted saves, duplicate actions, inherited runtime tooling, and mistaken shutdown of unrelated processes.

Publishing the repository does not authorize hosting the backend publicly. Public deployment needs a separate reviewed design. Loopback binding alone is not authentication or full request protection.

## 2. Trust boundaries

Treat player text, imported packs, Markdown lore, AI output, remote media, and restored archives as untrusted data. They cannot override engine permissions. Distinguish owner administration from fictional player actions. A character saying “delete the world files” is game input, not an administrative command.

The canonical writer is the backend application service using constrained database credentials. The runtime process (Codex or Claude) receives no database credentials, canonical filesystem write roots, developer repository, or general administrative API. The frontend receives authorized projections, not raw saves.

P03.2 hardens the database boundary with immutable owner/adventure/campaign
associations, campaign-scoped foreign keys and resource keys, explicit optimistic
row versions, bounded resource balances and bounded JSONB objects. Database
constraints protect storage integrity; they do not authorize reads. Future
application boundaries must verify owner and selected adventure/campaign and use
scoped repository queries, keeping inherited administrative methods private.
P06.1 adds an owner-scoped creation service/API with explicit adventure/revision
authorization. Database row-level security remains unimplemented; QA-20 evidence
covers constraints, scoped persistence queries and creation admission, not future APIs.

## 3. Codex capability controls

These controls apply to every selectable narration runtime; the Claude profile is specified in [Claude runtime integration](33-claude-runtime-integration.md). Local model servers, the image worker and the gateway follow the same container profile plus the rules of [inference backends](34-inference-backends-and-model-selection.md): untrusted weights, no egress for model servers, and keys held only by the gateway. Use a separately scoped runtime directory and an explicit supported restricted capability profile. Verify inherited global/project instructions, plugins, MCP servers, shell tools, filesystem roots, network tools, hooks, and agent delegation rather than assuming they are absent. Record effective policy in diagnostics without secrets.

Read-only filesystem permission controls writes; it does not by itself establish safe reads, eliminate shell access, or constrain every connected tool. Enforce restricted roots and unavailable tools through supported runtime/OS boundaries. The adapter compatibility gate must prove these controls on each claimed platform. If unsupported, disable AI mutation workflows until a supported isolated profile is available.

Prompt text reinforces these rules but is not sufficient enforcement. Structured outputs also need semantic checks. An authorized tool in a future version must expose a narrow capability with its own input validation, audience, quotas, and audit trail.

P02.2 development scope: the backend binds to loopback and exposes only status-only
Actuator health, with no diagnostic endpoint exposure, credentials, gameplay APIs,
or runtime account integration. PostgreSQL configuration is external. These shell
checks do not satisfy the complete HTTP protection or readiness design below.

P01.5 retains only [projected account-free protocol evidence](../tools/runtime_probe/COMPATIBILITY.md#retained-fixtures-and-replay): aliased IDs, selected policy/status fields and independently validated neutral final text. Arbitrary account metadata, raw errors, stderr and private prompts are excluded. Capture never mounts an account volume; new fixtures require field review and isolation checks before widening supported versions or platforms.

## 4. Application HTTP boundary

P06.2 applies the local admission filter to every method in the campaign API
subtree, including unsupported sections and malformed identities. Listing and
all read sections require a live configured-owner session and exact Host; any
supplied Origin/fetch metadata must also pass. GET requires no CSRF proof.
Responses are non-cacheable. Owner/adventure/campaign authorization precedes
child queries, and public DTOs never serialize persistence entities, internal
events, raw pending decisions, planning artifacts or seed identities. Independent
read-only repeatable-read transactions prevent torn revision/data snapshots.
Bounded collections fail closed; missing and foreign save scopes share 404.

`engine.http.owner-id` selects a pre-existing local owner server-side; absent
configuration disables session and campaign routes with 503. The whole campaign
subtree passes admission before routing; admitted unknown routes return 404.
Future APIs outside that subtree must join the admission boundary explicitly.
`engine.http.origin` defaults to
`http://127.0.0.1:8080` and accepts only an exact HTTP loopback origin with explicit
port. API Host must match its authority. Forwarded headers are not trusted.
`GET /api/v1/session` returns a random CSRF token and sets an independent 256-bit
`ENGINE_SESSION` HttpOnly, SameSite=Strict, path `/` cookie. Neither secret is
logged or persisted. Sessions expire after 30 minutes and restart invalidates
them; capacity is 32 without eviction of live sessions. Reuse the cookie for
bootstrap to avoid allocating another slot. Cross-site fetch metadata and
nonmatching/duplicate Origin or Host headers are denied. Mutations require the
exact Origin, cookie, `X-CSRF-Token`, UTF-8 JSON and one of four concurrent slots.
API responses disable caching and MIME sniffing. This boundary trusts local
processes and same-origin scripts; it is not public-hosting authentication.

Bind to loopback; use one origin for release frontend/backend where practical. Validate Host, Origin, content type, body size, and local session identity on mutation endpoints. Use an unpredictable local application session/CSRF token stored with appropriate browser restrictions rather than treating a fixed custom header as authentication. Do not enable wildcard CORS.

Validate all campaign IDs and object references against the current owner. Apply the same authorization to SSE, exports, asset IDs, and diagnostics. Return inaccessible objects as safe not-found responses where appropriate. No arbitrary directory browsing, file-serving route, or remote URL proxy exists.

Campaign authorization also resolves its owning adventure workspace. Specifications, narrative memory, drafts, jobs and generated-image paths cannot cross adventure/campaign scopes, even for equal entity names. Reject selected-adventure mismatches and escaped filesystem roots; follow [workspace isolation](25-adventure-workspaces.md).

## 5. Content and media security

Pack imports use the containment, parser, size, alias, and archive limits in [the pack specification](09-adventure-packs.md). Reject unsupported executable types. Never render raw HTML from lore or model output without a strict sanitization policy; the default narrative format is plain text or a small safe Markdown subset.

P04.4 stages ZIP bytes entirely in memory, checks both ZIP views and bounded
inflation, and admits only allowlisted media signatures and declared image sizes.
Directory sources must be owner-controlled and quiescent: static links/reparse
points are rejected, but portable path-based traversal does not exclude a local
process swapping intermediate directories during import. Use ZIP for hostile
distribution. Media headers are not full decoder validation; no serving endpoint
exists. Limit decoder allocation/frames/duration before introducing playback.
Per-pack memory caps also need request concurrency limits before an import API.

Registered bytes and reports are immutable database rows. V7 serializes sealing
and file insertion under READ COMMITTED, checks stored sizes/hashes and rejects
later files or mutations. Failed registration rolls back all bytes. These guards
protect ordinary application writes, not a database administrator disabling
triggers or truncating tables. License declarations are preserved, not evidence
of redistribution rights; export still requires provenance/rights review.
P04.6 keeps localized text in a separate catalog: callers must authorize the source
owner/field before resolving it. Neither locale coverage nor glossary metadata is
an audience projection. Report version 2 adds deterministic coverage; V8 widens
only the accepted version constraint and retains version 1 seals unchanged.

Serve registered assets with validated MIME type, content hash, containment checks, and `nosniff`. Disallow active SVG scripts or use a validated static subset; a file extension is insufficient. Default CSP permits only required local sources. Remote audio/image origins require an explicit configured allowlist and owner preference, with no arbitrary model-generated URL fetch.

Bounded image jobs use a separately verified restricted capability, public appearance inputs, finite budgets and managed temporary/output roots. Do not expose canonical storage, database credentials, private NPC notes or unrestricted developer tools to obtain an illustration. A generated image is untrusted media until validated and registered; it cannot change item data or choose its own serving path. Keep saved originals and subject associations in audience-aware backup/restore, as specified in [presentation assets](23-presentation-assets.md).

P01.6 [records explicit image unavailability](../tools/runtime_probe/IMAGE_CAPABILITY.md)
for the managed narration profile and refuses generation before runtime/account
access. Empty generation formats grant no media acceptance. Text sign-in/catalog
success and development-client image tools cannot establish runtime entitlement
or cost authorization; bounded policy alone cannot override this refusal.

## 6. Secrets and audience separation

P03.4 stores private 32-byte campaign random seeds in `campaign_random_seed`,
separate from canonical entities and receipt/outbox projections. These are gameplay
secrets: database/backups remain trusted and require the same access protections as
hidden world data; this is not encryption at rest. Never log/export seed bytes to
runtime contexts or owner-facing diagnostics. Uncommitted draw values are internal
preparation data; only a committed receipt exposes its operation's values/references.
Seeds and immutable draw reservations must be retained together in backups; preserve
the pinned algorithm/version when restoring. The P03.5 database backup captures both
in one `pg_dump` snapshot. Application authorization and equivalent-new-attempt policy remain future
gates; durable same-operation reuse alone does not prevent new-operation reroll fishing.

Never put runtime credentials (Codex or Claude), database passwords, `.env` files, auth stores, private saves, personal notes, or raw provider traces in Git. Diagnostic exports redact keys, tokens, absolute personal paths, private content, and RPC identifiers unless an owner explicitly selects a suitably protected full diagnostic mode.

The tracked root [`.env.example`](../.env.example) contains only the consumed loopback application port and neutral database labels. The labels do not configure authentication. Supply passwords through private secret files and account state through the supported owner-controlled sign-in flow; never add either to a public example. Ignore rules are a staging safeguard, not a secret scanner or a substitute for reviewing the staged content. The root and independent runtime `.dockerignore` policies now default-deny build inputs and recursively exclude private names inside admitted source trees. Real BuildKit fixture exports verify both policies (`py -B -m unittest discover -s tools/container_packaging -v`); they do not scan arbitrary source content or establish full application containment.

Player DTOs must omit unrevealed lore, private NPC knowledge, relationship internals, hidden encounter participants/statistics, and planner reasoning. Use separate role/audience threads so secret context cannot persist invisibly into a public narration task. Prevent media/audio choices from leaking a hidden threat before it is observed.

P08.3 enforces the thread separation for inference ([docs/06 section 7](06-codex-integration.md#p083-session-scoping)). Every task runs in the session of its campaign, generation, role, audience, pack hash and policy hash. Player-facing roles can never be scoped to the privileged audience. Only the scoping registry may submit to the runtime port, and tests enforce this at the source level. This prevents context carry-over through a shared conversation. It does not filter what a caller puts into a public context, and it does not detect a paraphrased secret in output.

The root Compose shell gives only PostgreSQL and engine the database file
secret. Runtime account/workspace volumes are separate from canonical storage;
the runtime joins inference egress only, never the internal data network. Only
engine publishes HTTP on host loopback. Local Compose secrets are read-only host file
mounts, not encrypted storage. The launcher still must create and restrict the
host secret file in P02.6. PostgreSQL's official entrypoint initializes ownership
as root and then drops privileges; engine and runtime use UID/GID 10001, drop all
capabilities and have read-only root filesystems. Engine mounts only read-only
socket IPC plus engine-only content/assets, never runtime account/workspace.
The account-free integration test verifies HTTP, socket access,
password-authenticated TCP and neutral persistence on recreation of all services;
it never loads an account. This does not establish tool/inference containment or
account readiness. See [deployment details](22-container-deployment-and-startup.md).

The backend's opt-in Spring `packaged` profile now requires a config tree at
`/run/secrets/` and consumes `postgres_password` without a password environment
variable or command argument. Missing mount/password properties abort startup;
normal explicit Spring overrides still apply. Tests use only neutral temporary
files and cover trailing newlines and paths with spaces. This is application
configuration and verified Compose engine secret wiring on Windows Docker
Desktop Linux containers. Native Linux secret-file UID readability remains a
host qualification requirement: do not solve it by making secrets world-readable.

## 7. Data storage and retention

Store application data outside the source checkout. The packaged profile uses separate Docker-managed persistent volumes for PostgreSQL, registered content/assets, and private runtime state; host development may use an OS-appropriate owner data directory. Separate exports, logs, and temporary workspaces, and record their actual locations and retention during implementation. Never rely on a container's writable layer for durable campaigns. See [container deployment](22-container-deployment-and-startup.md).

Default retention proposal: committed state/events until owner deletion; five rolling recovery snapshots plus daily backups according to configured retention; ordinary diagnostic logs seven days; raw AI prompts/traces off by default; expired temporary workspaces cleaned after task reconciliation. Retention is configurable and recorded in settings. Do not claim deletion from the local disk also deletes provider-side data; account/service policies are separate.

P03.3's internal canonical commit accepts bounded, already-resolved backend data and
rejects ambient transactions. Only database/JSON dependencies execute inside it;
inference and delivery must run outside. Campaign/journal locks, exact revision and
resource version checks protect atomic state/events/settlements/receipt/outbox writes.
Immutable outbox envelopes contain scoped references only; future workers must build
authorized projections and deduplicate delivery by message identity. No worker,
acknowledgment, pruning policy or owner authorization endpoint is delivered here.
P03.5 adds a database-clock campaign mutation lease with a monotonic fence; commits
and new draws require the current token, and a commit releases it atomically. See [commit limits and timeout contract](07-turn-protocol.md#5-canonical-commit).

## 8. Backups and restore

An independently restorable backup includes database data, referenced immutable content revisions, registered asset files, schema/version manifest, and checksums. A copy of one summary or a checkpoint in the same database is not sufficient.

Create backups before migration, restore replacement, and significant canon correction. Restore first validates archive limits, hashes, schema versions, content dependencies, ruleset support, and inventory/encounter invariants. Default restore creates a new campaign identity with source provenance, avoiding accidental overwrite. Replacing an existing campaign requires a concrete preview and explicit owner action.

Test restoration into a fresh database and asset directory. Verify sample encounters, quests, operations, and pending decisions. A backup job is not considered successful merely because an archive file exists.

P03.5 delivers a baseline [database backup tool](../tools/database_backup/README.md) for a
trusted owner with writers stopped. It produces a `pg_dump` custom archive and a
manifest written last, holding the version, catalog, size and SHA-256. Restore
validates the archive before any write and goes only into a newly created database,
in one transaction. It compares the migration catalog and retires every saved
mutation lease; engine startup reconciliation then runs before HTTP. A manual
PostgreSQL 18.6 rehearsal checked backup, refusal to reuse a directory, tamper
rejection, restore, data equality, lease retirement and reconciliation on startup.
External assets, unregistered packs, campaign-identity restore, replacement preview
and the full QA-23 rehearsal remain later work (P16, P18). P04.6 extends the exact
backup allowlist to V8, including registered original pack bytes, hashes, exports
and both sealed report versions; it does not rewrite old seals or accept older
archive schemas implicitly. A disposable V8 dump/restore rehearsal preserved both
complete bilingual fixture inventories, raw hashes, exports, metadata and sealed
report versions 1/2 exactly, with the source unchanged. Reports were explicit
recovery fixtures; this does not establish a historical pack import or full QA-23.

P03.6 extends that tool to V6 and all preparation tables. After verified restore,
one transaction increments recovery generations/storage versions, appends an
invalidation to usable plans and retires mutation leases. All old writers/runtime
workers must be stopped: this is single-installation recovery, not concurrent
campaign cloning. Job startup reconciliation supersedes unfinished old-generation
work and preserves attempt history before claims/admissions are allowed. A populated
V6 rehearsal on 24 October 2026 preserved snapshots, plan/admission bytes, delivered
critical-job origins and Italian preferences; generation 0→1 and fence 4→5 retired
saved capabilities, the plan became invalidated, and jar startup superseded a saved
running job. World revision/turn/time/event counters remained 7/3/60/12.

## 9. Startup and shutdown

The packaged entry point is the future root `start.sh`. It orchestrates the Compose stack, validates prerequisites and account readiness, and preserves existing configuration and volumes on repeated start or stop. The backend sequence below runs inside that deployment. Database health and backend readiness must be checked explicitly. Runtime account availability is a separate gameplay-readiness state, not a reason to erase data or a live-inference health probe.

Startup order: load configuration → verify filesystem permissions → connect database → validate/apply supported migrations after required backup policy → reconcile unfinished operations, leases and preparation jobs (P03.5–P03.6; a failure aborts startup) → check content dependencies → start HTTP → lazily connect Codex when requested or when an opted-in readiness check runs.

An unavailable narration runtime does not prevent local inspection of a valid saved campaign. A missing database or incompatible schema blocks mutation. Startup never silently resets a campaign.

For the packaged profile, the backend closes owned turns/connections and Compose stops only that installation's services with an appropriate grace period. Direct child-process termination applies only to the local stdio probe/development profile. Stop and restart preserve persistent volumes; destructive reset and independent backup/restore remain separate owner operations.

Shutdown: reject new mutation requests → stop admitting background jobs → request cancellation of live AI tasks → reconcile receipts → persist final operation states → stop or flush bounded jobs → close owned runtime input and wait → terminate only owned child processes if necessary → stop HTTP and close database pools. Closing a browser tab does not necessarily stop the backend. The UI must explain actual application status.

## 10. Observability

Measure local read latency, rule execution time, database commit time, AI queue/generation latency, time to first permitted narrative, context estimate, output validity, repair count, session renewal reason, encounter fallback rate, and stale-summary jobs. Correlate operation, campaign, source revision, and adapter generation using privacy-safe IDs.

Avoid logging complete prompts or private dialogue by default. Structured error codes should diagnose unsupported runtime, account unavailable, context overflow, invalid proposal, illegal action, stale state, lost lease, interrupted before commit, and committed narration pending.

The P01.2 probe retains only recognized runtime error variants; unknown strings or object keys become `unclassified`, since even an unknown error key can contain private data. Its final report omits raw errors, stderr and upstream bodies. Only the account-free scripted fixture logs upstream request bodies (neutral text plus runtime instructions) for settings/schema comparison, and its containers are removed afterwards. This fixture logging must not be enabled for live tasks. The opt-in live profile requires an explicitly named existing account volume and never copies host credentials or creates a missing volume. Internal fixture networking alone does not establish host/tool containment; the P01.3 managed tool-less profile and container isolation are verified separately ([ADR 0013](adr/0013-narration-runtime-isolation.md)).

P01.4 extends diagnostics with a bounded stderr summary: bytes classified (at most 65,536), truncation, line/level counts and a 16-entry tail. Line prefixes are limited to 512 bytes; raw text is discarded, including after the byte limit while the pipe continues to drain. Exact known module labels are allowlisted; unknown log-shaped labels become `unclassified` to prevent newline injection from smuggling private text into a diagnostic field. Shutdown records contain stage, exit code and duration only. EOF closes the RPC generation and discards/counts its buffered events. An unresolved request requires fresh-connection reconciliation; a timeout does not authorize replay or prove upstream cancellation.

## 11. Performance budgets

P01.7's [bounded qualification](../tools/runtime_probe/CONCURRENCY.md) explicitly
selects one active inference turn installation-wide. Scripted overlap measures
no live throughput, account headroom or service priority. Do not release a
permit on interrupt acknowledgment or connection loss; await terminal
confirmation/reconcile uncertain work. Production priority, finite queues,
fairness and quota/backpressure/restart checks remain P08/QA-37 work. Optional
jobs stay disabled or in explicit idle maintenance windows when safe preemption
is unproved; higher concurrency requires P08.6's authorized live evidence.

Initial targets on documented reference hardware with a warmed local database: public status/inventory query p95 under 200 ms; deterministic combat calculation excluding persistence p95 under 50 ms; normal canonical commit p95 under 250 ms; operation acceptance under 300 ms. These are measurable targets, not achieved benchmarks.

Do not give an end-to-end AI latency guarantee before measurement. Record provider latency separately from application overhead and include account/profile, context size, and test sample size in reports. A faster response with broken continuity is not an optimization.

## 12. Upgrades and incident recovery

Research extensions keep background planning and local media within the same
trust model. [Plan admission](28-asynchronous-narrative-planning.md) checks complete
engine-recorded dependencies, query epochs, ownership and fences twice; neither
a reviewer approval nor a model echo authenticates an output. Privileged plans,
budgets, job names and errors are never public narrative/media input. Reserve
interactive capacity, cap retries/speculation and preserve usage accounting
through restart/renewal; multiple runtime processes do not multiply entitlement.

The optional [speech worker](30-local-speech-synthesis.md) has no canonical-store,
runtime-account, host-home or Docker socket mount. Admit fixed public model
revisions and rights before an explicit installation, validate audio bounds and
reference types, keep consented private voice references out of ordinary story
exports, and reject arbitrary URLs, serialized code and model-supplied SSML.
GPU admission/OOM/worker loss falls back to text and must not restart a game
action. Locale changes and translated errors cannot bypass audience filtering.

Story bundles are untrusted imports regardless of sender; preview rights,
compatibility and IDs, enforce existing archive limits and create fresh local
identities. Do not transfer account credentials or private playthrough history
with a reusable authored adventure. See [portable stories](09-adventure-packs.md#14-portable-stories-and-person-to-person-sharing).

Optional [STT input](35-speech-input-and-hands-free-play.md) has a separate
microphone boundary: explicit enablement and capture status, independent mute,
bounded ephemeral audio, admitted owner/session/origin checks and no account or
hidden-state access for the recognizer. Scope audio, vocabulary, drafts and late
results by adventure/campaign/capture generation. Raw recordings stay out of
ordinary logs, backups and exports; diagnostic retention requires separate opt-in.
No silent browser/OS/cloud recognition fallback is allowed. Treat transcripts as
untrusted drafts, bind explicit confirmations to one current preview, suppress
engine playback echo and route only confirmed input through the existing command
pipeline. Silence, reconnect or recognition failure cannot authorize a world
change. QA-45 qualification is pending; capture remains unimplemented/disabled.

Maintain supported engine, pack schema, ruleset, database, and narration runtime adapter compatibility tables (one per supported runtime). Back up before an upgrade that changes persistent formats. Unsupported inputs produce a controlled migration-required state. Do not silently downgrade content or rules.

For a suspected incorrect action, preserve the operation receipt, source event references, and last good backup. Disable further mutation if invariants are violated. Use an explicit correction or restore after diagnosis. Do not edit event history to conceal the fault.
