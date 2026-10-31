"""Prove the readiness probes, the bilingual probe set and the measurement format.

Every server here is a scripted fixture on loopback that replays canned replies; it is not
a model, so a passing case shows the probe code distinguishes good from bad replies and says
nothing about any real model. The measurement sample is a format example, not a measurement.
The cases need no Docker and make no connection beyond 127.0.0.1.
"""

import contextlib
import copy
import io
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import socket
import tempfile
import threading
import unittest

import model_probe


def sample_instance(schema: dict) -> object:
    """Return a minimal value that satisfies `schema`, to script a conforming reply."""
    if "enum" in schema:
        return schema["enum"][0]
    kind = schema["type"]
    if kind == "object":
        return {name: sample_instance(item) for name, item in schema.get("properties", {}).items()
                if name in schema.get("required", [])}
    if kind == "array":
        return [sample_instance(schema["items"]) for _ in range(max(1, schema.get("minItems", 0)))]
    if kind == "string":
        return "x" * max(1, schema.get("minLength", 0))
    if kind == "boolean":
        return True
    return schema.get("minimum", 0)


class ScriptedHandler(BaseHTTPRequestHandler):
    """Answer `/health` and `/v1/chat/completions` from the owning server's script."""

    def log_message(self, *arguments) -> None:
        """Keep test output free of per-request lines."""

    def send(self, status: int, body: bytes) -> None:
        """Write one complete response."""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        """Serve the scripted health status."""
        if self.path == "/health":
            self.send(*self.server.health)
        else:
            self.send(404, b"{}")

    def do_POST(self) -> None:
        """Record the request and serve the scripted completion for that probe."""
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length))
        self.server.requests.append((self.path, request))
        name = request["response_format"]["json_schema"]["name"]
        self.send(*self.server.script(name, request))


class ScriptedServer(ThreadingHTTPServer):
    """A loopback server whose `script(name, request)` returns the (status, body) to send."""

    def __init__(self, script) -> None:
        super().__init__(("127.0.0.1", 0), ScriptedHandler)
        self.script = script
        self.health = (200, b'{"status":"ok"}')
        self.requests: list = []

    @property
    def url(self) -> str:
        """Return the server root."""
        return f"http://127.0.0.1:{self.server_address[1]}"


def completion(content: str) -> tuple[int, bytes]:
    """Wrap `content` as the body of a successful chat completion."""
    return 200, json.dumps({"choices": [{"message": {"role": "assistant", "content": content}}]}).encode()


class FixtureCase(unittest.TestCase):
    """Start a scripted server per case and stop it afterwards."""

    probe_set = model_probe.load_probe_set(model_probe.PROBES_DIR / "narration-probes.json")

    def start(self, script) -> ScriptedServer:
        """Start the fixture on a free port and register its shutdown."""
        server = ScriptedServer(script)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 5)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server

    def conforming(self, name: str, request: dict) -> tuple[int, bytes]:
        """Answer every probe with a reply that satisfies its own schema."""
        schema = request["response_format"]["json_schema"]["schema"]
        return completion(json.dumps(sample_instance(schema)))


class SchemaViolationsTest(unittest.TestCase):
    """The checker decides every probe verdict, so each rule needs a failing example."""

    SCHEMA = {
        "type": "object", "required": ["a", "b"], "additionalProperties": False,
        "properties": {
            "a": {"type": "string", "minLength": 2, "maxLength": 4, "enum": ["ok", "okay", "no"]},
            "b": {"type": "array", "minItems": 1, "maxItems": 2, "items": {"type": "integer", "minimum": 0, "maximum": 9}},
        },
    }

    def test_conforming_value_has_no_violation(self) -> None:
        """A value meeting every rule yields an empty list."""
        self.assertEqual([], model_probe.schema_violations({"a": "ok", "b": [1, 2]}, self.SCHEMA))

    def test_each_rule_reports_its_path(self) -> None:
        """Type, required, extra property, enum, bounds and item rules each fail visibly."""
        cases = {
            "expected object": [],
            "missing required property 'b'": {"a": "ok"},
            "unexpected property 'c'": {"a": "ok", "b": [1], "c": 1},
            "is not one of": {"a": "zz", "b": [1]},
            "$.a: expected string": {"a": 5, "b": [1]},
            "fewer than 1 items": {"a": "ok", "b": []},
            "more than 2 items": {"a": "ok", "b": [1, 2, 3]},
            "$.b[0]: expected integer": {"a": "ok", "b": ["1"]},
            "above maximum 9": {"a": "ok", "b": [10]},
            "below minimum 0": {"a": "ok", "b": [-1]},
            "shorter than 2": {"a": "o", "b": [1]},
        }
        for fragment, value in cases.items():
            with self.subTest(fragment):
                problems = model_probe.schema_violations(value, self.SCHEMA)
                self.assertTrue(any(fragment in problem for problem in problems), problems)

    def test_boolean_is_not_a_number(self) -> None:
        """`true` must not satisfy an integer or number type."""
        self.assertTrue(model_probe.schema_violations(True, {"type": "integer"}))
        self.assertTrue(model_probe.schema_violations(True, {"type": "number"}))

    def test_unsupported_keyword_is_refused_not_ignored(self) -> None:
        """A rule the checker cannot enforce must raise instead of passing everything."""
        with self.assertRaises(ValueError):
            model_probe.schema_violations("x", {"type": "string", "pattern": "^a"})
        self.assertEqual(["$.p.pattern"], model_probe.unsupported_keywords(
            {"type": "object", "properties": {"p": {"type": "string", "pattern": "a"}}}))


