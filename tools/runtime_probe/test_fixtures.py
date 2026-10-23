"""Replay retained neutral RPC projections through the current contract validators.

Inputs are repository fixtures only. Tests return unittest results and fail on
pin/schema/settings/status drift; they need no process, account or network.
"""
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import capture_fixtures

import contract
import session

FIXTURE = Path(__file__).resolve().parent / 'fixtures' / contract.VERSION / 'neutral-session.json'


class ReplayRpc:
    """Supply selected captured exchanges/events to validators; mismatches raise AssertionError."""

    def __init__(self, exchanges=(), events=()):
        """Take ordered fixture records and events; initialize iterators without I/O or failure."""
        self.exchanges = iter(exchanges)
        self.events = iter(events)
        self.initialized = False

    def request(self, method, params):
        """Return the next captured result for exact method/params; fail on drift or exhaustion."""
        record = next(self.exchanges)
        assert (method, params) == (record['method'], record['params'])
        return copy.deepcopy(record['result'])

    def notify(self, method, params):
        """Validate the omitted initialized notification; return None or fail on other notifications."""
        assert (method, params) == ('initialized', {})
        self.initialized = True

    def next_notification(self, deadline):
        """Return the next selected event; deadline is unused offline and exhaustion fails the test."""
        return copy.deepcopy(next(self.events))


