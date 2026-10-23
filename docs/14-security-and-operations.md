# Security, Privacy, and Operations

## 1. Deployment threat model

The initial supported product is a local application for one owner. Relevant threats still include malicious adventure files, prompt injection, generated operations outside rules, secret leakage, unsafe asset paths, cross-origin requests to localhost, corrupted saves, duplicate actions, inherited Codex tooling, and mistaken shutdown of unrelated processes.

Publishing the repository does not authorize hosting the backend publicly. Public deployment needs a separate reviewed design. Loopback binding alone is not authentication or full request protection.

## 2. Trust boundaries

Treat player text, imported packs, Markdown lore, AI output, remote media, and restored archives as untrusted data. They cannot override engine permissions. Distinguish owner administration from fictional player actions. A character saying “delete the world files” is game input, not an administrative command.

The canonical writer is the backend application service using constrained database credentials. The Codex process receives no database credentials, canonical filesystem write roots, developer repository, or general administrative API. The frontend receives authorized projections, not raw saves.

## 3. Codex capability controls

Use a separately scoped runtime directory and an explicit supported restricted capability profile. Verify inherited global/project instructions, plugins, MCP servers, shell tools, filesystem roots, network tools, hooks, and agent delegation rather than assuming they are absent. Record effective policy in diagnostics without secrets.

Read-only filesystem permission controls writes; it does not by itself establish safe reads, eliminate shell access, or constrain every connected tool. Enforce restricted roots and unavailable tools through supported runtime/OS boundaries. The adapter compatibility gate must prove these controls on each claimed platform. If unsupported, disable AI mutation workflows until a supported isolated profile is available.

Prompt text reinforces these rules but is not sufficient enforcement. Structured outputs also need semantic checks. An authorized tool in a future version must expose a narrow capability with its own input validation, audience, quotas, and audit trail.

P02.2 development scope: the backend binds to loopback and exposes only status-only
Actuator health, with no diagnostic endpoint exposure, credentials, gameplay APIs,
or runtime account integration. PostgreSQL configuration is external. These shell
checks do not satisfy the complete HTTP protection or readiness design below.

## 4. Application HTTP boundary

Bind to loopback; use one origin for release frontend/backend where practical. Validate Host, Origin, content type, body size, and local session identity on mutation endpoints. Use an unpredictable local application session/CSRF token stored with appropriate browser restrictions rather than treating a fixed custom header as authentication. Do not enable wildcard CORS.

Validate all campaign IDs and object references against the current owner. Apply the same authorization to SSE, exports, asset IDs, and diagnostics. Return inaccessible objects as safe not-found responses where appropriate. No arbitrary directory browsing, file-serving route, or remote URL proxy exists.

Campaign authorization also resolves its owning adventure workspace. Specifications, narrative memory, drafts, jobs and generated-image paths cannot cross adventure/campaign scopes, even for equal entity names. Reject selected-adventure mismatches and escaped filesystem roots; follow [workspace isolation](25-adventure-workspaces.md).

## 5. Content and media security

Pack imports use the containment, parser, size, alias, and archive limits in [the pack specification](09-adventure-packs.md). Reject unsupported executable types. Never render raw HTML from lore or model output without a strict sanitization policy; the default narrative format is plain text or a small safe Markdown subset.

Serve registered assets with validated MIME type, content hash, containment checks, and `nosniff`. Disallow active SVG scripts or use a validated static subset; a file extension is insufficient. Default CSP permits only required local sources. Remote audio/image origins require an explicit configured allowlist and owner preference, with no arbitrary model-generated URL fetch.

Bounded image jobs use a separately verified restricted capability, public appearance inputs, finite budgets and managed temporary/output roots. Do not expose canonical storage, database credentials, private NPC notes or unrestricted developer tools to obtain an illustration. A generated image is untrusted media until validated and registered; it cannot change item data or choose its own serving path. Keep saved originals and subject associations in audience-aware backup/restore, as specified in [presentation assets](23-presentation-assets.md).

## 6. Secrets and audience separation

Never put Codex credentials, database passwords, `.env` files, auth stores, private saves, personal notes, or raw provider traces in Git. Diagnostic exports redact keys, tokens, absolute personal paths, private content, and RPC identifiers unless an owner explicitly selects a suitably protected full diagnostic mode.

The tracked root [`.env.example`](../.env.example) contains only a proposed application port and neutral database labels; the database service now consumes its database labels. The application port is not consumed yet. The labels do not configure authentication. Supply passwords through private secret files and account state through the supported owner-controlled sign-in flow; never add either to a public example. Ignore rules are a staging safeguard, not a secret scanner or a substitute for reviewing the staged content. The root and independent runtime `.dockerignore` policies now default-deny build inputs and recursively exclude private names inside admitted source trees. Real BuildKit fixture exports verify both policies (`py -B -m unittest discover -s tools/container_packaging -v`); they do not scan arbitrary source content or establish full application containment.

Player DTOs must omit unrevealed lore, private NPC knowledge, relationship internals, hidden encounter participants/statistics, and planner reasoning. Use separate role/audience threads so secret context cannot persist invisibly into a public narration task. Prevent media/audio choices from leaking a hidden threat before it is observed.

