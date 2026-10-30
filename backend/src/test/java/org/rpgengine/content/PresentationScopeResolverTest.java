package org.rpgengine.content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.inference.contract.NarrationContext;
import org.rpgengine.narration.PresentationScopeResolver;
import org.rpgengine.presentation.PublicPresentationScope;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL proof of the audience presentation scope resolver (P05.7-B.1.2.3.1): a pack registered
 * through {@link PackRegistry} that declares a presentation reaches a campaign pinned to it, only the
 * actors that the operation's public events reference and that the pack declares appear in the scope, a
 * pack without the declaration gives an empty scope, a stale request is absent, and nothing is written.
 *
 * <p>Lives beside the pack corpus tests because it reuses their package-private reference pack.
 */
@SpringBootTest
class PresentationScopeResolverTest {
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final PackStaging STAGING = new PackStaging(PackStaging.Limits.standard());
    private static final String GENERATION_FILE = "{\"path\":\"generation.json\",\"category\":\"generationPolicy\"}";
    private static final String PRESENTATION_FILE = "{\"path\":\"presentation.json\",\"category\":\"presentation\"}";
    private static final String KEEPER = "{\"id\":\"keeper\",\"kind\":\"npc\",\"data\":{\"name\":\"Keeper\","
            + "\"role\":\"Harbormaster\",\"location\":\"harbor\",\"faction\":\"guild\",\"profile\":{\"attributes\":"
            + "{\"might\":1},\"skills\":{\"sailing\":1},\"abilities\":[\"shove\"]}}}";
    private static final String GUARD = KEEPER.replace("\"keeper\"", "\"guard\"").replace("Keeper", "Guard");
    private static final String DECLARATION = "{\"schemaVersion\":1,\"assets\":[\"harbor-backdrop\",\"greeting-voice\"],"
            + "\"scenes\":[{\"id\":\"harbor-dawn\",\"backdrop\":\"harbor-backdrop\"},{\"id\":\"market-day\"}],"
            + "\"lines\":[{\"id\":\"greeting.01\",\"audio\":\"greeting-voice\"},{\"id\":\"farewell\"}],"
            + "\"actors\":[{\"actor\":\"keeper\",\"poses\":[\"idle\",\"wave\"],\"expressions\":[\"calm\",\"smile\"]}]}";
    private static final PublicPresentationScope.ActorLooks KEEPER_LOOKS = new PublicPresentationScope.ActorLooks(
            Set.of("idle", "wave"), Set.of("calm", "smile"));
    private static final PublicPresentationScope EMPTY = new PublicPresentationScope(Set.of(), Map.of(), Set.of(),
            Set.of());
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private PackRegistry registry;
    @Autowired private PresentationScopeResolver resolver;
    private UUID adventure;
    private UUID campaign;
    private UUID player;
    private long sequence;
    private long revision;

    /** Construct the injected PostgreSQL fixture; missing infrastructure fails startup. */
    PresentationScopeResolverTest() {
    }

    /** Start every case from one committed owner and adventure with no registered packs. */
    @BeforeEach
    void prepare() {
        clear();
        adventure = new TransactionTemplate(manager).execute(status -> {
            var owner = OwnerProfile.create("Owner", NOW);
            entities.persist(owner);
            var workspace = AdventureWorkspace.create(owner.getId(), "Harbor", "harbor", SupportedLocale.EN, NOW);
            entities.persist(workspace);
            return workspace.getId();
        });
        sequence = 0;
        revision = 0;
    }

