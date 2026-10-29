package org.rpgengine.content;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.rpgengine.rules.WorldClock;

/**
 * Semantic validation of a {@link ParsedPack}: stable IDs, dependency resolution, cross-references,
 * creation profiles, item rarity/appearance identities, skills, abilities, conditions, quests and
 * initial world definitions (P04.2), derived statistics, equipment slots, hands and stacking
 * conditions (P10.1), plus bounded expressions/effects, relation cycles, engine and
 * ruleset compatibility, module support, hash conflicts and the generation policy (P04.3).
 *
 * <p>Boundaries: this stage reads only the immutable parser output, a caller-supplied
 * {@link DependencyCatalog} and the {@link EngineContract}; it performs no I/O. Kinds whose
 * semantics belong to later tasks (encounter, anchor, progression) fail closed with
 * UNSUPPORTED_KIND instead of being accepted unchecked. Path/archive protections and
 * registration are P04.4. Decisions are recorded in ADR 0017.
 *
 * <p>Failure behavior: the first violation throws a {@link PackException} with file, line and
 * pointer. Non-blocking findings are returned as {@link PackWarning}s, never as guarantees.
 * Success never admits content; it returns a {@link CompiledPack}.
 */
public final class PackCompiler {
    /**
     * Most item units one quest outcome may reward. An ending settles in a single canonical commit, which
     * bounds the rows, ledger entries and events it can write; the quest engine budgets against the same number.
     */
    public static final int MAX_QUEST_REWARD_UNITS = 32;
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
            "removeCondition", "modifyResource", "reveal", "createItem", "conditional");
    /** Kinds a generation constraint may name: every kind of a known content category. */
    private static final Set<String> ALL_KINDS = Set.copyOf(CATEGORY_KINDS.values());
    private static final int LIST_MAX = 64;
    /** Hands available when a pack's equipment rule does not say otherwise (docs/10 section 3). */
    static final int DEFAULT_HANDS = 2;
    /** Most hands a pack may declare; larger values would make hand occupancy uninformative. */
    static final int MAX_HANDS = 4;
    /** Most units one {@code createItem} node may create per recipient (P10.2). */
    static final int MAX_CREATED_QUANTITY = 99;
    /** Cap on stacks of a stacking condition; bounds how far one condition can move an attribute. */
    static final int MAX_CONDITION_STACKS = 10;
    private static final int VALUE_MAX = 1_000_000;
    /** Effect nodes per ability or condition tick list, nested conditional nodes included (docs/09 section 7). */
    static final int EFFECT_NODES = 20;
    /** Nesting levels of conditional effects; deeper trees are hard to explain and cost more to resolve. */
    static final int EFFECT_DEPTH = 4;

    private final DependencyCatalog catalog;
    private final EngineContract contract;

    /**
     * Create a compiler for an explicit engine contract.
     *
     * @param catalog trusted catalog of installed dependency packs
     * @param contract engine, ruleset and module versions packs are checked against
     */
    public PackCompiler(DependencyCatalog catalog, EngineContract contract) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.contract = java.util.Objects.requireNonNull(contract, "contract");
    }

    /** Create a compiler for this engine build using the given trusted catalog of installed dependency packs. */
    public PackCompiler(DependencyCatalog catalog) {
        this(catalog, EngineContract.current());
    }

    /** Create a compiler with no installed dependencies. */
    public PackCompiler() {
        this(DependencyCatalog.none());
    }

    /** Validate a parsed pack; throws a located PackException on the first blocking problem. */
    public CompiledPack compile(ParsedPack pack) {
        return new Run(pack).execute();
    }

    /**
     * Content category holding a kind, e.g. {@code npc -> npcs}; used by the generation gate to find
     * the policy of an authored definition. Unknown kinds map to themselves and so stay disabled.
     */
    static String categoryOf(String kind) {
        for (var entry : CATEGORY_KINDS.entrySet()) {
            if (entry.getValue().equals(kind)) {
                return entry.getKey();
            }
        }
        return kind;
    }

    /** Per-compilation mutable state; never shared, so the compiler itself stays stateless. */
    private final class Run implements Expressions.Context {
        private final ParsedPack pack;
        private final PackDocument manifestDocument;
        private final Map<String, Definition> byId = new LinkedHashMap<>();
        /** Exports of resolved dependencies keyed by declared pack ID (definition ID to kind). */
        private final Map<String, Map<String, String>> external = new HashMap<>();
        /** Attribute ID to {min, max}; filled from "attributes" rule definitions. */
        private final Map<String, int[]> attributes = new HashMap<>();
        /** Resource ID to its maximum; filled from "resources" rule definitions. */
        private final Map<String, Integer> resources = new HashMap<>();
        private final Set<String> rarityTiers = new HashSet<>();
        /** Rest policy IDs, unique within the pack. */
        private final Set<String> restPolicies = new HashSet<>();
        /** Derived statistic IDs; they share no namespace with attributes so a formula atom is never ambiguous. */
        private final Set<String> derivedStats = new HashSet<>();
        /** Equipment slot ID to {capacity, accepted tags}; filled from the "equipment" rule definition. */
        private final Map<String, EquipmentSlot> equipmentSlots = new HashMap<>();
        /** Simultaneous hands this pack's actors have; used to check two-handed items and loadouts. */
        private int handCapacity = DEFAULT_HANDS;
        /** Appearance identities must be unique across items so artwork binds to exactly one item. */
        private final Set<String> appearances = new HashSet<>();
        /** "Requires" relations whose cycles would make content unsatisfiable or loop forever. */
        private final DependencyGraph abilityGraph = new DependencyGraph();
        private final DependencyGraph conditionGraph = new DependencyGraph();
        private final DependencyGraph questGraph = new DependencyGraph();
        private final List<PackWarning> warnings = new ArrayList<>();
        /** Effect nodes counted so far in the current ability effect list or condition tick list. */
        private int effectNodes;

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
            compatibility();
            resolveDependencies();
            List<Definition> all = collect();
            // Rules first: they define the attribute/resource/rarity vocabularies others reference.
            // Derived statistics read attributes and rest policies read resources, so both follow the other
            // rules whatever the file order.
            for (Definition definition : all) {
                if (definition.kind().equals("rule") && !readsVocabulary(definition)) {
                    validate(definition);
                }
            }
            for (Definition definition : all) {
                if (definition.kind().equals("rule") && readsVocabulary(definition)) {
                    validate(definition);
                }
            }
            for (Definition definition : all) {
                if (!definition.kind().equals("rule")) {
                    validate(definition);
                }
            }
            entrypoint();
            // Graphs are complete only after every definition was read, so cycles are checked last.
            abilityGraph.rejectCycles("ability prerequisite");
            conditionGraph.rejectCycles("condition application");
            questGraph.rejectCycles("quest requirement");
            GenerationPolicy policy = generationPolicy();
            reachability();
            return new CompiledPack(pack, all, byId, policy, PackLocalization.compile(pack, byId), warnings);
        }

        /**
         * Check the pack's own version, engine range, ruleset, policy profile and modules against the
         * engine contract, and reject an already installed ID/version whose bytes differ.
         *
         * <p>The manifest schema already guaranteed the field types, so casts here cannot fail.
         */
        @SuppressWarnings("unchecked")
        private void compatibility() {
            var values = pack.manifest().values();
            String version = (String) values.get("version");
            version("/version", version);
            String engine = (String) values.get("engineCompatibility");
            if (!range("/engineCompatibility", engine).contains(Versions.version(contract.engineVersion()))) {
                throw new PackException("INCOMPATIBLE_ENGINE", manifestDocument.location("/engineCompatibility"),
                        "Pack requires engine " + engine + "; this engine is " + contract.engineVersion()
                                + " (pack contract " + EngineContract.PACK_CONTRACT + ")");
            }
            var ruleset = (Map<String, Object>) values.get("ruleset");
            if (!contract.rulesetId().equals(ruleset.get("id"))) {
                throw new PackException("INCOMPATIBLE_RULESET", manifestDocument.location("/ruleset/id"),
                        "Ruleset '" + ruleset.get("id") + "' is not provided; this engine provides '"
                                + contract.rulesetId() + "'");
            }
            String rulesetRange = (String) ruleset.get("version");
            if (!range("/ruleset/version", rulesetRange).contains(Versions.version(contract.rulesetVersion()))) {
                throw new PackException("INCOMPATIBLE_RULESET", manifestDocument.location("/ruleset/version"),
                        "Pack requires ruleset " + rulesetRange + "; this engine provides " + contract.rulesetVersion());
            }
            if (!contract.policyProfiles().contains((String) ruleset.get("policyProfile"))) {
                throw new PackException("INCOMPATIBLE_RULESET", manifestDocument.location("/ruleset/policyProfile"),
                        "Policy profile must be one of " + new TreeSet<>(contract.policyProfiles()));
            }
            var modules = (List<Map<String, Object>>) values.getOrDefault("modules", List.of());
            for (int index = 0; index < modules.size(); index++) {
                String module = (String) modules.get(index).get("id");
                if (!contract.modules().contains(module)) {
                    throw new PackException("UNSUPPORTED_MODULE", manifestDocument.location("/modules/" + index + "/id"),
                            "Module '" + module + "' is not supported by engine " + contract.engineVersion()
                                    + " (pack contract " + EngineContract.PACK_CONTRACT + ")");
                }
            }
            // One ID and version must always mean the same bytes; changed content needs a new release.
            var installed = catalog.installedHash(pack.manifest().id(), version);
            if (installed.isPresent() && !installed.get().equals(pack.contentHash())) {
                throw new PackException("HASH_CONFLICT", manifestDocument.location("/version"),
                        "Pack " + pack.manifest().id() + " " + version + " is installed with hash " + installed.get()
                                + " but these files hash to " + pack.contentHash() + "; publish a new version");
            }
        }

        /** Parse an exact SemVer manifest value; malformed text is a located VERSION_SYNTAX error. */
        private Versions.Version version(String pointer, String text) {
            try {
                return Versions.version(text);
            } catch (IllegalArgumentException exception) {
                throw new PackException("VERSION_SYNTAX", manifestDocument.location(pointer), exception.getMessage());
            }
        }

        /** Parse a manifest version range; malformed text is a located VERSION_SYNTAX error. */
        private Versions.Range range(String pointer, String text) {
            try {
                return Versions.range(text);
            } catch (IllegalArgumentException exception) {
                throw new PackException("VERSION_SYNTAX", manifestDocument.location(pointer), exception.getMessage());
            }
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
                // Dependencies pin one exact release; a range would let resolved content drift.
                String version = (String) dependency.get("version");
                version(pointer + "/version", version);
                String hash = (String) dependency.get("hash");
                var exports = catalog.exports(id, version, hash);
                var installed = catalog.installedHash(id, version);
                if (exports.isEmpty() && installed.isPresent() && !installed.get().equals(hash)) {
                    throw new PackException("HASH_CONFLICT", manifestDocument.location(pointer + "/hash"),
                            "Dependency " + id + " " + version + " is installed with hash " + installed.get()
                                    + " but this manifest pins " + hash);
                }
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
                        "'" + ref + "' is a " + kind + "; expected " + new TreeSet<>(kinds));
            }
        }

        /** Range of a rule-defined attribute, or null. */
        @Override
        public int[] attributeRange(String id) {
            return attributes.get(id);
        }

        /** Maximum of a rule-defined resource, or null. */
        @Override
        public Integer resourceMax(String id) {
            return resources.get(id);
        }

        /**
         * Maximum rank of a resolved skill: the local maxRank (default 2), or the global rank cap 10
         * for external skills whose data is not visible here. Out-of-range local values fail in skill().
         */
        @Override
        public int skillMax(String ref) {
            Definition skill = byId.get(ref);
            if (skill == null) {
                return 10;
            }
            return skill.data().get("maxRank") instanceof Number n ? n.intValue() : 2;
        }

        /**
         * Objective IDs of a local quest read from raw data, so quests may refer to each other in any
         * file order; malformed objectives are rejected when that quest itself is validated.
         */
        @Override
        public Set<String> objectives(String questRef) {
            Definition quest = byId.get(questRef);
            if (quest == null) {
                return null;
            }
            Set<String> ids = new HashSet<>();
            if (quest.data().get("objectives") instanceof List<?> list) {
                for (Object objective : list) {
                    if (objective instanceof Map<?, ?> map && map.get("id") instanceof String id) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        }

        /** Check the manifest entrypoint's location and creation profile references. */
        private void entrypoint() {
            var entry = new Fields(manifestDocument, "/entrypoint", pack.manifest().values().get("entrypoint"), this);
            entry.text("situation", true);
            entry.ref("location", LOCATION, true);
            entry.ref("creationProfile", Set.of("creation"), true);
            entry.close();
        }

        /** Whether a rule definition refers to vocabularies that other rule definitions declare. */
        private static boolean readsVocabulary(Definition definition) {
            return Set.of("derivedStats", "rest").contains(definition.data().get("ruleType"));
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
                case "condition" -> condition(f, definition);
                case "creation" -> creation(f);
                case "quest" -> quest(f, definition);
                default -> throw new PackException("UNSUPPORTED_KIND", document.location(definition.pointer() + "/kind"),
                        "Kind " + definition.kind() + " has no semantic schema in this pack contract revision");
            }
            f.close();
        }

        /** Register the rule vocabularies; unknown rule types fail closed rather than being ignored. */
        private void rule(Fields f) {
            String type = f.choice("ruleType", Set.of("attributes", "resources", "rarityTiers",
                    "derivedStats", "equipment", "rest"), null);
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
                        int max = r.integer("max", 1, VALUE_MAX, null);
                        r.close();
                        if (resources.putIfAbsent(id, max) != null) {
                            throw r.fail("DUPLICATE", "id", "Resource ID already defined");
                        }
                    }
                }
                case "derivedStats" -> derivedStats(f);
                case "equipment" -> equipment(f);
                case "rest" -> rest(f);
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

        /**
         * Rest policies (docs/10 section 8): how long a rest takes, where it may be taken (a location
         * tag), how likely an interruption is, which resources it restores and which conditions it
         * removes. Every policy must change something, and a resource is restored at most once, so a
         * rest can never be an empty action that only passes time.
         */
        private void rest(Fields f) {
            for (Fields policy : f.objects("policies", 1, 16)) {
                String id = policy.id("id", true);
                policy.text("name", true);
                policy.integer("minutes", 1, WorldClock.MAX_MINUTES, null);
                policy.id("locationTag", false);
                policy.integer("interruptionChancePercent", 0, 99, 0);
                Set<String> restored = new HashSet<>();
                for (Fields restores : policy.objects("restores", 0, 8)) {
                    String resource = restores.id("resource", true);
                    if (!resources.containsKey(resource)) {
                        throw restores.fail("REFERENCE_UNKNOWN", "resource", "Unknown resource '" + resource
                                + "'; defined resources: " + new TreeSet<>(resources.keySet()));
                    }
                    if (!restored.add(resource)) {
                        throw restores.fail("DUPLICATE", "resource", "Duplicate restoration of one resource");
                    }
                    restores.integer("amount", 1, VALUE_MAX, null);
                    restores.close();
                }
                List<String> removed = policy.refs("removesConditions", CONDITION, 8);
                if (restored.isEmpty() && removed.isEmpty()) {
                    throw policy.fail("restores", "A rest policy must restore a resource or remove a condition");
                }
                policy.close();
                if (!restPolicies.add(id)) {
                    throw policy.fail("DUPLICATE", "id", "Rest policy ID already defined");
                }
            }
        }

        /**
         * Derived statistics: pure functions of attribute values and skill ranks (docs/10 section 1).
         * A formula may read neither dice nor current resource values, so a derived value is
         * reproducible from the stored base values and never depends on a draw or on spent
         * resources. Its proven interval must lie inside the declared bounds, so a consumer can
         * rely on the range without clamping; use {@code clamp} in the formula to narrow it.
         */
        private void derivedStats(Fields f) {
            for (Fields stat : f.objects("stats", 1, 16)) {
                String id = stat.id("id", true);
                stat.text("name", true);
                int min = stat.integer("min", -VALUE_MAX, VALUE_MAX, 0);
                int max = stat.integer("max", -VALUE_MAX, VALUE_MAX, null);
                if (max < min) {
                    throw stat.fail("max", "Maximum must not be below minimum");
                }
                if (attributes.containsKey(id) || resources.containsKey(id)) {
                    throw stat.fail("DUPLICATE", "id", "Derived statistic ID is already an attribute or resource");
                }
                Object formula = stat.value("formula", true);
                if (readsAtom(formula, "dice") || readsAtom(formula, "resource")) {
                    throw stat.fail("formula", "A derived statistic may read only attributes and skills");
                }
                var result = Expressions.analyze(stat.document(), stat.at("formula"), formula, Expressions.Scope.ACTOR,
                        Expressions.Type.NUMBER, this);
                if (result.min() < min || result.max() > max) {
                    throw stat.fail("RANGE", "formula", "Formula may range " + result.min() + ".." + result.max()
                            + "; declared " + min + ".." + max);
                }
                stat.close();
                if (!derivedStats.add(id)) {
                    throw stat.fail("DUPLICATE", "id", "Derived statistic ID already defined");
                }
            }
        }

        /** Whether any node of an expression tree is the named atom; the tree is already size-bounded. */
        private static boolean readsAtom(Object node, String atom) {
            if (node instanceof Map<?, ?> map) {
                return map.entrySet().stream().anyMatch(entry -> atom.equals(entry.getKey()) || readsAtom(entry.getValue(), atom));
            }
            return node instanceof List<?> list && list.stream().anyMatch(child -> readsAtom(child, atom));
        }

        /**
         * Equipment vocabulary: the hands an actor has and the named slots with a capacity and
         * optional compatibility tags. Only one equipment rule may exist per pack, because two
         * would have to be merged by an unstated precedence.
         */
        private void equipment(Fields f) {
            if (!equipmentSlots.isEmpty()) {
                throw f.fail("DUPLICATE", "slots", "A pack may define one equipment rule");
            }
            handCapacity = f.integer("hands", 1, MAX_HANDS, DEFAULT_HANDS);
            for (Fields slot : f.objects("slots", 1, 16)) {
                String id = slot.id("id", true);
                slot.text("name", true);
                int capacity = slot.integer("capacity", 1, 8, 1);
                var accepts = Set.copyOf(slot.ids("accepts", 16));
                slot.close();
                if (equipmentSlots.putIfAbsent(id, new EquipmentSlot(capacity, accepts)) != null) {
                    throw slot.fail("DUPLICATE", "id", "Equipment slot ID already defined");
                }
            }
        }

        /** Slot and hand usage of the items a creation profile equips at the start. */
        private static final class StartingLoadout {
            private final Map<String, Integer> slotUse = new HashMap<>();
            private int hands;
            private final List<String> items = new ArrayList<>();
        }

        /**
         * Check that one starting item can be worn together with those already equipped by the same
         * profile. Only local definitions are comparable here; an external item is rechecked against the
         * pinned closure when the campaign is created. The diagnostic lists every item involved so the
         * author sees the conflict, not just the last item read (docs/09 section 10).
         */
        private void startingEquipment(Fields loadout, String reference, StartingLoadout worn) {
            Definition definition = byId.get(reference);
            if (definition == null) {
                return;
            }
            // Equippable items never stack (checked on the item), so the stack-limit check above already
            // limits an equipped grant to quantity 1.
            String slot = (String) definition.data().get("slot");
            if (slot == null) {
                throw loadout.fail("equipped", "Item '" + reference + "' has no equipment slot");
            }
            var slotRule = equipmentSlots.get(slot);
            if (slotRule == null) {
                // The item definition itself reports the unknown slot; this is only its starting use.
                return;
            }
            worn.items.add(reference);
            int hands = definition.data().get("hands") instanceof Number number ? number.intValue() : 0;
            if (worn.slotUse.merge(slot, 1, Integer::sum) > slotRule.capacity()) {
                throw loadout.fail("CONSTRAINT_CONFLICT", "equipped", "Starting loadout overfills slot '" + slot
                        + "'; equipped items: " + worn.items);
            }
            worn.hands += hands;
            if (worn.hands > handCapacity) {
                throw loadout.fail("CONSTRAINT_CONFLICT", "equipped", "Starting loadout needs " + worn.hands
                        + " hands; the pack's actors have " + handCapacity + "; equipped items: " + worn.items);
            }
        }

        /** Compiled facts of one equipment slot: how many items fit and which item tags it accepts (empty: any). */
        private record EquipmentSlot(int capacity, Set<String> accepts) {
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
                // Travel time along this link; zero keeps the move instantaneous (docs/10 section 2).
                link.integer("minutes", 0, WorldClock.MAX_MINUTES, 0);
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

        /**
         * Item: type, stack and equipment policy, validated rarity tier, appearance identity and plain
         * integer properties. An item is equippable exactly when it names a {@code slot}, which must be
         * one the pack's equipment rule defines (docs/09 section 10); {@code hands} says how many of the
         * actor's hands it occupies while equipped and {@code tags} are matched against the slot's
         * accepted tags. Rarity is a tier reference only: it never reads the appearance.
         */
        private void item(Fields f) {
            f.text("name", true);
            f.text("description", true);
            f.choice("itemType", ITEM_TYPES, null);
            int maxStack = f.integer("maxStack", 1, 9999, 1);
            String slot = f.id("slot", false);
            int hands = f.integer("hands", 0, MAX_HANDS, 0);
            List<String> tags = f.ids("tags", 16);
            if (slot == null && hands > 0) {
                throw f.fail("hands", "An item that occupies hands must name the equipment slot it is carried in");
            }
            if (slot != null && maxStack != 1) {
                // An equipment assignment names one item instance, so a stack cannot be worn.
                throw f.fail("maxStack", "An equippable item must not stack");
            }
            if (slot != null) {
                EquipmentSlot definition = equipmentSlots.get(slot);
                if (definition == null) {
                    throw f.fail("REFERENCE_UNKNOWN", "slot", "Unknown equipment slot '" + slot
                            + "'; defined slots: " + new TreeSet<>(equipmentSlots.keySet()));
                }
                if (hands > handCapacity) {
                    throw f.fail("RANGE", "hands", "Item needs " + hands + " hands; the pack's actors have " + handCapacity);
                }
                if (!definition.accepts().isEmpty() && tags.stream().noneMatch(definition.accepts()::contains)) {
                    throw f.fail("CONSTRAINT_CONFLICT", "tags", "Slot '" + slot + "' accepts only items tagged "
                            + new TreeSet<>(definition.accepts()));
                }
            }
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
            // Trade valuation (P10.2): explicit units, never derived from rarity, artwork or prose. An item
            // without a value cannot be priced, so the trade rule refuses it instead of guessing.
            f.integer("value", 1, VALUE_MAX, 0);
            Fields use = f.object("use", false);
            if (use != null) {
                useContract(use);
            }
            Fields appearance = f.object("appearance", true);
            String appearanceId = appearance.id("id", true);
            appearance.text("summary", true);
            appearance.close();
            if (!appearances.add(appearanceId)) {
                throw appearance.fail("DUPLICATE", "id", "Appearance identity already used by another item");
            }
            f.text("provenance", false);
        }

        /**
         * Item use contract (P10.2): whether one unit is consumed, who may be targeted and the effect
         * program that runs when the item is used. The grammar, bounds and node budget are exactly those
         * of an ability's effects, so a potion and an ability share one validated effect language.
         */
        private void useContract(Fields use) {
            use.bool("consumes", true);
            Fields target = use.object("target", false);
            if (target != null) {
                target.choice("policy", TARGET_POLICIES, null);
                target.integer("maxTargets", 1, 8, 1);
                target.close();
            }
            effectNodes = 0;
            effects(use, "effects", 1, null, 1, null);
            use.close();
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

        /**
         * Condition: identity, stacking policy, duration, bounded attribute modifiers and an optional
         * per-round tick effect list. Conditions applied by ticks become condition-graph edges, so a
         * condition can never (transitively) re-apply itself every round.
         */
        private void condition(Fields f, Definition self) {
            f.text("name", true);
            f.text("description", true);
            String stacking = f.choice("stacking", Set.of("none", "refresh", "stack"), "refresh");
            f.integer("durationRounds", 1, 10_000, 1);
            // Only a stacking condition can hold several stacks; the cap keeps modifier totals bounded.
            int maxStacks = f.integer("maxStacks", 1, MAX_CONDITION_STACKS, stacking.equals("stack") ? 3 : 1);
            if (!stacking.equals("stack") && maxStacks != 1) {
                throw f.fail("maxStacks", "Only a stacking condition may allow more than one stack");
            }
            Set<String> modified = new HashSet<>();
            for (Fields modifier : f.objects("modifiers", 0, 8)) {
                String attribute = modifier.id("attribute", true);
                if (!attributes.containsKey(attribute)) {
                    throw modifier.fail("REFERENCE_UNKNOWN", "attribute", "Unknown attribute '" + attribute + "'");
                }
                if (!modified.add(attribute)) {
                    throw modifier.fail("DUPLICATE", "attribute", "Attribute already modified by this condition");
                }
                // Attribute ranges are at most 0..100, so a larger modifier could only saturate.
                modifier.integer("amount", -100, 100, null);
                modifier.close();
            }
            conditionGraph.node(self.id());
            effectNodes = 0;
            effects(f, "tick", 0, null, 1, self.id());
        }

        /**
         * Ability: prerequisites (ability prerequisites become cycle-checked graph edges), costs,
         * target policy and a bounded effect tree of at most {@link #EFFECT_NODES} nodes.
         */
        private void ability(Fields f, Definition self) {
            abilityGraph.node(self.id());
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
                List<String> required = prerequisites.refs("abilities", ABILITY, 16);
                for (int index = 0; index < required.size(); index++) {
                    if (required.get(index).equals(self.id())) {
                        throw prerequisites.fail("abilities", "An ability cannot require itself");
                    }
                    abilityGraph.edge(self.id(), required.get(index), prerequisites.document()
                            .location(PackDocument.child(prerequisites.at("abilities"), String.valueOf(index))));
                }
                prerequisites.close();
            }
            Set<String> costed = new HashSet<>();
            for (Fields cost : f.objects("costs", 0, 8)) {
                String resource = cost.id("resource", true);
                if (!resources.containsKey(resource)) {
                    throw cost.fail("REFERENCE_UNKNOWN", "resource", "Unknown resource '" + resource
                            + "'; defined resources: " + new TreeSet<>(resources.keySet()));
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
            effectNodes = 0;
            effects(f, "effects", 1, null, 1, null);
        }

        /**
         * Validate an effect list.
         *
         * @param f object holding the list
         * @param key list field name
         * @param min minimum entries (the global node budget bounds the maximum)
         * @param parentTiming timing of the enclosing conditional, or null at the top level
         * @param depth conditional nesting level, 1 at the top level
         * @param owner condition whose ticks hold this list (for cycle edges), or null for abilities
         */
        private void effects(Fields f, String key, int min, String parentTiming, int depth, String owner) {
            for (Fields effect : f.objects(key, min, EFFECT_NODES)) {
                effect(effect, parentTiming, depth, owner);
            }
        }

        /**
         * Validate one effect node: its type, timing, expression-valued amount and the references the
         * type requires. A {@code conditional} node branches on an actor boolean expression; nested
         * nodes inherit and must agree with its timing, so a node cannot resolve before its own guard.
         *
         * @throws PackException LIMIT when the node budget or conditional depth is exceeded
         */
        private void effect(Fields e, String parentTiming, int depth, String owner) {
            if (++effectNodes > EFFECT_NODES) {
                throw e.fail("LIMIT", null, "More than " + EFFECT_NODES + " effect nodes in one list");
            }
            String type = e.choice("type", EFFECT_TYPES, null);
            String timing = e.choice("timing", Set.of("pre", "post"), parentTiming == null ? "post" : parentTiming);
            if (parentTiming != null && !timing.equals(parentTiming)) {
                throw e.fail("timing", "A nested effect must use its conditional's timing '" + parentTiming + "'");
            }
            if (type.equals("conditional")) {
                if (depth > EFFECT_DEPTH) {
                    throw e.fail("LIMIT", null, "Conditional effects nest deeper than " + EFFECT_DEPTH + " levels");
                }
                Expressions.analyze(e.document(), e.at("when"), e.value("when", true), Expressions.Scope.ACTOR,
                        Expressions.Type.BOOLEAN, this);
                effects(e, "then", 1, timing, depth + 1, owner);
                effects(e, "else", 0, timing, depth + 1, owner);
                e.close();
                return;
            }
            e.choice("target", Set.of("self", "target", "targets"), "target");
            boolean amount = type.equals("damage") || type.equals("heal") || type.equals("modifyResource");
            if (amount) {
                // Amounts are expressions; their proven bounds must stay inside the value bound,
                // and only resource modification may be negative.
                var result = Expressions.analyze(e.document(), e.at("amount"), e.value("amount", true),
                        Expressions.Scope.ACTOR, Expressions.Type.NUMBER, this);
                long low = type.equals("modifyResource") ? -VALUE_MAX : 0;
                if (result.min() < low || result.max() > VALUE_MAX) {
                    throw e.fail("RANGE", "amount", "Amount may range " + result.min() + ".." + result.max()
                            + "; allowed " + low + ".." + VALUE_MAX);
                }
            }
            if (type.equals("applyCondition") || type.equals("removeCondition")) {
                String condition = e.ref("condition", CONDITION, true);
                if (owner != null && type.equals("applyCondition")) {
                    conditionGraph.edge(owner, condition, e.document().location(e.at("condition")));
                }
            }
            if (type.equals("modifyResource")) {
                String resource = e.id("resource", true);
                if (!resources.containsKey(resource)) {
                    throw e.fail("REFERENCE_UNKNOWN", "resource", "Unknown resource '" + resource + "'");
                }
            }
            if (type.equals("createItem")) {
                e.ref("item", ITEM, true);
                // Units created per recipient; the resolver splits them into stacks (docs/10 section 3).
                e.integer("quantity", 1, MAX_CREATED_QUANTITY, 1);
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
            for (var entry : f.intRefs(key, 0, 10, LIST_MAX, SKILL).entrySet()) {
                String pointer = f.keyPointer(key, entry.getKey());
                // Import sees only dependency export kinds. Campaign loading checks the actual
                // pinned cap; until then use the same global bound as other external skill refs.
                int max = skillMax(entry.getKey());
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
                for (String key : archetype.intRefs("skills", 0, 10, LIST_MAX, SKILL).keySet()) {
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
            var worn = new StartingLoadout();
            for (Fields loadout : f.objects("startingItems", 0, LIST_MAX)) {
                String item = loadout.ref("item", ITEM, true);
                int quantity = loadout.integer("quantity", 1, 9999, 1);
                var definition = byId.get(item);
                // External items carry no local stack policy here; only local definitions are comparable.
                if (definition != null && definition.data().get("maxStack") instanceof Number stack
                        && quantity > stack.intValue()) {
                    throw loadout.fail("RANGE", "quantity", "Quantity exceeds the item's maxStack " + stack);
                }
                if (loadout.bool("equipped", false)) {
                    startingEquipment(loadout, item, worn);
                }
                loadout.close();
            }
            f.refs("startingAbilities", ABILITY, LIST_MAX);
        }

        /**
         * Quest: identity, issuer, discovery state, world-scope availability, objectives and outcomes.
         * Every condition is a bounded WORLD boolean expression. References to other quests' status or
         * objectives become quest-graph edges, so no quest can (transitively) wait on itself;
         * references to the quest's own objectives are the normal way outcomes resolve and are kept
         * out of the graph.
         */
        private void quest(Fields f, Definition self) {
            questGraph.node(self.id());
            f.text("name", true);
            f.text("summary", true);
            f.choice("visibility", VISIBILITY, "public");
            f.choice("initialStatus", Set.of("undiscovered", "available"), "undiscovered");
            f.ref("issuer", Set.of("npc"), false);
            f.ref("faction", FACTION, false);
            f.ids("tags", 32);
            if (f.has("availableWhen")) {
                questCondition(f, "availableWhen", self);
            }
            Set<String> objectiveIds = new HashSet<>();
            for (Fields objective : f.objects("objectives", 1, 32)) {
                if (!objectiveIds.add(objective.id("id", true))) {
                    throw objective.fail("DUPLICATE", "id", "Duplicate objective ID");
                }
                objective.text("description", true);
                objective.bool("optional", false);
                questCondition(objective, "when", self);
                objective.close();
            }
            Set<String> outcomeIds = new HashSet<>();
            boolean success = false;
            for (Fields outcome : f.objects("outcomes", 1, 8)) {
                if (!outcomeIds.add(outcome.id("id", true))) {
                    throw outcome.fail("DUPLICATE", "id", "Duplicate outcome ID");
                }
                success |= outcome.choice("kind", Set.of("success", "failure", "alternative"), null).equals("success");
                outcome.text("description", false);
                questCondition(outcome, "when", self);
                Fields settlement = outcome.object("settlement", false);
                if (settlement != null) {
                    int units = 0;
                    for (Fields reward : settlement.objects("rewards", 0, 8)) {
                        reward.ref("item", ITEM, true);
                        units += reward.integer("quantity", 1, 9999, 1);
                        reward.close();
                        // One outcome settles in a single commit, which bounds the units it can create (P10.3).
                        if (units > MAX_QUEST_REWARD_UNITS) {
                            throw settlement.fail("LIMIT", "rewards", "An outcome rewards at most "
                                    + MAX_QUEST_REWARD_UNITS + " units in total");
                        }
                    }
                    settlement.texts("consequences", 8);
                    settlement.close();
                }
                outcome.close();
            }
            // A quest the player can never succeed at is an authoring error, not a design choice.
            if (!success) {
                throw f.fail("outcomes", "A quest needs at least one success outcome");
            }
        }

        /** Analyze one quest condition and record its references to other quests as graph edges. */
        private void questCondition(Fields f, String key, Definition self) {
            var result = Expressions.analyze(f.document(), f.at(key), f.value(key, true), Expressions.Scope.WORLD,
                    Expressions.Type.BOOLEAN, this);
            for (var requirement : result.requirements()) {
                if (!requirement.quest().equals(self.id())) {
                    questGraph.edge(self.id(), requirement.quest(), f.document().location(requirement.pointer()));
                }
            }
        }

        /**
         * Compile the generation policy file (docs/09 sections 8 and 11) into a {@link GenerationPolicy}.
         *
         * <p>Categories are opt-in: an absent category stays disabled. Every listed template, required
         * entity and fixed entity must be an authored local definition, and every fixed field must be an
         * authored field, so constraints cannot silently point at nothing. An excluded tag that a
         * bounded category allows, or that a required entity carries, is a CONSTRAINT_CONFLICT: the
         * author's constraints must be satisfiable before any generator runs.
         *
         * @throws PackException SCHEMA, RANGE, REFERENCE_UNKNOWN, DUPLICATE or CONSTRAINT_CONFLICT
         */
        private GenerationPolicy generationPolicy() {
            String path = (String) pack.manifest().values().get("generationPolicy");
            PackDocument document = pack.documents().get(path);
            if (document == null) {
                throw new PackException("SCHEMA", manifestDocument.location("/generationPolicy"),
                        "The generation policy must be a structured JSON or YAML file");
            }
            Fields root = new Fields(document, "", document.value(), this);
            Map<String, GenerationPolicy.Category> categories = new HashMap<>();
            Fields listed = root.object("categories", false);
            if (listed != null) {
                // Sorted so the first reported problem does not depend on hash order.
                for (String name : new TreeSet<>(GenerationPolicy.CATEGORIES)) {
                    Fields category = listed.object(name, false);
                    if (category != null) {
                        categories.put(name, category(category, name));
                    }
                }
                listed.close();
            }
            Set<String> required = new HashSet<>();
            Map<String, Set<String>> fixed = new HashMap<>();
            Set<String> excluded = Set.of();
            Fields constraints = root.object("constraints", false);
            if (constraints != null) {
                List<String> requiredRefs = constraints.refs("required", ALL_KINDS, LIST_MAX);
                for (int index = 0; index < requiredRefs.size(); index++) {
                    local(constraints, PackDocument.child(constraints.at("required"), String.valueOf(index)),
                            requiredRefs.get(index));
                }
                required.addAll(requiredRefs);
                for (Fields entry : constraints.objects("fixed", 0, LIST_MAX)) {
                    String entity = entry.ref("entity", ALL_KINDS, true);
                    local(entry, entry.at("entity"), entity);
                    List<String> fields = entry.texts("fields", 32);
                    if (fields.isEmpty()) {
                        throw entry.fail("fields", "List at least one fixed field");
                    }
                    for (String field : fields) {
                        // Fixing protects an authored value; a name that is not authored is a typo.
                        if (!byId.get(entity).data().containsKey(field)) {
                            throw entry.fail("REFERENCE_UNKNOWN", "fields", "'" + entity + "' has no authored field '" + field + "'");
                        }
                    }
                    if (fixed.putIfAbsent(entity, Set.copyOf(fields)) != null) {
                        throw entry.fail("DUPLICATE", "entity", "Fixed fields of '" + entity + "' are already listed");
                    }
                    entry.close();
                }
                excluded = Set.copyOf(constraints.ids("excludedTags", LIST_MAX));
                constraints.close();
            }
            root.close();
            conflicts(document, categories, required, excluded);
            return new GenerationPolicy(categories, required, fixed, excluded);
        }

        /** Compile one category policy; only bounded categories carry budgets, tags, templates and ranges. */
        private GenerationPolicy.Category category(Fields f, String name) {
            String mode = f.choice("mode", Set.of("disabled", "catalogOnly", "bounded"), null);
            if (!mode.equals("bounded")) {
                f.close();
                return mode.equals("disabled") ? GenerationPolicy.Category.DISABLED
                        : new GenerationPolicy.Category(GenerationPolicy.Mode.CATALOG_ONLY, 0, 0, Set.of(), Set.of(), Map.of());
            }
            int perScene = f.integer("maxNewPerScene", 1, 8, null);
            int perCampaign = f.integer("maxNewPerCampaign", 1, 1000, null);
            if (perCampaign < perScene) {
                throw f.fail("RANGE", "maxNewPerCampaign", "Campaign budget must not be below the scene budget");
            }
            List<String> tags = f.ids("tags", 32);
            List<String> templates = f.refs("templates", Set.of(CATEGORY_KINDS.get(name)), LIST_MAX);
            Map<String, GenerationPolicy.Range> ranges = new HashMap<>();
            Object raw = f.value("ranges", false);
            if (raw != null) {
                if (!(raw instanceof Map<?, ?> map) || map.size() > 16) {
                    throw f.fail("ranges", "Expected object with at most 16 entries");
                }
                for (Object key : map.keySet()) {
                    String property = (String) key;
                    String pointer = f.keyPointer("ranges", property);
                    if (!Fields.ID.matcher(property).matches()) {
                        throw new PackException("SCHEMA", f.document().location(pointer), "Expected a lowercase stable identifier key");
                    }
                    Fields range = new Fields(f.document(), pointer, map.get(key), this);
                    int min = range.integer("min", -VALUE_MAX, VALUE_MAX, null);
                    int max = range.integer("max", -VALUE_MAX, VALUE_MAX, null);
                    if (max < min) {
                        throw range.fail("RANGE", "max", "Range maximum is below its minimum");
                    }
                    range.close();
                    ranges.put(property, new GenerationPolicy.Range(min, max));
                }
            }
            f.close();
            return new GenerationPolicy.Category(GenerationPolicy.Mode.BOUNDED, perScene, perCampaign,
                    Set.copyOf(tags), Set.copyOf(templates), ranges);
        }

        /** Constraints govern this pack's own content; dependency content is constrained by its own pack. */
        private void local(Fields f, String pointer, String ref) {
            if (ref.indexOf('#') >= 0) {
                throw new PackException("SCHEMA", f.document().location(pointer),
                        "Constraints may only name definitions of this pack");
            }
        }

        /** Reject excluded tags that a bounded category allows or a required entity carries. */
        private void conflicts(PackDocument document, Map<String, GenerationPolicy.Category> categories,
                Set<String> required, Set<String> excluded) {
            for (String name : new TreeSet<>(categories.keySet())) {
                for (String tag : new TreeSet<>(categories.get(name).tags())) {
                    if (excluded.contains(tag)) {
                        throw new PackException("CONSTRAINT_CONFLICT",
                                document.location("/categories/" + name + "/tags"),
                                "Tag '" + tag + "' is allowed for " + name + " but excluded by constraints");
                    }
                }
            }
            for (String entity : new TreeSet<>(required)) {
                if (byId.get(entity).data().get("tags") instanceof List<?> tags) {
                    for (Object tag : tags) {
                        if (excluded.contains(tag)) {
                            throw new PackException("CONSTRAINT_CONFLICT", document.location("/constraints/required"),
                                    "Required '" + entity + "' carries excluded tag '" + tag + "'");
                        }
                    }
                }
            }
        }

        /**
         * Warn about local locations that no authored link chain reaches from the entrypoint. This is a
         * heuristic, not a guarantee: move effects, unlocks and generated links are not modeled, so
         * locked links still count as paths and a warning never blocks compilation.
         */
        @SuppressWarnings("unchecked")
        private void reachability() {
            var entrypoint = (Map<String, Object>) pack.manifest().values().get("entrypoint");
            String start = (String) entrypoint.get("location");
            if (start.indexOf('#') >= 0) {
                return;
            }
            Set<String> reached = new HashSet<>(Set.of(start));
            Deque<String> queue = new ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                if (byId.get(queue.poll()).data().get("links") instanceof List<?> links) {
                    for (Object link : links) {
                        // External targets are skipped: their links live in another pack.
                        if (((Map<String, Object>) link).get("to") instanceof String to && byId.containsKey(to)
                                && reached.add(to)) {
                            queue.add(to);
                        }
                    }
                }
            }
            for (Definition definition : byId.values()) {
                if (definition.kind().equals("location") && !reached.contains(definition.id())) {
                    warnings.add(new PackWarning("UNREACHABLE_LOCATION",
                            pack.documents().get(definition.file()).location(definition.pointer()),
                            "Location '" + definition.id() + "' is not linked from the entrypoint '" + start
                                    + "' (heuristic: move effects and unlocks are not modeled)"));
                }
            }
        }
    }
}
