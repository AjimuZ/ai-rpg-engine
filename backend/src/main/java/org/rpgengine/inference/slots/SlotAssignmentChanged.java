package org.rpgengine.inference.slots;

import java.util.Objects;

/**
 * Published after a saved assignment changed what a slot runs: its backend, model, parameters, class
 * declaration or fallbacks. Saving an identical assignment publishes nothing.
 *
 * @param slot the slot whose assignment changed
 */
public record SlotAssignmentChanged(ModelSlot slot) {
    /** Require the slot. */
    public SlotAssignmentChanged {
        Objects.requireNonNull(slot, "slot");
    }
}
