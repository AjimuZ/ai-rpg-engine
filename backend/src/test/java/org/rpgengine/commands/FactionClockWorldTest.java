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
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.knowledge.KnowledgeView;
import org.rpgengine.preparation.QueryEpochs;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.world.FactionClocks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Faction clocks on real PostgreSQL (P15.2-B), through the production admission, resolver and canonical commit. A
 * sealed variant of the exploration fixture gives a {@code wardens} faction three twelve-segment clocks that tick every
 * 15 in-world minutes and an {@code alarm} clock advanced by the survey's ending and by the {@code patrol} clock's
 * completion; a {@code muster} quest becomes available once the alarm is full. The suite proves that each change is one
 * immutable row naming its cause, that a four-hour wait records at most 32 changes and leaves the rest due for the next
 * command in due order, that a completion cascades with its own cause, that the quest engine reads the recorded values
 * from the next command on, that reads and real time change nothing, and that the database refuses a forged change.
 */
@SpringBootTest
class FactionClockWorldTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> TABLES = List.of("campaign", "campaign_event", "quest_state",
            "faction_clock_change", "query_epoch");
    @Autowired private CommandExecutionService execution;
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

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    FactionClockWorldTest() { }

    /** Create the owner and adventure every case registers its pack variant into. */
    @BeforeEach
    void prepare() {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Clocks", "clocks", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
    }

    /** Remove disposable test rows, preserving production constraints and triggers. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, faction_clock_change, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, relationship_change, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
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
     * The survey's ending advances the alarm in its own commit, citing the ending event. A four-hour wait then has 36
     * time ticks and one cascade due; it records the oldest 32, the next command records the other 5 (the patrol's
     * completion fills the alarm at the same due time), and the command after that discovers the muster quest.
     */
    @Test
    void dueCausesAreRecordedInOrderBoundedPerCommit() throws Exception {
        start(true);
        assertEquals(0L, jdbc.queryForObject("select world_time_seconds from campaign where id = ?", Long.class,
                campaign));
        run(new TypedCommand.AcceptQuest("survey"));
        assertEquals(List.of(), changes());

        run(new TypedCommand.Move("grove"));
        long ended = revision();
        var opened = only(changes());
        assertEquals(List.of(ended, 0, "wardens", "alarm", "QUEST_ENDED", 0L, "survey", "", "", 0, 1, 2, false, 0L),
                opened.subList(0, 14));
        assertEquals(List.of("QUEST_COMPLETED", ended), event((Long) opened.get(14)));
        assertEquals(1L, epoch(FactionClocks.SCOPE));
        assertEquals(1L, epoch("clock:wardens:alarm"));

        // Reads and real time change nothing a cause could depend on.
        var before = snapshot();
        for (int round = 0; round < 3; round++) {
            view.forActor(campaign, player);
            view.privileged(campaign);
            Thread.sleep(100);
        }
        assertEquals(before, snapshot());

        run(new TypedCommand.Wait(4 * 3_600));
        long waited = revision();
        var bounded = changes().subList(1, changes().size());
        assertEquals(FactionClocks.MAX_CHANGES, bounded.size());
        for (int index = 0; index < bounded.size(); index++) {
            var change = bounded.get(index);
            // Ordinals follow due time, then clock identity; every row is a time tick of this commit.
            assertEquals(List.of(waited, index, "WORLD_TIME", 14_400L), List.of(change.get(0), change.get(1),
                    change.get(4), change.get(13)), change.toString());
            long due = 900L * (index / 3 + 1);
            assertEquals(due, change.get(5), change.toString());
            assertEquals(List.of("drill", "patrol", "watch").get(index % 3), change.get(3), change.toString());
        }
        assertEquals("muster", undiscovered());

        // The next command, with only one minute passing, first records the 5 changes that were still due.
        run(new TypedCommand.Wait(60));
        long caught = revision();
        var rest = changes().subList(33, changes().size());
        assertEquals(List.of(
                List.of(caught, 0, "watch", "WORLD_TIME", 9_900L, 10, 11, false),
                List.of(caught, 1, "drill", "WORLD_TIME", 10_800L, 11, 12, true),
                List.of(caught, 2, "patrol", "WORLD_TIME", 10_800L, 11, 12, true),
                List.of(caught, 3, "watch", "WORLD_TIME", 10_800L, 11, 12, true),
                List.of(caught, 4, "alarm", "CLOCK_COMPLETED", 10_800L, 1, 2, true)),
                rest.stream().map(change -> List.of(change.get(0), change.get(1), change.get(3), change.get(4),
                        change.get(5), change.get(9), change.get(10), change.get(12))).toList());
        assertEquals(List.of("wardens", "patrol"), rest.getLast().subList(7, 9));
        // That command's quests read the alarm as it stood before it (1), so the muster is still undiscovered.
        assertEquals("muster", undiscovered());

        // The following command reads the full alarm and discovers the muster; nothing more is due.
        run(new TypedCommand.Wait(60));
        assertEquals(38, changes().size());
        assertEquals("available", jdbc.queryForObject("select status from quest_state where campaign_id = ? "
                + "and quest_id = 'muster'", String.class, campaign));
        assertEquals(3L, epoch(FactionClocks.SCOPE));

        assertThrows(DataAccessException.class, () -> jdbc.update("update faction_clock_change set value_after = 1 "
                + "where campaign_id = ?", campaign));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from faction_clock_change where campaign_id = ?",
                campaign));
        // A forged change that does not continue its clock, or that belongs to an earlier revision, is refused by the
        // provenance trigger; both rows would otherwise satisfy every check and unique index.
        long now = jdbc.queryForObject("select world_time_seconds from campaign where id = ?", Long.class, campaign);
        assertThrows(DataAccessException.class, () -> forge(revision(), 5, now));
        assertThrows(DataAccessException.class, () -> forge(ended, 0, 0));
        assertEquals(38, changes().size());
    }

    /** The same commands on the fixture without clocks write no clock row and bump no clock scope. */
    @Test
    void campaignsWithoutClocksWriteNothing() throws Exception {
        start(false);
        run(new TypedCommand.AcceptQuest("survey"));
        run(new TypedCommand.Move("grove"));
        run(new TypedCommand.Wait(4 * 3_600));
        assertEquals(0, jdbc.queryForObject("select count(*) from faction_clock_change", Integer.class));
        assertEquals(0L, epoch(FactionClocks.SCOPE));
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Register a variant and create the campaign through production creation. */
    private void start(boolean clocks) throws Exception {
        UUID revision = register(clocks ? pack(fixture()) : fixture()).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(),
                revision, "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(),
                Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
    }

    /** The single element of a list. */
    private static <T> T only(List<T> list) {
        assertEquals(1, list.size(), list.toString());
        return list.getFirst();
    }

    /**
     * Every clock change of this campaign in commit order: revision, ordinal, faction, clock, cause, due time, cause
     * quest, cause faction, cause clock (empty when absent), before, after, target, completed, the commit's world time
     * and the cited event sequence (0 when none).
     */
    private List<List<Object>> changes() {
        return jdbc.query("select changed_revision, ordinal, faction_id, clock_id, cause, due_world_time, "
                + "coalesce(cause_quest_id, ''), coalesce(cause_faction_id, ''), coalesce(cause_clock_id, ''), "
                + "value_before, value_after, target, completed, changed_world_time, coalesce(cause_event_sequence, 0) "
                + "from faction_clock_change where campaign_id = ? order by changed_revision, ordinal",
                (row, index) -> List.<Object>of(row.getLong(1), row.getInt(2), row.getString(3), row.getString(4),
                        row.getString(5), row.getLong(6), row.getString(7), row.getString(8), row.getString(9),
                        row.getInt(10), row.getInt(11), row.getInt(12), row.getBoolean(13), row.getLong(14),
                        row.getLong(15)), campaign);
    }

    /** Insert a hand-made time tick of an unmoved clock ('siege', 12 segments); the database must refuse it. */
    private void forge(long revision, int before, long worldTime) {
        jdbc.update("insert into faction_clock_change (campaign_id, changed_revision, ordinal, faction_id, clock_id, "
                + "cause, due_world_time, value_before, value_after, target, completed, changed_world_time) "
                + "values (?, ?, 31, 'wardens', 'siege', 'WORLD_TIME', 0, ?, ?, 12, false, ?)", campaign, revision,
                before, before + 1, worldTime);
    }

    /** The muster quest's identity while it has no stored row (it is still undiscovered). */
    private String undiscovered() {
        assertEquals(0, jdbc.queryForObject("select count(*) from quest_state where campaign_id = ? "
                + "and quest_id = 'muster'", Integer.class, campaign));
        return "muster";
    }

    /** Type and revision of the event at a sequence. */
    private List<Object> event(long sequence) {
        return jdbc.queryForObject("select event_type, state_revision from campaign_event where campaign_id = ? "
                + "and sequence = ?", (row, index) -> List.<Object>of(row.getString(1), row.getLong(2)), campaign,
                sequence);
    }

    /** Run a registered player intention end to end at the current revision and require its commit. */
    private void run(TypedCommand command) {
        var result = execution.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision(), command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, result.operation().status(), command.toString());
    }

    /** Current committed state revision. */
    private long revision() {
        return jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign);
    }

    /** Current epoch of a query scope. */
    private long epoch(String scope) {
        return QueryEpochs.current(jdbc, campaign, scope);
    }

    /** Exact committed rows of every table a clock cause could read or write. */
    private Map<String, String> snapshot() {
        var snapshot = new LinkedHashMap<String, String>();
        for (String table : TABLES) {
            snapshot.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return snapshot;
    }

    /** Files of the packaged exploration fixture. */
    private Map<String, byte[]> fixture() throws Exception {
        return new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI())).files());
    }

    /**
     * Fixture variant with a {@code wardens} faction: {@code drill}, {@code patrol} and {@code watch} fill one of 12
     * segments every 15 minutes from the campaign's start; {@code alarm} (2 segments) gains one when the survey ends
     * surveyed and one when the patrol completes; the {@code muster} quest is discovered once the alarm is full.
     */
    private static Map<String, byte[]> pack(Map<String, byte[]> fixture) {
        var files = new TreeMap<>(fixture);
        var ticking = Map.of("segments", 12, "every", Map.of("minutes", 15));
        var clocks = new ArrayList<Object>();
        for (String id : List.of("drill", "patrol", "watch")) {
            var clock = new LinkedHashMap<String, Object>(ticking);
            clock.put("id", id);
            clocks.add(clock);
        }
        clocks.add(Map.of("id", "alarm", "segments", 2, "causes", List.of(
                Map.of("quest", "survey", "outcome", "surveyed"),
                Map.of("clock", Map.of("faction", "wardens", "id", "patrol")))));
        files.put("factions.json", JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions", List.of(
                Map.of("id", "wardens", "kind", "faction", "data", Map.of("name", "Wardens",
                        "description", "Keepers of the trail.", "clocks", clocks))))));
        @SuppressWarnings("unchecked")
        var quests = (Map<String, Object>) JSON.readValue(files.get("quests.json"), Map.class);
        @SuppressWarnings("unchecked")
        var definitions = new ArrayList<>((List<Object>) quests.get("definitions"));
        definitions.add(Map.of("id", "muster", "kind", "quest", "data", Map.of("name", "Answer the Muster",
                "summary", "Report to the wardens.", "initialStatus", "undiscovered",
                "availableWhen", Map.of("ge", List.of(Map.of("clock", Map.of("faction", "wardens", "id", "alarm")), 2)),
                "objectives", List.of(Map.of("id", "report", "description", "Return to the trailhead.",
                        "when", Map.of("visited", "trailhead"))),
                "outcomes", List.of(Map.of("id", "reported", "kind", "success",
                        "when", Map.of("objective", Map.of("quest", "muster", "id", "report")))))));
        quests.put("definitions", definitions);
        files.put("quests.json", JSON.writeValueAsBytes(quests));
        @SuppressWarnings("unchecked")
        var manifest = (Map<String, Object>) JSON.readValue(files.get("adventure.json"), Map.class);
        @SuppressWarnings("unchecked")
        var listed = new ArrayList<>((List<Object>) manifest.get("files"));
        listed.add(Map.of("path", "factions.json", "category", "factions"));
        manifest.put("files", listed);
        manifest.put("id", "test:clocks");
        manifest.put("version", "1.0.0");
        manifest.put("dependencies", List.of());
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        return files;
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
