"""Small, sequential protocol client. This is not the concurrent game adapter.

Every profile reaches the runtime through one line-delimited JSON child
process: the runtime itself in stdio mode, or the Java 21 relay
(UnixRelay.java) that owns the packaged Unix-socket WebSocket connection. The
WebSocket framing is deliberately implemented only in Java, the language of
the future engine adapter, so the packaged evidence exercises that stack.

All waits share monotonic deadlines. Diagnostics contain codes/counters only;
runtime errors and stderr can contain credentials and are never forwarded.
"""
import collections
import json
import queue
import subprocess
import threading
import time

from contract import ContractError, MAX_MESSAGE, parse_json


def remaining(deadline):
    """Return positive seconds until the supplied monotonic deadline; raise transport-timeout
    if it has expired.
    """
    value = deadline - time.monotonic()
    if value <= 0:
        raise ContractError('transport-timeout')
    return value


class Stdio:
    """Own exactly one child; bound stdout buffering and drain stderr separately."""

    def __init__(self, command, *, env, cwd):
        """Launch command in the supplied environment/cwd and start bounded stream readers;
        initialize an owned child or propagate process/thread startup failures.
        """
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        env=env, cwd=cwd, shell=False)
        self.messages = queue.Queue(maxsize=16)
        self.closed = threading.Event()
        self.stderr_bytes = 0
        self.stderr_truncated = False
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

    def _stderr(self):
        """Drain and discard child stderr with saturated counters; take no arguments and
        tolerate stream closure while shutting down.
        """
        try:
            while data := self.process.stderr.read(4096):
                total = self.stderr_bytes + len(data)
                self.stderr_bytes = min(total, 65536)
                self.stderr_truncated |= total > 65536
                # Deliberately discard contents; byte counters diagnose floods
                # without retaining raw account paths, prompts, or tokens.
        except (OSError, ValueError):
            pass

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
        """Return the next queued byte frame before the supplied deadline; raise a queued
        transport error or transport-timeout.
        """
        try:
            value = self.messages.get(timeout=remaining(deadline))
        except queue.Empty as exc:
            raise ContractError('transport-timeout') from exc
        if isinstance(value, ContractError):
            raise value
        return value

    def close(self):
        """Close this owned child and its streams, escalating bounded waits to terminate/kill;
        return None or propagate unrecoverable OS failures.
        """
        self.closed.set()
        self.process.stdin.close()
        try:
            self.process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try:
                self.process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)
        for reader in self.readers:
            reader.join(timeout=1)
        self.process.stdout.close()
        self.process.stderr.close()

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
    """Sequential, bounded RPC client; unsolicited privileged requests deny.

    Exactly one request is outstanding at a time and request IDs increase
    monotonically. Notifications that arrive while a request waits for its
    response are kept, in arrival order, in a bounded buffer instead of being
    dropped: a turn's first events can precede its turn/start response, and
    the session client (session.py) only learns the turn ID from that
    response. Buffer overflow is an explicit error, never a silent drop.
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

    def _send(self, value):
        """Serialize the supplied RPC object as strict compact UTF-8 JSON; return None or
        propagate serialization/framing/write failures.
        """
        self.wire.send(json.dumps(value, separators=(',', ':'), allow_nan=False).encode('utf-8'))

    def notify(self, method, params):
        """Send the supplied method and params without a request ID; return None or propagate
        serialization/transport failures.
        """
        self._send({'method': method, 'params': params})

    def _receive(self, deadline):
        """Read one message; deny server requests and buffer notifications.

        Returns the message when it is a response (has neither 'method' nor a
        privileged request shape) and None when it was consumed here.
        Raises ContractError for malformed messages or buffer overflow.
        """
        value = parse_json(self.wire.receive(deadline))
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
        """
        self.sequence += 1
        request_id = self.sequence
        deadline = time.monotonic() + self.timeout
        self._send({'id': request_id, 'method': method, 'params': params})
        for _ in range(self.MAX_EVENTS_PER_REQUEST):
            value = self._receive(deadline)
            if value is None:
                continue
            if type(value.get('id')) is not int or value['id'] != request_id:
                raise ContractError('unexpected-rpc-id')
            if ('result' in value) == ('error' in value):
                raise ContractError('invalid-rpc-response')
            if 'error' in value:
                raise ContractError('runtime-rpc-error')
            return value['result']
        raise ContractError('rpc-event-limit')

    def next_notification(self, deadline):
        """Return the oldest notification as {'method', 'params'}.

        Buffered notifications are returned first, then the wire is read
        until a notification arrives or the deadline passes. No request is
        outstanding here, so any response is an 'unexpected-rpc-id'.
        """
        # A backlog must not bypass the deadline merely because it no longer
        # needs a blocking wire read.
        remaining(deadline)
        while not self.pending:
            if self._receive(deadline) is not None:
                raise ContractError('unexpected-rpc-id')
            remaining(deadline)
        return self.pending.popleft()
