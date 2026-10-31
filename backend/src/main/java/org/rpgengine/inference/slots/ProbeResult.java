package org.rpgengine.inference.slots;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The last readiness probe of a slot, as stored and shown. It holds a state, a fixed reason code
 * and timing only: never an endpoint, key, account, model output or exception text.
 *
 * @param state outcome class
 * @param code fixed lowercase code explaining {@code FAILED} or {@code UNREACHABLE}; null otherwise
 * @param at when the probe finished; null for {@code NOT_RUN}
 * @param elapsedMillis how long the probe took; 0 for {@code NOT_RUN}
 */
public record ProbeResult(ProbeState state, String code, Instant at, long elapsedMillis) {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final ProbeResult NOT_RUN = new ProbeResult(ProbeState.NOT_RUN, null, null, 0);

    /**
     * Validate the combination of fields.
     *
     * @throws IllegalArgumentException when a failed or unreachable result lacks a well-formed
     *         code, when a ready or not-run result carries one, or when a run result lacks a time
     */
    public ProbeResult {
        Objects.requireNonNull(state, "state");
        boolean run = state != ProbeState.NOT_RUN;
        boolean coded = state == ProbeState.FAILED || state == ProbeState.UNREACHABLE;
        if (coded != (code != null) || (coded && !CODE.matcher(code).matches())
                || run != (at != null) || elapsedMillis < 0 || (!run && elapsedMillis != 0)) {
            throw new IllegalArgumentException("inconsistent probe result");
        }
    }

    /** @return the result of a slot that has never been probed under its current assignment */
    public static ProbeResult notRun() {
        return NOT_RUN;
    }
}
