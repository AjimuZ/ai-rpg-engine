"""Probe a local text server's JSON-Schema output and validate measurement records.

Responsibility: send the bilingual probe set to an OpenAI-compatible chat endpoint with a
`json_schema` response format, check each reply against its schema, and print a visible
PASS or FAIL line per probe. It also defines the measurement record format and validates
records against it.

A probe result says only that a reply parsed and matched its schema. It never certifies
narration quality, and a record produced here is not a measurement unless its `kind` is
`host-measurement`. Standard library only, so it runs in the pinned Python image.
"""

import argparse
import json
from pathlib import Path
import re
import sys
import time
from typing import Callable
import urllib.error
import urllib.request


PROBES_DIR = Path(__file__).resolve().parent / "probes"
PROBE_SET_FORMAT = "rpg-engine.narration-probes/1"
MEASUREMENT_FORMAT = "rpg-engine.model-measurement/1"
LANGUAGES = ("en", "it")
MEASUREMENT_KINDS = ("format-sample", "host-measurement")
# Metrics a host measurement must carry as non-negative numbers; a format sample carries null.
METRIC_FIELDS = ("loadTimeMs", "prefillTokensPerSecond", "decodeTokensPerSecond", "peakVramBytes",
                 "peakRamBytes", "contextTokensMax", "vramReleasedByUnloadBytes")
SHA256 = re.compile(r"^[0-9a-f]{64}$")

_TYPES = {
    "object": dict, "array": list, "string": str, "boolean": bool, "null": type(None),
    "integer": int, "number": (int, float),
}


SUPPORTED_KEYWORDS = {"type", "enum", "required", "properties", "additionalProperties", "items", "minLength",
                      "maxLength", "minItems", "maxItems", "minimum", "maximum", "description", "title"}


def unsupported_keywords(schema: dict, path: str = "$") -> list[str]:
    """Return `path.keyword` for every keyword in `schema`, nested ones included, that
    `schema_violations` does not enforce."""
    found = [f"{path}.{name}" for name in sorted(set(schema) - SUPPORTED_KEYWORDS)]
    for name, item in schema.get("properties", {}).items():
        found.extend(unsupported_keywords(item, f"{path}.{name}"))
    if isinstance(schema.get("items"), dict):
        found.extend(unsupported_keywords(schema["items"], f"{path}[]"))
    return found


