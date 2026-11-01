# GPU contention harness (P21.4-C.1.3.2)

A trusted-owner script that drives text, image and speech requests against the endpoints
you name while an unowned GPU competitor holds memory, and writes what the host showed to
one new JSON file: peak GPU memory, out-of-memory events and the lease executor's metrics.
It uses only the Python standard library, never loads or unloads a model, and never stops
a server.

**Status.** The harness and its self-test exist. The self-test uses stand-in servers and no
GPU. No run against a real model server, a real card or the reference host has been made, so
nothing here proves the engine stays out of out-of-memory trouble (P21.4 stays open). A file
produced elsewhere says nothing about the reference host.

## One command

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
  An example program is not shipped, and none was run.
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
| Load per backend | `not-configured`, `no-answer` (no request got an answer; `errors` counts `timeout`, `connection-refused`, `connection-failed`) |
| Competitor | `not-configured`, `start-failed`, `run-ended-before-start` |
| GPU memory | `tool-missing`, `tool-not-runnable`, `timeout`, `exit-nonzero`, `unparsable`, `no-samples` |
| Out-of-memory scan | `no-log-source`, `all-sources-unavailable`; per source `file-missing`, `file-unreadable`, `tool-missing`, `exit-nonzero`, `timeout` |
| Executor metrics | `not-configured`, `route-missing` (404, for example the switch is off), `refused` (401 or 403), `unreachable`, `timeout`, `invalid-body`, `http-<status>` |

An image or speech backend that is missing, or whose server has no control behind the engine, therefore
shows as `unavailable`, not as zero requests or zero memory. `out_of_memory.events` of `0` with status `ok`
means no line matched, not that no out-of-memory happened.

## The session value

The metrics route admits only the owner. Put the value of the `ENGINE_SESSION` cookie (43 characters, or
`ENGINE_SESSION=<value>`) in a private file outside the repository and pass the file with
`--metrics-session-file`; it is never accepted on the command line. The harness checks its form, sends
it only to a loopback `--metrics-url` (use the host and port the engine is configured to accept), and never
prints or writes it. A session lasts a fixed time, so renew the file before a long run.

## Output

`format` is `gpu-contention-observations/1`; the keys are `complete` (false after an interrupt), `started_at`,
`duration_seconds`, `settings`, `load`, `competitor`, `gpu_memory`, `out_of_memory`, `executor_metrics` and
`limits`. The JSON is indented, key-sorted, LF-terminated and holds no endpoint, model name, prompt, path,
log line or session value. A summary of four lines goes to the terminal.

## Limits

- Peak memory is the highest sample, so a spike shorter than `--interval` is missed.
- The patterns find the messages of the servers they were written for; a server that words its failure
  differently is missed, and `allocation failed` can also match an unrelated line.
- Image and speech requests use the common routes named above; a server with other routes needs the path options.
- The self-test ran against stand-in servers on `127.0.0.1` and a stand-in for `nvidia-smi`; no GPU, real model
  server, container or engine took part. It is run by CI (`.github/workflows/ci.yml`) but is not a step of
  `.agents/ralph/verify.sh`.
