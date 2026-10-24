package org.rpgengine.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.rpgengine.presentation.SupportedLocale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable pack registration: the last steps of the docs/09 section 9 import pipeline.
 *
 * <p>Input is always a {@link PackStaging.StagedPack}, so path, link, archive and size protections
 * have already run. This service parses, compiles and media-checks the staged bytes, then stores one
 * sealed {@link ContentRevision} with every file and a deterministic import report in a single
 * transaction. Nothing is written when any blocking diagnostic remains, and nothing that was written
 * can change afterwards: V7 triggers reject updates, deletes and late file inserts, and V7 checks
 * recompute each stored file's size and SHA-256 from its bytes.
 *
 * <p>Dependencies resolve only against packs already registered in the same adventure. A pack's
 * exports are all of its local definition IDs with their kinds; nothing is ever fetched.
 */
@Service
public class PackRegistry {
    /** Report schema revision stored with every registration; bump it when the document shape changes. */
    static final int REPORT_VERSION = 2;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate reads;
    private final EngineContract contract;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Create the registry for this engine build's contract.
     *
     * @param jdbc PostgreSQL access
     * @param manager transaction manager shared with the canonical services
     */
    @Autowired
    public PackRegistry(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, EngineContract.current());
    }

    /**
     * Create the registry for an explicit engine contract (tests use other rulesets or modules).
     *
     * @param jdbc PostgreSQL access
     * @param manager transaction manager
     * @param contract engine contract packs are compiled against and whose ruleset hash is recorded
     */
    PackRegistry(JdbcTemplate jdbc, PlatformTransactionManager manager, EngineContract contract) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.contract = Objects.requireNonNull(contract, "contract");
        transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // Up to 250 MiB of file rows is written in one transaction, so the bound is wider than
        // the ten seconds of canonical commits while still finite.
        transactions.setTimeout(120);
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads.setReadOnly(true);
        reads.setTimeout(60);
    }

    /**
     * Validate a staged pack and return its import report without writing anything (docs/09 step 10).
     *
     * @param adventureId adventure whose registered packs satisfy dependencies
     * @param staged protected inventory from {@link PackStaging}
     * @return the report a registration of the same bytes would store
     * @throws PackException on the first blocking diagnostic, including {@code HASH_CONFLICT}
     */
    public Inspection preview(UUID adventureId, PackStaging.StagedPack staged) {
        Objects.requireNonNull(adventureId, "adventureId");
        Prepared prepared = prepare(staged);
        requireNoTransaction();
        return reads.execute(status -> {
            boundWaits();
            requireAdventure(adventureId, false);
            return inspect(adventureId, prepared, staged.source());
        });
    }

    /**
     * Register a staged pack as an immutable content revision of one adventure.
     *
     * <p>The adventure row is locked first, so concurrent registrations of one adventure are serial
     * and the existence check below cannot race. Repeating a registration with identical bytes is
     * idempotent and returns the existing revision; the same pack ID and version with other bytes
     * fails with {@code HASH_CONFLICT}, naming both hashes, and writes nothing.
     *
     * @param adventureId owning adventure
     * @param staged protected inventory from {@link PackStaging}
     * @return the new or existing revision
     * @throws PackException on any blocking diagnostic; the transaction then writes nothing
     * @throws IllegalArgumentException when the adventure does not exist
     */
    public Registration register(UUID adventureId, PackStaging.StagedPack staged) {
        Objects.requireNonNull(adventureId, "adventureId");
        // Parsing and media checks are pure CPU work and run before any lock is taken.
        Prepared prepared = prepare(staged);
        requireNoTransaction();
        return transactions.execute(status -> {
            boundWaits();
            requireAdventure(adventureId, true);
            var parsed = prepared.parsed();
            var existing = jdbc.query("select id, content_hash from content_revision "
                    + "where adventure_id = ? and pack_id = ? and pack_version = ?",
                    (row, index) -> Map.entry(row.getObject(1, UUID.class), row.getString(2)),
                    adventureId, parsed.manifest().id(), version(parsed));
            if (!existing.isEmpty() && existing.getFirst().getValue().equals(parsed.contentHash())) {
                // Legacy metadata alone never proves that these bytes passed the admission boundary.
                if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from content_revision_report "
                        + "where content_revision_id = ?)", Boolean.class, existing.getFirst().getKey()))) {
                    throw new PackException("UNSEALED_REVISION", parsed.documents().get(root(parsed)).location("/version"),
                            "Existing revision has no sealed file inventory; import under a new pack version");
                }
                return new Registration(existing.getFirst().getKey(), false, coordinates(parsed));
            }
            // A differing hash reaches the compiler, which reports HASH_CONFLICT with both digests.
            Inspection inspection = inspect(adventureId, prepared, staged.source());
            UUID id = UUID.randomUUID();
            var pack = inspection.pack();
            var ruleset = inspection.ruleset();
            jdbc.update("insert into content_revision (id, adventure_id, pack_id, pack_version, content_hash, "
                    + "schema_version, ruleset_id, ruleset_version, ruleset_hash, source_locale, admitted_at) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())", id, adventureId, pack.packId(),
                    pack.packVersion(), pack.contentHash(), pack.schemaVersion(), ruleset.rulesetId(),
                    ruleset.rulesetVersion(), ruleset.rulesetHash(), pack.sourceLocale().tag());
            for (FileRow file : prepared.files()) {
                // The stored digest comes from the parser's private copy and the database recomputes it
                // from the inserted bytes, so bytes changed after parsing fail the check and roll back.
                jdbc.update("insert into content_revision_file (content_revision_id, path, category, byte_size, "
                        + "sha256, media_type, content) values (?, ?, ?, ?, ?, ?, ?)", id, file.path(), file.category(),
                        (long) file.bytes().length, parsed.fileHashes().get(file.path()), file.mediaType(), file.bytes());
            }
            // Inserting the report seals the revision: no further file rows are accepted.
            jdbc.update("insert into content_revision_report (content_revision_id, report_version, file_count, "
                    + "total_bytes, report, exports) values (?, ?, ?, ?, ?::jsonb, ?::jsonb)", id, REPORT_VERSION,
                    prepared.files().size(), prepared.totalBytes(), inspection.report(), inspection.exports());
            return new Registration(id, true, pack);
        });
    }

    /**
     * Read the stored import report of a registered revision.
     *
     * @param revisionId content revision
     * @return the report JSON, or empty for unknown revisions and revisions created before P04.4
     */
    public Optional<String> report(UUID revisionId) {
        Objects.requireNonNull(revisionId, "revisionId");
        requireNoTransaction();
        return reads.execute(status -> {
            boundWaits();
            return jdbc.query("select report::text from content_revision_report where content_revision_id = ?",
                    (row, index) -> row.getString(1), revisionId).stream().findFirst();
        });
    }

    /**
     * Reload the exact stored files of a revision and prove they still produce its content hash.
     *
     * @param revisionId content revision
     * @return sorted path to bytes, or empty for unknown revisions and revisions without stored files
     * @throws IllegalStateException when the stored files no longer reproduce the recorded hash or report
     */
    public Optional<Map<String, byte[]>> files(UUID revisionId) {
        Objects.requireNonNull(revisionId, "revisionId");
        requireNoTransaction();
        return reads.execute(status -> {
            boundWaits();
            var recorded = jdbc.query("select c.content_hash, r.file_count from content_revision c "
                    + "join content_revision_report r on r.content_revision_id = c.id where c.id = ?",
                    (row, index) -> Map.entry(row.getString(1), row.getInt(2)), revisionId);
            if (recorded.isEmpty()) {
                return Optional.<Map<String, byte[]>>empty();
            }
            Map<String, byte[]> files = new TreeMap<>();
            jdbc.query("select path, content from content_revision_file where content_revision_id = ? order by path",
                    (java.sql.ResultSet row) -> {
                        files.put(row.getString(1), row.getBytes(2));
                    }, revisionId);
            // Triggers already forbid changes; this end-to-end check also catches restored or
            // hand-edited databases before stale bytes reach a campaign.
            if (files.size() != recorded.getFirst().getValue()
                    || !PackHashes.calculate(files).contentHash().equals(recorded.getFirst().getKey())) {
                throw new IllegalStateException("stored pack files do not match content revision " + revisionId);
            }
            return Optional.of(java.util.Collections.unmodifiableMap(files));
        });
    }

    /**
     * Reload and recompile a sealed adventure-owned revision and its bounded pinned dependency
     * closure inside a caller's transaction.
     * The caller must authorize the owner first. Returns privileged immutable definitions;
     * never expose them as a public projection. Missing/unsealed revisions, changed hashes
     * or an unavailable pinned ruleset fail before campaign writes. Conflicting pack versions,
     * cycles and aggregate closure limits also fail closed. No filesystem is read.
     */
    public CampaignPackSnapshot loadForCampaign(UUID adventureId, UUID revisionId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("campaign content loading requires a transaction");
        }
        var closure = new CampaignClosure(adventureId);
        CompiledPack root = closure.load(revisionId, 0);
        return new CampaignPackSnapshot(root, closure.packs);
    }

    /**
     * Load one sealed revision under the caller's authorized transaction. Reparse and recompile
     * its exact bytes; missing rows, incompatible rulesets or mismatched hashes fail closed.
     */
    private CompiledPack loadRevision(UUID adventureId, UUID revisionId) {
        var hashes = jdbc.query("select c.content_hash, c.ruleset_hash from content_revision c "
                + "join content_revision_report r on r.content_revision_id = c.id "
                + "where c.adventure_id = ? and c.id = ?",
                (row, index) -> Map.entry(row.getString(1), row.getString(2)), adventureId, revisionId);
        if (hashes.isEmpty()) {
            throw new IllegalArgumentException("sealed content revision is unavailable in this adventure");
        }
        if (!contract.rulesetHash().equals(hashes.getFirst().getValue())) {
            throw new IllegalArgumentException("pinned ruleset is unavailable in this engine build");
        }
        Map<String, byte[]> files = new TreeMap<>();
        jdbc.query("select path, content from content_revision_file where content_revision_id = ? order by path",
                (org.springframework.jdbc.core.RowCallbackHandler) row ->
                        files.put(row.getString(1), row.getBytes(2)), revisionId);
        ParsedPack parsed = new PackParser().parse(files);
        if (!parsed.contentHash().equals(hashes.getFirst().getKey())) {
            throw new IllegalStateException("stored pack files do not match the pinned content hash");
        }
        return new PackCompiler(new Catalog(adventureId), contract).compile(parsed);
    }

    /**
     * Per-load bounded traversal, never shared between campaigns. Exact dependency coordinates
     * come only from sealed manifests, so later registrations cannot move an existing snapshot.
     * Conflicting versions of one pack ID are rejected because public definition references use
     * pack IDs rather than versions and must remain unambiguous throughout a campaign.
     */
    private final class CampaignClosure {
        private final UUID adventureId;
        private final Map<String, CompiledPack> packs = new LinkedHashMap<>();
        private final Map<UUID, CompiledPack> loaded = new LinkedHashMap<>();
        private final Map<UUID, Integer> heights = new LinkedHashMap<>();
        private final java.util.Set<UUID> visiting = new java.util.HashSet<>();
        private long bytes;

        /** Initialize an empty traversal scoped to an already authorized adventure. */
        CampaignClosure(UUID adventureId) {
            this.adventureId = adventureId;
        }

        /**
         * Verify a revision and its transitive dependencies once. Reject cycles, ambiguous pack
         * versions, more than 32 revisions, depth above 16 or over 250 MiB before parsing more
         * bytes. These aggregate limits prevent many individually bounded packs exhausting memory.
         */
        CompiledPack load(UUID revisionId, int depth) {
            if (depth + heights.getOrDefault(revisionId, 0) > 16) {
                throw new IllegalArgumentException("campaign dependency depth limit exceeded");
            }
            if (loaded.containsKey(revisionId)) {
                return loaded.get(revisionId);
            }
            if (!visiting.add(revisionId)) {
                throw new IllegalArgumentException("campaign dependency cycle detected");
            }
            if (loaded.size() + visiting.size() > 32) {
                throw new IllegalArgumentException("campaign dependency count limit exceeded");
            }
            bytes += jdbc.queryForObject("select coalesce(sum(byte_size), 0) from content_revision_file "
                    + "where content_revision_id = ?", Long.class, revisionId);
            if (bytes > 250L * 1024 * 1024) {
                throw new IllegalArgumentException("campaign dependency byte limit exceeded");
            }
            CompiledPack pack = loadRevision(adventureId, revisionId);
            String packId = pack.parsed().manifest().id();
            CompiledPack previous = packs.putIfAbsent(packId, pack);
            if (previous != null) {
                throw new IllegalArgumentException("campaign dependency has conflicting versions: " + packId);
            }
            int height = 0;
            for (var dependency : CampaignPackSnapshot.dependencies(pack)) {
                var revisions = jdbc.query("select c.id from content_revision c "
                        + "join content_revision_report r on r.content_revision_id = c.id "
                        + "where c.adventure_id = ? and c.pack_id = ? and c.pack_version = ? and c.content_hash = ?",
                        (row, index) -> row.getObject(1, UUID.class), adventureId, dependency.get("id"),
                        dependency.get("version"), dependency.get("hash"));
                if (revisions.isEmpty()) {
                    throw new IllegalArgumentException("pinned sealed dependency is unavailable in this adventure");
                }
                UUID child = revisions.getFirst();
                load(child, depth + 1);
                // A shared subtree loaded earlier at a shallower depth must not let a longer
                // diamond path evade the depth cap when its bytes are reused from this cache.
                height = Math.max(height, 1 + heights.get(child));
            }
            visiting.remove(revisionId);
            loaded.put(revisionId, pack);
            heights.put(revisionId, height);
            return pack;
        }
    }

    /** Parse the staged bytes and check every asset; no database access, so no lock is held meanwhile. */
    private static Prepared prepare(PackStaging.StagedPack staged) {
        Objects.requireNonNull(staged, "staged");
        Map<String, byte[]> snapshot = staged.files();
        ParsedPack parsed = new PackParser().parse(snapshot);
        Map<String, String> categories = new LinkedHashMap<>();
        categories.put(parsed.documents().containsKey("adventure.yaml") ? "adventure.yaml" : "adventure.json", "manifest");
        parsed.manifest().files().forEach(file -> categories.put(file.path(), file.category()));
        List<FileRow> files = new ArrayList<>();
        long total = 0;
        // Sorted path order keeps report and insert order independent of the manifest's listing order.
        for (var entry : new TreeMap<>(categories).entrySet()) {
            byte[] bytes = snapshot.get(entry.getKey());
            PackMedia.Media media = entry.getValue().equals("asset") ? PackMedia.inspect(entry.getKey(), bytes) : null;
            files.add(new FileRow(entry.getKey(), entry.getValue(), bytes, media));
            total += bytes.length;
        }
        return new Prepared(parsed, List.copyOf(files), total);
    }

    /** Compile against this adventure's registered packs and build the deterministic report. */
    private Inspection inspect(UUID adventureId, Prepared prepared, PackStaging.Source source) {
        CompiledPack compiled = new PackCompiler(new Catalog(adventureId), contract).compile(prepared.parsed());
        var pack = coordinates(prepared.parsed());
        var ruleset = new ContentRevision.RulesetCoordinates(contract.rulesetId(), contract.rulesetVersion(),
                contract.rulesetHash());
        Map<String, String> exports = new TreeMap<>();
        Map<String, Integer> kinds = new TreeMap<>();
        for (Definition definition : compiled.definitions()) {
            exports.put(definition.id(), definition.kind());
            kinds.merge(definition.kind(), 1, Integer::sum);
        }
        // Field order is fixed and no timestamp, host path or source kind is included, so the same
        // bytes under the same engine contract always yield the same document.
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportVersion", REPORT_VERSION);
        report.put("pack", ordered("id", pack.packId(), "version", pack.packVersion(), "contentHash",
                pack.contentHash(), "schemaVersion", pack.schemaVersion(), "contentLocale", pack.sourceLocale().tag()));
        report.put("ruleset", ordered("id", ruleset.rulesetId(), "version", ruleset.rulesetVersion(), "hash",
                ruleset.rulesetHash()));
        report.put("fileCount", prepared.files().size());
        report.put("totalBytes", prepared.totalBytes());
        List<Map<String, Object>> files = new ArrayList<>();
        for (FileRow file : prepared.files()) {
            Map<String, Object> row = ordered("path", file.path(), "category", file.category(), "bytes",
                    file.bytes().length, "sha256", prepared.parsed().fileHashes().get(file.path()));
            if (file.media() != null) {
                row.put("mediaType", file.media().mediaType());
                if (file.media().width() > 0) {
                    row.put("width", file.media().width());
                    row.put("height", file.media().height());
                }
            }
            files.add(row);
        }
        report.put("files", files);
        report.put("definitions", kinds);
        report.put("localization", compiled.localization().report());
        List<Map<String, Object>> warnings = new ArrayList<>();
        for (PackWarning warning : compiled.warnings()) {
            warnings.add(ordered("code", warning.code(), "file", warning.location().file(), "line",
                    warning.location().line(), "column", warning.location().column(), "pointer",
                    warning.location().pointer(), "message", warning.message()));
        }
        report.put("warnings", warnings);
        return new Inspection(source, pack, ruleset, json.writeValueAsString(report),
                json.writeValueAsString(exports), compiled.warnings());
    }

    /** Insertion-ordered map from alternating keys and values. */
    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    /**
     * Revision coordinates from the manifest. Content locales outside the engine's supported set
     * fail here with a located diagnostic instead of an unchecked database or enum error.
     */
    private static ContentRevision.PackCoordinates coordinates(ParsedPack parsed) {
        var values = parsed.manifest().values();
        SupportedLocale locale;
        try {
            locale = SupportedLocale.fromTag((String) values.get("contentLocale"));
        } catch (IllegalArgumentException unsupported) {
            throw new PackException("UNSUPPORTED_LOCALE", parsed.documents().get(root(parsed)).location("/contentLocale"),
                    "Content locale must be one of en, it");
        }
        return new ContentRevision.PackCoordinates(parsed.manifest().id(), version(parsed), parsed.contentHash(),
                ((Number) values.get("schemaVersion")).intValue(), locale);
    }

    /** Declared exact pack version; the schema guarantees a string. */
    private static String version(ParsedPack parsed) {
        return (String) parsed.manifest().values().get("version");
    }

    /** Root manifest file name; exactly one of the two names exists after parsing. */
    private static String root(ParsedPack parsed) {
        return parsed.documents().containsKey("adventure.yaml") ? "adventure.yaml" : "adventure.json";
    }

    /**
     * Require the adventure row, optionally locking it.
     *
     * @param lock true to take {@code FOR NO KEY UPDATE}, which serializes registrations of one
     *             adventure without blocking foreign-key checks of unrelated inserts
     */
    private void requireAdventure(UUID adventureId, boolean lock) {
        var found = jdbc.query("select id from adventure_workspace where id = ?" + (lock ? " for no key update" : ""),
                (row, index) -> row.getObject(1, UUID.class), adventureId);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("unknown adventure");
        }
    }

    /** Bound lock waits and single statements; one file row may carry up to 32 MiB. */
    private void boundWaits() {
        jdbc.execute("set local lock_timeout = '2s'");
        jdbc.execute("set local statement_timeout = '30s'");
    }

    /**
     * Reject an ambient transaction: joining one would hold the adventure lock across unrelated
     * work or report a registration before the caller actually commits.
     */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("pack registration requires no ambient transaction");
        }
    }

    /**
     * Dependency lookup scoped to one adventure, read inside the caller's transaction. Only sealed
     * revisions (with a report) export definitions; legacy rows still expose their hash so a
     * changed ID/version is reported as {@code HASH_CONFLICT}.
     */
    private final class Catalog implements DependencyCatalog {
        private final UUID adventureId;

        /** @param adventureId adventure whose registered packs are visible */
        Catalog(UUID adventureId) {
            this.adventureId = adventureId;
        }

        /** {@inheritDoc} */
        @Override
        @SuppressWarnings("unchecked")
        public Optional<Map<String, String>> exports(String packId, String version, String hash) {
            return jdbc.query("select r.exports::text from content_revision c join content_revision_report r "
                    + "on r.content_revision_id = c.id where c.adventure_id = ? and c.pack_id = ? "
                    + "and c.pack_version = ? and c.content_hash = ?", (row, index) -> row.getString(1),
                    adventureId, packId, version, hash).stream().findFirst()
                    .map(text -> Map.copyOf((Map<String, String>) json.readValue(text, Map.class)));
        }

        /** {@inheritDoc} */
        @Override
        public Optional<String> installedHash(String packId, String version) {
            return jdbc.query("select content_hash from content_revision where adventure_id = ? and pack_id = ? "
                    + "and pack_version = ?", (row, index) -> row.getString(1), adventureId, packId, version)
                    .stream().findFirst();
        }
    }

    /** Parsed pack plus its sorted file rows; built before any transaction starts. */
    private record Prepared(ParsedPack parsed, List<FileRow> files, long totalBytes) {
    }

    /** One file to store; media is present only for the {@code asset} category. */
    private record FileRow(String path, String category, byte[] bytes, PackMedia.Media media) {
        /** @return canonical media type for assets, otherwise null */
        String mediaType() {
            return media == null ? null : media.mediaType();
        }
    }

    /**
     * Validation result of a staged pack.
     *
     * @param source where the staged bytes came from; reported to the caller but never stored
     * @param pack revision coordinates the pack would be stored with
     * @param ruleset ruleset coordinates of this engine
     * @param report deterministic import report JSON
     * @param exports definition ID to kind map, as JSON, that dependent packs resolve against
     * @param warnings non-blocking findings, also listed in the report
     */
    public record Inspection(PackStaging.Source source, ContentRevision.PackCoordinates pack,
            ContentRevision.RulesetCoordinates ruleset, String report, String exports, List<PackWarning> warnings) {
        /** Copy the warning list. */
        public Inspection {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Outcome of a registration.
     *
     * @param revisionId stored content revision
     * @param created false when identical bytes were already registered and nothing was written
     * @param pack coordinates of the revision
     */
    public record Registration(UUID revisionId, boolean created, ContentRevision.PackCoordinates pack) {
    }
}
