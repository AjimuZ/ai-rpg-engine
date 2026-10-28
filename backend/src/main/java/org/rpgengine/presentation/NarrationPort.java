package org.rpgengine.presentation;

import java.util.Objects;
import java.util.UUID;

/**
 * Runtime-neutral boundary through which a post-commit presentation job asks for narration of
 * one committed operation (P07.5). The request carries references only: an implementation must
 * read the audience-filtered resolved event bundle itself and may never receive or return world
 * mutations. Grounded narration, validation and repair are P08; until then the engine wires
 * {@link UnavailableNarration}, so every job ends with narration pending.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>Called outside any database transaction, at most once per job attempt, by the worker
 *       holding that attempt's lease. A call may repeat for a later attempt of the same job, so
 *       any durable artifact must be keyed by operation and attempt and be idempotent.</li>
 *   <li>Return within the job lease (30 seconds). A result delivered after the lease expired
 *       or after the job was settled elsewhere is discarded by the job service.</li>
 *   <li>{@link Outcome#DELIVERED} means a validated narration exists for exactly this operation
 *       and revision; publication is decided by the settling transaction, which discards it
 *       when cancellation was requested meanwhile.</li>
 *   <li>Throwing is equivalent to {@link Outcome#FAILED}; nothing is retried inside the call.</li>
 * </ul>
 */
public interface NarrationPort {
    /**
     * Narrate one committed operation.
     *
     * @param request references of the committed operation and attempt
     * @return delivery outcome; never null
     */
    Outcome narrate(Request request);

    /** Result of one narration attempt. */
    enum Outcome {
        /** Validated narration for this operation exists and may be published. */
        DELIVERED,
        /** No narration runtime is configured or ready; retrying now would not help. */
        UNAVAILABLE,
        /** A transient failure; the job retries while attempts remain. */
        FAILED
    }

    /**
     * Reference-only narration request.
     *
     * @param adventureId owning adventure
     * @param campaignId owning campaign
     * @param operationId committed operation to narrate
     * @param committedRevision revision the operation produced
     * @param attempt 1-based job attempt number
     */
    record Request(UUID adventureId, UUID campaignId, UUID operationId, long committedRevision, int attempt) {
        /** Reject incomplete references before any implementation sees them. */
        public Request {
            Objects.requireNonNull(adventureId, "adventureId");
            Objects.requireNonNull(campaignId, "campaignId");
            Objects.requireNonNull(operationId, "operationId");
            if (committedRevision <= 0 || attempt < 1) {
                throw new IllegalArgumentException("committed revision and attempt must be positive");
            }
        }
    }
}
