package org.rpgengine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
            assertEquals("{\"status\":\"UP\"}", response.body());
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
        // The scaffold must not seed gameplay data or pre-empt P03's canonical migrations.
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' "
                        + "and table_name <> 'flyway_schema_history'", Integer.class));
    }
}
