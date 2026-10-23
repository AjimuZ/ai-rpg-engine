"""P01.3 isolation checks for the narration runtime image (ADR 0013).

Two groups of tests, using only synthetic data:

- ``ProfileFileTests`` run everywhere. They read the engine isolation profile
  in docker/codex-runtime and check that it is complete and actually packaged:
  every host feature is off, the catalog offers no tool types, the
  requirements pin the engine's policies, and the Dockerfile copies every
  file and starts through the guard.
- ``DockerIsolationTests`` run only when RUNTIME_PROBE_DOCKER names the Docker
  CLI and the probe image is built. They drive the pinned runtime against the
  scripted provider (no account, internal network) and inspect the requests
  it really sends upstream: the model must receive no tools, no host
  environment and no inherited instruction; scripted tool calls must be
  refused without touching the workspace; a full-access thread must be
  rejected and other widened thread settings clamped to read-only/never; and
  the guard must refuse inherited files in the runtime home.

Every denial is preceded by a positive control in the same session (a
completed, contract-valid neutral turn, or the image starting normally), so a
crashed or offline runtime can never pass as an effective restriction. Fixture
cleanup stops and removes only the containers and network it started
(`compose down` without `-v`); it deletes no volume.
"""
import base64
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import subprocess
import tomllib
import unittest

from contract import ContractError, discover, validate_output
import probe
import session
from transport import Rpc, Stdio

HERE = Path(__file__).resolve().parent
PROFILE = HERE.parents[1] / 'docker' / 'codex-runtime'
FIXTURE = HERE / 'isolation_fixture'
DOCKER = os.environ.get('RUNTIME_PROBE_DOCKER')
# The probe image session.compose.yaml uses; built from PROFILE's Dockerfile.
IMAGE = 'ai-rpg-engine-runtime-probe/codex:0.164.0'
# Files the Dockerfile installs into /etc/codex, read-only for root.
PROFILE_FILES = ('managed_config.toml', 'requirements.toml',
                 'engine-model-catalog.json', 'engine-instructions.md')
# Home files the runtime would load as configuration or instructions; the
# guard refuses to start when any of them exists.
GUARDED_HOME_FILES = ('config.toml', 'AGENTS.md', 'AGENTS.override.md')
# Every synthetic inherited value starts with this marker.
SENTINEL = 'inherited-sentinel'
# The path every scripted write in ScriptedProvider's scenario-tools targets.
CANONICAL_SENTINEL = '/var/lib/runtime/workspace/canonical-sentinel.json'
# The runtime's own answers to the eight scripted calls when no tool exists.
REFUSALS = ['unsupported call: shell', 'unsupported call: exec_command',
            'unsupported custom tool call: apply_patch',
            'unsupported custom tool call: exec',
            'unsupported call: read_mcp_resource', 'unsupported call: view_image',
            'unsupported call: image_generation', 'unsupported call: image_gen']


def engine_instructions():
    """Return the engine's base instruction text without its final newline."""
    return (PROFILE / 'engine-instructions.md').read_text(encoding='utf-8').strip()


def message_texts(body, role):
    """Return the input_text strings of every ``role`` message in a request body."""
    return [part.get('text') for item in body.get('input', [])
            if item.get('type') == 'message' and item.get('role') == role
            for part in item.get('content', []) if part.get('type') == 'input_text']


