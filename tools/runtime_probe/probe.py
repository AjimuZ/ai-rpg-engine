"""Command-line entry point for the P01.1 discovery and P01.2 session proofs.

Three launch profiles share one discovery contract (contract.discover) and one
bounded line transport (transport.Stdio):

- ``host``: an owner-supplied absolute runtime executable launched in stdio mode
  with an isolated, empty account home. Optional developer workflow.
- ``container``: the pinned runtime image launched in stdio mode with
  ``docker run -i`` and no network, no account volume, and a read-only root.
- ``packaged``: the Compose IPC fixture. The runtime listens on its private Unix
  socket; the Java 21 relay (UnixRelay.java) connects to that socket and bridges
  WebSocket text frames to line-delimited JSON on its own stdio.

Two further profiles prove thread and turn lifecycles (P01.2, session.py):

- ``session``: the deterministic fixture (session.compose.yaml). The packaged
  topology runs against ScriptedProvider.java on an internal network, so
  thread creation and resume, explicit per-turn model and effort,
  notification correlation, terminal statuses, and interruption are proven
  without an account and without any real inference.
- ``live``: the opt-in real neutral task. It needs an existing Docker volume
  in which the owner has signed in; it refuses to start a thread otherwise.

Every profile first checks the exact runtime version and refuses any other.
The report contains codes, counters, account *state*, identifiers, and model
identifiers only. Raw runtime messages, stderr contents, and account objects
never reach it, because they can contain e-mail addresses, tokens, or local
paths.

The probe never signs in. Only the ``live`` profile contacts a real inference
service, using the fixed neutral prompt.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

from contract import ContractError, OUTPUT_SCHEMA, VERSION, discover, validate_output
import session
from transport import Rpc, Stdio

# Features that would contact remote catalogs or update services at startup.
# They are irrelevant to discovery and stay off in every profile.
DISABLED_FEATURES = ('plugins', 'remote_plugin', 'apps', 'in_app_updates')
RUNTIME_UID = '10001'
# The image's working directory; thread/start must report it back unchanged.
WORKSPACE = '/var/lib/runtime/workspace'
# Docker volume names accepted for the live profile's account volume.
_VOLUME_NAME = re.compile(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}')
_VERSION_LINE = re.compile(r'codex-cli (\d+\.\d+\.\d+)\n?')


def validate_version(text):
    """Accept only the exact pinned release; suffixed builds are different binaries."""
    match = _VERSION_LINE.fullmatch(text)
    if match is None or match.group(1) != VERSION:
        raise ContractError('unsupported-runtime-version')


def executable_path(value):
    """Resolve an owner-configured executable; PATH lookup is never used."""
    path = Path(value)
    if not path.is_absolute() or not path.is_file():
        raise ContractError('executable-not-found')
    return str(path.resolve())


def isolated_environment(home):
    """Build a child environment from scratch instead of filtering os.environ.

    An allowlist cannot leak keys, proxies, or configuration variables that a
    denylist forgot. The runtime receives an empty private home, so it cannot
    read the developer's sign-in, configuration, instructions, or skills.
    """
    env = {'CODEX_HOME': str(home), 'HOME': str(home), 'RUST_LOG': 'warn', 'NO_COLOR': '1'}
    if os.name == 'nt':
        # Windows processes need the system directories to start; every
        # per-user location is redirected into the isolated home.
        for name in ('SYSTEMROOT', 'WINDIR', 'COMSPEC'):
            if name in os.environ:
                env[name] = os.environ[name]
        env['PATH'] = os.path.join(os.environ.get('SYSTEMROOT', r'C:\Windows'), 'System32')
        env['USERPROFILE'] = str(home)
        env['APPDATA'] = str(Path(home, 'AppData', 'Roaming'))
        env['LOCALAPPDATA'] = str(Path(home, 'AppData', 'Local'))
    else:
        env['PATH'] = '/usr/bin:/bin'
    return env


def _check_version(command, env=None):
    """Check command output against the pin using the optional child environment; return None
    or raise a redacted ContractError on launch, decoding or version failure.
    """
    try:
        done = subprocess.run(command, env=env, capture_output=True, timeout=120, shell=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ContractError('runtime-version-unavailable') from exc
    if done.returncode != 0:
        raise ContractError('runtime-version-unavailable')
    try:
        validate_version(done.stdout.decode('ascii'))
    except UnicodeError as exc:
        raise ContractError('unsupported-runtime-version') from exc


def _server_arguments():
    """Return stdio launch arguments with remote discovery features disabled; take no inputs
    and perform no I/O.
    """
    arguments = ['app-server', '--listen', 'stdio://']
    for feature in DISABLED_FEATURES:
        arguments += ['--disable', feature]
    return arguments


def _container_isolation():
    """docker run options shared by the version check and the stdio launch."""
    owner = f'uid={RUNTIME_UID},gid={RUNTIME_UID},mode=0700'
    return ['--network', 'none', '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges',
            # Writable state lives only in memory and disappears with the
            # container: no account volume, no host bind mount.
            '--tmpfs', f'/var/lib/runtime/home:{owner}',
            '--tmpfs', f'/var/lib/runtime/workspace:{owner}',
            '--tmpfs', '/tmp:mode=1777']


def run_discovery(command, *, env, cwd, timeout, profile):
    """Launch the supplied command/environment/cwd and return bounded discovery evidence;
    propagate transport, protocol and process failures after closing the child.
    """
    with Stdio(command, env=env, cwd=cwd) as wire:
        rpc = Rpc(wire, timeout=timeout)
        report = discover(rpc)
        report['notifications'] = rpc.notifications
    # Stderr is counted, never copied: see transport.Stdio._stderr.
    report.update(profile=profile, stderrBytes=wire.stderr_bytes,
                  stderrTruncated=wire.stderr_truncated)
    return report


def probe_host(args):
    """Discover an absolute executable from CLI args in a temporary private home; return
    evidence or propagate launch/protocol errors, always removing that home.
    """
    executable = executable_path(args.executable)
    work = Path(args.work_dir).resolve()
    work.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=work, prefix='runtime-home-') as home:
        env = isolated_environment(Path(home))
        _check_version([executable, '--version'], env)
        workspace = Path(home, 'workspace')
        workspace.mkdir()
        return run_discovery([executable, *_server_arguments()], env=env,
                             cwd=str(workspace), timeout=args.timeout, profile='host-stdio')


def probe_container(args):
    """Discover the CLI-selected image without network or account mounts; return evidence or
    propagate executable, version and protocol failures.
    """
    docker = executable_path(args.docker)
    run = [docker, 'run', '--rm', *_container_isolation()]
    _check_version([*run, args.image, '--version'])
    # -i keeps stdin attached; closing it is the runtime's EOF and ends the
    # container, which --rm then removes. No -t: a TTY would alter framing.
    return run_discovery([*run, '-i', args.image, *_server_arguments()], env=None,
                         cwd=None, timeout=args.timeout, profile='container-stdio')


def probe_packaged(args):
    """Discover the CLI-selected Compose fixture through its relay; return evidence or
    propagate failures, stopping owned services in either case.
    """
    docker = executable_path(args.docker)
    compose = [docker, 'compose', '--file', str(Path(args.compose_file).resolve())]
    _check_version([*compose, 'run', '--rm', '--no-deps', '-T', 'codex', '--version'])
    try:
        # The relay is the only child this probe owns; Compose starts the
        # runtime service as its dependency, exactly like the packaged engine.
        return run_discovery([*compose, 'run', '--rm', '-T', 'relay'], env=None, cwd=None,
                             timeout=args.timeout, profile='packaged-unix-socket')
    finally:
        # Stop the fixture's runtime container. Volumes are never removed;
        # the fixture's IPC volume is tmpfs-backed and holds no data at rest.
        subprocess.run([*compose, 'down', '--timeout', '10'], capture_output=True,
                       timeout=120, shell=False)


def pick_settings(catalog):
    """Choose two explicit (model, effort) pairs that differ as much as possible.

    The first pair is the first catalog model with its first listed effort;
    the second uses the next model (when there is one) and an effort different
    from the first pair's. Differing values make an ignored per-turn setting
    visible in the upstream evidence.
    Raises ContractError('catalog-too-small') when no differing pair exists.
    """
    if not catalog:
        raise ContractError('catalog-too-small')
    first = catalog[0]
    second = catalog[1] if len(catalog) > 1 else first
    a = (first['model'], first['efforts'][0])
    other = [effort for effort in second['efforts'] if effort != a[1]]
    if not other:
        raise ContractError('catalog-too-small')
    return a, (second['model'], other[0])


def upstream_requests(log_text):
    """Parse ScriptedProvider request lines into comparable settings.

    Input: the provider's stdout. Output: one dict per /responses request,
    {'scenario', 'model', 'effort', 'format', 'schemaMatches'}, in arrival order.
    Bodies are decoded only to compare these fields; nothing else is retained.
    Raises ContractError('invalid-upstream-log') for an undecodable line.
    """
    requests = []
    for line in log_text.splitlines():
        parts = line.split(' ')
        if len(parts) != 4 or parts[0] != 'request' or not parts[1].endswith('/responses'):
            continue
        try:
            body = json.loads(base64.b64decode(parts[3], validate=True))
            reasoning = body.get('reasoning') or {}
            text_format = (body.get('text') or {}).get('format') or {}
        except (ValueError, AttributeError) as exc:
            raise ContractError('invalid-upstream-log') from exc
        requests.append({'scenario': parts[2], 'model': body.get('model'),
                         'effort': reasoning.get('effort'), 'format': text_format.get('type'),
                         'schemaMatches': text_format.get('schema') == OUTPUT_SCHEMA})
    return requests


def _wait_for(condition, deadline, code):
    """Poll ``condition`` every 0.2 s until it is true or raise ``code``."""
    while not condition():
        if deadline - time.monotonic() <= 0:
            raise ContractError(code)
        time.sleep(.2)


def run_session_fixture(relay, *, timeout, provider_log):
    """Drive the P01.2 lifecycle sequence over two relay connections.

    Inputs: the relay command (one connection per process), the per-request
    timeout, and a callable returning the provider's current stdout.
    Sequence on connection 1: thread T1 runs turns A (complete, settings 1)
    and B (complete, settings 2). A second thread T2 then starts turn X
    (held upstream, settings 2) and, while X is in progress, T1 runs turn C
    (complete, settings 1): C must ignore X's interleaved events, and X must
    still reach its own terminal status when it is interrupted after C.
    T1 then runs D (held, then interrupted) and E (rejected upstream,
    failed). Connection 2 resumes both threads, checks their turn lists and
    sticky settings, and runs turn F (complete, settings 2) on T1.
    Output: a report of IDs, statuses, codes, counters, and model IDs only.
    Raises ContractError when any status, correlation, setting, or upstream
    request differs from the plan.
    """
    # Both lists follow the order in which the provider receives the
    # turns' upstream requests: A, B, X, C, D, E, F.
    plan = []
    turns = []

    def held_requests():
        """Count the held upstream requests the provider has received."""
        return sum(r['scenario'] == 'scenario-hold' for r in upstream_requests(provider_log()))

    def wait_for_new_hold(already):
        """Wait until a held request beyond ``already`` is open upstream.

        Interrupting only after this point makes the evidence cover the
        abort of real in-flight work, not of a turn that never left the runtime.
        """
        _wait_for(lambda: held_requests() > already, time.monotonic() + timeout,
                  'held-request-missing')

    def record(label, scenario, settings, turn_id, result, position=None):
        """Add one finished turn to the expected upstream plan and the report.

        ``position`` places a turn whose request preceded turns that finished
        before it (the concurrent turn X); by default the turn is appended.
        """
        model, effort = settings
        position = len(plan) if position is None else position
        plan.insert(position, {'scenario': scenario, 'model': model, 'effort': effort,
                               'format': 'json_schema', 'schemaMatches': True})
        turns.insert(position, {
            'thread': label, 'turnId': turn_id, 'scenario': scenario, 'model': model,
            'effort': effort, 'status': result['status'], 'errorCode': result['errorCode'],
            'events': result['events'], 'ignored': result['ignored'],
            'foreign': result['foreign']})

    def start(rpc, thread_id, scenario, settings):
        """Start one turn with explicit settings and the output schema."""
        model, effort = settings
        return session.start_turn(
            rpc, thread_id, text=f'{session.NEUTRAL_PROMPT} {scenario}',
            model=model, effort=effort, output_schema=OUTPUT_SCHEMA)

    def run_turn(rpc, thread_id, scenario, settings, expected):
        """Start one T1 turn, await it, check its status, record and return it."""
        holds = held_requests() if scenario == 'scenario-hold' else 0
        turn_id = start(rpc, thread_id, scenario, settings)
        on_started = None
        if scenario == 'scenario-hold':
            def on_started():
                """Wait for this held turn to reach upstream and request its interruption;
                return None or propagate timeout/protocol failures.
                """
                wait_for_new_hold(holds)
                session.interrupt_turn(rpc, thread_id, turn_id)
        result = session.await_turn(rpc, thread_id, turn_id, timeout=timeout,
                                    on_started=on_started)
        if result['status'] != expected or not result['started']:
            raise ContractError('unexpected-turn-status')
        if expected == 'completed':
            validate_output(result['text'], status=result['status'])
        record('T1', scenario, settings, turn_id, result)
        return result

    with Stdio(relay, env=None, cwd=None) as wire:
        rpc = Rpc(wire, timeout=timeout)
        found = discover(rpc)
        first, second = pick_settings(found['catalog'])
        thread = session.start_thread(rpc, model=first[0], cwd=WORKSPACE)
        thread_id = thread['threadId']
        run_turn(rpc, thread_id, 'scenario-complete', first, 'completed')
        run_turn(rpc, thread_id, 'scenario-complete', second, 'completed')

        # Concurrent turn X on a second thread of the same connection. Its
        # thread default (settings 1) differs from its explicit turn settings
        # (settings 2), so the upstream evidence also shows that per-turn
        # values win on a fresh thread.
        other_id = session.start_thread(rpc, model=first[0], cwd=WORKSPACE)['threadId']
        holds = held_requests()
        other_turn = start(rpc, other_id, 'scenario-hold', second)
        wait_for_new_hold(holds)
        overlapped = run_turn(rpc, thread_id, 'scenario-complete', first, 'completed')
        # At least X's turn/started was interleaved with C's events and was
        # ignored by thread ID instead of being applied to C.
        if overlapped['foreign'] < 1:
            raise ContractError('no-interleaved-foreign-events')
        session.interrupt_turn(rpc, other_id, other_turn)
        # X's turn/started was consumed (and ignored) while C was awaited; a
        # sequential client cannot route it back, so only X's own terminal
        # status is required here. The engine adapter routes per turn instead.
        concurrent = session.await_turn(rpc, other_id, other_turn, timeout=timeout)
        if concurrent['status'] != 'interrupted':
            raise ContractError('unexpected-turn-status')
        record('T2', 'scenario-hold', second, other_turn, concurrent, position=2)

        run_turn(rpc, thread_id, 'scenario-hold', second, 'interrupted')
        run_turn(rpc, thread_id, 'scenario-fail', first, 'failed')
        notifications = rpc.notifications

    # A new process is a new socket connection: resume must not depend on
    # connection-local state.
    with Stdio(relay, env=None, cwd=None) as wire:
        rpc = Rpc(wire, timeout=timeout)
        discover(rpc)
        for label, resumed_id, settings in (('T1', thread_id, first),
                                            ('T2', other_id, second)):
            resumed = session.resume_thread(rpc, resumed_id)
            expected = [{'turnId': t['turnId'], 'status': t['status']}
                        for t in turns if t['thread'] == label]
            if resumed['turns'] != expected:
                raise ContractError('resumed-turns-mismatch')
            # The last turn's explicit settings are the thread's current ones.
            if (resumed['model'], resumed['effort']) != settings:
                raise ContractError('resumed-settings-mismatch')
        run_turn(rpc, thread_id, 'scenario-complete', second, 'completed')
        notifications += rpc.notifications

    deadline = time.monotonic() + timeout
    # The provider logs each closed held stream at its next keep-alive write;
    # both X and D must have been aborted upstream.
    _wait_for(lambda: provider_log().splitlines().count('held-stream-closed') >= 2,
              deadline, 'held-stream-not-closed')
    upstream = upstream_requests(provider_log())
    if upstream != plan:
        raise ContractError('upstream-settings-mismatch')
    return {'runtimeVersion': found['runtimeVersion'], 'accountState': found['accountState'],
            'inferenceVerified': False, 'threadId': thread_id, 'concurrentThreadId': other_id,
            'modelProvider': thread['modelProvider'], 'turns': turns,
            'resumedThreads': 2, 'upstreamRequests': len(upstream),
            'upstreamMatchesRequests': True, 'heldStreamsClosed': 2,
            'notifications': notifications}


def probe_session(args):
    """Run the deterministic lifecycle fixture and always stop it afterwards."""
    docker = executable_path(args.docker)
    compose = [docker, 'compose', '--file', str(Path(args.compose_file).resolve())]
    _check_version([*compose, 'run', '--rm', '--no-deps', '-T', 'codex', '--version'])

    def provider_log():
        """Return the provider's stdout; the bodies hold fixture text only."""
        done = subprocess.run([*compose, 'logs', '--no-log-prefix', 'provider'],
                              capture_output=True, timeout=60, shell=False)
        if done.returncode != 0:
            raise ContractError('provider-log-unavailable')
        return done.stdout.decode('utf-8', errors='replace')

    try:
        # The runtime must outlive both relay connections, so it is started
        # explicitly instead of as a dependency of the first relay run.
        done = subprocess.run([*compose, 'up', '--detach', '--wait', 'codex'],
                              capture_output=True, timeout=300, shell=False)
        if done.returncode != 0:
            raise ContractError('fixture-start-failed')
        report = run_session_fixture([*compose, 'run', '--rm', '-T', 'relay'],
                                     timeout=args.timeout, provider_log=provider_log)
        report['profile'] = 'packaged-session-fixture'
        return report
    finally:
        # Volumes are never removed; the IPC volume is tmpfs-backed.
        subprocess.run([*compose, 'down', '--timeout', '10'], capture_output=True,
                       timeout=120, shell=False)


