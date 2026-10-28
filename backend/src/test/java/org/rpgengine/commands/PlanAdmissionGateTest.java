package org.rpgengine.commands;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ActorResource;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.preparation.Audience;
import org.rpgengine.preparation.JobRequest;
import org.rpgengine.preparation.PlanActivations;
import org.rpgengine.preparation.PlanAdmissionService;
import org.rpgengine.preparation.PlanAdmissionService.Dependency;
import org.rpgengine.preparation.PlanAdmissionService.Kind;
import org.rpgengine.preparation.PlanAdmissionService.Outcome;
import org.rpgengine.preparation.PlanAdmissionService.References;
import org.rpgengine.preparation.PlanAdmissionService.Severity;
import org.rpgengine.preparation.PlanContentCatalogs;
import org.rpgengine.preparation.PlanningSnapshotService;
import org.rpgengine.preparation.PreparationJobService;
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
 * Real PostgreSQL proof of the P07.6 transactional plan-admission gate and of plan activation
 * through ordinary canonical commands (QA-36, docs/28 AP-01 to AP-05 and AP-08), on a campaign
 * created by production code from sealed registered packs. World changes come from ordinary
 * player commands (WAIT, MOVE, USE_ABILITY) or from trusted internal commits standing in for
 * later writers (a reveal writer arrives with P09.1, item consumption with P10); plans only
 * record what they read. Every check compares exact rows, so an admission, invalidation or
 * refused activation that changed any world fact, counter, reward, draw or event would fail.
 */
