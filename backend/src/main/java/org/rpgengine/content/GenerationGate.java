package org.rpgengine.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic admission check for generated content against a compiled pack's authored facts
 * and {@link GenerationPolicy} (docs/09 sections 8 and 11). Generated gap filling can propose
 * entities and values, but this gate guarantees it cannot override authored content: it cannot
 * reuse an authored ID, fill a fixed or already authored field, exceed budgets, leave declared
 * tags/templates/ranges, carry excluded tags or retire a required entity.
 *
 * <p>The gate is pure: it reads only the immutable compiled pack and the caller's proposal, keeps
 * no state, and reports every violation at once so a caller can explain the full rejection.
 * Runtime callers (scene generation, P10/P12) must persist a proposal only when
 * {@link Decision#accepted()} is true.
 */
public final class GenerationGate {
    private final CompiledPack pack;
    private final GenerationPolicy policy;

    /** Bind the gate to one compiled pack and its policy. */
    public GenerationGate(CompiledPack pack) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.policy = pack.generationPolicy();
    }

    /** One reason a proposal was rejected. */
    public record Violation(String code, String message) {
    }

    /** Gate outcome; accepted exactly when no violation was found. */
    public record Decision(List<Violation> violations) {
        /** Copy the violations for immutability. */
        public Decision {
            violations = List.copyOf(violations);
        }

        /** True when the proposal may be persisted. */
        public boolean accepted() {
            return violations.isEmpty();
        }
    }

    /**
     * A proposed generated entity.
     *
     * @param category content category, e.g. {@code npcs}
     * @param id proposed stable ID
     * @param tags tags the entity carries
     * @param template authored definition it instantiates, or null
     * @param properties numeric properties that must fall inside the category's ranges
     * @param references definitions the entity refers to; each must be authored in this pack
     */
    public record NewEntity(String category, String id, Set<String> tags, String template,
            Map<String, Long> properties, Set<String> references) {
        /** Copy collections and require identity fields. */
        public NewEntity {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(id, "id");
            tags = Set.copyOf(tags);
            properties = Map.copyOf(properties);
            references = Set.copyOf(references);
        }
    }

    /**
     * Check a proposed new entity.
     *
     * @param entity proposal
     * @param newInScene entities of this category already generated in the current scene
     * @param newInCampaign entities of this category already generated in the campaign
     * @return every violation found; a disabled or catalog-only category short-circuits
     */
    public Decision checkNew(NewEntity entity, int newInScene, int newInCampaign) {
        List<Violation> violations = new ArrayList<>();
        GenerationPolicy.Category category = policy.category(entity.category());
        if (!mode(entity.category(), category, violations)) {
            return new Decision(violations);
        }
        if (pack.byId().containsKey(entity.id())) {
            violations.add(new Violation("AUTHORED_ID", "ID '" + entity.id() + "' belongs to authored content"));
        }
        if (newInScene >= category.maxNewPerScene() || newInCampaign >= category.maxNewPerCampaign()) {
            violations.add(new Violation("BUDGET", "Budget exhausted: " + category.maxNewPerScene() + " per scene, "
                    + category.maxNewPerCampaign() + " per campaign"));
        }
        for (String tag : new TreeSet<>(entity.tags())) {
            if (policy.excludedTags().contains(tag)) {
                violations.add(new Violation("EXCLUDED_TAG", "Tag '" + tag + "' is excluded by the author"));
            } else if (!category.tags().contains(tag)) {
                violations.add(new Violation("TAG", "Tag '" + tag + "' is not allowed for " + entity.category()));
            }
        }
        boolean templateMissing = entity.template() == null && !category.templates().isEmpty();
        if (templateMissing || entity.template() != null && !category.templates().contains(entity.template())) {
            violations.add(new Violation("TEMPLATE", "Template must be one of " + new TreeSet<>(category.templates())));
        }
        for (var property : new TreeSet<>(entity.properties().keySet())) {
            GenerationPolicy.Range range = category.ranges().get(property);
            long value = entity.properties().get(property);
            if (range == null || !range.contains(value)) {
                violations.add(new Violation("RANGE", "Property '" + property + "' value " + value
                        + (range == null ? " has no authored range" : " is outside " + range.min() + ".." + range.max())));
            }
        }
        for (String reference : new TreeSet<>(entity.references())) {
            if (!pack.byId().containsKey(reference)) {
                violations.add(new Violation("REFERENCE_UNKNOWN", "Reference '" + reference + "' is not authored content"));
            }
        }
        return new Decision(violations);
    }

    /**
     * Check a proposal to fill a missing field of an authored definition.
     *
     * @param entity authored definition ID
     * @param field top-level data field to fill
     * @return violations: unknown entity, non-bounded category, fixed field or already authored value
     */
    public Decision checkFill(String entity, String field) {
        List<Violation> violations = new ArrayList<>();
        Definition definition = pack.byId().get(entity);
        if (definition == null) {
            violations.add(new Violation("REFERENCE_UNKNOWN", "'" + entity + "' is not authored content"));
            return new Decision(violations);
        }
        String categoryName = PackCompiler.categoryOf(definition.kind());
        mode(categoryName, policy.category(categoryName), violations);
        if (policy.fixed().getOrDefault(entity, Set.of()).contains(field)) {
            violations.add(new Violation("FIXED_FIELD", "Field '" + field + "' of '" + entity + "' is fixed by the author"));
        }
        // Gap filling may only add what the author left open; it never replaces an authored value.
        if (definition.data().containsKey(field)) {
            violations.add(new Violation("AUTHORED_VALUE", "Field '" + field + "' of '" + entity + "' is already authored"));
        }
        return new Decision(violations);
    }

    /**
     * Check whether generated play may retire (remove, kill, destroy) an authored definition.
     *
     * @param entity authored definition ID
     * @return violations: unknown entity or required entity
     */
    public Decision checkRetire(String entity) {
        if (!pack.byId().containsKey(entity)) {
            return new Decision(List.of(new Violation("REFERENCE_UNKNOWN", "'" + entity + "' is not authored content")));
        }
        if (policy.required().contains(entity)) {
            return new Decision(List.of(new Violation("REQUIRED_ENTITY", "'" + entity + "' is required by the author")));
        }
        return new Decision(List.of());
    }

    /** Add the mode violation of a non-bounded category; returns true when the category is bounded. */
    private static boolean mode(String name, GenerationPolicy.Category category, List<Violation> violations) {
        switch (category.mode()) {
            case BOUNDED -> {
                return true;
            }
            case CATALOG_ONLY -> violations.add(new Violation("GENERATION_CATALOG_ONLY",
                    "Category " + name + " only selects authored catalog entries"));
            default -> violations.add(new Violation("GENERATION_DISABLED", "Generation is disabled for " + name));
        }
        return false;
    }
}
