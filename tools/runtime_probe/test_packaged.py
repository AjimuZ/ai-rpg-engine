"""Pin consistency (always) and Docker-backed transport evidence (opt-in).

The pin checks are deterministic text checks: they keep the Dockerfile, the
Compose fixture, ADR 0009, and contract.VERSION from drifting apart.

The Docker checks need Docker Engine and build the pinned image, so they run
only when RUNTIME_PROBE_DOCKER holds the absolute path of the Docker CLI, for
example ``C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe``.
They never sign in, never mount an account volume, and give neither container
a network, so they cannot reach the account or inference services.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import unittest

from contract import VERSION
import probe

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DOCKERFILE = ROOT / 'docker' / 'codex-runtime' / 'Dockerfile'
COMPOSE = HERE / 'compose.yaml'
ADR = ROOT / 'docs' / 'adr' / '0009-toolchain-baseline.md'
DOCKER = os.environ.get('RUNTIME_PROBE_DOCKER')
DIGEST = r'@sha256:[0-9a-f]{64}'


class PinTests(unittest.TestCase):
    def test_runtime_release_matches_contract_version_and_is_checksummed(self):
        text = DOCKERFILE.read_text(encoding='utf-8')
        releases = set(re.findall(r'/releases/download/rust-v([0-9.]+)/', text))
        self.assertEqual(releases, {VERSION})
        # Every download is verified by BuildKit before use.
        self.assertEqual(text.count('ADD --checksum=sha256:'), text.count('https://'))

    def test_every_external_image_has_exact_tag_and_digest_mirrored_in_adr(self):
        adr = ADR.read_text(encoding='utf-8')
        refs = re.findall(r'^# syntax=(\S+)', DOCKERFILE.read_text(encoding='utf-8'), re.M)
        refs += re.findall(r'^ARG BASE_IMAGE=(\S+)', DOCKERFILE.read_text(encoding='utf-8'), re.M)
        refs += re.findall(r'^\s+image: (\S+@\S+)', COMPOSE.read_text(encoding='utf-8'), re.M)
        self.assertEqual(len(refs), 3)
        for ref in refs:
            with self.subTest(ref=ref):
                self.assertRegex(ref, r'^[a-z0-9./-]+:[A-Za-z0-9._-]+' + DIGEST + '$')
                self.assertNotIn(':latest@', ref)
                self.assertIn(ref, adr)

    def test_fixture_socket_name_is_hash_of_packaged_listen_path(self):
        listen = re.search(r'"unix://(/[^"]+)"', DOCKERFILE.read_text(encoding='utf-8')).group(1)
        name = hashlib.sha256(listen.encode()).hexdigest()
        self.assertEqual(COMPOSE.read_text(encoding='utf-8').count(name), 2)


@unittest.skipUnless(DOCKER, 'set RUNTIME_PROBE_DOCKER to the Docker CLI path')
class DockerTransportTests(unittest.TestCase):
    """Real runtime, no account, no network: discovery over both transports."""

    @classmethod
    def setUpClass(cls):
        cls.compose = [DOCKER, 'compose', '--file', str(COMPOSE)]
        subprocess.run([*cls.compose, 'build', '--quiet', 'codex'], check=True,
                       capture_output=True, timeout=900)

    def assert_unauthenticated_report(self, report, profile):
        self.assertEqual(report['profile'], profile)
        self.assertEqual(report['runtimeVersion'], VERSION)
        self.assertEqual(report['accountState'], 'sign-in-required')
        self.assertIs(report['inferenceVerified'], False)
        self.assertGreater(len(report['catalog']), 2, 'pagination needs more than one page')
        self.assertFalse(report['stderrTruncated'])

    def test_container_stdio_and_packaged_socket_return_the_same_catalog(self):
        stdio = probe.probe_container(argparse.Namespace(
            docker=DOCKER, image='ai-rpg-engine-runtime-probe/codex:0.164.0', timeout=60.0))
        packaged = probe.probe_packaged(argparse.Namespace(
            docker=DOCKER, compose_file=str(COMPOSE), timeout=60.0))
        self.assert_unauthenticated_report(stdio, 'container-stdio')
        self.assert_unauthenticated_report(packaged, 'packaged-unix-socket')
        self.assertEqual(stdio['catalog'], packaged['catalog'])

    def test_socket_denies_other_uids_including_root_without_capabilities(self):
        subprocess.run([*self.compose, 'up', '--detach', '--wait', 'codex'], check=True,
                       capture_output=True, timeout=300)
        try:
            for user in ('10002:10002', '0:0'):
                with self.subTest(user=user):
                    done = subprocess.run(
                        [*self.compose, 'run', '--rm', '-T', '--no-deps', '--user', user, 'relay'],
                        input=b'{}\n', capture_output=True, timeout=300)
                    self.assertEqual(done.returncode, 2)
                    self.assertIn(b'relay-error: socket-io', done.stderr)
        finally:
            subprocess.run([*self.compose, 'down', '--timeout', '10'], capture_output=True,
                           timeout=300)

    def test_java_relay_unit_checks_pass_on_pinned_jdk(self):
        image = re.search(r'^\s+image: (eclipse-temurin\S+)',
                          COMPOSE.read_text(encoding='utf-8'), re.M).group(1)
        done = subprocess.run(
            [DOCKER, 'run', '--rm', '--network', 'none', '--read-only', '--tmpfs', '/tmp',
             '--user', '10001:10001', '--env', 'HOME=/tmp',
             '--volume', f'{HERE}:/src:ro', image, 'sh', '-c',
             'javac -Xlint:all -Werror -d /tmp/out /src/UnixRelay.java /src/UnixRelayTest.java'
             ' && java -ea -cp /tmp/out UnixRelayTest'],
            capture_output=True, timeout=600)
        self.assertEqual(done.returncode, 0, done.stderr.decode(errors='replace')[-2000:])
        self.assertIn(b'checks passed', done.stdout)


if __name__ == '__main__':
    unittest.main()
