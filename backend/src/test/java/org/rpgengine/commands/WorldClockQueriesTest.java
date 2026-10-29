package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.rules.ClockPack;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P10.4 exit check: reading the game never advances it. After the world clock has moved through committed
 * commands, every campaign and adventure read the interface can issue (library, list, state, continue,
 * known state, inventory, quests, abilities, consultation, resume card, operation status and the event
 * stream) is repeated while real time passes; the in-world clock, the revision, the game turn and every row
 * a command could write stay exactly as the last command left them. Only a command moves the clock.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "engine.http.owner-id=00000000-0000-0000-0000-000000000104",
        "engine.stream.hold-ms=100", "engine.stream.poll-ms=50"})
class WorldClockQueriesTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final List<String> TABLES = List.of("campaign", "campaign_event", "operation_journal",
            "canonical_outbox", "actor", "actor_resource", "actor_condition", "item_instance", "item_creation",
            "quest_state", "quest_objective", "location_visit", "query_epoch", "reward_settlement",
            "campaign_checkpoint", "campaign_known_fact", "campaign_known_quest", "memory_summary_job",
            "presentation_job", "operation_random_draw");
    @LocalServerPort private int port;
    @Autowired private CommandExecutionService executions;
    @Autowired private CampaignCreationService creation;
    @Autowired private PackRegistry registry;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private final JsonMapper json = JsonMapper.builder().build();
    private UUID adventure;
    private UUID campaign;
    private long revision;
    private String cookie;

    /** Construct the injected fixture; unavailable infrastructure fails context startup. */
    WorldClockQueriesTest() { }

    /** Register the clock pack for the configured owner and create a campaign through production code. */
    @BeforeEach
    void prepare() throws Exception {
        clear();
        jdbc.update("insert into identity_owner_profile (id, display_name, ui_locale, created_at, updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", OWNER);
        adventure = new TransactionTemplate(manager).execute(status -> {
            var workspace = AdventureWorkspace.create(OWNER, "Clock", "clock", SupportedLocale.EN, Instant.now());
            entities.persist(workspace);
            return workspace.getId();
        });
        UUID content = registry.register(adventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(ClockPack.files())))).revisionId();
        campaign = creation.create(OWNER, adventure, new CampaignCreationService.Request(UUID.randomUUID(), content,
                "Campaign", SupportedLocale.EN, new CharacterChoices("Player", "surveyor", Map.of(), Map.of(), null, null)))
                .campaignId();
        var session = get("/api/v1/session");
        cookie = session.headers().lines().filter(line -> line.toLowerCase().startsWith("set-cookie:"))
                .findFirst().orElseThrow().substring("set-cookie:".length()).strip().split(";", 2)[0];
    }

    /** Remove disposable rows. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, "
                + "learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, ai_job_attempt, "
                + "ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, operation_random_draw, "
                + "campaign_random_seed, canonical_outbox, campaign_checkpoint, reward_settlement, campaign_event, "
                + "operation_journal, actor_resource, actor, presentation_campaign_settings, campaign, "
                + "content_revision_report, content_revision_file, content_revision, adventure_workspace, "
                + "identity_owner_profile");
    }

    /** Repeated reads of every endpoint, with real time passing, change no row and not the world clock. */
    @Test
    void readsNeverAdvanceTheWorldClock() throws Exception {
        execute(new TypedCommand.Move("grove"));
        var last = execute(new TypedCommand.Rest("catch-breath"));
        long clock = clock();
        assertEquals(2_700 + 600, clock);
        var before = tables();
        String scope = "?adventureId=" + adventure;
        String base = "/api/v1/campaigns/" + campaign;
        var reads = new ArrayList<String>(List.of("/api/v1/adventures", "/api/v1/adventures/" + adventure,
                "/api/v1/campaigns", "/api/v1/campaigns" + scope, base + "/state" + scope, base + "/continue" + scope,
                base + "/known-state" + scope, base + "/inventory" + scope, base + "/quests" + scope,
                base + "/abilities" + scope, base + "/consultation" + scope + "&command=status",
                base + "/resume" + scope, base + "/panels" + scope, base + "/operations/" + last.operation().operationId() + scope,
                base + "/stream" + scope));
        for (int round = 0; round < 3; round++) {
            for (String path : reads) {
                var reply = get(path);
                assertEquals(200, reply.status(), path);
                assertEquals(clock, clock(), path + " moved the clock");
            }
            // Real time passing is not in-world time either.
            Thread.sleep(300);
        }
        assertEquals(before, tables(), "a read wrote a row");
        // The state read reports the same clock the commands left, so the reader sees time without causing it.
        var state = json.readTree(get(base + "/state" + scope).body());
        assertEquals(clock, find(state, "worldTimeSeconds"));
        assertEquals(revision, state.get("scope").get("stateRevision").asLong());
        // Only a command moves the clock.
        execute(new TypedCommand.Wait(60));
        assertNotEquals(before, tables());
        assertEquals(clock + 60, clock());
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private CommandExecutionService.Execution execute(TypedCommand command) {
        var execution = executions.execute(OWNER, adventure, campaign, new OperationAdmissionService.Command(
                UUID.randomUUID().toString(), revision, command.type(), command.payload()));
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.toString());
        revision = execution.operation().committedRevision();
        return execution;
    }

    private long clock() {
        return jdbc.queryForObject("select world_time_seconds from campaign where id = ?", Long.class, campaign);
    }

    /** Depth-first search for a numeric field; the projection nests it under its own envelope. */
    private static long find(JsonNode node, String field) {
        if (node.has(field)) {
            return node.get(field).asLong();
        }
        for (JsonNode child : node) {
            if (child.isObject() || child.isArray()) {
                try {
                    return find(child, field);
                } catch (IllegalStateException absent) {
                    // keep searching the siblings
                }
            }
        }
        throw new IllegalStateException("field " + field + " is absent");
    }

    private Map<String, List<String>> tables() {
        var snapshot = new LinkedHashMap<String, List<String>>();
        for (String table : TABLES) {
            snapshot.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return snapshot;
    }

    /** Same-origin browser GET over a loopback socket, so the Host header stays the configured local authority. */
    private Reply get(String path) throws Exception {
        String accept = path.contains("/stream") ? "text/event-stream" : "application/json";
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nConnection: close\r\n"
                    + "Accept: " + accept + "\r\nSec-Fetch-Site: same-origin\r\n"
                    + (cookie == null ? "" : "Cookie: " + cookie + "\r\n") + "\r\n").getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            String[] parts = response.split("\r\n\r\n", 2);
            String body = parts.length > 1 ? parts[1] : "";
            if (parts[0].toLowerCase().contains("transfer-encoding: chunked")) {
                var decoded = new StringBuilder();
                int offset = 0;
                while (offset < body.length()) {
                    int end = body.indexOf("\r\n", offset);
                    int length = Integer.parseInt(body.substring(offset, end), 16);
                    if (length == 0) {
                        break;
                    }
                    decoded.append(body, end + 2, end + 2 + length);
                    offset = end + 2 + length + 2;
                }
                body = decoded.toString();
            }
            return new Reply(Integer.parseInt(parts[0].split(" ", 3)[1]), parts[0], body);
        }
    }

    /** Detached HTTP status, raw headers and body. */
    private record Reply(int status, String headers, String body) {
    }
}
