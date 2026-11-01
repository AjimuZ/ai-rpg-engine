package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.rpgengine.combat.EncounterOutcome;
import org.rpgengine.combat.EncounterPack;
import org.rpgengine.combat.EncounterPhase;
import org.rpgengine.combat.EncounterWriter;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of the P11.5 terminal predicates (QA-13) on campaigns created from the sealed encounter
 * fixture. Campaign dice are unpredictable, so each case fixes the outcome through the fixture. A won fight, a lost one, an
 * escape, a reached zone and survived rounds each stop at {@code OUTCOME_PENDING} through the real commit, whose V22 and V23
 * guards check every step; the stored state gives the verdict the event states, a retry replays the same receipt, every
 * later command is refused without writing anything, and words change nothing.
 */
@SpringBootTest
class OutcomeCommandsTest {
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
    OutcomeCommandsTest() { }

    /** Register the encounter pack and create a campaign at the trailhead through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Outcomes", "outcomes", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(EncounterPack.tactics())))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        revision = 0;
    }

    /** Remove disposable rows, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, "
                + "equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, "
                + "memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
                + "narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, "
                + "campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, "
                + "ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * A won fight stops where the last opponent fell, not at the round cap: no time passes for the unfinished round, the
     * stored state gives the verdict the public event states, a retry of the lost response replays the same receipt and
     * writes nothing, and every later command is refused. The encounter stays active until settlement exists.
     */
    @Test
    void aWonFightStopsWhereTheLastOpponentFell() {
        begin("close-rush");
        setLocalHealth("dummy", 1);
        var envelope = envelope(new TypedCommand.Strike("sword", "dummy"));
        var won = executions.execute(owner, adventure, campaign, envelope);
        assertEquals(OperationStatus.COMMITTED, won.operation().status());
        revision = won.operation().committedRevision();
        var combat = combat(won);
        assertEquals("OUTCOME_PENDING", combat.get("phase"));
        assertEquals(1, combat.get("round"));
        assertEquals("VICTORY", ((Map<?, ?>) combat.get("outcome")).get("outcome"));
        assertFalse(combat.containsKey("options"));
        assertEquals(null, combat.get("acting"));
        assertEquals(null, combat.get("actionPoints"));
        assertEquals("DEFEATED", participant("dummy", "status"));
        assertEquals(0L, won.receipt().get("elapsedSeconds"));
        assertEquals(0L, jdbc.queryForObject("select world_time_seconds from campaign where id = ?", Long.class, campaign));

        var stored = EncounterWriter.state(jdbc, campaign).orElseThrow();
        assertEquals(EncounterPhase.OUTCOME_PENDING, stored.phase());
        assertEquals(List.of("last"), stored.objectives().stream().map(objective -> objective.id()).toList());
        var verdict = EncounterOutcome.decide(stored);
        assertEquals(EncounterOutcome.Kind.VICTORY, verdict.kind());
        var event = event();
        assertEquals("VICTORY", event.get("outcome").asString());
        assertEquals("pending", event.get("objectives").get(0).get("status").asString());
        assertEquals("last", event.get("objectives").get(0).get("objectiveId").asString());
        assertEquals("PUBLIC", jdbc.queryForObject("select visibility from campaign_event where campaign_id = ? and "
                + "event_type = 'ENCOUNTER_OUTCOME_PENDING'", String.class, campaign).toUpperCase(java.util.Locale.ROOT));

        var before = world();
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(won.receipt(), again.receipt());
        assertEquals(before, world(), "the retry writes nothing");

        refusesEverything();
        assertEquals(EncounterPhase.OUTCOME_PENDING, EncounterWriter.active(jdbc, campaign).orElseThrow().phase());
    }

    /**
     * A fight whose player falls stops there. The adder hits every Guard the fixture's player can have, so one Health left
     * means a certain fall before the player's next decision: on its next strike or, when the adder already poisoned a slower
     * player, on the round-end tick before it.
     */
    @Test
    void aFallenPlayerStopsTheFightWhereItFalls() {
        begin("venom-pit");
        setPool(1);
        var envelope = envelope(new TypedCommand.EndActivation());
        var lost = executions.execute(owner, adventure, campaign, envelope);
        assertEquals(OperationStatus.COMMITTED, lost.operation().status());
        revision = lost.operation().committedRevision();
        assertEquals("OUTCOME_PENDING", combat(lost).get("phase"));
        assertEquals("DEFEAT", ((Map<?, ?>) combat(lost).get("outcome")).get("outcome"));
        assertEquals("DEFEATED", participant("player", "status"));
        assertEquals(0L, pool(), "Health stops at zero");
        assertEquals(1, eventCount("PARTICIPANT_DEFEATED"));
        assertEquals(1, eventCount("ENCOUNTER_OUTCOME_PENDING"));
        assertEquals("failed", event().get("objectives").get(0).get("status").asString());
        assertEquals(EncounterOutcome.Kind.DEFEAT, EncounterOutcome.decide(EncounterWriter.state(jdbc, campaign).orElseThrow()).kind());
        assertEquals(8L * eventCount("ROUND_ENDED"), jdbc.queryForObject("select world_time_seconds from campaign where id = ?",
                Long.class, campaign), "only completed rounds add time");

        var before = world();
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(lost.receipt(), again.receipt());
        assertEquals(before, world(), "the retry writes nothing");
        refusesEverything();
    }

