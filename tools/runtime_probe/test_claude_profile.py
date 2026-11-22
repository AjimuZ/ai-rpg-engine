"""Launch profile, supervisor protocol, signals, readiness and retention of the second narration client (P20.1-C).

The engine-owned supervisor (claude_supervisor/supervise.sh) is the only way a task reaches the
pinned client: the caller sends task data, and the supervisor builds the fixed command line and
an allowlisted environment. The Docker checks send tasks through it to the unmodified client,
whose documented base-URL override points at the scripted upstream (MessagesProvider.java) on an
internal network, with no account and no real credential.

- ``ProfileFileTests`` always run. They keep the supervisor's template, environment, settings and
  schema, the Compose fixture, the case runner and the upstream scenarios consistent with what
  the Docker checks assume, and they show that the check functions reject a wrong report.
- ``DockerProfileTests`` exist only when RUNTIME_PROBE_DOCKER names the Docker CLI. Each runs one
  case of claude_profile_fixture/run-profile.sh against a fresh upstream and applies one check
  function below: refusals start nothing; output, error and limit signals in API-key mode; model
  and effort readiness; limit and overage events over a planted fixture subscription login; and
  session retention, resume and purge, after which nothing of the session is left.

Each EXPECTED table records what the pinned 2.1.323 client was observed to report; the tables
are the evidence behind docs/33-claude-runtime-integration.md sections 3, 8 and 9.
"""
from collections import namedtuple
from contextlib import contextmanager
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SUPERVISOR_DIR = HERE / 'claude_supervisor'
SUPERVISOR = SUPERVISOR_DIR / 'supervise.sh'
FIXTURE = HERE / 'claude_profile_fixture'
RUNNER = FIXTURE / 'run-profile.sh'
COMPOSE_FILE = HERE / 'claude_profile.compose.yaml'
PROVIDER = HERE / 'MessagesProvider.java'
ISOLATION_RUNNER = HERE / 'claude_isolation_fixture' / 'run-case.sh'
CLIENT_DOCKERFILE = HERE / 'claude_client' / 'Dockerfile'
DOCKER = os.environ.get('RUNTIME_PROBE_DOCKER')

PROJECT = 'ai-rpg-engine-claude-profile'
NEUTRAL_TEXT = 'The marker is blue.'
FIXTURE_KEY = 'fixture-api-key-not-a-credential'
LOGIN_TOKEN = 'fixture-subscription-access-not-a-credential'
MODEL = 'claude-sonnet-5-5'
SESSION = '0f8e3c2a-5b6d-4e7f-8a9b-0c1d2e3f4a5b'
HOME = '/var/lib/runtime/home'
TRANSCRIPT = f'{HOME}/.claude/projects/-var-lib-runtime-workspace/{SESSION}.jsonl'
RESET_AT = 4102444800
STRUCTURED = {'marker': 'blue'}

# Options of the fixed template; each closes one surface (P20.1-B) or fixes one task value.
TEMPLATE_OPTIONS = ['-p --output-format stream-json --verbose', '--model "$model"', '--effort "$effort"',
                    '--tools ""', '--strict-mcp-config', '--disable-slash-commands', '--setting-sources ""',
                    '--permission-mode dontAsk', '--system-prompt "$engine_prompt"', '--settings "$settings"',
                    '--json-schema "$schema_json"', "session_args='--no-session-persistence'",
                    'session_args="--session-id ${session#new:}"', 'session_args="--resume ${session#resume:}"']
# Options that would add authority, extensions, hidden state or a silent model change.
FORBIDDEN_OPTIONS = ['--dangerously-skip-permissions', '--allow-dangerously-skip-permissions', '--mcp-config',
                     '--add-dir', '--plugin-dir', '--plugin-url', '--agents', '--continue', '--allowedTools',
                     '--allowed-tools', '--bare', '--fallback-model', '--fork-session', 'CLAUDE_CONFIG_DIR',
                     'CLAUDE_CODE_RESUME_INTERRUPTED_TURN', '--bg', '--cloud', '--remote-control', '--teleport']
# The client's whole environment, in the supervisor's order, before the two conditional entries.
ENVIRONMENT = ['HOME="$HOME"', 'PATH=/usr/bin:/bin', 'LANG=C.UTF-8', 'NO_COLOR=1', 'DISABLE_TELEMETRY=1',
               'DISABLE_ERROR_REPORTING=1', 'DISABLE_AUTOUPDATER=1', 'DISABLE_FEEDBACK_COMMAND=1',
               'CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY=1', 'CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1',
               'CLAUDE_CODE_DISABLE_AUTO_MEMORY=1', 'CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP=1',
               'CLAUDE_CODE_MAX_RETRIES=0']

