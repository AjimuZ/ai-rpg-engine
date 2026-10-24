package org.rpgengine.content;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiled generation policy of a pack (docs/09 sections 8 and 11): per-category generation modes
 * and budgets, plus the fixed author constraints that generated content can never override.
 *
 * <p>The compiler builds this value after validating every reference, so all template, required
 * and fixed entity IDs name authored definitions. A category absent from the file is
 * {@link Mode#DISABLED}: generation is opt-in per category, never implied.
 *
 * @param categories category name (e.g. {@code npcs}) to its policy; absent categories are disabled
 * @param required authored definition IDs that must keep existing in every campaign
 * @param fixed authored definition ID to the authored field names generation may not change or fill
 * @param excludedTags tags generated content may never carry
 */
public record GenerationPolicy(Map<String, Category> categories, Set<String> required,
        Map<String, Set<String>> fixed, Set<String> excludedTags) {
    /** Categories whose content generation can be configured. */
    public static final Set<String> CATEGORIES = Set.of("locations", "factions", "npcs", "items", "abilities",
            "conditions", "quests", "encounters");

    /** Policy of a pack whose generation file is {@code {}}: everything disabled, nothing fixed. */
    public static final GenerationPolicy EMPTY = new GenerationPolicy(Map.of(), Set.of(), Map.of(), Set.of());

    /** Copy every collection so the compiled policy is deeply immutable. */
    public GenerationPolicy {
        categories = Map.copyOf(categories);
        required = Set.copyOf(required);
        fixed = Map.copyOf(fixed);
        fixed.values().forEach(Objects::requireNonNull);
        excludedTags = Set.copyOf(excludedTags);
    }

    /** How a category may grow at runtime. */
    public enum Mode {
        /** Nothing new may be created and authored entries may not be gap-filled. */
        DISABLED,
        /** Only authored catalog entries may be selected; nothing new is created. */
        CATALOG_ONLY,
        /** New entries may be created within the category's tags, templates, ranges and budgets. */
        BOUNDED
    }

    /** Inclusive integer range a generated numeric property must respect. */
    public record Range(long min, long max) {
        /** Reject inverted ranges. */
        public Range {
            if (max < min) {
                throw new IllegalArgumentException("Range maximum is below its minimum");
            }
        }

        /** True when {@code value} lies inside the range. */
        public boolean contains(long value) {
            return value >= min && value <= max;
        }
    }

    /**
     * Policy of one category.
     *
     * @param mode generation mode
     * @param maxNewPerScene new entries allowed per scene (0 unless bounded)
     * @param maxNewPerCampaign new entries allowed per campaign (0 unless bounded)
     * @param tags tags a generated entry may carry
     * @param templates authored definitions a generated entry may instantiate; empty means none required
     * @param ranges numeric property name to its permitted range; unlisted properties are rejected
     */
    public record Category(Mode mode, int maxNewPerScene, int maxNewPerCampaign, Set<String> tags,
            Set<String> templates, Map<String, Range> ranges) {
        /** Disabled category used for absent entries. */
        public static final Category DISABLED = new Category(Mode.DISABLED, 0, 0, Set.of(), Set.of(), Map.of());

        /** Copy collections for immutability. */
        public Category {
            Objects.requireNonNull(mode, "mode");
            tags = Set.copyOf(tags);
            templates = Set.copyOf(templates);
            ranges = Map.copyOf(ranges);
        }
    }

    /** Policy of a category name; unknown and absent categories are disabled. */
    public Category category(String name) {
        return categories.getOrDefault(name, Category.DISABLED);
    }
}
