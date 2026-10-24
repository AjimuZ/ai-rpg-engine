package org.rpgengine.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Semantic validation of a {@link ParsedPack}: stable IDs, dependency resolution, cross-references,
 * creation profiles, item rarity/appearance identities, skills, abilities and initial world definitions.
 *
 * <p>Boundaries: this stage reads only the immutable parser output and a caller-supplied
 * {@link DependencyCatalog}; it performs no I/O. Kinds whose semantics belong to later tasks
 * (quest, encounter, anchor, progression) fail closed with UNSUPPORTED_KIND instead of being
 * accepted unchecked. Expression/effect depth limits, graph cycles and version-range
 * compatibility are P04.3; path/archive protections and registration are P04.4.
 *
 * <p>Failure behavior: the first violation throws a {@link PackException} with file, line and
 * pointer. Success never admits content; it returns a {@link CompiledPack}.
 */
public final class PackCompiler {
    private static final Map<String, String> CATEGORY_KINDS = Map.ofEntries(
            Map.entry("locations", "location"), Map.entry("factions", "faction"), Map.entry("creation", "creation"),
            Map.entry("npcs", "npc"), Map.entry("items", "item"), Map.entry("skills", "skill"),
            Map.entry("abilities", "ability"), Map.entry("conditions", "condition"),
            Map.entry("progression", "progression"), Map.entry("encounters", "encounter"),
            Map.entry("quests", "quest"), Map.entry("rules", "rule"), Map.entry("anchors", "anchor"));
    private static final Set<String> LOCATION = Set.of("location");
    private static final Set<String> FACTION = Set.of("faction");
    private static final Set<String> CONDITION = Set.of("condition");
    private static final Set<String> SKILL = Set.of("skill");
    private static final Set<String> ABILITY = Set.of("ability");
    private static final Set<String> ITEM = Set.of("item");
    private static final Set<String> VISIBILITY = Set.of("public", "secret");
    private static final Set<String> ITEM_TYPES = Set.of("weapon", "armor", "tool", "consumable", "treasure", "key", "misc");
    private static final Set<String> TARGET_POLICIES = Set.of("self", "single", "multiple", "area");
    private static final Set<String> EFFECT_TYPES = Set.of("damage", "heal", "move", "applyCondition",
            "removeCondition", "modifyResource", "reveal", "createItem");
    private static final int LIST_MAX = 64;
    private static final int VALUE_MAX = 1_000_000;

    private final DependencyCatalog catalog;

