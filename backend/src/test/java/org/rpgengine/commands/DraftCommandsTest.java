package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.rpgengine.combat.CombatAbility;
import org.rpgengine.combat.CombatAbility.Kind;
import org.rpgengine.combat.CombatAbility.Targets;
import org.rpgengine.combat.CombatEffects;
import org.rpgengine.combat.EncounterPack;
import org.rpgengine.combat.EncounterState;
import org.rpgengine.combat.EncounterWriter;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.creative.AbilityDraft;
import org.rpgengine.creative.AbilityDraft.Step;
import org.rpgengine.creative.CreativePack;
import org.rpgengine.creative.DraftPreview;
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
 * Real PostgreSQL acceptance of P15.4-B on campaigns created from {@link CreativePack}: a draft is executed only by the explicit
 * {@code COMBAT_USE_DRAFT} command, which previews it again under the campaign lock at admission and at commit and resolves the
 * candidate through the combat scheduler. Its derived costs are paid, its cooldown holds across drafts, every limit is the
 * documented refusal and writes nothing, a lost response replays, using a draft learns nothing, and a pack without a creative
 * profile offers no draft. Learning is the separate exploration command {@code LEARN_DRAFT}: it stores immutable versions with
 * their provenance, and the next entry freezes the latest version that still fits as a {@code #recipe:<id>} ability. The fights
 * are the tactics pack's {@code venom-pit}, whose adder stands in the player's zone.
 */
@SpringBootTest
class DraftCommandsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** A light hit that winds one hostile beside the user: complexity (1 + 1 die) + (2 + 2) = 6, so 6 Focus and 6 rounds. */
    private static final AbilityDraft GUST = new AbilityDraft("Gust", Targets.HOSTILE, 1, 0, List.of(
            new Step(Kind.DAMAGE, 1, 4, null, 0, 0, null, false), new Step(Kind.APPLY, 0, 0, null, 0, 0, "winded", false)));
    /** A bare hit: complexity 2. */
    private static final AbilityDraft JAB = new AbilityDraft("Jab", Targets.HOSTILE, 1, 0, List.of(
            new Step(Kind.DAMAGE, 1, 4, null, 0, 0, null, false)));
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
    DraftCommandsTest() { }

    /** Register the creative pack and create a campaign at the trailhead through production code. */
    @BeforeEach
    void prepare() throws Exception {
        prepare(CreativePack.files());
    }

    /** Remove disposable rows, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, "
                + "equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, "
                + "memory_summary_job, relationship_change, faction_clock_change, learned_recipe,actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
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
     * The draft pays exactly the derived two points, six Focus and six-round cooldown the receipt lists, takes the adder's
     * Health from one recorded die and winds it, both effects of the profile being frozen at entry; its use is recorded under
     * the reserved ID, and no learned ability, pinned ability or other draft can follow it until its cooldown ends.
     */
    @Test
    void anExplicitCommandExecutesThePreviewedDraft() {
        learn("chain-riposte");
        survive();
        begin();
        assertTrue(state().effects().containsKey("winded"), "entry froze the profile's winded, which no participant names");
        long focus = pool("grit");
        int points = state().actionPoints();
        int adder = state().participant("adder").health();
        long learned = learnedCount();

        var used = execute(new TypedCommand.CombatUseDraft(GUST, List.of("adder")));
        var charged = (Map<?, ?>) used.receipt().get("draft");
        assertEquals(DraftPreview.CANDIDATE_ID, charged.get("abilityId"));
        assertEquals(2, ((Number) charged.get("actionPoints")).intValue());
        assertEquals(6, ((Number) charged.get("focus")).intValue());
        assertEquals(6, ((Number) charged.get("cooldownRounds")).intValue());
        assertEquals(points - 2, state().actionPoints());
        assertEquals(focus - 6, pool("grit"));
        int lost = adder - state().participant("adder").health();
        assertTrue(lost >= 1 && lost <= 4, "one d4 and no armor: " + lost);
        assertTrue(state().participant("adder").effects().stream().map(CombatEffects.Active::effectId).toList().contains("winded"));
        assertEquals(new CombatAbility.Use(state().round(), 1), state().player().uses().get(DraftPreview.CANDIDATE_ID));
        var announced = events("COMBAT_ABILITY_USED").getLast();
        assertEquals(DraftPreview.CANDIDATE_ID, announced.get("abilityId").asString());
        assertEquals(6, announced.get("focus").intValue());
        assertEquals("PUBLIC", visibility("COMBAT_ABILITY_USED"));
        assertEquals(learned, learnedCount(), "using a draft learns nothing");

        rejects(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, new TypedCommand.CombatUseDraft(GUST, List.of("adder")));
        rejects(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, new TypedCommand.CombatUseDraft(JAB, List.of("adder")));
        rejects(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE,
                new TypedCommand.CombatUseAbility(DraftPreview.CANDIDATE_ID, List.of("adder")));
    }

    /**
     * Every limit is refused at admission with its category and writes nothing: no draft outside a fight or before initiative,
     * a missing prerequisite, each bound of the profile (steps, a persistent effect, an effect it does not permit, complexity),
     * a target the candidate cannot take, and too little Focus.
     */
    @Test
    void limitsAreRefusedAndWriteNothing() {
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.CombatUseDraft(GUST, List.of("adder")));
        survive();
        execute(new TypedCommand.Engage("venom-pit", null));
        rejects(OperationAdmissionFailure.Kind.PREREQUISITE_UNMET, new TypedCommand.CombatUseDraft(GUST, List.of("adder")));
        learn("chain-riposte");
        rejects(OperationAdmissionFailure.Kind.TURN_UNAVAILABLE, new TypedCommand.CombatUseDraft(GUST, List.of("adder")));
        execute(new TypedCommand.RollInitiative());
        assertTrue(state().playerDecision());

        Step hit = new Step(Kind.DAMAGE, 1, 4, null, 0, 0, null, false);
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED, draft(Targets.HOSTILE, 1, hit, hit, hit));
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED,
                draft(Targets.HOSTILE, 1, new Step(Kind.APPLY, 0, 0, null, 0, 0, "warded", false)));
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED,
                draft(Targets.HOSTILE, 1, new Step(Kind.APPLY, 0, 0, null, 0, 0, "poisoned", false)));
        // (1 + 2 dice + 2 flat) times 2 targets = 10, above the budget of 8.
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED,
                draft(Targets.HOSTILE, 2, new Step(Kind.DAMAGE, 2, 4, null, 2, 0, null, false)));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.CombatUseDraft(GUST, List.of()));
        rejects(OperationAdmissionFailure.Kind.TARGET_ILLEGAL, new TypedCommand.CombatUseDraft(GUST, List.of("player")));
        jdbc.update("update actor_resource set current_value = 5, row_version = row_version + 1 where actor_id = ? "
                + "and resource_key = 'grit'", player);
        rejects(OperationAdmissionFailure.Kind.RESOURCE_INSUFFICIENT, new TypedCommand.CombatUseDraft(GUST, List.of("adder")));
        execute(new TypedCommand.CombatUseDraft(JAB, List.of("adder")));
        assertEquals(3, pool("grit"), "the cheaper draft fits what is left");
    }

    /** A lost response is retried with the same request and replays the receipt, drawing and charging nothing again. */
    @Test
    void aRetryReplaysTheDraft() {
        learn("chain-riposte");
        survive();
        begin();
        var request = envelope(new TypedCommand.CombatUseDraft(GUST, List.of("adder")));
        var first = executions.execute(owner, adventure, campaign, request);
        assertEquals(OperationStatus.COMMITTED, first.operation().status());
        revision = first.operation().committedRevision();
        var before = world();
        var replay = executions.execute(owner, adventure, campaign, request);
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(before, world(), "the replay writes nothing");
    }

    /** A fight of a pack that declares no creative profile offers no draft at all, and nothing can be learned. */
    @Test
    void aPackWithoutAProfileHasNoDrafts() throws Exception {
        clear();
        prepare(EncounterPack.tactics());
        learn("chain-riposte");
        rejects(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, new TypedCommand.LearnDraft("jab", JAB));
        survive();
        begin();
        rejects(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, new TypedCommand.CombatUseDraft(JAB, List.of("adder")));
        assertTrue(!state().effects().containsKey("winded"), "and its fights freeze nothing for drafts");
    }

    /**
     * Learning is the explicit exploration command: it previews the draft again, spends nothing, and stores version 1 with the
     * derived costs, the profile, the pinned content and its announcing event as provenance. Learning the same recipe ID again
     * adds version 2. The next fight's entry freezes the latest version as an ordinary frozen ability, which
     * {@code COMBAT_USE_ABILITY} uses at exactly those costs. Stored versions cannot be changed.
     */
    @Test
    void aLearnedRecipeIsVersionedWithProvenanceAndFrozenAtEntry() {
        learn("chain-riposte");
        long focus = pool("grit");
        var learned = execute(new TypedCommand.LearnDraft("gust", GUST));
        var receipt = (Map<?, ?>) learned.receipt().get("recipe");
        assertEquals("gust", receipt.get("recipeId"));
        assertEquals(1, ((Number) receipt.get("version")).intValue());
        assertEquals("#recipe:gust", receipt.get("abilityId"));
        assertEquals(6, ((Number) receipt.get("complexity")).intValue());
        assertEquals(2, ((Number) receipt.get("actionPoints")).intValue());
        assertEquals(6, ((Number) receipt.get("focus")).intValue());
        assertEquals(6, ((Number) receipt.get("cooldownRounds")).intValue());
        assertEquals(focus, pool("grit"), "learning spends nothing");

        var row = jdbc.queryForMap("select r.version, r.label, r.draft::text as draft, r.profile_pack_id, r.profile_id, "
                + "r.content_revision_id = c.content_revision_id as pinned, r.learned_revision, r.complexity, r.action_points, "
                + "r.focus, r.cooldown_rounds, e.event_type, e.state_revision = r.learned_revision as cited "
                + "from learned_recipe r join campaign c on c.id = r.campaign_id join campaign_event e "
                + "on e.campaign_id = r.campaign_id and e.sequence = r.learned_event_sequence "
                + "where r.campaign_id = ? and r.actor_id = ? and r.recipe_id = 'gust'", campaign, player);
        assertEquals(1, ((Number) row.get("version")).intValue());
        assertEquals("Gust", row.get("label"));
        assertEquals(GUST, LearnedRecipes.read((String) row.get("draft")), "the draft is stored as written");
        assertEquals(CreativePack.PACK, row.get("profile_pack_id"));
        assertEquals("rules.creative", row.get("profile_id"));
        assertEquals(Boolean.TRUE, row.get("pinned"));
        assertEquals(revision, ((Number) row.get("learned_revision")).longValue());
        assertEquals(6, ((Number) row.get("focus")).intValue());
        assertEquals("RECIPE_LEARNED", row.get("event_type"));
        assertEquals(Boolean.TRUE, row.get("cited"));
        var announced = events("RECIPE_LEARNED").getFirst();
        assertEquals(1, announced.get("version").intValue());
        assertTrue(!announced.has("label"), "the player's wording stays out of the event");
        assertEquals("PUBLIC", visibility("RECIPE_LEARNED"));

        var again = execute(new TypedCommand.LearnDraft("gust", JAB));
        assertEquals(2, ((Number) ((Map<?, ?>) again.receipt().get("recipe")).get("version")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) again.receipt().get("recipe")).get("focus")).intValue());
        assertEquals(List.of(1, 2), jdbc.queryForList("select version from learned_recipe where campaign_id = ? "
                + "and recipe_id = 'gust' order by version", Integer.class, campaign));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "update learned_recipe set label = 'Other' where campaign_id = ?", campaign));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "delete from learned_recipe where campaign_id = ?", campaign));

        survive();
        begin();
        var frozen = state().player().sheet().ability("#recipe:gust");
        assertEquals(new CombatAbility("#recipe:gust", 2, CombatAbility.Limit.NONE, 2, 2, Targets.HOSTILE, 1, 0,
                List.of(new CombatAbility.Operation(Kind.DAMAGE, 1, 4, null, 0, 0, null, false))), frozen,
                "entry froze the latest version with its derived costs");
        long grit = pool("grit");
        int points = state().actionPoints();
        int adder = state().participant("adder").health();
        execute(new TypedCommand.CombatUseAbility("#recipe:gust", List.of("adder")));
        assertEquals(grit - 2, pool("grit"));
        assertEquals(points - 2, state().actionPoints());
        int lost = adder - state().participant("adder").health();
        assertTrue(lost >= 1 && lost <= 4, "one d4 and no armor: " + lost);
        assertEquals(new CombatAbility.Use(state().round(), 1), state().player().uses().get("#recipe:gust"));
        rejects(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE,
                new TypedCommand.CombatUseAbility("#recipe:gust", List.of("adder")));
    }

    /**
     * Learning refuses, with its category and without writing anything: a missing profile prerequisite, a draft outside the
     * profile, a ninth recipe ID (a new version of a held one still fits) and any learning during a fight. An entry previews
     * every recipe again and freezes none whose prerequisite is no longer learned.
     */
    @Test
    void learningLimitsAreRefusedAndWriteNothing() {
        rejects(OperationAdmissionFailure.Kind.PREREQUISITE_UNMET, new TypedCommand.LearnDraft("gust", GUST));
        learn("chain-riposte");
        Step hit = new Step(Kind.DAMAGE, 1, 4, null, 0, 0, null, false);
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED, new TypedCommand.LearnDraft("triple",
                new AbilityDraft("Triple", Targets.HOSTILE, 1, 0, List.of(hit, hit, hit))));
        rejects(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED, new TypedCommand.LearnDraft("ward",
                new AbilityDraft("Ward", Targets.HOSTILE, 1, 0, List.of(new Step(Kind.APPLY, 0, 0, null, 0, 0, "warded", false)))));
        for (int index = 1; index <= LearnedRecipes.MAX_RECIPES; index++) {
            execute(new TypedCommand.LearnDraft("jab-" + index, JAB));
        }
        rejects(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, new TypedCommand.LearnDraft("jab-9", JAB));
        execute(new TypedCommand.LearnDraft("jab-1", GUST));
        assertEquals(LearnedRecipes.MAX_RECIPES + 1, jdbc.queryForObject("select count(*) from learned_recipe "
                + "where campaign_id = ?", Integer.class, campaign));

        jdbc.update("delete from learned_ability where campaign_id = ? and definition_id = 'chain-riposte'", campaign);
        survive();
        execute(new TypedCommand.Engage("venom-pit", null));
        assertTrue(state().player().sheet().abilities().stream().noneMatch(ability -> ability.id().startsWith("#recipe:")),
                "without the profile's prerequisite no recipe is frozen");
        rejects(OperationAdmissionFailure.Kind.MODE_ILLEGAL, new TypedCommand.LearnDraft("gust", GUST));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Register a pack and create a campaign at the trailhead through production code. */
    private void prepare(Map<String, byte[]> files) throws Exception {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Drafts", "drafts", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(files)))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        revision = 0;
    }

    /** A draft of these steps on hostiles at range 0. */
    private static TypedCommand.CombatUseDraft draft(Targets targets, int maxTargets, Step... steps) {
        return new TypedCommand.CombatUseDraft(new AbilityDraft("Trial", targets, maxTargets, 0, List.of(steps)),
                List.of("adder"));
    }

    /** Raise the player's Health so the adder's strikes cannot end the fight during a case, and fill the Focus pool. */
    private void survive() {
        jdbc.update("update actor_resource set maximum_value = 99, current_value = 99, row_version = row_version + 1 "
                + "where actor_id = ? and resource_key = 'vigor'", player);
        jdbc.update("update actor_resource set current_value = maximum_value, row_version = row_version + 1 "
                + "where actor_id = ? and resource_key = 'grit'", player);
    }

    /** Enter the duel and roll initiative. */
    private void begin() {
        execute(new TypedCommand.Engage("venom-pit", null));
        execute(new TypedCommand.RollInitiative());
        assertTrue(state().playerDecision());
    }

    /** Give the player one more learned ability, as advancement would. */
    private void learn(String ability) {
        jdbc.update("insert into learned_ability (campaign_id, actor_id, definition_id, rank, acquired_revision) values (?, ?, ?, 1, 0)",
                campaign, player, ability);
    }

    private long learnedCount() {
        return jdbc.queryForObject("select count(*) from learned_ability where campaign_id = ?", Long.class, campaign);
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
                "operation_random_draw", "canonical_outbox", "encounter", "encounter_participant", "item_instance",
                "learned_ability", "learned_recipe")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }
}
