package org.rpgengine.content;

import java.util.List;
import java.util.Map;

/**
 * Pack whose definitions, dependencies, cross-references, bounded expressions/effects, relation
 * graphs, compatibility, generation and localization policies were semantically validated.
 * This is internal content, including secret text; consumers must authorize source fields before
 * localization. Only protected registry admission can create an immutable ContentRevision.
 *
 * @param parsed parser output the compilation read
 * @param definitions definitions in manifest file order
 * @param byId definitions keyed by local ID
 * @param generationPolicy compiled generation modes, budgets and fixed author constraints
 * @param localization immutable text catalog with source fallback, policies and locale coverage
 * @param presentation public scene, line and asset references and per-actor poses and expressions the
 *        pack declares; {@link PackPresentation#NONE} when it declares none
 * @param warnings non-blocking findings, kept distinct from guaranteed checks
 */
public record CompiledPack(ParsedPack parsed, List<Definition> definitions, Map<String, Definition> byId,
        GenerationPolicy generationPolicy, PackLocalization localization, PackPresentation presentation,
        List<PackWarning> warnings) {
    /** Copy the indexes so callers cannot mutate the validated result. */
    public CompiledPack {
        java.util.Objects.requireNonNull(parsed, "parsed");
        java.util.Objects.requireNonNull(generationPolicy, "generationPolicy");
        java.util.Objects.requireNonNull(localization, "localization");
        java.util.Objects.requireNonNull(presentation, "presentation");
        definitions = List.copyOf(definitions);
        byId = Map.copyOf(byId);
        warnings = List.copyOf(warnings);
    }
}
