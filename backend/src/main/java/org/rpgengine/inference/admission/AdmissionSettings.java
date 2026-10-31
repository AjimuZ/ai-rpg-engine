package org.rpgengine.inference.admission;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the coordinator may hand out and how it behaves.
 *
 * <p>{@code capacity} is the budget the engine allows itself, which the caller sets below the real
 * total so the host keeps headroom; the coordinator never exceeds it. A lease that is not renewed
 * within {@code leaseTtl} expires. A background request gains one priority class per
 * {@code agingStep} waited, never reaching the interactive class.
 *
 * @param capacity GPU and RAM the coordinator may lease out in total
 * @param leaseTtl lifetime of a lease and of each renewal, positive
 * @param agingStep waiting time per background class promotion, positive
 * @param maxBatch members one lease may share per modality, 1..{@value #MAX_BATCH_BOUND}
 */
public record AdmissionSettings(Demand capacity, Duration leaseTtl, Duration agingStep, Map<Modality, Integer> maxBatch) {
    /** Largest batch a lease may carry; the V29 {@code batch_size} check repeats it. */
    public static final int MAX_BATCH_BOUND = 64;

    /** Validate the values and copy the batch limits; every modality needs one. */
    public AdmissionSettings {
        Objects.requireNonNull(capacity, "capacity");
        if (capacity.isEmpty()) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (leaseTtl == null || leaseTtl.isNegative() || leaseTtl.isZero()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
        if (agingStep == null || agingStep.isNegative() || agingStep.isZero()) {
            throw new IllegalArgumentException("agingStep must be positive");
        }
        EnumMap<Modality, Integer> limits = new EnumMap<>(Modality.class);
        for (Modality modality : Modality.values()) {
            Integer limit = maxBatch == null ? null : maxBatch.get(modality);
            if (limit == null || limit < 1 || limit > MAX_BATCH_BOUND) {
                throw new IllegalArgumentException("maxBatch out of range for " + modality);
            }
            limits.put(modality, limit);
        }
        maxBatch = Map.copyOf(limits);
    }

    /** @return the batch limit of {@code modality} */
    public int maxBatch(Modality modality) {
        return maxBatch.get(modality);
    }
}
