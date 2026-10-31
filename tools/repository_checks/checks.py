"""Credential-free repository checks for ordinary CI and local pre-push review.

Six independent checks run against the files Git considers part of the project
(tracked files plus untracked files that are not ignored):

* ``secrets``: refuse private paths that must never be published (local
  environment files, local backend configuration, account state, assistant
  configuration, key material),
  refuse content that looks like a credential or a developer-specific absolute
  path, and verify that ``.gitignore`` still covers every private location.
* ``docs``: verify that Markdown fences are balanced and that every relative
  link, including ``#anchor`` fragments, resolves to an existing file/heading.
* ``workflows``: verify that CI workflows stay credential-free (no secrets, a
  read-only token, SHA-pinned actions, no privileged triggers).
* ``images``: verify that every external Dockerfile ``FROM``, ``# syntax=`` line and
  Compose ``image:`` carries an exact tag and an index digest (ADR 0009).

* ``localization``: verify that every public campaign/stream error code a backend
  controller can answer has a localized message in the browser interface, so a new
  code cannot reach players as raw text or the generic fallback by omission.
* ``contracts``: verify that each recorded real-server transcript the frontend specs
  replay is byte-identical to the golden file the backend test produces and compares,
  so the two suites cannot silently test different protocol versions.

Usage: ``python tools/repository_checks/checks.py [secrets|docs|workflows|images|localization|contracts|all]``.
The exit status is 0 when every selected check passes and 1 otherwise; each
finding is printed as ``path:line: message``. The module uses only the Python
standard library and local Git commands. It makes no network requests, reads no
environment variable and never prints the matched secret text itself, so a CI
log cannot leak a value the scanner found. A line can opt out of the content
scan with the marker ``secret-scan: allow`` (use only for documented placeholders).
"""

from __future__ import annotations

from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[2]

# Files larger than this are binary-like or generated; the content scan skips them
# so a stray large artifact cannot make the check slow. Path rules still apply.
MAX_SCAN_BYTES = 4 * 1024 * 1024

ALLOW_MARKER = "secret-scan: allow"

# --- Private path rules -------------------------------------------------------

# Each rule is (regular expression over the POSIX-style relative path, reason).
# Sanitized example files (``.env.example``) are the only deliberate exception.
FORBIDDEN_PATHS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"(^|/)\.env(\.(?!example$)[^/]*)?$"), "local environment file"),
    (re.compile(r"^\.local/"), "private local runtime directory"),
    # Spring Boot reads ./config/ relative to backend/; a checkout keeps its private
    # datasource settings there (backend/README.md, "Local datasource file").
    (re.compile(r"^backend/config/"), "local backend configuration"),
    (re.compile(r"(^|/)\.(claude|agents|codex)/"), "private assistant configuration"),
    (re.compile(r"^(AGENTS|CLAUDE)(\.override|\.local)?\.md$"), "private assistant instructions"),
    (re.compile(r"(^|/)\.mcp\.json$"), "private tool configuration"),
    (re.compile(r"(^|/)auth\.json$"), "account state file"),
    (re.compile(r"(^|/)(runtime-data|backups)/"), "runtime data or backups"),
    (re.compile(r"\.(pem|key|p12|pfx|jks|keystore)$"), "key material"),
    (re.compile(r"(^|/)id_(rsa|dsa|ecdsa|ed25519)(\.pub)?$"), "SSH key"),
    (re.compile(r"(^|/)postgres[-_]password$"), "database password file"),
    (re.compile(r"(^|/)secrets?/"), "secrets directory"),
    (re.compile(r"\.log$"), "log output"),
]

# Locations that must stay ignored by ``.gitignore`` so a plain ``git add .``
# cannot publish them. ``git check-ignore`` is used with paths that need not exist.
REQUIRED_IGNORED = [
    ".env", ".env.local", ".local/secrets/postgres-password", ".local/automation/x",
    ".codex/auth.json", ".claude/settings.local.json", ".agents/hooks/x",
    "AGENTS.md", "CLAUDE.md", "runtime-data/campaign.db", "backups/dump.sql", "engine.log",
    "backend/config/application.properties",
]

