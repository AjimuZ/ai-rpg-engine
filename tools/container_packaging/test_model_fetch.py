"""Check the model-fetch helper's verification and installation rules on fixtures only.

Input is `docker/model-fetch/model_fetch.py`, driven through its own `main` with a fixture
catalog and a recording opener. No case opens a socket: every URL is under the reserved
`.invalid` domain and a URL the opener does not know fails the case. The launcher's use
of the helper in the real container is in `test_model_fetch_launcher.py`.
"""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import signal
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "docker" / "model-fetch" / "model_fetch.py"
REAL_CATALOG = ROOT / "backend" / "src" / "main" / "resources" / "inference" / "model-catalog.json"


def load_helper():
    """Import the helper by path: its directory is not a package and it must stay one file."""
    specification = importlib.util.spec_from_file_location("model_fetch", HELPER)
    module = importlib.util.module_from_spec(specification)
    # Dataclasses look their module up here while the class is being created.
    sys.modules["model_fetch"] = module
    specification.loader.exec_module(module)
    return module


model_fetch = load_helper()


def file_node(name: str, content: bytes, role: str = "weights", **changes) -> dict:
    """Return a catalog file for `content`, with any field replaced to create a mismatch."""
    node = {"role": role, "name": name, "url": f"https://fixtures.invalid/{name}",
            "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
    node.update(changes)
    return node


def model_node(identifier: str, *files: dict, **changes) -> dict:
    """Return a catalog entry holding the fields the helper relies on."""
    node = {"id": identifier, "displayName": f"Fixture {identifier}", "format": "gguf",
            "source": {"host": "huggingface", "page": f"https://fixtures.invalid/{identifier}"},
            "files": list(files),
            "license": {"id": "fixture-license", "class": "permissive",
                        "url": f"https://fixtures.invalid/{identifier}/license",
                        "basis": "model-card", "confirmationRequired": False},
            "attribution": f"Fixture credit for {identifier}", "contentNotes": []}
    node.update(changes)
    return node


class FixtureResponse(io.BytesIO):
    """A response with the one header the helper reads; optionally breaking while read."""

    def __init__(self, content: bytes, declared: str | None, failure: BaseException | None) -> None:
        super().__init__(content)
        self.headers = {} if declared is None else {"Content-Length": declared}
        self.failure = failure
        self.delivered = 0

    def read(self, size: int = -1) -> bytes:
        """Return bytes, and raise the configured failure once the body is used up."""
        chunk = super().read(size)
        self.delivered += len(chunk)
        if not chunk and self.failure is not None:
            raise self.failure
        return chunk


class FixtureOpener:
    """Serve known URLs from memory, record each request and refuse every other URL."""

    def __init__(self, bodies: dict[str, bytes]) -> None:
        self.bodies = bodies
        self.declared: dict[str, str] = {}
        self.failures: dict[str, BaseException] = {}
        self.opened: list[str] = []

    def open(self, url: str, timeout: float) -> FixtureResponse:
        """Return the fixture response, or raise an error for an unknown URL."""
        self.opened.append(url)
        if url not in self.bodies:
            raise urllib.error.URLError(f"unknown fixture URL {url}")
        return FixtureResponse(self.bodies[url], self.declared.get(url), self.failures.get(url))


class Fixture(unittest.TestCase):
    """Shared temporary catalog, model directory and helper invocation."""

    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="model fetch ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.models = self.root / "models"
        self.models.mkdir()
        self.catalog = self.root / "catalog.json"

    def write_catalog(self, *entries: dict) -> None:
        """Write a fixture catalog with the real format name."""
        self.catalog.write_text(json.dumps({"schema": model_fetch.SCHEMA, "entries": list(entries)}),
                                encoding="utf-8")

    def run_helper(self, *arguments: str, opener: FixtureOpener | None = None) -> tuple[int, str, str]:
        """Run `main` once and return its status, stdout and stderr."""
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            try:
                status = model_fetch.main(list(arguments), opener or FixtureOpener({}))
            except SystemExit as stopped:
                status = stopped.code
        return status, stdout.getvalue(), stderr.getvalue()

    def install(self, *identifiers: str, opener: FixtureOpener) -> tuple[int, str, str]:
        """Run `install` with every requested ID confirmed."""
        return self.run_helper("install", "--catalog", str(self.catalog), "--models-dir", str(self.models),
                               "--accepted", ",".join(identifiers), *identifiers, opener=opener)

    def tree(self) -> list[str]:
        """Return every path below the model directory, so a leftover cannot go unseen."""
        return sorted(path.relative_to(self.models).as_posix() for path in self.models.rglob("*"))


class CatalogTest(Fixture):
    """Only a catalog whose file facts can be trusted reaches a download."""

    def test_the_real_catalog_loads_with_https_urls_and_exact_facts(self) -> None:
        """The bundled catalog parses and each of its entries can be described offline."""
        models = model_fetch.load_catalog(REAL_CATALOG)
        self.assertEqual(8, len(models))
        pony = models["pony-diffusion-v6-xl"]
        self.assertEqual(2, len(pony.files))
        self.assertEqual("restricted", pony.license_class)
        for model in models.values():
            text = model_fetch.describe([model])
            self.assertIn(model.license_url, text)
            for item in model.files:
                self.assertIn(item.url, text)
                self.assertIn(item.sha256, text)

    def test_unsafe_or_incomplete_catalogs_are_refused(self) -> None:
        """A path, a non-HTTPS URL, a bad digest or a repeated ID stops before any download."""
        content = b"weights"
        cases = {
            "traversal": model_node("a", file_node("../../escape.gguf", content)),
            "separator": model_node("a", file_node("sub/escape.gguf", content)),
            "backslash": model_node("a", file_node("sub\\escape.gguf", content)),
            "extension": model_node("a", file_node("weights.bin", content)),
            "plain http": model_node("a", file_node("a.gguf", content, url="http://fixtures.invalid/a.gguf")),
            "file url": model_node("a", file_node("a.gguf", content, url="file:///etc/passwd")),
            "zero size": model_node("a", file_node("a.gguf", content, bytes=0)),
            "text size": model_node("a", file_node("a.gguf", content, bytes="7")),
            "true size": model_node("a", file_node("a.gguf", content, bytes=True)),
            "upper digest": model_node("a", file_node("a.gguf", content, sha256="A" * 64)),
            "short digest": model_node("a", file_node("a.gguf", content, sha256="a" * 63)),
            "no files": model_node("a"),
            "no license": model_node("a", file_node("a.gguf", content), license=None),
            "license url": model_node("a", file_node("a.gguf", content),
                                      license={"id": "x", "class": "permissive", "basis": "model-card",
                                               "url": "http://fixtures.invalid/license"}),
            "unknown format": model_node("a", file_node("a.gguf", content), format="pickle"),
            "bad id": model_node("A/B", file_node("a.gguf", content)),
        }
        for name, entry in cases.items():
            with self.subTest(name):
                self.write_catalog(entry)
                opener = FixtureOpener({})
                status, _, error = self.install("a", opener=opener)
                self.assertEqual(1, status, error)
                self.assertIn("invalid catalog", error)
                self.assertEqual([], opener.opened)
                self.assertEqual([], self.tree())

    def test_another_schema_a_repeat_or_an_unreadable_file_is_refused(self) -> None:
        """The format name is exact, IDs are unique and a missing file is a clean failure."""
        entry = model_node("a", file_node("a.gguf", b"x"))
        self.catalog.write_text(json.dumps({"schema": "rpg-engine.model-catalog/2", "entries": [entry]}),
                                encoding="utf-8")
        self.assertEqual(1, self.install("a", opener=FixtureOpener({}))[0])
        self.write_catalog(entry, entry)
        self.assertIn("repeats", self.install("a", opener=FixtureOpener({}))[2])
        self.catalog.unlink()
        self.assertIn("cannot read", self.install("a", opener=FixtureOpener({}))[2])
        self.catalog.write_text("{not json", encoding="utf-8")
        self.assertIn("cannot read", self.install("a", opener=FixtureOpener({}))[2])


class DescribeTest(Fixture):
    """The owner reads the license, source and files before anything is accepted."""

    def test_license_source_and_files_are_shown_without_any_request(self) -> None:
        """`describe` prints every fact for the named models only and opens no URL."""
        self.write_catalog(model_node("a", file_node("a.gguf", b"aaaa"), contentNotes=["nsfw-capable"]),
                           model_node("b", file_node("b.gguf", b"bb")))
        opener = FixtureOpener({})
        status, output, _ = self.run_helper("describe", "--catalog", str(self.catalog), "a", opener=opener)
        self.assertEqual(0, status)
        for expected in ("Model a: Fixture a", "License: fixture-license (class permissive, basis model-card)",
                         "https://fixtures.invalid/a/license", "https://fixtures.invalid/a.gguf",
                         "Fixture credit for a", "nsfw-capable", "4 bytes",
                         hashlib.sha256(b"aaaa").hexdigest()):
            self.assertIn(expected, output)
        self.assertNotIn("Model b", output)
        self.assertEqual([], opener.opened)

    def test_an_unknown_id_is_a_usage_error_listing_the_catalog(self) -> None:
        """An ID outside the catalog exits 2 and names what could have been asked for."""
        self.write_catalog(model_node("a", file_node("a.gguf", b"a")))
        status, _, error = self.run_helper("describe", "--catalog", str(self.catalog), "missing")
        self.assertEqual(2, status)
        self.assertIn("unknown model ID 'missing'", error)
        self.assertIn("Catalog IDs: a", error)


class InstallTest(Fixture):
    """A file reaches the model directory only after exact size and SHA-256 checks."""

    def single(self, content: bytes, served: bytes | None = None, **changes) -> tuple[FixtureOpener, str]:
        """Write a one-file catalog and return an opener serving `served` for its URL."""
        node = file_node("a.gguf", content, **changes)
        self.write_catalog(model_node("a", node))
        return FixtureOpener({node["url"]: content if served is None else served}), node["url"]

    def assert_refused(self, opener: FixtureOpener, message: str) -> None:
        """Require exit 1, the reason, and a model directory with nothing added."""
        before = self.tree()
        status, _, error = self.install("a", opener=opener)
        self.assertEqual(1, status, error)
        self.assertIn(message, error)
        self.assertIn("nothing new was installed", error)
        self.assertEqual(before, self.tree())

    def test_matching_bytes_are_installed_with_no_leftover(self) -> None:
        """Exit 0 leaves exactly the catalog's file name with the exact content."""
        opener, url = self.single(b"weights")
        status, output, error = self.install("a", opener=opener)
        self.assertEqual(0, status, error)
        self.assertEqual(["a.gguf"], self.tree())
        self.assertEqual(b"weights", (self.models / "a.gguf").read_bytes())
        self.assertIn("installed a.gguf", output)
        self.assertEqual([url], opener.opened)

    def test_verified_file_is_given_the_servers_read_mode(self) -> None:
        """The model server runs as another user, so the helper asks for mode 0644 explicitly.

        This proves the request on every host. Its effect under a strict umask is read back on
        a POSIX filesystem in `test_model_fetch_launcher.py`, because Windows keeps no such mode.
        """
        opener, _ = self.single(b"weights")
        requested: list[tuple[str, int]] = []
        real_chmod = Path.chmod

        def record(path, mode, **options):
            requested.append((path.name, mode))
            real_chmod(path, mode, **options)

        with mock.patch.object(model_fetch.Path, "chmod", record):
            self.assertEqual(0, self.install("a", opener=opener)[0])
        self.assertEqual([("a.gguf" + model_fetch.PART_SUFFIX, 0o644)], requested)

    def test_a_share_refusing_modes_does_not_fail_the_install(self) -> None:
        """Docker Desktop's Windows shares may refuse the call; the verified file still installs."""
        opener, _ = self.single(b"weights")
        with mock.patch.object(model_fetch.Path, "chmod", side_effect=PermissionError("not permitted")):
            self.assertEqual(0, self.install("a", opener=opener)[0])
        self.assertEqual(["a.gguf"], self.tree())

    def test_sha256_mismatch_installs_nothing(self) -> None:
        """Bytes of the right size with another digest stay out."""
        opener, _ = self.single(b"weights", served=b"WEIGHTS")
        self.assert_refused(opener, "SHA-256 mismatch")

    def test_short_download_installs_nothing(self) -> None:
        """A truncated body fails on size, whatever the transport reported."""
        opener, _ = self.single(b"weights", served=b"wei")
        self.assert_refused(opener, "size mismatch, received 3 bytes")

    def test_long_download_stops_at_the_catalog_size(self) -> None:
        """An oversized body fails without being read or written to its end."""
        served = b"weights" + b"x" * (3 * model_fetch.CHUNK_BYTES)
        opener, url = self.single(b"weights", served=served)
        response = FixtureResponse(served, None, None)
        opener.open = lambda _url, timeout: response
        self.assert_refused(opener, "more than the catalog's 7 bytes")
        self.assertLess(response.delivered, len(served))

    def test_declared_length_mismatch_is_refused_before_the_body(self) -> None:
        """A wrong Content-Length fails even when the body itself would pass."""
        opener, url = self.single(b"weights")
        opener.declared[url] = "8"
        self.assert_refused(opener, "the source declares 8 bytes")

    def test_a_matching_hash_with_the_wrong_catalog_size_is_refused(self) -> None:
        """The digest does not excuse the size: both facts must hold."""
        opener, _ = self.single(b"weights", bytes=8)
        self.assert_refused(opener, "size mismatch, received 7 bytes")

    def test_a_matching_size_with_the_wrong_catalog_hash_is_refused(self) -> None:
        """The size does not excuse the digest."""
        opener, _ = self.single(b"weights", sha256="0" * 64)
        self.assert_refused(opener, "SHA-256 mismatch")

    def test_transfer_failures_install_nothing(self) -> None:
        """An HTTP refusal, an unknown host and a broken stream fail cleanly."""
        opener, url = self.single(b"weights")
        opener.failures[url] = ConnectionResetError("connection reset")
        self.assert_refused(opener, "download failed")
        self.assert_refused(FixtureOpener({}), "download failed")
        refused = FixtureOpener({})
        refused.open = mock.Mock(side_effect=urllib.error.HTTPError(url, 403, "Forbidden", None, None))
        self.assert_refused(refused, "HTTP 403")

    def test_one_bad_file_of_a_model_installs_no_file_of_it(self) -> None:
        """Weights and VAE arrive together or not at all."""
        good, bad = file_node("a.gguf", b"good"), file_node("v.gguf", b"bad", role="vae")
        self.write_catalog(model_node("a", good, bad))
        opener = FixtureOpener({good["url"]: b"good", bad["url"]: b"BAD"})
        self.assert_refused(opener, "v.gguf: SHA-256 mismatch")
        self.assertEqual(2, len(opener.opened))

    def test_one_bad_model_of_a_command_installs_no_model_of_it(self) -> None:
        """A failure on the second model also keeps the first, already verified, out."""
        first, second = file_node("a.gguf", b"good"), file_node("b.gguf", b"bad")
        self.write_catalog(model_node("a", first), model_node("b", second))
        opener = FixtureOpener({first["url"]: b"good", second["url"]: b"BAD"})
        status, _, error = self.install("a", "b", opener=opener)
        self.assertEqual(1, status)
        self.assertIn("b.gguf: SHA-256 mismatch", error)
        self.assertEqual([], self.tree())

    def test_several_models_install_together(self) -> None:
        """Every requested model is installed, in one command."""
        first, second = file_node("a.gguf", b"one"), file_node("b.gguf", b"two")
        self.write_catalog(model_node("a", first), model_node("b", second))
        opener = FixtureOpener({first["url"]: b"one", second["url"]: b"two"})
        self.assertEqual(0, self.install("a", "b", opener=opener)[0])
        self.assertEqual(["a.gguf", "b.gguf"], self.tree())

    def test_a_failed_move_undoes_this_commands_moves(self) -> None:
        """A filesystem failure between two moves leaves neither file."""
        first, second = file_node("a.gguf", b"one"), file_node("b.gguf", b"two")
        self.write_catalog(model_node("a", first), model_node("b", second))
        opener = FixtureOpener({first["url"]: b"one", second["url"]: b"two"})
        real_replace = os.replace

        def fail_second(source, destination):
            if Path(destination).name == "b.gguf":
                raise OSError("no space left")
            real_replace(source, destination)

        with mock.patch.object(model_fetch.os, "replace", fail_second):
            status, _, error = self.install("a", "b", opener=opener)
        self.assertEqual(1, status)
        self.assertIn("no space left", error)
        self.assertEqual([], self.tree())

    def test_identical_existing_file_is_kept_without_a_download(self) -> None:
        """A verified file needs no second download; the command still succeeds."""
        opener, _ = self.single(b"weights")
        (self.models / "a.gguf").write_bytes(b"weights")
        status, output, _ = self.install("a", opener=opener)
        self.assertEqual(0, status)
        self.assertIn("already installed and verified", output)
        self.assertEqual([], opener.opened)

    def test_different_existing_file_is_refused_and_left_untouched(self) -> None:
        """The owner's file survives, whether its size or only its content differs."""
        for existing in (b"weights!", b"WEIGHTS"):
            with self.subTest(existing=existing):
                opener, _ = self.single(b"weights")
                (self.models / "a.gguf").write_bytes(existing)
                self.assert_refused(opener, "already exists in the model directory")
                self.assertEqual(existing, (self.models / "a.gguf").read_bytes())
                self.assertEqual([], opener.opened)

    def test_file_appearing_during_the_run_is_left_untouched(self) -> None:
        """The check before each move keeps a concurrent file from being replaced."""
        opener, url = self.single(b"weights")
        real_open = opener.open

        def appear_then_open(requested: str, timeout: float) -> FixtureResponse:
            (self.models / "a.gguf").write_bytes(b"other")
            return real_open(requested, timeout)

        opener.open = appear_then_open
        status, _, error = self.install("a", opener=opener)
        self.assertEqual(1, status)
        self.assertIn("appeared", error)
        self.assertEqual(b"other", (self.models / "a.gguf").read_bytes())

    def test_two_models_naming_one_file_differently_are_refused(self) -> None:
        """A shared name with other content could not hold both models."""
        self.write_catalog(model_node("a", file_node("x.gguf", b"one")),
                           model_node("b", file_node("x.gguf", b"two")))
        opener = FixtureOpener({})
        status, _, error = self.install("a", "b", opener=opener)
        self.assertEqual(1, status)
        self.assertIn("different content", error)
        self.assertEqual([], opener.opened)

    def test_shortage_of_disk_space_stops_before_the_download(self) -> None:
        """The whole command's size is compared with the free space first."""
        opener, _ = self.single(b"weights")
        usage = shutil.disk_usage(self.models)._replace(free=6)
        with mock.patch.object(model_fetch.shutil, "disk_usage", return_value=usage):
            self.assert_refused(opener, "6 bytes free")
        self.assertEqual([], opener.opened)

    def test_leftovers_of_an_earlier_run_are_removed(self) -> None:
        """A hard kill's staging directory neither blocks nor survives the next run."""
        stale = self.models / model_fetch.STAGING_DIRECTORY / "run-old"
        stale.mkdir(parents=True)
        (stale / "a.gguf.part").write_bytes(b"partial")
        opener, _ = self.single(b"weights")
        self.assertEqual(0, self.install("a", opener=opener)[0])
        self.assertEqual(["a.gguf"], self.tree())

    def test_staged_files_are_invisible_to_the_model_directory_scan(self) -> None:
        """While a download runs its file ends in `.part`, never in a model extension."""
        opener, url = self.single(b"weights")
        seen: list[str] = []
        real_replace = os.replace

        def observe(source, destination):
            seen.extend(self.tree())
            real_replace(source, destination)

        with mock.patch.object(model_fetch.os, "replace", observe):
            self.assertEqual(0, self.install("a", opener=opener)[0])
        names = [name for name in seen if Path(name).name.startswith("a.gguf")]
        self.assertEqual(1, len(names), seen)
        self.assertTrue(names[0].startswith(model_fetch.STAGING_DIRECTORY + "/"), names)
        self.assertTrue(names[0].endswith(model_fetch.PART_SUFFIX), names)

    def test_interrupt_and_stop_remove_the_unfinished_download(self) -> None:
        """Ctrl+C and `docker stop` unwind through the same cleanup."""
        for failure, expected in ((KeyboardInterrupt(), 130), (SystemExit(143), 143)):
            with self.subTest(failure=type(failure).__name__):
                opener, url = self.single(b"weights")
                opener.failures[url] = failure
                self.assertEqual(expected, self.install("a", opener=opener)[0])
                self.assertEqual([], self.tree())

    def test_only_the_catalogs_urls_are_requested(self) -> None:
        """Nothing outside the requested entries is contacted."""
        first, other = file_node("a.gguf", b"one"), file_node("b.gguf", b"two")
        self.write_catalog(model_node("a", first), model_node("b", other))
        opener = FixtureOpener({first["url"]: b"one", other["url"]: b"two"})
        self.assertEqual(0, self.install("a", opener=opener)[0])
        self.assertEqual([first["url"]], opener.opened)
        self.assertEqual(["a.gguf"], self.tree())

    def test_catalog_urls_used_here_are_reserved_names(self) -> None:
        """The fixture domain cannot resolve, so a mistake here cannot reach a real host."""
        self.assertTrue(file_node("a.gguf", b"x")["url"].startswith("https://fixtures.invalid/"))


class ConfirmationTest(Fixture):
    """The helper repeats the launcher's gate: an unconfirmed ID downloads nothing."""

    def setUp(self) -> None:
        super().setUp()
        self.node = file_node("a.gguf", b"weights")
        self.other = file_node("b.gguf", b"other")
        self.write_catalog(model_node("a", self.node), model_node("b", self.other))
        self.opener = FixtureOpener({self.node["url"]: b"weights", self.other["url"]: b"other"})

    def run_install(self, accepted: str, *identifiers: str) -> tuple[int, str, str]:
        """Run `install` with exactly this accepted list."""
        return self.run_helper("install", "--catalog", str(self.catalog), "--models-dir", str(self.models),
                               "--accepted", accepted, *identifiers, opener=self.opener)

    def test_every_requested_id_must_be_confirmed(self) -> None:
        """Another ID, an empty list, a wildcard or a partial list all exit 2 untouched."""
        for accepted, identifiers in (("b", ["a"]), ("", ["a"]), ("*", ["a"]), ("all", ["a"]),
                                      ("a", ["a", "b"]), ("A", ["a"]), ("a.gguf", ["a"])):
            with self.subTest(accepted=accepted, identifiers=identifiers):
                status, _, error = self.run_install(accepted, *identifiers)
                self.assertEqual(2, status)
                self.assertIn("no confirmation", error)
                self.assertEqual([], self.opener.opened)
                self.assertEqual([], self.tree())

    def test_missing_confirmation_argument_is_a_usage_error(self) -> None:
        """`install` has no form that works without a confirmation."""
        status, _, _ = self.run_helper("install", "--catalog", str(self.catalog),
                                       "--models-dir", str(self.models), "a", opener=self.opener)
        self.assertEqual(2, status)
        self.assertEqual([], self.opener.opened)

    def test_unknown_id_is_a_usage_error_even_when_confirmed(self) -> None:
        """A confirmation cannot make an ID exist."""
        self.assertEqual(2, self.run_install("missing", "missing")[0])
        self.assertEqual([], self.opener.opened)

    def test_named_confirmation_installs(self) -> None:
        """The exact list is enough."""
        self.assertEqual(0, self.run_install("a,b", "b", "a")[0])
        self.assertEqual(["a.gguf", "b.gguf"], self.tree())


class RedirectTest(unittest.TestCase):
    """The production opener follows HTTPS only and has no fixture setting."""

    def request_to(self, target: str) -> urllib.request.Request | None:
        """Ask the handler about one redirect from an HTTPS address."""
        handler = model_fetch.HttpsOnlyRedirects()
        original = urllib.request.Request("https://fixtures.invalid/a.gguf")
        return handler.redirect_request(original, None, 302, "Found", {}, target)

    def test_https_redirects_may_change_host(self) -> None:
        """Publishers serve from storage hosts; the digest decides identity."""
        self.assertEqual("https://storage.fixtures.invalid/x",
                         self.request_to("https://storage.fixtures.invalid/x").full_url)

    def test_every_other_scheme_is_refused(self) -> None:
        """A downgrade or a local file is a failed fetch."""
        for target in ("http://fixtures.invalid/x", "file:///etc/passwd", "ftp://fixtures.invalid/x"):
            with self.subTest(target), self.assertRaises(model_fetch.FetchError):
                self.request_to(target)

    def test_the_default_opener_uses_the_handler(self) -> None:
        """The default path carries the refusal and a neutral user agent."""
        opener = model_fetch.default_opener()
        self.assertTrue(any(isinstance(handler, model_fetch.HttpsOnlyRedirects) for handler in opener.handlers))
        self.assertEqual([("User-Agent", model_fetch.USER_AGENT)], opener.addheaders)

    def test_no_environment_setting_or_argument_replaces_the_catalog_or_transport(self) -> None:
        """The fixture seam is `main`'s parameter: the source reads no setting of its own."""
        source = HELPER.read_text(encoding="utf-8")
        self.assertNotIn("os.environ", source)
        self.assertNotIn("getenv", source)
        self.assertNotIn("--opener", source)
        self.assertNotIn("--url", source)


class SignallingResponse(FixtureResponse):
    """A response that runs `deliver` once the body is used up, in the middle of the transfer."""

    def __init__(self, content: bytes, deliver) -> None:
        super().__init__(content, None, None)
        self.deliver = deliver

    def read(self, size: int = -1) -> bytes:
        """Return bytes; an empty read calls `deliver` first, whose signal handler may raise."""
        chunk = super().read(size)
        if not chunk:
            self.deliver()
        return chunk


class StopSignalTest(Fixture):
    """A delivered SIGTERM reaches the cleanup; `RealContainerTest` sends a real `docker stop`."""

    def test_sigterm_during_a_download_removes_the_unfinished_file(self) -> None:
        """`main` installs the handler, so the signal exits 143 with the staging directory gone.

        `raise_signal` delivers the signal on every host, while another process's SIGTERM would
        end a Windows process without running any handler. The handler set here stops the test
        with a failure, rather than killing the test process, when `main` installs none.
        """
        def no_handler(_signal_number: int, _frame: object) -> None:
            raise AssertionError("main installed no SIGTERM handler")

        self.addCleanup(signal.signal, signal.SIGTERM, signal.signal(signal.SIGTERM, no_handler))
        node = file_node("a.gguf", b"weights")
        self.write_catalog(model_node("a", node))
        unfinished: list[Path] = []

        def deliver() -> None:
            unfinished.extend(self.models.rglob("*" + model_fetch.PART_SUFFIX))
            signal.raise_signal(signal.SIGTERM)

        opener = FixtureOpener({})
        opener.open = lambda _url, timeout: SignallingResponse(b"wei", deliver)
        status, _, _ = self.install("a", opener=opener)
        self.assertEqual(143, status)
        self.assertEqual(1, len(unfinished), "the signal arrived before a file was being written")
        self.assertEqual([], self.tree())


if __name__ == "__main__":
    unittest.main()
