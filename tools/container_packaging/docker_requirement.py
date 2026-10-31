"""Decide whether the default Docker CLI checks run, skip or must fail on this host.

The BuildKit context export (`test_build_context.py`) and the rendered Compose
boundary check (`test_compose.py`) need the `docker` executable. They run with
no opt-in flag because ordinary hosted CI provides Docker, and there a missing
CLI must fail rather than hide a broken packaging job. A development host or
container may lack Docker entirely, for example a Linux toolchain container with
no Docker CLI or socket. On such a host the suite would otherwise report
`FileNotFoundError: 'docker'` errors that describe the host, not the packaging
inputs.

Policy:

- the `docker` executable is on `PATH`: the checks run, and any Buildx, Compose,
  daemon or build error still fails them;
- it is absent and Docker is required: the checks run and fail on the missing
  tool. Docker is required when `PACKAGING_REQUIRE_DOCKER=1` (set by the CI
  packaging step) or `GITHUB_ACTIONS=true` (any hosted GitHub Actions job);
- it is absent and nothing requires it: the checks are skipped. The skip reason
  says that hosted CI still enforces them.

Only the complete absence of the executable can lead to a skip. A present but
unusable installation is never treated as missing.

A case that runs a large pinned image it never pulls (`test_model_server.py`) has one
more outcome: the image is absent from a working Docker host, which is true of every
hosted runner. It may skip only for that exact reason and only while
`PACKAGING_DOCKER=1` does not demand the check; any other Docker failure still fails.
"""

from collections.abc import Callable, Mapping
import os
import shutil
import unittest


REQUIRE_FLAG = "PACKAGING_REQUIRE_DOCKER"
DEMAND_FLAG = "PACKAGING_DOCKER"


def docker_required(environ: Mapping[str, str]) -> bool:
    """Return whether a missing `docker` executable must fail instead of skip.

    True for the explicit `PACKAGING_REQUIRE_DOCKER=1` flag and for every hosted
    GitHub Actions job (`GITHUB_ACTIONS=true`), so the guard cannot weaken CI
    even if a workflow step forgets the flag. Any other value counts as unset.
    The generic `CI=true` is deliberately not enough: local tools also set it to
    disable prompts, and it would turn a Docker-less host's skip into errors.
    """
    return environ.get(REQUIRE_FLAG) == "1" or environ.get("GITHUB_ACTIONS") == "true"


def missing_docker_reason(environ: Mapping[str, str] = os.environ,
                          which: Callable[[str], str | None] = shutil.which) -> str | None:
    """Return a skip reason when Docker checks may skip on this host, else None.

    Inputs are the process environment and an executable lookup; both are
    injectable for tests. None means "run the checks": either the executable
    exists, or Docker is required and its absence must surface as a failure.
    """
    if which("docker") is not None or docker_required(environ):
        return None
    return ("docker executable not found; hosted CI enforces this check "
            f"(set {REQUIRE_FLAG}=1 to fail instead of skip)")


def missing_image_reason(image: str, inspect_error: str,
                         environ: Mapping[str, str] = os.environ) -> str | None:
    """Return a skip reason when a case may skip because `image` is absent, else None.

    `inspect_error` is the error output of a failed `docker image inspect`. None means
    the case must fail: `PACKAGING_DOCKER=1` demanded it, or the error is anything other
    than an absent image, such as an unreachable daemon. Any other flag value counts as
    unset, and `PACKAGING_REQUIRE_DOCKER` does not apply: hosted CI sets it and holds no
    copy of this image.
    """
    if environ.get(DEMAND_FLAG) == "1" or "no such image" not in inspect_error.lower():
        return None
    return (f"{image} is not on this host, so nothing was proven about the running service; "
            f"./start.sh --models profile --gpu-check fetches it (set {DEMAND_FLAG}=1 to fail instead of skip)")


def requires_docker_cli(test_class: type) -> type:
    """Class decorator: skip the whole case when `missing_docker_reason` allows it.

    The decision is made once at import, before any `setUp` runs. This avoids
    a `FileNotFoundError` in `setUp`, which unittest would report as an error.
    """
    reason = missing_docker_reason()
    return unittest.skipIf(reason is not None, reason or "")(test_class)
