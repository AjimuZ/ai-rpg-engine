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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Upgrade populated V1 through V15 on real PostgreSQL; migration errors fail without changing the public schema. */
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
                            .locations("classpath:db/migration").target("5").load();
                    assertEquals(1, v5.migrate().migrationsExecuted);
                    assertTrue(v5.validateWithResult().validationSuccessful);
                    assertEquals(journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o",
                            String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from campaign_mutation_lease", Integer.class));
                    assertEquals(7L, jdbc.queryForObject("select state_revision from campaign", Long.class));
                    assertEquals(0, v5.migrate().migrationsExecuted);
                    // V6 preserves populated canonical state and initializes only the recovery token.
                    String v5Campaign = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
                    Flyway v6 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("6").load();
                    assertEquals(1, v6.migrate().migrationsExecuted);
                    assertTrue(v6.validateWithResult().validationSuccessful);
                    assertEquals(v5Campaign, jdbc.queryForObject("select (to_jsonb(c) - 'recovery_generation')::text "
                            + "from campaign c", String.class));
                    assertEquals(journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o", String.class));
                    assertEquals(0L, jdbc.queryForObject("select recovery_generation from campaign", Long.class));
                    for (String table : new String[] {"ai_job", "ai_job_attempt", "planning_snapshot", "query_epoch",
                            "quest_plan_artifact", "plan_dependency", "plan_admission", "plan_disposition"}) {
                        assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
                    }
                    assertEquals(0, v6.migrate().migrationsExecuted);
                    // V7 preserves metadata-only revisions and pinned campaigns; it must not invent
                    // file bytes or a report that would falsely claim a legacy pack passed admission.
                    String legacyRevision = jdbc.queryForObject("select to_jsonb(c)::text from content_revision c",
                            String.class);
                    String v6Campaign = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
                    Flyway v7 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("7").load();
                    assertEquals(1, v7.migrate().migrationsExecuted);
                    assertTrue(v7.validateWithResult().validationSuccessful);
                    assertEquals(legacyRevision, jdbc.queryForObject("select to_jsonb(c)::text from content_revision c",
                            String.class));
                    assertEquals(v6Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
                    assertEquals(journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o", String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from content_revision_file", Integer.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from content_revision_report", Integer.class));
                    assertEquals(0, v7.migrate().migrationsExecuted);
                    // Seal an explicit tiny V7 inventory, then prove V8 changes only allowed report
                    // versions. Previously sealed bytes, exports, reports and campaign pins stay exact.
                    jdbc.update("insert into content_revision_file (content_revision_id, path, category, byte_size, "
                            + "sha256, content) values (?, 'source.md', 'lore', 1, encode(sha256(?::bytea), 'hex'), ?)",
                            revision, new byte[] {65}, new byte[] {65});
                    jdbc.update("insert into content_revision_report (content_revision_id, report_version, file_count, "
                            + "total_bytes, report, exports) values (?, 1, 1, 1, '{\"reportVersion\":1}', '{}')", revision);
                    String oldReport = jdbc.queryForObject("select to_jsonb(r)::text from content_revision_report r", String.class);
                    String oldFile = jdbc.queryForObject("select to_jsonb(f)::text from content_revision_file f", String.class);
                    Flyway v8 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("8").load();
                    assertEquals(1, v8.migrate().migrationsExecuted);
                    assertTrue(v8.validateWithResult().validationSuccessful);
                    assertEquals(oldReport, jdbc.queryForObject("select to_jsonb(r)::text from content_revision_report r", String.class));
                    assertEquals(oldFile, jdbc.queryForObject("select to_jsonb(f)::text from content_revision_file f", String.class));
                    assertEquals(legacyRevision, jdbc.queryForObject("select to_jsonb(c)::text from content_revision c", String.class));
                    assertEquals(v6Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
                    assertEquals(journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o", String.class));
                    assertEquals(0, v8.migrate().migrationsExecuted);
                    // V9 adds empty creation storage without inventing character choices or changing old state.
                    Flyway v9 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("9").load();
                    assertEquals(1, v9.migrate().migrationsExecuted);
                    assertTrue(v9.validateWithResult().validationSuccessful);
                    assertEquals(v6Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
                    assertEquals(journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o", String.class));
                    assertEquals(oldFile, jdbc.queryForObject("select to_jsonb(f)::text from content_revision_file f", String.class));
                    assertEquals(oldReport, jdbc.queryForObject("select to_jsonb(r)::text from content_revision_report r", String.class));
                    for (String table : new String[] {"character_state", "item_instance", "learned_ability", "campaign_creation"}) {
                        assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
                    }
                    assertEquals(0, v9.migrate().migrationsExecuted);
                    // V10 adds audience knowledge without turning authored secrets into player knowledge.
                    Flyway v10 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("10").load();
                    assertEquals(1, v10.migrate().migrationsExecuted);
                    assertTrue(v10.validateWithResult().validationSuccessful);
                    assertEquals(v6Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
                    assertEquals(journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o", String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from campaign_known_fact", Integer.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from campaign_known_quest", Integer.class));
                    assertEquals(0, v10.migrate().migrationsExecuted);
                    // V11 adds attempt identity, the stream log and presentation jobs without backfilling
                    // history: old journal rows keep their values, gain null attempt columns and announce nothing.
                    Flyway v11 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("11").load();
                    assertEquals(1, v11.migrate().migrationsExecuted);
                    assertTrue(v11.validateWithResult().validationSuccessful);
                    assertEquals(v6Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
                    assertEquals(journal, jdbc.queryForObject("select (to_jsonb(o) - 'attempt_key' - 'draw_stream_id')::text "
                            + "from operation_journal o", String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from operation_journal where attempt_key is not null "
                            + "or draw_stream_id is not null", Integer.class));
                    for (String table : new String[] {"campaign_stream_head", "campaign_stream_message", "presentation_job"}) {
                        assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
                    }
                    assertEquals(0, v11.migrate().migrationsExecuted);
                    // V12 adds the plan-gate evaluator, receipt columns and activations without
                    // touching canonical rows; nothing is backfilled or marked as gate-checked.
                    String v11Journal = jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o",
                            String.class);
                    Flyway v12 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("12").load();
                    assertEquals(1, v12.migrate().migrationsExecuted);
                    assertTrue(v12.validateWithResult().validationSuccessful);
                    assertEquals(v6Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
                    assertEquals(v11Journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o",
                            String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from plan_activation", Integer.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from plan_admission where gate_version "
                            + "is not null or checked_references is not null", Integer.class));
                    UUID actor = UUID.randomUUID();
                    jdbc.update("insert into actor (id, campaign_id, kind, display_name, created_revision, created_at) "
                            + "values (?, ?, 'NPC', 'Witness', 7, now())", actor, campaign);
                    assertEquals(0L, jdbc.queryForObject("select plan_reference_version(?, ?)", Long.class, campaign,
                            "actor:" + actor));
                    for (String reference : new String[] {"actor:" + UUID.randomUUID(), "actor:" + actor.toString()
                            .toUpperCase(), "region:" + actor, "actor:" + actor + ":location"}) {
                        assertNull(jdbc.queryForObject("select plan_reference_version(?, ?)", Long.class, campaign,
                                reference), reference);
                    }
                    assertNull(jdbc.queryForObject("select plan_reference_version(?, ?)", Long.class,
                            UUID.randomUUID(), "actor:" + actor));
                    assertEquals(0, v12.migrate().migrationsExecuted);
                    // V13 adds narration publication and retry rounds without touching canonical rows:
                    // the journal is unchanged and no job, round or publication is invented.
                    String v12Journal = jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o",
                            String.class);
                    Flyway v13 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("13").load();
                    assertEquals(1, v13.migrate().migrationsExecuted);
                    assertTrue(v13.validateWithResult().validationSuccessful);
                    assertEquals(v12Journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o",
                            String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from narration_publication", Integer.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from presentation_job", Integer.class));
                    assertEquals(0, v13.migrate().migrationsExecuted);
                    // V14 adds the scheduler ledger and backoff state without touching canonical rows or
                    // inventing tasks; the single-turn index exists from the start.
                    String v13Campaign = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
                    Flyway v14 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("14").load();
                    assertEquals(1, v14.migrate().migrationsExecuted);
                    assertTrue(v14.validateWithResult().validationSuccessful);
                    assertEquals(v13Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c",
                            String.class));
                    assertEquals(v12Journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o",
                            String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from inference_task", Integer.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from inference_scheduler_state",
                            Integer.class));
                    assertEquals(1, jdbc.queryForObject("select count(*) from pg_indexes where schemaname = ? "
                            + "and indexname = 'inference_task_one_turn_uk'", Integer.class, schema));
                    assertEquals(0, v14.migrate().migrationsExecuted);
                    // V15 adds the typed knowledge tables empty: no claim, belief, sighting or commitment is
                    // invented for an old save, canonical rows stay exact, the P07.6 reference kinds keep
                    // their values and the new kinds evaluate to null (stale) when nothing exists.
                    String v14Campaign = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
                    Flyway v15 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("15").load();
                    assertEquals(1, v15.migrate().migrationsExecuted);
                    assertTrue(v15.validateWithResult().validationSuccessful);
                    assertEquals(v14Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c",
                            String.class));
                    assertEquals(v12Journal, jdbc.queryForObject("select to_jsonb(o)::text from operation_journal o",
                            String.class));
                    for (String table : new String[] {"knowledge_claim", "knowledge_belief", "actor_sighting",
                            "actor_commitment"}) {
                        assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class), table);
                    }
                    assertEquals(0L, jdbc.queryForObject("select plan_reference_version(?, ?)", Long.class, campaign,
                            "actor:" + actor));
                    for (String kind : new String[] {"claim", "belief", "commitment"}) {
                        assertNull(jdbc.queryForObject("select plan_reference_version(?, ?)", Long.class, campaign,
                                kind + ":" + UUID.randomUUID()), kind);
                    }
                    assertEquals(0, v15.migrate().migrationsExecuted);
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
