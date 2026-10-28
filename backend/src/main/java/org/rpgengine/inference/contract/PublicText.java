package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks every human-facing output string against the task's public knowledge scope, glossary
 * and selected locale. Shared by the intent clarification and the narration contract.
 */
final class PublicText {
    /** Static helpers only. */
    private PublicText() {
    }

    /**
     * One output string and its pointer.
     *
     * @param path RFC 6901 pointer of the string
     * @param text accepted output text
     */
    record Located(String path, String text) {
    }

    /**
     * Record {@code scope-leak} for a guard marker, {@code glossary-mismatch} for a wrong term or
     * altered proper name, and {@code locale-mismatch} when any string, or all strings together, are
     * dominated by the other supported language; a weak locale signal adds the warning {@code locale-uncertain}.
     *
     * @param reader violation sink
     * @param context task context with guard, glossary and binding
     * @param texts human-facing strings
     * @param exclusions text legitimately in another language (protected quotes), removed before
     *        the locale signal
     * @param warnings warning sink
     */
    static void check(StrictJson reader, ContractContext context, List<Located> texts, List<String> exclusions,
            List<String> warnings) {
        for (Located located : texts) {
            if (context.guard().leaks(located.text())) {
                reader.semantic("scope-leak", located.path());
            }
            if (TextChecks.glossaryMismatch(located.text(), context.glossary())) {
                reader.semantic("glossary-mismatch", located.path());
            }
        }
        var removed = new ArrayList<>(exclusions);
        // Glossary renderings are correct in the selected locale by definition, but a proper name
        // ("The Old Mill") may contain other-language function words.
        context.glossary().forEach(term -> removed.add(term.rendering()));
        removed.sort((a, b) -> Integer.compare(b.length(), a.length()));
        // Each string is judged alone, so one wrong-language passage cannot hide among correct ones,
        // and all strings together, so many short wrong-language labels are still caught.
        var signals = new ArrayList<TextChecks.LocaleSignal>();
        for (Located located : texts) {
            signals.add(TextChecks.localeSignal(List.of(located.text()), context.binding().outputLocale(), removed));
        }
        signals.add(TextChecks.localeSignal(texts.stream().map(Located::text).toList(),
                context.binding().outputLocale(), removed));
        if (signals.contains(TextChecks.LocaleSignal.MISMATCH)) {
            reader.semantic("locale-mismatch", "/body");
        } else if (signals.contains(TextChecks.LocaleSignal.UNCERTAIN)) {
            warnings.add("locale-uncertain");
        }
    }
}
