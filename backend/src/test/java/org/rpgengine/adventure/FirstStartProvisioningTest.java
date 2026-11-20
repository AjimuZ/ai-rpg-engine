package org.rpgengine.adventure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.commands.TypedCommand;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real PostgreSQL acceptance of first-start provisioning (P14.1-A.1, ADR 0046): the configured owner, its
 * sample workspace and the sealed bundled adventure are created once and never again; an owner who already
 * has adventures gets no sample; a database of another owner refuses provisioning; and a campaign created
 * from the provisioned revision enters the bundled encounter at its starting location through production code.
 * Provisioning is disabled by default in tests, so each case calls it directly.
 */
@SpringBootTest
class FirstStartProvisioningTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000146");
    @Autowired private FirstStartProvisioning provisioning;
    @Autowired private CampaignCreationService creation;
    @Autowired private CommandExecutionService executions;
    @Autowired private JdbcTemplate jdbc;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    FirstStartProvisioningTest() { }

    /** Start every case from an empty database. */
    @BeforeEach
    void prepare() {
        clear();
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

    /** The first call creates all three; a restart (here two more calls) creates nothing and finds the same rows. */
    @Test
    void provisioningCreatesOneOwnerWorkspaceAndAdventureAndRepeatsAsNothing() {
        var first = provisioning.provision(OWNER);
        assertEquals(new FirstStartProvisioning.Result(true, true, true, FirstStartProvisioning.sampleWorkspace(OWNER)),
                first);
        var unchanged = new FirstStartProvisioning.Result(false, false, false, first.workspaceId());
        assertEquals(unchanged, provisioning.provision(OWNER));
        assertEquals(unchanged, provisioning.provision(OWNER));
        assertEquals(List.of(OWNER), jdbc.queryForList("select id from identity_owner_profile", UUID.class));
        assertEquals(List.of(first.workspaceId()),
                jdbc.queryForList("select id from adventure_workspace where owner_id = ?", UUID.class, OWNER));
        assertEquals(List.of(Map.of("pack_id", "sample:lantern-ford", "pack_version", "1.0.0")),
                jdbc.queryForList("select pack_id, pack_version from content_revision"));
        assertEquals(1, jdbc.queryForObject("select count(*) from content_revision_report", Integer.class),
                "the revision is sealed");
        assertEquals("Lantern Ford", jdbc.queryForObject("select title from adventure_workspace", String.class));
    }

    /** A start interrupted after the workspace step is repaired: the next start seals the revision once. */
    @Test
    void anEmptySampleWorkspaceIsCompletedOnTheNextStart() {
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        jdbc.update("insert into adventure_workspace (id, owner_id, title, genre, source_locale, created_at, updated_at) "
                + "values (?, ?, 'Lantern Ford', 'fantasy', 'en', now(), now())",
                FirstStartProvisioning.sampleWorkspace(OWNER), OWNER);
        assertEquals(new FirstStartProvisioning.Result(false, false, true, FirstStartProvisioning.sampleWorkspace(OWNER)),
                provisioning.provision(OWNER));
        assertEquals(1, jdbc.queryForObject("select count(*) from content_revision", Integer.class));
    }

    /** An owner who already has an adventure is never given the sample, on any start. */
    @Test
    void anOwnerWithAdventuresGetsNoSample() {
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        jdbc.update("insert into adventure_workspace (id, owner_id, title, genre, source_locale, created_at, updated_at) "
                + "values (?, ?, 'Mine', 'mystery', 'en', now(), now())", UUID.randomUUID(), OWNER);
        var result = provisioning.provision(OWNER);
        assertEquals(new FirstStartProvisioning.Result(false, false, false, null), result);
        assertNull(provisioning.provision(OWNER).workspaceId());
        assertEquals(1, jdbc.queryForObject("select count(*) from adventure_workspace", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from content_revision", Integer.class));
    }

    /** A database that belongs to another owner refuses a second owner and writes nothing. */
    @Test
    void aDatabaseOfAnotherOwnerRefusesProvisioning() {
        UUID previous = UUID.fromString("00000000-0000-4000-8000-000000000999");
        provisioning.provision(previous);
        var failure = assertThrows(IllegalStateException.class, () -> provisioning.provision(OWNER));
        assertTrue(failure.getMessage().contains("another owner"), failure.getMessage());
        assertEquals(List.of(previous), jdbc.queryForList("select id from identity_owner_profile", UUID.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from adventure_workspace", Integer.class));
    }

    /**
     * The bundled adventure is playable as provisioned: a campaign created from its revision starts at the camp,
     * where the ford ambush is offered, and entering it commits and switches the campaign to encounter mode.
     */
    @Test
    void theProvisionedAdventureReachesItsEncounterInPlay() {
        var provisioned = provisioning.provision(OWNER);
        UUID revision = jdbc.queryForObject("select id from content_revision", UUID.class);
        var created = creation.create(OWNER, provisioned.workspaceId(), new CampaignCreationService.Request(
                UUID.randomUUID(), revision, "First watch", SupportedLocale.EN,
                new CharacterChoices("Player", "scout", Map.of(), Map.of(), null, null)));
        var engage = new TypedCommand.Engage("ford-ambush", null);
        var execution = executions.execute(OWNER, provisioned.workspaceId(), created.campaignId(),
                new OperationAdmissionService.Command(UUID.randomUUID().toString(), 0, engage.type(), engage.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status());
        assertEquals("ENCOUNTER", jdbc.queryForObject("select mode from campaign where id = ?", String.class,
                created.campaignId()));
        assertEquals("ford-camp", jdbc.queryForObject("select location_id from encounter", String.class));
    }
}
