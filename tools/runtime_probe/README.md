# Runtime compatibility probe

This is a developer fixture, not the application or its concurrent adapter. It
checks the pinned runtime `0.164.0`; unsupported versions fail before discovery.
Run commands from the repository root. Python uses only its standard library.
The container checks require the Docker baseline from ADR 0009 and its pinned
Java 21 images. No dependency or image pins change in P01.2.

Run the offline checks in PowerShell:

```powershell
py -B -m unittest discover -s tools/runtime_probe
```

Run all checks, including container integration and Java compiler/framing checks:

```powershell
$env:RUNTIME_PROBE_DOCKER = (Get-Command docker).Source
py -B -m unittest discover -s tools/runtime_probe
```

The suite builds the runtime image through `compose.yaml`. To build it and run
only the lifecycle profile:

```powershell
docker compose --file tools/runtime_probe/compose.yaml build --quiet codex
py -B tools/runtime_probe/probe.py --timeout 60 session --docker (Get-Command docker).Source --compose-file tools/runtime_probe/session.compose.yaml
```

The `session` profile launches the real packaged app-server and its Java Unix
socket WebSocket relay. Only the upstream Responses stream is scripted by
`ScriptedProvider.java`, on an internal Compose network without published ports.
This test double is confined to the fixture; it does not introduce a production
provider abstraction. The runtime receives no account volume, credentials,
developer home, campaign or repository mount. Runtime state is temporary. The
relay has no network. Internal networking disables external routing; host/tool
containment is covered by `test_isolation.py` (P01.3, below).

Seven upstream requests establish these facts:

| Order | Thread | Requested outcome |
| --- | --- | --- |
| A | T1 | Completed, first explicit model/effort pair |
| B | T1 | Completed, second explicit pair |
| X | T2 | Held while C runs, then interrupted |
| C | T1 | Completed, first pair; ignores interleaved T2 events |
| D | T1 | Held until upstream receives it, then interrupted |
| E | T1 | Failed after a fixed HTTP 400 response |
| F | T1 | Completed, second pair after reconnect/resume |

Both threads are resumed through a second relay connection while the runtime
stays alive. The probe compares ordered terminal histories and sticky settings.
It compares every upstream model, effort and full output schema with the request
plan, validates every completed neutral envelope, and waits for both interrupted
upstream connections to close. An interrupt acknowledgment is insufficient.
The report explicitly retains `inferenceVerified: false` and an unsupported
account state for this test double.

`Rpc` bounds early notifications and fails on overflow. The sequential session
client filters thread and turn IDs, rejects rerouting and malformed correlated
events, and exposes output only after confirmed completion. It discards other
turns' events; a production adapter still needs concurrent dispatch and pending
turn ownership. Diagnostics retain fixed error codes and counters, not raw errors.
Only this account-free fixture logs upstream bodies to compare settings; never
reuse that logging with private prompts or accounts. Services stop in `finally`
without deleting named volumes. A stopped fixture is checked by the Docker test.

### Owner account setup for the live check

Use the supported ChatGPT device-code flow in a dedicated Docker volume; do not
mount or copy the developer's home or credentials. After building the pinned
image, the owner can run this interactive command from PowerShell:

```powershell
docker run --rm -it --read-only --cap-drop ALL --security-opt no-new-privileges --tmpfs /tmp:mode=1777 --volume rpg-engine-owner-runtime-account:/var/lib/runtime/home rpg-engine/runtime:0.164.0 -c cli_auth_credentials_store=file -c forced_login_method=chatgpt login --device-auth
```

