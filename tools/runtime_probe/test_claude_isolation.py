"""Isolation proof for the second narration client probe image (P20.1-B).

The pinned client documents ``ANTHROPIC_BASE_URL`` as a supported base-URL override, so the
Docker checks run the unmodified client against a scripted upstream (MessagesProvider.java)
on an internal network, with no account and no real credential. Two groups of tests:

- ``FixtureFileTests`` always run. They keep the planted fixture, the runner's launch
  profiles, the Compose fixture and the scripted upstream consistent with what the Docker
  checks assume, without Docker.
- ``DockerClientIsolationTests`` exist only when RUNTIME_PROBE_DOCKER names the Docker CLI.
  The positive control runs the client with its own defaults over the planted home and
  project files and shows that every probe detects its surface: offered and executed tools,
  home and project hooks, MCP servers, instruction files, skills, the client's bundled
  ``@builtin`` plugins, and a credential taken from configuration. Only this control marks
  the workspace trusted, so the project's permission entries apply. The isolated profile then
  runs over the same files, with a fixture API key and subscription and first-runtime
  credentials in its environment, and must expose none of them, load no plugin at all, and
  still complete the neutral turn.

Sentinels: every planted value starts with ``inherited-sentinel`` (instructions and extension
names) or ``secret-sentinel`` (credentials). No real credential exists anywhere in the fixture.
Cleanup stops only the fixture's own containers and network (``compose down`` without ``-v``).
"""
import base64
from contextlib import contextmanager
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
FIXTURE = HERE / 'claude_isolation_fixture'
RUNNER = FIXTURE / 'run-case.sh'
COMPOSE_FILE = HERE / 'claude_isolation.compose.yaml'
PROVIDER = HERE / 'MessagesProvider.java'
CLIENT_DOCKERFILE = HERE / 'claude_client' / 'Dockerfile'
DOCKER = os.environ.get('RUNTIME_PROBE_DOCKER')

PROJECT = 'ai-rpg-engine-claude-isolation'
SECRET = 'secret-sentinel'
INHERITED = 'inherited-sentinel'
NEUTRAL_TEXT = 'The marker is blue.'
FIXTURE_KEY = 'fixture-api-key-not-a-credential'
ENGINE_PROMPT = 'You narrate for the engine. Reply with plain text only.'
# The scripted host tool calls of MessagesProvider.TOOL_CALLS, in order.
TOOL_IDS = ['toolu_probe_bash', 'toolu_probe_write', 'toolu_probe_read', 'toolu_probe_fetch']
# Fixture files that hold a credential sentinel. home-global-config.json deliberately holds
# none: the client rewrites and backs up that file, and a backup is not a leak.
SECRET_FILES = {'home-settings.json', 'project-settings.json', 'project-settings-local.json',
                'home-credentials.json', 'home-codex-auth.json', 'project-mcp.json'}
# Paths the isolated profile must ignore, as run-case.sh writes them.
REQUIRED_TARGETS = {
    'HOME/.claude/settings.json', 'HOME/.claude/CLAUDE.md', 'HOME/.claude/.credentials.json',
    'HOME/.claude.json', 'HOME/.codex/auth.json', 'WORKSPACE/.claude/settings.json',
    'WORKSPACE/.claude/settings.local.json', 'WORKSPACE/CLAUDE.md', 'WORKSPACE/CLAUDE.local.md',
    'WORKSPACE/.mcp.json'}
# Launch options of the isolated profile; each closes one surface the Docker test probes.
ISOLATED_OPTIONS = ['--tools ""', '--strict-mcp-config', '--disable-slash-commands',
                    '--setting-sources ""', '--permission-mode dontAsk', '--no-session-persistence',
                    '--system-prompt "$engine_prompt"', '--settings "$builtin_off"']
# The bundled plugins the pinned 2.1.323 binary registers; the isolated profile turns each off.
BUILTIN_PLUGINS = ['agents-md', 'claude-platform', 'claude-test', 'diff', 'easel', 'mermaid',
                   'mods-guide', 'plugin-authoring', 'privacy', 'responsive-mode', 'sec-default',
                   'telemetry', 'tips', 'you-should-know']
