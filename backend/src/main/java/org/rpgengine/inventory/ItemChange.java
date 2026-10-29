package org.rpgengine.inventory;

import java.util.Objects;
import java.util.UUID;

import org.rpgengine.persistence.CanonicalValues;

/**
 * One resolved mutation of an {@code item_instance} row. A change carries the state the resolver
 * read (owner, quantity, storage version), so the commit can refuse it when the row moved on,
 * and the definition, so the writer can prove that no unit appears or vanishes unexplained
 * ({@link ItemPlan} conservation). Changes are intentions with exact preconditions, never generic
 * field writes: only ownership and quantity can change, the instance identity and its definition
 * never do.
 */
public sealed interface ItemChange permits ItemChange.Update, ItemChange.Delete, ItemChange.Insert {
    /** @return the instance this change touches */
    UUID instanceId();

    /** @return canonical definition ID of the touched instance */
    String definitionId();

    /**
     * Change the owner and/or quantity of an existing instance. A whole-instance transfer keeps the
     * identity (and with it the artwork) of a unique item; a partial transfer or a merge only lowers
     * or raises the quantity.
     *
     * @param instanceId the instance
     * @param definitionId canonical definition ID
     * @param expectedRowVersion storage version that must still be current
     * @param expectedOwner owner that must still hold it
     * @param expectedQuantity quantity that must still be stored
     * @param newOwner owner after the change
     * @param newQuantity quantity after the change, at least one (use {@link Delete} to remove)
     */
    record Update(UUID instanceId, String definitionId, long expectedRowVersion, UUID expectedOwner,
            int expectedQuantity, UUID newOwner, int newQuantity) implements ItemChange {
        /** Validate the preconditions and the new state; stack bounds are enforced by the database. */
        public Update {
            Objects.requireNonNull(instanceId, "instanceId");
            CanonicalValues.requireText(definitionId, "definitionId", 256);
            CanonicalValues.requireNonNegative(expectedRowVersion, "expectedRowVersion");
            Objects.requireNonNull(expectedOwner, "expectedOwner");
            Objects.requireNonNull(newOwner, "newOwner");
            CanonicalValues.requirePositive(expectedQuantity, "expectedQuantity");
            CanonicalValues.requirePositive(newQuantity, "newQuantity");
            if (expectedOwner.equals(newOwner) && expectedQuantity == newQuantity) {
                throw new IllegalArgumentException("an update must change the owner or the quantity");
            }
        }
    }

    /**
     * Remove an instance whose last unit was consumed or moved elsewhere. History remains in the
     * events and, for created units, the ledger.
     *
     * @param instanceId the instance
     * @param definitionId canonical definition ID
     * @param expectedRowVersion storage version that must still be current
     * @param expectedOwner owner that must still hold it
     * @param expectedQuantity quantity that must still be stored
     */
    record Delete(UUID instanceId, String definitionId, long expectedRowVersion, UUID expectedOwner,
            int expectedQuantity) implements ItemChange {
        /** Validate the preconditions. */
        public Delete {
            Objects.requireNonNull(instanceId, "instanceId");
            CanonicalValues.requireText(definitionId, "definitionId", 256);
            CanonicalValues.requireNonNegative(expectedRowVersion, "expectedRowVersion");
            Objects.requireNonNull(expectedOwner, "expectedOwner");
            CanonicalValues.requirePositive(expectedQuantity, "expectedQuantity");
        }
    }

    /**
     * Add a new instance. The identity is chosen by the resolver (deterministically from the
     * operation), so a retried resolution of the same operation proposes the same rows.
     *
     * @param instanceId new identity
     * @param ownerId holding actor
     * @param definitionId canonical definition ID
     * @param quantity units, at least one and at most {@code maxStack}
     * @param maxStack stack bound of the definition, frozen on the row
     */
    record Insert(UUID instanceId, UUID ownerId, String definitionId, int quantity, int maxStack)
            implements ItemChange {
        /** Validate the new state against the stack bound. */
        public Insert {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(ownerId, "ownerId");
            CanonicalValues.requireText(definitionId, "definitionId", 256);
            CanonicalValues.requirePositive(quantity, "quantity");
            if (maxStack < 1 || maxStack > 9999 || quantity > maxStack) {
                throw new IllegalArgumentException("quantity must fit the stack bound");
            }
        }
    }
}
