"""Deterministic discovery/output contracts; no account or inference required."""
import copy
import json
import unittest

import contract


MODEL = {
    'id': 'neutral', 'model': 'neutral-runtime', 'displayName': 'Neutral',
    'hidden': False, 'defaultReasoningEffort': 'medium',
    'supportedReasoningEfforts': [{'reasoningEffort': 'medium', 'description': 'Neutral'}],
}


class RpcFixture:
    def __init__(self, account=None, pages=None):
        self.calls = []
        self.account = {'account': None, 'requiresOpenaiAuth': True} if account is None else account
        self.pages = iter(pages or [{'data': [MODEL], 'nextCursor': None}])

    def request(self, method, params):
        self.calls.append((method, params))
        if method == 'initialize':
            return {'userAgent': 'codex/0.164.0', 'platformFamily': 'unix', 'platformOs': 'linux'}
        if method == 'account/read':
            return self.account
        if method == 'model/list':
            return next(self.pages)
        raise AssertionError(method)

    def notify(self, method, params):
        self.calls.append((method, params))


class DiscoveryTests(unittest.TestCase):
    def test_handshake_precedes_account_and_all_catalog_pages(self):
        other = dict(MODEL, id='second', model='second-runtime')
        rpc = RpcFixture(pages=[{'data': [MODEL], 'nextCursor': 'opaque'},
                                {'data': [other], 'nextCursor': None}])
        result = contract.discover(rpc)
        self.assertEqual([m['id'] for m in result['catalog']], ['neutral', 'second'])
        self.assertEqual(result['accountState'], 'sign-in-required')
        self.assertFalse(result['inferenceVerified'])
        self.assertEqual([c[0] for c in rpc.calls],
                         ['initialize', 'initialized', 'account/read', 'model/list', 'model/list'])
        self.assertEqual(rpc.calls[2][1], {'refreshToken': False})
        self.assertEqual(rpc.calls[-1][1]['cursor'], 'opaque')

    def test_account_presence_is_not_entitlement_and_personal_fields_are_omitted(self):
        rpc = RpcFixture(account={'account': {'type': 'chatgpt', 'email': 'private@example.invalid',
                                               'planType': 'private'}, 'requiresOpenaiAuth': True})
        result = contract.discover(rpc)
        self.assertEqual(result['accountState'], 'account-present-unverified')
        self.assertNotIn('private', json.dumps(result))
        self.assertFalse(result['inferenceVerified'])

    def test_missing_account_without_required_auth_is_not_a_supported_account(self):
        result = contract.discover(RpcFixture(account={'account': None, 'requiresOpenaiAuth': False}))
        self.assertEqual(result['accountState'], 'unsupported-auth')

    def test_repeated_cursor_duplicate_model_and_unbounded_pages_fail_closed(self):
        for pages in [
            [{'data': [], 'nextCursor': 'same'}] * 3,
            [{'data': [MODEL], 'nextCursor': 'next'}, {'data': [MODEL]}],
            [{'data': [], 'nextCursor': str(i)} for i in range(100)],
        ]:
            with self.subTest(pages=len(pages)), self.assertRaises(contract.ContractError):
                contract.discover(RpcFixture(pages=pages))

    def test_malformed_account_and_catalog_fail_closed(self):
        for account in [{}, {'requiresOpenaiAuth': 'true'}, {'requiresOpenaiAuth': True, 'account': {}}]:
            with self.subTest(account=account), self.assertRaises(contract.ContractError):
                contract.discover(RpcFixture(account=account))
        bad = dict(MODEL, defaultReasoningEffort='ultra', supportedReasoningEfforts=[])
        for page in [{'data': 'not-a-list'}, {'data': [bad]}, {'data': [], 'nextCursor': 3}]:
            with self.subTest(page=page), self.assertRaises(contract.ContractError):
                contract.discover(RpcFixture(pages=[page]))


class OutputTests(unittest.TestCase):
    def test_only_completed_correlated_neutral_envelope_is_accepted(self):
        value = copy.deepcopy(contract.NEUTRAL_OUTPUT)
        self.assertEqual(contract.validate_output(json.dumps(value), status='completed'), value)
        for field in ['contractVersion', 'taskId', 'baseRevision', 'contextDigest', 'status', 'body']:
            bad = copy.deepcopy(value)
            del bad[field]
            with self.subTest(field=field), self.assertRaises(contract.ContractError):
                contract.validate_output(json.dumps(bad), status='completed')
        for bad in [dict(value, baseRevision=True), dict(value, taskId='stale'),
                    dict(value, body={'text': 'Invented result'}), dict(value, statePatch={})]:
            with self.subTest(bad=bad), self.assertRaises(contract.ContractError):
                contract.validate_output(json.dumps(bad), status='completed')
        for status in ['failed', 'interrupted', 'inProgress']:
            with self.subTest(status=status), self.assertRaises(contract.ContractError):
                contract.validate_output(json.dumps(value), status=status)

    def test_duplicate_keys_nonfinite_values_and_oversized_output_are_rejected(self):
        for text in ['{"taskId":"one","taskId":"two"}', '{"value":NaN}', ' ' * 4097, '[]']:
            with self.subTest(text=text[:40]), self.assertRaises(contract.ContractError):
                contract.validate_output(text, status='completed')


if __name__ == '__main__':
    unittest.main()
