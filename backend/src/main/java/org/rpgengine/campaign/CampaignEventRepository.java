package org.rpgengine.campaign;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read and append port for {@link CampaignEvent}. Deleting through this repository
 * fails at the database because the event log is append-only.
 */
public interface CampaignEventRepository extends JpaRepository<CampaignEvent, UUID> {
    /**
     * @param campaignId owning campaign
     * @return that campaign's events in sequence order; empty if none
     */
    List<CampaignEvent> findByCampaignIdOrderBySequenceAsc(UUID campaignId);
}
