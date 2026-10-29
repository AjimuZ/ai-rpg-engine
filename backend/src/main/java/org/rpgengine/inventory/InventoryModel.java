package org.rpgengine.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pure, single-use working copy of the item stacks a resolver edits (P10.2). The resolver reads the
 * participants' stacks under the campaign lock, applies consume, transfer and create operations here
 * in order, and takes the minimal {@link ItemChange} list that turns the stored rows into the result.
 * Nothing in this class touches storage, so every rule below is deterministic and unit-testable.
 *
 * <p>Rules shared by every operation:
 * <ul>
 *   <li><b>Quantities</b> are integers; an operation never takes more than a stack holds and never
 *       builds a stack above the stack bound frozen on its row.</li>
 *   <li><b>Merging.</b> Units entering an actor first fill that actor's non-full stacks of the same
 *       definition in ascending identity order, then open new stacks. This keeps equivalent copies
 *       together and makes the outcome independent of hash ordering.</li>
 *   <li><b>Identity.</b> Moving everything that is left of a stack to another actor reassigns the
 *       stack itself, so a unique item (stack bound one) keeps its identity, and with it its artwork,
 *       from a shop through an inventory to an equipment slot.</li>
 *   <li><b>New identities</b> come from the supplied source, which the resolver derives from the
 *       operation so a retried resolution proposes the same rows.</li>
 * </ul>
 */
public final class InventoryModel {
    private final Map<UUID, Work> stacks = new LinkedHashMap<>();
    private final Supplier<UUID> identities;

    /**
     * Stack as read from storage.
     *
     * @param id instance identity
     * @param owner holding actor
     * @param definition canonical definition ID
     * @param quantity units held, at least one
     * @param maxStack stack bound frozen on the row
     * @param rowVersion storage version at read time
     */
    public record Stack(UUID id, UUID owner, String definition, int quantity, int maxStack, long rowVersion) {
        /** Validate the stored invariants the model relies on. */
        public Stack {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(definition, "definition");
            if (quantity < 1 || maxStack < 1 || quantity > maxStack) {
                throw new IllegalArgumentException("a stack holds 1..maxStack units");
            }
        }
    }

    /**
     * Start from the stored stacks.
     *
     * @param initial stacks of every participant; identities must be distinct
     * @param identities source of fresh instance identities, called once per new stack
     * @throws IllegalArgumentException for a duplicated identity
     */
    public InventoryModel(Collection<Stack> initial, Supplier<UUID> identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
        initial.stream().sorted(Comparator.comparing(stack -> stack.id().toString())).forEach(stack -> {
            if (stacks.put(stack.id(), new Work(stack)) != null) {
                throw new IllegalArgumentException("duplicate stack identity");
            }
        });
    }

    /** @return the stack's current working state, or empty when it never existed or was used up */
    public Optional<Stack> find(UUID id) {
        Work work = stacks.get(id);
        return work == null || work.quantity == 0 ? Optional.empty() : Optional.of(work.view());
    }

    /**
     * Units of a definition an actor holds across all its live stacks, worn or not.
     *
     * @param owner holding actor
     * @param definition canonical definition ID
     * @return the total, zero when the actor holds none
     */
    public long total(UUID owner, String definition) {
        long total = 0;
        for (Work work : stacks.values()) {
            if (work.owner.equals(owner) && work.definition.equals(definition)) {
                total += work.quantity;
            }
        }
        return total;
    }

    /**
     * Use up units of a stack.
     *
     * @param id stack to reduce
     * @param quantity units, 1..current quantity
     * @throws IllegalArgumentException if the stack is unknown or holds fewer units
     */
    public void consume(UUID id, int quantity) {
        Work work = require(id, quantity);
        work.quantity -= quantity;
    }

    /**
     * Move units to another actor, merging into its stacks of the same definition first and
     * reassigning the stack when everything left of it moves unmerged.
     *
     * @param id source stack
     * @param quantity units, 1..current quantity
     * @param to receiving actor, different from the holder
     * @throws IllegalArgumentException if the stack is unknown, holds fewer units or already belongs to {@code to}
     */
    public void transfer(UUID id, int quantity, UUID to) {
        Work source = require(id, quantity);
        if (source.owner.equals(to)) {
            throw new IllegalArgumentException("the recipient already holds the stack");
        }
        int remaining = fill(to, source.definition, source.maxStack, quantity, source.id);
        source.quantity -= quantity - remaining;
        if (remaining == 0) {
            return;
        }
        if (remaining == source.quantity) {
            source.owner = to;
        } else {
            source.quantity -= remaining;
            open(to, source.definition, source.maxStack, remaining);
        }
    }

