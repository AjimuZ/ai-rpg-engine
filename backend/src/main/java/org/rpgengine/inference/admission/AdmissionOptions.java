package org.rpgengine.inference.admission;

import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

/**
 * Owner settings of the admission wiring, read once from {@code engine.inference.admission.*}. Values are
 * owner configuration, never task data; an invalid value fails startup with a message that names the
 * setting but not its value.
 *
 * <p>Only these values are the owner's to choose today. Every other value of the coordinator and the
 * executor is fixed in {@link AdmissionConfiguration} and has not been measured on a host.
 *
 * @param capacity the GPU and RAM the engine allows itself in total, set by the owner below the real totals
 * @param headroomBytes GPU bytes that must stay free beside a model about to load (0 up to 64 GiB)
 * @param patience how long a request may stay delayed by contention before it falls back (above 0, at most 1 hour)
 * @param textDemand what a text model without a recorded size is taken to need, within {@code capacity}. It defaults
 *        to the whole capacity, so such a model runs alone, the same answer the executor gives for a resident model
 *        of unknown size, because too large a figure only costs waiting and too small a one could overcommit the
 *        card. The GPU part is at least 1 MiB.
 * @param imageDemand what an image model is taken to need, under the same rules and with the same default. No image
 *        checkpoint has a recorded size
 * @param unmeasuredExtraBytes GPU bytes asked on top of {@code headroomBytes} before a model without a recorded size
 *        loads (0 up to 64 GiB)
 * @param sizes the recorded size of each measured model, and the two fallbacks above
 * @param readingWindow how long after a player's text turn the next one is expected, so that no batch starts (0 up to
 *        10 minutes, 0 turning this off). The default is a placeholder, not measured
 */
record AdmissionOptions(Demand capacity, long headroomBytes, Duration patience, Demand textDemand, Demand imageDemand,
        long unmeasuredExtraBytes, ModelSizes sizes, Duration readingWindow) {
    static final String PREFIX = "engine.inference.admission.";
    static final String ENABLED = PREFIX + "enabled";
    /** Largest accepted budget or headroom, in MiB: far above any single card, so a typo in the unit fails. */
    static final long MAX_MIB = 1024L * 1024;
    /** RAM is not budgeted unless the owner sets it: the default is the largest accepted value. */
    static final long DEFAULT_RAM_MIB = MAX_MIB;
    static final long DEFAULT_HEADROOM_MIB = 1024;
    /** Extra margin for a model nobody measured: as large as the headroom itself, a placeholder. */
    static final long DEFAULT_UNMEASURED_EXTRA_MIB = 1024;
    static final long MAX_HEADROOM_MIB = 64 * 1024;
    static final Duration DEFAULT_PATIENCE = Duration.ofMinutes(2);
    static final Duration MAX_PATIENCE = Duration.ofHours(1);
    static final Duration DEFAULT_READING_WINDOW = Duration.ofMinutes(1);
    /** A longer window would keep images out between any two turns, and is more likely a mistyped unit. */
    static final Duration MAX_READING_WINDOW = Duration.ofMinutes(10);
    private static final long BYTES_PER_MIB = 1024L * 1024L;

    /**
     * @param environment Spring environment
     * @return true when the switch is {@code true}; a missing or blank value is off
     * @throws IllegalStateException when the value is neither {@code true} nor {@code false}
     */
    static boolean enabled(Environment environment) {
        String text = environment.getProperty(ENABLED);
        if (text == null || text.isBlank() || text.strip().equalsIgnoreCase("false")) {
            return false;
        }
        if (text.strip().equalsIgnoreCase("true")) {
            return true;
        }
        // The raw text is left out: a mistyped value should not reach a log.
        throw new IllegalStateException(ENABLED + " must be true or false");
    }

    /**
     * @param environment Spring environment
     * @return validated options
     * @throws IllegalStateException naming the first invalid setting
     */
    static AdmissionOptions from(Environment environment) {
        Long gpu = number(environment, "gpu-budget-mib", null);
        if (gpu == null) {
            throw new IllegalStateException(PREFIX + "gpu-budget-mib is required when " + ENABLED + " is true");
        }
        long ram = number(environment, "ram-budget-mib", DEFAULT_RAM_MIB);
        long headroom = number(environment, "headroom-mib", DEFAULT_HEADROOM_MIB);
        if (gpu < 1 || gpu > MAX_MIB) {
            throw new IllegalStateException(PREFIX + "gpu-budget-mib out of range");
        }
        if (ram < 0 || ram > MAX_MIB) {
            throw new IllegalStateException(PREFIX + "ram-budget-mib out of range");
        }
        if (headroom < 0 || headroom > MAX_HEADROOM_MIB) {
            throw new IllegalStateException(PREFIX + "headroom-mib out of range");
        }
        long extra = number(environment, "unmeasured-extra-headroom-mib", DEFAULT_UNMEASURED_EXTRA_MIB);
        if (extra < 0 || extra > MAX_HEADROOM_MIB) {
            throw new IllegalStateException(PREFIX + "unmeasured-extra-headroom-mib out of range");
        }
        Demand textDemand = modelDemand(environment, "text", gpu, ram);
        Demand imageDemand = modelDemand(environment, "image", gpu, ram);
        Demand capacity = new Demand(gpu * BYTES_PER_MIB, ram * BYTES_PER_MIB);
        return new AdmissionOptions(capacity, headroom * BYTES_PER_MIB, patience(environment), textDemand, imageDemand,
                extra * BYTES_PER_MIB, ModelSizes.recorded(capacity, textDemand, imageDemand), readingWindow(environment));
    }

    private static Demand modelDemand(Environment environment, String kind, long gpu, long ram) {
        long modelGpu = number(environment, kind + "-gpu-demand-mib", gpu);
        long modelRam = number(environment, kind + "-ram-demand-mib", ram);
        if (modelGpu < 1 || modelGpu > gpu) {
            throw new IllegalStateException(PREFIX + kind + "-gpu-demand-mib must be between 1 and gpu-budget-mib");
        }
        if (modelRam < 0 || modelRam > ram) {
            throw new IllegalStateException(PREFIX + kind + "-ram-demand-mib must be between 0 and ram-budget-mib");
        }
        return new Demand(modelGpu * BYTES_PER_MIB, modelRam * BYTES_PER_MIB);
    }

    private static Long number(Environment environment, String key, Long fallback) {
        try {
            return environment.getProperty(PREFIX + key, Long.class, fallback);
        } catch (RuntimeException malformed) {
            // Conversion failures can quote the value.
            throw new IllegalStateException(PREFIX + key + " is not a whole number");
        }
    }

    private static Duration patience(Environment environment) {
        Duration value = duration(environment, "patience", DEFAULT_PATIENCE);
        if (value.isNegative() || value.isZero() || value.compareTo(MAX_PATIENCE) > 0) {
            throw new IllegalStateException(PREFIX + "patience must be between 0 and 1 hour");
        }
        return value;
    }

    private static Duration readingWindow(Environment environment) {
        Duration value = duration(environment, "reading-window", DEFAULT_READING_WINDOW);
        if (value.isNegative() || value.compareTo(MAX_READING_WINDOW) > 0) {
            throw new IllegalStateException(PREFIX + "reading-window must be between 0 and 10 minutes");
        }
        return value;
    }

    private static Duration duration(Environment environment, String key, Duration fallback) {
        String text = environment.getProperty(PREFIX + key);
        try {
            return text == null || text.isBlank() ? fallback : DurationStyle.detectAndParse(text.strip());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(PREFIX + key + " is not a duration");
        }
    }
}
