package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Instant;
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
import org.rpgengine.combat.EncounterPack;
import org.rpgengine.combat.EncounterPhase;
import org.rpgengine.combat.EncounterWriter;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.rules.CanonicalJson;
import org.rpgengine.rules.CombatProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of P11.1 on campaigns created from the sealed encounter fixture: entering an encounter
 * freezes the profile, zones, objectives, opening policy and roster beside one public event and moves the campaign
 * into encounter mode, without drawing a die, spending a resource or passing world time; a declared opening strike
 * is recorded once and not resolved; a retry replays; every refusal changes nothing; the database keeps the frozen
 * facts and the lifecycle; and a customized adventure profile is frozen through the same kernel. Since P11.2 the
 * initiative roll that follows entry is its own operation ({@link TacticalCommandsTest}), so entry itself still draws
 * nothing.
 */
@SpringBootTest
class EncounterCommandsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private UUID player;
    private long revision;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    EncounterCommandsTest() { }

    /** Register the encounter pack and create a campaign through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        start(EncounterPack.files());
    }

    /** Remove disposable rows, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, "
                + "equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, "
                + "memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
                + "narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, "
                + "campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, "
                + "ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * Entry freezes everything and resolves nothing: one public event, encounter mode, the frozen roster with the
     * worked example's derived defenses, the opening strike recorded as pending, and no draw, pool or clock change.
     */
    @Test
    void entryFreezesTheEncounterWithoutResolvingAnything() throws Exception {
        long vigor = vigor();
        var entered = execute(new TypedCommand.Engage("trail-ambush", "raider"));
        assertEquals("ENCOUNTER", jdbc.queryForObject("select mode from campaign where id = ?", String.class, campaign));
        assertEquals(1L, state("state_revision"));
        assertEquals(1L, state("game_turn"));
        assertEquals(0L, state("world_time_seconds"));
        assertEquals(vigor, vigor(), "entry spends nothing and refills nothing");
        assertEquals(0, jdbc.queryForObject("select count(*) from operation_random_draw", Integer.class));
        assertEquals(List.of(), entered.receipt().get("randomDraws"));
        assertEquals(0L, entered.receipt().get("elapsedSeconds"));
        var stored = EncounterWriter.active(jdbc, campaign).orElseThrow();
        assertEquals(EncounterPhase.ENTERING, stored.phase());
        assertEquals(0, stored.round());
        assertEquals("raider", stored.openingTarget());
        assertEquals("PENDING", stored.openingStatus());
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("encounterId", stored.id().toString());
        receipt.put("templateId", "trail-ambush");
        receipt.put("phase", "ENTERING");
        receipt.put("zone", "entry");
        receipt.put("openingPolicy", "initiative");
        receipt.put("openingStrike", "raider");
        assertEquals(receipt, entered.receipt().get("encounter"));
        // One public event lists the observable roster and the declared strike; it cites the stored encounter.
        var events = jdbc.queryForList("select event_type, visibility, payload::text as payload, sequence "
                + "from campaign_event where campaign_id = ? order by sequence", campaign);
        assertEquals(1, events.size());
        assertEquals("ENCOUNTER_STARTED", events.getFirst().get("event_type"));
        assertEquals("PUBLIC", events.getFirst().get("visibility"));
        var payload = JSON.readTree((String) events.getFirst().get("payload"));
        assertEquals("raider", payload.get("openingStrike").asString());
        assertEquals("yard", payload.get("participants").get(0).get("zone").asString());
        assertEquals(events.getFirst().get("sequence"), jdbc.queryForObject("select event_sequence from encounter",
                Long.class));
        // The frozen profile is the pack's default profile and reproduces its hash from the stored text.
        assertEquals(CombatProfile.defaults(), integers(column("profile").get("values")));
        assertEquals(stored.profileHash(), CanonicalJson.sha256(CanonicalJson.write(column("profile"))));
        assertEquals(stored.definitionHash(), CanonicalJson.sha256(CanonicalJson.write(column("definition"))));
        assertEquals(jdbc.queryForObject("select ruleset_hash from content_revision", String.class),
                jdbc.queryForObject("select ruleset_hash from encounter", String.class));
        // The roster: the actor-backed player and the template participant with encounter-local pools.
        var roster = jdbc.queryForList("select participant_id, ordinal, actor_id, side, zone_id, status, health_current, "
                + "focus_current, frozen::text as frozen from encounter_participant order by ordinal");
        assertEquals(List.of("player", "raider"), roster.stream().map(row -> row.get("participant_id")).toList());
        assertEquals(player, roster.get(0).get("actor_id"));
        assertNull(roster.get(0).get("health_current"), "the player's Health stays in actor_resource");
        assertEquals("HOSTILE", roster.get(1).get("side"));
        assertEquals(14, roster.get(1).get("health_current"));
        assertEquals(0, roster.get(1).get("focus_current"));
        var frozenPlayer = JSON.readTree((String) roster.get(0).get("frozen"));
        assertEquals(12, frozenPlayer.get("guard").asInt(), "10 + finesse (awareness 2)");
        assertEquals(12, frozenPlayer.get("resistance").asInt(), "10 + resolve (might 2)");
        assertEquals(4, frozenPlayer.get("initiativeModifier").asInt());
        assertEquals(vigor, frozenPlayer.get("health").asLong());
        assertEquals(List.of("cloak", "shield", "sword"), JSON.convertValue(frozenPlayer.get("equipment"), List.class));
        assertTrue(roster.stream().allMatch(row -> "ACTIVE".equals(row.get("status"))));
    }

    /** A lost response is replayed: the same request returns the same receipt and stores nothing twice. */
    @Test
    void aRetryReplaysTheEntry() {
        var envelope = envelope(new TypedCommand.Engage("trail-ambush", "raider"));
        var first = executions.execute(owner, adventure, campaign, envelope);
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(first.receipt(), again.receipt());
        assertEquals(1, jdbc.queryForObject("select count(*) from encounter", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from encounter_participant", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from campaign_event", Integer.class));
    }

    /**
     * Entry is refused, with one category and no write, for an unknown template, a template of another location, an
     * opening strike the policy forbids or that names an ally or nobody, a defeated player and a missing role attribute.
     */
    @Test
    void entryIsRefusedWhereItDoesNotApply() {
        rejects(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, new TypedCommand.Engage("dragon-lair", null));
        rejects(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, new TypedCommand.Engage("grove-watch", null));
        rejects(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, new TypedCommand.Engage("toll-parley", "smuggler"));
        rejects(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, new TypedCommand.Engage("trail-ambush", "nobody"));
        execute(new TypedCommand.Move("grove"));
        rejects(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, new TypedCommand.Engage("trail-ambush", null));
        rejects(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, new TypedCommand.Engage("grove-watch", "nobody"));
        // An opening strike may only target a hostile, even where the policy allows one.
        rejects(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, new TypedCommand.Engage("grove-watch", "hound"));
        execute(new TypedCommand.Move("trailhead"));
        rejects(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, new TypedCommand.Engage("toll-parley", "keeper"));
        jdbc.update("update actor_resource set current_value = minimum_value, row_version = row_version + 1 "
                + "where actor_id = ? and resource_key = 'vigor'", player);
        rejects(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, new TypedCommand.Engage("trail-ambush", null));
        jdbc.update("update actor_resource set current_value = maximum_value, row_version = row_version + 1 "
                + "where actor_id = ? and resource_key = 'vigor'", player);
        jdbc.update("update character_state set attributes = attributes - 'awareness', row_version = row_version + 1 "
                + "where actor_id = ?", player);
        rejects(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, new TypedCommand.Engage("trail-ambush", null));
        assertEquals(0, jdbc.queryForObject("select count(*) from encounter", Integer.class));
        assertEquals("EXPLORATION", jdbc.queryForObject("select mode from campaign where id = ?", String.class, campaign));
    }

    /** In encounter mode exploration intentions and a second entry are refused until the encounter ends. */
    @Test
    void encounterModeRefusesExplorationAndASecondEntry() {
        execute(new TypedCommand.Engage("toll-parley", null));
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.Move("grove"));
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.Wait(60));
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.Rest("anything"));
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.Engage("trail-ambush", "raider"));
        var stored = EncounterWriter.active(jdbc, campaign).orElseThrow();
        assertEquals("NONE", stored.openingStatus());
        assertNull(stored.openingTarget());
        var keeper = jdbc.queryForMap("select side, zone_id, frozen::text as frozen from encounter_participant "
                + "where participant_id = 'keeper'");
        assertEquals("ALLY", keeper.get("side"));
        assertEquals("warden", JSON.readTree((String) keeper.get("frozen")).get("npc").asString());
    }

    /** The opening policy travels with the template: a surprise opening is recorded for its dedicated slot. */
    @Test
    void theOpeningPolicyIsFrozenWithTheStrike() {
        execute(new TypedCommand.Move("grove"));
        var entered = execute(new TypedCommand.Engage("grove-watch", "wolf"));
        assertEquals("first", ((Map<?, ?>) entered.receipt().get("encounter")).get("openingPolicy"));
        assertEquals("first", jdbc.queryForObject("select opening_policy from encounter", String.class));
        assertEquals("PENDING", EncounterWriter.active(jdbc, campaign).orElseThrow().openingStatus());
        assertEquals("clearing", jdbc.queryForObject("select zone_id from encounter_participant "
                + "where participant_id = 'player'", String.class));
    }

    /** The database keeps one active encounter, its frozen facts and the lifecycle, whatever the writer. */
    @Test
    void theDatabaseGuardsTheFrozenEncounter() {
        execute(new TypedCommand.Engage("trail-ambush", null));
        UUID id = EncounterWriter.active(jdbc, campaign).orElseThrow().id();
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into encounter select gen_random_uuid(), "
                + "campaign_id, template_id, location_id, phase, round, content_revision_id, ruleset_hash, profile, "
                + "profile_hash, definition, definition_hash, opening_policy, opening_target, opening_status, "
                + "started_operation_id, event_sequence, started_revision, updated_revision, 0, created_at from encounter"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set profile = '{}'::jsonb, "
                + "row_version = row_version + 1 where id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set phase = 'SETTLED', "
                + "row_version = row_version + 1 where id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set opening_status = 'RESOLVED', "
                + "row_version = row_version + 1 where id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter_participant set frozen = '{}'::jsonb, "
                + "row_version = row_version + 1 where participant_id = 'raider'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from encounter_participant"));
        // The next arrow opens the first round, but only once every participant has an initiative rank (P11.2, V23).
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set phase = 'ROUND_OPEN', round = 1, "
                + "row_version = row_version + 1 where id = ?", id));
        jdbc.update("update encounter_participant set initiative = 10, initiative_rank = ordinal, "
                + "row_version = row_version + 1");
        assertEquals(1, jdbc.update("update encounter set phase = 'ROUND_OPEN', round = 1, row_version = row_version + 1 "
                + "where id = ?", id));
    }

    /**
     * A customized adventure profile freezes its own values through the same kernel and code path: only the frozen
     * parameter values and their hash differ from the default adventure.
     */
    @Test
    void aCustomizedProfileIsFrozenThroughTheSameKernel() throws Exception {
        execute(new TypedCommand.Engage("trail-ambush", null));
        var standard = column("profile");
        clear();
        start(EncounterPack.tuned());
        execute(new TypedCommand.Engage("trail-ambush", "raider"));
        var tuned = column("profile");
        assertEquals(standard.get("kernel"), tuned.get("kernel"));
        assertEquals(CombatProfile.KERNEL, tuned.get("kernel"));
        var values = integers(tuned.get("values"));
        assertEquals(4, values.get("actionPoints"));
        assertEquals(6, values.get("roundSeconds"));
        assertEquals(3, values.get("strikeCost"));
        assertEquals(12, values.get("roundCap"));
        assertNotEquals(integers(standard.get("values")), values);
        assertEquals(13, JSON.readTree(jdbc.queryForObject("select frozen::text from encounter_participant "
                + "where participant_id = 'player'", String.class)).get("guard").asInt(), "guardBase 11 + finesse 2");
    }

    /** Register a fixture variant, then create a campaign at the trailhead through production code. */
    private void start(Map<String, byte[]> files) throws Exception {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Encounters", "encounters", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(files)))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        revision = 0;
    }

    private CommandExecutionService.Execution execute(TypedCommand command) {
        var execution = executions.execute(owner, adventure, campaign, envelope(command));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
        revision = execution.operation().committedRevision();
        return execution;
    }

    private OperationAdmissionService.Command envelope(TypedCommand command) {
        return new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, command.type(),
                command.payload());
    }

    /** Submit a command that must be refused at admission with the given category and change nothing. */
    private void rejects(OperationAdmissionFailure.Kind kind, TypedCommand command) {
        var before = world();
        var failure = assertThrows(OperationAdmissionFailure.class, () -> executions.execute(owner, adventure, campaign,
                envelope(command)), command.toString());
        assertEquals(kind, failure.kind(), command.toString());
        assertEquals(before, world(), "a refusal writes nothing: " + command);
    }

    /** One stored jsonb column of the encounter, as plain Java values. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> column(String name) {
        return JSON.readValue(jdbc.queryForObject("select " + name + "::text from encounter", String.class), Map.class);
    }

    /** Integer view of a JSON object read as a map. */
    private static Map<String, Integer> integers(Object values) {
        var result = new java.util.TreeMap<String, Integer>();
        ((Map<?, ?>) values).forEach((key, value) -> result.put((String) key, ((Number) value).intValue()));
        return result;
    }

    private long state(String column) {
        return jdbc.queryForObject("select " + column + " from campaign where id = ?", Long.class, campaign);
    }

    private long vigor() {
        return jdbc.queryForObject("select current_value from actor_resource where actor_id = ? and resource_key = 'vigor'",
                Long.class, player);
    }

    /** Every table a command can touch, for exact before/after comparison. */
    private Map<String, List<String>> world() {
        var tables = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("campaign", "actor", "actor_resource", "campaign_event", "operation_journal",
                "operation_random_draw", "canonical_outbox", "encounter", "encounter_participant")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }
}
