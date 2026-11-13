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
import org.rpgengine.combat.EncounterPack;
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
 * Real PostgreSQL acceptance of the Ready/equip and Assess actions (P11.5, docs/11 section 7, ADR 0034) on campaigns created
 * from the sealed encounter fixture. The surveyor wears a sword, a shield and a cloak (armor 1, Guard 12, insight 2).
 * Readying changes the real equipment rows and the fight's Guard, armor and attacks for {@code readyCost}, follows the
 * exploration slot and hand rules without emptying a slot for the player, and replays on a retry. An assessment draws two
 * recorded dice against the target's effective Resistance and reveals values only on success. Every refusal and every
 * ambiguous request writes nothing, and V31 repeats what storage can see.
 */
@SpringBootTest
class ReadyAssessCommandsTest {
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
    ReadyAssessCommandsTest() { }

    /** Register the encounter pack and create a campaign at the trailhead through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Readying", "readying", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        campaignOf(registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(EncounterPack.files())))).revisionId());
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
     * Swapping the shield for the bolt caster, one point each, changes the equipment rows and the fight's attacks; the next
     * activation shoots with it and spends an arrow, a stowed sword can no longer strike, and stowing the cloak takes its armor
     * away. The frozen profile never changes: the readied values live beside it and the stored state reads them.
     */
    @Test
    void readyingChangesTheEquipmentAndTheFight() {
        begin("close-brawl");
        UUID caster = give("bolt-caster", 1, 1);
        give("arrows", 3, 20);
        UUID shield = instance("shield");
        UUID sword = instance("sword");
        String frozen = participant("player", "frozen::text");

        var off = execute(new TypedCommand.ReadyItem(shield, "unequip"));
        assertEquals(2, combat(off).get("actionPoints"));
        assertEquals(Map.of("itemInstanceId", shield.toString(), "definitionId", "shield", "change", "UNEQUIPPED", "guard", 12,
                "armor", 1, "attacks", List.of("sword")), combat(off).get("readied"));
        assertFalse(worn(shield));
        assertEquals(0L, off.receipt().get("elapsedSeconds"));
        var stowed = event("ITEM_READIED");
        assertEquals("UNEQUIPPED", stowed.get("change").asString());
        assertEquals("off-hand", stowed.get("slot").asString());
        assertEquals(player.toString(), stowed.get("actorId").asString());
        assertEquals("PUBLIC", visibility("ITEM_READIED"));

        var on = execute(new TypedCommand.ReadyItem(caster, "equip"));
        assertEquals(1, combat(on).get("actionPoints"));
        assertEquals(List.of("bolt-caster", "sword"), ((Map<?, ?>) combat(on).get("readied")).get("attacks"));
        assertTrue(worn(caster));
        var line = strike(on, "bolt-caster");
        assertEquals("ACTION_POINTS", line.get("disabled"), "its arrows are counted at once; only the points are short");
        assertEquals(List.of("dummy", "wall"), line.get("targets"));
        assertEquals(frozen, participant("player", "frozen::text"), "the frozen profile never changes");
        assertTrue(participant("player", "readied::text").contains("bolt-caster"));
        assertNull(participant("dummy", "readied::text"));

        execute(new TypedCommand.ReadyItem(sword, "unequip"));
        execute(new TypedCommand.EndActivation());
        rejects(OperationAdmissionFailure.Kind.ATTACK_UNAVAILABLE, new TypedCommand.Strike("sword", "dummy"));
        var shot = execute(new TypedCommand.Strike("bolt-caster", "dummy"));
        assertEquals("bolt-caster", ((Map<?, ?>) ((List<?>) combat(shot).get("strikes")).getFirst()).get("attackId"));
        assertEquals(2, arrows(), "the readied weapon spends its ammunition");
        assertEquals("arrows", event("STRIKE_RESOLVED").get("ammunition").asString());

        var bare = execute(new TypedCommand.ReadyItem(instance("cloak"), "unequip"));
        assertEquals(0, ((Map<?, ?>) combat(bare).get("readied")).get("armor"));
        var stored = EncounterWriter.state(jdbc, campaign).orElseThrow().player().sheet();
        assertEquals(0, stored.armor());
        assertEquals(12, stored.guard());
        assertEquals(List.of("bolt-caster"), stored.attacks().stream().map(org.rpgengine.combat.CombatAttack::id).toList());
        assertEquals(List.of("bolt-caster"), jdbc.queryForList("select i.definition_id from equipment_assignment e join item_instance i "
                + "on i.id = e.item_instance_id where e.actor_id = ? order by 1", String.class, player));
        rejects(OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT, new TypedCommand.ReadyItem(sword, "equip"));
    }

