"""Qualify managed-profile dispatch without claiming live inference capacity.

The fixed, account-free experiment uses the real pinned runtime and internal
scripted upstream. Only bounded counts, statuses and local cancellation times
are retained. This single-reader fixture is not the production scheduler.
Failures propagate as safe contract codes; owned services stop in ``finally``.
"""
from collections import deque
import base64
import json
from pathlib import Path
import shutil
import subprocess
import time

from contract import ContractError, OUTPUT_SCHEMA, VERSION, discover, validate_output
import probe
import session
from transport import Rpc, remaining

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE / 'evidence/concurrency-0.164.0.json'


class TurnRouter:
    """Route one RPC generation into bounded registered (thread, turn) inboxes.

    Inputs are the existing sequential RPC reader and at most three identities.
    ``await_turn`` returns the existing validated summary. Unknown turn events,
    overflow, malformed identities and deadlines fail closed; no active turn's
    event is discarded. Thread/account diagnostics without turn identity are
    counted separately. The caller must reconcile all turns after any failure.
    """

    MAX_TURNS = 3
    MAX_EVENTS = 1024

    def __init__(self, rpc):
        """Create empty per-generation inboxes for ``rpc``; perform no I/O."""
        self.rpc = rpc
        self.inboxes = {}
        self.buffered = 0
        self.routed = 0
        self.unscoped = 0

    def register(self, thread_id, turn_id):
        """Register one acknowledged identity; reject duplicates or excess turns."""
        key = (thread_id, turn_id)
        if any(not isinstance(value, str) or not value for value in key):
            raise ContractError('invalid-router-identity')
        if key in self.inboxes or len(self.inboxes) >= self.MAX_TURNS:
            raise ContractError('router-registration-limit')
        self.inboxes[key] = deque()

    def next_for(self, key, deadline):
        """Return the next event for ``key`` by deadline, buffering other turns.

        A total event bound also covers unscoped floods. Identity-less runtime
        diagnostics cannot establish turn completion and never enter an inbox.
        Transport/protocol failures propagate; a deadline leaves RPC usable for
        explicit interruption, as in the existing sequential probe.
        """
        remaining(deadline)
        if key not in self.inboxes:
            raise ContractError('unregistered-router-turn')
        inbox = self.inboxes[key]
        if inbox:
            self.buffered -= 1
            return inbox.popleft()
        for _ in range(self.MAX_EVENTS):
            event = self.rpc.next_notification(deadline)
            params = event['params']
            turn = params.get('turn')
            turn_id = turn.get('id') if isinstance(turn, dict) else params.get('turnId')
            if turn_id is None:
                # Malformed lifecycle events must not masquerade as diagnostics.
                if event['method'].startswith(('turn/', 'item/')):
                    raise ContractError('invalid-turn-event')
                self.unscoped += 1
                continue
            identity = (params.get('threadId'), turn_id)
            if any(not isinstance(value, str) or not value for value in identity):
                raise ContractError('invalid-router-identity')
            if identity not in self.inboxes:
                raise ContractError('unregistered-router-turn')
            if identity == key:
                return event
            if self.buffered >= self.MAX_EVENTS:
                raise ContractError('router-event-limit')
            self.inboxes[identity].append(event)
            self.buffered += 1
            self.routed += 1
        raise ContractError('router-event-limit')

    def await_turn(self, key, *, timeout):
        """Validate one registered turn with existing gates; propagate failures.

        A lightweight view supplies only the selected inbox. No second reader,
        concurrent request registry or production queue is introduced here.
        """
        router = self

        class Inbox:
            """Expose a selected notification reader; failures propagate unchanged."""

            def next_notification(self, deadline):
                """Read this call's selected identity by deadline or raise its failure."""
                return router.next_for(key, deadline)

        return session.await_turn(Inbox(), *key, timeout=timeout)


