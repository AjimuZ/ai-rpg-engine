"""Relay the engine's calls to hosted APIs and custom endpoints under the owner's allowlist and keys.

Runs as the Compose service `gateway` (docs/34-inference-backends-and-model-selection.md
section 12). The engine calls `GET|POST /upstream/<destination id>/<path>`; `GET /healthz`
reports only that the process serves.

Guarantees:

- only a destination of the owner's file is ever contacted, chosen by its identifier: a
  request cannot name a host. A path must fall under that destination's prefixes;
- a key is read once from its file, kept in memory and sent only to its destination's own
  origin. It is never logged, never returned and never placed in an argument or variable;
- a response that holds a loaded key, whole or percent- or base64-encoded, is refused;
- only `Content-Type` and `Accept` pass from the engine, and only `Content-Type` and
  `Retry-After` pass back, so cookies and credentials cross in neither direction. No cookie
  is stored. The body of a non-2xx answer is discarded, because providers echo key fragments
  in their errors: the engine sees the status and the fixed code `upstream-status`;
- request size, response size, wall-clock time, redirect count and requests in flight are
  all bounded. A response is buffered whole, so an over-limit answer is a clean refusal and
  never a truncated success. Nothing is streamed to the engine;
- redirects stay on the same scheme, host and port and under the allowed prefixes. A POST
  follows only 307 and 308, which keep its method and body;
- every refusal carries one fixed code. Log lines hold fixed codes, an allowlisted
  identifier and counts: never a path, query, header, body, peer text or exception message;
- connections arriving over the egress network are closed unanswered, so only the private
  network shared with the engine reaches the keys.

Limits: name resolution follows the system resolver's own time limit, and a hostname is
trusted to mean what the owner allowlisted, so DNS rebinding is not defended against. A call
whose deadline passes during connection setup is answered once setup ends, so the answer can
come late by the time left, at most `CONNECT_SECONDS`, for each TCP attempt and the TLS
handshake. Nothing is sent to the destination after the deadline.

Exit status: 0 stopped by SIGTERM or a passing `--health`; 1 a failing `--health`; 78 a
refused configuration, named by JSON location and never by value.

The standard library is the only dependency, because the pinned image adds nothing else.
"""

import argparse
import base64
from dataclasses import dataclass
import http.client
import http.server
import ipaddress
import json
from pathlib import Path
import re
import signal
import socket
import ssl
import struct
import sys
import threading
import time
from typing import NoReturn, TextIO
import urllib.parse


SCHEMA = "rpg-engine.gateway-destinations/1"
DESTINATIONS_FILE = "/etc/gateway/destinations.json"
KEYS_DIRECTORY = "/run/secrets/gateway"
ROUTE_TABLE = "/proc/net/route"
LISTEN_PORT = 8080

MEBIBYTE = 1024 * 1024
DEFAULT_MAX_REQUEST_BYTES = 4 * MEBIBYTE
DEFAULT_MAX_RESPONSE_BYTES = 16 * MEBIBYTE
# Generated images arrive base64-encoded in one JSON document, hence the high ceiling.
MAX_BODY_BYTES = 64 * MEBIBYTE
DEFAULT_TIMEOUT_SECONDS = 120.0
MAX_TIMEOUT_SECONDS = 900.0
DEFAULT_MAX_REDIRECTS = 3
MAX_REDIRECTS = 5
DEFAULT_MAX_IN_FLIGHT = 16
MAX_IN_FLIGHT = 64
DEFAULT_CLIENT_TIMEOUT_SECONDS = 30.0
MAX_CLIENT_TIMEOUT_SECONDS = 300.0
MIN_DURATION_SECONDS = 0.1
CONNECT_SECONDS = 10.0
HEALTH_SECONDS = 2.0
CHUNK_BYTES = 64 * 1024
# Reading a refused body briefly lets the refusal arrive instead of a connection reset.
DRAIN_BYTES = MEBIBYTE
DRAIN_SECONDS = 1.0
MAX_TARGET_CHARACTERS = 2048
MAX_KEY_BYTES = 8192
MAX_DESTINATIONS = 64
MAX_PREFIXES = 32
MAX_FIXED_HEADERS = 16

HEALTH_FAILED_STATUS = 1
CONFIG_STATUS = 78

