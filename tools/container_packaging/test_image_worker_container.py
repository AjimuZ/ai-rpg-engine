"""Prove the running image worker's isolation and its real libraries on a host that holds its image.

`test_image_worker.py` holds the rules and `test_compose.py` the rendered boundaries. These cases
start the service as Compose renders it, with an empty temporary model directory, in a uniquely
named project that is always removed. They also run the image's real tokenizers and text encoder
classes on the CPU, with no weights. They say nothing about a real checkpoint, the GPU under load,
memory or speed, because no case loads or downloads a model.

The image is about 3 GB of PyTorch and CUDA libraries, and no test builds it or pulls its parts:
`docker compose --profile images build image-worker` does that. `PACKAGING_DOCKER=1` does not
demand it, because `verify.sh containers` runs on hosts that were never asked to build an optional
image; `PACKAGING_IMAGE_WORKER=1` does, and then an absent image fails. Without that flag an absent
image skips with a reason saying that nothing was proven. Any other Docker failure fails.
"""

import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import textwrap
import unittest

from docker_requirement import requires_docker_cli
from test_model_server import GPU_OVERRIDE, UNROUTED_ADDRESS, default_routes


ROOT = Path(__file__).resolve().parents[2]
SERVICE = "image-worker"
USER = "10003:10003"
DEMAND_FLAG = "PACKAGING_IMAGE_WORKER"

# Runs inside the image with no network, as the service account, and prints one JSON document.
REAL_LIBRARIES = textwrap.dedent('''\
    import importlib.util, json, pathlib, sys, tempfile
    specification = importlib.util.spec_from_file_location("image_worker", "/opt/image-worker/image_worker.py")
    worker = importlib.util.module_from_spec(specification)
    sys.modules["image_worker"] = worker
    specification.loader.exec_module(worker)
    import diffusers, safetensors.torch, torch, transformers
    from transformers import CLIPTextConfig, CLIPTextModel, CLIPTextModelWithProjection, CLIPTokenizer
    first = CLIPTokenizer.from_pretrained(worker.CONFIG_DIRECTORY, subfolder="tokenizer", local_files_only=True)
    second = CLIPTokenizer.from_pretrained(worker.CONFIG_DIRECTORY, subfolder="tokenizer_2", local_files_only=True)
    tags = ", ".join(f"tag number {index}" for index in range(60))
    rows = worker.token_rows(first, second, tags, "bad quality")
    whole = list(first(tags, add_special_tokens=False, truncation=False).input_ids)
    content = [token for row in rows.positive_first for token in row[1:] if token != first.eos_token_id]
    config = CLIPTextConfig(vocab_size=49408, hidden_size=8, intermediate_size=16, num_hidden_layers=2,
                            num_attention_heads=2, max_position_embeddings=77, projection_dim=6)
    with torch.no_grad():
        embeddings, pooled = worker.embed_rows(torch, CLIPTextModel(config), CLIPTextModelWithProjection(config),
                                               rows.positive_first, rows.positive_second, "cpu")
    with tempfile.TemporaryDirectory() as directory:
        fake = pathlib.Path(directory) / "fake.safetensors"
        safetensors.torch.save_file({"a": torch.zeros(1)}, str(fake))
        try:
            worker.SdxlBackend(device="cpu").load(fake, None)
            load_code = "loaded"
        except worker.WorkerError as refusal:
            load_code = refusal.code
    print(json.dumps({
        "torch": torch.__version__, "diffusers": diffusers.__version__, "transformers": transformers.__version__,
        "chunks": len(rows.positive_first),
        "row_lengths": sorted({len(row) for side in (rows.positive_first, rows.positive_second,
                                                     rows.negative_first, rows.negative_second) for row in side}),
        "kept_every_token": content == whole,
        "first_row_ends_after_comma": first.convert_ids_to_tokens(
            [token for token in rows.positive_first[0][1:] if token != first.eos_token_id][-1]).startswith(","),
        "first_padding": rows.negative_first[0][-1], "second_padding": rows.negative_second[0][-1],
        "start": rows.positive_first[0][0],
        "embeddings": list(embeddings.shape), "pooled": list(pooled.shape), "load_code": load_code,
    }))
''')


