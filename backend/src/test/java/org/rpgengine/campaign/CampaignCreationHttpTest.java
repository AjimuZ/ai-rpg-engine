package org.rpgengine.campaign;

import java.net.Socket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;

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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP and PostgreSQL acceptance of admission, strict choices, ownership and durable retries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.http.owner-id=00000000-0000-0000-0000-000000000061")
class CampaignCreationHttpTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private PackRegistry registry;
    @Autowired private CampaignCreationService creation;
    private final JsonMapper json = JsonMapper.builder().build();
    private UUID adventure;
    private UUID revision;
    private String cookie;
    private String csrf;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    CampaignCreationHttpTest() {
    }

    /** Commit server-configured ownership and sealed fixture bytes, then bootstrap through the real server. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values ('00000000-0000-0000-0000-000000000061', 'Owner', 'en', now(), now())");
        adventure = new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(UUID.fromString("00000000-0000-0000-0000-000000000061"),
                    "Harbor", "harbor", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        revision = registry.register(adventure, new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI()))).revisionId();
        bootstrap();
    }

    /** Delete only disposable fixture rows after each test; constraints remain installed and active. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_disposition, "
                + "plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, "
                + "query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, canonical_outbox, "
                + "campaign_checkpoint, reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, "
                + "content_revision, adventure_workspace, identity_owner_profile");
    }

    /** Identical HTTP retries return only the committed identities; creation produces no action or narration. */
    @Test
    void createsAndRetriesWithoutAdvancement() throws Exception {
        String payload = payload(UUID.randomUUID());
        var first = post(payload);
        assertEquals(201, first.status(), first.body());
        JsonNode created = json.readTree(first.body());
        assertEquals(3, created.size());
        assertEquals(revision.toString(), created.get("contentRevisionId").asText());
        assertEquals(first.body(), post(payload).body());
        assertEquals(409, post(payload.replace("Campaign", "Changed")).status());
        assertEquals(1, jdbc.queryForObject("select count(*) from campaign where state_revision=0 and game_turn=0 "
                + "and event_sequence=0 and world_time_seconds=0 and pending_decision='{\"type\":\"STARTING_SITUATION\",\"version\":1}'::jsonb", Integer.class));
        for (String table : new String[] {"campaign_event", "operation_journal", "ai_job", "canonical_outbox", "operation_random_draw"}) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
    }

    /** Read APIs must authenticate, authorize the full scope, and return only the selected player's state. */
    @Test
    void readsScopedProjectionsWithoutWriting() throws Exception {
        var created = json.readTree(post(payload(UUID.randomUUID())).body());
        String id = created.get("campaignId").asText();
        String base = "/api/v1/campaigns/" + id;
        String scope = "?adventureId=" + adventure;
        var listing = exchange("GET", "/api/v1/campaigns", "", headers());
        assertEquals(200, listing.status(), listing.body());
        assertEquals(id, json.readTree(listing.body()).get("campaigns").get(0).get("campaignId").asText());
        assertEquals(403, exchange("GET", base + "/state" + scope, "", "").status());
        assertEquals(404, exchange("GET", base + "/state?adventureId=" + UUID.randomUUID(), "", headers()).status());
        assertEquals(400, exchange("GET", base + "/state", "", headers()).status());
        var before = databaseSnapshot();
        var state = exchange("GET", base + "/state" + scope, "", headers());
        assertEquals(200, state.status(), state.body());
        assertTrue(state.headers().toLowerCase().contains("cache-control: no-store"));
        JsonNode view = json.readTree(state.body());
        assertEquals(id, view.get("scope").get("campaignId").asText());
        assertEquals(0, view.get("scope").get("stateRevision").asLong());
        assertEquals("Player", view.get("player").get("displayName").asText());
        assertEquals("trailhead", view.get("player").get("locationId").asText());
        assertEquals(1, view.get("inventory").size());
        assertEquals(1, view.get("abilities").size());
        assertTrue(view.get("knownFacts").isEmpty());
        assertTrue(view.get("quests").isEmpty());
        assertStarting(view, "fantasy-exploration", "en", false);
        assertEquals(2, view.get("pendingDecision").size());
        assertEquals("STARTING_SITUATION", view.get("pendingDecision").get("type").asText());
        assertEquals("FREE_TEXT", view.get("pendingDecision").get("inputMode").asText());
        assertFalse(state.body().contains("seed"));
        for (String section : new String[] {"continue", "state", "inventory", "quests", "abilities", "known-state"}) {
            var response = exchange("GET", base + "/" + section + scope, "", headers());
            assertEquals(200, response.status(), response.body());
            assertEquals(id, json.readTree(response.body()).get("scope").get("campaignId").asText());
        }
        assertEquals(state.body(), exchange("GET", base + "/continue" + scope, "", headers()).body());
        assertEquals(state.body(), exchange("GET", base + "/state" + scope, "", "Cookie: " + cookie + "\r\n").body());
        assertEquals(before, databaseSnapshot(), "reads must not alter any stored row, counter or timestamp");
    }

    /** Both fixture genres resolve their selected Italian entrypoint without changing any stored row. */
    @Test
    void localizesStartingSituations() throws Exception {
        for (String genre : new String[] {"fantasy-exploration", "science-fiction-salvage"}) {
            revision = registry.register(adventure, new PackStaging(PackStaging.Limits.standard()).directory(
                    Path.of(getClass().getResource("/packs/" + genre).toURI()))).revisionId();
            var response = post(payload(UUID.randomUUID()).replace("\"narrativeLocale\":\"en\"", "\"narrativeLocale\":\"it\""));
            assertEquals(201, response.status(), response.body());
            String campaign = json.readTree(response.body()).get("campaignId").asText();
            var before = databaseSnapshot();
            var read = exchange("GET", "/api/v1/campaigns/" + campaign + "/continue?adventureId=" + adventure, "", headers());
            assertEquals(200, read.status(), read.body());
            assertStarting(json.readTree(read.body()), genre, "it", false);
            assertEquals(before, databaseSnapshot());
        }
    }

    /** A newly authored source and a missing overlay use the pinned text, never an engine prologue or active revision. */
    @Test
    void fallsBackToPinnedAuthoredStartingSituation() throws Exception {
        var files = new java.util.TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI())).files());
        var manifest = (tools.jackson.databind.node.ObjectNode) json.readTree(files.get("adventure.json"));
        manifest.put("version", "1.0.1");
        ((tools.jackson.databind.node.ObjectNode) manifest.get("entrypoint")).put("situation", "An unfamiliar signal awaits your response.");
        files.put("adventure.json", json.writeValueAsBytes(manifest));
        var overlay = (tools.jackson.databind.node.ObjectNode) json.readTree(files.get("locales/it.json"));
        var entries = (tools.jackson.databind.node.ArrayNode) overlay.get("entries");
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).get("field").asText().equals("/entrypoint/situation")) { entries.remove(i); }
        }
        files.put("locales/it.json", json.writeValueAsBytes(overlay));
        var output = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(output)) {
            zip.setLevel(0);
            for (var file : files.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        revision = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new java.io.ByteArrayInputStream(output.toByteArray()))).revisionId();
        String campaign = json.readTree(post(payload(UUID.randomUUID()).replace("\"narrativeLocale\":\"en\"",
                "\"narrativeLocale\":\"it\"")).body()).get("campaignId").asText();
        // Importing another revision cannot replace a campaign's pinned source or its decision.
        registry.register(adventure, new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI())));
        var before = databaseSnapshot();
        var response = exchange("GET", "/api/v1/campaigns/" + campaign + "/state?adventureId=" + adventure, "", headers());
        assertEquals(200, response.status(), response.body());
        var situation = json.readTree(response.body()).get("startingSituation");
        assertEquals("An unfamiliar signal awaits your response.", situation.get("text").asText());
        assertEquals("en", situation.get("locale").asText());
        assertTrue(situation.get("fallback").asBoolean());
        assertEquals(before, databaseSnapshot());
    }

    /** Continue/retry cannot resurrect cleared decisions; unsupported versions and private extras never become public. */
    @Test
    void withholdsUnsupportedDecisionsAndNeverReplaysTheStart() throws Exception {
        String payload = payload(UUID.randomUUID());
        String campaign = json.readTree(post(payload).body()).get("campaignId").asText();
        for (String decision : new String[] {null, "{\"type\":\"STARTING_SITUATION\",\"version\":2}",
                "{\"type\":\"STARTING_SITUATION\",\"version\":4294967297}",
                "{\"type\":\"STARTING_SITUATION\",\"version\":1,\"secret\":\"PRIVATE\"}"}) {
            jdbc.update("update campaign set pending_decision=?::jsonb, row_version=row_version+1 where id=?::uuid", decision, campaign);
            var before = databaseSnapshot();
            assertEquals(201, post(payload).status());
            var response = exchange("GET", "/api/v1/campaigns/" + campaign + "/continue?adventureId=" + adventure, "", headers());
            assertEquals(200, response.status(), response.body());
            var view = json.readTree(response.body());
            assertTrue(view.get("startingSituation").isNull());
            assertTrue(view.get("pendingDecision").isNull());
            assertEquals(decision != null, view.get("campaign").get("pendingDecisionAvailable").asBoolean());
            assertFalse(response.body().contains("PRIVATE"));
            assertEquals(before, databaseSnapshot());
        }
    }

    /** Compare only the authorized fixture leaf and language metadata; malformed resources fail the test. */
    private void assertStarting(JsonNode view, String genre, String locale, boolean fallback) throws Exception {
        String expected;
        try (var source = getClass().getResourceAsStream("/packs/" + genre
                + (locale.equals("en") ? "/adventure.json" : "/locales/it.json"))) {
            var document = json.readTree(source);
            expected = locale.equals("en") ? document.get("entrypoint").get("situation").asText() : null;
            if (expected == null) {
                for (var entry : document.get("entries")) {
                    if (entry.get("field").asText().equals("/entrypoint/situation")) { expected = entry.get("text").asText(); }
                }
            }
        }
        assertNotNull(expected);
        var situation = view.get("startingSituation");
        assertEquals(3, situation.size());
        assertEquals(expected, situation.get("text").asText());
        assertEquals(locale, situation.get("locale").asText());
        assertEquals(fallback, situation.get("fallback").asBoolean());
        assertEquals("STARTING_SITUATION", view.get("pendingDecision").get("type").asText());
    }

    /** Explicit audience rows, not equal names or hidden NPC state, determine known facts and quest results. */
    @Test
    void excludesHiddenAndOtherCampaignState() throws Exception {
        JsonNode first = json.readTree(post(payload(UUID.randomUUID())).body());
        UUID firstAdventure = adventure;
        UUID firstRevision = revision;
        adventure = new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(UUID.fromString("00000000-0000-0000-0000-000000000061"),
                    "Harbor", "science", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        UUID secondAdventure = adventure;
        revision = registry.register(adventure, new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/science-fiction-salvage").toURI()))).revisionId();
        JsonNode second = json.readTree(post(payload(UUID.randomUUID())).body());
        adventure = firstAdventure;
        revision = firstRevision;
        UUID campaign = UUID.fromString(first.get("campaignId").asText());
        UUID actor = UUID.fromString(first.get("actorId").asText());
        UUID otherCampaign = UUID.fromString(second.get("campaignId").asText());
        UUID otherActor = UUID.fromString(second.get("actorId").asText());
        UUID npc = UUID.randomUUID();
        jdbc.update("insert into actor (id, campaign_id, kind, display_name, location_id, created_revision, created_at) "
                + "values (?, ?, 'NPC', 'Player', 'hidden-vault', 0, now())", npc, campaign);
        knownRows(campaign, actor, "The visible rumor", "Known task");
        knownRows(campaign, npc, "SECRET NPC TRUTH", "SECRET NPC QUEST");
        knownRows(otherCampaign, otherActor, "SECRET OTHER WORLD", "SECRET OTHER QUEST");
        jdbc.update("update campaign set pending_decision='{\"private\":\"SECRET DECISION\"}', "
                + "row_version=row_version+1 where id=?", campaign);
        jdbc.update("insert into item_instance (id, campaign_id, actor_id, definition_id, quantity, maximum_stack, acquired_revision) "
                + "values (?, ?, ?, 'SECRET ITEM', 1, 1, 0)", UUID.randomUUID(), campaign, npc);
        jdbc.update("insert into learned_ability (campaign_id, actor_id, definition_id, rank, acquired_revision) "
                + "values (?, ?, 'SECRET ABILITY', 1, 0)", campaign, npc);
        var before = databaseSnapshot();
        String path = "/api/v1/campaigns/" + campaign;
        String scope = "?adventureId=" + adventure;
        var response = exchange("GET", path + "/continue" + scope, "", headers());
        assertEquals(200, response.status(), response.body());
        assertFalse(response.body().contains("SECRET"));
        assertFalse(response.body().contains("hidden-vault"));
        JsonNode view = json.readTree(response.body());
        assertTrue(view.get("campaign").get("pendingDecisionAvailable").asBoolean());
        assertTrue(view.get("pendingDecision").isNull());
        assertTrue(view.get("startingSituation").isNull());
        assertEquals(1, view.get("inventory").size());
        assertEquals(1, view.get("abilities").size());
        assertEquals("The visible rumor", view.get("knownFacts").get(0).get("text").asText());
        assertEquals("RUMORED", view.get("knownFacts").get(0).get("confidence").asText());
        assertEquals("Known task", view.get("quests").get(0).get("title").asText());
        assertEquals(1, json.readTree(exchange("GET", path + "/known-state" + scope, "", headers()).body()).get("data").size());
        assertEquals(404, exchange("GET", path + "/state?adventureId=" + secondAdventure, "", headers()).status());
        var otherView = exchange("GET", "/api/v1/campaigns/" + otherCampaign + "/state?adventureId=" + secondAdventure,
                "", headers());
        assertEquals(200, otherView.status(), otherView.body());
        assertEquals("dock", json.readTree(otherView.body()).get("player").get("locationId").asText());
        assertFalse(otherView.body().contains("The visible rumor"));
        assertEquals(before, databaseSnapshot());
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> knownRows(campaign, otherActor, "invalid audience", "invalid quest"));
    }

    /** Insert only public test knowledge; the real command writer must supply canonical evidence atomically. */
    private void knownRows(UUID campaign, UUID actor, String fact, String quest) {
        jdbc.update("insert into campaign_known_fact (id, campaign_id, actor_id, fact_text, confidence, learned_revision) "
                + "values (?, ?, ?, ?, 'RUMORED', 0)", UUID.randomUUID(), campaign, actor, fact);
        jdbc.update("insert into campaign_known_quest (id, campaign_id, actor_id, title, objective, status, updated_revision) "
                + "values (?, ?, ?, ?, 'Ask the harbor keeper', 'ACTIVE', 0)", UUID.randomUUID(), campaign, actor, quest);
    }

    /** Bounded keyset pages remain owner-scoped, and all missing/foreign/mismatched reads share one failure. */
    @Test
    void paginatesAndRejectsForeignOwnerAcrossEverySection() throws Exception {
        var first = json.readTree(post(payload(UUID.randomUUID())).body());
        var second = json.readTree(post(payload(UUID.randomUUID())).body());
        var page = json.readTree(exchange("GET", "/api/v1/campaigns?limit=1", "", headers()).body());
        assertEquals(1, page.get("campaigns").size());
        String cursor = page.get("nextAfter").asText();
        var last = json.readTree(exchange("GET", "/api/v1/campaigns?limit=1&after=" + cursor, "", headers()).body());
        assertEquals(1, last.get("campaigns").size());
        assertTrue(last.get("nextAfter").isNull());
        assertNotEquals(page.get("campaigns").get(0).get("campaignId"), last.get("campaigns").get(0).get("campaignId"));
        assertEquals(0, json.readTree(exchange("GET", "/api/v1/campaigns?adventureId=" + UUID.randomUUID(), "", headers())
                .body()).get("campaigns").size());
        for (String bad : new String[] {"limit=0", "limit=101", "limit=x", "after=x", "adventureId=1-1-1-1-1"}) {
            assertEquals(400, exchange("GET", "/api/v1/campaigns?" + bad, "", headers()).status());
        }
        UUID foreignOwner = UUID.randomUUID();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Other owner', 'en', now(), now())", foreignOwner);
        UUID foreignAdventure = new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(foreignOwner, "Harbor", "harbor", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        UUID foreignRevision = registry.register(foreignAdventure, new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI()))).revisionId();
        var foreignCampaign = creation.create(foreignOwner, foreignAdventure,
                new CampaignCreationService.Request(UUID.randomUUID(), foreignRevision, "Campaign", SupportedLocale.EN,
                        new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)));
        var before = databaseSnapshot();
        var ownerList = json.readTree(exchange("GET", "/api/v1/campaigns", "", headers()).body()).get("campaigns");
        assertEquals(2, ownerList.size());
        assertFalse(ownerList.toString().contains(foreignCampaign.campaignId().toString()));
        assertTrue(json.readTree(exchange("GET", "/api/v1/campaigns?adventureId=" + foreignAdventure, "", headers())
                .body()).get("campaigns").isEmpty());
        for (String section : new String[] {"continue", "state", "known-state", "inventory", "quests", "abilities"}) {
            String root = "/api/v1/campaigns/";
            var hidden = exchange("GET", root + foreignCampaign.campaignId() + "/" + section
                    + "?adventureId=" + foreignAdventure, "", headers());
            var missing = exchange("GET", root + UUID.randomUUID() + "/" + section
                    + "?adventureId=" + adventure, "", headers());
            assertEquals(404, hidden.status(), hidden.body());
            assertEquals(hidden.body(), missing.body());
            assertEquals(200, exchange("GET", root + first.get("campaignId").asText() + "/" + section
                    + "?adventureId=" + adventure, "", headers()).status());
            assertEquals(403, exchange("GET", root + second.get("campaignId").asText() + "/" + section
                    + "?adventureId=" + adventure, "", headers().replace("http://127.0.0.1:8080", "http://foreign.invalid")).status());
        }
        assertEquals(before, databaseSnapshot());
    }

    /** Snapshot every public-schema row deterministically; any consultation side effect fails comparison. */
    private Map<String, String> databaseSnapshot() {
        var snapshot = new java.util.TreeMap<String, String>();
        for (String table : jdbc.queryForList("select table_name from information_schema.tables "
                + "where table_schema='public' order by table_name", String.class)) {
            snapshot.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(x.row order by x.row::text), '[]')::text "
                    + "from (select to_jsonb(t) as row from " + table + " t) x", String.class));
        }
        return snapshot;
    }

    /** A commit between SQL statements cannot pair a new resource value with an older revision token. */
    @Test
    void keepsOneReadOnlySnapshotDuringConcurrentCommit() throws Exception {
        var created = json.readTree(post(payload(UUID.randomUUID())).body());
        UUID campaign = UUID.fromString(created.get("campaignId").asText());
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000061");
        var advanced = new java.util.concurrent.atomic.AtomicBoolean();
        try (var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            // Intercept real SQL only to schedule a competing committed writer after scope authorization.
            var observed = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())) {
                /** Run the real query; after the first campaign read, commit a concurrent resource/world update. */
                @Override
                public <T> java.util.List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper,
                        Object... args) {
                    assertEquals("on", jdbc.queryForObject("show transaction_read_only", String.class));
                    assertEquals("repeatable read", jdbc.queryForObject("show transaction_isolation", String.class));
                    var result = super.query(sql, mapper, args);
                    if (sql.contains("where a.owner_id=? and c.adventure_id=? and c.id=?")
                            && advanced.compareAndSet(false, true)) {
                        try {
                            writer.submit(() -> new TransactionTemplate(manager).execute(status -> {
                                jdbc.update("update campaign set state_revision=1, row_version=row_version+1 where id=?", campaign);
                                jdbc.update("update actor_resource set current_value=current_value-1, "
                                        + "updated_revision=1, row_version=row_version+1 where campaign_id=?", campaign);
                                return null;
                            })).get(10, TimeUnit.SECONDS);
                        } catch (Exception failure) { throw new AssertionError("concurrent writer failed", failure); }
                    }
                    return result;
                }
            };
            var service = new CampaignProjectionService(observed, manager, registry);
            var old = service.read(owner, adventure, campaign);
            assertTrue(advanced.get());
            assertEquals(0, old.scope().stateRevision());
            assertEquals(old.resources().getFirst().maximum(), old.resources().getFirst().current());
            var current = service.read(owner, adventure, campaign);
            assertEquals(1, current.scope().stateRevision());
            assertEquals(old.resources().getFirst().current() - 1, current.resources().getFirst().current());
            assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(status ->
                    service.read(owner, adventure, campaign)));
        }
    }

    /** Collection bounds fail explicitly, preserving all persisted rows rather than returning a partial truth. */
    @Test
    void rejectsOversizedKnowledgeProjectionWithoutWrites() throws Exception {
        var created = json.readTree(post(payload(UUID.randomUUID())).body());
        UUID campaign = UUID.fromString(created.get("campaignId").asText());
        UUID actor = UUID.fromString(created.get("actorId").asText());
        jdbc.update("insert into campaign_known_fact (id, campaign_id, actor_id, fact_text, confidence, learned_revision) "
                + "select gen_random_uuid(), ?, ?, 'Visible fact ' || n, 'KNOWN', 0 from generate_series(1,1001) n", campaign, actor);
        var before = databaseSnapshot();
        var response = exchange("GET", "/api/v1/campaigns/" + campaign + "/known-state?adventureId=" + adventure, "", headers());
        assertEquals(503, response.status(), response.body());
        assertEquals("{\"code\":\"CAMPAIGN_READ_CONTENT_UNAVAILABLE\"}", response.body());
        assertEquals(before, databaseSnapshot());
    }

    /** Older saves with no creation receipt remain inspectable and never borrow a same-name actor. */
    @Test
    void representsLegacyCharacterAsUnavailable() throws Exception {
        UUID legacy = UUID.randomUUID();
        jdbc.update("insert into campaign (id, adventure_id, content_revision_id, title, seed_identity, mode, created_at, updated_at) "
                + "values (?, ?, ?, 'Legacy save', 'private-legacy-seed', 'EXPLORATION', now(), now())", legacy, adventure, revision);
        var before = databaseSnapshot();
        var response = exchange("GET", "/api/v1/campaigns/" + legacy + "/state?adventureId=" + adventure, "", headers());
        assertEquals(200, response.status(), response.body());
        var view = json.readTree(response.body());
        assertTrue(view.get("player").isNull());
        assertTrue(view.get("startingSituation").isNull());
        assertTrue(view.get("pendingDecision").isNull());
        assertTrue(view.get("resources").isEmpty());
        assertFalse(response.body().contains("private-legacy-seed"));
        assertEquals(before, databaseSnapshot());
    }

    /** Two genre campaigns and their exact retry receipts survive stopping and starting the whole JVM. */
    @Test
    void survivesApplicationProcessRestart() throws Exception {
        UUID science = registry.register(adventure, new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/science-fiction-salvage").toURI()))).revisionId();
        int fixturePort = port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        String firstPayload = payload(UUID.randomUUID());
        String secondPayload = payload(UUID.randomUUID()).replace(revision.toString(), science.toString());
        Process process = null;
        try {
            process = startApplication("before");
            bootstrap();
            var first = post(firstPayload);
            var second = post(secondPayload);
            assertEquals(201, first.status(), first.body());
            assertEquals(201, second.status(), second.body());
            assertNotEquals(first.body(), second.body());
            String firstRead = "/api/v1/campaigns/" + json.readTree(first.body()).get("campaignId").asText()
                    + "/continue?adventureId=" + adventure;
            String secondRead = "/api/v1/campaigns/" + json.readTree(second.body()).get("campaignId").asText()
                    + "/continue?adventureId=" + adventure;
            var firstView = exchange("GET", firstRead, "", headers());
            var secondView = exchange("GET", secondRead, "", headers());
            assertEquals(200, firstView.status(), firstView.body());
            assertEquals(200, secondView.status(), secondView.body());
            assertStarting(json.readTree(firstView.body()), "fantasy-exploration", "en", false);
            assertStarting(json.readTree(secondView.body()), "science-fiction-salvage", "en", false);
            stopApplication(process);
            process = startApplication("after");
            assertEquals(403, post(firstPayload).status(), "old sessions must not survive process restart");
            assertEquals(403, exchange("GET", firstRead, "", headers()).status());
            bootstrap();
            assertEquals(first.body(), post(firstPayload).body());
            assertEquals(second.body(), post(secondPayload).body());
            assertEquals(firstView.body(), exchange("GET", firstRead, "", headers()).body());
            assertEquals(secondView.body(), exchange("GET", secondRead, "", headers()).body());
            assertEquals(2, json.readTree(exchange("GET", "/api/v1/campaigns", "", headers()).body()).get("campaigns").size());
            assertEquals(2, jdbc.queryForObject("select count(*) from campaign where state_revision=0 and game_turn=0 "
                    + "and event_sequence=0 and world_time_seconds=0 and pending_decision='{\"type\":\"STARTING_SITUATION\",\"version\":1}'::jsonb", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(*) from character_state", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(*) from item_instance", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(*) from learned_ability", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(*) from actor_resource where current_value=maximum_value", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from actor where location_id='trailhead'", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from actor where location_id='dock'", Integer.class));
        } finally {
            if (process != null) { stopApplication(process); }
            port = fixturePort;
        }
    }

    /** Launch the actual application entry point in a separate JVM; bounded readiness or process exit fails clearly. */
    private Process startApplication(String phase) throws Exception {
        Path log = Path.of("target", "p061-restart-" + phase + ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "org.rpgengine.EngineApplication",
                "--server.port=" + port, "--engine.http.owner-id=00000000-0000-0000-0000-000000000061",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                try {
                    if (exchange("GET", "/actuator/health/readiness", "", "").status() == 200) {
                        return process;
                    }
                } catch (java.io.IOException unavailable) {
                    // Startup has not yet bound its loopback listener; never treat this as readiness.
                }
                Thread.sleep(100);
            }
            fail("application restart readiness failed: " + Files.readString(log));
            return process;
        } catch (Throwable failure) {
            stopApplication(process);
            throw failure;
        }
    }

    /** Stop only the child created by this test; force termination on timeout and fail the graceful-stop check. */
    private static void stopApplication(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                fail("application did not stop gracefully");
            }
        }
    }

    /** Obtain fresh browser proofs from the current process; prior cookies are intentionally not reused. */
    private void bootstrap() throws Exception {
        var session = exchange("GET", "/api/v1/session", "", "");
        assertEquals(200, session.status());
        csrf = json.readTree(session.body()).get("csrfToken").asText();
        cookie = session.headers().lines().filter(line -> line.toLowerCase().startsWith("set-cookie:"))
                .findFirst().orElseThrow().substring("set-cookie:".length()).strip().split(";", 2)[0];
    }

    /** Shape, UTF-8, duplicate keys, coercion, oversized bodies and illegal budgets fail before any insert. */
    @Test
    void rejectsMalformedAndIllegalChoices() throws Exception {
        String valid = payload(UUID.randomUUID());
        for (String malformed : new String[] {"{}", valid + "{}", valid.replace("\"title\":", "\"ownerId\":\"x\",\"title\":"),
                valid.replace("\"Campaign\"", "false"), valid.replace("\"title\":", "\"title\":\"duplicate\",\"title\":"),
                valid.replace("\"attributes\":{}", "\"attributes\":{\"awareness\":\"2\"}"),
                valid.replace("\"attributes\":{}", "\"attributes\":{\"awareness\":2.0}")}) {
            assertEquals(400, post(malformed).status(), malformed);
        }
        assertEquals(413, post(" ".repeat(16_385)).status());
        assertEquals(400, exchange("POST", "/api/v1/campaigns", new byte[] {(byte) 0xc3, 0x28}, headers(), false).status());
        assertEquals(413, exchange("POST", "/api/v1/campaigns", " ".repeat(16_385).getBytes(StandardCharsets.UTF_8),
                headers(), true).status());
        String illegal = valid.replace("\"archetypeId\":\"surveyor\"", "\"archetypeId\":null")
                .replace("\"attributes\":{}", "\"attributes\":{\"awareness\":100}");
        assertEquals(422, post(illegal).status());
        assertEquals(0, jdbc.queryForObject("select count(*) from campaign", Integer.class));
    }

    /** Chunked input receives the same bounded strict parser and durable retry behavior as fixed-length JSON. */
    @Test
    void acceptsBoundedChunkedCreation() throws Exception {
        String payload = payload(UUID.randomUUID());
        var created = exchange("POST", "/api/v1/campaigns", payload.getBytes(StandardCharsets.UTF_8), headers(), true);
        assertEquals(201, created.status(), created.body());
        assertEquals(created.body(), post(payload).body());
        assertEquals(1, jdbc.queryForObject("select count(*) from campaign", Integer.class));
    }

    /** Foreign scope and forged browser proof cannot expose IDs, SQL errors or create grants. */
    @Test
    void rejectsForeignScopesAndMissingAdmission() throws Exception {
        var foreign = new TransactionTemplate(manager).execute(status -> {
            var owner = OwnerProfile.create("Other", Instant.now());
            entities.persist(owner);
            var workspace = AdventureWorkspace.create(owner.getId(), "Other", "other", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        String payload = payload(UUID.randomUUID());
        var denied = post(payload.replace(adventure.toString(), foreign.toString()));
        assertEquals(404, denied.status());
        assertEquals("{\"code\":\"CREATION_SCOPE_UNAVAILABLE\"}", denied.body());
        assertEquals(404, post(payload.replace(revision.toString(), UUID.randomUUID().toString())).status());
        assertEquals(403, exchange("POST", "/api/v1/campaigns", payload,
                "Origin: http://127.0.0.1:8080\r\nContent-Type: application/json\r\n").status());
        assertEquals(403, exchange("POST", "/api/v1/campaigns", payload, headers().replace(
                "Origin: http://127.0.0.1:8080", "Origin: https://evil.test")).status());
        assertEquals(415, exchange("POST", "/api/v1/campaigns", payload,
                headers().replace("application/json", "text/plain")).status());
        assertEquals(0, jdbc.queryForObject("select count(*) from campaign", Integer.class));
    }

    /** Encode one complete public request with explicit locale and character choices. */
    private String payload(UUID requestId) {
        return json.writeValueAsString(Map.of("requestId", requestId, "adventureId", adventure,
                "contentRevisionId", revision, "title", "Campaign", "narrativeLocale", "en", "choices",
                Map.of("name", "Player", "archetypeId", "surveyor", "attributes", Map.of(), "skills", Map.of())));
    }

    /** Return valid browser headers for the bootstrapped session; tests replace individual proofs. */
    private String headers() {
        return "Origin: http://127.0.0.1:8080\r\nContent-Type: application/json\r\nCookie: " + cookie
                + "\r\nX-CSRF-Token: " + csrf + "\r\n";
    }

    /** Submit JSON to the actual servlet stack; transport errors fail the test. */
    private Reply post(String body) throws Exception {
        return exchange("POST", "/api/v1/campaigns", body, headers());
    }

    /**
     * Use a bounded loopback socket to control the external Host independently of the random test port.
     * Connection-close and explicit byte lengths avoid HTTP client Host rewriting; decode response chunks as bytes.
     */
    private Reply exchange(String method, String path, String body, String headers) throws Exception {
        return exchange(method, path, body.getBytes(StandardCharsets.UTF_8), headers, false);
    }

    /** Send exact raw bytes with explicit or chunked framing; malformed UTF-8 is never normalized by a client. */
    private Reply exchange(String method, String path, byte[] bytes, String headers, boolean chunked) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:8080\r\n"
                    + "Connection: close\r\n" + headers + (chunked ? "Transfer-Encoding: chunked\r\n\r\n"
                    : "Content-Length: " + bytes.length + "\r\n\r\n"))
                    .getBytes(StandardCharsets.US_ASCII));
            if (chunked) {
                socket.getOutputStream().write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            }
            socket.getOutputStream().write(bytes);
            if (chunked) {
                socket.getOutputStream().write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            // HTTP chunk sizes count octets, so retain a one-to-one byte mapping until framing is removed.
            // Decoding UTF-8 first corrupts offsets for accented Italian text and split multibyte characters.
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            String[] parts = response.split("\r\n\r\n", 2);
            String bodyText = parts[1];
            if (parts[0].toLowerCase().contains("transfer-encoding: chunked")) {
                var decoded = new StringBuilder();
                int offset = 0;
                while (true) {
                    int end = bodyText.indexOf("\r\n", offset);
                    int length = Integer.parseInt(bodyText.substring(offset, end), 16);
                    if (length == 0) { break; }
                    decoded.append(bodyText, end + 2, end + 2 + length);
                    offset = end + 2 + length + 2;
                }
                bodyText = decoded.toString();
            }
            bodyText = new String(bodyText.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            return new Reply(Integer.parseInt(parts[0].split(" ", 3)[1]), parts[0], bodyText);
        }
    }

    /** Detached HTTP status, raw headers and JSON body; contains no live connection. */
    private record Reply(int status, String headers, String body) {
    }
}
