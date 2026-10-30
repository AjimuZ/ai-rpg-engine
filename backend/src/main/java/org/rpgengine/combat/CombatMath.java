package org.rpgengine.combat;

/**
 * The attack, defense, damage and healing arithmetic of AI RPG Skirmish (P11.3, docs/11 sections 8-9), as pure
 * functions of already recorded dice and frozen values. Nothing here draws, stores or decides legality: the same
 * arguments always give the same result, so a stored calculation can be recomputed from its recorded draws.
 *
 * <p>Rules, each pinned by {@code CombatMathTest}:
 * <ul>
 *   <li><b>Attack score</b> {@code 2d10 + attribute + skill + situational}, the situational modifier clamped to
 *       the profile's cap in both directions. There is no automatic success or failure on any die value.</li>
 *   <li><b>Hit</b>: meeting the defense succeeds. A margin of at least the profile's {@code strongHitMargin} is a
 *       strong hit when the attack permits one; a miss is never strong.</li>
 *   <li><b>Effective Guard</b>: base Guard plus the stance bonus (Guard action and Brace share one stacking key, so
 *       the caller passes the higher one, never their sum) plus cover, the situational part capped by
 *       {@code situationalGuardCap}.</li>
 *   <li><b>Damage</b>: {@code raw = dice + attribute + flat + strong die}; {@code effective armor = max(0, armor -
 *       penetration)}; {@code taken = max(0, raw - effective armor)}. Absorption is consumed after armor and before
 *       Health, and Health never falls below zero. Zero damage is a valid outcome: no minimum point is invented.</li>
 *   <li><b>Healing</b> restores at most the missing Health and never revives: a participant at zero stays there.</li>
 * </ul>
 */
public final class CombatMath {
    /** Static arithmetic only. */
    private CombatMath() {
    }

    /**
     * Result of one attack roll.
     *
     * @param total attack score
     * @param hit whether the score met the defense
     * @param margin score minus defense, negative on a miss
     * @param strong whether the hit is a strong hit
     */
    public record Roll(int total, boolean hit, int margin, boolean strong) {
    }

    /**
     * Result of applying one damaging hit.
     *
     * @param raw raw damage before armor; may be negative with a negative flat modifier
     * @param effectiveArmor armor left after penetration, at least zero
     * @param afterArmor damage left after armor, at least zero
     * @param absorbed part of it consumed by temporary absorption
     * @param healthLoss Health actually lost, never more than the Health there was
     * @param healthAfter Health left, at least zero
     */
    public record Damage(int raw, int effectiveArmor, int afterArmor, int absorbed, int healthLoss, int healthAfter) {
    }

    /**
     * Roll an attack from recorded dice.
     *
     * @param first first d10
     * @param second second d10
     * @param attribute relevant attribute value
     * @param skill trained skill rank
     * @param situational situational modifier before clamping
     * @param modifierCap the profile's {@code situationalModifierCap}, at least zero
     * @param defense effective defense of the target
     * @param strongMargin the profile's {@code strongHitMargin}
     * @param strongAllowed whether this attack permits a strong hit
     * @return the roll
     */
    public static Roll attack(int first, int second, int attribute, int skill, int situational, int modifierCap,
            int defense, int strongMargin, boolean strongAllowed) {
        int total = first + second + attribute + skill + Math.max(-modifierCap, Math.min(modifierCap, situational));
        int margin = total - defense;
        boolean hit = margin >= 0;
        return new Roll(total, hit, margin, hit && strongAllowed && margin >= strongMargin);
    }

    /**
     * Effective Guard against one attack.
     *
     * @param base frozen Guard, equipment included
     * @param stance the stance bonus: the higher of Guard action and Brace, never both, zero without either
     * @param cover cover bonus that applies to this attack, zero when none does
     * @param cap the profile's {@code situationalGuardCap}
     * @return base plus the capped situational part
     */
    public static int guard(int base, int stance, int cover, int cap) {
        return base + Math.min(cap, stance + cover);
    }

    /**
     * Effective Guard with declared effects (P11.4).
     *
     * @param base frozen Guard, equipment included
     * @param stance the stance bonus: the higher of Guard action and Brace, never both
     * @param cover cover bonus that applies to this attack
     * @param modifiers sum of active Guard modifiers, possibly negative
     * @param cap the profile's {@code situationalGuardCap}, which bounds the situational part in both directions
     * @return base plus the capped situational part, at least zero
     */
    public static int guard(int base, int stance, int cover, int modifiers, int cap) {
        return Math.max(0, base + Math.max(-cap, Math.min(cap, stance + cover + modifiers)));
    }

    /**
     * Effective Resistance with declared effects (P11.4). Neither stance nor cover applies.
     *
     * @param base frozen Resistance
     * @param modifiers sum of active Resistance modifiers, possibly negative
     * @param cap the profile's {@code situationalGuardCap}, which bounds them in both directions
     * @return base plus the capped modifiers, at least zero
     */
    public static int resistance(int base, int modifiers, int cap) {
        return Math.max(0, base + Math.max(-cap, Math.min(cap, modifiers)));
    }

    /**
     * Apply one damaging hit.
     *
     * @param dice sum of the attack's recorded damage dice
     * @param attribute attribute contribution
     * @param flat admitted flat modifier, possibly negative
     * @param strongDie recorded strong-hit die, zero without a strong hit
     * @param armor the target's flat armor
     * @param penetration the attack's penetration
     * @param absorption temporary absorption available to the target
     * @param health the target's Health before the hit, at least zero
     * @return the damage calculation
     */
    public static Damage damage(int dice, int attribute, int flat, int strongDie, int armor, int penetration,
            int absorption, int health) {
        int raw = dice + attribute + flat + strongDie;
        int effectiveArmor = Math.max(0, armor - penetration);
        int afterArmor = Math.max(0, raw - effectiveArmor);
        int absorbed = Math.min(absorption, afterArmor);
        int loss = Math.min(health, afterArmor - absorbed);
        return new Damage(raw, effectiveArmor, afterArmor, absorbed, loss, health - loss);
    }

    /**
     * Health a healing amount actually restores.
     *
     * @param health current Health above the pool's minimum
     * @param maximum Health maximum above the pool's minimum
     * @param amount healing amount; a negative amount restores nothing
     * @return points restored: zero for a defeated target (revival needs its own declared effect), otherwise at most
     *         the missing Health
     */
    public static long healed(long health, long maximum, long amount) {
        return health <= 0 ? 0 : Math.max(0, Math.min(maximum - health, amount));
    }
}