@requires_docker_cli
class ImageWorkerContainerTest(unittest.TestCase):
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

    def in_service(self, *command: str) -> subprocess.CompletedProcess:
        """Run one command in the running service, as its own configured user."""
        return self.run_docker(*self.compose, "exec", "-T", SERVICE, *command, timeout=120)

    def call_worker(self, method: str, path: str) -> tuple[int, dict]:
        """Call the worker's own HTTP surface from inside its container."""
        script = ("import http.client, json, sys; c = http.client.HTTPConnection('127.0.0.1', 8080, timeout=60); "
                  "c.request(sys.argv[1], sys.argv[2], b'{}'); r = c.getresponse(); "
                  "print(json.dumps([r.status, json.loads(r.read())]))")
        result = self.in_service("python", "-I", "-B", "-c", script, method, path)
        self.assertEqual(0, result.returncode, result.stderr[-2000:])
        status, document = json.loads(result.stdout)
        return status, document

    def require_image(self) -> None:
        """Return when the image is here; otherwise skip or fail as the module header says."""
        inspected = self.run_docker("image", "inspect", self.image, timeout=30)
        if inspected.returncode == 0:
            return
        if self.environment.get(DEMAND_FLAG) != "1" and "no such image" in inspected.stderr.lower():
            self.skipTest(f"{self.image} is not on this host, so nothing was proven about the running worker or its real "
                          f"libraries; docker compose --profile images build {SERVICE} builds it "
                          f"(set {DEMAND_FLAG}=1 to fail instead of skip)")
        self.fail(f"{self.image} is not usable here. " + inspected.stderr[-2000:])

    def setUp(self) -> None:
        """Start only the worker in a disposable project, or skip or fail with the reason."""
        models = tempfile.TemporaryDirectory()
        self.addCleanup(models.cleanup)
        self.models = Path(models.name)
        # The user inside the container must be able to list the directory on any host.
        self.models.chmod(0o755)
        self.environment = {name: value for name, value in os.environ.items()
                            if name not in {"COMPOSE_PROFILES", "IMAGE_CONTENT_POLICY"}}
        self.environment["MODELS_DIR"] = str(self.models)
        self.project = "engine-image-worker-check-" + secrets.token_hex(4)
        self.compose = ["compose", "--project-name", self.project, "--env-file", str(ROOT / ".env.example"),
                        "--profile", "images", "--file", str(ROOT / "compose.yaml")]
        config = json.loads(self.docker(*self.compose, "config", "--format", "json", timeout=30))
        self.image = config["services"][SERVICE]["image"]
        self.require_image()
        self.gpu = shutil.which("nvidia-smi") is not None
        if not self.gpu:
            override = self.models.parent / (self.models.name + "-override.yaml")
            override.write_text(GPU_OVERRIDE.replace("models:", SERVICE + ":"), encoding="utf-8", newline="\n")
            self.addCleanup(override.unlink)
            self.compose += ["--file", str(override)]
        self.addCleanup(self.run_docker, *self.compose, "down", "--timeout", "10")
        self.docker(*self.compose, "up", "--detach", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "120",
                    SERVICE, timeout=240)

    def test_running_worker_is_confined(self) -> None:
        """Require the rendered boundaries on the real container and from inside it."""
        identifier = self.docker(*self.compose, "ps", "--quiet", SERVICE).strip()
        state = json.loads(self.docker("inspect", identifier))[0]
        network = self.project + "_images"
        self.assertEqual({network}, set(state["NetworkSettings"]["Networks"]))
        self.assertIs(True, json.loads(self.docker("network", "inspect", network))[0]["Internal"])
        self.assertFalse(state["HostConfig"]["PortBindings"])
        self.assertEqual(USER, state["Config"]["User"])
        self.assertTrue(state["HostConfig"]["ReadonlyRootfs"])
        self.assertEqual(["ALL"], state["HostConfig"]["CapDrop"])
        self.assertIn("no-new-privileges:true", state["HostConfig"]["SecurityOpt"])
        mounts = {mount["Destination"]: mount for mount in state["Mounts"]}
        self.assertEqual({"/models"}, set(mounts))
        self.assertIs(False, mounts["/models"]["RW"])

        # No default route, and an outbound attempt fails at once instead of timing out.
        ipv4 = self.in_service("cat", "/proc/net/route")
        self.assertEqual(0, ipv4.returncode, ipv4.stderr)
        ipv6 = self.in_service("sh", "-c", "cat /proc/net/ipv6_route 2>/dev/null || true")
        self.assertEqual(0, ipv6.returncode, ipv6.stderr)
        self.assertEqual([], default_routes(ipv4.stdout, ipv6.stdout))
        attempt = self.in_service("python", "-I", "-B", "-c",
                                  f"import socket; socket.create_connection(('{UNROUTED_ADDRESS}', 80), timeout=5)")
        self.assertNotEqual(0, attempt.returncode)
        self.assertNotIn("timed out", attempt.stderr)

        # Positive control: the same probe sees the route an ordinary bridge network gives.
        control = self.docker("run", "--rm", "--pull", "never", "--network", "bridge", "--read-only",
                              "--cap-drop", "ALL", "--user", USER, "--entrypoint", "cat",
                              self.image, "/proc/net/route", timeout=60)
        self.assertNotEqual([], default_routes(control, ""))

        # Weights and the root filesystem refuse writes; only the private tmpfs accepts them.
        for path in ("/models/probe", "/probe", "/opt/image-worker/image_worker.py"):
            refused = self.in_service("touch", path)
            self.assertNotEqual(0, refused.returncode, path)
        self.assertEqual([], list(self.models.iterdir()))
        self.assertEqual(0, self.in_service("touch", "/tmp/probe").returncode)
        self.assertEqual("10003", self.in_service("id", "-u").stdout.strip())

    def test_running_worker_answers_without_a_model(self) -> None:
        """An empty directory is a clean refusal; the safe policy is the default of the render."""
        self.assertEqual((200, {"status": "serving"}), self.call_worker("GET", "/healthz"))
        status, document = self.call_worker("GET", "/v1/models")
        self.assertEqual(200, status)
        self.assertEqual("wai-illustrious-sdxl-v17", document["recommended"])
        self.assertEqual("safe", document["contentPolicy"])
        self.assertEqual((404, {"error": "checkpoint-missing"}), self.call_worker("POST", "/v1/load"))

    def test_real_libraries_chunk_and_encode_offline(self) -> None:
        """The pinned versions, the baked tokenizers and real encoder classes obey the worker's rules."""
        result = self.docker("run", "--rm", "--pull", "never", "--network", "none", "--read-only", "--cap-drop", "ALL",
                             "--user", USER, "--tmpfs", "/tmp:uid=10003,gid=10003,mode=0700", "--entrypoint", "python",
                             self.image, "-I", "-B", "-c", REAL_LIBRARIES, timeout=300)
        report = json.loads(result.splitlines()[-1])
        self.assertEqual("2.15.0+cu132", report["torch"])
        self.assertEqual("0.42.1", report["diffusers"])
        self.assertEqual("5.22.1", report["transformers"])
        self.assertGreater(report["chunks"], 1)
        self.assertEqual([77], report["row_lengths"])
        self.assertTrue(report["kept_every_token"])
        self.assertTrue(report["first_row_ends_after_comma"])
        # CLIP's start and end tokens; the second SDXL tokenizer pads with `!`, which is id 0.
        self.assertEqual(49406, report["start"])
        self.assertEqual(49407, report["first_padding"])
        self.assertEqual(0, report["second_padding"])
        self.assertEqual([1, 77 * report["chunks"], 16], report["embeddings"])
        self.assertEqual([1, 6], report["pooled"])
        # A file that is no checkpoint fails fast with no network, instead of a lookup online.
        self.assertEqual("checkpoint-load-failed", report["load_code"])

    def test_gpu_reaches_the_service_on_a_gpu_host(self) -> None:
        """On a GPU host, the rendered GPU request must expose a CUDA device to the pinned torch."""
        if not self.gpu:
            self.skipTest("nvidia-smi not found: GPU passthrough is not proven on this host")
        probe = self.in_service("python", "-I", "-B", "-c", "import torch; print(torch.cuda.is_available())")
        self.assertEqual(0, probe.returncode, probe.stderr[-2000:])
        self.assertEqual("True", probe.stdout.strip().splitlines()[-1])


if __name__ == "__main__":
    unittest.main()
