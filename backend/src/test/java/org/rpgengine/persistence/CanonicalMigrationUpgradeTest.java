package org.rpgengine.persistence;

import java.sql.Connection;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Upgrade populated V1 through V2/V3 on real PostgreSQL without touching the shared public schema. */
@SpringBootTest
class CanonicalMigrationUpgradeTest {
    @Autowired
    private DataSource dataSource;

    /** Construct a framework-injected fixture; a missing database fails the test context. */
    CanonicalMigrationUpgradeTest() {
    }

    /** Preserve identities/outcomes, initialize versions and add an empty outbox; migration errors fail the test. */
    @Test
    void upgradesPopulatedFoundationsWithoutChangingCanonicalCounters() throws Exception {
        String schema = "p032_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway v1 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("1").load();
        try {
            assertEquals(1, v1.migrate().migrationsExecuted);
            try (Connection connection = dataSource.getConnection()) {
                String originalSchema = connection.getSchema();
                try {
                    connection.setSchema(schema);
                    var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                    UUID owner = UUID.randomUUID();
                    UUID adventure = UUID.randomUUID();
                    UUID revision = UUID.randomUUID();
                    UUID campaign = UUID.randomUUID();
                    UUID operation = UUID.randomUUID();
                    jdbc.update("insert into identity_owner_profile (id, display_name, created_at, updated_at) "
                            + "values (?, 'Owner', now(), now())", owner);
                    jdbc.update("insert into adventure_workspace (id, owner_id, title, genre, source_locale, "
                            + "created_at, updated_at) values (?, ?, 'Adventure', 'neutral', 'en', now(), now())",
                            adventure, owner);
                    jdbc.update("insert into content_revision (id, adventure_id, pack_id, pack_version, content_hash, "
                            + "schema_version, ruleset_id, ruleset_version, ruleset_hash, source_locale, admitted_at) "
                            + "values (?, ?, 'pack', '1', ?, 1, 'core', '1', ?, 'en', now())",
                            revision, adventure, "a".repeat(64), "b".repeat(64));
                    jdbc.update("insert into campaign (id, adventure_id, content_revision_id, title, seed_identity, "
                            + "mode, state_revision, game_turn, world_time_seconds, event_sequence, created_at, updated_at) "
                            + "values (?, ?, ?, 'Campaign', 'seed', 'EXPLORATION', 7, 3, 60, 12, now(), now())",
                            campaign, adventure, revision);
                    jdbc.update("insert into operation_journal (id, campaign_id, idempotency_key, payload_hash, "
                            + "command_type, base_revision, status, operation_version, committed_revision, receipt, "
                            + "created_at, updated_at) values (?, ?, 'done', ?, 'move', 6, 'DELIVERED', 6, 7, '{}', "
                            + "now(), now())", operation, campaign, "c".repeat(64));
                    String before = jdbc.queryForObject("select to_jsonb(c)::text from campaign c where id = ?",
                            String.class, campaign);
                    Flyway v2 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("2").load();
                    assertEquals(1, v2.migrate().migrationsExecuted);
                    assertTrue(v2.validateWithResult().validationSuccessful);
                    assertEquals(before, jdbc.queryForObject(
                            "select (to_jsonb(c) - 'row_version')::text from campaign c where id = ?", String.class, campaign));
                    for (String table : new String[] {"identity_owner_profile", "adventure_workspace", "campaign",
                            "operation_journal"}) {
                        assertEquals(0L, jdbc.queryForObject("select row_version from " + table, Long.class));
                    }
                    assertEquals(1, jdbc.update("update campaign set title = 'Renamed', row_version = 1 "
                            + "where id = ? and row_version = 0", campaign));
                    assertEquals(6L, jdbc.queryForObject("select operation_version from operation_journal", Long.class));
                    assertEquals(0, v2.migrate().migrationsExecuted);
                    // A populated committed journal also survives V3 unchanged; the
                    // new outbox starts empty rather than fabricating historic delivery.
                    String journal = jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o",
                            String.class);
                    Flyway v3 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("3").load();
                    assertEquals(1, v3.migrate().migrationsExecuted);
                    assertTrue(v3.validateWithResult().validationSuccessful);
                    assertEquals(journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o",
                            String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from canonical_outbox", Integer.class));
                    assertEquals(0, v3.migrate().migrationsExecuted);
                    // V4 must retain existing outcomes and counters, never invent old randomness.
                    Flyway v4 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("4").load();
                    assertEquals(1, v4.migrate().migrationsExecuted);
                    assertTrue(v4.validateWithResult().validationSuccessful);
                    assertEquals(journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o",
                            String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from operation_random_draw", Integer.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from campaign_random_seed", Integer.class));
                    assertEquals(7L, jdbc.queryForObject("select state_revision from campaign", Long.class));
                    assertEquals(0, v4.migrate().migrationsExecuted);
                    // V5 keeps historic outcomes intact and creates no fictitious lease owner.
                    Flyway v5 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").load();
                    assertEquals(1, v5.migrate().migrationsExecuted);
                    assertTrue(v5.validateWithResult().validationSuccessful);
                    assertEquals(journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o",
                            String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from campaign_mutation_lease", Integer.class));
                    assertEquals(7L, jdbc.queryForObject("select state_revision from campaign", Long.class));
                    assertEquals(0, v5.migrate().migrationsExecuted);
                    // Restore the pooled connection's scope before it is returned to other tests.
                } finally {
                    connection.setSchema(originalSchema);
                }
            }
        } finally {
            // This random schema and every row in it were created solely by this test.
            new JdbcTemplate(dataSource).execute("drop schema if exists " + schema + " cascade");
        }
    }
}