class ProfileFileTests(unittest.TestCase):
    """Check the profile files and their packaging without Docker.

    These tests catch a profile edit that silently re-enables a capability or
    a file that would no longer reach the image; the Docker tests prove the
    runtime honors the same files.
    """

    def test_managed_config_disables_every_host_capability(self):
        """Require every feature switch off and every instruction source replaced.

        The runtime ignores unknown keys, so this also pins the exact feature
        set: removing a key must be a reviewed change of this test.
        """
        managed = tomllib.loads((PROFILE / 'managed_config.toml').read_text(encoding='utf-8'))
        features = managed.pop('features')
        self.assertEqual(set(features.values()), {False})
        for name in ('shell_tool', 'unified_exec', 'code_mode', 'view_image', 'multi_agent',
                     'hooks', 'memories', 'plugins', 'apps', 'computer_use', 'image_generation'):
            self.assertIs(features.get(name), False, name)
        self.assertEqual(managed['model_catalog_json'], '/etc/codex/engine-model-catalog.json')
        self.assertEqual(managed['model_instructions_file'], '/etc/codex/engine-instructions.md')
        self.assertEqual(managed['developer_instructions'], '')
        self.assertEqual(managed['web_search'], 'disabled')
        self.assertEqual(managed['project_doc_max_bytes'], 0)
        for key in ('include_collaboration_mode_instructions', 'include_environment_context',
                    'include_apps_instructions'):
            self.assertIs(managed[key], False, key)
        self.assertIs(managed['tools']['experimental_request_user_input']['enabled'], False)
        self.assertIs(managed['skills']['include_instructions'], False)
        self.assertIs(managed['skills']['bundled']['enabled'], False)

    def test_requirements_pin_engine_policies_and_forbid_extensions(self):
        """Require an empty extension allowlist and only the engine's policies."""
        requirements = tomllib.loads((PROFILE / 'requirements.toml').read_text(encoding='utf-8'))
        self.assertEqual(requirements, {
            'mcp_servers': {}, 'allowed_approval_policies': ['never'],
            'allowed_sandbox_modes': ['read-only'], 'allowed_web_search_modes': ['disabled']})

    def test_catalog_offers_no_tool_types_and_engine_instructions(self):
        """Require every catalog model to be text-only, tool-free and engine-instructed."""
        models = json.loads((PROFILE / 'engine-model-catalog.json')
                            .read_text(encoding='utf-8'))['models']
        # The P01.1 contract test needs more than two models to pick from.
        self.assertGreater(len(models), 2)
        for model in models:
            with self.subTest(model=model.get('slug')):
                self.assertEqual(model['base_instructions'], engine_instructions())
                self.assertEqual(model['tool_mode'], 'direct')
                self.assertEqual(model['shell_type'], 'disabled')
                self.assertIsNone(model['apply_patch_tool_type'])
                self.assertEqual(model['experimental_supported_tools'], [])
                self.assertEqual(model['input_modalities'], ['text'])
                self.assertIs(model['supports_search_tool'], False)
                for key in ('include_apps_usage_instructions', 'include_plugin_usage_instructions',
                            'include_skills_usage_instructions'):
                    self.assertIs(model[key], False, key)

    def test_dockerfile_installs_profile_and_starts_through_guard(self):
        """Require every profile file in the build context, the image and the entrypoint."""
        dockerfile = (PROFILE / 'Dockerfile').read_text(encoding='utf-8')
        ignored = (PROFILE / '.dockerignore').read_text(encoding='utf-8').splitlines()
        copy = re.search(r'^COPY --chown=0:0 --chmod=0444 (.+?) /etc/codex/$',
                         dockerfile.replace('\\\n', ' '), re.MULTILINE)
        self.assertIsNotNone(copy)
        self.assertEqual(sorted(copy.group(1).split()), sorted(PROFILE_FILES))
        for name in PROFILE_FILES + ('runtime-entrypoint.sh',):
            self.assertIn('!' + name, ignored)
        # A directory created by COPY --chmod=0444 is unreadable and the
        # runtime then refuses to start; the explicit 0755 directory is required.
        self.assertIn('RUN install -d -o 0 -g 0 -m 0755 /etc/codex', dockerfile)
        self.assertIn('ENTRYPOINT ["/usr/local/bin/runtime-entrypoint"]', dockerfile)
        guard = (PROFILE / 'runtime-entrypoint.sh').read_text(encoding='utf-8')
        for name in GUARDED_HOME_FILES:
            self.assertIn(name, guard)
        self.assertIn('exec /usr/local/bin/codex "$@"', guard)


