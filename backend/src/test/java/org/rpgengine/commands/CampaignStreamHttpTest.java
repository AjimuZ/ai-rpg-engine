package org.rpgengine.commands;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.Campaign;
import org.rpgengine.content.ContentRevision;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real servlet and PostgreSQL acceptance of the P07.5 HTTP delivery surface with the background
 * presentation worker enabled: a submitted command is presented automatically, the Server-Sent
 * Events stream replays its five announcements in order and resumes after {@code Last-Event-ID},
 * the cancellation route reports the race outcome with stable codes, and malformed, foreign or
 * unauthenticated requests fail before streaming. Stream responses are bounded long polls. The
 * context is discarded afterwards so its background worker cannot touch other test classes' jobs.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "engine.http.owner-id=00000000-0000-0000-0000-000000000075",
        "engine.presentation.worker.enabled=true", "engine.presentation.worker.poll-ms=100",
        "engine.stream.hold-ms=300", "engine.stream.poll-ms=50"})
class CampaignStreamHttpTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000075");
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private OperationAdmissionService admissions;
    private final JsonMapper json = JsonMapper.builder().build();
    private UUID adventure;
    private UUID campaign;
    private UUID guide;
    private String cookie;
    private String csrf;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    CampaignStreamHttpTest() {
    }

    /** Commit the configured owner with one content-free campaign, then obtain browser proofs. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Instant now = Instant.now();
            var workspace = AdventureWorkspace.create(OWNER, "Stream", "stream", SupportedLocale.EN, now);
            entities.persist(workspace);
            adventure = workspace.getId();
            var content = ContentRevision.admit(adventure, new ContentRevision.PackCoordinates("neutral", "1",
                    "a".repeat(64), 1, SupportedLocale.EN), new ContentRevision.RulesetCoordinates("core", "1",
                    "b".repeat(64)), now);
            entities.persist(content);
            var started = Campaign.start(adventure, content, "Campaign", "seed", now);
            entities.persist(started);
            campaign = started.getId();
            var player = org.rpgengine.characters.Actor.create(campaign, org.rpgengine.characters.ActorKind.CHARACTER,
                    null, "Player", "camp", 0, now);
            entities.persist(player);
            var listener = org.rpgengine.characters.Actor.create(campaign, org.rpgengine.characters.ActorKind.NPC,
                    null, "Guide", "camp", 0, now);
            entities.persist(listener);
            guide = listener.getId();
            entities.flush();
            jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, "
                    + "profile_id, choices, created_at) values (?, ?, ?, ?, ?, 'neutral', '{}'::jsonb, now())",
                    adventure, UUID.randomUUID(), "a".repeat(64), campaign, player.getId());
        });
        var session = exchange("GET", "/api/v1/session", "", "");
        assertEquals(200, session.status());
        csrf = json.readTree(session.body()).get("csrfToken").asText();
        cookie = session.headers().lines().filter(line -> line.toLowerCase().startsWith("set-cookie:"))
                .findFirst().orElseThrow().substring("set-cookie:".length()).strip().split(";", 2)[0];
    }

    /** Delete disposable rows after each test; constraints remain installed. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, "
                + "campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, "
                + "plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, "
                + "planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, "
                + "canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, operation_journal, "
                + "actor_resource, actor, presentation_campaign_settings, campaign, content_revision_report, "
                + "content_revision_file, content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * The background worker presents a committed speech without any further request; the stream
     * then delivers accepted, progress, committed, narrating and pending messages with the POST
     * receipt, resumes after {@code Last-Event-ID} and starts at the head without a cursor.
     */
    @Test
    void streamsACommittedOperationThroughAutomaticPresentation() throws Exception {
        var posted = exchange("POST", "/api/v1/campaigns/" + campaign + "/commands?adventureId=" + adventure,
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedRevision\":0,\"commandType\":\"SPEAK\","
                        + "\"payload\":{\"recipientActorId\":\"" + guide + "\",\"text\":\"Quiet words\"}}", headers());
        assertEquals(202, posted.status(), posted.body());
        JsonNode view = json.readTree(posted.body());
        String operation = view.get("operationId").asText();
        String streamPath = view.get("stream").asText();
        assertEquals("/api/v1/campaigns/" + campaign + "/stream?adventureId=" + adventure, streamPath);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!"COMMITTED_NARRATION_PENDING".equals(jdbc.queryForObject("select status from operation_journal "
                + "where id = ?::uuid", String.class, operation))) {
            assertTrue(System.nanoTime() < deadline, "presentation worker did not settle the operation");
            Thread.sleep(50);
        }
        var replay = exchange("GET", streamPath + "&after=0:0", "", headers());
        assertEquals(200, replay.status(), replay.body());
        assertTrue(replay.headers().toLowerCase().contains("content-type: text/event-stream"), replay.headers());
        assertTrue(replay.headers().toLowerCase().contains("cache-control: no-store"), replay.headers());
        assertTrue(replay.body().startsWith("retry: 1000\n\n"), replay.body());
        var frames = frames(replay.body());
        assertEquals(List.of("0:1", "0:2", "0:3", "0:4", "0:5"), frames.stream().map(Frame::id).toList());
        assertEquals(List.of("operation.accepted", "operation.progress", "state.committed", "operation.progress",
                "narrative.pending"), frames.stream().map(Frame::event).toList());
        assertEquals(view.get("receipt"), json.readTree(frames.get(2).data()).get("receipt"));
        assertEquals("NARRATION_UNAVAILABLE", json.readTree(frames.get(4).data()).get("reason").asText());
        assertFalse(replay.body().contains("Quiet words") || replay.body().contains(guide.toString()), replay.body());

        var resumed = exchange("GET", streamPath, "", headers() + "Last-Event-ID: 0:3\r\n");
        assertEquals(List.of("0:4", "0:5"), frames(resumed.body()).stream().map(Frame::id).toList());
        var head = frames(exchange("GET", streamPath, "", headers()).body());
        assertEquals("stream.ready|0:5", head.getFirst().event() + "|" + head.getFirst().id());
        assertEquals(1, jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign));
    }

    /**
     * Cancelling a committed operation reports ALREADY_COMMITTED with its receipt; cancelling an
     * admitted one reports CANCELLED, retires its permit and stays idempotent. Browser proofs, the
     * empty body and scope are enforced with stable codes before any write.
     */
    @Test
    void cancellationRouteReportsTheRaceOutcome() throws Exception {
        var posted = exchange("POST", "/api/v1/campaigns/" + campaign + "/commands?adventureId=" + adventure,
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedRevision\":0,\"commandType\":\"WAIT\","
                        + "\"payload\":{\"seconds\":5}}", headers());
        String committed = json.readTree(posted.body()).get("operationId").asText();
        var late = exchange("POST", cancelPath(committed), "{}", headers());
        assertEquals(200, late.status(), late.body());
        JsonNode lateView = json.readTree(late.body());
        assertEquals("ALREADY_COMMITTED", lateView.get("cancellation").asText());
        assertEquals(1, lateView.get("committedRevision").asLong());
        assertEquals(json.readTree(posted.body()).get("receipt"), lateView.get("receipt"));

        var admitted = admissions.submit(OWNER, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), 1, "WAIT", Map.of("seconds", 5)));
        String open = admitted.operation().operationId().toString();
        assertEquals(403, exchange("POST", cancelPath(open), "{}", headers().replaceAll("X-CSRF-Token: [^\r]*\r\n", ""))
                .status());
        for (String body : List.of("", "{\"reason\":1}", "[]", "{} {}")) {
            assertEquals(400, exchange("POST", cancelPath(open), body, headers()).status(), body);
        }
        assertEquals(404, exchange("POST", cancelPath(UUID.randomUUID().toString()), "{}", headers()).status());
        assertEquals("ACCEPTED", jdbc.queryForObject("select status from operation_journal where id = ?::uuid",
                String.class, open));
        for (int repeat = 0; repeat < 2; repeat++) {
            var cancelled = exchange("POST", cancelPath(open), "{}", headers());
            assertEquals(200, cancelled.status(), cancelled.body());
            assertEquals("CANCELLED|CANCELLED", json.readTree(cancelled.body()).get("status").asText() + "|"
                    + json.readTree(cancelled.body()).get("cancellation").asText());
            assertTrue(json.readTree(cancelled.body()).get("receipt").isNull());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from campaign_mutation_lease where operation_id is not null",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign));
    }

    /** Malformed cursors, ambiguous resumption, foreign scopes and missing sessions fail before streaming. */
    @Test
    void streamRejectsInvalidRequestsBeforeStreaming() throws Exception {
        String path = "/api/v1/campaigns/" + campaign + "/stream?adventureId=" + adventure;
        assertEquals(400, exchange("GET", path + "&after=abc", "", headers()).status());
        assertEquals(400, exchange("GET", path, "", headers() + "Last-Event-ID: 0:0\r\nLast-Event-ID: 0:1\r\n").status());
        assertEquals(400, exchange("GET", "/api/v1/campaigns/" + campaign + "/stream", "", headers()).status());
        assertEquals(404, exchange("GET", "/api/v1/campaigns/" + UUID.randomUUID() + "/stream?adventureId=" + adventure,
                "", headers()).status());
        assertEquals(403, exchange("GET", path, "", "Origin: http://127.0.0.1:8080\r\n").status());
        var reset = frames(exchange("GET", path + "&after=0:9", "", headers()).body());
        assertEquals("stream.reset|0:0", reset.getFirst().event() + "|" + reset.getFirst().id());
        assertEquals("CURSOR_UNKNOWN", json.readTree(reset.getFirst().data()).get("reason").asText());
    }

    /** Cancellation path of one operation in the primary campaign. */
    private String cancelPath(String operation) {
        return "/api/v1/campaigns/" + campaign + "/operations/" + operation + "/cancel?adventureId=" + adventure;
    }

    /** Parse SSE frames (id, event, single data line), ignoring the retry preamble and comments. */
    private static List<Frame> frames(String body) {
        var frames = new ArrayList<Frame>();
        for (String block : body.split("\n\n")) {
            String id = null;
            String event = null;
            String data = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("id: ")) {
                    id = line.substring(4);
                } else if (line.startsWith("event: ")) {
                    event = line.substring(7);
                } else if (line.startsWith("data: ")) {
                    data = line.substring(6);
                }
            }
            if (id != null) {
                frames.add(new Frame(id, event, data));
            }
        }
        return frames;
    }

    /** Valid browser headers for the bootstrapped session. */
    private String headers() {
        return "Origin: http://127.0.0.1:8080\r\nContent-Type: application/json\r\nCookie: " + cookie
                + "\r\nX-CSRF-Token: " + csrf + "\r\n";
    }

    /**
     * Use a bounded loopback socket so the Host header stays the configured local authority
     * independent of the random port; responses are de-chunked as bytes before UTF-8 decoding.
     */
    private Reply exchange(String method, String path, String body, String headers) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:8080\r\n"
                    + "Connection: close\r\n" + headers + "Content-Length: " + bytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes);
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            String[] parts = response.split("\r\n\r\n", 2);
            String bodyText = parts.length > 1 ? parts[1] : "";
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

    /** One parsed SSE frame. */
    private record Frame(String id, String event, String data) {
    }

    /** Detached HTTP status, raw headers and body. */
    private record Reply(int status, String headers, String body) {
    }
}
