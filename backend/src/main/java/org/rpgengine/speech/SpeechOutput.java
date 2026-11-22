package org.rpgengine.speech;

import java.util.List;

/**
 * Turns an accepted public line into what the player can receive: today always the unchanged text (P17.7-A).
 *
 * <p>Always holds: the returned text equals the input, no audio is claimed, no other locale's voice is
 * substituted, and nothing is fetched or started. Because no speech worker exists, even an {@code enabled}
 * decision yields a text-only line with reason {@code worker-unavailable}; a later story that adds a worker
 * replaces that branch and must keep every other branch.
 */
public final class SpeechOutput {
    private SpeechOutput() {
    }

    /**
     * One line as delivered.
     *
     * @param locale engine locale of the text
     * @param text the accepted line, byte for byte
     * @param audible whether audio accompanies the text; false in every current outcome
     * @param reasons stable codes explaining the text-only outcome, never empty while {@code audible} is false
     */
    public record Delivery(String locale, String text, boolean audible, List<String> reasons) {
    }

    /**
     * Deliver a line.
     *
     * @param decision the qualification decision in force
     * @param locale locale of the text
     * @param text the accepted public line
     * @return the text unchanged and not audible, with the decision's reasons, {@code locale-unsupported} for a
     *         locale outside {@link SpeechQualification#LOCALES}, or {@code worker-unavailable} when the
     *         decision is enabled
     * @throws IllegalArgumentException when the text is null
     */
    public static Delivery deliver(SpeechQualification.Decision decision, String locale, String text) {
        if (text == null) {
            throw new IllegalArgumentException("A line needs text");
        }
        List<String> reasons = new java.util.ArrayList<>(decision.reasons());
        if (locale == null || !SpeechQualification.LOCALES.contains(locale)) {
            reasons.add("locale-unsupported");
        }
        if (decision.enabled()) {
            reasons.add("worker-unavailable");
        }
        return new Delivery(locale, text, false, List.copyOf(reasons));
    }
}
