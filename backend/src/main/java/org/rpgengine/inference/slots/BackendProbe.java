package org.rpgengine.inference.slots;

import java.util.Objects;
import java.util.regex.Pattern;
import org.rpgengine.inference.EffectiveSettings;

/**
 * Port that tests whether a backend can serve a model right now. It is called only on an owner
 * request or an explicit selection, never on the dispatcher's hot path, and it must be bounded in
 * time by the implementation. It sends no task content and never switches backend or class.
 */
public interface BackendProbe {
    /**
     * The verdict of one probe.
     *
     * @param state {@code READY}, {@code FAILED} or {@code UNREACHABLE}
     * @param code fixed lowercase reason for the two failing states; null when ready
     */
    record Outcome(ProbeState state, String code) {
        private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{0,63}");

        /**
         * @throws IllegalArgumentException for {@code NOT_RUN}, a missing or malformed code on a
         *         failing state, or a code on a ready one
         */
        public Outcome {
            Objects.requireNonNull(state, "state");
            boolean failing = state == ProbeState.FAILED || state == ProbeState.UNREACHABLE;
            if (state == ProbeState.NOT_RUN || failing != (code != null) || (failing && !CODE.matcher(code).matches())) {
                throw new IllegalArgumentException("inconsistent probe outcome");
            }
        }

        /** @return a ready verdict */
        public static Outcome ready() {
            return new Outcome(ProbeState.READY, null);
        }

        /**
         * @param code fixed reason
         * @return a verdict for a backend that answered but cannot serve the model
         */
        public static Outcome failed(String code) {
            return new Outcome(ProbeState.FAILED, code);
        }

        /**
         * @param code fixed reason
         * @return a verdict for a backend that could not be reached
         */
        public static Outcome unreachable(String code) {
            return new Outcome(ProbeState.UNREACHABLE, code);
        }
    }

    /**
     * Test one backend and model.
     *
     * @param backend protocol adapter name
     * @param settings model and effort of the assignment under test ({@code none} for an image model)
     * @return the verdict; implementations report runtime conditions as a verdict, not an exception
     */
    Outcome probe(String backend, EffectiveSettings settings);
}
