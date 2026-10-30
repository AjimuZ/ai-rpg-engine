package org.rpgengine.commands;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA-33 at MVP scope, without the backup tool: two genres with equal titles, NPC and item names keep
 * separate commands, reads, memory, jobs, limits and folders, also across a restart in a separate JVM.
 * {@link AdventureRestoreCheck} extends the same world through a real backup and restore.
 */
class AdventureIsolationTest extends AdventureIsolationFixture {
    private static final Path STORAGE = Path.of("target", "isolation-" + UUID.randomUUID()).toAbsolutePath();
    /**
     * Derived tables a restart legitimately rewrites: scheduler recovery ends the queued tasks, and memory
     * reconciliation captures the first checkpoints. Each is asserted separately below.
     */
    private static final Set<String> RECONCILED_AT_STARTUP = Set.of("inference_task", "campaign_checkpoint");

    /** Enable the optional store only for this disposable test context, never for another suite. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("engine.workspaces.root", () -> STORAGE.resolve("live").toString());
    }

    /** Remove disposable rows, preserving production constraints and triggers. */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.execute("truncate identity_owner_profile cascade");
        jdbc.update("delete from inference_scheduler_state");
    }

    /** A same-name world pair stays separate in every read, and a separate JVM serves exactly the same state. */
    @Test
    void twoGenresStaySeparateAcrossARestartInASeparateJvm() throws Exception {
        World world = seed();
        long background = usage();
        Map<String, String> reads = assertReadsAreIsolated(world, client(serverPort));
        assertContinuity(world, client(serverPort), "NONE", "BEHIND");
        assertMemoryIsIsolated(world);
        Map<String, String> folders = assertFoldersAreIsolated(world, Path.of(workspacesRoot));
        var rows = allRows(RECONCILED_AT_STARTUP);

        Path rebuilt = STORAGE.resolve("restart");
        withRestartedProcess(rebuilt, client -> {
            assertSameReads(reads, assertReadsAreIsolated(world, client));
            assertContinuity(world, client, "VALID", "BEHIND");
        });

        assertEquals(rows, allRows(RECONCILED_AT_STARTUP), "a restart changes no canonical, inventory or memory row");
        assertCheckpointsAreIsolated(world);
        assertEquals(folders, assertFoldersAreIsolated(world, rebuilt), "an empty root is rebuilt byte for byte");
        assertMemoryIsIsolated(world);
        var summaries = summaries();
        for (Scope scope : world.scopes()) {
            assertEquals(1, jdbc.queryForObject("select count(*) from inference_task where id = ? and status = 'ABANDONED'",
                    Integer.class, scope.queuedTask()), "a restart ends queued tasks of a finished process");
            assertEquals("QUEUED", jdbc.queryForObject("select status from memory_summary_job where id = ?", String.class,
                    scope.pendingJob()), "a restart keeps queued summary work of the live generation");
            assertEquals(org.rpgengine.memory.MemorySummaries.Freshness.Status.BEHIND,
                    summaries.freshness(scope.summary()).status());
            assertEquals(org.rpgengine.memory.MemorySummaries.Request.Outcome.THROTTLED, boundedSummaries(2)
                    .request(scope.campaign(), org.rpgengine.inference.session.Audience.PRIVILEGED, true).outcome());
        }
        assertLimitsSurvive(world, background);
    }

    /** The opt-in restore rehearsal's evidence file round-trips, so ordinary runs catch a broken format early. */
    @Test
    void restoreEvidenceSurvivesItsFileFormat() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var scope = new Scope(FANTASY.directory(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var evidence = new AdventureRestoreCheck.Evidence(new World(scope, scope), 7, Map.of("library", "{}"), Map.of("file", "hash"));
        assertEquals(evidence, json.readValue(json.writeValueAsString(evidence), AdventureRestoreCheck.Evidence.class));
    }

    /** The mixing assertion rejects foreign identities and foreign texts, so a passing suite is not vacuous. */
    @Test
    void mixingAssertionRejectsForeignIdentitiesAndTexts() {
        UUID identity = UUID.randomUUID();
        var fantasy = new Scope(FANTASY.directory(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, null, null, null);
        var salvage = new Scope(SALVAGE.directory(), identity, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, null, null, null);
        assertBelongsTo(fantasy, salvage, "Lantern and Warden are shared names " + FANTASY.summary());
        for (String foreign : List.of(identity.toString(), SALVAGE.secret(), SALVAGE.lantern(), SALVAGE.role())) {
            assertThrows(AssertionError.class, () -> assertBelongsTo(fantasy, salvage, "before " + foreign + " after"));
        }
    }
}