IDENTIFIER_RULE = re.compile(r"[a-z0-9][a-z0-9-]{0,62}")
# No path separator can pass: the name becomes a path below the keys directory.
KEY_FILE_RULE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
# A short key would match unrelated bytes in the response scan; anything outside visible
# ASCII could inject a header.
KEY_RULE = re.compile(rb"[\x21-\x7e]{8,8192}")
HEADER_NAME_RULE = re.compile(r"[A-Za-z0-9-]{1,64}")
HEADER_VALUE_RULE = re.compile(r"[\x20-\x7e]{0,1024}")
# urlsplit silently drops tabs and line breaks and browsers read a backslash as a slash, so
# a URL holding any of them never reaches the parser.
URL_RULE = re.compile(r"[\x21-\x5b\x5d-\x7e]{1,2048}")
PATH_RULE = re.compile(r"/(?:[A-Za-z0-9._~!$&'()*+,;=:@/-]|%[0-9A-Fa-f]{2})*")
QUERY_RULE = re.compile(r"(?:[A-Za-z0-9._~!$&'()*+,;=:@/?-]|%[0-9A-Fa-f]{2})*")
# Encoded separators, dots, NUL and a second encoding layer could mean another path upstream.
ENCODED_SEPARATOR_RULE = re.compile(r"%(?:2[EeFf]|5[Cc]|00|25)")
TARGET_RULE = re.compile(r"/upstream/([^/?#]*)(/[^?#]*)?(?:\?([^#]*))?")
CONTENT_LENGTH_RULE = re.compile(r"[0-9]{1,15}")
FORWARDED_VALUE_RULE = re.compile(r"[\x20-\x7e]{1,256}")
CONTENT_TYPE_RULE = re.compile(r"[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64}"
                               r"(?: *; *[A-Za-z0-9_.-]{1,32}=[A-Za-z0-9_.\"-]{1,64}){0,4}")
RETRY_AFTER_RULE = re.compile(r"[0-9]{1,10}|[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT")

FORWARDED_REQUEST_HEADERS = ("content-type", "accept")
# These never come from the destinations file: the gateway owns them or they carry credentials.
CONNECTION_HEADERS = frozenset({"host", "content-length", "transfer-encoding", "connection", "te", "trailer",
                                "upgrade", "keep-alive", "accept-encoding", "proxy-connection", "proxy-authenticate",
                                "cookie", "cookie2", "set-cookie", "set-cookie2"})
CREDENTIAL_HEADERS = frozenset({"authorization", "proxy-authorization", "x-api-key", "api-key"})
DEFAULT_PORTS = {"http": 80, "https": 443}
REDIRECT_STATUSES = frozenset({301, 302, 303, 307, 308})
METHOD_KEEPING_REDIRECTS = frozenset({307, 308})
USER_AGENT = "rpg-engine-gateway"
DEFAULT_CONTENT_TYPE = "application/octet-stream"


class ConfigError(Exception):
    """The destinations file cannot be trusted; the message names a JSON location, never a value."""


class Refusal(Exception):
    """One request ends with this status and fixed code; nothing from any peer is attached."""

    def __init__(self, status: int, code: str) -> None:
        super().__init__(code)
        self.status = status
        self.code = code


@dataclass(frozen=True)
class KeySpec:
    """Where one key lives and how it is presented; the value itself is never stored here."""

    file: str
    header: str
    prefix: str


@dataclass(frozen=True)
class Destination:
    """One allowlisted origin with its path prefixes, fixed headers and bounds."""

    identifier: str
    scheme: str
    host: str
    port: int
    prefixes: tuple[str, ...]
    key: KeySpec | None
    headers: tuple[tuple[str, str], ...]
    max_request_bytes: int
    max_response_bytes: int
    timeout_seconds: float
    max_redirects: int

    @property
    def origin(self) -> tuple[str, str, int]:
        """Return the scheme, host and port that decide whether a redirect may be followed."""
        return self.scheme, self.host, self.port


@dataclass(frozen=True)
class Settings:
    """The validated destinations file."""

    destinations: dict[str, Destination]
    max_in_flight: int
    client_timeout_seconds: float


@dataclass(frozen=True)
class Answer:
    """What the engine receives for one relayed call, and how it is logged."""

    status: int
    payload: bytes
    content_type: str
    retry_after: str | None
    code: str
    hops: int


class EventLog:
    """Write one JSON line per event; callers pass only fixed codes, identifiers and counts."""

    def __init__(self, stream: TextIO) -> None:
        self.stream = stream
        self.lock = threading.Lock()

    def write(self, event: str, **fields: object) -> None:
        """Write one event; a closed stream must not fail the request being served."""
        line = json.dumps({"time": round(time.time(), 3), "event": event, **fields}, separators=(",", ":"))
        with self.lock:
            try:
                self.stream.write(line + "\n")
                self.stream.flush()
            except (OSError, ValueError):
                pass


def origin_of(url: str) -> tuple[str, str, int]:
    """Return the scheme, host and port of an absolute URL.

    Raises ValueError for a URL with unsafe characters, another scheme, no host, credentials
    or an invalid port. The message of that error may hold the URL, so it is never shown.
    """
    if not URL_RULE.fullmatch(url):
        raise ValueError("unsafe URL")
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in DEFAULT_PORTS or not parts.hostname or parts.username is not None or parts.password is not None:
        raise ValueError("unsupported URL")
    return parts.scheme, parts.hostname, parts.port or DEFAULT_PORTS[parts.scheme]


