package org.rpgengine.speech;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verify speech recognition ships disabled with a recorded disqualification and no capture (P17.8-A, QA-45). */
class SpeechInputQualificationTest {
    /** Construct an independent corpus; no application context is needed. */
    SpeechInputQualificationTest() {
    }

    /** The bundled record names no recognizer, passes no gate and states the ceilings a recognizer must fit under. */
    @Test
    void bundledRecordQualifiesNothing() {
        var q = SpeechInputQualification.load();
        assertNull(q.qualifiedRecognizer());
        assertTrue(q.disqualification().contains("No recognizer is qualified"));
        for (String gate : SpeechInputQualification.GATES) {
            assertNotEquals("passed", q.gate(gate).status(), gate);
        }
        for (String name : SpeechInputQualification.LIMITS) {
            assertTrue(q.limit(name) > 0, name);
        }
        assertEquals(1, q.limit("maxQueuedUtterances"));
        assertEquals(1, q.limit("maxRecognitionAttempts"));
    }

    /** Disabled by default and also when the owner asks: every unmet gate and the missing recognizer are named. */
    @Test
    void shipsDisabledWithNoCapture() {
        var q = SpeechInputQualification.load();
        var asked = q.decide(true);
        assertFalse(asked.enabled());
        assertFalse(asked.captureAllowed());
        assertNull(asked.recognizerId());
        assertEquals(List.of("gate:rights-inventory:pending", "gate:capture-cancel-interface:unexecuted",
                "gate:offline-behavior:unexecuted", "gate:bilingual-recognition:unexecuted",
                "gate:packaging:unexecuted", "gate:resources:unexecuted", "no-qualified-recognizer"), asked.reasons());
        var idle = q.decide(false);
        assertFalse(idle.captureAllowed());
        assertEquals("not-requested", idle.reasons().get(0));
    }

    /** Only the request, every passed gate and a named recognizer together enable capture. */
    @Test
    void enablesOnlyWhenEveryConditionHolds() {
        var q = parse(record("\"r\"", "passed", "passed"));
        var on = q.decide(true);
        assertTrue(on.captureAllowed());
        assertEquals("r", on.recognizerId());
        assertEquals(List.of("not-requested"), q.decide(false).reasons());
        assertEquals(List.of("no-qualified-recognizer"), parse(record("null", "passed", "passed")).decide(true).reasons());
    }

    /** A record that names a recognizer past an open gate, or lacks a gate, a limit or evidence, does not load. */
    @Test
    void refusesInconsistentRecords() {
        assertThrows(IllegalArgumentException.class, () -> parse(record("\"r\"", "passed", "unexecuted")));
        assertThrows(IllegalArgumentException.class, () -> parse(record("null", "done", "passed")));
        assertThrows(IllegalArgumentException.class,
                () -> parse(record("null", "passed", "passed").replace("\"resources\"", "\"other\"")));
        assertThrows(IllegalArgumentException.class,
                () -> parse(record("null", "passed", "passed").replace("\"maxQueuedUtterances\":1", "\"maxQueuedUtterances\":0")));
        assertThrows(IllegalArgumentException.class,
                () -> parse(record("null", "passed", "passed").replace("\"evidence\":\"e\"", "\"evidence\":\" \"")));
    }

    private static SpeechInputQualification parse(String json) {
        return SpeechInputQualification.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String record(String recognizer, String status, String resources) {
        StringBuilder gates = new StringBuilder();
        for (String id : SpeechInputQualification.GATES) {
            if (!gates.isEmpty()) {
                gates.append(',');
            }
            gates.append("{\"id\":\"").append(id).append("\",\"status\":\"")
                    .append(id.equals("resources") ? resources : status).append("\",\"evidence\":\"e\"}");
        }
        return "{\"schema\":1,\"recordedOn\":\"2026-11-22\",\"qualifiedRecognizer\":" + recognizer
                + ",\"disqualification\":\"d\",\"limits\":{\"maxUtteranceSeconds\":30,\"maxUtteranceBytes\":1024,"
                + "\"maxQueuedUtterances\":1,\"maxRecognitionAttempts\":1,\"maxCaptureSessionsPerScope\":1},\"gates\":["
                + gates + "]}";
    }
}
