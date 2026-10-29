package org.rpgengine.characters;

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
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.rules.ConditionRules;
import org.rpgengine.rules.EquipmentRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL acceptance of P10.1 character state: the pinned rule catalog, the effective sheet,
 * learned abilities, conditions and equipment constraints, all on a campaign created through the
 * real creation path from a sealed pack that declares the rules.
 */
@SpringBootTest
class CharacterServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private PackRegistry registry;
    @Autowired private CampaignCreationService creation;
    @Autowired private CharacterService service;
    private UUID campaign;
    private UUID actor;
    private CharacterRuleCatalog catalog;

    /** Construct the injected fixture; missing PostgreSQL fails application startup. */
    CharacterServiceTest() {
    }

    /** Commit an owner, a sealed armory pack and a created campaign with an equipped starting loadout. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        UUID[] ids = new UUID[2];
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            var workspace = AdventureWorkspace.create(profile.getId(), "Armory", "armory", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            ids[0] = profile.getId();
            ids[1] = workspace.getId();
        });
        UUID revision = registry.register(ids[1], new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(ArmoryPack.files())))).revisionId();
        var created = creation.create(ids[0], ids[1], new CampaignCreationService.Request(UUID.randomUUID(), revision,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        campaign = created.campaignId();
        actor = created.actorId();
        // Loading a pinned closure needs a read transaction, as in the production loaders.
        catalog = inTransaction(() -> CharacterRuleCatalog.from(registry.loadForCampaign(ids[1], revision)));
    }

    /** Remove only disposable fixture rows. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_activation, plan_disposition, "
                + "plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, "
                + "query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, canonical_outbox, "
                + "campaign_checkpoint, reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, "
                + "content_revision, adventure_workspace, identity_owner_profile");
    }

    /** Rarity is the declared tier ID; two items that look alike or differently keep their tiers. */
    @Test
    void catalogCarriesExplicitRarityAndNeverReadsArtwork() {
        assertEquals("ordinary", catalog.items().get("sword").rarity());
        assertEquals("rare", catalog.items().get("greatsword").rarity());
        assertEquals("legendary", catalog.items().get("relic").rarity());
        // A far more ornate appearance than the plain lantern grants no tier, slot, hands or tags.
        assertEquals(catalog.items().get("lantern"), catalog.items().get("gilded-lantern"));
        var vocabulary = catalog.vocabularyOf("traveler");
        assertEquals(2, vocabulary.hands());
        assertEquals(List.of("body", "main-hand", "off-hand", "trinket"), vocabulary.slots().keySet().stream().sorted().toList());
        assertEquals(3, vocabulary.derived().size());
        assertEquals(ConditionRules.Stacking.STACK, catalog.conditions().get("rattled").stacking());
        assertNull(catalog.vocabularyOf("unknown-profile"));
    }

    /** Creation stores the explicit equipped loadout atomically with the stacks it names. */
    @Test
    void creationEquipsOnlyWhatTheProfileNames() {
        var worn = inTransaction(() -> service.equipment(campaign, actor));
        assertEquals(List.of("cloak", "shield", "sword"), worn.stream().map(CharacterService.Equipped::definitionId).sorted().toList());
        assertEquals(2, worn.stream().mapToInt(CharacterService.Equipped::hands).sum());
        assertEquals(4, count("item_instance"), "the unequipped kit stays in the inventory");
        assertEquals(3, count("equipment_assignment"));
    }

    /** The sheet recomputes attributes, skills and derived statistics from committed rows. */
    @Test
    void sheetDerivesStatisticsFromStoredValues() {
        var sheet = inTransaction(() -> service.sheet(campaign, actor, catalog));
        assertEquals(Map.of("awareness", 2L, "might", 2L), sheet.attributes());
        assertEquals(Map.of("bladework", 2L, "fieldwork", 1L), sheet.skills());
        // guard = might + awareness + bladework, haul = (might + 1) * 2; focus needs resolve, which is not held.
        assertEquals(Map.of("guard", 6L, "haul", 6L), sheet.derived());
        assertEquals(sheet, inTransaction(() -> service.sheet(campaign, actor, catalog)), "same rows, same sheet");
    }

    /** Conditions apply by policy, move effective attributes and expire with elapsed rounds. */
    @Test
    void conditionsFollowTheirDefinitions() {
        assertEquals(ConditionRules.Change.ADDED, inTransaction(() -> service.applyCondition(campaign, actor, "rattled", catalog, 1)).change());
        assertEquals(ConditionRules.Change.STACKED, inTransaction(() -> service.applyCondition(campaign, actor, "rattled", catalog, 2)).change());
        var sheet = inTransaction(() -> service.sheet(campaign, actor, catalog));
        assertEquals(Map.of("awareness", 0L, "might", 0L), sheet.attributes());
        assertEquals(Map.of("guard", 2L, "haul", 2L), sheet.derived());
        assertEquals(List.of(new ConditionRules.Active("rattled", 2, 4)), sheet.conditions());
        // The cap is three stacks; a fourth application only restores the duration.
        inTransaction(() -> service.applyCondition(campaign, actor, "rattled", catalog, 3));
        assertEquals(ConditionRules.Change.REFRESHED, inTransaction(() -> service.applyCondition(campaign, actor, "rattled", catalog, 4)).change());
        assertEquals(3, stacks("rattled"));
        // No-stacking leaves the stored duration alone; refresh restores it.
        inTransaction(() -> service.applyCondition(campaign, actor, "steadfast", catalog, 4));
        assertEquals(ConditionRules.Change.UNCHANGED, inTransaction(() -> service.applyCondition(campaign, actor, "steadfast", catalog, 5)).change());
        assertEquals(List.of(), inTransaction(() -> service.elapse(campaign, actor, 3, 6)));
        assertEquals(1, remaining("rattled"));
        assertEquals(2, remaining("steadfast"));
        assertEquals(List.of("rattled"), inTransaction(() -> service.elapse(campaign, actor, 1, 7)));
        assertEquals(1, count("actor_condition"));
        assertTrue(inTransaction(() -> service.removeCondition(campaign, actor, "steadfast")));
        assertFalse(inTransaction(() -> service.removeCondition(campaign, actor, "steadfast")));
        // Dazed lasts three rounds and restores awareness once it ends.
        inTransaction(() -> service.applyCondition(campaign, actor, "dazed", catalog, 8));
        assertEquals(0L, inTransaction(() -> service.sheet(campaign, actor, catalog)).attributes().get("awareness"));
        inTransaction(() -> service.elapse(campaign, actor, 3, 9));
        assertEquals(2L, inTransaction(() -> service.sheet(campaign, actor, catalog)).attributes().get("awareness"));
    }

    /** Unknown definitions and stale revisions are refused before any write. */
    @Test
    void conditionRefusalsLeaveStateIntact() {
        inTransaction(() -> service.applyCondition(campaign, actor, "dazed", catalog, 5));
        assertReason(CharacterRuleViolation.Reason.STALE_REVISION, () -> service.applyCondition(campaign, actor, "dazed", catalog, 4));
        assertReason(CharacterRuleViolation.Reason.UNKNOWN_DEFINITION, () -> service.applyCondition(campaign, actor, "cursed", catalog, 6));
        assertReason(CharacterRuleViolation.Reason.UNKNOWN_ACTOR, () -> service.applyCondition(campaign, UUID.randomUUID(), "dazed", catalog, 6));
        assertEquals(5L, jdbc.queryForObject("select updated_revision from actor_condition", Long.class));
        assertEquals(3, remaining("dazed"));
    }

    /** Abilities need their pinned skill ranks and prerequisite abilities, and record the acquisition revision. */
    @Test
    void learnsAbilitiesOnlyWithPrerequisites() {
        assertReason(CharacterRuleViolation.Reason.ALREADY_LEARNED, () -> service.learnAbility(campaign, actor, "steady", catalog, 1));
        assertReason(CharacterRuleViolation.Reason.PREREQUISITE_MISSING, () -> service.learnAbility(campaign, actor, "master-riposte", catalog, 1));
        assertReason(CharacterRuleViolation.Reason.PREREQUISITE_MISSING, () -> service.learnAbility(campaign, actor, "chain-riposte", catalog, 1));
        assertReason(CharacterRuleViolation.Reason.UNKNOWN_DEFINITION, () -> service.learnAbility(campaign, actor, "levitate", catalog, 1));
        assertEquals(1, count("learned_ability"));
        inTransaction(() -> {
            service.learnAbility(campaign, actor, "riposte", catalog, 3);
            return null;
        });
        inTransaction(() -> {
            service.learnAbility(campaign, actor, "chain-riposte", catalog, 4);
            return null;
        });
        assertEquals(List.of(0L, 3L, 4L), jdbc.queryForList("select acquired_revision from learned_ability order by acquired_revision", Long.class));
        assertEquals(1, jdbc.queryForObject("select min(rank) from learned_ability", Integer.class));
    }

    /** Slot, tag, capacity and hand constraints are decided together and report every cause. */
    @Test
    void equipmentConstraintsApplyTogether() {
        UUID sword = instance("sword");
        UUID shield = instance("shield");
        UUID greatsword = addItem("greatsword");
        UUID lantern = addItem("lantern");
        UUID gilded = addItem("gilded-lantern");
        UUID kit = instance("kit");
        UUID arrows = addItem("arrows");
        // Both hands are in use and the main hand is taken.
        assertEquipment(List.of(EquipmentRules.Violation.SLOT_FULL, EquipmentRules.Violation.HANDS_EXCEEDED), greatsword);
        assertEquipment(List.of(EquipmentRules.Violation.SLOT_FULL, EquipmentRules.Violation.HANDS_EXCEEDED), lantern);
        assertEquipment(List.of(EquipmentRules.Violation.NOT_EQUIPPABLE), kit);
        assertEquipment(List.of(EquipmentRules.Violation.NOT_EQUIPPABLE), arrows);
        assertEquipment(List.of(EquipmentRules.Violation.ALREADY_WORN), sword);
        assertReason(CharacterRuleViolation.Reason.UNKNOWN_ITEM, () -> service.equip(campaign, actor, UUID.randomUUID(), catalog, 2));
        assertEquals(3, count("equipment_assignment"), "refusals write nothing");
        // Freeing the main hand is not enough for a two-handed weapon while a shield is worn.
        inTransaction(() -> {
            service.unequip(campaign, actor, sword);
            return null;
        });
        assertEquipment(List.of(EquipmentRules.Violation.HANDS_EXCEEDED), greatsword);
        inTransaction(() -> {
            service.unequip(campaign, actor, shield);
            return null;
        });
        var equipped = inTransaction(() -> service.equip(campaign, actor, greatsword, catalog, 3));
        assertEquals(new CharacterService.Equipped(greatsword, "greatsword", "main-hand", 2), equipped);
        // The off-hand slot is free but both hands are busy with the greatsword.
        assertEquipment(List.of(EquipmentRules.Violation.HANDS_EXCEEDED), lantern);
        inTransaction(() -> {
            service.unequip(campaign, actor, greatsword);
            service.equip(campaign, actor, lantern, catalog, 4);
            return null;
        });
        // An ornate lantern is mechanically the same item, so it cannot share the single off-hand slot.
        assertEquipment(List.of(EquipmentRules.Violation.SLOT_FULL), gilded);
        assertReason(CharacterRuleViolation.Reason.NOT_WORN, () -> service.unequip(campaign, actor, sword));
        assertEquals(List.of("cloak", "lantern"), inTransaction(() -> service.equipment(campaign, actor)).stream()
                .map(CharacterService.Equipped::definitionId).sorted().toList());
    }

    /** A slot with capacity two takes two rings and refuses the third. */
    @Test
    void slotCapacityBoundsIdenticalItems() {
        UUID first = addItem("ring");
        UUID second = addItem("ring");
        UUID third = addItem("ring");
        inTransaction(() -> {
            service.equip(campaign, actor, first, catalog, 1);
            service.equip(campaign, actor, second, catalog, 1);
            return null;
        });
        assertEquipment(List.of(EquipmentRules.Violation.SLOT_FULL), third);
    }

    /** An equipped instance cannot be transferred away until it is unequipped. */
    @Test
    void equippedItemsCannotBeTransferred() {
        UUID sword = instance("sword");
        UUID other = UUID.randomUUID();
        jdbc.update("insert into actor (id, campaign_id, kind, display_name, created_revision, created_at) "
                + "values (?, ?, 'NPC', 'Trader', 0, now())", other, campaign);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "update item_instance set actor_id = ?, row_version = row_version + 1 where id = ?", other, sword));
        inTransaction(() -> {
            service.unequip(campaign, actor, sword);
            return null;
        });
        assertEquals(1, jdbc.update("update item_instance set actor_id = ?, row_version = row_version + 1 where id = ?",
                other, sword));
    }

    /** Without the caller's transaction the service refuses to run, so no change can half-commit. */
    @Test
    void requiresTheCallersTransaction() {
        assertThrows(IllegalStateException.class, () -> service.sheet(campaign, actor, catalog));
        assertThrows(IllegalStateException.class, () -> service.unequip(campaign, actor, instance("sword")));
        assertThrows(IllegalStateException.class, () -> service.applyCondition(campaign, actor, "dazed", catalog, 1));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(manager).execute(status -> work.get());
    }

    /** Assert that the operation is refused with the reason and that the refusal rolls back cleanly. */
    private void assertReason(CharacterRuleViolation.Reason reason, Runnable work) {
        var violation = assertThrows(CharacterRuleViolation.class, () -> inTransaction(() -> {
            work.run();
            return null;
        }));
        assertEquals(reason, violation.reason());
    }

    private void assertEquipment(List<EquipmentRules.Violation> expected, UUID item) {
        var violation = assertThrows(CharacterRuleViolation.class,
                () -> inTransaction(() -> service.equip(campaign, actor, item, catalog, 2)));
        assertEquals(CharacterRuleViolation.Reason.EQUIPMENT, violation.reason());
        assertEquals(expected, violation.equipment());
    }

    private UUID instance(String definition) {
        return jdbc.queryForObject("select id from item_instance where campaign_id = ? and actor_id = ? and definition_id = ?",
                UUID.class, campaign, actor, definition);
    }

    private UUID addItem(String definition) {
        UUID id = UUID.randomUUID();
        int stack = definition.equals("arrows") ? 20 : 1;
        jdbc.update("insert into item_instance (id, campaign_id, actor_id, definition_id, quantity, maximum_stack, "
                + "acquired_revision) values (?, ?, ?, ?, 1, ?, 0)", id, campaign, actor, definition, stack);
        return id;
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int stacks(String condition) {
        return jdbc.queryForObject("select stacks from actor_condition where condition_id = ?", Integer.class, condition);
    }

    private int remaining(String condition) {
        return jdbc.queryForObject("select remaining_rounds from actor_condition where condition_id = ?", Integer.class, condition);
    }
}
