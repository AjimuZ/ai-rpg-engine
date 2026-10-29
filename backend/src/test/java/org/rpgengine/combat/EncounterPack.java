package org.rpgengine.combat;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.rpgengine.characters.ArmoryPack;
import org.rpgengine.content.CampaignPackSnapshot;
import org.rpgengine.content.PackCompiler;
import org.rpgengine.content.PackParser;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test fixture: the armory pack of P10.2 with the P11.1 combat profile and three encounter templates added in
 * memory, so encounter entry can be exercised on a real campaign without changing the bundled fixture whose hash
 * other suites pin.
 *
 * <p>Combat rule: the default AI RPG Skirmish parameters, roles force and resolve on {@code might}, finesse and
 * insight on {@code awareness}, Health on {@code vigor}, no Focus. The surveyor archetype (awareness 2, might 2)
 * therefore freezes with Guard 12, Resistance 12 and initiative modifier 4, like the worked fight of docs/20.
 *
 * <p>Templates: {@code trail-ambush} at the trailhead (zones entry, yard and escape-route as in docs/20; one hostile
 * raider; defeat and escape objectives; default {@code initiative} opening), {@code toll-parley} at the trailhead
 * (opening {@code forbidden}; an allied keeper portraying the warden NPC and a hostile smuggler behind a blocked
 * edge; survive and protect objectives) and {@code grove-watch} in the grove (opening {@code first}; a hostile wolf,
 * an allied hound and a reach-zone objective). {@link #tuned()} is the same pack under another ID with a customized profile.
 */
public final class EncounterPack {
    /** The default combat rule of the fixture. */
    public static final String COMBAT_RULE = """
            {"id":"rules.combat","kind":"rule","data":{"ruleType":"combat",
              "roles":{"force":"might","finesse":"awareness","insight":"awareness","resolve":"might"},"health":"vigor"}}""";

    /** A customized profile: more action points, shorter rounds, a dearer strike and a tighter round cap. */
    public static final String TUNED_RULE = """
            {"id":"rules.combat","kind":"rule","data":{"ruleType":"combat","scheduler":"initiative-rounds","space":"zones",
              "roles":{"force":"might","finesse":"awareness","insight":"awareness","resolve":"might"},"health":"vigor",
              "values":{"actionPoints":4,"roundSeconds":6,"strikeCost":3,"roundCap":12,"guardBase":11}}}""";

    /** The encounter templates. */
    public static final String ENCOUNTERS = """
            {"schemaVersion":1,"definitions":[
            {"id":"trail-ambush","kind":"encounter","data":{"name":"Trail Ambush","description":"A raider waits in the yard.",
              "location":"trailhead",
              "zones":[{"id":"entry","name":"Entry"},{"id":"yard","name":"Yard","cover":"partial","capacity":4},
                {"id":"escape-route","name":"Escape Route","tags":["exit"]}],
              "edges":[{"from":"entry","to":"yard"},{"from":"entry","to":"escape-route","difficult":true},
                {"from":"yard","to":"escape-route"}],
              "playerZone":"entry","escapeZones":["escape-route"],
              "participants":[{"id":"raider","name":"Raider","side":"hostile","zone":"yard","threat":"standard",
                "stats":{"health":14,"guard":12,"resistance":11,"armor":1,"force":1,"finesse":1,"insight":1,"resolve":1}}],
              "objectives":[{"id":"drive-off","name":"Drive off the raider","kind":"defeatHostiles"},
                {"id":"slip-away","name":"Slip away","kind":"escape"}]}},
            {"id":"toll-parley","kind":"encounter","data":{"name":"Toll Parley","location":"trailhead","opening":"forbidden",
              "zones":[{"id":"gate","name":"Gate"},{"id":"booth","name":"Booth","visibility":"obscured"}],
              "edges":[{"from":"gate","to":"booth","blocked":true}],
              "playerZone":"gate",
              "participants":[{"id":"keeper","name":"Keeper","side":"ally","zone":"gate","npc":"warden",
                "stats":{"health":10,"guard":11,"resistance":10}},
                {"id":"smuggler","name":"Smuggler","side":"hostile","zone":"booth","threat":"minor",
                "stats":{"health":8,"guard":10,"resistance":9,"finesse":2}}],
              "objectives":[{"id":"hold","kind":"survive","rounds":3},{"id":"keep-keeper","kind":"protect","participant":"keeper"}]}},
            {"id":"grove-watch","kind":"encounter","data":{"name":"Grove Watch","location":"grove","opening":"first",
              "zones":[{"id":"clearing","name":"Clearing"},{"id":"stones","name":"Stones"}],
              "edges":[{"from":"clearing","to":"stones"}],"playerZone":"clearing",
              "participants":[{"id":"wolf","name":"Wolf","side":"hostile","zone":"stones","threat":"minor",
                "stats":{"health":9,"guard":11,"resistance":9,"force":2,"finesse":2}},
                {"id":"hound","name":"Hound","side":"ally","zone":"clearing","threat":"minor",
                "stats":{"health":7,"guard":11,"resistance":8,"finesse":2}}],
              "objectives":[{"id":"reach-stones","kind":"reachZone","zone":"stones"}]}}]}""";

    private EncounterPack() {
    }

    /**
     * Build the default variant: the armory pack with the combat rule and encounters above.
     *
     * @return file index ready for staging
     */
    public static Map<String, byte[]> files() throws URISyntaxException {
        return variant(COMBAT_RULE, null);
    }

    /**
     * Build the customized variant under the pack ID {@code sample:armory-tuned}.
     *
     * @return file index ready for staging
     */
    public static Map<String, byte[]> tuned() throws URISyntaxException {
        return variant(TUNED_RULE, "sample:armory-tuned");
    }

    /**
     * Compile a fixture variant in memory and project its combat rules.
     *
     * @param files a variant from {@link #files()} or {@link #tuned()}
     * @return the projected combat rules
     */
    public static CombatRules rules(Map<String, byte[]> files) {
        return CombatRules.from(CampaignPackSnapshot.local(new PackCompiler().compile(new PackParser().parse(files))));
    }

    /** Add a combat rule, the encounters file and its manifest entry; optionally rename the pack. */
    private static Map<String, byte[]> variant(String combatRule, String packId) throws URISyntaxException {
        var files = new TreeMap<>(ArmoryPack.files());
        String rules = new String(files.get("rules.json"), StandardCharsets.UTF_8);
        int end = rules.lastIndexOf("]}");
        files.put("rules.json", (rules.substring(0, end) + "," + combatRule + rules.substring(end))
                .getBytes(StandardCharsets.UTF_8));
        files.put("encounters.json", ENCOUNTERS.getBytes(StandardCharsets.UTF_8));
        var json = JsonMapper.builder().build();
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = json.readValue(files.get("adventure.json"), Map.class);
        var listed = new ArrayList<Object>((List<?>) manifest.get("files"));
        listed.add(Map.of("path", "encounters.json", "category", "encounters"));
        manifest.put("files", listed);
        if (packId != null) {
            manifest.put("id", packId);
        }
        files.put("adventure.json", json.writeValueAsBytes(manifest));
        return files;
    }
}