# --- Content rules ------------------------------------------------------------

# Each rule is (name, regular expression). Names (never matched text) are reported.
CONTENT_RULES: list[tuple[str, re.Pattern[str]]] = [
    ("private key block", re.compile(r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----")),
    ("cloud access key id", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("repository host token", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})")),
    ("provider API key", re.compile(r"\bsk-[A-Za-z0-9_-]{20,}")),
    ("chat service token", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}")),
    ("signed JSON web token", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")),
    ("account token field", re.compile(
        r"[\"'](?:access_token|refresh_token|id_token|api_key|apikey)[\"']\s*:\s*[\"'][^\"']{16,}[\"']", re.I)),
    # A literal credential assigned in configuration or code. Prefixed names such
    # as ``db_password`` count, so the lookbehind excludes only letters and digits.
    # The value must start alphanumerically, which skips shell forms such as
    # ``${PASSWORD:+text}``. Placeholders are excluded in ``_is_placeholder`` so
    # documentation can show variable names.
    ("hard-coded credential assignment", re.compile(
        r"(?i)(?<![A-Za-z0-9])(?:password|passwd|secret|token|api[_-]?key)\b[\"']?\s*[:=]\s*[\"']?([A-Za-z0-9][A-Za-z0-9/+_.\-]{11,})")),
    # Developer-specific absolute paths reveal the owner's account name.
    ("developer home path", re.compile(
        # The lookbehind keeps container paths such as ``/runtime/home/`` and MSYS
        # paths such as ``/c/Users/`` out of the match; a name needs 3+ characters.
        r"(?i)(?:\b[A-Z]:[\\/]Users[\\/]|(?<![A-Za-z0-9._-])/(?:Users|home)/)([A-Za-z0-9][A-Za-z0-9._-]{2,})")),
]

# Path account names that are generic or placeholders rather than a real person.
GENERIC_HOME_NAMES = {
    "user", "username", "you", "name", "me", "example", "someone", "owner", "public", "default",
    "runner", "node", "app", "engine", "appuser", "ubuntu", "root", "build", "work",
}

# Substrings that mark a captured value as a placeholder rather than a credential.
PLACEHOLDER_HINTS = (
    "example", "placeholder", "changeme", "your", "xxxx", "dummy", "fixture", "sample",
    "secret-scan", "password-file", "_file", "file", "none", "null", "true", "false",
    "random", "generated", "private", "value", "redacted", "neutral", "test",
)


def _is_placeholder(value: str) -> bool:
    """Return True when a captured assignment value is an obvious non-credential.

    Inputs: the text captured after ``password=`` style keys. Output: a bool.
    Values that look like identifiers (all letters/underscores, e.g. a property
    name or environment variable), placeholders, or paths are not credentials.
    """
    lowered = value.lower()
    if any(hint in lowered for hint in PLACEHOLDER_HINTS):
        return True
    # Property or variable names such as ``spring.datasource.password`` contain no digits
    # and are separated by dots, underscores or dashes; real secrets mix classes.
    if re.fullmatch(r"[A-Za-z]+([._/-][A-Za-z]+)*", value):
        return True
    return False


def project_files(root: Path = ROOT) -> list[str]:
    """List project files as POSIX relative paths that exist in the working tree.

    Includes tracked files and untracked files that ``.gitignore`` does not hide,
    which is exactly what ``git add .`` could publish. Raises RuntimeError when
    Git fails (for example outside a repository) so CI cannot pass vacuously.
    """
    result = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=root, capture_output=True, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("git ls-files failed: " + result.stderr.decode("utf-8", "replace").strip())
    names = [n for n in result.stdout.decode("utf-8").split("\0") if n]
    # A tracked file deleted in the working tree is still listed; skip it.
    return sorted({n for n in names if (root / n).is_file()})


def scan_path(path: str) -> list[str]:
    """Return findings for one relative path that matches a forbidden private path rule."""
    return [f"{path}:1: forbidden path ({reason})" for rule, reason in FORBIDDEN_PATHS if rule.search(path)]


def scan_text(path: str, text: str) -> list[str]:
    """Scan decoded file content line by line and return findings.

    Only the rule name and location are reported, never the matching text, so the
    finding itself cannot leak the credential into logs.
    """
    findings: list[str] = []
    for number, line in enumerate(text.splitlines(), start=1):
        if ALLOW_MARKER in line:
            continue
        for name, rule in CONTENT_RULES:
            for match in rule.finditer(line):
                if name == "hard-coded credential assignment" and _is_placeholder(match.group(1)):
                    continue
                if name == "developer home path" and match.group(1).lower() in GENERIC_HOME_NAMES:
                    continue
                if name == "developer home path" and match.group(1).startswith(("<", "$", "{", "%")):
                    continue
                findings.append(f"{path}:{number}: {name}")
                break  # one finding per rule per line is enough
    return findings


def check_ignore_policy(root: Path = ROOT) -> list[str]:
    """Verify ``.gitignore`` hides every location in ``REQUIRED_IGNORED``.

    Returns findings for paths Git would stage. Raises RuntimeError if Git
    cannot be run. ``git check-ignore -q`` exits 0 when ignored, 1 when not.
    """
    findings = []
    for candidate in REQUIRED_IGNORED:
        result = subprocess.run(["git", "check-ignore", "-q", "--no-index", candidate],
                                cwd=root, capture_output=True, check=False)
        if result.returncode == 1:
            findings.append(f".gitignore:1: private path is not ignored: {candidate}")
        elif result.returncode not in (0, 1):
            raise RuntimeError("git check-ignore failed for " + candidate)
    return findings


def check_secrets(root: Path = ROOT) -> list[str]:
    """Run path, content and ignore-policy scans over the project files."""
    findings: list[str] = []
    for path in project_files(root):
        findings.extend(scan_path(path))
        file = root / path
        if file.stat().st_size > MAX_SCAN_BYTES:
            continue
        data = file.read_bytes()
        if b"\0" in data[:8192]:
            continue  # binary asset (for example a PNG): path rules only
        findings.extend(scan_text(path, data.decode("utf-8", "replace")))
    findings.extend(check_ignore_policy(root))
    return findings


# --- Documentation rules ------------------------------------------------------

LINK = re.compile(r"(?<!\!)\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)|^\s*\[[^\]]+\]:\s*(\S+)", re.M)
FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})")
HEADING = re.compile(r"^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$")
EXTERNAL = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|//)", re.I)


def slugify(heading: str) -> str:
    """Convert heading text to the anchor GitHub generates for it.

    Markdown links and emphasis are reduced to their text, letters are lowered,
    punctuation other than ``-``/``_`` is dropped and spaces become hyphens.
    """
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading)
    # Code-span backticks and emphasis asterisks never appear in the generated anchor.
    text = text.replace("`", "").replace("*", "")
    text = unicodedata.normalize("NFC", text).lower()
    text = "".join(c for c in text if c.isalnum() or c in " -_")
    return text.replace(" ", "-")


