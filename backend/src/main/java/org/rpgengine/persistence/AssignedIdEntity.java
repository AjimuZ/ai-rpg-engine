package org.rpgengine.persistence;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

import org.hibernate.Hibernate;
import org.springframework.data.domain.Persistable;

/**
 * Base for canonical entities whose opaque UUID identity is assigned by the engine.
 *
 * <p>Identities are created before the first insert, so Spring Data cannot use a null
 * ID to detect new rows. Without {@link Persistable} every {@code save} of a new row
 * would first run a SELECT and then a merge; with it, new rows are inserted directly
 * and a duplicate ID fails as a constraint violation instead of overwriting data.
 * Equality is identity-based, which keeps entities stable in sets across reloads.
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Not persisted: true until the row was inserted by, or loaded from, the database.
    @Transient
    private boolean newEntity = true;

    /** Reserved for JPA; loaded instances are marked as not new by {@link #markPersisted()}. */
    protected AssignedIdEntity() {
    }

    /**
     * @param id the engine-assigned identity; must not be null
     * @throws NullPointerException if {@code id} is null
     */
    protected AssignedIdEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    /** @return the immutable opaque identity of this row */
    @Override
    public UUID getId() {
        return id;
    }

    /** @return true while the row has never been inserted or loaded in this instance */
    @Override
    public boolean isNew() {
        return newEntity;
    }

    /** JPA callback: after insert or load the next save must update, not insert again. */
    @PostPersist
    @PostLoad
    void markPersisted() {
        newEntity = false;
    }

    /**
     * Identity equality. Hibernate.getClass unwraps lazy proxies so a proxy and its
     * loaded instance compare equal; an unassigned identity never equals anything else.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
            return false;
        }
        UUID otherId = ((AssignedIdEntity) other).getId();
        return id != null && id.equals(otherId);
    }

    /** Class-stable hash so the value never changes during an entity's lifetime. */
    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
