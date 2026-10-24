package org.rpgengine.characters;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for {@link Actor}. */
public interface ActorRepository extends JpaRepository<Actor, UUID> {
    /**
     * @param campaignId owning campaign
     * @return that campaign's actors, oldest first; empty if none
     */
    List<Actor> findByCampaignIdOrderByCreatedAtAsc(UUID campaignId);
}
