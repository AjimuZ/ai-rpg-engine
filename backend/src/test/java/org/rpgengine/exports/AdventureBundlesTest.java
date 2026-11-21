package org.rpgengine.exports;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureLibraryService;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.content.ClosureDiagnosis;
import org.rpgengine.content.PackException;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.exports.AdventureBundles.Coordinates;
import org.rpgengine.exports.AdventureBundles.PackSummary;
import org.rpgengine.exports.AdventureBundles.Standing;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of P16.2-B (QA-41). A sender seals the fantasy fixture and a cyberpunk root that pins
 * it, both with Italian overlays, and exports the root. The recipient side runs on a truncated database, so the
 * round trip lands on a clean installation; hostile and inconsistent archives, conflicting revisions and an existing
 * save then prove that an import writes nothing it should not.
 */
@SpringBootTest
class AdventureBundlesTest {
    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-0000000162b1");
    private static final UUID RECIPIENT = UUID.fromString("00000000-0000-0000-0000-0000000162b2");
    private static final String ACCOUNT = "Sentinel Bundle Sender";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private AdventureBundles bundles;
    @Autowired private PackRegistry registry;
    @Autowired private CampaignCreationService creation;
    @Autowired private JdbcTemplate jdbc;
    private UUID adventure;
    private PackRegistry.Registration leaf;
    private PackRegistry.Registration root;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    AdventureBundlesTest() { }

    /** Seal the fantasy fixture and a cyberpunk root pinning it in the sender's adventure. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        owner(SENDER, ACCOUNT);
        adventure = workspace(SENDER);
        leaf = seal(adventure, fixture("fantasy-exploration"));
        var files = fixture("cyberpunk-neon-alley");
        var manifest = document(files.get("adventure.json"));
        manifest.put("dependencies", List.of(Map.of("id", leaf.pack().packId(), "version", leaf.pack().packVersion(),
                "hash", leaf.pack().contentHash())));
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        root = seal(adventure, files);
    }

    /** Remove disposable rows; the truncation is also how a test reaches a clean installation. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, relationship_change, faction_clock_change, learned_recipe,actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, "
                + "ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * Export, wipe the database, import as another owner: pack IDs, versions, content and file hashes, dependency pins,
     * overlays and the license and credit files arrive unchanged under fresh local identities, and the recipient's own
     * re-export is byte-identical to what the sender sent.
     */
    @Test
    void cleanRecipientRoundTripPreservesIdsHashesDependenciesAndCredits() throws Exception {
        var bundle = bundles.export(SENDER, adventure, root.revisionId());
        assertEquals(2, bundle.packCount());
        assertEquals(coordinates(root), bundle.root());
        assertEquals(sha256(bundle.archive()), bundle.sha256());
        assertArrayEquals(bundle.archive(), bundles.export(SENDER, adventure, root.revisionId()).archive());
        Map<String, Map<String, String>> sent = Map.of(leaf.pack().packId(), encoded(registry.files(leaf.revisionId())
                .orElseThrow()), root.pack().packId(), encoded(registry.files(root.revisionId()).orElseThrow()));
        var entries = unzip(bundle.archive());
        assertEquals(AdventureBundles.MANIFEST, entries.keySet().iterator().next());
        assertEquals(1 + sent.get(leaf.pack().packId()).size() + sent.get(root.pack().packId()).size(), entries.size());
        var text = new StringBuilder();
        entries.values().forEach(bytes -> text.append(new String(bytes, StandardCharsets.UTF_8)));
        for (String local : List.of(SENDER.toString(), adventure.toString(), leaf.revisionId().toString(),
                root.revisionId().toString(), ACCOUNT, "admitted")) {
            assertFalse(text.toString().contains(local), local);
        }

        clear();
        owner(RECIPIENT, "Recipient");
        var preview = bundles.preview(RECIPIENT, null, bundle.archive());
        assertEquals(bundle.sha256(), preview.sha256());
        assertEquals(List.of(coordinates(leaf), coordinates(root)), preview.packs().stream().map(PackSummary::pack)
                .toList());
        assertEquals(List.of(coordinates(leaf)), preview.root().dependencies());
        assertEquals(List.of(), preview.packs().getFirst().dependencies());
        assertEquals(List.of("en", "it"), preview.packs().getFirst().overlays());
        assertEquals(List.of("it"), preview.root().overlays());
        for (PackSummary pack : preview.packs()) {
            assertEquals("en", pack.sourceLocale());
            assertEquals("MIT", pack.rights().license());
            assertEquals(List.of("Project contributors"), pack.rights().authors());
            assertEquals(List.of("LICENSE.md"), pack.rights().licenseFiles());
            assertEquals(List.of("CREDITS.md"), pack.rights().creditFiles());
            assertTrue(pack.rulesetMatches());
            assertEquals(Standing.NEW, pack.standing());
        }
        assertEquals("Neon Alley", preview.root().title());
        assertFalse(preview.conflicted());
        assertEquals(0, count("adventure_workspace"));

        var imported = bundles.importBundle(RECIPIENT, null, bundle.archive());
        assertTrue(imported.adventureCreated());
        assertEquals(List.of("Neon Alley", "city", "en", RECIPIENT.toString()), jdbc.queryForObject("select title, "
                + "genre, source_locale, owner_id::text from adventure_workspace where id = ?", (row, n) -> List.of(
                row.getString(1), row.getString(2), row.getString(3), row.getString(4)), imported.adventureId()));
        List<UUID> senderRevisions = List.of(leaf.revisionId(), root.revisionId());
        for (int index = 0; index < 2; index++) {
            var installed = imported.packs().get(index);
            assertTrue(installed.created());
            assertFalse(senderRevisions.contains(installed.revisionId()));
            assertEquals(preview.packs().get(index).pack(), installed.pack());
            assertEquals(List.of(installed.pack().id(), installed.pack().version(), installed.pack().hash()),
                    jdbc.queryForObject("select pack_id, pack_version, content_hash from content_revision where id = ? "
                            + "and adventure_id = ?", (row, n) -> List.of(row.getString(1), row.getString(2),
                            row.getString(3)), installed.revisionId(), imported.adventureId()));
            assertEquals(sent.get(installed.pack().id()), encoded(registry.files(installed.revisionId()).orElseThrow()));
        }
        UUID importedRoot = imported.packs().getLast().revisionId();
        assertEquals(ClosureDiagnosis.Status.AVAILABLE, registry.diagnose(imported.adventureId(), importedRoot).status());
        assertArrayEquals(bundle.archive(), bundles.export(RECIPIENT, imported.adventureId(), importedRoot).archive());
    }

