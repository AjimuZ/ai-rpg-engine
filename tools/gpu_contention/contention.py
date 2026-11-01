"""GPU contention harness: load beside an unowned GPU competitor, observations written to one file.

One command drives text, image and speech requests against the owner's configured
endpoints while an owner-supplied competitor program holds GPU memory outside the
engine. During the run it samples ``nvidia-smi`` for peak GPU memory, reads the
executor metrics from the owner-only route ``GET /api/v1/models/admission-metrics``,
and afterwards scans the server and engine logs for out-of-memory lines. Everything
goes into a new JSON file (``gpu-contention-observations/1``).

The harness only records. It never loads, unloads or stops a model, and a finished run
proves nothing about the engine unless the owner ran it on the reference host with real
servers; the file's ``limits`` say so. Anything it could not observe is written as
``{"status": "unavailable", "reason": <fixed code>}``, never as zero.

Load path: by default the load goes straight to the model servers, which proves nothing
about the engine's admission. With ``--via-entry-point`` the load is the owner-only
``POST /api/v1/models/admission-probe`` of the engine instead, so it passes through the
scheduler and the lease executor. The file's ``load_path`` says which of the two happened.

Privacy: the file holds counts, fixed codes and numbers. It holds no endpoint, model
name, prompt, path, log line, session value or CSRF token. The session value for the
metrics route and the entry point is read from a file the owner names (never from the
command line), validated, sent only to a loopback engine URL, and never printed or written.

Standard library only; ``http.client`` is used instead of ``urllib`` so that proxy
environment variables and redirects can never carry the session value elsewhere.
"""
import argparse
import collections
import datetime
import http.client
import ipaddress
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.parse

FORMAT = 'gpu-contention-observations/1'
METRICS_PATH = '/api/v1/models/admission-metrics'
PROBE_PATH = '/api/v1/models/admission-probe'
BOOTSTRAP_PATH = '/api/v1/session'
SESSION_COOKIE = 'ENGINE_SESSION'
# The two values of the output's ``load_path``; fixed so that a reader can rely on them.
PATH_ENTRY_POINT = 'executor-entry-point'
PATH_DIRECT = 'direct-to-model-servers'
# The hosts the engine accepts in its configured origin (LocalHttpAdmission).
_ENTRY_HOSTS = ('127.0.0.1', 'localhost', '::1')
_SESSION_VALUE = re.compile(r'[A-Za-z0-9_-]{43}')
_OUTCOME_STATUS = re.compile(r'[A-Z][A-Z_]{0,31}')
_OUTCOME_CODE = re.compile(r'[a-z][a-z0-9-]{0,63}|[a-z][A-Za-z0-9]{0,63}')
MAX_OUTCOME_KINDS = 50
_CONTAINER_NAME = re.compile(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}')
_PATH = re.compile(r'/[A-Za-z0-9._~/-]*')
_PREFIX = re.compile(r'(/[A-Za-z0-9._~-]+)*')

# Seconds one nvidia-smi call may take; HostGpuMemoryReading uses the same bound.
COMMAND_TIMEOUT = 5.0
DOCKER_TIMEOUT = 60.0
MAX_LOG_BYTES = 64 * 1024 * 1024
MAX_RESPONSE_BYTES = 64 * 1024 * 1024
MAX_METRICS_BYTES = 1024 * 1024
MAX_EVENT_DETAILS = 100
GPU_QUERY = ['--query-gpu=index,memory.used,memory.total', '--format=csv,noheader,nounits']

# A matching line counts once, under the first pattern that matches it. Lines are counted
# and numbered, never copied, because server logs can quote prompts.
OOM_PATTERNS = (
    ('out-of-memory-text', re.compile(r'out of memory', re.IGNORECASE)),
    ('torch-error-class', re.compile(r'OutOfMemoryError', re.IGNORECASE)),
    ('cuda-error-code', re.compile(r'CUDA_ERROR_OUT_OF_MEMORY|cudaErrorMemoryAllocation', re.IGNORECASE)),
    ('worker-code', re.compile(r'gpu-out-of-memory')),
    ('allocation-failed', re.compile(r'cudaMalloc|failed to allocate|unable to allocate|alloc(ation)? failed',
                                     re.IGNORECASE)),
    ('kernel-oom-kill', re.compile(r'oom[-_ ]?kill', re.IGNORECASE)),
)

