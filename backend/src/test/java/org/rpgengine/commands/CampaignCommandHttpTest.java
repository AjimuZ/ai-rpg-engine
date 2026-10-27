package org.rpgengine.commands;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.content.ContentRevision;
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

/**
 * Real servlet and PostgreSQL acceptance of the command admission adapter: 202/200 replay
 * semantics, the status query, strict envelope parsing, stable error codes, foreign scope
 * hiding and the browser-proof requirement inherited from local admission.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.http.owner-id=00000000-0000-0000-0000-000000000071")
class CampaignCommandHttpTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000071");
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    private final JsonMapper json = JsonMapper.builder().build();
    private UUID adventure;
    private UUID campaign;
    private String cookie;
    private String csrf;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    CampaignCommandHttpTest() {
    }

    /** Commit the configured owner with one campaign, then obtain browser proofs from the real server. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        UUID[] scope = campaign(OWNER);
        adventure = scope[0];
        campaign = scope[1];
        var session = exchange("GET", "/api/v1/session", "", "");
        assertEquals(200, session.status());
        csrf = json.readTree(session.body()).get("csrfToken").asText();
        cookie = session.headers().lines().filter(line -> line.toLowerCase().startsWith("set-cookie:"))
                .findFirst().orElseThrow().substring("set-cookie:".length()).strip().split(";", 2)[0];
    }

    /** Delete disposable rows after each test; constraints remain installed. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, identity_owner_profile");
    }

    /** A new command answers 202, the same request replays 200, and the query reads the same operation. */
    @Test
    void admitsReplaysAndReportsStatus() throws Exception {
        String request = UUID.randomUUID().toString();
        var accepted = post(commandsPath(), envelope(request, 0, "{\"target\":\"door\",\"n\":1.50}"));
        assertEquals(202, accepted.status(), accepted.body());
        JsonNode view = json.readTree(accepted.body());
        assertEquals("ACCEPTED", view.get("status").asText());
        assertEquals("MOVE", view.get("commandType").asText());
        assertFalse(view.get("replayed").asBoolean());
        assertFalse(accepted.body().contains("fence") || accepted.body().contains("holder"));
        String operation = view.get("operationId").asText();
        assertEquals("/api/v1/campaigns/" + campaign + "/operations/" + operation + "?adventureId=" + adventure,
                view.get("query").asText());
        // Key order and decimal spelling do not change the canonical identity.
        var replay = post(commandsPath(), envelope(request, 0, "{\"n\":1.5,\"target\":\"door\"}"));
        assertEquals(200, replay.status(), replay.body());
        assertEquals(operation, json.readTree(replay.body()).get("operationId").asText());
        assertTrue(json.readTree(replay.body()).get("replayed").asBoolean());
        var status = exchange("GET", view.get("query").asText(), "", headers());
        assertEquals(200, status.status(), status.body());
        assertEquals(operation, json.readTree(status.body()).get("operationId").asText());
        assertEquals(1, count());
    }

    /** Changed payloads, stale revisions and a busy campaign return fixed 409 codes. */
    @Test
    void reportsConflictsWithStableCodes() throws Exception {
        String request = UUID.randomUUID().toString();
        assertEquals(202, post(commandsPath(), envelope(request, 0, "{\"n\":10}")).status());
        var conflict = post(commandsPath(), envelope(request, 0, "{\"n\":10.0}"));
        assertEquals(409, conflict.status());
        assertEquals("{\"code\":\"COMMAND_REQUEST_CONFLICT\"}", conflict.body());
        assertEquals("{\"code\":\"COMMAND_STALE_REVISION\"}",
                post(commandsPath(), envelope(UUID.randomUUID().toString(), 3, "{}")).body());
        var busy = post(commandsPath(), envelope(UUID.randomUUID().toString(), 0, "{}"));
        assertEquals(409, busy.status());
        assertEquals("{\"code\":\"COMMAND_CAMPAIGN_BUSY\"}", busy.body());
        assertEquals(1, count());
    }

    /** Shape, coercion, duplicate keys, bad identities and oversized bodies fail before storage. */
    @Test
    void rejectsMalformedEnvelopes() throws Exception {
        String request = UUID.randomUUID().toString();
        String valid = envelope(request, 0, "{}");
        for (String malformed : new String[] {"{}", "[]", valid + "{}",
                valid.replace("\"payload\":{}", "\"payload\":{},\"extra\":1"),
                valid.replace("\"payload\":{}", "\"payload\":[]"),
                valid.replace("\"expectedRevision\":0", "\"expectedRevision\":\"0\""),
                valid.replace("\"expectedRevision\":0", "\"expectedRevision\":0.0"),
                valid.replace("\"expectedRevision\":0", "\"expectedRevision\":-1"),
                valid.replace("\"expectedRevision\":0", "\"expectedRevision\":9223372036854775808"),
                valid.replace("\"MOVE\"", "\"move\""),
                valid.replace(request, request.toUpperCase()),
                valid.replace("\"payload\":{}", "\"payload\":{\"a\":1,\"a\":2}")}) {
            assertEquals(400, post(commandsPath(), malformed).status(), malformed);
        }
        assertEquals(400, exchange("POST", commandsPath(), new byte[] {(byte) 0xc3, 0x28}, headers()).status());
        assertEquals(400, post("/api/v1/campaigns/not-a-uuid/commands?adventureId=" + adventure, valid).status());
        assertEquals(400, post("/api/v1/campaigns/" + campaign + "/commands", valid).status());
        assertEquals(413, post(commandsPath(), " ".repeat(16_385)).status());
        // A lone surrogate escape has no UTF-8 identity; the shared parser already refuses it.
        assertEquals(400, post(commandsPath(), valid.replace("\"payload\":{}", "\"payload\":{\"s\":\"\\ud800\"}"))
                .status());
        assertEquals(0, count());
    }

    /** Foreign campaigns and operations are indistinguishable from unknown ones. */
    @Test
    void hidesForeignScopes() throws Exception {
        UUID otherOwner = new TransactionTemplate(manager).execute(status -> {
            var other = OwnerProfile.create("Other", Instant.now());
            entities.persist(other);
            return other.getId();
        });
        UUID[] foreign = campaign(otherOwner);
        String valid = envelope(UUID.randomUUID().toString(), 0, "{}");
        var denied = post("/api/v1/campaigns/" + foreign[1] + "/commands?adventureId=" + foreign[0], valid);
        assertEquals(404, denied.status());
        assertEquals("{\"code\":\"COMMAND_SCOPE_UNAVAILABLE\"}", denied.body());
        assertEquals(404, post("/api/v1/campaigns/" + foreign[1] + "/commands?adventureId=" + adventure, valid)
                .status());
        assertEquals(404, exchange("GET", "/api/v1/campaigns/" + campaign + "/operations/" + UUID.randomUUID()
                + "?adventureId=" + adventure, "", headers()).status());
        assertEquals(0, count());
    }

    /** Missing cookie/CSRF or a foreign Origin is refused by local admission before any write. */
    @Test
    void requiresBrowserProofs() throws Exception {
        String valid = envelope(UUID.randomUUID().toString(), 0, "{}");
        assertEquals(403, exchange("POST", commandsPath(), valid,
                "Origin: http://127.0.0.1:8080\r\nContent-Type: application/json\r\n").status());
        assertEquals(403, exchange("POST", commandsPath(), valid,
                headers().replace("Origin: http://127.0.0.1:8080", "Origin: https://evil.test")).status());
        assertEquals(415, exchange("POST", commandsPath(), valid,
                headers().replace("application/json", "text/plain")).status());
        assertEquals(0, count());
    }

    /** Persist one adventure, content revision and campaign for an existing owner. */
    private UUID[] campaign(UUID owner) {
        return new TransactionTemplate(manager).execute(status -> {
            Instant now = Instant.now();
            var workspace = AdventureWorkspace.create(owner, "Neutral", "neutral", SupportedLocale.EN, now);
            entities.persist(workspace);
            var content = ContentRevision.admit(workspace.getId(),
                    new ContentRevision.PackCoordinates("neutral", "1", "a".repeat(64), 1, SupportedLocale.EN),
                    new ContentRevision.RulesetCoordinates("core", "1", "b".repeat(64)), now);
            entities.persist(content);
            var started = Campaign.start(workspace.getId(), content, "Campaign", "seed", now);
            entities.persist(started);
            return new UUID[] {workspace.getId(), started.getId()};
        });
    }

    /** Build one public envelope with a raw JSON payload so tests control exact spelling. */
    private static String envelope(String request, long revision, String payload) {
        return "{\"requestId\":\"" + request + "\",\"expectedRevision\":" + revision
                + ",\"commandType\":\"MOVE\",\"payload\":" + payload + "}";
    }

    /** Command path of the primary campaign. */
    private String commandsPath() {
        return "/api/v1/campaigns/" + campaign + "/commands?adventureId=" + adventure;
    }

    /** Count committed operation rows. */
    private int count() {
        return jdbc.queryForObject("select count(*) from operation_journal", Integer.class);
    }

    /** Valid browser headers for the bootstrapped session; tests replace individual proofs. */
    private String headers() {
        return "Origin: http://127.0.0.1:8080\r\nContent-Type: application/json\r\nCookie: " + cookie
                + "\r\nX-CSRF-Token: " + csrf + "\r\n";
    }

    /** Post JSON with valid proofs. */
    private Reply post(String path, String body) throws Exception {
        return exchange("POST", path, body, headers());
    }

    /** Send UTF-8 text through the raw-socket exchange. */
    private Reply exchange(String method, String path, String body, String headers) throws Exception {
        return exchange(method, path, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    /**
     * Use a bounded loopback socket so the Host header stays the configured local authority
     * independent of the random port; responses are de-chunked as bytes before UTF-8 decoding.
     */
    private Reply exchange(String method, String path, byte[] bytes, String headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:8080\r\n"
                    + "Connection: close\r\n" + headers + "Content-Length: " + bytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes);
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            String[] parts = response.split("\r\n\r\n", 2);
            String bodyText = parts[1];
            if (parts[0].toLowerCase().contains("transfer-encoding: chunked")) {
                var decoded = new StringBuilder();
                int offset = 0;
                while (true) {
                    int end = bodyText.indexOf("\r\n", offset);
                    int length = Integer.parseInt(bodyText.substring(offset, end), 16);
                    if (length == 0) {
                        break;
                    }
                    decoded.append(bodyText, end + 2, end + 2 + length);
                    offset = end + 2 + length + 2;
                }
                bodyText = decoded.toString();
            }
            bodyText = new String(bodyText.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            return new Reply(Integer.parseInt(parts[0].split(" ", 3)[1]), parts[0], bodyText);
        }
    }

    /** Detached HTTP status, raw headers and body. */
    private record Reply(int status, String headers, String body) {
    }
}
