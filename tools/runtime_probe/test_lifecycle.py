"""P01.4 process lifecycle checks against the pinned narration runtime.

Two groups of tests, using only synthetic data:

- ``OpenChildTests`` run everywhere. They check that probe.open_child scopes
  its shutdown escalation to the exact container it named, and that a
  command without a container name gets no Docker escalation at all.
- ``DockerLifecycleTests`` run only when RUNTIME_PROBE_DOCKER names the
  Docker CLI and the probe image is built. They drive the real runtime
  against the scripted provider of session.compose.yaml (no account,
  internal network), in both launch forms: a stdio child started with
  ``compose run``, and the packaged Unix socket reached through the relay.
  They demonstrate what docs/06 sections 4, 5 and 10 rely on: idle and
  interrupt-first shutdown, sticky EOF after the container is killed,
  escalation by `docker kill` of the named container, reconciliation after
  a relay loss, a prompt `docker stop` thanks to `init`, a stderr summary
  that keeps no message text, and early notifications that stay readable.

Upstream effects are observed through the scripted provider's log: it logs
``request <path> <scenario> <body>`` for each request and
``held-stream-closed`` when a held stream ends. Cleanup stops and removes only
the containers and network this file started (`compose down` without `-v`);
it deletes no volume.
"""
import os
from pathlib import Path
import json
import re
import subprocess
import time
import unittest
from unittest import mock

from contract import ContractError, discover
import probe
import session
from transport import Rpc

HERE = Path(__file__).resolve().parent
DOCKER = os.environ.get('RUNTIME_PROBE_DOCKER')
PROJECT = 'ai-rpg-engine-runtime-lifecycle'
# The packaged control socket, replaced by stdio for the stdio launch form.
SOCKET_LISTEN = 'unix:///run/runtime/control.sock'
# Every entry of a stderr summary tail: a level and module path, or the
# fixed word for anything else. Message text can never match.
SUMMARY_ENTRY = re.compile(r'(ERROR|WARN|INFO|DEBUG|TRACE) [a-z][a-z0-9_:]*|unstructured')
# Keys of the summary's level counter.
LEVELS = {'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE', 'unstructured'}


class OpenChildTests(unittest.TestCase):
    """Escalation scoping of probe.open_child, without Docker."""

    def test_named_container_is_killed_through_the_same_docker_cli(self):
        """The hook runs `<docker> kill <name>` for exactly the launched name."""
        command = ['C:/docker.exe', 'run', '--rm', '--name', 'rpg-probe-x-1', '-i', 'image']
        with mock.patch.object(probe, 'Stdio') as stdio, \
                mock.patch.object(probe.subprocess, 'run') as run:
            probe.open_child(command)
            stdio.call_args.kwargs['terminate']()
        self.assertEqual(stdio.call_args.args[0], command)
        self.assertEqual(run.call_args.args[0], ['C:/docker.exe', 'kill', 'rpg-probe-x-1'])
        self.assertIs(run.call_args.kwargs['shell'], False)

    def test_kill_timeout_is_left_to_the_next_shutdown_step(self):
        """A hung `docker kill` does not raise; Stdio then kills the CLI itself."""
        with mock.patch.object(probe, 'Stdio') as stdio, \
                mock.patch.object(probe.subprocess, 'run',
                                  side_effect=subprocess.TimeoutExpired('docker', 30)):
            probe.open_child(['docker', 'run', '--name', 'n', 'image'])
            self.assertIsNone(stdio.call_args.kwargs['terminate']())

    def test_unnamed_command_gets_no_docker_escalation(self):
        """Without --name nothing could be killed safely, so no hook is set."""
        with mock.patch.object(probe, 'Stdio') as stdio:
            probe.open_child(['codex', 'app-server'])
        self.assertIsNone(stdio.call_args.kwargs['terminate'])

    def test_container_names_are_unique_and_role_scoped(self):
        """Fresh names never repeat and carry the probe prefix and role."""
        names = {probe.container_name('stdio') for _ in range(50)}
        self.assertEqual(len(names), 50)
        self.assertTrue(all(re.fullmatch(r'rpg-probe-stdio-[0-9a-f]{12}', n) for n in names))


