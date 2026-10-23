"""Exercise the P01.6 unavailable capability and admission boundary without accounts.

Synthetic discovery, policy requests and CLI runs must never launch inference;
assertion failures identify any drift in the profile-scoped refusal contract.
"""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tomllib
import unittest
from unittest.mock import patch

from contract import ContractError, discover
import contract as images
import probe
from test_contract import RpcFixture


class ImageCapabilityTests(unittest.TestCase):
    """Verify capability diagnostics and refusal for synthetic inputs; fail on drift."""

    def test_unavailable_record_is_explicit_and_independent(self):
        """Return fresh, neutral evidence with no supported formats or account claims."""
        record = images.image_capability()
        self.assertEqual(record['status'], 'unavailable')
        self.assertEqual(record['reason'], 'managed-profile-tool-free')
        self.assertEqual(record['runtimeVersion'], '0.164.0')
        self.assertEqual(record['supportedOutputFormats'], [])
        self.assertIsNone(record['invocation'])
        self.assertEqual(record['accountEntitlement'], 'unverified')
        self.assertEqual(record['cost'], 'unverified')
        self.assertIs(record['generationVerified'], False)
        self.assertIs(record['paidFallbackAllowed'], False)
        self.assertEqual(record['fallback'], 'text-and-catalog')
        record['supportedOutputFormats'].append('image/png')
        self.assertEqual(images.image_capability()['supportedOutputFormats'], [])

    def test_generation_is_refused_for_every_policy_and_format(self):
        """Reject even bounded opt-in and invented formats before any external action."""
        for policy in ('disabled', 'catalogOnly', 'bounded', 'unknown', None):
            for output_format in ('png', 'jpeg', 'webp', 'svg', '../output', None):
                with self.subTest(policy=policy, output_format=output_format):
                    with self.assertRaisesRegex(ContractError, '^image-generation-unavailable$'):
                        images.require_image_generation(policy=policy, output_format=output_format)

    def test_text_discovery_remains_usable_with_unavailable_images(self):
        """Discover text catalog under absent or present synthetic accounts without turns."""
        for account in (None, {'type': 'chatgpt', 'email': 'private-sentinel'},
                        {'type': 'apiKey'}):
            rpc = RpcFixture(account={'account': account, 'requiresOpenaiAuth': True})
            report = discover(rpc)
            self.assertEqual(report['imageGeneration'], images.image_capability())
            self.assertEqual(len(report['catalog']), 1)
            self.assertIs(report['inferenceVerified'], False)
            self.assertEqual([method for method, _ in rpc.calls],
                             ['initialize', 'initialized', 'account/read', 'model/list'])
            self.assertNotIn('private-sentinel', json.dumps(report))

    def test_offline_cli_reports_and_denies_without_launching_processes(self):
        """Emit diagnostics or fixed refusal with no Docker/runtime child, even with keys."""
        with patch.dict('os.environ', {'OPENAI_API_KEY': 'private-sentinel'}):
            with patch('subprocess.Popen') as popen, patch('subprocess.run') as run:
                for arguments, expected in ((['images'], 0), (['images', '--generate'], 1)):
                    output = io.StringIO()
                    with redirect_stdout(output):
                        self.assertEqual(probe.main(arguments), expected)
                    report = json.loads(output.getvalue())
                    if expected:
                        self.assertEqual(report['error'], 'image-generation-unavailable')
                    else:
                        self.assertEqual(report, images.image_capability())
                    self.assertNotIn('private-sentinel', output.getvalue())
                popen.assert_not_called()
                run.assert_not_called()

    def test_unavailable_record_matches_managed_runtime_profile(self):
        """Require image generation and image reading off in the packaged profile."""
        root = Path(__file__).resolve().parents[2]
        profile = tomllib.loads((root / 'docker/codex-runtime/managed_config.toml')
                               .read_text(encoding='utf-8'))
        for feature in ('image_generation', 'view_image'):
            self.assertIs(profile['features'][feature], False)

    def test_retained_evidence_matches_current_policy(self):
        """Fail on stale or embellished neutral evidence rather than imply live access."""
        evidence = Path(__file__).resolve().parent / 'evidence/images-0.164.0.json'
        self.assertEqual(json.loads(evidence.read_text(encoding='utf-8')),
                         images.image_capability())


if __name__ == '__main__':
    unittest.main()
