package org.rpgengine.content;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The protected boundary between untrusted pack sources and the in-memory parser (docs/09 sections 3.1 and 9).
 *
 * <p>Staging turns a local directory or a ZIP archive into a bounded logical inventory of portable
 * relative paths and bytes. Nothing is ever extracted to disk and no logical path is ever resolved
 * against a host directory, so traversal, absolute names, drive letters and alternate streams cannot
 * escape: they are rejected lexically by {@link PackPaths}. Symbolic links, Windows junctions and
 * special files are refused rather than followed. Every size is budgeted before allocation and
 * verified again while reading or inflating, so a lying header or a decompression bomb fails with
 * {@code LIMIT} or {@code ARCHIVE} instead of exhausting memory.
 *
 * <p>Diagnostics carry sanitized logical names only, never host paths or exception messages that
 * might reveal the installation layout.
 */
public final class PackStaging {
    // ZIP record signatures (APPNOTE 6.3.x), little-endian.
    private static final int LOCAL = 0x04034b50;
    private static final int CENTRAL = 0x02014b50;
    private static final int END = 0x06054b50;
    private static final int ZIP64_LOCATOR = 0x07064b50;
    private static final int UNIX_HOST = 3;
    private static final int UNIX_TYPE_MASK = 0170000;
    private static final int UNIX_REGULAR = 0100000;
    private static final int UNIX_DIRECTORY = 0040000;
    private final Limits limits;

    /**
     * Create a stager with explicit budgets.
     *
     * @param limits product limits; use {@link Limits#standard()} outside tests
     */
    public PackStaging(Limits limits) {
        this.limits = limits;
    }

