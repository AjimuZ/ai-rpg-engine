package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Real PostgreSQL acceptance of negotiation, surrender and agreement (QA-11, QA-13) on campaigns created from the sealed
 * encounter fixture. Campaign dice are unpredictable, so each case takes a response the fixture makes certain, and the case
 * that follows real dice compares them with an independent reading of the rule. A refused negotiation, a counteroffer, an
 * explicit acceptance, a decline, a lapse and both surrenders go through the real commit and its V22 to V30 guards. A retry
 * replays the same receipt without a second draw, cost or effect, and every refusal writes nothing.
 */
@SpringBootTest
class NegotiationCommandsTest {
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
    private long revision;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    NegotiationCommandsTest() { }

    /** Register the encounter pack, create a campaign at the trailhead and start the talking fight through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Negotiations", "negotiations", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(EncounterPack.tactics())))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        campaign = created.campaignId();
        revision = 0;
        execute(new TypedCommand.Engage("parley-ground", null));
        execute(new TypedCommand.RollInitiative());
        assertTrue(EncounterWriter.state(jdbc, campaign).orElseThrow().playerDecision());
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
     * A refused negotiation pays its declared cost, draws its declared check once and leaves the fight at the same
     * activation with nothing on the table. A retry of the lost response replays the same receipt with no second draw, and
     * an answer to terms nobody made is refused without writing.
     */
    @Test
    void aRefusedNegotiationLeavesTheFightWhereItWas() {
        int points = points();
        int drawsBefore = draws();
        var envelope = envelope(new TypedCommand.Interact("hopeless-plea", "captain"));
        var refused = executions.execute(owner, adventure, campaign, envelope);
        assertEquals(OperationStatus.COMMITTED, refused.operation().status());
        revision = refused.operation().committedRevision();
        assertEquals("AWAITING_ACTION", combat(refused).get("phase"));
        assertEquals(points - 1, points(), "the declared cost");
        assertEquals(drawsBefore + 2, draws(), "the declared check");
        var social = (Map<?, ?>) combat(refused).get("social");
        assertEquals("REFUSED", social.get("response"));
        assertEquals(false, social.get("success"));
        assertFalse(((Map<?, ?>) combat(refused).get("options")).containsKey("offer"));
        assertEquals(1, eventCount("NEGOTIATION_RESPONDED"));
        assertEquals("REFUSED", event("NEGOTIATION_RESPONDED").get("response").asString());
        assertNull(column("open_offer"));
        assertNull(column("agreement"));
        assertEquals(0, eventCount("ENCOUNTER_OUTCOME_PENDING"));
        assertNull(EncounterOutcome.decide(EncounterWriter.state(jdbc, campaign).orElseThrow()));

        var before = world();
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(refused.receipt(), again.receipt());
        assertEquals(before, world(), "the retry writes nothing");
        rejects(OperationAdmissionFailure.Kind.OFFER_UNAVAILABLE, new TypedCommand.AnswerOffer("offer-1", "accept"));
    }

