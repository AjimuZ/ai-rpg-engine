package org.rpgengine.content;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for {@link ContentRevision}; database errors surface as Spring data-access exceptions. */
public interface ContentRevisionRepository extends JpaRepository<ContentRevision, UUID> {
    /**
     * @param adventureId owning adventure
     * @return that adventure's admitted revisions, oldest first; empty if none
     */
    List<ContentRevision> findByAdventureIdOrderByAdmittedAtAsc(UUID adventureId);
}