def markdown_structure(text: str) -> tuple[set[str], list[tuple[int, str]], list[str]]:
    """Parse Markdown outside fenced code.

    Returns ``(anchors, links, fence_errors)``: heading anchors (with GitHub's
    ``-N`` suffix for duplicates), ``(line, target)`` link pairs, and messages for
    an unclosed fence. Link-like text inside fences and inline code is ignored.
    """
    anchors: set[str] = set()
    seen: dict[str, int] = {}
    links: list[tuple[int, str]] = []
    errors: list[str] = []
    fence: tuple[str, int, int] | None = None  # (character, length, opening line)
    for number, line in enumerate(text.splitlines(), start=1):
        marker = FENCE.match(line)
        if fence:
            # A closing fence uses the same character, at least as long, with no info string.
            if marker and marker.group(1)[0] == fence[0] and len(marker.group(1)) >= fence[1] \
                    and line.strip() == marker.group(1):
                fence = None
            continue
        if marker:
            fence = (marker.group(1)[0], len(marker.group(1)), number)
            continue
        heading = HEADING.match(line)
        if heading:
            slug = slugify(heading.group(1))
            count = seen.get(slug, 0)
            seen[slug] = count + 1
            anchors.add(slug if count == 0 else f"{slug}-{count}")
        stripped = re.sub(r"`[^`]*`", "", line)  # inline code never forms a link
        for match in LINK.finditer(stripped):
            links.append((number, match.group(1) or match.group(2)))
    if fence:
        errors.append(f"unclosed code fence opened on line {fence[2]}")
    return anchors, links, errors