    /**
     * Add newly created units for an actor, merging into its stacks of the same definition first.
     *
     * @param owner receiving actor
     * @param definition canonical definition ID
     * @param maxStack stack bound of the definition
     * @param quantity units to add, at least one
     */
    public void deposit(UUID owner, String definition, int maxStack, int quantity) {
        if (quantity < 1 || maxStack < 1) {
            throw new IllegalArgumentException("a deposit adds at least one unit to a positive stack bound");
        }
        int remaining = fill(owner, definition, maxStack, quantity, null);
        while (remaining > 0) {
            int portion = Math.min(remaining, maxStack);
            open(owner, definition, maxStack, portion);
            remaining -= portion;
        }
    }

    /**
     * @return the minimal ordered mutations from the stored rows to the working state: updates and
     *         deletes of stored stacks in identity order, then inserts in creation order
     */
    public List<ItemChange> changes() {
        var changes = new ArrayList<ItemChange>();
        var inserts = new ArrayList<ItemChange>();
        for (Work work : stacks.values()) {
            Stack origin = work.origin;
            if (origin == null) {
                if (work.quantity > 0) {
                    inserts.add(new ItemChange.Insert(work.id, work.owner, work.definition, work.quantity, work.maxStack));
                }
            } else if (work.quantity == 0) {
                changes.add(new ItemChange.Delete(origin.id(), origin.definition(), origin.rowVersion(), origin.owner(),
                        origin.quantity()));
            } else if (!work.owner.equals(origin.owner()) || work.quantity != origin.quantity()) {
                changes.add(new ItemChange.Update(origin.id(), origin.definition(), origin.rowVersion(), origin.owner(),
                        origin.quantity(), work.owner, work.quantity));
            }
        }
        changes.addAll(inserts);
        return List.copyOf(changes);
    }

    /** Fill the receiver's non-full stacks in identity order; returns the units that did not fit. */
    private int fill(UUID owner, String definition, int maxStack, int quantity, UUID except) {
        int remaining = quantity;
        var candidates = stacks.values().stream()
                .filter(work -> work.owner.equals(owner) && work.definition.equals(definition) && work.quantity > 0
                        && work.quantity < work.maxStack && !work.id.equals(except))
                .sorted(Comparator.comparing(work -> work.id.toString())).toList();
        for (Work target : candidates) {
            int room = target.maxStack - target.quantity;
            int added = Math.min(room, remaining);
            target.quantity += added;
            remaining -= added;
            if (remaining == 0) {
                break;
            }
        }
        return remaining;
    }

    /** Open a new stack of at most the bound; the caller has already split larger amounts. */
    private void open(UUID owner, String definition, int maxStack, int quantity) {
        var work = new Work(identities.get(), owner, definition, maxStack, quantity);
        if (stacks.put(work.id, work) != null) {
            throw new IllegalStateException("identity source repeated an identity");
        }
    }

    /** Look up a live stack and check the requested units. */
    private Work require(UUID id, int quantity) {
        Work work = stacks.get(id);
        if (work == null || work.quantity == 0) {
            throw new IllegalArgumentException("unknown stack");
        }
        if (quantity < 1 || quantity > work.quantity) {
            throw new IllegalArgumentException("a stack holds fewer units than requested");
        }
        return work;
    }

    /** Mutable working state of one stack; {@code origin} is null for stacks opened by this model. */
    private static final class Work {
        final UUID id;
        final String definition;
        final int maxStack;
        final Stack origin;
        UUID owner;
        int quantity;

        /** Copy a stored stack. */
        Work(Stack stack) {
            id = stack.id();
            definition = stack.definition();
            maxStack = stack.maxStack();
            origin = stack;
            owner = stack.owner();
            quantity = stack.quantity();
        }

        /** Start a new stack. */
        Work(UUID id, UUID owner, String definition, int maxStack, int quantity) {
            this.id = id;
            this.definition = definition;
            this.maxStack = maxStack;
            this.origin = null;
            this.owner = owner;
            this.quantity = quantity;
        }

        /** Current state as a stack value. */
        Stack view() {
            return new Stack(id, owner, definition, quantity, maxStack, origin == null ? 0 : origin.rowVersion());
        }
    }
}
