"""Check `./start.sh --models fetch` on fixtures: the licence gate, then the real containers.

Every case runs the launcher in a temporary copy whose catalog is a fixture at the real
catalog path, so the launcher has no setting that could point it elsewhere. Every URL is
under the reserved `.invalid` domain, so no case can download a model.

`LauncherGateTest` replaces `docker` with a recording function and needs no Docker. It
proves what is asked and when, and that the container with egress never starts without
confirmation. A function stands in for the terminal test, because a child process here has
no terminal; the real prompt in a terminal is a manual check.

The real-container cases run the pinned image, which is small enough to pull, so hosted CI
runs them too. The real launcher enforces Engine 29.0 and Compose 5.0, which hosted CI may
not provide, so a host gets one deterministic outcome from the versions it reports:
every `RealLauncherTest` case (the launcher's real Compose calls) runs the fetch at or above
the floors and asserts the real launcher's refusal below them; nothing is skipped.
`ComposeServiceTest` calls `docker compose run` directly, never reaches the floors, and needs
only the Docker CLI. A wrapper placed at the
helper's path in the temporary copy gives the real helper a fixture opener; one case keeps the
unmodified helper and its real transport, which fails on the unresolvable name.
"""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import unittest

from docker_requirement import requires_docker_cli
from test_launcher import BASH
from test_model_fetch import file_node, model_node


ROOT = Path(__file__).resolve().parents[2]
CATALOG_PATH = Path("backend/src/main/resources/inference/model-catalog.json")
HELPER_DIRECTORY = Path("docker/model-fetch")
SCHEMA = "rpg-engine.model-catalog/1"
ONE_OFF_CALL = "run --rm --no-deps -T --name "
# The floors `start.sh` enforces in `check_docker`, as (major, minor).
MIN_ENGINE = (29, 0)
MIN_COMPOSE = (5, 0)

DOCKER_STUB = r'''docker() {
    printf '%s\n' "$*" >>"$FIXTURE_LOG"
    case "$*" in
        'context show') printf 'fixture\n' ;;
        'info --format '*) printf 'linux 29.0.0\n' ;;
        'compose version --short') printf '5.0.0\n' ;;
        'buildx version') return 0 ;;
        'compose --file compose.yaml --profile model-fetch run '*' models-license '*)
            printf 'Model fixture license text\n'
            return "${FIXTURE_DESCRIBE_STATUS:-0}" ;;
        'compose --file compose.yaml --profile model-fetch run '*' models-fetch '*)
            printf 'MODELS_DIR=%s\n' "$MODELS_DIR" >>"$FIXTURE_LOG"
            if [ -n "${FIXTURE_INTERRUPT:-}" ]; then kill -INT "$$"; fi
            return "${FIXTURE_INSTALL_STATUS:-0}" ;;
        'stop --time 15 '* | 'rm --force '*) return 0 ;;
        *) printf 'Unexpected Docker call: %s\n' "$*" >&2; return 97 ;;
    esac
}
'''

# Takes the helper's place in a temporary copy only. The real helper stays one file with no
# setting for its transport, so this wrapper is the only way a fixture reaches it.
HELPER_WRAPPER = '''"""Run the real helper with fixture bytes from the mounted directory."""
import importlib.util
import io
from pathlib import Path
import sys
import time
import urllib.error

HERE = Path("/opt/model-fetch")
specification = importlib.util.spec_from_file_location("model_fetch", HERE / "real_model_fetch.py")
helper = importlib.util.module_from_spec(specification)
sys.modules["model_fetch"] = helper
specification.loader.exec_module(helper)


class Response(io.BytesIO):
    headers = {}

    def __init__(self, content, slow):
        super().__init__(content)
        self.slow = slow

    def read(self, size=-1):
        chunk = super().read(size)
        if not chunk and self.slow:
            time.sleep(120)
        return chunk


class Opener:
    def open(self, url, timeout):
        name = url.rsplit("/", 1)[1]
        body = HERE / "fixtures" / name
        if not body.is_file():
            raise urllib.error.URLError("unknown fixture " + url)
        return Response(body.read_bytes(), name.startswith("slow"))


sys.exit(helper.main(sys.argv[1:], Opener()))
'''

