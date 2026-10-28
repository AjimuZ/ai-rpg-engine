package org.rpgengine.inference;

import java.util.Optional;

/**
 * Owner slot assignment: resolves a logical slot to effective settings for the selected
 * backend. P08.1 ships a configuration-backed implementation ({@link ConfiguredSlotAssignments});
 * P08.4 replaces it with owner settings resolved against the backend catalog or allowlist and an
 * explicit degradation policy. Implementations must be thread-safe and must not perform I/O on
 * the dispatcher's hot path beyond reading cached settings.
 */
public interface SlotAssignments {
    /**
     * @param slot logical slot of a task
     * @return the assigned settings, or empty when the owner has not assigned this slot (the
     *         task then fails with {@code slot-unassigned}; there is no silent default model)
     */
    Optional<EffectiveSettings> resolve(InferenceSlot slot);
}
