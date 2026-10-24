package org.rpgengine.identity;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for {@link OwnerProfile}; database errors surface as Spring data-access exceptions. */
public interface OwnerProfileRepository extends JpaRepository<OwnerProfile, UUID> {
}
