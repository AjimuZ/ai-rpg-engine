"""Check the gateway's relay rules in process against a scripted loopback upstream.

Input is `docker/gateway/gateway.py`, served on a loopback port with a random sentinel as its
key. The upstream is a fixture that replays scripted answers and records what it received, so
these cases prove what the gateway sends, refuses and repeats, and nothing about a real
provider. Every case also asserts that the sentinel is absent from the gateway's log and from
everything the caller received, while one positive control shows that the upstream did get it.
No Docker is needed, so hosted CI runs them. TLS needs certificates and the real image, so it
is in `test_gateway_container.py`, with the networks and the image itself.
"""

import base64
from collections.abc import Callable
import contextlib
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import http.client
import http.server
import importlib.util
import io
import ipaddress
import json
from pathlib import Path
import secrets
import socket
import ssl
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock
import urllib.parse


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docker" / "gateway" / "gateway.py"
SENTINEL = "sentinel-key-" + secrets.token_hex(16)
ID = "scripted"
# Stopping a server waits for its poll, and these cases start about two servers each.
POLL_SECONDS = 0.02


def load_gateway():
    """Import the gateway by path: its directory is not a package and it must stay one file."""
    specification = importlib.util.spec_from_file_location("gateway", SOURCE)
    module = importlib.util.module_from_spec(specification)
    # Dataclasses look their module up here while the class is being created.
    sys.modules["gateway"] = module
    specification.loader.exec_module(module)
    return module


gateway = load_gateway()


@dataclass
class Seen:
    """One request as the scripted upstream received it."""

    method: str
    path: str
    headers: dict[str, str]
    body: bytes


Route = Callable[[http.server.BaseHTTPRequestHandler], None]


def answer(status: int = 200, body: bytes = b"{}", headers: tuple[tuple[str, str], ...] = ()) -> Route:
    """Return a route that sends one complete answer with an exact length."""
    def route(handler: http.server.BaseHTTPRequestHandler) -> None:
        handler.send_response(status)
        for name, value in headers:
            handler.send_header(name, value)
        handler.send_header("Content-Length", str(len(body)))
        handler.send_header("Connection", "close")
        handler.end_headers()
        handler.wfile.write(body)
    return route


def redirect(location: str, status: int = 302, headers: tuple[tuple[str, str], ...] = ()) -> Route:
    """Return a route that redirects, with an empty body."""
    return answer(status, b"", (("Location", location), *headers))


def raw(data: bytes) -> Route:
    """Return a route that writes exact bytes, for answers a library would not produce."""
    def route(handler: http.server.BaseHTTPRequestHandler) -> None:
        handler.wfile.write(data)
    return route


