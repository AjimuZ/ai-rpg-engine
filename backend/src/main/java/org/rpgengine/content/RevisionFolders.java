package org.rpgengine.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Publish a complete derived revision by one same-filesystem atomic directory rename.
 * Input bytes must already be scoped and verified; all paths are checked again before I/O.
 * Existing revisions are compared exactly, never overwritten or silently repaired. A failed build
 * may leave a private .building directory for offline inspection, never a visible partial revision.
 * The root must be private and quiescent: portable JDK calls cannot exclude a hostile local process
 * replacing an already-checked ancestor between operations. Multiple engine writers are unsupported.
 */
final class RevisionFolders {
    private final Path root;

    /** Require an explicit absolute non-volume-root path; no user title or relative process directory is accepted. */
    RevisionFolders(Path root) {
        if (!root.isAbsolute() || root.normalize().getParent() == null || !root.normalize().equals(root)) {
            throw new IllegalArgumentException("workspace root must be an absolute normalized private directory");
        }
        this.root = root;
    }

    /** Publish verified bytes for an opaque pair, or prove an existing projection is identical; failures never overwrite it. */
    void publish(UUID adventure, UUID revision, Map<String, byte[]> files) throws IOException {
        // Prefixing a valid pack path adds directory components, so check each complete destination
        // using the host-independent component rules without imposing the pack's aggregate depth cap.
        for (String path : files.keySet()) {
            for (String segment : path.split("/", -1)) {
                if (!PackPaths.portable(segment)) { throw new IOException("invalid projection path"); }
            }
        }
        Path parent = root.resolve("adventures").resolve(adventure.toString()).resolve("content-revisions");
        directory(parent);
        Path target = parent.resolve(revision.toString());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verify(target, files);
            return;
        }
        Path staging = parent.resolve(".building-" + UUID.randomUUID());
        createDirectory(staging);
        // The entire tree, including the manifest, is hidden behind a non-revision staging name.
        // No fallback to a non-atomic move is allowed on filesystems lacking atomic directory rename.
        for (var file : files.entrySet()) {
            Path destination = staging.resolve(file.getKey());
            directory(destination.getParent());
            if (posix(destination.getParent())) {
                Files.createFile(destination, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.write(destination, file.getValue(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            } else {
                Files.write(destination, file.getValue(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
            }
        }
        verify(staging, files);
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Check every ancestor without following links; create missing directories privately or propagate I/O failure. */
    private static void directory(Path path) throws IOException {
        if (path.getParent() != null) { directory(path.getParent()); }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { createDirectory(path); }
        var attributes = attributes(path);
        if (!attributes.isDirectory()) { throw new IOException("directory required"); }
    }

    /** Create one new directory with owner-only POSIX mode; Windows requires an operator-controlled private ACL. */
    private static void createDirectory(Path path) throws IOException {
        if (posix(path.getParent())) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectory(path);
        }
    }

    /** Determine filesystem permission support from an existing parent; unsupported metadata is never simulated. */
    private static boolean posix(Path parent) throws IOException {
        return Files.getFileStore(parent).supportsFileAttributeView("posix");
    }

    /** Read no-follow attributes and reject symbolic links, junctions and special/reparse nodes before traversal. */
    private static BasicFileAttributes attributes(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) { throw new IOException("link or special file refused"); }
        return attributes;
    }

    /** Require exact directories and byte inventory, including the manifest; extra files and empty directories fail closed. */
    private static void verify(Path base, Map<String, byte[]> files) throws IOException {
        Set<String> directories = new HashSet<>();
        for (String file : files.keySet()) {
            for (int slash = file.indexOf('/'); slash >= 0; slash = file.indexOf('/', slash + 1)) {
                directories.add(file.substring(0, slash));
            }
        }
        Set<String> seen = new HashSet<>();
        inspect(base, "", files, directories, seen);
        if (!seen.equals(files.keySet())) { throw new IOException("missing projected file"); }
    }

    /** Walk only expected directories and bounded regular files; reject missing, extra, linked or changed content. */
    private static void inspect(Path path, String relative, Map<String, byte[]> files,
            Set<String> directories, Set<String> seen) throws IOException {
        var attributes = attributes(path);
        if (attributes.isDirectory()) {
            if (!relative.isEmpty() && !directories.contains(relative)) { throw new IOException("unexpected directory"); }
            try (var children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    inspect(child, relative.isEmpty() ? child.getFileName().toString()
                            : relative + "/" + child.getFileName(), files, directories, seen);
                }
            }
        } else {
            byte[] expected = files.get(relative);
            if (!attributes.isRegularFile() || expected == null || attributes.size() != expected.length) {
                throw new IOException("unexpected projected file");
            }
            // Read at most the expected size plus one; never allocate from an untrusted on-disk length.
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!Arrays.equals(expected, input.readNBytes(expected.length + 1))) {
                    throw new IOException("changed projected file");
                }
            }
            seen.add(relative);
        }
    }
}
