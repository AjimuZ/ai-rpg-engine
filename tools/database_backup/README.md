# Database backup and restore (P03.6 foundation)

A trusted-owner disaster-recovery tool for the canonical PostgreSQL database. It
uses only the Python standard library and the `pg_dump`/`pg_restore` clients inside
the pinned PostgreSQL 18.6 container, reached with `docker exec`. It reads no
password, account or configuration file, and on failure it prints only a fixed
message, never database diagnostics or row values.

```sh
# Stop the engine (all writers) first, then write a new private directory.
py -B tools/database_backup/backup.py backup --container <db-container> --role engine \
    --database engine --directory backups/2026-10-24
# Restore into a NEW database; existing databases are never dropped or overwritten.
py -B tools/database_backup/backup.py restore --container <db-container> --role engine \
    --database engine --directory backups/2026-10-24 --new-database engine_restored
py -B -m unittest discover -s tools/database_backup -v   # offline unit tests
```

## What it does

- **Backup** checks the exact server and client version, the exact table set and
  successful migrations V1–V6. It then creates the target directory, which must
  not already exist, and writes `database.dump` (custom format, no owners or
  privileges). It re-reads the catalog and writes `manifest.json` last. The manifest
  holds the format, schema and PostgreSQL versions, the scope, the byte count, the
  SHA-256 and the catalog. A directory without a manifest is incomplete and must
  not be restored.
- **Restore** validates the manifest, size and checksum before any write. It refuses
  symbolic links, empty dumps and dumps over 2 GiB. It runs `CREATE DATABASE` as an
  exclusive claim (an existing name fails), restores in a single transaction with
  `--exit-on-error`, and compares the restored catalog with the manifest. It then
  atomically increments each campaign's recovery generation and storage version,
  appends `INVALIDATED`/`GENERATION_RETIRED` to usable plan histories, and retires
  every saved mutation lease (fence + 1, owner cleared). Canonical counters and
  immutable preparation records remain unchanged; old callbacks cannot be admitted.
- Starting the engine on the restored database runs startup journal and job
  reconciliation before HTTP serves requests. Interrupted operations become
  `FAILED_BEFORE_COMMIT`, and committed operations that still need narration become
  `COMMITTED_NARRATION_PENDING`. Unfinished jobs from retired generations become
  `SUPERSEDED`, retaining interrupted attempt history. Claims/admissions remain
  gated until the job scan succeeds. See [turn protocol §8](../../docs/07-turn-protocol.md#8-interrupt-and-recovery-matrix).

## Limits

- The scope is the database only. Asset files, content packs and configuration are
  not included yet. A future migration or table makes the tool refuse until it is
  reviewed, and `test_scope_matches_backend_migrations` enforces that.
- The checksum detects corruption, not deliberate tampering by someone who can
  rewrite the manifest. Restore only archives you created and stored privately.
- Directory and file modes `0700`/`0600` apply on POSIX hosts. On Windows they are
  not enforced, so keep archives under the user profile or the ignored `backups/`
  directory, never in a shared or synced folder.
- This is single-installation recovery with preserved campaign identities. Stop all
  writers and old runtime workers before backup/restore and keep the restored target
  offline until successful startup reconciliation. Campaign cloning/identity remapping
  remains P16. The tool detects schema changes
  during the dump but not concurrent row writes; `pg_dump` itself gives one
  consistent snapshot.
- A failed restore leaves the new database in place but offline, for diagnosis.
  The tool never deletes it automatically.
- The P03.5 rehearsal ran manually against PostgreSQL 18.6 (backup, tamper
  rejection, restore, data comparison, lease retirement and engine startup
  reconciliation). CI runs only the offline unit tests.
- The P03.6 rehearsal (24 October 2026) restored V6 snapshots, accepted plans,
  admissions, a completed critical job with a delivered origin, a live running job
  and Italian preferences. Immutable rows matched; recovery generation advanced 0→1,
  mutation fence 4→5, a usable plan gained an invalidation, and jar startup superseded
  the old running job. Canonical revision/turn/time/event counters stayed 7/3/60/12.
