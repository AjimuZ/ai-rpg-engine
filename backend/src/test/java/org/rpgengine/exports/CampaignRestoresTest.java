package org.rpgengine.exports;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import javax.imageio.ImageIO;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.assets.AssetCategory;
import org.rpgengine.assets.ManagedAssets;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.commands.TypedCommand;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.exports.CampaignExports.Audience;
import org.rpgengine.exports.CampaignRestores.Code;
import org.rpgengine.exports.CampaignRestores.Identities;
import org.rpgengine.memory.MemorySummaries;
import org.rpgengine.memory.OperationalCheckpoints;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real PostgreSQL acceptance of P16.4-A (QA-23 at the campaign scope). The source is the bundled sample with one added
 * image asset, the fixture's original artwork: a campaign entered the ford ambush, so its fight is active and the
 * entry's narration is still owed, and it holds an operational checkpoint and a summary invalidation. The destination
 * workspace has the same title as the source and a campaign with the same title as the restored one, so any lookup by
 * title would merge them.
 */
@SpringBootTest
class CampaignRestoresTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000164a0");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ASSET = "/packs/fantasy-exploration/assets/map.png";
    private static final String TITLE = "Lantern Ford";
    private static final String CAMPAIGN = "Ford watch";
    /** Tables whose restored rows differ from the re-keyed source by design; each is checked on its own. */
    private static final Set<String> RENUMBERED = Set.of("inference_task", "campaign_checkpoint", "memory_invalidation");
    @Autowired private ManagedAssets artwork;
    @Autowired private CampaignRestores restores;
    @Autowired private CampaignExports exports;
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private OperationalCheckpoints checkpoints;
    @Autowired private MemorySummaries summaries;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID source;
    private UUID campaign;
    private UUID destination;
    private UUID destinationRevision;
    private UUID bystander;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    CampaignRestoresTest() { }

    /** Seal the sample with its artwork in two same-titled workspaces and start a fight in the source. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        owner(OWNER, "Owner");
        source = workspace(OWNER, TITLE);
        UUID sourceRevision = seal(source, pack(false));
        campaign = campaign(source, sourceRevision);
        execute(source, campaign, new TypedCommand.Engage("ford-ambush", null));
        assertEquals(OperationalCheckpoints.Capture.Outcome.CAPTURED, checkpoints.capture(campaign).outcome());
        UUID player = jdbc.queryForObject("select actor_id from campaign_creation where campaign_id = ?", UUID.class,
                campaign);
        new TransactionTemplate(manager).executeWithoutResult(status -> summaries.invalidate(campaign, "RESTORE_CHECK",
                List.of("actor:" + player)));
        destination = workspace(OWNER, TITLE);
        destinationRevision = seal(destination, pack(false));
        bystander = campaign(destination, destinationRevision);
    }

    /** Remove disposable rows. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate asset_association, managed_asset, encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, relationship_change, faction_clock_change, learned_recipe, canon_correction, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, "
                + "ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * A fresh-identity restore beside its source recovers every exported row under new identities, the active fight,
     * the owed narration, a valid checkpoint and the original artwork, merges with nothing of equal title, and plays
     * on without touching its source.
     */
    @Test
    void freshRestoreRecoversFightNarrationAndArtworkBesideItsSource() throws Exception {
        CampaignExports.Export export = exports.export(OWNER, source, campaign, Audience.FULL_OWNER);
        byte[] bystanderBefore = exports.export(OWNER, destination, bystander, Audience.FULL_OWNER).document();

        CampaignRestores.Restored restored = restores.restore(OWNER, destination, export.document(), export.sha256(),
                Identities.FRESH);
        UUID copy = restored.campaignId();
        assertFalse(Set.of(campaign, bystander).contains(copy), "a restore always creates a new campaign");
        assertEquals(destination, restored.adventureId());
        assertEquals(destinationRevision, restored.contentRevisionId());
        assertEquals(export.stateRevision(), restored.stateRevision());
        assertEquals(List.of(CAMPAIGN, CAMPAIGN), jdbc.queryForList("select title from campaign where adventure_id = ? "
                + "order by title", String.class, destination), "equal titles are never merged");
        assertArrayEquals(export.document(), exports.export(OWNER, source, campaign, Audience.FULL_OWNER).document(),
                "the source is unchanged");
        assertArrayEquals(bystanderBefore, exports.export(OWNER, destination, bystander, Audience.FULL_OWNER)
                .document(), "the same-titled campaign is unchanged");

        // Every identity is new, and only the source's own identities were mapped.
        String sourceText = export.text();
        assertEquals(copy, restored.identities().get(campaign));
        for (var entry : restored.identities().entrySet()) {
            assertTrue(sourceText.contains(entry.getKey().toString()));
            assertFalse(sourceText.contains(entry.getValue().toString()), "a restored identity existed in the source");
        }
        assertEquals(destination, restored.identities().get(source));
        assertTrue(restored.identities().size() > 10, "actors, operations, events, jobs and the fight were re-keyed");

        // Every exported row is restored, equal to the source row with its identities replaced.
        JsonNode before = JSON.readTree(sourceText).get("tables");
        JsonNode after = JSON.readTree(exports.export(OWNER, destination, copy, Audience.FULL_OWNER).text())
                .get("tables");
        int rows = 0;
        for (String table : new ArrayList<>(before.propertyNames())) {
            if (!RENUMBERED.contains(table)) {
                assertSameRows(substituted(before, table, restored), after.get(table), table);
            }
            rows += table.equals("inference_task") ? 0 : before.get(table).size();
        }
        assertEquals(rows, restored.rows());
        assertEquals(0, after.get("inference_task").size(), "scheduler work of the source is not restored");
        assertEquals(1, after.get("campaign_checkpoint").size());
        assertEquals(without(substituted(before, "campaign_checkpoint", restored), "snapshot_hash"),
                without(after.get("campaign_checkpoint"), "snapshot_hash"));
        assertNotEquals(before.at("/campaign_checkpoint/0/snapshot_hash"), after.at("/campaign_checkpoint/0/snapshot_hash"));
        assertEquals(OperationalCheckpoints.Verification.Status.VALID, checkpoints.verify(copy).status(),
                "the re-keyed checkpoint equals the restored state");
        assertEquals(1, after.get("memory_invalidation").size());
        assertEquals(without(substituted(before, "memory_invalidation", restored), "sequence"),
                without(after.get("memory_invalidation"), "sequence"));
        assertTrue(after.at("/memory_invalidation/0/sequence").asLong()
                > before.at("/memory_invalidation/0/sequence").asLong(), "invalidations are renumbered in order");

        // The active fight, the owed narration and the original artwork.
        assertEquals("ENCOUNTER", jdbc.queryForObject("select mode from campaign where id = ?", String.class, copy));
        assertEquals(1, count("select count(*) from encounter where campaign_id = ? and phase not in ('SETTLED', "
                + "'NARRATIVE_RESUME')", copy));
        int owed = count("select count(*) from operation_journal where campaign_id = ? and status in ('COMMITTED', "
                + "'NARRATING', 'COMMITTED_NARRATION_PENDING')", campaign);
        assertTrue(owed > 0, "the fixture owes narration");
        assertEquals(owed, count("select count(*) from operation_journal where campaign_id = ? and status in "
                + "('COMMITTED', 'NARRATING', 'COMMITTED_NARRATION_PENDING')", copy));
        assertEquals(count("select count(*) from presentation_job where campaign_id = ?", campaign),
                count("select count(*) from presentation_job where campaign_id = ?", copy));
        assertArrayEquals(artwork(), jdbc.queryForObject("select f.content from content_revision_file f join campaign c "
                + "on c.content_revision_id = f.content_revision_id where c.id = ? and f.path = 'assets/map.png'",
                byte[].class, copy), "the restored campaign serves the original artwork");

        // The restored fight plays on, and the source is not touched.
        var rolled = execute(destination, copy, new TypedCommand.RollInitiative());
        assertTrue(draws(rolled.operation().operationId()) != null, "the roll drew from the restored seed");
        assertEquals(1, count("select count(*) from campaign_event where campaign_id = ? and event_type = "
                + "'INITIATIVE_ROLLED'", copy));
        assertTrue(count("select round from encounter where campaign_id = ?", copy) >= 1);
        assertArrayEquals(export.document(), exports.export(OWNER, source, campaign, Audience.FULL_OWNER).document());

        // Restoring the same export again makes another campaign; nothing is matched or merged.
        UUID second = restores.restore(OWNER, destination, export.document(), export.sha256(), Identities.FRESH)
                .campaignId();
        assertFalse(Set.of(campaign, bystander, copy).contains(second));
        assertEquals(3, count("select count(*) from campaign where adventure_id = ?", destination));
    }

    /**
     * QA-31, restore part: the registered artwork of a campaign keeps its identity through a restore. The restored
     * campaign lists the same newest image per subject, with the same hash, credit and media facts, and serves exactly
     * the same bytes under that hash, while the older appearance, the source and the same-titled bystander are
     * untouched and the bystander is still refused the hash.
     */
    @Test
    void freshRestoreKeepsTheRegisteredArtworkIdentity() throws Exception {
        UUID pinned = jdbc.queryForObject("select content_revision_id from campaign where id = ?", UUID.class, campaign);
        byte[] older = image(11);
        byte[] current = image(12);
        publish(pinned, AssetCategory.ITEM, "lantern", 1, "Older lantern icon, MIT", older);
        publish(pinned, AssetCategory.ITEM, "lantern", 2, "Lantern icon, MIT", current);
        publish(pinned, AssetCategory.PORTRAIT, "scout", 1, "Scout portrait, CC0", image(13));
        var listed = artwork.artwork(OWNER, source, campaign).orElseThrow();
        assertEquals(2, listed.size());

        CampaignExports.Export export = exports.export(OWNER, source, campaign, Audience.FULL_OWNER);
        assertEquals(3, JSON.readTree(export.text()).at("/tables/asset_association").size(),
                "the export carries every association, old appearances included");
        UUID copy = restores.restore(OWNER, destination, export.document(), export.sha256(), Identities.FRESH)
                .campaignId();

        assertEquals(listed, artwork.artwork(OWNER, destination, copy).orElseThrow(),
                "the restored campaign lists the same image, credit and appearance per subject");
        assertEquals(3, count("select count(*) from asset_association where campaign_id = ?", copy));
        var lantern = artwork.current(copy, AssetCategory.ITEM, "lantern").orElseThrow();
        assertEquals(sha256(current), lantern.sha256());
        assertEquals("Lantern icon, MIT", lantern.credit());
        var served = artwork.serve(OWNER, destination, copy, sha256(current)).orElseThrow();
        assertArrayEquals(current, served.bytes());
        assertEquals("image/png", served.mediaType());
        assertTrue(artwork.serve(OWNER, destination, bystander, sha256(current)).isEmpty(),
                "a campaign without the association is not served the hash");
        assertEquals(listed, artwork.artwork(OWNER, source, campaign).orElseThrow(), "the source is unchanged");
    }

    /**
     * A restore into an installation that does not hold the image bytes is not refused: the associations are restored
     * and the restored campaign lists no image until the bytes exist, which the interface shows as a neutral icon.
     */
    @Test
    void restoreWithoutTheImageBytesKeepsTheAssociationsAndListsNoImage() throws Exception {
        UUID pinned = jdbc.queryForObject("select content_revision_id from campaign where id = ?", UUID.class, campaign);
        publish(pinned, AssetCategory.ITEM, "lantern", 1, "Lantern icon, MIT", image(14));
        CampaignExports.Export export = exports.export(OWNER, source, campaign, Audience.FULL_OWNER);
        clear();
        owner(OWNER, "Owner");
        UUID clean = workspace(OWNER, "Elsewhere");
        seal(clean, pack(false));

        var restored = restores.restore(OWNER, clean, export.document(), export.sha256(), Identities.ORIGINAL);
        assertEquals(1, count("select count(*) from asset_association where campaign_id = ?", restored.campaignId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from managed_asset", Integer.class), "no bytes were carried");
        assertTrue(artwork.artwork(OWNER, clean, restored.campaignId()).orElseThrow().isEmpty());
    }

    /** Kept identities restore exactly into a clean installation whose only change is the destination. */
    @Test
    void originalIdentitiesRestoreIntoACleanInstallation() throws Exception {
        CampaignExports.Export export = exports.export(OWNER, source, campaign, Audience.FULL_OWNER);
        clear();
        owner(OWNER, "Owner");
        UUID clean = workspace(OWNER, "Elsewhere");
        UUID revision = seal(clean, pack(false));

        var restored = restores.restore(OWNER, clean, export.document(), export.sha256(), Identities.ORIGINAL);
        assertEquals(campaign, restored.campaignId());
        assertEquals(Map.of(source, clean, revisionOf(export), revision), restored.identities());
        JsonNode before = JSON.readTree(export.text()).get("tables");
        JsonNode after = JSON.readTree(exports.export(OWNER, clean, campaign, Audience.FULL_OWNER).text()).get("tables");
        for (String table : new ArrayList<>(before.propertyNames())) {
            if (!RENUMBERED.contains(table)) {
                assertSameRows(substituted(before, table, restored), after.get(table), table);
            }
        }
        assertEquals(OperationalCheckpoints.Verification.Status.VALID, checkpoints.verify(campaign).status());
        execute(clean, campaign, new TypedCommand.RollInitiative());
    }

    /** Every refusal has its fixed code and leaves every table exactly as it was. */
    @Test
    void refusalsWriteNothing() throws Exception {
        CampaignExports.Export export = exports.export(OWNER, source, campaign, Audience.FULL_OWNER);
        byte[] bytes = export.document();
        UUID stranger = UUID.randomUUID();
        owner(stranger, "Stranger");
        UUID empty = workspace(OWNER, TITLE);
        UUID conflicting = workspace(OWNER, TITLE);
        seal(conflicting, pack(true));
        var counts = counts();

        refused(Code.INTEGRITY, destination, bytes, "0".repeat(64), Identities.FRESH);
        byte[] flipped = bytes.clone();
        flipped[flipped.length / 2] ^= 1;
        refused(Code.INTEGRITY, destination, flipped, export.sha256(), Identities.FRESH);
        byte[] player = exports.export(OWNER, source, campaign, Audience.PLAYER).document();
        refused(Code.FORMAT, destination, player, sha256(player), Identities.FRESH);
        byte[] garbage = "not json".getBytes(StandardCharsets.UTF_8);
        refused(Code.FORMAT, destination, garbage, sha256(garbage), Identities.FRESH);
        refused(Code.FORMAT, destination, edited(bytes, root -> ((ObjectNode) root.get("campaign"))
                .put("campaignId", UUID.randomUUID().toString())), Identities.FRESH);
        refused(Code.SCHEMA_MISMATCH, destination, edited(bytes, root -> ((ObjectNode) root).put("schemaVersion",
                "35")), Identities.FRESH);
        refused(Code.SCHEMA_MISMATCH, destination, edited(bytes, root -> ((ObjectNode) root.get("tables"))
                .remove("encounter")), Identities.FRESH);
        refused(Code.SCOPE_UNAVAILABLE, UUID.randomUUID(), bytes, export.sha256(), Identities.FRESH);
        var foreign = assertThrows(CampaignRestores.Refused.class, () -> restores.restore(stranger, destination, bytes,
                export.sha256(), Identities.FRESH));
        assertEquals(Code.SCOPE_UNAVAILABLE, foreign.code());
        refused(Code.CONTENT_UNAVAILABLE, empty, bytes, export.sha256(), Identities.FRESH);
        refused(Code.CONTENT_CONFLICT, conflicting, bytes, export.sha256(), Identities.FRESH);
        refused(Code.IDENTITY_IN_USE, destination, bytes, export.sha256(), Identities.ORIGINAL);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(
                status -> restores.restore(OWNER, destination, bytes, export.sha256(), Identities.FRESH)));
        assertThrows(NullPointerException.class, () -> restores.restore(OWNER, destination, bytes, export.sha256(),
                null));
        assertEquals(counts, counts(), "a refused restore wrote a row");
        assertArrayEquals(bytes, exports.export(OWNER, source, campaign, Audience.FULL_OWNER).document());
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private void refused(Code code, UUID into, byte[] document, String digest, Identities identities) {
        var refusal = assertThrows(CampaignRestores.Refused.class, () -> restores.restore(OWNER, into, document, digest,
                identities));
        assertEquals(code, refusal.code());
    }

    private void refused(Code code, UUID into, byte[] document, Identities identities) {
        refused(code, into, document, sha256(document), identities);
    }

    private static byte[] edited(byte[] document, java.util.function.Consumer<JsonNode> change) {
        JsonNode root = JSON.readTree(document);
        change.accept(root);
        return JSON.writeValueAsBytes(root);
    }

    /**
     * The bundled sample plus one image asset; {@code altered} appends a byte to the image, which keeps a valid header
     * but gives the same pack version other content.
     */
    private static Map<String, byte[]> pack(boolean altered) throws Exception {
        var files = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(CampaignRestoresTest.class.getResource("/sample-adventure").toURI())).files());
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(files.get("adventure.json"), Map.class);
        @SuppressWarnings("unchecked")
        var listed = new ArrayList<>((List<Object>) manifest.get("files"));
        listed.add(Map.of("path", "assets/map.png", "category", "asset"));
        manifest.put("files", listed);
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        byte[] image = artwork();
        if (altered) {
            image = java.util.Arrays.copyOf(image, image.length + 1);
        }
        files.put("assets/map.png", image);
        return files;
    }

    private void publish(UUID pinned, AssetCategory category, String subject, long appearance, String credit,
            byte[] bytes) {
        assertEquals(ManagedAssets.Status.ACCEPTED, artwork.publish(campaign, new ManagedAssets.Candidate(category,
                subject, appearance, 0, pinned, credit, bytes)).status());
    }

    /** A valid PNG whose bytes, and so hash, depend on the shade. */
    private static byte[] image(int shade) throws Exception {
        var picture = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        picture.setRGB(0, 0, 0x010101 * shade);
        var out = new ByteArrayOutputStream();
        ImageIO.write(picture, "png", out);
        return out.toByteArray();
    }

    private static byte[] artwork() throws Exception {
        try (InputStream asset = CampaignRestoresTest.class.getResourceAsStream(ASSET)) {
            return asset.readAllBytes();
        }
    }

    private void owner(UUID id, String name) {
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, ?, 'en', now(), now())", id, name);
    }

    private UUID workspace(UUID owner, String title) {
        return new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(owner, title, "fantasy", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
    }

    private UUID seal(UUID adventure, Map<String, byte[]> files) throws Exception {
        return registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(files)))).revisionId();
    }

    private UUID campaign(UUID adventure, UUID revision) {
        return creation.create(OWNER, adventure, new CampaignCreationService.Request(UUID.randomUUID(), revision,
                CAMPAIGN, SupportedLocale.EN, new CharacterChoices("Player", "scout", Map.of(), Map.of(), null, null)))
                .campaignId();
    }

    private CommandExecutionService.Execution execute(UUID adventure, UUID target, TypedCommand command) {
        long revision = jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, target);
        var execution = executions.execute(OWNER, adventure, target, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision, command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
        return execution;
    }

    /** One operation's draws as {@code ordinal:sides:value}. */
    private String draws(UUID operation) {
        return jdbc.queryForObject("select string_agg(ordinal || ':' || sides || ':' || value, ',' order by ordinal) "
                + "from operation_random_draw where operation_id = ?", String.class, operation);
    }

    private int count(String sql, UUID campaign) {
        return jdbc.queryForObject(sql, Integer.class, campaign);
    }

    private static UUID revisionOf(CampaignExports.Export export) {
        return UUID.fromString(JSON.readTree(export.text()).at("/content/revisionId").asString());
    }

    /** @return the row count of every table */
    private Map<String, Integer> counts() {
        var counts = new LinkedHashMap<String, Integer>();
        for (String table : jdbc.queryForList("select table_name::text from information_schema.tables where "
                + "table_schema = current_schema() and table_type = 'BASE TABLE' order by 1", String.class)) {
            counts.put(table, jdbc.queryForObject("select count(*) from \"" + table + "\"", Integer.class));
        }
        return counts;
    }

    private static JsonNode substituted(JsonNode tables, String table, CampaignRestores.Restored restored) {
        return JSON.readTree(substitute(JSON.writeValueAsString(tables.get(table)), restored.identities()));
    }

    private static String substitute(String text, Map<UUID, UUID> identities) {
        String result = text;
        for (var entry : identities.entrySet()) {
            result = result.replace(entry.getKey().toString(), entry.getValue().toString());
        }
        return result;
    }

    /** Equal rows in any order: the export orders rows by their text, which new identities may reorder. */
    private static void assertSameRows(JsonNode expected, JsonNode actual, String table) {
        assertEquals(expected.size(), actual.size(), table);
        var remaining = new ArrayList<JsonNode>();
        actual.forEach(remaining::add);
        for (JsonNode row : expected) {
            assertTrue(remaining.remove(row), table + " lacks a restored row: " + row);
        }
    }

    private static JsonNode without(JsonNode rows, String field) {
        ArrayNode copy = (ArrayNode) rows.deepCopy();
        copy.forEach(row -> ((ObjectNode) row).remove(field));
        return copy;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
