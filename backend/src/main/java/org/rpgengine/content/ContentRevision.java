package org.rpgengine.content;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.rpgengine.persistence.AssignedIdEntity;
import org.rpgengine.persistence.CanonicalValues;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.presentation.SupportedLocaleConverter;

/**
 * Immutable admitted content revision of one adventure: the exact pack snapshot and
 * ruleset that campaigns are pinned to (docs/05 InstalledPack/RulesetRevision, docs/25).
 *
 * <p>{@link Immutable} stops Hibernate from issuing updates, and a database trigger
 * rejects any UPDATE from other clients. Changed bytes require a new revision.
 * Pack compilation and admission checks themselves arrive with P04.
 */
@Entity
@Immutable
@Table(name = "content_revision")
public class ContentRevision extends AssignedIdEntity {
    @Column(name = "adventure_id", nullable = false, updatable = false)
    private UUID adventureId;

    @Column(name = "pack_id", nullable = false, updatable = false)
    private String packId;

    @Column(name = "pack_version", nullable = false, updatable = false)
    private String packVersion;

    @Column(name = "content_hash", nullable = false, updatable = false)
    private String contentHash;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "ruleset_id", nullable = false, updatable = false)
    private String rulesetId;

    @Column(name = "ruleset_version", nullable = false, updatable = false)
    private String rulesetVersion;

    @Column(name = "ruleset_hash", nullable = false, updatable = false)
    private String rulesetHash;

    @Convert(converter = SupportedLocaleConverter.class)
    @Column(name = "source_locale", nullable = false, updatable = false)
    private SupportedLocale sourceLocale;

    @Column(name = "admitted_at", nullable = false, updatable = false)
    private Instant admittedAt;

    /** Reserved for JPA. */
    protected ContentRevision() {
    }

    /** @param id the new identity */
    private ContentRevision(UUID id) {
        super(id);
    }

    /**
     * Record an admitted pack snapshot for an adventure.
     *
     * @param adventureId owning adventure; campaigns of other adventures cannot use it
     * @param pack pack identity, digest, schema version and source language
     * @param ruleset ruleset identity and digest the pack was admitted with
     * @param now admission time
     * @return an unsaved immutable revision with a fresh opaque identity
     * @throws IllegalArgumentException if any coordinate is malformed
     */
    public static ContentRevision admit(UUID adventureId, PackCoordinates pack,
            RulesetCoordinates ruleset, Instant now) {
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(ruleset, "ruleset");
        ContentRevision revision = new ContentRevision(UUID.randomUUID());
        revision.adventureId = Objects.requireNonNull(adventureId, "adventureId");
        revision.packId = pack.packId();
        revision.packVersion = pack.packVersion();
        revision.contentHash = pack.contentHash();
        revision.schemaVersion = pack.schemaVersion();
        revision.sourceLocale = pack.sourceLocale();
        revision.rulesetId = ruleset.rulesetId();
        revision.rulesetVersion = ruleset.rulesetVersion();
        revision.rulesetHash = ruleset.rulesetHash();
        revision.admittedAt = CanonicalValues.timestamp(now, "now");
        return revision;
    }

    /** @return owning adventure ID */
    public UUID getAdventureId() {
        return adventureId;
    }

    /** @return pack coordinates of this revision */
    public PackCoordinates getPack() {
        return new PackCoordinates(packId, packVersion, contentHash, schemaVersion, sourceLocale);
    }

    /** @return ruleset coordinates of this revision */
    public RulesetCoordinates getRuleset() {
        return new RulesetCoordinates(rulesetId, rulesetVersion, rulesetHash);
    }

    /** @return admission time */
    public Instant getAdmittedAt() {
        return admittedAt;
    }

    /**
     * Pack identity of an admitted snapshot.
     *
     * @param packId 1..128 character pack identifier
     * @param packVersion 1..64 character version label
     * @param contentHash lowercase hexadecimal SHA-256 of the normalized pack content
     * @param schemaVersion positive pack schema version
     * @param sourceLocale language the pack was authored in
     */
    public record PackCoordinates(String packId, String packVersion, String contentHash,
            int schemaVersion, SupportedLocale sourceLocale) {
        /** Validate eagerly; throws IllegalArgumentException or NullPointerException. */
        public PackCoordinates {
            CanonicalValues.requireText(packId, "packId", 128);
            CanonicalValues.requireText(packVersion, "packVersion", 64);
            CanonicalValues.requireSha256Hex(contentHash, "contentHash");
            CanonicalValues.requirePositive(schemaVersion, "schemaVersion");
            Objects.requireNonNull(sourceLocale, "sourceLocale");
        }
    }

    /**
     * Ruleset identity used to resolve mechanics for campaigns on this revision.
     *
     * @param rulesetId 1..128 character ruleset identifier
     * @param rulesetVersion 1..64 character version label
     * @param rulesetHash lowercase hexadecimal SHA-256 of the ruleset definition
     */
    public record RulesetCoordinates(String rulesetId, String rulesetVersion, String rulesetHash) {
        /** Validate eagerly; throws IllegalArgumentException or NullPointerException. */
        public RulesetCoordinates {
            CanonicalValues.requireText(rulesetId, "rulesetId", 128);
            CanonicalValues.requireText(rulesetVersion, "rulesetVersion", 64);
            CanonicalValues.requireSha256Hex(rulesetHash, "rulesetHash");
        }
    }
}
