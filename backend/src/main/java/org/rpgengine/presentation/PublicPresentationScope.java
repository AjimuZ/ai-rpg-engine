package org.rpgengine.presentation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The allowlist one audience's presentation events are checked against: the public scenes, lines
 * and assets plus the actors visible to that audience with the poses and expressions they may take.
 * The caller builds it from the audience-filtered projection and the admitted pack's declared
 * presentation references (the same visibility that decides who may speak in narration); this
 * package keeps no second visibility model. An actor that is absent is hidden or unknown, and the
 * two are deliberately indistinguishable so a rejection cannot confirm that a hidden actor exists.
 *
 * <p>Always holds: every reference is syntactically a {@link PresentationEvent#REFERENCE}, so a
 * pack cannot declare a URL or script as an identifier, and every collection is bounded and immutable.
 *
 * @param scenes public scene references
 * @param actors visible actors and what each may show
 * @param lines public line references
 * @param assets admitted asset references (backdrops and line audio)
 */
public record PublicPresentationScope(Set<String> scenes, Map<UUID, ActorLooks> actors, Set<String> lines,
        Set<String> assets) {
    /** Most entries per collection. */
    public static final int MAX_ENTRIES = 256;

    /**
     * Poses and expressions one visible actor may take.
     *
     * @param poses allowed pose references
     * @param expressions allowed expression references
     */
    public record ActorLooks(Set<String> poses, Set<String> expressions) {
        /** @throws IllegalArgumentException for an unbounded collection or a malformed reference */
        public ActorLooks {
            poses = references(poses, "poses");
            expressions = references(expressions, "expressions");
        }
    }

    /** @throws IllegalArgumentException for an unbounded collection or a malformed reference */
    public PublicPresentationScope {
        scenes = references(scenes, "scenes");
        lines = references(lines, "lines");
        assets = references(assets, "assets");
        Objects.requireNonNull(actors, "actors");
        if (actors.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("actors must contain at most " + MAX_ENTRIES + " entries");
        }
        var copy = new HashMap<UUID, ActorLooks>();
        actors.forEach((id, looks) -> copy.put(Objects.requireNonNull(id, "actor id"),
                Objects.requireNonNull(looks, "actor looks")));
        actors = Map.copyOf(copy);
    }

    private static Set<String> references(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(name + " must contain at most " + MAX_ENTRIES + " entries");
        }
        var copy = new HashSet<String>();
        for (String value : values) {
            if (value == null || !PresentationEvent.REFERENCE.matcher(value).matches()) {
                throw new IllegalArgumentException(name + " must hold stable content references");
            }
            copy.add(value);
        }
        return Set.copyOf(copy);
    }
}
