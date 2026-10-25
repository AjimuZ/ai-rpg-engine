package org.rpgengine.adventure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read the persisted adventure library for a trusted owner without activating a world.
 * Inputs are opaque identities and bounded page controls; outputs are detached public
 * metadata, never entities, authored specifications or private child records. Reads use
 * independent committed snapshots; invalid input, unavailable scope and storage failures
 * propagate to the transport adapter without partial results.
 */
@Service
public class AdventureLibraryService {
    private static final String SELECT = "select id, title, genre, source_locale, row_version, created_at, updated_at "
            + "from adventure_workspace where owner_id=? ";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads;

    /** Require persistence infrastructure and configure bounded read-only snapshots; null dependencies fail construction. */
    public AdventureLibraryService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        reads = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(15);
    }

    /**
     * Return at most 1..100 owner workspaces, ordered by UUID after an optional exclusive
     * cursor. Empty workspaces are included because adventures are not packs or campaigns.
     * The cursor is an ordering boundary, not an authorization credential. Invalid limits
     * fail before SQL; a null nextAfter means this snapshot has no further rows.
     */
    public Listing list(UUID owner, UUID after, int limit) {
        Objects.requireNonNull(owner, "owner");
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("invalid page size"); }
        independent();
        return reads.execute(status -> {
            var rows = jdbc.query(SELECT + "and (?::uuid is null or id>?) order by id limit ?",
                    AdventureLibraryService::summary, owner, after, after, limit + 1);
            boolean more = rows.size() > limit;
            var page = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            return new Listing(page, more ? page.getLast().adventureId() : null);
        });
    }

    /**
     * Return public metadata for exactly one owned adventure. Missing and foreign IDs
     * share ScopeUnavailable; authorization is part of the SQL predicate, so no private
     * row or child data is loaded before ownership is established. No active selection is saved.
     */
    public Summary read(UUID owner, UUID adventure) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        independent();
        return reads.execute(status -> {
            var rows = jdbc.query(SELECT + "and id=?", AdventureLibraryService::summary, owner, adventure);
            if (rows.isEmpty()) { throw new ScopeUnavailable(); }
            return rows.getFirst();
        });
    }

    /** Refuse ambient transactions so a library read cannot expose uncommitted writes or inherit weaker isolation. */
    private static void independent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("independent library snapshot required");
        }
    }

    /** Map only allowlisted workspace metadata; SQL decoding failures abort the read rather than returning partial data. */
    private static Summary summary(ResultSet row, int index) throws SQLException {
        return new Summary(row.getObject("id", UUID.class), row.getString("title"), row.getString("genre"),
                row.getString("source_locale"), row.getLong("row_version"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }

    /** Public metadata for an opaque identity; metadataRevision is a storage token, never a campaign world counter. */
    public record Summary(UUID adventureId, String title, String genre, String sourceLocale, long metadataRevision,
            Instant createdAt, Instant updatedAt) { }

    /** Immutable bounded page; nextAfter is null at the end, and each later page is a new committed snapshot. */
    public record Listing(List<Summary> adventures, UUID nextAfter) {
        /** Detach supplied rows so callers cannot mutate the page; null lists/elements fail immediately. */
        public Listing { adventures = List.copyOf(adventures); }
    }

    /** Uniform inaccessible-scope result; never carries the requested identity or ownership details. */
    public static final class ScopeUnavailable extends RuntimeException {
        /** Construct a fixed internal failure without exposing which ownership condition failed. */
        public ScopeUnavailable() { super("adventure scope unavailable"); }
    }
}
