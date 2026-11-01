package org.rpgengine.inference.admission;

import java.util.OptionalLong;

/**
 * The GPU memory free right now, whoever else is using the card. Other applications can take memory at
 * any moment, so this reading and not the coordinator's budget tells whether a model would fit.
 * {@link HostGpuMemoryReading} reads the host's GPU tool; the application uses it only behind
 * {@code engine.inference.admission.enabled}.
 */
@FunctionalInterface
public interface GpuMemoryReading {
    /** @return the free bytes, or empty when they cannot be read */
    OptionalLong freeBytes();
}
