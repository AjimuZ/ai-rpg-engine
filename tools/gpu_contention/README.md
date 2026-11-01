# GPU contention harness (P21.4-C.1.3.2)

A trusted-owner script that drives text, image and speech requests against the endpoints
you name while an unowned GPU competitor holds memory, and writes what the host showed to
one new JSON file: peak GPU memory, out-of-memory events and the lease executor's metrics.
It uses only the Python standard library, never loads or unloads a model, and never stops
a server.

**Status.** The harness and its self-test exist. The self-test uses stand-in servers and no
GPU. No run against a real model server, a real card or the reference host has been made, so
nothing here proves the engine stays out of out-of-memory trouble (P21.4 stays open). A file
produced elsewhere says nothing about the reference host. No GPU was available when the
entry-point mode and the competitor program below were written, so no competitor run happened
and the competitor command has never been executed.

**Load sent straight to a model server proves nothing about admission.** The engine's shared
admission (the scheduler's permit, residency claims and the lease executor) sits between the
engine and the model servers. A request the harness sends directly to a model server never passes
through it, so that run shows what the server and the card do under load, not that the engine
keeps its own load out of an out-of-memory error. To exercise the admission, use the entry-point
mode below. Every output file states which path its load took in `load_path`.

## One command through the engine's entry point

```sh
py -B tools/gpu_contention/contention.py --output runs/contention-entry-1.json \
    --duration 300 --interval 1 --via-entry-point \
    --metrics-url http://127.0.0.1:8080 --metrics-session-file ~/.private/engine-session.txt \
    --engine-log runs/engine.log --docker-logs <model-server-container> \
    --competitor docker --competitor-arg=exec --competitor-arg=<model-server-container> \
    --competitor-arg=timeout --competitor-arg=245 --competitor-arg=/app/llama-server \
    --competitor-arg=-m --competitor-arg=/models/<model-file> --competitor-arg=--n-gpu-layers \
    --competitor-arg=999 --competitor-arg=--ctx-size --competitor-arg=2048 --competitor-arg=--host \
    --competitor-arg=127.0.0.1 --competitor-arg=--port --competitor-arg=8091 --competitor-arg=--no-warmup \
    --competitor-start-after 60
```

The `--competitor` lines are the competitor program of the section "Competitor program held through the model
server's container" below. Leave them out for a run with no competitor. The load of this command has the path
`executor-entry-point`.

`--via-entry-point` replaces the backend URLs. Each iteration sends the owner-only
`POST /api/v1/models/admission-probe` (docs/34 section 10, P21.4-F.1) to the engine, which sends one fixed
text task through the scheduler and one fixed image job through the image admission. Requirements:

- The engine runs with `engine.inference.admission.enabled` `true`; with it off the route does not exist and
  every call is a 404.
- `--metrics-url` is the engine's configured origin exactly: `http://127.0.0.1:<port>`, `http://localhost:<port>`
  or `http://[::1]:<port>`, with no path. The harness sends that value as `Host` and `Origin`, because the
  engine refuses any other spelling. The same URL and session file serve the metrics reads.
- The route is a POST, so the harness first calls `GET /api/v1/session` with the session cookie, which reuses
  the live session and returns its CSRF token, then sends the token in `X-CSRF-Token` with a JSON content
  type. The token is kept in memory only. If the engine hands out no token (an expired or unknown session),
  the call counts as the error `csrf-unavailable`.
- The probe accepts no caller content and runs one at a time (a second gets 409 `PROBE_BUSY`), so the mode
  is repeated probe calls at concurrency 1. It refuses backend URLs, `--concurrency` other than 1, the
  request path, step and size options, and a metrics URL that is not an http loopback origin.
- `load.entry_point` holds the calls' statuses and latencies like any backend, plus `outcomes`: for the text
  and the image request the route's own fixed `status/code` words and how often each came back. A reply that
  does not consist of such words is counted in `unreadable_replies` and never copied. A `200` with
  `UNAVAILABLE/no-image-port` or `UNAVAILABLE/wait-expired` is a probe that reached the engine but did not
  load a model; read `outcomes`, not just the status.

## Competitor program held through the model server's container

A trusted-owner step, outside the harness and outside the engine: the harness contains no GPU code, and
the engine is never told about this memory. The packaged model server's container (the `models` service of
`compose.yaml`, started with `--profile models`) already has the GPU, the llama.cpp server binary and a
read-only `/models` directory. A second server process inside it, loading one model fully onto the card,
holds that model's weights plus its context cache and a CUDA context for as long as it runs. The
`--competitor` option below runs it through `docker exec`; no engine code and no image is added.

