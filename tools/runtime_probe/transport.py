"""Small, sequential protocol client. This is not the concurrent game adapter.

Every profile reaches the runtime through one line-delimited JSON child
process: the runtime itself in stdio mode, or the Java 21 relay
(UnixRelay.java) that owns the packaged Unix-socket WebSocket connection. The
WebSocket framing is deliberately implemented only in Java, the language of
the future engine adapter, so the packaged evidence exercises that stack.

All waits share monotonic deadlines. Diagnostics contain codes/counters only;
runtime errors and stderr can contain credentials and are never forwarded.

Process lifecycle (docs/06 sections 4, 5 and 11; measured in P01.4):
- a terminal wire failure (EOF, read error, oversized frame) is sticky:
  frames that arrived before it are still delivered in order, and every
  later receive raises the same code at once instead of waiting for a
  deadline;
- an Rpc generation that lost its wire, or whose request ended without
  a correlated response, is closed for good: its buffered notifications are discarded
  and counted, and the caller reconciles through a new process generation;
- shutdown closes input first and escalates only against the owned child,
  through an optional caller-supplied hook (for a container, a `docker kill`
  of exactly that named container), then terminate/kill of the child
  itself. close() returns a shutdown record instead of failing silently.
"""
import collections
import json
import queue
import re
import subprocess
import threading
import time

from contract import ContractError, MAX_MESSAGE, parse_json


# Wire failures after which no later frame of the same process can be
# trusted. A timeout is deliberately absent: the wire itself may be fine.
TERMINAL_WIRE_ERRORS = frozenset({
    'transport-eof', 'transport-read', 'transport-write', 'invalid-stdio-frame'})

# Stderr is classified only up to this many bytes; the rest is drained so the
# child never blocks on a full pipe, and is only reported as truncated.
STDERR_LIMIT = 65536
# A stderr line longer than this is not buffered further. Only its prefix can
# match the structured pattern, so one endless line costs bounded memory.
STDERR_LINE_LIMIT = 512
# Number of most recent classified stderr entries kept for diagnostics.
STDERR_TAIL = 16
# The runtime logs `<RFC 3339 UTC time> <LEVEL> <module::path>: <message>`.
# Only fixed levels and allowlisted module labels survive; messages can
# carry paths, prompts or tokens. Other shapes, including Compose progress
# lines, are reduced to 'unstructured'.
# Only exact, known diagnostic sources may survive; arbitrary module-shaped
# text can be injected through an upstream error containing a newline.
STDERR_MODULES = frozenset({'codex_app_server', 'codex_core::config'})
STDERR_PATTERN = re.compile(
    rb'\d{4}-\d\d-\d\dT[0-9:.]+Z +(ERROR|WARN|INFO|DEBUG|TRACE) +'
    rb'([a-z][a-z0-9_]{0,47}(?:::[a-z][a-z0-9_]{0,47}){0,7}):')


def remaining(deadline):
    """Return positive seconds until the supplied monotonic deadline; raise transport-timeout
    if it has expired.
    """
    value = deadline - time.monotonic()
    if value <= 0:
        raise ContractError('transport-timeout')
    return value


def classify_stderr_line(line):
    """Reduce one raw stderr line to a fixed diagnostic entry.

    Input: the bytes of one line without its newline (possibly only its
    bounded prefix). Output: 'LEVEL module::path' when the line has the
    runtime's structured log shape, otherwise 'unstructured'. The message
    part is never returned; unknown module names become 'unclassified' so
    a structured-looking private string cannot escape through that field.
    """
    match = STDERR_PATTERN.match(line)
    if match is None:
        return 'unstructured'
    module = match.group(2).decode('ascii')
    if module not in STDERR_MODULES:
        module = 'unclassified'
    return f"{match.group(1).decode('ascii')} {module}"


