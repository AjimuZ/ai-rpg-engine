package org.rpgengine.commands;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.rpgengine.characters.ActorResource;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.inference.contract.ContractRunner;
import org.rpgengine.inference.scheduling.InferenceScheduler;
import org.rpgengine.inference.scheduling.SchedulerSettings;
import org.rpgengine.inference.scheduling.TaskLedger;
import org.rpgengine.inference.session.ScopedSessions;
import org.rpgengine.planning.PlanningSettings;
import org.rpgengine.planning.QuestPlanAssembler;
import org.rpgengine.planning.QuestPlanning;
import org.rpgengine.planning.QuestPlanning.Processed;
import org.rpgengine.planning.QuestPlanning.Standing;
import org.rpgengine.preparation.PlanAdmissionService;
import org.rpgengine.preparation.PreparationJobService;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of optional bounded quest planning (P08.7; docs/28 AP-01, AP-02, AP-04,
 * AP-07, AP-08 and AP-11; QA-36): a campaign created by production code from sealed registered
 * packs, the real assembler, snapshot service, preparation jobs, durable fair scheduler ledger,
 * contract runner, transactional admission gate and canonical commands, with only the planner
 * runtime scripted ({@link PlannerTaskPort}). World changes come from ordinary player commands
 * (WAIT and the {@code soothe} ability on the witness); a quest writer does not exist yet (P10),
 * so the root change is a trusted direct update followed by the canonical invalidation hook.
 */
