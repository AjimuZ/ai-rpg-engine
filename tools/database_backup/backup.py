"""Create/restore private versioned database recovery snapshots using the pinned Docker database.

Inputs are an operator-selected container, role, database and private directory.
Outputs are a custom-format dump plus a version/checksum manifest, or a newly
created restored database with all saved mutation permits retired. Failures leave
the source untouched and never delete/overwrite a database or a pre-existing file.
This trusted-owner disaster-recovery tool is not an untrusted campaign importer.
"""

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


POSTGRES_VERSION = "18.6"
SCHEMA_VERSION = "28"
# Flyway versions this tool understands; test_backup.py fails when a new backend
# migration or table appears, so the archive scope can never silently drift.
MIGRATIONS = [str(version) for version in range(1, int(SCHEMA_VERSION) + 1)]
MAX_DUMP_BYTES = 2 * 1024**3
TABLES = sorted([
    "actor", "actor_resource", "adventure_workspace", "campaign", "campaign_checkpoint",
    "campaign_event", "campaign_mutation_lease", "campaign_random_seed", "canonical_outbox",
    "content_revision", "content_revision_file", "content_revision_report", "flyway_schema_history", "identity_owner_profile", "operation_journal",
    "operation_random_draw", "presentation_campaign_settings", "reward_settlement",
    "ai_job", "ai_job_attempt", "planning_snapshot", "query_epoch", "quest_plan_artifact",
    "plan_dependency", "plan_admission", "plan_disposition", "plan_activation",
    "character_state", "item_instance", "learned_ability", "campaign_creation",
    "campaign_known_fact", "campaign_known_quest",
    "campaign_stream_head", "campaign_stream_message", "presentation_job",
    "narration_publication", "inference_task", "inference_scheduler_state",
    "knowledge_claim", "knowledge_belief", "actor_sighting", "actor_commitment",
    "memory_summary_job", "memory_summary", "memory_invalidation",
    "ai_session_record", "actor_condition", "equipment_assignment",
    "item_creation", "quest_state", "quest_objective", "location_visit",
    "encounter", "encounter_participant", "presentation_event",
    "inference_slot_setting", "inference_slot_fallback",
])


def identifier(value: str) -> str:
    """Accept a bounded simple PostgreSQL identifier; reject syntax/options before invoking a process."""
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value):
        raise ValueError("database and role names must be simple lowercase identifiers")
    return value


