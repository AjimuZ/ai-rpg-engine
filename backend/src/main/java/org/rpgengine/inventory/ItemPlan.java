package org.rpgengine.inventory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import org.rpgengine.persistence.CanonicalValues;

/**
 * Complete, bounded item effects of one canonical commit: instance mutations, the units they
 * create or consume and the equipment assignments they change.
 *
 * <p><b>Conservation.</b> For every definition, the units added by the changes minus the units
 * removed must equal the units declared as created minus those declared as consumed. A transfer or
 * trade therefore declares nothing and must balance by itself; a created unit must appear in
 * {@link Creation} with its source; a used-up unit must appear in {@link Consumption}. The
 * constructor proves this purely, so a resolver defect is rejected before any lock is taken, and
 * {@link InventoryWriter} re-proves it against the stored rows it actually changes.
 *
 * @param changes ordered instance mutations, each instance at most once
 * @param creations units that enter the campaign, each with its source
 * @param consumptions units that leave it because an item was used up
 * @param equipment worn-state changes applied after the instance mutations
 */
public record ItemPlan(List<ItemChange> changes, List<Creation> creations, List<Consumption> consumptions,
        List<EquipmentChange> equipment) {
    /** Plan without item effects. */
    public static final ItemPlan NONE = new ItemPlan(List.of(), List.of(), List.of(), List.of());
    /** Upper bound of instance mutations (rows touched) in one commit. */
    public static final int MAX_CHANGES = 128;

    /** Copy and bound every list, reject a duplicated instance and prove unit conservation. */
    public ItemPlan {
        changes = bounded(changes, MAX_CHANGES, "item changes");
        creations = bounded(creations, MAX_CHANGES, "item creations");
        consumptions = bounded(consumptions, 32, "item consumptions");
        equipment = bounded(equipment, 16, "equipment changes");
        var instances = new HashSet<UUID>();
        for (ItemChange change : changes) {
            if (!instances.add(change.instanceId())) {
                throw new IllegalArgumentException("an item instance is changed twice in one commit");
            }
        }
        var balance = new TreeMap<>(net(changes));
        creations.forEach(creation -> balance.merge(creation.definitionId(), -(long) creation.quantity(), Long::sum));
        consumptions.forEach(use -> balance.merge(use.definitionId(), (long) use.quantity(), Long::sum));
        // Net change minus declared creations plus declared consumptions must be zero everywhere.
        if (balance.values().stream().anyMatch(value -> value != 0)) {
            throw new IllegalArgumentException("item plan does not conserve units");
        }
    }

    /** @return whether the plan changes nothing */
    public boolean isEmpty() {
        return changes.isEmpty() && creations.isEmpty() && consumptions.isEmpty() && equipment.isEmpty();
    }

    /** Where created units come from. */
    public enum SourceKind {
        /** An effect node of an ability or item use; the key is {@code <operation>#<effect path>}. */
        EFFECT,
        /** A settled reward; the key equals the source key of a reward settlement in the same commit. */
        REWARD
    }

    /**
     * Units that enter the campaign. The ledger row cites the operation and the event at
     * {@code eventIndex} of the same plan, so a created item can always be explained.
     *
     * @param recipientId actor that receives the units
     * @param definitionId canonical definition ID
     * @param quantity units created for this recipient, 1..9999
     * @param kind source category
     * @param sourceKey stable source identity, unique per recipient and definition in the campaign
     * @param eventIndex 0-based index of the announcing event within the plan
     */
    public record Creation(UUID recipientId, String definitionId, int quantity, SourceKind kind, String sourceKey,
            int eventIndex) {
        /** Validate the ledger row before any transaction starts. */
        public Creation {
            Objects.requireNonNull(recipientId, "recipientId");
            CanonicalValues.requireText(definitionId, "definitionId", 256);
            if (quantity < 1 || quantity > 9999) {
                throw new IllegalArgumentException("created quantity must be 1..9999");
            }
            Objects.requireNonNull(kind, "kind");
            CanonicalValues.requireText(sourceKey, "sourceKey", 512);
            if (eventIndex < 0) {
                throw new IllegalArgumentException("eventIndex must not be negative");
            }
        }
    }

    /**
     * Units used up by an item whose use contract consumes it.
     *
     * @param definitionId canonical definition ID
     * @param quantity units consumed, at least one
     */
    public record Consumption(String definitionId, int quantity) {
        /** Validate. */
        public Consumption {
            CanonicalValues.requireText(definitionId, "definitionId", 256);
            CanonicalValues.requirePositive(quantity, "quantity");
        }
    }

    /** A change of what an actor wears; compatibility is checked by the character rules at commit. */
    public sealed interface EquipmentChange permits Equip, Unequip {
        /** @return the wearing actor */
        UUID actorId();

        /** @return the item instance */
        UUID itemInstanceId();
    }

    /**
     * Wear an instance the actor holds.
     *
     * @param actorId wearing actor
     * @param itemInstanceId instance to wear
     */
    public record Equip(UUID actorId, UUID itemInstanceId) implements EquipmentChange {
        /** Require identities. */
        public Equip {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        }
    }

    /**
     * Stop wearing an instance; it stays in the inventory.
     *
     * @param actorId wearing actor
     * @param itemInstanceId instance to take off
     */
    public record Unequip(UUID actorId, UUID itemInstanceId) implements EquipmentChange {
        /** Require identities. */
        public Unequip {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        }
    }

    /** @return net unit change per definition implied by the instance mutations alone */
    public Map<String, Long> netUnits() {
        return Map.copyOf(net(changes));
    }

    /** Sum the unit effect of each mutation per definition. */
    private static Map<String, Long> net(List<ItemChange> changes) {
        var net = new TreeMap<String, Long>();
        for (ItemChange change : changes) {
            switch (change) {
                case ItemChange.Insert insert -> net.merge(insert.definitionId(), (long) insert.quantity(), Long::sum);
                case ItemChange.Update update -> net.merge(update.definitionId(),
                        (long) update.newQuantity() - update.expectedQuantity(), Long::sum);
                case ItemChange.Delete delete -> net.merge(delete.definitionId(), -(long) delete.expectedQuantity(),
                        Long::sum);
            }
        }
        return net;
    }

    /** Return a defensive copy of at most limit non-null entries. */
    private static <T> List<T> bounded(List<T> values, int limit, String name) {
        if (values.size() > limit) {
            throw new IllegalArgumentException(name + " exceed " + limit);
        }
        return List.copyOf(values);
    }
}
