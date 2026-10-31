"""Prove the running gateway's boundaries in its real image and networks, with a scripted upstream.

`test_gateway.py` holds the relay rules. These cases start the service as Compose renders it, in
a uniquely named project that is always removed, next to two fixtures: a scripted upstream on
the egress network and a prober on the private network, standing in for the engine. Nothing
leaves the machine and no real provider is involved. The key is a random sentinel, which must be
absent from the gateway's logs, replies, container metadata and process environment, and from
every layer of an image built from a context that deliberately holds it. The upstream's record
is the positive control showing that the key was really sent.

The real engine's route to the gateway and the database's lack of the sentinel are checked in
`test_infrastructure.py`, which starts the whole stack under `PACKAGING_DOCKER=1`. Here a host
with no `docker` executable follows `docker_requirement.py`, and any Docker failure fails.
The images are small, so hosted CI runs these cases, on whatever Compose 2.x it holds.
`LauncherStopTest` also drives the real launcher's stop against a running gateway.
"""

import gzip
import io
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tarfile
import tempfile
import textwrap
import time
from typing import BinaryIO
import unittest

from docker_requirement import requires_docker_cli
from test_launcher import BASH
from test_model_fetch_launcher import FLOOR_SHORTFALL
from test_model_server import default_routes


ROOT = Path(__file__).resolve().parents[2]
CONTEXT = ROOT / "docker" / "gateway"
SENTINEL = "sentinel-key-" + secrets.token_hex(16)
# Reserved for documentation (RFC 5737): never a real peer, whatever the host's network.
UNROUTED_ADDRESS = "192.0.2.1"
PROBE_TIMEOUT = 60
SCAN_CHUNK_BYTES = 1024 * 1024
ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"

# Runs in the fixture container. It answers scripted routes on three ports, one plain and two
# TLS, and reports through /__seen what the plain port's clients could not otherwise observe.
UPSTREAM = textwrap.dedent('''\
    import http.server, json, ssl, threading

    SEEN = []

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format, *args):
            pass

        def send(self, status, body=b"{}", headers=()):
            self.send_response(status)
            for name, value in headers:
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

        def serve(self):
            self.close_connection = True
            body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
            if self.path == "/__seen":
                return self.send(200, json.dumps(SEEN).encode())
            key = self.headers.get("x-api-key", "")
            SEEN.append({"port": self.server.server_address[1], "path": self.path, "key": key,
                         "cookie": self.headers.get("Cookie"), "bytes": len(body)})
            if self.path == "/v1/ok":
                self.send(200, b'{"reply":"scripted"}', (("Content-Type", "application/json"), ("Set-Cookie", "a=b")))
            elif self.path == "/v1/hop":
                self.send(302, b"", (("Location", "/v1/ok"),))
            elif self.path == "/v1/loop":
                self.send(302, b"", (("Location", "/v1/loop"),))
            elif self.path == "/v1/elsewhere":
                self.send(302, b"", (("Location", "http://scripted-other:8081/v1/ok"),))
            elif self.path == "/v1/echo":
                self.send(200, json.dumps({"echo": key}).encode())
            elif self.path == "/v1/denied":
                self.send(401, json.dumps({"error": "bad key " + key}).encode(), (("X-Echo", key),))
            elif self.path == "/v1/big":
                self.wfile.write(b"HTTP/1.1 200 OK\\r\\nConnection: close\\r\\n\\r\\n")
                for _ in range(4096):
                    self.wfile.write(b"x" * 4096)
            else:
                self.send(404)

        do_GET = do_POST = serve

    def start(port, certificate=None):
        server = http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler)
        server.handle_error = lambda request, address: None
        if certificate:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(f"/fixture/{certificate}.pem", f"/fixture/{certificate}.key")
            server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
        threading.Thread(target=server.serve_forever, daemon=True).start()

    start(8081)
    start(8443, "leaf")
    start(8444, "stranger")
    threading.Event().wait()
''')