class ScriptedUpstream:
    """A loopback HTTP server replaying scripted routes; it is a fixture and not a provider."""

    def __init__(self, routes: dict[str, Route]) -> None:
        self.routes = routes
        self.seen: list[Seen] = []
        upstream = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, format: str, *args: object) -> None:
                """Stay quiet: the suite reads the gateway's log, not this fixture's."""

            def serve(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                upstream.seen.append(Seen(self.command, self.path, {name.lower(): value for name, value in self.headers.items()},
                                          self.rfile.read(length)))
                self.close_connection = True
                route = upstream.routes.get(self.path.partition("?")[0], answer(404, b"no route"))
                # The gateway drops a connection it gave up on, and that is the behavior under test.
                with contextlib.suppress(OSError):
                    route(self)

            def do_GET(self) -> None:
                self.serve()

            def do_POST(self) -> None:
                self.serve()

        class Server(http.server.ThreadingHTTPServer):
            daemon_threads = True
            block_on_close = False

            def handle_error(self, request: socket.socket, client_address: tuple) -> None:
                """Stay quiet about a dropped connection."""

        self.server = Server(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": POLL_SECONDS}, daemon=True)
        self.thread.start()

    @property
    def url(self) -> str:
        """Return this upstream's origin."""
        return f"http://127.0.0.1:{self.port}"

    def close(self) -> None:
        """Stop serving."""
        self.server.shutdown()
        self.server.server_close()


@dataclass
class Reply:
    """What the engine would receive from the gateway."""

    status: int
    headers: dict[str, str]
    body: bytes

    @property
    def code(self) -> str:
        """Return the gateway's fixed code."""
        return json.loads(self.body)["code"]


class GatewayCase(unittest.TestCase):
    """Run one gateway per case; its log and every reply must hold no key."""

    def setUp(self) -> None:
        self.stream = io.StringIO()
        self.log = gateway.EventLog(self.stream)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.keys = Path(directory.name)
        (self.keys / "scripted").write_text(SENTINEL + "\n", encoding="ascii", newline="\n")
        states = tempfile.TemporaryDirectory()
        self.addCleanup(states.cleanup)
        self.budget_file = Path(states.name) / "budgets.json"
        self.now = datetime(2026, 10, 15, 12, 0, tzinfo=timezone.utc)
        self.addCleanup(self.assert_log_clean)
        self.release = threading.Event()
        self.addCleanup(self.release.set)

    def assert_log_clean(self) -> None:
        """Require a parseable log with no sentinel: runs after each case, passing or not."""
        text = self.stream.getvalue()
        self.assertNotIn(SENTINEL, text)
        for line in text.splitlines():
            self.assertIsInstance(json.loads(line), dict)

    def upstream(self, routes: dict[str, Route]) -> ScriptedUpstream:
        """Start a scripted upstream for this case."""
        upstream = ScriptedUpstream(routes)
        self.addCleanup(upstream.close)
        return upstream

    def serve(self, url: str, refused_peers: tuple = (), max_in_flight: int = 16, client_timeout: float = 5.0,
              others: tuple[dict, ...] = (), **changes: object) -> "GatewayCase":
        """Start the gateway with one destination at `url`; `changes` replace its fields.

        `others` are further destinations. Serving again on the same case is a restart: a new
        gateway reads the same budget file, on the clock this case holds in `self.now`.
        """
        destination = {"id": ID, "url": url, "path_prefixes": ["/v1"], "plaintext_key": True,
                       "key": {"file": "scripted", "header": "x-api-key"}, "timeout_seconds": 5}
        destination.update(changes)
        settings = gateway.parse_settings(json.dumps(
            {"schema": gateway.SCHEMA, "max_in_flight": max_in_flight, "client_timeout_seconds": client_timeout,
             "destinations": [{name: value for name, value in destination.items() if value is not None}, *others]}))
        keys = gateway.load_keys(settings, self.keys, self.log)
        budgets = gateway.BudgetLedger(settings, self.budget_file, self.log, lambda: self.now.timestamp())
        server = gateway.GatewayServer(("127.0.0.1", 0), gateway.Gateway(settings, keys, self.log, budgets=budgets), refused_peers)
        self.address = server.server_address
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": POLL_SECONDS}, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return self

    def call(self, method: str, target: str, body: bytes | None = None, headers: dict | None = None) -> Reply:
        """Send one request as the engine would; no reply may carry the key."""
        connection = http.client.HTTPConnection(*self.address, timeout=20)
        self.addCleanup(connection.close)
        connection.request(method, target, body=body, headers=headers or {})
        response = connection.getresponse()
        reply = Reply(response.status, {name.lower(): value for name, value in response.getheaders()}, response.read())
        self.assertNotIn(SENTINEL.encode("ascii"), reply.body)
        self.assertNotIn(SENTINEL, json.dumps(reply.headers))
        return reply

    def post(self, path: str = "/v1/call", body: bytes = b"{}", headers: dict | None = None) -> Reply:
        """Relay a POST to the destination."""
        return self.call("POST", f"/upstream/{ID}{path}", body, headers)

    def get(self, path: str = "/v1/call", headers: dict | None = None) -> Reply:
        """Relay a GET to the destination."""
        return self.call("GET", f"/upstream/{ID}{path}", None, headers)

    def exchange_raw(self, data: bytes) -> bytes:
        """Send exact bytes to the gateway and return everything it answers."""
        with socket.create_connection(self.address, timeout=10) as connection:
            connection.sendall(data)
            received = b""
            while chunk := connection.recv(65536):
                received += chunk
        return received

    def events(self) -> list[dict]:
        """Return the parsed log lines."""
        return [json.loads(line) for line in self.stream.getvalue().splitlines()]


class SettingsTest(unittest.TestCase):
    """A destinations file is accepted whole or refused without echoing what it holds."""

    GOOD = {"id": "hosted", "url": "https://provider.invalid", "path_prefixes": ["/v1/messages"],
            "key": {"file": "hosted", "header": "x-api-key"}, "headers": {"anthropic-version": "2023-06-01"}}

    def document(self, **changes: object) -> str:
        """Return a document with one destination whose fields `changes` replace or remove."""
        node = {**self.GOOD, **changes}
        return json.dumps({"schema": gateway.SCHEMA,
                           "destinations": [{name: value for name, value in node.items() if value is not None}]})

    def test_a_valid_file_applies_the_documented_defaults(self) -> None:
        """Bounds that the owner leaves out take their documented values."""
        settings = gateway.parse_settings(self.document())
        destination = settings.destinations["hosted"]
        self.assertEqual(("https", "provider.invalid", 443), destination.origin)
        self.assertEqual(gateway.DEFAULT_MAX_REQUEST_BYTES, destination.max_request_bytes)
        self.assertEqual(gateway.DEFAULT_MAX_RESPONSE_BYTES, destination.max_response_bytes)
        self.assertEqual(gateway.DEFAULT_TIMEOUT_SECONDS, destination.timeout_seconds)
        self.assertEqual(gateway.DEFAULT_MAX_REDIRECTS, destination.max_redirects)
        self.assertEqual(gateway.DEFAULT_MAX_IN_FLIGHT, settings.max_in_flight)
        self.assertEqual((("anthropic-version", "2023-06-01"),), destination.headers)

    def test_a_budget_is_optional_and_holds_both_caps(self) -> None:
        """No budget means an unmetered destination; the ceiling itself is accepted."""
        self.assertIsNone(gateway.parse_settings(self.document()).destinations["hosted"].budget)
        settings = gateway.parse_settings(self.document(budget={"daily_calls": 1, "monthly_calls": gateway.MAX_BUDGET_CALLS}))
        self.assertEqual(gateway.Budget(1, gateway.MAX_BUDGET_CALLS), settings.destinations["hosted"].budget)

    def test_an_owner_server_without_a_key_may_use_plain_http(self) -> None:
        """Plain HTTP is refused only when a key would cross it unasked."""
        settings = gateway.parse_settings(self.document(url="http://forge.invalid:7860", key=None))
        self.assertEqual(("http", "forge.invalid", 7860), settings.destinations["hosted"].origin)

    def test_refusals_name_a_location_and_never_a_value(self) -> None:
        """Each unsafe field refuses the whole file, and the value never appears in the message."""
        cases = {
            "credentials in the url": self.document(url=f"https://user:{SENTINEL}@provider.invalid"),
            "a path in the url": self.document(url=f"https://provider.invalid/{SENTINEL}"),
            "a query in the url": self.document(url=f"https://provider.invalid?{SENTINEL}"),
            "another scheme": self.document(url=f"ftp://{SENTINEL}.invalid"),
            "a line break in the url": self.document(url=f"https://provider.invalid\n{SENTINEL}"),
            "a key over plain http": self.document(url=f"http://{SENTINEL}.invalid"),
            "an invalid identifier": self.document(id=f"UPPER_{SENTINEL}"),
            "a key file outside the directory": self.document(key={"file": f"../{SENTINEL}", "header": "x-api-key"}),
            "a key sent as a cookie": self.document(key={"file": "hosted", "header": "Cookie"}),
            "a key sent as the host": self.document(key={"file": "hosted", "header": "Host"}),
            "a credential in a fixed header": self.document(headers={"Authorization": SENTINEL}),
            "a cookie in a fixed header": self.document(headers={"Cookie": SENTINEL}),
            "a line break in a fixed header": self.document(headers={"X-Thing": f"a\r\n{SENTINEL}"}),
            "a fixed value for the key header": self.document(headers={"X-Api-Key": SENTINEL}),
            "no prefix": self.document(path_prefixes=[]),
            "a relative prefix": self.document(path_prefixes=[SENTINEL]),
            "a dot segment in a prefix": self.document(path_prefixes=[f"/v1/../{SENTINEL}"]),
            "an unknown field": self.document(forward_cookies=SENTINEL),
            "too many redirects": self.document(max_redirects=gateway.MAX_REDIRECTS + 1),
            "a boolean as a number": self.document(max_redirects=True),
            "a request bound over the ceiling": self.document(max_request_bytes=gateway.MAX_BODY_BYTES + 1),
            "a response bound over the ceiling": self.document(max_response_bytes=gateway.MAX_BODY_BYTES + 1),
            "no response at all": self.document(max_response_bytes=0),
            "an excessive time": self.document(timeout_seconds=gateway.MAX_TIMEOUT_SECONDS + 1),
            "a budget that is not an object": self.document(budget=SENTINEL),
            "a budget without a monthly cap": self.document(budget={"daily_calls": 1}),
            "a budget without a daily cap": self.document(budget={"monthly_calls": 1}),
            "an unknown budget field": self.document(budget={"daily_calls": 1, "monthly_calls": 1, "tokens": SENTINEL}),
            "a cap of text": self.document(budget={"daily_calls": SENTINEL, "monthly_calls": 1}),
            "a boolean cap": self.document(budget={"daily_calls": True, "monthly_calls": 1}),
            "a fractional cap": self.document(budget={"daily_calls": 1.5, "monthly_calls": 2}),
            "a cap of zero": self.document(budget={"daily_calls": 0, "monthly_calls": 1}),
            "a negative cap": self.document(budget={"daily_calls": 1, "monthly_calls": -1}),
            "a cap over the ceiling": self.document(budget={"daily_calls": 1, "monthly_calls": gateway.MAX_BUDGET_CALLS + 1}),
            "a repeated field": '{"schema":"' + gateway.SCHEMA + '","schema":"x","destinations":[]}',
            "another schema": json.dumps({"schema": SENTINEL, "destinations": []}),
            "no JSON": SENTINEL,
            "a repeated destination": json.dumps({"schema": gateway.SCHEMA, "destinations": [self.GOOD, self.GOOD]}),
        }
        for name, text in cases.items():
            with self.subTest(name), self.assertRaises(gateway.ConfigError) as caught:
                gateway.parse_settings(text)
            self.assertNotIn(SENTINEL, str(caught.exception), name)

    def test_tls_verification_stays_on(self) -> None:
        """The context verifies chains and hostnames and refuses old protocol versions."""
        context = gateway.tls_context()
        self.assertEqual(ssl.CERT_REQUIRED, context.verify_mode)
        self.assertTrue(context.check_hostname)
        self.assertEqual(ssl.TLSVersion.TLSv1_2, context.minimum_version)


class KeysTest(unittest.TestCase):
    """A key file yields a usable key, or disables only its own destination."""

    def load(self, content: bytes | None) -> tuple[dict, str]:
        """Return the keys loaded from one file holding `content`, and the log text."""
        settings = gateway.parse_settings(json.dumps({"schema": gateway.SCHEMA, "destinations": [
            {"id": "hosted", "url": "https://provider.invalid", "path_prefixes": ["/"],
             "key": {"file": "key", "header": "x-api-key"}},
            {"id": "open", "url": "http://forge.invalid", "path_prefixes": ["/"]}]}))
        stream = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            if content is not None:
                (Path(directory) / "key").write_bytes(content)
            return gateway.load_keys(settings, directory, gateway.EventLog(stream)), stream.getvalue()

    def test_a_line_ending_is_not_part_of_the_key(self) -> None:
        """Files written with either line ending, or none, give the same key."""
        for suffix in (b"", b"\n", b"\r\n"):
            self.assertEqual({"hosted": b"key-value-123"}, self.load(b"key-value-123" + suffix)[0])

    def test_an_unusable_key_is_reported_without_its_content(self) -> None:
        """Missing, empty, short, oversized, multi-line and non-ASCII files are all refused."""
        secret = SENTINEL.encode("ascii")
        for content in (None, b"", b"\n", b"short", secret + b"\n" + secret, secret + b"\xc3\xa9", b"x" * 8193,
                        b"with space in it"):
            keys, log = self.load(content)
            self.assertEqual({}, keys)
            self.assertEqual("hosted", json.loads(log)["destination"])
            self.assertNotIn(SENTINEL, log)


class ReachTest(GatewayCase):
    """Only allowlisted destinations and paths are ever contacted."""

    def test_an_allowed_post_reaches_the_upstream_with_the_key(self) -> None:
        """Positive control: the upstream gets the key, the body and the fixed headers; the caller gets the answer."""
        upstream = self.upstream({"/v1/call": answer(200, b'{"reply":1}', (("Content-Type", "application/json"),))})
        self.serve(upstream.url, headers={"X-Version": "1"})
        reply = self.post(body=b'{"prompt":2}', headers={"Content-Type": "application/json", "Accept": "application/json"})
        self.assertEqual((200, b'{"reply":1}', "application/json"), (reply.status, reply.body, reply.headers["content-type"]))
        seen = upstream.seen[0]
        self.assertEqual(("POST", "/v1/call", b'{"prompt":2}'), (seen.method, seen.path, seen.body))
        self.assertEqual(SENTINEL, seen.headers["x-api-key"])
        self.assertEqual("1", seen.headers["x-version"])
        self.assertEqual("application/json", seen.headers["content-type"])
        self.assertEqual("identity", seen.headers["accept-encoding"])
        self.assertEqual(f"127.0.0.1:{upstream.port}", seen.headers["host"])

    def test_a_bearer_key_uses_its_header_and_prefix(self) -> None:
        """The destination decides how the key is presented."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url, key={"file": "scripted", "header": "Authorization", "prefix": "Bearer "})
        self.assertEqual(200, self.get().status)
        self.assertEqual(f"Bearer {SENTINEL}", upstream.seen[0].headers["authorization"])
        self.assertNotIn("x-api-key", upstream.seen[0].headers)

    def test_a_destination_without_a_key_sends_no_credential(self) -> None:
        """An owner's open server receives no key at all."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url, key=None)
        self.assertEqual(200, self.get(headers={"Authorization": "from-the-caller"}).status)
        self.assertNotIn("authorization", upstream.seen[0].headers)
        self.assertNotIn("x-api-key", upstream.seen[0].headers)

    def test_the_query_passes_and_is_never_logged(self) -> None:
        """A query reaches the destination, and the log holds neither it nor the path."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url)
        self.assertEqual(200, self.get("/v1/call?token=privatevalue").status)
        self.assertEqual("/v1/call?token=privatevalue", upstream.seen[0].path)
        self.assertNotIn("privatevalue", self.stream.getvalue())
        self.assertNotIn("/v1/call", self.stream.getvalue())

    def test_an_unknown_destination_is_refused_and_not_logged(self) -> None:
        """A request cannot name a host, and the refused name never reaches the log."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url)
        for target in ("/upstream/other/v1/call", f"/upstream/127.0.0.1:{upstream.port}/v1/call",
                       "/upstream//v1/call", "/upstream/privatevalue/v1/call"):
            reply = self.call("GET", target)
            self.assertEqual((403, "destination-not-allowed"), (reply.status, reply.code), target)
        self.assertEqual([], upstream.seen)
        self.assertNotIn("privatevalue", self.stream.getvalue())

    def test_a_path_outside_the_prefixes_is_refused(self) -> None:
        """Prefixes match whole segments, and no spelling of a path can escape them."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url)
        for path in ("/v2/call", "/v1x", "/v1x/call", "/", "/v1/../v2", "/v1/./call", "/v1//call", "//v1/call",
                     "/v1/%2e%2e/v2", "/v1/%2E%2E/v2", "/v1/..%2fv2", "/v1%2fcall", "/v1/%5c", "/v1/%00",
                     "/v1/%252e", "/v1/%zz", "/v1/call?x=%zz", "/v1/call?x=\"y\""):
            reply = self.get(path)
            self.assertEqual((403, "path-not-allowed"), (reply.status, reply.code), path)
        self.assertEqual([], upstream.seen)

    def test_other_paths_and_methods_are_refused(self) -> None:
        """Nothing but the two documented paths and methods exists, and refusals reflect nothing."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url)
        missing = self.call("GET", "/privatevalue")
        self.assertEqual((404, "not-found"), (missing.status, missing.code))
        for method in ("PUT", "DELETE", "PATCH", "OPTIONS", "TRACE", "HEAD", "CONNECT"):
            connection = http.client.HTTPConnection(*self.address, timeout=10)
            self.addCleanup(connection.close)
            connection.putrequest(method, f"/upstream/{ID}/v1/call", skip_host=True, skip_accept_encoding=True)
            connection.endheaders()
            self.assertEqual(405, connection.getresponse().status, method)
        self.assertEqual([], upstream.seen)
        self.assertNotIn("privatevalue", self.stream.getvalue())

    def test_a_malformed_request_reflects_nothing(self) -> None:
        """The library's error page would echo the request; the fixed answer does not."""
        self.serve("http://127.0.0.1:9")
        received = self.exchange_raw(b"BROKEN privatevalue WITH TOO MANY WORDS\r\n\r\n")
        self.assertIn(b"400", received.split(b"\r\n")[0])
        self.assertIn(b'{"code":"request-malformed"}', received)
        self.assertNotIn(b"privatevalue", received)
        self.assertNotIn(b"Python", received)

    def test_health_says_only_that_it_serves(self) -> None:
        """The health path names no destination and no key state, and contacts nothing."""
        upstream = self.upstream({})
        self.serve(upstream.url)
        reply = self.call("GET", "/healthz")
        self.assertEqual((200, b'{"status":"ok"}'), (reply.status, reply.body))
        self.assertEqual([], upstream.seen)

    def test_a_refused_network_gets_no_answer(self) -> None:
        """A peer on the egress network is closed unanswered and reaches no destination."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url, refused_peers=(ipaddress.ip_network("127.0.0.0/8"),))
        with socket.create_connection(self.address, timeout=10) as connection:
            connection.sendall(f"GET /upstream/{ID}/v1/call HTTP/1.0\r\n\r\n".encode("ascii"))
            received = b""
            with contextlib.suppress(ConnectionError):
                received = connection.recv(65536)
        self.assertEqual(b"", received)
        self.assertEqual([], upstream.seen)
        self.assertEqual("peer-refused", self.events()[-1]["event"])

    def test_the_egress_network_comes_from_the_default_route(self) -> None:
        """The interface holding the default route is the refused one, whatever its name."""
        header = "Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT\n"
        table = (header
                 + "eth1\t00000000\t0100120A\t0003\t0\t0\t0\t00000000\t0\t0\t0\n"
                 + "eth0\t0000130A\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0\n"
                 + "eth1\t0000120A\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0\n")
        subnets = gateway.egress_subnets(table)
        self.assertEqual(1, len(subnets))
        # The fields are host-order integers, so the reading depends only on the host's own order.
        self.assertEqual(16, subnets[0].prefixlen)
        self.assertEqual((), gateway.egress_subnets(header + "eth0\t0000130A\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0\n"))
        with self.assertRaises((ValueError, IndexError)):
            gateway.egress_subnets(header + "eth0\tunreadable\n")


class KeyHandlingTest(GatewayCase):
    """Keys stay on the gateway's side of every exchange."""

    def test_the_caller_cannot_supply_or_replace_credentials(self) -> None:
        """Only content negotiation passes: no credential, cookie, proxy or host header from the engine."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url, headers={"X-Version": "fixed"})
        self.assertEqual(200, self.post(headers={
            "x-api-key": "from-the-caller", "Authorization": "from-the-caller", "Cookie": "from=caller",
            "X-Forwarded-For": "from-the-caller", "Proxy-Authorization": "from-the-caller", "X-Version": "from-the-caller",
            "Accept-Encoding": "gzip", "Connection": "keep-alive, X-Other", "X-Other": "from-the-caller"}).status)
        seen = upstream.seen[0]
        self.assertEqual(SENTINEL, seen.headers["x-api-key"])
        self.assertEqual("fixed", seen.headers["x-version"])
        self.assertEqual("identity", seen.headers["accept-encoding"])
        self.assertEqual("close", seen.headers["connection"])
        self.assertNotIn("from-the-caller", json.dumps({k: v for k, v in seen.headers.items() if k != "x-api-key"}))
        self.assertTrue({"cookie", "authorization", "proxy-authorization", "x-forwarded-for", "x-other"}.isdisjoint(seen.headers))

    def test_an_unusable_key_stops_only_that_destination(self) -> None:
        """A destination without its key answers 503 and contacts nothing; the service stays up."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url, key={"file": "absent", "header": "x-api-key"})
        reply = self.get()
        self.assertEqual((503, "key-unavailable"), (reply.status, reply.code))
        self.assertEqual([], upstream.seen)
        self.assertEqual(200, self.call("GET", "/healthz").status)

    def test_a_response_echoing_the_key_is_refused(self) -> None:
        """The whole key, percent-encoded or base64-encoded, in a 2xx body or type never comes back."""
        secret = SENTINEL.encode("ascii")
        forms = {"raw": secret, "percent": urllib.parse.quote(SENTINEL, safe="").encode("ascii"),
                 "base64": base64.b64encode(secret), "urlsafe": base64.urlsafe_b64encode(secret)}
        routes = {f"/v1/{name}": answer(200, b'{"echo":"' + form + b'"}') for name, form in forms.items()}
        routes["/v1/type"] = answer(200, b"{}", (("Content-Type", f"text/{SENTINEL}"),))
        upstream = self.upstream(routes)
        self.serve(upstream.url)
        for path in routes:
            reply = self.get(path)
            self.assertEqual((502, "response-refused"), (reply.status, reply.code), path)
            for form in forms.values():
                self.assertNotIn(form, reply.body)

    def test_an_error_body_is_discarded(self) -> None:
        """A provider error often echoes key fragments: only the status and a fixed code return."""
        fragment = SENTINEL[:12].encode("ascii")
        upstream = self.upstream({"/v1/denied": answer(401, b'{"error":"bad key ' + fragment + b'"}',
                                                       (("X-Key-Hint", SENTINEL[:12]), ("Set-Cookie", "session=abc")))})
        self.serve(upstream.url)
        reply = self.get("/v1/denied")
        self.assertEqual(401, reply.status)
        self.assertEqual({"code": "upstream-status", "status": 401}, json.loads(reply.body))
        self.assertNotIn(fragment, reply.body + json.dumps(reply.headers).encode("ascii"))
        self.assertNotIn("set-cookie", reply.headers)

    def test_only_a_well_formed_retry_time_returns(self) -> None:
        """A retry time passes for the quota rules; any other text in that header is dropped."""
        upstream = self.upstream({
            "/v1/seconds": answer(429, b"", (("Retry-After", "30"),)),
            "/v1/date": answer(503, b"", (("Retry-After", "Sat, 31 Oct 2026 10:00:00 GMT"),)),
            "/v1/text": answer(429, b"", (("Retry-After", SENTINEL),))})
        self.serve(upstream.url)
        self.assertEqual("30", self.get("/v1/seconds").headers["retry-after"])
        self.assertEqual("Sat, 31 Oct 2026 10:00:00 GMT", self.get("/v1/date").headers["retry-after"])
        self.assertNotIn("retry-after", self.get("/v1/text").headers)

    def test_response_headers_do_not_pass(self) -> None:
        """Only the content type returns; a malformed one becomes a neutral type."""
        upstream = self.upstream({"/v1/call": answer(200, b"{}", (
            ("Content-Type", "application/json; charset=utf-8"), ("X-Internal", "privatevalue"), ("Server", "privatevalue"))),
            "/v1/odd": answer(200, b"{}", (("Content-Type", "privatevalue with spaces"),))})
        self.serve(upstream.url)
        reply = self.get()
        self.assertEqual("application/json; charset=utf-8", reply.headers["content-type"])
        self.assertNotIn("privatevalue", json.dumps(reply.headers))
        self.assertEqual(gateway.DEFAULT_CONTENT_TYPE, self.get("/v1/odd").headers["content-type"])

    def test_an_unexpected_failure_shows_only_its_type(self) -> None:
        """The caller gets a fixed code, and the log gets the type without its text."""
        self.serve("http://127.0.0.1:9")
        with mock.patch.object(gateway, "fetch_once", side_effect=RuntimeError(SENTINEL)):
            reply = self.get()
        self.assertEqual((500, "gateway-internal"), (reply.status, reply.code))
        self.assertEqual("RuntimeError", self.events()[-1]["failure"])

    def test_a_log_line_holds_only_codes_identifiers_and_counts(self) -> None:
        """The fields of a request line are fixed, so nothing from a peer can be added to them."""
        upstream = self.upstream({"/v1/call": answer(200, b"12345", (("X-Value", "privatevalue"),))})
        self.serve(upstream.url)
        self.post(body=b"privatevalue")
        line = self.events()[-1]
        self.assertEqual({"time", "event", "method", "destination", "status", "code", "hops", "request_bytes",
                          "response_bytes", "duration_ms"}, set(line))
        self.assertEqual(("request", "POST", ID, 200, "ok", 0, 12, 5),
                         (line["event"], line["method"], line["destination"], line["status"], line["code"],
                          line["hops"], line["request_bytes"], line["response_bytes"]))
        self.assertNotIn("privatevalue", self.stream.getvalue())


class CookieTest(GatewayCase):
    """No cookie is stored or forwarded, in either direction or across hops."""

    def test_cookies_are_neither_kept_nor_returned(self) -> None:
        """A cookie set by a hop reaches neither the next hop, the next call nor the caller."""
        upstream = self.upstream({
            "/v1/first": redirect("/v1/second", headers=(("Set-Cookie", "session=upstream"),)),
            "/v1/second": answer(200, b"{}", (("Set-Cookie", "other=upstream"),))})
        self.serve(upstream.url)
        reply = self.get("/v1/first", headers={"Cookie": "from=caller"})
        self.assertEqual(200, reply.status)
        self.assertNotIn("set-cookie", reply.headers)
        self.assertEqual(200, self.get("/v1/first").status)
        self.assertEqual(4, len(upstream.seen))
        for seen in upstream.seen:
            self.assertNotIn("cookie", seen.headers)


class RequestBoundsTest(GatewayCase):
    """A request over its bound is refused before anything is contacted."""

    def test_the_limit_itself_passes_and_one_byte_more_is_refused(self) -> None:
        """The bound is exact, and the refused request reaches no upstream."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url, max_request_bytes=32)
        self.assertEqual(200, self.post(body=b"x" * 32).status)
        reply = self.post(body=b"x" * 33)
        self.assertEqual((413, "request-too-large"), (reply.status, reply.code))
        self.assertEqual(1, len(upstream.seen))

    def test_a_large_declared_length_is_refused_without_reading_it(self) -> None:
        """The refusal comes from the declaration, so nothing is buffered first."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url, max_request_bytes=32)
        received = self.exchange_raw(f"POST /upstream/{ID}/v1/call HTTP/1.1\r\nContent-Length: 999999999999\r\n\r\n".encode("ascii"))
        self.assertIn(b"413", received.split(b"\r\n")[0])
        self.assertIn(b'{"code":"request-too-large"}', received)
        self.assertEqual([], upstream.seen)

    def test_a_request_without_an_exact_length_is_refused(self) -> None:
        """Chunked, missing, repeated and malformed lengths could desynchronize a relay."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url, max_request_bytes=32)
        cases = {"Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n": b"411",
                 "\r\n": b"411",
                 "Content-Length: 1\r\nContent-Length: 2\r\n\r\nxx": b"400",
                 "Content-Length: -1\r\n\r\n": b"400",
                 "Content-Length: 1e3\r\n\r\n": b"400"}
        for headers, status in cases.items():
            received = self.exchange_raw(f"POST /upstream/{ID}/v1/call HTTP/1.1\r\n{headers}".encode("ascii"))
            self.assertIn(status, received.split(b"\r\n")[0], headers)
        self.assertEqual([], upstream.seen)

    def test_a_get_may_not_carry_a_body(self) -> None:
        """A body on a GET is refused rather than dropped or passed on."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url)
        reply = self.call("GET", f"/upstream/{ID}/v1/call", b"body")
        self.assertEqual((400, "request-malformed"), (reply.status, reply.code))
        self.assertEqual([], upstream.seen)

    def test_requests_in_flight_are_bounded(self) -> None:
        """Over the bound a request is refused at once instead of adding to memory use."""
        entered = threading.Event()

        def hold(handler: http.server.BaseHTTPRequestHandler) -> None:
            entered.set()
            self.release.wait(15)
            answer()(handler)

        upstream = self.upstream({"/v1/hold": hold})
        self.serve(upstream.url, max_in_flight=1, timeout_seconds=20)
        results: list[Reply] = []
        first = threading.Thread(target=lambda: results.append(self.get("/v1/hold")), daemon=True)
        first.start()
        self.assertTrue(entered.wait(10))
        reply = self.get("/v1/hold")
        self.assertEqual((503, "gateway-busy"), (reply.status, reply.code))
        self.release.set()
        first.join(15)
        self.assertEqual(200, results[0].status)
        # The slot came back: the bound limits concurrency and does not count calls.
        self.assertEqual(200, self.get("/v1/hold").status)
        self.assertEqual(2, len(upstream.seen))


class SlowClientTest(GatewayCase):
    """The time a caller may take to send one request is bounded as a whole."""

    def receive_all(self, connection: socket.socket) -> bytes:
        """Return what the gateway sends until it closes; a reset counts as the end."""
        received = b""
        with contextlib.suppress(ConnectionError):
            while chunk := connection.recv(65536):
                received += chunk
        return received

    def test_an_unfinished_body_ends_with_a_refusal(self) -> None:
        """A declared body that never arrives is a timeout, and nothing goes upstream."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url, client_timeout=0.5)
        started = time.monotonic()
        with socket.create_connection(self.address, timeout=10) as connection:
            connection.sendall(f"POST /upstream/{ID}/v1/call HTTP/1.1\r\nContent-Length: 10\r\n\r\nx".encode("ascii"))
            received = self.receive_all(connection)
        self.assertIn(b'{"code":"request-timeout"}', received)
        self.assertLess(time.monotonic() - started, 5)
        self.assertEqual([], upstream.seen)

    def test_a_body_sent_byte_by_byte_cannot_outlast_the_bound(self) -> None:
        """Each byte would reset a per-read timeout, so the refusal must come while bytes still arrive."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url, client_timeout=0.5, max_request_bytes=1000)
        started = time.monotonic()
        with socket.create_connection(self.address, timeout=10) as connection:
            connection.sendall(f"POST /upstream/{ID}/v1/call HTTP/1.1\r\nContent-Length: 1000\r\n\r\n".encode("ascii"))
            connection.settimeout(0.1)
            received = b""
            while not received and time.monotonic() - started < 4:
                connection.sendall(b"x")
                with contextlib.suppress(TimeoutError):
                    received = connection.recv(65536)
            elapsed = time.monotonic() - started
            connection.settimeout(10)
            received += self.receive_all(connection)
        self.assertIn(b'{"code":"request-timeout"}', received)
        self.assertLess(elapsed, 3)
        self.assertEqual([], upstream.seen)

    def test_an_unfinished_request_head_closes_the_connection(self) -> None:
        """A silent or partial head holds no thread past the bound."""
        self.serve("http://127.0.0.1:9", client_timeout=0.5)
        started = time.monotonic()
        with socket.create_connection(self.address, timeout=10) as connection:
            connection.sendall(b"GET /healthz HTTP/1.1\r\nX-Partial: ")
            self.receive_all(connection)
        self.assertLess(time.monotonic() - started, 5)


class ResponseBoundsTest(GatewayCase):
    """No response over its bound reaches the caller, however the upstream frames it."""

    def endless(self, handler: http.server.BaseHTTPRequestHandler) -> None:
        """Write a body with no declared length until the gateway drops the connection."""
        handler.wfile.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n")
        for _ in range(4096):
            handler.wfile.write(b"x" * 4096)

    def endless_chunks(self, handler: http.server.BaseHTTPRequestHandler) -> None:
        """Write chunks with no end until the gateway drops the connection."""
        handler.wfile.write(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
        for _ in range(4096):
            handler.wfile.write(b"1000\r\n" + b"x" * 4096 + b"\r\n")

    def test_the_limit_itself_passes_and_one_byte_more_is_refused(self) -> None:
        """The bound is exact for a declared length."""
        upstream = self.upstream({"/v1/exact": answer(200, b"x" * 64), "/v1/over": answer(200, b"x" * 65)})
        self.serve(upstream.url, max_response_bytes=64)
        self.assertEqual(b"x" * 64, self.get("/v1/exact").body)
        reply = self.get("/v1/over")
        self.assertEqual((502, "response-too-large"), (reply.status, reply.code))

    def test_a_large_declared_length_is_refused_without_reading_it(self) -> None:
        """The declaration alone refuses: this body is short, so reading it would end differently."""
        upstream = self.upstream({"/v1/call": raw(b"HTTP/1.1 200 OK\r\nContent-Length: 999999999\r\nConnection: close\r\n\r\nabc")})
        self.serve(upstream.url, max_response_bytes=64)
        reply = self.get()
        self.assertEqual((502, "response-too-large"), (reply.status, reply.code))

    def test_an_undeclared_or_chunked_body_is_counted(self) -> None:
        """A body with no length or endless chunks is cut at the bound, not trusted to end."""
        upstream = self.upstream({"/v1/endless": self.endless, "/v1/chunks": self.endless_chunks})
        self.serve(upstream.url, max_response_bytes=64 * 1024)
        for path in ("/v1/endless", "/v1/chunks"):
            reply = self.get(path)
            self.assertEqual((502, "response-too-large"), (reply.status, reply.code), path)

    def test_a_chunked_body_within_the_bound_passes_whole(self) -> None:
        """Bounded framing does not break an ordinary chunked answer."""
        upstream = self.upstream({"/v1/call": raw(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                                                  b"3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")})
        self.serve(upstream.url, max_response_bytes=5)
        self.assertEqual(b"abcde", self.get().body)

    def test_a_truncated_body_is_never_a_success(self) -> None:
        """A body shorter than declared, or chunks that stop, are protocol failures."""
        upstream = self.upstream({
            "/v1/short": raw(b"HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nabc"),
            "/v1/chunks": raw(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n")})
        self.serve(upstream.url)
        for path in ("/v1/short", "/v1/chunks"):
            reply = self.get(path)
            self.assertEqual((502, "upstream-protocol"), (reply.status, reply.code), path)

    def test_a_compressed_body_is_refused(self) -> None:
        """Its size says nothing about what the engine would inflate."""
        upstream = self.upstream({"/v1/call": answer(200, b"\x1f\x8b", (("Content-Encoding", "gzip"),))})
        self.serve(upstream.url)
        reply = self.get()
        self.assertEqual((502, "response-encoded"), (reply.status, reply.code))

    def test_garbage_and_repeated_lengths_are_protocol_failures(self) -> None:
        """An answer that is not valid HTTP, or is ambiguous about its length, is refused."""
        upstream = self.upstream({
            "/v1/garbage": raw(b"not http at all\r\n\r\n"),
            "/v1/repeated": raw(b"HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nxx"),
            "/v1/empty": raw(b"")})
        self.serve(upstream.url)
        for path in ("/v1/garbage", "/v1/repeated", "/v1/empty"):
            reply = self.get(path)
            self.assertEqual((502, "upstream-protocol"), (reply.status, reply.code), path)

    def test_an_interim_status_is_not_relayed(self) -> None:
        """A switching-protocols answer is outside what the gateway can carry."""
        upstream = self.upstream({"/v1/call": raw(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: x\r\n\r\n")})
        self.serve(upstream.url)
        reply = self.get()
        self.assertEqual((502, "upstream-protocol"), (reply.status, reply.code))


class TimeLimitTest(GatewayCase):
    """Time is bounded across the whole call, including peers that keep sending slowly."""

    BOUND = 0.5

    def drip_body(self, handler: http.server.BaseHTTPRequestHandler) -> None:
        """Declare a body and send it one byte at a time, each byte resetting a read timeout."""
        handler.wfile.write(b"HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n")
        for _ in range(100):
            handler.wfile.write(b"x")
            handler.wfile.flush()
            time.sleep(0.05)

    def drip_head(self, handler: http.server.BaseHTTPRequestHandler) -> None:
        """Send the response head one byte at a time."""
        handler.wfile.write(b"HTTP/1.1 200 OK\r\nX-Slow: ")
        for _ in range(100):
            handler.wfile.write(b"x")
            handler.wfile.flush()
            time.sleep(0.05)

    def timed(self, path: str) -> tuple[Reply, float]:
        """Return the reply of one call and how long it took."""
        started = time.monotonic()
        return self.get(path), time.monotonic() - started

    def test_a_silent_or_dripping_upstream_times_out_near_the_bound(self) -> None:
        """Silence, a slow head and a slow body all end in the same refusal, on time."""
        def silent(_handler: http.server.BaseHTTPRequestHandler) -> None:
            self.release.wait(15)

        upstream = self.upstream({"/v1/silent": silent, "/v1/head": self.drip_head, "/v1/body": self.drip_body})
        self.serve(upstream.url, timeout_seconds=self.BOUND)
        for path in ("/v1/silent", "/v1/head", "/v1/body"):
            reply, elapsed = self.timed(path)
            self.assertEqual((504, "upstream-timeout"), (reply.status, reply.code), path)
            self.assertLess(elapsed, 3, path)
            self.assertGreaterEqual(elapsed, self.BOUND * 0.8, path)

    def test_a_deadline_passing_during_connection_setup_still_ends_the_call(self) -> None:
        """A timer firing before the socket is exposed severs nothing, so the deadline is checked again.

        The delayed connect stands in for a slow TCP connect or TLS handshake: the timer is spent
        by the time the upstream drips its answer, and only per-read timeouts would remain, which
        each byte resets. The call must end before the request is sent, so the key never leaves.
        """
        upstream = self.upstream({"/v1/body": self.drip_body})
        self.serve(upstream.url, timeout_seconds=self.BOUND)
        real_connect = http.client.HTTPConnection.connect

        def slow_connect(connection: http.client.HTTPConnection) -> None:
            # The caller's own connection uses this class too, and it must not be delayed.
            if connection.port == upstream.port:
                time.sleep(self.BOUND * 2)
            real_connect(connection)

        with mock.patch.object(http.client.HTTPConnection, "connect", slow_connect):
            reply, elapsed = self.timed("/v1/body")
        self.assertEqual((504, "upstream-timeout"), (reply.status, reply.code))
        self.assertLess(elapsed, 3)
        self.assertEqual([], upstream.seen)

    def test_the_bound_covers_all_hops_together(self) -> None:
        """A redirect chain shares one deadline instead of restarting it on each hop."""
        def slow_hop(handler: http.server.BaseHTTPRequestHandler) -> None:
            time.sleep(0.4)
            redirect("/v1/slow")(handler)

        upstream = self.upstream({"/v1/slow": slow_hop})
        self.serve(upstream.url, timeout_seconds=1, max_redirects=5)
        reply, elapsed = self.timed("/v1/slow")
        self.assertEqual((504, "upstream-timeout"), (reply.status, reply.code))
        self.assertLess(elapsed, 3)
        self.assertLessEqual(len(upstream.seen), 3)

    def test_a_refused_connection_fails_at_once_and_shows_no_address(self) -> None:
        """An unreachable destination has a fixed code, without the library's text."""
        with socket.socket() as unused:
            unused.bind(("127.0.0.1", 0))
            port = unused.getsockname()[1]
        self.serve(f"http://127.0.0.1:{port}")
        reply = self.get()
        self.assertEqual((502, "upstream-unreachable"), (reply.status, reply.code))
        self.assertNotIn(str(port).encode("ascii"), reply.body)
        self.assertNotIn(str(port), self.stream.getvalue())


class BudgetTest(GatewayCase):
    """A metered destination stops at its caps, across restarts, and nothing moves elsewhere."""

    def metered(self, url: str, daily: int = 2, monthly: int = 10, **changes: object) -> "BudgetTest":
        """Start the gateway with one destination under these caps."""
        return self.serve(url, budget={"daily_calls": daily, "monthly_calls": monthly}, **changes)

    def state(self) -> dict:
        """Return the budget file's stored document."""
        return json.loads(self.budget_file.read_text(encoding="ascii"))

    def counts(self) -> tuple[int, int]:
        """Return the stored daily and monthly counts of the destination."""
        entry = self.state()["destinations"][ID]
        return entry["day_calls"], entry["month_calls"]

    def assert_refused(self, code: str, status: int = 429, path: str = "/v1/call") -> Reply:
        """Require one fixed refusal and return it."""
        reply = self.get(path)
        self.assertEqual((status, code), (reply.status, reply.code))
        self.assertEqual({"code": code}, json.loads(reply.body))
        return reply

    def test_the_daily_cap_stops_the_destination_without_contact(self) -> None:
        """Calls up to the cap pass; the next one is refused for both methods and reaches nothing."""
        upstream = self.upstream({"/v1/call": answer()})
        self.metered(upstream.url, daily=2)
        self.assertEqual(200, self.get().status)
        self.assertEqual(200, self.post().status)
        reply = self.assert_refused("budget-exhausted-daily")
        self.assertEqual(str(12 * 3600), reply.headers["retry-after"])
        refused_post = self.post(body=b"payload")
        self.assertEqual((429, "budget-exhausted-daily"), (refused_post.status, refused_post.code))
        self.assertEqual(2, len(upstream.seen))
        self.assertEqual((2, 2), self.counts())
        line = self.events()[-1]
        self.assertEqual(("request", ID, 429, "budget-exhausted-daily"),
                         (line["event"], line["destination"], line["status"], line["code"]))

    def test_the_monthly_cap_stops_the_destination_without_contact(self) -> None:
        """The monthly refusal names its own code and the start of the next month."""
        upstream = self.upstream({"/v1/call": answer()})
        self.metered(upstream.url, daily=10, monthly=2)
        self.assertEqual(200, self.get().status)
        self.now += timedelta(days=1)
        self.assertEqual(200, self.get().status)
        reply = self.assert_refused("budget-exhausted-monthly")
        boundary = datetime(2026, 11, 1, tzinfo=timezone.utc)
        self.assertEqual(str(int((boundary - self.now).total_seconds())), reply.headers["retry-after"])
        self.assertEqual(2, len(upstream.seen))

    def test_the_monthly_code_wins_when_both_caps_are_reached(self) -> None:
        """Waiting for the next day would not help, so the longer stop is the one reported."""
        upstream = self.upstream({"/v1/call": answer()})
        self.metered(upstream.url, daily=1, monthly=1)
        self.assertEqual(200, self.get().status)
        self.assert_refused("budget-exhausted-monthly")

    def test_the_day_boundary_resets_only_the_daily_count(self) -> None:
        """The cap holds up to the last second of the UTC day, and the month keeps counting."""
        upstream = self.upstream({"/v1/call": answer()})
        self.metered(upstream.url, daily=1, monthly=2)
        self.assertEqual(200, self.get().status)
        self.now = datetime(2026, 10, 15, 23, 59, 59, tzinfo=timezone.utc)
        self.assertEqual("1", self.assert_refused("budget-exhausted-daily").headers["retry-after"])
        self.now = datetime(2026, 10, 16, 0, 0, 0, tzinfo=timezone.utc)
        self.assertEqual(200, self.get().status)
        self.assertEqual((1, 2), self.counts())
        self.now += timedelta(days=1)
        self.assert_refused("budget-exhausted-monthly")
        self.assertEqual(2, len(upstream.seen))

    def test_the_month_boundary_resets_both_counts_also_across_a_year(self) -> None:
        """December's refusal points at January, and January starts empty."""
        upstream = self.upstream({"/v1/call": answer()})
        self.now = datetime(2026, 12, 31, 23, 0, tzinfo=timezone.utc)
        self.metered(upstream.url, daily=1, monthly=1)
        self.assertEqual(200, self.get().status)
        self.assertEqual("3600", self.assert_refused("budget-exhausted-monthly").headers["retry-after"])
        self.now = datetime(2027, 1, 1, tzinfo=timezone.utc)
        self.assertEqual(200, self.get().status)
        self.assertEqual({"day": "2027-01-01", "day_calls": 1, "month": "2027-01", "month_calls": 1},
                         self.state()["destinations"][ID])

    def test_a_clock_moved_back_reopens_nothing(self) -> None:
        """An earlier label keeps the later period's count, so a clock fix cannot refund spending."""
        upstream = self.upstream({"/v1/call": answer()})
        self.metered(upstream.url, daily=1, monthly=10)
        self.assertEqual(200, self.get().status)
        self.now -= timedelta(days=2)
        self.assert_refused("budget-exhausted-daily")
        self.assertEqual("2026-10-15", self.state()["destinations"][ID]["day"])
        self.assertEqual(1, len(upstream.seen))

    def test_counts_survive_a_restart_and_still_reset_afterwards(self) -> None:
        """A new gateway on the same file continues the count and the stop, until the period ends."""
        upstream = self.upstream({"/v1/call": answer()})
        self.metered(upstream.url, daily=2, monthly=3)
        self.assertEqual(200, self.get().status)
        self.metered(upstream.url, daily=2, monthly=3)
        self.assertEqual(200, self.get().status)
        self.assertEqual((2, 2), self.counts())
        self.metered(upstream.url, daily=2, monthly=3)
        self.assert_refused("budget-exhausted-daily")
        self.now += timedelta(days=1)
        self.metered(upstream.url, daily=2, monthly=3)
        self.assertEqual(200, self.get().status)
        self.metered(upstream.url, daily=2, monthly=3)
        self.assert_refused("budget-exhausted-monthly")
        self.assertEqual(3, len(upstream.seen))

    def test_a_reserved_count_is_on_disk_before_the_call_is_made(self) -> None:
        """A process killed during a call has already recorded it, so a restart cannot forget it."""
        stored: list[tuple[int, int]] = []

        def record(handler: http.server.BaseHTTPRequestHandler) -> None:
            stored.append(self.counts())
            answer()(handler)

        upstream = self.upstream({"/v1/call": record})
        self.metered(upstream.url)
        self.assertEqual(200, self.get().status)
        self.assertEqual([(1, 1)], stored)

    def test_a_missing_file_starts_at_zero_and_is_created(self) -> None:
        """A first start looks like this, and the file exists after the first call."""
        upstream = self.upstream({"/v1/call": answer()})
        self.metered(upstream.url)
        self.assertFalse(self.budget_file.exists())
        self.assertEqual(200, self.get().status)
        self.assertEqual((1, 1), self.counts())
        self.assertNotIn("budget-state-refused", self.stream.getvalue())

    def test_a_damaged_file_stops_metered_destinations_and_nothing_else(self) -> None:
        """Resetting would reopen spending, so each damage fails closed and leaves the file for the owner."""
        entry = {"day": "2026-10-15", "day_calls": 1, "month": "2026-10", "month_calls": 1}
        document = {"schema": gateway.BUDGET_SCHEMA, "destinations": {ID: entry}}
        cases = {
            "not JSON": SENTINEL.encode("ascii"),
            "cut short": json.dumps(document).encode("ascii")[:-5],
            "not text": b"\xff\xfe" + SENTINEL.encode("ascii"),
            "nested without end": b"[" * 60000,
            "too large": json.dumps({**document, "padding": SENTINEL}).encode("ascii") + b" " * gateway.MAX_BUDGET_FILE_BYTES,
            "another schema": json.dumps({**document, "schema": SENTINEL}).encode("ascii"),
            "an unknown field": json.dumps({**document, "note": SENTINEL}).encode("ascii"),
            "a repeated field": ('{"schema":"' + gateway.BUDGET_SCHEMA + '","schema":"x","destinations":{}}').encode("ascii"),
            "a list of destinations": json.dumps({**document, "destinations": [entry]}).encode("ascii"),
            "an invalid identifier": json.dumps({**document, "destinations": {SENTINEL.upper(): entry}}).encode("ascii"),
            "a missing count": json.dumps({**document, "destinations": {ID: {"day": "2026-10-15", "month": "2026-10"}}}).encode("ascii"),
            "a negative count": json.dumps({**document, "destinations": {ID: {**entry, "day_calls": -1}}}).encode("ascii"),
            "a boolean count": json.dumps({**document, "destinations": {ID: {**entry, "month_calls": True}}}).encode("ascii"),
            "a text count": json.dumps({**document, "destinations": {ID: {**entry, "day_calls": SENTINEL}}}).encode("ascii"),
            "a bad day label": json.dumps({**document, "destinations": {ID: {**entry, "day": SENTINEL}}}).encode("ascii"),
            "a bad month label": json.dumps({**document, "destinations": {ID: {**entry, "month": "2026-10-15"}}}).encode("ascii"),
            "an unknown entry field": json.dumps({**document, "destinations": {ID: {**entry, "key": SENTINEL}}}).encode("ascii"),
        }
        upstream = self.upstream({"/v1/call": answer()})
        open_upstream = self.upstream({"/v1/call": answer()})
        others = ({"id": "open", "url": open_upstream.url, "path_prefixes": ["/v1"]},)
        for name, content in cases.items():
            with self.subTest(name):
                self.budget_file.write_bytes(content)
                refusals_before = self.stream.getvalue().count("budget-state-refused")
                self.metered(upstream.url, others=others)
                self.assertEqual(refusals_before + 1, self.stream.getvalue().count("budget-state-refused"))
                self.assert_refused("budget-unavailable", 503)
                self.assertEqual(200, self.call("GET", "/upstream/open/v1/call").status)
                self.assertEqual(200, self.call("GET", "/healthz").status)
                self.assertEqual(content, self.budget_file.read_bytes())
        self.assertEqual([], upstream.seen)
        self.assertEqual(len(cases), len(open_upstream.seen))

    def test_an_unreadable_path_fails_closed(self) -> None:
        """A path that exists and cannot be read is not the same as a first start."""
        upstream = self.upstream({"/v1/call": answer()})
        self.budget_file.mkdir()
        self.metered(upstream.url)
        self.assert_refused("budget-unavailable", 503)
        self.assertEqual([], upstream.seen)
        self.assertIn("budget-state-refused", self.stream.getvalue())

    def test_a_failed_write_refuses_the_call_and_consumes_nothing(self) -> None:
        """No call leaves unrecorded, and space returning restores service with the count intact."""
        upstream = self.upstream({"/v1/call": answer()})
        self.metered(upstream.url, daily=2)
        self.assertEqual(200, self.get().status)
        with mock.patch.object(gateway.os, "replace", side_effect=OSError(SENTINEL)):
            self.assert_refused("budget-unavailable", 503)
            self.assert_refused("budget-unavailable", 503)
        self.assertEqual(1, len(upstream.seen))
        self.assertEqual((1, 1), self.counts())
        self.assertEqual(200, self.get().status)
        self.assertEqual((2, 2), self.counts())
        self.assert_refused("budget-exhausted-daily")

    def test_a_metered_destination_without_a_ledger_fails_closed(self) -> None:
        """A cap nobody counts must not mean no cap."""
        upstream = self.upstream({"/v1/call": answer()})
        settings = gateway.parse_settings(json.dumps({"schema": gateway.SCHEMA, "destinations": [
            {"id": ID, "url": upstream.url, "path_prefixes": ["/v1"], "budget": {"daily_calls": 1, "monthly_calls": 1}}]}))
        with self.assertRaises(gateway.Refusal) as caught:
            gateway.Gateway(settings, {}, self.log).charge(settings.destinations[ID])
        self.assertEqual((503, "budget-unavailable"), (caught.exception.status, caught.exception.code))

    def test_a_capped_destination_never_moves_work_elsewhere(self) -> None:
        """The other destination sees nothing from the refused calls and keeps its own count."""
        capped = self.upstream({"/v1/call": answer(200, b"capped")})
        spare = self.upstream({"/v1/call": answer(200, b"spare")})
        others = ({"id": "spare-metered", "url": spare.url, "path_prefixes": ["/v1"],
                   "budget": {"daily_calls": 5, "monthly_calls": 5}},
                  {"id": "spare-open", "url": spare.url, "path_prefixes": ["/v1"]})
        self.metered(capped.url, daily=1, others=others)
        self.assertEqual(b"capped", self.get().body)
        for _ in range(3):
            self.assert_refused("budget-exhausted-daily")
        self.assertEqual([], spare.seen)
        self.assertEqual(1, len(capped.seen))
        self.assertEqual(b"spare", self.call("GET", "/upstream/spare-metered/v1/call").body)
        self.assertEqual(b"spare", self.call("GET", "/upstream/spare-open/v1/call").body)
        self.assertEqual(2, len(spare.seen))
        destinations = self.state()["destinations"]
        self.assertEqual({ID, "spare-metered"}, set(destinations))
        self.assertEqual(1, destinations["spare-metered"]["day_calls"])

    def test_a_destination_without_a_budget_is_unchanged(self) -> None:
        """It never reads or writes the file, even a damaged one, and answers as before."""
        upstream = self.upstream({"/v1/call": answer()})
        self.serve(upstream.url)
        for _ in range(5):
            self.assertEqual(200, self.get().status)
        self.assertEqual(5, len(upstream.seen))
        self.assertFalse(self.budget_file.exists())
        self.budget_file.write_bytes(b"damaged")
        self.serve(upstream.url)
        self.assertEqual(200, self.get().status)
        self.assertEqual(b"damaged", self.budget_file.read_bytes())
        self.assertNotIn("budget-state-refused", self.stream.getvalue())

    def test_a_request_refused_before_contact_costs_nothing(self) -> None:
        """Only a call that may reach the destination spends the budget."""
        upstream = self.upstream({"/v1/call": answer()})
        self.metered(upstream.url, max_request_bytes=4)
        self.assert_refused("path-not-allowed", 403, "/v2/call")
        self.assertEqual(413, self.post(body=b"too large").status)
        self.assertEqual(400, self.call("GET", f"/upstream/{ID}/v1/call", b"body").status)
        self.assertFalse(self.budget_file.exists())
        keyless = self.upstream({"/v1/call": answer()})
        self.metered(keyless.url, key={"file": "absent", "header": "x-api-key"})
        self.assert_refused("key-unavailable", 503)
        self.assertFalse(self.budget_file.exists())
        self.assertEqual([], upstream.seen + keyless.seen)

    def test_a_failed_call_counts_once_and_redirect_hops_share_it(self) -> None:
        """The gateway cannot tell whether the provider billed a failure, so it counts as spent."""
        upstream = self.upstream({"/v1/call": answer(), "/v1/denied": answer(500, b"failed"),
                                  "/v1/hop": redirect("/v1/call")})
        self.metered(upstream.url, daily=10)
        self.assertEqual(500, self.get("/v1/denied").status)
        self.assertEqual((1, 1), self.counts())
        self.assertEqual(200, self.get("/v1/hop").status)
        self.assertEqual((2, 2), self.counts())
        self.assertEqual(3, len(upstream.seen))
        with socket.socket() as unused:
            unused.bind(("127.0.0.1", 0))
            port = unused.getsockname()[1]
        self.metered(f"http://127.0.0.1:{port}", daily=10)
        self.assert_refused("upstream-unreachable", 502)
        self.assertEqual((3, 3), self.counts())

    def test_the_time_budget_ends_a_metered_call_and_it_counts(self) -> None:
        """`timeout_seconds` is the per-call time budget, whatever the destination's caps."""
        def silent(_handler: http.server.BaseHTTPRequestHandler) -> None:
            self.release.wait(15)

        upstream = self.upstream({"/v1/call": silent})
        self.metered(upstream.url, daily=1, timeout_seconds=0.5)
        started = time.monotonic()
        self.assert_refused("upstream-timeout", 504)
        elapsed = time.monotonic() - started
        self.assertGreaterEqual(elapsed, 0.4)
        self.assertLess(elapsed, 3)
        self.assertEqual((1, 1), self.counts())
        self.assert_refused("budget-exhausted-daily")
        self.assertEqual(1, len(upstream.seen))

    def test_concurrent_calls_cannot_pass_the_cap(self) -> None:
        """Reserving before contact means exactly the cap reaches the destination."""
        upstream = self.upstream({"/v1/call": answer()})
        self.metered(upstream.url, daily=5, monthly=5, max_in_flight=32)
        replies: list[Reply] = []
        threads = [threading.Thread(target=lambda: replies.append(self.get()), daemon=True) for _ in range(20)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(30)
        self.assertEqual(20, len(replies))
        self.assertEqual(5, sum(reply.status == 200 for reply in replies))
        self.assertEqual(15, sum(reply.status == 429 for reply in replies))
        self.assertEqual(5, len(upstream.seen))
        self.assertEqual((5, 5), self.counts())

    def test_no_key_reaches_the_budget_file_or_a_refusal(self) -> None:
        """The file holds identifiers, labels and counts only, while the upstream really got the key (QA-16)."""
        upstream = self.upstream({"/v1/call": answer()})
        self.metered(upstream.url, daily=1)
        self.assertEqual(200, self.get().status)
        self.assertEqual(SENTINEL, upstream.seen[0].headers["x-api-key"], "control: the key must really have been sent")
        reply = self.assert_refused("budget-exhausted-daily")
        self.assertEqual({"content-type", "content-length", "cache-control", "connection", "retry-after", "server", "date"},
                         set(reply.headers))
        raw_state = self.budget_file.read_bytes()
        self.assertNotIn(SENTINEL.encode("ascii"), raw_state)
        self.assertNotIn(upstream.url.encode("ascii"), raw_state)
        self.assertEqual({"schema": gateway.BUDGET_SCHEMA, "destinations": {
            ID: {"day": "2026-10-15", "day_calls": 1, "month": "2026-10", "month_calls": 1}}}, self.state())
        self.assertEqual([self.budget_file.name], [path.name for path in self.budget_file.parent.iterdir()])
        self.assertNotIn(SENTINEL, self.stream.getvalue())
        self.assertNotIn(str(self.budget_file.parent), self.stream.getvalue())


class RedirectTest(GatewayCase):
    """Redirects are bounded in number and never leave the destination's origin or prefixes."""

    def chain(self, length: int) -> dict[str, Route]:
        """Return routes where /v1/0 redirects `length` times before the final answer."""
        routes = {f"/v1/{index}": redirect(f"/v1/{index + 1}") for index in range(length)}
        routes[f"/v1/{length}"] = answer(200, b"final")
        return routes

    def test_redirects_within_the_bound_are_followed_with_the_key(self) -> None:
        """The chain ends at its answer, and each hop of the same origin carries the key."""
        upstream = self.upstream(self.chain(2))
        self.serve(upstream.url, max_redirects=2)
        reply = self.get("/v1/0")
        self.assertEqual((200, b"final"), (reply.status, reply.body))
        self.assertEqual(["/v1/0", "/v1/1", "/v1/2"], [seen.path for seen in upstream.seen])
        for seen in upstream.seen:
            self.assertEqual(SENTINEL, seen.headers["x-api-key"])
        self.assertEqual(2, self.events()[-1]["hops"])

    def test_one_redirect_over_the_bound_is_refused(self) -> None:
        """The hop past the bound is never requested."""
        upstream = self.upstream(self.chain(3))
        self.serve(upstream.url, max_redirects=2)
        reply = self.get("/v1/0")
        self.assertEqual((502, "redirect-limit"), (reply.status, reply.code))
        self.assertEqual(["/v1/0", "/v1/1", "/v1/2"], [seen.path for seen in upstream.seen])

    def test_a_bound_of_zero_follows_nothing(self) -> None:
        """Zero is a usable setting: the first redirect ends the call."""
        upstream = self.upstream(self.chain(1))
        self.serve(upstream.url, max_redirects=0)
        reply = self.get("/v1/0")
        self.assertEqual((502, "redirect-limit"), (reply.status, reply.code))
        self.assertEqual(["/v1/0"], [seen.path for seen in upstream.seen])

    def test_a_redirect_loop_ends_at_the_bound(self) -> None:
        """A loop costs at most the bound plus one request."""
        upstream = self.upstream({"/v1/loop": redirect("/v1/loop")})
        self.serve(upstream.url, max_redirects=gateway.MAX_REDIRECTS)
        reply = self.get("/v1/loop")
        self.assertEqual((502, "redirect-limit"), (reply.status, reply.code))
        self.assertEqual(gateway.MAX_REDIRECTS + 1, len(upstream.seen))

    def test_a_redirect_off_the_origin_is_refused_and_never_contacted(self) -> None:
        """Another host, port or scheme ends the call, and the other server sees nothing, so no key leaves."""
        other = self.upstream({"/v1/call": answer(200, b"elsewhere")})
        locations = {
            "port": other.url + "/v1/call",
            "host": f"http://localhost:{other.port}/v1/call",
            "relative-host": f"//localhost:{other.port}/v1/call",
        }
        routes = {f"/v1/{name}": redirect(location) for name, location in locations.items()}
        upstream = self.upstream(routes)
        locations_same_port = {
            "scheme": f"https://127.0.0.1:{upstream.port}/v1/call",
            "same-port-host": f"http://localhost:{upstream.port}/v1/call",
        }
        routes.update({f"/v1/{name}": redirect(location) for name, location in locations_same_port.items()})
        routes["/v1/call"] = answer(200, b"same origin")
        self.serve(upstream.url)
        for name in (*locations, *locations_same_port):
            reply = self.get(f"/v1/{name}")
            self.assertEqual((502, "redirect-cross-origin"), (reply.status, reply.code), name)
        self.assertEqual([], other.seen)
        self.assertEqual(len(routes) - 1, len(upstream.seen))
        self.assertNotIn("/v1/call", [seen.path for seen in upstream.seen])

    def test_an_unsafe_location_is_refused(self) -> None:
        """Credentials, other schemes, backslashes and missing or repeated headers are invalid."""
        upstream = self.upstream({
            "/v1/credentials": redirect("http://user:pass@127.0.0.1:1/v1/call"),
            "/v1/scheme": redirect("ftp://127.0.0.1/v1/call"),
            "/v1/backslash": redirect("/\\localhost/v1/call"),
            "/v1/userinfo-trick": redirect("http://127.0.0.1\\@localhost/v1/call"),
            "/v1/missing": answer(302, b""),
            "/v1/repeated": answer(302, b"", (("Location", "/v1/a"), ("Location", "/v1/b"))),
        })
        self.serve(upstream.url)
        for path in ("/v1/credentials", "/v1/scheme", "/v1/backslash", "/v1/userinfo-trick", "/v1/missing", "/v1/repeated"):
            reply = self.get(path)
            self.assertEqual((502, "redirect-invalid"), (reply.status, reply.code), path)
        self.assertEqual(6, len(upstream.seen))

    def test_a_redirect_outside_the_prefixes_is_refused(self) -> None:
        """A redirect cannot reach what a request could not, however the path is spelled."""
        upstream = self.upstream({
            "/v1/outside": redirect("/v2/call"), "/v1/dots": redirect("/v1/%2e%2e/v2"),
            "/v1/query": redirect("/v1/call?x=\"y\""), "/v2/call": answer(200, b"outside")})
        self.serve(upstream.url)
        for path in ("/v1/outside", "/v1/dots", "/v1/query"):
            reply = self.get(path)
            self.assertEqual((502, "redirect-path"), (reply.status, reply.code), path)
        self.assertNotIn("/v2/call", [seen.path for seen in upstream.seen])

    def test_a_post_follows_only_method_keeping_redirects(self) -> None:
        """307 and 308 resend the same body, and others refuse rather than drop it."""
        upstream = self.upstream({
            "/v1/307": redirect("/v1/done", 307), "/v1/308": redirect("/v1/done", 308),
            "/v1/301": redirect("/v1/done", 301), "/v1/302": redirect("/v1/done", 302),
            "/v1/303": redirect("/v1/done", 303), "/v1/done": answer(200, b"done")})
        self.serve(upstream.url)
        for status in ("307", "308"):
            self.assertEqual(b"done", self.post(f"/v1/{status}", b"payload").body)
            self.assertEqual(("POST", b"payload", SENTINEL),
                             (upstream.seen[-1].method, upstream.seen[-1].body, upstream.seen[-1].headers["x-api-key"]))
        for status in ("301", "302", "303"):
            before = len(upstream.seen)
            reply = self.post(f"/v1/{status}", b"payload")
            self.assertEqual((502, "redirect-method"), (reply.status, reply.code), status)
            self.assertEqual(before + 1, len(upstream.seen))

    def test_a_get_follows_every_redirect_status(self) -> None:
        """A relative location resolves against the current path, and the query survives."""
        routes = {f"/v1/{status}": redirect("done?page=2", status) for status in (301, 302, 303, 307, 308)}
        routes["/v1/done"] = answer(200, b"done")
        upstream = self.upstream(routes)
        self.serve(upstream.url)
        for status in (301, 302, 303, 307, 308):
            self.assertEqual(b"done", self.get(f"/v1/{status}").body, status)
            self.assertEqual("/v1/done?page=2", upstream.seen[-1].path)


class MainTest(unittest.TestCase):
    """The process entry point refuses bad configuration without echoing it."""

    def run_main(self, *arguments: str) -> tuple[int, str]:
        """Return the exit status of `main` and what it wrote to standard error."""
        stream = io.StringIO()
        with contextlib.redirect_stderr(stream):
            status = gateway.main(list(arguments))
        return status, stream.getvalue()

    def test_a_refused_file_exits_with_the_configuration_status(self) -> None:
        """Malformed content and a missing file both exit 78, naming no value."""
        with tempfile.TemporaryDirectory() as directory:
            bad = Path(directory) / "destinations.json"
            bad.write_text(json.dumps({"schema": gateway.SCHEMA, "destinations": [
                {"id": "x", "url": f"https://user:{SENTINEL}@provider.invalid", "path_prefixes": ["/"]}]}), encoding="utf-8")
            status, output = self.run_main("--config", str(bad), "--keys-dir", directory)
            self.assertEqual(gateway.CONFIG_STATUS, status)
            self.assertEqual("destinations[0].url must be an http or https origin without credentials",
                             json.loads(output)["reason"])
            self.assertNotIn(SENTINEL, output)
            status, output = self.run_main("--config", str(Path(directory) / SENTINEL), "--keys-dir", directory)
            self.assertEqual(gateway.CONFIG_STATUS, status)
            self.assertNotIn(SENTINEL, output)

    def test_health_fails_without_a_listener(self) -> None:
        """The health check is a real request, so an absent service fails it."""
        with socket.socket() as unused:
            unused.bind(("127.0.0.1", 0))
            port = unused.getsockname()[1]
        self.assertEqual(gateway.HEALTH_FAILED_STATUS, self.run_main("--health", "--port", str(port))[0])

    def test_health_passes_with_a_listener(self) -> None:
        """A serving gateway passes its health check even with no destination."""
        log = gateway.EventLog(io.StringIO())
        settings = gateway.parse_settings(json.dumps({"schema": gateway.SCHEMA, "destinations": []}))
        server = gateway.GatewayServer(("127.0.0.1", 0), gateway.Gateway(settings, {}, log))
        threading.Thread(target=server.serve_forever, kwargs={"poll_interval": POLL_SECONDS}, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.assertEqual(0, self.run_main("--health", "--port", str(server.server_address[1]))[0])


if __name__ == "__main__":
    unittest.main()