    /**
     * Stage a local pack directory without following links.
     *
     * <p>Limitation: links are detected per entry with {@code NOFOLLOW_LINKS}, but a concurrent
     * process that swaps an already inspected intermediate directory for a link between inspection
     * and open (a time-of-check race) is not fully excluded by the JDK file API. Directory import is
     * an owner-local operation on a folder the owner controls; untrusted packs should arrive as ZIP
     * archives, which never touch the host filesystem.
     *
     * @param root directory containing {@code adventure.yaml} or {@code adventure.json}
     * @return immutable staged inventory
     * @throws PackException {@code SOURCE}, {@code LINK}, {@code PATH} or {@code LIMIT}
     */
    public StagedPack directory(Path root) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // isOther covers Windows junctions and other reparse points, which also report isDirectory.
            if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
                throw error("SOURCE", "_", "Pack source must be a real directory, not a link or file");
            }
            Map<String, byte[]> files = new TreeMap<>();
            Budget budget = new Budget();
            walk(root, "", 0, files, budget);
            return finish(Source.DIRECTORY, files, budget.directories, budget.expanded, 0);
        } catch (IOException exception) {
            // The exception text may contain host paths, so only a stable code is reported.
            throw error("SOURCE", "_", "Pack directory could not be read");
        }
    }

    /** Visit one directory level in sorted order, enforcing entry, depth, size and link rules. */
    private void walk(Path directory, String prefix, int depth, Map<String, byte[]> files, Budget budget) throws IOException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                // Bound directory entries too, so a tree of empty folders cannot make the walk unbounded.
                if (++budget.entries > 2L * limits.files()) {
                    throw error("LIMIT", prefix.isEmpty() ? "_" : prefix, "Pack directory has too many entries");
                }
                children.add(child);
            }
        }
        children.sort(null);
        for (Path child : children) {
            String name = child.getFileName().toString();
            String logical = prefix.isEmpty() ? name : prefix + "/" + name;
            if (!PackPaths.portable(logical)) {
                throw error("PATH", PackPaths.sanitize(logical), "Expected a canonical portable relative path");
            }
            BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw error("LINK", logical, "Links, junctions and special files are forbidden");
            }
            if (attributes.isDirectory()) {
                budget.directories.add(logical);
                if (depth + 1 >= PackPaths.MAX_DEPTH) {
                    throw error("PATH", logical, "Directory nesting is too deep");
                }
                walk(child, logical, depth + 1, files, budget);
            } else if (attributes.isRegularFile()) {
                if (files.size() >= limits.files()) {
                    throw error("LIMIT", logical, "Pack exceeds the file count limit");
                }
                files.put(logical, read(child, logical, budget));
            } else {
                throw error("LINK", logical, "Unsupported file type");
            }
        }
    }

    /** Read one regular file without following a final link, never buffering more than the budget allows. */
    private byte[] read(Path file, String logical, Budget budget) throws IOException {
        long allowed = Math.min(limits.fileBytes(), limits.expandedBytes() - budget.expanded);
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            // Reading one byte past the budget distinguishes "exactly at the limit" from "over it"
            // without trusting the size reported by attributes, which can change during the read.
            byte[] bytes = input.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, allowed + 1));
            if (bytes.length > allowed) {
                throw error("LIMIT", logical, "File exceeds the per-file or expanded size limit");
            }
            budget.expanded += bytes.length;
            return bytes;
        }
    }

    /**
     * Stage a ZIP archive held entirely in memory; nothing is written to disk.
     *
     * <p>Only the stored and deflate methods are accepted. Encrypted, multi-disk, ZIP64,
     * duplicated, overlapping or link entries are refused, and every entry is inflated against its
     * central-directory size and CRC-32 so the local and central views cannot disagree. A single
     * top-level folder wrapping the manifest (as produced by "compress folder" tools) is stripped.
     *
     * @param input archive bytes; the caller owns and closes the stream
     * @return immutable staged inventory
     * @throws PackException {@code ARCHIVE}, {@code LINK}, {@code PATH}, {@code LIMIT} or {@code SOURCE}
     */
    public StagedPack archive(InputStream input) {
        byte[] data;
        try {
            data = input.readNBytes(limits.archiveBytes() + 1);
        } catch (IOException exception) {
            throw error("SOURCE", "_", "Pack archive could not be read");
        }
        if (data.length > limits.archiveBytes()) {
            throw error("LIMIT", "_", "Archive exceeds the compressed size limit");
        }
        int end = endOfCentralDirectory(data);
        int entries = u16(data, end + 10);
        long directorySize = u32(data, end + 12);
        long directoryOffset = u32(data, end + 16);
        // Single-disk archives only; equal counts and an exact directory placement leave no room for
        // prepended payloads or a second, hidden central directory.
        if (u16(data, end + 4) != 0 || u16(data, end + 6) != 0 || u16(data, end + 8) != entries
                || directoryOffset + directorySize != end) {
            throw error("ARCHIVE", "_", "Multi-disk or inconsistent central directory");
        }
        if (entries == 0xFFFF || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL
                || (end >= 20 && u32(data, end - 20) == ZIP64_LOCATOR)) {
            throw error("ARCHIVE", "_", "ZIP64 archives are not supported");
        }
        if (entries == 0 || entries > 2L * limits.files()) {
            throw error("LIMIT", "_", "Archive entry count is outside the supported range");
        }
        List<Entry> parsed = centralDirectory(data, (int) directoryOffset, end, entries);
        long declared = parsed.stream().mapToLong(Entry::size).sum();
        // Refuse amplification before allocating any expanded buffers, including empty directory data.
        if (declared > (long) limits.ratio() * data.length) {
            throw error("LIMIT", "_", "Archive expansion ratio exceeds the limit");
        }
        Map<String, byte[]> files = new TreeMap<>();
        List<String> directories = new ArrayList<>();
        long expanded = 0;
        for (Entry entry : parsed) {
            byte[] bytes = inflate(data, entry);
            if (entry.directory) {
                directories.add(entry.name);
                continue;
            }
            if (files.size() >= limits.files()) {
                throw error("LIMIT", entry.name, "Archive exceeds the file count limit");
            }
            files.put(entry.name, bytes);
            expanded += entry.size;
        }
        // The ratio bounds total amplification even when every individual entry stays within budget.
        if (expanded > (long) limits.ratio() * data.length) {
            throw error("LIMIT", "_", "Archive expansion ratio exceeds the limit");
        }
        String wrapper = wrapper(files);
        return finish(Source.ARCHIVE, strip(files, wrapper), strip(directories, wrapper), expanded, data.length);
    }

    /** Locate the end-of-central-directory record whose comment length exactly reaches the end of input. */
    private static int endOfCentralDirectory(byte[] data) {
        // The record is 22 bytes plus a comment of at most 65,535 bytes, so the search window is bounded.
        for (int offset = data.length - 22; offset >= 0 && offset >= data.length - 22 - 0xFFFF; offset--) {
            if (u32(data, offset) == END && offset + 22 + u16(data, offset + 20) == data.length) {
                return offset;
            }
        }
        throw error("ARCHIVE", "_", "Input is not a ZIP archive");
    }

    /** Parse and pre-validate every central header and its local header before any byte is inflated. */
    private List<Entry> centralDirectory(byte[] data, int offset, int limit, int count) {
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long declared = 0;
        int position = offset;
        for (int index = 0; index < count; index++) {
            if (position + 46 > limit || u32(data, position) != CENTRAL) {
                throw error("ARCHIVE", "_", "Corrupt central directory");
            }
            int flags = u16(data, position + 8);
            int method = u16(data, position + 10);
            long crc = u32(data, position + 16);
            long compressed = u32(data, position + 20);
            long size = u32(data, position + 24);
            int nameLength = u16(data, position + 28);
            int next = position + 46 + nameLength + u16(data, position + 30) + u16(data, position + 32);
            long local = u32(data, position + 42);
            if (next > limit || nameLength == 0) {
                throw error("ARCHIVE", "_", "Corrupt central directory");
            }
            String raw = ascii(data, position + 46, nameLength);
            String safe = PackPaths.sanitize(raw);
            if ((flags & ~(0x0800 | 0x0008 | 0x0006)) != 0 || (method == 0 && (flags & 6) != 0)) {
                throw error("ARCHIVE", safe, "Encrypted or unsupported entry flags");
            }
            if (method != 0 && method != 8) {
                throw error("ARCHIVE", safe, "Only stored and deflate entries are supported");
            }
            if (compressed == 0xFFFFFFFFL || size == 0xFFFFFFFFL || local == 0xFFFFFFFFL) {
                throw error("ARCHIVE", safe, "ZIP64 entries are not supported");
            }
            boolean directory = raw.endsWith("/");
            // Unix hosts store the file type in the high external attribute bits; anything other than
            // a regular file or directory (notably 0120000, a symbolic link) must not be materialized.
            int host = u16(data, position + 4) >>> 8;
            if (host == UNIX_HOST || host == 19) {
                int type = (int) (u32(data, position + 38) >>> 16) & UNIX_TYPE_MASK;
                if (type != 0 && type != (directory ? UNIX_DIRECTORY : UNIX_REGULAR)) {
                    throw error("LINK", safe, "Links and special entries are forbidden");
                }
            }
            if (u16(data, position + 34) != 0) {
                throw error("ARCHIVE", safe, "Multi-disk entries are not supported");
            }
            String name = directory ? raw.substring(0, raw.length() - 1) : raw;
            if (!PackPaths.portable(name)) {
                throw error("PATH", safe, "Expected a canonical portable relative path");
            }
            if (!names.add(raw)) {
                throw error("ARCHIVE", name, "Duplicate archive entry");
            }
            if (directory && size != 0) {
                throw error("ARCHIVE", name, "Directory entries must be empty");
            }
            // Declared sizes are checked before allocation; inflation re-verifies the real output.
            if (size > limits.fileBytes() || (declared += size) > limits.expandedBytes()) {
                throw error("LIMIT", name, "Entry exceeds the per-file or expanded size limit");
            }
            if (method == 0 && compressed != size) {
                throw error("ARCHIVE", name, "Stored entry sizes disagree");
            }
            int start = localData(data, (int) Math.min(local, Integer.MAX_VALUE), raw, flags, method, offset, name);
            if (start + compressed > offset) {
                throw error("ARCHIVE", name, "Entry data overlaps the central directory");
            }
            int end = entryEnd(data, (int) local, start + (int) compressed, flags, crc, compressed, size, offset, name);
            entries.add(new Entry(name, directory, method, crc, (int) size, (int) local, start, (int) compressed, end));
            position = next;
        }
        if (position != limit) {
            throw error("ARCHIVE", "_", "Central directory has trailing data");
        }
        rejectOverlaps(entries, offset);
        return entries;
    }

    /** Validate a local header against its central record and return the first data byte offset. */
    private static int localData(byte[] data, int local, String raw, int flags, int method, int directoryOffset, String name) {
        // Long arithmetic: a hostile offset near Integer.MAX_VALUE must not wrap around to a small number.
        if ((long) local + 30 > directoryOffset || u32(data, local) != LOCAL) {
            throw error("ARCHIVE", name, "Missing local header");
        }
        int nameLength = u16(data, local + 26);
        int start = local + 30 + nameLength + u16(data, local + 28);
        // A local name or method that differs from the central directory would let two tools disagree on content.
        if (start > directoryOffset || nameLength != raw.length()
                || !raw.equals(new String(data, local + 30, nameLength, StandardCharsets.ISO_8859_1))
                || u16(data, local + 8) != method || u16(data, local + 6) != flags) {
            throw error("ARCHIVE", name, "Local header disagrees with the central directory");
        }
        return start;
    }

    /** Validate sizes/CRC in the local header or classic data descriptor; return the complete entry end. */
    private static int entryEnd(byte[] data, int local, int end, int flags, long crc, long compressed,
            long size, int limit, String name) {
        if ((flags & 8) == 0) {
            if (u32(data, local + 14) != crc || u32(data, local + 18) != compressed || u32(data, local + 22) != size) {
                throw error("ARCHIVE", name, "Local sizes or checksum disagree with the central directory");
            }
            return end;
        }
        // Streaming ZIP writers use zero placeholders followed by a descriptor, optionally signed.
        for (int index = 0; index < 3; index++) {
            long value = u32(data, local + 14 + index * 4);
            long expected = index == 0 ? crc : index == 1 ? compressed : size;
            if (value != 0 && value != expected) {
                throw error("ARCHIVE", name, "Invalid streaming header placeholder");
            }
        }
        if (end + 16 <= limit && u32(data, end) == 0x08074b50L
                && descriptorMatches(data, end + 4, crc, compressed, size)) {
            return end + 16;
        }
        if (end + 12 <= limit && descriptorMatches(data, end, crc, compressed, size)) {
            return end + 12;
        }
        throw error("ARCHIVE", name, "Missing or inconsistent data descriptor");
    }

    /** Compare a bounds-checked classic descriptor with its central-directory CRC and sizes. */
    private static boolean descriptorMatches(byte[] data, int offset, long crc, long compressed, long size) {
        return u32(data, offset) == crc && u32(data, offset + 4) == compressed && u32(data, offset + 8) == size;
    }

    /** Refuse entries whose header or data ranges overlap, the basis of overlapping-file ZIP bombs. */
    private static void rejectOverlaps(List<Entry> entries, int directoryOffset) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((left, right) -> Integer.compare(left.local, right.local));
        int expected = 0;
        for (Entry entry : sorted) {
            // A complete contiguous inventory excludes prepended executables, hidden local entries and gaps.
            if (entry.local != expected) {
                throw error("ARCHIVE", entry.name, "Archive entries overlap or contain unlisted data");
            }
            expected = entry.end;
        }
        if (expected != directoryOffset) {
            throw error("ARCHIVE", "_", "Unlisted data before the central directory");
        }
    }

    /** Decompress one entry into exactly its declared size and verify the CRC-32. */
    private static byte[] inflate(byte[] data, Entry entry) {
        byte[] output;
        if (entry.method == 0) {
            output = java.util.Arrays.copyOfRange(data, entry.start, entry.start + entry.compressed);
        } else {
            // One spare byte detects streams that produce more than the declared size.
            byte[] buffer = new byte[entry.size + 1];
            Inflater inflater = new Inflater(true);
            try {
                inflater.setInput(data, entry.start, entry.compressed);
                int produced = 0;
                while (!inflater.finished() && produced < buffer.length) {
                    int count = inflater.inflate(buffer, produced, buffer.length - produced);
                    // Zero output while unfinished means truncated input or a preset dictionary; both are corrupt.
                    if (count == 0 && !inflater.finished()) {
                        throw error("ARCHIVE", entry.name, "Compressed entry is truncated or unsupported");
                    }
                    produced += count;
                }
                if (!inflater.finished() || produced != entry.size) {
                    throw error("LIMIT", entry.name, "Entry inflates to a size other than declared");
                }
                if (inflater.getRemaining() != 0) {
                    throw error("ARCHIVE", entry.name, "Trailing bytes after the deflate stream");
                }
                output = java.util.Arrays.copyOf(buffer, produced);
            } catch (DataFormatException exception) {
                throw error("ARCHIVE", entry.name, "Compressed entry is corrupt");
            } finally {
                inflater.end();
            }
        }
        CRC32 crc = new CRC32();
        crc.update(output);
        if (crc.getValue() != entry.crc) {
            throw error("ARCHIVE", entry.name, "Entry checksum mismatch");
        }
        return output;
    }

    /**
     * Detect a single wrapping folder: the manifest is not at the root, every file shares one
     * top-level folder, and that folder holds the manifest directly.
     *
     * @return the folder prefix including its trailing slash, or an empty string when nothing is wrapped
     */
    private static String wrapper(Map<String, byte[]> files) {
        if (files.isEmpty() || files.containsKey("adventure.yaml") || files.containsKey("adventure.json")) {
            return "";
        }
        String first = files.keySet().iterator().next();
        int slash = first.indexOf('/');
        if (slash < 0) {
            return "";
        }
        String wrapper = first.substring(0, slash + 1);
        boolean wrapped = files.keySet().stream().allMatch(path -> path.startsWith(wrapper))
                && (files.containsKey(wrapper + "adventure.yaml") || files.containsKey(wrapper + "adventure.json"));
        return wrapped ? wrapper : "";
    }

    /** Remove the wrapper prefix from file paths, keeping sorted order. */
    private static Map<String, byte[]> strip(Map<String, byte[]> files, String wrapper) {
        Map<String, byte[]> stripped = new TreeMap<>();
        files.forEach((path, bytes) -> stripped.put(path.substring(wrapper.length()), bytes));
        return stripped;
    }

    /** Remove the wrapper prefix from directory entries and drop the wrapper entry itself. */
    private static List<String> strip(List<String> directories, String wrapper) {
        List<String> stripped = new ArrayList<>();
        for (String directory : directories) {
            if (wrapper.isEmpty()) {
                stripped.add(directory);
            } else if (directory.startsWith(wrapper)) {
                stripped.add(directory.substring(wrapper.length()));
            } else if (!(directory + "/").equals(wrapper)) {
                throw error("ARCHIVE", directory, "Directory lies outside the single pack wrapper");
            }
        }
        return stripped;
    }

    /** Apply inventory-wide rules shared by both sources and freeze the result. */
    private StagedPack finish(Source source, Map<String, byte[]> files, List<String> directories, long expanded, long archive) {
        if (files.isEmpty()) {
            throw error("LIMIT", "_", "Pack contains no files");
        }
        String collision = PackPaths.collision(files.keySet(), directories);
        if (collision != null) {
            throw error("PATH", collision, "Case-colliding or file/directory-colliding paths are forbidden");
        }
        return new StagedPack(source, files, expanded, archive);
    }

    /** Little-endian unsigned 16-bit value, or a structural error when out of bounds. */
    private static int u16(byte[] data, int offset) {
        if (offset < 0 || offset + 2 > data.length) {
            throw error("ARCHIVE", "_", "Truncated archive structure");
        }
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    /** Little-endian unsigned 32-bit value, or a structural error when out of bounds. */
    private static long u32(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            throw error("ARCHIVE", "_", "Truncated archive structure");
        }
        return (u16(data, offset) | (long) u16(data, offset + 2) << 16) & 0xFFFFFFFFL;
    }

    /** Decode an entry name that must be printable ASCII without backslashes or control characters. */
    private static String ascii(byte[] data, int offset, int length) {
        for (int index = offset; index < offset + length; index++) {
            int value = data[index] & 0xFF;
            // Backslash is a separator on Windows extractors and would bypass the "/" segment rules.
            if (value < 0x20 || value > 0x7E || value == '\\') {
                throw error("PATH", "_", "Archive entry names must be printable ASCII without backslashes");
            }
        }
        return new String(data, offset, length, StandardCharsets.ISO_8859_1);
    }

    /** Build a staging diagnostic located at a sanitized logical name. */
    private static PackException error(String code, String file, String message) {
        return new PackException(code, new SourceLocation(file, 1, 1, ""), message);
    }

    /** Running totals for one directory walk. */
    private static final class Budget {
        private final List<String> directories = new ArrayList<>();
        private long entries;
        private long expanded;
    }

    /** One validated central-directory entry; offsets are already bounds-checked against the input. */
    private record Entry(String name, boolean directory, int method, long crc, int size, int local, int start, int compressed, int end) {
    }

    /** Where a staged inventory came from; returned by preview but never stored, so reports stay host-independent. */
    public enum Source {
        /** A local folder walked without following links. */
        DIRECTORY,
        /** An in-memory ZIP archive. */
        ARCHIVE
    }

    /**
     * Configurable product limits (docs/09 section 9). All values must be positive.
     *
     * @param archiveBytes largest compressed archive
     * @param expandedBytes largest total of file bytes after expansion
     * @param files largest number of files
     * @param ratio largest expanded-to-archive size ratio
     * @param fileBytes largest single file; text categories are further capped at 1 MiB by the parser
     */
    public record Limits(int archiveBytes, long expandedBytes, int files, int ratio, int fileBytes) {
        /** Reject non-positive budgets, which would silently disable a protection. */
        public Limits {
            if (archiveBytes < 1 || archiveBytes > Integer.MAX_VALUE - 8 || expandedBytes < 1 || files < 1 || ratio < 1
                    || fileBytes < 1 || fileBytes > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("Pack limits must be positive");
            }
        }

        /**
         * The documented defaults: 100 MiB archive, 250 MiB expanded, 2,000 files, 50:1 and 32 MiB per asset.
         *
         * @return default product limits
         */
        public static Limits standard() {
            return new Limits(100 * 1024 * 1024, 250L * 1024 * 1024, 2000, 50, PackMedia.MAX_ASSET_BYTES);
        }
    }

    /**
     * An immutable staged inventory ready for {@link PackParser#parse(Map)}.
     *
     * Instances can only be created by this boundary; accessors never expose retained byte arrays.
     */
    public static final class StagedPack {
        private final Source source;
        private final Map<String, byte[]> files;
        private final long expandedBytes;
        private final long archiveBytes;

        /** Freeze a validated inventory with its source and measured byte totals; only staging may call this. */
        private StagedPack(Source source, Map<String, byte[]> files, long expandedBytes, long archiveBytes) {
            this.source = source;
            this.files = copy(files);
            this.expandedBytes = expandedBytes;
            this.archiveBytes = archiveBytes;
        }

        /** Return a defensive copy so neither the caller nor a preview can change staged content. */
        public Map<String, byte[]> files() {
            return copy(files);
        }

        /** Return the source kind, without revealing a host path. */
        public Source source() {
            return source;
        }

        /** Return the measured total expanded file bytes. */
        public long expandedBytes() {
            return expandedBytes;
        }

        /** Return the measured archive size, or zero for directory input. */
        public long archiveBytes() {
            return archiveBytes;
        }

        /** Copy the complete byte inventory, preserving sorted order and an unmodifiable map. */
        private static Map<String, byte[]> copy(Map<String, byte[]> source) {
            Map<String, byte[]> result = new TreeMap<>();
            source.forEach((path, bytes) -> result.put(path, bytes.clone()));
            return Collections.unmodifiableMap(result);
        }
    }
}
