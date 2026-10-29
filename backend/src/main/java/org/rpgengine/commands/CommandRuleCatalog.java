package org.rpgengine.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.rpgengine.characters.CharacterRuleCatalog;
import org.rpgengine.content.CampaignPackSnapshot;
import org.rpgengine.content.CompiledPack;
import org.rpgengine.content.Definition;

/**
 * Compact immutable projection of the pinned rule facts that command validation and local
 * resolution consult: location links and disclosure, actor-definition disclosure and
 * declaring packs, and ability contracts including their compiled effect programs. It is
 * derived only from a sealed, recompiled {@link CampaignPackSnapshot}; no authored prose,
 * secret text, display name or localization is retained, so caching it costs little memory
 * and cannot leak content. Effect programs hold only types, targets, resource keys and
 * bounded expressions ({@link EffectProgram}). Every identity is canonical: root definitions keep their local
 * ID and dependency definitions use {@code pack#id}, exactly as campaign creation stores
 * actor locations, skills and learned abilities. Pinned content never changes for a
 * campaign, so a catalog stays valid for the whole life of its content revision.
 *
 * <p>Resource keys are the one unqualified vocabulary: campaign creation stores the
 * resource IDs declared by the creation profile's own pack, and an ability's costs name
 * resources of the ability's declaring pack. {@link #resourceVocabularies} and
 * {@link Ability#pack()} record those packs so the rule gate never charges a cost against
 * a same-named resource that belongs to an independent pack's vocabulary.
 *
 * @param revisionId sealed root content revision this catalog was compiled from
 * @param locations location rules keyed by canonical location ID
 * @param actorDefinitions disclosure of actor-origin definitions keyed by canonical ID
 * @param resourceVocabularies declaring pack ID of each creation profile, keyed by canonical profile ID
 * @param actorPacks declaring pack ID of every NPC and creation-profile origin, keyed by canonical ID;
 *        an actor's resource keys belong to this pack's vocabulary (P07.4 effect targets)
 * @param abilities ability contracts keyed by canonical ability ID
 * @param items trade valuation and use contract of every item definition, keyed by canonical item ID (P10.2)
 * @param characters the character rule projection of the same closure: stack bounds, equipment facts
 *        and the equipment vocabularies the equip and unequip commands check against (P10.1)
 */