The new named volume is initialized from the image's home directory owned by
UID/GID 10001. The owner completes the displayed browser link and one-time code;
device-code login may need enabling in the account/workspace security settings
([official authentication guide](https://learn.chatgpt.com/docs/auth)). Keep the
volume private and retain it after the container exits. It contains account and
thread state; never expose its files in logs, source control or build contexts.
Git Bash needs `MSYS_NO_PATHCONV=1` for the container paths. A successful login
does not verify inference or close P01.2. Do not run live verification concurrently
with login or another process using the same account volume. The future launcher
still needs its own tested sign-in/status integration in P02.6–P02.7.

The separate `live` profile is opt-in and **passed for P01.2 on 23 October 2026** after owner-operated sign-in. The [sanitized report](evidence/live-neutral-0.164.0.json) records explicit `gpt-6-luna` / `low`, validated completed output and resume from a second stdio process; it omits thread/turn IDs and all credentials. The
owner must first complete supported sign-in in a dedicated runtime-home Docker
volume accessible to UID/GID 10001; this probe never signs in or copies host
credentials. After explicitly choosing that existing volume, run:

```powershell
py -B tools/runtime_probe/probe.py --timeout 120 live --docker (Get-Command docker).Source --image ai-rpg-engine-runtime-probe/codex:0.164.0 --account-volume OWNER_SIGNED_IN_VOLUME
```

The live profile permits inference egress, keeps the read-only root and temporary
workspace, and sends only the fixed neutral task and schema. It refuses a missing
volume or an absent account before starting a thread. `--model` and `--effort`
must resolve against the runtime catalog; defaults are resolved and then sent
explicitly. It validates correlated completed output before resuming the thread
in a second stdio process using that volume. Only that verified path reports
`inferenceVerified: true`. This owner-authenticated neutral stdio path passed; packaged
runtime/socket restart and broader platform coverage are pending. A live re-check after the
P01.3 profile reached the account usage limit (`usageLimitExceeded`) and must be repeated.

## Isolation profile (P01.3)

`test_isolation.py` checks the profile baked into the runtime image
([ADR 0013](../../docs/adr/0013-narration-runtime-isolation.md)). Its file tests always
run. Its three Docker tests (`RUNTIME_PROBE_DOCKER`) use the synthetic, account-free
`session` fixture under the dedicated `ai-rpg-engine-runtime-isolation` project. They
prove the following:

- the upstream request carries `tools: []` and only engine instructions;
- six scripted host tool calls are refused, and no file is written;
- `danger-full-access` thread starts are rejected, and other wider policies are clamped;
- `isolation.compose.yaml` bypasses the guard to mount the synthetic files in
  `isolation_fixture/` plus hostile launch flags, and none of them take effect;
- the guard refuses `config.toml`, `AGENTS.md` and `AGENTS.override.md` in the
  runtime home with exit 78.

Rebuild the probe image first (`docker compose -f tools/runtime_probe/compose.yaml
build codex`). Re-run these tests after every runtime upgrade.

## Process lifecycle (P01.4)

Run only the owned-process integration checks after building the pinned image:

```powershell
$env:RUNTIME_PROBE_DOCKER = (Get-Command docker).Source
py -B -m unittest discover -s tools/runtime_probe -p test_lifecycle.py
```

`test_transport.py` uses controllable local children and scripted RPC messages;
`test_lifecycle.py` repeats lifecycle scenarios against runtime 0.164.0 on the
account-free internal fixture network. No real inference, account mount or
credential access is needed. Tests stop their own containers and keep volumes.

The checked behavior is:

- Idle or mid-turn stdio input closure exits with code 0. Normal shutdown first
  interrupts the turn and awaits `interrupted`, confirming upstream stream closure.
- Packaged relay loss leaves the upstream turn running. A fresh connection resumes
  the thread, inspects history, interrupts the active turn and awaits its terminal
  notification. Closing a connection is not cancellation.
- A turn-wait deadline leaves RPC usable for explicit interruption. A request that
  loses its reply (timeout, malformed response or event flood) retires the generation
  as `request-unresolved`; later calls perform no I/O. Reconcile before retrying.
- Runtime death yields sticky EOF, clears/counts old-generation notifications and
  rejects subsequent requests. Frames preceding EOF remain ordered on the raw wire.
- Each protocol probe container has a random unique `--name`. Input-close escalation kills
  exactly that container through the same Docker executable, then kills the owned
  local client if necessary. It never enumerates or kills other runtime processes.
- Shutdown records report stage, exit code and elapsed seconds. Defaults are 2-second
  waits per escalation, a 30-second Docker hook limit, and 1-second reader joins.
  Synthetic tests verify final kill, an unrelated surviving child and shutdown with
  a full stdout queue. Compose owns the packaged runtime; the backend never calls Docker.
- Runtime services enable `init: true`. `docker stop` then exits promptly with code 0;
  without init, the pinned runtime as PID 1 ignores SIGTERM until Docker kills it.
- Stderr always drains. Its summary classifies at most 65,536 bytes, keeps a maximum
  512-byte line prefix and 16 entries, and records truncation. Only fixed log levels,
  exact allowlisted module names (`codex_app_server`, `codex_core::config`) and
  `unclassified`/`unstructured` survive; raw messages and unknown module text do not.
- Early events stay in arrival order until acknowledgment supplies turn identity.
  The raw queue holds 16 frames and RPC holds 256 notifications; overflow is explicit.
  Buffered events cannot bypass the monotonic deadline.

Verification on 23 October 2026: all 90 runtime probe tests passed with Docker
enabled and no skips (65.990 seconds), including 23 transport tests and 13 process-
lifecycle checks. All 5 packaging tests passed with recreation enabled. The host
was Windows 11, Python 3.15.1, Docker Desktop Engine 29.9.2 / Compose 5.6.1 /
`linux/amd64`. Four Compose configurations and documentation links/anchors passed.
These checks do not implement the production concurrent adapter, canonical operation
receipts, campaign-save coherence (QA-24), full packaged startup (QA-28), or another
platform. The P01.3 live isolation re-check still needs account quota before P01 exit.
