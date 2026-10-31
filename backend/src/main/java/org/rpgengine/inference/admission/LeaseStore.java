package org.rpgengine.inference.admission;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable record of the coordinator's leases (V29 {@code resource_lease}), so a restart finds the
 * capacity that an earlier process may have left in use. Only the process that owns a row, named by
 * {@code instance}, may change it; every other call reports {@code false} instead of changing it.
 * Implementations are used from the coordinator's serialized calls.
 */
public interface LeaseStore {
    /**
     * Record a new lease as ACTIVE, with {@code lease.members().size()} members.
     *
     * @throws RuntimeException when the row cannot be written; the lease then does not exist
     */
    void grant(UUID instance, Lease lease);

    /** @return true when the active lease of {@code instance} now records {@code size} members (1..64) */
    boolean resize(UUID leaseId, UUID instance, int size);

    /** @return true when the active, unexpired lease of {@code instance} now ends at {@code expiresAt} or later */
    boolean renew(UUID leaseId, UUID instance, Instant expiresAt, Instant now);

    /**
     * End an active lease.
     *
     * @param terminal {@link LeaseStatus#RELEASED}, {@link LeaseStatus#EXPIRED} or {@link LeaseStatus#REVOKED}
     * @return true when the lease was active under {@code instance} and is now ended
     * @throws IllegalArgumentException for any other status
     */
    boolean settle(UUID leaseId, UUID instance, LeaseStatus terminal, Instant now);

    /**
     * Take over every open lease of another process: each becomes RECONCILING under {@code instance},
     * because a model that process loaded may still be resident.
     *
     * @return the taken-over leases, whose capacity the caller must keep reserved
     */
    List<LeaseRecord> recover(UUID instance, Instant now);

    /** @return true when a RECONCILING lease of {@code instance} is now RELEASED, its model confirmed gone */
    boolean confirmUnloaded(UUID leaseId, UUID instance, Instant now);

    /** @return ACTIVE and RECONCILING leases, oldest grant first */
    List<LeaseRecord> open();

    /** @return the status of a stored lease, or empty when unknown */
    Optional<LeaseStatus> status(UUID leaseId);
}