def serialized_policy():
    """Return the selected limit and unproved live capabilities without I/O/failure.

    This is an explicit qualification decision, not an implemented backend
    admission controller. Raising the production limit belongs to P08.6.
    """
    return {
        'mode': 'serialized', 'installationActiveTurnLimit': 1,
        'liveSimultaneityVerified': False, 'liveHeadroomVerified': False,
        'liveCancellationLatencyVerified': False, 'liveQuotaBehaviorVerified': False,
        'providerPriorityVerified': False, 'throughputMeasured': False,
        'reason': 'live-simultaneity-and-headroom-unproved',
        'priorityOwner': 'backend', 'releasePermit': 'terminal-confirmed-or-reconciled',
        'unknownOutcome': 'stop-admissions-and-reconcile',
        'increaseGate': 'P08.6',
    }


def run_qualification(launch, provider_log, *, timeout=60):
    """Run two-thread dispatch/cancellation and a serialized priority handoff.

    ``launch`` creates an owned relay; ``provider_log`` reads only synthetic logs.
    Return a neutral evidence projection; raise ContractError on policy, routing,
    cancellation, schema or order failures. No live account is accessed. Counts
    describe the scripted experiment, never subscription capacity or throughput.
    """
    with probe.open_child(launch()) as wire:
        rpc = Rpc(wire, timeout=timeout)
        found = discover(rpc)
        settings = probe.pick_settings(found['catalog'])[0]
        if found['accountState'] != 'unsupported-auth':
            raise ContractError('qualification-requires-account-free-fixture')
        router = TurnRouter(rpc)
        plan = []
        results = []
        cancellations = []

        def wait_for_counts(holds, closed):
            """Await exact lower-bound scripted arrival/closure counts or fail by timeout."""
            def ready():
                """Read fixed fixture log markers and return whether both bounds hold."""
                log = provider_log()
                requests = probe.upstream_requests(log)
                return (sum(r['scenario'] == 'scenario-hold' for r in requests) >= holds
                        and log.splitlines().count('held-stream-closed') >= closed)
            probe._wait_for(ready, time.monotonic() + timeout, 'qualification-upstream-timeout')

        def start(scenario, thread_id=None):
            """Start an explicitly configured neutral turn; register its acknowledged ID."""
            if thread_id is None:
                thread_id = session.start_thread(rpc, model=settings[0], cwd=probe.WORKSPACE)['threadId']
            turn_id = session.start_turn(rpc, thread_id,
                                         text=f'{session.NEUTRAL_PROMPT} {scenario}',
                                         model=settings[0], effort=settings[1],
                                         output_schema=OUTPUT_SCHEMA)
            key = (thread_id, turn_id)
            router.register(*key)
            plan.append(scenario)
            return key

        def finish(key, status):
            """Await and validate one routed terminal result; retain only fixed counters."""
            result = router.await_turn(key, timeout=timeout)
            if result['status'] != status or not result['started'] or result['ignored']:
                raise ContractError('qualification-terminal-mismatch')
            if status == 'completed':
                validate_output(result['text'], status=status)
            results.append({'status': status, 'events': result['events'],
                            'started': result['started'], 'ignored': result['ignored']})

        def cancel(key, holds, closed):
            """Require interrupt acknowledgment, matching terminal and stream closure.

            Record local elapsed milliseconds through both confirmations. This
            includes log polling overhead and gives no live cancellation SLA.
            """
            began = time.monotonic()
            session.interrupt_turn(rpc, *key)
            finish(key, 'interrupted')
            wait_for_counts(holds, closed)
            cancellations.append(round((time.monotonic() - began) * 1000))

        def require_active(key):
            """Inspect history to ensure another turn survived targeted cancellation."""
            turns = session.list_turns(rpc, key[0])
            if not any(t['turnId'] == key[1] and t['status'] == 'inProgress' for t in turns):
                raise ContractError('qualification-foreign-turn-not-active')

        # Qualification deliberately exceeds the selected production limit, on
        # an internal scripted upstream only, to test routing and cancellation.
        first = start('scenario-hold')
        wait_for_counts(1, 0)
        second = start('scenario-hold')
        wait_for_counts(2, 0)
        cancel(first, 2, 1)
        require_active(second)
        foreground = start('scenario-complete', first[0])
        finish(foreground, 'completed')
        require_active(second)
        cancel(second, 2, 2)
        if router.routed < 1 or router.buffered:
            raise ContractError('qualification-interleaving-missing')
        routed = router.routed

        # Fresh inboxes preserve the hard three-turn bound. This separate
        # sequence represents backend-controlled priority: release the only
        # permit after reconciliation before dispatching interactive work.
        router = TurnRouter(rpc)
        background = start('scenario-hold')
        wait_for_counts(3, 2)
        cancel(background, 3, 3)
        interactive = start('scenario-complete', background[0])
        finish(interactive, 'completed')
        if router.buffered:
            raise ContractError('qualification-undrained-inbox')

    requests = probe.upstream_requests(provider_log())
    if [r['scenario'] for r in requests] != plan or any(
            r['model'] != settings[0] or r['effort'] != settings[1]
            or r['format'] != 'json_schema' or not r['schemaMatches'] for r in requests):
        raise ContractError('qualification-upstream-mismatch')
    # Inspect tool exposure on every request, retaining no body or raw log.
    bodies = [json.loads(base64.b64decode(line.split(' ', 3)[3]))
              for line in provider_log().splitlines() if line.startswith('request /v1/responses ')]
    if len(bodies) != 5 or any(body.get('tools') != [] for body in bodies):
        raise ContractError('qualification-tools-exposed')
    return {
        'evidenceVersion': 1, 'runtimeVersion': VERSION,
        'profile': 'managed-narration', 'source': 'account-free-packaged-scripted-upstream',
        'inferenceVerified': False, 'accountVolumeMounted': False,
        'requestCount': len(requests), 'toolsEmptyEveryRequest': True,
        'scripted': {'overlappingHeldRequests': 2, 'targetedCancellationPreservedOtherTurn': True,
                     'interleavedDispatchVerified': True, 'eventsRoutedToOtherTurn': routed,
                     'terminalResults': results,
                     'heldStreamsClosed': 3, 'cancellationElapsedMs': cancellations,
                     'serializedPriorityHandoffVerified': True,
                     'priorityMechanism': 'client-interrupt-reconcile-then-dispatch'},
        'bounds': {'requestTimeoutSeconds': timeout, 'turnTimeoutSeconds': timeout,
                   'upstreamWaitSeconds': timeout, 'cancellationBudgetSeconds': timeout * 3,
                   'registeredTurns': TurnRouter.MAX_TURNS,
                   'bufferedEvents': TurnRouter.MAX_EVENTS, 'liveRequests': 0},
        'decision': serialized_policy(),
    }