# Runs in the fixture containers: one request, one JSON line, and no exception text.
PROBE = textwrap.dedent('''\
    import http.client, json, sys

    method, host, path, size = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
    connection = http.client.HTTPConnection(host, 8081 if host.startswith("scripted") else 8080, timeout=30)
    try:
        body = b"x" * size if method == "POST" else None
        connection.request(method, path, body=body, headers={"Cookie": "from=caller"})
        response = connection.getresponse()
        print(json.dumps({"status": response.status, "headers": dict(response.getheaders()),
                          "body": response.read().decode("utf-8", "replace")}))
    except Exception as failure:
        print(json.dumps({"failure": type(failure).__name__}))
''')

CERTIFICATES = r"""
set -e
cd /fixture
curve="-newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes"
openssl req -x509 $curve -keyout ca.key -out ca.pem -days 2 -subj /CN=gateway-check-ca \
  -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign
openssl req $curve -keyout leaf.key -out leaf.csr -subj /CN=scripted-upstream
printf 'subjectAltName=DNS:scripted-upstream\nauthorityKeyIdentifier=keyid\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth\n' > leaf.ext
openssl x509 -req -in leaf.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 2 -extfile leaf.ext -out leaf.pem
openssl req -x509 $curve -keyout stranger.key -out stranger.pem -days 2 -subj /CN=scripted-upstream \
  -addext subjectAltName=DNS:scripted-upstream
chmod 0644 ca.pem
"""


def stream_holds(source: BinaryIO, needle: bytes) -> bool:
    """Return whether a byte stream holds `needle`, including across read boundaries."""
    tail = b""
    while chunk := source.read(SCAN_CHUNK_BYTES):
        if needle in tail + chunk:
            return True
        tail = chunk[-(len(needle) - 1):]
    return False


def layers_hold(archive: Path, needle: bytes) -> bool:
    """Return whether any blob of a `docker image save` archive holds `needle`, once inflated.

    Depending on the host's image store, a layer is plain or compressed, so a raw scan of the
    archive could miss what a compressed layer holds. A compression this cannot read raises,
    so that an unreadable layer never passes as an empty one.
    """
    with tarfile.open(archive) as outer:
        for member in outer:
            if not member.isfile():
                continue
            blob = outer.extractfile(member)
            head = blob.read(4)
            blob.seek(0)
            if head.startswith(ZSTD_MAGIC):
                raise AssertionError(f"{member.name} is compressed in a way this scan cannot read")
            source = gzip.GzipFile(fileobj=blob) if head.startswith(b"\x1f\x8b") else blob
            if stream_holds(source, needle):
                return True
    return False


class LayerScanTest(unittest.TestCase):
    """The no-sentinel verdict depends on this scan, so it must see every layer form."""

    def archive(self, directory: str, blob: bytes) -> Path:
        """Return a tar archive holding one blob."""
        path = Path(directory) / "image.tar"
        with tarfile.open(path, "w") as output:
            member = tarfile.TarInfo("blobs/sha256/layer")
            member.size = len(blob)
            output.addfile(member, io.BytesIO(blob))
        return path

    def test_a_plain_or_compressed_layer_is_read(self) -> None:
        """The needle is found in both forms, also across a read boundary."""
        padded = b"x" * (SCAN_CHUNK_BYTES - 3) + b"needle" + b"y" * 10
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(layers_hold(self.archive(directory, padded), b"needle"))
            self.assertTrue(layers_hold(self.archive(directory, gzip.compress(padded)), b"needle"))
            self.assertFalse(layers_hold(self.archive(directory, gzip.compress(padded)), b"absent"))

    def test_an_unreadable_compression_fails(self) -> None:
        """A layer the scan cannot read must not count as holding nothing."""
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(AssertionError):
            layers_hold(self.archive(directory, ZSTD_MAGIC + b"data"), b"needle")