def choose_live_settings(catalog, model=None, effort=None):
    """Resolve the live task's explicit model and effort against the catalog.

    Defaults: the first catalog model and that model's default effort.
    Raises ContractError('model-not-in-catalog') or
    ContractError('effort-not-supported') for owner values the catalog lacks.
    """
    if not catalog:
        raise ContractError('model-not-in-catalog')
    entry = catalog[0] if model is None else next(
        (m for m in catalog if m['model'] == model), None)
    if entry is None:
        raise ContractError('model-not-in-catalog')
    chosen = entry['defaultEffort'] if effort is None else effort
    if chosen not in entry['efforts']:
        raise ContractError('effort-not-supported')
    return entry['model'], chosen


def run_live_task(launch, *, timeout, model=None, effort=None):
    """Run the neutral task once, then resume it from a second process.

    ``launch`` returns a fresh stdio command each time; both processes share
    the account volume, where the runtime stores thread history. A thread is
    started only when an account is present, so an unsigned volume fails
    with 'sign-in-required' before any inference context is sent.
    Output: a report whose 'inferenceVerified' is true only after the output
    validator accepted a correlated completed turn and resume returned it.
    """
    with Stdio(launch(), env=None, cwd=None) as wire:
        rpc = Rpc(wire, timeout=timeout)
        found = discover(rpc)
        if found['accountState'] != 'account-present-unverified':
            raise ContractError('sign-in-required')
        settings = choose_live_settings(found['catalog'], model, effort)
        thread = session.start_thread(rpc, model=settings[0], cwd=WORKSPACE)
        turn_id = session.start_turn(rpc, thread['threadId'], text=session.NEUTRAL_PROMPT,
                                     model=settings[0], effort=settings[1],
                                     output_schema=OUTPUT_SCHEMA)
        result = session.await_turn(rpc, thread['threadId'], turn_id, timeout=timeout)
        if result['status'] != 'completed':
            raise ContractError(f"live-turn-{result['status']}")
        validate_output(result['text'], status=result['status'])
    with Stdio(launch(), env=None, cwd=None) as wire:
        rpc = Rpc(wire, timeout=timeout)
        discover(rpc)
        resumed = session.resume_thread(rpc, thread['threadId'])
        if resumed['turns'] != [{'turnId': turn_id, 'status': 'completed'}]:
            raise ContractError('resumed-turns-mismatch')
        if (resumed['model'], resumed['effort']) != settings:
            raise ContractError('resumed-settings-mismatch')
    return {'runtimeVersion': found['runtimeVersion'], 'accountState': found['accountState'],
            'inferenceVerified': True, 'model': settings[0], 'effort': settings[1],
            'threadId': thread['threadId'], 'turnId': turn_id, 'status': 'completed',
            'events': result['events'], 'ignored': result['ignored'], 'resumed': True}


