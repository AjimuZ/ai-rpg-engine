package org.rpgengine.content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Hostile-input corpus for the import boundary: portable paths, directory walks, ZIP structure
 * and media allowlists (docs/09 sections 9 and 10). Every case runs in memory or in a JUnit
 * temporary directory; nothing outside it is read or written.
 */
class PackStagingTest {
    /** Small budgets so limit cases need only kilobytes: 1 MiB archive, 4 MiB expanded, 10 files, 50:1, 2 MiB per file. */
    private static final PackStaging.Limits SMALL = new PackStaging.Limits(1 << 20, 4L << 20, 10, 50, 2 << 20);
    private static final PackStaging STAGING = new PackStaging(SMALL);

    /** Construct the stateless corpus. */
    PackStagingTest() {
    }

    /** Canonical portable names pass; traversal, host syntax, hidden, device and non-ASCII names fail. */
    @Test
    void classifiesPortablePaths() {
        for (String good : List.of("a.json", "lore/intro.md", "a_b-c.d/e", "assets/map.v2.png", "x".repeat(240))) {
            assertTrue(PackPaths.portable(good), good);
        }
        List<String> bad = new ArrayList<>(List.of("", "/abs.json", "a//b", "../x", "a/../b", "./a", ".hidden", "-x",
                "a\\b", "C:/x", "a:b", "con.md", "lore/AUX", "Lpt1.txt", "x.", "a/b/", "\u00e9.json", "a b", "x".repeat(241)));
        bad.add("a/".repeat(16) + "a");
        for (String path : bad) {
            assertFalse(PackPaths.portable(path), path);
        }
    }

    /** Case-folded duplicates and a file shadowing a directory are collisions; distinct names are not. */
    @Test
    void detectsCollisions() {
        assertEquals("map.json", PackPaths.collision(List.of("Map.json", "map.json"), List.of()));
        assertEquals("lore", PackPaths.collision(List.of("lore", "lore/a.md"), List.of()));
        assertEquals("lore/sub", PackPaths.collision(List.of("Lore"), List.of("lore/sub")));
        assertEquals("lore/b.md", PackPaths.collision(List.of("Lore/a.md", "lore/b.md"), List.of()));
        assertEquals("empty", PackPaths.collision(List.of("a.md"), List.of("Empty", "empty")));
        assertNull(PackPaths.collision(List.of("a.json", "lore/a.md", "lore/b.md"), List.of("lore", "empty")));
    }

