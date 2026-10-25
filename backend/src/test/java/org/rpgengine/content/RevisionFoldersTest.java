package org.rpgengine.content;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise real filesystem failure boundaries without a database; unsupported native link tests are Linux-only. */
class RevisionFoldersTest {
    @TempDir Path temporary;
    private final UUID adventure = UUID.randomUUID();
    private final UUID revision = UUID.randomUUID();
    private final Map<String, byte[]> files = Map.of("specification/lore.md", "secret".getBytes(StandardCharsets.UTF_8),
            "assets/authored/map.png", new byte[] {1, 2, 3}, "integrity.json", new byte[] {4, 5});

    /** Construct independent random identities; the test framework owns temporary directory cleanup. */
    RevisionFoldersTest() { }

    /** Reject relative, volume-root and noncanonical roots before any filesystem operation. */
    @Test
    void rejectsUnsafeRoots() {
        assertThrows(IllegalArgumentException.class, () -> new RevisionFolders(Path.of("relative")));
        assertThrows(IllegalArgumentException.class, () -> new RevisionFolders(temporary.getRoot()));
        assertThrows(IllegalArgumentException.class, () -> new RevisionFolders(temporary.resolve("a/../b")));
    }

    /** Host path syntax, traversal and device names must fail before even creating the managed root. */
    @Test
    void rejectsUntrustedPathsBeforeWriting() {
        for (String path : new String[] {"../outside", "/absolute", "C:/escape", "a\\b", "a//b", "aux.txt"}) {
            assertThrows(IOException.class, () -> store().publish(adventure, revision, Map.of(path, new byte[] {1})));
            assertFalse(Files.exists(temporary.resolve("managed")));
        }
    }

    /** Complete publication contains all expected files, and an identical retry is nondestructive. */
    @Test
    void publishesAndVerifiesExactInventory() throws Exception {
        store().publish(adventure, revision, files);
        var timestamp = Files.getLastModifiedTime(target().resolve("specification/lore.md"));
        store().publish(adventure, revision, files);
        assertEquals(timestamp, Files.getLastModifiedTime(target().resolve("specification/lore.md")));
        try (var children = Files.list(target().getParent())) {
            assertEquals(1, children.count(), "successful publication leaves no staging folder");
        }
    }

    /** Extra directories, files, modified bytes and missing files are each refused without repair. */
    @Test
    void rejectsEveryInventoryMismatch() throws Exception {
        store().publish(adventure, revision, files);
        Path extra = Files.createDirectory(target().resolve("extra"));
        assertThrows(IOException.class, () -> store().publish(adventure, revision, files));
        Files.delete(extra);
        Path rogue = Files.writeString(target().resolve("rogue"), "private");
        assertThrows(IOException.class, () -> store().publish(adventure, revision, files));
        Files.delete(rogue);
        Path lore = target().resolve("specification/lore.md");
        Files.writeString(lore, "SECRET");
        assertThrows(IOException.class, () -> store().publish(adventure, revision, files));
        assertEquals("SECRET", Files.readString(lore));
        Files.delete(lore);
        assertThrows(IOException.class, () -> store().publish(adventure, revision, files));
        assertFalse(Files.exists(lore));
    }

    /** A static link anywhere in the managed ancestry cannot redirect a write outside the configured root. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsLinkedRootAncestor() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(temporary.resolve("managed"), outside);
        assertThrows(IOException.class, () -> store().publish(adventure, revision, files));
        try (var children = Files.list(outside)) { assertEquals(0, children.count()); }
    }

    /** A linked file or directory in a published revision is refused without reading/replacing its target. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsLinkedFilesAndDirectories() throws Exception {
        store().publish(adventure, revision, files);
        Path lore = target().resolve("specification/lore.md");
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "secret");
        Files.delete(lore);
        Files.createSymbolicLink(lore, outside);
        assertThrows(IOException.class, () -> store().publish(adventure, revision, files));
        assertEquals("secret", Files.readString(outside));
        Files.delete(lore);
        Files.delete(lore.getParent());
        Files.createSymbolicLink(lore.getParent(), temporary);
        assertThrows(IOException.class, () -> store().publish(adventure, revision, files));
    }

    /** Interrupted nonrevision staging folders cannot be mistaken for accepted content or block a clean retry. */
    @Test
    void abandonedStagingIsNeverAcceptedOrDeleted() throws Exception {
        Path abandoned = target().getParent().resolve(".building-abandoned");
        Files.createDirectories(abandoned);
        Files.writeString(abandoned.resolve("private"), "incomplete");
        store().publish(adventure, revision, files);
        assertEquals("incomplete", Files.readString(abandoned.resolve("private")));
        assertArrayEquals(files.get("specification/lore.md"), Files.readAllBytes(target().resolve("specification/lore.md")));
    }

    /** Create a writer for this test's private path; no disk access occurs at construction. */
    private RevisionFolders store() { return new RevisionFolders(temporary.resolve("managed")); }

    /** Resolve the documented destination using only the test's opaque IDs. */
    private Path target() {
        return temporary.resolve("managed/adventures/" + adventure + "/content-revisions/" + revision);
    }
}