def path_is_safe(path: str) -> bool:
    """Return whether a path has one meaning: no dot segment, empty segment or encoded separator."""
    if len(path) > MAX_TARGET_CHARACTERS or not PATH_RULE.fullmatch(path) or ENCODED_SEPARATOR_RULE.search(path):
        return False
    segments = path.split("/")[1:]
    return "" not in segments[:-1] and "." not in segments and ".." not in segments


def path_allowed(destination: Destination, path: str) -> bool:
    """Return whether a safe path falls under a prefix, matching whole segments only."""
    return path_is_safe(path) and any(path == prefix or path.startswith(prefix.rstrip("/") + "/")
                                      for prefix in destination.prefixes)


def refuse_repeated_fields(pairs: list[tuple[str, object]]) -> dict[str, object]:
    """Build a JSON object, refusing a repeated field that could hide an allowlist entry."""
    node = dict(pairs)
    if len(node) != len(pairs):
        raise ConfigError("the destinations file repeats a field")
    return node


def object_at(node: object, where: str, required: set[str], optional: set[str]) -> dict:
    """Return `node` as an object holding exactly the allowed fields, or refuse it."""
    if not isinstance(node, dict):
        raise ConfigError(f"{where} must be an object")
    missing = required - set(node)
    if missing:
        raise ConfigError(f"{where} lacks {sorted(missing)[0]}")
    if set(node) - required - optional:
        raise ConfigError(f"{where} holds an unknown field")
    return node


def text_at(node: dict, field: str, where: str, rule: re.Pattern, default: str | None = None) -> str:
    """Return a string field that matches `rule` whole, or its default when absent."""
    value = node.get(field, default)
    if not isinstance(value, str) or not rule.fullmatch(value):
        raise ConfigError(f"{where}.{field} is invalid")
    return value


def number_at(node: dict, field: str, where: str, default: int | float, low: int | float, high: int | float,
              integer: bool) -> int | float:
    """Return a number field within its inclusive bounds; a boolean never counts as a number."""
    value = node.get(field, default)
    kinds = int if integer else (int, float)
    if isinstance(value, bool) or not isinstance(value, kinds) or not low <= value <= high:
        raise ConfigError(f"{where}.{field} must be a number from {low} to {high}")
    return value


def parse_key(node: object, where: str) -> KeySpec:
    """Return a key's file and presentation; the header may carry credentials, others may not."""
    node = object_at(node, where, {"file", "header"}, {"prefix"})
    header = text_at(node, "header", where, HEADER_NAME_RULE)
    if header.lower() in CONNECTION_HEADERS:
        raise ConfigError(f"{where}.header is reserved")
    return KeySpec(text_at(node, "file", where, KEY_FILE_RULE), header,
                   text_at(node, "prefix", where, HEADER_VALUE_RULE, ""))


def parse_fixed_headers(node: object, where: str, key: KeySpec | None) -> tuple[tuple[str, str], ...]:
    """Return non-secret fixed headers; a credential or connection header is refused."""
    if not isinstance(node, dict) or len(node) > MAX_FIXED_HEADERS:
        raise ConfigError(f"{where} must be an object of at most {MAX_FIXED_HEADERS} headers")
    reserved = CONNECTION_HEADERS | CREDENTIAL_HEADERS | set(FORWARDED_REQUEST_HEADERS)
    if key is not None:
        reserved |= {key.header.lower()}
    headers = []
    for index, (name, value) in enumerate(node.items()):
        if not HEADER_NAME_RULE.fullmatch(name) or name.lower() in reserved:
            raise ConfigError(f"{where}[{index}] has a reserved or invalid name")
        if not isinstance(value, str) or not HEADER_VALUE_RULE.fullmatch(value):
            raise ConfigError(f"{where}[{index}] has an invalid value")
        headers.append((name, value))
    return tuple(headers)


def parse_prefixes(node: object, where: str) -> tuple[str, ...]:
    """Return the path prefixes; an empty list is refused, so `/` is an explicit choice."""
    if not isinstance(node, list) or not 1 <= len(node) <= MAX_PREFIXES:
        raise ConfigError(f"{where} must list from 1 to {MAX_PREFIXES} prefixes")
    for index, prefix in enumerate(node):
        if not isinstance(prefix, str) or not path_is_safe(prefix) or (prefix != "/" and prefix.endswith("/")):
            raise ConfigError(f"{where}[{index}] is invalid")
    return tuple(node)


