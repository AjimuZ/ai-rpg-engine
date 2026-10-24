package org.rpgengine.presentation;

import java.util.Locale;
import java.util.Objects;

/**
 * Closed allowlist of locales the engine can present (docs/29 section 2).
 *
 * <p>The same allowlist serves the owner's UI locale, a campaign's narrative locale and
 * a pack's source locale, but those remain separate preferences stored in separate
 * columns. None of them is world state: changing one never advances a canonical counter.
 */
public enum SupportedLocale {
    /** English, the default and the fallback for unsupported requests. */
    EN("en"),
    /** Italian. */
    IT("it");

    private final String tag;

    /** @param tag the stored lowercase BCP 47 primary language subtag */
    SupportedLocale(String tag) {
        this.tag = tag;
    }

    /** @return the stored tag, for example {@code en} */
    public String tag() {
        return tag;
    }

    /**
     * Strict lookup used for stored values and explicit settings.
     *
     * @param tag an exact stored tag such as {@code it}
     * @return the matching locale
     * @throws IllegalArgumentException if the tag is not on the allowlist
     */
    public static SupportedLocale fromTag(String tag) {
        Objects.requireNonNull(tag, "tag");
        for (SupportedLocale locale : values()) {
            if (locale.tag.equals(tag)) {
                return locale;
            }
        }
        throw new IllegalArgumentException("unsupported locale tag: " + tag);
    }

    /**
     * Normalize a requested BCP 47 tag: {@code en-*} maps to English, {@code it-*} to
     * Italian, and anything else (including null, blank or malformed input) falls back
     * to English with {@link Negotiation#fellBack()} set so the caller can show a notice.
     *
     * @param requested a tag such as {@code it-IT}, {@code EN_gb} or {@code fr}
     * @return the negotiated locale; never throws for bad input
     */
    public static Negotiation negotiate(String requested) {
        if (requested == null || requested.isBlank()) {
            return new Negotiation(EN, true);
        }
        // Locale.forLanguageTag rejects underscores, which browsers and OS settings use.
        String language = Locale.forLanguageTag(requested.strip().replace('_', '-')).getLanguage();
        for (SupportedLocale locale : values()) {
            if (locale.tag.equals(language)) {
                return new Negotiation(locale, false);
            }
        }
        return new Negotiation(EN, true);
    }

    /**
     * Result of {@link #negotiate(String)}.
     *
     * @param locale the locale to use
     * @param fellBack true when the request was unsupported and English was substituted
     */
    public record Negotiation(SupportedLocale locale, boolean fellBack) {
    }
}
