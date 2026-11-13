package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.rpgengine.rules.CombatProfile;

/**
 * Combat data of one equippable item (P11.3, docs/11 sections 4 and 9): what it adds to the wearer's frozen profile
 * at entry. It has effect only while worn. Worn items are read when the encounter freezes and again, through
 * {@link #worn}, when a Ready/equip action changes what the player wears (P11.5, ADR 0034).
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

    /**
     * What a whole worn set adds to its wearer.
     *
     * @param guard equipment Guard modifier of the set, already capped by {@code equipmentGuardCap}
     * @param armor flat armor of the set, already capped by {@code armorMax}
     * @param attacks one frozen attack per distinct worn weapon, in canonical item order
     */
    public record Worn(long guard, long armor, List<CombatAttack> attacks) {
        /** Copy the attacks. */
        public Worn {
            attacks = List.copyOf(attacks);
        }
    }

    /**
     * Combine the combat data of a worn set. Pure: the same set, data and ranks always give the same values, so entry and a
     * later Ready/equip action agree on what one set of equipment is worth.
     *
     * @param worn canonical item definitions worn, one entry per worn instance, in any order
     * @param gear combat data of every item that has any, by canonical item ID; a worn item without an entry adds nothing
     * @param skills the wearer's trained ranks by canonical skill ID
     * @param profile the profile whose caps apply
     * @return the capped modifiers and the attacks, each with the wearer's rank in its skill under the attack-skill cap
     */
    public static Worn worn(List<String> worn, Map<String, EncounterGear> gear, Map<String, Long> skills,
            CombatProfile profile) {
        long guard = 0;
        long armor = 0;
        var attacks = new ArrayList<CombatAttack>();
        // Sorted and distinct, so the attacks and their order follow from what is worn, not from storage order.
        for (String item : new TreeSet<>(worn)) {
            EncounterGear data = gear.get(item);
            if (data != null) {
                int count = Collections.frequency(worn, item);
                guard += (long) data.guard() * count;
                armor += (long) data.armor() * count;
                if (data.attack() != null) {
                    long rank = data.skill() == null ? 0 : skills.getOrDefault(data.skill(), 0L);
                    attacks.add(data.attack().forWearer(item, Math.toIntExact(Math.min(rank, CombatProfile.ATTACK_SKILL_MAX))));
                }
            }
        }
        return new Worn(Math.min(guard, profile.value("equipmentGuardCap")), Math.min(armor, profile.value("armorMax")),
                attacks);
    }
}
