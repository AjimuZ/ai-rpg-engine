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
                       if name not in {"COMPOSE_PROFILES", "MODELS_DIR", "GATEWAY_KEYS_DIR", "GATEWAY_DESTINATIONS_FILE",
                                       "IMAGE_CONTENT_POLICY"}}
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
        # The engine alone may reach the optional model server, image worker and gateway, over internal networks.
        self.assertEqual({"web", "data", "models", "gateway", "images"}, set(services["engine"]["networks"]))
        self.assertTrue(self.config["networks"]["models"]["internal"])
        self.assertTrue(self.config["networks"]["gateway"]["internal"])
        self.assertTrue(self.config["networks"]["images"]["internal"])
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

    def model_bind_creates_host_path(self, weights: dict, service: str = "models") -> bool:
        """Return whether a bind of `service` may create its host path; fail if it cannot be told.

        Compose 5.x prints `bind.create_host_path` even when false, while 2.x omits it.
        A rendered value is used as printed. When it is omitted, `compose.yaml` itself
        must set `create_host_path: false` explicitly on the volume with that target in
        that service, so a missing key never passes for a safe setting.
        """
        rendered = weights.get("bind", {}).get("create_host_path")
        if rendered is not None:
            return rendered
        text = (ROOT / "compose.yaml").read_text(encoding="utf-8")
        block = re.search(rf"^  {re.escape(service)}:\n((?:    .*\n|\n)*)", text, re.M)
        self.assertIsNotNone(block, f"compose.yaml has no {service} service")
        setting = re.findall(rf"^\s+target: {re.escape(weights['target'])}\n(?:\s+read_only: \w+\n)?\s+bind:\s*\{{\s*create_host_path:\s*(\w+)\s*\}}\s*$",
                             block.group(1), re.M)
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

    def test_image_worker_runs_only_under_its_profile(self) -> None:
        """Selecting the profile adds one service and changes no other service."""
        selected = self.render("images")
        self.assertNotIn("image-worker", self.config["services"])
        self.assertEqual(set(self.config["services"]) | {"image-worker"}, set(selected["services"]))
        self.assertEqual(["images"], selected["services"]["image-worker"]["profiles"])
        self.assertEqual(self.config["services"], {name: service for name, service
                                                   in selected["services"].items() if name != "image-worker"})
        # A default start must not wait for, build or implicitly start the optional worker.
        self.assertNotIn("image-worker", self.config["services"]["engine"]["depends_on"])

    def test_image_worker_boundaries(self) -> None:
        """Require a local build, a read-only model bind, the safe policy and no way off its network."""
        selected = self.render("images", "models", "gateway", "model-fetch")
        worker = selected["services"]["image-worker"]
        self.assertEqual((ROOT / "docker/image-worker").as_posix(), Path(worker["build"]["context"]).as_posix())
        self.assertFalse(worker["build"].get("args"))
        self.assertEqual("rpg-engine/image-worker:0.1.0", worker["image"])
        self.assertFalse(worker.get("ports"))
        self.assertNotIn("network_mode", worker)
        self.assertEqual({"images"}, set(worker["networks"]))
        self.assertTrue(selected["networks"]["images"]["internal"])
        # The engine is the only peer: no other service joins, whatever is selected.
        for name, service in selected["services"].items():
            if name not in {"image-worker", "engine"}:
                self.assertNotIn("images", set(service.get("networks") or {}), name)
        self.assertEqual(1, len(worker["volumes"]))
        weights = worker["volumes"][0]
        self.assertEqual(("bind", "/models", True), (weights["type"], weights["target"], weights["read_only"]))
        self.assertEqual(selected["services"]["models"]["volumes"][0]["source"], weights["source"])
        self.assertIs(False, self.model_bind_creates_host_path(weights, "image-worker"))
        self.assertFalse(worker.get("secrets"))
        self.assertFalse(worker.get("env_file"))
        self.assertEqual({"IMAGE_CONTENT_POLICY": "safe"}, worker["environment"])
        self.assertEqual("10003:10003", worker["user"])
        self.assertIs(True, worker["init"])
        self.assertTrue(worker["read_only"])
        self.assertEqual(["ALL"], worker["cap_drop"])
        self.assertIn("no-new-privileges:true", worker["security_opt"])
        self.assertTrue(worker["gpus"])
        self.assertEqual("30s", worker["stop_grace_period"])

    def test_model_fetch_runs_only_under_its_profile(self) -> None:
        """Selecting the profile adds its two services and changes no other service."""
        selected = self.render("model-fetch")
        helpers = {"models-license", "models-fetch"}
        self.assertFalse(helpers & set(self.config["services"]))
        self.assertEqual(set(self.config["services"]) | helpers, set(selected["services"]))
        self.assertEqual(self.config["services"], {name: service for name, service
                                                   in selected["services"].items() if name not in helpers})
        self.assertEqual(self.render("models")["services"]["models"]["volumes"][0]["source"],
                         self.fetch_mounts(selected)["/models"]["source"])

    def fetch_mounts(self, rendered: dict) -> dict:
        """Return the fetch service's mounts by target."""
        return {mount["target"]: mount for mount in rendered["services"]["models-fetch"]["volumes"]}

    def test_model_fetch_boundaries(self) -> None:
        """Only the fetch service has egress and write access, to the model directory alone."""
        selected = self.render("model-fetch")
        license_service = selected["services"]["models-license"]
        fetch = selected["services"]["models-fetch"]
        self.assertEqual("none", license_service["network_mode"])
        self.assertEqual("bridge", fetch["network_mode"])
        self.assertRegex(fetch["image"], r"^python:3\.15\.1-slim-trixie@sha256:[0-9a-f]{64}$")
        adr = (ROOT / "docs" / "adr" / "0009-toolchain-baseline.md").read_text(encoding="utf-8")
        self.assertIn(fetch["image"], adr)
        for service in (license_service, fetch):
            self.assertEqual(fetch["image"], service["image"])
            self.assertNotIn("build", service)
            self.assertNotIn("networks", service)
            self.assertFalse(service.get("ports"))
            self.assertFalse(service.get("secrets"))
            self.assertFalse(service.get("environment"))
            self.assertFalse(service.get("depends_on"))
            self.assertEqual("65534:65534", service["user"])
            self.assertTrue(service["read_only"])
            self.assertEqual(["ALL"], service["cap_drop"])
            self.assertIn("no-new-privileges:true", service["security_opt"])
            self.assertEqual(["python", "-I", "-B", "/opt/model-fetch/model_fetch.py"], service["entrypoint"])
        license_mounts = {mount["target"]: mount for mount in license_service["volumes"]}
        fetch_mounts = self.fetch_mounts(selected)
        self.assertEqual({"/opt/model-fetch", "/catalog/model-catalog.json"}, set(license_mounts))
        self.assertEqual({"/opt/model-fetch", "/catalog/model-catalog.json", "/models"}, set(fetch_mounts))
        for mounts in (license_mounts, fetch_mounts):
            for target in ("/opt/model-fetch", "/catalog/model-catalog.json"):
                self.assertEqual("bind", mounts[target]["type"])
                self.assertIs(True, mounts[target]["read_only"])
            # The real catalog is the only input: no variable can replace its path.
            self.assertTrue(Path(mounts["/catalog/model-catalog.json"]["source"]).as_posix().endswith(
                "/backend/src/main/resources/inference/model-catalog.json"))
            self.assertTrue(Path(mounts["/opt/model-fetch"]["source"]).as_posix().endswith("/docker/model-fetch"))
        self.assertFalse(fetch_mounts["/models"].get("read_only", False))
        self.assertIs(False, self.model_bind_creates_host_path(fetch_mounts["/models"], "models-fetch"))

    def test_gateway_runs_only_under_its_profile(self) -> None:
        """Selecting the profile adds one service and changes no other service."""
        selected = self.render("gateway")
        self.assertNotIn("gateway", self.config["services"])
        self.assertEqual(set(self.config["services"]) | {"gateway"}, set(selected["services"]))
        self.assertEqual(["gateway"], selected["services"]["gateway"]["profiles"])
        self.assertEqual(self.config["services"], {name: service for name, service
                                                   in selected["services"].items() if name != "gateway"})
        # A default start must not wait for, or implicitly start, the optional gateway.
        self.assertNotIn("gateway", self.config["services"]["engine"]["depends_on"])
        # Nothing but the profile creates the route out or the budget volume.
        self.assertNotIn("gateway_egress", self.config["networks"])
        self.assertNotIn("gateway_state", self.config["volumes"])

    def test_gateway_networks_are_shared_only_as_declared(self) -> None:
        """Only the gateway has the egress network, and only the engine shares its private one."""
        selected = self.render("gateway", "models", "model-fetch")
        services = selected["services"]
        gateway = services["gateway"]
        self.assertEqual({"gateway", "gateway_egress"}, set(gateway["networks"]))
        self.assertNotIn("network_mode", gateway)
        self.assertFalse(gateway.get("ports"))
        self.assertTrue(selected["networks"]["gateway"]["internal"])
        self.assertFalse(selected["networks"]["gateway_egress"].get("internal", False))
        for name, service in services.items():
            joined = set(service.get("networks") or {})
            if name != "gateway":
                self.assertNotIn("gateway_egress", joined, name)
            if name not in {"gateway", "engine"}:
                self.assertNotIn("gateway", joined, name)
        # The route to the keys never crosses the database, runtime, browser or model networks.
        self.assertFalse({"data", "inference", "web", "models"} & set(gateway["networks"]))

    def test_gateway_keys_are_runtime_only(self) -> None:
        """Keys and the allowlist are read-only host paths; no other route can bring in a secret."""
        gateway = self.render("gateway")["services"]["gateway"]
        mounts = {mount["target"]: mount for mount in gateway["volumes"]}
        self.assertEqual({"/etc/gateway/destinations.json", "/run/secrets/gateway", "/var/lib/gateway"}, set(mounts))
        # The one writable path is a named volume of counts, so no key file can be replaced from here.
        state = mounts.pop("/var/lib/gateway")
        self.assertEqual(("volume", "gateway_state"), (state["type"], state["source"]))
        self.assertFalse(state.get("read_only", False))
        for mount in mounts.values():
            self.assertEqual("bind", mount["type"])
            self.assertIs(True, mount["read_only"])
            self.assertIs(False, self.model_bind_creates_host_path(mount, "gateway"))
        self.assertTrue(Path(mounts["/run/secrets/gateway"]["source"]).as_posix().endswith("/.local/secrets/gateway"))
        self.assertTrue(Path(mounts["/etc/gateway/destinations.json"]["source"]).as_posix().endswith(
            "/.local/gateway/destinations.json"))
        self.assertFalse(gateway.get("environment"), "a key must never travel in a variable")
        self.assertFalse(gateway.get("env_file"))
        self.assertFalse(gateway.get("secrets"), "the database password is not this service's")
        self.assertFalse(gateway["build"].get("args"), "a key must never travel in a build argument")
        self.assertFalse(gateway["build"].get("secrets"))
        self.assertFalse(gateway.get("command"), "a key must never travel in an argument")
        # The engine and every other service stay away from these paths.
        for name, service in self.render("gateway")["services"].items():
            if name != "gateway":
                sources = {Path(str(mount.get("source"))).as_posix() for mount in service.get("volumes", [])}
                self.assertFalse(any("/.local/secrets/gateway" in source or "/.local/gateway" in source for source in sources), name)
                self.assertNotIn("gateway_state", sources, name)

    def test_gateway_is_confined_and_pinned(self) -> None:
        """Require the container profile and a build on the digest that ADR 0009 records."""
        gateway = self.render("gateway")["services"]["gateway"]
        self.assertEqual("10002:10002", gateway["user"])
        self.assertTrue(gateway["read_only"])
        self.assertEqual(["ALL"], gateway["cap_drop"])
        self.assertIn("no-new-privileges:true", gateway["security_opt"])
        self.assertEqual("15s", gateway["stop_grace_period"])
        self.assertEqual((ROOT / "docker/gateway").as_posix(), Path(gateway["build"]["context"]).as_posix())
        dockerfile = (ROOT / "docker/gateway/Dockerfile").read_text(encoding="utf-8")
        base = re.findall(r"^FROM (\S+)$", dockerfile, re.M)
        self.assertEqual(1, len(base))
        self.assertRegex(base[0], r"^python:3\.15\.1-slim-trixie@sha256:[0-9a-f]{64}$")
        self.assertEqual(self.render("model-fetch")["services"]["models-fetch"]["image"], base[0])
        adr = (ROOT / "docs" / "adr" / "0009-toolchain-baseline.md").read_text(encoding="utf-8")
        self.assertIn(base[0], adr)
        self.assertIn("USER 10002:10002", dockerfile)
        self.assertNotRegex(dockerfile, r"(?m)^(ARG|ENV)\b", "a key must have no way into the build")


if __name__ == "__main__":
    unittest.main()
