package org.rpgengine.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercise the P03.1 canonical foundations against the real Flyway schema on
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

    /** Canonical counters and stored vocabularies are constrained in the database too. */
    @Test
    void rejectsInvalidCanonicalValuesInTheDatabase() {
        Fixture f = newCampaign("Checks");
        for (String column : List.of("state_revision", "game_turn", "world_time_seconds", "event_sequence")) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("update campaign set " + column + " = -1 where id = ?", f.campaign.getId()),
                    column);
        }
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update presentation_campaign_settings set narrative_locale = 'fr' where campaign_id = ?",
                f.campaign.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update identity_owner_profile set ui_locale = 'de' where id = ?", owner.getId()));
        assertEquals(CampaignCounters.initial(), campaigns.findById(f.campaign.getId()).orElseThrow().getCounters());
    }

    /** Workspace, admitted revision, campaign and presentation settings created together. */
    private record Fixture(AdventureWorkspace adventure, ContentRevision revision, Campaign campaign) {
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
        jdbc.execute("truncate table campaign_checkpoint, reward_settlement, campaign_event, operation_journal, "
                + "actor, presentation_campaign_settings, campaign, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }
}
