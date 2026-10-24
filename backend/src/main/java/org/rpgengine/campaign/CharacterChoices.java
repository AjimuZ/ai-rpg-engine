package org.rpgengine.campaign;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.rpgengine.persistence.CanonicalValues;

/**
 * Bounded player input for one character. Archetypes and custom allocations are mutually
 * exclusive; the pack validator checks IDs, minima, caps and budgets. No item, resource or
 * ability override is accepted. Construction copies maps and rejects malformed text/values.
 */
public record CharacterChoices(String name, String archetypeId, Map<String, Integer> attributes,
        Map<String, Integer> skills, String background, String role) {
    /** Copy bounded allocations, reject nulls/negative values and ambiguous preset overrides. */
    public CharacterChoices {
        name = CanonicalValues.requireText(name, "name", 120);
        archetypeId = CanonicalValues.optionalText(archetypeId, "archetypeId", 256);
        background = CanonicalValues.optionalText(background, "background", 200);
        role = CanonicalValues.optionalText(role, "role", 200);
        attributes = allocation(attributes, 16, 100);
        skills = allocation(skills, 64, 10);
        if (archetypeId != null && (!attributes.isEmpty() || !skills.isEmpty())) {
            throw new IllegalArgumentException("archetype cannot override custom allocations");
        }
    }

    /** Return a sorted immutable input map; null maps/entries, oversized maps and bad values fail. */
    private static Map<String, Integer> allocation(Map<String, Integer> input, int limit, int maximum) {
        java.util.Objects.requireNonNull(input, "allocation");
        if (input.size() > limit) {
            throw new IllegalArgumentException("too many allocation entries");
        }
        Map<String, Integer> copy = new TreeMap<>();
        input.forEach((key, value) -> {
            CanonicalValues.requireText(key, "allocation key", 256);
            if (value == null || value < 0 || value > maximum) {
                throw new IllegalArgumentException("invalid allocation value");
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }
}
