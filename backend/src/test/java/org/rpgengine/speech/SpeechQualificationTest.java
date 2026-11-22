package org.rpgengine.speech;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verify speech ships disabled with an exact rights record and a text-only fallback (P17.7-A, QA-38). */
class SpeechQualificationTest {
    /** Construct an independent corpus; no application context is needed. */
    SpeechQualificationTest() {
    }

    /** The bundled record lists every candidate's rights separately, admits none and excludes XTTS-v2. */
    @Test
    void bundledRecordAdmitsNoProfile() {
        var q = SpeechQualification.load();
        assertEquals(5, q.candidates().size());
        assertTrue(q.candidates().stream().noneMatch(SpeechQualification.Candidate::admittedBilingual));
        var qwen = q.candidates().get(0);
        assertEquals("qwen3-tts-0.6b-base", qwen.id());
        assertEquals("Apache-2.0", qwen.weightsLicense());
        assertEquals("none-admitted", qwen.voiceRights());
        var xtts = q.candidates().stream().filter(c -> c.id().equals("xtts-v2")).findFirst().orElseThrow();
        assertEquals("excluded", xtts.rights());
        assertTrue(xtts.weightsLicense().contains("noncommercial"));
        for (String gate : SpeechQualification.GATES) {
            assertNotEquals("passed", q.gate(gate).status(), gate);
        }
    }

    /** Disabled by default and also when the owner asks: every unmet gate and the missing profile are named. */
    @Test
    void shipsDisabledEvenWhenRequested() {
        var q = SpeechQualification.load();
        var asked = q.decide(true);
        assertFalse(asked.enabled());
        assertNull(asked.profileId());
        assertEquals(List.of("gate:rights-inventory:pending", "gate:container-offline:unexecuted",
                "gate:bilingual-quality:unexecuted", "gate:resources:unexecuted",
                "gate:persistence-fault:unexecuted", "no-admitted-bilingual-profile"), asked.reasons());
        assertEquals("not-requested", q.decide(false).reasons().get(0));
    }

    /** Only all passed gates plus an admitted bilingual profile plus the owner's request enable speech. */
    @Test
    void enablesOnlyWhenEveryConditionHolds() {
        String all = record("admitted", "admitted", "passed", "passed");
        var q = parse(all);
        var on = q.decide(true);
        assertTrue(on.enabled());
        assertEquals("c", on.profileId());
        assertEquals(List.of("not-requested"), q.decide(false).reasons());
        assertEquals(List.of("gate:resources:failed"), parse(record("admitted", "admitted", "passed", "failed"))
                .decide(true).reasons());
        assertEquals(List.of("no-admitted-bilingual-profile"),
                parse(record("recorded", "none-admitted", "passed", "passed")).decide(true).reasons());
    }

    /** A record that admits a profile without a voice, or leaves out a gate or evidence, does not load. */
    @Test
    void refusesInconsistentRecords() {
        assertThrows(IllegalArgumentException.class, () -> parse(record("admitted", "none-admitted", "passed", "passed")));
        assertThrows(IllegalArgumentException.class, () -> parse(record("recorded", "none-admitted", "done", "passed")));
        assertThrows(IllegalArgumentException.class,
                () -> parse(record("recorded", "none-admitted", "passed", "passed").replace("\"resources\"", "\"other\"")));
        assertThrows(IllegalArgumentException.class,
                () -> parse(record("recorded", "none-admitted", "passed", "passed").replace("\"locales\":[\"en\",\"it\"]",
                        "\"locales\":[\"en\",\"fr\"]")));
        assertThrows(IllegalArgumentException.class,
                () -> parse(record("recorded", "none-admitted", "passed", "passed").replace("\"evidence\":\"e\"",
                        "\"evidence\":\" \"")));
    }

    /** Every outcome delivers the text unchanged and silent; a foreign locale and an enabled decision say why. */
    @Test
    void fallsBackToUnchangedText() {
        var q = SpeechQualification.load();
        String line = "  L'acqua è già fredda, perché?  \n";
        var d = SpeechOutput.deliver(q.decide(true), "it", line);
        assertEquals(line, d.text());
        assertEquals("it", d.locale());
        assertFalse(d.audible());
        assertEquals(q.decide(true).reasons(), d.reasons());
        var foreign = SpeechOutput.deliver(q.decide(true), "fr", "Bonjour");
        assertEquals("Bonjour", foreign.text());
        assertTrue(foreign.reasons().contains("locale-unsupported"));
        var enabled = SpeechOutput.deliver(parse(record("admitted", "admitted", "passed", "passed")).decide(true),
                "en", "Hello");
        assertFalse(enabled.audible());
        assertEquals(List.of("worker-unavailable"), enabled.reasons());
        assertThrows(IllegalArgumentException.class, () -> SpeechOutput.deliver(q.decide(true), "en", null));
    }

    private static SpeechQualification parse(String json) {
        return SpeechQualification.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String record(String rights, String voice, String status, String resources) {
        StringBuilder gates = new StringBuilder();
        for (String id : SpeechQualification.GATES) {
            if (!gates.isEmpty()) {
                gates.append(',');
            }
            gates.append("{\"id\":\"").append(id).append("\",\"status\":\"")
                    .append(id.equals("resources") ? resources : status).append("\",\"evidence\":\"e\"}");
        }
        return "{\"schema\":1,\"recordedOn\":\"2026-11-22\",\"candidates\":[{\"id\":\"c\",\"role\":\"primary\","
                + "\"locales\":[\"en\",\"it\"],\"codeLicense\":\"L\",\"codeRevision\":\"r\",\"weightsLicense\":\"L\","
                + "\"weightsRevision\":\"r\",\"voiceRights\":\"" + voice + "\",\"rights\":\"" + rights
                + "\",\"verifiedOn\":\"2026-11-22\",\"notes\":\"n\"}],\"gates\":[" + gates + "]}";
    }
}