LIMITS = (
    'Observation only: the values come from this run on this host with these servers.',
    'Zero out-of-memory events means no log line matched the patterns, not that none occurred.',
    'Peak memory is the highest nvidia-smi reading at the sampling interval, so a shorter spike is missed.',
    'A file produced against stand-in servers or without a GPU says nothing about the reference host.',
)


class UsageError(Exception):
    """A rejected argument; the message is fixed text and never quotes the value."""


class CommandError(Exception):
    """An external command failed; ``reason`` is one fixed code."""

    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


Endpoint = collections.namedtuple('Endpoint', 'https host port prefix')


# ----------------------------------------------------------------- arguments

def _number(low, high, integer=False):
    """argparse type for a number in [low, high]; the error never echoes the value."""
    def convert(text):
        try:
            value = int(text) if integer else float(text)
        except ValueError:
            value = None
        if value is None or value != value or not low <= value <= high:
            kind = 'whole number' if integer else 'number'
            raise argparse.ArgumentTypeError(f'must be a {kind} from {low} to {high}')
        return value
    return convert


def _endpoint(text):
    """argparse type for an http(s) base URL with no credentials, query or fragment."""
    problem = argparse.ArgumentTypeError('must be an http or https base URL without credentials, query or fragment')
    try:
        parts = urllib.parse.urlsplit(text)
        port = parts.port
    except ValueError:
        raise problem from None
    if (parts.scheme not in ('http', 'https') or not parts.hostname or parts.username is not None
            or parts.password is not None or parts.query or parts.fragment
            or _PREFIX.fullmatch(parts.path.rstrip('/')) is None):
        raise problem
    https = parts.scheme == 'https'
    return Endpoint(https, parts.hostname, port or (443 if https else 80), parts.path.rstrip('/'))


def _api_path(text):
    if _PATH.fullmatch(text) is None:
        raise argparse.ArgumentTypeError('must be an absolute path of letters, digits and . _ ~ / -')
    return text


def _container(text):
    if _CONTAINER_NAME.fullmatch(text) is None:
        raise argparse.ArgumentTypeError('must be a container name')
    return text


def build_parser():
    parser = argparse.ArgumentParser(
        prog='contention.py',
        description='Drive text, image and speech load beside an unowned GPU competitor and write '
                    'peak GPU memory, out-of-memory events and executor metrics to a new file.')
    parser.add_argument('--output', required=True, help='new file to write; an existing file is never replaced')
    load = parser.add_argument_group('load (give at least one URL)')
    load.add_argument('--text-url', type=_endpoint, help='base URL of the text model server')
    load.add_argument('--text-model', help='model name sent in the text request (needed with --text-url)')
    load.add_argument('--text-path', type=_api_path, default='/v1/chat/completions')
    load.add_argument('--image-url', type=_endpoint, help='base URL of the image server')
    load.add_argument('--image-path', type=_api_path, default='/sdapi/v1/txt2img')
    load.add_argument('--image-steps', type=_number(1, 150, True), default=20)
    load.add_argument('--image-size', type=_number(64, 2048, True), default=1024, help='square side in pixels')
    load.add_argument('--speech-url', type=_endpoint, help='base URL of the speech server')
    load.add_argument('--speech-model', help='model name sent in the speech request (needed with --speech-url)')
    load.add_argument('--speech-path', type=_api_path, default='/v1/audio/speech')
    load.add_argument('--duration', type=_number(0.1, 3600), default=60.0, help='seconds of load')
    load.add_argument('--concurrency', type=_number(1, 8, True), default=1, help='parallel requests per backend')
    load.add_argument('--max-requests', type=_number(1, 100000, True), default=200, help='cap per backend')
    load.add_argument('--request-timeout', type=_number(0.1, 3600), default=120.0)
    load.add_argument('--via-entry-point', action='store_true',
                      help='send the load through the engine\'s owner-only admission-probe route instead of to '
                           'model servers; needs --metrics-url (an http loopback engine URL) and '
                           '--metrics-session-file, and excludes the backend URLs')
    sampling = parser.add_argument_group('observation')
    sampling.add_argument('--interval', type=_number(0.02, 60), default=1.0, help='seconds between samples')
    sampling.add_argument('--nvidia-smi', default='nvidia-smi', help='program that samples GPU memory')
    sampling.add_argument('--nvidia-smi-arg', action='append', default=[], metavar='ARG',
                          help='argument placed before the query flags; repeatable (use --nvidia-smi-arg=-x)')
    sampling.add_argument('--metrics-url', type=_endpoint,
                          help='base URL of the engine; the executor metrics route is read from it')
    sampling.add_argument('--metrics-session-file', metavar='FILE',
                          help='file holding the owner session value (ENGINE_SESSION); needed with --metrics-url')
    sampling.add_argument('--server-log', action='append', default=[], metavar='FILE',
                          help='model server log file; only lines added during the run are scanned; repeatable')
    sampling.add_argument('--engine-log', action='append', default=[], metavar='FILE',
                          help='engine log file; repeatable')
    sampling.add_argument('--docker-logs', action='append', default=[], type=_container, metavar='CONTAINER',
                          help='read a container\'s log lines written during the run; repeatable')
    sampling.add_argument('--docker', default='docker', help='program used for --docker-logs')
    sampling.add_argument('--docker-arg', action='append', default=[], metavar='ARG',
                          help='argument placed before "logs"; repeatable')
    rival = parser.add_argument_group('unowned competitor')
    rival.add_argument('--competitor', help='program that holds GPU memory outside the engine for the run')
    rival.add_argument('--competitor-arg', action='append', default=[], metavar='ARG', help='repeatable')
    rival.add_argument('--competitor-start-after', type=_number(0, 3600), default=0.0, help='seconds into the run')
    return parser


