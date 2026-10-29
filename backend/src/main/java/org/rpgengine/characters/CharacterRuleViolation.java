package org.rpgengine.characters;

import java.util.List;
import java.util.Objects;

import org.rpgengine.rules.EquipmentRules;

/**
 * A character operation the pinned rules refuse. It is thrown before any row is written, so a
 * caller that catches it inside its transaction can report a rejection and still commit other
 * work; the exception never carries pack prose, only stable identifiers and rule reasons.
 */
public final class CharacterRuleViolation extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Machine-readable cause of a refusal. */
    public enum Reason {
        /** The actor does not exist in the campaign. */
        UNKNOWN_ACTOR,
        /** The pinned rules know no such condition, ability or item definition, or the actor has no vocabulary. */
        UNKNOWN_DEFINITION,
        /** The item instance is not held by the actor. */
        UNKNOWN_ITEM,
        /** The ability is already learned. */
        ALREADY_LEARNED,
        /** A skill rank or prerequisite ability is missing. */
        PREREQUISITE_MISSING,
        /** Equipment constraints failed; see {@link #equipment()}. */
        EQUIPMENT,
        /** The item instance is not currently worn. */
        NOT_WORN,
        /** The revision is older than the state it would change. */
        STALE_REVISION
    }

    private final Reason reason;
    private final transient List<EquipmentRules.Violation> equipment;

    /**
     * @param reason the cause
     * @param message plain-English diagnostic with identifiers only
     * @param equipment equipment violations in rule order; empty unless the reason is {@link Reason#EQUIPMENT}
     */
    CharacterRuleViolation(Reason reason, String message, List<EquipmentRules.Violation> equipment) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.equipment = List.copyOf(equipment);
    }

    /** @return the cause */
    public Reason reason() {
        return reason;
    }

    /** @return equipment violations in rule order; empty for other reasons */
    public List<EquipmentRules.Violation> equipment() {
        return equipment;
    }
}