    /** A real directory is walked into sorted logical paths with exact bytes. */
    @Test
    void stagesDirectory(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("lore/deep"));
        Files.writeString(root.resolve("adventure.json"), "{}");
        Files.write(root.resolve("lore/deep/a.md"), new byte[] {1, 2, 3});
        var staged = STAGING.directory(root);
        assertEquals(PackStaging.Source.DIRECTORY, staged.source());
        assertEquals(List.of("adventure.json", "lore/deep/a.md"), List.copyOf(staged.files().keySet()));
        assertArrayEquals(new byte[] {1, 2, 3}, staged.files().get("lore/deep/a.md"));
        assertEquals(5, staged.expandedBytes());
        assertThrows(UnsupportedOperationException.class, () -> staged.files().clear());
        staged.files().get("adventure.json")[0] = 0;
        assertEquals('{', staged.files().get("adventure.json")[0]);
    }

    /** Non-portable names, oversize files, too many files and a non-directory root are rejected. */
    @Test
    void rejectsBadDirectories(@TempDir Path root) throws IOException {
        Path names = Files.createDirectory(root.resolve("names"));
        Files.writeString(names.resolve("bad name.json"), "{}");
        assertEquals("PATH", code(() -> STAGING.directory(names)));
        Path big = Files.createDirectory(root.resolve("big"));
        Files.write(big.resolve("a.bin"), new byte[(2 << 20) + 1]);
        assertEquals("LIMIT", code(() -> STAGING.directory(big)));
        Path many = Files.createDirectory(root.resolve("many"));
        for (int index = 0; index < 11; index++) {
            Files.writeString(many.resolve("f" + index + ".json"), "{}");
        }
        assertEquals("LIMIT", code(() -> STAGING.directory(many)));
        assertEquals("SOURCE", code(() -> STAGING.directory(big.resolve("a.bin"))));
        assertEquals("SOURCE", code(() -> STAGING.directory(root.resolve("missing"))));
    }

    /** Symbolic links inside the pack and as the pack root are never followed. */
    @Test
    void rejectsLinks(@TempDir Path root) throws IOException {
        Path outside = Files.writeString(root.resolve("secret.txt"), "host data");
        Path pack = Files.createDirectory(root.resolve("pack"));
        Files.writeString(pack.resolve("adventure.json"), "{}");
        try {
            Files.createSymbolicLink(pack.resolve("leak.txt"), outside);
            Files.createSymbolicLink(root.resolve("alias"), pack);
        } catch (IOException | UnsupportedOperationException unsupported) {
            // Windows without developer mode cannot create links; the Linux CI image always can.
            Assumptions.abort("symbolic links are not available on this host");
        }
        assertEquals("LINK", code(() -> STAGING.directory(pack)));
        assertEquals("SOURCE", code(() -> STAGING.directory(root.resolve("alias"))));
    }

    /** A plain archive stages exactly; a single wrapping folder holding the manifest is removed. */
    @Test
    void stagesArchiveAndStripsWrapper() {
        var plain = STAGING.archive(input(zip(stored("adventure.json", "{}"), stored("lore/a.md", "x"))));
        assertEquals(PackStaging.Source.ARCHIVE, plain.source());
        assertEquals(List.of("adventure.json", "lore/a.md"), List.copyOf(plain.files().keySet()));
        var wrapped = STAGING.archive(input(zip(stored("pack/", ""), stored("pack/adventure.json", "{}"),
                stored("pack/lore/", ""), stored("pack/lore/a.md", "x"))));
        assertEquals(List.of("adventure.json", "lore/a.md"), List.copyOf(wrapped.files().keySet()));
        assertEquals("x", new String(wrapped.files().get("lore/a.md"), StandardCharsets.UTF_8));
        // Without a manifest directly inside the folder, nothing is stripped.
        var unwrapped = STAGING.archive(input(zip(stored("pack/lore/a.md", "x"))));
        assertEquals(List.of("pack/lore/a.md"), List.copyOf(unwrapped.files().keySet()));
    }

    /** Traversal, absolute, drive, backslash and colliding entry names never reach a path map. */
    @Test
    void rejectsHostileArchiveNames() {
        for (String name : List.of("../evil.json", "/etc/passwd", "C:/x.json", "a/../../x", "a\\b.json", "con.json", ".git/x")) {
            assertEquals("PATH", code(() -> STAGING.archive(input(zip(stored(name, "x"))))), name);
        }
        assertEquals("PATH", code(() -> STAGING.archive(input(zip(stored("Map.json", "1"), stored("map.json", "2"))))));
        assertEquals("PATH", code(() -> STAGING.archive(input(zip(stored("lore", "1"), stored("lore/a.md", "2"))))));
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(zip(stored("a.json", "1"), stored("a.json", "2"))))));
    }

    /** Encryption, links, unknown methods, stored size disagreement and directories with data are refused. */
    @Test
    void rejectsUnsafeEntryKinds() {
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(zip(stored("a.json", "x").flags(1))))));
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(zip(stored("a.json", "x").flags(0x40))))));
        // Unix "made by" host with type 0120000 is a symbolic link whose content is its target.
        assertEquals("LINK", code(() -> STAGING.archive(input(zip(stored("a.json", "/etc/passwd").unix(0120777))))));
        assertEquals("LINK", code(() -> STAGING.archive(input(zip(stored("a.json", "x").unix(0060644))))));
        assertNotNull(STAGING.archive(input(zip(stored("a.json", "x").unix(0100644)))));
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(zip(stored("a.json", "x").method(12))))));
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(zip(stored("a.json", "xyz").sizes(3, 2))))));
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(zip(stored("d/", "x"))))));
    }

    /** Structural tampering: overlap, CRC, local/central disagreement, ZIP64, truncation and junk. */
    @Test
    void rejectsTamperedStructure() {
        // The first entry claims to extend over the second entry's local header and data.
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(zip(stored("a.json", "x").sizes(1 + 30 + 6 + 1, 1 + 30 + 6 + 1),
                stored("b.json", "y"))))));
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(zip(stored("a.json", "x").crc(1))))));
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(zip(stored("a.json", "x").localName("b.json"))))));
        byte[] valid = zip(stored("a.json", "x"));
        // Both headers retain the correct CRC; only the payload changes, reaching the actual-byte check.
        byte[] changedPayload = valid.clone();
        changedPayload[30 + "a.json".length()] ^= 1;
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(changedPayload))));
        byte[] zip64 = valid.clone();
        int end = valid.length - 22;
        putShort(zip64, end + 8, 0xFFFF);
        putShort(zip64, end + 10, 0xFFFF);
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(zip64))));
        byte[] prepended = new byte[valid.length + 4];
        System.arraycopy(valid, 0, prepended, 4, valid.length);
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(prepended))));
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(java.util.Arrays.copyOf(valid, valid.length - 1)))));
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input("not a zip".getBytes(StandardCharsets.US_ASCII)))));
        assertEquals("LIMIT", code(() -> STAGING.archive(input(zip()))));
    }

    /** Deflate bombs and lying sizes are caught by ratio, declared-size and inflate-size checks. */
    @Test
    void rejectsBombsAndSizeLies() throws IOException {
        byte[] bomb = deflated("zeros.bin", new byte[1 << 20]);
        assertEquals("LIMIT", code(() -> STAGING.archive(input(bomb))));
        var relaxed = new PackStaging(new PackStaging.Limits(1 << 20, 4L << 20, 10, 100_000, 2 << 20));
        assertEquals(1 << 20, relaxed.archive(input(bomb)).files().get("zeros.bin").length);
        // Central directory understates the size: inflation must stop at the declared size and fail.
        byte[] understated = bomb.clone();
        putInt(understated, central(understated) + 24, 1000);
        // Keep the descriptor consistent to reach the actual inflate-size guard.
        putInt(understated, central(understated) - 4, 1000);
        assertEquals("LIMIT", code(() -> relaxed.archive(input(understated))));
        // Declared size above the per-file budget fails before any allocation.
        byte[] overstated = bomb.clone();
        putInt(overstated, central(overstated) + 24, (2 << 20) + 1);
        assertEquals("LIMIT", code(() -> relaxed.archive(input(overstated))));
        assertEquals("LIMIT", code(() -> new PackStaging(new PackStaging.Limits(64, 1 << 20, 10, 50, 1 << 20))
                .archive(input(zip(stored("a.json", "x".repeat(100)))))));
        List<Stored> eleven = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            eleven.add(stored("f" + index + ".json", "x"));
        }
        assertEquals("LIMIT", code(() -> STAGING.archive(input(zip(eleven.toArray(Stored[]::new))))));
    }

    /** All ZIP views must agree, including descriptors, directory streams and disk numbers. */
    @Test
    void rejectsAlternateArchiveViews() throws IOException {
        byte[] localSize = zip(stored("a.json", "x"));
        putInt(localSize, 22, 0);
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(localSize))));
        byte[] flags = zip(stored("a.json", "x"));
        putShort(flags, 6, 8);
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(flags))));
        byte[] disk = zip(stored("a.json", "x"));
        putShort(disk, central(disk) + 34, 1);
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(disk))));
        byte[] descriptor = deflated("a.json", new byte[] {1, 2, 3});
        putInt(descriptor, central(descriptor) - 12, 1);
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(descriptor))));
        // Matching descriptor/central CRCs must still be verified against inflated bytes.
        putInt(descriptor, central(descriptor) + 16, 1);
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(descriptor))));
        byte[] stream = deflated("a.json", new byte[] {1, 2, 3});
        int directoryOffset = central(stream);
        int descriptorOffset = directoryOffset - 16;
        int compressedSize = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN).getInt(directoryOffset + 20);
        // Insert one byte after the deflate terminator and consistently enlarge every compressed-size
        // field/offset. Structural agreement must not hide an incompletely consumed deflate stream.
        byte[] trailing = new byte[stream.length + 1];
        System.arraycopy(stream, 0, trailing, 0, descriptorOffset);
        System.arraycopy(stream, descriptorOffset, trailing, descriptorOffset + 1, stream.length - descriptorOffset);
        putInt(trailing, directoryOffset + 1 + 20, compressedSize + 1);
        putInt(trailing, directoryOffset + 1 - 8, compressedSize + 1);
        putInt(trailing, trailing.length - 6, directoryOffset + 1);
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(trailing))));
        // The classic descriptor signature is optional; stripping only those four bytes remains valid.
        byte[] unsigned = new byte[stream.length - 4];
        System.arraycopy(stream, 0, unsigned, 0, descriptorOffset);
        System.arraycopy(stream, descriptorOffset + 4, unsigned, descriptorOffset, stream.length - descriptorOffset - 4);
        putInt(unsigned, unsigned.length - 6, directoryOffset - 4);
        assertArrayEquals(new byte[] {1, 2, 3}, STAGING.archive(input(unsigned)).files().get("a.json"));
        byte[] directory = deflated("empty/", new byte[] {1});
        putInt(directory, central(directory) + 24, 0);
        putInt(directory, central(directory) - 4, 0);
        assertEquals("LIMIT", code(() -> STAGING.archive(input(directory))));
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(zip(stored("pack/adventure.json", "{}"),
                stored("unrelated/", ""))))));
        byte[] prefix = zip(stored("a.json", "x"));
        byte[] executable = new byte[prefix.length + 4];
        System.arraycopy(prefix, 0, executable, 4, prefix.length);
        putInt(executable, central(executable) + 42, 4);
        putInt(executable, executable.length - 6, central(executable));
        assertEquals("ARCHIVE", code(() -> STAGING.archive(input(executable))));
    }

    /** Allowlisted media pass with dimensions; wrong signatures, unknown types and huge images fail. */
    @Test
    void inspectsMedia() {
        var png = PackMedia.inspect("assets/map.png", png(640, 480));
        assertEquals(new PackMedia.Media("image/png", 640, 480), png);
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 4, 0, 0,
                (byte) 0xFF, (byte) 0xC0, 0, 11, 8, 0, 20, 0, 30, 1, 1, 0x11, 0};
        assertEquals(new PackMedia.Media("image/jpeg", 30, 20), PackMedia.inspect("a.JPG", jpeg));
        byte[] webp = new byte[30];
        ascii(webp, 0, "RIFF");
        ascii(webp, 8, "WEBP");
        ascii(webp, 12, "VP8X");
        webp[24] = 99;
        webp[27] = 49;
        assertEquals(new PackMedia.Media("image/webp", 100, 50), PackMedia.inspect("a.webp", webp));
        assertEquals("audio/ogg", PackMedia.inspect("a.ogg", "OggS....".getBytes(StandardCharsets.US_ASCII)).mediaType());
        assertEquals("audio/mpeg", PackMedia.inspect("a.mp3", "ID3....".getBytes(StandardCharsets.US_ASCII)).mediaType());
        assertEquals("audio/wav", PackMedia.inspect("a.wav", "RIFF....WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).mediaType());
        assertEquals("MEDIA_TYPE", code(() -> PackMedia.inspect("a.svg", "<svg/>".getBytes(StandardCharsets.US_ASCII))));
        assertEquals("MEDIA_TYPE", code(() -> PackMedia.inspect("a.exe", "MZ".getBytes(StandardCharsets.US_ASCII))));
        assertEquals("MEDIA_TYPE", code(() -> PackMedia.inspect("noext", png(1, 1))));
        assertEquals("MEDIA_TYPE", code(() -> PackMedia.inspect("a.jpg", png(1, 1))));
        assertEquals("MEDIA_TYPE", code(() -> PackMedia.inspect("a.png", "MZ\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".getBytes(StandardCharsets.US_ASCII))));
        assertEquals("MEDIA_TYPE", code(() -> PackMedia.inspect("a.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9})));
        assertEquals("MEDIA_LIMIT", code(() -> PackMedia.inspect("a.png", png(8193, 1))));
        assertEquals("MEDIA_LIMIT", code(() -> PackMedia.inspect("a.png", png(8192, 8192))));
        assertEquals("MEDIA_LIMIT", code(() -> PackMedia.inspect("a.png", png(0, 5))));
        assertEquals("MEDIA_LIMIT", code(() -> PackMedia.inspect("a.png", png(-1, 5))));
        byte[] huge = new byte[PackMedia.MAX_ASSET_BYTES + 1];
        ascii(huge, 0, "OggS");
        assertEquals("MEDIA_LIMIT", code(() -> PackMedia.inspect("a.ogg", huge)));
    }

    /** Minimal PNG prefix: signature plus an IHDR chunk header with the given dimensions. */
    private static byte[] png(int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN);
        buffer.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        buffer.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height);
        return buffer.array();
    }

    /** Run an action expected to fail and return its diagnostic code. */
    private static String code(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(PackException.class, action).code();
    }

    /** Wrap bytes as an input stream. */
    private static ByteArrayInputStream input(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    /** A real deflated single-entry archive from the JDK writer. */
    private static byte[] deflated(String name, byte[] content) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    /** Offset of the first central directory header. */
    private static int central(byte[] zip) {
        for (int offset = 0; offset + 4 <= zip.length; offset++) {
            if (ByteBuffer.wrap(zip, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == 0x02014b50) {
                return offset;
            }
        }
        throw new AssertionError("no central directory");
    }

    /** Begin describing one stored entry with consistent defaults. */
    private static Stored stored(String name, String content) {
        return new Stored(name, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One hand-written stored entry; each setter introduces a single deliberate inconsistency so a
     * test exercises exactly one parser rule.
     */
    private static final class Stored {
        private final String name;
        private final byte[] data;
        private String localName;
        private int flags;
        private int method;
        private int madeBy = 20;
        private long external;
        private Long crc;
        private long compressed;
        private long size;

        /** @param name central and local name @param data stored bytes */
        Stored(String name, byte[] data) {
            this.name = name;
            this.localName = name;
            this.data = data;
            this.compressed = data.length;
            this.size = data.length;
        }

        /** Set general-purpose flags in both headers. */
        Stored flags(int value) {
            flags = value;
            return this;
        }

        /** Set the compression method in both headers without changing the data. */
        Stored method(int value) {
            method = value;
            return this;
        }

        /** Mark the entry as created on Unix with the given st_mode. */
        Stored unix(int mode) {
            madeBy = (3 << 8) | 20;
            external = (long) mode << 16;
            return this;
        }

        /** Override the central CRC. */
        Stored crc(long value) {
            crc = value;
            return this;
        }

        /** Override the central compressed and uncompressed sizes. */
        Stored sizes(long compressedSize, long uncompressedSize) {
            compressed = compressedSize;
            size = uncompressedSize;
            return this;
        }

        /** Use a different name in the local header only. */
        Stored localName(String value) {
            localName = value;
            return this;
        }
    }

    /** Write stored entries as a classic single-disk ZIP with exact local/central/EOCD layout. */
    private static byte[] zip(Stored... entries) {
        var output = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        for (Stored entry : entries) {
            offsets.add(output.size());
            byte[] name = entry.localName.getBytes(StandardCharsets.ISO_8859_1);
            ByteBuffer local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            local.putInt(0x04034b50).putShort((short) 20).putShort((short) entry.flags).putShort((short) entry.method)
                    .putInt(0).putInt((int) crc(entry.data)).putInt(entry.data.length).putInt(entry.data.length)
                    .putShort((short) name.length).putShort((short) 0);
            output.writeBytes(local.array());
            output.writeBytes(name);
            output.writeBytes(entry.data);
        }
        int directory = output.size();
        for (int index = 0; index < entries.length; index++) {
            Stored entry = entries[index];
            byte[] name = entry.name.getBytes(StandardCharsets.ISO_8859_1);
            ByteBuffer central = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
            central.putInt(0x02014b50).putShort((short) entry.madeBy).putShort((short) 20).putShort((short) entry.flags)
                    .putShort((short) entry.method).putInt(0)
                    .putInt((int) (entry.crc == null ? crc(entry.data) : entry.crc))
                    .putInt((int) entry.compressed).putInt((int) entry.size).putShort((short) name.length)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                    .putInt((int) entry.external).putInt(offsets.get(index));
            output.writeBytes(central.array());
            output.writeBytes(name);
        }
        int size = output.size() - directory;
        ByteBuffer end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) entries.length)
                .putShort((short) entries.length).putInt(size).putInt(directory).putShort((short) 0);
        output.writeBytes(end.array());
        return output.toByteArray();
    }

    /** CRC-32 of bytes. */
    private static long crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /** Write a little-endian 16-bit value. */
    private static void putShort(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, (short) value);
    }

    /** Write a little-endian 32-bit value. */
    private static void putInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    }

    /** Copy ASCII text into a byte array. */
    private static void ascii(byte[] bytes, int offset, String text) {
        byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, bytes, offset, encoded.length);
    }
}
