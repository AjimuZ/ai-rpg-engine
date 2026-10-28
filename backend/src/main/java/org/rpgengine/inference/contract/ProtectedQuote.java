package org.rpgengine.inference.contract;

import org.rpgengine.presentation.SupportedLocale;

/**
 * Verbatim text that the pack's quote policy protects (docs/29 section 4): an inscription,
 * password or riddle that must appear exactly as authored, in its source language, even inside
 * narration in another locale. A passage declares the quotes it uses and each declared quote
 * must occur unchanged in that passage's text. The locale-signal check ignores quoted text, so a
 * legitimate source-language quote is not mistaken for wrong-locale output.
 *
 * @param quoteId stable text key
 * @param text exact protected text, at most {@link #MAX_TEXT} characters
 * @param sourceLocale language of the protected text
 */
public record ProtectedQuote(String quoteId, String text, SupportedLocale sourceLocale) {
    /** Longest protected quote. */
    public static final int MAX_TEXT = 400;

    /** @throws IllegalArgumentException for a malformed key or unbounded text */
    public ProtectedQuote {
        ContextValues.reference(quoteId, "quote id");
        ContextValues.text(text, "quote text", MAX_TEXT);
        java.util.Objects.requireNonNull(sourceLocale, "sourceLocale");
    }
}