@unittest.skipUnless(DOCKER, 'set RUNTIME_PROBE_DOCKER to the Docker CLI path')
class DockerLifecycleTests(unittest.TestCase):
    """Drive the real pinned runtime through start, loss and shutdown.

    One fixture (provider and packaged runtime) serves every test; tests that
    need a stdio runtime start their own named `compose run` container on the
    same internal network. Subprocess and protocol errors fail the test;
    cleanup always runs.
    """

    @classmethod
    def setUpClass(cls):
        """Start the provider and the packaged runtime and read the runtime command.

        Raises AssertionError with the runtime's log tail when the fixture
        does not become healthy; the fixture is stopped in that case too.
        """
        cls.compose = [DOCKER, 'compose', '-p', PROJECT, '-f', str(HERE / 'session.compose.yaml')]
        started = subprocess.run([*cls.compose, 'up', '-d', '--wait', 'codex'],
                                 capture_output=True, timeout=240)
        if started.returncode:
            tail = cls.logs('codex')[-2000:]
            cls.tearDownClass()
            raise AssertionError('fixture did not start: ' + tail)
        config = json.loads(subprocess.check_output(
            [*cls.compose, 'config', '--format', 'json'], timeout=60))
        # The stdio form reuses the fixture's own runtime arguments, so both
        # launch forms run with the same provider and feature settings.
        cls.stdio_args = [('stdio://' if arg == SOCKET_LISTEN else arg)
                          for arg in config['services']['codex']['command']]

    @classmethod
    def tearDownClass(cls):
        """Stop and remove the fixture's containers and network; keep volumes."""
        subprocess.run([*cls.compose, 'down', '--timeout', '10'], check=True,
                       capture_output=True, timeout=120)

    @classmethod
    def logs(cls, service):
        """Return the service's current log as text (bounded by the fixture's lifetime)."""
        return subprocess.run([*cls.compose, 'logs', '--no-log-prefix', service],
                              capture_output=True, timeout=30).stdout.decode(errors='replace')

    def upstream(self):
        """Return (held requests seen, held streams closed) from the provider log."""
        lines = self.logs('provider').splitlines()
        held = sum(1 for line in lines
                   if line.startswith('request ') and line.split(' ')[2:3] == ['scenario-hold'])
        return held, lines.count('held-stream-closed')

    def wait_for(self, condition, seconds):
        """Poll ``condition`` every 0.2 s for up to ``seconds``; return its last value."""
        deadline = time.monotonic() + seconds
        while not (value := condition()) and time.monotonic() < deadline:
            time.sleep(.2)
        return value

    def container_exists(self, name):
        """Return whether any container, running or stopped, has exactly ``name``."""
        found = subprocess.run([DOCKER, 'ps', '-aq', '--filter', f'name=^{name}$'],
                               capture_output=True, timeout=30, check=True).stdout
        return bool(found.strip())

    def stdio_child(self):
        """Start a named stdio runtime container as an owned child; return (wire, name)."""
        name = probe.container_name('lifecycle')
        command = [*self.compose, 'run', '--rm', '--no-deps', '--name', name, '-T',
                   'codex', *self.stdio_args]
        return probe.open_child(command), name

    def relay_child(self):
        """Start a named relay to the packaged runtime as an owned child."""
        return probe.open_child([*self.compose, 'run', '--rm', '--name',
                                 probe.container_name('relay'), '-T', 'relay'])

    def held_turn(self, rpc):
        """Start a thread and a turn whose upstream stream the provider holds open.

        Returns (threadId, turnId) once the provider has logged the held
        request, so the turn is really running upstream.
        """
        settings = probe.pick_settings(discover(rpc)['catalog'])[0]
        thread = session.start_thread(rpc, model=settings[0], cwd=probe.WORKSPACE)['threadId']
        before = self.upstream()[0]
        turn = session.start_turn(rpc, thread, model=settings[0], effort=settings[1],
                                  text=session.NEUTRAL_PROMPT + ' scenario-hold')
        self.assertTrue(self.wait_for(lambda: self.upstream()[0] > before, 30),
                        'held request never reached the provider')
        return thread, turn

    def assert_gone(self, name):
        """Require that `--rm` removed the named container within 15 s."""
        self.assertTrue(self.wait_for(lambda: not self.container_exists(name), 15),
                        f'{name} was left behind')

    def test_idle_runtime_exits_on_input_closure_with_redacted_stderr(self):
        """Closing stdin of an idle stdio runtime ends it with exit 0, no escalation.

        Also checks the first rung of shutdown leaves no container and that
        the stderr summary holds only level/module entries.
        """
        wire, name = self.stdio_child()
        with wire:
            discover(Rpc(wire, timeout=60))
        self.assertEqual((wire.shutdown['stage'], wire.shutdown['exitCode']), ('input-closed', 0))
        self.assert_gone(name)
        summary = wire.stderr_summary()
        self.assertFalse(summary['truncated'])
        self.assertLessEqual(set(summary['levels']), LEVELS)
        for entry in summary['tail']:
            self.assertRegex(entry, f'^(?:{SUMMARY_ENTRY.pattern})$')
        self.assertNotIn('fixture', json.dumps(summary))

    def test_early_notifications_survive_until_read(self):
        """Notifications that arrive before a response stay queued and readable in order."""
        wire, _name = self.stdio_child()
        with wire:
            rpc = Rpc(wire, timeout=60)
            settings = probe.pick_settings(discover(rpc)['catalog'])[0]
            thread = session.start_thread(rpc, model=settings[0], cwd=probe.WORKSPACE)['threadId']
            turn = session.start_turn(rpc, thread, model=settings[0], effort=settings[1],
                                      text=session.NEUTRAL_PROMPT + ' scenario-complete')
            queued = [message.get('method') for message in rpc.pending]
            self.assertIn('thread/started', queued)
            first = rpc.next_notification(time.monotonic() + 5)
            self.assertEqual(first.get('method'), queued[0])
            # The turn's own events are still awaited correctly afterwards.
            self.assertEqual(session.await_turn(rpc, thread, turn, timeout=60)['status'],
                             'completed')

    def test_interrupt_first_shutdown_releases_the_upstream_stream(self):
        """Interrupt, await 'interrupted', then close: upstream work ends and exit is clean."""
        wire, name = self.stdio_child()
        with wire:
            rpc = Rpc(wire, timeout=60)
            thread, turn = self.held_turn(rpc)
            closed = self.upstream()[1]
            session.interrupt_turn(rpc, thread, turn)
            self.assertEqual(session.await_turn(rpc, thread, turn, timeout=30)['status'],
                             'interrupted')
            # Observed while the runtime is still alive: once its container
            # is gone the provider can no longer see the connection end.
            self.assertTrue(self.wait_for(lambda: self.upstream()[1] > closed, 15))
        self.assertEqual((wire.shutdown['stage'], wire.shutdown['exitCode']), ('input-closed', 0))
        self.assert_gone(name)

    def test_input_closure_mid_turn_ends_the_process_without_escalation(self):
        """Closing stdin while a turn runs still ends the stdio runtime with exit 0.

        The runtime does not interrupt the turn first; whether the upstream
        stream then sees an orderly end depends on how fast the container's
        network disappears, so it is not asserted here (in most runs the
        provider never sees it). The packaged form shows the same lack of
        interruption deterministically in the relay-loss test. Either way,
        shutdown must interrupt and await active turns before closing.
        """
        wire, name = self.stdio_child()
        with wire:
            self.held_turn(Rpc(wire, timeout=60))
        self.assertEqual((wire.shutdown['stage'], wire.shutdown['exitCode']), ('input-closed', 0))
        self.assertLess(wire.shutdown['seconds'], wire.grace)
        self.assert_gone(name)

    def test_turn_deadline_allows_explicit_interrupt_and_terminal_confirmation(self):
        """A local wait timeout leaves upstream work active until explicitly interrupted."""
        wire, name = self.stdio_child()
        with wire:
            rpc = Rpc(wire, timeout=60)
            thread, turn = self.held_turn(rpc)
            closed = self.upstream()[1]
            with self.assertRaisesRegex(ContractError, '^transport-timeout$'):
                session.await_turn(rpc, thread, turn, timeout=.1)
            self.assertIsNone(rpc.failure)
            self.assertIn({'turnId': turn, 'status': 'inProgress'},
                          session.list_turns(rpc, thread))
            self.assertEqual(self.upstream()[1], closed)
            session.interrupt_turn(rpc, thread, turn)
            self.assertEqual(session.await_turn(rpc, thread, turn, timeout=30)['status'],
                             'interrupted')
            self.assertTrue(self.wait_for(lambda: self.upstream()[1] > closed, 15))
        self.assertEqual(wire.shutdown['exitCode'], 0)
        self.assert_gone(name)

    def test_killed_runtime_gives_a_sticky_eof(self):
        """Killing the container mid-turn fails the wait promptly and every later call too."""
        wire, name = self.stdio_child()
        with wire:
            rpc = Rpc(wire, timeout=60)
            thread, turn = self.held_turn(rpc)
            subprocess.run([DOCKER, 'kill', name], capture_output=True, timeout=30, check=True)
            started = time.monotonic()
            with self.assertRaises(ContractError) as caught:
                session.await_turn(rpc, thread, turn, timeout=30)
            self.assertEqual(str(caught.exception), 'transport-eof')
            self.assertLess(time.monotonic() - started, 10)
            with self.assertRaises(ContractError) as again:
                session.list_turns(rpc, thread)
            self.assertEqual(str(again.exception), 'transport-eof')
            self.assertEqual(rpc.failure, 'transport-eof')
        # The Docker CLI may still be exiting when close() runs, so either
        # first rung is valid; what matters is that the code is not success.
        self.assertIn(wire.shutdown['stage'], ('exited', 'input-closed'))
        self.assertNotEqual(wire.shutdown['exitCode'], 0)
        self.assert_gone(name)

    def test_child_ignoring_input_closure_is_killed_by_container_name(self):
        """A container that never reads stdin is ended by the scoped `docker kill`.

        Killing only the local Docker CLI would leave the container running,
        so the escalation must address the container itself.
        """
        name = probe.container_name('lifecycle')
        wire = probe.open_child([*self.compose, 'run', '--rm', '--no-deps', '--name', name, '-T',
                                 '--entrypoint', 'sleep', 'relay', '120'])
        wire.grace = 5
        self.assertTrue(self.wait_for(lambda: self.container_exists(name), 30))
        record = wire.close()
        self.assertEqual(record['stage'], 'terminated')
        self.assertLess(record['seconds'], 30)
        self.assert_gone(name)

    def test_relay_loss_is_reconciled_from_a_new_generation(self):
        """After the relay drops mid-turn, a new connection resumes, interrupts and awaits.

        The packaged runtime keeps the turn running when its client
        disappears, so the turn is still 'inProgress' until reconciled.
        """
        with self.relay_child() as first:
            thread, turn = self.held_turn(Rpc(first, timeout=60))
            closed = self.upstream()[1]
        self.assertEqual(first.shutdown['stage'], 'input-closed')
        with self.relay_child() as second:
            rpc = Rpc(second, timeout=60)
            discover(rpc)
            resumed = session.resume_thread(rpc, thread)
            self.assertIn({'turnId': turn, 'status': 'inProgress'}, resumed['turns'])
            self.assertEqual(self.upstream()[1], closed)
            session.interrupt_turn(rpc, thread, turn)
            self.assertEqual(session.await_turn(rpc, thread, turn, timeout=30)['status'],
                             'interrupted')
            self.assertIn({'turnId': turn, 'status': 'interrupted'},
                          session.list_turns(rpc, thread))
        self.assertTrue(self.wait_for(lambda: self.upstream()[1] > closed, 15))

    def test_z_runtime_service_stops_promptly_under_init(self):
        """`docker stop` ends the init-enabled runtime with exit 0, well before SIGKILL.

        Without init the runtime, as PID 1, ignores SIGTERM and is killed
        (137) only after the full grace period. Runs last (name order)
        because it stops the shared runtime service.
        """
        container = subprocess.check_output([*self.compose, 'ps', '-q', 'codex'],
                                            timeout=30).decode().strip()
        inspect = [DOCKER, 'inspect', '-f']
        self.assertEqual(subprocess.check_output([*inspect, '{{.HostConfig.Init}}', container],
                                                 timeout=30).decode().strip(), 'true')
        started = time.monotonic()
        subprocess.run([DOCKER, 'stop', '-t', '15', container], check=True,
                       capture_output=True, timeout=60)
        self.assertLess(time.monotonic() - started, 10)
        self.assertEqual(subprocess.check_output([*inspect, '{{.State.ExitCode}}', container],
                                                 timeout=30).decode().strip(), '0')


if __name__ == '__main__':
    unittest.main()
