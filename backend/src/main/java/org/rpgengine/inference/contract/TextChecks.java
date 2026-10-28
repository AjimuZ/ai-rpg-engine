package org.rpgengine.inference.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.rpgengine.presentation.SupportedLocale;

/**
 * Deterministic text checks shared by the contracts: Unicode well-formedness, markup, numeric
 * claims, glossary terminology, protected quotes and a coarse locale signal.
 *
 * <p>These checks are mechanical by design. They catch verbatim mistakes (a leaked marker, a
 * number absent from the receipt, an English stat name in Italian narration) but cannot judge
 * meaning; docs/21 section 8 keeps natural-language semantic review an evaluation problem.
 */
final class TextChecks {
    /** Markup or code-fence starts: output text is plain prose, never HTML, script or Markdown code. */
    private static final Pattern EXECUTABLE = Pattern.compile("<\\s*[A-Za-z/!?]|```|\\{\\{|\\$\\{|javascript:",
            Pattern.CASE_INSENSITIVE);
    /** Digit runs, including grouped or decimal forms such as {@code 1,200} or {@code 3.5}. */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern WORD = Pattern.compile("[\\p{L}']+");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    /**
     * Function words that are frequent in one supported language and rare in the other. Shared
     * or ambiguous words ("a", "in", "no", "come", "all") are deliberately absent.
     */
    private static final Set<String> ENGLISH = Set.of("the", "and", "of", "with", "you", "your", "is", "are", "was",
            "were", "this", "that", "to", "from", "into", "his", "her", "their", "it", "has", "have", "not", "what",
            "where", "there", "they", "be", "will", "would", "an", "at", "on", "by");
    private static final Set<String> ITALIAN = Set.of("il", "lo", "la", "gli", "le", "di", "del", "della", "dei",
            "che", "con", "per", "sono", "è", "non", "una", "uno", "nel", "nella", "tuo", "tua", "suo", "sua", "ha",
            "hai", "e", "si", "ti", "ci", "questo", "questa", "dove", "cosa", "sei", "alla", "al", "un", "l", "dell", "nell");

    /** Words below which the locale signal only warns: short or name-heavy text is unreliable. */
    static final int LOCALE_MIN_WORDS = 12;
    /** Other-language function words required, together with dominance, for a locale violation. */
    static final int LOCALE_MIN_FOREIGN = 4;

    /** Static helpers only. */
    private TextChecks() {
    }

    /**
     * @param text any text
     * @return true when every surrogate is paired and no control character other than a line
     *         feed occurs
     */
    static boolean wellFormed(String text) {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(ch) || (Character.isISOControl(ch) && ch != '\n')) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param text any text
     * @return text lowercased with the root locale and with whitespace runs collapsed to one space,
     *         used for case- and spacing-insensitive containment
     */
    static String fold(String text) {
        return SPACE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** @return true when the text contains markup, a code fence or a template/script expression */
    static boolean executable(String text) {
        return EXECUTABLE.matcher(text).find();
    }

    /**
     * @param text any text
     * @return every digit run in order, separators removed ({@code 1,200} becomes {@code 1200})
     */
    static List<String> numbers(String text) {
        var values = new ArrayList<String>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            values.add(normalizeNumber(matcher.group()));
        }
        return values;
    }

    /** @return a digit run without grouping or decimal separators and without leading zeros */
    static String normalizeNumber(String digits) {
        return digits.replace(",", "").replace(".", "").replaceFirst("^0+(?=\\d)", "");
    }

    /**
     * @param text output text
     * @param phrase glossary rendering or form
     * @return matcher positions of the phrase as a whole word, ignoring case
     */
    static Matcher wholeWord(String text, String phrase) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
    }

    /**
     * Check terminology in one output string.
     *
     * @param text output text
     * @param glossary selected-locale glossary
     * @return true when a proper name occurs with altered capitalization or a term occurs in an
     *         avoided (wrong-locale) form
     */
    static boolean glossaryMismatch(String text, List<GlossaryTerm> glossary) {
        for (GlossaryTerm term : glossary) {
            if (term.kind() == GlossaryTerm.Kind.PROPER_NAME) {
                Matcher matcher = wholeWord(text, term.rendering());
                while (matcher.find()) {
                    if (!matcher.group().equals(term.rendering())) {
                        return true;
                    }
                }
            } else {
                for (String form : term.avoidForms()) {
                    if (wholeWord(text, form).find()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Result of the coarse locale signal. */
    enum LocaleSignal {
        /** No evidence against the selected locale. */
        CONSISTENT,
        /** Weak evidence of the other language; recorded as a warning only. */
        UNCERTAIN,
        /** Long text dominated by the other language's function words: a repairable violation. */
        MISMATCH
    }

    /**
     * Count function words of each supported language in human-facing text after removing
     * protected quotes and glossary renderings, which may legitimately be in another language.
     *
     * <p>{@link LocaleSignal#MISMATCH} requires at least {@link #LOCALE_MIN_WORDS} words, at least
     * {@link #LOCALE_MIN_FOREIGN} other-language function words and more than twice as many as the
     * selected language's. Weaker evidence yields {@link LocaleSignal#UNCERTAIN}: docs/29 section 5
     * treats a detector as a warning on short or mixed-name passages, never as proof.
     *
     * @param texts every human-facing output string
     * @param locale selected output locale
     * @param exclusions protected quotes and glossary renderings to remove first
     * @return the signal
     */
    static LocaleSignal localeSignal(List<String> texts, SupportedLocale locale, List<String> exclusions) {
        int words = 0;
        int selected = 0;
        int foreign = 0;
        Set<String> own = locale == SupportedLocale.IT ? ITALIAN : ENGLISH;
        Set<String> other = locale == SupportedLocale.IT ? ENGLISH : ITALIAN;
        for (String text : texts) {
            String remaining = text;
            for (String exclusion : exclusions) {
                remaining = remaining.replace(exclusion, " ");
            }
            Matcher matcher = WORD.matcher(remaining.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                // Italian elision ("l'uomo", "dell'oste") contributes its article to the count.
                for (String word : matcher.group().split("'")) {
                    if (word.isEmpty()) {
                        continue;
                    }
                    words++;
                    if (own.contains(word)) {
                        selected++;
                    } else if (other.contains(word)) {
                        foreign++;
                    }
                }
            }
        }
        if (words >= LOCALE_MIN_WORDS && foreign >= LOCALE_MIN_FOREIGN && foreign > 2 * selected) {
            return LocaleSignal.MISMATCH;
        }
        return foreign >= 2 && foreign > selected ? LocaleSignal.UNCERTAIN : LocaleSignal.CONSISTENT;
    }
}
