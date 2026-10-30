package org.rpgengine.panels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionFailure;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.commands.TypedCommand;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.quests.QuestPack;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL and servlet acceptance of P10.5: after every committed command the panels show exactly
 * the committed state (clock, resources, conditions, inventory and worn equipment, quests with their
 * objectives and outcome, public events worded in the campaign language), a hidden background quest and
 * internal events never appear, reading changes no row, a refused or failed command leaves the panels
 * as they were, and the journal pages by a stable cursor.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "engine.http.owner-id=00000000-0000-0000-0000-000000000105",
        "engine.stream.hold-ms=100", "engine.stream.poll-ms=50"})
class PlayPanelsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000105");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> TABLES = List.of("campaign", "campaign_event", "operation_journal",
            "canonical_outbox", "actor", "actor_resource", "actor_condition", "item_instance", "item_creation",
            "quest_state", "quest_objective", "location_visit", "query_epoch", "reward_settlement",
            "campaign_checkpoint", "campaign_known_fact", "campaign_known_quest", "memory_summary_job",
            "presentation_job", "operation_random_draw", "knowledge_claim", "knowledge_belief", "actor_commitment");
    @LocalServerPort private int port;
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private PlayPanels service;
    private UUID adventure;
    private UUID content;
    private UUID campaign;
    private UUID player;
    private long revision;
    private String cookie;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    PlayPanelsTest() { }

    /** Register the quest pack for the configured owner, create an English campaign and open a browser session. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        adventure = new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(OWNER, "Panels", "panels", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(QuestPack.files())))).revisionId();
        var created = create(SupportedLocale.EN);
        campaign = created.campaignId();
        player = created.actorId();
        revision = 0;
        var session = get("/api/v1/session");
        cookie = session.headers().lines().filter(line -> line.toLowerCase().startsWith("set-cookie:"))
                .findFirst().orElseThrow().substring("set-cookie:".length()).strip().split(";", 2)[0];
    }

    /** Remove disposable rows. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, "
                + "ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /** A new campaign shows its starting character, resources, worn equipment and the quests that start available. */
    @Test
    void aNewCampaignShowsItsStartingState() throws Exception {
        JsonNode panels = panels(campaign, "");
        assertEquals(0, panels.at("/scope/stateRevision").asInt());
        assertEquals(0, panels.at("/clock/seconds").asInt());
        assertEquals(1, panels.at("/clock/day").asInt(), "days are numbered from 1");
        assertEquals("Player", panels.at("/character/name").asText());
        assertTrue(panels.get("contentAvailable").asBoolean());
        assertEquals("en", panels.get("locale").asText());
        // Attributes carry their public pack names and, with no condition, equal base and effective values.
        for (JsonNode attribute : panels.at("/character/attributes")) {
            assertEquals(attribute.get("base").asLong(), attribute.get("effective").asLong());
            assertFalse(attribute.get("name").isNull(), attribute.toString());
        }
        assertEquals("Vigor", panels.at("/resources/0/name/text").asText());
        assertEquals(jdbc.queryForObject("select current_value from actor_resource where actor_id = ? and resource_key = 'vigor'",
                Long.class, player), panels.at("/resources/0/current").asLong());
        // Worn equipment is the stored assignment, and each worn stack says where it is worn.
        assertEquals(count("equipment_assignment"), panels.get("equipment").size());
        JsonNode sword = byField(panels.get("inventory"), "definitionId", "sword");
        assertEquals("main-hand", sword.get("wornIn").asText());
        assertEquals("Sword", sword.at("/name/text").asText());
        assertEquals("Ordinary", sword.at("/rarityName/text").asText());
        assertTrue(byField(panels.get("inventory"), "definitionId", "kit").get("wornIn").isNull());
        // Quests that start available are discovered; an undiscovered one and a hidden one are not listed.
        assertEquals(List.of("supply", "survey"), ids(panels.get("quests")));
        JsonNode survey = byField(panels.get("quests"), "id", "survey");
        assertEquals("available", survey.get("status").asText());
        assertEquals("Find the Waystone", survey.at("/name/text").asText());
        assertEquals("Visit the grove.", survey.at("/summary/text").asText());
        assertEquals(0, survey.get("objectives").size(), "objectives appear once the quest is accepted");
        assertTrue(survey.get("outcome").isNull());
    }

    /** Each command's result appears in the panels at the revision it committed, and nowhere else. */
    @Test
    void panelsFollowEveryCommittedChange() throws Exception {
        execute(new TypedCommand.AcceptQuest("survey"));
        JsonNode accepted = panels(campaign, "");
        assertEquals(revision, accepted.at("/scope/stateRevision").asLong());
        JsonNode survey = byField(accepted.get("quests"), "id", "survey");
        assertEquals("active", survey.get("status").asText());
        assertEquals(List.of("reach", "provisions"), ids(survey.get("objectives")));
        assertEquals("Reach the grove.", survey.at("/objectives/0/description/text").asText());
        assertFalse(survey.at("/objectives/0/satisfied").asBoolean());
        assertTrue(survey.at("/objectives/1/optional").asBoolean());
        assertEquals("QUEST_ACCEPTED", accepted.at("/journal/entries/0/type").asText());
        assertTrue(accepted.at("/journal/entries/0/statement").asText().contains("survey"));

        execute(new TypedCommand.Move("grove"));
        JsonNode moved = panels(campaign, "");
        assertEquals(clock(), moved.at("/clock/seconds").asLong());
        assertEquals(revision, moved.at("/scope/stateRevision").asLong());
        assertEquals("grove", moved.at("/character/location/id").asText());
        // The ending paid its reward once and says so; the unlocked quest is discovered, the hidden one is not.
        JsonNode ended = byField(moved.get("quests"), "id", "survey");
        assertEquals("completed", ended.get("status").asText());
        assertEquals("surveyed", ended.at("/outcome/id").asText());
        assertEquals("success", ended.at("/outcome/kind").asText());
        assertEquals(List.of("coin", "potion"), ids(ended.at("/outcome/rewards"), "itemId"));
        assertTrue(ended.at("/objectives/0/satisfied").asBoolean());
        assertEquals(10, byField(moved.get("inventory"), "definitionId", "coin").get("quantity").asInt());
        assertEquals(List.of("gate", "supply", "survey"), ids(moved.get("quests")));
        assertEquals(1, jdbc.queryForObject("select count(*) from quest_state where quest_id = 'shadow'", Integer.class),
                "the hidden quest exists in canonical state, so its absence above is the projection's doing");
        // Nothing in the journal mentions the hidden quest or an internal event.
        for (JsonNode entry : moved.at("/journal/entries")) {
            assertFalse(entry.get("statement").asText().contains("shadow"), entry.toString());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? and visibility = "
                + "'PUBLIC' and payload::text like '%shadow%'", Integer.class, campaign));
        List<Long> sequences = new ArrayList<>();
        moved.at("/journal/entries").forEach(entry -> sequences.add(entry.get("sequence").asLong()));
        assertEquals(sequences.stream().sorted(java.util.Comparator.reverseOrder()).toList(), sequences, "newest first");
        assertEquals(jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? and visibility = "
                + "'PUBLIC'", Integer.class, campaign) , sequences.size(), "every public event here is statable");

        // Taking off the shield changes the stack's slot and the equipment list together.
        UUID shield = jdbc.queryForObject("select id from item_instance where campaign_id = ? and actor_id = ? and "
                + "definition_id = 'shield'", UUID.class, campaign, player);
        int worn = moved.get("equipment").size();
        execute(new TypedCommand.UnequipItem(shield));
        JsonNode bare = panels(campaign, "");
        assertEquals(worn - 1, bare.get("equipment").size());
        assertTrue(byField(bare.get("inventory"), "definitionId", "shield").get("wornIn").isNull());
        assertEquals("ITEM_UNEQUIPPED", bare.at("/journal/entries/0/type").asText());
    }

    /** Conditions show with their rounds, and the effective attribute reflects the pinned modifier. */
    @Test
    void conditionsChangeTheEffectiveSheet() throws Exception {
        jdbc.update("insert into actor_condition (campaign_id, actor_id, condition_id, stacks, remaining_rounds, "
                + "applied_revision, updated_revision) values (?, ?, 'dazed', 1, 3, 0, 0)", campaign, player);
        JsonNode panels = panels(campaign, "");
        assertEquals(1, panels.get("conditions").size());
        assertEquals("Dazed", panels.at("/conditions/0/name/text").asText());
        assertEquals(3, panels.at("/conditions/0/remainingRounds").asInt());
        JsonNode awareness = byField(panels.at("/character/attributes"), "key", "awareness");
        assertEquals(awareness.get("base").asLong() - 2, awareness.get("effective").asLong());
        // A derived statistic is recomputed from the effective attributes.
        assertFalse(panels.at("/character/derived").isEmpty());
    }

    /** The journal returns stable pages: no event repeats or is skipped, and the last page has no cursor. */
    @Test
    void theJournalPagesByCursor() throws Exception {
        execute(new TypedCommand.AcceptQuest("survey"));
        execute(new TypedCommand.Move("grove"));
        execute(new TypedCommand.Wait(60));
        execute(new TypedCommand.Move("trailhead"));
        JsonNode all = panels(campaign, "&limit=100");
        assertTrue(all.at("/journal/nextBefore").isNull());
        List<Long> everything = sequences(all);
        assertTrue(everything.size() >= 6, everything.toString());
        var paged = new ArrayList<Long>();
        String cursor = "";
        int pages = 0;
        while (true) {
            JsonNode page = panels(campaign, "&limit=2" + cursor);
            assertTrue(page.at("/journal/entries").size() <= 2);
            paged.addAll(sequences(page));
            pages++;
            JsonNode next = page.at("/journal/nextBefore");
            if (next.isNull()) {
                break;
            }
            assertEquals(page.at("/journal/entries/1/sequence").asLong(), next.asLong());
            cursor = "&before=" + next.asLong();
        }
        assertEquals(everything, paged);
        assertTrue(pages >= 3);
        // A new commit adds an event in front; a cursor taken before it still pages exactly the old past.
        long newest = everything.getFirst();
        execute(new TypedCommand.Wait(60));
        JsonNode fresh = panels(campaign, "&limit=1");
        assertEquals("TIME_PASSED", fresh.at("/journal/entries/0/type").asText());
        assertTrue(fresh.at("/journal/entries/0/sequence").asLong() > newest);
        assertEquals(everything, sequences(panels(campaign, "&limit=100&before=" + (newest + 1))));
    }

    /** Events are worded in the campaign's narrative language, not the owner's. */
    @Test
    void statementsUseTheCampaignLanguage() throws Exception {
        execute(new TypedCommand.Wait(60));
        String english = panels(campaign, "").at("/journal/entries/0/statement").asText();
        assertEquals("Waiting: 60 seconds pass.", english);
        var italian = create(SupportedLocale.IT);
        campaign = italian.campaignId();
        revision = 0;
        execute(new TypedCommand.Wait(60));
        JsonNode panels = panels(campaign, "");
        assertEquals("it", panels.get("locale").asText());
        assertEquals("Attesa: trascorrono 60 secondi.", panels.at("/journal/entries/0/statement").asText());
    }

    /** Reading is free: repeated reads change no row and not the clock, and a refused command changes no panel. */
    @Test
    void readsAndRefusalsChangeNothing() throws Exception {
        execute(new TypedCommand.Move("grove"));
        var before = tables();
        String first = panelsBody(campaign, "");
        for (int round = 0; round < 3; round++) {
            assertEquals(first, panelsBody(campaign, ""), "panels differ between two reads of one revision");
            Thread.sleep(200);
        }
        assertEquals(before, tables(), "a panel read wrote a row");
        // A refused command (the stale revision) leaves the panels byte-identical.
        var stale = assertThrows(OperationAdmissionFailure.class, () -> executions.execute(OWNER, adventure, campaign,
                new OperationAdmissionService.Command(UUID.randomUUID().toString(), 0, new TypedCommand.Wait(60).type(),
                        new TypedCommand.Wait(60).payload())));
        assertNotEquals(null, stale.kind());
        assertEquals(first, panelsBody(campaign, ""));
        // A real command changes them.
        execute(new TypedCommand.Wait(60));
        assertNotEquals(first, panelsBody(campaign, ""));
    }

    /** A foreign, unknown or malformed scope and bad paging parameters get the fixed codes and no data. */
    @Test
    void scopeAndInputAreChecked() throws Exception {
        String base = "/api/v1/campaigns/" + campaign + "/panels";
        var foreign = get(base + "?adventureId=" + UUID.randomUUID());
        assertEquals(404, foreign.status());
        assertEquals("CAMPAIGN_SCOPE_UNAVAILABLE", JSON.readTree(foreign.body()).get("code").asText());
        assertEquals(404, get("/api/v1/campaigns/" + UUID.randomUUID() + "/panels?adventureId=" + adventure).status());
        assertEquals(400, get("/api/v1/campaigns/not-a-uuid/panels?adventureId=" + adventure).status());
        assertEquals(400, get(base).status(), "the adventure is required");
        for (String bad : List.of("&limit=0", "&limit=101", "&limit=x", "&before=0", "&before=-3", "&before=07", "&before=x")) {
            assertEquals(400, get(base + "?adventureId=" + adventure + bad).status(), bad);
        }
        assertEquals(200, get(base + "?adventureId=" + adventure + "&limit=100").status());
        // Without a session the local admission denies the read.
        String saved = cookie;
        cookie = null;
        assertEquals(403, get(base + "?adventureId=" + adventure).status());
        cookie = saved;
    }

    /**
     * Knowledge comes from the typed records only: a told rumor keeps its stance, a common claim is shown as common,
     * a claim without a statement and another holder's belief are not shown, promises carry their direction, and the
     * legacy free-text fact table is no panel source.
     */
    @Test
    void typedKnowledgeAppearsWithItsStance() throws Exception {
        JsonNode empty = panels(campaign, "");
        assertEquals(0, empty.at("/knowledge/discoveriesTotal").asInt());
        assertEquals(0, empty.at("/knowledge/promisesTotal").asInt());
        execute(new TypedCommand.Wait(60));
        UUID warden = new TransactionTemplate(manager).execute(status -> {
            var actor = org.rpgengine.characters.Actor.create(campaign, org.rpgengine.characters.ActorKind.NPC, "warden",
                    "Warden", "trailhead", 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
        long sequence = jdbc.queryForObject("select max(sequence) from campaign_event where campaign_id = ?", Long.class, campaign);
        UUID rumor = UUID.randomUUID();
        UUID common = UUID.randomUUID();
        UUID exists = UUID.randomUUID();
        UUID wardens = UUID.randomUUID();
        claim(rumor, "actor:" + warden, "STATEMENT", "The warden hides a key.", "HOLDERS", sequence);
        claim(common, "location:grove", "STATEMENT", "The grove is safe by day.", "COMMON", sequence);
        claim(exists, "location:grove", "EXISTS", null, "COMMON", sequence);
        claim(wardens, "actor:" + warden, "STATEMENT", "Only the warden knows this.", "HOLDERS", sequence);
        jdbc.update("insert into knowledge_belief (id, campaign_id, holder_actor_id, claim_id, stance, acquisition, "
                + "informant_actor_id, learned_revision, learned_world_time, learned_event_sequence) values (?, ?, ?, ?, "
                + "'RUMORED', 'TOLD', ?, ?, 60, ?)", UUID.randomUUID(), campaign, player, rumor, warden, revision, sequence);
        // The player also holds a belief about a claim that has no statement; there is nothing to show for it.
        jdbc.update("insert into knowledge_belief (id, campaign_id, holder_actor_id, claim_id, stance, acquisition, "
                + "learned_revision, learned_world_time, learned_event_sequence) values (?, ?, ?, ?, 'BELIEVES', "
                + "'OBSERVED', ?, 60, ?)", UUID.randomUUID(), campaign, player, exists, revision, sequence);
        jdbc.update("insert into knowledge_belief (id, campaign_id, holder_actor_id, claim_id, stance, acquisition, "
                + "learned_revision, learned_world_time, learned_event_sequence) values (?, ?, ?, ?, 'BELIEVES', "
                + "'OBSERVED', ?, 60, ?)", UUID.randomUUID(), campaign, warden, wardens, revision, sequence);
        commitment(warden, player, "Bring lamp oil.", sequence);
        commitment(player, warden, "Guard the gate.", sequence);
        JsonNode panels = panels(campaign, "");
        assertEquals(2, panels.at("/knowledge/discoveriesTotal").asInt());
        JsonNode told = byField(panels.at("/knowledge/discoveries"), "statement", "The warden hides a key.");
        assertEquals("RUMORED", told.get("stance").asText());
        assertEquals("TOLD", told.get("acquisition").asText());
        assertEquals("COMMON", byField(panels.at("/knowledge/discoveries"), "statement",
                "The grove is safe by day.").get("stance").asText());
        assertEquals(2, panels.at("/knowledge/promisesTotal").asInt());
        assertEquals("OWED_TO_YOU", byField(panels.at("/knowledge/promises"), "terms", "Bring lamp oil.")
                .get("direction").asText());
        JsonNode owed = byField(panels.at("/knowledge/promises"), "terms", "Guard the gate.");
        assertEquals("YOU_OWE", owed.get("direction").asText());
        assertEquals("Warden", owed.get("creditor").asText());
        // A legacy free-text fact is not a panel source any more.
        jdbc.update("insert into campaign_known_fact (id, campaign_id, actor_id, fact_text, confidence, learned_revision, "
                + "row_version) values (?, ?, ?, 'legacy text', 'KNOWN', 0, 0)", UUID.randomUUID(), campaign, player);
        assertEquals(2, panels(campaign, "").at("/knowledge/discoveriesTotal").asInt());
        assertFalse(panelsBody(campaign, "").contains("legacy text"));
    }

    private void claim(UUID id, String subject, String predicate, String statement, String disclosure, long sequence) {
        jdbc.update("insert into knowledge_claim (id, campaign_id, subject_ref, predicate, statement, statement_locale, "
                + "disclosure, truth, established_revision, established_world_time, established_event_sequence, "
                + "truth_revision, truth_event_sequence) values (?, ?, ?, ?, ?, ?, ?, 'UNDETERMINED', ?, 60, ?, ?, ?)",
                id, campaign, subject, predicate, statement, statement == null ? null : "en", disclosure, revision,
                sequence, revision, sequence);
    }

    private void commitment(UUID debtor, UUID creditor, String terms, long sequence) {
        jdbc.update("insert into actor_commitment (id, campaign_id, kind, debtor_actor_id, creditor_actor_id, terms, "
                + "terms_locale, quoted, disclosure, status, made_revision, made_world_time, made_event_sequence) "
                + "values (?, ?, 'PROMISE', ?, ?, ?, 'en', false, 'PARTIES', 'OPEN', ?, 60, ?)", UUID.randomUUID(),
                campaign, debtor, creditor, terms, revision, sequence);
    }

    /**
     * A hidden background quest appears only once its ending is committed, and even then its secret text stays
     * out: the panel lists its identity, status, outcome and the public names of the units it paid.
     */
    @Test
    void anEndedHiddenQuestIsListedWithoutItsSecretText() throws Exception {
        execute(new TypedCommand.AcceptQuest("survey"));
        execute(new TypedCommand.Move("grove"));
        assertEquals("active", jdbc.queryForObject("select status from quest_state where quest_id = 'shadow'", String.class));
        assertEquals(List.of("gate", "supply", "survey"), ids(panels(campaign, "").get("quests")));
        // Fifteen coins and a move make the hidden quest's objective hold, which ends it.
        jdbc.update("update item_instance set quantity = 15, row_version = row_version + 1 where campaign_id = ? and "
                + "definition_id = 'coin'", campaign);
        execute(new TypedCommand.Move("trailhead"));
        JsonNode panels = panels(campaign, "");
        assertEquals(List.of("gate", "shadow", "supply", "survey"), ids(panels.get("quests")));
        JsonNode shadow = byField(panels.get("quests"), "id", "shadow");
        assertEquals("completed", shadow.get("status").asText());
        assertTrue(shadow.get("name").isNull(), "the name of a secret quest is secret text");
        assertTrue(shadow.get("summary").isNull());
        assertEquals("success", shadow.at("/outcome/kind").asText());
        assertEquals("Relic", shadow.at("/outcome/rewards/0/name/text").asText());
        assertTrue(shadow.at("/objectives/0/description").isNull());
        var statements = new ArrayList<String>();
        panels.at("/journal/entries").forEach(entry -> statements.add(entry.get("statement").asText()));
        assertTrue(statements.stream().anyMatch(text -> text.contains("shadow") && text.contains("completed")),
                "its ending is public: " + statements);
        assertEquals(1, statements.stream().filter(text -> text.contains("shadow")).count(),
                "only the ending is public, not its start or progress: " + statements);
    }

    /** A save without a player and a save whose pinned content cannot be loaded still read, without names or quests. */
    @Test
    void legacyAndUnloadableCampaignsStillRead() throws Exception {
        UUID legacy = UUID.randomUUID();
        jdbc.update("insert into campaign (id, adventure_id, content_revision_id, title, seed_identity, mode, created_at, "
                + "updated_at) values (?, ?, ?, 'Legacy save', 'private-legacy-seed', 'EXPLORATION', now(), now())",
                legacy, adventure, content);
        JsonNode bare = panels(legacy, "");
        assertTrue(bare.get("contentAvailable").asBoolean());
        assertTrue(bare.get("character").isNull());
        assertEquals(1, bare.at("/clock/day").asInt());
        assertTrue(bare.get("quests").isEmpty());
        assertFalse(bare.toString().contains("private-legacy-seed"));

        // The same revision without its sealed files: numbers and identifiers remain, names and quests do not.
        UUID unsealed = UUID.randomUUID();
        jdbc.update("insert into content_revision (id, adventure_id, pack_id, pack_version, content_hash, schema_version, "
                + "ruleset_id, ruleset_version, ruleset_hash, source_locale, admitted_at) select ?, adventure_id, pack_id, "
                + "'9.9.9', content_hash, schema_version, ruleset_id, ruleset_version, ruleset_hash, source_locale, "
                + "admitted_at from content_revision where id = ?", unsealed, content);
        UUID orphan = UUID.randomUUID();
        UUID hero = UUID.randomUUID();
        jdbc.update("insert into campaign (id, adventure_id, content_revision_id, title, seed_identity, mode, created_at, "
                + "updated_at) values (?, ?, ?, 'Orphan', 'orphan-seed', 'EXPLORATION', now(), now())", orphan, adventure, unsealed);
        jdbc.update("insert into actor (id, campaign_id, kind, display_name, location_id, created_revision, created_at) "
                + "values (?, ?, 'CHARACTER', 'Orphan hero', 'trailhead', 0, now())", hero, orphan);
        jdbc.update("insert into character_state (campaign_id, actor_id, attributes, skills) values (?, ?, "
                + "'{\"might\":2}'::jsonb, '{}'::jsonb)", orphan, hero);
        jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, profile_id, "
                + "choices, created_at) values (?, ?, ?, ?, ?, 'surveyor', '{}'::jsonb, now())", adventure, UUID.randomUUID(),
                "a".repeat(64), orphan, hero);
        JsonNode orphaned = panels(orphan, "");
        assertFalse(orphaned.get("contentAvailable").asBoolean());
        assertEquals("Orphan hero", orphaned.at("/character/name").asText());
        JsonNode might = byField(orphaned.at("/character/attributes"), "key", "might");
        assertEquals(2, might.get("base").asLong());
        assertEquals(2, might.get("effective").asLong());
        assertTrue(might.get("name").isNull());
        assertTrue(orphaned.get("quests").isEmpty());
        assertTrue(orphaned.at("/character/derived").isEmpty());
    }

    /** Another owner's identity cannot read a campaign, even with the right adventure and campaign identities. */
    @Test
    void anotherOwnerCannotReadTheCampaign() {
        UUID stranger = UUID.randomUUID();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Stranger', 'en', now(), now())", stranger);
        assertThrows(PlayPanels.ScopeUnavailable.class, () -> service.read(stranger, adventure, campaign, null, 25));
        assertEquals(0, service.read(OWNER, adventure, campaign, null, 25).scope().stateRevision());
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private CampaignCreationService.CreatedCampaign create(SupportedLocale locale) {
        return creation.create(OWNER, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", locale, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
    }

    private void execute(TypedCommand command) {
        var execution = executions.execute(OWNER, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision, command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
        revision = execution.operation().committedRevision();
    }

    private long clock() {
        return jdbc.queryForObject("select world_time_seconds from campaign where id = ?", Long.class, campaign);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where campaign_id = ?", Integer.class, campaign);
    }

    private JsonNode panels(UUID id, String query) throws Exception {
        return JSON.readTree(panelsBody(id, query));
    }

    private String panelsBody(UUID id, String query) throws Exception {
        var reply = get("/api/v1/campaigns/" + id + "/panels?adventureId=" + adventure + query);
        assertEquals(200, reply.status(), reply.body());
        return reply.body();
    }

    private static List<Long> sequences(JsonNode panels) {
        var result = new ArrayList<Long>();
        panels.at("/journal/entries").forEach(entry -> result.add(entry.get("sequence").asLong()));
        return result;
    }

    private static JsonNode byField(JsonNode list, String field, String value) {
        for (JsonNode node : list) {
            if (node.has(field) && value.equals(node.get(field).asText())) {
                return node;
            }
        }
        throw new AssertionError("no " + field + "=" + value + " in " + list);
    }

    private static List<String> ids(JsonNode list) {
        return ids(list, "id");
    }

    private static List<String> ids(JsonNode list, String field) {
        var result = new ArrayList<String>();
        list.forEach(node -> result.add(node.get(field).asText()));
        return result;
    }

    private Map<String, List<String>> tables() {
        var snapshot = new LinkedHashMap<String, List<String>>();
        for (String table : TABLES) {
            snapshot.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return snapshot;
    }

    /** Same-origin browser GET over a loopback socket, so the Host header stays the configured local authority. */
    private Reply get(String path) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nConnection: close\r\n"
                    + "Accept: application/json\r\nSec-Fetch-Site: same-origin\r\n"
                    + (cookie == null ? "" : "Cookie: " + cookie + "\r\n") + "\r\n").getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            String[] parts = response.split("\r\n\r\n", 2);
            String body = parts.length > 1 ? parts[1] : "";
            if (parts[0].toLowerCase().contains("transfer-encoding: chunked")) {
                var decoded = new StringBuilder();
                int offset = 0;
                while (offset < body.length()) {
                    int end = body.indexOf("\r\n", offset);
                    int length = Integer.parseInt(body.substring(offset, end), 16);
                    if (length == 0) {
                        break;
                    }
                    decoded.append(body, end + 2, end + 2 + length);
                    offset = end + 2 + length + 2;
                }
                body = decoded.toString();
            }
            // Chunk sizes count bytes, so the body is decoded as UTF-8 only after the chunks are joined.
            body = new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            return new Reply(Integer.parseInt(parts[0].split(" ", 3)[1]), parts[0], body);
        }
    }

    /** Detached HTTP status, raw headers and body. */
    private record Reply(int status, String headers, String body) { }
}
