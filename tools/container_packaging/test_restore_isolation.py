"""Rehearse the shipped backup tool on a populated two-genre world.

Inputs are the tracked sources and a locally generated fixture password. The test
builds the offline verification image, starts its own PostgreSQL 18.6 on a private
tmpfs and an internal network, seeds the QA-33 world through the backend's
`AdventureRestoreCheck`, runs `tools/database_backup/backup.py` as an operator would
and proves the restored database row by row. A new JVM then starts on it with an empty
managed-folder root and asserts every read, inventory, memory record, job limit and
folder. Containers and the network carry a unique suffix and are always removed; a
killed run can leave them behind, and they block nothing later. No account, gameplay
inference or generated image is involved. Any Docker, Maven or comparison failure fails.

It runs with no opt-in flag and follows `docker_requirement.py`: only a host without a
`docker` executable, where nothing requires Docker, leaves it out. Hosted CI requires it.
"""

import json
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time
import unittest

from docker_requirement import requires_docker_cli


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "tools" / "database_backup" / "backup.py"
IMAGE = "rpg-engine/packaging-verification:0.1.0"
SOURCE = "engine"
RESTORED = "engine_restored"
STORAGE = "/build/restore-check"
# The only columns the tool's documented retirement step may change: it advances each
# campaign's recovery generation and retires every saved mutation lease.
RETIRED_COLUMNS = {
    "campaign": ("recovery_generation", "row_version"),
    "campaign_mutation_lease": ("fence", "operation_id", "holder_id", "expires_at"),
}
# Tables whose populated rows the world must contain, or the row comparison proves nothing.
POPULATED = (
    "adventure_workspace", "content_revision", "content_revision_file", "campaign", "actor", "item_instance",
    "item_creation", "equipment_assignment", "campaign_event", "operation_journal", "knowledge_claim",
    "knowledge_belief", "actor_commitment", "memory_summary", "memory_summary_job", "inference_task",
    "inference_scheduler_state",
)


