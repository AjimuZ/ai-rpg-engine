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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ActorResource;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof that typed/common semantics and canonical effects share their
 * transaction boundary. Exact table snapshots include counters, resources, operation,
 * permit, draws and outbox; rejected intentions never partially apply or consume identity.
 * This fixture pins no sealed pack, so it exercises content-free intentions (WAIT, and
 * SPEAK to a co-present actor without an authored origin); pinned-content rules are
 * covered with a registered pack by {@code CommandRulesTest}.
 */
@SpringBootTest
class TypedCommandValidationTest {
    @Autowired private OperationAdmissionService admissions;
    @Autowired private CommandRuleCatalogs catalogs;
    @Autowired private CanonicalCommitService commits;
    @Autowired private RandomDrawService draws;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID owner;
    private UUID adventure;
    private UUID campaign;
    private UUID content;
    private UUID player;
    private UUID guide;
    private UUID resource;

    /** Construct the injected fixture; missing database infrastructure fails startup. */
    TypedCommandValidationTest() { }

    /** Create a committed campaign with an immutable creation player and a bounded resource. */
    @BeforeEach
    void prepare() {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Instant now = Instant.now();
            var profile = OwnerProfile.create("Owner", now);
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Neutral", "neutral", SupportedLocale.EN, now);
            entities.persist(workspace);
            adventure = workspace.getId();
            var revision = ContentRevision.admit(adventure,
                    new ContentRevision.PackCoordinates("neutral", "1", "a".repeat(64), 1, SupportedLocale.EN),
                    new ContentRevision.RulesetCoordinates("core", "1", "b".repeat(64)), now);
            entities.persist(revision);
            content = revision.getId();
            var world = Campaign.start(adventure, revision, "Campaign", "seed", now);
            entities.persist(world);
            campaign = world.getId();
            var actor = Actor.create(campaign, ActorKind.CHARACTER, null, "Player", "camp", 0, now);
            entities.persist(actor);
            player = actor.getId();
            // Co-present actor without an authored origin: perceivable without pinned content.
            var listener = Actor.create(campaign, ActorKind.NPC, null, "Guide", "camp", 0, now);
            entities.persist(listener);
            guide = listener.getId();
            var pool = ActorResource.create(actor, "hp", 0, 5, 10, 0, now);
            entities.persist(pool);
            resource = pool.getId();
            entities.flush();
            jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, "
                            + "profile_id, choices, created_at) values (?, ?, ?, ?, ?, 'neutral', '{}'::jsonb, now())",
                    adventure, UUID.randomUUID(), "a".repeat(64), campaign, player);
        });
    }

    /** Remove disposable test rows, preserving production constraints and triggers. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, content_revision_report, content_revision_file, content_revision, adventure_workspace, identity_owner_profile");
    }

    /** A registered intention is durably admitted without a world effect, event, draw or resource spend. */
    @ParameterizedTest
    @ValueSource(strings = {"WAIT", "SPEAK"})
    void admitsSupportedTypedIntentionsOnly(String type) {
        var world = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
        var command = switch (type) {
            case "WAIT" -> new TypedCommand.Wait(1);
            default -> new TypedCommand.Speak(guide, "È qui? 😀");
        };
        var admitted = submit(envelope(command));
        assertEquals(type, admitted.operation().commandType());
        assertEquals(world, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
        assertEquals(5L, jdbc.queryForObject("select current_value from actor_resource", Long.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from campaign_event", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from operation_random_draw", Integer.class));
    }

    /** All new unknown/patch payloads leave the full database snapshot unchanged, including an expired permit. */
    @Test
    void rejectedPayloadCannotRetireOrPartiallyApplyAnEarlierOperation() {
        submit(envelope(new TypedCommand.Wait(60)));
        jdbc.update("update campaign_mutation_lease set expires_at = clock_timestamp() - interval '1 second'");
        var before = snapshot();
        var bad = new OperationAdmissionService.Command("bad", 0, "MOVE",
                Map.of("destinationId", "door", "effects", List.of(Map.of("resourceId", resource.toString(), "delta", -1))));
        assertFailure(OperationAdmissionFailure.Kind.INVALID_COMMAND, () -> submit(bad));
        assertEquals(before, snapshot());
        assertFailure(OperationAdmissionFailure.Kind.INVALID_COMMAND,
                () -> submit(new OperationAdmissionService.Command("patch", 0, "PATCH", Map.of("hp", 100))));
        assertEquals(before, snapshot());
    }

    /** Unsupported pending decisions, including near-matching starting markers, never authorize input. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"type\":\"CHOICE\",\"version\":1}",
            "{\"type\":\"STARTING_SITUATION\",\"version\":2}",
            "{\"type\":\"STARTING_SITUATION\",\"version\":1,\"extra\":true}"})
    void unknownDecisionFailsClosed(String decision) {
        decision(decision);
        var before = snapshot();
        assertFailure(OperationAdmissionFailure.Kind.DECISION_UNAVAILABLE,
                () -> submit(envelope(new TypedCommand.Wait(60))));
        assertEquals(before, snapshot());
    }

    /** The supported starting decision remains input-capable and is never cleared by validation. */
    @Test
    void startingMarkerAllowsInputWithoutChoosingAnAction() {
        decision("{\"type\":\"STARTING_SITUATION\",\"version\":1}");
        var before = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
        submit(envelope(new TypedCommand.Wait(60)));
        assertEquals(before, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
    }

    /** Encounter scheduling cannot be bypassed by disguising speech or movement as exploration. */
    @Test
    void encounterModeConsumesNothing() {
        jdbc.update("update campaign set mode = 'ENCOUNTER', row_version = row_version + 1");
        var before = snapshot();
        assertFailure(OperationAdmissionFailure.Kind.MODE_ILLEGAL,
                () -> submit(envelope(new TypedCommand.Speak(guide, "hello"))));
        assertEquals(before, snapshot());
    }

    /** Legacy campaigns without a creation player do not silently select another character. */
    @Test
    void missingCreationPlayerIsUnavailable() {
        jdbc.execute("truncate campaign_creation");
        var before = snapshot();
        assertFailure(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE,
                () -> submit(envelope(new TypedCommand.Wait(60))));
        assertEquals(before, snapshot());
    }

    /** Missing and foreign target IDs have the same sanitized rejection and cannot leak another campaign. */
    @Test
    void speechRecipientMustBelongToThisCampaign() {
        UUID foreign = new TransactionTemplate(manager).execute(status -> {
            var other = Campaign.start(adventure, entities.find(ContentRevision.class, content), "Other", "other", Instant.now());
            entities.persist(other);
            var actor = Actor.create(other.getId(), ActorKind.NPC, null, "Player", null, 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
        for (UUID recipient : List.of(foreign, UUID.randomUUID())) {
            var before = snapshot();
            assertFailure(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE,
                    () -> submit(envelope(new TypedCommand.Speak(recipient, "hello"))));
            assertEquals(before, snapshot());
        }
    }

    /** Pre-registry identical requests replay verbatim before newer eligibility/registry checks. */
    @Test
    void historicalOpaqueRequestStillReplaysWithoutWrites() {
        var old = new OperationAdmissionService.Command("old", 0, "LEGACY", Map.of("delta", -1));
        new TransactionTemplate(manager).executeWithoutResult(status -> entities.persist(CampaignOperation.accept(campaign,
                old.requestId(), CommandCanonicalForm.sha256(CommandCanonicalForm.encode(old.commandType(), 0,
                        old.payload(), 32_768)), old.commandType(), 0, Instant.now())));
        jdbc.update("update campaign set state_revision = 1, mode = 'ENCOUNTER', row_version = row_version + 1");
        var before = snapshot();
        assertTrue(submit(old).replayed());
        assertEquals(before, snapshot());
        assertFailure(OperationAdmissionFailure.Kind.REQUEST_CONFLICT, () -> submit(
                new OperationAdmissionService.Command("old", 0, "LEGACY", Map.of("delta", 1))));
        assertEquals(before, snapshot());
    }

    /** Lock waits cannot let a caller change what gets validated after its payload hash has been calculated. */
    @Test
    void validationUsesFrozenCanonicalBytesInsteadOfMutableMap() {
        var payload = new LinkedHashMap<String, Object>(Map.of("seconds", 60));
        var input = new OperationAdmissionService.Command("frozen", 0, "WAIT", payload);
        var boundary = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())) {
            @Override public void execute(String sql) {
                if (sql.startsWith("set local lock_timeout")) {
                    payload.clear();
                    payload.put("patch", Map.of("hp", 100));
                }
                super.execute(sql);
            }
        };
        var admitted = new OperationAdmissionService(entities, boundary, manager, catalogs)
                .submit(owner, adventure, campaign, input);
        assertEquals(CommandCanonicalForm.sha256(CommandCanonicalForm.encode("WAIT", 0,
                        Map.of("seconds", 60), 32_768)),
                jdbc.queryForObject("select payload_hash from operation_journal where id = ?", String.class,
                        admitted.operation().operationId()));
        assertTrue(submit(new OperationAdmissionService.Command("frozen", 0, "WAIT", Map.of("seconds", 60))).replayed());
    }

    /** Missing evidence, changed request/type/hash and an inaccessible owner cannot use the trusted legacy overload. */
    @Test
    void commitRequiresMatchingTypedEvidence() {
        var input = envelope(new TypedCommand.Wait(60));
        var base = plan(submit(input), input);
        for (CommandIntent intent : new CommandIntent[] {null,
                new CommandIntent(owner, "other", 0, new TypedCommand.Wait(60)),
                new CommandIntent(owner, input.requestId(), 0, new TypedCommand.Wait(61)),
                new CommandIntent(owner, input.requestId(), 0, new TypedCommand.Move("door")),
                new CommandIntent(owner, input.requestId(), 1, new TypedCommand.Wait(60))}) {
            var before = snapshot();
            assertThrows(IllegalStateException.class, () -> commits.commit(withIntent(base, intent)));
            assertEquals(before, snapshot());
        }
        var before = snapshot();
        assertFailure(OperationAdmissionFailure.Kind.SCOPE_UNAVAILABLE, () -> commits.commit(withIntent(base,
                new CommandIntent(UUID.randomUUID(), input.requestId(), 0, new TypedCommand.Wait(60)))));
        assertEquals(before, snapshot());
    }

    /** A valid admission is not a reusable authorization stamp; changed mode/decision wins at commit. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitRechecksSemanticsAndRetainsDurableDraws(boolean encounter) {
        var input = envelope(new TypedCommand.Wait(60));
        var resolved = plan(submit(input), input);
        var draw = draws.reserve(resolved.context(), 0, 20);
        if (encounter) {
            jdbc.update("update campaign set mode = 'ENCOUNTER', row_version = row_version + 1");
        } else { decision("{}"); }
        var before = snapshot();
        assertFailure(encounter ? OperationAdmissionFailure.Kind.MODE_ILLEGAL : OperationAdmissionFailure.Kind.DECISION_UNAVAILABLE,
                () -> commits.commit(resolved));
        assertEquals(before, snapshot());
        assertEquals(draw, draws.reserve(resolved.context(), 0, 20));
    }

    /** A committed eligibility change during real campaign-lock contention wins for admission and final commit. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eligibilityIsReadAfterAcquiringCampaignLock(boolean finalCommit) throws Exception {
        var input = envelope(new TypedCommand.Wait(60));
        var resolved = finalCommit ? plan(submit(input), input) : null;
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
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
            var waiting = pool.submit(() -> {
                var failure = assertThrows(OperationAdmissionFailure.class, () -> {
                    if (finalCommit) { commits.commit(resolved); } else { submit(input); }
                });
                return failure.kind();
            });
            // Confirm an actual database wait, within the unchanged production 2s lock timeout.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
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
            try (var update = connection.prepareStatement("update campaign set mode = 'ENCOUNTER', "
                    + "row_version = row_version + 1 where id = ?")) {
                update.setObject(1, campaign);
                assertEquals(1, update.executeUpdate());
            }
            connection.commit();
            var before = snapshot();
            assertEquals(OperationAdmissionFailure.Kind.MODE_ILLEGAL,
                    waiting.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(before, snapshot());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    /** A late invalid effect rolls back the earlier valid cost, all counters, journal and permit together. */
    @Test
    void multiEffectResolutionIsAtomicAndSuccessfulReplaySkipsNewEligibility() {
        var input = envelope(new TypedCommand.Wait(60));
        var base = plan(submit(input), input);
        UUID second = new TransactionTemplate(manager).execute(status -> {
            var pool = ActorResource.create(entities.find(Actor.class, player), "mana", 0, 0, 10, 0, Instant.now());
            entities.persist(pool);
            return pool.getId();
        });
        var invalid = new ResolvedCommit(base.context(), base.world(),
                List.of(new ResolvedCommit.ResourceChange(resource, 0, -1), new ResolvedCommit.ResourceChange(second, 0, -1)),
                base.events(), List.of(), "{}", false, ResolvedCommit.Preparation.NONE, base.intent());
        var before = snapshot();
        assertThrows(IllegalArgumentException.class, () -> commits.commit(invalid));
        assertEquals(before, snapshot());
        var receipt = commits.commit(base);
        assertEquals(4L, jdbc.queryForObject("select current_value from actor_resource where id = ?", Long.class, resource));
        decision("{}");
        before = snapshot();
        assertEquals(receipt, commits.commit(base));
        assertEquals(before, snapshot());
    }

    /** Build one fresh scoped envelope from a sealed typed intention. */
    private OperationAdmissionService.Command envelope(TypedCommand command) {
        return new OperationAdmissionService.Command(UUID.randomUUID().toString(), 0, command.type(), command.payload());
    }

    /** Submit directly across the real admission transaction. */
    private OperationAdmissionService.Admission submit(OperationAdmissionService.Command command) {
        return admissions.submit(owner, adventure, campaign, command);
    }

    /** Advance only journal progress, then construct trusted test effects bound to immutable typed evidence. */
    private ResolvedCommit plan(OperationAdmissionService.Admission admitted, OperationAdmissionService.Command input) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var operation = entities.find(CampaignOperation.class, admitted.operation().operationId());
            operation.transitionTo(OperationStatus.PLANNING, Instant.now());
            operation.transitionTo(OperationStatus.VALIDATING, Instant.now());
        });
        var intent = CommandIntent.from(owner, input);
        return new ResolvedCommit(new ResolvedCommit.Context(adventure, campaign, admitted.operation().operationId(), content,
                intent.payloadHash(), 0, 2, admitted.lease().token()),
                new ResolvedCommit.WorldChange(true, 1, CampaignMode.EXPLORATION, null),
                List.of(new ResolvedCommit.ResourceChange(resource, 0, -1)),
                List.of(new ResolvedCommit.Event(player, null, EventVisibility.PUBLIC,
                        new CampaignEvent.Body("test-intent-resolved", 1, 1, "{}"))),
                List.of(), "{}", false, ResolvedCommit.Preparation.NONE, intent);
    }

    /** Change evidence without changing effects so mismatch tests isolate authorization. */
    private ResolvedCommit withIntent(ResolvedCommit plan, CommandIntent intent) {
        return new ResolvedCommit(plan.context(), plan.world(), plan.resources(), plan.events(), plan.rewards(),
                plan.receiptDetails(), plan.narrationRequired(), plan.preparation(), intent);
    }

    /** Simulate another supported writer changing the decision with a valid storage-version step. */
    private void decision(String value) {
        jdbc.update("update campaign set pending_decision = ?::jsonb, row_version = row_version + 1", value);
    }

    /** Capture exact committed rows for every foundation/command/preparation effect touched by these tests. */
    private Map<String, String> snapshot() {
        var snapshot = new LinkedHashMap<String, String>();
        for (String table : List.of("campaign", "actor", "actor_resource", "campaign_creation", "operation_journal",
                "campaign_mutation_lease", "campaign_event", "reward_settlement", "canonical_outbox",
                "operation_random_draw", "campaign_random_seed", "query_epoch", "ai_job")) {
            snapshot.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), "
                    + "'[]'::jsonb)::text from " + table + " t", String.class));
        }
        return snapshot;
    }

    /** Assert a fixed semantic category without inspecting raw SQL or submitted text. */
    private static void assertFailure(OperationAdmissionFailure.Kind kind, org.junit.jupiter.api.function.Executable action) {
        assertEquals(kind, assertThrows(OperationAdmissionFailure.class, action).kind());
    }
}
