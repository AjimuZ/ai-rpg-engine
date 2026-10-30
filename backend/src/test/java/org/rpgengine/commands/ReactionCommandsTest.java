package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
import org.rpgengine.combat.CombatEffects;
import org.rpgengine.combat.CombatReaction;
import org.rpgengine.combat.EncounterPack;
import org.rpgengine.combat.EncounterState;
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
 * Real PostgreSQL acceptance of P11.4 on campaigns created from the sealed tactics fixture: policies stored and read back,
 * Brace through a real commit, an ability's points, Focus and cooldown across commits, a learned reaction and a counter
 * with recorded draws, an attack's effect that ticks once per round on the player's own Health, Guard and Brace under one
 * key, each clock and a cooldown running out across commits, a tick felling a holder once, a retry that replays everything,
 * refusals that write nothing and a fight frozen earlier that keeps its behaviour. Campaign dice are unpredictable, so each
 * case fixes the outcome through the fixture or recalculates it from what the receipt lists.
 */
@SpringBootTest
class ReactionCommandsTest {
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
    ReactionCommandsTest() { }

    /** Register the tactics pack and create a campaign at the trailhead through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Reactions", "reactions", SupportedLocale.EN, Instant.now());
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
                + "memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
                + "narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, "
                + "campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, "
                + "ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * A policy is stored at entry and read back; changing it costs nothing, before the first round or at a decision, and an
     * unknown reaction or an out-of-turn change writes nothing. The default of a new fight is Brace at the first eligible
     * attack, the adder's own declaration is frozen with it, and the receipt lists the player's policies.
     */
    @Test
    void policiesAreStoredAndChangedFreely() {
        execute(new TypedCommand.Engage("venom-pit", null));
        assertEquals(CombatReaction.Policy.FIRST, state().player().policy("brace"));
        assertEquals(CombatReaction.Policy.OFF, state().participant("adder").policy("brace"), "declared off");
        assertEquals(CombatReaction.Policy.FIRST, state().participant("adder").policy("snap"));

        execute(new TypedCommand.SetReactionPolicy("brace", "healthAtMost", 50));
        assertEquals(new CombatReaction.Policy(CombatReaction.Policy.Mode.HEALTH_AT_MOST, 50), state().player().policy("brace"));
        assertEquals("ENTERING", jdbc.queryForObject("select phase from encounter", String.class), "before any roll");
        rejects(OperationAdmissionFailure.Kind.REACTION_UNAVAILABLE, new TypedCommand.SetReactionPolicy("parry", "off", 0));
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.EndActivation());

