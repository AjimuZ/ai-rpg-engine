package org.rpgengine.speech;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Answers a microphone request and any recognition result on the no-capture path: capture never opens (P17.9-A, QA-45).
 *
 * <p>Always holds: no permission is requested, no capture session, buffer or worker is opened, no draft is created and
 * nothing is queued or submitted, whatever the decision, scope, locale or mode. Typed input stays the complete path.
 * Because no capture worker exists, even an {@code enabled} decision yields {@code unavailable} with reason
 * {@code worker-unavailable}; a later story that adds a qualified worker replaces that branch and must keep every other
 * branch, including that recognition only ever proposes a draft and never queues input out of turn.
 */
public final class SpeechInput {
    /** Input locales a capture request may name; the locale is never inferred from audio. */
    public static final Set<String> LOCALES = Set.of("en", "it");
    /** Capture modes of {@code docs/35} section 2. */
    public static final Set<String> MODES = Set.of("push-to-talk", "hands-free");
    /** The only state this path reports, one of the accessible states of {@code docs/35} section 2. */
    public static final String UNAVAILABLE = "unavailable";

    private SpeechInput() {
    }

    /**
     * The accessible input status shown after a request.
     *
     * @param state always {@link #UNAVAILABLE} on this path
     * @param scope the adventure/campaign scope the request named, echoed for display, or null
     * @param locale the requested locale, echoed unchanged, or null
     * @param mode the requested capture mode, echoed unchanged, or null
     * @param capturing whether audio is being captured; false in every current outcome
     * @param permissionRequested whether microphone permission was asked for; false in every current outcome
     * @param sessionId the capture session identity; null because no session is opened
     * @param typedInputAvailable whether typed play stays available; true in every outcome
     * @param reasons stable codes explaining why capture is unavailable, never empty
     */
    public record Status(String state, String scope, String locale, String mode, boolean capturing,
            boolean permissionRequested, String sessionId, boolean typedInputAvailable, List<String> reasons) {
    }

    /**
     * What happened to a recognition result.
     *
     * @param draft the reviewable draft text; null because a result without an open session is discarded
     * @param queued whether any input was queued or submitted; false in every outcome, so nothing runs out of turn
     * @param reasons stable codes explaining the discard, never empty
     */
    public record Receipt(String draft, boolean queued, List<String> reasons) {
    }

    /**
     * Answer an explicit request to start listening.
     *
     * @param decision the recognition decision in force
     * @param scope the adventure/campaign scope the capture would be bound to
     * @param locale the explicitly selected input locale
     * @param mode the explicitly selected capture mode
     * @return an {@link #UNAVAILABLE} status that captures nothing, with the decision's reasons followed by
     *         {@code scope-missing}, {@code locale-unsupported}, {@code mode-unsupported} and, when the decision is
     *         enabled, {@code worker-unavailable}, each when applicable and in that order
     * @throws IllegalArgumentException when the decision is null
     */
    public static Status start(SpeechInputQualification.Decision decision, String scope, String locale, String mode) {
        if (decision == null) {
            throw new IllegalArgumentException("A capture request needs a recognition decision");
        }
        List<String> reasons = new ArrayList<>(decision.reasons());
        if (scope == null || scope.isBlank()) {
            reasons.add("scope-missing");
        }
        if (locale == null || !LOCALES.contains(locale)) {
            reasons.add("locale-unsupported");
        }
        if (mode == null || !MODES.contains(mode)) {
            reasons.add("mode-unsupported");
        }
        if (decision.captureAllowed()) {
            reasons.add("worker-unavailable");
        }
        return new Status(UNAVAILABLE, scope, locale, mode, false, false, null, true, List.copyOf(reasons));
    }

    /**
     * Handle a recognition result that reaches the engine, for example a late or duplicated one.
     *
     * <p>Why discard rather than draft: {@code docs/35} section 8.2 discards any result without a live session of the
     * same scope and generation, and this path never opens one. The text is not read, so a control phrase such as
     * "confirm" inside it changes nothing.
     *
     * @param status the status in force
     * @param recognizedText the result text, possibly null
     * @return no draft, nothing queued, reason {@code no-capture-session}
     * @throws IllegalArgumentException when the status is null
     */
    public static Receipt receive(Status status, String recognizedText) {
        if (status == null) {
            throw new IllegalArgumentException("A recognition result needs the status in force");
        }
        return new Receipt(null, false, List.of("no-capture-session"));
    }
}
