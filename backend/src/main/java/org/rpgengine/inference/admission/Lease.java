package org.rpgengine.inference.admission;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Snapshot of a lease: the right to keep one model resident, and its capacity reserved, until
 * {@code expiresAt} unless renewed.
 *
 * @param id lease identity
 * @param modality kind of model
 * @param model model identifier
 * @param demand capacity reserved, counted once for all members
 * @param members identities of the requests sharing the lease, in the order they joined
 * @param grantedAt grant time
 * @param expiresAt time at which the lease no longer protects its capacity
 */
public record Lease(UUID id, Modality modality, String model, Demand demand, List<UUID> members, Instant grantedAt,
        Instant expiresAt) {
    /** Copy the member list. */
    public Lease {
        members = List.copyOf(members);
    }
}
