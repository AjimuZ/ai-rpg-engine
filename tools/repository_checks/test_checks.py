"""Unit and integration tests for the credential-free repository checks.

The tests build neutral fixture repositories under the ignored ``.local/scratch``
directory and run the real checker against them with real Git. Credential-like
fixture strings are assembled from fragments at runtime so that this file itself
passes the secret scan it tests. No network, account or environment secret is used.
A missing Git executable fails the tests rather than skipping them.
"""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import checks  # noqa: E402  (path setup must precede the import)

ROOT = checks.ROOT
SCRATCH = ROOT / ".local" / "scratch"

# A minimal workflow that satisfies every policy rule; failure tests mutate it.
GOOD_WORKFLOW = """\
name: CI
on:
  push:
    branches: [main]
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false
      - run: echo ok
"""


class FixtureRepository:
    """Temporary Git repository under ``.local/scratch`` for end-to-end checks.

    Use as a context manager. Files are written but never committed: the checker
    lists tracked plus not-ignored untracked files, which covers both cases.
    The directory is removed on exit, even after a failed assertion.
    """

    def __enter__(self) -> "FixtureRepository":
        """Create the repository; raise if Git cannot initialize it."""
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.root = Path(tempfile.mkdtemp(prefix="repo-checks-", dir=SCRATCH))
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        return self

    def __exit__(self, *exc: object) -> None:
        """Delete the fixture tree (only the directory this instance created)."""
        shutil.rmtree(self.root, ignore_errors=True)

    def write(self, name: str, text: str) -> None:
        """Write one UTF-8 file, creating parent directories."""
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8", newline="\n")


class PathRuleTest(unittest.TestCase):
    """Private path rules reject publishable secrets and accept sanitized examples."""

    def test_forbidden_paths(self) -> None:
        """Each private location produces at least one finding."""
        for path in [".env", "backend/.env.local", ".local/secrets/x", ".codex/auth.json",
                     ".claude/settings.local.json", ".agents/hooks/x", "AGENTS.md", "CLAUDE.md",
                     "runtime-data/db", "backups/dump.sql", "certs/server.pem", "home/id_rsa",
                     "x/postgres-password", "config/secrets/token", "engine.log"]:
            with self.subTest(path=path):
                self.assertTrue(checks.scan_path(path), path)

    def test_allowed_paths(self) -> None:
        """Sanitized examples and similarly named public fixtures are accepted."""
        for path in [".env.example", "docs/16-development-roadmap.md", "backend/pom.xml",
                     "tools/runtime_probe/isolation_fixture/home-AGENTS.md",
                     "docker/codex-runtime/Dockerfile", ".github/workflows/ci.yml"]:
            with self.subTest(path=path):
                self.assertEqual([], checks.scan_path(path))


class ContentRuleTest(unittest.TestCase):
    """Content rules find credential shapes and never echo the matched value."""

    def test_credential_shapes(self) -> None:
        """Representative provider token formats are detected."""
        samples = {
            "private key block": "-----BEGIN " + "RSA PRIVATE KEY-----",
            "cloud access key id": "key = AKIA" + "ABCDEFGHIJKLMNOP",
            "repository host token": "t = gh" + "p_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8",
            "provider API key": "export K=sk" + "-abcdefghijklmnopqrstuvwx",
            "chat service token": "x" + "oxb-1234567890-abcdef",
            "signed JSON web token": "ey" + "JhbGciOiJIUzI1NiJ9.ey" + "JzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghij1234",
            "account token field": '{"refresh' + '_token": "abcdefghijklmnopqrstuv"}',
            "hard-coded credential assignment": "db_pass" + "word = Tr0ub4dor9xKq2mZ",
            "developer home path": "path C:" + "\\Users\\" + "alice" + "\\project",
        }
        for name, line in samples.items():
            with self.subTest(rule=name):
                findings = checks.scan_text("f.txt", line)
                self.assertEqual(["f.txt:1: " + name], findings)

    def test_findings_do_not_echo_values(self) -> None:
        """A finding names the rule and line but never contains the credential text."""
        value = "Tr0ub4dor9xKq2mZ"
        findings = checks.scan_text("f.txt", "api_" + "key: " + value)
        self.assertTrue(findings)
        self.assertNotIn(value, "".join(findings))

    def test_placeholders_and_generic_paths_pass(self) -> None:
        """Variable names, file-secret references and generic home paths are accepted."""
        for line in ["POSTGRES_PASSWORD_FILE=./.local/secrets/postgres-password",
                     "spring.datasource.password=${postgres_password}",
                     "password: ${DB_PASSWORD}", "token = <your-token-here>",
                     "mount /run/secrets/postgres_password",
                     "cd /home/user/project", "run /var/lib/runtime/home/config.toml",
                     "C:" + "\\Users\\" + "<name>" + "\\x", "/Users/you/x",
                     "sample sk-short", "token: " + "abcdefghijklmnop  # secret-scan: allow"]:
            with self.subTest(line=line):
                self.assertEqual([], checks.scan_text("f.txt", line))


