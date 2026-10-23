"""Test bounded dispatch and retained scripted qualification, without live accounts.

Synthetic events exercise lost-event, correlation, failure and overflow hazards.
Docker tests opt in through RUNTIME_PROBE_DOCKER and run the managed image on
the fixed internal network; assertion/command failures fail qualification.
"""
from collections import deque
import copy
import json
import os
import subprocess
import time
import unittest
from unittest.mock import patch

from contract import ContractError, NEUTRAL_OUTPUT, VERSION
import concurrency


def event(method, thread, turn, **fields):
    """Build a synthetic identified event with supplied fields; return its RPC shape."""
    params = {'threadId': thread}
    if method in ('turn/started', 'turn/completed'):
        params['turn'] = {'id': turn, **fields}
    else:
        params.update({'turnId': turn, **fields})
    return {'method': method, 'params': params}


class EventSource:
    """Supply controlled event order; raise the safe deadline code when exhausted."""

    def __init__(self, events):
        """Copy supplied event references into a FIFO; perform no I/O."""
        self.events = deque(events)

    def next_notification(self, deadline):
        """Return one queued event by deadline, or raise timeout when none remains."""
        concurrency.remaining(deadline)
        if not self.events:
            raise ContractError('protocol-timeout')
        return self.events.popleft()


class RoutingTests(unittest.TestCase):
    """Exercise the single-reader inbox router using deterministic event order."""

    def test_interleaved_early_events_and_failure_keep_both_turns(self):
        """Retain another turn's start/error/terminal while validating successful output."""
        events = [event('turn/started', 'b', '2'), event('turn/started', 'a', '1'),
                  event('error', 'b', '2', error={'message': 'private-sentinel'}),
                  event('item/completed', 'a', '1', item={
                      'type': 'agentMessage', 'text': json.dumps(NEUTRAL_OUTPUT)}),
                  event('turn/completed', 'b', '2', status='failed'),
                  event('turn/completed', 'a', '1', status='completed')]
        router = concurrency.TurnRouter(EventSource(events))
        router.register('a', '1')
        router.register('b', '2')
        first = router.await_turn(('a', '1'), timeout=1)
        second = router.await_turn(('b', '2'), timeout=1)
        self.assertEqual((first['status'], second['status']), ('completed', 'failed'))
        self.assertTrue(first['started'] and second['started'])
        self.assertEqual(first['ignored'] + second['ignored'], 0)
        self.assertIsNone(second['text'])
        self.assertEqual(second['errorCode'], 'unclassified')
        self.assertNotIn('private-sentinel', json.dumps(second))
        self.assertEqual((router.routed, router.buffered), (3, 0))

    def test_wrong_thread_or_turn_never_reaches_another_inbox(self):
        """Fail unknown pairs even when one component matches an acknowledged turn."""
        for thread, turn in (('a', 'old'), ('foreign', '1'), ('b', '1')):
            router = concurrency.TurnRouter(EventSource([event('turn/completed', thread, turn)]))
            router.register('a', '1')
            router.register('b', '2')
            with self.subTest(thread=thread, turn=turn):
                with self.assertRaisesRegex(ContractError, '^unregistered-router-turn$'):
                    router.await_turn(('a', '1'), timeout=1)

    def test_registration_is_bounded_and_rejects_duplicates(self):
        """Reject invalid, duplicate or fourth identities before reading any event."""
        router = concurrency.TurnRouter(EventSource([]))
        with self.assertRaisesRegex(ContractError, '^invalid-router-identity$'):
            router.register('a', [])
        for turn in ('1', '2', '3'):
            router.register('a', turn)
        for turn in ('1', '4'):
            with self.assertRaisesRegex(ContractError, '^router-registration-limit$'):
                router.register('a', turn)

    def test_foreign_and_unscoped_floods_are_bounded(self):
        """Fail event floods while retaining at most the configured buffered count."""
        for sample in (event('turn/started', 'b', '2'), {'method': 'diagnostic', 'params': {}}):
            router = concurrency.TurnRouter(EventSource([sample] * 5))
            router.MAX_EVENTS = 4
            router.register('a', '1')
            router.register('b', '2')
            with self.assertRaisesRegex(ContractError, '^router-event-limit$'):
                router.await_turn(('a', '1'), timeout=1)
            self.assertLessEqual(router.buffered, 4)

    def test_buffered_events_cannot_bypass_deadline(self):
        """Leave already routed data intact after an expired local wait."""
        router = concurrency.TurnRouter(EventSource([event('turn/started', 'b', '2')]))
        router.register('a', '1')
        router.register('b', '2')
        with self.assertRaises(ContractError):
            router.next_for(('a', '1'), time.monotonic() + 1)
        with self.assertRaisesRegex(ContractError, '^transport-timeout$'):
            router.next_for(('b', '2'), time.monotonic() - 1)
        self.assertEqual(router.buffered, 1)

    def test_malformed_lifecycle_or_identity_fails_closed(self):
        """Reject missing lifecycle identity and non-string correlation components."""
        for malformed in ({'method': 'turn/completed', 'params': {'threadId': 'a'}},
                          event('turn/completed', ['a'], '1', status='completed')):
            router = concurrency.TurnRouter(EventSource([malformed]))
            router.register('a', '1')
            with self.assertRaises(ContractError):
                router.await_turn(('a', '1'), timeout=1)

    def test_interruption_requires_terminal_confirmation(self):
        """A routed start without a terminal event cannot release the active turn."""
        router = concurrency.TurnRouter(EventSource([event('turn/started', 'a', '1')]))
        router.register('a', '1')
        with self.assertRaisesRegex(ContractError, '^protocol-timeout$'):
            router.await_turn(('a', '1'), timeout=1)

    def test_cleanup_runs_when_fixture_start_fails(self):
        """Stop only the fixed project after startup failure; never delete volumes."""
        failed = subprocess.CalledProcessError(1, ['docker'])
        with patch.object(concurrency.probe, 'executable_path', return_value='docker'), \
                patch.object(subprocess, 'check_output', return_value=b''), \
                patch.object(concurrency.probe, '_check_version'), \
                patch.object(subprocess, 'run', side_effect=[failed, None]) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                concurrency.capture('docker')
        cleanup = run.call_args.args[0]
        self.assertEqual(cleanup[-3:], ['down', '--timeout', '10'])
        self.assertIn('ai-rpg-engine-runtime-concurrency', cleanup)
        self.assertNotIn('-v', cleanup)

    def test_existing_project_is_never_adopted_or_stopped(self):
        """Refuse existing fixture containers before version/start/cleanup mutations."""
        with patch.object(concurrency.probe, 'executable_path', return_value='docker'), \
                patch.object(subprocess, 'check_output', return_value=b'existing-container'), \
                patch.object(subprocess, 'run') as run:
            with self.assertRaisesRegex(ContractError, '^qualification-project-in-use$'):
                concurrency.capture('docker')
        run.assert_not_called()


