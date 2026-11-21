package org.rpgengine.commands;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.knowledge.KnowledgeChange.Acquisition;
import org.rpgengine.knowledge.KnowledgeLedger;
import org.rpgengine.knowledge.KnowledgeView;
import org.rpgengine.preparation.QueryEpochs;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA-21 on real PostgreSQL (P15.1-A): world time and witnesses determine NPC presence and knowledge, and
 * inspections advance neither. A sealed pack declares a scheduled witness that reports to a captain after
 * thirty in-world minutes and a bystander without world rules; registered player commands run through the
 * production admission, resolver and canonical commit. The suite proves that a schedule moves an NPC only when
 * a command's world time crosses its boundary, that only a co-present witness learns where the player is, that
 * a report arrives exactly when world time reaches its arrival and never earlier, and that reads, real time and
 * refused commands change no row.
 */
@SpringBootTest
class NpcWorldTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> TABLES = List.of("campaign", "actor", "campaign_event", "operation_journal",
            "actor_sighting", "knowledge_belief", "query_epoch");
    @Autowired private CommandExecutionService execution;
    @Autowired private OperationAdmissionService admissions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private KnowledgeView view;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private UUID player;
    private UUID keeper;
    private UUID captain;
    private UUID bystander;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    NpcWorldTest() { }

    /**
     * Register the pack through real staging and sealing, create a campaign through the production creation
     * service, then place the authored NPCs where their definitions start them: the keeper and the bystander at
     * the trailhead beside the player, the captain at the grove.
     */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "World", "world", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        var fixture = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI())).files());
        UUID revision = register(pack(fixture)).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(),
                revision, "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(),
                Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        keeper = npc("keeper", "Keeper", "trailhead");
        captain = npc("captain", "Captain", "grove");
        bystander = npc("bystander", "Bystander", "trailhead");
    }

    /** Remove disposable test rows, preserving production constraints and triggers. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
                + "narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, "
                + "campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, "
                + "quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, query_epoch, "
                + "campaign_mutation_lease, operation_random_draw, campaign_random_seed, canonical_outbox, "
                + "campaign_checkpoint, reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, "
                + "content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * QA-21: the keeper's routine and its report route decide who is where and who knows what, as world time
     * moves through committed commands; reading the game, real time and a refused command decide nothing.
     */
    @Test
    void worldTimeAndWitnessesDeterminePresenceAndKnowledge() throws Exception {
        // 00:10. The witness beside the player records the sighting; the bystander, who is no witness, does not,
        // and the captain at the grove has heard nothing yet: the report is due at 00:40.
        run(new TypedCommand.Wait(600));
        long first = revision();
        var seen = only(view.forActor(campaign, keeper).lastKnown());
        assertEquals(player, seen.subjectId());
        assertEquals("trailhead", seen.locationId());
        assertEquals(Acquisition.OBSERVED, seen.acquisition());
        assertEquals(first, seen.observedRevision());
        assertEquals(600, seen.observedWorldTime());
        assertEquals(List.of(), view.forActor(campaign, bystander).lastKnown());
        assertEquals(List.of(), view.forActor(campaign, captain).lastKnown());
        assertEquals(1L, epoch(KnowledgeLedger.sightingScope(keeper)));

        // Inspections advance neither presence nor knowledge: every audience view, the privileged view, real
        // time passing and a refused command leave every row exactly as the last command did.
        var before = snapshot();
        for (int round = 0; round < 3; round++) {
            view.forActor(campaign, player);
            view.forActor(campaign, keeper);
            view.forActor(campaign, captain);
            view.privileged(campaign);
            Thread.sleep(200);
        }
        assertRefused(OperationAdmissionFailure.Kind.TIME_ILLEGAL, new TypedCommand.Wait(5 * 3_600));
        assertEquals(before, snapshot());

        // 00:30. Still inside the keeper's first period, and the report is not due: nothing moves, and the
        // witness keeps the sighting from when it first saw the player here.
        run(new TypedCommand.Wait(1_200));
        assertEquals("trailhead", location(keeper));
        assertEquals(first, only(view.forActor(campaign, keeper).lastKnown()).observedRevision());
        assertEquals(List.of(), view.forActor(campaign, captain).lastKnown());

        // 00:45. The report arrived at 00:40: the captain is told, by the keeper, where the player was then.
        run(new TypedCommand.Wait(900));
        var told = only(view.forActor(campaign, captain).lastKnown());
        assertEquals(player, told.subjectId());
        assertEquals("trailhead", told.locationId());
        assertEquals(Acquisition.TOLD, told.acquisition());
        assertEquals(keeper, told.informantId());
        assertEquals(2_400, told.observedWorldTime());
        assertEquals(revision(), told.observedRevision());
        assertEquals(1L, epoch(KnowledgeLedger.sightingScope(captain)));
        assertEquals(List.of(), view.forActor(campaign, bystander).lastKnown());
        // The same report is delivered once: a later commit with nothing new leaves the captain's knowledge alone.
        long delivered = told.observedRevision();
        run(new TypedCommand.Wait(60));
        assertEquals(delivered, only(view.forActor(campaign, captain).lastKnown()).observedRevision());

        // 01:16. The keeper's routine moved it to the grove at 01:00; the player, still at the trailhead, no
        // longer finds it there and can no longer address it, while the bystander without a schedule stays.
        long keeperVersion = rowVersion(keeper);
        run(new TypedCommand.Wait(1_800));
        assertEquals("grove", location(keeper));
        assertEquals(keeperVersion + 1, rowVersion(keeper));
        assertEquals(1L, epoch("actor:" + keeper));
        assertEquals("trailhead", location(bystander));
        var lost = view.forActor(campaign, player).lastKnown().stream().filter(s -> s.subjectId().equals(keeper))
                .findFirst().orElseThrow();
        assertEquals("trailhead", lost.locationId());
        assertEquals(revision(), lost.absenceRevision());
        assertRefused(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE, new TypedCommand.Speak(keeper, "Hello"));
        // The keeper's own knowledge did not follow the player either: it last saw the player at the trailhead.
        assertEquals("trailhead", only(view.forActor(campaign, keeper).lastKnown()).locationId());

        // The player walks to the grove. The keeper sees it there at once; the captain, who stands beside the
        // player but is no witness, keeps the old report until the keeper's new one arrives thirty minutes later.
        run(new TypedCommand.Move("grove"));
        long arrived = jdbc.queryForObject("select world_time_seconds from campaign where id = ?", Long.class, campaign);
        var moved = only(view.forActor(campaign, keeper).lastKnown());
        assertEquals("grove", moved.locationId());
        assertEquals(arrived, moved.observedWorldTime());
        assertEquals("trailhead", only(view.forActor(campaign, captain).lastKnown()).locationId());
        run(new TypedCommand.Wait(1_799));
        assertEquals("trailhead", only(view.forActor(campaign, captain).lastKnown()).locationId());
        run(new TypedCommand.Wait(1));
        var news = only(view.forActor(campaign, captain).lastKnown());
        assertEquals("grove", news.locationId());
        assertEquals(keeper, news.informantId());
        assertEquals(arrived + 1_800, news.observedWorldTime());
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The single element of a list. */
    private static <T> T only(List<T> list) {
        assertEquals(1, list.size(), list.toString());
        return list.getFirst();
    }

    /** Run a registered player intention end to end at the current revision and require its commit. */
    private void run(TypedCommand command) {
        var result = execution.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision(), command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, result.operation().status(), command.toString());
    }

    /** Assert that a fresh admission is refused with exactly this category and writes nothing. */
    private void assertRefused(OperationAdmissionFailure.Kind kind, TypedCommand command) {
        var before = snapshot();
        assertEquals(kind, assertThrows(OperationAdmissionFailure.class, () -> admissions.submit(owner, adventure,
                campaign, new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision(),
                        command.type(), command.payload()))).kind());
        assertEquals(before, snapshot());
    }

    /** Current committed state revision. */
    private long revision() {
        return jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign);
    }

    /** Current canonical location of an actor. */
    private String location(UUID actor) {
        return jdbc.queryForObject("select location_id from actor where id = ?", String.class, actor);
    }

    /** Current storage version of an actor row. */
    private long rowVersion(UUID actor) {
        return jdbc.queryForObject("select row_version from actor where id = ?", Long.class, actor);
    }

    /** Current epoch of a query scope. */
    private long epoch(String scope) {
        return QueryEpochs.current(jdbc, campaign, scope);
    }

    /** Persist one campaign NPC with an authored origin at a location. */
    private UUID npc(String definition, String name, String location) {
        return new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, definition, name, location, 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
    }

    /** Exact committed rows of every table a command, a schedule move or a report could touch. */
    private Map<String, String> snapshot() {
        var snapshot = new LinkedHashMap<String, String>();
        for (String table : TABLES) {
            snapshot.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return snapshot;
    }

    /**
     * Fixture variant: the keeper is a witness at the trailhead until 01:00 and at the grove from then until
     * midnight, and reports what it sees to the captain thirty minutes later; the captain and the bystander
     * have no world rules.
     */
    private static Map<String, byte[]> pack(Map<String, byte[]> fixture) {
        var files = new TreeMap<>(fixture);
        var keeper = npcDefinition("keeper", "Trail keeper", "trailhead");
        keeper.put("witness", true);
        keeper.put("schedule", List.of(Map.of("from", "00:00", "location", "trailhead"),
                Map.of("from", "01:00", "location", "grove")));
        keeper.put("reports", List.of(Map.of("to", "captain", "minutes", 30)));
        put(files, "npcs.json", List.of(Map.of("id", "keeper", "kind", "npc", "data", keeper),
                Map.of("id", "captain", "kind", "npc", "data", npcDefinition("captain", "Grove captain", "grove")),
                Map.of("id", "bystander", "kind", "npc", "data", npcDefinition("bystander", "Traveller",
                        "trailhead"))));
        @SuppressWarnings("unchecked")
        var manifest = (Map<String, Object>) JSON.readValue(files.get("adventure.json"), Map.class);
        @SuppressWarnings("unchecked")
        var listed = new ArrayList<>((List<Object>) manifest.get("files"));
        listed.add(Map.of("path", "npcs.json", "category", "npcs"));
        manifest.put("files", listed);
        manifest.put("id", "test:npc-world");
        manifest.put("version", "1.0.0");
        manifest.put("dependencies", List.of());
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        return files;
    }

    /** Data of one authored public NPC at a location. */
    private static Map<String, Object> npcDefinition(String id, String role, String location) {
        return new LinkedHashMap<>(Map.of("name", "NPC " + id, "role", role, "location", location));
    }

    /** Replace one definition file with exactly these entries. */
    private static void put(Map<String, byte[]> files, String path, List<Map<String, Object>> definitions) {
        files.put(path, JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions", definitions)));
    }

    /** Register the variant through real ZIP staging and sealing. */
    private PackRegistry.Registration register(Map<String, byte[]> files) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.setLevel(0);
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(output.toByteArray())));
    }
}
