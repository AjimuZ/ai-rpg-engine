package org.rpgengine.speech;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * The recorded qualification of optional local speech and the one rule that may enable it (P17.7-A, QA-38).
 *
 * <p>Always holds: the record lists exactly the five gates of {@code docs/30} section 9.3 once each, every
 * candidate states its code, weights and voice rights with revisions and an ISO verification date, and a gate
 * marked {@code passed} carries evidence. {@link #decide} answers {@code enabled} only when the owner asked for
 * speech, every gate passed, and one candidate is admitted for both English and Italian with an admitted voice;
 * every other combination is disabled with reasons that name the gate or the missing admission. Loading fails
 * with {@link IllegalArgumentException} naming the candidate or gate (never a value) when the record is
 * inconsistent, so a gate cannot be passed by editing one field.
 */
public final class SpeechQualification {
    /** Classpath location of the recorded qualification. */
    public static final String RESOURCE = "/speech/qualification.json";
    /** Locales a speech profile must cover together; there is no silent fallback between them. */
    public static final Set<String> LOCALES = Set.of("en", "it");
    /** Gates of the rollout table, in the order the record lists them. */
    public static final List<String> GATES = List.of("rights-inventory", "container-offline", "bilingual-quality",
            "resources", "persistence-fault");
    /** Gate states; only {@code passed} counts toward enablement. */
    public static final Set<String> GATE_STATUSES = Set.of("pending", "unexecuted", "failed", "passed");
    /** Candidate rights states; only {@code admitted} may be enabled and {@code excluded} never. */
    public static final Set<String> RIGHTS = Set.of("recorded", "admitted", "excluded");
    /** Candidate roles. */
    public static final Set<String> ROLES = Set.of("primary", "fallback", "challenger", "reserve", "excluded");

    private final String recordedOn;
    private final List<Candidate> candidates;
    private final Map<String, Gate> gates;

    private SpeechQualification(String recordedOn, List<Candidate> candidates, Map<String, Gate> gates) {
        this.recordedOn = recordedOn;
        this.candidates = List.copyOf(candidates);
        this.gates = Map.copyOf(gates);
    }

    /**
     * One candidate model with its separate code, weights and voice rights.
     *
     * @param id stable reference
     * @param role one of {@link #ROLES}
     * @param locales engine locales the publisher lists for it
     * @param codeLicense declared license of the inference code and wrappers
     * @param codeRevision inspected repository revisions
     * @param weightsLicense declared license of the weights and tokenizer
     * @param weightsRevision inspected weight revisions and published hashes
     * @param voiceRights {@code admitted} or {@code none-admitted}; a reference recording is never implied
     * @param rights one of {@link #RIGHTS}
     * @param verifiedOn ISO date the declarations were read
     * @param notes limits of the record
     */
    public record Candidate(String id, String role, List<String> locales, String codeLicense, String codeRevision,
            String weightsLicense, String weightsRevision, String voiceRights, String rights, String verifiedOn,
            String notes) {
        /** @return whether it is admitted for English and Italian with an admitted voice */
        public boolean admittedBilingual() {
            return "admitted".equals(rights) && "admitted".equals(voiceRights) && locales.containsAll(LOCALES);
        }
    }

    /**
     * One rollout gate.
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
     * @param enabled whether speech may be offered
     * @param profileId the admitted candidate when enabled, otherwise null
     * @param reasons empty when enabled, otherwise one stable code per unmet condition
     */
    public record Decision(boolean enabled, String profileId, List<String> reasons) {
    }

    /** @return ISO date of the recorded qualification */
    public String recordedOn() {
        return recordedOn;
    }

    /** @return the candidates in record order */
    public List<Candidate> candidates() {
        return candidates;
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
     * Decide whether speech may be offered.
     *
     * @param ownerRequested whether the owner explicitly enabled speech; the default is false
     * @return enabled only when requested, every gate passed and a candidate is admitted for both locales;
     *         otherwise disabled with {@code not-requested}, {@code gate:<id>:<status>} and
     *         {@code no-admitted-bilingual-profile} reasons in that order
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
        Candidate profile = candidates.stream().filter(Candidate::admittedBilingual).findFirst().orElse(null);
        if (profile == null) {
            reasons.add("no-admitted-bilingual-profile");
        }
        return reasons.isEmpty() ? new Decision(true, profile.id(), List.of())
                : new Decision(false, null, List.copyOf(reasons));
    }

    /**
     * Load and validate the bundled record.
     *
     * @return the validated qualification
     * @throws IllegalArgumentException when the record is incomplete or inconsistent
     */
    public static SpeechQualification load() {
        try (InputStream in = SpeechQualification.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalArgumentException("Speech qualification resource is missing");
            }
            return parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parse and validate qualification JSON.
     *
     * @param json document bytes
     * @return the validated qualification
     * @throws IllegalArgumentException when a candidate or gate is incomplete or inconsistent
     */
    public static SpeechQualification parse(byte[] json) {
        Map<?, ?> root = JsonMapper.builder().build().readValue(json, Map.class);
        String recordedOn = text(root, "recordedOn", "record");
        date(recordedOn, "record");
        if (!(root.get("candidates") instanceof List<?> items) || !(root.get("gates") instanceof List<?> gateItems)) {
            throw new IllegalArgumentException("Speech qualification needs candidates and gates lists");
        }
        List<Candidate> candidates = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : items) {
            Candidate c = candidate((Map<?, ?>) item);
            if (!ids.add(c.id())) {
                throw new IllegalArgumentException("Duplicate candidate " + c.id());
            }
            candidates.add(c);
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
            throw new IllegalArgumentException("Speech qualification lacks a gate");
        }
        return new SpeechQualification(recordedOn, candidates, gates);
    }

    private static Candidate candidate(Map<?, ?> m) {
        String id = text(m, "id", "candidate");
        List<String> locales = new ArrayList<>();
        if (!(m.get("locales") instanceof List<?> l) || l.isEmpty()) {
            throw new IllegalArgumentException("Candidate " + id + " lacks locales");
        }
        for (Object o : l) {
            if (!(o instanceof String s) || !LOCALES.contains(s)) {
                throw new IllegalArgumentException("Candidate " + id + " has an unknown locale");
            }
            locales.add(s);
        }
        Candidate c = new Candidate(id, text(m, "role", id), List.copyOf(locales), text(m, "codeLicense", id),
                text(m, "codeRevision", id), text(m, "weightsLicense", id), text(m, "weightsRevision", id),
                text(m, "voiceRights", id), text(m, "rights", id), text(m, "verifiedOn", id), text(m, "notes", id));
        if (!ROLES.contains(c.role()) || !RIGHTS.contains(c.rights())
                || !Set.of("admitted", "none-admitted").contains(c.voiceRights())) {
            throw new IllegalArgumentException("Candidate " + id + " has an unknown role, rights or voice state");
        }
        if ("admitted".equals(c.rights()) && !"admitted".equals(c.voiceRights())) {
            throw new IllegalArgumentException("Candidate " + id + " is admitted without an admitted voice");
        }
        if ("excluded".equals(c.role()) != "excluded".equals(c.rights())) {
            throw new IllegalArgumentException("Candidate " + id + " has an exclusion that role and rights disagree on");
        }
        date(c.verifiedOn(), id);
        return c;
    }

    private static String text(Map<?, ?> m, String field, String id) {
        if (m.get(field) instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException(id + " lacks " + field);
    }

    private static void date(String value, String id) {
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(id + " has no ISO date");
        }
    }
}
