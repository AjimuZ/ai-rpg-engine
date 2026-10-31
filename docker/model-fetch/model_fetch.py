"""Show catalog licenses and install hash-verified model files inside the one-off fetch container.

`start.sh --models fetch` runs this file through the Compose services `models-license`
(no network) and `models-fetch` (egress, writable model directory); see
docs/34-inference-backends-and-model-selection.md section 5. The owner's confirmation is
asked on the host, because the container has no terminal.

Commands:

    describe --catalog FILE ID...
    install --catalog FILE --models-dir DIR --accepted ID[,ID...] ID...

Exit status: 0 success; 1 a malformed catalog, or a download, verification or filesystem
failure; 2 a usage error, an unknown model ID or an ID without accepted confirmation; 130
and 143 an interrupted or stopped run.

Guarantees of `install`:

- every requested file is downloaded and verified, by exact size and SHA-256, before the
  first file is installed, so any failure installs nothing from that command;
- an existing file is never overwritten: identical content is reused, other content fails
  the command before any download;
- unfinished downloads stay in `.partial/`, end in `.part` so that the model server's
  directory scan ignores them, and are removed on failure and on a stop signal. A hard kill
  can leave them behind; the next `install` removes them. That also fails a concurrent
  `install` on the same directory, which then installs nothing;
- only HTTPS URLs and HTTPS redirects are followed. Redirects may change host, because the
  publishers serve files from their storage hosts and the hash decides the file's identity.

The standard library is the only dependency, because the pinned image adds nothing else.
"""

import argparse
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import signal
import sys
import tempfile
from typing import NoReturn
import urllib.error
import urllib.parse
import urllib.request


SCHEMA = "rpg-engine.model-catalog/1"
# Same rules as org.rpgengine.inference.catalog.ModelCatalog, which remains the strict parser.
ID_RULE = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")
# No path separator can pass: the name becomes a path below the model directory.
FILE_NAME_RULE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,159}")
SHA256_RULE = re.compile(r"[0-9a-f]{64}")
EXTENSIONS = {"gguf": ".gguf", "safetensors": ".safetensors"}
STAGING_DIRECTORY = ".partial"
PART_SUFFIX = ".part"
CHUNK_BYTES = 1024 * 1024
PROGRESS_BYTES = 1024 * 1024 * 1024
SOCKET_TIMEOUT_SECONDS = 60
USER_AGENT = "rpg-engine-model-fetch"
# The server's user 65534 must be able to read what this command's user installs.
INSTALLED_MODE = 0o644

USAGE_STATUS = 2
FAILURE_STATUS = 1
INTERRUPTED_STATUS = 130
STOPPED_STATUS = 143


class CatalogError(Exception):
    """The catalog cannot be trusted as input; the message names the JSON location."""


class RequestError(Exception):
    """The request names an unknown model or lacks confirmation; nothing was attempted."""


class FetchError(Exception):
    """A download, verification or filesystem step failed; nothing was installed."""


@dataclass(frozen=True)
class ModelFile:
    """One catalog file: a plain name, an HTTPS URL, its exact size and digest."""

    name: str
    url: str
    size: int
    sha256: str
    role: str


@dataclass(frozen=True)
class Model:
    """The catalog facts needed to show a license and to install the files."""

    identifier: str
    display_name: str
    page: str
    license_id: str
    license_class: str
    license_url: str
    license_basis: str
    attribution: str
    content_notes: tuple[str, ...]
    files: tuple[ModelFile, ...]


def text_field(node: dict, name: str, location: str) -> str:
    """Return the non-empty string `name`, or raise CatalogError naming its location."""
    value = node.get(name)
    if not isinstance(value, str) or not value:
        raise CatalogError(f"{location}/{name} must be a non-empty string")
    return value


def https_field(node: dict, name: str, location: str) -> str:
    """Return the HTTPS URL `name`; any other scheme is a CatalogError."""
    value = text_field(node, name, location)
    parts = urllib.parse.urlsplit(value)
    if parts.scheme != "https" or not parts.hostname:
        raise CatalogError(f"{location}/{name} must be an HTTPS URL")
    return value


