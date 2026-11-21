package org.rpgengine.adventure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CampaignProjectionService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.combat.EncounterWriter;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.commands.TypedCommand;
import org.rpgengine.memory.ResumeCards;
import org.rpgengine.panels.PlayPanels;
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
    @Autowired private InstalledPackService packs;
    @Autowired private CampaignProjectionService projections;
    @Autowired private ResumeCards resumeCards;
    @Autowired private PlayPanels playPanels;

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
        // Both labels come from the bundled manifest (P15.5-A): its title and its first content theme.
        assertEquals(Map.of("title", "Lantern Ford", "genre", "travel"),
                jdbc.queryForMap("select title, genre from adventure_workspace"));
    }

    /** The labels follow whatever manifest is bundled; without a usable theme the genre is the neutral label. */
    @Test
    void workspaceLabelsAreReadFromTheManifest() {
        assertEquals(new FirstStartProvisioning.Labels("Other Place", "mystery"), FirstStartProvisioning.labels(
                "{\"title\":\" Other Place \",\"contentGuidance\":{\"themes\":[\"mystery\",\"travel\"]}}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(new FirstStartProvisioning.Labels("Other Place", "adventure"),
                FirstStartProvisioning.labels("{\"title\":\"Other Place\",\"contentGuidance\":{\"themes\":[]}}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(new FirstStartProvisioning.Labels("Other Place", "adventure"), FirstStartProvisioning.labels(
                ("{\"title\":\"Other Place\",\"contentGuidance\":{\"themes\":[\"" + "x".repeat(81) + "\"]}}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class, () -> FirstStartProvisioning.labels("{\"title\":\" \"}".getBytes(StandardCharsets.UTF_8)));
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
     * The installed-pack read (P14.1-C) finds the provisioned sample's sealed revision with its author metadata, and
     * the creation screen's choices (a name with default allocations, no archetype) create a playthrough from it
     * whose state, resume card and panels (the live campaign view's reads) answer at the camp, unpaused.
     */
    @Test
    void theProvisionedRevisionIsListedAndAcceptsDefaultChoices() {
        var provisioned = provisioning.provision(OWNER);
        var listing = packs.list(OWNER, provisioned.workspaceId(), null, 50);
        assertEquals(1, listing.packs().size());
        assertNull(listing.nextAfter());
        var pack = listing.packs().getFirst();
        assertEquals(jdbc.queryForObject("select id from content_revision", UUID.class), pack.contentRevisionId());
        assertEquals(List.of("sample:lantern-ford", "1.0.0", "Lantern Ford", "en", "MIT"),
                List.of(pack.packId(), pack.packVersion(), pack.title(), pack.sourceLocale(), pack.license()));
        assertEquals(List.of("Project contributors"), pack.authors());
        assertThrows(AdventureLibraryService.ScopeUnavailable.class,
                () -> packs.list(OWNER, UUID.randomUUID(), null, 50));
        var created = creation.create(OWNER, provisioned.workspaceId(), new CampaignCreationService.Request(
                UUID.randomUUID(), pack.contentRevisionId(), "Lantern watch", SupportedLocale.EN,
                new CharacterChoices("Player", null, Map.of(), Map.of(), null, null)));
        assertEquals(pack.contentRevisionId(), jdbc.queryForObject(
                "select content_revision_id from campaign where id = ?", UUID.class, created.campaignId()));

        // The three reads the live campaign view makes before following the stream all answer for this playthrough.
        var state = projections.read(OWNER, provisioned.workspaceId(), created.campaignId());
        assertEquals("Lantern watch", state.campaign().title());
        assertEquals(List.of("You reach the camp beside the ford at dusk. A raider waits by the crossing; you can face "
                + "them, or look for another way to the far bank.", "en", false), List.of(state.startingSituation().text(),
                state.startingSituation().locale(), state.startingSituation().fallback()));
        var card = resumeCards.read(OWNER, provisioned.workspaceId(), created.campaignId());
        assertEquals(List.of("ford-camp", "Ford Camp"), List.of(card.location().id(), card.location().name()));
        assertEquals(List.of("AVAILABLE", false), List.of(card.dependencies().status(), card.dependencies().mutationPaused()));
        assertNull(card.encounter());
        var panels = playPanels.read(OWNER, provisioned.workspaceId(), created.campaignId(), null, 20);
        assertEquals("Player", panels.character().name());
        assertEquals(created.campaignId(), panels.scope().campaignId());
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

    /**
     * The bundled ford ambush ends by agreement (P14.1-D, ADR 0048): from the engagement and the initiative roll, the
     * player offers the sample's declared passage, which has no check and so always puts its free terms on the table.
     * Only the explicit answer makes the truce, the lost response of the acceptance replays the same receipt, and the
     * settlement returns the campaign to exploration at the camp and replays its own receipt too. Dice decide only what
     * the raider does before the player's first decision, so a fight the raider ended there is started again.
     */
    @Test
    void theProvisionedEncounterEndsInANegotiatedSettlement() {
        var provisioned = provisioning.provision(OWNER);
        UUID revisionId = jdbc.queryForObject("select id from content_revision", UUID.class);
        UUID campaign = null;
        long revision = 0;
        for (int attempt = 0; attempt < 5 && campaign == null; attempt++) {
            var created = creation.create(OWNER, provisioned.workspaceId(), new CampaignCreationService.Request(
                    UUID.randomUUID(), revisionId, "Parley " + attempt, SupportedLocale.EN,
                    new CharacterChoices("Player", "scout", Map.of(), Map.of(), null, null)));
            var engaged = send(created.campaignId(), 0, new TypedCommand.Engage("ford-ambush", null));
            var rolled = send(created.campaignId(), engaged.operation().committedRevision(), new TypedCommand.RollInitiative());
            revision = rolled.operation().committedRevision();
            if ("AWAITING_ACTION".equals(combat(rolled).get("phase"))) {
                campaign = created.campaignId();
            }
        }
        assertTrue(campaign != null, "the raider ended five fights before the player's first decision");

        var offered = send(campaign, revision, new TypedCommand.Interact("offer-passage", "raider"));
        revision = offered.operation().committedRevision();
        var offer = (Map<?, ?>) ((Map<?, ?>) combat(offered).get("options")).get("offer");
        assertEquals("OFFERED", offer.get("response"));
        assertEquals("raider", offer.get("recipientId"));
        assertEquals(Map.of("termsId", "shared-crossing", "kind", "truce", "cost", 0), offer.get("terms"));
        assertEquals(true, offer.get("canAccept"));
        assertEquals("AWAITING_ACTION", combat(offered).get("phase"), "a response binds nobody");

        var answer = new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, "ANSWER_OFFER",
                new TypedCommand.AnswerOffer((String) offer.get("offerId"), "accept").payload());
        var accepted = executions.execute(OWNER, provisioned.workspaceId(), campaign, answer);
        assertEquals(OperationStatus.COMMITTED, accepted.operation().status());
        revision = accepted.operation().committedRevision();
        assertEquals("OUTCOME_PENDING", combat(accepted).get("phase"));
        var outcome = (Map<?, ?>) combat(accepted).get("outcome");
        assertEquals("AGREEMENT", outcome.get("outcome"));
        assertEquals("shared-crossing", outcome.get("termsId"));
        var lost = executions.execute(OWNER, provisioned.workspaceId(), campaign, answer);
        assertTrue(lost.replayed(), "the lost response of the acceptance is retried with the same identity");
        assertEquals(accepted.receipt(), lost.receipt());

        UUID encounter = EncounterWriter.state(jdbc, campaign).orElseThrow().id();
        var settle = new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, "SETTLE_ENCOUNTER",
                new TypedCommand.SettleEncounter(encounter).payload());
        var settled = executions.execute(OWNER, provisioned.workspaceId(), campaign, settle);
        assertEquals(OperationStatus.COMMITTED, settled.operation().status());
        assertEquals("AGREEMENT", ((Map<?, ?>) combat(settled).get("outcome")).get("outcome"));
        assertEquals("EXPLORATION", jdbc.queryForObject("select mode from campaign where id = ?", String.class, campaign));
        assertEquals("ford-camp", resumeCards.read(OWNER, provisioned.workspaceId(), campaign).location().id());
        var again = executions.execute(OWNER, provisioned.workspaceId(), campaign, settle);
        assertTrue(again.replayed(), "the lost response of the settlement is retried with the same identity");
        assertEquals(settled.receipt(), again.receipt());
        assertEquals(1, jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? "
                + "and event_type = 'ENCOUNTER_SETTLED'", Integer.class, campaign));
        assertEquals(1, jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? "
                + "and event_type = 'OFFER_ANSWERED'", Integer.class, campaign));
    }

    private CommandExecutionService.Execution send(UUID campaign, long revision, TypedCommand command) {
        var execution = executions.execute(OWNER, FirstStartProvisioning.sampleWorkspace(OWNER), campaign,
                new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
        return execution;
    }

    private static Map<?, ?> combat(CommandExecutionService.Execution execution) {
        return (Map<?, ?>) execution.receipt().get("combat");
    }
}
