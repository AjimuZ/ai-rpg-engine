package org.rpgengine.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The arithmetic of docs/11 sections 8-9, pinned by the worked numbers of docs/20 section 3 and by exhaustive sweeps
 * (docs/15 section 6): every 2d10 outcome against every stored defense, and every damage combination inside the
 * supported bounds. The sweeps enumerate rather than sample, so they have no seed; their sizes are stated in each case.
 */
class CombatMathTest {
    /** Construct a stateless fixture. */
    CombatMathTest() {
    }

    /** The attack and damage numbers docs/20 section 3 states, including the Guard that Brace or the Guard action gives. */
    @Test
    void reproducesTheWorkedFight() {
        var strong = CombatMath.attack(9, 9, 2, 2, 0, 3, 14, 6, true);
        assertEquals(22, strong.total());
        assertEquals(8, strong.margin());
        assertTrue(strong.hit() && strong.strong());
        var first = CombatMath.damage(4, 2, 0, 2, 1, 0, 0, 14);
        assertEquals(8, first.raw());
        assertEquals(7, first.afterArmor());
        assertEquals(7, first.healthAfter());
        var miss = CombatMath.attack(5, 6, 1, 1, 0, 3, 14, 6, true);
        assertEquals(13, miss.total());
        assertFalse(miss.hit());
        var normal = CombatMath.attack(6, 5, 2, 2, 0, 3, 14, 6, true);
        assertEquals(15, normal.total());
        assertEquals(1, normal.margin());
        assertTrue(normal.hit());
        assertFalse(normal.strong());
        var last = CombatMath.damage(6, 2, 0, 0, 1, 0, 0, 7);
        assertEquals(8, last.raw());
        assertEquals(7, last.healthLoss());
        assertEquals(0, last.healthAfter());
    }

    /** Meeting the defense succeeds, one less misses; the strong-hit threshold is inclusive and only for a permitting attack. */
    @Test
    void meetingTheDefenseHits() {
        assertTrue(CombatMath.attack(5, 5, 0, 0, 0, 3, 10, 6, true).hit());
        assertEquals(0, CombatMath.attack(5, 5, 0, 0, 0, 3, 10, 6, true).margin());
        assertFalse(CombatMath.attack(5, 4, 0, 0, 0, 3, 10, 6, true).hit());
        assertEquals(-1, CombatMath.attack(5, 4, 0, 0, 0, 3, 10, 6, true).margin());
        assertFalse(CombatMath.attack(10, 5, 0, 0, 0, 3, 10, 6, true).strong());
        assertTrue(CombatMath.attack(10, 6, 0, 0, 0, 3, 10, 6, true).strong());
        assertFalse(CombatMath.attack(10, 10, 0, 0, 0, 3, 10, 6, false).strong(), "the attack does not permit it");
        assertTrue(CombatMath.attack(1, 1, 0, 0, 0, 3, 2, 0, true).strong(), "a margin of zero meets a threshold of zero");
        assertFalse(CombatMath.attack(1, 1, 0, 0, 0, 3, 3, 0, true).strong(), "a miss is never strong");
    }

    /** No die value decides by itself: two ones can hit and two tens can miss. */
    @Test
    void noAutomaticOutcomes() {
        assertTrue(CombatMath.attack(1, 1, 0, 0, 0, 3, 2, 6, true).hit());
        assertFalse(CombatMath.attack(10, 10, 0, 0, 0, 3, 21, 6, true).hit());
    }

    /** The situational modifier is clamped in both directions before it counts. */
    @Test
    void situationalModifierIsClamped() {
        assertEquals(13, CombatMath.attack(5, 5, 0, 0, 9, 3, 0, 6, true).total());
        assertEquals(7, CombatMath.attack(5, 5, 0, 0, -9, 3, 0, 6, true).total());
        assertEquals(12, CombatMath.attack(5, 5, 0, 0, 2, 3, 0, 6, true).total());
        assertEquals(10, CombatMath.attack(5, 5, 0, 0, 2, 0, 0, 6, true).total(), "a zero cap removes the modifier");
    }

    /** Stance and cover add up to the situational cap; the base is never capped. */
    @Test
    void effectiveGuardIsCapped() {
        assertEquals(12, CombatMath.guard(12, 0, 0, 4));
        assertEquals(14, CombatMath.guard(12, 2, 0, 4));
        assertEquals(16, CombatMath.guard(12, 2, 2, 4));
        assertEquals(16, CombatMath.guard(12, 4, 2, 4));
        assertEquals(13, CombatMath.guard(12, 2, 2, 1));
    }

