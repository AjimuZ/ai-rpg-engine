"""Check `./start.sh --models profile` and the hardware-profile fixtures (P21.1).

The launcher writes `.local/hardware.json` from host tools: the NVIDIA driver
tool, `/proc`, PowerShell's CIM classes on Windows, `df` and Docker. These tests
source a temporary copy of the launcher and replace each tool with a Bash
function that prints recorded output, so they need no GPU, Docker daemon or
Windows host and never touch the checkout's private state.

The same replay produces the hardware-profile fixtures that the backend advisor
tests read (`backend/src/test/resources/inference/hardware-profiles/`). Each
fixture must be byte-identical to what the launcher writes for its recorded tool
output, so the Java parser and the advisor are tested against the launcher's real
format, not a hand-written imitation. After an intended format change, refresh
them with `HARDWARE_PROFILE_UPDATE=1` and review the diff.

Fixture values: `reference-host` replays the reference-host facts measured on
25 October 2026 (docs/34 section 2) plus the WSL 2 VM memory measured from a
container on that machine on 28 October 2026; free VRAM and free disk are derived
from those facts, not separately measured. `reference-host-gpu-visible` adds a
synthetic container-GPU answer, because passthrough has not yet been observed.
The other profiles (no GPU, 6, 8 and 24 GB) are synthetic. None of them is
evidence about a real machine; the real reference-host run is recorded in the
roadmap when it happens.
"""

import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest

from test_launcher import ROOT, LAUNCHER, run_bash


FIXTURES = ROOT / "backend" / "src" / "test" / "resources" / "inference" / "hardware-profiles"
UPDATE = os.environ.get("HARDWARE_PROFILE_UPDATE") == "1"
IMAGE = ("ghcr.io/ggml-org/llama.cpp:server-cuda-b11960"
         "@sha256:4ccae195bf542f7844301ae06891baa32a7551cb16de8428af6e929403d4292c")


def cpuinfo(model: str, threads: int) -> str:
    """Return a /proc/cpuinfo excerpt with THREADS processor blocks."""
    return "".join(f"processor\t: {i}\nmodel name\t: {model}\n\n" for i in range(threads))


def meminfo(total_kib: int, available_kib: int) -> str:
    """Return a /proc/meminfo excerpt in the kernel's layout."""
    return (f"MemTotal:       {total_kib} kB\nMemFree:         1000 kB\n"
            f"MemAvailable:   {available_kib} kB\n")


def list_devices(*devices: tuple[str, str, int, int]) -> str:
    """Return llama-server --list-devices output for (name, description, total, free)."""
    if not devices:
        return "Available devices:\n  (none)\n"
    lines = [f"  {n}: {d} ({t} MiB, {f} MiB free)" for n, d, t, f in devices]
    return "load_backend: loaded CUDA backend\nAvailable devices:\n" + "\n".join(lines) + "\n"