def parse_file(node: object, extension: str, location: str) -> ModelFile:
    """Return one validated file; its name, URL, size and digest are all required."""
    if not isinstance(node, dict):
        raise CatalogError(f"{location} must be an object")
    name = text_field(node, "name", location)
    if not FILE_NAME_RULE.fullmatch(name) or not name.endswith(extension):
        raise CatalogError(f"{location}/name must be a plain file name ending in {extension}")
    size = node.get("bytes")
    if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
        raise CatalogError(f"{location}/bytes must be a positive integer")
    digest = text_field(node, "sha256", location)
    if not SHA256_RULE.fullmatch(digest):
        raise CatalogError(f"{location}/sha256 must be 64 lower-case hexadecimal characters")
    return ModelFile(name, https_field(node, "url", location), size, digest,
                     text_field(node, "role", location))


def parse_model(node: object, location: str) -> Model:
    """Return one validated entry with at least one file."""
    if not isinstance(node, dict):
        raise CatalogError(f"{location} must be an object")
    identifier = text_field(node, "id", location)
    if not ID_RULE.fullmatch(identifier):
        raise CatalogError(f"{location}/id is not a valid model ID")
    extension = EXTENSIONS.get(node.get("format"))
    if extension is None:
        raise CatalogError(f"{location}/format must be gguf or safetensors")
    source, license_node, files = node.get("source"), node.get("license"), node.get("files")
    if not isinstance(source, dict) or not isinstance(license_node, dict):
        raise CatalogError(f"{location} needs source and license objects")
    if not isinstance(files, list) or not files:
        raise CatalogError(f"{location}/files must be a non-empty array")
    notes = node.get("contentNotes")
    if not isinstance(notes, list) or not all(isinstance(note, str) for note in notes):
        raise CatalogError(f"{location}/contentNotes must be an array of strings")
    return Model(identifier, text_field(node, "displayName", location),
                 https_field(source, "page", f"{location}/source"),
                 text_field(license_node, "id", f"{location}/license"),
                 text_field(license_node, "class", f"{location}/license"),
                 https_field(license_node, "url", f"{location}/license"),
                 text_field(license_node, "basis", f"{location}/license"),
                 text_field(node, "attribution", location), tuple(notes),
                 tuple(parse_file(item, extension, f"{location}/files/{index}")
                       for index, item in enumerate(files)))


def load_catalog(path: Path) -> dict[str, Model]:
    """Return the catalog's models by ID, in catalog order.

    Raises CatalogError for an unreadable file, another schema, a duplicate ID or any field
    this command relies on. Other fields stay the business of the backend's strict parser.
    """
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as failure:
        raise CatalogError(f"cannot read the catalog: {failure}") from failure
    if not isinstance(document, dict) or document.get("schema") != SCHEMA:
        raise CatalogError(f"/schema must be {SCHEMA}")
    entries = document.get("entries")
    if not isinstance(entries, list):
        raise CatalogError("/entries must be an array")
    models: dict[str, Model] = {}
    for index, node in enumerate(entries):
        model = parse_model(node, f"/entries/{index}")
        if model.identifier in models:
            raise CatalogError(f"/entries/{index}/id repeats {model.identifier}")
        models[model.identifier] = model
    return models


def select_models(catalog: dict[str, Model], identifiers: Iterable[str]) -> list[Model]:
    """Return the named models once each, in request order; an unknown ID is a RequestError."""
    selected: dict[str, Model] = {}
    for identifier in identifiers:
        if identifier not in catalog:
            raise RequestError(f"unknown model ID '{identifier}'. Catalog IDs: {', '.join(catalog)}")
        selected[identifier] = catalog[identifier]
    if not selected:
        raise RequestError("name at least one model ID")
    return list(selected.values())


def describe(models: Iterable[Model]) -> str:
    """Return what the owner must read before accepting: license, source, files and notes."""
    lines: list[str] = []
    for model in models:
        total = sum(item.size for item in model.files)
        lines += [f"Model {model.identifier}: {model.display_name}",
                  f"  License: {model.license_id} (class {model.license_class}, basis {model.license_basis})",
                  f"  License terms: {model.license_url}",
                  f"  Source: {model.page}",
                  f"  Attribution: {model.attribution}"]
        if model.content_notes:
            lines.append(f"  Content notes: {', '.join(model.content_notes)}")
        lines.append(f"  Download: {total} bytes ({total / PROGRESS_BYTES:.2f} GiB)")
        for item in model.files:
            lines += [f"  File ({item.role}): {item.name}, {item.size} bytes",
                      f"    URL: {item.url}",
                      f"    SHA-256: {item.sha256}"]
    return "\n".join(lines)


