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
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ActorResource;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.inventory.InventoryWriter;
import org.rpgengine.inventory.ItemChange;
import org.rpgengine.inventory.ItemPlan;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of P10.2 on a campaign created from the sealed armory pack: using,
 * giving, trading, wearing and taking off item instances, and creating items from a source. Every
 * command commits its item rows, resources, events, receipt and ledger together or not at all;
 * rejections write nothing and use stable categories; a fault injected after the item rows were
 * written rolls the whole operation back; and unit conservation holds throughout (QA-10).
 */
@SpringBootTest
class ItemCommandsTest {
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
    private UUID warden;
    private long revision;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    ItemCommandsTest() { }

    /** Register the armory pack, create a campaign through production code and place a public warden beside the player. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Items", "items", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(ArmoryPack.files())))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        warden = new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, "warden", "Warden", "trailhead", 0, Instant.now());
            entities.persist(actor);
            entities.persist(ActorResource.create(actor, "vigor", 0, 2, 6, 0, Instant.now()));
            return actor.getId();
        });
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
     * QA-10: a potion's quantity and its capped healing commit together. Vigor 6 plus 4 is clamped at
     * the pool maximum 8, one unit is used up, and the receipt, events and revision describe exactly that.
     */
    @Test
    void potionConsumesOneUnitAndCapsHealingInOneCommit() {
        UUID potion = give(player, "potion", 3, 5);
        setVigor(player, 6);
        var executed = execute(new TypedCommand.UseItem(potion, List.of()));
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        assertEquals(2, quantity(potion));
        assertEquals(8L, vigor(player));
        assertEquals(1L, state("state_revision"));
        var events = events();
        assertEquals(List.of("ITEM_USED", "EFFECT_RESOLVED"), events.stream().map(row -> row.get("event_type")).toList());
        var used = JSON.readTree((String) events.get(0).get("payload"));
        assertEquals(potion.toString(), used.path("itemInstanceId").asString());
        assertEquals(1, used.path("consumed").asInt());
        var effect = JSON.readTree((String) events.get(1).get("payload")).path("outcomes").get(0);
        assertEquals("CLAMPED", effect.path("outcome").asString());
        assertEquals(2, effect.path("applied").asInt());
        // The player-safe receipt names the consumed unit and the resource change, never an engine identity.
        var receipt = executed.receipt();
        assertEquals(List.of(Map.of("kind", "CONSUMED", "definitionId", "potion", "itemInstanceId", potion.toString(),
                "quantity", 1L)), receipt.get("items"));
        assertEquals(List.of(Map.of("resourceKey", "vigor", "before", 6L, "after", 8L)), receipt.get("resources"));
        assertEquals(0, count("item_creation"));
        // At the cap the healing is clamped to nothing, yet the unit is still used; the last unit removes the row.
        execute(new TypedCommand.UseItem(potion, List.of()));
        execute(new TypedCommand.UseItem(potion, List.of()));
        assertEquals(0, jdbc.queryForObject("select count(*) from item_instance where id = ?", Integer.class, potion));
        assertEquals(8L, vigor(player));
        assertEquals(0, units("potion"));
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.UseItem(potion, List.of()));
    }

    /** A unique item changes hands under the same identity; stacks split, merge and disappear without losing a unit. */
    @Test
    void transferMovesWholeAndPartialStacksAndConservesUnits() {
        UUID kit = instance(player, "kit");
        UUID potions = give(player, "potion", 5, 5);
        var executed = execute(new TypedCommand.TransferItem(kit, warden, 1));
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        assertEquals(warden, owner(kit), "the same instance, so the same identity and artwork");
        assertEquals(List.of(Map.of("kind", "GIVEN", "definitionId", "kit", "itemInstanceId", kit.toString(),
                "quantity", 1L)), executed.receipt().get("items"));
        assertEquals("ITEM_TRANSFERRED", events().getFirst().get("event_type"));

        execute(new TypedCommand.TransferItem(potions, warden, 2));
        assertEquals(3, quantity(potions));
        assertEquals(2, quantityHeldBy(warden, "potion"));
        assertEquals(5, units("potion"));
        // The last three units fit the warden's open stack: the player's row disappears, nothing is lost.
        execute(new TypedCommand.TransferItem(potions, warden, 3));
        assertEquals(0, jdbc.queryForObject("select count(*) from item_instance where id = ?", Integer.class, potions));
        assertEquals(1, jdbc.queryForObject("select count(*) from item_instance where actor_id = ? and definition_id = 'potion'",
                Integer.class, warden));
        assertEquals(5, quantityHeldBy(warden, "potion"));
        assertEquals(5, units("potion"));
        assertEquals(3, executionsCommitted());
    }

    /** Invalid gifts are refused with stable categories and leave every table untouched. */
    @Test
    void transferRefusalsWriteNothing() {
        UUID potions = give(player, "potion", 2, 5);
        UUID wardenRing = give(warden, "ring", 1, 1);
        UUID sword = instance(player, "sword");
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.TransferItem(sword, warden, 1));
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.TransferItem(wardenRing, warden, 1));
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.TransferItem(UUID.randomUUID(), warden, 1));
        rejects(OperationAdmissionFailure.Kind.QUANTITY_ILLEGAL, new TypedCommand.TransferItem(potions, warden, 3));
        rejects(OperationAdmissionFailure.Kind.QUANTITY_ILLEGAL, new TypedCommand.TransferItem(instance(player, "kit"), warden, 2));
        rejects(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE, new TypedCommand.TransferItem(potions, player, 1));
        rejects(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE, new TypedCommand.TransferItem(potions, UUID.randomUUID(), 1));
        // A recipient elsewhere cannot be handed anything: it is out of the player's reach.
        jdbc.update("update actor set location_id = 'grove', row_version = row_version + 1 where id = ?", warden);
        rejects(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE, new TypedCommand.TransferItem(potions, warden, 1));
    }

    /** A trade swaps items when the counterparty receives at least the value it gives, and refuses everything else. */
    @Test
    void tradeExchangesItemsWhenValueBalances() {
        UUID arrows = give(player, "arrows", 10, 20);
        UUID ring = give(player, "ring", 1, 1);
        UUID wardenPotions = give(warden, "potion", 2, 5);
        var cheap = new TypedCommand.TradeItems(warden, List.of(new TypedCommand.TradeItems.Line(arrows, 5)),
                List.of(new TypedCommand.TradeItems.Line(wardenPotions, 1)));
        rejects(OperationAdmissionFailure.Kind.TRADE_REFUSED, cheap);
        var unpriced = new TypedCommand.TradeItems(warden, List.of(new TypedCommand.TradeItems.Line(instance(player, "kit"), 1)),
                List.of(new TypedCommand.TradeItems.Line(wardenPotions, 1)));
        rejects(OperationAdmissionFailure.Kind.TRADE_REFUSED, unpriced);
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.TradeItems(warden,
                List.of(new TypedCommand.TradeItems.Line(instance(player, "sword"), 1)),
                List.of(new TypedCommand.TradeItems.Line(wardenPotions, 1))));
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.TradeItems(warden,
                List.of(new TypedCommand.TradeItems.Line(arrows, 10)),
                List.of(new TypedCommand.TradeItems.Line(ring, 1))));
        rejects(OperationAdmissionFailure.Kind.QUANTITY_ILLEGAL, new TypedCommand.TradeItems(warden,
                List.of(new TypedCommand.TradeItems.Line(arrows, 10)),
                List.of(new TypedCommand.TradeItems.Line(wardenPotions, 3))));
        // One instance on both sides, or an empty side, is not a command at all.
        assertEquals(OperationAdmissionFailure.Kind.INVALID_COMMAND, assertThrows(OperationAdmissionFailure.class,
                () -> new TypedCommand.TradeItems(warden, List.of(new TypedCommand.TradeItems.Line(arrows, 1)),
                        List.of(new TypedCommand.TradeItems.Line(arrows, 1)))).kind());
        assertThrows(OperationAdmissionFailure.class, () -> new TypedCommand.TradeItems(warden, List.of(),
                List.of(new TypedCommand.TradeItems.Line(wardenPotions, 1))));

        int total = units("arrows") + units("potion") + units("ring");
        var fair = new TypedCommand.TradeItems(warden, List.of(new TypedCommand.TradeItems.Line(arrows, 10)),
                List.of(new TypedCommand.TradeItems.Line(wardenPotions, 1)));
        var executed = execute(fair);
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        assertEquals(warden, owner(arrows), "the warden holds no arrows, so the player's whole stack changed hands");
        assertEquals(10, quantityHeldBy(warden, "arrows"));
        assertEquals(1, quantityHeldBy(player, "potion"));
        assertEquals(1, quantityHeldBy(warden, "potion"));
        assertEquals(total, units("arrows") + units("potion") + units("ring"));
        var trade = JSON.readTree((String) events().getFirst().get("payload"));
        assertEquals("ITEM_TRADED", events().getFirst().get("event_type"));
        assertEquals(10, trade.path("givenValue").asInt());
        assertEquals(10, trade.path("receivedValue").asInt());
        assertEquals(List.of("GIVEN", "RECEIVED"), ((List<?>) executed.receipt().get("items")).stream()
                .map(line -> ((Map<?, ?>) line).get("kind")).toList());
        // Overpaying is allowed: the counterparty accepts any exchange that is not worse for it.
        var generous = execute(new TypedCommand.TradeItems(warden, List.of(new TypedCommand.TradeItems.Line(ring, 1)),
                List.of(new TypedCommand.TradeItems.Line(wardenPotions, 1))));
        assertEquals(OperationStatus.COMMITTED, generous.operation().status());
        assertEquals(2, quantityHeldBy(player, "potion"));
        assertEquals(1, quantityHeldBy(warden, "ring"));
    }

    /** Wearing follows the pinned slot, tag, capacity and hand rules; ownership never duplicates. */
    @Test
    void equipAndUnequipFollowTheEquipmentRules() {
        UUID shield = instance(player, "shield");
        UUID sword = instance(player, "sword");
        UUID lantern = give(player, "lantern", 1, 1);
        UUID greatsword = give(player, "greatsword", 1, 1);
        UUID wardenRing = give(warden, "ring", 1, 1);
        // Sword and shield fill both hands and both hand slots.
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.EquipItem(lantern));
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.EquipItem(greatsword));
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.EquipItem(sword));
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.EquipItem(instance(player, "kit")));
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.UnequipItem(instance(player, "kit")));
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.EquipItem(wardenRing));
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.EquipItem(UUID.randomUUID()));
        assertEquals(3, count("equipment_assignment"));

        var off = execute(new TypedCommand.UnequipItem(shield));
        assertEquals(OperationStatus.COMMITTED, off.operation().status());
        assertEquals(2, count("equipment_assignment"));
        assertEquals("ITEM_UNEQUIPPED", events().getFirst().get("event_type"));
        assertEquals(List.of(Map.of("kind", "UNEQUIPPED", "definitionId", "shield", "itemInstanceId", shield.toString(),
                "quantity", 1L)), off.receipt().get("items"));
        // The freed hand and slot now take the lantern, but not the shield and not a two-handed weapon.
        assertEquals(OperationStatus.COMMITTED, execute(new TypedCommand.EquipItem(lantern)).operation().status());
        assertEquals(3, count("equipment_assignment"));
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.EquipItem(shield));
        rejects(OperationAdmissionFailure.Kind.EQUIPMENT_ILLEGAL, new TypedCommand.EquipItem(greatsword));
        // A worn instance cannot be given away or traded; an unworn one can.
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.TransferItem(lantern, warden, 1));
        assertEquals(OperationStatus.COMMITTED, execute(new TypedCommand.TransferItem(shield, warden, 1)).operation().status());
        assertEquals(warden, owner(shield));
        assertEquals(3, count("equipment_assignment"));
    }

    /**
     * A use that consumes a unit needs the instance unworn: a worn instance is bound to its slot and cannot
     * vanish. Taking it off first makes the use legal, and the used-up instance leaves nothing behind.
     */
    @Test
    void consumingUseNeedsTheInstanceUnworn() {
        UUID torch = give(player, "torch", 1, 1);
        execute(new TypedCommand.UnequipItem(instance(player, "shield")));
        assertEquals(OperationStatus.COMMITTED, execute(new TypedCommand.EquipItem(torch)).operation().status());
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.UseItem(torch, List.of()));
        execute(new TypedCommand.UnequipItem(torch));
        setVigor(player, 3);
        assertEquals(OperationStatus.COMMITTED, execute(new TypedCommand.UseItem(torch, List.of())).operation().status());
        assertEquals(0, jdbc.queryForObject("select count(*) from item_instance where id = ?", Integer.class, torch));
        assertEquals(4L, vigor(player));
        assertEquals(2, count("equipment_assignment"));
    }

    /**
     * An item use may create items. Every created unit has a ledger row naming its operation, effect path and
     * announcing event; units merge into open stacks; a reusable tool is not consumed; a purse is.
     */
    @Test
    void createdItemsAreLinkedToTheirSource() {
        UUID press = give(player, "oil-press", 1, 1);
        var first = execute(new TypedCommand.UseItem(press, List.of()));
        assertEquals(OperationStatus.COMMITTED, first.operation().status());
        assertEquals(1, quantity(press), "a tool whose contract does not consume stays");
        assertEquals(2, quantityHeldBy(player, "lamp-oil"));
        var row = jdbc.queryForMap("select operation_id, event_sequence, source_kind, source_key, recipient_actor_id, "
                + "definition_id, quantity, applied_revision from item_creation");
        assertEquals(first.operation().operationId(), row.get("operation_id"));
        assertEquals("EFFECT", row.get("source_kind"));
        assertEquals(first.operation().operationId() + "#effects/0", row.get("source_key"));
        assertEquals(player, row.get("recipient_actor_id"));
        assertEquals("lamp-oil", row.get("definition_id"));
        assertEquals(2, row.get("quantity"));
        assertEquals(1L, row.get("applied_revision"));
        assertEquals("ITEM_CREATED", jdbc.queryForObject("select event_type from campaign_event where sequence = ?",
                String.class, row.get("event_sequence")));
        assertEquals(List.of(Map.of("kind", "CREATED", "definitionId", "lamp-oil", "quantity", 2L)),
                first.receipt().get("items"));

        // A second use is a different operation and a different source; the oil merges into one stack.
        var second = execute(new TypedCommand.UseItem(press, List.of()));
        assertEquals(1, jdbc.queryForObject("select count(*) from item_instance where definition_id = 'lamp-oil'", Integer.class));
        assertEquals(4, quantityHeldBy(player, "lamp-oil"));
        assertEquals(List.of(first.operation().operationId() + "#effects/0", second.operation().operationId() + "#effects/0"),
                jdbc.queryForList("select source_key from item_creation order by applied_revision", String.class));

        // A purse is used up and creates coins (a new stack) and more oil (merged) from two effect nodes.
        UUID purse = give(player, "lucky-purse", 2, 4);
        var opened = execute(new TypedCommand.UseItem(purse, List.of()));
        assertEquals(1, quantity(purse));
        assertEquals(50, quantityHeldBy(player, "coin"));
        assertEquals(8, quantityHeldBy(player, "lamp-oil"));
        var ledger = jdbc.queryForList("select source_key || ' ' || definition_id || ' ' || quantity from item_creation "
                + "where operation_id = ? order by source_key", String.class, opened.operation().operationId());
        assertEquals(List.of(opened.operation().operationId() + "#effects/0 coin 50",
                opened.operation().operationId() + "#effects/1 lamp-oil 4"), ledger);
        assertEquals(List.of("ITEM_USED", "ITEM_CREATED", "ITEM_CREATED"), eventTypes(opened.operation().operationId()));
        assertEquals(List.of("CONSUMED", "CREATED", "CREATED"), ((List<?>) opened.receipt().get("items")).stream()
                .map(line -> ((Map<?, ?>) line).get("kind")).toList());
        // Conservation: every unit that is not a starting item or a consumed unit is in the ledger.
        assertEquals(units("lamp-oil"), ledgerUnits("lamp-oil"));
        assertEquals(units("coin"), ledgerUnits("coin"));
        // The ledger is append-only and unique per source, recipient and definition.
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from item_creation"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update item_creation set quantity = quantity + 1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into item_creation (id, campaign_id, operation_id, "
                + "event_sequence, source_kind, source_key, recipient_actor_id, definition_id, quantity, applied_revision, "
                + "created_at) select gen_random_uuid(), campaign_id, operation_id, event_sequence, source_kind, source_key, "
                + "recipient_actor_id, definition_id, quantity, applied_revision, created_at from item_creation limit 1"));
    }

    /** Targeting, effect support and usability checks use the same stable categories as abilities. */
    @Test
    void itemUseChecksTargetsAndEffects() {
        UUID elixir = give(player, "elixir", 2, 3);
        UUID flare = give(player, "flare", 1, 3);
        UUID potion = give(player, "potion", 1, 5);
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.UseItem(elixir, List.of()));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.UseItem(elixir, List.of(warden, player)));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.UseItem(potion, List.of(warden)));
        rejects(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE, new TypedCommand.UseItem(elixir, List.of(UUID.randomUUID())));
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED, new TypedCommand.UseItem(flare, List.of(warden)));
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.UseItem(instance(player, "sword"), List.of()));
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.UseItem(UUID.randomUUID(), List.of()));

        var healed = execute(new TypedCommand.UseItem(elixir, List.of(warden)));
        assertEquals(OperationStatus.COMMITTED, healed.operation().status());
        assertEquals(5L, vigor(warden));
        assertEquals(1, quantity(elixir));
        // Another actor's pool values are engine-internal: the event is not public and the receipt omits them.
        var effect = events().get(1);
        assertEquals("EFFECT_RESOLVED", effect.get("event_type"));
        assertEquals("INTERNAL", effect.get("visibility"));
        assertEquals(List.of(), healed.receipt().get("resources"));
    }

    /**
     * A failure after the item rows were written (the presentation job insert) rolls back every effect of
     * every item command: rows, resources, events, ledger and revision; the operation records the failure.
     */
    @Test
    void faultAfterItemWritesLeavesNoPartialEffect() {
        UUID potion = give(player, "potion", 3, 5);
        UUID purse = give(player, "lucky-purse", 1, 4);
        UUID arrows = give(player, "arrows", 10, 20);
        UUID wardenPotions = give(warden, "potion", 2, 5);
        UUID shield = instance(player, "shield");
        setVigor(player, 4);
        jdbc.execute("create function test_fail_job() returns trigger language plpgsql as $$ begin "
                + "raise exception 'injected job failure' using errcode = '23514'; end; $$");
        jdbc.execute("create trigger test_fail_job before insert on presentation_job for each row execute function test_fail_job()");
        var commands = List.<TypedCommand>of(new TypedCommand.UseItem(potion, List.of()),
                new TypedCommand.UseItem(purse, List.of()), new TypedCommand.TransferItem(potion, warden, 2),
                new TypedCommand.TradeItems(warden, List.of(new TypedCommand.TradeItems.Line(arrows, 10)),
                        List.of(new TypedCommand.TradeItems.Line(wardenPotions, 1))),
                new TypedCommand.UnequipItem(shield));
        for (TypedCommand command : commands) {
            var before = effects();
            var failed = execute(command);
            assertEquals(OperationStatus.FAILED_BEFORE_COMMIT, failed.operation().status(), command.type());
            assertEquals(before, effects(), command.type());
            assertEquals(0, count("item_creation"));
            assertEquals(0, count("campaign_event"));
            assertEquals(0, jdbc.queryForObject("select count(*) from campaign_mutation_lease where operation_id is not null",
                    Integer.class));
        }
        jdbc.execute("drop trigger test_fail_job on presentation_job");
        // With the fault gone the same intention succeeds under a fresh request.
        assertEquals(OperationStatus.COMMITTED, execute(new TypedCommand.UseItem(purse, List.of())).operation().status());
        assertEquals(50, quantityHeldBy(player, "coin"));
        assertEquals(2, count("item_creation"));
    }

    /** A lost response is answered from the journal: the retry never gives, trades or creates a second time. */
    @Test
    void matchingRetryReplaysWithoutRepeating() {
        UUID arrows = give(player, "arrows", 10, 20);
        UUID wardenPotions = give(warden, "potion", 2, 5);
        UUID press = give(player, "oil-press", 1, 1);
        for (TypedCommand command : List.<TypedCommand>of(new TypedCommand.TradeItems(warden,
                List.of(new TypedCommand.TradeItems.Line(arrows, 10)),
                List.of(new TypedCommand.TradeItems.Line(wardenPotions, 1))), new TypedCommand.UseItem(press, List.of()))) {
            var input = envelope(command);
            var first = executions.execute(owner, adventure, campaign, input);
            revision = first.operation().committedRevision();
            var before = world();
            var replay = executions.execute(owner, adventure, campaign, input);
            assertTrue(replay.replayed());
            assertEquals(first.receipt(), replay.receipt());
            assertEquals(before, world());
        }
        assertEquals(2, quantityHeldBy(warden, "lamp-oil") + quantityHeldBy(player, "lamp-oil"));
    }

    /**
     * The writer refuses plans resolved from state that moved on: a wrong owner, quantity or storage
     * version, and any reassignment of a worn instance, each leaving the rows as they were.
     */
    @Test
    void writerRefusesStaleOrWornInstances() {
        UUID kit = instance(player, "kit");
        UUID sword = instance(player, "sword");
        long version = jdbc.queryForObject("select row_version from item_instance where id = ?", Long.class, kit);
        var stale = List.of(
                new ItemPlan(List.of(new ItemChange.Update(kit, "kit", version + 1, player, 1, warden, 1)), List.of(),
                        List.of(), List.of()),
                new ItemPlan(List.of(new ItemChange.Update(kit, "kit", version, warden, 1, player, 1)), List.of(),
                        List.of(), List.of()),
                new ItemPlan(List.of(new ItemChange.Delete(kit, "kit", version, player, 2)), List.of(),
                        List.of(new ItemPlan.Consumption("kit", 2)), List.of()),
                new ItemPlan(List.of(new ItemChange.Delete(kit, "potion", version, player, 1)), List.of(),
                        List.of(new ItemPlan.Consumption("potion", 1)), List.of()));
        for (ItemPlan plan : stale) {
            var before = world();
            assertThrows(IllegalStateException.class, () -> inTransaction(plan));
            assertEquals(before, world());
        }
        var before = world();
        // A worn instance is held by the equipment foreign key: it cannot change owner or disappear.
        assertThrows(DataAccessException.class, () -> inTransaction(new ItemPlan(
                List.of(new ItemChange.Update(sword, "sword", 0, player, 1, warden, 1)), List.of(), List.of(), List.of())));
        assertThrows(DataAccessException.class, () -> inTransaction(new ItemPlan(
                List.of(new ItemChange.Delete(sword, "sword", 0, player, 1)), List.of(),
                List.of(new ItemPlan.Consumption("sword", 1)), List.of())));
        assertEquals(before, world());
    }

    /**
     * Resolved item effects may only touch the intention's participants: a forged plan that moves, removes,
     * creates or wears an item of an uninvolved actor is refused before anything is written.
     */
    @Test
    void attributionRefusesItemEffectsOnUninvolvedActors() {
        UUID bystander = UUID.randomUUID();
        UUID stack = UUID.randomUUID();
        var world = new ResolvedCommit.WorldChange(true, 0, org.rpgengine.campaign.CampaignMode.EXPLORATION, null);
        var context = new ResolvedCommit.Context(adventure, campaign, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
                0, 2, new MutationLeaseService.Token(UUID.randomUUID(), 1));
        var event = new ResolvedCommit.Event(player, null, org.rpgengine.campaign.EventVisibility.PUBLIC,
                new org.rpgengine.campaign.CampaignEvent.Body("ITEM_TRANSFERRED", 1, 1, "{}"));
        java.util.function.Function<ItemPlan, ResolvedCommit> plan = items -> new ResolvedCommit(context, world, List.of(),
                List.of(event), List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), List.of(), items);
        var participants = java.util.Set.of(player, warden);
        // Moves between participants are fine; so is a creation for a participant and wearing by one.
        CommandRules.requireAttributed(jdbc, campaign, participants, plan.apply(new ItemPlan(
                List.of(new ItemChange.Update(stack, "potion", 0, player, 2, warden, 2)), List.of(), List.of(), List.of())));
        CommandRules.requireAttributed(jdbc, campaign, participants, plan.apply(new ItemPlan(
                List.of(new ItemChange.Insert(stack, warden, "coin", 5, 9)), List.of(new ItemPlan.Creation(warden, "coin", 5,
                        ItemPlan.SourceKind.EFFECT, "op#effects/0", 0)), List.of(), List.of(new ItemPlan.Equip(player, stack)))));
        for (ItemPlan forged : List.of(
                new ItemPlan(List.of(new ItemChange.Update(stack, "potion", 0, bystander, 2, player, 2)), List.of(), List.of(), List.of()),
                new ItemPlan(List.of(new ItemChange.Update(stack, "potion", 0, player, 2, bystander, 2)), List.of(), List.of(), List.of()),
                new ItemPlan(List.of(new ItemChange.Delete(stack, "potion", 0, bystander, 2)), List.of(),
                        List.of(new ItemPlan.Consumption("potion", 2)), List.of()),
                new ItemPlan(List.of(new ItemChange.Insert(stack, bystander, "coin", 5, 9)), List.of(new ItemPlan.Creation(player,
                        "coin", 5, ItemPlan.SourceKind.EFFECT, "op#effects/0", 0)), List.of(), List.of()),
                new ItemPlan(List.of(new ItemChange.Insert(stack, player, "coin", 5, 9)), List.of(new ItemPlan.Creation(bystander,
                        "coin", 5, ItemPlan.SourceKind.EFFECT, "op#effects/0", 0)), List.of(), List.of()),
                new ItemPlan(List.of(), List.of(), List.of(), List.of(new ItemPlan.Equip(bystander, stack))),
                new ItemPlan(List.of(), List.of(), List.of(), List.of(new ItemPlan.Unequip(bystander, stack))))) {
            assertThrows(IllegalStateException.class, () -> CommandRules.requireAttributed(jdbc, campaign, participants,
                    plan.apply(forged)), forged.toString());
        }
    }

    /** Run a plan's rows through the writer in a transaction that is rolled back or thrown away by the caller. */
    private void inTransaction(ItemPlan plan) {
        new TransactionTemplate(manager).executeWithoutResult(status ->
                InventoryWriter.applyWithin(jdbc, campaign, UUID.randomUUID(), 99, 1, plan, Instant.now()));
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
        assertEquals(0, count("operation_journal where status not in ('COMMITTED', 'COMMITTED_NARRATION_PENDING')"));
    }

    /** Insert a stack held by an actor, outside any command (a test-only way to stock inventories). */
    private UUID give(UUID actor, String definition, int quantity, int maxStack) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into item_instance (id, campaign_id, actor_id, definition_id, quantity, maximum_stack, "
                + "acquired_revision) values (?, ?, ?, ?, ?, ?, 0)", id, campaign, actor, definition, quantity, maxStack);
        return id;
    }

    /** First instance of a definition held by an actor, in identity order. */
    private UUID instance(UUID actor, String definition) {
        return jdbc.queryForObject("select id from item_instance where campaign_id = ? and actor_id = ? and definition_id = ? "
                + "order by id limit 1", UUID.class, campaign, actor, definition);
    }

    private int quantity(UUID instance) {
        return jdbc.queryForObject("select quantity from item_instance where id = ?", Integer.class, instance);
    }

    private int quantityHeldBy(UUID actor, String definition) {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from item_instance where campaign_id = ? "
                + "and actor_id = ? and definition_id = ?", Integer.class, campaign, actor, definition);
    }

    private int units(String definition) {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from item_instance where campaign_id = ? "
                + "and definition_id = ?", Integer.class, campaign, definition);
    }

    private int ledgerUnits(String definition) {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from item_creation where campaign_id = ? "
                + "and definition_id = ?", Integer.class, campaign, definition);
    }

    private UUID owner(UUID instance) {
        return jdbc.queryForObject("select actor_id from item_instance where id = ?", UUID.class, instance);
    }

    private long vigor(UUID actor) {
        return jdbc.queryForObject("select current_value from actor_resource where campaign_id = ? and actor_id = ? "
                + "and resource_key = 'vigor'", Long.class, campaign, actor);
    }

    private void setVigor(UUID actor, long value) {
        jdbc.update("update actor_resource set current_value = ?, row_version = row_version + 1 where campaign_id = ? "
                + "and actor_id = ? and resource_key = 'vigor'", value, campaign, actor);
    }

    private long state(String column) {
        return jdbc.queryForObject("select " + column + " from campaign where id = ?", Long.class, campaign);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int executionsCommitted() {
        return count("operation_journal where status in ('COMMITTED', 'COMMITTED_NARRATION_PENDING')");
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("select event_type, visibility, payload::text as payload from campaign_event "
                + "where campaign_id = ? order by sequence", campaign);
    }

    private List<String> eventTypes(UUID operation) {
        return jdbc.queryForList("select event_type from campaign_event where operation_id = ? order by sequence",
                String.class, operation);
    }

    /** Every table the item commands can touch, with storage versions, for exact before/after comparison. */
    private Map<String, List<String>> world() {
        return tables(List.of("campaign", "actor", "actor_resource", "item_instance", "equipment_assignment",
                "item_creation", "campaign_event", "operation_journal", "canonical_outbox", "query_epoch"));
    }

    /** The same comparison without the journal, which legitimately gains a row when an operation fails after admission. */
    private Map<String, List<String>> effects() {
        return tables(List.of("campaign", "actor", "actor_resource", "item_instance", "equipment_assignment",
                "item_creation", "campaign_event", "canonical_outbox", "query_epoch"));
    }

    private Map<String, List<String>> tables(List<String> names) {
        var tables = new java.util.LinkedHashMap<String, List<String>>();
        for (String table : names) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }

    /** Guard against a vacuous comparison: the snapshots really cover item rows. */
    @Test
    void worldSnapshotCoversItemRows() {
        var before = world();
        give(player, "coin", 1, 999);
        assertNotEquals(before, world());
        assertFalse(before.get("item_instance").isEmpty());
    }
}
