package org.rpgengine.assets;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.content.PackException;
import org.rpgengine.content.PackMedia;
import org.rpgengine.content.PackPresentation;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.presentation.PresentationEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registry of validated presentation media (V37 {@code managed_asset} and {@code asset_association}): it accepts
 * a late job result only when it is not stale, and serves bytes by their SHA-256.
 *
 * <p>Always holds: stored bytes pass {@link PackMedia#inspectBytes} and fit the category, so a caller cannot
 * choose the media type or a serving path; a result is accepted at most once per campaign, category, subject and
 * appearance revision, and the first accepted result wins; a result that is older than the one in force, requested
 * under another pinned pack revision, or requested after the campaign's current revision never replaces anything;
 * and {@link #serve} returns bytes only for a hash the asking owner's campaign is associated with or that its
 * pinned pack revision ships, so equal names or hashes in another campaign never resolve. Nothing here advances a
 * canonical counter or writes world rows, and no metadata it returns carries a prompt, path, URL or secret.
 */
@Service
public class ManagedAssets {
    /** Longest accepted credit line. */
    public static final int MAX_CREDIT = 200;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final String LATEST = "select a.category, a.subject, a.appearance_revision, a.source_revision, a.sha256, "
            + "m.media_type, m.width, m.height, m.byte_size, a.credit from asset_association a "
            + "join managed_asset m on m.sha256 = a.sha256 where a.campaign_id = ? and a.category = ? and a.subject = ? "
            + "order by a.appearance_revision desc limit 1";
    /** Most subjects one listing returns; a campaign holding more is cut at the first 500 by category and subject. */
    public static final int MAX_LISTED = 500;
    private static final String CURRENT_ALL = "select distinct on (a.category, a.subject) a.category, a.subject, "
            + "a.appearance_revision, a.source_revision, a.sha256, m.media_type, m.width, m.height, m.byte_size, a.credit "
            + "from asset_association a join managed_asset m on m.sha256 = a.sha256 where a.campaign_id = ? "
            + "order by a.category, a.subject, a.appearance_revision desc limit " + MAX_LISTED;
    /** Sealed image files of one pack revision, as its import report recorded them. */
    private static final String AUTHORED = "select f->>'sha256', f->>'mediaType', coalesce((f->>'width')::int, 0), "
            + "coalesce((f->>'height')::int, 0), (f->>'bytes')::bigint from content_revision_report r, "
            + "jsonb_array_elements(r.report->'files') f where r.content_revision_id = ? and f->>'path' = ? "
            + "and f->>'category' = 'asset' and f->>'mediaType' like 'image/%'";
    /** Sealed audio files of one pack revision, as its import report recorded them. */
    private static final String AUTHORED_AUDIO = "select f->>'sha256', f->>'mediaType' from content_revision_report r, "
            + "jsonb_array_elements(r.report->'files') f where r.content_revision_id = ? and f->>'path' = ? "
            + "and f->>'category' = 'asset' and f->>'mediaType' like 'audio/%'";
    private final JdbcTemplate jdbc;
    private final PackRegistry packs;
    private final TransactionTemplate transaction;
    private final TransactionTemplate reads;

    /**
     * Wire persistence; missing dependencies fail startup.
     *
     * @param jdbc JDBC access joined to the transaction
     * @param manager shared transaction manager
     * @param packs registry that recompiles a campaign's pinned pack, whose presentation file binds authored artwork
     */
    public ManagedAssets(JdbcTemplate jdbc, PlatformTransactionManager manager, PackRegistry packs) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.packs = Objects.requireNonNull(packs, "packs");
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
        reads.setTimeout(30);
    }

    /** What a job asks to publish; the bytes are untrusted until validated. */
    public record Candidate(AssetCategory category, String subject, long appearanceRevision, long sourceRevision,
            UUID contentRevisionId, String credit, byte[] bytes) {
        /** Copy the bytes so the request cannot change while it is judged. */
        public Candidate {
            bytes = bytes == null ? null : bytes.clone();
        }
    }

    /**
     * Public description of one accepted asset. Its components are the complete metadata allowlist: a hash, media
     * facts, the public subject reference, the revision and the credit line.
     *
     * @param category category of the association
     * @param subject stable public reference of the depicted scene, character or item definition
     * @param appearanceRevision version of the subject's appearance this asset depicts
     * @param sourceRevision campaign state revision the producing job was requested at
     * @param sha256 lower-case hexadecimal SHA-256 of the bytes, which is also the serving identity
     * @param mediaType canonical media type
     * @param width declared image width, 0 for audio
     * @param height declared image height, 0 for audio
     * @param byteSize size in bytes
     * @param credit visible credit line
     */
    public record Metadata(AssetCategory category, String subject, long appearanceRevision, long sourceRevision,
            String sha256, String mediaType, int width, int height, long byteSize, String credit) {
    }

    /** Verdict of {@link #publish}. */
    public enum Status {
        /** The result was stored and is now the one in force. */
        ACCEPTED,
        /** The same appearance already has an accepted result, which stays in force. */
        REUSED,
        /** A newer appearance of the subject is already accepted. */
        STALE_APPEARANCE,
        /** The request predates the accepted scene or map, so it cannot replace what is shown. */
        STALE_SOURCE,
        /** The request was made under a pack revision the campaign no longer pins. */
        STALE_CONTENT
    }

    /**
     * Outcome of {@link #publish}.
     *
     * @param status the verdict
     * @param current the association in force for the subject after the call, or null when it has none
     */
    public record Published(Status status, Metadata current) {
    }

    /**
     * Bytes of one asset together with what a server needs to send them.
     *
     * @param sha256 hash the bytes were found and verified under
     * @param mediaType canonical media type stored with the bytes
     * @param bytes the verified content
     */
    public record Served(String sha256, String mediaType, byte[] bytes) {
    }

    /**
     * Judge and, when fresh, store one job result. Takes the campaign's row lock, so racing results for one
     * campaign are judged one after the other.
     *
     * @param campaign campaign whose job produced the result
     * @param candidate the result and the identity its job was requested with
     * @return the verdict and the association in force afterwards; a stale or reused result stores nothing
     * @throws IllegalArgumentException for an invalid reference, revision or credit, bytes of an unsupported or
     *         unsuitable media type, or a source revision beyond the campaign's current one; no row is written
     * @throws org.rpgengine.content.PackException {@code MEDIA_TYPE} or {@code MEDIA_LIMIT} for bad bytes
     * @throws org.springframework.dao.DataAccessException when the campaign is missing or storage fails
     */
    public Published publish(UUID campaign, Candidate candidate) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.category() == null || candidate.subject() == null
                || !PresentationEvent.REFERENCE.matcher(candidate.subject()).matches()
                || candidate.appearanceRevision() < 1 || candidate.sourceRevision() < 0
                || candidate.contentRevisionId() == null || candidate.bytes() == null) {
            throw new IllegalArgumentException("invalid asset request");
        }
        String credit = candidate.credit();
        if (credit == null || credit.isBlank() || credit.length() > MAX_CREDIT || CONTROL.matcher(credit).find()) {
            throw new IllegalArgumentException("invalid asset credit");
        }
        // Pure CPU work, so no lock is held while it runs.
        PackMedia.Media media = PackMedia.inspectBytes(candidate.bytes());
        if (!candidate.category().accepts(media.mediaType())) {
            throw new IllegalArgumentException("media does not fit the category");
        }
        String hash = sha256(candidate.bytes());
        return transaction.execute(status -> {
            var campaignRow = jdbc.queryForMap("select state_revision, content_revision_id from campaign where id = ? "
                    + "for no key update", campaign);
            long state = ((Number) campaignRow.get("state_revision")).longValue();
            if (candidate.sourceRevision() > state) {
                throw new IllegalArgumentException("request is newer than the campaign");
            }
            String category = candidate.category().wire();
            Metadata latest = jdbc.query(LATEST, (rs, n) -> metadata(rs), campaign, category, candidate.subject())
                    .stream().findFirst().orElse(null);
            if (!candidate.contentRevisionId().equals(campaignRow.get("content_revision_id"))) {
                return new Published(Status.STALE_CONTENT, latest);
            }
            if (latest != null && candidate.appearanceRevision() < latest.appearanceRevision()) {
                return new Published(Status.STALE_APPEARANCE, latest);
            }
            if (latest != null && candidate.appearanceRevision() == latest.appearanceRevision()) {
                return new Published(Status.REUSED, latest);
            }
            if (latest != null && candidate.category().orderedBySource()
                    && candidate.sourceRevision() < latest.sourceRevision()) {
                return new Published(Status.STALE_SOURCE, latest);
            }
            jdbc.update("insert into managed_asset (sha256, media_type, byte_size, width, height, content, created_at) "
                    + "values (?, ?, ?, ?, ?, ?, clock_timestamp()) on conflict (sha256) do nothing", hash,
                    media.mediaType(), (long) candidate.bytes().length, media.width(), media.height(), candidate.bytes());
            jdbc.update("insert into asset_association (campaign_id, category, subject, appearance_revision, "
                    + "source_revision, content_revision_id, sha256, credit, created_at) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())", campaign, category, candidate.subject(),
                    candidate.appearanceRevision(), candidate.sourceRevision(), candidate.contentRevisionId(), hash,
                    credit);
            return new Published(Status.ACCEPTED, new Metadata(candidate.category(), candidate.subject(),
                    candidate.appearanceRevision(), candidate.sourceRevision(), hash, media.mediaType(), media.width(),
                    media.height(), candidate.bytes().length, credit));
        });
    }

    /**
     * Read the association in force for a subject.
     *
     * @param campaign campaign scope
     * @param category category of the subject
     * @param subject stable public reference
     * @return the newest accepted association, or empty
     * @throws org.springframework.dao.DataAccessException when storage fails
     */
    public Optional<Metadata> current(UUID campaign, AssetCategory category, String subject) {
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(subject, "subject");
        return jdbc.query(LATEST, (rs, n) -> metadata(rs), campaign, category.wire(), subject).stream().findFirst();
    }

    /**
     * List the artwork in force for every subject of one campaign, so a client can look an entity's stable image up
     * by category and subject instead of guessing a hash. Accepted associations come first; a subject the pinned
     * pack revision binds to authored artwork (see {@link PackPresentation#artwork()}) and that has no association
     * is listed with that artwork, credited as the pack declares.
     *
     * @param owner owner asking, from local admission
     * @param adventure adventure workspace the campaign belongs to
     * @param campaign campaign scope
     * @return the newest accepted association of each category and subject ordered by category then subject (at
     *         most {@value #MAX_LISTED}), empty list when the campaign has none, or empty optional when the owner,
     *         adventure or campaign do not match
     * @throws org.springframework.dao.DataAccessException when storage fails
     */
    public Optional<java.util.List<Metadata>> artwork(UUID owner, UUID adventure, UUID campaign) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        return reads.execute(status -> {
            UUID revision = pinnedRevision(owner, adventure, campaign);
            if (revision == null) {
                return Optional.<java.util.List<Metadata>>empty();
            }
            var listed = new java.util.ArrayList<>(jdbc.query(CURRENT_ALL, (rs, n) -> metadata(rs), campaign));
            var held = new java.util.HashSet<String>();
            listed.forEach(item -> held.add(item.category().wire() + '\u0000' + item.subject()));
            for (Metadata authored : authored(adventure, revision)) {
                // An accepted association is a later, explicit choice for the subject, so it keeps its place.
                if (held.add(authored.category().wire() + '\u0000' + authored.subject())) {
                    listed.add(authored);
                }
            }
            listed.sort(java.util.Comparator.comparing((Metadata item) -> item.category().wire())
                    .thenComparing(Metadata::subject));
            return Optional.of(java.util.List.copyOf(listed.size() > MAX_LISTED ? listed.subList(0, MAX_LISTED) : listed));
        });
    }

    /**
     * The artwork the pinned pack revision binds to its entities in its presentation file, as appearance revision 1
     * of the pack's own source revision 0. A binding whose file is not a sealed image of the revision is skipped.
     * A pack that cannot be loaded here binds nothing: the failure is reported by the paths that need the pack, and
     * it must not hide the associations the campaign holds.
     */
    private java.util.List<Metadata> authored(UUID adventure, UUID revision) {
        java.util.List<PackPresentation.Artwork> bindings;
        try {
            bindings = packs.loadForCampaign(adventure, revision).root().presentation().artwork();
        } catch (PackException | IllegalStateException failure) {
            return java.util.List.of();
        }
        var found = new java.util.ArrayList<Metadata>();
        for (PackPresentation.Artwork binding : bindings) {
            jdbc.query(AUTHORED, (rs, n) -> new Metadata(AssetCategory.fromWire(binding.category()), binding.subject(), 1, 0,
                    rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getLong(5), binding.credit()),
                    revision, binding.file()).stream().findFirst().ifPresent(found::add);
        }
        return found;
    }

    /**
     * A pack's music declaration as the client receives it (P17.2-D).
     *
     * @param moods public state to mood for the states the pinned pack declares, in public-state order; empty when it
     *        declares no policy
     * @param tracks the pack's tracks whose file is a sealed audio asset of the pinned revision
     */
    public record MusicListing(java.util.Map<String, String> moods, java.util.List<MusicTrack> tracks) {
    }

    /**
     * One pack track with the hash {@link #serve} accepts for it.
     *
     * @param track the declaration, credits included
     * @param licenseUrl exact text URL of the track's license
     * @param sha256 lower-case SHA-256 of the sealed file
     * @param mediaType its canonical audio media type
     */
    public record MusicTrack(PackPresentation.Track track, String licenseUrl, String sha256, String mediaType) {
    }

    /**
     * Read the mood policy and authored tracks of the campaign's pinned pack revision. The policy is part of the sealed
     * revision, so it cannot change under a campaign; it maps only closed public states (see
     * {@link PackPresentation.Music}). Reading changes nothing.
     *
     * @param owner owner asking, from local admission
     * @param adventure adventure workspace the campaign belongs to
     * @param campaign campaign scope
     * @return the listing (empty policy and no tracks for a pack that declares none or cannot be loaded here), or empty
     *         when the owner, adventure or campaign do not match
     * @throws org.springframework.dao.DataAccessException when storage fails
     */
    public Optional<MusicListing> music(UUID owner, UUID adventure, UUID campaign) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        return reads.execute(status -> {
            UUID revision = pinnedRevision(owner, adventure, campaign);
            if (revision == null) {
                return Optional.<MusicListing>empty();
            }
            PackPresentation.Music declared;
            try {
                declared = packs.loadForCampaign(adventure, revision).root().presentation().music();
            } catch (PackException | IllegalStateException failure) {
                // Music is optional: a pack that cannot be loaded here plays the default policy and no own track.
                return Optional.of(new MusicListing(java.util.Map.of(), java.util.List.of()));
            }
            var tracks = new java.util.ArrayList<MusicTrack>();
            for (PackPresentation.Track track : declared.tracks()) {
                jdbc.query(AUTHORED_AUDIO, (rs, n) -> new MusicTrack(track,
                        PackPresentation.TRACK_LICENSES.get(track.license()), rs.getString(1), rs.getString(2)),
                        revision, track.file()).stream().findFirst().ifPresent(tracks::add);
            }
            return Optional.of(new MusicListing(declared.moods(), java.util.List.copyOf(tracks)));
        });
    }

    /** The pack revision the owner's campaign pins, or null when owner, adventure and campaign do not line up. */
    private UUID pinnedRevision(UUID owner, UUID adventure, UUID campaign) {
        return jdbc.query("select c.content_revision_id from campaign c join adventure_workspace w "
                + "on w.id = c.adventure_id where c.id = ? and c.adventure_id = ? and w.owner_id = ?",
                (rs, n) -> rs.getObject(1, UUID.class), campaign, adventure, owner).stream().findFirst().orElse(null);
    }

    /**
     * Find the bytes a campaign may show under a hash.
     *
     * @param owner owner asking, from local admission
     * @param adventure adventure workspace the campaign belongs to
     * @param campaign campaign scope
     * @param sha256 lower-case hexadecimal SHA-256 of the wanted bytes
     * @return the bytes with their canonical media type, or empty when the owner, adventure or campaign do not
     *         match, the campaign has no association with the hash and its pinned pack revision ships no asset of
     *         that hash, or the stored bytes no longer match the hash
     * @throws IllegalArgumentException when the hash is not 64 lower-case hexadecimal characters
     * @throws org.springframework.dao.DataAccessException when storage fails
     */
    public Optional<Served> serve(UUID owner, UUID adventure, UUID campaign, String sha256) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        if (sha256 == null || !HASH.matcher(sha256).matches()) {
            throw new IllegalArgumentException("invalid asset hash");
        }
        UUID revision = pinnedRevision(owner, adventure, campaign);
        if (revision == null) {
            return Optional.empty();
        }
        var found = jdbc.query("select m.media_type, m.content from managed_asset m where m.sha256 = ? and exists ("
                + "select 1 from asset_association a where a.campaign_id = ? and a.sha256 = m.sha256)",
                (rs, n) -> new Served(sha256, rs.getString(1), rs.getBytes(2)), sha256, campaign).stream().findFirst();
        if (found.isEmpty()) {
            // Authored artwork of the pinned pack revision: sealed bytes, listed by the pack as category asset.
            found = jdbc.query("select media_type, content from content_revision_file where content_revision_id = ? "
                    + "and category = 'asset' and sha256 = ? and media_type is not null",
                    (rs, n) -> new Served(sha256, rs.getString(1), rs.getBytes(2)), revision, sha256).stream().findFirst();
        }
        // The database checks the digest on insert; recomputing here means a damaged row is never served as it.
        return found.filter(served -> sha256(served.bytes()).equals(sha256));
    }

    private static Metadata metadata(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Metadata(AssetCategory.fromWire(rs.getString("category")), rs.getString("subject"),
                rs.getLong("appearance_revision"), rs.getLong("source_revision"), rs.getString("sha256"),
                rs.getString("media_type"), rs.getInt("width"), rs.getInt("height"), rs.getLong("byte_size"),
                rs.getString("credit"));
    }

    /** Lower-case hexadecimal SHA-256, the identity under which bytes are stored and served. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
