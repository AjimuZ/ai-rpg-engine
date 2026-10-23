"""Bounded framing, correlation, process ownership and lifecycle checks.

The Stdio tests launch the current Python interpreter as a stand-in child, so
EOF, exit codes, ignored input closure and stderr shapes are exercised
without Docker or the runtime. test_lifecycle.py repeats the relevant cases
against the real runtime.
"""
import json
import sys
import time
import unittest

from contract import ContractError
import transport


class FixtureTransport:
    """Scripted wire for Rpc tests.

    Input: a list of messages; a ContractError entry is raised instead of
    returned, and an exhausted script raises transport-eof like a real wire.
    Sent frames are decoded into ``sent``; ``send_error`` makes send fail.
    """

    def __init__(self, messages, send_error=None):
        """Store the scripted messages and an optional send failure code."""
        self.messages = iter(messages)
        self.sent = []
        self.send_error = send_error

    def send(self, value):
        """Record one sent frame, or raise the configured send failure."""
        if self.send_error is not None:
            raise ContractError(self.send_error)
        self.sent.append(json.loads(value))

    def receive(self, _deadline):
        """Return the next scripted frame; raise scripted errors and EOF."""
        value = next(self.messages, ContractError('transport-eof'))
        if isinstance(value, ContractError):
            raise value
        return json.dumps(value).encode()


def child(script, **options):
    """Start ``script`` under the current interpreter as an owned Stdio child."""
    return transport.Stdio([sys.executable, '-u', '-c', script], env=None, cwd=None, **options)


