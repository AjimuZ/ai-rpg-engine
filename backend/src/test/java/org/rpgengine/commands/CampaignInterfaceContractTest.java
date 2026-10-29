package org.rpgengine.commands;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Producer side of the browser reload contract (P05.6, QA-35): records the exact HTTP replies a
 * real servlet stack and PostgreSQL give the bilingual interface while one playthrough is used
 * across an explicit EN→IT→EN language reload, and pins them in a checked-in transcript.
 *
 * <p>The frontend consumer spec ({@code frontend/src/app/campaign/campaign-contract.spec.ts})
 * replays the same transcript through its validators, {@code CampaignSession} and the routed
 * playthrough view, so a change of field names, enumerations, SSE framing, cursor spelling or
 * error codes on either side fails a test instead of being discovered in a browser. The
 * repository {@code contracts} check keeps the frontend copy byte-identical to the golden file in
 * {@code src/test/resources/contracts/}.
 *
 * <p>Scenario, in phases the consumer advances explicitly:
 * <ol>
 *   <li>{@code first-load}: the English page bootstraps a session, reads the adventure, its
 *       playthrough list, the state (an Italian starting situation, because the campaign's
 *       narrative language is Italian) and the resume card (P09.5), then opens the stream at its
 *       head and polls once without news.</li>
 *   <li>{@code actions}: another client (this fixture) moves the character; the background
 *       presentation worker settles narration as pending, and a second command is admitted
 *       but deliberately left unexecuted, so an operation is in flight at reload time.</li>
 *   <li>{@code before-reload}: the page receives the six stream messages, re-reads the state and
 *       the resume card (now listing the move whose narration is pending) at the new revision and
 *       polls again without news.</li>
 *   <li>{@code during-reload}: the fixture cancels the in-flight operation while the page is
 *       reloading into the other language.</li>
 *   <li>{@code after-reload}: the reloaded page re-queries both operations by identity, resumes
 *       after its saved cursor, receives only the cancellation and polls again without news.
 *       An unknown cursor and an unknown campaign are also recorded for the reset and
 *       localized-error paths.</li>
 * </ol>
 * The page never submits anything; every {@code POST} in the transcript is marked
 * {@code "client": "fixture"} and the consumer refuses to serve it to the interface.
 *
 * <p>Normalization keeps the transcript deterministic without hiding shapes: every UUID is
 * replaced, in order of first appearance, by {@code 00000000-0000-4000-8000-<ordinal>}; every
 * ISO instant becomes {@code 2026-01-01T00:00:00Z}; the CSRF token becomes a fixed placeholder.
 * Everything else (key order, numbers, statuses, SSE bytes, Italian text) is the server's own.
 *
 * <p>To refresh after an intended protocol change, run the backend tests with
 * {@code -Dengine.contract.update=true}, review the diff of both copies and run the frontend
 * tests. The actual transcript of every run is also written to {@code target/contracts/}.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "engine.http.owner-id=00000000-0000-0000-0000-000000000056",
        "engine.presentation.worker.enabled=true", "engine.presentation.worker.poll-ms=100",
        "engine.stream.hold-ms=200", "engine.stream.poll-ms=50"})
class CampaignInterfaceContractTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000056");
    private static final String GOLDEN = "contracts/campaign-reload-transcript.json";
    private static final String FRONTEND_COPY = "../frontend/src/app/campaign/contract/campaign-reload-transcript.json";
    private static final List<String> PHASES = List.of("first-load", "actions", "before-reload", "during-reload",
            "after-reload");
    private static final Pattern UUIDS = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern INSTANTS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z");
    private static final Pattern CSRF = Pattern.compile("\"csrfToken\":\"[^\"]*\"");
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private PackRegistry registry;
    @Autowired private OperationAdmissionService admissions;
    private final JsonMapper json = JsonMapper.builder().build();
    /** Raw UUID to normalized placeholder, in order of first appearance across the transcript. */
    private final Map<String, String> identities = new LinkedHashMap<>();
    private final List<Map<String, Object>> exchanges = new ArrayList<>();
    private UUID adventure;
    private UUID revision;
    private String cookie;
    private String csrf;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    CampaignInterfaceContractTest() {
    }

    /** Commit the configured owner, one adventure and the sealed fantasy fixture pack. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        adventure = new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(OWNER, "Mosslight", "fantasy", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        revision = registry.register(adventure, new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(getClass().getResource("/packs/fantasy-exploration").toURI()))).revisionId();
    }

    /** Delete disposable rows after each test; constraints remain installed. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, "
                + "campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, "
                + "plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, ai_job, "
                + "planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, campaign_random_seed, "
                + "canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, operation_journal, "
                + "actor_resource, actor, presentation_campaign_settings, campaign, content_revision_report, "
                + "content_revision_file, content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * Record the scenario described in the class comment, assert its essential facts directly
     * (so a failure names the broken step) and compare the normalized transcript with the golden file.
     */
    @Test
    void recordsTheReloadTranscriptTheInterfaceReplays() throws Exception {
        // Setup traffic is not part of the transcript: the interface has no creation screen yet.
        var session = exchange("GET", "/api/v1/session", "", browser("application/json"));
        assertEquals(200, session.status(), session.body());
        csrf = json.readTree(session.body()).get("csrfToken").asText();
        cookie = session.headers().lines().filter(line -> line.toLowerCase().startsWith("set-cookie:"))
                .findFirst().orElseThrow().substring("set-cookie:".length()).strip().split(";", 2)[0];
        var created = exchange("POST", "/api/v1/campaigns", json.writeValueAsString(Map.of("requestId", UUID.randomUUID(),
                "adventureId", adventure, "contentRevisionId", revision, "title", "Lantern walk", "narrativeLocale", "it",
                "choices", Map.of("name", "Mara", "archetypeId", "surveyor", "attributes", Map.of(), "skills", Map.of()))),
                fixture());
        assertEquals(201, created.status(), created.body());
        UUID campaign = UUID.fromString(json.readTree(created.body()).get("campaignId").asText());
        String scope = "?adventureId=" + adventure;
        String base = "/api/v1/campaigns/" + campaign;
        String stream = base + "/stream" + scope;

        page("first-load", "/api/v1/session");
        page("first-load", "/api/v1/adventures/" + adventure);
        page("first-load", "/api/v1/campaigns?adventureId=" + adventure + "&limit=50");
        JsonNode initial = json.readTree(page("first-load", base + "/state" + scope).body());
        assertEquals(0, initial.get("scope").get("stateRevision").asLong());
        assertEquals("it", initial.get("startingSituation").get("locale").asText());
        assertFalse(initial.get("startingSituation").get("fallback").asBoolean());
        JsonNode card = json.readTree(page("first-load", base + "/resume" + scope).body());
        assertEquals("STARTING_SITUATION", card.get("pendingDecision").asText());
        assertEquals("it", card.get("location").get("nameLocale").asText());
        // P10.5: the panels report the same revision and clock, worded in the campaign's Italian.
        JsonNode panels = json.readTree(page("first-load", base + "/panels" + scope).body());
        assertEquals(0, panels.at("/scope/stateRevision").asLong());
        assertEquals("it", panels.get("locale").asText());
        assertEquals(1, panels.at("/clock/day").asInt());
        assertEquals(List.of("stream.ready|0:0"), frames(page("first-load", stream).body()));
        assertEquals(List.of(), frames(page("first-load", stream + "&after=0%3A0").body()));

        var moved = record("actions", "fixture", "POST", base + "/commands" + scope, "{\"requestId\":\""
                + UUID.randomUUID() + "\",\"expectedRevision\":0,\"commandType\":\"MOVE\",\"payload\":"
                + "{\"destinationId\":\"grove\"}}");
        assertEquals(202, moved.status(), moved.body());
        String move = json.readTree(moved.body()).get("operationId").asText();
        assertEquals("grove", json.readTree(moved.body()).get("receipt").get("destinationId").asText());
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!"COMMITTED_NARRATION_PENDING".equals(jdbc.queryForObject("select status from operation_journal "
                + "where id = ?::uuid", String.class, move))) {
            assertTrue(System.nanoTime() < deadline, "presentation worker did not settle the move");
            Thread.sleep(50);
        }
        // Admitted but never executed: the operation is still in flight when the page reloads.
        String wait = admissions.submit(OWNER, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), 1, "WAIT", Map.of("seconds", 30))).operation().operationId().toString();

        assertEquals(List.of("operation.accepted|0:1", "operation.progress|0:2", "state.committed|0:3",
                "operation.progress|0:4", "narrative.pending|0:5", "operation.accepted|0:6"),
                frames(page("before-reload", stream + "&after=0%3A0").body()));
        JsonNode advanced = json.readTree(page("before-reload", base + "/state" + scope).body());
        assertEquals(1, advanced.get("scope").get("stateRevision").asLong());
        assertTrue(advanced.get("startingSituation").isNull());
        JsonNode owed = json.readTree(page("before-reload", base + "/resume" + scope).body());
        assertEquals(1, owed.get("awaitingNarrationTotal").asInt());
        assertEquals("RECEIPT", owed.get("awaitingNarration").get(0).get("narration").get("mode").asText());
        JsonNode movedPanels = json.readTree(page("before-reload", base + "/panels" + scope).body());
        assertEquals(1, movedPanels.at("/scope/stateRevision").asLong());
        assertEquals("grove", movedPanels.at("/character/location/id").asText());
        assertEquals("ACTOR_MOVED", movedPanels.at("/journal/entries/0/type").asText());
        assertEquals(List.of(), frames(page("before-reload", stream + "&after=0%3A6").body()));

        var cancelled = record("during-reload", "fixture", "POST", base + "/operations/" + wait + "/cancel" + scope, "{}");
        assertEquals("CANCELLED", json.readTree(cancelled.body()).get("cancellation").asText());

        page("after-reload", "/api/v1/campaigns?adventureId=" + adventure + "&limit=50");
        page("after-reload", base + "/state" + scope);
        page("after-reload", base + "/resume" + scope);
        page("after-reload", base + "/panels" + scope);
        assertEquals("CANCELLED", json.readTree(page("after-reload", base + "/operations/" + wait + scope).body())
                .get("status").asText());
        JsonNode settled = json.readTree(page("after-reload", base + "/operations/" + move + scope).body());
        assertEquals("COMMITTED_NARRATION_PENDING", settled.get("status").asText());
        assertEquals(json.readTree(moved.body()).get("receipt"), settled.get("receipt"));
        assertEquals(List.of("operation.cancelled|0:7"), frames(page("after-reload", stream + "&after=0%3A6").body()));
        assertEquals(List.of(), frames(page("after-reload", stream + "&after=0%3A7").body()));
        assertEquals(List.of("stream.reset|0:7"), frames(page("after-reload", stream + "&after=0%3A99").body()));
        UUID foreign = UUID.randomUUID();
        var missing = page("after-reload", "/api/v1/campaigns/" + foreign + "/state" + scope);
        assertEquals(404, missing.status());
        assertEquals("CAMPAIGN_SCOPE_UNAVAILABLE", json.readTree(missing.body()).get("code").asText());

        String actual = transcript(Map.of("adventureId", adventure.toString(), "campaignId", campaign.toString(),
                "moveOperationId", move, "waitOperationId", wait, "foreignCampaignId", foreign.toString()));
        Path output = Path.of("target", "contracts", "campaign-reload-transcript.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, actual, StandardCharsets.UTF_8);
        if (Boolean.getBoolean("engine.contract.update")) {
            Files.writeString(Path.of("src", "test", "resources", GOLDEN), actual, StandardCharsets.UTF_8);
            // The verification image carries only the backend sources, so the copy is refreshed only in a checkout.
            if (Files.isDirectory(Path.of(FRONTEND_COPY).getParent().getParent())) {
                Files.createDirectories(Path.of(FRONTEND_COPY).getParent());
                Files.writeString(Path.of(FRONTEND_COPY), actual, StandardCharsets.UTF_8);
            }
        }
        // The classpath copy was made before an update run wrote the source file, so read the file that applies.
        String golden;
        try (var source = Boolean.getBoolean("engine.contract.update")
                ? Files.newInputStream(Path.of("src", "test", "resources", GOLDEN))
                : getClass().getResourceAsStream("/" + GOLDEN)) {
            assertNotNull(source, "missing golden transcript; run once with -Dengine.contract.update=true");
            golden = new String(source.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        assertEquals(golden, actual, "The interface reload contract changed. Compare target/contracts/"
                + "campaign-reload-transcript.json with src/test/resources/" + GOLDEN + "; if the change is intended, "
                + "rerun with -Dengine.contract.update=true and run the frontend contract spec.");
    }

    /** Record one GET the interface itself issues in this phase, with browser-equivalent headers. */
    private Reply page(String phase, String path) throws Exception {
        return record(phase, "page", "GET", path, null);
    }

    /**
     * Perform and record one exchange. Page reads send what a same-origin {@code fetch} sends; fixture
     * writes carry the session's Origin and CSRF proofs, as the future composer will.
     */
    private Reply record(String phase, String client, String method, String path, String body) throws Exception {
        String accept = path.contains("/stream?") ? "text/event-stream" : "application/json";
        var reply = exchange(method, path, body == null ? "" : body, client.equals("page") ? browser(accept) + cookieHeader()
                : fixture());
        var entry = new LinkedHashMap<String, Object>();
        entry.put("phase", phase);
        entry.put("client", client);
        entry.put("method", method);
        entry.put("path", normalize(path));
        entry.put("requestBody", body == null ? null : normalize(body));
        entry.put("status", reply.status());
        entry.put("contentType", reply.headers().lines().filter(line -> line.toLowerCase().startsWith("content-type:"))
                .findFirst().map(line -> line.substring("content-type:".length()).strip()).orElse(null));
        entry.put("body", normalize(reply.body()));
        exchanges.add(entry);
        return reply;
    }

    /** Headers of a same-origin browser GET: no Origin, fetch metadata and the requested media type. */
    private static String browser(String accept) {
        return "Accept: " + accept + "\r\nSec-Fetch-Site: same-origin\r\n";
    }

    /** The bootstrapped session cookie as the browser would send it. */
    private String cookieHeader() {
        return "Cookie: " + cookie + "\r\n";
    }

    /** Headers of an unsafe same-origin request with both browser proofs. */
    private String fixture() {
        return "Origin: http://127.0.0.1:8080\r\nContent-Type: application/json\r\nSec-Fetch-Site: same-origin\r\n"
                + (cookie == null ? "" : cookieHeader() + "X-CSRF-Token: " + csrf + "\r\n");
    }

    /** Replace volatile identities, instants and the CSRF token; see the class comment. */
    private String normalize(String text) {
        Matcher match = UUIDS.matcher(text);
        var out = new StringBuilder();
        while (match.find()) {
            String placeholder = identities.computeIfAbsent(match.group(),
                    raw -> String.format("00000000-0000-4000-8000-%012d", identities.size() + 1));
            match.appendReplacement(out, placeholder);
        }
        match.appendTail(out);
        String stable = INSTANTS.matcher(out).replaceAll("2026-01-01T00:00:00Z");
        return CSRF.matcher(stable).replaceAll("\"csrfToken\":\"contract-csrf-token\"");
    }

    /**
     * Serialize the transcript with a fixed layout (two-space indent, one field per line, LF only)
     * so the golden file and its frontend copy diff cleanly on every platform.
     */
    private String transcript(Map<String, String> named) throws Exception {
        var out = new StringBuilder("{\n  \"schemaVersion\": 1,\n");
        out.append("  \"producer\": \"backend/src/test/java/org/rpgengine/commands/CampaignInterfaceContractTest.java\",\n");
        out.append("  \"phases\": ").append(json.writeValueAsString(PHASES)).append(",\n  \"identities\": {\n");
        var names = List.of("adventureId", "campaignId", "moveOperationId", "waitOperationId", "foreignCampaignId");
        for (int index = 0; index < names.size(); index++) {
            out.append("    ").append(json.writeValueAsString(names.get(index))).append(": ")
                    .append(json.writeValueAsString(identities.get(named.get(names.get(index)))))
                    .append(index + 1 < names.size() ? ",\n" : "\n");
        }
        out.append("  },\n  \"exchanges\": [\n");
        for (int index = 0; index < exchanges.size(); index++) {
            out.append("    {\n");
            var fields = new ArrayList<>(exchanges.get(index).entrySet());
            for (int field = 0; field < fields.size(); field++) {
                out.append("      ").append(json.writeValueAsString(fields.get(field).getKey())).append(": ")
                        .append(json.writeValueAsString(fields.get(field).getValue()))
                        .append(field + 1 < fields.size() ? ",\n" : "\n");
            }
            out.append(index + 1 < exchanges.size() ? "    },\n" : "    }\n");
        }
        return out.append("  ]\n}\n").toString();
    }

    /** Summarize SSE frames as {@code event|id}, ignoring the retry preamble and comments. */
    private static List<String> frames(String body) {
        var frames = new ArrayList<String>();
        for (String block : body.split("\n\n")) {
            String id = null;
            String event = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("id: ")) {
                    id = line.substring(4);
                } else if (line.startsWith("event: ")) {
                    event = line.substring(7);
                }
            }
            if (id != null) {
                frames.add(event + "|" + id);
            }
        }
        return frames;
    }

    /**
     * Use a bounded loopback socket so the Host header stays the configured local authority
     * independent of the random port; responses are de-chunked as bytes before UTF-8 decoding,
     * because chunk sizes count octets and Italian text contains multibyte characters.
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

    /** Detached HTTP status, raw headers and body. */
    private record Reply(int status, String headers, String body) {
    }
}
