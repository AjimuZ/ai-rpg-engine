package org.rpgengine.inference.slots;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Non-durable {@link SlotSettingsStore} for contexts without a data source. It is not a Spring
 * component: the PostgreSQL store is the installation's store, and a context that has no database
 * wires this one explicitly, so a missing database can never silently demote the owner's settings
 * to memory in a real run. Thread-safe.
 */
public final class MemorySlotSettingsStore implements SlotSettingsStore {
    private final Map<ModelSlot, Stored> rows = new EnumMap<>(ModelSlot.class);

    /** Create an empty store. */
    public MemorySlotSettingsStore() {
    }

    @Override
    public synchronized Map<ModelSlot, Stored> loadAll() {
        return new EnumMap<>(rows);
    }

    @Override
    public synchronized void save(Stored stored) {
        Objects.requireNonNull(stored, "stored");
        rows.put(stored.setting().slot(), stored);
    }

    @Override
    public synchronized boolean saveProbe(ModelSlot slot, ProbeResult probe) {
        Stored row = rows.get(slot);
        if (row == null) {
            return false;
        }
        rows.put(slot, new Stored(row.setting(), row.acknowledgedClass(), row.acknowledgedAt(), probe, row.updatedAt()));
        return true;
    }
}