class DocumentationTest(unittest.TestCase):
    """Markdown structure checks cover anchors, relative links and fences."""

    def test_slugify_matches_hosting_anchors(self) -> None:
        """Headings with code, links and punctuation map to the generated anchor."""
        self.assertEqual("packaged-runtime-configuration-p023", checks.slugify("Packaged runtime configuration (P02.3)"))
        self.assertEqual("use-the-x-api", checks.slugify("Use the `X` [API](a.md)"))
        self.assertEqual("a--b", checks.slugify("A / B"))

    def test_duplicate_headings_get_numeric_suffixes(self) -> None:
        """Repeated headings are addressable as slug, slug-1, slug-2."""
        anchors, _, _ = checks.markdown_structure("# T\n## Same\n## Same\n## Same\n")
        self.assertTrue({"t", "same", "same-1", "same-2"} <= anchors)

    def test_links_in_code_are_ignored(self) -> None:
        """Link syntax inside fences and inline code is not a link."""
        text = "```md\n[x](missing.md)\n```\n`[y](missing.md)` and [z](real.md)\n"
        _, links, errors = checks.markdown_structure(text)
        self.assertEqual([(4, "real.md")], links)
        self.assertEqual([], errors)

    def test_unclosed_fence_is_reported(self) -> None:
        """An opening fence without a close names the opening line."""
        _, _, errors = checks.markdown_structure("text\n```sh\necho\n")
        self.assertEqual(["unclosed code fence opened on line 2"], errors)

    def test_repository_fixture(self) -> None:
        """Good links pass; missing files, anchors, escapes and fences are findings."""
        with FixtureRepository() as repo:
            repo.write("docs/a.md", "# A\n[ok](b.md#target) [self](#a) [ext](https://example.org/x)\n")
            repo.write("docs/b.md", "# Target\n")
            self.assertEqual([], checks.check_docs(repo.root))
            repo.write("docs/c.md", "[gone](missing.md) [bad](b.md#nope) [up](../../out.md) [s](#none)\n```\nopen\n")
            findings = checks.check_docs(repo.root)
            self.assertEqual(5, len(findings), findings)
            self.assertTrue(any("broken link target missing.md" in f for f in findings))
            self.assertTrue(any("missing anchor #nope in docs/b.md" in f for f in findings))
            self.assertTrue(any("escapes the repository" in f for f in findings))
            self.assertTrue(any("missing anchor #none" in f for f in findings))
            self.assertTrue(any("unclosed code fence" in f for f in findings))