@requires_docker_cli
class GatewayContainerTest(unittest.TestCase):
    """Run the rendered service once for the whole class and probe it from both networks."""

    @classmethod
    def run_docker(cls, *arguments: str, timeout: int = 240, env: dict | None = None) -> subprocess.CompletedProcess:
        """Run Docker without a shell and return the outcome, whatever its exit code."""
        return subprocess.run(["docker", *arguments], capture_output=True, text=True, encoding="utf-8",
                              errors="replace", timeout=timeout, check=False, env=env or cls.environment)

    @classmethod
    def docker(cls, *arguments: str, timeout: int = 240) -> str:
        """Run Docker and return stdout, or fail with bounded output that the sentinel cannot reach."""
        result = cls.run_docker(*arguments, timeout=timeout)
        if result.returncode != 0:
            raise AssertionError((result.stdout[-2000:] + result.stderr[-2000:]).replace(SENTINEL, "<sentinel>"))
        return result.stdout

    @classmethod
    def destinations(cls) -> dict:
        """Return the allowlist of this project: one fixture server under several names."""
        shared = {"path_prefixes": ["/v1"], "key": {"file": "scripted", "header": "x-api-key"},
                  "max_request_bytes": 1024, "max_response_bytes": 65536, "max_redirects": 2, "timeout_seconds": 10}
        return {"schema": "rpg-engine.gateway-destinations/1", "destinations": [
            {"id": "plain", "url": "http://scripted-upstream:8081", "plaintext_key": True, **shared},
            {"id": "tls", "url": "https://scripted-upstream:8443", **shared},
            {"id": "untrusted", "url": "https://scripted-upstream:8444", **shared},
            {"id": "mismatched", "url": "https://scripted-other:8443", **shared},
            {**shared, "id": "keyless", "url": "http://scripted-upstream:8081", "plaintext_key": True,
             "key": {"file": "absent", "header": "x-api-key"}},
        ]}

    @classmethod
    def setUpClass(cls) -> None:
        """Prepare the fixtures and start the service, the upstream and the prober."""
        directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(directory.cleanup)
        cls.root = Path(directory.name)
        cls.root.chmod(0o755)
        fixture = cls.root / "fixture"
        keys = cls.root / "keys"
        fixture.mkdir()
        keys.mkdir()
        # The gateway's own account must be able to read its inputs on any host.
        fixture.chmod(0o755)
        keys.chmod(0o755)
        (fixture / "upstream.py").write_text(UPSTREAM, encoding="utf-8", newline="\n")
        (fixture / "probe.py").write_text(PROBE, encoding="utf-8", newline="\n")
        key = keys / "scripted"
        key.write_text(SENTINEL + "\n", encoding="ascii", newline="\n")
        key.chmod(0o644)
        cls.config_file = cls.root / "destinations.json"
        cls.config_file.write_text(json.dumps(cls.destinations()), encoding="utf-8", newline="\n")
        cls.config_file.chmod(0o644)
        cls.environment = {name: value for name, value in os.environ.items() if name != "COMPOSE_PROFILES"}
        cls.environment["GATEWAY_KEYS_DIR"] = str(keys)
        cls.environment["GATEWAY_DESTINATIONS_FILE"] = str(cls.config_file)
        cls.project = "engine-gateway-check-" + secrets.token_hex(4)
        cls.base_compose = ["compose", "--project-name", cls.project, "--env-file", str(ROOT / ".env.example"),
                            "--profile", "gateway", "--file", str(ROOT / "compose.yaml")]
        rendered = json.loads(cls.docker(*cls.base_compose, "config", "--format", "json", timeout=30))
        cls.rendered = rendered
        base_image = re.search(r"^FROM (\S+)$", (CONTEXT / "Dockerfile").read_text(encoding="utf-8"), re.M).group(1)
        cls.docker("run", "--rm", "--network", "none", "--volume", f"{fixture}:/fixture", "--entrypoint", "sh",
                   base_image, "-c", CERTIFICATES)
        fixture_mount = {"type": "bind", "source": str(fixture), "target": "/fixture", "read_only": True}
        # JSON is YAML, and it quotes any host path safely.
        override = {"services": {
            "gateway": {
                # The real store stays in place; this adds only the fixture authority to verify against.
                "environment": {"SSL_CERT_FILE": "/fixture-ca/ca.pem"},
                "volumes": [{"type": "bind", "source": str(fixture / "ca.pem"), "target": "/fixture-ca/ca.pem", "read_only": True}]},
            "scripted-upstream": {
                "image": base_image, "profiles": ["gateway"], "entrypoint": ["python", "-I", "-B", "/fixture/upstream.py"],
                "networks": {"gateway_egress": {"aliases": ["scripted-other"]}}, "volumes": [fixture_mount]},
            "prober": {
                "image": base_image, "profiles": ["gateway"], "entrypoint": ["sleep", "infinity"],
                "networks": ["gateway"], "volumes": [fixture_mount]},
        }}
        override_file = cls.root / "override.yaml"
        override_file.write_text(json.dumps(override), encoding="utf-8", newline="\n")
        cls.compose = [*cls.base_compose, "--file", str(override_file)]
        cls.addClassCleanup(cls.run_docker, *cls.compose, "down", "--timeout", "10")
        started = cls.run_docker(*cls.compose, "up", "--detach", "--build", "--wait", "--wait-timeout", "120",
                                 "gateway", "scripted-upstream", "prober", timeout=420)
        if started.returncode != 0:
            # Capture the service's own startup diagnostics before cleanup removes it.
            logs = cls.run_docker(*cls.compose, "logs", "--no-color", "--tail", "40", "gateway", timeout=30)
            raise AssertionError((started.stderr[-1500:] + logs.stdout[-3000:] + logs.stderr[-1000:]).replace(SENTINEL, "<sentinel>"))

    def probe(self, service: str, method: str, host: str, path: str, size: int = 0) -> dict:
        """Send one request from inside a fixture container and return its JSON report."""
        output = self.docker(*self.compose, "exec", "-T", service, "python", "-I", "-B", "/fixture/probe.py",
                             method, host, path, str(size), timeout=PROBE_TIMEOUT)
        return json.loads(output)

    def relay(self, destination: str, path: str, method: str = "GET", size: int = 0) -> dict:
        """Relay one call through the gateway from the private network."""
        return self.probe("prober", method, "gateway", f"/upstream/{destination}{path}", size)

    def seen(self) -> list[dict]:
        """Return what the upstream recorded. The sentinel appears here by design: it is the control."""
        return json.loads(self.probe("scripted-upstream", "GET", "scripted-upstream", "/__seen")["body"])

    def in_gateway(self, *command: str) -> subprocess.CompletedProcess:
        """Run one command in the running service, as its own configured user."""
        return self.run_docker(*self.compose, "exec", "-T", "gateway", *command, timeout=PROBE_TIMEOUT)

    def inspect_gateway(self) -> dict:
        """Return the running gateway's Docker metadata."""
        identifier = self.docker(*self.compose, "ps", "--quiet", "gateway").strip()
        return json.loads(self.docker("inspect", identifier))[0]

    def assert_no_sentinel_outside_the_gateway_memory(self) -> None:
        """Require the key to be absent from everything observable about the service."""
        logs = self.run_docker(*self.compose, "logs", "--no-color", "gateway", timeout=PROBE_TIMEOUT)
        self.assertEqual(0, logs.returncode)
        self.assertIn('"event":"started"', logs.stdout + logs.stderr)
        self.assertNotIn(SENTINEL, logs.stdout + logs.stderr, "gateway logs")
        self.assertNotIn(SENTINEL, json.dumps(self.inspect_gateway()), "container metadata")
        self.assertNotIn(SENTINEL, self.docker(*self.compose, "config", "--format", "json", timeout=30), "rendered Compose")
        environment = self.in_gateway("python", "-I", "-B", "-c",
                                      "print(open('/proc/1/environ','rb').read(), open('/proc/1/cmdline','rb').read())")
        self.assertEqual(0, environment.returncode, environment.stderr[-500:])
        self.assertNotIn(SENTINEL, environment.stdout, "process environment and arguments")

    def test_the_service_is_confined_as_rendered(self) -> None:
        """Require the rendered boundaries on the real container and from inside it."""
        state = self.inspect_gateway()
        private, egress = self.project + "_gateway", self.project + "_gateway_egress"
        self.assertEqual({private, egress}, set(state["NetworkSettings"]["Networks"]))
        self.assertIs(True, json.loads(self.docker("network", "inspect", private))[0]["Internal"])
        self.assertIs(False, json.loads(self.docker("network", "inspect", egress))[0]["Internal"])
        self.assertFalse(state["HostConfig"]["PortBindings"])
        self.assertFalse(any(state["NetworkSettings"]["Ports"].values()))
        self.assertEqual("10002:10002", state["Config"]["User"])
        self.assertTrue(state["HostConfig"]["ReadonlyRootfs"])
        self.assertEqual(["ALL"], state["HostConfig"]["CapDrop"])
        self.assertIn("no-new-privileges:true", state["HostConfig"]["SecurityOpt"])
        mounts = {mount["Destination"]: mount for mount in state["Mounts"]}
        self.assertEqual({"/etc/gateway/destinations.json", "/run/secrets/gateway", "/fixture-ca/ca.pem"}, set(mounts))
        self.assertFalse(any(mount["RW"] for mount in mounts.values()))
        self.assertEqual("10002", self.in_gateway("id", "-u").stdout.strip())
        # Keys, the allowlist and the root filesystem all refuse writes, and there is no tmpfs.
        for path in ("/run/secrets/gateway/probe", "/etc/gateway/probe", "/opt/gateway/probe", "/tmp/probe"):
            refused = self.in_gateway("touch", path)
            self.assertNotEqual(0, refused.returncode, path)
        self.assertEqual(0, self.in_gateway("test", "-s", "/etc/ssl/certs/ca-certificates.crt").returncode)

    def test_only_the_private_network_reaches_the_service(self) -> None:
        """The prober gets an answer, and a peer on the egress network gets none."""
        self.assertEqual(200, self.probe("prober", "GET", "gateway", "/healthz")["status"])
        before = len(self.seen())
        refused = self.probe("scripted-upstream", "GET", "gateway", "/upstream/plain/v1/ok")
        self.assertIn("failure", refused, refused)
        self.assertEqual(before, len(self.seen()))
        logs = self.docker(*self.compose, "logs", "--no-color", "gateway", timeout=PROBE_TIMEOUT)
        self.assertIn('"event":"peer-refused"', logs)

    def test_routes_match_the_declared_topology(self) -> None:
        """The prober, like the engine there, gets no way off the private network; the gateway does."""
        read_routes = ("import os\nprint(open('/proc/net/route').read())\n"
                       "print('=====')\n"
                       "print(open('/proc/net/ipv6_route').read() if os.path.exists('/proc/net/ipv6_route') else '')")
        prober = self.docker(*self.compose, "exec", "-T", "prober", "python", "-I", "-B", "-c", read_routes)
        ipv4, _, ipv6 = prober.partition("=====")
        self.assertEqual([], default_routes(ipv4.strip(), ipv6.strip()))
        attempt = self.docker(*self.compose, "exec", "-T", "prober", "python", "-I", "-B", "-c",
                              "import socket\ntry:\n    socket.create_connection(('" + UNROUTED_ADDRESS + "', 80), 5)\n"
                              "    print('connected')\nexcept TimeoutError:\n    print('timeout')\n"
                              "except OSError:\n    print('refused')\n")
        self.assertEqual("refused", attempt.strip(), "an unrouted packet must fail at once, not leave the container")
        gateway_routes = self.in_gateway("cat", "/proc/net/route")
        self.assertEqual(0, gateway_routes.returncode, gateway_routes.stderr[-500:])
        self.assertEqual(1, len(default_routes(gateway_routes.stdout, "")))

    def test_the_relay_enforces_its_rules_in_the_real_networks(self) -> None:
        """Positive control and refusals together: the key is sent, and only where it may go."""
        reply = self.relay("plain", "/v1/ok")
        self.assertEqual((200, '{"reply":"scripted"}'), (reply["status"], reply["body"]))
        self.assertNotIn("Set-Cookie", reply["headers"])
        recorded = self.seen()[-1]
        self.assertEqual((8081, "/v1/ok", SENTINEL, None),
                         (recorded["port"], recorded["path"], recorded["key"], recorded["cookie"]))
        self.assertEqual(200, self.relay("plain", "/v1/hop")["status"])
        self.assertEqual("/v1/ok", self.seen()[-1]["path"])
        self.assertEqual(200, self.relay("plain", "/v1/ok", "POST", 1024)["status"])
        self.assertEqual(1024, self.seen()[-1]["bytes"])

        expected = {"/v1/loop": (502, "redirect-limit"), "/v1/elsewhere": (502, "redirect-cross-origin"),
                    "/v1/big": (502, "response-too-large"), "/v2/ok": (403, "path-not-allowed")}
        for path, (status, code) in expected.items():
            reply = self.relay("plain", path)
            self.assertEqual((status, code), (reply["status"], json.loads(reply["body"])["code"]), path)
        before = len(self.seen())
        too_large = self.relay("plain", "/v1/ok", "POST", 1025)
        self.assertEqual((413, "request-too-large"), (too_large["status"], json.loads(too_large["body"])["code"]))
        self.assertEqual(403, self.relay("not-listed", "/v1/ok")["status"])
        self.assertEqual(503, self.relay("keyless", "/v1/ok")["status"])
        self.assertEqual(before, len(self.seen()), "refused calls must contact nothing")

    def test_tls_verifies_the_chain_and_the_hostname(self) -> None:
        """A trusted certificate is accepted, while a stranger or a wrong name never gets a request."""
        reply = self.relay("tls", "/v1/ok")
        self.assertEqual((200, '{"reply":"scripted"}'), (reply["status"], reply["body"]))
        recorded = self.seen()[-1]
        self.assertEqual((8443, SENTINEL), (recorded["port"], recorded["key"]))
        before = len(self.seen())
        for destination in ("untrusted", "mismatched"):
            refused = self.relay(destination, "/v1/ok")
            self.assertEqual((502, "upstream-tls"), (refused["status"], json.loads(refused["body"])["code"]), destination)
        self.assertEqual(before, len(self.seen()), "the key must not cross an unverified connection")

    def test_sentinels_stay_absent_after_every_path_that_could_leak(self) -> None:
        """Echoed and failing answers are driven first, so these checks cover the worst output."""
        self.assertEqual(200, self.relay("plain", "/v1/ok")["status"])
        recorded = self.seen()[-1]
        self.assertEqual(SENTINEL, recorded["key"], "control: the upstream must really have held the key")
        echoed = self.relay("plain", "/v1/echo")
        self.assertEqual((502, "response-refused"), (echoed["status"], json.loads(echoed["body"])["code"]))
        denied = self.relay("plain", "/v1/denied")
        self.assertEqual(401, denied["status"])
        self.assertEqual({"code": "upstream-status", "status": 401}, json.loads(denied["body"]))
        for reply in (echoed, denied, self.relay("plain", "/v2/none"), self.relay("untrusted", "/v1/ok")):
            self.assertNotIn(SENTINEL, json.dumps(reply))
        self.assert_no_sentinel_outside_the_gateway_memory()

    def test_a_bad_allowlist_refuses_to_start_without_echoing_it(self) -> None:
        """A malformed allowlist exits 78 and shows no value.

        A missing host path is not run here, because refusing it depends on the daemon: on
        Docker Desktop for Windows `create_host_path: false` did not stop the container. The
        rendered setting is in `test_compose.py`, and with no key file a destination answers
        `key-unavailable`, as the `keyless` destination shows in the relay case.
        """
        bad = self.root / "bad-destinations.json"
        bad.write_text(json.dumps({"schema": "rpg-engine.gateway-destinations/1", "destinations": [
            {"id": "x", "url": f"https://user:{SENTINEL}@provider.invalid", "path_prefixes": ["/"]}]}),
            encoding="utf-8", newline="\n")
        bad.chmod(0o644)
        environment = dict(self.environment, GATEWAY_DESTINATIONS_FILE=str(bad))
        refused = self.run_docker(*self.compose, "run", "--rm", "--no-deps", "-T", "gateway", env=environment)
        self.assertEqual(78, refused.returncode, refused.stderr[-500:].replace(SENTINEL, "<sentinel>"))
        self.assertNotIn(SENTINEL, refused.stdout + refused.stderr)
        self.assertIn("startup-refused", refused.stdout + refused.stderr)

    def test_a_stop_signal_ends_the_service_cleanly(self) -> None:
        """As PID 1 it would ignore SIGTERM by default, and Docker would have to kill it."""
        started = time.monotonic()
        self.docker(*self.compose, "stop", "--timeout", "15", "gateway")
        elapsed = time.monotonic() - started
        identifier = self.docker(*self.compose, "ps", "--all", "--quiet", "gateway").strip()
        self.assertEqual(0, json.loads(self.docker("inspect", identifier))[0]["State"]["ExitCode"])
        self.assertLess(elapsed, 12)
        self.docker(*self.compose, "up", "--detach", "--wait", "--wait-timeout", "60", "gateway", timeout=180)
        self.assertEqual(200, self.probe("prober", "GET", "gateway", "/healthz")["status"])

    def test_no_image_layer_holds_the_sentinel(self) -> None:
        """Build with the key in the context, as a careless copy would, and scan every layer.

        A control string from the gateway's source shows the scan really read the layers.
        """
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            context = workspace / "context"
            shutil.copytree(CONTEXT, context)
            (context / "keys").mkdir()
            (context / "keys" / "scripted").write_text(SENTINEL, encoding="ascii")
            (context / "destinations.json").write_text(json.dumps(self.destinations()) + SENTINEL, encoding="utf-8")
            (context / ".env").write_text(f"KEY={SENTINEL}\n", encoding="ascii")
            image = "rpg-engine/gateway-sentinel-check:" + secrets.token_hex(4)
            self.addCleanup(self.run_docker, "image", "rm", "--force", image)
            self.docker("build", "--quiet", "--tag", image, str(context), timeout=300)
            self.assertNotIn(SENTINEL, self.docker("image", "inspect", image), "image configuration")
            self.assertNotIn(SENTINEL, self.docker("history", "--no-trunc", image), "image history")
            archive = workspace / "image.tar"
            self.docker("image", "save", "--output", str(archive), image, timeout=300)
            self.assertTrue(layers_hold(archive, b"redirect-cross-origin"), "control: the scan must read the layers")
            self.assertFalse(layers_hold(archive, SENTINEL.encode("ascii")), "image layers")


