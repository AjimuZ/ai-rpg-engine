package org.rpgengine.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Base for mutable canonical entities: an engine-assigned identity plus an explicit
 * optimistic-locking version stored in {@code row_version} (V2 migration).
 *
 * <p>Hibernate adds {@code where row_version = ?} to every update and increments the
 * value, so two writers that loaded the same row cannot both succeed: the second one
 * fails with an optimistic-locking exception instead of silently overwriting the first.
 * Merging a detached copy with an older version fails the same way. The database also
 * requires every update to advance the version by exactly one. Raw SQL writers must
 * additionally compare their expected version in the WHERE clause and reject zero
 * affected rows; an increment alone cannot detect a stale read.
 *
 * <p>The row version is a storage concurrency token only. It never replaces the domain
 * counters of docs/05 section 1 (state revision, operation version, presentation
 * generations), which change only when the documented rule says so. Immutable rows
 * (events, checkpoints, settlements, content revisions) extend {@link AssignedIdEntity}
 * directly because they are never updated.
 */
@MappedSuperclass
public abstract class VersionedEntity extends AssignedIdEntity {
    // Managed by Hibernate; application code never assigns it.
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    /** Reserved for JPA. */
    protected VersionedEntity() {
    }

    /**
     * @param id the engine-assigned identity; must not be null
     * @throws NullPointerException if {@code id} is null
     */
    protected VersionedEntity(UUID id) {
        super(id);
    }

    /** @return the row version last read from or written to the database; 0 before the first update */
    public long getRowVersion() {
        return rowVersion;
    }
}