def probe_live(args):
    """Opt-in real neutral task with an existing, owner-signed-in account volume.

    The container keeps the discovery isolation (read-only root, no
    capabilities, tmpfs workspace) but needs outbound network access for
    inference. The volume must already exist: Docker would otherwise create
    an empty one silently, which is never the owner's account.
    """
    docker = executable_path(args.docker)
    if not _VOLUME_NAME.fullmatch(args.account_volume):
        raise ContractError('invalid-account-volume')
    done = subprocess.run([docker, 'volume', 'inspect', args.account_volume],
                          capture_output=True, timeout=60, shell=False)
    if done.returncode != 0:
        raise ContractError('account-volume-missing')
    isolation = [option for option in _container_isolation()
                 if option not in ('--network', 'none')]
    # Replace the tmpfs home with the account volume; everything else stays.
    home = f'/var/lib/runtime/home:uid={RUNTIME_UID},gid={RUNTIME_UID},mode=0700'
    index = isolation.index(home)
    isolation[index - 1:index + 1] = ['--volume', f'{args.account_volume}:/var/lib/runtime/home']
    run = [docker, 'run', '--rm', *isolation]
    _check_version([*run, args.image, '--version'])
    report = run_live_task(lambda: [*run, '-i', args.image, *_server_arguments()],
                           timeout=args.timeout, model=args.model, effort=args.effort)
    report['profile'] = 'live-container-stdio'
    return report


