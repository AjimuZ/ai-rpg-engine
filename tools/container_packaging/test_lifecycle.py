"""Exercise the launcher lifecycle against real Docker when PACKAGING_LIFECYCLE=1 (P02.7, QA-28).

The test drives the real root `start.sh` in an isolated copy of the tracked files
placed in a directory whose name contains a space (`.local/scratch/lifecycle check`).
The copy has its own installation identity, so it owns a distinct Compose project,
its own volumes and its own database password; the developer's real checkout and any
installation in it are never touched. It is invoked from an unrelated working
directory to prove the launcher does not depend on the caller's directory.

Phases (each asserts, then records measurements in the report):
  1. first start, repeated start (idempotence: same containers, same secret);
  2. neutral campaign stand-ins written to PostgreSQL and the content volume;
  3. graceful stop: every service exits 0 within the grace budget, volumes kept;
  4. restart: containers recreated, markers and the password unchanged;
  5. interruption during the image build and during service startup, then recovery
     by simply rerunning;
  6. image recreation: a changed build input yields a new engine image and the
     markers survive.

Failure behavior: any Docker, launcher, timing or persistence error fails the test.
The services are always stopped at the end; named volumes are retained on purpose
(volumes are never deleted by this suite, and a rerun reuses the same copy and
installation). Set PACKAGING_LIFECYCLE_REPORT to a path to write a sanitized JSON
report (timings, exit codes and booleans only; no secrets, IDs or account data).
The interrupt is SIGINT sent from a Git Bash job-control shell to the launcher job.
Bash runs the launcher's trap only after the foreground `docker compose` command
ends, and the native docker client does not receive a Cygwin-delivered SIGINT, so
the in-flight build or `up` normally runs to completion first. The test therefore
proves the trap, exit status 130, the message, an untouched state and recovery by
rerunning; it does not prove that a human Ctrl+C on an interactive terminal cancels
the Docker client (the console sends the event to every attached process). The
roadmap and docs/22 state this limit.
"""

import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import time
import unittest

from test_launcher import BASH, ROOT, docker_daemon_available


# A port unlikely to collide with the developer's own installation (default 8080).
TEST_PORT = "18480"
COPY = ROOT / ".local" / "scratch" / "lifecycle check"
# Compose gives each service 30 s; --stop passes 35 s to `down`. A graceful stop of
# this idle stack must finish far below that; the bound catches a SIGKILL fallback.
STOP_BUDGET_SECONDS = 40


def tracked_files() -> list[str]:
    """Return repository-relative paths of tracked files (NUL-separated, so spaces are safe)."""
    result = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True,
                            timeout=30, check=True)
    return [name for name in result.stdout.decode("utf-8").split("\0") if name]