class SecretScanRepositoryTest(unittest.TestCase):
    """End-to-end secret scan on fixture repositories with a real ``.gitignore``."""

    # Mirrors the project's private-path ignore rules so the policy check passes.
    IGNORE = ".env\n.env.*\n!.env.example\n.local/\nruntime-data/\nbackups/\n*.log\n.codex/\n.claude/\n.agents/\nAGENTS.md\nCLAUDE.md\n"

    def test_clean_repository_passes(self) -> None:
        """Neutral files plus a sanitized example produce no findings."""
        with FixtureRepository() as repo:
            repo.write(".gitignore", self.IGNORE)
            repo.write(".env.example", "APP_PORT=8080\n")
            repo.write("README.md", "# Neutral\n")
            self.assertEqual([], checks.check_secrets(repo.root))

    def test_secret_content_and_missing_ignore_rules_fail(self) -> None:
        """A leaked key and an incomplete ignore file are both reported."""
        with FixtureRepository() as repo:
            repo.write(".gitignore", ".env\n")
            repo.write("notes.txt", "key = AKIA" + "ABCDEFGHIJKLMNOP\n")
            findings = checks.check_secrets(repo.root)
            self.assertIn("notes.txt:1: cloud access key id", findings)
            self.assertTrue(any("private path is not ignored: CLAUDE.md" in f for f in findings))

    def test_ignored_private_files_are_not_scanned(self) -> None:
        """Ignored local files never count; an unignored one is rejected by path."""
        with FixtureRepository() as repo:
            repo.write(".gitignore", self.IGNORE)
            repo.write(".local/secrets/postgres-password", "irrelevant\n")
            self.assertEqual([], checks.check_secrets(repo.root))
            repo.write("config/auth.json", "{}\n")
            self.assertIn("config/auth.json:1: forbidden path (account state file)", checks.check_secrets(repo.root))

    def test_git_failure_is_not_a_pass(self) -> None:
        """Outside a repository the scan raises instead of passing vacuously."""
        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as plain:
            # Without .git here, Git would search upward into this checkout, so the
            # directory is declared a ceiling that Git must not cross.
            env_ceiling = str(Path(plain).parent)
            import os
            previous = os.environ.get("GIT_CEILING_DIRECTORIES")
            os.environ["GIT_CEILING_DIRECTORIES"] = env_ceiling
            try:
                with self.assertRaises(RuntimeError):
                    checks.project_files(Path(plain))
            finally:
                if previous is None:
                    del os.environ["GIT_CEILING_DIRECTORIES"]
                else:
                    os.environ["GIT_CEILING_DIRECTORIES"] = previous


class WorkflowPolicyTest(unittest.TestCase):
    """The workflow scanner accepts the baseline and rejects each known regression."""

    def test_baseline_passes(self) -> None:
        """The minimal pinned, read-only workflow has no findings."""
        self.assertEqual([], checks.scan_workflow("w.yml", GOOD_WORKFLOW))

    def test_regressions_are_rejected(self) -> None:
        """Every mutation below must produce a finding that names its cause."""
        pinned = "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
        mutations = {
            "repository secrets": GOOD_WORKFLOW.replace("echo ok", "echo ${{ secrets." + "TOKEN }}"),
            "inherited secrets": GOOD_WORKFLOW + "    secrets: inherit\n",
            "privileged trigger": GOOD_WORKFLOW.replace("push:", "pull_request" + "_target:\n  push:"),
            "write permission": GOOD_WORKFLOW.replace("contents: read", "contents: write"),
            "extra write scope": GOOD_WORKFLOW.replace("contents: read", "contents: read\n  packages: write"),
            "missing permissions": GOOD_WORKFLOW.replace("permissions:\n  contents: read\n", ""),
            "unpinned action": GOOD_WORKFLOW.replace(pinned, "actions/checkout@v7"),
            "short sha": GOOD_WORKFLOW.replace(pinned, "actions/checkout@3d3c42e"),
            "persisted credentials": GOOD_WORKFLOW.replace("persist-credentials: false", "fetch-depth: 1"),
            "runtime account": GOOD_WORKFLOW.replace("echo ok", "cp ~/.co" + "dex/auth.json ."),
            "unpinned image": GOOD_WORKFLOW.replace("    steps:", "    services:\n      db:\n        image: postgres:18\n    steps:"),
        }
        for name, text in mutations.items():
            with self.subTest(mutation=name):
                self.assertTrue(checks.scan_workflow("w.yml", text), name)

    def test_comments_are_not_policy_violations(self) -> None:
        """Documentation comments may mention forbidden words without failing."""
        text = "# never use secrets." + "X or the " + "co" + "dex login\n" + GOOD_WORKFLOW
        self.assertEqual([], checks.scan_workflow("w.yml", text))

    def test_missing_workflow_fails(self) -> None:
        """A repository with no workflow is a finding, not a vacuous pass."""
        with FixtureRepository() as repo:
            self.assertEqual([".github/workflows:1: no workflow found"], checks.check_workflows(repo.root))
            repo.write(".github/workflows/ci.yml", GOOD_WORKFLOW)
            self.assertEqual([], checks.check_workflows(repo.root))


