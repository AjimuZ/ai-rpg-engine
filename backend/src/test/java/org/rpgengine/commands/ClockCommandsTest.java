package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Instant;
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
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.rules.ClockPack;
import org.rpgengine.rules.WorldClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of P10.4 on a campaign created from the sealed clock pack: travel takes the
 * authored duration of its link, waiting is bounded, a rest restores resources, ends conditions and can be
 * interrupted by a recorded draw, a clock-driven quest completes as time passes, the clock never passes its
 * end, and a refused or failed command leaves the clock untouched.
 */
@SpringBootTest
class ClockCommandsTest {
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
    ClockCommandsTest() { }

    /** Register the clock pack and create a campaign through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Clock", "clock", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(ClockPack.files())))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        revision = 0;
    }

    /** Remove disposable rows, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("alter table campaign enable trigger campaign_world_time_step");
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, "
                + "ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /** A move adds exactly the authored duration of the link it follows, and the event records the clock it reached. */
    @Test
    void travelTakesTheAuthoredDurationOfItsLink() {
        assertEquals(0, clock());
        var out = execute(new TypedCommand.Move("grove"));
        assertEquals(2_700L, out.receipt().get("elapsedSeconds"));
        assertEquals(2_700, clock());
        var back = execute(new TypedCommand.Move("trailhead"));
        assertEquals(1_800L, back.receipt().get("elapsedSeconds"));
        assertEquals(4_500, clock());
        assertEquals(List.of(2_700L, 4_500L), jdbc.queryForList("select world_time_seconds from campaign_event "
                + "where campaign_id = ? and event_type = 'ACTOR_MOVED' order by sequence", Long.class, campaign));
        assertEquals(2_700, JSON.readTree((String) events().getFirst().get("payload")).get("seconds").asInt());
        // The longest legal link is a whole day and still fits one operation.
        execute(new TypedCommand.Move("far-pass"));
        assertEquals(4_500 + WorldClock.DAY, clock());
        assertEquals(3L, state("game_turn"));
    }

    /** A wait is legal up to four hours; a longer one is refused and a refusal changes nothing. */
    @Test
    void waitingIsBounded() {
        rejects(OperationAdmissionFailure.Kind.TIME_ILLEGAL, new TypedCommand.Wait(WorldClock.MAX_WAIT_SECONDS + 1));
        assertEquals(0, clock());
        var waited = execute(new TypedCommand.Wait(WorldClock.MAX_WAIT_SECONDS));
        assertEquals(14_400L, waited.receipt().get("elapsedSeconds"));
        assertEquals(14_400, clock());
        // Waiting changes no other world fact: no event but the wait itself.
        assertEquals(List.of("TIME_PASSED"), eventTypes());
    }

    /** The clock never passes its end: a step that would is refused before anything is written. */
    @Test
    void theClockStopsAtItsEnd() {
        setClock(WorldClock.END_SECONDS - 600);
        rejects(OperationAdmissionFailure.Kind.TIME_ILLEGAL, new TypedCommand.Wait(601));
        rejects(OperationAdmissionFailure.Kind.TIME_ILLEGAL, new TypedCommand.Move("grove"));
        rejects(OperationAdmissionFailure.Kind.TIME_ILLEGAL, new TypedCommand.Rest("night"));
        execute(new TypedCommand.Wait(600));
        assertEquals(WorldClock.END_SECONDS, clock());
        rejects(OperationAdmissionFailure.Kind.TIME_ILLEGAL, new TypedCommand.Wait(1));
        // A command that takes no time still works at the end of the clock.
        execute(new TypedCommand.AcceptQuest("vigil"));
        assertEquals(WorldClock.END_SECONDS, clock());
    }