def check_docs(root: Path = ROOT) -> list[str]:
    """Check Markdown fences and local links/anchors in all project Markdown files."""
    files = [p for p in project_files(root) if p.lower().endswith(".md")]
    parsed = {p: markdown_structure((root / p).read_text(encoding="utf-8", errors="replace")) for p in files}
    findings: list[str] = []
    for path, (_, links, errors) in parsed.items():
        findings.extend(f"{path}:1: {e}" for e in errors)
        for number, target in links:
            if EXTERNAL.match(target):
                continue  # external URLs are not fetched: CI stays offline and deterministic
            base, _, fragment = target.partition("#")
            base = base.strip("<>")
            if base:
                resolved = PurePosixPath(path).parent / base
                parts: list[str] = []
                for part in resolved.parts:
                    if part == "..":
                        if not parts:
                            break
                        parts.pop()
                    elif part != ".":
                        parts.append(part)
                else:
                    resolved_text = "/".join(parts)
                    if not (root / resolved_text).exists():
                        findings.append(f"{path}:{number}: broken link target {target}")
                        continue
                    if fragment and resolved_text in parsed and fragment.lower() not in parsed[resolved_text][0]:
                        findings.append(f"{path}:{number}: missing anchor #{fragment} in {resolved_text}")
                    continue
                findings.append(f"{path}:{number}: link escapes the repository: {target}")
            elif fragment and fragment.lower() not in parsed[path][0]:
                findings.append(f"{path}:{number}: missing anchor #{fragment}")
    return findings


# --- Workflow policy ----------------------------------------------------------

WORKFLOW_DIRECTORY = ".github/workflows"

# Ordinary CI must never see runtime-account material, so these names may not
# appear in executable workflow lines (comments are stripped before matching).
CREDENTIAL_WORDS = re.compile(r"codex|openai|anthropic|claude|auth\.json", re.I)
PINNED_ACTION = re.compile(r"^(?:\./\S*|[\w.-]+/[\w./-]+@[0-9a-f]{40}|docker://\S+@sha256:[0-9a-f]{64})$")


def scan_workflow(path: str, text: str) -> list[str]:
    """Check one workflow file's text against the credential-free CI policy.

    Inputs: relative path and file text. Output: findings as ``path:line: message``.
    The check is textual on purpose (no YAML dependency): comments are blanked,
    then forbidden constructs, unpinned actions and missing read-only permissions
    are reported. It cannot prove a workflow safe; it blocks the known regressions.
    """
    findings: list[str] = []
    lines = [re.sub(r"(^|\s)#.*$", "", line) for line in text.splitlines()]
    body = "\n".join(lines)
    if not re.search(r"^permissions:\s*\n\s+contents:\s*read\s*$", body, re.M):
        findings.append(f"{path}:1: top-level permissions must be exactly contents: read")
    for number, line in enumerate(lines, start=1):
        # Only expression use (``${{ secrets.X }}``) or ``secrets: inherit`` reads secrets;
        # the word may appear in step names such as "Scan for secrets".
        if re.search(r"\$\{\{[^}]*\bsecrets\b|\bsecrets:\s*inherit", line):
            findings.append(f"{path}:{number}: workflow must not reference repository secrets")
        if re.search(r"\bpull_request_target\b|\bworkflow_run\b", line):
            findings.append(f"{path}:{number}: trigger runs contributed code with a privileged token")
        if re.search(r":\s*write(-all)?\b|\bwrite-all\b", line):
            findings.append(f"{path}:{number}: write permission is not allowed")
        if CREDENTIAL_WORDS.search(line):
            findings.append(f"{path}:{number}: runtime credential reference in ordinary CI")
        if re.search(r"^\s*(?:-\s+)?image:\s*\S+", line) and "@sha256:" not in line:
            findings.append(f"{path}:{number}: container image is not pinned by digest")
        used = re.match(r"^(\s*)(?:-\s+)?uses:\s*(\S+)\s*$", line)
        if used:
            if not PINNED_ACTION.match(used.group(2)):
                findings.append(f"{path}:{number}: action is not pinned to a full commit SHA")
            if used.group(2).startswith("actions/checkout@"):
                # The `with:` block of this step sits on the following lines, up to the next step.
                following = []
                for later in lines[number:]:
                    if re.match(r"^\s*-\s", later):
                        break
                    following.append(later)
                if not any(re.search(r"persist-credentials:\s*false", s) for s in following):
                    findings.append(f"{path}:{number}: checkout must set persist-credentials: false")
    return findings


