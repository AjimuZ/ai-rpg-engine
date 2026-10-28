package org.rpgengine.commands;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.inference.contract.ContractRunner;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.inference.scheduling.SchedulerSettings;
import org.rpgengine.inference.scheduling.TaskLedger;
import org.rpgengine.inference.session.AiRole;
import org.rpgengine.inference.session.Audience;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.knowledge.KnowledgeChange;
import org.rpgengine.knowledge.KnowledgeChange.Acquisition;
import org.rpgengine.knowledge.KnowledgeChange.CommitmentDisclosure;
import org.rpgengine.knowledge.KnowledgeChange.CommitmentKind;
import org.rpgengine.knowledge.KnowledgeChange.CommitmentStatus;
import org.rpgengine.knowledge.KnowledgeChange.Disclosure;
import org.rpgengine.knowledge.KnowledgeChange.Predicate;
import org.rpgengine.knowledge.KnowledgeChange.Stance;
import org.rpgengine.knowledge.KnowledgeChange.Truth;
import org.rpgengine.memory.ContextAssembler;
import org.rpgengine.memory.ContextBudget;
import org.rpgengine.memory.ContextEnvelope;
import org.rpgengine.memory.ContextItem;
import org.rpgengine.memory.ContextItem.Standing;
import org.rpgengine.memory.ContinuitySummarizer;
import org.rpgengine.memory.ContinuitySummarizer.Processed;
import org.rpgengine.memory.MemorySettings;
import org.rpgengine.memory.MemorySummaries;
import org.rpgengine.memory.MemorySummaries.Freshness;
import org.rpgengine.memory.OperationalCheckpoints;
import org.rpgengine.memory.OperationalCheckpoints.Capture;
import org.rpgengine.memory.OperationalCheckpoints.Verification;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of P09.3 (docs/08 sections 3, 8 and 10, docs/21 section 10, ADR 0005,
 * ADR 0007) over a campaign created by production code from sealed registered packs, with history
 * from ordinary player commands, knowledge from trusted internal operations through the real
 * canonical commit, and only the summarizer runtime scripted ({@link PlannerTaskPort}) behind a real
 * fair scheduler.
 *
 * <p>The suite proves that operational checkpoints are deterministic, hash-verified, taken only at
 * safe boundaries and bounded; that summary jobs are requested by the interval and scene policy,
 * deduplicated, bounded, retried and failed without touching canonical state; that a recorded
 * summary has exact coverage and versioned sources, stays inside its audience and is assembled as a
 * labelled, optional {@code summary} item; and that freshness detects newer events, source changes,
 * explicit invalidations, locale changes and a restore-style generation change.
 */
