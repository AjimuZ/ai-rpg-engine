package org.rpgengine.content;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/** Real sealed imports must produce separate private folders; missing database or failed I/O fails the test. */
@SpringBootTest
class WorkspaceProjectionTest {
    private static final Path ROOT = Path.of("target", "workspaces-" + UUID.randomUUID()).toAbsolutePath();
    @Autowired private PackRegistry registry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkspaceProjection projection;

    /** Construct the injected fixture; dependencies are supplied by the test application. */
    WorkspaceProjectionTest() { }

    /** Enable the optional store only for this disposable test context, never for another suite. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("engine.workspaces.root", ROOT::toString);
    }

    /** Keep disposable database fixtures independent; truncation is restricted to this test database. */
    @BeforeEach
    @AfterEach
    void clear() { jdbc.execute("truncate identity_owner_profile cascade"); }

    /** Equal titles and source paths must remain under distinct opaque adventure and revision identities. */
    @Test
    void separatesSameNameSpecificationsAndArtwork() throws Exception {
        UUID first = adventure();
        UUID second = adventure();
        var a = registry.register(first, pack("fantasy-exploration"));
        var b = registry.register(second, pack("science-fiction-salvage"));
        Path left = revision(first, a.revisionId());
        Path right = revision(second, b.revisionId());
        assertTrue(Files.isRegularFile(left.resolve("specification/lore.md")));
        assertTrue(Files.isRegularFile(right.resolve("specification/lore.md")));
        assertNotEquals(Files.readString(left.resolve("specification/lore.md")),
                Files.readString(right.resolve("specification/lore.md")));
        assertArrayEquals(Files.readAllBytes(Path.of("src/test/resources/packs/fantasy-exploration/assets/map.png")),
                Files.readAllBytes(left.resolve("assets/authored/assets/map.png")));
        assertArrayEquals(Files.readAllBytes(Path.of("src/test/resources/packs/science-fiction-salvage/assets/map.png")),
                Files.readAllBytes(right.resolve("assets/authored/assets/map.png")));
        assertTrue(Files.isRegularFile(left.resolve("integrity.json")));
        assertTrue(Files.isRegularFile(right.resolve("integrity.json")));
    }

    /** Idempotent import verifies rather than rewrites existing files; renaming a title never moves a folder. */
    @Test
    void retryPreservesFilesAndTitleChangesDoNotMoveThem() throws Exception {
        UUID adventure = adventure();
        var registered = registry.register(adventure, pack("fantasy-exploration"));
        Path lore = revision(adventure, registered.revisionId()).resolve("specification/lore.md");
        var time = Files.getLastModifiedTime(lore);
        jdbc.update("update adventure_workspace set title='Renamed', row_version=row_version+1 where id=?", adventure);
        assertFalse(registry.register(adventure, pack("fantasy-exploration")).created());
        assertEquals(time, Files.getLastModifiedTime(lore));
        assertEquals(1, jdbc.queryForObject("select count(*) from content_revision", Integer.class));
    }

    /** A failed post-commit projection leaves canonical originals intact and refuses to overwrite modified private files. */
    @Test
    void corruptionFailsClosedWithoutChangingRegisteredBytes() throws Exception {
        UUID adventure = adventure();
        var registered = registry.register(adventure, pack("fantasy-exploration"));
        Path lore = revision(adventure, registered.revisionId()).resolve("specification/lore.md");
        Files.writeString(lore, "tampered");
        assertThrows(UncheckedIOException.class, () -> registry.register(adventure, pack("fantasy-exploration")));
        assertEquals("tampered", Files.readString(lore));
        assertArrayEquals(Files.readAllBytes(Path.of("src/test/resources/packs/fantasy-exploration/lore.md")),
                registry.files(registered.revisionId()).orElseThrow().get("lore.md"));
        assertThrows(UncheckedIOException.class, projection::startup);
    }

    /** A foreign adventure/revision pair cannot create files or copy another world's private content. */
    @Test
    void mismatchedRevisionScopeWritesNothing() {
        UUID first = adventure();
        UUID second = adventure();
        var registered = registry.register(first, pack("fantasy-exploration"));
        assertThrows(IllegalArgumentException.class, () -> projection.registered(
                new PackRegistry.RegisteredRevision(second, registered.revisionId())));
        assertFalse(Files.exists(ROOT.resolve("adventures/" + second)));
    }