        execute(new TypedCommand.RollInitiative());
        int points = state().actionPoints();
        var changed = execute(new TypedCommand.SetReactionPolicy("brace", "first", 0));
        assertEquals(points, state().actionPoints());
        var options = options(changed);
        assertEquals("brace", ((Map<?, ?>) ((List<?>) options.get("reactions")).getFirst()).get("reactionId"));
        assertEquals("first", ((Map<?, ?>) ((Map<?, ?>) ((List<?>) options.get("reactions")).getFirst()).get("policy")).get("mode"));
        assertEquals("PUBLIC", visibility("REACTION_POLICY_SET"));
    }

    /**
     * The adder's fang always hits a braced player: Brace fires once per round, raises the shown defense by its 2 and the
     * blow takes 3 or 4 Health; the poison it applies ticks once at each round end, stacks and takes Health from the pool the
     * player's Health lives in, every trigger key distinct, and a retry of a committed request replays without new draws.
     */
    @Test
    void braceAndPoisonRunThroughACommit() {
        begin();
        // A survivable fight: the player's pool is raised for this case, as a stronger character's would be.
        jdbc.update("update actor_resource set maximum_value = 99, current_value = 99, row_version = row_version + 1 "
                + "where actor_id = ? and resource_key = 'vigor'", player);
        long health = pool("vigor");
        int strikesBefore = events("STRIKE_RESOLVED").size();
        var request = envelope(new TypedCommand.EndActivation());
        var ended = executions.execute(owner, adventure, campaign, request);
        assertEquals(OperationStatus.COMMITTED, ended.operation().status());
        revision = ended.operation().committedRevision();
        var replay = executions.execute(owner, adventure, campaign, request);
        assertEquals(ended.receipt(), replay.receipt(), "a retry returns the committed receipt");
        assertEquals(strikesBefore + 1, events("STRIKE_RESOLVED").size(), "and resolves nothing again");

        execute(new TypedCommand.EndActivation());
        // The adder may have struck before the player's first decision, while the pool was still the small one.
        var strikes = events("STRIKE_RESOLVED").subList(strikesBefore, events("STRIKE_RESOLVED").size());
        assertEquals(2, strikes.size(), "the adder strikes every round");
        for (JsonNode strike : strikes) {
            assertEquals(14, strike.get("defense").intValue(), "Guard 12 + the player's Brace");
            assertTrue(strike.get("hit").asBoolean(), "2d10 + 6 + 6 always meets 14");
        }
        assertEquals(strikesBefore + 2, events("REACTION_RESOLVED").size(), "one Brace per round's one strike");
        events("REACTION_RESOLVED").forEach(reaction -> assertEquals("player", reaction.get("participantId").asString()));

        var keys = new HashSet<String>();
        long ticks = 0;
        long lost = 0;
        for (JsonNode resolved : events("EFFECTS_RESOLVED")) {
            for (JsonNode line : resolved.get("lines")) {
                if ("TICK".equals(line.get("kind").asString())) {
                    assertTrue(keys.add(line.get("triggerKey").asString()), "one resolution per trigger key: " + line);
                    assertEquals("roundEnd", line.get("phase").asString());
                    ticks++;
                    lost += line.get("damage").longValue();
                }
            }
        }
        long struck = strikes.stream().mapToLong(strike -> strike.get("damage").longValue()).sum();
        assertEquals(health - struck - lost, pool("vigor"), "strikes and ticks reached the player's own pool, and nothing else did");
        assertTrue(ticks >= 1, "the poison ticked");
        var poison = state().player().effects().stream().filter(active -> active.effectId().equals("poisoned")).findFirst();
        assertTrue(poison.isPresent());
        assertTrue(poison.get().stacks() >= 1 && poison.get().stacks() <= 3);
        assertEquals(state().effectsApplied(), jdbc.queryForObject("select effects_applied from encounter", Integer.class));
        assertEquals(1, state().effectsApplied(), "stacks and refreshes create no new instance");
    }

    /**
     * An ability pays its point and Focus through the commit and its cooldown holds across commits; a Focus shortage is the
     * shared resource refusal; a free once-per-encounter ability stays used; every refusal writes nothing.
     */
    @Test
    void abilitiesPayAndCoolDownAcrossCommits() {
        learn("chain-riposte");
        begin();
        long focus = pool("grit");
        int points = state().actionPoints();
        jdbc.update("update actor_resource set current_value = ?, row_version = row_version + 1 where actor_id = ? and resource_key = 'vigor'",
                3, player);
        var used = execute(new TypedCommand.CombatUseAbility("steady", List.of()));
        assertEquals(points - 1, state().actionPoints());
        assertEquals(focus - 1, pool("grit"));
        assertEquals(5, pool("vigor"), "heals 2");
        assertEquals("COOLDOWN", abilityOption(used, "steady").get("disabled"));
        rejects(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, new TypedCommand.CombatUseAbility("steady", List.of()));
        rejects(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, new TypedCommand.CombatUseAbility("riposte", List.of()));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.CombatUseAbility("chain-riposte", List.of("adder")));

        var ward = execute(new TypedCommand.CombatUseAbility("chain-riposte", List.of()));
        assertEquals(points - 1, state().actionPoints(), "free");
        assertEquals("LIMIT", abilityOption(ward, "chain-riposte").get("disabled"));
        assertTrue(state().player().effects().stream().map(CombatEffects.Active::effectId).toList().contains("warded"));
        var standing = ((List<?>) ((Map<?, ?>) ward.receipt().get("combat")).get("participants")).getFirst();
        assertTrue(((List<?>) ((Map<?, ?>) standing).get("effects")).stream().anyMatch(effect ->
                "warded".equals(((Map<?, ?>) effect).get("effectId"))), "the receipt shows which effects a participant holds");
        rejects(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, new TypedCommand.CombatUseAbility("chain-riposte", List.of()));
    }

    /** Without the Focus it costs, an ability is the shared resource refusal, and nothing is written. */
    @Test
    void aFocusShortageIsRefused() {
        begin();
        jdbc.update("update actor_resource set current_value = 0, row_version = row_version + 1 where actor_id = ? and resource_key = 'grit'", player);
        rejects(OperationAdmissionFailure.Kind.RESOURCE_INSUFFICIENT, new TypedCommand.CombatUseAbility("steady", List.of()));
    }

    /**
     * A learned reaction freezes at entry under its ability's ID and its counter runs inside the provoking action with recorded
     * draws; the adder's own counter answers a player who leaves the pit, and neither provokes another reaction.
     */
    @Test
    void countersRunInsideTheProvokingAction() {
        learn("riposte");
        begin();
        // Keep the fight on the player's decision long enough: nobody falls in this case.
        jdbc.update("update encounter_participant set reaction_policies = reaction_policies || '{\"brace\":{\"mode\":\"off\",\"value\":0}}', "
                + "row_version = row_version + 1 where participant_id = 'player'");
        assertEquals(CombatReaction.Policy.FIRST, state().player().policy("riposte"));
        jdbc.update("update actor_resource set maximum_value = 99, current_value = 99, row_version = row_version + 1 "
                + "where actor_id = ? and resource_key = 'vigor'", player);

        var moved = execute(new TypedCommand.MoveZone("ledge"));
        var snap = events("REACTION_RESOLVED").getLast();
        assertEquals("adder", snap.get("participantId").asString());
        assertEquals("snap", snap.get("reactionId").asString());
        assertEquals("leavesZone", snap.get("trigger").asString());
        assertEquals("ledge", state().player().zone(), "a player who survives the blow arrives");
        assertFalse(draws(moved).isEmpty(), "the counter's dice are recorded by the move");
        JsonNode counter = events("STRIKE_RESOLVED").getLast();
        assertTrue(counter.get("reaction").asBoolean());
        assertEquals(12, counter.get("defense").intValue(), "no Brace, and none could answer a reaction");
    }

    /** A fight frozen before this release has no stored policies: nobody reacts until the player declares one. */
    @Test
    void anEarlierFightKeepsItsBehaviour() {
        begin();
        jdbc.update("update encounter_participant set reaction_policies = '{}', row_version = row_version + 1");
        jdbc.update("update actor_resource set maximum_value = 99, current_value = 99, row_version = row_version + 1 "
                + "where actor_id = ? and resource_key = 'vigor'", player);
        assertEquals(CombatReaction.Policy.OFF, state().player().policy("brace"));
        int before = events("REACTION_RESOLVED").size();
        execute(new TypedCommand.EndActivation());
        assertEquals(before, events("REACTION_RESOLVED").size());
        assertEquals(12, events("STRIKE_RESOLVED").getLast().get("defense").intValue());
        execute(new TypedCommand.SetReactionPolicy("brace", "first", 0));
        execute(new TypedCommand.EndActivation());
        assertEquals(14, events("STRIKE_RESOLVED").getLast().get("defense").intValue());
    }

    /** The database repeats what it can see: reaction budget, local Focus, and effects that end with their holder. */
    @Test
    void theDatabaseRepeatsTheBounds() {
        begin();
        String reactions = "update encounter_participant set reactions = ?, row_version = row_version + 1 where participant_id = 'adder'";
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(reactions, 2), "above the frozen allowance");
        assertEquals(1, jdbc.update(reactions, 0));
        String focus = "update encounter_participant set focus_current = ?, row_version = row_version + 1 where participant_id = 'adder'";
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(focus, 3), "above the frozen maximum of 2");
        assertEquals(1, jdbc.update(focus, 1));
        assertEquals(1, state().participant("adder").focus(), "read back from its row");
        assertEquals(6, state().player().focusMax(), "and the player's from its pool");
    }

    /** The stored fight's hidden values stay out of its public record. */
    @Test
    void publicEffectsKeepOtherParticipantsHidden() {
        begin();
        jdbc.update("update actor_resource set maximum_value = 99, current_value = 99, row_version = row_version + 1 "
                + "where actor_id = ? and resource_key = 'vigor'", player);
        execute(new TypedCommand.Strike("sword", "adder"));
        JsonNode public1 = events("STRIKE_RESOLVED").stream().filter(strike -> strike.get("participantId").asString().equals("player"))
                .findFirst().orElseThrow();
        for (String hidden : List.of("defense", "targetHealthAfter", "armor")) {
            assertFalse(public1.has(hidden), hidden);
        }
        assertEquals("INTERNAL", jdbc.queryForObject("select visibility from campaign_event where campaign_id = ? and event_type = "
                + "'STRIKE_CALCULATED' order by sequence desc limit 1", String.class, campaign));
    }

    /**
     * Guard and Brace share one stacking key through a commit: the stance alone meets the next blow and the Brace is skipped
     * with its reason at no cost; once the stance has ended Brace fires again, and the defense is the same both times.
     */
    @Test
    void guardAndBraceShareOneKeyThroughACommit() {
        begin();
        jdbc.update("update actor_resource set maximum_value = 99, current_value = 99, row_version = row_version + 1 "
                + "where actor_id = ? and resource_key = 'vigor'", player);
        int fired = events("REACTION_RESOLVED").size();
        execute(new TypedCommand.Guard());
        execute(new TypedCommand.EndActivation());
        assertEquals(14, events("STRIKE_RESOLVED").getLast().get("defense").intValue(), "Guard 12 + the stance, never + Brace");
        assertEquals(fired, events("REACTION_RESOLVED").size(), "a redundant Brace spends nothing");
        assertEquals("REDUNDANT", lines("player", "REACTION_SKIPPED").getLast().get("reason").asString());

        execute(new TypedCommand.EndActivation());
        assertEquals(14, events("STRIKE_RESOLVED").getLast().get("defense").intValue(), "Brace alone gives the same bonus");
        assertEquals(fired + 1, events("REACTION_RESOLVED").size());
    }

    /**
     * Each clock of an entry effect runs through real commits and counts its own unit once: an effect lasting until the next
     * activation and one lasting one activation end in the holder's first activation; two round ends mean exactly two ticks
     * and one end. A holder felled by its first tick is defeated once and ticks no more. A retry of the last commit changes nothing.
     */
    @Test
    void effectClocksCountOnceThroughCommits() {
        begin("clock-pit");
        execute(new TypedCommand.EndActivation());
        var held = state().participant("idol").effects();
        assertEquals(List.of("poisoned"), held.stream().map(CombatEffects.Active::effectId).toList(), "one round end has passed");
        assertEquals(1, held.getFirst().remaining());
        assertEquals(1, lines("idol", "TICK").size());
        assertEquals(EncounterState.Status.DEFEATED, state().participant("reed").status());
        assertEquals(0, state().participant("reed").health());

        execute(new TypedCommand.EndActivation());
        var request = envelope(new TypedCommand.EndActivation());
        var last = executions.execute(owner, adventure, campaign, request);
        revision = last.operation().committedRevision();
        int recorded = events("EFFECTS_RESOLVED").size();
        assertEquals(last.receipt(), executions.execute(owner, adventure, campaign, request).receipt());
        assertEquals(recorded, events("EFFECTS_RESOLVED").size(), "a retry resolves nothing again");

        var ticks = lines("idol", "TICK");
        assertEquals(2, ticks.size(), "three round ends passed, and the poison lasts two");
        assertEquals(2, ticks.stream().map(line -> line.get("triggerKey").asString()).distinct().count());
        assertEquals(999 - 2, state().participant("idol").health());
        assertEquals(Map.of("winded", 1L, "stunned", 1L, "poisoned", 1L), lines("idol", "ENDED").stream().collect(
                java.util.stream.Collectors.groupingBy(line -> line.get("effectId").asString(), java.util.stream.Collectors.counting())));
        lines("idol", "ENDED").forEach(line -> assertEquals("EXPIRED", line.get("reason").asString()));
        assertEquals("[]", storedEffects("idol"));

        assertEquals(1, lines("reed", "TICK").size(), "felled by the first");
        assertEquals(List.of("HOLDER_DEFEATED"), lines("reed", "ENDED").stream().map(line -> line.get("reason").asString()).toList());
        assertEquals(1, events("PARTICIPANT_DEFEATED").size());
        assertEquals("[]", storedEffects("reed"));
    }

    /**
     * An effect applied during its holder's own activation keeps that fact across commits: the activation in progress does not
     * count, and the next one is felt whole, with no usable points and a prevented action, before it ends once.
     */
    @Test
    void aFreshEffectWaitsForAWholeActivationAcrossCommits() {
        begin("clock-pit");
        int points = state().actionPoints();
        hold("stunned", 1);
        assertTrue(state().player().effects().getFirst().fresh(), "read back from its row");
        execute(new TypedCommand.EndActivation());
        var held = state().player().effects().getFirst();
        assertEquals(1, held.remaining(), "the activation in progress did not count");
        assertFalse(held.fresh());
        assertEquals(0, state().actionPoints(), "prevented from its start");
        rejects(OperationAdmissionFailure.Kind.ACTION_PREVENTED, new TypedCommand.Guard());
        execute(new TypedCommand.EndActivation());
        assertEquals("[]", storedEffects("player"));
        assertEquals(1, lines("player", "ENDED").size());
        assertEquals(points, state().actionPoints(), "the next activation is free");
    }

    /** A tick that takes the player's last Health reaches its pool, defeats once and ends the holder's effects, on a retry too. */
    @Test
    void aTickFellsThePlayerOnceThroughACommit() {
        begin("clock-pit");
        jdbc.update("update actor_resource set current_value = 1, row_version = row_version + 1 where actor_id = ? and resource_key = 'vigor'", player);
        hold("poisoned", 2);
        var request = envelope(new TypedCommand.EndActivation());
        var ended = executions.execute(owner, adventure, campaign, request);
        assertEquals(ended.receipt(), executions.execute(owner, adventure, campaign, request).receipt());
        assertEquals(0, pool("vigor"));
        assertEquals(EncounterState.Status.DEFEATED, state().player().status());
        assertEquals(1, lines("player", "TICK").size());
        assertEquals(List.of("HOLDER_DEFEATED"), lines("player", "ENDED").stream().map(line -> line.get("reason").asString()).toList());
        assertEquals(1, events("PARTICIPANT_DEFEATED").stream().filter(event -> "player".equals(event.get("participantId").asString())).count());
        assertEquals("[]", storedEffects("player"));
    }

    /**
     * A cooldown of two rounds blocks the next round and ends on the second, across commits; the second use pays again and the
     * stored use count and round follow it.
     */
    @Test
    void aCooldownEndsAfterItsRounds() {
        begin("clock-pit");
        int round = state().round();
        long focus = pool("grit");
        jdbc.update("update actor_resource set current_value = 3, row_version = row_version + 1 where actor_id = ? and resource_key = 'vigor'", player);
        execute(new TypedCommand.CombatUseAbility("steady", List.of()));
        assertEquals(5, pool("vigor"));

        var next = execute(new TypedCommand.EndActivation());
        assertEquals(round + 1, state().round());
        assertEquals("COOLDOWN", abilityOption(next, "steady").get("disabled"));
        rejects(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, new TypedCommand.CombatUseAbility("steady", List.of()));

        var cooled = execute(new TypedCommand.EndActivation());
        assertEquals(round + 2, state().round());
        assertNull(abilityOption(cooled, "steady").get("disabled"), "a usable ability has no reason");
        execute(new TypedCommand.CombatUseAbility("steady", List.of()));
        assertEquals(7, pool("vigor"));
        assertEquals(focus - 2, pool("grit"));
        assertEquals(new org.rpgengine.combat.CombatAbility.Use(round + 2, 2), state().player().uses().get("steady"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Enter the duel and roll initiative. */
    private void begin() {
        begin("venom-pit");
    }

    /** Enter a template and roll initiative. */
    private void begin(String template) {
        execute(new TypedCommand.Engage(template, null));
        execute(new TypedCommand.RollInitiative());
        assertTrue(state().playerDecision());
    }

    /** Store one frozen effect on the player as applied during its activation in progress, the way the writer would. */
    private void hold(String effect, int remaining) {
        jdbc.update("update encounter_participant set effects = ?::jsonb, row_version = row_version + 1 where participant_id = 'player'",
                "[{\"instanceId\":\"e90\",\"effectId\":\"" + effect + "\",\"sourceId\":null,\"sequence\":90,\"remaining\":" + remaining
                        + ",\"stacks\":1,\"absorption\":0,\"untilId\":null,\"fresh\":true}]");
    }

    /** One participant's stored effects, as text. */
    private String storedEffects(String participant) {
        return jdbc.queryForObject("select effects::text from encounter_participant where participant_id = ?", String.class, participant);
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

    /** Give the player one more learned ability, as advancement would. */
    private void learn(String ability) {
        jdbc.update("insert into learned_ability (campaign_id, actor_id, definition_id, rank, acquired_revision) values (?, ?, ?, 1, 0)",
                campaign, player, ability);
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

    /** Submit a command that must be refused at admission with the given category and change nothing. */
    private void rejects(OperationAdmissionFailure.Kind kind, TypedCommand command) {
        var before = world();
        var failure = assertThrows(OperationAdmissionFailure.class, () -> executions.execute(owner, adventure, campaign,
                envelope(command)), command.toString());
        assertEquals(kind, failure.kind(), command.toString());
        assertEquals(before, world(), "a refusal writes nothing: " + command);
    }

    /** The legal options of a receipt. */
    private static Map<?, ?> options(CommandExecutionService.Execution execution) {
        return (Map<?, ?>) ((Map<?, ?>) execution.receipt().get("combat")).get("options");
    }

    /** One ability's line in a receipt's options. */
    private static Map<?, ?> abilityOption(CommandExecutionService.Execution execution, String id) {
        return ((List<?>) options(execution).get("abilities")).stream().map(line -> (Map<?, ?>) line)
                .filter(line -> id.equals(line.get("abilityId"))).findFirst().orElseThrow();
    }

    /** The draws a receipt lists, in ordinal order. */
    private static List<Map<?, ?>> draws(CommandExecutionService.Execution execution) {
        return ((List<?>) execution.receipt().get("randomDraws")).stream().<Map<?, ?>>map(draw -> (Map<?, ?>) draw).toList();
    }

    /** Every payload of an event type, in order. */
    private List<JsonNode> events(String type) {
        return new ArrayList<>(jdbc.query("select payload::text from campaign_event where campaign_id = ? and event_type = ? order by sequence",
                (row, index) -> JSON.readTree(row.getString(1)), campaign, type));
    }

    private String visibility(String type) {
        return jdbc.queryForObject("select visibility from campaign_event where campaign_id = ? and event_type = ? "
                + "order by sequence desc limit 1", String.class, campaign, type);
    }

    private long pool(String key) {
        return jdbc.queryForObject("select current_value from actor_resource where actor_id = ? and resource_key = ?", Long.class, player, key);
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