    /** Hostile and inconsistent archives fail with a fixed code, and none of them writes a workspace or revision. */
    @Test
    void hostileOrInconsistentBundlesAreRejectedBeforeAnyWrite() throws Exception {
        var good = unzip(bundles.export(SENDER, adventure, root.revisionId()).archive());
        owner(RECIPIENT, "Recipient");
        int workspaces = count("adventure_workspace");
        int revisions = count("content_revision");
        int files = count("content_revision_file");
        Map<String, byte[]> cases = new LinkedHashMap<>();
        cases.put("PATH", zip(with(good, "../escape.json", "{}".getBytes(StandardCharsets.UTF_8))));
        cases.put("LIMIT", zip(with(good, "pack-1/zeros.bin", new byte[16 * 1024 * 1024])));
        cases.put("ARCHIVE", "not a zip archive".getBytes(StandardCharsets.UTF_8));
        cases.put("BUNDLE", zip(without(good, AdventureBundles.MANIFEST)));
        cases.put("BUNDLE-format", zip(edited(good, AdventureBundles.MANIFEST,
                text -> text.replace(AdventureBundles.FORMAT, "engine.other-bundle"))));
        cases.put("INTEGRITY-tampered", zip(edited(good, "pack-2/lore.md", text -> text + "\nAn edited line.\n")));
        cases.put("INTEGRITY-manifest", zip(edited(good, AdventureBundles.MANIFEST,
                text -> text.replace("\"Neon Alley\"", "\"Renamed Alley\""))));
        cases.put("INTEGRITY-outside", zip(with(good, "notes.md", "# Notes\n".getBytes(StandardCharsets.UTF_8))));
        cases.put("FILE_REFERENCE", zip(with(good, "pack-1/extra.md", "# Extra\n".getBytes(StandardCharsets.UTF_8))));
        cases.put("DEPENDENCY_MISSING", zip(rootOnly(good)));
        for (var hostile : cases.entrySet()) {
            String code = hostile.getKey().replaceFirst("-.*", "");
            var preview = assertThrows(PackException.class,
                    () -> bundles.preview(RECIPIENT, null, hostile.getValue()), hostile.getKey());
            assertEquals(code, preview.code(), hostile.getKey());
            var refused = assertThrows(PackException.class,
                    () -> bundles.importBundle(RECIPIENT, null, hostile.getValue()), hostile.getKey());
            assertEquals(code, refused.code(), hostile.getKey());
            assertEquals(workspaces, count("adventure_workspace"), hostile.getKey());
            assertEquals(revisions, count("content_revision"), hostile.getKey());
            assertEquals(files, count("content_revision_file"), hostile.getKey());
        }
    }

