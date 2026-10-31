"""Check rendered infrastructure boundaries without reading private configuration.

Inputs are the tracked Compose and example environment files. Outputs are test
assertions; missing Compose, invalid configuration, or unsafe mounts fail tests.
A host with no `docker` executable at all skips them unless Docker is required
(`docker_requirement.py`; hosted CI requires it, so the absence fails there).
No services are started and no existing account or secret contents are read.
"""

import json
import os
from pathlib import Path
import re
import subprocess
import unittest

from docker_requirement import requires_docker_cli


ROOT = Path(__file__).resolve().parents[2]


@requires_docker_cli
class ComposeTest(unittest.TestCase):
    """Validate Compose's resolved configuration; unsafe service boundaries fail."""

    def render(self, *profiles: str) -> dict:
        """Return the resolved configuration for exactly these profiles; fail if invalid.

        Host selections are cleared so that only tracked inputs decide the result.
        """
        selection = [argument for profile in profiles for argument in ("--profile", profile)]
        environment = {name: value for name, value in os.environ.items()
                       if name not in {"COMPOSE_PROFILES", "MODELS_DIR"}}
        result = subprocess.run(
            ["docker", "compose", "--env-file", str(ROOT / ".env.example"),
             "--file", str(ROOT / "compose.yaml"), *selection, "config", "--format", "json"],
            capture_output=True, text=True, timeout=30, check=False, env=environment,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def setUp(self) -> None:
        """Render tracked inputs only and store JSON; fail on missing/invalid files."""
        self.config = self.render()

    def test_network_and_secret_boundaries(self) -> None:
        """Require isolated networks, loopback HTTP and a database/engine-only secret."""
        services = self.config["services"]
        self.assertEqual({"engine", "postgres", "codex"}, set(services))
        self.assertEqual({"data"}, set(services["postgres"]["networks"]))
        self.assertEqual({"inference"}, set(services["codex"]["networks"]))
        self.assertTrue(self.config["networks"]["data"]["internal"])
        self.assertFalse(self.config["networks"]["inference"].get("internal", False))
        # The engine alone may reach the optional model server, over an internal network.
        self.assertEqual({"web", "data", "models"}, set(services["engine"]["networks"]))
        self.assertTrue(self.config["networks"]["models"]["internal"])
        for name, service in services.items():
            if name != "engine":
                self.assertFalse(service.get("ports"))
            self.assertNotIn("network_mode", service)
        engine = services["engine"]
        self.assertEqual(1, len(engine["ports"]))
        self.assertEqual("127.0.0.1", engine["ports"][0]["host_ip"])
        self.assertEqual(8080, engine["ports"][0]["target"])
        self.assertEqual("packaged", engine["environment"]["SPRING_PROFILES_ACTIVE"])
        self.assertNotIn("SPRING_DATASOURCE_PASSWORD", engine["environment"])
        self.assertEqual("10001:10001", engine["user"])
        self.assertTrue(engine["read_only"])
        self.assertEqual(["ALL"], engine["cap_drop"])
        self.assertIn("no-new-privileges:true", engine["security_opt"])
        self.assertEqual({"postgres", "codex"}, set(engine["depends_on"]))
        self.assertTrue(all(value["condition"] == "service_healthy"
                            for value in engine["depends_on"].values()))
        database = services["postgres"]
        self.assertEqual("/run/secrets/postgres_password",
                         database["environment"]["POSTGRES_PASSWORD_FILE"])
        self.assertNotIn("POSTGRES_PASSWORD", database["environment"])
        self.assertEqual([{"source": "postgres_password", "target": "/run/secrets/postgres_password"}],
                         database["secrets"])
        self.assertEqual(database["secrets"], engine["secrets"])
        runtime = services["codex"]
        self.assertFalse(runtime.get("secrets"))
        self.assertFalse(runtime.get("environment"))
        self.assertEqual("10001:10001", runtime["user"])
        # Without an init, SIGTERM to the PID 1 runtime is ignored (P01.4).
        self.assertIs(True, runtime["init"])
        self.assertTrue(runtime["read_only"])
        self.assertEqual(["ALL"], runtime["cap_drop"])
        self.assertIn("no-new-privileges:true", runtime["security_opt"])

    def test_storage_and_image_boundaries(self) -> None:
        """Reject host binds and shared account/data mounts; require pinned inputs."""
        expected = {
            "engine": {"content": "/var/lib/engine/content",
                       "assets": "/var/lib/engine/assets",
                       "runtime_ipc": "/tmp/codex-daemon-10001"},
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
        engine = self.config["services"]["engine"]
        self.assertEqual("docker/engine/Dockerfile", engine["build"]["dockerfile"])
        ipc_mount = next(mount for mount in engine["volumes"] if mount["source"] == "runtime_ipc")
        self.assertTrue(ipc_mount["read_only"])
        self.assertEqual("postgres:18.6-trixie@sha256:"
                         "edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6", image)
        build = self.config["services"]["codex"]["build"]
        self.assertEqual((ROOT / "docker/codex-runtime").as_posix(),
                         Path(build["context"]).as_posix())
        ipc = self.config["volumes"]["runtime_ipc"]["driver_opts"]
        self.assertEqual({"type": "tmpfs", "device": "tmpfs",
                          "o": "uid=10001,gid=10001,mode=0700"}, ipc)

    def test_model_server_runs_only_under_its_profile(self) -> None:
        """Selecting the profile adds one service and changes no other service."""
        selected = self.render("models")
        self.assertNotIn("models", self.config["services"])
        self.assertEqual(set(self.config["services"]) | {"models"}, set(selected["services"]))
        self.assertEqual(["models"], selected["services"]["models"]["profiles"])
        self.assertEqual(self.config["services"], {name: service for name, service
                                                   in selected["services"].items() if name != "models"})
        # A default start must not wait for, or implicitly start, the optional server.
        self.assertNotIn("models", self.config["services"]["engine"]["depends_on"])

    def model_bind_creates_host_path(self, weights: dict) -> bool:
        """Return whether the model bind may create its host path; fail if it cannot be told.

        Compose 5.x prints `bind.create_host_path` even when false, while 2.x omits it.
        A rendered value is used as printed. When it is omitted, `compose.yaml` itself
        must set `create_host_path: false` explicitly on the `models` service, so a
        missing key never passes for a safe setting.
        """
        rendered = weights.get("bind", {}).get("create_host_path")
        if rendered is not None:
            return rendered
        text = (ROOT / "compose.yaml").read_text(encoding="utf-8")
        service = re.search(r"^  models:\n((?:    .*\n|\n)*)", text, re.M)
        self.assertIsNotNone(service, "compose.yaml has no models service")
        setting = re.findall(r"^\s+bind:\s*\{\s*create_host_path:\s*(\w+)\s*\}\s*$",
                             service.group(1), re.M)
        self.assertEqual(["false"], setting,
                         "the rendered bind omits create_host_path and compose.yaml does not set it false")
        return False

    def test_model_server_boundaries(self) -> None:
        """Require the launcher's pin, a read-only model bind and no way off its network."""
        selected = self.render("models")
        server = selected["services"]["models"]
        launcher = (ROOT / "start.sh").read_text(encoding="utf-8")
        pin = re.search(r'^readonly MODEL_SERVER_IMAGE="([^"]+)"$', launcher, re.M)
        self.assertIsNotNone(pin)
        self.assertEqual(pin.group(1), server["image"])
        self.assertRegex(server["image"], r":server-cuda-b\d+@sha256:[0-9a-f]{64}$")
        self.assertNotIn("build", server)
        # No published port, host network, or second network reaching PostgreSQL or the runtime.
        self.assertFalse(server.get("ports"))
        self.assertNotIn("network_mode", server)
        self.assertEqual({"models"}, set(server["networks"]))
        self.assertTrue(selected["networks"]["models"]["internal"])
        for name in ("postgres", "codex"):
            self.assertNotIn("models", selected["services"][name]["networks"])
        # The bind is the only mount: no account, database, socket or Docker control path.
        self.assertEqual(1, len(server["volumes"]))
        weights = server["volumes"][0]
        self.assertEqual(("bind", "/models", True),
                         (weights["type"], weights["target"], weights["read_only"]))
        self.assertTrue(Path(weights["source"]).as_posix().endswith("/.local/models"), weights["source"])
        self.assertIs(False, self.model_bind_creates_host_path(weights))
        self.assertFalse(server.get("secrets"))
        self.assertEqual({"LLAMA_CACHE": "/tmp/llama-cache"}, server["environment"])
        self.assertEqual("65534:65534", server["user"])
        self.assertIs(True, server["init"])
        self.assertTrue(server["read_only"])
        self.assertEqual(["ALL"], server["cap_drop"])
        self.assertIn("no-new-privileges:true", server["security_opt"])
        self.assertTrue(server["gpus"])
        self.assertEqual("30s", server["stop_grace_period"])
        command = server["command"]
        self.assertEqual("/models", command[command.index("--models-dir") + 1])
        self.assertEqual("1", command[command.index("--models-max") + 1])
        # An explicit model argument would disable router mode, and a fetch path would need egress.
        for forbidden in ("--model", "-m", "--hf-repo", "-hf", "--model-url", "-mu"):
            self.assertNotIn(forbidden, command)


if __name__ == "__main__":
    unittest.main()