def parse_arguments(argv):
    """@return the validated namespace, with ``session`` read from its file; @raise UsageError otherwise"""
    parser = build_parser()
    config = parser.parse_args(argv)
    if config.via_entry_point:
        _check_entry_point_mode(config, parser)
    elif not (config.text_url or config.image_url or config.speech_url):
        raise UsageError('give at least one of --text-url, --image-url and --speech-url')
    for kind in ('text', 'speech'):
        url, model = getattr(config, f'{kind}_url'), getattr(config, f'{kind}_model')
        if url and not model:
            raise UsageError(f'--{kind}-url needs --{kind}-model')
        if model and not url:
            raise UsageError(f'--{kind}-model needs --{kind}-url')
    if bool(config.metrics_url) != bool(config.metrics_session_file):
        raise UsageError('--metrics-url and --metrics-session-file go together')
    config.session = None
    if config.metrics_url:
        if not _is_loopback(config.metrics_url.host):
            raise UsageError('the metrics URL must be on the loopback interface because it receives the session value')
        config.session = read_session(config.metrics_session_file)
    if config.competitor_arg and not config.competitor:
        raise UsageError('--competitor-arg needs --competitor')
    if config.competitor and config.competitor_start_after >= config.duration:
        raise UsageError('--competitor-start-after must be less than --duration')
    if config.docker_arg and not config.docker_logs:
        raise UsageError('--docker-arg needs --docker-logs')
    return config


def _check_entry_point_mode(config, parser):
    """Refuse every combination ``--via-entry-point`` cannot honour; the messages are fixed text.

    The probe route accepts no caller content and runs one probe at a time (a second gets 409), so the
    backend options, a concurrency above one and the request-shape options have no meaning in this mode.
    """
    if config.text_url or config.image_url or config.speech_url or config.text_model or config.speech_model:
        raise UsageError('--via-entry-point sends no load to model servers; drop the backend URLs and models')
    if not config.metrics_url:
        raise UsageError('--via-entry-point needs --metrics-url and --metrics-session-file')
    if config.concurrency != 1:
        raise UsageError('--via-entry-point runs one probe at a time; leave --concurrency at 1')
    for dest in ('text_path', 'image_path', 'speech_path', 'image_steps', 'image_size'):
        if getattr(config, dest) != parser.get_default(dest):
            raise UsageError('--via-entry-point has fixed requests; drop the path, step and size options')
    url = config.metrics_url
    if url.https or url.prefix or url.host not in _ENTRY_HOSTS:
        raise UsageError('--via-entry-point needs --metrics-url as http://127.0.0.1:<port>, '
                         'http://localhost:<port> or http://[::1]:<port>, the engine\'s configured origin')


def _is_loopback(host):
    if host == 'localhost':
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def read_session(path):
    """Read one session value from a small file; the message never quotes the content."""
    try:
        with open(path, 'rb') as handle:
            raw = handle.read(257)
    except OSError:
        raise UsageError('the session file cannot be read') from None
    if len(raw) > 256:
        raise UsageError('the session file is too large')
    text = raw.decode('ascii', 'replace').strip()
    if text.startswith(SESSION_COOKIE + '='):
        text = text[len(SESSION_COOKIE) + 1:]
    if _SESSION_VALUE.fullmatch(text) is None:
        raise UsageError('the session file must hold one 43-character session value')
    return text


