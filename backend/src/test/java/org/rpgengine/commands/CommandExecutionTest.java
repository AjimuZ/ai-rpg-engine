package org.rpgengine.commands;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ActorResource;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of P07.4 local execution on a campaign created from sealed, registered
 * packs: movement, waiting, speech and ability use commit their calculated effects, events,
 * durable draws, receipts, outbox intents, query-epoch bumps and the cleared pending decision
 * in one transaction; replays never re-execute, including after startup reconciliation.
 * Database triggers inject faults after durable draws were reserved (a failing event insert and
 * a concurrent rule-fact change), proving that failures leave no world effects, keep the draws
 * bound to their operation, record FAILED_BEFORE_COMMIT or REJECTED and free the campaign.
 */
@SpringBootTest
class CommandExecutionTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private CommandExecutionService executions;
    @Autowired private JournalRecoveryService recovery;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private UUID player;
    private UUID warden;
    private UUID wardenVigor;
    private long revision;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    CommandExecutionTest() { }

    /** Register dependency and root packs, create a campaign through production code and place one NPC. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Execution", "execution", SupportedLocale.EN,
                    Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        var fixture = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI())).files());
        var dependency = register(dependencyPack(fixture), "test:dep", List.of());
        UUID content = register(rootPack(fixture), "test:exec", List.of(pin(dependency))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null,
                null)));
        campaign = created.campaignId();
        player = created.actorId();
        warden = new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, "warden", "Warden", "trailhead", 0, Instant.now());
            entities.persist(actor);
            var pool = ActorResource.create(actor, "vigor", 0, 2, 6, 0, Instant.now());
            entities.persist(pool);
            wardenVigor = pool.getId();
            return actor.getId();
        });
        revision = 0;
    }

    /** Remove disposable rows and any fault-injection triggers, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("drop trigger if exists test_fail_event on campaign_event");
        jdbc.execute("drop trigger if exists test_move_after_draw on operation_random_draw");
        jdbc.execute("drop function if exists test_fail_event()");
        jdbc.execute("drop function if exists test_move_after_draw()");
        jdbc.execute("truncate campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, "
                + "ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * Movement commits the placement, counters, cleared starting decision, public event, receipt,
     * outbox intents, query-epoch bump and lease release together; an identical retry replays the
     * same receipt without writes, before and after startup reconciliation.
     */
    @Test
    void movementCommitsAllEffectsAndReplaysWithoutReexecution() {
        var input = envelope(new TypedCommand.Move("grove"));
        var executed = executions.execute(owner, adventure, campaign, input);
        assertFalse(executed.replayed());
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        assertEquals(1L, executed.operation().committedRevision());
        assertEquals("grove", jdbc.queryForObject("select location_id from actor where id = ?", String.class, player));
        assertEquals("1|1|0|1|null", jdbc.queryForObject("select concat_ws('|', state_revision, game_turn, "
                + "world_time_seconds, event_sequence, coalesce(pending_decision::text, 'null')) from campaign "
                + "where id = ?", String.class, campaign));
        var event = jdbc.queryForMap("select event_type, visibility, actor_id, payload::text as payload "
                + "from campaign_event where campaign_id = ?", campaign);
        assertEquals("ACTOR_MOVED", event.get("event_type"));
        assertEquals("PUBLIC", event.get("visibility"));
        assertEquals(player, event.get("actor_id"));
        assertEquals("grove", JSON.readTree((String) event.get("payload")).path("to").asString());
        assertEquals(List.of("NARRATION_REQUIRED", "PUBLIC_STATE"), jdbc.queryForList("select kind from "
                + "canonical_outbox where campaign_id = ? order by kind", String.class, campaign));
        assertEquals(1L, jdbc.queryForObject("select epoch from query_epoch where campaign_id = ? and scope_key = ?",
                Long.class, campaign, "actor:" + player));
        assertEquals(0, jdbc.queryForObject("select count(*) from campaign_mutation_lease where campaign_id = ? "
                + "and operation_id is not null", Integer.class, campaign));
        var receipt = executed.receipt();
        assertEquals(List.of("stateRevision", "firstEventSequence", "lastEventSequence", "commandType",
                "elapsedSeconds", "turnAdvanced", "destinationId", "resources", "calculations", "randomDraws"),
                List.copyOf(receipt.keySet()));
        assertEquals("grove", receipt.get("destinationId"));
        assertEquals(1L, receipt.get("stateRevision"));

        var before = snapshot();
        var replay = executions.execute(owner, adventure, campaign, input);
        assertTrue(replay.replayed());
        assertEquals(receipt, replay.receipt());
        assertEquals(before, snapshot());
        // A restart owes narration but never re-runs the action; the same request still replays.
        assertEquals(1, recovery.reconcile());
        var restarted = executions.execute(owner, adventure, campaign, input);
        assertEquals(OperationStatus.COMMITTED_NARRATION_PENDING, restarted.operation().status());
        assertEquals(receipt, restarted.receipt());
        assertEquals("grove", jdbc.queryForObject("select location_id from actor where id = ?", String.class, player));
        assertEquals(1L, jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign));
    }

    /** Waiting advances exactly the requested world time; speech records the exact words and recipient. */
    @Test
    void waitingAndSpeechAdvanceTurnsAndRecordEvents() {
        assertEquals(OperationStatus.COMMITTED, execute(new TypedCommand.Wait(90)).operation().status());
        var spoken = execute(new TypedCommand.Speak(warden, "Good morning, wardené"));
        assertEquals(OperationStatus.COMMITTED, spoken.operation().status());
        assertEquals(0L, spoken.receipt().get("elapsedSeconds"));
        assertEquals("2|2|90|2", jdbc.queryForObject("select concat_ws('|', state_revision, game_turn, "
                + "world_time_seconds, event_sequence) from campaign where id = ?", String.class, campaign));
        var events = jdbc.queryForList("select event_type || ' ' || payload::text from campaign_event "
                + "where campaign_id = ? order by sequence", String.class, campaign);
        assertTrue(events.get(0).startsWith("TIME_PASSED "), events.get(0));
        var speech = JSON.readTree(events.get(1).substring("SPEECH_DELIVERED ".length()));
        assertEquals("Good morning, wardené", speech.path("text").asString());
        assertEquals(warden.toString(), speech.path("recipientActorId").asString());
    }

    /**
     * Ability use pays its cost exactly, resolves its conditional with durable draws in ordinal order,
     * clamps to bounds and records calculations; the committed receipt lists the same draws stored
     * for the operation. Target values stay internal and out of the player projection.
     */
    @Test
    void abilityUseSpendsCostsAndUsesDurableDraws() {
        var executed = execute(use("gamble"));
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        UUID operation = executed.operation().operationId();
        var draws = jdbc.queryForList("select ordinal || ':' || sides || ':' || value from operation_random_draw "
                + "where operation_id = ? order by ordinal", String.class, operation);
        int roll = Integer.parseInt(draws.get(0).split(":")[2]);
        assertTrue(draws.get(0).startsWith("0:6:"));
        // Vigor 8 pays 2; on 4+ it gains 1d4 + awareness (2), clamped at 8; otherwise it loses 1.
        long expected = roll > 3 ? Math.min(8, 6 + Integer.parseInt(draws.get(1).split(":")[2]) + 2) : 5;
        assertEquals(roll > 3 ? 2 : 1, draws.size());
        if (roll > 3) {
            assertTrue(draws.get(1).startsWith("1:4:"));
        }
        assertEquals(expected, jdbc.queryForObject("select current_value from actor_resource where actor_id = ? "
                + "and resource_key = 'vigor'", Long.class, player));
        assertEquals(List.of("ABILITY_USED", "RESOURCE_SPENT", "EFFECT_RESOLVED"), jdbc.queryForList(
                "select event_type from campaign_event where operation_id = ? order by sequence", String.class, operation));
        var receipt = executed.receipt();
        // The receipt lists net pool changes; spending 2 and regaining them nets to no entry.
        assertEquals(expected == 8 ? List.of() : List.of(Map.of("resourceKey", "vigor", "before", 8L, "after", expected)),
                receipt.get("resources"));
        var spent = JSON.readTree(jdbc.queryForObject("select payload::text from campaign_event where operation_id = ? "
                + "and event_type = 'RESOURCE_SPENT'", String.class, operation));
        assertEquals("8|6", spent.path("before").asLong() + "|" + spent.path("after").asLong());
        @SuppressWarnings("unchecked")
        var listed = ((List<Map<String, Object>>) receipt.get("randomDraws")).stream()
                .map(draw -> draw.get("ordinal") + ":" + draw.get("sides") + ":" + draw.get("value")).toList();
        assertEquals(draws, listed);
        @SuppressWarnings("unchecked")
        var calculations = (List<Map<String, Object>>) receipt.get("calculations");
        assertEquals("effects/0/when", calculations.getFirst().get("path"));
        assertEquals(roll > 3, calculations.getFirst().get("value"));

        // A target effect changes the warden's pool, but its values stay engine-internal.
        var soothed = execute(use("soothe", warden));
        assertEquals(OperationStatus.COMMITTED, soothed.operation().status());
        long wardenAfter = jdbc.queryForObject("select current_value from actor_resource where id = ?", Long.class,
                wardenVigor);
        assertTrue(wardenAfter > 2 && wardenAfter <= 6);
        assertEquals("INTERNAL", jdbc.queryForObject("select visibility from campaign_event where operation_id = ? "
                + "and event_type = 'EFFECT_RESOLVED'", String.class, soothed.operation().operationId()));
        assertEquals(List.of(), soothed.receipt().get("resources"));
        assertFalse(soothed.receipt().toString().contains(warden.toString()));
        // A dependency ability's foreign-vocabulary resource is an explicit no-op, not a charge to "vigor".
        long vigor = jdbc.queryForObject("select current_value from actor_resource where actor_id = ? "
                + "and resource_key = 'vigor'", Long.class, player);
        var foreign = execute(use("test:dep#steady"));
        assertEquals(OperationStatus.COMMITTED, foreign.operation().status());
        assertEquals(vigor, jdbc.queryForObject("select current_value from actor_resource where actor_id = ? "
                + "and resource_key = 'vigor'", Long.class, player));
        assertEquals("NO_RESOURCE", JSON.readTree(jdbc.queryForObject("select payload::text from campaign_event "
                + "where operation_id = ? and event_type = 'EFFECT_RESOLVED'", String.class,
                foreign.operation().operationId())).path("outcomes").get(0).path("outcome").asString());
    }

    /** Programs the local resolver cannot execute are refused at admission, before any write. */
    @Test
    void unsupportedEffectProgramsAreRejectedBeforeJournaling() {
        var before = snapshot();
        for (var command : List.of(use("smite", warden), use("storm"), use("test:dep#sense"))) {
            var failure = assertThrows(OperationAdmissionFailure.class,
                    () -> executions.execute(owner, adventure, campaign, envelope(command)));
            assertEquals(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED, failure.kind(), command.toString());
            assertEquals(before, snapshot());
        }
    }

    /**
     * A failure inside the canonical transaction after draws were reserved leaves no world effect,
     * records FAILED_BEFORE_COMMIT, releases the permit and keeps the draws bound to the attempt.
     */
    @Test
    void failureAfterDurableDrawsKeepsDrawsAndChangesNoWorldState() {
        jdbc.execute("create function test_fail_event() returns trigger language plpgsql as "
                + "$$ begin raise exception 'injected event failure'; end $$");
        jdbc.execute("create trigger test_fail_event before insert on campaign_event "
                + "for each row execute function test_fail_event()");
        var world = world();
        var input = envelope(use("gamble"));
        var failed = executions.execute(owner, adventure, campaign, input);
        assertEquals(OperationStatus.FAILED_BEFORE_COMMIT, failed.operation().status());
        assertNull(failed.receipt());
        assertEquals(world, world());
        UUID operation = failed.operation().operationId();
        var draws = jdbc.queryForList("select id::text || value from operation_random_draw where operation_id = ? "
                + "order by ordinal", String.class, operation);
        assertFalse(draws.isEmpty());
        assertEquals(0, jdbc.queryForObject("select count(*) from campaign_mutation_lease where operation_id is not null",
                Integer.class));
        jdbc.execute("drop trigger test_fail_event on campaign_event");
        // The same request replays its failure; it never resumes, re-rolls or commits later.
        var replay = executions.execute(owner, adventure, campaign, input);
        assertTrue(replay.replayed());
        assertEquals(OperationStatus.FAILED_BEFORE_COMMIT, replay.operation().status());
        assertEquals(draws, jdbc.queryForList("select id::text || value from operation_random_draw "
                + "where operation_id = ? order by ordinal", String.class, operation));
        assertEquals(world, world());
        // The campaign is free again for a new deliberate attempt at the unchanged revision.
        assertEquals(OperationStatus.COMMITTED, execute(new TypedCommand.Wait(1)).operation().status());
    }

    /**
     * A rule fact that changes after admission and draw reservation is re-derived at commit: the
     * operation becomes REJECTED with no world effect, its draws retained and its permit released.
     */
    @Test
    void ruleDriftDuringResolutionRecordsRejected() {
        jdbc.execute("create function test_move_after_draw() returns trigger language plpgsql as $$ begin "
                + "update actor set location_id = 'grove', row_version = row_version + 1 where id = '" + warden
                + "'; return null; end $$");
        jdbc.execute("create trigger test_move_after_draw after insert on operation_random_draw "
                + "for each row execute function test_move_after_draw()");
        var resources = jdbc.queryForObject("select jsonb_agg(to_jsonb(r) order by r.id)::text from actor_resource r",
                String.class);
        var rejected = execute(use("soothe", warden));
        assertEquals(OperationStatus.REJECTED, rejected.operation().status());
        assertNull(rejected.operation().committedRevision());
        assertEquals(resources, jdbc.queryForObject("select jsonb_agg(to_jsonb(r) order by r.id)::text "
                + "from actor_resource r", String.class));
        assertEquals("0|0|0", jdbc.queryForObject("select concat_ws('|', state_revision, game_turn, event_sequence) "
                + "from campaign where id = ?", String.class, campaign));
        assertEquals(1, jdbc.queryForObject("select count(*) from operation_random_draw where operation_id = ?",
                Integer.class, rejected.operation().operationId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from campaign_mutation_lease where operation_id is not null",
                Integer.class));
    }

    /** Execute one fresh intention at the tracked revision; a commit advances the tracked revision. */
    private CommandExecutionService.Execution execute(TypedCommand command) {
        var execution = executions.execute(owner, adventure, campaign, envelope(command));
        if (execution.operation().committedRevision() != null) {
            revision = execution.operation().committedRevision();
        }
        return execution;
    }

    /** Fresh envelope at the tracked revision. */
    private OperationAdmissionService.Command envelope(TypedCommand command) {
        return new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, command.type(),
                command.payload());
    }

    /** Ability intention with explicit targets. */
    private static TypedCommand.UseAbility use(String ability, UUID... targets) {
        return new TypedCommand.UseAbility(ability, List.of(targets));
    }

    /** Exact rows of every world-state table (excluding journal, permits and draws). */
    private Map<String, String> world() {
        var rows = new LinkedHashMap<String, String>();
        for (String table : List.of("campaign", "actor", "actor_resource", "character_state", "campaign_event",
                "canonical_outbox", "query_epoch", "reward_settlement")) {
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return rows;
    }

    /** World rows plus journal, permit, draw and seed tables. */
    private Map<String, String> snapshot() {
        var rows = world();
        for (String table : List.of("operation_journal", "campaign_mutation_lease", "operation_random_draw",
                "campaign_random_seed")) {
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return rows;
    }

    /** Dependency pack: a cost-free foreign-vocabulary "steady" plus an ability reading foreign values. */
    private static Map<String, byte[]> dependencyPack(Map<String, byte[]> fixture) {
        var files = new TreeMap<>(fixture);
        var abilities = definitions(files, "abilities.json");
        abilities.add(ability("sense", "self", Map.of(), List.of(Map.of("type", "modifyResource", "resource", "vigor",
                "amount", Map.of("resource", "vigor")))));
        put(files, "abilities.json", abilities);
        return files;
    }

    /**
     * Root pack: trailhead and grove linked both ways, a public warden, and abilities with a
     * conditional dice program, a dice-based target effect, an unsupported damage effect and a
     * program exceeding the per-operation draw budget. The profile learns all of them.
     */
    private static Map<String, byte[]> rootPack(Map<String, byte[]> fixture) {
        var files = new TreeMap<>(fixture);
        put(files, "npcs.json", List.of(Map.of("id", "warden", "kind", "npc", "data", Map.of("name", "Warden",
                "role", "Trail keeper", "location", "trailhead"))));
        var abilities = definitions(files, "abilities.json");
        abilities.add(ability("gamble", "self", Map.of("vigor", 2), List.of(Map.of("type", "conditional",
                "when", Map.of("gt", List.of(dice(1, 6), 3)),
                "then", List.of(Map.of("type", "modifyResource", "target", "self", "resource", "vigor",
                        "amount", Map.of("add", List.of(dice(1, 4), Map.of("attribute", "awareness"))))),
                "else", List.of(Map.of("type", "modifyResource", "target", "self", "resource", "vigor",
                        "amount", -1))))));
        abilities.add(ability("soothe", "single", Map.of(), List.of(Map.of("type", "modifyResource",
                "resource", "vigor", "amount", Map.of("add", List.of(dice(1, 4), 1))))));
        abilities.add(ability("smite", "single", Map.of(), List.of(Map.of("type", "damage", "amount", 2))));
        var heavy = Map.of("type", "modifyResource", "target", "self", "resource", "vigor",
                "amount", Map.of("add", List.of(dice(20, 6), dice(20, 6))));
        abilities.add(ability("storm", "self", Map.of(), List.of(heavy, heavy, heavy, heavy)));
        put(files, "abilities.json", abilities);
        var profiles = definitions(files, "creation.json");
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) profiles.getFirst().get("data");
        data.put("startingAbilities", List.of("steady", "gamble", "soothe", "smite", "storm", "test:dep#steady",
                "test:dep#sense"));
        put(files, "creation.json", profiles);
        var manifest = document(files, "adventure.json");
        @SuppressWarnings("unchecked")
        var listed = new java.util.ArrayList<>((List<Object>) manifest.get("files"));
        listed.add(Map.of("path", "npcs.json", "category", "npcs"));
        manifest.put("files", listed);
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        return files;
    }

    /** Dice atom. */
    private static Map<String, Object> dice(int count, int sides) {
        return Map.of("dice", Map.of("count", count, "sides", sides));
    }

    /** One ability definition with costs, a target policy and an explicit effect list. */
    private static Map<String, Object> ability(String id, String policy, Map<String, Integer> costs,
            List<Map<String, Object>> effects) {
        var data = new LinkedHashMap<String, Object>();
        data.put("name", "Ability " + id);
        data.put("description", "Fixture ability " + id);
        data.put("costs", costs.entrySet().stream().map(cost -> Map.of("resource", cost.getKey(), "amount",
                cost.getValue())).toList());
        data.put("target", Map.of("policy", policy));
        data.put("effects", effects);
        return Map.of("id", id, "kind", "ability", "data", data);
    }

    /** Mutable copy of a definition file's entries. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> definitions(Map<String, byte[]> files, String path) {
        return new java.util.ArrayList<>((List<Map<String, Object>>) document(files, path).get("definitions"));
    }

    /** Replace one definition file. */
    private static void put(Map<String, byte[]> files, String path, List<Map<String, Object>> definitions) {
        files.put(path, JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions", definitions)));
    }

    /** Parse one fixture JSON object. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> document(Map<String, byte[]> files, String path) {
        return JSON.readValue(files.get(path), Map.class);
    }

    /** Exact registered coordinates of a dependency. */
    private static Map<String, Object> pin(PackRegistry.Registration registration) {
        var pack = registration.pack();
        return Map.of("id", pack.packId(), "version", pack.packVersion(), "hash", pack.contentHash());
    }

    /** Register a fixture variant through real ZIP staging and sealing. */
    private PackRegistry.Registration register(Map<String, byte[]> original, String id,
            List<Map<String, Object>> dependencies) throws Exception {
        var files = new TreeMap<>(original);
        var manifest = document(files, "adventure.json");
        manifest.put("id", id);
        manifest.put("version", "1.0.0");
        manifest.put("dependencies", dependencies);
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.setLevel(0);
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(output.toByteArray())));
    }
}
