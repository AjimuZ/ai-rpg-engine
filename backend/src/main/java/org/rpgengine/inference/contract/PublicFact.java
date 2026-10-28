package org.rpgengine.inference.contract;

/**
 * One audience-authorized fact or resolved event the model may rely on and cite. Only facts the
 * selected audience is permitted to know may be built into a context; the contract never filters
 * visibility itself (docs/06 section 7).
 *
 * @param id stable reference the output cites in {@code sourceFactIds} or passage references
 * @param text localized public statement of the fact, at most {@link #MAX_TEXT} characters
 */
public record PublicFact(String id, String text) {
    /** Longest fact text rendered into one instruction. */
    public static final int MAX_TEXT = 1000;

    /** @throws IllegalArgumentException for a malformed reference or unbounded text */
    public PublicFact {
        ContextValues.reference(id, "fact id");
        ContextValues.text(text, "fact text", MAX_TEXT);
    }
}
