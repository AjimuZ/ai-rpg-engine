package org.rpgengine.presentation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.persistence.VersionedEntity;

/**
 * Presentation preferences of one campaign (docs/29): the narrative locale and the
 * presentation generations that invalidate cached narration, glossary rendering and
 * media configuration.
 *
 * <p>These live in their own table on purpose. Changing any of them updates only this
 * row, so a locale switch or glossary edit can never advance {@code stateRevision},
 * {@code gameTurn}, {@code worldTime} or {@code eventSequence}, and never conflicts with
 * a concurrent gameplay commit on the campaign row. The identity is the campaign ID.
 */
@Entity
@Table(name = "presentation_campaign_settings")
@AttributeOverride(name = "id", column = @Column(name = "campaign_id", updatable = false))
public class CampaignPresentationSettings extends VersionedEntity {
    @Convert(converter = SupportedLocaleConverter.class)
    @Column(name = "narrative_locale", nullable = false)
    private SupportedLocale narrativeLocale;

    @Column(name = "locale_revision", nullable = false)
    private long localeRevision;

    @Column(name = "glossary_revision", nullable = false)
    private long glossaryRevision;

    @Column(name = "media_configuration_generation", nullable = false)
    private long mediaConfigurationGeneration;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Reserved for JPA. */
    protected CampaignPresentationSettings() {
    }

    /** @param campaignId the campaign these settings belong to */
    private CampaignPresentationSettings(UUID campaignId) {
        super(campaignId);
    }

    /**
     * Create the settings row for a new campaign with all generations at zero.
     *
     * @param campaignId the campaign identity (also this row's identity)
     * @param narrativeLocale initial narrative locale chosen for the campaign
     * @param now creation time
     * @return unsaved settings
     */
    public static CampaignPresentationSettings create(UUID campaignId, SupportedLocale narrativeLocale,
            Instant now) {
        CampaignPresentationSettings settings =
                new CampaignPresentationSettings(Objects.requireNonNull(campaignId, "campaignId"));
        settings.narrativeLocale = Objects.requireNonNull(narrativeLocale, "narrativeLocale");
        settings.updatedAt = CanonicalValues.timestamp(now, "now");
        return settings;
    }

    /**
     * Select a narrative locale. Re-selecting the current locale is a no-op so repeated
     * clicks do not invalidate cached narration.
     *
     * @param locale the selected allowlisted locale
     * @param now change time
     * @return true if the locale changed and {@code localeRevision} advanced
     */
    public boolean changeNarrativeLocale(SupportedLocale locale, Instant now) {
        Objects.requireNonNull(locale, "locale");
        Instant changedAt = CanonicalValues.timestamp(now, "now");
        if (locale == narrativeLocale) {
            return false;
        }
        narrativeLocale = locale;
        localeRevision = Math.addExact(localeRevision, 1);
        updatedAt = changedAt;
        return true;
    }

    /**
     * Record a glossary edit so presentation caches keyed by glossary revision expire.
     *
     * @param now change time
     * @return the new glossary revision
     * @throws ArithmeticException on overflow (not reachable in practice)
     */
    public long advanceGlossaryRevision(Instant now) {
        updatedAt = CanonicalValues.timestamp(now, "now");
        glossaryRevision = Math.addExact(glossaryRevision, 1);
        return glossaryRevision;
    }

    /**
     * Record a media configuration change (style, voice or provider settings).
     *
     * @param now change time
     * @return the new media configuration generation
     * @throws ArithmeticException on overflow (not reachable in practice)
     */
    public long advanceMediaConfigurationGeneration(Instant now) {
        updatedAt = CanonicalValues.timestamp(now, "now");
        mediaConfigurationGeneration = Math.addExact(mediaConfigurationGeneration, 1);
        return mediaConfigurationGeneration;
    }

    /** @return the campaign these settings belong to */
    public UUID getCampaignId() {
        return getId();
    }

    /** @return current narrative locale */
    public SupportedLocale getNarrativeLocale() {
        return narrativeLocale;
    }

    /** @return number of effective narrative locale changes */
    public long getLocaleRevision() {
        return localeRevision;
    }

    /** @return glossary revision */
    public long getGlossaryRevision() {
        return glossaryRevision;
    }

    /** @return media configuration generation */
    public long getMediaConfigurationGeneration() {
        return mediaConfigurationGeneration;
    }

    /** @return last change time of this row */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
