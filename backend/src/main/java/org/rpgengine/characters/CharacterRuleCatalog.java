package org.rpgengine.characters;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rpgengine.content.CampaignPackSnapshot;
import org.rpgengine.content.CompiledPack;
import org.rpgengine.content.Definition;
import org.rpgengine.rules.CharacterSheet;
import org.rpgengine.rules.ConditionRules;
import org.rpgengine.rules.EquipmentRules;
import org.rpgengine.rules.StatFormula;

/**
 * Immutable projection of the pinned rule facts that character state depends on: per-pack
 * attribute, derived-statistic and equipment vocabularies, skills, abilities, conditions and
 * item rules including the explicit rarity tier. It is built only from a sealed, compiled
 * {@link CampaignPackSnapshot}, keeps no prose, art or localized text, and is valid for the whole
 * life of a campaign's pinned content revision.
 *
 * <p>Identities follow campaign creation: definitions of the root pack keep their local ID and
 * dependency definitions are {@code pack#id}. Vocabulary keys (attributes, resources, derived
 * statistics, equipment slots) stay unqualified and belong to the pack that declares the actor's
 * creation profile, exactly like resource keys; an item or condition from another pack therefore
 * uses an actor's vocabulary only where a key matches by name, and an item naming a slot the
 * actor's vocabulary lacks is reported as not wearable instead of being guessed.
 *
 * <p>Nothing here reads artwork. An item's {@link Item#rarity()} is the tier ID the pack
 * declared, copied verbatim; appearance identities are not part of the catalog.
 *
 * @param vocabularies per declaring pack ID
 * @param profiles creation profile canonical ID to the pack ID whose vocabulary its actors use
 * @param items item rules by canonical ID
 * @param skills skill rules by canonical ID
 * @param abilities ability prerequisites by canonical ID
 * @param conditions condition definitions by canonical ID
 */
