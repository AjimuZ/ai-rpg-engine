package org.rpgengine.content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rpgengine.adventure.AdventureWorkspace;
import org.rpgengine.identity.OwnerProfile;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA-02 through the author validation report (P16.1), on real PostgreSQL and the bundled
 * fantasy and salvage fixtures. Each invalid case mutates one fact of an otherwise valid pack and
 * requires the exact code, stage, file, one-based line and column, and pointer. Expected line and
 * column are computed from the mutated source text, independently of the parser. The other cases
 * cover version standing, version inspection, the dependency graph and the absence of writes.
 */
@SpringBootTest
class PackDiagnosticsTest {
    private static final PackStaging STAGING = new PackStaging(PackStaging.Limits.standard());
    private static final String FANTASY = "fantasy-exploration";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private PackDiagnostics diagnostics;
    @Autowired private PackRegistry registry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager manager;

    /** Construct an injected corpus; unavailable PostgreSQL fails context initialization. */
    PackDiagnosticsTest() {
    }

    /** Delete disposable rows after each case; TRUNCATE preserves immutable-row triggers. */
    @AfterEach
    void clear() {
        jdbc.execute("truncate encounter_participant, encounter, quest_objective, quest_state, location_visit, item_creation, equipment_assignment, actor_condition, ai_session_record, memory_invalidation, memory_summary, memory_summary_job, relationship_change, faction_clock_change, learned_recipe,actor_commitment, actor_sighting, knowledge_belief, knowledge_claim, inference_task, narration_publication, presentation_event, presentation_job, campaign_stream_message, campaign_stream_head, campaign_known_fact, campaign_known_quest, campaign_creation, character_state, item_instance, learned_ability, plan_activation, plan_disposition, plan_admission, plan_dependency, quest_plan_artifact, "
                + "ai_job_attempt, ai_job, planning_snapshot, query_epoch, campaign_mutation_lease, "
                + "operation_random_draw, campaign_random_seed, canonical_outbox, campaign_checkpoint, "
                + "reward_settlement, campaign_event, operation_journal, actor_resource, actor, "
                + "presentation_campaign_settings, campaign, content_revision_report, content_revision_file, "
                + "content_revision, adventure_workspace, identity_owner_profile");
    }