def parse_destination(node: object, where: str) -> Destination:
    """Return one destination, or refuse it with its JSON location."""
    node = object_at(node, where, {"id", "url", "path_prefixes"},
                     {"key", "plaintext_key", "headers", "max_request_bytes", "max_response_bytes",
                      "timeout_seconds", "max_redirects"})
    url = node["url"]
    try:
        if not isinstance(url, str):
            raise ValueError("not text")
        scheme, host, port = origin_of(url)
    except ValueError:
        raise ConfigError(f"{where}.url must be an http or https origin without credentials") from None
    parts = urllib.parse.urlsplit(url)
    if parts.path not in ("", "/") or parts.query or parts.fragment:
        raise ConfigError(f"{where}.url must be an origin: paths belong to path_prefixes")
    key = parse_key(node["key"], f"{where}.key") if "key" in node else None
    plaintext_key = node.get("plaintext_key", False)
    if not isinstance(plaintext_key, bool):
        raise ConfigError(f"{where}.plaintext_key must be true or false")
    # A key leaves in clear text only through the owner's explicit choice, for a LAN server.
    if key is not None and scheme != "https" and not plaintext_key:
        raise ConfigError(f"{where}.key needs an https url, or plaintext_key for an owner-run server")
    return Destination(
        text_at(node, "id", where, IDENTIFIER_RULE), scheme, host, port,
        parse_prefixes(node["path_prefixes"], f"{where}.path_prefixes"), key,
        parse_fixed_headers(node.get("headers", {}), f"{where}.headers", key),
        number_at(node, "max_request_bytes", where, DEFAULT_MAX_REQUEST_BYTES, 1, MAX_BODY_BYTES, True),
        number_at(node, "max_response_bytes", where, DEFAULT_MAX_RESPONSE_BYTES, 1, MAX_BODY_BYTES, True),
        float(number_at(node, "timeout_seconds", where, DEFAULT_TIMEOUT_SECONDS, MIN_DURATION_SECONDS,
                        MAX_TIMEOUT_SECONDS, False)),
        number_at(node, "max_redirects", where, DEFAULT_MAX_REDIRECTS, 0, MAX_REDIRECTS, True))


def parse_settings(text: str) -> Settings:
    """Return the settings of a destinations document, or raise ConfigError without any value."""
    try:
        root = json.loads(text, object_pairs_hook=refuse_repeated_fields)
    except ValueError:
        raise ConfigError("the destinations file is not valid JSON") from None
    root = object_at(root, "file", {"schema", "destinations"}, {"max_in_flight", "client_timeout_seconds"})
    if root["schema"] != SCHEMA:
        raise ConfigError("file.schema is not supported")
    nodes = root["destinations"]
    if not isinstance(nodes, list) or len(nodes) > MAX_DESTINATIONS:
        raise ConfigError(f"file.destinations must list at most {MAX_DESTINATIONS} destinations")
    destinations: dict[str, Destination] = {}
    for index, node in enumerate(nodes):
        destination = parse_destination(node, f"destinations[{index}]")
        if destination.identifier in destinations:
            raise ConfigError(f"destinations[{index}].id repeats an earlier destination")
        destinations[destination.identifier] = destination
    return Settings(destinations,
                    number_at(root, "max_in_flight", "file", DEFAULT_MAX_IN_FLIGHT, 1, MAX_IN_FLIGHT, True),
                    float(number_at(root, "client_timeout_seconds", "file", DEFAULT_CLIENT_TIMEOUT_SECONDS,
                                    MIN_DURATION_SECONDS, MAX_CLIENT_TIMEOUT_SECONDS, False)))


def load_settings(path: str | Path) -> Settings:
    """Read the destinations file; an unreadable one is a ConfigError that shows no content."""
    try:
        return parse_settings(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError):
        raise ConfigError("the destinations file cannot be read") from None


def read_key(path: Path) -> bytes:
    """Return one key without its line ending; raise ValueError when it is not a usable key."""
    if not path.is_file():
        raise ValueError("no file")
    with path.open("rb") as source:
        raw = source.read(MAX_KEY_BYTES + 3)
    key = raw.removesuffix(b"\n").removesuffix(b"\r")
    if not KEY_RULE.fullmatch(key):
        raise ValueError("unusable key")
    return key


def load_keys(settings: Settings, directory: str | Path, log: EventLog) -> dict[str, bytes]:
    """Return each destination's key by identifier; an unusable key disables only that destination."""
    keys: dict[str, bytes] = {}
    for destination in settings.destinations.values():
        if destination.key is None:
            continue
        try:
            keys[destination.identifier] = read_key(Path(directory) / destination.key.file)
        except (OSError, ValueError):
            log.write("key-unavailable", destination=destination.identifier)
    return keys


def leak_forms(key: bytes) -> tuple[bytes, ...]:
    """Return the encodings of a whole key that a reply might carry; a fragment cannot be matched."""
    return (key, urllib.parse.quote_from_bytes(key, safe="").encode("ascii"),
            base64.b64encode(key), base64.urlsafe_b64encode(key))


def tls_context() -> ssl.SSLContext:
    """Return a context that verifies the certificate and hostname against the system store."""
    context = ssl.create_default_context()
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    return context


