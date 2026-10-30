# Database backup and restore (V18 scope)

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
  successful migrations V1–V18, including immutable pack bytes and sealed reports. It then creates the target directory, which must
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
- Starting the engine on the restored database runs startup journal, presentation and
  job reconciliation before HTTP serves requests. Interrupted operations become
  `FAILED_BEFORE_COMMIT`. Committed operations that still need narration become
  `COMMITTED_NARRATION_PENDING`: legacy commits directly, and P07.5 presentation jobs
  because the restore advanced their campaign generation (`GENERATION_RETIRED`).
  Public stream cursors from before the restore receive `stream.reset` for the same
  reason. Unfinished jobs from retired generations become
  `SUPERSEDED`, retaining interrupted attempt history. Claims/admissions remain
  gated until the job scan succeeds. See [turn protocol §8](../../docs/07-turn-protocol.md#8-interrupt-and-recovery-matrix).

## Limits

P06.5 optional workspace folders contain derived copies of these sealed originals,
not additional canonical data. Restore into a new offline database, then start
the engine with `ENGINE_WORKSPACES_ROOT` pointing to an empty private persistent
directory to reconstruct adventure/revision folders and integrity manifests with
the preserved IDs. An existing complete projection is verified byte-for-byte;
altered or partial published trees fail startup for offline operator repair.
Generated campaign media and identity-remapped workspace restores remain outside
this checkpoint. This does not change the database archive format or table scope.

- The scope is the database only. Registered pack bytes (including declared assets
  and EN/IT resources), hashes, exports and immutable version 1/2 reports are included.
  External assets, unregistered pack directories and configuration are not included.
  Earlier-schema archives require a separately reviewed recovery procedure; this
  tool accepts only the current exact schema. A future migration or table makes the tool refuse until it is
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
- The P04.6 V8 rehearsal (24 October 2026) seeded both complete bilingual fixture
  inventories with sealed report versions 1 and 2, then backed up and restored into
  a new disposable database. Original bytes, aggregate/per-file hashes, revision
  metadata, exports, reports and migration catalogs matched exactly; the source
  remained unchanged. These were explicit recovery rows, not historical registry
  imports. This check does not extend gameplay/startup acceptance.
- The P03.5 rehearsal ran manually against PostgreSQL 18.6 (backup, tamper
  rejection, restore, data comparison, lease retirement and engine startup
  reconciliation). CI runs only the offline unit tests.
- The P03.6 rehearsal (24 October 2026) restored V6 snapshots, accepted plans,
  admissions, a completed critical job with a delivered origin, a live running job
  and Italian preferences. Immutable rows matched; recovery generation advanced 0→1,
  mutation fence 4→5, a usable plan gained an invalidation, and jar startup superseded
  the old running job. Canonical revision/turn/time/event counters stayed 7/3/60/12.

V9 scope includes `character_state`, `item_instance`, `learned_ability` and
`campaign_creation`, so initial allocations, grants and idempotent request results
are retained with campaign pins. The schema/table allowlist tests cover this scope;
the earlier V8 live dump/restore rehearsal is not V9 rehearsal evidence.

V10 scope also includes `campaign_known_fact` and `campaign_known_quest`, preserving
explicit actor audiences, fact confidence and known quest objectives/status.
The exact migration/table regression check reproduced the P06.2 CI failure before
this scope update; older schemas and unknown tables remain rejected. Offline
checks cover the V10 manifest/catalog. The P06.5 rehearsal (25 October 2026)
used the shipped backup/restore commands on PostgreSQL 18.6 with two same-title
adventures and sealed fantasy/science-fiction imports. All 32 canonical tables
matched the source after restore; a fresh JVM with an empty workspace root rebuilt
34 byte-identical specification/artwork files and integrity manifests. The source
database remained unchanged. This verifies V10 authored-content recovery, not
populated gameplay memory, drafts, jobs or generated-media recovery under QA-33.

V11 scope (P07.5) adds `campaign_stream_head`, `campaign_stream_message` and
`presentation_job`, so the stream high-water mark, the retained public messages and
owed presentation survive a backup. Offline checks cover the V11 manifest/catalog; no
live V11 dump/restore rehearsal has been run.

V12 scope (P07.6) adds `plan_activation` (one immutable row per plan branch an
ordinary committed command used) and two nullable gate columns on `plan_admission`.
Restore keeps activation history as audit evidence; the existing restore step still
retires every usable plan with a `GENERATION_RETIRED` disposition, and activation
refuses retired generations, so a restored plan cannot be used again. Offline checks
cover the V12 manifest/catalog; no live V12 dump/restore rehearsal has been run.

V13–V15 scope (P08.5, P08.6, P09.1) adds `narration_publication` (immutable accepted
narration per operation), `inference_task` and `inference_scheduler_state` (the fair
scheduler ledger and the installation-wide account backoff), and the typed knowledge
records `knowledge_claim`, `knowledge_belief`, `actor_sighting` and `actor_commitment`.
Restore needs no new retirement step: publications and knowledge are append-only
history, the preserved backoff only delays background admissions, and engine startup
already ends restored `QUEUED` inference tasks `ABANDONED` and takes over `DISPATCHED`/
`RECONCILING` rows as `RECONCILING` with their charge, while the advanced recovery
generation retires presentation jobs. The exact migration/table regression check
reproduced the hosted CI failure (run 40743829288) before this scope update. Offline
checks cover the V15 manifest/catalog; no live V13–V15 dump/restore rehearsal has been run.

V16 scope (P09.3) adds `memory_summary_job`, `memory_summary` and `memory_invalidation`
(continuity summaries, their bounded jobs and explicit invalidations) and the
`memory_operational_snapshot` function behind operational checkpoints, which stay in
`campaign_checkpoint`. Restore needs no new retirement step: the advanced recovery
generation makes every restored summary stale, the next job claim scan cancels queued
summary jobs of the old generation (`GENERATION_RETIRED`), and a restored `RUNNING` job is
requeued or failed by lease recovery and can no longer record because its generation is
retired. Checkpoints describe canonical state at their revision, which a restore does not
change. Offline checks cover the V16 manifest/catalog; no live V16 dump/restore rehearsal
has been run.

V17 scope (P09.4) adds `ai_session_record`, the durable records of logical AI sessions and
their renewal (counters, rehydration basis and the verified checkpoint hash). They are
diagnostics and continuity provenance, never canonical state. Restore needs no new
retirement step: the restored backend's startup recovery marks every record left `ACTIVE`
as retired with reason `RESTART`, no runtime conversation survives a restart, and the
advanced recovery generation makes each line's next session rehydrate from the restored
state. Offline checks cover the V17 manifest/catalog; no live V17 dump/restore rehearsal
has been run.

V18 scope (P10.1) adds `actor_condition` (active conditions with stacks and remaining rounds)
and `equipment_assignment` (which item instance each actor wears, with its slot and hands), and
a unique constraint on `item_instance (campaign_id, actor_id, id)`. Both tables are canonical
character state: restore them with the campaign rows they reference. They need no extra
restore step. Offline checks cover the V18 manifest/catalog; no live V18 dump/restore
rehearsal has been run.

V19 scope (P10.2) adds `item_creation`, the append-only ledger that links every item unit created
after campaign creation to its operation, announcing event and source (an effect node or a settled
reward). It references `operation_journal`, `campaign_event` and `actor`, so restore it with the
campaign rows; it needs no extra restore step, and a restored ledger keeps a replayed source from
creating a second time. Offline checks cover the V19 manifest/catalog; no live V19 dump/restore
rehearsal has been run.

V20 scope (P10.3) adds `quest_state` (one row per quest that has left its pack-declared initial
status, with its outcome once it has ended), `quest_objective` (objectives that were satisfied; rows
are never changed) and `location_visit` (locations an actor has been recorded at). They reference
`campaign`, `campaign_event` and `actor`, so restore them with the campaign rows. A restored ended quest
stays ended and its reward settlement in `reward_settlement` stays unique, so a restore cannot pay a
quest reward a second time. Offline checks cover the V20 manifest/catalog; no live V20 dump/restore
rehearsal has been run.

V21 scope (P10.4) adds no table: it bounds `campaign.world_time_seconds` and `campaign_event.world_time_seconds`
at 3,155,760,000 seconds (check constraints) and adds the `campaign_world_time_step` trigger, which lets the clock
change only in an update that also advances `state_revision`, by at most 86,400 seconds. A restore inserts rows,
so only the check constraints apply to restored values: a dump whose clock passes the bound is refused, while the
step trigger guards later updates and does not re-check restored history. Offline checks cover the V21
manifest/catalog; no live V21 dump/restore rehearsal has been run.

V22 scope (P11.1) adds `encounter` (one row per started encounter: template, location, pinned content revision
and ruleset hash, the frozen combat profile and definition with their SHA-256, the opening policy and the
lifecycle phase, round and opening-strike status) and `encounter_participant` (the frozen roster: the player
at ordinal 0 backed by its actor, template participants with encounter-local Health and Focus). They reference
`campaign`, `content_revision`, `operation_journal`, `campaign_event` and `actor`, so restore them with the
campaign rows. `pg_restore` creates triggers after loading data, so a restored encounter in any phase loads;
the lifecycle, frozen-column and one-active-encounter guards apply to later writes, and the partial unique
index is built over the restored rows. Offline checks cover the V22 manifest/catalog; no live V22 dump/restore
rehearsal has been run.

V23 scope (P11.2) adds no table: `encounter` gains the tactical cursor (`actor_cursor`, `action_points`,
`speech_used`) and the `passages` changed by scene actions, and `encounter_participant` gains `initiative`,
`initiative_rank` (unique per encounter) and `reactions`. Check constraints tie the cursor to its points and to
the activation phases; the `encounter_schedule` and `encounter_initiative_once` triggers guard later updates
(initiative before the first round and never re-rolled, points granted only at an activation start and within the
frozen allowance, the speech allowance). A restore inserts rows, so only the check and unique constraints apply to
restored values. Offline checks cover the V23 manifest/catalog; no live V23 dump/restore rehearsal has been run.