@SpringBootTest
class PlanAdmissionGateTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HASH = "c".repeat(64);
    private static final String TARGET = "quest:survey";
    private static final Duration LEASE = Duration.ofSeconds(120);
    private static final String BRANCHES = "[\"interview\",\"document\",\"harbor\",\"reveal\"]";
    // Every table holding canonical world state, counters, rewards, draws, events or outbox intents.
    private static final List<String> WORLD = List.of("campaign", "actor", "actor_resource", "item_instance",
            "character_state", "learned_ability", "campaign_event", "canonical_outbox", "reward_settlement",
            "operation_random_draw", "campaign_known_fact", "campaign_known_quest");
    @Autowired private CommandExecutionService executions;
    @Autowired private CanonicalCommitService commits;
    @Autowired private MutationLeaseService leases;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private PreparationJobService jobs;
    @Autowired private PlanningSnapshotService snapshots;
    @Autowired private PlanAdmissionService admissions;
    @Autowired private PlanContentCatalogs catalogs;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID revisionId;
    private UUID campaign;
    private UUID player;
    private UUID warden;
    private UUID wardenVigor;
    private UUID kit;
    private String authorHash;
    // Distinct purposes and rising priorities keep each fixture job independent and claimed next.
    private int jobSequence;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    PlanAdmissionGateTest() { }

    /**
     * Register a dependency and a root pack (with a public warden and a secret hermit), create a
     * campaign through production code and place the warden with a vigor pool beside the player.
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
                List.of(npc("warden", null), npc("hermit", "secret")))));
        revisionId = register(root, "test:plan", List.of(CommandExecutionTest.pin(dependency))).revisionId();
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
        authorHash = catalogs.preload(campaign).catalog().authorConstraintHash();
        jobSequence = 0;
    }

    /** Remove disposable rows and fault-injection triggers, preserving production constraints. */
    @AfterEach
    void clear() {
        jdbc.execute("drop trigger if exists p076_fault on plan_activation");
        jdbc.execute("drop function if exists p076_fail()");
        jdbc.execute("truncate memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, "
                + "campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, "
                + "plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, "
                + "ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, "
                + "operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, "
                + "reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, "
                + "content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * AP-01: eight unrelated ordinary commits (revision r to r+8) leave every recorded read
     * unchanged, so the plan is admitted at the new revision with its checked references, and
     * neither admission nor invalidation changes any world row.
     */
    @Test
    void unrelatedRevisionsKeepThePlanAndAdmissionChangesNoWorldState() {
        var dependencies = witnessAndDocument();
        var lease = result(dependencies);
        long captured = revision();
        for (int turn = 0; turn < 8; turn++) {
            execute(new TypedCommand.Wait(60));
        }
        assertEquals(captured + 8, revision());
        var before = world();
        var references = new References(List.of("actor:" + warden, "item:" + kit),
                List.of("warden", "grove", "test:dep#sense"), List.of("trailhead", "kit"));
        var admission = admissions.admit(lease, proposal(dependencies, references));
        assertEquals(Outcome.ACCEPTED, admission.outcome());
        assertEquals(before, world());
        var receipt = jdbc.queryForMap("select gate_version, checked_revision, checked_references::text as refs "
                + "from plan_admission where id = ?", admission.admissionId());
        assertEquals(PlanAdmissionService.GATE_VERSION, receipt.get("gate_version"));
        assertEquals(captured + 8, receipt.get("checked_revision"));
        assertEquals(JSON.readTree("{\"entityRefs\":[\"actor:" + warden + "\",\"item:" + kit + "\"],"
                + "\"contentRefs\":[\"grove\",\"test:dep#sense\",\"warden\"],"
                + "\"authorConstraintRefs\":[\"kit\",\"trailhead\"]}"), JSON.readTree((String) receipt.get("refs")));
        assertEquals(0, admissions.invalidateStale(campaign));
        assertEquals("VALIDATED", status(admission.artifactId()));
        assertEquals(before, world());
        // The same delivery again replays the receipt; nothing new is stored.
        assertEquals(Outcome.DUPLICATE, admissions.admit(lease, proposal(dependencies, references)).outcome());
        assertEquals(1, count("plan_admission"));
    }

    /**
     * AP-02 and AP-08: an ordinary ability command changes the witness's pool, invalidating only
     * the dependent branch. That branch cannot be used, while the independent branch is used
     * exactly once by an ordinary canonical commit whose effects are only its own.
     */
    @Test
    void witnessChangeBlocksItsBranchWhileTheIndependentBranchActivatesOnce() {
        var dependencies = witnessAndDocument();
        UUID artifact = admissions.admit(result(dependencies), proposal(dependencies, References.NONE)).artifactId();
        execute(new TypedCommand.UseAbility("soothe", List.of(warden)));
        assertEquals("PARTIALLY_INVALIDATED|DEPENDENCY_CHANGED", disposition(artifact));

        var stale = internal(List.of(), List.of(activation(artifact, "interview")));
        var before = world();
        assertEquals("PLAN_DEPENDENCY_STALE", rejected(stale));
        assertEquals(before, world());
        assertEquals("VALIDATING", journalStatus(stale.operationId()));
        assertEquals(0, count("plan_activation"));
        expireLease();

        var used = internal(List.of(), List.of(activation(artifact, "document")));
        long base = revision();
        long events = eventSequence();
        var counters = counters();
        var receipt = commits.commit(used.plan());
        assertEquals(base + 1, receipt.stateRevision());
        assertEquals(List.of("document|" + used.operationId() + "|" + (base + 1)), jdbc.queryForList(
                "select concat_ws('|', branch_ref, operation_id, committed_revision) from plan_activation", String.class));
        // The plan contributed nothing: only the command's own revision and event; no turn,
        // time, reward, item, pool or placement change.
        assertEquals(counters.replace("rev=" + base + " ", "rev=" + (base + 1) + " ")
                .replace("seq=" + events + " ", "seq=" + (events + 1) + " "), counters());
        assertEquals(0, count("reward_settlement"));
        assertFalse(receipt.json().contains(artifact.toString()), "receipts never cite private plans");
        // A lost response replays the stored receipt; the branch stays used once.
        assertEquals(receipt, commits.commit(used.plan()));
        assertEquals(1, count("plan_activation"));
        var again = internal(List.of(), List.of(activation(artifact, "document")));
        assertEquals("PLAN_BRANCH_ALREADY_ACTIVATED", rejected(again));
        expireLease();
        assertEquals("PLAN_BRANCH_NOT_ACCEPTED", rejected(internal(List.of(), List.of(activation(artifact, "ambush")))));
        expireLease();
        assertEquals("PLAN_UNKNOWN", rejected(internal(List.of(), List.of(activation(UUID.randomUUID(), "document")))));
        expireLease();
        jdbc.update("update campaign set recovery_generation = recovery_generation + 1, row_version = row_version + 1 "
                + "where id = ?", campaign);
        assertEquals("PLAN_GENERATION_RETIRED", rejected(internal(List.of(), List.of(activation(artifact, "harbor")))));
    }

    /**
     * AP-04: a plan that read the player's position is admitted, then the position changes
     * through a writer that skipped the invalidation hook; the disposition still says
     * VALIDATED, but the activation's live recheck wins and rolls the whole command back.
     * A racing admission and ordinary move can never leave a usable stale artifact.
     */
    @Test
    void secondCheckAtUseWinsOverAnEarlierValidation() throws Exception {
        var dependencies = List.of(versioned(Kind.ENTITY_FIELD, "actor:" + player, version("actor", "id", player),
                Severity.ARTIFACT, "harbor"));
        UUID artifact = admissions.admit(result(dependencies), proposal(dependencies, References.NONE)).artifactId();
        jdbc.update("update actor set location_id = 'grove', row_version = row_version + 1 where id = ?", player);
        assertEquals("VALIDATED", status(artifact));
        var before = world();
        assertEquals("PLAN_DEPENDENCY_STALE", rejected(internal(List.of(), List.of(activation(artifact, "harbor")))));
        assertEquals(before, world());
        jdbc.update("update actor set location_id = 'trailhead', row_version = row_version + 1 where id = ?", player);
        expireLease();

        var racing = List.of(versioned(Kind.ENTITY_FIELD, "actor:" + player, version("actor", "id", player),
                Severity.ARTIFACT, "harbor"));
        var lease = result(racing);
        var outcomes = race(() -> admissions.admit(lease, proposal(racing, References.NONE)),
                () -> execute(new TypedCommand.Move("grove")));
        var admission = (PlanAdmissionService.Admission) outcomes.getFirst();
        if (admission.outcome() == Outcome.ACCEPTED) {
            assertEquals("INVALIDATED|DEPENDENCY_CHANGED", disposition(admission.artifactId()));
        } else {
            assertEquals("STALE_DEPENDENCY", admission.reason());
        }
        assertEquals(0, usableStaleArtifacts());
    }

    /**
     * AP-02/AP-03: a destroyed clue object and a revealed secret (a phantom in an empty
     * knowledge query) make pending results stale at admission, and the reveal commit itself
     * invalidates an already admitted plan that depended on the empty result.
     */
    @Test
    void destroyedObjectAndKnowledgePhantomRejectStaleAdmission() {
        var itemDependency = List.of(versioned(Kind.ENTITY_FIELD, "item:" + kit, version("item_instance", "id", kit),
                Severity.ARTIFACT, "document"));
        var itemLease = result(itemDependency);
        // Stand-in for a P10 consuming writer: the unique object no longer exists.
        jdbc.update("delete from item_instance where id = ?", kit);
        assertEquals("STALE_DEPENDENCY", admissions.admit(itemLease, proposal(itemDependency, References.NONE)).reason());

        String knowledge = "knowledge:" + player;
        var empty = List.of(query(knowledge, Severity.ARTIFACT, "reveal"));
        UUID artifact = admissions.admit(result(empty), proposal(empty, References.NONE)).artifactId();
        var pending = result(empty);
        var reveal = internal(List.of(knowledge), List.of());
        commits.commit(reveal.plan());
        assertEquals("INVALIDATED|QUERY_EPOCH_CHANGED", disposition(artifact));
        assertEquals("STALE_QUERY_EPOCH", admissions.admit(pending, proposal(empty, References.NONE)).reason());
        assertEquals("PLAN_NOT_USABLE", rejected(internal(List.of(), List.of(activation(artifact, "reveal")))));
        assertEquals(1, count("quest_plan_artifact"));
    }

    /**
     * Scope, content and author checks reject without storing an artifact or changing the
     * world; foreign identities are indistinguishable from absent ones, and dependencies
     * without an evaluator fail closed.
     */
    @Test
    void scopeContentAndAuthorChecksFailClosed() {
        UUID otherPlayer = creation.create(owner, adventure, new CampaignCreationService.Request(UUID.randomUUID(),
                revisionId, "Other", SupportedLocale.EN, new CharacterChoices("Other", "surveyor", Map.of(),
                Map.of(), null, null))).actorId();
        var before = world();
        var witness = List.of(query("actor:" + warden, Severity.ARTIFACT, "interview"));
        assertEquals("STALE_DEPENDENCY", reason(List.of(versioned(Kind.ENTITY_FIELD, "actor:" + otherPlayer, 0,
                Severity.ARTIFACT, "interview")), References.NONE));
        assertEquals("UNRECORDED_DEPENDENCY", reason(witness,
                new References(List.of("actor:" + player), List.of(), List.of())));
        UUID absent = UUID.randomUUID();
        assertEquals("UNKNOWN_REFERENCE", reason(List.of(query("actor:" + absent, Severity.ARTIFACT, "interview")),
                new References(List.of("actor:" + absent), List.of(), List.of())));
        assertEquals("UNKNOWN_CONTENT", reason(witness, new References(List.of(), List.of("cellar"), List.of())));
        assertEquals("UNKNOWN_AUTHOR_CONSTRAINT", reason(witness,
                new References(List.of(), List.of("warden"), List.of("warden"))));
        assertEquals("UNSUPPORTED_DEPENDENCY", reason(List.of(Dependency.version(Kind.CONSTRAINT, "author:trailhead",
                0, Severity.ARTIFACT, "[]")), References.NONE));
        assertEquals("UNSUPPORTED_DEPENDENCY", reason(List.of(new Dependency(Kind.ENTITY_FIELD, "actor:" + warden, 0L,
                "{\"alive\":true}", null, null, null, null, Severity.ARTIFACT, "[]")), References.NONE));
        assertEquals("UNSUPPORTED_DEPENDENCY", reason(List.of(Dependency.version(Kind.FACT, "actor:" + warden, 0,
                Severity.ARTIFACT, "[]")), References.NONE));
        // Public input may not cite a secret definition nor record a private read.
        var publicLease = result(Audience.PUBLIC, List.of());
        assertEquals("AUDIENCE_SCOPE", admissions.admit(publicLease, proposal(List.of(),
                new References(List.of(), List.of("hermit"), List.of()))).reason());
        assertThrows(IllegalArgumentException.class, () -> request(Audience.PUBLIC, witness, authorHash));
        // Capture recomputes author constraints from the sealed pin instead of trusting the assembler.
        assertThrows(IllegalStateException.class, () -> snapshots.capture(adventure, campaign,
                request(Audience.PLANNER_PRIVATE, witness, HASH)));
        assertThrows(IllegalArgumentException.class, () -> new References(List.of("region:north"), List.of(), List.of()));
        var oversized = new ArrayList<String>();
        for (int index = 0; index < 200; index++) {
            oversized.add(index + "x".repeat(200));
        }
        assertThrows(IllegalArgumentException.class, () -> new References(List.of(), oversized, List.of()));
        assertEquals(0, count("quest_plan_artifact"));
        assertEquals(0, count("plan_admission"));
        assertEquals(before, world());
    }

    /**
     * Snapshots recorded before capture-time checks (crafted here) still cannot pass the
     * transactional gate: another author-constraint digest, foreign pack hash, private reads in
     * public input or a campaign whose pinned content cannot be reloaded are rejected.
     */
    @Test
    void transactionalGateRejectsLegacySnapshotsWithoutWriting() {
        var witness = List.of(query("actor:" + warden, Severity.ARTIFACT, "interview"));
        assertEquals("STALE_AUTHOR_CONSTRAINTS", craftedReason(witness, "author_constraint_hash", "'" + "d".repeat(64) + "'"));
        assertEquals("STALE_CONTENT", craftedReason(witness, "pack_hash", "'" + "e".repeat(64) + "'"));
        assertEquals("AUDIENCE_SCOPE", craftedReason(witness, "audience", "'PUBLIC'"));

        UUID legacy = new TransactionTemplate(manager).execute(status -> {
            var content = ContentRevision.admit(adventure, new ContentRevision.PackCoordinates("legacy", "1",
                    "a".repeat(64), 1, SupportedLocale.EN), new ContentRevision.RulesetCoordinates("core", "1",
                    "b".repeat(64)), Instant.now());
            entities.persist(content);
            var started = Campaign.start(adventure, content, "Legacy", "seed", Instant.now());
            entities.persist(started);
            return started.getId();
        });
        assertThrows(IllegalStateException.class, () -> snapshots.capture(adventure, legacy,
                request(Audience.PLANNER_PRIVATE, List.of(), authorHash, legacy)));
        UUID crafted = UUID.randomUUID();
        jdbc.update("insert into planning_snapshot (id, campaign_id, campaign_generation, content_revision_id, "
                + "source_revision, event_high_water, world_time_seconds, locale_revision, glossary_revision, audience, "
                + "selected_state, source_refs, dependency_manifest, pack_hash, ruleset_hash, author_constraint_hash, "
                + "policy_hash, context_digest, created_at) select ?, c.id, c.recovery_generation, c.content_revision_id, "
                + "0, 0, 0, 0, 0, 'PLANNER_PRIVATE', '{}', '[]', '[]', ?, ?, ?, ?, ?, clock_timestamp() from campaign c "
                + "where c.id = ?", crafted, "a".repeat(64), "b".repeat(64), authorHash, HASH, HASH, legacy);
        var lease = bound(legacy, crafted);
        assertEquals("CONTENT_UNAVAILABLE", admissions.admit(lease, proposal(List.of(), References.NONE)).reason());
        assertEquals(0, count("plan_admission"));
    }

    /**
     * Transaction fault injection: a failure at COMMIT of an activating command leaves no
     * effect, receipt or activation and keeps the permit; the same command then commits once.
     * Storage guards refuse an activation without its committed command and any rewrite.
     */
    @Test
    void activationFaultRollsBackTheWholeCommandAndStorageGuardsHold() {
        var dependencies = witnessAndDocument();
        UUID artifact = admissions.admit(result(dependencies), proposal(dependencies, References.NONE)).artifactId();
        var command = internal(List.of(), List.of(activation(artifact, "document")));
        var lease = jdbc.queryForObject("select to_jsonb(l)::text from campaign_mutation_lease l", String.class);
        var before = world();
        jdbc.execute("create function p076_fail() returns trigger language plpgsql as $$ begin "
                + "if (select status from operation_journal where id = new.operation_id) <> 'COMMITTED' "
                + "then raise exception 'incomplete test bundle'; end if; "
                + "raise exception 'p076 injected commit failure'; end; $$");
        jdbc.execute("create constraint trigger p076_fault after insert on plan_activation "
                + "deferrable initially deferred for each row execute function p076_fail()");
        assertThrows(RuntimeException.class, () -> commits.commit(command.plan()));
        assertEquals(before, world());
        assertEquals(0, count("plan_activation"));
        assertEquals("VALIDATING", journalStatus(command.operationId()));
        assertEquals(lease, jdbc.queryForObject("select to_jsonb(l)::text from campaign_mutation_lease l", String.class));
        jdbc.execute("drop trigger p076_fault on plan_activation");
        long base = revision();
        assertEquals(base + 1, commits.commit(command.plan()).stateRevision());
        assertEquals(1, count("plan_activation"));
        assertThrows(RuntimeException.class, () -> jdbc.update("update plan_activation set branch_ref = 'harbor'"));
        assertThrows(RuntimeException.class, () -> jdbc.update("delete from plan_activation"));
        UUID uncommitted = internal(List.of(), List.of()).operationId();
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status ->
                jdbc.update("insert into plan_activation (artifact_id, branch_ref, campaign_id, operation_id, "
                        + "committed_revision, created_at) values (?, 'harbor', ?, ?, ?, clock_timestamp())", artifact,
                        campaign, uncommitted, base + 2)));
        assertEquals(1, count("plan_activation"));
    }

    /**
     * P08.7 manifest retention: the provisional validator may narrow captured whole-artifact
     * reads to the branches citing them, or to observed only for reads the plan never
     * references, but can never drop or alter an observation, weaken a captured branch guard
     * or leave a cited entity unguarded. Refusals write nothing; the narrowed rows are stored.
     */
    @Test
    void selectiveNarrowingKeepsEveryObservationAndNeverUnguardsACitedEntity() {
        long wardenVersion = version("actor", "id", warden);
        long kitVersion = version("item_instance", "id", kit);
        long playerVersion = version("actor", "id", player);
        var captured = List.of(versioned(Kind.ENTITY_FIELD, "actor:" + warden, wardenVersion, Severity.ARTIFACT),
                versioned(Kind.ENTITY_FIELD, "item:" + kit, kitVersion, Severity.BRANCH, "document"),
                versioned(Kind.ENTITY_FIELD, "actor:" + player, playerVersion, Severity.ARTIFACT));
        var cited = new References(List.of("actor:" + warden, "item:" + kit), List.of(), List.of());
        var lease = result(captured);
        var refusals = List.of(captured.subList(0, 2),
                List.of(captured.get(0), captured.get(1), versioned(Kind.ENTITY_FIELD, "actor:" + player,
                        playerVersion + 1, Severity.ARTIFACT)),
                List.of(versioned(Kind.ENTITY_FIELD, "actor:" + warden, wardenVersion, Severity.BRANCH),
                        captured.get(1), captured.get(2)),
                List.of(captured.get(0), versioned(Kind.ENTITY_FIELD, "item:" + kit, kitVersion, Severity.BRANCH,
                        "harbor"), captured.get(2)));
        for (var dependencies : refusals) {
            assertThrows(IllegalArgumentException.class, () -> admissions.admit(lease, proposal(dependencies, cited)));
        }
        assertEquals(0, count("quest_plan_artifact"));
        var narrowed = List.of(versioned(Kind.ENTITY_FIELD, "actor:" + warden, wardenVersion, Severity.BRANCH,
                        "interview"), versioned(Kind.ENTITY_FIELD, "item:" + kit, kitVersion, Severity.BRANCH,
                        "document", "harbor"), versioned(Kind.ENTITY_FIELD, "actor:" + player, playerVersion,
                        Severity.BRANCH));
        var admission = admissions.admit(lease, proposal(narrowed, cited));
        assertEquals(Outcome.ACCEPTED, admission.outcome());
        assertEquals(List.of("BRANCH|[\"interview\"]", "BRANCH|[\"document\", \"harbor\"]", "BRANCH|[]"),
                jdbc.queryForList("select severity || '|' || branch_refs::text from plan_dependency order by ordinal",
                        String.class));
        // An observed-only read changes no disposition and guards no branch.
        jdbc.update("update actor set location_id = 'grove', row_version = row_version + 1 where id = ?", player);
        assertEquals(0, admissions.invalidateStale(campaign));
        assertEquals("VALIDATED", status(admission.artifactId()));
    }

    /** Witness branch reads the warden row, its pool and its epoch scope; document branch reads the kit. */
    private List<Dependency> witnessAndDocument() {
        return List.of(versioned(Kind.ENTITY_FIELD, "actor:" + warden, version("actor", "id", warden), Severity.BRANCH,
                        "interview"),
                versioned(Kind.ENTITY_FIELD, "resource:" + wardenVigor, version("actor_resource", "id", wardenVigor),
                        Severity.BRANCH, "interview"),
                query("actor:" + warden, Severity.BRANCH, "interview"),
                versioned(Kind.ENTITY_FIELD, "item:" + kit, version("item_instance", "id", kit), Severity.BRANCH,
                        "document"));
    }

    /** Typed version dependency guarding the named branches. */
    private static Dependency versioned(Kind kind, String reference, long expected, Severity severity, String... branches) {
        return Dependency.version(kind, reference, expected, severity, JSON.writeValueAsString(List.of(branches)));
    }

    /** Query dependency on one scope at its current epoch, recorded as an empty result. */
    private Dependency query(String scope, Severity severity, String... branches) {
        return Dependency.query("present-in-scope", "{}", Audience.PLANNER_PRIVATE, scope,
                QueryEpochs.current(jdbc, campaign, scope), HASH, severity, JSON.writeValueAsString(List.of(branches)));
    }

    /** Version-1 proposal for a fresh plan with fixed accepted and rejected branches. */
    private static PlanAdmissionService.Proposal proposal(List<Dependency> dependencies, References references) {
        return new PlanAdmissionService.Proposal(UUID.randomUUID(), 1, TARGET, "v1", "{\"premise\":\"survey\"}",
                dependencies, BRANCHES, "[\"ambush\"]", "[]", "v1", HASH, references);
    }

    /** Admit a fresh private result for the dependencies and return the rejection reason or null. */
    private String reason(List<Dependency> dependencies, References references) {
        return admissions.admit(result(dependencies), proposal(dependencies, references)).reason();
    }

    /** Lease, snapshot, start and result for a private planning job of this campaign. */
    private PreparationJobService.Lease result(List<Dependency> dependencies) {
        return result(Audience.PLANNER_PRIVATE, dependencies);
    }

    /** Lease, snapshot of the given audience, start and result; returns a lease ready for admission. */
    private PreparationJobService.Lease result(Audience audience, List<Dependency> dependencies) {
        var lease = claim(campaign);
        jobs.bindSnapshot(lease, snapshots.capture(adventure, campaign, request(audience, dependencies, authorHash)).id());
        jobs.start(lease, LEASE, 0, "thread", "turn");
        jobs.recordResult(lease, HASH, 1);
        return lease;
    }

    /** Copy a captured snapshot with one column replaced by an SQL literal, then bind it to a fresh result. */
    private String craftedReason(List<Dependency> dependencies, String column, String literal) {
        var original = snapshots.capture(adventure, campaign, request(Audience.PLANNER_PRIVATE, dependencies, authorHash));
        UUID crafted = UUID.randomUUID();
        var columns = List.of("campaign_id", "campaign_generation", "content_revision_id", "source_revision",
                "event_high_water", "world_time_seconds", "locale_revision", "glossary_revision", "audience",
                "selected_state", "source_refs", "dependency_manifest", "pack_hash", "ruleset_hash",
                "author_constraint_hash", "policy_hash", "context_digest", "created_at");
        jdbc.update("insert into planning_snapshot (id, " + String.join(", ", columns) + ") select ?, "
                + String.join(", ", columns.stream().map(name -> name.equals(column) ? literal : name).toList())
                + " from planning_snapshot where id = ?", crafted, original.id());
        return admissions.admit(bound(campaign, crafted), proposal(dependencies, References.NONE)).reason();
    }

    /** Claim a fresh job of the campaign and bind an existing snapshot to it. */
    private PreparationJobService.Lease bound(UUID target, UUID snapshot) {
        var lease = claim(target);
        jobs.bindSnapshot(lease, snapshot);
        jobs.start(lease, LEASE, 0, "thread", "turn");
        jobs.recordResult(lease, HASH, 1);
        return lease;
    }

    /** Enqueue a one-attempt job with the highest priority so far and claim exactly it. */
    private PreparationJobService.Lease claim(UUID target) {
        jobSequence++;
        UUID owned = jdbc.queryForObject("select adventure_id from campaign where id = ?", UUID.class, target);
        var job = jobs.enqueue(owned, target, new JobRequest("QUEST_PLAN", TARGET, "p" + jobSequence, "v1", HASH,
                HASH, HASH, HASH, 10 + jobSequence, false, Duration.ZERO, Duration.ofHours(1), 1, 1, 1));
        var lease = jobs.claim(UUID.randomUUID(), LEASE).orElseThrow();
        assertEquals(job.jobId(), lease.jobId());
        return lease;
    }

    /** Snapshot request asserting the campaign's current tokens. */
    private PlanningSnapshotService.SnapshotRequest request(Audience audience, List<Dependency> dependencies,
            String constraints) {
        return request(audience, dependencies, constraints, campaign);
    }

    /** Snapshot request asserting another campaign's current tokens. */
    private PlanningSnapshotService.SnapshotRequest request(Audience audience, List<Dependency> dependencies,
            String constraints, UUID target) {
        var tokens = jdbc.queryForMap("select c.state_revision, c.recovery_generation, coalesce(p.locale_revision, 0) "
                + "as locale, coalesce(p.glossary_revision, 0) as glossary from campaign c left join "
                + "presentation_campaign_settings p on p.campaign_id = c.id where c.id = ?", target);
        return new PlanningSnapshotService.SnapshotRequest(audience, "{}", "[]", dependencies, constraints, HASH,
                ((Number) tokens.get("state_revision")).longValue(), ((Number) tokens.get("recovery_generation")).longValue(),
                ((Number) tokens.get("locale")).longValue(), ((Number) tokens.get("glossary")).longValue());
    }

    /** Activation request for one artifact. */
    private static PlanActivations.Request activation(UUID artifact, String... branches) {
        return new PlanActivations.Request(artifact, List.of(branches));
    }

    /** Journaled internal operation in VALIDATING with a fresh permit, plus its resolved commit. */
    private Internal internal(List<String> scopes, List<PlanActivations.Request> activations) {
        long base = revision();
        var operation = new TransactionTemplate(manager).execute(status -> {
            var created = CampaignOperation.accept(campaign, UUID.randomUUID().toString(), HASH, "plan-check", base,
                    Instant.now());
            created.transitionTo(OperationStatus.PLANNING, Instant.now());
            created.transitionTo(OperationStatus.VALIDATING, Instant.now());
            entities.persist(created);
            return created;
        });
        var grant = leases.acquire(adventure, campaign, operation.getId(), operation.getId(), Duration.ofSeconds(300));
        String mode = jdbc.queryForObject("select mode from campaign where id = ?", String.class, campaign);
        var plan = new ResolvedCommit(new ResolvedCommit.Context(adventure, campaign, operation.getId(), revisionId,
                HASH, base, operation.getOperationVersion(), grant.token()),
                new ResolvedCommit.WorldChange(false, 0, CampaignMode.valueOf(mode), null), List.of(),
                List.of(new ResolvedCommit.Event(null, null, EventVisibility.INTERNAL,
                        new CampaignEvent.Body("plan_checked", 1, 1, "{}"))),
                List.of(), "{}", false, new ResolvedCommit.Preparation(scopes, List.of(), activations));
        return new Internal(operation.getId(), plan);
    }

    /** Commit and return the activation rejection reason; any other outcome fails the test. */
    private String rejected(Internal command) {
        return assertThrows(PlanActivations.Rejected.class, () -> commits.commit(command.plan())).reason();
    }

    /** Retire the permit a refused internal command kept, as lease expiry would. */
    private void expireLease() {
        jdbc.update("update campaign_mutation_lease set expires_at = clock_timestamp() - interval '1 second' "
                + "where campaign_id = ? and expires_at is not null", campaign);
    }

    /** Execute one ordinary player intention at the current revision; it must commit. */
    private Object execute(TypedCommand command) {
        var execution = executions.execute(owner, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision(), command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status());
        return execution;
    }

    /** Artifacts whose latest disposition is usable although a dependency guarding the whole artifact is stale. */
    private int usableStaleArtifacts() {
        return jdbc.queryForObject("select count(*) from quest_plan_artifact a cross join lateral (select status "
                + "from plan_disposition where artifact_id = a.id order by sequence desc limit 1) d "
                + "where d.status in ('VALIDATED', 'PARTIALLY_INVALIDATED') and exists (select 1 from plan_dependency p "
                + "where p.artifact_id = a.id and p.severity = 'ARTIFACT' and not plan_dependency_fresh(a.campaign_id, p))",
                Integer.class);
    }

    /** Exact rows of every world table, ordered by their JSON rendering. */
    private Map<String, String> world() {
        var rows = new LinkedHashMap<String, String>();
        for (String table : WORLD) {
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return rows;
    }

    /** Campaign counters plus every pool, item and placement version, as one comparable line. */
    private String counters() {
        return jdbc.queryForObject("select concat('rev=', state_revision, ' turn=', game_turn, ' time=', "
                + "world_time_seconds, ' seq=', event_sequence, ' pools=', (select string_agg(id || ':' || current_value "
                + "|| ':' || row_version, ',' order by id) from actor_resource), ' items=', (select string_agg(id || ':' "
                + "|| quantity || ':' || row_version, ',' order by id) from item_instance), ' actors=', (select "
                + "string_agg(id || ':' || coalesce(location_id, '-') || ':' || row_version, ',' order by id) from actor)) "
                + "from campaign where id = ?", String.class, campaign);
    }

    /** Current campaign event sequence. */
    private long eventSequence() {
        return jdbc.queryForObject("select event_sequence from campaign where id = ?", Long.class, campaign);
    }

    /** Current canonical state revision. */
    private long revision() {
        return jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign);
    }

    /** Storage row version of one row identified by a constant column. */
    private long version(String table, String column, UUID id) {
        return jdbc.queryForObject("select row_version from " + table + " where " + column + " = ?", Long.class, id);
    }

    /** Latest disposition status of an artifact. */
    private String status(UUID artifact) {
        return disposition(artifact).split("\\|")[0];
    }

    /** Latest disposition as {@code status|reason}. */
    private String disposition(UUID artifact) {
        return jdbc.queryForObject("select status || '|' || reason_code from plan_disposition where artifact_id = ? "
                + "order by sequence desc limit 1", String.class, artifact);
    }

    /** Journal status of one operation. */
    private String journalStatus(UUID operation) {
        return jdbc.queryForObject("select status from operation_journal where id = ?", String.class, operation);
    }

    /** Row count of a constant table. */
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** NPC definition at the trailhead with optional knowledge visibility. */
    private static Map<String, Object> npc(String id, String knowledge) {
        var data = new LinkedHashMap<String, Object>(Map.of("name", "NPC " + id, "role", "Trail keeper",
                "location", "trailhead"));
        if (knowledge != null) {
            data.put("knowledge", knowledge);
        }
        return Map.of("id", id, "kind", "npc", "data", data);
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

    /** Start two independent calls together and propagate every failure within a bounded timeout. */
    private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (Callable<?> work : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return work.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.getFirst().get(30, TimeUnit.SECONDS), futures.getLast().get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
        }
    }

    /** Internal operation identity and its resolved commit. */
    private record Internal(UUID operationId, ResolvedCommit plan) { }
}
