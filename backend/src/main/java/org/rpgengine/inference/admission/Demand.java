package org.rpgengine.inference.admission;

/**
 * An amount of host capacity in bytes: what one resident model needs, what the host offers, or what
 * remains. Both components are non-negative.
 *
 * @param gpuBytes accelerator memory
 * @param ramBytes system memory
 */
public record Demand(long gpuBytes, long ramBytes) {
    /** No capacity at all. */
    public static final Demand NONE = new Demand(0, 0);

    /** Require non-negative components. */
    public Demand {
        if (gpuBytes < 0 || ramBytes < 0) {
            throw new IllegalArgumentException("capacity cannot be negative");
        }
    }

    /** @return the component-wise sum; overflow fails loudly */
    Demand plus(Demand other) {
        return new Demand(Math.addExact(gpuBytes, other.gpuBytes), Math.addExact(ramBytes, other.ramBytes));
    }

    /** @return the component-wise difference; {@code other} must not exceed this amount */
    Demand minus(Demand other) {
        return new Demand(gpuBytes - other.gpuBytes, ramBytes - other.ramBytes);
    }

    /** @return true when both components are within {@code available} */
    boolean fitsIn(Demand available) {
        return gpuBytes <= available.gpuBytes && ramBytes <= available.ramBytes;
    }

    /** @return true when neither component is positive */
    boolean isEmpty() {
        return gpuBytes == 0 && ramBytes == 0;
    }
}
