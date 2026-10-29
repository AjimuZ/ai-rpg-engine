package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.inventory.ItemPlan;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.quests.QuestChanges;
import org.rpgengine.quests.QuestPack;
import org.rpgengine.quests.QuestPlan;
import org.rpgengine.quests.QuestStatus;
import org.rpgengine.quests.QuestWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of P10.3 on a campaign created from the sealed quest pack: derived discovery,
 * explicit accept, suspend and resume, objective predicates over visits and possessions, ordered outcomes,
 * one-time reward settlement through the shared creation ledger, hidden background quests, and the
 * all-or-nothing, replay-safe and stale-state behavior of the commit.
 */
@SpringBootTest
class QuestCommandsTest {
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
    QuestCommandsTest() { }

    /** Register the quest pack and create a campaign through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Quests", "quests", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(QuestPack.files())))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        revision = 0;
    }

    /** Remove disposable rows and any fault-injection trigger, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("drop trigger if exists test_fail_job on presentation_job");
        jdbc.execute("drop function if exists test_fail_job()");
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, "
                + "ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * Accepting a quest, reaching its place and finishing it: the objective, the ending, the one-time
     * reward, the units it creates, the discovery of a dependent quest and the start of a hidden one all
     * commit with the move, and nothing is paid again however often the player returns.
     */
    @Test
    void acceptMoveAndSettleTheRewardOnce() {
        var accepted = execute(new TypedCommand.AcceptQuest("survey"));
        assertEquals(OperationStatus.COMMITTED, accepted.operation().status());
        assertEquals(List.of(Map.of("kind", "ACCEPTED", "questId", "survey")), accepted.receipt().get("quests"));
        assertEquals(List.of("QUEST_ACCEPTED"), eventTypes());
        assertEquals("active", questStatus("survey"));
        assertEquals(0, count("reward_settlement"));

        var moved = execute(new TypedCommand.Move("grove"));
        assertEquals(OperationStatus.COMMITTED, moved.operation().status());
        assertEquals(List.of("QUEST_ACCEPTED", "ACTOR_MOVED", "QUEST_AVAILABLE", "QUEST_OBJECTIVE_SATISFIED",
                "QUEST_COMPLETED", "ITEM_CREATED", "ITEM_CREATED", "QUEST_AVAILABLE", "QUEST_ACCEPTED"), eventTypes());
        // The hidden quest's discovery and start are engine-internal; everything else is public.
        assertEquals(List.of("PUBLIC", "PUBLIC", "PUBLIC", "PUBLIC", "PUBLIC", "PUBLIC", "PUBLIC", "INTERNAL", "INTERNAL"),
                events().stream().map(row -> (String) row.get("visibility")).toList());
        assertEquals(List.of(Map.of("kind", "DISCOVERED", "questId", "gate"),
                Map.of("kind", "OBJECTIVE", "questId", "survey", "objectiveId", "reach"),
                Map.of("kind", "COMPLETED", "questId", "survey", "outcomeId", "surveyed")), moved.receipt().get("quests"));
        assertEquals(List.of(Map.of("kind", "CREATED", "definitionId", "coin", "quantity", 10L),
                Map.of("kind", "CREATED", "definitionId", "potion", "quantity", 1L)), moved.receipt().get("items"));
        assertEquals(2L, state("state_revision"));
        assertEquals(Map.of("gate", "available", "shadow", "active", "survey", "completed"), questStatuses());
        assertEquals("surveyed", jdbc.queryForObject("select outcome_id from quest_state where quest_id = 'survey'",
                String.class));
        assertEquals(List.of("survey/reach"), jdbc.queryForList("select quest_id || '/' || objective_id from quest_objective",
                String.class));
        assertEquals(Set.of("trailhead", "grove"), Set.copyOf(jdbc.queryForList("select location_id from location_visit "
                + "where actor_id = ?", String.class, player)));
        assertEquals(10, quantityHeldBy("coin"));
        assertEquals(1, quantityHeldBy("potion"));
        // One settlement for the player, and a REWARD-sourced ledger row per definition citing it.
        assertEquals(1, count("reward_settlement"));
        var settlement = jdbc.queryForMap("select source_key, recipient_key, applied_revision, rewards::text as rewards "
                + "from reward_settlement");
        assertEquals("quest:survey:outcome", settlement.get("source_key"));
        assertEquals(player.toString(), settlement.get("recipient_key"));
        assertEquals(2L, ((Number) settlement.get("applied_revision")).longValue());
        assertEquals(List.of("REWARD|quest:survey:outcome|coin|10", "REWARD|quest:survey:outcome|potion|1"),
                jdbc.queryForList("select source_kind || '|' || source_key || '|' || definition_id || '|' || quantity "
                        + "from item_creation order by definition_id", String.class));
        assertEquals(10, ledgerUnits("coin"));

        // Returning and revisiting changes no quest and pays nothing, and the ended quest cannot be accepted.
        execute(new TypedCommand.Move("trailhead"));
        execute(new TypedCommand.Move("grove"));
        assertEquals(1, count("reward_settlement"));
        assertEquals(10, quantityHeldBy("coin"));
        assertEquals(2, count("location_visit"));
        rejects(OperationAdmissionFailure.Kind.QUEST_UNAVAILABLE, new TypedCommand.AcceptQuest("survey"));

        // The database holds the line even for a writer that bypasses the engine.
        UUID operation = jdbc.queryForObject("select id from operation_journal order by committed_revision limit 1", UUID.class);
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into reward_settlement (id, campaign_id, "
                + "source_key, recipient_key, operation_id, applied_revision, rewards, settled_at) values (?, ?, "
                + "'quest:survey:outcome', ?, ?, 2, '{}'::jsonb, now())", UUID.randomUUID(), campaign, player.toString(),
                operation));
        assertThrows(DataAccessException.class, () -> jdbc.update("update quest_state set status = 'failed', "
                + "outcome_id = 'lost', row_version = row_version + 1 where quest_id = 'survey'"));
    }

    /** An ending that needs a suspended quest waits: suspended quests track nothing until they are resumed. */
    @Test
    void suspendedQuestTracksNothingUntilResumed() {
        execute(new TypedCommand.AcceptQuest("survey"));
        execute(new TypedCommand.SuspendQuest("survey"));
        assertEquals("suspended", questStatus("survey"));
        execute(new TypedCommand.Move("grove"));
        // The grove was reached, yet the suspended quest recorded nothing and paid nothing.
        assertEquals(List.of("QUEST_ACCEPTED", "QUEST_SUSPENDED", "ACTOR_MOVED", "QUEST_AVAILABLE"), eventTypes());
        assertEquals("suspended", questStatus("survey"));
        assertEquals(0, count("quest_objective"));
        assertEquals(0, count("reward_settlement"));
        var resumed = execute(new TypedCommand.ResumeQuest("survey"));
        assertEquals(List.of("QUEST_RESUMED", "QUEST_OBJECTIVE_SATISFIED", "QUEST_COMPLETED", "ITEM_CREATED",
                "ITEM_CREATED", "QUEST_AVAILABLE", "QUEST_ACCEPTED"), eventTypes().subList(4, 11));
        assertEquals(List.of(Map.of("kind", "RESUMED", "questId", "survey"),
                Map.of("kind", "OBJECTIVE", "questId", "survey", "objectiveId", "reach"),
                Map.of("kind", "COMPLETED", "questId", "survey", "outcomeId", "surveyed")), resumed.receipt().get("quests"));
        assertEquals("completed", questStatus("survey"));
        assertEquals(1, count("reward_settlement"));
    }

    /** Refusals share one category, hide undiscovered and hidden quests, and write nothing. */
    @Test
    void refusalsWriteNothingAndHideUnknownQuests() {
        var unavailable = OperationAdmissionFailure.Kind.QUEST_UNAVAILABLE;
        rejects(unavailable, new TypedCommand.AcceptQuest("nope"));
        rejects(unavailable, new TypedCommand.AcceptQuest("gate"));     // undiscovered
        rejects(unavailable, new TypedCommand.AcceptQuest("shadow"));   // hidden background quest
        rejects(unavailable, new TypedCommand.SuspendQuest("survey"));  // available, not active
        rejects(unavailable, new TypedCommand.ResumeQuest("survey"));   // available, not suspended
        execute(new TypedCommand.AcceptQuest("survey"));
        rejects(unavailable, new TypedCommand.AcceptQuest("survey"));   // already active
        rejects(unavailable, new TypedCommand.ResumeQuest("survey"));
        execute(new TypedCommand.SuspendQuest("survey"));
        rejects(unavailable, new TypedCommand.SuspendQuest("survey"));
        execute(new TypedCommand.ResumeQuest("survey"));
        execute(new TypedCommand.Move("grove"));
        for (String id : List.of("survey", "shadow")) {
            rejects(unavailable, new TypedCommand.AcceptQuest(id));
            rejects(unavailable, new TypedCommand.SuspendQuest(id));
            rejects(unavailable, new TypedCommand.ResumeQuest(id));
        }
        // The hidden quest runs by itself and is invisible to the player's commands, receipts and events.
        assertEquals("active", questStatus("shadow"));
        assertEquals(2, count("campaign_event where visibility = 'INTERNAL' and payload->>'questId' = 'shadow'"));
    }

    /**
     * An item effect and the quest it finishes commit together: the press creates the missing oil, the
     * objective and ending follow from the new possession, and the reward is a second, separately
     * sourced creation in the same ledger, with units conserved per definition.
     */
    @Test
    void itemEffectAndQuestEndingShareOneCommit() {
        UUID press = give("oil-press", 1, 1);
        execute(new TypedCommand.AcceptQuest("supply"));
        var first = execute(new TypedCommand.UseItem(press, List.of()));
        assertNull(first.receipt().get("quests"), "no quest moved, so the receipt keeps its earlier shape");
        assertEquals(2, quantityHeldBy("lamp-oil"));
        var second = execute(new TypedCommand.UseItem(press, List.of()));
        assertEquals(List.of("ITEM_USED", "ITEM_CREATED", "QUEST_OBJECTIVE_SATISFIED", "QUEST_COMPLETED", "ITEM_CREATED"),
                eventTypes().subList(3, 8));
        assertEquals(List.of(Map.of("kind", "OBJECTIVE", "questId", "supply", "objectiveId", "stock"),
                Map.of("kind", "COMPLETED", "questId", "supply", "outcomeId", "stocked")), second.receipt().get("quests"));
        assertEquals(List.of(Map.of("kind", "CREATED", "definitionId", "lamp-oil", "quantity", 2L),
                Map.of("kind", "CREATED", "definitionId", "coin", "quantity", 7L)), second.receipt().get("items"));
        // Rewards naming the same definition merge into one ledger row and one stack.
        assertEquals(7, quantityHeldBy("coin"));
        assertEquals(7, ledgerUnits("coin"));
        assertEquals(4, ledgerUnits("lamp-oil"));
        assertEquals(List.of("EFFECT", "EFFECT", "REWARD"),
                jdbc.queryForList("select source_kind from item_creation order by source_kind", String.class));
        assertEquals(1, count("reward_settlement"));
        assertEquals("completed", questStatus("supply"));
    }

    /**
     * One command can finish a chain: the oil finishes the gate quest, its coins finish the hidden quest,
     * whose relic reward is paid, all in one commit. The hidden quest's progress stays internal and only
     * its ending is public.
     */
    @Test
    void rewardsFeedOtherQuestsInTheSameCommit() {
        execute(new TypedCommand.AcceptQuest("survey"));
        execute(new TypedCommand.Move("grove"));
        execute(new TypedCommand.AcceptQuest("gate"));
        UUID press = give("oil-press", 1, 1);
        long before = jdbc.queryForObject("select coalesce(max(sequence), 0) from campaign_event where campaign_id = ?",
                Long.class, campaign);
        var used = execute(new TypedCommand.UseItem(press, List.of()));
        assertEquals(List.of("ITEM_USED", "ITEM_CREATED", "QUEST_OBJECTIVE_SATISFIED", "QUEST_COMPLETED", "ITEM_CREATED",
                "QUEST_OBJECTIVE_SATISFIED", "QUEST_COMPLETED", "ITEM_CREATED"),
                jdbc.queryForList("select event_type from campaign_event where campaign_id = ? and sequence > ? "
                        + "order by sequence", String.class, campaign, before));
        assertEquals(List.of(Map.of("kind", "OBJECTIVE", "questId", "gate", "objectiveId", "gather"),
                Map.of("kind", "COMPLETED", "questId", "gate", "outcomeId", "lit"),
                Map.of("kind", "COMPLETED", "questId", "shadow", "outcomeId", "found")), used.receipt().get("quests"));
        assertEquals(15, quantityHeldBy("coin"));
        assertEquals(1, quantityHeldBy("relic"));
        assertEquals(Map.of("gate", "completed", "shadow", "completed", "survey", "completed"), questStatuses());
        assertEquals(3, count("reward_settlement"));
        // The hidden quest's objective event is internal; its ending is the first thing the player learns of it.
        assertEquals(List.of("INTERNAL", "PUBLIC"), jdbc.queryForList("select visibility from campaign_event where campaign_id = ? "
                + "and payload->>'questId' = 'shadow' and event_type in ('QUEST_OBJECTIVE_SATISFIED', 'QUEST_COMPLETED') "
                + "order by sequence", String.class, campaign));
    }

    /**
     * A failure after the quest rows and the reward were written (the presentation job insert) rolls back
     * everything: rows, ledger, settlement, events and revision. With the fault gone a fresh request
     * succeeds and pays once.
     */
    @Test
    void faultAfterQuestWritesLeavesNoPartialEffect() {
        execute(new TypedCommand.AcceptQuest("survey"));
        jdbc.execute("create function test_fail_job() returns trigger language plpgsql as $$ begin "
                + "raise exception 'injected job failure' using errcode = '23514'; end; $$");
        jdbc.execute("create trigger test_fail_job before insert on presentation_job for each row execute function test_fail_job()");
        var before = effects();
        var failed = execute(new TypedCommand.Move("grove"));
        assertEquals(OperationStatus.FAILED_BEFORE_COMMIT, failed.operation().status());
        assertEquals(before, effects());
        assertEquals(0, count("reward_settlement"));
        assertEquals(0, count("item_creation"));
        assertEquals(0, count("location_visit"));
        assertEquals("active", questStatus("survey"));
        jdbc.execute("drop trigger test_fail_job on presentation_job");
        assertEquals(OperationStatus.COMMITTED, execute(new TypedCommand.Move("grove")).operation().status());
        assertEquals(1, count("reward_settlement"));
        assertEquals(10, quantityHeldBy("coin"));
    }

    /** A lost response is answered from the journal: the retry never settles, discovers or records a second time. */
    @Test
    void matchingRetryReplaysWithoutRepeating() {
        execute(new TypedCommand.AcceptQuest("survey"));
        var input = envelope(new TypedCommand.Move("grove"));
        var first = executions.execute(owner, adventure, campaign, input);
        revision = first.operation().committedRevision();
        var before = world();
        var replay = executions.execute(owner, adventure, campaign, input);
        assertTrue(replay.replayed());
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(before, world());
        assertEquals(1, count("reward_settlement"));
    }

    /**
     * The writer refuses plans resolved from state that moved on: a wrong storage version, a second insert
     * of a stored quest, any change of an ended quest, and a repeated objective; each leaves the rows as they were.
     */
    @Test
    void writerRefusesStaleOrEndedQuests() {
        execute(new TypedCommand.AcceptQuest("survey"));
        execute(new TypedCommand.Move("grove"));
        long ended = jdbc.queryForObject("select row_version from quest_state where quest_id = 'survey'", Long.class);
        long active = jdbc.queryForObject("select row_version from quest_state where quest_id = 'shadow'", Long.class);
        var stale = new QuestChanges(List.of(new QuestChanges.StateWrite("shadow", active + 1, QuestStatus.ACTIVE, null, 0)),
                List.of(), List.of());
        var reinsert = new QuestChanges(List.of(new QuestChanges.StateWrite("shadow", QuestPlan.State.ABSENT,
                QuestStatus.ACTIVE, null, 0)), List.of(), List.of());
        var reopen = new QuestChanges(List.of(new QuestChanges.StateWrite("survey", ended, QuestStatus.ACTIVE, null, 0)),
                List.of(), List.of());
        var repeated = new QuestChanges(List.of(), List.of(new QuestChanges.ObjectiveWrite("survey", "reach", 0)), List.of());
        var orphan = new QuestChanges(List.of(), List.of(new QuestChanges.ObjectiveWrite("unknown", "reach", 0)), List.of());
        var before = world();
        assertThrows(IllegalStateException.class, () -> inTransaction(stale));
        assertThrows(DataAccessException.class, () -> inTransaction(reinsert));
        assertThrows(DataAccessException.class, () -> inTransaction(reopen));
        assertThrows(DataAccessException.class, () -> inTransaction(repeated));
        assertThrows(DataAccessException.class, () -> inTransaction(orphan));
        assertEquals(before, world());
        // A visit that is already stored is skipped, never duplicated or changed.
        inTransaction(new QuestChanges(List.of(), List.of(), List.of(new QuestChanges.VisitWrite(player, "grove"))));
        assertEquals(before, world());
    }

    /**
     * Resolved quest effects may only touch the intention's participants and must cite events inside the
     * plan: a forged reward for, or visit by, an uninvolved actor is refused before anything is written, and a
     * source cannot settle twice for one recipient inside one plan.
     */
    @Test
    void attributionRefusesQuestEffectsOnUninvolvedActors() {
        UUID bystander = UUID.randomUUID();
        var world = new ResolvedCommit.WorldChange(true, 0, CampaignMode.EXPLORATION, null);
        var context = new ResolvedCommit.Context(adventure, campaign, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
                0, 2, new MutationLeaseService.Token(UUID.randomUUID(), 1));
        var event = new ResolvedCommit.Event(player, null, EventVisibility.PUBLIC,
                new CampaignEvent.Body("QUEST_COMPLETED", 1, 1, "{}"));
        java.util.function.BiFunction<List<ResolvedCommit.Reward>, QuestChanges, ResolvedCommit> plan =
                (rewards, quests) -> new ResolvedCommit(context, world, List.of(), List.of(event), rewards, "{}", false,
                        ResolvedCommit.Preparation.NONE, null, List.of(), List.of(), ItemPlan.NONE, quests);
        var participants = Set.of(player);
        var own = new ResolvedCommit.Reward("quest:q:outcome", player.toString(), "{}");
        CommandRules.requireAttributed(jdbc, campaign, participants,
                plan.apply(List.of(own), new QuestChanges(List.of(), List.of(),
                        List.of(new QuestChanges.VisitWrite(player, "grove")))));
        assertThrows(IllegalStateException.class, () -> CommandRules.requireAttributed(jdbc,
                campaign, participants, plan.apply(List.of(new ResolvedCommit.Reward("quest:q:outcome",
                        bystander.toString(), "{}")), QuestChanges.NONE)));
        assertThrows(IllegalStateException.class, () -> CommandRules.requireAttributed(jdbc,
                campaign, participants, plan.apply(List.of(), new QuestChanges(List.of(), List.of(),
                        List.of(new QuestChanges.VisitWrite(bystander, "grove"))))));
        assertThrows(IllegalArgumentException.class, () -> plan.apply(List.of(own, own), QuestChanges.NONE));
        assertThrows(IllegalArgumentException.class, () -> plan.apply(List.of(), new QuestChanges(List.of(
                new QuestChanges.StateWrite("q", QuestPlan.State.ABSENT, QuestStatus.ACTIVE, null, 1)), List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> plan.apply(List.of(), new QuestChanges(List.of(), List.of(
                new QuestChanges.ObjectiveWrite("q", "o", 1)), List.of())));
    }

    /** Run quest writes through the writer in a transaction that the caller expects to be thrown away or no-op. */
    private void inTransaction(QuestChanges changes) {
        new TransactionTemplate(manager).executeWithoutResult(status ->
                QuestWriter.applyWithin(jdbc, campaign, 99, 1, changes));
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private CommandExecutionService.Execution execute(TypedCommand command) {
        var execution = executions.execute(owner, adventure, campaign, envelope(command));
        if (execution.operation().committedRevision() != null) {
            revision = execution.operation().committedRevision();
        }
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

    /** Insert a stack held by the player, outside any command (a test-only way to stock the inventory). */
    private UUID give(String definition, int quantity, int maxStack) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into item_instance (id, campaign_id, actor_id, definition_id, quantity, maximum_stack, "
                + "acquired_revision) values (?, ?, ?, ?, ?, ?, 0)", id, campaign, player, definition, quantity, maxStack);
        return id;
    }

    private int quantityHeldBy(String definition) {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from item_instance where campaign_id = ? "
                + "and actor_id = ? and definition_id = ?", Integer.class, campaign, player, definition);
    }

    private int ledgerUnits(String definition) {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from item_creation where campaign_id = ? "
                + "and definition_id = ?", Integer.class, campaign, definition);
    }

    private String questStatus(String quest) {
        return jdbc.queryForObject("select status from quest_state where quest_id = ?", String.class, quest);
    }

    private Map<String, String> questStatuses() {
        var statuses = new java.util.TreeMap<String, String>();
        jdbc.query("select quest_id, status from quest_state", row -> {
            statuses.put(row.getString(1), row.getString(2));
        });
        return statuses;
    }

    private long state(String column) {
        return jdbc.queryForObject("select " + column + " from campaign where id = ?", Long.class, campaign);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("select event_type, visibility, payload::text as payload from campaign_event "
                + "where campaign_id = ? order by sequence", campaign);
    }

    private List<String> eventTypes() {
        return events().stream().map(row -> (String) row.get("event_type")).toList();
    }

    /** Every table the quest commands can touch, with storage versions, for exact before/after comparison. */
    private Map<String, List<String>> world() {
        return tables(List.of("campaign", "actor", "actor_resource", "item_instance", "item_creation", "campaign_event",
                "operation_journal", "canonical_outbox", "query_epoch", "quest_state", "quest_objective",
                "location_visit", "reward_settlement"));
    }

    /** The same comparison without the journal, which legitimately gains a row when an operation fails after admission. */
    private Map<String, List<String>> effects() {
        return tables(List.of("campaign", "actor", "actor_resource", "item_instance", "item_creation", "campaign_event",
                "canonical_outbox", "query_epoch", "quest_state", "quest_objective", "location_visit", "reward_settlement"));
    }

    private Map<String, List<String>> tables(List<String> names) {
        var tables = new java.util.LinkedHashMap<String, List<String>>();
        for (String table : names) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }

    /** Guard against a vacuous comparison: the snapshots really cover quest rows. */
    @Test
    void worldSnapshotCoversQuestRows() {
        var before = world();
        execute(new TypedCommand.AcceptQuest("survey"));
        assertNotEquals(before, world());
        assertFalse(world().get("quest_state").isEmpty());
    }
}