    /**
     * QA-02: missing references, cycles, unknown effects, traversal and aliases each fail at their
     * exact source coordinates, through the same report, and nothing is written.
     */
    @Test
    void reportsQa02FailuresAtExactLocations() throws Exception {
        UUID adventure = workspace();

        // Missing reference: the starting item names a definition that does not exist.
        var reference = fixture(FANTASY);
        reference.put("creation.json", replace(reference, "creation.json", "\"item\": \"kit\"", "\"item\": \"missing-kit\""));
        expect(validate(adventure, reference), PackDiagnostics.Stage.COMPILATION, "REFERENCE_UNKNOWN",
                at(reference, "creation.json", "\"missing-kit\"", 0, "/definitions/0/data/startingItems/0/item"));

        // Cycle: two abilities require each other; the edge that closes the cycle is reported.
        var cycle = fixture(FANTASY);
        cycle.put("abilities.json", bytes("""
                {"schemaVersion": 1, "definitions": [
                  {"id": "steady", "kind": "ability", "data": {"name": "Catch Breath", "description": "Recover vigor.",
                    "prerequisites": {"abilities": ["brace"]}, "target": {"policy": "self"},
                    "effects": [{"type": "modifyResource", "resource": "vigor", "amount": 1}]}},
                  {"id": "brace", "kind": "ability", "data": {"name": "Brace", "description": "Hold firm.",
                    "prerequisites": {"abilities": ["steady"]}, "target": {"policy": "self"},
                    "effects": [{"type": "modifyResource", "resource": "vigor", "amount": 1}]}}
                ]}
                """));
        var cyclic = validate(adventure, cycle);
        expect(cyclic, PackDiagnostics.Stage.COMPILATION, "CYCLE",
                at(cycle, "abilities.json", "[\"steady\"]", 1, "/definitions/1/data/prerequisites/abilities/0"));
        assertTrue(cyclic.blocking().message().contains("steady -> brace -> steady"), cyclic.blocking().message());

        // Unknown effect: an effect type outside the closed vocabulary.
        var effect = fixture(FANTASY);
        effect.put("abilities.json", replace(effect, "abilities.json", "\"type\": \"modifyResource\"", "\"type\": \"teleport\""));
        expect(validate(adventure, effect), PackDiagnostics.Stage.COMPILATION, "SCHEMA",
                at(effect, "abilities.json", "\"teleport\"", 0, "/definitions/0/data/effects/0/type"));

        // Traversal declared in the manifest: located at the declaring field.
        var declared = fixture(FANTASY);
        declared.put("adventure.json", replace(declared, "adventure.json", "\"path\": \"lore.md\"",
                "\"path\": \"notes/../lore.md\""));
        expect(validate(adventure, declared), PackDiagnostics.Stage.PARSING, "PATH",
                at(declared, "adventure.json", "\"notes/../lore.md\"", 0, "/files/8/path"));

        // Traversal inside an archive: refused while staging, by sanitized entry name only.
        byte[] escape = zip(Map.of("../escape.json", bytes("{}")));
        var staged = diagnostics.validate(adventure, () -> STAGING.archive(new ByteArrayInputStream(escape)));
        expect(staged, PackDiagnostics.Stage.STAGING, "PATH", new SourceLocation("../escape.json", 1, 1, ""));
        assertNull(staged.standing());

        // Oversized aliases: an exponential alias document fails at its first anchor, before any expansion.
        var aliases = fixture(FANTASY);
        StringBuilder laughs = new StringBuilder("schemaVersion: 1\nlol0: &lol0 [lol, lol, lol, lol, lol, lol, lol, lol]\n");
        for (int level = 1; level < 10; level++) {
            laughs.append("lol").append(level).append(": &lol").append(level).append(" [")
                    .append(String.join(", ", java.util.Collections.nCopies(8, "*lol" + (level - 1)))).append("]\n");
        }
        laughs.append("definitions: []\n");
        aliases.remove("abilities.json");
        aliases.put("abilities.yaml", bytes(laughs.toString()));
        aliases.put("adventure.json", replace(aliases, "adventure.json", "\"path\": \"abilities.json\"",
                "\"path\": \"abilities.yaml\""));
        var aliased = validate(adventure, aliases);
        expect(aliased, PackDiagnostics.Stage.PARSING, "YAML_FEATURE",
                at(aliases, "abilities.yaml", "&lol0", 0, "/lol0"));
        assertEquals(2, aliased.blocking().location().line());

        assertEquals(0, (int) jdbc.queryForObject("select count(*) from content_revision", Integer.class));
        assertEquals(0, (int) jdbc.queryForObject("select count(*) from content_revision_file", Integer.class));
    }

