package org.rpgengine.content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Import standalone genre fixtures through the unchanged engine and real PostgreSQL.
 * Inputs are classpath pack directories and equivalent ZIP bytes; outputs are sealed revisions
 * and recompiled definitions. Missing fixtures, altered bytes or invalid content fail assertions.
 * These checks establish content-driven import, not playable campaigns or optional module runtime.
 */
@SpringBootTest
class NeutralGenreImportTest {
    private static final PackStaging STAGING = new PackStaging(PackStaging.Limits.standard());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private PackRegistry registry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;

    /** Construct an injected import corpus; unavailable PostgreSQL fails context initialization. */
    NeutralGenreImportTest() {
    }

    /** Delete disposable test rows after each case; TRUNCATE preserves immutable-row triggers. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, "
                + "ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, "
                + "operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, "
                + "reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, "
                + "content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * Import both authored directories and ZIPs unchanged, then recompile exact stored bytes.
     * Same local IDs deliberately coexist: their vocabulary, mechanics and assets stay pack-local.
     */
    @Test
    void importsBothGenresWithoutSourceEdits() throws Exception {
        UUID adventure = workspace();
        Map<String, CompiledPack> compiled = new TreeMap<>();
        Map<String, String> hashes = new TreeMap<>();
        for (String name : new String[] {"fantasy-exploration", "science-fiction-salvage"}) {
            Path directory = fixture(name);
            var staged = STAGING.directory(directory);
            Map<String, byte[]> source = staged.files();
            var preview = registry.preview(adventure, staged);
            assertEquals(0, (int) jdbc.queryForObject("select count(*) from content_revision "
                    + "where adventure_id = ? and pack_id = ?", Integer.class, adventure, preview.pack().packId()));
            var registered = registry.register(adventure, staged);
            assertTrue(registered.created());
            assertEquals("sample:" + name, registered.pack().packId());
            assertEquals(JSON.readTree(preview.report()), JSON.readTree(registry.report(registered.revisionId()).orElseThrow()));
            var stored = registry.files(registered.revisionId()).orElseThrow();
            assertEquals(source.keySet(), stored.keySet());
            source.forEach((path, bytes) -> assertArrayEquals(bytes, stored.get(path), path));

            // ZIP packaging never rewrites manifest IDs, references, media or prose.
            var archive = STAGING.archive(new ByteArrayInputStream(zip(source)));
            var repeated = registry.register(adventure, archive);
            assertFalse(repeated.created());
            assertEquals(registered.revisionId(), repeated.revisionId());
            assertEquals(registered.pack().contentHash(), PackHashes.calculate(stored).contentHash());
            assertEquals(registered.pack().contentHash(), PackHashes.calculate(archive.files()).contentHash());

            var pack = new PackCompiler().compile(new PackParser().parse(stored));
            assertEquals(java.util.List.of(), pack.warnings());
            assertEquals(java.util.List.of(), pack.parsed().manifest().values().get("modules"));
            assertTrue(pack.byId().containsKey("survey"));
            assertEquals("quest", pack.byId().get("survey").kind());
            assertEquals("item", pack.byId().get("kit").kind());
            compiled.put(name, pack);
            hashes.put(name, registered.pack().contentHash());
            assertTrue(JSON.readTree(preview.report()).path("files").valueStream()
                    .anyMatch(file -> file.path("path").asString().equals("assets/map.png")
                            && file.path("mediaType").asString().equals("image/png")
                            && file.path("width").asInt() == 48 && file.path("height").asInt() == 32));

            // A second adventure can import the ZIP first, without depending on the first workspace.
            var independent = registry.register(workspace(), archive);
            assertTrue(independent.created());
            assertNotEquals(registered.revisionId(), independent.revisionId());
            assertEquals(registered.pack().contentHash(), independent.pack().contentHash());
        }
        var fantasy = compiled.get("fantasy-exploration");
        var salvage = compiled.get("science-fiction-salvage");
        assertNotEquals(hashes.get("fantasy-exploration"), hashes.get("science-fiction-salvage"));
        assertEquals("Trailcraft", fantasy.byId().get("fieldwork").data().get("name"));
        assertEquals("Systems", salvage.byId().get("fieldwork").data().get("name"));
        assertTrue(fantasy.byId().containsKey("grove"));
        assertFalse(salvage.byId().containsKey("grove"));
        assertTrue(salvage.byId().containsKey("reactor"));
        assertNotEquals(fantasy.byId().get("rules.resources").data(), salvage.byId().get("rules.resources").data());
        assertNotEquals(fantasy.byId().get("traveler").data(), salvage.byId().get("traveler").data());
        assertNotEquals(fantasy.parsed().fileHashes().get("assets/map.png"),
                salvage.parsed().fileHashes().get("assets/map.png"));
        assertEquals(4, (int) jdbc.queryForObject("select count(*) from content_revision", Integer.class));
        assertEquals(4, (int) jdbc.queryForObject("select count(*) from content_revision_report", Integer.class));
    }

