package org.rpgengine.narration;

import java.time.Clock;
import java.time.Duration;
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
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationCancellationService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.commands.PublicStreamService;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.inference.RuntimeStatus;
import org.rpgengine.inference.contract.ContractRunner;
import org.rpgengine.inference.contract.NarrationContract;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.inference.scheduling.SchedulerSettings;
import org.rpgengine.inference.scheduling.TaskLedger;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.presentation.PresentationJobService;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of P08.5 through the real presentation job service, context assembler,
 * contract runner, scoped sessions and narration contract, with only the runtime replaced by
 * {@link NarratorTaskPort}: validated narration is published exactly once and only while the
 * campaign's locale and glossary generation are unchanged; invalid output gets one repair and then
 * the deterministic receipt; a declined or unnarratable operation shows the receipt at once; and a
 * narration-only retry narrates again without re-running the action, rolling a draw or writing a
 * world row, receipt or stream message.
 */
@SpringBootTest
class GroundedNarrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private CommandExecutionService executions;
    @Autowired private OperationCancellationService cancellations;
    @Autowired private PublicStreamService stream;
    @Autowired private NarrationContextAssembler assembler;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private TaskLedger ledger;
    private NarratorTaskPort port;
    private PresentationJobService jobs;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private UUID player;
    private UUID mara;
    private long revision;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    GroundedNarrationTest() { }

    /** Commit one owner, a content-free campaign, its creation player, a present NPC and English settings. */
    @BeforeEach
    void prepare() {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Instant now = Instant.now();
            var profile = OwnerProfile.create("Owner", now);
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Narration", "narration", SupportedLocale.EN, now);
            entities.persist(workspace);
            adventure = workspace.getId();
            var content = ContentRevision.admit(adventure, new ContentRevision.PackCoordinates("neutral", "1",
                    "a".repeat(64), 1, SupportedLocale.EN), new ContentRevision.RulesetCoordinates("core", "1",
                    "b".repeat(64)), now);
            entities.persist(content);
            var started = Campaign.start(adventure, content, "Campaign", "seed", now);
            entities.persist(started);
            campaign = started.getId();
            var hero = Actor.create(campaign, ActorKind.CHARACTER, null, "Aria", "camp", 0, now);
            entities.persist(hero);
            player = hero.getId();
            var npc = Actor.create(campaign, ActorKind.NPC, null, "Mara", "camp", 0, now);
            entities.persist(npc);
            mara = npc.getId();
            entities.flush();
            jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, "
                    + "profile_id, choices, created_at) values (?, ?, ?, ?, ?, 'neutral', '{}'::jsonb, now())",
                    adventure, UUID.randomUUID(), "a".repeat(64), campaign, player);
            jdbc.update("insert into presentation_campaign_settings (campaign_id, narrative_locale, updated_at) "
                    + "values (?, 'en', now())", campaign);
        });
        revision = 0;
        port = new NarratorTaskPort();
        // The real fair scheduler over the real ledger: narration is interactive work behind the single permit.
        var scheduler = new InferenceScheduler(new ScopedSessions(port, 16), port, ledger, SchedulerSettings.defaults(),
                Clock.systemUTC(), () -> 0.5);
        scheduler.recover();
        var narration = new GroundedNarration(port, assembler, new ContractRunner(scheduler, Clock.systemUTC()),
                new NarrationContract(), Duration.ofSeconds(20));
        jobs = new PresentationJobService(entities, jdbc, manager, narration);
    }

    /** Remove disposable rows; constraints and triggers remain installed. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, "
                + "campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, "
                + "quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, "
                + "operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, "
                + "campaign_event, operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * Valid narration of a speech is published once with the delivering round: speaker names come
     * from the trusted context, message identities from the publisher, and the journal reaches
     * DELIVERED with one narrative.ready message. The task ran in the public narrator session with
     * the deterministic statements of the public events only, and the world did not move.
     */
    @Test
    void validatedNarrationIsPublishedOnce() {
        UUID operation = commit("SPEAK", Map.of("recipientActorId", mara.toString(), "text", "Hello there"));
        port.then(seen -> NarratorTaskPort.narrated(seen, true));
        var world = world();
        assertEquals(1, jobs.drain(4));
        assertEquals(0, jobs.drain(4));
        assertEquals(1, port.requests.size());
        var seen = NarratorTaskPort.parse(port.requests.getFirst());
        assertEquals("narrator", seen.envelope().path("role").asString());
        assertEquals("public", seen.envelope().path("audience").asString());
        assertEquals("en", seen.envelope().path("outputLocale").asString());
        assertEquals(List.of("event.1"), seen.events());
        assertEquals("Aria says to Mara: \"Hello there\"", seen.context().path("events").get(0).path("text").asString());
        assertEquals(mara.toString(), seen.context().path("speakers").get(0).path("actorId").asString());
        assertEquals(0, seen.context().path("displayNumbers").size());

        assertEquals("DELIVERED|NARRATION_DELIVERED|1", job(operation, "status || '|' || outcome_code || '|' || round"));
        assertEquals("DELIVERED", journal(operation));
        assertEquals(world, world());
        assertEquals(1, count("select count(*) from campaign_stream_message where operation_id = ? "
                + "and message_type = 'narrative.ready'", operation));
        var presentation = jobs.presentation(campaign, operation);
        assertEquals(PresentationJobService.Mode.NARRATED, presentation.mode());
        assertNull(presentation.reason());
        String view = JSON.writeValueAsString(presentation.narration());
        assertEquals("{\"locale\":\"en\",\"repaired\":false,\"passages\":[{\"messageId\":\"" + operation + ":p1\","
                + "\"kind\":\"NARRATION\",\"speaker\":null,\"text\":\"Time passes quietly.\",\"locale\":\"en\"},"
                + "{\"messageId\":\"" + operation + ":p2\",\"kind\":\"CHARACTER_SPEECH\",\"speaker\":{\"actorId\":\""
                + mara + "\",\"name\":\"Mara\"},\"text\":\"Welcome, traveller.\",\"locale\":\"en\"}],"
                + "\"suggestions\":[{\"suggestionId\":\"" + operation + ":s1\",\"kind\":\"QUESTION\","
                + "\"label\":\"Look around\",\"draftText\":\"I look around.\",\"actionId\":null}]}", view);
        assertEquals("scripted|fixture-model|low|false", jdbc.queryForObject("select runtime || '|' || model || '|' "
                + "|| effort || '|' || repaired from narration_publication where operation_id = ?", String.class, operation));
        // A publication is final: the database refuses any rewrite or deletion.
        assertThrows(RuntimeException.class, () -> jdbc.update("update narration_publication set repaired = true "
                + "where operation_id = ?", operation));
        assertThrows(RuntimeException.class, () -> jdbc.update("delete from narration_publication where operation_id = ?",
                operation));
    }

    /**
     * Output that stays ungrounded after its single repair is never published: exactly two task
     * submissions in one job attempt, then the receipt is the presentation with the public reason
     * NARRATION_FAILED and three narration-only retries remaining. A repaired valid output, by
     * contrast, is published and marked repaired.
     */
    @Test
    void invalidOutputGetsOneRepairThenTheReceipt() {
        UUID operation = commitWait();
        port.then(NarratorTaskPort::ungrounded).then(NarratorTaskPort::ungrounded);
        var world = world();
        String receipt = receipt(operation);
        assertEquals(1, jobs.drain(4));
        assertEquals(2, port.requests.size());
        assertTrue(port.requests.get(1).instructions().contains("REPAIR:"));
        assertFalse(port.requests.get(1).instructions().contains("secret door"));
        assertEquals("PENDING|NARRATION_FAILED|1", job(operation, "status || '|' || outcome_code || '|' || attempt_count"));
        assertEquals("COMMITTED_NARRATION_PENDING", journal(operation));
        assertEquals(0, count("select count(*) from narration_publication where operation_id = ?", operation));
        var presentation = jobs.presentation(campaign, operation);
        assertEquals(PresentationJobService.Mode.RECEIPT, presentation.mode());
        assertEquals("NARRATION_FAILED", presentation.reason());
        assertEquals(3, presentation.retriesRemaining());
        assertNull(presentation.narration());
        assertEquals(receipt, receipt(operation));
        assertEquals(world, world());
        var pending = stream.read(owner, adventure, campaign, "0:0").messages().stream()
                .filter(m -> m.type().equals("narrative.pending")).toList();
        assertEquals(1, pending.size());
        assertEquals("NARRATION_FAILED", pending.getFirst().data().get("reason"));

        UUID repaired = commitWait();
        port.then(NarratorTaskPort::ungrounded).then(seen -> NarratorTaskPort.narrated(seen, false));
        assertEquals(1, jobs.drain(4));
        assertEquals("DELIVERED", journal(repaired));
        assertEquals(true, jobs.presentation(campaign, repaired).narration().get("repaired"));
    }

    /**
     * A narrator that declines, and an operation whose public events cannot be stated, both show
     * the receipt at once without retrying inside the round; an unavailable runtime ends the round
     * NARRATION_UNAVAILABLE without reading or submitting anything.
     */
    @Test
    void declinedUnnarratableAndUnavailableFallBackToTheReceipt() {
        UUID declined = commitWait();
        port.then(NarratorTaskPort::declined);
        assertEquals(1, jobs.drain(4));
        assertEquals("PENDING|NARRATION_FAILED|1", job(declined, "status || '|' || outcome_code || '|' || attempt_count"));
        assertEquals(1, port.requests.size());

        UUID unnarratable = commitWait();
        // An unsupported public event type makes the bundle unnarratable: nothing is submitted.
        insertEvent(unnarratable, 900, "PUBLIC", "TELEPORTED", "{\"actorId\":\"" + player + "\"}");
        assertEquals(1, jobs.drain(4));
        assertEquals("PENDING|NARRATION_FAILED", job(unnarratable, "status || '|' || outcome_code"));
        assertEquals(1, port.requests.size());

        UUID unavailable = commitWait();
        port.state(RuntimeStatus.State.UNAVAILABLE);
        assertEquals(1, jobs.drain(4));
        assertEquals("PENDING|NARRATION_UNAVAILABLE", job(unavailable, "status || '|' || outcome_code"));
        assertEquals("NARRATION_UNAVAILABLE", jobs.presentation(campaign, unavailable).reason());
        assertEquals(1, port.requests.size());
    }

    /**
     * Engine-internal events of the narrated operation never reach the narrator: a sentinel in an
     * INTERNAL payload is absent from every instruction, while the public event is stated.
     */
    @Test
    void internalEventsNeverReachTheNarrator() {
        UUID operation = commitWait();
        insertEvent(operation, 901, "INTERNAL", "EFFECT_RESOLVED", "{\"actorId\":\"" + mara
                + "\",\"note\":\"Hidden Vault Sentinel\"}");
        port.then(seen -> NarratorTaskPort.narrated(seen, false));
        assertEquals(1, jobs.drain(4));
        assertEquals(1, port.requests.size());
        String instructions = port.requests.getFirst().instructions();
        assertFalse(instructions.contains("Hidden Vault Sentinel"));
        assertFalse(instructions.contains(mara.toString()));
        assertEquals(List.of("event.1"), NarratorTaskPort.parse(port.requests.getFirst()).events());
        assertEquals("Waiting: 10 seconds pass.", NarratorTaskPort.parse(port.requests.getFirst()).context()
                .path("events").get(0).path("text").asString());
        assertEquals("DELIVERED", journal(operation));
    }

    /**
     * A locale switch while the narrator runs makes its valid English narration stale: settlement
     * discards it and counts a failed attempt, and the next attempt narrates in Italian, which is
     * then published with its passage locale.
     */
    @Test
    void narrationBoundToAnOldLocaleIsNeverPublished() {
        UUID operation = commitWait();
        port.then(seen -> {
            jdbc.update("update presentation_campaign_settings set narrative_locale = 'it', "
                    + "locale_revision = locale_revision + 1, updated_at = now(), row_version = row_version + 1 "
                    + "where campaign_id = ?", campaign);
            return NarratorTaskPort.narrated(seen, false);
        });
        assertEquals(1, jobs.drain(4));
        assertEquals("QUEUED|1", job(operation, "status || '|' || attempt_count"));
        assertEquals("NARRATING", journal(operation));
        assertEquals(0, count("select count(*) from narration_publication where operation_id = ?", operation));
        makeDue(operation);
        port.then(seen -> NarratorTaskPort.narrated(seen, false));
        assertEquals(1, jobs.drain(4));
        assertEquals(2, port.requests.size());
        assertEquals("it", NarratorTaskPort.parse(port.requests.get(1)).envelope().path("outputLocale").asString());
        assertEquals("Attesa: trascorrono 10 secondi.", NarratorTaskPort.parse(port.requests.get(1)).context()
                .path("events").get(0).path("text").asString());
        assertEquals("DELIVERED|2", job(operation, "status || '|' || attempt_count"));
        assertEquals("it|1|Il tempo scorre tranquillo.", jdbc.queryForObject("select passage_locale || '|' "
                + "|| locale_revision || '|' || (passages -> 0 ->> 'text') from narration_publication "
                + "where operation_id = ?", String.class, operation));
    }

    /**
     * Narration-only retry: after the receipt fallback a retry queues one new round, the worker
     * narrates it and the publication appears while the journal keeps its terminal
     * COMMITTED_NARRATION_PENDING status. World rows, receipts, draws and the stream are untouched;
     * equal requests are idempotent while a round is in flight, and a published or never-committed
     * operation cannot be retried.
     */
    @Test
    void narrationOnlyRetryNeverRepeatsTheAction() {
        UUID operation = commitWait();
        port.state(RuntimeStatus.State.UNAVAILABLE);
        assertEquals(1, jobs.drain(4));
        assertEquals("COMMITTED_NARRATION_PENDING", journal(operation));
        var world = world();
        var messages = messages();

        port.state(RuntimeStatus.State.READY);
        assertEquals(PresentationJobService.Retry.QUEUED, jobs.retry(adventure, campaign, operation));
        assertEquals(PresentationJobService.Retry.IN_PROGRESS, jobs.retry(adventure, campaign, operation));
        assertEquals(PresentationJobService.Mode.IN_PROGRESS, jobs.presentation(campaign, operation).mode());
        assertEquals("QUEUED|2|0", jdbc.queryForObject("select status || '|' || round || '|' || attempt_count "
                + "from presentation_job where operation_id = ? and round = 2", String.class, operation));
        port.then(seen -> NarratorTaskPort.narrated(seen, false));
        assertEquals(1, jobs.drain(4));
        assertEquals("DELIVERED", jdbc.queryForObject("select status from presentation_job where operation_id = ? "
                + "and round = 2", String.class, operation));
        assertEquals("COMMITTED_NARRATION_PENDING", journal(operation));
        assertEquals(PresentationJobService.Mode.NARRATED, jobs.presentation(campaign, operation).mode());
        assertEquals(PresentationJobService.Retry.ALREADY_NARRATED, jobs.retry(adventure, campaign, operation));
        assertEquals(world, world());
        assertEquals(messages, messages());
        // The original round's single public message and reason are unchanged by later rounds.
        var pending = stream.read(owner, adventure, campaign, "0:0").messages().stream()
                .filter(m -> m.type().equals("narrative.pending")).toList();
        assertEquals(1, pending.size());
        assertEquals("NARRATION_UNAVAILABLE", pending.getFirst().data().get("reason"));
        assertEquals(PresentationJobService.Retry.ALREADY_NARRATED,
                jobs.retry(adventure, campaign, commitWaitDelivered()));
    }

    /**
     * Retry rounds are bounded: after the original round and three failed retries the request
     * reports EXHAUSTED and writes nothing. Cancelling a queued retry stops it, and the stopped
     * round counts against the bound.
     */
    @Test
    void retryRoundsAreBoundedAndCancellable() {
        UUID operation = commitWait();
        port.state(RuntimeStatus.State.UNAVAILABLE);
        assertEquals(1, jobs.drain(4));
        for (int round = 2; round <= 3; round++) {
            assertEquals(PresentationJobService.Retry.QUEUED, jobs.retry(adventure, campaign, operation));
            assertEquals(1, jobs.drain(4));
            assertEquals("PENDING|NARRATION_UNAVAILABLE", jdbc.queryForObject("select status || '|' || outcome_code "
                    + "from presentation_job where operation_id = ? and round = ?", String.class, operation, round));
        }
        assertEquals(PresentationJobService.Retry.QUEUED, jobs.retry(adventure, campaign, operation));
        assertEquals(OperationCancellationService.Outcome.ALREADY_COMMITTED,
                cancellations.cancel(owner, adventure, campaign, operation));
        assertEquals("PENDING|NARRATION_CANCELLED", jdbc.queryForObject("select status || '|' || outcome_code "
                + "from presentation_job where operation_id = ? and round = 4", String.class, operation));
        var presentation = jobs.presentation(campaign, operation);
        assertEquals(PresentationJobService.Mode.RECEIPT, presentation.mode());
        assertEquals("NARRATION_CANCELLED", presentation.reason());
        assertEquals(0, presentation.retriesRemaining());
        var before = rows();
        assertEquals(PresentationJobService.Retry.EXHAUSTED, jobs.retry(adventure, campaign, operation));
        assertEquals(before, rows());
        assertEquals(0, port.requests.size());
    }

    /** The database admits at most one active and one delivered round per operation, and never a stray publication. */
    @Test
    void databaseGuardsRoundsAndPublications() {
        UUID operation = commitWait();
        assertThrows(RuntimeException.class, () -> jdbc.update("insert into presentation_job (id, adventure_id, "
                + "campaign_id, campaign_generation, operation_id, committed_revision, kind, status, max_attempts, "
                + "due_at, created_at, updated_at, round) values (gen_random_uuid(), ?, ?, 0, ?, 1, 'NARRATION', "
                + "'QUEUED', 3, now(), now(), now(), 2)", adventure, campaign, operation));
        assertThrows(RuntimeException.class, () -> jdbc.update("insert into narration_publication (campaign_id, "
                + "operation_id, adventure_id, job_id, committed_revision, campaign_generation, passage_locale, "
                + "locale_revision, glossary_revision, contract_version, template_hash, repaired, passages, suggestions, "
                + "published_at) select campaign_id, operation_id, adventure_id, id, committed_revision, 0, 'en', 0, 0, "
                + "1, ?, false, '[{}]'::jsonb, '[]'::jsonb, now() from presentation_job where operation_id = ?",
                "c".repeat(64), operation));
        assertThrows(RuntimeException.class, () -> jdbc.update("update presentation_job set round = 3, "
                + "row_version = row_version + 1 where operation_id = ?", operation));
    }

    /** Commit one WAIT at the tracked revision and return its operation. */
    private UUID commitWait() {
        return commit("WAIT", Map.of("seconds", 10));
    }

    /** Commit one WAIT and deliver its narration through the scripted narrator. */
    private UUID commitWaitDelivered() {
        UUID operation = commitWait();
        port.then(seen -> NarratorTaskPort.narrated(seen, false));
        assertEquals(1, jobs.drain(4));
        assertEquals("DELIVERED", journal(operation));
        return operation;
    }

    /** Commit one typed command at the tracked revision and return its operation. */
    private UUID commit(String type, Map<String, Object> payload) {
        var executed = executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision, type, payload));
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        revision = executed.operation().committedRevision();
        return executed.operation().operationId();
    }

    /** Append one extra event to a committed operation, as later resolvers could. */
    private void insertEvent(UUID operation, long sequence, String visibility, String type, String payload) {
        jdbc.update("insert into campaign_event (id, campaign_id, sequence, operation_id, correlation_id, event_type, "
                + "event_version, state_revision, world_time_seconds, ruleset_hash, visibility, payload_schema_version, "
                + "payload, created_at) values (gen_random_uuid(), ?, ?, ?, gen_random_uuid(), ?, 1, ?, 0, ?, ?, 1, "
                + "?::jsonb, now())", campaign, sequence, operation, type, revision, "b".repeat(64), visibility, payload);
    }

    /** Make a requeued job due now, as its backoff would eventually. */
    private void makeDue(UUID operation) {
        jdbc.update("update presentation_job set due_at = clock_timestamp(), row_version = row_version + 1 "
                + "where operation_id = ? and status = 'QUEUED'", operation);
    }

    /** One SQL expression over the operation's first job round. */
    private String job(UUID operation, String expression) {
        return jdbc.queryForObject("select " + expression + " from presentation_job where operation_id = ? and round = 1",
                String.class, operation);
    }

    private String journal(UUID operation) {
        return jdbc.queryForObject("select status from operation_journal where id = ?", String.class, operation);
    }

    private String receipt(UUID operation) {
        return jdbc.queryForObject("select receipt::text from operation_journal where id = ?", String.class, operation);
    }

    private int count(String sql, UUID operation) {
        return jdbc.queryForObject(sql, Integer.class, operation);
    }

    /** Ordered stream message identities and types of the campaign. */
    private List<String> messages() {
        return jdbc.queryForList("select sequence || ':' || message_type from campaign_stream_message "
                + "where campaign_id = ? order by sequence", String.class, campaign);
    }

    /** Exact rows of every world-state table, plus receipts and draws. */
    private Map<String, String> world() {
        var rows = rows(List.of("campaign", "actor", "actor_resource", "campaign_event", "canonical_outbox",
                "query_epoch", "reward_settlement", "operation_random_draw"));
        rows.put("receipts", jdbc.queryForObject("select coalesce(jsonb_agg(receipt order by id), '[]'::jsonb)::text "
                + "from operation_journal", String.class));
        return rows;
    }

    /** Exact presentation rows. */
    private Map<String, String> rows() {
        return rows(List.of("presentation_job", "narration_publication", "operation_journal", "campaign_stream_message"));
    }

    private Map<String, String> rows(List<String> tables) {
        var rows = new LinkedHashMap<String, String>();
        for (String table : tables) {
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return rows;
    }
}
