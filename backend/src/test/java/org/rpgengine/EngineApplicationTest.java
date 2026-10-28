package org.rpgengine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercise the real HTTP server and configured PostgreSQL, without account access.
 * Requires a disposable PostgreSQL 18 database through Spring datasource variables;
 * unavailable or incompatible infrastructure fails the suite instead of skipping it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EngineApplicationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    /** Tables created by V1–V15, sorted by name; identifiers are constants, never user input. */
    private static final List<String> CANONICAL_TABLES = List.of(
            "actor", "actor_commitment", "actor_resource", "actor_sighting", "adventure_workspace", "ai_job", "ai_job_attempt", "campaign", "campaign_checkpoint", "campaign_creation", "campaign_event",
            "campaign_known_fact", "campaign_known_quest", "campaign_mutation_lease", "campaign_random_seed",
            "campaign_stream_head", "campaign_stream_message", "canonical_outbox", "character_state", "content_revision", "content_revision_file", "content_revision_report", "identity_owner_profile", "inference_scheduler_state", "inference_task", "item_instance", "knowledge_belief", "knowledge_claim", "learned_ability", "narration_publication", "operation_journal",
            "operation_random_draw",
            "plan_activation", "plan_admission", "plan_dependency", "plan_disposition", "planning_snapshot",
            "presentation_campaign_settings", "presentation_job", "query_epoch", "quest_plan_artifact",
            "reward_settlement");

    /** Construct the framework-managed test fixture; injection failures fail setup. */
    EngineApplicationTest() {
    }

    /** Verify the status-only HTTP contract; connection and assertion failures fail the test. */
    @Test
    void exposesOnlyMinimalHealth() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            // Boot lists the group names on the root; no component or detail is exposed.
            assertEquals("{\"groups\":[\"gameplay\",\"liveness\",\"readiness\"],\"status\":\"UP\"}",
                    response.body());
            var hidden = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/actuator/env")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(404, hidden.statusCode());
        }
    }

    /** Verify the database baseline and migration wiring; SQL or validation errors fail the test. */
    @Test
    void connectsToPostgresqlWithFlyway() {
        int version = Integer.parseInt(jdbc.queryForObject("show server_version_num", String.class));
        assertEquals(18, version / 10000);
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(0, flyway.info().pending().length);
        // Exactly the admitted canonical tables exist; nothing else sneaks in.
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' "
                        + "and table_name <> 'flyway_schema_history' order by table_name", String.class);
        assertEquals(CANONICAL_TABLES, tables);
        // Migrations create structure only: no gameplay, identity or content rows are seeded.
        for (String table : tables) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class), table);
        }
    }

    /** GET a path on the running server; returns {status, body}. Transport errors fail the test. */
    private String[] get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var r = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return new String[] {String.valueOf(r.statusCode()), r.body()};
        }
    }

    /** Liveness and readiness are UP with a ready database; gameplay is never claimed ready. */
    @Test
    void separatesLivenessStorageReadinessAndGameplay() throws Exception {
        assertEquals("200", get("/actuator/health/liveness")[0]);
        assertEquals("{\"status\":\"UP\"}", get("/actuator/health/liveness")[1]);
        assertEquals("200", get("/actuator/health/readiness")[0]);
        assertEquals("{\"status\":\"UP\"}", get("/actuator/health/readiness")[1]);
        // The shell has no narration runtime: gameplay must be 503 while the root stays UP.
        var gameplay = get("/actuator/health/gameplay");
        assertEquals("503", gameplay[0]);
        assertEquals("{\"status\":\"UNKNOWN\"}", gameplay[1]);
        assertEquals("200", get("/actuator/health")[0]);
    }
}
