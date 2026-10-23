"""Small, sequential discovery client. This is not the concurrent game adapter.

Every profile reaches the runtime through one line-delimited JSON child
process: the runtime itself in stdio mode, or the Java 21 relay
(UnixRelay.java) that owns the packaged Unix-socket WebSocket connection. The
WebSocket framing is deliberately implemented only in Java, the language of
the future engine adapter, so the packaged evidence exercises that stack.

All waits share monotonic deadlines. Diagnostics contain codes/counters only;
runtime errors and stderr can contain credentials and are never forwarded.
"""
import json
import queue
import subprocess
import threading
import time

from contract import ContractError, MAX_MESSAGE, parse_json


def remaining(deadline):
    value = deadline - time.monotonic()
    if value <= 0:
        raise ContractError('transport-timeout')
    return value


class Stdio:
    """Own exactly one child; bound stdout buffering and drain stderr separately."""

    def __init__(self, command, *, env, cwd):
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
        while not self.closed.is_set():
            try:
                self.messages.put(value, timeout=.05)
                return
            except queue.Full:
                continue

    def _stdout(self):
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
        if len(data) > MAX_MESSAGE or b'\n' in data:
            raise ContractError('invalid-stdio-frame')
        try:
            self.process.stdin.write(data + b'\n')
            self.process.stdin.flush()
        except (OSError, ValueError) as exc:
            raise ContractError('transport-write') from exc

    def receive(self, deadline):
        try:
            value = self.messages.get(timeout=remaining(deadline))
        except queue.Empty as exc:
            raise ContractError('transport-timeout') from exc
        if isinstance(value, ContractError):
            raise value
        return value

    def close(self):
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
        return self

    def __exit__(self, *_exc):
        self.close()


class Rpc:
    """Sequential, bounded discovery RPC; unsolicited privileged requests deny."""
    def __init__(self, wire, timeout=30):
        self.wire, self.timeout = wire, timeout
        self.sequence = 0
        self.notifications = 0

    def _send(self, value):
        self.wire.send(json.dumps(value, separators=(',', ':'), allow_nan=False).encode('utf-8'))

    def notify(self, method, params):
        self._send({'method': method, 'params': params})

    def request(self, method, params):
        self.sequence += 1
        request_id = self.sequence
        deadline = time.monotonic() + self.timeout
        self._send({'id': request_id, 'method': method, 'params': params})
        for _ in range(128):
            value = parse_json(self.wire.receive(deadline))
            if not isinstance(value, dict):
                raise ContractError('invalid-rpc-message')
            if 'method' in value:
                if (not isinstance(value['method'], str) or not value['method']
                        or 'result' in value or 'error' in value):
                    raise ContractError('invalid-rpc-method')
                if 'id' in value:
                    if type(value['id']) not in (int, str):
                        raise ContractError('invalid-rpc-id')
                    self._send({'id': value['id'], 'error': {
                        'code': -32601, 'message': 'Unsupported by discovery probe'}})
                else:
                    self.notifications += 1
                continue
            if type(value.get('id')) is not int or value['id'] != request_id:
                raise ContractError('unexpected-rpc-id')
            if ('result' in value) == ('error' in value):
                raise ContractError('invalid-rpc-response')
            if 'error' in value:
                raise ContractError('runtime-rpc-error')
            return value['result']
        raise ContractError('rpc-event-limit')
