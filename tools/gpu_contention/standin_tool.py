"""Stand-in for nvidia-smi, docker logs and the GPU competitor, used only by test_contention.py.

The mode comes from a ``--mode=<name>`` argument; every other argument is ignored, so the
same file answers whatever flags the harness appends. It touches no GPU and no network.
"""
import sys
import time

MODES = [arg[len('--mode='):] for arg in sys.argv[1:] if arg.startswith('--mode=')]
STATES = [arg[len('--state='):] for arg in sys.argv[1:] if arg.startswith('--state=')]
mode = MODES[0] if MODES else 'missing-mode'

if mode == 'rising':
    # First call 100 MiB, second 900, then 500: the peak is 900 whatever the number of samples.
    path = STATES[0]
    try:
        with open(path, encoding='ascii') as handle:
            calls = int(handle.read())
    except (OSError, ValueError):
        calls = 0
    with open(path, 'w', encoding='ascii') as handle:
        handle.write(str(calls + 1))
    used = (100, 900)[calls] if calls < 2 else 500
    print(f'0, {used}, 8192')
    print('1, 50, 4096')
elif mode == 'fail':
    sys.exit(9)
elif mode == 'garbage':
    print('No devices were found')
elif mode == 'slow':
    time.sleep(30)
elif mode == 'docker-oom':
    # Flushed in order: the harness merges both streams into one pipe and numbers the lines.
    print('serving request', flush=True)
    print('CUDA error: out of memory', file=sys.stderr, flush=True)
    print('serving request', flush=True)
elif mode == 'sleep':
    time.sleep(60)
elif mode == 'exit3':
    sys.exit(3)
else:
    sys.exit(64)
