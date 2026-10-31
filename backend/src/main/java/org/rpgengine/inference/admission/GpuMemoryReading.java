package org.rpgengine.inference.admission;

import java.util.OptionalLong;

/**
 * The GPU memory free right now, whoever else is using the card. Other applications can take memory at
 * any moment, so this reading and not the coordinator's budget tells whether a model would fit.
 * No implementation reads real hardware yet.
 */
@FunctionalInterface
public interface GpuMemoryReading {
    /** @return the free bytes, or empty when they cannot be read */
    OptionalLong freeBytes();
}
