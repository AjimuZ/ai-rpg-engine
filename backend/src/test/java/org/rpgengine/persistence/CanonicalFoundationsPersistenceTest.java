package org.rpgengine.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.adventure.AdventureWorkspaceRepository;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.campaign.CampaignCounters;
import org.rpgengine.campaign.CampaignEvent;
import org.rpgengine.campaign.CampaignEventRepository;
import org.rpgengine.campaign.CampaignMode;
import org.rpgengine.campaign.CampaignRepository;
import org.rpgengine.campaign.EventVisibility;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ActorRepository;
import org.rpgengine.characters.ActorResource;
import org.rpgengine.characters.ActorResourceRepository;
import org.rpgengine.commands.CampaignOperation;
import org.rpgengine.commands.CampaignOperationRepository;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.content.ContentRevisionRepository;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.identity.OwnerProfileRepository;
import org.rpgengine.memory.CampaignCheckpoint;
import org.rpgengine.memory.CampaignCheckpointRepository;
import org.rpgengine.presentation.CampaignPresentationSettings;
import org.rpgengine.presentation.CampaignPresentationSettingsRepository;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.progression.RewardSettlement;
import org.rpgengine.progression.RewardSettlementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercise the P03.1/P03.2 canonical foundations against the real Flyway schema on
 * PostgreSQL 18: round trips through JPA, relational ownership enforced by composite
 * foreign keys, uniqueness of idempotency keys, event sequences, reward settlements and
 * checkpoints, append-only triggers, and the separation of presentation generations
 * from canonical counters.
 *
 * <p>Requires the disposable test database configured through Spring datasource
 * variables. Repository calls run without a surrounding test transaction so every
 * reload really reads PostgreSQL; each test truncates the tables afterwards because
 * the database is shared with {@code EngineApplicationTest}, which asserts zero rows.
 */
@SpringBootTest
class CanonicalFoundationsPersistenceTest {
    private static final String CONTENT_HASH = "a".repeat(64);
    private static final String RULESET_HASH = "b".repeat(64);
    private static final String PAYLOAD_HASH = "c".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-24T10:15:30.123456789Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OwnerProfileRepository owners;
    @Autowired
    private AdventureWorkspaceRepository adventures;
    @Autowired
    private ContentRevisionRepository revisions;
    @Autowired
    private CampaignRepository campaigns;
    @Autowired
    private CampaignPresentationSettingsRepository presentationSettings;
    @Autowired
    private ActorRepository actors;
    @Autowired
    private CampaignOperationRepository operations;
    @Autowired
    private CampaignEventRepository events;
    @Autowired
    private RewardSettlementRepository rewards;
    @Autowired
    private CampaignCheckpointRepository checkpoints;
    @Autowired
    private ActorResourceRepository resources;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private OwnerProfile owner;

    /** Construct the framework-managed fixture; injection failures fail setup. */
    CanonicalFoundationsPersistenceTest() {
    }

    /** Start every test from an empty schema with one local owner profile. */
    @BeforeEach
    void createOwner() {
        truncateAll();
        owner = owners.saveAndFlush(OwnerProfile.create("Local owner", NOW));
    }

    /** Leave the shared database empty for the other integration tests. */
    @AfterEach
    void cleanUp() {
        truncateAll();
    }