class RpcTests(unittest.TestCase):
    """Correlation, denial, buffering and generation-closure rules of Rpc."""

    def test_notifications_do_not_complete_request_and_privileged_requests_are_denied(self):
        """A notification is buffered and a server request is denied before the response."""
        wire = FixtureTransport([{'method': 'account/updated', 'params': {}},
                                 {'id': 'approval', 'method': 'unknown/tool', 'params': {}},
                                 {'id': 1, 'result': {'ok': True}}])
        rpc = transport.Rpc(wire)
        self.assertEqual(rpc.request('initialize', {}), {'ok': True})
        self.assertEqual(wire.sent[1]['error']['code'], -32601)
        self.assertEqual(wire.sent[1]['id'], 'approval')
        self.assertEqual(rpc.notifications, 1)

    def test_malformed_or_mismatched_response_and_runtime_error_are_redacted(self):
        """Bad responses raise fixed codes that never contain runtime text."""
        for message in [{'id': 2, 'result': {}}, {'id': True, 'result': {}},
                        {'id': 1, 'error': {'message': 'secret-token'}},
                        {'id': 1, 'result': {}, 'error': {}}, [], {'id': 1}]:
            with self.subTest(message=message), self.assertRaises(ContractError) as caught:
                transport.Rpc(FixtureTransport([message])).request('x', {})
            self.assertNotIn('secret-token', str(caught.exception))

    def test_notification_flood_is_bounded(self):
        """More than MAX_EVENTS_PER_REQUEST messages before a response fail the request."""
        wire = FixtureTransport([{'method': 'noise'}] * 130)
        with self.assertRaises(ContractError):
            transport.Rpc(wire).request('x', {})

    def test_early_notifications_are_buffered_in_arrival_order(self):
        """Events that precede a response stay available, in order, after it."""
        wire = FixtureTransport([{'method': 'turn/started', 'params': {'n': 1}},
                                 {'method': 'item/started', 'params': {'n': 2}},
                                 {'id': 1, 'result': {}}])
        rpc = transport.Rpc(wire)
        rpc.request('turn/start', {})
        self.assertEqual(rpc.early, 2)
        deadline = time.monotonic() + 1
        self.assertEqual(rpc.next_notification(deadline)['params'], {'n': 1})
        self.assertEqual(rpc.next_notification(deadline)['params'], {'n': 2})

    def test_eof_discards_the_generation_and_every_later_call_fails_without_io(self):
        """EOF discards buffered events and later calls repeat the code without writing."""
        wire = FixtureTransport([{'method': 'turn/started'}, {'method': 'item/started'}])
        rpc = transport.Rpc(wire)
        with self.assertRaises(ContractError) as caught:
            rpc.request('turn/start', {})
        self.assertEqual(str(caught.exception), 'transport-eof')
        self.assertEqual((rpc.failure, rpc.discarded, len(rpc.pending)), ('transport-eof', 2, 0))
        sent = len(wire.sent)
        for call in (lambda: rpc.request('turn/interrupt', {}),
                     lambda: rpc.notify('initialized', {}),
                     lambda: rpc.next_notification(time.monotonic() + 1)):
            with self.subTest(call=call), self.assertRaises(ContractError) as again:
                call()
            self.assertEqual(str(again.exception), 'transport-eof')
        self.assertEqual(len(wire.sent), sent)

    def test_write_failure_closes_the_generation(self):
        """A failed write is terminal: the next call does not try the wire again."""
        wire = FixtureTransport([], send_error='transport-write')
        rpc = transport.Rpc(wire)
        for _ in range(2):
            with self.assertRaises(ContractError) as caught:
                rpc.request('x', {})
            self.assertEqual(str(caught.exception), 'transport-write')
        self.assertEqual(rpc.failure, 'transport-write')

    def test_request_timeout_leaves_the_generation_unresolved(self):
        """A request without a response may still run, so the generation is closed."""
        wire = FixtureTransport([{'method': 'noise'}, ContractError('transport-timeout')])
        rpc = transport.Rpc(wire)
        with self.assertRaises(ContractError) as caught:
            rpc.request('turn/start', {})
        self.assertEqual(str(caught.exception), 'transport-timeout')
        self.assertEqual(rpc.discarded, 1)
        with self.assertRaises(ContractError) as again:
            rpc.request('turn/interrupt', {})
        self.assertEqual(str(again.exception), 'request-unresolved')
        self.assertEqual(len(wire.sent), 1)

    def test_unresolved_protocol_failures_close_the_generation(self):
        """Bad replies and event floods fail once, then reject reuse without I/O."""
        scripts = [([{'id': 9, 'result': {}}], 'unexpected-rpc-id'),
                   ([{'id': 1}], 'invalid-rpc-response'),
                   ([[]], 'invalid-rpc-message'),
                   ([{'method': 'noise'}] * 128, 'rpc-event-limit')]
        for messages, code in scripts:
            with self.subTest(code=code):
                wire = FixtureTransport(messages)
                rpc = transport.Rpc(wire)
                with self.assertRaisesRegex(ContractError, '^' + code + '$'):
                    rpc.request('turn/start', {})
                with self.assertRaisesRegex(ContractError, '^request-unresolved$'):
                    rpc.notify('initialized', {})
                self.assertFalse(rpc.pending)
                self.assertEqual(len(wire.sent), 1)

    def test_correlated_error_response_does_not_poison_the_generation(self):
        """A matching error resolves its request; the next request may proceed."""
        wire = FixtureTransport([{'id': 1, 'error': {'message': 'private'}},
                                 {'id': 2, 'result': {}}])
        rpc = transport.Rpc(wire)
        with self.assertRaisesRegex(ContractError, '^runtime-rpc-error$'):
            rpc.request('x', {})
        self.assertEqual(rpc.request('y', {}), {})

    def test_notification_deadline_keeps_the_generation_usable(self):
        """A turn deadline is not a wire failure: the caller can still interrupt."""
        wire = FixtureTransport([ContractError('transport-timeout'), {'id': 1, 'result': {}}])
        rpc = transport.Rpc(wire)
        with self.assertRaises(ContractError) as caught:
            rpc.next_notification(time.monotonic() + 1)
        self.assertEqual(str(caught.exception), 'transport-timeout')
        self.assertIsNone(rpc.failure)
        self.assertEqual(rpc.request('turn/interrupt', {}), {})


