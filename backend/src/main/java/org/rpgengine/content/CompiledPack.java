package org.rpgengine.content;

import java.util.List;
import java.util.Map;

/**
 * Pack whose definitions, dependencies, cross-references, bounded expressions/effects, relation
 * graphs, version compatibility and generation policy were semantically validated (P04.2, P04.3).
 * It is still not an admitted ContentRevision: path/archive protections and immutable registration
 * (P04.4) are outstanding.
 *
 * @param parsed parser output the compilation read
 * @param definitions definitions in manifest file order
 * @param byId definitions keyed by local ID
 * @param generationPolicy compiled generation modes, budgets and fixed author constraints
 * @param warnings non-blocking findings, kept distinct from guaranteed checks
 */
public record CompiledPack(ParsedPack parsed, List<Definition> definitions, Map<String, Definition> byId,
        GenerationPolicy generationPolicy, List<PackWarning> warnings) {
    /** Copy the indexes so callers cannot mutate the validated result. */
    public CompiledPack {
        java.util.Objects.requireNonNull(parsed, "parsed");
        java.util.Objects.requireNonNull(generationPolicy, "generationPolicy");
        definitions = List.copyOf(definitions);
        byId = Map.copyOf(byId);
        warnings = List.copyOf(warnings);
    }
}
