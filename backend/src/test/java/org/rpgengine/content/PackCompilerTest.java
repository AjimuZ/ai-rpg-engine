package org.rpgengine.content;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Semantic validation tests: parse a small valid pack, then break one fact at a time. */
class PackCompilerTest {
    /** Construct an independent corpus; no application context or storage is needed. */
    PackCompilerTest() {
    }

    /** Files of the valid reference pack, as JSON text so single facts can be replaced by string edits. */
    private static Map<String, String> sources() {
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
    private static String envelope(String definitions) {
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
        // Quests have no semantic schema yet, so a well-formed quest definition is refused, not ignored.
        var files = sources();
        files.put("adventure.json", files.get("adventure.json").replace("\"conditions.json\",\"category\":\"conditions\"",
                "\"conditions.json\",\"category\":\"quests\""));
        files.put("conditions.json", files.get("conditions.json").replace("\"kind\":\"condition\"", "\"kind\":\"quest\""));
        var error = assertThrows(PackException.class, () -> new PackCompiler().compile(parse(files)));
        assertEquals("UNSUPPORTED_KIND", error.code());
    }
}
