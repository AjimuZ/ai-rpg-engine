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
 * P11.1 pack contract: the {@code combat} rule and {@code encounter} definitions, with the declared social and scene
 * actions of P11.2 and the attacks and item combat data of P11.3. Each case breaks one fact of the
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

    /**
     * A role may only map an attribute whose whole declared range fits the supported role range, so a character at
     * the attribute's maximum can still enter and roll initiative; an attribute no role maps keeps its own range.
     */
    @Test
    void refusesARoleAttributeBeyondTheSupportedRange() throws Exception {
        var beyond = assertThrows(PackException.class, () -> compile(withNerve(7, "\"resolve\":\"nerve\"")));
        assertEquals("CONSTRAINT_CONFLICT", beyond.code(), beyond.getMessage());
        assertTrue(beyond.location().pointer().endsWith("/roles/resolve"), beyond.location().pointer());
        compile(withNerve(6, "\"resolve\":\"nerve\""));
        compile(withNerve(20, "\"resolve\":\"might\""));
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
        // The grove party is the player and the hound: four hostiles fit, a fifth does not.
        String five = wolf + "," + wolf.replace("wolf", "wolf2") + "," + wolf.replace("wolf", "wolf3") + ","
                + wolf.replace("wolf", "wolf4");
        var pack = rejects("CONSTRAINT_CONFLICT", "encounters.json", wolf, five + "," + wolf.replace("wolf", "wolf5"), false);
        assertTrue(pack.getMessage().contains("party size"), pack.getMessage());
        rejects("SCHEMA", "encounters.json", "\"cover\":\"partial\",\"capacity\":4",
                "\"cover\":\"partial\",\"capacity\":0", false);
        rejects("CONSTRAINT_CONFLICT", "encounters.json", "{\"id\":\"gate\",\"name\":\"Gate\"}",
                "{\"id\":\"gate\",\"name\":\"Gate\",\"capacity\":1}", false);
        rejects("SCHEMA", "encounters.json", "\"opening\":\"first\"", "\"opening\":\"ambush\"", false);
    }

    /**
     * Declared social and scene actions (P11.2): a closed kind and intent vocabulary, a recipient policy, a cost of at
     * least one point that fits one activation, and for a scene action a declared zone and a passage that joins two
     * zones by a declared edge.
     */
    @Test
    void refusesBrokenActions() throws Exception {
        rejects("SCHEMA", "encounters.json", "\"intent\":\"intimidate\"}", "\"intent\":\"intimidate\",\"cost\":0}", false);
        rejects("SCHEMA", "encounters.json", "\"intent\":\"intimidate\"}", "\"intent\":\"intimidate\",\"cost\":4}", false);
        rejects("SCHEMA", "encounters.json", "\"kind\":\"social\",\"intent\":\"intimidate\"",
                "\"kind\":\"magic\",\"intent\":\"intimidate\"", false);
        rejects("SCHEMA", "encounters.json", "\"intent\":\"intimidate\"", "\"intent\":\"charm\"", false);
        rejects("SCHEMA", "encounters.json", "\"kind\":\"social\",\"intent\":\"intimidate\"}", "\"kind\":\"social\"}", false);
        rejects("SCHEMA", "encounters.json", "\"recipients\":\"ally\"", "\"recipients\":\"enemy\"", false);
        rejects("REFERENCE_UNKNOWN", "encounters.json", "\"kind\":\"scene\",\"zone\":\"yard\"",
                "\"kind\":\"scene\",\"zone\":\"barn\"", false);
        var unjoined = rejects("REFERENCE_UNKNOWN", "encounters.json", "\"passage\":{\"from\":\"booth\",\"to\":\"gate\"",
                "\"passage\":{\"from\":\"booth\",\"to\":\"booth\"", false);
        assertTrue(unjoined.location().pointer().endsWith("/passage"), unjoined.location().pointer());
        rejects("SCHEMA", "encounters.json", "\"to\":\"gate\",\"blocked\":false}", "\"to\":\"gate\"}", false);
        rejects("SCHEMA", "encounters.json", "\"kind\":\"scene\",\"zone\":\"gate\",",
                "\"kind\":\"scene\",\"zone\":\"gate\",\"intent\":\"negotiate\",", false);
        rejects("SCHEMA", "encounters.json", "\"zone\":\"gate\",\"passage\":{\"from\":\"booth\",\"to\":\"gate\",\"blocked\":false}",
                "\"zone\":\"gate\"", false);
        rejects("DUPLICATE", "encounters.json", "{\"id\":\"ask-keeper\"", "{\"id\":\"demand-passage\"", false);
        // A customized profile with four action points admits a four-point action.
        var files = text(EncounterPack.tuned());
        files.put("encounters.json", files.get("encounters.json").replace("\"cost\":2}", "\"cost\":4}"));
        assertTrue(files.get("encounters.json").contains("\"cost\":4"));
        compile(bytes(files));
    }

    /** The default fixture with an extra attribute {@code nerve} of range {@code 0..max} and the given resolve mapping. */
    /** Template attacks are closed and bounded: dice, sides, range against the profile, unique IDs. */
    @Test
    void refusesBrokenTemplateAttacks() {
        String cudgel = "\"attacks\":[{\"id\":\"cudgel\",\"attribute\":\"force\",\"skill\":1,\"strongHit\":false,\"damage\":{\"count\":1,\"sides\":6}}]";
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"count\":1", "\"count\":5"), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"sides\":6", "\"sides\":1"), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"sides\":6", "\"sides\":21"), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"sides\":6", "\"sides\":6,\"flat\":21"), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"sides\":6", "\"sides\":6,\"flat\":-6"), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"sides\":6", "\"sides\":6,\"attribute\":\"luck\""), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"attribute\":\"force\"", "\"attribute\":\"might\""), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"skill\":1", "\"skill\":11"), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"skill\":1", "\"skill\":1,\"range\":3"), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"skill\":1", "\"skill\":1,\"penetration\":11"), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"skill\":1", "\"skill\":1,\"defense\":\"luck\""), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace("\"skill\":1", "\"skill\":1,\"ammunition\":\"arrows\""), false);
        rejects("SCHEMA", "encounters.json", cudgel, cudgel.replace(",\"damage\":{\"count\":1,\"sides\":6}", ""), false);
        String twin = cudgel.replace("]", "," + cudgel.substring(cudgel.indexOf('{'), cudgel.lastIndexOf(']')) + "]");
        rejects("DUPLICATE", "encounters.json", cudgel, twin, false);
    }

    /**
     * The combat form of a condition is closed and bounded (P11.4): stacking modes and caps, a declared clock, modifiers,
     * prevention and ticks at a named phase.
     */
    @Test
    void refusesBrokenConditionCombat() throws Exception {
        var valid = compile(bytes(text(EncounterPack.tactics())));
        org.junit.jupiter.api.Assertions.assertNotNull(valid);
        String poison = "\"stacking\":\"additive\",\"cap\":3";
        String clock = "\"duration\":{\"clock\":\"roundEnds\",\"count\":2}";
        String tick = "\"ticks\":[{\"phase\":\"roundEnd\",\"damage\":1}]";
        tactics("SCHEMA", "conditions.json", poison, poison.replace("\"additive\"", "\"sum\""));
        tactics("SCHEMA", "conditions.json", poison, poison.replace("\"cap\":3", "\"cap\":9"));
        tactics("CONSTRAINT_CONFLICT", "conditions.json", poison, poison.replace("\"additive\"", "\"strongest\""));
        tactics("SCHEMA", "conditions.json", clock, clock.replace("\"roundEnds\"", "\"weeks\""));
        tactics("SCHEMA", "conditions.json", clock, clock.replace(",\"count\":2", ""));
        tactics("SCHEMA", "conditions.json", clock, clock.replace("\"count\":2", "\"count\":-1"));
        tactics("SCHEMA", "conditions.json", tick, tick.replace("\"phase\":\"roundEnd\"", "\"phase\":\"midRound\""));
        tactics("SCHEMA", "conditions.json", tick, tick.replace("\"damage\":1", "\"damage\":1,\"heal\":1"));
        tactics("SCHEMA", "conditions.json", tick, tick.replace("\"damage\":1", "\"damage\":100"));
        tactics("SCHEMA", "conditions.json", tick, tick.replace("\"damage\":1", "\"apply\":\"stunned\""));
        tactics("SCHEMA", "conditions.json", "\"attack\":-2", "\"attack\":-5");
        tactics("SCHEMA", "conditions.json", "\"prevents\":[\"actions\"]", "\"prevents\":[\"turns\"]");
        tactics("DUPLICATE", "conditions.json", "\"prevents\":[\"actions\"]", "\"prevents\":[\"actions\",\"actions\"]");
        tactics("SCHEMA", "conditions.json", "\"absorption\":3", "\"absorption\":3,\"bonus\":1");
    }

    /** A combat ability cannot be free without a limit, and a reaction's effect must fit its trigger (P11.4). */
    @Test
    void refusesBrokenAbilityAndReactionCombat() {
        String steady = "\"cost\":1,\"focus\":1,\"cooldownRounds\":2";
        tactics("CONSTRAINT_CONFLICT", "abilities.json", steady, steady.replace("\"cost\":1", "\"cost\":0"));
        tactics("SCHEMA", "abilities.json", steady, steady.replace("\"cost\":1", "\"cost\":4"));
        tactics("SCHEMA", "abilities.json", steady, steady.replace("\"cooldownRounds\":2", "\"cooldownRounds\":31"));
        tactics("CONSTRAINT_CONFLICT", "abilities.json", steady, steady + ",\"maxTargets\":2");
        tactics("SCHEMA", "abilities.json", "\"operations\":[{\"type\":\"heal\"", "\"operations\":[{\"type\":\"revive\"");
        tactics("CONSTRAINT_CONFLICT", "abilities.json", "\"condition\":\"warded\"", "\"condition\":\"dazed\"");
        tactics("REFERENCE_UNKNOWN", "abilities.json", "\"condition\":\"warded\"", "\"condition\":\"nowhere\"");
        String dice = "\"operations\":[{\"type\":\"heal\",\"flat\":2,\"to\":\"self\"}]";
        String many = "\"operations\":[" + "{\"type\":\"heal\",\"dice\":{\"count\":4,\"sides\":4}},".repeat(4)
                + "{\"type\":\"heal\",\"dice\":{\"count\":1,\"sides\":4}}]";
        tactics("SCHEMA", "abilities.json", dice, many);
        String counter = "\"trigger\":\"hit\",\"effect\":{\"type\":\"counter\"}";
        tactics("CONSTRAINT_CONFLICT", "abilities.json", counter, counter.replace("\"hit\"", "\"attacked\""));
        tactics("CONSTRAINT_CONFLICT", "abilities.json", counter, "\"trigger\":\"hit\",\"effect\":{\"type\":\"guard\"}");
        tactics("CONSTRAINT_CONFLICT", "abilities.json", counter, "\"trigger\":\"damaged\",\"effect\":{\"type\":\"absorb\",\"amount\":2}");
        tactics("CONSTRAINT_CONFLICT", "abilities.json", counter, counter.replace("\"hit\"", "\"leavesZone\"") + ",\"protects\":\"ally\"");
        tactics("SCHEMA", "abilities.json", counter, counter.replace("\"hit\"", "\"seen\""));
        tactics("REFERENCE_UNKNOWN", "abilities.json", "\"type\":\"counter\"", "\"type\":\"counter\",\"attack\":\"sword\"");
        tactics("SCHEMA", "abilities.json", counter, counter + ",\"priority\":11");

        String snap = "\"effect\":{\"type\":\"counter\",\"attack\":\"fang\"}";
        tactics("REFERENCE_UNKNOWN", "encounters.json", snap, snap.replace("\"fang\"", "\"tooth\""));
        tactics("DUPLICATE", "encounters.json", "{\"id\":\"snap\"", "{\"id\":\"brace\"");
        tactics("SCHEMA", "encounters.json", "\"zone\":\"pit\",\"brace\":{\"mode\":\"off\"}", "\"zone\":\"pit\",\"brace\":{\"mode\":\"sometimes\"}");
        tactics("SCHEMA", "encounters.json", "\"zone\":\"pit\",\"brace\":{\"mode\":\"off\"}",
                "\"zone\":\"pit\",\"brace\":{\"mode\":\"healthAtMost\",\"value\":101}");
        tactics("CONSTRAINT_CONFLICT", "encounters.json", "\"condition\":\"poisoned\"", "\"condition\":\"dazed\"");
        tactics("SCHEMA", "encounters.json", "\"phase\":\"postDamage\"", "\"phase\":\"preRoll\"");
    }

    /** A Focus cost or predicate needs the combat rule's Focus resource, which the default fixture does not name. */
    @Test
    void refusesFocusWithoutAFocusResource() throws Exception {
        var files = text(EncounterPack.tactics());
        files.put("rules.json", files.get("rules.json").replace(",\"focus\":\"grit\"", ""));
        assertEquals("CONSTRAINT_CONFLICT", org.junit.jupiter.api.Assertions.assertThrows(PackException.class,
                () -> compile(bytes(files))).code());

        // With the ability's own cost gone the pack compiles, so each case below is refused for the use it adds.
        files.put("abilities.json", files.get("abilities.json").replace("\"focus\":1,", ""));
        org.junit.jupiter.api.Assertions.assertNotNull(compile(bytes(files)));
        for (var use : Map.of("{\"id\":\"snap\",", "{\"id\":\"snap\",\"focus\":1,",
                "\"zone\":\"pit\",\"brace\":{\"mode\":\"off\"}", "\"zone\":\"pit\",\"brace\":{\"mode\":\"focusAtLeast\",\"value\":1}").entrySet()) {
            var changed = new TreeMap<>(files);
            changed.put("encounters.json", changed.get("encounters.json").replace(use.getKey(), use.getValue()));
            assertEquals("CONSTRAINT_CONFLICT", org.junit.jupiter.api.Assertions.assertThrows(PackException.class,
                    () -> compile(bytes(changed)), use.getValue()).code());
        }
    }

    /** Apply a replacement to the {@link EncounterPack#tactics()} variant and expect one code. */
    private static void tactics(String code, String file, String from, String to) {
        try {
            var files = text(EncounterPack.tactics());
            org.junit.jupiter.api.Assertions.assertTrue(files.get(file).contains(from), from);
            files.put(file, files.get(file).replace(from, to));
            var failure = org.junit.jupiter.api.Assertions.assertThrows(PackException.class, () -> compile(bytes(files)), to);
            assertEquals(code, failure.code(), failure.getMessage());
        } catch (java.net.URISyntaxException broken) {
            throw new IllegalStateException(broken);
        }
    }

    /** Item combat data needs an equippable item and a combat rule, and stays inside the profile's caps. */
    @Test
    void refusesBrokenItemCombat() throws Exception {
        rejects("SCHEMA", "items.json", "\"combat\":{\"armor\":1}", "\"combat\":{\"armor\":5}", false);
        rejects("SCHEMA", "items.json", "\"combat\":{\"armor\":1}", "\"combat\":{\"guard\":4}", false);
        rejects("SCHEMA", "items.json", "\"combat\":{\"armor\":1}", "\"combat\":{\"bonus\":1}", false);
        rejects("REFERENCE_UNKNOWN", "items.json", "\"skill\":\"bladework\"", "\"skill\":\"juggling\"", false);
        rejects("REFERENCE_KIND", "items.json", "\"ammunition\":\"arrows\"", "\"ammunition\":\"bladework\"", false);
        // A potion cannot be worn, so it has no combat data to add.
        rejects("CONSTRAINT_CONFLICT", "items.json", "{\"name\":\"Potion\"", "{\"combat\":{\"armor\":1},\"name\":\"Potion\"", false);
        var files = text(EncounterPack.files());
        files.put("rules.json", files.get("rules.json").replace("," + EncounterPack.COMBAT_RULE, ""));
        files.put("encounters.json", "{\"schemaVersion\":1,\"definitions\":[]}");
        var missing = assertThrows(PackException.class, () -> compile(bytes(files)));
        assertEquals("CONSTRAINT_CONFLICT", missing.code());
    }

    private static Map<String, byte[]> withNerve(int max, String resolveMapping) throws Exception {
        var files = text(EncounterPack.files());
        String resolve = "{\"id\":\"resolve\",\"name\":\"Resolve\",\"min\":0,\"max\":4}";
        String rules = files.get("rules.json");
        assertTrue(rules.contains(resolve) && rules.contains("\"resolve\":\"might\""), "mutation target missing");
        files.put("rules.json", rules.replace(resolve, resolve + ",{\"id\":\"nerve\",\"name\":\"Nerve\",\"min\":0,\"max\":"
                + max + "}").replace("\"resolve\":\"might\"", resolveMapping));
        return bytes(files);
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
