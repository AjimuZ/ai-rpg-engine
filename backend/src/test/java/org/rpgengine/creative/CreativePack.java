package org.rpgengine.creative;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.rpgengine.combat.EncounterPack;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test fixture (P15.4-B): the P11.4 tactics pack of {@link EncounterPack#tactics()} under the pack ID
 * {@code sample:armory-creative}, with the {@code creativeAbilities} module enabled and one {@code ruleType: creative} rule
 * added in memory, so drafts can be used on a real campaign without changing a bundled fixture.
 *
 * <p>The default {@link #RULE} is a standard profile: at most two steps of damage or an applied effect, two targets, range 1,
 * two dice, a flat part of 2, the effects {@code winded} (until the holder's next activation) and {@code warded} (persistent,
 * so a standard profile refuses to apply it), a budget of 8, two action points, and one Focus and one cooldown round per
 * complexity point. Using a draft needs the {@code chain-riposte} ability learned. The tactics pack's Focus is {@code grit}.
 */
public final class CreativePack {
    /** The pack ID of every variant. */
    public static final String PACK = "sample:armory-creative";
    /** The default creative rule. */
    public static final String RULE = """
            {"id":"rules.creative","kind":"rule","data":{"ruleType":"creative","maxOperations":2,"maxTargets":2,"maxRange":1,
              "maxDice":2,"maxFlat":2,"kinds":["damage","apply"],"effects":["winded","warded"],"maxEffectCount":2,
              "complexityBudget":8,"actionPoints":2,"focusPerComplexity":1,"cooldownPerComplexity":1,
              "prerequisites":["chain-riposte"]}}""";

    private CreativePack() {
    }

    /**
     * Build the default variant: the module enabled and {@link #RULE} declared.
     *
     * @return file index ready for staging
     */
    public static Map<String, byte[]> files() throws URISyntaxException {
        return files(true, RULE);
    }

    /**
     * Build a variant.
     *
     * @param module whether the manifest enables the {@code creativeAbilities} module
     * @param rules rule definitions to add to {@code rules.json}, each a JSON object; none for a pack without a profile
     * @return file index ready for staging
     */
    public static Map<String, byte[]> files(boolean module, String... rules) throws URISyntaxException {
        var files = new TreeMap<>(EncounterPack.tactics());
        String declared = new String(files.get("rules.json"), StandardCharsets.UTF_8);
        int end = declared.lastIndexOf("]}");
        files.put("rules.json", (declared.substring(0, end) + (rules.length == 0 ? "" : "," + String.join(",", rules))
                + declared.substring(end)).getBytes(StandardCharsets.UTF_8));
        var json = JsonMapper.builder().build();
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = json.readValue(files.get("adventure.json"), Map.class);
        manifest.put("id", PACK);
        var modules = new ArrayList<Object>((List<?>) manifest.get("modules"));
        if (module) {
            modules.add(Map.of("id", "creativeAbilities"));
        }
        manifest.put("modules", modules);
        files.put("adventure.json", json.writeValueAsBytes(manifest));
        return files;
    }
}
