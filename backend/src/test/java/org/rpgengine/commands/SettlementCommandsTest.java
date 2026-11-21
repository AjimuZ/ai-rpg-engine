package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
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
import org.rpgengine.combat.EncounterPack;
import org.rpgengine.combat.EncounterWriter;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.rules.WorldClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of settlement and the return to narrative (P11.5, docs/11 section 14, QA-13, ADR 0036) on
 * campaigns created from the sealed encounter fixture. Every terminal path settles through the real commit, whose V22 to V31
 * guards check both steps: the campaign leaves encounter mode once, the encounter stops at {@code NARRATIVE_RESUME}, a lost
 * response of the settlement or of the negotiated ending before it replays its own receipt and writes nothing, a second
 * settlement cannot exist, and the campaign accepts exploration commands and a new fight afterwards.
 */
@SpringBootTest
class SettlementCommandsTest {
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
    SettlementCommandsTest() { }

    /** Register the encounter pack and create a campaign at the trailhead through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Settlements", "settlements", SupportedLocale.EN, Instant.now());
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
                + "memory_summary_job, relationship_change, faction_clock_change, learned_recipe,actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
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
     * A fight won inside a round settles once: the round's eight seconds are added, the encounter passes SETTLED to stop at
     * NARRATIVE_RESUME with no activation, the campaign is back in exploration with no pending decision, nothing is refilled,
     * one public event of the player's decision states the verdict and narration is owed for it. A retry of the lost response
     * replays the same receipt and writes nothing, a fresh request cannot settle again, the database refuses every arrow back,
     * and the campaign then waits and enters a new fight.
     */
    @Test
    void aWonFightSettlesOnceAndTheStoryResumes() {
        begin("close-rush");
        UUID encounter = EncounterWriter.active(jdbc, campaign).orElseThrow().id();
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.SettleEncounter(encounter));
        setLocalHealth("dummy", 1);
        execute(new TypedCommand.Strike("sword", "dummy"));
        assertEquals("OUTCOME_PENDING", phase(encounter));
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.SettleEncounter(UUID.randomUUID()));
        long health = pool();
        long clock = clock();
        long version = jdbc.queryForObject("select row_version from encounter where id = ?", Long.class, encounter);

        var envelope = envelope(new TypedCommand.SettleEncounter(encounter));
        var settled = executions.execute(owner, adventure, campaign, envelope);
        assertEquals(OperationStatus.COMMITTED, settled.operation().status());
        revision = settled.operation().committedRevision();
        assertEquals("SETTLE_ENCOUNTER", settled.receipt().get("commandType"));
        var combat = combat(settled);
        assertEquals("NARRATIVE_RESUME", combat.get("phase"));
        assertEquals("VICTORY", ((Map<?, ?>) combat.get("outcome")).get("outcome"));
        assertEquals(Map.of("seconds", 8L, "effectsEnded", List.of()), combat.get("settlement"));
        assertFalse(combat.containsKey("options"));
        assertNull(combat.get("acting"));
        assertEquals(8L, settled.receipt().get("elapsedSeconds"));
        assertEquals(clock + 8, clock(), "the unfinished round runs to its boundary once");
        assertEquals(health, pool(), "nothing is refilled");
        assertEquals("EXPLORATION", jdbc.queryForObject("select mode from campaign where id = ?", String.class, campaign));
        assertNull(jdbc.queryForObject("select pending_decision::text from campaign where id = ?", String.class, campaign));
        assertEquals("NARRATIVE_RESUME", phase(encounter));
        assertEquals(version + 2, jdbc.queryForObject("select row_version from encounter where id = ?", Long.class, encounter),
                "SETTLED and NARRATIVE_RESUME are two checked row versions");
        assertEquals(1, jdbc.queryForObject("select count(*) from encounter where id = ? and actor_cursor is null "
                + "and action_points is null", Integer.class, encounter));
        assertTrue(EncounterWriter.active(jdbc, campaign).isEmpty());
        assertTrue(EncounterWriter.state(jdbc, campaign).isEmpty());
        assertEquals(1, eventCount("ENCOUNTER_SETTLED"));
        var event = latest("ENCOUNTER_SETTLED");
        assertEquals("VICTORY", event.get("outcome").asString());
        assertEquals(encounter.toString(), event.get("encounterId").asString());
        assertEquals(8, event.get("seconds").asInt());
        assertEquals("PUBLIC", jdbc.queryForObject("select visibility from campaign_event where campaign_id = ? and "
                + "event_type = 'ENCOUNTER_SETTLED'", String.class, campaign).toUpperCase(java.util.Locale.ROOT));
        assertEquals(player, jdbc.queryForObject("select actor_id from campaign_event where campaign_id = ? and "
                + "event_type = 'ENCOUNTER_SETTLED'", UUID.class, campaign), "the player's own decision");
        assertEquals(1, jdbc.queryForObject("select count(*) from presentation_job where operation_id = ?", Integer.class,
                settled.operation().operationId()), "narration is requested from the finalized result");

        var before = world();
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(settled.receipt(), again.receipt());
        assertEquals(before, world(), "the retry writes nothing");
        for (TypedCommand command : List.of(new TypedCommand.SettleEncounter(encounter), new TypedCommand.EndActivation(),
                new TypedCommand.Strike("sword", "dummy"), new TypedCommand.RollInitiative())) {
            rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, command);
        }
        for (String phase : List.of("OUTCOME_PENDING", "SETTLED", "ROUND_OPEN")) {
            assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set phase = ?, "
                    + "row_version = row_version + 1 where id = ?", phase, encounter), "no arrow leaves " + phase);
        }

        execute(new TypedCommand.Wait(30));
        assertEquals(clock + 8 + 30, clock());
        execute(new TypedCommand.Engage("close-rush", null));
        assertEquals("ENTERING", EncounterWriter.active(jdbc, campaign).orElseThrow().phase().name(), "a new fight can start");
        assertEquals(2, jdbc.queryForObject("select count(*) from encounter where campaign_id = ?", Integer.class, campaign));
    }

    /**
     * A lost fight settles at the incapacitated recovery state: Health stays at the pool's minimum, no exploration condition
     * changes, the time of an unfinished round is added once, and the player can wait but cannot start another fight.
     */
    @Test
    void aDefeatSettlesAtTheRecoveryState() {
        begin("venom-pit");
        setPool(1);
        execute(new TypedCommand.EndActivation());
        var stored = EncounterWriter.state(jdbc, campaign).orElseThrow();
        assertEquals("OUTCOME_PENDING", stored.phase().name());
        long seconds = stored.cursor() == null ? 0 : 8;
        long clock = clock();
        var conditions = jdbc.queryForList("select condition_id from actor_condition where actor_id = ? order by 1", String.class, player);

        var settled = execute(new TypedCommand.SettleEncounter(stored.id()));
        assertEquals("DEFEAT", ((Map<?, ?>) combat(settled).get("outcome")).get("outcome"));
        assertEquals(clock + seconds, clock());
        assertEquals(0L, pool(), "a defeat is never refilled");
        assertEquals(conditions, jdbc.queryForList("select condition_id from actor_condition where actor_id = ? order by 1",
                String.class, player), "settlement converts no combat effect into an exploration condition");
        assertEquals("EXPLORATION", jdbc.queryForObject("select mode from campaign where id = ?", String.class, campaign));
        assertEquals("trailhead", jdbc.queryForObject("select location_id from actor where id = ?", String.class, player));
        rejects(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, new TypedCommand.Engage("venom-pit", null));
        execute(new TypedCommand.Wait(10));
    }

    /**
     * A negotiated ending settles once across both lost responses (QA-13, docs/20 section 9): the accepted counteroffer's
     * retry replays before settlement, and after it both the acceptance and the settlement replay their own receipts and
     * write nothing. The truce stays recorded, the captain's ward ends with the fight, and every later answer or settlement
     * is refused as out of mode.
     */
    @Test
    void aNegotiatedEndingSettlesOnceAcrossLostResponses() {
        begin("parley-ground");
        var countered = execute(new TypedCommand.Interact("haggle", "captain"));
        var offer = (String) ((Map<?, ?>) ((Map<?, ?>) combat(countered).get("options")).get("offer")).get("offerId");
        var answer = envelope(new TypedCommand.AnswerOffer(offer, "accept"));
        var accepted = executions.execute(owner, adventure, campaign, answer);
        assertEquals(OperationStatus.COMMITTED, accepted.operation().status());
        revision = accepted.operation().committedRevision();
        assertEquals("AGREEMENT", ((Map<?, ?>) combat(accepted).get("outcome")).get("outcome"));
        assertEquals(accepted.receipt(), executions.execute(owner, adventure, campaign, answer).receipt(), "the first lost response");

        var stored = EncounterWriter.state(jdbc, campaign).orElseThrow();
        long seconds = stored.cursor() == null ? 0 : 8;
        long clock = clock();
        var settle = envelope(new TypedCommand.SettleEncounter(stored.id()));
        var settled = executions.execute(owner, adventure, campaign, settle);
        assertEquals(OperationStatus.COMMITTED, settled.operation().status());
        revision = settled.operation().committedRevision();
        var outcome = (Map<?, ?>) combat(settled).get("outcome");
        assertEquals("AGREEMENT", outcome.get("outcome"));
        assertEquals("toll", outcome.get("termsId"));
        assertEquals(List.of(Map.of("participantId", "captain", "effectId", "warded", "stacks", 1)),
                ((Map<?, ?>) combat(settled).get("settlement")).get("effectsEnded"));
        assertEquals("[]", jdbc.queryForObject("select effects::text from encounter_participant where participant_id = 'captain'",
                String.class));
        assertEquals("toll", JSON.readTree(jdbc.queryForObject("select agreement::text from encounter where id = ?", String.class,
                stored.id())).get("termsId").asString(), "the truce stays on record");
        assertEquals(clock + seconds, clock());

        var before = world();
        var again = executions.execute(owner, adventure, campaign, settle);
        assertTrue(again.replayed());
        assertEquals(settled.receipt(), again.receipt(), "the settlement's lost response");
        var late = executions.execute(owner, adventure, campaign, answer);
        assertTrue(late.replayed());
        assertEquals(accepted.receipt(), late.receipt(), "the acceptance's lost response, after settlement");
        assertEquals(before, world(), "neither retry pays, settles or writes anything");
        assertEquals(1, eventCount("ENCOUNTER_SETTLED"));
        assertEquals(1, eventCount("OFFER_ANSWERED"));
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.AnswerOffer(offer, "accept"));
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.SettleEncounter(stored.id()));
        execute(new TypedCommand.Wait(5));
    }

    /**
     * Every other ending settles to the verdict it reached, one fight after another in the same campaign: an escape, the
     * player's surrender, the opponents' surrender, survived rounds at a round end (no time added) and a reached zone in
     * another location. Each leaves the campaign in exploration at the player's own location.
     */
    @Test
    void everyEndingSettlesAndTheCampaignFightsAgain() {
        var outcomes = new ArrayList<String>();
        begin("trail-ambush");
        execute(new TypedCommand.MoveZone("escape-route"));
        outcomes.add(settle());
        assertEquals("trailhead", jdbc.queryForObject("select location_id from actor where id = ?", String.class, player),
                "an escape leaves the encounter's zones, not the place");

        begin("parley-ground");
        execute(new TypedCommand.AnswerOffer(offerOf(execute(new TypedCommand.Interact("lay-down-arms", "captain"))), "accept"));
        outcomes.add(settle());

        begin("parley-ground");
        execute(new TypedCommand.AnswerOffer(offerOf(execute(new TypedCommand.Interact("demand-surrender", "captain"))), "accept"));
        execute(new TypedCommand.AnswerOffer(offerOf(execute(new TypedCommand.Interact("demand-surrender", "squire"))), "accept"));
        outcomes.add(settle());

        begin("toll-parley");
        for (int round = 0; round < 3; round++) {
            execute(new TypedCommand.EndActivation());
        }
        long clock = clock();
        outcomes.add(settle());
        assertEquals(clock, clock(), "a fight decided at its round end adds no time at settlement");

        execute(new TypedCommand.Move("grove"));
        begin("grove-watch");
        execute(new TypedCommand.MoveZone("stones"));
        outcomes.add(settle());

        assertEquals(List.of("ESCAPE", "PLAYER_SURRENDER", "HOSTILE_SURRENDER", "OBJECTIVE", "OBJECTIVE"), outcomes);
        assertEquals(5, eventCount("ENCOUNTER_SETTLED"));
        assertEquals(5, jdbc.queryForObject("select count(*) from encounter where campaign_id = ? and phase = 'NARRATIVE_RESUME'",
                Integer.class, campaign));
        execute(new TypedCommand.Wait(5));
    }

    /**
     * Both round-cap verdicts settle at the end of round 30 with no time added: the stalemate and the declared escape move
     * nobody, so the player stays in the zone where the fight stood still and at the encounter's location.
     */
    @Test
    void theRoundCapEndingsSettleWithoutTimeOrMovement() {
        for (String template : List.of("stand-off", "watch-post")) {
            begin(template);
            for (int round = 0; round < 30; round++) {
                execute(new TypedCommand.EndActivation());
            }
            long clock = clock();
            var settled = execute(new TypedCommand.SettleEncounter(lastEncounter()));
            var outcome = (Map<?, ?>) combat(settled).get("outcome");
            assertEquals(template.equals("stand-off") ? "STALEMATE" : "ESCAPE", outcome.get("outcome"));
            assertEquals(true, outcome.get("roundCap"));
            assertEquals(clock, clock());
            var standing = latest("ENCOUNTER_SETTLED").get("participants");
            assertEquals("bank", standing.get(0).get("zone").asString());
            assertEquals("ACTIVE", standing.get(0).get("status").asString());
            assertEquals("trailhead", jdbc.queryForObject("select location_id from actor where id = ?", String.class, player));
            assertEquals("EXPLORATION", jdbc.queryForObject("select mode from campaign where id = ?", String.class, campaign));
        }
    }

    /**
     * Settling a fight that ended inside a round needs room on the bounded clock for that round: at seven seconds from the
     * end it is refused and writes nothing, and the fight keeps waiting at its outcome.
     */
    @Test
    void theClockMustTakeTheUnfinishedRound() {
        begin("close-rush");
        setLocalHealth("dummy", 1);
        execute(new TypedCommand.Strike("sword", "dummy"));
        UUID encounter = EncounterWriter.active(jdbc, campaign).orElseThrow().id();
        setClock(WorldClock.END_SECONDS - 7);
        rejects(OperationAdmissionFailure.Kind.TIME_ILLEGAL, new TypedCommand.SettleEncounter(encounter));
        assertEquals("OUTCOME_PENDING", phase(encounter));
    }

    /** A fight decided at its round end needs no time to settle, so it settles even when the clock has reached its end. */
    @Test
    void aRoundEndOutcomeSettlesAtTheEndOfTheClock() {
        begin("toll-parley");
        setClock(WorldClock.END_SECONDS - 24);
        for (int round = 0; round < 3; round++) {
            execute(new TypedCommand.EndActivation());
        }
        assertEquals(WorldClock.END_SECONDS, clock());
        execute(new TypedCommand.SettleEncounter(lastEncounter()));
        assertEquals(WorldClock.END_SECONDS, clock());
        assertEquals("EXPLORATION", jdbc.queryForObject("select mode from campaign where id = ?", String.class, campaign));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Settle the fight waiting at its outcome; check the return to exploration and the verdict stated; return it. */
    private String settle() {
        UUID encounter = EncounterWriter.active(jdbc, campaign).orElseThrow().id();
        String pending = latest("ENCOUNTER_OUTCOME_PENDING").get("outcome").asString();
        var settled = execute(new TypedCommand.SettleEncounter(encounter));
        assertEquals("NARRATIVE_RESUME", combat(settled).get("phase"));
        assertEquals(pending, ((Map<?, ?>) combat(settled).get("outcome")).get("outcome"));
        assertEquals(pending, latest("ENCOUNTER_SETTLED").get("outcome").asString());
        assertEquals("NARRATIVE_RESUME", phase(encounter));
        assertEquals("EXPLORATION", jdbc.queryForObject("select mode from campaign where id = ?", String.class, campaign));
        return pending;
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

    /** The identity of the offer a receipt lists as waiting for an answer. */
    private static String offerOf(CommandExecutionService.Execution execution) {
        return (String) ((Map<?, ?>) ((Map<?, ?>) combat(execution).get("options")).get("offer")).get("offerId");
    }

    /** The campaign's most recently started encounter, the one waiting at its outcome in these cases. */
    private UUID lastEncounter() {
        return EncounterWriter.active(jdbc, campaign).orElseThrow().id();
    }

    private String phase(UUID encounter) {
        return jdbc.queryForObject("select phase from encounter where id = ?", String.class, encounter);
    }

    /** The payload of the latest event of a type. */
    private JsonNode latest(String type) {
        return JSON.readTree(jdbc.queryForObject("select payload::text from campaign_event where campaign_id = ? "
                + "and event_type = ? order by sequence desc limit 1", String.class, campaign, type));
    }

    private int eventCount(String type) {
        return jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? and event_type = ?", Integer.class, campaign, type);
    }

    private long clock() {
        return jdbc.queryForObject("select world_time_seconds from campaign where id = ?", Long.class, campaign);
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
                + "where participant_id = ? and encounter_id = (select id from encounter where campaign_id = ? "
                + "and phase not in ('SETTLED', 'NARRATIVE_RESUME'))", value, value == 0 ? "DEFEATED" : "ACTIVE", participant, campaign);
    }

    /** Move the clock directly, as the clock tests do: the V21 step trigger is paused for this one statement only. */
    private void setClock(long seconds) {
        jdbc.execute("alter table campaign disable trigger campaign_world_time_step");
        try {
            jdbc.update("update campaign set world_time_seconds = ?, row_version = row_version + 1 where id = ?", seconds, campaign);
        } finally {
            jdbc.execute("alter table campaign enable trigger campaign_world_time_step");
        }
    }

    /** Every table a command can touch, for exact before/after comparison. */
    private Map<String, List<String>> world() {
        var tables = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("campaign", "actor", "actor_resource", "actor_condition", "campaign_event", "operation_journal",
                "operation_random_draw", "canonical_outbox", "encounter", "encounter_participant", "item_instance")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }
}
