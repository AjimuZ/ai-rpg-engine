"""Check the root start.sh launcher without starting any service (P02.6).

Inputs are the tracked launcher, Compose file and example environment. Outputs are
test assertions. Pure-Bash helpers (version comparison, .env parsing, redaction)
are exercised by sourcing the script in a temporary copy, so nothing touches the
real checkout's private state. The command-surface tests that need a Docker daemon
skip when none is reachable. The full build/start/stop path is verified manually
and documented in docs/22-container-deployment-and-startup.md, not here: it needs
the loopback port, minutes of build time and a developer's Docker state.
"""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "start.sh"


def find_bash() -> str:
    """Return a GNU Bash to run the launcher; LAUNCHER_BASH overrides discovery.

    On Windows `bash` on PATH can be the WSL stub, so prefer Git for Windows.
    """
    override = os.environ.get("LAUNCHER_BASH")
    if override:
        return override
    for base in (os.environ.get("ProgramFiles"), os.environ.get("ProgramW6432")):
        if base:
            candidate = Path(base) / "Git" / "bin" / "bash.exe"
            if candidate.exists():
                return str(candidate)
    return shutil.which("bash") or "bash"


BASH = find_bash()


def run_bash(*args: str, cwd: Path, extra_env: dict | None = None) -> subprocess.CompletedProcess:
    """Run Bash with captured text output; the caller inspects the result."""
    env = dict(os.environ)
    # Keep launcher state out of any inherited installation.
    for name in ("APP_PORT", "POSTGRES_PASSWORD_FILE", "COMPOSE_PROJECT_NAME"):
        env.pop(name, None)
    if extra_env:
        env.update(extra_env)
    return subprocess.run([BASH, *args], cwd=cwd, env=env, capture_output=True,
                          text=True, timeout=120, check=False)


def docker_daemon_available() -> bool:
    """Report whether `docker info` succeeds; used to skip daemon-dependent checks."""
    try:
        return subprocess.run(["docker", "info"], capture_output=True, timeout=30,
                              check=False).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def code_only(text: str) -> str:
    """Drop here-document help text and comments so rules apply to executable lines."""
    text = re.sub(r"<<'?(\w+)'?\n.*?\n\1\n", "\n", text, flags=re.S)
    return "\n".join(re.sub(r"(^|\s)#.*$", "", line) for line in text.splitlines())


class LauncherStaticTest(unittest.TestCase):
    """Source-level contract: file format, forbidden commands and safe teardown."""

    def setUp(self) -> None:
        self.text = LAUNCHER.read_text(encoding="utf-8")
        self.code = code_only(self.text)

    def test_file_format(self) -> None:
        """Require a Bash shebang, LF endings and valid syntax."""
        raw = LAUNCHER.read_bytes()
        self.assertTrue(raw.startswith(b"#!/usr/bin/env bash\n"))
        self.assertNotIn(b"\r", raw)
        result = run_bash("-n", str(LAUNCHER), cwd=ROOT)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_executable_bit_is_tracked(self) -> None:
        """Require git mode 100755 so Linux/macOS checkouts can run ./start.sh."""
        try:
            result = subprocess.run(["git", "ls-files", "-s", "start.sh"], cwd=ROOT,
                                    capture_output=True, text=True, timeout=30, check=False)
        except OSError:
            self.skipTest("git is not available")
        if result.returncode != 0 or not result.stdout.strip():
            self.skipTest("start.sh is not tracked in this tree")
        self.assertTrue(result.stdout.startswith("100755 "), result.stdout)

    def test_no_unlisted_host_tools(self) -> None:
        """Forbid host HTTP/crypto/runtime tools; ADR 0009 allows only Docker plus basics."""
        for number, code in enumerate(self.code.splitlines(), start=1):
            line = code
            if "compose exec" in code:
                continue  # curl runs inside the engine image, not on the host
            if re.search(r"(?<![\w-])(curl|wget|openssl|jq|python3?|node|npm|mvn|java)(?![\w-])", code):
                self.fail(f"start.sh:{number} uses a host tool outside the baseline: {line.strip()}")

    def test_never_deletes_data(self) -> None:
        """Forbid commands that remove volumes or unrelated Docker resources."""
        forbidden = [r"down[^\n]*(\s-v\b|--volumes)", r"volume\s+(rm|remove|prune)",
                     r"\b(system|image|container|network|builder)\s+prune", r"\brm\s+-[a-z]*r"]
        for pattern in forbidden:
            self.assertIsNone(re.search(pattern, self.code), pattern)

    def test_does_not_print_secret_values(self) -> None:
        """Require that no output command expands the secret file or generated value."""
        self.assertNotRegex(self.text, r"(echo|printf|info|warn|die)[^\n|>]*\$\(<\"?\$SECRET_FILE")
        self.assertNotRegex(self.text, r"cat\s+\"?\$SECRET_FILE")