class ProbeSetFileTest(unittest.TestCase):
    """The shipped probe set must cover both languages with enforceable schemas."""

    def test_shipped_set_covers_english_and_italian_in_pairs(self) -> None:
        """Every probe id has an `-en` and an `-it` counterpart with the same schema shape."""
        probes = model_probe.load_probe_set(model_probe.PROBES_DIR / "narration-probes.json")["probes"]
        by_id = {probe["id"]: probe for probe in probes}
        self.assertGreaterEqual(len(probes), 8)
        for language in model_probe.LANGUAGES:
            self.assertGreaterEqual(sum(1 for probe in probes if probe["language"] == language), 4)
        for identifier, probe in by_id.items():
            stem, _, suffix = identifier.rpartition("-")
            self.assertEqual(probe["language"], suffix, identifier)
            other = by_id[f"{stem}-{'it' if suffix == 'en' else 'en'}"]
            self.assertEqual(sorted(probe["schema"]["required"]), sorted(other["schema"]["required"]), identifier)
            self.assertEqual([], model_probe.unsupported_keywords(probe["schema"]), identifier)

    def test_italian_prompts_are_not_english_copies(self) -> None:
        """An Italian probe's user text differs from its English pair."""
        probes = model_probe.load_probe_set(model_probe.PROBES_DIR / "narration-probes.json")["probes"]
        by_id = {probe["id"]: probe for probe in probes}
        for identifier, probe in by_id.items():
            if identifier.endswith("-it"):
                self.assertNotEqual(probe["user"], by_id[identifier[:-3] + "-en"]["user"], identifier)

    def test_loader_refuses_malformed_sets(self) -> None:
        """A wrong marker, a single language, a repeated id and an unsupported rule are refused."""
        good = json.loads((model_probe.PROBES_DIR / "narration-probes.json").read_text(encoding="utf-8"))
        english = [p for p in good["probes"] if p["language"] == "en"]
        broken = {
            "format": {**good, "format": "other/1"},
            "one language": {**good, "probes": english},
            "repeated id": {**good, "probes": [english[0], english[0]] + [p for p in good["probes"] if p["language"] == "it"]},
            "unsupported keyword": {**good, "probes": [{**good["probes"][0], "schema": {"type": "object", "pattern": "x"}}]
                                    + good["probes"][1:]},
            "no probes": {**good, "probes": []},
        }
        with tempfile.TemporaryDirectory() as directory:
            for name, data in broken.items():
                with self.subTest(name):
                    path = Path(directory) / "set.json"
                    path.write_text(json.dumps(data), encoding="utf-8")
                    with self.assertRaises(ValueError):
                        model_probe.load_probe_set(path)