def assert_evidence(case, report):
    """Assert retained or freshly captured evidence scope; fail on unproved live claims."""
    case.assertEqual(report['runtimeVersion'], VERSION)
    case.assertEqual(report['source'], 'account-free-packaged-scripted-upstream')
    case.assertEqual(report['profile'], 'managed-narration')
    case.assertIs(report['inferenceVerified'], False)
    case.assertIs(report['accountVolumeMounted'], False)
    case.assertEqual(report['decision'], concurrency.serialized_policy())
    case.assertEqual(report['requestCount'], 5)
    case.assertIs(report['toolsEmptyEveryRequest'], True)
    scripted = report['scripted']
    case.assertEqual(scripted['overlappingHeldRequests'], 2)
    case.assertIs(scripted['targetedCancellationPreservedOtherTurn'], True)
    case.assertIs(scripted['interleavedDispatchVerified'], True)
    case.assertGreater(scripted['eventsRoutedToOtherTurn'], 0)
    case.assertIs(scripted['serializedPriorityHandoffVerified'], True)
    case.assertEqual(scripted['heldStreamsClosed'], 3)
    case.assertEqual([t['status'] for t in scripted['terminalResults']],
                     ['interrupted', 'completed', 'interrupted', 'interrupted', 'completed'])
    for turn in scripted['terminalResults']:
        case.assertEqual(turn['ignored'], 0)
        case.assertIs(turn['started'], True)
        case.assertGreater(turn['events'], 0)
    case.assertEqual(len(scripted['cancellationElapsedMs']), 3)
    for elapsed in scripted['cancellationElapsedMs']:
        case.assertGreaterEqual(elapsed, 0)
        case.assertLess(elapsed, 180_000)
    case.assertEqual(report['bounds'], {
        'requestTimeoutSeconds': 60, 'turnTimeoutSeconds': 60,
        'upstreamWaitSeconds': 60, 'cancellationBudgetSeconds': 180, 'registeredTurns': 3,
        'bufferedEvents': 1024, 'liveRequests': 0})
    for sentinel in ('threadId', 'turnId', 'base64', 'credential', 'accountId', 'prompt'):
        case.assertNotIn(sentinel, json.dumps(report))


class RetainedEvidenceTests(unittest.TestCase):
    """Verify the neutral saved projection and selected installation limit offline."""

    def test_evidence_retains_serialization_without_live_claims(self):
        """Compare saved decisions and bounded actual observations against current policy."""
        report = json.loads(concurrency.EVIDENCE.read_text(encoding='utf-8'))
        assert_evidence(self, report)

    def test_evidence_assertions_reject_inferred_live_capacity(self):
        """Treat scripted overlap promoted to a live-capacity claim as a failing record."""
        report = json.loads(concurrency.EVIDENCE.read_text(encoding='utf-8'))
        changed = copy.deepcopy(report)
        changed['decision']['installationActiveTurnLimit'] = 2
        with self.assertRaises(AssertionError):
            assert_evidence(self, changed)


@unittest.skipUnless(os.environ.get('RUNTIME_PROBE_DOCKER'), 'set RUNTIME_PROBE_DOCKER')
class DockerConcurrencyTests(unittest.TestCase):
    """Qualify the real managed runtime on the internal fixture, with no account volume."""

    def test_dispatch_cancellation_and_serialized_priority(self):
        """Capture five scripted turns and assert scope, terminal routing and cleanup."""
        docker = os.environ['RUNTIME_PROBE_DOCKER']
        compose = [docker, 'compose', '-f', str(concurrency.HERE / 'compose.yaml')]
        subprocess.run([*compose, 'build', '--quiet', 'codex'], check=True,
                       capture_output=True, timeout=900)
        report = concurrency.capture(docker)
        assert_evidence(self, report)
        stopped = subprocess.check_output([docker, 'compose', '-p',
            'ai-rpg-engine-runtime-concurrency', '-f', str(concurrency.HERE / 'session.compose.yaml'),
            'ps', '--all', '--quiet'], timeout=60)
        self.assertEqual(stopped.strip(), b'')


if __name__ == '__main__':
    unittest.main()