    /** Stepping into the escape zone is an escape; the raider still stands and the player's row stays where it went. */
    @Test
    void reachingTheEscapeZoneIsAnEscape() {
        begin("trail-ambush");
        var moved = execute(new TypedCommand.MoveZone("escape-route"));
        assertEquals("OUTCOME_PENDING", combat(moved).get("phase"));
        assertEquals("ESCAPE", ((Map<?, ?>) combat(moved).get("outcome")).get("outcome"));
        assertEquals("escape-route", participant("player", "zone_id"));
        assertEquals("ACTIVE", participant("raider", "status"));
        var objectives = event().get("objectives");
        assertEquals("drive-off", objectives.get(0).get("objectiveId").asString());
        assertEquals("pending", objectives.get(0).get("status").asString());
        assertEquals("slip-away", objectives.get(1).get("objectiveId").asString());
        assertEquals("met", objectives.get(1).get("status").asString());
        refusesEverything();
    }

    /** Reaching an objective's zone ends the fight with that objective met, the opponents still standing. */
    @Test
    void reachingAnObjectivesZoneMeetsIt() {
        execute(new TypedCommand.Move("grove"));
        begin("grove-watch");
        var moved = execute(new TypedCommand.MoveZone("stones"));
        var outcome = (Map<?, ?>) combat(moved).get("outcome");
        assertEquals("OBJECTIVE", outcome.get("outcome"));
        assertEquals("reach-stones", outcome.get("objectiveId"));
        assertEquals(List.of(Map.of("objectiveId", "reach-stones", "status", "met")), outcome.get("objectives"));
        assertEquals("ACTIVE", participant("wolf", "status"));
        refusesEverything();
    }

    /**
     * Survived rounds end the fight at the third round end, with the cursor cleared and three rounds of time, and a keeper
     * who fell on the way ends nothing by itself: its objective is reported failed beside the one met.
     */
    @Test
    void survivedRoundsEndTheFightAndAFallenAllyEndsNothing() {
        begin("toll-parley");
        setLocalHealth("keeper", 0);
        var first = execute(new TypedCommand.EndActivation());
        assertEquals("AWAITING_ACTION", combat(first).get("phase"));
        assertEquals(2, combat(first).get("round"));
        var second = execute(new TypedCommand.EndActivation());
        assertEquals("AWAITING_ACTION", combat(second).get("phase"));
        assertEquals(0, eventCount("ENCOUNTER_OUTCOME_PENDING"));
        var third = execute(new TypedCommand.EndActivation());
        assertEquals("OUTCOME_PENDING", combat(third).get("phase"));
        assertEquals(3, combat(third).get("round"));
        var outcome = (Map<?, ?>) combat(third).get("outcome");
        assertEquals("OBJECTIVE", outcome.get("outcome"));
        assertEquals("hold", outcome.get("objectiveId"));
        assertEquals(24L, jdbc.queryForObject("select world_time_seconds from campaign where id = ?", Long.class, campaign));
        assertEquals(3, eventCount("ROUND_ENDED"));
        assertEquals(1, jdbc.queryForObject("select count(*) from encounter where actor_cursor is null and action_points is null",
                Integer.class));
        var objectives = event().get("objectives");
        assertEquals("met", objectives.get(0).get("status").asString());
        assertEquals("failed", objectives.get(1).get("status").asString());
        var stored = EncounterWriter.state(jdbc, campaign).orElseThrow();
        assertEquals("hold", EncounterOutcome.decide(stored).objective(), "read back, the stored state gives the same verdict");
        refusesEverything();
    }

