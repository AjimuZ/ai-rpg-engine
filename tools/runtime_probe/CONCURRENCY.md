# Managed concurrency qualification (P01.7)

The selected inference policy is **serialized: one active turn per installation**,
across all campaigns, threads and roles. Live simultaneity, headroom, quota
behavior, service priority and cancellation latency are unverified. The historical
P01.2 single-task smoke predates the managed profile and cannot establish them.
The handoff authorizes no account volume for this session, so no live request,
sign-in, credential access, paid overage or billing fallback is attempted.

`concurrency.py` adds a fixed account-free experiment against the pinned managed
runtime and internal scripted upstream. The initial capture passed all five
turns: three interrupted, two completed, three upstream streams closed and three
events buffered for another active turn, with no discarded correlated events.
The retained
[evidence projection](evidence/concurrency-0.164.0.json) records scope explicitly.
It routes notifications to bounded per-thread/turn inboxes through one reader;
it does not implement the production concurrent adapter or durable scheduler.

Experiment sequence: hold two distinct threads upstream, cancel one by exact
identity, verify the other remains active, complete an interactive turn while
the other is held, then cancel the remaining held turn. Require each matching
terminal status and upstream stream closure. Separately hold one background
turn, interrupt/reconcile it and only then start interactive work. This last
sequence exercises client-controlled priority under the selected limit, without
assuming upstream scheduling or a pause/resume facility.

The experiment is bounded to five neutral requests, three held streams, three
registered turns per router, 1,024 buffered events, and 60-second local request,
turn and upstream waits. Interrupt acknowledgment, terminal confirmation and
stream-closure polling have separate waits, with a combined local ceiling of
180 seconds plus bounded log reads/polling overhead. Services
stop in `finally` without deleting volumes. Only allowlisted counts, statuses,
policy and local timing measurements may be retained; no identifiers, raw logs,
request bodies, account data or prompts enter the evidence projection.

Scripted overlap qualifies protocol routing only. It establishes neither actual
inference concurrency nor a maximum upstream capacity, throughput, live latency,
account entitlement or remaining quota. Local timings include polling overhead
and provide no service guarantee. Unknown/cancelled outcomes remain accounted
for until terminal reconciliation; an interrupt acknowledgment alone cannot
release the active permit. If bounded preemption cannot be proven in production,
optional work runs only in an explicit idle maintenance window or stays disabled.

P08 implements admission, priority, finite queues, fairness and restart recovery;
[P08.6 and ADR 0014](../../docs/adr/0014-bounded-asynchronous-planning.md) gate any
increase above one with explicitly authorized live evidence, correct routing,
isolated cancellation/failure, quota/backpressure/restart checks and measured
latency/usage benefit against serialization. P01's live isolation recheck and
full QA-24/QA-28 application checks remain separate open gates.

Refresh the projection with `py -B tools/runtime_probe/concurrency.py` after
building the pinned runtime as documented in [README](README.md). The capture
refuses existing fixture containers before starting or cleaning up services.
Run offline replay with
`py -B -m unittest discover -s tools/runtime_probe -p test_concurrency.py`.
Set `RUNTIME_PROBE_DOCKER` to the absolute Docker CLI path and run
`py -B -m unittest discover -s tools/runtime_probe` for actual managed-runtime,
relay compilation/framing, isolation and lifecycle regression. The full run
passes 116 tests without skips, including 12 qualification checks and the guard
against adopting or stopping existing fixture containers. Packaging passes five
tests without skips; four Compose
configurations and the source/docstring/local-link audits also pass.
