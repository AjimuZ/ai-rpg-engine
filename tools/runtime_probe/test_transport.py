"""Bounded framing, correlation, and process ownership checks."""
import json
import sys
import time
import unittest

from contract import ContractError
import transport


class FixtureTransport:
    def __init__(self, messages):
        self.messages = iter(messages)
        self.sent = []

    def send(self, value):
        self.sent.append(json.loads(value))

    def receive(self, _deadline):
        return json.dumps(next(self.messages)).encode()


class RpcTests(unittest.TestCase):
    def test_notifications_do_not_complete_request_and_privileged_requests_are_denied(self):
        wire = FixtureTransport([{'method': 'account/updated', 'params': {}},
                                 {'id': 'approval', 'method': 'unknown/tool', 'params': {}},
                                 {'id': 1, 'result': {'ok': True}}])
        rpc = transport.Rpc(wire)
        self.assertEqual(rpc.request('initialize', {}), {'ok': True})
        self.assertEqual(wire.sent[1]['error']['code'], -32601)
        self.assertEqual(wire.sent[1]['id'], 'approval')
        self.assertEqual(rpc.notifications, 1)

    def test_malformed_or_mismatched_response_and_runtime_error_are_redacted(self):
        for message in [{'id': 2, 'result': {}}, {'id': True, 'result': {}},
                        {'id': 1, 'error': {'message': 'secret-token'}},
                        {'id': 1, 'result': {}, 'error': {}}, [], {'id': 1}]:
            with self.subTest(message=message), self.assertRaises(ContractError) as caught:
                transport.Rpc(FixtureTransport([message])).request('x', {})
            self.assertNotIn('secret-token', str(caught.exception))

    def test_notification_flood_is_bounded(self):
        wire = FixtureTransport([{'method': 'noise'}] * 130)
        with self.assertRaises(ContractError):
            transport.Rpc(wire).request('x', {})


class StdioTests(unittest.TestCase):
    def test_owned_child_and_stderr_are_drained_and_closed(self):
        script = "import sys; sys.stderr.write('x'*100000); sys.stderr.flush(); sys.stdout.buffer.write(sys.stdin.buffer.readline()); sys.stdout.flush(); sys.stdin.read()"
        with transport.Stdio([sys.executable, '-u', '-c', script], env=None, cwd=None) as wire:
            wire.send(b'{"id":1}')
            self.assertEqual(wire.receive(time.monotonic() + 5), b'{"id":1}')
            child = wire.process
        self.assertIsNotNone(child.poll())
        self.assertLessEqual(wire.stderr_bytes, 65536)
        self.assertTrue(wire.stderr_truncated)

    def test_eof_oversize_and_timeout_are_explicit(self):
        scripts = ["pass", "print('x'*1048577,flush=True)", "import time; time.sleep(5)"]
        for script in scripts:
            with self.subTest(script=script), transport.Stdio(
                    [sys.executable, '-u', '-c', script], env=None, cwd=None) as wire:
                with self.assertRaises(ContractError):
                    wire.receive(time.monotonic() + .2)


if __name__ == '__main__':
    unittest.main()
