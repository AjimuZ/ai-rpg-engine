package org.rpgengine.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rpgengine.combat.CombatAbility.Kind;
import org.rpgengine.combat.EncounterPack;
import org.rpgengine.content.CampaignPackSnapshot;
import org.rpgengine.content.PackCompiler;
import org.rpgengine.content.PackException;
import org.rpgengine.content.PackParser;
import org.rpgengine.creative.CreativePack;
import org.rpgengine.creative.CreativeProfile;
import org.rpgengine.rules.CreativeBounds.Power;

/**
 * Creative profiles as pack data (P15.4-B, docs/09 and docs/10 section 9): a {@code ruleType: creative} rule compiles only in a
 * pack that enables the {@code creativeAbilities} module, once, with every bound inside a frozen ability's and the power tier's
 * joint rules, and the command catalog projects it with canonical effect and prerequisite IDs and the compiler's defaults.
 */
class CreativeRulesTest {
    /** Construct a stateless fixture. */
    CreativeRulesTest() {
    }

    /** The default rule projects to exactly the profile it declares; a pack without one has none. */
    @Test
    void aCreativeRuleProjectsToItsProfile() throws Exception {
        var catalog = catalog(CreativePack.files());
        assertEquals(Map.of(CreativePack.PACK, new CreativeProfile("rules.creative", Power.STANDARD, 2, 2, 1, 2, 2,
                EnumSet.of(Kind.DAMAGE, Kind.APPLY), Set.of("winded", "warded"), 2, false, 8, 2, 1, 1,
                Set.of("chain-riposte"))), catalog.creative());
        assertEquals(Map.of(), catalog(EncounterPack.tactics()).creative(), "no module, no profile");
        // Defaults: standard power, range, flat and effect count 0, no persistent effects, no effects or prerequisites.
        var minimal = catalog(CreativePack.files(true, rule("\"maxOperations\":1,\"maxTargets\":1,\"maxDice\":1,"
                + "\"kinds\":[\"damage\"],\"complexityBudget\":4,\"actionPoints\":1,\"focusPerComplexity\":1,"
                + "\"cooldownPerComplexity\":0")));
        assertEquals(new CreativeProfile("rules.creative", Power.STANDARD, 1, 1, 0, 1, 0, EnumSet.of(Kind.DAMAGE), Set.of(), 0,
                false, 4, 1, 1, 0, Set.of()), minimal.creative().get(CreativePack.PACK));
        // A high-power profile may charge nothing and apply persistent effects, with a budget above 16.
        var high = catalog(CreativePack.files(true, rule("\"power\":\"high\",\"maxOperations\":4,\"maxTargets\":9,"
                + "\"maxDice\":16,\"kinds\":[\"damage\",\"heal\",\"apply\",\"remove\"],\"effects\":[\"warded\"],"
                + "\"persistentEffects\":true,\"complexityBudget\":32,\"actionPoints\":3,\"focusPerComplexity\":0,"
                + "\"cooldownPerComplexity\":0")));
        var profile = high.creative().get(CreativePack.PACK);
        assertEquals(Power.HIGH, profile.power());
        assertTrue(profile.zeroCost());
        assertTrue(profile.persistentEffects());
    }

    /** Each rule of the compiler is a located diagnostic with its code. */
    @Test
    void theCompilerRefusesProfilesOutsideTheirBounds() throws Exception {
        assertEquals("MODULE_DISABLED", failure(CreativePack.files(false, CreativePack.RULE)));
        assertEquals("DUPLICATE", failure(CreativePack.files(true, CreativePack.RULE,
                CreativePack.RULE.replace("rules.creative", "rules.creative-2"))));
        String valid = "\"maxOperations\":2,\"maxTargets\":2,\"maxDice\":2,\"kinds\":[\"damage\"],\"complexityBudget\":8,"
                + "\"actionPoints\":2,\"focusPerComplexity\":1,\"cooldownPerComplexity\":1";
        assertEquals("SCHEMA", failure(CreativePack.files(true, rule(valid.replace("\"damage\"", "\"teleport\"")))));
        assertEquals("SCHEMA", failure(CreativePack.files(true, rule(valid.replace("\"kinds\":[\"damage\"],", "")))));
        assertEquals("SCHEMA", failure(CreativePack.files(true, rule(valid.replace("\"maxOperations\":2", "\"maxOperations\":5")))));
        assertEquals("SCHEMA", failure(CreativePack.files(true, rule(valid.replace("\"actionPoints\":2", "\"actionPoints\":4")))),
                "more points than the combat rule's activation grants");
        assertEquals("SCHEMA", failure(CreativePack.files(true, rule(valid.replace(",\"cooldownPerComplexity\":1", "")))),
                "both rates are required, so a cost is always stated");
        assertEquals("SCHEMA", failure(CreativePack.files(true, rule(valid + ",\"cost\":1"))), "no other member");
        assertEquals("CONSTRAINT_CONFLICT", failure(CreativePack.files(true, rule(valid + ",\"effects\":[\"dazed\"]"))),
                "a condition without a combat block cannot be frozen");
        assertEquals("REFERENCE_UNKNOWN", failure(CreativePack.files(true, rule(valid + ",\"prerequisites\":[\"flight\"]"))));
        assertEquals("CONSTRAINT_CONFLICT", failure(CreativePack.files(true, rule(valid.replace("\"focusPerComplexity\":1",
                "\"focusPerComplexity\":0").replace("\"cooldownPerComplexity\":1", "\"cooldownPerComplexity\":0")))),
                "zero cost needs power high");
        assertEquals("CONSTRAINT_CONFLICT", failure(CreativePack.files(true, rule(valid + ",\"persistentEffects\":true"))));
        assertEquals("CONSTRAINT_CONFLICT", failure(CreativePack.files(true, rule(valid.replace("\"complexityBudget\":8",
                "\"complexityBudget\":17")))));
        assertEquals("CONSTRAINT_CONFLICT", failure(CreativePack.files(true, rule("\"power\":\"high\","
                + valid.replace("\"complexityBudget\":8", "\"complexityBudget\":32").replace("\"focusPerComplexity\":1",
                "\"focusPerComplexity\":4")))), "32 times 4 Focus exceeds the largest cost of 99");
        var configured = CreativePack.files();
        configured.put("adventure.json", new String(configured.get("adventure.json"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("{\"id\":\"creativeAbilities\"}", "{\"id\":\"creativeAbilities\",\"configuration\":\"rules.json\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("CONSTRAINT_CONFLICT", failure(configured), "the module takes no configuration file");
    }

    /** @return a creative rule with these members */
    private static String rule(String members) {
        return "{\"id\":\"rules.creative\",\"kind\":\"rule\",\"data\":{\"ruleType\":\"creative\"," + members + "}}";
    }

    private static CommandRuleCatalog catalog(Map<String, byte[]> files) {
        return CommandRuleCatalog.from(UUID.randomUUID(),
                CampaignPackSnapshot.local(new PackCompiler().compile(new PackParser().parse(files))));
    }

    private static String failure(Map<String, byte[]> files) {
        return assertThrows(PackException.class, () -> new PackCompiler().compile(new PackParser().parse(files))).code();
    }
}
