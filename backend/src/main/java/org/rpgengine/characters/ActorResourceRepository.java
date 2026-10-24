package org.rpgengine.characters;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence port for {@link ActorResource}. The scoped lookup below includes the
 * campaign (QA-20); application boundaries must use it after checking ownership rather
 * than expose inherited unscoped administrative repository methods to callers.
 */
public interface ActorResourceRepository extends JpaRepository<ActorResource, UUID> {
    /**
     * @param campaignId owning campaign
     * @param actorId owning actor
     * @return that actor's resources ordered by key; empty if none or if the actor
     *         belongs to another campaign
     */
    List<ActorResource> findByCampaignIdAndActorIdOrderByResourceKeyAsc(UUID campaignId, UUID actorId);
}