    /** Remove disposable rows; constraints and triggers remain installed. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, "
                + "ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, "
                + "operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, "
                + "reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, content_revision, "
                + "adventure_workspace, identity_owner_profile");
    }

    /**
     * Only an actor that a public event of the operation references and whose definition the pack declares
     * is in the scope, with exactly its declared looks; scenes, lines and assets are the declaration's.
     * Hidden (internal-only or unreferenced), unknown, undefined and undeclared actors are absent, and so is
     * an actor that only a different operation's public event names.
     */
    @Test
    void resolvesOnlyVisibleDeclaredActors() {
        start(declaring());
        UUID keeper = npc("keeper", "Keeper");
        UUID internalOnly = npc("keeper", "Vault Keeper");
        UUID unreferenced = npc("keeper", "Sleeper");
        UUID elsewhere = npc("keeper", "Traveller");
        UUID guard = npc("guard", "Guard");
        UUID drifter = npc(null, "Drifter");
        UUID unknown = UUID.randomUUID();
        UUID operation = operation();
        long committed = revision;
        event(operation, "PUBLIC", "{\"actorId\":\"" + player + "\",\"targetActorIds\":[\"" + keeper + "\",\""
                + guard + "\",\"" + drifter + "\",\"" + unknown + "\",\"not-an-id\"]}");
        event(operation, "INTERNAL", "{\"actorId\":\"" + internalOnly + "\"}");
        event(operation(), "PUBLIC", "{\"actorId\":\"" + elsewhere + "\"}");
        String before = world();

        var scope = resolver.resolve(adventure, campaign, operation, committed).orElseThrow();

        assertEquals(new PublicPresentationScope(Set.of("harbor-dawn", "market-day"), Map.of(keeper, KEEPER_LOOKS),
                Set.of("greeting.01", "farewell"), Set.of("harbor-backdrop", "greeting-voice")), scope);
        for (UUID absent : List.of(player, internalOnly, unreferenced, elsewhere, guard, drifter, unknown)) {
            assertFalse(scope.actors().containsKey(absent), absent.toString());
        }
        assertEquals(before, world(), "resolving never writes");
    }

    /** A pack that declares no presentation gives an empty scope even when declared-looking actors are visible. */
    @Test
    void packWithoutDeclarationYieldsEmptyScope() {
        start(plain());
        assertSame(PackPresentation.NONE, loadedPresentation());
        UUID keeper = npc("keeper", "Keeper");
        UUID operation = operation();
        event(operation, "PUBLIC", "{\"actorId\":\"" + player + "\",\"recipientActorId\":\"" + keeper + "\"}");

        assertEquals(EMPTY, resolver.resolve(adventure, campaign, operation, revision).orElseThrow());
    }

    /**
     * An unknown or foreign adventure, campaign or operation, a revision other than the committed one, an
     * uncommitted operation and an operation with more public events than narration may state all resolve to
     * nothing, which is distinct from an empty scope.
     */
    @Test
    void staleOrMismatchedRequestsResolveToNothing() {
        start(declaring());
        UUID operation = operation();
        event(operation, "PUBLIC", "{\"actorId\":\"" + player + "\"}");
        assertTrue(resolver.resolve(adventure, campaign, operation, revision).isPresent());

        assertTrue(resolver.resolve(UUID.randomUUID(), campaign, operation, revision).isEmpty());
        assertTrue(resolver.resolve(adventure, UUID.randomUUID(), operation, revision).isEmpty());
        assertTrue(resolver.resolve(adventure, campaign, UUID.randomUUID(), revision).isEmpty());
        assertTrue(resolver.resolve(adventure, campaign, operation, revision + 1).isEmpty());
        assertTrue(resolver.resolve(adventure, campaign, operation, revision - 1).isEmpty());

        UUID open = UUID.randomUUID();
        jdbc.update("insert into operation_journal (id, campaign_id, idempotency_key, payload_hash, command_type, "
                + "base_revision, status, created_at, updated_at) values (?, ?, 'open', ?, 'WAIT', 0, 'ACCEPTED', "
                + "now(), now())", open, campaign, "c".repeat(64));
        assertTrue(resolver.resolve(adventure, campaign, open, revision).isEmpty());

        UUID crowded = operation();
        for (int index = 0; index <= NarrationContext.MAX_EVENTS; index++) {
            event(crowded, "PUBLIC", "{\"actorId\":\"" + player + "\"}");
        }
        assertTrue(resolver.resolve(adventure, campaign, crowded, revision).isEmpty());
    }

