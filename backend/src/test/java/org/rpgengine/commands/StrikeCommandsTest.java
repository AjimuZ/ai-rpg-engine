package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Real PostgreSQL acceptance of P11.3 on campaigns created from the sealed encounter fixture. Campaign dice are
 * unpredictable, so each case either fixes the outcome through the fixture (a target nobody can miss or nobody can hit)
 * or recalculates the expected result from the dice its receipt lists: a strike's calculation matches its recorded
 * draws, a miss pays its costs, the player's Health pool falls only as far as zero, the opening strike resolves once at
 * its slot, Guard and healing behave as documented, ammunition leaves the inventory, every refusal writes nothing,
 * retries replay, hidden values stay out of the public record and the database repeats the Health guarantees.
 */
@SpringBootTest
class StrikeCommandsTest {
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
    StrikeCommandsTest() { }

    /** Register the encounter pack and create a campaign at the trailhead through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Strikes", "strikes", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(EncounterPack.files())))).revisionId();
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
     * A strike against an unhittable target is a recorded miss: two d10, its points paid, nothing else changes. Against
     * Guard 0 it is always a strong hit whose calculation matches its recorded dice; the public event and receipt leave
     * the target's defense, armor and Health to the internal record, and a retry replays the same receipt with no new draw.
     */
    @Test
    void aStrikeIsRecordedAndReplayed() {
        begin("close-brawl");
        var miss = execute(new TypedCommand.Strike("sword", "wall"));
        var missed = draws(miss);
        assertEquals(2, missed.size());
        assertEquals(List.of(10, 10), missed.stream().map(draw -> draw.get("sides")).toList());
        assertEquals(1, combat(miss).get("actionPoints"), "a legal miss pays its points");
        assertEquals(20, number("wall", "health_current"));
        var missLine = (Map<?, ?>) ((List<?>) combat(miss).get("strikes")).getFirst();
        assertEquals(false, missLine.get("hit"));
        assertEquals(0, missLine.get("damage"));
        assertEquals(0L, miss.receipt().get("elapsedSeconds"));

        execute(new TypedCommand.EndActivation());
        var envelope = envelope(new TypedCommand.Strike("sword", "dummy"));
        var hit = executions.execute(owner, adventure, campaign, envelope);
        assertEquals(OperationStatus.COMMITTED, hit.operation().status());
        revision = hit.operation().committedRevision();
        var dice = draws(hit);
        assertEquals(List.of(10, 10, 6, 4), dice.stream().map(draw -> draw.get("sides")).toList());
        // Sword: force 2 + rank 2; damage die + force 2 + strong die, against no armor.
        int total = value(dice, 0) + value(dice, 1) + 4;
        int damage = value(dice, 2) + 2 + value(dice, 3);
        assertEquals(999 - damage, number("dummy", "health_current"));
        var line = (Map<?, ?>) ((List<?>) combat(hit).get("strikes")).getFirst();
        assertEquals(total, line.get("attackTotal"));
        assertEquals(true, line.get("strongHit"));
        assertEquals(damage, line.get("damage"));
        assertEquals("player", line.get("attackerId"));

        var shown = event("STRIKE_RESOLVED");
        assertEquals(total, shown.get("attackTotal").intValue());
        assertEquals(player.toString(), shown.get("actorId").asString());
        for (String hidden : List.of("defense", "armor", "rawDamage", "healthAfter", "targetHealthAfter", "margin")) {
            assertFalse(shown.has(hidden), hidden);
        }
        assertEquals("PUBLIC", visibility("STRIKE_RESOLVED"));
        var calculation = event("STRIKE_CALCULATED");
        assertEquals("INTERNAL", visibility("STRIKE_CALCULATED"));
        assertEquals(0, calculation.get("defense").intValue());
        assertEquals(total, calculation.get("margin").intValue());
        assertEquals(damage, calculation.get("rawDamage").intValue());
        assertEquals(999, calculation.get("healthBefore").intValue());
        assertEquals(999 - damage, calculation.get("healthAfter").intValue());
        assertEquals(List.of(0, 1, 2, 3), JSON.convertValue(calculation.get("drawOrdinals"), List.class));
        assertFalse(hit.receipt().toString().contains("rawDamage"));

        // The lost response replays: same receipt, no new draw, no second hit.
        long recorded = count("operation_random_draw");
        var again = executions.execute(owner, adventure, campaign, envelope);
        assertTrue(again.replayed());
        assertEquals(hit.receipt(), again.receipt());
        assertEquals(recorded, count("operation_random_draw"));
        assertEquals(999 - damage, number("dummy", "health_current"));
    }