# ---------------------------------------------------------------------- HTTP

def exchange(endpoint, method, path, timeout, body=None, headers=None, keep=0):
    """One request on a fresh connection, no proxy, no redirect.

    @return (status, up to ``keep`` leading body bytes); the rest of the body is read and dropped
    @raise OSError, http.client.HTTPException on any transport failure
    """
    connection_class = http.client.HTTPSConnection if endpoint.https else http.client.HTTPConnection
    connection = connection_class(endpoint.host, endpoint.port, timeout=timeout)
    try:
        connection.request(method, endpoint.prefix + path, body=body, headers=headers or {})
        response = connection.getresponse()
        kept, total = bytearray(), 0
        while True:
            chunk = response.read(65536)
            if not chunk:
                break
            total += len(chunk)
            if len(kept) < keep:
                kept += chunk[:keep - len(kept)]
            if total > MAX_RESPONSE_BYTES:
                break
        return response.status, bytes(kept)
    finally:
        connection.close()


def error_code(exc):
    """Fixed code for a transport failure; never the exception text, which can quote an address."""
    if isinstance(exc, TimeoutError):
        return 'timeout'
    if isinstance(exc, ConnectionRefusedError):
        return 'connection-refused'
    return 'connection-failed'


# ---------------------------------------------------------------------- load

class LoadStats:
    """Thread-safe tally of one backend's requests."""

    def __init__(self):
        self._lock = threading.Lock()
        self.requests = 0
        self.statuses = collections.Counter()
        self.errors = collections.Counter()
        self.latencies = []

    def record(self, status, error, millis):
        with self._lock:
            self.requests += 1
            if error is None:
                self.statuses[str(status)] += 1
                self.latencies.append(millis)
            else:
                self.errors[error] += 1

    def summary(self):
        with self._lock:
            answered = len(self.latencies)
            result = {
                'status': 'ok' if answered else 'unavailable',
                'requests': self.requests,
                'answered': answered,
                'statuses': dict(sorted(self.statuses.items())),
                'errors': dict(sorted(self.errors.items())),
            }
            if not answered:
                result['reason'] = 'no-answer'
            else:
                result['latency_ms'] = {'min': round(min(self.latencies), 1),
                                        'mean': round(sum(self.latencies) / answered, 1),
                                        'max': round(max(self.latencies), 1)}
            return result


class Tickets:
    """Hands out at most ``limit`` request slots across a backend's workers."""

    def __init__(self, limit):
        self._lock = threading.Lock()
        self._left = limit

    def take(self):
        with self._lock:
            if self._left <= 0:
                return False
            self._left -= 1
            return True


def drive(stats, endpoint, path, payload, deadline, tickets, stop, timeout):
    """Worker loop: send the fixed request until the deadline, the request cap or ``stop``."""
    body = json.dumps(payload).encode('utf-8')
    headers = {'Content-Type': 'application/json', 'Accept': '*/*'}
    while not stop.is_set() and time.monotonic() < deadline and tickets.take():
        began = time.monotonic()
        try:
            status, _ = exchange(endpoint, 'POST', path, timeout, body=body, headers=headers)
        except (OSError, http.client.HTTPException) as exc:
            stats.record(None, error_code(exc), 0.0)
            stop.wait(0.05)
        else:
            stats.record(status, None, (time.monotonic() - began) * 1000.0)


class EntryStats(LoadStats):
    """Tally of the probe calls, plus what the probe said about its text and image requests.

    The outcomes are the route's own fixed ``status`` and ``code`` words. Anything that does not look
    like such a word is counted as ``unreadable`` and never copied, because the body comes from a server.
    """

    def __init__(self):
        super().__init__()
        self.outcomes = {'text': collections.Counter(), 'image': collections.Counter()}
        self.unreadable = 0

    def record_body(self, body):
        parsed = {}
        try:
            reply = json.loads(body.decode('utf-8'))
            for kind in self.outcomes:
                status, code = reply[kind]['status'], reply[kind]['code']
                if (not isinstance(status, str) or not isinstance(code, str)
                        or _OUTCOME_STATUS.fullmatch(status) is None or _OUTCOME_CODE.fullmatch(code) is None):
                    raise ValueError('unreadable')
                parsed[kind] = f'{status}/{code}'
        except (ValueError, KeyError, TypeError):
            parsed = None
        with self._lock:
            if parsed is None:
                self.unreadable += 1
                return
            for kind, outcome in parsed.items():
                counter = self.outcomes[kind]
                if outcome in counter or len(counter) < MAX_OUTCOME_KINDS:
                    counter[outcome] += 1
                else:
                    self.unreadable += 1

    def summary(self):
        result = super().summary()
        with self._lock:
            result['outcomes'] = {kind: dict(sorted(counter.items())) for kind, counter in self.outcomes.items()}
            result['unreadable_replies'] = self.unreadable
        return result


