"""Launch isolation and executable pin checks without an account."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from contract import ContractError
import probe


class LaunchTests(unittest.TestCase):
    def test_environment_does_not_inherit_keys_configuration_or_proxy(self):
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'secret', 'CODEX_HOME': 'private',
                                     'HTTPS_PROXY': 'private', 'CODEX_CONFIG': 'private'}):
            env = probe.isolated_environment(Path('isolated').resolve())
        self.assertNotIn('OPENAI_API_KEY', env)
        self.assertNotIn('HTTPS_PROXY', env)
        self.assertNotIn('CODEX_CONFIG', env)
        self.assertEqual(env['CODEX_HOME'], str(Path('isolated').resolve()))
        self.assertEqual(env['HOME'], env['CODEX_HOME'])

    def test_version_is_exact_and_missing_or_relative_executable_fails(self):
        for value in ['codex-cli 0.164.0', 'codex-cli 0.164.0\n']:
            probe.validate_version(value)
        for value in ['codex-cli 0.164.1', 'codex-cli 0.164.0-dev', 'secret 0.164.0']:
            with self.subTest(value=value), self.assertRaises(ContractError):
                probe.validate_version(value)
        for path in ['codex', str(Path('absent.exe').resolve())]:
            with self.subTest(path=path), self.assertRaises(ContractError):
                probe.executable_path(path)

    def test_windows_environment_points_user_profile_at_isolated_home(self):
        with patch.dict(os.environ, {'USERPROFILE': 'private', 'APPDATA': 'private'}):
            env = probe.isolated_environment(Path('isolated').resolve())
        self.assertNotEqual(env.get('USERPROFILE'), 'private')
        self.assertNotEqual(env.get('APPDATA'), 'private')


if __name__ == '__main__':
    unittest.main()
