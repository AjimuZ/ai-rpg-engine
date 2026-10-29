package org.rpgengine.quests;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.rpgengine.characters.ArmoryPack;

/**
 * Test fixture: the armory pack of P10.2 with a small quest chain added in memory (P10.3), so quests can
 * be exercised against items, locations and a hidden background quest on a real campaign without changing
 * the bundled fixture whose hash other suites pin.
 *
 * <p>The chain, by identity order: {@code gate} is discovered when the player reaches the grove and is
 * finished by holding two lamp oil; {@code shadow} is a hidden quest, discovered when {@code survey} is
 * completed, finished by holding fifteen coins and paying a relic; {@code supply} is available from the
 * start and is finished by holding four lamp oil; {@code survey} is available from the start, finished by
 * visiting the grove, and pays ten coins and a potion exactly once.
 */
public final class QuestPack {
    private static final String QUESTS = """
            {"schemaVersion":1,"definitions":[
            {"id":"gate","kind":"quest","data":{"name":"Light the Gate","summary":"Bring oil to the gate lamp.",
              "initialStatus":"undiscovered","availableWhen":{"visited":"grove"},
              "objectives":[{"id":"gather","description":"Hold two flasks of oil.","when":{"possesses":{"item":"lamp-oil","quantity":2}}}],
              "outcomes":[{"id":"lit","kind":"success","when":{"objective":{"quest":"gate","id":"gather"}},
                "settlement":{"rewards":[{"item":"coin","quantity":5}],"consequences":["The gate lamp burns."]}}]}},
            {"id":"shadow","kind":"quest","data":{"name":"Hidden Purse","summary":"Never shown to the player.",
              "visibility":"secret","initialStatus":"undiscovered",
              "availableWhen":{"questStatus":{"quest":"survey","status":"completed"}},
              "objectives":[{"id":"wealthy","description":"Hold fifteen coins.","when":{"possesses":{"item":"coin","quantity":15}}}],
              "outcomes":[{"id":"found","kind":"success","when":{"objective":{"quest":"shadow","id":"wealthy"}},
                "settlement":{"rewards":[{"item":"relic","quantity":1}]}}]}},
            {"id":"supply","kind":"quest","data":{"name":"Stock the Shelter","summary":"Keep four flasks of oil.",
              "initialStatus":"available",
              "objectives":[{"id":"stock","description":"Hold four flasks of oil.","when":{"possesses":{"item":"lamp-oil","quantity":4}}}],
              "outcomes":[
                {"id":"spoiled","kind":"failure","when":{"visited":"nowhere-yet"},"description":"Unused."},
                {"id":"stocked","kind":"success","when":{"objective":{"quest":"supply","id":"stock"}},
                "settlement":{"rewards":[{"item":"coin","quantity":4},{"item":"coin","quantity":3}]}}]}},
            {"id":"survey","kind":"quest","data":{"name":"Find the Waystone","summary":"Visit the grove.",
              "initialStatus":"available",
              "objectives":[
                {"id":"reach","description":"Reach the grove.","when":{"visited":"grove"}},
                {"id":"provisions","description":"Carry two potions.","optional":true,"when":{"possesses":{"item":"potion","quantity":2}}}],
              "outcomes":[{"id":"surveyed","kind":"success","when":{"objective":{"quest":"survey","id":"reach"}},
                "settlement":{"rewards":[{"item":"coin","quantity":10},{"item":"potion","quantity":1}],
                  "consequences":["The waystone is known."]}}]}}]}""";

    private QuestPack() {
    }

    /**
     * Build the variant's files: the armory pack with the chain above in place of the bundled quests.
     * The unreachable {@code nowhere-yet} location in {@code supply} is replaced by an impossible
     * conjunction so the file stays valid while that failure outcome can never hold.
     *
     * @return file index ready for staging
     */
    public static Map<String, byte[]> files() throws URISyntaxException {
        var files = new java.util.TreeMap<>(ArmoryPack.files());
        String quests = QUESTS.replace("{\"visited\":\"nowhere-yet\"}",
                "{\"all\":[{\"visited\":\"grove\"},{\"not\":{\"visited\":\"grove\"}}]}");
        files.put("quests.json", quests.getBytes(StandardCharsets.UTF_8));
        return files;
    }
}
