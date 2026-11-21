package org.rpgengine.exports;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.rpgengine.adventure.AdventureLibraryService;
import org.rpgengine.content.EngineContract;
import org.rpgengine.content.PackException;
import org.rpgengine.content.PackParser;
import org.rpgengine.content.PackPaths;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.content.PackStaging;
import org.rpgengine.content.PackTextParser;
import org.rpgengine.content.ParsedPack;
import org.rpgengine.content.SourceLocation;
import org.rpgengine.presentation.SupportedLocale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reusable adventure bundles for sharing between people (P16.2-B; docs/09 section 14, QA-41).
 *
 * <p><b>Layout.</b> A bundle is one ZIP archive. {@code bundle.json} sits at its root, and each pack of the exported
 * revision's dependency closure sits in its own folder {@code pack-1}, {@code pack-2} and so on, dependencies before
 * the packs that need them; the last folder is the root pack. Each folder holds the exact sealed bytes of one content
 * revision, so pack IDs, versions, file hashes, content hashes, dependency pins, locale overlays and the license and
 * credit files are the sender's own bytes. {@code bundle.json} is the integrity, dependency, locale and rights
 * manifest. It is canonical: an importer rebuilds it from the packs it receives and refuses the bundle unless the
 * bytes are identical, so no field can be edited, added or reordered.
 *
 * <p><b>What a bundle never carries.</b> Only sealed {@code content_revision_file} rows are read. Campaigns, saves,
 * journals, generated memories and plans, jobs, the owner's identity or profile, local adventure and revision UUIDs,
 * admission times and host paths never enter a bundle. The same revision exported twice gives identical bytes.
 *
 * <p><b>Import.</b> Bytes cross {@link PackStaging#archive} first, so every hostile-archive protection of a pack import
 * applies whoever sent the bundle. Every pack is then parsed, its file list and hashes checked against
 * {@code bundle.json}, and its dependencies required inside the bundle, all before anything is written. Packs are
 * registered through {@link PackRegistry#register}, which seals them as new immutable revisions with local UUIDs.
 * Without a destination the import creates a new adventure workspace; with one, the destination must be owned and an
 * existing pack ID and version with other bytes is a conflict that stops the import before any write. Nothing is ever
 * overwritten, merged by title or added to a campaign, and identical packs already in the destination are reused.
 */
@Service
public class AdventureBundles {
    /** Identifies the bundle manifest; an importer refuses any other value. */
    public static final String FORMAT = "engine.adventure-bundle";
    /** Version of the bundle layout; a change of layout increments it. */
    public static final int FORMAT_VERSION = 1;
    /** Name of the bundle manifest at the archive root. */
    public static final String MANIFEST = "bundle.json";
    /** Most packs one bundle carries, the campaign dependency-closure limit. */
    static final int MAX_PACKS = 32;
    /** Deepest dependency chain exported, the campaign dependency-closure limit. */
    static final int MAX_DEPTH = 16;
    private static final String FOLDER = "pack-";
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final PackStaging.Limits LIMITS = PackStaging.Limits.standard();

    private final JdbcTemplate jdbc;
    private final PackRegistry registry;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param jdbc storage
     * @param manager transaction manager for authorization reads and workspace creation
     * @param registry the only path through which imported packs are sealed
     */
    public AdventureBundles(JdbcTemplate jdbc, PlatformTransactionManager manager, PackRegistry registry) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.registry = Objects.requireNonNull(registry, "registry");
        reads = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(30);
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(15);
    }

    /**
     * Export one sealed revision of an owned adventure with its whole pinned dependency closure.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must hold the revision
     * @param revision sealed content revision to share
     * @return the archive and its digest
     * @throws AdventureLibraryService.ScopeUnavailable for a missing, foreign, unsealed or mismatched scope
     * @throws PackException {@code DEPENDENCY_MISSING}, {@code DEPENDENCY_CONFLICT} or {@code LIMIT} when the closure
     *     cannot be bundled, or a staging code when the archive would not pass a recipient's import boundary
     * @throws IllegalStateException inside an ambient transaction, or when stored bytes no longer match their hash
     */
    public Bundle export(UUID owner, UUID adventure, UUID revision) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(revision, "revision");
        independent();
        if (!Boolean.TRUE.equals(reads.execute(status -> jdbc.queryForObject("select exists(select 1 from "
                + "content_revision c join adventure_workspace w on w.id = c.adventure_id join content_revision_report r "
                + "on r.content_revision_id = c.id where c.id = ? and c.adventure_id = ? and w.owner_id = ?)",
                Boolean.class, revision, adventure, owner)))) {
            throw new AdventureLibraryService.ScopeUnavailable();
        }
        List<Member> members = new ArrayList<>();
        collect(adventure, revision, 0, new HashSet<>(), new HashMap<>(), members);
        Map<String, byte[]> entries = new TreeMap<>();
        long total = 0;
        for (Member member : members) {
            for (var file : member.files().entrySet()) {
                String path = member.folder() + "/" + file.getKey();
                // A prefixed path one segment deeper than the pack allows would fail the recipient's staging.
                if (!PackPaths.portable(path)) {
                    throw error("LIMIT", member.folder(), "", "A pack path is too long or deep to bundle");
                }
                entries.put(path, file.getValue());
                total += file.getValue().length;
            }
        }
        if (entries.size() + 1 > LIMITS.files() || total > LIMITS.expandedBytes()) {
            throw error("LIMIT", MANIFEST, "", "The dependency closure exceeds the bundle limits");
        }
        byte[] archive = zip(document(members), entries);
        // Prove that the bundle passes the recipient's whole import boundary before handing it out.
        var read = read(archive);
        return new Bundle(read.members().getLast().coordinates(), read.members().size(), sha256(archive), archive);
    }

    /**
     * Check a received bundle and describe it, without writing anything (docs/09 section 14: recipients preview origin,
     * title, version, locales, dependencies, rights and warnings before an explicit import).
     *
     * @param owner locally admitted owner
     * @param destination owned adventure the import would use, or null for a new adventure
     * @param archive received bytes, untrusted
     * @return what an import would install, with each pack's standing in the destination
     * @throws PackException for a hostile, malformed or inconsistent bundle, with the same codes as a pack import plus
     *     {@code BUNDLE}, {@code INTEGRITY} and {@code DEPENDENCY_MISSING}
     * @throws AdventureLibraryService.ScopeUnavailable for an unknown owner or a missing or foreign destination
     */
    public Preview preview(UUID owner, UUID destination, byte[] archive) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(archive, "archive");
        independent();
        Read read = read(archive);
        return new Preview(sha256(archive), describe(read, standings(owner, destination, read)));
    }

    /**
     * Install a received bundle as new immutable revisions.
     *
     * <p>Every check of {@link #preview} runs first, and a conflict in the destination stops the import before any
     * write. Packs are then sealed one by one in dependency order. A pack the recipient's engine refuses after earlier
     * packs were sealed leaves those earlier, valid and immutable revisions in place (in the new workspace when no
     * destination was named); repeating the import into that workspace reuses them.
     *
     * @param owner locally admitted owner
     * @param destination owned adventure to add the packs to, or null to create a new adventure workspace
     * @param archive received bytes, untrusted
     * @return the destination and each pack's local revision
     * @throws PackException as {@link #preview}, {@code HASH_CONFLICT} for a conflicting pack in the destination, or
     *     any registration diagnostic
     * @throws AdventureLibraryService.ScopeUnavailable for an unknown owner or a missing or foreign destination
     */
    public Imported importBundle(UUID owner, UUID destination, byte[] archive) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(archive, "archive");
        independent();
        Read read = read(archive);
        List<Standing> standings = standings(owner, destination, read);
        for (int index = 0; index < standings.size(); index++) {
            if (standings.get(index) == Standing.CONFLICT) {
                throw error("HASH_CONFLICT", MANIFEST, "/packs/" + index,
                        "The destination already holds this pack version with other bytes");
            }
        }
        UUID target = destination;
        boolean created = false;
        if (target == null) {
            target = create(owner, read.members().getLast().parsed());
            created = true;
        }
        List<Installed> installed = new ArrayList<>();
        for (Member member : read.members()) {
            var registration = registry.register(target, member.staged());
            installed.add(new Installed(member.coordinates(), registration.revisionId(), registration.created()));
        }
        return new Imported(target, created, installed);
    }

    /** Visit a revision's dependencies first, then the revision; bounds match the campaign closure. */
    private void collect(UUID adventure, UUID revision, int depth, Set<UUID> visiting, Map<String, Member> byPack,
            List<Member> members) {
        if (depth > MAX_DEPTH || members.size() + visiting.size() >= MAX_PACKS) {
            throw error("LIMIT", MANIFEST, "", "The dependency closure exceeds the bundle limits");
        }
        if (!visiting.add(revision)) {
            throw error("DEPENDENCY_CONFLICT", MANIFEST, "", "The dependency closure has a cycle");
        }
        Map<String, byte[]> files = registry.files(revision)
                .orElseThrow(AdventureLibraryService.ScopeUnavailable::new);
        ParsedPack parsed = new PackParser().parse(files);
        List<Map<String, Object>> dependencies = dependencies(parsed);
        for (int index = 0; index < dependencies.size(); index++) {
            var pin = dependencies.get(index);
            Member existing = byPack.get((String) pin.get("id"));
            if (existing != null) {
                if (!existing.coordinates().equals(coordinates(pin))) {
                    throw error("DEPENDENCY_CONFLICT", MANIFEST, "", "Two versions of one pack are pinned");
                }
                continue;
            }
            List<UUID> found = reads.execute(status -> jdbc.query("select c.id from content_revision c "
                    + "join content_revision_report r on r.content_revision_id = c.id where c.adventure_id = ? and "
                    + "c.pack_id = ? and c.pack_version = ? and c.content_hash = ?", (row, n) -> row.getObject(1,
                    UUID.class), adventure, pin.get("id"), pin.get("version"), pin.get("hash")));
            if (found.isEmpty()) {
                throw error("DEPENDENCY_MISSING", root(parsed), "/dependencies/" + index,
                        "A pinned dependency is not sealed in this adventure");
            }
            collect(adventure, found.getFirst(), depth + 1, visiting, byPack, members);
        }
        visiting.remove(revision);
        if (byPack.containsKey(parsed.manifest().id())) {
            throw error("DEPENDENCY_CONFLICT", MANIFEST, "", "Two versions of one pack are pinned");
        }
        Map<String, String> ruleset = reads.execute(status -> jdbc.queryForObject("select ruleset_id, ruleset_version, "
                + "ruleset_hash from content_revision where id = ?", (row, n) -> AdventureBundles.<String>ordered("id",
                row.getString(1),
                "version", row.getString(2), "hash", row.getString(3)), revision));
        Member member = new Member(FOLDER + (members.size() + 1), null, parsed, files, ruleset);
        byPack.put(parsed.manifest().id(), member);
        members.add(member);
    }

    /**
     * Stage and check a bundle without touching storage.
     *
     * @throws PackException for any hostile, malformed or inconsistent input
     */
    private Read read(byte[] archive) {
        var staging = new PackStaging(LIMITS);
        PackStaging.StagedPack staged = staging.archive(new ByteArrayInputStream(archive));
        Map<String, byte[]> all = staged.files();
        byte[] manifest = all.get(MANIFEST);
        if (manifest == null) {
            throw error("BUNDLE", MANIFEST, "", "The archive has no bundle manifest");
        }
        if (!(new PackTextParser().parse(MANIFEST, manifest).value() instanceof Map<?, ?> declared)
                || !FORMAT.equals(declared.get("format"))
                || !String.valueOf(declared.get("formatVersion")).equals(String.valueOf(FORMAT_VERSION))
                || !(declared.get("packs") instanceof List<?> packs) || packs.isEmpty() || packs.size() > MAX_PACKS) {
            throw error("BUNDLE", MANIFEST, "", "Not a supported adventure bundle");
        }
        Set<String> folders = new HashSet<>();
        for (int index = 1; index <= packs.size(); index++) {
            folders.add(FOLDER + index);
        }
        for (String path : all.keySet()) {
            int slash = path.indexOf('/');
            if (!path.equals(MANIFEST) && (slash < 0 || !folders.contains(path.substring(0, slash)))) {
                throw error("INTEGRITY", path, "", "The archive holds a file outside its packs");
            }
        }
        List<Member> members = new ArrayList<>();
        Map<String, Member> byPack = new HashMap<>();
        for (int index = 0; index < packs.size(); index++) {
            String folder = FOLDER + (index + 1);
            String pointer = "/packs/" + index;
            if (!(packs.get(index) instanceof Map<?, ?> entry) || !(entry.get("ruleset") instanceof Map<?, ?> ruleset)
                    || !(ruleset.get("id") instanceof String rulesetId)
                    || !(ruleset.get("version") instanceof String rulesetVersion)
                    || !(ruleset.get("hash") instanceof String rulesetHash)) {
                throw error("BUNDLE", MANIFEST, pointer, "Malformed pack entry");
            }
            PackStaging.StagedPack pack = staging.subtree(staged, folder);
            Map<String, byte[]> files = pack.files();
            ParsedPack parsed = new PackParser().parse(files);
            try {
                SupportedLocale.fromTag((String) parsed.manifest().values().get("contentLocale"));
            } catch (IllegalArgumentException unsupported) {
                throw error("UNSUPPORTED_LOCALE", folder + "/" + root(parsed), "/contentLocale",
                        "Content locale must be one of en, it");
            }
            if (byPack.containsKey(parsed.manifest().id())) {
                throw error("BUNDLE", MANIFEST, pointer, "A pack appears twice");
            }
            // Dependencies must come earlier in the same bundle, with exactly the pinned coordinates.
            List<Map<String, Object>> dependencies = dependencies(parsed);
            for (int at = 0; at < dependencies.size(); at++) {
                var pin = dependencies.get(at);
                Member found = byPack.get((String) pin.get("id"));
                if (found == null || !found.coordinates().equals(coordinates(pin))) {
                    throw error("DEPENDENCY_MISSING", folder + "/" + root(parsed), "/dependencies/" + at,
                            "A pinned dependency is not carried earlier in the bundle");
                }
            }
            Member member = new Member(folder, pack, parsed, files, ordered("id", rulesetId, "version", rulesetVersion,
                    "hash", rulesetHash));
            byPack.put(parsed.manifest().id(), member);
            members.add(member);
        }
        // The manifest must be exactly what these packs produce: any edited, added or reordered field fails here.
        if (!Arrays.equals(document(members), manifest)) {
            throw error("INTEGRITY", MANIFEST, "", "The bundle manifest does not match its packs");
        }
        return new Read(List.copyOf(members));
    }

    /** Canonical bundle manifest of an ordered closure; export writes it and import requires identical bytes. */
    private byte[] document(List<Member> members) {
        List<Object> packs = new ArrayList<>();
        for (Member member : members) {
            ParsedPack parsed = member.parsed();
            Map<String, Object> values = parsed.manifest().values();
            Map<String, String> categories = new TreeMap<>();
            categories.put(root(parsed), "manifest");
            parsed.manifest().files().forEach(file -> categories.put(file.path(), file.category()));
            List<Object> files = new ArrayList<>();
            List<String> licenses = new ArrayList<>();
            List<String> credits = new ArrayList<>();
            for (var file : categories.entrySet()) {
                files.add(ordered("path", file.getKey(), "category", file.getValue(), "bytes",
                        (long) member.files().get(file.getKey()).length, "sha256",
                        parsed.fileHashes().get(file.getKey())));
                if (file.getValue().equals("license")) {
                    licenses.add(file.getKey());
                } else if (file.getValue().equals("credits")) {
                    credits.add(file.getKey());
                }
            }
            Map<String, Object> provenance = new LinkedHashMap<>();
            if (values.get("provenance") instanceof Map<?, ?> declared) {
                for (String key : List.of("source", "notice")) {
                    if (declared.get(key) != null) {
                        provenance.put(key, declared.get(key));
                    }
                }
            }
            List<String> overlays = new ArrayList<>();
            if (values.get("localizations") instanceof Map<?, ?> localizations) {
                localizations.keySet().forEach(key -> overlays.add((String) key));
                overlays.sort(null);
            }
            List<Object> pins = new ArrayList<>();
            dependencies(parsed).forEach(pin -> pins.add(ordered("id", pin.get("id"), "version", pin.get("version"),
                    "hash", pin.get("hash"))));
            Map<String, Object> entry = ordered("folder", member.folder(), "id", parsed.manifest().id(), "version",
                    values.get("version"), "contentHash", parsed.contentHash(), "schemaVersion",
                    ((Number) values.get("schemaVersion")).intValue(), "title", values.get("title"), "summary",
                    values.get("summary"), "narrativeMode", values.get("narrativeMode"));
            entry.put("locales", ordered("source", values.get("contentLocale"), "overlays", overlays));
            entry.put("rights", ordered("license", values.get("license"), "authors", values.get("authors"),
                    "provenance", provenance, "licenseFiles", licenses, "creditFiles", credits));
            entry.put("dependencies", pins);
            entry.put("ruleset", member.ruleset());
            entry.put("files", files);
            packs.add(entry);
        }
        Member root = members.getLast();
        Map<String, Object> document = ordered("format", FORMAT, "formatVersion", FORMAT_VERSION, "root",
                ordered("id", root.parsed().manifest().id(), "version", root.coordinates().version(), "contentHash",
                        root.parsed().contentHash()));
        document.put("packs", packs);
        return json.writeValueAsBytes(document);
    }

    /** Write the manifest first, then every pack file in path order, with one fixed entry time. */
    private static byte[] zip(byte[] manifest, Map<String, byte[]> entries) {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            Map<String, byte[]> ordered = new LinkedHashMap<>();
            ordered.put(MANIFEST, manifest);
            ordered.putAll(entries);
            for (var file : ordered.entrySet()) {
                var entry = new ZipEntry(file.getKey());
                entry.setTimeLocal(ENTRY_TIME);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("bundle could not be archived", failure);
        }
        return output.toByteArray();
    }

    /**
     * Authorize the owner and destination and classify each pack there, in one read-only snapshot.
     *
     * @return one standing per bundled pack, in bundle order; all {@link Standing#NEW} without a destination
     */
    private List<Standing> standings(UUID owner, UUID destination, Read read) {
        return reads.execute(status -> {
            boolean allowed = destination == null
                    ? Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from identity_owner_profile "
                            + "where id = ?)", Boolean.class, owner))
                    : Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from adventure_workspace "
                            + "where id = ? and owner_id = ?)", Boolean.class, destination, owner));
            if (!allowed) {
                throw new AdventureLibraryService.ScopeUnavailable();
            }
            List<Standing> standings = new ArrayList<>();
            for (Member member : read.members()) {
                if (destination == null) {
                    standings.add(Standing.NEW);
                    continue;
                }
                var hashes = jdbc.queryForList("select content_hash from content_revision where adventure_id = ? "
                        + "and pack_id = ? and pack_version = ?", String.class, destination,
                        member.coordinates().id(), member.coordinates().version());
                standings.add(hashes.isEmpty() ? Standing.NEW
                        : hashes.getFirst().equals(member.coordinates().hash()) ? Standing.INSTALLED : Standing.CONFLICT);
            }
            return standings;
        });
    }

    /** Public description of each pack, from its own sealed manifest bytes. */
    private List<PackSummary> describe(Read read, List<Standing> standings) {
        String rulesetHash = EngineContract.current().rulesetHash();
        List<PackSummary> summaries = new ArrayList<>();
        for (int index = 0; index < read.members().size(); index++) {
            Member member = read.members().get(index);
            Map<String, Object> values = member.parsed().manifest().values();
            List<String> overlays = new ArrayList<>();
            if (values.get("localizations") instanceof Map<?, ?> localizations) {
                localizations.keySet().forEach(key -> overlays.add((String) key));
                overlays.sort(null);
            }
            List<String> licenses = new ArrayList<>();
            List<String> credits = new ArrayList<>();
            member.parsed().manifest().files().forEach(file -> {
                if (file.category().equals("license")) {
                    licenses.add(file.path());
                } else if (file.category().equals("credits")) {
                    credits.add(file.path());
                }
            });
            licenses.sort(null);
            credits.sort(null);
            String source = null;
            String notice = null;
            if (values.get("provenance") instanceof Map<?, ?> provenance) {
                source = (String) provenance.get("source");
                notice = (String) provenance.get("notice");
            }
            @SuppressWarnings("unchecked")
            List<String> authors = (List<String>) values.get("authors");
            summaries.add(new PackSummary(member.coordinates(), (String) values.get("title"),
                    (String) values.get("summary"), (String) values.get("contentLocale"), overlays,
                    new Rights((String) values.get("license"), authors, source, notice, licenses, credits),
                    dependencies(member.parsed()).stream().map(AdventureBundles::coordinates).toList(),
                    member.files().size(), member.files().values().stream().mapToLong(bytes -> bytes.length).sum(),
                    rulesetHash.equals(member.ruleset().get("hash")), standings.get(index)));
        }
        return summaries;
    }

    /**
     * Create the owner's new workspace for an import, labelled from the root manifest as first-start provisioning does:
     * its title, and its first content theme as genre (or {@code adventure}).
     */
    private UUID create(UUID owner, ParsedPack root) {
        Map<String, Object> values = root.manifest().values();
        String title = limit(((String) values.get("title")).strip(), 200);
        String genre = "adventure";
        if (values.get("contentGuidance") instanceof Map<?, ?> guidance && guidance.get("themes") instanceof List<?> themes
                && !themes.isEmpty() && themes.getFirst() instanceof String theme && !theme.isBlank()
                && theme.strip().codePointCount(0, theme.strip().length()) <= 80) {
            genre = theme.strip();
        }
        String chosenGenre = genre;
        UUID workspace = UUID.randomUUID();
        writes.execute(status -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from identity_owner_profile where "
                    + "id = ?)", Boolean.class, owner))) {
                throw new AdventureLibraryService.ScopeUnavailable();
            }
            return jdbc.update("insert into adventure_workspace (id, owner_id, title, genre, source_locale, created_at, "
                    + "updated_at) values (?, ?, ?, ?, ?, now(), now())", workspace, owner, title.isEmpty() ? "Adventure"
                    : title, chosenGenre, values.get("contentLocale"));
        });
        return workspace;
    }

    /** At most {@code max} code points, so the stored title fits its column. */
    private static String limit(String text, int max) {
        return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
    }

    /** @return the parser-validated dependency pins of a manifest; none when absent */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dependencies(ParsedPack parsed) {
        return (List<Map<String, Object>>) parsed.manifest().values().getOrDefault("dependencies", List.of());
    }

    /** Coordinates of one dependency pin. */
    private static Coordinates coordinates(Map<String, Object> pin) {
        return new Coordinates((String) pin.get("id"), (String) pin.get("version"), (String) pin.get("hash"));
    }

    /** Root manifest file name; exactly one of the two exists after parsing. */
    private static String root(ParsedPack parsed) {
        return parsed.documents().containsKey("adventure.yaml") ? "adventure.yaml" : "adventure.json";
    }

    /** Insertion-ordered map from alternating keys and values. */
    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> ordered(Object... pairs) {
        Map<String, V> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], (V) pairs[index + 1]);
        }
        return map;
    }

    /** A bundle diagnostic at a logical name; never a host path. */
    private static PackException error(String code, String file, String pointer, String message) {
        return new PackException(code, new SourceLocation(file, 1, 1, pointer), message);
    }

    /** Refuse an ambient transaction, which would hide uncommitted rows or hold locks across registrations. */
    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("adventure bundles require no ambient transaction");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * One pack of a bundle.
     *
     * @param folder archive folder, {@code pack-<n>}
     * @param staged protected inventory to register; null on the export side
     * @param files the pack's own paths and bytes
     * @param ruleset id, version and hash of the ruleset the sender sealed it with
     */
    private record Member(String folder, PackStaging.StagedPack staged, ParsedPack parsed, Map<String, byte[]> files,
            Map<String, String> ruleset) {
        /** @return the pack's exact coordinates */
        Coordinates coordinates() {
            return new Coordinates(parsed.manifest().id(), (String) parsed.manifest().values().get("version"),
                    parsed.contentHash());
        }
    }

    /** A checked bundle: its packs in dependency order, the root last. */
    private record Read(List<Member> members) {
    }

    /**
     * Exact pack coordinates, as a dependency pin names them.
     *
     * @param id pack ID
     * @param version exact pack version
     * @param hash lowercase hexadecimal content hash
     */
    public record Coordinates(String id, String version, String hash) {
    }

    /** Where a bundled pack stands in the destination. */
    public enum Standing {
        /** Not in the destination; an import seals it as a new revision. */
        NEW,
        /** The destination already holds identical bytes; an import reuses that revision. */
        INSTALLED,
        /** The destination holds this pack ID and version with other bytes; an import is refused. */
        CONFLICT
    }

    /**
     * Author-declared rights of a pack. The texts of the license and credit files are the pack's own bytes.
     *
     * @param license the manifest's license statement
     * @param authors the manifest's authors
     * @param provenanceSource the manifest's provenance source, or null
     * @param provenanceNotice the manifest's provenance notice, or null
     * @param licenseFiles paths of files in the {@code license} category
     * @param creditFiles paths of files in the {@code credits} category
     */
    public record Rights(String license, List<String> authors, String provenanceSource, String provenanceNotice,
            List<String> licenseFiles, List<String> creditFiles) {
        /** Detach the lists. */
        public Rights {
            authors = List.copyOf(authors);
            licenseFiles = List.copyOf(licenseFiles);
            creditFiles = List.copyOf(creditFiles);
        }
    }

    /**
     * What a recipient sees of one bundled pack before importing it.
     *
     * @param pack exact coordinates
     * @param sourceLocale the pack's source language
     * @param overlays locales with an authored overlay, sorted
     * @param dependencies the pack's pins, each carried earlier in the bundle
     * @param fileCount files including the manifest
     * @param bytes total file bytes
     * @param rulesetMatches the sender sealed it with this build's ruleset; when false the recipient's engine may
     *     still accept it, which registration decides
     * @param standing what an import into the chosen destination does with it
     */
    public record PackSummary(Coordinates pack, String title, String summary, String sourceLocale,
            List<String> overlays, Rights rights, List<Coordinates> dependencies, int fileCount, long bytes,
            boolean rulesetMatches, Standing standing) {
        /** Detach the lists. */
        public PackSummary {
            overlays = List.copyOf(overlays);
            dependencies = List.copyOf(dependencies);
        }
    }

    /**
     * A checked bundle as a recipient may review it.
     *
     * @param sha256 lowercase hexadecimal SHA-256 of the received archive
     * @param packs packs in dependency order; the last is the root
     */
    public record Preview(String sha256, List<PackSummary> packs) {
        /** Detach the list. */
        public Preview {
            packs = List.copyOf(packs);
        }

        /** @return the root pack */
        public PackSummary root() {
            return packs.getLast();
        }

        /** @return true when an import into the chosen destination would be refused */
        public boolean conflicted() {
            return packs.stream().anyMatch(pack -> pack.standing() == Standing.CONFLICT);
        }
    }

    /**
     * One installed pack.
     *
     * @param pack exact coordinates, identical to the sender's
     * @param revisionId the recipient's own revision identity
     * @param created false when identical bytes were already sealed in the destination
     */
    public record Installed(Coordinates pack, UUID revisionId, boolean created) {
    }

    /**
     * Outcome of an import.
     *
     * @param adventureId the destination workspace
     * @param adventureCreated true when the import created it
     * @param packs installed packs in dependency order; the last is the root
     */
    public record Imported(UUID adventureId, boolean adventureCreated, List<Installed> packs) {
        /** Detach the list. */
        public Imported {
            packs = List.copyOf(packs);
        }
    }

    /**
     * One exported bundle. {@link #sha256()} is the digest of exactly {@link #archive()}; the bytes are copied in and
     * out.
     */
    public static final class Bundle {
        private final Coordinates root;
        private final int packCount;
        private final String sha256;
        private final byte[] archive;

        Bundle(Coordinates root, int packCount, String sha256, byte[] archive) {
            this.root = root;
            this.packCount = packCount;
            this.sha256 = sha256;
            this.archive = archive.clone();
        }

        /** @return coordinates of the exported revision */
        public Coordinates root() {
            return root;
        }

        /** @return packs carried, the root included */
        public int packCount() {
            return packCount;
        }

        /** @return lowercase hexadecimal SHA-256 of {@link #archive()} */
        public String sha256() {
            return sha256;
        }

        /** @return a copy of the ZIP archive */
        public byte[] archive() {
            return archive.clone();
        }
    }
}
