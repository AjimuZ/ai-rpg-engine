package org.rpgengine.inference;

import java.util.Optional;

/**
 * Owner slot assignment: maps a logical slot to the backend, model, effort and approved
 * degradation alternatives the owner chose for it (ADR 0021). {@link ConfiguredSlotAssignments}
 * reads private settings; the owner-only settings API of P21.3 can replace it. The assignment is
 * only the owner's intent: {@link ProfileResolver} checks it against the backend's catalog or
 * approved allowlist for every task. Implementations must be thread-safe, must return current
 * settings on every call (callers never cache) and must not perform I/O on the dispatcher's hot
 * path beyond reading cached settings.
 */
public interface SlotAssignments {
    /**
     * @param slot logical slot of a task
     * @return the owner's assignment, or empty when the owner has not assigned this slot (the
     *         task then fails with {@code slot-unassigned}; there is no silent default model)
     */
    Optional<SlotAssignment> resolve(InferenceSlot slot);
}
