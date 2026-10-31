"""Prove the running model server's isolation on any host that holds its pinned image.

The rendered configuration is checked by `test_compose.py`; this case starts the real
pinned image under the `models` profile, with an empty temporary model directory, in a
uniquely named project that it always removes. It proves router mode, the absence of a
route off the network and the refusal of writes. It loads no model, so it says nothing
about inference, memory or speed. A host without `nvidia-smi` drops only the GPU
request through an override, and then proves nothing about GPU passthrough.

The image is never pulled here, because it is about 2.6 GB compressed. A host without
it follows `docker_requirement.missing_image_reason`: the case skips and says what stayed
unproven, or fails with the fetch command when `PACKAGING_DOCKER=1` demanded it, as
`verify.sh containers` does. Any other Docker failure or isolation breach fails. The
route parser needs no Docker, so its cases always run.
"""

import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import unittest

from docker_requirement import missing_image_reason, requires_docker_cli


ROOT = Path(__file__).resolve().parents[2]
# Reserved for documentation (RFC 5737): never a real peer, whatever the host's network.
UNROUTED_ADDRESS = "192.0.2.1"
GPU_OVERRIDE = "services:\n  models:\n    gpus: !reset []\n"


def default_routes(ipv4_table: str, ipv6_table: str) -> list[str]:
    """Return every default-route line of `/proc/net/route` and `/proc/net/ipv6_route`.

    The IPv4 destination is the second column; an IPv6 default has an all-zero destination
    of prefix length 0, and the loopback device's reject entry does not count.
    """
    routes = [line for line in ipv4_table.splitlines()[1:]
              if len(line.split()) > 1 and line.split()[1] == "00000000"]
    for line in ipv6_table.splitlines():
        fields = line.split()
        if len(fields) == 10 and fields[0] == "0" * 32 and fields[1] == "00" and fields[9] != "lo":
            routes.append(line)
    return routes


class DefaultRoutesTest(unittest.TestCase):
    """The no-route verdict depends on this parser, so it must see real table shapes."""

    IPV4_HEADER = "Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT\n"
    ANY_IPV6 = "0" * 32

    def ipv6_line(self, destination: str, prefix: str, device: str) -> str:
        """Return one `/proc/net/ipv6_route` line with its ten columns."""
        return f"{destination} {prefix} {self.ANY_IPV6} 00 {self.ANY_IPV6} 00000400 00000001 00000000 00000003 {device}"

    def test_internal_tables_have_no_default_route(self) -> None:
        """A subnet route and the loopback reject entry are not a way off the network."""
        ipv4 = self.IPV4_HEADER + "eth0\t0000120A\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0\n"
        ipv6 = "\n".join((self.ipv6_line("fd00" + "0" * 28, "40", "eth0"), self.ipv6_line(self.ANY_IPV6, "00", "lo")))
        self.assertEqual([], default_routes(ipv4, ipv6))
        self.assertEqual([], default_routes(self.IPV4_HEADER, ""))

    def test_either_family_reports_a_default_route(self) -> None:
        """An IPv4 or an IPv6 default alone is enough to fail the isolation check."""
        ipv4 = self.IPV4_HEADER + "eth0\t00000000\t0100120A\t0003\t0\t0\t0\t00000000\t0\t0\t0\n"
        self.assertEqual(1, len(default_routes(ipv4, "")))
        ipv6 = self.ipv6_line(self.ANY_IPV6, "00", "eth0")
        self.assertEqual([ipv6], default_routes(self.IPV4_HEADER, ipv6))


