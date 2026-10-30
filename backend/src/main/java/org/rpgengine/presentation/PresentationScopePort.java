package org.rpgengine.presentation;

import java.util.Optional;
import java.util.UUID;

/**
 * Boundary through which a settling presentation job obtains the audience's allowlist for one committed
 * operation (P05.7-B.1.2.3.2). It exists so this package need not depend on the package that derives
 * visibility; the engine wires {@code org.rpgengine.narration.PresentationScopeResolver}.
 */
public interface PresentationScopePort {
    /**
     * Resolve the scope of one committed operation. Must be called outside any database transaction.
     *
     * @param adventureId adventure that owns the campaign
     * @param campaignId campaign the operation belongs to
     * @param operationId committed operation
     * @param committedRevision revision the operation committed at
     * @return the scope, which is empty when the pack declares nothing; {@link Optional#empty()} when the
     *         request is stale or out of bounds and nothing may be shown
     * @throws RuntimeException when the scope cannot be built or read; no state changed
     */
    Optional<PublicPresentationScope> resolve(UUID adventureId, UUID campaignId, UUID operationId, long committedRevision);
}
