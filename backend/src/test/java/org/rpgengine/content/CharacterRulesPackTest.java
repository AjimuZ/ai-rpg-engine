package org.rpgengine.content;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P10.1 pack contract: derived statistics, the equipment vocabulary (slots, capacity, hands, tags),
 * equippable items, stacking conditions and equipped starting items. Each case breaks one fact of
 * a valid pack, so a diagnostic cannot be explained by a second defect.
 */
class CharacterRulesPackTest {
    private static final String EQUIPMENT = compact("""
            ,{"id":"rules.equipment","kind":"rule","data":{"ruleType":"equipment","hands":2,"slots":[
            {"id":"main-hand","name":"Main hand","capacity":1,"accepts":["weapon"]},
            {"id":"off-hand","name":"Off hand","capacity":1,"accepts":["shield"]},
            {"id":"body","name":"Body"}]}}
            ,{"id":"rules.derived","kind":"rule","data":{"ruleType":"derivedStats","stats":[
            {"id":"guard","name":"Guard","formula":{"add":[{"attribute":"might"},{"skill":"sailing"}]},"min":0,"max":8}]}}""");
    private static final String ITEMS = compact("""
            ,{"id":"sword","kind":"item","data":{"name":"Sword","description":"Steel.","itemType":"weapon","slot":"main-hand",
            "hands":1,"tags":["weapon"],"rarity":"common","appearance":{"id":"sword.steel","summary":"A steel sword"}}}
            ,{"id":"greatsword","kind":"item","data":{"name":"Greatsword","description":"Heavy.","itemType":"weapon",
            "slot":"main-hand","hands":2,"tags":["weapon"],"rarity":"common","appearance":{"id":"great.steel","summary":"A big sword"}}}
            ,{"id":"shield","kind":"item","data":{"name":"Shield","description":"Wood.","itemType":"armor","slot":"off-hand",
            "hands":1,"tags":["shield"],"rarity":"common","appearance":{"id":"shield.wood","summary":"A round shield"}}}
            ,{"id":"cloak","kind":"item","data":{"name":"Cloak","description":"Wool.","itemType":"armor","slot":"body",
            "rarity":"common","appearance":{"id":"cloak.wool","summary":"A grey cloak"}}}""");

    /** Join a text block into one line so mutations can match exact fragments. */
    private static String compact(String block) {
        return block.replace("\n", "").replace("            ", "");
    }

    /** Construct a stateless fixture. */
    CharacterRulesPackTest() {
    }

    /** A pack using every P10.1 definition compiles. */
    @Test
    void acceptsEquipmentDerivedStatsAndStackingConditions() {
        var compiled = compile(armed());
        assertTrue(compiled.definitions().stream().anyMatch(definition -> definition.id().equals("sword")));
        // Rule order must not matter: derived statistics are checked after the attributes they read.
        var files = armed();
        files.put("rules.json", files.get("rules.json").replace("{\"id\":\"rules.attributes\"",
                "{\"id\":\"rules.first\",\"kind\":\"rule\",\"data\":{\"ruleType\":\"derivedStats\",\"stats\":[{\"id\":\"edge\","
                        + "\"name\":\"Edge\",\"formula\":{\"attribute\":\"might\"},\"min\":0,\"max\":5}]}},"
                        + "{\"id\":\"rules.attributes\""));
        compile(files);
    }

    /** An item may not name a slot the pack does not define, even when other slots exist. */
    @Test
    void rejectsUnknownSlot() {
        var error = rejects("REFERENCE_UNKNOWN", "items.json", "\"slot\":\"main-hand\",\"hands\":1,",
                "\"slot\":\"head\",\"hands\":1,");
        assertTrue(error.getMessage().contains("head") && error.getMessage().contains("main-hand"), error.getMessage());
        // A pack without an equipment rule has no slots at all.
        var files = armed();
        files.put("rules.json", files.get("rules.json").replace(EQUIPMENT, ""));
        var missing = assertThrows(PackException.class, () -> compile(files));
        assertEquals("REFERENCE_UNKNOWN", missing.code());
    }

    /** Hands are bounded by the pack's hand count and need a slot to be meaningful. */
    @Test
    void rejectsImpossibleHands() {
        rejects("RANGE", "items.json", "\"hands\":2,\"tags\"", "\"hands\":3,\"tags\"");
        rejects("SCHEMA", "items.json", "\"itemType\":\"armor\",\"slot\":\"body\",", "\"itemType\":\"armor\",\"hands\":1,");
        rejects("SCHEMA", "items.json", "\"hands\":1,\"tags\":[\"weapon\"]", "\"hands\":5,\"tags\":[\"weapon\"]");
    }

    /** A slot that accepts tags refuses an item that carries none of them. */
    @Test
    void rejectsIncompatibleTags() {
        rejects("CONSTRAINT_CONFLICT", "items.json", "\"tags\":[\"shield\"]", "\"tags\":[\"light\"]");
    }

    /** An equipment assignment names one instance, so a stackable item cannot be equippable. */
    @Test
    void rejectsStackableEquipment() {
        rejects("SCHEMA", "items.json", "\"itemType\":\"armor\",\"slot\":\"body\"",
                "\"itemType\":\"armor\",\"maxStack\":5,\"slot\":\"body\"");
    }