@requires_docker_cli
class LauncherStopTest(unittest.TestCase):
    """The real launcher in a temporary copy, with its own identity, against a real gateway.

    The launcher enforces Engine 29.0 and Compose 5.0, which hosted CI may lack. Below them the
    case asserts the launcher's refusal and returns, as in `test_model_fetch_launcher.py`, and
    `test_launcher.py` still proves there that the stop command selects the gateway's profile.
    """

    def setUp(self) -> None:
        """Prepare a copy that owns a unique Compose project, removed whatever the outcome."""
        directory = tempfile.TemporaryDirectory(prefix="launcher gateway ")
        self.addCleanup(directory.cleanup)
        self.copy = Path(directory.name)
        self.copy.chmod(0o755)
        for name in ("start.sh", "compose.yaml"):
            shutil.copyfile(ROOT / name, self.copy / name)
        shutil.copyfile(ROOT / ".env.example", self.copy / ".env")
        shutil.copytree(CONTEXT, self.copy / "docker" / "gateway")
        (self.copy / ".local").mkdir()
        identity = secrets.token_hex(6)
        (self.copy / ".local" / "installation-id").write_text(identity + "\n", encoding="ascii", newline="\n")
        self.project = "rpg-engine-" + identity
        cleared = {"APP_PORT", "POSTGRES_PASSWORD_FILE", "COMPOSE_PROFILES", "GATEWAY_KEYS_DIR", "GATEWAY_DESTINATIONS_FILE"}
        # The launcher runs with the inputs' default paths, which do not exist in this copy: a
        # stop must not depend on them, because an installation without a gateway has neither.
        self.environment = {**{name: value for name, value in os.environ.items() if name not in cleared},
                            "COMPOSE_PROJECT_NAME": self.project}

    def run_launcher_stop(self) -> subprocess.CompletedProcess:
        """Run the copy's `--stop` from another directory and return the outcome."""
        return subprocess.run([BASH, str(self.copy / "start.sh"), "--stop"], cwd=tempfile.gettempdir(), env=self.environment,
                              capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120, check=False)

    def start_gateway(self) -> None:
        """Start the service as the documentation says to, on empty fixture inputs."""
        keys = self.copy / "fixture-keys"
        keys.mkdir()
        keys.chmod(0o755)
        destinations = self.copy / "fixture-destinations.json"
        destinations.write_text(json.dumps({"schema": "rpg-engine.gateway-destinations/1", "destinations": []}),
                                encoding="utf-8", newline="\n")
        destinations.chmod(0o644)
        environment = {**self.environment, "GATEWAY_KEYS_DIR": str(keys), "GATEWAY_DESTINATIONS_FILE": str(destinations)}
        command = ["docker", "compose", "--file", "compose.yaml", "--profile", "gateway"]

        def run(*arguments: str, timeout: int) -> subprocess.CompletedProcess:
            return subprocess.run([*command, *arguments], cwd=self.copy, env=environment, capture_output=True, text=True,
                                  encoding="utf-8", errors="replace", timeout=timeout, check=False)

        self.addCleanup(run, "down", "--timeout", "10", timeout=120)
        started = run("up", "--detach", "--build", "--wait", "--wait-timeout", "120", "gateway", timeout=420)
        self.assertEqual(0, started.returncode, started.stdout[-2000:] + started.stderr[-2000:])

    def owned(self, kind: str) -> list[str]:
        """Return this project's containers (running or not) or networks, by their Compose label."""
        listing = ("ps", "--all") if kind == "container" else ("network", "ls")
        result = subprocess.run(["docker", *listing, "--quiet", "--filter", f"label=com.docker.compose.project={self.project}"],
                                capture_output=True, text=True, timeout=60, check=False)
        self.assertEqual(0, result.returncode, result.stderr[-1000:])
        return result.stdout.split()

    def test_the_launcher_stop_removes_a_running_gateway(self) -> None:
        """Nothing survives: left running, the service keeps its keys in memory and its egress network."""
        if FLOOR_SHORTFALL is not None:
            refused = self.run_launcher_stop()
            self.assertEqual(1, refused.returncode, FLOOR_SHORTFALL + refused.stdout[-2000:] + refused.stderr[-2000:])
            self.assertIn("older than the required", refused.stderr)
            return
        self.start_gateway()
        self.assertEqual(1, len(self.owned("container")), "control: the gateway must be running before the stop")
        self.assertEqual(2, len(self.owned("network")), "control: its two networks must exist before the stop")
        stopped = self.run_launcher_stop()
        self.assertEqual(0, stopped.returncode, stopped.stdout[-2000:] + stopped.stderr[-2000:])
        self.assertEqual([], self.owned("container"))
        self.assertEqual([], self.owned("network"))
        again = self.run_launcher_stop()
        self.assertEqual(0, again.returncode, again.stdout[-2000:] + again.stderr[-2000:])


if __name__ == "__main__":
    unittest.main()
