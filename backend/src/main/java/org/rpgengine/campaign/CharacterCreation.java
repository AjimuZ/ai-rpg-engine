package org.rpgengine.campaign;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.rpgengine.content.CompiledPack;
import org.rpgengine.content.CampaignPackSnapshot;
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
        return resolve(CampaignPackSnapshot.local(pack), choices);
    }

    /**
     * Resolve a registry-verified closure. References inside a dependency profile use that pack's
     * namespace and rules; persisted skill, location and grant IDs are canonical root-relative
     * references. Invalid choices or missing pinned mechanics fail before any persistence.
     */
    public static InitialCharacter resolve(CampaignPackSnapshot snapshot, CharacterChoices choices) {
        var entry = object(snapshot.root().parsed().manifest().values().get("entrypoint"));
        var creation = snapshot.resolve(snapshot.root(), (String) entry.get("creationProfile"), "creation");
        String profileId = creation.id();
        String location = snapshot.resolve(snapshot.root(), (String) entry.get("location"), "location").id();
        CompiledPack source = creation.source();
        var profile = creation.definition().data();
        Map<String, Integer> selectedAttributes = choices.attributes();
        Map<String, Integer> selectedSkills = choices.skills();
        if (choices.archetypeId() != null) {
            var preset = objects(profile, "archetypes").stream()
                    .filter(value -> choices.archetypeId().equals(value.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown archetype"));
            selectedAttributes = integers(preset.getOrDefault("attributes", Map.of()));
            selectedSkills = canonicalSkills(snapshot, source, integers(preset.getOrDefault("skills", Map.of())));
        }
        requireOption(profile, "backgrounds", choices.background());
        requireOption(profile, "roles", choices.role());
        List<String> allowedAttributes = strings(profile, "attributes");
        Map<String, Definition> allowedSkills = new TreeMap<>();
        for (String reference : strings(profile, "skills")) {
            var resolved = snapshot.resolve(source, reference, "skill");
            allowedSkills.put(resolved.id(), resolved.definition());
        }
        if (!allowedAttributes.containsAll(selectedAttributes.keySet())
                || !allowedSkills.keySet().containsAll(selectedSkills.keySet())) {
            throw new IllegalArgumentException("allocation is not allowed by the creation profile");
        }
        Map<String, Map<String, Object>> ranges = new TreeMap<>();
        Map<String, Integer> resources = new TreeMap<>();
        // Attribute/resource vocabularies belong to the profile's declaring pack. Merging all
        // dependency rules would silently overwrite equal keys from independent authored worlds.
        for (Definition definition : source.definitions()) {
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
                throw new IllegalArgumentException("attribute has no profile rule definition");
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
        for (var entrySkill : allowedSkills.entrySet()) {
            String id = entrySkill.getKey();
            var skill = entrySkill.getValue();
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
        Map<String, Integer> maxima = new TreeMap<>();
        for (var grant : objects(profile, "startingItems")) {
            var resolved = snapshot.resolve(source, (String) grant.get("item"), "item");
            String id = resolved.id();
            var item = resolved.definition();
            int quantity = number(grant, "quantity", 1);
            int maximum = number(item.data(), "maxStack", 1);
            // Repeated grants cannot bypass a stack limit by becoming indistinguishable stacks.
            int total = quantities.merge(id, quantity, Math::addExact);
            if (quantity < 1 || total > maximum) {
                throw new IllegalArgumentException("starting loadout exceeds its stack limit");
            }
            maxima.put(id, maximum);
        }
        quantities.forEach((id, quantity) -> items.add(new StartingItem(id, quantity, maxima.get(id))));
        List<String> abilities = strings(profile, "startingAbilities").stream()
                .map(id -> snapshot.resolve(source, id, "ability").id()).toList();
        // Explicit authored grants do not run action predicates or spend resources during creation.
        return new InitialCharacter(profileId, location, attributes, skills, resources, items, abilities);
    }

    /** Qualify authored preset skill keys, preserving their ranks and rejecting unresolved references. */
    private static Map<String, Integer> canonicalSkills(CampaignPackSnapshot snapshot, CompiledPack source,
            Map<String, Integer> skills) {
        Map<String, Integer> result = new TreeMap<>();
        skills.forEach((id, rank) -> result.put(snapshot.resolve(source, id, "skill").id(), rank));
        return result;
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
