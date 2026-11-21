package org.rpgengine.corrections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.commands.TypedCommand;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.corrections.CanonCorrections.Code;
import org.rpgengine.corrections.CanonCorrections.Request;
import org.rpgengine.exports.CampaignExports;
import org.rpgengine.knowledge.KnowledgeChange.Truth;
import org.rpgengine.memory.MemorySummaries;
import org.rpgengine.preparation.Audience;
import org.rpgengine.preparation.JobRequest;
import org.rpgengine.preparation.PlanAdmissionService;
import org.rpgengine.preparation.PlanAdmissionService.Dependency;
import org.rpgengine.preparation.PlanAdmissionService.References;
import org.rpgengine.preparation.PlanAdmissionService.Severity;
import org.rpgengine.preparation.PlanContentCatalogs;
import org.rpgengine.preparation.PlanningSnapshotService;
import org.rpgengine.preparation.PreparationJobService;
import org.rpgengine.preparation.QueryEpochs;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.quests.QuestPack;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of QA-27 (P16.3-A). A campaign holds a claim the player knows and an NPC believes, two
 * admitted plans (one reads the claim registry, one does not) and two continuity summaries (one covers the claim's
 * source event, one does not). Correcting the claim must preview exactly what it commits, keep every earlier event
 * byte for byte, store a backup equal to a full-owner export of the corrected revision, cite both in an internal audit
 * event, carry the beliefs, invalidate only the dependent plan and summary, and write nothing when refused.
 */
