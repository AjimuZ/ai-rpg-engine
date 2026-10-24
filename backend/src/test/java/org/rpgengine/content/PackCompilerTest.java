package org.rpgengine.content;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Semantic validation tests: parse a small valid pack, then break one fact at a time. */
class PackCompilerTest {
    /** Construct an independent corpus; no application context or storage is needed. */
    PackCompilerTest() {
    }

    /** Files of the valid reference pack, as JSON text so single facts can be replaced by string edits. */
    static Map<String, String> sources() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("adventure.json", """
                {"schemaVersion":1,"id":"example:harbor","version":"1.0.0","title":"Harbor","summary":"A quiet harbor.",
                "authors":["Example Authors"],"license":"MIT","engineCompatibility":">=0.1.0 <1.0.0",
                "ruleset":{"id":"skirmish","version":">=1.0.0 <2.0.0","policyProfile":"standard"},
                "contentLocale":"en","narrativeMode":"emergent",
                "entrypoint":{"situation":"arrival","location":"harbor","creationProfile":"visitor"},
                "files":[{"path":"rules.json","category":"rules"},{"path":"world.json","category":"locations"},
                {"path":"conditions.json","category":"conditions"},{"path":"skills.json","category":"skills"},
                {"path":"abilities.json","category":"abilities"},{"path":"items.json","category":"items"},
                {"path":"creation.json","category":"creation"},{"path":"factions.json","category":"factions"},
                {"path":"npcs.json","category":"npcs"},{"path":"generation.json","category":"generationPolicy"}],
                "modules":[],"generationPolicy":"generation.json"__DEPENDENCIES__}""");
        files.put("rules.json", envelope("""
                {"id":"rules.attributes","kind":"rule","data":{"ruleType":"attributes","attributes":[{"id":"might","name":"Might","min":0,"max":5}]}},
                {"id":"rules.resources","kind":"rule","data":{"ruleType":"resources","resources":[{"id":"stamina","name":"Stamina","max":10}]}},
                {"id":"rules.rarity","kind":"rule","data":{"ruleType":"rarityTiers","tiers":[{"id":"common","name":"Common","token":"rarity-common"}]}}"""));
        files.put("world.json", envelope("""
                {"id":"harbor","kind":"location","data":{"name":"Harbor","description":"Docks.","links":[{"to":"market","accessible":true}]}},
                {"id":"market","kind":"location","data":{"name":"Market","description":"Stalls.","links":[{"to":"harbor","accessible":true}]}}"""));
        files.put("conditions.json", envelope("""
                {"id":"dazed","kind":"condition","data":{"name":"Dazed","description":"Unsteady."}}"""));
        files.put("skills.json", envelope("""
                {"id":"sailing","kind":"skill","data":{"name":"Sailing","description":"Boats.","attribute":"might","maxRank":3}}"""));
        files.put("abilities.json", envelope("""
                {"id":"shove","kind":"ability","data":{"name":"Shove","description":"Push.",
                "prerequisites":{"skills":[{"skill":"sailing","rank":1}]},"costs":[{"resource":"stamina","amount":2}],
                "target":{"policy":"single"},"effects":[{"type":"damage","amount":3},{"type":"applyCondition","condition":"dazed"}]}}"""));
        files.put("items.json", envelope("""
                {"id":"rope","kind":"item","data":{"name":"Rope","description":"Hemp.","itemType":"tool","maxStack":3,
                "rarity":"common","properties":{"length":10},"appearance":{"id":"rope.hemp","summary":"Coiled hemp rope"}}}"""));
        files.put("creation.json", envelope("""
                {"id":"visitor","kind":"creation","data":{"name":"Visitor","attributeBudget":3,"skillBudget":2,"attributes":["might"],
                "skills":["sailing"],"archetypes":[{"id":"sailor","name":"Sailor","attributes":{"might":2},"skills":{"sailing":2}}],
                "startingItems":[{"item":"rope","quantity":1}],"startingAbilities":["shove"]}}"""));
        files.put("factions.json", envelope("""
                {"id":"guild","kind":"faction","data":{"name":"Guild","description":"Dockers."}}"""));
        files.put("npcs.json", envelope("""
                {"id":"keeper","kind":"npc","data":{"name":"Keeper","role":"Harbormaster","location":"harbor","faction":"guild",
                "profile":{"attributes":{"might":1},"skills":{"sailing":1},"abilities":["shove"]}}}"""));
        files.put("generation.json", "{}");
        return files;
    }

    /** Wrap comma-separated definitions in the contract-1 envelope. */
    static String envelope(String definitions) {
        return "{\"schemaVersion\":1,\"definitions\":[" + definitions + "]}";
    }

    /** Parse the sources, applying one textual replacement to one file; the replacement must match. */
    private static ParsedPack parse(Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, text) -> bytes.put(path, text.replace("__DEPENDENCIES__", "").getBytes(StandardCharsets.UTF_8)));
        return new PackParser().parse(bytes);
    }

    /** Edit one file and compile; fails the test when the edit target is absent so mutations stay honest. */
    private static CompiledPack compile(String file, String from, String to) {
        var files = sources();
        assertTrue(files.get(file).contains(from), "mutation target missing: " + from);
        files.put(file, files.get(file).replace(from, to));
        return new PackCompiler().compile(parse(files));
    }

    /** Assert the first blocking diagnostic code for one mutation. */
    private static void rejects(String code, String file, String from, String to) {
        var error = assertThrows(PackException.class, () -> compile(file, from, to));
        assertEquals(code, error.code(), error.getMessage());
    }

    /** The reference pack compiles and exposes definitions by ID in file order. */
    @Test
    void compilesValidPack() {
        var compiled = new PackCompiler().compile(parse(sources()));
        assertEquals(12, compiled.definitions().size());
        assertEquals("ability", compiled.byId().get("shove").kind());
        assertThrows(UnsupportedOperationException.class, () -> compiled.byId().clear());
    }

    /** IDs are unique across all files and categories must agree with kinds. */
    @Test
    void rejectsDuplicateIdsAndWrongCategory() {
        rejects("DUPLICATE_ID", "factions.json", "\"id\":\"guild\"", "\"id\":\"harbor\"");
        rejects("KIND_MISMATCH", "factions.json", "\"kind\":\"faction\"", "\"kind\":\"npc\"");
    }

    /** References must exist, have the expected kind, and external ones need a declared dependency. */
    @Test
    void rejectsBadReferences() {
        rejects("REFERENCE_UNKNOWN", "npcs.json", "\"location\":\"harbor\"", "\"location\":\"nowhere\"");
        rejects("REFERENCE_KIND", "npcs.json", "\"location\":\"harbor\"", "\"location\":\"guild\"");
        rejects("REFERENCE_UNKNOWN", "npcs.json", "\"location\":\"harbor\"", "\"location\":\"other:pack#dock\"");
        rejects("SCHEMA", "world.json", "\"links\":[{\"to\":\"market\",\"accessible\":true}]", "\"links\":[{\"to\":\"harbor\",\"accessible\":true}]");
    }

    /** Dependencies resolve only through the injected catalog, enabling pack#id references. */
    @Test
    void resolvesDependenciesThroughCatalog() {
        String hash = "a".repeat(64);
        var files = sources();
        files.put("adventure.json", files.get("adventure.json").replace("__DEPENDENCIES__",
                ",\"dependencies\":[{\"id\":\"core:town\",\"version\":\"1.0.0\",\"hash\":\"" + hash + "\"}]"));
        files.put("npcs.json", files.get("npcs.json").replace("\"location\":\"harbor\"", "\"location\":\"core:town#square\""));
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, text) -> bytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        var parsed = new PackParser().parse(bytes);
        var error = assertThrows(PackException.class, () -> new PackCompiler().compile(parsed));
        assertEquals("DEPENDENCY", error.code());
        DependencyCatalog catalog = (id, version, h) -> id.equals("core:town") && h.equals(hash)
                ? Optional.of(Map.of("square", "location", "elder", "npc")) : Optional.empty();
        assertNotNull(new PackCompiler(catalog).compile(parsed).byId().get("keeper"));
        files.put("npcs.json", files.get("npcs.json").replace("core:town#square", "core:town#elder"));
        files.forEach((path, text) -> bytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        var wrongKind = new PackParser().parse(bytes);
        assertEquals("REFERENCE_KIND", assertThrows(PackException.class, () -> new PackCompiler(catalog).compile(wrongKind)).code());
    }

    /** Rarity tiers come from rules and appearance identities cannot be shared between items. */
    @Test
    void validatesRarityAndAppearanceIdentity() {
        rejects("REFERENCE_UNKNOWN", "items.json", "\"rarity\":\"common\"", "\"rarity\":\"mythic\"");
        rejects("SCHEMA", "items.json", "\"rarity\":\"common\",", "");
        var files = sources();
        files.put("items.json", envelope("""
                {"id":"rope","kind":"item","data":{"name":"Rope","description":"Hemp.","itemType":"tool","rarity":"common","appearance":{"id":"a.one","summary":"One"}}},
                {"id":"cord","kind":"item","data":{"name":"Cord","description":"Thin.","itemType":"tool","rarity":"common","appearance":{"id":"a.one","summary":"Same"}}}"""));
        var error = assertThrows(PackException.class, () -> new PackCompiler().compile(parse(files)));
        assertEquals("DUPLICATE", error.code());
    }

    /** Creation budgets, vocabularies and starting stacks are enforced. */
    @Test
    void validatesCreationProfiles() {
        rejects("RANGE", "creation.json", "\"attributes\":{\"might\":2}", "\"attributes\":{\"might\":5}");
        rejects("RANGE", "creation.json", "\"skills\":{\"sailing\":2}", "\"skills\":{\"sailing\":3}");
        rejects("REFERENCE_UNKNOWN", "creation.json", "\"attributes\":[\"might\"]", "\"attributes\":[\"luck\"]");
        rejects("RANGE", "creation.json", "\"quantity\":1", "\"quantity\":4");
        rejects("REFERENCE_UNKNOWN", "adventure.json", "\"creationProfile\":\"visitor\"", "\"creationProfile\":\"ghost\"");
    }

    /** Ability costs, prerequisites and effects use known IDs and reject stray fields. */
    @Test
    void validatesSkillsAndAbilities() {
        rejects("REFERENCE_UNKNOWN", "abilities.json", "\"resource\":\"stamina\"", "\"resource\":\"mana\"");
        rejects("SCHEMA", "abilities.json", "\"rank\":1", "\"rank\":4");
        rejects("SCHEMA", "abilities.json", "{\"type\":\"damage\",\"amount\":3}", "{\"type\":\"damage\",\"amount\":3,\"bonus\":1}");
        rejects("SCHEMA", "abilities.json", "{\"type\":\"damage\",\"amount\":3}", "{\"type\":\"teleport\"}");
        rejects("REFERENCE_UNKNOWN", "skills.json", "\"attribute\":\"might\"", "\"attribute\":\"luck\"");
    }

    /** Unknown mechanical fields and unsupported kinds fail closed. */
    @Test
    void failsClosed() {
        rejects("SCHEMA", "world.json", "\"name\":\"Harbor\"", "\"name\":\"Harbor\",\"damageBonus\":5");
        // Encounters have no semantic schema yet, so a well-formed encounter definition is refused, not ignored.
        var files = sources();
        files.put("adventure.json", files.get("adventure.json").replace("\"conditions.json\",\"category\":\"conditions\"",
                "\"conditions.json\",\"category\":\"encounters\""));
        files.put("conditions.json", files.get("conditions.json").replace("\"kind\":\"condition\"", "\"kind\":\"encounter\""));
        assertEquals("UNSUPPORTED_KIND", failure(files));
    }

    /** The default damage effect of the reference ability, the target of effect mutations. */
    private static final String DAMAGE = "{\"type\":\"damage\",\"amount\":3}";

    /** Add one file and its manifest entry to the reference sources, for categories the base pack lacks. */
    private static Map<String, String> withFile(String path, String category, String text) {
        var files = sources();
        files.put("adventure.json", files.get("adventure.json").replace("{\"path\":\"generation.json\"",
                "{\"path\":\"" + path + "\",\"category\":\"" + category + "\"},{\"path\":\"generation.json\""));
        files.put(path, text);
        return files;
    }

    /** Compile a full source map with the default catalog and return the blocking diagnostic code. */
    private static String failure(Map<String, String> files) {
        return assertThrows(PackException.class, () -> new PackCompiler().compile(parse(files))).code();
    }

    /** Replace the damage amount with an expression and assert the analyzer's diagnostic code. */
    private static void rejectsAmount(String code, String expression) {
        rejects(code, "abilities.json", "\"amount\":3", "\"amount\":" + expression);
    }

    /** Effect amounts are typed, scoped, cost-bounded expressions with proven integer bounds. */
    @Test
    void boundsEffectExpressions() {
        assertNotNull(compile("abilities.json", "\"amount\":3", "\"amount\":{\"add\":[{\"attribute\":\"might\"},"
                + "{\"dice\":{\"count\":2,\"sides\":6}},{\"skill\":\"sailing\"}]}"));
        // A divisor proven positive is accepted; one whose range includes zero is not.
        assertNotNull(compile("abilities.json", "\"amount\":3", "\"amount\":{\"div\":[10,{\"add\":[{\"attribute\":\"might\"},1]}]}"));
        rejectsAmount("DIVISION_BY_ZERO", "{\"div\":[10,{\"attribute\":\"might\"}]}");
        rejectsAmount("OVERFLOW", "{\"mul\":[1000000,1000000]}");
        // Damage cannot be negative: stamina 0..10 subtracted from 0 can reach -10.
        rejectsAmount("RANGE", "{\"sub\":[0,{\"resource\":\"stamina\"}]}");
        rejectsAmount("RANGE", "{\"clamp\":[1,5,2]}");
        rejectsAmount("SCHEMA", "{\"ge\":[1,0]}");
        rejectsAmount("SCHEMA", "{\"visited\":\"harbor\"}");
        rejectsAmount("SCHEMA", "{\"pow\":[2,3]}");
        rejectsAmount("SCHEMA", "1.5");
        rejectsAmount("REFERENCE_UNKNOWN", "{\"attribute\":\"luck\"}");
        String die = "{\"dice\":{\"count\":20,\"sides\":6}}";
        rejectsAmount("COST", "{\"add\":[" + die + "," + die + "," + die + "]}");
        String deep = "1";
        for (int level = 0; level < Expressions.MAX_DEPTH + 1; level++) {
            deep = "{\"add\":[" + deep + ",1]}";
        }
        rejectsAmount("LIMIT", deep);
        // 3 x (1 + 8 x 9) + 1 = 220 nodes at depth 4: too many nodes without being too deep.
        String leaf = "{\"add\":[" + String.join(",", Collections.nCopies(8, "1")) + "]}";
        String mid = "{\"add\":[" + String.join(",", Collections.nCopies(8, leaf)) + "]}";
        rejectsAmount("LIMIT", "{\"add\":[" + mid + "," + mid + "," + mid + "]}");
    }

    /** Conditional effects branch on actor predicates, share timing and respect depth and node budgets. */
    @Test
    void boundsConditionalEffects() {
        String guarded = "{\"type\":\"conditional\",\"when\":{\"ge\":[{\"attribute\":\"might\"},2]},"
                + "\"then\":[{\"type\":\"damage\",\"amount\":2}],\"else\":[{\"type\":\"heal\",\"amount\":1,\"target\":\"self\"}]}";
        assertNotNull(compile("abilities.json", DAMAGE, guarded));
        rejects("SCHEMA", "abilities.json", DAMAGE, "{\"type\":\"conditional\",\"timing\":\"pre\",\"when\":true,"
                + "\"then\":[{\"type\":\"damage\",\"amount\":1,\"timing\":\"post\"}]}");
        rejects("SCHEMA", "abilities.json", DAMAGE, "{\"type\":\"conditional\",\"when\":3,\"then\":[" + DAMAGE + "]}");
        rejects("SCHEMA", "abilities.json", DAMAGE, "{\"type\":\"conditional\",\"when\":true,\"then\":[]}");
        assertNotNull(compile("abilities.json", DAMAGE, nestedConditionals(4)));
        rejects("LIMIT", "abilities.json", DAMAGE, nestedConditionals(5));
        rejects("LIMIT", "abilities.json", DAMAGE, "{\"type\":\"conditional\",\"when\":true,\"then\":["
                + String.join(",", Collections.nCopies(20, DAMAGE)) + "]}");
    }

    /** Wrap the damage effect in the given number of nested conditionals. */
    private static String nestedConditionals(int levels) {
        String effect = DAMAGE;
        for (int level = 0; level < levels; level++) {
            effect = "{\"type\":\"conditional\",\"when\":true,\"then\":[" + effect + "]}";
        }
        return effect;
    }

    /** Version ranges use the closed comparator grammar, including SemVer pre-release precedence. */
    @Test
    void parsesVersionRanges() {
        var range = Versions.range(">=1.0.0 <2.0.0");
        assertTrue(range.contains(Versions.version("1.9.9")));
        assertFalse(range.contains(Versions.version("2.0.0")));
        assertFalse(range.contains(Versions.version("1.0.0-rc.1")));
        assertTrue(Versions.version("1.0.0-alpha.10").compareTo(Versions.version("1.0.0-alpha.9")) > 0);
        assertTrue(Versions.range("1.2.3").contains(Versions.version("1.2.3+build.7")));
        assertThrows(IllegalArgumentException.class, () -> Versions.range("^1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> Versions.range(">=1.0.0 || <0.5.0"));
        assertThrows(IllegalArgumentException.class, () -> Versions.range(""));
        assertThrows(IllegalArgumentException.class, () -> Versions.version("1.0"));
    }

    /** Engine, ruleset, policy profile and module requirements are checked against the engine contract. */
    @Test
    void checksVersionCompatibility() {
        rejects("VERSION_SYNTAX", "adventure.json", "\">=0.1.0 <1.0.0\"", "\"~0.1\"");
        rejects("INCOMPATIBLE_ENGINE", "adventure.json", "\">=0.1.0 <1.0.0\"", "\">=1.0.0 <2.0.0\"");
        rejects("INCOMPATIBLE_RULESET", "adventure.json", "\"id\":\"skirmish\"", "\"id\":\"duel\"");
        rejects("INCOMPATIBLE_RULESET", "adventure.json", "\">=1.0.0 <2.0.0\"", "\">=2.0.0\"");
        rejects("INCOMPATIBLE_RULESET", "adventure.json", "\"policyProfile\":\"standard\"", "\"policyProfile\":\"gritty\"");
        rejects("UNSUPPORTED_MODULE", "adventure.json", "\"modules\":[]", "\"modules\":[{\"id\":\"weather\"}]");
        var files = sources();
        files.put("adventure.json", files.get("adventure.json").replace("\"modules\":[]", "\"modules\":[{\"id\":\"weather\"}]"));
        var contract = new EngineContract("0.1.0", "skirmish", "1.0.0", Set.of("standard"), Set.of("weather"));
        assertNotNull(new PackCompiler(DependencyCatalog.none(), contract).compile(parse(files)));
    }

    /** One pack ID and version must always mean the same bytes, for the pack itself and its dependencies. */
    @Test
    void rejectsHashConflicts() {
        var parsed = parse(sources());
        assertEquals("HASH_CONFLICT", assertThrows(PackException.class,
                () -> new PackCompiler(installed("example:harbor", "0".repeat(64))).compile(parsed)).code());
        // Reinstalling identical bytes under the same version is not a conflict.
        assertNotNull(new PackCompiler(installed("example:harbor", parsed.contentHash())).compile(parsed));
        var files = sources();
        files.put("adventure.json", files.get("adventure.json").replace("__DEPENDENCIES__",
                ",\"dependencies\":[{\"id\":\"core:town\",\"version\":\"1.0.0\",\"hash\":\"" + "a".repeat(64) + "\"}]"));
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, text) -> bytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        var dependent = new PackParser().parse(bytes);
        var error = assertThrows(PackException.class, () -> new PackCompiler(installed("core:town", "b".repeat(64))).compile(dependent));
        assertEquals("HASH_CONFLICT", error.code());
        assertEquals("/dependencies/0/hash", error.location().pointer());
    }

    /** Catalog with no exports that reports one pack ID (any version) as installed with the given hash. */
    private static DependencyCatalog installed(String installedId, String hash) {
        return new DependencyCatalog() {
            @Override
            public Optional<Map<String, String>> exports(String packId, String version, String expected) {
                return Optional.empty();
            }

            @Override
            public Optional<String> installedHash(String packId, String version) {
                return packId.equals(installedId) ? Optional.of(hash) : Optional.empty();
            }
        };
    }

    /** Ability prerequisites and condition ticks may not form cycles; the diagnostic names the path. */
    @Test
    void rejectsDefinitionCycles() {
        var abilities = sources();
        abilities.put("abilities.json", envelope("""
                {"id":"shove","kind":"ability","data":{"name":"Shove","description":"Push.","prerequisites":{"abilities":["brace"]},
                "target":{"policy":"single"},"effects":[{"type":"damage","amount":3}]}},
                {"id":"brace","kind":"ability","data":{"name":"Brace","description":"Hold.","prerequisites":{"abilities":["shove"]},
                "target":{"policy":"self"},"effects":[{"type":"heal","amount":1,"target":"self"}]}}"""));
        var error = assertThrows(PackException.class, () -> new PackCompiler().compile(parse(abilities)));
        assertEquals("CYCLE", error.code());
        assertTrue(error.getMessage().contains("shove -> brace -> shove") || error.getMessage().contains("brace -> shove -> brace"),
                error.getMessage());
        var conditions = sources();
        conditions.put("conditions.json", envelope("""
                {"id":"dazed","kind":"condition","data":{"name":"Dazed","description":"Unsteady.",
                "tick":[{"type":"applyCondition","condition":"reeling","target":"self"}]}},
                {"id":"reeling","kind":"condition","data":{"name":"Reeling","description":"Spinning.",
                "tick":[{"type":"conditional","when":true,"then":[{"type":"applyCondition","condition":"dazed","target":"self"}]}]}}"""));
        assertEquals("CYCLE", failure(conditions));
        // A tick that only damages is fine, and modifiers name rule attributes once each.
        assertNotNull(compile("conditions.json", "\"description\":\"Unsteady.\"", "\"description\":\"Unsteady.\","
                + "\"stacking\":\"stack\",\"durationRounds\":3,\"modifiers\":[{\"attribute\":\"might\",\"amount\":-1}],"
                + "\"tick\":[{\"type\":\"damage\",\"amount\":1,\"target\":\"self\"}]"));
        rejects("REFERENCE_UNKNOWN", "conditions.json", "\"description\":\"Unsteady.\"",
                "\"description\":\"Unsteady.\",\"modifiers\":[{\"attribute\":\"luck\",\"amount\":1}]");
        rejects("DUPLICATE", "conditions.json", "\"description\":\"Unsteady.\"", "\"description\":\"Unsteady.\","
                + "\"modifiers\":[{\"attribute\":\"might\",\"amount\":1},{\"attribute\":\"might\",\"amount\":2}]");
    }

    /** One quest with a configurable availability predicate, its own objective and a success outcome. */
    private static String quest(String id, String availableWhen) {
        return """
                {"id":"%s","kind":"quest","data":{"name":"Ferry","summary":"Cross the bay.","issuer":"keeper",
                "availableWhen":%s,"objectives":[{"id":"rope","description":"Get rope.","when":{"possesses":{"item":"rope"}}}],
                "outcomes":[{"id":"done","kind":"success","when":{"objective":{"quest":"%s","id":"rope"}},
                "settlement":{"rewards":[{"item":"rope","quantity":2}],"consequences":["The ferry runs."]}}]}}"""
                .formatted(id, availableWhen, id);
    }

    /** Quests compile with world predicates; dependencies between quests may not be circular. */
    @Test
    void validatesQuests() {
        String ferry = quest("ferry", "{\"visited\":\"market\"}");
        var compiled = new PackCompiler().compile(parse(withFile("quests.json", "quests", envelope(ferry))));
        assertEquals("quest", compiled.byId().get("ferry").kind());
        String done = "{\"questStatus\":{\"quest\":\"%s\",\"status\":\"completed\"}}";
        // ferry waits on toll and toll waits on ferry: neither can ever become available.
        assertEquals("CYCLE", failure(withFile("quests.json", "quests", envelope(
                quest("ferry", done.formatted("toll")) + "," + quest("toll", done.formatted("ferry"))))));
        // Negated or alternative conditions are not unconditional requirements, so they add no edge.
        assertNotNull(new PackCompiler().compile(parse(withFile("quests.json", "quests", envelope(
                quest("ferry", done.formatted("toll")) + ","
                        + quest("toll", "{\"any\":[" + done.formatted("ferry") + ",{\"visited\":\"harbor\"}]}"))))));
        assertEquals("SCHEMA", failure(withFile("quests.json", "quests",
                envelope(ferry.replace("\"kind\":\"success\"", "\"kind\":\"failure\"")))));
        assertEquals("REFERENCE_UNKNOWN", failure(withFile("quests.json", "quests",
                envelope(ferry.replace("\"id\":\"rope\"}}", "\"id\":\"oar\"}}")))));
        assertEquals("SCHEMA", failure(withFile("quests.json", "quests", envelope(
                quest("ferry", "{\"ge\":[{\"dice\":{\"count\":1,\"sides\":6}},3]}")))));
        assertEquals("DUPLICATE", failure(withFile("quests.json", "quests", envelope(ferry.replace(
                "\"objectives\":[{\"id\":\"rope\",\"description\":\"Get rope.\",\"when\":{\"possesses\":{\"item\":\"rope\"}}}",
                "\"objectives\":[{\"id\":\"rope\",\"description\":\"A.\",\"when\":true},{\"id\":\"rope\",\"description\":\"B.\",\"when\":true}")))));
        assertEquals("REFERENCE_KIND", failure(withFile("quests.json", "quests",
                envelope(ferry.replace("\"issuer\":\"keeper\"", "\"issuer\":\"guild\"")))));
    }

    /** Generation policy with one bounded and one catalog-only category plus fixed author constraints. */
    private static final String POLICY = """
            {"categories":{"npcs":{"mode":"bounded","maxNewPerScene":2,"maxNewPerCampaign":10,"tags":["sailor"],
            "templates":["keeper"],"ranges":{"might":{"min":0,"max":3}}},"items":{"mode":"catalogOnly"}},
            "constraints":{"required":["keeper"],"fixed":[{"entity":"keeper","fields":["role"]}],"excludedTags":["undead"]}}""";

    /** The policy file compiles into opt-in categories and constraints whose references are authored. */
    @Test
    void compilesGenerationPolicy() {
        var policy = compile("generation.json", "{}", POLICY).generationPolicy();
        assertEquals(GenerationPolicy.Mode.BOUNDED, policy.category("npcs").mode());
        assertEquals(new GenerationPolicy.Range(0, 3), policy.category("npcs").ranges().get("might"));
        assertEquals(GenerationPolicy.Mode.CATALOG_ONLY, policy.category("items").mode());
        assertEquals(GenerationPolicy.Mode.DISABLED, policy.category("locations").mode());
        assertEquals(Set.of("keeper"), policy.required());
        assertEquals(Map.of("keeper", Set.of("role")), policy.fixed());
        assertEquals(GenerationPolicy.EMPTY, new PackCompiler().compile(parse(sources())).generationPolicy());
        rejects("SCHEMA", "generation.json", "{}", "{\"categories\":{\"dragons\":{\"mode\":\"bounded\"}}}");
        rejects("SCHEMA", "generation.json", "{}", "{\"seed\":1}");
        rejects("REFERENCE_KIND", "generation.json", "{}", POLICY.replace("\"templates\":[\"keeper\"]", "\"templates\":[\"rope\"]"));
        rejects("RANGE", "generation.json", "{}", POLICY.replace("\"maxNewPerCampaign\":10", "\"maxNewPerCampaign\":1"));
        rejects("RANGE", "generation.json", "{}", POLICY.replace("\"min\":0,\"max\":3", "\"min\":3,\"max\":0"));
        rejects("REFERENCE_UNKNOWN", "generation.json", "{}", POLICY.replace("\"required\":[\"keeper\"]", "\"required\":[\"ghost\"]"));
        // A fixed field must be one the author wrote, which also catches misspelled field names.
        rejects("REFERENCE_UNKNOWN", "generation.json", "{}", POLICY.replace("\"fields\":[\"role\"]", "\"fields\":[\"rolle\"]"));
    }

    /** Excluded tags that the author also allows or requires are contradictions reported before play. */
    @Test
    void rejectsConstraintConflicts() {
        rejects("CONSTRAINT_CONFLICT", "generation.json", "{}", POLICY.replace("\"tags\":[\"sailor\"]", "\"tags\":[\"sailor\",\"undead\"]"));
        var files = sources();
        files.put("world.json", files.get("world.json").replace("\"description\":\"Docks.\"", "\"description\":\"Docks.\",\"tags\":[\"undead\"]"));
        files.put("generation.json", POLICY.replace("\"required\":[\"keeper\"]", "\"required\":[\"keeper\",\"harbor\"]"));
        assertEquals("CONSTRAINT_CONFLICT", failure(files));
    }

    /** The gate admits generated content only inside the policy and never over authored facts. */
    @Test
    void gatesGeneratedContent() {
        var gate = new GenerationGate(compile("generation.json", "{}", POLICY));
        assertTrue(gate.checkNew(new GenerationGate.NewEntity("npcs", "sailor.one", Set.of("sailor"), "keeper",
                Map.of("might", 2L), Set.of("harbor")), 1, 9).accepted());
        var rejected = gate.checkNew(new GenerationGate.NewEntity("npcs", "keeper", Set.of("undead", "pirate"), null,
                Map.of("might", 9L, "luck", 1L), Set.of("nowhere")), 2, 0);
        assertEquals(List.of("AUTHORED_ID", "BUDGET", "TAG", "EXCLUDED_TAG", "TEMPLATE", "RANGE", "RANGE", "REFERENCE_UNKNOWN"),
                codes(rejected));
        assertEquals(List.of("BUDGET"), codes(gate.checkNew(new GenerationGate.NewEntity("npcs", "sailor.two", Set.of(),
                "keeper", Map.of(), Set.of()), 0, 10)));
        assertEquals(List.of("GENERATION_CATALOG_ONLY"), codes(gate.checkNew(new GenerationGate.NewEntity("items", "oar",
                Set.of(), null, Map.of(), Set.of()), 0, 0)));
        assertEquals(List.of("GENERATION_DISABLED"), codes(gate.checkNew(new GenerationGate.NewEntity("locations", "cove",
                Set.of(), null, Map.of(), Set.of()), 0, 0)));
        // Gap filling may add an unwritten field but never a fixed or authored one.
        assertTrue(gate.checkFill("keeper", "description").accepted());
        assertEquals(List.of("FIXED_FIELD", "AUTHORED_VALUE"), codes(gate.checkFill("keeper", "role")));
        assertEquals(List.of("AUTHORED_VALUE"), codes(gate.checkFill("keeper", "name")));
        assertEquals(List.of("GENERATION_CATALOG_ONLY"), codes(gate.checkFill("rope", "weight")));
        assertEquals(List.of("REFERENCE_UNKNOWN"), codes(gate.checkFill("ghost", "name")));
        assertEquals(List.of("REQUIRED_ENTITY"), codes(gate.checkRetire("keeper")));
        assertTrue(gate.checkRetire("guild").accepted());
        assertEquals(List.of("REFERENCE_UNKNOWN"), codes(gate.checkRetire("ghost")));
    }

    /** Violation codes of a gate decision, in report order. */
    private static List<String> codes(GenerationGate.Decision decision) {
        return decision.violations().stream().map(GenerationGate.Violation::code).toList();
    }

    /** A location no link path reaches from the entrypoint is a located warning, not an error. */
    @Test
    void warnsAboutUnreachableLocations() {
        assertEquals(List.of(), new PackCompiler().compile(parse(sources())).warnings());
        var compiled = compile("world.json", "\"links\":[{\"to\":\"harbor\",\"accessible\":true}]}}",
                "\"links\":[{\"to\":\"harbor\",\"accessible\":true}]}},{\"id\":\"cove\",\"kind\":\"location\",\"data\":"
                        + "{\"name\":\"Cove\",\"description\":\"Quiet.\",\"links\":[{\"to\":\"harbor\",\"accessible\":true}]}}");
        assertEquals(1, compiled.warnings().size());
        var warning = compiled.warnings().getFirst();
        assertEquals("UNREACHABLE_LOCATION", warning.code());
        assertEquals("world.json", warning.location().file());
        assertTrue(warning.message().contains("cove"), warning.message());
    }
}