class RetainedFixtureTests(unittest.TestCase):
    """Check version-bound discovery, lifecycle and output semantics in the saved projection."""

    def setUp(self):
        """Load strict UTF-8 fixture JSON for each test; parsing/I/O failures fail the test."""
        self.fixture = contract.parse_json(FIXTURE.read_bytes())
        self.requests = self.fixture['requests']
        self.events = self.fixture['notifications']

    def test_pin_and_provenance(self):
        """Assert retained metadata and coverage counts; fail if the expected baseline changes."""
        self.assertEqual(self.fixture['runtimeVersion'], contract.VERSION)
        self.assertEqual(self.fixture['fixtureVersion'], 1)
        self.assertEqual(self.fixture['source'], 'account-free-packaged-session')
        self.assertIs(self.fixture['inferenceVerified'], False)
        self.assertEqual(len(self.requests), 27)
        self.assertEqual(len(self.events), 19)

    def test_both_connection_handshakes_and_catalog_pagination(self):
        """Replay discovery on both connections; fail on account, catalog or initialization drift."""
        starts = [i for i, r in enumerate(self.requests) if r['method'] == 'initialize']
        self.assertEqual(len(starts), 2)
        for index in starts:
            rpc = ReplayRpc(self.requests[index:])
            found = contract.discover(rpc)
            self.assertTrue(rpc.initialized)
            self.assertEqual(found['accountState'], 'unsupported-auth')
            self.assertFalse(found['inferenceVerified'])
            self.assertGreater(len(found['catalog']), 2)

    def test_thread_creation_resume_and_history(self):
        """Replay observed thread start/resume/history calls; fail on policy or history drift."""
        for index, record in enumerate(self.requests):
            rpc = ReplayRpc(self.requests[index:])
            params, method = record['params'], record['method']
            if method == 'thread/start':
                result = session.start_thread(rpc, model=params['model'], cwd=params['cwd'])
                self.assertEqual(result['threadId'], record['result']['thread']['id'])
            elif method == 'thread/resume':
                result = session.resume_thread(rpc, params['threadId'])
                self.assertTrue(result['turns'])
                self.assertIn(result['effort'], ('low', 'medium', 'high', 'minimal', 'xhigh'))

    def test_every_turn_has_explicit_settings_and_exact_output_schema(self):
        """Replay turn starts and interrupts; fail if settings/schema drift or interruption lacks a terminal event."""
        settings = set()
        for record in self.requests:
            params, method = record['params'], record['method']
            if method == 'turn/start':
                self.assertEqual(params['outputSchema'], contract.OUTPUT_SCHEMA)
                self.assertIn(session.NEUTRAL_PROMPT, params['input'][0]['text'])
                settings.add((params['model'], params['effort']))
                result = session.start_turn(ReplayRpc([record]), params['threadId'],
                    text=params['input'][0]['text'], model=params['model'],
                    effort=params['effort'], output_schema=contract.OUTPUT_SCHEMA)
                self.assertEqual(result, record['result']['turn']['id'])
            elif method == 'turn/interrupt':
                session.interrupt_turn(ReplayRpc([record]), params['threadId'], params['turnId'])
                self.assertTrue(any(e['method'] == 'turn/completed'
                    and e['params']['threadId'] == params['threadId']
                    and e['params']['turn']['id'] == params['turnId']
                    and e['params']['turn']['status'] == 'interrupted' for e in self.events))
        self.assertEqual(len(settings), 2)

    def test_terminal_events_gate_neutral_output_and_ignore_foreign_turns(self):
        """Replay all seven terminal sequences with a foreign event; fail if output or correlation is unsafe."""
        statuses = []
        for record in self.requests:
            if record['method'] != 'turn/start':
                continue
            thread_id = record['params']['threadId']
            turn_id = record['result']['turn']['id']
            events = [e for e in self.events if e['params']['threadId'] == thread_id
                and e['params'].get('turnId', e['params'].get('turn', {}).get('id')) == turn_id]
            foreign = {'method': 'turn/completed', 'params': {'threadId': 'foreign-thread',
                       'turn': {'id': turn_id, 'status': 'completed'}}}
            result = session.await_turn(ReplayRpc(events=[foreign, *events]),
                                        thread_id, turn_id, timeout=1)
            self.assertEqual(result['foreign'], 1)
            statuses.append(result['status'])
            if result['status'] == 'completed':
                self.assertEqual(contract.validate_output(result['text'], status=result['status']),
                                 contract.NEUTRAL_OUTPUT)
            else:
                self.assertIsNone(result['text'])
        self.assertEqual(statuses.count('completed'), 4)
        self.assertEqual(statuses.count('interrupted'), 2)
        self.assertEqual(statuses.count('failed'), 1)

    def test_capture_omits_unreviewed_account_and_initialize_metadata(self):
        """Feed private-shaped metadata to projection functions; fail if any unreviewed value survives."""
        for method, result, expected in (
            ('initialize', {'userAgent': 'private-host-path', 'extra': 'private'},
             {'userAgent': 'neutral-runtime/0.164.0'}),
            ('account/read', {'account': None, 'requiresOpenaiAuth': False,
                              'workspaceRouting': 'private'},
             {'account': None, 'requiresOpenaiAuth': False}),
        ):
            with patch.object(capture_fixtures, 'original_request', return_value=result), \
                    patch.object(capture_fixtures, 'requests', []):
                self.assertIs(capture_fixtures.request(None, method, {}), result)
                self.assertEqual(capture_fixtures.requests[0]['result'], expected)
        with patch.object(capture_fixtures, 'original_request',
                          return_value={'account': {'email': 'private'}}), \
                patch.object(capture_fixtures, 'requests', []):
            with self.assertRaises(AssertionError):
                capture_fixtures.request(None, 'account/read', {})
            self.assertEqual(capture_fixtures.requests, [])

    def test_capture_rejects_non_neutral_final_text(self):
        """Pass an arbitrary final message and require rejection before retention; leave no saved data."""
        event = {'method': 'item/completed', 'params': {'threadId': 't', 'turnId': 'v',
                 'item': {'id': 'i', 'type': 'agentMessage', 'text': 'private'}}}
        with patch.object(capture_fixtures, 'original_event', return_value=event), \
                patch.object(capture_fixtures, 'events', []), \
                patch.object(capture_fixtures, 'identities', {}):
            with self.assertRaises(contract.ContractError):
                capture_fixtures.event(None, 1)
            self.assertEqual(capture_fixtures.events, [])

    def test_changed_neutral_envelope_is_rejected(self):
        """Mutate a retained final envelope and require failure; no altered fixture is written."""
        message = next(e['params']['item']['text'] for e in self.events if e['method'] == 'item/completed')
        value = json.loads(message)
        value['baseRevision'] += 1
        with self.assertRaisesRegex(contract.ContractError, 'invalid-neutral-output'):
            contract.validate_output(json.dumps(value), status='completed')


if __name__ == '__main__':
    unittest.main()