public record CharacterRuleCatalog(Map<String, Vocabulary> vocabularies, Map<String, String> profiles,
        Map<String, Item> items, Map<String, Skill> skills, Map<String, Ability> abilities,
        Map<String, ConditionRules.Definition> conditions) {
    /** Freeze every index. */
    public CharacterRuleCatalog {
        vocabularies = Map.copyOf(vocabularies);
        profiles = Map.copyOf(profiles);
        items = Map.copyOf(items);
        skills = Map.copyOf(skills);
        abilities = Map.copyOf(abilities);
        conditions = Map.copyOf(conditions);
    }

    /**
     * One pack's actor vocabulary.
     *
     * @param attributes attribute ranges by key
     * @param derived derived statistics in authored order
     * @param hands hands every actor of the vocabulary has
     * @param slots equipment slots by key; empty when the pack declares no equipment rule
     */
    public record Vocabulary(Map<String, CharacterSheet.Range> attributes, List<CharacterSheet.Derived> derived,
            int hands, Map<String, EquipmentRules.Slot> slots) {
        /** Freeze the parts. */
        public Vocabulary {
            attributes = Map.copyOf(attributes);
            derived = List.copyOf(derived);
            slots = Map.copyOf(slots);
        }
    }

    /**
     * Mechanical facts of an item definition.
     *
     * @param rarity the declared rarity tier ID, or null when the pack defines no tiers
     * @param maxStack stack bound
     * @param gear slot, hands and tags; the slot is null for an item that cannot be worn
     */
    public record Item(String rarity, int maxStack, EquipmentRules.Gear gear) {
    }

    /**
     * Skill rule.
     *
     * @param maxRank highest rank
     * @param attribute governing attribute key, or null
     */
    public record Skill(int maxRank, String attribute) {
    }

    /**
     * Ability learning prerequisites.
     *
     * @param skills required minimum rank by canonical skill ID
     * @param abilities canonical IDs of abilities that must already be learned
     */
    public record Ability(Map<String, Integer> skills, Set<String> abilities) {
        /** Freeze the parts. */
        public Ability {
            skills = Map.copyOf(skills);
            abilities = Set.copyOf(abilities);
        }
    }

    /**
     * Project an already compiled closure. The compiler validated every reference, so a failure
     * here means the content and the engine disagree and is reported as an exception.
     *
     * @param snapshot privileged compiled closure; it is not retained
     * @return the catalog
     * @throws IllegalArgumentException if a compiled shape is not understood
     */
    public static CharacterRuleCatalog from(CampaignPackSnapshot snapshot) {
        var vocabularies = new HashMap<String, Vocabulary>();
        var profiles = new HashMap<String, String>();
        var items = new HashMap<String, Item>();
        var skills = new HashMap<String, Skill>();
        var abilities = new HashMap<String, Ability>();
        var conditions = new HashMap<String, ConditionRules.Definition>();
        for (CompiledPack pack : snapshot.packs().values()) {
            String packId = pack.parsed().manifest().id();
            vocabularies.put(packId, vocabulary(snapshot, pack));
            for (Definition definition : pack.definitions()) {
                String id = pack == snapshot.root() ? definition.id() : packId + "#" + definition.id();
                var data = definition.data();
                switch (definition.kind()) {
                    case "creation" -> profiles.put(id, packId);
                    case "item" -> items.put(id, item(data));
                    case "skill" -> skills.put(id, new Skill(number(data.get("maxRank"), 2), (String) data.get("attribute")));
                    case "ability" -> abilities.put(id, ability(snapshot, pack, data));
                    case "condition" -> conditions.put(id, condition(id, data));
                    default -> { }
                }
            }
        }
        return new CharacterRuleCatalog(vocabularies, profiles, items, skills, abilities, conditions);
    }

    /**
     * Vocabulary of an actor's creation profile.
     *
     * @param profileId canonical creation profile ID stored as the actor's definition ID
     * @return the vocabulary, or null when the profile is unknown (a generated actor has no profile)
     */
    public Vocabulary vocabularyOf(String profileId) {
        String pack = profiles.get(profileId);
        return pack == null ? null : vocabularies.get(pack);
    }

    /** Read a pack's rule definitions into one vocabulary. */
    private static Vocabulary vocabulary(CampaignPackSnapshot snapshot, CompiledPack pack) {
        var attributes = new LinkedHashMap<String, CharacterSheet.Range>();
        var derived = new ArrayList<CharacterSheet.Derived>();
        var slots = new LinkedHashMap<String, EquipmentRules.Slot>();
        int hands = 2;
        for (Definition definition : pack.definitions()) {
            if (!definition.kind().equals("rule")) {
                continue;
            }
            var data = definition.data();
            switch ((String) data.get("ruleType")) {
                case "attributes" -> {
                    for (Map<?, ?> attribute : objects(data.get("attributes"))) {
                        attributes.put((String) attribute.get("id"),
                                new CharacterSheet.Range(number(attribute.get("min"), 0), number(attribute.get("max"), 0)));
                    }
                }
                case "derivedStats" -> {
                    for (Map<?, ?> stat : objects(data.get("stats"))) {
                        var formula = StatFormula.parse(stat.get("formula"),
                                reference -> snapshot.resolve(pack, reference, "skill").id());
                        derived.add(new CharacterSheet.Derived((String) stat.get("id"), formula,
                                number(stat.get("min"), 0), number(stat.get("max"), 0)));
                    }
                }
                case "equipment" -> {
                    hands = number(data.get("hands"), 2);
                    for (Map<?, ?> slot : objects(data.get("slots"))) {
                        slots.put((String) slot.get("id"), new EquipmentRules.Slot((String) slot.get("id"),
                                number(slot.get("capacity"), 1), strings(slot.get("accepts"))));
                    }
                }
                default -> { }
            }
        }
        return new Vocabulary(attributes, derived, hands, slots);
    }

    private static Item item(Map<String, Object> data) {
        return new Item((String) data.get("rarity"), number(data.get("maxStack"), 1),
                new EquipmentRules.Gear((String) data.get("slot"), number(data.get("hands"), 0), strings(data.get("tags"))));
    }

    private static Ability ability(CampaignPackSnapshot snapshot, CompiledPack pack, Map<String, Object> data) {
        var skills = new HashMap<String, Integer>();
        var required = new LinkedHashSet<String>();
        if (data.get("prerequisites") instanceof Map<?, ?> prerequisites) {
            for (Map<?, ?> need : objects(prerequisites.get("skills"))) {
                skills.put(snapshot.resolve(pack, (String) need.get("skill"), "skill").id(), number(need.get("rank"), 1));
            }
            for (String reference : strings(prerequisites.get("abilities"))) {
                required.add(snapshot.resolve(pack, reference, "ability").id());
            }
        }
        return new Ability(skills, required);
    }

    private static ConditionRules.Definition condition(String id, Map<String, Object> data) {
        var modifiers = new HashMap<String, Integer>();
        for (Map<?, ?> modifier : objects(data.get("modifiers"))) {
            modifiers.put((String) modifier.get("attribute"), number(modifier.get("amount"), 0));
        }
        var stacking = ConditionRules.Stacking.valueOf(((String) data.getOrDefault("stacking", "refresh")).toUpperCase());
        return new ConditionRules.Definition(id, stacking, number(data.get("durationRounds"), 1),
                number(data.get("maxStacks"), stacking == ConditionRules.Stacking.STACK ? 3 : 1), modifiers);
    }

    private static List<Map<?, ?>> objects(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().<Map<?, ?>>map(element -> (Map<?, ?>) element).toList();
    }

    private static Set<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        return list.stream().map(String.class::cast).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