class LauncherCommandSurfaceTest(unittest.TestCase):
    """Run the real script for commands that need no Docker daemon."""

    def test_help_and_usage_errors(self) -> None:
        """`--help` exits 0 with every command; unknown or extra arguments exit 2."""
        result = run_bash(str(LAUNCHER), "--help", cwd=Path(tempfile.gettempdir()))
        self.assertEqual(0, result.returncode, result.stderr)
        for command in ("--check", "--login", "--status", "--logs", "--stop", "--help"):
            self.assertIn(command, result.stdout)
        self.assertIn("https://learn.chatgpt.com/docs/auth", result.stdout)
        for args in (["--bogus"], ["--stop", "extra"], ["--status", "x"]):
            failed = run_bash(str(LAUNCHER), *args, cwd=ROOT)
            self.assertEqual(2, failed.returncode, (args, failed.stderr))

    def test_old_bash_is_rejected_before_parsing(self) -> None:
        """The version guard text sits before the first Bash-4-only construct."""
        text = LAUNCHER.read_text(encoding="utf-8")
        guard = text.index("BASH_VERSINFO")
        self.assertLess(guard, text.index("set -euo pipefail"))
        self.assertLess(guard, text.index("local -a"))

    @unittest.skipUnless(docker_daemon_available(), "needs a reachable Docker daemon")
    def test_check_is_read_only_and_works_from_a_spaced_path(self) -> None:
        """`--check` from another directory creates no file in a path with spaces."""
        with tempfile.TemporaryDirectory(prefix="launcher check ") as tmp:
            copy = Path(tmp) / "checkout copy"
            copy.mkdir()
            for name in ("start.sh", "compose.yaml", ".env.example"):
                shutil.copyfile(ROOT / name, copy / name)
            shutil.copytree(ROOT / "docker", copy / "docker")
            before = sorted(p.name for p in copy.iterdir())
            result = run_bash(str(copy / "start.sh"), "--check", cwd=Path(tmp))
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            self.assertEqual(before, sorted(p.name for p in copy.iterdir()))
            self.assertIn("check passed", result.stdout)

    @unittest.skipUnless(docker_daemon_available(), "needs a reachable Docker daemon")
    def test_check_reports_an_invalid_port(self) -> None:
        """A non-numeric APP_PORT is a nonzero, actionable failure."""
        with tempfile.TemporaryDirectory(prefix="launcher port ") as tmp:
            copy = Path(tmp)
            for name in ("start.sh", "compose.yaml", ".env.example"):
                shutil.copyfile(ROOT / name, copy / name)
            result = run_bash(str(copy / "start.sh"), "--check", cwd=copy,
                              extra_env={"APP_PORT": "abc"})
            self.assertEqual(1, result.returncode)
            self.assertIn("APP_PORT", result.stderr)


