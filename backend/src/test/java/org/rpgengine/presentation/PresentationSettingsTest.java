package org.rpgengine.presentation;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verify locale negotiation and the presentation generations without a database. The
 * isolation of these values from canonical counters is proven against PostgreSQL in
 * {@code CanonicalFoundationsPersistenceTest}.
 */
class PresentationSettingsTest {
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    /** Regional and underscore variants normalize; anything else falls back to English visibly. */
    @Test
    void negotiatesSupportedLocales() {
        assertEquals(new SupportedLocale.Negotiation(SupportedLocale.IT, false), SupportedLocale.negotiate("it-IT"));
        assertEquals(new SupportedLocale.Negotiation(SupportedLocale.IT, false), SupportedLocale.negotiate(" it_CH "));
        assertEquals(new SupportedLocale.Negotiation(SupportedLocale.EN, false), SupportedLocale.negotiate("EN-gb"));
        for (String unsupported : new String[] {"fr", "", "   ", null, "not a tag!"}) {
            assertEquals(new SupportedLocale.Negotiation(SupportedLocale.EN, true),
                    SupportedLocale.negotiate(unsupported), String.valueOf(unsupported));
        }
    }

    /** Stored tags are strict: only the exact allowlisted values are accepted. */
    @Test
    void parsesStoredTagsStrictly() {
        assertEquals(SupportedLocale.IT, SupportedLocale.fromTag("it"));
        assertEquals("en", SupportedLocale.EN.tag());
        assertThrows(IllegalArgumentException.class, () -> SupportedLocale.fromTag("it-IT"));
        assertThrows(IllegalArgumentException.class, () -> SupportedLocale.fromTag("IT"));
        SupportedLocaleConverter converter = new SupportedLocaleConverter();
        assertEquals("it", converter.convertToDatabaseColumn(SupportedLocale.IT));
        assertEquals(SupportedLocale.EN, converter.convertToEntityAttribute("en"));
    }

    /** Only a real locale change advances the locale revision; generations move independently. */
    @Test
    void advancesPresentationGenerationsIndependently() {
        CampaignPresentationSettings settings =
                CampaignPresentationSettings.create(UUID.randomUUID(), SupportedLocale.EN, NOW);
        assertEquals(0, settings.getLocaleRevision());
        assertFalse(settings.changeNarrativeLocale(SupportedLocale.EN, NOW.plusSeconds(1)));
        assertEquals(NOW, settings.getUpdatedAt());
        assertTrue(settings.changeNarrativeLocale(SupportedLocale.IT, NOW.plusSeconds(2)));
        assertEquals(1, settings.getLocaleRevision());
        assertEquals(1, settings.advanceGlossaryRevision(NOW.plusSeconds(3)));
        assertEquals(2, settings.advanceGlossaryRevision(NOW.plusSeconds(4)));
        assertEquals(1, settings.advanceMediaConfigurationGeneration(NOW.plusSeconds(5)));
        assertEquals(1, settings.getLocaleRevision());
        assertEquals(2, settings.getGlossaryRevision());
        assertEquals(SupportedLocale.IT, settings.getNarrativeLocale());
    }
}
