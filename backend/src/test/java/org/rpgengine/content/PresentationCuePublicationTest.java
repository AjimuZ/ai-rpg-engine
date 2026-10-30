package org.rpgengine.content;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationCancellationService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.commands.PublicStreamService;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.NarrationPort;
import org.rpgengine.presentation.PresentationEventStore;
import org.rpgengine.presentation.PresentationEvents;
import org.rpgengine.presentation.PresentationEvents.Draft;
import org.rpgengine.presentation.PresentationJobService;
import org.rpgengine.presentation.PresentationScopePort;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of presentation cues on the post-commit narration path (P05.7-B.1.2.3.2): played
 * WAIT commands, a registered pack, the real scope resolver, store, V26 trigger and public stream, with only
 * the narration port scripted. The admission gate runs once per operation, a refused list costs the cues
 * and never the narration, a discarded or rolled-back settlement leaves no cue, and every retry delivers
 * each cue once.
 *
 * <p>The extra public and internal events are inserted by SQL: a WAIT names no NPC, and the actors a played
 * command would name are not what this path decides. Lives beside the pack corpus tests because it reuses
 * their package-private reference pack.
 */
@SpringBootTest
class PresentationCuePublicationTest {
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final String TYPE = PublicStreamService.PRESENTATION;
    private static final List<String> NO_CUE_MESSAGES = List.of("operation.accepted", "operation.progress",
            "state.committed", "operation.progress", "narrative.ready");
    private static final Logger JOB_LOG = Logger.getLogger(PresentationJobService.class.getName());
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    /**
     * Reads the job service's own logger rather than the console: PackagedSecretsTest starts an application
     * with logging switched off in this JVM, so what reaches the console depends on the test order.
     */
    private final Handler trace = new Handler() {
        @Override
        public void publish(LogRecord record) {
            warnings.add(new SimpleFormatter().formatMessage(record));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    @Autowired private PackRegistry registry;
    @Autowired private CommandExecutionService executions;
    @Autowired private OperationCancellationService cancellations;
    @Autowired private PublicStreamService stream;
    @Autowired private PresentationEventStore store;
    @Autowired private PresentationScopePort scopes;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private UUID player;
    private UUID keeper;
    private UUID internalOnly;
    private UUID unreferenced;
    private UUID guard;
    private long revision;
    private long sequence;

    /** Construct the injected PostgreSQL fixture; missing infrastructure fails startup. */
    PresentationCuePublicationTest() {
    }

    /** Commit one owner and a campaign pinned to a registered pack that declares a presentation. */
    @BeforeEach
    void prepare() {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", NOW);
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Harbor", "harbor", SupportedLocale.EN, NOW);
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        UUID content = registry.register(adventure, PresentationScopeResolverTest.staged(
                PresentationScopeResolverTest.declaring())).revisionId();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var started = Campaign.start(adventure, entities.find(ContentRevision.class, content), "Campaign", "seed", NOW);
            entities.persist(started);
            campaign = started.getId();
            player = actor(ActorKind.CHARACTER, null, "Aria");
            keeper = actor(ActorKind.NPC, "keeper", "Keeper");
            internalOnly = actor(ActorKind.NPC, "keeper", "Vault Keeper");
            unreferenced = actor(ActorKind.NPC, "keeper", "Sleeper");
            guard = actor(ActorKind.NPC, "guard", "Guard");
            entities.flush();
            jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, "
                    + "profile_id, choices, created_at) values (?, ?, ?, ?, ?, 'visitor', '{}'::jsonb, now())",
                    adventure, UUID.randomUUID(), "a".repeat(64), campaign, player);
            jdbc.update("insert into presentation_campaign_settings (campaign_id, narrative_locale, updated_at) "
                    + "values (?, 'en', now())", campaign);
        });
        revision = 0;
        sequence = 1000;
    }

    /** Observe the job service's warnings whatever the JVM's logging configuration became. */
    @BeforeEach
    void observeWarnings() {
        JOB_LOG.setLevel(Level.WARNING);
        JOB_LOG.addHandler(trace);
    }

    /** Give the logger back as it was. */
    @AfterEach
    void releaseWarnings() {
        JOB_LOG.removeHandler(trace);
        JOB_LOG.setLevel(null);
    }

    /** Remove disposable rows; constraints and triggers remain installed. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, "
                + "ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, "
                + "operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, "
                + "reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, content_revision, "
                + "adventure_workspace, identity_owner_profile");
    }

    /**
     * One delivered round calls the store once and the admission gate once. Its cues reach the ordinary stream
     * before {@code narrative.ready} with exactly the admitted references, one message per stored row, and a
     * further pass or a replayed read adds nothing. No world row moves.
     */
    @Test
    void admittedCuesReachTheStreamThroughOneAdmission() {
        UUID operation = playedOperation();
        var counted = Mockito.spy(store);
        var world = world();
        var jobs = service(request -> delivered(cues(operation)), counted, scopes);
        var before = stream.read(owner, adventure, campaign, "0:0").messages().size();
        try (var admission = Mockito.mockStatic(PresentationEvents.class, Mockito.CALLS_REAL_METHODS)) {
            assertEquals(1, jobs.drain(4));
            assertEquals(0, jobs.drain(4));
            admission.verify(() -> PresentationEvents.acceptAll(Mockito.anyList(), Mockito.any()), Mockito.times(1));
        }
        Mockito.verify(counted, Mockito.times(1)).record(Mockito.eq(campaign), Mockito.anyList(), Mockito.any());

        assertEquals(List.of("operation.accepted", "operation.progress", "state.committed", "operation.progress", TYPE, TYPE,
                TYPE, "narrative.ready"), messages(operation));
        assertEquals(3, rows(operation));
        assertEquals(3, jdbc.queryForObject("select count(*) from campaign_stream_message where operation_id = ? "
                + "and message_type = ?", Integer.class, operation, TYPE));
        var read = stream.read(owner, adventure, campaign, "0:0").messages();
        assertEquals(before + 5, read.size(), "the claim's progress, three cues and narrative.ready");
        var cues = read.stream().filter(message -> message.type().equals(TYPE)).toList();
        assertEquals(List.of("SCENE|harbor-dawn|harbor-backdrop", "POSE|" + keeper + "|wave|smile",
                "LINE_AUDIO|" + keeper + "|greeting.01|greeting-voice"), cues.stream().map(message -> {
                    var data = message.data();
                    return switch ((String) data.get("kind")) {
                        case "SCENE" -> "SCENE|" + data.get("sceneId") + "|" + data.get("assetId");
                        case "POSE" -> "POSE|" + data.get("actorId") + "|" + data.get("poseId") + "|"
                                + data.get("expressionId");
                        default -> "LINE_AUDIO|" + data.get("actorId") + "|" + data.get("lineId") + "|"
                                + data.get("assetId");
                    };
                }).toList());
        assertEquals(cues, stream.read(owner, adventure, campaign, "0:0").messages().stream()
                .filter(message -> message.type().equals(TYPE)).toList(), "a replayed read keeps the delivery identities");
        assertEquals("DELIVERED", journal(operation));
        assertEquals(world, world());
    }

    /** A publication without cues never loads a scope, calls the store or reaches the admission gate. */
    @Test
    void publicationWithoutCuesCostsNothing() {
        UUID operation = playedOperation();
        var counted = Mockito.spy(store);
        var resolved = new CopyOnWriteArrayList<UUID>();
        var jobs = service(request -> delivered(List.of()), counted, (adventureId, campaignId, operationId, committed) -> {
            resolved.add(operationId);
            return Optional.empty();
        });
        try (var admission = Mockito.mockStatic(PresentationEvents.class, Mockito.CALLS_REAL_METHODS)) {
            assertEquals(1, jobs.drain(4));
            admission.verify(() -> PresentationEvents.acceptAll(Mockito.anyList(), Mockito.any()), Mockito.never());
        }
        Mockito.verifyNoInteractions(counted);
        assertEquals(List.of(), resolved);
        assertEquals(NO_CUE_MESSAGES, messages(operation));
    }

    /**
     * Hidden actors, undeclared references, executable values and raw URLs each refuse the whole list against
     * the real scope: nothing is stored or streamed, the good cue beside them included, the drop is logged by
     * reason without the value, and the narration is still published.
     */
    @Test
    void refusedValuesNeverReachTheStream() {
        var hostile = new LinkedHashMap<String, Refused>();
        for (UUID actor : List.of(internalOnly, unreferenced, guard, player, UUID.randomUUID())) {
            hostile.put("actor " + actor, new Refused("HIDDEN_ACTOR", pose(actor.toString(), "wave", "smile")));
        }
        hostile.put("undeclared scene", new Refused("NOT_ALLOWLISTED", scene("cellar-secret", null)));
        hostile.put("undeclared backdrop", new Refused("NOT_ALLOWLISTED", scene("market-day", "secret-backdrop")));
        hostile.put("undeclared pose", new Refused("NOT_ALLOWLISTED", pose(null, "secret-pose", "smile")));
        hostile.put("undeclared expression", new Refused("NOT_ALLOWLISTED", pose(null, "wave", "secret-face")));
        hostile.put("undeclared line", new Refused("NOT_ALLOWLISTED", line("secret-line", "greeting-voice")));
        hostile.put("undeclared audio", new Refused("NOT_ALLOWLISTED", line("farewell", "secret-audio")));
        hostile.put("url scene", new Refused("RAW_URL", scene("https://example.test/x.png", null)));
        hostile.put("url asset", new Refused("RAW_URL", scene("market-day", "//example.test/x.png")));
        hostile.put("url audio", new Refused("RAW_URL", line("farewell", "file:///etc/passwd")));
        hostile.put("markup scene", new Refused("EXECUTABLE", scene("<script>alert(1)</script>", null)));
        hostile.put("script pose", new Refused("EXECUTABLE", pose(null, "javascript:alert(1)", "smile")));
        hostile.put("handler expression", new Refused("EXECUTABLE", pose(null, "wave", "onload=alert(1)")));
        hostile.put("markup actor", new Refused("EXECUTABLE", pose("<img src=x>", "wave", "smile")));

        hostile.forEach((name, refused) -> {
            UUID operation = playedOperation();
            var cue = refused.draft();
            var targeted = new Draft(cue.kind(), operation, revision, 1, cue.sceneId(),
                    cue.actorId() == null && cue.kind().equals("POSE") ? keeper.toString() : cue.actorId(), cue.poseId(),
                    cue.expressionId(), cue.lineId(), cue.assetId());
            var good = new Draft("SCENE", operation, revision, 0, "harbor-dawn", null, null, null, null, null);
            assertEquals(1, service(request -> delivered(List.of(good, targeted)), store, scopes).drain(4), name);
            assertEquals(NO_CUE_MESSAGES, messages(operation), name);
            assertEquals(0, rows(operation), name);
            assertEquals("DELIVERED|NARRATION_DELIVERED", job(operation), name);
            assertEquals("DELIVERED", journal(operation), name);
            assertEquals(1, publications(operation), name);
            assertTrue(warnings.contains("Presentation cues of operation " + operation + " dropped: "
                    + refused.reason()), name);
        });

        assertEquals(0, jdbc.queryForObject("select count(*) from presentation_event", Integer.class));
        String delivered = stream.read(owner, adventure, campaign, "0:0").messages().toString();
        assertFalse(delivered.contains(TYPE));
        assertEquals(hostile.size(), warnings.size());
        for (String value : List.of("cellar-secret", "secret-backdrop", "secret-pose", "secret-face", "secret-line",
                "secret-audio", "example.test", "alert(1)", "<script", "<img", "etc/passwd")) {
            assertFalse(delivered.contains(value), value);
            assertFalse(warnings.toString().contains(value), "a log line never carries a rejected value: " + value);
        }
    }

    /**
     * Cues naming another committed operation or revision, out-of-bounds or repeated ordinals, and a missing or
     * failed scope are all dropped; the other operation gains nothing and the narration is published.
     */
    @Test
    void foreignBoundedAndScopelessCuesAreDropped() {
        UUID earlier = playedOperation();
        long earlierRevision = revision;
        assertEquals(1, service(request -> delivered(List.of()), store, scopes).drain(4));

        UUID foreign = playedOperation();
        assertDropped(foreign, List.of(new Draft("SCENE", earlier, earlierRevision, 0, "harbor-dawn", null, null, null, null,
                null)), scopes, "FOREIGN_OPERATION");
        assertEquals(0, rows(earlier));
        UUID wrongRevision = playedOperation();
        assertDropped(wrongRevision, List.of(new Draft("SCENE", wrongRevision, revision + 1, 0, "harbor-dawn", null, null,
                null, null, null)), scopes, "FOREIGN_OPERATION");
        UUID outOfBounds = playedOperation();
        assertDropped(outOfBounds, List.of(new Draft("SCENE", outOfBounds, revision, PresentationEvents.MAX_PER_OPERATION,
                "harbor-dawn", null, null, null, null, null)), scopes, "BOUNDS");
        UUID repeated = playedOperation();
        var twice = new Draft("SCENE", repeated, revision, 0, "harbor-dawn", null, null, null, null, null);
        assertDropped(repeated, List.of(twice, twice), scopes, "BOUNDS");
        UUID malformed = playedOperation();
        assertDropped(malformed, List.of(new Draft("SCRIPT", malformed, revision, 0, "harbor-dawn", null, null, null, null,
                null)), scopes, "MALFORMED");

        UUID stale = playedOperation();
        assertDropped(stale, cues(stale), (adventureId, campaignId, operationId, committed) -> Optional.empty(),
                "SCOPE_UNAVAILABLE");
        UUID failing = playedOperation();
        assertDropped(failing, cues(failing), (adventureId, campaignId, operationId, committed) -> {
            throw new IllegalStateException("pinned pack closure is gone");
        }, "SCOPE_UNAVAILABLE");
    }

    /**
     * A refused list on a narration-only retry round costs the cues too: the publication is stored, the round is
     * DELIVERED and the journal keeps its terminal status. The operation view shows the narration.
     */
    @Test
    void refusedCuesOnARetryRoundKeepThePublication() {
        UUID operation = playedOperation();
        assertEquals(1, service(request -> NarrationPort.Result.of(NarrationPort.Outcome.UNAVAILABLE), store, scopes).drain(4));
        assertEquals("COMMITTED_NARRATION_PENDING", journal(operation));
        var jobs = service(request -> delivered(List.of(scene(operation, 0, "https://example.test/x.png"))), store, scopes);
        assertEquals(PresentationJobService.Retry.QUEUED, jobs.retry(adventure, campaign, operation));
        assertEquals(1, jobs.drain(4));
        assertEquals(1, publications(operation));
        assertEquals(0, rows(operation));
        assertEquals("COMMITTED_NARRATION_PENDING", journal(operation));
        assertEquals(PresentationJobService.Mode.NARRATED, jobs.presentation(campaign, operation).mode());
    }

    /** The refusal of one operation's list costs that operation only: the next operation's cues are delivered. */
    @Test
    void aRejectionLeavesTheWorkerAbleToDeliverLaterCues() {
        UUID refused = playedOperation();
        assertEquals(1, service(request -> delivered(List.of(scene(refused, 0, "cellar-secret"))), store, scopes).drain(4));
        UUID admitted = playedOperation();
        assertEquals(1, service(request -> delivered(cues(admitted)), store, scopes).drain(4));
        assertEquals(0, rows(refused));
        assertEquals(3, rows(admitted));
    }

    /**
     * A publication discarded for a stale locale generation records no cue. The attempt that replaces it records
     * each cue once, through one admission.
     */
    @Test
    void staleGenerationDiscardsCuesAndTheRetryDeliversThemOnce() {
        UUID operation = playedOperation();
        var counted = Mockito.spy(store);
        var attempts = new CopyOnWriteArrayList<Integer>();
        var jobs = service(request -> {
            attempts.add(request.attempt());
            return request.attempt() == 1 ? stale(cues(operation)) : delivered(cues(operation));
        }, counted, scopes);
        assertEquals(1, jobs.drain(4));
        assertEquals(0, rows(operation));
        assertEquals(0, publications(operation));
        Mockito.verifyNoInteractions(counted);
        makeDue(operation);
        assertEquals(1, jobs.drain(4));
        assertEquals(List.of(1, 2), attempts);
        assertEquals(3, rows(operation));
        assertEquals(3, streamed(operation));
        Mockito.verify(counted, Mockito.times(1)).record(Mockito.eq(campaign), Mockito.anyList(), Mockito.any());
    }

    /**
     * A settlement that fails after recording rolls back its cues, their messages and the publication together,
     * so the attempt after the lease expires delivers each cue exactly once.
     */
    @Test
    void aFailedSettlementRollsCuesBackAndTheRetryDeliversThemOnce() {
        UUID operation = playedOperation();
        var failing = Mockito.spy(store);
        Mockito.doAnswer(call -> {
            call.callRealMethod();
            throw new TransientDataAccessResourceException("storage failed after the insert");
        }).doCallRealMethod().when(failing).record(Mockito.any(), Mockito.anyList(), Mockito.any());
        var head = head();
        var jobs = service(request -> delivered(cues(operation)), failing, scopes);

        assertThrows(TransientDataAccessResourceException.class, jobs::runOnce);
        assertEquals(0, rows(operation));
        assertEquals(0, publications(operation));
        assertEquals(head + 1, head(), "only the claim's progress message survives the rollback");
        assertEquals("NARRATING", journal(operation));

        jdbc.update("update presentation_job set lease_expires_at = clock_timestamp() - interval '1 second', "
                + "row_version = row_version + 1 where operation_id = ?", operation);
        assertTrue(jobs.runOnce());
        makeDue(operation);
        assertEquals(1, jobs.drain(4));
        assertEquals(3, rows(operation));
        assertEquals(3, streamed(operation));
        assertEquals(1, publications(operation));
        assertEquals("DELIVERED", journal(operation));
    }

    /** A worker that lost its lease while narrating settles nothing, and its replacement delivers each cue once. */
    @Test
    void lostLeaseRecordsNoCueAndTheReplacementDeliversThemOnce() {
        UUID operation = playedOperation();
        var counted = Mockito.spy(store);
        var jobs = service(request -> {
            if (request.attempt() == 1) {
                jdbc.update("update presentation_job set lease_expires_at = clock_timestamp() - interval '1 second', "
                        + "row_version = row_version + 1 where operation_id = ?", operation);
            }
            return delivered(cues(operation));
        }, counted, scopes);
        assertTrue(jobs.runOnce());
        assertEquals(0, rows(operation));
        assertEquals(0, publications(operation));
        Mockito.verifyNoInteractions(counted);
        assertTrue(jobs.runOnce());
        makeDue(operation);
        assertEquals(1, jobs.drain(4));
        assertEquals(3, rows(operation));
        assertEquals(3, streamed(operation));
        Mockito.verify(counted, Mockito.times(1)).record(Mockito.eq(campaign), Mockito.anyList(), Mockito.any());
    }

    /** Cancelling while narration runs discards the publication, and its cues with it. */
    @Test
    void cancellationDuringNarrationDiscardsCues() {
        UUID operation = playedOperation();
        var jobs = service(request -> {
            assertEquals(OperationCancellationService.Outcome.ALREADY_COMMITTED,
                    cancellations.cancel(owner, adventure, campaign, operation));
            return delivered(cues(operation));
        }, store, scopes);
        assertEquals(1, jobs.drain(4));
        assertEquals("PENDING|NARRATION_CANCELLED", job(operation));
        assertEquals(0, rows(operation));
        assertEquals(0, publications(operation));
    }

    /**
     * A narration-only retry round records its cues under the journal's terminal status, once; asking again reports
     * the published narration and adds nothing. Cues an earlier write already stored are not announced again,
     * and the first stored content wins.
     */
    @Test
    void retryRoundAndRepeatedCuesDeliverEachCueOnce() {
        UUID operation = playedOperation();
        assertEquals(1, service(request -> NarrationPort.Result.of(NarrationPort.Outcome.UNAVAILABLE), store, scopes).drain(4));
        assertEquals(0, rows(operation));
        var jobs = service(request -> delivered(cues(operation)), store, scopes);
        assertEquals(PresentationJobService.Retry.QUEUED, jobs.retry(adventure, campaign, operation));
        assertEquals(1, jobs.drain(4));
        assertEquals(3, rows(operation));
        assertEquals(3, streamed(operation));
        assertEquals("COMMITTED_NARRATION_PENDING", jdbc.queryForObject("select distinct status from "
                + "campaign_stream_message where operation_id = ? and message_type = ?", String.class, operation, TYPE));
        var head = head();
        assertEquals(PresentationJobService.Retry.ALREADY_NARRATED, jobs.retry(adventure, campaign, operation));
        assertEquals(0, jobs.drain(4));
        assertEquals(head, head());

        UUID repeated = playedOperation();
        var scope = scopes.resolve(adventure, campaign, repeated, revision).orElseThrow();
        assertEquals(1, store.record(campaign, List.of(scene(repeated, 0, "market-day")), scope));
        assertEquals(1, service(request -> delivered(cues(repeated)), store, scopes).drain(4));
        assertEquals(3, rows(repeated));
        assertEquals(3, streamed(repeated));
        assertEquals("market-day", jdbc.queryForObject("select scene_id from presentation_event where operation_id = ? "
                + "and ordinal = 0", String.class, repeated));
    }

    /** A publication holds at most {@link PresentationEvents#MAX_PER_OPERATION} cues and copes with none. */
    @Test
    void publicationBoundsItsCues() {
        UUID operation = UUID.randomUUID();
        var cues = new ArrayList<Draft>();
        for (int ordinal = 0; ordinal < PresentationEvents.MAX_PER_OPERATION; ordinal++) {
            cues.add(scene(operation, ordinal, "market-day"));
        }
        assertEquals(PresentationEvents.MAX_PER_OPERATION, publication(cues, 0).cues().size());
        cues.add(scene(operation, PresentationEvents.MAX_PER_OPERATION, "market-day"));
        assertThrows(IllegalArgumentException.class, () -> publication(cues, 0));
        assertEquals(List.of(), new NarrationPort.Publication(SupportedLocale.EN, 0, 0, "c".repeat(64), false, passages(),
                List.of(), null, null, null).cues());
    }

    /** Drive one operation with a dropped list and require the narration, the trace and no cue. */
    private void assertDropped(UUID operation, List<Draft> cues, PresentationScopePort scope, String reason) {
        assertEquals(1, service(request -> delivered(cues), store, scope).drain(4), reason);
        assertEquals(NO_CUE_MESSAGES, messages(operation), reason);
        assertEquals(0, rows(operation), reason);
        assertEquals(1, publications(operation), reason);
        assertEquals("DELIVERED", journal(operation), reason);
        assertTrue(warnings.contains("Presentation cues of operation " + operation + " dropped: " + reason), reason);
    }

    /**
     * Play one WAIT, then give it the public statement that makes the keeper visible and the internal one that
     * must not make the other keeper visible.
     */
    private UUID playedOperation() {
        var executed = executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision, "WAIT", Map.of("seconds", 10)));
        assertEquals(OperationStatus.COMMITTED, executed.operation().status());
        revision = executed.operation().committedRevision();
        UUID operation = executed.operation().operationId();
        event(operation, "PUBLIC", "{\"actorId\":\"" + player + "\",\"targetActorIds\":[\"" + keeper + "\",\"" + guard + "\"]}");
        event(operation, "INTERNAL", "{\"actorId\":\"" + internalOnly + "\"}");
        return operation;
    }

    private void event(UUID operation, String visibility, String payload) {
        jdbc.update("insert into campaign_event (id, campaign_id, sequence, operation_id, correlation_id, event_type, "
                + "event_version, state_revision, world_time_seconds, ruleset_hash, visibility, payload_schema_version, "
                + "payload, created_at) values (gen_random_uuid(), ?, ?, ?, gen_random_uuid(), 'TEST_EVENT', 1, ?, 0, "
                + "?, ?, 1, ?::jsonb, now())", campaign, ++sequence, operation, revision, "b".repeat(64), visibility,
                payload);
    }

    private UUID actor(ActorKind kind, String definition, String name) {
        var actor = Actor.create(campaign, kind, definition, name, "harbor", 0, NOW);
        entities.persist(actor);
        return actor.getId();
    }

    /** One cue of each kind the pack declares, for the tracked revision. */
    private List<Draft> cues(UUID operation) {
        return List.of(new Draft("SCENE", operation, revision, 0, "harbor-dawn", null, null, null, null, "harbor-backdrop"),
                new Draft("POSE", operation, revision, 1, null, keeper.toString(), "wave", "smile", null, null),
                new Draft("LINE_AUDIO", operation, revision, 2, null, keeper.toString(), null, null, "greeting.01",
                        "greeting-voice"));
    }

    private Draft scene(UUID operation, int ordinal, String scene) {
        return new Draft("SCENE", operation, revision, ordinal, scene, null, null, null, null, null);
    }

    /** Field templates of a refused cue; the case fills in the operation and the visible keeper when none is named. */
    private static Draft scene(String scene, String asset) {
        return new Draft("SCENE", null, 0, 0, scene, null, null, null, null, asset);
    }

    private static Draft pose(String actor, String pose, String expression) {
        return new Draft("POSE", null, 0, 0, null, actor, pose, expression, null, null);
    }

    private static Draft line(String line, String asset) {
        return new Draft("LINE_AUDIO", null, 0, 0, null, null, null, null, line, asset);
    }

    private static NarrationPort.Result delivered(List<Draft> cues) {
        return NarrationPort.Result.delivered(publication(cues, 0));
    }

    /** A publication bound to a locale generation the campaign does not have. */
    private static NarrationPort.Result stale(List<Draft> cues) {
        return NarrationPort.Result.delivered(publication(cues, 99));
    }

    private static NarrationPort.Publication publication(List<Draft> cues, long localeRevision) {
        return new NarrationPort.Publication(SupportedLocale.EN, localeRevision, 0, "c".repeat(64), false, passages(),
                List.of(), null, null, null, cues);
    }

    private static List<NarrationPort.Passage> passages() {
        return List.of(new NarrationPort.Passage(NarrationPort.PassageKind.NARRATION, null, null, "Time passes quietly.",
                List.of("event.1")));
    }

    private PresentationJobService service(NarrationPort port, PresentationEventStore events, PresentationScopePort scope) {
        return new PresentationJobService(entities, jdbc, manager, port, events, scope);
    }

    private void makeDue(UUID operation) {
        jdbc.update("update presentation_job set due_at = clock_timestamp(), row_version = row_version + 1 "
                + "where operation_id = ? and status = 'QUEUED'", operation);
    }

    private String job(UUID operation) {
        return jdbc.queryForObject("select status || '|' || outcome_code from presentation_job where operation_id = ?",
                String.class, operation);
    }

    private String journal(UUID operation) {
        return jdbc.queryForObject("select status from operation_journal where id = ?", String.class, operation);
    }

    private List<String> messages(UUID operation) {
        return jdbc.queryForList("select message_type from campaign_stream_message where operation_id = ? "
                + "order by sequence", String.class, operation);
    }

    private int rows(UUID operation) {
        return jdbc.queryForObject("select count(*) from presentation_event where operation_id = ?", Integer.class, operation);
    }

    private int streamed(UUID operation) {
        return jdbc.queryForObject("select count(*) from campaign_stream_message where operation_id = ? "
                + "and message_type = ?", Integer.class, operation, TYPE);
    }

    private int publications(UUID operation) {
        return jdbc.queryForObject("select count(*) from narration_publication where operation_id = ?", Integer.class,
                operation);
    }

    private long head() {
        return jdbc.queryForObject("select last_sequence from campaign_stream_head where campaign_id = ?", Long.class,
                campaign);
    }

    /** Exact rows of the world tables and the receipts, which cues must never move. */
    private Map<String, String> world() {
        var rows = new LinkedHashMap<String, String>();
        for (String table : List.of("campaign", "actor", "actor_resource", "campaign_event", "canonical_outbox", "query_epoch",
                "reward_settlement", "operation_random_draw")) {
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        rows.put("receipts", jdbc.queryForObject("select coalesce(jsonb_agg(receipt order by id), '[]'::jsonb)::text "
                + "from operation_journal", String.class));
        return rows;
    }

    /** One refused cue and the reason its drop must log. */
    private record Refused(String reason, Draft draft) {
    }
}