    /** A lost response replays the same receipt: the equipment and the readied values change once, with one event. */
    @Test
    void aRetryReplaysTheReadying() {
        begin("close-brawl");
        var envelope = envelope(new TypedCommand.ReadyItem(instance("cloak"), "unequip"));
        var first = executions.execute(owner, adventure, campaign, envelope);
        assertEquals(OperationStatus.COMMITTED, first.operation().status());
        revision = first.operation().committedRevision();
        var before = world();
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(first.receipt(), again.receipt());
        assertEquals(before, world(), "a replay writes nothing");
        assertEquals(1, events("ITEM_READIED").size());
    }

    /**
     * Every illegal, unaffordable or ambiguous readying is refused before anything is written: a full slot is never emptied
     * for the player, a worn item cannot be worn again nor an unworn one stowed, an unknown instance is unavailable, two hands
     * cannot come from one, a change the player did not state exactly is invalid, and the turn and mode gates hold.
     */
    @Test
    void illegalReadyingWritesNothing() {
        UUID caster = give("bolt-caster", 1, 1);
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.ReadyItem(caster, "equip"));
        begin("close-brawl");
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.ReadyItem(caster, "equip"));
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.ReadyItem(instance("sword"), "equip"));
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.ReadyItem(caster, "unequip"));
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.ReadyItem(UUID.randomUUID(), "equip"));
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.ReadyItem(UUID.randomUUID(), "unequip"));
        for (var payload : List.<Map<String, Object>>of(Map.of("itemInstanceId", caster.toString(), "change", "toggle"),
                Map.of("itemInstanceId", caster.toString()), Map.of("itemInstanceId", caster.toString(), "change", "equip",
                        "replace", instance("shield").toString()))) {
            rejectsRaw(OperationAdmissionFailure.Kind.INVALID_COMMAND, "READY_ITEM", payload);
        }
        UUID great = give("greatsword", 1, 1);
        UUID sword = instance("sword");
        execute(new TypedCommand.ReadyItem(sword, "unequip"));
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.ReadyItem(great, "equip"));
        execute(new TypedCommand.Guard());
        execute(new TypedCommand.ReadyItem(sword, "equip"));
        rejects(OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT, new TypedCommand.ReadyItem(instance("shield"), "unequip"));
        jdbc.update("update encounter set phase = 'ACTOR_END', action_points = 0, row_version = row_version + 1");
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.ReadyItem(instance("shield"), "unequip"));
    }

    /**
     * A weapon whose hit applies an effect the fight did not freeze cannot be readied, because resolving its hit would need the
     * pack; in a fight that froze that effect, the same weapon can.
     */
    @Test
    void aWeaponNeedsItsEffectsFrozen() throws Exception {
        UUID tactics = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(EncounterPack.tactics())))).revisionId();
        campaignOf(tactics);
        UUID blade = give("venom-blade", 1, 1);
        begin("parley-ground");
        execute(new TypedCommand.ReadyItem(instance("sword"), "unequip"));
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED, new TypedCommand.ReadyItem(blade, "equip"));
        campaignOf(tactics);
        blade = give("venom-blade", 1, 1);
        begin("clock-pit");
        execute(new TypedCommand.ReadyItem(instance("sword"), "unequip"));
        var armed = execute(new TypedCommand.ReadyItem(blade, "equip"));
        assertEquals(List.of("venom-blade"), ((Map<?, ?>) combat(armed).get("readied")).get("attacks"));
    }

    /**
     * A player who enters holding {@code searing} falls to its declared-action tick when paying for the readying: the fight ends
     * in defeat and nothing was readied, so the equipment rows, the readied values and the event log show no change of gear.
     */
    @Test
    void aFallOnTheDeclaredTickReadiesNothing() throws Exception {
        campaignOf(registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(EncounterPack.tactics())))).revisionId());
        jdbc.update("insert into actor_condition (campaign_id, actor_id, condition_id, stacks, remaining_rounds, applied_revision, "
                + "updated_revision) values (?, ?, 'searing', 1, 3, 0, 0)", campaign, player);
        begin("parley-ground");
        UUID cloak = instance("cloak");
        var fallen = execute(new TypedCommand.ReadyItem(cloak, "unequip"));
        assertEquals("DEFEAT", ((Map<?, ?>) combat(fallen).get("outcome")).get("outcome"));
        assertFalse(combat(fallen).containsKey("readied"));
        assertTrue(worn(cloak), "nothing was readied, so the cloak stays on");
        assertNull(participant("player", "readied::text"));
        assertTrue(events("ITEM_READIED").isEmpty());
    }

    /**
     * Assessing the dummy (Resistance 0) always succeeds and reveals its values; the wall (Resistance 40) can never be read.
     * Each costs one point and two recorded dice, the difficulty and margin stay internal, and a retry draws nothing new.
     */
    @Test
    void anAssessmentFollowsItsDiceAndRevealsOnlyOnSuccess() {
        begin("close-brawl");
        var read = execute(new TypedCommand.Assess("dummy"));
        var dice = draws(read);
        assertEquals(List.of(10, 10), dice.stream().map(draw -> draw.get("sides")).toList());
        int total = (Integer) dice.get(0).get("value") + (Integer) dice.get(1).get("value") + 2;
        assertEquals(2, combat(read).get("actionPoints"));
        assertEquals(Map.of("targetId", "dummy", "total", total, "success", true, "revealed",
                Map.of("health", 999, "healthMax", 999, "guard", 0, "resistance", 0, "armor", 0)), combat(read).get("assessment"));
        var shown = event("ASSESSMENT_RESOLVED");
        assertEquals("PUBLIC", visibility("ASSESSMENT_RESOLVED"));
        assertEquals(total, shown.get("total").intValue());
        assertFalse(shown.has("difficulty"));
        assertFalse(shown.has("margin"));
        var hidden = event("ASSESSMENT_CALCULATED");
        assertEquals("INTERNAL", visibility("ASSESSMENT_CALCULATED"));
        assertEquals(0, hidden.get("difficulty").intValue());
        assertEquals(total, hidden.get("margin").intValue());
        assertEquals(0L, read.receipt().get("elapsedSeconds"));

        var envelope = envelope(new TypedCommand.Assess("wall"));
        var blank = executions.execute(owner, adventure, campaign, envelope);
        assertEquals(OperationStatus.COMMITTED, blank.operation().status());
        revision = blank.operation().committedRevision();
        var assessment = (Map<?, ?>) combat(blank).get("assessment");
        assertEquals(false, assessment.get("success"));
        assertFalse(assessment.containsKey("revealed"), "a failure reveals nothing");
        assertFalse(event("ASSESSMENT_RESOLVED").has("revealed"));
        assertEquals(40, event("ASSESSMENT_CALCULATED").get("difficulty").intValue());
        long recorded = count("operation_random_draw");
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(blank.receipt(), again.receipt());
        assertEquals(recorded, count("operation_random_draw"));
        assertEquals(1, ((Map<?, ?>) options(blank).get("assess")).get("cost"));
        assertEquals(List.of("dummy", "wall", "porter", "lookout"), ((Map<?, ?>) options(blank).get("assess")).get("targets"));
    }

    /** Self, unknown, hidden and unaffordable assessments are refused before anything is written; an ambiguous one is invalid. */
    @Test
    void illegalAssessmentsWriteNothing() {
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.Assess("raider"));
        begin("toll-parley");
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Assess("smuggler"));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Assess("player"));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Assess("ghost"));
        rejectsRaw(OperationAdmissionFailure.Kind.INVALID_COMMAND, "ASSESS", Map.of());
        rejectsRaw(OperationAdmissionFailure.Kind.INVALID_COMMAND, "ASSESS", Map.of("targetId", "keeper", "reveal", "health"));
        execute(new TypedCommand.Assess("keeper"));
        execute(new TypedCommand.Assess("keeper"));
        execute(new TypedCommand.Assess("keeper"));
        rejects(OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT, new TypedCommand.Assess("keeper"));
    }

    /** The database repeats what it can see of readied values, whatever the writer. */
    @Test
    void theDatabaseGuardsReadiedValues() {
        begin("close-brawl");
        String set = "update encounter_participant set readied = ?::jsonb, row_version = row_version + 1 where participant_id = ?";
        String valid = "{\"guard\":12,\"armor\":1,\"attacks\":[],\"equipment\":[\"cloak\"]}";
        assertThrows(DataAccessException.class, () -> jdbc.update(set, valid, "dummy"), "only the player wears campaign items");
        assertThrows(DataAccessException.class, () -> jdbc.update(set, "[]", "player"), "an object");
        assertThrows(DataAccessException.class, () -> jdbc.update(set, "{\"guard\":12,\"armor\":1,\"attacks\":[]}", "player"),
                "with every member");
        assertThrows(DataAccessException.class, () -> jdbc.update(set, valid.replace("[\"cloak\"]", "\"cloak\""), "player"),
                "of its JSON type");
        assertEquals(1, jdbc.update(set, valid, "player"));
        assertThrows(DataAccessException.class, () -> jdbc.update(set, null, "player"), "never removed");
        assertEquals(1, jdbc.update(set, valid.replace("12", "13"), "player"), "but replaced");
        assertEquals(13, EncounterWriter.state(jdbc, campaign).orElseThrow().player().sheet().guard());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Create a fresh campaign on a registered content revision; the surveyor starts at the trailhead. */
    private void campaignOf(UUID content) {
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        revision = 0;
    }

    /** Enter a template at the trailhead and roll initiative. */
    private void begin(String template) {
        execute(new TypedCommand.Engage(template, null));
        execute(new TypedCommand.RollInitiative());
        assertTrue(EncounterWriter.state(jdbc, campaign).orElseThrow().playerDecision());
    }

    /** Stock the player's inventory directly, as the item suites do. */
    private UUID give(String definition, int quantity, int maxStack) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into item_instance (id, campaign_id, actor_id, definition_id, quantity, maximum_stack, "
                + "acquired_revision) values (?, ?, ?, ?, ?, ?, 0)", id, campaign, player, definition, quantity, maxStack);
        return id;
    }

    /** The player's instance of a starting item. */
    private UUID instance(String definition) {
        return jdbc.queryForObject("select id from item_instance where campaign_id = ? and actor_id = ? and definition_id = ?",
                UUID.class, campaign, player, definition);
    }

    private boolean worn(UUID instance) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from equipment_assignment where item_instance_id = ?)",
                Boolean.class, instance));
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

    /** The strike option of one attack. */
    private static Map<?, ?> strike(CommandExecutionService.Execution execution, String attack) {
        return ((List<?>) options(execution).get("strikes")).stream().map(line -> (Map<?, ?>) line)
                .filter(line -> attack.equals(line.get("attackId"))).findFirst().orElseThrow(() -> new AssertionError(attack));
    }

    private static List<Map<?, ?>> draws(CommandExecutionService.Execution execution) {
        return ((List<?>) execution.receipt().get("randomDraws")).stream().<Map<?, ?>>map(draw -> (Map<?, ?>) draw).toList();
    }

    private JsonNode event(String type) {
        return events(type).getLast();
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

    /** Units of ammunition left; a used-up stack has no row. */
    private int arrows() {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from item_instance where actor_id = ? and definition_id = 'arrows'",
                Integer.class, player);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    /** Every table a command can touch, equipment included, for exact before/after comparison. */
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