class MeasurementFormatTest(unittest.TestCase):
    """The record format is validated, and the shipped sample is not evidence."""

    SAMPLE = model_probe.PROBES_DIR / "measurement-sample.json"

    def sample(self) -> dict:
        """Return a fresh copy of the shipped format sample."""
        return json.loads(self.SAMPLE.read_text(encoding="utf-8"))

    def synthetic_measurement(self) -> dict:
        """Return a made-up, format-valid host record. It is test data, not a measurement."""
        record = self.sample()
        record["kind"] = "host-measurement"
        record["date"] = "2000-01-01"
        record["host"] = "synthetic test host, not a real machine"
        record["model"]["sha256"] = "0" * 64
        record["metrics"] = {field: 1 for field in model_probe.METRIC_FIELDS}
        return record

    def test_sample_is_valid_and_carries_no_numbers(self) -> None:
        """The sample passes the validator as a format sample and claims no value."""
        record = self.sample()
        self.assertEqual([], model_probe.measurement_problems(record))
        self.assertEqual("format-sample", record["kind"])
        self.assertTrue(all(value is None for value in record["metrics"].values()))
        self.assertEqual(set(model_probe.METRIC_FIELDS), set(record["metrics"]))
        self.assertIn("not a measurement", record["notice"])

    def test_sample_cannot_pass_as_a_host_measurement(self) -> None:
        """Relabelling the sample as a measurement exposes its null metrics and missing facts."""
        record = self.sample()
        record["kind"] = "host-measurement"
        problems = model_probe.measurement_problems(record)
        for field in model_probe.METRIC_FIELDS:
            self.assertTrue(any(f"metrics.{field}" in problem for problem in problems), field)
        self.assertTrue(any("date" in problem for problem in problems))
        self.assertTrue(any("host" in problem for problem in problems))
        self.assertTrue(any("sha256" in problem for problem in problems))

    def test_synthetic_host_record_is_accepted(self) -> None:
        """A complete record of the measurement kind validates."""
        self.assertEqual([], model_probe.measurement_problems(self.synthetic_measurement()))

    def test_incomplete_records_are_rejected(self) -> None:
        """Dropping any required part, or giving it a wrong value, is reported."""
        for kind, build in (("format-sample", self.sample), ("host-measurement", self.synthetic_measurement)):
            for key in ("format", "kind", "model", "metrics", "probes"):
                with self.subTest(kind=kind, missing=key):
                    record = build()
                    del record[key]
                    self.assertTrue(model_probe.measurement_problems(record))
            for field in model_probe.METRIC_FIELDS:
                with self.subTest(kind=kind, missing=field):
                    record = build()
                    del record["metrics"][field]
                    self.assertTrue(any(field in problem for problem in model_probe.measurement_problems(record)))
        self.assertTrue(model_probe.measurement_problems([]))
        self.assertTrue(model_probe.measurement_problems({}))

    def test_bad_values_are_rejected(self) -> None:
        """Negative or non-numeric metrics, a bad hash and a bad probe entry fail."""
        mutations = {
            "negative metric": lambda r: r["metrics"].update(loadTimeMs=-1),
            "text metric": lambda r: r["metrics"].update(peakVramBytes="12"),
            "boolean metric": lambda r: r["metrics"].update(peakRamBytes=True),
            "short hash": lambda r: r["model"].update(sha256="abc"),
            "bad date": lambda r: r.update(date="31/10/2026"),
            "blank host": lambda r: r.update(host=" "),
            "bad probe status": lambda r: r["probes"][0].update(status="maybe"),
            "bad probe language": lambda r: r["probes"][0].update(language="fr"),
            "unknown kind": lambda r: r.update(kind="estimate"),
        }
        for name, mutate in mutations.items():
            with self.subTest(name):
                record = copy.deepcopy(self.synthetic_measurement())
                mutate(record)
                self.assertTrue(model_probe.measurement_problems(record))

    def test_sample_with_a_number_is_rejected(self) -> None:
        """A format sample may not smuggle a figure in."""
        record = self.sample()
        record["metrics"]["decodeTokensPerSecond"] = 50
        self.assertTrue(model_probe.measurement_problems(record))