    /**
     * A counteroffer stays a counteroffer: it waits on the row with its cost disclosed, words saying "I accept" change nothing,
     * and only the explicit answer makes the agreement. That answer pays the terms' points, ends the fight as an agreement with
     * everyone standing, and a retry of its lost response replays the same receipt without paying twice (QA-13).
     */
    @Test
    void aCounterofferNeedsTheExplicitAnswer() {
        var countered = execute(new TypedCommand.Interact("haggle", "captain"));
        assertEquals("AWAITING_ACTION", combat(countered).get("phase"));
        var listed = offer(countered);
        assertEquals("COUNTEROFFERED", listed.get("response"));
        assertEquals("captain", listed.get("recipientId"));
        assertEquals(Map.of("termsId", "toll", "kind", "truce", "cost", 2), listed.get("terms"));
        assertEquals(true, listed.get("canAccept"));
        var id = (String) listed.get("offerId");
        assertEquals(id, JSON.readTree(column("open_offer")).get("id").asString());

        var spoken = execute(new TypedCommand.CombatSpeak("captain", "I accept your terms: the fight is over."));
        assertEquals("AWAITING_ACTION", combat(spoken).get("phase"));
        assertEquals(id, offer(spoken).get("offerId"), "the terms still wait");
        assertNull(column("agreement"));
        assertEquals(0, eventCount("OFFER_ANSWERED"));
        assertEquals(0, eventCount("ENCOUNTER_OUTCOME_PENDING"));

        rejects(OperationAdmissionFailure.Kind.OFFER_UNAVAILABLE, new TypedCommand.AnswerOffer("offer-999999", "accept"));
        var envelope = envelope(new TypedCommand.AnswerOffer(id, "accept"));
        var accepted = executions.execute(owner, adventure, campaign, envelope);
        assertEquals(OperationStatus.COMMITTED, accepted.operation().status());
        revision = accepted.operation().committedRevision();
        var outcome = (Map<?, ?>) combat(accepted).get("outcome");
        assertEquals("OUTCOME_PENDING", combat(accepted).get("phase"));
        assertEquals("AGREEMENT", outcome.get("outcome"));
        assertEquals("toll", outcome.get("termsId"));
        assertEquals("ACCEPTED", ((Map<?, ?>) combat(accepted).get("social")).get("decision"));
        assertEquals(0, points(), "one point for the attempt and two for the toll");
        assertEquals("ACTIVE", status("captain"));
        assertEquals("ACTIVE", status("squire"));
        assertEquals("ACTIVE", status("player"));
        assertEquals("toll", JSON.readTree(column("agreement")).get("termsId").asString());
        assertEquals(id, JSON.readTree(column("agreement")).get("offerId").asString());
        assertNull(column("open_offer"));
        assertEquals("AGREEMENT", event("ENCOUNTER_OUTCOME_PENDING").get("outcome").asString());
        assertEquals(EncounterOutcome.Kind.AGREEMENT, EncounterOutcome.decide(EncounterWriter.state(jdbc, campaign).orElseThrow()).kind(),
                "read back, the stored state gives the same verdict");

        var before = world();
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(accepted.receipt(), again.receipt());
        assertEquals(before, world(), "the retry pays and writes nothing");
        refusesEverything();
        assertEquals(EncounterPhase.OUTCOME_PENDING, EncounterWriter.active(jdbc, campaign).orElseThrow().phase());
    }

    /** Accepting terms the activation cannot pay is refused and leaves them on the table; declining stays free and final. */
    @Test
    void anUnaffordableAcceptanceChangesNothing() {
        execute(new TypedCommand.Guard());
        execute(new TypedCommand.Interact("hopeless-plea", "captain"));
        var id = (String) offer(execute(new TypedCommand.Interact("haggle", "captain"))).get("offerId");
        assertEquals(0, points());
        rejects(OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT, new TypedCommand.AnswerOffer(id, "accept"));
        assertEquals(id, JSON.readTree(column("open_offer")).get("id").asString());
        var declined = execute(new TypedCommand.AnswerOffer(id, "decline"));
        assertEquals("AWAITING_ACTION", combat(declined).get("phase"));
        assertEquals("DECLINED", event("OFFER_ANSWERED").get("decision").asString());
        assertNull(column("open_offer"));
        assertEquals(0, points());
        rejects(OperationAdmissionFailure.Kind.OFFER_UNAVAILABLE, new TypedCommand.AnswerOffer(id, "decline"));
    }

    /** Terms lapse when the activation ends and when the player acts instead, publicly, and can no longer be answered. */
    @Test
    void offersLapseUnanswered() {
        var id = (String) offer(execute(new TypedCommand.Interact("offer-peace", "captain"))).get("offerId");
        execute(new TypedCommand.Guard());
        assertEquals("ACTION_TAKEN", event("OFFER_LAPSED").get("reason").asString());
        assertNull(column("open_offer"));
        rejects(OperationAdmissionFailure.Kind.OFFER_UNAVAILABLE, new TypedCommand.AnswerOffer(id, "accept"));

        var second = (String) offer(execute(new TypedCommand.Interact("offer-peace", "squire"))).get("offerId");
        var ended = execute(new TypedCommand.EndActivation());
        assertEquals("AWAITING_ACTION", combat(ended).get("phase"));
        assertEquals(2, eventCount("OFFER_LAPSED"));
        assertNull(column("open_offer"));
        assertFalse(((Map<?, ?>) combat(ended).get("options")).containsKey("offer"));
        rejects(OperationAdmissionFailure.Kind.OFFER_UNAVAILABLE, new TypedCommand.AnswerOffer(second, "accept"));
    }