@unittest.skipUnless(DOCKER, 'set RUNTIME_PROBE_DOCKER to the Docker CLI path')
class DockerIsolationTests(unittest.TestCase):
    """Drive the real pinned runtime against the scripted provider.

    No credentials, account volume or private prompt enter these fixtures.
    Subprocess and protocol errors fail the test; cleanup always runs.
    """

    def compose(self, *files, project):
        """Return a Compose argv prefix for ``files`` under a fixed project name."""
        argv = [DOCKER, 'compose', '-p', project]
        for name in files:
            argv += ['-f', str(HERE / name)]
        return argv

    @contextmanager
    def fixture(self, compose):
        """Start the provider and runtime, yield, then stop the fixture.

        Raises CalledProcessError (with the runtime's log tail in the failure
        message) when the runtime does not become healthy.
        """
        try:
            started = subprocess.run([*compose, 'up', '-d', '--wait', 'codex'],
                                     capture_output=True, timeout=240)
            if started.returncode:
                tail = subprocess.run([*compose, 'logs', '--no-log-prefix', 'codex'],
                                      capture_output=True, timeout=30).stdout[-2000:]
                self.fail('fixture did not start: ' + tail.decode(errors='replace'))
            yield
        finally:
            subprocess.run([*compose, 'down', '--timeout', '10'], check=True,
                           capture_output=True, timeout=120)

    def provider_bodies(self, compose):
        """Return every upstream request body the scripted provider logged, in order."""
        logs = subprocess.check_output([*compose, 'logs', '--no-log-prefix', 'provider'],
                                       timeout=30).decode()
        return [json.loads(base64.b64decode(line.split(' ', 3)[3]))
                for line in logs.splitlines() if line.startswith('request ')]

    def neutral_turn(self, rpc, settings, scenario):
        """Run one turn on a new thread and require a completed, valid narration result.

        This is the positive control: the runtime is up, reachable and able to
        answer, so any later absence of a capability is a real restriction.
        Returns the thread ID.
        """
        thread = session.start_thread(rpc, model=settings[0], cwd=probe.WORKSPACE)
        turn = session.start_turn(rpc, thread['threadId'], model=settings[0], effort=settings[1],
                                  text=session.NEUTRAL_PROMPT + ' ' + scenario)
        # await_turn fails with unexpected-turn-item if the runtime starts any
        # command, file change, tool call or other non-narration item.
        result = session.await_turn(rpc, thread['threadId'], turn, timeout=90)
        self.assertEqual((result['status'], result['errorCode']), ('completed', None))
        validate_output(result['text'], status=result['status'])
        return thread['threadId']

    def assert_clean_request(self, body):
        """Require no tools, only engine and runtime policy text, and no host context."""
        raw = json.dumps(body)
        self.assertEqual(body.get('tools'), [], 'model must receive no tools')
        self.assertNotIn(SENTINEL, raw)
        self.assertNotIn('environment_context', raw)
        developer = message_texts(body, 'developer')
        # Exactly the engine's instructions plus the runtime's own statement
        # of the read-only policy; any inherited text would be an extra entry.
        self.assertEqual(len(developer), 2, 'unexpected developer text')
        self.assertEqual(developer[0], engine_instructions())
        self.assertTrue(developer[1].startswith('<permissions instructions>'))
        self.assertIn('`sandbox_mode` is `read-only`', developer[1])
        for text in message_texts(body, 'user'):
            self.assertTrue(text.startswith(session.NEUTRAL_PROMPT), 'unexpected user text')

    def test_guard_refuses_inherited_home_files(self):
        """Require exit 78 for each inherited home file, after a normal start control.

        The guard never reads the files, so the synthetic content is irrelevant.
        """
        run = [DOCKER, 'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL']
        control = subprocess.run([*run, IMAGE, '--version'], capture_output=True, timeout=60)
        self.assertEqual(control.returncode, 0, control.stderr)
        self.assertIn(b'0.164.0', control.stdout)
        for name in GUARDED_HOME_FILES:
            mount = f'{FIXTURE / "home-AGENTS.md"}:/var/lib/runtime/home/{name}:ro'
            with self.subTest(name=name):
                refused = subprocess.run([*run, '-v', mount, IMAGE, 'app-server'],
                                         capture_output=True, timeout=60)
                self.assertEqual(refused.returncode, 78)
                self.assertIn(b'refusing to start', refused.stderr)
                self.assertNotIn(SENTINEL.encode(), refused.stdout + refused.stderr)

    def test_packaged_profile_exposes_no_tools_and_refuses_host_actions(self):
        """Prove restricted surface, denied writes and pinned policies on the packaged image.

        Thread 1 is the neutral control. Thread 2 makes the scripted provider
        call eight host tools (two command runners, two patch forms writing the
        canonical sentinel, an extension read, an image read and two image
        generation function names); every call
        must be answered as unsupported and the sentinel must not exist.
        Finally, a thread asking for full host access must be rejected and
        threads asking for other wider policies must be clamped to
        read-only sandboxing with approvals never requested.
        """
        compose = self.compose('session.compose.yaml', project='ai-rpg-engine-runtime-isolation')
        with self.fixture(compose):
            with Stdio([*compose, 'run', '--rm', '-T', 'relay'], env=None, cwd=None) as wire:
                rpc = Rpc(wire, timeout=60)
                settings = probe.pick_settings(discover(rpc)['catalog'])[0]
                self.neutral_turn(rpc, settings, 'scenario-complete')
                self.neutral_turn(rpc, settings, 'scenario-tools')
                params = {'model': settings[0], 'cwd': probe.WORKSPACE,
                          'approvalPolicy': 'never', 'sandbox': 'read-only'}
                # Full host access is rejected outright by requirements.toml.
                with self.assertRaises(ContractError) as caught:
                    rpc.request('thread/start', {**params, 'sandbox': 'danger-full-access'})
                self.assertEqual(str(caught.exception), 'runtime-rpc-error')
                # Other wider requests are accepted but clamped by the pinned
                # runtime to the only allowed policies; the effective thread
                # policy is what matters, so it must still be read-only/never.
                for wider in ({'sandbox': 'workspace-write'},
                              {'sandbox': 'workspace-write', 'config': {
                                  'sandbox_workspace_write': {'network_access': True}}},
                              {'approvalPolicy': 'on-request'},
                              {'approvalPolicy': 'untrusted'}):
                    with self.subTest(wider=wider):
                        result = rpc.request('thread/start', {**params, **wider})
                        self.assertEqual(result.get('approvalPolicy'), 'never')
                        self.assertEqual(result.get('sandbox'),
                                         {'type': 'readOnly', 'networkAccess': False})
            absent = subprocess.run([*compose, 'exec', '-T', 'codex', 'test', '!', '-e',
                                     CANONICAL_SENTINEL], capture_output=True, timeout=60)
            self.assertEqual(absent.returncode, 0, 'a refused write left the sentinel')
            bodies = self.provider_bodies(compose)
        # One request for the control, two for the tool scenario (calls, then
        # the runtime's refusals); none for the policy thread starts, which run no turn.
        self.assertEqual(len(bodies), 3)
        for body in bodies:
            self.assert_clean_request(body)
        outputs = [item.get('output') for item in bodies[2]['input']
                   if item.get('type') in ('function_call_output', 'custom_tool_call_output')]
        self.assertEqual(outputs, REFUSALS)

    def test_inherited_configuration_and_launch_flags_do_not_apply(self):
        """Prove the managed profile alone defeats inherited config and hostile flags.

        isolation.compose.yaml bypasses the guard, mounts a synthetic user
        config.toml, project AGENTS.md and project .codex/config.toml, and
        appends flags re-enabling shell, exec, images and live search. The
        neutral turn must still complete with a clean request.
        """
        base = self.compose('session.compose.yaml', project='ai-rpg-engine-runtime-isolation')
        compose = self.compose('session.compose.yaml', 'isolation.compose.yaml',
                               project='ai-rpg-engine-runtime-isolation')
        # The override restates the fixture command; it must not drift from it.
        rendered = [json.loads(subprocess.check_output([*argv, 'config', '--format', 'json'],
                                                       timeout=60))['services']['codex']
                    for argv in (base, compose)]
        prefix = rendered[0]['command']
        self.assertEqual(rendered[1]['command'][:len(prefix)], prefix)
        self.assertIn('shell_tool', rendered[1]['command'][len(prefix):])
        self.assertEqual(rendered[1]['entrypoint'], ['/usr/local/bin/codex'])
        with self.fixture(compose):
            with Stdio([*compose, 'run', '--rm', '-T', 'relay'], env=None, cwd=None) as wire:
                rpc = Rpc(wire, timeout=60)
                settings = probe.pick_settings(discover(rpc)['catalog'])[0]
                self.neutral_turn(rpc, settings, 'scenario-complete')
            # The planted files really were present, so their absence from
            # the request is the profile's effect, not a missing mount.
            planted = subprocess.run(
                [*compose, 'exec', '-T', 'codex', 'grep', '-l', SENTINEL,
                 '/var/lib/runtime/home/config.toml', '/var/lib/runtime/workspace/AGENTS.md',
                 '/var/lib/runtime/workspace/.codex/config.toml'],
                capture_output=True, timeout=60)
            self.assertEqual(planted.returncode, 0, planted.stderr)
            self.assertEqual(len(planted.stdout.splitlines()), 3)
            bodies = self.provider_bodies(compose)
        self.assertEqual(len(bodies), 1)
        self.assert_clean_request(bodies[0])


if __name__ == '__main__':
    unittest.main()
