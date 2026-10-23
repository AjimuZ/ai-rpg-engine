# Supported runtime contract (P01.5)

Evidence date: 23 October 2026. This matrix describes developer compatibility,
not a released application or an upstream support guarantee. Exact executable
checks reject every version other than `0.164.0`, including suffixed builds.
[ADR 0009](../../docs/adr/0009-toolchain-baseline.md) remains authoritative for
checksums, image index digests, toolchain floors and refresh policy; this record
adds protocol and platform scope without relaxing those pins.

| Component / surface | Verified baseline | Support boundary |
| --- | --- | --- |
| Runtime executable | `codex-cli 0.164.0`, static musl `x86_64` | Exact release only; no semver range or automatic upgrade |
| Runtime image | Local image built from [`Dockerfile`](../../docker/codex-runtime/Dockerfile), Debian 13 slim by digest, UID 10001, managed isolation profile | Tools absent, never/read-only/no search; container is the security boundary; bubblewrap is unavailable and not relied on |
| Local wire | `app-server --listen stdio://`, bounded UTF-8 JSON lines | Isolated Linux container stdio verified; arbitrary native host executables unclaimed |
| Packaged wire | `app-server --listen unix://PATH`, HTTP upgrade then WebSocket text messages over Unix socket | Java 21 `UnixDomainSocketAddress` relay; hashed socket in `/tmp/codex-daemon-10001`, mode 0600, parent 0700; shared UID required |
| Client / probe | Temurin 21.0.12.1 pinned JDK image; Python 3.15.1 standard library on Windows | Developer relay and sequential probe only; concurrent production adapter remains P08 |
| Host | Windows 11 x64, Docker Desktop 4.96.3, WSL 2 Linux containers, Engine 29.9.2, Compose 5.6.1 | Actual runtime architecture `linux/amd64`; PowerShell probe commands verified; Git Bash launcher remains P02.7 |
| Other combinations | `aarch64` release checksum is pinned, not executed | No `linux/arm64`, native Linux/macOS/Windows runtime, WSL distribution shell or other host support claim |
| Account-backed text | Retained [P01.2 report](evidence/live-neutral-0.164.0.json), explicit `gpt-6-luna` / `low`, neutral output and second-process stdio resume | Historical successful check predates P01.3 profile; profile recheck hit usage quota and remains required before package exit; no account volume authorized for P01.5 |
| Images | [Explicitly unavailable in the managed profile](IMAGE_CAPABILITY.md) (P01.6); no supported generation formats/invocation | Account entitlement/cost unverified; generation refused before runtime access; text/catalog fallback retained; P17 owns future qualification |

## Retained fixtures and replay

[`fixtures/0.164.0/neutral-session.json`](fixtures/0.164.0/neutral-session.json)
contains selected request/result payloads and consumed notifications from the
real packaged runtime against the internal scripted upstream. It covers the
handshake, account state, paginated catalog, thread creation/resume/history,
explicit turn settings/schema, completion, failure and interrupt acknowledgment
plus terminal confirmation. The initialized notification is sent by the existing
client but omitted from the request/result list because it has no response.

This is a **projection**, not a raw wire transcript or the complete protocol
schema. JSON-RPC envelopes, unconsumed events, item deltas/reasoning, arbitrary
metadata and history contents are omitted. IDs/cursors become stable aliases;
userAgent and free-text errors become neutral placeholders. Only validated
neutral final messages survive. Catalog identifiers are release observations,
not entitlement guarantees. No account objects, credentials, host paths, raw
stderr or live prompts are retained. The projection preserves observed order
within each list; it does not preserve request/event interleaving across lists.
Transport and concurrency evidence stays in the existing dedicated tests.

Replay requires no Docker, account or inference:

```powershell
py -B -m unittest discover -s tools/runtime_probe -p test_fixtures.py
```

To refresh the projection, build the pinned image through the discovery Compose
file as documented in [README](README.md), then run:

```powershell
py -B tools/runtime_probe/capture_fixtures.py
```

The capture command has no live/account option. It uses only the fixed internal
session fixture, validates the whole session before writing, and stops services
in `finally` without deleting volumes. Review every changed fixture field and
its normalization; never substitute a live account capture. Then run the full
Docker-enabled runtime suite and packaging checks from their READMEs. Refresh
this matrix, ADR 0009, isolation evidence and affected specifications together
before accepting a new pin. A passing replay alone cannot qualify an upgrade.

## Service maturity and remaining gates

Project assessment: a version-bound integration proof, not release readiness.
The protocol has usable lifecycle and structured-output behavior at this pin;
there is no project claim of cross-version stability or an upstream SLA. Keep
experimental APIs disabled. TCP WebSocket service is outside the accepted
private IPC design; the existing [deployment gate](../../docs/22-container-deployment-and-startup.md#3-runtime-integration-gate)
records its upstream experimental/unsupported status. Do not open a TCP listener
or fall back to a host process on failure.

Account-free scripted responses prove protocol/isolation, not service quality,
entitlement or successful paid/subscription inference. Account presence/catalog
availability never establish playable readiness. Sign-in is owner-operated;
quota failures stay visible and never enable paid overage or API fallback.
The historical signed-in smoke does not prove authenticated packaged socket
restart. Connection loss can leave a turn active; reconcile on a fresh connection
rather than replaying a mutation. Runtime services require init for termination.

P01 package exit still needs the live isolation recheck and P01.7. Packaged
sign-in/restart, launcher readiness, production adapter concurrency, canonical
receipts and campaign saves remain future gates. Full QA-24 (save coherence)
and QA-28 (fresh-clone startup/persistence) are not satisfied by these fixtures.
