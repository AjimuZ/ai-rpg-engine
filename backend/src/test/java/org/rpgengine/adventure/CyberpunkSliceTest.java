package org.rpgengine.adventure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import org.rpgengine.content.CompiledPack;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.memory.ResumeCards;
import org.rpgengine.panels.PlayPanels;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PostgreSQL acceptance of the second genre (P14.1-B, QA-01): the cyberpunk fixture {@code Neon Alley} is
 * imported through the ordinary staging and registration path next to the provisioned fantasy sample, and a campaign
 * in each supported language walks the same path as the fantasy slice (creation, the opening situation, entering the
 * encounter, rolling initiative, an ending, the settlement and the return to exploration) with no production source
 * change. Both endings the fixture offers are played in each language: the negotiated toll and the climb to the
 * rooftop. Dice decide only what the enforcer does before the player's first decision, so a fight the enforcer ended
 * there is started again.
 */
@SpringBootTest
class CyberpunkSliceTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000141");
    private static final String SITUATION_EN = "You step off the tram at the Neon Alley stop after midnight. An enforcer "
            + "blocks the lane to the data dock; you can face them, or look for another way through.";
    private static final String SITUATION_IT = "Scendi dal tram alla fermata del Vicolo al neon dopo mezzanotte. Un "
            + "esattore armato blocca la corsia verso il molo dati: puoi affrontarlo, oppure cercare un'altra strada.";
    @Autowired private FirstStartProvisioning provisioning;
    @Autowired private PackRegistry registry;
    @Autowired private CampaignCreationService creation;
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignProjectionService projections;
    @Autowired private ResumeCards resumeCards;
    @Autowired private PlayPanels playPanels;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private UUID fantasyWorkspace;
    private UUID fantasyRevision;
    private UUID workspace;
    private UUID revision;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    CyberpunkSliceTest() { }

    /** Provision the fantasy sample, then import the cyberpunk fixture into a second workspace of the same owner. */
    @BeforeEach
    void prepare() throws URISyntaxException {
        clear();
        var provisioned = provisioning.provision(OWNER);
        fantasyWorkspace = provisioned.workspaceId();
        fantasyRevision = jdbc.queryForObject("select id from content_revision where adventure_id = ?", UUID.class,
                fantasyWorkspace);
        workspace = UUID.randomUUID();
        jdbc.update("insert into adventure_workspace (id, owner_id, title, genre, source_locale, created_at, updated_at) "
                + "values (?, ?, 'Neon Alley', 'cyberpunk', 'en', now(), now())", workspace, OWNER);
        Path directory = Path.of(getClass().getResource("/packs/cyberpunk-neon-alley/").toURI());
        var registered = registry.register(workspace, new PackStaging(PackStaging.Limits.standard()).directory(directory));
        assertTrue(registered.created());
        revision = registered.revisionId();
    }

    /** Remove disposable rows, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, "
                + "equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, "
                + "memory_summary_job, relationship_change, faction_clock_change, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
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
     * Two genres on one build (QA-01): the fixture seals as its own pack next to the sample, shares no definition
     * identity or display name with it beyond the ruleset's fixed rule slots, and declares no module.
     */
    @Test
    void theSecondGenreSharesNoVocabularyWithTheFantasySample() {
        CompiledPack cyber = load(workspace, revision);
        CompiledPack fantasy = load(fantasyWorkspace, fantasyRevision);
        assertEquals(List.of("sample:cyberpunk-neon-alley", "sample:lantern-ford"), jdbc.queryForList(
                "select pack_id from content_revision order by pack_id", String.class));
        assertEquals(List.of(), cyber.parsed().manifest().values().get("modules"));
        assertEquals(List.of(), fantasy.parsed().manifest().values().get("modules"));
        Set<String> ids = new HashSet<>(cyber.byId().keySet());
        ids.removeIf(id -> id.startsWith("rules."));
        Set<String> fantasyIds = new HashSet<>(fantasy.byId().keySet());
        fantasyIds.removeIf(id -> id.startsWith("rules."));
        assertFalse(ids.isEmpty());
        ids.retainAll(fantasyIds);
        assertEquals(Set.of(), ids, "no definition identity is shared");
        assertEquals(Set.of(), Sets.intersection(names(cyber), names(fantasy)), "no display name is shared");
        assertEquals(1, jdbc.queryForObject("select count(*) from content_revision where adventure_id = ?",
                Integer.class, workspace));
        assertEquals(1, jdbc.queryForObject("select count(*) from content_revision where adventure_id = ?",
                Integer.class, fantasyWorkspace));
    }

    /**
     * The same overlay serves the encounter text the player reads: zone, opponent, objective and action names and the
     * proposed terms resolve in reviewed Italian without the source-language fallback, and in English from the source.
     */
    @Test
    void theEncounterTextResolvesInBothLanguages() {
        var text = load(workspace, revision).localization();
        for (String[] row : new String[][] {
                {"/zones/roof/name", "Rooftop", "Tetto"},
                {"/participants/enforcer/name", "Enforcer", "Esattore armato"},
                {"/objectives/slip-away/name", "Climb to the rooftop", "Sali sul tetto"},
                {"/actions/offer-toll/name", "Offer a toll", "Offri un pedaggio"},
                {"/actions/offer-toll/terms/name", "A paid passage", "Un passaggio pagato"}}) {
            var english = text.resolve("lane-standoff", row[0], "en");
            var italian = text.resolve("lane-standoff", row[0], "it");
            assertEquals(List.of(row[1], "en", false), List.of(english.text(), english.locale(), english.fallback()), row[0]);
            assertEquals(List.of(row[2], "it", false), List.of(italian.text(), italian.locale(), italian.fallback()), row[0]);
        }
    }

    /** The English slice: opening text and names in English, then the negotiated ending and the climb, each settled. */
    @Test
    void theEnglishPlaythroughFightsAndReturnsToExploration() {
        var voice = new Voice(SupportedLocale.EN, SITUATION_EN, "Tram Stop", "Reflex", "Tech", "Integrity",
                List.of("Armored Jacket", "Cyberdeck", "Machine Pistol"), "Stim Patch", "Shooting", "Netrunning");
        playBoth(voice);
    }

    /** The Italian slice: the same path with the opening text, names and terminology in reviewed Italian. */
    @Test
    void theItalianPlaythroughFightsAndReturnsToExploration() {
        var voice = new Voice(SupportedLocale.IT, SITUATION_IT, "Fermata del tram", "Riflessi", "Tecnica", "Integrità",
                List.of("Cyberdeck", "Giacca corazzata", "Pistola mitragliatrice"), "Cerotto stimolante", "Tiro",
                "Intrusione in rete");
        playBoth(voice);
    }

    private void playBoth(Voice voice) {
        for (boolean negotiate : new boolean[] {true, false}) {
            UUID campaign = null;
            long revisionNumber = 0;
            for (int attempt = 0; attempt < 5 && campaign == null; attempt++) {
                UUID candidate = create(voice, (negotiate ? "Toll " : "Climb ") + attempt);
                checkOpening(voice, candidate);
                long decided = startFight(candidate);
                if (decided >= 0) {
                    campaign = candidate;
                    revisionNumber = decided;
                }
            }
            assertTrue(campaign != null, "the enforcer ended five fights before the player's first decision");
            if (negotiate) {
                negotiate(campaign, revisionNumber);
            } else {
                climb(campaign, revisionNumber);
            }
        }
    }

    private UUID create(Voice voice, String title) {
        return creation.create(OWNER, workspace, new CampaignCreationService.Request(UUID.randomUUID(), revision,
                title + " " + voice.locale().tag(), voice.locale(),
                new CharacterChoices("Player", "courier", Map.of(), Map.of(), null, null))).campaignId();
    }

    /** The opening text, place, attributes, resource, skills, ability and items arrive in the campaign's language. */
    private void checkOpening(Voice voice, UUID campaign) {
        String tag = voice.locale().tag();
        var state = projections.read(OWNER, workspace, campaign);
        assertEquals(List.of(voice.situation(), tag, false), List.of(state.startingSituation().text(),
                state.startingSituation().locale(), state.startingSituation().fallback()));
        var card = resumeCards.read(OWNER, workspace, campaign);
        assertEquals("tram-stop", card.location().id());
        assertNull(card.encounter());
        var panels = playPanels.read(OWNER, workspace, campaign, null, 20);
        assertEquals(tag, panels.locale());
        assertTrue(panels.contentAvailable());
        var place = panels.character().location();
        assertEquals(List.of("tram-stop", voice.place(), tag, false),
                List.of(place.id(), place.name().text(), place.name().locale(), place.name().fallback()));
        assertEquals(Map.of("reflex", voice.reflex(), "tech", voice.tech()), panels.character().attributes().stream()
                .collect(Collectors.toMap(PlayPanels.Stat::key, stat -> stat.name().text())));
        assertEquals(Map.of("shooting", voice.shooting(), "netrunning", voice.netrunning()),
                panels.character().skills().stream().collect(Collectors.toMap(PlayPanels.Skill::key,
                        skill -> skill.name().text())));
        assertEquals(List.of("integrity", voice.integrity()), List.of(panels.resources().getFirst().key(),
                panels.resources().getFirst().name().text()));
        assertEquals(List.of(voice.patch()), panels.abilities().stream().map(a -> a.name().text()).toList());
        assertEquals(voice.items(), panels.inventory().stream().map(item -> item.name().text()).sorted().toList());
        var everything = new java.util.ArrayList<PlayPanels.Text>();
        everything.add(place.name());
        panels.character().attributes().forEach(stat -> everything.add(stat.name()));
        panels.character().skills().forEach(skill -> everything.add(skill.name()));
        panels.resources().forEach(resource -> everything.add(resource.name()));
        panels.abilities().forEach(ability -> everything.add(ability.name()));
        panels.inventory().forEach(item -> everything.add(item.name()));
        assertTrue(everything.stream().allMatch(item -> item.locale().equals(tag) && !item.fallback()),
                "every name is in the campaign's language, with no fallback notice");
    }

    /**
     * Engage the encounter at the tram stop and roll initiative.
     *
     * @return the committed revision at which the player decides, or -1 when the enforcer ended the fight before the
     *         player's first decision, so the caller starts another campaign
     */
    private long startFight(UUID campaign) {
        var engaged = send(campaign, 0, new TypedCommand.Engage("lane-standoff", null));
        assertEquals("ENCOUNTER", jdbc.queryForObject("select mode from campaign where id = ?", String.class, campaign));
        assertEquals("tram-stop", jdbc.queryForObject("select location_id from encounter where campaign_id = ?",
                String.class, campaign));
        var rolled = send(campaign, engaged.operation().committedRevision(), new TypedCommand.RollInitiative());
        return "AWAITING_ACTION".equals(combat(rolled).get("phase")) ? rolled.operation().committedRevision() : -1;
    }

    /** Offer the declared toll; the enforcer's response is certain, only the explicit answer settles the fight. */
    private void negotiate(UUID campaign, long revisionNumber) {
        var offered = send(campaign, revisionNumber, new TypedCommand.Interact("offer-toll", "enforcer"));
        var offer = (Map<?, ?>) ((Map<?, ?>) combat(offered).get("options")).get("offer");
        assertEquals("OFFERED", offer.get("response"));
        assertEquals("enforcer", offer.get("recipientId"));
        assertEquals(Map.of("termsId", "paid-passage", "kind", "truce", "cost", 0), offer.get("terms"));
        assertEquals(true, offer.get("canAccept"));
        assertEquals("AWAITING_ACTION", combat(offered).get("phase"), "a response binds nobody");
        var answer = new OperationAdmissionService.Command(UUID.randomUUID().toString(),
                offered.operation().committedRevision(), "ANSWER_OFFER",
                new TypedCommand.AnswerOffer((String) offer.get("offerId"), "accept").payload());
        var accepted = executions.execute(OWNER, workspace, campaign, answer);
        assertEquals(OperationStatus.COMMITTED, accepted.operation().status());
        assertEquals("OUTCOME_PENDING", combat(accepted).get("phase"));
        var outcome = (Map<?, ?>) combat(accepted).get("outcome");
        assertEquals(List.of("AGREEMENT", "paid-passage"), List.of(outcome.get("outcome"), outcome.get("termsId")));
        var lost = executions.execute(OWNER, workspace, campaign, answer);
        assertTrue(lost.replayed(), "the lost response of the acceptance is retried with the same identity");
        assertEquals(accepted.receipt(), lost.receipt());
        settle(campaign, accepted.operation().committedRevision(), "AGREEMENT");
    }

    /** Climb to the rooftop, the fixture's escape zone, one plain step from the platform. */
    private void climb(UUID campaign, long revisionNumber) {
        var moved = send(campaign, revisionNumber, new TypedCommand.MoveZone("roof"));
        assertEquals("OUTCOME_PENDING", combat(moved).get("phase"));
        assertEquals("ESCAPE", ((Map<?, ?>) combat(moved).get("outcome")).get("outcome"));
        settle(campaign, moved.operation().committedRevision(), "ESCAPE");
    }

    /** Settle the verdict once, replay the lost response, and check that play is back in exploration at the stop. */
    private void settle(UUID campaign, long revisionNumber, String outcome) {
        UUID encounter = EncounterWriter.state(jdbc, campaign).orElseThrow().id();
        var settle = new OperationAdmissionService.Command(UUID.randomUUID().toString(), revisionNumber,
                "SETTLE_ENCOUNTER", new TypedCommand.SettleEncounter(encounter).payload());
        var settled = executions.execute(OWNER, workspace, campaign, settle);
        assertEquals(OperationStatus.COMMITTED, settled.operation().status());
        assertEquals(outcome, ((Map<?, ?>) combat(settled).get("outcome")).get("outcome"));
        assertEquals("EXPLORATION", jdbc.queryForObject("select mode from campaign where id = ?", String.class, campaign));
        var card = resumeCards.read(OWNER, workspace, campaign);
        assertEquals("tram-stop", card.location().id());
        assertNull(card.encounter(), "no fight remains after the settlement");
        var again = executions.execute(OWNER, workspace, campaign, settle);
        assertTrue(again.replayed(), "the lost response of the settlement is retried with the same identity");
        assertEquals(settled.receipt(), again.receipt());
        assertEquals(1, jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? "
                + "and event_type = 'ENCOUNTER_SETTLED'", Integer.class, campaign));
    }

    /** Recompile a sealed revision's stored bytes as a campaign would, which the registry only does inside a transaction. */
    private CompiledPack load(UUID adventure, UUID sealed) {
        return new TransactionTemplate(transactions).execute(status -> registry.loadForCampaign(adventure, sealed).root());
    }

    private CommandExecutionService.Execution send(UUID campaign, long revisionNumber, TypedCommand command) {
        var execution = executions.execute(OWNER, workspace, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revisionNumber, command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
        return execution;
    }

    private static Map<?, ?> combat(CommandExecutionService.Execution execution) {
        return (Map<?, ?>) execution.receipt().get("combat");
    }

    /** Every top-level display name a pack's definitions declare. */
    private static Set<String> names(CompiledPack pack) {
        return pack.byId().values().stream().map(definition -> definition.data().get("name"))
                .filter(String.class::isInstance).map(String.class::cast).collect(Collectors.toSet());
    }

    /** What a campaign in one language must show: the opening text and the pack's names as reviewed in that language. */
    private record Voice(SupportedLocale locale, String situation, String place, String reflex, String tech,
            String integrity, List<String> items, String patch, String shooting, String netrunning) { }

    /** Set intersection without mutating either operand. */
    private static final class Sets {
        private Sets() { }

        static Set<String> intersection(Set<String> left, Set<String> right) {
            Set<String> result = new HashSet<>(left);
            result.retainAll(right);
            return result;
        }
    }
}