# Refusals: step name -> (exit status, the one stderr line). Nothing may reach the upstream.
REFUSALS = {
    'usage': (64, 'supervisor-refused usage'),
    'operation': (64, 'supervisor-refused operation'),
    'extra-argument': (64, 'supervisor-refused usage'),
    'session-shape': (64, 'supervisor-refused session'),
    'session-upper-case': (64, 'supervisor-refused session'),
    'model-option': (64, 'supervisor-refused model'),
    'model-with-space': (64, 'supervisor-refused model'),
    'effort-unknown': (64, 'supervisor-refused effort'),
    'schema-path': (64, 'supervisor-refused schema'),
    'schema-absent': (64, 'supervisor-refused schema'),
    'prompt-empty': (64, 'supervisor-refused prompt-empty'),
    'prompt-size': (64, 'supervisor-refused prompt-size'),
    'auth-mode': (64, 'supervisor-refused auth-mode'),
    'api-key-file': (64, 'supervisor-refused api-key-file'),
    'retire-shape': (64, 'supervisor-refused session'),
    'guard-oauth-variable': (66, 'supervisor-guard subscription-credentials-in-api-key-mode'),
    'guard-stored-login': (66, 'supervisor-guard subscription-credentials-in-api-key-mode'),
    'guard-api-key-variable': (66, 'supervisor-guard api-credentials-in-subscription-mode'),
    'guard-api-key-file': (66, 'supervisor-guard api-credentials-in-subscription-mode'),
}

# What one task reported: client exit status, the `error` of its last assistant event, the
# result's terminal_reason, is_error and api_error_status, the number of upstream requests it
# caused, and its rate_limit_event infos in order.
Signal = namedtuple('Signal', 'status error terminal is_error http requests limits')
# API-key mode, CLAUDE_CODE_MAX_RETRIES=0. The result subtype is "success" in every row, errors
# included: only is_error and terminal_reason tell them apart. No row has a rate_limit_event.
SIGNALS = {
    'schema': Signal(0, None, 'completed', False, None, 1, []),
    'schema-unanswered': Signal(0, None, 'completed', False, None, 2, []),
    'prompt-is-data': Signal(0, None, 'completed', False, None, 1, []),
    'auth': Signal(1, 'authentication_failed', 'api_error', True, 401, 1, []),
    'billing': Signal(1, 'billing_error', 'api_error', True, 400, 1, []),
    'invalid': Signal(1, 'unknown', 'api_error', True, 400, 3, []),
    'toolong': Signal(1, 'invalid_request', 'prompt_too_long', True, 400, 1, []),
    'overloaded': Signal(1, 'server_error', 'api_error', True, 529, 1, []),
    'server': Signal(1, 'server_error', 'api_error', True, 500, 1, []),
    'ratelimit': Signal(1, 'rate_limit', 'api_error', True, 429, 1, []),
    'overage': Signal(0, None, 'completed', False, None, 1, []),
    'maxtokens': Signal(1, 'max_output_tokens', 'api_error', True, None, 4, []),
    'model': Signal(1, 'model_not_found', 'api_error', True, 404, 2, []),
}
LIMIT_REJECTED = {'status': 'rejected', 'resetsAt': RESET_AT, 'rateLimitType': 'five_hour',
                  'overageStatus': 'rejected', 'isUsingOverage': False}
LIMIT_OVERAGE = {'status': 'rejected', 'resetsAt': RESET_AT, 'rateLimitType': 'five_hour',
                 'overageStatus': 'allowed', 'overageResetsAt': RESET_AT, 'isUsingOverage': True,
                 'overageInUse': True}
