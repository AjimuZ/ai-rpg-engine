package org.rpgengine.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.rpgengine.rules.CombatProfile;

/**
 * The player's equipment-derived fighting values after a Ready/equip action changed what it wears (P11.5, docs/11
 * section 7, ADR 0034). The frozen profile keeps the values of entry and never changes; once the player readies or
 * stows an item, this value replaces its Guard, armor and attacks for the rest of the fight. It is stored on the
 * player's participant row (V31) and computed once, when the action resolves, so no later operation reads the pack.
 *
 * @param guard Guard before any stance, cover or effect: {@code guardBase + finesse} plus the capped equipment Guard
 * @param armor flat armor of the worn set, capped by {@code armorMax}
 * @param attacks frozen attacks of the worn weapons, in canonical item order
 * @param equipment canonical item definitions worn after the action, sorted, one entry per worn instance
 */
public record ReadiedGear(int guard, int armor, List<CombatAttack> attacks, List<String> equipment) {
    /** Reject negative values; copy the lists, the equipment sorted. */
    public ReadiedGear {
        if (guard < 0 || armor < 0) {
            throw new IllegalArgumentException("readied values cannot be negative");
        }
        attacks = List.copyOf(attacks);
        equipment = Objects.requireNonNull(equipment, "equipment").stream().sorted().toList();
    }

    /**
     * Compute the readied values of a worn set with the same rules as entry.
     *
     * @param profile the encounter's frozen profile
     * @param sheet the player's current fighting values, whose frozen roles give the finesse part of Guard
     * @param worn canonical item definitions worn after the action, one entry per worn instance
     * @param gear combat data of every item that has any, by canonical item ID
     * @param skills the player's trained ranks by canonical skill ID
     * @return the values that replace the player's Guard, armor and attacks
     */
    public static ReadiedGear of(CombatProfile profile, EncounterState.Sheet sheet, List<String> worn,
            Map<String, EncounterGear> gear, Map<String, Long> skills) {
        var combined = EncounterGear.worn(worn, gear, skills, profile);
        return new ReadiedGear(Math.toIntExact(profile.value("guardBase") + sheet.role("finesse") + combined.guard()),
                Math.toIntExact(combined.armor()), combined.attacks(), worn);
    }

    /** @return the stored canonical form */
    public Map<String, Object> canonicalForm() {
        var form = new LinkedHashMap<String, Object>();
        form.put("guard", guard);
        form.put("armor", armor);
        form.put("attacks", attacks.stream().map(attack -> (Object) attack.canonicalForm()).toList());
        form.put("equipment", equipment);
        return form;
    }

    /**
     * Read a stored form back.
     *
     * @param form parsed {@link #canonicalForm}
     * @return the readied values
     * @throws ClassCastException, {@link NullPointerException} or {@link IllegalArgumentException} for a malformed form
     */
    public static ReadiedGear fromCanonical(Map<?, ?> form) {
        var attacks = new ArrayList<CombatAttack>();
        ((List<?>) form.get("attacks")).forEach(attack -> attacks.add(CombatAttack.fromCanonical((Map<?, ?>) attack)));
        var equipment = new ArrayList<String>();
        ((List<?>) form.get("equipment")).forEach(item -> equipment.add((String) item));
        return new ReadiedGear(((Number) form.get("guard")).intValue(), ((Number) form.get("armor")).intValue(), attacks,
                equipment);
    }
}
