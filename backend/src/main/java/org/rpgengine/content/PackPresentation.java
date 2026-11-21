package org.rpgengine.content;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.rpgengine.music.MusicCatalog;
import org.rpgengine.presentation.PresentationEvent;
import org.rpgengine.presentation.PublicPresentationScope;

/**
 * A pack's optional declaration of the public presentation references its adventure may show: scenes,
 * lines and assets, plus the poses and expressions each NPC definition may take (P05.7-B.1.2.1).
 *
 * <p>Always holds: every reference matches {@link PresentationEvent#REFERENCE}, so a declaration cannot
 * carry a URL, markup, path or script as an identifier; no collection exceeds
 * {@link PublicPresentationScope#MAX_ENTRIES}; and every scene backdrop and line audio names a declared
 * asset. A pack without the declaration compiles to {@link #NONE}. This is the pack-side allowlist only:
 * a later task builds the per-audience {@link PublicPresentationScope} from it, and nothing consumes it yet.
 *
 * <p>Source format (the manifest's {@code presentation} file, category {@code presentation}):
 * {@code schemaVersion}, optional {@code scenes} [{@code id}, optional {@code backdrop}], {@code lines}
 * [{@code id}, optional {@code audio}], {@code assets} [reference], and {@code actors} [{@code actor},
 * {@code poses}, {@code expressions}]. A reference to an asset the file does not list is an undeclared
 * asset and is refused. Since P17.1-C an optional {@code artwork} list [{@code category}, {@code subject},
 * {@code file}, {@code credit}] binds a listed {@code asset} file to the entity it depicts; the asset registry
 * lists those bindings with the campaign's artwork. Since P17.2-D an optional {@code music} object
 * [{@code moods} {public state: mood}, {@code tracks} [{@code id}, {@code mood}, {@code file}, {@code title},
 * {@code creator}, {@code license}, {@code source}, {@code notices}, {@code modifications}]] declares the pack's
 * mood policy and its own credited audio files (see {@link Music}).
 *
 * @param scenes declared scene references
 * @param lines declared line references
 * @param assets declared asset references; the only values a backdrop or audio may name
 * @param sceneBackdrops scene reference to the asset shown behind it, for scenes that declare one
 * @param lineAudio line reference to its audio asset, for lines that declare one
 * @param actors local NPC definition ID to the poses and expressions it may take
 * @param artwork authored image bindings
 * @param music mood policy and authored music tracks; {@link Music#NONE} when the pack declares none
 */
