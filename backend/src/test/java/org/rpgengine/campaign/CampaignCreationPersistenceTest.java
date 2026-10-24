package org.rpgengine.campaign;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL acceptance of atomic initial state, owner isolation and durable request retries. */
@SpringBootTest
class CampaignCreationPersistenceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private PackRegistry registry;
    @Autowired private CampaignCreationService service;
    private UUID owner;
    private UUID adventure;
    private UUID revision;

    /** Construct the injected fixture; missing PostgreSQL fails application startup. */
    CampaignCreationPersistenceTest() {
    }

    /** Commit an owner, adventure and sealed snapshot before each creation transaction. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var profile = OwnerProfile.create("Owner", Instant.now());
            entities.persist(profile);
            owner = profile.getId();
            var workspace = AdventureWorkspace.create(owner, "Harbor", "harbor", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            adventure = workspace.getId();
        });
        revision = register("fantasy-exploration");
    }

    /** Remove only disposable fixture rows and failure-injection objects, including after failed assertions. */
    @AfterEach
    void clear() {
        jdbc.execute("drop trigger if exists p061_fail on campaign_creation");
        jdbc.execute("drop function if exists p061_fail()");
        jdbc.execute("truncate campaign_creation, character_state, item_instance, learned_ability, plan_disposition, "
                + "plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, "
                + "query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, canonical_outbox, "
                + "campaign_checkpoint, reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, "
                + "content_revision, adventure_workspace, identity_owner_profile");
    }

    /** Creation pins the selected genre, grants exact initial state and consumes no canonical counters or jobs. */
    @Test
    void createsIndependentCampaignsAndSurvivesServiceRestart() throws Exception {
        var request = request(revision, UUID.randomUUID());
        var first = service.create(owner, adventure, request);
        var restarted = new CampaignCreationService(jdbc, registry, manager);
        assertEquals(first, restarted.create(owner, adventure, request));
        var second = restarted.create(owner, adventure, request(register("science-fiction-salvage"), UUID.randomUUID()));
        assertNotEquals(first.campaignId(), second.campaignId());
        assertNotEquals(first.actorId(), second.actorId());
        assertEquals("trailhead", jdbc.queryForObject("select location_id from actor where id = ?", String.class, first.actorId()));
        assertEquals("dock", jdbc.queryForObject("select location_id from actor where id = ?", String.class, second.actorId()));
        assertEquals(2, count("campaign"));
        assertEquals(2, count("character_state"));
        assertEquals(2, count("item_instance"));
        assertEquals(2, count("learned_ability"));
        assertEquals(2, jdbc.queryForObject("select count(*) from actor_resource where current_value = maximum_value "
                + "and minimum_value = 0 and updated_revision = 0", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from campaign where state_revision = 0 "
                + "and game_turn = 0 and event_sequence = 0 and world_time_seconds = 0 and pending_decision is null", Integer.class));
        for (String table : new String[] {"campaign_event", "operation_journal", "operation_random_draw", "ai_job",
                "campaign_random_seed", "canonical_outbox"}) {
            assertEquals(0, count(table), table);
        }
        assertEquals("2", jdbc.queryForObject("select attributes ->> 'awareness' from character_state where actor_id = ?",
                String.class, first.actorId()));
        assertEquals("en", jdbc.queryForObject("select narrative_locale from presentation_campaign_settings where campaign_id = ?",
                String.class, first.campaignId()));
        assertThrows(DataAccessException.class, () -> jdbc.update("update campaign_creation set payload_hash = repeat('a', 64)"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update item_instance set quantity = maximum_stack + 1, row_version = row_version + 1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update item_instance set actor_id = ?, row_version = row_version + 1 where campaign_id = ?",
                second.actorId(), first.campaignId()));
        assertThrows(DataAccessException.class, () -> jdbc.update("update learned_ability set rank = 11, row_version = row_version + 1"));
    }

    /** Wrong ownership, inaccessible revisions, overspending and changed retries leave no partial state. */
    @Test
    void rejectsInvalidRequestsBeforeWriting() {
        var request = request(revision, UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> service.create(UUID.randomUUID(), adventure, request));
        assertThrows(IllegalArgumentException.class, () -> service.create(owner, UUID.randomUUID(), request));
        assertThrows(IllegalArgumentException.class, () -> service.create(owner, adventure, request(UUID.randomUUID(), UUID.randomUUID())));
        var invalid = new CampaignCreationService.Request(UUID.randomUUID(), revision, "Campaign", SupportedLocale.EN,
                new CharacterChoices("Player", null, Map.of("awareness", 3), Map.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create(owner, adventure, invalid));
        // A real but unsealed legacy revision must never become a creation source.
        UUID unsealed = UUID.randomUUID();
        jdbc.update("insert into content_revision (id, adventure_id, pack_id, pack_version, content_hash, schema_version, "
                + "ruleset_id, ruleset_version, ruleset_hash, source_locale, admitted_at) select ?, adventure_id, pack_id, "
                + "'9.9.9', content_hash, schema_version, ruleset_id, ruleset_version, ruleset_hash, source_locale, "
                + "admitted_at from content_revision where id = ?", unsealed, revision);
        assertThrows(IllegalArgumentException.class, () -> service.create(owner, adventure, request(unsealed, UUID.randomUUID())));
        UUID otherAdventure = new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(owner, "Other", "other", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        // Owner authorization alone cannot permit a revision belonging to a different adventure.
        assertThrows(IllegalArgumentException.class, () -> service.create(owner, otherAdventure, request));
        assertEquals(0, count("campaign"));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(status -> service.create(owner, adventure, request)));
        var created = service.create(owner, adventure, request);
        var changed = new CampaignCreationService.Request(request.requestId(), revision, "Changed", SupportedLocale.EN, request.choices());
        assertThrows(IllegalArgumentException.class, () -> service.create(owner, adventure, changed));
        assertEquals(created, service.create(owner, adventure, request));
        assertEquals(1, count("campaign"));
    }

    /** Competing identical requests return the same committed result rather than duplicate grants. */
    @Test
    void serializesConcurrentIdenticalRequests() throws Exception {
        var request = request(revision, UUID.randomUUID());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.create(owner, adventure, request));
            var second = pool.submit(() -> service.create(owner, adventure, request));
            assertEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, count("campaign_creation"));
        assertEquals(1, count("item_instance"));
    }

    /** Even a failure deferred until COMMIT rolls back every grant and permits the same request to retry. */
    @Test
    void rollsBackCommitFailureAndRetries() {
        jdbc.execute("create function p061_fail() returns trigger language plpgsql as $$ begin raise exception 'creation failure'; end $$");
        jdbc.execute("create constraint trigger p061_fail after insert on campaign_creation deferrable initially deferred "
                + "for each row execute function p061_fail()");
        var request = request(revision, UUID.randomUUID());
        assertThrows(RuntimeException.class, () -> service.create(owner, adventure, request));
        for (String table : new String[] {"campaign", "actor", "actor_resource", "character_state", "item_instance",
                "learned_ability", "presentation_campaign_settings", "campaign_creation"}) {
            assertEquals(0, count(table), table);
        }
        jdbc.execute("drop trigger p061_fail on campaign_creation");
        assertNotNull(service.create(owner, adventure, request));
        assertEquals(1, count("campaign_creation"));
    }

    /** Register exact bundled bytes in this adventure; staging or semantic failures fail test setup. */
    private UUID register(String genre) throws Exception {
        return registry.register(adventure, new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/" + genre).toURI()))).revisionId();
    }

    /** Build an immutable preset request whose identity can be reused explicitly by retry tests. */
    private static CampaignCreationService.Request request(UUID revision, UUID requestId) {
        return new CampaignCreationService.Request(requestId, revision, "Campaign", SupportedLocale.EN,
                new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null));
    }

    /** Count only fixed test-controlled table names; SQL errors fail the assertion. */
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