# Recorded tool output per fixture. Keys: uname, cpuinfo, meminfo, version
# (/proc/version), gpu (driver-tool CSV or None for no tool), modules
# (PowerShell output or None), df (available KiB), docker_info (or None for
# no engine), devices (list-devices output, rc) and gpu_check.
CASES = {
    "reference-host": {
        "uname": "MINGW64_NT-10.0-26300",
        "cpuinfo": cpuinfo("Intel(R) Core(TM) i5-14600KF", 20),
        "meminfo": meminfo(16567296, 9437184),
        "version": "",
        "gpu": "0, NVIDIA GeForce RTX 4070, 12282, 1270, 11012, 617.42, 8.9\r\n",
        "modules": "module 17179869184 5600\r\nslots 4\r\n",
        "df": 686817280,
        "docker_info": "linux|29.9.2|Docker Desktop|8203939840|20",
        "devices": None,
        "gpu_check": False,
    },
    "reference-host-gpu-visible": {
        "uname": "MINGW64_NT-10.0-26300",
        "cpuinfo": cpuinfo("Intel(R) Core(TM) i5-14600KF", 20),
        "meminfo": meminfo(16567296, 9437184),
        "version": "",
        "gpu": "0, NVIDIA GeForce RTX 4070, 12282, 1270, 11012, 617.42, 8.9\r\n",
        "modules": "module 17179869184 5600\r\nslots 4\r\n",
        "df": 686817280,
        "docker_info": "linux|29.9.2|Docker Desktop|8203939840|20",
        "devices": (list_devices(("CUDA0", "NVIDIA GeForce RTX 4070", 12281, 10776)), 0),
        "gpu_check": True,
    },
    "no-gpu": {
        "uname": "Linux",
        "cpuinfo": cpuinfo("AMD Ryzen 5 5600G with Radeon Graphics", 12),
        "meminfo": meminfo(16314368, 12582912),
        "version": "Linux version 6.8.0-generic",
        "gpu": None,
        "modules": None,
        "df": 209715200,
        "docker_info": "linux|29.9.2|Ubuntu 24.04.3 LTS|16705912832|12",
        "devices": (list_devices(), 0),
        "gpu_check": True,
    },
    "gpu-6gb": {
        "uname": "Linux",
        "cpuinfo": cpuinfo("Intel(R) Core(TM) i5-10400F CPU @ 2.90GHz", 12),
        "meminfo": meminfo(16314368, 13631488),
        "version": "Linux version 6.8.0-generic",
        "gpu": "0, NVIDIA GeForce RTX 2060, 6144, 312, 5832, 580.95.05, 7.5\n",
        "modules": None,
        "df": 314572800,
        "docker_info": "linux|29.9.2|Ubuntu 24.04.3 LTS|16705912832|12",
        "devices": (list_devices(("CUDA0", "NVIDIA GeForce RTX 2060", 5926, 5800)), 0),
        "gpu_check": True,
    },
    "gpu-8gb": {
        "uname": "MINGW64_NT-10.0-26200",
        "cpuinfo": cpuinfo("AMD Ryzen 7 7700", 16),
        "meminfo": meminfo(33163264, 20971520),
        "version": "",
        "gpu": "0, NVIDIA GeForce RTX 4060, 8188, 904, 7284, 617.42, 8.9\r\n",
        "modules": "module 17179869184 5200\r\nmodule 17179869184 5200\r\nslots 2\r\n",
        "df": 524288000,
        "docker_info": "linux|29.9.2|Docker Desktop|16559693824|16",
        "devices": (list_devices(("CUDA0", "NVIDIA GeForce RTX 4060", 8187, 7110)), 0),
        "gpu_check": True,
    },
    "gpu-24gb": {
        "uname": "Linux",
        "cpuinfo": cpuinfo("AMD Ryzen 9 7950X 16-Core Processor", 32),
        "meminfo": meminfo(65808384, 58720256),
        "version": "Linux version 6.8.0-generic",
        "gpu": "0, NVIDIA GeForce RTX 4090, 24564, 412, 24152, 580.95.05, 8.9\n",
        "modules": None,
        "df": 1048576000,
        "docker_info": "linux|29.9.2|Ubuntu 24.04.3 LTS|67387785216|32",
        "devices": (list_devices(("CUDA0", "NVIDIA GeForce RTX 4090", 24080, 23640)), 0),
        "gpu_check": True,
    },
}


def bash_quote(text: str) -> str:
    """Quote TEXT as one Bash ANSI-C string ($'...')."""
    escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
    return f"$'{escaped}'"


