package org.rpgengine.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * Pure rules of the inventory working copy and of unit conservation (P10.2): consuming, merging,
 * reassigning, splitting and creating stacks, the minimal change list, determinism and the
 * conservation proof that every {@link ItemPlan} carries.
 */
class InventoryModelTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID WARDEN = new UUID(0, 2);

    /** Construct a stateless fixture. */
    InventoryModelTest() {
    }

    /** Deterministic identity source: 100, 101, ... */
    private static Supplier<UUID> ids() {
        var next = new AtomicInteger(100);
        return () -> new UUID(0, next.getAndIncrement());
    }

    private static InventoryModel.Stack stack(int id, UUID owner, String definition, int quantity, int maxStack) {
        return new InventoryModel.Stack(new UUID(0, id), owner, definition, quantity, maxStack, 3);
    }

    /** Consuming part of a stack lowers it; consuming all removes the row; history is not the model's concern. */
    @Test
    void consumesPartAndAll() {
        var model = new InventoryModel(List.of(stack(10, PLAYER, "potion", 3, 5), stack(11, PLAYER, "key", 1, 1)), ids());
        model.consume(new UUID(0, 10), 1);
        model.consume(new UUID(0, 11), 1);
        assertEquals(List.of(
                new ItemChange.Update(new UUID(0, 10), "potion", 3, PLAYER, 3, PLAYER, 2),
                new ItemChange.Delete(new UUID(0, 11), "key", 3, PLAYER, 1)), model.changes());
        assertTrue(model.find(new UUID(0, 11)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> model.consume(new UUID(0, 11), 1));
        assertThrows(IllegalArgumentException.class, () -> model.consume(new UUID(0, 10), 3));
        assertThrows(IllegalArgumentException.class, () -> model.consume(new UUID(0, 10), 0));
    }

    /** A unique item keeps its identity when it changes hands: the stack itself is reassigned. */
    @Test
    void reassignsUniqueItemWithoutNewIdentity() {
        var model = new InventoryModel(List.of(stack(10, PLAYER, "sword", 1, 1)), ids());
        model.transfer(new UUID(0, 10), 1, WARDEN);
        assertEquals(List.of(new ItemChange.Update(new UUID(0, 10), "sword", 3, PLAYER, 1, WARDEN, 1)), model.changes());
        assertThrows(IllegalArgumentException.class, () -> model.transfer(new UUID(0, 10), 1, WARDEN));
    }

    /** Moving part of a stack to an actor without that item splits it into a new stack with a fresh identity. */
    @Test
    void splitsPartialTransferIntoNewStack() {
        var model = new InventoryModel(List.of(stack(10, PLAYER, "potion", 5, 5)), ids());
        model.transfer(new UUID(0, 10), 2, WARDEN);
        assertEquals(List.of(
                new ItemChange.Update(new UUID(0, 10), "potion", 3, PLAYER, 5, PLAYER, 3),
                new ItemChange.Insert(new UUID(0, 100), WARDEN, "potion", 2, 5)), model.changes());
    }

    /** Units fill the receiver's open stacks in identity order before anything new opens or moves. */
    @Test
    void mergesIntoOpenStacksInIdentityOrder() {
        var model = new InventoryModel(List.of(stack(10, PLAYER, "potion", 5, 5), stack(20, WARDEN, "potion", 4, 5),
                stack(21, WARDEN, "potion", 3, 5), stack(22, WARDEN, "potion", 5, 5)), ids());
        model.transfer(new UUID(0, 10), 5, WARDEN);
        // 1 fills stack 20, 2 fill stack 21, the remaining 2 move with the reduced source stack itself.
        assertEquals(List.of(
                new ItemChange.Update(new UUID(0, 10), "potion", 3, PLAYER, 5, WARDEN, 2),
                new ItemChange.Update(new UUID(0, 20), "potion", 3, WARDEN, 4, WARDEN, 5),
                new ItemChange.Update(new UUID(0, 21), "potion", 3, WARDEN, 3, WARDEN, 5)), model.changes());
        new ItemPlan(model.changes(), List.of(), List.of(), List.of());
    }

    /** A transfer that fits entirely into the receiver's stacks removes the source row. */
    @Test
    void deletesSourceWhenEverythingMerges() {
        var model = new InventoryModel(List.of(stack(10, PLAYER, "potion", 2, 5), stack(20, WARDEN, "potion", 1, 5)), ids());
        model.transfer(new UUID(0, 10), 2, WARDEN);
        assertEquals(List.of(new ItemChange.Update(new UUID(0, 20), "potion", 3, WARDEN, 1, WARDEN, 3),
                new ItemChange.Delete(new UUID(0, 10), "potion", 3, PLAYER, 2)).stream()
                .sorted(java.util.Comparator.comparing(change -> change.instanceId().toString())).toList(),
                model.changes());
    }

    /** Created units merge first and then open as many full stacks as needed, never above the bound. */
    @Test
    void depositFillsThenOpensStacks() {
        var model = new InventoryModel(List.of(stack(10, PLAYER, "oil", 7, 10)), ids());
        model.deposit(PLAYER, "oil", 10, 23);
        assertEquals(List.of(
                new ItemChange.Update(new UUID(0, 10), "oil", 3, PLAYER, 7, PLAYER, 10),
                new ItemChange.Insert(new UUID(0, 100), PLAYER, "oil", 10, 10),
                new ItemChange.Insert(new UUID(0, 101), PLAYER, "oil", 10, 10)), model.changes());
        assertEquals(23, new ItemPlan(model.changes(), List.of(new ItemPlan.Creation(PLAYER, "oil", 23,
                ItemPlan.SourceKind.EFFECT, "op#effects/0", 0)), List.of(), List.of()).netUnits().get("oil"));
        assertThrows(IllegalArgumentException.class, () -> model.deposit(PLAYER, "oil", 10, 0));
    }

    /** A stack that ends where it started produces no change at all, so no row version moves needlessly. */
    @Test
    void omitsUnchangedStacks() {
        var model = new InventoryModel(List.of(stack(10, PLAYER, "potion", 5, 5), stack(11, PLAYER, "ring", 1, 1)), ids());
        model.consume(new UUID(0, 10), 1);
        model.deposit(PLAYER, "potion", 5, 1);
        assertEquals(List.of(), model.changes());
    }

    /** The same inputs always propose the same rows and identities. */
    @Test
    void isDeterministic() {
        var first = scenario();
        var second = scenario();
        assertEquals(first, second);
        assertEquals(5, first.size());
    }

    private static List<ItemChange> scenario() {
        var model = new InventoryModel(List.of(stack(12, WARDEN, "coin", 99, 999), stack(10, PLAYER, "potion", 5, 5),
                stack(11, PLAYER, "sword", 1, 1)), ids());
        model.transfer(new UUID(0, 10), 3, WARDEN);
        model.transfer(new UUID(0, 11), 1, WARDEN);
        model.deposit(PLAYER, "coin", 999, 1500);
        return model.changes();
    }

    /** Operations refuse unknown stacks, excess units and giving a stack to its holder. */
    @Test
    void rejectsImpossibleOperations() {
        var model = new InventoryModel(List.of(stack(10, PLAYER, "potion", 2, 5)), ids());
        assertThrows(IllegalArgumentException.class, () -> model.transfer(new UUID(0, 99), 1, WARDEN));
        assertThrows(IllegalArgumentException.class, () -> model.transfer(new UUID(0, 10), 3, WARDEN));
        assertThrows(IllegalArgumentException.class, () -> model.transfer(new UUID(0, 10), 1, PLAYER));
        assertThrows(IllegalArgumentException.class, () -> new InventoryModel(List.of(stack(10, PLAYER, "a", 1, 1),
                stack(10, WARDEN, "a", 1, 1)), ids()));
        assertThrows(IllegalStateException.class, () -> {
            var repeating = new InventoryModel(List.of(), () -> new UUID(0, 7));
            repeating.deposit(PLAYER, "oil", 1, 2);
        });
    }

    /** Conservation: moves balance, creations and consumptions must be declared, and an instance changes once. */
    @Test
    void planProvesConservation() {
        var give = new ItemChange.Update(new UUID(0, 10), "potion", 3, PLAYER, 5, PLAYER, 3);
        var receive = new ItemChange.Insert(new UUID(0, 100), WARDEN, "potion", 2, 5);
        new ItemPlan(List.of(give, receive), List.of(), List.of(), List.of());
        // A lost unit, a duplicated unit and an undeclared creation are all refused.
        assertThrows(IllegalArgumentException.class, () -> new ItemPlan(List.of(give), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ItemPlan(List.of(give,
                new ItemChange.Insert(new UUID(0, 100), WARDEN, "potion", 3, 5)), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ItemPlan(List.of(receive), List.of(), List.of(), List.of()));
        // Declared consumption explains a vanished unit; a declared creation explains a new one.
        new ItemPlan(List.of(give), List.of(), List.of(new ItemPlan.Consumption("potion", 2)), List.of());
        new ItemPlan(List.of(receive), List.of(new ItemPlan.Creation(WARDEN, "potion", 2, ItemPlan.SourceKind.REWARD,
                "quest:rescue", 0)), List.of(), List.of());
        // One instance cannot be changed twice, and an update must change something.
        assertThrows(IllegalArgumentException.class, () -> new ItemPlan(List.of(give, give), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ItemChange.Update(new UUID(0, 10), "potion", 3, PLAYER, 5, PLAYER, 5));
        assertThrows(IllegalArgumentException.class, () -> new ItemChange.Insert(new UUID(0, 9), PLAYER, "potion", 6, 5));
        var changes = new ArrayList<ItemChange>();
        for (int index = 0; index <= ItemPlan.MAX_CHANGES; index++) {
            changes.add(new ItemChange.Insert(new UUID(1, index), PLAYER, "a", 1, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> new ItemPlan(changes, List.of(), List.of(), List.of()));
    }
}