@SpringBootTest
class CanonCorrectionsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000163a0");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HASH = "c".repeat(64);
    private static final String TARGET = "quest:survey";
    private static final Duration LEASE = Duration.ofSeconds(120);
    private static final String STATEMENT = "The warden guards the north bridge.";
    private static final String REASON = "The bridge has no warden in this campaign.";
    // Every table a correction writes, plus the stream; a preview or a refusal must leave all of them unchanged.
    private static final List<String> WRITTEN = List.of("campaign", "campaign_event", "operation_journal",
            "canonical_outbox", "knowledge_claim", "knowledge_belief", "query_epoch", "plan_disposition",
            "memory_invalidation", "campaign_stream_message", "canon_correction");
    @Autowired private CanonCorrections corrections;
    @Autowired private CampaignExports exports;
    @Autowired private MemorySummaries summaries;
    @Autowired private CommandExecutionService executions;
    @Autowired private OperationAdmissionService admissionsOfCommands;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private PreparationJobService jobs;
    @Autowired private PlanningSnapshotService snapshots;
    @Autowired private PlanAdmissionService admissions;
    @Autowired private PlanContentCatalogs catalogs;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID adventure;
    private UUID campaign;
    private UUID player;
    private UUID warden;
    private UUID claim;
    private long claimEvent;
    private long revision;
    private int jobSequence;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    CanonCorrectionsTest() { }

    /** Create a campaign through production code, play two waits and plant the claim between them. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        adventure = new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(OWNER, "Corrections", "corrections", SupportedLocale.EN,
                    Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(QuestPack.files())))).revisionId();
        var created = creation.create(OWNER, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null,
                        null)));
        campaign = created.campaignId();
        player = created.actorId();
        revision = 0;
        jobSequence = 0;
        execute(new TypedCommand.Wait(60));
        warden = new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, "warden", "Warden", "trailhead", 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
        claimEvent = jdbc.queryForObject("select max(sequence) from campaign_event where campaign_id = ?", Long.class,
                campaign);
        claim = UUID.randomUUID();
        jdbc.update("insert into knowledge_claim (id, campaign_id, subject_ref, predicate, statement, statement_locale, "
                + "disclosure, truth, established_revision, established_world_time, established_event_sequence, "
                + "truth_revision, truth_event_sequence) values (?, ?, ?, 'STATEMENT', ?, 'en', 'HOLDERS', 'TRUE', ?, 60, "
                + "?, ?, ?)", claim, campaign, "actor:" + warden, STATEMENT, revision, claimEvent, revision, claimEvent);
        belief(player, "KNOWS");
        belief(warden, "BELIEVES");
        execute(new TypedCommand.Wait(60));
    }

    /** Remove disposable rows. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate canon_correction, encounter_participant, encounter, quest_objective, quest_state, "
                + "location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, "
                + "memory_invalidation, memory_summary, memory_summary_job, relationship_change, faction_clock_change, "
                + "learned_recipe, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, "
                + "narration_publication, presentation_event, presentation_job, campaign_stream_message, "
                + "campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, "
                + "item_instance, learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, "
                + "quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, "
                + "operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, "
                + "campaign_event, operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * QA-27: the preview is exact and writes nothing; the commit keeps history, stores the backup and the approved
     * preview, cites them in an internal event, carries the beliefs, and invalidates only what depended on the claim.
     */
    @Test
    void previewIsExactAndTheCorrectionPreservesHistory() {
        UUID reader = admitPlan("knowledge:claims");
        UUID bystander = admitPlan("actor:" + warden);
        UUID covering = summary(claimEvent);
        UUID unrelated = summary(lastEvent());
        var history = events();
        var claimRow = row("knowledge_claim", claim);
        Request request = request(UUID.randomUUID().toString(), REASON);

        var before = written();
        var preview = corrections.preview(OWNER, adventure, campaign, request);
        assertEquals(before, written(), "a preview wrote a row");
        var again = corrections.preview(OWNER, adventure, campaign, request);
        assertEquals(preview, again, "equal state gives an equal preview");
        JsonNode impact = JSON.readTree(preview.document());
        assertEquals(CanonCorrections.PREVIEW_FORMAT, impact.get("format").asString());
        assertEquals(revision, impact.get("baseRevision").asLong());
        assertEquals("TRUE", impact.at("/claim/truth").asString());
        assertEquals("FALSE", impact.at("/successor/truth").asString());
        assertEquals(STATEMENT, impact.at("/successor/statement").asString());
        assertEquals(claimEvent, impact.at("/sourceEvents/0/sequence").asLong(), "the claim's source event is linked");
        Map<String, String> carried = new LinkedHashMap<>();
        impact.get("beliefs").forEach(entry -> carried.put(entry.get("holderId").asString(),
                entry.get("stance").asString() + ">" + entry.get("carriedStance").asString()));
        assertEquals(Map.of(player.toString(), "KNOWS>BELIEVES", warden.toString(), "BELIEVES>BELIEVES"), carried);
        assertEquals(List.of("knowledge:" + player, "knowledge:" + warden, "knowledge:claims").stream().sorted().toList(),
                texts(impact.get("queryScopes")));
        assertEquals(1, impact.at("/plans/changed").asInt());
        assertEquals(reader.toString(), impact.at("/plans/listed/0/artifactId").asString());
        assertEquals("INVALIDATED", impact.at("/plans/listed/0/status").asString());
        assertEquals(List.of(covering.toString()), texts(impact.at("/summaries/stale")));
        assertEquals(2, impact.at("/summaries/usable").asInt());

        CampaignExports.Export expected = exports.export(OWNER, adventure, campaign,
                CampaignExports.Audience.FULL_OWNER);
        var applied = corrections.apply(OWNER, adventure, campaign, request, preview.sha256());
        assertFalse(applied.replayed());
        assertEquals(revision + 1, applied.stateRevision());
        assertEquals(preview.sha256(), applied.previewSha256());
        assertEquals(expected.sha256(), applied.backupSha256());
        assertEquals(2, applied.beliefsCarried());
        assertEquals(1, applied.plansChanged());
        assertEquals(1, applied.summariesStale());

        // History: every earlier event is unchanged, and exactly one internal audit event follows them.
        var after = events();
        assertEquals(history, after.subList(0, history.size()), "an earlier event changed");
        assertEquals(history.size() + 1, after.size());
        var audit = jdbc.queryForMap("select sequence, event_type, visibility, state_revision, world_time_seconds, "
                + "payload::text as payload from campaign_event where campaign_id = ? and sequence = ?", campaign,
                applied.eventSequence());
        assertEquals(CanonCorrections.EVENT_TYPE, audit.get("event_type"));
        assertEquals("INTERNAL", audit.get("visibility"));
        assertEquals(applied.stateRevision(), ((Number) audit.get("state_revision")).longValue());
        JsonNode payload = JSON.readTree((String) audit.get("payload"));
        assertEquals(claim.toString(), payload.get("claimId").asString());
        assertEquals(applied.successorClaimId().toString(), payload.get("successorClaimId").asString());
        assertEquals(preview.sha256(), payload.get("previewSha256").asString());
        assertEquals(expected.sha256(), payload.get("backupSha256").asString());
        assertEquals(REASON, payload.get("reason").asString());
        var counters = jdbc.queryForMap("select game_turn, world_time_seconds, mode from campaign where id = ?", campaign);
        assertEquals(((Number) audit.get("world_time_seconds")).longValue(),
                ((Number) counters.get("world_time_seconds")).longValue(), "a correction moves no clock");

        // The corrected claim keeps its proposition and truth and is only closed; its successor cites the audit event.
        var closed = JSON.readTree(row("knowledge_claim", claim));
        var original = JSON.readTree(claimRow);
        for (String field : List.of("subject_ref", "predicate", "statement", "truth", "established_event_sequence",
                "truth_event_sequence")) {
            assertEquals(original.get(field), closed.get(field), field);
        }
        assertEquals(applied.stateRevision(), closed.get("superseded_revision").asLong());
        var successor = JSON.readTree(row("knowledge_claim", applied.successorClaimId()));
        assertEquals("FALSE", successor.get("truth").asString());
        assertEquals(claim.toString(), successor.get("supersedes").asString());
        assertEquals(applied.eventSequence(), successor.get("established_event_sequence").asLong());
        assertEquals(List.of("BELIEVES|" + applied.eventSequence(), "BELIEVES|" + applied.eventSequence()),
                jdbc.queryForList("select stance || '|' || learned_event_sequence from knowledge_belief where "
                        + "claim_id = ? and superseded_revision is null", String.class, applied.successorClaimId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_belief where claim_id = ? and "
                + "superseded_revision is null", Integer.class, claim), "old beliefs stay, closed");
        assertEquals(2, jdbc.queryForObject("select count(*) from knowledge_belief where claim_id = ?", Integer.class,
                claim));

        // Dependencies: only the plan and summary that read the claim are affected.
        assertEquals("INVALIDATED|QUERY_EPOCH_CHANGED", disposition(reader));
        assertTrue(disposition(bystander).startsWith("VALIDATED|"), "a plan that never read the claim stays valid");
        var stale = summaries.freshness(covering);
        assertEquals(MemorySummaries.Freshness.Status.STALE, stale.status());
        assertTrue(stale.reasons().contains("invalidated:" + CanonCorrections.INVALIDATION_REASON), stale.reasons()
                .toString());
        assertNotEquals(MemorySummaries.Freshness.Status.STALE, summaries.freshness(unrelated).status());

        // Audit and backup: the stored backup is the export of the corrected revision, byte for byte.
        var record = corrections.audit(OWNER, adventure, campaign, applied.operationId());
        assertEquals(revision, record.baseRevision());
        assertEquals(preview.document(), record.preview());
        assertEquals(preview.sha256(), record.previewSha256());
        assertArrayEquals(expected.document(), record.backup());
        assertEquals("COMMITTED|" + CanonCorrections.COMMAND_TYPE + "|" + applied.stateRevision(), jdbc.queryForObject(
                "select status || '|' || command_type || '|' || committed_revision from operation_journal where id = ?",
                String.class, applied.operationId()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "update canon_correction set base_revision = base_revision"), "the record is immutable");

        // A lost response replays the outcome; a reused identity with another payload or a preview is refused.
        var written = written();
        var replay = corrections.apply(OWNER, adventure, campaign, request, preview.sha256());
        assertTrue(replay.replayed());
        assertEquals(applied.operationId(), replay.operationId());
        assertEquals(applied.successorClaimId(), replay.successorClaimId());
        assertEquals(written, written(), "a replay wrote a row");
        assertEquals(Code.REQUEST_CONFLICT, refusal(() -> corrections.apply(OWNER, adventure, campaign,
                request(request.requestId(), "Another reason."), preview.sha256())));
        assertEquals(Code.REQUEST_USED, refusal(() -> corrections.preview(OWNER, adventure, campaign, request)));
        revision = applied.stateRevision();
        assertEquals(Code.CLAIM_UNAVAILABLE, refusal(() -> corrections.preview(OWNER, adventure, campaign,
                request(UUID.randomUUID().toString(), REASON))), "a superseded claim is no longer correctable");
        assertEquals(written, written(), "a refusal wrote a row");
    }

    /** Every refusal is decided before any write; an approval of an older impact is refused, not reinterpreted. */
    @Test
    void refusalsWriteNothing() {
        summary(claimEvent);
        String id = UUID.randomUUID().toString();
        var before = written();
        assertEquals(Code.NO_CHANGE, refusal(() -> corrections.preview(OWNER, adventure, campaign,
                new Request(id, revision, claim, Truth.TRUE, null, null, null, REASON))));
        assertEquals(Code.SHAPE, refusal(() -> corrections.preview(OWNER, adventure, campaign,
                new Request(id, revision, claim, Truth.FALSE, null, null, "1", REASON))));
        assertEquals(Code.STALE_REVISION, refusal(() -> corrections.preview(OWNER, adventure, campaign,
                new Request(id, revision - 1, claim, Truth.FALSE, null, null, null, REASON))));
        assertEquals(Code.CLAIM_UNAVAILABLE, refusal(() -> corrections.preview(OWNER, adventure, campaign,
                new Request(id, revision, UUID.randomUUID(), Truth.FALSE, null, null, null, REASON))));
        UUID stranger = UUID.randomUUID();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Stranger', 'en', now(), now())", stranger);
        Request request = request(id, REASON);
        assertEquals(Code.SCOPE_UNAVAILABLE, refusal(() -> corrections.preview(stranger, adventure, campaign, request)));
        assertEquals(Code.SCOPE_UNAVAILABLE, refusal(() -> corrections.preview(OWNER, UUID.randomUUID(), campaign,
                request)));
        assertEquals(Code.SCOPE_UNAVAILABLE, refusal(() -> corrections.apply(stranger, adventure, campaign, request,
                HASH)));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(
                status -> corrections.preview(OWNER, adventure, campaign, request)));
        assertThrows(IllegalArgumentException.class, () -> new Request(id, revision, claim, Truth.FALSE, "Text", null,
                null, REASON));

        // The approved impact changes when a new summary would also become stale: preview again.
        var preview = corrections.preview(OWNER, adventure, campaign, request);
        summary(claimEvent);
        before = written();
        assertEquals(Code.PREVIEW_CHANGED, refusal(() -> corrections.apply(OWNER, adventure, campaign, request,
                preview.sha256())));
        assertEquals(before, written());

        // A commit after the preview makes it stale before the backup is kept.
        var fresh = corrections.preview(OWNER, adventure, campaign, request);
        execute(new TypedCommand.Wait(60));
        before = written();
        assertEquals(Code.STALE_REVISION, refusal(() -> corrections.apply(OWNER, adventure, campaign, request,
                fresh.sha256())));
        assertEquals(before, written());

        // An admitted operation holds the mutation lease, so the campaign is busy.
        var admitted = admissionsOfCommands.submit(OWNER, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision, "WAIT", new TypedCommand.Wait(60).payload()));
        assertEquals(OperationStatus.ACCEPTED, admitted.operation().status());
        before = written();
        assertEquals(Code.CAMPAIGN_BUSY, refusal(() -> corrections.preview(OWNER, adventure, campaign,
                request(UUID.randomUUID().toString(), REASON))));
        assertEquals(before, written());
    }

    // --- helpers -------------------------------------------------------------------------------------------

    /** A correction of the planted claim to FALSE at the current revision. */
    private Request request(String id, String reason) {
        return new Request(id, revision, claim, Truth.FALSE, null, null, null, reason);
    }

    private Code refusal(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(CanonCorrections.Refused.class, action).code();
    }

    private void belief(UUID holder, String stance) {
        jdbc.update("insert into knowledge_belief (id, campaign_id, holder_actor_id, claim_id, stance, acquisition, "
                + "learned_revision, learned_world_time, learned_event_sequence) values (?, ?, ?, ?, ?, 'OBSERVED', ?, 60, "
                + "?)", UUID.randomUUID(), campaign, holder, claim, stance, revision, claimEvent);
    }

    /** A completed privileged continuity summary covering one event, with no sources or entities. */
    private UUID summary(long event) {
        var tokens = jdbc.queryForMap("select c.recovery_generation as generation, coalesce(p.narrative_locale, "
                + "w.source_locale) as locale, coalesce(p.locale_revision, 0) as locale_revision, "
                + "coalesce(p.glossary_revision, 0) as glossary_revision, c.state_revision, c.event_sequence from campaign c "
                + "join adventure_workspace w on w.id = c.adventure_id left join presentation_campaign_settings p on "
                + "p.campaign_id = c.id where c.id = ?", campaign);
        UUID job = UUID.randomUUID();
        UUID summary = UUID.randomUUID();
        jdbc.update("insert into memory_summary_job (id, adventure_id, campaign_id, campaign_generation, audience, "
                + "range_from, range_to, status, max_attempts, due_at, expires_at, created_at, updated_at) values (?, ?, ?, "
                + "?, 'privileged', ?, ?, 'QUEUED', 1, now(), now() + interval '1 hour', now(), now())", job, adventure,
                campaign, tokens.get("generation"), event, event);
        jdbc.update("insert into memory_summary (id, campaign_id, job_id, campaign_generation, audience, kind, "
                + "covered_from, covered_to, covered_events, source_revision, event_high_water, locale, locale_revision, "
                + "glossary_revision, invalidation_high_water, context_digest, template_hash, sources, entity_refs, body, "
                + "body_digest, created_at) values (?, ?, ?, ?, 'privileged', 'CONTINUITY', ?, ?, cast(? as bigint[]), ?, ?, "
                + "?, ?, ?, (select coalesce(max(sequence), 0) from memory_invalidation where campaign_id = ?), ?, ?, "
                + "'[]'::jsonb, '[]'::jsonb, '{}'::jsonb, ?, now())", summary, campaign, job, tokens.get("generation"),
                event, event, new Long[] {event}, tokens.get("state_revision"), tokens.get("event_sequence"),
                tokens.get("locale"), tokens.get("locale_revision"), tokens.get("glossary_revision"), campaign, HASH, HASH,
                HASH);
        jdbc.update("update memory_summary_job set status = 'COMPLETED', summary_id = ?, row_version = row_version + 1 "
                + "where id = ?", summary, job);
        assertNotEquals(MemorySummaries.Freshness.Status.STALE, summaries.freshness(summary).status());
        return summary;
    }

    /** Admit a private plan whose whole artifact depends on one query scope at its current epoch. */
    private UUID admitPlan(String scope) {
        var dependencies = List.of(Dependency.query("present-in-scope", "{}", Audience.PLANNER_PRIVATE, scope,
                QueryEpochs.current(jdbc, campaign, scope), HASH, Severity.ARTIFACT, "[\"interview\"]"));
        jobSequence++;
        var job = jobs.enqueue(adventure, campaign, new JobRequest("QUEST_PLAN", TARGET, "p" + jobSequence, "v1", HASH,
                HASH, HASH, HASH, 10 + jobSequence, false, Duration.ZERO, Duration.ofHours(1), 1, 1, 1));
        var lease = jobs.claim(UUID.randomUUID(), LEASE).orElseThrow();
        assertEquals(job.jobId(), lease.jobId());
        var tokens = jdbc.queryForMap("select c.state_revision, c.recovery_generation, coalesce(p.locale_revision, 0) "
                + "as locale, coalesce(p.glossary_revision, 0) as glossary from campaign c left join "
                + "presentation_campaign_settings p on p.campaign_id = c.id where c.id = ?", campaign);
        jobs.bindSnapshot(lease, snapshots.capture(adventure, campaign, new PlanningSnapshotService.SnapshotRequest(
                Audience.PLANNER_PRIVATE, "{}", "[]", dependencies, catalogs.preload(campaign).catalog()
                        .authorConstraintHash(), HASH, ((Number) tokens.get("state_revision")).longValue(),
                ((Number) tokens.get("recovery_generation")).longValue(), ((Number) tokens.get("locale")).longValue(),
                ((Number) tokens.get("glossary")).longValue())).id());
        jobs.start(lease, LEASE, 0, "thread", "turn");
        jobs.recordResult(lease, HASH, 1);
        var admission = admissions.admit(lease, new PlanAdmissionService.Proposal(UUID.randomUUID(), 1, TARGET, "v1",
                "{\"premise\":\"survey\"}", dependencies, "[\"interview\"]", "[]", "[]", "v1", HASH, References.NONE));
        assertEquals(PlanAdmissionService.Outcome.ACCEPTED, admission.outcome(), String.valueOf(admission.reason()));
        assertTrue(disposition(admission.artifactId()).startsWith("VALIDATED|"));
        return admission.artifactId();
    }

    private void execute(TypedCommand command) {
        var execution = executions.execute(OWNER, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision, command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
        revision = execution.operation().committedRevision();
    }

    private long lastEvent() {
        return jdbc.queryForObject("select max(sequence) from campaign_event where campaign_id = ?", Long.class,
                campaign);
    }

    private List<String> events() {
        return jdbc.queryForList("select to_jsonb(e)::text from campaign_event e where campaign_id = ? order by sequence",
                String.class, campaign);
    }

    private String row(String table, UUID id) {
        return jdbc.queryForObject("select to_jsonb(t)::text from " + table + " t where id = ?", String.class, id);
    }

    private String disposition(UUID artifact) {
        return jdbc.queryForObject("select status || '|' || reason_code from plan_disposition where artifact_id = ? "
                + "order by sequence desc limit 1", String.class, artifact);
    }

    private static List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private Map<String, List<String>> written() {
        var snapshot = new LinkedHashMap<String, List<String>>();
        for (String table : WRITTEN) {
            snapshot.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1",
                    String.class));
        }
        return snapshot;
    }
}
