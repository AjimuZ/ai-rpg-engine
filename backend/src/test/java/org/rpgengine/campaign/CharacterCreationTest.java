package org.rpgengine.campaign;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.rpgengine.content.CompiledPack;
import org.rpgengine.content.DependencyCatalog;
import org.rpgengine.content.EngineContract;
import org.rpgengine.content.PackCompiler;
import org.rpgengine.content.PackParser;
import org.rpgengine.content.PackStaging;

import static org.junit.jupiter.api.Assertions.*;

/** Pure creation acceptance corpus: authored genres, budgets, minima, input isolation and loadouts. */
class CharacterCreationTest {
    /** Framework-created fixture; tests use explicit pack bytes and no database. */
    CharacterCreationTest() {
    }

    /** Both same-ID genre packs resolve their own rules, labels, resources and grants. */
    @ParameterizedTest
    @ValueSource(strings = {"fantasy-exploration", "science-fiction-salvage"})
    void resolvesPresetAndEquivalentCustomChoices(String genre) throws Exception {
        var pack = compile(files(genre));
        var preset = CharacterCreation.resolve(pack, choices("surveyor", Map.of(), Map.of()));
        var custom = CharacterCreation.resolve(pack, choices(null, preset.attributes(), preset.skills()));
        assertEquals(preset, custom);
        assertEquals(1, preset.items().size());
        assertEquals("kit", preset.items().getFirst().definitionId());
        assertEquals(1, preset.items().getFirst().quantity());
        assertEquals(java.util.List.of("steady"), preset.abilities());
        assertFalse(preset.resources().isEmpty());
        assertEquals("fantasy-exploration".equals(genre) ? "trailhead" : "dock", preset.locationId());
    }

    /** Allocations must fit allowlists, both budgets and individual attribute/skill bounds. */
    @Test
    void rejectsInvalidAllocationsAndOptions() throws Exception {
        var pack = compile(files("fantasy-exploration"));
        for (CharacterChoices choice : java.util.List.of(
                choices(null, Map.of("awareness", 3), Map.of()),
                choices(null, Map.of(), Map.of("fieldwork", 2)),
                choices(null, Map.of("awareness", 5), Map.of()),
                choices(null, Map.of(), Map.of("fieldwork", 4)),
                choices(null, Map.of("logic", 1), Map.of()),
                choices(null, Map.of(), Map.of("unknown", 0)),
                choices("unknown", Map.of(), Map.of()),
                new CharacterChoices("Player", null, Map.of(), Map.of(), "invented", null),
                new CharacterChoices("Player", null, Map.of(), Map.of(), null, "invented"))) {
            assertThrows(IllegalArgumentException.class, () -> CharacterCreation.resolve(pack, choice));
        }
        assertThrows(IllegalArgumentException.class, () -> choices("surveyor", Map.of("awareness", 1), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> choices(null, Map.of("awareness", -1), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> choices(null, Map.of(), Map.of("fieldwork", 11)));
    }

    /** Nonzero minima cost no points, omitted skills cost zero, and input maps are copied. */
    @Test
    void appliesMinimaAndDoesNotRequireSpendingEveryPoint() throws Exception {
        var files = files("fantasy-exploration");
        String rules = new String(files.get("rules.json"), StandardCharsets.UTF_8);
        files.put("rules.json", rules.replace("\"min\": 0", "\"min\": 1").getBytes(StandardCharsets.UTF_8));
        var pack = compile(files);
        var allocated = new TreeMap<>(Map.of("awareness", 3));
        var choices = choices(null, allocated, Map.of());
        allocated.put("awareness", 100);
        assertEquals(3, CharacterCreation.resolve(pack, choices).attributes().get("awareness"));
        var minimal = CharacterCreation.resolve(pack, choices(null, Map.of(), Map.of()));
        assertEquals(Map.of("awareness", 1), minimal.attributes());
        assertEquals(Map.of("fieldwork", 0), minimal.skills());
        assertThrows(UnsupportedOperationException.class, () -> minimal.attributes().put("awareness", 2));
        assertThrows(IllegalArgumentException.class,
                () -> CharacterCreation.resolve(pack, choices(null, Map.of("awareness", 0), Map.of())));
    }

    /** Individually legal duplicate grants must not create a stack larger than the pinned item allows. */
    @Test
    void rejectsAggregateStartingStackOverflow() throws Exception {
        var files = files("fantasy-exploration");
        String creation = new String(files.get("creation.json"), StandardCharsets.UTF_8);
        String item = "{\"item\":\"kit\",\"quantity\":1}";
        creation = creation.replaceFirst("(?s)\\\"startingItems\\\"\\s*:\\s*\\[.*?\\]",
                "\"startingItems\":[" + item + "," + item + "]");
        files.put("creation.json", creation.getBytes(StandardCharsets.UTF_8));
        var pack = compile(files);
        assertThrows(IllegalArgumentException.class,
                () -> CharacterCreation.resolve(pack, choices("surveyor", Map.of(), Map.of())));
    }

    /** Build bounded choices with no optional background/role; invalid input propagates to each assertion. */
    private static CharacterChoices choices(String archetype, Map<String, Integer> attributes, Map<String, Integer> skills) {
        return new CharacterChoices("Player", archetype, attributes, skills, null, null);
    }

    /** Stage a bundled fixture and return a mutable file index for isolated hostile-input variants. */
    private Map<String, byte[]> files(String genre) throws Exception {
        return new TreeMap<>(new PackStaging(PackStaging.Limits.standard())
                .directory(Path.of(getClass().getResource("/packs/" + genre).toURI())).files());
    }

    /** Parse and semantically compile exact bytes under the pinned engine contract; invalid packs fail setup. */
    private static CompiledPack compile(Map<String, byte[]> files) {
        return new PackCompiler(DependencyCatalog.none(), EngineContract.current()).compile(new PackParser().parse(files));
    }
}