class ImagePinTest(unittest.TestCase):
    """Dockerfile and Compose references must carry an exact tag and an index digest."""

    DIGEST = "@sha256:" + "a" * 64

    def test_dockerfile_pins(self) -> None:
        """Pinned images, stage references and ARG-defaulted bases pass."""
        text = (
            "# syntax=docker/dockerfile:1.27.1" + self.DIGEST + "\n"
            "ARG BASE=debian:trixie-slim" + self.DIGEST + "\n"
            "FROM node:24.22.1-trixie-slim" + self.DIGEST + " AS web\n"
            "FROM ${BASE} AS fetch-amd64\n"
            "FROM fetch-${TARGETARCH} AS fetch\n"
            "FROM --platform=linux/amd64 web AS final\n"
        )
        self.assertEqual([], checks.scan_dockerfile("Dockerfile", text))

    def test_dockerfile_regressions(self) -> None:
        """Floating tags, tag-only, digest-only, unpinned ARG and syntax lines are findings."""
        cases = {
            "floating tag": "FROM node:latest" + self.DIGEST + "\n",
            "tag only": "FROM node:24.22.1\n",
            "digest only": "FROM node" + self.DIGEST + "\n",
            "unknown stage": "FROM builder AS x\n",
            "unpinned ARG default": "ARG BASE=debian:trixie-slim\nFROM ${BASE}\n",
            "undeclared ARG": "FROM ${BASE}\n",
            "unpinned syntax": "# syntax=docker/dockerfile:1\nFROM node:24" + self.DIGEST + "\n",
        }
        for name, text in cases.items():
            with self.subTest(case=name):
                self.assertTrue(checks.scan_dockerfile("Dockerfile", text), name)

    def test_compose_pins(self) -> None:
        """Locally built images are exempt; external images need a digest."""
        good = (
            "services:\n  engine:\n    build:\n      context: .\n    image: local/engine:1\n"
            "  db:\n    image: postgres:18.6-trixie" + self.DIGEST + "\n"
        )
        self.assertEqual([], checks.scan_compose("compose.yaml", good))
        bad = good + "  cache:\n    image: redis:8\n"
        self.assertEqual(["compose.yaml:9: external image is not pinned by exact tag and digest"],
                         checks.scan_compose("compose.yaml", bad))
        # A build key in a different service must not exempt this one.
        other = "services:\n  a:\n    build: .\n  b:\n    image: redis:8\n"
        self.assertEqual(1, len(checks.scan_compose("compose.yaml", other)))

    def test_launcher_image_constants(self) -> None:
        """Launcher image constants need a tag and digest; other constants are ignored."""
        good = ('readonly MODEL_SERVER_IMAGE="ghcr.io/ggml-org/llama.cpp:server-cuda-b11960' + self.DIGEST + '"\n'
                'readonly STATE_DIR=".local"\n')
        self.assertEqual([], checks.scan_launcher("start.sh", good))
        for bad in ('readonly MODEL_SERVER_IMAGE="ghcr.io/ggml-org/llama.cpp:server-cuda"\n',
                    'readonly MODEL_SERVER_IMAGE="ghcr.io/ggml-org/llama.cpp' + self.DIGEST + '"\n',
                    '    readonly WORKER_IMAGE="worker:latest' + self.DIGEST + '"\n'):
            with self.subTest(line=bad):
                self.assertEqual(["start.sh:1: launcher image is not pinned by exact tag and digest"],
                                 checks.scan_launcher("start.sh", bad))