def check_workflows(root: Path = ROOT) -> list[str]:
    """Apply ``scan_workflow`` to every workflow; fail when none exist.

    A repository without a workflow would otherwise pass vacuously, hiding a
    deleted or renamed CI definition.
    """
    files = [p for p in project_files(root) if p.startswith(WORKFLOW_DIRECTORY + "/") and p.endswith((".yml", ".yaml"))]
    if not files:
        return [f"{WORKFLOW_DIRECTORY}:1: no workflow found"]
    findings: list[str] = []
    for path in files:
        findings.extend(scan_workflow(path, (root / path).read_text(encoding="utf-8")))
    return findings


# --- Container image pins -----------------------------------------------------

# ``name:exact-tag@sha256:<64 hex>``: an exact tag documents the version and the
# digest decides what is pulled (ADR 0009). A registry port is not supported.
IMAGE_PIN = re.compile(r"^[\w.-]+(?:/[\w.-]+)*:(?!latest@)[\w.+-]+@sha256:[0-9a-f]{64}$")


def scan_dockerfile(path: str, text: str) -> list[str]:
    """Check one Dockerfile's ``# syntax=`` line and every external ``FROM`` reference.

    A ``FROM`` that names an earlier build stage (including a ``prefix-${ARG}``
    stage selector) is exempt; a ``${ARG}`` reference is resolved through that
    ARG's declared default so the pin is checked where it is written.
    """
    findings: list[str] = []
    defaults: dict[str, str] = {}
    stages: list[str] = []
    for number, line in enumerate(text.splitlines(), start=1):
        syntax = re.match(r"^#\s*syntax\s*=\s*(\S+)", line)
        if syntax and not IMAGE_PIN.match(syntax.group(1)):
            findings.append(f"{path}:{number}: syntax frontend is not pinned by exact tag and digest")
        arg = re.match(r"^ARG\s+(\w+)=(\S+)", line)
        if arg:
            defaults[arg.group(1)] = arg.group(2)
        source = re.match(r"^FROM\s+(?:--\S+\s+)*(\S+)(?:\s+AS\s+(\S+))?", line, re.I)
        if not source:
            continue
        reference = source.group(1)
        whole_arg = re.fullmatch(r"\$\{(\w+)\}", reference)
        if whole_arg:
            reference = defaults.get(whole_arg.group(1), reference)
        # Turn ``fetch-${TARGETARCH}`` into a pattern that earlier stage names must match.
        stage_pattern = "".join(".+" if part.startswith("${") else re.escape(part)
                                for part in re.split(r"(\$\{[^}]+\})", reference))
        is_stage = any(re.fullmatch(stage_pattern, stage) for stage in stages)
        if not is_stage and not IMAGE_PIN.match(reference):
            findings.append(f"{path}:{number}: base image is not pinned by exact tag and digest")
        if source.group(2):
            stages.append(source.group(2))
    return findings


