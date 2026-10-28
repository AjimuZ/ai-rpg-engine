package org.rpgengine.inference;

/**
 * Logical text inference slots of ADR 0021 ({@code text.light}, {@code text.standard},
 * {@code text.deliberate}), aligned with the logical profiles of docs/06 section 9. A task names
 * a slot, never a model: the owner's private slot assignment maps each slot to the model and
 * effort of the selected backend, and the effective values are sent explicitly on every turn.
 * The owner-selected {@code fixedQuality} profile and the {@code image} slot are not text task
 * slots of this port; P08.4 resolves {@code fixedQuality} onto one of these three.
 */
public enum InferenceSlot {
    /** Cheap, short tasks such as quick replies and simple interpretation. */
    LIGHT("light"),
    /** Ordinary narration and interpretation; the default weight class. */
    STANDARD("standard"),
    /** High-impact negotiation, complex content proposals and tactical planning. */
    DELIBERATE("deliberate");

    private final String key;

    InferenceSlot(String key) {
        this.key = key;
    }

    /** @return the lowercase setting key ({@code light}, {@code standard}, {@code deliberate}) */
    public String key() {
        return key;
    }
}
