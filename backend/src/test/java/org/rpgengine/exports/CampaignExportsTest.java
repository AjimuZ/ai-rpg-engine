package org.rpgengine.exports;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.commands.TypedCommand;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.exports.CampaignExports.Audience;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.quests.QuestPack;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of P16.2-A. Sentinel secrets are planted where a campaign keeps private data: the random
 * seed, the seed identity, an idempotency key, a hidden quest with internal events, a claim only an NPC holds, that
 * NPC's name, a legacy free-text fact and the account name. The player export must contain none of them; the
 * full-owner export must contain every one except the account data, which neither export carries.
 */
@SpringBootTest
class CampaignExportsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000162a0");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ACCOUNT = "Sentinel Account Holder";
    private static final String IDEMPOTENCY = "sentinel-idempotency-key";
    private static final String NPC = "Sentinel Warden";
    private static final String PRIVATE_CLAIM = "Sentinel secret: the key lies under the third stone.";
    private static final String LEGACY_FACT = "Sentinel legacy fact text";
    private static final byte[] SEED = "SENTINEL-SEED-0123456789abcdef!!".getBytes(StandardCharsets.US_ASCII);
    private static final List<String> TABLES = List.of("campaign", "campaign_event", "operation_journal",
            "canonical_outbox", "actor", "actor_resource", "item_instance", "quest_state", "knowledge_claim",
            "knowledge_belief", "campaign_random_seed", "presentation_job", "ai_job", "memory_summary_job");
    @Autowired private CampaignExports exports;
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID adventure;
    private UUID content;
    private UUID campaign;
    private UUID player;
    private long revision;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    CampaignExportsTest() { }

    /** Register the quest pack for an owner with a sentinel account name and create an English campaign. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, ?, 'en', now(), now())", OWNER, ACCOUNT);
        adventure = new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(OWNER, "Exports", "exports", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(QuestPack.files())))).revisionId();
        var created = creation.create(OWNER, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null,
                        null)));
        campaign = created.campaignId();
        player = created.actorId();
        revision = 0;
    }

    /** Remove disposable rows. */
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
     * The sentinel test: after real play has produced a hidden quest with internal events, and after private rows
     * are planted beside it, the player export names none of the secrets while the full-owner export holds them all.
     */
    @Test
    void playerExportHoldsNoPrivateDataOrSecrets() {
        jdbc.update("insert into campaign_random_seed (campaign_id, seed, algorithm, algorithm_version, created_at) "
                + "values (?, ?, 'HMAC-SHA-256', 1, now())", campaign, SEED);
        execute(new TypedCommand.AcceptQuest("survey"), IDEMPOTENCY);
        execute(new TypedCommand.Move("grove"), UUID.randomUUID().toString());
        assertEquals("active", jdbc.queryForObject("select status from quest_state where campaign_id = ? and quest_id = "
                + "'shadow'", String.class, campaign), "the hidden quest is running, so it is a live secret");
        assertTrue(jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? and visibility = "
                + "'INTERNAL'", Integer.class, campaign) > 0, "play produced internal events");
        plantPrivateKnowledge();
        String seedIdentity = jdbc.queryForObject("select seed_identity from campaign where id = ?", String.class,
                campaign);
        String seedHex = HexFormat.of().formatHex(SEED);

        CampaignExports.Export shared = exports.export(OWNER, adventure, campaign, Audience.PLAYER);
        String text = shared.text();
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("hidden quest", "shadow");
        secrets.put("seed bytes", new String(SEED, StandardCharsets.US_ASCII));
        secrets.put("seed hex", seedHex);
        secrets.put("seed identity", seedIdentity);
        secrets.put("idempotency key", IDEMPOTENCY);
        secrets.put("NPC-only claim", PRIVATE_CLAIM);
        secrets.put("NPC name", NPC);
        secrets.put("legacy fact", LEGACY_FACT);
        secrets.put("account name", ACCOUNT);
        secrets.put("owner identity", OWNER.toString());
        secrets.put("internal visibility", "INTERNAL");
        for (var secret : secrets.entrySet()) {
            assertFalse(text.contains(secret.getValue()), "player export leaks the " + secret.getKey());
        }
        JsonNode document = JSON.readTree(text);
        assertEquals(List.of("audience", "campaign", "content", "format", "formatVersion", "journal", "panels"),
                sortedFields(document), "the player document has exactly the documented sections");
        assertEquals("player", document.get("audience").asText());
        assertEquals(revision, document.at("/campaign/stateRevision").asLong());
        assertEquals(revision, shared.stateRevision());
        // What the player may see is there: the accepted quest and every public event, worded.
        assertTrue(text.contains("survey"));
        assertEquals(jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? and visibility = "
                + "'PUBLIC'", Integer.class, campaign), document.get("journal").size());
        assertEquals("Player", document.at("/panels/character/name").asText());

        // The full-owner export holds every planted secret except account data, which no export carries.
        String full = exports.export(OWNER, adventure, campaign, Audience.FULL_OWNER).text();
        for (var secret : secrets.entrySet()) {
            boolean account = secret.getKey().equals("account name") || secret.getKey().equals("owner identity");
            boolean raw = secret.getKey().equals("seed bytes");
            assertEquals(!account && !raw, full.contains(secret.getValue()), "full-owner export and the "
                    + secret.getKey());
        }
    }

    /**
     * The full-owner export copies every row of every campaign-scoped table, and only this campaign's rows; both
     * exports are deterministic, match their digest, name the pinned content and write nothing.
     */
    @Test
    void ownerExportIsCompleteDeterministicAndReadOnly() {
        execute(new TypedCommand.AcceptQuest("survey"), UUID.randomUUID().toString());
        // A second campaign of the same owner must not appear in the first one's export.
        UUID other = creation.create(OWNER, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Other", SupportedLocale.EN, new CharacterChoices("Other", "surveyor", Map.of(), Map.of(), null, null)))
                .campaignId();
        var before = tables();
        CampaignExports.Export first = exports.export(OWNER, adventure, campaign, Audience.FULL_OWNER);
        CampaignExports.Export again = exports.export(OWNER, adventure, campaign, Audience.FULL_OWNER);
        CampaignExports.Export shared = exports.export(OWNER, adventure, campaign, Audience.PLAYER);
        assertArrayEquals(first.document(), again.document(), "two exports of one revision differ");
        assertArrayEquals(shared.document(), exports.export(OWNER, adventure, campaign, Audience.PLAYER).document());
        assertEquals(before, tables(), "an export wrote a row");
        for (CampaignExports.Export export : List.of(first, shared)) {
            assertEquals(sha256(export.document()), export.sha256());
            JsonNode document = JSON.readTree(export.text());
            assertEquals(CampaignExports.FORMAT, document.get("format").asText());
            assertEquals(1, document.get("formatVersion").asInt());
            assertEquals(campaign.toString(), document.at("/campaign/campaignId").asText());
            assertEquals(content.toString(), document.at("/content/revisionId").asText());
            assertEquals(jdbc.queryForObject("select content_hash from content_revision where id = ?", String.class,
                    content), document.at("/content/contentHash").asText());
        }
        JsonNode document = JSON.readTree(first.text());
        assertEquals("full-owner", document.get("audience").asText());
        assertEquals(jdbc.queryForObject("select version from flyway_schema_history where success and version is not "
                + "null order by installed_rank desc limit 1", String.class), document.get("schemaVersion").asText());
        // Every base table with a campaign column is present, with exactly this campaign's row count.
        var expected = new ArrayList<String>(jdbc.queryForList("select c.table_name::text from "
                + "information_schema.columns c join information_schema.tables t on t.table_schema = c.table_schema and "
                + "t.table_name = c.table_name where c.table_schema = current_schema() and c.column_name = 'campaign_id' "
                + "and t.table_type = 'BASE TABLE'", String.class));
        expected.add("campaign");
        expected.sort(null);
        JsonNode tables = document.get("tables");
        assertEquals(expected, sortedFields(tables));
        for (String table : expected) {
            String key = table.equals("campaign") ? "id" : "campaign_id";
            assertEquals(jdbc.queryForObject("select count(*) from \"" + table + "\" where " + key + " = ?",
                    Integer.class, campaign), tables.get(table).size(), table);
            for (JsonNode row : tables.get(table)) {
                assertEquals(campaign.toString(), row.get(key).asText(), table);
            }
        }
        assertTrue(tables.get("operation_journal").size() > 0 && tables.get("campaign_event").size() > 0);
        assertFalse(first.text().contains(other.toString()), "another campaign leaked into the export");
        assertFalse(first.text().contains(ACCOUNT));
    }

    /** A journal longer than one page is exported whole, newest first, with no entry repeated or skipped. */
    @Test
    void thePlayerJournalSpansEveryPage() {
        for (int wait = 0; wait < 105; wait++) {
            execute(new TypedCommand.Wait(60), UUID.randomUUID().toString());
        }
        JsonNode journal = JSON.readTree(exports.export(OWNER, adventure, campaign, Audience.PLAYER).text())
                .get("journal");
        List<Long> sequences = new ArrayList<>();
        journal.forEach(entry -> sequences.add(entry.get("sequence").asLong()));
        assertEquals(jdbc.queryForList("select sequence from campaign_event where campaign_id = ? and visibility = "
                + "'PUBLIC' order by sequence desc", Long.class, campaign), sequences);
        assertTrue(sequences.size() > 100, "more than one journal page");
    }

    /** Another owner, another adventure or an unknown campaign gets one uniform failure for both audiences. */
    @Test
    void scopeIsCheckedForBothAudiences() {
        UUID stranger = UUID.randomUUID();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Stranger', 'en', now(), now())", stranger);
        for (Audience audience : Audience.values()) {
            assertThrows(CampaignExports.ScopeUnavailable.class,
                    () -> exports.export(stranger, adventure, campaign, audience));
            assertThrows(CampaignExports.ScopeUnavailable.class,
                    () -> exports.export(OWNER, UUID.randomUUID(), campaign, audience));
            assertThrows(CampaignExports.ScopeUnavailable.class,
                    () -> exports.export(OWNER, adventure, UUID.randomUUID(), audience));
            assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(
                    status -> exports.export(OWNER, adventure, campaign, audience)));
        }
        assertThrows(NullPointerException.class, () -> exports.export(OWNER, adventure, campaign, null));
    }

    // --- helpers -------------------------------------------------------------------------------------------

    /** An NPC who holds a claim nobody else knows, plus a legacy free-text fact that is no player source. */
    private void plantPrivateKnowledge() {
        UUID warden = new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, "warden", NPC, "trailhead", 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
        long sequence = jdbc.queryForObject("select max(sequence) from campaign_event where campaign_id = ?", Long.class,
                campaign);
        UUID claim = UUID.randomUUID();
        jdbc.update("insert into knowledge_claim (id, campaign_id, subject_ref, predicate, statement, statement_locale, "
                + "disclosure, truth, established_revision, established_world_time, established_event_sequence, "
                + "truth_revision, truth_event_sequence) values (?, ?, ?, 'STATEMENT', ?, 'en', 'HOLDERS', "
                + "'UNDETERMINED', ?, 60, ?, ?, ?)", claim, campaign, "actor:" + warden, PRIVATE_CLAIM, revision,
                sequence, revision, sequence);
        jdbc.update("insert into knowledge_belief (id, campaign_id, holder_actor_id, claim_id, stance, acquisition, "
                + "learned_revision, learned_world_time, learned_event_sequence) values (?, ?, ?, ?, 'BELIEVES', "
                + "'OBSERVED', ?, 60, ?)", UUID.randomUUID(), campaign, warden, claim, revision, sequence);
        jdbc.update("insert into campaign_known_fact (id, campaign_id, actor_id, fact_text, confidence, learned_revision, "
                + "row_version) values (?, ?, ?, ?, 'KNOWN', 0, 0)", UUID.randomUUID(), campaign, player, LEGACY_FACT);
    }

    private void execute(TypedCommand command, String key) {
        var execution = executions.execute(OWNER, adventure, campaign, new OperationAdmissionService.Command(key,
                revision, command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
        revision = execution.operation().committedRevision();
    }

    private static List<String> sortedFields(JsonNode node) {
        var names = new ArrayList<String>(node.propertyNames());
        names.sort(null);
        return names;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private Map<String, List<String>> tables() {
        var snapshot = new LinkedHashMap<String, List<String>>();
        for (String table : TABLES) {
            snapshot.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return snapshot;
    }
}