# Subscription shape: a planted fixture login, no key. Rate-limit events appear only here.
SUBSCRIPTION = {
    'complete': Signal(0, None, 'completed', False, None, 1, [{'status': 'allowed', 'isUsingOverage': False}]),
    'ratelimit': Signal(1, 'rate_limit', 'api_error', True, 429, 1, [LIMIT_REJECTED]),
    'overage': Signal(0, None, 'completed', False, None, 1, [LIMIT_OVERAGE]),
    'auth': Signal(1, 'authentication_failed', 'api_error', True, 401, 2, []),
    'billing': Signal(1, 'billing_error', 'api_error', True, 400, 1, []),
}
# Readiness verdict per model@effort pair of readiness-pairs.txt (see readiness_verdict).
READINESS = {
    'claude-sonnet-5-5@high': 'ready',
    'claude-opus-5-5@max': 'ready',
    'claude-haiku-5-5@low': 'ready',
    'claude-fable-5-2@xhigh': 'ready',
    'claude-sonnet-4-5@medium': 'effort-dropped',
    'claude-opus-4-1@low': 'effort-dropped',
    'claude-unlisted-fixture@high': 'model-not-found',
}


def parse_report(raw):
    """Parse run-profile.sh's report into steps and extra lines; raise AssertionError when incomplete."""
    report = {'steps': [], 'lines': {}, 'end': False}
    for line in raw.decode(errors='replace').splitlines():
        key, _, value = line.partition(' ')
        if key == 'step':
            report['steps'].append({'name': value})
        elif key == 'status' and report['steps']:
            report['steps'][-1]['status'] = int(value)
        elif key in ('stdout', 'stderr') and report['steps']:
            report['steps'][-1][key] = base64.b64decode(value)
        elif key == 'end':
            report['end'] = True
        elif key in ('stored', 'plaintext', 'before', 'found', 'file'):
            report['lines'].setdefault(key, []).append(value)
    if not report['end'] or any(set(step) != {'name', 'status', 'stdout', 'stderr'} for step in report['steps']):
        raise AssertionError('incomplete case report: ' + raw.decode(errors='replace')[-2000:])
    return report


def parse_requests(log):
    """Return the upstream's logged requests in arrival order, from its stdout text."""
    found = []
    for line in log.splitlines():
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


def own_requests(requests, name):
    """Return the Messages requests caused by one step: those whose body carries its prompt token."""
    token = f'[step-{name}]'.encode()
    return [request for request in requests
            if request['path'].endswith('/v1/messages') and token in request['raw']]


def summary(step, requests):
    """Reduce one task step to its Signal, plus its init and result events and api_retry errors."""
    found = events(step['stdout'])
    inits = [e for e in found if e.get('type') == 'system' and e.get('subtype') == 'init']
    results = [e for e in found if e.get('type') == 'result']
    errors = [e.get('error') for e in found if e.get('type') == 'assistant' and e.get('error')]
    result = results[-1] if results else {}
    signal = Signal(step['status'], errors[-1] if errors else None, result.get('terminal_reason'),
                    result.get('is_error'), result.get('api_error_status'),
                    len(own_requests(requests, step['name'])),
                    [e.get('rate_limit_info') for e in found if e.get('type') == 'rate_limit_event'])
    retries = [e.get('error') for e in found if e.get('type') == 'system' and e.get('subtype') == 'api_retry']
    return signal, inits, result, retries, found


def offered_tools(request):
    """Return the tool names one request offers the model."""
    return [tool.get('name') for tool in (request['body'] or {}).get('tools') or []]


def diagnosis(step):
    """Return a step's status and output tails for failure messages."""
    return (f"step {step['name']} status {step['status']}; stderr: "
            + step['stderr'].decode(errors='replace')[-800:]
            + '; stdout: ' + step['stdout'].decode(errors='replace')[-1500:])


def check_refusals(tc, report, requests):
    """Every malformed request and guard case exits with its code and line, and starts nothing."""
    tc.assertEqual([step['name'] for step in report['steps']], list(REFUSALS))
    for step in report['steps']:
        with tc.subTest(step=step['name']):
            status, line = REFUSALS[step['name']]
            tc.assertEqual((step['status'], step['stderr'], step['stdout']),
                           (status, (line + '\n').encode(), b''), diagnosis(step))
    tc.assertEqual(requests, [], 'a refused request reached the upstream')


def check_task_common(tc, step, requests, inits, model, auth):
    """One init event naming the requested model, no extension, and only the expected credential."""
    why = diagnosis(step)
    tc.assertEqual(len(inits), 1, why)
    init = inits[0]
    tc.assertEqual(init.get('model'), model, why)
    tc.assertEqual((init.get('mcp_servers'), init.get('plugins'), init.get('skills'), init.get('slash_commands')),
                   ([], [], [], []), why)
    tc.assertEqual(init.get('apiKeySource'), 'ANTHROPIC_API_KEY' if auth == 'api-key' else 'none', why)
    for request in own_requests(requests, step['name']):
        headers = request['headers']
        if auth == 'api-key':
            tc.assertEqual((headers.get('x-api-key'), headers.get('authorization')), ([FIXTURE_KEY], None))
        else:
            tc.assertEqual((headers.get('x-api-key'), headers.get('authorization')),
                           (None, ['Bearer ' + LOGIN_TOKEN]))
        tc.assertEqual(request['body'].get('model'), model)


