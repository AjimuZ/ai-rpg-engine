package org.rpgengine.content;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 * asset and is refused.
 *
 * @param scenes declared scene references
 * @param lines declared line references
 * @param assets declared asset references; the only values a backdrop or audio may name
 * @param sceneBackdrops scene reference to the asset shown behind it, for scenes that declare one
 * @param lineAudio line reference to its audio asset, for lines that declare one
 * @param actors local NPC definition ID to the poses and expressions it may take
 */
public record PackPresentation(Set<String> scenes, Set<String> lines, Set<String> assets,
        Map<String, String> sceneBackdrops, Map<String, String> lineAudio, Map<String, Looks> actors) {
    /** The declaration of a pack that makes none: nothing may be presented. */
    public static final PackPresentation NONE = new PackPresentation(Set.of(), Set.of(), Set.of(), Map.of(),
            Map.of(), Map.of());
    private static final int MAX = PublicPresentationScope.MAX_ENTRIES;
    private static final String EXPECTED = "Expected a stable reference of 1 to " + PresentationEvent.MAX_REFERENCE
            + " letters, digits, '.', '_' or '-', starting with a letter or digit";

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
        root.close();
        return new PackPresentation(scenes, lines, declaredAssets, backdrops, audio, actors);
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