def scan_compose(path: str, text: str) -> list[str]:
    """Check Compose ``image:`` lines; images built locally (same service has ``build:``) are exempt.

    The scan is textual: a service is the run of lines indented deeper than its
    two-space key, which matches this project's Compose style.
    """
    findings: list[str] = []
    lines = text.splitlines()
    for number, line in enumerate(lines, start=1):
        image = re.match(r"^(\s+)image:\s*(\S+)", line)
        if not image or IMAGE_PIN.match(image.group(2)):
            continue
        # Walk outward from the image line to the service's first and last line.
        start = number - 1
        while start > 0 and (not lines[start - 1].strip() or len(lines[start - 1]) - len(lines[start - 1].lstrip()) >= len(image.group(1))):
            start -= 1
        end = number
        while end < len(lines) and (not lines[end].strip() or len(lines[end]) - len(lines[end].lstrip()) >= len(image.group(1))):
            end += 1
        builds = any(re.match(rf"^\s{{{len(image.group(1))}}}build:", other) for other in lines[start:end])
        if not builds:
            findings.append(f"{path}:{number}: external image is not pinned by exact tag and digest")
    return findings


def scan_launcher(path: str, text: str) -> list[str]:
    """Check image constants in the root launcher (``readonly NAME_IMAGE="..."``).

    The launcher runs some images directly, such as the pinned model-server image
    of ``--models profile --gpu-check`` (P21.1), so they follow the same rule as
    Dockerfile and Compose references.
    """
    findings: list[str] = []
    for number, line in enumerate(text.splitlines(), start=1):
        constant = re.match(r'^\s*readonly\s+[A-Z][A-Z0-9_]*_IMAGE="([^"]*)"', line)
        if constant and not IMAGE_PIN.match(constant.group(1)):
            findings.append(f"{path}:{number}: launcher image is not pinned by exact tag and digest")
    return findings


def check_images(root: Path = ROOT) -> list[str]:
    """Apply the Dockerfile, Compose and launcher pin rules to every such project file."""
    findings: list[str] = []
    for path in project_files(root):
        name = path.rsplit("/", 1)[-1]
        if name == "Dockerfile" or name.startswith("Dockerfile."):
            findings.extend(scan_dockerfile(path, (root / path).read_text(encoding="utf-8")))
        elif name in {"compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml"}:
            findings.extend(scan_compose(path, (root / path).read_text(encoding="utf-8")))
        elif path == "start.sh":
            findings.extend(scan_launcher(path, (root / path).read_text(encoding="utf-8")))
    return findings


# Controllers whose stable error codes the campaign screen must explain, and the module that maps them.
CAMPAIGN_CONTROLLERS = (
    "backend/src/main/java/org/rpgengine/campaign/CampaignProjectionController.java",
    "backend/src/main/java/org/rpgengine/campaign/CampaignStreamController.java",
    "backend/src/main/java/org/rpgengine/campaign/CampaignCreationController.java",
)
CAMPAIGN_MESSAGES = "frontend/src/app/campaign/campaign-messages.ts"
# Creation codes belong to the future creation screen and have their own contract (P06.1).
CAMPAIGN_CODE = re.compile(r'"((?:COMMAND|CAMPAIGN|STREAM|LOCAL)_[A-Z0-9_]+)"')

# The owner model settings API (P21.3-D): its controller and the slot validation that supplies the
# 422 codes, and the module the Settings → Models panel maps them in.
MODELS_CONTROLLERS = (
    "backend/src/main/java/org/rpgengine/inference/slots/ModelSettingsController.java",
    "backend/src/main/java/org/rpgengine/inference/slots/SlotSetting.java",
)
MODELS_MESSAGES = "frontend/src/app/models/models-messages.ts"
MODELS_CODE = re.compile(r'"((?:MODEL|SLOT|FALLBACK|CLASS|LOCAL)_[A-Z0-9_]+)"')

