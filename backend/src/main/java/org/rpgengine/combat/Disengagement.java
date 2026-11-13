package org.rpgengine.combat;

import java.util.Locale;

/**
 * Declared disengagement policy of an encounter template (P11.5, docs/11 section 13, ADR 0035): what a fight that no other
 * terminal predicate has decided becomes when its last allowed round, the profile's {@code roundCap}, ends. Either way the
 * cap only names the outcome: it changes no participant's Health, status, zone, effects or resources, so it never harms
 * anybody, and the fight stops at {@code OUTCOME_PENDING} for settlement instead of waiting at its round end forever.
 */
public enum Disengagement {
    /** Default. Neither side prevails: the fight ends as a stalemate, every participant standing where it stands. */
    STALEMATE,
    /**
     * The sides break off and the player gets away: the fight ends as an escape. No participant moves, so an escape objective
     * keeps its own status, and the verdict says the cap decided it.
     */
    ESCAPE;

    /** @return the pack wire name */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a pack wire name.
     *
     * @param wire {@code stalemate} or {@code escape}
     * @return the policy
     * @throws IllegalArgumentException for any other text
     */
    public static Disengagement parse(String wire) {
        for (Disengagement policy : values()) {
            if (policy.wire().equals(wire)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("unknown disengagement policy " + wire);
    }
}
