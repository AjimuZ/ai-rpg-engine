package org.rpgengine.content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercise immutable pack registration against real PostgreSQL (P04.4).
 *
 * <p>Every case stages input through {@link PackStaging}, so the registry only ever sees protected
 * inventories. The corpus proves that a registration is atomic, idempotent for identical bytes and
 * conflicting for changed bytes under one version, that blocked imports write nothing, and that the
 * V7 triggers and checks keep stored files and reports sealed even against direct SQL.
 */
@SpringBootTest
class PackRegistryPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final PackStaging STAGING = new PackStaging(PackStaging.Limits.standard());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private PackRegistry registry;
    private UUID adventure;

    /** Construct the injected PostgreSQL fixture; missing infrastructure fails startup. */
    PackRegistryPersistenceTest() {
    }

    /** Start every case from one committed owner and adventure with no registered packs. */
    @BeforeEach
    void prepare() {
        clear();
        adventure = new TransactionTemplate(manager).execute(status -> {
            var owner = OwnerProfile.create("Owner", NOW);
            entities.persist(owner);
            var workspace = AdventureWorkspace.create(owner.getId(), "Harbor", "harbor", SupportedLocale.EN, NOW);
            entities.persist(workspace);
            return workspace.getId();
        });
    }

    /**
     * Remove disposable fixture rows. TRUNCATE fires no row triggers, so the immutability triggers do
     * not block cleanup; triggers disabled by a test are re-enabled here even when it failed.
     */
    @AfterEach
    void clear() {
        jdbc.execute("alter table content_revision_file enable trigger content_revision_file_immutable");
        jdbc.execute("alter table content_revision_report enable trigger content_revision_report_immutable");
        jdbc.execute("drop trigger if exists p044_fail on content_revision_report");
        jdbc.execute("drop function if exists p044_fail()");
        jdbc.execute("truncate ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, "
                + "ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, "
                + "operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, "
                + "reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, content_revision, "
                + "adventure_workspace, identity_owner_profile");
    }

    /** An archive registers once with every file and a report; identical directory bytes are idempotent. */
    @Test
    void registersArchiveAndReusesIdenticalBytes(@TempDir Path directory) throws IOException {
        var first = registry.register(adventure, STAGING.archive(new ByteArrayInputStream(zip(harbor()))));
        assertTrue(first.created());
        assertEquals("example:harbor", first.pack().packId());
        assertEquals(1, count("content_revision"));
        assertEquals(11, count("content_revision_file"));
        assertEquals(1, count("content_revision_report"));
        // The database recomputed every digest and size from the stored bytes.
        assertEquals(11, (int) jdbc.queryForObject("select count(*) from content_revision_file "
                + "where sha256 = encode(sha256(content), 'hex') and byte_size = octet_length(content)", Integer.class));
        assertEquals("manifest", jdbc.queryForObject("select category from content_revision_file where path = 'adventure.json'",
                String.class));
        String report = registry.report(first.revisionId()).orElseThrow();
        assertEquals(first.pack().contentHash(), JSON.readTree(report).path("pack").path("contentHash").asString());
        assertFalse(report.contains(directory.toString()), "reports carry no host paths");

        // The same bytes from a directory produce the same hash and reuse the stored revision.
        for (var file : harbor().entrySet()) {
            Files.write(directory.resolve(file.getKey()), file.getValue());
        }
        var again = registry.register(adventure, STAGING.directory(directory));
        assertFalse(again.created());
        assertEquals(first.revisionId(), again.revisionId());
        assertEquals(1, count("content_revision"));
        assertEquals(11, count("content_revision_file"));
    }

    /** Preview stores nothing and produces exactly the report that registration later stores. */
    @Test
    void previewIsDeterministicAndWritesNothing() {
        var staged = staged(harbor());
        var preview = registry.preview(adventure, staged);
        assertEquals(PackStaging.Source.ARCHIVE, preview.source());
        assertEquals("skirmish", preview.ruleset().rulesetId());
        assertTrue(preview.exports().contains("\"market\":\"location\""), preview.exports());
        assertEquals(0, count("content_revision"));
        assertEquals(preview.report(), registry.preview(adventure, staged).report());
        var registration = registry.register(adventure, staged);
        assertEquals(JSON.readTree(preview.report()), JSON.readTree(registry.report(registration.revisionId()).orElseThrow()));
    }

    /** One pack ID and version must keep one hash; blocked registrations leave no partial rows. */
    @Test
    void rejectsChangedBytesUnderOneVersion() {
        registry.register(adventure, staged(harbor()));
        var changed = harbor();
        changed.put("world.json", text(changed.get("world.json")).replace("Docks.", "Quays.").getBytes(StandardCharsets.UTF_8));
        var error = assertThrows(PackException.class, () -> registry.register(adventure, staged(changed)));
        assertEquals("HASH_CONFLICT", error.code());
        assertThrows(PackException.class, () -> registry.preview(adventure, staged(changed)));
        assertEquals(1, count("content_revision"));
        assertEquals(11, count("content_revision_file"));
        // A new version of the changed bytes is a separate, allowed revision.
        changed.put("adventure.json", text(changed.get("adventure.json")).replace("\"1.0.0\"", "\"1.1.0\"")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(registry.register(adventure, staged(changed)).created());
        assertEquals(2, count("content_revision_report"));
    }

    /** Assets are type- and size-checked before any row is written; accepted media is reported. */
    @Test
    void checksAssetsBeforeWriting() {
        var valid = withAsset(png(64, 32));
        var registration = registry.register(adventure, staged(valid));
        String report = registry.report(registration.revisionId()).orElseThrow();
        var asset = JSON.readTree(report).path("files").valueStream()
                .filter(file -> file.path("path").asString().equals("assets/map.png")).findFirst().orElseThrow();
        assertEquals("image/png", asset.path("mediaType").asString());
        assertEquals(64, asset.path("width").asInt());
        assertEquals(32, asset.path("height").asInt());
        assertEquals("image/png", jdbc.queryForObject("select media_type from content_revision_file "
                + "where path = 'assets/map.png'", String.class));

        clear();
        prepare();
        var disguised = withAsset("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        assertEquals("MEDIA_TYPE", assertThrows(PackException.class, () -> registry.register(adventure, staged(disguised))).code());
        assertEquals("MEDIA_LIMIT", assertThrows(PackException.class,
                () -> registry.register(adventure, staged(withAsset(png(9000, 10))))).code());
        assertEquals(0, count("content_revision"));
        assertEquals(0, count("content_revision_file"));
    }

    /** Dependencies resolve only against sealed packs of the same adventure, never another adventure's. */
    @Test
    void resolvesDependenciesWithinTheAdventure() {
        var harbor = registry.register(adventure, staged(harbor()));
        var quay = quay(harbor.pack().contentHash());
        UUID other = new TransactionTemplate(manager).execute(status -> {
            var owner = OwnerProfile.create("Other", NOW);
            entities.persist(owner);
            var workspace = AdventureWorkspace.create(owner.getId(), "Other", "other", SupportedLocale.EN, NOW);
            entities.persist(workspace);
            return workspace.getId();
        });
        assertThrows(PackException.class, () -> registry.register(other, staged(quay)));
        var registered = registry.register(adventure, staged(quay));
        assertTrue(registered.created());
        assertEquals("example:quay", registered.pack().packId());
        // A dependency hash that disagrees with the installed pack is a conflict, not a fallback.
        assertEquals("HASH_CONFLICT", assertThrows(PackException.class,
                () -> registry.preview(adventure, staged(quay("b".repeat(64))))).code());
    }

    /** Stored files round-trip exactly; edits that bypass the triggers are detected on reload. */
    @Test
    void reloadsFilesAndDetectsTampering() {
        var source = harbor();
        var registration = registry.register(adventure, staged(source));
        var files = registry.files(registration.revisionId()).orElseThrow();
        assertEquals(source.keySet(), files.keySet());
        source.forEach((path, bytes) -> assertArrayEquals(bytes, files.get(path), path));
        assertEquals(registration.pack().contentHash(), PackHashes.calculate(files).contentHash());
        assertTrue(registry.files(UUID.randomUUID()).isEmpty());
        assertTrue(registry.report(UUID.randomUUID()).isEmpty());

        // Simulate a hand-edited database: the size and digest checks still hold, but the bytes no
        // longer reproduce the recorded content hash, so reload must refuse them.
        jdbc.execute("alter table content_revision_file disable trigger content_revision_file_immutable");
        jdbc.update("update content_revision_file set content = convert_to('{}', 'UTF8'), byte_size = 2, "
                + "sha256 = encode(sha256(convert_to('{}', 'UTF8')), 'hex') where path = 'world.json'");
        jdbc.execute("alter table content_revision_file enable trigger content_revision_file_immutable");
        assertThrows(IllegalStateException.class, () -> registry.files(registration.revisionId()));
    }

    /**
     * P09.5: a pinned closure is diagnosed without throwing and without writing. A loadable closure is
     * available; a legacy row without a sealed inventory is unsealed; an engine build with another
     * ruleset reports the revision whose ruleset is unavailable; a lost dependency is listed by its
     * pinned coordinates while loading keeps its argument error; changed bytes are corrupt and keep
     * their state error; and a revision of another adventure is missing.
     */
    @Test
    void diagnosesPinnedClosuresWithoutThrowing() {
        var harbor = registry.register(adventure, staged(harbor()));
        var quay = registry.register(adventure, staged(quay(harbor.pack().contentHash())));
        assertEquals(ClosureDiagnosis.AVAILABLE, registry.diagnose(adventure, quay.revisionId()));
        assertEquals(new ClosureDiagnosis(ClosureDiagnosis.Status.MISSING, List.of(new ClosureDiagnosis.Issue(
                ClosureDiagnosis.Code.REVISION_MISSING, null, null))), registry.diagnose(UUID.randomUUID(), quay.revisionId()));

        UUID legacy = UUID.randomUUID();
        jdbc.update("insert into content_revision (id, adventure_id, pack_id, pack_version, content_hash, schema_version, "
                + "ruleset_id, ruleset_version, ruleset_hash, source_locale, admitted_at) select ?, adventure_id, pack_id, "
                + "'9.9.9', content_hash, schema_version, ruleset_id, ruleset_version, ruleset_hash, source_locale, "
                + "admitted_at from content_revision where id = ?", legacy, harbor.revisionId());
        assertEquals(ClosureDiagnosis.UNSEALED, registry.diagnose(adventure, legacy));
        assertFalse(ClosureDiagnosis.UNSEALED.pausesMutation(), "a legacy revision keeps its earlier behavior");

        // An upgraded engine with another ruleset version cannot run the sealed root.
        var upgraded = new PackRegistry(jdbc, manager, new EngineContract("0.1.0", "skirmish", "1.1.0",
                Set.of("standard"), Set.of()));
        assertEquals(List.of(new ClosureDiagnosis.Issue(ClosureDiagnosis.Code.RULESET_UNAVAILABLE, "example:quay",
                "1.0.0")), upgraded.diagnose(adventure, quay.revisionId()).issues());

        jdbc.execute("alter table content_revision_report disable trigger content_revision_report_immutable");
        jdbc.update("delete from content_revision_report where content_revision_id = ?", harbor.revisionId());
        jdbc.execute("alter table content_revision_report enable trigger content_revision_report_immutable");
        var lost = registry.diagnose(adventure, quay.revisionId());
        assertTrue(lost.pausesMutation());
        assertEquals(List.of(new ClosureDiagnosis.Issue(ClosureDiagnosis.Code.DEPENDENCY_MISSING, "example:harbor",
                "1.0.0")), lost.issues());
        assertEquals("pinned sealed dependency is unavailable in this adventure", assertThrows(
                IllegalArgumentException.class, () -> new TransactionTemplate(manager).execute(status ->
                        registry.loadForCampaign(adventure, quay.revisionId()))).getMessage());

        jdbc.execute("alter table content_revision_file disable trigger content_revision_file_immutable");
        jdbc.update("update content_revision_file set content = content || convert_to(' ', 'UTF8'), byte_size = "
                + "byte_size + 1, sha256 = encode(sha256(content || convert_to(' ', 'UTF8')), 'hex') where "
                + "content_revision_id = ? and path = 'world.json'", quay.revisionId());
        jdbc.execute("alter table content_revision_file enable trigger content_revision_file_immutable");
        String stored = snapshot();
        assertEquals(List.of(new ClosureDiagnosis.Issue(ClosureDiagnosis.Code.CONTENT_CORRUPT, "example:quay", "1.0.0")),
                registry.diagnose(adventure, quay.revisionId()).issues());
        assertEquals(stored, snapshot(), "diagnosis never writes");
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(status ->
                registry.loadForCampaign(adventure, quay.revisionId())));
    }

    /** @return every content row as text, to show which writes happened */
    private String snapshot() {
        return jdbc.queryForObject("select (select count(*) from content_revision_report) || '|' || (select "
                + "string_agg(sha256, ',' order by content_revision_id, path) from content_revision_file)", String.class);
    }

    /** Direct SQL cannot change, delete, extend or misdescribe a sealed revision. */
    @Test
    void databaseKeepsRevisionsSealed() {
        UUID id = registry.register(adventure, staged(harbor())).revisionId();
        assertThrows(DataAccessException.class, () -> jdbc.update("update content_revision_file set category = 'rules' "
                + "where content_revision_id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from content_revision_file where content_revision_id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("update content_revision_report set file_count = 1 "
                + "where content_revision_id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from content_revision_report"));
        assertThrows(DataAccessException.class, () -> insertFile(id, "late.json", "{}", null));

        // An unsealed revision still rejects lying digests, unportable paths and mismatched reports.
        UUID open = UUID.randomUUID();
        jdbc.update("insert into content_revision (id, adventure_id, pack_id, pack_version, content_hash, schema_version, "
                + "ruleset_id, ruleset_version, ruleset_hash, source_locale, admitted_at) select ?, adventure_id, pack_id, "
                + "'9.9.9', content_hash, schema_version, ruleset_id, ruleset_version, ruleset_hash, source_locale, "
                + "admitted_at from content_revision where id = ?", open, id);
        assertThrows(DataAccessException.class, () -> insertFile(open, "a.json", "{}", "0".repeat(64)));
        assertThrows(DataAccessException.class, () -> insertFile(open, "../a.json", "{}", null));
        assertThrows(DataAccessException.class, () -> insertFile(open, "C:/a.json", "{}", null));
        insertFile(open, "a.json", "{}", null);
        var snapshotTransaction = new TransactionTemplate(manager);
        snapshotTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(DataAccessException.class, () -> snapshotTransaction.executeWithoutResult(
                status -> insertFile(open, "snapshot.json", "{}", null)));
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into content_revision_report (content_revision_id, "
                + "report_version, file_count, total_bytes, report, exports) values (?, 1, 2, 2, '{}', '{}')", open));
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into content_revision_report (content_revision_id, "
                + "report_version, file_count, total_bytes, report, exports) values (?, 1, 1, 3, '{}', '{}')", open));
        jdbc.update("insert into content_revision_report (content_revision_id, report_version, file_count, total_bytes, "
                + "report, exports) values (?, 1, 1, 2, '{}', '{}')", open);
        assertThrows(DataAccessException.class, () -> insertFile(open, "b.json", "{}", null));
    }

    /** Unknown adventures and ambient transactions are refused before any write. */
    @Test
    void refusesUnknownAdventuresAndAmbientTransactions() {
        var staged = staged(harbor());
        assertThrows(IllegalArgumentException.class, () -> registry.register(UUID.randomUUID(), staged));
        assertThrows(IllegalArgumentException.class, () -> registry.preview(UUID.randomUUID(), staged));
        new TransactionTemplate(manager).executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> registry.register(adventure, staged)));
        assertEquals(0, count("content_revision"));
    }

    /** A matching hash on a legacy metadata-only row cannot masquerade as an admitted revision. */
    @Test
    void refusesUnsealedLegacyRetry() {
        var input = staged(harbor());
        var inspection = registry.preview(adventure, input);
        var pack = inspection.pack();
        var rules = inspection.ruleset();
        jdbc.update("insert into content_revision (id, adventure_id, pack_id, pack_version, content_hash, "
                + "schema_version, ruleset_id, ruleset_version, ruleset_hash, source_locale, admitted_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())", UUID.randomUUID(), adventure,
                pack.packId(), pack.packVersion(), pack.contentHash(), pack.schemaVersion(), rules.rulesetId(),
                rules.rulesetVersion(), rules.rulesetHash(), pack.sourceLocale().tag());
        assertEquals("UNSEALED_REVISION", assertThrows(PackException.class,
                () -> registry.register(adventure, input)).code());
        assertEquals(0, count("content_revision_report"));
        assertEquals(0, count("content_revision_file"));
    }

    /** Competing identical imports produce one sealed revision, while a failed seal rolls back all files. */
    @Test
    void serializesRegistrationAndRollsBackFailedSeal() throws Exception {
        var input = staged(harbor());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return registry.register(adventure, input); });
            var second = executor.submit(() -> { start.await(); return registry.register(adventure, input); });
            start.countDown();
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertEquals(a.revisionId(), b.revisionId());
            assertNotEquals(a.created(), b.created());
        }
        assertEquals(1, count("content_revision_report"));
        clear();
        prepare();
        jdbc.execute("create function p044_fail() returns trigger language plpgsql as $$ begin "
                + "raise exception 'injected seal failure'; end $$");
        jdbc.execute("create trigger p044_fail before insert on content_revision_report "
                + "for each row execute function p044_fail()");
        assertThrows(DataAccessException.class, () -> registry.register(adventure, input));
        assertEquals(0, count("content_revision"));
        assertEquals(0, count("content_revision_file"));
        assertEquals(0, count("content_revision_report"));
    }

    /** A late SQL writer waits for the uncommitted seal and then fails, rather than extending its snapshot. */
    @Test
    void serializesConcurrentSealAndFileInsert() throws Exception {
        UUID original = registry.register(adventure, staged(harbor())).revisionId();
        UUID open = UUID.randomUUID();
        jdbc.update("insert into content_revision (id, adventure_id, pack_id, pack_version, content_hash, schema_version, "
                + "ruleset_id, ruleset_version, ruleset_hash, source_locale, admitted_at) select ?, adventure_id, pack_id, "
                + "'9.9.9', content_hash, schema_version, ruleset_id, ruleset_version, ruleset_hash, source_locale, "
                + "admitted_at from content_revision where id = ?", open, original);
        insertFile(open, "a.json", "{}", null);
        var sealed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var seal = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.update("insert into content_revision_report (content_revision_id, report_version, file_count, "
                        + "total_bytes, report, exports) values (?, 1, 1, 2, '{}', '{}')", open);
                sealed.countDown();
                await(release);
            }));
            try {
                assertTrue(sealed.await(5, TimeUnit.SECONDS));
                var late = executor.submit(() -> assertThrows(DataAccessException.class,
                        () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                            writerPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                            jdbc.execute("set local lock_timeout = '10s'");
                            insertFile(open, "late.json", "{}", null);
                        })));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean blocked = false;
                while (!blocked && System.nanoTime() < deadline) {
                    blocked = writerPid.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                            "select cardinality(pg_blocking_pids(?)) > 0", Boolean.class, writerPid.get()));
                    if (!blocked) { Thread.sleep(10); }
                }
                assertTrue(blocked, "late file insertion must wait for the seal transaction");
                release.countDown();
                seal.get(5, TimeUnit.SECONDS);
                late.get(5, TimeUnit.SECONDS);
                assertEquals(1, jdbc.queryForObject("select count(*) from content_revision_file "
                        + "where content_revision_id = ?", Integer.class, open));
            } finally {
                release.countDown();
            }
        }
    }

    /** Bound a latch inside a transaction callback; fail the test on timeout or interruption. */
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /** Insert one file row by hand; a null digest means "the correct digest of the content". */
    private void insertFile(UUID revision, String path, String content, String sha256) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String digest = sha256 != null ? sha256 : PackHashes.calculate(Map.of("x", bytes)).fileHashes().get("x");
        jdbc.update("insert into content_revision_file (content_revision_id, path, category, byte_size, sha256, content) "
                + "values (?, ?, 'rules', ?, ?, ?)", revision, path, (long) bytes.length, digest, bytes);
    }

    /** Count all rows of one fixture table. */
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** The valid reference pack of the compiler corpus, without dependencies, as mutable bytes. */
    private static Map<String, byte[]> harbor() {
        return bytes(PackCompilerTest.sources(), "");
    }

    /** A second pack whose keeper stands in the harbor pack's market, pinned to the given hash. */
    private static Map<String, byte[]> quay(String harborHash) {
        var sources = PackCompilerTest.sources();
        sources.put("adventure.json", sources.get("adventure.json").replace("example:harbor", "example:quay"));
        sources.put("npcs.json", sources.get("npcs.json").replace("\"location\":\"harbor\"", "\"location\":\"example:harbor#market\""));
        return bytes(sources, ",\"dependencies\":[{\"id\":\"example:harbor\",\"version\":\"1.0.0\",\"hash\":\"" + harborHash + "\"}]");
    }

    /** The harbor pack plus one listed asset with the given bytes. */
    private static Map<String, byte[]> withAsset(byte[] asset) {
        var sources = PackCompilerTest.sources();
        sources.put("adventure.json", sources.get("adventure.json").replace("\"files\":[",
                "\"files\":[{\"path\":\"assets/map.png\",\"category\":\"asset\"},"));
        var files = bytes(sources, "");
        files.put("assets/map.png", asset);
        return files;
    }

    /** Encode text sources as UTF-8, filling the manifest's dependency placeholder. */
    private static Map<String, byte[]> bytes(Map<String, String> sources, String dependencies) {
        Map<String, byte[]> files = new TreeMap<>();
        sources.forEach((path, text) -> files.put(path,
                text.replace("__DEPENDENCIES__", dependencies).getBytes(StandardCharsets.UTF_8)));
        return files;
    }

    /** Stage files through the archive path, exactly as an uploaded ZIP would be. */
    private static PackStaging.StagedPack staged(Map<String, byte[]> files) {
        try {
            return STAGING.archive(new ByteArrayInputStream(zip(files)));
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Write files into a deflated ZIP below one wrapper directory, which staging strips. */
    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry("harbor-1.0.0/" + file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Minimal PNG signature and IHDR header; the media sniffer reads nothing beyond it. */
    private static byte[] png(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(33);
        buffer.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        buffer.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height);
        return Arrays.copyOf(buffer.array(), 33);
    }

    /** Decode UTF-8 bytes. */
    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