    /**
     * A recipient with a save and a different revision of the same pack version: the preview names the conflict, the
     * import into that adventure is refused without a write, an import into a new adventure leaves the save untouched,
     * and repeating an import into the new adventure reuses its revisions.
     */
    @Test
    void recipientSavesAndConflictingRevisionsAreNeverOverwritten() throws Exception {
        byte[] archive = bundles.export(SENDER, adventure, root.revisionId()).archive();
        owner(RECIPIENT, "Recipient");
        UUID destination = workspace(RECIPIENT);
        var files = fixture("fantasy-exploration");
        var manifest = document(files.get("adventure.json"));
        manifest.put("summary", "The recipient's own edit of the same pack version.");
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        var own = seal(destination, files);
        assertEquals(leaf.pack().packId(), own.pack().packId());
        assertEquals(leaf.pack().packVersion(), own.pack().packVersion());
        assertNotEquals(leaf.pack().contentHash(), own.pack().contentHash());
        var save = creation.create(RECIPIENT, destination, new CampaignCreationService.Request(UUID.randomUUID(),
                own.revisionId(), "Recipient save", SupportedLocale.EN, new CharacterChoices("Player", "surveyor",
                        Map.of(), Map.of(), null, null)));
        String before = snapshot(destination);

        var preview = bundles.preview(RECIPIENT, destination, archive);
        assertTrue(preview.conflicted());
        assertEquals(List.of(Standing.CONFLICT, Standing.NEW), preview.packs().stream().map(PackSummary::standing)
                .toList());
        int revisions = count("content_revision");
        var conflict = assertThrows(PackException.class, () -> bundles.importBundle(RECIPIENT, destination, archive));
        assertEquals("HASH_CONFLICT", conflict.code());
        assertEquals(revisions, count("content_revision"));
        assertEquals(before, snapshot(destination));

        var fresh = bundles.importBundle(RECIPIENT, null, archive);
        assertNotEquals(destination, fresh.adventureId());
        assertEquals(before, snapshot(destination));
        assertEquals(1, jdbc.queryForObject("select count(*) from campaign where id = ? and adventure_id = ?",
                Integer.class, save.campaignId(), destination));
        int afterFirst = count("content_revision");
        var again = bundles.importBundle(RECIPIENT, fresh.adventureId(), archive);
        assertFalse(again.adventureCreated());
        assertEquals(fresh.packs().stream().map(AdventureBundles.Installed::revisionId).toList(),
                again.packs().stream().map(AdventureBundles.Installed::revisionId).toList());
        assertTrue(again.packs().stream().noneMatch(AdventureBundles.Installed::created));
        assertEquals(afterFirst, count("content_revision"));
        assertTrue(bundles.preview(RECIPIENT, fresh.adventureId(), archive).packs().stream()
                .allMatch(pack -> pack.standing() == Standing.INSTALLED));
        assertEquals(before, snapshot(destination));

        // Another owner's adventure, an unknown owner and an unknown revision share one answer.
        assertThrows(AdventureLibraryService.ScopeUnavailable.class,
                () -> bundles.importBundle(RECIPIENT, adventure, archive));
        assertThrows(AdventureLibraryService.ScopeUnavailable.class,
                () -> bundles.export(RECIPIENT, adventure, root.revisionId()));
        assertThrows(AdventureLibraryService.ScopeUnavailable.class,
                () -> bundles.preview(UUID.randomUUID(), null, archive));
        assertThrows(AdventureLibraryService.ScopeUnavailable.class,
                () -> bundles.export(SENDER, adventure, UUID.randomUUID()));
        assertEquals(afterFirst, count("content_revision"));
    }

