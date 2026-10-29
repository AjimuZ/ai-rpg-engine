package org.rpgengine.characters;

import java.io.ByteArrayOutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.rpgengine.content.PackStaging;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test fixture: the neutral {@code fantasy-exploration} pack with P10.1 character rules added in
 * memory (attributes, derived statistics, rarity tiers, equipment slots, skills, learnable abilities,
 * conditions and an equipped starting loadout). Since P10.2 several items carry a trade {@code value}
 * and use contracts (a public warden NPC, a potion, a reusable oil press, a purse that creates coins and oil, a flare whose
 * damage effect is not executable yet). The bundled fixture on disk is untouched, so the other suites
 * that pin its hash keep their expectations.
 */
public final class ArmoryPack {
    private static final String RULES = """
            {"schemaVersion":1,"definitions":[
            {"id":"rules.attributes","kind":"rule","data":{"ruleType":"attributes","attributes":[
              {"id":"awareness","name":"Awareness","min":0,"max":4},{"id":"might","name":"Might","min":0,"max":4},
              {"id":"resolve","name":"Resolve","min":0,"max":4}]}},
            {"id":"rules.resources","kind":"rule","data":{"ruleType":"resources","resources":[{"id":"vigor","name":"Vigor","max":8}]}},
            {"id":"rules.rarity","kind":"rule","data":{"ruleType":"rarityTiers","tiers":[
              {"id":"ordinary","name":"Ordinary","token":"tier-ordinary"},{"id":"rare","name":"Rare","token":"tier-rare"},
              {"id":"legendary","name":"Legendary","token":"tier-legendary"}]}},
            {"id":"rules.equipment","kind":"rule","data":{"ruleType":"equipment","hands":2,"slots":[
              {"id":"main-hand","name":"Main hand","capacity":1,"accepts":["weapon","tool"]},
              {"id":"off-hand","name":"Off hand","capacity":1,"accepts":["shield","light"]},
              {"id":"body","name":"Body"},
              {"id":"trinket","name":"Trinket","capacity":2}]}},
            {"id":"rules.derived","kind":"rule","data":{"ruleType":"derivedStats","stats":[
              {"id":"guard","name":"Guard","min":0,"max":11,"formula":{"add":[{"attribute":"might"},{"attribute":"awareness"},{"skill":"bladework"}]}},
              {"id":"haul","name":"Haul","min":2,"max":10,"formula":{"mul":[{"add":[{"attribute":"might"},1]},2]}},
              {"id":"focus","name":"Focus","min":0,"max":8,"formula":{"add":[{"attribute":"awareness"},{"attribute":"resolve"}]}}]}}]}""";

    private static final String SKILLS = """
            {"schemaVersion":1,"definitions":[
            {"id":"fieldwork","kind":"skill","data":{"name":"Trailcraft","description":"Read paths.","attribute":"awareness","maxRank":3}},
            {"id":"bladework","kind":"skill","data":{"name":"Bladework","description":"Fight with blades.","attribute":"might","maxRank":3}}]}""";

    private static final String ABILITIES = """
            {"schemaVersion":1,"definitions":[
            {"id":"steady","kind":"ability","data":{"name":"Catch Breath","description":"Recover.","target":{"policy":"self"},
              "effects":[{"type":"modifyResource","resource":"vigor","amount":1}]}},
            {"id":"riposte","kind":"ability","data":{"name":"Riposte","description":"Answer a blow.","target":{"policy":"self"},
              "prerequisites":{"skills":[{"skill":"bladework","rank":2}],"abilities":["steady"]},
              "effects":[{"type":"modifyResource","resource":"vigor","amount":1}]}},
            {"id":"chain-riposte","kind":"ability","data":{"name":"Chain Riposte","description":"Answer twice.","target":{"policy":"self"},
              "prerequisites":{"abilities":["riposte"]},"effects":[{"type":"modifyResource","resource":"vigor","amount":1}]}},
            {"id":"master-riposte","kind":"ability","data":{"name":"Master Riposte","description":"Answer all.","target":{"policy":"self"},
              "prerequisites":{"skills":[{"skill":"bladework","rank":3}]},"effects":[{"type":"modifyResource","resource":"vigor","amount":1}]}}]}""";

