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
import org.rpgengine.content.PackException;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.knowledge.KnowledgeView;
import org.rpgengine.preparation.QueryEpochs;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.world.Relationships;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Relationship causes and companions on real PostgreSQL (P15.1-B), through the production admission, resolver and
 * canonical commit. A sealed pack enables the {@code relationships} module and gives a guide a trust dimension that
 * rises when the player speaks to it, with a companion threshold; the guide's schedule keeps it at the trailhead. The
 * suite proves that every change is one immutable row citing the event of its own revision, that a clamped no-op
 * records nothing, that the change crossing the threshold is marked JOINED, that a companion follows the player's
 * move instead of its schedule, and that reads change nothing. The same pack without the module stores no
 * relationship row, moves no companion and refuses the relationship field outright.
 */
@SpringBootTest
class RelationshipWorldTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> TABLES = List.of("campaign", "actor", "campaign_event", "relationship_change",
            "query_epoch");
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
    private UUID guide;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    RelationshipWorldTest() { }

    /** Create the owner and adventure every case registers its pack variant into. */
    @BeforeEach
    void prepare() {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Bonds", "bonds", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
    }

    /** Remove disposable test rows, preserving production constraints and triggers. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, relationship_change, faction_clock_change, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
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
     * Speaking to the guide raises its trust 0, 2, 4 (JOINED), 5 (clamped) and then not at all; each change cites
     * the SPEECH_DELIVERED event of its own revision. As a companion the guide walks to the grove with the player and
     * stays there although its schedule says trailhead; the rows cannot be edited or removed.
     */
    @Test
    void causedChangesAreRecordedAndCompanionsFollowThePlayer() throws Exception {
        start(true);
        // The first command lets the player perceive the guide; nothing names it yet, so nothing changes.
        run(new TypedCommand.Wait(60));
        assertEquals(List.of(), changes());

        run(new TypedCommand.Speak(guide, "Good morning"));
        long first = revision();
        var opened = only(changes());
        assertEquals(List.of(first, 0L, "SPOKEN_TO", 2, 0, 2, "", "trust"), opened.subList(0, 8));
        assertEquals(List.of("SPEECH_DELIVERED", first), cause((Long) opened.get(8)));
        assertEquals(1L, epoch(Relationships.scope(guide)));

        // Reads and real time change nothing a cause could depend on.
        var before = snapshot();
        for (int round = 0; round < 3; round++) {
            view.forActor(campaign, player);
            view.forActor(campaign, guide);
            view.privileged(campaign);
            Thread.sleep(100);
        }
        assertEquals(before, snapshot());

        run(new TypedCommand.Speak(guide, "Will you come along?"));
        var joined = changes().get(1);
        assertEquals(List.of(revision(), 0L, "SPOKEN_TO", 2, 2, 4, "JOINED", "trust"), joined.subList(0, 8));
        assertEquals(List.of("SPEECH_DELIVERED", revision()), cause((Long) joined.get(8)));

        // The companion stood where the move began, so it arrives with the player in the same commit.
        long version = rowVersion(guide);
        long actorEpoch = epoch("actor:" + guide);
        run(new TypedCommand.Move("grove"));
        assertEquals("grove", location(player));
        assertEquals("grove", location(guide));
        assertEquals(version + 1, rowVersion(guide));
        assertEquals(actorEpoch + 1, epoch("actor:" + guide));
        // Its schedule (trailhead all day) is suspended while it is a companion.
        run(new TypedCommand.Wait(60));
        assertEquals("grove", location(guide));

        // 4 + 2 clamps to the maximum 5; a further cause that cannot move the value records nothing.
        run(new TypedCommand.Speak(guide, "Thank you"));
        assertEquals(List.of(revision(), 0L, "SPOKEN_TO", 2, 4, 5, "", "trust"), changes().get(2).subList(0, 8));
        run(new TypedCommand.Speak(guide, "Again"));
        assertEquals(3, changes().size());
        assertEquals(3L, epoch(Relationships.scope(guide)));

        assertThrows(DataAccessException.class, () -> jdbc.update("update relationship_change set value_after = 1 "
                + "where campaign_id = ?", campaign));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from relationship_change where campaign_id = ?",
                campaign));
    }

    /**
     * The module disabled: the relationship field is refused at registration, and the same world without it runs the
     * same commands with no relationship row, no relationship scope and the guide kept on its schedule.
     */
    @Test
    void disabledModuleLeavesNoTrace() throws Exception {
        var refused = assertThrows(PackException.class, () -> register(pack(fixture(), false, true)));
        assertEquals("MODULE_DISABLED", refused.code());

        start(false);
        run(new TypedCommand.Wait(60));
        run(new TypedCommand.Speak(guide, "Good morning"));
        run(new TypedCommand.Speak(guide, "Will you come along?"));
        run(new TypedCommand.Move("grove"));
        assertEquals("grove", location(player));
        assertEquals("trailhead", location(guide));
        assertEquals(0, jdbc.queryForObject("select count(*) from relationship_change", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from query_epoch where campaign_id = ? "
                + "and scope_key like 'relationships:%'", Integer.class, campaign));
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Register a variant, create the campaign through production creation and place the guide at the trailhead. */
    private void start(boolean enabled) throws Exception {
        UUID revision = register(pack(fixture(), enabled, enabled)).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(),
                revision, "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(),
                Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        guide = new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, "guide", "Guide", "trailhead", 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
    }

    /** The single element of a list. */
    private static <T> T only(List<T> list) {
        assertEquals(1, list.size(), list.toString());
        return list.getFirst();
    }

    /**
     * Every change of this campaign in commit order: revision, ordinal, cause, delta, before, after, membership (empty
     * when none), dimension and the cited event sequence.
     */
    private List<List<Object>> changes() {
        return jdbc.query("select changed_revision, ordinal, cause, delta, value_before, value_after, "
                + "coalesce(membership, ''), dimension_id, cause_event_sequence from relationship_change "
                + "where campaign_id = ? and holder_actor_id = ? and subject_actor_id = ? "
                + "order by changed_revision, ordinal", (row, index) -> List.<Object>of(row.getLong(1),
                        (long) row.getInt(2), row.getString(3), row.getInt(4), row.getInt(5), row.getInt(6),
                        row.getString(7), row.getString(8), row.getLong(9)), campaign, guide, player);
    }

    /** Type and revision of the event at a sequence. */
    private List<Object> cause(long sequence) {
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

    /** Exact committed rows of every table a cause or a companion move could touch. */
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
     * Fixture variant with one guide NPC whose schedule keeps it at the trailhead all day.
     *
     * @param module whether the manifest enables the relationships module
     * @param rules whether the guide declares relationship rules: trust -5..5 from 0, +2 when spoken to, companion
     *        from 4
     */
    private static Map<String, byte[]> pack(Map<String, byte[]> fixture, boolean module, boolean rules) {
        var files = new TreeMap<>(fixture);
        var guide = new LinkedHashMap<String, Object>(Map.of("name", "NPC guide", "role", "Trail guide",
                "location", "trailhead"));
        guide.put("schedule", List.of(Map.of("from", "00:00", "location", "trailhead")));
        if (rules) {
            guide.put("relationship", Map.of(
                    "dimensions", List.of(Map.of("id", "trust", "min", -5, "max", 5, "initial", 0)),
                    "causes", List.of(Map.of("on", "spoken-to", "dimension", "trust", "delta", 2)),
                    "companion", Map.of("dimension", "trust", "atLeast", 4)));
        }
        files.put("npcs.json", JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions",
                List.of(Map.of("id", "guide", "kind", "npc", "data", guide)))));
        @SuppressWarnings("unchecked")
        var manifest = (Map<String, Object>) JSON.readValue(files.get("adventure.json"), Map.class);
        @SuppressWarnings("unchecked")
        var listed = new ArrayList<>((List<Object>) manifest.get("files"));
        listed.add(Map.of("path", "npcs.json", "category", "npcs"));
        manifest.put("files", listed);
        manifest.put("id", module ? "test:bonds" : "test:bonds-off");
        manifest.put("version", "1.0.0");
        manifest.put("dependencies", List.of());
        manifest.put("modules", module ? List.of(Map.of("id", "relationships")) : List.of());
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
