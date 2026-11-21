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
import org.rpgengine.combat.Disengagement;
import org.rpgengine.combat.EncounterPack;
import org.rpgengine.combat.EncounterWriter;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of Withdraw and the round cap (P11.5, docs/11 sections 7 and 13, ADR 0035) on campaigns created
 * from the sealed tactics fixture. In {@code ridge-retreat} a sentry's {@code snap} answers anyone leaving its zone: a move
 * provokes it, a withdrawal does not and draws nothing, and only reaching the pass ends the fight, as an escape. In
 * {@code stand-off} and {@code watch-post} nothing can decide the fight, so the end of round 30 does: a stalemate by default and
 * an escape where the template declares it, harming nobody. Retries replay the same receipt and write nothing; refusals write
 * nothing.
 */
@SpringBootTest
class WithdrawCommandsTest {
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
    WithdrawCommandsTest() { }

    /** Register the tactics pack and create a campaign at the trailhead through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Withdrawing", "withdrawing", SupportedLocale.EN, Instant.now());
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
                + "memory_summary_job, relationship_change, faction_clock_change, learned_recipe, canon_correction, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
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
     * Withdrawing from the sentry's camp to the ridge costs two points, provokes no reaction and draws nothing; its retry
     * replays the receipt. With the sentry beside it on the ridge, the next withdrawal reaches the pass, and the escape comes
     * from the terminal predicates: the player's standing stays active and every later command is refused.
     */
    @Test
    void aWithdrawalIsAnsweredByNobodyAndEscapesOnlyAtThePass() {
        var rolled = begin("ridge-retreat");
        assertEquals(List.of(Map.of("zone", "ridge", "cost", 2)), options(rolled).get("withdraw"), "the gully leads away");
        long reactions = events("REACTION_RESOLVED").size();

        var envelope = envelope(new TypedCommand.Withdraw("ridge"));
        var drawsBefore = count("operation_random_draw");
        var first = executions.execute(owner, adventure, campaign, envelope);
        assertEquals(OperationStatus.COMMITTED, first.operation().status());
        revision = first.operation().committedRevision();
        assertEquals(1, combat(first).get("actionPoints"));
        assertEquals("ridge", combat(first).get("zone"));
        assertFalse(combat(first).containsKey("strikes"), "nobody answers the withdrawal");
        assertEquals(reactions, events("REACTION_RESOLVED").size());
        assertEquals(drawsBefore, count("operation_random_draw"), "a withdrawal draws nothing");
        var withdrew = events("PARTICIPANT_WITHDREW").getLast();
        assertEquals("camp", withdrew.get("from").asString());
        assertEquals("ridge", withdrew.get("to").asString());
        assertEquals(2, withdrew.get("actionPoints").intValue());
        assertEquals(player.toString(), withdrew.get("actorId").asString());
        assertEquals("PUBLIC", visibility("PARTICIPANT_WITHDREW"));
        assertEquals(0L, first.receipt().get("elapsedSeconds"));

        var before = world();
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(first.receipt(), again.receipt());
        assertEquals(before, world(), "a retry writes nothing");

        rejects(OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT, new TypedCommand.Withdraw("pass"));
        rejects(OperationAdmissionFailure.Kind.ZONE_UNAVAILABLE, new TypedCommand.Withdraw("camp"));
        rejects(OperationAdmissionFailure.Kind.ZONE_UNAVAILABLE, new TypedCommand.Withdraw("gully"));
        rejectsRaw(OperationAdmissionFailure.Kind.INVALID_COMMAND, "WITHDRAW", Map.of());
        rejectsRaw(OperationAdmissionFailure.Kind.INVALID_COMMAND, "WITHDRAW", Map.of("zoneId", "pass", "escape", true));

        execute(new TypedCommand.EndActivation());
        assertEquals("ridge", participant("sentry", "zone_id"), "the sentry follows and stands beside the player");
        assertEquals("ACTIVE", participant("player", "status"));
        reactions = events("REACTION_RESOLVED").size();
        var out = execute(new TypedCommand.Withdraw("pass"));
        assertEquals(reactions, events("REACTION_RESOLVED").size(), "leaving the sentry's zone again provokes nothing");
        var outcome = (Map<?, ?>) combat(out).get("outcome");
        assertEquals("ESCAPE", outcome.get("outcome"));
        assertFalse(outcome.containsKey("roundCap"));
        assertEquals(List.of(Map.of("objectiveId", "away", "status", "met")), outcome.get("objectives"));
        assertEquals("OUTCOME_PENDING", jdbc.queryForObject("select phase from encounter where campaign_id = ?", String.class, campaign));
        assertEquals("pass", participant("player", "zone_id"));
        assertEquals("ACTIVE", participant("player", "status"), "the action set no standing");
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.EndActivation());
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.Withdraw("ridge"));
    }

    /** The same edge walked as a move is answered by the sentry's snap, which is what a withdrawal suppresses. */
    @Test
    void aMoveIsAnsweredByTheSnap() {
        begin("ridge-retreat");
        long reactions = events("REACTION_RESOLVED").size();
        var moved = execute(new TypedCommand.MoveZone("ridge"));
        var answered = events("REACTION_RESOLVED");
        assertEquals(reactions + 1, answered.size());
        assertEquals("snap", answered.getLast().get("reactionId").asString());
        assertEquals("leavesZone", answered.getLast().get("trigger").asString());
        assertEquals(1, ((List<?>) combat(moved).get("strikes")).size());
    }

    /**
     * Nothing can decide the stand-off, so the end of round 30 does under the default policy: a stalemate the cap gave, every
     * participant where and as it was, the round's time added once per round and no round beyond the cap. The frozen
     * definition records no policy, and the last retry replays.
     */
    @Test
    void theRoundCapEndsAStandOffInAStalemate() {
        begin("stand-off");
        assertNull(frozenPolicy(), "the default leaves the definition as it was");
        assertEquals(Disengagement.STALEMATE, EncounterWriter.state(jdbc, campaign).orElseThrow().disengagement());
        long health = vigor();
        var last = untilTheCap();
        var outcome = (Map<?, ?>) combat(last).get("outcome");
        assertEquals("STALEMATE", outcome.get("outcome"));
        assertEquals(true, outcome.get("roundCap"));
        assertNull(outcome.get("objectiveId"));
        assertEquals(List.of(Map.of("objectiveId", "rout", "status", "pending")), outcome.get("objectives"));
        assertEquals(30, combat(last).get("round"));
        assertEquals(8L, last.receipt().get("elapsedSeconds"));
        var verdict = events("ENCOUNTER_OUTCOME_PENDING").getLast();
        assertEquals("STALEMATE", verdict.get("outcome").asString());
        assertTrue(verdict.get("roundCap").asBoolean());
        assertEquals("PUBLIC", visibility("ENCOUNTER_OUTCOME_PENDING"));
        assertEquals(30, events("ROUND_ENDED").size());
        assertEquals(30, events("ROUND_STARTED").size(), "no round opens beyond the cap");
        assertEquals(health, vigor(), "the cap harms nobody");
        assertEquals("999", participant("watcher", "health_current::text"));
        assertEquals("ACTIVE", participant("watcher", "status"));
        assertEquals("ford", participant("watcher", "zone_id"));
        assertEquals("bank", participant("player", "zone_id"));
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.EndActivation());
    }

    /** A template declaring {@code escape} freezes that policy, and the end of round 30 lets the player go without moving it. */
    @Test
    void theDeclaredEscapePolicyLetsThePlayerGoAtTheCap() {
        begin("watch-post");
        assertEquals("escape", frozenPolicy());
        assertEquals(Disengagement.ESCAPE, EncounterWriter.state(jdbc, campaign).orElseThrow().disengagement());
        long health = vigor();
        var last = untilTheCap();
        var outcome = (Map<?, ?>) combat(last).get("outcome");
        assertEquals("ESCAPE", outcome.get("outcome"));
        assertEquals(true, outcome.get("roundCap"));
        assertEquals("bank", participant("player", "zone_id"), "nobody moves");
        assertEquals("ACTIVE", participant("player", "status"));
        assertEquals(health, vigor());

        var before = world();
        var replay = executions.execute(owner, adventure, campaign, lastEnvelope);
        assertTrue(replay.replayed());
        assertEquals(last.receipt(), replay.receipt());
        assertEquals(before, world(), "the decisive retry writes nothing");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Envelope of the last command {@link #untilTheCap} sent. */
    private OperationAdmissionService.Command lastEnvelope;

    /** End the player's activation until the fight has its verdict, which must come with the thirtieth round. */
    private CommandExecutionService.Execution untilTheCap() {
        CommandExecutionService.Execution last = null;
        for (int round = 1; round <= 30; round++) {
            assertNull(last == null ? null : combat(last).get("outcome"), "undecided before the cap, round " + round);
            lastEnvelope = envelope(new TypedCommand.EndActivation());
            last = executions.execute(owner, adventure, campaign, lastEnvelope);
            assertEquals(OperationStatus.COMMITTED, last.operation().status());
            revision = last.operation().committedRevision();
        }
        return last;
    }

    /** Enter a template at the trailhead and roll initiative; returns the roll's execution. */
    private CommandExecutionService.Execution begin(String template) {
        execute(new TypedCommand.Engage(template, null));
        var rolled = execute(new TypedCommand.RollInitiative());
        assertTrue(EncounterWriter.state(jdbc, campaign).orElseThrow().playerDecision());
        return rolled;
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
        rejected(kind, new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, command.type(),
                command.payload()), command.toString());
    }

    /** The same for a raw payload no typed value can hold. */
    private void rejectsRaw(OperationAdmissionFailure.Kind kind, String type, Map<String, Object> payload) {
        rejected(kind, new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, type, payload),
                type + payload);
    }

    private void rejected(OperationAdmissionFailure.Kind kind, OperationAdmissionService.Command envelope, String label) {
        var before = world();
        var failure = assertThrows(OperationAdmissionFailure.class, () -> executions.execute(owner, adventure, campaign, envelope),
                label);
        assertEquals(kind, failure.kind(), label);
        assertEquals(before, world(), "a refusal writes nothing: " + label);
    }

    private static Map<?, ?> combat(CommandExecutionService.Execution execution) {
        return (Map<?, ?>) execution.receipt().get("combat");
    }

    private static Map<?, ?> options(CommandExecutionService.Execution execution) {
        return (Map<?, ?>) combat(execution).get("options");
    }

    private List<JsonNode> events(String type) {
        return jdbc.query("select payload::text from campaign_event where campaign_id = ? and event_type = ? order by sequence",
                (row, index) -> JSON.readTree(row.getString(1)), campaign, type);
    }

    private String visibility(String type) {
        return jdbc.queryForObject("select visibility from campaign_event where campaign_id = ? and event_type = ? "
                + "order by sequence desc limit 1", String.class, campaign, type);
    }

    private String participant(String id, String column) {
        return jdbc.queryForObject("select " + column + " from encounter_participant where campaign_id = ? and participant_id = ?",
                String.class, campaign, id);
    }

    /** The disengagement member of the stored frozen definition, or null without one. */
    private String frozenPolicy() {
        return jdbc.queryForObject("select definition->>'disengagement' from encounter where campaign_id = ?", String.class,
                campaign);
    }

    /** The player's Health pool, which the fight reads relative to its minimum. */
    private long vigor() {
        return jdbc.queryForObject("select current_value from actor_resource where campaign_id = ? and actor_id = ? "
                + "and resource_key = 'vigor'", Long.class, campaign, player);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    /** Every table a command can touch, for exact before/after comparison. */
    private Map<String, List<String>> world() {
        var tables = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("campaign", "actor", "actor_resource", "campaign_event", "operation_journal",
                "operation_random_draw", "canonical_outbox", "encounter", "encounter_participant", "item_instance",
                "equipment_assignment")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }
}