class HttpsOnlyRedirects(urllib.request.HTTPRedirectHandler):
    """Follow redirects only to HTTPS; a downgrade or a file URL raises FetchError."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """Return the next request, after refusing every scheme except HTTPS."""
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise FetchError(f"refused a redirect from {req.full_url} to a non-HTTPS address")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def default_opener() -> urllib.request.OpenerDirector:
    """Return the production opener. Tests pass their own to `main`; no setting replaces it."""
    opener = urllib.request.build_opener(HttpsOnlyRedirects())
    opener.addheaders = [("User-Agent", USER_AGENT)]
    return opener


def file_sha256(path: Path) -> str:
    """Return the lower-case SHA-256 of a file's content, read in bounded chunks."""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(CHUNK_BYTES):
            digest.update(chunk)
    return digest.hexdigest()


def is_installed(item: ModelFile, destination: Path) -> bool:
    """Return whether `destination` already holds exactly this file.

    False when nothing is there. A symbolic link, another size or another digest raises
    FetchError, because the command never replaces something it did not verify.
    """
    if not os.path.lexists(destination):
        return False
    if (destination.is_file() and not destination.is_symlink()
            and destination.stat().st_size == item.size and file_sha256(destination) == item.sha256):
        return True
    raise FetchError(f"{item.name} already exists in the model directory with other content; "
                     "move it away and rerun. It was left untouched.")


def planned_files(models: Iterable[Model]) -> list[ModelFile]:
    """Return each file once; two entries naming one file with other content is a FetchError."""
    planned: dict[str, ModelFile] = {}
    for model in models:
        for item in model.files:
            earlier = planned.setdefault(item.name, item)
            if (earlier.size, earlier.sha256) != (item.size, item.sha256):
                raise FetchError(f"two requested models use the file name {item.name} with different content")
    return list(planned.values())


def download_verified(item: ModelFile, stage: Path, opener: urllib.request.OpenerDirector,
                      report: Callable[[str], None]) -> Path:
    """Download one file into `stage` and return its path after exact verification.

    A declared length is checked before the first byte is written, and reading stops as soon
    as the size is exceeded, so a wrong or endless response costs no more than the catalog
    size. Raises FetchError for a wrong size or digest and for any transfer failure.
    """
    target = stage / (item.name + PART_SUFFIX)
    digest = hashlib.sha256()
    received = 0
    report(f"downloading {item.name} ({item.size} bytes)")
    try:
        with opener.open(item.url, timeout=SOCKET_TIMEOUT_SECONDS) as response:
            declared = response.headers.get("Content-Length")
            if declared is not None and declared.isdigit() and int(declared) != item.size:
                raise FetchError(f"{item.name}: size mismatch, the source declares {declared} bytes "
                                 f"and the catalog {item.size}")
            with target.open("xb") as sink:
                while chunk := response.read(CHUNK_BYTES):
                    received += len(chunk)
                    if received > item.size:
                        raise FetchError(f"{item.name}: size mismatch, more than the catalog's {item.size} bytes")
                    digest.update(chunk)
                    sink.write(chunk)
                    if received // PROGRESS_BYTES > (received - len(chunk)) // PROGRESS_BYTES:
                        report(f"  {item.name}: {received} of {item.size} bytes")
                sink.flush()
                os.fsync(sink.fileno())
    except urllib.error.HTTPError as failure:
        raise FetchError(f"{item.name}: the source answered HTTP {failure.code}; if it asks for "
                         "a sign-in, this command cannot provide one") from failure
    except (urllib.error.URLError, http.client.HTTPException, OSError) as failure:
        raise FetchError(f"{item.name}: download failed: {failure}") from failure
    if received != item.size:
        raise FetchError(f"{item.name}: size mismatch, received {received} bytes and the catalog has {item.size}")
    if digest.hexdigest() != item.sha256:
        raise FetchError(f"{item.name}: SHA-256 mismatch, received {digest.hexdigest()} "
                         f"and the catalog has {item.sha256}")
    try:
        target.chmod(INSTALLED_MODE)
    except OSError:
        # Docker Desktop's Windows shares ignore modes and may refuse the call; every
        # container reads those files anyway.
        pass
    return target


