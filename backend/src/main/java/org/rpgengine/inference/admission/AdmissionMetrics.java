package org.rpgengine.inference.admission;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters of the {@link LeaseExecutor}. Names are fixed and every value is a number, so a metric cannot
 * carry a key, an endpoint, a model name, a prompt or an output. Counters only grow and start at zero
 * with each process.
 */
final class AdmissionMetrics {
    /** One fixed metric name per constant. */
    enum Counter {
        GRANTS,
        WAIT_TOTAL_MILLIS,
        WAIT_MAX_MILLIS,
        LEASES_EXPIRED,
        SWAPS_OUT,
        SWAPS_BACK,
        SWAP_FAILURES,
        LOAD_FAILURES,
        UNLOAD_FAILURES,
        SLOT_SAVES,
        SLOT_SAVE_FAILURES,
        SLOT_RESTORES,
        SLOT_RESTORE_FAILURES,
        JOB_STEPS,
        INTERRUPTIONS,
        DEFERRALS,
        CONTENTION_DELAYS,
        CONTENTION_FALLBACKS,
        CONTENTION_YIELDS,
        READINGS_UNAVAILABLE,
        STORE_FAILURES
    }

    private final Map<Counter, AtomicLong> counters = new EnumMap<>(Counter.class);

    AdmissionMetrics() {
        for (Counter counter : Counter.values()) {
            counters.put(counter, new AtomicLong());
        }
    }

    void count(Counter counter) {
        counters.get(counter).incrementAndGet();
    }

    /** Record one admission that waited {@code millis}. */
    void waited(long millis) {
        long waited = Math.max(0, millis);
        counters.get(Counter.WAIT_TOTAL_MILLIS).addAndGet(waited);
        counters.get(Counter.WAIT_MAX_MILLIS).accumulateAndGet(waited, Math::max);
    }

    /** @return every counter under its fixed lowercase name, sorted by name */
    Map<String, Long> snapshot() {
        Map<String, Long> values = new TreeMap<>();
        counters.forEach((counter, value) -> values.put(counter.name().toLowerCase(Locale.ROOT), value.get()));
        return values;
    }
}