def sever(connection: socket.socket | None, how: int = socket.SHUT_RDWR) -> None:
    """Interrupt a blocked socket from another thread; an already closed socket is fine."""
    if connection is None:
        return
    try:
        connection.shutdown(how)
    except OSError:
        pass


def read_upstream_body(response: http.client.HTTPResponse, limit: int) -> bytes:
    """Return a whole 2xx body within `limit`, or refuse it.

    A body that is compressed is refused because its size says nothing about what the engine
    would inflate. A declared length is trusted only to refuse early: the count of bytes read
    enforces the limit, including chunked bodies and bodies with no declared length.
    """
    encoding = response.headers.get("content-encoding", "identity").strip().lower()
    if encoding != "identity":
        raise Refusal(502, "response-encoded")
    if len(response.headers.get_all("content-length") or []) > 1:
        raise Refusal(502, "upstream-protocol")
    if response.length is not None and response.length > limit:
        raise Refusal(502, "response-too-large")
    chunks: list[bytes] = []
    total = 0
    while chunk := response.read1(CHUNK_BYTES):
        total += len(chunk)
        if total > limit:
            raise Refusal(502, "response-too-large")
        chunks.append(chunk)
    # A short read of a declared length ends without an error from the client library.
    if not response.chunked and response.length:
        raise Refusal(502, "upstream-protocol")
    return b"".join(chunks)


def fetch_once(destination: Destination, method: str, target: str, headers: dict[str, bytes | str],
               body: bytes | None, deadline: float, context: ssl.SSLContext) -> tuple[int, http.client.HTTPMessage, bytes]:
    """Perform one upstream exchange and return its status, headers and 2xx body, or refuse it.

    A timer severs the socket at `deadline`, because per-operation socket timeouts alone would
    let a peer that sends one byte at a time hold the request open indefinitely. It keeps its
    own reference, since the connection forgets its socket once a closing response has begun.
    While the connection is being set up there is no socket it can reach: none exists during
    the TCP connect, and TLS wrapping detaches the one it could see. Setup is limited by its own
    timeouts instead, and the deadline is checked again before anything is sent, so a key never
    leaves after the deadline. A timer firing later finds `connected` set, because that is
    assigned before the check. Only 2xx bodies are read. Exception text is dropped, as it can
    hold addresses and peer data.
    """
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise Refusal(504, "upstream-timeout")
    connect_limit = min(CONNECT_SECONDS, remaining)
    if destination.scheme == "https":
        connection = http.client.HTTPSConnection(destination.host, destination.port, timeout=connect_limit, context=context)
    else:
        connection = http.client.HTTPConnection(destination.host, destination.port, timeout=connect_limit)
    expired = threading.Event()
    connected: socket.socket | None = None
    response: http.client.HTTPResponse | None = None

    def expire() -> None:
        expired.set()
        sever(connected if connected is not None else connection.sock)

    timer = threading.Timer(remaining, expire)
    timer.daemon = True
    timer.start()
    try:
        connection.connect()
        connected = connection.sock
        remaining = deadline - time.monotonic()
        if expired.is_set() or remaining <= 0:
            raise Refusal(504, "upstream-timeout")
        connected.settimeout(remaining)
        connection.request(method, target, body=body, headers=headers)
        response = connection.getresponse()
        status = response.status
        payload = read_upstream_body(response, destination.max_response_bytes) if 200 <= status <= 299 else b""
        return status, response.headers, payload
    except Refusal:
        if expired.is_set():
            raise Refusal(504, "upstream-timeout") from None
        raise
    except ssl.SSLError:
        code, gateway_status = ("upstream-timeout", 504) if expired.is_set() else ("upstream-tls", 502)
        raise Refusal(gateway_status, code) from None
    except TimeoutError:
        raise Refusal(504, "upstream-timeout") from None
    except http.client.HTTPException:
        code, gateway_status = ("upstream-timeout", 504) if expired.is_set() else ("upstream-protocol", 502)
        raise Refusal(gateway_status, code) from None
    except OSError:
        code, gateway_status = ("upstream-timeout", 504) if expired.is_set() else ("upstream-unreachable", 502)
        raise Refusal(gateway_status, code) from None
    finally:
        timer.cancel()
        if response is not None:
            response.close()
        connection.close()


def redirect_target(destination: Destination, target: str, method: str, status: int,
                    locations: list[str]) -> str:
    """Return the next request target of a redirect, or refuse it.

    Only one origin can ever be current, so resolving against the destination is exact. A POST
    follows only redirects that keep its method and body: dropping the body silently would
    send a different request than the engine asked for.
    """
    if method == "POST" and status not in METHOD_KEEPING_REDIRECTS:
        raise Refusal(502, "redirect-method")
    if len(locations) != 1 or not URL_RULE.fullmatch(locations[0]):
        raise Refusal(502, "redirect-invalid")
    host = f"[{destination.host}]" if ":" in destination.host else destination.host
    base = f"{destination.scheme}://{host}:{destination.port}{target}"
    try:
        resolved = urllib.parse.urljoin(base, locations[0])
        origin = origin_of(resolved)
    except ValueError:
        raise Refusal(502, "redirect-invalid") from None
    if origin != destination.origin:
        raise Refusal(502, "redirect-cross-origin")
    parts = urllib.parse.urlsplit(resolved)
    if not path_allowed(destination, parts.path) or not QUERY_RULE.fullmatch(parts.query):
        raise Refusal(502, "redirect-path")
    return parts.path + ("?" + parts.query if parts.query else "")


