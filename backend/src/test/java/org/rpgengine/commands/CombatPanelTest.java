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
import java.util.Set;
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
 * Real PostgreSQL acceptance of the player-safe combat projection (P12.1, docs/13 section 7, QA-16). It shows the
 * initiative order, the acting participant's points, the zone graph with passage states and crossing costs, every
 * participant's public standing, the player's own values, what an assessment revealed, the objectives with their status,
 * the legal candidates exactly as the receipt lists them and the verdict waiting for settlement. Hidden values are absent:
 * another participant's Health and defenses before an assessment, its attacks and reactions, a check's difficulty, a
 * counteroffer not yet made and anything an {@code INTERNAL} event or another encounter's event holds. Reading it writes
 * nothing.
 */
@SpringBootTest
class CombatPanelTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The members a participant other than the player may carry, and nothing else. */
    private static final Set<String> PUBLIC_MEMBERS = Set.of("participantId", "side", "zone", "status", "effects", "known");
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private CombatPanels panels;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private long revision;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    CombatPanelTest() { }

    /** Create an owner and an adventure; each test registers its pack. */
    @BeforeEach
    void prepare() {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Panel", "panel", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
    }

    /** Remove disposable rows, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, "
                + "equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, "
                + "memory_summary_job, relationship_change, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
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
     * Outside a fight the projection has no encounter. In {@code close-brawl} it shows the cursor, the order, the space, the
     * standings, the player's values, the objective and the receipt's own candidates; an assessment that succeeds makes the
     * dummy's values known with the round and revision they were learned at, and one that fails leaves the wall unknown.
     * Every read writes nothing and a foreign scope is refused.
     */
    @Test
    void theProjectionShowsTheFightAndOnlyWhatThePlayerKnows() throws Exception {
        campaignOf(EncounterPack.files());
        var calm = read();
        assertNull(calm.encounter());
        assertEquals(revision, calm.scope().stateRevision());

        var rolled = begin("close-brawl");
        var before = world();
        var panel = read();
        assertEquals(before, world(), "reading the projection writes nothing");
        assertEquals(JSON.writeValueAsString(panel), JSON.writeValueAsString(read()), "a repeated read is identical");
        assertEquals(revision, panel.scope().stateRevision());
        var fight = panel.encounter();
        var stored = EncounterWriter.state(jdbc, campaign).orElseThrow();
        assertEquals(stored.id(), fight.encounterId());
        assertEquals("close-brawl", fight.templateId());
        assertEquals("AWAITING_ACTION", fight.phase());
        assertEquals(1, fight.round());
        assertEquals("player", fight.acting());
        assertEquals(stored.actionPoints(), fight.actionPoints());
        assertEquals(stored.actionPoints(), fight.actionPointsPerActivation(), "a fresh activation has every point");
        assertTrue(fight.playerDecision());
        assertEquals(List.of(0, 1, 2, 3, 4), fight.initiative().stream().map(CombatPanels.Initiative::rank).toList());
        assertEquals(Set.of("player", "dummy", "wall", "porter", "lookout"), Set.copyOf(fight.initiative().stream()
                .map(CombatPanels.Initiative::participantId).toList()));
        assertEquals(stored.order().getFirst().initiative(), fight.initiative().getFirst().initiative());
        assertEquals(List.of(new CombatPanels.Zone("gallery", "none", false, 0), new CombatPanels.Zone("ring", "none", false, 0)),
                fight.zones());
        assertEquals(List.of(new CombatPanels.Edge("gallery", "ring", false, false, 1),
                new CombatPanels.Edge("ring", "gallery", false, false, 1)), fight.edges(), "a declared edge is two-way");
        assertEquals(List.of(), fight.escapeZones());
        assertEquals(List.of("player", "dummy", "wall", "porter", "lookout"), fight.participants().stream()
                .map(CombatPanels.Participant::participantId).toList());
        assertEquals("lookout", fight.participants().getLast().participantId());
        assertEquals("gallery", fight.participants().getLast().zone());
        assertEquals("ally", fight.participants().getLast().side());
        assertTrue(fight.participants().stream().allMatch(line -> line.known() == null), "nobody is assessed yet");
        assertEquals(12, fight.player().guard());
        assertEquals(1, fight.player().armor());
        assertEquals(stored.player().health(), fight.player().health());
        assertEquals(List.of(new CombatPanels.Objective("last", "survive", null, null, 3, "pending")), fight.objectives());
        assertNull(fight.outcome());
        assertEquals(tree(((Map<?, ?>) rolled.receipt().get("combat")).get("options")), tree(fight.options()),
                "the candidates, costs and targets are the receipt's");

        var looked = execute(new TypedCommand.Assess("dummy"));
        long learned = revision;
        execute(new TypedCommand.Assess("wall"));
        var assessed = read().encounter();
        assertEquals(new CombatPanels.Known(999, 999, 0, 0, 0, 1, learned), participant(assessed, "dummy").known());
        assertTrue((Boolean) ((Map<?, ?>) ((Map<?, ?>) looked.receipt().get("combat")).get("assessment")).get("success"));
        assertNull(participant(assessed, "wall").known(), "a failed assessment reveals nothing");
        assertEquals(stored.actionPoints() - 2, assessed.actionPoints());

        assertThrows(PlayPanels.ScopeUnavailable.class, () -> panels.read(UUID.randomUUID(), adventure, campaign));
        assertThrows(PlayPanels.ScopeUnavailable.class, () -> panels.read(owner, UUID.randomUUID(), campaign));
    }

    /**
     * Hidden data sentinels (QA-16): the sentry's attack {@code jab} and reaction {@code snap} never appear, every participant
     * other than the player carries only public members, and the known values come only from this encounter's public
     * assessment: a forged internal assessment and one of another encounter, both newer, are ignored.
     */
    @Test
    void hiddenSentinelsAreAbsent() throws Exception {
        campaignOf(EncounterPack.tactics());
        begin("ridge-retreat");
        var fight = read().encounter();
        assertEquals(List.of("pass"), fight.escapeZones());
        assertEquals(List.of("camp>gully", "camp>ridge", "gully>camp", "pass>ridge", "ridge>camp", "ridge>pass"),
                fight.edges().stream().map(edge -> edge.from() + ">" + edge.to()).toList());
        assertTrue(fight.edges().stream().allMatch(edge -> !edge.blocked() && Integer.valueOf(1).equals(edge.cost())));
        assertEquals(tree(List.of(Map.of("zone", "ridge", "cost", 2))), tree(fight.options().get("withdraw")));
        assertEquals(List.of(new CombatPanels.Objective("away", "escape", null, null, null, "pending")), fight.objectives());
        assertSentinelsAbsent(List.of("jab", "snap"));
        assertNull(participant(fight, "sentry").known());

        execute(new TypedCommand.Assess("sentry"));
        long learned = revision;
        var known = new CombatPanels.Known(999, 999, 0, 0, 0, 1, learned);
        assertEquals(known, participant(read().encounter(), "sentry").known());

        UUID operation = jdbc.queryForObject("select id from operation_journal where campaign_id = ? order by committed_revision "
                + "desc nulls last limit 1", UUID.class, campaign);
        String forged = "{\"encounterId\":\"%s\",\"round\":1,\"participantId\":\"player\",\"targetId\":\"sentry\","
                + "\"success\":true,\"revealed\":{\"health\":%d,\"healthMax\":%d,\"guard\":%d,\"resistance\":%d,\"armor\":%d}}";
        String encounter = fight.encounterId().toString();
        insertEvent(operation, "INTERNAL", forged.formatted(encounter, 7331, 7331, 7331, 7331, 7331));
        insertEvent(operation, "PUBLIC", forged.formatted(UUID.randomUUID(), 4242, 4242, 4242, 4242, 4242));
        assertEquals(known, participant(read().encounter(), "sentry").known(),
                "an internal event and another encounter's event never feed the panel");
        // Numeric sentinels are checked structurally above: as text they could occur inside an identity's hex digits.
        assertSentinelsAbsent(List.of("jab", "snap"));
    }

    /**
     * In {@code parley-ground} the declared actions are listed with their proposed terms and public check, never a counteroffer
     * not yet made or a difficulty. Once an accepted truce decides the fight, the projection names the verdict waiting for
     * settlement, lists no acting participant and no candidate, and shows the objective's status.
     */
    @Test
    void negotiationHidesTheCounterofferAndShowsTheVerdict() throws Exception {
        campaignOf(EncounterPack.tactics());
        begin("parley-ground");
        assertSentinelsAbsent(List.of("small-toll", "\"toll\"", "difficulty", "margin"));
        var offered = execute(new TypedCommand.Interact("offer-peace", "captain"));
        var offer = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) offered.receipt().get("combat")).get("options")).get("offer");
        assertEquals(tree(offer), tree(read().encounter().options().get("offer")), "the offer on the table is the receipt's");
        execute(new TypedCommand.AnswerOffer((String) offer.get("offerId"), "accept"));

        var decided = read().encounter();
        assertEquals("OUTCOME_PENDING", decided.phase());
        assertNull(decided.acting());
        assertNull(decided.actionPoints());
        assertFalse(decided.playerDecision());
        assertEquals(Map.of(), decided.options());
        assertEquals("AGREEMENT", decided.outcome().get("outcome"));
        assertEquals("open-road", decided.outcome().get("termsId"));
        assertEquals(List.of(new CombatPanels.Objective("last", "survive", null, null, 5, "pending")), decided.objectives());
    }

    /**
     * Outside a fight the projection offers exactly the templates a plain {@code ENGAGE} admits (P13.2-B, ADR 0042): the
     * trailhead's in ID order and never the grove's; at the grove only the grove's, whose ally is never an opening target. An
     * offer carries only what entry reveals, an offered opening target is accepted by {@code ENGAGE}, and nothing is offered
     * during the fight it starts. Reading the offers writes nothing.
     */
    @Test
    void offersNameOnlyTheEncountersEntryAdmits() throws Exception {
        campaignOf(EncounterPack.files());
        var before = world();
        var calm = read();
        assertEquals(before, world(), "reading the offers writes nothing");
        assertEquals(List.of("close-brawl", "close-rush", "toll-parley", "trail-ambush", "yard-duel"), ids(calm));
        assertEquals(new CombatPanels.Offer("trail-ambush", "entry",
                List.of(new CombatPanels.OfferParticipant("raider", "hostile", "yard")), List.of("escape-route"),
                List.of(new CombatPanels.Stake("drive-off", "defeatHostiles", null, null, null),
                        new CombatPanels.Stake("slip-away", "escape", null, null, null)),
                "initiative", List.of("raider")), offer(calm, "trail-ambush"));
        var parley = offer(calm, "toll-parley");
        assertEquals(List.of(new CombatPanels.OfferParticipant("keeper", "ally", "gate"),
                new CombatPanels.OfferParticipant("smuggler", "hostile", "booth")), parley.participants());
        assertEquals(List.of(new CombatPanels.Stake("hold", "survive", null, null, 3),
                new CombatPanels.Stake("keep-keeper", "protect", null, "keeper", null)), parley.objectives());
        assertEquals("forbidden", parley.openingPolicy());
        assertEquals(List.of(), parley.openingTargets(), "a forbidden opening names no target");
        // Hidden sentinels: the portrayed NPC, attacks, declared actions, threat tiers, cover, zone tags and display names.
        String body = JSON.writeValueAsString(calm.offers());
        for (String sentinel : List.of("warden", "cudgel", "taunt", "seal-route", "demand-passage", "standard", "minor",
                "partial", "obscured", "exit", "Trail Ambush", "Raider", "health", "stats")) {
            assertFalse(body.contains(sentinel), "hidden sentinel in the offers: " + sentinel);
        }
        for (JsonNode offer : JSON.readTree(body)) {
            assertEquals(Set.of("templateId", "playerZone", "participants", "escapeZones", "objectives", "openingPolicy",
                    "openingTargets"), Set.copyOf(offer.propertyNames()));
            for (JsonNode participant : offer.path("participants")) {
                assertEquals(Set.of("participantId", "side", "zone"), Set.copyOf(participant.propertyNames()));
            }
        }

        execute(new TypedCommand.Move("grove"));
        var grove = read();
        assertEquals(List.of("grove-watch"), ids(grove), "only the current location's templates are offered");
        assertEquals(List.of("wolf"), offer(grove, "grove-watch").openingTargets(), "an ally is never an opening target");
        execute(new TypedCommand.Move("trailhead"));
        assertEquals(ids(calm), ids(read()));

        execute(new TypedCommand.Engage("trail-ambush", "raider"));
        var fighting = read();
        assertEquals("trail-ambush", fighting.encounter().templateId());
        assertEquals(List.of(), fighting.offers(), "nothing is offered during a fight");
    }

    /** A defeated player is offered nothing, exactly as {@code ENGAGE} refuses every entry for one. */
    @Test
    void aDefeatedPlayerIsOfferedNothing() throws Exception {
        campaignOf(EncounterPack.files());
        UUID player = jdbc.queryForObject("select actor_id from campaign_creation where campaign_id = ?", UUID.class,
                campaign);
        jdbc.update("update actor_resource set current_value = minimum_value, row_version = row_version + 1 "
                + "where campaign_id = ? and actor_id = ? and resource_key = 'vigor'", campaign, player);
        assertEquals(List.of(), read().offers());
        var refused = assertThrows(OperationAdmissionFailure.class, () -> executions.execute(owner, adventure, campaign,
                new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, "ENGAGE",
                        Map.of("encounterId", "trail-ambush"))));
        assertEquals(OperationAdmissionFailure.Kind.ENCOUNTER_UNAVAILABLE, refused.kind());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static List<String> ids(CombatPanels.CombatPanel panel) {
        return panel.offers().stream().map(CombatPanels.Offer::templateId).toList();
    }

    private static CombatPanels.Offer offer(CombatPanels.CombatPanel panel, String id) {
        return panel.offers().stream().filter(offer -> offer.templateId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError(id));
    }

    /** Register a pack variant and create a campaign on it; the surveyor starts at the trailhead. */
    private void campaignOf(Map<String, byte[]> files) throws Exception {
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(files)))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        campaign = created.campaignId();
        revision = 0;
    }

    /** Enter a template at the trailhead and roll initiative; the player decides first. */
    private CommandExecutionService.Execution begin(String template) {
        execute(new TypedCommand.Engage(template, null));
        var rolled = execute(new TypedCommand.RollInitiative());
        assertTrue(EncounterWriter.state(jdbc, campaign).orElseThrow().playerDecision());
        return rolled;
    }

    private CombatPanels.CombatPanel read() {
        return panels.read(owner, adventure, campaign);
    }

    private CommandExecutionService.Execution execute(TypedCommand command) {
        var execution = executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision, command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
        revision = execution.operation().committedRevision();
        return execution;
    }

    /**
     * Check the serialized projection: no sentinel text anywhere, and every participant other than the player carries only
     * public members.
     */
    private void assertSentinelsAbsent(List<String> sentinels) throws Exception {
        String body = JSON.writeValueAsString(read());
        for (String sentinel : sentinels) {
            assertFalse(body.contains(sentinel), "hidden sentinel in the projection: " + sentinel);
        }
        for (JsonNode line : JSON.readTree(body).path("encounter").path("participants")) {
            assertEquals(PUBLIC_MEMBERS, Set.copyOf(line.propertyNames()), line.toString());
        }
    }

    private static CombatPanels.Participant participant(CombatPanels.Encounter fight, String id) {
        return fight.participants().stream().filter(line -> line.participantId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError(id));
    }

    /** @return a value as a JSON tree, so receipt and projection values compare regardless of their map types */
    private static JsonNode tree(Object value) throws Exception {
        return JSON.readTree(JSON.writeValueAsString(value));
    }

    /** Append a public or internal event directly, as the narration suite does. */
    private void insertEvent(UUID operation, String visibility, String payload) {
        long sequence = jdbc.queryForObject("select max(sequence) + 1 from campaign_event where campaign_id = ?", Long.class,
                campaign);
        jdbc.update("insert into campaign_event (id, campaign_id, sequence, operation_id, correlation_id, event_type, "
                + "event_version, state_revision, world_time_seconds, ruleset_hash, visibility, payload_schema_version, "
                + "payload, created_at) values (gen_random_uuid(), ?, ?, ?, gen_random_uuid(), 'ASSESSMENT_RESOLVED', 1, ?, 0, ?, "
                + "?, 1, ?::jsonb, now())", campaign, sequence, operation, revision, "b".repeat(64), visibility, payload);
    }

    /** Every table a command can touch, for exact before/after comparison. */
    private Map<String, List<String>> world() {
        var tables = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("campaign", "actor", "actor_resource", "campaign_event", "operation_journal",
                "operation_random_draw", "canonical_outbox", "encounter", "encounter_participant", "item_instance",
                "campaign_stream_message", "presentation_job")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }
}