    /**
     * A valid candidate returns the registration report; standing tracks identical, conflicting, newer
     * and older versions, and version inspection lists sealed revisions in precedence order.
     */
    @Test
    void reportsVersionStandingAndRegisteredVersions() throws Exception {
        UUID adventure = workspace();
        var original = fixture(FANTASY);
        var fresh = validate(adventure, original);
        assertEquals(PackDiagnostics.Status.VALID, fresh.status());
        assertEquals(PackDiagnostics.Relation.NEW_PACK, fresh.standing().relation());
        assertNull(fresh.standing().highestRegistered());
        assertEquals(List.of(), fresh.dependencies());
        assertEquals(List.of(), fresh.warnings());
        assertEquals(0, (int) jdbc.queryForObject("select count(*) from content_revision", Integer.class));

        var first = registry.register(adventure, STAGING.archive(new ByteArrayInputStream(zip(original))));
        assertEquals(JSON.readTree(fresh.report()), JSON.readTree(registry.report(first.revisionId()).orElseThrow()));
        var same = validate(adventure, original);
        assertEquals(PackDiagnostics.Status.VALID, same.status());
        assertEquals(PackDiagnostics.Relation.IDENTICAL, same.standing().relation());
        assertEquals(first.pack().contentHash(), same.standing().installedHash());

        // Changed prose under the same version: blocked, and located at the manifest version.
        var changed = fixture(FANTASY);
        changed.put("lore.md", bytes(new String(changed.get("lore.md"), StandardCharsets.UTF_8) + "\nA new trail note.\n"));
        var conflict = validate(adventure, changed);
        expect(conflict, PackDiagnostics.Stage.COMPILATION, "HASH_CONFLICT",
                at(changed, "adventure.json", "\"version\": \"1.0.0\"", 11, "/version"));
        assertEquals(PackDiagnostics.Relation.HASH_CONFLICT, conflict.standing().relation());
        assertEquals(first.pack().contentHash(), conflict.standing().installedHash());
        assertNotEquals(first.pack().contentHash(), conflict.standing().contentHash());

        changed.put("adventure.json", replace(changed, "adventure.json", "\"version\": \"1.0.0\"", "\"version\": \"1.1.0\""));
        var newer = validate(adventure, changed);
        assertEquals(PackDiagnostics.Status.VALID, newer.status());
        assertEquals(PackDiagnostics.Relation.NEW_VERSION, newer.standing().relation());
        assertEquals("1.0.0", newer.standing().highestRegistered());
        assertFalse(newer.standing().belowHighest());
        var second = registry.register(adventure, STAGING.archive(new ByteArrayInputStream(zip(changed))));

        changed.put("adventure.json", replace(changed, "adventure.json", "\"version\": \"1.1.0\"", "\"version\": \"0.9.0\""));
        var older = validate(adventure, changed);
        assertEquals(PackDiagnostics.Relation.NEW_VERSION, older.standing().relation());
        assertEquals("1.1.0", older.standing().highestRegistered());
        assertTrue(older.standing().belowHighest());

        var versions = diagnostics.versions(adventure, "sample:fantasy-exploration");
        assertEquals(List.of("1.0.0", "1.1.0"), versions.stream().map(PackDiagnostics.RegisteredVersion::version).toList());
        assertEquals(List.of(first.revisionId(), second.revisionId()),
                versions.stream().map(PackDiagnostics.RegisteredVersion::revisionId).toList());
        for (var version : versions) {
            assertTrue(version.sealed());
            assertEquals(2, version.reportVersion());
            assertTrue(version.rulesetAvailable());
            assertEquals(0, version.campaigns());
        }
        assertEquals(List.of(), diagnostics.versions(adventure, "sample:absent"));
        assertEquals(List.of(), diagnostics.versions(workspace(), "sample:fantasy-exploration"));
        assertEquals(2, (int) jdbc.queryForObject("select count(*) from content_revision", Integer.class));
    }

    /**
     * Declared dependencies are reported with their state even when compilation fails, and a sealed
     * one carries its own closure diagnosis.
     */
    @Test
    void reportsDependencyGraph() throws Exception {
        UUID adventure = workspace();
        var base = registry.register(adventure, STAGING.archive(new ByteArrayInputStream(zip(fixture(FANTASY)))));
        String hash = base.pack().contentHash();

        var sealed = dependent("sample:fantasy-exploration", hash);
        var valid = validate(adventure, sealed);
        assertEquals(PackDiagnostics.Status.VALID, valid.status(), String.valueOf(valid.blocking()));
        var edge = valid.dependencies().getFirst();
        assertEquals(PackDiagnostics.DependencyState.SEALED, edge.state());
        assertEquals(ClosureDiagnosis.AVAILABLE, edge.closure());
        assertEquals(at(sealed, "adventure.json", "[{\"id\"", 1, "/dependencies/0"), edge.location());

        String wrong = "0".repeat(64);
        var mismatch = dependent("sample:fantasy-exploration", wrong);
        var conflicting = validate(adventure, mismatch);
        expect(conflicting, PackDiagnostics.Stage.COMPILATION, "HASH_CONFLICT",
                at(mismatch, "adventure.json", "\"hash\": \"" + wrong, 8, "/dependencies/0/hash"));
        assertEquals(PackDiagnostics.DependencyState.HASH_MISMATCH, conflicting.dependencies().getFirst().state());
        assertEquals(hash, conflicting.dependencies().getFirst().installedHash());
        assertNull(conflicting.dependencies().getFirst().closure());

        var absent = dependent("sample:absent", hash);
        var missing = validate(adventure, absent);
        expect(missing, PackDiagnostics.Stage.COMPILATION, "DEPENDENCY",
                at(absent, "adventure.json", "[{\"id\"", 1, "/dependencies/0"));
        assertEquals(PackDiagnostics.DependencyState.MISSING, missing.dependencies().getFirst().state());
        assertNull(missing.dependencies().getFirst().installedHash());
        assertEquals(PackDiagnostics.Relation.NEW_PACK, missing.standing().relation());
        assertEquals(1, (int) jdbc.queryForObject("select count(*) from content_revision", Integer.class));
    }