record CommandRuleCatalog(UUID revisionId, Map<String, Location> locations, Map<String, Boolean> actorDefinitions,
        Map<String, String> resourceVocabularies, Map<String, String> actorPacks, Map<String, Ability> abilities,
        Map<String, ItemRule> items, CharacterRuleCatalog characters) {
    /** Freeze every index; null parts are construction bugs and fail immediately. */
    CommandRuleCatalog {
        Objects.requireNonNull(revisionId, "revisionId");
        items = Map.copyOf(items);
        Objects.requireNonNull(characters, "characters");
        locations = Map.copyOf(locations);
        actorDefinitions = Map.copyOf(actorDefinitions);
        resourceVocabularies = Map.copyOf(resourceVocabularies);
        actorPacks = Map.copyOf(actorPacks);
        abilities = Map.copyOf(abilities);
    }

    /** Catalog without item rules, the shape used before P10.2 (tests that exercise only movement and abilities). */
    CommandRuleCatalog(UUID revisionId, Map<String, Location> locations, Map<String, Boolean> actorDefinitions,
            Map<String, String> resourceVocabularies, Map<String, String> actorPacks, Map<String, Ability> abilities) {
        this(revisionId, locations, actorDefinitions, resourceVocabularies, actorPacks, abilities, Map.of(),
                new CharacterRuleCatalog(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
    }

    /**
     * Project an already compiled closure. The compiler has validated every reference, so
     * resolution here cannot discover new errors; if it does, the argument error propagates
     * and the caller treats the content as unavailable rather than guessing a rule.
     *
     * @param revisionId root revision identity used as the cache key and recheck token
     * @param snapshot privileged compiled closure; it is not retained
     * @return detached rule facts for every pack in the closure
     */
    static CommandRuleCatalog from(UUID revisionId, CampaignPackSnapshot snapshot) {
        var locations = new HashMap<String, Location>();
        var actors = new HashMap<String, Boolean>();
        var vocabularies = new HashMap<String, String>();
        var origins = new HashMap<String, String>();
        var abilities = new HashMap<String, Ability>();
        var items = new HashMap<String, ItemRule>();
        for (CompiledPack pack : snapshot.packs().values()) {
            String packId = pack.parsed().manifest().id();
            for (Definition definition : pack.definitions()) {
                String id = pack == snapshot.root() ? definition.id() : packId + "#" + definition.id();
                var data = definition.data();
                switch (definition.kind()) {
                    case "location" -> {
                        // Only links the author marked accessible are traversable; a blocked
                        // link is a known route that the player cannot currently use.
                        var reachable = new LinkedHashSet<String>();
                        for (Map<?, ?> link : objects(data.get("links"))) {
                            if (Boolean.TRUE.equals(link.get("accessible"))) {
                                reachable.add(snapshot.resolve(pack, (String) link.get("to"), "location").id());
                            }
                        }
                        locations.put(id, new Location(disclosed(data), reachable));
                    }
                    case "npc" -> {
                        actors.put(id, disclosed(data));
                        origins.put(id, packId);
                    }
                    // A creation profile is the player's own origin and is never concealed; its
                    // declaring pack supplied the player's resource keys at campaign creation.
                    case "creation" -> {
                        actors.put(id, true);
                        vocabularies.put(id, packId);
                        origins.put(id, packId);
                    }
                    case "ability" -> abilities.put(id, ability(snapshot, pack, data));
                    case "item" -> items.put(id, item(snapshot, pack, data));
                    default -> { }
                }
            }
        }
        return new CommandRuleCatalog(revisionId, locations, actors, vocabularies, origins, abilities, items,
                CharacterRuleCatalog.from(snapshot));
    }

    /**
     * Read an item's trade valuation and optional use contract. The use contract is held as an
     * {@link Ability} without prerequisites or costs, so item use and ability use share one effect
     * resolver; it is declared by the item's own pack, which owns the unqualified resource keys.
     */
    private static ItemRule item(CampaignPackSnapshot snapshot, CompiledPack pack, Map<String, Object> data) {
        int value = number(data.get("value"), 0);
        Use use = null;
        if (data.get("use") instanceof Map<?, ?> declared) {
            var target = declared.get("target") instanceof Map<?, ?> targeting ? targeting : Map.of();
            String policy = target.get("policy") instanceof String text ? text : "self";
            var contract = new Ability(pack.parsed().manifest().id(), List.of(), Set.of(), Map.of(), policy,
                    number(target.get("maxTargets"), 1), EffectProgram.compile(declared.get("effects"), snapshot, pack, null));
            use = new Use(contract, !Boolean.FALSE.equals(declared.get("consumes")));
        }
        return new ItemRule(value == 0 ? null : value, use);
    }

    /** Canonicalize one ability contract's prerequisites, costs, target policy and effect program. */
    private static Ability ability(CampaignPackSnapshot snapshot, CompiledPack pack, Map<String, Object> data) {
        var skills = new ArrayList<SkillNeed>();
        var required = new LinkedHashSet<String>();
        if (data.get("prerequisites") instanceof Map<?, ?> prerequisites) {
            for (Map<?, ?> need : objects(prerequisites.get("skills"))) {
                skills.add(new SkillNeed(snapshot.resolve(pack, (String) need.get("skill"), "skill").id(),
                        number(need.get("rank"), 1)));
            }
            if (prerequisites.get("abilities") instanceof List<?> references) {
                for (Object reference : references) {
                    required.add(snapshot.resolve(pack, (String) reference, "ability").id());
                }
            }
        }
        var costs = new HashMap<String, Integer>();
        for (Map<?, ?> cost : objects(data.get("costs"))) {
            costs.put((String) cost.get("resource"), number(cost.get("amount"), 0));
        }
        var target = data.get("target") instanceof Map<?, ?> value ? value : Map.of();
        return new Ability(pack.parsed().manifest().id(), skills, required, costs, (String) target.get("policy"),
                number(target.get("maxTargets"), 1), EffectProgram.compile(data.get("effects"), snapshot, pack, null));
    }

    /** Pack default for omitted knowledge visibility is public, matching the compiler's default. */
    private static boolean disclosed(Map<String, Object> data) {
        return !"secret".equals(data.get("knowledge"));
    }

    /** Return the object elements of an optional validated list; absent lists are empty. */
    private static List<Map<?, ?>> objects(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().<Map<?, ?>>map(element -> (Map<?, ?>) element).toList();
    }

    /** Read a validated bounded integer, or the compiler's documented default when omitted. */
    private static int number(Object value, int fallback) {
        return value == null ? fallback : ((Number) value).intValue();
    }

    /**
     * Location rule facts.
     *
     * @param disclosed false when the author marked the location's knowledge as secret
     * @param accessibleLinks canonical destinations reachable by one ordinary move
     */
    record Location(boolean disclosed, Set<String> accessibleLinks) {
        /** Freeze the link set. */
        Location { accessibleLinks = Set.copyOf(accessibleLinks); }
    }

    /**
     * Ability contract facts needed for validation and local resolution.
     *
     * @param pack declaring pack ID, which owns the vocabulary of the cost resource keys
     * @param skills minimum ranks of canonical skills
     * @param abilities canonical abilities that must also be learned
     * @param costs non-negative amount per unqualified resource key of {@code pack}, charged to the acting player
     * @param targetPolicy {@code self}, {@code single}, {@code multiple} or {@code area}
     * @param maxTargets declared upper bound for multiple-target abilities
     * @param effects compiled effect program in authored order (at least one node in valid content)
     */
    record Ability(String pack, List<SkillNeed> skills, Set<String> abilities, Map<String, Integer> costs,
            String targetPolicy, int maxTargets, List<EffectProgram.Effect> effects) {
        /** Freeze nested collections; the pack and policy are always present in compiled content. */
        Ability {
            Objects.requireNonNull(pack, "pack");
            skills = List.copyOf(skills);
            abilities = Set.copyOf(abilities);
            costs = Map.copyOf(costs);
            Objects.requireNonNull(targetPolicy, "targetPolicy");
            effects = List.copyOf(effects);
        }
    }

    /**
     * Item facts the command rules need beyond the character catalog.
     *
     * @param value trade valuation units, or null when the item is unpriced and cannot be traded
     * @param use use contract, or null when the item cannot be used
     */
    record ItemRule(Integer value, Use use) { }

    /**
     * What happens when an item is used.
     *
     * @param contract targeting policy and effect program, with the item's pack as the vocabulary owner
     * @param consumes whether one unit is used up by the action
     */
    record Use(Ability contract, boolean consumes) {
        /** Require the contract. */
        Use { Objects.requireNonNull(contract, "contract"); }
    }

    /**
     * One skill prerequisite.
     *
     * @param skill canonical skill ID as stored in {@code character_state.skills}
     * @param rank minimum rank, 1..10
     */
    record SkillNeed(String skill, int rank) { }
}
