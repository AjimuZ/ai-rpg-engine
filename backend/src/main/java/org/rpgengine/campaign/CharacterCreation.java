package org.rpgengine.campaign;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.rpgengine.content.CompiledPack;
import org.rpgengine.content.Definition;

/**
 * Pure creation resolution from a compiled, sealed pack and bounded player choices.
 * Returns only validated initial values; unknown IDs, unavailable dependency mechanics,
 * invalid budgets, caps or loadouts fail before persistence. It performs no I/O or draws.
 */
public final class CharacterCreation {
    /** Stateless utility; all inputs are supplied to resolve. */
    private CharacterCreation() {
    }

    /**
     * Resolve the manifest's creation profile and location, filling omitted attributes at
     * their minima and skills at zero. Presets spend the same budgets as custom choices.
     * Unspent points are allowed; attribute cost is value minus the declared minimum.
     * Starting grants come exclusively from the profile, never from player payload fields.
     */
    public static InitialCharacter resolve(CompiledPack pack, CharacterChoices choices) {
        var entry = object(pack.parsed().manifest().values().get("entrypoint"));
        String profileId = (String) entry.get("creationProfile");
        String location = (String) entry.get("location");
        requireDefinition(pack, location, "location");
        var profile = requireDefinition(pack, profileId, "creation").data();
        Map<String, Integer> selectedAttributes = choices.attributes();
        Map<String, Integer> selectedSkills = choices.skills();
        if (choices.archetypeId() != null) {
            var preset = objects(profile, "archetypes").stream()
                    .filter(value -> choices.archetypeId().equals(value.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown archetype"));
            selectedAttributes = integers(preset.getOrDefault("attributes", Map.of()));
            selectedSkills = integers(preset.getOrDefault("skills", Map.of()));
        }
        requireOption(profile, "backgrounds", choices.background());
        requireOption(profile, "roles", choices.role());
        List<String> allowedAttributes = strings(profile, "attributes");
        List<String> allowedSkills = strings(profile, "skills");
        if (!allowedAttributes.containsAll(selectedAttributes.keySet())
                || !allowedSkills.containsAll(selectedSkills.keySet())) {
            throw new IllegalArgumentException("allocation is not allowed by the creation profile");
        }
        Map<String, Map<String, Object>> ranges = new TreeMap<>();
        Map<String, Integer> resources = new TreeMap<>();
        for (Definition definition : pack.definitions()) {
            if (definition.kind().equals("rule")) {
                var data = definition.data();
                if ("attributes".equals(data.get("ruleType"))) {
                    for (var range : objects(data, "attributes")) {
                        ranges.put((String) range.get("id"), range);
                    }
                } else if ("resources".equals(data.get("ruleType"))) {
                    for (var resource : objects(data, "resources")) {
                        resources.put((String) resource.get("id"), number(resource, "max", 0));
                    }
                }
            }
        }
        Map<String, Integer> attributes = new TreeMap<>();
        int attributeCost = 0;
        for (String id : allowedAttributes) {
            var range = ranges.get(id);
            if (range == null) {
                throw new IllegalArgumentException("attribute has no local rule definition");
            }
            int minimum = number(range, "min", 0);
            int value = selectedAttributes.getOrDefault(id, minimum);
            if (value < minimum || value > number(range, "max", 0)) {
                throw new IllegalArgumentException("attribute is outside its declared bounds");
            }
            attributes.put(id, value);
            attributeCost = Math.addExact(attributeCost, value - minimum);
        }
        Map<String, Integer> skills = new TreeMap<>();
        int skillCost = 0;
        for (String id : allowedSkills) {
            var skill = requireDefinition(pack, id, "skill");
            int rank = selectedSkills.getOrDefault(id, 0);
            if (rank < 0 || rank > number(skill.data(), "maxRank", 2)) {
                throw new IllegalArgumentException("skill is outside its declared bounds");
            }
            skills.put(id, rank);
            skillCost = Math.addExact(skillCost, rank);
        }
        if (attributeCost > number(profile, "attributeBudget", 0)
                || skillCost > number(profile, "skillBudget", 0)) {
            throw new IllegalArgumentException("character exceeds its creation budget");
        }
        List<StartingItem> items = new ArrayList<>();
        Map<String, Integer> quantities = new TreeMap<>();
        for (var grant : objects(profile, "startingItems")) {
            String id = (String) grant.get("item");
            var item = requireDefinition(pack, id, "item");
            int quantity = number(grant, "quantity", 1);
            int maximum = number(item.data(), "maxStack", 1);
            // Repeated grants cannot bypass a stack limit by becoming indistinguishable stacks.
            int total = quantities.merge(id, quantity, Math::addExact);
            if (quantity < 1 || total > maximum) {
                throw new IllegalArgumentException("starting loadout exceeds its stack limit");
            }
        }
        quantities.forEach((id, quantity) -> items.add(new StartingItem(id, quantity,
                number(requireDefinition(pack, id, "item").data(), "maxStack", 1))));
        List<String> abilities = strings(profile, "startingAbilities");
        for (String id : abilities) {
            requireDefinition(pack, id, "ability");
        }
        // Explicit authored grants do not run action predicates or spend resources during creation.
        return new InitialCharacter(profileId, location, attributes, skills, resources, items, abilities);
    }

    /** Return a local definition of the required kind; external mechanics fail closed in this checkpoint. */
    private static Definition requireDefinition(CompiledPack pack, String id, String kind) {
        Definition definition = pack.byId().get(id);
        if (definition == null || !definition.kind().equals(kind)) {
            throw new IllegalArgumentException("creation requires a local " + kind + " definition: " + id);
        }
        return definition;
    }

    /** Require an optional choice to match an authored label exactly; absence is allowed. */
    private static void requireOption(Map<String, Object> profile, String key, String choice) {
        if (choice != null && !strings(profile, key).contains(choice)) {
            throw new IllegalArgumentException("unknown creation " + key);
        }
    }

    /** Read an optional compiled integer field, using the same default as the pack compiler. */
    private static int number(Map<String, Object> object, String key, int fallback) {
        return object.get(key) instanceof Number value ? value.intValue() : fallback;
    }

    /** View an immutable parser object; only semantically compiled values reach this helper. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    /** Read an optional compiled object array, preserving author order and returning an empty default. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Map<String, Object> value, String key) {
        return (List<Map<String, Object>>) value.getOrDefault(key, List.of());
    }

    /** Read an optional compiled ID/label array; parser/compiler already enforce element types and bounds. */
    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> value, String key) {
        return (List<String>) value.getOrDefault(key, List.of());
    }

    /** Convert exact compiled integer allocations into Java integers; values are compiler-bounded. */
    private static Map<String, Integer> integers(Object value) {
        Map<String, Integer> result = new TreeMap<>();
        object(value).forEach((key, number) -> result.put(key, ((Number) number).intValue()));
        return result;
    }

    /** One engine-resolved item stack, with a pinned stack bound; no equipment is implicitly assigned. */
    public record StartingItem(String definitionId, int quantity, int maximumStack) {
    }

    /** Immutable resolution output for trusted persistence, not a public projection or a live state patch. */
    public record InitialCharacter(String profileId, String locationId, Map<String, Integer> attributes,
            Map<String, Integer> skills, Map<String, Integer> resources, List<StartingItem> items,
            List<String> abilities) {
        /** Copy every collection; callers cannot change the resolved values before insertion. */
        public InitialCharacter {
            attributes = Map.copyOf(attributes);
            skills = Map.copyOf(skills);
            resources = Map.copyOf(resources);
            items = List.copyOf(items);
            abilities = List.copyOf(abilities);
        }
    }
}