# (label, controllers, message module, code pattern) of each screen whose backend codes must be explained.
LOCALIZED_SURFACES = (
    ("campaign", CAMPAIGN_CONTROLLERS, CAMPAIGN_MESSAGES, CAMPAIGN_CODE),
    ("model settings", MODELS_CONTROLLERS, MODELS_MESSAGES, MODELS_CODE),
)


def check_localization(root: Path = ROOT) -> list[str]:
    """Return findings for backend error codes lacking a localized browser message.

    Every string literal matching a surface's code pattern in its controllers is a public code
    (campaign: ``COMMAND_*``, ``CAMPAIGN_*``, ``STREAM_*``, ``LOCAL_*``; model settings:
    ``MODEL_*``, ``SLOT_*``, ``FALLBACK_*``, ``CLASS_*``, ``LOCAL_*``). Each must appear as a key of
    the ``ERRORS`` table in that surface's message module; the i18n tooling then guarantees an
    Italian target. A missing controller or message module is itself a finding, so moving a file
    cannot disable the check.
    """
    findings: list[str] = []
    for label, controllers, messages_file, pattern in LOCALIZED_SURFACES:
        messages_path = root / messages_file
        if not messages_path.is_file():
            findings.append(f"{messages_file}:1: {label} message module is missing")
            continue
        mapped = set(re.findall(r"^\s{2}([A-Z][A-Z0-9_]+):\s*\(\)\s*=>", messages_path.read_text(encoding="utf-8"), re.M))
        for controller in controllers:
            path = root / controller
            if not path.is_file():
                findings.append(f"{controller}:1: {label} controller is missing")
                continue
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                for code in pattern.findall(line):
                    if code not in mapped:
                        findings.append(f"{controller}:{number}: error code has no localized browser message")
    return findings


# Golden transcripts recorded by backend tests, paired with the frontend copies their consumer specs import.
CONTRACT_COPIES = (
    ("backend/src/test/resources/contracts/campaign-reload-transcript.json",
     "frontend/src/app/campaign/contract/campaign-reload-transcript.json"),
)


def check_contracts(root: Path = ROOT) -> list[str]:
    """Return findings for recorded protocol transcripts whose frontend copy drifted from the golden file.

    The backend test (``CampaignInterfaceContractTest``) compares a fresh real-server recording with
    the golden file; the frontend specs replay their own copy because the frontend build context
    contains no backend sources. A missing file on either side is a finding, as is any byte
    difference (the files are LF-normalized by ``.gitattributes``, so no line-ending tolerance is
    needed). Findings name the frontend copy and never print transcript content.
    """
    findings: list[str] = []
    for golden, copy in CONTRACT_COPIES:
        missing = [path for path in (golden, copy) if not (root / path).is_file()]
        findings.extend(f"{path}:1: recorded contract transcript is missing" for path in missing)
        if missing:
            continue
        if (root / golden).read_bytes() != (root / copy).read_bytes():
            findings.append(f"{copy}:1: contract copy differs from {golden}; rerun the backend test with "
                            "-Dengine.contract.update=true")
    return findings


def main(argv: list[str]) -> int:
    """Run the selected checks, print findings and return the process exit status.

    ``argv`` holds zero or one selector (``secrets``, ``docs``, ``workflows``, ``images``, ``localization``,
    ``contracts``, ``all``). An unknown selector returns 2; findings return 1; a clean run returns 0.
    """
    selected = argv[0] if argv else "all"
    if selected not in {"secrets", "docs", "workflows", "images", "localization", "contracts", "all"}:
        print("usage: checks.py [secrets|docs|workflows|images|localization|contracts|all]", file=sys.stderr)
        return 2
    findings: list[str] = []
    if selected in {"secrets", "all"}:
        findings.extend(check_secrets())
    if selected in {"docs", "all"}:
        findings.extend(check_docs())
    if selected in {"workflows", "all"}:
        findings.extend(check_workflows())
    if selected in {"images", "all"}:
        findings.extend(check_images())
    if selected in {"localization", "all"}:
        findings.extend(check_localization())
    if selected in {"contracts", "all"}:
        findings.extend(check_contracts())
    for finding in findings:
        print(finding)
    print(f"{selected}: {len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