def schema_violations(value: object, schema: dict, path: str = "$") -> list[str]:
    """Return every way `value` breaks `schema`, each prefixed with its JSON path.

    Supports the subset the probe schemas use: `type`, `enum`, `required`, `properties`,
    `additionalProperties: false`, `items`, `minLength`, `maxLength`, `minItems`, `maxItems`,
    `minimum` and `maximum`. A keyword outside the subset raises `ValueError`, so a probe
    can never pass by relying on a rule this checker silently ignores.
    """
    unsupported = sorted(set(schema) - SUPPORTED_KEYWORDS)
    if unsupported:
        raise ValueError(f"unsupported schema keyword at {path}: {', '.join(unsupported)}")
    found: list[str] = []
    expected = schema.get("type")
    if expected is not None:
        python_type = _TYPES[expected]
        is_number_flag = isinstance(value, bool) and expected in ("integer", "number")
        if not isinstance(value, python_type) or is_number_flag:
            return [f"{path}: expected {expected}, got {type(value).__name__}"]
    if "enum" in schema and value not in schema["enum"]:
        found.append(f"{path}: {value!r} is not one of {schema['enum']}")
    if isinstance(value, dict):
        for name in schema.get("required", []):
            if name not in value:
                found.append(f"{path}: missing required property {name!r}")
        properties = schema.get("properties", {})
        for name, item in value.items():
            if name in properties:
                found.extend(schema_violations(item, properties[name], f"{path}.{name}"))
            elif schema.get("additionalProperties") is False:
                found.append(f"{path}: unexpected property {name!r}")
    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0):
            found.append(f"{path}: fewer than {schema['minItems']} items")
        if len(value) > schema.get("maxItems", len(value)):
            found.append(f"{path}: more than {schema['maxItems']} items")
        for index, item in enumerate(value):
            if "items" in schema:
                found.extend(schema_violations(item, schema["items"], f"{path}[{index}]"))
    if isinstance(value, str):
        if len(value) < schema.get("minLength", 0):
            found.append(f"{path}: shorter than {schema['minLength']} characters")
        if len(value) > schema.get("maxLength", len(value)):
            found.append(f"{path}: longer than {schema['maxLength']} characters")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            found.append(f"{path}: below minimum {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            found.append(f"{path}: above maximum {schema['maximum']}")
    return found


def load_probe_set(path: Path) -> dict:
    """Load a probe set file and return it, or raise `ValueError` naming the first defect.

    A usable set has the format marker and, for every probe, an id, a language in
    `LANGUAGES`, a system and user text, and an object schema. Both languages must be
    present and every id must be unique, so a set cannot silently cover only one language.
    """
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("format") != PROBE_SET_FORMAT:
        raise ValueError(f"{path.name}: format must be {PROBE_SET_FORMAT}")
    probes = data.get("probes")
    if not isinstance(probes, list) or not probes:
        raise ValueError(f"{path.name}: probes must be a non-empty list")
    seen: set[str] = set()
    for probe in probes:
        identifier = probe.get("id")
        if not isinstance(identifier, str) or not identifier or identifier in seen:
            raise ValueError(f"{path.name}: probe id missing or repeated: {identifier!r}")
        seen.add(identifier)
        if probe.get("language") not in LANGUAGES:
            raise ValueError(f"{identifier}: language must be one of {LANGUAGES}")
        for field in ("system", "user"):
            if not isinstance(probe.get(field), str) or not probe[field].strip():
                raise ValueError(f"{identifier}: {field} text is required")
        schema = probe.get("schema")
        if not isinstance(schema, dict) or schema.get("type") != "object":
            raise ValueError(f"{identifier}: schema must be an object schema")
        unsupported = unsupported_keywords(schema)
        if unsupported:
            raise ValueError(f"{identifier}: unsupported schema keyword: {', '.join(unsupported)}")
    missing = [language for language in LANGUAGES if not any(p["language"] == language for p in probes)]
    if missing:
        raise ValueError(f"{path.name}: no probe for {', '.join(missing)}")
    return data


def _request(url: str, body: dict | None, timeout: float) -> tuple[int, bytes]:
    """Send one request and return (status, body), reading HTTP error bodies too.

    Raises `OSError` when the server cannot be reached or the exchange times out.
    """
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json"} if data is not None else {}
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        with error:
            return error.code, error.read()
    except urllib.error.URLError as error:
        raise OSError(str(error.reason)) from error


def check_ready(base_url: str, timeout: float = 5.0) -> str | None:
    """Return None when `GET /health` answers 200, else the reason the server is not ready.

    A loading server answers 503 and an unreachable one raises inside; both become a
    reason string, never an exception, so the caller reports one visible failure.
    """
    try:
        status, body = _request(base_url.rstrip("/") + "/health", None, timeout)
    except OSError as error:
        return f"server unreachable: {error}"
    if status != 200:
        return f"server not ready: HTTP {status} {body[:120].decode('utf-8', 'replace')!r}"
    return None


def run_probe(base_url: str, model: str, probe: dict, timeout: float = 120.0,
              clock: Callable[[], float] = time.monotonic) -> dict:
    """Send one probe and return its result record.

    The record has `id`, `language`, `status` (`pass` or `fail`), `latencyMs` and `detail`.
    A probe fails, never raises, on a transport error, a non-200 status, a body or reply
    that is not JSON, or a reply that breaks the probe's schema. Temperature is 0 and the
    reply length is capped, so a run is comparable with another.
    """
    request_body = {
        "model": model,
        "messages": [{"role": "system", "content": probe["system"]},
                     {"role": "user", "content": probe["user"]}],
        "temperature": 0,
        "max_tokens": probe.get("maxTokens", 400),
        "response_format": {"type": "json_schema",
                            "json_schema": {"name": probe["id"].replace("-", "_"), "strict": True,
                                            "schema": probe["schema"]}},
    }
    result = {"id": probe["id"], "language": probe["language"], "status": "fail", "latencyMs": None,
              "detail": ""}
    started = clock()
    try:
        status, body = _request(base_url.rstrip("/") + "/v1/chat/completions", request_body, timeout)
    except OSError as error:
        result["detail"] = f"transport error: {error}"
        return result
    result["latencyMs"] = round((clock() - started) * 1000)
    if status != 200:
        result["detail"] = f"HTTP {status}: {body[:120].decode('utf-8', 'replace')}"
        return result
    try:
        content = json.loads(body)["choices"][0]["message"]["content"]
    except (ValueError, KeyError, IndexError, TypeError):
        result["detail"] = "response is not a chat completion with message content"
        return result
    try:
        reply = json.loads(content)
    except (ValueError, TypeError):
        result["detail"] = "reply is not valid JSON"
        return result
    problems = schema_violations(reply, probe["schema"])
    if problems:
        result["detail"] = "schema violation: " + "; ".join(problems[:3])
        return result
    result["status"] = "pass"
    result["detail"] = "reply matches schema"
    return result


def run_probe_set(base_url: str, model: str, probe_set: dict, timeout: float = 120.0) -> list[dict]:
    """Run readiness, then every probe, and return the result records in order.

    A server that is not ready yields one failed `readiness` record and no probe is sent,
    so a loading model is never reported as a hundred schema failures.
    """
    reason = check_ready(base_url)
    if reason is not None:
        return [{"id": "readiness", "language": None, "status": "fail", "latencyMs": None, "detail": reason}]
    return [run_probe(base_url, model, probe, timeout) for probe in probe_set["probes"]]


def measurement_problems(record: object) -> list[str]:
    """Return every way `record` breaks the measurement format; an empty list means valid.

    Every field is required. A `format-sample` shows the shape and carries null metrics, so
    it cannot pass as evidence. A `host-measurement` needs a date, a host description, the
    model's SHA-256 and a non-negative number for every metric.
    """
    if not isinstance(record, dict):
        return ["record must be an object"]
    problems: list[str] = []
    if record.get("format") != MEASUREMENT_FORMAT:
        problems.append(f"format must be {MEASUREMENT_FORMAT}")
    kind = record.get("kind")
    if kind not in MEASUREMENT_KINDS:
        problems.append(f"kind must be one of {MEASUREMENT_KINDS}")
    model = record.get("model")
    if not isinstance(model, dict) or not isinstance(model.get("id"), str) or not model.get("id"):
        problems.append("model.id is required")
    elif model.get("sha256") is not None and not SHA256.match(str(model["sha256"])):
        problems.append("model.sha256 must be 64 lowercase hex digits or null")
    metrics = record.get("metrics")
    if not isinstance(metrics, dict):
        problems.append("metrics must be an object")
        metrics = {}
    for field in METRIC_FIELDS:
        if field not in metrics:
            problems.append(f"metrics.{field} is missing")
        elif kind == "format-sample" and metrics[field] is not None:
            problems.append(f"metrics.{field} must be null in a format sample")
        elif kind == "host-measurement":
            value = metrics[field]
            if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
                problems.append(f"metrics.{field} must be a non-negative number")
    if kind == "host-measurement":
        if not (isinstance(record.get("date"), str) and re.match(r"^\d{4}-\d{2}-\d{2}$", record["date"])):
            problems.append("date must be YYYY-MM-DD in a host measurement")
        if not isinstance(record.get("host"), str) or not record["host"].strip():
            problems.append("host is required in a host measurement")
        if not isinstance(model, dict) or not SHA256.match(str(model.get("sha256"))):
            problems.append("model.sha256 is required in a host measurement")
    probes = record.get("probes")
    if not isinstance(probes, list):
        problems.append("probes must be a list")
    else:
        for index, entry in enumerate(probes):
            if not isinstance(entry, dict) or entry.get("status") not in ("pass", "fail") \
                    or entry.get("language") not in LANGUAGES or not isinstance(entry.get("id"), str):
                problems.append(f"probes[{index}] needs id, language en|it and status pass|fail")
    return problems


def format_results(results: list[dict]) -> str:
    """Return one `PASS` or `FAIL` line per result and a closing count line."""
    lines = []
    for result in results:
        latency = "" if result["latencyMs"] is None else f" ({result['latencyMs']} ms)"
        language = result["language"] or "--"
        lines.append(f"{result['status'].upper():4} {result['id']} [{language}]{latency}: {result['detail']}")
    failed = sum(1 for result in results if result["status"] != "pass")
    lines.append(f"{len(results) - failed} passed, {failed} failed")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    """Run a probe set against a server; exit 0 only when every probe passed."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", required=True, help="server root, e.g. http://127.0.0.1:8080")
    parser.add_argument("--model", required=True, help="model id the router should serve")
    parser.add_argument("--probes", type=Path, default=PROBES_DIR / "narration-probes.json")
    parser.add_argument("--timeout", type=float, default=120.0, help="seconds per probe request")
    arguments = parser.parse_args(argv)
    try:
        probe_set = load_probe_set(arguments.probes)
    except (OSError, ValueError) as error:
        print(f"FAIL probe set: {error}")
        return 2
    results = run_probe_set(arguments.base_url, arguments.model, probe_set, arguments.timeout)
    print(format_results(results))
    return 0 if all(result["status"] == "pass" for result in results) else 1


if __name__ == "__main__":
    sys.exit(main())
