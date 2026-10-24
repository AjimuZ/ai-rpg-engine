"""Unit tests for the database backup/restore tool, without Docker or a database.

A scripted ``Database`` subclass replaces the ``docker exec`` boundary so that the
archive layout, manifest validation, exclusive-creation rules, restore ordering and
lease retirement can be checked offline. Fixture directories live under the ignored
``.local/scratch`` directory. The real PostgreSQL rehearsal is a separate manual
check documented in the README; these tests prove the fail-closed file logic only.
"""

from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backup  # noqa: E402  (path setup must precede the import)

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = ROOT / ".local" / "scratch"
MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"

# Catalog the fake server reports; it mirrors what backup.catalog() returns.
CATALOG = {"tables": backup.TABLES,
           "migrations": [{"version": v, "checksum": 1, "success": True} for v in backup.MIGRATIONS]}


class FakeDatabase(backup.Database):
    """Scripted stand-in for one database; records every program call it receives.

    ``catalogs`` is a shared list of catalogs returned by successive catalog reads,
    so tests can simulate a schema change during backup or a restore mismatch.
    ``calls`` collects (database, program, arguments) tuples; SQL is one argument.
    """

    def __init__(self, name="engine", *, catalogs=None, calls=None, server="180006"):
        """Create a fake bound to ``name``; inputs are validated like the real class."""
        super().__init__("engine-db", "engine", name)
        self.catalogs = catalogs if catalogs is not None else []
        self.calls = calls if calls is not None else []
        self.server = server

    def run(self, program, arguments, *, source=None, destination=None):
        """Answer the few programs the tool uses; anything unexpected fails the test."""
        self.calls.append((self.name, program, list(arguments)))
        if arguments == ["--version"]:
            return f"{program} (PostgreSQL) 18.6 (Debian 18.6-1)\n".encode()
        if program == "pg_dump":
            destination.write(b"PGDMP fixture bytes")
            return b""
        if program == "pg_restore":
            # The dump must be streamed from the validated file, not re-read by path.
            assert source.read() == b"PGDMP fixture bytes"
            return b""
        raise AssertionError(f"unexpected program {program}")

    def sql(self, statement):
        """Return scripted metadata for the catalog and version queries; record writes."""
        self.calls.append((self.name, "psql", [statement]))
        if statement == "show server_version_num":
            return self.server
        if statement.startswith("select tablename"):
            return "\n".join(self._next_catalog()["tables"])
        if statement.startswith("select nspname"):
            return ""
        if statement.startswith("select coalesce(json_agg"):
            # The table query already consumed this catalog; reuse its migrations.
            return json.dumps(self.current["migrations"])
        return ""

    def _next_catalog(self):
        """Pop the next scripted catalog, or the supported one when the script is empty."""
        self.current = self.catalogs.pop(0) if self.catalogs else CATALOG
        return self.current


