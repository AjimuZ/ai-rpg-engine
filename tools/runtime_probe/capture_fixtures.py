"""Capture only neutral account-free RPC projections; fail without writing on probe failure."""
import argparse
import os
import copy
import json
from pathlib import Path
import shutil
import probe
from contract import VERSION, validate_output
from transport import Rpc

requests, events, identities = [], [], {}
original_request, original_event = Rpc.request, Rpc.next_notification

def alias(value):
    """Take an observed identifier and return its stable alias; unhashable inputs fail.

    Aliasing preserves correlation without retaining runtime IDs or opaque cursors.
    """
    if value not in identities:
        identities[value] = f'neutral-id-{len(identities) + 1}'
    return identities[value]

def turn(value):
    """Take a runtime turn dict and return only aliased ID/status; missing fields fail.

    History and arbitrary error text must never enter retained protocol evidence.
    """
    return {'id': alias(value['id']), 'status': value['status']}

def request(self, method, params):
    """Call real RPC with method/params and return its unmodified result.

    Append only a reviewed projection; RPC failures, unknown methods and unsafe
    account state raise before evidence is written. Inputs are fixed by the
    internal scripted fixture, never a live account or arbitrary prompt.
    """
    result = original_request(self, method, params)
    p = copy.deepcopy(params)
    for key in ('threadId', 'turnId'):
        if key in p:
            p[key] = alias(p[key])
    if method == 'initialize':
        r = {'userAgent': 'neutral-runtime/0.164.0'}
    elif method == 'account/read':
        assert result['account'] is None
        r = {'account': None, 'requiresOpenaiAuth': result['requiresOpenaiAuth']}
    elif method == 'model/list':
        fields = ('id', 'model', 'defaultReasoningEffort', 'supportedReasoningEfforts')
        r = {'data': [{k: m[k] for k in fields} for m in result['data']],
             'nextCursor': result['nextCursor']}
        for m in r['data']:
            m['supportedReasoningEfforts'] = [{'reasoningEffort': e['reasoningEffort']}
                                            for e in m['supportedReasoningEfforts']]
        if p.get('cursor') is not None:
            p['cursor'] = alias(p['cursor'])
        if r['nextCursor'] is not None:
            r['nextCursor'] = alias(r['nextCursor'])
    elif method in ('thread/start', 'thread/resume'):
        r = {k: result[k] for k in ('model', 'modelProvider', 'cwd', 'approvalPolicy', 'sandbox')}
        r['sandbox'] = {k: v for k, v in r['sandbox'].items() if k in ('type', 'networkAccess')}
        r['thread'] = {'id': alias(result['thread']['id']), 'status': {'type': result['thread']['status']['type']}}
        if method == 'thread/resume':
            r['reasoningEffort'] = result['reasoningEffort']
    elif method == 'thread/turns/list':
        assert result['nextCursor'] is None
        r = {'data': [turn(t) for t in result['data']], 'nextCursor': None}
    elif method == 'turn/start':
        r = {'turn': turn(result['turn'])}
    elif method == 'turn/interrupt':
        assert result == {}
        r = result
    else:
        raise AssertionError(method)
    requests.append({'method': method, 'params': p, 'result': r})
    return result

def event(self, deadline):
    """Read one real event by deadline and return it unchanged, retaining a projection.

    Only lifecycle, fixed-code errors and validated neutral final text survive.
    Transport, missing-field and validation failures propagate without writing.
    """
    value = original_event(self, deadline)
    method, p = value['method'], value['params']
    if method in ('turn/started', 'turn/completed', 'error', 'item/completed'):
        if method == 'item/completed' and p.get('item', {}).get('type') != 'agentMessage':
            return value
        r = {'threadId': alias(p['threadId'])}
        if method in ('turn/started', 'turn/completed'):
            r['turn'] = turn(p['turn'])
        else:
            r['turnId'] = alias(p['turnId'])
        if method == 'item/completed':
            validate_output(p['item']['text'], status='completed')
            r['item'] = {'id': alias(p['item']['id']), 'type': 'agentMessage', 'text': p['item']['text']}
        if method == 'error':
            r.update({'willRetry': p['willRetry'], 'error': {
                'message': 'Neutral error text omitted',
                'codexErrorInfo': probe.session._error_code(p['error'])}})
        events.append({'method': method, 'params': r})
    return value

def main():
    """Capture the fixed internal session from the repository root and write its projection.

    Takes no inputs and returns None. Probe/validation/I/O failures propagate;
    services are stopped by probe_session even on failure. No account is mounted.
    """
    os.chdir(Path(__file__).resolve().parents[2])
    Rpc.request, Rpc.next_notification = request, event
    report = probe.probe_session(argparse.Namespace(docker=shutil.which('docker'), timeout=60,
                                compose_file='tools/runtime_probe/session.compose.yaml'))
    assert report['upstreamMatchesRequests'] and report['resumedThreads'] == 2
    assert report['accountState'] == 'unsupported-auth' and not report['inferenceVerified']
    fixture = {'fixtureVersion': 1, 'runtimeVersion': VERSION, 'source': 'account-free-packaged-session',
               'inferenceVerified': False, 'requests': requests, 'notifications': events}
    path = Path('tools/runtime_probe/fixtures/0.164.0/neutral-session.json')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(fixture, indent=2) + '\n', encoding='utf-8')
    print(f'Captured {len(requests)} exchanges and {len(events)} selected notifications')


if __name__ == '__main__':
    main()