def check_signals(tc, report, requests):
    """API-key mode: each scenario reports exactly the recorded signal, with no rate-limit event."""
    tc.assertEqual([step['name'] for step in report['steps']], list(SIGNALS))
    for step in report['steps']:
        with tc.subTest(step=step['name']):
            name = step['name']
            signal, inits, result, retries, _ = summary(step, requests)
            model = 'claude-unlisted-fixture' if name == 'model' else MODEL
            tc.assertEqual(signal, SIGNALS[name], diagnosis(step))
            tc.assertEqual(result.get('subtype'), 'success', diagnosis(step))
            tc.assertEqual(retries, [], 'the client retried on its own')
            check_task_common(tc, step, requests, inits, model, 'api-key')
            own = own_requests(requests, name)
            if name.startswith('schema'):
                # A schema adds the client's own StructuredOutput tool; nothing else is offered.
                tc.assertEqual(inits[0].get('tools'), ['StructuredOutput'], diagnosis(step))
                for request in own:
                    tc.assertIn('StructuredOutput', offered_tools(request))
                    tc.assertLessEqual(set(offered_tools(request)), {'StructuredOutput', 'DeferredToolPlaceholder'})
            else:
                tc.assertEqual(inits[0].get('tools'), [], diagnosis(step))
                for request in own:
                    tc.assertEqual(offered_tools(request), [])
            if name == 'schema':
                tc.assertEqual(result.get('structured_output'), STRUCTURED, diagnosis(step))
            else:
                tc.assertNotIn('structured_output', result, 'only a StructuredOutput call yields output')
            if name in ('schema-unanswered', 'prompt-is-data', 'overage'):
                tc.assertEqual(result.get('result'), NEUTRAL_TEXT, diagnosis(step))
            if name == 'prompt-is-data':
                # The prompt reached the model as text; the client did not read it as an option.
                tc.assertIn(b'"--version Answer the neutral protocol check.', own[0]['raw'])
                tc.assertNotIn(b'(Claude Code)', step['stdout'])


def readiness_verdict(step, requests, model, effort):
    """Return the readiness verdict of one neutral readiness task for model@effort.

    ``ready``: the task completed, init and every request named exactly the requested model (no
    remap), and every request carried the requested effort as ``output_config.effort``.
    ``effort-dropped``: it completed, but a request carried no effort or another one: the client
    silently ignores the effort for that model, so the pair must not be approved.
    ``model-not-found``: the client reported ``model_not_found``. ``failed``: anything else.
    """
    signal, inits, _, _, _ = summary(step, requests)
    own = own_requests(requests, step['name'])
    if signal.error == 'model_not_found':
        return 'model-not-found'
    if (signal.status, signal.is_error, signal.terminal) != (0, False, 'completed') or not own or len(inits) != 1:
        return 'failed'
    if inits[0].get('model') != model or any(request['body'].get('model') != model for request in own):
        return 'failed'
    if any((request['body'].get('output_config') or {}).get('effort') != effort for request in own):
        return 'effort-dropped'
    return 'ready'


def check_readiness(tc, report, requests):
    """Each pair gets its recorded verdict, and no request names a model other than the one asked for."""
    tc.assertEqual([step['name'] for step in report['steps']], list(READINESS))
    for step in report['steps']:
        with tc.subTest(pair=step['name']):
            model, effort = step['name'].split('@')
            tc.assertEqual(readiness_verdict(step, requests, model, effort), READINESS[step['name']], diagnosis(step))
            for request in own_requests(requests, step['name']):
                tc.assertEqual(request['body'].get('model'), model, 'the client remapped the model')


