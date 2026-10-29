package org.rpgengine.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure equipment constraints: item compatibility with a slot, slot capacity and hand occupancy
 * are checked together, never one at a time (docs/05 section 4, docs/10 section 3).
 *
 * <p>The rules read only explicit definitions: the slot an item names, its {@code hands} and
 * {@code tags}. Appearance, rarity, price and description never take part, so artwork cannot make
 * an item equippable or free a hand. Every method is deterministic and returns the complete
 * ordered list of violations, so a rejection can explain all of its causes at once.
 */
public final class EquipmentRules {
    private EquipmentRules() {
    }

    /** Why an item cannot be worn; the order of declaration is the order violations are reported. */
    public enum Violation {
        /** The item definition names no equipment slot. */
        NOT_EQUIPPABLE,
        /** The item names a slot the actor's vocabulary does not define. */
        UNKNOWN_SLOT,
        /** The item is already worn. */
        ALREADY_WORN,
        /** The slot only accepts tags the item does not carry. */
        INCOMPATIBLE_TAGS,
        /** The slot already holds as many items as its capacity. */
        SLOT_FULL,
        /** Wearing the item would need more hands than the actor has. */
        HANDS_EXCEEDED
    }

    /**
     * Equipment slot of an actor's vocabulary.
     *
     * @param id unqualified slot key
     * @param capacity items that fit at once, at least one
     * @param accepts item tags the slot accepts; empty accepts any item that names the slot
     */
    public record Slot(String id, int capacity, Set<String> accepts) {
        /** Validate and freeze. */
        public Slot {
            Objects.requireNonNull(id, "id");
            if (capacity < 1) {
                throw new IllegalArgumentException("slot capacity must be at least 1");
            }
            accepts = Set.copyOf(accepts);
        }
    }

    /**
     * Equipment facts of an item definition.
     *
     * @param slot slot key the item is worn in, or null for an item that cannot be equipped
     * @param hands hands occupied while worn, not negative
     * @param tags compatibility tags
     */
    public record Gear(String slot, int hands, Set<String> tags) {
        /** Validate and freeze. */
        public Gear {
            if (hands < 0) {
                throw new IllegalArgumentException("hands must not be negative");
            }
            tags = Set.copyOf(tags);
        }
    }

    /**
     * An item instance currently worn.
     *
     * @param itemKey identity of the item instance, compared for equality only
     * @param slot slot key it occupies
     * @param hands hands it occupies
     */
    public record Worn(String itemKey, String slot, int hands) {
    }

    /**
     * Check whether one more item can be worn.
     *
     * @param handCapacity hands the actor has
     * @param slots the actor's slot vocabulary by key
     * @param worn everything currently worn
     * @param itemKey identity of the candidate instance
     * @param gear the candidate's equipment facts
     * @return all violations in declaration order; empty when the item can be worn. An item that
     *         is already worn, or has no usable slot, reports only that single violation
     */
    public static List<Violation> check(int handCapacity, Map<String, Slot> slots, List<Worn> worn, String itemKey,
            Gear gear) {
        var violations = new ArrayList<Violation>();
        if (gear.slot() == null) {
            violations.add(Violation.NOT_EQUIPPABLE);
            return violations;
        }
        Slot slot = slots.get(gear.slot());
        if (slot == null) {
            violations.add(Violation.UNKNOWN_SLOT);
            return violations;
        }
        if (worn.stream().anyMatch(entry -> entry.itemKey().equals(itemKey))) {
            // Its own assignment would be counted against slot and hands, so nothing else is meaningful.
            violations.add(Violation.ALREADY_WORN);
            return violations;
        }
        if (!slot.accepts().isEmpty() && gear.tags().stream().noneMatch(slot.accepts()::contains)) {
            violations.add(Violation.INCOMPATIBLE_TAGS);
        }
        if (worn.stream().filter(entry -> entry.slot().equals(slot.id())).count() >= slot.capacity()) {
            violations.add(Violation.SLOT_FULL);
        }
        // Long arithmetic: occupancy is bounded by the database, but this must never wrap.
        long hands = gear.hands();
        for (Worn entry : worn) {
            hands += entry.hands();
        }
        if (hands > handCapacity) {
            violations.add(Violation.HANDS_EXCEEDED);
        }
        return violations;
    }

    /**
     * Check that a complete set of worn items is consistent, for example a starting loadout.
     *
     * @param handCapacity hands the actor has
     * @param slots the actor's slot vocabulary by key
     * @param worn the complete worn set
     * @param gear equipment facts of every worn item key
     * @return the first violating item key paired with its violations, or empty when consistent;
     *         items are examined in the given order, each against those before it
     */
    public static Map<String, List<Violation>> audit(int handCapacity, Map<String, Slot> slots,
            List<Worn> worn, Map<String, Gear> gear) {
        for (int index = 0; index < worn.size(); index++) {
            Worn entry = worn.get(index);
            List<Violation> violations = check(handCapacity, slots, worn.subList(0, index), entry.itemKey(),
                    gear.get(entry.itemKey()));
            if (!violations.isEmpty()) {
                return Map.of(entry.itemKey(), violations);
            }
        }
        return Map.of();
    }
}