class Gateway:
    """Hold the validated destinations and keys, and relay one call under their rules."""

    def __init__(self, settings: Settings, keys: dict[str, bytes], log: EventLog,
                 context: ssl.SSLContext | None = None) -> None:
        self.settings = settings
        self.keys = keys
        self.log = log
        self.context = context if context is not None else tls_context()
        self.leak_forms = tuple(form for key in keys.values() for form in leak_forms(key))

    def resolve(self, request_target: str) -> tuple[Destination, str]:
        """Return the destination and upstream target of a request, or refuse before any contact."""
        match = TARGET_RULE.fullmatch(request_target)
        if match is None:
            raise Refusal(404, "not-found")
        destination = self.settings.destinations.get(match.group(1))
        if destination is None:
            raise Refusal(403, "destination-not-allowed")
        path = match.group(2) or "/"
        query = match.group(3)
        if not path_allowed(destination, path) or (query is not None and (
                len(query) > MAX_TARGET_CHARACTERS or not QUERY_RULE.fullmatch(query))):
            raise Refusal(403, "path-not-allowed")
        if destination.key is not None and destination.identifier not in self.keys:
            raise Refusal(503, "key-unavailable")
        return destination, path + (f"?{query}" if query else "")

    def holds_key(self, data: bytes) -> bool:
        """Return whether a reply carries any loaded key, whichever destination it belongs to."""
        return any(form in data for form in self.leak_forms)

    def upstream_headers(self, destination: Destination, inbound: dict[str, str]) -> dict[str, bytes | str]:
        """Build the headers of one call: nothing from the engine can replace the key or fixed values."""
        headers: dict[str, bytes | str] = {"User-Agent": USER_AGENT, "Accept-Encoding": "identity", "Connection": "close"}
        headers.update(inbound)
        headers.update(destination.headers)
        if destination.key is not None:
            headers[destination.key.header] = destination.key.prefix.encode("ascii") + self.keys[destination.identifier]
        return headers

    def forward(self, destination: Destination, method: str, target: str, inbound: dict[str, str],
                body: bytes | None) -> Answer:
        """Relay one call through its bounded redirects and return what the engine may see."""
        deadline = time.monotonic() + destination.timeout_seconds
        headers = self.upstream_headers(destination, inbound)
        hops = 0
        while True:
            status, reply_headers, payload = fetch_once(destination, method, target, headers, body, deadline, self.context)
            if status not in REDIRECT_STATUSES:
                break
            if hops >= destination.max_redirects:
                raise Refusal(502, "redirect-limit")
            target = redirect_target(destination, target, method, status, reply_headers.get_all("location") or [])
            hops += 1
        if not 200 <= status <= 599:
            raise Refusal(502, "upstream-protocol")
        retry_after = reply_headers.get("retry-after", "").strip()
        retry_after = retry_after if RETRY_AFTER_RULE.fullmatch(retry_after) else None
        if not 200 <= status <= 299:
            return Answer(status, encode_code("upstream-status", status), "application/json", retry_after,
                          "upstream-status", hops)
        content_type = reply_headers.get("content-type", "").strip()
        if not CONTENT_TYPE_RULE.fullmatch(content_type):
            content_type = DEFAULT_CONTENT_TYPE
        if self.holds_key(payload) or self.holds_key(content_type.encode("ascii")):
            raise Refusal(502, "response-refused")
        return Answer(status, payload, content_type, retry_after, "ok", hops)


def encode_code(code: str, status: int | None = None) -> bytes:
    """Return the JSON body of a gateway answer: a fixed code and, for an upstream status, that status."""
    node: dict[str, object] = {"code": code}
    if status is not None:
        node["status"] = status
    return json.dumps(node, separators=(",", ":")).encode("ascii")


