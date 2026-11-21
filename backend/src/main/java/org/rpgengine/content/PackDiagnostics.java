package org.rpgengine.content;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read-only author diagnostics for adventure packs (P16.1, docs/09 sections 9 to 11): one validation
 * report per candidate pack, the declared dependency graph against what the adventure has sealed, and
 * the registered versions of a pack.
 *
 * <p>Where {@link PackRegistry#preview} throws on the first blocking diagnostic, {@link #validate}
 * returns it as data together with everything that could still be established: the pack's standing
 * against registered versions and each declared dependency's state. Staging, parsing and compilation
 * failures all arrive as the same located {@link Diagnostic}. Nothing here writes, caches or reads the
 * filesystem beyond the caller's staging step. The caller must authorize the adventure's owner first.
 */
@Service
public class PackDiagnostics {
    private final PackRegistry registry;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads;
    private final String rulesetHash = EngineContract.current().rulesetHash();

    /**
     * Create the diagnostics over the registry of this engine build.
     *
     * @param registry registry whose preview and closure diagnosis are reused unchanged
     * @param jdbc PostgreSQL access
     * @param manager transaction manager for bounded read-only transactions
     */
    public PackDiagnostics(PackRegistry registry, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        reads = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads.setReadOnly(true);
        reads.setTimeout(60);
    }

    /**
     * Stage, parse and compile a candidate pack for one adventure and report the outcome without writing.
     *
     * <p>The staging step runs inside this call so that its diagnostics (traversal, links, archive and
     * size limits) are reported like parser and compiler ones; they locate by sanitized logical file
     * name only, at line 1, column 1 and the empty pointer, because no document exists yet. Once the
     * manifest parses, the pack's version standing and every declared dependency are reported even when
     * compilation then fails. A valid report carries exactly the import report a registration of the
     * same bytes would store.
     *
     * @param adventureId adventure whose sealed packs satisfy dependencies and hold earlier versions
     * @param staging the owner's staging step, for example {@code () -> staging.archive(input)}
     * @return the validation report; never null
     * @throws IllegalArgumentException when the adventure does not exist
     * @throws IllegalStateException inside an ambient transaction
     * @throws org.springframework.dao.DataAccessException for storage failures, which are never
     *         reported as author diagnostics
     */
    public ValidationReport validate(UUID adventureId, Supplier<PackStaging.StagedPack> staging) {
        Objects.requireNonNull(adventureId, "adventureId");
        Objects.requireNonNull(staging, "staging");
        requireNoTransaction();
        // An unknown adventure is a caller error, not an author diagnostic, even when staging would fail.
        read(() -> {
            if (jdbc.query("select 1 from adventure_workspace where id = ?", (row, index) -> 1, adventureId).isEmpty()) {
                throw new IllegalArgumentException("unknown adventure");
            }
            return null;
        });
        PackStaging.StagedPack staged;
        try {
            staged = staging.get();
        } catch (PackException failure) {
            return ValidationReport.invalid(Stage.STAGING, failure, null, List.of());
        }
        ParsedPack parsed;
        try {
            // The registry parses again; parsing is bounded CPU work and keeps its preview unchanged.
            parsed = new PackParser().parse(staged.files());
        } catch (PackException failure) {
            return ValidationReport.invalid(Stage.PARSING, failure, null, List.of());
        }
        Standing standing = read(() -> standing(adventureId, parsed));
        List<DependencyEdge> dependencies = dependencies(adventureId, parsed);
        try {
            var inspection = registry.preview(adventureId, staged);
            var warnings = inspection.warnings().stream().map(Diagnostic::of).toList();
            return new ValidationReport(Status.VALID, null, null, warnings, standing, dependencies, inspection.report());
        } catch (PackException failure) {
            return ValidationReport.invalid(Stage.COMPILATION, failure, standing, dependencies);
        }
    }

    /**
     * List every registered revision of one pack in an adventure, lowest version first.
     *
     * <p>Versions order by SemVer precedence; a legacy version that is not SemVer sorts after all
     * others, by text. The campaign count covers campaigns that pin the revision as their root;
     * campaigns reaching it only as a dependency are not counted.
     *
     * @param adventureId adventure to inspect
     * @param packId exact pack ID
     * @return registered versions, empty when the pack has none in this adventure
     * @throws IllegalStateException inside an ambient transaction
     */
    public List<RegisteredVersion> versions(UUID adventureId, String packId) {
        Objects.requireNonNull(adventureId, "adventureId");
        Objects.requireNonNull(packId, "packId");
        requireNoTransaction();
        return read(() -> registered(adventureId, packId));
    }

    /** Registered revisions of one pack, ordered as {@link #versions} promises. */
    private List<RegisteredVersion> registered(UUID adventureId, String packId) {
        var rows = jdbc.query("select c.id, c.pack_version, c.content_hash, r.report_version, c.ruleset_hash, "
                + "c.admitted_at, (select count(*) from campaign p where p.adventure_id = c.adventure_id "
                + "and p.content_revision_id = c.id) from content_revision c "
                + "left join content_revision_report r on r.content_revision_id = c.id "
                + "where c.adventure_id = ? and c.pack_id = ?",
                (row, index) -> {
                    Integer report = row.getObject(4, Integer.class);
                    return new RegisteredVersion(row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                            report != null, report, rulesetHash.equals(row.getString(5)),
                            row.getObject(6, java.time.OffsetDateTime.class).toInstant(), row.getInt(7));
                }, adventureId, packId);
        var sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(RegisteredVersion::version, PackDiagnostics::byPrecedence));
        return List.copyOf(sorted);
    }

    /** Order SemVer versions by precedence, ahead of non-SemVer text, which orders lexically. */
    private static int byPrecedence(String left, String right) {
        Versions.Version a = semver(left);
        Versions.Version b = semver(right);
        if (a != null && b != null) {
            int order = a.compareTo(b);
            return order != 0 ? order : left.compareTo(right);
        }
        if (a == null && b == null) {
            return left.compareTo(right);
        }
        return a == null ? 1 : -1;
    }

    /** @return the parsed version, or null when the text is not SemVer */
    private static Versions.Version semver(String text) {
        try {
            return Versions.version(text);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** Relation of the candidate's ID, version and hash to the revisions this adventure already holds. */
    private Standing standing(UUID adventureId, ParsedPack parsed) {
        String version = (String) parsed.manifest().values().get("version");
        var versions = registered(adventureId, parsed.manifest().id());
        Relation relation = Relation.NEW_PACK;
        String installed = null;
        for (RegisteredVersion existing : versions) {
            if (existing.version().equals(version)) {
                installed = existing.contentHash();
            }
        }
        if (installed != null) {
            relation = installed.equals(parsed.contentHash()) ? Relation.IDENTICAL : Relation.HASH_CONFLICT;
        } else if (!versions.isEmpty()) {
            relation = Relation.NEW_VERSION;
        }
        String highest = versions.stream().map(RegisteredVersion::version).filter(text -> semver(text) != null)
                .reduce((left, right) -> right).orElse(null);
        Versions.Version candidate = semver(version);
        boolean below = candidate != null && highest != null && candidate.compareTo(semver(highest)) < 0;
        return new Standing(parsed.manifest().id(), version, parsed.contentHash(), relation, installed, highest, below);
    }

    /**
     * Each declared dependency with its state in the adventure; the closure of a sealed one comes from
     * {@link PackRegistry#diagnose}, in its own read-only transaction.
     */
    @SuppressWarnings("unchecked")
    private List<DependencyEdge> dependencies(UUID adventureId, ParsedPack parsed) {
        var declared = (List<Map<String, Object>>) parsed.manifest().values().getOrDefault("dependencies", List.of());
        PackDocument manifest = parsed.documents().get(parsed.documents().containsKey("adventure.yaml")
                ? "adventure.yaml" : "adventure.json");
        List<DependencyEdge> edges = new ArrayList<>();
        for (int index = 0; index < declared.size(); index++) {
            var dependency = declared.get(index);
            String id = (String) dependency.get("id");
            String version = (String) dependency.get("version");
            String hash = (String) dependency.get("hash");
            var rows = read(() -> jdbc.query("select c.id, c.content_hash, exists(select 1 from content_revision_report r "
                    + "where r.content_revision_id = c.id) from content_revision c "
                    + "where c.adventure_id = ? and c.pack_id = ? and c.pack_version = ?",
                    (row, rowIndex) -> new Object[] {row.getObject(1, UUID.class), row.getString(2), row.getBoolean(3)},
                    adventureId, id, version));
            DependencyState state;
            String installed = null;
            ClosureDiagnosis closure = null;
            if (rows.isEmpty()) {
                state = DependencyState.MISSING;
            } else {
                Object[] row = rows.getFirst();
                installed = (String) row[1];
                if (!installed.equals(hash)) {
                    state = DependencyState.HASH_MISMATCH;
                } else if (!(boolean) row[2]) {
                    state = DependencyState.UNSEALED;
                } else {
                    state = DependencyState.SEALED;
                    closure = registry.diagnose(adventureId, (UUID) row[0]);
                }
            }
            edges.add(new DependencyEdge(manifest.location("/dependencies/" + index), id, version, hash, state,
                    installed, closure));
        }
        return List.copyOf(edges);
    }

    /** Run bounded reads in one read-only transaction. */
    private <T> T read(Supplier<T> work) {
        return reads.execute(status -> {
            jdbc.execute("set local statement_timeout = '30s'");
            return work.get();
        });
    }

    /** Reject an ambient transaction: the registry's preview and diagnosis open their own. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("pack diagnostics require no ambient transaction");
        }
    }

    /** Overall outcome. */
    public enum Status {
        /** No blocking diagnostic: registering the same bytes now would succeed. */
        VALID,
        /** One blocking diagnostic stops registration; see {@link ValidationReport#blocking()}. */
        INVALID
    }

    /** Pipeline step that produced the blocking diagnostic. */
    public enum Stage {
        /** Path, link, archive or size protection before any file was parsed; located by file name only. */
        STAGING,
        /** Syntax, encoding, schema, file reference or parser limit; located at the field. */
        PARSING,
        /** References, cycles, effects, versions, dependencies and other semantic checks; located at the field. */
        COMPILATION
    }

    /** How the candidate relates to the revisions of the same pack ID in the adventure. */
    public enum Relation {
        /** No version of this pack ID is registered. */
        NEW_PACK,
        /** Other versions are registered, this one is not. */
        NEW_VERSION,
        /** This version is registered with the same content hash; registering again changes nothing. */
        IDENTICAL,
        /** This version is registered with other bytes; a new version is required. */
        HASH_CONFLICT
    }

    /** State of one declared dependency in the adventure. */
    public enum DependencyState {
        /** A sealed revision matches the pinned ID, version and hash; see its closure. */
        SEALED,
        /** The pinned ID, version and hash exist only as legacy metadata without stored files. */
        UNSEALED,
        /** The pinned ID and version exist with another content hash. */
        HASH_MISMATCH,
        /** No revision of the pinned ID and version exists in the adventure. */
        MISSING
    }

    /**
     * One located finding.
     *
     * @param code stable machine-readable code, as in docs/09 section 10
     * @param location file, one-based line and column, and RFC 6901 pointer; never a host path
     * @param message author-facing explanation without source contents
     */
    public record Diagnostic(String code, SourceLocation location, String message) {
        /** Require every component. */
        public Diagnostic {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(message, "message");
        }

        /** @return the blocking diagnostic of a pack failure */
        static Diagnostic of(PackException failure) {
            return new Diagnostic(failure.code(), failure.location(), failure.explanation());
        }

        /** @return the non-blocking diagnostic of a compiler warning */
        static Diagnostic of(PackWarning warning) {
            return new Diagnostic(warning.code(), warning.location(), warning.message());
        }
    }

    /**
     * The candidate's version standing.
     *
     * @param packId declared pack ID
     * @param version declared version, possibly not SemVer when compilation reports {@code VERSION_SYNTAX}
     * @param contentHash content hash of the staged bytes
     * @param relation relation to registered versions
     * @param installedHash hash registered for this exact version, or null when it is not registered
     * @param highestRegistered highest registered SemVer version of the pack, or null when none
     * @param belowHighest true when the candidate precedes {@code highestRegistered}; allowed, because
     *        campaigns stay pinned, but usually a forgotten version bump
     */
    public record Standing(String packId, String version, String contentHash, Relation relation,
            String installedHash, String highestRegistered, boolean belowHighest) {
    }

    /**
     * One declared dependency.
     *
     * @param location where the manifest declares it ({@code /dependencies/<index>})
     * @param packId pinned pack ID
     * @param version pinned exact version
     * @param hash pinned content hash
     * @param state state in the adventure
     * @param installedHash hash registered for the pinned ID and version, or null when missing
     * @param closure whether the sealed dependency's own pinned closure loads, or null unless {@code SEALED}
     */
    public record DependencyEdge(SourceLocation location, String packId, String version, String hash,
            DependencyState state, String installedHash, ClosureDiagnosis closure) {
    }

    /**
     * One registered revision.
     *
     * @param revisionId content revision
     * @param version pack version
     * @param contentHash pinned content hash
     * @param sealed true when the revision has a stored file inventory and report
     * @param reportVersion stored report version, or null when unsealed
     * @param rulesetAvailable true when this engine build provides the ruleset the revision was sealed for
     * @param admittedAt admission time
     * @param campaigns campaigns of the adventure pinning this revision as their root
     */
    public record RegisteredVersion(UUID revisionId, String version, String contentHash, boolean sealed,
            Integer reportVersion, boolean rulesetAvailable, Instant admittedAt, int campaigns) {
    }

    /**
     * Outcome of {@link #validate}.
     *
     * @param status overall outcome
     * @param stage step that failed, or null when valid
     * @param blocking the blocking diagnostic, or null when valid
     * @param warnings non-blocking compiler findings; empty unless valid, because compilation stops at
     *        the first blocking diagnostic
     * @param standing version standing, or null when staging or parsing failed
     * @param dependencies declared dependencies in manifest order; empty when staging or parsing failed
     * @param report the import report JSON registration would store, or null when invalid
     */
    public record ValidationReport(Status status, Stage stage, Diagnostic blocking, List<Diagnostic> warnings,
            Standing standing, List<DependencyEdge> dependencies, String report) {
        /** Copy the lists and require the components that the status promises. */
        public ValidationReport {
            Objects.requireNonNull(status, "status");
            warnings = List.copyOf(warnings);
            dependencies = List.copyOf(dependencies);
            if ((status == Status.VALID) != (blocking == null && stage == null && report != null)) {
                throw new IllegalArgumentException("a valid report has a report and no blocking diagnostic");
            }
        }

        /** @return an invalid report with no warnings */
        static ValidationReport invalid(Stage stage, PackException failure, Standing standing,
                List<DependencyEdge> dependencies) {
            return new ValidationReport(Status.INVALID, Objects.requireNonNull(stage, "stage"), Diagnostic.of(failure),
                    List.of(), standing, dependencies, null);
        }
    }
}
