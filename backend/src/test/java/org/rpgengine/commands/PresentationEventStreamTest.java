package org.rpgengine.commands;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.PresentationEventStore;
import org.rpgengine.presentation.PresentationEvents;
import org.rpgengine.presentation.PresentationEvents.Draft;
import org.rpgengine.presentation.PublicPresentationScope;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of durable presentation events on the public stream (P05.7-B.1.1): only what
 * the admission gate accepts is stored, the V26 trigger announces a stored cue exactly once through
 * the shared head and retention, and skipping, reconnecting, replaying, repeating an insert or
 * resetting a cursor never produces a second cue for one delivery identity.
 */
@SpringBootTest
class PresentationEventStreamTest {
    private static final String TYPE = PublicStreamService.PRESENTATION;
    /** Cursor of a campaign holding only the three announcements of the committed operation. */
    private static final String AFTER_COMMIT = "0:3";

    @Autowired private PublicStreamService stream;
    @Autowired private PresentationEventStore store;
    @Autowired private CommandExecutionService executions;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private UUID guide;
    private UUID hidden;
    private UUID operation;
    private PublicPresentationScope scope;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    PresentationEventStreamTest() { }

    /** Commit one owner, a content-free campaign with its player and guide, and one committed WAIT. */
    @BeforeEach
    void prepare() {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Instant now = Instant.now();
            var profile = OwnerProfile.create("Owner", now);
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Stream", "stream", SupportedLocale.EN, now);
            entities.persist(workspace);
            adventure = workspace.getId();
            var content = ContentRevision.admit(adventure, new ContentRevision.PackCoordinates("neutral", "1",
                    "a".repeat(64), 1, SupportedLocale.EN), new ContentRevision.RulesetCoordinates("core", "1",
                    "b".repeat(64)), now);
            entities.persist(content);
            var started = Campaign.start(adventure, content, "Campaign", "seed", now);
            entities.persist(started);
            campaign = started.getId();
            var player = Actor.create(campaign, ActorKind.CHARACTER, null, "Player", "camp", 0, now);
            entities.persist(player);
            var listener = Actor.create(campaign, ActorKind.NPC, null, "Guide", "camp", 0, now);
            entities.persist(listener);
            guide = listener.getId();
            var unseen = Actor.create(campaign, ActorKind.NPC, null, "Unseen", "cellar", 0, now);
            entities.persist(unseen);
            hidden = unseen.getId();
            entities.flush();
            jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, "
                    + "profile_id, choices, created_at) values (?, ?, ?, ?, ?, 'neutral', '{}'::jsonb, now())",
                    adventure, UUID.randomUUID(), "a".repeat(64), campaign, player.getId());
        });
        operation = commit(0);
        scope = new PublicPresentationScope(Set.of("harbour", "hall"),
                Map.of(guide, new PublicPresentationScope.ActorLooks(Set.of("leaning"), Set.of("smile", "worried"))),
                Set.of("line-1"), Set.of("bg-harbour", "audio-1"));
    }

    /** Remove disposable rows; constraints and triggers remain installed. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, "
                + "campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, "
                + "plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, "
                + "planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, "
                + "canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, operation_journal, "
                + "actor_resource, actor, presentation_campaign_settings, campaign, content_revision_report, "
                + "content_revision_file, content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * The three kinds arrive in input order through the ordinary read, after the operation's own
     * announcements, in the same id space and with exactly the allowlisted fields; reads change nothing.
     */
    @Test
    void admittedEventsReachTheStreamWithOnlyAllowlistedFields() {
        assertEquals(3, store.record(campaign, cues(operation, 1), scope));
        var before = rows();

        var all = stream.read(owner, adventure, campaign, "0:0").messages();
        assertEquals(List.of("0:1", "0:2", "0:3", "0:4", "0:5", "0:6"),
                all.stream().map(PublicStreamService.Message::id).toList());
        assertEquals(List.of("operation.accepted", "operation.progress", "state.committed", TYPE, TYPE, TYPE),
                all.stream().map(PublicStreamService.Message::type).toList());

        var envelope = List.of("campaignId", "operationId", "commandType", "status", "operationVersion",
                "stateRevision", "deliveryId", "kind", "ordinal");
        var scene = all.get(3).data();
        assertEquals(concat(envelope, "sceneId", "assetId"), List.copyOf(scene.keySet()));
        assertEquals(operation.toString(), scene.get("operationId"));
        assertEquals("WAIT", scene.get("commandType"));
        assertEquals(1L, scene.get("stateRevision"));
        assertEquals("0:4:" + operation + ":0", scene.get("deliveryId"));
        assertEquals("SCENE|0|harbour|bg-harbour", scene.get("kind") + "|" + scene.get("ordinal") + "|"
                + scene.get("sceneId") + "|" + scene.get("assetId"));
        var pose = all.get(4).data();
        assertEquals(concat(envelope, "actorId", "poseId", "expressionId"), List.copyOf(pose.keySet()));
        assertEquals(guide.toString(), pose.get("actorId"));
        var audio = all.get(5).data();
        assertEquals(concat(envelope, "actorId", "lineId", "assetId"), List.copyOf(audio.keySet()));
        assertEquals("line-1|audio-1", audio.get("lineId") + "|" + audio.get("assetId"));

        assertEquals(6L, head());
        assertEquals(before, rows());
    }

    /** A refused draft stops its whole batch before storage: no row, no message, no sequence, no delivery. */
    @Test
    void rejectedDraftsAreNeverStoredOrDelivered() {
        var good = new Draft("SCENE", operation, 1, 0, "harbour", null, null, null, null, null);
        var refused = Map.of(
                PresentationEvents.Reason.RAW_URL, new Draft("SCENE", operation, 1, 1, "https://example.test/x", null, null,
                        null, null, null),
                PresentationEvents.Reason.EXECUTABLE, new Draft("SCENE", operation, 1, 1, "<script>", null, null, null,
                        null, null),
                PresentationEvents.Reason.NOT_ALLOWLISTED, new Draft("SCENE", operation, 1, 1, "cellar", null, null, null,
                        null, null),
                PresentationEvents.Reason.HIDDEN_ACTOR, new Draft("POSE", operation, 1, 1, null, hidden.toString(), null,
                        "smile", null, null),
                PresentationEvents.Reason.MALFORMED, new Draft("script", operation, 1, 1, "harbour", null, null, null, null,
                        null));
        var before = rows();
        refused.forEach((reason, draft) -> {
            var rejection = assertThrows(PresentationEvents.Rejection.class,
                    () -> store.record(campaign, List.of(good, draft), scope), reason.name());
            assertEquals(reason, rejection.reason());
        });
        assertEquals(before, rows());
        assertEquals(0, jdbc.queryForObject("select count(*) from presentation_event", Integer.class));
        assertEquals(3L, head());
        assertEquals(List.of(), stream.read(owner, adventure, campaign, AFTER_COMMIT).messages());
        assertEquals(0, store.record(campaign, List.of(), scope));
        assertEquals(before, rows());
    }

    /**
     * Repeating an insert adds no message and no sequence, keeps the first content, and adds only
     * ordinals not seen before. Storage itself refuses a second message for a cue and any rewrite.
     */
    @Test
    void repeatedInsertsAddNoMessageAndKeepTheHead() {
        assertEquals(2, store.record(campaign, cues(operation, 1).subList(0, 2), scope));
        var before = rows();
        var first = stream.read(owner, adventure, campaign, AFTER_COMMIT).messages();
        assertEquals(5L, head());

        assertEquals(0, store.record(campaign, cues(operation, 1).subList(0, 2), scope));
        var changed = new Draft("SCENE", operation, 1, 0, "hall", null, null, null, null, null);
        assertEquals(0, store.record(campaign, List.of(changed), scope));
        assertEquals(before, rows());
        assertEquals(first, stream.read(owner, adventure, campaign, AFTER_COMMIT).messages());

        assertEquals(1, store.record(campaign, cues(operation, 1), scope));
        assertEquals(6L, head());
        assertEquals(List.of("0:6"), stream.read(owner, adventure, campaign, "0:5").messages().stream()
                .map(PublicStreamService.Message::id).toList());

        var duplicateMessage = "insert into campaign_stream_message (campaign_id, sequence, campaign_generation, operation_id, "
                + "operation_version, message_type, status, state_revision, created_at, presentation_ordinal) "
                + "values (?, 7, 0, ?, 3, ?, 'COMMITTED', 1, now(), ?)";
        assertThrows(DataAccessException.class, () -> jdbc.update(duplicateMessage, campaign, operation, TYPE, 0));
        assertThrows(DataAccessException.class, () -> jdbc.update(duplicateMessage, campaign, operation, TYPE, 9));
        assertThrows(DataAccessException.class, () -> jdbc.update(duplicateMessage, campaign, operation, TYPE, null));
        assertThrows(DataAccessException.class, () -> jdbc.update(duplicateMessage, campaign, operation, "state.committed", 0));
        assertThrows(DataAccessException.class, () -> jdbc.update("update presentation_event set scene_id = 'hall'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from presentation_event"));
    }

    /** Two concurrent writers of the same cues produce one message per cue. */
    @Test
    void concurrentRepeatsAnnounceEachCueOnce() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writers = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                writers.add(pool.submit(() -> store.record(campaign, cues(operation, 1), scope)));
            }
            int stored = 0;
            for (var writer : writers) {
                stored += writer.get();
            }
            assertEquals(3, stored);
        }
        assertEquals(6L, head());
        assertEquals(3, stream.read(owner, adventure, campaign, AFTER_COMMIT).messages().size());
    }

    /**
     * A client resuming from any identity, reconnecting at the last one or replaying from the start
     * sees the same identities and content, and collecting by delivery identity yields each cue once.
     */
    @Test
    void skipReconnectAndReplayNeverDeliverACueTwice() {
        store.record(campaign, cues(operation, 1), scope);
        var delivered = new LinkedHashSet<String>();
        var complete = stream.read(owner, adventure, campaign, AFTER_COMMIT).messages();
        assertEquals(3, complete.size());
        complete.forEach(message -> delivered.add(deliveryId(message)));

        // Reconnect at the last identity: nothing to deliver.
        assertEquals(List.of(), stream.read(owner, adventure, campaign, "0:6").messages());
        // Skip: resuming after the second cue returns only the third, rendered identically.
        assertEquals(List.of(complete.get(2)), stream.read(owner, adventure, campaign, "0:5").messages());
        // Replay from the start repeats the same frames, which the delivery identity recognizes.
        var replay = stream.read(owner, adventure, campaign, "0:0").messages().stream()
                .filter(message -> message.type().equals(TYPE)).toList();
        assertEquals(complete, replay);
        replay.forEach(message -> assertFalse(delivered.add(deliveryId(message)), message.id()));
        assertEquals(3, delivered.size());

        // A later cue of a new operation continues the same id space after the operation's own messages.
        UUID next = commit(1);
        assertEquals(1, store.record(campaign, List.of(new Draft("SCENE", next, 2, 0, "hall", null, null, null, null,
                null)), scope));
        var after = stream.read(owner, adventure, campaign, "0:6").messages();
        assertEquals(List.of("operation.accepted", "operation.progress", "state.committed", TYPE),
                after.stream().map(PublicStreamService.Message::type).toList());
        assertTrue(delivered.add(deliveryId(after.getLast())));
        assertEquals(List.of(), stream.read(owner, adventure, campaign, after.getLast().id()).messages());
    }

    /**
     * An unknown cursor, another generation and an expired cursor get a reset instead of cues. A cue
     * that left the retained window stays recorded, is never re-announced, and blocks no retention.
     */
    @Test
    void resetsAndRetentionWorkWithCues() {
        store.record(campaign, cues(operation, 1), scope);
        var unknown = stream.read(owner, adventure, campaign, "0:7").messages();
        assertEquals("stream.reset|0:6|CURSOR_UNKNOWN", unknown.getFirst().type() + "|" + unknown.getFirst().id() + "|"
                + unknown.getFirst().data().get("reason"));
        assertEquals("stream.ready|0:6", stream.read(owner, adventure, campaign, null).messages().getFirst().type() + "|"
                + stream.read(owner, adventure, campaign, null).messages().getFirst().id());

        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1 "
                + "where id = ?", campaign);
        var restored = stream.read(owner, adventure, campaign, "0:6").messages();
        assertEquals("stream.reset|1:6|GENERATION_CHANGED", restored.getFirst().type() + "|" + restored.getFirst().id()
                + "|" + restored.getFirst().data().get("reason"));
        assertEquals(1, restored.size());
        assertEquals(List.of(), stream.read(owner, adventure, campaign, "1:6").messages());
        // A repeat after the restore announces nothing under the new generation.
        assertEquals(0, store.record(campaign, cues(operation, 1), scope));
        assertEquals(6L, head());
        // A resume inside the new generation still renders the stored identity of an earlier cue.
        assertEquals("0:6:" + operation + ":2", deliveryId(stream.read(owner, adventure, campaign, "1:5").messages().getFirst()));

        jdbc.update("insert into operation_journal (id, campaign_id, idempotency_key, payload_hash, command_type, "
                + "base_revision, status, created_at, updated_at) select gen_random_uuid(), ?, 'bulk-' || n, ?, 'WAIT', "
                + "1, 'REJECTED', now(), now() from generate_series(1, 1001) n", campaign, "d".repeat(64));
        assertEquals(1007L, head());
        assertEquals(PublicStreamService.RETAINED, jdbc.queryForObject("select count(*) from campaign_stream_message "
                + "where campaign_id = ?", Integer.class, campaign));
        assertEquals(0, jdbc.queryForObject("select count(*) from campaign_stream_message where message_type = ?",
                Integer.class, TYPE));
        assertEquals(3, jdbc.queryForObject("select count(*) from presentation_event", Integer.class));
        var expired = stream.read(owner, adventure, campaign, "1:6").messages();
        assertEquals("stream.reset|1:1007|CURSOR_EXPIRED", expired.getFirst().type() + "|" + expired.getFirst().id() + "|"
                + expired.getFirst().data().get("reason"));

        // An expired cue is not announced again by a repeat, and the head stays where it was.
        assertEquals(0, store.record(campaign, cues(operation, 1), scope));
        assertEquals(1007L, head());
        assertEquals(List.of(), stream.read(owner, adventure, campaign, "1:1007").messages());

        // A cue written at the edge of the window pushes the oldest message out like any other message.
        assertEquals(1, store.record(campaign, List.of(new Draft("SCENE", operation, 1, 3, "hall", null, null, null, null,
                null)), scope));
        assertEquals(PublicStreamService.RETAINED, jdbc.queryForObject("select count(*) from campaign_stream_message "
                + "where campaign_id = ?", Integer.class, campaign));
        var fresh = stream.read(owner, adventure, campaign, "1:1007").messages();
        assertEquals(List.of("1:1008"), fresh.stream().map(PublicStreamService.Message::id).toList());
        assertEquals("1:1008:" + operation + ":3", deliveryId(fresh.getFirst()));
    }

    /**
     * Storage admits cues only for an operation committed at their revision in this campaign, for an
     * actor of this campaign, in bounds and with a reference shape; a failure rolls back its batch.
     */
    @Test
    void storageRefusesAnythingTheEventModelForbids() {
        UUID uncommitted = UUID.randomUUID();
        jdbc.update("insert into operation_journal (id, campaign_id, idempotency_key, payload_hash, command_type, "
                + "base_revision, status, created_at, updated_at) values (?, ?, ?, ?, 'WAIT', 1, 'REJECTED', now(), now())",
                uncommitted, campaign, UUID.randomUUID().toString(), "e".repeat(64));
        var before = rows();
        assertThrows(DataAccessException.class, () -> store.record(campaign, cues(uncommitted, 1), scope));
        assertThrows(DataAccessException.class, () -> store.record(campaign, List.of(new Draft("SCENE", operation, 2, 0,
                "harbour", null, null, null, null, null)), scope), "the operation committed at another revision");
        assertThrows(DataAccessException.class, () -> store.record(UUID.randomUUID(), cues(operation, 1), scope));
        assertThrows(DataAccessException.class, () -> store.record(campaign, List.of(new Draft("SCENE", UUID.randomUUID(),
                1, 0, "harbour", null, null, null, null, null)), scope));
        assertEquals(before, rows());

        // The first cue inserts and the second fails on an actor of no campaign: the batch rolls back whole.
        UUID stranger = UUID.randomUUID();
        var wide = new PublicPresentationScope(Set.of("harbour"), Map.of(stranger,
                new PublicPresentationScope.ActorLooks(Set.of(), Set.of("smile"))), Set.of(), Set.of());
        assertThrows(DataAccessException.class, () -> store.record(campaign, List.of(
                new Draft("SCENE", operation, 1, 0, "harbour", null, null, null, null, null),
                new Draft("POSE", operation, 1, 1, null, stranger.toString(), null, "smile", null, null)), wide));
        assertEquals(before, rows(), "a failed batch stores nothing and allocates no sequence");

        String insert = "insert into presentation_event (campaign_id, operation_id, ordinal, committed_revision, kind, "
                + "scene_id, actor_id, expression_id, created_at) values (?, ?, ?, 1, ?, ?, ?, ?, now())";
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, campaign, operation, 16, "SCENE", "harbour", null, null));
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, campaign, operation, 5, "SCENE", "a/b", null, null));
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, campaign, operation, 5, "SCENE", "harbour", guide, null));
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, campaign, operation, 5, "POSE", null, UUID.randomUUID(), "smile"));
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, campaign, operation, 5, "OTHER", "harbour", null, null));
        assertEquals(before, rows());
    }

    private List<Draft> cues(UUID id, long revision) {
        return List.of(new Draft("SCENE", id, revision, 0, "harbour", null, null, null, null, "bg-harbour"),
                new Draft("POSE", id, revision, 1, null, guide.toString(), "leaning", "smile", null, null),
                new Draft("LINE_AUDIO", id, revision, 2, null, guide.toString(), null, null, "line-1", "audio-1"));
    }

    private UUID commit(long expectedRevision) {
        var executed = executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), expectedRevision, "WAIT", Map.of("seconds", 5)));
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        return executed.operation().operationId();
    }

    private long head() {
        return jdbc.queryForObject("select last_sequence from campaign_stream_head where campaign_id = ?", Long.class, campaign);
    }

    private static String deliveryId(PublicStreamService.Message message) {
        return (String) message.data().get("deliveryId");
    }

    private static List<String> concat(List<String> envelope, String... rest) {
        var all = new ArrayList<>(envelope);
        all.addAll(List.of(rest));
        return all;
    }

    /** Canonical JSON of every table a write here could touch. */
    private Map<String, String> rows() {
        var rows = new java.util.LinkedHashMap<String, String>();
        for (String table : List.of("presentation_event", "campaign_stream_message", "campaign_stream_head", "operation_journal",
                "campaign")) {
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return rows;
    }
}
