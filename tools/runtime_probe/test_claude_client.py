"""Pin and installation checks for the second narration client probe image (P20.1-A).

The pin checks always run. They are text checks that keep the probe Dockerfile and
ADR 0009 in step, and they reject any build step that could run a package lifecycle
script.

The Docker checks run only when RUNTIME_PROBE_DOCKER holds the absolute path of the
Docker CLI. They build the image, which fetches the pinned archives from the public
npm registry, and they run the result with no network, no account and no credentials.
They never sign in and never send a task, so they prove installation, not inference.
"""
import base64
import os
from pathlib import Path
import re
import subprocess
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CONTEXT = HERE / 'claude_client'
DOCKERFILE = CONTEXT / 'Dockerfile'
ADR = ROOT / 'docs' / 'adr' / '0009-toolchain-baseline.md'
DOCKER = os.environ.get('RUNTIME_PROBE_DOCKER')

VERSION = '2.1.323'
IMAGE = f'ai-rpg-engine-runtime-probe/claude:{VERSION}'
SELFTEST_IMAGE = f'ai-rpg-engine-runtime-probe/claude-selftest:{VERSION}'
REGISTRY = 'https://registry.npmjs.org/'
# Run settings of every container these tests start: no network, read-only root,
# no capabilities and a throw-away home, so nothing can reach an account or persist.
ISOLATED_RUN = ['run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
                '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,exec,mode=1777',
                '--env', 'HOME=/tmp']


def dockerfile_args():
    """Return the global ``ARG NAME=default`` values declared before the first stage."""
    text = DOCKERFILE.read_text(encoding='utf-8')
    head = text.split('\nFROM ', 1)[0]
    return dict(re.findall(r'^ARG (\w+)=(\S+)$', head, re.M))


class ClientPinTests(unittest.TestCase):
    """The exact version, integrity pins and install method stay as ADR 0009 records them."""

    def test_version_and_integrity_pins_are_exact_and_mirrored_in_adr(self):
        args = dockerfile_args()
        adr = ADR.read_text(encoding='utf-8')
        self.assertEqual(args['CLIENT_VERSION'], VERSION)
        self.assertIn(f'`{VERSION}`', adr)
        for name in ('WRAPPER_INTEGRITY', 'PLATFORM_INTEGRITY'):
            with self.subTest(pin=name):
                integrity = args[name]
                self.assertRegex(integrity, r'^sha512-[A-Za-z0-9+/]+={0,2}$')
                self.assertEqual(len(base64.b64decode(integrity[len('sha512-'):], validate=True)), 64)
                self.assertIn(integrity, adr)
        self.assertNotEqual(args['WRAPPER_INTEGRITY'], args['PLATFORM_INTEGRITY'])

    def test_base_image_and_frontend_are_the_pinned_runtime_references(self):
        text = DOCKERFILE.read_text(encoding='utf-8')
        runtime = (ROOT / 'docker' / 'codex-runtime' / 'Dockerfile').read_text(encoding='utf-8')
        syntax = re.search(r'^# syntax=(\S+)', text, re.M).group(1)
        self.assertEqual(syntax, re.search(r'^# syntax=(\S+)', runtime, re.M).group(1))
        self.assertEqual(dockerfile_args()['BASE_IMAGE'],
                         re.search(r'^ARG BASE_IMAGE=(\S+)', runtime, re.M).group(1))

    def test_every_download_is_a_versioned_registry_archive_verified_before_use(self):
        text = DOCKERFILE.read_text(encoding='utf-8')
        adds = re.findall(r'^ADD (\S+) (\S+)$', text, re.M)
        self.assertEqual(adds, [
            (REGISTRY + '@anthropic-ai/claude-code/-/claude-code-${CLIENT_VERSION}.tgz',
             '/fetch/wrapper.tgz'),
            (REGISTRY + '@anthropic-ai/claude-code-linux-x64/-/claude-code-linux-x64-${CLIENT_VERSION}.tgz',
             '/fetch/platform.tgz'),
        ])
        # Only these two lines reach the network, and both archives pass the verifier
        # in the first RUN after them, before `tar` opens either one.
        self.assertEqual(text.count('https://'), 2)
        after = text.split(adds[-1][1], 1)[1]
        step = re.search(r'^RUN (.+?)(?=^\S)', after, re.M | re.S).group(1)
        self.assertLess(step.index('verify-sri /fetch/wrapper.tgz "$WRAPPER_INTEGRITY"'), step.index('tar '))
        self.assertLess(step.index('verify-sri /fetch/platform.tgz "$PLATFORM_INTEGRITY"'), step.index('tar '))

    def test_no_step_can_run_a_package_lifecycle_script(self):
        text = DOCKERFILE.read_text(encoding='utf-8')
        instructions = [line for line in text.splitlines() if line and not line.startswith('#')]
        for line in instructions:
            with self.subTest(line=line):
                self.assertNotRegex(line, r'\b(npm|npx|node|yarn|pnpm|bun|corepack)\b')
                self.assertNotIn('install.cjs', line)
                self.assertNotIn('cli-wrapper', line)
        self.assertNotRegex(text, r'^FROM\s+\S*node', 'a Node.js base would bring npm')
        # The client stage receives exactly one file from the fetch stage: the binary.
        self.assertEqual(re.findall(r'^COPY --from=(\S+) (\S+) (\S+)$', text, re.M),
                         [('fetch', '/fetch/claude', '/usr/local/bin/claude')])

    def test_verifier_accepts_only_a_single_sha512_digest(self):
        script = (CONTEXT / 'verify-sri.sh').read_text(encoding='utf-8')
        self.assertTrue(script.startswith('#!/bin/sh\n'))
        self.assertNotIn('\r', script)
        self.assertIn('sha512sum', script)
        self.assertNotRegex(script, r'sha(1|256|384)sum|md5sum')


