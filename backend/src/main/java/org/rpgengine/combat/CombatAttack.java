package org.rpgengine.combat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.rpgengine.rules.CombatProfile;

/**
 * One frozen attack a participant can make (P11.3, docs/11 sections 8-9). A weapon the player wears at entry or an
 * attack a template declares becomes one of these; it is stored in the participant's frozen profile and never read
 * from the pack again, so a later content revision cannot change a fight in progress. It is original pack data: a
 * neutral blade is {@code 1d6 + force}, a device {@code 1d6 + finesse} with one unit of ammunition per attempt.
 *
 * <p>Invariants, checked here and by the pack compiler: 1..{@link CombatProfile#ATTACK_DICE_MAX} damage dice of
 * 2..{@link CombatProfile#ATTACK_SIDES_MAX} sides, bounded flat modifier, penetration, skill rank and range. The dice
 * bound keeps every operation inside the durable draw budget even at the largest roster.
 *
 * @param id stable attack ID: the canonical item definition of a weapon, or the template's attack ID
 * @param attribute ruleset role added to the attack score
 * @param skill trained skill rank added to the attack score, already read at entry
 * @param diceCount damage dice
 * @param diceSides faces of each damage die
 * @param damageAttribute ruleset role added to raw damage, or null when no attribute contributes
 * @param flat admitted flat damage modifier
 * @param penetration armor the attack ignores
 * @param range greatest zone distance; 0 is melee, which requires the same zone
 * @param defense which defense the attack is compared against
 * @param strongHit whether a large margin adds the strong-hit die
 * @param ammunition canonical item definition one unit of which each attempt consumes, hit or miss, or null
 */
public record CombatAttack(String id, String attribute, int skill, int diceCount, int diceSides, String damageAttribute,
        int flat, int penetration, int range, Defense defense, boolean strongHit, String ammunition) {
    /** The defense an attack is compared against; the attack fixes it, never the moment. */
    public enum Defense {
        /** Physical attacks; the Guard action and cover raise it. */
        GUARD,
        /** Explicitly mental or energy contests; neither stance nor cover applies. */
        RESISTANCE;

        /** @return the lowercase pack wire name */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Check every bound; a violation here is a broken compiler guarantee. */
    public CombatAttack {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(defense, "defense");
        role(attribute);
        if (damageAttribute != null) {
            role(damageAttribute);
        }
        if (skill < 0 || skill > CombatProfile.ATTACK_SKILL_MAX || diceCount < 1
                || diceCount > CombatProfile.ATTACK_DICE_MAX || diceSides < 2
                || diceSides > CombatProfile.ATTACK_SIDES_MAX || flat < CombatProfile.ATTACK_FLAT_MIN
                || flat > CombatProfile.ATTACK_FLAT_MAX || penetration < 0
                || penetration > CombatProfile.ATTACK_PENETRATION_MAX || range < 0
                || range > CombatProfile.PARAMETERS.get("maxRangeEdges").max()) {
            throw new IllegalArgumentException("attack " + id + " has a value outside its bound");
        }
    }

    /** @return whether the attack can cross zones, which makes cover and line of sight relevant */
    public boolean ranged() {
        return range > 0;
    }

    /**
     * The same attack under another identity with the attacker's skill rank, as a weapon freezes at entry.
     *
     * @param weapon canonical item definition
     * @param rank the wearer's rank in the weapon's skill
     * @return the frozen attack
     */
    public CombatAttack forWearer(String weapon, int rank) {
        return new CombatAttack(weapon, attribute, rank, diceCount, diceSides, damageAttribute, flat, penetration, range,
                defense, strongHit, ammunition);
    }

    /** @return canonical frozen form, part of the participant's frozen profile */
    public Map<String, Object> canonicalForm() {
        var form = new LinkedHashMap<String, Object>();
        form.put("id", id);
        form.put("attribute", attribute);
        form.put("skill", skill);
        form.put("diceCount", diceCount);
        form.put("diceSides", diceSides);
        form.put("damageAttribute", damageAttribute);
        form.put("flat", flat);
        form.put("penetration", penetration);
        form.put("range", range);
        form.put("defense", defense.wire());
        form.put("strongHit", strongHit);
        form.put("ammunition", ammunition);
        return form;
    }

    /**
     * Read the canonical form {@link #canonicalForm()} wrote.
     *
     * @param form parsed canonical object
     * @return the attack
     * @throws IllegalArgumentException or {@link ClassCastException} for a malformed frozen form
     */
    public static CombatAttack fromCanonical(Map<?, ?> form) {
        return new CombatAttack((String) form.get("id"), (String) form.get("attribute"),
                ((Number) form.get("skill")).intValue(), ((Number) form.get("diceCount")).intValue(),
                ((Number) form.get("diceSides")).intValue(), (String) form.get("damageAttribute"),
                ((Number) form.get("flat")).intValue(), ((Number) form.get("penetration")).intValue(),
                ((Number) form.get("range")).intValue(),
                Defense.valueOf(((String) form.get("defense")).toUpperCase(Locale.ROOT)),
                Boolean.TRUE.equals(form.get("strongHit")), (String) form.get("ammunition"));
    }

    /** Fail unless a role of the default ruleset is named. */
    private static void role(String role) {
        if (!CombatProfile.ROLES.contains(role)) {
            throw new IllegalArgumentException("unknown ruleset role " + role);
        }
    }
}