class ScriptedServerProbeTest(FixtureCase):
    """Run the probes against the scripted fixture and read the visible verdicts."""

    def results_for(self, server: ScriptedServer) -> list[dict]:
        """Run the whole shipped set against `server`."""
        return model_probe.run_probe_set(server.url, "fixture-model", self.probe_set, timeout=10)

    def test_conforming_replies_pass_every_probe(self) -> None:
        """A server that answers within schema yields a pass for each probe in both languages."""
        server = self.start(self.conforming)
        results = self.results_for(server)
        self.assertEqual(len(self.probe_set["probes"]), len(results))
        self.assertEqual({"pass"}, {result["status"] for result in results})
        self.assertEqual({"en", "it"}, {result["language"] for result in results})
        self.assertTrue(all(isinstance(result["latencyMs"], int) for result in results))

    def test_request_asks_for_the_schema_deterministically(self) -> None:
        """The probe sends the model id, temperature 0, a capped reply and the strict schema."""
        server = self.start(self.conforming)
        self.results_for(server)
        path, request = server.requests[0]
        first = self.probe_set["probes"][0]
        self.assertEqual("/v1/chat/completions", path)
        self.assertEqual("fixture-model", request["model"])
        self.assertEqual(0, request["temperature"])
        self.assertEqual(first["maxTokens"], request["max_tokens"])
        self.assertEqual("json_schema", request["response_format"]["type"])
        self.assertTrue(request["response_format"]["json_schema"]["strict"])
        self.assertEqual(first["schema"], request["response_format"]["json_schema"]["schema"])
        self.assertEqual(["system", "user"], [message["role"] for message in request["messages"]])
        self.assertEqual(first["user"], request["messages"][1]["content"])

    def test_schema_violation_fails_only_that_probe(self) -> None:
        """One non-conforming reply fails its probe with the violation; the others still pass."""
        def script(name: str, request: dict) -> tuple[int, bytes]:
            if name == "intent_parse_it":
                return completion(json.dumps({"action": "fly", "target": "gate", "confident": True}))
            return self.conforming(name, request)

        results = {r["id"]: r for r in self.results_for(self.start(script))}
        failed = results["intent-parse-it"]
        self.assertEqual("fail", failed["status"])
        self.assertIn("schema violation", failed["detail"])
        self.assertIn("$.action", failed["detail"])
        self.assertEqual(1, sum(1 for r in results.values() if r["status"] == "fail"))

    def test_malformed_json_reply_fails(self) -> None:
        """Content that is not JSON, or a body that is not a chat completion, is a failure."""
        replies = {
            "reply is not valid JSON": completion("Sure! Here is the scene {"),
            "not a chat completion": (200, b'{"choices": []}'),
            "not a chat completion ": (200, b"<html>gateway</html>"),
            "HTTP 500": (500, b'{"error":"boom"}'),
        }
        for fragment, reply in replies.items():
            with self.subTest(fragment):
                server = self.start(lambda name, request, reply=reply: reply)
                result = model_probe.run_probe(server.url, "m", self.probe_set["probes"][0], timeout=10)
                self.assertEqual("fail", result["status"])
                self.assertIn(fragment.strip(), result["detail"])

    def test_server_not_ready_reports_one_failure_and_sends_no_probe(self) -> None:
        """A loading server (HTTP 503) fails readiness once; no completion request is made."""
        server = self.start(self.conforming)
        server.health = (503, b'{"error":{"message":"Loading model"}}')
        results = self.results_for(server)
        self.assertEqual(["readiness"], [result["id"] for result in results])
        self.assertEqual("fail", results[0]["status"])
        self.assertIn("not ready", results[0]["detail"])
        self.assertEqual([], server.requests)

    def test_unreachable_server_fails_readiness(self) -> None:
        """A port nobody listens on is reported as unreachable, not raised."""
        with socket.socket() as probe_socket:
            probe_socket.bind(("127.0.0.1", 0))
            port = probe_socket.getsockname()[1]
        results = model_probe.run_probe_set(f"http://127.0.0.1:{port}", "m", self.probe_set, timeout=2)
        self.assertEqual(["readiness"], [result["id"] for result in results])
        self.assertIn("unreachable", results[0]["detail"])

    def run_main(self, *arguments: str) -> tuple[int, str]:
        """Run the command line and return its exit code and printed text."""
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = model_probe.main(list(arguments))
        return code, output.getvalue()

    def test_command_line_shows_pass_and_fail_lines(self) -> None:
        """Exit 0 and only PASS lines for a conforming server; exit 1 with a FAIL line otherwise."""
        good = self.start(self.conforming)
        code, text = self.run_main("--base-url", good.url, "--model", "m", "--timeout", "10")
        self.assertEqual(0, code)
        self.assertIn("PASS narration-beat-en [en]", text)
        self.assertNotIn("FAIL", text)
        self.assertIn("8 passed, 0 failed", text)

        bad = self.start(lambda name, request: completion("not json"))
        code, text = self.run_main("--base-url", bad.url, "--model", "m", "--timeout", "10")
        self.assertEqual(1, code)
        self.assertIn("FAIL narration-beat-it [it]", text)
        self.assertIn("0 passed, 8 failed", text)

    def test_command_line_reports_not_ready_and_unreadable_set(self) -> None:
        """A 503 server exits 1 with a readiness line; a missing probe file exits 2."""
        server = self.start(self.conforming)
        server.health = (503, b"{}")
        code, text = self.run_main("--base-url", server.url, "--model", "m")
        self.assertEqual(1, code)
        self.assertIn("FAIL readiness [--]", text)
        code, text = self.run_main("--base-url", server.url, "--model", "m", "--probes", "no-such-file.json")
        self.assertEqual(2, code)
        self.assertIn("FAIL probe set", text)


if __name__ == "__main__":
    unittest.main()
