package org.rpgengine.commands;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ActorResource;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of the P07.3 current-state rules on a campaign created from a sealed,
 * registered pack closure. The root pack adds authored links (open, blocked, secret, absent),
 * public and secret NPC origins and abilities exercising ownership, ability and skill
 * prerequisites, every target policy and resource costs; a dependency pack supplies
 * abilities whose costs belong to an independent resource vocabulary. Every rejection is
 * compared against an exact snapshot of all touched tables, so no rule failure can journal,
 * consume a permit, retire earlier work or change world state.
 */
@SpringBootTest
class CommandRulesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private OperationAdmissionService admissions;
    @Autowired private CommandRuleCatalogs catalogs;
    @Autowired private CanonicalCommitService commits;
    @Autowired private MutationLeaseService leases;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID revision;
    private UUID campaign;
    private UUID player;
    private UUID vigor;
    private UUID warden;
    private UUID lurker;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    CommandRulesTest() { }

    /**
     * Register the dependency and root packs through real staging and sealing, create one campaign
     * through the production creation service, then place a public and a secret NPC beside the player.
     */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Rules", "rules", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        var fixture = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI())).files());
        var dependency = register(dependencyPack(fixture), "test:dep", List.of());
        revision = register(rootPack(fixture), "test:rules", List.of(pin(dependency))).revisionId();
        var created = creation.create(owner, adventure, request(revision));
        campaign = created.campaignId();
        player = created.actorId();
        vigor = jdbc.queryForObject("select id from actor_resource where actor_id = ? and resource_key = 'vigor'",
                UUID.class, player);
        warden = npc(campaign, "warden", "Warden", "trailhead");
        lurker = npc(campaign, "lurker", "Lurker", "trailhead");
    }

    /** Remove disposable test rows, preserving production constraints and triggers. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, "
                + "ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /** Only a disclosed destination behind an accessible authored link from the current location is reachable. */
    @Test
    void movementFollowsOnlyDisclosedAccessibleAuthoredLinks() {
        var before = snapshot();
        // Current, blocked, secret, unlinked, unknown and dependency-namespaced destinations share one category.
        for (String destination : List.of("trailhead", "ridge", "cellar", "tower", "nowhere", "test:dep#grove")) {
            assertFailure(OperationAdmissionFailure.Kind.DESTINATION_UNAVAILABLE,
                    () -> submit(new TypedCommand.Move(destination)));
            assertEquals(before, snapshot(), destination);
        }
        admit(new TypedCommand.Move("grove"));
        // A location outside the pinned closure, or none at all, cannot anchor a route.
        for (String location : new String[] {"camp", null}) {
            jdbc.update("update actor set location_id = ?, row_version = row_version + 1 where id = ?", location, player);
            before = snapshot();
            assertFailure(OperationAdmissionFailure.Kind.DESTINATION_UNAVAILABLE,
                    () -> submit(new TypedCommand.Move("grove")));
            assertEquals(before, snapshot());
        }
    }

    /** Speech reaches only another co-present campaign actor whose authored origin is disclosed. */
    @Test
    void speechRequiresAPerceivedCoPresentRecipient() throws Exception {
        admit(new TypedCommand.Speak(warden, "Good morning"));
        UUID foreign = creation.create(owner, adventure, request(revision)).actorId();
        UUID unknownOrigin = npc(campaign, "ghost", "Ghost", "trailhead");
        UUID elsewhere = npc(campaign, "warden", "Second warden", "grove");
        var before = snapshot();
        for (UUID recipient : List.of(player, lurker, foreign, unknownOrigin, elsewhere, UUID.randomUUID())) {
            assertFailure(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE,
                    () -> submit(new TypedCommand.Speak(recipient, "hello")));
            assertEquals(before, snapshot());
        }
        // Range is current state: the same public NPC is unreachable once it leaves the location.
        jdbc.update("update actor set location_id = 'grove', row_version = row_version + 1 where id = ?", warden);
        before = snapshot();
        assertFailure(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE,
                () -> submit(new TypedCommand.Speak(warden, "hello")));
        assertEquals(before, snapshot());
    }

    /** Ownership, ability/skill prerequisites, target policy, target perception and costs fail in that order. */
    @Test
    void abilityRulesApplyOwnershipPrerequisitesTargetsAndCosts() {
        var before = snapshot();
        Map<TypedCommand, OperationAdmissionFailure.Kind> rejected = new LinkedHashMap<>();
        rejected.put(use("nowhere"), OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE);
        // Defined in the pinned pack but never learned by this player.
        rejected.put(use("ward"), OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE);
        // A grant is not a substitute for the prerequisite ability or the skill rank.
        rejected.put(use("rally"), OperationAdmissionFailure.Kind.PREREQUISITE_UNMET);
        rejected.put(use("surge"), OperationAdmissionFailure.Kind.PREREQUISITE_UNMET);
        rejected.put(use("steady", warden), OperationAdmissionFailure.Kind.TARGET_ILLEGAL);
        rejected.put(use("focus"), OperationAdmissionFailure.Kind.TARGET_ILLEGAL);
        rejected.put(use("focus", warden, lurker), OperationAdmissionFailure.Kind.TARGET_ILLEGAL);
        rejected.put(use("volley"), OperationAdmissionFailure.Kind.TARGET_ILLEGAL);
        rejected.put(use("volley", warden, lurker, player), OperationAdmissionFailure.Kind.TARGET_ILLEGAL);
        rejected.put(use("quake", warden), OperationAdmissionFailure.Kind.TARGET_ILLEGAL);
        rejected.put(use("focus", lurker), OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
        rejected.put(use("focus", UUID.randomUUID()), OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
        // Nine vigor exceeds the eight available above the minimum.
        rejected.put(use("volley", warden), OperationAdmissionFailure.Kind.RESOURCE_INSUFFICIENT);
        // The dependency's "vigor" is another pack's vocabulary even though the key matches.
        rejected.put(use("test:dep#mend"), OperationAdmissionFailure.Kind.RESOURCE_INSUFFICIENT);
        rejected.forEach((command, kind) -> {
            assertFailure(kind, () -> submit(command));
            assertEquals(before, snapshot(), command.toString());
        });
        admit(use("steady"));
        admit(use("test:dep#steady"));
        // The player may target itself with a single-target ability it can afford.
        admit(use("focus", player));
        jdbc.update("update character_state set skills = jsonb_set(skills, '{fieldwork}', '3'), "
                + "row_version = row_version + 1 where actor_id = ?", player);
        admit(use("surge"));
        // Exactly the cost above the minimum is affordable; one less is not.
        spend(5);
        admit(use("focus", warden));
        spend(1);
        var poorer = snapshot();
        assertFailure(OperationAdmissionFailure.Kind.RESOURCE_INSUFFICIENT, () -> submit(use("focus", warden)));
        assertEquals(poorer, snapshot());
        // A cost-free ability stays usable when the paid one is not.
        admit(use("steady"));
        // A missing resource row is never treated as an unlimited pool.
        jdbc.update("delete from actor_resource where id = ?", vigor);
        assertFailure(OperationAdmissionFailure.Kind.RESOURCE_INSUFFICIENT, () -> submit(use("focus", warden)));
    }

    /**
     * Final commit re-derives every premise from current state, then rejects effects that touch an
     * actor the intention does not involve. A matching committed receipt still replays afterwards.
     */
    @Test
    void commitRederivesRulesAndRequiresAttributableEffects() {
        UUID bystander = npc(campaign, null, "Bystander", "grove");
        UUID wardenPool = resource(warden, 4);
        UUID bystanderPool = resource(bystander, 4);
        var input = envelope(use("focus", warden));
        var admitted = admissions.submit(owner, adventure, campaign, input);
        validating(admitted);
        // The target walked away after admission: the stale premise cannot be reused.
        jdbc.update("update actor set location_id = 'grove', row_version = row_version + 1 where id = ?", warden);
        var before = snapshot();
        assertFailure(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE,
                () -> commits.commit(plan(admitted, input, List.of(change(vigor, -3)), player)));
        assertEquals(before, snapshot());
        jdbc.update("update actor set location_id = 'trailhead', row_version = row_version + 1 where id = ?", warden);
        // Another writer spent the resource after admission.
        spend(6);
        before = snapshot();
        assertFailure(OperationAdmissionFailure.Kind.RESOURCE_INSUFFICIENT,
                () -> commits.commit(plan(admitted, input, List.of(change(vigor, -2)), player)));
        assertEquals(before, snapshot());
        jdbc.update("update actor_resource set current_value = 8, row_version = row_version + 1 where id = ?", vigor);
        before = snapshot();
        // Neither a bystander's resource, placement or event can be caused by this intention, and a
        // participant's placement must still match the actor row version it was resolved from.
        for (var forged : List.of(plan(admitted, input, List.of(change(vigor, -3), change(bystanderPool, -1)), player),
                plan(admitted, input, List.of(change(vigor, -3)), bystander),
                plan(admitted, input, List.of(change(UUID.randomUUID(), -1)), player),
                place(plan(admitted, input, List.of(change(vigor, -3)), player), bystander, version(bystander)),
                place(plan(admitted, input, List.of(change(vigor, -3)), player), player, version(player) + 1))) {
            assertThrows(IllegalStateException.class, () -> commits.commit(forged));
            assertEquals(before, snapshot());
        }
        var valid = plan(admitted, input, List.of(change(vigor, -3), change(wardenPool, 1)), warden);
        var receipt = commits.commit(valid);
        assertEquals(5L, jdbc.queryForObject("select current_value from actor_resource where id = ?", Long.class, vigor));
        assertEquals(5L, jdbc.queryForObject("select current_value from actor_resource where id = ?", Long.class,
                wardenPool));
        // Replay precedes current rules: the stored receipt returns after the target left.
        jdbc.update("update actor set location_id = 'grove', row_version = row_version + 1 where id = ?", warden);
        before = snapshot();
        assertEquals(receipt, commits.commit(valid));
        assertEquals(before, snapshot());
    }

    /** A matching admission replays its stored identity even after the rules would now reject it. */
    @Test
    void identicalRetryReplaysBeforeCurrentRules() {
        var input = envelope(new TypedCommand.Move("grove"));
        var first = admissions.submit(owner, adventure, campaign, input);
        jdbc.update("update actor set location_id = 'tower', row_version = row_version + 1 where id = ?", player);
        var before = snapshot();
        var replay = admissions.submit(owner, adventure, campaign, input);
        assertTrue(replay.replayed());
        assertEquals(first.operation().operationId(), replay.operation().operationId());
        assertEquals(before, snapshot());
    }

    /**
     * A committed rule-fact change made while the command waits on the real campaign row lock wins
     * at both boundaries: rules read current rows under the lock, never the pre-lock catalog load.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ruleFactsAreReadAfterAcquiringCampaignLock(boolean finalCommit) throws Exception {
        var input = envelope(new TypedCommand.Speak(warden, "Wait for me"));
        // Compile the pinned closure before contention so only the campaign lock can delay the command.
        assertNotNull(catalogs.preload(owner, adventure, campaign, "SPEAK").require(revision));
        var admitted = finalCommit ? admissions.submit(owner, adventure, campaign, input) : null;
        if (finalCommit) {
            validating(admitted);
        }
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            int blocker;
            try (var query = connection.createStatement(); var rows = query.executeQuery("select pg_backend_pid()")) {
                assertTrue(rows.next());
                blocker = rows.getInt(1);
            }
            try (var lock = connection.prepareStatement("select id from campaign where id = ? for update")) {
                lock.setObject(1, campaign);
                try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
            }
            var waiting = pool.submit(() -> assertThrows(OperationAdmissionFailure.class, () -> {
                if (finalCommit) {
                    commits.commit(plan(admitted, input, List.of(), player));
                } else {
                    admissions.submit(owner, adventure, campaign, input);
                }
            }).kind());
            // Confirm an actual database wait, within the unchanged production 2s lock timeout.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity "
                        + "where ? = any(pg_blocking_pids(pid)))", Boolean.class, blocker))) {
                    blocked = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(blocked, "command must wait on the actual campaign row lock");
            // The lock holder is a canonical writer: it moves the recipient out of range and commits.
            try (var update = connection.prepareStatement("update actor set location_id = 'grove', "
                    + "row_version = row_version + 1 where id = ?")) {
                update.setObject(1, warden);
                assertEquals(1, update.executeUpdate());
            }
            connection.commit();
            var before = snapshot();
            assertEquals(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE, waiting.get(10, TimeUnit.SECONDS));
            assertEquals(before, snapshot());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** The rule-fact loader authorizes scope first, retains no prose and binds a catalog to one revision. */
    @Test
    void ruleCatalogIsScopedProseFreeAndRevisionBound() {
        assertSame(CommandRuleCatalogs.Content.UNAVAILABLE, catalogs.preload(owner, adventure, campaign, "WAIT"));
        assertSame(CommandRuleCatalogs.Content.UNAVAILABLE, catalogs.preload(UUID.randomUUID(), adventure, campaign, "MOVE"));
        assertSame(CommandRuleCatalogs.Content.UNAVAILABLE, catalogs.preload(owner, UUID.randomUUID(), campaign, "MOVE"));
        var content = catalogs.preload(owner, adventure, campaign, "USE_ABILITY");
        var catalog = content.require(revision);
        assertEquals(Map.of("trailhead", true, "grove", true, "ridge", true, "cellar", false, "tower", true,
                        "test:dep#trailhead", true, "test:dep#grove", true),
                catalog.locations().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().disclosed())));
        assertEquals(java.util.Set.of("grove", "cellar"), catalog.locations().get("trailhead").accessibleLinks());
        assertEquals("test:rules", catalog.abilities().get("focus").pack());
        assertEquals("test:dep", catalog.abilities().get("test:dep#mend").pack());
        assertEquals("test:rules", catalog.resourceVocabularies().get("traveler"));
        assertFalse(catalog.toString().contains("Pale flowers"), "authored prose must not be retained");
        assertFalse(catalog.toString().contains("Hidden watcher"), "secret NPC prose must not be retained");
        assertSame(catalog, catalogs.preload(owner, adventure, campaign, "MOVE").require(revision));
        assertFailure(OperationAdmissionFailure.Kind.CONTENT_UNAVAILABLE, () -> content.require(UUID.randomUUID()));
        assertFailure(OperationAdmissionFailure.Kind.CONTENT_UNAVAILABLE,
                () -> CommandRuleCatalogs.Content.UNAVAILABLE.require(revision));
    }

    /** Admit one intention, check it journaled without world effects, then free the campaign for the next case. */
    private void admit(TypedCommand command) {
        var world = jdbc.queryForObject("select to_jsonb(c)::text from campaign c where id = ?", String.class, campaign);
        var resources = jdbc.queryForObject("select jsonb_agg(to_jsonb(r) order by r.id)::text from actor_resource r",
                String.class);
        var admitted = submit(command);
        assertFalse(admitted.replayed());
        assertEquals(command.type(), admitted.operation().commandType());
        assertEquals(world, jdbc.queryForObject("select to_jsonb(c)::text from campaign c where id = ?", String.class,
                campaign));
        assertEquals(resources, jdbc.queryForObject("select jsonb_agg(to_jsonb(r) order by r.id)::text "
                + "from actor_resource r", String.class));
        assertTrue(leases.release(adventure, campaign, admitted.operation().operationId(), admitted.lease().token()));
    }

    /** Submit one fresh envelope across the real admission transaction. */
    private OperationAdmissionService.Admission submit(TypedCommand command) {
        return admissions.submit(owner, adventure, campaign, envelope(command));
    }

    /** Build one fresh envelope at the campaign's unchanged creation revision. */
    private static OperationAdmissionService.Command envelope(TypedCommand command) {
        return new OperationAdmissionService.Command(UUID.randomUUID().toString(), 0, command.type(), command.payload());
    }

    /** Build an ability intention with explicit targets in submitted order. */
    private static TypedCommand.UseAbility use(String ability, UUID... targets) {
        return new TypedCommand.UseAbility(ability, List.of(targets));
    }

    /** Lower the player's vigor by a supported storage-version step, as another canonical writer would. */
    private void spend(long amount) {
        jdbc.update("update actor_resource set current_value = current_value - ?, row_version = row_version + 1 "
                + "where id = ?", amount, vigor);
    }

    /** Persist one campaign NPC with an optional authored origin at a location. */
    private UUID npc(UUID campaignId, String definition, String name, String location) {
        return new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaignId, ActorKind.NPC, definition, name, location, 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
    }

    /** Persist a bounded resource pool for an existing actor. */
    private UUID resource(UUID actor, long current) {
        return new TransactionTemplate(manager).execute(status -> {
            var pool = ActorResource.create(entities.find(Actor.class, actor), "stamina", 0, current, 10, 0,
                    Instant.now());
            entities.persist(pool);
            return pool.getId();
        });
    }

    /** Advance only journal progress so the trusted commit path accepts a resolved plan. */
    private void validating(OperationAdmissionService.Admission admitted) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var operation = entities.find(CampaignOperation.class, admitted.operation().operationId());
            operation.transitionTo(OperationStatus.PLANNING, Instant.now());
            operation.transitionTo(OperationStatus.VALIDATING, Instant.now());
        });
    }

    /** Read a resource's current storage version so plans isolate rule failures from version conflicts. */
    private ResolvedCommit.ResourceChange change(UUID resource, long delta) {
        Long version = jdbc.query("select row_version from actor_resource where id = ?",
                (row, index) -> row.getLong(1), resource).stream().findFirst().orElse(0L);
        return new ResolvedCommit.ResourceChange(resource, version, delta);
    }

    /** Construct trusted test effects bound to immutable typed evidence for the admitted request. */
    private ResolvedCommit plan(OperationAdmissionService.Admission admitted, OperationAdmissionService.Command input,
            List<ResolvedCommit.ResourceChange> resources, UUID eventActor) {
        var intent = CommandIntent.from(owner, input);
        return new ResolvedCommit(new ResolvedCommit.Context(adventure, campaign, admitted.operation().operationId(),
                revision, intent.payloadHash(), 0, 2, admitted.lease().token()),
                new ResolvedCommit.WorldChange(true, 6, CampaignMode.EXPLORATION, null), resources,
                List.of(new ResolvedCommit.Event(eventActor, null, EventVisibility.PUBLIC,
                        new CampaignEvent.Body("test-ability-resolved", 1, 1, "{}"))),
                List.of(), "{}", false, ResolvedCommit.Preparation.NONE, intent);
    }

    /** Copy a plan with one actor placement to the grove. */
    private static ResolvedCommit place(ResolvedCommit plan, UUID actor, long rowVersion) {
        return new ResolvedCommit(plan.context(), plan.world(), plan.resources(), plan.events(), plan.rewards(),
                plan.receiptDetails(), plan.narrationRequired(), plan.preparation(), plan.intent(),
                List.of(new ResolvedCommit.Placement(actor, rowVersion, "grove")));
    }

    /** Current storage version of an actor row. */
    private long version(UUID actor) {
        return jdbc.queryForObject("select row_version from actor where id = ?", Long.class, actor);
    }

    /** Capture exact committed rows for every state, journal, permit and effect table these rules could touch. */
    private Map<String, String> snapshot() {
        var snapshot = new LinkedHashMap<String, String>();
        for (String table : List.of("campaign", "actor", "actor_resource", "character_state", "learned_ability",
                "item_instance", "campaign_creation", "operation_journal", "campaign_mutation_lease", "campaign_event",
                "reward_settlement", "canonical_outbox", "operation_random_draw", "campaign_random_seed")) {
            snapshot.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return snapshot;
    }

    /** Assert a fixed sanitized category without inspecting raw SQL or submitted text. */
    private static void assertFailure(OperationAdmissionFailure.Kind kind, org.junit.jupiter.api.function.Executable action) {
        assertEquals(kind, assertThrows(OperationAdmissionFailure.class, action).kind());
    }

    /** Preset creation request; every call creates an independent campaign in the same adventure. */
    private static CampaignCreationService.Request request(UUID content) {
        return new CampaignCreationService.Request(UUID.randomUUID(), content, "Campaign", SupportedLocale.EN,
                new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null));
    }

    /** Dependency pack: the fixture plus a costed ability whose "vigor" belongs to this pack's own rules. */
    private static Map<String, byte[]> dependencyPack(Map<String, byte[]> fixture) {
        var files = new TreeMap<>(fixture);
        var abilities = definitions(files, "abilities.json");
        abilities.add(ability("mend", "self", 1, Map.of(), List.of(), 1));
        put(files, "abilities.json", abilities);
        return files;
    }

    /**
     * Root pack: open, blocked and secret links plus an unlinked location; public and secret NPC
     * origins; and abilities covering every rule. The creation profile grants all abilities except
     * {@code ward}, including two from the dependency.
     */
    private static Map<String, byte[]> rootPack(Map<String, byte[]> fixture) {
        var files = new TreeMap<>(fixture);
        put(files, "locations.json", List.of(
                location("trailhead", null, link("grove", true), link("ridge", false), link("cellar", true)),
                location("grove", null, link("trailhead", true)),
                location("ridge", null, link("trailhead", true)),
                location("cellar", "secret", link("trailhead", true)),
                location("tower", null)));
        put(files, "npcs.json", List.of(npcDefinition("warden", "Trail keeper", null),
                npcDefinition("lurker", "Hidden watcher", "secret")));
        var abilities = definitions(files, "abilities.json");
        abilities.add(ability("focus", "single", 1, Map.of("fieldwork", 1), List.of("steady"), 3));
        abilities.add(ability("surge", "self", 1, Map.of("fieldwork", 3), List.of(), 0));
        abilities.add(ability("ward", "self", 1, Map.of(), List.of(), 0));
        abilities.add(ability("rally", "self", 1, Map.of(), List.of("ward"), 0));
        abilities.add(ability("volley", "multiple", 2, Map.of(), List.of(), 9));
        abilities.add(ability("quake", "area", 1, Map.of(), List.of(), 0));
        put(files, "abilities.json", abilities);
        var creationProfiles = definitions(files, "creation.json");
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) creationProfiles.getFirst().get("data");
        data.put("startingAbilities", List.of("steady", "focus", "surge", "rally", "volley", "quake",
                "test:dep#mend", "test:dep#steady"));
        put(files, "creation.json", creationProfiles);
        var manifest = document(files, "adventure.json");
        @SuppressWarnings("unchecked")
        var listed = new java.util.ArrayList<>((List<Object>) manifest.get("files"));
        listed.add(Map.of("path", "npcs.json", "category", "npcs"));
        manifest.put("files", listed);
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        return files;
    }

    /** One authored location with an optional knowledge visibility and ordered links. */
    @SafeVarargs
    private static Map<String, Object> location(String id, String knowledge, Map<String, Object>... links) {
        var data = new LinkedHashMap<String, Object>(Map.of("name", "Place " + id, "description", "Fixture " + id,
                "links", List.of(links)));
        if (knowledge != null) {
            data.put("knowledge", knowledge);
        }
        return Map.of("id", id, "kind", "location", "data", data);
    }

    /** One authored link with explicit accessibility. */
    private static Map<String, Object> link(String to, boolean accessible) {
        return Map.of("to", to, "accessible", accessible);
    }

    /** One authored NPC standing at the entry location, optionally secret. */
    private static Map<String, Object> npcDefinition(String id, String role, String knowledge) {
        var data = new LinkedHashMap<String, Object>(Map.of("name", "NPC " + id, "role", role, "location", "trailhead"));
        if (knowledge != null) {
            data.put("knowledge", knowledge);
        }
        return Map.of("id", id, "kind", "npc", "data", data);
    }

    /** One ability contract with prerequisites, an optional vigor cost and a target policy. */
    private static Map<String, Object> ability(String id, String policy, int maxTargets, Map<String, Integer> skills,
            List<String> abilities, int vigorCost) {
        var data = new LinkedHashMap<String, Object>();
        data.put("name", "Ability " + id);
        data.put("description", "Fixture ability " + id);
        data.put("prerequisites", Map.of("skills", skills.entrySet().stream()
                .map(need -> Map.of("skill", need.getKey(), "rank", need.getValue())).toList(), "abilities", abilities));
        data.put("costs", vigorCost == 0 ? List.of() : List.of(Map.of("resource", "vigor", "amount", vigorCost)));
        data.put("target", Map.of("policy", policy, "maxTargets", maxTargets));
        data.put("effects", List.of(Map.of("type", "modifyResource", "resource", "vigor", "amount", 1)));
        return Map.of("id", id, "kind", "ability", "data", data);
    }

    /** Return a mutable copy of a definition file's entries; malformed fixture input fails setup. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> definitions(Map<String, byte[]> files, String path) {
        return new java.util.ArrayList<>((List<Map<String, Object>>) document(files, path).get("definitions"));
    }

    /** Replace one definition file with exactly these entries. */
    private static void put(Map<String, byte[]> files, String path, List<Map<String, Object>> definitions) {
        files.put(path, JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions", definitions)));
    }

    /** Parse one fixture JSON object for controlled mutations. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> document(Map<String, byte[]> files, String path) {
        return JSON.readValue(files.get(path), Map.class);
    }

    /** Preserve a dependency's exact registered coordinates in a manifest declaration. */
    private static Map<String, Object> pin(PackRegistry.Registration registration) {
        var pack = registration.pack();
        return Map.of("id", pack.packId(), "version", pack.packVersion(), "hash", pack.contentHash());
    }

    /**
     * Register a fixture variant through real ZIP staging and sealing, without bypassing any database
     * protection. Uncompressed entries keep archive ratio limits irrelevant to these small files.
     */
    private PackRegistry.Registration register(Map<String, byte[]> original, String id,
            List<Map<String, Object>> dependencies) throws Exception {
        var files = new TreeMap<>(original);
        var manifest = document(files, "adventure.json");
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
