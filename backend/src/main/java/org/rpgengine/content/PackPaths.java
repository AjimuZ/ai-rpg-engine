package org.rpgengine.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Portable logical path rules shared by the parser and the staging boundary (docs/09 sections 3.1 and 9).
 *
 * <p>A logical path is a pack-relative name that must mean the same file on every supported host:
 * ASCII letters, digits, {@code _}, {@code -}, {@code .} and {@code /} separators only, so no drive
 * letters, backslashes, colons (NTFS alternate streams), Unicode normalization or case tricks can
 * redirect a name. The rules are purely lexical; containment on a real filesystem is enforced
 * separately by {@link PackStaging}, which never resolves a logical path against a host directory.
 */
public final class PackPaths {
    /** Longest accepted logical path, in ASCII characters. */
    static final int MAX_LENGTH = 240;
    /** Deepest accepted directory nesting; deep trees only serve to exhaust walkers. */
    static final int MAX_DEPTH = 16;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");
    // Windows treats these device names as reserved in any directory and with any extension,
    // so "aux.md" or "lore/con.yaml" cannot be created portably and could address a device.
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul",
            "com0", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt0", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** Prevent instances of the stateless rule set. */
    private PackPaths() {
    }

    /**
     * Report whether a logical path is canonical and portable.
     *
     * @param path candidate pack-relative path using {@code /} separators
     * @return true only for 1..240 ASCII characters in 1..16 non-empty segments, none of which is
     *         {@code .}/{@code ..}, starts with {@code .} or {@code -}, ends with {@code .} (Windows
     *         strips trailing dots, aliasing two names) or is a reserved device name
     */
    public static boolean portable(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_LENGTH) {
            return false;
        }
        String[] segments = path.split("/", -1);
        if (segments.length > MAX_DEPTH) {
            return false;
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches() || segment.endsWith(".")) {
                return false;
            }
            // The device check applies to the stem before the first dot, as Windows does.
            int dot = segment.indexOf('.');
            String stem = (dot < 0 ? segment : segment.substring(0, dot)).toLowerCase(Locale.ROOT);
            if (RESERVED.contains(stem)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Require a portable path or throw a located {@code PATH} diagnostic.
     *
     * @param path candidate logical path
     * @param location where the path was declared or found; must not carry a host path
     */
    static void require(String path, SourceLocation location) {
        if (!portable(path)) {
            throw new PackException("PATH", location, "Expected a canonical portable relative path");
        }
    }

    /**
     * Find the first path that would alias another file or directory on a case-insensitive host.
     *
     * <p>Two names collide when they are equal after ASCII case folding ({@code Map.yaml} versus
     * {@code map.yaml}), or when a file name equals a directory prefix of another entry
     * ({@code lore} versus {@code lore/a.md}), because no filesystem can hold both and an extractor
     * would silently overwrite or misplace one of them.
     *
     * @param files logical file paths; already individually portable
     * @param directories explicit directory entries without trailing slash, which may be empty
     * @return the first colliding path in iteration order, or null when all names are distinct
     */
    static String collision(Iterable<String> files, Iterable<String> directories) {
        Set<String> folded = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        Map<String, String> spelling = new HashMap<>();
        for (String path : files) {
            if (aliases(path, spelling)) {
                return path;
            }
            if (!folded.add(path.toLowerCase(Locale.ROOT))) {
                return path;
            }
            prefixes.addAll(parents(path.toLowerCase(Locale.ROOT)));
        }
        for (String directory : directories) {
            if (aliases(directory, spelling)) {
                return directory;
            }
            String lower = directory.toLowerCase(Locale.ROOT);
            prefixes.add(lower);
            prefixes.addAll(parents(lower));
        }
        // A second pass is needed because a directory may be introduced after the file that shadows it.
        for (String path : files) {
            if (prefixes.contains(path.toLowerCase(Locale.ROOT))) {
                return path;
            }
        }
        return null;
    }

    /** Record each path component's spelling; return true if any prefix aliases a differently cased name. */
    private static boolean aliases(String path, Map<String, String> spelling) {
        List<String> names = parents(path);
        names.add(path);
        for (String name : names) {
            String previous = spelling.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            if (previous != null && !previous.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Every proper directory prefix of a path, for example {@code a} and {@code a/b} for {@code a/b/c}. */
    private static List<String> parents(String path) {
        List<String> parents = new ArrayList<>();
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            parents.add(path.substring(0, slash));
        }
        return parents;
    }

    /**
     * Replace characters outside the portable alphabet so an untrusted name can appear in a
     * diagnostic without control characters, host separators or unbounded length.
     *
     * @param name untrusted entry name
     * @return a printable ASCII rendering of at most {@link #MAX_LENGTH} characters, never empty
     */
    static String sanitize(String name) {
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < name.length() && safe.length() < MAX_LENGTH; index++) {
            char character = name.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9') || "_.-/".indexOf(character) >= 0;
            safe.append(allowed ? character : '_');
        }
        return safe.isEmpty() ? "_" : safe.toString();
    }

    /**
     * Lower-case extension of the last segment, used for media type allowlists.
     *
     * @param path portable logical path
     * @return extension without the dot, or an empty string when the name has none
     */
    static String extension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
