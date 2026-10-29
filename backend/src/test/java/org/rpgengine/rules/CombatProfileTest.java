package org.rpgengine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * P11.1 combat profile: the documented AI RPG Skirmish defaults, bounded per-adventure overrides, the
 * cross-parameter invariants and a canonical frozen form whose identity follows every value but not map order.
 */
class CombatProfileTest {
    private static final Map<String, String> ROLES = Map.of("force", "might", "finesse", "awareness",
            "insight", "awareness", "resolve", "nerve");

    /** Construct a stateless fixture. */
    CombatProfileTest() {
    }

    /** Every provisional number of docs/11 section 17 is the default, and the kernel is the fixed one. */
    @Test
    void defaultsAreTheDocumentedProvisionalValues() {
        var profile = CombatProfile.resolve("pack", ROLES, "health", "focus", Map.of());
        var expected = new HashMap<String, Integer>();
        expected.putAll(Map.of("actionPoints", 3, "reactionsPerRound", 1, "roundSeconds", 8, "roundCap", 30,
                "guardBase", 10, "resistanceBase", 10, "equipmentGuardCap", 3, "situationalGuardCap", 4, "armorMax", 4,
                "guardBonus", 2));
        expected.putAll(Map.of("partialCoverBonus", 2, "situationalModifierCap", 3, "strongHitMargin", 6,
                "strongHitDieSides", 4, "maxRangeEdges", 2, "speechPerActivation", 1, "difficultTerrainMultiplier", 2,
                "moveCost", 1, "strikeCost", 2, "guardCost", 1));
        expected.putAll(Map.of("useItemCost", 2, "readyCost", 1, "interactCost", 1, "assessCost", 1, "withdrawCost", 2));
        assertEquals(expected, profile.values());
        assertEquals(CombatProfile.defaults(), profile.values());
        assertEquals(3, profile.actionPoints());
        assertEquals(8, profile.roundSeconds());
        assertEquals(1, profile.reactionsPerRound());
        assertEquals(30, profile.roundCap());
        assertEquals(List.of("force", "finesse", "insight", "resolve"), CombatProfile.ROLES);
        assertEquals(2, CombatProfile.INITIATIVE_DICE);
        assertEquals(10, CombatProfile.INITIATIVE_SIDES);
        assertEquals(1, CombatProfile.REACTION_DEPTH);
        assertTrue(profile.canonical().contains("\"kernel\":\"" + CombatProfile.KERNEL + "\""));
        assertTrue(profile.canonical().contains("\"scheduler\":\"initiative-rounds\",\"space\":\"zones\""));
    }

    /** A pack may set any parameter inside its bound; the others keep their default. */
    @Test
    void overridesStayInsideTheirBounds() {
        var tuned = CombatProfile.resolve("pack", ROLES, "health", null,
                Map.of("actionPoints", 4, "roundSeconds", 6, "strikeCost", 3));
        assertEquals(4, tuned.actionPoints());
        assertEquals(6, tuned.roundSeconds());
        assertEquals(3, tuned.value("strikeCost"));
        assertEquals(2, tuned.value("withdrawCost"));
        for (var entry : CombatProfile.PARAMETERS.entrySet()) {
            var bound = entry.getValue();
            assertThrows(IllegalArgumentException.class, () -> CombatProfile.resolve("pack", ROLES, "health", null,
                    Map.of(entry.getKey(), bound.max() + 1)), entry.getKey());
            assertThrows(IllegalArgumentException.class, () -> CombatProfile.resolve("pack", ROLES, "health", null,
                    Map.of(entry.getKey(), bound.min() - 1)), entry.getKey());
        }
        var unknown = assertThrows(IllegalArgumentException.class, () -> CombatProfile.resolve("pack", ROLES, "health",
                null, Map.of("initiativeDice", 3)));
        assertTrue(unknown.getMessage().contains("initiativeDice"));
        assertThrows(IllegalArgumentException.class, () -> tuned.value("reactionDepth"));
    }

    /** Costs fit one activation, a difficult edge stays traversable and bonuses fit the situational cap. */
    @Test
    void crossParameterInvariantsHold() {
        var cost = assertThrows(IllegalArgumentException.class, () -> CombatProfile.resolve("pack", ROLES, "health", null,
                Map.of("actionPoints", 2, "strikeCost", 3)));
        assertTrue(cost.getMessage().contains("strikeCost"), cost.getMessage());
        // Withdraw costs 2 by default, so fewer than two action points already conflict.
        assertThrows(IllegalArgumentException.class, () -> CombatProfile.resolve("pack", ROLES, "health", null,
                Map.of("actionPoints", 1)));
        assertThrows(IllegalArgumentException.class, () -> CombatProfile.resolve("pack", ROLES, "health", null,
                Map.of("moveCost", 2, "difficultTerrainMultiplier", 2)));
        assertThrows(IllegalArgumentException.class, () -> CombatProfile.resolve("pack", ROLES, "health", null,
                Map.of("situationalGuardCap", 1)));
        CombatProfile.resolve("pack", ROLES, "health", null, Map.of("actionPoints", 6, "moveCost", 3,
                "difficultTerrainMultiplier", 2));
    }

    /** The role mapping is complete and Health differs from Focus. */
    @Test
    void mappingIsComplete() {
        assertThrows(IllegalArgumentException.class, () -> CombatProfile.resolve("pack",
                Map.of("force", "might", "finesse", "awareness", "insight", "awareness"), "health", null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> CombatProfile.resolve("pack", ROLES, "vigor", "vigor", Map.of()));
    }

    /** Equal profiles freeze to identical text and hash whatever the map order; any change moves the hash. */
    @Test
    void frozenFormIsCanonical() {
        var shuffled = new LinkedHashMap<String, Integer>();
        shuffled.put("strikeCost", 3);
        shuffled.put("actionPoints", 4);
        var ordered = new LinkedHashMap<String, Integer>();
        ordered.put("actionPoints", 4);
        ordered.put("strikeCost", 3);
        var first = CombatProfile.resolve("pack", ROLES, "health", null, shuffled);
        var second = CombatProfile.resolve("pack", new java.util.TreeMap<>(ROLES), "health", null, ordered);
        assertEquals(first.canonical(), second.canonical());
        assertEquals(first.hash(), second.hash());
        assertEquals(64, first.hash().length());
        assertNotEquals(first.hash(), CombatProfile.resolve("pack", ROLES, "health", null, Map.of("actionPoints", 4))
                .hash());
        assertNotEquals(first.hash(), CombatProfile.resolve("other", ROLES, "health", null, shuffled).hash());
        assertTrue(first.canonical().startsWith("{\"focus\":null,\"health\":\"health\",\"kernel\":"), first.canonical());
    }

    /** Canonical JSON sorts keys, escapes text and refuses values it cannot represent exactly. */
    @Test
    void canonicalJsonIsDeterministic() {
        var map = new LinkedHashMap<String, Object>();
        map.put("b", List.of(1, 2L, true));
        map.put("a", "quote \" slash \\ line\n");
        assertEquals("{\"a\":\"quote \\\" slash \\\\ line\\u000a\",\"b\":[1,2,true]}", CanonicalJson.write(map));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Map.of("x", 1.5)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Map.of(1, "x")));
    }
}
