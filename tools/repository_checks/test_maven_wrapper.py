"""Regression tests for the project's modified Maven Wrapper script, ``backend/mvnw``.

Upstream Maven Wrapper 3.3.4 downloads the ``.tar.gz`` distribution when ``unzip`` is
missing. That archive can never match the ZIP checksum pinned in
``distributionSha256Sum``, so a host without ``unzip`` and with an empty wrapper cache
always stopped with "Failed to validate Maven distribution SHA-256". When a checksum
is pinned, the committed copy keeps the ZIP and extracts it with the JDK ``jar`` tool
instead; without a pin the upstream ``.tar.gz`` path remains (ADR 0009). These tests
run the real script against a small fixture distribution served from a loopback HTTP
server, with a ``PATH`` that holds only the basic tools the script needs. That makes
the missing-``unzip`` case reproducible on any host and needs no external network,
account or secret. The script's own ``mktemp`` directory still goes to the host's
temporary directory, and the script removes it.

The script is POSIX shell, so the tests skip on Windows (``mvnw.cmd`` serves there).
Cases that download need a JDK (``java``, ``javac`` and ``jar`` under ``JAVA_HOME``
or next to ``javac`` on ``PATH``): without ``curl`` or ``wget`` the wrapper compiles
its own Java downloader, as on the development container. They skip with that reason
when no JDK is found, because the fallback under test cannot run there either. Cases
that need ``unzip`` skip on hosts that lack it.
"""

from __future__ import annotations

import functools
import hashlib
import http.server
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import checks  # noqa: E402  (path setup must precede the import)

ROOT = checks.ROOT
SCRATCH = ROOT / ".local" / "scratch"
WRAPPER = ROOT / "backend" / "mvnw"

# Fixture distribution; the version is fictional so no real cache entry can match it.
VERSION = "9.9.9"
DIST_DIR = f"apache-maven-{VERSION}"
REPO_DIR = f"/maven2/org/apache/maven/apache-maven/{VERSION}"
REPO_PATH = f"{REPO_DIR}/{DIST_DIR}-bin.zip"
TAR_PATH = f"{REPO_DIR}/{DIST_DIR}-bin.tar.gz"

# External commands the wrapper runs (``printf`` and ``command`` are shell builtins).
# ``unzip``, ``curl``, ``wget`` and the JDK are added per test so that their absence
# is deliberate. ``find`` is left out on purpose: the script must not need it.
# ``tar`` and ``gzip`` serve only the unpinned ``.tar.gz`` path.
BASE_TOOLS = ("uname", "dirname", "basename", "tr", "mktemp", "rm", "mkdir", "mv",
              "cat", "ls", "chmod", "tar", "gzip", "sha256sum", "shasum")
REQUIRED_TOOLS = ("uname", "dirname", "basename", "tr", "mktemp", "rm", "mkdir", "mv",
                  "cat", "chmod", "tar", "gzip")

# Entries of the fixture distribution: path, Unix mode and content. Like the real
# distribution, ``bin/mvn`` is 0755 while the Windows launcher and ``m2.conf`` are
# 0644, so the tests can tell whether only the launchers end up executable.
ENTRIES = (
    ("bin/mvn", 0o755, '#!/bin/sh\necho "fixture maven: $*"\n'),
    ("bin/mvn.cmd", 0o644, "@echo fixture maven\r\n"),
    ("bin/m2.conf", 0o644, "main is org.example.Main from plexus.core\n"),
    ("lib/README.txt", 0o644, "fixture library directory\n"),
)


