"""Rehearse a real restore of the bundled sample with an active fight, pending work and lost responses.

P14.2-B (QA-03, QA-23). Inputs are the tracked sources and a locally generated fixture password.
With the harness of `test_restore_isolation.py`, the test starts its own PostgreSQL 18.6 on a
private tmpfs and an internal network, and seeds the source through the backend's
`SampleRestoreCheck`. The sample is provisioned as a first start does, with a fight entered and
its initiative roll cut off before commit, permit held. A supplies variant uses a tonic and pays
a quest reward, each retried under the same request identity. The test then runs
`tools/database_backup/backup.py` as an operator would, restores into a new database and
compares it row by row. A new JVM with an empty managed-folder root then proves the recovery and
retries both requests and the cut roll. No account, narration runtime, browser or generated image
is involved. Any Docker, Maven or comparison failure fails, and every container is removed.

It runs with no opt-in flag and follows `docker_requirement.py`, like the harness it reuses.
"""

import json
from pathlib import Path
import tempfile
import unittest

from docker_requirement import requires_docker_cli
import test_restore_isolation as rehearsal


# Tables whose rows the source must hold, or the row comparison proves nothing about them: the
# fight, the cut attempt's draws and held permit, the consumed item, the paid reward and the asset.
POPULATED = (
    "adventure_workspace", "content_revision", "content_revision_file", "campaign", "actor", "item_instance",
    "item_creation", "reward_settlement", "quest_state", "encounter", "encounter_participant", "campaign_event",
    "operation_journal", "operation_random_draw", "campaign_mutation_lease", "presentation_job",
)


@requires_docker_cli
class SampleRestoreTest(rehearsal.RestoreRehearsal, unittest.TestCase):
    """Back up and restore the sample mid-fight with the shipped tool, between two backend processes."""

    CHECK = "SampleRestoreCheck"

    def count(self, database: str, statement: str) -> int:
        """Return one count read from the disposable database."""
        return int(self.psql(database, statement))

    def test_restore_recovers_an_active_fight_and_replays_lost_responses(self) -> None:
        """Prove the archived rows, the recovery in a new JVM, and an untouched source."""
        self.prepare()
        self.backend("seedSourceInstallation", rehearsal.SOURCE, f"{rehearsal.STORAGE}/source")
        source = self.rows(rehearsal.SOURCE)
        for table in POPULATED:
            self.assertGreater(source[table][0], 0, f"{table} must hold rows for this comparison to prove anything")
        pending = "select count(*) from operation_journal where status = 'VALIDATING'"
        held = "select count(*) from campaign_mutation_lease where operation_id is not null"
        self.assertEqual(1, self.count(rehearsal.SOURCE, pending), "the backup catches one attempt before its commit")
        self.assertEqual(1, self.count(rehearsal.SOURCE, held), "with its permit held")

        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "archive"
            self.tool("backup", "--container", self.database_container, "--role", "engine",
                      "--database", rehearsal.SOURCE, "--directory", str(archive))
            self.assertEqual(source, self.rows(rehearsal.SOURCE), "a backup must not change its source")
            manifest = json.loads((archive / "manifest.json").read_text(encoding="utf-8"))
            self.tool("restore", "--container", self.database_container, "--role", "engine",
                      "--database", rehearsal.SOURCE, "--directory", str(archive),
                      "--new-database", rehearsal.RESTORED)

        # Before any engine process touches it, the restore differs only by its retirement step.
        self.assertEqual(self.rows(rehearsal.SOURCE, retired_columns=True),
                         self.rows(rehearsal.RESTORED, retired_columns=True))
        self.assertEqual(1, self.count(rehearsal.RESTORED, pending), "the restore keeps the pending attempt")
        self.assertEqual(0, self.count(rehearsal.RESTORED, held), "and retires its permit")

        self.backend("restoredInstallationRecoversTheSample", rehearsal.RESTORED, f"{rehearsal.STORAGE}/restored")
        self.assertEqual(0, self.count(rehearsal.RESTORED, pending), "the start ended the pending attempt")
        self.assertEqual(source, self.rows(rehearsal.SOURCE), "a restore and its startup must not change the source")
        print(f"\nsample restore rehearsal: schema {manifest['schema_version']}, {len(source)} tables, "
              f"{sum(count for count, _ in source.values())} rows, {manifest['bytes']} dump bytes; rows per "
              "populated table: " + ", ".join(f"{table}={source[table][0]}" for table in POPULATED))


if __name__ == "__main__":
    unittest.main()