def refresh_copy() -> None:
    """Copy tracked working-tree files into COPY, keeping its ignored `.local` and `.env`.

    Only tracked files are copied, which also simulates a clean clone: no private
    state of the developer's checkout (secrets, installation id, sign-in) is carried
    over. Files deleted upstream are not pruned; the rebuild input set is unchanged.
    """
    COPY.mkdir(parents=True, exist_ok=True)
    for name in tracked_files():
        source = ROOT / name
        if not source.is_file():
            continue  # a tracked file deleted in the working tree
        target = COPY / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def sha256(path: Path) -> str:
    """Hash a file without exposing its content; used only to compare, never printed."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


@unittest.skipUnless(os.environ.get("PACKAGING_LIFECYCLE") == "1",
                     "Set PACKAGING_LIFECYCLE=1 to run the real-Docker launcher lifecycle check")
class LauncherLifecycleTest(unittest.TestCase):
    """Run the whole lifecycle as one ordered scenario (phases share one installation)."""

    report: dict

    def setUp(self) -> None:
        """Skip without a daemon; otherwise prepare the isolated copy and the environment."""
        if not docker_daemon_available():
            self.skipTest("no reachable Docker daemon")
        refresh_copy()
        self.env = dict(os.environ, APP_PORT=TEST_PORT)
        for name in ("POSTGRES_PASSWORD_FILE", "COMPOSE_PROJECT_NAME"):
            self.env.pop(name, None)
        self.report = {
            "host": f"{platform.system()} {platform.release()}",
            "shell": "Git Bash", "phases": {},
        }
        # The working directory is deliberately unrelated to the checkout.
        self.elsewhere = Path(tempfile.gettempdir())
        self.addCleanup(self.stop_quietly)

    # --- helpers ---------------------------------------------------------------

    def launcher(self, *args: str, timeout: int = 900) -> tuple[subprocess.CompletedProcess, float]:
        """Run the copied start.sh from an unrelated directory; return result and seconds."""
        began = time.monotonic()
        result = subprocess.run([BASH, str(COPY / "start.sh"), *args], cwd=self.elsewhere,
                                env=self.env, capture_output=True, text=True,
                                timeout=timeout, check=False)
        return result, round(time.monotonic() - began, 1)

    def stop_quietly(self) -> None:
        """Always stop the owned stack (volumes are kept); failures here are not masked."""
        if (COPY / "start.sh").exists() and (COPY / ".local" / "installation-id").exists():
            self.launcher("--stop", timeout=120)

    def project(self) -> str:
        """Return the copy's Compose project name from its stored installation id."""
        identity = (COPY / ".local" / "installation-id").read_text().strip()
        return f"rpg-engine-{identity}"

    def docker(self, *args: str, timeout: int = 120) -> str:
        """Run a Docker command and return stdout; a non-zero exit fails with bounded stderr."""
        result = subprocess.run(["docker", *args], capture_output=True, text=True,
                                timeout=timeout, check=False)
        self.assertEqual(0, result.returncode, result.stderr[-1500:])
        return result.stdout.strip()

    def container_ids(self) -> dict[str, str]:
        """Map service name to container ID for the copy's project (running or not)."""
        rows = self.docker("ps", "--all", "--no-trunc", "--filter",
                           f"label=com.docker.compose.project={self.project()}",
                           "--format", '{{.Label "com.docker.compose.service"}} {{.ID}}')
        return dict(line.split(" ", 1) for line in rows.splitlines() if line)

    def engine_image_id(self) -> str:
        """Return the image ID the engine container was created from."""
        return self.docker("inspect", "--format", "{{.Image}}", self.container_ids()["engine"])

    def sql(self, statement: str) -> str:
        """Run neutral SQL inside postgres over a password-authenticated TCP connection.

        The password is read inside the container from the mounted secret, never
        placed on a host command line or printed.
        """
        return self.docker(
            "exec", self.container_ids()["postgres"], "sh", "-ec",
            'export PGPASSWORD="$(cat /run/secrets/postgres_password)"; '
            'exec psql -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -Atc "$1"',
            "fixture", statement)

    def volume_names(self) -> set[str]:
        """Return the named volumes labelled with the copy's project."""
        names = self.docker("volume", "ls", "--filter",
                            f"label=com.docker.compose.project={self.project()}",
                            "--format", "{{.Name}}")
        return set(names.split())

    def assert_started(self, result: subprocess.CompletedProcess) -> None:
        """Assert a successful launch that reached storage readiness."""
        self.assertEqual(0, result.returncode, (result.stdout + result.stderr)[-2500:])
        self.assertIn("storage: ready", result.stdout)

    # --- scenario --------------------------------------------------------------

    def test_lifecycle(self) -> None:
        """Run phases 1-6 in order; each phase's measurements land in the report."""
        phases = self.report["phases"]
        first_ever = not (COPY / ".local" / "installation-id").exists()

        # 1. Launch twice. The second run must only confirm the running stack.
        result, seconds = self.launcher()
        self.assert_started(result)
        phases["start"] = {"firstInstallation": first_ever, "seconds": seconds}
        project = self.project()
        secret_file = COPY / ".local" / "secrets" / "postgres-password"
        secret_hash = sha256(secret_file)
        before = self.container_ids()
        self.assertEqual({"engine", "postgres", "codex"}, set(before))

        result, seconds = self.launcher()
        self.assert_started(result)
        self.assertEqual(before, self.container_ids(), "repeated launch recreated containers")
        self.assertEqual(secret_hash, sha256(secret_file), "repeated launch rewrote the secret")
        status, _ = self.launcher("--status")
        # Exit 3 is the documented "storage ready, gameplay not ready" status; gameplay
        # readiness is unimplemented until P14, so a fully-ready 0 is not expected yet.
        self.assertEqual(3, status.returncode, status.stdout + status.stderr)
        self.assertIn("storage: ready", status.stdout)
        phases["repeatStart"] = {"seconds": seconds, "containersUnchanged": True,
                                 "secretUnchanged": True}

        # 2. Neutral campaign stand-ins: a database row and a content-volume file.
        marker = hashlib.sha256(os.urandom(16)).hexdigest()[:16]
        self.sql("CREATE TABLE IF NOT EXISTS lifecycle_marker (token text PRIMARY KEY)")
        self.sql(f"INSERT INTO lifecycle_marker VALUES ('{marker}')")
        self.docker("exec", self.container_ids()["engine"], "sh", "-ec",
                    f"printf %s {marker} > /var/lib/engine/content/lifecycle-marker")

        def markers_intact() -> bool:
            """True when both stand-ins are readable with the expected marker."""
            row = self.sql(f"SELECT token FROM lifecycle_marker WHERE token = '{marker}'")
            filed = self.docker("exec", self.container_ids()["engine"], "cat",
                                "/var/lib/engine/content/lifecycle-marker")
            return row == marker and filed == marker

        self.assertTrue(markers_intact())
        volumes = self.volume_names()
        self.assertGreaterEqual(len(volumes), 4, volumes)

        # 3. Graceful stop: record die events (exit codes) emitted during --stop.
        since = int(time.time())
        result, seconds = self.launcher("--stop", timeout=120)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        events = self.docker(
            "events", "--since", str(since), "--until", str(int(time.time()) + 1),
            "--filter", "type=container", "--filter", "event=die",
            "--filter", f"label=com.docker.compose.project={project}",
            "--format", '{{index .Actor.Attributes "com.docker.compose.service"}} {{.Actor.Attributes.exitCode}}')
        codes = dict(line.split(" ", 1) for line in events.splitlines() if line)
        self.assertEqual({"engine", "postgres", "codex"}, set(codes), events)
        # postgres and the init-wrapped runtime exit 0. The JVM exits 143 (128 + SIGTERM)
        # after running its shutdown hooks, which is a clean stop; 137 (SIGKILL after
        # the grace period) or any other code means the service did not stop gracefully.
        self.assertEqual("0", codes["postgres"], codes)
        self.assertEqual("0", codes["codex"], codes)
        self.assertIn(codes["engine"], {"0", "143"}, codes)
        self.assertLess(seconds, STOP_BUDGET_SECONDS)
        self.assertEqual({}, self.container_ids(), "stop left containers behind")
        self.assertEqual(volumes, self.volume_names(), "stop changed the volume set")
        phases["gracefulStop"] = {"seconds": seconds, "exitCodes": codes, "volumesKept": True}

        # 4. Restart: new containers, same data and password.
        result, seconds = self.launcher()
        self.assert_started(result)
        self.assertNotEqual(before["postgres"], self.container_ids()["postgres"])
        self.assertTrue(markers_intact(), "markers lost across stop/start")
        self.assertEqual(secret_hash, sha256(secret_file))
        phases["restart"] = {"seconds": seconds, "markersIntact": True, "secretUnchanged": True}
        image_before = self.engine_image_id()

        # 5. Interrupt a rebuild. A changed build input forces real build work; the
        # signal goes to the launcher's process group like a terminal Ctrl+C does.
        self.launcher("--stop", timeout=120)
        page = COPY / "frontend" / "src" / "index.html"
        page.write_text(page.read_text(encoding="utf-8") + f"\n<!-- lifecycle {marker} -->\n",
                        encoding="utf-8")
        # The first interrupt lands right after the build begins, the second right after
        # the "starting services" line, so both long operations are covered.
        building = self.interrupt_launcher("building images")
        phases["interruptDuringBuild"] = building
        self.assertEqual(130, building["exitCode"], building)
        self.assertTrue(building["messageShown"], building)
        self.assertEqual({}, self.container_ids(), "an interrupted build must not leave services")
        starting = self.interrupt_launcher("starting services")
        phases["interruptDuringStart"] = starting
        self.assertEqual(130, starting["exitCode"], starting)
        self.assertTrue(starting["messageShown"], starting)

        # 6. Recover by rerunning; the new input yields a new image, data unchanged.
        result, seconds = self.launcher()
        self.assert_started(result)
        self.assertNotEqual(image_before, self.engine_image_id(), "engine image was not recreated")
        self.assertTrue(markers_intact(), "markers lost across image recreation")
        self.assertEqual(secret_hash, sha256(secret_file))
        phases["imageRecreation"] = {"seconds": seconds, "newImage": True, "markersIntact": True,
                                     "secretUnchanged": True}
        self.assertEqual(volumes, self.volume_names())

        out = os.environ.get("PACKAGING_LIFECYCLE_REPORT")
        if out:
            Path(out).write_text(json.dumps(self.report, indent=2, sort_keys=True) + "\n",
                                 encoding="utf-8")

    def interrupt_launcher(self, marker: str) -> dict:
        """Start the launcher in a job-control shell, interrupt it after MARKER, and report.

        A Git Bash driver with job control (`set -m`) runs the launcher as its own
        job, waits until MARKER appears in its output, then sends SIGINT to the job
        (the process group first, the PID as fallback) after a short delay. Waiting
        for MARKER guarantees the signal arrives while that phase is running, not
        before the script began. As the module docstring explains, the trap fires once
        the in-flight Docker command ends; this is a harness limit, not a launcher one.
        Returns the exit status, whether the interrupt message was shown, and seconds.
        """
        driver = (
            'set -m; out="$1"; marker="$2"; shift 2; "$@" >"$out" 2>&1 & pid=$!; '
            'for _ in $(seq 1 240); do grep -q "$marker" "$out" 2>/dev/null && break; sleep 0.25; done; '
            'sleep 1; kill -INT -- "-$pid" 2>/dev/null || kill -INT "$pid"; wait "$pid"; '
            'echo "exit=$?" >"$out.status"'
        )
        out = COPY / ".local" / "interrupt.log"
        status_file = Path(str(out) + ".status")
        for leftover in (out, status_file):
            leftover.unlink(missing_ok=True)
        began = time.monotonic()
        subprocess.run([BASH, "-c", driver, "driver", str(out), marker, BASH, str(COPY / "start.sh")],
                       cwd=self.elsewhere, env=self.env, timeout=300, check=False)
        text = out.read_text(encoding="utf-8", errors="replace") if out.exists() else ""
        status = status_file.read_text() if status_file.exists() else ""
        match = re.search(r"exit=(\d+)", status)
        return {
            "marker": marker,
            "exitCode": int(match.group(1)) if match else None,
            "messageShown": "interrupted" in text,
            "seconds": round(time.monotonic() - began, 1),
        }


if __name__ == "__main__":
    unittest.main()
