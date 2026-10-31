package org.rpgengine.inference.slots;

import java.util.Optional;
import org.rpgengine.inference.InferenceSlot;

/**
 * The four owner-assigned model slots of ADR 0021 decision 2, named as the settings API and the
 * stored rows name them. The three text slots map onto {@link InferenceSlot}; {@link #IMAGE} has
 * no text task counterpart and is served by an image backend only.
 */
public enum ModelSlot {
    /** Cheap, short text tasks. */
    LIGHT("text.light", InferenceSlot.LIGHT),
    /** Ordinary narration and interpretation. */
    STANDARD("text.standard", InferenceSlot.STANDARD),
    /** High-impact or long text tasks. */
    DELIBERATE("text.deliberate", InferenceSlot.DELIBERATE),
    /** Scene, portrait and item artwork. */
    IMAGE("image", null);

    private final String wire;
    private final InferenceSlot text;

    ModelSlot(String wire, InferenceSlot text) {
        this.wire = wire;
        this.text = text;
    }

    /** @return the fixed identifier used on the wire and in storage, such as {@code text.standard} */
    public String wire() {
        return wire;
    }

    /** @return the text task slot this slot serves, or empty for the image slot */
    public Optional<InferenceSlot> text() {
        return Optional.ofNullable(text);
    }

    /**
     * @param text a wire identifier, or null
     * @return the slot it names, or empty for null and for anything outside the closed set
     */
    public static Optional<ModelSlot> parse(String text) {
        for (ModelSlot slot : values()) {
            if (slot.wire.equals(text)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    /**
     * @param slot a text task slot
     * @return the settings slot that serves it
     */
    public static ModelSlot of(InferenceSlot slot) {
        return switch (slot) {
            case LIGHT -> LIGHT;
            case STANDARD -> STANDARD;
            case DELIBERATE -> DELIBERATE;
        };
    }
}
