# ADR 0003: Backend-Owned Canonical Transactions

**Status:** Proposed. **Date:** 22 October 2026.

## Context

The primary project correctly separates narration from authoritative rules. The demo lets AI edit a Markdown save and validates selected invariants afterwards, leaving partial-write and side-asset recovery concerns.

## Decision

Accept typed intentions and content proposals, never arbitrary field patches or AI database writes. Validate and commit canonical state, events, reward settlements, operation receipt, and outbox together. Use durable idempotency keys with payload hashes, expected revisions, per-campaign leases/fencing, and backend-owned random draws. Do not hold a transaction open during AI work.

Narration follows the resolved event bundle. Missing narration is recoverable presentation work, not a reason to repeat the action. Queries and transcript records do not advance world counters.

## Alternatives

Markdown-as-live-state is simple but weak for concurrency and cross-entity invariants. Fully event-sourced reconstruction would require a larger versioned-reducer commitment than the initial engine needs.

## Consequences

More foundational persistence work is required before real AI mutations. In exchange, retries, interruption, UI reconnect, and campaign recovery become well-defined. The event log is auditable without promising universal replay across arbitrary versions.

P03.2 storage decision (24 October 2026): use a separate JPA `@Version`/`row_version`
on mutable rows rather than reusing `stateRevision`, `operationVersion` or a
presentation generation. Multiple journal transitions can be flushed together and
presentation updates must leave canonical counters unchanged. PostgreSQL requires
one version step on UPDATE; SQL writers must also compare the expected token and
reject a zero-row update. Composite foreign keys, frozen scope/provenance columns,
resource bounds and write-once complete journal outcomes guard independent clients.
The full lifecycle is validated in Java. These constraints are foundations for,
and do not replace, the short atomic transaction and lease/fencing checks of P03.3/P03.5.

## Verification

P07.6 implementation decision (28 October 2026): plan use stays inside this boundary.
A trusted resolved commit may cite admitted plan branches; the commit rechecks them
against current rows under the campaign lock, after its own revision/lease/rule checks
and before writing any effect, and records each branch once in an immutable
`plan_activation` row whose deferred trigger requires the committed operation. The plan
supplies no effect, and every commit re-evaluates admitted plans against the rows it
changed. Plan admission keeps its own campaign-serialized transaction and now also
rechecks scope, pinned content, author constraints and typed row versions; it writes no
canonical counters, draws, rewards or events. Real PostgreSQL races, fault injection at
COMMIT and deliberate weakening probes verify both boundaries
([quality record](../15-quality-and-acceptance.md)).

P03.6 implementation decision (24 October 2026): the same short canonical transaction
also bumps explicitly affected query epochs, appends invalidation of dependent plans
and enqueues bounded critical preparation intents. A deferred database trigger requires
each critical job to reference a committed origin, so enqueue failure rolls back the
entire world/event/receipt/outbox bundle. Duplicate logical jobs must match origin and
payload/schema identity; a prior proactive job cannot silently replace a critical
outbox obligation. Preparation admission uses its own campaign-serialized transaction
and immutable receipt, with no canonical counters, draws or events. PostgreSQL fault
injection, duplicate replay and admission/epoch races verify both boundaries.

P03.4 decision (24 October 2026): reserve immutable operation/ordinal draws in their
own bounded transaction before canonical commit. Use lazy private 256-bit campaign
seed material and explicitly framed HMAC-SHA-256 v1 with rejection sampling, rather
than JDK generator implementation/cursor state. This gives portable test vectors and
independent operation draws without changing canonical counters. Persist seed bytes
and algorithm/version with the reservation ledger; never derive a secret from the
public campaign seed label. Canonical receipts read stored reservations under the
campaign lock, while a failed world commit retains the earlier draws. There is no
speculative-planner draw API. Later command policy must prevent equivalent unresolved
attempts from acquiring new operation identities solely to reroll.

P03.3 implementation decision (24 October 2026): the internal commit boundary accepts
an immutable bounded resolved value, never an executable callback. It rejects ambient
transactions, locks campaign then journal, rechecks exact revision/content/operation
and resource versions, and returns detached receipt data only after database commit.
The initial typed effects cover world counters/mode/decision, resource adjustments,
events and reward settlements. New domain effects must extend that explicit boundary.
The outbox stores immutable scoped operation/revision references, not event payloads;
its deferred foreign key requires a committed journal outcome. Dispatch and audience
filtering remain later worker work. No network client or delivery hook is injected into
the transaction. Populated upgrade and 27 focused PostgreSQL cases pass, including
deferred commit failure and an intervening resource writer after inserts flush.
Full backend verification passes 72 tests, with three packaged-interface tests skipped
outside their profile. Leases/fencing remain P03.5 and gameplay
semantic/owner authorization remains P07/the application boundary.