    /** Every illegal strike and Guard is refused before anything is written, with its own category. */
    @Test
    void illegalAttacksWriteNothing() {
        begin("close-brawl");
        rejects(OperationAdmissionFailure.Kind.ATTACK_UNAVAILABLE, new TypedCommand.Strike("axe", "dummy"));
        rejects(OperationAdmissionFailure.Kind.ATTACK_UNAVAILABLE, new TypedCommand.Strike("shield", "dummy"));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Strike("sword", "ghost"));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Strike("sword", "player"));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Strike("sword", "porter"));
        execute(new TypedCommand.Guard());
        rejects(OperationAdmissionFailure.Kind.GUARD_UNAVAILABLE, new TypedCommand.Guard());
        execute(new TypedCommand.Strike("sword", "wall"));
        rejects(OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT, new TypedCommand.Strike("sword", "wall"));
        // Tactical attacks need the mode and the turn, like every tactical intention.
        jdbc.update("update encounter set phase = 'ACTOR_END', action_points = 0, row_version = row_version + 1");
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.Strike("sword", "wall"));
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.Guard());
    }

    /** Before a fight they are refused by the mode gate, and melee out of reach by the target rule. */
    @Test
    void attacksNeedTheModeAndTheReach() {
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.Strike("sword", "raider"));
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.Guard());
        begin("trail-ambush");
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.Strike("sword", "raider"));
        var options = options(execute(new TypedCommand.CombatSpeak("raider", "Come closer.")));
        var sword = (Map<?, ?>) ((List<?>) options.get("strikes")).getFirst();
        assertEquals("sword", sword.get("attackId"));
        assertEquals("NO_TARGET_IN_REACH", sword.get("disabled"));
        assertEquals(List.of(), sword.get("targets"));
    }

    /**
     * The armed bandit of the worked fight approaches and strikes: each strike's result follows from its recorded dice and
     * the frozen values, the player's Health pool falls by exactly that and stops at zero, and a defeated player is
     * marked, draws no further strikes and has no decision left, with the fight waiting at a round end.
     */
    @Test
    void anOpponentStrikesUntilThePlayerFalls() {
        begin("yard-duel");
        long health = pool("vigor");
        assertEquals(1, jdbc.queryForObject("select count(*) from encounter_participant where participant_id = 'player' "
                + "and (frozen ->> 'armor')::integer = 1 and (frozen ->> 'guard')::integer = 12", Integer.class),
                "the cloak's armor and Guard 12 froze at entry");
        var seen = new ArrayList<JsonNode>();
        for (int turn = 0; turn < 29 && pool("vigor") > 0; turn++) {
            int before = eventCount("STRIKE_CALCULATED");
            var ended = execute(new TypedCommand.EndActivation());
            var strikes = events("STRIKE_CALCULATED");
            for (JsonNode strike : strikes.subList(before, strikes.size())) {
                seen.add(strike);
                assertEquals("bandit", strike.get("participantId").asString());
                assertEquals("player", strike.get("targetId").asString());
                // Bandit: force 1 + skill 1 against the player's Guard 12 and its default Brace, its die + force 1 - armor 1.
                int[] attack = JSON.convertValue(strike.get("attackDice"), int[].class);
                int total = attack[0] + attack[1] + 2;
                assertEquals(total, strike.get("attackTotal").intValue());
                assertEquals(14, strike.get("defense").intValue());
                assertEquals(total >= 14, strike.get("hit").asBoolean());
                long loss = 0;
                assertFalse(strike.get("strongHit").asBoolean(), "the cudgel permits no strong hit");
                if (strike.get("hit").asBoolean()) {
                    int die = JSON.convertValue(strike.get("damageDice"), int[].class)[0];
                    assertEquals(die + 1, strike.get("rawDamage").intValue());
                    assertEquals(1, strike.get("effectiveArmor").intValue());
                    loss = Math.min(health, die);
                }
                assertEquals(health, strike.get("healthBefore").longValue());
                assertEquals(health - loss, strike.get("healthAfter").longValue());
                health -= loss;
            }
            assertEquals(health, pool("vigor"));
            assertEquals(health == 0, participant("player", "status").equals("DEFEATED"));
            // The receipt shows the player's own pool whenever this operation changed it.
            for (Object change : (List<?>) ended.receipt().get("resources")) {
                assertEquals(health, ((Number) ((Map<?, ?>) change).get("after")).longValue());
            }
        }
        assertFalse(seen.isEmpty());
        assertEquals(0, pool("vigor"), "the bandit lands enough hits over 29 rounds; Health stops at zero");
        assertEquals("DEFEATED", participant("player", "status"));
        assertEquals(1, eventCount("PARTICIPANT_DEFEATED"));
        var stored = EncounterWriter.state(jdbc, campaign).orElseThrow();
        assertEquals(EncounterPhase.ROUND_END, stored.phase());
        assertFalse(stored.playerDecision());
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.EndActivation());
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.Guard());
    }

    /** The Guard action costs its point, raises the Guard the opponent's strike meets and ends at the next activation. */
    @Test
    void theGuardActionMeetsTheNextStrike() {
        begin("yard-duel");
        var guarded = execute(new TypedCommand.Guard());
        assertEquals(2, combat(guarded).get("actionPoints"));
        assertEquals("true", jdbc.queryForObject("select guarding::text from encounter_participant "
                + "where participant_id = 'player'", String.class));
        assertEquals(false, ((Map<?, ?>) options(guarded).get("guard")).get("available"));
        // The bandit needs its first activation to walk and strike (or to strike if it already walked before the player).
        int before = eventCount("STRIKE_CALCULATED");
        execute(new TypedCommand.EndActivation());
        var strikes = events("STRIKE_CALCULATED");
        assertEquals(before + 1, strikes.size());
        assertEquals(14, strikes.getLast().get("defense").intValue());
        assertEquals("false", jdbc.queryForObject("select guarding::text from encounter_participant "
                + "where participant_id = 'player'", String.class), "gone when the next activation starts");
    }

    /** {@code first}: the opening strike hits once right after round one opens and the player's points are already short. */
    @Test
    void anOpeningStrikeUnderFirstResolvesOnce() {
        execute(new TypedCommand.Engage("close-rush", "dummy"));
        assertEquals("PENDING", opening());
        var rolled = execute(new TypedCommand.RollInitiative());
        assertEquals("RESOLVED", opening());
        assertEquals(1, combat(rolled).get("actionPoints"));
        assertEquals(1, eventCount("STRIKE_RESOLVED"));
        var dice = draws(rolled);
        assertEquals(4 + 4, dice.size(), "two participants' initiative, then attack, damage and strong dice");
        int damage = value(dice, 6) + 2 + value(dice, 7);
        assertEquals(999 - damage, number("dummy", "health_current"));
        assertTrue(event("STRIKE_RESOLVED").get("opening").asBoolean());
        // Nothing resolves it again.
        execute(new TypedCommand.EndActivation());
        assertEquals(1, eventCount("STRIKE_RESOLVED"));
        assertEquals(999 - damage, number("dummy", "health_current"));
    }

    /** {@code initiative}: it resolves at the player's own round-one slot and is paid from that activation. */
    @Test
    void anOpeningStrikeUnderInitiativeResolvesAtItsSlot() {
        execute(new TypedCommand.Engage("close-brawl", "wall"));
        var rolled = execute(new TypedCommand.RollInitiative());
        assertEquals("RESOLVED", opening());
        assertEquals(1, combat(rolled).get("actionPoints"));
        assertEquals(false, event("STRIKE_RESOLVED").get("hit").asBoolean());
        assertEquals(1, eventCount("STRIKE_RESOLVED"));
        assertEquals(1, event("STRIKE_RESOLVED").get("remainingActionPoints").intValue());
    }

    /** An opening the weapon cannot reach at its slot lapses publicly and costs nothing. */
    @Test
    void anUnreachableOpeningLapses() {
        execute(new TypedCommand.Engage("trail-ambush", "raider"));
        var rolled = execute(new TypedCommand.RollInitiative());
        assertEquals("RESOLVED", opening());
        assertEquals(3, combat(rolled).get("actionPoints"));
        assertEquals("OUT_OF_RANGE", event("OPENING_STRIKE_LAPSED").get("reason").asString());
        assertEquals(0, eventCount("STRIKE_RESOLVED"));
        assertEquals(4, ((List<?>) rolled.receipt().get("randomDraws")).size(), "only the initiative dice");
    }

    /**
     * A ranged weapon worn at entry reaches an adjacent zone: partial cover adds its +2 from outside the zone, one unit of
     * ammunition leaves the inventory per attempt hit or miss, and with none left the attack is refused and its option
     * says why. A later equipment change would not alter the frozen attack.
     */
    @Test
    void aRangedAttackUsesCoverAndAmmunition() {
        arm();
        give("arrows", 2, 20);
        begin("trail-ambush");
        execute(new TypedCommand.Strike("bolt-caster", "raider"));
        assertEquals(16, event("STRIKE_CALCULATED").get("defense").intValue(),
                "Guard 12 + 2 partial cover from outside the yard + 2 from the raider's default Brace");
        assertEquals(1, arrows());
        execute(new TypedCommand.EndActivation());
        var line = (Map<?, ?>) ((List<?>) options(execute(new TypedCommand.CombatSpeak("raider", "Again."))).get("strikes")).getFirst();
        assertEquals(List.of("raider"), line.get("targets"));
        execute(new TypedCommand.Strike("bolt-caster", "raider"));
        assertEquals(0, arrows());
        rejects(OperationAdmissionFailure.Kind.ATTACK_UNAVAILABLE, new TypedCommand.Strike("bolt-caster", "raider"));
        var spent = options(execute(new TypedCommand.EndActivation()));
        var none = (Map<?, ?>) ((List<?>) spent.get("strikes")).getFirst();
        assertEquals("NO_AMMUNITION", none.get("disabled"));
        assertEquals(2, jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? and "
                + "event_type = 'STRIKE_RESOLVED' and payload ->> 'ammunition' = 'arrows'", Integer.class, campaign));
    }

    /**
     * A healing item restores at most the missing Health, consumes its unit and pays the action; unknown and mistargeted
     * items are refused without a write.
     */
    @Test
    void anItemHealsWithinTheMaximum() {
        UUID salve = give("salve", 3, 5);
        begin("close-brawl");
        long maximum = jdbc.queryForObject("select maximum_value from actor_resource where actor_id = ? and resource_key = 'vigor'",
                Long.class, player);
        setPool(maximum - 5);
        var used = execute(new TypedCommand.CombatUseItem(salve, null));
        assertEquals(maximum - 2, pool("vigor"));
        assertEquals(1, combat(used).get("actionPoints"));
        assertEquals(2, quantity(salve));
        var resources = (List<?>) used.receipt().get("resources");
        assertEquals(maximum - 2, ((Number) ((Map<?, ?>) resources.getFirst()).get("after")).longValue());
        assertEquals(List.of("ITEM_USED", "EFFECT_RESOLVED", "COMBAT_ITEM_USED"), types().subList(types().size() - 3, types().size()));
        rejects(OperationAdmissionFailure.Kind.ACTION_POINTS_INSUFFICIENT, new TypedCommand.CombatUseItem(salve, null));
        execute(new TypedCommand.EndActivation());

        // One point missing: the salve restores that one and still costs what it costs.
        setPool(maximum - 1);
        execute(new TypedCommand.CombatUseItem(salve, null));
        assertEquals(maximum, pool("vigor"), "healing stops at the maximum");
        assertEquals("CLAMPED", event("EFFECT_RESOLVED").path("outcomes").get(0).get("outcome").asString());
        assertEquals(1, quantity(salve));
        execute(new TypedCommand.EndActivation());

        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.CombatUseItem(salve, "porter"));
        rejects(OperationAdmissionFailure.Kind.ITEM_UNAVAILABLE, new TypedCommand.CombatUseItem(UUID.randomUUID(), null));
    }

    /** A bandage heals an ally beside the player in its encounter-local Health, up to its maximum; never a defeated one. */
    @Test
    void anItemHealsAnAllyBeside() {
        UUID bandage = give("bandage", 4, 5);
        begin("close-brawl");
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.CombatUseItem(bandage, "lookout"));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.CombatUseItem(bandage, "dummy"));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.CombatUseItem(bandage, null));
        setLocalHealth("porter", 3);
        var used = execute(new TypedCommand.CombatUseItem(bandage, "porter"));
        assertEquals(7, number("porter", "health_current"));
        assertEquals(1, combat(used).get("actionPoints"));
        assertEquals(3, quantity(bandage));
        var announced = event("COMBAT_ITEM_USED");
        assertEquals("porter", announced.get("targetId").asString());
        assertEquals(4, announced.get("healed").intValue());
        assertEquals("INTERNAL", visibility("EFFECT_RESOLVED"));
        execute(new TypedCommand.EndActivation());
        var last = execute(new TypedCommand.CombatUseItem(bandage, "porter"));
        assertEquals(9, number("porter", "health_current"), "only the two points missing");
        assertEquals(2, event("COMBAT_ITEM_USED").get("healed").intValue());
        assertEquals(1, combat(last).get("actionPoints"));
        execute(new TypedCommand.EndActivation());
        setLocalHealth("porter", 0);
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.CombatUseItem(bandage, "porter"));
        // The same item may also be used on the player, by name.
        setPool(1);
        execute(new TypedCommand.CombatUseItem(bandage, "player"));
        assertEquals(5, pool("vigor"));
    }

    /**
     * A damaging item stays unsupported in a fight until effects can target participants; a contract with more dice than
     * the operation can spare is refused; an ally without a campaign actor cannot receive created units, though the player
     * can. Nothing is written by a refusal.
     */
    @Test
    void unsupportedEffectsAreRefused() {
        UUID flare = give("flare", 1, 3);
        UUID tonic = give("storm-tonic", 1, 5);
        UUID pouch = give("gift-pouch", 2, 5);
        begin("close-brawl");
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED, new TypedCommand.CombatUseItem(flare, "porter"));
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED, new TypedCommand.CombatUseItem(tonic, null));
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED, new TypedCommand.CombatUseItem(pouch, "porter"));
        execute(new TypedCommand.CombatUseItem(pouch, "player"));
        assertEquals(1, quantity(pouch));
        assertEquals(1, jdbc.queryForObject("select coalesce(sum(quantity), 0) from item_instance where actor_id = ? "
                + "and definition_id = 'coin'", Integer.class, player));
    }

    /**
     * {@code modifyResource} has no meaning on an ally without a campaign actor: it is recorded as unaffected, and above
     * all it does not fall through to the player who used the item.
     */
    @Test
    void aResourceEffectDoesNotReachAnAllyOrFallBackOnThePlayer() {
        UUID elixir = give("elixir", 1, 3);
        begin("close-brawl");
        setPool(2);
        setLocalHealth("porter", 3);
        execute(new TypedCommand.CombatUseItem(elixir, "porter"));
        assertEquals(2, pool("vigor"), "the elixir targets the ally, never its user");
        assertEquals(3, number("porter", "health_current"));
        var effect = event("EFFECT_RESOLVED").path("outcomes").get(0);
        assertEquals("porter", effect.get("participantId").asString());
        assertEquals("NO_RESOURCE", effect.get("outcome").asString());
    }

    /** {@code heal} needs to know which resource is Health: content without a combat rule refuses it instead of guessing. */
    @Test
    void healingNeedsACombatRule() throws Exception {
        var files = new java.util.TreeMap<>(ArmoryPack.files());
        String items = new String(files.get("items.json"), java.nio.charset.StandardCharsets.UTF_8);
        int end = items.lastIndexOf("]}");
        files.put("items.json", (items.substring(0, end) + """
                ,{"id":"salve","kind":"item","data":{"name":"Salve","description":"Closes wounds.","itemType":"consumable",
                  "maxStack":5,"rarity":"ordinary","use":{"target":{"policy":"self"},"effects":[{"type":"heal","amount":3,"target":"self"}]},
                  "appearance":{"id":"salve.tin","summary":"A tin of salve."}}}""" + items.substring(end))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Under its own pack ID: the same ID with other bytes would be a hash conflict with the fixture registered above.
        files.put("adventure.json", new String(files.get("adventure.json"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"sample:armory\"", "\"sample:armory-plain\"").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(files)))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "No combat", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        revision = 0;
        UUID salve = give("salve", 1, 5);
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED, new TypedCommand.UseItem(salve, List.of()));
    }

    /**
     * A stored receipt from before these options existed replays exactly as it was: no empty standings, strikes or
     * invented Guard and item prices appear in it.
     */
    @Test
    void anEarlierReceiptKeepsItsShape() {
        var view = CommandReceipts.project("""
                {"stateRevision":3,"firstEventSequence":4,"lastEventSequence":4,"randomDraws":[],"details":{
                "resolver":"local-v1","commandType":"END_ACTIVATION","elapsedSeconds":8,"turnAdvanced":true,"actorId":"a",
                "combat":{"encounterId":"e","phase":"AWAITING_ACTION","round":2,"zone":"entry","acting":"player","actionPoints":3,
                "initiative":[],"options":{"moves":[],"speech":{"remaining":1,"recipients":[]},"actions":[],"endActivation":true}}}}""");
        var combat = (Map<?, ?>) view.get("combat");
        assertFalse(combat.containsKey("participants"));
        assertFalse(combat.containsKey("strikes"));
        var options = (Map<?, ?>) combat.get("options");
        for (String added : List.of("strikes", "guard", "useItem")) {
            assertFalse(options.containsKey(added), added);
        }
    }

    /** In exploration a {@code heal} effect restores the same Health, within the maximum, and never revives. */
    @Test
    void healingOutsideAFightFollowsTheSameRule() {
        UUID salve = give("salve", 2, 5);
        long maximum = pool("vigor");
        setPool(maximum - 1);
        execute(new TypedCommand.UseItem(salve, List.of()));
        assertEquals(maximum, pool("vigor"));
        setPool(0);
        execute(new TypedCommand.UseItem(salve, List.of()));
        assertEquals(0, pool("vigor"), "ordinary healing does not revive");
        assertEquals("NOT_REVIVED", event("EFFECT_RESOLVED").path("outcomes").get(0).get("outcome").asString());
    }

    /** The database keeps local Health in bounds, zero Health off its feet and the defeated defeated, whatever the writer. */
    @Test
    void theDatabaseGuardsVitals() {
        begin("close-brawl");
        String health = "update encounter_participant set health_current = ?, row_version = row_version + 1 where participant_id = ?";
        assertThrows(DataAccessException.class, () -> jdbc.update(health, 21, "wall"), "above the frozen maximum");
        assertThrows(DataAccessException.class, () -> jdbc.update(health, 0, "wall"), "zero Health cannot stand");
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter_participant set guarding = true, status = 'DEFEATED', "
                + "row_version = row_version + 1 where participant_id = 'wall'"), "a defeated participant holds no stance");
        assertEquals(1, jdbc.update("update encounter_participant set health_current = 0, status = 'DEFEATED', "
                + "row_version = row_version + 1 where participant_id = 'wall'"));
        assertThrows(DataAccessException.class, () -> jdbc.update(health, 5, "wall"), "ordinary healing does not revive");
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter_participant set status = 'ACTIVE', "
                + "row_version = row_version + 1 where participant_id = 'wall'"), "a defeated participant does not return");
        // A participant defeated with Health left (a later settlement policy may do so) does not return either.
        assertEquals(1, jdbc.update("update encounter_participant set status = 'DEFEATED', row_version = row_version + 1 "
                + "where participant_id = 'lookout'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update encounter_participant set status = 'ACTIVE', "
                + "row_version = row_version + 1 where participant_id = 'lookout'"), "with Health left too");
        assertEquals(1, jdbc.update(health, 5, "porter"));
        assertEquals(1, jdbc.update("update encounter_participant set guarding = true, row_version = row_version + 1 "
                + "where participant_id = 'porter'"));
    }

    /** A fight frozen before P11.3 has no attacks in its frozen forms: its participants read back unable to attack. */
    @Test
    void anOlderFrozenFormReadsBackUnarmed() {
        begin("close-brawl");
        jdbc.execute("alter table encounter_participant disable trigger encounter_participant_frozen");
        try {
            jdbc.update("update encounter_participant set frozen = frozen - 'attacks', row_version = row_version + 1");
        } finally {
            jdbc.execute("alter table encounter_participant enable trigger encounter_participant_frozen");
        }
        var stored = EncounterWriter.state(jdbc, campaign).orElseThrow();
        assertTrue(stored.player().sheet().attacks().isEmpty());
        rejects(OperationAdmissionFailure.Kind.ATTACK_UNAVAILABLE, new TypedCommand.Strike("sword", "dummy"));
        assertEquals(List.of(), options(execute(new TypedCommand.Guard())).get("strikes"));
    }

    /** The new refusals have their stable public codes at HTTP 422. */
    @Test
    void refusalsHaveStableCodes() {
        for (var kind : Map.of(OperationAdmissionFailure.Kind.ATTACK_UNAVAILABLE, "COMMAND_ATTACK_UNAVAILABLE",
                OperationAdmissionFailure.Kind.GUARD_UNAVAILABLE, "COMMAND_GUARD_UNAVAILABLE").entrySet()) {
            org.springframework.http.ResponseEntity<Map<String, String>> response = org.springframework.test.util.ReflectionTestUtils
                    .invokeMethod(org.rpgengine.campaign.CampaignCreationController.class, "failure",
                            new OperationAdmissionFailure(kind.getKey()));
            assertEquals(422, response.getStatusCode().value());
            assertEquals(Map.of("code", kind.getValue()), response.getBody());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Enter a template at the trailhead and roll initiative. */
    private void begin(String template) {
        execute(new TypedCommand.Engage(template, null));
        execute(new TypedCommand.RollInitiative());
        assertTrue(EncounterWriter.state(jdbc, campaign).orElseThrow().playerDecision());
    }

    /** Swap the shield for the bolt caster before a fight, through the equipment commands. */
    private void arm() {
        UUID caster = give("bolt-caster", 1, 1);
        UUID shield = jdbc.queryForObject("select id from item_instance where campaign_id = ? and actor_id = ? and definition_id = 'shield'",
                UUID.class, campaign, player);
        execute(new TypedCommand.UnequipItem(shield));
        execute(new TypedCommand.EquipItem(caster));
    }

    /** Stock the player's inventory directly, as the item suites do. */
    private UUID give(String definition, int quantity, int maxStack) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into item_instance (id, campaign_id, actor_id, definition_id, quantity, maximum_stack, "
                + "acquired_revision) values (?, ?, ?, ?, ?, ?, 0)", id, campaign, player, definition, quantity, maxStack);
        return id;
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

    /** The receipt's tactical summary. */
    private static Map<?, ?> combat(CommandExecutionService.Execution execution) {
        return (Map<?, ?>) execution.receipt().get("combat");
    }

    /** The legal options of a receipt. */
    private static Map<?, ?> options(CommandExecutionService.Execution execution) {
        return (Map<?, ?>) combat(execution).get("options");
    }

    /** The draws a receipt lists, in ordinal order. */
    private static List<Map<?, ?>> draws(CommandExecutionService.Execution execution) {
        return ((List<?>) execution.receipt().get("randomDraws")).stream().<Map<?, ?>>map(draw -> (Map<?, ?>) draw).toList();
    }

    private static int value(List<Map<?, ?>> draws, int index) {
        return (Integer) draws.get(index).get("value");
    }

    /** Latest payload of an event type. */
    private JsonNode event(String type) {
        return events(type).getLast();
    }

    /** Every payload of an event type, in order. */
    private List<JsonNode> events(String type) {
        return jdbc.query("select payload::text from campaign_event where campaign_id = ? and event_type = ? order by sequence",
                (row, index) -> JSON.readTree(row.getString(1)), campaign, type);
    }

    private int eventCount(String type) {
        return events(type).size();
    }

    private String visibility(String type) {
        return jdbc.queryForObject("select visibility from campaign_event where campaign_id = ? and event_type = ? "
                + "order by sequence desc limit 1", String.class, campaign, type);
    }

    private List<String> types() {
        return jdbc.queryForList("select event_type from campaign_event where campaign_id = ? order by sequence", String.class, campaign);
    }

    private String participant(String id, String column) {
        return jdbc.queryForObject("select " + column + " from encounter_participant where participant_id = ?", String.class, id);
    }

    private int number(String id, String column) {
        return jdbc.queryForObject("select " + column + " from encounter_participant where participant_id = ?", Integer.class, id);
    }

    private String opening() {
        return jdbc.queryForObject("select opening_status from encounter", String.class);
    }

    private long pool(String key) {
        return jdbc.queryForObject("select current_value from actor_resource where actor_id = ? and resource_key = ?", Long.class, player, key);
    }

    /** Set the player's Health pool directly, as a wound from before this step. */
    private void setPool(long value) {
        jdbc.update("update actor_resource set current_value = ?, row_version = row_version + 1 where actor_id = ? and resource_key = 'vigor'",
                value, player);
    }

    private void setLocalHealth(String participant, int value) {
        jdbc.update("update encounter_participant set health_current = ?, status = ?, row_version = row_version + 1 "
                + "where participant_id = ?", value, value == 0 ? "DEFEATED" : "ACTIVE", participant);
    }

    private int quantity(UUID instance) {
        return jdbc.queryForObject("select quantity from item_instance where id = ?", Integer.class, instance);
    }

    /** Units of ammunition left; a used-up stack has no row. */
    private int arrows() {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from item_instance where actor_id = ? and definition_id = 'arrows'",
                Integer.class, player);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
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
