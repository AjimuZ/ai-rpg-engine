"""Thread and turn lifecycle client for the P01.2 session proof.

This module is the reference for how the engine adapter must drive a turn
(docs/06 sections 4 and 5), written as small sequential functions on top of
transport.Rpc:

- ``start_thread`` and ``resume_thread`` create or reattach a thread and
  validate the identifiers and settings the runtime reports;
- ``start_turn`` always sends the model, reasoning effort, approval policy,
  sandbox policy, and output schema explicitly. The runtime treats per-turn
  model and effort as sticky for later turns, and a turn that omits them
  silently inherits the previous turn's values, so "explicit" means "on every
  turn", never "once per thread";
- ``await_turn`` correlates notifications by thread *and* turn ID, keeps
  notifications that arrived before the turn ID was known, ignores foreign
  and delayed prior-turn events, and returns only on a confirmed terminal
  status of the requested turn;
- ``interrupt_turn`` interrupts an in-progress turn by its exact ID.

Every result holds identifiers, statuses, counters, and fixed error codes
only. Runtime error messages and item contents other than the final agent
message are never copied, because a real provider's text can contain
account details or task context.
"""
import time

from contract import ContractError, _text

# Terminal turn statuses defined by the pinned v2 protocol schema.
TERMINAL_STATUSES = ('completed', 'interrupted', 'failed')
# Recognized variants from 0.164.0's generated ErrorNotification schema.
# Unknown strings/objects are legal there for forward compatibility; copying
# them verbatim would defeat diagnostic redaction.
ERROR_CODES = frozenset((
    'contextWindowExceeded', 'sessionBudgetExceeded', 'usageLimitExceeded',
    'rateLimitExceeded', 'flexUnavailable', 'serverOverloaded', 'cyberPolicy',
    'misalignmentPolicyViolation', 'tooManyDenials', 'internalServerError',
    'unauthorized', 'badRequest', 'threadRollbackFailed', 'sandboxError', 'other',
    'httpConnectionFailed', 'responseStreamConnectionFailed',
    'responseStreamDisconnected', 'responseTooManyFailedAttempts', 'activeTurnNotSteerable',
))
# Notifications read for one turn before giving up; a turn of the neutral
# task produces about a dozen.
MAX_TURN_EVENTS = 4096
# The final agent message is bounded like the output contract (4 KiB) with
# headroom, so an oversized message is rejected before validation.
MAX_MESSAGE_TEXT = 65536
# The neutral task's only user text. It carries no game, account, or local
# data, and it contains no fixture scenario token; the session fixture
# appends one token per turn to select the scripted upstream behavior.
NEUTRAL_PROMPT = ('Protocol compatibility check. Respond only with the JSON object '
                  'required by the output schema; its body text is: The marker is blue.')


def _identifier(value, code):
    """Return a non-empty bounded string identifier or raise ``code``."""
    if not isinstance(value, str) or not value or len(value) > 128:
        raise ContractError(code)
    return value


def _error_code(error):
    """Reduce a runtime TurnError to a fixed, non-sensitive code.

    ``codexErrorInfo`` is either a bare enum string or a single-key object
    such as {"httpConnectionFailed": {...}}; only the enum name is kept. The
    free-text message is deliberately discarded.
    """
    if not isinstance(error, dict):
        return None
    info = error.get('codexErrorInfo')
    if isinstance(info, dict) and len(info) == 1:
        info = next(iter(info))
    return info if isinstance(info, str) and info in ERROR_CODES else 'unclassified'


def _validate_policy(result):
    """Check a thread result's effective policy; raise on missing/unsafe fields.

    Input is the start/resume result dict; success returns None. A requested
    policy alone is insufficient evidence that the runtime applied it.
    """
    sandbox = result.get('sandbox')
    if (result.get('approvalPolicy') != 'never' or not isinstance(sandbox, dict)
            or sandbox.get('type') != 'readOnly'
            or sandbox.get('networkAccess', False) is not False):
        raise ContractError('thread-settings-mismatch')