def entry_headers(endpoint, session):
    """Headers the engine's admission requires: exact Host and Origin of its configured origin, and the session."""
    authority = f'[{endpoint.host}]:{endpoint.port}' if ':' in endpoint.host else f'{endpoint.host}:{endpoint.port}'
    return {'Host': authority, 'Origin': f'http://{authority}', 'Accept': 'application/json',
            'Cookie': f'{SESSION_COOKIE}={session}'}


def fetch_csrf(endpoint, session, timeout):
    """@return the CSRF token the engine ties to this session, or None when it did not hand one out.

    ``GET /api/v1/session`` with a live session cookie reuses that session and returns its token; with an
    unknown cookie the engine would start another session, whose token does not match, so the caller's
    probe is then refused and counted by status.
    @raise OSError, http.client.HTTPException on a transport failure
    """
    status, body = exchange(endpoint, 'GET', BOOTSTRAP_PATH, timeout, headers=entry_headers(endpoint, session),
                            keep=4096)
    if status != 200:
        return None
    try:
        token = json.loads(body.decode('utf-8'))['csrfToken']
    except (ValueError, KeyError, TypeError):
        return None
    return token if isinstance(token, str) and _SESSION_VALUE.fullmatch(token) else None


def entry_drive(stats, endpoint, session, deadline, tickets, stop, timeout):
    """Worker loop: call the probe route until the deadline, the request cap or ``stop``; one call at a time."""
    while not stop.is_set() and time.monotonic() < deadline and tickets.take():
        began = time.monotonic()
        try:
            token = fetch_csrf(endpoint, session, timeout)
            if token is None:
                stats.record(None, 'csrf-unavailable', 0.0)
                stop.wait(0.05)
                continue
            headers = entry_headers(endpoint, session)
            headers.update({'Content-Type': 'application/json', 'X-CSRF-Token': token})
            status, body = exchange(endpoint, 'POST', PROBE_PATH, timeout, body=b'{}', headers=headers,
                                    keep=MAX_METRICS_BYTES)
        except (OSError, http.client.HTTPException) as exc:
            stats.record(None, error_code(exc), 0.0)
            stop.wait(0.05)
        else:
            stats.record(status, None, (time.monotonic() - began) * 1000.0)
            if status == 200:
                stats.record_body(body)


def load_plans(config):
    """@return {modality: (endpoint, path, payload)} for the configured backends only"""
    plans = {}
    if config.text_url:
        plans['text'] = (config.text_url, config.text_path, {
            'model': config.text_model, 'stream': False, 'max_tokens': 16,
            'messages': [{'role': 'user', 'content': 'Describe a quiet harbour in one sentence.'}]})
    if config.image_url:
        plans['image'] = (config.image_url, config.image_path, {
            'prompt': 'a lighthouse on a cliff at dusk', 'steps': config.image_steps,
            'width': config.image_size, 'height': config.image_size})
    if config.speech_url:
        plans['speech'] = (config.speech_url, config.speech_path, {
            'model': config.speech_model, 'input': 'The gate is closed.'})
    return plans


# ------------------------------------------------------------------- sampling

class Sampler(threading.Thread):
    """Calls ``sample`` at once, every ``interval`` seconds, and a last time after ``finish``."""

    def __init__(self, interval, sample):
        super().__init__(daemon=True)
        self._interval = interval
        self._sample = sample
        self._done = threading.Event()
        self.results = []

    def run(self):
        while True:
            self.results.append(self._sample())
            if self._done.wait(self._interval):
                break
        self.results.append(self._sample())

    def finish(self):
        self._done.set()
        if self.is_alive():
            self.join()


