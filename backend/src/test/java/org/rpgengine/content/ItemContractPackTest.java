package org.rpgengine.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * P10.2 pack contract: an item's optional trade {@code value}, its optional {@code use} contract
 * (consumption, targeting, effect program) and the {@code quantity} of a {@code createItem} effect.
 * Each case breaks one fact of a valid pack, so a diagnostic cannot be explained by a second defect.
 */
class ItemContractPackTest {
    private static final String ITEMS = (",{\"id\":\"tonic\",\"kind\":\"item\",\"data\":{\"name\":\"Tonic\",\"description\":\"Restores.\","
            + "\"itemType\":\"consumable\",\"maxStack\":4,\"value\":7,\"rarity\":\"common\","
            + "\"use\":{\"consumes\":true,\"target\":{\"policy\":\"single\",\"maxTargets\":2},"
            + "\"effects\":[{\"type\":\"modifyResource\",\"resource\":\"stamina\",\"amount\":2}]},"
            + "\"appearance\":{\"id\":\"tonic.green\",\"summary\":\"A green tonic\"}}}"
            + ",{\"id\":\"press\",\"kind\":\"item\",\"data\":{\"name\":\"Press\",\"description\":\"Presses.\","
            + "\"itemType\":\"tool\",\"rarity\":\"common\",\"use\":{\"consumes\":false,"
            + "\"effects\":[{\"type\":\"createItem\",\"item\":\"rope\",\"quantity\":3}]},"
            + "\"appearance\":{\"id\":\"press.iron\",\"summary\":\"An iron press\"}}}");

    /** Construct a stateless fixture. */
    ItemContractPackTest() {
    }

    /** A pack using a value, a consuming use with targets, and a reusable use that creates items compiles. */
    @Test
    void acceptsValueUseAndCreateItem() {
        var compiled = compile(valid());
        var tonic = compiled.definitions().stream().filter(definition -> definition.id().equals("tonic")).findFirst().orElseThrow();
        assertEquals(7, ((Number) tonic.data().get("value")).intValue());
        assertTrue(tonic.data().get("use") instanceof Map<?, ?>);
        assertTrue(compiled.definitions().stream().anyMatch(definition -> definition.id().equals("press")));
    }

    /** A value is an exact positive integer inside the bound; zero would hide an unpriced item as free. */
    @Test
    void validatesValue() {
        rejects("SCHEMA", "\"value\":7", "\"value\":0");
        rejects("SCHEMA", "\"value\":7", "\"value\":1000001");
        rejects("SCHEMA", "\"value\":7", "\"value\":7.5");
        rejects("SCHEMA", "\"value\":7", "\"value\":\"7\"");
    }

    /** The use contract is closed: known fields, a target policy, and at least one valid effect. */
    @Test
    void validatesUseContract() {
        rejects("SCHEMA", "\"consumes\":true,", "\"consumes\":\"yes\",");
        rejects("SCHEMA", "\"consumes\":true,", "\"consumes\":true,\"extra\":1,");
        rejects("SCHEMA", "\"policy\":\"single\"", "\"policy\":\"everyone\"");
        rejects("SCHEMA", "\"maxTargets\":2", "\"maxTargets\":9");
        rejects("SCHEMA", "\"effects\":[{\"type\":\"modifyResource\",\"resource\":\"stamina\",\"amount\":2}]}",
                "\"effects\":[]}");
        rejects("REFERENCE_UNKNOWN", "\"resource\":\"stamina\",\"amount\":2", "\"resource\":\"mana\",\"amount\":2");
        // An effect program is the ability grammar: a constant or a proven interval beyond the bound is refused.
        rejects("SCHEMA", "\"amount\":2}]},\"appearance\":{\"id\":\"tonic.green\"", "\"amount\":2000000}]},\"appearance\":{\"id\":\"tonic.green\"");
        rejects("RANGE", "\"amount\":2}]},\"appearance\":{\"id\":\"tonic.green\"",
                "\"amount\":{\"mul\":[{\"dice\":{\"count\":20,\"sides\":100}},1000]}}]},\"appearance\":{\"id\":\"tonic.green\"");
    }

    /** A createItem effect names a known item and a bounded positive quantity, and nothing else. */
    @Test
    void validatesCreateItemQuantity() {
        rejects("SCHEMA", "\"quantity\":3", "\"quantity\":0");
        rejects("SCHEMA", "\"quantity\":3", "\"quantity\":100");
        rejects("REFERENCE_UNKNOWN", "\"item\":\"rope\",\"quantity\":3", "\"item\":\"ghost\",\"quantity\":3");
        rejects("SCHEMA", "\"item\":\"rope\",\"quantity\":3", "\"item\":\"rope\",\"quantity\":3,\"amount\":1");
    }

    /** An item without use or value stays valid: both fields are optional and absent by default. */
    @Test
    void keepsOldItemsValid() {
        compile(PackCompilerTest.sources());
    }

    /** Valid base pack with the two P10.2 items appended. */
    private static Map<String, String> valid() {
        var files = PackCompilerTest.sources();
        assertTrue(files.get("items.json").contains("}}]}"), "items envelope shape changed");
        files.put("items.json", files.get("items.json").replace("}}]}", "}}" + ITEMS + "]}"));
        return files;
    }

    private static CompiledPack compile(Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, text) -> bytes.put(path, text.replace("__DEPENDENCIES__", "").getBytes(StandardCharsets.UTF_8)));
        return new PackCompiler().compile(new PackParser().parse(bytes));
    }

    /** Apply one textual mutation to the valid pack's item file and assert the first diagnostic code. */
    private static void rejects(String code, String from, String to) {
        var files = valid();
        assertTrue(files.get("items.json").contains(from), "mutation target missing: " + from);
        files.put("items.json", files.get("items.json").replace(from, to));
        var error = assertThrows(PackException.class, () -> compile(files));
        assertEquals(code, error.code(), error.getMessage());
    }
}
