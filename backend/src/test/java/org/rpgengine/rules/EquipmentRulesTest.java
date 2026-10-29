package org.rpgengine.rules;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Equipment constraints: slot, tag compatibility, capacity and hands are decided together. */
class EquipmentRulesTest {
    private static final Map<String, EquipmentRules.Slot> SLOTS = Map.of(
            "main-hand", new EquipmentRules.Slot("main-hand", 1, Set.of("weapon")),
            "off-hand", new EquipmentRules.Slot("off-hand", 1, Set.of("shield", "light")),
            "trinket", new EquipmentRules.Slot("trinket", 2, Set.of()));

    /** Construct a stateless fixture. */
    EquipmentRulesTest() {
    }

    /** A compatible item with room in the slot and enough free hands is allowed. */
    @Test
    void allowsCompatibleItemWithRoom() {
        var worn = List.of(new EquipmentRules.Worn("sword", "main-hand", 1));
        assertTrue(EquipmentRules.check(2, SLOTS, worn, "shield", gear("off-hand", 1, "shield")).isEmpty());
        assertTrue(EquipmentRules.check(2, SLOTS, List.of(), "ring", gear("trinket", 0)).isEmpty());
    }

    /** Hands are shared across slots: a two-handed weapon excludes a shield even in another slot. */
    @Test
    void checksHandsAcrossSlots() {
        var worn = List.of(new EquipmentRules.Worn("greatsword", "main-hand", 2));
        assertEquals(List.of(EquipmentRules.Violation.HANDS_EXCEEDED),
                EquipmentRules.check(2, SLOTS, worn, "shield", gear("off-hand", 1, "shield")));
        // A pack with three hands accepts the same loadout, so hands come from the vocabulary.
        assertTrue(EquipmentRules.check(3, SLOTS, worn, "shield", gear("off-hand", 1, "shield")).isEmpty());
    }

    /** Capacity applies per slot and all violated constraints are reported together in rule order. */
    @Test
    void reportsEveryViolationInOrder() {
        var worn = List.of(new EquipmentRules.Worn("sword", "main-hand", 2));
        assertEquals(List.of(EquipmentRules.Violation.INCOMPATIBLE_TAGS, EquipmentRules.Violation.SLOT_FULL,
                EquipmentRules.Violation.HANDS_EXCEEDED),
                EquipmentRules.check(2, SLOTS, worn, "club", gear("main-hand", 1, "tool")));
        // An item that is already worn is reported alone: it must not count against its own slot.
        assertEquals(List.of(EquipmentRules.Violation.ALREADY_WORN),
                EquipmentRules.check(1, SLOTS, worn, "sword", gear("main-hand", 2, "weapon")));
        // Capacity two allows a second trinket and rejects a third.
        var rings = List.of(new EquipmentRules.Worn("a", "trinket", 0), new EquipmentRules.Worn("b", "trinket", 0));
        assertEquals(List.of(EquipmentRules.Violation.SLOT_FULL),
                EquipmentRules.check(2, SLOTS, rings, "c", gear("trinket", 0)));
        assertTrue(EquipmentRules.check(2, SLOTS, rings.subList(0, 1), "b2", gear("trinket", 0)).isEmpty());
    }

    /** An item without a slot, or with a slot the vocabulary lacks, is never wearable. */
    @Test
    void rejectsItemsWithoutUsableSlot() {
        assertEquals(List.of(EquipmentRules.Violation.NOT_EQUIPPABLE),
                EquipmentRules.check(2, SLOTS, List.of(), "rope", new EquipmentRules.Gear(null, 0, Set.of())));
        assertEquals(List.of(EquipmentRules.Violation.UNKNOWN_SLOT),
                EquipmentRules.check(2, SLOTS, List.of(), "visor", gear("head", 0)));
    }

    /** A complete loadout is audited in order and the first conflicting item is named. */
    @Test
    void auditsACompleteLoadout() {
        var gear = Map.of("sword", gear("main-hand", 1, "weapon"), "greatsword", gear("main-hand", 2, "weapon"),
                "shield", gear("off-hand", 1, "shield"));
        var good = List.of(new EquipmentRules.Worn("sword", "main-hand", 1), new EquipmentRules.Worn("shield", "off-hand", 1));
        assertTrue(EquipmentRules.audit(2, SLOTS, good, gear).isEmpty());
        var bad = List.of(new EquipmentRules.Worn("sword", "main-hand", 1), new EquipmentRules.Worn("shield", "off-hand", 1),
                new EquipmentRules.Worn("greatsword", "main-hand", 2));
        assertEquals(Map.of("greatsword", List.of(EquipmentRules.Violation.SLOT_FULL, EquipmentRules.Violation.HANDS_EXCEEDED)),
                EquipmentRules.audit(2, SLOTS, bad, gear));
    }

    /** Invalid construction is refused. */
    @Test
    void rejectsInvalidDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> new EquipmentRules.Slot("s", 0, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new EquipmentRules.Gear("s", -1, Set.of()));
    }

    private static EquipmentRules.Gear gear(String slot, int hands, String... tags) {
        return new EquipmentRules.Gear(slot, hands, Set.of(tags));
    }
}