@SpringBootTest
class QuestPlanningTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET_PROPOSAL = "A surveyor moved the stones at night.";
    // Tables a player can ever receive text from; secret plan text must never reach them.
    private static final List<String> PUBLIC_SINKS = List.of("campaign_event", "canonical_outbox", "operation_journal",
            "campaign_stream_message", "narration_publication", "presentation_job");
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private PreparationJobService jobs;
    @Autowired private PlanAdmissionService admissions;
    @Autowired private QuestPlanAssembler assembler;
    @Autowired private QuestPlanning springPlanning;
    @Autowired private TaskLedger ledger;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private UUID player;
    private UUID warden;
    private UUID wardenVigor;
    private UUID kit;
    private String target;
    private PlannerTaskPort port;
    private final UUID worker = UUID.randomUUID();

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    QuestPlanningTest() { }

    /**
     * Register the dependency and root packs, create a campaign through production code, place
     * the warden with a vigor pool beside the player and give the player one known quest.
     */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Plans", "plans", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        var fixture = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI())).files());
        var dependency = register(CommandExecutionTest.dependencyPack(fixture), "test:dep", List.of());
        var root = CommandExecutionTest.rootPack(fixture);
        root.put("npcs.json", JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions",
                List.of(Map.of("id", "warden", "kind", "npc", "data", Map.of("name", "Warden", "role", "Trail keeper",
                        "location", "trailhead"))))));
        UUID revisionId = register(root, "test:plan", List.of(CommandExecutionTest.pin(dependency))).revisionId();
        var created = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(),
                revisionId, "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(),
                Map.of(), null, null)));
        campaign = created.campaignId();
        player = created.actorId();
        warden = new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, "warden", "Warden", "trailhead", 0, Instant.now());
            entities.persist(actor);
            var pool = ActorResource.create(actor, "vigor", 0, 2, 6, 0, Instant.now());
            entities.persist(pool);
            wardenVigor = pool.getId();
            return actor.getId();
        });
        kit = jdbc.queryForObject("select id from item_instance where campaign_id = ? and actor_id = ? "
                + "order by id limit 1", UUID.class, campaign, player);
        UUID quest = UUID.randomUUID();
        jdbc.update("insert into campaign_known_quest (id, campaign_id, actor_id, title, objective, status, "
                + "updated_revision) values (?, ?, ?, 'Boundary stones', 'Find who moved the boundary stones.', "
                + "'ACTIVE', ?)", quest, campaign, player, revision());
        target = "known-quest:" + quest;
        port = new PlannerTaskPort();
    }

    /** Remove disposable rows, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate inference_task, narration_publication, presentation_job, campaign_stream_message, "
                + "campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, "
                + "item_instance, learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, "
                + "quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, "
                + "operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, "
                + "reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, "
                + "content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * Economy default (AP-07 with planning off): the Spring service is disabled, writes nothing,
     * runs nothing and reports the baseline, while ordinary commands commit unchanged.
     */
    @Test
    void disabledPlanningIsTheDefaultAndLeavesThePlayableBaselineUntouched() {
        assertFalse(springPlanning.settings().enabled());
        assertEquals(QuestPlanning.Request.Outcome.DISABLED, springPlanning.request(adventure, campaign, target).outcome());
        assertTrue(springPlanning.processNext(worker).isEmpty());
        assertEquals(0, springPlanning.replan(campaign));
        assertEquals("PLANNING_DISABLED", springPlanning.standing(campaign, target).reason());
        execute(new TypedCommand.Wait(60));
        assertEquals(0, count("ai_job") + count("planning_snapshot") + count("quest_plan_artifact")
                + count("inference_task"));
        assertThrows(IllegalArgumentException.class, () -> springPlanning.request(adventure, campaign, "quest:other"));
    }

    /**
     * AP-01, AP-07, AP-08 and AP-11: play continues while the plan is pending; the plan runs as
     * privileged preparation work through the fair scheduler, is admitted with reads narrowed to
     * the branches whose predicates cite them, changes no world row and leaks nothing to a public
     * sink; unrelated revisions keep it; a retired generation stops its use.
     */
    @Test
    void aPlanIsAdmittedAsPrivilegedPreparationWithSelectiveDependencies() {
        var planning = planning(enabled());
        assertEquals(QuestPlanning.Request.Outcome.REQUESTED, planning.request(adventure, campaign, target).outcome());
        assertEquals("PLAN_PENDING", planning.standing(campaign, target).reason());
        // The playable baseline never waits for preparation.
        execute(new TypedCommand.Wait(60));
        var before = world();
        port.then(PlannerTaskPort::planned);
        Processed processed = planning.processNext(worker).orElseThrow();
        assertEquals(Processed.Outcome.ADMITTED, processed.outcome(), String.valueOf(processed.code()));
        assertEquals(before, world(), "planning and admission change no canonical row");
        var task = jdbc.queryForMap("select role, audience, work_class, status from inference_task");
        assertEquals("quest-planner|privileged|PREPARATION|COMPLETED", String.join("|", task.get("role").toString(),
                task.get("audience").toString(), task.get("work_class").toString(), task.get("status").toString()));
        assertEquals("quest-plan", port.seen.getFirst().envelope().path("taskKind").asString());
        assertEquals("PLANNER_PRIVATE", jdbc.queryForObject("select audience from planning_snapshot", String.class));
        // Selective dependencies: the witness rows guard only the interview, the kit only the
        // document route, the quest the whole plan; the player's own rows are observed only.
        assertEquals(List.of("[\"interview\"]"), severities("actor:" + warden, "BRANCH").stream().distinct().toList());
        assertEquals(List.of("[\"interview\"]"), severities("resource:" + wardenVigor, "BRANCH"));
        assertEquals(List.of("[\"document\"]"), severities("item:" + kit, "BRANCH"));
        assertEquals(List.of("[]"), severities(target, "ARTIFACT"));
        assertEquals(List.of("[]", "[]"), severities("actor:" + player, "BRANCH"));
        assertEquals(QuestPlanning.planId(campaign, target), jdbc.queryForObject("select plan_id from quest_plan_artifact",
                UUID.class));
        var standing = planning.standing(campaign, target);
        assertEquals(Standing.Mode.PREPARED, standing.mode());
        assertEquals(List.of("interview", "document"), standing.usableBranches());
        assertNoPublicLeak();
        // AP-01: unrelated revisions keep every branch; nothing to replan.
        for (int turn = 0; turn < 8; turn++) {
            execute(new TypedCommand.Wait(60));
        }
        assertEquals("VALIDATED", status(processed.artifactId()));
        assertEquals(0, planning.replan(campaign));
        assertEquals(List.of("interview", "document"), planning.standing(campaign, target).usableBranches());
        // A restore-style generation change retires the preparation; play stays on the baseline.
        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1 "
                + "where id = ?", campaign);
        assertEquals("PLAN_INVALIDATED", planning.standing(campaign, target).reason());
    }

    /**
     * AP-02 and selective replanning: an ordinary ability on the witness invalidates only the
     * interview; the document route stays usable; one refinement is requested for that version
     * (never again for unrelated revisions), runs as {@code quest-plan-refine} with the stale and
     * usable branches, and supersedes the predecessor; the replan allowance bounds later changes.
     */
    @Test
    void aWitnessChangeInvalidatesOnlyItsBranchAndReplansWithinTheAllowance() {
        var planning = planning(new PlanningSettings(true, PlanningSettings.defaults().slot(),
                Duration.ofSeconds(60), Duration.ofSeconds(120), 2, Duration.ofHours(1), 1, Duration.ofDays(7), 100));
        UUID first = admitFirst(planning);
        execute(new TypedCommand.UseAbility("soothe", List.of(warden)));
        assertEquals("PARTIALLY_INVALIDATED|DEPENDENCY_CHANGED", disposition(first));
        assertEquals(List.of("document"), planning.standing(campaign, target).usableBranches());
        assertEquals(1, planning.replan(campaign));
        assertEquals(0, planning.replan(campaign), "one refinement per artifact version");
        execute(new TypedCommand.Wait(60));
        assertEquals(0, planning.replan(campaign), "unrelated revisions never add work");
        port.then(PlannerTaskPort::planned);
        Processed refined = planning.processNext(worker).orElseThrow();
        assertEquals(Processed.Outcome.ADMITTED, refined.outcome(), String.valueOf(refined.code()));
        var request = port.seen.getLast();
        assertEquals("quest-plan-refine", request.envelope().path("taskKind").asString());
        assertEquals("[\"interview\"]", request.context().path("refinement").path("staleBranches").toString());
        assertEquals("[\"document\"]", request.context().path("refinement").path("usableBranches").toString());
        assertEquals("SUPERSEDED|NEW_VERSION", disposition(first));
        var standing = planning.standing(campaign, target);
        assertEquals(2, standing.version());
        assertEquals(List.of("v2-interview", "v2-document"), standing.usableBranches());
        // Another witness change: the allowance (one refinement) is spent, so the quest keeps its
        // surviving branch and baseline; nothing more is requested. A second soothe can leave a
        // capped pool unchanged, so a trusted writer stands in deterministically.
        new TransactionTemplate(manager).executeWithoutResult(status -> jdbc.update("update actor_resource set "
                + "current_value = 1, row_version = row_version + 1 where id = ?", wardenVigor));
        assertEquals(1, admissions.invalidateStale(campaign));
        assertEquals(List.of("v2-document"), planning.standing(campaign, target).usableBranches());
        assertEquals(0, planning.replan(campaign));
        assertEquals(2, count("ai_job"));
    }

    /**
     * The worker thread is off with planning off; one enabled pass sweeps the stale campaign,
     * requests its refinement and runs it.
     */
    @Test
    void oneWorkerPassReplansAStaleCampaignAndRunsTheRefinement() {
        var idle = new org.rpgengine.planning.QuestPlanWorker(springPlanning, jdbc, true, 1000);
        idle.start();
        assertFalse(idle.isRunning(), "no worker thread while planning is disabled");
        var planning = planning(enabled());
        UUID first = admitFirst(planning);
        execute(new TypedCommand.UseAbility("soothe", List.of(warden)));
        port.then(PlannerTaskPort::planned);
        new org.rpgengine.planning.QuestPlanWorker(planning, jdbc, false, 1000).pass();
        assertEquals("SUPERSEDED|NEW_VERSION", disposition(first));
        assertEquals("refine-v1", jdbc.queryForObject("select purpose from ai_job where status = 'COMPLETED' "
                + "and purpose <> 'initial'", String.class));
        assertEquals(2, planning.standing(campaign, target).version());
    }

    /**
     * A root change (the quest itself) invalidates the whole plan; play continues on the
     * baseline and one regeneration is requested.
     */
    @Test
    void aRootChangeInvalidatesTheWholePlanAndTheBaselineContinues() {
        var planning = planning(enabled());
        UUID first = admitFirst(planning);
        new TransactionTemplate(manager).executeWithoutResult(status -> jdbc.update("update campaign_known_quest "
                + "set objective = objective || ' Hurry.', row_version = row_version + 1 where campaign_id = ?",
                campaign));
        assertEquals(1, admissions.invalidateStale(campaign));
        assertEquals("INVALIDATED|DEPENDENCY_CHANGED", disposition(first));
        assertEquals("PLAN_INVALIDATED", planning.standing(campaign, target).reason());
        execute(new TypedCommand.Wait(60));
        assertEquals(1, planning.replan(campaign));
        assertEquals("PLAN_PENDING", planning.standing(campaign, target).reason());
    }

    /**
     * AP-07 with the planner unavailable, invalid or declining: bounded attempts, no artifact,
     * a baseline standing with a reason, and ordinary commands committing throughout.
     */
    @Test
    void anUnavailableInvalidOrDecliningPlannerEndsBoundedWhilePlayContinues() {
        var planning = planning(enabled());
        planning.request(adventure, campaign, target);
        port.then(seen -> null);
        assertEquals(Processed.Outcome.FAILED, planning.processNext(worker).orElseThrow().outcome());
        execute(new TypedCommand.Wait(60));
        // The single repair is used inside the second attempt; both outputs stay invalid.
        port.then(PlannerTaskPort::unconstrained).then(PlannerTaskPort::unconstrained);
        assertEquals(Processed.Outcome.INVALID, planning.processNext(worker).orElseThrow().outcome());
        assertTrue(planning.processNext(worker).isEmpty(), "two attempts in total, then the job fails");
        assertEquals("FAILED", jdbc.queryForObject("select status from ai_job", String.class));
        assertEquals("PLAN_FAILED", planning.standing(campaign, target).reason());
        assertEquals(3, port.seen.size());
        assertTrue(port.seen.get(2).request().instructions().contains("REPAIR:"));
        execute(new TypedCommand.Wait(60));
        assertEquals(0, count("quest_plan_artifact"));

        String second = newQuest();
        planning.request(adventure, campaign, second);
        port.then(PlannerTaskPort::declined);
        var declined = planning.processNext(worker).orElseThrow();
        assertEquals("DECLINED|PLANNER_DECLINED", declined.outcome() + "|" + declined.code());
        assertEquals("PLAN_FAILED", planning.standing(campaign, second).reason());
        assertEquals(0, count("quest_plan_artifact"));
    }

    /**
     * AP-04: a world change while the planner runs (after capture, before admission) makes the
     * transactional gate reject the stale proposal; the one regeneration then admits a fresh plan.
     * A cancellation request during generation discards the result.
     */
    @Test
    void aChangeDuringGenerationIsRejectedByTheGateAndCancellationDiscardsTheResult() {
        var planning = planning(enabled());
        planning.request(adventure, campaign, target);
        port.then(seen -> {
            execute(new TypedCommand.UseAbility("soothe", List.of(warden)));
            return PlannerTaskPort.planned(seen);
        });
        var stale = planning.processNext(worker).orElseThrow();
        assertEquals("GATE_REJECTED|STALE_QUERY_EPOCH", stale.outcome() + "|" + stale.code());
        assertEquals(0, count("quest_plan_artifact"));
        port.then(PlannerTaskPort::planned);
        assertEquals(Processed.Outcome.ADMITTED, planning.processNext(worker).orElseThrow().outcome());

        // An unknown quest fails closed before anything reaches the planner.
        var unknown = planning.request(adventure, campaign, "known-quest:" + UUID.randomUUID());
        var refused = planning.processNext(worker).orElseThrow();
        assertEquals("FAILED|UNSUPPORTED_TARGET", refused.outcome() + "|" + refused.code());
        assertEquals(unknown.jobId(), refused.jobId());

        var again = planning.request(adventure, campaign, newQuest());
        port.then(seen -> {
            jobs.requestCancel(campaign, again.jobId());
            return PlannerTaskPort.planned(seen);
        });
        long artifacts = count("quest_plan_artifact");
        var result = planning.processNext(worker).orElseThrow();
        assertEquals(Processed.Outcome.CANCELLED, result.outcome());
        assertEquals("CANCELLED", jdbc.queryForObject("select status from ai_job where id = ?", String.class,
                again.jobId()));
        assertEquals(artifacts, count("quest_plan_artifact"));
    }

    /** Give the player another known quest; returns its target. */
    private String newQuest() {
        UUID quest = UUID.randomUUID();
        jdbc.update("insert into campaign_known_quest (id, campaign_id, actor_id, title, objective, status, "
                + "updated_revision) values (?, ?, ?, 'Lost map', 'Recover the lost map.', 'ACTIVE', ?)", quest,
                campaign, player, revision());
        return "known-quest:" + quest;
    }

    /** Request and admit the first plan version; returns its artifact. */
    private UUID admitFirst(QuestPlanning planning) {
        planning.request(adventure, campaign, target);
        port.then(PlannerTaskPort::planned);
        Processed processed = planning.processNext(worker).orElseThrow();
        assertEquals(Processed.Outcome.ADMITTED, processed.outcome(), String.valueOf(processed.code()));
        return processed.artifactId();
    }

    /** Planning over the real ledger and gate, with the scripted planner behind a real scheduler. */
    private QuestPlanning planning(PlanningSettings settings) {
        var scheduler = new InferenceScheduler(new ScopedSessions(port, 16), port, ledger, SchedulerSettings.defaults(),
                Clock.systemUTC(), () -> 0.5);
        scheduler.recover();
        return new QuestPlanning(jdbc, jobs, admissions, assembler, new ContractRunner(scheduler, Clock.systemUTC()),
                settings);
    }

    private static PlanningSettings enabled() {
        return PlanningSettings.defaults().withEnabled(true);
    }

    /** Branch references of the stored dependencies on one reference (or query scope) with a severity. */
    private List<String> severities(String reference, String severity) {
        return jdbc.queryForList("select branch_refs::text from plan_dependency where (reference = ? or scope_key = ?) "
                + "and severity = ? order by ordinal", String.class, reference, reference, severity);
    }

    /** No player-reachable table contains secret plan text. */
    private void assertNoPublicLeak() {
        for (String table : PUBLIC_SINKS) {
            String rows = jdbc.queryForObject("select coalesce(string_agg(to_jsonb(t)::text, ''), '') from " + table
                    + " t", String.class);
            assertFalse(rows.contains(SECRET_PROPOSAL), table);
        }
        assertTrue(jdbc.queryForObject("select body::text from quest_plan_artifact", String.class)
                .contains(SECRET_PROPOSAL), "the private artifact keeps its secret proposal");
    }

    /** Execute one ordinary player intention at the current revision; it must commit. */
    private void execute(TypedCommand command) {
        var execution = executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision(), command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status());
    }

    /** Exact rows of every canonical world table. */
    private Map<String, String> world() {
        var rows = new LinkedHashMap<String, String>();
        for (String table : List.of("campaign", "actor", "actor_resource", "item_instance", "character_state",
                "campaign_event", "canonical_outbox", "reward_settlement", "operation_random_draw",
                "campaign_known_fact", "campaign_known_quest")) {
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return rows;
    }

    private long revision() {
        return jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign);
    }

    private String status(UUID artifact) {
        return disposition(artifact).split("\\|")[0];
    }

    private String disposition(UUID artifact) {
        return jdbc.queryForObject("select status || '|' || reason_code from plan_disposition where artifact_id = ? "
                + "order by sequence desc limit 1", String.class, artifact);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
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