    /** Create a compiler using the given trusted catalog of installed dependency packs. */
    public PackCompiler(DependencyCatalog catalog) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
    }

    /** Create a compiler with no installed dependencies. */
    public PackCompiler() {
        this(DependencyCatalog.none());
    }

    /** Validate a parsed pack; throws a located PackException on the first blocking problem. */
    public CompiledPack compile(ParsedPack pack) {
        return new Run(pack).execute();
    }

    /** Per-compilation mutable state; never shared, so the compiler itself stays stateless. */
    private final class Run implements Fields.Resolver {
        private final ParsedPack pack;
        private final PackDocument manifestDocument;
        private final Map<String, Definition> byId = new LinkedHashMap<>();
        /** Exports of resolved dependencies keyed by declared pack ID (definition ID to kind). */
        private final Map<String, Map<String, String>> external = new HashMap<>();
        /** Attribute ID to {min, max}; filled from "attributes" rule definitions. */
        private final Map<String, int[]> attributes = new HashMap<>();
        private final Set<String> resources = new HashSet<>();
        private final Set<String> rarityTiers = new HashSet<>();
        /** Appearance identities must be unique across items so artwork binds to exactly one item. */
        private final Set<String> appearances = new HashSet<>();

        /** Capture the immutable input. */
        Run(ParsedPack pack) {
            this.pack = pack;
            this.manifestDocument = pack.documents().get(rootName());
        }

        /** Root manifest path: exactly one of the two names is present in the parsed documents. */
        private String rootName() {
            return pack.documents().containsKey("adventure.yaml") ? "adventure.yaml" : "adventure.json";
        }

        /** Run all passes in dependency order. */
        CompiledPack execute() {
            resolveDependencies();
            List<Definition> all = collect();
            // Rules first: they define the attribute/resource/rarity vocabularies others reference.
            for (Definition definition : all) {
                if (definition.kind().equals("rule")) {
                    validate(definition);
                }
            }
            for (Definition definition : all) {
                if (!definition.kind().equals("rule")) {
                    validate(definition);
                }
            }
            entrypoint();
            return new CompiledPack(pack, all, byId);
        }

        /** Resolve every declared dependency through the catalog; duplicates, self-reference and misses fail. */
        @SuppressWarnings("unchecked")
        private void resolveDependencies() {
            var values = pack.manifest().values();
            var dependencies = (List<Map<String, Object>>) values.getOrDefault("dependencies", List.of());
            for (int index = 0; index < dependencies.size(); index++) {
                var dependency = dependencies.get(index);
                String pointer = "/dependencies/" + index;
                String id = (String) dependency.get("id");
                if (id.equals(pack.manifest().id()) || external.containsKey(id)) {
                    throw new PackException("DEPENDENCY", manifestDocument.location(pointer + "/id"),
                            "A pack cannot depend on itself or declare a dependency twice");
                }
                var exports = catalog.exports(id, (String) dependency.get("version"), (String) dependency.get("hash"));
                if (exports.isEmpty()) {
                    throw new PackException("DEPENDENCY", manifestDocument.location(pointer),
                            "No installed pack matches this id, version and hash");
                }
                external.put(id, Map.copyOf(exports.get()));
            }
        }

        /** Gather definitions from categorized files; enforce category/kind agreement and unique IDs. */
        @SuppressWarnings("unchecked")
        private List<Definition> collect() {
            List<Definition> all = new ArrayList<>();
            for (var file : pack.manifest().files()) {
                String expected = CATEGORY_KINDS.get(file.category());
                if (expected == null) {
                    continue;
                }
                PackDocument document = pack.documents().get(file.path());
                var list = (List<Map<String, Object>>) ((Map<String, Object>) document.value()).get("definitions");
                for (int index = 0; index < list.size(); index++) {
                    String pointer = "/definitions/" + index;
                    var entry = list.get(index);
                    String id = (String) entry.get("id");
                    String kind = (String) entry.get("kind");
                    if (!kind.equals(expected)) {
                        throw new PackException("KIND_MISMATCH", document.location(pointer + "/kind"),
                                "Category " + file.category() + " only holds kind " + expected);
                    }
                    if (byId.containsKey(id)) {
                        throw new PackException("DUPLICATE_ID", document.location(pointer + "/id"),
                                "ID already defined in " + byId.get(id).file() + " " + byId.get(id).pointer());
                    }
                    var definition = new Definition(id, kind, file.path(), pointer, (Map<String, Object>) entry.get("data"));
                    byId.put(id, definition);
                    all.add(definition);
                }
            }
            return all;
        }

        /** Resolve a local ID or an {@code external-pack#id} reference to a definition of an allowed kind. */
        @Override
        public void resolve(PackDocument document, String pointer, String ref, Set<String> kinds) {
            int hash = ref.indexOf('#');
            String kind;
            if (hash >= 0) {
                var exports = external.get(ref.substring(0, hash));
                if (exports == null) {
                    throw new PackException("REFERENCE_UNKNOWN", document.location(pointer),
                            "Pack '" + ref.substring(0, hash) + "' is not a declared dependency");
                }
                kind = exports.get(ref.substring(hash + 1));
            } else {
                var local = byId.get(ref);
                kind = local == null ? null : local.kind();
            }
            if (kind == null) {
                throw new PackException("REFERENCE_UNKNOWN", document.location(pointer), "Unknown definition '" + ref + "'");
            }
            if (!kinds.contains(kind)) {
                throw new PackException("REFERENCE_KIND", document.location(pointer),
                        "'" + ref + "' is a " + kind + "; expected " + new java.util.TreeSet<>(kinds));
            }
        }

        /** Check the manifest entrypoint's location and creation profile references. */
        private void entrypoint() {
            var entry = new Fields(manifestDocument, "/entrypoint", pack.manifest().values().get("entrypoint"), this);
            entry.text("situation", true);
            entry.ref("location", LOCATION, true);
            entry.ref("creationProfile", Set.of("creation"), true);
            entry.close();
        }

        /** Dispatch one definition to its kind-specific validator. */
        private void validate(Definition definition) {
            PackDocument document = pack.documents().get(definition.file());
            var f = new Fields(document, definition.pointer() + "/data", definition.data(), this);
            switch (definition.kind()) {
                case "rule" -> rule(f);
                case "location" -> location(f, definition);
                case "faction" -> faction(f, definition);
                case "npc" -> npc(f);
                case "item" -> item(f);
                case "skill" -> skill(f);
                case "ability" -> ability(f, definition);
                case "condition" -> condition(f);
                case "creation" -> creation(f);
                default -> throw new PackException("UNSUPPORTED_KIND", document.location(definition.pointer() + "/kind"),
                        "Kind " + definition.kind() + " has no semantic schema in this pack contract revision");
            }
            f.close();
        }

        /** Register the rule vocabularies; unknown rule types fail closed rather than being ignored. */
        private void rule(Fields f) {
            String type = f.choice("ruleType", Set.of("attributes", "resources", "rarityTiers"), null);
            switch (type) {
                case "attributes" -> {
                    for (Fields a : f.objects("attributes", 1, 16)) {
                        String id = a.id("id", true);
                        a.text("name", true);
                        int min = a.integer("min", 0, 100, 0);
                        int max = a.integer("max", 0, 100, null);
                        if (max < min) {
                            throw a.fail("max", "Maximum must not be below minimum");
                        }
                        a.close();
                        if (attributes.putIfAbsent(id, new int[] {min, max}) != null) {
                            throw a.fail("DUPLICATE", "id", "Attribute ID already defined");
                        }
                    }
                }
                case "resources" -> {
                    for (Fields r : f.objects("resources", 1, 16)) {
                        String id = r.id("id", true);
                        r.text("name", true);
                        r.integer("max", 1, VALUE_MAX, null);
                        r.close();
                        if (!resources.add(id)) {
                            throw r.fail("DUPLICATE", "id", "Resource ID already defined");
                        }
                    }
                }
                default -> {
                    for (Fields t : f.objects("tiers", 1, 16)) {
                        String id = t.id("id", true);
                        t.text("name", true);
                        // Accessible presentation token (docs/23); never a mechanical value.
                        t.id("token", true);
                        t.close();
                        if (!rarityTiers.add(id)) {
                            throw t.fail("DUPLICATE", "id", "Rarity tier ID already defined");
                        }
                    }
                }
            }
        }

        /** Read the knowledge-visibility facts shared by world definitions. */
        private void facts(Fields f) {
            for (Fields fact : f.objects("facts", 0, 50)) {
                fact.text("text", true);
                fact.choice("visibility", VISIBILITY, "public");
                fact.close();
            }
        }

        /** Location: links to other locations, initial conditions and knowledge visibility. */
        private void location(Fields f, Definition self) {
            f.text("name", true);
            f.text("description", true);
            f.ids("tags", 32);
            f.choice("knowledge", VISIBILITY, "public");
            Set<String> targets = new HashSet<>();
            for (Fields link : f.objects("links", 0, LIST_MAX)) {
                String to = link.ref("to", LOCATION, true);
                if (to.equals(self.id()) || !targets.add(to)) {
                    throw link.fail("to", "A location cannot link to itself or repeat a link");
                }
                link.bool("accessible", true);
                link.close();
            }
            f.refs("conditions", CONDITION, 16);
            facts(f);
        }

        /** Faction: goals and relationships to other factions. */
        private void faction(Fields f, Definition self) {
            f.text("name", true);
            f.text("description", true);
            f.texts("goals", 20);
            f.choice("knowledge", VISIBILITY, "public");
            Set<String> targets = new HashSet<>();
            for (Fields relation : f.objects("relationships", 0, LIST_MAX)) {
                String other = relation.ref("faction", FACTION, true);
                if (other.equals(self.id()) || !targets.add(other)) {
                    throw relation.fail("faction", "A faction cannot relate to itself or repeat a relationship");
                }
                relation.choice("stance", Set.of("allied", "neutral", "hostile"), null);
                relation.close();
            }
        }

        /** NPC: initial location, optional faction and a profile of attributes, skills and abilities. */
        private void npc(Fields f) {
            f.text("name", true);
            f.text("role", true);
            f.text("description", false);
            f.texts("goals", 20);
            f.ref("location", LOCATION, true);
            f.ref("faction", FACTION, false);
            f.choice("knowledge", VISIBILITY, "public");
            Fields profile = f.object("profile", false);
            if (profile != null) {
                attributeValues(profile, "attributes");
                skillRanks(profile, "skills");
                profile.refs("abilities", ABILITY, LIST_MAX);
                profile.close();
            }
            facts(f);
        }

        /** Item: type, stack/slot policy, validated rarity tier, appearance identity and plain integer properties. */
        private void item(Fields f) {
            f.text("name", true);
            f.text("description", true);
            f.choice("itemType", ITEM_TYPES, null);
            f.integer("maxStack", 1, 9999, 1);
            f.id("slot", false);
            String rarity = f.id("rarity", false);
            if (rarity == null && !rarityTiers.isEmpty()) {
                throw f.fail("rarity", "This pack defines rarity tiers, so every item needs one");
            }
            if (rarity != null && !rarityTiers.contains(rarity)) {
                throw f.fail("REFERENCE_UNKNOWN", "rarity", "Unknown rarity tier '" + rarity + "'");
            }
            // Properties are plain integers: formulas and effects must use the bounded expression
            // layer (P04.3), so prose or text cannot smuggle a mechanical bonus in here.
            f.intMap("properties", -VALUE_MAX, VALUE_MAX, 32);
            Fields appearance = f.object("appearance", true);
            String appearanceId = appearance.id("id", true);
            appearance.text("summary", true);
            appearance.close();
            if (!appearances.add(appearanceId)) {
                throw appearance.fail("DUPLICATE", "id", "Appearance identity already used by another item");
            }
            f.text("provenance", false);
        }

        /** Skill: optional governing attribute and a bounded maximum rank. */
        private void skill(Fields f) {
            f.text("name", true);
            f.text("description", true);
            String attribute = f.id("attribute", false);
            if (attribute != null && !attributes.containsKey(attribute)) {
                throw f.fail("REFERENCE_UNKNOWN", "attribute", "Unknown attribute '" + attribute + "'");
            }
            f.integer("maxRank", 1, 10, 2);
        }

        /** Condition: identity, stacking policy and optional duration; modifiers arrive with P04.3. */
        private void condition(Fields f) {
            f.text("name", true);
            f.text("description", true);
            f.choice("stacking", Set.of("none", "refresh", "stack"), "refresh");
            f.integer("durationRounds", 1, 10_000, 1);
        }

        /** Ability: prerequisites, costs, target policy and a shallowly typed effect list (depth rules: P04.3). */
        private void ability(Fields f, Definition self) {
            f.text("name", true);
            f.text("description", true);
            f.ids("tags", 32);
            Fields prerequisites = f.object("prerequisites", false);
            if (prerequisites != null) {
                Set<String> seenSkills = new HashSet<>();
                for (Fields need : prerequisites.objects("skills", 0, 16)) {
                    String skill = need.ref("skill", SKILL, true);
                    if (!seenSkills.add(skill)) {
                        throw need.fail("skill", "Duplicate skill prerequisite");
                    }
                    int rank = need.integer("rank", 1, 10, null);
                    Object max = byId.get(skill) == null ? null : byId.get(skill).data().get("maxRank");
                    if (max != null && rank > ((Number) max).intValue()) {
                        throw need.fail("rank", "Rank exceeds the skill's maxRank");
                    }
                    need.close();
                }
                for (String other : prerequisites.refs("abilities", ABILITY, 16)) {
                    if (other.equals(self.id())) {
                        throw prerequisites.fail("abilities", "An ability cannot require itself");
                    }
                }
                prerequisites.close();
            }
            Set<String> costed = new HashSet<>();
            for (Fields cost : f.objects("costs", 0, 8)) {
                String resource = cost.id("resource", true);
                if (!resources.contains(resource)) {
                    throw cost.fail("REFERENCE_UNKNOWN", "resource", "Unknown resource '" + resource
                            + "'; defined resources: " + new java.util.TreeSet<>(resources));
                }
                if (!costed.add(resource)) {
                    throw cost.fail("resource", "Duplicate cost for one resource");
                }
                cost.integer("amount", 0, VALUE_MAX, null);
                cost.close();
            }
            Fields target = f.object("target", true);
            target.choice("policy", TARGET_POLICIES, null);
            // Eight targets per normal action (docs/09 section 7).
            target.integer("maxTargets", 1, 8, 1);
            target.close();
            for (Fields effect : f.objects("effects", 1, 20)) {
                effect(effect);
            }
        }

        /** Validate one effect node's declared type and the references that type requires. */
        private void effect(Fields e) {
            String type = e.choice("type", EFFECT_TYPES, null);
            e.choice("target", Set.of("self", "target", "targets"), "target");
            e.choice("timing", Set.of("pre", "post"), "post");
            boolean amount = type.equals("damage") || type.equals("heal") || type.equals("modifyResource");
            if (amount) {
                e.integer("amount", 0, VALUE_MAX, null);
            }
            if (type.equals("applyCondition") || type.equals("removeCondition")) {
                e.ref("condition", CONDITION, true);
            }
            if (type.equals("modifyResource")) {
                String resource = e.id("resource", true);
                if (!resources.contains(resource)) {
                    throw e.fail("REFERENCE_UNKNOWN", "resource", "Unknown resource '" + resource + "'");
                }
            }
            if (type.equals("createItem")) {
                e.ref("item", ITEM, true);
            }
            if (type.equals("move")) {
                e.ref("destination", LOCATION, true);
            }
            // Fields not consumed for this type (e.g. amount on move) are rejected as unknown.
            e.close();
        }

        /** Check attribute values against the rule-defined range; returns the sum above each minimum. */
        private int attributeValues(Fields f, String key) {
            int spent = 0;
            for (var entry : f.intMap(key, 0, 100, 16).entrySet()) {
                int[] range = attributes.get(entry.getKey());
                if (range == null) {
                    throw new PackException("REFERENCE_UNKNOWN", f.document().location(f.keyPointer(key, entry.getKey())),
                            "Unknown attribute '" + entry.getKey() + "'");
                }
                if (entry.getValue() < range[0] || entry.getValue() > range[1]) {
                    throw new PackException("RANGE", f.document().location(f.keyPointer(key, entry.getKey())),
                            "Value outside attribute range " + range[0] + ".." + range[1]);
                }
                spent += entry.getValue() - range[0];
            }
            return spent;
        }

        /** Check skill ranks against each skill's maxRank; returns the total ranks. */
        private int skillRanks(Fields f, String key) {
            int total = 0;
            for (var entry : f.intMap(key, 0, 10, LIST_MAX).entrySet()) {
                String pointer = f.keyPointer(key, entry.getKey());
                f.resolve(pointer, entry.getKey(), SKILL);
                Object declared = byId.get(entry.getKey()) == null ? null : byId.get(entry.getKey()).data().get("maxRank");
                int max = declared instanceof Number n ? n.intValue() : 2;
                if (entry.getValue() > max) {
                    throw new PackException("RANGE", f.document().location(pointer), "Rank exceeds the skill's maxRank " + max);
                }
                total += entry.getValue();
            }
            return total;
        }

        /** Creation profile: budgets, allowed skills, archetype presets and starting loadout. */
        private void creation(Fields f) {
            f.text("name", true);
            f.text("description", false);
            int attributeBudget = f.integer("attributeBudget", 0, 1000, null);
            int skillBudget = f.integer("skillBudget", 0, 1000, null);
            List<String> allowedAttributes = f.ids("attributes", 16);
            for (String attribute : allowedAttributes) {
                if (!attributes.containsKey(attribute)) {
                    throw f.fail("REFERENCE_UNKNOWN", "attributes", "Unknown attribute '" + attribute + "'");
                }
            }
            if (allowedAttributes.isEmpty()) {
                throw f.fail("attributes", "A creation profile needs at least one attribute");
            }
            List<String> allowedSkills = f.refs("skills", SKILL, LIST_MAX);
            f.texts("backgrounds", 32);
            f.texts("roles", 32);
            Set<String> archetypeIds = new HashSet<>();
            for (Fields archetype : f.objects("archetypes", 0, 32)) {
                if (!archetypeIds.add(archetype.id("id", true))) {
                    throw archetype.fail("DUPLICATE", "id", "Duplicate archetype ID");
                }
                archetype.text("name", true);
                // Archetypes are data presets that must fit the same budgets the player faces.
                for (String key : archetype.intMap("attributes", 0, 100, 16).keySet()) {
                    if (!allowedAttributes.contains(key)) {
                        throw new PackException("REFERENCE_UNKNOWN", archetype.document().location(archetype.keyPointer("attributes", key)),
                                "Attribute is not allowed by this creation profile");
                    }
                }
                if (attributeValues(archetype, "attributes") > attributeBudget) {
                    throw archetype.fail("RANGE", "attributes", "Archetype exceeds the attribute budget");
                }
                for (String key : archetype.intMap("skills", 0, 10, LIST_MAX).keySet()) {
                    if (!allowedSkills.contains(key)) {
                        throw new PackException("REFERENCE_UNKNOWN", archetype.document().location(archetype.keyPointer("skills", key)),
                                "Skill is not allowed by this creation profile");
                    }
                }
                if (skillRanks(archetype, "skills") > skillBudget) {
                    throw archetype.fail("RANGE", "skills", "Archetype exceeds the skill budget");
                }
                archetype.close();
            }
            for (Fields loadout : f.objects("startingItems", 0, LIST_MAX)) {
                String item = loadout.ref("item", ITEM, true);
                int quantity = loadout.integer("quantity", 1, 9999, 1);
                var definition = byId.get(item);
                // External items carry no local stack policy here; only local definitions are comparable.
                if (definition != null && definition.data().get("maxStack") instanceof Number stack
                        && quantity > stack.intValue()) {
                    throw loadout.fail("RANGE", "quantity", "Quantity exceeds the item's maxStack " + stack);
                }
                loadout.close();
            }
            f.refs("startingAbilities", ABILITY, LIST_MAX);
        }
    }
}
