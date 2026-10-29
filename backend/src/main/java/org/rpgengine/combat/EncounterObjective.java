package org.rpgengine.combat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One frozen encounter objective and its pure evaluation (P11.1, docs/11 sections 13-14, docs/12 section 3).
 * Objectives are structured predicates over the encounter's mechanical state, never prose: a narration that
 * says the gate was reached establishes nothing. Evaluation reads a {@link Progress} view and returns
 * {@link Status#MET}, {@link Status#PENDING} or {@link Status#FAILED}; turning statuses into an outcome and
 * settling it exactly once is P11.5.
 *
 * <p>Semantics, all for the player's side:
 * <ul>
 *   <li>{@link Kind#DEFEAT_HOSTILES}: met when no hostile participant remains active.</li>
 *   <li>{@link Kind#ESCAPE}: met when the player stands in one of the encounter's escape zones.</li>
 *   <li>{@link Kind#REACH_ZONE}: met when the player stands in {@link #zone}.</li>
 *   <li>{@link Kind#SURVIVE}: met once {@link #rounds} rounds have completed.</li>
 *   <li>{@link Kind#PROTECT}: fails as soon as the allied {@link #participant} is defeated; met when no hostile
 *       remains active and the ally stands.</li>
 * </ul>
 * A defeated player fails every objective: the default defeat policy is incapacitation, not death, and no
 * objective is met by an incapacitated protagonist.
 *
 * @param id stable objective ID, unique within the encounter
 * @param kind predicate kind
 * @param zone target zone of {@link Kind#REACH_ZONE}, otherwise null
 * @param participant protected ally of {@link Kind#PROTECT}, otherwise null
 * @param rounds rounds to survive for {@link Kind#SURVIVE} (at least one), otherwise 0
 */
public record EncounterObjective(String id, Kind kind, String zone, String participant, int rounds) {
    /** Predicate kinds; the wire names are the pack's. */
    public enum Kind {
        DEFEAT_HOSTILES("defeatHostiles"),
        ESCAPE("escape"),
        REACH_ZONE("reachZone"),
        SURVIVE("survive"),
        PROTECT("protect");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        /** @return the pack wire name */
        public String wire() {
            return wire;
        }

        /**
         * Parse a pack wire name.
         *
         * @param wire one of the documented names
         * @return the kind
         * @throws IllegalArgumentException for any other text
         */
        public static Kind parse(String wire) {
            for (Kind kind : values()) {
                if (kind.wire.equals(wire)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown objective kind " + wire);
        }
    }

    /** Evaluation result. */
    public enum Status {
        /** Not yet decided. */
        PENDING,
        /** The predicate holds. */
        MET,
        /** The predicate can no longer hold. */
        FAILED;

        /** @return the lowercase wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Mechanical facts an objective reads; implemented over committed encounter state. */
    public interface Progress {
        /** @return whether any hostile participant is still active (not defeated, escaped or surrendered) */
        boolean hostilesActive();

        /** @return the player's current zone */
        String playerZone();

        /** @return whether the player is defeated */
        boolean playerDefeated();

        /**
         * @param participant participant ID
         * @return whether that participant is defeated
         */
        boolean defeated(String participant);

        /** @return number of fully completed rounds */
        int completedRounds();

        /** @return the encounter's escape zones */
        Set<String> escapeZones();
    }

    /** Require the fields each kind needs and forbid the others, so a frozen objective is unambiguous. */
    public EncounterObjective {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.REACH_ZONE) != (zone != null)) {
            throw new IllegalArgumentException("only a reachZone objective names a zone");
        }
        if ((kind == Kind.PROTECT) != (participant != null)) {
            throw new IllegalArgumentException("only a protect objective names a participant");
        }
        if ((kind == Kind.SURVIVE) != (rounds > 0) || rounds < 0) {
            throw new IllegalArgumentException("only a survive objective counts rounds, at least one");
        }
    }

    /**
     * Evaluate this objective.
     *
     * @param progress current mechanical facts
     * @return the status those facts establish
     */
    public Status evaluate(Progress progress) {
        if (progress.playerDefeated()) {
            return Status.FAILED;
        }
        return switch (kind) {
            case DEFEAT_HOSTILES -> progress.hostilesActive() ? Status.PENDING : Status.MET;
            case ESCAPE -> progress.escapeZones().contains(progress.playerZone()) ? Status.MET : Status.PENDING;
            case REACH_ZONE -> zone.equals(progress.playerZone()) ? Status.MET : Status.PENDING;
            case SURVIVE -> progress.completedRounds() >= rounds ? Status.MET : Status.PENDING;
            case PROTECT -> progress.defeated(participant) ? Status.FAILED
                    : progress.hostilesActive() ? Status.PENDING : Status.MET;
        };
    }

    /** @return the frozen form: ID, wire kind and only the parameter the kind uses */
    public Map<String, Object> canonicalForm() {
        var form = new LinkedHashMap<String, Object>();
        form.put("id", id);
        form.put("kind", kind.wire());
        switch (kind) {
            case REACH_ZONE -> form.put("zone", zone);
            case PROTECT -> form.put("participant", participant);
            case SURVIVE -> form.put("rounds", rounds);
            default -> { }
        }
        return form;
    }
}