    /** An unknown adventure is a caller error raised before the staging step runs. */
    @Test
    void rejectsUnknownAdventureBeforeStaging() {
        Supplier<PackStaging.StagedPack> never = () -> fail("staging must not run");
        assertThrows(IllegalArgumentException.class, () -> diagnostics.validate(UUID.randomUUID(), never));
    }

    /** The salvage fixture with one dependency declared after its module list. */
    private Map<String, byte[]> dependent(String packId, String hash) throws Exception {
        var files = fixture("science-fiction-salvage");
        files.put("adventure.json", replace(files, "adventure.json", "\"modules\": [],",
                "\"modules\": [],\n  \"dependencies\": [{\"id\": \"" + packId
                        + "\", \"version\": \"1.0.0\", \"hash\": \"" + hash + "\"}],"));
        return files;
    }

    /** Assert an invalid report: stage, code and the complete location. */
    private static void expect(PackDiagnostics.ValidationReport report, PackDiagnostics.Stage stage, String code,
            SourceLocation location) {
        assertEquals(PackDiagnostics.Status.INVALID, report.status(), String.valueOf(report.report()));
        assertEquals(code, report.blocking().code(), report.blocking().message());
        assertEquals(stage, report.stage());
        assertEquals(location, report.blocking().location());
        assertNull(report.report());
        assertFalse(report.blocking().message().startsWith(code), "the message is the explanation alone");
    }

    /**
     * Location of a unique needle in one file: one-based line, and one-based column of the
     * character {@code offset} places into the needle.
     */
    private static SourceLocation at(Map<String, byte[]> files, String file, String needle, int offset, String pointer) {
        String text = new String(files.get(file), StandardCharsets.UTF_8);
        int index = text.indexOf(needle);
        assertTrue(index >= 0 && text.indexOf(needle, index + 1) < 0, "needle must occur once: " + needle);
        index += offset;
        int line = 1;
        int start = 0;
        for (int position = 0; position < index; position++) {
            if (text.charAt(position) == '\n') {
                line++;
                start = position + 1;
            }
        }
        return new SourceLocation(file, line, index - start + 1, pointer);
    }

    /** Validate an inventory staged as a ZIP archive. */
    private PackDiagnostics.ValidationReport validate(UUID adventure, Map<String, byte[]> files) {
        byte[] archive = zip(files);
        return diagnostics.validate(adventure, () -> STAGING.archive(new ByteArrayInputStream(archive)));
    }

    /** Replace one required occurrence of text in a file of the inventory. */
    private static byte[] replace(Map<String, byte[]> files, String file, String from, String to) {
        String text = new String(files.get(file), StandardCharsets.UTF_8);
        assertTrue(text.contains(from), "mutation target missing: " + from);
        return bytes(text.replace(from, to));
    }

    /** UTF-8 bytes of a text. */
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Mutable copy of a bundled fixture's staged inventory; absence fails rather than skipping. */
    private Map<String, byte[]> fixture(String name) throws URISyntaxException {
        var resource = getClass().getResource("/packs/" + name + "/");
        assertNotNull(resource, "missing standalone fixture: " + name);
        return new TreeMap<>(STAGING.directory(Path.of(resource.toURI())).files());
    }

    /** Persist and return a fresh owner-scoped workspace; database failures propagate to the test. */
    private UUID workspace() {
        return new TransactionTemplate(manager).execute(status -> {
            Instant now = Instant.parse("2026-11-21T12:00:00Z");
            var owner = OwnerProfile.create("Fixture owner", now);
            entities.persist(owner);
            var workspace = AdventureWorkspace.create(owner.getId(), "Diagnostics check", "neutral", SupportedLocale.EN, now);
            entities.persist(workspace);
            return workspace.getId();
        });
    }

    /** Package an inventory as a portable ZIP in sorted path order. */
    private static byte[] zip(Map<String, byte[]> files) {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var file : new TreeMap<>(files).entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return bytes.toByteArray();
    }
}