    private static final String CONDITIONS = """
            {"schemaVersion":1,"definitions":[
            {"id":"dazed","kind":"condition","data":{"name":"Dazed","description":"Unsteady.","stacking":"refresh","durationRounds":3,
              "modifiers":[{"attribute":"awareness","amount":-2}]}},
            {"id":"rattled","kind":"condition","data":{"name":"Rattled","description":"Shaken.","stacking":"stack","maxStacks":3,"durationRounds":4,
              "modifiers":[{"attribute":"might","amount":-1},{"attribute":"awareness","amount":-1}]}},
            {"id":"steadfast","kind":"condition","data":{"name":"Steadfast","description":"Braced.","stacking":"none","durationRounds":5,
              "modifiers":[{"attribute":"might","amount":1}]}}]}""";

    private static final String ITEMS = """
            {"schemaVersion":1,"definitions":[
            {"id":"kit","kind":"item","data":{"name":"Trail Kit","description":"A pouch of tools.","itemType":"tool","maxStack":1,
              "rarity":"ordinary","appearance":{"id":"kit.canvas","summary":"A green pouch."}}},
            {"id":"sword","kind":"item","data":{"value":30,"name":"Sword","description":"Steel.","itemType":"weapon","slot":"main-hand","hands":1,
              "tags":["weapon"],"rarity":"ordinary","appearance":{"id":"sword.steel","summary":"A plain steel sword."}}},
            {"id":"greatsword","kind":"item","data":{"value":80,"name":"Greatsword","description":"Heavy.","itemType":"weapon","slot":"main-hand","hands":2,
              "tags":["weapon"],"rarity":"rare","appearance":{"id":"great.steel","summary":"A broad sword."}}},
            {"id":"shield","kind":"item","data":{"value":20,"name":"Shield","description":"Wood.","itemType":"armor","slot":"off-hand","hands":1,
              "tags":["shield"],"rarity":"ordinary","appearance":{"id":"shield.wood","summary":"A round shield."}}},
            {"id":"lantern","kind":"item","data":{"value":8,"name":"Lantern","description":"Light.","itemType":"tool","slot":"off-hand","hands":1,
              "tags":["light"],"rarity":"ordinary","appearance":{"id":"lantern.plain","summary":"A plain tin lantern."}}},
            {"id":"gilded-lantern","kind":"item","data":{"value":8,"name":"Gilded Lantern","description":"Light.","itemType":"tool","slot":"off-hand","hands":1,
              "tags":["light"],"rarity":"ordinary","appearance":{"id":"lantern.gilded","summary":"Ornate gold filigree set with gems and glowing runes."}}},
            {"id":"cloak","kind":"item","data":{"value":15,"name":"Cloak","description":"Wool.","itemType":"armor","slot":"body","rarity":"rare",
              "appearance":{"id":"cloak.wool","summary":"A grey cloak."}}},
            {"id":"ring","kind":"item","data":{"value":12,"name":"Ring","description":"Tin.","itemType":"treasure","slot":"trinket","rarity":"ordinary",
              "appearance":{"id":"ring.tin","summary":"A tin ring."}}},
            {"id":"arrows","kind":"item","data":{"value":1,"name":"Arrows","description":"Shafts.","itemType":"consumable","maxStack":20,
              "rarity":"ordinary","appearance":{"id":"arrows.bundle","summary":"A bundle."}}},
            {"id":"relic","kind":"item","data":{"name":"Relic","description":"Old.","itemType":"treasure","rarity":"legendary",
              "appearance":{"id":"relic.plain","summary":"A small grey stone."}}},
            {"id":"potion","kind":"item","data":{"name":"Potion","description":"Restores vigor.","itemType":"consumable","maxStack":5,
              "rarity":"ordinary","value":10,"use":{"target":{"policy":"self"},
              "effects":[{"type":"modifyResource","resource":"vigor","amount":4}]},
              "appearance":{"id":"potion.red","summary":"A red vial."}}},
            {"id":"elixir","kind":"item","data":{"name":"Elixir","description":"Heals a companion.","itemType":"consumable","maxStack":3,
              "rarity":"rare","value":25,"use":{"target":{"policy":"single"},
              "effects":[{"type":"modifyResource","resource":"vigor","amount":3,"target":"target"}]},
              "appearance":{"id":"elixir.blue","summary":"A blue vial."}}},
            {"id":"lamp-oil","kind":"item","data":{"name":"Lamp Oil","description":"Fuel.","itemType":"consumable","maxStack":10,
              "rarity":"ordinary","value":2,"appearance":{"id":"oil.flask","summary":"A flask of oil."}}},
            {"id":"coin","kind":"item","data":{"name":"Coin","description":"Currency.","itemType":"treasure","maxStack":999,
              "rarity":"ordinary","value":1,"appearance":{"id":"coin.brass","summary":"A brass coin."}}},
            {"id":"oil-press","kind":"item","data":{"name":"Oil Press","description":"Presses oil.","itemType":"tool",
              "rarity":"ordinary","value":25,"use":{"consumes":false,"target":{"policy":"self"},
              "effects":[{"type":"createItem","item":"lamp-oil","quantity":2}]},
              "appearance":{"id":"press.iron","summary":"An iron press."}}},
            {"id":"lucky-purse","kind":"item","data":{"name":"Lucky Purse","description":"Opens once.","itemType":"consumable",
              "maxStack":4,"rarity":"ordinary","value":6,"use":{"target":{"policy":"self"},"effects":[
              {"type":"createItem","item":"coin","quantity":50},{"type":"createItem","item":"lamp-oil","quantity":4}]},
              "appearance":{"id":"purse.felt","summary":"A felt purse."}}},
            {"id":"torch","kind":"item","data":{"name":"Torch","description":"Burns down.","itemType":"tool","slot":"off-hand","hands":1,
              "tags":["light"],"rarity":"ordinary","value":3,"use":{"consumes":true,"target":{"policy":"self"},
              "effects":[{"type":"modifyResource","resource":"vigor","amount":1}]},
              "appearance":{"id":"torch.pitch","summary":"A pitch torch."}}},
            {"id":"flare","kind":"item","data":{"name":"Flare","description":"Burns.","itemType":"consumable","maxStack":3,
              "rarity":"ordinary","value":4,"use":{"target":{"policy":"single"},"effects":[{"type":"damage","amount":1}]},
              "appearance":{"id":"flare.red","summary":"A red flare."}}}]}""";