The program names one model file, offloads every layer and fixes the context size:

```sh
docker exec <model-server-container> timeout 245 /app/llama-server \
    -m /models/<model-file> --n-gpu-layers 999 --ctx-size 2048 --host 127.0.0.1 --port 8091 --no-warmup
```

- **Amount held.** Roughly the size of `<model-file>` plus the cache for 2048 tokens plus a few hundred MiB of
  CUDA context. Choose a file whose size gives the amount you want to hold, and state that amount in the run's
  notes. The harness does not set or check it; read it from the file's `gpu_memory` (`baseline_mib`, `peak_mib`)
  and from `nvidia-smi` before and after.
- **Stopping.** `timeout 245` ends the process after 245 seconds, which fits `--duration 300
  --competitor-start-after 60`. The harness stops its `docker exec` client when the load ends, but stopping the
  client does not necessarily stop the process in the container, so keep the `timeout` and confirm with
  `nvidia-smi` that the memory is free afterwards.
- **Port.** `127.0.0.1:8091` inside the container is reachable from nothing else; the `models` network has the
  engine as its only peer and the process publishes no port.
- **Unchecked.** The path `/app/llama-server`, the presence of `timeout` in the image, and that the server
  starts with a read-only root and user 65534 beside the running one were not checked here: no GPU, no image and
  no container was available, and nothing in this section was run. Check them with
  `docker exec <model-server-container> ls /app` first. The pinned build's other options are those of
  `llama-server --help`.

## One command straight to the model servers

This path observes a server and a card without the engine; it proves nothing about admission and its file
says `load_path: direct-to-model-servers`.

```sh
py -B tools/gpu_contention/contention.py --output runs/contention-1.json \
    --duration 300 --interval 1 \
    --text-url http://127.0.0.1:8081 --text-model <text-model-name> \
    --image-url http://127.0.0.1:7860 \
    --speech-url http://127.0.0.1:8082 --speech-model <speech-model-name> \
    --metrics-url http://127.0.0.1:8080 --metrics-session-file ~/.private/engine-session.txt \
    --server-log runs/model-server.log --engine-log runs/engine.log \
    --docker-logs <image-worker-container> \
    --competitor python --competitor-arg=-c --competitor-arg="<program that holds GPU memory>" \
    --competitor-start-after 60
py -B -m unittest discover -s tools/gpu_contention -v   # the no-GPU self-test
```

The output file must not exist: an existing file is never replaced, and a bad output path
fails before any load starts. Exit status: `0` the file was written (observations may still
be `unavailable`), `1` the run failed and no file was kept, `2` bad arguments.

## What it does

- **Load.** One fixed neutral request per backend, sent by `--concurrency` workers (default 1)
  until `--duration` seconds pass or `--max-requests` (default 200) were sent. Text:
  `POST /v1/chat/completions`; image: `POST /sdapi/v1/txt2img`; speech:
  `POST /v1/audio/speech`. Each path can be changed with `--text-path`, `--image-path` and
  `--speech-path`. Give at least one URL. The harness counts HTTP statuses and fixed error codes
  and keeps no response body. The packaged model server loads nothing for a plain chat request, so its
  model must already be loaded, or every text request counts as a 400.
- **Unowned competitor.** `--competitor PROGRAM` with repeatable `--competitor-arg` is started
  without a shell `--competitor-start-after` seconds into the run and stopped when the load ends.
  The harness contains no GPU code; you supply the program that takes memory outside the engine.
  No program is shipped; the section "Competitor program held through the model server's container"
  documents one command, which was not run.
- **Peak GPU memory.** `nvidia-smi --query-gpu=index,memory.used,memory.total
  --format=csv,noheader,nounits` is sampled every `--interval` seconds (5 s limit per call) and once
  more at the end. Per GPU it records the first reading, the highest reading and the total, in MiB.
  `--nvidia-smi` and repeatable `--nvidia-smi-arg` name another program, which the self-test uses.