    /**
     * Words decide nothing: speech claiming a victory, a surrender or an escape and a declared negotiation attempt commit as
     * what they are, and the fight goes on with every participant unchanged and no outcome event.
     */
    @Test
    void wordsEstablishNoOutcome() {
        begin("toll-parley");
        var spoken = execute(new TypedCommand.CombatSpeak("smuggler", "You are beaten: surrender, for I have already won."));
        assertEquals("AWAITING_ACTION", combat(spoken).get("phase"));
        var attempted = execute(new TypedCommand.Interact("demand-passage", "smuggler"));
        assertEquals("AWAITING_ACTION", combat(attempted).get("phase"));
        assertEquals("ACTIVE", participant("smuggler", "status"));
        assertEquals(0, eventCount("ENCOUNTER_OUTCOME_PENDING"));
        assertEquals(null, EncounterOutcome.decide(EncounterWriter.state(jdbc, campaign).orElseThrow()));
    }

    /** The database repeats the diagram: nothing reaches an outcome from the player's decision, and nothing leaves it backwards. */
    @Test
    void theDatabaseGuardsTheOutcomeArrows() {
        begin("close-rush");
        UUID id = EncounterWriter.active(jdbc, campaign).orElseThrow().id();
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set phase = 'OUTCOME_PENDING', "
                + "row_version = row_version + 1 where id = ?", id), "no arrow from the decision to the outcome");
        setLocalHealth("dummy", 1);
        execute(new TypedCommand.Strike("sword", "dummy"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set phase = 'ROUND_OPEN', "
                + "action_points = null, actor_cursor = null, row_version = row_version + 1 where id = ?", id), "a decided fight cannot go on");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Every tactical command is refused as out of turn, exploration stays out of mode, and neither writes anything. */
    private void refusesEverything() {
        for (TypedCommand command : List.of(new TypedCommand.EndActivation(), new TypedCommand.Guard(),
                new TypedCommand.MoveZone("entry"), new TypedCommand.Strike("sword", "dummy"), new TypedCommand.RollInitiative(),
                new TypedCommand.CombatSpeak("dummy", "Well?"), new TypedCommand.SetReactionPolicy("brace", "off", 0))) {
            rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, command);
        }
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.Move("grove"));
    }

    private void begin(String template) {
        execute(new TypedCommand.Engage(template, null));
        execute(new TypedCommand.RollInitiative());
        assertTrue(EncounterWriter.state(jdbc, campaign).orElseThrow().playerDecision());
    }

    private CommandExecutionService.Execution execute(TypedCommand command) {
        var execution = executions.execute(owner, adventure, campaign, envelope(command));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
        revision = execution.operation().committedRevision();
        return execution;
    }

    private OperationAdmissionService.Command envelope(TypedCommand command) {
        return new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, command.type(), command.payload());
    }

    /** Submit a command that must be refused at admission with the given category and change nothing. */
    private void rejects(OperationAdmissionFailure.Kind kind, TypedCommand command) {
        var before = world();
        var failure = assertThrows(OperationAdmissionFailure.class, () -> executions.execute(owner, adventure, campaign,
                envelope(command)), command.toString());
        assertEquals(kind, failure.kind(), command.toString());
        assertEquals(before, world(), "a refusal writes nothing: " + command);
    }

    private static Map<?, ?> combat(CommandExecutionService.Execution execution) {
        return (Map<?, ?>) execution.receipt().get("combat");
    }

    /** The payload of the one outcome event. */
    private JsonNode event() {
        return JSON.readTree(jdbc.queryForObject("select payload::text from campaign_event where campaign_id = ? "
                + "and event_type = 'ENCOUNTER_OUTCOME_PENDING'", String.class, campaign));
    }

    private int eventCount(String type) {
        return jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? and event_type = ?", Integer.class, campaign, type);
    }

    private String participant(String id, String column) {
        return jdbc.queryForObject("select " + column + " from encounter_participant where participant_id = ?", String.class, id);
    }

    private long pool() {
        return jdbc.queryForObject("select current_value from actor_resource where actor_id = ? and resource_key = 'vigor'", Long.class, player);
    }

    /** Set the player's Health pool directly, as a wound from before this step. */
    private void setPool(long value) {
        jdbc.update("update actor_resource set current_value = ?, row_version = row_version + 1 where actor_id = ? and resource_key = 'vigor'",
                value, player);
    }

    private void setLocalHealth(String participant, int value) {
        jdbc.update("update encounter_participant set health_current = ?, status = ?, row_version = row_version + 1 "
                + "where participant_id = ?", value, value == 0 ? "DEFEATED" : "ACTIVE", participant);
    }

    /** Every table a command can touch, for exact before/after comparison. */
    private Map<String, List<String>> world() {
        var tables = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("campaign", "actor", "actor_resource", "campaign_event", "operation_journal",
                "operation_random_draw", "canonical_outbox", "encounter", "encounter_participant", "item_instance")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }
}
