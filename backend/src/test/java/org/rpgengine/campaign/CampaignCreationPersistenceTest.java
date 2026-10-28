package org.rpgengine.campaign;

import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.TreeMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
import tools.jackson.databind.json.JsonMapper;

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
        jdbc.execute("truncate presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_activation, plan_disposition, "
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
                + "and game_turn = 0 and event_sequence = 0 and world_time_seconds = 0 and pending_decision='{\"type\":\"STARTING_SITUATION\",\"version\":1}'::jsonb", Integer.class));
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

    /** Equal local IDs cannot override external mechanics, and later versions cannot move a pin. */
    @Test
    void usesPinnedDependencyMechanicsAndRejectsTheirActualCaps() throws Exception {
        var dependency = fixture("fantasy-exploration");
        // The root still has a maxRank of three, so a rank of two detects wrong-pack lookup.
        dependency.put("skills.json", new String(dependency.get("skills.json"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"maxRank\": 3", "\"maxRank\": 1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var pinned = registerVariant(dependency, "test:mechanics", "1.0.0", List.of());
        var root = fixture("fantasy-exploration");
        root.put("creation.json", new String(root.get("creation.json"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("fieldwork", "test:mechanics#fieldwork")
                .replace("\"kit\"", "\"test:mechanics#kit\"")
                .replace("\"steady\"", "\"test:mechanics#steady\"")
                .replace("\"skillBudget\": 1", "\"skillBudget\": 5").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var imported = registerVariant(root, "test:root", "1.0.0", List.of(pin(pinned)));
        registerVariant(fixture("fantasy-exploration"), "test:mechanics", "2.0.0", List.of());
        var invalid = new CampaignCreationService.Request(UUID.randomUUID(), imported.revisionId(), "Campaign",
                SupportedLocale.EN, new CharacterChoices("Player", null, Map.of(),
                Map.of("test:mechanics#fieldwork", 2), null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create(owner, adventure, invalid));
        assertEquals(0, count("campaign"));
        var invalidPreset = new TreeMap<>(root);
        invalidPreset.put("creation.json", new String(root.get("creation.json"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"test:mechanics#fieldwork\": 1", "\"test:mechanics#fieldwork\": 2")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var importedPreset = registerVariant(invalidPreset, "test:root", "2.0.0", List.of(pin(pinned)));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(owner, adventure, request(importedPreset.revisionId(), UUID.randomUUID())));
        var invalidLoadout = new TreeMap<>(root);
        invalidLoadout.put("creation.json", new String(root.get("creation.json"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"quantity\": 1", "\"quantity\": 2").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var importedLoadout = registerVariant(invalidLoadout, "test:root", "3.0.0", List.of(pin(pinned)));
        var stackFailure = assertThrows(IllegalArgumentException.class,
                () -> service.create(owner, adventure, request(importedLoadout.revisionId(), UUID.randomUUID())));
        assertEquals("starting loadout exceeds its stack limit", stackFailure.getMessage());
        assertEquals(0, count("campaign"));
        var created = service.create(owner, adventure, request(imported.revisionId(), UUID.randomUUID()));
        assertEquals("1", jdbc.queryForObject("select skills ->> 'test:mechanics#fieldwork' from character_state "
                + "where actor_id = ?", String.class, created.actorId()));
        assertEquals("test:mechanics#kit", jdbc.queryForObject("select definition_id from item_instance", String.class));
        assertEquals("test:mechanics#steady", jdbc.queryForObject("select definition_id from learned_ability", String.class));
    }

    /** A dependency-owned profile uses its own rules and qualifies its local grants and preset skills. */
    @Test
    @SuppressWarnings("unchecked")
    void resolvesExternalProfileAndLocationInTheirDeclaringPack() throws Exception {
        var dependency = registerVariant(fixture("science-fiction-salvage"), "test:profile", "1.0.0", List.of());
        var root = fixture("fantasy-exploration");
        var manifest = document(root, "adventure.json");
        var entry = (Map<String, Object>) manifest.get("entrypoint");
        entry.put("creationProfile", "test:profile#traveler");
        entry.put("location", "test:profile#dock");
        root.put("adventure.json", JsonMapper.builder().build().writeValueAsBytes(manifest));
        var imported = registerVariant(root, "test:root", "1.0.0", List.of(pin(dependency)));
        var created = service.create(owner, adventure, request(imported.revisionId(), UUID.randomUUID()));
        assertEquals("test:profile#traveler", jdbc.queryForObject("select definition_id from actor", String.class));
        assertEquals("test:profile#dock", jdbc.queryForObject("select location_id from actor", String.class));
        assertEquals("3", jdbc.queryForObject("select attributes ->> 'logic' from character_state", String.class));
        assertEquals("2", jdbc.queryForObject("select skills ->> 'test:profile#fieldwork' from character_state", String.class));
        assertEquals("test:profile#kit", jdbc.queryForObject("select definition_id from item_instance", String.class));
        // Custom payloads also use canonical skill identities; a bare equal ID is not an alias.
        var custom = new CampaignCreationService.Request(UUID.randomUUID(), imported.revisionId(), "Custom",
                SupportedLocale.IT, new CharacterChoices("Player", null, Map.of(),
                Map.of("test:profile#fieldwork", 1), null, null));
        assertNotNull(service.create(owner, adventure, custom));
        var ambiguous = new CampaignCreationService.Request(UUID.randomUUID(), imported.revisionId(), "Custom",
                SupportedLocale.EN, new CharacterChoices("Player", null, Map.of(), Map.of("fieldwork", 1), null, null));
        assertThrows(IllegalArgumentException.class, () -> service.create(owner, adventure, ambiguous));
        assertNotNull(created.campaignId());
    }

    /** A diamond reuses the same sealed leaf; two different versions of its pack ID are ambiguous. */
    @Test
    void verifiesTransitivePinsAndRejectsConflictingVersions() throws Exception {
        var files = fixture("fantasy-exploration");
        var leaf = registerVariant(files, "test:leaf", "1.0.0", List.of());
        var left = registerVariant(files, "test:left", "1.0.0", List.of(pin(leaf)));
        var right = registerVariant(files, "test:right", "1.0.0", List.of(pin(leaf)));
        var root = registerVariant(files, "test:root", "1.0.0", List.of(pin(left), pin(right)));
        var snapshot = new TransactionTemplate(manager).execute(status -> registry.loadForCampaign(adventure, root.revisionId()));
        assertEquals(4, snapshot.packs().size());
        assertThrows(IllegalArgumentException.class, () -> snapshot.resolve(snapshot.root(), "test:leaf#kit", "item"));
        assertThrows(IllegalArgumentException.class, () -> snapshot.resolve(snapshot.root(), "test:left#kit", "skill"));
        assertNotNull(service.create(owner, adventure, request(root.revisionId(), UUID.randomUUID())));
        var newer = registerVariant(files, "test:leaf", "2.0.0", List.of());
        var conflicting = registerVariant(files, "test:right", "2.0.0", List.of(pin(newer)));
        var invalid = registerVariant(files, "test:root", "2.0.0", List.of(pin(left), pin(conflicting)));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(owner, adventure, request(invalid.revisionId(), UUID.randomUUID())));
        assertEquals(1, count("campaign"));
    }

    /** Chains of admitted packs cannot bypass the aggregate campaign traversal depth bound. */
    @Test
    void boundsDependencyDepthBeforeWriting() throws Exception {
        var files = fixture("fantasy-exploration");
        var last = registerVariant(files, "test:layer0", "1.0.0", List.of());
        PackRegistry.Registration shared = last;
        for (int index = 1; index <= 16; index++) {
            last = registerVariant(files, "test:layer" + index, "1.0.0", List.of(pin(last)));
            if (index == 14) {
                shared = last;
            }
        }
        assertNotNull(service.create(owner, adventure, request(last.revisionId(), UUID.randomUUID())));
        last = registerVariant(files, "test:layer17", "1.0.0", List.of(pin(last)));
        UUID root = last.revisionId();
        assertThrows(IllegalArgumentException.class,
                () -> service.create(owner, adventure, request(root, UUID.randomUUID())));
        var side = registerVariant(files, "test:side", "1.0.0", List.of(pin(last)));
        // Resolve the shared subtree first at shallow depth, then encounter it on a longer path.
        var diamond = registerVariant(files, "test:diamond", "1.0.0", List.of(pin(shared), pin(side)));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(owner, adventure, request(diamond.revisionId(), UUID.randomUUID())));
        assertEquals(1, count("campaign"));
    }

    /** Shared nodes count once; a root plus 32 distinct dependencies exceeds the revision cap. */
    @Test
    void boundsDistinctDependencyCount() throws Exception {
        var files = fixture("fantasy-exploration");
        List<Map<String, Object>> dependencies = new java.util.ArrayList<>();
        for (int index = 0; index < 31; index++) {
            dependencies.add(pin(registerVariant(files, "test:leaf" + index, "1.0.0", List.of())));
        }
        var allowed = registerVariant(files, "test:root", "1.0.0", dependencies);
        assertNotNull(service.create(owner, adventure, request(allowed.revisionId(), UUID.randomUUID())));
        dependencies.add(pin(registerVariant(files, "test:leaf31", "1.0.0", List.of())));
        var rejected = registerVariant(files, "test:root", "2.0.0", dependencies);
        assertThrows(IllegalArgumentException.class,
                () -> service.create(owner, adventure, request(rejected.revisionId(), UUID.randomUUID())));
        assertEquals(1, count("campaign"));
    }

    /** Reject an oversized aggregate before reading byte arrays; no giant fixture allocation is necessary. */
    @Test
    void boundsDependencyBytesBeforeParsing() {
        var oversized = org.mockito.Mockito.spy(jdbc);
        org.mockito.Mockito.doReturn(250L * 1024 * 1024 + 1).when(oversized).queryForObject(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.any(UUID.class));
        var bounded = new CampaignCreationService(jdbc, new PackRegistry(oversized, manager), manager);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> bounded.create(owner, adventure, request(revision, UUID.randomUUID())));
        assertEquals("campaign dependency byte limit exceeded", failure.getMessage());
        assertEquals(0, count("campaign"));
    }

    /** Load a mutable fixture inventory; staging failures fail test setup. */
    private Map<String, byte[]> fixture(String genre) throws Exception {
        return new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/" + genre).toURI())).files());
    }

    /** Parse one fixture JSON object for controlled mutations; malformed fixture input fails setup. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> document(Map<String, byte[]> files, String path) {
        return JsonMapper.builder().build().readValue(files.get(path), Map.class);
    }

    /** Preserve each dependency's exact registered coordinates in a new manifest declaration. */
    private static Map<String, Object> pin(PackRegistry.Registration registration) {
        var pack = registration.pack();
        return Map.of("id", pack.packId(), "version", pack.packVersion(), "hash", pack.contentHash());
    }

    /**
     * Register an isolated fixture variant through real ZIP staging and sealing, without bypassing
     * any database protections. Uncompressed ZIP entries keep archive ratio limits irrelevant here.
     */
    private PackRegistry.Registration registerVariant(Map<String, byte[]> original, String id, String version,
            List<Map<String, Object>> dependencies) throws Exception {
        var files = new TreeMap<>(original);
        var manifest = document(files, "adventure.json");
        manifest.put("id", id);
        manifest.put("version", version);
        manifest.put("dependencies", dependencies);
        files.put("adventure.json", JsonMapper.builder().build().writeValueAsBytes(manifest));
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
