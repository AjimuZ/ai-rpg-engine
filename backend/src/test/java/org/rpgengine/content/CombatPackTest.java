package org.rpgengine.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.EncounterPack;

/**
 * P11.1 pack contract: the {@code combat} rule and {@code encounter} definitions. Each case breaks one fact of the
 * valid fixture, so a diagnostic cannot be explained by a second defect. Supported customization (parameter values
 * inside their bounds) compiles; a different scheduler or spatial model, an unknown parameter or an incoherent
 * encounter is a located error.
 */
class CombatPackTest {
    /** Construct a stateless fixture. */
    CombatPackTest() {
    }

    /** Both the default and the customized fixture compile, and encounter names are localizable public text. */
    @Test
    void acceptsTheFixtures() throws Exception {
        var compiled = compile(EncounterPack.files());
        assertTrue(compiled.definitions().stream().anyMatch(definition -> definition.kind().equals("encounter")));
        compile(EncounterPack.tuned());
        // Rule order does not matter: the combat rule is checked after the attributes and resources it maps.
        var files = text(EncounterPack.files());
        String rules = files.get("rules.json");
        String combat = EncounterPack.COMBAT_RULE;
        files.put("rules.json", rules.replace("," + combat, "").replace("\"definitions\":[",
                "\"definitions\":[" + combat + ","));
        compile(bytes(files));
    }

    /** Only the one kernel can be named; other schedulers and spaces are engine extensions. */
    @Test
    void refusesAnotherKernel() {
        rejects("SCHEMA", "rules.json", "\"scheduler\":\"initiative-rounds\"", "\"scheduler\":\"realtime\"", true);
        rejects("SCHEMA", "rules.json", "\"space\":\"zones\"", "\"space\":\"grid\"", true);
        var unknown = rejects("SCHEMA", "rules.json", "\"guardBase\":11", "\"guardBase\":11,\"reactionDepth\":2", true);
        assertTrue(unknown.location().pointer().endsWith("/values/reactionDepth"), unknown.location().pointer());
    }

    /** Parameters stay in their bounds and satisfy the cross-parameter invariants. */
    @Test
    void refusesOutOfBoundParameters() {
        rejects("SCHEMA", "rules.json", "\"actionPoints\":4", "\"actionPoints\":7", true);
        rejects("SCHEMA", "rules.json", "\"roundSeconds\":6", "\"roundSeconds\":0", true);
        var conflict = rejects("CONSTRAINT_CONFLICT", "rules.json", "\"strikeCost\":3", "\"strikeCost\":5", true);
        assertTrue(conflict.getMessage().contains("strikeCost"), conflict.getMessage());
    }

    /** The role mapping names attributes, Health and Focus name resources, and a pack has one combat rule. */
    @Test
    void refusesBrokenMappings() {
        rejects("REFERENCE_UNKNOWN", "rules.json", "\"insight\":\"awareness\",\"resolve\":\"might\"},\"health\":\"vigor\"}}",
                "\"insight\":\"wits\",\"resolve\":\"might\"},\"health\":\"vigor\"}}", false);
        rejects("SCHEMA", "rules.json", "\"finesse\":\"awareness\",\"insight\"", "\"insight\"", false);
        rejects("REFERENCE_UNKNOWN", "rules.json", "\"health\":\"vigor\"}}", "\"health\":\"blood\"}}", false);
        rejects("DUPLICATE", "rules.json", "\"health\":\"vigor\"}}", "\"health\":\"vigor\",\"focus\":\"vigor\"}}", false);
        rejects("DUPLICATE", "rules.json", EncounterPack.COMBAT_RULE,
                EncounterPack.COMBAT_RULE + "," + EncounterPack.COMBAT_RULE.replace("rules.combat", "rules.combat2"), false);
    }

