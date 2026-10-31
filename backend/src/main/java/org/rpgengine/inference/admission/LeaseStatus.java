package org.rpgengine.inference.admission;

/** Lifecycle of a stored lease; the names match the V29 {@code resource_lease.status} check. */
public enum LeaseStatus {
    /** Granted; its capacity is in use until the expiry unless renewed. */
    ACTIVE,
    /** Owned by an earlier process that may have left the model resident; capacity stays reserved until confirmed gone. */
    RECONCILING,
    /** The last member finished, or a reconciliation confirmed that the model is gone. */
    RELEASED,
    /** Nobody renewed it before its expiry. */
    EXPIRED,
    /** The coordinator took it back for more urgent work. */
    REVOKED;

    /** @return true for the statuses a lease can never leave */
    public boolean terminal() {
        return this == RELEASED || this == EXPIRED || this == REVOKED;
    }
}