# Runs the real helper in the container on the container's own tmpfs, because a bind mount
# from a Windows host reports no POSIX modes. Without the helper's chmod this file gets 0600.
INSTALLED_MODE_CHECK = '''
import importlib.util, io, os, stat, sys
specification = importlib.util.spec_from_file_location('model_fetch', '/opt/model-fetch/model_fetch.py')
helper = importlib.util.module_from_spec(specification)
sys.modules['model_fetch'] = helper
specification.loader.exec_module(helper)
class Response(io.BytesIO):
    headers = {}
class Opener:
    def open(self, url, timeout):
        return Response(b'fixture weights')
os.umask(0o077)
os.mkdir('/tmp/models')
status = helper.main(['install', '--catalog', '/catalog/model-catalog.json', '--models-dir', '/tmp/models',
                      '--accepted', 'fixture', 'fixture'], Opener())
assert status == 0, status
mode = stat.S_IMODE(os.stat('/tmp/models/a.gguf').st_mode)
assert mode == 0o644, oct(mode)
print('installed mode holds')
'''


def write_catalog(checkout: Path, *entries: dict) -> None:
    """Write a fixture catalog at the path the launcher reads in this copy."""
    target = checkout / CATALOG_PATH
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps({"schema": SCHEMA, "entries": list(entries)}), encoding="utf-8", newline="\n")


def environment(**extra: str) -> dict:
    """Return the host environment without settings that could reach a real installation."""
    cleared = {"APP_PORT", "POSTGRES_PASSWORD_FILE", "COMPOSE_PROJECT_NAME", "COMPOSE_PROFILES", "MODELS_DIR"}
    return {**{name: value for name, value in os.environ.items() if name not in cleared}, **extra}