@requires_docker_cli
class RestoreIsolationTest(unittest.TestCase):
    """Run the real tool between two real backend phases, with no shared process."""

    def docker(self, *arguments: str, timeout: int = 180) -> str:
        """Run Docker without a shell; return stdout or fail with bounded fixture-only output."""
        result = subprocess.run(["docker", *arguments], capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=timeout, check=False)
        self.assertEqual(0, result.returncode, (result.stdout[-4000:] + result.stderr[-4000:]))
        return result.stdout.strip()

    def psql(self, database: str, statement: str) -> str:
        """Run one read of the disposable database inside its container; SQL errors fail."""
        return self.docker("exec", self.database_container, "psql", "-X", "-U", "engine", "-d", database,
                           "-v", "ON_ERROR_STOP=1", "-Atc", statement)

    def rows(self, database: str, *, retired_columns: bool = False) -> dict:
        """Return table to (row count, MD5 of its sorted canonical rows), including flyway history.

        `retired_columns` removes only the columns the retirement step may change, so
        every other value of every row of every table must match exactly.
        """
        digests = {}
        for table in self.psql(database, "select tablename from pg_tables where schemaname = 'public' "
                                         "order by tablename").splitlines():
            removed = "".join(f" - '{column}'" for column in RETIRED_COLUMNS.get(table, ())) if retired_columns else ""
            count, digest = self.psql(
                database,
                f"select count(*), md5(coalesce(string_agg(r, E'\\n' order by r), '')) from "
                f"(select (to_jsonb(t){removed})::text as r from {table} t) rows").split("|")
            digests[table] = (int(count), digest)
        return digests

    def backend(self, method: str, database: str, root: str) -> None:
        """Run one phase in a new JVM against one database; require exactly one passing test."""
        result = subprocess.run(
            ["docker", "exec",
             "--env", f"SPRING_DATASOURCE_URL=jdbc:postgresql://{self.database_container}:5432/{database}",
             "--env", "SPRING_DATASOURCE_USERNAME=engine",
             "--env", f"SPRING_DATASOURCE_PASSWORD={self.password}",
             "--env", f"ENGINE_WORKSPACES_ROOT={root}",
             "--env", f"ENGINE_RESTORE_CHECK_DIR={STORAGE}",
             self.worker, "./mvnw", "-B", "-ntp", "--offline",
             "-Dmaven.repo.local=/opt/verification-maven/repository", "-Ppackaged-ui", "test",
             f"-Dtest=AdventureRestoreCheck#{method}"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600, check=False)
        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output[-8000:])
        summaries = re.findall(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)", output)
        self.assertTrue(summaries, "Maven must report the executed phase")
        self.assertEqual(("1", "0", "0", "0"), summaries[-1])

    def tool(self, *arguments: str) -> None:
        """Run the shipped tool exactly as its README documents; any failure fails the rehearsal."""
        result = subprocess.run([sys.executable, "-B", str(TOOL), *arguments], capture_output=True, text=True,
                                timeout=600, check=False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def start_database(self) -> None:
        """Start the composed PostgreSQL pin on tmpfs; bound the wait across its first-run restart."""
        compose = (ROOT / "compose.yaml").read_text(encoding="utf-8")
        image = re.search(r"image:\s*(postgres:\S+)", compose).group(1)
        self.docker("run", "--detach", "--name", self.database_container, "--network", self.network,
                    "--env", "POSTGRES_DB=engine", "--env", "POSTGRES_USER=engine",
                    "--env", f"POSTGRES_PASSWORD={self.password}", "--tmpfs", "/var/lib/postgresql", image)
        self.addCleanup(self.docker, "rm", "--force", self.database_container)
        ready = 0
        deadline = time.monotonic() + 90
        while ready < 2 and time.monotonic() < deadline:
            probe = subprocess.run(["docker", "exec", self.database_container, "pg_isready", "--quiet",
                                    "--username", "engine", "--dbname", "engine"], capture_output=True, timeout=30,
                                   check=False)
            ready = ready + 1 if probe.returncode == 0 else 0
            time.sleep(2)
        self.assertEqual(2, ready, "the fixture database did not become ready")

    def test_backup_restores_every_scope_into_empty_storage(self) -> None:
        """Prove canonical rows, then every scope through a new JVM, and an untouched source."""
        suffix = secrets.token_hex(4)
        self.network = f"engine-restore-check-{suffix}"
        self.database_container = f"{self.network}-db"
        self.worker = f"{self.network}-worker"
        self.password = secrets.token_urlsafe(24)

        self.docker("build", "--quiet", "--target", "verification", "--tag", IMAGE,
                    "--file", str(ROOT / "docker/engine/Dockerfile"), str(ROOT), timeout=1200)
        self.docker("network", "create", "--internal", self.network)
        self.addCleanup(self.docker, "network", "rm", self.network)
        self.start_database()
        self.docker("run", "--detach", "--name", self.worker, "--network", self.network,
                    "--entrypoint", "sleep", IMAGE, "3600")
        self.addCleanup(self.docker, "rm", "--force", self.worker)
        self.docker("exec", self.worker, "mkdir", "-m", "0700", STORAGE)

        self.backend("seedSourceInstallation", SOURCE, f"{STORAGE}/source")
        source = self.rows(SOURCE)
        for table in POPULATED:
            self.assertGreater(source[table][0], 0, f"{table} must hold rows for this comparison to prove anything")

        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "archive"
            self.tool("backup", "--container", self.database_container, "--role", "engine", "--database", SOURCE,
                      "--directory", str(archive))
            self.assertEqual(source, self.rows(SOURCE), "a backup must not change its source")
            manifest = json.loads((archive / "manifest.json").read_text(encoding="utf-8"))
            self.tool("restore", "--container", self.database_container, "--role", "engine", "--database", SOURCE,
                      "--directory", str(archive), "--new-database", RESTORED)

        # Before any engine process touches it, the restore differs only by its retirement step.
        self.assertEqual(self.rows(SOURCE, retired_columns=True), self.rows(RESTORED, retired_columns=True))
        self.assertEqual(source["campaign"][0], int(self.psql(
            RESTORED, "select count(*) from campaign where recovery_generation = 1")))
        self.assertEqual("0", self.psql(SOURCE, "select count(*) from campaign where recovery_generation <> 0"))

        self.backend("restoredInstallationKeepsEveryScope", RESTORED, f"{STORAGE}/restored")
        self.assertEqual(source, self.rows(SOURCE), "a restore and its startup must not change the source")

        files = self.docker("exec", self.worker, "find", f"{STORAGE}/restored", "-type", "f").splitlines()
        print(f"\nrestore rehearsal: schema {manifest['schema_version']}, {len(source)} tables, "
              f"{sum(count for count, _ in source.values())} rows, {manifest['bytes']} dump bytes, "
              f"{len(files)} rebuilt files; rows per populated table: "
              + ", ".join(f"{table}={source[table][0]}" for table in POPULATED))


if __name__ == "__main__":
    unittest.main()
