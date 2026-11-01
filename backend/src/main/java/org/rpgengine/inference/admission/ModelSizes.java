package org.rpgengine.inference.admission;

import java.util.Map;
import java.util.Objects;

/**
 * What each model is taken to need, per model rather than one figure for all (docs/34 section 10): the sizes
 * recorded on the reference host by P21.2-D for the five catalog text models, and a fallback for every other model.
 *
 * <p>A recorded size is the GPU memory the load added (the peak reading minus the reading before it) and the
 * container's RAM peak, both in MiB, because the executor compares the free reading before a load with the
 * demand. These are the arithmetic of one sample each on one host, not a bound: another card, driver or context
 * size can differ, and an owner on other hardware gets the same figures. A model without a recorded size,
 * including every image checkpoint, uses the fallback of its modality, which defaults to the whole budget so that
 * it runs alone, and the executor asks more headroom for it ({@link LeaseExecutorSettings#unmeasuredExtraBytes()}).
 *
 * <p>Models are keyed by their router ID (the file stem), which is the name a text slot is assigned and the name a
 * lease carries. Immutable and thread-safe.
 */
final class ModelSizes {
    private static final long BYTES_PER_MIB = 1024L * 1024L;

    /**
     * P21.2-D, 1 November 2026, RTX 4070 12,282 MiB: VRAM peak minus VRAM before the load, and container RAM peak.
     * 12B 11,934 - 1,313; Qwen 11,522 - 1,271; E2B 3,920 - 1,262; E4B 6,606 - 1,273; 26B 11,546 - 1,272.
     */
    static final Map<String, Demand> RECORDED = Map.of(
            "gemma-4-12b-it-qat-q4_0", mib(10_621, 6_356),
            "Qwen3.5-9B-Q4_K_M", mib(10_251, 5_749),
            "gemma-4-E2B_q4_0-it", mib(2_658, 1_993),
            "gemma-4-E4B_q4_0-it", mib(5_333, 3_367),
            "gemma-4-26B_q4_0-it", mib(10_274, 5_804));

    private final Demand capacity;
    private final Map<String, Demand> measured;
    private final Demand unmeasuredText;
    private final Demand unmeasuredImage;

    /**
     * @param capacity the budget the engine allows itself
     * @param measured recorded size by model ID; every ID must be a valid lease model and every size non-empty
     * @param unmeasuredText what a text model without a record is taken to need
     * @param unmeasuredImage what an image model without a record is taken to need
     * @throws IllegalArgumentException when a recorded ID or size is invalid
     */
    ModelSizes(Demand capacity, Map<String, Demand> measured, Demand unmeasuredText, Demand unmeasuredImage) {
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.measured = Map.copyOf(measured);
        this.unmeasuredText = Objects.requireNonNull(unmeasuredText, "unmeasuredText");
        this.unmeasuredImage = Objects.requireNonNull(unmeasuredImage, "unmeasuredImage");
        this.measured.forEach((model, size) -> {
            if (!AdmissionRequest.validModel(model) || size.isEmpty()) {
                throw new IllegalArgumentException("a recorded size needs a valid model and some capacity");
            }
        });
    }

    /** @return the sizes recorded on the reference host, over the given budget and fallbacks */
    static ModelSizes recorded(Demand capacity, Demand unmeasuredText, Demand unmeasuredImage) {
        return new ModelSizes(capacity, RECORDED, unmeasuredText, unmeasuredImage);
    }

    /** @return true when a text model has a recorded size; an image checkpoint never has one */
    boolean isMeasured(Modality modality, String model) {
        return modality == Modality.TEXT && measured.containsKey(model);
    }

    /**
     * @param model router ID of a model with a recorded size
     * @return its recorded size
     * @throws IllegalArgumentException for an ID with no record, or for a size above the budget, which no
     *         lease could ever hold
     */
    Demand measured(String model) {
        Demand size = measured.get(model);
        if (size == null) {
            throw new IllegalArgumentException("no recorded size for the model");
        }
        if (!size.fitsIn(capacity)) {
            throw new IllegalArgumentException("the recorded size exceeds the budget");
        }
        return size;
    }

    /**
     * @param modality kind of model, text or image
     * @param model the model a request names
     * @return the recorded size of a measured text model, else the modality's fallback
     * @throws IllegalArgumentException for a measured model larger than the budget (the caller refuses the request
     *         as unavailable) and for a modality that has no demand source
     */
    Demand demandFor(Modality modality, String model) {
        if (isMeasured(modality, model)) {
            return measured(model);
        }
        return switch (modality) {
            case TEXT -> unmeasuredText;
            case IMAGE -> unmeasuredImage;
            default -> throw new IllegalArgumentException("no demand source for the modality");
        };
    }

    private static Demand mib(long gpu, long ram) {
        return new Demand(gpu * BYTES_PER_MIB, ram * BYTES_PER_MIB);
    }
}
