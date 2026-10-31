package org.rpgengine.inference.slots;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Durable storage of the owner's slot assignments. Implementations hold no policy: the class
 * change and fallback rules live in {@link SlotSetting} and {@link SlotSettingsService}.
 */
public interface SlotSettingsStore {
    /**
     * A stored assignment with the acknowledgement and probe state saved beside it.
     *
     * @param setting the validated assignment
     * @param acknowledgedClass the class the owner last acknowledged for this slot, or null if none
     * @param acknowledgedAt when that acknowledgement was recorded; null exactly when
     *        {@code acknowledgedClass} is null
     * @param probe the last readiness probe of this exact assignment
     * @param updatedAt when the assignment was last saved
     */
    record Stored(SlotSetting setting, BackendClass acknowledgedClass, Instant acknowledgedAt, ProbeResult probe,
            Instant updatedAt) {
        /** Require the parts and pair the acknowledgement fields. */
        public Stored {
            Objects.requireNonNull(setting, "setting");
            Objects.requireNonNull(probe, "probe");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if ((acknowledgedClass == null) != (acknowledgedAt == null)) {
                throw new IllegalArgumentException("acknowledgement needs a class and a time");
            }
        }
    }

    /**
     * @return every stored assignment by slot
     * @throws IllegalStateException when a stored row no longer validates; the caller must treat
     *         the settings as unreadable and must not substitute another assignment
     * @throws org.springframework.dao.DataAccessException when storage is unavailable
     */
    Map<ModelSlot, Stored> loadAll();

    /**
     * Insert or replace one slot atomically, with its fallbacks.
     *
     * @param stored the assignment to keep
     * @throws org.springframework.dao.DataAccessException when storage is unavailable
     */
    void save(Stored stored);

    /**
     * Record a probe result for a slot that has a stored assignment.
     *
     * @param slot the probed slot
     * @param probe the result
     * @return whether a row existed to take it
     * @throws org.springframework.dao.DataAccessException when storage is unavailable
     */
    boolean saveProbe(ModelSlot slot, ProbeResult probe);
}
