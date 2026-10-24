package org.rpgengine.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.persistence.VersionedEntity;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.presentation.SupportedLocaleConverter;

/**
 * Local owner profile (docs/05 section 2): identity and display preferences only.
 * It never stores account, token or credential material. {@code uiLocale} is a UI
 * preference, independent of any campaign's narrative locale or world counters.
 */
@Entity
@Table(name = "identity_owner_profile")
public class OwnerProfile extends VersionedEntity {
    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Convert(converter = SupportedLocaleConverter.class)
    @Column(name = "ui_locale", nullable = false)
    private SupportedLocale uiLocale;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Reserved for JPA. */
    protected OwnerProfile() {
    }

    /**
     * Create a new profile with a fresh opaque identity and the default English UI.
     *
     * @param displayName 1..120 characters shown in the local UI
     * @param now creation time
     * @return an unsaved profile
     * @throws IllegalArgumentException if the display name is blank or too long
     */
    public static OwnerProfile create(String displayName, Instant now) {
        OwnerProfile profile = new OwnerProfile(UUID.randomUUID());
        profile.displayName = CanonicalValues.requireText(displayName, "displayName", 120);
        profile.uiLocale = SupportedLocale.EN;
        profile.createdAt = CanonicalValues.timestamp(now, "now");
        profile.updatedAt = profile.createdAt;
        return profile;
    }

    /** @param id the new identity */
    private OwnerProfile(UUID id) {
        super(id);
    }

    /**
     * Change the UI locale preference. This touches only this row; it never affects a
     * campaign's narrative locale, presentation generations or canonical counters.
     *
     * @param locale the selected allowlisted locale
     * @param now change time
     */
    public void changeUiLocale(SupportedLocale locale, Instant now) {
        this.uiLocale = Objects.requireNonNull(locale, "locale");
        this.updatedAt = CanonicalValues.timestamp(now, "now");
    }

    /** @return the display name */
    public String getDisplayName() {
        return displayName;
    }

    /** @return the UI locale preference */
    public SupportedLocale getUiLocale() {
        return uiLocale;
    }

    /** @return creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return last preference change time */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
