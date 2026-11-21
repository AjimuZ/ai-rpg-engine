package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.FirstStartProvisioning;
import org.rpgengine.campaign.CampaignCreationService;
import org.rpgengine.campaign.CharacterChoices;
import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.memory.MemoryStartupReconciliation;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java side of the P14.2-B restore rehearsal (QA-03, QA-23). The name keeps it out of backend test runs, because
 * it needs a database the shipped backup tool can reach: {@code tools/container_packaging/test_sample_restore.py} runs
 * {@link #seedSourceInstallation} against a new source database, runs {@code tools/database_backup/backup.py} to back
 * it up and restore it into a new database, and runs {@link #restoredInstallationRecoversTheSample} against the restored
 * database in a new JVM with an empty managed-folder root. {@code ENGINE_RESTORE_CHECK_DIR} carries the evidence
 * between the two phases. Both phases start as a packaged start does: provisioning and the memory pass are on.
 *
 * <p>The source holds two campaigns of the same owner:
 * <ul>
 * <li>a fight on the bundled sample {@code sample:lantern-ford} exactly as first-start provisioning installs it: the
 *     ford ambush is entered, and its initiative roll is cut off after its dice are reserved, with its permit held as a
 *     killed process leaves it. The backup therefore holds an active fight, a pending operation and narration still
 *     owed for the entry;</li>
 * <li>a supplies variant of the same sample in a second workspace, which adds a consumable, a quest reward and an image
 *     asset (the sample itself has none): a tonic used once and the crossing quest completed once by a move, each
 *     retried under the same request identity before the backup.</li>
 * </ul>
 * After the restore, both requests are retried again, the cut roll's lost response is retried, and the fight goes on.
 * No narration runtime, account, browser or generated image takes part. Rows are deliberately not removed: the databases
 * are disposable, and seeding refuses a database that holds a campaign.
 */
@SpringBootTest(properties = {"engine.provisioning.enabled=true",
        "engine.http.owner-id=00000000-0000-4000-8000-000000001422",
        "engine.memory.reconciliation.enabled=true"})
class SampleRestoreCheck {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000001422");
    /** Original project fixture artwork, sealed into the supplies variant as its only asset. */
    private static final String ASSET = "/packs/fantasy-exploration/assets/map.png";
    @Autowired private FirstStartProvisioning provisioning;
    @Autowired private CampaignCreationService creation;
    @Autowired private CommandExecutionService executions;
    @Autowired private PackRegistry registry;
    @Autowired private MemoryStartupReconciliation reconciliation;
    @Autowired private JdbcTemplate jdbc;
    @Value("${engine.workspaces.root:}") private String workspacesRoot;

    /**
     * What the source held at its backup.
     *
     * @param fight the sample campaign whose fight is active
     * @param supplies the variant campaign that used the tonic and completed the quest
     * @param use the tonic use, retried under its identity
     * @param move the move that completed the quest and paid its reward, retried under its identity
     * @param roll the initiative roll cut off before its commit
     * @param entry the committed fight entry whose narration was still owed
     * @param reserved the cut roll's reserved draws as {@code ordinal:sides:value}
     * @param world every canonical row of both campaigns and their content, minus what a restore retires
     */
    record Evidence(UUID fightAdventure, UUID fight, UUID suppliesAdventure, UUID supplies,
            OperationAdmissionService.Command use, String useReceipt, OperationAdmissionService.Command move,
            String moveReceipt, OperationAdmissionService.Command roll, UUID rollOperation, String reserved,
            UUID entry, Map<String, List<String>> world) {
    }

    /** Construct the injected check; missing database infrastructure fails context startup. */
    SampleRestoreCheck() { }

    /** Provisioned sample, both campaigns, lost responses retried once, and an attempt cut off before its commit. */
    @Test
    void seedSourceInstallation() throws Exception {
        assertEquals(0, rows("campaign"), "the rehearsal needs a new, disposable database");
        assertEquals(List.of(1, 2, 2), List.of(rows("identity_owner_profile"), rows("adventure_workspace"),
                rows("content_revision")), "the start provisioned the owner and both bundled samples (ADR 0055)");
        UUID sample = provisioning.provision(OWNER).workspaceId();

        UUID suppliesAdventure = UUID.randomUUID();
        jdbc.update("insert into adventure_workspace (id, owner_id, title, genre, source_locale, created_at, updated_at) "
                + "values (?, ?, 'Lantern Ford supplies', 'fantasy', 'en', now(), now())", suppliesAdventure, OWNER);
        UUID suppliesRevision = registry.register(suppliesAdventure, new PackStaging(PackStaging.Limits.standard())
                .archive(new ByteArrayInputStream(ArmoryPack.zip(supplies())))).revisionId();
        UUID supplies = campaign(suppliesAdventure, suppliesRevision, "Supplies");
        UUID player = player(supplies);

        // QA-03: one tonic consumed, retried under the same identity before the restart.
        assertEquals(2, held(supplies, player, "tonic"));
        var use = envelope(supplies, new TypedCommand.UseItem(item(supplies, player, "tonic"), List.of()));
        var used = committed(suppliesAdventure, supplies, use);
        assertEquals(1, held(supplies, player, "tonic"), "one unit is consumed");
        var beforeUseRetry = everything();
        var usedAgain = executions.execute(OWNER, suppliesAdventure, supplies, use);
        assertTrue(usedAgain.replayed(), "the lost response is answered from the journal");
        assertEquals(text(used.receipt()), text(usedAgain.receipt()));
        assertEquals(beforeUseRetry, everything(), "and consumes nothing again");

        // QA-03: one reward paid, retried under the same identity before the restart.
        committed(suppliesAdventure, supplies, envelope(supplies, new TypedCommand.AcceptQuest("crossing")));
        var move = envelope(supplies, new TypedCommand.Move("far-bank"));
        var moved = committed(suppliesAdventure, supplies, move);
        assertEquals(5, held(supplies, player, "coin"), "the quest pays its reward");
        assertEquals(1, rewards(supplies));
        var beforeMoveRetry = everything();
        var movedAgain = executions.execute(OWNER, suppliesAdventure, supplies, move);
        assertTrue(movedAgain.replayed());
        assertEquals(text(moved.receipt()), text(movedAgain.receipt()));
        assertEquals(beforeMoveRetry, everything(), "and pays nothing again");

        // QA-23: an active fight with a pending operation and owed narration on the bundled sample.
        UUID sampleRevision = jdbc.queryForObject("select id from content_revision where adventure_id = ?", UUID.class,
                sample);
        UUID fight = campaign(sample, sampleRevision, "Ford watch");
        var entry = committed(sample, fight, envelope(fight, new TypedCommand.Engage("ford-ambush", null)));
        var roll = envelope(fight, new TypedCommand.RollInitiative());
        var world = world();
        crash(sample, fight, roll);
        UUID cut = jdbc.queryForObject("select id from operation_journal where campaign_id = ? and idempotency_key = ?",
                UUID.class, fight, roll.requestId());
        assertEquals("VALIDATING", status(cut), "the crash left the attempt unresolved");
        String reserved = draws(cut);
        assertNotNull(reserved, "the roll reserved its dice before the crash");
        assertEquals(world, world(), "an attempt cut off before its commit changes nothing of the world");
        assertEquals(1, jdbc.queryForObject("select count(*) from campaign_mutation_lease where campaign_id = ? and "
                + "operation_id = ?", Integer.class, fight, cut), "its permit is still held");
        assertEquals(1, jdbc.queryForObject("select count(*) from encounter where campaign_id = ?", Integer.class, fight));

        Files.writeString(evidenceDirectory().resolve("sample-evidence.json"), JSON.writeValueAsString(new Evidence(
                sample, fight, suppliesAdventure, supplies, use, text(used.receipt()), move, text(moved.receipt()), roll,
                cut, reserved, entry.operation().operationId(), world)));
    }

    /**
     * Starting on the restored database changes no world row, provisions nothing again, ends the cut attempt before
     * commit with its dice, and turns owed narration into receipts; both lost responses then replay their receipts
     * without consuming or paying again, and the fight continues from the same dice.
     */
    @Test
    void restoredInstallationRecoversTheSample() throws Exception {
        var evidence = JSON.readValue(Files.readString(evidenceDirectory().resolve("sample-evidence.json")),
                Evidence.class);
        assertEquals(evidence.world(), world(), "every canonical row of both campaigns and their content is restored");
        assertEquals(List.of(1, 3, 3), List.of(rows("identity_owner_profile"), rows("adventure_workspace"),
                rows("content_revision")), "a restored installation is not provisioned again");
        assertEquals(2, jdbc.queryForObject("select count(*) from campaign where recovery_generation = 1", Integer.class));
        var report = reconciliation.lastReport();
        assertNotNull(report, "the memory pass ran at this start");
        assertEquals(0, report.failures());

        // The pending operation: the cut roll ended before commit, with its dice kept.
        assertEquals("FAILED_BEFORE_COMMIT", status(evidence.rollOperation()));
        assertEquals(evidence.reserved(), draws(evidence.rollOperation()));
        assertEquals(0, jdbc.queryForObject("select count(*) from campaign_mutation_lease where operation_id is not null",
                Integer.class), "no permit survives the restore");
        // Narration owed at the backup belongs to the retired generation and becomes a receipt.
        for (UUID operation : List.of(evidence.entry(), operation(evidence.supplies(), evidence.use()),
                operation(evidence.supplies(), evidence.move()))) {
            assertEquals("COMMITTED_NARRATION_PENDING", status(operation));
        }
        assertAssetRecovered(evidence.suppliesAdventure());

        // QA-03 after the restart: both lost responses replay their receipts and change nothing.
        var settled = everything();
        var use = executions.execute(OWNER, evidence.suppliesAdventure(), evidence.supplies(), evidence.use());
        assertTrue(use.replayed());
        assertEquals(JSON.readTree(evidence.useReceipt()), JSON.readTree(text(use.receipt())));
        var move = executions.execute(OWNER, evidence.suppliesAdventure(), evidence.supplies(), evidence.move());
        assertTrue(move.replayed());
        assertEquals(JSON.readTree(evidence.moveReceipt()), JSON.readTree(text(move.receipt())));
        assertEquals(settled, everything(), "retries after a restore consume, pay and announce nothing again");
        UUID player = player(evidence.supplies());
        assertEquals(1, held(evidence.supplies(), player, "tonic"), "one unit was consumed, once");
        assertEquals(5, held(evidence.supplies(), player, "coin"), "the reward was paid, once");
        assertEquals(1, rewards(evidence.supplies()));
        assertEquals(1, events(evidence.supplies(), "QUEST_COMPLETED"));

        // The fight continues: the lost response of the cut roll replays its ending, and the roll asked again commits
        // with the same dice.
        var quiet = world();
        var lost = executions.execute(OWNER, evidence.fightAdventure(), evidence.fight(), evidence.roll());
        assertTrue(lost.replayed());
        assertEquals(OperationStatus.FAILED_BEFORE_COMMIT, lost.operation().status(), "a retry never resumes the attempt");
        assertNull(lost.receipt());
        assertEquals(quiet, world(), "replaying an ending changes nothing of the world");
        var again = envelope(evidence.fight(), new TypedCommand.RollInitiative());
        var rolled = committed(evidence.fightAdventure(), evidence.fight(), again);
        assertNotEquals(evidence.rollOperation(), rolled.operation().operationId());
        assertEquals(evidence.reserved(), draws(rolled.operation().operationId()), "a restore is not a reroll");
        assertEquals(1, events(evidence.fight(), "INITIATIVE_ROLLED"));
        assertTrue(jdbc.queryForObject("select round from encounter where campaign_id = ?", Integer.class,
                evidence.fight()) >= 1, "the restored fight opened its first round");
        var after = everything();
        var replay = executions.execute(OWNER, evidence.fightAdventure(), evidence.fight(), again);
        assertTrue(replay.replayed());
        assertEquals(text(rolled.receipt()), text(replay.receipt()));
        assertEquals(after, everything());
        assertEquals(1, events(evidence.fight(), "INITIATIVE_ROLLED"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * The bundled sample with what it lacks for QA-03 and QA-23: a stackable tonic (two at creation) that restores one
     * Vigor, coins, five coins paid by the crossing quest, and one image asset. Everything else is the bundled bytes.
     */
    private static Map<String, byte[]> supplies() throws Exception {
        var files = new TreeMap<>(new PackStaging(PackStaging.Limits.standard()).directory(
                Path.of(SampleRestoreCheck.class.getResource("/sample-adventure").toURI())).files());
        var manifest = CommandExecutionTest.document(files, "adventure.json");
        @SuppressWarnings("unchecked")
        var listed = new ArrayList<>((List<Object>) manifest.get("files"));
        listed.add(Map.of("path", "assets/map.png", "category", "asset"));
        manifest.put("files", listed);
        files.put("adventure.json", JSON.writeValueAsBytes(manifest));
        try (InputStream asset = SampleRestoreCheck.class.getResourceAsStream(ASSET)) {
            files.put("assets/map.png", asset.readAllBytes());
        }

        var items = CommandExecutionTest.document(files, "items.json");
        @SuppressWarnings("unchecked")
        var definitions = new ArrayList<>((List<Object>) items.get("definitions"));
        definitions.add(Map.of("id", "tonic", "kind", "item", "data", Map.of("value", 10, "name", "Tonic",
                "description", "A bitter draught.", "itemType", "consumable", "maxStack", 5, "rarity", "ordinary",
                "appearance", Map.of("id", "tonic.flask", "summary", "A small corked flask."),
                "use", Map.of("target", Map.of("policy", "self"), "effects", List.of(Map.of("type", "modifyResource",
                        "resource", "vigor", "amount", 1))))));
        definitions.add(Map.of("id", "coin", "kind", "item", "data", Map.of("value", 1, "name", "Coin",
                "description", "A worn coin.", "itemType", "treasure", "maxStack", 999, "rarity", "ordinary",
                "appearance", Map.of("id", "coin.copper", "summary", "A worn copper coin."))));
        items.put("definitions", definitions);
        files.put("items.json", JSON.writeValueAsBytes(items));

        var creationFile = CommandExecutionTest.document(files, "creation.json");
        @SuppressWarnings("unchecked")
        var profile = (Map<String, Object>) ((List<Map<String, Object>>) creationFile.get("definitions")).getFirst()
                .get("data");
        @SuppressWarnings("unchecked")
        var starting = new ArrayList<>((List<Object>) profile.get("startingItems"));
        starting.add(Map.of("item", "tonic", "quantity", 2));
        profile.put("startingItems", starting);
        files.put("creation.json", JSON.writeValueAsBytes(creationFile));

        var quests = CommandExecutionTest.document(files, "quests.json");
        @SuppressWarnings("unchecked")
        var crossing = (Map<String, Object>) ((List<Map<String, Object>>) quests.get("definitions")).getFirst().get("data");
        @SuppressWarnings("unchecked")
        var outcome = ((List<Map<String, Object>>) crossing.get("outcomes")).getFirst();
        @SuppressWarnings("unchecked")
        var settlement = (Map<String, Object>) outcome.get("settlement");
        settlement.put("rewards", List.of(Map.of("item", "coin", "quantity", 5)));
        files.put("quests.json", JSON.writeValueAsBytes(quests));
        return files;
    }

    /**
     * The restored database holds the asset's exact bytes under its recorded hash, and the start rebuilt it, byte for
     * byte, under the empty managed-folder root.
     */
    private void assertAssetRecovered(UUID adventure) throws Exception {
        byte[] original;
        try (InputStream asset = SampleRestoreCheck.class.getResourceAsStream(ASSET)) {
            original = asset.readAllBytes();
        }
        byte[] stored = jdbc.queryForObject("select f.content from content_revision_file f join content_revision r on "
                + "r.id = f.content_revision_id where r.adventure_id = ? and f.path = 'assets/map.png' and f.sha256 = "
                + "encode(sha256(f.content), 'hex')", byte[].class, adventure);
        assertArrayEquals(original, stored);
        assertFalse(workspacesRoot.isEmpty(), "the rehearsal sets an empty managed-folder root");
        List<Path> rebuilt;
        try (Stream<Path> walk = Files.walk(Path.of(workspacesRoot))) {
            rebuilt = walk.filter(path -> path.getFileName().toString().equals("map.png")).toList();
        }
        assertEquals(1, rebuilt.size(), "one projected asset, for the variant's single revision");
        assertArrayEquals(original, Files.readAllBytes(rebuilt.getFirst()));
    }

    /** Create one English playthrough with the sample's scout archetype; any refusal fails. */
    private UUID campaign(UUID adventure, UUID revision, String name) {
        return creation.create(OWNER, adventure, new CampaignCreationService.Request(UUID.randomUUID(), revision, name,
                SupportedLocale.EN, new CharacterChoices("Player", "scout", Map.of(), Map.of(), null, null))).campaignId();
    }

    /**
     * Submit a request whose commit and whose failure bookkeeping both fail after its dice are reserved, as when the
     * process dies between them: the operation stays {@code VALIDATING} with its permit held. The injected trigger
     * refuses every update that moves an operation out of {@code VALIDATING}, and is removed before returning.
     */
    private void crash(UUID adventure, UUID campaign, OperationAdmissionService.Command request) {
        jdbc.execute("create or replace function test_restore_crash() returns trigger language plpgsql as "
                + "$$ begin raise exception 'injected crash'; end $$");
        jdbc.execute("create trigger test_restore_crash before update on operation_journal for each row "
                + "when (old.status = 'VALIDATING' and new.status <> 'VALIDATING') execute function test_restore_crash()");
        try {
            var failure = assertThrows(RuntimeException.class, () -> executions.execute(OWNER, adventure, campaign, request));
            assertFalse(failure instanceof OperationAdmissionFailure, "an infrastructure failure, not a refusal");
        } finally {
            jdbc.execute("drop trigger if exists test_restore_crash on operation_journal");
            jdbc.execute("drop function if exists test_restore_crash()");
        }
    }

    private CommandExecutionService.Execution committed(UUID adventure, UUID campaign,
            OperationAdmissionService.Command command) {
        var execution = executions.execute(OWNER, adventure, campaign, command);
        assertFalse(execution.replayed(), command.commandType());
        assertEquals(OperationStatus.COMMITTED, execution.operation().status(), command.commandType());
        return execution;
    }

    /** @return a new request at the campaign's current committed revision */
    private OperationAdmissionService.Command envelope(UUID campaign, TypedCommand command) {
        long revision = jdbc.queryForObject("select state_revision from campaign where id = ?", Long.class, campaign);
        return new OperationAdmissionService.Command(UUID.randomUUID().toString(), revision, command.type(),
                command.payload());
    }

    /** @return a receipt as JSON text, so receipts from two processes compare by value */
    private static String text(Map<String, Object> receipt) {
        return JSON.writeValueAsString(receipt);
    }

    private UUID player(UUID campaign) {
        return jdbc.queryForObject("select actor_id from campaign_creation where campaign_id = ?", UUID.class, campaign);
    }

    private UUID operation(UUID campaign, OperationAdmissionService.Command command) {
        return jdbc.queryForObject("select id from operation_journal where campaign_id = ? and idempotency_key = ?",
                UUID.class, campaign, command.requestId());
    }

    private UUID item(UUID campaign, UUID actor, String definition) {
        return jdbc.queryForObject("select id from item_instance where campaign_id = ? and actor_id = ? and definition_id = ? "
                + "order by id limit 1", UUID.class, campaign, actor, definition);
    }

    private int held(UUID campaign, UUID actor, String definition) {
        return jdbc.queryForObject("select coalesce(sum(quantity), 0) from item_instance where campaign_id = ? and actor_id = ? "
                + "and definition_id = ?", Integer.class, campaign, actor, definition);
    }

    private int rewards(UUID campaign) {
        return jdbc.queryForObject("select count(*) from reward_settlement where campaign_id = ?", Integer.class, campaign);
    }

    private String status(UUID operation) {
        return jdbc.queryForObject("select status from operation_journal where id = ?", String.class, operation);
    }

    /** One operation's draws as {@code ordinal:sides:value}, or null when it has none. */
    private String draws(UUID operation) {
        return jdbc.queryForObject("select string_agg(ordinal || ':' || sides || ':' || value, ',' order by ordinal) "
                + "from operation_random_draw where operation_id = ?", String.class, operation);
    }

    private int events(UUID campaign, String type) {
        return jdbc.queryForObject("select count(*) from campaign_event where campaign_id = ? and event_type = ?",
                Integer.class, campaign, type);
    }

    /** @return the row count of a fixed table */
    private int rows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /**
     * The state a restore must keep: every row of the campaigns, their actors, pools, conditions, equipment, items, item
     * ledger, abilities, events, quests, visits, fights and reward settlements, and the sealed content with its hashes
     * (file bytes are compared through their recorded SHA-256, which a database constraint ties to the bytes). The
     * campaign's recovery generation and row version are left out, because the restore advances them by design.
     */
    private Map<String, List<String>> world() {
        var tables = new LinkedHashMap<String, List<String>>();
        tables.put("campaign", jdbc.queryForList("select (to_jsonb(t) - 'recovery_generation' - 'row_version')::text "
                + "from campaign t order by 1", String.class));
        tables.put("content_revision_file", jdbc.queryForList("select (to_jsonb(t) - 'content')::text from "
                + "content_revision_file t order by 1", String.class));
        for (String table : List.of("content_revision", "actor", "actor_resource", "actor_condition", "equipment_assignment",
                "item_instance", "item_creation", "learned_ability", "campaign_event", "quest_state", "quest_objective",
                "location_visit", "encounter", "encounter_participant", "reward_settlement")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }

    /** The world plus the journal, draws, intents, narration and delivery rows a lost-response replay must not touch. */
    private Map<String, List<String>> everything() {
        var tables = world();
        for (String table : List.of("operation_journal", "operation_random_draw", "canonical_outbox",
                "narration_publication", "presentation_job", "presentation_event", "campaign_stream_message",
                "campaign_stream_head")) {
            tables.put(table, jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class));
        }
        return tables;
    }

    /** @return the directory shared by both phases; a missing setting fails instead of guessing a location */
    private static Path evidenceDirectory() {
        String directory = System.getenv("ENGINE_RESTORE_CHECK_DIR");
        assertNotNull(directory, "run this check through tools/container_packaging/test_sample_restore.py");
        return Path.of(directory);
    }
}
