package org.rpgengine.assets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.assets.ManagedAssets.Candidate;
import org.rpgengine.assets.ManagedAssets.Metadata;
import org.rpgengine.assets.ManagedAssets.Status;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.commands.TypedCommand;
import org.rpgengine.content.PackException;
import org.rpgengine.content.PackPresentation;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL and HTTP acceptance of P17.1-A: category separation, stale-result protection, hash-based serving
 * confined to the owning campaign, and metadata that holds nothing but its allowlist. The campaign has entered a
 * fight, so its state revision is above zero and a request can be older or newer than it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.http.owner-id=00000000-0000-0000-0000-000000000061")
class ManagedAssetsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000061");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String AUTHORED = "/packs/fantasy-exploration/assets/map.png";
    @LocalServerPort private int port;
    @Autowired private ManagedAssets assets;
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID adventure;
    private UUID revision;
    private UUID campaign;
    private UUID otherAdventure;
    private UUID otherCampaign;
    private long state;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    ManagedAssetsTest() { }

    /** Seal the sample with its artwork twice, start a campaign in each and advance the first one revision. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        adventure = workspace();
        revision = seal(adventure);
        campaign = campaign(adventure, revision);
        otherAdventure = workspace();
        otherCampaign = campaign(otherAdventure, seal(otherAdventure));
        var execution = executions.execute(OWNER, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), 0, new TypedCommand.Engage("ford-ambush", null).type(),
                new TypedCommand.Engage("ford-ambush", null).payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status());
        state = jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign);
        assertTrue(state >= 1);
    }

    /** Remove disposable rows. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate asset_association, managed_asset, encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, relationship_change, faction_clock_change, learned_recipe, canon_correction, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, "
                + "ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /**
     * A result is accepted once per appearance, the first accepted result wins, and an older appearance, a request
     * made under another pinned pack revision and, for scenes and maps, an older request never replace anything.
     * The database refuses the same overwrite from a caller that bypasses the service.
     */
    @Test
    void protectsAgainstStaleJobResults() {
        byte[] first = png(10);
        var accepted = assets.publish(campaign, candidate(AssetCategory.PORTRAIT, "mira", 1, 0, first));
        assertEquals(Status.ACCEPTED, accepted.status());
        assertEquals(ManagedAssets.sha256(first), accepted.current().sha256());
        assertEquals("image/png", accepted.current().mediaType());

        // The same appearance again, even with other bytes, reuses the accepted result: nothing is stored.
        var repeated = assets.publish(campaign, candidate(AssetCategory.PORTRAIT, "mira", 1, 0, png(11)));
        assertEquals(Status.REUSED, repeated.status());
        assertEquals(accepted.current(), repeated.current());
        assertEquals(1, count("select count(*) from managed_asset"));

        assertEquals(Status.ACCEPTED, assets.publish(campaign,
                candidate(AssetCategory.PORTRAIT, "mira", 2, 0, png(12))).status());
        var late = assets.publish(campaign, candidate(AssetCategory.PORTRAIT, "mira", 1, 0, png(13)));
        assertEquals(Status.STALE_APPEARANCE, late.status());
        assertEquals(2, late.current().appearanceRevision(), "the newer appearance stays in force");
        assertEquals(2, count("select count(*) from asset_association where subject = 'mira'"));
        assertEquals(2, count("select count(*) from managed_asset"), "a stale result stores no bytes");

        // A job requested under a pack revision the campaign does not pin cannot publish.
        var foreign = assets.publish(campaign, new Candidate(AssetCategory.PORTRAIT, "mira", 3, 0, UUID.randomUUID(),
                "Credit", png(14)));
        assertEquals(Status.STALE_CONTENT, foreign.status());
        assertEquals(2, foreign.current().appearanceRevision());

        // Scenes and maps are ordered by request time as well: a delayed request cannot replace the current one.
        assertEquals(Status.ACCEPTED, assets.publish(campaign,
                candidate(AssetCategory.SCENE, "ford", 1, state, png(20))).status());
        var delayed = assets.publish(campaign, candidate(AssetCategory.SCENE, "ford", 2, state - 1, png(21)));
        assertEquals(Status.STALE_SOURCE, delayed.status());
        assertEquals(1, delayed.current().appearanceRevision());
        // An item is not ordered by request time, only by appearance.
        assets.publish(campaign, candidate(AssetCategory.ITEM, "lantern", 1, state, png(30)));
        assertEquals(Status.ACCEPTED, assets.publish(campaign,
                candidate(AssetCategory.ITEM, "lantern", 2, state - 1, png(31))).status());

        // A request from after the campaign's current revision is invalid, not stale.
        assertThrows(IllegalArgumentException.class, () -> assets.publish(campaign,
                candidate(AssetCategory.ITEM, "lantern", 3, state + 1, png(32))));

        // Categories are separate: the same subject text names independent associations.
        assets.publish(campaign, candidate(AssetCategory.MAP, "ford", 1, 0, png(40)));
        assertNotEquals(assets.current(campaign, AssetCategory.SCENE, "ford").orElseThrow().sha256(),
                assets.current(campaign, AssetCategory.MAP, "ford").orElseThrow().sha256());
        assertTrue(assets.current(campaign, AssetCategory.PORTRAIT, "ford").isEmpty());
        // Another campaign never sees this one's association.
        assertTrue(assets.current(otherCampaign, AssetCategory.SCENE, "ford").isEmpty());

        // Without the service the database still refuses an older appearance and any edit.
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into asset_association (campaign_id, category, "
                + "subject, appearance_revision, source_revision, content_revision_id, sha256, credit, created_at) "
                + "select campaign_id, category, subject, 1, source_revision, content_revision_id, sha256, credit, now() "
                + "from asset_association where subject = 'mira' and appearance_revision = 2"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update asset_association set credit = 'Other'"));
    }

    /** Bytes must be allowlisted media that fits the category; a rejected result leaves no row. */
    @Test
    void refusesUnsuitableMediaAndInvalidRequests() {
        byte[] ogg = signed("OggS", 40);
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> assets.publish(campaign, candidate(AssetCategory.MUSIC, "theme", 1, 0, png(1))));
        assertThrows(IllegalArgumentException.class,
                () -> assets.publish(campaign, candidate(AssetCategory.PORTRAIT, "mira", 1, 0, ogg)));
        var failure = assertThrows(PackException.class,
                () -> assets.publish(campaign, candidate(AssetCategory.SCENE, "ford", 1, 0, svg)));
        assertEquals("MEDIA_TYPE", failure.code());
        // A name that is not a stable reference, a bad revision and a bad credit are refused before any SQL.
        for (String subject : new String[] {"../x", "a b", "", "x".repeat(65)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> assets.publish(campaign, candidate(AssetCategory.ITEM, subject, 1, 0, png(2))));
        }
        assertThrows(IllegalArgumentException.class,
                () -> assets.publish(campaign, candidate(AssetCategory.ITEM, "lantern", 0, 0, png(2))));
        for (String credit : new String[] {"", "  ", "line\nbreak", "x".repeat(ManagedAssets.MAX_CREDIT + 1)}) {
            assertThrows(IllegalArgumentException.class, () -> assets.publish(campaign, new Candidate(AssetCategory.ITEM,
                    "lantern", 1, 0, revision, credit, png(2))));
        }
        assertThrows(DataAccessException.class, () -> assets.publish(UUID.randomUUID(), candidate(AssetCategory.ITEM,
                "lantern", 1, 0, png(2))));
        assertEquals(0, count("select count(*) from asset_association"));
        assertEquals(0, count("select count(*) from managed_asset"));
        // A valid audio result is accepted where audio belongs, and keeps its dimensions at zero.
        var music = assets.publish(campaign, candidate(AssetCategory.MUSIC, "theme", 1, 0, ogg)).current();
        assertEquals("audio/ogg", music.mediaType());
        assertEquals(0, music.width());
    }

    /**
     * The route serves a hash only to the owner's campaign that is associated with it or whose pinned pack ships it,
     * with the stored media type, {@code nosniff} and the hash as its tag; anything else is the same not-found.
     */
    @Test
    void servesByHashWithinTheOwningCampaignOnly() throws Exception {
        byte[] art = png(50);
        String hash = ManagedAssets.sha256(art);
        assets.publish(campaign, candidate(AssetCategory.PORTRAIT, "mira", 1, 0, art));
        String cookie = session();
        String base = "/api/v1/campaigns/" + campaign + "/assets/";
        String scope = "?adventureId=" + adventure;

        Reply served = get(base + hash + scope, cookie);
        assertEquals(200, served.status(), served.head());
        assertArrayEquals(art, served.body());
        String head = served.head().toLowerCase();
        assertTrue(head.contains("content-type: image/png"), head);
        assertTrue(head.contains("x-content-type-options: nosniff"), head);
        assertTrue(head.contains("etag: \"" + hash + "\""), head);
        assertTrue(head.contains("content-security-policy: default-src 'none'"), head);
        assertTrue(head.contains("content-length: " + art.length), head);

        // The sealed authored artwork of the pinned pack revision is served by its own hash.
        byte[] authored = resource(AUTHORED);
        Reply pack = get(base + ManagedAssets.sha256(authored) + scope, cookie);
        assertEquals(200, pack.status(), pack.head());
        assertArrayEquals(authored, pack.body());

        // Not admitted, wrong adventure, another campaign with equal bytes, unknown, and malformed requests.
        assertEquals(403, get(base + hash + scope, null).status());
        assertEquals(404, get(base + hash + "?adventureId=" + otherAdventure, cookie).status());
        assertEquals(404, get("/api/v1/campaigns/" + otherCampaign + "/assets/" + hash + "?adventureId=" + otherAdventure,
                cookie).status(), "the other campaign has no association with these bytes");
        assertEquals(404, get(base + "0".repeat(64) + scope, cookie).status());
        assertEquals(404, get("/api/v1/campaigns/" + UUID.randomUUID() + "/assets/" + hash + scope, cookie).status());
        assertEquals("{\"code\":\"ASSET_NOT_FOUND\"}", new String(get(base + "0".repeat(64) + scope, cookie).body(),
                StandardCharsets.UTF_8));
        assertEquals(400, get(base + hash.toUpperCase() + scope, cookie).status());
        assertEquals(400, get(base + hash, cookie).status());
        assertEquals(400, get(base + hash + "?adventureId=" + adventure.toString().toUpperCase(), cookie).status());

        // The other campaign gets the same bytes only after its own accepted association.
        assets.publish(otherCampaign, new Candidate(AssetCategory.PORTRAIT, "mira", 1, 0, otherRevision(), "Credit", art));
        assertEquals(200, get("/api/v1/campaigns/" + otherCampaign + "/assets/" + hash + "?adventureId="
                + otherAdventure, cookie).status());
    }

    /**
     * The listing names the newest appearance per category and subject, keeps categories and campaigns apart, carries
     * only the allowlist and the credit, and answers the same not-found for a campaign the owner does not hold.
     */
    @Test
    void listsTheArtworkInForcePerSubject() throws Exception {
        byte[] first = png(70);
        byte[] second = png(71);
        assets.publish(campaign, candidate(AssetCategory.ITEM, "lantern", 1, 0, first));
        assets.publish(campaign, candidate(AssetCategory.ITEM, "lantern", 2, 0, second));
        assets.publish(campaign, candidate(AssetCategory.PORTRAIT, "lantern", 1, 0, png(72)));
        String cookie = session();
        String path = "/api/v1/campaigns/" + campaign + "/artwork?adventureId=" + adventure;

        Reply reply = get(path, cookie);
        assertEquals(200, reply.status(), reply.head());
        var body = JSON.readTree(new String(reply.body(), StandardCharsets.UTF_8));
        assertEquals(campaign.toString(), body.get("scope").get("campaignId").asString());
        var listed = body.get("artwork");
        // The bundled sample binds its own sword icon to the item definition, listed beside the accepted artwork.
        assertEquals(3, listed.size());
        assertEquals("item", listed.get(0).get("category").asString());
        assertEquals("lantern", listed.get(0).get("subject").asString());
        assertEquals("item", listed.get(1).get("category").asString());
        assertEquals("sword", listed.get(1).get("subject").asString());
        assertEquals("portrait", listed.get(2).get("category").asString());
        assertEquals(2, listed.get(0).get("appearanceRevision").asInt());
        assertEquals(ManagedAssets.sha256(second), listed.get(0).get("sha256").asString());
        assertEquals("Original artwork, MIT", listed.get(0).get("credit").asString());
        assertEquals(Set.of("category", "subject", "appearanceRevision", "sha256", "mediaType", "width", "height", "credit"),
                Set.copyOf(listed.get(0).propertyNames()));

        var sword = listed.get(1);
        byte[] authored = resource("/sample-adventure/assets/sword.png");
        assertEquals(ManagedAssets.sha256(authored), sword.get("sha256").asString(), "the pack's own file, by its hash");
        assertEquals("image/png", sword.get("mediaType").asString());
        assertEquals(96, sword.get("width").asInt());
        assertEquals(96, sword.get("height").asInt());
        assertTrue(sword.get("credit").asString().contains("make-sample-art.mjs"));
        Reply image = get("/api/v1/campaigns/" + campaign + "/assets/" + sword.get("sha256").asString() + "?adventureId="
                + adventure, cookie);
        assertEquals(200, image.status(), image.head());
        assertArrayEquals(authored, image.body());
        assertEquals(1, JSON.readTree(new String(get("/api/v1/campaigns/" + otherCampaign + "/artwork?adventureId="
                + otherAdventure, cookie).body(), StandardCharsets.UTF_8)).get("artwork").size(),
                "another campaign lists only what its own pinned pack binds");

        // An accepted association for the subject is a later, explicit choice and replaces the authored image.
        assets.publish(campaign, candidate(AssetCategory.ITEM, "sword", 1, 0, png(73)));
        var replaced = JSON.readTree(new String(get(path, cookie).body(), StandardCharsets.UTF_8)).get("artwork");
        assertEquals(3, replaced.size());
        assertEquals(ManagedAssets.sha256(png(73)), replaced.get(1).get("sha256").asString());
        assertEquals(403, get(path, null).status());
        assertEquals(404, get("/api/v1/campaigns/" + campaign + "/artwork?adventureId=" + otherAdventure, cookie).status());
        assertEquals(400, get("/api/v1/campaigns/" + campaign + "/artwork", cookie).status());
    }

    /**
     * The music route delivers the pinned pack's mood policy over the closed public states and its own tracks with
     * complete credits and the hash the asset route serves; nothing else is in the body (P17.2-D).
     */
    @Test
    void deliversThePackMoodPolicyAndItsTracks() throws Exception {
        String cookie = session();
        String path = "/api/v1/campaigns/" + campaign + "/music?adventureId=" + adventure;
        Reply reply = get(path, cookie);
        assertEquals(200, reply.status(), reply.head());
        String text = new String(reply.body(), StandardCharsets.UTF_8);
        var body = JSON.readTree(text);
        assertEquals(Set.of("scope", "policy", "tracks"), Set.copyOf(body.propertyNames()));
        assertEquals(campaign.toString(), body.get("scope").get("campaignId").asString());
        var policy = body.get("policy");
        assertEquals(PackPresentation.PUBLIC_STATES, List.copyOf(policy.propertyNames()), "every public state, in order");
        assertEquals("tension", policy.get("offered").asString());
        assertEquals("tension", policy.get("fight").asString());
        assertEquals("exploration", policy.get("quiet").asString());
        var tracks = body.get("tracks");
        assertEquals(2, tracks.size());
        var dusk = tracks.get(0);
        assertEquals(Set.of("id", "mood", "title", "creator", "license", "licenseUrl", "source", "notices", "modifications",
                "sha256", "mediaType"), Set.copyOf(dusk.propertyNames()));
        assertEquals("ford-dusk", dusk.get("id").asString());
        assertEquals("exploration", dusk.get("mood").asString());
        assertEquals("MIT", dusk.get("license").asString());
        assertEquals("https://opensource.org/license/mit", dusk.get("licenseUrl").asString());
        assertEquals("audio/wav", dusk.get("mediaType").asString());
        assertTrue(dusk.get("source").asString().contains("make-sample-music.mjs"));
        assertEquals("tension", tracks.get(1).get("mood").asString());
        byte[] audio = resource("/sample-adventure/assets/ford-dusk.wav");
        assertEquals(ManagedAssets.sha256(audio), dusk.get("sha256").asString());
        Reply served = get("/api/v1/campaigns/" + campaign + "/assets/" + dusk.get("sha256").asString() + "?adventureId="
                + adventure, cookie);
        assertEquals(200, served.status(), served.head());
        assertTrue(served.head().toLowerCase().contains("content-type: audio/wav"), served.head());
        assertArrayEquals(audio, served.body());
        // Nothing hidden: no encounter, participant, location or other definition of the pack is named.
        for (String hidden : new String[] {"ford-ambush", "ford-camp", "stepping", "health", "cudgel", "drive-off", "http://", "path"}) {
            assertFalse(text.contains(hidden), hidden);
        }

        assertEquals(403, get(path, null).status());
        assertEquals(404, get("/api/v1/campaigns/" + campaign + "/music?adventureId=" + otherAdventure, cookie).status());
        assertEquals(404, get("/api/v1/campaigns/" + UUID.randomUUID() + "/music?adventureId=" + adventure, cookie).status());
        assertEquals(400, get("/api/v1/campaigns/" + campaign + "/music", cookie).status());
        assertEquals(400, get("/api/v1/campaigns/" + campaign + "/music?adventureId=" + adventure.toString().toUpperCase(),
                cookie).status());
    }

    /** Stored columns, the metadata record and the served headers hold only the allowlist; no free text but a credit. */
    @Test
    void keepsSecretsOutOfMetadata() throws Exception {
        assets.publish(campaign, candidate(AssetCategory.ITEM, "lantern", 1, 0, png(60)));
        assertEquals(Set.of("sha256", "media_type", "byte_size", "width", "height", "content", "created_at"),
                columns("managed_asset"));
        assertEquals(Set.of("campaign_id", "category", "subject", "appearance_revision", "source_revision",
                "content_revision_id", "sha256", "credit", "created_at"), columns("asset_association"));
        assertEquals(Set.of("category", "subject", "appearanceRevision", "sourceRevision", "sha256", "mediaType", "width",
                "height", "byteSize", "credit"), Arrays.stream(Metadata.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet()));
        String text = JSON.writeValueAsString(assets.current(campaign, AssetCategory.ITEM, "lantern").orElseThrow());
        for (String forbidden : new String[] {"prompt", "seed", "path", "url", "http", "token", "key", "secret"}) {
            assertFalse(text.toLowerCase().contains(forbidden), forbidden);
        }
        assertEquals(10, JSON.readTree(text).size());
    }

    private Candidate candidate(AssetCategory category, String subject, long appearance, long source, byte[] bytes) {
        return new Candidate(category, subject, appearance, source, revision, "Original artwork, MIT", bytes);
    }

    private UUID otherRevision() {
        return jdbc.queryForObject("select content_revision_id from campaign where id = ?", UUID.class, otherCampaign);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private Set<String> columns(String table) {
        return Set.copyOf(jdbc.queryForList("select column_name::text from information_schema.columns where table_schema "
                + "= current_schema() and table_name = ?", String.class, table));
    }

    /** A valid PNG whose bytes, and so hash, depend on the shade. */
    private static byte[] png(int shade) {
        try {
            var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0x010101 * shade);
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] signed(String signature, int length) {
        byte[] bytes = new byte[length];
        System.arraycopy(signature.getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, signature.length());
        return bytes;
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream stream = ManagedAssetsTest.class.getResourceAsStream(name)) {
            return stream.readAllBytes();
        }
    }

    /** The bundled sample plus the authored image, so a sealed revision ships one asset file. */
    private static Map<String, byte[]> pack() throws Exception {
        var files = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(ManagedAssetsTest.class.getResource("/sample-adventure").toURI())).files());
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JSON.readValue(files.get("adventure.json"), Map.class);
        @SuppressWarnings("unchecked")
        var listed = new ArrayList<>((List<Object>) manifest.get("files"));
        listed.add(Map.of("path", "assets/map.png", "category", "asset"));
        manifest.put("files", listed);
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        files.put("assets/map.png", resource(AUTHORED));
        return files;
    }

    private UUID workspace() {
        return new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(OWNER, "Lantern Ford", "fantasy", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
    }

    private UUID seal(UUID target) throws Exception {
        return registry.register(target, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(pack())))).revisionId();
    }

    private UUID campaign(UUID target, UUID sealed) {
        return creation.create(OWNER, target, new CampaignCreationService.Request(UUID.randomUUID(), sealed, "Ford watch",
                SupportedLocale.EN, new CharacterChoices("Player", "scout", Map.of(), Map.of(), null, null))).campaignId();
    }

    /** Bootstrap a local session and return its cookie pair. */
    private String session() throws Exception {
        Reply reply = get("/api/v1/session", null);
        assertEquals(200, reply.status(), reply.head());
        return reply.head().lines().filter(line -> line.toLowerCase().startsWith("set-cookie:")).findFirst().orElseThrow()
                .substring("set-cookie:".length()).strip().split(";", 2)[0];
    }

    /** GET over a bounded loopback socket, so the Host header stays the admitted origin and bytes stay exact. */
    private Reply get(String path, String cookie) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nConnection: close\r\n"
                    + (cookie == null ? "" : "Cookie: " + cookie + "\r\n") + "\r\n").getBytes(StandardCharsets.US_ASCII));
            byte[] raw = socket.getInputStream().readAllBytes();
            String all = new String(raw, StandardCharsets.ISO_8859_1);
            int split = all.indexOf("\r\n\r\n");
            String head = all.substring(0, split);
            int status = Integer.parseInt(head.substring(9, 12));
            byte[] body = Arrays.copyOfRange(raw, split + 4, raw.length);
            return new Reply(status, head, head.toLowerCase().contains("transfer-encoding: chunked") ? dechunk(body) : body);
        }
    }

    /** Remove HTTP chunk framing from a complete body; sizes count octets, so the bytes are never decoded as text. */
    private static byte[] dechunk(byte[] framed) {
        var out = new ByteArrayOutputStream();
        int offset = 0;
        while (true) {
            int end = offset;
            while (framed[end] != '\r') {
                end++;
            }
            int length = Integer.parseInt(new String(framed, offset, end - offset, StandardCharsets.US_ASCII), 16);
            if (length == 0) {
                return out.toByteArray();
            }
            out.write(framed, end + 2, length);
            offset = end + 2 + length + 2;
        }
    }

    private record Reply(int status, String head, byte[] body) {
    }
}