Duplicate/crash/concurrency fault tests, resource/ownership constraints, one-time settlement, narration-only retries, and fresh-storage recovery. See [turn protocol](../07-turn-protocol.md).

P07.1 implementation decision (26 October 2026): reuse the existing journal and
lease schema for atomic command admission, with database owner-before-campaign
serialization rather than process-local capacity counters. Hash versioned bounded
canonical command bytes before entering the transaction; replay a matching request
before revision/busy/limit checks. Extend the same owner lock and two-live-permit cap
to all internal acquisition/renewal paths. Classify retirement and busy with one
captured database timestamp so expiry between statements cannot strand an old active
journal. Status reads expose detached progress only, never permit capabilities or
unprojected receipts. Typed semantics and execution workers remain later tasks.

P07.2 implementation decision (27 October 2026): use a sealed intention vocabulary
with closed payloads rather than a configurable field/effect patch registry. Start
with exploration movement, bounded waiting and addressed speech; unsupported actions
fail closed. Hash and decode one frozen canonical byte snapshot, then check current
mode, decision, immutable creation player and recipient scope under the admission
lock. At canonical commit require matching immutable typed intention evidence for
registered player operations and recheck common semantics before effects. Keep the
trusted internal compatibility constructors for non-player foundation operations;
no HTTP or planning-artifact route accepts resolved plans. Preserve matching historic
admission/receipt replay before newer policy checks. Add no migration or executor;
detailed rule validation and local resolution remain P07.3–P07.4.

P07.3 implementation decision (27 October 2026): extend the same lock-scoped gate with
current-state rules instead of a separate validation phase or stored validation
stamp. Read mutable premises (position, learned abilities, skill ranks, resources)
under the campaign lock at admission and again at commit; read authored facts from
an immutable catalog compiled from the sealed pinned closure before the lock, so pack
recompilation never extends lock hold time. Fail closed on secret definitions until
typed reveal records exist, on area targeting until encounter zones exist and on
costs from another pack's resource vocabulary. Share one category across concealment-
sensitive failures. At commit require every resource change and event actor to belong
to the intention's participants. Alternatives rejected: trusting admission-time
results at commit (stale after any canonical write), validating inside the resolver
only (would let a resolver defect write unattributable effects), and caching
mutable state (cannot be fenced by the campaign lock). Local calculation remains P07.4.

P07.4 implementation decision (28 October 2026): execute a registered intention in the
request thread as four short transactions (admission, prepare to `VALIDATING` with a
rule re-check and detached snapshot, one committed draw reservation per die, canonical
commit) instead of one long transaction or a background worker. A single transaction
could not keep durable draws when the commit fails; a worker would need the payload,
which admission deliberately does not store. Calculation is a pure function of
intention, snapshot, pinned catalog and draws, so the commit's own re-checks (revision,
lease, typed evidence, semantics, rules, attribution, row versions) make a stale
calculation fail rather than apply. After durable admission every failure is recorded
(`REJECTED` for rule drift, otherwise `FAILED_BEFORE_COMMIT`) and the lease released;
reserved draws stay bound to the failed operation. Movement is a new explicit typed
`Placement` effect guarded by the actor row version, not a generic field write.
Abilities whose effect programs need later systems are refused at the gate
(`EFFECT_UNSUPPORTED`) rather than partially executed. Alternatives rejected: storing
the payload for asynchronous resumption (a second copy of untrusted input and a resume
path that P07.5 cancellation must govern first), evaluating dice without durable
reservation (reroll on retry), and silently skipping unsupported effect nodes (a
partial ability is not the authored ability).

P07.5 implementation decision (28 October 2026): decide cancellation races with the
campaign lock that every journal writer already takes first; a winning cancellation moves
the operation to `CANCELLED` and retires its permit with a fence step, so the executing
request's own status, version and lease checks stop it. Prevent reroll fishing with draw
streams: draw values derive from the earliest equivalent attempt (same normalized
intention and base revision) instead of the operation identity. Announce operation
progress through an append-only stream log written by a journal trigger in the changing
transaction, rendered at delivery from immutable journal data and served as bounded SSE
long polls with `generation:sequence` identities. Represent owed presentation as one
durable fenced `presentation_job` per committed operation, inserted by the commit and
settled with the journal status. Alternatives rejected: a cancel flag polled by the worker
(a flag set after the commit lock was taken could be reported as a cancellation of saved
effects), rejecting equivalent attempts outright (blocks a legitimate retry after an
infrastructure failure), application-level stream writes (every present and future
journal writer, including recovery, would have to remember them), held server-push
connections (unbounded threads and no resumption after a server restart), and reusing
`ai_job` for presentation (its admission contract requires planning snapshots and
artifacts, and presentation must not depend on planning admission). Retention is bounded
(1000 messages); older cursors reset instead of guessing.
