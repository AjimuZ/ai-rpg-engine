package org.rpgengine.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.memory.MemorySummaries;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Java side of the real QA-33 restore rehearsal. The name keeps it out of backend test runs, because
 * it needs a database the shipped backup tool can reach: {@code tools/container_packaging/test_restore_isolation.py}
 * runs {@link #seedSourceInstallation} against a source database, runs the tool, and runs
 * {@link #restoredInstallationKeepsEveryScope} against the restored database in a new process with an empty
 * managed-folder root. {@code ENGINE_RESTORE_CHECK_DIR} carries the evidence between the two phases. Rows are
 * deliberately not removed; the databases are disposable, and seeding refuses a database holding owners.
 */
@TestPropertySource(properties = "engine.memory.reconciliation.enabled=true")
class AdventureRestoreCheck extends AdventureIsolationFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What the source installation served before its backup, compared byte for byte after the restore. */
    record Evidence(World world, long backgroundUsage, Map<String, String> reads, Map<String, String> folders) {
    }

    /** Seed the world and restart it in a separate JVM, then leave queued work and evidence for the restore. */
    @Test
    void seedSourceInstallation() throws Exception {
        assertEquals(0, count("identity_owner_profile"), "the rehearsal needs a new, disposable database");
        World world = seed();
        long background = usage();
        Map<String, String> reads = assertReadsAreIsolated(world, client(serverPort));
        assertMemoryIsIsolated(world);
        Map<String, String> folders = assertFoldersAreIsolated(world, Path.of(workspacesRoot));
        withRestartedProcess(evidenceDirectory().resolve("restart"), client -> {
            assertSameReads(reads, assertReadsAreIsolated(world, client));
            assertContinuity(world, client, "VALID", "BEHIND");
        });
        assertCheckpointsAreIsolated(world);
        assertEquals(folders, assertFoldersAreIsolated(world, evidenceDirectory().resolve("restart")));
        // The restart ended the first queued tasks; these are the ones the backup catches still queued.
        World archived = new World(withQueuedTask(world.fantasy()), withQueuedTask(world.salvage()));
        Files.writeString(evidenceDirectory().resolve("evidence.json"), JSON.writeValueAsString(
                new Evidence(archived, background, reads, folders)));
    }

    /**
     * Starting on the restored database rebuilds the folders and retires the restored generation's work;
     * every read, inventory, memory record and limit of both scopes must be exactly what was archived.
     */
    @Test
    void restoredInstallationKeepsEveryScope() throws Exception {
        var evidence = JSON.readValue(Files.readString(evidenceDirectory().resolve("evidence.json")), Evidence.class);
        World world = evidence.world();
        var reads = new TreeMap<>(assertReadsAreIsolated(world, client(serverPort)));
        var archived = new TreeMap<>(evidence.reads());
        for (Scope scope : world.scopes()) {
            String resume = scope.directory() + "/resume";
            assertNarrationRetired(archived.remove(resume), reads.remove(resume));
        }
        assertSameReads(archived, reads);
        assertContinuity(world, client(serverPort), "VALID", "STALE");
        assertCheckpointsAreIsolated(world);
        assertEquals(evidence.folders(), assertFoldersAreIsolated(world, Path.of(workspacesRoot)),
                "the empty root is rebuilt byte for byte, under the same identities");
        assertMemoryIsIsolated(world);
        var summaries = summaries();
        for (Scope scope : world.scopes()) {
            assertEquals(1L, jdbc.queryForObject("select recovery_generation from campaign where id = ?", Long.class, scope.campaign()));
            assertEquals(MemorySummaries.Freshness.Status.STALE, summaries.freshness(scope.summary()).status(),
                    "a restored summary is kept as a record and never used again");
            assertEquals(List.of("CANCELLED", "GENERATION_RETIRED"), jdbc.queryForObject(
                    "select status, failure_code from memory_summary_job where id = ?", (row, index) ->
                            List.of(row.getString(1), row.getString(2)), scope.pendingJob()));
            assertEquals("ABANDONED", jdbc.queryForObject("select status from inference_task where id = ?", String.class, scope.queuedTask()));
            assertEquals(0, jdbc.queryForObject("select count(*) from inference_task where campaign_id = ? and status "
                    + "in ('QUEUED', 'DISPATCHED')", Integer.class, scope.campaign()));
        }
        assertLimitsSurvive(world, evidence.backgroundUsage());
        // Retired work no longer counts against the bound, which still holds for the new generation.
        var bound = boundedSummaries(2);
        assertEquals(MemorySummaries.Request.Outcome.REQUESTED, bound.request(world.fantasy().campaign(), Audience.PUBLIC, true).outcome());
        assertEquals(MemorySummaries.Request.Outcome.REQUESTED, bound.request(world.salvage().campaign(), Audience.PUBLIC, true).outcome());
        assertEquals(MemorySummaries.Request.Outcome.THROTTLED, bound.request(world.salvage().campaign(), Audience.PRIVILEGED, true).outcome());
    }

    /**
     * A restore retires narration still owed for player commands, by design (tools/database_backup/README.md):
     * each keeps its committed receipt and becomes receipt-only with {@code GENERATION_RETIRED}. Everything else
     * on the resume card must stay byte-identical. Trusted internal operations owe no narration and do not change.
     */
    private static void assertNarrationRetired(String archived, String restored) {
        JsonNode before = JSON.readTree(archived);
        JsonNode after = JSON.readTree(restored);
        int retired = 0;
        for (int index = 0; index < after.get("awaitingNarration").size(); index++) {
            var entry = (ObjectNode) after.get("awaitingNarration").get(index);
            if (!entry.get("commandType").asString().equals("isolation-test")) {
                assertEquals("COMMITTED_NARRATION_PENDING", entry.get("status").asString());
                assertEquals("RECEIPT", entry.get("narration").get("mode").asString());
                assertEquals("GENERATION_RETIRED", entry.get("narration").get("reason").asString());
                retired++;
            }
            var original = (ObjectNode) before.get("awaitingNarration").get(index);
            for (ObjectNode row : List.of(entry, original)) {
                row.remove("status");
                row.remove("narration");
            }
        }
        assertTrue(retired > 0, "the world owes narration, or this comparison proves nothing");
        assertEquals(before, after, "a restore changes nothing else on the resume card");
    }

    /** @return the directory shared by both phases; a missing setting fails instead of guessing a location */
    private static Path evidenceDirectory() {
        String directory = System.getenv("ENGINE_RESTORE_CHECK_DIR");
        assertNotNull(directory, "run this check through tools/container_packaging/test_restore_isolation.py");
        return Path.of(directory);
    }
}