class LauncherGateTest(unittest.TestCase):
    """The gate on the host: nothing with egress starts without a named confirmation."""

    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="launcher fetch ")
        self.addCleanup(temporary.cleanup)
        self.checkout = Path(temporary.name)
        shutil.copyfile(ROOT / "start.sh", self.checkout / "start.sh")
        write_catalog(self.checkout, model_node("fixture", file_node("a.gguf", b"a")))
        (self.checkout / "docker-stub.sh").write_text(DOCKER_STUB, encoding="utf-8", newline="\n")
        self.log = self.checkout / "docker.log"
        self.log.touch()

    def run_launcher(self, *arguments: str, answers: str = "", terminal: bool = False,
                     **fixture: str) -> subprocess.CompletedProcess:
        """Run the fetch command with piped stdin, or sourced with a stand-in for a terminal.

        The sourced form quotes each argument inside the shell: Git Bash expands a `*` in the
        arguments a Windows process hands it, so only that form delivers a wildcard intact.
        Answers are bytes, because text mode on Windows would add a carriage return to each.
        """
        env = environment(BASH_ENV=(self.checkout / "docker-stub.sh").as_posix(),
                          FIXTURE_LOG=self.log.as_posix(), **fixture)
        if terminal:
            quoted = " ".join(f"'{argument}'" for argument in arguments)
            script = (f'source "{(self.checkout / "start.sh").as_posix()}"\n'
                      f'stdin_is_terminal() {{ return 0; }}\nmain --models fetch {quoted}\n')
            command = [BASH, "-c", script]
        else:
            command = [BASH, str(self.checkout / "start.sh"), "--models", "fetch", *arguments]
        result = subprocess.run(command, cwd=self.checkout, env=env, input=answers.encode("utf-8"),
                                capture_output=True, timeout=120, check=False)
        return subprocess.CompletedProcess(command, result.returncode, result.stdout.decode("utf-8"),
                                           result.stderr.decode("utf-8"))

    def calls(self) -> list[str]:
        """Return the recorded Docker calls, one per line."""
        return self.log.read_text(encoding="utf-8").splitlines()

    def install_calls(self) -> list[str]:
        """Return the calls that would start the container with egress."""
        return [call for call in self.calls() if " models-fetch " in call]

    def license_calls(self) -> list[str]:
        """Return the calls that show licenses with no network."""
        return [call for call in self.calls() if " models-license " in call]

    def test_without_a_terminal_or_a_named_confirmation_nothing_starts(self) -> None:
        """The license is shown, the failure names the option, and no download container runs."""
        result = self.run_launcher("fixture", answers="yes\n")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("Model fixture license text", result.stdout)
        self.assertIn("--accept-license fixture", result.stderr)
        self.assertIn("Nothing was downloaded", result.stderr)
        self.assertNotIn("type yes", result.stdout)
        self.assertEqual(1, len(self.license_calls()))
        self.assertEqual([], self.install_calls())
        self.assertFalse((self.checkout / ".local" / "models").exists())

    def test_every_answer_except_yes_refuses(self) -> None:
        """No, end of input and near misses are all refusals, and the model directory stays absent."""
        for answers in ("no\n", "", "\n", "y\n", "YES\n", "yes please\n", " yes\n", "fixture\n"):
            with self.subTest(answers=answers):
                result = self.run_launcher("fixture", answers=answers, terminal=True)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("was not accepted", result.stderr)
                self.assertEqual([], self.install_calls())
                self.assertFalse((self.checkout / ".local" / "models").exists())

    def test_explicit_yes_installs_after_the_license_was_shown(self) -> None:
        """The order is license, question, then the download container with the confirmed ID."""
        result = self.run_launcher("fixture", answers="yes\n", terminal=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertLess(result.stdout.index("Model fixture license text"), result.stdout.index("type yes"))
        self.assertLess(self.calls().index(self.license_calls()[0]), self.calls().index(self.install_calls()[0]))
        self.assertEqual(1, len(self.install_calls()))
        self.assertTrue(self.install_calls()[0].endswith(
            "models-fetch install --catalog /catalog/model-catalog.json --models-dir /models "
            "--accepted fixture fixture"), self.install_calls())
        self.assertIn("model fetch complete: fixture", result.stdout)

    def test_a_carriage_return_after_yes_is_tolerated(self) -> None:
        """A Windows terminal may deliver CR LF; the answer is still the exact word."""
        result = self.run_launcher("fixture", answers="yes\r\n", terminal=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(self.install_calls()))

    def test_named_confirmation_needs_no_prompt_and_still_shows_the_license(self) -> None:
        """Both spellings apply a recorded decision with no terminal and no question."""
        for arguments in (["--accept-license", "fixture", "fixture"], ["fixture", "--accept-license=fixture"]):
            with self.subTest(arguments=arguments):
                self.log.write_text("", encoding="utf-8")
                result = self.run_launcher(*arguments, answers="no\n")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("Model fixture license text", result.stdout)
                self.assertIn("license accepted by --accept-license", result.stdout)
                self.assertNotIn("type yes", result.stdout)
                self.assertEqual(1, len(self.install_calls()))

    def test_confirmation_for_something_else_is_refused_before_any_docker_call(self) -> None:
        """Another ID, an empty list and a wildcard are usage errors that change nothing."""
        for value in ("other", "", "*", "all", "fixture*", ",fixture", "fixture,", "fixture,,other",
                      "fixture,other", "FIXTURE", "../fixture", "fixture other"):
            with self.subTest(value=value):
                result = self.run_launcher("--accept-license", value, "fixture", answers="yes\n", terminal=True)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertEqual([], self.calls())
                self.assertFalse((self.checkout / ".local").exists())

    def test_usage_errors_exit_2_before_any_docker_call(self) -> None:
        """A missing ID has no default, and an unknown option or malformed ID changes nothing."""
        for arguments in ([], ["--bogus", "fixture"], ["--accept-license"], ["--accept-license", "fixture"],
                          ["Fixture"], ["../fixture"], ["*"], ["--force", "fixture"], ["--yes", "fixture"]):
            with self.subTest(arguments=arguments):
                result = self.run_launcher(*arguments, answers="yes\n", terminal=True)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("Usage:", result.stderr)
                self.assertEqual([], self.calls())

    def test_a_partial_confirmation_needs_the_rest(self) -> None:
        """Without a terminal one unnamed ID refuses all; with one, it is asked for alone."""
        result = self.run_launcher("--accept-license", "fixture", "fixture", "other")
        self.assertEqual(1, result.returncode)
        self.assertIn("--accept-license other", result.stderr)
        self.assertEqual([], self.install_calls())
        result = self.run_launcher("--accept-license", "fixture", "fixture", "other", answers="yes\n", terminal=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, result.stdout.count("type yes"))
        self.assertIn("type yes to accept the license of other", result.stdout)
        self.assertTrue(self.install_calls()[0].endswith("--accepted fixture,other fixture other"))

    def test_a_refused_answer_on_the_second_model_refuses_the_first_too(self) -> None:
        """One command is one decision: no download starts until every model is accepted."""
        result = self.run_launcher("fixture", "other", answers="yes\nno\n", terminal=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("the license of other was not accepted", result.stderr)
        self.assertEqual([], self.install_calls())

    def test_a_repeated_id_is_asked_once(self) -> None:
        """Naming a model twice means one question and one download."""
        result = self.run_launcher("fixture", "fixture", answers="yes\n", terminal=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, result.stdout.count("type yes"))
        self.assertTrue(self.install_calls()[0].endswith("--accepted fixture fixture"))

    def test_unknown_model_id_exits_2_and_asks_nothing(self) -> None:
        """The helper's verdict on the catalog passes through."""
        result = self.run_launcher("missing", answers="yes\n", terminal=True, FIXTURE_DESCRIBE_STATUS="2")
        self.assertEqual(2, result.returncode)
        self.assertNotIn("type yes", result.stdout)
        self.assertEqual([], self.install_calls())

    def test_an_unreadable_catalog_stops_before_the_question(self) -> None:
        """A failed license display is never followed by a question."""
        result = self.run_launcher("fixture", answers="yes\n", terminal=True, FIXTURE_DESCRIBE_STATUS="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("could not read the licenses", result.stderr)
        self.assertEqual([], self.install_calls())

    def test_missing_catalog_is_a_failure(self) -> None:
        """The launcher does not start a container for a damaged checkout."""
        (self.checkout / CATALOG_PATH).unlink()
        result = self.run_launcher("fixture")
        self.assertEqual(1, result.returncode)
        self.assertIn("is missing from the checkout", result.stderr)
        self.assertEqual([], self.calls())

    def test_a_failed_install_is_reported_with_its_status(self) -> None:
        """A verification failure is a failure of the command, and says nothing new was installed."""
        result = self.run_launcher("--accept-license", "fixture", "fixture", FIXTURE_INSTALL_STATUS="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("Nothing new was installed", result.stderr)
        self.assertNotIn("model fetch complete", result.stdout)
        result = self.run_launcher("--accept-license", "fixture", "fixture", FIXTURE_INSTALL_STATUS="2")
        self.assertEqual(2, result.returncode)

    def test_interrupt_stops_and_removes_the_named_container(self) -> None:
        """The client may be what Ctrl+C hit, so the launcher stops the container itself."""
        result = self.run_launcher("--accept-license", "fixture", "fixture", FIXTURE_INTERRUPT="1")
        self.assertEqual(130, result.returncode, result.stderr)
        self.assertIn("interrupted", result.stderr)
        name = re.search(r"--name (\S+)", self.install_calls()[0]).group(1)
        self.assertIn(f"stop --time 15 {name}", self.calls())
        self.assertIn(f"rm --force {name}", self.calls())

    def test_both_containers_run_with_the_host_account_and_no_dependency(self) -> None:
        """Files stay the owner's, no other service starts, and both calls are one-off."""
        self.assertEqual(0, self.run_launcher("--accept-license", "fixture", "fixture").returncode)
        for call in self.license_calls() + self.install_calls():
            self.assertIn(ONE_OFF_CALL, call)
            self.assertRegex(call, r"--name rpg-engine-[0-9a-f]{12}-model-fetch-\d+ --user \d+:\d+ models-")

    def test_model_directory_follows_the_servers_setting(self) -> None:
        """The default and MODELS_DIR are the ones the model server mounts."""
        self.assertEqual(0, self.run_launcher("--accept-license", "fixture", "fixture").returncode)
        self.assertIn("MODELS_DIR=./.local/models", self.calls())
        self.assertTrue((self.checkout / ".local" / "models").is_dir())
        self.log.write_text("", encoding="utf-8")
        result = self.run_launcher("--accept-license", "fixture", "fixture", MODELS_DIR="./elsewhere/weights")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("MODELS_DIR=./elsewhere/weights", self.calls())
        self.assertTrue((self.checkout / "elsewhere" / "weights").is_dir())

    def test_help_documents_the_named_confirmation(self) -> None:
        """The non-interactive form is part of the published usage."""
        result = subprocess.run([BASH, str(self.checkout / "start.sh"), "--help"], cwd=self.checkout,
                                env=environment(), input="", capture_output=True, text=True, encoding="utf-8",
                                timeout=60, check=False)
        self.assertEqual(0, result.returncode)
        self.assertIn("--models fetch [--accept-license ID[,ID...]] ID...", result.stdout)


def version_pair(text: str) -> tuple[int, int] | None:
    """Return (major, minor) from a version such as `29.0.1` or `v5.0.0`, or None if unreadable."""
    found = re.match(r"v?(\d+)\.(\d+)", text.strip())
    return (int(found.group(1)), int(found.group(2))) if found else None


def launcher_floor_shortfall(engine_info: str, compose_version: str) -> str | None:
    """Return why the real launcher would refuse these Docker versions, or None when it would not.

    `engine_info` is the `OSType ServerVersion` line and `compose_version` the short Compose
    version, as `start.sh` reads them. Unreadable output gives None on purpose: the launcher-driven
    cases then run and fail on the real problem instead of being skipped for it.
    """
    engine = version_pair(engine_info.partition(" ")[2])
    compose = version_pair(compose_version)
    if engine is not None and engine < MIN_ENGINE:
        return f"Docker Engine {engine[0]}.{engine[1]} is below the launcher floor {MIN_ENGINE[0]}.{MIN_ENGINE[1]}"
    if compose is not None and compose < MIN_COMPOSE:
        return f"Docker Compose {compose[0]}.{compose[1]} is below the launcher floor {MIN_COMPOSE[0]}.{MIN_COMPOSE[1]}"
    return None


def host_floor_shortfall() -> str | None:
    """Return the shortfall of this host's Docker, or None when it meets the floors or cannot be read.

    Evaluated once at import, like the other Docker guards. A missing `docker` executable, an
    unreachable daemon and a timeout all give None, so they stay failures of the cases that need them.
    """
    if shutil.which("docker") is None:
        return None
    try:
        info = subprocess.run(["docker", "info", "--format", "{{.OSType}} {{.ServerVersion}}"],
                              capture_output=True, text=True, timeout=60, check=False)
        compose = subprocess.run(["docker", "compose", "version", "--short"],
                                 capture_output=True, text=True, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if info.returncode != 0 or compose.returncode != 0:
        return None
    return launcher_floor_shortfall(info.stdout, compose.stdout)


FLOOR_SHORTFALL = host_floor_shortfall()


class LauncherFloorPolicyTest(unittest.TestCase):
    """The version comparison that decides which real-container outcome a host gets."""

    def test_versions_at_or_above_the_floors_have_no_shortfall(self) -> None:
        self.assertIsNone(launcher_floor_shortfall("linux 29.0.0", "5.0.0"))
        self.assertIsNone(launcher_floor_shortfall("linux 30.1.2", "v5.3.0\n"))

    def test_an_old_engine_or_compose_is_a_shortfall(self) -> None:
        self.assertIn("Engine 28.5", launcher_floor_shortfall("linux 28.5.1", "5.0.0") or "")
        self.assertIn("Compose 2.40", launcher_floor_shortfall("linux 29.0.0", "v2.40.3") or "")
        self.assertIn("Compose 4.9", launcher_floor_shortfall("linux 29.0.0", "4.9.9") or "")

    def test_unreadable_versions_are_not_a_shortfall(self) -> None:
        """The launcher-driven cases must then run and fail on the real problem."""
        self.assertIsNone(launcher_floor_shortfall("", ""))
        self.assertIsNone(launcher_floor_shortfall("linux unknown", "dev"))


class FixtureCopyMixin:
    """A temporary copy of the launcher and Compose file, with an empty helper directory."""

    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="launcher real fetch ")
        self.addCleanup(temporary.cleanup)
        self.checkout = Path(temporary.name)
        shutil.copyfile(ROOT / "start.sh", self.checkout / "start.sh")
        shutil.copyfile(ROOT / "compose.yaml", self.checkout / "compose.yaml")
        self.helpers = self.checkout / HELPER_DIRECTORY
        self.helpers.mkdir(parents=True)
        self.fixtures = self.helpers / "fixtures"
        self.fixtures.mkdir()
        self.models = self.checkout / ".local" / "models"

    def use_fixture_opener(self) -> None:
        """Put the wrapper at the helper's path, with the real helper beside it."""
        shutil.copyfile(ROOT / HELPER_DIRECTORY / "model_fetch.py", self.helpers / "real_model_fetch.py")
        (self.helpers / "model_fetch.py").write_text(HELPER_WRAPPER, encoding="utf-8", newline="\n")

    def launcher_command(self, *arguments: str) -> list[str]:
        """Return the real launcher's command line for a fetch."""
        return [BASH, str(self.checkout / "start.sh"), "--models", "fetch", *arguments]

    def run_launcher(self, *arguments: str, timeout: int = 300) -> subprocess.CompletedProcess:
        """Run the real launcher with no terminal and no answers."""
        return subprocess.run(self.launcher_command(*arguments), cwd=self.checkout, env=environment(),
                              input="", capture_output=True, text=True, encoding="utf-8", errors="replace",
                              timeout=timeout, check=False)

    def model_tree(self) -> list[str]:
        """Return everything below the model directory, staging included."""
        return sorted(path.relative_to(self.models).as_posix() for path in self.models.rglob("*"))

    def compose_python(self, service: str, code: str) -> subprocess.CompletedProcess:
        """Run Python in a service as Compose renders it, in place of the helper."""
        return subprocess.run(
            ["docker", "compose", "--project-name", "engine-model-fetch-check", "--file", "compose.yaml",
             "--profile", "model-fetch", "run", "--rm", "--no-deps", "-T", "--entrypoint", "python",
             service, "-I", "-c", code],
            cwd=self.checkout, env=environment(), input="", capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=300, check=False)


@requires_docker_cli
class RealLauncherTest(FixtureCopyMixin, unittest.TestCase):
    """The launcher's real Compose calls in the pinned image, on fixture bytes.

    The real `check_docker` runs first, so a full run needs Engine 29.0 and Compose 5.0. On a host
    below them every case asserts the real launcher's refusal instead and returns, so the outcome is
    deterministic from the reported versions and no case is skipped. The fetch itself is then
    unproven on that host: only a conforming host proves it.
    """

    def below_floors_refused(self) -> bool:
        """Return True after asserting the refusal on a host below the floors; False on a conforming one."""
        if FLOOR_SHORTFALL is None:
            return False
        write_catalog(self.checkout, model_node("fixture", file_node("a.gguf", b"a")))
        result = self.run_launcher("--accept-license", "fixture", "fixture", timeout=120)
        self.assertEqual(1, result.returncode, FLOOR_SHORTFALL + result.stdout[-2000:] + result.stderr[-2000:])
        self.assertIn("older than the required", result.stderr)
        self.assertNotIn("License:", result.stdout)
        self.assertFalse(self.models.exists())
        return True

    def test_matching_fixture_is_installed_on_the_real_bind_mount(self) -> None:
        """License, verification and the move all work under the container's restrictions."""
        if self.below_floors_refused():
            return
        self.use_fixture_opener()
        content = b"fixture weights"
        (self.fixtures / "a.gguf").write_bytes(content)
        write_catalog(self.checkout, model_node("fixture", file_node("a.gguf", content)))
        result = self.run_launcher("--accept-license", "fixture", "fixture")
        self.assertEqual(0, result.returncode, result.stdout[-2000:] + result.stderr[-2000:])
        self.assertLess(result.stdout.index("License: fixture-license"), result.stdout.index("installed a.gguf"))
        self.assertEqual(["a.gguf"], self.model_tree())
        self.assertEqual(hashlib.sha256(content).hexdigest(),
                         hashlib.sha256((self.models / "a.gguf").read_bytes()).hexdigest())

    def test_mismatches_install_nothing_on_the_real_bind_mount(self) -> None:
        """A wrong digest and a wrong size each fail and leave an empty model directory."""
        if self.below_floors_refused():
            return
        self.use_fixture_opener()
        content = b"fixture weights"
        for name, served, reason in (("hash", b"FIXTURE WEIGHTS", "SHA-256 mismatch"),
                                     ("size", b"short", "size mismatch")):
            with self.subTest(name):
                (self.fixtures / "a.gguf").write_bytes(served)
                write_catalog(self.checkout, model_node("fixture", file_node("a.gguf", content)))
                result = self.run_launcher("--accept-license", "fixture", "fixture")
                self.assertEqual(1, result.returncode, result.stdout[-2000:] + result.stderr[-2000:])
                self.assertIn(reason, result.stdout + result.stderr)
                self.assertEqual([], self.model_tree())

    def test_refusal_starts_no_download_on_the_real_path(self) -> None:
        """Without confirmation the real launcher stops and creates no model directory."""
        if self.below_floors_refused():
            return
        self.use_fixture_opener()
        write_catalog(self.checkout, model_node("fixture", file_node("a.gguf", b"a")))
        result = self.run_launcher("fixture")
        self.assertEqual(1, result.returncode, result.stderr[-2000:])
        self.assertIn("License: fixture-license", result.stdout)
        self.assertIn("--accept-license fixture", result.stderr)
        self.assertFalse(self.models.exists())

    def test_unknown_id_exits_2_through_the_real_container(self) -> None:
        """The helper's exit status survives Compose."""
        if self.below_floors_refused():
            return
        self.use_fixture_opener()
        write_catalog(self.checkout, model_node("fixture", file_node("a.gguf", b"a")))
        result = self.run_launcher("--accept-license", "missing", "missing")
        self.assertEqual(2, result.returncode, result.stdout[-2000:] + result.stderr[-2000:])
        self.assertIn("unknown model ID 'missing'", result.stdout + result.stderr)
        self.assertFalse(self.models.exists())

    def test_unmodified_helper_fails_cleanly_on_the_reserved_name(self) -> None:
        """The real transport runs; the `.invalid` name cannot resolve, so nothing is fetched."""
        if self.below_floors_refused():
            return
        shutil.copyfile(ROOT / HELPER_DIRECTORY / "model_fetch.py", self.helpers / "model_fetch.py")
        write_catalog(self.checkout, model_node("fixture", file_node("a.gguf", b"a")))
        result = self.run_launcher("--accept-license", "fixture", "fixture")
        self.assertEqual(1, result.returncode, result.stdout[-2000:] + result.stderr[-2000:])
        self.assertIn("download failed", result.stdout + result.stderr)
        self.assertEqual([], self.model_tree())

    def test_stopping_the_container_removes_the_unfinished_download(self) -> None:
        """`docker stop`, which the launcher's interrupt handler sends, leaves nothing behind."""
        if self.below_floors_refused():
            return
        self.use_fixture_opener()
        (self.fixtures / "slow.gguf").write_bytes(b"part")
        write_catalog(self.checkout, model_node("fixture", file_node("slow.gguf", b"part and more")))
        process = subprocess.Popen(self.launcher_command("--accept-license", "fixture", "fixture"),
                                   cwd=self.checkout, env=environment(), stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                   encoding="utf-8", errors="replace")
        self.addCleanup(process.communicate)
        self.addCleanup(process.kill)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline and not list(self.models.rglob("*.part")):
            self.assertIsNone(process.poll(), "the launcher ended before the download started")
            time.sleep(0.25)
        self.assertTrue(list(self.models.rglob("*.part")), "no unfinished download appeared")
        identity = (self.checkout / ".local" / "installation-id").read_text(encoding="utf-8").strip()
        prefix = f"rpg-engine-{identity}-model-fetch-"
        listed = subprocess.run(["docker", "ps", "--filter", f"name={prefix}", "--format", "{{.Names}}"],
                                capture_output=True, text=True, timeout=30, check=True)
        names = listed.stdout.split()
        self.assertEqual(1, len(names), listed.stdout)
        self.addCleanup(subprocess.run, ["docker", "rm", "--force", names[0]], capture_output=True, timeout=30)
        started = time.monotonic()
        subprocess.run(["docker", "stop", "--time", "15", names[0]], capture_output=True, timeout=60, check=True)
        self.assertLess(time.monotonic() - started, 14, "the helper ignored the stop signal")
        output, error = process.communicate(timeout=60)
        self.assertEqual(1, process.returncode, output[-2000:] + error[-2000:])
        self.assertEqual([], self.model_tree())


@requires_docker_cli
class ComposeServiceTest(FixtureCopyMixin, unittest.TestCase):
    """The rendered services run directly with `docker compose`, so no launcher floor applies.

    These cases call `docker compose run` themselves and never reach `check_docker`; only the
    Docker CLI is required, like the other Compose cases. They were run on the reference host's
    Compose; their outcome on the older Compose of hosted CI is not proven here.
    """

    def test_service_boundaries_on_the_real_containers(self) -> None:
        """The license service has no route and no writable path; the fetch image trusts CAs."""
        write_catalog(self.checkout, model_node("fixture", file_node("a.gguf", b"a")))
        (self.helpers / "model_fetch.py").write_text("", encoding="utf-8", newline="\n")
        self.models.mkdir(parents=True)
        boundaries = (
            "import os, pathlib\n"
            "routes = pathlib.Path('/proc/net/route').read_text().splitlines()[1:]\n"
            "assert not routes, routes\n"
            "assert os.getuid() == 65534\n"
            "assert not os.path.exists('/models')\n"
            "for target in ('/opt/model-fetch/x', '/catalog/x', '/x'):\n"
            "    try:\n"
            "        open(target, 'w')\n"
            "    except OSError:\n"
            "        continue\n"
            "    raise AssertionError(target + ' is writable')\n"
            "print('boundaries hold')\n")
        result = self.compose_python("models-license", boundaries)
        self.assertEqual(0, result.returncode, result.stdout[-2000:] + result.stderr[-2000:])
        self.assertIn("boundaries hold", result.stdout)
        trust = ("import ssl\n"
                 "assert ssl.create_default_context().cert_store_stats()['x509_ca'] > 0\n"
                 "print('trust store present')\n")
        result = self.compose_python("models-fetch", trust)
        self.assertEqual(0, result.returncode, result.stdout[-2000:] + result.stderr[-2000:])
        self.assertIn("trust store present", result.stdout)

    def test_installed_file_is_readable_by_the_server_account_on_posix(self) -> None:
        """Under umask 077 the real helper still leaves mode 0644 for the server's user 65534."""
        shutil.copyfile(ROOT / HELPER_DIRECTORY / "model_fetch.py", self.helpers / "model_fetch.py")
        write_catalog(self.checkout, model_node("fixture", file_node("a.gguf", b"fixture weights")))
        result = self.compose_python("models-license", INSTALLED_MODE_CHECK)
        self.assertEqual(0, result.returncode, result.stdout[-2000:] + result.stderr[-2000:])
        self.assertIn("installed mode holds", result.stdout)


if __name__ == "__main__":
    unittest.main()
