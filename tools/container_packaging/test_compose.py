"""Check rendered infrastructure boundaries without reading private configuration.

Inputs are the tracked Compose and example environment files. Outputs are test
assertions; missing Compose, invalid configuration, or unsafe mounts fail tests.
No services are started and no existing account or secret contents are read.
"""

import json
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]


class ComposeTest(unittest.TestCase):
    """Validate Compose's resolved configuration; unsafe service boundaries fail."""

    def setUp(self) -> None:
        """Render tracked inputs only and store JSON; fail on missing/invalid files."""
        result = subprocess.run(
            ["docker", "compose", "--env-file", str(ROOT / ".env.example"),
             "--file", str(ROOT / "compose.yaml"), "config", "--format", "json"],
            capture_output=True, text=True, timeout=30, check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.config = json.loads(result.stdout)

    def test_network_and_secret_boundaries(self) -> None:
        """Require separate networks, no host ports, and a database-only file secret."""
        services = self.config["services"]
        self.assertEqual({"postgres", "codex"}, set(services))
        self.assertEqual({"data"}, set(services["postgres"]["networks"]))
        self.assertEqual({"inference"}, set(services["codex"]["networks"]))
        self.assertTrue(self.config["networks"]["data"]["internal"])
        self.assertFalse(self.config["networks"]["inference"].get("internal", False))
        for service in services.values():
            self.assertFalse(service.get("ports"))
            self.assertNotIn("network_mode", service)
        database = services["postgres"]
        self.assertEqual("/run/secrets/postgres_password",
                         database["environment"]["POSTGRES_PASSWORD_FILE"])
        self.assertNotIn("POSTGRES_PASSWORD", database["environment"])
        self.assertEqual([{"source": "postgres_password", "target": "/run/secrets/postgres_password"}],
                         database["secrets"])
        runtime = services["codex"]
        self.assertFalse(runtime.get("secrets"))
        self.assertFalse(runtime.get("environment"))
        self.assertEqual("10001:10001", runtime["user"])
        self.assertTrue(runtime["read_only"])
        self.assertEqual(["ALL"], runtime["cap_drop"])
        self.assertIn("no-new-privileges:true", runtime["security_opt"])

    def test_storage_and_image_boundaries(self) -> None:
        """Reject host binds and shared account/data mounts; require pinned inputs."""
        expected = {
            "postgres": {"database": "/var/lib/postgresql"},
            "codex": {"runtime_account": "/var/lib/runtime/home",
                      "runtime_workspace": "/var/lib/runtime/workspace",
                      "runtime_ipc": "/tmp/codex-daemon-10001"},
        }
        for name, mounts in expected.items():
            service = self.config["services"][name]
            self.assertEqual(mounts, {mount["source"]: mount["target"]
                                      for mount in service["volumes"]})
            self.assertTrue(all(mount["type"] == "volume" for mount in service["volumes"]))
            self.assertEqual("30s", service["stop_grace_period"])
        image = self.config["services"]["postgres"]["image"]
        self.assertEqual("postgres:18.6-trixie@sha256:"
                         "edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6", image)
        build = self.config["services"]["codex"]["build"]
        self.assertEqual((ROOT / "docker/codex-runtime").as_posix(),
                         Path(build["context"]).as_posix())
        ipc = self.config["volumes"]["runtime_ipc"]["driver_opts"]
        self.assertEqual({"type": "tmpfs", "device": "tmpfs",
                          "o": "uid=10001,gid=10001,mode=0700"}, ipc)


if __name__ == "__main__":
    unittest.main()
