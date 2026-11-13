package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.rpgengine.combat.EncounterPhase;
import org.rpgengine.combat.EncounterState;
import org.rpgengine.combat.EncounterWriter;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.Rehydrator;
import org.rpgengine.inference.session.SessionScope;
import org.rpgengine.memory.MemoryReconciliation;
import org.rpgengine.memory.OperationalCheckpoints;
import org.rpgengine.memory.OperationalCheckpoints.Capture;
import org.rpgengine.memory.OperationalCheckpoints.Verification;
import org.rpgengine.memory.SessionRehydration;
import org.rpgengine.presentation.PresentationJobService;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of P11.6-A (docs/11 sections 2 and 6, docs/08 section 10, QA-09, QA-12, ADR 0037) on campaigns
 * created from the sealed tactics fixture. The operational checkpoint records a fight stopped inside a round with every cursor
 * the scheduler reads; a restore's retirement step and the engine's startup passes leave that fight, the pools, the items and
 * the draws exactly as they were, so the fight continues from the same action points, round and effect clocks, and an attempt
 * cut off before its commit finds its own draws again; renewals in the middle of a fight write no canonical row, so each effect
 * clock ticks once per round end and a retried commit ticks nothing again; a fight decided inside a round settles that round's
 * seconds once, at the boundary its checkpoint recorded. The rows are not dumped and reloaded here: that is the backup tool's
 * own rehearsal, which holds no encounter.
 */