def commit(staged: Sequence[tuple[ModelFile, Path]], models_dir: Path, report: Callable[[str], None]) -> None:
    """Move verified files into place, undoing this run's moves if one of them fails.

    A file that appeared after the first check is left alone and fails the command.
    """
    installed: list[Path] = []
    try:
        for item, source in staged:
            destination = models_dir / item.name
            if os.path.lexists(destination):
                raise FetchError(f"{item.name} appeared in the model directory during this run. It was left untouched.")
            os.replace(source, destination)
            installed.append(destination)
    except BaseException:
        for destination in installed:
            destination.unlink(missing_ok=True)
        raise
    for destination in installed:
        report(f"installed {destination.name}")


def check_free_space(models_dir: Path, required: int) -> None:
    """Raise FetchError when the model directory's disk cannot hold `required` more bytes."""
    free = shutil.disk_usage(models_dir).free
    if free < required:
        raise FetchError(f"the model directory has {free} bytes free and this download needs {required}")


def install(models: Sequence[Model], models_dir: Path, opener: urllib.request.OpenerDirector,
            report: Callable[[str], None] = print) -> None:
    """Install the models' files all together, or none of them (see the module guarantees).

    Requires an existing model directory. Raises FetchError with nothing new installed.
    """
    staging = models_dir / STAGING_DIRECTORY
    try:
        pending = []
        for item in planned_files(models):
            if is_installed(item, models_dir / item.name):
                report(f"{item.name} is already installed and verified")
            else:
                pending.append(item)
        if not pending:
            return
        check_free_space(models_dir, sum(item.size for item in pending))
        if staging.exists():
            shutil.rmtree(staging)
        staging.mkdir()
        try:
            stage = Path(tempfile.mkdtemp(prefix="run-", dir=staging))
            commit([(item, download_verified(item, stage, opener, report)) for item in pending],
                   models_dir, report)
        finally:
            shutil.rmtree(staging, ignore_errors=True)
    except OSError as failure:
        raise FetchError(f"model directory failure: {failure}") from failure


def stop_on_signal(_signal_number: int, _frame: object) -> NoReturn:
    """Turn `docker stop` into a normal unwind. PID 1 would otherwise ignore SIGTERM."""
    raise SystemExit(STOPPED_STATUS)


def parse_arguments(argv: Sequence[str] | None) -> argparse.Namespace:
    """Return the parsed command line; argparse exits with status 2 on a usage error."""
    parser = argparse.ArgumentParser(prog="model_fetch", description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    shown = commands.add_parser("describe")
    shown.add_argument("--catalog", type=Path, required=True)
    shown.add_argument("identifiers", nargs="+", metavar="ID")
    fetched = commands.add_parser("install")
    fetched.add_argument("--catalog", type=Path, required=True)
    fetched.add_argument("--models-dir", type=Path, required=True)
    fetched.add_argument("--accepted", required=True, help="comma-separated confirmed model IDs")
    fetched.add_argument("identifiers", nargs="+", metavar="ID")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None, opener: urllib.request.OpenerDirector | None = None) -> int:
    """Run one command and return the documented exit status. `opener` exists for fixtures."""
    arguments = parse_arguments(argv)
    signal.signal(signal.SIGTERM, stop_on_signal)
    try:
        models = select_models(load_catalog(arguments.catalog), arguments.identifiers)
        if arguments.command == "describe":
            print(describe(models), flush=True)
            return 0
        unconfirmed = [model.identifier for model in models
                       if model.identifier not in arguments.accepted.split(",")]
        if unconfirmed:
            raise RequestError(f"no confirmation for: {', '.join(unconfirmed)}")
        install(models, arguments.models_dir, opener or default_opener(),
                lambda message: print(message, flush=True))
        return 0
    except RequestError as failure:
        print(f"model fetch: {failure}", file=sys.stderr, flush=True)
        return USAGE_STATUS
    except CatalogError as failure:
        print(f"model fetch: invalid catalog: {failure}", file=sys.stderr, flush=True)
        return FAILURE_STATUS
    except FetchError as failure:
        print(f"model fetch: {failure}", file=sys.stderr, flush=True)
        print("model fetch: nothing new was installed by this command.", file=sys.stderr, flush=True)
        return FAILURE_STATUS
    except KeyboardInterrupt:
        print("model fetch: interrupted; nothing new was installed.", file=sys.stderr, flush=True)
        return INTERRUPTED_STATUS


if __name__ == "__main__":
    sys.exit(main())