    /**
     * Accepted surrender terms take the recipient out of the fight, with its effects cleared as V25 requires and its Health kept,
     * while the other opponent fights on. A surrendered one cannot be attacked or asked again but can be spoken to, and takes no
     * activation. The second surrender ends the fight as a surrender, not a victory.
     */
    @Test
    void acceptedSurrendersEndTheFightOnlyWithTheLastOpponent() {
        var offered = execute(new TypedCommand.Interact("demand-surrender", "captain"));
        assertEquals("ACTIVE", status("captain"), "the response binds nobody");
        var yielded = execute(new TypedCommand.AnswerOffer((String) offer(offered).get("offerId"), "accept"));
        assertEquals("AWAITING_ACTION", combat(yielded).get("phase"));
        assertEquals("SURRENDERED", status("captain"));
        assertEquals("[]", jdbc.queryForObject("select effects::text from encounter_participant where participant_id = 'captain'", String.class));
        assertEquals(999, jdbc.queryForObject("select health_current from encounter_participant where participant_id = 'captain'", Integer.class));
        assertEquals(1, eventCount("PARTICIPANT_SURRENDERED"));
        assertTrue(lines("EFFECTS_RESOLVED").contains("HOLDER_SURRENDERED"), "its held effect ended, publicly");
        assertEquals(0, eventCount("ENCOUNTER_OUTCOME_PENDING"));

        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Strike("sword", "captain"));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Interact("demand-surrender", "captain"));
        execute(new TypedCommand.CombatSpeak("captain", "Stay where you are."));

        int started = startedBy("captain");
        var round = execute(new TypedCommand.EndActivation());
        assertEquals("AWAITING_ACTION", combat(round).get("phase"));
        assertEquals(started, startedBy("captain"), "a surrendered participant takes no activation");
        assertEquals("SURRENDERED", status("captain"));

        var last = execute(new TypedCommand.Interact("demand-surrender", "squire"));
        var ended = execute(new TypedCommand.AnswerOffer((String) offer(last).get("offerId"), "accept"));
        assertEquals("OUTCOME_PENDING", combat(ended).get("phase"));
        assertEquals("HOSTILE_SURRENDER", ((Map<?, ?>) combat(ended).get("outcome")).get("outcome"));
        assertEquals(EncounterOutcome.Kind.HOSTILE_SURRENDER, EncounterOutcome.decide(EncounterWriter.state(jdbc, campaign).orElseThrow()).kind());
        refusesEverything();
    }

    /**
     * The player's surrender needs the same explicit answer: offering it, or being offered it, changes no standing. Accepted, it
     * ends the fight, fails every objective and keeps the player's Health; a retry replays it.
     */
    @Test
    void thePlayersSurrenderNeedsTheExplicitAnswer() {
        var offered = execute(new TypedCommand.Interact("lay-down-arms", "captain"));
        assertEquals("ACTIVE", status("player"));
        assertNull(EncounterOutcome.decide(EncounterWriter.state(jdbc, campaign).orElseThrow()));
        long health = jdbc.queryForObject("select current_value from actor_resource where resource_key = 'vigor'", Long.class);
        var envelope = envelope(new TypedCommand.AnswerOffer((String) offer(offered).get("offerId"), "accept"));
        var surrendered = executions.execute(owner, adventure, campaign, envelope);
        assertEquals(OperationStatus.COMMITTED, surrendered.operation().status());
        revision = surrendered.operation().committedRevision();
        assertEquals("SURRENDERED", status("player"));
        assertEquals("PLAYER_SURRENDER", ((Map<?, ?>) combat(surrendered).get("outcome")).get("outcome"));
        assertEquals("failed", event("ENCOUNTER_OUTCOME_PENDING").get("objectives").get(0).get("status").asString());
        assertEquals(health, jdbc.queryForObject("select current_value from actor_resource where resource_key = 'vigor'", Long.class));
        var before = world();
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(surrendered.receipt(), again.receipt());
        assertEquals(before, world());
        refusesEverything();
    }

    /**
     * A check follows the recorded dice: its public event states them with the total the stored rank and role give, the response
     * matches an independent reading of the rule, the difficulty stays in the internal event and out of the receipt, and a check
     * with no terms changes nothing beyond its cost (QA-11).
     */
    @Test
    void aDeclaredCheckFollowsItsRecordedDice() {
        int rank = JSON.readTree(jdbc.queryForObject("select definition::text from encounter", String.class)).get("actions").get(6)
                .get("check").get("rank").asInt();
        var measured = execute(new TypedCommand.Interact("measured-offer", "captain"));
        var shown = event("SOCIAL_CHECK_RESOLVED");
        int first = shown.get("dice").get(0).asInt();
        int second = shown.get("dice").get(1).asInt();
        List<Integer> stored = jdbc.queryForList("select value from operation_random_draw where operation_id = "
                + "(select id from operation_journal where campaign_id = ? order by committed_revision desc nulls last limit 1) "
                + "order by ordinal", Integer.class, campaign);
        assertEquals(List.of(first, second), stored);
        int total = first + second + 2 + rank;
        assertEquals(total, shown.get("total").asInt(), "dice, the surveyor's awareness and the frozen rank");
        assertEquals(total >= 14, shown.get("success").asBoolean());
        assertFalse(shown.has("difficulty"));
        var social = (Map<?, ?>) combat(measured).get("social");
        assertFalse(social.containsKey("difficulty"));
        assertFalse(social.containsKey("margin"));
        String expected = total < 14 ? "REFUSED" : total - 14 >= 6 ? "OFFERED" : "COUNTEROFFERED";
        assertEquals(expected, social.get("response"));
        var hidden = event("SOCIAL_CHECK_CALCULATED");
        assertEquals(14, hidden.get("difficulty").asInt());
        assertEquals("internal", jdbc.queryForObject("select visibility from campaign_event where campaign_id = ? "
                + "and event_type = 'SOCIAL_CHECK_CALCULATED'", String.class, campaign).toLowerCase(java.util.Locale.ROOT));
        assertEquals(expected.equals("REFUSED"), column("open_offer") == null);
        if (!expected.equals("REFUSED")) {
            assertEquals(expected.equals("OFFERED") ? "fair-terms" : "small-toll", JSON.readTree(column("open_offer")).get("terms").get("id").asString());
        }
        assertEquals(1, points(), "the declared two of three");

        var swayed = execute(new TypedCommand.Interact("sway", "captain"));
        var line = (Map<?, ?>) combat(swayed).get("social");
        assertTrue(line.containsKey("total"));
        assertFalse(line.containsKey("response"));
        assertEquals(1, eventCount("NEGOTIATION_RESPONDED"), "a check alone gets no response");
        assertEquals("ACTIVE", status("captain"));
        assertNull(column("open_offer"), "the attempt took whatever was on the table");
    }

    /** Ambiguous input changes nothing: a missing recipient, an unknown action, the wrong side, a malformed decision (QA-11). */
    @Test
    void ambiguousInputChangesNothing() {
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Interact("haggle", null));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Interact("haggle", "nobody"));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Interact("haggle", "player"));
        rejects(OperationAdmissionFailure.Kind.INTERACTION_UNAVAILABLE, new TypedCommand.Interact("charm", "captain"));
        rejects(OperationAdmissionFailure.Kind.OFFER_UNAVAILABLE, new TypedCommand.AnswerOffer("offer-1", "decline"));
        var id = (String) offer(execute(new TypedCommand.Interact("haggle", "captain"))).get("offerId");
        for (var payload : List.<Map<String, Object>>of(Map.of("offerId", id, "decision", "maybe"), Map.of("offerId", id, "decision", "yes"),
                Map.of("offerId", id, "decision", "ACCEPT"), Map.of("offerId", id), Map.of("decision", "accept"),
                Map.of("offerId", id, "decision", "accept", "terms", "free-pass"))) {
            var before = world();
            var failure = assertThrows(OperationAdmissionFailure.class, () -> executions.execute(owner, adventure, campaign,
                    new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, "ANSWER_OFFER", payload)), payload.toString());
            assertEquals(OperationAdmissionFailure.Kind.INVALID_COMMAND, failure.kind(), payload.toString());
            assertEquals(before, world(), payload.toString());
        }
        assertEquals(id, JSON.readTree(column("open_offer")).get("id").asString(), "the terms still wait for a real answer");
    }

    /** The database repeats what it can see: an offer waits only at a decision, a truce comes with an outcome and never changes. */
    @Test
    void theDatabaseGuardsTheNegotiation() {
        var id = EncounterWriter.active(jdbc, campaign).orElseThrow().id();
        String setAgreement = "update encounter set agreement = ?::jsonb, row_version = row_version + 1 where id = ?";
        assertThrows(DataAccessException.class, () -> jdbc.update(setAgreement, "{\"termsId\":\"open-road\"}", id), "an agreement comes with its outcome");
        String setOffer = "update encounter set open_offer = ?::jsonb, row_version = row_version + 1 where id = ?";
        assertThrows(DataAccessException.class, () -> jdbc.update(setOffer, "[]", id), "an offer is an object");
        assertEquals(1, jdbc.update(setOffer, "{\"id\":\"offer-x\"}", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set phase = 'RESOLVING', "
                + "row_version = row_version + 1 where id = ?", id), "an offer waits only at the decision");
        assertEquals(1, jdbc.update(setOffer, null, id));

        var yielded = execute(new TypedCommand.Interact("offer-peace", "captain"));
        execute(new TypedCommand.AnswerOffer((String) offer(yielded).get("offerId"), "accept"));
        assertEquals("OUTCOME_PENDING", jdbc.queryForObject("select phase from encounter", String.class));
        assertThrows(DataAccessException.class, () -> jdbc.update(setAgreement, "{\"termsId\":\"other\"}", id), "an accepted agreement never changes");
        assertThrows(DataAccessException.class, () -> jdbc.update(setOffer, "{\"id\":\"offer-y\"}", id), "no offer waits at an outcome");
        jdbc.update("update encounter_participant set status = 'SURRENDERED', row_version = row_version + 1 where participant_id = 'squire'");
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter_participant set status = 'ACTIVE', "
                + "row_version = row_version + 1 where participant_id = 'squire'"), "a surrendered participant does not return");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Every tactical command is refused as out of turn, exploration stays out of mode, and neither writes anything. */
    private void refusesEverything() {
        for (TypedCommand command : List.of(new TypedCommand.EndActivation(), new TypedCommand.Guard(),
                new TypedCommand.MoveZone("bridge"), new TypedCommand.Interact("offer-peace", "squire"), new TypedCommand.RollInitiative(),
                new TypedCommand.CombatSpeak("squire", "Well?"), new TypedCommand.AnswerOffer("offer-1", "accept"))) {
            rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, command);
        }
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.Move("grove"));
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

    /** The offer the receipt lists at the player's decision. */
    private static Map<?, ?> offer(CommandExecutionService.Execution execution) {
        return (Map<?, ?>) ((Map<?, ?>) combat(execution).get("options")).get("offer");
    }

    private int points() {
        return jdbc.queryForObject("select action_points from encounter", Integer.class);
    }

    private int draws() {
        return jdbc.queryForObject("select count(*) from operation_random_draw", Integer.class);
    }

    private String column(String name) {
        return jdbc.queryForObject("select " + name + "::text from encounter", String.class);
    }

    private String status(String participant) {
        return jdbc.queryForObject("select status from encounter_participant where participant_id = ?", String.class, participant);
    }

    /** The payload of the latest event of a type. */
    private JsonNode event(String type) {
        return JSON.readTree(jdbc.queryForObject("select payload::text from campaign_event where campaign_id = ? and event_type = ? "
                + "order by sequence desc limit 1", String.class, campaign, type));
    }

    /** The whole payload text of every event of a type. */
    private String lines(String type) {
        return String.join("|", jdbc.queryForList("select payload::text from campaign_event where campaign_id = ? and event_type = ?",
                String.class, campaign, type));
    }

    private int eventCount(String type) {
        return jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? and event_type = ?", Integer.class, campaign, type);
    }

    private int startedBy(String participant) {
        return jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? and event_type = 'ACTIVATION_STARTED' "
                + "and payload ->> 'participantId' = ?", Integer.class, campaign, participant);
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