@requires_docker_cli
class ModelServerIsolationTest(unittest.TestCase):
    """Run the selected service as Compose renders it and probe it from inside."""

    def run_docker(self, *arguments: str, timeout: int = 180) -> subprocess.CompletedProcess:
        """Run Docker without a shell and return the outcome, whatever its exit code."""
        return subprocess.run(["docker", *arguments], capture_output=True, text=True, encoding="utf-8",
                              errors="replace", timeout=timeout, check=False, env=self.environment)

    def docker(self, *arguments: str, timeout: int = 180) -> str:
        """Run Docker and return stdout, or fail with bounded output."""
        result = self.run_docker(*arguments, timeout=timeout)
        self.assertEqual(0, result.returncode, result.stdout[-2000:] + result.stderr[-2000:])
        return result.stdout

    def in_server(self, *command: str) -> subprocess.CompletedProcess:
        """Run one command in the running service, as its own configured user."""
        return self.run_docker(*self.compose, "exec", "-T", "models", *command, timeout=60)

    def require_image(self) -> None:
        """Return when the pinned image is here; otherwise skip or fail by the shared policy."""
        inspected = self.run_docker("image", "inspect", self.image, timeout=30)
        if inspected.returncode == 0:
            return
        reason = missing_image_reason(self.image, inspected.stderr, self.environment)
        if reason is not None:
            self.skipTest(reason)
        self.fail(f"{self.image} is not usable here; ./start.sh --models profile --gpu-check fetches it. "
                  + inspected.stderr[-2000:])

    def setUp(self) -> None:
        """Start only the model server in a disposable project, or skip or fail with the reason."""
        models = tempfile.TemporaryDirectory()
        self.addCleanup(models.cleanup)
        self.models = Path(models.name)
        # The user inside the container must be able to list the directory on any host.
        self.models.chmod(0o755)
        self.environment = {name: value for name, value in os.environ.items() if name != "COMPOSE_PROFILES"}
        self.environment["MODELS_DIR"] = str(self.models)
        self.project = "engine-model-server-check-" + secrets.token_hex(4)
        self.compose = ["compose", "--project-name", self.project, "--env-file", str(ROOT / ".env.example"),
                        "--profile", "models", "--file", str(ROOT / "compose.yaml")]
        config = json.loads(self.docker(*self.compose, "config", "--format", "json", timeout=30))
        self.image = config["services"]["models"]["image"]
        self.require_image()
        self.gpu = shutil.which("nvidia-smi") is not None
        if not self.gpu:
            override = self.models.parent / (self.models.name + "-override.yaml")
            override.write_text(GPU_OVERRIDE, encoding="utf-8", newline="\n")
            self.addCleanup(override.unlink)
            self.compose += ["--file", str(override)]
        self.addCleanup(self.run_docker, *self.compose, "down", "--timeout", "10")
        self.docker(*self.compose, "up", "--detach", "--pull", "never", "--wait", "--wait-timeout", "120",
                    "models", timeout=240)

    def test_running_server_is_isolated_and_in_router_mode(self) -> None:
        """Require the rendered boundaries on the real container and from inside it."""
        identifier = self.docker(*self.compose, "ps", "--quiet", "models").strip()
        state = json.loads(self.docker("inspect", identifier))[0]
        network = self.project + "_models"
        self.assertEqual({network}, set(state["NetworkSettings"]["Networks"]))
        self.assertIs(True, json.loads(self.docker("network", "inspect", network))[0]["Internal"])
        self.assertFalse(state["HostConfig"]["PortBindings"])
        self.assertFalse(any(state["NetworkSettings"]["Ports"].values()))
        self.assertEqual("65534:65534", state["Config"]["User"])
        self.assertTrue(state["HostConfig"]["ReadonlyRootfs"])
        self.assertEqual(["ALL"], state["HostConfig"]["CapDrop"])
        self.assertIn("no-new-privileges:true", state["HostConfig"]["SecurityOpt"])
        mounts = {mount["Destination"]: mount for mount in state["Mounts"]}
        self.assertEqual({"/models"}, set(mounts))
        self.assertIs(False, mounts["/models"]["RW"])

        # The healthy service is the router: it reports that role and the configured limit.
        properties = self.in_server("curl", "--fail", "--silent", "--show-error", "http://127.0.0.1:8080/props")
        self.assertEqual(0, properties.returncode, properties.stderr)
        router = json.loads(properties.stdout)
        self.assertEqual("router", router.get("role"), properties.stdout[:500])
        self.assertEqual(1, router.get("max_instances"), properties.stdout[:500])

        # No default route, and an outbound attempt fails at once (curl 7) instead of timing
        # out (curl 28), which would mean the packet left the container.
        ipv4 = self.in_server("cat", "/proc/net/route")
        self.assertEqual(0, ipv4.returncode, ipv4.stderr)
        ipv6 = self.in_server("sh", "-c", "cat /proc/net/ipv6_route 2>/dev/null || true")
        self.assertEqual(0, ipv6.returncode, ipv6.stderr)
        self.assertEqual([], default_routes(ipv4.stdout, ipv6.stdout))
        attempt = self.in_server("curl", "--silent", "--show-error", "--noproxy", "*", "--max-time", "5",
                                 f"http://{UNROUTED_ADDRESS}/")
        self.assertEqual(7, attempt.returncode, attempt.stdout + attempt.stderr)

        # Positive control: the same probe sees the route an ordinary bridge network gives.
        control = self.docker("run", "--rm", "--pull", "never", "--network", "bridge", "--read-only",
                              "--cap-drop", "ALL", "--user", "65534:65534", "--entrypoint", "cat",
                              self.image, "/proc/net/route", timeout=60)
        self.assertNotEqual([], default_routes(control, ""))

        # Weights and the root filesystem refuse writes; only the private tmpfs accepts them.
        for path in ("/models/probe", "/probe"):
            refused = self.in_server("touch", path)
            self.assertNotEqual(0, refused.returncode, path)
            self.assertIn("read-only", refused.stderr.lower(), path)
        self.assertEqual([], list(self.models.iterdir()))
        self.assertEqual(0, self.in_server("touch", "/tmp/probe").returncode)
        self.assertEqual("65534", self.in_server("id", "-u").stdout.strip())

    def test_gpu_reaches_the_service_on_a_gpu_host(self) -> None:
        """On a GPU host, the rendered GPU request must expose a CUDA device to the server."""
        if not self.gpu:
            self.skipTest("nvidia-smi not found: GPU passthrough is not proven on this host")
        listing = self.run_docker(*self.compose, "run", "--rm", "--no-deps", "--pull", "never", "-T", "models",
                                  "--list-devices", timeout=120)
        self.assertEqual(0, listing.returncode, listing.stderr[-2000:])
        self.assertIn("CUDA", listing.stdout + listing.stderr)


if __name__ == "__main__":
    unittest.main()
