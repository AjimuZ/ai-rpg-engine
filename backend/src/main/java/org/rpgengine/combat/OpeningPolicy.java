package org.rpgengine.combat;

import java.util.Locale;

/**
 * Explicit opening-action policy of an encounter template (P11.1, docs/11 section 3). When the action that
 * starts a fight is itself an attack, the engine enters combat first and executes that attack exactly once,
 * at the slot this policy allows. Entry itself never applies the attack: it draws no die and changes no
 * Health, so the same blow can never land once in narrative mode and again on the first tactical turn.
 * Without an opening attack every policy simply enters combat.
 */
public enum OpeningPolicy {
    /**
     * Default. The opening strike is held as the initiator's first action and resolves at the initiator's own
     * initiative slot in round one, paying its action points from that activation. Faster actors act first.
     */
    INITIATIVE,
    /**
     * The opening strike resolves in a dedicated opening slot before round one's first activation (the
     * initiator caught the opposition unprepared). Its action points are still paid from the initiator's
     * round-one activation, so surprise moves the blow earlier but never adds an action.
     */
    FIRST,
    /** The encounter cannot be opened by an attack (for example a parley that must start peacefully). */
    FORBIDDEN;

    /** @return the pack wire name */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a pack wire name.
     *
     * @param wire {@code initiative}, {@code first} or {@code forbidden}
     * @return the policy
     * @throws IllegalArgumentException for any other text
     */
    public static OpeningPolicy parse(String wire) {
        for (OpeningPolicy policy : values()) {
            if (policy.wire().equals(wire)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("unknown opening policy " + wire);
    }
}
