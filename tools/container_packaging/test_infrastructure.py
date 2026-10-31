"""Exercise account-free infrastructure recreation when PACKAGING_DOCKER=1.

Inputs are tracked Compose settings and a locally generated fixture password.
The test starts only its dedicated project, writes neutral persistence markers,
and always stops owned services. Named volumes and their private password file
are deliberately retained. Docker, health, permission or persistence errors fail.
"""

import json
import hashlib
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest
from urllib.request import ProxyHandler, build_opener
from urllib.error import HTTPError
from urllib.parse import urljoin, urlparse
import re


ROOT = Path(__file__).resolve().parents[2]
PROJECT = "engine-packaged-shell-check"
STATE = ROOT / ".local" / "packaged-shell-check"
SOCKET = "/tmp/codex-daemon-10001/bd2fc4d5dd4ec410275f7d5460680ca9c0ce1ef1b48e49b182f350ff18153681"


@unittest.skipUnless(os.environ.get("PACKAGING_DOCKER") == "1", "Set PACKAGING_DOCKER=1 for service recreation")
class InfrastructureTest(unittest.TestCase):
    """Test real isolated services, with no account or gameplay/inference request."""

    def compose(self, *arguments: str, timeout: int = 180) -> str:
        """Run fixture-only Compose arguments; return stdout or fail with bounded stderr."""
        result = subprocess.run(
            ["docker", "compose", "--project-name", PROJECT,
             "--env-file", str(ROOT / ".env.example"),
             "--file", str(ROOT / "compose.yaml"), *arguments],
            env=self.environment, capture_output=True, text=True,
            timeout=timeout, check=False,
        )
        diagnostic = result.stderr[-3000:]
        if result.returncode and arguments[0] == "up":
            # Capture fixture-only startup diagnostics before cleanup removes
            # failed services. No account is loaded and secrets are never logged.
            logs = subprocess.run(
                ["docker", "compose", "--project-name", PROJECT, "--file", str(ROOT / "compose.yaml"),
                 "logs", "--no-color", "--tail", "45", "engine"],
                env=self.environment, capture_output=True, text=True, timeout=30, check=False,
            )
            diagnostic += "\n" + logs.stdout[-6000:]
        self.assertEqual(0, result.returncode, diagnostic)
        return result.stdout.strip()

    def database(self, statement: str) -> str:
        """Execute neutral SQL over password-authenticated TCP; fail on SQL/auth errors.

        Read the fixture password inside postgres, never into host command arguments
        or output. A local Unix-socket trust connection would not test the secret.
        """
        return self.compose(
            "exec", "-T", "postgres", "sh", "-ec",
            'export PGPASSWORD="$(cat /run/secrets/postgres_password)"; '
            'exec psql -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -Atc "$1"',
            "fixture", statement,
        )

    def inspect(self, service: str, *selection: str) -> dict:
        """Return one owned container's Docker metadata; missing/invalid metadata fails.

        `selection` holds the Compose arguments that select a service behind a profile.
        """
        identifier = self.compose(*selection, "ps", "--quiet", service)
        result = subprocess.run(["docker", "inspect", identifier], capture_output=True,
                                text=True, timeout=30, check=False)
        self.assertEqual(0, result.returncode, result.stderr[-1000:])
        return json.loads(result.stdout)[0]

    def verify_http(self) -> None:
        """Fetch packaged UI/health over published loopback; leaks or HTTP errors fail.

        Inputs are the owned engine's dynamic host port and compiled asset URLs.
        No proxy or owner service is contacted; only neutral response bytes remain.
        """
        engine = self.inspect("engine")
        binding = engine["NetworkSettings"]["Ports"]["8080/tcp"]
        self.assertEqual(1, len(binding))
        self.assertEqual("127.0.0.1", binding[0]["HostIp"])
        origin = f'http://127.0.0.1:{binding[0]["HostPort"]}/'
        client = build_opener(ProxyHandler({}))
        with client.open(origin, timeout=10) as response:
            self.assertIn("text/html", response.headers["Content-Type"])
            index = response.read().decode("utf-8")
        self.assertIn("<title>Adventure engine</title>", index)
        self.assertIn('href="/en/"', index)
        self.assertIn('href="/it/"', index)
        assets = ["locale-entry.js", "3rdpartylicenses.txt", "actuator/health", "ui/LICENSE.txt"]
        for locale in ("en", "it"):
            with client.open(urljoin(origin, f"{locale}/settings"), timeout=10) as response:
                localized = response.read().decode("utf-8")
                self.assertEqual("no-store", response.headers["Cache-Control"])
            self.assertIn(f'<base href="/{locale}/">', localized)
            self.assertIn(f'lang="{locale}"', localized)
            bundles = re.findall(r'(?:src|href)="([^"]+\.(?:js|css))"', localized)
            self.assertGreaterEqual(len(bundles), 2)
            for bundle in bundles:
                self.assertRegex(bundle, r"^[a-zA-Z0-9_-]+-[a-zA-Z0-9]+\.(js|css)$")
                assets.append(f"{locale}/{bundle}")
            assets.extend(f"{locale}/{path}" for path in
                          ("3rdpartylicenses.txt", "ui/LICENSE.txt", "ui/README.md",
                           "portraits/keeper.svg", "scenes/woodland.svg"))
        hashes = {}
        for asset in assets:
            url = urljoin(origin, asset)
            self.assertEqual(urlparse(origin).netloc, urlparse(url).netloc)
            with self.subTest(resource=asset), client.open(url, timeout=10) as response:
                body = response.read()
                self.assertTrue(body)
                if asset.endswith(".js"):
                    self.assertIn("javascript", response.headers["Content-Type"])
                elif asset.endswith(".css"):
                    self.assertIn("text/css", response.headers["Content-Type"])
                if asset != "actuator/health":
                    hashes[asset] = hashlib.sha256(body).hexdigest()
                if asset == "actuator/health":
                    health = json.loads(body)
                    # Status-only: group names are allowed, components and details are not.
                    self.assertEqual("UP", health["status"])
                    self.assertLessEqual(set(health), {"status", "groups"})
        # Compare actual production resource bytes across recreation; port changes
        # are irrelevant, while a stale/missing locale tree must fail the fixture.
        if hasattr(self, "resource_hashes"):
            self.assertEqual(self.resource_hashes, hashes)
        self.resource_hashes = hashes
        for path in ("api/missing", ".env", "auth.json", "run/secrets/postgres_password",
                     "actuator/env", "var/lib/engine/content/.packaging-check",
                     "api/v1/missing", "fr/settings", "en/missing.js", "it/missing.css",
                     "en/api/v1/missing", "it/.env", "it/unknown",
                     "it/adventures/relay/unknown"):
            with self.assertRaises(HTTPError) as error:
                client.open(urljoin(origin, path), timeout=10)
            try:
                self.assertEqual(404, error.exception.code, path)
            finally:
                error.exception.close()
        if os.environ.get("PACKAGING_BROWSER") == "1" and not hasattr(self, "browser_checked"):
            # Opt-in host Chrome exercises real compiled reload and root preference
            # routing against this owned container; its fresh profile is removed.
            result = subprocess.run(
                ["node", "tools/browser-smoke.mjs", "--locales", f"--packaged-origin={origin.rstrip('/') }"],
                cwd=ROOT / "frontend", capture_output=True, text=True, timeout=180, check=False,
            )
            self.assertEqual(0, result.returncode, (result.stdout + result.stderr)[-4000:])
            self.browser_checked = True

    def verify_backend(self) -> None:
        """Run all backend tests in the pinned JDK target against a separate fixture DB.

        Inputs are neutral database labels and a read-only fixture secret mount;
        no password value enters arguments/environment. Build/test failures fail
        with bounded output. The disposable test container is always removed.
        """
        image = "rpg-engine/packaging-verification:0.1.0"
        result = subprocess.run(
            ["docker", "build", "--quiet", "--target", "verification", "--tag", image,
             "--file", str(ROOT / "docker/engine/Dockerfile"), str(ROOT)],
            capture_output=True, text=True, timeout=300, check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr[-3000:])
        # Keep the public-schema scaffold assertion independent of the durable
        # marker table in the infrastructure database, including on repeat runs.
        self.database("DROP DATABASE IF EXISTS packaging_backend_checks")
        self.database("CREATE DATABASE packaging_backend_checks")
        try:
            result = subprocess.run(
                ["docker", "run", "--rm", "--network", PROJECT + "_data",
                 "--mount", f"type=bind,source={STATE / 'postgres-password'},target=/run/secrets/postgres_password,readonly",
                 "--env", "SPRING_PROFILES_ACTIVE=packaged",
                 "--env", "SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/packaging_backend_checks",
                 "--env", "SPRING_DATASOURCE_USERNAME=engine", image],
                capture_output=True, text=True, timeout=600, check=False,
            )
            self.assertEqual(0, result.returncode, (result.stdout + result.stderr)[-4000:])
            # The suite grows with the roadmap. Require the actual final Maven
            # summary, rather than freezing the count at the original shell tests.
            summaries = re.findall(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)", result.stdout)
            self.assertTrue(summaries, "Maven must report the executed test suite")
            self.assertGreater(int(summaries[-1][0]), 0)
            self.assertEqual(("0", "0", "0"), summaries[-1][1:])
        finally:
            self.database("DROP DATABASE packaging_backend_checks")

    def verify_missing_secret(self) -> None:
        """Require the built jar to reject a missing config tree before healthy startup."""
        result = subprocess.run(
            ["docker", "compose", "--project-name", PROJECT,
             "--env-file", str(ROOT / ".env.example"), "--file", str(ROOT / "compose.yaml"),
             "run", "--rm", "--no-deps", "engine", "--engine.secrets-directory=/missing/"],
            env=self.environment, capture_output=True, text=True, timeout=60, check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("does not exist", result.stdout + result.stderr)

    def service_output(self, service: str, *command: str, check: bool = True) -> str:
        """Run one command in a service and return its output as UTF-8 text, for scanning.

        This does not use `compose()`, which decodes with the host's locale: a database dump
        holds text that some locales cannot decode.
        """
        result = subprocess.run(
            ["docker", "compose", "--project-name", PROJECT, "--env-file", str(ROOT / ".env.example"),
             "--file", str(ROOT / "compose.yaml"), "--profile", "gateway", "exec", "-T", service, *command],
            env=self.environment, capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=180, check=False,
        )
        if check:
            self.assertEqual(0, result.returncode, result.stderr[-2000:])
        return result.stdout

    def verify_gateway_boundary(self) -> None:
        """Select the gateway beside the real engine: reachable from it alone, its key nowhere else.

        A sentinel key is mounted while the real engine, database and runtime run. The engine
        has no adapter for the gateway and the launcher has no key command yet, so no code path
        can carry a key into the database today. The dump shows that holds with a key present,
        and the check must stay meaningful once such a path exists.
        """
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        fixture = Path(directory.name)
        fixture.chmod(0o755)
        keys = fixture / "keys"
        keys.mkdir()
        keys.chmod(0o755)
        sentinel = "sentinel-key-" + secrets.token_hex(16)
        key = keys / "fixture"
        key.write_text(sentinel, encoding="ascii", newline="\n")
        key.chmod(0o644)
        destinations = fixture / "destinations.json"
        destinations.write_text(json.dumps({"schema": "rpg-engine.gateway-destinations/1", "destinations": [
            {"id": "fixture", "url": "https://provider.invalid", "path_prefixes": ["/v1"],
             "key": {"file": "fixture", "header": "x-api-key"}}]}), encoding="utf-8", newline="\n")
        destinations.chmod(0o644)
        self.environment = dict(self.environment, GATEWAY_KEYS_DIR=str(keys), GATEWAY_DESTINATIONS_FILE=str(destinations))
        # Without the profile, `down` would miss this service and the network only it uses.
        self.addCleanup(self.compose, "--profile", "gateway", "down", "--timeout", "30")
        self.compose("--profile", "gateway", "up", "--detach", "--build", "--wait", "--wait-timeout", "90", "gateway",
                     timeout=420)
        gateway = self.inspect("gateway", "--profile", "gateway")
        self.assertEqual({PROJECT + "_gateway", PROJECT + "_gateway_egress"}, set(gateway["NetworkSettings"]["Networks"]))
        self.assertNotIn(PROJECT + "_gateway_egress", self.inspect("engine")["NetworkSettings"]["Networks"])
        # The real engine container reaches it, and an unlisted destination is refused there too.
        self.assertEqual('{"status":"ok"}', self.service_output(
            "engine", "curl", "--silent", "--show-error", "--fail", "--max-time", "10", "http://gateway:8080/healthz"))
        self.assertEqual('{"code":"destination-not-allowed"}', self.service_output(
            "engine", "curl", "--silent", "--show-error", "--max-time", "10", "http://gateway:8080/upstream/other/v1"))
        self.assertEqual("", self.service_output("engine", "sh", "-ec", "test ! -e /run/secrets/gateway"))
        # Budget counts live in the gateway's own volume, which its account can write and no other service mounts.
        self.assertEqual("10002 700", self.service_output("gateway", "stat", "-c", "%u %a", "/var/lib/gateway").strip())
        self.assertEqual("", self.service_output("engine", "sh", "-ec", "test ! -e /var/lib/gateway"))
        # The gateway resolves its one peer and neither the database nor the runtime; they do not resolve it.
        names = ("import socket\nfor name in ('engine', 'postgres', 'codex'):\n    try:\n"
                 "        socket.getaddrinfo(name, 80)\n        print(name)\n    except OSError:\n        pass\n")
        self.assertEqual("engine", self.service_output("gateway", "python", "-I", "-B", "-c", names).strip())
        for service in ("postgres", "codex"):
            self.assertEqual("", self.service_output(service, "sh", "-ec", "! getent hosts gateway"), service)
        dump = self.service_output("postgres", "sh", "-ec", 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"')
        self.assertIn("flyway_schema_history", dump, "control: the dump must hold the engine's schema")
        self.assertNotIn(sentinel, dump, "database")
        logs = subprocess.run(
            ["docker", "compose", "--project-name", PROJECT, "--env-file", str(ROOT / ".env.example"),
             "--file", str(ROOT / "compose.yaml"), "--profile", "gateway", "logs", "--no-color"],
            env=self.environment, capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120, check=False,
        )
        self.assertEqual(0, logs.returncode, logs.stderr[-2000:])
        self.assertIn('"event":"started"', logs.stdout)
        self.assertNotIn(sentinel, logs.stdout + logs.stderr, "service logs")

    def test_recreation_preserves_private_storage(self) -> None:
        """Verify topology, UID/socket isolation and durable neutral markers on recreation."""
        STATE.mkdir(parents=True, exist_ok=True)
        password = STATE / "postgres-password"
        # Reuse this private fixture secret because the retained database volume
        # is initialized only once. Never copy an owner/application secret.
        if not password.exists():
            with password.open("x", encoding="utf-8", newline="\n") as output:
                output.write(secrets.token_urlsafe(32) + "\n")
            password.chmod(0o600)
        self.environment = dict(os.environ, POSTGRES_PASSWORD_FILE=str(password), APP_PORT="0")
        # Never adopt or stop pre-existing containers, even with our fixture name.
        self.assertEqual("", self.compose("ps", "--all", "--quiet"),
                         "Fixture project already has containers; inspect them before rerunning")
        self.addCleanup(self.compose, "down", "--timeout", "30")
        self.compose("up", "--detach", "--build", "--wait", "--wait-timeout", "90", timeout=600)
        database = self.inspect("postgres")
        runtime = self.inspect("codex")
        engine = self.inspect("engine")
        # `_models`, `_gateway` and `_images` are internal and stay empty here: their services have profiles.
        self.assertEqual({PROJECT + "_web", PROJECT + "_data", PROJECT + "_models", PROJECT + "_gateway", PROJECT + "_images"},
                         set(engine["NetworkSettings"]["Networks"]))
        started = self.compose("ps", "--all", "--format", "{{.Service}}").splitlines()
        self.assertNotIn("models", started)
        self.assertNotIn("gateway", started)
        self.assertNotIn("image-worker", started)
        self.assertEqual("10001:10001", engine["Config"]["User"])
        self.assertTrue(engine["HostConfig"]["ReadonlyRootfs"])
        self.assertEqual(["ALL"], engine["HostConfig"]["CapDrop"])
        self.assertIn("no-new-privileges:true", engine["HostConfig"]["SecurityOpt"])
        self.assertFalse(any("PASSWORD=" in value for value in engine["Config"]["Env"]))
        self.assertEqual(
            {"/var/lib/engine/content", "/var/lib/engine/assets", "/tmp/codex-daemon-10001"},
            {mount["Destination"] for mount in engine["Mounts"] if mount["Type"] == "volume"},
        )
        ipc = next(mount for mount in engine["Mounts"] if mount["Destination"] == "/tmp/codex-daemon-10001")
        self.assertFalse(ipc["RW"])
        # File secrets are the only bind; no source checkout/account/Docker socket.
        self.assertEqual(["/run/secrets/postgres_password"],
                         [mount["Destination"] for mount in engine["Mounts"] if mount["Type"] == "bind"])
        self.compose("exec", "-T", "engine", "sh", "-ec",
                     "test -r /run/secrets/postgres_password; test ! -e /var/lib/runtime/home; "
                     "test ! -e /var/run/docker.sock; test ! -e /build; "
                     "! command -v node; ! command -v javac; ! command -v mvn")
        # A WebSocket upgrade proves this actual production UID can connect over
        # the read-only IPC mount. No RPC, account read or inference is requested.
        self.compose("exec", "-T", "engine", "sh", "-ec",
                     'code=$(curl --silent --max-time 2 --unix-socket "$1" '
                     '-H "Connection: Upgrade" -H "Upgrade: websocket" '
                     '-H "Sec-WebSocket-Version: 13" '
                     '-H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" '
                     '-o /dev/null -w "%{http_code}" http://localhost/ || true); '
                     'test "$code" = 101', "fixture", SOCKET)
        self.verify_http()
        self.verify_missing_secret()
        self.verify_backend()
        self.assertEqual({PROJECT + "_data"}, set(database["NetworkSettings"]["Networks"]))
        self.assertEqual({PROJECT + "_inference"}, set(runtime["NetworkSettings"]["Networks"]))
        for container in (database, runtime):
            self.assertTrue(container["HostConfig"]["ReadonlyRootfs"])
            self.assertFalse(container["HostConfig"].get("PortBindings"))
        self.assertEqual("10001:10001", runtime["Config"]["User"])
        self.assertEqual(["ALL"], runtime["HostConfig"]["CapDrop"])
        self.assertEqual(
            {"/var/lib/runtime/home", "/var/lib/runtime/workspace", "/tmp/codex-daemon-10001"},
            {mount["Destination"] for mount in runtime["Mounts"] if mount["Type"] == "volume"},
        )
        self.assertFalse(any(mount["Type"] == "bind" for mount in runtime["Mounts"]))
        self.assertFalse(any("POSTGRES" in value for value in runtime["Config"]["Env"]))
        self.assertEqual("10001", self.compose("exec", "-T", "codex", "id", "-u"))
        self.assertEqual("600:10001:10001", self.compose(
            "exec", "-T", "codex", "stat", "-c", "%a:%u:%g", SOCKET))
        self.assertEqual("700:10001:10001", self.compose(
            "exec", "-T", "codex", "stat", "-c", "%a:%u:%g", "/tmp/codex-daemon-10001"))
        # The shared UID is necessary: another unprivileged identity cannot even
        # traverse the socket directory, despite running in the same container.
        self.compose("exec", "-T", "--user", "10002:10002", "codex", "sh", "-ec",
                     "test ! -x /tmp/codex-daemon-10001")
        self.compose("exec", "-T", "codex", "sh", "-ec",
                     "test ! -e /run/secrets/postgres_password; test ! -e /var/lib/postgresql")
        self.assertEqual("1", self.database("SELECT 1"))
        # Supplying a password is not proof it is required: reject passwordless
        # TCP explicitly so a trust-authentication regression cannot pass.
        self.compose("exec", "-T", "postgres", "sh", "-ec",
                     'unset PGPASSWORD; export PGPASSFILE=/dev/null; '
                     'if psql -w -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" '
                     '-c "SELECT 1" >/dev/null 2>&1; then exit 1; fi')
        # A sentinel in each durable directory detects accidental anonymous/tmpfs
        # mounts even when Compose's rendered configuration looks correct.
        marker = secrets.token_hex(16)
        self.compose("exec", "-T", "engine", "sh", "-ec",
                     'printf "%s" "$1" > /var/lib/engine/content/.packaging-check; '
                     'printf "%s" "$1" > /var/lib/engine/assets/.packaging-check', "fixture", marker)
        # Keep fixture tables outside public: Flyway correctly refuses an unknown
        # non-empty canonical schema without migration history. Do not baseline
        # it automatically or change any previous infrastructure fixture volume.
        self.database("CREATE SCHEMA IF NOT EXISTS packaging_fixture")
        self.database("CREATE TABLE IF NOT EXISTS packaging_fixture.markers (marker text PRIMARY KEY)")
        self.database(f"INSERT INTO packaging_fixture.markers VALUES ('{marker}')")
        self.compose("exec", "-T", "codex", "sh", "-ec",
                     'printf "%s" "$1" > /var/lib/runtime/home/.packaging-check; '
                     'printf "%s" "$1" > /var/lib/runtime/workspace/.packaging-check',
                     "fixture", marker)
        self.compose("up", "--detach", "--force-recreate", "--wait", "--wait-timeout", "90")
        self.assertNotEqual(database["Id"], self.inspect("postgres")["Id"])
        self.assertNotEqual(runtime["Id"], self.inspect("codex")["Id"])
        self.assertNotEqual(engine["Id"], self.inspect("engine")["Id"])
        self.verify_http()
        for directory in ("content", "assets"):
            self.assertEqual(marker, self.compose("exec", "-T", "engine", "cat",
                                                 f"/var/lib/engine/{directory}/.packaging-check"))
        self.assertEqual(marker, self.database(
            f"SELECT marker FROM packaging_fixture.markers WHERE marker = '{marker}'"))
        for directory in ("home", "workspace"):
            self.assertEqual(marker, self.compose("exec", "-T", "codex", "cat",
                                                 f"/var/lib/runtime/{directory}/.packaging-check"))
        self.verify_gateway_boundary()
        # Only fixture-owned neutral markers are removed; volumes remain intact.
        self.database(f"DELETE FROM packaging_fixture.markers WHERE marker = '{marker}'")
        self.compose("exec", "-T", "codex", "rm",
                     "/var/lib/runtime/home/.packaging-check",
                     "/var/lib/runtime/workspace/.packaging-check")
        self.compose("exec", "-T", "engine", "rm",
                     "/var/lib/engine/content/.packaging-check",
                     "/var/lib/engine/assets/.packaging-check")


if __name__ == "__main__":
    unittest.main()