class LauncherFunctionTest(unittest.TestCase):
    """Source the launcher in a temporary copy and call its pure helpers."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="launcher fn ")
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        shutil.copyfile(LAUNCHER, self.dir / "start.sh")

    def call(self, snippet: str, extra_env: dict | None = None) -> subprocess.CompletedProcess:
        """Source the copy (defines functions only) and run SNIPPET in that shell."""
        script = f'source "{(self.dir / "start.sh").as_posix()}"\n{snippet}\n'
        return run_bash("-c", script, cwd=self.dir, extra_env=extra_env)

    def test_version_comparison(self) -> None:
        """Dotted versions compare numerically with floors from ADR 0009."""
        cases = [("29.9.2", "29.0", 0), ("29.0.0", "29.0", 0), ("28.9.9", "29.0", 1),
                 ("v5.6.1", "5.0", 0), ("4.99", "5.0", 1), ("29.10", "29.9", 0),
                 ("29.09", "29.9", 0), ("29.0.0-rc1", "29.0", 0)]
        for actual, minimum, expected in cases:
            result = self.call(f'version_ge "{actual}" "{minimum}" && echo 0 || echo 1')
            self.assertEqual(str(expected), result.stdout.strip().splitlines()[-1],
                             (actual, minimum, result.stderr))

    def test_env_file_parsing(self) -> None:
        """Parse .env without executing it; environment wins; defaults apply."""
        (self.dir / ".env").write_bytes(
            b"# comment\r\nAPP_PORT=9090 # inline\r\nPOSTGRES_DB=\"quoted db\"\r\n"
            b"INJECT=$(touch pwned)\r\n")
        result = self.call('echo "[$(config_value APP_PORT 1)][$(config_value POSTGRES_DB x)]'
                           '[$(config_value MISSING dflt)][$(config_value INJECT z)]"')
        self.assertEqual("[9090][quoted db][dflt][$(touch pwned)]", result.stdout.strip(), result.stderr)
        self.assertFalse((self.dir / "pwned").exists())
        override = self.call('echo "$(config_value APP_PORT 1)"', extra_env={"APP_PORT": "7777"})
        self.assertEqual("7777", override.stdout.strip())

    def test_redaction(self) -> None:
        """Credential-shaped text and the exact password value never survive."""
        sample = "0123456789abcdef" + "0123456789abcdef"
        (self.dir / "pw").write_text(sample)
        # Fixtures are assembled from pieces so no credential-shaped literal is stored in
        # the repository (the secrets check would otherwise flag this file).
        key_value = "pass" + "word=hunter2abc ok"
        bearer = "Authorization: Bearer " + "abc.def-123456"
        api_key = "key " + "sk-" + "abcdefghijklmnopqrstuv x"
        lines = [f"db {key_value}", bearer, f"leak {sample} end", api_key, "plain line"]
        printf = " ".join(f"'{line}'" for line in lines)
        result = self.call(f'SECRET_FILE="{(self.dir / "pw").as_posix()}"\n'
                           f'printf "%s\\n" {printf} | redact_stream')
        self.assertEqual(0, result.returncode, result.stderr)
        for fragment in ("hunter2abc", "abc.def-123456", sample, "sk-" + "abcdefghijklmnopqrstuv"):
            self.assertNotIn(fragment, result.stdout)
        self.assertIn("plain line", result.stdout)

    def test_identity_and_secret_generation(self) -> None:
        """Generate a private 64-hex password once and refuse to overwrite or leak it."""
        (self.dir / ".env.example").write_text("APP_PORT=8080\n")
        result = self.call('mkdir -p .local; SECRET_FILE=.local/secrets/postgres-password\n'
                           'PROJECT=launcher-test\n'
                           'docker() { return 1; }  # no volume exists\n'
                           'ensure_secret; first="$(<"$SECRET_FILE")"; ensure_secret\n'
                           '[ "$first" = "$(<"$SECRET_FILE")" ] && echo same\n'
                           'echo "len=${#first}"; [[ "$first" =~ ^[0-9a-f]{64}$ ]] && echo hex\n'
                           'ensure_identity; echo "$PROJECT"')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("same", result.stdout)
        self.assertIn("len=64", result.stdout)
        self.assertIn("hex", result.stdout)
        self.assertRegex(result.stdout, r"rpg-engine-[0-9a-f]{12}")
        secret = (self.dir / ".local" / "secrets" / "postgres-password").read_text()
        self.assertNotIn(secret, result.stdout + result.stderr)
        self.assertFalse(secret.endswith("\n"))

    def test_missing_secret_with_existing_database_is_refused(self) -> None:
        """A new password must never be generated for an existing database volume."""
        result = self.call('SECRET_FILE=.local/secrets/postgres-password; PROJECT=launcher-test\n'
                           'docker() { return 0; }  # the volume exists\n'
                           '( ensure_secret ) || echo "rc=$?"')
        self.assertIn("rc=1", result.stdout)
        self.assertIn("database volume", result.stderr)
        self.assertFalse((self.dir / ".local" / "secrets" / "postgres-password").exists())

    def test_empty_secret_is_refused_and_untouched(self) -> None:
        """An existing empty file is reported, not silently replaced."""
        secrets_dir = self.dir / "s"
        secrets_dir.mkdir()
        (secrets_dir / "pw").write_text("")
        result = self.call('SECRET_FILE=s/pw; PROJECT=x\n( ensure_secret ) || echo "rc=$?"')
        self.assertIn("rc=1", result.stdout)
        self.assertEqual("", (secrets_dir / "pw").read_text())

    def test_malformed_identity_is_not_regenerated(self) -> None:
        """A damaged installation id fails loudly; regenerating would orphan volumes."""
        (self.dir / ".local").mkdir()
        (self.dir / ".local" / "installation-id").write_text("not-an-id\n")
        result = self.call('( ensure_identity ) || echo "rc=$?"')
        self.assertIn("rc=1", result.stdout)
        self.assertEqual("not-an-id\n", (self.dir / ".local" / "installation-id").read_text())


if __name__ == "__main__":
    unittest.main()
