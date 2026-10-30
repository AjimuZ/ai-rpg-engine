package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of P11.2 on campaigns created from the sealed encounter fixture: initiative is rolled
 * once with recorded draws, the schedule stops only at the player's own decision, movement, speech and declared
 * actions are legal only there and cost exactly their declared action points, ending the activation closes a round
 * once with its world time, every refusal writes nothing, retries replay, and the database refuses a re-roll, points
 * granted mid-activation, extra speech and a first round without initiative.
 */
@SpringBootTest
class TacticalCommandsTest {
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
    TacticalCommandsTest() { }

    /** Register the encounter pack and create a campaign at the trailhead through production code. */
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
     * The roll draws two d10 per participant in roster order, stores totals and ranks matching the recorded dice and
     * the documented tie-breaks, opens round one and stops at the player's activation with three points, whoever
     * acts first; no world time passes and a retry replays the same draws.
     */
    @Test
    void initiativeIsRolledOnceAndStopsAtThePlayersDecision() {
        execute(new TypedCommand.Engage("trail-ambush", null));
        var envelope = envelope(new TypedCommand.RollInitiative());
        var rolled = executions.execute(owner, adventure, campaign, envelope);
        assertEquals(OperationStatus.COMMITTED, rolled.operation().status());
        revision = rolled.operation().committedRevision();
        var draws = (List<?>) rolled.receipt().get("randomDraws");
        assertEquals(4, draws.size());
        int[] dice = draws.stream().mapToInt(draw -> (Integer) ((Map<?, ?>) draw).get("value")).toArray();
        assertTrue(draws.stream().allMatch(draw -> Integer.valueOf(10).equals(((Map<?, ?>) draw).get("sides"))));
        // Player modifier 4 (awareness twice), raider 2; on a tie the player's finesse 2 beats the raider's 1.
        int playerTotal = dice[0] + dice[1] + 4;
        int raiderTotal = dice[2] + dice[3] + 2;
        boolean playerFirst = playerTotal >= raiderTotal;
        assertEquals(playerTotal, number("player", "initiative"));
        assertEquals(raiderTotal, number("raider", "initiative"));
        assertEquals(playerFirst ? 0 : 1, number("player", "initiative_rank"));
        var combat = combat(rolled);
        assertEquals("AWAITING_ACTION", combat.get("phase"));
        assertEquals(1, combat.get("round"));
        assertEquals("player", combat.get("acting"));
        assertEquals(3, combat.get("actionPoints"));
        assertEquals(0L, state("world_time_seconds"));
        assertEquals(List.of("INITIATIVE_ROLLED", "ROUND_STARTED"), types().subList(1, 3));
        assertEquals(playerFirst ? 3 : 5, types().size() - 1, "the raider's activation passes before a slower player");
        var stored = EncounterWriter.state(jdbc, campaign).orElseThrow();
        assertEquals(EncounterPhase.AWAITING_ACTION, stored.phase());
        assertTrue(stored.playerDecision());
        assertTrue(stored.participants().stream().allMatch(p -> p.reactions() == 1));
        // The lost response is replayed: same receipt, no new draw, nothing re-rolled.
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(rolled.receipt(), again.receipt());
        assertEquals(4, jdbc.queryForObject("select count(*) from operation_random_draw", Integer.class));
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.RollInitiative());
    }

    /**
     * Moves cost one point per normal edge and two across difficult terrain, keep the activation open at any point
     * total, and are refused for an unknown, current or unaffordable zone, without spending anything.
     */
    @Test
    void movementSpendsItsDeclaredPoints() {
        begin("trail-ambush");
        var moved = execute(new TypedCommand.MoveZone("yard"));
        assertEquals(2, combat(moved).get("actionPoints"));
        assertEquals("yard", participant("player", "zone_id"));
        assertEquals(List.of(), moved.receipt().get("randomDraws"));
        assertEquals(0L, moved.receipt().get("elapsedSeconds"));
        rejects(OperationAdmissionFailure.Kind.ZONE_UNAVAILABLE, new TypedCommand.MoveZone("yard"));
        rejects(OperationAdmissionFailure.Kind.ZONE_UNAVAILABLE, new TypedCommand.MoveZone("cellar"));
        execute(new TypedCommand.MoveZone("escape-route"));
        rejects(OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT, new TypedCommand.MoveZone("entry"));
        var last = execute(new TypedCommand.MoveZone("yard"));
        var combat = combat(last);
        assertEquals(0, combat.get("actionPoints"));
        assertEquals("AWAITING_ACTION", combat.get("phase"), "only the explicit end passes control");
        assertEquals(List.of(), ((Map<?, ?>) combat.get("options")).get("moves"));
        var event = JSON.readTree(jdbc.queryForObject("select payload::text from campaign_event where campaign_id = ? "
                + "order by sequence desc limit 1", String.class, campaign));
        assertEquals("escape-route", event.get("from").asString());
        assertEquals(player.toString(), event.get("actorId").asString());
    }

    /**
     * One speech-only utterance per activation costs nothing and moves neither the clock nor the draws; ending the
     * activation closes the round once with eight seconds, refreshes the allowance and returns to the player.
     */
    @Test
    void speechIsBoundedAndTheRoundClosesOnce() {
        begin("trail-ambush");
        long turn = state("game_turn");
        var spoken = execute(new TypedCommand.CombatSpeak("raider", "Why are you attacking us?"));
        assertEquals(3, combat(spoken).get("actionPoints"));
        assertEquals(0L, state("world_time_seconds"));
        assertEquals(turn + 1, state("game_turn"));
        assertEquals(List.of(), spoken.receipt().get("randomDraws"));
        var words = JSON.readTree(jdbc.queryForObject("select payload::text from campaign_event where campaign_id = ? "
                + "and event_type = 'COMBAT_SPEECH'", String.class, campaign));
        assertEquals("Why are you attacking us?", words.get("text").asString());
        assertEquals("raider", words.get("recipientId").asString());
        rejects(OperationAdmissionFailure.Kind.SPEECH_LIMIT_REACHED, new TypedCommand.CombatSpeak("raider", "Again!"));
        var ended = execute(new TypedCommand.EndActivation());
        var combat = combat(ended);
        assertEquals(2, combat.get("round"));
        assertEquals("player", combat.get("acting"));
        assertEquals(3, combat.get("actionPoints"));
        assertEquals(8L, ended.receipt().get("elapsedSeconds"));
        assertEquals(8L, state("world_time_seconds"));
        assertEquals(1, jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? "
                + "and event_type = 'ROUND_ENDED'", Integer.class, campaign));
        assertEquals(1, ((Map<?, ?>) ((Map<?, ?>) combat.get("options")).get("speech")).get("remaining"));
        execute(new TypedCommand.CombatSpeak("raider", "Last warning."));
    }

    /**
     * The initiative roll and the end of an activation may close a round, so they need room for one round on the
     * bounded clock; speech, which takes no time, still works at the end of the clock.
     */
    @Test
    void roundTimeMustFitTheClock() {
        begin("trail-ambush");
        jdbc.execute("alter table campaign disable trigger campaign_world_time_step");
        try {
            jdbc.update("update campaign set world_time_seconds = ?, row_version = row_version + 1 where id = ?",
                    org.rpgengine.rules.WorldClock.END_SECONDS - 7, campaign);
        } finally {
            jdbc.execute("alter table campaign enable trigger campaign_world_time_step");
        }
        rejects(OperationAdmissionFailure.Kind.TIME_ILLEGAL, new TypedCommand.EndActivation());
        execute(new TypedCommand.CombatSpeak("raider", "Time is short."));
    }

    /** Speech reaches only addressable participants; an unknown recipient and the player are refused. */
    @Test
    void speechNeedsAnAddressableRecipient() {
        begin("trail-ambush");
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.CombatSpeak("player", "Me."));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.CombatSpeak("ghost", "Hello?"));
        jdbc.update("update encounter_participant set status = 'DEFEATED', row_version = row_version + 1 "
                + "where participant_id = 'raider'");
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.CombatSpeak("raider", "Hello?"));
    }

    /**
     * Declared social actions need an admitted recipient and cost their declared points; a scene action opens its
     * passage once and movement follows; undeclared, repeated and unaffordable actions are refused.
     */
    @Test
    void declaredActionsAreTheOnlyWayWordsChangeTheFight() {
        begin("toll-parley");
        rejects(OperationAdmissionFailure.Kind.ZONE_UNAVAILABLE, new TypedCommand.MoveZone("booth"));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Interact("demand-passage", "keeper"));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Interact("demand-passage", null));
        rejects(OperationAdmissionFailure.Kind.INTERACTION_UNAVAILABLE, new TypedCommand.Interact("bribe", "smuggler"));
        var demanded = execute(new TypedCommand.Interact("demand-passage", "smuggler"));
        assertEquals(2, combat(demanded).get("actionPoints"));
        assertEquals(List.of(), demanded.receipt().get("randomDraws"));
        var attempt = JSON.readTree(jdbc.queryForObject("select payload::text from campaign_event where campaign_id = ? "
                + "and event_type = 'INTERACTION_ATTEMPTED'", String.class, campaign));
        assertEquals("negotiate", attempt.get("intent").asString());
        assertEquals("smuggler", attempt.get("targetId").asString());
        assertEquals("ACTIVE", jdbc.queryForObject("select status from encounter_participant "
                + "where participant_id = 'smuggler'", String.class), "an attempt establishes no outcome");
        var unbarred = execute(new TypedCommand.Interact("unbar-gate", null));
        assertEquals(1, combat(unbarred).get("actionPoints"));
        assertEquals("[{\"to\": \"gate\", \"from\": \"booth\", \"blocked\": false}]",
                jdbc.queryForObject("select passages::text from encounter", String.class));
        rejects(OperationAdmissionFailure.Kind.INTERACTION_UNAVAILABLE, new TypedCommand.Interact("unbar-gate", null));
        rejects(OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT, new TypedCommand.Interact("ask-keeper", "keeper"));
        execute(new TypedCommand.MoveZone("booth"));
        assertEquals("booth", participant("player", "zone_id"));
    }

    /**
     * Tactical intentions exist only in encounter mode and only at the player's decision: in exploration they are
     * MODE_ILLEGAL, before initiative and while the raider acts they are TURN_UNAVAILABLE, and exploration stays
     * refused during the fight.
     */
    @Test
    void nothingIsAcceptedOutOfTurnOrOutOfMode() {
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.RollInitiative());
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.EndActivation());
        execute(new TypedCommand.Engage("trail-ambush", null));
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.MoveZone("yard"));
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.EndActivation());
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.CombatSpeak("raider", "Wait."));
        execute(new TypedCommand.RollInitiative());
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.Move("grove"));
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.Wait(60));
        // Freeze a state where the raider is acting (another writer stopped there): the player cannot act or queue.
        int raiderRank = number("raider", "initiative_rank");
        jdbc.update("update encounter set actor_cursor = ?, row_version = row_version + 1", raiderRank);
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.EndActivation());
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.CombatSpeak("raider", "Wait."));
    }

    /** A customized profile runs through the same scheduler: four points per activation, six-second rounds. */
    @Test
    void aCustomizedProfileDrivesTheSameScheduler() throws Exception {
        clear();
        start(EncounterPack.tuned());
        begin("trail-ambush");
        assertEquals(4, EncounterWriter.state(jdbc, campaign).orElseThrow().actionPoints());
        var ended = execute(new TypedCommand.EndActivation());
        assertEquals(6L, ended.receipt().get("elapsedSeconds"));
        assertEquals(4, combat(ended).get("actionPoints"));
    }

    /**
     * The database keeps the scheduling guarantees whatever the writer: no re-roll, no points granted outside an
     * activation start, no points beyond the frozen allowance, no extra speech, no first round without initiative.
     */
    @Test
    void theDatabaseGuardsTheSchedule() {
        execute(new TypedCommand.Engage("trail-ambush", null));
        UUID id = EncounterWriter.active(jdbc, campaign).orElseThrow().id();
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set phase = 'ROUND_OPEN', round = 1, "
                + "row_version = row_version + 1 where id = ?", id), "initiative comes first");
        execute(new TypedCommand.RollInitiative());
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter_participant set initiative = 2, "
                + "row_version = row_version + 1 where participant_id = 'player'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter_participant set initiative_rank = 5, "
                + "row_version = row_version + 1 where participant_id = 'player'"));
        jdbc.update("update encounter set action_points = 1, row_version = row_version + 1 where id = ?", id);
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set action_points = 3, "
                + "row_version = row_version + 1 where id = ?", id), "no refund in the middle of an activation");
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set phase = 'RESOLVING', "
                + "action_points = null, row_version = row_version + 1 where id = ?", id), "a cursor needs its points");
        jdbc.update("update encounter set speech_used = 1, row_version = row_version + 1 where id = ?", id);
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set speech_used = 2, "
                + "row_version = row_version + 1 where id = ?", id), "one utterance per activation by default");
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set speech_used = 0, "
                + "row_version = row_version + 1 where id = ?", id), "the allowance resets only at a new activation");
        jdbc.update("update encounter set phase = 'ACTOR_END', action_points = 0, row_version = row_version + 1 "
                + "where id = ?", id);
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set phase = 'ACTOR_START', "
                + "action_points = 4, row_version = row_version + 1 where id = ?", id), "beyond the frozen allowance");
        assertEquals(1, jdbc.update("update encounter set phase = 'ACTOR_START', action_points = 3, speech_used = 0, "
                + "row_version = row_version + 1 where id = ?", id), "an activation start grants the allowance");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Enter a template at the player's location (moving to the grove for grove-watch) and roll initiative. */
    private void begin(String template) {
        execute(new TypedCommand.Engage(template, null));
        execute(new TypedCommand.RollInitiative());
        assertTrue(EncounterWriter.state(jdbc, campaign).orElseThrow().playerDecision());
    }

    /** Register a fixture variant, then create a campaign at the trailhead through production code. */
    private void start(Map<String, byte[]> files) throws Exception {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Tactics", "tactics", SupportedLocale.EN, Instant.now());
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

    /** The receipt's tactical summary. */
    private static Map<?, ?> combat(CommandExecutionService.Execution execution) {
        return (Map<?, ?>) execution.receipt().get("combat");
    }

    /** One text column of one participant row. */
    private String participant(String id, String column) {
        return jdbc.queryForObject("select " + column + " from encounter_participant where participant_id = ?",
                String.class, id);
    }

    /** One integer column of one participant row. */
    private int number(String id, String column) {
        return jdbc.queryForObject("select " + column + " from encounter_participant where participant_id = ?",
                Integer.class, id);
    }

    /** Event types of the campaign in order. */
    private List<String> types() {
        return jdbc.queryForList("select event_type from campaign_event where campaign_id = ? order by sequence",
                String.class, campaign);
    }

    private long state(String column) {
        return jdbc.queryForObject("select " + column + " from campaign where id = ?", Long.class, campaign);
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
