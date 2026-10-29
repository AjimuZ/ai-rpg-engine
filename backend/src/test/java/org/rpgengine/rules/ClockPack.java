package org.rpgengine.rules;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import org.rpgengine.characters.ArmoryPack;

/**
 * Test fixture: the armory pack of P10.2 with the world clock of P10.4 added in memory, so travel durations,
 * rest policies and a clock-driven quest can be exercised on a real campaign without changing the bundled
 * fixture whose hash other suites pin.
 *
 * <p>Locations: {@code trailhead} (tag {@code shelter}) links to {@code grove} in 45 minutes, to {@code camp}
 * (tag {@code wild}) in 120 minutes and to {@code far-pass} in a full day (1,440 minutes, the longest
 * legal link); the way back takes 30, 120 and 1,440 minutes. Rest policies: {@code catch-breath} (10 minutes,
 * anywhere, restores 2 vigor), {@code night} (8 hours, only where a {@code shelter} tag is present, restores
 * 8 vigor and ends {@code dazed} and {@code rattled}), {@code uneasy} (8 hours, a 99% chance of interruption,
 * restores 8 vigor and ends {@code dazed}) and {@code remedy} (1 hour, ends {@code steadfast} only). Quest
 * {@code vigil} is available from the start and completes once eight whole hours of world time have passed
 * after it was accepted, paying three coins.
 */
public final class ClockPack {
    private static final String LOCATIONS = """
            {"schemaVersion":1,"definitions":[
            {"id":"trailhead","kind":"location","data":{"name":"Mosslight Trailhead","description":"A footpath.",
              "tags":["shelter"],"links":[{"to":"grove","accessible":true,"minutes":45},
              {"to":"camp","accessible":true,"minutes":120},{"to":"far-pass","accessible":true,"minutes":1440}]}},
            {"id":"grove","kind":"location","data":{"name":"Lantern Grove","description":"Pale flowers.",
              "links":[{"to":"trailhead","accessible":true,"minutes":30}]}},
            {"id":"camp","kind":"location","data":{"name":"Windy Camp","description":"An open clearing.",
              "tags":["wild"],"links":[{"to":"trailhead","accessible":true,"minutes":120}]}},
            {"id":"far-pass","kind":"location","data":{"name":"Far Pass","description":"A day away.",
              "links":[{"to":"trailhead","accessible":true,"minutes":1440}]}}]}""";

    private static final String REST_RULE = """
            {"id":"rules.rest","kind":"rule","data":{"ruleType":"rest","policies":[
              {"id":"catch-breath","name":"Catch breath","minutes":10,"restores":[{"resource":"vigor","amount":2}]},
              {"id":"night","name":"Night's rest","minutes":480,"locationTag":"shelter",
                "restores":[{"resource":"vigor","amount":8}],"removesConditions":["dazed","rattled"]},
              {"id":"uneasy","name":"Uneasy camp","minutes":480,"interruptionChancePercent":99,
                "restores":[{"resource":"vigor","amount":8}],"removesConditions":["dazed"]},
              {"id":"remedy","name":"Remedy","minutes":60,"removesConditions":["steadfast"]}]}}""";

    private static final String QUESTS = """
            {"schemaVersion":1,"definitions":[
            {"id":"vigil","kind":"quest","data":{"name":"Keep Watch","summary":"Let the night pass.",
              "initialStatus":"available",
              "objectives":[{"id":"dawn","description":"Let eight hours pass.",
                "when":{"ge":[{"worldTime":"hours"},8]}}],
              "outcomes":[{"id":"morning","kind":"success","when":{"objective":{"quest":"vigil","id":"dawn"}},
                "settlement":{"rewards":[{"item":"coin","quantity":3}]}}]}}]}""";

    private ClockPack() {
    }

    /**
     * Build the variant's files: the armory pack with the locations, rest rule and quest above.
     *
     * @return file index ready for staging
     */
    public static Map<String, byte[]> files() throws URISyntaxException {
        var files = new TreeMap<>(ArmoryPack.files());
        String rules = new String(files.get("rules.json"), StandardCharsets.UTF_8);
        int end = rules.lastIndexOf("]}");
        files.put("rules.json", (rules.substring(0, end) + "," + REST_RULE + rules.substring(end))
                .getBytes(StandardCharsets.UTF_8));
        files.put("locations.json", LOCATIONS.getBytes(StandardCharsets.UTF_8));
        files.put("quests.json", QUESTS.getBytes(StandardCharsets.UTF_8));
        return files;
    }
}