    /** The database refuses time that moves without its revision, by more than a day, backwards or past the end. */
    @Test
    void theDatabaseGuardsTheClockAgainstAnyWriter() {
        execute(new TypedCommand.Wait(60));
        long base = clock();
        long revisionNow = state("state_revision");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update campaign set world_time_seconds = world_time_seconds + 5, row_version = row_version + 1 where id = ?",
                campaign));
        assertThrows(DataAccessException.class, () -> jdbc.update("update campaign set world_time_seconds = ?, "
                + "state_revision = state_revision + 1, row_version = row_version + 1 where id = ?",
                base + WorldClock.DAY + 1, campaign));
        assertThrows(DataAccessException.class, () -> jdbc.update("update campaign set world_time_seconds = ?, "
                + "state_revision = state_revision + 1, row_version = row_version + 1 where id = ?",
                WorldClock.END_SECONDS + 1, campaign));
        assertEquals(base, clock());
        assertEquals(revisionNow, state("state_revision"));
        // One revision may add up to a day, and an update that leaves time alone is unaffected.
        assertEquals(1, jdbc.update("update campaign set world_time_seconds = ?, state_revision = state_revision + 1, "
                + "row_version = row_version + 1 where id = ?", base + WorldClock.DAY, campaign));
        assertThrows(DataAccessException.class, () -> jdbc.update("update campaign set world_time_seconds = 1, "
                + "state_revision = state_revision + 1, row_version = row_version + 1 where id = ?", campaign));
        assertThrows(DataAccessException.class, () -> jdbc.update("update campaign_event set world_time_seconds = ? "
                + "where campaign_id = ?", WorldClock.END_SECONDS + 1, campaign));
    }

    /**
     * An uninterrupted rest at a shelter restores to the pool maximum, ends the listed conditions and only those,
     * takes its full duration and reports all of it in the receipt.
     */
    @Test
    void aRestRestoresResourcesAndEndsConditions() {
        setVigor(2);
        condition("dazed");
        condition("rattled");
        condition("steadfast");
        var rested = execute(new TypedCommand.Rest("night"));
        assertEquals(OperationStatus.COMMITTED, rested.operation().status());
        assertEquals(28_800L, rested.receipt().get("elapsedSeconds"));
        assertEquals(28_800, clock());
        assertEquals(8, vigor());
        assertEquals(List.of("steadfast"), jdbc.queryForList("select condition_id from actor_condition where actor_id = ?",
                String.class, player));
        assertEquals(List.of("REST_COMPLETED", "RESOURCE_RECOVERED", "CONDITION_REMOVED", "CONDITION_REMOVED"), eventTypes());
        assertEquals(Map.of("restId", "night", "interrupted", false, "seconds", 28_800L,
                "conditionsEnded", List.of("dazed", "rattled")), rested.receipt().get("rest"));
        assertEquals(List.of(Map.of("resourceKey", "vigor", "before", 2L, "after", 8L)), rested.receipt().get("resources"));
        assertEquals(List.of(), rested.receipt().get("randomDraws"), "a policy that cannot be interrupted draws nothing");
        // A second rest has nothing to restore or remove, yet still takes its time: it is an action.
        var again = execute(new TypedCommand.Rest("night"));
        assertEquals(List.of("REST_COMPLETED"), eventTypes().subList(4, 5));
        assertEquals(57_600, clock());
        assertEquals(List.of(), again.receipt().get("resources"));
    }

    /** A short rest restores only what the pool can still take. */
    @Test
    void restorationStopsAtThePoolMaximum() {
        setVigor(7);
        var rested = execute(new TypedCommand.Rest("catch-breath"));
        assertEquals(8, vigor());
        assertEquals(600L, rested.receipt().get("elapsedSeconds"));
        assertEquals(List.of(Map.of("resourceKey", "vigor", "before", 7L, "after", 8L)), rested.receipt().get("resources"));
    }

    /** The one interruption draw decides the outcome: half the time, half the restoration and no condition removed. */
    @Test
    void anInterruptedRestFollowsItsRecordedDraw() {
        setVigor(0);
        condition("dazed");
        var rested = execute(new TypedCommand.Rest("uneasy"));
        @SuppressWarnings("unchecked")
        var draws = (List<Map<String, Object>>) rested.receipt().get("randomDraws");
        assertEquals(1, draws.size());
        assertEquals(100, draws.getFirst().get("sides"));
        int draw = (Integer) draws.getFirst().get("value");
        boolean interrupted = draw <= 99;
        @SuppressWarnings("unchecked")
        var rest = (Map<String, Object>) rested.receipt().get("rest");
        assertEquals(interrupted, rest.get("interrupted"));
        if (interrupted) {
            assertEquals(14_400L, rested.receipt().get("elapsedSeconds"));
            assertEquals(4, vigor());
            assertEquals(1, count("actor_condition"));
            assertEquals(List.of("REST_INTERRUPTED", "RESOURCE_RECOVERED"), eventTypes());
        } else {
            assertEquals(28_800L, rested.receipt().get("elapsedSeconds"));
            assertEquals(8, vigor());
            assertEquals(0, count("actor_condition"));
        }
        // The same request replays the same receipt and never draws or restores twice.
        var before = clock();
        var replay = executions.execute(owner, adventure, campaign, lastEnvelope);
        assertTrue(replay.replayed());
        assertEquals(rested.receipt(), replay.receipt());
        assertEquals(before, clock());
    }

    /** Rest refusals share a category per cause and write nothing. */
    @Test
    void restRefusalsWriteNothing() {
        rejects(OperationAdmissionFailure.Kind.REST_UNAVAILABLE, new TypedCommand.Rest("missing"));
        execute(new TypedCommand.Move("camp"));
        // The wild camp carries no shelter tag, so the night's rest cannot be taken there.
        rejects(OperationAdmissionFailure.Kind.REST_UNAVAILABLE, new TypedCommand.Rest("night"));
        execute(new TypedCommand.Rest("catch-breath"));
        assertEquals(7_200 + 600, clock());
    }

    /** A clock threshold in a quest predicate becomes true as committed time passes, and a query cannot cause it. */
    @Test
    void aClockQuestCompletesAsTimePasses() {
        execute(new TypedCommand.AcceptQuest("vigil"));
        execute(new TypedCommand.Wait(WorldClock.MAX_WAIT_SECONDS));
        assertEquals("active", questStatus("vigil"));
        assertEquals(0, count("reward_settlement"));
        // Seven hours have passed; one more hour crosses the threshold in the command that takes it.
        execute(new TypedCommand.Wait(WorldClock.MAX_WAIT_SECONDS));
        assertEquals("completed", questStatus("vigil"));
        assertEquals(1, count("reward_settlement"));
        assertEquals(28_800, clock());
        execute(new TypedCommand.Wait(60));
        assertEquals(1, count("reward_settlement"));
    }

    /** A rest can finish a clock quest in the same commit that moves the clock, paying its reward once. */
    @Test
    void aRestCanCompleteAClockQuestInOneCommit() {
        execute(new TypedCommand.AcceptQuest("vigil"));
        var rested = execute(new TypedCommand.Rest("night"));
        assertEquals("completed", questStatus("vigil"));
        assertEquals(List.of("REST_COMPLETED", "QUEST_OBJECTIVE_SATISFIED", "QUEST_COMPLETED", "ITEM_CREATED"),
                eventTypes().subList(1, 5).stream().filter(type -> !type.equals("RESOURCE_RECOVERED")).toList());
        assertEquals(3, jdbc.queryForObject("select coalesce(sum(quantity), 0) from item_instance where actor_id = ? "
                + "and definition_id = 'coin'", Integer.class, player));
        assertNotEquals(0, rested.receipt().get("quests").toString().length());
    }

    /** A failure after the clock changed in memory leaves the committed clock and the events untouched. */
    @Test
    void aFailedCommitDoesNotMoveTheClock() {
        jdbc.execute("create or replace function test_fail_job() returns trigger language plpgsql as $$ begin "
                + "raise exception 'injected failure'; end $$");
        jdbc.execute("create trigger test_fail_job before insert on presentation_job for each row "
                + "execute function test_fail_job()");
        try {
            var before = clock();
            var failed = executions.execute(owner, adventure, campaign, envelope(new TypedCommand.Wait(600)));
            assertNotEquals(OperationStatus.COMMITTED, failed.operation().status());
            assertEquals(before, clock());
            assertEquals(0, count("campaign_event"));
            assertEquals(0L, state("state_revision"));
        } finally {
            jdbc.execute("drop trigger if exists test_fail_job on presentation_job");
            jdbc.execute("drop function if exists test_fail_job()");
        }
    }

    /** A forged plan cannot end a condition of an actor the rest does not involve, and nothing is written. */
    @Test
    void attributionRefusesConditionRemovalOfAnUninvolvedActor() {
        UUID bystander = UUID.randomUUID();
        var world = new ResolvedCommit.WorldChange(true, 600, org.rpgengine.campaign.CampaignMode.EXPLORATION, null);
        var context = new ResolvedCommit.Context(adventure, campaign, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
                0, 2, new MutationLeaseService.Token(UUID.randomUUID(), 1));
        var event = new ResolvedCommit.Event(player, null, org.rpgengine.campaign.EventVisibility.PUBLIC,
                new org.rpgengine.campaign.CampaignEvent.Body("REST_COMPLETED", 1, 1, "{}"));
        java.util.function.Function<UUID, ResolvedCommit> plan = actor -> new ResolvedCommit(context, world, List.of(),
                List.of(event), List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), List.of(),
                org.rpgengine.inventory.ItemPlan.NONE, org.rpgengine.quests.QuestChanges.NONE,
                List.of(new ResolvedCommit.ConditionRemoval(actor, "dazed")));
        var before = world();
        CommandRules.requireAttributed(jdbc, campaign, java.util.Set.of(player), plan.apply(player));
        assertThrows(IllegalStateException.class, () -> CommandRules.requireAttributed(jdbc, campaign,
                java.util.Set.of(player), plan.apply(bystander)));
        assertEquals(before, world());
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private OperationAdmissionService.Command lastEnvelope;

    private CommandExecutionService.Execution execute(TypedCommand command) {
        lastEnvelope = envelope(command);
        var execution = executions.execute(owner, adventure, campaign, lastEnvelope);
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

    /** Put the committed clock somewhere else without a revision change: the guard trigger is lifted for this write only. */
    private void setClock(long seconds) {
        jdbc.execute("alter table campaign disable trigger campaign_world_time_step");
        jdbc.update("update campaign set world_time_seconds = ?, row_version = row_version + 1 where id = ?", seconds, campaign);
        jdbc.execute("alter table campaign enable trigger campaign_world_time_step");
    }

    private void setVigor(long value) {
        jdbc.update("update actor_resource set current_value = ?, row_version = row_version + 1 where actor_id = ? "
                + "and resource_key = 'vigor'", value, player);
    }

    private long vigor() {
        return jdbc.queryForObject("select current_value from actor_resource where actor_id = ? and resource_key = 'vigor'",
                Long.class, player);
    }

    private void condition(String id) {
        jdbc.update("insert into actor_condition (campaign_id, actor_id, condition_id, stacks, remaining_rounds, "
                + "applied_revision, updated_revision) values (?, ?, ?, 1, 3, 0, 0)", campaign, player, id);
    }

    private String questStatus(String quest) {
        return jdbc.queryForObject("select status from quest_state where quest_id = ?", String.class, quest);
    }

    private long clock() {
        return state("world_time_seconds");
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

    /** Every table a command can touch, for exact before/after comparison. */
    private Map<String, List<String>> world() {
        var tables = new java.util.LinkedHashMap<String, List<String>>();
        for (String table : List.of("campaign", "actor", "actor_resource", "actor_condition", "item_instance",
                "campaign_event", "operation_journal", "canonical_outbox", "query_epoch", "quest_state", "reward_settlement")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }

    /** Guard against a vacuous comparison: the snapshot really covers the clock. */
    @Test
    void worldSnapshotCoversTheClock() {
        var before = world();
        execute(new TypedCommand.Wait(60));
        assertNotEquals(before, world());
        assertFalse(world().get("campaign").isEmpty());
    }
}
