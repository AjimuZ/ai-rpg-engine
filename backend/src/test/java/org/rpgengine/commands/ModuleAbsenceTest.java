package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CampaignProjectionService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.combat.CombatAbility.Kind;
import org.rpgengine.combat.CombatAbility.Targets;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.creative.AbilityDraft;
import org.rpgengine.creative.AbilityDraft.Step;
import org.rpgengine.creative.CreativePack;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.memory.ResumeCards;
import org.rpgengine.panels.CombatPanels;
import org.rpgengine.panels.PlayPanels;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of P15.5-A for the optional modules: one pack, {@link CreativePack}, is registered twice, once
 * with the {@code creativeAbilities} module and its profile and once without either, and both campaigns play the same
 * commands up to the same fight. Every read the campaign routes return (state, resume card, panels, combat panel, stream)
 * and every receipt is serialized as JSON. With the module disabled none of them carries a draft, a recipe, a relationship
 * or a companion, no module row is stored, and learning is refused. With it enabled the learned recipe appears only as an
 * ordinary ability of the fight's legal options, which shows the comparison is not vacuous.
 *
 * <p>The reads are the services the routes serialize; the HTTP layer itself is not exercised here.
 */
@SpringBootTest
class ModuleAbsenceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Complexity (1 + 1 die) + (2 + 2) = 6 under the default profile: 2 points, 6 Focus, 6 rounds. */
    private static final AbilityDraft GUST = new AbilityDraft("Gust", Targets.HOSTILE, 1, 0, List.of(
            new Step(Kind.DAMAGE, 1, 4, null, 0, 0, null, false), new Step(Kind.APPLY, 0, 0, null, 0, 0, "winded", false)));
    /**
     * Text that only a module's data can put into a read: the reserved ability prefixes and the start of a JSON string a
     * module's member or value would begin with (prose such as an item description is not matched).
     */
    private static final List<String> MODULE_MARKERS = List.of("#draft", "#recipe", "\"draft\"", "\"recipe\"",
            "\"relationship", "\"companion");
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private CampaignProjectionService projections;
    @Autowired private ResumeCards resumeCards;
    @Autowired private PlayPanels playPanels;
    @Autowired private CombatPanels combatPanels;
    @Autowired private PublicStreamService stream;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    ModuleAbsenceTest() { }

    /** Start from an empty database with one owner. */
    @BeforeEach
    void prepare() {
        clear();
        owner = new TransactionTemplate(manager).execute(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            return profile.getId();
        });
    }

    /** Remove disposable rows, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, "
                + "equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, "
                + "memory_summary_job, relationship_change, faction_clock_change, learned_recipe, actor_commitment, actor_sighting, "
                + "knowledge_belief, knowledge_claim, inference_task, "
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
     * The disabled campaign is refused the learning, and after the same fight entry none of its reads or receipts names a
     * module concept, while the enabled campaign's combat options list the learned recipe at its derived costs.
     */
    @Test
    void aDisabledModuleLeavesNoTraceInAnyRead() throws Exception {
        var disabled = campaign(CreativePack.files(false));
        var enabled = campaign(CreativePack.files());

        var refused = assertThrows(OperationAdmissionFailure.class, () -> disabled.send(new TypedCommand.LearnDraft("gust", GUST)));
        assertEquals(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE, refused.kind());
        var learned = enabled.send(new TypedCommand.LearnDraft("gust", GUST));
        assertEquals("#recipe:gust", ((Map<?, ?>) learned.receipt().get("recipe")).get("abilityId"));

        for (Scope scope : List.of(disabled, enabled)) {
            scope.survive();
            scope.send(new TypedCommand.Engage("venom-pit", null));
            scope.send(new TypedCommand.RollInitiative());
        }

        String off = disabled.reads();
        for (String marker : MODULE_MARKERS) {
            assertFalse(off.contains(marker), "a disabled module leaves no '" + marker + "' in any read or receipt");
        }
        for (String table : List.of("learned_recipe", "relationship_change")) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table + " where campaign_id = ?", Integer.class,
                    disabled.campaign), table);
        }

        assertTrue(enabled.reads().contains("#recipe:gust"), "the same reads carry the module's data when it is enabled");
        JsonNode abilities = JSON.readTree(JSON.writeValueAsString(combatPanels.read(owner, enabled.adventure,
                enabled.campaign))).path("encounter").path("options").path("abilities");
        JsonNode recipe = null;
        for (JsonNode ability : abilities) {
            if ("#recipe:gust".equals(ability.path("abilityId").asString())) {
                recipe = ability;
            }
        }
        assertTrue(recipe != null, "the learned recipe is one of the fight's legal abilities: " + abilities);
        assertEquals(List.of(2, 6), List.of(recipe.path("cost").asInt(), recipe.path("focus").asInt()));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Register a pack into a new workspace and create a campaign whose player has the profile's prerequisite. */
    private Scope campaign(Map<String, byte[]> files) throws Exception {
        UUID adventure = new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(owner, "Modules", "modules", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(files)))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        jdbc.update("insert into learned_ability (campaign_id, actor_id, definition_id, rank, acquired_revision) "
                + "values (?, ?, 'chain-riposte', 1, 0)", created.campaignId(), created.actorId());
        return new Scope(adventure, created.campaignId(), created.actorId());
    }

    /** One campaign with its own revision and every receipt it was given. */
    private final class Scope {
        final UUID adventure;
        final UUID campaign;
        final UUID player;
        final List<Map<String, Object>> receipts = new ArrayList<>();
        long revision;

        Scope(UUID adventure, UUID campaign, UUID player) {
            this.adventure = adventure;
            this.campaign = campaign;
            this.player = player;
        }

        /** Submit a command that must commit, keeping its receipt. */
        CommandExecutionService.Execution send(TypedCommand command) {
            var execution = executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                    UUID.randomUUID().toString(), revision, command.type(), command.payload()));
            assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
            revision = execution.operation().committedRevision();
            receipts.add(execution.receipt());
            return execution;
        }

        /** Raise Health so the adder cannot end the fight, and fill the Focus pool. */
        void survive() {
            jdbc.update("update actor_resource set maximum_value = 99, current_value = 99, row_version = row_version + 1 "
                    + "where actor_id = ? and resource_key = 'vigor'", player);
            jdbc.update("update actor_resource set current_value = maximum_value, row_version = row_version + 1 "
                    + "where actor_id = ? and resource_key = 'grit'", player);
        }

        /** Every read the campaign routes serve, and every receipt, as one JSON text. */
        String reads() {
            return JSON.writeValueAsString(List.of(projections.read(owner, adventure, campaign),
                    resumeCards.read(owner, adventure, campaign), playPanels.read(owner, adventure, campaign, null, 20),
                    combatPanels.read(owner, adventure, campaign), stream.read(owner, adventure, campaign, null), receipts));
        }
    }
}