def run_command(argv, timeout, merge_stderr=False):
    """@return stdout text; @raise CommandError with tool-missing, tool-not-runnable, timeout or exit-nonzero"""
    try:
        done = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT if merge_stderr else subprocess.DEVNULL,
                              timeout=timeout, check=False)
    except FileNotFoundError:
        raise CommandError('tool-missing') from None
    except subprocess.TimeoutExpired:
        raise CommandError('timeout') from None
    except OSError:
        raise CommandError('tool-not-runnable') from None
    if done.returncode != 0:
        raise CommandError('exit-nonzero')
    return done.stdout.decode('utf-8', 'replace')


def parse_gpu_table(text):
    """@return {index: (used_mib, total_mib)} from ``index, used, total`` lines; @raise ValueError otherwise"""
    gpus = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        parts = [part.strip() for part in line.split(',')]
        if len(parts) != 3 or not all(part.isascii() and part.isdigit() for part in parts):
            raise ValueError('unparsable')
        index, used, total = (int(part) for part in parts)
        if index in gpus:
            raise ValueError('unparsable')
        gpus[index] = (used, total)
    if not gpus:
        raise ValueError('unparsable')
    return gpus


def gpu_sample(command):
    """@return ('ok', {index: (used, total)}) or ('fail', reason)"""
    try:
        text = run_command(command + GPU_QUERY, COMMAND_TIMEOUT)
        return 'ok', parse_gpu_table(text)
    except CommandError as exc:
        return 'fail', exc.reason
    except ValueError:
        return 'fail', 'unparsable'


def summarize_gpu(results):
    good = [value for kind, value in results if kind == 'ok']
    failures = collections.Counter(value for kind, value in results if kind == 'fail')
    if not good:
        reason = failures.most_common(1)[0][0] if failures else 'no-samples'
        return {'status': 'unavailable', 'reason': reason, 'samples': 0, 'failed_samples': sum(failures.values())}
    gpus = {}
    for index in sorted({index for sample in good for index in sample}):
        used = [sample[index][0] for sample in good if index in sample]
        gpus[str(index)] = {'baseline_mib': used[0], 'peak_mib': max(used),
                            'total_mib': [sample[index][1] for sample in good if index in sample][-1]}
    return {'status': 'ok', 'samples': len(good), 'failed_samples': sum(failures.values()), 'gpus': gpus}


def metrics_sample(endpoint, session, timeout):
    """@return ('ok', {name: number}) or ('fail', reason) from the executor metrics route"""
    headers = {'Accept': 'application/json', 'Cookie': f'{SESSION_COOKIE}={session}'}
    try:
        status, body = exchange(endpoint, 'GET', METRICS_PATH, timeout, headers=headers, keep=MAX_METRICS_BYTES)
    except (OSError, http.client.HTTPException) as exc:
        return 'fail', 'timeout' if error_code(exc) == 'timeout' else 'unreachable'
    if status == 404:
        return 'fail', 'route-missing'
    if status in (401, 403):
        return 'fail', 'refused'
    if status != 200:
        return 'fail', f'http-{status}'
    try:
        values = json.loads(body.decode('utf-8'))
    except ValueError:
        return 'fail', 'invalid-body'
    if (not isinstance(values, dict) or len(values) > 1000
            or not all(isinstance(k, str) and isinstance(v, (int, float)) and not isinstance(v, bool)
                       for k, v in values.items())):
        return 'fail', 'invalid-body'
    return 'ok', values


def summarize_metrics(results):
    good = [value for kind, value in results if kind == 'ok']
    failures = collections.Counter(value for kind, value in results if kind == 'fail')
    if not good:
        reason = failures.most_common(1)[0][0] if failures else 'no-samples'
        return {'status': 'unavailable', 'reason': reason, 'samples': 0, 'failed_samples': sum(failures.values())}
    peak = {}
    for sample in good:
        for name, value in sample.items():
            peak[name] = max(peak.get(name, value), value)
    return {'status': 'ok', 'samples': len(good), 'failed_samples': sum(failures.values()),
            'first': good[0], 'last': good[-1], 'peak': dict(sorted(peak.items()))}


# ----------------------------------------------------------------- competitor