The partial root Compose infrastructure gives only PostgreSQL the database file
secret. Runtime account/workspace volumes are separate from canonical storage;
the runtime joins inference egress only, never the internal data network, and no
service publishes a host port. Local Compose secrets are read-only host file
mounts, not encrypted storage. The launcher still must create and restrict the
host secret file in P02.6. PostgreSQL's official entrypoint initializes ownership
as root and then drops privileges; the runtime uses UID/GID 10001, drops all
capabilities and has a read-only root filesystem. The account-free infrastructure
test verifies password-authenticated TCP and neutral persistence on recreation;
it never loads an account. This does not establish tool/inference containment or
account readiness. See [deployment details](22-container-deployment-and-startup.md).

## 7. Data storage and retention

Store application data outside the source checkout. The packaged profile uses separate Docker-managed persistent volumes for PostgreSQL, registered content/assets, and private runtime state; host development may use an OS-appropriate owner data directory. Separate exports, logs, and temporary workspaces, and record their actual locations and retention during implementation. Never rely on a container's writable layer for durable campaigns. See [container deployment](22-container-deployment-and-startup.md).

Default retention proposal: committed state/events until owner deletion; five rolling recovery snapshots plus daily backups according to configured retention; ordinary diagnostic logs seven days; raw AI prompts/traces off by default; expired temporary workspaces cleaned after task reconciliation. Retention is configurable and recorded in settings. Do not claim deletion from the local disk also deletes provider-side data; account/service policies are separate.

## 8. Backups and restore

An independently restorable backup includes database data, referenced immutable content revisions, registered asset files, schema/version manifest, and checksums. A copy of one summary or a checkpoint in the same database is not sufficient.

Create backups before migration, restore replacement, and significant canon correction. Restore first validates archive limits, hashes, schema versions, content dependencies, ruleset support, and inventory/encounter invariants. Default restore creates a new campaign identity with source provenance, avoiding accidental overwrite. Replacing an existing campaign requires a concrete preview and explicit owner action.

Test restoration into a fresh database and asset directory. Verify sample encounters, quests, operations, and pending decisions. A backup job is not considered successful merely because an archive file exists.

## 9. Startup and shutdown

The packaged entry point is the future root `start.sh`. It orchestrates the Compose stack, validates prerequisites and account readiness, and preserves existing configuration and volumes on repeated start or stop. The backend sequence below runs inside that deployment. Database health and backend readiness must be checked explicitly. Runtime account availability is a separate gameplay-readiness state, not a reason to erase data or a live-inference health probe.

Startup order: load configuration → verify filesystem permissions → connect database → validate/apply supported migrations after required backup policy → reconcile unfinished operations and leases → check content dependencies → start HTTP → lazily connect Codex when requested or when an opted-in readiness check runs.

An unavailable Codex process does not prevent local inspection of a valid saved campaign. A missing database or incompatible schema blocks mutation. Startup never silently resets a campaign.

For the packaged profile, the backend closes owned turns/connections and Compose stops only that installation's services with an appropriate grace period. Direct child-process termination applies only to the local stdio probe/development profile. Stop and restart preserve persistent volumes; destructive reset and independent backup/restore remain separate owner operations.

Shutdown: reject new mutation requests → stop admitting background jobs → request cancellation of live AI tasks → reconcile receipts → persist final operation states → stop or flush bounded jobs → close owned Codex input and wait → terminate only owned child processes if necessary → stop HTTP and close database pools. Closing a browser tab does not necessarily stop the backend. The UI must explain actual application status.

## 10. Observability

Measure local read latency, rule execution time, database commit time, AI queue/generation latency, time to first permitted narrative, context estimate, output validity, repair count, session renewal reason, encounter fallback rate, and stale-summary jobs. Correlate operation, campaign, source revision, and adapter generation using privacy-safe IDs.

Avoid logging complete prompts or private dialogue by default. Structured error codes should diagnose unsupported runtime, account unavailable, context overflow, invalid proposal, illegal action, stale state, lost lease, interrupted before commit, and committed narration pending.

The P01.2 probe retains only recognized runtime error variants; unknown strings or object keys become `unclassified`, since even an unknown error key can contain private data. Its final report omits raw errors, stderr and upstream bodies. Only the account-free scripted fixture logs upstream request bodies (neutral text plus runtime instructions) for settings/schema comparison, and its containers are removed afterwards. This fixture logging must not be enabled for live tasks. The opt-in live profile requires an explicitly named existing account volume and never copies host credentials or creates a missing volume. Internal fixture networking does not establish host/tool containment; P01.3 remains open.

## 11. Performance budgets

Initial targets on documented reference hardware with a warmed local database: public status/inventory query p95 under 200 ms; deterministic combat calculation excluding persistence p95 under 50 ms; normal canonical commit p95 under 250 ms; operation acceptance under 300 ms. These are measurable targets, not achieved benchmarks.

Do not give an end-to-end AI latency guarantee before measurement. Record provider latency separately from application overhead and include account/profile, context size, and test sample size in reports. A faster response with broken continuity is not an optimization.

## 12. Upgrades and incident recovery

Maintain supported engine, pack schema, ruleset, database, and Codex adapter compatibility tables. Back up before an upgrade that changes persistent formats. Unsupported inputs produce a controlled migration-required state. Do not silently downgrade content or rules.

For a suspected incorrect action, preserve the operation receipt, source event references, and last good backup. Disable further mutation if invariants are violated. Use an explicit correction or restore after diagnosis. Do not edit event history to conceal the fault.