# The Docker checks exist only when RUNTIME_PROBE_DOCKER names the Docker CLI, which
# `.agents/ralph/verify.sh containers` always sets for this suite; without it they are
# not defined at all, so nothing here is reported as skipped or as passed.
if DOCKER:
    class DockerClientImageTests(unittest.TestCase):
        """Real build from the registry; runs without a network, account or credentials."""

        @classmethod
        def setUpClass(cls):
            for target, tag in (('integrity-selftest', SELFTEST_IMAGE), ('client', IMAGE)):
                done = subprocess.run(
                    [DOCKER, 'build', '--quiet', '--target', target, '--tag', tag, str(CONTEXT)],
                    capture_output=True, timeout=1200)
                if done.returncode != 0:
                    raise AssertionError(f'{target} build failed: '
                                         + done.stderr.decode(errors='replace')[-3000:])

        def run_isolated(self, image, *arguments, entrypoint=None, user='10001:10001'):
            command = [DOCKER, *ISOLATED_RUN, '--user', user]
            if entrypoint:
                command += ['--entrypoint', entrypoint]
            return subprocess.run([*command, image, *arguments], capture_output=True, timeout=120)

        def test_selftest_stage_rejected_modified_archive_and_bad_pins(self):
            done = self.run_isolated(SELFTEST_IMAGE, '/fetch/selftest-passed',
                                     entrypoint='cat', user='0:0')
            self.assertEqual(done.returncode, 0, done.stderr.decode(errors='replace'))
            self.assertEqual(done.stdout, b'integrity selftest passed\n')

        def test_installed_binary_is_the_verified_archive_member_unchanged(self):
            member = self.run_isolated(SELFTEST_IMAGE, '/fetch/platform/package/claude',
                                       entrypoint='sha256sum', user='0:0')
            installed = self.run_isolated(IMAGE, '/usr/local/bin/claude', entrypoint='sha256sum')
            self.assertEqual(member.returncode, 0, member.stderr.decode(errors='replace'))
            self.assertEqual(installed.returncode, 0, installed.stderr.decode(errors='replace'))
            self.assertEqual(member.stdout.split()[0], installed.stdout.split()[0])

        def test_client_reports_pinned_version_offline_as_unprivileged_user(self):
            done = self.run_isolated(IMAGE, '--version')
            self.assertEqual(done.returncode, 0, done.stderr.decode(errors='replace')[-2000:])
            self.assertTrue(done.stdout.decode().startswith(VERSION), done.stdout[:200])

        def test_image_has_no_package_manager_or_wrapper_scripts(self):
            done = self.run_isolated(
                IMAGE, '-c',
                'for tool in node npm npx; do command -v "$tool" && exit 1; done;'
                ' test ! -e /fetch && test ! -e /usr/local/lib/node_modules && id -u',
                entrypoint='sh')
            self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
            self.assertEqual(done.stdout, b'10001\n')


if __name__ == '__main__':
    unittest.main()
