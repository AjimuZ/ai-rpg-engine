package org.rpgengine.inference.admission;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored lease as {@link LeaseStore} reports it. It carries no member identities: which requests
 * shared a lease is not needed to recover its capacity, and a restart abandons the requests anyway.
 *
 * @param id lease identity
 * @param instance coordinator process that owns the row
 * @param modality kind of model
 * @param model model identifier
 * @param demand capacity reserved
 * @param batchSize members sharing the lease when last recorded
 * @param status lifecycle state
 * @param grantedAt grant time
 * @param expiresAt expiry as last recorded
 * @param finishedAt time the lease reached a terminal status, or null while open
 */
public record LeaseRecord(UUID id, UUID instance, Modality modality, String model, Demand demand, int batchSize,
        LeaseStatus status, Instant grantedAt, Instant expiresAt, Instant finishedAt) {
}