TRUST_KEY = 'hasTrustDialogAccepted'
# Both profiles report through the same event stream, so the probe reads both the same way.
OUTPUT_OPTIONS = '--output-format stream-json --verbose --model "$FIXTURE_MODEL"'
# Options that would add authority, extensions or hidden state (docs/33 section 4).
FORBIDDEN_OPTIONS = ['--dangerously-skip-permissions', '--allow-dangerously-skip-permissions',
                     '--mcp-config', '--add-dir', '--plugin-dir', '--agents', '--continue', '--resume',
                     '--allowedTools', '--allowed-tools', '--bare', '--fallback-model',
                     'CLAUDE_CONFIG_DIR', '--plugin-url', '--setting-sources user',
                     '--setting-sources project', '--setting-sources local']


def plant_table():
    """Return the ``(fixture file, target)`` pairs of run-case.sh's plant table, in order."""
    text = RUNNER.read_text(encoding='utf-8')
    table = text.split("<<'TABLE'\n", 1)[1].split('\nTABLE\n', 1)[0]
    return [tuple(line.split()) for line in table.splitlines()]


def profile_block(name):
    """Return the text of one ``case $profile`` branch of run-case.sh, up to its ``;;``."""
    text = RUNNER.read_text(encoding='utf-8').replace('\\\n', ' ')
    return re.search(r'^  ' + name + r'\)\n(.*?)^    ;;$', text, re.M | re.S).group(1)


def shell_value(name):
    """Return the single-quoted value of a top-level ``name='...'`` assignment in run-case.sh."""
    return re.search(r"^" + name + r"='([^']*)'$", RUNNER.read_text(encoding='utf-8'), re.M).group(1)


def parse_report(raw):
    """Parse run-case.sh's report; raise AssertionError when it is incomplete."""
    report = {'markers': [], 'created': [], 'secrets': [], 'canonical': None, 'end': False}
    for line in raw.decode(errors='replace').splitlines():
        key, _, value = line.partition(' ')
        if key == 'status':
            report['status'] = int(value)
        elif key in ('stdout', 'stderr'):
            report[key] = base64.b64decode(value)
        elif key == 'marker':
            report['markers'].append(value)
        elif key == 'canonical':
            report['canonical'] = value == 'present'
        elif key == 'created':
            report['created'].append(value)
        elif key == 'secret':
            report['secrets'].append(value)
        elif key == 'end':
            report['end'] = True
    if not report['end'] or 'status' not in report:
        raise AssertionError('incomplete case report: ' + raw.decode(errors='replace')[-2000:])
    return report


def events(stdout):
    """Return the JSON objects of a stream-json stdout, skipping any non-JSON line."""
    found = []
    for line in stdout.decode(errors='replace').splitlines():
        try:
            value = json.loads(line)
        except ValueError:
            continue
        if isinstance(value, dict):
            found.append(value)
    return found


def tool_results(body):
    """Return every ``tool_result`` block in the messages of a request body (a dict or None)."""
    blocks = []
    for message in (body or {}).get('messages', []):
        content = message.get('content')
        if isinstance(content, list):
            blocks += [block for block in content
                       if isinstance(block, dict) and block.get('type') == 'tool_result']
    return blocks


