package org.rpgengine.content;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile the manual authoring guide's example pack through the real parser and compiler, with no database.
 * Inputs are the classpath directory {@code packs/manual-authoring-example} and in-memory mutations of it
 * that reproduce the guide's troubleshooting rows; a stale example or a changed diagnostic code fails here.
 * This proves the files compile, not that the pack is playable or registers into a workspace.
 */
class ManualAuthoringExampleTest {
    private static final PackStaging STAGING = new PackStaging(PackStaging.Limits.standard());

    /** Construct the example corpus; no resources are acquired. */
    ManualAuthoringExampleTest() {
    }

    /** The example compiles without warnings, and its definitions are the ones the guide describes. */
    @Test
    void exampleCompilesWithoutWarnings() throws Exception {
        var pack = new PackCompiler().compile(new PackParser().parse(files()));
        assertEquals(java.util.List.of(), pack.warnings());
        assertEquals("example:harbor-lamp", pack.parsed().manifest().values().get("id"));
        assertEquals("location", pack.byId().get("quay").kind());
        assertEquals("location", pack.byId().get("lamp-house").kind());
        assertEquals("item", pack.byId().get("satchel").kind());
        assertEquals("creation", pack.byId().get("walker").kind());
    }

    /** A starting item that names no definition fails at its field, as the guide's troubleshooting row says. */
    @Test
    void unknownReferenceIsLocatedAtItsField() throws Exception {
        Map<String, byte[]> changed = new TreeMap<>(files());
        String creation = new String(changed.get("creation.json"), StandardCharsets.UTF_8);
        changed.put("creation.json", creation.replace("\"satchel\"", "\"satchle\"").getBytes(StandardCharsets.UTF_8));
        var error = assertThrows(PackException.class, () -> new PackCompiler().compile(new PackParser().parse(changed)));
        assertEquals("REFERENCE_UNKNOWN", error.code());
        assertEquals("creation.json", error.location().file());
        assertTrue(error.location().pointer().contains("startingItems"));
    }

    /** A file left out of the manifest inventory, and a listed file that is missing, are both FILE_REFERENCE. */
    @Test
    void inventoryMismatchesAreFileReferenceErrors() throws Exception {
        Map<String, byte[]> extra = new TreeMap<>(files());
        extra.put("notes.md", "scratch".getBytes(StandardCharsets.UTF_8));
        assertEquals("FILE_REFERENCE", assertThrows(PackException.class,
                () -> new PackParser().parse(extra)).code());

        Map<String, byte[]> missing = new TreeMap<>(files());
        missing.remove("lore.md");
        assertEquals("FILE_REFERENCE", assertThrows(PackException.class,
                () -> new PackParser().parse(missing)).code());
    }

    /** A changed byte changes the content hash, which is why the guide requires a version bump. */
    @Test
    void anyChangedByteChangesTheContentHash() throws Exception {
        Map<String, byte[]> changed = new TreeMap<>(files());
        String lore = new String(changed.get("lore.md"), StandardCharsets.UTF_8);
        changed.put("lore.md", (lore + "\n").getBytes(StandardCharsets.UTF_8));
        assertNotEquals(PackHashes.calculate(files()).contentHash(), PackHashes.calculate(changed).contentHash());
    }

    /** Read the example directory exactly as an author's folder would be staged. */
    private Map<String, byte[]> files() throws URISyntaxException {
        var resource = getClass().getResource("/packs/manual-authoring-example/");
        assertNotNull(resource, "missing guide example");
        return STAGING.directory(Path.of(resource.toURI())).files();
    }
}
