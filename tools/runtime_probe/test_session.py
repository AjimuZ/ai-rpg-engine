"""Thread and turn lifecycle checks (P01.2).

The unit checks drive session.py and the session/live profiles of probe.py
through the real transport.Rpc over a scripted wire, so buffering, ID
correlation, terminal statuses, interruption, and redaction are exercised
without a runtime. The pin checks keep session.compose.yaml and
ScriptedProvider.java consistent with ADR 0009 and contract.py.

The Docker checks run only when RUNTIME_PROBE_DOCKER holds the absolute path
of the Docker CLI. They run the scripted-provider fixture on an internal
network with no account, so nothing reaches an account or inference service.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import unittest
from unittest.mock import patch

from contract import ContractError, NEUTRAL_OUTPUT, OUTPUT_SCHEMA, VERSION
import probe
import session
import transport

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SESSION_COMPOSE = HERE / 'session.compose.yaml'
PACKAGED_COMPOSE = HERE / 'compose.yaml'
PROVIDER = HERE / 'ScriptedProvider.java'
ADR = ROOT / 'docs' / 'adr' / '0009-toolchain-baseline.md'
DOCKERFILE = ROOT / 'docker' / 'codex-runtime' / 'Dockerfile'
DOCKER = os.environ.get('RUNTIME_PROBE_DOCKER')
THREAD = 'thread-1'
TURN = 'turn-2'


class ScriptedWire:
    """Wire stand-in: returns scripted messages in order and records sends.

    Once the script is exhausted, receive raises 'transport-timeout' like a
    silent runtime would at its deadline.
    """

    def __init__(self, messages):
        """Copy supplied scripted messages and initialize sent-message capture; return a wire
        without I/O or runtime access.
        """
        self.messages = list(messages)
        self.sent = []

    def send(self, value):
        """Decode the supplied JSON bytes into the sent-message list; return None or propagate
        invalid test-data decoding failures.
        """
        self.sent.append(json.loads(value))

    def receive(self, _deadline):
        """Return the next scripted message as JSON bytes; ignore the test deadline and raise
        transport-timeout on exhaustion.
        """
        if not self.messages:
            raise ContractError('transport-timeout')
        return json.dumps(self.messages.pop(0)).encode()


def note(method, **params):
    """Return a notification dict from the supplied method/params without I/O or validation."""
    return {'method': method, 'params': params}


def turn_event(method, turn_id=TURN, thread_id=THREAD, status='inProgress', error=None):
    """Wrap the supplied IDs/status/error in a lifecycle dict; allow malformed test values."""
    return note(method, threadId=thread_id,
                turn={'id': turn_id, 'status': status, 'error': error, 'items': []})


def agent_message(text, turn_id=TURN, thread_id=THREAD):
    """Wrap text and IDs in an agent item notification; allow invalid text for negative tests."""
    return note('item/completed', threadId=thread_id, turnId=turn_id,
                item={'type': 'agentMessage', 'id': 'item-1', 'text': text})


def rpc_for(messages):
    """Return (Rpc, wire) for supplied messages without I/O; receive fails on exhaustion."""
    wire = ScriptedWire(messages)
    return transport.Rpc(wire, timeout=1), wire


class TurnTests(unittest.TestCase):
    """Exercise synthetic turn streams and RPC boundaries; unittest reports assertion failures
    when lifecycle invariants regress.
    """
    def test_interrupt_acknowledgment_without_terminal_event_times_out(self):
        """Given an interrupt acknowledgment alone, require terminal confirmation or timeout."""
        rpc, _ = rpc_for([turn_event('turn/started'), {'id': 1, 'result': {}}])
        with self.assertRaisesRegex(ContractError, '^transport-timeout$'):
            session.await_turn(rpc, THREAD, TURN, timeout=1,
                               on_started=lambda: session.interrupt_turn(rpc, THREAD, TURN))

    def test_unknown_error_variants_never_reach_diagnostics(self):
        """Given unknown error strings/keys, require fixed redacted codes or fail."""
        for info in ('private@example.com', {'secret-token': {'detail': 'private'}}):
            rpc, _ = rpc_for([turn_event('turn/completed', status='failed',
                                        error={'codexErrorInfo': info})])
            with self.subTest(info=info):
                result = session.await_turn(rpc, THREAD, TURN, timeout=1)
                self.assertEqual(result['errorCode'], 'unclassified')

    def test_completed_without_final_message_is_a_controlled_error(self):
        """Given a completed turn without output, require a safe protocol failure."""
        rpc, _ = rpc_for([turn_event('turn/completed', status='completed')])
        with self.assertRaisesRegex(ContractError, '^missing-agent-message$'):
            session.await_turn(rpc, THREAD, TURN, timeout=1)

    def test_buffered_events_cannot_outlive_the_deadline(self):
        """Given queued events and an expired deadline, require timeout or fail."""
        rpc, _ = rpc_for([turn_event('turn/started'), {'id': 1, 'result': {}}])
        rpc.request('x', {})
        with self.assertRaisesRegex(ContractError, '^transport-timeout$'):
            rpc.next_notification(time.monotonic() - 1)

    def test_every_turn_sends_explicit_settings_and_schema_only_when_given(self):
        """Use controlled fixture inputs to check that every turn sends explicit settings and
        schema only when given; return None or raise an assertion/protocol failure.
        """
        rpc, wire = rpc_for([{'id': 1, 'result': {'turn': {'id': 'a', 'status': 'inProgress'}}},
                             {'id': 2, 'result': {'turn': {'id': 'b', 'status': 'inProgress'}}}])
        self.assertEqual(session.start_turn(rpc, THREAD, text='x', model='m1', effort='low',
                                            output_schema=OUTPUT_SCHEMA), 'a')
        self.assertEqual(session.start_turn(rpc, THREAD, text='x', model='m2', effort='high'), 'b')
        first, second = (sent['params'] for sent in wire.sent)
        self.assertEqual((first['model'], first['effort']), ('m1', 'low'))
        self.assertEqual((second['model'], second['effort']), ('m2', 'high'))
        for params in (first, second):
            self.assertEqual(params['approvalPolicy'], 'never')
            self.assertEqual(params['sandboxPolicy'], {'type': 'readOnly', 'networkAccess': False})
        self.assertEqual(first['outputSchema'], OUTPUT_SCHEMA)
        self.assertNotIn('outputSchema', second)

    def test_early_events_are_kept_and_foreign_or_prior_turn_events_are_ignored(self):
        """Use controlled fixture inputs to check that early events are kept and foreign or
        prior turn events are ignored; return None or raise an assertion/protocol failure.
        """
        text = json.dumps(NEUTRAL_OUTPUT)
        rpc, _ = rpc_for([
            # Events of the new turn arriving before its turn/start response.
            turn_event('turn/started'),
            {'id': 1, 'result': {'turn': {'id': TURN, 'status': 'inProgress'}}},
            # A delayed event of the previous turn and one of another thread.
            agent_message('stale', turn_id='turn-1'),
            agent_message('foreign', thread_id='thread-9'),
            note('account/rateLimits/updated'),
            agent_message(text),
            turn_event('turn/completed', turn_id='turn-1', status='completed'),
            turn_event('turn/completed', status='completed'),
        ])
        turn_id = session.start_turn(rpc, THREAD, text='x', model='m', effort='low')
        result = session.await_turn(rpc, THREAD, turn_id, timeout=1)
        self.assertEqual(result['status'], 'completed')
        self.assertTrue(result['started'])
        self.assertEqual(result['text'], text)
        self.assertEqual((result['events'], result['ignored']), (3, 4))
        # Only the thread-9 message names another thread; the rate-limit
        # notification is account-level.
        self.assertEqual(result['foreign'], 1)

    def test_failed_turn_keeps_only_the_error_code(self):
        """Use controlled fixture inputs to check that failed turn keeps only the error code;
        return None or raise an assertion/protocol failure.
        """
        for info, code in (('other', 'other'), ({'httpConnectionFailed': {'httpStatusCode': 400}},
                                                'httpConnectionFailed'), (None, 'unclassified')):
            error = {'message': 'secret-token user@example.com', 'codexErrorInfo': info,
                     'additionalDetails': 'secret-token'}
            rpc, _ = rpc_for([
                turn_event('turn/started'),
                note('error', threadId=THREAD, turnId=TURN, error=error, willRetry=False),
                agent_message('partial'),
                turn_event('turn/completed', status='failed', error=error),
            ])
            with self.subTest(info=info):
                result = session.await_turn(rpc, THREAD, TURN, timeout=1)
                self.assertEqual((result['status'], result['errorCode']), ('failed', code))
                self.assertIsNone(result['text'])
                self.assertNotIn('secret-token', json.dumps(result))

    def test_interrupt_is_sent_after_start_and_terminal_status_is_awaited(self):
        """Use controlled fixture inputs to check that interrupt is sent after start and
        terminal status is awaited; return None or raise an assertion/protocol failure.
        """
        rpc, wire = rpc_for([
            turn_event('turn/started'),
            turn_event('turn/started'),
            # Response to turn/interrupt, then the confirmed terminal status.
            {'id': 1, 'result': {}},
            agent_message('partial'),
            turn_event('turn/completed', status='interrupted'),
        ])
        calls = []

        def interrupt():
            """Record this test callback and interrupt its exact scripted turn; return None or
            propagate unexpected protocol failures.
            """
            calls.append(True)
            session.interrupt_turn(rpc, THREAD, TURN)

        result = session.await_turn(rpc, THREAD, TURN, timeout=1, on_started=interrupt)
        self.assertEqual(calls, [True])
        self.assertEqual(wire.sent, [{'id': 1, 'method': 'turn/interrupt',
                                      'params': {'threadId': THREAD, 'turnId': TURN}}])
        self.assertEqual(result['status'], 'interrupted')
        self.assertIsNone(result['text'])

    def test_nonempty_interrupt_response_is_rejected(self):
        """Use controlled fixture inputs to check that nonempty interrupt response is rejected;
        return None or raise an assertion/protocol failure.
        """
        rpc, _ = rpc_for([{'id': 1, 'result': {'ok': True}}])
        with self.assertRaises(ContractError):
            session.interrupt_turn(rpc, THREAD, TURN)

    def test_missing_terminal_status_rerouting_and_malformed_events_fail_closed(self):
        """Use controlled fixture inputs to check that missing terminal status rerouting and
        malformed events fail closed; return None or raise an assertion/protocol failure.
        """
        cases = {
            'transport-timeout': [turn_event('turn/started'), agent_message('x')],
            'model-rerouted': [note('model/rerouted', threadId=THREAD, turnId=TURN,
                                    fromModel='m1', toModel='m2', reason='x')],
            'invalid-turn-status': [turn_event('turn/completed', status='inProgress')],
            'invalid-turn-event': [note('turn/completed', threadId=THREAD, turnId=TURN)],
            'invalid-agent-message': [agent_message(None)],
            'invalid-turn-item': [note('item/completed', threadId=THREAD, turnId=TURN, item=[])],
            # A host action must fail the turn as soon as it starts (ADR 0013).
            'unexpected-turn-item': [note('item/started', threadId=THREAD, turnId=TURN,
                                          item={'type': 'commandExecution', 'id': 'c1'})],
        }
        for code, messages in cases.items():
            rpc, _ = rpc_for(messages)
            with self.subTest(code=code), self.assertRaises(ContractError) as caught:
                session.await_turn(rpc, THREAD, TURN, timeout=1)
            self.assertEqual(str(caught.exception), code)

    def test_only_narration_item_types_are_accepted(self):
        """Given allowed and disallowed item types, require that only text items pass.

        Every non-narration type the pinned schema defines for host actions is
        rejected on completion as well as on start, and an unknown future type
        is rejected rather than ignored; allowed types complete normally.
        """
        for kind in ('commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall',
                     'webSearch', 'imageView', 'collabAgentToolCall', 'futureType'):
            for method in ('item/started', 'item/completed'):
                rpc, _ = rpc_for([note(method, threadId=THREAD, turnId=TURN,
                                       item={'type': kind, 'id': 'x'})])
                with self.subTest(kind=kind, method=method),                         self.assertRaises(ContractError) as caught:
                    session.await_turn(rpc, THREAD, TURN, timeout=1)
                self.assertEqual(str(caught.exception), 'unexpected-turn-item')
        allowed = [note('item/started', threadId=THREAD, turnId=TURN,
                        item={'type': kind, 'id': kind}) for kind in session.ALLOWED_TURN_ITEMS]
        rpc, _ = rpc_for(allowed + [agent_message('ok'),
                                   turn_event('turn/completed', status='completed')])
        self.assertEqual(session.await_turn(rpc, THREAD, TURN, timeout=1)['text'], 'ok')

    def test_event_count_per_turn_is_bounded(self):
        """Use controlled fixture inputs to check that event count per turn is bounded; return
        None or raise an assertion/protocol failure.
        """
        rpc, _ = rpc_for([note('noise', threadId=THREAD)] * 5)
        with patch.object(session, 'MAX_TURN_EVENTS', 4), self.assertRaises(ContractError) as caught:
            session.await_turn(rpc, THREAD, TURN, timeout=1)
        self.assertEqual(str(caught.exception), 'turn-event-limit')

    def test_pending_notification_overflow_is_explicit(self):
        """Given more queued events than the bound, require overflow rather than event loss."""
        rpc, _ = rpc_for([note('noise')] * 3 + [{'id': 1, 'result': {}}])
        with patch.object(rpc, 'MAX_PENDING', 2), \
                self.assertRaisesRegex(ContractError, '^notification-overflow$'):
            rpc.request('x', {})

    def test_privileged_request_during_turn_wait_is_denied(self):
        """Given an unsolicited tool request while waiting, require denial and normal completion."""
        rpc, wire = rpc_for([
            {'id': 'tool-1', 'method': 'item/tool/call', 'params': {'arguments': 'private'}},
            agent_message('final'), turn_event('turn/completed', status='completed')])
        self.assertEqual(session.await_turn(rpc, THREAD, TURN, timeout=1)['text'], 'final')
        self.assertEqual(wire.sent, [{'id': 'tool-1', 'error': {
            'code': -32601, 'message': 'Unsupported by protocol probe'}}])


class ThreadTests(unittest.TestCase):
    """Exercise synthetic start/resume responses; unittest reports assertion failures for
    accepted unsafe or inconsistent state.
    """
    def test_reported_policy_must_be_safe_on_start_and_resume(self):
        """Given unsafe effective policies, reject start/resume instead of trusting requests."""
        changes = ({'sandbox': {'type': 'dangerFullAccess'}},
                   {'sandbox': {'type': 'readOnly', 'networkAccess': True}},
                   {'sandbox': None}, {'approvalPolicy': 'on-request'})
        for changed in changes:
            for resume in (False, True):
                rpc, _ = rpc_for([{'id': 1, 'result': self.thread_response(**changed)},
                                  {'id': 2, 'result': {'data': [], 'nextCursor': None}}])
                with self.subTest(changed=changed, resume=resume), \
                        self.assertRaisesRegex(ContractError, '^thread-settings-mismatch$'):
                    if resume:
                        session.resume_thread(rpc, THREAD)
                    else:
                        session.start_thread(rpc, model='m1', cwd=probe.WORKSPACE)

    def thread_response(self, **changes):
        """Return a safe synthetic thread response with supplied field overrides; perform no
        I/O and leave malformed overrides for negative tests.
        """
        result = {'thread': {'id': THREAD, 'status': {'type': 'idle'}}, 'model': 'm1',
                  'modelProvider': 'fixture', 'cwd': probe.WORKSPACE, 'approvalPolicy': 'never',
                  'approvalsReviewer': 'user', 'sandbox': {'type': 'readOnly'},
                  'reasoningEffort': 'low'}
        result.update(changes)
        return result

    def test_start_validates_reported_settings(self):
        """Use controlled fixture inputs to check that start validates reported settings;
        return None or raise an assertion/protocol failure.
        """
        rpc, wire = rpc_for([{'id': 1, 'result': self.thread_response()}])
        started = session.start_thread(rpc, model='m1', cwd=probe.WORKSPACE)
        self.assertEqual(started['threadId'], THREAD)
        self.assertEqual(wire.sent[0]['params']['ephemeral'], False)
        for changes in ({'model': 'm2'}, {'cwd': '/elsewhere'}, {'approvalPolicy': 'onRequest'},
                        {'thread': {'id': THREAD, 'status': {'type': 'active'}}},
                        {'thread': {'id': '', 'status': {'type': 'idle'}}}):
            rpc, _ = rpc_for([{'id': 1, 'result': self.thread_response(**changes)}])
            with self.subTest(changes=changes), self.assertRaises(ContractError):
                session.start_thread(rpc, model='m1', cwd=probe.WORKSPACE)

    def test_resume_excludes_inline_turns_and_lists_them_oldest_first(self):
        """Use controlled fixture inputs to check that resume excludes inline turns and lists
        them oldest first; return None or raise an assertion/protocol failure.
        """
        turns = {'data': [{'id': 'a', 'status': 'completed'}, {'id': 'b', 'status': 'failed'}],
                 'nextCursor': None}
        rpc, wire = rpc_for([{'id': 1, 'result': self.thread_response(reasoningEffort=None)},
                             {'id': 2, 'result': turns}])
        resumed = session.resume_thread(rpc, THREAD)
        self.assertEqual(wire.sent[0]['params'], {'threadId': THREAD, 'excludeTurns': True})
        self.assertEqual(wire.sent[1]['params']['sortDirection'], 'asc')
        self.assertEqual(resumed, {'threadId': THREAD, 'model': 'm1', 'effort': None,
                                   'turns': [{'turnId': 'a', 'status': 'completed'},
                                             {'turnId': 'b', 'status': 'failed'}]})

    def test_resume_of_another_thread_or_an_incomplete_listing_fails(self):
        """Use controlled fixture inputs to check that resume of another thread or an
        incomplete listing fails; return None or raise an assertion/protocol failure.
        """
        other = self.thread_response(thread={'id': 'thread-9', 'status': {'type': 'idle'}})
        rpc, _ = rpc_for([{'id': 1, 'result': other}])
        with self.assertRaises(ContractError):
            session.resume_thread(rpc, THREAD)
        rpc, _ = rpc_for([{'id': 1, 'result': self.thread_response()},
                          {'id': 2, 'result': {'data': [], 'nextCursor': 'more'}}])
        with self.assertRaises(ContractError):
            session.resume_thread(rpc, THREAD)


def provider_line(scenario, model, effort, schema=True):
    """Encode supplied fixture settings into one request log line; propagate JSON errors."""
    body = {'model': model, 'reasoning': {'effort': effort}, 'input': [scenario]}
    if schema:
        body['text'] = {'format': {'type': 'json_schema', 'schema': OUTPUT_SCHEMA}}
    encoded = base64.b64encode(json.dumps(body).encode()).decode()
    return f'request /v1/responses {scenario} {encoded}'


CATALOG = [{'id': 'a', 'model': 'm1', 'efforts': ['low', 'medium'], 'defaultEffort': 'medium'},
           {'id': 'b', 'model': 'm2', 'efforts': ['low', 'high'], 'defaultEffort': 'high'}]


class ProfileHelperTests(unittest.TestCase):
    """Exercise synthetic upstream records and catalogs; unittest reports assertion failures
    when explicit settings or schemas drift.
    """
    def test_upstream_schema_is_checked_beyond_its_format_name(self):
        """Given a changed upstream schema, expose the mismatch even when format agrees."""
        body = {'model': 'm1', 'reasoning': {'effort': 'low'},
                'text': {'format': {'type': 'json_schema', 'schema': {'type': 'string'}}}}
        encoded = base64.b64encode(json.dumps(body).encode()).decode()
        result = probe.upstream_requests(f'request /v1/responses scenario-complete {encoded}')
        self.assertIs(result[0]['schemaMatches'], False)
        correct = probe.upstream_requests(provider_line('scenario-complete', 'm1', 'low'))
        self.assertIs(correct[0]['schemaMatches'], True)

    def test_upstream_log_parsing_keeps_only_settings(self):
        """Use controlled fixture inputs to check that upstream log parsing keeps only
        settings; return None or raise an assertion/protocol failure.
        """
        log = '\n'.join(['held-stream-closed', 'request /v1/models unhandled e30=',
                         provider_line('scenario-complete', 'm1', 'low'),
                         provider_line('scenario-fail', 'm2', 'high', schema=False)])
        self.assertEqual(probe.upstream_requests(log), [
            {'scenario': 'scenario-complete', 'model': 'm1', 'effort': 'low',
             'format': 'json_schema', 'schemaMatches': True},
            {'scenario': 'scenario-fail', 'model': 'm2', 'effort': 'high',
             'format': None, 'schemaMatches': False}])
        with self.assertRaises(ContractError):
            probe.upstream_requests('request /v1/responses scenario-complete !!!')

    def test_fixture_settings_differ_in_model_and_effort(self):
        """Use controlled fixture inputs to check that fixture settings differ in model and
        effort; return None or raise an assertion/protocol failure.
        """
        self.assertEqual(probe.pick_settings(CATALOG), (('m1', 'low'), ('m2', 'high')))
        with self.assertRaises(ContractError):
            probe.pick_settings([{'model': 'm1', 'efforts': ['low']}])

    def test_live_settings_must_come_from_the_catalog(self):
        """Use controlled fixture inputs to check that live settings must come from the
        catalog; return None or raise an assertion/protocol failure.
        """
        self.assertEqual(probe.choose_live_settings(CATALOG), ('m1', 'medium'))
        self.assertEqual(probe.choose_live_settings(CATALOG, 'm2', 'low'), ('m2', 'low'))
        for model, effort in (('m3', None), ('m1', 'high')):
            with self.subTest(model=model), self.assertRaises(ContractError):
                probe.choose_live_settings(CATALOG, model, effort)


class LiveGateTests(unittest.TestCase):
    """Exercise the opt-in profile with mocked process/account inputs; unittest reports
    assertion failures for unintended execution or mounts.
    """
    def test_no_thread_is_started_without_a_signed_in_account(self):
        """Use controlled fixture inputs to check that no thread is started without a signed in
        account; return None or raise an assertion/protocol failure.
        """
        found = {'runtimeVersion': VERSION, 'accountState': 'sign-in-required',
                 'inferenceVerified': False, 'catalog': CATALOG}
        with patch.object(probe, 'Stdio') as stdio, \
                patch.object(probe, 'discover', return_value=found), \
                patch.object(session, 'start_thread') as start:
            with self.assertRaises(ContractError) as caught:
                probe.run_live_task(lambda: ['runtime'], timeout=1)
        self.assertEqual(str(caught.exception), 'sign-in-required')
        start.assert_not_called()
        self.assertEqual(stdio.call_count, 1)

    def test_volume_name_is_validated_before_docker_runs(self):
        """Use controlled fixture inputs to check that volume name is validated before docker
        runs; return None or raise an assertion/protocol failure.
        """
        for name in ('../home', '-v', '', 'a' * 200, 'C:\\Users'):
            args = argparse.Namespace(docker=sys.executable, account_volume=name,
                                      image='x', model=None, effort=None, timeout=1)
            with self.subTest(name=name), patch.object(subprocess, 'run') as run, \
                    self.assertRaises(ContractError) as caught:
                probe.probe_live(args)
            self.assertEqual(str(caught.exception), 'invalid-account-volume')
            run.assert_not_called()

    def test_missing_volume_is_never_created(self):
        """Use controlled fixture inputs to check that missing volume is never created; return
        None or raise an assertion/protocol failure.
        """
        args = argparse.Namespace(docker=sys.executable, account_volume='probe-account',
                                  image='x', model=None, effort=None, timeout=1)
        failed = subprocess.CompletedProcess([], 1, b'', b'')
        with patch.object(subprocess, 'run', return_value=failed) as run, \
                self.assertRaises(ContractError) as caught:
            probe.probe_live(args)
        self.assertEqual(str(caught.exception), 'account-volume-missing')
        self.assertEqual(run.call_args.args[0][1:], ['volume', 'inspect', 'probe-account'])

    def test_live_container_keeps_isolation_but_mounts_the_account_volume(self):
        """Use controlled fixture inputs to check that live container keeps isolation but
        mounts the account volume; return None or raise an assertion/protocol failure.
        """
        args = argparse.Namespace(docker=sys.executable, account_volume='probe-account',
                                  image='img', model=None, effort=None, timeout=1)
        ok = subprocess.CompletedProcess([], 0, b'', b'')
        with patch.object(subprocess, 'run', return_value=ok), \
                patch.object(probe, '_check_version'), \
                patch.object(probe, 'run_live_task', return_value={}) as task:
            probe.probe_live(args)
        command = task.call_args.args[0]()
        self.assertNotIn('--network', command)
        self.assertIn('--read-only', command)
        self.assertEqual(command[command.index('--cap-drop') + 1], 'ALL')
        self.assertIn('probe-account:/var/lib/runtime/home', command)
        self.assertFalse(any(part.startswith('/var/lib/runtime/home:uid') for part in command))
        self.assertTrue(any(part.startswith('/var/lib/runtime/workspace:') for part in command))
        self.assertEqual(command[-len(probe._server_arguments()):], probe._server_arguments())


class FixturePinTests(unittest.TestCase):
    """Compare repository fixture sources with the pinned contract and images; unittest reports
    assertion failures on drift.
    """
    def test_java_neutral_output_equals_the_contract_envelope(self):
        """Use controlled fixture inputs to check that java neutral output equals the contract
        envelope; return None or raise an assertion/protocol failure.
        """
        source = PROVIDER.read_text(encoding='utf-8')
        declaration = re.search(r'NEUTRAL_OUTPUT = (.*?);', source, re.S).group(1)
        literal = ''.join(json.loads(f'"{part}"') for part in
                          re.findall(r'"((?:[^"\\]|\\.)*)"', declaration))
        self.assertEqual(json.loads(literal), NEUTRAL_OUTPUT)

    def test_fixture_images_match_the_pinned_relay_image_in_adr(self):
        """Use controlled fixture inputs to check that fixture images match the pinned relay
        image in adr; return None or raise an assertion/protocol failure.
        """
        adr = ADR.read_text(encoding='utf-8')
        relay = re.findall(r'^\s+image: (\S+@\S+)', PACKAGED_COMPOSE.read_text(encoding='utf-8'),
                           re.M)
        images = re.findall(r'^\s+image: (\S+)', SESSION_COMPOSE.read_text(encoding='utf-8'), re.M)
        self.assertEqual(images, [relay[0], 'ai-rpg-engine-runtime-probe/codex:' + VERSION,
                                  relay[0]])
        self.assertIn(relay[0], adr)

    def test_fixture_socket_name_and_internal_network(self):
        """Read fixture sources and assert that hashed socket paths, internal networking and
        absence of account configuration agree; fail on drift without launching containers.
        """
        text = SESSION_COMPOSE.read_text(encoding='utf-8')
        listen = re.search(r'"unix://(/[^"]+)"', DOCKERFILE.read_text(encoding='utf-8')).group(1)
        self.assertEqual(text.count(hashlib.sha256(listen.encode()).hexdigest()), 2)
        self.assertIn('unix://' + listen, text)
        self.assertRegex(text, r'\n  fixture:\n    internal: true\n')
        # The fixture never receives credentials or an account volume.
        self.assertNotIn('/var/lib/runtime/home\n', text)
        self.assertNotRegex(text, r'(?i)api[_-]?key|token|auth')


@unittest.skipUnless(DOCKER, 'set RUNTIME_PROBE_DOCKER to the Docker CLI path')
class DockerSessionTests(unittest.TestCase):
    """Exercise the real runtime on an internal fixture network; fail unittest on protocol drift.

    Inputs are pinned repository fixtures without accounts; results are assertions,
    with command failures/timeouts propagated rather than treated as successful evidence.
    """

    @classmethod
    def setUpClass(cls):
        """Build the pinned runtime through the fixture Compose file; return None or fail setup
        on command failure or timeout.
        """
        subprocess.run([DOCKER, 'compose', '--file', str(PACKAGED_COMPOSE), 'build', '--quiet',
                        'codex'], check=True, capture_output=True, timeout=900)

    def test_session_fixture_proves_the_lifecycle(self):
        """Use controlled fixture inputs to check that session fixture proves the lifecycle;
        return None or raise an assertion/protocol failure.
        """
        report = probe.probe_session(argparse.Namespace(
            docker=DOCKER, compose_file=str(SESSION_COMPOSE), timeout=60.0))
        self.assertEqual(report['runtimeVersion'], VERSION)
        self.assertIs(report['inferenceVerified'], False)
        self.assertEqual([turn['status'] for turn in report['turns']],
                         ['completed', 'completed', 'interrupted', 'completed',
                          'interrupted', 'failed', 'completed'])
        self.assertEqual([turn['thread'] for turn in report['turns']],
                         ['T1', 'T1', 'T2', 'T1', 'T1', 'T1', 'T1'])
        self.assertGreater(report['turns'][3]['foreign'], 0)
        self.assertEqual(report['turns'][5]['errorCode'], 'other')
        self.assertEqual(report['resumedThreads'], 2)
        self.assertEqual(report['upstreamRequests'], 7)
        self.assertTrue(report['upstreamMatchesRequests'])
        self.assertEqual(report['heldStreamsClosed'], 2)
        done = subprocess.run([DOCKER, 'compose', '--file', str(SESSION_COMPOSE), 'ps',
                               '--all', '--quiet'], capture_output=True, timeout=60)
        # An empty stdout is insufficient if Docker itself failed to inspect services.
        self.assertEqual(done.returncode, 0, 'fixture shutdown inspection must succeed')
        self.assertEqual(done.stdout.strip(), b'', 'the fixture must be stopped afterwards')

    def test_scripted_provider_compiles_without_warnings_on_pinned_jdk(self):
        """Use controlled fixture inputs to check that scripted provider compiles without
        warnings on pinned jdk; return None or raise an assertion/protocol failure.
        """
        image = re.search(r'^\s+image: (eclipse-temurin\S+)',
                          SESSION_COMPOSE.read_text(encoding='utf-8'), re.M).group(1)
        done = subprocess.run(
            [DOCKER, 'run', '--rm', '--network', 'none', '--read-only', '--tmpfs', '/tmp',
             '--user', '10001:10001', '--env', 'HOME=/tmp',
             '--volume', f'{HERE}:/src:ro', image,
             'javac', '-Xlint:all', '-Werror', '-d', '/tmp/out', '/src/ScriptedProvider.java'],
            capture_output=True, timeout=600)
        self.assertEqual(done.returncode, 0, done.stderr.decode(errors='replace')[-2000:])


if __name__ == '__main__':
    unittest.main()
