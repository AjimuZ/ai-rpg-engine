package org.rpgengine.commands;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence port for the operation journal. Inserting a duplicate idempotency key in
 * the same campaign fails with a data-integrity exception from the unique constraint.
 */
public interface CampaignOperationRepository extends JpaRepository<CampaignOperation, UUID> {
    /**
     * @param campaignId owning campaign
     * @param idempotencyKey client-chosen key
     * @return the journaled operation for that key, if any
     */
    Optional<CampaignOperation> findByCampaignIdAndIdempotencyKey(UUID campaignId, String idempotencyKey);
}
