package org.rpgengine.commands;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.rpgengine.presentation.PresentationJobService;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of the P07.5 resumable public stream: the journal trigger announces every
 * status change exactly once in its own transaction, identities resume in order, the same identity
 * always renders the same allowlisted content, and cursors from another generation, beyond the
 * head or outside the 1000-message retention window receive a reset instead of a silent gap.
 * Reads change no row and never expose payload text, other actors or internal receipt details.
 */
@SpringBootTest
class PublicStreamTest {
    @Autowired private PublicStreamService stream;
    @Autowired private CommandExecutionService executions;
    @Autowired private PresentationJobService presentation;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private UUID guide;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    PublicStreamTest() { }

    /** Commit one owner, a content-free campaign with its creation player and a co-present guide. */
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
            entities.flush();
            jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, "
                    + "profile_id, choices, created_at) values (?, ?, ?, ?, ?, 'neutral', '{}'::jsonb, now())",
                    adventure, UUID.randomUUID(), "a".repeat(64), campaign, player.getId());
        });
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
     * A committed speech announces accepted, progress and state.committed with the player-safe
     * receipt; a cursor resumes after any identity with identical rendering; presentation appends
     * its own messages; no read writes a row or reveals the words or the recipient.
     */
    @Test
    void announcesEveryStatusChangeAndResumesFromAnyIdentity() {
        var executed = executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), 0, "SPEAK", Map.of("recipientActorId", guide.toString(),
                "text", "Hidden words")));
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        var before = snapshot();
        var ready = stream.read(owner, adventure, campaign, null).messages();
        assertEquals(1, ready.size());
        assertEquals("stream.ready", ready.getFirst().type());
        assertEquals("0:3", ready.getFirst().id());
        assertEquals(Map.of("campaignId", campaign.toString(), "stateRevision", 1L), ready.getFirst().data());

        var all = stream.read(owner, adventure, campaign, "0:0").messages();
        assertEquals(List.of("0:1", "0:2", "0:3"), all.stream().map(PublicStreamService.Message::id).toList());
        assertEquals(List.of("operation.accepted", "operation.progress", "state.committed"),
                all.stream().map(PublicStreamService.Message::type).toList());
        assertEquals(List.of("campaignId", "operationId", "commandType", "status", "operationVersion", "stateRevision"),
                List.copyOf(all.getFirst().data().keySet()));
        assertEquals("ACCEPTED|0|0", all.get(0).data().get("status") + "|" + all.get(0).data().get("operationVersion")
                + "|" + all.get(0).data().get("stateRevision"));
        assertEquals("VALIDATING", all.get(1).data().get("status"));
        assertEquals(executed.receipt(), all.get(2).data().get("receipt"));
        assertEquals(1L, all.get(2).data().get("stateRevision"));
        // At-least-once delivery: the same identity renders identical content on every read.
        assertEquals(List.of(all.get(2)), stream.read(owner, adventure, campaign, "0:2").messages());
        assertEquals(List.of(), stream.read(owner, adventure, campaign, "0:3").messages());
        assertFalse(all.toString().contains("Hidden words") || all.toString().contains(guide.toString())
                || all.toString().contains("details"), all.toString());
        assertEquals(before, snapshot());

        assertEquals(1, presentation.drain(4));
        var presented = stream.read(owner, adventure, campaign, "0:3").messages();
        assertEquals(List.of("operation.progress", "narrative.pending"),
                presented.stream().map(PublicStreamService.Message::type).toList());
        assertEquals("NARRATING", presented.get(0).data().get("status"));
        assertEquals("NARRATION_UNAVAILABLE", presented.get(1).data().get("reason"));
        assertEquals(1L, presented.get(1).data().get("stateRevision"));
    }

    /** Every journal status maps to exactly one message type; an update that keeps the status announces nothing. */
    @Test
    void everyLifecycleStatusMapsToOneMessageType() {
        var expected = new LinkedHashMap<String, String>();
        expected.put("ACCEPTED", "operation.accepted");
        expected.put("PLANNING", "operation.progress");
        expected.put("VALIDATING", "operation.progress");
        expected.put("NEEDS_CLARIFICATION", "operation.clarification");
        expected.put("REJECTED", "operation.rejected");
        expected.put("CANCELLED", "operation.cancelled");
        expected.put("FAILED_BEFORE_COMMIT", "operation.failed");
        expected.put("COMMITTED", "state.committed");
        expected.put("NARRATING", "operation.progress");
        expected.put("DELIVERED", "narrative.ready");
        expected.put("COMMITTED_NARRATION_PENDING", "narrative.pending");
        long revision = 0;
        for (String status : expected.keySet()) {
            boolean committed = OperationStatus.valueOf(status).isCommitted();
            jdbc.update("insert into operation_journal (id, campaign_id, idempotency_key, payload_hash, command_type, "
                    + "base_revision, status, operation_version, committed_revision, receipt, created_at, updated_at) "
                    + "values (?, ?, ?, ?, 'WAIT', 0, ?, 1, ?, ?::jsonb, now(), now())", UUID.randomUUID(), campaign,
                    UUID.randomUUID().toString(), "c".repeat(64), status, committed ? ++revision : null,
                    committed ? "{}" : null);
        }
        jdbc.update("update operation_journal set updated_at = now(), row_version = row_version + 1 where campaign_id = ?",
                campaign);
        assertEquals(List.copyOf(expected.values()), jdbc.queryForList("select message_type from campaign_stream_message "
                + "where campaign_id = ? order by sequence", String.class, campaign));
        assertEquals(List.copyOf(expected.keySet()), jdbc.queryForList("select status from campaign_stream_message "
                + "where campaign_id = ? order by sequence", String.class, campaign));
        assertEquals(11L, jdbc.queryForObject("select last_sequence from campaign_stream_head where campaign_id = ?",
                Long.class, campaign));
        assertThrows(RuntimeException.class, () -> jdbc.update("update campaign_stream_message set status = 'X'"));
    }

    /**
     * Cursors beyond the head, from an earlier recovery generation or older than the retained
     * window are reset to the head; the oldest still resumable cursor continues without a gap.
     */
    @Test
    void resetsCursorsOutsideTheRetainedTimeline() {
        executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), 0, "WAIT", Map.of("seconds", 5)));
        var unknown = stream.read(owner, adventure, campaign, "0:4").messages();
        assertEquals("stream.reset|0:3|CURSOR_UNKNOWN", unknown.getFirst().type() + "|" + unknown.getFirst().id()
                + "|" + unknown.getFirst().data().get("reason"));
        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1 "
                + "where id = ?", campaign);
        var restored = stream.read(owner, adventure, campaign, "0:3").messages();
        assertEquals("stream.reset|1:3|GENERATION_CHANGED", restored.getFirst().type() + "|"
                + restored.getFirst().id() + "|" + restored.getFirst().data().get("reason"));
        assertEquals(List.of(), stream.read(owner, adventure, campaign, "1:3").messages());
        executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), 1, "WAIT", Map.of("seconds", 5)));
        assertEquals(List.of("1:4", "1:5", "1:6"), stream.read(owner, adventure, campaign, "1:3").messages().stream()
                .map(PublicStreamService.Message::id).toList());

        // 1001 further announcements leave exactly the newest 1000 retained.
        jdbc.update("insert into operation_journal (id, campaign_id, idempotency_key, payload_hash, command_type, "
                + "base_revision, status, created_at, updated_at) select gen_random_uuid(), ?, 'bulk-' || n, ?, 'WAIT', "
                + "2, 'REJECTED', now(), now() from generate_series(1, 1001) n", campaign, "d".repeat(64));
        long head = jdbc.queryForObject("select last_sequence from campaign_stream_head where campaign_id = ?",
                Long.class, campaign);
        assertEquals(1007L, head);
        long oldest = jdbc.queryForObject("select min(sequence) from campaign_stream_message where campaign_id = ?",
                Long.class, campaign);
        assertEquals(head - PublicStreamService.RETAINED + 1, oldest);
        assertEquals(PublicStreamService.RETAINED, jdbc.queryForObject("select count(*) from campaign_stream_message "
                + "where campaign_id = ?", Integer.class, campaign));
        var expired = stream.read(owner, adventure, campaign, "1:" + (oldest - 2)).messages();
        assertEquals("stream.reset|1:" + head + "|CURSOR_EXPIRED", expired.getFirst().type() + "|"
                + expired.getFirst().id() + "|" + expired.getFirst().data().get("reason"));
        var resumed = stream.read(owner, adventure, campaign, "1:" + (oldest - 1)).messages();
        assertEquals(PublicStreamService.MAX_BATCH, resumed.size());
        assertEquals("1:" + oldest, resumed.getFirst().id());
        assertEquals("operation.rejected", resumed.getFirst().type());
    }

    /** Malformed cursors fail before storage; foreign scopes are indistinguishable; ambient transactions are refused. */
    @Test
    void rejectsMalformedCursorsForeignScopesAndAmbientTransactions() {
        for (String cursor : List.of("", "1", "01:1", "1:01", "-1:0", "a:b", "1:2:3", " 0:0", "0:0 ",
                "1234567890123456789:0")) {
            assertThrows(CampaignCommandJson.Malformed.class, () -> stream.read(owner, adventure, campaign, cursor),
                    cursor);
        }
        for (UUID[] scope : new UUID[][] {{UUID.randomUUID(), adventure, campaign}, {owner, UUID.randomUUID(), campaign},
                {owner, adventure, UUID.randomUUID()}}) {
            assertEquals(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE, assertThrows(OperationAdmissionFailure.class,
                    () -> stream.read(scope[0], scope[1], scope[2], "0:0")).kind());
        }
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(status ->
                stream.read(owner, adventure, campaign, null)));
    }

    /** Canonical JSON of the world, journal, stream and presentation tables. */
    private Map<String, String> snapshot() {
        var rows = new LinkedHashMap<String, String>();
        for (String table : List.of("campaign", "actor", "campaign_event", "canonical_outbox", "operation_journal",
                "campaign_mutation_lease", "campaign_stream_message", "campaign_stream_head", "presentation_job")) {
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return rows;
    }
}
