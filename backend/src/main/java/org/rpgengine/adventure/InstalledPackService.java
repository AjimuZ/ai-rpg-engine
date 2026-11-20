package org.rpgengine.adventure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.content.PackException;
import org.rpgengine.content.PackTextParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * List the sealed content revisions installed in a trusted owner's adventures, with public author
 * metadata only (docs/13, {@code GET /api/v1/packs}, ADR 0047).
 *
 * <p>A revision is listed only when it is sealed (its import report exists), because only a sealed
 * revision can pin a campaign. Each row carries the identities a client needs to create a playthrough
 * and the manifest's author-declared {@code title}, {@code summary}, {@code authors} and {@code license}
 * in the pack's source language. Rules, lore, secrets, file lists, hashes, import diagnostics and owner
 * identity are never returned. Reads use one repeatable-read snapshot and never write.
 */
@Service
public class InstalledPackService {
    private static final String SELECT = "select c.id, c.adventure_id, c.pack_id, c.pack_version, c.source_locale, "
            + "c.admitted_at, f.path, f.content from content_revision c "
            + "join adventure_workspace w on w.id = c.adventure_id "
            + "join content_revision_report r on r.content_revision_id = c.id "
            + "join content_revision_file f on f.content_revision_id = c.id and f.category = 'manifest' "
            + "where w.owner_id = ? and (?::uuid is null or c.adventure_id = ?) and (?::uuid is null or c.id > ?) "
            + "order by c.id limit ?";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads;

    /** Require persistence infrastructure and configure bounded read-only snapshots. */
    public InstalledPackService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        reads = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(15);
    }

    /**
     * Return at most {@code limit} sealed revisions of the owner, ordered by revision UUID after an optional
     * exclusive cursor.
     *
     * @param owner the admitted owner; never taken from the client
     * @param adventure optional filter; when present it must be an owned adventure
     * @param after optional exclusive revision cursor; an ordering boundary, not a credential
     * @param limit page size, 1..100
     * @return one detached page; {@code nextAfter} is null when this snapshot has no further rows
     * @throws IllegalArgumentException for an invalid page size
     * @throws AdventureLibraryService.ScopeUnavailable when the filter names a missing or foreign adventure
     * @throws IllegalStateException when a sealed manifest no longer has the shape its schema guarantees
     */
    public Listing list(UUID owner, UUID adventure, UUID after, int limit) {
        Objects.requireNonNull(owner, "owner");
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("invalid page size"); }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("independent pack snapshot required");
        }
        return reads.execute(status -> {
            // Missing and foreign adventures share one answer, decided before any revision row is read.
            if (adventure != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from adventure_workspace where id = ? and owner_id = ?)", Boolean.class,
                    adventure, owner))) {
                throw new AdventureLibraryService.ScopeUnavailable();
            }
            var rows = jdbc.query(SELECT, InstalledPackService::pack, owner, adventure, adventure, after, after, limit + 1);
            boolean more = rows.size() > limit;
            var page = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new Listing(page, more ? page.getLast().contentRevisionId() : null);
        });
    }

    /** Map one sealed row; only allowlisted manifest fields leave this method. */
    private static Pack pack(ResultSet row, int index) throws SQLException {
        Map<?, ?> manifest;
        try {
            // The stored manifest passed the bounded parser and the schema when it was sealed; its stored path
            // (adventure.json or adventure.yaml) selects the same syntax again.
            if (!(new PackTextParser().parse(row.getString("path"), row.getBytes("content")).value()
                    instanceof Map<?, ?> values)) {
                throw new IllegalStateException("sealed manifest is not an object");
            }
            manifest = values;
        } catch (PackException failure) {
            throw new IllegalStateException("sealed manifest is unreadable", failure);
        }
        return new Pack(row.getObject("id", UUID.class), row.getObject("adventure_id", UUID.class),
                row.getString("pack_id"), row.getString("pack_version"), row.getString("source_locale"),
                row.getTimestamp("admitted_at").toInstant(), text(manifest, "title"), text(manifest, "summary"),
                authors(manifest.get("authors")), text(manifest, "license"));
    }

    /** Read one required manifest string; any other shape means the sealed bytes are not trustworthy. */
    private static String text(Map<?, ?> manifest, String field) {
        if (!(manifest.get(field) instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("sealed manifest lacks " + field);
        }
        return value;
    }

    /** Read the required non-empty author list of strings. */
    private static List<String> authors(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()
                || !list.stream().allMatch(item -> item instanceof String text && !text.isBlank())) {
            throw new IllegalStateException("sealed manifest lacks authors");
        }
        return list.stream().map(String.class::cast).toList();
    }

    /**
     * One sealed revision as a client may see it.
     *
     * @param contentRevisionId the identity {@code POST /api/v1/campaigns} pins
     * @param adventureId the owning adventure
     * @param sourceLocale language of the author metadata, {@code en} or {@code it}
     * @param admittedAt when the revision was sealed
     */
    public record Pack(UUID contentRevisionId, UUID adventureId, String packId, String packVersion,
            String sourceLocale, Instant admittedAt, String title, String summary, List<String> authors,
            String license) {
        /** Detach the author list. */
        public Pack { authors = List.copyOf(authors); }
    }

    /** Immutable bounded page; {@code nextAfter} is null at the end. */
    public record Listing(List<Pack> packs, UUID nextAfter) {
        /** Detach supplied rows so callers cannot mutate the page. */
        public Listing { packs = List.copyOf(packs); }
    }
}
