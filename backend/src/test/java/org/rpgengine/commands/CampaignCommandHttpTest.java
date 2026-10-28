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
 * Real servlet and PostgreSQL acceptance of the command adapter: local execution with a
 * player-safe receipt, 202/200 replay semantics, the status query, strict envelope parsing,
 * stable error codes, foreign scope hiding and the browser-proof requirement inherited from
 * local admission.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.http.owner-id=00000000-0000-0000-0000-000000000071")
class CampaignCommandHttpTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000071");
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private OperationAdmissionService admissions;
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

    /**
     * A new command executes and answers 202 with its committed receipt; the same request replays
     * 200 with the identical receipt, and the query reads the same operation and receipt.
     */
    @Test
    void admitsReplaysAndReportsStatus() throws Exception {
        String request = UUID.randomUUID().toString();
        var accepted = post(commandsPath(), envelope(request, 0, "{\"recipientActorId\":\"" + guide(campaign) + "\",\"text\":\"Hello\"}").replace("\"MOVE\"", "\"SPEAK\""));
        assertEquals(202, accepted.status(), accepted.body());
        JsonNode view = json.readTree(accepted.body());
        assertEquals("COMMITTED", view.get("status").asText());
        assertEquals("SPEAK", view.get("commandType").asText());
        assertEquals(1, view.get("committedRevision").asLong());
        assertFalse(view.get("replayed").asBoolean());
        assertFalse(accepted.body().contains("fence") || accepted.body().contains("holder"));
        // Allowlisted body and receipt projection: no stored details, actors, words or draw identities.
        assertEquals(java.util.List.of("operationId", "campaignId", "commandType", "status", "baseRevision",
                "committedRevision", "operationVersion", "replayed", "createdAt", "updatedAt", "receipt", "query"),
                java.util.List.copyOf(view.propertyNames()));
        JsonNode receipt = view.get("receipt");
        assertEquals(java.util.List.of("stateRevision", "firstEventSequence", "lastEventSequence", "commandType",
                "elapsedSeconds", "turnAdvanced", "resources", "calculations", "randomDraws"),
                java.util.List.copyOf(receipt.propertyNames()));
        assertFalse(accepted.body().contains(guide(campaign).toString()) || accepted.body().contains("Hello")
                || accepted.body().contains("details"));
        String operation = view.get("operationId").asText();
        assertEquals("/api/v1/campaigns/" + campaign + "/operations/" + operation + "?adventureId=" + adventure,
                view.get("query").asText());
        // Key order does not change the canonical identity of a closed speech payload.
        var replay = post(commandsPath(), envelope(request, 0, "{\"text\":\"Hello\",\"recipientActorId\":\"" + guide(campaign) + "\"}").replace("\"MOVE\"", "\"SPEAK\""));
        assertEquals(200, replay.status(), replay.body());
        assertEquals(operation, json.readTree(replay.body()).get("operationId").asText());
        assertTrue(json.readTree(replay.body()).get("replayed").asBoolean());
        assertEquals(receipt, json.readTree(replay.body()).get("receipt"));
        var status = exchange("GET", view.get("query").asText(), "", headers());
        assertEquals(200, status.status(), status.body());
        assertEquals(operation, json.readTree(status.body()).get("operationId").asText());
        assertEquals(receipt, json.readTree(status.body()).get("receipt"));
        assertEquals(1, jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign));
        assertEquals(1, count());
    }

    /**
     * Changed payloads, stale revisions and a busy campaign return fixed 409 codes. The first
     * command commits and frees the campaign, so a second operation admitted internally holds
     * the permit that makes the HTTP request busy.
     */
    @Test
    void reportsConflictsWithStableCodes() throws Exception {
        String request = UUID.randomUUID().toString();
        assertEquals(202, post(commandsPath(), envelope(request, 0, "{\"seconds\":10}").replace("\"MOVE\"", "\"WAIT\"")).status());
        var conflict = post(commandsPath(), envelope(request, 0, "{\"seconds\":10.0}").replace("\"MOVE\"", "\"WAIT\""));
        assertEquals(409, conflict.status());
        assertEquals("{\"code\":\"COMMAND_REQUEST_CONFLICT\"}", conflict.body());
        assertEquals("{\"code\":\"COMMAND_STALE_REVISION\"}",
                post(commandsPath(), envelope(UUID.randomUUID().toString(), 3, "{\"destinationId\":\"door\"}")).body());
        var holder = admissions.submit(OWNER, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), 1, "WAIT", java.util.Map.of("seconds", 5)));
        assertNotNull(holder.lease());
        var busy = post(commandsPath(), envelope(UUID.randomUUID().toString(), 1, "{\"seconds\":20}")
                .replace("\"MOVE\"", "\"WAIT\""));
        assertEquals(409, busy.status());
        assertEquals("{\"code\":\"COMMAND_CAMPAIGN_BUSY\"}", busy.body());
        assertEquals(2, count());
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

    /** Typed shape, common-semantic and rule failures return stable 422 categories without retaining an operation. */
    @Test
    void rejectsGenericPatchesAndIllegalTypedActions() throws Exception {
        for (String payload : new String[] {"{}", "{\"destinationId\":\"door\",\"patch\":{\"hp\":99}}",
                "{\"destinationId\":{\"set\":\"door\"}}", "{\"destinationId\":\"door\",\"cost\":-1}"}) {
            var rejected = post(commandsPath(), envelope(UUID.randomUUID().toString(), 0, payload));
            assertEquals(422, rejected.status(), rejected.body());
            assertEquals("{\"code\":\"COMMAND_INVALID\"}", rejected.body());
        }
        var unknown = post(commandsPath(), envelope(UUID.randomUUID().toString(), 0, "{}")
                .replace("\"MOVE\"", "\"PATCH\""));
        assertEquals(422, unknown.status());
        assertEquals("{\"code\":\"COMMAND_INVALID\"}", unknown.body());
        String valid = envelope(UUID.randomUUID().toString(), 0, "{\"seconds\":20}").replace("\"MOVE\"", "\"WAIT\"");
        jdbc.update("update campaign set mode = 'ENCOUNTER', row_version = row_version + 1 where id = ?", campaign);
        var mode = post(commandsPath(), valid);
        assertEquals(422, mode.status());
        assertEquals("{\"code\":\"COMMAND_MODE_ILLEGAL\"}", mode.body());
        jdbc.update("update campaign set mode = 'EXPLORATION', pending_decision = '{}'::jsonb, "
                + "row_version = row_version + 1 where id = ?", campaign);
        var decision = post(commandsPath(), valid);
        assertEquals(422, decision.status());
        assertEquals("{\"code\":\"COMMAND_DECISION_UNAVAILABLE\"}", decision.body());
        jdbc.update("update campaign set pending_decision = null, row_version = row_version + 1 where id = ?", campaign);
        var target = post(commandsPath(), envelope(UUID.randomUUID().toString(), 0,
                "{\"recipientActorId\":\"" + UUID.randomUUID() + "\",\"text\":\"hello\"}")
                .replace("\"MOVE\"", "\"SPEAK\""));
        assertEquals(422, target.status());
        assertEquals("{\"code\":\"COMMAND_ACTOR_UNAVAILABLE\"}", target.body());
        // P07.3: the player cannot address itself, and rules needing definitions fail closed
        // when the campaign pins no sealed pack (this fixture's revision has no stored bytes).
        var self = post(commandsPath(), envelope(UUID.randomUUID().toString(), 0,
                "{\"recipientActorId\":\"" + player(campaign) + "\",\"text\":\"hello\"}")
                .replace("\"MOVE\"", "\"SPEAK\""));
        assertEquals("{\"code\":\"COMMAND_ACTOR_UNAVAILABLE\"}", self.body());
        for (String content : new String[] {envelope(UUID.randomUUID().toString(), 0, "{\"destinationId\":\"door\"}"),
                envelope(UUID.randomUUID().toString(), 0, "{\"abilityId\":\"steady\",\"targetActorIds\":[]}")
                        .replace("\"MOVE\"", "\"USE_ABILITY\"")}) {
            var unavailable = post(commandsPath(), content);
            assertEquals(422, unavailable.status());
            assertEquals("{\"code\":\"COMMAND_CONTENT_UNAVAILABLE\"}", unavailable.body());
        }
        assertEquals("{\"code\":\"COMMAND_INVALID\"}", post(commandsPath(), envelope(UUID.randomUUID().toString(), 0,
                "{\"abilityId\":\"steady\",\"targetActorIds\":[],\"cost\":0}").replace("\"MOVE\"", "\"USE_ABILITY\"")).body());
        assertEquals(0, count());
        assertEquals(0, jdbc.queryForObject("select count(*) from campaign_mutation_lease", Integer.class));
        // Rejection consumes no request identity: the same valid envelope can be reviewed and submitted.
        assertEquals(202, post(commandsPath(), valid).status());
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
        String valid = envelope(UUID.randomUUID().toString(), 0, "{\"destinationId\":\"door\"}");
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
        String valid = envelope(UUID.randomUUID().toString(), 0, "{\"destinationId\":\"door\"}");
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
            var player = org.rpgengine.characters.Actor.create(started.getId(),
                    org.rpgengine.characters.ActorKind.CHARACTER, null, "Player", "camp", 0, now);
            entities.persist(player);
            // A co-present listener without an authored origin is perceivable without pinned content.
            entities.persist(org.rpgengine.characters.Actor.create(started.getId(),
                    org.rpgengine.characters.ActorKind.NPC, null, "Guide", "camp", 0, now));
            entities.flush();
            jdbc.update("insert into campaign_creation (adventure_id, request_id, payload_hash, campaign_id, actor_id, "
                            + "profile_id, choices, created_at) values (?, ?, ?, ?, ?, 'neutral', '{}'::jsonb, now())",
                    workspace.getId(), UUID.randomUUID(), "a".repeat(64), started.getId(), player.getId());
            return new UUID[] {workspace.getId(), started.getId()};
        });
    }

    /** Resolve the fixture's immutable creation player, which can never address itself. */
    private UUID player(UUID id) {
        return jdbc.queryForObject("select actor_id from campaign_creation where campaign_id = ?", UUID.class, id);
    }

    /** Resolve the fixture's co-present listener for an addressed speech intention. */
    private UUID guide(UUID id) {
        return jdbc.queryForObject("select id from actor where campaign_id = ? and kind = 'NPC'", UUID.class, id);
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
