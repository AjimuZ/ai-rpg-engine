package org.rpgengine.memory;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for {@link CampaignCheckpoint}. */
public interface CampaignCheckpointRepository extends JpaRepository<CampaignCheckpoint, UUID> {
    /**
     * @param campaignId owning campaign
     * @return the checkpoint with the highest state revision, if any exists
     */
    Optional<CampaignCheckpoint> findTopByCampaignIdOrderByStateRevisionDesc(UUID campaignId);
}
