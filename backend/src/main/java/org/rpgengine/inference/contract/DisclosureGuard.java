package org.rpgengine.inference.contract;

import java.util.List;

/**
 * Privileged phrases that must never reach the audience of a task: names of undiscovered
 * entities, secret passwords, hidden plan text and similar (ADR 0007, docs/06 section 7).
 *
 * <p>The guard is validator input only. It is never rendered into instructions; the contract
 * also refuses to submit when a marker already occurs in the rendered context, because that means
 * the caller assembled a context outside the public scope. Matching ignores case and whitespace
 * differences, so it catches verbatim and lightly reformatted leaks; it cannot detect paraphrase,
 * which remains a human-rubric evaluation concern (docs/21 section 13).
 *
 * @param markers privileged phrases, each {@link #MIN_MARKER}..{@link #MAX_MARKER} characters, at
 *        most {@link #MAX_MARKERS}
 */
public record DisclosureGuard(List<String> markers) {
    /** Shortest marker; shorter phrases would match ordinary words and block valid narration. */
    public static final int MIN_MARKER = 4;
    /** Longest marker. */
    public static final int MAX_MARKER = 200;
    /** Most markers per task. */
    public static final int MAX_MARKERS = 256;

    /** @throws IllegalArgumentException for a marker outside the bounds */
    public DisclosureGuard {
        markers = ContextValues.distinct(markers, "markers", 0, MAX_MARKERS, TextChecks::fold);
        for (String marker : markers) {
            ContextValues.text(marker, "marker", MAX_MARKER);
            if (TextChecks.fold(marker).length() < MIN_MARKER) {
                throw new IllegalArgumentException("marker shorter than " + MIN_MARKER + " characters");
            }
        }
    }

    /** @return a guard without markers */
    public static DisclosureGuard none() {
        return new DisclosureGuard(List.of());
    }

    /**
     * @param text any text
     * @return true when a marker occurs in the text, ignoring case and whitespace runs
     */
    boolean leaks(String text) {
        String folded = TextChecks.fold(text);
        for (String marker : markers) {
            if (folded.contains(TextChecks.fold(marker))) {
                return true;
            }
        }
        return false;
    }
}