@SpringBootTest
class CombatRecoveryTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PACK_HASH = "c".repeat(64);
    private static final String POLICY_HASH = "d".repeat(64);
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private OperationalCheckpoints checkpoints;
    @Autowired private MemoryReconciliation reconciliation;
    @Autowired private JournalRecoveryService journal;
    @Autowired private PresentationJobService presentation;
    @Autowired private SessionRehydration rehydration;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private UUID player;
    private long revision;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    CombatRecoveryTest() { }

    /** Register the tactics pack and create a campaign at the trailhead through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Recovery", "recovery", SupportedLocale.EN, Instant.now());
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

    /** Remove disposable rows and the injected failure, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("drop trigger if exists test_recovery_cut on campaign_event");
        jdbc.execute("drop function if exists test_recovery_cut()");
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
     * Inside a round, the checkpoint holds what the scheduler reads: phase, round, cursor, the points the Guard action left,
     * speech, the application counter, the round's boundary on the world clock, and per participant initiative, rank,
     * reaction budget, zone, standing, stance, effect clocks and local Health, beside the player's pool and the draw count.
     * An effect clock changed outside a command at the same revision is a divergence that reconciliation replaces.
     */
    @Test
    void theCheckpointRecordsEveryCursorOfAFightInsideARound() {
        begin("venom-pit");
        survivable();
        execute(new TypedCommand.EndActivation());
        execute(new TypedCommand.Guard());
        var state = state();
        assertTrue(state.playerDecision());
        assertEquals(2, state.actionPoints(), "three points less the Guard action's one");
        assertTrue(clocks(state.player()).stream().anyMatch(clock -> clock.startsWith("poisoned:")), "the adder's fang poisons");
        assertEquals(Capture.Outcome.CAPTURED, checkpoints.capture(campaign).outcome());
        assertEquals(Verification.Status.VALID, checkpoints.verify(campaign).status());

        JsonNode snapshot = latestSnapshot();
        JsonNode fight = snapshot.path("encounter");
        assertEquals(state.id().toString(), fight.path("id").asString());
        assertEquals("AWAITING_ACTION", fight.path("phase").asString());
        assertEquals(state.round(), fight.path("round").asInt());
        assertEquals(state.cursor().intValue(), fight.path("actorCursor").asInt());
        assertEquals(state.actionPoints().intValue(), fight.path("actionPoints").asInt());
        assertEquals(state.speechUsed(), fight.path("speechUsed").asInt());
        assertEquals(state.effectsApplied(), fight.path("effectsApplied").asInt());
        assertEquals(8, fight.path("roundSeconds").asInt());
        assertEquals(clock() + 8, fight.path("roundEndsAt").asLong(), "the round in progress still owes its eight seconds");
        JsonNode roster = fight.path("participants");
        assertEquals(state.participants().size(), roster.size());
        for (int index = 0; index < roster.size(); index++) {
            var expected = state.participants().get(index);
            JsonNode recorded = roster.get(index);
            assertEquals(expected.id(), recorded.path("id").asString());
            assertEquals(expected.initiative().intValue(), recorded.path("initiative").asInt(), expected.id());
            assertEquals(expected.rank().intValue(), recorded.path("initiativeRank").asInt(), expected.id());
            assertEquals(expected.reactions(), recorded.path("reactions").asInt(), expected.id());
            assertEquals(expected.zone(), recorded.path("zone").asString(), expected.id());
            assertEquals(expected.status().name(), recorded.path("status").asString(), expected.id());
            assertEquals(expected.guarding(), recorded.path("guarding").asBoolean(), expected.id());
            assertEquals(clocks(expected), clocks(snapshot, expected.id()), expected.id());
        }
        assertTrue(roster.get(0).path("guarding").asBoolean(), "the player's Guard stance");
        assertTrue(roster.get(0).path("health").isNull(), "the player's Health stays in its pool");
        assertEquals(state.participant("adder").health(), roster.get(1).path("health").asInt());
        assertEquals(pool("vigor"), resource(snapshot, "vigor"));
        assertEquals(drawCount(), snapshot.path("rng").path("draws").asLong());

        jdbc.update("update encounter_participant set effects = jsonb_set(effects, '{0,remaining}', '9'), "
                + "row_version = row_version + 1 where participant_id = 'player'");
        assertEquals(Verification.Status.DIVERGED, checkpoints.verify(campaign).status(), "the stored clock no longer matches");
        var repaired = reconciliation.reconcile(campaign).checkpoint();
        assertEquals(List.of(Verification.Status.DIVERGED), repaired.discarded());
        assertEquals(Verification.Status.VALID, repaired.now());
        assertEquals(9, latestSnapshot().path("encounter").path("participants").get(0).path("effects").get(0)
                .path("remaining").asInt(), "the new checkpoint records the clock as it now stands");
    }

    /**
     * QA-09: a restore taken inside a round, with a strike cut off before its commit, changes no row of the fight, the pools,
     * the items or the draws. After the engine's startup passes the checkpoint differs only in the status of the narration
     * those passes retired, so its encounter, resources, items and RNG members are the same. The fight then goes on from
     * those cursors: the strike asked again finds the draws the cut
     * attempt reserved, spends two of the three points the restore kept, the restored round is the one that ends, the poison
     * held through the restore ticks exactly once at that end, and nobody rolls initiative again.
     */
    @Test
    void aRestoreInsideARoundKeepsEveryCursorAndTheFightContinuesFromThem() {
        begin("venom-pit");
        survivable();
        execute(new TypedCommand.EndActivation());
        var strike = new TypedCommand.Strike("sword", "adder");
        var cut = interrupted(strike);
        String cutDraws = draws(cut.operation().operationId());
        assertNotNull(cutDraws, "the cut attempt reserved its dice before the commit failed");
        var before = state();
        assertTrue(before.playerDecision());
        assertEquals(3, before.actionPoints(), "the cut attempt spent nothing");
        assertTrue(clocks(before.player()).stream().anyMatch(clock -> clock.startsWith("poisoned:")));
        int ticks = lines("player", "TICK").size();
        String initiative = initiative();
        long generation = generation();
        assertEquals(Capture.Outcome.CAPTURED, checkpoints.capture(campaign).outcome());
        JsonNode taken = latestSnapshot();
        var world = world();

        restore();

        assertEquals(generation + 1, generation(), "the restore retired the old generation");
        assertEquals(world, world(), "neither the restore nor the startup passes change the fight, pools, items or draws");
        var reconciled = reconciliation.reconcile(campaign).checkpoint();
        // The startup passes retired the narration still queued at the stop, whose status the snapshot has listed since
        // V16, so the checkpoint taken before is replaced; that is the only member that differs.
        assertEquals(List.of(Verification.Status.DIVERGED), reconciled.discarded());
        assertEquals(Verification.Status.VALID, reconciled.now());
        JsonNode now = latestSnapshot();
        var differing = new ArrayList<String>();
        for (String member : taken.propertyNames()) {
            if (!taken.path(member).equals(now.path(member))) {
                differing.add(member);
            }
        }
        assertEquals(List.of("pendingNarration"), differing);
        assertEquals(List.copyOf(taken.propertyNames()), List.copyOf(now.propertyNames()));

        var retried = execute(strike);
        assertEquals(cutDraws, draws(retried.operation().operationId()), "a restore is not a reroll");
        assertEquals(1, state().actionPoints(), "the strike's two points, from the three the restore kept");
        execute(new TypedCommand.EndActivation());
        assertEquals(1, latestOf("ACTIVATION_ENDED", "player").path("unusedActionPoints").asInt());
        assertEquals(before.round(), latest("ROUND_ENDED").path("round").asInt(), "the restored round is the one that ends");
        assertEquals(before.round() + 1, state().round());
        assertEquals(ticks + 1, lines("player", "TICK").size(), "the poison held through the restore ticks once at that end");
        assertEquals(initiative, initiative(), "initiative is never rolled again");
    }

    /**
     * QA-12 through renewal: before each of three round ends and between each commit and its retry, a renewed narrator session
     * is prepared from a verified checkpoint that holds the effect clocks as stored, and writes no canonical row. The idol's
     * poison ticks once at each of its two round ends and then expires, the reed falls to its first tick, a retry after
     * renewals ticks nothing again, and the clock moves eight seconds per round end and never for a renewal. The fight,
     * decided at its third round end, owes no time, so its settlement adds none and the checkpoint then records no encounter.
     */
    @Test
    void effectClocksTickOncePerRoundEndThroughRenewals() {
        begin("clock-pit");
        long start = clock();
        for (int end = 1; end <= 3; end++) {
            var unchanged = everything();
            JsonNode held = snapshot(renew());
            assertEquals(unchanged, everything(), "a renewal writes no canonical row");
            assertEquals(clocks(state().participant("idol")), clocks(held, "idol"), "the renewal's checkpoint holds the clocks");
            var request = envelope(new TypedCommand.EndActivation());
            var ended = executions.execute(owner, adventure, campaign, request);
            assertEquals(OperationStatus.COMMITTED, ended.operation().status());
            revision = ended.operation().committedRevision();
            int resolved = events("EFFECTS_RESOLVED").size();
            var settled = everything();
            renew();
            renew();
            assertEquals(settled, everything(), "nor do two more");
            assertTrue(executions.execute(owner, adventure, campaign, request).replayed());
            assertEquals(resolved, events("EFFECTS_RESOLVED").size(), "a retry after renewals ticks nothing again");
            assertEquals(Math.min(end, 2), lines("idol", "TICK").size(), "one tick per round end while the poison lasts");
            assertEquals(start + 8L * end, clock(), "eight seconds per round end");
        }
        assertEquals(2, lines("idol", "TICK").stream().map(line -> line.get("triggerKey").asString()).distinct().count());
        assertEquals(1, lines("idol", "ENDED").stream().filter(line -> "poisoned".equals(line.get("effectId").asString())).count());
        assertEquals(1, lines("reed", "TICK").size(), "felled by the first");

        var decided = state();
        assertEquals(EncounterPhase.OUTCOME_PENDING, decided.phase(), "three rounds survived");
        JsonNode last = snapshot(renew());
        assertEquals("OUTCOME_PENDING", last.path("encounter").path("phase").asString());
        assertTrue(last.path("encounter").path("roundEndsAt").isNull(), "decided at a round end, the fight owes no time");
        execute(new TypedCommand.SettleEncounter(decided.id()));
        assertEquals(start + 24, clock(), "so its settlement adds none");
        assertTrue(snapshot(renew()).path("encounter").isNull(), "a settled fight leaves the checkpoint");
    }

    /**
     * The partial-round time boundary: a fight won inside a round stores its cursor, so its checkpoint records the world time
     * at which that round ends. A restore and the startup passes keep that boundary, the settlement then moves the clock to it
     * exactly once, and a retry of the settlement adds nothing.
     */
    @Test
    void aFightDecidedInsideARoundSettlesItsSecondsOnceAfterARestore() {
        begin("close-rush");
        setLocalHealth("dummy", 1);
        execute(new TypedCommand.Strike("sword", "dummy"));
        var decided = state();
        assertEquals(EncounterPhase.OUTCOME_PENDING, decided.phase());
        assertNotNull(decided.cursor(), "the fight ended inside its round");
        assertEquals(Capture.Outcome.CAPTURED, checkpoints.capture(campaign).outcome());
        long boundary = latestSnapshot().path("encounter").path("roundEndsAt").asLong();
        assertEquals(clock() + 8, boundary);
        var world = world();

        restore();

        assertEquals(world, world());
        assertEquals(Verification.Status.VALID, reconciliation.reconcile(campaign).checkpoint().now());
        assertEquals(boundary, latestSnapshot().path("encounter").path("roundEndsAt").asLong());
        var request = envelope(new TypedCommand.SettleEncounter(decided.id()));
        var settled = executions.execute(owner, adventure, campaign, request);
        assertEquals(OperationStatus.COMMITTED, settled.operation().status());
        revision = settled.operation().committedRevision();
        assertEquals(boundary, clock(), "the unfinished round runs to its recorded boundary once");
        assertTrue(executions.execute(owner, adventure, campaign, request).replayed());
        assertEquals(boundary, clock(), "and a retry adds nothing");
        assertEquals(Capture.Outcome.CAPTURED, checkpoints.capture(campaign).outcome());
        assertTrue(latestSnapshot().path("encounter").isNull(), "a settled fight leaves the checkpoint");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Enter a template and roll initiative. */
    private void begin(String template) {
        execute(new TypedCommand.Engage(template, null));
        execute(new TypedCommand.RollInitiative());
        assertTrue(state().playerDecision());
    }

    /** Raise the player's Health pool, as a stronger character's would be, so the adder cannot end the fight. */
    private void survivable() {
        jdbc.update("update actor_resource set maximum_value = 99, current_value = 99, row_version = row_version + 1 "
                + "where actor_id = ? and resource_key = 'vigor'", player);
    }

    /** Set a template participant's local Health directly, as a wound from before this step. */
    private void setLocalHealth(String participant, int value) {
        jdbc.update("update encounter_participant set health_current = ?, row_version = row_version + 1 where participant_id = ? "
                + "and encounter_id = (select id from encounter where campaign_id = ? and phase not in ('SETTLED', "
                + "'NARRATIVE_RESUME'))", value, participant, campaign);
    }

    /**
     * Submit a command whose commit fails after its draws are reserved, standing in for one cut off by the stop before a
     * backup; startup journal recovery would leave such an attempt in the same terminal state.
     */
    private CommandExecutionService.Execution interrupted(TypedCommand command) {
        jdbc.execute("create or replace function test_recovery_cut() returns trigger language plpgsql as "
                + "$$ begin raise exception 'injected interruption'; end $$");
        jdbc.execute("create trigger test_recovery_cut before insert on campaign_event "
                + "for each row execute function test_recovery_cut()");
        try {
            var cut = executions.execute(owner, adventure, campaign, envelope(command));
            assertEquals(OperationStatus.FAILED_BEFORE_COMMIT, cut.operation().status());
            return cut;
        } finally {
            jdbc.execute("drop trigger if exists test_recovery_cut on campaign_event");
            jdbc.execute("drop function if exists test_recovery_cut()");
        }
    }

    /**
     * What a restore does besides loading the rows: the retirement statement of {@code tools/database_backup/backup.py}
     * (recovery generation and storage version, usable plans, saved permits), then the engine's startup passes over the
     * journal and presentation. The memory pass is left to the caller, which reads its result.
     */
    private void restore() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.execute("set local lock_timeout = '2s'");
            jdbc.execute("set local statement_timeout = '5s'");
            jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1");
            jdbc.update("insert into plan_disposition (artifact_id, sequence, campaign_id, status, reason_code, "
                    + "checked_revision, created_at) select a.id, d.sequence + 1, a.campaign_id, 'INVALIDATED', "
                    + "'GENERATION_RETIRED', c.state_revision, clock_timestamp() from quest_plan_artifact a "
                    + "join campaign c on c.id = a.campaign_id cross join lateral "
                    + "(select sequence, status from plan_disposition where artifact_id = a.id "
                    + "order by sequence desc limit 1) d where d.status in ('VALIDATED', 'PARTIALLY_INVALIDATED')");
            jdbc.update("update campaign_mutation_lease set fence = fence + 1, operation_id = null, "
                    + "holder_id = null, expires_at = null");
        });
        journal.reconcile();
        presentation.reconcile();
    }

    /** Prepare a renewed narrator session of the campaign's current generation; it must rest on a verified checkpoint. */
    private Rehydrator.Ready renew() {
        var scope = new SessionScope(adventure, campaign, generation(), AiRole.NARRATOR, Audience.PUBLIC, PACK_HASH, POLICY_HASH);
        var result = rehydration.prepare(scope, "turn-ceiling", false);
        var ready = assertInstanceOf(Rehydrator.Ready.class, result, String.valueOf(result));
        assertEquals("VALID", ready.checkpointStatus());
        assertNotNull(ready.checkpoint());
        assertEquals(revision, ready.revision());
        return ready;
    }

    /** The snapshot of the checkpoint a renewal verified. */
    private JsonNode snapshot(Rehydrator.Ready ready) {
        var stored = checkpoints.latest(campaign).orElseThrow();
        assertEquals(ready.checkpoint().id(), stored.id());
        return JSON.readTree(stored.snapshotJson());
    }

    private JsonNode latestSnapshot() {
        return JSON.readTree(checkpoints.latest(campaign).orElseThrow().snapshotJson());
    }

    /** One participant's effect clocks as {@code effect:remaining:stacks}, in stored order. */
    private static List<String> clocks(EncounterState.Combatant participant) {
        return participant.effects().stream().map(active -> active.effectId() + ":" + active.remaining() + ":"
                + active.stacks()).toList();
    }

    /** The same clocks as a checkpoint records them. */
    private static List<String> clocks(JsonNode snapshot, String participant) {
        var clocks = new ArrayList<String>();
        for (JsonNode recorded : snapshot.path("encounter").path("participants")) {
            if (participant.equals(recorded.path("id").asString())) {
                for (JsonNode effect : recorded.path("effects")) {
                    clocks.add(effect.path("effectId").asString() + ":" + effect.path("remaining").asInt() + ":"
                            + effect.path("stacks").asInt());
                }
            }
        }
        return clocks;
    }

    /** The player's pool as a checkpoint records it. */
    private long resource(JsonNode snapshot, String key) {
        for (JsonNode resource : snapshot.path("resources")) {
            if (player.toString().equals(resource.path("actorId").asString()) && key.equals(resource.path("key").asString())) {
                return resource.path("current").asLong();
            }
        }
        throw new AssertionError("no recorded pool " + key);
    }

    /** Every participant's initiative total and rank, in roster order. */
    private String initiative() {
        return jdbc.queryForObject("select string_agg(p.participant_id || ':' || p.initiative || ':' || p.initiative_rank, ',' "
                + "order by p.ordinal) from encounter_participant p join encounter e on e.id = p.encounter_id where "
                + "e.campaign_id = ? and e.phase not in ('SETTLED', 'NARRATIVE_RESUME')", String.class, campaign);
    }

    /** One operation's draws as {@code ordinal:sides:value}, or null when it has none. */
    private String draws(UUID operation) {
        return jdbc.queryForObject("select string_agg(ordinal || ':' || sides || ':' || value, ',' order by ordinal) "
                + "from operation_random_draw where operation_id = ?", String.class, operation);
    }

    private long drawCount() {
        return jdbc.queryForObject("select count(*) from operation_random_draw where campaign_id = ?", Long.class, campaign);
    }

    private long generation() {
        return jdbc.queryForObject("select recovery_generation from campaign where id = ?", Long.class, campaign);
    }

    private long clock() {
        return jdbc.queryForObject("select world_time_seconds from campaign where id = ?", Long.class, campaign);
    }

    private long pool(String key) {
        return jdbc.queryForObject("select current_value from actor_resource where actor_id = ? and resource_key = ?", Long.class,
                player, key);
    }

    private EncounterState state() {
        return EncounterWriter.state(jdbc, campaign).orElseThrow();
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

    /** Every public effect line of one holder and kind across the fight, in order. */
    private List<JsonNode> lines(String holder, String kind) {
        var lines = new ArrayList<JsonNode>();
        for (JsonNode resolved : events("EFFECTS_RESOLVED")) {
            for (JsonNode line : resolved.get("lines")) {
                if (kind.equals(line.get("kind").asString()) && holder.equals(line.get("holderId").asString())) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    /** Every payload of an event type, in order. */
    private List<JsonNode> events(String type) {
        return new ArrayList<>(jdbc.query("select payload::text from campaign_event where campaign_id = ? and event_type = ? "
                + "order by sequence", (row, index) -> JSON.readTree(row.getString(1)), campaign, type));
    }

    private JsonNode latest(String type) {
        return events(type).getLast();
    }

    /** The latest event of a type about one participant. */
    private JsonNode latestOf(String type, String participant) {
        return events(type).stream().filter(event -> participant.equals(event.path("participantId").asString()))
                .reduce((first, second) -> second).orElseThrow();
    }

    /**
     * Canonical rows of the world a restore must keep: the campaign without the two columns the retirement step changes, and
     * every row of the actors, pools, conditions, equipment, items, abilities, events, draws and the fight.
     */
    private Map<String, List<String>> world() {
        var tables = new LinkedHashMap<String, List<String>>();
        tables.put("campaign", jdbc.queryForList("select (to_jsonb(t) - 'recovery_generation' - 'row_version')::text "
                + "from campaign t order by 1", String.class));
        for (String table : List.of("actor", "actor_resource", "actor_condition", "equipment_assignment", "item_instance",
                "learned_ability", "campaign_event", "operation_random_draw", "encounter", "encounter_participant")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }

    /** Every canonical row a renewal must not touch, including the whole campaign row, the journal and the outbox. */
    private Map<String, List<String>> everything() {
        var tables = world();
        for (String table : List.of("campaign", "operation_journal", "canonical_outbox", "narration_publication")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }
}