    /** An encounter needs its pack's combat rule. */
    @Test
    void refusesEncountersWithoutAProfile() throws Exception {
        var files = text(EncounterPack.files());
        files.put("rules.json", files.get("rules.json").replace("," + EncounterPack.COMBAT_RULE, ""));
        var error = assertThrows(PackException.class, () -> compile(bytes(files)));
        assertEquals("CONSTRAINT_CONFLICT", error.code(), error.getMessage());
    }

    /** Zone references, edges and the connected space are checked with located diagnostics. */
    @Test
    void refusesIncoherentZones() {
        rejects("REFERENCE_UNKNOWN", "encounters.json", "{\"from\":\"entry\",\"to\":\"yard\"}",
                "{\"from\":\"entry\",\"to\":\"barn\"}", false);
        rejects("CONSTRAINT_CONFLICT", "encounters.json", "{\"from\":\"entry\",\"to\":\"yard\"}",
                "{\"from\":\"entry\",\"to\":\"entry\"}", false);
        rejects("CONSTRAINT_CONFLICT", "encounters.json", "{\"from\":\"entry\",\"to\":\"yard\"}",
                "{\"from\":\"yard\",\"to\":\"escape-route\"}", false);
        var island = rejects("CONSTRAINT_CONFLICT", "encounters.json", "{\"id\":\"stones\",\"name\":\"Stones\"}",
                "{\"id\":\"stones\",\"name\":\"Stones\"},{\"id\":\"cave\",\"name\":\"Cave\"}", false);
        assertTrue(island.getMessage().contains("cave"), island.getMessage());
        rejects("DUPLICATE", "encounters.json", "{\"id\":\"yard\",\"name\":\"Yard\"", "{\"id\":\"entry\",\"name\":\"Yard\"",
                false);
        rejects("SCHEMA", "encounters.json", "\"cover\":\"partial\"", "\"cover\":\"walls\"", false);
        rejects("REFERENCE_UNKNOWN", "encounters.json", "\"playerZone\":\"entry\"", "\"playerZone\":\"porch\"", false);
    }

    /** Escape and objective zones must be reachable along unblocked edges and differ from the start. */
    @Test
    void refusesInfeasibleEscapeAndObjectives() {
        rejects("CONSTRAINT_CONFLICT", "encounters.json", "\"escapeZones\":[\"escape-route\"]", "\"escapeZones\":[\"entry\"]",
                false);
        rejects("CONSTRAINT_CONFLICT", "encounters.json",
                "{\"from\":\"entry\",\"to\":\"escape-route\",\"difficult\":true},{\"from\":\"yard\",\"to\":\"escape-route\"}",
                "{\"from\":\"entry\",\"to\":\"escape-route\",\"blocked\":true},{\"from\":\"yard\",\"to\":\"escape-route\","
                        + "\"blocked\":true}", false);
        rejects("CONSTRAINT_CONFLICT", "encounters.json", "\"playerZone\":\"entry\",\"escapeZones\":[\"escape-route\"],",
                "\"playerZone\":\"entry\",", false);
        rejects("CONSTRAINT_CONFLICT", "encounters.json", "{\"id\":\"reach-stones\",\"kind\":\"reachZone\",\"zone\":\"stones\"}",
                "{\"id\":\"reach-stones\",\"kind\":\"reachZone\",\"zone\":\"clearing\"}", false);
        rejects("SCHEMA", "encounters.json", "{\"id\":\"hold\",\"kind\":\"survive\",\"rounds\":3}",
                "{\"id\":\"hold\",\"kind\":\"survive\",\"rounds\":31}", false);
        rejects("SCHEMA", "encounters.json", "{\"id\":\"hold\",\"kind\":\"survive\",\"rounds\":3}",
                "{\"id\":\"hold\",\"kind\":\"survive\",\"rounds\":3,\"zone\":\"gate\"}", false);
        rejects("REFERENCE_UNKNOWN", "encounters.json", "\"kind\":\"protect\",\"participant\":\"keeper\"",
                "\"kind\":\"protect\",\"participant\":\"smuggler\"", false);
        rejects("DUPLICATE", "encounters.json", "{\"id\":\"slip-away\"", "{\"id\":\"drive-off\"", false);
    }

