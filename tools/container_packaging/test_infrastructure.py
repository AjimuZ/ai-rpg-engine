"""Exercise account-free infrastructure recreation when PACKAGING_DOCKER=1.

Inputs are tracked Compose settings and a locally generated fixture password.
The test starts only its dedicated project, writes neutral persistence markers,
and always stops owned services. Named volumes and their private password file
are deliberately retained. Docker, health, permission or persistence errors fail.
"""

import json
import os
from pathlib import Path
import secrets
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
PROJECT = "engine-packaging-check"
STATE = ROOT / ".local" / "packaging-check"
SOCKET = "/tmp/codex-daemon-10001/bd2fc4d5dd4ec410275f7d5460680ca9c0ce1ef1b48e49b182f350ff18153681"


@unittest.skipUnless(os.environ.get("PACKAGING_DOCKER") == "1", "Set PACKAGING_DOCKER=1 for service recreation")
class InfrastructureTest(unittest.TestCase):
    """Test real isolated services, with no account or gameplay/inference request."""

    def compose(self, *arguments: str, timeout: int = 180) -> str:
        """Run fixture-only Compose arguments; return stdout or fail with bounded stderr."""
        result = subprocess.run(
            ["docker", "compose", "--project-name", PROJECT,
             "--env-file", str(ROOT / ".env.example"),
             "--file", str(ROOT / "compose.yaml"), *arguments],
            env=self.environment, capture_output=True, text=True,
            timeout=timeout, check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr[-3000:])
        return result.stdout.strip()

    def database(self, statement: str) -> str:
        """Execute neutral SQL over password-authenticated TCP; fail on SQL/auth errors.

        Read the fixture password inside postgres, never into host command arguments
        or output. A local Unix-socket trust connection would not test the secret.
        """
        return self.compose(
            "exec", "-T", "postgres", "sh", "-ec",
            'export PGPASSWORD="$(cat /run/secrets/postgres_password)"; '
            'exec psql -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -Atc "$1"',
            "fixture", statement,
        )

    def inspect(self, service: str) -> dict:
        """Return one owned container's Docker metadata; missing/invalid metadata fails."""
        identifier = self.compose("ps", "--quiet", service)
        result = subprocess.run(["docker", "inspect", identifier], capture_output=True,
                                text=True, timeout=30, check=False)
        self.assertEqual(0, result.returncode, result.stderr[-1000:])
        return json.loads(result.stdout)[0]

    def test_recreation_preserves_private_storage(self) -> None:
        """Verify topology, UID/socket isolation and durable neutral markers on recreation."""
        STATE.mkdir(parents=True, exist_ok=True)
        password = STATE / "postgres-password"
        # Reuse this private fixture secret because the retained database volume
        # is initialized only once. Never copy an owner/application secret.
        if not password.exists():
            with password.open("x", encoding="utf-8", newline="\n") as output:
                output.write(secrets.token_urlsafe(32) + "\n")
            password.chmod(0o600)
        self.environment = dict(os.environ, POSTGRES_PASSWORD_FILE=str(password))
        # Never adopt or stop pre-existing containers, even with our fixture name.
        self.assertEqual("", self.compose("ps", "--all", "--quiet"),
                         "Fixture project already has containers; inspect them before rerunning")
        self.addCleanup(self.compose, "down", "--timeout", "30")
        self.compose("up", "--detach", "--build", "--wait", "--wait-timeout", "90", timeout=300)
        database = self.inspect("postgres")
        runtime = self.inspect("codex")
        self.assertEqual({PROJECT + "_data"}, set(database["NetworkSettings"]["Networks"]))
        self.assertEqual({PROJECT + "_inference"}, set(runtime["NetworkSettings"]["Networks"]))
        for container in (database, runtime):
            self.assertTrue(container["HostConfig"]["ReadonlyRootfs"])
            self.assertFalse(container["HostConfig"].get("PortBindings"))
        self.assertEqual("10001:10001", runtime["Config"]["User"])
        self.assertEqual(["ALL"], runtime["HostConfig"]["CapDrop"])
        self.assertEqual(
            {"/var/lib/runtime/home", "/var/lib/runtime/workspace", "/tmp/codex-daemon-10001"},
            {mount["Destination"] for mount in runtime["Mounts"] if mount["Type"] == "volume"},
        )
        self.assertFalse(any(mount["Type"] == "bind" for mount in runtime["Mounts"]))
        self.assertFalse(any("POSTGRES" in value for value in runtime["Config"]["Env"]))
        self.assertEqual("10001", self.compose("exec", "-T", "codex", "id", "-u"))
        self.assertEqual("600:10001:10001", self.compose(
            "exec", "-T", "codex", "stat", "-c", "%a:%u:%g", SOCKET))
        self.assertEqual("700:10001:10001", self.compose(
            "exec", "-T", "codex", "stat", "-c", "%a:%u:%g", "/tmp/codex-daemon-10001"))
        # The shared UID is necessary: another unprivileged identity cannot even
        # traverse the socket directory, despite running in the same container.
        self.compose("exec", "-T", "--user", "10002:10002", "codex", "sh", "-ec",
                     "test ! -x /tmp/codex-daemon-10001")
        self.compose("exec", "-T", "codex", "sh", "-ec",
                     "test ! -e /run/secrets/postgres_password; test ! -e /var/lib/postgresql")
        self.assertEqual("1", self.database("SELECT 1"))
        # Supplying a password is not proof it is required: reject passwordless
        # TCP explicitly so a trust-authentication regression cannot pass.
        self.compose("exec", "-T", "postgres", "sh", "-ec",
                     'unset PGPASSWORD; export PGPASSFILE=/dev/null; '
                     'if psql -w -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" '
                     '-c "SELECT 1" >/dev/null 2>&1; then exit 1; fi')
        # A sentinel in each durable directory detects accidental anonymous/tmpfs
        # mounts even when Compose's rendered configuration looks correct.
        marker = secrets.token_hex(16)
        self.database("CREATE TABLE IF NOT EXISTS packaging_check (marker text PRIMARY KEY)")
        self.database(f"INSERT INTO packaging_check VALUES ('{marker}')")
        self.compose("exec", "-T", "codex", "sh", "-ec",
                     'printf "%s" "$1" > /var/lib/runtime/home/.packaging-check; '
                     'printf "%s" "$1" > /var/lib/runtime/workspace/.packaging-check',
                     "fixture", marker)
        self.compose("up", "--detach", "--force-recreate", "--wait", "--wait-timeout", "90")
        self.assertNotEqual(database["Id"], self.inspect("postgres")["Id"])
        self.assertNotEqual(runtime["Id"], self.inspect("codex")["Id"])
        self.assertEqual(marker, self.database(
            f"SELECT marker FROM packaging_check WHERE marker = '{marker}'"))
        for directory in ("home", "workspace"):
            self.assertEqual(marker, self.compose("exec", "-T", "codex", "cat",
                                                 f"/var/lib/runtime/{directory}/.packaging-check"))
        # Only fixture-owned neutral markers are removed; volumes remain intact.
        self.database(f"DELETE FROM packaging_check WHERE marker = '{marker}'")
        self.compose("exec", "-T", "codex", "rm",
                     "/var/lib/runtime/home/.packaging-check",
                     "/var/lib/runtime/workspace/.packaging-check")


if __name__ == "__main__":
    unittest.main()
