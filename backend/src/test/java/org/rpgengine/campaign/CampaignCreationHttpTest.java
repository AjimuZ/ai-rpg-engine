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
        jdbc.execute("truncate campaign_creation, character_state, item_instance, learned_ability, plan_disposition, "
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
                + "and event_sequence=0 and world_time_seconds=0 and pending_decision is null", Integer.class));
        for (String table : new String[] {"campaign_event", "operation_journal", "ai_job", "canonical_outbox", "operation_random_draw"}) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
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
            stopApplication(process);
            process = startApplication("after");
            assertEquals(403, post(firstPayload).status(), "old sessions must not survive process restart");
            bootstrap();
            assertEquals(first.body(), post(firstPayload).body());
            assertEquals(second.body(), post(secondPayload).body());
            assertEquals(2, jdbc.queryForObject("select count(*) from campaign where state_revision=0 and game_turn=0 "
                    + "and event_sequence=0 and world_time_seconds=0 and pending_decision is null", Integer.class));
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
     * Connection-close and explicit byte lengths avoid HTTP client Host rewriting and chunk decoding.
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
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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
            return new Reply(Integer.parseInt(parts[0].split(" ", 3)[1]), parts[0], bodyText);
        }
    }

    /** Detached HTTP status, raw headers and JSON body; contains no live connection. */
    private record Reply(int status, String headers, String body) {
    }
}