def check_subscription(tc, report, requests):
    """Subscription shape: each scenario reports its signal; limit and overage arrive as events."""
    tc.assertEqual([step['name'] for step in report['steps']], list(SUBSCRIPTION))
    for step in report['steps']:
        with tc.subTest(step=step['name']):
            signal, inits, result, retries, found = summary(step, requests)
            tc.assertEqual(signal, SUBSCRIPTION[step['name']], diagnosis(step))
            tc.assertEqual(result.get('subtype'), 'success', diagnosis(step))
            check_task_common(tc, step, requests, inits, MODEL, 'subscription')
            tc.assertEqual(retries, ['authentication_failed'] * 2 if step['name'] == 'auth' else [], diagnosis(step))
            kinds = [e.get('type') for e in found if e.get('type') in ('assistant', 'rate_limit_event')]
            if step['name'] == 'overage':
                # The paid answer is complete before the overage event arrives.
                tc.assertEqual(kinds, ['assistant', 'rate_limit_event'], diagnosis(step))
                tc.assertEqual(result.get('result'), NEUTRAL_TEXT, diagnosis(step))
            if step['name'] == 'ratelimit':
                tc.assertEqual(kinds, ['rate_limit_event', 'assistant'], diagnosis(step))


def check_lifecycle(tc, report, requests):
    """A session is stored in plaintext, resumed, refused when reused, purged, and leaves nothing."""
    steps = {step['name']: step for step in report['steps']}
    tc.assertEqual(list(steps), ['create', 'resume', 'reuse', 'retire', 'resume-retired', 'stateless'])
    lines = report['lines']
    for name in ('create', 'resume', 'stateless'):
        with tc.subTest(step=name):
            signal, inits, result, _, _ = summary(steps[name], requests)
            tc.assertEqual((signal.status, signal.is_error, result.get('result')), (0, False, NEUTRAL_TEXT),
                           diagnosis(steps[name]))
            check_task_common(tc, steps[name], requests, inits, MODEL, 'api-key')
            tc.assertEqual(result.get('session_id') == SESSION, name != 'stateless')
    tc.assertEqual(lines.get('stored'), [TRANSCRIPT], 'one transcript file names the session')
    tc.assertEqual(lines.get('plaintext'), [TRANSCRIPT], 'the transcript holds the prompt as plain text')
    resumed = own_requests(requests, 'resume')
    tc.assertTrue(resumed and all(b'session-sentinel-first' in request['raw'] for request in resumed),
                  'the resumed turn did not carry the first turn')
    reuse = steps['reuse']
    tc.assertEqual(reuse['status'], 1, diagnosis(reuse))
    tc.assertIn(f'Session ID {SESSION} is already in use'.encode(), reuse['stderr'])
    tc.assertIn(TRANSCRIPT, lines.get('before', []), 'the scan must see the transcript before the purge')
    retire = steps['retire']
    tc.assertEqual((retire['status'], retire['stdout'], retire['stderr']),
                   (0, f'retired {SESSION}\n'.encode(), b''), diagnosis(retire))
    after = steps['resume-retired']
    results = [e for e in events(after['stdout']) if e.get('type') == 'result']
    tc.assertEqual(after['status'], 1, diagnosis(after))
    tc.assertEqual(results[-1].get('subtype') if results else None, 'error_during_execution', diagnosis(after))
    tc.assertIn(f'No conversation found with session ID: {SESSION}', results[-1].get('errors', []))
    for name in ('reuse', 'resume-retired'):
        tc.assertEqual(own_requests(requests, name), [], f'{name} reached the upstream')
    tc.assertEqual(lines.get('found', []), [], 'something of the session is left after the purge')
    for path in lines.get('file', []):
        with tc.subTest(left=path):
            tc.assertRegex(path, r'^' + re.escape(HOME) + r'/(\.claude\.json|\.claude/backups/\.claude\.json\.backup\.\d+)$')


def code_lines(path):
    """Return the lines of a shell script that are not comments."""
    return [line for line in path.read_text(encoding='utf-8').splitlines() if not line.lstrip().startswith('#')]


