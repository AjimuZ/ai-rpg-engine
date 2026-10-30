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
 *
 * <p>Declared actions (P11.2): {@code trail-ambush} lets the player taunt a hostile (social, intimidate, the default
 * Interact cost 1) and seal the yard's route to the escape zone from the yard (scene); {@code toll-parley} lets the
 * player demand passage from a hostile (social, negotiate), ask the allied keeper for cover (social, request, 2 AP)
 * and unbar the blocked gate from the gate zone (scene). {@code grove-watch} declares none, so its frozen definition
 * keeps the P11.1 shape.
 *
 * <p>Attacks (P11.3): the sword is a {@code 1d6 + force} blade trained by {@code bladework} and the cloak gives armor 1, so
 * the surveyor fights like the worked fight of docs/20. A {@code bolt-caster} (off hand, range 1, {@code 1d6 + finesse},
 * one unit of {@code arrows} per attempt), a {@code salve} (heals its user 3), a {@code bandage} (heals a single target 4),
 * a {@code storm-tonic} with more dice than a fight allows, a {@code gift-pouch} that creates a coin for its target and a
 * {@code flare} whose damage effect stays unsupported complete the kit. {@code yard-duel} is the worked fight: an
 * armed {@code bandit} with the statistics of docs/20, whose cudgel permits no strong hit, so no single blow can fell the
 * surveyor's full Health of 8 before its first decision. {@code close-brawl} (opening {@code initiative}) and
 * {@code close-rush} (opening {@code first}) put unarmed targets in the player's own zone: {@code dummy} is always struck
 * hard (Guard 0, 999 Health), {@code wall} can never be hit (Guard 40) and {@code porter} is an ally who can be healed.
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
                {"id":"slip-away","name":"Slip away","kind":"escape"}],
              "actions":[{"id":"taunt","name":"Taunt","kind":"social","intent":"intimidate"},
                {"id":"seal-route","name":"Seal the route","kind":"scene","zone":"yard",
                "passage":{"from":"yard","to":"escape-route","blocked":true}}]}},
            {"id":"toll-parley","kind":"encounter","data":{"name":"Toll Parley","location":"trailhead","opening":"forbidden",
              "zones":[{"id":"gate","name":"Gate"},{"id":"booth","name":"Booth","visibility":"obscured"}],
              "edges":[{"from":"gate","to":"booth","blocked":true}],
              "playerZone":"gate",
              "participants":[{"id":"keeper","name":"Keeper","side":"ally","zone":"gate","npc":"warden",
                "stats":{"health":10,"guard":11,"resistance":10}},
                {"id":"smuggler","name":"Smuggler","side":"hostile","zone":"booth","threat":"minor",
                "stats":{"health":8,"guard":10,"resistance":9,"finesse":2}}],
              "objectives":[{"id":"hold","kind":"survive","rounds":3},{"id":"keep-keeper","kind":"protect","participant":"keeper"}],
              "actions":[{"id":"demand-passage","name":"Demand passage","kind":"social","intent":"negotiate"},
                {"id":"ask-keeper","name":"Ask the keeper for cover","kind":"social","intent":"request","recipients":"ally",
                "cost":2},
                {"id":"unbar-gate","name":"Unbar the gate","kind":"scene","zone":"gate",
                "passage":{"from":"booth","to":"gate","blocked":false}}]}},
            {"id":"grove-watch","kind":"encounter","data":{"name":"Grove Watch","location":"grove","opening":"first",
              "zones":[{"id":"clearing","name":"Clearing"},{"id":"stones","name":"Stones"}],
              "edges":[{"from":"clearing","to":"stones"}],"playerZone":"clearing",
              "participants":[{"id":"wolf","name":"Wolf","side":"hostile","zone":"stones","threat":"minor",
                "stats":{"health":9,"guard":11,"resistance":9,"force":2,"finesse":2}},
                {"id":"hound","name":"Hound","side":"ally","zone":"clearing","threat":"minor",
                "stats":{"health":7,"guard":11,"resistance":8,"finesse":2}}],
              "objectives":[{"id":"reach-stones","kind":"reachZone","zone":"stones"}]}},
            {"id":"yard-duel","kind":"encounter","data":{"name":"Yard Duel","location":"trailhead",
              "zones":[{"id":"entry","name":"Entry"},{"id":"yard","name":"Yard"},{"id":"escape-route","name":"Escape Route"}],
              "edges":[{"from":"entry","to":"yard"},{"from":"entry","to":"escape-route"},{"from":"yard","to":"escape-route"}],
              "playerZone":"entry","escapeZones":["escape-route"],
              "participants":[{"id":"bandit","name":"Bandit","side":"hostile","zone":"yard","threat":"standard",
                "stats":{"health":14,"guard":12,"resistance":11,"armor":1,"force":1,"finesse":1,"insight":1,"resolve":1},
                "attacks":[{"id":"cudgel","attribute":"force","skill":1,"strongHit":false,"damage":{"count":1,"sides":6}}]}],
              "objectives":[{"id":"drive-off","kind":"defeatHostiles"}]}},
            {"id":"close-brawl","kind":"encounter","data":{"name":"Close Brawl","location":"trailhead",
              "zones":[{"id":"ring","name":"Ring"},{"id":"gallery","name":"Gallery"}],
              "edges":[{"from":"ring","to":"gallery"}],"playerZone":"ring",
              "participants":[{"id":"dummy","name":"Dummy","side":"hostile","zone":"ring",
                "stats":{"health":999,"guard":0,"resistance":0}},
                {"id":"wall","name":"Wall","side":"hostile","zone":"ring","stats":{"health":20,"guard":40,"resistance":40}},
                {"id":"porter","name":"Porter","side":"ally","zone":"ring","stats":{"health":9,"guard":11,"resistance":8}},
                {"id":"lookout","name":"Lookout","side":"ally","zone":"gallery","stats":{"health":9,"guard":11,"resistance":8}}],
              "objectives":[{"id":"last","kind":"survive","rounds":3}]}},
            {"id":"close-rush","kind":"encounter","data":{"name":"Close Rush","location":"trailhead","opening":"first",
              "zones":[{"id":"ring","name":"Ring"}],"playerZone":"ring",
              "participants":[{"id":"dummy","name":"Dummy","side":"hostile","zone":"ring",
                "stats":{"health":999,"guard":0,"resistance":0}}],
              "objectives":[{"id":"last","kind":"survive","rounds":3}]}}]}""";

    /** Combat data of the fixture's armory items, added in memory by item ID. */
    private static final Map<String, String> ITEM_COMBAT = Map.of(
            "sword", """
                    "combat":{"attack":{"attribute":"force","skill":"bladework","damage":{"count":1,"sides":6}}},""",
            "cloak", """
                    "combat":{"armor":1},""");

    /** Items only the combat fixture needs. */
    private static final String COMBAT_ITEMS = """
            ,{"id":"bolt-caster","kind":"item","data":{"name":"Bolt Caster","description":"Throws bolts.","itemType":"tool",
              "slot":"off-hand","hands":1,"tags":["light"],"rarity":"ordinary",
              "combat":{"attack":{"attribute":"finesse","damage":{"count":1,"sides":6},"range":1,"ammunition":"arrows"}},
              "appearance":{"id":"caster.brass","summary":"A brass caster."}}},
            {"id":"salve","kind":"item","data":{"name":"Salve","description":"Closes wounds.","itemType":"consumable","maxStack":5,
              "rarity":"ordinary","use":{"target":{"policy":"self"},"effects":[{"type":"heal","amount":3,"target":"self"}]},
              "appearance":{"id":"salve.tin","summary":"A tin of salve."}}},
            {"id":"bandage","kind":"item","data":{"name":"Bandage","description":"Binds a wound.","itemType":"consumable","maxStack":5,
              "rarity":"ordinary","use":{"target":{"policy":"single"},"effects":[{"type":"heal","amount":4,"target":"target"}]},
              "appearance":{"id":"bandage.linen","summary":"A linen strip."}}},
            {"id":"storm-tonic","kind":"item","data":{"name":"Storm Tonic","description":"Too many dice.","itemType":"consumable",
              "maxStack":5,"rarity":"ordinary","use":{"target":{"policy":"self"},"effects":[
              {"type":"heal","target":"self","amount":{"add":[{"dice":{"count":20,"sides":2}},{"dice":{"count":20,"sides":2}}]}},
              {"type":"heal","target":"self","amount":{"add":[{"dice":{"count":20,"sides":2}},{"dice":{"count":5,"sides":2}}]}}]},"appearance":{"id":"tonic.storm","summary":"A humming flask."}}},
            {"id":"gift-pouch","kind":"item","data":{"name":"Gift Pouch","description":"Hands over a coin.","itemType":"consumable",
              "maxStack":5,"rarity":"ordinary","use":{"target":{"policy":"single"},
              "effects":[{"type":"createItem","item":"coin","target":"target"}]},
              "appearance":{"id":"pouch.gift","summary":"A small pouch."}}}""";

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
        String items = new String(files.get("items.json"), StandardCharsets.UTF_8);
        for (var combat : ITEM_COMBAT.entrySet()) {
            String head = "{\"id\":\"" + combat.getKey() + "\",\"kind\":\"item\",\"data\":{";
            items = items.replace(head, head + combat.getValue());
        }
        int last = items.lastIndexOf("]}");
        files.put("items.json", (items.substring(0, last) + COMBAT_ITEMS + items.substring(last))
                .getBytes(StandardCharsets.UTF_8));
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
