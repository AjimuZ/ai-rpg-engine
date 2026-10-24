package org.rpgengine.adventure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.rpgengine.persistence.AssignedIdEntity;
import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.presentation.SupportedLocaleConverter;

/**
 * Adventure workspace (docs/25 section 1): the owner's container for one adventure's
 * admitted content revisions and its campaigns. Other aggregates reference it by ID
 * only; the database enforces ownership with foreign keys and never cascades deletes.
 */
@Entity
@Table(name = "adventure_workspace")
public class AdventureWorkspace extends AssignedIdEntity {
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "genre", nullable = false)
    private String genre;

    @Convert(converter = SupportedLocaleConverter.class)
    @Column(name = "source_locale", nullable = false)
    private SupportedLocale sourceLocale;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Reserved for JPA. */
    protected AdventureWorkspace() {
    }

    /** @param id the new identity */
    private AdventureWorkspace(UUID id) {
        super(id);
    }

    /**
     * Create a workspace owned by one local owner profile.
     *
     * @param ownerId owning profile ID; the foreign key rejects unknown owners on flush
     * @param title 1..200 characters
     * @param genre 1..80 characters, free-form label until pack metadata defines it
     * @param sourceLocale authoring language of the adventure
     * @param now creation time
     * @return an unsaved workspace with a fresh opaque identity
     * @throws IllegalArgumentException for blank or oversized text
     */
    public static AdventureWorkspace create(UUID ownerId, String title, String genre,
            SupportedLocale sourceLocale, Instant now) {
        AdventureWorkspace workspace = new AdventureWorkspace(UUID.randomUUID());
        workspace.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        workspace.title = CanonicalValues.requireText(title, "title", 200);
        workspace.genre = CanonicalValues.requireText(genre, "genre", 80);
        workspace.sourceLocale = Objects.requireNonNull(sourceLocale, "sourceLocale");
        workspace.createdAt = CanonicalValues.timestamp(now, "now");
        workspace.updatedAt = workspace.createdAt;
        return workspace;
    }

    /** @return owning profile ID */
    public UUID getOwnerId() {
        return ownerId;
    }

    /** @return workspace title */
    public String getTitle() {
        return title;
    }

    /** @return genre label */
    public String getGenre() {
        return genre;
    }

    /** @return authoring language */
    public SupportedLocale getSourceLocale() {
        return sourceLocale;
    }

    /** @return creation time */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return last change time */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
