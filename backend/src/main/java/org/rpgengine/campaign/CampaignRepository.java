package org.rpgengine.campaign;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for {@link Campaign}; database errors surface as Spring data-access exceptions. */
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {
    /**
     * @param adventureId owning adventure
     * @return that adventure's campaigns, oldest first; empty if none
     */
    List<Campaign> findByAdventureIdOrderByCreatedAtAsc(UUID adventureId);
}
