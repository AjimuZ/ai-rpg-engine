package org.rpgengine.content;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.rpgengine.presentation.PublicPresentationScope;

/**
 * Builds the {@link PublicPresentationScope} of one audience from the actors that audience may see and
 * the pack's declared {@link PackPresentation} (P05.7-B.1.2.2).
 *
 * <p>Always holds: the builder is pure and keeps no visibility model. The caller passes the actors the
 * audience-filtered projection already exposes (the same set narration may let speak); an actor that is
 * not passed is hidden or unknown, and an actor whose definition the pack does not declare is left out
 * the same way, so the scope never confirms that a hidden actor exists. Scenes, lines and assets are
 * copied from the declaration unchanged, and poses and expressions come only from the actor's own
 * declaration.
 */
public final class PublicPresentationScopeBuilder {
    private PublicPresentationScopeBuilder() {
    }

    /**
     * One actor the audience-filtered projection exposes.
     *
     * @param id the actor's canonical ID
     * @param definitionId the pack NPC definition ID the actor was created from, or null when it has none
     */
    public record VisibleActor(UUID id, String definitionId) {
        /** @throws NullPointerException when the actor ID is null */
        public VisibleActor {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * Build the scope for one audience.
     *
     * @param visibleActors actors from the audience-filtered projection; duplicates by ID collapse
     * @param declared the admitted pack's declared references; {@link PackPresentation#NONE} gives an
     *        empty scope
     * @return the immutable scope
     * @throws IllegalArgumentException when more than {@link PublicPresentationScope#MAX_ENTRIES}
     *         declared actors would be visible; the scope is never silently truncated
     */
    public static PublicPresentationScope build(List<VisibleActor> visibleActors, PackPresentation declared) {
        Objects.requireNonNull(visibleActors, "visibleActors");
        Objects.requireNonNull(declared, "declared");
        var actors = new HashMap<UUID, PublicPresentationScope.ActorLooks>();
        for (VisibleActor actor : visibleActors) {
            PackPresentation.Looks looks = actor.definitionId() == null ? null
                    : declared.actors().get(actor.definitionId());
            if (looks != null) {
                actors.put(actor.id(), new PublicPresentationScope.ActorLooks(looks.poses(), looks.expressions()));
            }
        }
        return new PublicPresentationScope(declared.scenes(), actors, declared.lines(), declared.assets());
    }
}