class StdioTests(unittest.TestCase):
    """Framing, EOF, shutdown and stderr bounds of an owned child process."""

    def test_owned_child_and_stderr_are_drained_and_closed(self):
        """Oversized stderr cannot block the child and is reported as truncated."""
        script = "import sys; sys.stderr.write('x'*100000); sys.stderr.flush(); sys.stdout.buffer.write(sys.stdin.buffer.readline()); sys.stdout.flush(); sys.stdin.read()"
        with child(script) as wire:
            wire.send(b'{"id":1}')
            self.assertEqual(wire.receive(time.monotonic() + 5), b'{"id":1}')
            process = wire.process
        self.assertIsNotNone(process.poll())
        self.assertLessEqual(wire.stderr_bytes, 65536)
        self.assertTrue(wire.stderr_truncated)
        self.assertTrue(wire.stderr_summary()['truncated'])

    def test_eof_oversize_and_timeout_are_explicit(self):
        """EOF, an oversized frame and silence each raise a ContractError."""
        scripts = ["pass", "print('x'*1048577,flush=True)", "import time; time.sleep(5)"]
        for script in scripts:
            with self.subTest(script=script), child(script) as wire:
                with self.assertRaises(ContractError):
                    wire.receive(time.monotonic() + .2)

    def test_frames_before_eof_are_delivered_then_eof_is_sticky(self):
        """Frames written before exit arrive in order; EOF then repeats without waiting."""
        # Binary writes: text-mode stdout would add carriage returns on Windows.
        with child("import sys; sys.stdout.buffer.write(b'{\"a\":1}\\n{\"b\":2}\\n')") as wire:
            deadline = time.monotonic() + 5
            self.assertEqual(wire.receive(deadline), b'{"a":1}')
            self.assertEqual(wire.receive(deadline), b'{"b":2}')
            for _ in range(2):
                started = time.monotonic()
                with self.assertRaises(ContractError) as caught:
                    wire.receive(time.monotonic() + 30)
                self.assertEqual(str(caught.exception), 'transport-eof')
                self.assertLess(time.monotonic() - started, 5)

    def test_input_closure_shutdown_is_recorded_once(self):
        """A child that exits on stdin EOF is recorded as 'input-closed' with its code."""
        wire = child("import sys; sys.stdin.read(); sys.exit(3)")
        record = wire.close()
        self.assertEqual((record['stage'], record['exitCode']), ('input-closed', 3))
        self.assertIs(wire.close(), record)
        self.assertIs(wire.shutdown, record)

    def test_already_exited_child_is_recorded_as_exited(self):
        """A child that ended on its own is not escalated."""
        wire = child("import sys; sys.exit(4)")
        wire.process.wait(timeout=10)
        self.assertEqual(wire.close()['stage'], 'exited')
        self.assertEqual(wire.shutdown['exitCode'], 4)

    def test_child_ignoring_eof_is_escalated_through_the_hook(self):
        """The terminate hook runs once when input closure does not stop the child."""
        calls = []
        holder = {}

        def terminate():
            """Stand in for a scoped `docker kill`: end exactly this child."""
            calls.append(True)
            holder['wire'].process.kill()

        holder['wire'] = child("import time; time.sleep(60)", terminate=terminate, grace=.5)
        record = holder['wire'].close()
        self.assertEqual(record['stage'], 'terminated')
        self.assertEqual(calls, [True])

    def test_child_ignoring_eof_is_terminated_without_a_hook(self):
        """Without a hook the child process itself is terminated."""
        record = child("import time; time.sleep(60)", grace=.5).close()
        self.assertEqual(record['stage'], 'terminated')
        self.assertIsNotNone(record['exitCode'])

    def test_final_kill_leaves_an_unrelated_child_alive(self):
        """An ineffective hook reaches bounded kill without touching another child."""
        with child("import time; time.sleep(60)", grace=.1) as other:
            wire = child("import time; time.sleep(60)",
                         terminate=lambda: None, grace=.1)
            record = wire.close()
            self.assertEqual(record['stage'], 'killed')
            self.assertIsNotNone(record['exitCode'])
            self.assertIsNone(other.process.poll())
            self.assertTrue(all(not reader.is_alive() for reader in wire.readers))

    def test_full_stdout_queue_does_not_block_shutdown(self):
        """Closing a child releases its reader even when no consumer drains stdout."""
        wire = child("import sys; sys.stdout.buffer.write(b'{}\\n' * 100); "
                     "sys.stdout.flush(); sys.stdin.read()")
        try:
            deadline = time.monotonic() + 5
            while not wire.messages.full() and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertTrue(wire.messages.full())
        finally:
            wire.close()
        self.assertEqual(wire.shutdown['exitCode'], 0)
        self.assertTrue(all(not reader.is_alive() for reader in wire.readers))

    def test_stderr_is_reduced_to_level_and_module(self):
        """Structured lines keep LEVEL and module only; message text never survives."""
        lines = ['2026-10-23T16:48:45.023243Z ERROR codex_app_server: token sk-secret /home/x',
                 '2026-10-23T16:48:45.1Z  WARN codex_core::config: secret bubblewrap',
                 ' Container secret-project Creating',
                 '2026-10-23T16:48:45Z ERROR Upper::Case: secret']
        script = ("import sys; sys.stderr.write(" + repr('\n'.join(lines) + '\ntrailing-secret')
                  + "); sys.stderr.flush(); sys.stdin.read()")
        with child(script) as wire:
            pass
        summary = wire.stderr_summary()
        self.assertEqual(summary['lines'], 5)
        self.assertEqual(summary['levels'], {'ERROR': 1, 'WARN': 1, 'unstructured': 3})
        self.assertEqual(summary['tail'][:2], ['ERROR codex_app_server', 'WARN codex_core::config'])
        self.assertNotIn('secret', json.dumps(summary))
        self.assertNotIn('bubblewrap', json.dumps(summary))

    def test_stderr_module_shape_cannot_smuggle_private_text(self):
        """A log-shaped injected module is replaced by a fixed diagnostic label."""
        for module in ('private_token', 'codex_core::private_token'):
            entry = transport.classify_stderr_line(
                f'2026-10-23T00:00:00Z WARN {module}: private message'.encode())
            self.assertEqual(entry, 'WARN unclassified')

    def test_overlong_stderr_line_is_bounded_and_still_classified(self):
        """A huge line is buffered only up to its prefix and still counts once."""
        # Binary writes: text-mode stderr would add carriage returns on Windows.
        script = ("import sys; sys.stderr.buffer.write(b'2026-10-23T00:00:00Z INFO a::b: '"
                  " + b'y'*40000 + b'\\n' + b'z'*20000); sys.stderr.flush(); sys.stdin.read()")
        with child(script) as wire:
            pass
        summary = wire.stderr_summary()
        self.assertEqual(summary['tail'], ['INFO unclassified', 'unstructured'])
        self.assertEqual(summary['bytes'], 60033)
        self.assertFalse(summary['truncated'])

    def test_tail_keeps_only_recent_entries(self):
        """The tail is bounded to STDERR_TAIL entries while lines are still counted."""
        script = ("import sys; sys.stderr.write('noise\\n' * 100); sys.stderr.flush();"
                  " sys.stdin.read()")
        with child(script) as wire:
            pass
        summary = wire.stderr_summary()
        self.assertEqual(summary['lines'], 100)
        self.assertEqual(len(summary['tail']), transport.STDERR_TAIL)


if __name__ == '__main__':
    unittest.main()