def main(argv=None):
    """Parse optional CLI argv and print one evidence/error object; return zero on success or
    one on ContractError, with argparse handling invalid options.
    """
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--timeout', type=float, default=60.0,
                        help='per-request deadline in seconds')
    profiles = parser.add_subparsers(dest='profile', required=True)
    host = profiles.add_parser('host', help='owner-supplied executable over stdio')
    host.add_argument('--executable', required=True)
    host.add_argument('--work-dir', default='.local/runtime-probe')
    container = profiles.add_parser('container', help='pinned image over stdio')
    container.add_argument('--docker', required=True)
    container.add_argument('--image', required=True)
    packaged = profiles.add_parser('packaged', help='Compose fixture over the Unix socket')
    packaged.add_argument('--docker', required=True)
    packaged.add_argument('--compose-file', required=True)
    fixture = profiles.add_parser('session', help='lifecycle fixture over the Unix socket')
    fixture.add_argument('--docker', required=True)
    fixture.add_argument('--compose-file', required=True)
    live = profiles.add_parser('live', help='opt-in real neutral task (owner sign-in)')
    live.add_argument('--docker', required=True)
    live.add_argument('--image', required=True)
    live.add_argument('--account-volume', required=True,
                      help='existing Docker volume holding the signed-in runtime home')
    live.add_argument('--model', help='catalog model; default: first catalog entry')
    live.add_argument('--effort', help="reasoning effort; default: the model's default")
    args = parser.parse_args(argv)
    handler = {'host': probe_host, 'container': probe_container,
               'packaged': probe_packaged, 'session': probe_session,
               'live': probe_live}[args.profile]
    try:
        report = handler(args)
    except ContractError as exc:
        print(json.dumps({'profile': args.profile, 'error': str(exc)}))
        return 1
    print(json.dumps(report, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