- **Out-of-memory events.** Lines added during the run to each `--server-log` and `--engine-log`
  file, and the lines `docker logs --since <start>` returns for each `--docker-logs` container
  (`--docker` and `--docker-arg` name another program), are matched against fixed patterns:
  `out of memory`, `OutOfMemoryError`, `CUDA_ERROR_OUT_OF_MEMORY`, the image worker's
  `gpu-out-of-memory` code, `cudaMalloc` and allocation failures, and `oom-kill`. A line counts
  once. The file holds counts, line numbers within the run's lines and the pattern name, never the
  line.
- **Executor metrics.** With `--metrics-url` and `--metrics-session-file`, the owner-only
  `GET /api/v1/models/admission-metrics` of the engine is read at the start, every interval and at
  the end. The route exists only while `engine.inference.admission.enabled` is `true`. The file
  holds the first reading, the last reading and the highest value of every name. The executor's
  metric names are not per modality, so `driven` says which modalities this run loaded.

## Unavailable is not zero

Whatever could not be observed is written as `{"status": "unavailable", "reason": <code>}`:

| Part | Reasons |
| --- | --- |
| Load per backend | `not-configured`, `no-answer` (no request got an answer; `errors` counts `timeout`, `connection-refused`, `connection-failed`, and for `entry_point` also `csrf-unavailable`) |
| Competitor | `not-configured`, `start-failed`, `run-ended-before-start` |
| GPU memory | `tool-missing`, `tool-not-runnable`, `timeout`, `exit-nonzero`, `unparsable`, `no-samples` |
| Out-of-memory scan | `no-log-source`, `all-sources-unavailable`; per source `file-missing`, `file-unreadable`, `tool-missing`, `exit-nonzero`, `timeout` |
| Executor metrics | `not-configured`, `route-missing` (404, for example the switch is off), `refused` (401 or 403), `unreachable`, `timeout`, `invalid-body`, `http-<status>` |

An image or speech backend that is missing, or whose server has no control behind the engine, therefore
shows as `unavailable`, not as zero requests or zero memory. `out_of_memory.events` of `0` with status `ok`
means no line matched, not that no out-of-memory happened.

## The session value

The metrics route and the entry point admit only the owner. Put the value of the `ENGINE_SESSION` cookie (43
characters, or `ENGINE_SESSION=<value>`) in a private file outside the repository and pass the file with
`--metrics-session-file`; it is never accepted on the command line. The harness checks its form, sends
it only to a loopback `--metrics-url` (use the host and port the engine is configured to accept), and never
prints or writes it; the CSRF token the entry-point mode fetches is treated the same way. A session lasts a
fixed time, so renew the file before a long run.

## Output

`format` is `gpu-contention-observations/1`; the keys are `complete` (false after an interrupt), `load_path`,
`started_at`, `duration_seconds`, `settings`, `load`, `competitor`, `gpu_memory`, `out_of_memory`,
`executor_metrics` and `limits`. `load_path` is `executor-entry-point` (the load went through the engine's
probe route) or `direct-to-model-servers` (it went to the servers named by the URL options and passed no
admission); `load` has the keys `text`, `image`, `speech` and `entry_point`, of which only the ones that
carried load are `ok`. `load_path` and `load.entry_point` were added to format 1 without a version change, so a
reader of earlier files must treat their absence as `direct-to-model-servers`. The JSON is indented, key-sorted,
LF-terminated and holds no endpoint, model name, prompt, path, log line, session value or CSRF token. A summary
of five lines (the second is `load_path=...`) goes to the terminal.

## Limits

- Peak memory is the highest sample, so a spike shorter than `--interval` is missed.
- The patterns find the messages of the servers they were written for; a server that words its failure
  differently is missed, and `allocation failed` can also match an unrelated line.
- Image and speech requests use the common routes named above; a server with other routes needs the path options.
- Entry-point mode: the self-test's stand-in engine checks the session cookie, `Host`, `Origin`, CSRF token and
  JSON content type as `LocalHttpAdmission` is documented to; the real engine, scheduler, executor and route
  took no part, so agreement with the real admission rests on reading `LocalHttpAdmission.java`. The probe's
  30-second waits are placeholders, so a probe right after start often answers `wait-expired`.
- The self-test ran against stand-in servers on `127.0.0.1` and a stand-in for `nvidia-smi`; no GPU, real model
  server, container or engine took part. It is run by CI (`.github/workflows/ci.yml`) but is not a step of
  `.agents/ralph/verify.sh`.