class ProfileFileTests(unittest.TestCase):
    """Check the supervisor, its fixture and the check functions without Docker."""

    def test_template_is_fixed_and_closes_every_surface(self):
        """The template names every option once and no option that widens authority or hides state."""
        code = '\n'.join(code_lines(SUPERVISOR)).replace('\\\n', ' ')
        flat = ' '.join(code.split())
        for option in TEMPLATE_OPTIONS:
            with self.subTest(option=option):
                self.assertEqual(flat.count(option), 1)
        for option in FORBIDDEN_OPTIONS:
            with self.subTest(forbidden=option):
                self.assertNotIn(option, flat)
        self.assertEqual(flat.count('exec env'), 1)
        self.assertTrue(flat.endswith('exec env -i "$@" <&3 3<&-'))
        self.assertIn('client=${SUPERVISOR_CLIENT:-/usr/local/bin/claude}', flat)

    def test_prompt_is_never_an_argument(self):
        """The client parses a positional prompt such as --version as an option, so none is passed."""
        flat = ' '.join('\n'.join(code_lines(SUPERVISOR)).replace('\\\n', ' ').split())
        self.assertIn('"$client" -p --output-format', flat)
        self.assertNotIn('$prompt', flat)
        self.assertIn('head -c "$((max_prompt + 1))"', flat)
        self.assertIn('max_prompt=262144', flat)

    def test_environment_is_an_allowlist(self):
        """The client receives only the listed variables, the forwarded base URL and the mode's key."""
        flat = ' '.join('\n'.join(code_lines(SUPERVISOR)).replace('\\\n', ' ').split())
        listed = re.search(r'set -- (HOME=.*?CLAUDE_CODE_MAX_RETRIES=0)', flat).group(1).split()
        self.assertEqual(listed, ENVIRONMENT)
        self.assertIn('[ -n "${ANTHROPIC_BASE_URL:-}" ] && set -- "$@" ANTHROPIC_BASE_URL="$ANTHROPIC_BASE_URL"', flat)
        self.assertIn('[ -n "$key" ] && set -- "$@" ANTHROPIC_API_KEY="$key"', flat)
        self.assertEqual(flat.count('ANTHROPIC_API_KEY='), 1)
        self.assertNotIn('ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY"', flat)
        self.assertNotIn('CLAUDE_CODE_OAUTH_TOKEN=', flat)

    def test_settings_hold_only_the_isolation_profiles_plugin_map(self):
        """settings.json is exactly the P20.1-B `enabledPlugins` map, so both profiles stay in step."""
        settings = json.loads((SUPERVISOR_DIR / 'settings.json').read_text(encoding='utf-8'))
        isolation = re.search(r"^builtin_off='([^']*)'$", ISOLATION_RUNNER.read_text(encoding='utf-8'), re.M).group(1)
        self.assertEqual(settings, json.loads(isolation))
        self.assertEqual(list(settings), ['enabledPlugins'])
        self.assertEqual(set(settings['enabledPlugins'].values()), {False})

    def test_schema_is_closed_and_matches_the_scripted_answer(self):
        """The probe schema is a closed object that the upstream's StructuredOutput input satisfies."""
        schemas = sorted(path.name for path in (SUPERVISOR_DIR / 'schemas').iterdir())
        self.assertEqual(schemas, ['neutral-check.json'])
        schema = json.loads((SUPERVISOR_DIR / 'schemas' / 'neutral-check.json').read_text(encoding='utf-8'))
        self.assertEqual((schema['type'], schema['additionalProperties'], schema['required']),
                         ('object', False, ['marker']))
        source = PROVIDER.read_text(encoding='utf-8')
        answer = json.loads(re.search(r'STRUCTURED_INPUT = "(.*)";', source).group(1).replace('\\"', '"'))
        self.assertEqual(answer, STRUCTURED)
        self.assertIn(answer['marker'], schema['properties']['marker']['enum'])

    def test_compose_fixture_is_offline_unprivileged_and_pinned(self):
        """The fixture has no external route or port, mounts read-only, and uses the pinned images."""
        text = COMPOSE_FILE.read_text(encoding='utf-8')
        version = re.search(r'^ARG CLIENT_VERSION=(\S+)$', CLIENT_DOCKERFILE.read_text(encoding='utf-8'), re.M).group(1)
        jdk = re.search(r'^\s+image: (eclipse-temurin\S+)', (HERE / 'session.compose.yaml').read_text(encoding='utf-8'),
                        re.M).group(1)
        self.assertIn(f'    image: ai-rpg-engine-runtime-probe/claude:{version}\n', text)
        self.assertIn(f'    image: {jdk}\n', text)
        self.assertIn('  fixture:\n    internal: true\n', text)
        self.assertNotRegex(text, r'(?m)^\s+(ports|network_mode|privileged|cap_add):')
        for setting in ('read_only: true', 'cap_drop: [ALL]', 'user: "10001:10001"'):
            self.assertEqual(text.count(setting), 2)
        self.assertIn('./claude_profile_fixture:/fixture:ro', text)
        self.assertIn('./claude_supervisor:/opt/supervisor:ro', text)
        self.assertIn(f'FIXTURE_API_KEY: {FIXTURE_KEY}\n', text)

    def test_scripts_and_fixtures_are_lf_and_scan_clean(self):
        """Scripts are LF POSIX shell, and the repository secret and path scan accepts every new file."""
        spec = importlib.util.spec_from_file_location(
            'repository_checks', ROOT / 'tools' / 'repository_checks' / 'checks.py')
        checks = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checks)
        files = [*sorted(FIXTURE.iterdir()), SUPERVISOR, SUPERVISOR_DIR / 'settings.json',
                 SUPERVISOR_DIR / 'schemas' / 'neutral-check.json', COMPOSE_FILE, PROVIDER, Path(__file__).resolve()]
        for path in files:
            relative = path.relative_to(ROOT).as_posix()
            with self.subTest(file=relative):
                text = path.read_bytes().decode('utf-8')
                self.assertNotIn('\r', text)
                if path.suffix == '.sh':
                    self.assertTrue(text.startswith('#!/bin/sh\n'))
                self.assertEqual(checks.scan_path(relative), [])
                self.assertEqual(checks.scan_text(relative, text), [])

    def test_runner_cases_match_the_expectation_tables(self):
        """The runner's scenarios, pairs and steps are exactly the rows the Docker checks expect."""
        text = RUNNER.read_text(encoding='utf-8')
        source = PROVIDER.read_text(encoding='utf-8')
        loop = re.search(r'signals\)\n(.*?);;', text, re.S).group(1)
        scenarios = re.search(r'for scenario in ([a-z ]+); do', loop).group(1).split()
        self.assertEqual(['schema', 'schema-unanswered', 'prompt-is-data', *scenarios, 'model'], list(SIGNALS))
        sub = re.search(r'subscription\)\n(.*?);;', text, re.S).group(1)
        self.assertEqual(re.search(r'for scenario in ([a-z ]+); do', sub).group(1).split(), list(SUBSCRIPTION))
        refusals = re.findall(r'^    step ([a-z-]+) ', re.search(r'refusals\)\n(.*?);;', text, re.S).group(1), re.M)
        self.assertEqual(refusals, list(REFUSALS))
        pairs = (FIXTURE / 'readiness-pairs.txt').read_text(encoding='utf-8').split()
        self.assertEqual(pairs, list(READINESS))
        for scenario in scenarios:
            if scenario not in ('overage', 'maxtokens'):
                with self.subTest(scenario=scenario):
                    self.assertIn(f'{{"scenario-{scenario}", "', source)
        self.assertIn('static final long RESET_AT = 4102444800L;', source)
        self.assertIn('static final String UNLISTED_MODEL = "claude-unlisted-fixture";', source)
        self.assertIn(f'"accessToken": "{LOGIN_TOKEN}"', (FIXTURE / 'home-login.json').read_text(encoding='utf-8'))

    def test_check_functions_reject_wrong_reports(self):
        """Negative controls: a started refusal, a dropped effort or a leftover file fails its check.

        Each wrong report differs from a passing synthetic one in one detail only, so the check
        fails on that detail and not on the report's shape.
        """
        def task(name, session=SESSION):
            init = {'type': 'system', 'subtype': 'init', 'model': MODEL, 'tools': [], 'mcp_servers': [],
                    'plugins': [], 'skills': [], 'slash_commands': [], 'apiKeySource': 'ANTHROPIC_API_KEY'}
            result = {'type': 'result', 'subtype': 'success', 'is_error': False, 'terminal_reason': 'completed',
                      'api_error_status': None, 'result': NEUTRAL_TEXT, 'session_id': session}
            out = '\n'.join(json.dumps(e) for e in (init, result)) + '\n'
            return {'name': name, 'status': 0, 'stdout': out.encode(), 'stderr': b''}

        def request(name, body, extra=b''):
            return {'method': 'POST', 'path': '/v1/messages', 'kind': 'text', 'headers': {'x-api-key': [FIXTURE_KEY]},
                    'raw': json.dumps(body).encode() + extra + f' [step-{name}]'.encode(), 'body': body}

        refusal = {'steps': [{'name': name, 'status': status, 'stdout': b'', 'stderr': (line + '\n').encode()}
                             for name, (status, line) in REFUSALS.items()], 'lines': {}, 'end': True}
        check_refusals(self, refusal, [])
        with self.assertRaises(AssertionError):
            check_refusals(unittest.TestCase(), refusal, [request('usage', {'model': MODEL})])
        ready = task('claude-sonnet-5-5@high')
        body = {'model': MODEL, 'output_config': {'effort': 'high'}}
        self.assertEqual(readiness_verdict(ready, [request(ready['name'], body)], MODEL, 'high'), 'ready')
        self.assertEqual(readiness_verdict(ready, [request(ready['name'], {'model': MODEL})], MODEL, 'high'),
                         'effort-dropped')
        self.assertEqual(readiness_verdict(ready, [request(ready['name'], {**body, 'model': 'claude-opus-5-5'})],
                                           MODEL, 'high'), 'failed')
        missing = json.dumps({'type': 'result', 'subtype': 'error_during_execution', 'is_error': True,
                              'errors': [f'No conversation found with session ID: {SESSION}']}) + '\n'
        steps = [task('create'), task('resume'),
                 {'name': 'reuse', 'status': 1, 'stdout': b'',
                  'stderr': f'Error: Session ID {SESSION} is already in use.\n'.encode()},
                 {'name': 'retire', 'status': 0, 'stdout': f'retired {SESSION}\n'.encode(), 'stderr': b''},
                 {'name': 'resume-retired', 'status': 1, 'stdout': missing.encode(), 'stderr': b''},
                 task('stateless', session='70c50d33-6d2f-48ac-812b-e0a5046eb4de')]
        lines = {'stored': [TRANSCRIPT], 'plaintext': [TRANSCRIPT], 'before': [TRANSCRIPT],
                 'file': [f'{HOME}/.claude.json']}
        turns = [request('create', {'model': MODEL}),
                 request('resume', {'model': MODEL}, b' session-sentinel-first'),
                 request('stateless', {'model': MODEL})]
        check_lifecycle(self, {'steps': steps, 'lines': lines, 'end': True}, turns)
        for wrong in ({**lines, 'found': [TRANSCRIPT]}, {**lines, 'file': [*lines['file'], TRANSCRIPT]},
                      {**lines, 'before': []}):
            with self.assertRaises(AssertionError):
                check_lifecycle(unittest.TestCase(), {'steps': steps, 'lines': wrong, 'end': True}, turns)


