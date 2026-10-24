package org.rpgengine.progression;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence port for the reward ledger. A second settlement for the same source and
 * recipient in one campaign fails with a data-integrity exception.
 */
public interface RewardSettlementRepository extends JpaRepository<RewardSettlement, UUID> {
    /**
     * @param campaignId owning campaign
     * @param sourceKey reward source identity
     * @param recipientKey recipient identity
     * @return the existing settlement, if the reward was already paid
     */
    Optional<RewardSettlement> findByCampaignIdAndSourceKeyAndRecipientKey(UUID campaignId, String sourceKey,
            String recipientKey);
}