def stub_script(case: dict, calls: Path) -> str:
    """Return Bash that replaces every host tool the profile command uses.

    Each Docker call is appended to CALLS so tests can assert the exact
    container options and that nothing else ran.
    """
    lines = [
        f"uname() {{ printf '%s\\n' {bash_quote(case['uname'])}; }}",
        "read_proc() { case \"$1\" in",
        f"  cpuinfo) printf '%s' {bash_quote(case['cpuinfo'])} ;;",
        f"  meminfo) printf '%s' {bash_quote(case['meminfo'])} ;;",
        f"  version) printf '%s' {bash_quote(case['version'])} ;;",
        "esac; }",
        "profile_now() { printf '2026-10-28T12:00:00Z'; }",
        f"df() {{ printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\\n"
        f"fixture 2000000000 100 {case['df']} 50% /\\n'; }}",
    ]
    if case["gpu"] is None:
        lines.append("gpu_tool() { return 1; }")
    else:
        lines.append("gpu_tool() { printf 'nvidia-smi'; }")
        lines.append(f"nvidia-smi() {{ printf '%s' {bash_quote(case['gpu'])}; }}")
    if case["modules"] is not None:
        lines.append(f"powershell.exe() {{ printf '%s' {bash_quote(case['modules'])}; }}")
    if case["docker_info"] is None:
        lines.append("docker() { echo \"docker $*\" >> " + bash_quote(str(calls)) + "; return 1; }")
    else:
        output, status = case["devices"] or ("", 0)
        lines += [
            "docker() {",
            "  echo \"docker $*\" >> " + bash_quote(str(calls)) + ";",
            "  case \"$1\" in",
            f"    info) printf '%s\\n' {bash_quote(case['docker_info'])} ;;",
            "    image) return \"${FIXTURE_IMAGE_MISSING:-0}\" ;;",
            "    pull) return \"${FIXTURE_PULL_STATUS:-0}\" ;;",
            f"    run) printf '%s' {bash_quote(output)}; return {status} ;;",
            "    *) return 97 ;;",
            "  esac",
            "}",
        ]
    return "\n".join(lines) + "\n"


