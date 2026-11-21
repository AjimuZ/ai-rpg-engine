package org.rpgengine.music;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * The finite, reviewed catalog of free-license recordings that optional music may use (P17.2-A).
 *
 * <p>Always holds: every recording has a unique stable ID, title, creator, mood, notices, modification
 * statement, an ISO verification date, an admitted license whose URL is the exact one for its version, and
 * a source page on an owner-approved HTTPS host. The catalog holds no media URL, so no unreviewed address
 * can be fetched from it; nothing here plays or downloads audio. Loading fails with
 * {@link IllegalArgumentException} naming the recording and field (never the offending value) when a
 * record is incomplete, so an unreviewed entry cannot be admitted by accident.
 */
public final class MusicCatalog {
    /** Classpath location of the reviewed catalog. */
    public static final String RESOURCE = "/music/catalog.json";
    /** Admitted licenses: ID to the exact license URL of that version. CC BY 2.x and non-free terms are absent. */
    public static final Map<String, String> LICENSES = Map.of(
            "CC0-1.0", "https://creativecommons.org/publicdomain/zero/1.0/",
            "CC-BY-3.0", "https://creativecommons.org/licenses/by/3.0/",
            "CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/");
    /** Owner-approved source hosts for the source page of a recording. */
    public static final Set<String> SOURCE_HOSTS = Set.of("commons.wikimedia.org");
    /** Moods a public presentation policy may map to a recording. */
    public static final Set<String> MOODS = Set.of("exploration", "dialogue", "rest", "tension", "combat",
            "victory", "defeat");

    private final List<Recording> recordings;

    private MusicCatalog(List<Recording> recordings) {
        this.recordings = List.copyOf(recordings);
    }

    /**
     * One reviewed recording with its complete rights record.
     *
     * @param id stable reference
     * @param title credited title
     * @param creator credited creator and other required attribution parties
     * @param mood one of {@link #MOODS}
     * @param sourcePage HTTPS page of the recording on an approved host
     * @param mediaFile file name of the exact recording on that page
     * @param licenseId key of {@link #LICENSES}
     * @param licenseName full license name with version
     * @param licenseUrl exact license URL of that version
     * @param notices attribution and other notices to preserve
     * @param modifications disclosed edits, or the statement that there are none
     * @param verifiedOn ISO date the rights were last checked
     * @param localHash SHA-256 of a permitted local copy, or null when none is held
     */
    public record Recording(String id, String title, String creator, String mood, String sourcePage,
            String mediaFile, String licenseId, String licenseName, String licenseUrl, String notices,
            String modifications, String verifiedOn, String localHash) {
    }

    /** @return the reviewed recordings in catalog order */
    public List<Recording> recordings() {
        return recordings;
    }

    /**
     * Find a recording by ID.
     *
     * @param id stable reference
     * @return the recording, or empty for an unknown ID (the runtime never invents one)
     */
    public Optional<Recording> find(String id) {
        return recordings.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /**
     * Load and validate the bundled catalog.
     *
     * @return the validated catalog
     * @throws IllegalArgumentException when a record is incomplete or not admissible
     */
    public static MusicCatalog load() {
        try (InputStream in = MusicCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalArgumentException("Music catalog resource is missing");
            }
            return parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parse and validate catalog JSON.
     *
     * @param json catalog document bytes
     * @return the validated catalog
     * @throws IllegalArgumentException when a record is incomplete or not admissible
     */
    public static MusicCatalog parse(byte[] json) {
        Map<?, ?> root = JsonMapper.builder().build().readValue(json, Map.class);
        if (!(root.get("recordings") instanceof List<?> items)) {
            throw new IllegalArgumentException("Music catalog needs a recordings list");
        }
        List<Recording> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : items) {
            Map<?, ?> m = (Map<?, ?>) item;
            String id = text(m, "id", "?");
            Recording r = new Recording(id, text(m, "title", id), text(m, "creator", id), text(m, "mood", id),
                    text(m, "sourcePage", id), text(m, "mediaFile", id), text(m, "licenseId", id),
                    text(m, "licenseName", id), text(m, "licenseUrl", id), text(m, "notices", id),
                    text(m, "modifications", id), text(m, "verifiedOn", id),
                    m.get("localHash") instanceof String s ? s : null);
            validate(r);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate recording " + id);
            }
            out.add(r);
        }
        return new MusicCatalog(out);
    }

    private static String text(Map<?, ?> m, String field, String id) {
        if (m.get(field) instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException("Recording " + id + " lacks " + field);
    }

    private static void validate(Recording r) {
        if (!MOODS.contains(r.mood())) {
            throw new IllegalArgumentException("Recording " + r.id() + " has an unknown mood");
        }
        if (!r.licenseUrl().equals(LICENSES.get(r.licenseId()))) {
            throw new IllegalArgumentException("Recording " + r.id() + " has a license that is not admitted or "
                    + "whose URL does not match its version");
        }
        URI source = URI.create(r.sourcePage());
        if (!"https".equals(source.getScheme()) || !SOURCE_HOSTS.contains(source.getHost())) {
            throw new IllegalArgumentException("Recording " + r.id() + " has a source page outside the approved hosts");
        }
        try {
            LocalDate.parse(r.verifiedOn());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Recording " + r.id() + " has no ISO verification date");
        }
        if (r.localHash() != null && !r.localHash().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Recording " + r.id() + " has a malformed local hash");
        }
    }
}