public record PackPresentation(Set<String> scenes, Set<String> lines, Set<String> assets,
        Map<String, String> sceneBackdrops, Map<String, String> lineAudio, Map<String, Looks> actors,
        List<Artwork> artwork, Music music) {
    /**
     * The closed public states a mood policy may name, equal to {@code PUBLIC_STATES} of the client
     * ({@code frontend/src/app/music/music-mood.ts}). Each is derived from the player-safe combat projection only,
     * so a policy has no key through which hidden state could reach the music. Declared before {@link #NONE}, whose
     * {@link Music#NONE} reads it during class initialization.
     */
    public static final List<String> PUBLIC_STATES = List.of("quiet", "offered", "fight", "won", "lost", "settled");
    /** The declaration of a pack that makes none: nothing may be presented. */
    public static final PackPresentation NONE = new PackPresentation(Set.of(), Set.of(), Set.of(), Map.of(),
            Map.of(), Map.of(), List.of(), Music.NONE);
    /**
     * Licenses a pack-authored track may carry, with the exact text URL of each: the reviewed music licenses of
     * {@link MusicCatalog#LICENSES} plus MIT, the license the project's own sample content already uses
     * (docs/adr/0054).
     */
    public static final Map<String, String> TRACK_LICENSES = trackLicenses();
    /** Audio extensions a track file may have; equal to the audio part of {@link PackMedia}'s allowlist. */
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("ogg", "mp3", "wav");
    /** Image categories a pack may bind authored files to; music and effects are audio and have no binding yet. */
    public static final Set<String> ARTWORK_CATEGORIES = Set.of("scene", "map", "portrait", "item", "ui");
    /** Longest credit line, equal to {@code ManagedAssets.MAX_CREDIT}. */
    public static final int MAX_CREDIT = 200;
    private static final int MAX = PublicPresentationScope.MAX_ENTRIES;
    private static final Pattern CREDIT = Pattern.compile("(?=.*\\S)[^\\p{Cntrl}]{1," + MAX_CREDIT + "}");
    private static final String EXPECTED = "Expected a stable reference of 1 to " + PresentationEvent.MAX_REFERENCE
            + " letters, digits, '.', '_' or '-', starting with a letter or digit";

    /**
     * Authored artwork bound to one subject: the pack file that depicts it and the credit line shown with it.
     *
     * @param category one of {@link #ARTWORK_CATEGORIES}
     * @param subject stable reference of the depicted item or NPC definition, scene or other subject
     * @param file path of a manifest file of category {@code asset}
     * @param credit visible credit line, 1 to 200 characters without control characters
     */
    public record Artwork(String category, String subject, String file, String credit) {
    }

    /**
     * A pack's music declaration (P17.2-D).
     *
     * <p>Always holds: every key of {@code moods} is one of {@link #PUBLIC_STATES} and every mood one of
     * {@link MusicCatalog#MOODS}; at most one track per mood; every track names a listed manifest file of category
     * {@code asset} with an audio extension and carries complete credit text and an admitted license. The policy
     * therefore cannot name an event, entity or state outside the closed public list.
     *
     * @param moods public state to mood, for the states the pack declares; empty when it declares no policy, in which
     *        case the client keeps its default mapping
     * @param tracks the pack's own credited recordings, in declaration order
     */
    public record Music(Map<String, String> moods, List<Track> tracks) {
        /** A pack without a music declaration. */
        public static final Music NONE = new Music(Map.of(), List.of());

        /** Copy both collections so the compiled declaration stays immutable; moods keep the public-state order. */
        public Music {
            var ordered = new java.util.LinkedHashMap<String, String>();
            for (String state : PUBLIC_STATES) {
                if (moods.containsKey(state)) {
                    ordered.put(state, moods.get(state));
                }
            }
            moods = java.util.Collections.unmodifiableMap(ordered);
            tracks = List.copyOf(tracks);
        }
    }

    /**
     * One recording shipped in the pack.
     *
     * @param id stable reference, unique within the pack
     * @param mood one of {@link MusicCatalog#MOODS}; the track plays for that mood
     * @param file path of a listed manifest file of category {@code asset} with an audio extension
     * @param title shown title
     * @param creator credited creator
     * @param license key of {@link #TRACK_LICENSES}
     * @param source plain-text origin of the recording (for example the generator that made it), never a URL to fetch
     * @param notices notices the license or creator requires
     * @param modifications disclosed changes, or a statement that there are none
     */
    public record Track(String id, String mood, String file, String title, String creator, String license, String source,
            String notices, String modifications) {
    }

    /** A declaration without authored artwork bindings or music. */
    public PackPresentation(Set<String> scenes, Set<String> lines, Set<String> assets,
            Map<String, String> sceneBackdrops, Map<String, String> lineAudio, Map<String, Looks> actors) {
        this(scenes, lines, assets, sceneBackdrops, lineAudio, actors, List.of(), Music.NONE);
    }

    /** A declaration without music. */
    public PackPresentation(Set<String> scenes, Set<String> lines, Set<String> assets,
            Map<String, String> sceneBackdrops, Map<String, String> lineAudio, Map<String, Looks> actors,
            List<Artwork> artwork) {
        this(scenes, lines, assets, sceneBackdrops, lineAudio, actors, artwork, Music.NONE);
    }

    /**
     * Poses and expressions one actor definition may take.
     *
     * @param poses allowed pose references
     * @param expressions allowed expression references
     */
    public record Looks(Set<String> poses, Set<String> expressions) {
        /** Copy the sets so the compiled declaration stays immutable. */
        public Looks {
            poses = Set.copyOf(poses);
            expressions = Set.copyOf(expressions);
        }
    }

    /** Copy every collection so the compiled declaration stays immutable. */
    public PackPresentation {
        scenes = Set.copyOf(scenes);
        lines = Set.copyOf(lines);
        assets = Set.copyOf(assets);
        sceneBackdrops = Map.copyOf(sceneBackdrops);
        lineAudio = Map.copyOf(lineAudio);
        actors = Map.copyOf(actors);
        artwork = List.copyOf(artwork);
        music = java.util.Objects.requireNonNull(music, "music");
    }

    /**
     * Read and validate the declaration a pack's manifest names, or return {@link #NONE} when it names none.
     *
     * @param pack parsed pack whose manifest may carry a {@code presentation} file
     * @param definitions the pack's local definitions by ID, used to check the actors named
     * @return the immutable declaration
     * @throws PackException located SCHEMA, DUPLICATE or REFERENCE_UNKNOWN diagnostic for the first
     *         violation; a message never repeats the rejected value
     */
    static PackPresentation compile(ParsedPack pack, Map<String, Definition> definitions) {
        String path = (String) pack.manifest().values().get("presentation");
        if (path == null) {
            return NONE;
        }
        PackDocument document = pack.documents().get(path);
        var root = new Fields(document, "", document.value(), null);
        root.integer("schemaVersion", 1, 1, null);
        var assets = root.matchingAll("assets", PresentationEvent.REFERENCE, MAX, EXPECTED);
        var declaredAssets = Set.copyOf(assets);
        var scenes = new HashSet<String>();
        var backdrops = new HashMap<String, String>();
        for (Fields entry : root.objects("scenes", 0, MAX)) {
            String id = unique(entry, "id", scenes);
            String backdrop = asset(entry, "backdrop", declaredAssets);
            if (backdrop != null) {
                backdrops.put(id, backdrop);
            }
            entry.close();
        }
        var lines = new HashSet<String>();
        var audio = new HashMap<String, String>();
        for (Fields entry : root.objects("lines", 0, MAX)) {
            String id = unique(entry, "id", lines);
            String clip = asset(entry, "audio", declaredAssets);
            if (clip != null) {
                audio.put(id, clip);
            }
            entry.close();
        }
        var actors = new HashMap<String, Looks>();
        for (Fields entry : root.objects("actors", 0, MAX)) {
            // Checked against the local index directly: the shared reference resolver would repeat the value.
            String actor = entry.matching("actor", Fields.ID, true, "Expected the stable ID of a local NPC definition");
            Definition definition = definitions.get(actor);
            if (definition == null || !definition.kind().equals("npc")) {
                throw entry.fail("REFERENCE_UNKNOWN", "actor", "Expected the ID of an NPC defined by this pack");
            }
            if (actors.containsKey(actor)) {
                throw entry.fail("DUPLICATE", "actor", "Actor already has a declaration");
            }
            var poses = entry.matchingAll("poses", PresentationEvent.REFERENCE, MAX, EXPECTED);
            var expressions = entry.matchingAll("expressions", PresentationEvent.REFERENCE, MAX, EXPECTED);
            actors.put(actor, new Looks(Set.copyOf(poses), Set.copyOf(expressions)));
            entry.close();
        }
        var artwork = artwork(pack, root, definitions, scenes);
        var music = music(pack, root);
        root.close();
        return new PackPresentation(scenes, lines, declaredAssets, backdrops, audio, actors, artwork, music);
    }

    /**
     * Read the optional {@code music} object: a mood policy over the closed public states and the pack's own tracks.
     * Unknown keys, a state outside {@link #PUBLIC_STATES}, an unknown mood, a second track for one mood, a file that
     * is not a listed audio asset, incomplete credit text or a license outside {@link #TRACK_LICENSES} are refused
     * with a located diagnostic that never repeats the value.
     */
    private static Music music(ParsedPack pack, Fields root) {
        Fields music = root.object("music", false);
        if (music == null) {
            return Music.NONE;
        }
        var moods = new HashMap<String, String>();
        Fields policy = music.object("moods", false);
        if (policy != null) {
            for (String state : PUBLIC_STATES) {
                if (policy.has(state)) {
                    moods.put(state, policy.choice(state, MusicCatalog.MOODS, null));
                }
            }
            // A key outside the public states is an unknown field here, so a policy cannot name hidden state.
            policy.close();
        }
        var listed = new HashSet<String>();
        pack.manifest().files().forEach(file -> {
            if (file.category().equals("asset") && AUDIO_EXTENSIONS.contains(PackPaths.extension(file.path()))) {
                listed.add(file.path());
            }
        });
        var ids = new HashSet<String>();
        var covered = new HashSet<String>();
        var tracks = new java.util.ArrayList<Track>();
        for (Fields entry : music.objects("tracks", 0, MusicCatalog.MOODS.size())) {
            String id = unique(entry, "id", ids);
            String mood = entry.choice("mood", MusicCatalog.MOODS, null);
            String file = entry.text("file", true);
            if (!listed.contains(file)) {
                throw entry.fail("FILE_REFERENCE", "file", "Expected a manifest file of category asset with an audio extension");
            }
            if (!covered.add(mood)) {
                throw entry.fail("DUPLICATE", "mood", "Mood already has a track in this pack");
            }
            String license = entry.choice("license", TRACK_LICENSES.keySet(), null);
            tracks.add(new Track(id, mood, file, credit(entry, "title"), credit(entry, "creator"), license,
                    credit(entry, "source"), credit(entry, "notices"), credit(entry, "modifications")));
            entry.close();
        }
        music.close();
        return new Music(moods, tracks);
    }

    /** Required credit text: 1 to {@link #MAX_CREDIT} characters without control characters. */
    private static String credit(Fields entry, String key) {
        return entry.matching(key, CREDIT, true,
                "Expected a credit text of 1 to " + MAX_CREDIT + " characters without control characters");
    }

    private static Map<String, String> trackLicenses() {
        var licenses = new HashMap<>(MusicCatalog.LICENSES);
        licenses.put("MIT", "https://opensource.org/license/mit");
        return Map.copyOf(licenses);
    }

    /**
     * Read the optional {@code artwork} bindings: each names an image category, the subject it depicts, a listed
     * manifest file of category {@code asset} and a credit line. An item or portrait subject must be a local
     * definition of that kind and a scene subject a declared scene; map and ui subjects are free references.
     */
    private static List<Artwork> artwork(ParsedPack pack, Fields root, Map<String, Definition> definitions,
            Set<String> scenes) {
        var listed = new HashSet<String>();
        pack.manifest().files().forEach(file -> {
            if (file.category().equals("asset")) {
                listed.add(file.path());
            }
        });
        var bound = new HashSet<String>();
        var result = new java.util.ArrayList<Artwork>();
        for (Fields entry : root.objects("artwork", 0, MAX)) {
            String category = entry.choice("category", ARTWORK_CATEGORIES, null);
            String subject = entry.matching("subject", PresentationEvent.REFERENCE, true, EXPECTED);
            String file = entry.text("file", true);
            String credit = entry.matching("credit", CREDIT, true,
                    "Expected a credit line of 1 to " + MAX_CREDIT + " characters without control characters");
            if (!listed.contains(file)) {
                throw entry.fail("FILE_REFERENCE", "file", "Expected a manifest file of category asset");
            }
            String kind = category.equals("item") ? "item" : category.equals("portrait") ? "npc" : null;
            if (kind != null) {
                Definition definition = definitions.get(subject);
                if (definition == null || !definition.kind().equals(kind)) {
                    throw entry.fail("REFERENCE_UNKNOWN", "subject", "Expected the ID of a " + kind + " defined by this pack");
                }
            } else if (category.equals("scene") && !scenes.contains(subject)) {
                throw entry.fail("REFERENCE_UNKNOWN", "subject", "Expected a scene declared by this file");
            }
            if (!bound.add(category + '\u0000' + subject)) {
                throw entry.fail("DUPLICATE", "subject", "Subject already has artwork in this category");
            }
            result.add(new Artwork(category, subject, file, credit));
            entry.close();
        }
        return result;
    }

    /** A required reference no earlier entry of the same collection used. */
    private static String unique(Fields entry, String key, Set<String> seen) {
        String id = entry.matching(key, PresentationEvent.REFERENCE, true, EXPECTED);
        if (!seen.add(id)) {
            throw entry.fail("DUPLICATE", key, "Reference already declared");
        }
        return id;
    }

    /** An optional reference that must also be one of the declared assets. */
    private static String asset(Fields entry, String key, Set<String> declared) {
        String value = entry.matching(key, PresentationEvent.REFERENCE, false, EXPECTED);
        if (value != null && !declared.contains(value)) {
            throw entry.fail("REFERENCE_UNKNOWN", key, "Asset is not listed in the declaration's assets");
        }
        return value;
    }
}