class LocalizationCodeTest(unittest.TestCase):
    """Campaign error codes must stay explained in the browser interface."""

    MESSAGES = "  COMMAND_INVALID: () => $localize`x`,\n  STREAM_CAPACITY_EXCEEDED: () => $localize`y`,\n"

    def _repository(self, controller: str, messages: str | None) -> Path:
        """Build a minimal fixture repository under scratch with one controller and optional messages."""
        SCRATCH.mkdir(parents=True, exist_ok=True)
        root = Path(tempfile.mkdtemp(dir=SCRATCH))
        self.addCleanup(shutil.rmtree, root, True)
        for name in checks.CAMPAIGN_CONTROLLERS:
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(controller, encoding="utf-8")
        if messages is not None:
            target = root / checks.CAMPAIGN_MESSAGES
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(messages, encoding="utf-8")
        return root

    def test_mapped_codes_pass_and_unmapped_codes_are_reported(self) -> None:
        """A new controller code without a message is a finding naming file and line, never the code."""
        good = 'return error(422, "COMMAND_INVALID");\nreturn error(429, "STREAM_CAPACITY_EXCEEDED");\n'
        self.assertEqual([], checks.check_localization(self._repository(good, self.MESSAGES)))
        bad = good + 'return error(409, "COMMAND_NEW_FAILURE");\n'
        findings = checks.check_localization(self._repository(bad, self.MESSAGES))
        self.assertEqual(len(checks.CAMPAIGN_CONTROLLERS), len(findings))
        self.assertTrue(all(":3: error code has no localized browser message" in item for item in findings))
        self.assertFalse(any("COMMAND_NEW_FAILURE" in item for item in findings))

    def test_missing_files_cannot_disable_the_check(self) -> None:
        """A moved message module or controller is itself reported."""
        self.assertEqual(1, len(checks.check_localization(self._repository("", None))))
        root = self._repository("", self.MESSAGES)
        (root / checks.CAMPAIGN_CONTROLLERS[0]).unlink()
        self.assertEqual(["%s:1: campaign controller is missing" % checks.CAMPAIGN_CONTROLLERS[0]], checks.check_localization(root))


class ContractCopyTest(unittest.TestCase):
    """Recorded protocol transcripts must be identical on the producer and consumer side."""

    def _repository(self, golden: bytes | None, copy: bytes | None) -> Path:
        """Build a minimal fixture repository under scratch with optional golden and copy files."""
        SCRATCH.mkdir(parents=True, exist_ok=True)
        root = Path(tempfile.mkdtemp(dir=SCRATCH))
        self.addCleanup(shutil.rmtree, root, True)
        for (golden_path, copy_path) in checks.CONTRACT_COPIES:
            for path, content in ((golden_path, golden), (copy_path, copy)):
                if content is not None:
                    (root / path).parent.mkdir(parents=True, exist_ok=True)
                    (root / path).write_bytes(content)
        return root

    def test_identical_copies_pass_and_drift_is_reported(self) -> None:
        """One changed byte in the frontend copy is a finding that names the copy, not its content."""
        text = b'{"schemaVersion": 1, "body": "Arrivi al riparo"}\n'
        self.assertEqual([], checks.check_contracts(self._repository(text, text)))
        findings = checks.check_contracts(self._repository(text, text.replace(b"riparo", b"rifugio")))
        self.assertEqual(len(checks.CONTRACT_COPIES), len(findings))
        self.assertIn(checks.CONTRACT_COPIES[0][1] + ":1: contract copy differs", findings[0])
        self.assertNotIn("rifugio", findings[0])

    def test_missing_files_cannot_disable_the_check(self) -> None:
        """Deleting either side is reported instead of passing."""
        self.assertEqual([checks.CONTRACT_COPIES[0][1] + ":1: recorded contract transcript is missing"],
                         checks.check_contracts(self._repository(b"{}", None)))
        self.assertEqual(2, len(checks.check_contracts(self._repository(None, None))))


class ProjectTest(unittest.TestCase):
    """The committed project itself satisfies every check (same as the CI steps)."""

    def test_project_is_clean(self) -> None:
        """No secret, private path, broken link, workflow-policy or image-pin finding exists."""
        for name, check in [("secrets", checks.check_secrets), ("docs", checks.check_docs),
                            ("workflows", checks.check_workflows), ("images", checks.check_images),
                            ("localization", checks.check_localization), ("contracts", checks.check_contracts)]:
            with self.subTest(check=name):
                self.assertEqual([], check(ROOT))


if __name__ == "__main__":
    unittest.main()
