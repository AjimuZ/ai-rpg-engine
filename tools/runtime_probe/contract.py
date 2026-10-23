"""Version-specific, read-only discovery and a deliberately tiny neutral output gate.

This is compatibility evidence, not the gameplay adapter. Account presence and
catalog availability never establish entitlement or successful inference.
P01.6 records the managed profile's image unavailability without probing accounts
or making an image request; generation admission always fails closed.
"""
import hashlib
import json

VERSION = '0.164.0'
MAX_MESSAGE = 1024 * 1024
MAX_PAGES = 32
MAX_MODELS = 256


class ContractError(Exception):
    """A safe diagnostic code; raw runtime messages must not reach reports."""


def image_capability():
    """Return fresh P01.6 diagnostics for the pinned managed narration profile.

    Takes no account/configuration inputs, performs no I/O and cannot fail on
    account or quota availability. Empty formats and a null invocation mean no
    supported generation path, rather than a claim about upstream service access.
    """
    return {
        'runtimeVersion': VERSION,
        'profile': 'managed-narration',
        'status': 'unavailable',
        'reason': 'managed-profile-tool-free',
        'invocation': None,
        'supportedOutputFormats': [],
        'accountEntitlement': 'unverified',
        'cost': 'unverified',
        'generationVerified': False,
        'paidFallbackAllowed': False,
        'fallback': 'text-and-catalog',
    }


def require_image_generation(*, policy, output_format):
    """Refuse image admission for any requested policy/format with a safe error.

    Returns nothing: always raises ContractError before runtime, account, network
    or filesystem access. Even explicit bounded policy cannot grant a capability
    absent from this profile. Inputs deliberately cannot select a paid fallback,
    tool, endpoint or output path, and are never echoed in diagnostics.
    """
    raise ContractError('image-generation-unavailable')


def _unique_object(pairs):
    """Return an object from JSON key/value pairs; reject duplicate keys safely."""
    result = {}
    for key, value in pairs:
        if key in result:
            raise ContractError('duplicate-json-key')
        result[key] = value
    return result


def _nonfinite(_value):
    """Reject any nonfinite JSON constant with ContractError; never return a value."""
    raise ContractError('nonfinite-json-number')


def parse_json(data):
    """Parse bounded JSON text/bytes; raise safe errors for malformed/ambiguous data."""
    try:
        if len(data) > MAX_MESSAGE:
            raise ContractError('message-too-large')
        return json.loads(data, object_pairs_hook=_unique_object, parse_constant=_nonfinite)
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise ContractError('invalid-json') from exc


def _text(value, limit=256):
    """Return a nonempty bounded string or raise ContractError on invalid input."""
    if not isinstance(value, str) or not value or len(value) > limit:
        raise ContractError('invalid-string')
    if any(ord(c) < 32 for c in value):
        raise ContractError('invalid-string')
    return value


def discover(rpc):
    """Initialize the supplied RPC, inspect account state and drain bounded pages.

    Return sanitized text discovery plus the engine's managed-profile image
    policy record; never infer image support from runtime/account fields. Raise
    ContractError for malformed or excessive replies and propagate RPC failures.
    """
    hello = rpc.request('initialize', {
        'clientInfo': {'name': 'engine_protocol_probe', 'title': 'Engine protocol probe', 'version': '1.0.0'},
        'capabilities': {'experimentalApi': False},
    })
    if not isinstance(hello, dict):
        raise ContractError('invalid-initialize')
    _text(hello.get('userAgent'), 2048)
    rpc.notify('initialized', {})
    account = rpc.request('account/read', {'refreshToken': False})
    if not isinstance(account, dict) or type(account.get('requiresOpenaiAuth')) is not bool:
        raise ContractError('invalid-account')
    identity = account.get('account')
    if identity is None:
        state = 'sign-in-required' if account['requiresOpenaiAuth'] else 'unsupported-auth'
    elif isinstance(identity, dict) and isinstance(identity.get('type'), str):
        state = ('account-present-unverified' if identity['type'] in ('chatgpt', 'apiKey')
                 else 'unsupported-auth')
    else:
        raise ContractError('invalid-account')

    catalog, ids, cursors = [], set(), set()
    cursor = None
    for _ in range(MAX_PAGES):
        params = {'limit': 2, 'includeHidden': False}
        if cursor is not None:
            params['cursor'] = cursor
        page = rpc.request('model/list', params)
        if not isinstance(page, dict) or not isinstance(page.get('data'), list):
            raise ContractError('invalid-catalog')
        for model in page['data']:
            if not isinstance(model, dict):
                raise ContractError('invalid-catalog')
            model_id, model_name = _text(model.get('id')), _text(model.get('model'))
            efforts = model.get('supportedReasoningEfforts')
            if not isinstance(efforts, list) or not 1 <= len(efforts) <= 16:
                raise ContractError('invalid-efforts')
            if any(not isinstance(e, dict) for e in efforts):
                raise ContractError('invalid-efforts')
            effort_names = [_text(e.get('reasoningEffort'), 32) for e in efforts]
            default = _text(model.get('defaultReasoningEffort'), 32)
            if default not in effort_names or len(set(effort_names)) != len(effort_names):
                raise ContractError('invalid-efforts')
            if model_id in ids or len(catalog) >= MAX_MODELS:
                raise ContractError('duplicate-or-excess-model')
            ids.add(model_id)
            # Never copy the account object or arbitrary runtime fields to diagnostics.
            catalog.append({'id': model_id, 'model': model_name,
                            'efforts': effort_names, 'defaultEffort': default})
        cursor = page.get('nextCursor')
        if cursor is None:
            return {'runtimeVersion': VERSION, 'accountState': state,
                    'inferenceVerified': False, 'catalog': catalog,
                    'imageGeneration': image_capability()}
        _text(cursor, 4096)
        if cursor in cursors:
            raise ContractError('repeated-catalog-cursor')
        cursors.add(cursor)
    raise ContractError('catalog-page-limit')


NEUTRAL_OUTPUT = {
    'contractVersion': 1, 'taskId': 'neutral-protocol-check', 'baseRevision': 0,
    'contextDigest': hashlib.sha256(b'The marker is blue.').hexdigest(),
    'status': 'answered', 'body': {'text': 'The marker is blue.'},
}
# Passing this schema as turn/start.outputSchema constrains generation only.
# The independent validator below still checks terminal status and exact echoes.
OUTPUT_SCHEMA = {
    'type': 'object', 'additionalProperties': False,
    'required': list(NEUTRAL_OUTPUT),
    'properties': {
        key: ({'type': 'object', 'additionalProperties': False, 'required': ['text'],
               'properties': {'text': {'type': 'string', 'const': value['text'], 'maxLength': 64}}}
              if key == 'body' else
              {'type': 'integer' if type(value) is int else 'string', 'const': value})
        for key, value in NEUTRAL_OUTPUT.items()
    },
}


def validate_output(text, *, status):
    """Only a confirmed, correlated completed turn may reach this final gate.

    Thread/turn/item correlation is the caller's responsibility (P01.2). This
    neutral proof accepts one bounded envelope, not arbitrary gameplay output.
    """
    if status != 'completed' or len(text.encode('utf-8')) > 4096:
        raise ContractError('nonterminal-or-oversized-output')
    value = parse_json(text)
    if (not isinstance(value, dict) or value != NEUTRAL_OUTPUT
            or type(value.get('contractVersion')) is not int
            or type(value.get('baseRevision')) is not int):
        raise ContractError('invalid-neutral-output')
    return value