    private static final String NPCS = """
            {"schemaVersion":1,"definitions":[
            {"id":"warden","kind":"npc","data":{"name":"Warden","role":"Trail keeper","location":"trailhead"}}]}""";

    private static final String CREATION = """
            {"schemaVersion":1,"definitions":[
            {"id":"traveler","kind":"creation","data":{"name":"Wayfarer","attributeBudget":6,"skillBudget":4,
              "attributes":["awareness","might"],"skills":["fieldwork","bladework"],
              "archetypes":[{"id":"surveyor","name":"Pathfinder","attributes":{"awareness":2,"might":2},
                "skills":{"fieldwork":1,"bladework":2}}],
              "startingItems":[{"item":"kit","quantity":1},{"item":"sword","quantity":1,"equipped":true},
                {"item":"shield","quantity":1,"equipped":true},{"item":"cloak","quantity":1,"equipped":true}],
              "startingAbilities":["steady"]}}]}""";

    private ArmoryPack() {
    }

    /**
     * Build the variant's files from the bundled fixture.
     *
     * @return file index ready for staging
     */
    public static Map<String, byte[]> files() throws URISyntaxException {
        var files = new TreeMap<>(new PackStaging(PackStaging.Limits.standard())
                .directory(Path.of(ArmoryPack.class.getResource("/packs/fantasy-exploration").toURI())).files());
        // Drop localization: this variant adds vocabulary the bundled translations do not cover.
        files.keySet().removeIf(name -> name.startsWith("locales/") || name.equals("text-resources.json"));
        files.put("rules.json", RULES.getBytes(StandardCharsets.UTF_8));
        files.put("skills.json", SKILLS.getBytes(StandardCharsets.UTF_8));
        files.put("abilities.json", ABILITIES.getBytes(StandardCharsets.UTF_8));
        files.put("conditions.json", CONDITIONS.getBytes(StandardCharsets.UTF_8));
        files.put("items.json", ITEMS.getBytes(StandardCharsets.UTF_8));
        files.put("creation.json", CREATION.getBytes(StandardCharsets.UTF_8));
        files.put("npcs.json", NPCS.getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = JsonMapper.builder().build().readValue(files.get("adventure.json"), Map.class);
        var listed = new ArrayList<Map<String, Object>>();
        for (Object entry : (List<?>) manifest.get("files")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> file = (Map<String, Object>) entry;
            String category = (String) file.get("category");
            if (!category.equals("localization") && !category.equals("textResources")) {
                listed.add(file);
            }
        }
        listed.add(Map.of("path", "conditions.json", "category", "conditions"));
        listed.add(Map.of("path", "npcs.json", "category", "npcs"));
        manifest.put("files", listed);
        manifest.put("id", "sample:armory");
        manifest.remove("textResources");
        manifest.remove("localizations");
        files.put("adventure.json", JsonMapper.builder().build().writeValueAsBytes(manifest));
        return files;
    }

    /** Zip a file map without compression, as the registry accepts it. */
    public static byte[] zip(Map<String, byte[]> files) throws java.io.IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.setLevel(0);
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
