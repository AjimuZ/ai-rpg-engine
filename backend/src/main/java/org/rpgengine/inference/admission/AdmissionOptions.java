package org.rpgengine.inference.admission;

import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

/**
 * Owner settings of the admission wiring, read once from {@code engine.inference.admission.*}. Values are
 * owner configuration, never task data; an invalid value fails startup with a message that names the
 * setting but not its value.
 *
 * <p>Only these three values are the owner's to choose today. Every other value of the coordinator and the
 * executor is fixed in {@link AdmissionConfiguration} and has not been measured on a host.
 *
 * @param capacity the GPU and RAM the engine allows itself in total, set by the owner below the real totals
 * @param headroomBytes GPU bytes that must stay free beside a model about to load (0 up to 64 GiB)
 * @param patience how long a request may stay delayed by contention before it falls back (above 0, at most 1 hour)
 */
record AdmissionOptions(Demand capacity, long headroomBytes, Duration patience) {
    static final String PREFIX = "engine.inference.admission.";
    static final String ENABLED = PREFIX + "enabled";
    /** Largest accepted budget or headroom, in MiB: far above any single card, so a typo in the unit fails. */
    static final long MAX_MIB = 1024L * 1024;
    /** RAM is not budgeted unless the owner sets it: the default is the largest accepted value. */
    static final long DEFAULT_RAM_MIB = MAX_MIB;
    static final long DEFAULT_HEADROOM_MIB = 1024;
    static final long MAX_HEADROOM_MIB = 64 * 1024;
    static final Duration DEFAULT_PATIENCE = Duration.ofMinutes(2);
    static final Duration MAX_PATIENCE = Duration.ofHours(1);
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
        return new AdmissionOptions(new Demand(gpu * BYTES_PER_MIB, ram * BYTES_PER_MIB), headroom * BYTES_PER_MIB,
                patience(environment));
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
        String text = environment.getProperty(PREFIX + "patience");
        Duration value;
        try {
            value = text == null || text.isBlank() ? DEFAULT_PATIENCE : DurationStyle.detectAndParse(text.strip());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(PREFIX + "patience is not a duration");
        }
        if (value.isNegative() || value.isZero() || value.compareTo(MAX_PATIENCE) > 0) {
            throw new IllegalStateException(PREFIX + "patience must be between 0 and 1 hour");
        }
        return value;
    }
}
