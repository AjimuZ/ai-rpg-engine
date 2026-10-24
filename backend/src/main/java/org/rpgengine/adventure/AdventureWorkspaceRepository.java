package org.rpgengine.adventure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for {@link AdventureWorkspace}; database errors surface as Spring data-access exceptions. */
public interface AdventureWorkspaceRepository extends JpaRepository<AdventureWorkspace, UUID> {
    /**
     * @param ownerId owning profile ID
     * @return that owner's workspaces, oldest first; empty if none
     */
    List<AdventureWorkspace> findByOwnerIdOrderByCreatedAtAsc(UUID ownerId);
}
