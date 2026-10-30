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
import org.rpgengine.combat.EncounterState;
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
 * granted mid-activation, extra speech and a first round without initiative. A long seeded fight checks every
 * candidate intention at every decision against an independent model of the fixture, strikes and the Guard action
 * included (P11.3); {@code StrikeCommandsTest} covers the rest of attacks.
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

    /**
     * The largest supported role values still roll and store initiative: finesse 6 and insight 6 give the largest
     * modifier, 12, whose highest total is the stored bound 32.
     */
    @Test
    void theStrongestSupportedRolesRollInitiative() {
        jdbc.update("update character_state set attributes = attributes || '{\"awareness\": 6}'::jsonb, "
                + "row_version = row_version + 1 where actor_id = ?", player);
        execute(new TypedCommand.Engage("trail-ambush", null));
        var rolled = execute(new TypedCommand.RollInitiative());
        var draws = (List<?>) rolled.receipt().get("randomDraws");
        int dice = (Integer) ((Map<?, ?>) draws.get(0)).get("value") + (Integer) ((Map<?, ?>) draws.get(1)).get("value");
        assertEquals(dice + 12, number("player", "initiative"));
        assertEquals(1, jdbc.queryForObject("select count(*) from encounter_participant where participant_id = 'player' "
                + "and initiative <= 32", Integer.class));
    }

    /** Words PostgreSQL cannot store are an invalid command before anything is written, not a failed operation. */
    @Test
    void unstorableWordsWriteNothing() {
        begin("trail-ambush");
        var before = world();
        var failure = assertThrows(OperationAdmissionFailure.class, () -> executions.execute(owner, adventure, campaign,
                new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, "COMBAT_SPEAK",
                        Map.of("recipientId", "raider", "text", "Stop\u0000now"))));
        assertEquals(OperationAdmissionFailure.Kind.INVALID_COMMAND, failure.kind());
        assertEquals(before, world());
    }

    /**
     * A long seeded fight: at each decision one candidate intention, legal or not, is submitted and its verdict and
     * effect are compared with an independent model of {@code trail-ambush} (edge prices, declared actions and
     * allowances written out here, not read from the rules). Across every activation the verdict is exact, a refusal
     * writes nothing, points never carry over, the speech allowance returns only with a new activation, a sealed
     * passage stays sealed, each completed round adds its eight seconds once, no draw follows the initiative roll, the
     * receipt's moves are the model's and the stored activations follow the initiative order round after round. Each
     * strike's outcome is recalculated from the dice in its receipt; when the raider falls it stops taking activations and
     * stops being a legal recipient or target.
     */
    @Test
    void aLongFightKeepsEveryActivationLegal() {
        begin("trail-ambush");
        var order = EncounterWriter.state(jdbc, campaign).orElseThrow().order().stream()
                .map(EncounterState.Combatant::id).toList();
        var candidates = List.<TypedCommand>of(new TypedCommand.MoveZone("entry"), new TypedCommand.MoveZone("yard"),
                new TypedCommand.MoveZone("escape-route"), new TypedCommand.MoveZone("nowhere"),
                new TypedCommand.CombatSpeak("raider", "Stand down."), new TypedCommand.CombatSpeak("player", "Steady."),
                new TypedCommand.CombatSpeak("ghost", "Who goes there?"), new TypedCommand.Interact("taunt", "raider"),
                new TypedCommand.Interact("taunt", null), new TypedCommand.Interact("taunt", "player"),
                new TypedCommand.Interact("seal-route", null), new TypedCommand.Interact("seal-route", "raider"),
                new TypedCommand.Interact("missing", null), new TypedCommand.EndActivation(),
                new TypedCommand.RollInitiative(), new TypedCommand.Strike("sword", "raider"),
                new TypedCommand.Strike("sword", "raider"), new TypedCommand.Strike("sword", "raider"),
                new TypedCommand.Strike("sword", "raider"), new TypedCommand.Strike("sword", "raider"),
                new TypedCommand.Strike("sword", "raider"), new TypedCommand.MoveZone("yard"),
                new TypedCommand.MoveZone("yard"), new TypedCommand.Strike("sword", "player"),
                new TypedCommand.Strike("sword", "ghost"), new TypedCommand.Strike("axe", "raider"),
                new TypedCommand.Guard(), new TypedCommand.SetReactionPolicy("brace", "off", 0),
                new TypedCommand.SetReactionPolicy("brace", "first", 0), new TypedCommand.SetReactionPolicy("parry", "first", 0));
        var random = new java.util.Random(20261030L);
        var expired = new java.util.ArrayList<Integer>();
        var fight = new FightModel();
        int committed = 0;
        for (int step = 0; step < 720; step++) {
            TypedCommand command = candidates.get(random.nextInt(candidates.size()));
            var refusal = fight.refusal(command);
            if (refusal != null) {
                rejects(refusal, command);
                continue;
            }
            if (command instanceof TypedCommand.EndActivation) {
                expired.add(fight.points);
            }
            fight.apply(command);
            var execution = execute(command);
            committed++;
            if (command instanceof TypedCommand.Strike) {
                fight.struck((List<?>) execution.receipt().get("randomDraws"));
            }
            var stored = EncounterWriter.state(jdbc, campaign).orElseThrow();
            assertEquals(EncounterPhase.AWAITING_ACTION, stored.phase(), command.toString());
            assertTrue(stored.playerDecision(), command.toString());
            assertEquals(fight.round, stored.round(), command.toString());
            assertEquals(fight.points, stored.actionPoints(), command.toString());
            assertEquals(fight.speech, stored.speechUsed(), command.toString());
            assertEquals(fight.zone, stored.player().zone(), command.toString());
            assertEquals(fight.guarding, stored.player().guarding(), command.toString());
            assertEquals(fight.raiderReaction ? 1 : 0, stored.participant("raider").reactions(), command.toString());
            assertEquals(fight.braceOn ? "first" : "off", stored.player().policy("brace").mode().wire(), command.toString());
            assertEquals(fight.raiderHealth, number("raider", "health_current"), command.toString());
            assertEquals(fight.raiderHealth == 0 ? "DEFEATED" : "ACTIVE", participant("raider", "status"), command.toString());
            assertEquals(!fight.sealed, stored.space().stepCost("yard", "escape-route", stored.profile()) > 0, command.toString());
            assertEquals(8L * (fight.round - 1), state("world_time_seconds"), command.toString());
            assertEquals(fight.round, combat(execution).get("round"), command.toString());
            assertEquals(fight.points, combat(execution).get("actionPoints"), command.toString());
            var moves = ((List<?>) ((Map<?, ?>) combat(execution).get("options")).get("moves")).stream()
                    .map(move -> (String) ((Map<?, ?>) move).get("zone")).toList();
            assertEquals(fight.affordableMoves(), moves, command.toString());
        }
        assertTrue(committed >= 30 && fight.round >= 6 && fight.sealed, "the walk must exercise the fight: " + committed
                + " commits, round " + fight.round + ", sealed " + fight.sealed);
        // The dice decide when the raider falls, but not the first strike: the candidates before it are seeded.
        assertTrue(fight.strikes >= 1, "the walk must strike: " + fight.strikes);
        assertEquals(4 + fight.draws, jdbc.queryForObject("select count(*) from operation_random_draw", Integer.class));
        // Round after round the activations follow the stored order, up to the player's current one, without a fallen raider.
        var activations = new java.util.ArrayList<>(order.subList(0, order.indexOf("player") + 1));
        for (boolean raiderUp : fight.raiderUpAtEachEnd) {
            if (raiderUp) {
                activations.add("raider");
            }
            activations.add("player");
        }
        assertEquals(activations, jdbc.queryForList("select payload ->> 'participantId' from campaign_event where "
                + "campaign_id = ? and event_type = 'ACTIVATION_STARTED' order by sequence", String.class, campaign));
        assertEquals(expired, jdbc.queryForList("select (payload ->> 'unusedActionPoints')::integer from campaign_event "
                + "where campaign_id = ? and event_type = 'ACTIVATION_ENDED' and payload ->> 'participantId' = 'player' "
                + "order by sequence", Integer.class, campaign), "the points left at each end of activation expire");
        assertEquals(fight.round - 1, jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? "
                + "and event_type = 'ROUND_ENDED'", Integer.class, campaign));
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

    /**
     * Independent model of the player's decisions in {@code trail-ambush} under the default profile, written from the
     * fixture and docs/11 rather than from {@code TacticalRules}: three points and one utterance per activation; the
     * edges entry-yard (1 point), entry-escape-route (difficult, 2 points) and yard-escape-route (1 point, until
     * sealed); the raider stays in the yard, within speaking range of every zone, and carries no attack. The player's
     * sword costs two points, needs the raider in its own zone and rolls {@code 2d10 + force 2 + rank 2} against Guard 12:
     * a hit deals {@code 1d6 + 2}, a margin of six adds {@code 1d4}, and armor 1 takes one off, out of 14 Health. The Guard
     * action costs one point, once per activation. The raider's default Brace adds 2 to its Guard against the first strike
     * of each round, and only that one; changing the player's own policy costs nothing and moves nothing.
     */
    private static final class FightModel {
        int round = 1;
        int points = 3;
        int speech;
        String zone = "entry";
        boolean sealed;
        boolean guarding;
        boolean raiderReaction = true;
        boolean braceOn = true;
        int raiderHealth = 14;
        int strikes;
        int draws;
        final List<Boolean> raiderUpAtEachEnd = new java.util.ArrayList<>();

        /** Price of a legal edge from the current zone, or null when no legal edge leads there. */
        Integer price(String to) {
            return switch (zone + ">" + to) {
                case "entry>yard", "yard>entry" -> 1;
                case "entry>escape-route", "escape-route>entry" -> 2;
                case "yard>escape-route", "escape-route>yard" -> sealed ? null : 1;
                default -> null;
            };
        }

        /** The expected refusal of an intention, or null when it must commit. */
        OperationAdmissionFailure.Kind refusal(TypedCommand command) {
            return switch (command) {
                case TypedCommand.MoveZone move -> {
                    Integer cost = price(move.zoneId());
                    yield cost == null ? OperationAdmissionFailure.Kind.ZONE_UNAVAILABLE
                            : cost > points ? OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT : null;
                }
                case TypedCommand.CombatSpeak speak -> speech >= 1 ? OperationAdmissionFailure.Kind.SPEECH_LIMIT_REACHED
                        : speak.recipientId().equals("raider") && raiderHealth > 0 ? null
                        : OperationAdmissionFailure.Kind.TARGET_ILLEGAL;
                case TypedCommand.Strike strike -> !strike.attackId().equals("sword")
                        ? OperationAdmissionFailure.Kind.ATTACK_UNAVAILABLE
                        : !strike.targetId().equals("raider") || raiderHealth == 0 || !zone.equals("yard")
                        ? OperationAdmissionFailure.Kind.TARGET_ILLEGAL
                        : points < 2 ? OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT : null;
                case TypedCommand.Guard guard -> guarding ? OperationAdmissionFailure.Kind.GUARD_UNAVAILABLE
                        : points < 1 ? OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT : null;
                case TypedCommand.Interact interact when interact.actionId().equals("taunt") ->
                        !"raider".equals(interact.targetId()) || raiderHealth == 0 ? OperationAdmissionFailure.Kind.TARGET_ILLEGAL
                                : points < 1 ? OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT : null;
                case TypedCommand.Interact interact when interact.actionId().equals("seal-route") ->
                        interact.targetId() != null ? OperationAdmissionFailure.Kind.TARGET_ILLEGAL
                                : !zone.equals("yard") || sealed ? OperationAdmissionFailure.Kind.INTERACTION_UNAVAILABLE
                                : points < 1 ? OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT : null;
                case TypedCommand.Interact interact -> OperationAdmissionFailure.Kind.INTERACTION_UNAVAILABLE;
                case TypedCommand.RollInitiative roll -> OperationAdmissionFailure.Kind.TURN_UNAVAILABLE;
                case TypedCommand.EndActivation end -> null;
                case TypedCommand.SetReactionPolicy policy -> policy.reactionId().equals("brace") ? null
                        : OperationAdmissionFailure.Kind.REACTION_UNAVAILABLE;
                default -> throw new IllegalArgumentException(command.toString());
            };
        }

        /** Apply a legal intention. */
        void apply(TypedCommand command) {
            switch (command) {
                case TypedCommand.MoveZone move -> {
                    points -= price(move.zoneId());
                    zone = move.zoneId();
                }
                case TypedCommand.CombatSpeak speak -> speech++;
                case TypedCommand.Interact interact -> {
                    points--;
                    sealed |= interact.actionId().equals("seal-route");
                }
                case TypedCommand.EndActivation end -> {
                    raiderUpAtEachEnd.add(raiderHealth > 0);
                    round++;
                    points = 3;
                    speech = 0;
                    guarding = false;
                    // The budget refreshes for a participant still standing when the round opens.
                    raiderReaction = raiderHealth > 0 || raiderReaction;
                }
                case TypedCommand.SetReactionPolicy policy -> braceOn = policy.mode().equals("first");
                case TypedCommand.Strike strike -> points -= 2;
                case TypedCommand.Guard guard -> {
                    points--;
                    guarding = true;
                }
                default -> throw new IllegalArgumentException(command.toString());
            }
        }

        /** Recalculate one strike from the dice its receipt lists, in their documented order. */
        void struck(List<?> recorded) {
            strikes++;
            draws += recorded.size();
            int[] dice = recorded.stream().mapToInt(draw -> (Integer) ((Map<?, ?>) draw).get("value")).toArray();
            int total = dice[0] + dice[1] + 2 + 2;
            int defense = raiderReaction ? 14 : 12;
            raiderReaction = false;
            if (total < defense) {
                assertEquals(2, dice.length, "a miss draws no damage");
                return;
            }
            boolean strong = total - defense >= 6;
            assertEquals(strong ? 4 : 3, dice.length, "a hit draws its damage die and, when strong, the strong die");
            int raw = dice[2] + 2 + (strong ? dice[3] : 0);
            raiderHealth = Math.max(0, raiderHealth - Math.max(0, raw - 1));
        }

        /** Zones the player can pay to reach now, in zone ID order. */
        List<String> affordableMoves() {
            return java.util.stream.Stream.of("entry", "escape-route", "yard")
                    .filter(to -> price(to) != null && price(to) <= points).toList();
        }
    }

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