def start_thread(rpc, *, model, cwd):
    """Create a persistent thread and return a summary dict.

    Inputs: an Rpc after initialize, the catalog model slug for the thread
    default, and the runtime-side working directory.
    Output: {'threadId', 'model', 'modelProvider', 'status'}.
    Raises ContractError when the response lacks a valid thread ID, reports
    a different model or working directory, or is not idle.
    """
    result = rpc.request('thread/start', {
        'model': model, 'cwd': cwd, 'approvalPolicy': 'never',
        'sandbox': 'read-only', 'ephemeral': False})
    if not isinstance(result, dict) or not isinstance(result.get('thread'), dict):
        raise ContractError('invalid-thread-start')
    thread = result['thread']
    thread_id = _identifier(thread.get('id'), 'invalid-thread-id')
    if result.get('model') != model or result.get('cwd') != cwd:
        raise ContractError('thread-settings-mismatch')
    _validate_policy(result)
    status = thread.get('status')
    if not isinstance(status, dict) or status.get('type') != 'idle':
        raise ContractError('thread-not-idle')
    return {'threadId': thread_id, 'model': model,
            'modelProvider': _text(result.get('modelProvider'), 64), 'status': 'idle'}


def list_turns(rpc, thread_id, *, limit=32):
    """Return [{'turnId', 'status'}] for a thread, oldest first.

    The runtime pages turns newest first by default; one bounded page is
    enough for the probe, and a further page is reported as an error rather
    than silently ignored.
    """
    result = rpc.request('thread/turns/list', {
        'threadId': thread_id, 'limit': limit, 'sortDirection': 'asc'})
    if not isinstance(result, dict) or not isinstance(result.get('data'), list):
        raise ContractError('invalid-turn-list')
    if result.get('nextCursor') is not None:
        raise ContractError('turn-list-incomplete')
    turns = []
    for turn in result['data']:
        if not isinstance(turn, dict):
            raise ContractError('invalid-turn-list')
        status = turn.get('status')
        if status not in TERMINAL_STATUSES + ('inProgress',):
            raise ContractError('invalid-turn-status')
        turns.append({'turnId': _identifier(turn.get('id'), 'invalid-turn-id'),
                      'status': status})
    return turns


def resume_thread(rpc, thread_id):
    """Reattach an existing thread on this connection and return its state.

    ``excludeTurns`` follows the runtime's guidance for paginated threads:
    metadata comes from thread/resume, history from thread/turns/list.
    Output: {'threadId', 'model', 'effort', 'turns'}, where model and effort
    are the thread's current sticky settings; effort is None when the runtime
    reports none (the schema makes it nullable).
    Raises ContractError when the runtime returns a different thread.
    """
    result = rpc.request('thread/resume', {'threadId': thread_id, 'excludeTurns': True})
    if not isinstance(result, dict) or not isinstance(result.get('thread'), dict):
        raise ContractError('invalid-thread-resume')
    if result['thread'].get('id') != thread_id:
        raise ContractError('resumed-thread-mismatch')
    _validate_policy(result)
    effort = result.get('reasoningEffort')
    return {'threadId': thread_id, 'model': _text(result.get('model'), 128),
            'effort': None if effort is None else _text(effort, 32),
            'turns': list_turns(rpc, thread_id)}


def start_turn(rpc, thread_id, *, text, model, effort, output_schema=None):
    """Start one turn with every setting explicit and return its turn ID.

    ``output_schema`` applies to this turn only (the runtime does not carry
    it to later turns), so callers pass it whenever they expect structured
    output. Approval stays 'never' and the sandbox read-only: the neutral task
    needs no tools, and any approval request would be denied anyway.
    Raises ContractError when the response has no valid in-progress turn.
    """
    params = {
        'threadId': thread_id,
        'input': [{'type': 'text', 'text': text}],
        'model': model, 'effort': effort,
        'approvalPolicy': 'never',
        'sandboxPolicy': {'type': 'readOnly', 'networkAccess': False},
    }
    if output_schema is not None:
        params['outputSchema'] = output_schema
    result = rpc.request('turn/start', params)
    if not isinstance(result, dict) or not isinstance(result.get('turn'), dict):
        raise ContractError('invalid-turn-start')
    turn = result['turn']
    if turn.get('status') not in ('inProgress',) + TERMINAL_STATUSES:
        raise ContractError('invalid-turn-status')
    return _identifier(turn.get('id'), 'invalid-turn-id')


