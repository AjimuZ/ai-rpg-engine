"""Verify Docker's real build-context filtering using neutral, isolated fixtures.

Run from any directory with Python and a Linux Docker/BuildKit daemon available.
Tests create only disposable fixture files under the repository's ignored scratch
area, export scratch-stage builds, and fail on missing Buildx, daemon or build
errors and on unexpected files. A host with no `docker` executable at all skips
them unless Docker is required (`docker_requirement.py`; hosted CI requires it).
No repository credentials are read, and no containers or volumes are started.
"""

from pathlib import Path
import subprocess
import tempfile
import unittest

from docker_requirement import requires_docker_cli


ROOT = Path(__file__).resolve().parents[2]
SCRATCH = ROOT / ".local" / "scratch"
FRONTEND = "docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e"


@requires_docker_cli
class BuildContextTest(unittest.TestCase):
    """Export fixture contexts and compare allowed files; Docker errors fail tests."""

    def verify_context(self, policy: Path, allowed: set[str], denied: set[str]) -> None:
        """Apply policy to synthetic paths and assert exact export; fail on build errors.

        Inputs are a repository ignore file and disjoint relative fixture paths.
        The only output is a test assertion and disposable exported files. Never
        send the source checkout to Docker: even a broken policy sees markers only.
        """
        self.assertTrue(policy.is_file(), f"Missing build-context policy: {policy}")
        self.assertTrue(allowed.isdisjoint(denied))
        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="p023-context-", dir=SCRATCH) as temporary:
            workspace = Path(temporary)
            context = workspace / "context"
            context.mkdir()
            (context / ".dockerignore").write_bytes(policy.read_bytes())
            for relative in allowed | denied:
                target = context / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("neutral build-context sentinel\n", encoding="utf-8")
            # Scratch images need no base-image pull. Export outside the context
            # to prevent the build output from becoming an input on repeat runs.
            dockerfile = f"# syntax={FRONTEND}\nFROM scratch\nCOPY . /\n"
            result = subprocess.run(
                ["docker", "buildx", "build", "--network=none", "--progress=quiet",
                 "--output", f"type=local,dest={workspace / 'export'}", "-f", "-", str(context)],
                input=dockerfile, text=True, capture_output=True, timeout=180, check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr[-4000:])
            actual = {path.relative_to(workspace / "export").as_posix()
                      for path in (workspace / "export").rglob("*") if path.is_file()}
            self.assertEqual(allowed, actual, "Docker context leaked or omitted fixture files")

    def test_engine_context(self) -> None:
        """Keep build inputs and reject private/generated paths at root and nested depths."""
        allowed = {
            "frontend/package.json", "frontend/package-lock.json", "frontend/.npmrc",
            "frontend/angular.json", "frontend/tsconfig.json", "frontend/tsconfig.app.json",
            "frontend/tsconfig.spec.json", "frontend/src/main.ts",
            "frontend/src/app/app.ts", "frontend/public/icon.svg",
            "frontend/tools/check-localization.mjs", "frontend/tools/check-localization.test.mjs",
            "frontend/tools/localize-index.mjs",
            "backend/pom.xml", "backend/mvnw", "backend/mvnw.cmd",
            "backend/.mvn/maven.config", "backend/.mvn/settings.xml",
            "backend/.mvn/wrapper/maven-wrapper.properties",
            "backend/src/main/java/org/rpgengine/EngineApplication.java",
            "backend/src/main/resources/application.properties",
            "backend/src/test/java/org/rpgengine/EngineApplicationTest.java",
            "backend/src/packaged-ui/resources/static/index.html",
            "docker/engine/Dockerfile",
            "tools/runtime_probe/fixtures/0.164.0/neutral-session.json",
        }
        denied = {"README.md", "docs/private.md", "tools/private.py", "unknown/future.txt",
                  "tools/runtime_probe/probe.py", "tools/runtime_probe/fixtures/private.json",
                  "tools/runtime_probe/fixtures/0.164.0/private.json",
                  "tools/runtime_probe/fixtures/0.165.0/neutral-session.json",
                  ".env.example", "backend/target/engine.jar", "frontend/dist/index.html",
                  "frontend/node_modules/example/index.js", "frontend/.angular/cache/data",
                  "docker/codex-runtime/private.txt", "docker/engine/private.txt",
                  "frontend/README.md", "backend/README.md", "backend/.mvn/private.txt",
                  "backend/.mvn/wrapper/private.txt", "frontend/tools/private.mjs",
                  "frontend/tools/browser-smoke.mjs"}
        # Repeat sensitive names under admitted source trees: root-only patterns
        # would miss developer instructions or credentials placed in nested folders.
        private = [".git/config", ".agents/hooks/private.py", ".claude/settings.json",
                   ".codex/auth.json", "AGENTS.md", "AGENTS.override.md", "CLAUDE.md",
                   "CLAUDE.local.md", ".mcp.json", ".env", ".env.production",
                   ".local/secret.txt", "runtime-data/auth.json", "backups/save.sql",
                   "logs/session.txt", "secrets/password", "auth.json", "private.key",
                   "certificate.pem", "debug.log", "__pycache__/module.pyc",
                   ".idea/workspace.xml", ".vscode/settings.json", "local.iml",
                   ".DS_Store", "Thumbs.db", "Desktop.ini", ".ssh/id_rsa",
                   ".aws/credentials", ".gnupg/private.key", "account.p12", "account.pfx"]
        denied.update(prefix + name for prefix in ("", "frontend/src/nested/", "backend/src/main/resources/")
                      for name in private)
        self.verify_context(ROOT / ".dockerignore", allowed, denied)

    def test_runtime_context(self) -> None:
        """Admit only the reviewed Dockerfile/isolation profile in the runtime context."""
        allowed = {"Dockerfile", "managed_config.toml", "requirements.toml",
                   "engine-model-catalog.json", "engine-instructions.md", "runtime-entrypoint.sh"}
        self.verify_context(ROOT / "docker/codex-runtime/.dockerignore", allowed,
                            {".env", "auth.json", "private.key", "README.md",
                             ".local/account.json", ".codex/auth.json", "src/private.txt"})

    def test_gateway_context(self) -> None:
        """Admit only the gateway's Dockerfile and source, so no key or allowlist can reach an image."""
        self.verify_context(ROOT / "docker/gateway/.dockerignore", {"Dockerfile", "gateway.py"},
                            {".env", "destinations.json", "README.md", "scripted", "private.key",
                             "keys/scripted", "secrets/gateway/scripted", ".local/secrets/gateway/scripted",
                             "nested/gateway.py", "nested/Dockerfile"})

    def test_image_worker_context(self) -> None:
        """Admit only the worker's Dockerfile, source and locks, so no weights can reach an image."""
        self.verify_context(ROOT / "docker/image-worker/.dockerignore",
                            {"Dockerfile", "image_worker.py", "requirements.lock", "build-requirements.lock"},
                            {".env", "README.md", "model.safetensors", "style.ckpt", "loras/style.safetensors",
                             ".local/models/model.safetensors", "private.key", "nested/image_worker.py",
                             "nested/requirements.lock", "nested/Dockerfile"})

    def test_image_worker_dockerfile_passes_the_builders_check(self) -> None:
        """Parse the real Dockerfile with the builder's own check; this builds nothing and runs no step."""
        context = ROOT / "docker/image-worker"
        result = subprocess.run(["docker", "buildx", "build", "--check", "--progress=plain", "-f", str(context / "Dockerfile"), str(context)],
                                capture_output=True, text=True, timeout=180, check=False)
        self.assertEqual(0, result.returncode, result.stdout[-2000:] + result.stderr[-2000:])


if __name__ == "__main__":
    unittest.main()