    /** Persist the whole foundation graph and verify every value survives a reload. */
    @Test
    void persistsAndReloadsTheCanonicalGraph() {
        Fixture f = newCampaign("Moonfall");
        Actor hero = actors.saveAndFlush(
                Actor.create(f.campaign.getId(), ActorKind.CHARACTER, "pc.hero", "Ilaria", "loc.gate", 0, NOW));
        Actor wolf = actors.saveAndFlush(
                Actor.create(f.campaign.getId(), ActorKind.CREATURE, null, "Wolf", null, 0, NOW.plusSeconds(1)));
        CampaignOperation operation = committedOperation(f.campaign.getId(), "turn-1");
        CampaignEvent first = events.saveAndFlush(event(f.campaign.getId(), 1, operation.getId(), hero.getId(), null));
        CampaignEvent second = events.saveAndFlush(
                event(f.campaign.getId(), 2, operation.getId(), null, first.getId()));
        rewards.saveAndFlush(RewardSettlement.settle(f.campaign.getId(), "quest.gate", "actor:" + hero.getId(),
                operation.getId(), 1, "{\"xp\": 50, \"items\": [\"key\"]}", NOW));
        checkpoints.saveAndFlush(CampaignCheckpoint.capture(f.campaign.getId(), CampaignCounters.of(1, 1, 60, 2),
                new CampaignCheckpoint.Hashes(CONTENT_HASH, RULESET_HASH, "d".repeat(64)), 1,
                "{\"actors\":{\"count\":2}}", NOW));

        Campaign campaign = campaigns.findById(f.campaign.getId()).orElseThrow();
        assertEquals(f.adventure.getId(), campaign.getAdventureId());
        assertEquals(f.revision.getId(), campaign.getContentRevisionId());
        assertEquals(CampaignMode.EXPLORATION, campaign.getMode());
        assertEquals(CampaignCounters.initial(), campaign.getCounters());
        assertNull(campaign.getPendingDecision());
        // Instants are truncated to the microseconds PostgreSQL stores, so reloads compare equal.
        assertEquals(NOW.truncatedTo(ChronoUnit.MICROS), campaign.getCreatedAt());

        ContentRevision revision = revisions.findById(f.revision.getId()).orElseThrow();
        assertEquals(f.revision.getPack(), revision.getPack());
        assertEquals(f.revision.getRuleset(), revision.getRuleset());
        assertEquals(SupportedLocale.IT, adventures.findById(f.adventure.getId()).orElseThrow().getSourceLocale());

        assertEquals(List.of(hero.getId(), wolf.getId()),
                actors.findByCampaignIdOrderByCreatedAtAsc(f.campaign.getId()).stream().map(Actor::getId).toList());
        assertNull(actors.findById(wolf.getId()).orElseThrow().getDefinitionId());

        CampaignOperation reloaded = operations
                .findByCampaignIdAndIdempotencyKey(f.campaign.getId(), "turn-1").orElseThrow();
        assertEquals(OperationStatus.COMMITTED, reloaded.getStatus());
        assertEquals(1L, reloaded.getCommittedRevision());
        assertEquals(4L, reloaded.getOperationVersion());
        assertTrue(sameJson("{\"summary\":\"gate opened\"}", reloaded.getReceipt()));

        var log = events.findByCampaignIdOrderBySequenceAsc(f.campaign.getId());
        assertEquals(List.of(first.getId(), second.getId()), log.stream().map(CampaignEvent::getId).toList());
        assertEquals(first.getEnvelope(), log.get(0).getEnvelope());
        assertEquals(first.getBody().eventType(), log.get(0).getBody().eventType());
        assertTrue(sameJson(first.getBody().payload(), log.get(0).getBody().payload()));
        assertEquals(first.getId(), log.get(1).getEnvelope().causationEventId());

        RewardSettlement settlement = rewards.findByCampaignIdAndSourceKeyAndRecipientKey(
                f.campaign.getId(), "quest.gate", "actor:" + hero.getId()).orElseThrow();
        assertTrue(sameJson("{\"items\":[\"key\"],\"xp\":50}", settlement.getRewards()));

        CampaignCheckpoint checkpoint =
                checkpoints.findTopByCampaignIdOrderByStateRevisionDesc(f.campaign.getId()).orElseThrow();
        assertEquals(CampaignCounters.of(1, 1, 60, 2), checkpoint.getCounters());
        assertEquals("d".repeat(64), checkpoint.getHashes().snapshotHash());
    }