class FixtureFileTests(unittest.TestCase):
    """Check the fixture and its launch profiles without Docker."""

    def test_plant_table_covers_every_fixture_file_and_surface(self):
        """Every fixture file is planted once, at a distinct path, covering every probed surface."""
        pairs = plant_table()
        sources = [source for source, _ in pairs]
        targets = [target for _, target in pairs]
        on_disk = sorted(path.name for path in FIXTURE.iterdir() if path.name != 'run-case.sh')
        self.assertEqual(sorted(sources), on_disk)
        self.assertEqual(len(set(targets)), len(targets))
        self.assertLessEqual(REQUIRED_TARGETS, set(targets))
        for surface in ('skills/', 'commands/', 'agents/'):
            for scope in ('HOME/.claude/', 'WORKSPACE/.claude/'):
                with self.subTest(surface=scope + surface):
                    self.assertTrue(any(target.startswith(scope + surface) for target in targets))

    def test_fixture_files_are_lf_and_carry_their_sentinels(self):
        """Each planted file is LF text holding a sentinel; credentials only where expected."""
        for path in sorted(FIXTURE.iterdir()):
            with self.subTest(file=path.name):
                text = path.read_bytes().decode('utf-8')
                self.assertNotIn('\r', text)
                if path.name == 'run-case.sh':
                    self.assertTrue(text.startswith('#!/bin/sh\n'))
                    continue
                if path.suffix == '.json':
                    json.loads(text)
                self.assertTrue(INHERITED in text or SECRET in text or 'touch /tmp/markers/' in text)
                self.assertEqual(SECRET in text, path.name in SECRET_FILES)

    def test_hooks_and_mcp_servers_leave_distinct_markers(self):
        """Every planted hook and MCP server touches its own marker, so the probe can tell them apart."""
        markers = []
        for name in ('home-settings.json', 'project-settings.json', 'home-global-config.json',
                     'project-mcp.json'):
            markers += re.findall(r'touch /tmp/markers/([\w-]+)', (FIXTURE / name).read_text(encoding='utf-8'))
        self.assertEqual(len(markers), len(set(markers)))
        self.assertIn('mcp-home', markers)
        self.assertIn('mcp-project', markers)
        self.assertEqual(sum(marker.startswith('hook-home-') for marker in markers), 4)
        self.assertEqual(sum(marker.startswith('hook-project-') for marker in markers), 4)

    def test_isolated_profile_closes_every_surface_and_unrestricted_is_the_default(self):
        """The isolated branch names every closing option; no branch widens authority."""
        isolated = ' '.join(profile_block('isolated').split())
        unrestricted = ' '.join(profile_block('unrestricted').split())
        for option in ISOLATED_OPTIONS:
            with self.subTest(option=option):
                self.assertIn(option, isolated)
                self.assertNotIn(option.split()[0], unrestricted)
        self.assertIn('ANTHROPIC_API_KEY="$FIXTURE_API_KEY"', isolated)
        self.assertIn('CLAUDE_CODE_OAUTH_TOKEN=secret-sentinel-fixture-', isolated)
        self.assertIn('OPENAI_API_KEY=secret-sentinel-fixture-', isolated)
        self.assertNotIn('ANTHROPIC_API_KEY', unrestricted)
        # The prompt must directly follow -p; a list option would absorb it otherwise.
        for block in (isolated, unrestricted):
            self.assertIn('/usr/local/bin/claude -p "$prompt" ', block)
            self.assertIn(OUTPUT_OPTIONS, block)
        script = RUNNER.read_text(encoding='utf-8')
        for option in FORBIDDEN_OPTIONS:
            with self.subTest(forbidden=option):
                self.assertNotIn(option, script)
        self.assertIn(f"engine_prompt='{ENGINE_PROMPT}'", script)

    def test_isolated_settings_only_turn_builtin_plugins_off(self):
        """The one inline setting of the isolated profile disables bundled plugins and adds nothing.

        `--settings` could otherwise widen the profile (hooks, permissions, MCP, a key helper),
        so the value must be exactly the `enabledPlugins` map with every built-in id set to false.
        """
        code = [line for line in RUNNER.read_text(encoding='utf-8').splitlines()
                if not line.lstrip().startswith('#')]
        self.assertEqual(sum(line.count('--settings') for line in code), 1)
        self.assertIn('--settings "$builtin_off"', ' '.join(profile_block('isolated').split()))
        value = json.loads(shell_value('builtin_off'))
        self.assertEqual(list(value), ['enabledPlugins'])
        self.assertEqual(value['enabledPlugins'],
                         {f'cc-plugin-{name}@builtin': False for name in BUILTIN_PLUGINS})

    def test_workspace_trust_reaches_only_the_positive_control(self):
        """Only the unrestricted branch marks the workspace trusted; no fixture file does.

        The control needs the trust so the project's permissions.allow entries apply; the
        isolated profile must be proven without it. The edit must leave valid JSON that keeps
        the planted home MCP server.
        """
        unrestricted = profile_block('unrestricted')
        self.assertIn(TRUST_KEY, unrestricted)
        self.assertNotIn(TRUST_KEY, profile_block('isolated'))
        self.assertEqual(RUNNER.read_text(encoding='utf-8').count(TRUST_KEY), 1)
        for path in sorted(FIXTURE.iterdir()):
            if path.name != 'run-case.sh':
                with self.subTest(file=path.name):
                    self.assertNotIn(TRUST_KEY, path.read_text(encoding='utf-8'))
        pattern, replacement = re.search(r"sed -i '1s\|(.*?)\|(.*?)\|'", unrestricted).groups()
        lines = (FIXTURE / 'home-global-config.json').read_text(encoding='utf-8').split('\n')
        self.assertRegex(lines[0], pattern)
        edited = json.loads('\n'.join([replacement, *lines[1:]]))
        self.assertEqual(edited['projects'], {'/var/lib/runtime/workspace': {TRUST_KEY: True}})
        self.assertIn('inherited-sentinel-home-mcp', edited['mcpServers'])

    def test_compose_fixture_is_offline_unprivileged_and_pinned(self):
        """The fixture has no external route or port, and uses the pinned client and JDK images."""
        text = COMPOSE_FILE.read_text(encoding='utf-8')
        version = re.search(r'^ARG CLIENT_VERSION=(\S+)$',
                            CLIENT_DOCKERFILE.read_text(encoding='utf-8'), re.M).group(1)
        session = (HERE / 'session.compose.yaml').read_text(encoding='utf-8')
        jdk = re.search(r'^\s+image: (eclipse-temurin\S+)', session, re.M).group(1)
        self.assertIn(f'    image: ai-rpg-engine-runtime-probe/claude:{version}\n', text)
        self.assertIn(f'    image: {jdk}\n', text)
        self.assertIn('  fixture:\n    internal: true\n', text)
        self.assertNotRegex(text, r'(?m)^\s+(ports|network_mode|privileged|cap_add):')
        self.assertEqual(text.count('read_only: true'), 2)
        self.assertEqual(text.count('cap_drop: [ALL]'), 2)
        self.assertEqual(text.count('user: "10001:10001"'), 2)
        self.assertIn('ANTHROPIC_BASE_URL: http://provider:8080\n', text)
        self.assertIn(f'FIXTURE_API_KEY: {FIXTURE_KEY}\n', text)
        self.assertIn('./claude_isolation_fixture:/fixture:ro', text)
        self.assertNotIn(SECRET, text)

    def test_repository_secret_scan_accepts_every_fixture_file(self):
        """The sentinels read as fixture placeholders to the repository's secret and path scan.

        A sentinel that looked like a credential would make that scan refuse the fixture,
        and a real-looking value must never be needed to prove isolation.
        """
        spec = importlib.util.spec_from_file_location(
            'repository_checks', ROOT / 'tools' / 'repository_checks' / 'checks.py')
        checks = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checks)
        files = [*sorted(FIXTURE.iterdir()), COMPOSE_FILE, PROVIDER, Path(__file__).resolve()]
        for path in files:
            relative = path.relative_to(ROOT).as_posix()
            with self.subTest(file=relative):
                self.assertEqual(checks.scan_path(relative), [])
                self.assertEqual(checks.scan_text(relative, path.read_text(encoding='utf-8')), [])

    def test_scripted_upstream_matches_the_probe(self):
        """The upstream scripts the expected calls and text, and holds no sentinel itself."""
        source = PROVIDER.read_text(encoding='utf-8')
        self.assertEqual(re.findall(r'\{"(toolu_probe_\w+)", "(\w+)"', source),
                         list(zip(TOOL_IDS, ['Bash', 'Write', 'Read', 'WebFetch'])))
        self.assertIn(f'NEUTRAL_TEXT = "{NEUTRAL_TEXT}";', source)
        self.assertIn('CANONICAL_SENTINEL = "/var/lib/runtime/workspace/canonical-sentinel.json";', source)
        self.assertNotIn(SECRET, source)
        self.assertNotIn(INHERITED, source)