class Database:
    """Bound operator-selected Docker socket client; no password/account/configuration files are read."""

    def __init__(self, container: str, role: str, name: str):
        """Validate command targets; malformed labels fail before any subprocess or database write."""
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}", container):
            raise ValueError("invalid container name")
        self.container = container
        self.role = identifier(role)
        self.name = identifier(name)

    def run(self, program: str, arguments: list[str], *, source=None, destination=None) -> bytes:
        """Run a shell-free bounded Docker command; stream dump bytes and suppress potentially private diagnostics."""
        try:
            result = subprocess.run(
                ["docker", "exec", "-i", self.container, program, *arguments],
                # Without a dump source, stdin is closed so a client can never wait on or
                # read the operator's terminal; dumps stream to a file, not into memory.
                stdin=source if source is not None else subprocess.DEVNULL,
                stdout=destination if destination is not None else subprocess.PIPE,
                stderr=subprocess.DEVNULL, timeout=300, check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            raise RuntimeError(f"{program} could not complete; check the selected local database") from None
        if result.returncode:
            # Database errors can include private row values; do not echo stderr or
            # the archive into console logs. A failed new target stays offline.
            raise RuntimeError(f"{program} failed; no recovery success is claimed")
        return result.stdout or b""

    def sql(self, statement: str) -> str:
        """Execute constant/validated administrative SQL, returning bounded metadata; errors propagate safely."""
        return self.run("psql", ["-X", "--no-password", "-U", self.role, "-d", self.name,
                                 "-v", "ON_ERROR_STOP=1", "-Atc", statement]).decode("utf-8").strip()

    def check_version(self) -> None:
        """Require the reviewed PostgreSQL server and dump/restore clients; mismatches fail before mutation."""
        if self.sql("show server_version_num") != "180006":
            raise ValueError("backup requires the reviewed PostgreSQL 18.6 server")
        for program in ("pg_dump", "pg_restore"):
            version = self.run(program, ["--version"]).decode("ascii").split()
            if len(version) < 3 or version[2] != POSTGRES_VERSION:
                raise ValueError("backup requires the reviewed PostgreSQL 18.6 clients")

    def catalog(self) -> dict:
        """Validate the supported schema and return migration evidence; extra schemas/tables fail closed."""
        tables = self.sql("select tablename from pg_tables where schemaname = 'public' order by tablename").splitlines()
        schemas = self.sql("select nspname from pg_namespace where nspname <> 'public' "
                           "and nspname <> 'information_schema' and nspname not like 'pg_%'")
        if tables != TABLES or schemas:
            raise ValueError(f"unsupported database scope; expected only the reviewed V{SCHEMA_VERSION} schema")
        migrations = json.loads(self.sql(
            "select coalesce(json_agg(json_build_object('version', version, 'checksum', checksum, "
            "'success', success) order by installed_rank), '[]') from flyway_schema_history where type = 'SQL'"))
        if ([entry["version"] for entry in migrations] != MIGRATIONS
                or any(entry["success"] is not True for entry in migrations)):
            raise ValueError("unsupported or unsuccessful migration history")
        return {"tables": tables, "migrations": migrations}


def checksum(path: Path) -> str:
    """Hash a regular bounded dump with constant memory; reject symlinks, empty/oversized and missing files."""
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_DUMP_BYTES:
        raise ValueError("dump must be a regular nonempty file no larger than 2 GiB")
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def backup(database: Database, directory: Path) -> None:
    """Write a new private directory and publish its manifest last; source data is never mutated.

    The operator must stop all writers first; pg_dump provides a consistent database
    snapshot, and the manifest checks the surrounding migration history. Interrupted
    directories without a manifest are incomplete and must never be restored.
    """
    database.check_version()
    catalog = database.catalog()
    directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    dump = directory / "database.dump"
    with dump.open("xb") as stream:
        dump.chmod(0o600)
        database.run("pg_dump", ["--no-password", "-U", database.role, "-d", database.name,
                                 "--format=custom", "--no-owner", "--no-privileges"], destination=stream)
    if catalog != database.catalog():
        raise ValueError("schema changed during backup; keep writers stopped and retry to a new directory")
    manifest = {"format": "engine-database-backup-v1", "schema_version": SCHEMA_VERSION,
                "postgres_version": POSTGRES_VERSION, "scope": "trusted-owner-database-only",
                "bytes": dump.stat().st_size, "sha256": checksum(dump), "catalog": catalog}
    # Exclusive creation avoids replacing a previous valid backup. A truncated
    # manifest fails strict parsing on restore; no archive extraction is performed.
    with (directory / "manifest.json").open("x", encoding="utf-8") as stream:
        (directory / "manifest.json").chmod(0o600)
        json.dump(manifest, stream, indent=2)
        stream.write("\n")


def validate_archive(directory: Path) -> dict:
    """Validate format/version/size/hash before any restore write; checksums detect corruption, not malicious authorship."""
    path = directory / "manifest.json"
    if directory.is_symlink() or path.is_symlink() or not path.is_file() or path.stat().st_size > 65_536:
        raise ValueError("missing or invalid backup manifest")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if (not isinstance(manifest, dict) or manifest.get("format") != "engine-database-backup-v1"
            or manifest.get("schema_version") != SCHEMA_VERSION
            or manifest.get("postgres_version") != POSTGRES_VERSION
            or manifest.get("scope") != "trusted-owner-database-only"
            or manifest.get("catalog", {}).get("tables") != TABLES):
        raise ValueError("unsupported backup manifest")
    dump = directory / "database.dump"
    if checksum(dump) != manifest.get("sha256") or dump.stat().st_size != manifest.get("bytes"):
        raise ValueError("backup checksum/size mismatch")
    return manifest


def restore(database: Database, directory: Path, new_name: str) -> None:
    """Restore trusted bytes into a new isolated database; never overwrite or drop an existing target.

    The source database argument is used only for version checks and CREATE DATABASE.
    Restore runs atomically, verifies schema history, then retires generations, usable
    plans and saved leases in one transaction without changing canonical counters.
    Failures retain the new database offline for diagnosis, never report it ready,
    and never change the source. Run the engine's startup reconciliation before use.
    """
    new_name = identifier(new_name)
    manifest = validate_archive(directory)
    database.check_version()
    # CREATE DATABASE is the exclusive claim: PostgreSQL rejects any existing name,
    # including this connection's source. No --clean, --create or DROP is allowed.
    database.sql(f'create database "{new_name}" template template0')
    target = Database(database.container, database.role, new_name)
    with (directory / "database.dump").open("rb") as stream:
        target.run("pg_restore", ["--no-password", "-U", target.role, "-d", target.name,
                                  "--single-transaction", "--exit-on-error", "--no-owner", "--no-privileges"], source=stream)
    if target.catalog() != manifest["catalog"]:
        raise ValueError("restored migration history differs; keep the new database offline")
    # An installation recovery preserves campaign identities, but a saved worker
    # capability must never become live again even when its deadline is in the future.
    # Preparation bytes remain immutable audit evidence; append invalidation for every
    # formerly usable plan before startup can accept a restored callback or new work.
    target.sql("begin; set local lock_timeout = '2s'; set local statement_timeout = '5s'; "
               "update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1; "
               "insert into plan_disposition (artifact_id, sequence, campaign_id, status, reason_code, "
               "checked_revision, created_at) select a.id, d.sequence + 1, a.campaign_id, 'INVALIDATED', "
               "'GENERATION_RETIRED', c.state_revision, clock_timestamp() from quest_plan_artifact a "
               "join campaign c on c.id = a.campaign_id cross join lateral "
               "(select sequence, status from plan_disposition where artifact_id = a.id "
               "order by sequence desc limit 1) d where d.status in ('VALIDATED', 'PARTIALLY_INVALIDATED'); "
               "update campaign_mutation_lease set fence = fence + 1, operation_id = null, "
               "holder_id = null, expires_at = null; commit;")


def main() -> int:
    """Parse explicit operator targets, execute one action and print only a fixed success/failure message."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("backup", "restore"))
    parser.add_argument("--container", required=True)
    parser.add_argument("--role", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--new-database")
    args = parser.parse_args()
    try:
        database = Database(args.container, args.role, args.database)
        if args.action == "backup":
            if args.new_database:
                raise ValueError("new database is only valid for restore")
            backup(database, args.directory)
        else:
            if not args.new_database:
                raise ValueError("restore requires a new database name")
            restore(database, args.directory, args.new_database)
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, AttributeError):
        print("Backup/restore failed. Keep any new target offline; the source was not replaced.")
        return 1
    print("Backup complete." if args.action == "backup" else "Restore complete; run engine startup reconciliation before use.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