    /** Storage failure after the first commit cannot lose admission; an identical retry repairs only the missing projection. */
    @Test
    void failedPublicationRetainsCommitAndRetryReconciles() throws Exception {
        UUID adventure = adventure();
        Files.createDirectories(ROOT.resolve("adventures"));
        Path blocker = Files.writeString(ROOT.resolve("adventures/" + adventure), "blocked");
        assertThrows(UncheckedIOException.class, () -> registry.register(adventure, pack("fantasy-exploration")));
        UUID revision = jdbc.queryForObject("select id from content_revision where adventure_id=?", UUID.class, adventure);
        assertTrue(registry.files(revision).isPresent());
        assertEquals("blocked", Files.readString(blocker));
        Files.delete(blocker);
        var retry = registry.register(adventure, pack("fantasy-exploration"));
        assertFalse(retry.created());
        assertEquals(revision, retry.revisionId());
        assertTrue(Files.isRegularFile(revision(adventure, revision).resolve("integrity.json")));
        assertEquals(1, jdbc.queryForObject("select count(*) from content_revision", Integer.class));
    }

    /** A fresh process with an empty root reconstructs both worlds solely from persisted sealed originals. */
    @Test
    void separateJvmRebuildsMissingFolders() throws Exception {
        UUID first = adventure();
        UUID second = adventure();
        var a = registry.register(first, pack("fantasy-exploration"));
        var b = registry.register(second, pack("science-fiction-salvage"));
        Path recovered = ROOT.resolve("recovered");
        Path log = Path.of("target", "p065-workspace-restart.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "org.rpgengine.EngineApplication",
                "--spring.main.web-application-type=none", "--engine.workspaces.root=" + recovered,
                "--spring.main.banner-mode=off", "--logging.level.root=WARN")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "restart did not exit within its bound");
            assertEquals(0, child.exitValue(), () -> "restart failed; inspect " + log);
            for (var pair : java.util.List.of(java.util.Map.entry(first, a), java.util.Map.entry(second, b))) {
                Path original = revision(pair.getKey(), pair.getValue().revisionId());
                Path rebuilt = recovered.resolve(ROOT.relativize(original));
                assertArrayEquals(Files.readAllBytes(original.resolve("integrity.json")),
                        Files.readAllBytes(rebuilt.resolve("integrity.json")));
                assertArrayEquals(Files.readAllBytes(original.resolve("specification/lore.md")),
                        Files.readAllBytes(rebuilt.resolve("specification/lore.md")));
                assertArrayEquals(Files.readAllBytes(original.resolve("assets/authored/assets/map.png")),
                        Files.readAllBytes(rebuilt.resolve("assets/authored/assets/map.png")));
            }
        } finally {
            // Only this fixture's process may be stopped; never leave a test server running.
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(10, TimeUnit.SECONDS)) { child.destroyForcibly().waitFor(10, TimeUnit.SECONDS); }
            }
        }
    }

    /** Commit an owner and same-title empty workspace with random identities; SQL failure aborts setup. */
    private UUID adventure() {
        UUID owner = UUID.randomUUID();
        UUID adventure = UUID.randomUUID();
        jdbc.update("insert into identity_owner_profile(id,display_name,ui_locale,created_at,updated_at) "
                + "values (?, 'Owner', 'en', now(), now())", owner);
        jdbc.update("insert into adventure_workspace(id,owner_id,title,genre,source_locale,created_at,updated_at) "
                + "values (?, ?, 'Same world', 'neutral', 'en', now(), now())", adventure, owner);
        return adventure;
    }

    /** Stage a known genre through the real admission boundary; invalid fixtures fail before registration. */
    private static PackStaging.StagedPack pack(String genre) {
        return new PackStaging(PackStaging.Limits.standard()).directory(Path.of("src/test/resources/packs", genre));
    }

    /** Derive the documented private projection path from UUIDs, with no title-based lookup. */
    private static Path revision(UUID adventure, UUID revision) {
        return ROOT.resolve("adventures/" + adventure + "/content-revisions/" + revision);
    }
}