    /** Locale files are sealed and reported unchanged; editing only Italian text requires a new revision. */
    @Test
    void sealsLocaleCoverageAndRejectsChangedTranslationVersion() throws Exception {
        UUID adventure = workspace();
        for (String name : new String[] {"fantasy-exploration", "science-fiction-salvage"}) {
            var staged = STAGING.directory(fixture(name));
            var result = registry.register(adventure, staged);
            var report = JSON.readTree(registry.report(result.revisionId()).orElseThrow());
            var compiled = new PackCompiler().compile(new PackParser().parse(staged.files()));
            assertEquals(2, report.path("reportVersion").asInt());
            assertEquals(2, (int) jdbc.queryForObject("select report_version from content_revision_report "
                    + "where content_revision_id = ?", Integer.class, result.revisionId()));
            assertEquals(JSON.readTree(JSON.writeValueAsString(compiled.localization().report())), report.path("localization"));
            for (var coverage : report.path("localization").path("coverage")) {
                assertEquals(coverage.path("total"), coverage.path("overlaid"));
            }
            Map<String, byte[]> changed = new TreeMap<>(staged.files());
            String italian = new String(changed.get("locales/it.json"), StandardCharsets.UTF_8);
            changed.put("locales/it.json", italian.replace("Raggiungi il sito da esaminare.",
                    "Raggiungi il luogo della ricognizione.").getBytes(StandardCharsets.UTF_8));
            assertNotEquals(PackHashes.calculate(staged.files()).contentHash(), PackHashes.calculate(changed).contentHash());
            var archive = STAGING.archive(new ByteArrayInputStream(zip(changed)));
            var error = assertThrows(PackException.class, () -> registry.register(adventure, archive));
            assertEquals("HASH_CONFLICT", error.code());
            assertArrayEquals(staged.files().get("locales/it.json"),
                    registry.files(result.revisionId()).orElseThrow().get("locales/it.json"));
        }
        assertEquals(2, (int) jdbc.queryForObject("select count(*) from content_revision", Integer.class));
    }

    /** A localized visibility patch in either fixture fails atomically with the overlay's field coordinates. */
    @Test
    void rejectsLocalizedVisibilityChangesAtomically() throws Exception {
        UUID adventure = workspace();
        for (String name : new String[] {"fantasy-exploration", "science-fiction-salvage"}) {
            Map<String, byte[]> files = new TreeMap<>(STAGING.directory(fixture(name)).files());
            String overlay = new String(files.get("locales/it.json"), StandardCharsets.UTF_8);
            files.put("locales/it.json", overlay.replace("\"field\": \"/name\"", "\"field\": \"/visibility\"")
                    .getBytes(StandardCharsets.UTF_8));
            var archive = STAGING.archive(new ByteArrayInputStream(zip(files)));
            var error = assertThrows(PackException.class, () -> registry.register(adventure, archive));
            assertEquals("TEXT_TARGET", error.code());
            assertEquals("locales/it.json", error.location().file());
            assertTrue(error.location().pointer().endsWith("/field"));
        }
        assertEquals(0, (int) jdbc.queryForObject("select count(*) from content_revision", Integer.class));
        assertEquals(0, (int) jdbc.queryForObject("select count(*) from content_revision_report", Integer.class));
        assertEquals(0, (int) jdbc.queryForObject("select count(*) from content_revision_file", Integer.class));
    }

    /** A broken reference in either real fixture fails at its source field without admitting a row. */
    @Test
    void rejectsBrokenFixtureReferencesAtomically() throws Exception {
        UUID adventure = workspace();
        for (String name : new String[] {"fantasy-exploration", "science-fiction-salvage"}) {
            Map<String, byte[]> files = new TreeMap<>(STAGING.directory(fixture(name)).files());
            String creation = new String(files.get("creation.json"), StandardCharsets.UTF_8);
            assertTrue(creation.contains("\"kit\""), "the mutation must change a real reference");
            files.put("creation.json", creation.replace("\"kit\"", "\"missing-kit\"").getBytes(StandardCharsets.UTF_8));
            var staged = STAGING.archive(new ByteArrayInputStream(zip(files)));
            var error = assertThrows(PackException.class, () -> registry.register(adventure, staged));
            assertEquals("REFERENCE_UNKNOWN", error.code());
            assertEquals("creation.json", error.location().file());
            assertTrue(error.location().pointer().contains("startingItems"));
        }
        assertEquals(0, (int) jdbc.queryForObject("select count(*) from content_revision", Integer.class));
        assertEquals(0, (int) jdbc.queryForObject("select count(*) from content_revision_file", Integer.class));
        assertEquals(0, (int) jdbc.queryForObject("select count(*) from content_revision_report", Integer.class));
    }

    /** Persist and return a fresh owner-scoped workspace; database failures propagate to the test. */
    private UUID workspace() {
        return new TransactionTemplate(manager).execute(status -> {
            Instant now = Instant.parse("2026-10-24T12:00:00Z");
            var owner = OwnerProfile.create("Fixture owner", now);
            entities.persist(owner);
            var workspace = AdventureWorkspace.create(owner.getId(), "Import check", "neutral", SupportedLocale.EN, now);
            entities.persist(workspace);
            return workspace.getId();
        });
    }

    /** Resolve a bundled directory by name; absence fails explicitly rather than skipping coverage. */
    private Path fixture(String name) throws URISyntaxException {
        var resource = getClass().getResource("/packs/" + name + "/");
        assertNotNull(resource, "missing standalone fixture: " + name);
        return Path.of(resource.toURI());
    }

    /** Package unchanged inventory bytes as a portable ZIP; I/O failures propagate to the test. */
    private byte[] zip(Map<String, byte[]> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