    /** The resolver needs its own snapshot, so an ambient transaction is refused. */
    @Test
    void rejectsAnAmbientTransaction() {
        start(declaring());
        UUID operation = operation();
        event(operation, "PUBLIC", "{\"actorId\":\"" + player + "\"}");
        long committed = revision;
        var error = assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(
                status -> resolver.resolve(adventure, campaign, operation, committed)));
        assertEquals("presentation scope requires an independent snapshot", error.getMessage());
    }

    /** More declared visible actors than the scope allows fail loudly instead of being truncated. */
    @Test
    void refusesToTruncateOverTheScopeBound() {
        start(declaring());
        var crowd = new ArrayList<UUID>();
        for (int index = 0; index <= PublicPresentationScope.MAX_ENTRIES; index++) {
            crowd.add(npc("keeper", "Visitor " + index));
        }
        UUID operation = operation();
        event(operation, "PUBLIC", "{\"targetActorIds\":" + crowd.stream().map(id -> "\"" + id + "\"").toList() + "}");
        var error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(adventure, campaign, operation, revision));
        assertTrue(error.getMessage().contains("actors"), error.getMessage());
    }

    /** Register the pack, pin a campaign to it and give the campaign its creation-bound player. */
    private void start(Map<String, byte[]> files) {
        UUID content = registry.register(adventure, staged(files)).revisionId();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var started = Campaign.start(adventure, entities.find(ContentRevision.class, content), "Campaign", "seed", NOW);
            entities.persist(started);
            campaign = started.getId();
            var hero = Actor.create(campaign, ActorKind.CHARACTER, null, "Aria", "harbor", 0, NOW);
            entities.persist(hero);
            player = hero.getId();
            entities.flush();
            jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, "
                    + "profile_id, choices, created_at) values (?, ?, ?, ?, ?, 'visitor', '{}'::jsonb, now())",
                    adventure, UUID.randomUUID(), "a".repeat(64), campaign, player);
        });
    }

    /** Persist one NPC of the campaign created from the given pack definition (null for none). */
    private UUID npc(String definition, String name) {
        return new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, definition, name, "harbor", 0, NOW);
            entities.persist(actor);
            return actor.getId();
        });
    }

    /** Commit one operation at the next revision. */
    private UUID operation() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into operation_journal (id, campaign_id, idempotency_key, payload_hash, command_type, "
                + "base_revision, status, operation_version, committed_revision, receipt, created_at, updated_at) "
                + "values (?, ?, ?, ?, 'WAIT', 0, 'COMMITTED', 1, ?, '{}'::jsonb, now(), now())",
                id, campaign, id.toString(), "c".repeat(64), ++revision);
        return id;
    }

    /** Append one event of the given visibility to a committed operation. */
    private void event(UUID operation, String visibility, String payload) {
        jdbc.update("insert into campaign_event (id, campaign_id, sequence, operation_id, correlation_id, event_type, "
                + "event_version, state_revision, world_time_seconds, ruleset_hash, visibility, payload_schema_version, "
                + "payload, created_at) values (gen_random_uuid(), ?, ?, ?, gen_random_uuid(), 'TEST_EVENT', 1, ?, 0, "
                + "?, ?, 1, ?::jsonb, now())", campaign, ++sequence, operation, revision, "b".repeat(64), visibility,
                payload);
    }

    /** @return the presentation declaration the registry compiles for the campaign's pinned pack */
    private PackPresentation loadedPresentation() {
        UUID pinned = jdbc.queryForObject("select content_revision_id from campaign where id = ?", UUID.class, campaign);
        return new TransactionTemplate(manager).execute(status ->
                registry.loadForCampaign(adventure, pinned).root().presentation());
    }

    /** @return every row the resolver could have written, as text */
    private String world() {
        return jdbc.queryForObject("select (select count(*) from operation_journal) || '|' || (select count(*) from "
                + "campaign_event) || '|' || (select count(*) from presentation_event) || '|' || (select count(*) from "
                + "campaign_stream_message) || '|' || (select count(*) from actor)", String.class);
    }

    /** The reference pack with a guard NPC the declaration does not mention, plus the declaration file. */
    static Map<String, byte[]> declaring() {
        var sources = base();
        String manifest = sources.get("adventure.json")
                .replace(GENERATION_FILE + "]", GENERATION_FILE + "," + PRESENTATION_FILE + "]")
                .replace("\"generationPolicy\":\"generation.json\"",
                        "\"generationPolicy\":\"generation.json\",\"presentation\":\"presentation.json\"");
        sources.put("adventure.json", manifest);
        sources.put("presentation.json", DECLARATION);
        return bytes(sources);
    }

    /** The same pack without any presentation declaration. */
    private static Map<String, byte[]> plain() {
        return bytes(base());
    }

    private static Map<String, String> base() {
        var sources = PackCompilerTest.sources();
        sources.put("npcs.json", PackCompilerTest.envelope(KEEPER + "," + GUARD));
        sources.put("adventure.json", sources.get("adventure.json").replace("__DEPENDENCIES__", ""));
        return sources;
    }

    private static Map<String, byte[]> bytes(Map<String, String> sources) {
        Map<String, byte[]> files = new TreeMap<>();
        sources.forEach((path, text) -> files.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return files;
    }

    /** Stage files through the archive path, exactly as an uploaded ZIP would be. */
    static PackStaging.StagedPack staged(Map<String, byte[]> files) {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry("harbor-1.0.0/" + file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        return STAGING.archive(new ByteArrayInputStream(out.toByteArray()));
    }
}
