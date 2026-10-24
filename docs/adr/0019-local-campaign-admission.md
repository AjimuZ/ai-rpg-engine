# ADR 0019: Opt-in local campaign creation admission

Status: Accepted and implemented for P06.1 (24 October 2026).

## Context

Campaign creation must bind immutable pack snapshots and validated character
choices to an authorized owner/adventure in one transaction. The initial product
is a loopback application for one owner, while account provisioning, workspace
administration and campaign projections remain separate work. A client-supplied
owner UUID cannot establish authorization. Loopback binding alone cannot stop
cross-origin browser requests.

## Decision

Enable session and creation routes only when the operator configures an existing
owner with `engine.http.owner-id`. Require the exact configured loopback
`engine.http.origin` authority in Host, and exact Origin for mutations; ignore
forwarding headers. Bootstrap independent random session and CSRF proofs through
same-origin JSON and an HttpOnly, SameSite=Strict cookie. Keep at most 32 sessions
for a fixed 30 minutes in memory and admit at most four concurrent mutations.
Restart invalidates browser sessions without affecting durable creation receipts.

Decode at most 16 KiB of JSON, including chunked requests, with the existing
bounded data-only parser in fixed JSON mode. Reject duplicate/unknown fields,
coercions and arbitrary grants. The controller receives ownership only through
the admission attribute and invokes the existing transactional creation service;
only committed campaign, actor and revision identifiers leave it. Inaccessible
adventures/revisions share one safe error category. Exact request retries return
the original committed identities; changed payloads conflict.

Protect every method on the two implemented paths. Unknown API paths retain
their existing 404 behavior. Future controllers must explicitly join admission
and independently require authorized owner context before accessing stored data.

## Consequences and verification

This deliberately trusts local processes and same-origin scripts. It is not a
public-hosting authentication design or a new owner/workspace provisioning API.
The packaged launcher does not automatically provision an owner or enable this
API; an existing owner, admitted pack and explicit backend configuration are
required. No additional library or schema migration is introduced.

Focused tests exercise exact/ambiguous browser headers, cookie/CSRF separation,
expiry/capacity, writer failure release, strict/chunked bodies, scoped failures,
retry conflicts and zero advancement. Separate application JVMs create two genre
campaigns, restart against the same PostgreSQL store, reject the old browser
session and recover both original creation results with unchanged initial state.
