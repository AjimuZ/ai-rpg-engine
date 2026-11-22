package org.rpgengine.speech;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verify the no-capture path: no permission, session, draft or queued input, and typed play stays (P17.9-A, QA-45). */
class SpeechInputTest {
    /** Construct an independent corpus; no application context is needed. */
    SpeechInputTest() {
    }

    /** With the bundled record, an explicit request opens nothing and reports why, requested or not. */
    @Test
    void bundledRecordCapturesNothing() {
        var q = SpeechInputQualification.load();
        for (boolean requested : new boolean[] {true, false}) {
            var decision = q.decide(requested);
            var status = SpeechInput.start(decision, "adventure-a/campaign-1", "it", "hands-free");
            assertEquals(SpeechInput.UNAVAILABLE, status.state());
            assertFalse(status.capturing());
            assertFalse(status.permissionRequested());
            assertNull(status.sessionId());
            assertTrue(status.typedInputAvailable());
            assertEquals(decision.reasons(), status.reasons());
            assertEquals("adventure-a/campaign-1", status.scope());
            assertEquals("it", status.locale());
        }
    }

    /** No capture worker exists, so even an enabled decision stays unavailable; bad requests add their reasons. */
    @Test
    void enabledDecisionStillOpensNoCapture() {
        var enabled = new SpeechInputQualification.Decision(true, "r", List.of());
        var status = SpeechInput.start(enabled, "adventure-a/campaign-1", "en", "push-to-talk");
        assertFalse(status.capturing());
        assertNull(status.sessionId());
        assertEquals(List.of("worker-unavailable"), status.reasons());
        assertEquals(List.of("scope-missing", "locale-unsupported", "mode-unsupported", "worker-unavailable"),
                SpeechInput.start(enabled, " ", "fr", "always-on").reasons());
        assertNull(SpeechInput.start(enabled, "s", null, "hands-free").locale());
        assertThrows(IllegalArgumentException.class, () -> SpeechInput.start(null, "s", "en", "hands-free"));
    }

    /** A recognition result, including a control phrase, creates no draft and queues nothing out of turn. */
    @Test
    void recognitionResultIsDiscardedNeverQueued() {
        var status = SpeechInput.start(SpeechInputQualification.load().decide(true), "s", "en", "hands-free");
        for (String text : new String[] {"attack the guard", "confirm", "", null}) {
            var receipt = SpeechInput.receive(status, text);
            assertNull(receipt.draft());
            assertFalse(receipt.queued());
            assertEquals(List.of("no-capture-session"), receipt.reasons());
        }
        assertThrows(IllegalArgumentException.class, () -> SpeechInput.receive(null, "confirm"));
    }
}