def interrupt_turn(rpc, thread_id, turn_id):
    """Request interruption of exactly one turn; the result is an empty object.

    The terminal 'interrupted' status still arrives as turn/completed and must
    be awaited with await_turn; the interrupt response alone proves nothing.
    """
    result = rpc.request('turn/interrupt', {'threadId': thread_id, 'turnId': turn_id})
    if result != {}:
        raise ContractError('invalid-turn-interrupt')


def await_turn(rpc, thread_id, turn_id, *, timeout, on_started=None):
    """Wait for the terminal status of one turn and return its summary.

    Correlation rules (docs/06 section 5):
    - only notifications whose threadId and turn ID both match are applied;
      everything else, including late events of an earlier turn on the same
      thread, is counted in 'ignored' and otherwise discarded. The subset
      that names another thread is also counted in 'foreign', so evidence
      can show that a concurrent turn's events were actually interleaved;
    - notifications buffered by Rpc before the turn ID was known are read
      first, so early events are never lost;
    - 'model/rerouted' for this turn fails closed: the effective model would
      differ from the explicit request;
    - the agent message is taken from item/completed, never from deltas, and
      is returned only when the terminal status is 'completed'.

    ``on_started`` is called once with no arguments after the matching
    turn/started notification; the probe uses it to interrupt a held turn.
    Output: {'turnId', 'status', 'started', 'errorCode', 'willRetry', 'text',
    'ignored', 'foreign', 'events'}.
    Raises ContractError on timeout, event-limit overflow, malformed
    correlated events, or a rerouted model.
    """
    deadline = time.monotonic() + timeout
    summary = {'turnId': turn_id, 'status': None, 'started': False, 'errorCode': None,
               'willRetry': False, 'text': None, 'ignored': 0, 'foreign': 0, 'events': 0}
    message = None
    for _ in range(MAX_TURN_EVENTS):
        event = rpc.next_notification(deadline)
        params = event['params']
        method = event['method']
        event_thread = params.get('threadId')
        if event_thread != thread_id:
            summary['ignored'] += 1
            # Account-level events carry no thread ID; only events naming a
            # different thread count as foreign.
            if isinstance(event_thread, str):
                summary['foreign'] += 1
            continue
        # Turn lifecycle events carry the turn object; others carry turnId.
        turn = params.get('turn') if method in ('turn/started', 'turn/completed') else None
        event_turn = turn.get('id') if isinstance(turn, dict) else params.get('turnId')
        if event_turn != turn_id:
            summary['ignored'] += 1
            continue
        # A malformed lifecycle event may repeat turnId outside its missing
        # turn object. Once correlated, reject it rather than dereferencing it.
        if method in ('turn/started', 'turn/completed') and not isinstance(turn, dict):
            raise ContractError('invalid-turn-event')
        summary['events'] += 1
        if method == 'turn/started':
            if not summary['started']:
                summary['started'] = True
                if on_started is not None:
                    on_started()
        elif method == 'model/rerouted':
            raise ContractError('model-rerouted')
        elif method == 'error':
            summary['errorCode'] = _error_code(params.get('error'))
            summary['willRetry'] = params.get('willRetry') is True
        elif method == 'item/completed':
            item = params.get('item')
            if not isinstance(item, dict):
                raise ContractError('invalid-turn-item')
            if item.get('type') == 'agentMessage':
                text = item.get('text')
                if not isinstance(text, str) or len(text.encode('utf-8')) > MAX_MESSAGE_TEXT:
                    raise ContractError('invalid-agent-message')
                message = text
        elif method == 'turn/completed':
            status = turn.get('status')
            if status not in TERMINAL_STATUSES:
                raise ContractError('invalid-turn-status')
            summary['status'] = status
            if status == 'completed' and message is None:
                raise ContractError('missing-agent-message')
            if status == 'failed' and summary['errorCode'] is None:
                summary['errorCode'] = _error_code(turn.get('error')) or 'unclassified'
            # Output is only meaningful after a confirmed successful turn.
            summary['text'] = message if status == 'completed' else None
            return summary
    raise ContractError('turn-event-limit')