class Competitor(threading.Thread):
    """Runs the owner's competitor program from ``start_after`` seconds until ``finish``."""

    def __init__(self, argv, start_after):
        super().__init__(daemon=True)
        self._argv = argv
        self._start_after = start_after
        self._done = threading.Event()
        self.state = {'status': 'unavailable', 'reason': 'run-ended-before-start'}

    def run(self):
        if self._done.wait(self._start_after):
            return
        try:
            process = subprocess.Popen(self._argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL)
        except OSError:
            self.state = {'status': 'unavailable', 'reason': 'start-failed'}
            return
        self._done.wait()
        code = process.poll()
        if code is not None:
            self.state = {'status': 'ran', 'exited_early': True, 'exit_code': code}
            return
        process.terminate()
        try:
            process.wait(10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        self.state = {'status': 'ran', 'exited_early': False, 'exit_code': None}

    def finish(self):
        self._done.set()
        if self.is_alive():
            self.join()


# ----------------------------------------------------------------------- logs

class LogSource:
    """One place out-of-memory lines are looked for: a file or a container's log."""

    def __init__(self, label, kind, target):
        self.label = label
        self.kind = kind
        self.target = target
        self.offset = 0


def log_sources(config):
    sources = []
    for kind, flag, paths in (('server', 'file', config.server_log), ('engine', 'file', config.engine_log)):
        sources += [LogSource(f'{kind}-log-{n}', flag, path) for n, path in enumerate(paths, 1)]
    sources += [LogSource(f'container-log-{n}', 'docker', name) for n, name in enumerate(config.docker_logs, 1)]
    return sources


def scan_text(text):
    """@return (lines scanned, total events, [(line number, pattern name)] capped at MAX_EVENT_DETAILS)"""
    scanned, events, details = 0, 0, []
    for number, line in enumerate(text.splitlines(), 1):
        scanned += 1
        for name, pattern in OOM_PATTERNS:
            if pattern.search(line):
                events += 1
                if len(details) < MAX_EVENT_DETAILS:
                    details.append((number, name))
                break
    return scanned, events, details


def scan_source(source, since, docker_command):
    """@return the per-source observation; lines older than the run are skipped, and no line is copied"""
    rotated = truncated = False
    if source.kind == 'file':
        try:
            size = os.path.getsize(source.target)
            offset = source.offset if size >= source.offset else 0
            rotated = offset != source.offset
            with open(source.target, 'rb') as handle:
                handle.seek(offset)
                raw = handle.read(MAX_LOG_BYTES + 1)
        except FileNotFoundError:
            return {'status': 'unavailable', 'reason': 'file-missing'}
        except OSError:
            return {'status': 'unavailable', 'reason': 'file-unreadable'}
        text = raw[:MAX_LOG_BYTES].decode('utf-8', 'replace')
        truncated = len(raw) > MAX_LOG_BYTES
    else:
        try:
            raw = run_command(docker_command + ['logs', '--since', since, source.target], DOCKER_TIMEOUT, True)
        except CommandError as exc:
            return {'status': 'unavailable', 'reason': exc.reason}
        text = raw[:MAX_LOG_BYTES]
        truncated = len(raw) > MAX_LOG_BYTES
    scanned, events, details = scan_text(text)
    by_pattern = collections.Counter(name for _, name in details)
    return {'status': 'ok', 'lines_scanned': scanned, 'events': events,
            'by_pattern': dict(sorted(by_pattern.items())), 'line_numbers': [number for number, _ in details],
            'rotated': rotated, 'truncated': truncated}


def summarize_oom(per_source):
    if not per_source:
        return {'status': 'unavailable', 'reason': 'no-log-source', 'events': 0, 'sources': {}}
    readable = [item for item in per_source.values() if item['status'] == 'ok']
    result = {'events': sum(item['events'] for item in readable),
              'sources_unavailable': len(per_source) - len(readable), 'sources': per_source}
    if not readable:
        result.update(status='unavailable', reason='all-sources-unavailable')
    else:
        result['status'] = 'ok'
    return result


# ------------------------------------------------------------------ the run

def _utc(moment):
    return moment.strftime('%Y-%m-%dT%H:%M:%SZ')


def observe(config):
    """Run the whole contention window and @return the observations document (a dict)."""
    started = datetime.datetime.now(datetime.timezone.utc)
    began = time.monotonic()
    deadline = began + config.duration
    sources = log_sources(config)
    for source in sources:
        if source.kind == 'file':
            try:
                source.offset = os.path.getsize(source.target)
            except OSError:
                source.offset = 0
    plans = load_plans(config)
    stop = threading.Event()
    stats = {name: LoadStats() for name in plans}
    workers = []
    for name, (endpoint, path, payload) in plans.items():
        tickets = Tickets(config.max_requests)
        for _ in range(config.concurrency):
            workers.append(threading.Thread(daemon=True, target=drive, args=(
                stats[name], endpoint, path, payload, deadline, tickets, stop, config.request_timeout)))
    if config.via_entry_point:
        stats['entry_point'] = EntryStats()
        workers.append(threading.Thread(daemon=True, target=entry_drive, args=(
            stats['entry_point'], config.metrics_url, config.session, deadline, Tickets(config.max_requests), stop,
            config.request_timeout)))
    command = [config.nvidia_smi] + config.nvidia_smi_arg
    gpu = Sampler(config.interval, lambda: gpu_sample(command))
    metrics = None
    if config.metrics_url:
        metrics = Sampler(config.interval, lambda: metrics_sample(
            config.metrics_url, config.session, min(config.request_timeout, 30.0)))
    competitor = Competitor([config.competitor] + config.competitor_arg,
                            config.competitor_start_after) if config.competitor else None
    complete = True
    gpu.start()
    if metrics:
        metrics.start()
    if competitor:
        competitor.start()
    try:
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join()
    except KeyboardInterrupt:
        complete = False
        stop.set()
        for worker in workers:
            worker.join(2)
    if competitor:
        competitor.finish()
    gpu.finish()
    if metrics:
        metrics.finish()
    elapsed = time.monotonic() - began
    since = _utc(started - datetime.timedelta(seconds=1))
    docker_command = [config.docker] + config.docker_arg
    oom = summarize_oom({source.label: scan_source(source, since, docker_command) for source in sources})
    load = {name: stats[name].summary() if name in stats else {'status': 'unavailable', 'reason': 'not-configured'}
            for name in ('text', 'image', 'speech', 'entry_point')}
    metrics_result = summarize_metrics(metrics.results) if metrics else {
        'status': 'unavailable', 'reason': 'not-configured', 'samples': 0, 'failed_samples': 0}
    # The probe route sends one text and one image request per call, so both count as driven in that mode.
    metrics_result['driven'] = {name: name in plans or (config.via_entry_point and name != 'speech')
                                for name in ('text', 'image', 'speech')}
    return {
        'format': FORMAT,
        'complete': complete,
        'load_path': PATH_ENTRY_POINT if config.via_entry_point else PATH_DIRECT,
        'started_at': _utc(started),
        'duration_seconds': round(elapsed, 2),
        'settings': {'duration_seconds': config.duration, 'concurrency': config.concurrency,
                     'max_requests': config.max_requests, 'interval_seconds': config.interval,
                     'request_timeout_seconds': config.request_timeout},
        'load': load,
        'competitor': competitor.state if competitor else {'status': 'unavailable', 'reason': 'not-configured'},
        'gpu_memory': summarize_gpu(gpu.results),
        'out_of_memory': oom,
        'executor_metrics': metrics_result,
        'limits': list(LIMITS),
    }


def summary_lines(observations):
    gpu = observations['gpu_memory']
    peaks = [item['peak_mib'] for item in gpu.get('gpus', {}).values()]
    return [
        f"complete={str(observations['complete']).lower()}",
        f"load_path={observations['load_path']}",
        f"gpu_memory={'peak_mib=' + str(max(peaks)) if peaks else 'unavailable (' + gpu['reason'] + ')'}",
        f"out_of_memory={observations['out_of_memory']['status']} events={observations['out_of_memory']['events']}",
        f"executor_metrics={observations['executor_metrics']['status']}",
    ]


def main(argv=None):
    """@return 0 when the file was written (observations may be unavailable), 1 when the run failed, 2 for bad usage"""
    try:
        config = parse_arguments(sys.argv[1:] if argv is None else argv)
    except UsageError as exc:
        print(f'error: {exc}', file=sys.stderr)
        return 2
    except SystemExit as exc:
        return exc.code if isinstance(exc.code, int) else 2
    # Reserve the file first so that a bad path fails before a long run, and never replace an existing file.
    try:
        handle = open(config.output, 'x', encoding='utf-8', newline='\n')
    except OSError:
        print('error: the output file cannot be created or already exists', file=sys.stderr)
        return 2
    try:
        observations = observe(config)
        json.dump(observations, handle, indent=2, sort_keys=True)
        handle.write('\n')
    except Exception:
        handle.close()
        try:
            os.unlink(config.output)  # the reserved file is empty or partial; do not block a rerun
        except OSError:
            pass
        print('error: the run failed before the observations were written', file=sys.stderr)
        return 1
    handle.close()
    print(f'wrote {config.output}')
    for line in summary_lines(observations):
        print(line)
    return 0


if __name__ == '__main__':
    sys.exit(main())