def capture(docker):
    """Run the fixed isolated Compose fixture and return evidence after cleanup.

    Input is an absolute Docker executable path; no account or live arguments
    exist. Subprocess/contract failures propagate, including cleanup failure.
    The caller writes evidence only after successful service shutdown.
    """
    compose = [probe.executable_path(docker), 'compose', '-p',
               'ai-rpg-engine-runtime-concurrency', '-f', str(HERE / 'session.compose.yaml')]
    # Refuse to adopt an earlier or concurrent invocation's containers: cleanup
    # may stop only services this capture attempted to create. Retained tmpfs
    # IPC volumes are expected and are neither inspected nor deleted here.
    existing = subprocess.check_output([*compose, 'ps', '--all', '--quiet'], timeout=30)
    if existing.strip():
        raise ContractError('qualification-project-in-use')

    def logs():
        """Read account-free upstream logs; propagate command and timeout failures."""
        return subprocess.check_output([*compose, 'logs', '--no-log-prefix', 'provider'],
                                       timeout=30).decode('utf-8')

    try:
        probe._check_version([*compose, 'run', '--rm', '--no-deps', '-T', 'codex', '--version'])
        subprocess.run([*compose, 'up', '-d', '--wait', 'codex'], check=True,
                       capture_output=True, timeout=300)
        return run_qualification(lambda: [*compose, 'run', '--rm', '--name',
                                           probe.container_name('concurrency'), '-T', 'relay'], logs)
    finally:
        # Never delete volumes; these services have no runtime account mount.
        subprocess.run([*compose, 'down', '--timeout', '10'], check=True,
                       capture_output=True, timeout=120)


def main():
    """Refresh the neutral projection from the fixed fixture; fail before writing on error."""
    report = capture(shutil.which('docker'))
    EVIDENCE.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('Captured five scripted turns; selected installation active-turn limit: 1')


if __name__ == '__main__':
    main()