    /** Equipment rules are checked for shape: hands, duplicates and a single rule per pack. */
    @Test
    void rejectsMalformedEquipmentRules() {
        rejects("SCHEMA", "rules.json", "\"hands\":2,\"slots\"", "\"hands\":5,\"slots\"");
        rejects("DUPLICATE", "rules.json", "{\"id\":\"body\",\"name\":\"Body\"}",
                "{\"id\":\"body\",\"name\":\"Body\"},{\"id\":\"body\",\"name\":\"Again\"}");
        rejects("DUPLICATE", "rules.json", "\"ruleType\":\"derivedStats\"",
                "\"ruleType\":\"equipment\",\"slots\":[{\"id\":\"belt\",\"name\":\"Belt\"}]}},{\"id\":\"rules.more\",\"kind\":\"rule\",\"data\":"
                        + "{\"ruleType\":\"derivedStats\"");
    }

    /** Derived statistics read only attributes and skills, stay in their bounds and have unique IDs. */
    @Test
    void validatesDerivedStatistics() {
        rejects("SCHEMA", "rules.json", "{\"attribute\":\"might\"},{\"skill\":\"sailing\"}",
                "{\"attribute\":\"might\"},{\"dice\":{\"count\":1,\"sides\":6}}");
        rejects("SCHEMA", "rules.json", "{\"attribute\":\"might\"},{\"skill\":\"sailing\"}",
                "{\"attribute\":\"might\"},{\"resource\":\"stamina\"}");
        // might 0..5 plus sailing 0..3 reaches 8; declaring a smaller maximum is a proof failure.
        rejects("RANGE", "rules.json", "\"min\":0,\"max\":8", "\"min\":0,\"max\":7");
        rejects("REFERENCE_UNKNOWN", "rules.json", "{\"attribute\":\"might\"},{\"skill\":\"sailing\"}",
                "{\"attribute\":\"grace\"},{\"skill\":\"sailing\"}");
        rejects("DUPLICATE", "rules.json", "\"id\":\"guard\"", "\"id\":\"stamina\"");
        rejects("SCHEMA", "rules.json", "\"min\":0,\"max\":8", "\"min\":9,\"max\":8");
    }

    /** Only a stacking condition may allow more than one stack, and the cap is bounded. */
    @Test
    void validatesConditionStacks() {
        rejects("SCHEMA", "conditions.json", "\"stacking\":\"stack\",\"maxStacks\":3", "\"stacking\":\"refresh\",\"maxStacks\":3");
        rejects("SCHEMA", "conditions.json", "\"maxStacks\":3", "\"maxStacks\":11");
    }

    /** A profile that equips starting items must give a loadout the slot and hand rules allow. */
    @Test
    void validatesEquippedStartingItems() {
        var ok = armed();
        compile(ok);
        var tooManyHands = rejects("CONSTRAINT_CONFLICT", "creation.json",
                "{\"item\":\"shield\",\"quantity\":1,\"equipped\":true}", "{\"item\":\"shield\",\"quantity\":1,\"equipped\":true},"
                        + "{\"item\":\"greatsword\",\"quantity\":1,\"equipped\":true}");
        // The diagnostic lists the items involved so the author sees the whole conflict.
        assertTrue(tooManyHands.getMessage().contains("sword"), tooManyHands.getMessage());
        // Two hands are available; a two-handed sword plus a shield needs three.
        rejects("CONSTRAINT_CONFLICT", "items.json", "\"hands\":1,\"tags\":[\"weapon\"]", "\"hands\":2,\"tags\":[\"weapon\"]");
        rejects("SCHEMA", "creation.json", "{\"item\":\"rope\",\"quantity\":1}", "{\"item\":\"rope\",\"quantity\":1,\"equipped\":true}");
    }

    /** Valid base: shield and sword equipped fill both hands exactly. */
    private static Map<String, String> armed() {
        var files = PackCompilerTest.sources();
        files.put("rules.json", files.get("rules.json")
                .replace("\"max\":5}]}}", "\"max\":5}]}}")
                .replace("{\"id\":\"common\",\"name\":\"Common\",\"token\":\"rarity-common\"}]}}",
                        "{\"id\":\"common\",\"name\":\"Common\",\"token\":\"rarity-common\"}]}}" + EQUIPMENT));
        files.put("items.json", files.get("items.json").replace("}}]}", "}}" + ITEMS + "]}"));
        files.put("conditions.json", PackCompilerTest.envelope("""
                {"id":"dazed","kind":"condition","data":{"name":"Dazed","description":"Unsteady.","stacking":"stack","maxStacks":3,
                "durationRounds":4,"modifiers":[{"attribute":"might","amount":-1}]}}"""));
        files.put("creation.json", files.get("creation.json").replace("\"startingItems\":[{\"item\":\"rope\",\"quantity\":1}]",
                "\"startingItems\":[{\"item\":\"rope\",\"quantity\":1},{\"item\":\"sword\",\"quantity\":1,\"equipped\":true},"
                        + "{\"item\":\"shield\",\"quantity\":1,\"equipped\":true},{\"item\":\"cloak\",\"quantity\":1}]"));
        return files;
    }

    private static CompiledPack compile(Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, text) -> bytes.put(path, text.replace("__DEPENDENCIES__", "").getBytes(StandardCharsets.UTF_8)));
        return new PackCompiler().compile(new PackParser().parse(bytes));
    }

    /** Apply one textual mutation to the valid pack and assert the first diagnostic code. */
    private static PackException rejects(String code, String file, String from, String to) {
        var files = armed();
        assertTrue(files.get(file).contains(from), "mutation target missing: " + from);
        files.put(file, files.get(file).replace(from, to));
        var error = assertThrows(PackException.class, () -> compile(files));
        assertEquals(code, error.code(), error.getMessage());
        return error;
    }
}