class Stdio:
    """Own exactly one child; bound stdout buffering and drain stderr separately.

    ``terminate`` is an optional callable used as the first escalation step
    when the child does not exit after its input closes. Container launches
    pass a hook that kills exactly their own named container, because ending
    the Docker client process alone leaves that container running (measured
    on Windows in P01.4). ``grace`` is the wait after closing input and after
    each escalation step. After close(), ``shutdown`` holds the record that
    close() returned.
    """

    def __init__(self, command, *, env, cwd, terminate=None, grace=2.0):
        """Launch command in the supplied environment/cwd and start bounded stream readers;
        initialize an owned child or propagate process/thread startup failures.
        """
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        env=env, cwd=cwd, shell=False)
        self.terminate_hook = terminate
        self.grace = grace
        self.messages = queue.Queue(maxsize=16)
        self.closed = threading.Event()
        # First terminal wire failure returned by receive(); every later
        # receive raises it again immediately (sticky EOF).
        self.failure = None
        self.shutdown = None
        self.stderr_bytes = 0
        self.stderr_truncated = False
        self.stderr_lines = 0
        self.stderr_levels = collections.Counter()
        self.stderr_tail = collections.deque(maxlen=STDERR_TAIL)
        self.readers = [threading.Thread(target=f, daemon=True)
                        for f in (self._stdout, self._stderr)]
        for reader in self.readers:
            reader.start()

    def _offer(self, value):
        """Queue a frame or ContractError from a reader with bounded backpressure; return
        without enqueueing if this child is closing.
        """
        while not self.closed.is_set():
            try:
                self.messages.put(value, timeout=.05)
                return
            except queue.Full:
                continue

    def _stdout(self):
        """Read child stdout into bounded newline frames; enqueue fixed errors for EOF,
        oversized frames or read failures instead of leaking raw output.
        """
        try:
            while not self.closed.is_set():
                line = self.process.stdout.readline(MAX_MESSAGE + 2)
                if not line:
                    self._offer(ContractError('transport-eof'))
                    return
                if len(line) > MAX_MESSAGE + 1 or not line.endswith(b'\n'):
                    self._offer(ContractError('invalid-stdio-frame'))
                    return
                self._offer(line[:-1])
        except (OSError, ValueError):
            self._offer(ContractError('transport-read'))

    def _record_stderr_line(self, line):
        """Count one stderr line (or its bounded prefix) and keep only its class.

        Input: raw line bytes. Output: None; the raw bytes are not retained.
        """
        entry = classify_stderr_line(line)
        self.stderr_lines += 1
        self.stderr_levels[entry.split(' ', 1)[0]] += 1
        self.stderr_tail.append(entry)

    def _stderr(self):
        """Drain child stderr, classifying the first STDERR_LIMIT bytes into redacted entries.

        Takes no arguments and tolerates stream closure while shutting down.
        Raw bytes live only in a line buffer of at most STDERR_LINE_LIMIT
        bytes and are dropped as soon as their line is classified.
        """
        line = b''
        try:
            while data := self.process.stderr.read(4096):
                room = STDERR_LIMIT - self.stderr_bytes
                if room <= 0:
                    # Keep draining so the child cannot block on stderr.
                    self.stderr_truncated = True
                    continue
                if len(data) > room:
                    data, self.stderr_truncated = data[:room], True
                self.stderr_bytes += len(data)
                *complete, rest = data.split(b'\n')
                for part in complete:
                    self._record_stderr_line((line + part)[:STDERR_LINE_LIMIT])
                    line = b''
                # An overlong line keeps only its prefix until its newline.
                line = (line + rest)[:STDERR_LINE_LIMIT]
        except (OSError, ValueError):
            pass
        # A final line without a newline still counts once.
        if line:
            self._record_stderr_line(line)

    def stderr_summary(self):
        """Return bounded, redacted stderr evidence.

        Output: {'bytes', 'truncated', 'lines', 'levels', 'tail'}, where
        'levels' counts entries per log level (or 'unstructured') and 'tail'
        lists at most STDERR_TAIL recent 'LEVEL module::path' entries. No
        message text is included. Call after close() for complete counts.
        """
        return {'bytes': self.stderr_bytes, 'truncated': self.stderr_truncated,
                'lines': self.stderr_lines, 'levels': dict(sorted(self.stderr_levels.items())),
                'tail': list(self.stderr_tail)}

    def send(self, data):
        """Write one supplied byte frame and flush; return None or raise a fixed ContractError
        for framing/write failures.
        """
        if len(data) > MAX_MESSAGE or b'\n' in data:
            raise ContractError('invalid-stdio-frame')
        try:
            self.process.stdin.write(data + b'\n')
            self.process.stdin.flush()
        except (OSError, ValueError) as exc:
            raise ContractError('transport-write') from exc

    def receive(self, deadline):
        """Return the next queued byte frame before the supplied deadline.

        Frames queued before a terminal wire error are returned first, in
        order. The error itself (EOF, read failure, oversized frame) is then
        raised, and raised again on every later call without waiting.
        Raises transport-timeout when the deadline passes first.
        """
        if self.failure is not None:
            raise ContractError(self.failure)
        try:
            value = self.messages.get(timeout=remaining(deadline))
        except queue.Empty as exc:
            raise ContractError('transport-timeout') from exc
        if isinstance(value, ContractError):
            self.failure = str(value)
            raise value
        return value

    def _wait(self, seconds):
        """Wait up to ``seconds`` for the child; return True once it has exited."""
        try:
            self.process.wait(timeout=seconds)
            return True
        except subprocess.TimeoutExpired:
            return False

    def close(self):
        """Shut down the owned child in bounded steps and return the shutdown record.

        Steps: a child that had already exited is recorded as 'exited';
        otherwise its input is closed (the runtime's and relay's shutdown
        signal) and, if it exits within ``grace``, recorded as
        'input-closed'. Next the terminate hook runs, or the child is
        terminated when there is no hook ('terminated'); last the child is
        killed ('killed'). A child that survives even that is recorded as
        'unreaped' rather than raised, so the caller's other cleanup still
        runs. Repeated calls return the first record.
        Output: {'stage', 'exitCode', 'seconds'}.
        """
        if self.shutdown is not None:
            return self.shutdown
        started = time.monotonic()
        stage = 'exited' if self.process.poll() is not None else None
        self.closed.set()
        try:
            self.process.stdin.close()
        except (OSError, ValueError):
            # A broken pipe only means the child already stopped reading.
            pass
        if stage is None and self._wait(self.grace):
            stage = 'input-closed'
        if stage is None:
            try:
                if self.terminate_hook is not None:
                    self.terminate_hook()
                else:
                    self.process.terminate()
            except OSError:
                pass
            if self._wait(self.grace):
                stage = 'terminated'
        if stage is None:
            try:
                self.process.kill()
            except OSError:
                pass
            stage = 'killed' if self._wait(self.grace) else 'unreaped'
        for reader in self.readers:
            reader.join(timeout=1)
        self.process.stdout.close()
        self.process.stderr.close()
        self.shutdown = {'stage': stage, 'exitCode': self.process.returncode,
                         'seconds': round(time.monotonic() - started, 2)}
        return self.shutdown

    def __enter__(self):
        """Return this already-started owned child for a context manager; take no inputs and
        perform no extra I/O.
        """
        return self

    def __exit__(self, *_exc):
        """Close this owned child on normal or exceptional context exit; return None without
        suppressing the original exception.
        """
        self.close()