# The Docker checks exist only when RUNTIME_PROBE_DOCKER names the Docker CLI, which
# `.agents/ralph/verify.sh containers` always sets for this suite; without it they are not
# defined at all, so nothing here is reported as skipped or as passed.
if DOCKER:
    class DockerProfileTests(unittest.TestCase):
        """Send tasks through the supervisor to the real pinned client; no account, no credential."""

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
            """Start a fresh scripted upstream, yield, then stop the fixture without deleting volumes."""
            try:
                started = subprocess.run([*self.COMPOSE, 'up', '-d', '--wait', '--force-recreate', 'provider'],
                                         capture_output=True, timeout=240)
                if started.returncode:
                    self.fail('provider did not start: ' + started.stderr.decode(errors='replace')[-2000:])
                yield
            finally:
                subprocess.run([*self.COMPOSE, 'down', '--timeout', '10'], check=True,
                               capture_output=True, timeout=120)

        def run_case(self, case):
            """Run one case of run-profile.sh against a fresh upstream; return its report and requests."""
            with self.fixture():
                done = subprocess.run([*self.COMPOSE, 'run', '--rm', '-T', '--no-deps', 'client', case],
                                      stdin=subprocess.DEVNULL, capture_output=True, timeout=600)
                logs = subprocess.check_output([*self.COMPOSE, 'logs', '--no-log-prefix', 'provider'], timeout=30)
            self.assertEqual(done.returncode, 0, done.stderr.decode(errors='replace')[-2000:])
            return parse_report(done.stdout), parse_requests(logs.decode(errors='replace'))

        def test_refusals_start_nothing(self):
            check_refusals(self, *self.run_case('refusals'))

        def test_api_key_mode_reports_output_errors_and_limits(self):
            check_signals(self, *self.run_case('signals'))

        def test_readiness_pairs_get_their_recorded_verdicts(self):
            check_readiness(self, *self.run_case('readiness'))

        def test_subscription_shape_reports_limit_and_overage_events(self):
            check_subscription(self, *self.run_case('subscription'))

        def test_session_is_stored_resumed_and_purged_without_trace(self):
            check_lifecycle(self, *self.run_case('lifecycle'))


if __name__ == '__main__':
    unittest.main()