    /** Armor can absorb everything, penetration floors at zero, absorption goes before Health and Health stops at zero. */
    @Test
    void damageFollowsItsOrder() {
        var absorbed = CombatMath.damage(1, 0, 0, 0, 4, 0, 0, 10);
        assertEquals(0, absorbed.healthLoss(), "zero damage is valid; no minimum point is invented");
        assertEquals(10, absorbed.healthAfter());
        assertEquals(0, CombatMath.damage(3, 0, 0, 0, 2, 5, 0, 10).effectiveArmor());
        assertEquals(1, CombatMath.damage(3, 0, 0, 0, 2, 1, 0, 10).effectiveArmor());
        assertEquals(2, CombatMath.damage(3, 0, 0, 0, 2, 1, 0, 10).healthLoss());
        var shielded = CombatMath.damage(6, 0, 0, 0, 1, 0, 3, 10);
        assertEquals(5, shielded.afterArmor());
        assertEquals(3, shielded.absorbed());
        assertEquals(2, shielded.healthLoss());
        assertEquals(0, CombatMath.damage(6, 0, 0, 0, 0, 0, 99, 10).healthLoss());
        var fatal = CombatMath.damage(20, 6, 20, 4, 0, 0, 0, 3);
        assertEquals(3, fatal.healthLoss());
        assertEquals(0, fatal.healthAfter());
        var weak = CombatMath.damage(1, 0, -5, 0, 0, 0, 0, 10);
        assertEquals(-4, weak.raw());
        assertEquals(0, weak.afterArmor(), "a negative result is zero damage, never healing");
        assertEquals(10, weak.healthAfter());
    }

    /** Healing restores at most the missing Health, never revives and is never negative. */
    @Test
    void healingRestoresOnlyWhatIsMissing() {
        assertEquals(8, CombatMath.healed(16, 24, 8));
        assertEquals(3, CombatMath.healed(21, 24, 8));
        assertEquals(0, CombatMath.healed(24, 24, 8));
        assertEquals(0, CombatMath.healed(0, 24, 8), "a defeated participant needs a declared revival");
        assertEquals(0, CombatMath.healed(10, 24, -3));
        assertEquals(1, CombatMath.healed(1, 24, 1));
    }

    /**
     * Every one of the 100 two-die outcomes against every stored defense 0..40 (4,100 rolls): the count of hits is the
     * two-dice distribution counted independently of the rule under test, strong hits follow the same count with the
     * threshold added, and every roll is consistent with itself.
     */
    @Test
    void everyTwoDieOutcomeMatchesTheDistribution() {
        int[] ways = new int[21];
        for (int total = 2; total <= 20; total++) {
            ways[total] = total <= 11 ? total - 1 : 21 - total;
        }
        for (int defense = 0; defense <= 40; defense++) {
            int hits = 0;
            int strong = 0;
            for (int first = 1; first <= 10; first++) {
                for (int second = 1; second <= 10; second++) {
                    var roll = CombatMath.attack(first, second, 0, 0, 0, 3, defense, 6, true);
                    assertEquals(first + second, roll.total());
                    assertEquals(roll.total() - defense, roll.margin());
                    assertEquals(roll.margin() >= 0, roll.hit());
                    hits += roll.hit() ? 1 : 0;
                    strong += roll.strong() ? 1 : 0;
                }
            }
            assertEquals(ways(ways, defense), hits, "hits against " + defense);
            assertEquals(ways(ways, defense + 6), strong, "strong hits against " + defense);
        }
    }

    /** Number of two-die outcomes of at least a total. */
    private static int ways(int[] ways, int atLeast) {
        int count = 0;
        for (int total = Math.max(2, atLeast); total <= 20; total++) {
            count += ways[total];
        }
        return count;
    }

    /**
     * Every damage combination inside the bounds (about 1.25 million): armor 0..10, penetration 0..10, absorption 0..8,
     * Health 0..24 and raw parts from -5 to 40. Health and every derived value stay in bounds, and the parts add up.
     */
    @Test
    void damageStaysInBoundsOverEverySupportedValue() {
        for (int raw = -5; raw <= 40; raw++) {
            for (int armor = 0; armor <= 10; armor++) {
                for (int penetration = 0; penetration <= 10; penetration++) {
                    for (int absorption = 0; absorption <= 8; absorption++) {
                        for (int health = 0; health <= 24; health++) {
                            var result = CombatMath.damage(raw, 0, 0, 0, armor, penetration, absorption, health);
                            assertEquals(raw, result.raw());
                            assertTrue(result.effectiveArmor() >= 0 && result.effectiveArmor() <= armor);
                            assertEquals(Math.max(0, raw - result.effectiveArmor()), result.afterArmor());
                            assertTrue(result.absorbed() >= 0 && result.absorbed() <= absorption);
                            assertTrue(result.healthLoss() >= 0 && result.healthLoss() <= health);
                            assertEquals(health - result.healthLoss(), result.healthAfter());
                            assertTrue(result.absorbed() + result.healthLoss() <= result.afterArmor());
                        }
                    }
                }
            }
        }
    }

    /** Every healing combination up to a maximum of 40: the result stays between the current value and the maximum. */
    @Test
    void healingStaysInBoundsOverEverySupportedValue() {
        for (int maximum = 0; maximum <= 40; maximum++) {
            for (int health = 0; health <= maximum; health++) {
                for (int amount = -2; amount <= 45; amount++) {
                    long restored = CombatMath.healed(health, maximum, amount);
                    assertTrue(restored >= 0 && health + restored <= maximum);
                    assertEquals(health == 0 || amount < 0 ? 0 : Math.min(maximum - health, amount), restored);
                }
            }
        }
    }
}