    /** A campaign can only pin a content revision admitted to its own adventure. */
    @Test
    void rejectsCampaignPinnedToAnotherAdventuresRevision() {
        Fixture mine = newCampaign("Mine");
        Fixture other = newCampaign("Other");
        // The domain factory refuses the mismatch before any SQL is issued.
        assertThrows(IllegalArgumentException.class,
                () -> Campaign.start(mine.adventure.getId(), other.revision, "Bad", "seed", NOW));
        // The composite foreign key refuses it even when a client bypasses the domain.
        DataAccessException failure = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into campaign (id, adventure_id, content_revision_id, title, seed_identity, mode, "
                        + "created_at, updated_at) values (?, ?, ?, 'Bad', 'seed', 'EXPLORATION', now(), now())",
                UUID.randomUUID(), mine.adventure.getId(), other.revision.getId()));
        assertTrue(failure.getMessage().contains("campaign_content_revision_fk"), failure.getMessage());
    }

    /** Events may not reference another campaign's operation, actor or causation event. */
    @Test
    void rejectsCrossCampaignEventReferences() {
        Fixture a = newCampaign("A");
        Fixture b = newCampaign("B");
        CampaignOperation opA = committedOperation(a.campaign.getId(), "k");
        CampaignOperation opB = committedOperation(b.campaign.getId(), "k");
        Actor actorB = actors.saveAndFlush(
                Actor.create(b.campaign.getId(), ActorKind.NPC, "npc.guard", "Guard", null, 0, NOW));
        CampaignEvent eventB = events.saveAndFlush(event(b.campaign.getId(), 1, opB.getId(), null, null));

        assertRejected("campaign_event_operation_fk", () -> events.saveAndFlush(
                event(a.campaign.getId(), 1, opB.getId(), null, null)));
        assertRejected("campaign_event_actor_fk", () -> events.saveAndFlush(
                event(a.campaign.getId(), 1, opA.getId(), actorB.getId(), null)));
        assertRejected("campaign_event_causation_fk", () -> events.saveAndFlush(
                event(a.campaign.getId(), 1, opA.getId(), null, eventB.getId())));
        assertRejected("reward_settlement_operation_fk", () -> rewards.saveAndFlush(RewardSettlement.settle(
                a.campaign.getId(), "quest", "party", opB.getId(), 1, "{}", NOW)));
        assertEquals(0, events.findByCampaignIdOrderBySequenceAsc(a.campaign.getId()).size());
    }

    /** Idempotency keys are unique per campaign, not globally. */
    @Test
    void scopesIdempotencyKeysToTheCampaign() {
        Fixture a = newCampaign("A");
        Fixture b = newCampaign("B");
        operations.saveAndFlush(CampaignOperation.accept(a.campaign.getId(), "same-key", PAYLOAD_HASH, "move", 0, NOW));
        // Another campaign may reuse the key.
        operations.saveAndFlush(CampaignOperation.accept(b.campaign.getId(), "same-key", PAYLOAD_HASH, "move", 0, NOW));
        assertRejected("operation_journal_idempotency_uk", () -> operations.saveAndFlush(
                CampaignOperation.accept(a.campaign.getId(), "same-key", "e".repeat(64), "move", 0, NOW)));
        CampaignOperation stored =
                operations.findByCampaignIdAndIdempotencyKey(a.campaign.getId(), "same-key").orElseThrow();
        assertTrue(stored.matchesPayload(PAYLOAD_HASH));
        assertFalse(stored.matchesPayload("e".repeat(64)));
    }

    /** Event sequences, reward sources and checkpoint revisions are unique per campaign. */
    @Test
    void enforcesPerCampaignUniqueness() {
        Fixture f = newCampaign("Unique");
        CampaignOperation op = committedOperation(f.campaign.getId(), "k");
        events.saveAndFlush(event(f.campaign.getId(), 1, op.getId(), null, null));
        assertRejected("campaign_event_sequence_uk",
                () -> events.saveAndFlush(event(f.campaign.getId(), 1, op.getId(), null, null)));

        rewards.saveAndFlush(RewardSettlement.settle(f.campaign.getId(), "encounter.wolves", "party",
                op.getId(), 1, "{\"xp\":10}", NOW));
        assertRejected("reward_settlement_source_uk", () -> rewards.saveAndFlush(RewardSettlement.settle(
                f.campaign.getId(), "encounter.wolves", "party", op.getId(), 1, "{\"xp\":10}", NOW)));

        var hashes = new CampaignCheckpoint.Hashes(CONTENT_HASH, RULESET_HASH, "d".repeat(64));
        checkpoints.saveAndFlush(CampaignCheckpoint.capture(
                f.campaign.getId(), CampaignCounters.of(1, 1, 0, 1), hashes, 1, "{}", NOW));
        assertRejected("campaign_checkpoint_revision_uk", () -> checkpoints.saveAndFlush(
                CampaignCheckpoint.capture(f.campaign.getId(), CampaignCounters.of(1, 1, 0, 1), hashes, 1, "{}", NOW)));
    }

    /** Append-only and admitted rows reject UPDATE and DELETE from any client. */
    @Test
    void rejectsMutationOfImmutableRows() {
        Fixture f = newCampaign("Immutable");
        CampaignOperation op = committedOperation(f.campaign.getId(), "k");
        CampaignEvent event = events.saveAndFlush(event(f.campaign.getId(), 1, op.getId(), null, null));
        RewardSettlement reward = rewards.saveAndFlush(
                RewardSettlement.settle(f.campaign.getId(), "quest", "party", op.getId(), 1, "{}", NOW));
        CampaignCheckpoint checkpoint = checkpoints.saveAndFlush(CampaignCheckpoint.capture(f.campaign.getId(),
                CampaignCounters.of(1, 1, 0, 1), new CampaignCheckpoint.Hashes(CONTENT_HASH, RULESET_HASH,
                        "d".repeat(64)), 1, "{}", NOW));

        assertImmutable("update campaign_event set event_type = 'x' where id = ?", event.getId());
        assertImmutable("delete from campaign_event where id = ?", event.getId());
        assertImmutable("update content_revision set pack_version = '9' where id = ?", f.revision.getId());
        assertImmutable("update reward_settlement set source_key = 'other' where id = ?", reward.getId());
        assertImmutable("update campaign_checkpoint set game_turn = 5 where id = ?", checkpoint.getId());
        assertEquals(1, events.count());
    }

    /** Locale and presentation generations change without touching canonical counters. */
    @Test
    void keepsPresentationGenerationsOutOfCanonicalCounters() {
        Fixture f = newCampaign("Locale");
        Map<String, Object> before = campaignRow(f.campaign.getId());

        CampaignPresentationSettings settings = presentationSettings.findById(f.campaign.getId()).orElseThrow();
        Instant later = NOW.plusSeconds(60);
        assertTrue(settings.changeNarrativeLocale(SupportedLocale.IT, later));
        assertEquals(1, settings.advanceGlossaryRevision(later));
        assertEquals(1, settings.advanceMediaConfigurationGeneration(later));
        presentationSettings.saveAndFlush(settings);
        OwnerProfile profile = owners.findById(owner.getId()).orElseThrow();
        profile.changeUiLocale(SupportedLocale.IT, later);
        owners.saveAndFlush(profile);

        CampaignPresentationSettings reloaded = presentationSettings.findById(f.campaign.getId()).orElseThrow();
        assertEquals(SupportedLocale.IT, reloaded.getNarrativeLocale());
        assertEquals(1, reloaded.getLocaleRevision());
        assertEquals(1, reloaded.getGlossaryRevision());
        assertEquals(1, reloaded.getMediaConfigurationGeneration());
        assertEquals(SupportedLocale.IT, owners.findById(owner.getId()).orElseThrow().getUiLocale());
        // The whole campaign row, counters and updated_at included, is byte-for-byte unchanged.
        assertEquals(before, campaignRow(f.campaign.getId()));
        assertEquals(CampaignCounters.initial(), campaigns.findById(f.campaign.getId()).orElseThrow().getCounters());
    }

    /**
     * Canonical counters and stored vocabularies are constrained in the database too.
     * Each update advances row_version correctly, so the rejection comes from the value
     * rules (negative counters now meet the V2 monotonic guard first) and not from the
     * row-version step.
     */
    @Test
    void rejectsInvalidCanonicalValuesInTheDatabase() {
        Fixture f = newCampaign("Checks");
        for (String column : List.of("state_revision", "game_turn", "world_time_seconds", "event_sequence")) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("update campaign set " + column
                    + " = -1, row_version = row_version + 1 where id = ?", f.campaign.getId()), column);
        }
        assertRejected("presentation_campaign_settings_narrative_locale_check", () -> jdbc.update(
                "update presentation_campaign_settings set narrative_locale = 'fr', row_version = row_version + 1 "
                        + "where campaign_id = ?", f.campaign.getId()));
        assertRejected("identity_owner_profile_ui_locale_check", () -> jdbc.update(
                "update identity_owner_profile set ui_locale = 'de', row_version = row_version + 1 where id = ?",
                owner.getId()));
        assertEquals(CampaignCounters.initial(), campaigns.findById(f.campaign.getId()).orElseThrow().getCounters());
    }

    /** Workspace, admitted revision, campaign and presentation settings created together. */
    private record Fixture(AdventureWorkspace adventure, ContentRevision revision, Campaign campaign) {
    }

    /** Bounded resources round-trip through JPA; lookup and uniqueness include campaign and actor. */
    @Test
    void persistsResourcesAndRejectsCrossCampaignOwnership() {
        Fixture a = newCampaign("ResourcesA");
        Fixture b = newCampaign("ResourcesB");
        Actor actor = actors.saveAndFlush(Actor.create(a.campaign.getId(), ActorKind.CHARACTER,
                null, "Hero", null, 0, NOW));
        Actor otherActor = actors.saveAndFlush(Actor.create(b.campaign.getId(), ActorKind.CHARACTER,
                null, "Hero", null, 0, NOW));
        ActorResource health = resources.saveAndFlush(ActorResource.create(actor, "health", 0, 10, 10, 0, NOW));
        resources.saveAndFlush(ActorResource.create(otherActor, "health", 0, 10, 10, 0, NOW));
        assertEquals(List.of(health), resources.findByCampaignIdAndActorIdOrderByResourceKeyAsc(
                a.campaign.getId(), actor.getId()));
        assertTrue(resources.findByCampaignIdAndActorIdOrderByResourceKeyAsc(
                b.campaign.getId(), actor.getId()).isEmpty());
        assertRejected("actor_resource_key_uk", () -> resources.saveAndFlush(
                ActorResource.create(actor, "health", 0, 10, 10, 0, NOW)));
        assertRejected("actor_resource_actor_fk", () -> jdbc.update(
                "insert into actor_resource (id, campaign_id, actor_id, resource_key, minimum_value, "
                        + "current_value, maximum_value, updated_revision, created_at, updated_at) "
                        + "values (?, ?, ?, 'stamina', 0, 5, 10, 0, now(), now())",
                UUID.randomUUID(), a.campaign.getId(), otherActor.getId()));
        for (String assignment : List.of("current_value = -1", "current_value = 11", "minimum_value = 11",
                "maximum_value = 9")) {
            assertRejected("actor_resource_bounds_ck", () -> jdbc.update("update actor_resource set "
                    + assignment + ", row_version = row_version + 1 where id = ?", health.getId()));
        }
        health.adjust(-10, 1, NOW.plusSeconds(1));
        resources.saveAndFlush(health);
        ActorResource reloaded = resources.findById(health.getId()).orElseThrow();
        assertEquals(0, reloaded.getCurrentValue());
        assertEquals(1, reloaded.getUpdatedRevision());
        assertEquals(1, reloaded.getRowVersion());
        assertEquals(10, reloaded.getMaximumValue());
    }

    /** Every mutable table rejects ownership/provenance rewrites and missing or skipped versions. */
    @Test
    void freezesOwnershipAndRequiresVersionSteps() {
        Fixture a = newCampaign("FrozenA");
        Fixture b = newCampaign("FrozenB");
        Actor actor = actors.saveAndFlush(Actor.create(a.campaign.getId(), ActorKind.NPC,
                "npc.guard", "Guard", null, 0, NOW));
        ActorResource resource = resources.saveAndFlush(ActorResource.create(actor, "health", 0, 5, 10, 0, NOW));
        CampaignOperation operation = operations.saveAndFlush(CampaignOperation.accept(a.campaign.getId(),
                "frozen", PAYLOAD_HASH, "move", 0, NOW));
        // Table/column names are fixed test constants. Values remain bound parameters.
        Map<String, UUID> rows = Map.of("identity_owner_profile", owner.getId(), "adventure_workspace",
                a.adventure.getId(), "campaign", a.campaign.getId(), "actor", actor.getId(),
                "actor_resource", resource.getId(), "operation_journal", operation.getId(),
                "presentation_campaign_settings", a.campaign.getId());
        for (var row : rows.entrySet()) {
            String key = row.getKey().equals("presentation_campaign_settings") ? "campaign_id" : "id";
            for (String version : List.of("row_version", "row_version + 2", "-1")) {
                assertRejected(row.getKey() + "_row_version_step", () -> jdbc.update("update " + row.getKey()
                        + " set row_version = " + version + " where " + key + " = ?", row.getValue()));
            }
            assertRejected(row.getKey() + "_frozen", () -> jdbc.update("update " + row.getKey()
                    + " set " + key + " = ?, row_version = row_version + 1 where " + key + " = ?",
                    UUID.randomUUID(), row.getValue()));
        }
        Map<String, String> ownership = Map.of("adventure_workspace", "owner_id", "campaign", "adventure_id",
                "actor", "campaign_id", "actor_resource", "actor_id", "operation_journal", "campaign_id");
        for (var row : ownership.entrySet()) {
            assertRejected(row.getKey() + "_frozen", () -> jdbc.update("update " + row.getKey() + " set "
                    + row.getValue() + " = ?, row_version = row_version + 1 where id = ?",
                    b.campaign.getId(), rows.get(row.getKey())));
        }
        assertRejected("campaign_frozen", () -> jdbc.update("update campaign set content_revision_id = ?, "
                + "row_version = row_version + 1 where id = ?", b.revision.getId(), a.campaign.getId()));
        assertRejected("operation_journal_frozen", () -> jdbc.update("update operation_journal set "
                + "payload_hash = ?, row_version = row_version + 1 where id = ?", RULESET_HASH, operation.getId()));
        // The version guard does not replace CAS: a stale WHERE matches no row and changes nothing.
        assertEquals(1, jdbc.update("update campaign set title = 'Renamed', row_version = row_version + 1 "
                + "where id = ? and row_version = 0", a.campaign.getId()));
        assertEquals(0, jdbc.update("update campaign set title = 'Stale', row_version = row_version + 1 "
                + "where id = ? and row_version = 0", a.campaign.getId()));
        assertEquals("Renamed", campaigns.findById(a.campaign.getId()).orElseThrow().getTitle());
    }

    /** Canonical revisions step once; domain and presentation counters cannot rewind. */
    @Test
    void rejectsRevisionJumpsAndCounterRewinds() {
        Fixture f = newCampaign("Monotonic");
        jdbc.update("update campaign set state_revision = 1, game_turn = 2, world_time_seconds = 60, "
                + "event_sequence = 3, row_version = row_version + 1 where id = ?", f.campaign.getId());
        assertRejected("campaign_state_revision_step", () -> jdbc.update("update campaign set state_revision = 3, "
                + "row_version = row_version + 1 where id = ?", f.campaign.getId()));
        for (String column : List.of("state_revision", "game_turn", "world_time_seconds", "event_sequence")) {
            assertRejected("campaign_counters_monotonic", () -> jdbc.update("update campaign set " + column
                    + " = 0, row_version = row_version + 1 where id = ?", f.campaign.getId()));
        }
        jdbc.update("update presentation_campaign_settings set locale_revision = 1, glossary_revision = 1, "
                + "media_configuration_generation = 1, row_version = row_version + 1 where campaign_id = ?",
                f.campaign.getId());
        for (String column : List.of("locale_revision", "glossary_revision", "media_configuration_generation")) {
            assertRejected("presentation_campaign_settings_monotonic", () -> jdbc.update(
                    "update presentation_campaign_settings set " + column + " = 0, row_version = row_version + 1 "
                            + "where campaign_id = ?", f.campaign.getId()));
        }
    }

    /** Journal outcomes are complete and write-once; per-campaign revisions cannot be paid twice. */
    @Test
    void enforcesCompleteWriteOnceCommitOutcomes() {
        Fixture a = newCampaign("CommitA");
        Fixture b = newCampaign("CommitB");
        CampaignOperation pending = operations.saveAndFlush(CampaignOperation.accept(a.campaign.getId(),
                "pending", PAYLOAD_HASH, "move", 0, NOW));
        for (String assignment : List.of("receipt = '{}'", "committed_revision = 1", "status = 'COMMITTED'")) {
            assertRejected("operation_journal_commit_consistency_ck", () -> jdbc.update("update operation_journal set "
                    + assignment + ", row_version = row_version + 1 where id = ?", pending.getId()));
        }
        CampaignOperation committed = committedOperation(a.campaign.getId(), "done");
        committedOperation(b.campaign.getId(), "done");
        assertRejected("operation_journal_committed_revision_uk", () -> committedOperation(a.campaign.getId(), "twice"));
        for (String assignment : List.of("receipt = '{\"changed\":true}'", "committed_revision = 2")) {
            assertRejected("operation_journal_write_once", () -> jdbc.update("update operation_journal set "
                    + assignment + ", row_version = row_version + 1 where id = ?", committed.getId()));
        }
        committed.transitionTo(OperationStatus.NARRATING, NOW);
        committed.transitionTo(OperationStatus.DELIVERED, NOW);
        operations.saveAndFlush(committed);
        assertRejected("operation_journal_terminal_status", () -> jdbc.update("update operation_journal set "
                + "status = 'NARRATING', row_version = row_version + 1 where id = ?", committed.getId()));
        assertRejected("operation_journal_monotonic", () -> jdbc.update("update operation_journal set "
                + "operation_version = 0, row_version = row_version + 1 where id = ?", committed.getId()));
    }

    /** Detached stale copies fail and a lost concurrent resource race rolls back the whole transaction. */
    @Test
    void rejectsStaleAndConcurrentWriters() throws Exception {
        Fixture f = newCampaign("Concurrent");
        Actor actor = actors.saveAndFlush(Actor.create(f.campaign.getId(), ActorKind.CHARACTER,
                null, "Hero", null, 0, NOW));
        ActorResource resource = resources.saveAndFlush(ActorResource.create(actor, "health", 0, 10, 10, 0, NOW));
        ActorResource stale = resources.findById(resource.getId()).orElseThrow();
        resource.adjust(-1, 1, NOW);
        resources.saveAndFlush(resource);
        stale.adjust(-2, 1, NOW);
        assertThrows(OptimisticLockingFailureException.class, () -> resources.saveAndFlush(stale));
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Callable<Boolean> spend = () -> {
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    ActorResource copy = resources.findById(resource.getId()).orElseThrow();
                    loaded.countDown();
                    await(release);
                    // This row is inserted before the contested flush; a losing writer must roll it back.
                    operations.saveAndFlush(CampaignOperation.accept(f.campaign.getId(), UUID.randomUUID().toString(),
                            PAYLOAD_HASH, "spend", 1, NOW));
                    copy.adjust(-1, 2, NOW);
                    resources.saveAndFlush(copy);
                });
                return true;
            } catch (OptimisticLockingFailureException conflict) {
                return false;
            }
        };
        assertEquals(1, race(spend, loaded, release));
        assertEquals(8, resources.findById(resource.getId()).orElseThrow().getCurrentValue());
        assertEquals(1, operations.count());
        assertEquals(0, campaigns.findById(f.campaign.getId()).orElseThrow().getCounters().stateRevision());
        assertRejected("actor_resource_monotonic", () -> jdbc.update("update actor_resource set updated_revision = 0, "
                + "row_version = row_version + 1 where id = ?", resource.getId()));
    }

    /** Concurrent duplicate inserts succeed once for both idempotency keys and reward sources. */
    @Test
    void serializesConcurrentDuplicateKeysAndRewards() throws Exception {
        Fixture f = newCampaign("Duplicates");
        CampaignOperation operation = committedOperation(f.campaign.getId(), "source");
        for (boolean reward : List.of(false, true)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Callable<Boolean> insert = () -> {
                ready.countDown();
                await(release);
                try {
                    if (reward) {
                        rewards.saveAndFlush(RewardSettlement.settle(f.campaign.getId(), "quest", "party",
                                operation.getId(), 1, "{}", NOW));
                    } else {
                        operations.saveAndFlush(CampaignOperation.accept(f.campaign.getId(), "duplicate",
                                PAYLOAD_HASH, "move", 0, NOW));
                    }
                    return true;
                } catch (DataIntegrityViolationException conflict) {
                    assertTrue(conflict.getMessage().contains(reward ? "reward_settlement_source_uk"
                            : "operation_journal_idempotency_uk"), conflict.getMessage());
                    return false;
                }
            };
            assertEquals(1, race(insert, ready, release));
        }
        assertEquals(1, rewards.count());
        assertEquals(2, operations.count());
    }

    /** Run two independent writers after both reach their barrier; timeouts fail instead of hanging CI. */
    private static int race(Callable<Boolean> action, CountDownLatch ready, CountDownLatch release) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(action);
            var second = executor.submit(action);
            try {
                assertTrue(ready.await(15, TimeUnit.SECONDS), "writers must reach the same starting point");
            } finally {
                release.countDown();
            }
            return (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
        }
    }

    /** Await a test barrier for at most 15 seconds; interruption preserves the flag and fails the test. */
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "test barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", interrupted);
        }
    }

    /** Every existing scoped repository query excludes entities persisted solely in another campaign. */
    @Test
    void excludesOtherCampaignsFromScopedReads() {
        Fixture a = newCampaign("ReadsA");
        Fixture b = newCampaign("ReadsB");
        actors.saveAndFlush(Actor.create(a.campaign.getId(), ActorKind.NPC, null, "Same name", null, 0, NOW));
        CampaignOperation operation = committedOperation(a.campaign.getId(), "source");
        events.saveAndFlush(event(a.campaign.getId(), 1, operation.getId(), null, null));
        rewards.saveAndFlush(RewardSettlement.settle(a.campaign.getId(), "quest", "party", operation.getId(), 1, "{}", NOW));
        checkpoints.saveAndFlush(CampaignCheckpoint.capture(a.campaign.getId(), CampaignCounters.of(1, 1, 0, 1),
                new CampaignCheckpoint.Hashes(CONTENT_HASH, RULESET_HASH, "d".repeat(64)), 1, "{}", NOW));
        assertEquals(List.of(b.campaign), campaigns.findByAdventureIdOrderByCreatedAtAsc(b.adventure.getId()));
        assertTrue(actors.findByCampaignIdOrderByCreatedAtAsc(b.campaign.getId()).isEmpty());
        assertTrue(operations.findByCampaignIdAndIdempotencyKey(b.campaign.getId(), "source").isEmpty());
        assertTrue(events.findByCampaignIdOrderBySequenceAsc(b.campaign.getId()).isEmpty());
        assertTrue(rewards.findByCampaignIdAndSourceKeyAndRecipientKey(b.campaign.getId(), "quest", "party").isEmpty());
        assertTrue(checkpoints.findTopByCampaignIdOrderByStateRevisionDesc(b.campaign.getId()).isEmpty());
    }

    /** PostgreSQL bounds the normalized UTF-8 payload, including JSONB's rendering overhead. */
    @Test
    void enforcesStoredPayloadByteBounds() {
        Fixture f = newCampaign("Payloads");
        CampaignOperation operation = committedOperation(f.campaign.getId(), "source");
        CampaignEvent event = events.saveAndFlush(event(f.campaign.getId(), 1, operation.getId(), null, null));
        RewardSettlement reward = rewards.saveAndFlush(RewardSettlement.settle(f.campaign.getId(), "quest",
                "party", operation.getId(), 1, "{}", NOW));
        CampaignCheckpoint checkpoint = checkpoints.saveAndFlush(CampaignCheckpoint.capture(f.campaign.getId(),
                CampaignCounters.of(1, 1, 0, 1), new CampaignCheckpoint.Hashes(CONTENT_HASH, RULESET_HASH,
                        "d".repeat(64)), 1, "{}", NOW));
        int overhead = jdbc.queryForObject("select octet_length(jsonb_build_object('text', '')::text)", Integer.class);
        // The copied row remains valid in every other respect, so the byte check is the rejection reason.
        assertPayloadBoundary("campaign_event", "payload", event.getId(), CampaignEvent.MAX_PAYLOAD_BYTES,
                overhead, "{\"sequence\":2}", "{\"sequence\":3}");
        assertPayloadBoundary("reward_settlement", "rewards", reward.getId(), RewardSettlement.MAX_REWARDS_BYTES,
                overhead, "{\"source_key\":\"boundary\"}", "{\"source_key\":\"oversized\"}");
        assertPayloadBoundary("campaign_checkpoint", "snapshot", checkpoint.getId(), CampaignCheckpoint.MAX_SNAPSHOT_BYTES,
                overhead, "{\"state_revision\":2}", "{\"state_revision\":3}");
        assertPayloadBoundary("operation_journal", "receipt", operation.getId(), CampaignOperation.MAX_RECEIPT_BYTES,
                overhead, "{\"idempotency_key\":\"boundary\",\"committed_revision\":2}",
                "{\"idempotency_key\":\"oversized\",\"committed_revision\":3}");
        jdbc.update("update campaign set pending_decision = jsonb_build_object('text', repeat('x', ?)), "
                + "row_version = row_version + 1 where id = ?", 16_384 - overhead, f.campaign.getId());
        assertRejected("campaign_pending_decision_size_ck", () -> jdbc.update("update campaign set "
                + "pending_decision = jsonb_build_object('text', repeat('x', ?)), row_version = row_version + 1 "
                + "where id = ?", 16_385 - overhead, f.campaign.getId()));
    }

    /** Insert copies at the exact byte limit and one byte above it; only fixed test identifiers enter SQL. */
    private void assertPayloadBoundary(String table, String column, UUID source, int limit, int overhead,
            String validOverrides, String invalidOverrides) {
        String sql = "insert into " + table + " select (jsonb_populate_record(null::" + table
                + ", to_jsonb(s) || cast(? as jsonb) || jsonb_build_object('id', cast(? as uuid), '" + column
                + "', jsonb_build_object('text', repeat('x', ?))))).* from " + table + " s where id = ?";
        assertEquals(1, jdbc.update(sql, validOverrides, UUID.randomUUID(), limit - overhead, source));
        assertRejected(table + "_" + column + "_size_ck", () -> jdbc.update(sql, invalidOverrides,
                UUID.randomUUID(), limit - overhead + 1, source));
    }

    /**
     * @param title adventure and campaign title
     * @return a persisted adventure with one admitted revision and one started campaign
     */
    private Fixture newCampaign(String title) {
        AdventureWorkspace adventure = adventures.saveAndFlush(
                AdventureWorkspace.create(owner.getId(), title, "dark fantasy", SupportedLocale.IT, NOW));
        ContentRevision revision = revisions.saveAndFlush(ContentRevision.admit(adventure.getId(),
                new ContentRevision.PackCoordinates("pack." + title.toLowerCase(), "1.0.0", CONTENT_HASH, 1,
                        SupportedLocale.IT),
                new ContentRevision.RulesetCoordinates("core", "1.0.0", RULESET_HASH), NOW));
        Campaign campaign = campaigns.saveAndFlush(
                Campaign.start(adventure.getId(), revision, title, "seed-" + title, NOW));
        presentationSettings.saveAndFlush(
                CampaignPresentationSettings.create(campaign.getId(), SupportedLocale.EN, NOW));
        return new Fixture(adventure, revision, campaign);
    }

    /**
     * @param campaignId owning campaign
     * @param key idempotency key
     * @return a persisted operation driven through the docs/07 lifecycle to COMMITTED
     */
    private CampaignOperation committedOperation(UUID campaignId, String key) {
        CampaignOperation operation = CampaignOperation.accept(campaignId, key, PAYLOAD_HASH, "explore", 0, NOW);
        operation.transitionTo(OperationStatus.PLANNING, NOW);
        operation.transitionTo(OperationStatus.VALIDATING, NOW);
        operation.transitionTo(OperationStatus.COMMITTING, NOW);
        operation.recordCommit(1, "{\"summary\": \"gate opened\"}", NOW);
        return operations.saveAndFlush(operation);
    }

    /** @return an unsaved public event with a small typed payload */
    private static CampaignEvent event(UUID campaignId, long sequence, UUID operationId, UUID actorId,
            UUID causationEventId) {
        return CampaignEvent.append(
                new CampaignEvent.Envelope(campaignId, sequence, operationId, actorId, causationEventId,
                        UUID.randomUUID(), 1, 60, RULESET_HASH, EventVisibility.PUBLIC),
                new CampaignEvent.Body("door.opened", 1, 1, "{\"door\": \"gate\", \"by\": [1, 2]}"), NOW);
    }

    /** Assert that the action violates the named constraint; other failures propagate. */
    private static void assertRejected(String constraint, Executable action) {
        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class, action);
        assertTrue(failure.getMessage().contains(constraint), failure.getMessage());
    }

    /** Assert that the SQL statement is refused by the reject_mutation trigger. */
    private void assertImmutable(String sql, UUID id) {
        DataAccessException failure = assertThrows(DataAccessException.class, () -> jdbc.update(sql, id));
        assertTrue(failure.getMessage().contains("rows are immutable"), failure.getMessage());
    }

    /** @return true if PostgreSQL considers both texts the same JSON value (jsonb reformats text) */
    private boolean sameJson(String expected, String actual) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select cast(? as jsonb) = cast(? as jsonb)", Boolean.class, expected, actual));
    }

    /** @return every column of the campaign row as text, for exact before/after comparison */
    private Map<String, Object> campaignRow(UUID campaignId) {
        return jdbc.queryForMap("select to_jsonb(c)::text as row from campaign c where id = ?", campaignId);
    }

    /** Remove all rows; TRUNCATE bypasses the row-level immutability triggers by design. */
    private void truncateAll() {
        jdbc.execute("truncate table canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, operation_journal, "
                + "actor_resource, actor, presentation_campaign_settings, campaign, content_revision, "
                + "adventure_workspace, identity_owner_profile");
    }
}