    /** Every row of an adventure, its revisions and its campaign tables, as one comparable text. */
    private String snapshot(UUID workspace) {
        var rows = new ArrayList<String>();
        rows.addAll(jdbc.queryForList("select to_jsonb(w)::text from adventure_workspace w where id = ?", String.class,
                workspace));
        rows.addAll(jdbc.queryForList("select to_jsonb(c)::text from content_revision c where adventure_id = ? "
                + "order by id", String.class, workspace));
        rows.addAll(jdbc.queryForList("select f.content_revision_id || f.path || f.sha256 from content_revision_file f "
                + "join content_revision c on c.id = f.content_revision_id where c.adventure_id = ? order by 1",
                String.class, workspace));
        for (String table : List.of("campaign", "actor", "actor_resource", "campaign_event", "character_state",
                "item_instance", "quest_state", "operation_journal")) {
            String key = table.equals("campaign") ? "id" : "campaign_id";
            rows.addAll(jdbc.queryForList("select to_jsonb(t)::text from " + table + " t where t." + key + " in "
                    + "(select id from campaign where adventure_id = ?) order by 1", String.class, workspace));
        }
        return String.join("\n", rows);
    }

    /** Insert an owner profile with a chosen display name. */
    private void owner(UUID owner, String name) {
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, ?, 'en', now(), now())", owner, name);
    }

    /** Insert an empty workspace for an owner. */
    private UUID workspace(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into adventure_workspace (id, owner_id, title, genre, source_locale, created_at, updated_at) "
                + "values (?, ?, 'Workspace', 'test', 'en', now(), now())", id, owner);
        return id;
    }

    /** Load a mutable copy of a test fixture through real directory staging. */
    private Map<String, byte[]> fixture(String name) throws Exception {
        return new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/" + name).toURI())).files());
    }

    /** Seal files through real ZIP staging; uncompressed entries keep ratio limits irrelevant here. */
    private PackRegistry.Registration seal(UUID workspace, Map<String, byte[]> files) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.setLevel(0);
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return registry.register(workspace, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(output.toByteArray())));
    }

    /** Parse a JSON object for a controlled mutation. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> document(byte[] bytes) {
        return JSON.readValue(bytes, LinkedHashMap.class);
    }

    /** Coordinates of a sender registration. */
    private static Coordinates coordinates(PackRegistry.Registration registration) {
        return new Coordinates(registration.pack().packId(), registration.pack().packVersion(),
                registration.pack().contentHash());
    }

    /** Comparable path-to-bytes text. */
    private static Map<String, String> encoded(Map<String, byte[]> files) {
        Map<String, String> encoded = new TreeMap<>();
        files.forEach((path, bytes) -> encoded.put(path, Base64.getEncoder().encodeToString(bytes)));
        return encoded;
    }

    /** Archive entries in stored order. */
    private static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    /** Deflated archive of entries in their order; entry names are written as given. */
    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    /** A copy with one more entry. */
    private static Map<String, byte[]> with(Map<String, byte[]> entries, String name, byte[] bytes) {
        var copy = new LinkedHashMap<>(entries);
        copy.put(name, bytes);
        return copy;
    }

    /** A copy without one entry. */
    private static Map<String, byte[]> without(Map<String, byte[]> entries, String name) {
        var copy = new LinkedHashMap<>(entries);
        copy.remove(name);
        return copy;
    }

    /** A copy with one UTF-8 entry rewritten. */
    private static Map<String, byte[]> edited(Map<String, byte[]> entries, String name, UnaryOperator<String> edit) {
        var copy = new LinkedHashMap<>(entries);
        copy.put(name, edit.apply(new String(entries.get(name), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        return copy;
    }

    /** The root pack alone, moved to the first folder, with a manifest that lists only it: its dependency is gone. */
    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> rootOnly(Map<String, byte[]> entries) {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        var manifest = document(entries.get(AdventureBundles.MANIFEST));
        var packs = new ArrayList<>((List<Object>) manifest.get("packs"));
        var kept = new LinkedHashMap<>((Map<String, Object>) packs.getLast());
        kept.put("folder", "pack-1");
        manifest.put("packs", List.of(kept));
        copy.put(AdventureBundles.MANIFEST, JSON.writeValueAsBytes(manifest));
        entries.forEach((name, bytes) -> {
            if (name.startsWith("pack-2/")) {
                copy.put("pack-1/" + name.substring("pack-2/".length()), bytes);
            }
        });
        return copy;
    }

    /** Count rows of a fixed table name. */
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