class HardwareProfileTest(unittest.TestCase):
    """Run the profile command against recorded tool output in a temporary copy."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="launcher profile ")
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        shutil.copyfile(LAUNCHER, self.dir / "start.sh")
        self.calls = self.dir / "docker-calls.txt"

    def profile(self, case: dict, *args: str, env: dict | None = None):
        """Run `cmd_models profile ARGS` with CASE's stubs; return (result, text)."""
        script = (f'source "{(self.dir / "start.sh").as_posix()}"\n'
                  f"{stub_script(case, self.calls)}"
                  f"cmd_models profile {' '.join(args)}\n")
        result = run_bash("-c", script, cwd=self.dir, extra_env=env)
        target = self.dir / ".local" / "hardware.json"
        text = target.read_text(encoding="utf-8") if target.exists() else ""
        return result, text

    def docker_calls(self) -> list[str]:
        """Return the recorded Docker invocations, one per line."""
        return self.calls.read_text(encoding="utf-8").splitlines() if self.calls.exists() else []

    def test_fixtures_match_launcher_output(self) -> None:
        """Every backend fixture is exactly what the launcher writes for its replay."""
        for name, case in CASES.items():
            with self.subTest(fixture=name):
                if self.calls.exists():
                    self.calls.unlink()
                args = ("--gpu-check",) if case["gpu_check"] else ()
                result, text = self.profile(case, *args)
                self.assertEqual(0, result.returncode, result.stderr + result.stdout)
                json.loads(text)
                fixture = FIXTURES / f"{name}.json"
                if UPDATE:
                    fixture.parent.mkdir(parents=True, exist_ok=True)
                    fixture.write_bytes(text.encode("utf-8"))
                self.assertEqual(fixture.read_bytes().decode("utf-8"), text,
                                 f"{fixture} differs; rerun with HARDWARE_PROFILE_UPDATE=1 after an intended change")

    def test_reference_host_reads_vram_from_the_driver_tool(self) -> None:
        """The 12 GB card is reported from the driver tool, with the module layout and WSL memory."""
        result, text = self.profile(CASES["reference-host"])
        self.assertEqual(0, result.returncode, result.stderr)
        profile = json.loads(text)
        self.assertEqual("rpg-engine.hardware-profile/1", profile["schema"])
        self.assertEqual("windows", profile["platform"])
        self.assertEqual("nvidia-smi", profile["gpuSource"])
        gpu = profile["gpus"][0]
        self.assertEqual((12282, 1270, 11012), (gpu["memoryTotalMiB"], gpu["memoryUsedMiB"], gpu["memoryFreeMiB"]))
        self.assertEqual(("617.42", "8.9"), (gpu["driverVersion"], gpu["computeCapability"]))
        self.assertEqual([{"capacityMiB": 16384, "speedMTs": 5600}], profile["memory"]["modules"])
        self.assertEqual(4, profile["memory"]["moduleSlots"])
        self.assertEqual("host", profile["memory"]["scope"])
        self.assertEqual(7823, profile["containerEngine"]["memoryTotalMiB"])
        self.assertEqual({"status": "not-checked", "image": IMAGE, "devices": []}, profile["containerGpu"])
        self.assertEqual(["docker info --format {{.OSType}}|{{.ServerVersion}}|{{.OperatingSystem}}|{{.MemTotal}}|{{.NCPU}}"],
                         self.docker_calls(), "a profile without --gpu-check must not pull or run anything")
        self.assertIn("--gpu-check", result.stdout)

    def test_gpu_check_runs_the_pinned_image_without_network_or_privileges(self) -> None:
        """--gpu-check runs only the pinned digest with no network, capabilities or root."""
        result, text = self.profile(CASES["reference-host-gpu-visible"], "--gpu-check")
        self.assertEqual(0, result.returncode, result.stderr)
        container = json.loads(text)["containerGpu"]
        self.assertEqual("visible", container["status"])
        self.assertEqual([{"name": "CUDA0", "description": "NVIDIA GeForce RTX 4070",
                           "memoryTotalMiB": 12281, "memoryFreeMiB": 10776}], container["devices"])
        runs = [c for c in self.docker_calls() if c.startswith("docker run ")]
        self.assertEqual(1, len(runs))
        for option in ("--rm", "--pull never", "--gpus all", "--network none", "--read-only",
                       "--cap-drop ALL", "--security-opt no-new-privileges:true", "--user 65534:65534",
                       f"{IMAGE} --list-devices"):
            self.assertIn(option, runs[0])
        self.assertFalse(any(c.startswith("docker pull") for c in self.docker_calls()))
        self.assertIn("container gpu: visible", result.stdout)

    def test_missing_image_is_pulled_once_by_digest(self) -> None:
        """An absent image is pulled by its pinned digest; a failed pull is recorded, not fatal."""
        result, _ = self.profile(CASES["reference-host-gpu-visible"], "--gpu-check",
                                 env={"FIXTURE_IMAGE_MISSING": "1"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"docker pull {IMAGE}", self.docker_calls())
        self.assertIn("pulling the pinned model-server image", result.stdout)
        self.calls.unlink()
        result, text = self.profile(CASES["reference-host-gpu-visible"], "--gpu-check",
                                    env={"FIXTURE_IMAGE_MISSING": "1", "FIXTURE_PULL_STATUS": "1"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("failed", json.loads(text)["containerGpu"]["status"])
        self.assertFalse(any(c.startswith("docker run ") for c in self.docker_calls()))

    def test_container_gpu_outcomes(self) -> None:
        """No CUDA device or a refused GPU request is not-visible; other run errors are failed."""
        cases = [
            (list_devices(), 0, "not-visible"),
            ("docker: Error response from daemon: could not select device driver \"\" with capabilities: [[gpu]]\n",
             125, "not-visible"),
            ("exec format error\n", 1, "failed"),
            (list_devices(("Vulkan0", "Some GPU", 4096, 4000)), 0, "not-visible"),
        ]
        for output, status, expected in cases:
            with self.subTest(expected=expected, output=output[:30]):
                case = dict(CASES["reference-host-gpu-visible"], devices=(output, status))
                result, text = self.profile(case, "--gpu-check")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(expected, json.loads(text)["containerGpu"]["status"])

    def test_missing_tools_are_recorded_not_fatal(self) -> None:
        """No driver tool, no PowerShell, no df and no Docker still yield a valid profile."""
        case = dict(CASES["no-gpu"], docker_info=None, devices=None)
        script = (f'source "{(self.dir / "start.sh").as_posix()}"\n'
                  f"{stub_script(case, self.calls)}"
                  "df() { return 127; }\n"
                  "cmd_models profile --gpu-check\n")
        result = run_bash("-c", script, cwd=self.dir)
        self.assertEqual(0, result.returncode, result.stderr)
        profile = json.loads((self.dir / ".local" / "hardware.json").read_text(encoding="utf-8"))
        self.assertEqual({"path": ".local", "freeMiB": None}, profile["disk"])
        self.assertEqual(("unavailable", []), (profile["gpuSource"], profile["gpus"]))
        self.assertEqual({"available": False}, profile["containerEngine"])
        self.assertEqual("engine-unavailable", profile["containerGpu"]["status"])
        self.assertIsNone(profile["memory"]["modules"])
        self.assertIn("advised for CPU only", result.stdout)

    def test_failing_driver_tool_and_placeholders(self) -> None:
        """A failing driver tool is "failed"; "[N/A]" fields become null, never text."""
        script = (f'source "{(self.dir / "start.sh").as_posix()}"\n'
                  f"{stub_script(CASES['reference-host'], self.calls)}"
                  "nvidia-smi() { return 9; }\n"
                  "cmd_models profile\n")
        result = run_bash("-c", script, cwd=self.dir)
        self.assertEqual(0, result.returncode, result.stderr)
        profile = json.loads((self.dir / ".local" / "hardware.json").read_text(encoding="utf-8"))
        self.assertEqual(("failed", []), (profile["gpuSource"], profile["gpus"]))
        result, text = self.profile(CASES["reference-host"] | {"gpu": "0, Quadro \"X\\, [N/A], 100, [N/A], 500.1, [N/A]\n"})
        gpu = json.loads(text)["gpus"][0]
        self.assertEqual('Quadro "X\\', gpu["name"])
        self.assertIsNone(gpu["memoryTotalMiB"])
        self.assertIsNone(gpu["memoryFreeMiB"])
        self.assertIsNone(gpu["computeCapability"])

    def test_wsl_memory_is_marked_as_vm_scope(self) -> None:
        """Under WSL the kernel's memory is the VM's, and the profile says so."""
        case = dict(CASES["gpu-24gb"], version="Linux version 6.18.54.1-microsoft-standard-WSL2")
        _, text = self.profile(case)
        profile = json.loads(text)
        self.assertEqual(("wsl", "vm"), (profile["platform"], profile["memory"]["scope"]))

    def test_profile_contains_no_identifiers(self) -> None:
        """Only hardware models, sizes and versions are written: no names, paths or UUIDs."""
        for name in CASES:
            text = (FIXTURES / f"{name}.json").read_text(encoding="utf-8")
            profile = json.loads(text)
            self.assertEqual({"schema", "generatedAt", "platform", "cpu", "memory", "gpuSource", "gpus",
                              "disk", "containerEngine", "containerGpu"}, set(profile))
            self.assertEqual(".local", profile["disk"]["path"])
            self.assertNotRegex(text, r"(?i)GPU-[0-9a-f]{8}|uuid|serial|hostname|/home/|C:\\\\Users")

    def test_usage_errors(self) -> None:
        """Unknown model commands and arguments are usage errors that write nothing."""
        for args in (["--models"], ["--models", "fetch"], ["--models", "profile", "--bogus"],
                     ["--models", "profile", "--gpu-check", "extra"]):
            with self.subTest(args=args):
                result = run_bash(str(self.dir / "start.sh"), *args, cwd=self.dir)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse((self.dir / ".local").exists())

    def test_json_string_escaping(self) -> None:
        """Quotes and backslashes are escaped and control characters dropped."""
        script = (f'source "{(self.dir / "start.sh").as_posix()}"\n'
                  "json_string $'a\"b\\\\c\\td'; printf '|'; json_text '[N/A]'; printf '|'; json_count 0042; "
                  "printf '|'; json_count 12x\n")
        result = run_bash("-c", script, cwd=self.dir)
        self.assertEqual('"a\\"b\\\\cd"|null|42|null', result.stdout)

    def test_gpu_tool_prefers_path_then_wsl_location(self) -> None:
        """The driver tool on PATH is used first; without it the lookup fails or uses WSL's copy."""
        script = (f'source "{(self.dir / "start.sh").as_posix()}"\n'
                  "nvidia-smi() { :; }\ngpu_tool\n")
        self.assertEqual("nvidia-smi", run_bash("-c", script, cwd=self.dir).stdout)
        script = (f'source "{(self.dir / "start.sh").as_posix()}"\n'
                  "PATH=/nonexistent\ngpu_tool && echo found || echo none\n")
        expected = "/usr/lib/wsl/lib/nvidia-smifound" if Path("/usr/lib/wsl/lib/nvidia-smi").exists() else "none"
        self.assertEqual(expected, run_bash("-c", script, cwd=self.dir).stdout.strip())


if __name__ == "__main__":
    unittest.main()