@SpringBootTest
class MemoryContinuityTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HASH = "c".repeat(64);
    private static final String POLICY = "d".repeat(64);
    private static final String SECRET = "A surveyor moved the boundary stones at night.";
    private static final String HELD = "The warden hides a key under the waystone.";
    private static final String PROMISE = "I will show you the hidden ford before dusk.";
    private static final String SUMMARY = "The traveller waited at the trailhead while the warden kept watch.";
    @Autowired private CommandExecutionService executions;
    @Autowired private CanonicalCommitService commits;
    @Autowired private MutationLeaseService leases;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private TaskLedger ledger;
    @Autowired private ContextAssembler assembler;
    @Autowired private OperationalCheckpoints checkpoints;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID revision;
    private UUID campaign;
    private UUID player;
    private UUID warden;
    private UUID hermit;
    private UUID promise;
    private PlannerTaskPort port;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    MemoryContinuityTest() { }

    /** Register the dependency and root packs, then create an English campaign through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Memory", "memory", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        var fixture = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI())).files());
        var dependency = register(CommandExecutionTest.dependencyPack(fixture), "test:dep", List.of());
        revision = register(rootPack(fixture), "test:memory", List.of(CommandExecutionTest.pin(dependency)))
                .revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(),
                revision, "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(),
                        null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        warden = actor("warden", "Warden", "trailhead");
        hermit = actor("hermit", "Hermit", "grove");
    }

    /** Remove disposable rows, preserving production constraints and triggers. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, "
                + "knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, "
                + "campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, "
                + "campaign_creation, character_state, item_instance, learned_ability, plan_activation, "
                + "plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, "
                + "planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, "
                + "canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, operation_journal, "
                + "actor_resource, actor, presentation_campaign_settings, campaign, content_revision_report, "
                + "content_revision_file, content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * A checkpoint is the canonical jsonb snapshot with its SQL-computed hash: equal state yields the
     * existing checkpoint, a later revision makes it outdated, a live mutation lease refuses capture,
     * a tampered or divergent row is detected, the RNG seed is never recorded and retention is bounded.
     */
    @Test
    void operationalCheckpointsAreDeterministicVerifiedAndTakenOnlyAtSafeBoundaries() throws Exception {
        assertEquals(Verification.Status.NONE, checkpoints.verify(campaign).status());
        execute(new TypedCommand.Wait(60));
        Capture first = checkpoints.capture(campaign);
        assertEquals(Capture.Outcome.CAPTURED, first.outcome());
        assertEquals(revision(), first.revision());
        assertEquals(Capture.Outcome.EXISTING, checkpoints.capture(campaign).outcome());
        assertEquals(Verification.Status.VALID, checkpoints.verify(campaign).status());
        var stored = checkpoints.latest(campaign).orElseThrow();
        assertEquals(sha256(stored.snapshotJson()), stored.snapshotHash(), "hash of the canonical jsonb text");
        JsonNode snapshot = JSON.readTree(stored.snapshotJson());
        assertEquals("operational", snapshot.path("kind").asString());
        assertEquals(revision(), snapshot.path("campaign").path("stateRevision").asLong());
        assertEquals(player.toString(), snapshot.path("playerActorId").asString());
        assertEquals(3, snapshot.path("actors").size());
        assertTrue(snapshot.path("encounter").isNull());
        assertTrue(snapshot.path("rng").isObject() || snapshot.path("rng").isNull());
        assertFalse(stored.snapshotJson().contains("\"seed\""), "the RNG seed is never part of a checkpoint");

        execute(new TypedCommand.Wait(30));
        assertEquals(Verification.Status.OUTDATED, checkpoints.verify(campaign).status());
        assertEquals(Capture.Outcome.CAPTURED, checkpoints.capture(campaign).outcome());
        assertEquals(Verification.Status.VALID, checkpoints.verify(campaign).status());

        // A live mutation lease marks a possible commit in progress: nothing is written.
        UUID operation = accepted();
        var grant = leases.acquire(adventure, campaign, operation, operation, Duration.ofSeconds(60));
        try {
            assertEquals(Capture.Outcome.UNSAFE_BOUNDARY, checkpoints.capture(campaign).outcome());
        } finally {
            leases.release(adventure, campaign, operation, grant.token());
        }

        // Retention keeps the newest checkpoints only.
        execute(new TypedCommand.Wait(30));
        assertEquals(Capture.Outcome.CAPTURED, checkpoints.capture(campaign).outcome());
        assertEquals(1, checkpoints.prune(campaign, 2));
        assertEquals(2, count("campaign_checkpoint"));
        assertEquals(revision(), checkpoints.latest(campaign).orElseThrow().revision(), "the newest are kept");

        // Tampering and divergence are detected; capture refuses to confirm a divergent row.
        jdbc.update("delete from campaign_checkpoint where campaign_id = ? and state_revision = ?", campaign, revision());
        forge("{\"kind\":\"operational\"}", "0".repeat(64));
        assertEquals(Verification.Status.CORRUPT, checkpoints.verify(campaign).status());
        jdbc.update("delete from campaign_checkpoint where campaign_id = ? and state_revision = ?", campaign, revision());
        forge("{\"kind\":\"operational\"}", null);
        assertEquals(Verification.Status.DIVERGED, checkpoints.verify(campaign).status());
        assertEquals(Capture.Outcome.MISMATCH, checkpoints.capture(campaign).outcome());
    }

    /**
     * Summaries are requested only after the interval of significant actions (or a scene boundary),
     * deduplicated while pending, and recorded with exactly the supplied events, every supplied
     * source with its row version and the named entities. The public summarizer never sees the
     * secret, and the recorded summary is assembled as a labelled, optional item for the public
     * audience only; newer events make it behind, not stale.
     */
    @Test
    void summariesAreDuePerPolicyAndRecordPreciseSourceLinkedCoverage() {
        seedKnowledge();
        var summaries = summaries(MemorySettings.defaults().withSummaries(true));
        var summarizer = summarizer(summaries);
        for (int index = 0; index < 4; index++) {
            execute(new TypedCommand.Wait(60));
        }
        assertEquals(MemorySummaries.Request.Outcome.NOT_DUE, summaries.request(campaign, Audience.PUBLIC, false).outcome());
        execute(new TypedCommand.Wait(60));
        var requested = summaries.request(campaign, Audience.PUBLIC, false);
        assertEquals(MemorySummaries.Request.Outcome.REQUESTED, requested.outcome());
        var again = summaries.request(campaign, Audience.PUBLIC, false);
        assertEquals(MemorySummaries.Request.Outcome.PENDING, again.outcome());
        assertEquals(requested.jobId(), again.jobId(), "a pending job is deduplicated");
        var publicEvents = publicEvents();
        assertEquals(publicEvents.getFirst(), requested.rangeFrom());
        assertEquals(publicEvents.getLast(), requested.rangeTo());

        long before = revision();
        port.then(MemoryContinuityTest::summarized);
        Processed processed = summarizer.processNext(UUID.randomUUID()).orElseThrow();
        assertEquals(Processed.Outcome.RECORDED, processed.outcome(), String.valueOf(processed.code()));
        assertEquals(before, revision(), "recording a summary never changes canonical state");

        JsonNode seen = port.seen.getFirst().context();
        assertEquals("summary", port.seen.getFirst().envelope().path("taskKind").asString());
        assertEquals("summarizer", port.seen.getFirst().envelope().path("role").asString());
        assertEquals("public", port.seen.getFirst().envelope().path("audience").asString());
        assertFalse(seen.toString().contains(SECRET), "the public summarizer never receives the secret");
        assertTrue(seen.toString().contains(HELD) && seen.toString().contains(PROMISE));

        var row = jdbc.queryForMap("select audience, covered_events::text as covered, sources::text as sources, "
                + "source_revision, body::text as body from memory_summary where id = ?", processed.summaryId());
        assertEquals("public", row.get("audience"));
        assertEquals(publicEvents.toString().replace("[", "{").replace("]", "}").replace(" ", ""), row.get("covered"));
        JsonNode sources = JSON.readTree((String) row.get("sources"));
        assertTrue(sources.toString().contains("commitment:" + promise));
        sources.forEach(source -> assertTrue(source.path("version").isNumber()));
        assertTrue(((String) row.get("body")).contains(SUMMARY));
        assertEquals(Freshness.Status.CURRENT, summaries.freshness(processed.summaryId()).status());
        assertEquals(MemorySummaries.Request.Outcome.UP_TO_DATE,
                summaries.request(campaign, Audience.PUBLIC, true).outcome());

        var envelope = assembled(AiRole.NARRATOR, Audience.PUBLIC);
        ContextItem summary = only(envelope, Standing.SUMMARY);
        assertFalse(summary.mandatory());
        assertEquals("current", summary.fields().get("freshness"));
        assertEquals(SUMMARY, summary.fields().get("summary"));
        assertEquals(publicEvents.stream().map(sequence -> "event:" + sequence).toList(),
                summary.fields().get("coveredEvents"));
        assertTrue(envelope.json().contains("A derived summary of the listed earlier events"));
        assertTrue(assembled(AiRole.QUEST_PLANNER, Audience.PRIVILEGED).all().stream()
                .noneMatch(item -> item.standing() == Standing.SUMMARY), "a public summary stays public");

        execute(new TypedCommand.Wait(60));
        var behind = summaries.freshness(processed.summaryId());
        assertEquals(Freshness.Status.BEHIND, behind.status());
        assertEquals(1, behind.uncoveredEvents());
        assertEquals("behind", only(assembled(AiRole.NARRATOR, Audience.PUBLIC), Standing.SUMMARY).fields().get("freshness"));
        assertEquals(MemorySummaries.Request.Outcome.NOT_DUE, summaries.request(campaign, Audience.PUBLIC, false).outcome());

        // The player moving is a scene boundary: a summary is due after a single action.
        execute(new TypedCommand.Move("grove"));
        assertEquals(MemorySummaries.Request.Outcome.REQUESTED, summaries.request(campaign, Audience.PUBLIC, false)
                .outcome());
    }

    /**
     * A summary becomes stale, and leaves every context, when a supplied source changes, when an
     * invalidation names what it covers, when the narrative locale changes and when a restore-style
     * generation change retires it; an unrelated invalidation does not touch it. A source changing
     * between assembly and recording is caught under the lock, and the attempt is retried.
     */
    @Test
    void freshnessDetectsSourceChangesInvalidationsLocaleAndRestore() {
        seedKnowledge();
        var summaries = summaries(MemorySettings.defaults().withSummaries(true));
        var summarizer = summarizer(summaries);
        execute(new TypedCommand.Wait(60));

        // The promise is kept while the summarizer works: the result is not recorded and the job retries.
        assertEquals(MemorySummaries.Request.Outcome.REQUESTED, summaries.request(campaign, Audience.PUBLIC, true)
                .outcome());
        port.then(seen -> {
            internal(1, List.of(new KnowledgeChange.Resolution(promise, 0, CommitmentStatus.FULFILLED, 0)));
            return summarized(seen);
        });
        Processed raced = summarizer.processNext(UUID.randomUUID()).orElseThrow();
        assertEquals(Processed.Outcome.STALE, raced.outcome());
        assertEquals("QUEUED", jobStatus(raced.jobId()));
        assertEquals(0, count("memory_summary"));
        makeDue(raced.jobId());
        port.then(MemoryContinuityTest::summarized);
        Processed retried = summarizer.processNext(UUID.randomUUID()).orElseThrow();
        assertEquals(Processed.Outcome.RECORDED, retried.outcome(), String.valueOf(retried.code()));
        assertFalse(port.seen.getLast().context().toString().contains(PROMISE), "a kept promise is no longer open");
        UUID summary = retried.summaryId();

        // A supplied belief is revised: the summary is stale and is never assembled.
        UUID heldBelief = jdbc.queryForObject("select id from knowledge_belief where holder_actor_id = ?", UUID.class,
                player);
        UUID heldClaim = jdbc.queryForObject("select claim_id from knowledge_belief where id = ?", UUID.class, heldBelief);
        internal(1, List.of(new KnowledgeChange.Belief(UUID.randomUUID(), player, heldClaim, Stance.DOUBTS,
                Acquisition.INFERRED, null, null, new KnowledgeChange.Supersession(heldBelief, 0), 0)));
        var stale = summaries.freshness(summary);
        assertEquals(Freshness.Status.STALE, stale.status());
        assertEquals(List.of("source:belief:" + heldBelief), stale.reasons());
        assertTrue(assembled(AiRole.NARRATOR, Audience.PUBLIC).all().stream()
                .noneMatch(item -> item.standing() == Standing.SUMMARY));

        // A fresh summary replaces it from the same first event; explicit invalidations then apply.
        var replacement = summaries.request(campaign, Audience.PUBLIC, true);
        assertEquals(MemorySummaries.Request.Outcome.REQUESTED, replacement.outcome());
        port.then(MemoryContinuityTest::summarized);
        UUID fresh = summarizer.processNext(UUID.randomUUID()).orElseThrow().summaryId();
        assertNotNull(fresh);
        assertEquals(Freshness.Status.CURRENT, summaries.freshness(fresh).status());
        summaries.invalidate(campaign, "CORRECTION", List.of("actor:" + hermit));
        assertEquals(Freshness.Status.CURRENT, summaries.freshness(fresh).status(), "an unrelated correction");
        summaries.invalidate(campaign, "CORRECTION", List.of("event:" + replacement.rangeFrom()));
        assertEquals(List.of("invalidated:CORRECTION"), summaries.freshness(fresh).reasons());

        port.then(MemoryContinuityTest::summarized);
        summaries.request(campaign, Audience.PUBLIC, true);
        UUID localized = summarizer.processNext(UUID.randomUUID()).orElseThrow().summaryId();
        assertEquals(Freshness.Status.CURRENT, summaries.freshness(localized).status());
        jdbc.update("update presentation_campaign_settings set narrative_locale = 'it', locale_revision = "
                + "locale_revision + 1, row_version = row_version + 1 where campaign_id = ?", campaign);
        assertTrue(summaries.freshness(localized).reasons().contains("locale"));

        // A restore-style generation change retires every summary and every queued job.
        jdbc.update("update presentation_campaign_settings set narrative_locale = 'en', locale_revision = "
                + "locale_revision + 1, row_version = row_version + 1 where campaign_id = ?", campaign);
        execute(new TypedCommand.Wait(60));
        var queued = summaries.request(campaign, Audience.PUBLIC, true);
        assertEquals(MemorySummaries.Request.Outcome.REQUESTED, queued.outcome());
        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1 "
                + "where id = ?", campaign);
        assertTrue(summaries.freshness(localized).reasons().contains("generation"));
        assertEquals(1, summaries.recover());
        assertEquals("CANCELLED", jobStatus(queued.jobId()));
        assertTrue(summaries.claim(UUID.randomUUID()).isEmpty());
    }

    /**
     * Invalid and leaking outputs are repaired once and then use the job's bounded attempts; a
     * failed range is not requested again; a runtime failure is retryable; an expired lease is
     * recovered with a new fence that locks the old worker out; the installation bound throttles;
     * disabled summaries write nothing. No failure changes canonical state.
     */
    @Test
    void failuresAreBoundedRetriedFencedAndNeverTouchCanonicalState() {
        seedKnowledge();
        var summaries = summaries(MemorySettings.defaults().withSummaries(true).withAttempts(2).withMaxPending(1));
        var summarizer = summarizer(summaries);
        execute(new TypedCommand.Wait(60));
        long before = revision();
        assertEquals(MemorySummaries.Request.Outcome.DISABLED, summaries(MemorySettings.defaults())
                .request(campaign, Audience.PUBLIC, true).outcome());
        assertEquals(0, count("memory_summary_job"));

        var job = summaries.request(campaign, Audience.PUBLIC, true);
        assertEquals(MemorySummaries.Request.Outcome.THROTTLED,
                summaries.request(campaign, Audience.PRIVILEGED, true).outcome(), "the installation bound holds");
        // Attempt 1: a public summary repeating the withheld secret, then one claiming an unsupplied event.
        port.then(seen -> summarized(seen, body -> body.put("summary", "The warden knows: " + SECRET)));
        port.then(seen -> summarized(seen, body -> ((List<Object>) body.get("coveredEvents")).add("event:999999")));
        Processed invalid = summarizer.processNext(UUID.randomUUID()).orElseThrow();
        assertEquals(Processed.Outcome.INVALID, invalid.outcome());
        assertEquals(2, port.seen.size(), "exactly one repair");
        assertEquals("QUEUED", jobStatus(job.jobId()));
        assertTrue(summarizer.processNext(UUID.randomUUID()).isEmpty(), "the retry waits for its backoff");

        // Attempt 2: the runtime fails the turn; attempts are spent and the range is given up.
        makeDue(job.jobId());
        port.then(seen -> null);
        Processed unavailable = summarizer.processNext(UUID.randomUUID()).orElseThrow();
        assertEquals("RUNTIME_FAILED", unavailable.code());
        assertEquals("FAILED", jobStatus(job.jobId()));
        assertEquals(2, jdbc.queryForObject("select attempt_count from memory_summary_job where id = ?", Integer.class,
                job.jobId()));
        assertEquals(MemorySummaries.Request.Outcome.GAVE_UP, summaries.request(campaign, Audience.PUBLIC, true)
                .outcome());

        // A lost lease: recovery requeues with a new fence, and the old worker can neither fail nor record.
        execute(new TypedCommand.Wait(60));
        var next = summaries.request(campaign, Audience.PUBLIC, true);
        assertEquals(MemorySummaries.Request.Outcome.REQUESTED, next.outcome());
        var lease = summaries.claim(UUID.randomUUID()).orElseThrow();
        jdbc.update("update memory_summary_job set lease_expires_at = ?, row_version = row_version + 1 where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), lease.jobId());
        assertEquals(1, summaries.recover());
        assertEquals("QUEUED", jobStatus(lease.jobId()));
        assertThrows(IllegalStateException.class, () -> summaries.fail(lease, "RUNTIME_FAILED", true));
        assertThrows(IllegalStateException.class, () -> summaries.record(lease, new MemorySummaries.Recording(
                List.of(lease.rangeFrom()), List.of(), List.of(), "{}", HASH, HASH, 0, before, "en", 0, 0, 0)));
        assertEquals(0, count("memory_summary"));
        assertEquals(before + 1, revision(), "only the player's own wait changed canonical state");

        // Database guards: terminal jobs and stored summaries never change.
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("update memory_summary_job "
                + "set status = 'QUEUED', row_version = row_version + 1 where id = ?", job.jobId()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("insert into "
                + "memory_summary_job (id, adventure_id, campaign_id, campaign_generation, audience, range_from, "
                + "range_to, status, max_attempts, due_at, expires_at, created_at, updated_at) values (?, ?, ?, 0, "
                + "'public', 1, 1, 'QUEUED', 1, now(), now() + interval '1 hour', now(), now())", UUID.randomUUID(),
                adventure, campaign), "one unfinished job per campaign and audience");
    }

    /**
     * A privileged summary receives facts with their truth and is stored for the privileged
     * audience; it is assembled only into privileged contexts, never a public one.
     */
    @Test
    void privilegedSummariesKeepFactsAwayFromThePublicAudience() {
        seedKnowledge();
        var summaries = summaries(MemorySettings.defaults().withSummaries(true));
        var summarizer = summarizer(summaries);
        execute(new TypedCommand.Wait(60));
        assertEquals(MemorySummaries.Request.Outcome.REQUESTED, summaries.request(campaign, Audience.PRIVILEGED, true)
                .outcome());
        port.then(MemoryContinuityTest::summarized);
        Processed processed = summarizer.processNext(UUID.randomUUID()).orElseThrow();
        assertEquals(Processed.Outcome.RECORDED, processed.outcome(), String.valueOf(processed.code()));
        assertEquals("summary-privileged", port.seen.getFirst().envelope().path("taskKind").asString());
        assertEquals("privileged", port.seen.getFirst().envelope().path("audience").asString());
        assertTrue(port.seen.getFirst().context().toString().contains(SECRET), "privileged input holds the secret fact");
        assertTrue(port.seen.getFirst().context().toString().contains("\"standing\":\"fact\""));
        assertEquals(1, assembled(AiRole.QUEST_PLANNER, Audience.PRIVILEGED).all().stream()
                .filter(item -> item.standing() == Standing.SUMMARY).count());
        assertTrue(assembled(AiRole.NARRATOR, Audience.PUBLIC).all().stream()
                .noneMatch(item -> item.standing() == Standing.SUMMARY));
        assertThrows(IllegalArgumentException.class, () -> summaries.request(campaign,
                Audience.character("actor:" + warden), true));
    }

    // ----------------------------------------------------------------------------------------------
    // Fixture helpers

    /** A valid summary echoing every supplied event, obligation note and belief uncertainty. */
    static String summarized(PlannerTaskPort.Seen seen) {
        return summarized(seen, body -> { });
    }

    /** A valid summary changed by {@code change}. */
    static String summarized(PlannerTaskPort.Seen seen, java.util.function.Consumer<Map<String, Object>> change) {
        JsonNode context = seen.context();
        var events = new ArrayList<Object>();
        context.path("events").forEach(event -> events.add(event.path("id").asString()));
        var obligations = new ArrayList<Object>();
        var uncertainties = new ArrayList<Object>();
        context.path("sources").forEach(source -> {
            String standing = source.path("standing").asString();
            if (standing.equals("obligation")) {
                obligations.add(note(source.path("id").asString(), "This promise is still open."));
            } else if (standing.equals("belief")) {
                uncertainties.add(note(source.path("id").asString(), "Heard as a rumor; unconfirmed."));
            }
        });
        var entities = new ArrayList<Object>();
        context.path("entities").forEach(entity -> {
            if (entities.size() < 2) {
                entities.add(entity.path("ref").asString());
            }
        });
        var body = new LinkedHashMap<String, Object>();
        body.put("summary", SUMMARY);
        body.put("coveredEvents", events);
        body.put("obligations", obligations);
        body.put("entities", entities);
        body.put("uncertainties", uncertainties);
        body.put("reasonCode", null);
        change.accept(body);
        return PlannerTaskPort.output(seen, "proposed", body);
    }

    private static Map<String, Object> note(String ref, String note) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ref", ref);
        entry.put("note", note);
        return entry;
    }

    /** Summary jobs over the real storage. */
    private MemorySummaries summaries(MemorySettings settings) {
        return new MemorySummaries(jdbc, manager, settings, Clock.systemUTC());
    }

    /** The summarizer with the scripted runtime behind a real scheduler. */
    private ContinuitySummarizer summarizer(MemorySummaries summaries) {
        port = new PlannerTaskPort();
        var scheduler = new InferenceScheduler(new ScopedSessions(port, 16), port, ledger, SchedulerSettings.defaults(),
                Clock.systemUTC(), () -> 0.5);
        scheduler.recover();
        return new ContinuitySummarizer(jdbc, summaries, assembler, new ContractRunner(scheduler, Clock.systemUTC()));
    }

    /**
     * Claims (a held statement the player has heard as a rumor, a secret the warden knows), and the
     * warden's open promise to the player.
     */
    private void seedKnowledge() {
        UUID held = UUID.randomUUID();
        UUID secret = UUID.randomUUID();
        promise = UUID.randomUUID();
        internal(2, List.of(
                new KnowledgeChange.Claim(held, "actor:" + warden, Predicate.STATEMENT, null, null, null, HELD, "en",
                        Disclosure.HOLDERS, Truth.UNDETERMINED, null, 0),
                new KnowledgeChange.Claim(secret, "actor:" + warden, Predicate.STATEMENT, null, null, null, SECRET,
                        "en", Disclosure.HOLDERS, Truth.TRUE, null, 0),
                new KnowledgeChange.Belief(UUID.randomUUID(), player, held, Stance.RUMORED, Acquisition.TOLD, warden,
                        null, null, 1),
                new KnowledgeChange.Belief(UUID.randomUUID(), warden, secret, Stance.KNOWS, Acquisition.OBSERVED, null,
                        null, null, 1),
                new KnowledgeChange.Commitment(promise, CommitmentKind.PROMISE, warden, player, "location:grove",
                        PROMISE, "en", true, CommitmentDisclosure.PARTIES, null, 1)));
    }

    /** @return the assembled envelope of the current campaign for a role and audience */
    private ContextEnvelope envelope(AiRole role, Audience audience) {
        return assertInstanceOf(ContextAssembler.Assembled.class, assembler.assemble(new ContextAssembler.Request(
                adventure, campaign, role, audience, null, List.of(), null, 1_000,
                ContextBudget.standard(audience.kind() == Audience.Kind.PRIVILEGED), POLICY))).envelope();
    }

    /** Alias kept short for the assertions. */
    private ContextEnvelope assembled(AiRole role, Audience audience) {
        return envelope(role, audience);
    }

    /** @return the single selected item of a standing */
    private static ContextItem only(ContextEnvelope envelope, Standing standing) {
        var items = envelope.all().stream().filter(item -> item.standing() == standing).toList();
        assertEquals(1, items.size(), standing + " in " + envelope.json());
        return items.getFirst();
    }

    /** @return sequences of the campaign's public events in order */
    private List<Long> publicEvents() {
        return jdbc.queryForList("select sequence from campaign_event where campaign_id = ? and visibility = 'PUBLIC' "
                + "order by sequence", Long.class, campaign);
    }

    /** @return the job status */
    private String jobStatus(UUID job) {
        return jdbc.queryForObject("select status from memory_summary_job where id = ?", String.class, job);
    }

    /** Skip a retry backoff without waiting. */
    private void makeDue(UUID job) {
        jdbc.update("update memory_summary_job set due_at = ?, row_version = row_version + 1 where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), job);
    }

    /** @return the row count of a fixed table */
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where campaign_id = ?", Integer.class, campaign);
    }

    /** Insert a checkpoint row at the current revision with the given snapshot and hash (null: its true hash). */
    private void forge(String snapshot, String hash) {
        jdbc.update("insert into campaign_checkpoint (id, campaign_id, state_revision, event_high_water, game_turn, "
                + "world_time_seconds, content_hash, ruleset_hash, snapshot_schema_version, snapshot_hash, snapshot, "
                + "created_at) select ?, c.id, c.state_revision, c.event_sequence, c.game_turn, c.world_time_seconds, "
                + "r.content_hash, r.ruleset_hash, 1, coalesce(?, encode(sha256(convert_to(cast(? as jsonb)::text, "
                + "'UTF8')), 'hex')), cast(? as jsonb), now() from campaign c join content_revision r on "
                + "r.id = c.content_revision_id where c.id = ?", UUID.randomUUID(), hash, snapshot, snapshot, campaign);
    }

    /** @return a new NPC of the campaign */
    private UUID actor(String definition, String name, String location) {
        return new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, definition, name, location, 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
    }

    /** @return a newly accepted operation in validation, ready for a mutation lease */
    private UUID accepted() {
        long base = revision();
        return new TransactionTemplate(manager).execute(status -> {
            var created = CampaignOperation.accept(campaign, UUID.randomUUID().toString(), HASH, "memory-test", base,
                    Instant.now());
            created.transitionTo(OperationStatus.PLANNING, Instant.now());
            created.transitionTo(OperationStatus.VALIDATING, Instant.now());
            entities.persist(created);
            return created.getId();
        });
    }

    /** Commit a trusted internal operation with knowledge changes; returns its revision. */
    private long internal(int events, List<KnowledgeChange> knowledge) {
        long base = revision();
        UUID operation = accepted();
        long version = jdbc.queryForObject("select operation_version from operation_journal where id = ?", Long.class,
                operation);
        var grant = leases.acquire(adventure, campaign, operation, operation, Duration.ofSeconds(60));
        try {
            var list = new ArrayList<ResolvedCommit.Event>();
            for (int index = 0; index < events; index++) {
                list.add(new ResolvedCommit.Event(null, null, EventVisibility.INTERNAL,
                        new CampaignEvent.Body("test-knowledge", 1, 1, "{}")));
            }
            return commits.commit(new ResolvedCommit(new ResolvedCommit.Context(adventure, campaign, operation,
                    revision, HASH, base, version, grant.token()),
                    new ResolvedCommit.WorldChange(false, 0, CampaignMode.EXPLORATION, null), List.of(), list,
                    List.of(), "{}", false, ResolvedCommit.Preparation.NONE, null, List.of(), knowledge)).stateRevision();
        } finally {
            leases.release(adventure, campaign, operation, grant.token());
        }
    }

    /** Execute one ordinary player intention at the current revision; it must commit. */
    private void execute(TypedCommand command) {
        var execution = executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision(), command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status());
    }

    /** Current committed state revision. */
    private long revision() {
        return jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign);
    }

    /** @return lowercase SHA-256 hex of UTF-8 text */
    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Root pack of the command suites plus a hermit in the grove (the same variant as the context suite). */
    private static Map<String, byte[]> rootPack(Map<String, byte[]> fixture) {
        var files = CommandExecutionTest.rootPack(fixture);
        files.put("npcs.json", JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions", List.of(
                Map.of("id", "warden", "kind", "npc", "data", Map.of("name", "Warden", "role", "Trail keeper",
                        "location", "trailhead")),
                Map.of("id", "hermit", "kind", "npc", "data", Map.of("name", "Hermit", "role", "Recluse",
                        "location", "grove"))))));
        return files;
    }

    /** Register a fixture variant through real ZIP staging and sealing. */
    private PackRegistry.Registration register(Map<String, byte[]> original, String id,
            List<Map<String, Object>> dependencies) throws Exception {
        var files = new TreeMap<>(original);
        var manifest = CommandExecutionTest.document(files, "adventure.json");
        manifest.put("id", id);
        manifest.put("version", "1.0.0");
        manifest.put("dependencies", dependencies);
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
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
