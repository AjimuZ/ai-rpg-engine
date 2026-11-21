package org.rpgengine.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.persistence.EntityManager;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.assets.RuntimeImageJobs.Admission;
import org.rpgengine.assets.RuntimeImageJobs.Capability;
import org.rpgengine.assets.RuntimeImageJobs.Settings;
import org.rpgengine.assets.RuntimeImageRequests.State;
import org.rpgengine.assets.RuntimeImageRequests.Subject;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.Actor;
import org.rpgengine.characters.ActorKind;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.commands.CommandExecutionService;
import org.rpgengine.commands.OperationAdmissionService;
import org.rpgengine.commands.OperationStatus;
import org.rpgengine.commands.TypedCommand;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.inference.ImageGenerationPort;
import org.rpgengine.inference.ImageHandle;
import org.rpgengine.inference.ImageProvenance;
import org.rpgengine.inference.ImageRequest;
import org.rpgengine.inference.ImageResult;
import org.rpgengine.inference.admission.ImageAdmission;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL acceptance of the runtime image game flow (P17.6-C) over a scripted image port: which subjects the
 * player can ask an image for, that the appearance text sent to the port comes only from public pack fields, the
 * pending, ready and failed states a screen reads back, and that a disabled, unavailable or exhausted capability
 * leaves play on the fallback art. The properties equal those of {@link ManagedAssetsTest}, so the suites share one
 * application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.http.owner-id=00000000-0000-0000-0000-000000000061")
class RuntimeImageRequestsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000061");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Public NPC: role and description are its appearance. */
    private static final String KEEPER_LOOK = "Ferry keeper. A broad man in an oilskin coat.";
    /** Hidden NPC: its description names a secret that must never reach a prompt or a listing. */
    private static final String SECRET = "Secretly the raider's captain";
    @Autowired private ManagedAssets assets;
    @Autowired private RuntimeImageRequests wired;
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @LocalServerPort private int httpPort;
    private final ScriptedPort port = new ScriptedPort();
    private UUID adventure;
    private UUID campaign;
    private UUID keeper;
    private UUID stranger;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    RuntimeImageRequestsTest() { }

    /**
     * Seal the sample with a public and a hidden NPC, start a campaign at the ford camp, place both NPCs there and
     * wait once, so the player's perception records what it sees.
     */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        adventure = new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(OWNER, "Lantern Ford", "fantasy", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        UUID sealed = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(pack())))).revisionId();
        campaign = creation.create(OWNER, adventure, new CampaignCreationService.Request(UUID.randomUUID(), sealed,
                "Ford watch", SupportedLocale.EN, new CharacterChoices("Player", "scout", Map.of(), Map.of(), null, null)))
                .campaignId();
        keeper = actor("keeper", "Old Bram");
        stranger = actor("stranger", "Hooded figure");
        long revision = jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign);
        var wait = new TypedCommand.Wait(30);
        assertEquals(OperationStatus.COMMITTED, executions.execute(OWNER, adventure, campaign,
                new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, wait.type(), wait.payload()))
                .operation().status());
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
     * The player can ask for the scene of the current place, the portrait of an NPC seen here and the art of carried
     * items, and nothing else; the port receives only the category framing and the public appearance field, and the
     * listing reads back pending, ready and failed states.
     */
    @Test
    void requestsVisibleSubjectsWithPublicAppearanceAndReportsTheirState() {
        RuntimeImageRequests requests = requests(jobs(new Settings(true, 4, 12, Duration.ofHours(1), 1,
                Duration.ofMinutes(5), Duration.ZERO)));
        var listing = requests.list(OWNER, adventure, campaign).orElseThrow();
        assertEquals(Capability.ENABLED, listing.capability());
        assertEquals(List.of("scene:ford-camp:Ford Camp:MISSING", "portrait:" + keeper + ":Old Bram:MISSING",
                "item:cloak:Cloak:MISSING", "item:kit:Trail Kit:MISSING", "item:sword:Sword:READY"),
                listing.subjects().stream().map(RuntimeImageRequestsTest::line).toList(),
                "the hidden NPC, the far bank and items the player does not carry are not listed");

        var scene = requests.request(OWNER, adventure, campaign, AssetCategory.SCENE, "ford-camp").orElseThrow();
        assertEquals(Admission.QUEUED, scene.admission());
        assertEquals(State.PENDING, scene.subject().state());
        assertEquals("scenic background illustration, wide establishing view, A ring of stones and a lantern post "
                + "beside a shallow river crossing.", port.requests.getFirst().prompt());
        var portrait = requests.request(OWNER, adventure, campaign, AssetCategory.PORTRAIT, keeper.toString())
                .orElseThrow();
        assertEquals(Admission.QUEUED, portrait.admission());
        assertEquals(List.of(State.PENDING, State.PENDING), states(requests, "scene", "portrait"));
        assertEquals(1, port.requests.size(), "one job of the campaign at a time");

        port.complete(0, png(10));
        assertEquals(List.of(State.READY, State.PENDING), states(requests, "scene", "portrait"));
        assertEquals(ManagedAssets.sha256(png(10)),
                assets.current(campaign, AssetCategory.SCENE, "ford-camp").orElseThrow().sha256());
        assertEquals("character portrait, head and shoulders, " + KEEPER_LOOK, port.requests.get(1).prompt());

        port.fail(1, "image-server-busy");
        Subject failed = subject(requests, "portrait");
        assertEquals(State.FAILED, failed.state());
        assertEquals("image-server-busy", failed.errorCode());
        // A retry after a failure is a new job; a backend code outside the plain lowercase form is not repeated.
        assertEquals(Admission.QUEUED, requests.request(OWNER, adventure, campaign, AssetCategory.PORTRAIT,
                keeper.toString()).orElseThrow().admission());
        port.fail(2, "Bad Code <b>");
        assertEquals(RuntimeImageRequests.FAILED, subject(requests, "portrait").errorCode());

        // The pack's authored sword art is in force: asking again generates nothing.
        var sword = requests.request(OWNER, adventure, campaign, AssetCategory.ITEM, "sword").orElseThrow();
        assertEquals(Admission.REUSED, sword.admission());
        assertEquals(State.READY, sword.subject().state());
        assertEquals(3, port.requests.size());
        assertEquals(Admission.QUEUED, requests.request(OWNER, adventure, campaign, AssetCategory.ITEM, "kit")
                .orElseThrow().admission());
        assertEquals("single object illustration, centered on a plain background, A green canvas pouch.",
                port.requests.get(3).prompt(), "the appearance summary, never the item description");

        // Subjects out of view, hidden, unknown or in another scope are the same refusal and queue nothing.
        for (var refused : List.of(List.of("portrait", stranger.toString()), List.of("scene", "far-bank"),
                List.of("item", "raider"), List.of("portrait", "keeper"))) {
            assertTrue(requests.request(OWNER, adventure, campaign, AssetCategory.fromWire(refused.get(0)),
                    refused.get(1)).isEmpty(), refused.toString());
        }
        assertTrue(requests.request(UUID.randomUUID(), adventure, campaign, AssetCategory.SCENE, "ford-camp").isEmpty());
        assertTrue(requests.list(OWNER, UUID.randomUUID(), campaign).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> requests.request(OWNER, adventure, campaign, AssetCategory.MAP, "ford-camp"));
        assertThrows(IllegalArgumentException.class,
                () -> requests.request(OWNER, adventure, campaign, AssetCategory.ITEM, "../kit"));
        assertEquals(4, port.requests.size());
        for (ImageRequest sent : port.requests) {
            assertFalse(sent.prompt().contains(SECRET) || sent.prompt().contains("A pouch of tools")
                    || sent.prompt().contains(campaign.toString()) || sent.prompt().contains("Old Bram"), sent.prompt());
        }
    }

    /**
     * Without the owner switch, without an image capability, or with the budget spent, a request is answered at once,
     * nothing is generated or stored, and every subject keeps its fallback state so play goes on.
     */
    @Test
    void leavesPlayOnFallbackArtWithoutCapabilityOrBudget() throws Exception {
        RuntimeImageRequests disabled = requests(jobs(new Settings(false, 4, 12, Duration.ofHours(1), 1,
                Duration.ofMinutes(5), Duration.ZERO)));
        assertEquals(Capability.DISABLED, disabled.list(OWNER, adventure, campaign).orElseThrow().capability());
        var off = disabled.request(OWNER, adventure, campaign, AssetCategory.SCENE, "ford-camp").orElseThrow();
        assertEquals(Admission.DISABLED, off.admission());
        assertEquals(State.MISSING, off.subject().state());

        RuntimeImageRequests unavailable = requests(new RuntimeImageJobs(jdbc, assets, null, ImageAdmission.direct(),
                new Settings(true, 4, 12, Duration.ofHours(1), 1, Duration.ofMinutes(5), Duration.ZERO), Clock.systemUTC()));
        assertEquals(Capability.UNAVAILABLE, unavailable.list(OWNER, adventure, campaign).orElseThrow().capability());
        assertEquals(Admission.UNAVAILABLE, unavailable.request(OWNER, adventure, campaign, AssetCategory.ITEM, "kit")
                .orElseThrow().admission());

        RuntimeImageRequests spent = requests(jobs(new Settings(true, 4, 1, Duration.ofHours(1), 1,
                Duration.ofMinutes(5), Duration.ZERO)));
        assertEquals(Admission.QUEUED, spent.request(OWNER, adventure, campaign, AssetCategory.SCENE, "ford-camp")
                .orElseThrow().admission());
        var exhausted = spent.request(OWNER, adventure, campaign, AssetCategory.ITEM, "kit").orElseThrow();
        assertEquals(Admission.BUDGET_EXHAUSTED, exhausted.admission());
        assertEquals(State.MISSING, exhausted.subject().state());
        var listing = spent.list(OWNER, adventure, campaign).orElseThrow();
        assertEquals(new RuntimeImageJobs.Budget(1, 1, 0, true), listing.budget());
        assertEquals(State.MISSING, listing.subjects().stream().filter(item -> item.subject().equals("kit")).findFirst()
                .orElseThrow().state());
        assertEquals(1, port.requests.size());
        assertEquals(0, jdbc.queryForObject("select count(*) from asset_association", Integer.class));

        // The packaged default leaves generation off: the real route lists the public subjects and refuses a request
        // with a 200 answer naming the reason, so the screen keeps its fallback.
        String cookie = session();
        Reply list = send("GET", "/api/v1/campaigns/" + campaign + "/artwork/requests?adventureId=" + adventure, cookie,
                null, null);
        assertEquals(200, list.status(), list.head());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JSON.readValue(list.body(), Map.class);
        assertEquals("disabled", body.get("capability"));
        assertEquals(List.of("category", "subject", "label", "state", "errorCode"),
                new ArrayList<>(((List<Map<String, Object>>) body.get("subjects")).getFirst().keySet()));
        assertFalse(new String(list.body(), StandardCharsets.UTF_8).contains(stranger.toString()));
        assertFalse(new String(list.body(), StandardCharsets.UTF_8).contains(SECRET));
        String csrf = csrf(cookie);
        Reply asked = send("POST", "/api/v1/campaigns/" + campaign + "/artwork/requests?adventureId=" + adventure,
                cookie, csrf, "{\"category\":\"scene\",\"subject\":\"ford-camp\"}");
        assertEquals(200, asked.status(), asked.head());
        assertEquals("disabled", JSON.readValue(asked.body(), Map.class).get("admission"));
        Reply hidden = send("POST", "/api/v1/campaigns/" + campaign + "/artwork/requests?adventureId=" + adventure,
                cookie, csrf, "{\"category\":\"portrait\",\"subject\":\"" + stranger + "\"}");
        Reply unknown = send("POST", "/api/v1/campaigns/" + campaign + "/artwork/requests?adventureId=" + adventure,
                cookie, csrf, "{\"category\":\"portrait\",\"subject\":\"" + UUID.randomUUID() + "\"}");
        assertEquals(404, hidden.status());
        assertTrue(Arrays.equals(hidden.body(), unknown.body()), "a hidden subject answers like a missing one");
        Reply extra = send("POST", "/api/v1/campaigns/" + campaign + "/artwork/requests?adventureId=" + adventure,
                cookie, csrf, "{\"category\":\"scene\",\"subject\":\"ford-camp\",\"appearance\":\"anything\"}");
        assertEquals(400, extra.status(), "the client cannot supply appearance text");
        assertNull(wired.list(OWNER, adventure, campaign).orElseThrow().subjects().stream()
                .filter(item -> item.state() != State.MISSING && item.state() != State.READY).findFirst().orElse(null));
    }

    private RuntimeImageJobs jobs(Settings settings) {
        return new RuntimeImageJobs(jdbc, assets, port, ImageAdmission.direct(), settings, Clock.systemUTC());
    }

    private RuntimeImageRequests requests(RuntimeImageJobs jobs) {
        return new RuntimeImageRequests(jdbc, manager, registry, assets, jobs);
    }

    private static String line(Subject subject) {
        return subject.category().wire() + ":" + subject.subject() + ":" + subject.label() + ":" + subject.state();
    }

    private List<State> states(RuntimeImageRequests requests, String... categories) {
        return Arrays.stream(categories).map(category -> subject(requests, category).state()).toList();
    }

    private Subject subject(RuntimeImageRequests requests, String category) {
        return requests.list(OWNER, adventure, campaign).orElseThrow().subjects().stream()
                .filter(item -> item.category().wire().equals(category)).findFirst().orElseThrow();
    }

    private UUID actor(String definition, String name) {
        return new TransactionTemplate(manager).execute(status -> {
            var actor = Actor.create(campaign, ActorKind.NPC, definition, name, "ford-camp", 0, Instant.now());
            entities.persist(actor);
            return actor.getId();
        });
    }

    /** The bundled sample plus a public ferry keeper and a hidden watcher at the ford camp. */
    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> pack() throws Exception {
        var files = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(RuntimeImageRequestsTest.class.getResource("/sample-adventure").toURI())).files());
        files.put("npcs.json", JSON.writeValueAsBytes(Map.of("schemaVersion", 1, "definitions", List.of(
                Map.of("id", "keeper", "kind", "npc", "data", Map.of("name", "Keeper", "role", "Ferry keeper",
                        "description", "A broad man in an oilskin coat.", "location", "ford-camp")),
                Map.of("id", "stranger", "kind", "npc", "data", Map.of("name", "Stranger", "role", "Hooded watcher",
                        "description", SECRET + ".", "knowledge", "secret", "location", "ford-camp"))))));
        Map<String, Object> manifest = JSON.readValue(files.get("adventure.json"), Map.class);
        var listed = new ArrayList<>((List<Object>) manifest.get("files"));
        listed.add(Map.of("path", "npcs.json", "category", "npcs"));
        manifest.put("files", listed);
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        return files;
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

    /** Bootstrap a local session and return its cookie pair. */
    private String session() throws Exception {
        Reply reply = send("GET", "/api/v1/session", null, null, null);
        assertEquals(200, reply.status(), reply.head());
        return reply.head().lines().filter(line -> line.toLowerCase().startsWith("set-cookie:")).findFirst().orElseThrow()
                .substring("set-cookie:".length()).strip().split(";", 2)[0];
    }

    /** The CSRF proof of an existing session, as the bootstrap reply carries it. */
    private String csrf(String cookie) throws Exception {
        Reply reply = send("GET", "/api/v1/session", cookie, null, null);
        assertEquals(200, reply.status(), reply.head());
        return (String) JSON.readValue(reply.body(), Map.class).get("csrfToken");
    }

    /** One request over a bounded loopback socket, so the Host and Origin headers stay the admitted ones. */
    private Reply send(String method, String path, String cookie, String csrf, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", httpPort)) {
            socket.setSoTimeout(10_000);
            byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String head = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nConnection: close\r\n"
                    + (cookie == null ? "" : "Cookie: " + cookie + "\r\n")
                    + (csrf == null ? "" : "Origin: http://127.0.0.1:8080\r\nX-CSRF-Token: " + csrf + "\r\n")
                    + (body == null ? "" : "Content-Type: application/json\r\nContent-Length: " + content.length + "\r\n")
                    + "\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(content);
            byte[] raw = socket.getInputStream().readAllBytes();
            String all = new String(raw, StandardCharsets.ISO_8859_1);
            int split = all.indexOf("\r\n\r\n");
            String replyHead = all.substring(0, split);
            int status = Integer.parseInt(replyHead.substring(9, 12));
            byte[] replyBody = Arrays.copyOfRange(raw, split + 4, raw.length);
            return new Reply(status, replyHead, replyHead.toLowerCase().contains("transfer-encoding: chunked")
                    ? dechunk(replyBody) : replyBody);
        }
    }

    /** Remove HTTP chunk framing from a complete body. */
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

    /** Image port whose jobs stay open until the test completes them; it records every request it receives. */
    private static final class ScriptedPort implements ImageGenerationPort {
        final List<ImageRequest> requests = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<ImageResult>> results = new CopyOnWriteArrayList<>();

        @Override
        public String backend() {
            return "scripted";
        }

        @Override
        public ImageHandle submit(ImageRequest request) {
            requests.add(request);
            var future = new CompletableFuture<ImageResult>();
            results.add(future);
            return new ImageHandle() {
                @Override
                public CompletionStage<ImageResult> result() {
                    return future;
                }

                @Override
                public boolean cancel() {
                    return future.complete(ImageResult.cancelled());
                }
            };
        }

        void complete(int index, byte[] png) {
            ImageRequest request = requests.get(index);
            results.get(index).complete(ImageResult.completed(png, new ImageProvenance("scripted", null, null, null,
                    request.seed(), request.steps(), request.guidance(), request.width(), request.height(),
                    "0".repeat(64), "0".repeat(64), ManagedAssets.sha256(png), png.length)));
        }

        void fail(int index, String code) {
            results.get(index).complete(ImageResult.failed(code));
        }
    }
}