class RelayHandler(http.server.BaseHTTPRequestHandler):
    """Serve one connection and one request: HTTP/1.0 semantics, no keep-alive."""

    def version_string(self) -> str:
        """Name the service without the library or interpreter version."""
        return "gateway"

    def log_message(self, format: str, *args: object) -> None:
        """Drop the library's access log: it prints request lines, which hold paths and queries."""

    def setup(self) -> None:
        """Bound each socket operation, and the whole time the engine may take to send a request.

        The library reads the request head, so only a timer can bound it. It ends there: the
        body loop has its own deadline, because on some systems data arriving after a shutdown
        resets the connection and would destroy the refusal.
        """
        self.timeout = self.server.gateway.settings.client_timeout_seconds
        super().setup()
        self.body_unread = False
        self.client_deadline = time.monotonic() + self.timeout
        self.client_timer = threading.Timer(self.timeout, sever, (self.connection, socket.SHUT_RD))
        self.client_timer.daemon = True
        self.client_timer.start()

    def finish(self) -> None:
        """Stop the request timer and close; a peer that already left is not an error."""
        self.client_timer.cancel()
        try:
            super().finish()
        except OSError:
            pass

    def send_error(self, code: int, message: str | None = None, explain: str | None = None) -> None:
        """Answer a request the library refused while parsing, without reflecting any of it."""
        status, fixed = (405, "method-not-allowed") if int(code) == 501 else (int(code), "request-malformed")
        self.reply(status, encode_code(fixed), "application/json", None)
        self.server.gateway.log.write("request", status=status, code=fixed)
        self.close_connection = True

    def reply(self, status: int, payload: bytes, content_type: str, retry_after: str | None) -> None:
        """Send one complete answer; a peer that left ends the request and nothing else."""
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            if retry_after is not None:
                self.send_header("Retry-After", retry_after)
            self.end_headers()
            self.wfile.write(payload)
        except OSError:
            self.close_connection = True

    def read_body(self, destination: Destination) -> bytes | None:
        """Return the request body within its bound, or refuse it without reading what it declares."""
        if "transfer-encoding" in self.headers:
            self.body_unread = True
            raise Refusal(411, "length-required")
        lengths = self.headers.get_all("content-length") or []
        if len(lengths) > 1 or (lengths and not CONTENT_LENGTH_RULE.fullmatch(lengths[0].strip())):
            self.body_unread = True
            raise Refusal(400, "request-malformed")
        declared = int(lengths[0]) if lengths else None
        if self.command != "POST":
            if declared:
                self.body_unread = True
                raise Refusal(400, "request-malformed")
            return None
        if declared is None:
            raise Refusal(411, "length-required")
        if declared > destination.max_request_bytes:
            self.body_unread = True
            raise Refusal(413, "request-too-large")
        chunks: list[bytes] = []
        left = declared
        try:
            while left > 0:
                # Shrinking the timeout makes the bound cover the whole body, not each read.
                remaining = self.client_deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError
                self.connection.settimeout(remaining)
                chunk = self.rfile.read1(min(CHUNK_BYTES, left))
                if not chunk:
                    raise TimeoutError
                chunks.append(chunk)
                left -= len(chunk)
        except OSError:
            self.body_unread = True
            raise Refusal(408, "request-timeout") from None
        finally:
            self.connection.settimeout(self.timeout)
        return b"".join(chunks)

    def inbound_headers(self) -> dict[str, str]:
        """Return the only request headers that pass to a destination."""
        kept = {}
        for name in FORWARDED_REQUEST_HEADERS:
            value = self.headers.get(name)
            if value is not None and FORWARDED_VALUE_RULE.fullmatch(value):
                kept[name] = value
        return kept

    def drain(self) -> None:
        """Read part of a refused body after answering, so that closing does not destroy the answer.

        This reads the socket itself: a buffered reader refuses all further reads after a timeout.
        """
        try:
            sever(self.connection, socket.SHUT_WR)
            self.connection.settimeout(DRAIN_SECONDS)
            left = DRAIN_BYTES
            while left > 0 and (chunk := self.connection.recv(min(CHUNK_BYTES, left))):
                left -= len(chunk)
        except OSError:
            pass

    def relay(self) -> None:
        """Serve one parsed request and write one log line of fixed codes and counts.

        The log line is written before the answer, so an answered call is always on record.
        """
        self.close_connection = True
        self.client_timer.cancel()
        if self.path == "/healthz" and self.command == "GET":
            self.reply(200, b'{"status":"ok"}', "application/json", None)
            return
        gateway = self.server.gateway
        started = time.monotonic()
        fields: dict[str, object] = {"method": self.command}
        request_bytes = 0
        holding_slot = False
        try:
            try:
                destination, target = gateway.resolve(self.path)
                fields["destination"] = destination.identifier
                holding_slot = self.server.slots.acquire(blocking=False)
                if not holding_slot:
                    self.body_unread = True
                    raise Refusal(503, "gateway-busy")
                body = self.read_body(destination)
                request_bytes = len(body or b"")
                answer = gateway.forward(destination, self.command, target, self.inbound_headers(), body)
                fields.update(status=answer.status, code=answer.code, hops=answer.hops, response_bytes=len(answer.payload))
            except Refusal as refusal:
                answer = Answer(refusal.status, encode_code(refusal.code), "application/json", None, refusal.code, 0)
                fields.update(status=refusal.status, code=refusal.code)
            except Exception as failure:
                # The type is the only safe part of an unexpected failure: its text may hold peer data.
                answer = Answer(500, encode_code("gateway-internal"), "application/json", None, "gateway-internal", 0)
                fields.update(status=500, code="gateway-internal", failure=type(failure).__name__)
            gateway.log.write("request", request_bytes=request_bytes,
                              duration_ms=round((time.monotonic() - started) * 1000), **fields)
            self.reply(answer.status, answer.payload, answer.content_type, answer.retry_after)
        finally:
            # The slot covers the answer too, because the buffered body stays in memory until sent.
            if holding_slot:
                self.server.slots.release()
        if self.body_unread:
            self.drain()

    def do_GET(self) -> None:
        """Relay a GET; it carries no body."""
        self.relay()

    def do_POST(self) -> None:
        """Relay a POST with a declared length."""
        self.relay()


