package org.rpgengine.combat;

/**
 * Combat data of one equippable item (P11.3, docs/11 sections 4 and 9): what it adds to the wearer's frozen profile
 * at entry. It has effect only while worn, and worn items are read once, when the encounter freezes.
 *
 * @param guard equipment Guard modifier; the sum over worn items is capped by the profile's {@code equipmentGuardCap}
 * @param armor flat armor; the sum over worn items is capped by the profile's {@code armorMax}
 * @param skill canonical skill the attack trains, or null
 * @param attack the attack it grants, with skill rank zero until a wearer freezes it, or null
 */
public record EncounterGear(int guard, int armor, String skill, CombatAttack attack) {
    /** Reject a negative modifier, which the compiler already refuses. */
    public EncounterGear {
        if (guard < 0 || armor < 0) {
            throw new IllegalArgumentException("equipment modifiers cannot be negative");
        }
    }
}