class Rpc:
    """Sequential, bounded RPC client for one process generation.

    Exactly one request is outstanding at a time and request IDs increase
    monotonically. Notifications that arrive while a request waits for its
    response are kept, in arrival order, in a bounded buffer instead of being
    dropped: a turn's first events can precede its turn/start response, and
    the session client (session.py) only learns the turn ID from that
    response. Buffer overflow is an explicit error, never a silent drop.

    The generation closes for good on a terminal wire failure or on a
    request failure without a correlated response: upstream work may still
    execute, and a late response would violate the single-request contract.
    Its buffered notifications are then
    discarded and counted in ``discarded``, and every later call raises
    ``failure`` without touching the wire: the caller must reconcile through
    a new process (thread/resume plus thread/turns/list), never by retrying
    here.
    """
    # Per-request message bound: a response must arrive within this many
    # messages, so a notification flood cannot keep a request waiting forever.
    MAX_EVENTS_PER_REQUEST = 128
    # Notifications retained for later correlation. A sequential client that
    # stops consuming them fails closed with 'notification-overflow'.
    MAX_PENDING = 256

    def __init__(self, wire, timeout=30):
        """Store the supplied wire and request timeout, with empty sequence/notification state;
        perform no I/O and leave wire failures to the first operation.
        """
        self.wire, self.timeout = wire, timeout
        self.sequence = 0
        self.notifications = 0
        self.pending = collections.deque()
        # Notifications buffered while the latest request awaited its response.
        self.early = 0
        self.failure = None
        self.discarded = 0

    def _close_generation(self, code):
        """Mark this generation failed with ``code`` and discard its buffered events."""
        if self.failure is None:
            self.failure = code
            self.discarded += len(self.pending)
            self.pending.clear()

    def _check_open(self):
        """Raise the recorded failure code if this generation is already closed."""
        if self.failure is not None:
            raise ContractError(self.failure)

    def _send(self, value):
        """Serialize the supplied RPC object as strict compact UTF-8 JSON; return None or
        propagate serialization/framing/write failures, closing the generation on a
        terminal write failure.
        """
        try:
            self.wire.send(json.dumps(value, separators=(',', ':'),
                                      allow_nan=False).encode('utf-8'))
        except ContractError as exc:
            if str(exc) in TERMINAL_WIRE_ERRORS:
                self._close_generation(str(exc))
            raise

    def notify(self, method, params):
        """Send the supplied method and params without a request ID; return None or propagate
        serialization/transport failures and an earlier generation failure.
        """
        self._check_open()
        self._send({'method': method, 'params': params})

    def _receive(self, deadline):
        """Read one message; deny server requests and buffer notifications.

        Returns the message when it is a response (has neither 'method' nor a
        privileged request shape) and None when it was consumed here.
        Raises ContractError for malformed messages or buffer overflow; a
        terminal wire failure also closes the generation.
        """
        try:
            raw = self.wire.receive(deadline)
        except ContractError as exc:
            if str(exc) in TERMINAL_WIRE_ERRORS:
                self._close_generation(str(exc))
            raise
        value = parse_json(raw)
        if not isinstance(value, dict):
            raise ContractError('invalid-rpc-message')
        if 'method' not in value:
            return value
        if (not isinstance(value['method'], str) or not value['method']
                or 'result' in value or 'error' in value):
            raise ContractError('invalid-rpc-method')
        if 'id' in value:
            # Server requests (approvals, tool calls, elicitation) are never
            # granted by this client; unknown privileged requests deny.
            if type(value['id']) not in (int, str):
                raise ContractError('invalid-rpc-id')
            self._send({'id': value['id'], 'error': {
                'code': -32601, 'message': 'Unsupported by protocol probe'}})
            return None
        if 'params' in value and not isinstance(value['params'], dict):
            raise ContractError('invalid-rpc-params')
        if len(self.pending) >= self.MAX_PENDING:
            raise ContractError('notification-overflow')
        self.notifications += 1
        self.pending.append({'method': value['method'], 'params': value.get('params', {})})
        return None

    def request(self, method, params):
        """Send one request and return its result.

        Raises ContractError with a fixed code for timeouts, mismatched or
        malformed responses, runtime errors (contents are never forwarded),
        and more than MAX_EVENTS_PER_REQUEST messages before the response.
        A timeout or malformed reply leaves the request unresolved, closing the generation
        with 'request-unresolved'; later calls raise that code.
        """
        self._check_open()
        self.sequence += 1
        request_id = self.sequence
        deadline = time.monotonic() + self.timeout
        self.early = 0
        resolved = False
        try:
            self._send({'id': request_id, 'method': method, 'params': params})
            for _ in range(self.MAX_EVENTS_PER_REQUEST):
                value = self._receive(deadline)
                if value is None:
                    self.early += 1
                    continue
                if type(value.get('id')) is not int or value['id'] != request_id:
                    raise ContractError('unexpected-rpc-id')
                if ('result' in value) == ('error' in value):
                    raise ContractError('invalid-rpc-response')
                # A well-formed matching error resolves the request just as a
                # result does; only unresolved requests poison the generation.
                resolved = True
                if 'error' in value:
                    raise ContractError('runtime-rpc-error')
                return value['result']
            raise ContractError('rpc-event-limit')
        except ContractError:
            if not resolved:
                # Preserve a more specific terminal wire failure if recorded.
                # Malformed replies and floods are ambiguous like timeouts:
                # the request may have executed, so never reuse this generation.
                self._close_generation('request-unresolved')
            raise

    def next_notification(self, deadline):
        """Return the oldest notification as {'method', 'params'}.

        Buffered notifications are returned first, then the wire is read
        until a notification arrives or the deadline passes. No request is
        outstanding here, so any response is an 'unexpected-rpc-id'. An
        expired deadline does not close the generation: the caller may still
        interrupt the turn on this connection.
        """
        self._check_open()
        # A backlog must not bypass the deadline merely because it no longer
        # needs a blocking wire read.
        remaining(deadline)
        while not self.pending:
            if self._receive(deadline) is not None:
                raise ContractError('unexpected-rpc-id')
            remaining(deadline)
        return self.pending.popleft()