class GatewayServer(http.server.ThreadingHTTPServer):
    """Serve the private network; connections from a refused network are closed unanswered."""

    daemon_threads = True
    block_on_close = False
    request_queue_size = MAX_IN_FLIGHT

    def __init__(self, address: tuple[str, int], gateway: Gateway,
                 refused_peers: tuple[ipaddress.IPv4Network, ...] = ()) -> None:
        self.gateway = gateway
        self.refused_peers = refused_peers
        self.slots = threading.BoundedSemaphore(gateway.settings.max_in_flight)
        super().__init__(address, RelayHandler)

    def verify_request(self, request: socket.socket, client_address: tuple) -> bool:
        """Return whether this peer may be served; the private network is the only way in."""
        peer = ipaddress.ip_address(client_address[0])
        if any(peer in network for network in self.refused_peers):
            self.gateway.log.write("peer-refused")
            return False
        return True

    def handle_error(self, request: socket.socket, client_address: tuple) -> None:
        """Replace the library's traceback, which could print request data, with the failure's type."""
        self.gateway.log.write("connection-failed", failure=sys.exc_info()[0].__name__)


def egress_subnets(route_table: str) -> tuple[ipaddress.IPv4Network, ...]:
    """Return the subnets of the interfaces holding a default route in `/proc/net/route` text.

    The egress network is the one with a default route, while an internal network has none, so
    this tells them apart without relying on interface names or attachment order. Raises
    ValueError on a line it cannot read, so that an unreadable table refuses startup.
    """
    rows = [line.split() for line in route_table.splitlines()[1:] if line.strip()]
    egress = {row[0] for row in rows if int(row[1], 16) == 0}
    networks = []
    for row in rows:
        destination, mask = int(row[1], 16), int(row[7], 16)
        if row[0] in egress and destination != 0:
            # The kernel prints these fields in host byte order.
            address = socket.inet_ntoa(struct.pack("=L", destination))
            netmask = socket.inet_ntoa(struct.pack("=L", mask))
            networks.append(ipaddress.ip_network(f"{address}/{netmask}", strict=False))
    return tuple(networks)


def health(port: int) -> int:
    """Return 0 when the local listener answers its health path, whatever the destinations' state."""
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=HEALTH_SECONDS)
    try:
        connection.request("GET", "/healthz")
        return 0 if connection.getresponse().status == 200 else HEALTH_FAILED_STATUS
    except (OSError, http.client.HTTPException):
        return HEALTH_FAILED_STATUS
    finally:
        connection.close()


def stop_on_signal(_signal: int, _frame: object) -> NoReturn:
    """End the serving loop: as PID 1 this process would otherwise ignore SIGTERM."""
    raise SystemExit(0)


def main(arguments: list[str] | None = None) -> int:
    """Start serving, or return the exit status documented in the module header."""
    parser = argparse.ArgumentParser(description="Relay engine calls to allowlisted destinations.")
    parser.add_argument("--config", default=DESTINATIONS_FILE)
    parser.add_argument("--keys-dir", default=KEYS_DIRECTORY)
    parser.add_argument("--port", type=int, default=LISTEN_PORT)
    parser.add_argument("--health", action="store_true")
    options = parser.parse_args(arguments)
    if options.health:
        return health(options.port)
    log = EventLog(sys.stderr)
    try:
        settings = load_settings(options.config)
        refused_peers = egress_subnets(Path(ROUTE_TABLE).read_text(encoding="ascii"))
    except ConfigError as refusal:
        log.write("startup-refused", reason=str(refusal))
        return CONFIG_STATUS
    except (OSError, ValueError, IndexError):
        log.write("startup-refused", reason="the route table cannot be read")
        return CONFIG_STATUS
    keys = load_keys(settings, options.keys_dir, log)
    signal.signal(signal.SIGTERM, stop_on_signal)
    with GatewayServer(("0.0.0.0", options.port), Gateway(settings, keys, log), refused_peers) as server:
        log.write("started", destinations=len(settings.destinations), keys=len(keys))
        server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