def fixture_zip() -> bytes:
    """Return the distribution as a ZIP that records the Unix modes of ``ENTRIES``."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, mode, text in ENTRIES:
            info = zipfile.ZipInfo(f"{DIST_DIR}/{name}", date_time=(2026, 10, 29, 0, 0, 0))
            info.create_system = 3  # Unix, so unzip honours the stored mode bits
            info.external_attr = (0o100000 | mode) << 16
            archive.writestr(info, text)
    return buffer.getvalue()


def fixture_tar() -> bytes:
    """Return the same distribution as the ``.tar.gz`` that upstream fetches unpinned."""
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for name, mode, text in ENTRIES:
            data = text.encode("utf-8")
            info = tarfile.TarInfo(f"{DIST_DIR}/{name}")
            info.size, info.mode, info.mtime = len(data), mode, 1_793_232_000
            archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def find_jdk() -> Path | None:
    """Return a JDK home that has ``java``, ``javac`` and ``jar``, or ``None``."""
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    javac = shutil.which("javac")
    if javac:
        candidates.append(Path(javac).resolve().parent.parent)
    for home in candidates:
        if all((home / "bin" / tool).is_file() for tool in ("java", "javac", "jar")):
            return home
    return None


class FixtureServer:
    """Serve a directory on 127.0.0.1 and record every requested path."""

    def __init__(self, directory: Path) -> None:
        self.requests: list[str] = []
        recorded = self.requests

        class Handler(http.server.SimpleHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802  (http.server naming)
                recorded.append(self.path)
                super().do_GET()

            def log_message(self, *args: object) -> None:
                pass  # keep test output to the assertions

        handler = functools.partial(Handler, directory=str(directory))
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=10)


@unittest.skipIf(os.name == "nt", "mvnw is a POSIX shell script; Windows uses mvnw.cmd")
class MavenWrapperTest(unittest.TestCase):
    """Run the committed ``mvnw`` with and without ``unzip`` against a fixture repository."""

    def setUp(self) -> None:
        missing = [tool for tool in REQUIRED_TOOLS if shutil.which(tool) is None]
        if missing or not (shutil.which("sha256sum") or shutil.which("shasum")):
            self.fail(f"basic tools the wrapper needs are missing: {missing or 'sha256sum/shasum'}")
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.tmp = Path(tempfile.mkdtemp(prefix="mvnw-", dir=SCRATCH))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.zip_bytes = fixture_zip()
        served = self.tmp / "served"
        for path, data in ((REPO_PATH, self.zip_bytes), (TAR_PATH, fixture_tar())):
            target = served / path.lstrip("/")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.server = FixtureServer(served)
        self.addCleanup(self.server.close)
        self.home = self.tmp / "home"
        self.home.mkdir()
        self.project = self.tmp / "project"
        (self.project / ".mvn" / "wrapper").mkdir(parents=True)
        shutil.copyfile(WRAPPER, self.project / "mvnw")
        (self.project / "mvnw").chmod(0o755)

    def write_properties(self, path: str = REPO_PATH, checksum: str | None = None,
                         pinned: bool = True) -> str:
        """Pin ``path`` on the fixture server with ``checksum`` (default: the real one).

        ``pinned=False`` omits ``distributionSha256Sum``, as an unpinned project would.
        """
        url = self.server.base + path
        lines = ["wrapperVersion=3.3.4", "distributionType=only-script", f"distributionUrl={url}"]
        if pinned:
            lines.append(f"distributionSha256Sum={checksum or hashlib.sha256(self.zip_bytes).hexdigest()}")
        (self.project / ".mvn" / "wrapper" / "maven-wrapper.properties").write_text(
            "\n".join(lines) + "\n", encoding="utf-8")
        return url

    def run_wrapper(self, *, extra_tools: tuple[str, ...] = (), jdk: Path | None = None,
                    java_home: str | None = None, jdk_on_path: Path | None = None
                    ) -> subprocess.CompletedProcess[str]:
        """Run ``./mvnw --version`` with only the listed tools on ``PATH``.

        ``jdk`` sets an absolute ``JAVA_HOME``; ``java_home`` sets it verbatim (for a
        relative value); ``jdk_on_path`` links that JDK's ``java``, ``javac`` and
        ``jar`` into the tool directory instead. ``MAVEN_USER_HOME`` stays unset and
        ``HOME`` points into the fixture, which is how an unconfigured verification run
        calls the wrapper; the cache therefore lands in ``<home>/.m2``. No host proxy or
        other variable is passed on, and ``no_proxy`` keeps downloaders off any proxy
        configured in system files.
        """
        tools = Path(tempfile.mkdtemp(prefix="tools-", dir=self.tmp))
        for name in BASE_TOOLS + extra_tools:
            found = shutil.which(name)
            if found:
                (tools / name).symlink_to(found)
        if jdk_on_path is not None:
            for name in ("java", "javac", "jar"):
                (tools / name).symlink_to(jdk_on_path / "bin" / name)
        env = {"PATH": str(tools), "HOME": str(self.home), "LC_ALL": "C",
               "no_proxy": "127.0.0.1,localhost", "NO_PROXY": "127.0.0.1,localhost"}
        if jdk is not None:
            env["JAVA_HOME"] = str(jdk)
        if java_home is not None:
            env["JAVA_HOME"] = java_home
        return subprocess.run(["./mvnw", "--version"], cwd=self.project, env=env,
                              capture_output=True, text=True, timeout=180, check=False)

    def installed(self) -> list[Path]:
        """Return the installed distribution directories of the fixture version."""
        return sorted((self.home / ".m2" / "wrapper" / "dists" / DIST_DIR).glob("*"))

    def require_jdk(self) -> Path:
        jdk = find_jdk()
        if jdk is None:
            self.skipTest("no JDK with java, javac and jar; the jar fallback cannot run here")
        return jdk

    def assert_installed(self, url: str) -> None:
        """Check the single installed copy: launchers executable, other files not."""
        dists = self.installed()
        self.assertEqual(len(dists), 1, dists)
        bin_dir = dists[0] / "bin"
        self.assertTrue(os.access(bin_dir / "mvn", os.X_OK))
        self.assertFalse(os.access(bin_dir / "mvn.cmd", os.X_OK))
        self.assertFalse(os.access(bin_dir / "m2.conf", os.X_OK))
        self.assertEqual((dists[0] / "mvnw.url").read_text(encoding="utf-8").strip(), url)

    def assert_ran(self, result: subprocess.CompletedProcess[str]) -> None:
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("fixture maven: --version", result.stdout)

    def test_without_unzip_the_pinned_zip_is_verified_and_extracted_with_jar(self) -> None:
        jdk = self.require_jdk()
        url = self.write_properties()
        self.assert_ran(self.run_wrapper(jdk=jdk))
        # Only the pinned ZIP is fetched; upstream would have asked for the .tar.gz here.
        self.assertEqual(self.server.requests, [REPO_PATH])
        self.assert_installed(url)
        # A second run uses the cache and downloads nothing.
        self.assert_ran(self.run_wrapper(jdk=jdk))
        self.assertEqual(self.server.requests, [REPO_PATH])

    def test_a_relative_java_home_still_finds_jar_after_the_download(self) -> None:
        jdk = self.require_jdk()
        (self.project / "jdk").symlink_to(jdk, target_is_directory=True)
        url = self.write_properties()
        self.assert_ran(self.run_wrapper(java_home="jdk"))
        self.assertEqual(self.server.requests, [REPO_PATH])
        self.assert_installed(url)

    def test_a_jdk_found_only_on_path_is_used(self) -> None:
        jdk = self.require_jdk()
        url = self.write_properties()
        self.assert_ran(self.run_wrapper(jdk_on_path=jdk))
        self.assertEqual(self.server.requests, [REPO_PATH])
        self.assert_installed(url)

    def test_with_unzip_the_standard_path_is_unchanged(self) -> None:
        if shutil.which("unzip") is None:
            self.skipTest("unzip is not installed on this host")
        jdk = self.require_jdk()
        url = self.write_properties()
        self.assert_ran(self.run_wrapper(extra_tools=("unzip", "curl", "wget"), jdk=jdk))
        self.assertEqual(self.server.requests, [REPO_PATH])
        self.assert_installed(url)

    def test_without_unzip_and_without_a_pin_the_upstream_tarball_path_remains(self) -> None:
        jdk = self.require_jdk()
        url = self.write_properties(pinned=False)
        self.assert_ran(self.run_wrapper(jdk=jdk))
        self.assertEqual(self.server.requests, [TAR_PATH])
        self.assert_installed(url[: -len(".zip")] + ".tar.gz")

    def test_a_checksum_mismatch_still_stops_before_installing(self) -> None:
        jdk = self.require_jdk()
        self.write_properties(checksum="0" * 64)
        result = self.run_wrapper(jdk=jdk)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Failed to validate Maven distribution SHA-256", result.stderr)
        self.assertNotIn("fixture maven", result.stdout)
        self.assertEqual(self.server.requests, [REPO_PATH])
        self.assertEqual(self.installed(), [])

    def test_a_failed_download_is_reported_as_such(self) -> None:
        jdk = self.require_jdk()
        missing = f"{REPO_DIR}/missing/{DIST_DIR}-bin.zip"
        self.write_properties(path=missing)
        result = self.run_wrapper(jdk=jdk)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Java: Failed to fetch", result.stderr)
        self.assertNotIn("Failed to validate", result.stderr)
        self.assertEqual(self.installed(), [])

    def test_without_unzip_or_jdk_it_fails_fast_with_the_remedy(self) -> None:
        self.write_properties()
        result = self.run_wrapper()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Neither unzip nor a JDK is available", result.stderr)
        self.assertNotIn("Failed to validate", result.stderr)
        self.assertEqual(self.server.requests, [])
        self.assertEqual(self.installed(), [])

    def test_with_unzip_but_no_downloader_it_says_so(self) -> None:
        if shutil.which("unzip") is None:
            self.skipTest("unzip is not installed on this host")
        self.write_properties()
        result = self.run_wrapper(extra_tools=("unzip",))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Cannot download", result.stderr)
        self.assertNotIn("Failed to validate", result.stderr)
        self.assertEqual(self.server.requests, [])
        self.assertEqual(self.installed(), [])


if __name__ == "__main__":
    unittest.main()