class BackupToolTest(unittest.TestCase):
    """Offline checks of validation, archive creation and restore ordering."""

    def setUp(self):
        """Create a private scratch root removed after each test."""
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.root = Path(self.temporary.name)

    def tearDown(self):
        """Delete only the scratch root this test created."""
        self.temporary.cleanup()

    def make_backup(self):
        """Produce a valid archive with the fake database and return its directory."""
        directory = self.root / "snapshot"
        backup.backup(FakeDatabase(), directory)
        return directory

    def test_scope_matches_backend_migrations(self):
        """A new migration or table must force a reviewed tool update."""
        versions = sorted(int(re.match(r"V(\d+)__", path.name).group(1))
                          for path in MIGRATIONS.glob("V*.sql"))
        self.assertEqual([str(v) for v in versions], backup.MIGRATIONS)
        created = set()
        for path in MIGRATIONS.glob("V*.sql"):
            created.update(re.findall(r"(?i)create table (?:if not exists )?([a-z_]+)", path.read_text("utf-8")))
        self.assertEqual(sorted(created | {"flyway_schema_history"}), backup.TABLES)

    def test_rejects_unsafe_targets_before_any_process(self):
        """Option-like, quoted or uppercase names never reach docker or SQL."""
        for name in ("-d", "Engine", 'x"; drop', "", "a" * 64):
            with self.assertRaises(ValueError):
                backup.identifier(name)
        for container in ("-it", "a b", "", "x;y"):
            with self.assertRaises(ValueError):
                backup.Database(container, "engine", "engine")

    def test_backup_writes_dump_then_manifest_and_never_reuses_directory(self):
        """The manifest describes the dump exactly; an existing directory is refused."""
        directory = self.make_backup()
        manifest = json.loads((directory / "manifest.json").read_text("utf-8"))
        self.assertEqual(manifest["bytes"], len(b"PGDMP fixture bytes"))
        self.assertEqual(manifest["catalog"], CATALOG)
        self.assertEqual(backup.validate_archive(directory), manifest)
        with self.assertRaises(FileExistsError):
            backup.backup(FakeDatabase(), directory)

    def test_backup_rejects_wrong_version_scope_or_concurrent_schema_change(self):
        """Version, scope and mid-dump catalog changes fail, and no manifest is published."""
        with self.assertRaises(ValueError):
            backup.backup(FakeDatabase(server="180005"), self.root / "old")
        self.assertFalse((self.root / "old").exists())
        extra = dict(CATALOG, tables=sorted(backup.TABLES + ["unexpected"]))
        with self.assertRaises(ValueError):
            backup.backup(FakeDatabase(catalogs=[extra]), self.root / "extra")
        failed = dict(CATALOG, migrations=[dict(m, success=False) for m in CATALOG["migrations"]])
        with self.assertRaises(ValueError):
            backup.backup(FakeDatabase(catalogs=[failed]), self.root / "failed")
        changed = dict(CATALOG, migrations=CATALOG["migrations"][:-1] + [dict(CATALOG["migrations"][-1], checksum=2)])
        with self.assertRaises(ValueError):
            backup.backup(FakeDatabase(catalogs=[CATALOG, changed]), self.root / "changed")
        self.assertFalse((self.root / "changed" / "manifest.json").exists())

    def test_archive_validation_fails_closed(self):
        """Corrupt, oversized, empty, unknown or incomplete archives are rejected."""
        directory = self.make_backup()
        dump = directory / "database.dump"
        dump.write_bytes(b"PGDMP fixture bytez")
        with self.assertRaises(ValueError):
            backup.validate_archive(directory)
        dump.write_bytes(b"")
        with self.assertRaises(ValueError):
            backup.validate_archive(directory)
        dump.write_bytes(b"PGDMP fixture bytes")
        with mock.patch.object(backup, "MAX_DUMP_BYTES", 4), self.assertRaises(ValueError):
            backup.validate_archive(directory)
        manifest = directory / "manifest.json"
        original = manifest.read_text("utf-8")
        for change in ({"format": "other"}, {"schema_version": "4"}, {"postgres_version": "18.5"},
                       {"scope": "any"}, {"catalog": {"tables": []}}):
            manifest.write_text(json.dumps(dict(json.loads(original), **change)), "utf-8")
            with self.assertRaises(ValueError):
                backup.validate_archive(directory)
        manifest.write_text(original[:20], "utf-8")
        with self.assertRaises(ValueError):
            backup.validate_archive(directory)
        manifest.unlink()
        with self.assertRaises(ValueError):
            backup.validate_archive(directory)

    def test_archive_validation_rejects_symlinked_dump(self):
        """A dump replaced by a link is refused even when the target bytes would match."""
        directory = self.make_backup()
        dump = directory / "database.dump"
        target = self.root / "elsewhere.dump"
        target.write_bytes(dump.read_bytes())
        dump.unlink()
        try:
            os.symlink(target, dump)
        except OSError:
            self.skipTest("symbolic links need extra privileges on this host")
        with self.assertRaises(ValueError):
            backup.validate_archive(directory)

    def test_restore_creates_new_database_then_retires_leases(self):
        """Restore order is create, single-transaction restore, catalog check, lease retirement."""
        directory = self.make_backup()
        calls = []
        source = FakeDatabase(calls=calls)
        with mock.patch.object(backup, "Database", lambda c, r, n: FakeDatabase(n, calls=calls)):
            backup.restore(source, directory, "engine_restored")
        create = next(i for i, call in enumerate(calls) if call[2][-1].startswith("create database"))
        restore = next(i for i, call in enumerate(calls) if call[1] == "pg_restore" and call[2] != ["--version"])
        retire = next(i for i, call in enumerate(calls) if "campaign_mutation_lease" in call[2][-1])
        # The claim runs on the source connection; every write afterwards targets the new database.
        self.assertEqual(calls[create], ("engine", "psql", ['create database "engine_restored" template template0']))
        self.assertEqual(calls[restore][0], "engine_restored")
        self.assertIn("--single-transaction", calls[restore][2])
        self.assertIn("--exit-on-error", calls[restore][2])
        self.assertNotIn("--clean", calls[restore][2])
        self.assertEqual(calls[retire][0], "engine_restored")
        self.assertLess(create, restore)
        self.assertLess(restore, retire)
        self.assertIn("fence = fence + 1", calls[retire][2][-1])
        self.assertIn("recovery_generation = recovery_generation + 1", calls[retire][2][-1])
        self.assertIn("row_version = row_version + 1", calls[retire][2][-1])
        self.assertIn("insert into plan_disposition", calls[retire][2][-1])
        self.assertIn("'GENERATION_RETIRED'", calls[retire][2][-1])

    def test_restore_mismatch_keeps_leases_untouched_and_reports_failure(self):
        """A restored catalog that differs stops before lease retirement and success output."""
        directory = self.make_backup()
        calls = []
        wrong = dict(CATALOG, migrations=CATALOG["migrations"][:-1])
        with mock.patch.object(backup, "Database", lambda c, r, n: FakeDatabase(n, catalogs=[wrong], calls=calls)):
            with self.assertRaises(ValueError):
                backup.restore(FakeDatabase(calls=calls), directory, "engine_restored")
        self.assertFalse(any("campaign_mutation_lease" in call[2][-1] for call in calls))
        with self.assertRaises(ValueError):
            backup.restore(FakeDatabase(), directory, "Engine")

    def test_main_prints_only_fixed_messages(self):
        """Command-line failures never echo targets, paths or database diagnostics."""
        output = io.StringIO()
        arguments = ["backup.py", "restore", "--container", "engine-db", "--role", "engine",
                     "--database", "engine", "--directory", str(self.root / "missing-secret-path")]
        with mock.patch.object(sys, "argv", arguments + ["--new-database", "engine_restored"]), redirect_stdout(output):
            self.assertEqual(backup.main(), 1)
        with mock.patch.object(sys, "argv", arguments), redirect_stdout(output):
            self.assertEqual(backup.main(), 1)
        self.assertNotIn("missing-secret-path", output.getvalue())
        self.assertEqual(output.getvalue().count("Backup/restore failed."), 2)


if __name__ == "__main__":
    unittest.main()