# The Docker checks exist only when RUNTIME_PROBE_DOCKER names the Docker CLI, which
# `.agents/ralph/verify.sh containers` always sets for this suite; without it they are not
# defined at all, so nothing here is reported as skipped or as passed.
if DOCKER:
    class DockerClientIsolationTests(unittest.TestCase):
        """Run the real pinned client against the scripted upstream; no account, no credential."""

        COMPOSE = [DOCKER, 'compose', '-p', PROJECT, '-f', str(COMPOSE_FILE)]

        @classmethod
        def setUpClass(cls):
            """Build (or reuse from cache) the P20.1-A client image the fixture runs."""
            version = re.search(r'^ARG CLIENT_VERSION=(\S+)$',
                                CLIENT_DOCKERFILE.read_text(encoding='utf-8'), re.M).group(1)
            done = subprocess.run(
                [DOCKER, 'build', '--quiet', '--target', 'client', '--tag',
                 f'ai-rpg-engine-runtime-probe/claude:{version}', str(CLIENT_DOCKERFILE.parent)],
                capture_output=True, timeout=1200)
            if done.returncode != 0:
                raise AssertionError('client build failed: ' + done.stderr.decode(errors='replace')[-3000:])

        @contextmanager
        def fixture(self):
            """Start a fresh scripted upstream, yield, then stop the fixture without deleting volumes.

            The provider is always recreated: a container left by an interrupted run would keep
            its old request log, and the positive control's sentinels would then appear to leak.
            """
            try:
                started = subprocess.run([*self.COMPOSE, 'up', '-d', '--wait', '--force-recreate', 'provider'],
                                         capture_output=True, timeout=240)
                if started.returncode:
                    self.fail('provider did not start: ' + started.stderr.decode(errors='replace')[-2000:])
                yield
            finally:
                subprocess.run([*self.COMPOSE, 'down', '--timeout', '10'], check=True,
                               capture_output=True, timeout=120)

        def run_case(self, profile, plant, scenario):
            """Run one client case and return its parsed report."""
            done = subprocess.run([*self.COMPOSE, 'run', '--rm', '-T', '--no-deps', 'client',
                                   profile, plant, scenario],
                                  stdin=subprocess.DEVNULL, capture_output=True, timeout=240)
            self.assertEqual(done.returncode, 0, done.stderr.decode(errors='replace')[-2000:])
            return parse_report(done.stdout)

        def requests(self):
            """Return every request the scripted upstream logged, in arrival order."""
            logs = subprocess.check_output([*self.COMPOSE, 'logs', '--no-log-prefix', 'provider'],
                                           timeout=30).decode(errors='replace')
            found = []
            for line in logs.splitlines():
                if not line.startswith('request '):
                    continue
                _, method, path, kind, headers, body = line.split(' ')
                raw = base64.b64decode(body)
                try:
                    parsed = json.loads(raw)
                except ValueError:
                    parsed = None
                found.append({'method': method, 'path': path, 'kind': kind, 'raw': raw,
                              'headers': json.loads(base64.b64decode(headers)), 'body': parsed})
            return found

        @staticmethod
        def diagnosis(report):
            """Return the case's exit status and output tails for failure messages."""
            return (f"status {report['status']}; stderr: "
                    + report.get('stderr', b'').decode(errors='replace')[-1500:]
                    + '; stdout: ' + report.get('stdout', b'').decode(errors='replace')[-1500:])

        def test_scripted_upstream_compiles_without_warnings_on_pinned_jdk(self):
            """MessagesProvider.java compiles cleanly on the fixture's pinned JDK image."""
            image = re.search(r'^\s+image: (eclipse-temurin\S+)',
                              COMPOSE_FILE.read_text(encoding='utf-8'), re.M).group(1)
            done = subprocess.run(
                [DOCKER, 'run', '--rm', '--network', 'none', '--read-only', '--tmpfs', '/tmp',
                 '--user', '10001:10001', '--env', 'HOME=/tmp', '--volume', f'{HERE}:/src:ro', image,
                 'javac', '-Xlint:all', '-Werror', '-d', '/tmp/out', '/src/MessagesProvider.java'],
                capture_output=True, timeout=600)
            self.assertEqual(done.returncode, 0, done.stderr.decode(errors='replace')[-2000:])

        def test_unrestricted_client_exposes_every_probed_surface(self):
            """Positive control: with its defaults, the client shows every surface the probes watch.

            Each subtest is the detection that a denial in the isolated test relies on, so a
            probe that could never fire cannot make that denial pass.
            """
            with self.fixture():
                report = self.run_case('unrestricted', 'all', 'scenario-tools')
                requests = self.requests()
            why = self.diagnosis(report)
            messages = [request for request in requests if request['path'].endswith('/v1/messages')]
            self.assertTrue(messages, 'the client sent no request upstream; ' + why)
            bodies = b'\n'.join(request['raw'] for request in messages).decode(errors='replace')
            seen = bodies + report['stdout'].decode(errors='replace')
            offered = {tool.get('name') for request in messages
                       for tool in (request['body'] or {}).get('tools') or []}
            inits = [e for e in events(report['stdout'])
                     if e.get('type') == 'system' and e.get('subtype') == 'init']
            builtin = {plugin.get('source') for init in inits for plugin in init.get('plugins') or []
                       if str(plugin.get('source', '')).endswith('@builtin')}
            disabled = set(json.loads(shell_value('builtin_off'))['enabledPlugins'])
            checks = {
                'built-in plugins load': bool(builtin),
                'the isolated settings name every loaded built-in plugin': builtin <= disabled,
                'tools are offered upstream': 'Bash' in offered,
                'a scripted host command runs': 'tool-bash' in report['markers'] and report['canonical'],
                'home hooks run': any(m.startswith('hook-home-') for m in report['markers']),
                'project hooks run': any(m.startswith('hook-project-') for m in report['markers']),
                'the home MCP server starts': 'mcp-home' in report['markers'],
                'the project MCP server starts': 'mcp-project' in report['markers'],
                'home instructions reach the model': 'inherited-sentinel-home-memory' in bodies,
                'project instructions reach the model': 'inherited-sentinel-project-memory' in bodies,
                'home skills are discovered': 'inherited-sentinel-home-skill' in seen,
                'project skills are discovered': 'inherited-sentinel-project-skill' in seen,
                'a configured credential is sent': any(
                    SECRET in json.dumps(request['headers']) for request in messages),
            }
            for name, detected in checks.items():
                with self.subTest(probe=name):
                    self.assertTrue(detected, why)

        def test_isolated_profile_refuses_tools_extensions_configuration_and_credentials(self):
            """The isolated profile completes neutral turns while exposing no probed surface.

            Case 1 (empty home, plain answer) shows the profile itself works. Case 2 plants every
            home and project file, puts subscription and first-runtime credentials in the
            environment, and has the upstream request four host tools. Both cases must complete
            with the neutral text, offer no tool, use only the fixture key, run no hook, MCP
            server or tool, write nothing canonical and expose no sentinel anywhere.
            """
            with self.fixture():
                empty = self.run_case('isolated', 'none', 'scenario-complete')
                first = len(self.requests())
                planted = self.run_case('isolated', 'all', 'scenario-tools')
                requests = self.requests()
            for name, report, own in (('empty', empty, requests[:first]),
                                      ('planted', planted, requests[first:])):
                with self.subTest(case=name):
                    self.assert_isolated_case(report, own)
            # The planted case answered every scripted call, each with an error result.
            results = [block for request in requests[first:] for block in tool_results(request['body'])]
            self.assertEqual(sorted({block.get('tool_use_id') for block in results}), sorted(TOOL_IDS),
                             self.diagnosis(planted))
            for block in results:
                with self.subTest(tool=block.get('tool_use_id')):
                    self.assertIs(block.get('is_error'), True, json.dumps(block)[:500])

        def assert_isolated_case(self, report, requests):
            """Require a completed neutral turn and no trace of any probed surface in one case."""
            why = self.diagnosis(report)
            self.assertEqual(report['status'], 0, why)
            found = events(report['stdout'])
            inits = [e for e in found if e.get('type') == 'system' and e.get('subtype') == 'init']
            results = [e for e in found if e.get('type') == 'result']
            self.assertEqual(len(inits), 1, why)
            self.assertTrue(results, why)
            self.assertEqual((results[-1].get('subtype'), results[-1].get('is_error'),
                              results[-1].get('result')), ('success', False, NEUTRAL_TEXT), why)
            self.assertEqual(inits[0].get('tools'), [], 'the client must report no tools')
            self.assertEqual(inits[0].get('mcp_servers', []), [], 'the client must start no MCP server')
            self.assertEqual(inits[0].get('plugins', []), [], 'the client must load no plugin')
            messages = [request for request in requests if request['path'].endswith('/v1/messages')]
            self.assertTrue(messages, why)
            for request in requests:
                with self.subTest(path=request['path'], kind=request['kind']):
                    headers = json.dumps(request['headers'])
                    raw = request['raw'].decode(errors='replace')
                    self.assertNotIn(SECRET, headers + raw)
                    self.assertNotIn(INHERITED, headers + raw)
                    self.assertNotIn('webfetch-probe', request['path'])
            for request in messages:
                body = request['body'] or {}
                self.assertIn(body.get('tools'), (None, []), 'the model must be offered no tool')
                self.assertEqual(request['headers'].get('x-api-key'), [FIXTURE_KEY])
            # Auxiliary requests may carry their own system text; the turn itself carries the engine's.
            self.assertTrue(any(ENGINE_PROMPT in json.dumps((request['body'] or {}).get('system'))
                                for request in messages), why)
            output = (report['stdout'] + report['stderr']).decode(errors='replace')
            self.assertNotIn(SECRET, output)
            self.assertNotIn(INHERITED, output)
            self.assertEqual(report['markers'], [], 'no hook, MCP server or tool may run')
            self.assertIs(report['canonical'], False, 'no write may reach the workspace')
            self.assertEqual(report['secrets'], [], 'no new file may hold a credential sentinel')


if __name__ == '__main__':
    unittest.main()