    /** Participants: bounded statistics, a reserved player ID, at least one hostile and a bounded hostile count. */
    @Test
    void refusesBrokenRosters() {
        rejects("SCHEMA", "encounters.json", "\"stats\":{\"health\":14,", "\"stats\":{\"health\":0,", false);
        rejects("SCHEMA", "encounters.json", "\"armor\":1,", "\"armor\":5,", false);
        rejects("SCHEMA", "encounters.json", "\"force\":1,\"finesse\":1", "\"force\":7,\"finesse\":1", false);
        rejects("SCHEMA", "encounters.json", "{\"id\":\"raider\",", "{\"id\":\"player\",", false);
        rejects("REFERENCE_KIND", "encounters.json", "\"npc\":\"warden\"", "\"npc\":\"kit\"", false);
        rejects("CONSTRAINT_CONFLICT", "encounters.json", "\"side\":\"hostile\",\"zone\":\"stones\"",
                "\"side\":\"ally\",\"zone\":\"stones\"", false);
        String wolf = "{\"id\":\"wolf\",\"name\":\"Wolf\",\"side\":\"hostile\",\"zone\":\"stones\",\"threat\":\"minor\","
                + "\"stats\":{\"health\":9,\"guard\":11,\"resistance\":9,\"force\":2,\"finesse\":2}}";
        var pack = rejects("CONSTRAINT_CONFLICT", "encounters.json", wolf, wolf + "," + wolf.replace("wolf", "wolf2") + ","
                + wolf.replace("wolf", "wolf3") + "," + wolf.replace("wolf", "wolf4"), false);
        assertTrue(pack.getMessage().contains("party size"), pack.getMessage());
        rejects("SCHEMA", "encounters.json", "\"cover\":\"partial\",\"capacity\":4",
                "\"cover\":\"partial\",\"capacity\":0", false);
        rejects("CONSTRAINT_CONFLICT", "encounters.json", "{\"id\":\"gate\",\"name\":\"Gate\"}",
                "{\"id\":\"gate\",\"name\":\"Gate\",\"capacity\":1}", false);
        rejects("SCHEMA", "encounters.json", "\"opening\":\"first\"", "\"opening\":\"ambush\"", false);
    }

    /** Apply one textual mutation to a fixture variant and assert the first diagnostic code. */
    private static PackException rejects(String code, String file, String from, String to, boolean tuned) {
        try {
            var files = text(tuned ? EncounterPack.tuned() : EncounterPack.files());
            String compact = files.get(file).replace("\n", "").replaceAll(" {2,}", "");
            assertTrue(compact.contains(from.replace("\n", "").replaceAll(" {2,}", "")), "mutation target missing: " + from);
            files.put(file, compact.replace(from.replace("\n", "").replaceAll(" {2,}", ""),
                    to.replace("\n", "").replaceAll(" {2,}", "")));
            var error = assertThrows(PackException.class, () -> compile(bytes(files)));
            assertEquals(code, error.code(), error.getMessage());
            return error;
        } catch (java.net.URISyntaxException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Map<String, String> text(Map<String, byte[]> files) {
        var text = new TreeMap<String, String>();
        files.forEach((name, content) -> text.put(name, new String(content, StandardCharsets.UTF_8)));
        return text;
    }

    private static Map<String, byte[]> bytes(Map<String, String> files) {
        var bytes = new TreeMap<String, byte[]>();
        files.forEach((name, content) -> bytes.put(name, content.getBytes(StandardCharsets.UTF_8)));
        return bytes;
    }

    private static CompiledPack compile(Map<String, byte[]> files) {
        return new PackCompiler().compile(new PackParser().parse(files));
    }
}
