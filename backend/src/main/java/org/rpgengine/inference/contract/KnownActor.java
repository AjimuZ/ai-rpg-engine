package org.rpgengine.inference.contract;

import java.util.Objects;
import java.util.UUID;

/**
 * An actor the task's audience currently knows, with the display name it knows them by. Display
 * names are context for the model only; published speaker names and portraits are resolved from
 * the authorized projection, never from model output (docs/21 section 8).
 *
 * @param actorId canonical actor identity
 * @param name known display name, at most {@link #MAX_NAME} characters
 */
public record KnownActor(UUID actorId, String name) {
    /** Longest display name. */
    public static final int MAX_NAME = 120;

    /** @throws IllegalArgumentException for a missing identity or unbounded name */
    public KnownActor {
        Objects.requireNonNull(actorId, "actorId");
        ContextValues.text(name, "actor name", MAX_NAME);
    }
}
