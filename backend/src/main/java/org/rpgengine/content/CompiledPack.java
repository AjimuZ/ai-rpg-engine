package org.rpgengine.content;

import java.util.List;
import java.util.Map;

/**
 * Pack whose definitions, dependencies and cross-references were semantically validated.
 * It is still not an admitted ContentRevision: expressions/effects (P04.3), path/archive
 * protections and immutable registration (P04.4) are outstanding.
 */
public record CompiledPack(ParsedPack parsed, List<Definition> definitions, Map<String, Definition> byId) {
    /** Copy the indexes so callers cannot mutate the validated result. */
    public CompiledPack {
        java.util.Objects.requireNonNull(parsed, "parsed");
        definitions = List.copyOf(definitions);
        byId = Map.copyOf(byId);
    }
}
