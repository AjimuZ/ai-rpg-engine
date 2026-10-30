package org.rpgengine.persistence;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Upgrade populated V1 through V24 on real PostgreSQL; migration errors fail without changing the public schema. */
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
                    // V16 adds the memory tables empty and the snapshot function: an old save gets no invented
                    // summary, job, invalidation or checkpoint, canonical rows stay exact, and the operational
                    // snapshot of the populated campaign is deterministic and reports its real counters.
                    String v15Campaign = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
                    Flyway v16 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("16").load();
                    assertEquals(1, v16.migrate().migrationsExecuted);
                    assertTrue(v16.validateWithResult().validationSuccessful);
                    assertEquals(v15Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c",
                            String.class));
                    for (String table : new String[] {"memory_summary_job", "memory_summary", "memory_invalidation"}) {
                        assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class), table);
                    }
                    String snapshot = jdbc.queryForObject("select memory_operational_snapshot(?)::text", String.class,
                            campaign);
                    assertEquals(snapshot, jdbc.queryForObject("select memory_operational_snapshot(?)::text",
                            String.class, campaign));
                    assertEquals(jdbc.queryForObject("select state_revision from campaign", Long.class),
                            jdbc.queryForObject("select (memory_operational_snapshot(?) #>> '{campaign,stateRevision}')"
                                    + "::bigint", Long.class, campaign));
                    assertEquals(0, v16.migrate().migrationsExecuted);
                    // V17 adds the AI-session records empty: an old save gets no invented session, its
                    // canonical rows stay exact, and a retired record rejects being reopened.
                    String v16Campaign = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
                    Flyway v17 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("17").load();
                    assertEquals(1, v17.migrate().migrationsExecuted);
                    assertTrue(v17.validateWithResult().validationSuccessful);
                    assertEquals(v16Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c",
                            String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from ai_session_record", Integer.class));
                    UUID record = UUID.randomUUID();
                    jdbc.update("insert into ai_session_record (id, adventure_id, campaign_id, campaign_generation, "
                            + "role, audience, pack_hash, policy_hash, line_generation, opened_cause, status, "
                            + "rehydration_basis, opened_at, retired_reason, retired_at) select ?, adventure_id, id, "
                            + "recovery_generation, 'narrator', 'public', ?, ?, 1, 'FIRST', 'RETIRED', 'NONE', now(), "
                            + "'OWNER', now() from campaign", record, "a".repeat(64), "b".repeat(64));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update ai_session_record set status = "
                            + "'ACTIVE', retired_reason = null, retired_at = null, row_version = row_version + 1 "
                            + "where id = ?", record));
                    assertEquals(0, v17.migrate().migrationsExecuted);
                    // V18 adds conditions and equipment empty: an old save is unchanged and gets no invented
                    // worn item or condition; storage bounds hold even for a stray SQL writer.
                    String v17Campaign = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
                    Flyway v18 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("18").load();
                    assertEquals(1, v18.migrate().migrationsExecuted);
                    assertTrue(v18.validateWithResult().validationSuccessful);
                    assertEquals(v17Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c",
                            String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from actor_condition", Integer.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from equipment_assignment", Integer.class));
                    UUID wearer = UUID.randomUUID();
                    UUID sword = UUID.randomUUID();
                    UUID shield = UUID.randomUUID();
                    UUID arrows = UUID.randomUUID();
                    jdbc.update("insert into actor (id, campaign_id, kind, display_name, created_revision, created_at) "
                            + "values (?, ?, 'CHARACTER', 'Wearer', 0, now())", wearer, campaign);
                    for (UUID item : new UUID[] {sword, shield, arrows}) {
                        jdbc.update("insert into item_instance (id, campaign_id, actor_id, definition_id, quantity, "
                                + "maximum_stack, acquired_revision) values (?, ?, ?, ?, 1, ?, 0)", item, campaign,
                                wearer, "item", item.equals(arrows) ? 20 : 1);
                    }
                    String wear = "insert into equipment_assignment (campaign_id, actor_id, item_instance_id, slot_id, "
                            + "hands, equipped_revision) values (?, ?, ?, 'main-hand', ?, 1)";
                    // A stack cannot be worn, and nothing may claim an instance of another actor.
                    assertThrows(DataAccessException.class, () -> jdbc.update(wear, campaign, wearer, arrows, 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(wear, campaign, actor, sword, 1));
                    assertEquals(1, jdbc.update(wear, campaign, wearer, sword, 3));
                    // Wearing the same instance twice and exceeding the four-hand storage bound both fail.
                    assertThrows(DataAccessException.class, () -> jdbc.update(wear, campaign, wearer, sword, 0));
                    assertThrows(DataAccessException.class, () -> jdbc.update(wear, campaign, wearer, shield, 2));
                    // Slot, hands and owner are frozen, and an equipped instance cannot change hands.
                    assertThrows(DataAccessException.class, () -> jdbc.update("update equipment_assignment set "
                            + "slot_id = 'off-hand', row_version = row_version + 1"));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update item_instance set actor_id = ? "
                            + "where id = ?", actor, sword));
                    String condition = "insert into actor_condition (campaign_id, actor_id, condition_id, stacks, "
                            + "remaining_rounds, applied_revision, updated_revision) values (?, ?, 'dazed', ?, ?, 1, 1)";
                    assertThrows(DataAccessException.class, () -> jdbc.update(condition, campaign, wearer, 11, 3));
                    assertThrows(DataAccessException.class, () -> jdbc.update(condition, campaign, wearer, 1, 0));
                    assertEquals(1, jdbc.update(condition, campaign, wearer, 2, 3));
                    assertThrows(DataAccessException.class, () -> jdbc.update(condition, campaign, wearer, 1, 3));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update actor_condition set "
                            + "applied_revision = 0, row_version = row_version + 1"));
                    assertEquals(0, v18.migrate().migrationsExecuted);
                    // V19 adds the item creation ledger empty: an old save gets no invented creation, and the
                    // ledger is append-only, unique per source, and tied to a real operation, event and actor.
                    String v18Campaign = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
                    Flyway v19 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("19").load();
                    assertEquals(1, v19.migrate().migrationsExecuted);
                    assertTrue(v19.validateWithResult().validationSuccessful);
                    assertEquals(v18Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
                    assertEquals(0, jdbc.queryForObject("select count(*) from item_creation", Integer.class));
                    jdbc.update("insert into campaign_event (id, campaign_id, sequence, operation_id, actor_id, "
                            + "correlation_id, event_type, event_version, state_revision, world_time_seconds, "
                            + "ruleset_hash, visibility, payload_schema_version, payload, created_at) values "
                            + "(?, ?, 1, ?, ?, ?, 'ITEM_CREATED', 1, 7, 60, ?, 'PUBLIC', 1, '{}'::jsonb, now())",
                            UUID.randomUUID(), campaign, operation, wearer, operation, "b".repeat(64));
                    String creation = "insert into item_creation (id, campaign_id, operation_id, event_sequence, "
                            + "source_kind, source_key, recipient_actor_id, definition_id, quantity, applied_revision, "
                            + "created_at) values (?, ?, ?, ?, ?, ?, ?, 'item', ?, 7, now())";
                    assertEquals(1, jdbc.update(creation, UUID.randomUUID(), campaign, operation, 1, "EFFECT",
                            operation + "#effects/0", wearer, 3));
                    // One creation per source, recipient and definition: a replayed source cannot pay twice.
                    assertThrows(DataAccessException.class, () -> jdbc.update(creation, UUID.randomUUID(), campaign,
                            operation, 1, "EFFECT", operation + "#effects/0", wearer, 3));
                    assertThrows(DataAccessException.class, () -> jdbc.update(creation, UUID.randomUUID(), campaign,
                            operation, 1, "GIFT", operation + "#effects/1", wearer, 3));
                    assertThrows(DataAccessException.class, () -> jdbc.update(creation, UUID.randomUUID(), campaign,
                            operation, 1, "EFFECT", operation + "#effects/1", wearer, 0));
                    // The announcing event and the recipient must exist in the same campaign.
                    assertThrows(DataAccessException.class, () -> jdbc.update(creation, UUID.randomUUID(), campaign,
                            operation, 99, "EFFECT", operation + "#effects/1", wearer, 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(creation, UUID.randomUUID(), campaign,
                            operation, 1, "EFFECT", operation + "#effects/1", UUID.randomUUID(), 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update item_creation set quantity = 4"));
                    assertThrows(DataAccessException.class, () -> jdbc.update("delete from item_creation"));
                    assertEquals(0, v19.migrate().migrationsExecuted);
                    // V20 adds quest state, satisfied objectives and visits empty: an old save invents no quest
                    // progress and reads every quest at its pack-declared initial status.
                    String v19Campaign = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
                    Flyway v20 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("20").load();
                    assertEquals(1, v20.migrate().migrationsExecuted);
                    assertTrue(v20.validateWithResult().validationSuccessful);
                    assertEquals(v19Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
                    for (String table : List.of("quest_state", "quest_objective", "location_visit")) {
                        assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
                    }
                    String quest = "insert into quest_state (campaign_id, quest_id, status, outcome_id, event_sequence, "
                            + "updated_revision) values (?, ?, ?, ?, ?, 7)";
                    assertEquals(1, jdbc.update(quest, campaign, "survey", "active", null, 1));
                    assertEquals(1, jdbc.update(quest, campaign, "rescue", "available", null, 1));
                    // One row per quest; unknown statuses, a terminal row without an outcome, an outcome on a
                    // running quest and an event outside the campaign are refused.
                    assertThrows(DataAccessException.class, () -> jdbc.update(quest, campaign, "survey", "active", null, 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(quest, campaign, "other", "paused", null, 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(quest, campaign, "other", "completed", null, 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(quest, campaign, "other", "active", "won", 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(quest, campaign, "other", "active", null, 99));
                    // Updates step the row version; discovery cannot be repeated; an ended quest never changes.
                    String advance = "update quest_state set status = ?, outcome_id = ?, row_version = row_version + 1 "
                            + "where quest_id = ?";
                    assertThrows(DataAccessException.class, () -> jdbc.update("update quest_state set status = 'suspended' "
                            + "where quest_id = 'survey'"));
                    assertThrows(DataAccessException.class, () -> jdbc.update(advance, "available", null, "survey"));
                    assertEquals(1, jdbc.update(advance, "completed", "surveyed", "survey"));
                    assertThrows(DataAccessException.class, () -> jdbc.update(advance, "failed", "abandoned", "survey"));
                    assertThrows(DataAccessException.class, () -> jdbc.update(advance, "active", null, "survey"));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update quest_state set "
                            + "row_version = row_version + 1, quest_id = 'renamed' where quest_id = 'rescue'"));
                    // A satisfied objective needs its quest and a real event, and is never changed or removed.
                    String objective = "insert into quest_objective (campaign_id, quest_id, objective_id, event_sequence, "
                            + "satisfied_revision) values (?, ?, ?, ?, 7)";
                    assertEquals(1, jdbc.update(objective, campaign, "survey", "reach", 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(objective, campaign, "survey", "reach", 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(objective, campaign, "unknown", "reach", 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(objective, campaign, "survey", "other", 99));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update quest_objective set satisfied_revision = 8"));
                    assertThrows(DataAccessException.class, () -> jdbc.update("delete from quest_objective"));
                    // A visit belongs to an actor of the campaign and is immutable.
                    String visit = "insert into location_visit (campaign_id, actor_id, location_id, first_revision) "
                            + "values (?, ?, ?, 7)";
                    assertEquals(1, jdbc.update(visit, campaign, wearer, "grove"));
                    assertThrows(DataAccessException.class, () -> jdbc.update(visit, campaign, wearer, "grove"));
                    assertThrows(DataAccessException.class, () -> jdbc.update(visit, campaign, UUID.randomUUID(), "trail"));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update location_visit set first_revision = 8"));
                    assertThrows(DataAccessException.class, () -> jdbc.update("delete from location_visit"));
                    assertEquals(0, v20.migrate().migrationsExecuted);
                    // V21 bounds the world clock: it keeps its value, may not pass its end, and moves only with
                    // its revision, by at most one in-world day, and never backwards.
                    String v20Campaign = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
                    Flyway v21 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("21").load();
                    assertEquals(1, v21.migrate().migrationsExecuted);
                    assertTrue(v21.validateWithResult().validationSuccessful);
                    assertEquals(v20Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
                    long clock = jdbc.queryForObject("select world_time_seconds from campaign", Long.class);
                    String tick = "update campaign set world_time_seconds = ?, state_revision = state_revision + ?, "
                            + "row_version = row_version + 1";
                    assertThrows(DataAccessException.class, () -> jdbc.update(tick, clock + 5, 0));
                    assertThrows(DataAccessException.class, () -> jdbc.update(tick, clock + 86_401, 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(tick, 3_155_760_001L, 1));
                    assertEquals(v20Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
                    assertEquals(1, jdbc.update(tick, clock + 86_400, 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(tick, clock + 1, 1));
                    assertEquals(0, v21.migrate().migrationsExecuted);
                    // V22 stores frozen encounters: the populated campaign is unchanged, an encounter starts at
                    // ENTERING, at most one is active, frozen columns never change, the lifecycle follows the state
                    // machine and an opening strike resolves once.
                    String v21Campaign = jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class);
                    Flyway v22 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("22").load();
                    assertEquals(1, v22.migrate().migrationsExecuted);
                    assertTrue(v22.validateWithResult().validationSuccessful);
                    assertEquals(v21Campaign, jdbc.queryForObject("select to_jsonb(c)::text from campaign c", String.class));
                    assertEquals(0L, jdbc.queryForObject("select count(*) from encounter", Long.class));
                    String digest = "a".repeat(64);
                    String encounter = "insert into encounter (id, campaign_id, template_id, location_id, phase, round, "
                            + "content_revision_id, ruleset_hash, profile, profile_hash, definition, definition_hash, "
                            + "opening_policy, opening_target, opening_status, started_operation_id, event_sequence, "
                            + "started_revision, updated_revision, created_at) values (?, ?, 'ambush', 'trail', ?, 0, ?, ?, "
                            + "'{}', ?, '{}', ?, ?, ?, ?, ?, 1, 7, 7, now())";
                    UUID fight = UUID.randomUUID();
                    assertThrows(DataAccessException.class, () -> jdbc.update(encounter, UUID.randomUUID(), campaign,
                            "ROUND_OPEN", revision, digest, digest, digest, "initiative", null, "NONE", operation));
                    assertThrows(DataAccessException.class, () -> jdbc.update(encounter, UUID.randomUUID(), campaign,
                            "ENTERING", revision, digest, digest, digest, "initiative", "raider", "RESOLVED", operation));
                    assertThrows(DataAccessException.class, () -> jdbc.update(encounter, UUID.randomUUID(), campaign,
                            "ENTERING", revision, digest, digest, digest, "initiative", null, "PENDING", operation));
                    assertThrows(DataAccessException.class, () -> jdbc.update(encounter, UUID.randomUUID(), campaign,
                            "ENTERING", revision, digest, digest, digest, "forbidden", "raider", "PENDING", operation));
                    assertEquals(1, jdbc.update(encounter, fight, campaign, "ENTERING", revision, digest, digest, digest,
                            "initiative", "raider", "PENDING", operation));
                    assertThrows(DataAccessException.class, () -> jdbc.update(encounter, UUID.randomUUID(), campaign,
                            "ENTERING", revision, digest, digest, digest, "initiative", null, "NONE", operation));
                    // The roster: the player is actor-backed at ordinal 0; a template participant keeps local pools.
                    String member = "insert into encounter_participant (encounter_id, campaign_id, participant_id, ordinal, "
                            + "actor_id, side, zone_id, health_current, focus_current, frozen) values (?, ?, ?, ?, ?, ?, "
                            + "'entry', ?, ?, '{}')";
                    assertEquals(1, jdbc.update(member, fight, campaign, "player", 0, wearer, "PLAYER", null, null));
                    assertEquals(1, jdbc.update(member, fight, campaign, "raider", 1, null, "HOSTILE", 14, 0));
                    assertThrows(DataAccessException.class, () -> jdbc.update(member, fight, campaign, "brute", 2, null,
                            "HOSTILE", null, null));
                    assertThrows(DataAccessException.class, () -> jdbc.update(member, fight, campaign, "player", 3, wearer,
                            "ALLY", null, null));
                    assertThrows(DataAccessException.class, () -> jdbc.update(member, fight, campaign, "ghost", 4,
                            UUID.randomUUID(), "ALLY", null, null));
                    String step = "update encounter set phase = ?, round = ?, row_version = row_version + 1";
                    assertThrows(DataAccessException.class, () -> jdbc.update(step, "SETTLED", 0));
                    assertThrows(DataAccessException.class, () -> jdbc.update(step, "ROUND_OPEN", 2));
                    assertThrows(DataAccessException.class, () -> jdbc.update(step, "ENTERING", 1));
                    assertEquals(1, jdbc.update(step, "ROUND_OPEN", 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(step, "ENTERING", 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set "
                            + "definition = '{\"x\": 1}', row_version = row_version + 1"));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set "
                            + "opening_status = 'NONE', row_version = row_version + 1"));
                    assertEquals(1, jdbc.update("update encounter set opening_status = 'RESOLVED', row_version = row_version + 1"));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set "
                            + "opening_status = 'PENDING', row_version = row_version + 1"));
                    assertThrows(DataAccessException.class, () -> jdbc.update("delete from encounter"));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update encounter_participant set "
                            + "side = 'ALLY', row_version = row_version + 1 where participant_id = 'raider'"));
                    assertThrows(DataAccessException.class, () -> jdbc.update("delete from encounter_participant"));
                    assertEquals(1, jdbc.update("update encounter_participant set zone_id = 'yard', "
                            + "row_version = row_version + 1 where participant_id = 'player'"));
                    assertEquals(0, v22.migrate().migrationsExecuted);
                    // V23 adds the tactical cursor: the populated encounter keeps its row with no activation, no
                    // speech and no changed passage; a cursor comes with points, initiative is written once with
                    // distinct ranks, points grow only at an activation start and speech by one per update.
                    String v22Fight = jdbc.queryForObject("select to_jsonb(e)::text from encounter e", String.class);
                    Flyway v23 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("23").load();
                    assertEquals(1, v23.migrate().migrationsExecuted);
                    assertTrue(v23.validateWithResult().validationSuccessful);
                    var upgraded = jdbc.queryForMap("select actor_cursor, action_points, speech_used, passages::text as p "
                            + "from encounter");
                    assertNull(upgraded.get("actor_cursor"));
                    assertNull(upgraded.get("action_points"));
                    assertEquals(0, upgraded.get("speech_used"));
                    assertEquals("[]", upgraded.get("p"));
                    assertEquals(v22Fight, jdbc.queryForObject("select (to_jsonb(e) - 'actor_cursor' - 'action_points' "
                            + "- 'speech_used' - 'passages')::text from encounter e", String.class));
                    assertEquals(2L, jdbc.queryForObject("select count(*) from encounter_participant "
                            + "where initiative is null and reactions = 0", Long.class));
                    String rank = "update encounter_participant set initiative = ?, initiative_rank = ?, "
                            + "row_version = row_version + 1 where participant_id = ?";
                    assertEquals(1, jdbc.update(rank, 12, 0, "player"));
                    assertThrows(DataAccessException.class, () -> jdbc.update(rank, 9, 0, "raider"));
                    assertThrows(DataAccessException.class, () -> jdbc.update(rank, 13, 0, "player"));
                    assertThrows(DataAccessException.class, () -> jdbc.update(rank, 40, 1, "raider"));
                    assertEquals(1, jdbc.update(rank, 9, 1, "raider"));
                    String cursor = "update encounter set phase = ?, actor_cursor = ?, action_points = ?, speech_used = ?, "
                            + "row_version = row_version + 1";
                    assertThrows(DataAccessException.class, () -> jdbc.update(cursor, "ACTOR_START", null, null, 0));
                    assertThrows(DataAccessException.class, () -> jdbc.update(cursor, "ACTOR_START", 0, null, 0));
                    assertEquals(1, jdbc.update(cursor, "ACTOR_START", 0, 3, 0));
                    assertEquals(1, jdbc.update(cursor, "AWAITING_ACTION", 0, 2, 0));
                    assertThrows(DataAccessException.class, () -> jdbc.update(cursor, "AWAITING_ACTION", 0, 3, 0));
                    assertEquals(1, jdbc.update(cursor, "AWAITING_ACTION", 0, 2, 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update(cursor, "AWAITING_ACTION", 0, 2, 3));
                    assertThrows(DataAccessException.class, () -> jdbc.update(cursor, "AWAITING_ACTION", 0, 2, 0));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update encounter set passages = '{}', "
                            + "row_version = row_version + 1"));
                    assertEquals(0, v23.migrate().migrationsExecuted);
                    // V24 adds the Guard stance: the populated fight keeps its rows with no stance held, local Health stays
                    // within the frozen maximum, zero Health cannot stand and a defeated participant stays defeated.
                    String v23Roster = jdbc.queryForObject("select jsonb_agg(to_jsonb(p) order by p.participant_id)::text "
                            + "from encounter_participant p", String.class);
                    Flyway v24 = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration").target("24").load();
                    assertEquals(1, v24.migrate().migrationsExecuted);
                    assertTrue(v24.validateWithResult().validationSuccessful);
                    assertEquals(v23Roster, jdbc.queryForObject("select jsonb_agg(to_jsonb(p) - 'guarding' order by "
                            + "p.participant_id)::text from encounter_participant p", String.class));
                    assertEquals(0L, jdbc.queryForObject("select count(*) from encounter_participant where guarding", Long.class));
                    assertEquals(1, jdbc.update("update encounter_participant set guarding = true, row_version = "
                            + "row_version + 1 where participant_id = 'raider'"));
                    assertEquals(1, jdbc.update("insert into encounter_participant (encounter_id, campaign_id, participant_id, "
                            + "ordinal, side, zone_id, health_current, focus_current, frozen) values (?, ?, 'rival', 2, 'HOSTILE', "
                            + "'entry', 14, 0, '{\"healthMax\": 14}')", fight, campaign));
                    String vitals = "update encounter_participant set health_current = ?, row_version = row_version + 1 "
                            + "where participant_id = 'rival'";
                    assertThrows(DataAccessException.class, () -> jdbc.update(vitals, 15));
                    assertThrows(DataAccessException.class, () -> jdbc.update(vitals, 0));
                    assertEquals(1, jdbc.update(vitals, 13));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update encounter_participant set "
                            + "status = 'DEFEATED', row_version = row_version + 1 where participant_id = 'raider'"),
                            "a stance ends with its holder");
                    assertEquals(1, jdbc.update("update encounter_participant set health_current = 0, status = 'DEFEATED', "
                            + "row_version = row_version + 1 where participant_id = 'rival'"));
                    assertThrows(DataAccessException.class, () -> jdbc.update(vitals, 1));
                    assertThrows(DataAccessException.class, () -> jdbc.update("update encounter_participant set "
                            + "status = 'ACTIVE', row_version = row_version + 1 where participant_id = 'rival'"));
                    assertEquals(0, v24.migrate().migrationsExecuted);
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
