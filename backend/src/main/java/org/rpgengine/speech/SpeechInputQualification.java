package org.rpgengine.speech;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * The recorded disqualification of speech recognition and the one rule that may enable microphone capture (P17.8-A, QA-45).
 *
 * <p>Always holds: the record lists exactly the six gates of {@code docs/35} section 8.3 once each, every limit is a
 * positive integer, a gate marked {@code passed} carries evidence, and a qualified recognizer is named only when every
 * gate passed. {@link #decide} answers {@code enabled} only when the owner asked for capture, every gate passed and a
 * recognizer is qualified; any other combination is disabled with reasons, which means no capture, no buffer and no
 * background retry while typed play stays complete. The bundled record names no recognizer and passes no gate. Loading
 * fails with {@link IllegalArgumentException} naming the field or gate (never a value) when the record is inconsistent.
 */
public final class SpeechInputQualification {
    /** Classpath location of the recorded disqualification. */
    public static final String RESOURCE = "/speech/recognition-qualification.json";
    /** Gates of the qualification, in the order the record lists them. */
    public static final List<String> GATES = List.of("rights-inventory", "capture-cancel-interface",
            "offline-behavior", "bilingual-recognition", "packaging", "resources");
    /** Gate states; only {@code passed} counts toward enablement. */
    public static final Set<String> GATE_STATUSES = Set.of("pending", "unexecuted", "failed", "passed");
    /** Names of the capture ceilings a recognizer must fit under. */
    public static final List<String> LIMITS = List.of("maxUtteranceSeconds", "maxUtteranceBytes", "maxQueuedUtterances",
            "maxRecognitionAttempts", "maxCaptureSessionsPerScope");

    private final String recordedOn;
    private final String qualifiedRecognizer;
    private final String disqualification;
    private final Map<String, Integer> limits;
    private final Map<String, Gate> gates;

    private SpeechInputQualification(String recordedOn, String qualifiedRecognizer, String disqualification,
            Map<String, Integer> limits, Map<String, Gate> gates) {
        this.recordedOn = recordedOn;
        this.qualifiedRecognizer = qualifiedRecognizer;
        this.disqualification = disqualification;
        this.limits = Map.copyOf(limits);
        this.gates = Map.copyOf(gates);
    }

    /**
     * One qualification gate.
     *
     * @param id one of {@link #GATES}
     * @param status one of {@link #GATE_STATUSES}
     * @param evidence what the status rests on, or what is missing
     */
    public record Gate(String id, String status, String evidence) {
    }

    /**
     * The outcome of {@link #decide}.
     *
     * @param enabled whether microphone capture may be offered
     * @param recognizerId the qualified recognizer when enabled, otherwise null
     * @param reasons empty when enabled, otherwise one stable code per unmet condition
     */
    public record Decision(boolean enabled, String recognizerId, List<String> reasons) {
        /** @return whether audio may be captured; false means no buffer is opened at all */
        public boolean captureAllowed() {
            return enabled;
        }
    }

    /** @return ISO date of the record */
    public String recordedOn() {
        return recordedOn;
    }

    /** @return why no recognizer is qualified */
    public String disqualification() {
        return disqualification;
    }

    /** @return the qualified recognizer, or null when none is */
    public String qualifiedRecognizer() {
        return qualifiedRecognizer;
    }

    /**
     * Read one capture ceiling.
     *
     * @param name one of {@link #LIMITS}
     * @return its positive value
     */
    public int limit(String name) {
        return limits.get(name);
    }

    /**
     * Read one gate.
     *
     * @param id one of {@link #GATES}
     * @return that gate (the record always holds all of them)
     */
    public Gate gate(String id) {
        return gates.get(id);
    }

    /**
     * Decide whether microphone capture may be offered.
     *
     * @param ownerRequested whether the owner explicitly enabled recognition; the default is false
     * @return enabled only when requested, every gate passed and a recognizer is qualified; otherwise disabled with
     *         {@code not-requested}, {@code gate:<id>:<status>} and {@code no-qualified-recognizer} reasons in that order
     */
    public Decision decide(boolean ownerRequested) {
        List<String> reasons = new ArrayList<>();
        if (!ownerRequested) {
            reasons.add("not-requested");
        }
        for (String id : GATES) {
            Gate gate = gates.get(id);
            if (!"passed".equals(gate.status())) {
                reasons.add("gate:" + id + ":" + gate.status());
            }
        }
        if (qualifiedRecognizer == null) {
            reasons.add("no-qualified-recognizer");
        }
        return reasons.isEmpty() ? new Decision(true, qualifiedRecognizer, List.of())
                : new Decision(false, null, List.copyOf(reasons));
    }

    /**
     * Load and validate the bundled record.
     *
     * @return the validated record
     * @throws IllegalArgumentException when the record is incomplete or inconsistent
     */
    public static SpeechInputQualification load() {
        try (InputStream in = SpeechInputQualification.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalArgumentException("Speech input qualification resource is missing");
            }
            return parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parse and validate record JSON.
     *
     * @param json document bytes
     * @return the validated record
     * @throws IllegalArgumentException when a field, limit or gate is missing or inconsistent
     */
    public static SpeechInputQualification parse(byte[] json) {
        Map<?, ?> root = JsonMapper.builder().build().readValue(json, Map.class);
        String recordedOn = text(root, "recordedOn", "record");
        try {
            LocalDate.parse(recordedOn);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("record has no ISO date");
        }
        String disqualification = text(root, "disqualification", "record");
        Object recognizer = root.get("qualifiedRecognizer");
        if (recognizer != null && !(recognizer instanceof String s && !s.isBlank())) {
            throw new IllegalArgumentException("qualifiedRecognizer must be null or a name");
        }
        if (!(root.get("limits") instanceof Map<?, ?> rawLimits) || !(root.get("gates") instanceof List<?> gateItems)) {
            throw new IllegalArgumentException("Speech input qualification needs limits and a gates list");
        }
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (String name : LIMITS) {
            if (!(rawLimits.get(name) instanceof Integer n) || n <= 0) {
                throw new IllegalArgumentException("Limit " + name + " must be a positive integer");
            }
            limits.put(name, n);
        }
        Map<String, Gate> gates = new LinkedHashMap<>();
        for (Object item : gateItems) {
            Map<?, ?> m = (Map<?, ?>) item;
            String id = text(m, "id", "gate");
            Gate gate = new Gate(id, text(m, "status", id), text(m, "evidence", id));
            if (!GATES.contains(id) || gates.put(id, gate) != null) {
                throw new IllegalArgumentException("Gate " + id + " is unknown or repeated");
            }
            if (!GATE_STATUSES.contains(gate.status())) {
                throw new IllegalArgumentException("Gate " + id + " has an unknown status");
            }
        }
        if (!gates.keySet().containsAll(GATES)) {
            throw new IllegalArgumentException("Speech input qualification lacks a gate");
        }
        if (recognizer != null && gates.values().stream().anyMatch(g -> !"passed".equals(g.status()))) {
            throw new IllegalArgumentException("A recognizer is named while a gate has not passed");
        }
        return new SpeechInputQualification(recordedOn, (String) recognizer, disqualification, limits, gates);
    }

    private static String text(Map<?, ?> m, String field, String id) {
        if (m.get(field) instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException(id + " lacks " + field);
    }
}
