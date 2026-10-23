"""Command-line entry point for the P01.1 read-only discovery proof.

Three launch profiles share one discovery contract (contract.discover) and one
bounded line transport (transport.Stdio):

- ``host``: an owner-supplied absolute runtime executable launched in stdio mode
  with an isolated, empty account home. Optional developer workflow.
- ``container``: the pinned runtime image launched in stdio mode with
  ``docker run -i`` and no network, no account volume, and a read-only root.
- ``packaged``: the Compose IPC fixture. The runtime listens on its private Unix
  socket; the Java 21 relay (UnixRelay.java) connects to that socket and bridges
  WebSocket text frames to line-delimited JSON on its own stdio.

Every profile first checks the exact runtime version and refuses any other.
The report contains codes, counters, account *state* and model identifiers
only. Raw runtime messages, stderr contents, and account objects never reach
it, because they can contain e-mail addresses, tokens, or local paths.

The probe never signs in, never starts a thread or a turn, and never sends
inference context; account presence is therefore reported as unverified.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from contract import ContractError, VERSION, discover
from transport import Rpc, Stdio

# Features that would contact remote catalogs or update services at startup.
# They are irrelevant to discovery and stay off in every profile.
DISABLED_FEATURES = ('plugins', 'remote_plugin', 'apps', 'in_app_updates')
RUNTIME_UID = '10001'
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
    with Stdio(command, env=env, cwd=cwd) as wire:
        rpc = Rpc(wire, timeout=timeout)
        report = discover(rpc)
        report['notifications'] = rpc.notifications
    # Stderr is counted, never copied: see transport.Stdio._stderr.
    report.update(profile=profile, stderrBytes=wire.stderr_bytes,
                  stderrTruncated=wire.stderr_truncated)
    return report


def probe_host(args):
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
    docker = executable_path(args.docker)
    run = [docker, 'run', '--rm', *_container_isolation()]
    _check_version([*run, args.image, '--version'])
    # -i keeps stdin attached; closing it is the runtime's EOF and ends the
    # container, which --rm then removes. No -t: a TTY would alter framing.
    return run_discovery([*run, '-i', args.image, *_server_arguments()], env=None,
                         cwd=None, timeout=args.timeout, profile='container-stdio')


def probe_packaged(args):
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


def main(argv=None):
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
    args = parser.parse_args(argv)
    handler = {'host': probe_host, 'container': probe_container,
               'packaged': probe_packaged}[args.profile]
    try:
        report = handler(args)
    except ContractError as exc:
        print(json.dumps({'profile': args.profile, 'error': str(exc)}))
        return 1
    print(json.dumps(report, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
